package com.gemini.krakenbot.codegen.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

private const val ANNOTATION_NAME = "com.gemini.krakenbot.view.util.GenerateCssClasses"
private const val PACKAGE_NAME = "com.gemini.krakenbot.view.util"
private const val GENERATED_CSS_CLASSES_COMMENT =
    "/** Generated from @GenerateCssClasses; edit the YAML resource instead. */"

class CssClassProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        environment.createCssClassProcessor()
}

private fun SymbolProcessorEnvironment.createCssClassProcessor(): SymbolProcessor = CssClassProcessor(
    codeGenerator,
    logger,
    options["codegenResourceRoot"].orEmpty(),
)

private class CssClassProcessor(codeGenerator: CodeGenerator, logger: KSPLogger, resourceRoot: String) :
    SymbolProcessor {
    private val processedSourceNames = mutableSetOf<String>()
    private val support = CatalogProcessorSupport(codeGenerator, logger, resourceRoot)

    override fun process(resolver: Resolver): List<KSAnnotated> = support.process(
        resolver,
        ANNOTATION_NAME,
        processedSourceNames,
        ::generate,
    )

    private fun generate(input: CatalogInput) {
        val source = buildSource(input)
        support.write(input, PACKAGE_NAME, CSS_CLASSES_FILE_NAME, source)
    }

    private fun buildSource(input: CatalogInput): String = KotlinSourceBuilder().apply {
        line("package $PACKAGE_NAME")
        line()
        line(GENERATED_CSS_CLASSES_COMMENT)
        block("sealed class $CSS_CLASS_NAME(open val value: String)") {
            renderCssClassBody(input)
        }
    }.toString()

    private fun KotlinSourceBuilder.renderCssClassBody(input: CatalogInput) {
        line(TO_STRING_DECLARATION)
        line()
        line(QUERY_SELECTOR_DECLARATION)
        line(QUERY_SELECTOR_GETTER)
        line()
        line(CSS_CLASS_PLUS_OPERATOR)
        line()
        line("class $COMPOSITE_CLASS_NAME(override val value: String) : $CSS_CLASS_NAME(value)")
        line()
        renderCssClassGroups(input)
    }

    private fun KotlinSourceBuilder.renderCssClassGroups(input: CatalogInput) {
        renderGroups(
            input.definitions,
            header = { group -> "sealed class $group(override val value: String) : $CSS_CLASS_NAME(value)" },
            entry = { definition, group ->
                "object ${definition.name} : $group(\"${escapeKotlinString(definition.value.toString())}\")"
            },
        )
    }
}
