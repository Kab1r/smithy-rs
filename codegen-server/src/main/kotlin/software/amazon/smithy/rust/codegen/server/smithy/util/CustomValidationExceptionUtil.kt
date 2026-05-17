/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.util

import software.amazon.smithy.framework.rust.ValidationFieldNameTrait
import software.amazon.smithy.framework.rust.ValidationMessageTrait
import software.amazon.smithy.model.shapes.MemberShape

/**
 * Helper function to determine if this [MemberShape] is a validation message either explicitly with the
 * @validationMessage trait or implicitly because it is named "message".
 *
 * The name match is case-insensitive: AWS service models for XML-based protocols (awsQuery, restXml)
 * commonly declare the field as PascalCase `Message` to match the wire format. A case-sensitive match
 * aborts those services at smithy-build with `MissingMessageField`.
 */
fun MemberShape.isValidationMessage(): Boolean {
    return this.hasTrait(ValidationMessageTrait.ID) || this.memberName.equals("message", ignoreCase = true)
}

fun MemberShape.isValidationFieldName(): Boolean {
    return this.hasTrait(ValidationFieldNameTrait.ID) || this.memberName.equals("name", ignoreCase = true)
}
