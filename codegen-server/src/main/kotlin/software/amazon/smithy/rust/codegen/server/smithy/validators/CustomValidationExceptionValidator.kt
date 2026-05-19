/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.validators

import software.amazon.smithy.framework.rust.ValidationExceptionMemberDefaultTrait
import software.amazon.smithy.framework.rust.ValidationExceptionTrait
import software.amazon.smithy.framework.rust.ValidationFieldListTrait
import software.amazon.smithy.framework.rust.ValidationFieldNameTrait
import software.amazon.smithy.framework.rust.ValidationMessageTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeType
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.DefaultTrait
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.EnumValueTrait
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.LengthTrait
import software.amazon.smithy.model.traits.PatternTrait
import software.amazon.smithy.model.traits.RangeTrait
import software.amazon.smithy.model.validation.AbstractValidator
import software.amazon.smithy.model.validation.Severity
import software.amazon.smithy.model.validation.ValidationEvent
import software.amazon.smithy.rust.codegen.core.util.expectTrait
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.core.util.targetOrSelf
import software.amazon.smithy.rust.codegen.server.smithy.canReachConstrainedShapeForValidation
import software.amazon.smithy.rust.codegen.server.smithy.isDirectlyConstrainedForValidation
import software.amazon.smithy.rust.codegen.server.smithy.util.isValidationMessage
import java.math.BigDecimal
import java.util.regex.PatternSyntaxException

class CustomValidationExceptionValidator : AbstractValidator() {
    override fun validate(model: Model): List<ValidationEvent> {
        val events = mutableListOf<ValidationEvent>()

        model.shapes(StructureShape::class.java).filter { it.hasTrait(ValidationExceptionTrait.ID) }
            .forEach { shape ->
                // Validate that the shape also has @error trait
                if (!shape.hasTrait(ErrorTrait::class.java)) {
                    events.add(
                        ValidationEvent.builder().id("CustomValidationException.MissingErrorTrait")
                            .severity(Severity.ERROR).shape(shape)
                            .message("@validationException requires @error trait")
                            .build(),
                    )
                }

                // Validate exactly one member with @validationMessage trait (explicit) or named "message" (implicit)
                val messageFields =
                    shape.members().filter { it.isValidationMessage() }

                when (messageFields.size) {
                    0 ->
                        events.add(
                            ValidationEvent.builder().id("CustomValidationException.MissingMessageField")
                                .severity(Severity.ERROR).shape(shape)
                                .message(
                                    "@validationException requires exactly one String member named " +
                                        "\"message\" or with the @validationMessage trait",
                                ).build(),
                        )

                    1 -> {
                        val validationMessageField = messageFields.first()
                        if (!model.expectShape(validationMessageField.target).isStringShape) {
                            events.add(
                                ValidationEvent.builder().id("CustomValidationException.NonStringMessageField")
                                    .severity(Severity.ERROR).shape(shape)
                                    .message("@validationMessage field must be a String").build(),
                            )
                        }
                    }

                    else ->
                        events.add(
                            ValidationEvent.builder().id("CustomValidationException.MultipleMessageFields")
                                .severity(Severity.ERROR).shape(shape)
                                .message(
                                    "@validationException can have only one member explicitly marked with the" +
                                        "@validationMessage trait or implicitly selected via the \"message\" field name convention.",
                                ).build(),
                        )
                }

                // Warn when a model relies on the lowercase name fallback rather than the explicit trait.
                // This nudges users towards annotating with @validationMessage / @validationFieldName.
                shape.members()
                    .filter { it.memberName == "message" && !it.hasTrait(ValidationMessageTrait.ID) }
                    .forEach { member ->
                        events.add(
                            ValidationEvent.builder()
                                .id("CustomValidationException.ImplicitMessageField")
                                .severity(Severity.WARNING)
                                .shape(member)
                                .message(
                                    "Member \"${member.memberName}\" is treated as the validation message by " +
                                        "name convention. Apply the @validationMessage trait explicitly to " +
                                        "avoid relying on the implicit name fallback.",
                                ).build(),
                        )
                    }

                shape.members()
                    .filter {
                        (it.memberName == "name" || it.memberName == "path") &&
                            !it.hasTrait(ValidationFieldNameTrait.ID)
                    }
                    .forEach { member ->
                        events.add(
                            ValidationEvent.builder()
                                .id("CustomValidationException.ImplicitFieldNameField")
                                .severity(Severity.WARNING)
                                .shape(member)
                                .message(
                                    "Member \"${member.memberName}\" is treated as the validation field name by " +
                                        "name convention. Apply the @validationFieldName trait explicitly to " +
                                        "avoid relying on the implicit name fallback.",
                                ).build(),
                        )
                    }

                shape.members()
                    .filter {
                        (it.memberName == "fieldList" || it.memberName == "field_list") &&
                            !it.hasTrait(ValidationFieldListTrait.ID)
                    }
                    .forEach { member ->
                        events.add(
                            ValidationEvent.builder()
                                .id("CustomValidationException.ImplicitFieldListField")
                                .severity(Severity.WARNING)
                                .shape(member)
                                .message(
                                    "Member \"${member.memberName}\" is treated as the validation field list by " +
                                        "name convention. Apply the @validationFieldList trait explicitly to " +
                                        "avoid relying on the implicit name fallback.",
                                ).build(),
                        )
                    }

                // Validate default constructibility if it contains constrained shapes
                if (shape.canReachConstrainedShapeForValidation(model)) {
                    shape.members().forEach { member -> member.validateDefaultConstructibility(model, events) }
                }
            }

        return events
    }

