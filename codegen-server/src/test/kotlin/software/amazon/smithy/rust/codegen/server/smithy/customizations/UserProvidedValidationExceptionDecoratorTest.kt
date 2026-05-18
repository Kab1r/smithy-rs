/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.customizations

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import software.amazon.smithy.framework.rust.ValidationExceptionTrait
import software.amazon.smithy.framework.rust.ValidationFieldListTrait
import software.amazon.smithy.framework.rust.ValidationFieldNameTrait
import software.amazon.smithy.framework.rust.ValidationMessageTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.server.smithy.testutil.HttpTestType
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverIntegrationTest
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.transformers.ReplaceFrameworkValidationExceptionWithUserDefined

internal class UserProvidedValidationExceptionDecoratorTest {
    private val modelWithCustomValidation =
        """
        namespace com.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage
        use smithy.framework.rust#validationFieldList
        use smithy.framework.rust#validationFieldName

        @restJson1
        service TestService {
            version: "1.0.0"
        }

        @validationException
        @error("client")
        structure MyValidationException {
            @validationMessage
            customMessage: String

            @validationFieldList
            customFieldList: ValidationExceptionFieldList
        }

        structure ValidationExceptionField {
            @validationFieldName
            path: String
            message: String
        }

        list ValidationExceptionFieldList {
            member: ValidationExceptionField
        }
        """.asSmithyModel(smithyVersion = "2.0")

    private val modelWithoutFieldList =
        """
        namespace com.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @restJson1
        service TestService {
            version: "1.0.0"
        }

        @validationException
        @error("client")
        structure MyValidationException {
            @validationMessage
            message: String
        }
        """.asSmithyModel(smithyVersion = "2.0")

    private fun mockValidationException(model: Model): StructureShape {
        val codegenContext = serverTestCodegenContext(model)
        val decorator = UserProvidedValidationExceptionDecorator()
        return decorator.firstStructureShapeWithValidationExceptionTrait(codegenContext.model)!!
    }

    @Test
    fun `firstStructureShapeWithValidationExceptionTrait returns correct shape`() {
        val result = mockValidationException(modelWithCustomValidation)

        result shouldNotBe null
        result.id shouldBe ShapeId.from("com.example#MyValidationException")
        result.hasTrait(ValidationExceptionTrait.ID) shouldBe true
    }

    @Test
    fun `firstStructureShapeWithValidationExceptionTrait returns null when no validation exception exists`() {
        val model =
            """
            namespace com.example

            use aws.protocols#restJson1

            @restJson1
            service TestService {
                version: "1.0.0"
            }

            structure RegularException { message: String }
            """.asSmithyModel(smithyVersion = "2.0")

        val codegenContext = serverTestCodegenContext(model)
        val decorator = UserProvidedValidationExceptionDecorator()

        val result = decorator.firstStructureShapeWithValidationExceptionTrait(codegenContext.model)

        result shouldBe null
    }

    @Test
    fun `validationMessageMember returns correct member shape`() {
        val model = modelWithCustomValidation
        val validationExceptionStructure = mockValidationException(model)

        val result = UserProvidedValidationExceptionDecorator().validationMessageMember(validationExceptionStructure)

        result shouldNotBe null
        result.memberName shouldBe "customMessage"
        result.hasTrait(ValidationMessageTrait.ID) shouldBe true
    }

    @Test
    fun `validationFieldListMember returns correct member shape`() {
        val model = modelWithCustomValidation
        val validationExceptionStructure = mockValidationException(model)
        val result =
            UserProvidedValidationExceptionDecorator().maybeValidationFieldList(
                model,
                validationExceptionStructure,
            )!!.validationFieldListMember

        result shouldNotBe null
        result.memberName shouldBe "customFieldList"
        result.hasTrait(ValidationFieldListTrait.ID) shouldBe true
    }

    @Test
    fun `maybeValidationFieldList returns null when no field list exists`() {
        val model = modelWithoutFieldList
        val validationExceptionStructure = mockValidationException(model)

        val result =
            UserProvidedValidationExceptionDecorator().maybeValidationFieldList(
                model,
                validationExceptionStructure,
            )

        result shouldBe null
    }

