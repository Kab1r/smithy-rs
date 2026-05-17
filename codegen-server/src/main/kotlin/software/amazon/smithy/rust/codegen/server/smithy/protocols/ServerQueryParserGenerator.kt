/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.protocols

import software.amazon.smithy.aws.traits.protocols.Ec2QueryNameTrait
import software.amazon.smithy.model.shapes.BigDecimalShape
import software.amazon.smithy.model.shapes.BigIntegerShape
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.EnumShape
import software.amazon.smithy.model.shapes.IntEnumShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.model.traits.XmlFlattenedTrait
import software.amazon.smithy.model.traits.XmlNameTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.generators.setterName
import software.amazon.smithy.rust.codegen.core.smithy.isOptional
import software.amazon.smithy.rust.codegen.core.smithy.isRustBoxed
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolFunctions
import software.amazon.smithy.rust.codegen.core.smithy.protocols.parse.StructuredDataParserGenerator
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.isTargetUnit
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.returnSymbolToParseFn
import software.amazon.smithy.rust.codegen.server.smithy.generators.serverBuilderSymbol
import software.amazon.smithy.utils.StringUtils

abstract class ServerQueryParserGenerator(
    private val codegenContext: ServerCodegenContext,
    private val protocol: ServerProtocol,
) : StructuredDataParserGenerator {
    protected val model = codegenContext.model
    protected val symbolProvider = codegenContext.symbolProvider
    private val runtimeConfig = codegenContext.runtimeConfig
    private val protocolFunctions = ProtocolFunctions(codegenContext)
    private val builderInstantiator = codegenContext.builderInstantiator()
    private val returnSymbolToParse = returnSymbolToParseFn(codegenContext)
    private val requestRejection = protocol.requestRejection(runtimeConfig)
    private val smithyTypes = RuntimeType.smithyTypes(runtimeConfig)

    private val codegenScope =
        arrayOf(
            "Blob" to RuntimeType.blob(runtimeConfig),
            "FormUrlEncoded" to ServerCargoDependency.FormUrlEncoded.toType(),
            "RequestRejection" to requestRejection,
            "SmithyTypes" to smithyTypes,
            *RuntimeType.preludeScope,
        )

    abstract val protocolName: String

    abstract fun MemberShape.queryKeyName(prioritizedFallback: String? = null): String

    abstract fun MemberShape.isFlattened(): Boolean

    protected open fun CollectionShape.queryListMemberName(): String =
        member.getTrait<XmlNameTrait>()?.value ?: "member"

    protected open fun emptyListMarkerIsSerialized(member: MemberShape): Boolean = !member.isFlattened()

    override fun payloadParser(member: MemberShape): RuntimeType {
        TODO("$protocolName does not support server payload-bound query parsing")
    }

    override fun operationParser(operationShape: OperationShape): RuntimeType? = null

    override fun errorParser(errorShape: StructureShape): RuntimeType? = null

    override fun serverInputParser(operationShape: OperationShape): RuntimeType {
        val inputShape = operationShape.inputShape(model)
        val builderSymbol = inputShape.serverBuilderSymbol(codegenContext)
        val action = operationShape.id.name
        val version = codegenContext.serviceShape.version
        return protocolFunctions.deserializeFn(operationShape, fnNameSuffix = "server_input") { fnName ->
            Attribute.AllowUnusedMut.render(this)
            rustBlockTemplate(
                "pub fn $fnName(inp: &[u8], mut builder: #{Builder}) -> #{Result}<#{Builder}, #{RequestRejection}>",
                *codegenScope,
                "Builder" to builderSymbol,
            ) {
                rustTemplate(
                    """
                    let pairs: #{Vec}<(#{String}, #{String})> = #{FormUrlEncoded}::parse(inp)
                        .map(|(key, value)| (key.into_owned(), value.into_owned()))
                        .collect();

                    let action = #{queryValue}(&pairs, "Action")
                        .ok_or_else(|| #{RequestRejection}::QueryDeserialize("missing Action".into()))?;
                    if action != ${action.dq()} {
                        return Err(#{RequestRejection}::QueryDeserialize(
                            format!("unexpected Action `{action}`, expected `${action}`")
                        ));
                    }

                    let version = #{queryValue}(&pairs, "Version")
                        .ok_or_else(|| #{RequestRejection}::QueryDeserialize("missing Version".into()))?;
                    if version != ${version.dq()} {
                        return Err(#{RequestRejection}::QueryDeserialize(
                            format!("unexpected Version `{version}`, expected `${version}`")
                        ));
                    }
                    """,
                    *codegenScope,
                    "queryValue" to queryValue(),
                )

                for (member in inputShape.members()) {
                    val parsed = safeName("parsed")
                    withBlock("let $parsed = ", "?;") {
                        parseMember(member, member.queryKeyName().dq())
                    }
                    rustBlock("if let Some(value) = $parsed") {
                        setBuilderMember("builder", member, "value")
                    }
                }

                rust("Ok(builder)")
            }
        }
    }

    private fun queryValue(): RuntimeType =
        ProtocolFunctions.crossOperationFn("server_query_value") { fnName ->
            rustBlockTemplate(
                "pub(crate) fn $fnName<'a>(pairs: &'a [(#{String}, #{String})], key: &str) -> #{Option}<&'a str>",
                *codegenScope,
            ) {
                rust("pairs.iter().rev().find(|(candidate, _)| candidate == key).map(|(_, value)| value.as_str())")
            }
        }

    private fun queryContainsPrefix(): RuntimeType =
        ProtocolFunctions.crossOperationFn("server_query_contains_prefix") { fnName ->
            rustBlockTemplate(
                "pub(crate) fn $fnName(pairs: &[(#{String}, #{String})], prefix: &str) -> bool",
                *codegenScope,
            ) {
                rust(
                    """
                    pairs.iter().any(|(key, _)| {
                        key == prefix || key.strip_prefix(prefix).map_or(false, |rest| rest.starts_with('.'))
                    })
                    """,
                )
            }
        }

    private fun RustWriter.setBuilderMember(
        builder: String,
        member: MemberShape,
        value: String,
    ) {
        val valueToSet =
            if (symbolProvider.toSymbol(member).isOptional()) {
                "Some($value)"
            } else {
                value
            }
        rust("$builder = $builder.${member.setterName()}($valueToSet);")
    }

    private fun RustWriter.parseMember(
        member: MemberShape,
        prefixExpression: String,
    ) {
        val target = model.expectShape(member.target)
        val memberType =
            codegenContext.unconstrainedShapeSymbolProvider.toSymbol(member)
                .rustType()
                .stripOuter<RustType.Option>()
                .render(true)
        withBlock("Result::<Option<$memberType>, #T>::Ok(", ")", requestRejection) {
            val isBoxed = symbolProvider.toSymbol(member).isRustBoxed()
            if (isBoxed) {
                rust("(")
            }
            when (target) {
                is StringShape,
                is BooleanShape,
                is NumberShape,
                is TimestampShape,
                is BlobShape,
                is BigIntegerShape,
                is BigDecimalShape,
                -> parseScalarMember(member, prefixExpression)

                is StructureShape -> rust("#T(&pairs, $prefixExpression)?", structureParser(target))
                is CollectionShape ->
                    rust(
                        "#T(&pairs, $prefixExpression, ${member.isFlattened()}, ${target.queryListMemberName().dq()}, ${emptyListMarkerIsSerialized(member)})?",
                        collectionParser(target),
                    )
                is MapShape ->
                    rust(
                        "#T(&pairs, $prefixExpression, ${member.isFlattened()}, ${target.key.queryKeyName("key").dq()}, ${target.value.queryKeyName("value").dq()})?",
                        mapParser(target),
                    )
                is UnionShape -> rust("#T(&pairs, $prefixExpression)?", unionParser(target))
                else -> TODO("unsupported query member target: $target")
            }
            if (isBoxed) {
                rust(").map(::std::boxed::Box::new)")
            }
        }
    }

    private fun RustWriter.parseScalarMember(
        member: MemberShape,
        prefixExpression: String,
    ) {
        rustBlockTemplate("match #{queryValue}(&pairs, $prefixExpression)", *codegenScope, "queryValue" to queryValue()) {
            rust("Some(value) => Some(")
            parseScalarValue(member, "value")
            rust("?),")
            rust("None => None,")
        }
    }

    private fun RustWriter.parseScalarValue(
        member: MemberShape,
        valueExpression: String,
    ) {
        val target = model.expectShape(member.target)
        when (target) {
            is StringShape -> parseStringValue(target, valueExpression)
            is IntEnumShape -> parseIntEnumValue(target, valueExpression)
            is BigIntegerShape ->
                rustTemplate(
                    "<#{BigInteger} as ::std::str::FromStr>::from_str($valueExpression).map_err(|err| #{RequestRejection}::QueryDeserialize(format!(\"invalid BigInteger: {err}\")))",
                    *codegenScope,
                    "BigInteger" to RuntimeType.bigInteger(runtimeConfig),
                )
            is BigDecimalShape ->
                rustTemplate(
                    "<#{BigDecimal} as ::std::str::FromStr>::from_str($valueExpression).map_err(|err| #{RequestRejection}::QueryDeserialize(format!(\"invalid BigDecimal: {err}\")))",
                    *codegenScope,
                    "BigDecimal" to RuntimeType.bigDecimal(runtimeConfig),
                )
            is BooleanShape, is NumberShape ->
                rustTemplate(
                    "<#{Shape} as #{SmithyTypes}::primitive::Parse>::parse_smithy_primitive($valueExpression).map_err(#{RequestRejection}::PrimitiveParse)",
                    *codegenScope,
                    "Shape" to codegenContext.unconstrainedShapeSymbolProvider.toSymbol(target),
                )
            is TimestampShape -> {
                val timestampFormat =
                    member.getMemberTrait(model, TimestampFormatTrait::class.java).orNull()?.format
                        ?: TimestampFormatTrait.Format.DATE_TIME
                val timestampFormatType =
                    RuntimeType.parseTimestampFormat(codegenContext.target, runtimeConfig, timestampFormat)
                rust(
                    "#T::from_str($valueExpression, #T).map_err(#T::DateTimeParse)",
                    RuntimeType.dateTime(runtimeConfig),
                    timestampFormatType,
                    requestRejection,
                )
            }
            is BlobShape ->
                rust(
                    "#T($valueExpression).map(#T::new).map_err(|err| #T::QueryDeserialize(format!(\"invalid base64: {err:?}\")))",
                    RuntimeType.base64Decode(runtimeConfig),
                    RuntimeType.blob(runtimeConfig),
                    requestRejection,
                )
            else -> TODO("unsupported query scalar target: $target")
        }
    }

    private fun RustWriter.parseStringValue(
        target: StringShape,
        valueExpression: String,
    ) {
        if (target is EnumShape || target.hasTrait<EnumTrait>()) {
            val unconstrainedSymbol = codegenContext.unconstrainedShapeSymbolProvider.toSymbol(target)
            if (unconstrainedSymbol.rustType() == symbolProvider.toSymbol(target).rustType()) {
                rustTemplate(
                    "<#{Shape} as ::std::convert::TryFrom<&str>>::try_from($valueExpression).map_err(|err| #{RequestRejection}::QueryDeserialize(format!(\"unknown enum variant: {err}\")))",
                    *codegenScope,
                    "Shape" to symbolProvider.toSymbol(target),
                )
            } else {
                rust("Result::<#T, #T>::Ok($valueExpression.into())", unconstrainedSymbol, requestRejection)
            }
        } else {
            rust(
                "Result::<#T, #T>::Ok($valueExpression.into())",
                codegenContext.unconstrainedShapeSymbolProvider.toSymbol(target),
                requestRejection,
            )
        }
    }

    private fun RustWriter.parseIntEnumValue(
        target: IntEnumShape,
        valueExpression: String,
    ) {
        val unconstrainedSymbol = codegenContext.unconstrainedShapeSymbolProvider.toSymbol(target)
        if (unconstrainedSymbol.rustType() == symbolProvider.toSymbol(target).rustType()) {
            rustTemplate(
                """
                <i32 as #{SmithyTypes}::primitive::Parse>::parse_smithy_primitive($valueExpression)
                    .map_err(#{RequestRejection}::PrimitiveParse)
                    .and_then(|value| <#{Shape} as ::std::convert::TryFrom<i32>>::try_from(value)
                        .map_err(|err| #{RequestRejection}::QueryDeserialize(format!("unknown int enum variant: {err}"))))
                """,
                *codegenScope,
                "Shape" to symbolProvider.toSymbol(target),
            )
        } else {
            rustTemplate(
                "<i32 as #{SmithyTypes}::primitive::Parse>::parse_smithy_primitive($valueExpression).map_err(#{RequestRejection}::PrimitiveParse)",
                *codegenScope,
            )
        }
    }

    private fun structureParser(shape: StructureShape): RuntimeType {
        val returnSymbolToParse = returnSymbolToParse(shape)
        return protocolFunctions.deserializeFn(shape, fnNameSuffix = "query") { fnName ->
            rustBlockTemplate(
                "pub(crate) fn $fnName(pairs: &[(#{String}, #{String})], prefix: &str) -> #{Result}<#{Option}<#{ReturnType}>, #{RequestRejection}>",
                *codegenScope,
                "ReturnType" to returnSymbolToParse.symbol,
            ) {
                rustTemplate(
                    """
                    if !#{queryContainsPrefix}(pairs, prefix) {
                        return Ok(None);
                    }
                    """,
                    *codegenScope,
                    "queryContainsPrefix" to queryContainsPrefix(),
                )
                Attribute.AllowUnusedMut.render(this)
                rust("let mut builder = #T::default();", shape.serverBuilderSymbol(codegenContext))
                for (member in shape.members()) {
                    val memberPrefix = safeName("member_prefix")
                    rust("let $memberPrefix = format!(\"{}.{}\", prefix, ${member.queryKeyName().dq()});")
                    val parsed = safeName("parsed")
                    withBlock("let $parsed = ", "?;") {
                        parseMember(member, "$memberPrefix.as_str()")
                    }
                    rustBlock("if let Some(value) = $parsed") {
                        setBuilderMember("builder", member, "value")
                    }
                }
                val builder =
                    builderInstantiator.finalizeBuilder(
                        "builder",
                        shape,
                    )
                rust("Ok(Some(#T))", builder)
            }
        }
    }

    private fun collectionParser(shape: CollectionShape): RuntimeType =
        protocolFunctions.deserializeFn(shape, fnNameSuffix = "query") { fnName ->
            rustBlockTemplate(
                """
                pub(crate) fn $fnName(
                    pairs: &[(#{String}, #{String})],
                    prefix: &str,
                    flattened: bool,
                    member_name: &'static str,
                    empty_marker: bool,
                ) -> #{Result}<#{Option}<#{Collection}>, #{RequestRejection}>
                """,
                *codegenScope,
                "Collection" to symbolProvider.toSymbol(shape),
            ) {
                rustTemplate(
                    """
                    let has_empty_marker = empty_marker && #{queryValue}(pairs, prefix).map_or(false, |value| value.is_empty());
                    let item_prefix = if flattened {
                        prefix.to_owned()
                    } else {
                        format!("{prefix}.{member_name}")
                    };
                    let mut out = #{Vec}::new();
                    let mut idx = 1;
                    let mut found = false;
                    """,
                    *codegenScope,
                    "queryValue" to queryValue(),
                )
                rustBlock("loop") {
                    rust("let entry_prefix = format!(\"{}.{}\", item_prefix, idx);")
                    val parsed = safeName("parsed")
                    withBlock("let $parsed = ", "?;") {
                        parseMember(shape.member, "entry_prefix.as_str()")
                    }
                    rustBlock("match $parsed") {
                        rust(
                            """
                            Some(value) => {
                                out.push(value);
                                found = true;
                                idx += 1;
                            }
                            None => break,
                            """,
                        )
                    }
                }
                rust(
                    """
                    if found || has_empty_marker {
                        Ok(Some(out))
                    } else {
                        Ok(None)
                    }
                    """,
                )
            }
        }

    private fun mapParser(shape: MapShape): RuntimeType =
        protocolFunctions.deserializeFn(shape, fnNameSuffix = "query") { fnName ->
            rustBlockTemplate(
                """
                pub(crate) fn $fnName(
                    pairs: &[(#{String}, #{String})],
                    prefix: &str,
                    flattened: bool,
                    key_name: &'static str,
                    value_name: &'static str,
                ) -> #{Result}<#{Option}<#{Map}>, #{RequestRejection}>
                """,
                *codegenScope,
                "Map" to symbolProvider.toSymbol(shape),
            ) {
                rustTemplate(
                    """
                    let mut out = #{HashMap}::new();
                    let mut idx = 1;
                    let mut found = false;
                    """,
                    *codegenScope,
                    "HashMap" to RuntimeType.HashMap,
                )
                rustBlock("loop") {
                    rust(
                        """
                        let entry_prefix = if flattened {
                            format!("{prefix}.{idx}")
                        } else {
                            format!("{prefix}.entry.{idx}")
                        };
                        """,
                    )
                    rustTemplate(
                        """
                        if !#{queryContainsPrefix}(pairs, entry_prefix.as_str()) {
                            break;
                        }
                        """,
                        *codegenScope,
                        "queryContainsPrefix" to queryContainsPrefix(),
                    )
                    rust("let key_prefix = format!(\"{}.{}\", entry_prefix, key_name);")
                    rust("let value_prefix = format!(\"{}.{}\", entry_prefix, value_name);")
                    val key = safeName("key")
                    withBlock("let $key = ", "?;") {
                        parseMember(shape.key, "key_prefix.as_str()")
                    }
                    rustTemplate(
                        """
                        let $key = $key.ok_or_else(|| #{RequestRejection}::QueryDeserialize(
                            format!("missing query map key at {key_prefix}")
                        ))?;
                        """,
                        *codegenScope,
                    )
                    val value = safeName("value")
                    withBlock("let $value = ", "?;") {
                        parseMember(shape.value, "value_prefix.as_str()")
                    }
                    rustTemplate(
                        """
                        let $value = $value.ok_or_else(|| #{RequestRejection}::QueryDeserialize(
                            format!("missing query map value at {value_prefix}")
                        ))?;
                        out.insert($key, $value);
                        found = true;
                        idx += 1;
                        """,
                        *codegenScope,
                    )
                }
                rust(
                    """
                    if found {
                        Ok(Some(out))
                    } else {
                        Ok(None)
                    }
                    """,
                )
            }
        }

    private fun unionParser(shape: UnionShape): RuntimeType {
        val returnSymbolToParse = returnSymbolToParse(shape)
        return protocolFunctions.deserializeFn(shape, fnNameSuffix = "query") { fnName ->
            rustBlockTemplate(
                "pub(crate) fn $fnName(pairs: &[(#{String}, #{String})], prefix: &str) -> #{Result}<#{Option}<#{Union}>, #{RequestRejection}>",
                *codegenScope,
                "Union" to returnSymbolToParse.symbol,
            ) {
                rustTemplate(
                    """
                    if !#{queryContainsPrefix}(pairs, prefix) {
                        return Ok(None);
                    }
                    let mut out = None;
                    """,
                    *codegenScope,
                    "queryContainsPrefix" to queryContainsPrefix(),
                )
                for (member in shape.members()) {
                    val variantName = symbolProvider.toMemberName(member)
                    val memberPrefix = safeName("member_prefix")
                    rust("let $memberPrefix = format!(\"{}.{}\", prefix, ${member.queryKeyName().dq()});")
                    if (member.isTargetUnit()) {
                        rustTemplate(
                            """
                            if #{queryContainsPrefix}(pairs, $memberPrefix.as_str()) {
                                if out.is_some() {
                                    return Err(#{RequestRejection}::QueryDeserialize("encountered mixed variants in union".into()));
                                }
                                out = Some(#{Union}::$variantName);
                            }
                            """,
                            *codegenScope,
                            "queryContainsPrefix" to queryContainsPrefix(),
                            "Union" to returnSymbolToParse.symbol,
                        )
                    } else {
                        val parsed = safeName("parsed")
                        withBlock("let $parsed = ", "?;") {
                            parseMember(member, "$memberPrefix.as_str()")
                        }
                        rustBlock("if let Some(value) = $parsed") {
                            rustTemplate(
                                """
                                if out.is_some() {
                                    return Err(#{RequestRejection}::QueryDeserialize("encountered mixed variants in union".into()));
                                }
                                out = Some(#{Union}::$variantName(
                                """,
                                *codegenScope,
                                "Union" to returnSymbolToParse.symbol,
                            )
                            rust("value")
                            rust("));")
                        }
                    }
                }
                rustTemplate(
                    """
                    out.ok_or_else(|| #{RequestRejection}::QueryDeserialize("union did not contain a valid variant".into()))
                        .map(Some)
                    """,
                    *codegenScope,
                )
            }
        }
    }
}

