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

        @validationException
        @error("client")
        structure WithPascalCaseMessage {
            Message: String
        }

        @validationException
        @error("client")
        structure WithUppercaseMessage {
            MESSAGE: String
        }

        @validationException
        @error("client")
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
    fun `isValidationMessage accepts PascalCase Message`() {
        member("WithPascalCaseMessage", "Message").isValidationMessage() shouldBe true
    }

    @Test
    fun `isValidationMessage accepts uppercase MESSAGE`() {
        member("WithUppercaseMessage", "MESSAGE").isValidationMessage() shouldBe true
    }

    @Test
    fun `isValidationMessage accepts mixed case MeSsAgE`() {
        member("WithMixedCaseMessage", "MeSsAgE").isValidationMessage() shouldBe true
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
    fun `isValidationFieldName accepts PascalCase Name`() {
        member("WithPascalCaseName", "Name").isValidationFieldName() shouldBe true
    }

    @Test
    fun `isValidationFieldName rejects unrelated member name`() {
        member("WithUnrelatedNameField", "field").isValidationFieldName() shouldBe false
    }

    @Test
    fun `isValidationFieldName accepts any member name carrying the validationFieldName trait`() {
        member("WithTraitOnNonName", "label").isValidationFieldName() shouldBe true
    }
}
