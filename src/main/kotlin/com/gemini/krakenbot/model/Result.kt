package com.gemini.krakenbot.model

/**
 * Represents the outcome of an operation that may succeed or fail.
 * Provides type-safe error handling without using nulls or exceptions for control flow.
 */
sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure<T>(val exception: Exception) : Result<T>()

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (Exception) -> R): R =
        when (this) {
            is Success -> onSuccess(value)
            is Failure -> onFailure(exception)
        }

    inline fun <R> map(transform: (T) -> R): Result<R> =
        when (this) {
            is Success -> Success(transform(value))
            is Failure -> Failure(exception)
        }

    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> =
        when (this) {
            is Success -> transform(value)
            is Failure -> Failure(exception)
        }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (Exception) -> Unit): Result<T> {
        if (this is Failure) action(exception)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.value

    fun exceptionOrNull(): Exception? = (this as? Failure)?.exception

    companion object {
        inline fun <T> runCatching(block: () -> T): Result<T> =
            try {
                Success(block())
            } catch (ex: Exception) {
                Failure(ex)
            }
    }
}

/**
 * Extension for use in coroutines and suspend functions.
 */
suspend inline fun <T> resultOf(block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (ex: Exception) {
        Result.Failure(ex)
    }
