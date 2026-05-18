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
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.customizations.SmithyValidationExceptionConversionGenerator

/**
 * When the model designates a custom validation exception via `@validationException`, the framework's
 * `smithy.framework#ValidationException` and its companion shapes
 * (`smithy.framework#ValidationExceptionFieldList`, `smithy.framework#ValidationExceptionField`) must not also
 * reach codegen. If an upstream model definition listed any of them — for example by importing
 * `smithy.framework#ValidationException` into an operation's errors before the custom exception was layered on
 * — duplicate top-level Rust definitions land in the generated crate because the framework shapes and the
 * user-defined shapes share their local Smithy names.
 *
 * This transformer rewires every reference to the framework shapes — operation/service errors, structure
 * members, list members, map keys/values, and union members — so they point at the user-defined equivalents
 * where one is known, and removes the framework shapes from the model so that only the user-defined shapes
 * participate in codegen. When a framework helper shape has no user equivalent it is left in the model and
 * relied on to be unreachable from the service closure.
 */
object ReplaceFrameworkValidationExceptionWithUserDefined {
    fun transform(model: Model): Model {
        val userValidationException =
            model.shapes(StructureShape::class.java)
                .filter { it.hasTrait(ValidationExceptionTrait.ID) }
                .findFirst()
                .orNull() ?: return model

        val frameworkExceptionId = SmithyValidationExceptionConversionGenerator.SHAPE_ID
        val frameworkFieldListId = ShapeId.from("smithy.framework#ValidationExceptionFieldList")
        val frameworkFieldId = ShapeId.from("smithy.framework#ValidationExceptionField")
        val frameworkExceptionShape = model.getShape(frameworkExceptionId).orNull() ?: return model
        if (userValidationException.toShapeId() == frameworkExceptionId) {
            return model
        }

        // Look up the user-defined list-of-fields and field shapes via `@validationFieldList`, so that
        // references to the framework counterparts can be rewired to them. Either may be absent: a custom
        // validation exception is allowed to omit the field list entirely.
        val userFieldListId =
            userValidationException.members()
                .firstOrNull { it.hasTrait(ValidationFieldListTrait.ID) }
                ?.target
        val userFieldId =
            userFieldListId?.let { model.getShape(it).orNull() as? ListShape }
                ?.member?.target

        val redirects =
            buildMap {
                put(frameworkExceptionId, userValidationException.toShapeId())
                userFieldListId?.let { put(frameworkFieldListId, it) }
                userFieldId?.let { put(frameworkFieldId, it) }
            }

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
            buildList {
                add(frameworkExceptionShape)
                userFieldListId?.let { rewired.getShape(frameworkFieldListId).orNull()?.let(::add) }
                userFieldId?.let { rewired.getShape(frameworkFieldId).orNull()?.let(::add) }
            }
        return transformer.removeShapes(rewired, shapesToRemove)
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
