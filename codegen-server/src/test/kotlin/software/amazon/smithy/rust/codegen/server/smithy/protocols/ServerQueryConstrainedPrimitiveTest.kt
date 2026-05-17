/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.rust.codegen.server.smithy.protocols

import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.testutil.IntegrationTestParams
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.testModule
import software.amazon.smithy.rust.codegen.core.testutil.tokioTest
import software.amazon.smithy.rust.codegen.server.smithy.testutil.ServerHttpTestHelpers
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverIntegrationTest

class ServerQueryConstrainedPrimitiveTest {
    private val model =
        """
        namespace test

        use aws.protocols#awsQuery
        use smithy.api#xmlNamespace
        use smithy.framework#ValidationException

        @awsQuery
        @xmlNamespace(uri: "https://example.com/")
        service QueryConstrained {
            version: "2020-01-08",
            operations: [
                ConstrainedPrimitiveInput,
                ConstrainedPrimitiveOutput,
            ],
        }

        @http(uri: "/", method: "POST")
        operation ConstrainedPrimitiveInput {
            input: ConstrainedPrimitiveInputInput,
            output: ConstrainedPrimitiveInputOutput,
            errors: [ValidationException],
        }

        structure ConstrainedPrimitiveInputInput {
            durationSeconds: DurationSecondsType,
            name: NameType,
            labels: LabelList,
            uniqueLabels: UniqueLabelList,
            attributes: AttributeMap,
        }

        structure ConstrainedPrimitiveInputOutput {}

        @http(uri: "/output", method: "POST")
        operation ConstrainedPrimitiveOutput {
            input: ConstrainedPrimitiveOutputInput,
            output: ConstrainedPrimitiveOutputOutput,
            errors: [ValidationException],
        }

        structure ConstrainedPrimitiveOutputInput {}

        structure ConstrainedPrimitiveOutputOutput {
            @required
            count: NonNegativeIntegerType,
        }

        @range(min: 1, max: 43200)
        integer DurationSecondsType

        @range(min: 0)
        integer NonNegativeIntegerType

        @pattern("^[A-Za-z0-9]+${'$'}")
        string NameType

        @length(min: 1, max: 2)
        list LabelList {
            member: LabelValue
        }

        @uniqueItems
        list UniqueLabelList {
            member: LabelValue
        }

        @length(min: 1, max: 2)
        map AttributeMap {
            key: AttributeKey,
            value: LabelValue,
        }

        @pattern("^[A-Za-z0-9]+${'$'}")
        string AttributeKey

        @pattern("^[A-Za-z0-9]+${'$'}")
        string LabelValue
        """.asSmithyModel(smithyVersion = "2")

