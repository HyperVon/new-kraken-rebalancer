package com.gemini.krakenbot.codegen.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import java.io.OutputStreamWriter

private const val ANNOTATION_NAME = "com.gemini.krakenbot.codegen.GenerateApiMapper"
private const val GENERATED_API_MAPPER_COMMENT =
    "/** Generated from @GenerateApiMapper; only target constructor properties are emitted. */"

class ApiMapperProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ApiMapperProcessor(environment.codeGenerator, environment.logger)
}

private class ApiMapperProcessor(private val codeGenerator: CodeGenerator, private val logger: KSPLogger) :
    SymbolProcessor {
    private val generatedFiles = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val mappings =
            resolver
                .getSymbolsWithAnnotation(ANNOTATION_NAME)
                .filterIsInstance<KSClassDeclaration>()
                .map { source -> source to source.apiTarget() }
                .toList()
        val mappingsBySource = mappings.associate { (source, target) -> source.qualifiedNameString() to target }

        mappings.forEach { (source, target) ->
            val sourceName = source.qualifiedNameString()
            if (sourceName in generatedFiles) return@forEach
            try {
                generate(source, target, mappingsBySource)
                generatedFiles += sourceName
            } catch (exception: MappingException) {
                logger.error(exception.message.orEmpty(), source)
            }
        }
        return emptyList()
    }

    private fun generate(
        source: KSClassDeclaration,
        target: KSClassDeclaration,
        mappingsBySource: Map<String, KSClassDeclaration>,
    ) {
        val sourceFile = source.containingFile ?: fail("${source.qualifiedNameString()} has no source file")
        val targetConstructor = target.primaryConstructor
            ?: fail("${target.qualifiedNameString()} has no primary constructor")
        val sourceProperties = source.declarations
            .filterIsInstance<KSPropertyDeclaration>()
            .associateBy { it.simpleName.asString() }
        val packageName = target.packageName.asString()
        val imports = linkedMapOf<String, String>()
        val sourceTypeName = source.renderReference(packageName, imports, "Domain")
        val targetTypeName = target.renderReference(packageName, imports, "Api")
        val arguments = targetConstructor.parameters.map { parameter ->
            val name = parameter.name?.asString()
                ?: fail("Unnamed target parameter in ${target.qualifiedNameString()}")
            val sourceProperty = sourceProperties[name]
                ?: fail("${source.qualifiedNameString()} does not define target property '$name'")
            val expression = mapExpression(
                sourceType = sourceProperty.type.resolve(),
                targetType = parameter.type.resolve(),
                expression = name,
                path = "${source.qualifiedNameString()}.$name",
                mappingsBySource = mappingsBySource,
            )
            name to expression
        }
        val sourceText = buildString {
            appendLine("package $packageName")
            if (imports.isNotEmpty()) {
                appendLine()
                imports.forEach { (qualifiedName, alias) ->
                    appendLine("import $qualifiedName as $alias")
                }
            }
            appendLine()
            appendLine(GENERATED_API_MAPPER_COMMENT)
            appendLine("fun $sourceTypeName.toApiDto(): $targetTypeName = $targetTypeName(")
            arguments.forEachIndexed { index, (name, expression) ->
                val comma = if (index == arguments.lastIndex) "" else ","
                appendLine("    $name = $expression$comma")
            }
            appendLine(")")
        }
        val fileName = source.qualifiedNameString()
            .removePrefix("${source.packageName.asString()}.")
            .replace('.', '_') + "ApiMapper"
        val dependencies = target.containingFile?.let {
            Dependencies(aggregating = false, sourceFile, it)
        } ?: Dependencies(aggregating = false, sourceFile)
        OutputStreamWriter(
            codeGenerator.createNewFile(dependencies, packageName, fileName),
            Charsets.UTF_8,
        ).use { writer -> writer.write(sourceText) }
    }

    private fun mapExpression(
        sourceType: KSType,
        targetType: KSType,
        expression: String,
        path: String,
        mappingsBySource: Map<String, KSClassDeclaration>,
    ): String {
        if (sourceType.isError || targetType.isError) fail("Unresolved type at $path")
        if (sourceType.isMarkedNullable && !targetType.isMarkedNullable) {
            fail("Nullable source cannot map to non-null target at $path")
        }
        if (typeKey(sourceType) == typeKey(targetType)) return expression
        if (sourceType.isMarkedNullable) {
            val inner = mapExpression(
                sourceType = sourceType.makeNotNullable(),
                targetType = targetType.makeNotNullable(),
                expression = "value",
                path = path,
                mappingsBySource = mappingsBySource,
            )
            return "$expression?.let { value -> $inner }"
        }

        val sourceName = sourceType.declarationName()
        val targetName = targetType.declarationName()
        val sourceClass = sourceType.declaration as? KSClassDeclaration
        return when {
            sourceName == BIG_DECIMAL_NAME && targetName == STRING_NAME -> "$expression.toPlainString()"
            sourceName == INSTANT_NAME && targetName == STRING_NAME -> "$expression.toString()"
            sourceName == ASSET_NAME && targetName == STRING_NAME -> "$expression.value"
            sourceClass?.classKind == ClassKind.ENUM_CLASS && targetName == STRING_NAME -> "$expression.name"
            mappingsBySource[sourceName]?.qualifiedNameString() == targetName -> "$expression.toApiDto()"
            sourceName == LIST_NAME && targetName == LIST_NAME -> mapList(
                sourceType,
                targetType,
                expression,
                path,
                mappingsBySource,
            )
            sourceName == MAP_NAME && targetName == MAP_NAME -> mapMap(
                sourceType,
                targetType,
                expression,
                path,
                mappingsBySource,
            )
            else -> fail("Unsupported mapping at $path: ${typeKey(sourceType)} -> ${typeKey(targetType)}")
        }
    }

    private fun mapList(
        sourceType: KSType,
        targetType: KSType,
        expression: String,
        path: String,
        mappingsBySource: Map<String, KSClassDeclaration>,
    ): String {
        val sourceElement = sourceType.argumentType(0, path)
        val targetElement = targetType.argumentType(0, path)
        val mappedElement = mapExpression(
            sourceElement,
            targetElement,
            "element",
            "$path[]",
            mappingsBySource,
        )
        return "$expression.map { element -> $mappedElement }"
    }

    private fun mapMap(
        sourceType: KSType,
        targetType: KSType,
        expression: String,
        path: String,
        mappingsBySource: Map<String, KSClassDeclaration>,
    ): String {
        val sourceKey = sourceType.argumentType(0, path)
        val targetKey = targetType.argumentType(0, path)
        val sourceValue = sourceType.argumentType(1, path)
        val targetValue = targetType.argumentType(1, path)
        val mappedKey = mapExpression(
            sourceKey,
            targetKey,
            "key",
            "$path.keys",
            mappingsBySource,
        )
        val mappedValue = mapExpression(
            sourceValue,
            targetValue,
            "value",
            "$path.values",
            mappingsBySource,
        )
        return when {
            mappedKey == "key" && mappedValue == "value" -> expression
            mappedKey == "key" -> "$expression.mapValues { (_, value) -> $mappedValue }"
            mappedValue == "value" -> "$expression.mapKeys { (key, _) -> $mappedKey }"
            else -> "$expression.map { (key, value) -> $mappedKey to $mappedValue }.toMap()"
        }
    }

    private fun KSClassDeclaration.apiTarget(): KSClassDeclaration {
        val annotation = annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedNameString() == ANNOTATION_NAME
        } ?: fail("Missing $ANNOTATION_NAME annotation")
        val targetType = annotation.arguments.firstOrNull()?.value as? KSType
            ?: fail("$ANNOTATION_NAME requires a class target")
        return targetType.declaration as? KSClassDeclaration
            ?: fail("$ANNOTATION_NAME target must be a class")
    }

    private fun KSType.argumentType(index: Int, path: String): KSType =
        arguments.getOrNull(index)?.type?.resolve() ?: fail("Missing generic argument $index at $path")

    private fun KSClassDeclaration.renderReference(
        packageName: String,
        imports: MutableMap<String, String>,
        prefix: String,
    ): String {
        val qualifiedName = qualifiedNameString()
        val localName = if (this.packageName.asString() == packageName) {
            qualifiedName.removePrefix("$packageName.")
        } else {
            val alias = prefix + qualifiedName
                .removePrefix("${this.packageName.asString()}.")
                .replace('.', '_')
            imports[qualifiedName] = alias
            alias
        }
        return localName
    }

    private fun KSType.declarationName(): String = declaration.qualifiedNameString()

    private fun typeKey(type: KSType): String = buildString {
        append(type.declarationName())
        if (type.arguments.isNotEmpty()) {
            append('<')
            append(
                type.arguments.joinToString(",") { argument ->
                    argument.type?.resolve()?.let(::typeKey) ?: "*"
                },
            )
            append('>')
        }
    }

    private fun KSDeclaration.qualifiedNameString(): String =
        qualifiedName?.asString() ?: fail("Declaration has no qualified name: ${simpleName.asString()}")

    private fun fail(message: String): Nothing = throw MappingException(message)
}

private class MappingException(message: String) : IllegalArgumentException(message)
