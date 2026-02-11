package com.danielealbano.androidremotecontrolmcp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun appBarTitleIsDisplayed() {
        composeTestRule
            .onNodeWithText("MCP Remote Control")
            .assertIsDisplayed()
    }

    @Test
    fun serverStatusSectionIsDisplayed() {
        composeTestRule
            .onNodeWithText("Server Status")
            .assertIsDisplayed()
    }

    @Test
    fun serverStartButtonIsDisplayed() {
        composeTestRule
            .onNodeWithText("Start")
            .assertIsDisplayed()
    }

    @Test
    fun configurationSectionIsDisplayed() {
        composeTestRule
            .onNodeWithText("Configuration")
            .assertIsDisplayed()
    }

    @Test
    fun bindingAddressLocalhostIsDisplayed() {
        composeTestRule
            .onNodeWithText("Localhost (127.0.0.1)")
            .assertIsDisplayed()
    }

    @Test
    fun portFieldIsDisplayed() {
        composeTestRule
            .onNodeWithText("Port")
            .assertIsDisplayed()
    }

    @Test
    fun permissionsSectionIsDisplayed() {
        composeTestRule
            .onNodeWithText("Permissions")
            .assertIsDisplayed()
    }

    @Test
    fun connectionInfoSectionIsDisplayed() {
        composeTestRule
            .onNodeWithText("Connection Info")
            .assertIsDisplayed()
    }

    @Test
    fun networkBindingShowsSecurityWarningDialog() {
        composeTestRule
            .onNodeWithText("Network (0.0.0.0)")
            .performClick()

        composeTestRule
            .onNodeWithText("Network Exposure Warning")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Cancel")
            .performClick()
    }

    @Test
    fun autoStartToggleIsDisplayed() {
        composeTestRule
            .onNodeWithText("Auto-start on boot")
            .assertIsDisplayed()
    }

    @Test
    fun serverLogsSectionIsDisplayed() {
        composeTestRule
            .onNodeWithText("Server Logs")
            .assertIsDisplayed()
    }
}
