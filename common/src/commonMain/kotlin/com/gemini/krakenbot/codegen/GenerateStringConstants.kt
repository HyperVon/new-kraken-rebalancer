package com.gemini.krakenbot.codegen

/**
 * Marks an explicit string-constant schema for KSP generation.
 *
 * The processor emits `const val` members, so generated consumers retain the
 * same compile-time constant semantics as handwritten catalogs.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateStringConstants(val fileName: String, val resource: String)
