import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

interface MyService {
    suspend fun doSomething(): String
}

fun main() = runBlocking {
    val service = mockk<MyService>()
    coEvery { service.doSomething() } returns "Hello"
    println(service.doSomething())
}
