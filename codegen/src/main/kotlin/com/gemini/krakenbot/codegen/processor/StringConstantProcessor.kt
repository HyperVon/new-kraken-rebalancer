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
        if (fileName != CSS_THEME_FILE_NAME) return

        listOf(
            CSS_THEME_VARS_IMPORT,
            COLOR_IMPORT,
            CSS_BUILDER_IMPORT,
            PX_IMPORT,
            REM_IMPORT,
        ).forEach(::line)

        line()
    }

    private fun KotlinSourceBuilder.renderGeneratedBody(input: CatalogInput, fileName: String) {
        when (fileName) {
            CSS_THEME_VARS_FILE_NAME -> renderCssThemeVars(input, fileName)
            CSS_THEME_FILE_NAME -> renderCssTheme(input, fileName)
            else -> renderDefaultGroups(input)
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
