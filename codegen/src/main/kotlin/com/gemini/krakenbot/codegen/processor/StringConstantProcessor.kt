package com.gemini.krakenbot.codegen.processor

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

private const val ANNOTATION_NAME = "com.gemini.krakenbot.codegen.GenerateStringConstants"
private const val GENERATED_SOURCE_COMMENT =
    "/** Generated from @GenerateStringConstants; edit the YAML resource instead. */"

class StringConstantProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = StringConstantProcessor(
        CatalogProcessorSupport(
            environment.codeGenerator,
            environment.logger,
            environment.options["codegenResourceRoot"].orEmpty(),
        ),
    )
}

private class StringConstantProcessor(private val support: CatalogProcessorSupport) : SymbolProcessor {
    private val generatedSources = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> =
        support.process(resolver, ANNOTATION_NAME, generatedSources, ::generate)

    private fun generate(input: CatalogInput) {
        val fileName = input.stringArgument(ANNOTATION_NAME, FILE_NAME_ARGUMENT)

        if (!isKotlinIdentifier(fileName)) {
            failCatalog("Generated constant file name is not a Kotlin identifier: $fileName")
        }

        val packageName = input.declaration.packageName.asString()
        val sourceText = buildGeneratedSource(input, packageName, fileName)

        support.write(input, packageName, fileName, sourceText)
    }

    private fun buildGeneratedSource(input: CatalogInput, packageName: String, fileName: String): String =
        KotlinSourceBuilder().apply {
            line("package $packageName")
            line()

            renderThemeImportsIfNeeded(fileName)

            line(GENERATED_SOURCE_COMMENT)
            renderGeneratedBody(input, fileName)
        }.toString()

    private fun KotlinSourceBuilder.renderThemeImportsIfNeeded(fileName: String) {
        when (fileName) {
            CSS_THEME_FILE_NAME -> {
                listOf(
                    CSS_THEME_VARS_IMPORT,
                    COLOR_IMPORT,
                    CSS_BUILDER_IMPORT,
                    PX_IMPORT,
                    REM_IMPORT,
                ).forEach(::line)
                line()
            }

            CHART_PROPS_FILE_NAME -> {
                line(ASSET_IMPORT)
                line()
            }
        }
    }

    private fun KotlinSourceBuilder.renderGeneratedBody(input: CatalogInput, fileName: String) {
        when (fileName) {
            CSS_THEME_VARS_FILE_NAME -> renderCssThemeVars(input, fileName)
            CSS_THEME_FILE_NAME -> renderCssTheme(input, fileName)
            CHART_PROPS_FILE_NAME -> renderChartProps(input, fileName)
            else -> renderDefaultGroups(input)
        }
    }

    private fun KotlinSourceBuilder.renderChartProps(input: CatalogInput, fileName: String) {
        block("object $fileName") {
            input.definitions.forEach { definition ->
                line(renderDefinition(definition))
            }

            line()
            line("val PALETTE_BORDER_COLORS = arrayOf(")
            line("    COLOR_BLUE,")
            line("    COLOR_EMERALD,")
            line("    COLOR_AMBER,")
            line("    COLOR_VIOLET,")
            line("    COLOR_RED,")
            line("    COLOR_TEAL,")
            line("    COLOR_ORANGE,")
            line("    COLOR_FUCHSIA,")
            line(")")
            line()
            line("val PALETTE_BG_COLORS = arrayOf(")
            line("    COLOR_BLUE_BG_PALETTE,")
            line("    COLOR_EMERALD_BG_PALETTE,")
            line("    COLOR_AMBER_BG_PALETTE,")
            line("    COLOR_VIOLET_BG_PALETTE,")
            line("    COLOR_RED_BG_PALETTE,")
            line("    COLOR_TEAL_BG_PALETTE,")
            line("    COLOR_ORANGE_BG_PALETTE,")
            line("    COLOR_FUCHSIA_BG_PALETTE,")
            line(")")
            line()
            line("private val SOLID_FALLBACK_PALETTE =")
            line("    arrayOf(")
            line("        SOLID_BLUE,")
            line("        SOLID_EMERALD,")
            line("        SOLID_AMBER,")
            line("        SOLID_VIOLET,")
            line("        SOLID_RED,")
            line("        SOLID_TEAL,")
            line("        SOLID_ORANGE,")
            line("        SOLID_FUCHSIA,")
            line("    )")
            line()
            line(
                "private class SymbolColors(val btc: String, val eth: String, val usd: String, val fallbackPalette: Array<String>)",
            )
            line()
            line("private val BORDER_COLORS = SymbolColors(")
            line("    btc = COLOR_AMBER,")
            line("    eth = COLOR_VIOLET,")
            line("    usd = COLOR_SLATE,")
            line("    fallbackPalette = PALETTE_BORDER_COLORS,")
            line(")")
            line()
            line("private val BG_COLORS = SymbolColors(")
            line("    btc = COLOR_AMBER_BG_PALETTE,")
            line("    eth = COLOR_VIOLET_BG_PALETTE,")
            line("    usd = COLOR_SLATE_BG_PALETTE,")
            line("    fallbackPalette = PALETTE_BG_COLORS,")
            line(")")
            line()
            line("private val SOLID_COLORS = SymbolColors(")
            line("    btc = SOLID_BTC,")
            line("    eth = SOLID_ETH,")
            line("    usd = SOLID_USD,")
            line("    fallbackPalette = SOLID_FALLBACK_PALETTE,")
            line(")")
            line()
            line("private fun colorForSymbol(symbol: String, fallbackIndex: Int, colors: SymbolColors): String =")
            line("    when (symbol.uppercase()) {")
            line("        Asset.BTC -> colors.btc")
            line("        Asset.ETH -> colors.eth")
            line("        Asset.USD -> colors.usd")
            line("        else -> colors.fallbackPalette[fallbackIndex % colors.fallbackPalette.size]")
            line("    }")
            line()
            line("/** Default per-asset chart colors; Settings-stored colors override when present. */")
            line("fun borderColorForSymbol(symbol: String, fallbackIndex: Int = 0): String =")
            line("    colorForSymbol(symbol, fallbackIndex, BORDER_COLORS)")
            line()
            line("fun backgroundColorForSymbol(symbol: String, fallbackIndex: Int = 0): String =")
            line("    colorForSymbol(symbol, fallbackIndex, BG_COLORS)")
            line()
            line("fun solidColorForSymbol(symbol: String, fallbackIndex: Int = 0): String =")
            line("    colorForSymbol(symbol, fallbackIndex, SOLID_COLORS)")
        }
    }

