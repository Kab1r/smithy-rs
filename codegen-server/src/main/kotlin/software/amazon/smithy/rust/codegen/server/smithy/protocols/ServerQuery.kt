/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.protocols

import software.amazon.smithy.aws.traits.protocols.AwsQueryErrorTrait
import software.amazon.smithy.model.pattern.UriPattern
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.HttpTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.generators.http.HttpBindingCustomization
import software.amazon.smithy.rust.codegen.core.smithy.generators.protocol.ProtocolSupport
import software.amazon.smithy.rust.codegen.core.smithy.protocols.AwsQueryProtocol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Ec2QueryProtocol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingDescriptor
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Protocol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolGeneratorFactory
import software.amazon.smithy.rust.codegen.core.smithy.protocols.StaticHttpBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.parse.StructuredDataParserGenerator
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.Ec2QuerySerializerGenerator
import software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize.StructuredDataSerializerGenerator
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.ServerRuntimeType
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocolGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.deserializePayloadErrorType

private val queryHttpTrait =
    HttpTrait.builder()
        .code(200)
        .method("POST")
        .uri(UriPattern.parse("/"))
        .build()

private fun ec2HttpBindingResolver(codegenContext: ServerCodegenContext): HttpBindingResolver =
    StaticHttpBindingResolver(
        codegenContext.model,
        queryHttpTrait,
        "application/x-www-form-urlencoded",
        "text/xml;charset=UTF-8",
    )

private fun queryProtocolSupport(): ProtocolSupport =
    ProtocolSupport(
        // Client support
        requestSerialization = false,
        requestBodySerialization = false,
        responseDeserialization = false,
        errorDeserialization = false,
        // Server support
        requestDeserialization = true,
        requestBodyDeserialization = true,
        responseSerialization = true,
        errorSerialization = true,
    )

class ServerAwsQueryFactory(
    private val additionalServerHttpBoundProtocolCustomizations: List<ServerHttpBoundProtocolCustomization> = listOf(),
    private val additionalHttpBindingCustomizations: List<HttpBindingCustomization> = listOf(),
) : ProtocolGeneratorFactory<ServerHttpBoundProtocolGenerator, ServerCodegenContext> {
    override fun protocol(codegenContext: ServerCodegenContext): Protocol = ServerAwsQueryProtocol(codegenContext)

    override fun buildProtocolGenerator(codegenContext: ServerCodegenContext): ServerHttpBoundProtocolGenerator =
        ServerHttpBoundProtocolGenerator(
            codegenContext,
            ServerAwsQueryProtocol(codegenContext),
            additionalServerHttpBoundProtocolCustomizations,
            additionalHttpBindingCustomizations,
        )

    override fun support(): ProtocolSupport = queryProtocolSupport()
}

class ServerEc2QueryFactory(
    private val additionalServerHttpBoundProtocolCustomizations: List<ServerHttpBoundProtocolCustomization> = listOf(),
    private val additionalHttpBindingCustomizations: List<HttpBindingCustomization> = listOf(),
) : ProtocolGeneratorFactory<ServerHttpBoundProtocolGenerator, ServerCodegenContext> {
    override fun protocol(codegenContext: ServerCodegenContext): Protocol = ServerEc2QueryProtocol(codegenContext)

    override fun buildProtocolGenerator(codegenContext: ServerCodegenContext): ServerHttpBoundProtocolGenerator =
        ServerHttpBoundProtocolGenerator(
            codegenContext,
            ServerEc2QueryProtocol(codegenContext),
            additionalServerHttpBoundProtocolCustomizations,
            additionalHttpBindingCustomizations,
        )

    override fun support(): ProtocolSupport = queryProtocolSupport()
}

