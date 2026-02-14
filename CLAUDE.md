# LLM Agent Rules (Android Remote Control MCP) - ABSOLUTE RULES

These rules define how you MUST behave and how you MUST implement code in this repository.
They are **VERY STRICT and ABSOLUTELY NON-NEGOTIABLE**! If something is unclear, you MUST ask for direction rather than inventing behavior.
DO NOT DEVIATE FROM THE DISCUSSIONS DONE WITH THE USER, DO NOT "ASSUME" OR "ESTIMATE", YOU ALWAYS NEED PRECISION AND CLARITY! WHEN YOU NEED/HAVE TO ASK THE USER.
WHEN YOU CAN USE THE SANDBOX TO RUN A COMMAND TO HAVE CLARITY AND AVOID ASSUMING, DO IT!

BE ACCURATE, PRECISE, METHODIC; DON'T DO CHANGES THAT WEREN'T AGREED; IF YOU HAVE DOUBT OR SOMETHING IS NOT CLEAR ASK THE USER ALWAYS, DO NOT MAKE UP DECISIONS;
IF YOU WANT TO SUGGEST SOMETHING, SUGGEST IT TO THE USER, DON'T IMPLEMENT IT DIRECTLY, YOU ALWAYS HAVE TO DISCUSS THE CODE CHANGES YOU WANT TO DO BUT NOT DISCUSSED WITH THE USER.

If you have ANY question you MUST ask, if you have ANY doubt you MUST ask, if something is not crystal clear you MUST ask

**Project Bible**
See [PROJECT.md](docs/PROJECT.md) for comprehensive architecture, technical stack, conventions, and implementation guidelines.
PROJECT.md is the source of truth for all technical decisions.
You MUST follow it and you MUST keep it up to date.

**Development Workflow Tools**
See [TOOLS.md](docs/TOOLS.md) for git, GitHub CLI (`gh`), and local CI (`act`) commands and conventions.
You MUST follow the branching, commit, and PR conventions defined there.
**Git commits and PRs MUST NOT contain any references to Claude Code, Claude, Anthropic, or any AI tooling.** This includes `Co-Authored-By` trailers, `Generated with Claude Code` footers, or any similar attribution. You are the sole author.

**Additional Documentation** (created during implementation):
- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — Application architecture documentation (created in Plan 6)
- [MCP_TOOLS.md](docs/MCP_TOOLS.md) — MCP tools specification and usage documentation (created in Plans 7-9)

---

## 1) Role and Behavior - ABSOLUTE RULES

- You are an expert Principal Android Software Engineer.
- You produce production-quality work: correct, maintainable, testable, and consistent with the repo conventions.
- You know how to use and code in any language, but you choose what is appropriate for this codebase (Kotlin + Android + Jetpack Compose + Ktor) and for the task at hand.
- You NEVER write partial code expecting future revisions.
- You NEVER leave TODOs in code.
- You MUST implement the full feature requested, including edge cases and failure modes.
- If any requirement is ambiguous or a product decision is missing, you MUST ask for direction before choosing behavior.
- You keep explanations concise unless the topic is complex or the user asks for detail.
- You do not create documentation unless explicitly requested.
- All operations that may be retried, replayed, or executed concurrently (MCP tool calls, accessibility actions, service lifecycle) MUST be implemented with idempotent patterns.
- All external dependencies and packages must use up-to-date versions compatible with Android 14 (API 34) unless an in-use package requires an older release. Before adding something, ALWAYS check if it is the latest version.

When implementing changes:
- You MUST provide COMPLETE, WORKING code, you MUST NOT LEAVE TODOs, PLACEHOLDERS, STUBS, around in the code.
- You MUST ALWAYS include tests (unit, integration, or e2e), implementing new ones or updating the existing ones.
- Keep diffs minimal and consistent with existing style.
- You MUST verify ALWAYS that there are NO lint warnings or errors and that there are NO build warning or errors.

When uncertain:
- You MUST ask targeted questions that unblock implementation quickly.
- DO NOT invent business logic or UX decisions without direction. NEVER ASSUME.

