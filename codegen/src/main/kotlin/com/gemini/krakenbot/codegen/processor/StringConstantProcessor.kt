package com.gemini.krakenbot.codegen.processor

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

private const val ANNOTATION_NAME = "com.gemini.krakenbot.codegen.GenerateStringConstants"

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
        val fileName = input.stringArgument(ANNOTATION_NAME, "fileName")
        if (!isKotlinIdentifier(fileName)) {
            failCatalog("Generated constant file name is not a Kotlin identifier: $fileName")
        }
        val packageName = input.declaration.packageName.asString()
        val source = KotlinSourceBuilder().apply {
            line("package $packageName")
            line()
            line("/** Generated from @GenerateStringConstants; edit the YAML resource instead. */")
            renderGroups(
                input.definitions,
                header = { group -> "object $group" },
                entry = { definition, _ ->
                    "const val ${definition.name} = \"${escapeKotlinString(definition.value)}\""
                },
            )
        }.toString()
        support.write(input, packageName, fileName, source)
    }
}
