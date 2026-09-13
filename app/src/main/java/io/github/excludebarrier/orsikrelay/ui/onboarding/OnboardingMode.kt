package io.github.excludebarrier.orsikrelay.ui.onboarding

/**
 * The two onboarding connection modes (Mission 017B).
 *
 * [Pairing] is the recommended default: the user pastes a one-time device
 * pairing code and no API key. [ApiKey] is the pre-existing mode, kept
 * semantically unchanged: the user provides a static API key. The mode is
 * UI state only — it carries no credential, code or URL.
 */
enum class OnboardingMode {
    Pairing,
    ApiKey
}
