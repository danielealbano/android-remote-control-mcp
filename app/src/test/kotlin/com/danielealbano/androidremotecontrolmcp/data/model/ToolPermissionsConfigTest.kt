package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ToolPermissionsConfig")
class ToolPermissionsConfigTest {
    @Test
    fun `isToolEnabled returns true for empty disabledTools`() {
        val config = ToolPermissionsConfig()
        assertTrue(config.isToolEnabled("tap"))
    }

    @Test
    fun `isToolEnabled returns false when tool is in disabledTools`() {
        val config = ToolPermissionsConfig(disabledTools = setOf("tap"))
        assertFalse(config.isToolEnabled("tap"))
    }

    @Test
    fun `isToolEnabled returns true for unknown tool names`() {
        val config = ToolPermissionsConfig(disabledTools = setOf("nonexistent_tool"))
        assertTrue(config.isToolEnabled("tap"))
    }

    @Test
    fun `isParamEnabled returns true for empty disabledParams`() {
        val config = ToolPermissionsConfig()
        assertTrue(config.isParamEnabled("get_screen_state", "include_screenshot"))
    }

    @Test
    fun `isParamEnabled returns false when param is in disabledParams`() {
        val config =
            ToolPermissionsConfig(
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )
        assertFalse(config.isParamEnabled("get_screen_state", "include_screenshot"))
    }

    @Test
    fun `isParamEnabled returns true when tool has no entry`() {
        val config =
            ToolPermissionsConfig(
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )
        assertTrue(config.isParamEnabled("save_camera_video", "audio"))
    }

    @Test
    fun `toJson with empty config produces expected JSON`() {
        val json = ToolPermissionsConfig().toJson()
        assertEquals("{\"disabledTools\":[],\"disabledParams\":{}}", json)
    }

    @Test
    fun `toJson and fromJson round-trip`() {
        val original =
            ToolPermissionsConfig(
                disabledTools = setOf("tap", "swipe"),
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )
        val json = original.toJson()
        val restored = ToolPermissionsConfig.fromJson(json)
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `fromJson with empty JSON object`() {
        val config = ToolPermissionsConfig.fromJson("{}")
        assertNotNull(config)
        assertEquals(ToolPermissionsConfig(), config)
    }

    @Test
    fun `fromJson with valid JSON`() {
        val json =
            """{"disabledTools":["tap","swipe"],"disabledParams":{"get_screen_state":["include_screenshot"]}}"""
        val config = ToolPermissionsConfig.fromJson(json)
        assertNotNull(config)
        assertEquals(setOf("tap", "swipe"), config!!.disabledTools)
        assertEquals(
            mapOf("get_screen_state" to setOf("include_screenshot")),
            config.disabledParams,
        )
    }

    @Test
    fun `fromJson with invalid JSON returns null`() {
        assertNull(ToolPermissionsConfig.fromJson("not json"))
    }

    @Test
    fun `fromJsonOrDefault with null returns default`() {
        val config = ToolPermissionsConfig.fromJsonOrDefault(null)
        assertEquals(ToolPermissionsConfig(), config)
    }

    @Test
    fun `fromJsonOrDefault with invalid JSON returns default`() {
        val config = ToolPermissionsConfig.fromJsonOrDefault("not json")
        assertEquals(ToolPermissionsConfig(), config)
    }

    @Test
    fun `fromJson with unknown extra JSON keys`() {
        val json =
            """{"disabledTools":["tap"],"disabledParams":{},"unknownKey":"value"}"""
        val config = ToolPermissionsConfig.fromJson(json)
        assertNotNull(config)
        assertEquals(setOf("tap"), config!!.disabledTools)
    }

    @Test
    fun `fromJson with partially valid JSON`() {
        val json = """{"disabledTools":["tap"],"disabledParams":"not_an_object"}"""
        val config = ToolPermissionsConfig.fromJson(json)
        assertNull(config)
    }
}