class ServerAwsQueryParserGenerator(
    codegenContext: ServerCodegenContext,
    protocol: ServerProtocol,
) : ServerQueryParserGenerator(codegenContext, protocol) {
    override val protocolName: String = "AWS Query"

    override fun MemberShape.queryKeyName(prioritizedFallback: String?): String =
        getTrait<XmlNameTrait>()?.value ?: prioritizedFallback ?: memberName

    override fun MemberShape.isFlattened(): Boolean = getTrait<XmlFlattenedTrait>() != null
}

class ServerEc2QueryParserGenerator(
    codegenContext: ServerCodegenContext,
    protocol: ServerProtocol,
) : ServerQueryParserGenerator(codegenContext, protocol) {
    override val protocolName: String = "EC2 Query"

    override fun MemberShape.queryKeyName(prioritizedFallback: String?): String =
        getTrait<Ec2QueryNameTrait>()?.value
            ?: getTrait<XmlNameTrait>()?.value?.let { StringUtils.capitalize(it) }
            ?: prioritizedFallback?.let { StringUtils.capitalize(it) }
            ?: StringUtils.capitalize(memberName)

    override fun MemberShape.isFlattened(): Boolean = true

    override fun CollectionShape.queryListMemberName(): String = "member"

    override fun emptyListMarkerIsSerialized(member: MemberShape): Boolean = false
}