    @Test
    fun `validationFieldStructure returns correct structure shape`() {
        val model = modelWithCustomValidation
        val validationExceptionStructure = mockValidationException(model)

        val result =
            UserProvidedValidationExceptionDecorator().maybeValidationFieldList(
                model,
                validationExceptionStructure,
            )!!.validationFieldStructure

        result shouldNotBe null
        result.id shouldBe ShapeId.from("com.example#ValidationExceptionField")
        result.members().any { it.hasTrait(ValidationFieldNameTrait.ID) } shouldBe true
    }

    @Test
    fun `decorator returns null when no custom validation exception exists`() {
        val model =
            """
            namespace com.example

            use aws.protocols#restJson1

            @restJson1
            service TestService {
                version: "1.0.0"
            }

            structure RegularException { message: String }
            """.asSmithyModel(smithyVersion = "2.0")

        val codegenContext = serverTestCodegenContext(model)
        val decorator = UserProvidedValidationExceptionDecorator()

        val generator = decorator.validationExceptionConversion(codegenContext)

        generator shouldBe null
    }

    private val completeTestModel =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationFieldList
        use smithy.framework.rust#validationFieldMessage
        use smithy.framework.rust#validationFieldName
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
                StreamingOperation
                PublishMessages
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        @http(method: "GET", uri: "/streaming-operation")
        @readonly
        operation StreamingOperation {
            input := {}
            output := {
                @httpPayload
                output: StreamingBlob = ""
            }
        }

        @streaming
        blob StreamingBlob

        @http(method: "POST", uri: "/publish")
        operation PublishMessages {
            input: PublishMessagesInput
        }

        @input
        structure PublishMessagesInput {
            @httpPayload
            messages: PublishEvents
        }

        @streaming
        union PublishEvents {
            message: Message
            leave: LeaveEvent
        }

        structure Message {
            message: String
        }

        structure LeaveEvent {}

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String

            @range(min: 1, max: 100)
            age: Integer
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @required
            @validationMessage
            customMessage: String

            @required
            @default("testReason1")
            reason: ValidationExceptionReason

