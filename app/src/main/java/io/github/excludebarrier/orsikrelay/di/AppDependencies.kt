package io.github.excludebarrier.orsikrelay.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftStore
import io.github.excludebarrier.orsikrelay.data.notification.NotificationPermissionStore
import io.github.excludebarrier.orsikrelay.data.notification.SharedPreferencesNotificationPermissionStore
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
import io.github.excludebarrier.orsikrelay.data.run.RunStatusRepository
import io.github.excludebarrier.orsikrelay.data.run.RunStopRepository
import io.github.excludebarrier.orsikrelay.data.run.RunSteerRepository
import io.github.excludebarrier.orsikrelay.data.run.SharedPreferencesRunLeaseStore
import io.github.excludebarrier.orsikrelay.data.session.SessionCreateRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionListRepository
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.data.session.resolveStartupProfile
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectCoordinator
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingCoordinator
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationCoordinator
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.network.HermesConnectionClient
import io.github.excludebarrier.orsikrelay.network.HermesDevicePairClient
import io.github.excludebarrier.orsikrelay.network.HermesDeviceSelfRevokeClient
import io.github.excludebarrier.orsikrelay.network.HermesRunApprovalClient
import io.github.excludebarrier.orsikrelay.network.HermesRunStatusClient
import io.github.excludebarrier.orsikrelay.network.HermesRunStopClient
import io.github.excludebarrier.orsikrelay.network.HermesRunSteerClient
import io.github.excludebarrier.orsikrelay.platform.attachment.AndroidChatAttachmentSource
import io.github.excludebarrier.orsikrelay.platform.notification.AndroidNotificationManager
import io.github.excludebarrier.orsikrelay.platform.notification.AppForegroundTracker
import io.github.excludebarrier.orsikrelay.platform.run.AndroidRunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore

/**
 * The single production dependency graph for the whole app: exactly one
 * instance of each repository, coordinator and store, wired by hand (no DI
 * framework). Extracted from [io.github.excludebarrier.orsikrelay.navigation.HermesNavHost]
 * so the navigation host stays navigation-only.
 *
 * One [SharedPreferencesChatDraftRepository] is shared by the chat screen and
 * the profile repository: profile deletion clears drafts
 * through this same instance, so no second, independent store exists.
 * The device metadata store holds ONLY non-secret metadata; the device key is
 * stored by the saver through the same Keystore-wrapped credential store as
 * the API key.
 */
internal data class AppDependencies(
    val profileAdmin: ProfileAdmin,
    val coordinator: ProfileConnectionCoordinator,
    // The pairing onboarding path and the "Gerät trennen"
    // disconnect path of the server management screen.
    val pairingCoordinator: DevicePairingCoordinator,
    val deviceDisconnectCoordinator: DeviceDisconnectCoordinator,
    val sessionPageSource: SessionPageSource,
    val sessionCreator: SessionCreator,
    val messageHistorySource: MessageHistorySource,
    val runController: RunSessionController,
    val runStopRequester: RunStopRequester,
    val runSteerRequester: RunSteerRequester,
    val runApprovalRequester: RunApprovalRequester,
    /** Reads `GET /v1/runs/{run_id}` for the manual uncertain-run recovery. */
    val runStatusRequester: RunStatusRequester,
    val notificationCoordinator: NotificationCoordinator,
    val notificationPermissionStore: NotificationPermissionStore,
    val capabilityStore: RunApprovalCapabilityStore,
    val chatDraftRepository: ChatDraftRepository,
    /** Reads a picked attachment's metadata/bytes through the ContentResolver. */
    val chatAttachmentSource: ChatAttachmentSource,
    val selectionStore: ActiveServerSelectionStore,
    val startupResolver: () -> StartupProfile,
)

/**
 * Process-scoped holder of the single production [AppDependencies] graph.
 *
 * The graph owns the app-level live-run state and its stream (through
 * [RunSessionController]): a chat screen that is re-created by a
 * configuration change must be able to re-attach to the still-running turn.
 * A composition-scoped `remember { lazy { … } }` built a NEW graph - and
 * therefore an empty `activeRun` - on every Activity recreation, so the
 * re-created screen lost the live-turn binding while the previous graph kept
 * the stream and the foreground service alive.
 *
 * Scope of this holder is deliberately the LIVE PROCESS only: it survives an
 * Activity or view-model recreation inside the same process and dies with the
 * process. The graph itself is not persisted; process-death recovery of a
 * confirmed run is limited to the run lease the controller writes.
 *
 * Injected test stores always bypass the cache: a caller that supplies its own
 * [selectionStore]/[capabilityStore] gets a fresh, isolated graph, exactly as
 * before.
 */
