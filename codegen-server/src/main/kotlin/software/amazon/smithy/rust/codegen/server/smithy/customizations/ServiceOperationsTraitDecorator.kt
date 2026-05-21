/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.customizations

import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.customize.ServerCodegenDecorator
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.server.smithy.protocols.ServerProtocolLoader

/**
 * Emits a service-implementor trait into every generated server crate. See
 * [ServiceOperationsTraitGenerator] for the shape of the generated code.
 *
 * Wires via [extras] (the standard "add an additional module" hook used by
 * `codegen-server-python` for its analogous module). No [libRsCustomizations]
 * is needed because [RustModule.public] auto-declares `pub mod service_impl;`
 * in `lib.rs`.
 */
class ServiceOperationsTraitDecorator : ServerCodegenDecorator {
    override val name: String = "ServiceOperationsTraitDecorator"
    override val order: Byte = 0

    override fun extras(
        codegenContext: ServerCodegenContext,
        rustCrate: RustCrate,
    ) {
        val factory =
            ServerProtocolLoader.defaultProtocols()[codegenContext.protocol]
                ?: return // Unknown protocol — skip; the rest of codegen will surface the failure.
        val protocol = factory.protocol(codegenContext) as ServerProtocol
        ServiceOperationsTraitGenerator(codegenContext, protocol, rustCrate).render()
    }
}
