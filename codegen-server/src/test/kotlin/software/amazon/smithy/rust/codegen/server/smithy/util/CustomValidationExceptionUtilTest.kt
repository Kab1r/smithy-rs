/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import software.amazon.smithy.framework.rust.ValidationFieldListTrait
import software.amazon.smithy.framework.rust.ValidationFieldNameTrait
import software.amazon.smithy.framework.rust.ValidationMessageTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.SourceLocation
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestSymbolProvider

class CustomValidationExceptionUtilTest {
    // The @validationMessage trait selector requires its parent structure to carry
    // @validationException, so the message-bearing fixtures need that overlay. The
    // @validationFieldName trait has the more permissive selector `structure > member`,
    // so its fixtures can be plain structures.
    private val testModel =
        """
        namespace test
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationFieldName
        use smithy.framework.rust#validationMessage

        @validationException
        @error("client")
        structure WithLowercaseMessage {
            message: String
        }

        // No @validationException here: these structures are used only to test that
        // isValidationMessage() returns false when the name is non-lowercase and the trait is absent.
        // Keeping @validationException on them would cause MissingMessageField validator errors
        // since the non-lowercase variants no longer satisfy the name-only fallback.
        structure WithPascalCaseMessage {
            Message: String
        }

        structure WithUppercaseMessage {
            MESSAGE: String
        }

        structure WithMixedCaseMessage {
            MeSsAgE: String
        }

        structure WithUnrelatedField {
            description: String
        }

        @validationException
        @error("client")
        structure WithTraitOnNonMessage {
            @validationMessage
            code: String
        }

        structure WithLowercaseName {
            name: String
        }

        structure WithPascalCaseName {
            Name: String
        }

        structure WithUnrelatedNameField {
            field: String
        }

        structure WithTraitOnNonName {
            @validationFieldName
            label: String
        }

        structure WithLowercasePath {
            path: String
        }

        @validationException
        @error("client")
        structure WithTraitOnPascalCaseMessage {
            @validationMessage
            Message: String
        }
        """.asSmithyModel(smithyVersion = "2")

    private fun member(
        struct: String,
        member: String,
    ): MemberShape =
        testModel
            .expectShape(software.amazon.smithy.model.shapes.ShapeId.from("test#$struct"))
            .asStructureShape()
            .get()
            .getMember(member)
            .get()

    @Test
    fun `isValidationMessage accepts lowercase message`() {
        member("WithLowercaseMessage", "message").isValidationMessage() shouldBe true
    }

    @Test
    fun `isValidationMessage rejects PascalCase Message without trait`() {
        // After tightening, only the exact lowercase "message" is recognised by name convention.
        // PascalCase variants require the explicit @validationMessage trait.
        member("WithPascalCaseMessage", "Message").isValidationMessage() shouldBe false
    }

    @Test
    fun `isValidationMessage rejects uppercase MESSAGE without trait`() {
        // After tightening, only the exact lowercase "message" is recognised by name convention.
        member("WithUppercaseMessage", "MESSAGE").isValidationMessage() shouldBe false
    }

    @Test
    fun `isValidationMessage rejects mixed case MeSsAgE without trait`() {
        // After tightening, any non-lowercase variant requires the explicit @validationMessage trait.
        member("WithMixedCaseMessage", "MeSsAgE").isValidationMessage() shouldBe false
    }

    @Test
    fun `isValidationMessage rejects unrelated member name`() {
        member("WithUnrelatedField", "description").isValidationMessage() shouldBe false
    }

    @Test
    fun `isValidationMessage accepts any member name carrying the validationMessage trait`() {
        // Explicit trait annotation continues to override naming heuristics.
        member("WithTraitOnNonMessage", "code").isValidationMessage() shouldBe true
    }

    @Test
    fun `isValidationFieldName accepts lowercase name`() {
        member("WithLowercaseName", "name").isValidationFieldName() shouldBe true
    }

    @Test
    fun `isValidationFieldName rejects PascalCase Name without trait`() {
        // After tightening, only the exact lowercase "name" or "path" is recognised by name convention.
        // PascalCase variants require the explicit @validationFieldName trait.
        member("WithPascalCaseName", "Name").isValidationFieldName() shouldBe false
    }

    @Test
    fun `isValidationFieldName rejects unrelated member name`() {
        member("WithUnrelatedNameField", "field").isValidationFieldName() shouldBe false
    }

    @Test
    fun `isValidationFieldName accepts any member name carrying the validationFieldName trait`() {
        member("WithTraitOnNonName", "label").isValidationFieldName() shouldBe true
    }

    // ── New tests added by Tasks 12 + 13 ──────────────────────────────────────────────────────
    // Note: the lowercase-message backward-compat assertion lives in `isValidationMessage
    // accepts lowercase message` above; not duplicated here.

    @Test
    fun `isValidationMessage accepts PascalCase Message carrying the validationMessage trait`() {
        member("WithTraitOnPascalCaseMessage", "Message").isValidationMessage() shouldBe true
    }

    @Test
    fun `isValidationFieldName still accepts lowercase path without trait (backward compat)`() {
        member("WithLowercasePath", "path").isValidationFieldName() shouldBe true
    }

    // ── New tests added by Task 14 ────────────────────────────────────────────────────────────
    // These tests exercise resolveValidationExceptionFieldIdentifiers directly.
    //
    // The function always looks up `smithy.framework#ValidationException` in the supplied model.
    // Because `asSmithyModel` loads all classpath shapes (including the built-in framework model),
    // tests that need a custom `smithy.framework#ValidationException` build the model
    // programmatically via the Smithy builder API so they can control the exact members and traits.

