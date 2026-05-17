/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.framework.rust.ValidationExceptionTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.EnumShape
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.SetShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.smithy.DirectedWalker
import software.amazon.smithy.rust.codegen.core.util.hasEventStreamMember
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.server.smithy.ServerRustSettings
import software.amazon.smithy.rust.codegen.server.smithy.customizations.SmithyValidationExceptionConversionGenerator
import software.amazon.smithy.rust.codegen.server.smithy.hasConstraintTrait
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("AttachValidationExceptionToConstrainedOperationInputs")

/**
 * Returns the custom validation exception defined in the model, if one exists.
 * A custom validation exception is a structure with the `@validationException` trait.
 */
private fun customValidationExceptionShapeId(model: Model): ShapeId? =
    model.shapes(StructureShape::class.java)
        .filter { it.hasTrait(ValidationExceptionTrait.ID) }
        .findFirst()
        .map { it.toShapeId() }
        .orElse(null)

private fun validationExceptionShapeIdToAttach(
    model: Model,
    settings: ServerRustSettings,
): ShapeId =
    settings.codegenConfig.experimentalCustomValidationExceptionWithReasonPleaseDoNotUse
        ?.let(ShapeId::from)
        ?: customValidationExceptionShapeId(model)
        ?: SmithyValidationExceptionConversionGenerator.SHAPE_ID

/**
 * Ensures the `smithy.framework#ValidationException` shape and its dependencies exist in the model.
 * The shape is normally provided by the `smithy-validation-model` dependency, but it may not be on
 * the classpath for all consumers, or may be removed by Smithy build projection transforms. If the
 * shape is missing, we add it programmatically so that adding a ShapeId reference to it doesn't
 * result in a dangling reference.
 */
private fun ensureValidationExceptionShapeExists(model: Model): Model {
    val shapeId = SmithyValidationExceptionConversionGenerator.SHAPE_ID
    if (model.getShape(shapeId).isPresent) {
        return model
    }

    val ns = shapeId.namespace
    val fieldShape =
        StructureShape.builder().id("$ns#ValidationExceptionField")
            .addMember(
                MemberShape.builder().id("$ns#ValidationExceptionField\$path")
                    .target("smithy.api#String").addTrait(RequiredTrait()).build(),
            )
            .addMember(
                MemberShape.builder().id("$ns#ValidationExceptionField\$message")
                    .target("smithy.api#String").addTrait(RequiredTrait()).build(),
            ).build()

    val fieldListShape =
        ListShape.builder().id("$ns#ValidationExceptionFieldList")
            .member(ShapeId.from("$ns#ValidationExceptionField"))
            .build()

    val validationExceptionShape =
        StructureShape.builder().id(shapeId)
            .addTrait(ErrorTrait("client"))
            .addMember(
                MemberShape.builder().id("$ns#ValidationException\$message")
                    .target("smithy.api#String").addTrait(RequiredTrait()).build(),
            )
            .addMember(
                MemberShape.builder().id("$ns#ValidationException\$fieldList")
                    .target("$ns#ValidationExceptionFieldList").build(),
            ).build()

    return model.toBuilder()
        .addShapes(listOf(fieldShape, fieldListShape, validationExceptionShape))
        .build()
}

private fun addValidationExceptionToMatchingServiceShapes(
    model: Model,
    validationExceptionShapeId: ShapeId,
    filterPredicate: (ServiceShape) -> Boolean,
): Model {
    val walker = DirectedWalker(model)
    val operationsWithConstrainedInputWithoutValidationException =
        model.serviceShapes
            .filter(filterPredicate)
            .flatMap { service ->
                // Walk the entire service closure to find all operations, including those on resources.
                walker.walkShapes(service).filterIsInstance<OperationShape>()
            }
            .filter { operationShape ->
                walker.walkShapes(operationShape.inputShape(model))
                    .any { it is SetShape || it is EnumShape || it.hasConstraintTrait() || it.hasEventStreamMember(model) }
            }
            .filter { !it.errors.contains(validationExceptionShapeId) }

    if (operationsWithConstrainedInputWithoutValidationException.isEmpty()) {
        return model
    }

    val modelWithShape =
        if (validationExceptionShapeId == SmithyValidationExceptionConversionGenerator.SHAPE_ID) {
            // Ensure the smithy.framework#ValidationException shape and its dependencies exist in the model
            // before adding references to it. The shape may be absent if it was not on the classpath or was
            // removed by Smithy build projection transforms.
            ensureValidationExceptionShapeExists(model)
        } else {
            model
        }

    return ModelTransformer.create().mapShapes(modelWithShape) { shape ->
        if (shape is OperationShape && operationsWithConstrainedInputWithoutValidationException.contains(shape)) {
            shape.toBuilder().addError(validationExceptionShapeId).build()
        } else {
            shape
        }
    }
}

