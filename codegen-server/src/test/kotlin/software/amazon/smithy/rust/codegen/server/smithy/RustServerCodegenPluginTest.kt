/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import software.amazon.smithy.codegen.core.CodegenException

class RustServerCodegenPluginTest {
    @Test
    fun `guardAgainstRegexStackOverflow converts StackOverflowError into a CodegenException tagged with the projection name`() {
        val exception =
            shouldThrow<CodegenException> {
                guardAgainstRegexStackOverflow<Unit>("my-projection") {
                    throw StackOverflowError("synthetic")
                }
            }

        exception.message shouldContain "my-projection"
        // The original StackOverflowError is preserved as the cause so operators can still see the
        // full JVM trace if they need it.
        (exception.cause is StackOverflowError) shouldBe true
    }

    @Test
    fun `guardAgainstRegexStackOverflow returns the block's value when no overflow occurs`() {
        val result =
            guardAgainstRegexStackOverflow("my-projection") {
                42
            }
        result shouldBe 42
    }

    @Test
    fun `guardAgainstRegexStackOverflow does not swallow other exceptions`() {
        shouldThrow<IllegalStateException> {
            guardAgainstRegexStackOverflow<Unit>("my-projection") {
                throw IllegalStateException("propagate me")
            }
        }
    }
}
