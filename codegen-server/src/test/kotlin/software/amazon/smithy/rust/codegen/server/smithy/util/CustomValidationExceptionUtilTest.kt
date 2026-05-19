/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel

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
}
