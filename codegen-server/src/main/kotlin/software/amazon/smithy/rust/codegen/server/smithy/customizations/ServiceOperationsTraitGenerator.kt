/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.customizations

import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustReservedWords
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.docs
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.HttpVersion
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.util.toPascalCase
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol

/**
 * Renders a service-implementor trait `<ServiceName>Operations` plus a free
 * `into_router(impl, config)` function into a new `service_impl` module.
 *
 * Each operation becomes one default-implemented async method on the trait
 * whose body is `panic!("operation not implemented: <Service>.<Operation>")`.
 * Implementors override only the operations they care about; everything else
 * panics at the call site, making missing implementations visible.
 *
 * `into_router` takes the impl + a `<ServiceName>Config` and returns the
 * concrete protocol-specific routing service — same type smithy-rs's existing
 * builder produces. State lives on the impl struct itself (Axum-style): the
 * trait method receiver is `&self`, and mutation goes through interior
 * mutability on fields (e.g. `Arc<Mutex<_>>`, `Arc<AtomicU64>`).
 */
class ServiceOperationsTraitGenerator(
    private val codegenContext: ServerCodegenContext,
    private val protocol: ServerProtocol,
    private val rustCrate: RustCrate,
) {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val model = codegenContext.model
    private val symbolProvider = codegenContext.symbolProvider
    private val service = codegenContext.serviceShape
    private val serviceName = service.id.name.toPascalCase()
    private val traitName = "${serviceName}Operations"
    private val builderName = "${serviceName}Builder"
    private val configName = "${serviceName}Config"
    private val smithyHttpServer = ServerCargoDependency.smithyHttpServer(runtimeConfig).toType()

    private val operations: List<OperationShape> =
        TopDownIndex.of(model)
            .getContainedOperations(service)
            .toSortedSet(compareBy { it.id })
            .toList()

    private val operationFieldNames: Map<OperationShape, String> =
        operations.associateWith { RustReservedWords.escapeIfNeeded(symbolProvider.toSymbol(it).name.toSnakeCase()) }

    private val operationStructNames: Map<OperationShape, String> =
        operations.associateWith { symbolProvider.toSymbol(it).name.toPascalCase() }

    private fun defaultRequestBodyType(): RuntimeType =
        when (runtimeConfig.httpVersion) {
            HttpVersion.Http1x -> RuntimeType.hyper(runtimeConfig).resolve("body::Incoming")
            HttpVersion.Http0x -> RuntimeType.Hyper0x.resolve("body::Body")
        }

    /**
     * Emits the protocol-wrapped body type as the inner type of `Route<...>`.
     *
     * Most protocols pass the body straight through (`Route<Body>`). awsQuery
     * and ec2Query wrap it in `QueryBody<Body>` / `Ec2QueryBody<Body>` — this
     * is what [ServerProtocol.serverRouteRequestBodyTypePath] communicates.
     * We split the wrapper string on a sentinel and re-inject the body type as
     * a RuntimeType reference so its CargoDependency (e.g. `hyper`) survives.
     */
    private fun routedBodyType(): Writable {
        val sentinel = "__SERVICE_OPS_BODY_HOLE__"
        val wrapped = protocol.serverRouteRequestBodyTypePath(sentinel, smithyHttpServer)
        val parts = wrapped.split(sentinel)
        return writable {
            when (parts.size) {
                1 -> rustTemplate("#{Body}", "Body" to defaultRequestBodyType())
                2 -> {
                    rust(parts[0])
                    rustTemplate("#{Body}", "Body" to defaultRequestBodyType())
                    rust(parts[1])
                }
                else -> error("unexpected serverRouteRequestBodyTypePath output: $wrapped")
            }
        }
    }

    private val codegenScope =
        arrayOf(
            "Arc" to RuntimeType.Arc,
            "Future" to RuntimeType.std.resolve("future::Future"),
            "SmithyHttpServer" to smithyHttpServer,
            "Marker" to protocol.markerStruct(),
            "Router" to protocol.routerType(),
            "RoutedBody" to routedBodyType(),
            *RuntimeType.preludeScope,
        )

    fun render() {
        rustCrate.withModule(ServiceOperationsTraitModule.Module) {
            renderTrait(this)
            renderBuildHelper(this)
            renderIntoRouter(this)
        }
    }

    /**
     * Helper trait that lets [renderIntoRouter] call `.build_or_unwrap()` on the
     * result of `<ServiceName>Config::builder().build()` without caring whether
     * that returns a bare `Config` (no required config methods) or a
     * `Result<Config, Error>` (decorator-injected required fields like
     * `aws_auth`). The decorator can't detect builder fallibility from inside
     * [extras] — the combined decorator chain isn't exposed — so the generator
     * defers the choice to the type system.
     */
    private fun renderBuildHelper(writer: RustWriter) {
        // `##` escapes the smithy CodeFormatter's `#` placeholder marker so the
        // literal `#[doc(hidden)]` reaches the .rs file.
        writer.rust(
            """
            ##[doc(hidden)]
            pub trait __ConfigBuildOrUnwrap<T> {
                fn __build_or_unwrap(self) -> T;
            }
            impl<T> __ConfigBuildOrUnwrap<T> for T {
                fn __build_or_unwrap(self) -> T { self }
            }
            impl<T, E> __ConfigBuildOrUnwrap<T> for ::std::result::Result<T, E> {
                fn __build_or_unwrap(self) -> T {
                    match self {
                        ::std::result::Result::Ok(value) => value,
                        ::std::result::Result::Err(_) => {
                            panic!("config builder failed; pass a fully-constructed config via the underlying builder instead")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun renderTrait(writer: RustWriter) {
        writer.docs(
            "Implementor surface for the `$serviceName` service. Each method corresponds to one\n" +
                "operation in the Smithy model; every method has a default that panics, so\n" +
                "implementors override only the operations they actually serve.\n\n" +
                "Use [`into_router`] to convert an implementor of this trait into the same\n" +
                "routing service produced by [`crate::$builderName`].",
        )
        writer.rustTemplate(
            """
            pub trait $traitName: #{Send} + #{Sync} + #{Sized} + 'static {
            """.trimIndent(),
            *codegenScope,
        )
        for (operation in operations) {
            renderOperationMethod(writer, operation)
        }
        writer.rust("}")
    }

    private fun renderOperationMethod(
        writer: RustWriter,
        operation: OperationShape,
    ) {
        val fieldName = operationFieldNames.getValue(operation)
        val structName = operationStructNames.getValue(operation)
        val operationPanicMessage = "operation not implemented: $serviceName.$structName"
        val hasErrors = operation.errors.isNotEmpty()

        if (hasErrors) {
            writer.rustTemplate(
                """
                fn $fieldName(
                    &self,
                    _input: crate::input::${structName}Input,
                ) -> impl #{Future}<
                    Output = #{Result}<crate::output::${structName}Output, crate::error::${structName}Error>
                > + #{Send} {
                    async move { panic!("$operationPanicMessage") }
                }
                """.trimIndent(),
                *codegenScope,
            )
        } else {
            writer.rustTemplate(
                """
                fn $fieldName(
                    &self,
                    _input: crate::input::${structName}Input,
                ) -> impl #{Future}<Output = crate::output::${structName}Output> + #{Send} {
                    async move { panic!("$operationPanicMessage") }
                }
                """.trimIndent(),
                *codegenScope,
            )
        }
    }

    private fun renderIntoRouter(writer: RustWriter) {
        writer.docs(
            "Convert an implementation of [`$traitName`] into the concrete routing service.\n\n" +
                "Internally constructs an [`crate::$builderName`] with the default\n" +
                "[`crate::$configName`], registers one closure per operation that delegates\n" +
                "to the trait method, and calls `build_unchecked()`. The implementor is\n" +
                "shared across all handlers via `Arc<T>`; trait methods take `&self`, so\n" +
                "mutation uses interior mutability on fields just like Axum's\n" +
                "`with_state`-style handlers.\n\n" +
                "Users who need custom layers or plugins should bypass this function and\n" +
                "use [`crate::$builderName`] directly.",
        )
        writer.rustTemplate(
            """
            pub fn into_router<T>(
                svc: T,
            ) -> crate::$serviceName<
                #{SmithyHttpServer}::routing::RoutingService<
                    #{Router}<#{SmithyHttpServer}::routing::Route<#{RoutedBody:W}>>,
                    #{Marker},
                >,
            >
            where
                T: $traitName,
            {
                let svc = #{Arc}::new(svc);
                let config = __ConfigBuildOrUnwrap::__build_or_unwrap(
                    crate::$configName::builder().build()
                );
                crate::$serviceName::builder(config)
            """.trimIndent(),
            *codegenScope,
        )
        for (operation in operations) {
            val fieldName = operationFieldNames.getValue(operation)
            // Use fully-qualified trait dispatch so operations named after
            // `Arc<T>` inherent methods (e.g. `clone`, `as_ref`) still resolve
            // to the trait method instead of the smart-pointer's own method.
            writer.rust(
                """
                    .$fieldName({
                        let svc = ::std::sync::Arc::clone(&svc);
                        move |input| {
                            let svc = ::std::sync::Arc::clone(&svc);
                            async move { <T as $traitName>::$fieldName(&*svc, input).await }
                        }
                    })
                """.trimIndent(),
            )
        }
        writer.rust(
            """
                    .build_unchecked()
            }
            """.trimIndent(),
        )
    }
}

object ServiceOperationsTraitModule {
    val Module = RustModule.public("service_impl")
}
