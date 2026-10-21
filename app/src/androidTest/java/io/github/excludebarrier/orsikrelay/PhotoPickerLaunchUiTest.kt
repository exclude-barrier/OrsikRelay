package io.github.excludebarrier.orsikrelay

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.chat.rememberImagePicker
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #51: opening the photo picker crashed with IllegalArgumentException
 * from FragmentActivity.checkForValidRequestCode. The host is a real
 * FragmentActivity (MainActivity's base class, needed for BiometricPrompt)
 * and the launcher is the production [rememberImagePicker]; only the system
 * picker's answer is stubbed (espresso-intents), after the request code
 * check that crashed.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPickerLaunchUiTest {

    // The host is started by an explicit component of the app package, where
    // the debug manifest declares it.
    @get:Rule
    val composeRule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<FragmentActivity>(
            Intent().setClassName(
                InstrumentationRegistry.getInstrumentation().targetContext.packageName,
                FragmentActivity::class.java.name
            )
        ),
        activityProvider = { rule ->
            var activity: FragmentActivity? = null
            rule.scenario.onActivity { activity = it }
            checkNotNull(activity)
        }
    )

    private val picked = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        Intents.init()
        showPicker()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun pickingAnImageDeliversItsContentUri() {
        respondWith(Activity.RESULT_OK, IMAGE_A)

        openPicker()

        assertEquals(listOf(IMAGE_A), picked.toList())
    }

    @Test
    fun cancellingThePickDeliversNothingAndTheActivityStays() {
        respondWith(Activity.RESULT_CANCELED, uri = null)

        openPicker()

        assertEquals(emptyList<String>(), picked.toList())
        assertEquals(Lifecycle.State.RESUMED, composeRule.activityRule.scenario.state)
    }

    @Test
    fun repeatedPicksDeliverEachResultExactlyOnce() {
        respondWith(Activity.RESULT_OK, IMAGE_A)
        openPicker()
        respondWith(Activity.RESULT_OK, IMAGE_B)
        openPicker()
        respondWith(Activity.RESULT_CANCELED, uri = null)
        openPicker()

        assertEquals(listOf(IMAGE_A, IMAGE_B), picked.toList())
    }

    @Test
    fun thePickerStillWorksAfterTheActivityWasRecreated() {
        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent { PickerButton() }
        }
        respondWith(Activity.RESULT_OK, IMAGE_B)

        openPicker()

        assertEquals(listOf(IMAGE_B), picked.toList())
    }

    private fun showPicker() {
        composeRule.setContent { PickerButton() }
    }

    @androidx.compose.runtime.Composable
    private fun PickerButton() {
        val pickImage = rememberImagePicker { picked += it }
        Button(onClick = pickImage, modifier = Modifier.testTag(TAG_PICK)) {
            Text("pick")
        }
    }

    private fun openPicker() {
        composeRule.onNodeWithTag(TAG_PICK).performClick()
        composeRule.waitForIdle()
    }

    private fun respondWith(resultCode: Int, uri: String?) {
        val data = uri?.let { Intent().setData(Uri.parse(it)) }
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(resultCode, data))
    }

    private companion object {
        const val TAG_PICK = "photoPickerLaunch"
        const val IMAGE_A = "content://media/picker/0/test/media/1"
        const val IMAGE_B = "content://media/picker/0/test/media/2"
    }
}
