package io.github.excludebarrier.orsikrelay.di

import android.content.Context
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftStore
import io.github.excludebarrier.orsikrelay.data.device.CredentialStoreDeviceCredentialSaver
import io.github.excludebarrier.orsikrelay.data.device.SharedPreferencesDeviceCredentialMetadataStore
import io.github.excludebarrier.orsikrelay.data.message.MessageHistoryRepository
import io.github.excludebarrier.orsikrelay.data.message.StreamingChatRepository
import io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.RunApprovalCapabilityStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileAdmin
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesRunApprovalCapabilityStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.run.RunApprovalCapabilityRepository
import io.github.excludebarrier.orsikrelay.data.run.RunApprovalRepository
import io.github.excludebarrier.orsikrelay.data.run.RunStopRepository
import io.github.excludebarrier.orsikrelay.data.run.RunSteerRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionCreateRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionListRepository
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.data.session.resolveStartupProfile
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectCoordinator
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingCoordinator
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.network.HermesConnectionClient
import io.github.excludebarrier.orsikrelay.network.HermesDevicePairClient
import io.github.excludebarrier.orsikrelay.network.HermesDeviceSelfRevokeClient
import io.github.excludebarrier.orsikrelay.network.HermesRunApprovalClient
import io.github.excludebarrier.orsikrelay.network.HermesRunStopClient
import io.github.excludebarrier.orsikrelay.network.HermesRunSteerClient
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore

/**
 * The single production dependency graph for the whole app: exactly one
 * instance of each repository, coordinator and store, wired by hand (no DI
 * framework). Extracted from [io.github.excludebarrier.orsikrelay.navigation.HermesNavHost]
 * so the navigation host stays navigation-only.
 *
 * One [SharedPreferencesChatDraftRepository] is shared by the chat screen and
 * the profile repository (Mission 009B): profile deletion clears drafts
 * through this same instance, so no second, independent store exists.
 * The device metadata store holds ONLY non-secret metadata; the device key is
 * stored by the saver through the same Keystore-wrapped credential store as
 * the API key (Mission 017B).
 */
internal data class AppDependencies(
    val profileAdmin: ProfileAdmin,
    val coordinator: ProfileConnectionCoordinator,
    // Mission 017B: the pairing onboarding path and the "Gerät trennen"
    // disconnect path of the server management screen.
    val pairingCoordinator: DevicePairingCoordinator,
    val deviceDisconnectCoordinator: DeviceDisconnectCoordinator,
    val sessionPageSource: SessionPageSource,
    val sessionCreator: SessionCreator,
    val messageHistorySource: MessageHistorySource,
    val streamingChatSource: StreamingChatSource,
    val runStopRequester: RunStopRequester,
    val runSteerRequester: RunSteerRequester,
    val runApprovalRequester: RunApprovalRequester,
    val capabilityStore: RunApprovalCapabilityStore,
    val chatDraftRepository: ChatDraftRepository,
    val selectionStore: ActiveServerSelectionStore,
    val startupResolver: () -> StartupProfile,
)

/**
 * Builds the production [AppDependencies] graph for [applicationContext].
 * [selectionStore] and [capabilityStore] may be injected (tests); when null
 * the app-private SharedPreferences-backed stores are created.
 */