/**
 * Attach the `smithy.framework#ValidationException` error to operations whose inputs are constrained, if they belong
 * to a service in an allowlist.
 *
 * Some of the models we generate in CI have constrained operation inputs, but the operations don't have
 * `smithy.framework#ValidationException` in their list of errors. This is a codegen error, unless
 * `disableDefaultValidation` is set to `true`, a code generation mode we don't support yet. See [1] for more details.
 * Until we implement said mode, we manually attach the error to build these models, since we don't own them (they're
 * either actual AWS service model excerpts, or they come from the `awslabs/smithy` library.
 *
 * [1]: https://github.com/smithy-lang/smithy-rs/pull/1199#discussion_r809424783
 *
 * TODO(https://github.com/smithy-lang/smithy-rs/issues/1401): This transformer will go away once we add support for
 *  `disableDefaultValidation` set to `true`, allowing service owners to map from constraint violations to operation errors.
 */
object AttachValidationExceptionToConstrainedOperationInputsInAllowList {
    private val serviceShapeIdAllowList =
        setOf(
            // These we currently generate server SDKs for.
            ShapeId.from("aws.protocoltests.restjson#RestJson"),
            ShapeId.from("aws.protocoltests.json10#JsonRpc10"),
            ShapeId.from("aws.protocoltests.json#JsonProtocol"),
            ShapeId.from("com.amazonaws.s3#AmazonS3"),
            ShapeId.from("com.amazonaws.ebs#Ebs"),
            // These are only loaded in the classpath and need this model transformer, but we don't generate server
            // SDKs for them. Here they are for reference.
            // ShapeId.from("aws.protocoltests.restxml#RestXml"),
            // ShapeId.from("com.amazonaws.glacier#Glacier"),
            // ShapeId.from("aws.protocoltests.ec2#AwsEc2"),
            // ShapeId.from("aws.protocoltests.query#AwsQuery"),
            // ShapeId.from("com.amazonaws.machinelearning#AmazonML_20141212"),
        )

    fun transform(
        model: Model,
        validationExceptionShapeId: ShapeId,
    ): Model {
        return addValidationExceptionToMatchingServiceShapes(
            model,
            validationExceptionShapeId,
        ) { serviceShapeIdAllowList.contains(it.toShapeId()) }
    }
}

/**
 * Attach the active validation exception error to operations with constrained inputs.
 *
 * This transformer automatically adds the active ValidationException to operations that have
 * constrained inputs but don't have a validation exception attached. If the model defines a
 * custom validation exception (a structure with the @validationException trait), that exception
 * is attached. Otherwise, the default smithy.framework#ValidationException is attached.
 *
 * The `addValidationExceptionToConstrainedOperations` codegen flag is deprecated. The transformer
 * now automatically determines which validation exception to add based on whether a custom
 * validation exception exists in the model or settings.
 */
object AttachValidationExceptionToConstrainedOperationInput {
    fun transform(
        model: Model,
        settings: ServerRustSettings,
        validationExceptionShapeId: ShapeId,
    ): Model {
        // Log deprecation warning if the flag is explicitly set
        val addExceptionNullableFlag = settings.codegenConfig.addValidationExceptionToConstrainedOperations
        if (addExceptionNullableFlag == true) {
            logger.warning(
                "The 'addValidationExceptionToConstrainedOperations' codegen flag is deprecated. " +
                    "The active validation exception is now automatically added to operations with constrained inputs.",
            )
        } else if (addExceptionNullableFlag == false) {
            // Skip adding validation exceptions when `addValidationExceptionToConstrainedOperations`
            // is explicitly false (backward compatibility).
            return model
        }

        val service = settings.getService(model)

        return addValidationExceptionToMatchingServiceShapes(
            model,
            validationExceptionShapeId,
        ) { it == service }
    }
}

/**
 * Attaches the active validation exception error to operations with constrained inputs
 * if either of the following conditions is met:
 * 1. The operation belongs to a service in the allowlist.
 * 2. The codegen flag `addValidationExceptionToConstrainedOperations` has been set.
 *
 * The error is only attached if the operation does not already have the active validation exception added.
 */
object AttachValidationExceptionToConstrainedOperationInputs {
    fun transform(
        model: Model,
        settings: ServerRustSettings,
    ): Model {
        val validationExceptionShapeId = validationExceptionShapeIdToAttach(model, settings)
        val allowListTransformedModel =
            AttachValidationExceptionToConstrainedOperationInputsInAllowList.transform(model, validationExceptionShapeId)
        return AttachValidationExceptionToConstrainedOperationInput.transform(
            allowListTransformedModel,
            settings,
            validationExceptionShapeId,
        )
    }
}
