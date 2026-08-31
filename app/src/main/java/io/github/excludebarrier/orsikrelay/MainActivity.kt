package io.github.excludebarrier.orsikrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.navigation.initialDestination
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme

/**
 * Single entry point of the app. Hosts the Compose navigation shell;
 * all navigation logic lives in [HermesNavHost] and [io.github.excludebarrier.orsikrelay.navigation.AppNavigationState].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HermesNavHost(startDestination = initialDestination())
                }
            }
        }
    }
}
