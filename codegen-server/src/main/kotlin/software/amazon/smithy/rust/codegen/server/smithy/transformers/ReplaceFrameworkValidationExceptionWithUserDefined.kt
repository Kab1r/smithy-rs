/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.framework.rust.ValidationExceptionTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.customizations.SmithyValidationExceptionConversionGenerator

/**
 * When the model designates a custom validation exception via `@validationException`, the
 * framework's `smithy.framework#ValidationException` must not also reach codegen. If an upstream
 * model definition listed both — for example by importing `smithy.framework#ValidationException`
 * into an operation's errors before the custom exception was layered on — two `pub struct
 * ValidationException` definitions would be emitted into `crate::error`, and any operation error
 * enum would contain colliding variants.
 *
 * This transformer rewires every operation and service error reference from
 * `smithy.framework#ValidationException` to the user-defined exception, then removes the framework
 * shape from the model so that only the user-defined exception participates in codegen.
 */
object ReplaceFrameworkValidationExceptionWithUserDefined {
    fun transform(model: Model): Model {
        val userValidationException =
            model.shapes(StructureShape::class.java)
                .filter { it.hasTrait(ValidationExceptionTrait.ID) }
                .findFirst()
                .orNull() ?: return model

        val frameworkId = SmithyValidationExceptionConversionGenerator.SHAPE_ID
        val frameworkShape = model.getShape(frameworkId).orNull() ?: return model
        val userId = userValidationException.toShapeId()
        if (userId == frameworkId) {
            return model
        }

        val transformer = ModelTransformer.create()
        val rewired =
            transformer.mapShapes(model) { shape ->
                when {
                    shape is OperationShape && shape.errors.contains(frameworkId) ->
                        shape.toBuilder().replaceError(frameworkId, userId).build()
                    shape is ServiceShape && shape.errors.contains(frameworkId) ->
                        shape.toBuilder().replaceError(frameworkId, userId).build()
                    else -> shape
                }
            }
        return transformer.removeShapes(rewired, listOf(frameworkShape))
    }
}

private fun OperationShape.Builder.replaceError(
    from: ShapeId,
    to: ShapeId,
): OperationShape.Builder {
    val current = this.build().errors
    clearErrors()
    val seen = mutableSetOf<ShapeId>()
    for (id in current) {
        val replacement = if (id == from) to else id
        if (seen.add(replacement)) {
            addError(replacement)
        }
    }
    return this
}

private fun ServiceShape.Builder.replaceError(
    from: ShapeId,
    to: ShapeId,
): ServiceShape.Builder {
    val current = this.build().errors
    clearErrors()
    val seen = mutableSetOf<ShapeId>()
    for (id in current) {
        val replacement = if (id == from) to else id
        if (seen.add(replacement)) {
            addError(replacement)
        }
    }
    return this
}
