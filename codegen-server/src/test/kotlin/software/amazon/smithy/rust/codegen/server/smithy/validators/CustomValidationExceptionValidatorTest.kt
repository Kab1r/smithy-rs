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
        // member as PascalCase Message to match the wire format. Recognizing the lowercase variant
        // alone aborted these services at smithy-build with MissingMessageField; the matcher must
        // accept any case-folded "message" alongside the explicit @validationMessage annotation.
        """
        namespace test
        use smithy.framework.rust#validationException

        @validationException
        @error("client")
        structure ValidationException {
            Code: String,
            Message: String,
        }
        """.asSmithyModel(smithyVersion = "2")
    }

    @Test
    fun `should accept uppercase MESSAGE as the canonical validation message member`() {
        """
        namespace test
        use smithy.framework.rust#validationException

        @validationException
        @error("client")
        structure ValidationException {
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

        enum ValidationExceptionReason {
            FIELD_VALIDATION_FAILED = "fieldValidationFailed"
            OTHER = "other"
        }
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

                enum ValidationExceptionReason {
                    FIELD_VALIDATION_FAILED = "fieldValidationFailed"
                    OTHER = "other"
                }
                """.asSmithyModel(smithyVersion = "2")
            }

        val events = exception.validationEvents.filter { it.severity == Severity.ERROR }

        events shouldHaveSize 1
        events[0].id shouldBe "ValidationExceptionMemberDefault.InvalidValue"
        events[0].shapeId.get() shouldBe ShapeId.from("test#ValidationError${'$'}reason")
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
}
