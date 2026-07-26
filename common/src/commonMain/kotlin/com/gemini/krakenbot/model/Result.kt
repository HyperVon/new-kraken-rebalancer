package com.gemini.krakenbot.model

/**
 * Analyzer-style success/failure without null or thrown control flow.
 * [Failure] carries [Exception] only (not Throwable); distinct from kotlin.Result and OrderResult.
 */
sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure<T>(val exception: Exception) : Result<T>()

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (Exception) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(exception)
    }

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> Failure(exception)
    }

    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(value)
        is Failure -> Failure(exception)
    }

    fun getOrNull(): T? = (this as? Success)?.value

    fun exceptionOrNull(): Exception? = (this as? Failure)?.exception

    companion object {
        inline fun <T> runCatching(block: () -> T): Result<T> = try {
            Success(block())
        } catch (ex: Exception) {
            Failure(ex)
        }
    }
}
