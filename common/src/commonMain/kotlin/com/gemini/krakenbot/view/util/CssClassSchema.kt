package com.gemini.krakenbot.view.util

/** Marks the YAML resource used to generate the common CSS-class catalog. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateCssClasses(val resource: String)

@GenerateCssClasses("codegen/css-classes.yaml")
internal object CssClassSchema