    private fun KotlinSourceBuilder.renderCssThemeVars(input: CatalogInput, fileName: String) {
        val objectName = input.definitions.firstOrNull()?.group ?: fileName

        block("object $objectName") {
            input.definitions.forEach { definition ->
                line(renderDefinition(definition))
            }

            line()
            renderCssVarsProperty(input.definitions)
        }
    }

    private fun KotlinSourceBuilder.renderCssVarsProperty(definitions: List<CatalogDefinition>) {
        line("val cssVars: List<Pair<String, String>> by lazy {")
        line("    listOf(")

        definitions.forEach { definition ->
            line(renderCssVariablePair(definition.name))
        }

        line("    )")
        line("}")
    }

    private fun KotlinSourceBuilder.renderCssTheme(input: CatalogInput, fileName: String) {
        block("object $fileName") {
            input.definitions.forEach { definition ->
                line(renderCssThemeDefinition(definition))
            }

            line()
            renderRootVariablesFunction()
        }
    }

    private fun renderCssThemeDefinition(definition: CatalogDefinition): String = when {
        definition.name.startsWith(CSS_RADIUS_PREFIX) -> renderRadiusDefinition(definition)
        definition.name.startsWith(CSS_COLOR_PREFIX) -> renderColorDefinition(definition)
        else -> renderDefinition(definition)
    }

    private fun renderRadiusDefinition(definition: CatalogDefinition): String {
        val strValue = definition.value.toString()
        val escapedValue = escapeKotlinString(strValue)
        val renderedValue = renderRadiusValue(strValue, escapedValue)

        return "val ${definition.name} = $renderedValue"
    }

    private fun renderColorDefinition(definition: CatalogDefinition): String {
        val escapedValue = escapeKotlinString(definition.value.toString())

        return "val ${definition.name} = Color(\"$escapedValue\")"
    }

    private fun renderDefinition(definition: CatalogDefinition): String = when (val value = definition.value) {
        is Number -> "const val ${definition.name} = $value"
        is Boolean -> "const val ${definition.name} = $value"
        is String -> "const val ${definition.name} = \"${escapeKotlinString(value)}\""
        else -> "const val ${definition.name} = \"${escapeKotlinString(value.toString())}\""
    }

    private fun KotlinSourceBuilder.renderRootVariablesFunction() {
        line("fun CssBuilder.applyRootVariables() {")
        line("    \":root\" {")
        line("        CssThemeVars.cssVars.forEach { (key, value) -> put(key, value) }")
        line("    }")
        line("}")
    }

    private fun KotlinSourceBuilder.renderDefaultGroups(input: CatalogInput) {
        renderGroups(
            input.definitions,
            header = { group -> "object $group" },
            entry = { definition, _ -> renderDefinition(definition) },
        )
    }

    private fun renderCssVariablePair(constantName: String): String {
        val cssVariableName = CSS_VARIABLE_PREFIX + toKebabCase(constantName)

        return "\"$cssVariableName\" to $constantName,"
    }

    private fun renderRadiusValue(value: String, escapedValue: String): String = when {
        value.endsWith(PIXEL_SUFFIX) -> renderNumericCssValue(value, PIXEL_SUFFIX, PIXEL_SUFFIX)
        value.endsWith(REM_SUFFIX) -> renderNumericCssValue(value, REM_SUFFIX, REM_SUFFIX)
        else -> "\"$escapedValue\""
    }

    private fun renderNumericCssValue(value: String, suffix: String, kotlinCssExtension: String): String {
        val numericValue = value.removeSuffix(suffix)

        return "$numericValue.$kotlinCssExtension"
    }

    private fun toKebabCase(name: String): String = name
        .replace(LOWER_TO_UPPER_BOUNDARY_REGEX, PLACEHOLDER_FORMAT)
        .replace(LETTER_TO_DIGIT_BOUNDARY_REGEX, PLACEHOLDER_FORMAT)
        .lowercase()
}
