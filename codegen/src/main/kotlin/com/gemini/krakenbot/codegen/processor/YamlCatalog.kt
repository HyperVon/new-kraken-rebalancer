package com.gemini.krakenbot.codegen.processor

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

internal data class CatalogDefinition(val group: String, val name: String, val value: String)

internal fun loadYamlCatalog(resource: String, resourceRoot: String): List<CatalogDefinition> {
    val resourceFile = resolveCatalogFile(resource, resourceRoot)
    val yamlRoot = readYamlRoot(resourceFile, resource)
    val groups = yamlRoot as? Map<*, *> ?: failCatalog("Catalog root must be a map: $resource")

    return parseCatalogDefinitions(groups, resource).also { definitions ->
        if (definitions.isEmpty()) failCatalog("Catalog is empty: $resource")
    }
}

private fun resolveCatalogFile(resource: String, resourceRoot: String): File {
    if (resource.isBlank()) failCatalog("Catalog resource path is empty")
    if (resourceRoot.isBlank()) failCatalog("Missing KSP option: codegenResourceRoot")

    val rootDirectory = File(resourceRoot).canonicalFile
    val resourceFile = File(rootDirectory, resource).canonicalFile

    if (!resourceFile.toPath().startsWith(rootDirectory.toPath())) {
        failCatalog("Catalog resource escapes codegenResourceRoot: $resource")
    }
    if (!resourceFile.isFile) {
        failCatalog("Catalog resource does not exist: $resource")
    }

    return resourceFile
}

private fun readYamlRoot(resourceFile: File, resource: String): Any? =
    try {
        createYamlParser().load<Any?>(resourceFile.readText(Charsets.UTF_8))
    } catch (exception: Exception) {
        failCatalog("Unable to parse catalog resource $resource: ${exception.message}")
    }

private fun createYamlParser(): Yaml {
    val loaderOptions = LoaderOptions().apply { isAllowDuplicateKeys = false }
    return Yaml(SafeConstructor(loaderOptions))
}

private fun parseCatalogDefinitions(groups: Map<*, *>, resource: String): List<CatalogDefinition> =
    buildList {
        groups.forEach { (rawGroup, rawEntries) ->
            val group = rawGroup as? String
                ?: failCatalog("Catalog group names must be strings: $resource")
            validateIdentifier(group, "group", resource)

            val entries = rawEntries as? Map<*, *>
                ?: failCatalog("Catalog group must contain a map: $resource/$group")

            addAll(parseGroupDefinitions(group, entries, resource))
        }
    }

private fun parseGroupDefinitions(
    group: String,
    entries: Map<*, *>,
    resource: String,
): List<CatalogDefinition> =
    buildList {
        entries.forEach { (rawName, rawValue) ->
            val catalogLocation = "$resource/$group"
            val name = rawName as? String
                ?: failCatalog("Catalog constant names must be strings: $catalogLocation")
            validateIdentifier(name, "constant", catalogLocation)

            val value = rawValue as? String
                ?: failCatalog("Catalog values must be strings: $catalogLocation.$name")

            add(CatalogDefinition(group, name, value))
        }
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

internal fun isKotlinIdentifier(value: String): Boolean = KOTLIN_IDENTIFIER_REGEX.matches(value)

internal fun failCatalog(message: String): Nothing = throw CatalogSchemaException(message)

private fun validateIdentifier(value: String, kind: String, resource: String) {
    if (!isKotlinIdentifier(value)) failCatalog("Invalid Kotlin $kind '$value' in catalog $resource")
}

internal class CatalogSchemaException(message: String) : IllegalArgumentException(message)
