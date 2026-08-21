package com.gemini.krakenbot.codegen.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.io.OutputStreamWriter

internal const val FILE_NAME_ARGUMENT = "fileName"

internal data class CatalogInput(
    val declaration: KSClassDeclaration,
    val sourceFile: KSFile,
    val arguments: Map<String, Any?>,
    val definitions: List<CatalogDefinition>,
) {
    fun stringArgument(annotationName: String, name: String): String =
        arguments[name] as? String ?: failCatalog("$annotationName requires $name")
}

internal class CatalogProcessorSupport(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val resourceRoot: String,
) {
    fun process(
        resolver: Resolver,
        annotationName: String,
        generatedSources: MutableSet<String>,
        generate: (CatalogInput) -> Unit,
    ): List<KSAnnotated> {
        resolver
            .getSymbolsWithAnnotation(annotationName)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                val sourceName = declaration.qualifiedNameString()
                if (sourceName in generatedSources) return@forEach
                try {
                    val arguments = declaration.annotationArguments(annotationName)
                    val resource = arguments["resource"] as? String
                        ?: failCatalog("$annotationName requires a resource path")
                    val sourceFile = declaration.containingFile
                        ?: failCatalog("$annotationName has no source file")
                    generate(
                        CatalogInput(
                            declaration,
                            sourceFile,
                            arguments,
                            loadYamlCatalog(resource, resourceRoot),
                        ),
                    )
                    generatedSources += sourceName
                } catch (exception: CatalogSchemaException) {
                    logger.error(exception.message.orEmpty(), declaration)
                }
            }
        return emptyList()
    }

    fun write(input: CatalogInput, packageName: String, fileName: String, source: String) {
        OutputStreamWriter(
            codeGenerator.createNewFile(
                Dependencies(aggregating = false, input.sourceFile),
                packageName,
                fileName,
            ),
            Charsets.UTF_8,
        ).use { it.write(source) }
    }
}

private fun KSClassDeclaration.annotationArguments(annotationName: String): Map<String, Any?> {
    val annotation = annotations.firstOrNull { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedNameString() == annotationName
    } ?: failCatalog("Missing $annotationName annotation")
    return annotation.arguments.associate { argument ->
        val name = argument.name?.asString() ?: failCatalog("$annotationName arguments must be named")
        name to argument.value
    }
}

internal fun KSDeclaration.qualifiedNameString(): String =
    qualifiedName?.asString() ?: failCatalog("Declaration has no qualified name: ${simpleName.asString()}")

internal data class CatalogGroup(val name: String, val definitions: List<CatalogDefinition>)

internal fun List<CatalogDefinition>.groups(): List<CatalogGroup> =
    groupBy(CatalogDefinition::group).map { (name, definitions) -> CatalogGroup(name, definitions) }

internal class KotlinSourceBuilder {
    private val lines = mutableListOf<String>()
    private var indentation = 0

    fun line(value: String = "") {
        lines += "    ".repeat(indentation) + value
    }

    fun block(header: String, body: KotlinSourceBuilder.() -> Unit) {
        line("$header {")
        indentation++
        body()
        indentation--
        line("}")
    }

    override fun toString(): String = lines.joinToString("\n") + "\n"
}

internal fun KotlinSourceBuilder.renderGroups(
    definitions: List<CatalogDefinition>,
    header: (String) -> String,
    entry: (CatalogDefinition, String) -> String,
) {
    definitions.groups().forEach { group ->
        block(header(group.name)) {
            group.definitions.forEach { definition ->
                line(entry(definition, group.name))
            }
        }
        line()
    }
}
