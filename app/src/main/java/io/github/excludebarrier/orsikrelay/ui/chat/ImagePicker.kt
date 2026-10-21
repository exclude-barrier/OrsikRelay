package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The Android system photo picker - no storage permission, no custom file
 * browser; the app can only ever read what the user explicitly picked.
 * [onPicked] receives the picked content:// URI (a cancelled pick delivers
 * nothing), which the view model validates through the ChatAttachmentSource
 * seam. Returns the action that opens the picker for one image.
 */
@Composable
fun rememberImagePicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    return remember(launcher) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }
}
