package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisplayName("ShizukuShellBackend")
class ShizukuShellBackendTest {
    private inner class Helper {
        val binder = mockk<IBinder>()
        val service = mockk<IShellService>()

        @Volatile
        var alive = true

        init {
            every { binder.pingBinder() } answers { alive }
            every { service.asBinder() } returns binder
            every { service.start(any(), any(), any()) } answers {
                callbacks.add(thirdArg())
                onStart()
                descriptor
            }
            every { service.kill(any()) } just runs
        }
    }

    private val api = mockk<ShizukuApi>(relaxed = true)
    private val descriptor = mockk<ParcelFileDescriptor>()
    private val args = CopyOnWriteArrayList<Shizuku.UserServiceArgs>()
    private val connections = CopyOnWriteArrayList<ServiceConnection>()
    private val callbacks = CopyOnWriteArrayList<IShellProcessCallback>()
    private var autoConnect = true
    private var onStart: () -> Unit = {}
    private val helpers = List(MAX_BINDINGS) { Helper() }

    private fun backend(bindTimeout: Duration = 200.milliseconds): ShizukuShellBackend =
        ShizukuShellBackend("com.example", api, { ByteArrayInputStream(ByteArray(0)) }, bindTimeout)

    private fun connect(index: Int) {
        connections[index].onServiceConnected(null, helpers[index].binder)
    }

    @BeforeEach
    fun setUp() {
        mockkStatic(IShellService.Stub::class)
        every { IShellService.Stub.asInterface(any()) } answers {
            helpers.first { it.binder === firstArg<IBinder>() }.service
        }
        coEvery { api.bindUserService(any(), any()) } answers { recordBind(firstArg(), secondArg()) }
    }

    private fun recordBind(
        bindArgs: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
    ) {
        args.add(bindArgs)
        connections.add(connection)
        if (autoConnect) connect(connections.size - 1)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(IShellService.Stub::class)
    }

    @Test
    fun `binds on first spawn and reuses the binding`() =
        runBlocking {
            val backend = backend()

            backend.spawn(1, "a")
            backend.spawn(2, "b")

            assertEquals(1, connections.size)
        }

    @Test
    fun `bind timeout fails the spawn and unbinds`() =
        runBlocking {
            autoConnect = false
            val backend = backend()
            val started = System.nanoTime()

            assertThrows<IllegalStateException> { runBlocking { backend.spawn(1, "a") } }
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS

            assertTrue(elapsedMs in 150..WAIT_MS, "took $elapsedMs ms")
            verify(exactly = 1) { api.unbindUserService(args[0], connections[0]) }
        }

    @Test
    fun `bind throwing does not escape and the next spawn binds again`() =
        runBlocking {
            val backend = backend()
            coEvery { api.bindUserService(any(), any()) } throws IllegalStateException("binder not received")

            val error = assertThrows<IllegalStateException> { runBlocking { backend.spawn(1, "a") } }
            assertTrue(error.message.orEmpty().contains("could not start the shell helper"), error.message)

            coEvery { api.bindUserService(any(), any()) } answers { recordBind(firstArg(), secondArg()) }
            backend.spawn(2, "b")

            coVerify(exactly = 2) { api.bindUserService(any(), any()) }
        }

    @Test
    fun `exit callback completes the process exit`() =
        runBlocking {
            val shell = backend().spawn(1, "a")

            callbacks.single().onExit(1, 5)

            assertEquals(ShellExit(5), withTimeout(WAIT_MS) { shell.exit.await() })
        }

    @Test
    fun `an exit does not release the helper before the manager does`() =
        runBlocking {
            val backend = backend()
            backend.spawn(1, "a")
            backend.spawn(2, "b")

            callbacks.first().onExit(2, 0)
            backend.release(1)

            verify(exactly = 0) { api.unbindUserService(any(), any()) }

            backend.release(2)

            verify(exactly = 1) { api.unbindUserService(args[0], connections[0]) }
        }