    /**
     * Build a minimal model from scratch (no classpath shapes) that contains only the shapes
     * needed to exercise resolveValidationExceptionFieldIdentifiers.
     *
     * @param fieldListMemberName the name of the field-list member on smithy.framework#ValidationException
     * @param withFieldListTrait whether the field-list member carries @validationFieldList
     * @param fieldNameMemberName the name of the field-name member on the ValidationExceptionField structure
     * @param withFieldNameTrait whether the field-name member carries @validationFieldName
     * @param messageMemberName the name of the message member on the ValidationExceptionField structure
     * @param withMessageTrait whether the message member carries @validationMessage
     */
    private fun buildMinimalFrameworkModel(
        fieldListMemberName: String = "fieldList",
        withFieldListTrait: Boolean = false,
        fieldNameMemberName: String = "path",
        withFieldNameTrait: Boolean = false,
        messageMemberName: String = "message",
        withMessageTrait: Boolean = false,
    ): Model {
        val ns = "smithy.framework"
        val fieldMemberId = "$ns#ValidationExceptionField"
        val fieldListId = "$ns#ValidationExceptionFieldList"
        val exceptionId = "$ns#ValidationException"

        val fieldNameBuilder =
            MemberShape.builder().id("$fieldMemberId\$$fieldNameMemberName")
                .target("smithy.api#String")
        if (withFieldNameTrait) fieldNameBuilder.addTrait(ValidationFieldNameTrait(SourceLocation.NONE))
        val fieldNameMember = fieldNameBuilder.build()

        val messageBuilder =
            MemberShape.builder().id("$fieldMemberId\$$messageMemberName")
                .target("smithy.api#String")
        if (withMessageTrait) messageBuilder.addTrait(ValidationMessageTrait(SourceLocation.NONE))
        val messageMember = messageBuilder.build()

        val fieldStructure =
            StructureShape.builder().id(fieldMemberId)
                .addMember(fieldNameMember)
                .addMember(messageMember)
                .build()

        val fieldListMemberShape =
            ListShape.builder().id(fieldListId)
                .member(ShapeId.from(fieldMemberId))
                .build()

        val exceptionFieldListBuilder =
            MemberShape.builder().id("$exceptionId\$$fieldListMemberName")
                .target(fieldListId)
        if (withFieldListTrait) exceptionFieldListBuilder.addTrait(ValidationFieldListTrait(SourceLocation.NONE))
        val exceptionFieldListMember = exceptionFieldListBuilder.build()
        val exceptionStructure =
            StructureShape.builder().id(exceptionId)
                .addTrait(ErrorTrait("client"))
                .addMember(
                    MemberShape.builder().id("$exceptionId\$message")
                        .target("smithy.api#String")
                        .addTrait(RequiredTrait())
                        .build(),
                )
                .addMember(exceptionFieldListMember)
                .build()

        return Model.builder()
            .addShapes(listOf(fieldStructure, fieldListMemberShape, exceptionStructure))
            .build()
    }

    @Test
    fun `resolves field identifiers via @validationFieldList trait when member is renamed`() {
        // The field-list member is named "errors" (not "fieldList") but carries @validationFieldList.
        // The field structure uses a non-default field-name member "fieldPath" with @validationFieldName
        // so that the resolved identifiers are demonstrably different from DEFAULT ("path"/"message").
        val model =
            buildMinimalFrameworkModel(
                fieldListMemberName = "errors",
                withFieldListTrait = true,
                fieldNameMemberName = "fieldPath",
                withFieldNameTrait = true,
                messageMemberName = "errorMessage",
                withMessageTrait = true,
            )
        val symbolProvider = serverTestSymbolProvider(model)
        val identifiers = resolveValidationExceptionFieldIdentifiers(model, symbolProvider)
        // The function must have traversed through the trait-annotated "errors" member;
        // if it had returned DEFAULT ("path"/"message") the trait path would have been skipped.
        identifiers shouldNotBe ValidationExceptionFieldIdentifiers.DEFAULT
        identifiers.nameMember shouldBe "field_path"
        identifiers.messageMember shouldBe "error_message"
    }

    @Test
    fun `falls back to canonical fieldList name when trait is absent`() {
        // The field-list member is named "fieldList" without @validationFieldList.
        // The name-based fallback should find it and return non-DEFAULT identifiers.
        val model =
            buildMinimalFrameworkModel(
                fieldListMemberName = "fieldList",
                withFieldListTrait = false,
                fieldNameMemberName = "path",
                withFieldNameTrait = true,
                messageMemberName = "message",
                withMessageTrait = true,
            )
        val symbolProvider = serverTestSymbolProvider(model)
        val identifiers = resolveValidationExceptionFieldIdentifiers(model, symbolProvider)
        // The framework VE's fieldList member is found by name; identifiers come from the field structure.
        identifiers.nameMember shouldBe "path"
        identifiers.messageMember shouldBe "message"
    }

    @Test
    fun `returns DEFAULT when neither trait nor canonical name is present`() {
        // The field-list member is named "errors" WITHOUT @validationFieldList.
        // Neither the trait path nor the name fallback ("fieldList"/"field_list") matches.
        val model =
            buildMinimalFrameworkModel(
                fieldListMemberName = "errors",
                withFieldListTrait = false,
            )
        val symbolProvider = serverTestSymbolProvider(model)
        val identifiers = resolveValidationExceptionFieldIdentifiers(model, symbolProvider)
        identifiers shouldBe ValidationExceptionFieldIdentifiers.DEFAULT
    }
}