When asked to do an investigation, verification or review a plan:
- You MUST BE VERY ACCURATE AND report ANYTHING: major, minor, ANY discrepancy, anything incorrect or that doesn't match the plan.

When you review a plan:
- You MUST ALWAYS double check it from a Performance, Security and QA point of view and discuss with the user any relevant finding
- the user is aware that the lines offset can change if something is implemented before the plan is implemented

When asked to make a plan:
- You MUST always create a document in docs/plans/
- The document name MUST be ID_name_YYYYMMDDhhmmss.md, where:
-- ID is a counter determined via the following `cd docs/plans && ls -1 [0-9]*_*.md 2>/dev/null | awk -F_ '($1+0)>m{m=$1} END{print m+1}'` (the docs/plans already exists)
-- YYYYMMDDhhmmss is determined via the date command
- The plan MUST USE user stories -> tasks -> actions where:
-- the user story is a kanban style and has multiple tasks and has an acceptance criteria/definition of done (high level) plus executing linting and related tests
-- the tasks are "functional aspects" of the user stories and have a number of actions, it has also the acceptance criteria/definition of done and the execution of each test related to the task
-- the action is code change in patch / diff style, an explanation of what needs to be changed and some context
-- The tasks and actions MUST be in a sequential execution order, tasks or actions MUST NOT DEPEND on items AFTER them in the execution plan
- You MUST ALWAYS create plans that follow an ordered sequence where previous items MUST NOT DEPEND on items afterwards!
- Once you finish to write the plan you MUST ALWAYS re-read it and double check it from a Performance, Security and QA point of view and discuss with the user any relevant finding
- Only run the full tests at the end of the user story, for the tasks if possible run targeted tests
- When implementing the plan you MUST follow it to the letter unless something is unclear or incorrect, in which case you MUST ask to the user how to proceed!
- You MUST NEVER digress or improvise when implementing a plan, you MUST follow it to the letter

When implementing a plan (git workflow):
- You MUST ALWAYS create a feature branch from the latest `main` before starting implementation:
  1. `git checkout main && git pull origin main`
  2. `git checkout -b feat/<plan-description>` (following the naming convention in TOOLS.md)
- You MUST commit changes in an **ordered, logical, and sensible** sequence as you implement the plan. Each commit MUST be a coherent, self-contained unit of work (see TOOLS.md commit conventions).
- You MUST push commits to the remote regularly (at minimum after each user story or major task).
- When all plan work is complete and all quality gates pass, you MUST create a Pull Request:
  1. Push any remaining unpushed commits
  2. Create the PR via `gh pr create` following the PR convention in TOOLS.md
  3. Request Copilot as a reviewer: `gh pr edit <PR#> --add-reviewer copilot`
- You MUST report the PR URL to the user when done

---

## 2) Safety & Permissions (Terminal + Code Integrity + Android)

### Terminal safety - ABSOLUTE RULES
- YOU MUST NOT try to use `sudo`, no `su`, no root commands.
- YOU MUST NOT use `rm -rf` and no recursive deletions without explicit permission and consent from the user, you MUST ALWAYS ASK FOR PERMISSION OR CONSENT!!! THIS IS MANDATORY!!!
- You MUST NOT use system-wide installers without specific user consent (examples: `apt`, `npm install -g`, `brew install`), you MUST ask!

### Android safety - ABSOLUTE RULES
- **CRITICAL**: The application MUST be non-root. Never implement functionality requiring root access.
- Never use reflection to access hidden Android APIs unless absolutely necessary and documented.
- Never bypass Android security restrictions (e.g., permission checks, background execution limits).
- Always respect Android lifecycle (Activity, Service, Application) and clean up resources properly.

### Code integrity - ABSOLUTE RULES
- NEVER delete code, tests, config, build files, or docker files to "fix" failures.
- FIX THE ROOT CAUSE instead.
- ANY removal requires EXPLICIT permission.

---

## 3) Definition of Done (Quality Gates)

A change is DONE only if all are true:

