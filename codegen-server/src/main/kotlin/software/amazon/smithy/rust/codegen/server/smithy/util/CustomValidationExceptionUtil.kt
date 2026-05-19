/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.util

import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.framework.rust.ValidationFieldListTrait
import software.amazon.smithy.framework.rust.ValidationFieldNameTrait
import software.amazon.smithy.framework.rust.ValidationMessageTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.util.orNull

/**
 * Helper function to determine if this [MemberShape] is a validation message either explicitly with the
 * @validationMessage trait or implicitly because it is named "message" (exact lowercase).
 *
 * The name match is exact lowercase only: the case-insensitive fallback was removed because it
 * silently collides when a model has both `message` and `Message` members, and silently loses the
 * binding when a user renames `Message` to `ErrorMessage`. Models that use PascalCase or any other
 * casing must apply the explicit `@validationMessage` trait.
 *
 * [CustomValidationExceptionValidator] emits a WARNING when a model relies on this lowercase fallback
 * rather than the explicit trait.
 */
fun MemberShape.isValidationMessage(): Boolean {
    if (this.hasTrait(ValidationMessageTrait.ID)) return true
    // Backward-compat: members literally named `message` (lowercase) are recognised without the
    // trait. Any other casing or name requires the explicit @validationMessage trait;
    // CustomValidationExceptionValidator emits a WARNING when a model relies on this fallback.
    return this.memberName == "message"
}

/**
 * Helper function to determine if this [MemberShape] is a validation field-name member either explicitly with the
 * @validationFieldName trait or implicitly because it is named "name" or "path" (exact lowercase).
 *
 * Both names are recognised: AWS service models conventionally call it `name`, while
 * `smithy.framework#ValidationExceptionField` calls it `path`. The match is exact lowercase only:
 * the case-insensitive fallback was removed to avoid silent collisions and silent loss of binding.
 * Models using PascalCase or any other casing must apply the explicit `@validationFieldName` trait.
 *
 * [CustomValidationExceptionValidator] emits a WARNING when a model relies on this lowercase fallback
 * rather than the explicit trait.
 */
fun MemberShape.isValidationFieldName(): Boolean {
    if (this.hasTrait(ValidationFieldNameTrait.ID)) return true
    // Backward-compat: members literally named `name` or `path` (lowercase) are recognised
    // without the trait. Any other casing or name requires @validationFieldName; the validator
    // emits a WARNING when a model relies on this fallback.
    return this.memberName == "name" || this.memberName == "path"
}

/**
 * Rust-side identifiers for the validation-field structure's two canonical members.
 *
 * The framework's [smithy.framework#ValidationExceptionField] uses `path` and `message`. AWS service
 * models that declare their own field structure (for example Shield) often follow a different
 * convention — `name` for the field-name member, sometimes a PascalCase `Message`. The synthetic
 * code that builds a validation field at runtime needs to use whichever names the modeled
 * structure actually declares, otherwise the generated struct literal references members that do
 * not exist.
 */
data class ValidationExceptionFieldIdentifiers(
    val nameMember: String,
    val messageMember: String,
) {
    companion object {
        val DEFAULT = ValidationExceptionFieldIdentifiers(nameMember = "path", messageMember = "message")
    }
}

/**
 * Resolve the Rust identifiers for the validation field structure reachable through the framework
 * `smithy.framework#ValidationException`'s `fieldList` member. After the
 * `ReplaceFrameworkValidationExceptionWithUserDefined` baseline transform has run that target may
 * be the user-modeled field shape (the framework field is removed when a user counterpart is
 * present), in which case the user's member names are returned; otherwise the canonical framework
 * names (`path` / `message`) are returned.
 */
fun resolveValidationExceptionFieldIdentifiers(
    model: Model,
    symbolProvider: SymbolProvider,
): ValidationExceptionFieldIdentifiers {
    val frameworkExceptionId = ShapeId.from("smithy.framework#ValidationException")
    val exception =
        model.getShape(frameworkExceptionId).orNull()
            as? StructureShape
            ?: return ValidationExceptionFieldIdentifiers.DEFAULT
    // Prefer @validationFieldList; fall back to the canonical names for backward compatibility
    // with models that haven't been annotated. CustomValidationExceptionValidator emits a WARNING
    // when the fallback path is hit so users are nudged toward the trait.
    val fieldListMember =
        exception.members().firstOrNull { it.hasTrait(ValidationFieldListTrait.ID) }
            ?: exception.members().firstOrNull { it.memberName == "fieldList" || it.memberName == "field_list" }
            ?: return ValidationExceptionFieldIdentifiers.DEFAULT
    val fieldList =
        model.getShape(fieldListMember.target).orNull() as? ListShape
            ?: return ValidationExceptionFieldIdentifiers.DEFAULT
    val fieldShape =
        model.getShape(fieldList.member.target).orNull() as? StructureShape
            ?: return ValidationExceptionFieldIdentifiers.DEFAULT
    return ValidationExceptionFieldIdentifiers(
        nameMember =
            fieldShape.members().firstOrNull { it.isValidationFieldName() }
                ?.let { symbolProvider.toMemberName(it) }
                ?: "path",
        messageMember =
            fieldShape.members().firstOrNull { it.isValidationMessage() }
                ?.let { symbolProvider.toMemberName(it) }
                ?: "message",
    )
}
