package com.danielealbano.androidremotecontrolmcp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.danielealbano.androidremotecontrolmcp.data.repository.ArcTunnelSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.ArcTunnelSettingsImpl
import com.danielealbano.androidremotecontrolmcp.data.repository.DownloadSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.DownloadSettingsImpl
import com.danielealbano.androidremotecontrolmcp.data.repository.EventChannelSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.EventChannelSettingsImpl
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.geo.DbIpGeoResolver
import com.danielealbano.androidremotecontrolmcp.geo.GeoIpResolver
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.AuthorizationCodeStore
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.AuthorizationCodeStoreImpl
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.JwtTokenService
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.JwtTokenServiceImpl
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinatorImpl
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyPipeline
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyPipelineImpl
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelStore
import com.danielealbano.androidremotecontrolmcp.privacy.ner.OrtPiiModelRunner
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PiiModelInference
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutorImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputControllerImpl
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManagerImpl
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProvider
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcherImpl
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcherImpl
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ApiLevelProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.DefaultApiLevelProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkServiceImpl
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInbox
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInboxImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.DownloadHttpClient
import com.danielealbano.androidremotecontrolmcp.services.storage.DownloadHttpClientImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.MediaStoreFileOperations
import com.danielealbano.androidremotecontrolmcp.services.storage.MediaStoreFileOperationsImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.PermissionChecker
import com.danielealbano.androidremotecontrolmcp.services.storage.PermissionCheckerImpl
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProviderImpl
import com.danielealbano.androidremotecontrolmcp.services.tunnel.AndroidCloudflareBinaryResolver
import com.danielealbano.androidremotecontrolmcp.services.tunnel.CloudflaredBinaryResolver
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.AndroidArcTunnelKeyStore
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlerts
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlertsImpl
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentImpl
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelKeyStore
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewals
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalsImpl
import com.danielealbano.androidremotecontrolmcp.services.update.AppVersionProvider
import com.danielealbano.androidremotecontrolmcp.services.update.BuildConfigAppVersionProvider
import com.danielealbano.androidremotecontrolmcp.services.update.GithubReleaseChecker
import com.danielealbano.androidremotecontrolmcp.services.update.GithubReleaseCheckerImpl
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateNotifier
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateNotifierImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the dedicated OAuth-clients Preferences DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OAuthClientsDataStore

/** Extension property for creating the Preferences DataStore on [Context]. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

/** Extension property for the dedicated OAuth-clients Preferences DataStore on [Context]. */
private val Context.oauthClientsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "oauth_clients",
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * Provides the application-scoped [DataStore] for settings persistence.
     */
    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    /**
     * Provides the dedicated application-scoped [DataStore] for the OAuth client registry.
     */
    @Provides
    @Singleton
    @OAuthClientsDataStore
    fun provideOAuthClientsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.oauthClientsDataStore

    /**
     * Provides [Dispatchers.IO] for background work.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides [Dispatchers.Default] for CPU-bound work (on-device NER inference).
     */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides the Privacy Mode model store rooted at the app's files directory.
     */
    @Provides
    @Singleton
    fun providePrivacyModelStore(
        @ApplicationContext context: Context,
    ): PrivacyModelStore = PrivacyModelStore(context.filesDir)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    /**
     * Binds [SettingsRepositoryImpl] as the implementation of [SettingsRepository].
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** Binds the event-channel settings slice that [SettingsRepositoryImpl] delegates to. */
    @Binds
    @Singleton
    abstract fun bindEventChannelSettings(impl: EventChannelSettingsImpl): EventChannelSettings

    /** Binds the free E2E tunnel settings slice delegated to by [SettingsRepositoryImpl]. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelSettings(impl: ArcTunnelSettingsImpl): ArcTunnelSettings

    @Binds
    @Singleton
    abstract fun bindDownloadSettings(impl: DownloadSettingsImpl): DownloadSettings

    /** Binds the persisted OAuth client registry. */
    @Binds
    @Singleton
    abstract fun bindOAuthClientRepository(impl: OAuthClientRepositoryImpl): OAuthClientRepository

    /** Binds the offline IP-geolocation resolver used to annotate connection requests. */
    @Binds
    @Singleton
    abstract fun bindGeoIpResolver(impl: DbIpGeoResolver): GeoIpResolver

    /** Binds the disk-backed server log used by the in-app logs viewer. */
    @Binds
    @Singleton
    abstract fun bindServerLogRepository(impl: ServerLogRepositoryImpl): ServerLogRepository
}