internal object AppDependenciesHolder {
    @Volatile
    private var cached: AppDependencies? = null

    fun get(
        applicationContext: Context,
        selectionStore: ActiveServerSelectionStore? = null,
        capabilityStore: RunApprovalCapabilityStore? = null,
    ): AppDependencies {
        if (selectionStore != null || capabilityStore != null) {
            return buildAppDependencies(applicationContext, selectionStore, capabilityStore)
        }
        val existing = cached
        if (existing != null) return existing
        return synchronized(this) {
            cached ?: buildAppDependencies(applicationContext, selectionStore, capabilityStore)
                .also { cached = it }
        }
    }

    /**
     * Drops the process-scoped cached graph. Test-only: without this a
     * cached production graph (including a live [io.github.excludebarrier.orsikrelay.domain.run.RunSessionController]
     * and its coroutine scope) would leak into later tests in the same process.
     */
    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(this) { cached = null }
    }
}

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
    // One app-private selection store shared by the
    // repository (select/delete) and the startup resolver. It holds
    // exactly one profile ID — no credential, URL, draft or chat data.
    val resolvedSelectionStore =
        selectionStore ?: SharedPreferencesActiveServerSelectionStore(applicationContext)
    // Per-profile boolean confirming strict validation of the complete
    // `session_chat_interactive_approvals` protocol-v1 contract.
    // It contains no credential, URL, draft, chat or approval content.
    val resolvedCapabilityStore =
        capabilityStore ?: SharedPreferencesRunApprovalCapabilityStore(applicationContext)
    // The "ask at most once" flag for the POST_NOTIFICATIONS permission.
    // Only a single boolean is stored; no credential or notification content.
    val notificationPermissionStore =
        SharedPreferencesNotificationPermissionStore(applicationContext)
    // One production ChatDraftRepository instance shared by the chat
    // screen and the profile repository: profile
    // deletion clears the drafts through this same instance, so no
    // second, independent store is created.
    val chatDraftRepository = SharedPreferencesChatDraftRepository(
        store = SharedPreferencesChatDraftStore(applicationContext)
    )
    // The device-pairing production dependencies.
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
        deviceCredentialMetadataStore = deviceMetadataStore,
        runApprovalCapabilityStore = resolvedCapabilityStore
    )
    val profileAdmin = ServerProfileAdmin(profileRepository)
    // Local notifications. The poster is the single Android adapter;
    // the coordinator applies the pure policy and the duplicate guard.
    // The server label comes from the profile metadata and is validated
    // (isSafeTitle) before it can reach a notification; the foreground
    // signal is the single-activity start/stop tracker.
    val notificationCoordinator = NotificationCoordinator(
        poster = AndroidNotificationManager(applicationContext),
        isForeground = { AppForegroundTracker.isForeground },
        serverNameProvider = { profileId ->
            profileAdmin
                .listProfiles()
                .firstOrNull { it.id == profileId }
                ?.profileName
                .orEmpty()
        },
        fallbackTitle = applicationContext.getString(R.string.notification_fallback_title)
    )
    // The app-level owner of the single active run: the streaming
    // source, the run foreground service and the run notification side
    // effects. Exactly one instance for the whole app: the run must
    // outlive any chat screen.
    val runController = RunSessionController(
        chatSource = StreamingChatRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore,
            runApprovalCapabilitySource = RunApprovalCapabilityRepository(
                capabilityStore = resolvedCapabilityStore
            )
        ),
        serviceStarter = AndroidRunForegroundServiceStarter(applicationContext),
        notificationCoordinator = notificationCoordinator,
        // Notification titles resolve profile metadata off the main thread.
        ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
        // The confirmed run survives a process death as an UNCERTAIN
        // lease; a lease of a deleted profile could never be reconciled.
        leaseStore = SharedPreferencesRunLeaseStore(applicationContext),
        isLeaseRestorable = { lease ->
            profileAdmin.listProfiles().any { it.id == lease.profileId }
        }
    )
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
        runController = runController,
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
        runStatusRequester = RunStatusRepository(
            profileRepository = profileRepository,
            credentialStore = credentialStore,
            runStatusTransport = HermesRunStatusClient()
        ),
        notificationCoordinator = notificationCoordinator,
        notificationPermissionStore = notificationPermissionStore,
        chatDraftRepository = chatDraftRepository,
        chatAttachmentSource = AndroidChatAttachmentSource(applicationContext),
        selectionStore = resolvedSelectionStore,
        capabilityStore = resolvedCapabilityStore,
        startupResolver = {
            // The startup resolution validates the
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
