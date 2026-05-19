/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait
import software.amazon.smithy.rust.codegen.client.testutil.clientIntegrationTest
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.reusedInputOutputShapesModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeEnumVariantsModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeEnumsModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeOperationsModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeStructsModel
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel

class NamingObstacleCourseTest {
    @Test
    fun `test Rust prelude operation names compile`() {
        clientIntegrationTest(rustPreludeOperationsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude structure names compile`() {
        clientIntegrationTest(rustPreludeStructsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude enum names compile`() {
        clientIntegrationTest(rustPreludeEnumsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude enum variant names compile`() {
        clientIntegrationTest(rustPreludeEnumVariantsModel()) { _, _ -> }
    }

    @Test
    fun `test reuse of input and output shapes json`() {
        clientIntegrationTest(reusedInputOutputShapesModel(RestJson1Trait.builder().build()))
    }

    @Test
    fun `test reuse of input and output shapes xml`() {
        clientIntegrationTest(reusedInputOutputShapesModel(RestXmlTrait.builder().build()))
    }

    // Mirrors the server-side `operations with a Blob HTTP payload output compile` test.
    // A named Blob shape decorated with `@length` is bound to `@httpPayload` in an operation output.
    // The client symbol provider does not emit constrained-wrapper types (`publicConstrainedTypes`
    // is off), so the generated accessor must return `&[u8]` via a single unwrap.  If the client
    // codegen ever starts treating `@length`-constrained Blobs as newtypes, the generated code
    // would fail to compile here, catching the regression early.
    @Test
    fun `constrained blob bound to httpPayload generates a single-unwrap accessor on the client`() {
        val model =
            """
            namespace test

            use aws.protocols#restJson1

            @restJson1
            service InvokeService {
                version: "1.0.0"
                operations: [InvokeEndpoint]
            }

            @http(method: "POST", uri: "/endpoints/{EndpointName}/invocations")
            operation InvokeEndpoint {
                input: InvokeEndpointInput
                output: InvokeEndpointOutput
            }

            structure InvokeEndpointInput {
                @httpLabel
                @required
                EndpointName: String

                @httpHeader("Content-Type")
                ContentType: String

                @httpPayload
                @required
                Body: BodyBlob
            }

            // A required @httpPayload Blob alongside several @httpHeader members, mirroring
            // real-world service models such as SageMaker Runtime's InvokeEndpointOutput.
            // The client deserializer must hand the raw bytes to the output builder as a
            // `Blob`, not as `Vec<u8>`, even when the blob shape carries a `@length` trait.
            structure InvokeEndpointOutput {
                @httpPayload
                @required
                Body: BodyBlob

                @httpHeader("Content-Type")
                ContentType: String

                @httpHeader("X-Amzn-Invoked-Production-Variant")
                InvokedProductionVariant: String
            }

            // Named blob shape with a @length constraint — present on many real service models
            // (sagemakerruntime, datazone, …).  The constraint is irrelevant to the client (no
            // constrained-wrapper type is emitted), but it must not confuse the payload generator
            // into double-unwrapping the value.
            @length(min: 0, max: 6291456)
            blob BodyBlob
            """.asSmithyModel(smithyVersion = "2")

        clientIntegrationTest(model) { _, _ -> }
    }
}
