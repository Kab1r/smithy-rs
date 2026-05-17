/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.validation.ValidatedResultException
import java.io.File

class ServiceLoaderTest {
    @Test
    fun `reports regex stack overflow as validation event`() {
        val file = File("deadline.json")

        val exception =
            assertThrows(ValidatedResultException::class.java) {
                assembleServiceDiscoveryModel(file) {
                    throw StackOverflowError("regex recursion")
                }
            }

        val event = exception.validationEvents.single()
        assertEquals("PatternTrait.RegexStackOverflow", event.id)
        assertEquals(file.absolutePath, event.sourceLocation.filename)
        assertTrue(event.message.contains("@pattern traits"))
    }
}
