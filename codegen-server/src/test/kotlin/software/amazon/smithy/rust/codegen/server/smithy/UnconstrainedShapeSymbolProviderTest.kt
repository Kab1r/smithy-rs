/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.util.lookup
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestSymbolProvider
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestSymbolProviders
import software.amazon.smithy.rust.codegen.server.smithy.transformers.ConstrainedMemberTransform

/**
 * While [UnconstrainedShapeSymbolProvider] _must_ be in the `codegen` subproject, these tests need to be in the
 * `codegen-server` subproject, because they use [serverTestSymbolProvider].
 */
class UnconstrainedShapeSymbolProviderTest {
    private val baseModelString =
        """
        namespace test

        service TestService {
            version: "123",
            operations: [TestOperation]
        }

        operation TestOperation {
            input: TestInputOutput,
            output: TestInputOutput,
        }

        structure TestInputOutput {
            list: ListA
        }
        """

    @Test
    fun `it should adjust types for unconstrained shapes`() {
        val model =
            """
            $baseModelString

            list ListA {
                member: ListB
            }

            list ListB {
                member: StructureC
            }

            structure StructureC {
                @required
                string: String
            }
            """.asSmithyModel()

        val unconstrainedShapeSymbolProvider = serverTestSymbolProviders(model).unconstrainedShapeSymbolProvider

        val listAShape = model.lookup<ListShape>("test#ListA")
        val listAType = unconstrainedShapeSymbolProvider.toSymbol(listAShape).rustType()

        val listBShape = model.lookup<ListShape>("test#ListB")
        val listBType = unconstrainedShapeSymbolProvider.toSymbol(listBShape).rustType()

        val structureCShape = model.lookup<StructureShape>("test#StructureC")
        val structureCType = unconstrainedShapeSymbolProvider.toSymbol(structureCShape).rustType()

        listAType shouldBe RustType.Opaque("ListAUnconstrained", "crate::unconstrained::list_a_unconstrained")
        listBType shouldBe RustType.Opaque("ListBUnconstrained", "crate::unconstrained::list_b_unconstrained")
        structureCType shouldBe RustType.Opaque("Builder", "crate::model::structure_c")
    }

    @Test
    fun `it should terminate on a constrained collection member that targets another collection in a non-structure container`() {
        // Reproduces an infinite recursion in `getParentAndInlineModuleForConstrainedMember`:
        // for a synthetic shape with a non-structure container and `publicConstrainedTypes=true`,
        // it used to call back into the symbol provider on the same shape. When the caller is
        // `UnconstrainedShapeSymbolProvider` (which passes `this`), the call returns through
        // `unconstrainedSymbolForCollectionOrMapOrUnionShape` → `getParentAndInlineModuleForConstrainedMember`
        // → `toSymbol` … without any base case.
        //
        // The minimal trigger is a non-structure container whose member carries a collection
        // constraint trait (e.g. `@length` on a member targeting a list/map), because the
        // constrained-member transform then extracts a synthetic *collection/map* shape, which is
        // the shape kind that flows through `unconstrainedSymbolForCollectionOrMapOrUnionShape`.
        val model =
            ConstrainedMemberTransform.transform(
                OperationNormalizer.transform(
                    """
                    namespace test

                    service TestService {
                        version: "123",
                        operations: [TestOperation]
                    }

                    operation TestOperation {
                        input: TestInputOutput,
                        output: TestInputOutput,
                    }

                    structure TestInputOutput {
                        outer: OuterList
                    }

                    list OuterList {
                        @length(max: 5)
                        member: InnerList
                    }

                    list InnerList {
                        member: String
                    }
                    """.asSmithyModel(),
                ),
            )

        val unconstrainedShapeSymbolProvider = serverTestSymbolProviders(model).unconstrainedShapeSymbolProvider

        // The synthetic shape extracted by `ConstrainedMemberTransform` for the constrained member.
        val syntheticOuterListMember = model.lookup<ListShape>("test#OuterListMember")
        val rendered =
            unconstrainedShapeSymbolProvider.toSymbol(syntheticOuterListMember)
                .rustType().render()

        // The exact module path is an implementation detail; what matters is that the lookup
        // terminates (this previously stack-overflowed) and lands somewhere under the outer-list
        // namespace, which is where `ConstrainedShapeSymbolProvider` places the constrained twin.
        rendered shouldContain "OuterListMemberUnconstrained"
    }

    @Test
    fun `it should delegate to the base symbol provider if called with a shape that cannot reach a constrained shape`() {
        val model =
            """
            $baseModelString

            list ListA {
                member: StructureB
            }

            structure StructureB {
                string: String
            }
            """.asSmithyModel()

        val unconstrainedShapeSymbolProvider = serverTestSymbolProviders(model).unconstrainedShapeSymbolProvider

        val listAShape = model.lookup<ListShape>("test#ListA")
        val structureBShape = model.lookup<StructureShape>("test#StructureB")

        unconstrainedShapeSymbolProvider.toSymbol(structureBShape).rustType().render() shouldBe "crate::model::StructureB"
        unconstrainedShapeSymbolProvider.toSymbol(listAShape).rustType().render() shouldBe "::std::vec::Vec::<crate::model::StructureB>"
    }
}
