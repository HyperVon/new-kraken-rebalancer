package com.gemini.krakenbot.codegen.processor

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal data class CatalogDefinition(val group: String, val name: String, val value: String)

internal fun loadYamlCatalog(resource: String, resourceRoot: String): List<CatalogDefinition> {
    if (resource.isBlank()) failCatalog("Catalog resource path is empty")
    if (resourceRoot.isBlank()) failCatalog("Missing KSP option: codegenResourceRoot")

    val rootDirectory = File(resourceRoot).canonicalFile
    val resourceFile = File(rootDirectory, resource).canonicalFile
    if (!resourceFile.toPath().startsWith(rootDirectory.toPath())) {
        failCatalog("Catalog resource escapes codegenResourceRoot: $resource")
    }
    if (!resourceFile.isFile) failCatalog("Catalog resource does not exist: $resource")

    val yamlRoot = try {
        val loaderOptions = LoaderOptions().apply { setAllowDuplicateKeys(false) }
        Yaml(SafeConstructor(loaderOptions)).load<Any?>(resourceFile.readText(Charsets.UTF_8))
    } catch (exception: Exception) {
        failCatalog("Unable to parse catalog resource $resource: ${exception.message}")
    }
    val groups = yamlRoot as? Map<*, *> ?: failCatalog("Catalog root must be a map: $resource")
    val definitions = buildList {
        groups.forEach { (rawGroup, rawEntries) ->
            val group = rawGroup as? String
                ?: failCatalog("Catalog group names must be strings: $resource")
            validateIdentifier(group, "group", resource)
            val entries = rawEntries as? Map<*, *>
                ?: failCatalog("Catalog group must contain a map: $resource/$group")
            entries.forEach { (rawName, rawValue) ->
                val name = rawName as? String
                    ?: failCatalog("Catalog constant names must be strings: $resource/$group")
                validateIdentifier(name, "constant", "$resource/$group")
                val value = rawValue as? String
                    ?: failCatalog("Catalog values must be strings: $resource/$group.$name")
                add(CatalogDefinition(group, name, value))
            }
        }
    }
    if (definitions.isEmpty()) failCatalog("Catalog is empty: $resource")
    return definitions
}

internal fun escapeKotlinString(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

internal fun isKotlinIdentifier(value: String): Boolean = IDENTIFIER.matches(value)

internal fun failCatalog(message: String): Nothing = throw CatalogSchemaException(message)

private fun validateIdentifier(value: String, kind: String, resource: String) {
    if (!isKotlinIdentifier(value)) failCatalog("Invalid Kotlin $kind '$value' in catalog $resource")
}

internal class CatalogSchemaException(message: String) : IllegalArgumentException(message)