            @validationFieldList
            customFieldList: CustomValidationFieldList
        }

        enum ValidationExceptionReason {
            TEST_REASON_0 = "testReason0"
            TEST_REASON_1 = "testReason1"
        }

        structure CustomValidationField {
            @required
            @validationFieldName
            customFieldName: String

            @required
            @validationFieldMessage
            customFieldMessage: String
        }

        list CustomValidationFieldList {
            member: CustomValidationField
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with custom validation exception`() {
        serverIntegrationTest(completeTestModel, testCoverage = HttpTestType.Default)
    }

    private val completeTestModelWithOptionals =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationFieldList
        use smithy.framework.rust#validationFieldMessage
        use smithy.framework.rust#validationFieldName
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String

            @range(min: 1, max: 100)
            age: Integer
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @validationMessage
            customMessage: String

            @default("testReason1")
            reason: ValidationExceptionReason

            @validationFieldList
            customFieldList: CustomValidationFieldList
        }

        enum ValidationExceptionReason {
            TEST_REASON_0 = "testReason0"
            TEST_REASON_1 = "testReason1"
        }

        structure CustomValidationField {
            @validationFieldName
            customFieldName: String

            @validationFieldMessage
            customFieldMessage: String
        }

        list CustomValidationFieldList {
            member: CustomValidationField
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with custom validation exception using optionals`() {
        serverIntegrationTest(completeTestModelWithOptionals, testCoverage = HttpTestType.Default)
    }

    private val completeTestModelWithImplicitNamesWithoutFieldMessage =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationFieldList

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String

            @range(min: 1, max: 100)
            age: Integer
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            message: String

            @validationFieldList
            customFieldList: CustomValidationFieldList
        }

        structure CustomValidationField {
            name: String,
        }

        list CustomValidationFieldList {
            member: CustomValidationField
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with implicit message and field name and without field message`() {
        serverIntegrationTest(completeTestModelWithImplicitNamesWithoutFieldMessage)
    }

    private val modelWithConstrainedMessage =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @validationMessage
            message: ErrorMessage
        }

        @length(min: 0, max: 2048)
        string ErrorMessage
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with constrained optional validation message`() {
        serverIntegrationTest(modelWithConstrainedMessage, testCoverage = HttpTestType.Default)
    }

    private val modelWithRequiredConstrainedMessage =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @required
            @validationMessage
            message: ErrorMessage
        }

        @length(min: 0, max: 2048)
        string ErrorMessage
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with required constrained validation message`() {
        serverIntegrationTest(modelWithRequiredConstrainedMessage, testCoverage = HttpTestType.Default)
    }

    private val modelWithConstrainedAdditionalFieldDefault =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @validationMessage
            message: String

            @default("ok")
            tag: ConstrainedTag
        }

        @length(min: 0, max: 16)
        string ConstrainedTag
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with constrained additional field default`() {
        serverIntegrationTest(modelWithConstrainedAdditionalFieldDefault, testCoverage = HttpTestType.Default)
    }

    private val modelWithValidationExceptionMemberDefault =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationExceptionMemberDefault
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @validationMessage
            message: String

            @required
            @validationExceptionMemberDefault("fieldValidationFailed")
            reason: ValidationExceptionReason
        }

        @enum([
            { value: "fieldValidationFailed", name: "FIELD_VALIDATION_FAILED" },
            { value: "other", name: "OTHER" }
        ])
        string ValidationExceptionReason
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with validationExceptionMemberDefault on constrained additional field`() {
        val generatedServers = serverIntegrationTest(modelWithValidationExceptionMemberDefault, testCoverage = HttpTestType.Default)
        val generatedInput = generatedServers.single().path.resolve("src/input.rs").toFile().readText()
        generatedInput shouldContain "reason: crate::model::ValidationExceptionReason::FieldValidationFailed"
    }

    private val modelWithOptionalValidationExceptionMemberDefault =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationExceptionMemberDefault
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @validationMessage
            message: String

            @validationExceptionMemberDefault("fieldValidationFailed")
            reason: ValidationExceptionReason
        }

        @enum([
            { value: "fieldValidationFailed", name: "FIELD_VALIDATION_FAILED" },
            { value: "other", name: "OTHER" }
        ])
        string ValidationExceptionReason
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with validationExceptionMemberDefault on optional additional field`() {
        val generatedServers = serverIntegrationTest(modelWithOptionalValidationExceptionMemberDefault, testCoverage = HttpTestType.Default)
        val generatedInput = generatedServers.single().path.resolve("src/input.rs").toFile().readText()
        generatedInput shouldContain "reason: Some(crate::model::ValidationExceptionReason::FieldValidationFailed)"
    }

    private val modelWithCollidingFrameworkValidationException =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
            errors: [smithy.framework#ValidationException]
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure ValidationException {
            @required
            @validationMessage
            message: String
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `framework validation exception is not emitted when a user-modeled one exists with the same local name`() {
        val generatedServers = serverIntegrationTest(modelWithCollidingFrameworkValidationException, testCoverage = HttpTestType.Default)
        val errorRs = generatedServers.single().path.resolve("src/error.rs").toFile().readText()
        val occurrences = Regex("""pub struct ValidationException\b""").findAll(errorRs).count()
        occurrences shouldBe 1
    }

    private val modelWithFieldNamedPath =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationFieldList

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [TestOperation]
            errors: [MyValidationException]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyValidationException {
            message: String

            @validationFieldList
            fieldList: MyValidationFieldList
        }

        structure MyValidationField {
            path: String
            message: String
        }

        list MyValidationFieldList {
            member: MyValidationField
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with implicit path member name`() {
        serverIntegrationTest(modelWithFieldNamedPath, testCoverage = HttpTestType.Default)
    }

    private val modelWithCollidingFrameworkValidationExceptionField =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationFieldList
        use smithy.framework.rust#validationFieldMessage
        use smithy.framework.rust#validationFieldName
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [TestOperation, ShowField]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
            errors: [smithy.framework#ValidationException]
        }

        @http(method: "GET", uri: "/show-field")
        @readonly
        operation ShowField {
            output: ShowFieldOutput
        }

        structure ShowFieldOutput {
            // References framework's ValidationExceptionField directly so it stays reachable
            field: smithy.framework#ValidationExceptionField
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure ValidationException {
            @required
            @validationMessage
            message: String

            @validationFieldList
            fieldList: ValidationExceptionFieldList
        }

        structure ValidationExceptionField {
            @required
            @validationFieldName
            name: String

            @required
            @validationFieldMessage
            message: String
        }

        list ValidationExceptionFieldList {
            member: ValidationExceptionField
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `framework validation exception field is not emitted when a user-modeled one exists with the same local name`() {
        val generatedServers = serverIntegrationTest(modelWithCollidingFrameworkValidationExceptionField, testCoverage = HttpTestType.Default)
        val modelRs = generatedServers.single().path.resolve("src/model.rs").toFile().readText()
        val occurrences = Regex("""pub struct ValidationExceptionField\b""").findAll(modelRs).count()
        occurrences shouldBe 1
    }

    private val modelWithUnnamedLegacyEnumDefault =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationExceptionMemberDefault
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @validationMessage
            message: String

            @required
            @validationExceptionMemberDefault("UnknownOperation")
            reason: ValidationExceptionReason
        }

        @enum([
            { value: "UnknownOperation" },
            { value: "CannotParse" },
            { value: "Other" }
        ])
        string ValidationExceptionReason
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with validationExceptionMemberDefault on legacy enum without names`() {
        val generatedServers = serverIntegrationTest(modelWithUnnamedLegacyEnumDefault, testCoverage = HttpTestType.Default)
        val generatedInput = generatedServers.single().path.resolve("src/input.rs").toFile().readText()
        // The unnamed legacy enum is rendered as a constrained-string newtype, so the assignment must
        // route through TryFrom<&str> rather than a non-existent `::UnknownOperation` associated item.
        generatedInput shouldContain "ValidationExceptionReason"
        generatedInput shouldContain "UnknownOperation"
    }

    private val modelWithDefaultedConstrainedListAdditionalField =
        """
        namespace com.aws.example

        use aws.protocols#restJson1
        use smithy.framework.rust#validationException
        use smithy.framework.rust#validationMessage

        @restJson1
        service CustomValidationExample {
            version: "1.0.0"
            operations: [
                TestOperation
            ]
            errors: [
                MyCustomValidationException
            ]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        @error("client")
        @httpError(400)
        @validationException
        structure MyCustomValidationException {
            @validationMessage
            message: String

            resources: Resources = []
        }

        // A constrained list whose constraints permit the empty-list `@default`. The constrained newtype
        // does not implement `Default`, so the framework cannot fall back to `Default::default()` for the
        // additional VE member — the codegen has to construct the wrapper directly.
        @uniqueItems
        list Resources {
            member: String
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `code compiles with defaulted constrained-list additional field`() {
        serverIntegrationTest(modelWithDefaultedConstrainedListAdditionalField, testCoverage = HttpTestType.Default)
    }

    private val modelWithFieldOnlyButNoCustomException =
        """
        namespace com.aws.example

        use aws.protocols#restJson1

        @restJson1
        service NoCustomExceptionExample {
            version: "1.0.0"
            operations: [TestOperation]
        }

        @http(method: "POST", uri: "/test")
        operation TestOperation {
            input: TestInput
            errors: [smithy.framework#ValidationException]
        }

        structure TestInput {
            @required
            @length(min: 1, max: 10)
            name: String
        }

        // The model declares its own `ValidationExceptionField` (and a list-of-fields shape) but does
        // *not* declare a `@validationException`-marked structure. The framework's
        // `smithy.framework#ValidationExceptionField` still reaches codegen via
        // `smithy.framework#ValidationException`, and the two structures share their local name —
        // resulting in two top-level `pub struct ValidationExceptionField` definitions without a fix.
        structure ValidationExceptionField {
            @required
            name: String

            @required
            message: String
        }

        list ValidationExceptionFieldList {
            member: ValidationExceptionField
        }
        """.asSmithyModel(smithyVersion = "2.0")

    @Test
    fun `framework validation exception field is dropped from the model when the user declares its own without a custom validation exception`() {
        val transformed =
            ReplaceFrameworkValidationExceptionWithUserDefined.transform(modelWithFieldOnlyButNoCustomException)
        // The framework's `ValidationExceptionField` must be removed; otherwise it would collide with
        // the user-modeled one. The framework `ValidationException` stays — there is no user-modeled
        // exception to take its place.
        transformed.getShape(ShapeId.from("smithy.framework#ValidationExceptionField")).isPresent shouldBe false
        transformed.getShape(ShapeId.from("smithy.framework#ValidationExceptionFieldList")).isPresent shouldBe false
        transformed.getShape(ShapeId.from("smithy.framework#ValidationException")).isPresent shouldBe true
        // The user-modeled field/list must survive and be the type the framework `ValidationException`
        // points at.
        val frameworkException =
            transformed.expectShape(ShapeId.from("smithy.framework#ValidationException"), StructureShape::class.java)
        val fieldListMember = frameworkException.members().first { it.memberName == "fieldList" }
        fieldListMember.target shouldBe ShapeId.from("com.aws.example#ValidationExceptionFieldList")
    }
}
