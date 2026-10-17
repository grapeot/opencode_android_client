package com.yage.opencode_client

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.opencode_client.ambient.ui.AmbientNavigationTags
import com.yage.opencode_client.ambient.ui.AmbientTags
import com.yage.opencode_client.util.SettingsManager
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone bottom navigation for Ambient against the real [MainActivity].
 * The setting starts off. The launch target is an unreachable blank server.
 * The test never taps connect, the microphone, or a session action.
 * Recreation checks the restored Ambient destination and idle connect control only.
 */
@RunWith(AndroidJUnit4::class)
class AmbientNavigationInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeScenario() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun ambientNavFollowsSettingAndRestoresRoute() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsManager(targetContext).ambientEnabled = false
        val intent = Intent(targetContext, MainActivity::class.java).apply {
            putExtra("test_server_url", "http://127.0.0.1:9")
            putExtra("test_username", "")
            putExtra("test_password", "")
        }
        scenario = ActivityScenario.launch(intent)
        try {
            waitForPhoneNav()
            composeRule.onNodeWithTag(AmbientNavigationTags.PHONE_NAV).assertDoesNotExist()

            nav(R.string.nav_settings).performClick().assertIsSelected()
            ambientSwitch().performScrollTo().assertIsOff().performClick()

            waitUntilTag(AmbientNavigationTags.PHONE_NAV, present = true)
            composeRule.onNodeWithTag(AmbientNavigationTags.PHONE_NAV)
                .assertIsDisplayed()
                .performClick()
            assertIdleAmbient()
            capturePortrait()

            scenario?.recreate()
            composeRule.waitForIdle()
            waitUntilTag(AmbientNavigationTags.ROUTE, present = true)
            assertIdleAmbient()

            nav(R.string.nav_chat).performClick().assertIsSelected()
            composeRule.onNodeWithTag(AmbientNavigationTags.ROUTE).assertDoesNotExist()
            waitUntilText(R.string.chat_connect_to_server)

            nav(R.string.nav_files).performClick().assertIsSelected()
            composeRule.onNodeWithTag(AmbientNavigationTags.ROUTE).assertDoesNotExist()
            composeRule.onNodeWithContentDescription(text(R.string.common_refresh)).assertIsDisplayed()

            nav(R.string.nav_settings).performClick().assertIsSelected()
            ambientSwitch().performScrollTo().assertIsOn()

            composeRule.onNodeWithTag(AmbientNavigationTags.PHONE_NAV).performClick()
            waitUntilTag(AmbientNavigationTags.ROUTE, present = true)
            assertIdleAmbient()

            nav(R.string.nav_settings).performClick().assertIsSelected()
            ambientSwitch().performScrollTo().assertIsOn().performClick()
            waitUntilTag(AmbientNavigationTags.PHONE_NAV, present = false)
            composeRule.onNodeWithTag(AmbientNavigationTags.PHONE_NAV).assertDoesNotExist()
            ambientSwitch().assertIsOff()
        } finally {
            turnAmbientOffIfShown()
        }
    }

    private fun assertIdleAmbient() {
        composeRule.onNodeWithTag(AmbientNavigationTags.PHONE_NAV).assertIsSelected()
        composeRule.onNodeWithTag(AmbientNavigationTags.ROUTE).assertIsDisplayed()
        composeRule.onNodeWithTag(AmbientTags.STATUS).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_disconnected)).assertIsDisplayed()
        composeRule.onNodeWithTag(AmbientTags.CONNECT)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains(text(R.string.ambient_start), substring = true)
        composeRule.onNodeWithTag(AmbientTags.MIC).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.END).assertDoesNotExist()
    }

    private fun capturePortrait() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null)
            ?: error("external files dir unavailable")
        dir.mkdirs()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            FileOutputStream(File(dir, "ambient-navigation-portrait.png")).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun turnAmbientOffIfShown() {
        try {
            if (!tagPresent(AmbientNavigationTags.PHONE_NAV)) return
            nav(R.string.nav_settings).performClick()
            composeRule.waitForIdle()
            val toggle = ambientSwitch()
            toggle.performScrollTo()
            if (toggleIsOn()) toggle.performClick()
            composeRule.waitForIdle()
        } catch (_: Throwable) {
            // Best-effort on the emulator fixture.
        }
    }

    private fun waitForPhoneNav() {
        val chat = text(R.string.nav_chat)
        try {
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodes(hasText(chat) and isSelectable())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "Phone bottom navigation did not appear. Expanded width uses TabletLayout and has no ambient-nav item.",
                timeout,
            )
        }
    }

    private fun waitUntilTag(tag: String, present: Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS) { tagPresent(tag) == present }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $tag present=$present", timeout)
        }
    }

    private fun waitUntilText(resId: Int) {
        val value = text(resId)
        try {
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for text $value", timeout)
        }
    }

    private fun tagPresent(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun toggleIsOn(): Boolean =
        try {
            ambientSwitch().assertIsOn()
            true
        } catch (_: AssertionError) {
            false
        }

    private fun nav(labelRes: Int) =
        composeRule.onNode(hasText(text(labelRes)) and isSelectable())

    private fun ambientSwitch() =
        composeRule.onNodeWithTag(AmbientNavigationTags.ENABLE, useUnmergedTree = true)

    private fun text(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