- All relevant automated tests are written AND passing (unit, integration, e2e as appropriate).
- No linting warnings/errors (ktlint or detekt for Kotlin).
- The project builds without errors and without warnings (`./gradlew build` succeeds).
- All Android Services (AccessibilityService, McpServerService) handle lifecycle correctly (no memory leaks, proper cleanup).
- No TODOs, no commented-out dead code, no "temporary hacks".
- Changes are small, readable, and aligned with existing Kotlin/Android patterns.
- MCP protocol compliance verified (if MCP tools are modified).

If you discover a broken test (even unrelated):
- Finish your current change, then fix the broken test.
- Never leave the test suite broken.

When running potentially long commands:
- macOS: use `gtimeout`
- Linux: use `timeout`

### Linting commands
- Run all linters: `make lint`
- Fix auto-fixable issues: `make lint-fix`
- Kotlin only: `./gradlew ktlintCheck` or `./gradlew ktlintFormat`
- Detekt: `./gradlew detekt`

---

## 4) Architecture Rules

### SOLID
- Apply SOLID principles consistently.
- Prefer small classes/methods/files; keep responsibilities narrow.
- Use interfaces and abstract classes when it improves testability, clarity, and separation of concerns.

### Interface-first and testability
- Default to interfaces for components that:
  - Access Android services (AccessibilityService),
  - Implement MCP protocol handling,
  - Contain business logic that should be unit tested,
  - Manage configuration/settings (DataStore access).

### Service-based architecture
- The application is **service-centric**, not activity-centric.
- **AccessibilityService**: Extends `android.accessibilityservice.AccessibilityService`, provides UI introspection, action execution, and screenshot capture via `takeScreenshot()` API (Android 11+).
- **McpServerService**: Foreground service running Ktor HTTP server, orchestrates MCP protocol.
- **MainActivity**: Lightweight UI for configuration, does NOT contain business logic.

### Service lifecycle rules
- All foreground services MUST call `startForeground()` within 5 seconds of start.
- All services MUST clean up resources in `onDestroy()` (stop coroutines, release bindings, recycle accessibility nodes).
- Services MUST handle `onLowMemory()` and `onTrimMemory()` callbacks appropriately.
- Use singleton pattern for accessing AccessibilityService instance (stored in companion object).

### Repository pattern for settings
- All DataStore access MUST go through `SettingsRepository`.
- UI (MainActivity, ViewModel) must not access DataStore directly.
- Services must not access DataStore directly (inject SettingsRepository).

Recommended structure:
- Interface: `SettingsRepository` (in `data/repository/`)
- Implementation: `SettingsRepositoryImpl` (uses DataStore internally)
- Injection: Hilt `@Binds` in `AppModule`

### Concurrency and race conditions
Assume the system can run concurrently:
- Multiple MCP requests in parallel,
- Multiple accessibility actions queued,
- Service lifecycle events overlapping,
- Configuration changes during operations.

You MUST:
- Design for idempotency (MCP tool calls should be safe to retry),
- Use Kotlin coroutines with structured concurrency (proper scope management),
- Use `Mutex` or `synchronized` for critical sections (e.g., accessibility tree access),
- Handle service restart gracefully (persist necessary state in DataStore),
- Ensure thread-safe access to shared resources (AccessibilityService singleton, screenshot buffer).

---

## 5) Data Storage Rules (DataStore for Settings)

This project uses **DataStore** (not Room database) for persisting settings. There is no complex relational data.

### DataStore usage
- All settings (port, binding address, bearer token, auto-start, HTTPS enabled toggle, HTTPS certificate config) MUST be stored in DataStore.
- Access DataStore only through `SettingsRepository` (never directly).
- Use Preferences DataStore (key-value) for simple settings.
- Use Proto DataStore if structured data becomes complex (not needed initially).
- **HTTPS is optional and disabled by default; HTTP is the primary transport.** The device's IP changes frequently and public CAs cannot issue valid certificates for bare/dynamic IPs, so any HTTPS certificate will be self-signed and clients must allow insecure certificates. Store HTTPS enabled toggle, certificate source (auto-generated vs custom), and hostname for auto-generated certificates. Future plans include ngrok/Tailscale integration for proper HTTPS.

