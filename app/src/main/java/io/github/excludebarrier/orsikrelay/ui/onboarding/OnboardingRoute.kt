package io.github.excludebarrier.orsikrelay.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionOutcome
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingRoute(
    connect: (ProfileDraft) -> ProfileConnectionOutcome,
    onConnected: (ProfileConnectionOutcome.Success) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var connectionState by remember {
        mutableStateOf<OnboardingConnectionState>(OnboardingConnectionState.Idle)
    }

    OnboardingScreen(
        connectionState = connectionState,
        onInputChanged = {
            if (connectionState is OnboardingConnectionState.Failure) {
                connectionState = OnboardingConnectionState.Idle
            }
        },
        onValidDraft = { draft ->
            if (connectionState !is OnboardingConnectionState.Connecting) {
                connectionState = OnboardingConnectionState.Connecting
                coroutineScope.launch {
                    val outcome = try {
                        withContext(Dispatchers.IO) { connect(draft) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ProfileConnectionOutcome.Failure(ConnectionFailureReason.NETWORK)
                    }
                    when (outcome) {
                        is ProfileConnectionOutcome.Success -> {
                            connectionState = OnboardingConnectionState.Success(
                                version = outcome.version,
                                profileId = outcome.profileId
                            )
                            onConnected(outcome)
                        }

                        is ProfileConnectionOutcome.Failure -> {
                            connectionState = OnboardingConnectionState.Failure(
                                outcome.reason.toOnboardingFailureReason()
                            )
                        }

                        ProfileConnectionOutcome.PersistenceFailure -> {
                            connectionState = OnboardingConnectionState.Failure(
                                OnboardingFailureReason.PERSISTENCE
                            )
                        }
                    }
                }
            }
        }
    )
}