@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {
    @Binds
    @Singleton
    abstract fun bindAccessibilityNodeCache(impl: AccessibilityNodeCacheImpl): AccessibilityNodeCache

    @Binds
    @Singleton
    abstract fun bindScreenStateSnapshotCache(impl: ScreenStateSnapshotCacheImpl): ScreenStateSnapshotCache

    @Binds
    @Singleton
    abstract fun bindApiLevelProvider(impl: DefaultApiLevelProvider): ApiLevelProvider

    @Binds
    @Singleton
    abstract fun bindTypeInputController(impl: TypeInputControllerImpl): TypeInputController

    @Binds
    @Singleton
    abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor

    @Binds
    @Singleton
    abstract fun bindAccessibilityServiceProvider(impl: AccessibilityServiceProviderImpl): AccessibilityServiceProvider

    @Binds
    @Singleton
    abstract fun bindScreenCaptureProvider(impl: ScreenCaptureProviderImpl): ScreenCaptureProvider

    @Binds
    abstract fun bindCloudflareBinaryResolver(impl: AndroidCloudflareBinaryResolver): CloudflaredBinaryResolver

    @Binds
    @Singleton
    abstract fun bindStorageLocationProvider(impl: StorageLocationProviderImpl): StorageLocationProvider

    @Binds
    @Singleton
    abstract fun bindFileOperationProvider(impl: FileOperationProviderImpl): FileOperationProvider

    @Binds
    @Singleton
    abstract fun bindMediaStoreFileOperations(impl: MediaStoreFileOperationsImpl): MediaStoreFileOperations

    @Binds
    @Singleton
    abstract fun bindDownloadHttpClient(impl: DownloadHttpClientImpl): DownloadHttpClient

    @Binds
    @Singleton
    abstract fun bindAppManager(impl: AppManagerImpl): AppManager

    @Binds
    @Singleton
    abstract fun bindCameraProvider(impl: CameraProviderImpl): CameraProvider

    @Binds
    @Singleton
    abstract fun bindIntentDispatcher(impl: IntentDispatcherImpl): IntentDispatcher

    @Binds
    @Singleton
    abstract fun bindNotificationProvider(impl: NotificationProviderImpl): NotificationProvider

    @Binds
    @Singleton
    abstract fun bindPermissionChecker(impl: PermissionCheckerImpl): PermissionChecker

    // LocationProvider is bound per-flavor (Fused in gms / LocationManager in foss) — see
    // GmsLocationModule / FossLocationModule.

    @Binds
    @Singleton
    abstract fun bindEventDispatcher(impl: EventDispatcherImpl): EventDispatcher

    // GeofenceManager and GeofenceChannelController are bound per-flavor — see GmsGeofenceModule
    // (gms) / FossGeofenceModule (foss). foss has no GeofenceManager (geofencing excluded).

    @Binds
    @Singleton
    abstract fun bindEphemeralFileLinkService(impl: EphemeralFileLinkServiceImpl): EphemeralFileLinkService

    @Binds
    @Singleton
    abstract fun bindSharedContentInbox(impl: SharedContentInboxImpl): SharedContentInbox

    @Binds
    @Singleton
    abstract fun bindJwtTokenService(impl: JwtTokenServiceImpl): JwtTokenService

    @Binds
    @Singleton
    abstract fun bindAuthorizationCodeStore(impl: AuthorizationCodeStoreImpl): AuthorizationCodeStore

    @Binds
    @Singleton
    abstract fun bindOAuthApprovalCoordinator(impl: OAuthApprovalCoordinatorImpl): OAuthApprovalCoordinator

    @Binds
    @Singleton
    abstract fun bindPrivacyPipeline(impl: PrivacyPipelineImpl): PrivacyPipeline

    @Binds
    @Singleton
    abstract fun bindPiiModelInference(impl: OrtPiiModelRunner): PiiModelInference

    @Binds
    @Singleton
    abstract fun bindGithubReleaseChecker(impl: GithubReleaseCheckerImpl): GithubReleaseChecker

    @Binds
    @Singleton
    abstract fun bindAppVersionProvider(impl: BuildConfigAppVersionProvider): AppVersionProvider

    @Binds
    @Singleton
    abstract fun bindUpdateNotifier(impl: UpdateNotifierImpl): UpdateNotifier

    /** Binds the AndroidKeyStore-backed key store of the free E2E tunnel client. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelKeyStore(impl: AndroidArcTunnelKeyStore): ArcTunnelKeyStore

    /** Binds the free E2E tunnel enrollment service. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelEnrollment(impl: ArcTunnelEnrollmentImpl): ArcTunnelEnrollment

    /** Binds the certificate renewals of the free E2E tunnel. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelRenewals(impl: ArcTunnelRenewalsImpl): ArcTunnelRenewals

    /** Binds the Certificate Transparency alert state of the free E2E tunnel. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelCtAlerts(impl: ArcTunnelCtAlertsImpl): ArcTunnelCtAlerts
}