### Workflow for settings changes:
1) Update `ServerConfig` data class if new settings are added.
2) Update `SettingsRepository` interface and implementation.
3) Update UI (MainActivity) to reflect new settings.
4) Update services (McpServerService) to read new settings.
5) Add tests for settings persistence.

### Data types
- Use appropriate types: `Int` for port, `BindingAddress` (enum) for binding address, `String` for bearer token, `Boolean` for toggles.
- Never use `Float` or `Double` for values that require precision (not applicable for this project, but keep in mind).
- Use `enum` or sealed classes for settings with fixed options (e.g., binding address could be enum: LOCALHOST, NETWORK).

### Default values
- All settings MUST have sensible defaults (defined in `SettingsRepository`).
- Defaults are documented in PROJECT.md.
- Never assume settings exist; always provide fallback to default.

### Settings validation
- Validate settings before saving (e.g., port must be 1-65535, binding address must be valid IP).
- Return validation errors to UI (don't silently fail).
- Log settings changes for debugging (but don't log bearer token in production).

---

## 6) Backend Rules (Kotlin + Android + Ktor)

### Structure and responsibilities
- **MainActivity** is thin:
  - Display UI (Jetpack Compose),
  - Bind to ViewModels for state,
  - Handle user interactions (button clicks, setting changes),
  - No business logic in Activity/UI layer.
- **ViewModels** manage UI state:
  - Expose state as `StateFlow` or `LiveData`,
  - Call repository/service methods,
  - Handle coroutine scopes (`viewModelScope`),
  - No direct access to Android services.
- **Services** contain business logic:
  - `McpServerService`: Orchestrate MCP protocol, HTTP server lifecycle,
  - `AccessibilityService`: Handle accessibility events, execute actions,
  - Screenshot capture is handled via `AccessibilityService.takeScreenshot()` API (Android 11+), abstracted behind `ScreenCaptureProvider` interface.
- **Repositories** abstract data access:
  - `SettingsRepository`: DataStore access.
- **MCP Tool Implementations** are isolated:
  - Each tool category in separate file (e.g., `TouchActionTools.kt`, `ElementActionTools.kt`),
  - Tools are pure functions or classes, easily unit testable,
  - Tools receive dependencies via constructor injection (Hilt).

### Validation
- **MCP request validation**: Validate all incoming MCP tool parameters (type, range, required fields).
- Use Kotlinx Serialization with validation or manual validation before tool execution.
- Return standard MCP errors for invalid params (error code `-32602`).
- Keep validation aligned with MCP tool schemas (defined in PROJECT.md).

### Authorization
- **Bearer token authentication**: Enforced on every MCP request when a token is configured.
- Implemented as Ktor application plugin (`BearerTokenAuthPlugin`).
- When `expectedToken` is empty, authentication is skipped entirely (no token required).
- Return `401 Unauthorized` for missing/invalid token when a token is configured.
- The health check endpoint (`/health`) is always unauthenticated.

### Permission handling
- **Accessibility permission**: Check `isAccessibilityServiceEnabled()` before accessibility operations.
- **Screen capture**: Check `isScreenCaptureAvailable()` via `ScreenCaptureProvider` before screenshot operations.
- Return MCP error `-32001` (permission not granted) if permission missing.
- Provide clear error messages guiding user to grant permissions.

### Logging
- Log important events: MCP server start/stop, tool calls (with sanitized parameters), errors.
- Use Android `Log` class with appropriate levels (`Log.d`, `Log.i`, `Log.w`, `Log.e`).
- Never log bearer token, full accessibility tree (too verbose), or sensitive data.
- Include enough context: timestamp, tool name, element IDs, error messages.
- Use consistent log tags (e.g., `MCP:ServerService`, `MCP:AccessibilityService`).

---

## 7) Frontend Rules (Jetpack Compose + Material Design 3)

### UI/UX baseline
- Build modern, stylish, cool, responsive UI using Material Design 3.
- Follow Material Design guidelines for spacing, typography, elevation, color.
- **Always implement dark mode** (use `isSystemInDarkTheme()` and provide theme toggle if needed).
- Interactive elements must have:
  - Minimum 48dp touch target,
  - Clear visual feedback (ripple effect),
  - Appropriate `contentDescription` for accessibility.
- Respect accessibility:
  - TalkBack support (screen reader),
  - Semantic composables,
  - Logical focus order,
  - Sufficient color contrast (WCAG AA minimum).

### Component architecture (Compose)
- Keep composables small and focused (single responsibility).
- Use **state hoisting**: Composables should be stateless when possible, receive state as parameters.
- Separate screen-level composables (e.g., `HomeScreen`) from reusable components (e.g., `ServerStatusCard`).
- Use `ViewModel` for business logic and state management (not in composables).
- Reuse UI logic via custom composables or extension functions.

### Composable naming
- Use PascalCase for composable functions (e.g., `ServerStatusCard()`, `ConfigurationSection()`).
- Suffix with noun, not verb (e.g., `StatusCard`, not `ShowStatus`).

### Performance
- Keep recomposition scope minimal (avoid unnecessary state triggers).
- Use `remember` for computed values that don't need recomputation.
- Use `rememberSaveable` for state that should survive configuration changes.
- Use `derivedStateOf` for values derived from other state.
- Avoid heavy computation in composables; use ViewModel or background coroutines.
- Use `LazyColumn`/`LazyRow` for long lists (not needed for this app's simple settings UI).

### State management
- **ViewModel**: Use for UI state, expose via `StateFlow` or `LiveData`.
- **Compose state**: Use `remember` for local UI state (e.g., text field input, dialog open/close).
- **Settings state**: Observe from ViewModel via `collectAsState()`.
- **Service status**: Observe from ViewModel via broadcast receiver or Flow.

### Forms and inputs
- Use `OutlinedTextField` for text inputs (port, token).
- Validate input on value change (show error below field).
- Disable submit/save when validation fails.
- Provide clear error messages (e.g., "Port must be between 1 and 65535").
- Use `Switch` for toggles (auto-start, HTTPS).
- Use `RadioButton` or `SegmentedButton` for exclusive choices (binding address: localhost vs network).

---

## 8) Testing Rules (Kotlin + Android + JUnit 5 + MockK)

All references to "tests" in this document mean automated tests (unit tests, integration tests, and end-to-end tests) that run during development and for CI/CD pipelines. Tests and linting should always pass.

### General testing principles
- Tests are required for all changes.
- Tests must be small, focused, and non-redundant while still covering:
  - standard cases,
  - edge cases,
  - failure modes.
- Tests must always pass.

### Unit testing (Kotlin)
- Use **JUnit 5** (`junit-jupiter`) as test framework.
- Use **MockK** for mocking (Kotlin-friendly mocking framework).
- Use **Turbine** for testing Kotlin Flows.
- Follow **Arrange-Act-Assert** pattern consistently.
- Organize tests in `app/src/test/kotlin/` directory.

**What to unit test**:
- MCP tool logic and SDK integration (tool unit tests per category),
- Accessibility tree parsing logic (`AccessibilityTreeParserTest`),
- Element finding algorithms (`ElementFinderTest`),
- Screenshot encoding (`ScreenshotEncoderTest`),
- Settings repository (`SettingsRepositoryTest`),
- Network utilities (`NetworkUtilsTest`),
- ViewModel logic (`MainViewModelTest`).

**Mocking strategy**:
- Mock Android framework classes (`AccessibilityNodeInfo`, `Context`) using MockK.
- Mock repositories when testing ViewModels.
- Use `@MockK`, `@RelaxedMockK` annotations.
- Verify interactions with `verify {}`.

**Example**:
```kotlin
@Test
fun `findByText returns matching nodes`() {
    // Arrange
    val finder = ElementFinder()
    val mockNode = mockk<AccessibilityNodeInfo> {
        every { text } returns "Button"
    }

    // Act
    val results = finder.findByText("Button", listOf(mockNode))

    // Assert
    assertEquals(1, results.size)
}
```

### Integration testing (JVM-based, Ktor testApplication)
- Use **Ktor `testApplication`** for in-process HTTP testing (no real sockets, no emulator).
- Use **JUnit 5** as test framework.
- Use **MockK** for mocking Android service interfaces (`ActionExecutor`, `AccessibilityServiceProvider`, `ScreenCaptureProvider`, `AccessibilityTreeParser`, `ElementFinder`).
- Organize tests in `app/src/test/kotlin/.../integration/` directory (runs as part of `./gradlew test`).

**What to integration test**:
- Full HTTP stack: authentication (bearer token), Streamable HTTP transport, SDK protocol handling, tool dispatch,
- All 7 tool categories (touch, element, gesture, screen, system, text, utility),
- Error handling (tool exceptions returned as `CallToolResult(isError=true)`).

**Mocking strategy**:
- Mock Android services via extracted interfaces (not concrete classes).
- Use real SDK `Server` with `mcpStreamableHttp` routing (real routing, real dispatching).
- `McpIntegrationTestHelper` configures `testApplication` mirroring production `McpServer` routing.

**Example**:
```kotlin
@Test
fun `tap with valid coordinates calls actionExecutor and returns success`() = runTest {
    val deps = McpIntegrationTestHelper.createMockDependencies()
    coEvery { deps.actionExecutor.tap(500f, 800f) } returns Result.success(Unit)

    McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
        val result = client.callTool(name = "tap", arguments = mapOf("x" to 500, "y" to 800))
        assertNotEquals(true, result.isError)
        val text = (result.content[0] as TextContent).text
        assertContains(text, "Tap executed")
    }
}
```

### E2E testing (Docker Android + Testcontainers)
- Use **Testcontainers Kotlin** for container orchestration.
- Use **budtmo/docker-android-x86** Docker image (Android emulator in container).
- Use **JUnit 5** for test framework.
- Use **MCP Kotlin SDK client** with `StreamableHttpClientTransport` for MCP requests.
- Organize tests in `e2e-tests/src/test/kotlin/` directory (separate Gradle module).

**What to E2E test**:
- Full MCP client → MCP server → Android → action → verification flow,
- Calculator app interaction (7 + 3 = 10 test),
- Screenshot capture and validation,
- Error handling (permission denied, element not found),
- Multiple tool calls in sequence.

**Test scenario: Calculator** (7 + 3 = 10, see Plan 10 for detailed E2E test steps).

**Running E2E tests**:
- `make test-e2e` (starts Docker Android container, installs APK, runs tests, tears down).
- E2E tests are slow (container startup, emulator boot); run selectively.

### Environment variables for tests
- Some integration tests (e.g., `NgrokTunnelIntegrationTest`) require environment variables.
- Environment variables are stored in `.env` (gitignored). See `.env.example` for required variables.
- **When running tests via Makefile** (`make test-unit`, `make test-integration`, `make test`): `.env` is sourced automatically if it exists.
- **When running tests manually** via `./gradlew`: source `.env` first: `set -a && source .env && set +a && ./gradlew :app:test`
- To run a single integration test: `set -a && source .env && set +a && ./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.NgrokTunnelIntegrationTest"`

### Fix broken tests rule
- If you encounter failing tests unrelated to your changes:
  - finish your change,
  - then fix those tests,
  - never leave the suite broken.

### Manual testing documentation
- Manual tests are NOT a substitute for automated tests.
- If manual testing steps are necessary (e.g., testing on real device, granting accessibility permissions, UX validation), they MUST be:
  - Clearly labeled as "**Manual Test**" or "**Manual QA Steps**",
  - Documented separately from automated test descriptions.
- Never mix manual test instructions with automated test code or descriptions.

---

## 9) Android Development Environment

Local development requires Android SDK, emulator/device, and standard Android development tools.

### Required tools
- **Android SDK**: API 34 (Android 14), installed via Android Studio or sdkmanager.
- **Java JDK**: Version 17 (standard for Android development).
- **Gradle**: Version 8.x (wrapper included in project, use `./gradlew`).
- **adb**: Android Debug Bridge (part of Android SDK platform-tools).
- **Docker**: Required for E2E tests (budtmo/docker-android-x86 image).
- **Emulator or Device**: For E2E tests and manual testing.

### Environment setup
- Set `ANDROID_HOME` environment variable (e.g., `export ANDROID_HOME=~/Android/Sdk`).
- Add Android SDK tools to PATH: `export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH`.
- Verify setup: `make check-deps` (checks for all required tools).

### Build workflow
- **Build APK**: `make build` (debug) or `make build-release` (release).
- **Install APK**: `make install` (installs debug APK on connected device/emulator).
- **Run tests**: `make test-unit`, `make test-integration`, `make test-e2e`.
- **Lint code**: `make lint` (ktlint/detekt).
- **Clean build**: `make clean`.

### Emulator usage
- **Create emulator**: `make setup-emulator` (creates AVD with API 34, x86_64).
- **Start emulator**: `make start-emulator` (starts in background, headless).
- **Stop emulator**: `make stop-emulator`.
- Alternatively, use Android Studio AVD Manager for GUI-based emulator management.

### Device setup (for testing)
- Connect device via USB or wirelessly (adb connect).
- Enable Developer Options and USB Debugging on device.
- Grant permissions via adb: `make grant-permissions` (provides instructions, user must grant manually).
- Port forwarding: `make forward-port` (forwards device port 8080 to host 8080 for localhost-bound MCP server).

### Build diagnostics
- Always use Makefile targets or Gradle tasks (not ad-hoc commands).
- When investigating build failures, inspect at least the last 150 lines of Gradle output.
- Do not grep for a single error and stop; failures can be cascading (e.g., dependency resolution → compilation → test failures).
- Check Gradle daemon status: `./gradlew --status`.
- Clear Gradle cache if needed: `./gradlew clean --no-daemon`.

---

## 10) Deployment Rules (Android APK Building)

### APK building
- **Debug APK**: Build with `make build` or `./gradlew assembleDebug`.
  - Application ID: `com.danielealbano.androidremotecontrolmcp.debug`.
  - Debuggable: true.
  - Minify: false.
  - Signed with debug keystore.
- **Release APK**: Build with `make build-release` or `./gradlew assembleRelease`.
  - Application ID: `com.danielealbano.androidremotecontrolmcp`.
  - Debuggable: false.
  - Minify: false (open source with MIT license, no ProGuard/R8).
  - Signed with release keystore (if configured in `keystore.properties`).

### Versioning
- Follow **semantic versioning** (MAJOR.MINOR.PATCH).
- Version defined in `gradle.properties`:
  - `VERSION_NAME=1.0.0`
  - `VERSION_CODE=1` (auto-increment for each release).
- Bump version: `make version-bump-patch`, `make version-bump-minor`, `make version-bump-major`.

### Health check endpoint (MCP server)
- Implement `/health` endpoint in Ktor server.
- Returns JSON: `{"status": "healthy", "version": "1.0.0", "server": "running"}`.
- Keep health check lightweight (no accessibility/screenshot operations).
- Return HTTP 200 for healthy, 503 for unhealthy.
- Health check is **unauthenticated** (no bearer token required).

### Graceful shutdown (Android Services)
- **McpServerService**: Handle `onDestroy()`:
  - Stop Ktor server gracefully (wait for in-flight requests with timeout),
  - Cancel coroutine scopes,
  - Log shutdown event.
- **AccessibilityService**: Handle `onDestroy()`:
  - Recycle cached accessibility nodes,
  - Clear singleton instance,
  - Cancel coroutine scopes,
  - Log shutdown event.

### Release distribution
- APK location: `app/build/outputs/apk/release/app-release.apk`.
- Distribute via:
  - GitHub Releases (tag with version, attach APK),
  - Direct download (host APK on server),
  - Google Play Store (if public release desired, requires additional setup).
- Include changelog in release notes (document new MCP tools, bug fixes, improvements).

### CI/CD (GitHub Actions)
- Workflow defined in `.github/workflows/ci.yml`.
- Runs on: push to main, pull requests.
- Jobs: lint → test-unit (includes JVM integration tests) → test-e2e → build-release.
- Upload APK as artifact on successful build.
- Fail build if any test or lint check fails.
