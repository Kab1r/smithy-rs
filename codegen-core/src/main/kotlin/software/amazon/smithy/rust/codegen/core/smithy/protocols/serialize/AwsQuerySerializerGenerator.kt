/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize

import software.amazon.smithy.aws.traits.protocols.AwsQueryErrorTrait
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.XmlFlattenedTrait
import software.amazon.smithy.model.traits.XmlNameTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.protocols.AwsQueryBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.XmlMemberIndex
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.expectTrait
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.outputShape

class AwsQuerySerializerGenerator(codegenContext: CodegenContext) : QuerySerializerGenerator(codegenContext) {
    private val xmlSerializer = XmlBindingTraitSerializerGenerator(codegenContext, AwsQueryBindingResolver(model))

    override val protocolName: String get() = "AWS Query"

    override fun MemberShape.queryKeyName(prioritizedFallback: String?): String =
        getTrait<XmlNameTrait>()?.value ?: memberName

    override fun MemberShape.isFlattened(): Boolean = getTrait<XmlFlattenedTrait>() != null

    override fun operationOutputSerializer(operationShape: OperationShape): RuntimeType? {
        val outputShape = operationShape.outputShape(model)
        val xmlMembers = XmlMemberIndex.fromMembers(outputShape.members().toList())
        if (xmlMembers.isEmpty()) {
            return null
        }
        val operationName = symbolProvider.toSymbol(operationShape).name
        val responseWrapperName = "${operationName}Response"
        val resultWrapperName = "${operationName}Result"
        return protocolFunctions.serializeFn(operationShape, fnNameSuffix = "output") { fnName ->
            rustBlockTemplate(
                "pub fn $fnName(output: &#{target}) -> #{Result}<String, #{Error}>",
                *codegenScope,
                "target" to symbolProvider.toSymbol(outputShape),
            ) {
                rust("let mut out = String::new();")
                rustBlockTemplate("") {
                    rustTemplate(
                        """
                        let mut writer = #{XmlWriter}::new(&mut out);
                        ##[allow(unused_mut)]
                        let mut root = writer.start_el(${responseWrapperName.dq()})${xmlSerializer.serviceRootNamespace()};
                        let mut response_scope = root.finish();
                        let mut result_scope = response_scope.start_el(${resultWrapperName.dq()}).finish();
                        """,
                        "XmlWriter" to RuntimeType.smithyXml(runtimeConfig).resolve("encode::XmlWriter"),
                    )
                    xmlSerializer.renderStructureMembers(this, xmlMembers, "result_scope", "output")
                    rust(
                        """
                        result_scope.finish();
                        response_scope.finish();
                        """,
                    )
                }
                rustTemplate("Ok(out)", *codegenScope)
            }
        }
    }

    override fun serverErrorSerializer(shape: ShapeId): RuntimeType {
        val errorShape = model.expectShape(shape, StructureShape::class.java)
        val xmlMembers = XmlMemberIndex.fromMembers(errorShape.members().toList())
        val errorType =
            if (errorShape.expectTrait<ErrorTrait>().isClientError) {
                "Sender"
            } else {
                "Receiver"
            }
        val errorCode = errorShape.getTrait<AwsQueryErrorTrait>()?.code ?: errorShape.id.name
        return protocolFunctions.serializeFn(errorShape, fnNameSuffix = "error") { fnName ->
            rustBlockTemplate(
                "pub fn $fnName(error: &#{target}) -> #{Result}<String, #{Error}>",
                *codegenScope,
                "target" to symbolProvider.toSymbol(errorShape),
            ) {
                Attribute.AllowUnusedVariables.render(this)
                rust("let _ = error;")
                rust("let mut out = String::new();")
                rustBlockTemplate("") {
                    rustTemplate(
                        """
                        let mut writer = #{XmlWriter}::new(&mut out);
                        let root = writer.start_el("ErrorResponse");
                        let mut response_scope = root.finish();
                        let mut error_scope = response_scope.start_el("Error").finish();

                        let mut error_type = error_scope.start_el("Type").finish();
                        error_type.data(${errorType.dq()});
                        error_type.finish();

                        let mut error_code = error_scope.start_el("Code").finish();
                        error_code.data(${errorCode.dq()});
                        error_code.finish();
                        """,
                        "XmlWriter" to RuntimeType.smithyXml(runtimeConfig).resolve("encode::XmlWriter"),
                    )
                    xmlSerializer.renderStructureMembers(this, xmlMembers, "error_scope", "error")
                    rust(
                        """
                        error_scope.finish();

                        let mut request_id = response_scope.start_el("RequestId").finish();
                        request_id.data("foo-id");
                        request_id.finish();
                        response_scope.finish();
                        """,
                    )
                }
                rustTemplate("Ok(out)", *codegenScope)
            }
        }
    }
}
