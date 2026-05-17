/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.validation.Severity
import software.amazon.smithy.model.validation.ValidationEvent
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel

class PatternTraitEscapedSpecialCharsValidatorTest {
    @Test
    fun `should warn with a suggestion if non-escaped special chars used inside @pattern`() {
        val events =
            patternEvents(
                """
                namespace test

                @pattern("\t")
                string MyString
                """,
            )

        events shouldHaveSize 1
        events[0].severity shouldBe Severity.DANGER
        events[0].shapeId.get() shouldBe ShapeId.from("test#MyString")
        events[0].message shouldBe
            """
            Non-escaped special characters used inside `@pattern`.
            You must escape them: `@pattern("\\t")`.
            See https://github.com/smithy-lang/smithy-rs/issues/2508 for more details.
            """.trimIndent()
    }

    @Test
    fun `should suggest escaping spacial characters properly`() {
        val events =
            patternEvents(
                """
                namespace test

                @pattern("[.\n\\r]+")
                string MyString
                """,
            )

        events shouldHaveSize 1
        events[0].severity shouldBe Severity.DANGER
        events[0].shapeId.get() shouldBe ShapeId.from("test#MyString")
        events[0].message shouldBe
            """
            Non-escaped special characters used inside `@pattern`.
            You must escape them: `@pattern("[.\\n\\r]+")`.
            See https://github.com/smithy-lang/smithy-rs/issues/2508 for more details.
            """.trimIndent()
    }

    @Test
    fun `should report all non-escaped special characters`() {
        val events =
            patternEvents(
                """
                namespace test

                @pattern("\b")
                string MyString

                @pattern("^\n$")
                string MyString2

                @pattern("^[\n]+$")
                string MyString3

                @pattern("^[\r\t]$")
                string MyString4
                """,
            )

        events shouldHaveSize 4
        events.forEach { it.severity shouldBe Severity.DANGER }
    }

    @Test
    fun `should report warnings on string members`() {
        val events =
            patternEvents(
                """
                namespace test

                @pattern("\t")
                string MyString

                structure MyStructure {
                    @pattern("\b")
                    field: String
                }
                """,
            )

        events shouldHaveSize 2
        events.forEach { it.severity shouldBe Severity.DANGER }
        events[0].shapeId.get() shouldBe ShapeId.from("test#MyString")
        events[1].shapeId.get() shouldBe ShapeId.from("test#MyStructure\$field")
    }

    @Test
    fun `should be suppressible with metadata suppressions`() {
        val events =
            patternEvents(
                """
                metadata suppressions = [
                    {
                        id: "PatternTraitEscapedSpecialChars",
                        namespace: "test",
                        reason: "Accepted for compatibility with existing models."
                    }
                ]

                namespace test

                @pattern("\t")
                string MyString
                """,
            )

        events shouldHaveSize 0
    }

    @Test
    fun `shouldn't error out if special chars are properly escaped`() {
        """
        namespace test

        @pattern("\\t")
        string MyString

        @pattern("[.\\n\\r]+")
        string MyString2

        @pattern("\\b\\f\\n\\r\\t")
        string MyString3

        @pattern("\\w+")
        string MyString4
        """.asSmithyModel(smithyVersion = "2")
    }

    private fun patternEvents(model: String): List<ValidationEvent> {
        val processed =
            if (model.trimStart().startsWith("\$version")) {
                model
            } else {
                "\$version: \"2\"\n$model"
            }
        return Model.assembler()
            .discoverModels()
            .addUnparsedModel("test.smithy", processed)
            .assemble()
            .validationEvents
            .filter { it.id == "PatternTraitEscapedSpecialChars" && !it.suppressionReason.isPresent }
    }
}
