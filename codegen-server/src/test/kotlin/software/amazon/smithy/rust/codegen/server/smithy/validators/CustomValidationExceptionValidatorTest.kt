/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.validators

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.validation.Severity
import software.amazon.smithy.model.validation.ValidatedResultException
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel

class CustomValidationExceptionValidatorTest {
    @Test
    fun `should error when validationException lacks error trait`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationMessage

                @validationException
                structure ValidationError {
                    @validationMessage
                    message: String
                }
                """.asSmithyModel(smithyVersion = "2")
            }
        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError")
        events[0].id shouldBe "CustomValidationException.MissingErrorTrait"
    }

    @Test
    fun `should error when validationException has no validationMessage field`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException

                @validationException
                @error("client")
                structure ValidationError {
                    code: String
                }
                """.asSmithyModel(smithyVersion = "2")
            }
        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError")
        events[0].id shouldBe "CustomValidationException.MissingMessageField"
    }

    @Test
    fun `should error when validationException has multiple explicit validationMessage fields`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationMessage

                @validationException
                @error("client")
                structure ValidationError {
                    @validationMessage
                    message: String,
                    @validationMessage
                    details: String
                }
                """.asSmithyModel(smithyVersion = "2")
            }
        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError")
        events[0].id shouldBe "CustomValidationException.MultipleMessageFields"
    }

    @Test
    fun `should error when validationException has explicit validationMessage and implicit message fields`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationMessage

                @validationException
                @error("client")
                structure ValidationError {
                    message: String,
                    @validationMessage
                    details: String,
                }
                """.asSmithyModel(smithyVersion = "2")
            }
        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError")
        events[0].id shouldBe "CustomValidationException.MultipleMessageFields"
    }

    @Test
    fun `should error when constrained shape lacks default trait`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationMessage

                @validationException
                @error("client")
                structure ValidationError {
                    @validationMessage
                    message: String,
                    constrainedField: ConstrainedString
                }

                @length(min: 1, max: 10)
                string ConstrainedString
                """.asSmithyModel(smithyVersion = "2")
            }
        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].id shouldBe "CustomValidationException.MissingDefault"
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$constrainedField")
    }

    @Test
    fun `should pass validation for properly configured validationException`() {
        """
        namespace test
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @validationException
        @error("client")
        structure ValidationError {
            @validationMessage
            message: String
        }
        """.asSmithyModel(smithyVersion = "2")
    }

    @Test
    fun `should pass validation for validationException with constrained shape having default`() {
        """
        namespace test
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @validationException
        @error("client")
        structure ValidationError {
            @validationMessage
            message: String,
            @default("default")
            constrainedField: ConstrainedString
        }

        @length(min: 1, max: 10)
        @default("default")
        string ConstrainedString
        """.asSmithyModel(smithyVersion = "2")
    }

    @Test
    fun `should allow validation message member to target constrained string without default`() {
        """
        namespace test
        use smithy.framework.rust#validationException

        @validationException
        @error("client")
        structure ValidationError {
            message: ConstrainedMessage
        }

        @length(min: 1, max: 1024)
        string ConstrainedMessage
        """.asSmithyModel(smithyVersion = "2")
    }

    @Test
    fun `should allow validation field list member to target constrained list without default`() {
        """
        namespace test
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationFieldList

        @validationException
        @error("client")
        structure ValidationError {
            message: String,

            @validationFieldList
            fieldList: ConstrainedValidationFieldList
        }

        @length(min: 1)
        list ConstrainedValidationFieldList {
            member: ValidationField
        }

        structure ValidationField {
            name: String
        }
        """.asSmithyModel(smithyVersion = "2")
    }

    @Test
    fun `should accept PascalCase Message as the canonical validation message member`() {
        // awsQuery / restXml AWS service models commonly declare the validation-exception message
        // member as PascalCase Message to match the wire format. After tightening, PascalCase
        // variants require the explicit @validationMessage trait — the fixture was updated to
        // reflect this while preserving the test's invariant that the VE is accepted.
        """
        namespace test
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @validationException
        @error("client")
        structure ValidationException {
            Code: String,
            @validationMessage
            Message: String,
        }
        """.asSmithyModel(smithyVersion = "2")
    }

    @Test
    fun `should accept uppercase MESSAGE as the canonical validation message member`() {
        // After tightening, non-lowercase variants require the explicit @validationMessage trait.
        // The fixture was updated to add the trait while preserving the test's invariant.
        """
        namespace test
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @validationException
        @error("client")
        structure ValidationException {
            @validationMessage
            MESSAGE: String,
        }
        """.asSmithyModel(smithyVersion = "2")
    }

    @Test
    fun `should allow validationExceptionMemberDefault on non-canonical constrained members`() {
        """
        namespace test
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationExceptionMemberDefault

        @validationException
        @error("client")
        structure ValidationError {
            message: String,

            @validationExceptionMemberDefault("fieldValidationFailed")
            reason: ValidationExceptionReason
        }

        @enum([
            { value: "fieldValidationFailed", name: "FIELD_VALIDATION_FAILED" },
            { value: "other", name: "OTHER" }
        ])
        string ValidationExceptionReason
        """.asSmithyModel(smithyVersion = "2").also { model ->
            val reason = model.expectShape(ShapeId.from("test#ValidationError${'$'}reason"))
            reason.hasTrait(software.amazon.smithy.framework.rust.ValidationExceptionMemberDefaultTrait.ID) shouldBe true
        }
    }

    @Test
    fun `should error when validationExceptionMemberDefault does not satisfy an enum target`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationExceptionMemberDefault

                @validationException
                @error("client")
                structure ValidationError {
                    message: String,

                    @validationExceptionMemberDefault("notAValidVariant")
                    reason: ValidationExceptionReason
                }

                @enum([
                    { value: "fieldValidationFailed", name: "FIELD_VALIDATION_FAILED" },
                    { value: "other", name: "OTHER" }
                ])
                string ValidationExceptionReason
                """.asSmithyModel(smithyVersion = "2")
            }

        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].id shouldBe "ValidationExceptionMemberDefault.InvalidValue"
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError${'$'}reason")
    }

    // ── New tests added by Tasks 12 + 13 ──────────────────────────────────────────────────────

    @Test
    fun `should emit WARNING when message member relies on lowercase name fallback without trait`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException

            @validationException
            @error("client")
            structure ValidationError {
                message: String
            }
            """.asSmithyModel(smithyVersion = "2")
        val warnings =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.WARNING && it.id == "CustomValidationException.ImplicitMessageField" }
        warnings shouldHaveSize 1
        warnings[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$message")
    }

    @Test
    fun `should NOT emit WARNING when message member carries the explicit validationMessage trait`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String
            }
            """.asSmithyModel(smithyVersion = "2")
        val warnings =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.WARNING && it.id == "CustomValidationException.ImplicitMessageField" }
        warnings shouldHaveSize 0
    }

    @Test
    fun `should emit WARNING when field-name member relies on lowercase name fallback without trait`() {
        // Fixture: a VE with a member literally named "name" without @validationFieldName.
        // The validator walks @validationException structures and emits an
        // ImplicitFieldNameField WARNING for any "name" or "path" member that lacks the trait.
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                name: String
            }
            """.asSmithyModel(smithyVersion = "2")
        val warnings =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.WARNING && it.id == "CustomValidationException.ImplicitFieldNameField" }
        warnings shouldHaveSize 1
        warnings[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$name")
    }

    @Test
    fun `should NOT emit WARNING when field-name member carries the explicit validationFieldName trait`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationFieldName
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                @validationFieldName
                name: String
            }
            """.asSmithyModel(smithyVersion = "2")
        val warnings =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.WARNING && it.id == "CustomValidationException.ImplicitFieldNameField" }
        warnings shouldHaveSize 0
    }

    @Test
    fun `should still require defaults for non-canonical constrained members`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException

                @validationException
                @error("client")
                structure ValidationError {
                    message: String,
                    extraField: ConstrainedString
                }

                @pattern("^[a-z]+${'$'}")
                string ConstrainedString
                """.asSmithyModel(smithyVersion = "2")
            }

        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].id shouldBe "CustomValidationException.MissingDefault"
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$extraField")
    }

    // ── New tests added by Task 14 ────────────────────────────────────────────────────────────

    @Test
    fun `should emit WARNING when field-list member relies on canonical name fallback without trait`() {
        // Fixture: a VE with a member literally named "fieldList" without @validationFieldList.
        // The validator walks @validationException structures and emits an
        // ImplicitFieldListField WARNING for "fieldList" or "field_list" members that lack the trait.
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                fieldList: SomeList
            }

            list SomeList {
                member: SomeField
            }

            structure SomeField {
                name: String
            }
            """.asSmithyModel(smithyVersion = "2")
        val warnings =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.WARNING && it.id == "CustomValidationException.ImplicitFieldListField" }
        warnings shouldHaveSize 1
        warnings[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$fieldList")
    }

    @Test
    fun `should NOT emit WARNING when field-list member carries the explicit validationFieldList trait`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationFieldList
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                @validationFieldList
                fieldList: SomeList
            }

            list SomeList {
                member: SomeField
            }

            structure SomeField {
                name: String
            }
            """.asSmithyModel(smithyVersion = "2")
        val warnings =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.WARNING && it.id == "CustomValidationException.ImplicitFieldListField" }
        warnings shouldHaveSize 0
    }

    // ── New tests added by Task 15 ────────────────────────────────────────────────────────────

    @Test
    fun `should emit ERROR when default value violates string @length constraint`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationExceptionMemberDefault
                use smithy.framework.rust#validationMessage

                @validationException
                @error("client")
                structure ValidationError {
                    @validationMessage
                    message: String,

                    @validationExceptionMemberDefault("ab")
                    extraField: ConstrainedString
                }

                @length(min: 3, max: 10)
                string ConstrainedString
                """.asSmithyModel(smithyVersion = "2")
            }

        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }
        val relevant = events.filter { it.id == "ValidationExceptionMemberDefault.LengthConstraintViolation" }
        relevant shouldHaveSize 1
        relevant[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$extraField")
    }

    @Test
    fun `should emit ERROR when default value violates string @pattern constraint`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationExceptionMemberDefault
                use smithy.framework.rust#validationMessage

                @validationException
                @error("client")
                structure ValidationError {
                    @validationMessage
                    message: String,

                    @validationExceptionMemberDefault("123abc")
                    extraField: LowercaseString
                }

                @pattern("^[a-z]+${'$'}")
                string LowercaseString
                """.asSmithyModel(smithyVersion = "2")
            }

        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }
        val relevant = events.filter { it.id == "ValidationExceptionMemberDefault.PatternConstraintViolation" }
        relevant shouldHaveSize 1
        relevant[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$extraField")
    }

    @Test
    fun `should emit ERROR when default value violates number @range constraint`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationExceptionMemberDefault
                use smithy.framework.rust#validationMessage

                @validationException
                @error("client")
                structure ValidationError {
                    @validationMessage
                    message: String,

                    @validationExceptionMemberDefault("200")
                    extraField: BoundedInt
                }

                @range(min: 0, max: 100)
                integer BoundedInt
                """.asSmithyModel(smithyVersion = "2")
            }

        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }
        val relevant = events.filter { it.id == "ValidationExceptionMemberDefault.RangeConstraintViolation" }
        relevant shouldHaveSize 1
        relevant[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$extraField")
    }

    @Test
    fun `should accept default value satisfying string @length constraint`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationExceptionMemberDefault
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                @validationExceptionMemberDefault("abc")
                extraField: ConstrainedString
            }

            @length(min: 3, max: 10)
            string ConstrainedString
            """.asSmithyModel(smithyVersion = "2")

        val events =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.ERROR && it.id == "ValidationExceptionMemberDefault.LengthConstraintViolation" }
        events shouldHaveSize 0
    }

    @Test
    fun `should accept default value satisfying string @pattern constraint`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationExceptionMemberDefault
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                @validationExceptionMemberDefault("abc")
                extraField: LowercaseString
            }

            @pattern("^[a-z]+${'$'}")
            string LowercaseString
            """.asSmithyModel(smithyVersion = "2")

        val events =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.ERROR && it.id == "ValidationExceptionMemberDefault.PatternConstraintViolation" }
        events shouldHaveSize 0
    }

    @Test
    fun `should accept default value satisfying number @range constraint`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationExceptionMemberDefault
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                @validationExceptionMemberDefault("50")
                extraField: BoundedInt
            }

            @range(min: 0, max: 100)
            integer BoundedInt
            """.asSmithyModel(smithyVersion = "2")

        val events =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.severity == Severity.ERROR && it.id == "ValidationExceptionMemberDefault.RangeConstraintViolation" }
        events shouldHaveSize 0
    }

    @Test
    fun `should emit ERROR when default value cannot be parsed as a number`() {
        val exception =
            shouldThrow<ValidatedResultException> {
                """
                namespace test
                use smithy.framework.rust#validationException
                use smithy.framework.rust#validationExceptionMemberDefault
                use smithy.framework.rust#validationMessage

                @validationException
                @error("client")
                structure ValidationError {
                    @validationMessage
                    message: String,

                    @validationExceptionMemberDefault("not-a-number")
                    extraField: BoundedInt
                }

                @range(min: 0, max: 100)
                integer BoundedInt
                """.asSmithyModel(smithyVersion = "2")
            }

        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }
        val relevant = events.filter { it.id == "ValidationExceptionMemberDefault.InvalidNumberValue" }
        relevant shouldHaveSize 1
        relevant[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError\$extraField")
    }

    // Note: Test #7 from the task plan (should emit WARNING when @pattern regex cannot be compiled) cannot
    // be expressed via the normal asSmithyModel() path because smithy.api#PatternTrait eagerly compiles
    // the pattern in its constructor and throws a SourceException for malformed patterns before the model
    // is assembled — so an invalid pattern string never reaches our validator. The try/catch guard in the
    // validator exists as defensive code to handle any future Smithy API changes where this might change.
    //
    // Instead, we verify here that when a valid pattern is used, no spurious WARNING is emitted:
    @Test
    fun `should NOT emit UnparseablePattern WARNING for a valid @pattern regex`() {
        val model =
            """
            namespace test
            use smithy.framework.rust#validationException
            use smithy.framework.rust#validationExceptionMemberDefault
            use smithy.framework.rust#validationMessage

            @validationException
            @error("client")
            structure ValidationError {
                @validationMessage
                message: String,

                @validationExceptionMemberDefault("abc")
                extraField: LowercaseString
            }

            @pattern("^[a-z]+${'$'}")
            string LowercaseString
            """.asSmithyModel(smithyVersion = "2")

        val warnings =
            CustomValidationExceptionValidator().validate(model)
                .filter { it.id == "ValidationExceptionMemberDefault.UnparseablePattern" }
        warnings shouldHaveSize 0
    }
}
