package com.gemini.krakenbot.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val TEST = "test"
private const val HELLO = "hello"

@Suppress("unused")
class ResultTest : StringSpec({
    "Success.fold calls onSuccess" {
        val result: Result<Int> = Result.Success(42)
        val value = result.fold(
            onSuccess = { it + 1 },
            onFailure = { -1 }
        )
        value shouldBe 43
    }

    "Failure.fold calls onFailure" {
        val result: Result<Int> = Result.Failure(Exception(TEST))
        val value = result.fold(
            onSuccess = { it + 1 },
            onFailure = { -1 }
        )
        value shouldBe -1
    }

    "map transforms Success" {
        val result: Result<Int> = Result.Success(42)
        val mapped = result.map { it * 2 }
        mapped.shouldBeInstanceOf<Result.Success<Int>>()
        mapped.value shouldBe 84
    }

    "map preserves Failure" {
        val result: Result<Int> = Result.Failure(Exception(TEST))
        val mapped = result.map { it * 2 }
        mapped.shouldBeInstanceOf<Result.Failure<Int>>()
    }

    "flatMap chains operations" {
        val result: Result<Int> = Result.Success(10)
        val chained = result.flatMap { value ->
            Result.Success(value + 5)
        }
        chained.shouldBeInstanceOf<Result.Success<Int>>()
        chained.value shouldBe 15
    }

    "getOrNull returns value on Success" {
        val result: Result<String> = Result.Success(HELLO)
        result.getOrNull() shouldBe HELLO
    }

    "getOrNull returns null on Failure" {
        val result: Result<String> = Result.Failure(Exception(TEST))
        result.getOrNull() shouldBe null
    }

    "exceptionOrNull returns exception on Failure" {
        val ex = Exception(TEST)
        val result: Result<String> = Result.Failure(ex)
        result.exceptionOrNull() shouldBe ex
    }

    "runCatching returns Success on no error" {
        val result = Result.runCatching { HELLO }
        result.shouldBeInstanceOf<Result.Success<String>>()
        result.value shouldBe HELLO
    }

    "runCatching returns Failure on error" {
        val result = Result.runCatching<String> { throw Exception("boom") }
        result.shouldBeInstanceOf<Result.Failure<String>>()
    }
})
