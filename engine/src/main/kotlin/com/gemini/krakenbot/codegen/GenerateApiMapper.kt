package com.gemini.krakenbot.codegen

import kotlin.reflect.KClass

/** Marks a JVM domain type for generation of its explicit API DTO mapper. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateApiMapper(val target: KClass<*>)
