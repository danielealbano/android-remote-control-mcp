package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProvider
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotRedactor
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInbox
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import javax.inject.Inject

/** What the accessibility-driven tools (actions, node actions, text input, utilities) act through. */
class AccessibilityToolDeps
    @Inject
    constructor(
        val actionExecutor: ActionExecutor,
        val accessibilityServiceProvider: AccessibilityServiceProvider,
        val treeParser: AccessibilityTreeParser,
        val elementFinder: ElementFinder,
        val nodeCache: AccessibilityNodeCache,
        val typeInputController: TypeInputController,
    )

/** What the screen introspection tools capture and format with. */
class ScreenToolDeps
    @Inject
    constructor(
        val screenCaptureProvider: ScreenCaptureProvider,
        val compactTreeFormatter: CompactTreeFormatter,
        val screenshotAnnotator: ScreenshotAnnotator,
        val screenshotEncoder: ScreenshotEncoder,
        val screenStateSnapshotCache: ScreenStateSnapshotCache,
        val webViewNodeMerger: WebViewNodeMerger,
    )

/** Privacy Mode as the tools apply it to what they return and accept. */
class PrivacyToolDeps
    @Inject
    constructor(
        val privacyToolGate: PrivacyToolGate,
        val placeholderSubstitutor: PlaceholderSubstitutor,
        val screenshotRedactor: ScreenshotRedactor,
    )

/** The device capabilities behind the file, app, camera, intent and location tools. */
class DeviceToolDeps
    @Inject
    constructor(
        val storageLocationProvider: StorageLocationProvider,
        val fileOperationProvider: FileOperationProvider,
        val appManager: AppManager,
        val cameraProvider: CameraProvider,
        val intentDispatcher: IntentDispatcher,
        val locationProvider: LocationProvider,
    )

/** What reaches the device from outside (notifications, shared content) and the links the tools hand out. */
class InboxToolDeps
    @Inject
    constructor(
        val notificationProvider: NotificationProvider,
        val sharedContentInbox: SharedContentInbox,
        val ephemeralFileLinkService: EphemeralFileLinkService,
    )
