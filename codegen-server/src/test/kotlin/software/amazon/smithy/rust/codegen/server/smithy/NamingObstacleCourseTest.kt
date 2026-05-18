/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverIntegrationTest

class NamingObstacleCourseTest {
    @Test
    fun `test Rust prelude operation names compile`() {
        serverIntegrationTest(NamingObstacleCourseTestModels.rustPreludeOperationsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude structure names compile`() {
        serverIntegrationTest(NamingObstacleCourseTestModels.rustPreludeStructsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude enum names compile`() {
        serverIntegrationTest(NamingObstacleCourseTestModels.rustPreludeEnumsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude enum variant names compile`() {
        serverIntegrationTest(NamingObstacleCourseTestModels.rustPreludeEnumVariantsModel()) { _, _ -> }
    }

    // A structure shape called `Namespace` lives at `crate::model::Namespace`. Its server builder
    // module is `crate::model::namespace` (snake-cased). Other shapes that reference `Namespace`
    // by type — including from nested collections and from constrained inputs — must resolve
    // `crate::model::Namespace`, not `crate::model::namespace`, the builder module, or rustc
    // fails with E0573 "expected type, found module".
    // Operation outputs may carry a `@httpPayload`-bound Blob. The generated builder must end up
    // assigning the deserialized Blob to a field that is itself typed as `Blob` (not `Vec<u8>`),
    // otherwise rustc reports E0308 `expected Vec<u8>, found Blob` and every such service stops
    // compiling.
    @Test
    fun `operations with a Blob HTTP payload output compile`() {
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

                @httpHeader("X-Amzn-SageMaker-Custom-Attributes")
                CustomAttributes: String

                @httpPayload
                @required
                Body: BodyBlob
            }

            // Mirrors `InvokeEndpointOutput`: a required @httpPayload Blob alongside several
            // @httpHeader members. The output serializer must hand the field's `Blob` to the wire
            // writer; it must not assume the field is `Vec<u8>`.
            structure InvokeEndpointOutput {
                @httpPayload
                @required
                Body: BodyBlob

                @httpHeader("Content-Type")
                ContentType: String

                @httpHeader("X-Amzn-Invoked-Production-Variant")
                InvokedProductionVariant: String

                @httpHeader("X-Amzn-SageMaker-Custom-Attributes")
                CustomAttributes: String
            }

            // Many service models (sagemakerruntime, datazone, ...) declare their payload Blob as a
            // separately named Blob shape, often with no constraints but distinct from `smithy.api#Blob`.
            @length(min: 0, max: 6291456)
            blob BodyBlob
            """.asSmithyModel(smithyVersion = "2")

        serverIntegrationTest(model) { _, _ -> }
    }

    @Test
    fun `types referenced by other shapes are reached as PascalCase, not via the builder module`() {
        val model =
            """
            namespace test

            use aws.protocols#restJson1

            @restJson1
            service NamingService {
                version: "1.0.0"
                operations: [DescribeNamespace, ListNames]
            }

            @http(method: "POST", uri: "/describe")
            operation DescribeNamespace {
                input: DescribeNamespaceInput
                output: DescribeNamespaceOutput
            }

            @http(method: "POST", uri: "/list-names")
            operation ListNames {
                input: ListNamesInput
                output: ListNamesOutput
            }

            structure DescribeNamespaceInput {
                @required
                identifier: String
            }

            structure DescribeNamespaceOutput {
                namespace: Namespace
                name: Name
                region: Region
                ref: NamespaceRef
            }

            structure ListNamesInput {
                @range(min: 1, max: 16)
                pageSize: Integer

                names: NamespaceList
            }

            structure ListNamesOutput {
                names: NameList
                namespaces: NamespaceMap
            }

            list NamespaceList {
                member: Namespace
            }

            list NameList {
                member: Name
            }

            map NamespaceMap {
                key: String
                value: Namespace
            }

            structure Namespace {
                identifier: String
            }

            structure Name {
                value: String
            }

            @length(min: 1, max: 64)
            string Region

            structure NamespaceRef {
                @required
                namespace: Namespace
                region: Region
            }
            """.asSmithyModel(smithyVersion = "2")

        serverIntegrationTest(model) { _, _ -> }
    }
}
