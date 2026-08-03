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
private const val FILE_NAME = "CssClasses"

class CssClassProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = CssClassProcessor(
        environment.codeGenerator,
        environment.logger,
        environment.options["codegenResourceRoot"].orEmpty(),
    )
}

private class CssClassProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val resourceRoot: String,
) : SymbolProcessor {
    private val generatedSources = mutableSetOf<String>()
    private val support = CatalogProcessorSupport(codeGenerator, logger, resourceRoot)

    override fun process(resolver: Resolver): List<KSAnnotated> = support.process(
        resolver,
        ANNOTATION_NAME,
        generatedSources,
        ::generate,
    )

    private fun generate(input: CatalogInput) {
        val source = KotlinSourceBuilder().apply {
            line("package $PACKAGE_NAME")
            line()
            line("/** Generated from @GenerateCssClasses; edit the YAML resource instead. */")
            block("sealed class CssClass(open val value: String)") {
                line("override fun toString(): String = value")
                line()
                line("val querySelector: String")
                line(
                    """    get() = value.split(" ").filter { it.isNotBlank() }.joinToString("") { ".${'$'}it" }""",
                )
                line()
                line(
                    "operator fun plus(other: CssClass): CssClass = Composite(\"${'$'}value ${'$'}{other.value}\".trim())",
                )
                line()
                line("class Composite(override val value: String) : CssClass(value)")
                line()
                renderGroups(
                    input.definitions,
                    header = { group -> "sealed class $group(override val value: String) : CssClass(value)" },
                    entry = { definition, group ->
                        "object ${definition.name} : $group(\"${escapeKotlinString(definition.value)}\")"
                    },
                )
            }
        }.toString()
        support.write(input, PACKAGE_NAME, FILE_NAME, source)
    }
}