class ServerAwsQueryProtocol(
    private val serverCodegenContext: ServerCodegenContext,
) : AwsQueryProtocol(serverCodegenContext), ServerProtocol {
    private val runtimeConfig = serverCodegenContext.runtimeConfig

    override val protocolModulePath: String = "aws_query"

    override fun structuredDataParser(): StructuredDataParserGenerator =
        ServerAwsQueryParserGenerator(serverCodegenContext, this)

    // structuredDataSerializer, httpBindingResolver, defaultTimestampFormat,
    // parseHttpErrorMetadata, parseEventStreamErrorMetadata inherited from AwsQueryProtocol.

    override fun markerStruct(): RuntimeType = ServerRuntimeType.protocol("AwsQuery", protocolModulePath, runtimeConfig)

    override fun routerType(): RuntimeType =
        ServerCargoDependency.smithyHttpServer(runtimeConfig).toType()
            .resolve("protocol::aws_query::router::AwsQueryRouter")

    override fun serverRouterRuntimeConstructor(): String = "new_aws_query_router"

    override fun serverRouterRequestSpec(
        operationShape: OperationShape,
        operationName: String,
        serviceName: String,
        requestSpecModule: RuntimeType,
    ): Writable =
        writable {
            rust(operationShape.id.name.dq())
        }

    override fun serverRouterRequestSpecType(requestSpecModule: RuntimeType): RuntimeType = RuntimeType.StaticStr

    override fun serverRouteRequestBodyTypePath(
        bodyType: String,
        smithyHttpServer: RuntimeType,
    ): String =
        "${smithyHttpServer.path}::protocol::aws_query::router::QueryBody<${bodyType.replace("#{SmithyHttpServer}", smithyHttpServer.path)}>"

    override fun errorStatusCode(
        errorShape: StructureShape,
        defaultCode: Int,
    ): Int = errorShape.getTrait<AwsQueryErrorTrait>()?.httpResponseCode ?: defaultCode

    override fun deserializePayloadErrorType(binding: HttpBindingDescriptor): RuntimeType =
        deserializePayloadErrorType(
            serverCodegenContext,
            binding,
            requestRejection(runtimeConfig),
            RuntimeType.smithyXml(runtimeConfig).resolve("decode::XmlDecodeError"),
        )
}

class ServerEc2QueryProtocol(
    private val serverCodegenContext: ServerCodegenContext,
) : ServerProtocol {
    private val runtimeConfig = serverCodegenContext.runtimeConfig
    private val clientProtocol = Ec2QueryProtocol(serverCodegenContext)

    override val protocolModulePath: String = "ec2_query"

    override val httpBindingResolver: HttpBindingResolver = ec2HttpBindingResolver(serverCodegenContext)

    override val defaultTimestampFormat: TimestampFormatTrait.Format = TimestampFormatTrait.Format.DATE_TIME

    override fun structuredDataParser(): StructuredDataParserGenerator =
        ServerEc2QueryParserGenerator(serverCodegenContext, this)

    override fun structuredDataSerializer(): StructuredDataSerializerGenerator =
        Ec2QuerySerializerGenerator(serverCodegenContext)

    override fun parseHttpErrorMetadata(operationShape: OperationShape): RuntimeType =
        clientProtocol.parseHttpErrorMetadata(operationShape)

    override fun parseEventStreamErrorMetadata(operationShape: OperationShape): RuntimeType =
        clientProtocol.parseEventStreamErrorMetadata(operationShape)

    override fun markerStruct(): RuntimeType = ServerRuntimeType.protocol("Ec2Query", protocolModulePath, runtimeConfig)

    override fun routerType(): RuntimeType =
        ServerCargoDependency.smithyHttpServer(runtimeConfig).toType()
            .resolve("protocol::ec2_query::router::Ec2QueryRouter")

    override fun serverRouterRuntimeConstructor(): String = "new_ec2_query_router"

    override fun serverRouterRequestSpec(
        operationShape: OperationShape,
        operationName: String,
        serviceName: String,
        requestSpecModule: RuntimeType,
    ): Writable =
        writable {
            rust(operationShape.id.name.dq())
        }

    override fun serverRouterRequestSpecType(requestSpecModule: RuntimeType): RuntimeType = RuntimeType.StaticStr

    override fun serverRouteRequestBodyTypePath(
        bodyType: String,
        smithyHttpServer: RuntimeType,
    ): String =
        "${smithyHttpServer.path}::protocol::ec2_query::router::QueryBody<${bodyType.replace("#{SmithyHttpServer}", smithyHttpServer.path)}>"

    override fun deserializePayloadErrorType(binding: HttpBindingDescriptor): RuntimeType =
        deserializePayloadErrorType(
            serverCodegenContext,
            binding,
            requestRejection(runtimeConfig),
            RuntimeType.smithyXml(runtimeConfig).resolve("decode::XmlDecodeError"),
        )
}
