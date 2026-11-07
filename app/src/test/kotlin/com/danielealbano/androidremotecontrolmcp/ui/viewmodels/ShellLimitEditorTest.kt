package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ShellLimitEditor")
class ShellLimitEditorTest {
    private val defaultLimit = ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES
    private val maxLimit = ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES
    private val stores = mutableListOf<Pair<ShellLimit, Int>>()
    private var answer: suspend (ShellLimit, Int, Int) -> Boolean = { _, _, _ -> true }

    private val editor =
        ShellLimitEditor { limit, value ->
            stores += limit to value
            answer(limit, value, stores.count { it == limit to value })
        }

    private fun error(limit: ShellLimit): ShellLimitInputError? = editor.fields.value[limit]?.error

    private fun text(limit: ShellLimit): String? = editor.fields.value[limit]?.text

    @Test
    fun `load fills every field from the config`() {
        val config = ShellConfig()

        editor.load(config)

        ShellLimit.entries.forEach { limit ->
            assertEquals(config.limit(limit).toString(), text(limit))
            assertNull(error(limit))
        }
    }

    @Test
    fun `invalid input is reported and not returned`() {
        val limit = ShellLimit.MAX_RUNNING_PROCESSES

        assertNull(editor.edit(limit, " "))
        assertEquals(ShellLimitInputError.REQUIRED, error(limit))
        assertNull(editor.edit(limit, "abc"))
        assertEquals(ShellLimitInputError.NOT_A_NUMBER, error(limit))
        assertNull(editor.edit(limit, "65"))
        assertEquals(ShellLimitInputError.OUT_OF_RANGE, error(limit))
    }

    @Test
    fun `valid input is returned for saving`() {
        assertEquals(12, editor.edit(ShellLimit.MAX_RUNNING_PROCESSES, " 12 "))
        assertNull(error(ShellLimit.MAX_RUNNING_PROCESSES))
    }

    @Test
    fun `refused default shows above max`() =
        runTest {
            answer = { _, _, _ -> false }
            editor.edit(defaultLimit, "200")

            editor.save(defaultLimit, 200)

            assertEquals(ShellLimitInputError.ABOVE_MAX, error(defaultLimit))
        }

    @Test
    fun `refused max shows below default`() =
        runTest {
            answer = { _, _, _ -> false }
            editor.edit(maxLimit, "5")

            editor.save(maxLimit, 5)

            assertEquals(ShellLimitInputError.BELOW_DEFAULT, error(maxLimit))
        }

    @Test
    fun `a refusal for an outdated value leaves the newer text alone`() =
        runTest {
            answer = { _, _, _ -> false }
            editor.edit(maxLimit, "2")
            editor.edit(maxLimit, "20")

            editor.save(maxLimit, 2)

            assertEquals("20", text(maxLimit))
            assertNull(error(maxLimit))
        }

    @Test
    fun `a sibling retry does not overwrite newer text`() =
        runTest {
            val gate = CompletableDeferred<Boolean>()
            answer = { limit, _, attempt ->
                when {
                    limit == defaultLimit && attempt == 1 -> false
                    limit == defaultLimit -> gate.await()
                    else -> true
                }
            }
            editor.edit(defaultLimit, "200")
            editor.save(defaultLimit, 200)
            editor.edit(maxLimit, "300")

            launch { editor.save(maxLimit, 300) }
            runCurrent()
            editor.edit(defaultLimit, "150")
            gate.complete(true)
            runCurrent()

            assertEquals("150", text(defaultLimit))
            assertNull(error(defaultLimit))
            assertEquals(listOf(defaultLimit to 200, maxLimit to 300, defaultLimit to 200), stores)
        }

    @Test
    fun `the refused sibling is saved once the other one allows it`() =
        runTest {
            answer = { limit, _, attempt -> !(limit == defaultLimit && attempt == 1) }
            editor.edit(defaultLimit, "200")
            editor.save(defaultLimit, 200)

            assertEquals(ShellLimitInputError.ABOVE_MAX, error(defaultLimit))

            editor.edit(maxLimit, "300")
            editor.save(maxLimit, 300)

            assertEquals(listOf(defaultLimit to 200, maxLimit to 300, defaultLimit to 200), stores)
            assertNull(error(defaultLimit))
        }

    @Test
    fun `a sibling without a refusal error is not saved again`() =
        runTest {
            editor.load(ShellConfig())
            editor.edit(maxLimit, "300")

            editor.save(maxLimit, 300)

            assertEquals(listOf(maxLimit to 300), stores)
        }

    @Test
    fun `refused memory limit shows over memory cap`() =
        runTest {
            answer = { _, _, _ -> false }
            val memoryLimits =
                listOf(
                    ShellLimit.OUTPUT_BUFFER_KIB,
                    ShellLimit.MAX_RUNNING_PROCESSES,
                    ShellLimit.MAX_FINISHED_PROCESSES,
                )

            memoryLimits.forEach { limit ->
                editor.edit(limit, limit.max.toString())
                editor.save(limit, limit.max)
            }

            memoryLimits.forEach { assertEquals(ShellLimitInputError.OVER_MEMORY_CAP, error(it), it.key) }
        }

    @Test
    fun `a memory limit refused is saved once another memory limit allows it`() =
        runTest {
            answer = { limit, _, attempt -> !(limit == ShellLimit.OUTPUT_BUFFER_KIB && attempt == 1) }
            editor.load(ShellConfig())
            editor.edit(ShellLimit.OUTPUT_BUFFER_KIB, "4096")
            editor.save(ShellLimit.OUTPUT_BUFFER_KIB, 4096)

            assertEquals(ShellLimitInputError.OVER_MEMORY_CAP, error(ShellLimit.OUTPUT_BUFFER_KIB))

            editor.edit(ShellLimit.MAX_RUNNING_PROCESSES, "4")
            editor.save(ShellLimit.MAX_RUNNING_PROCESSES, 4)

            assertEquals(
                listOf(
                    ShellLimit.OUTPUT_BUFFER_KIB to 4096,
                    ShellLimit.MAX_RUNNING_PROCESSES to 4,
                    ShellLimit.OUTPUT_BUFFER_KIB to 4096,
                ),
                stores,
            )
            assertNull(error(ShellLimit.OUTPUT_BUFFER_KIB))
        }
}