    /** Validate default constructibility of the shape
     * When a validation exception occurs, the framework has to create a Rust type that represents
     * the ValidationException structure, but if that structure has fields other than 'message' and
     * 'field list', then it can't instantiate them if they don't have defaults. Later on, we will introduce
     * a mechanism for service code to be able to participate in construction of a validation exception type.
     * Until that time, we need to restrict this to default constructibility.
     */
    private fun Shape.validateDefaultConstructibility(
        model: Model,
        events: MutableList<ValidationEvent>,
    ) {
        when (this.type) {
            ShapeType.STRUCTURE -> {
                this.members().forEach { member -> member.validateDefaultConstructibility(model, events) }
            }

            ShapeType.MEMBER -> {
                val member = this.asMemberShape().get()
                val isCanonicalValidationExceptionMember =
                    member.isValidationMessage() || member.hasTrait(ValidationFieldListTrait.ID)
                member.validateValidationExceptionMemberDefault(model, events)
                // We want to check if the member's target is constrained. If so, we want the default trait to be on the
                // member.
                if (!isCanonicalValidationExceptionMember &&
                    !this.hasTrait(ValidationExceptionMemberDefaultTrait.ID) &&
                    this.targetOrSelf(model).isDirectlyConstrainedForValidation() &&
                    !this.hasTrait<DefaultTrait>()
                ) {
                    events.add(
                        ValidationEvent.builder().id("CustomValidationException.MissingDefault")
                            .severity(Severity.ERROR)
                            .shape(this)
                            .message("$this must be default constructible")
                            .build(),
                    )
                }
            }

            else -> return
        }
    }

