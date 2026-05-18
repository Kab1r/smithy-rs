/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.framework.rust.ValidationExceptionTrait
import software.amazon.smithy.framework.rust.ValidationFieldListTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.customizations.SmithyValidationExceptionConversionGenerator

/**
 * When a model declares its own equivalents of any of
 * `smithy.framework#ValidationException`,
 * `smithy.framework#ValidationExceptionFieldList`, or
 * `smithy.framework#ValidationExceptionField`,
 * the framework counterparts must not also reach codegen. The framework and the user-modeled shapes share
 * their local Smithy names, so both would land at the same Rust path — duplicate `pub struct` definitions,
 * duplicate `Debug` / `Clone` impls, duplicate `Builder` items, and a colliding `ConstraintViolation` enum.
 *
 * Each shape is replaced independently: the exception, the field-list, and the field can each be modeled on
 * their own and a service is free to bring just some of them (Shield, for example, models only
 * `ValidationExceptionField` and `ValidationExceptionFieldList`). For every framework shape whose user-defined
 * counterpart we can locate, this transformer rewires every operation/service error, structure member, list
 * member, map key/value, and union member reference to it, then removes the framework shape from the model so
 * only the user-defined one participates in codegen.
 */
object ReplaceFrameworkValidationExceptionWithUserDefined {
    private val frameworkExceptionId = SmithyValidationExceptionConversionGenerator.SHAPE_ID
    private val frameworkFieldListId = ShapeId.from("smithy.framework#ValidationExceptionFieldList")
    private val frameworkFieldId = ShapeId.from("smithy.framework#ValidationExceptionField")

    fun transform(model: Model): Model {
        val userException = userValidationException(model)
        val userFieldList = userValidationFieldList(model, userException)
        val userField = userValidationField(model, userFieldList)

        val redirects =
            buildMap {
                userException?.toShapeId()?.takeIf { it != frameworkExceptionId }?.let {
                    put(frameworkExceptionId, it)
                }
                userFieldList?.takeIf { it != frameworkFieldListId }?.let { put(frameworkFieldListId, it) }
                userField?.takeIf { it != frameworkFieldId }?.let { put(frameworkFieldId, it) }
            }
        if (redirects.isEmpty()) return model

        val transformer = ModelTransformer.create()
        val rewired =
            transformer.mapShapes(model) { shape ->
                when (shape) {
                    is OperationShape -> shape.rewireErrors(redirects)
                    is ServiceShape -> shape.rewireErrors(redirects)
                    is StructureShape -> shape.rewireMembers(redirects)
                    is ListShape -> shape.rewireMember(redirects)
                    is MapShape -> shape.rewireKeyAndValue(redirects)
                    is UnionShape -> shape.rewireMembers(redirects)
                    else -> shape
                }
            }

        val shapesToRemove =
            redirects.keys.mapNotNull { rewired.getShape(it).orNull() }
        return transformer.removeShapes(rewired, shapesToRemove)
    }

    private fun userValidationException(model: Model): StructureShape? =
        model.shapes(StructureShape::class.java)
            .filter { it.hasTrait(ValidationExceptionTrait.ID) }
            .findFirst()
            .orNull()

    /**
     * Returns the [ShapeId] of the list shape that holds the validation field structures, preferring the
     * member explicitly tagged on the user-defined `ValidationException` via `@validationFieldList` and
     * falling back to any list shape whose local Smithy name is `ValidationExceptionFieldList`. The latter
     * fallback is what lets services that declare only the field/list shapes (Shield) still benefit.
     */
    private fun userValidationFieldList(
        model: Model,
        userException: StructureShape?,
    ): ShapeId? {
        val viaTrait =
            userException?.members()
                ?.firstOrNull { it.hasTrait(ValidationFieldListTrait.ID) }
                ?.target
        if (viaTrait != null) return viaTrait
        return model.shapes(ListShape::class.java)
            .filter { it.id.name == "ValidationExceptionFieldList" && it.id != frameworkFieldListId }
            .findFirst()
            .orNull()
            ?.toShapeId()
    }

    /**
     * Returns the [ShapeId] of the validation field structure, preferring the member type of the
     * field-list shape resolved above and falling back to any structure shape whose local Smithy name is
     * `ValidationExceptionField`.
     */
    private fun userValidationField(
        model: Model,
        userFieldList: ShapeId?,
    ): ShapeId? {
        val viaList =
            userFieldList?.let { model.getShape(it).orNull() }
                ?.let { it as? ListShape }
                ?.member?.target
        if (viaList != null && viaList != frameworkFieldId) return viaList
        return model.shapes(StructureShape::class.java)
            .filter { it.id.name == "ValidationExceptionField" && it.id != frameworkFieldId }
            .findFirst()
            .orNull()
            ?.toShapeId()
    }
}

private fun OperationShape.rewireErrors(redirects: Map<ShapeId, ShapeId>): OperationShape =
    if (errors.any { it in redirects.keys }) {
        val builder = toBuilder().clearErrors()
        val seen = mutableSetOf<ShapeId>()
        for (id in errors) {
            val replacement = redirects[id] ?: id
            if (seen.add(replacement)) {
                builder.addError(replacement)
            }
        }
        builder.build()
    } else {
        this
    }

private fun ServiceShape.rewireErrors(redirects: Map<ShapeId, ShapeId>): ServiceShape =
    if (errors.any { it in redirects.keys }) {
        val builder = toBuilder().clearErrors()
        val seen = mutableSetOf<ShapeId>()
        for (id in errors) {
            val replacement = redirects[id] ?: id
            if (seen.add(replacement)) {
                builder.addError(replacement)
            }
        }
        builder.build()
    } else {
        this
    }

private fun StructureShape.rewireMembers(redirects: Map<ShapeId, ShapeId>): StructureShape {
    val updated = members().map { it.rewire(redirects) }
    if (updated.zip(members()).all { (a, b) -> a === b }) return this
    val builder = toBuilder().clearMembers()
    updated.forEach { builder.addMember(it) }
    return builder.build()
}

private fun UnionShape.rewireMembers(redirects: Map<ShapeId, ShapeId>): UnionShape {
    val updated = members().map { it.rewire(redirects) }
    if (updated.zip(members()).all { (a, b) -> a === b }) return this
    val builder = toBuilder().clearMembers()
    updated.forEach { builder.addMember(it) }
    return builder.build()
}

private fun ListShape.rewireMember(redirects: Map<ShapeId, ShapeId>): ListShape {
    val updated = member.rewire(redirects)
    if (updated === member) return this
    return toBuilder().member(updated).build()
}

private fun MapShape.rewireKeyAndValue(redirects: Map<ShapeId, ShapeId>): MapShape {
    val newKey = key.rewire(redirects)
    val newValue = value.rewire(redirects)
    if (newKey === key && newValue === value) return this
    return toBuilder().key(newKey).value(newValue).build()
}

private fun MemberShape.rewire(redirects: Map<ShapeId, ShapeId>): MemberShape {
    val replacement = redirects[target] ?: return this
    return toBuilder().target(replacement).build()
}
