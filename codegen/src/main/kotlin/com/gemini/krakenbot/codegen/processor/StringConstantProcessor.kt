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
            if (fileName == "CssTheme") {
                line("import com.gemini.krakenbot.view.util.CssThemeVars")
                line("import kotlinx.css.Color")
                line("import kotlinx.css.CssBuilder")
                line("import kotlinx.css.px")
                line("import kotlinx.css.rem")
                line()
            }
            line("/** Generated from @GenerateStringConstants; edit the YAML resource instead. */")
            when (fileName) {
                "CssThemeVars" -> {
                    // Single batch: object + embedded cssVars list so callers have one source of truth
                    val group = input.definitions.firstOrNull()?.group ?: fileName
                    block("object $group") {
                        for (def in input.definitions) {
                            line("const val ${def.name} = \"${escapeKotlinString(def.value)}\"")
                        }
                        line()
                        line("val cssVars: List<Pair<String, String>> by lazy {")
                        line("    listOf(")
                        for (def in input.definitions) {
                            val kebab = toKebabCase(def.name)
                            line("        \"--$kebab\" to ${def.name},")
                        }
                        line("    )")
                        line("}")
                    }
                }
                "CssTheme" -> {
                    // Typed CssTheme for JVM: Color for colors, LinearDimension for radii, String for rest
                    block("object $fileName") {
                        for (def in input.definitions) {
                            val escaped = escapeKotlinString(def.value)
                            when {
                                def.name.startsWith("radius") -> {
                                    val v = def.value
                                    val typed = when {
                                        v.endsWith("px") -> v.removeSuffix("px") + ".px"
                                        v.endsWith("rem") -> v.removeSuffix("rem") + ".rem"
                                        else -> "\"$escaped\""
                                    }
                                    line("val ${def.name} = $typed")
                                }
                                def.name.startsWith("color") -> line("val ${def.name} = Color(\"$escaped\")")
                                else -> line("const val ${def.name} = \"$escaped\"")
                            }
                        }
                        line()
                        line("fun kotlinx.css.CssBuilder.applyRootVariables() {")
                        line("    \":root\" {")
                        line(
                            "        com.gemini.krakenbot.view.util.CssThemeVars.cssVars.forEach { (k, v) -> put(k, v) }",
                        )
                        line("    }")
                        line("}")
                    }
                }
                else -> renderGroups(
                    input.definitions,
                    header = { group -> "object $group" },
                    entry = { definition, _ ->
                        "const val ${definition.name} = \"${escapeKotlinString(definition.value)}\""
                    },
                )
            }
        }.toString()
        support.write(input, packageName, fileName, source)
    }

    private fun toKebabCase(name: String): String = name
        .replace(Regex("([a-z])([A-Z])"), "$1-$2")
        .replace(Regex("([a-zA-Z])(\\d)"), "$1-$2")
        .lowercase()
}