    private fun Shape.validateValidationExceptionMemberDefault(
        model: Model,
        events: MutableList<ValidationEvent>,
    ) {
        val trait = this.findTrait(ValidationExceptionMemberDefaultTrait.ID).orElse(null) ?: return
        val member = this.asMemberShape().get()
        val value = trait.toNode().expectStringNode().value
        val target = member.targetOrSelf(model)

        val isValid =
            when {
                target.isEnumShape -> {
                    val enumShape = target.asEnumShape().get()
                    enumShape.members().any { enumMember ->
                        enumMember.memberName == value ||
                            enumMember.getTrait(EnumValueTrait::class.java)
                                .map { it.stringValue.orElse(enumMember.memberName) }
                                .orElse(enumMember.memberName) == value
                    }
                }

                target.hasTrait<EnumTrait>() ->
                    target.expectTrait<EnumTrait>().values.any { enumDefinition ->
                        enumDefinition.value == value || enumDefinition.name.orElse(null) == value
                    }

                else -> true
            }

        if (!isValid) {
            events.add(
                ValidationEvent.builder().id("ValidationExceptionMemberDefault.InvalidValue")
                    .severity(Severity.ERROR)
                    .shape(this)
                    .message("$member has an invalid @validationExceptionMemberDefault value: $value")
                    .build(),
            )
        }

        // Check @length constraint for string targets.
        if (target.isStringShape) {
            target.getTrait(LengthTrait::class.java).orNull()?.let { lengthTrait ->
                val charCount = value.codePointCount(0, value.length).toLong()
                val min = lengthTrait.min.orNull()
                val max = lengthTrait.max.orNull()
                val violated = (min != null && charCount < min) || (max != null && charCount > max)
                if (violated) {
                    val constraint =
                        when {
                            min != null && max != null -> "between $min and $max"
                            min != null -> "at least $min"
                            else -> "at most $max"
                        }
                    events.add(
                        ValidationEvent.builder()
                            .id("ValidationExceptionMemberDefault.LengthConstraintViolation")
                            .severity(Severity.ERROR)
                            .shape(this)
                            .message(
                                "$member has @validationExceptionMemberDefault value \"$value\" " +
                                    "with length $charCount, which violates the @length constraint ($constraint characters)",
                            ).build(),
                    )
                }
            }

            // Check @pattern constraint for string targets.
            target.getTrait(PatternTrait::class.java).orNull()?.let { patternTrait ->
                val patternString = patternTrait.value
                try {
                    val compiled = java.util.regex.Pattern.compile(patternString)
                    if (!compiled.matcher(value).matches()) {
                        events.add(
                            ValidationEvent.builder()
                                .id("ValidationExceptionMemberDefault.PatternConstraintViolation")
                                .severity(Severity.ERROR)
                                .shape(this)
                                .message(
                                    "$member has @validationExceptionMemberDefault value \"$value\" " +
                                        "that does not match the @pattern constraint \"$patternString\"",
                                ).build(),
                        )
                    }
                } catch (e: PatternSyntaxException) {
                    events.add(
                        ValidationEvent.builder()
                            .id("ValidationExceptionMemberDefault.UnparseablePattern")
                            .severity(Severity.WARNING)
                            .shape(this)
                            .message(
                                "$member has a @pattern trait with regex \"$patternString\" that could not be " +
                                    "compiled (${e.message}); the default value \"$value\" could not be validated " +
                                    "against this pattern",
                            ).build(),
                    )
                }
            }
        }

        // Check @range constraint for number targets.
        if (target is NumberShape) {
            target.getTrait(RangeTrait::class.java).orNull()?.let { rangeTrait ->
                val parsed =
                    try {
                        BigDecimal(value)
                    } catch (e: NumberFormatException) {
                        events.add(
                            ValidationEvent.builder()
                                .id("ValidationExceptionMemberDefault.InvalidNumberValue")
                                .severity(Severity.ERROR)
                                .shape(this)
                                .message(
                                    "$member has @validationExceptionMemberDefault value \"$value\" " +
                                        "that is not a valid number for target ${target.id}",
                                ).build(),
                        )
                        return
                    }

                val min = rangeTrait.min.orNull()
                val max = rangeTrait.max.orNull()
                val violated = (min != null && parsed.compareTo(min) < 0) || (max != null && parsed.compareTo(max) > 0)
                if (violated) {
                    val constraint =
                        when {
                            min != null && max != null -> "between $min and $max"
                            min != null -> "at least $min"
                            else -> "at most $max"
                        }
                    events.add(
                        ValidationEvent.builder()
                            .id("ValidationExceptionMemberDefault.RangeConstraintViolation")
                            .severity(Severity.ERROR)
                            .shape(this)
                            .message(
                                "$member has @validationExceptionMemberDefault value \"$value\" " +
                                    "that violates the @range constraint ($constraint)",
                            ).build(),
                    )
                }
            }
        }
    }
}
