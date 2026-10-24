package com.danielealbano.androidremotecontrolmcp.e2e

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [AndroidContainerSetup.kernelPrerequisitesSatisfied], the decision that lets the redroid setup skip the
 * sudo `modprobe` path. Pure logic: no container is started.
 */
class KernelPrerequisitesTest {
    private val bothModulesLoaded =
        "binder_linux 217088 0 - Live 0x0000000000000000\nfuse 176128 3 - Live 0x0000000000000000\n"
    private val onlyBinderModuleLoaded = "binder_linux 217088 0 - Live 0x0000000000000000\n"
    private val onlyFuseModuleLoaded = "fuse 176128 3 - Live 0x0000000000000000\n"

    @Test
    fun `fuse module and mounted binderfs are satisfied`() {
        assertTrue(
            AndroidContainerSetup.kernelPrerequisitesSatisfied(
                procModules = onlyFuseModuleLoaded,
                devFuseExists = false,
                binderfsMounted = true,
            ),
        )
    }

    @Test
    fun `built-in fuse and mounted binderfs with no modules listed are satisfied`() {
        assertTrue(
            AndroidContainerSetup.kernelPrerequisitesSatisfied(
                procModules = "",
                devFuseExists = true,
                binderfsMounted = true,
            ),
        )
    }

    @Test
    fun `loaded binder module without a mounted binderfs is not satisfied`() {
        assertFalse(
            AndroidContainerSetup.kernelPrerequisitesSatisfied(
                procModules = bothModulesLoaded,
                devFuseExists = true,
                binderfsMounted = false,
            ),
        )
    }

    @Test
    fun `missing fuse is not satisfied`() {
        assertFalse(
            AndroidContainerSetup.kernelPrerequisitesSatisfied(
                procModules = onlyBinderModuleLoaded,
                devFuseExists = false,
                binderfsMounted = true,
            ),
        )
    }

    @Test
    fun `nothing available is not satisfied`() {
        assertFalse(
            AndroidContainerSetup.kernelPrerequisitesSatisfied(
                procModules = "",
                devFuseExists = false,
                binderfsMounted = false,
            ),
        )
    }
}
