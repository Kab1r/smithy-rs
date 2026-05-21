/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.customizations

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.server.smithy.testutil.HttpTestType
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverIntegrationTest

internal class ServiceOperationsTraitDecoratorTest {
    /**
     * Two operations: `Echo` has modeled errors, `Ping` has none. Exercises both
     * branches of the generator (fallible / infallible signature).
     */
    private val model =
        """
        namespace com.example

        use aws.protocols#restJson1

        @restJson1
        service ExampleService {
            version: "1.0.0"
            operations: [Echo, Ping]
        }

        @http(method: "POST", uri: "/echo")
        operation Echo {
            input: EchoInput
            output: EchoOutput
            errors: [ServerError]
        }

        @http(method: "GET", uri: "/ping")
        @readonly
        operation Ping {}

        structure EchoInput {
            @required
            message: String
        }

        structure EchoOutput {
            message: String
        }

        @error("server")
        @httpError(500)
        structure ServerError {
            message: String
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `generates service_impl module with trait and into_router`() {
        val generated = serverIntegrationTest(model, testCoverage = HttpTestType.Default)
        val serviceImpl = generated.single().path.resolve("src/service_impl.rs").toFile().readText()

        // Trait declaration uses the service shape name.
        serviceImpl shouldContain "pub trait ExampleServiceOperations"

        // Operation methods reference Input/Output/Error via the OperationShape
        // associated types (avoids relying on per-module casing conventions).
        serviceImpl shouldContain "fn echo("
        serviceImpl shouldContain "crate::operation_shape::Echo as"
        serviceImpl shouldContain "operation not implemented: ExampleService.Echo"

        serviceImpl shouldContain "fn ping("
        serviceImpl shouldContain "crate::operation_shape::Ping as"
        serviceImpl shouldContain "operation not implemented: ExampleService.Ping"

        // The free into_router function: takes the impl, returns the
        // protocol-specific routing service. Config is built inline with defaults.
        serviceImpl shouldContain "pub fn into_router<T>"
        serviceImpl shouldContain "ExampleServiceConfig::builder().build()"
        serviceImpl shouldContain "T: ExampleServiceOperations"
        serviceImpl shouldContain ".build_unchecked()"
    }

    /**
     * Same shape, AwsJson1_0 protocol — confirms the generator emits a different
     * router type per protocol (Marker swaps to AwsJson1_0).
     */
    private val awsJsonModel =
        """
        namespace com.example

        use aws.protocols#awsJson1_0

        @awsJson1_0
        service ExampleService {
            version: "1.0.0"
            operations: [Echo]
        }

        operation Echo {
            input: EchoInput
            output: EchoOutput
        }

        structure EchoInput { message: String }
        structure EchoOutput { message: String }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `generates a router type that matches the service protocol`() {
        val generated = serverIntegrationTest(awsJsonModel, testCoverage = HttpTestType.Default)
        val serviceImpl = generated.single().path.resolve("src/service_impl.rs").toFile().readText()
        // The Marker symbol is protocol-specific. AwsJson services use the AwsJson*
        // marker; if the generator hard-coded a REST marker we'd see RestJson1 here.
        serviceImpl shouldContain "AwsJson1_0"
    }
}