    @Test
    fun `query constrained primitive members parse validate and serialize`() {
        serverIntegrationTest(
            model,
            IntegrationTestParams(service = "test#QueryConstrained"),
        ) { codegenContext, rustCrate ->
            val codegenScope = ServerHttpTestHelpers.getHttpRuntimeTypeScope(codegenContext)

            rustCrate.testModule {
                rustTemplate(
                    """
                    async fn constrained_input_handler(
                        input: crate::input::ConstrainedPrimitiveInputInput,
                    ) -> Result<crate::output::ConstrainedPrimitiveInputOutput, crate::error::ConstrainedPrimitiveInputError> {
                        let duration_seconds = input.duration_seconds.expect("duration_seconds should be set");
                        assert_eq!(*duration_seconds.inner(), 3600);

                        let name = input.name.expect("name should be set");
                        assert_eq!(name.as_str(), "example");

                        assert!(input.labels.is_some(), "labels should be set");
                        assert!(input.unique_labels.is_some(), "unique_labels should be set");
                        assert!(input.attributes.is_some(), "attributes should be set");

                        Ok(crate::output::ConstrainedPrimitiveInputOutput {})
                    }

                    """,
                    *codegenScope,
                )

                tokioTest("valid_query_constrained_primitives_reach_handler") {
                    rustTemplate(
                        """
                        let config = crate::QueryConstrainedConfig::builder().build();
                        let service = crate::QueryConstrained::builder(config)
                            .constrained_primitive_input(constrained_input_handler)
                            .build_unchecked();

                        let request_body = b"Action=ConstrainedPrimitiveInput&Version=2020-01-08&durationSeconds=3600&name=example&labels.member.1=alpha&labels.member.2=beta&uniqueLabels.member.1=alpha&uniqueLabels.member.2=beta&attributes.entry.1.key=first&attributes.entry.1.value=alpha".to_vec();
                        let request = #{Http}::Request::builder()
                            .uri("/")
                            .method("POST")
                            .header("content-type", "application/x-www-form-urlencoded")
                            .body(#{CreateBody:W})
                            .expect("failed to build request");

                        let response = #{Tower}::ServiceExt::oneshot(service, request)
                            .await
                            .expect("failed to call service");
                        assert_eq!(response.status(), #{Http}::StatusCode::OK);
                        """,
                        *codegenScope,
                        "CreateBody" to ServerHttpTestHelpers.createBodyFromBytes(codegenContext, "request_body"),
                    )
                }

                tokioTest("invalid_query_constrained_primitives_reject_as_validation") {
                    rustTemplate(
                        """
                        let config = crate::QueryConstrainedConfig::builder().build();
                        let service = crate::QueryConstrained::builder(config)
                            .constrained_primitive_input(constrained_input_handler)
                            .build_unchecked();

                        let request_body = b"Action=ConstrainedPrimitiveInput&Version=2020-01-08&durationSeconds=0&name=example".to_vec();
                        let request = #{Http}::Request::builder()
                            .uri("/")
                            .method("POST")
                            .header("content-type", "application/x-www-form-urlencoded")
                            .body(#{CreateBody:W})
                            .expect("failed to build request");

                        let response = #{Tower}::ServiceExt::oneshot(service, request)
                            .await
                            .expect("failed to call service");
                        assert_eq!(response.status(), #{Http}::StatusCode::BAD_REQUEST);
                        """,
                        *codegenScope,
                        "CreateBody" to ServerHttpTestHelpers.createBodyFromBytes(codegenContext, "request_body"),
                    )
                }

                tokioTest("invalid_query_constrained_list_rejects_as_validation") {
                    rustTemplate(
                        """
                        let config = crate::QueryConstrainedConfig::builder().build();
                        let service = crate::QueryConstrained::builder(config)
                            .constrained_primitive_input(constrained_input_handler)
                            .build_unchecked();

                        let request_body = b"Action=ConstrainedPrimitiveInput&Version=2020-01-08&durationSeconds=3600&labels.member.1=alpha&labels.member.2=beta&labels.member.3=gamma".to_vec();
                        let request = #{Http}::Request::builder()
                            .uri("/")
                            .method("POST")
                            .header("content-type", "application/x-www-form-urlencoded")
                            .body(#{CreateBody:W})
                            .expect("failed to build request");

                        let response = #{Tower}::ServiceExt::oneshot(service, request)
                            .await
                            .expect("failed to call service");
                        assert_eq!(response.status(), #{Http}::StatusCode::BAD_REQUEST);
                        """,
                        *codegenScope,
                        "CreateBody" to ServerHttpTestHelpers.createBodyFromBytes(codegenContext, "request_body"),
                    )
                }

                tokioTest("invalid_query_constrained_unique_list_rejects_as_validation") {
                    rustTemplate(
                        """
                        let config = crate::QueryConstrainedConfig::builder().build();
                        let service = crate::QueryConstrained::builder(config)
                            .constrained_primitive_input(constrained_input_handler)
                            .build_unchecked();

                        let request_body = b"Action=ConstrainedPrimitiveInput&Version=2020-01-08&durationSeconds=3600&uniqueLabels.member.1=alpha&uniqueLabels.member.2=alpha".to_vec();
                        let request = #{Http}::Request::builder()
                            .uri("/")
                            .method("POST")
                            .header("content-type", "application/x-www-form-urlencoded")
                            .body(#{CreateBody:W})
                            .expect("failed to build request");

                        let response = #{Tower}::ServiceExt::oneshot(service, request)
                            .await
                            .expect("failed to call service");
                        assert_eq!(response.status(), #{Http}::StatusCode::BAD_REQUEST);
                        """,
                        *codegenScope,
                        "CreateBody" to ServerHttpTestHelpers.createBodyFromBytes(codegenContext, "request_body"),
                    )
                }

                tokioTest("query_constrained_primitive_output_serializes_inner_value") {
                    rustTemplate(
                        """
                        let count = <crate::model::NonNegativeIntegerType as ::std::convert::TryFrom<i32>>::try_from(7)
                            .expect("count should satisfy constraints");
                        let output = crate::output::ConstrainedPrimitiveOutputOutput { count };
                        let body = crate::protocol_serde::shape_constrained_primitive_output::ser_constrained_primitive_output_output(&output)
                            .expect("output should serialize");
                        assert!(body.contains("<count>7</count>"), "unexpected body: {body}");
                        """,
                        *codegenScope,
                    )
                }
            }
        }
    }
}
