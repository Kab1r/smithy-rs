/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.framework.rust

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ShapeId

class ValidationModelTest {
    @Test
    fun `discovers default validation exception model`() {
        val model = Model.assembler().discoverModels().assemble().unwrap()

        assertTrue(model.getShape(ShapeId.from("smithy.framework#ValidationException")).isPresent)
        assertTrue(model.getShape(ShapeId.from("smithy.framework#ValidationExceptionField")).isPresent)
        assertTrue(model.getShape(ShapeId.from("smithy.framework#ValidationExceptionFieldList")).isPresent)
    }
}