internal fun buildAppDependencies(
    applicationContext: Context,
    selectionStore: ActiveServerSelectionStore? = null,
    capabilityStore: RunApprovalCapabilityStore? = null,
): AppDependencies {
    val credentialStore = AndroidKeystoreCredentialStore(applicationContext)
    val metadataStore = SharedPreferencesServerProfileMetadataStore(applicationContext)
    // Mission 012: one app-private selection store shared by the
    // repository (select/delete) and the startup resolver. It holds
    // exactly one profile ID — no credential, URL, draft or chat data.
    val resolvedSelectionStore =
        selectionStore ?: SharedPreferencesActiveServerSelectionStore(applicationContext)
    // Per-profile boolean confirming strict validation of the complete
    // `session_chat_interactive_approvals` protocol-v1 contract.
    // It contains no credential, URL, draft, chat or approval content.
    val resolvedCapabilityStore =
        capabilityStore ?: SharedPreferencesRunApprovalCapabilityStore(applicationContext)
    // One production ChatDraftRepository instance shared by the chat
    // screen and the profile repository (Mission 009B): profile
    // deletion clears the drafts through this same instance, so no
    // second, independent store is created.
    val chatDraftRepository = SharedPreferencesChatDraftRepository(
        store = SharedPreferencesChatDraftStore(applicationContext)
    )
    // Mission 017B: the device-pairing production dependencies.
    // The device metadata store holds ONLY non-secret metadata; the
    // device key is stored by the saver through the same
    // [credentialStore] (Keystore-wrapped) as the API key. The
    // pairing coordinator verifies a freshly issued key through the
    // normal authenticated probe BEFORE persistence and performs
    // exactly one best-effort self-revoke on every post-pair
    // failure — no retries, no anonymous preflight.
    val deviceMetadataStore =
        SharedPreferencesDeviceCredentialMetadataStore(applicationContext)
    val deviceCredentialSaver = CredentialStoreDeviceCredentialSaver(
        credentialStore = credentialStore,
        metadataStore = deviceMetadataStore
    )
    val devicePairTransport = HermesDevicePairClient()
    val deviceSelfRevokeTransport = HermesDeviceSelfRevokeClient()
    val profileRepository = ServerProfileRepository(
        metadataStore = metadataStore,
        credentialStore = credentialStore,
        profileDraftClearer = chatDraftRepository,
        selectionStore = resolvedSelectionStore,
        deviceCredentialSaver = deviceCredentialSaver,
        deviceCredentialMetadataStore = deviceMetadataStore
    )
    val profileAdmin = ServerProfileAdmin(profileRepository)
    return AppDependencies(
        profileAdmin = profileAdmin,
        coordinator = ProfileConnectionCoordinator(
            connectionProbe = HermesConnectionClient(),
            profileSaver = profileRepository
        ),
        pairingCoordinator = DevicePairingCoordinator(
            pairTransport = devicePairTransport,
            connectionProbe = HermesConnectionClient(),
            pairedProfileSaver = profileRepository,
            selfRevokeTransport = deviceSelfRevokeTransport
        ),
        deviceDisconnectCoordinator = DeviceDisconnectCoordinator(
            disconnectSource = profileAdmin,
            selfRevokeTransport = deviceSelfRevokeTransport,
            profileAdmin = profileAdmin
        ),
        sessionPageSource = SessionListRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore
        ),
        sessionCreator = SessionCreateRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore
        ),
        messageHistorySource = MessageHistoryRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore
        ),
        streamingChatSource = StreamingChatRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore,
            runApprovalCapabilitySource = RunApprovalCapabilityRepository(
                capabilityStore = resolvedCapabilityStore
            )
        ),
        runStopRequester = RunStopRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore,
            runStopTransport = HermesRunStopClient()
        ),
        runSteerRequester = RunSteerRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore,
            runSteerTransport = HermesRunSteerClient()
        ),
        runApprovalRequester = RunApprovalRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore,
            runApprovalTransport = HermesRunApprovalClient()
        ),
        chatDraftRepository = chatDraftRepository,
        selectionStore = resolvedSelectionStore,
        capabilityStore = resolvedCapabilityStore,
        startupResolver = {
            // Mission 012: the startup resolution validates the
            // persisted active server selection against the metadata
            // AND credential stores; every invalid selection falls
            // back to the existing deterministic resolution.
            resolveStartupProfile(
                metadataStore = metadataStore,
                credentialStore = credentialStore,
                selectionStore = resolvedSelectionStore
            )
        }
    )
}