    @Test
    fun `release unbinds when the last process is released`() =
        runBlocking {
            val backend = backend()
            backend.spawn(1, "a")
            backend.spawn(2, "b")

            backend.release(1)

            verify(exactly = 0) { api.unbindUserService(any(), any()) }

            backend.release(2)

            verify(exactly = 1) { api.unbindUserService(any(), any()) }
        }

    @Test
    fun `each binding uses new service args`() =
        runBlocking {
            val backend = backend()

            backend.spawn(1, "a")
            backend.release(1)
            backend.spawn(2, "b")

            assertEquals(2, args.size)
            assertNotSame(args[0], args[1])
        }

    @Test
    fun `a dropped binding's disconnect does not fail newer processes`() =
        runBlocking {
            val backend = backend()
            backend.spawn(1, "a")
            backend.release(1)
            val newer = backend.spawn(2, "b")

            connections[0].onServiceDisconnected(null)

            assertFalse(newer.exit.isCompleted)
        }

    @Test
    fun `losing the current binding fails pending processes`() =
        runBlocking {
            val shell = backend().spawn(1, "a")

            connections.single().onServiceDisconnected(null)

            assertEquals(ShellExit(null, "Shizuku stopped"), withTimeout(WAIT_MS) { shell.exit.await() })
        }

    @Test
    fun `a helper that died before its disconnect fails its processes at the next spawn`() =
        runBlocking {
            val backend = backend()
            val first = backend.spawn(1, "a")
            helpers[0].alive = false

            val second = backend.spawn(2, "b")

            assertEquals(ShellExit(null, "Shizuku stopped"), withTimeout(WAIT_MS) { first.exit.await() })
            verify(exactly = 1) { api.unbindUserService(args[0], connections[0]) }
            assertEquals(2, connections.size)
            assertFalse(second.exit.isCompleted)
        }

    @Test
    fun `unbind throwing does not escape`() =
        runBlocking {
            every { api.unbindUserService(any(), any()) } throws RuntimeException("binder died")
            val backend = backend()
            backend.spawn(1, "a")

            assertDoesNotThrow { runBlocking { backend.release(1) } }
        }

    @Test
    fun `releaseAll unbinds and fails pending`() =
        runBlocking {
            val backend = backend()
            val shell = backend.spawn(1, "a")

            backend.releaseAll()

            verify(exactly = 1) { api.unbindUserService(args[0], connections[0]) }
            assertEquals(ShellExit(null, "The server stopped"), withTimeout(WAIT_MS) { shell.exit.await() })
        }

    @Test
    fun `releaseAll while the helper starts a command fails that process`() =
        runBlocking {
            val backend = backend()
            onStart = { backend.releaseAll() }

            val shell = backend.spawn(1, "a")

            assertEquals(ShellExit(null, "The server stopped"), withTimeout(WAIT_MS) { shell.exit.await() })
        }

    @Test
    fun `a binding dropped while connecting is unbound and fails its spawn`() =
        runBlocking {
            autoConnect = false
            val backend = backend(bindTimeout = 5.seconds)
            val spawn = async(Dispatchers.Default) { runCatching { backend.spawn(1, "a") } }
            withTimeout(WAIT_MS) { while (connections.isEmpty()) delay(POLL_MS) }

            backend.releaseAll()
            connect(0)
            val error = spawn.await().exceptionOrNull()

            assertTrue(error is IllegalStateException, error.toString())
            assertTrue(error?.message.orEmpty().contains("stopped while it was starting"), error?.message)
            verify(atLeast = 1) { api.unbindUserService(args[0], connections[0]) }
        }

    @Test
    fun `RemoteException on start becomes IllegalStateException`() =
        runBlocking {
            val backend = backend()
            onStart = { throw RemoteException("helper died") }

            val error = assertThrows<IllegalStateException> { runBlocking { backend.spawn(1, "a") } }

            assertTrue(error.message.orEmpty().startsWith("Shizuku could not start the command"), error.message)
        }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 5L
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_BINDINGS = 4
    }
}
