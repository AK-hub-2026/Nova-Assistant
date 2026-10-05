# NOVA — Master Development Plan

**Permanent Core Mandate — Zero Mock Data:**  
Nova operates with **0% mock, fake, placeholder, fabricated, simulated, or hardcoded runtime data**. Every screen node, installed app, contact lookup, notification, battery/sensor reading, voice transcript, AI completion, permission status, and action execution result comes strictly from real Android OS APIs or real configured AI endpoints. Whenever a permission is ungranted, a service is disabled, an API key is missing, or an action fails verification, Nova surfaces the exact real state (`PERMISSION_REQUIRED`, `SERVICE_DISABLED`, `CONFIGURATION_REQUIRED`, `UNSUPPORTED_SDK`, `VERIFICATION_FAILED`, or `NO_DATA`) and never fabricates a success state.

---

## 1. Nova Architecture

Nova uses a modular, unidirectional **Clean Architecture** tailored for Android's process lifecycle and strict 2–4 GB RAM constraints:

1. **UI Layer (`ui`)**: Single-Activity Jetpack Compose interface (`NovaMainActivity`) + an in-service Accessibility Overlay HUD (`TYPE_ACCESSIBILITY_OVERLAY`) using adaptive **Material You (Material 3)** dynamic light and dark themes.
2. **Voice Layer (`voice`)**: Real-time Android `SpeechRecognizer` (STT) and `TextToSpeech` (TTS) state machine with audio focus management and barge-in cancellation.
3. **AI Provider Layer (`ai`)**: Unified `NovaAiProvider` abstraction with a **Gemini API** primary adapter alongside **Groq** and **OpenAI-compatible** REST adapters, plus a deterministic local intent fast-path when offline.
4. **Agent & Reasoning Layer (`agent`)**: Multi-step Observe-Plan-Confirm-Act-Verify loop with prompt-injection sanitization, structured tool-call validation, loop prevention, and recovery.
5. **Tools & Android Integration Layer (`tools`, `system`)**: Standard Android API/Intent executors (`PackageManager`, `AlarmClock`, `Intent.ACTION_DIAL`, `ACTION_SENDTO`, `ACTION_VIEW`, system settings, clipboard/share, audio volume, flashlight) always preferred before Accessibility.
6. **Accessibility Layer (`accessibility`)**: `NovaAccessibilityService` providing cross-app semantic UI node inspection (`rootInActiveWindow`), node-level actions (`ACTION_CLICK`, `ACTION_SET_TEXT`, `ACTION_SCROLL`), gesture dispatch (`dispatchGesture`), and real-state post-action verification.
7. **Vision & On-Demand OCR Layer (`vision`)**: Fallback screen capture (`takeScreenshot` on API 30+) and on-demand OCR/vision triggered *only* when the Accessibility node tree exposes insufficient semantic data.
8. **Memory & Storage Layer (`memory`, `storage`)**: Single consolidated **Room** database (`NovaDatabase`) for conversation logs, user-approved memory facts, learned app navigation hints, and scheduled tasks.
9. **Security & Privacy Layer (`security`)**: Permission gatekeeper, sensitive-node redactor (`isPassword` and PII scrubbing), and risky-action confirmation gate.
10. **Scheduler & Background Layer (`scheduler`)**: `WorkManager` and `AlarmManager` for reminders and deferred tasks, plus a strictly scoped `ForegroundService` active only during live voice/multi-step automation.
11. **Configuration Layer (`config`)**: Encrypted/local provider credentials, model selection, low-end device mode toggles, and runtime diagnostics.

---

## 2. Feature List

- **Voice & Conversation**: Push-to-talk and continuous multi-turn voice sessions, live partial STT transcript streaming, spoken TTS responses, and immediate barge-in interruption.
- **Multi-Provider AI Reasoning**: Gemini primary provider with seamless switching/fallback to Groq and OpenAI-compatible endpoints, plus offline deterministic command execution for core device controls.
- **Standard Android Device Control**: App discovery and launching via real `PackageManager` queries, setting alarms/timers, dialing numbers, composing SMS/emails, toggling flashlight, adjusting media/ring volume, opening system settings panels, and deep-linking searches.
- **Accessibility-First Cross-App Automation**: Semantic UI node tree extraction across any foreground app, smart clickable parent resolution, text input into editable fields, list scrolling, global navigation (`BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`), and coordinate gesture fallback.
- **Post-Action State Verification**: Every action compares pre-action and post-action window/package/node signatures before reporting completion; failed transitions trigger bounded recovery or honest failure reporting.
- **On-Demand VisionFallback**: API 30+ `AccessibilityService.takeScreenshot()` downscaled in memory for custom canvas/WebView screens where semantic nodes are empty.
- **Adaptive Floating Assistant HUD**: Lightweight overlay rendered via `TYPE_ACCESSIBILITY_OVERLAY` inside `NovaAccessibilityService` (requiring zero `SYSTEM_ALERT_WINDOW` permission) showing real-time step status and an instant **Stop/Cancel** button.
- **Notification Awareness**: Opt-in `NotificationListenerService` to read, summarize, and trigger user-approved actions from real incoming notifications.
- **Privacy-First Local Memory**: Room-backed persistent memory for user facts, contact aliases, and task audit trails with full user inspection and one-tap deletion.
- **Reminders & Scheduling**: Real Android `AlarmManager` / `WorkManager` reminders and scheduled assistant routines.
- **Extensible Input Hooks**: Modular `InputSignalSource` interface prepared for future camera/face/eye/gesture triggers without altering the core agent loop.

---

## 3. Module / Package Structure

All code resides under a clean, original namespace (`com.nova.assistant` inside `/app/src/main/java/com/nova/assistant/`):

```text
com.nova.assistant
├── NovaApplication.kt                  # App container & lazy dependency graph
├── ui/
│   ├── NovaMainActivity.kt             # Single-Activity Compose host
│   ├── navigation/NovaDestinations.kt  # Type-safe navigation routes
│   ├── theme/                          # Material You dynamic light/dark theme
│   ├── home/                           # Voice/text console, live execution timeline
│   ├── permissions/                    # Real-time permission & service status center
│   ├── memory/                         # Memory & audit log inspector/editor
│   ├── scheduler/                      # Reminders & scheduled tasks screen
│   └── settings/                       # AI provider config, low-RAM mode, open-source licenses
├── overlay/
│   └── NovaAccessibilityOverlay.kt     # Lightweight HUD attached via TYPE_ACCESSIBILITY_OVERLAY
├── voice/
│   ├── VoiceSessionController.kt       # Coordinates STT, TTS, audio focus & barge-in
│   ├── AndroidSpeechRecognizerEngine.kt# Real Android SpeechRecognizer wrapper
│   └── AndroidTtsEngine.kt             # Real Android TextToSpeech wrapper
├── ai/
│   ├── NovaAiProvider.kt               # Unified interface for completion & tool calling
│   ├── ProviderRouter.kt               # Primary/fallback routing & health checks
│   ├── GeminiAiProvider.kt             # Gemini REST API implementation
│   ├── GroqAiProvider.kt               # Groq Cloud API implementation
│   ├── OpenAiCompatibleProvider.kt     # Generic OpenAI-spec REST implementation
│   └── LocalDeterministicParser.kt     # Zero-RAM offline command parser for basic intents
├── agent/
│   ├── NovaAgentOrchestrator.kt        # Observe → Plan → Confirm → Act → Verify loop
│   ├── PromptAssembler.kt              # Token-budgeted prompt builder
│   ├── OutputSchemaValidator.kt        # Strict JSON action/tool schema parser
│   └── VerificationEngine.kt           # Pre/post UI & system state verifier
├── tools/
│   ├── NovaToolRegistry.kt             # Declarative tool specifications & permissions required
│   ├── SystemIntentExecutor.kt         # Alarms, dialer, SMS, web, app launch, settings
│   └── DeviceControlExecutor.kt        # Volume, flashlight, clipboard, battery/network queries
├── accessibility/
│   ├── NovaAccessibilityService.kt     # Bound AccessibilityService & overlay host
│   ├── SemanticScreenExtractor.kt      # Pruned, privacy-redacted AccessibilityNodeInfo reader
│   └── AccessibilityActionPerformer.kt # Node click/type/scroll & GestureDescription dispatcher
├── vision/
│   └── OnDemandScreenVision.kt         # API 30+ takeScreenshot() & downscaled bitmap processor
├── notifications/
│   └── NovaNotificationService.kt      # Opt-in NotificationListenerService
├── memory/
│   ├── NovaDatabase.kt                 # Single consolidated Room database
│   ├── dao/                            # ConversationDao, MemoryFactDao, TaskLogDao, ReminderDao
│   └── entity/                         # Room entities
├── scheduler/
│   ├── NovaSchedulerManager.kt         # WorkManager & AlarmManager coordinator
│   └── NovaActiveTaskService.kt        # Scoped ForegroundService during active tasks
├── security/
│   ├── PermissionAuditor.kt            # Real-time runtime & special permission verifier
│   ├── SensitiveDataRedactor.kt        # Scrubs password nodes, OTPs, and keys before AI calls
│   └── ActionConfirmationGate.kt       # Blocks high-risk actions until explicit user approval
└── config/
    └── NovaPreferencesRepository.kt    # Provider keys, active models, low-RAM flags
```

---

## 4. End-to-End Data Flow

```text
[1. User Input]
   Real Voice Utterance (SpeechRecognizer) OR Text Input
       │
       ▼
[2. Context & Redaction]
   • Queries real permission states (PermissionAuditor)
   • Retrieves top relevant local facts (MemoryFactDao)
   • If cross-app context needed: extracts pruned SemanticScreenSnapshot
     via NovaAccessibilityService and scrubs password/sensitive nodes
       │
       ▼
[3. AI / Agent Planning]
   • LocalDeterministicParser checks for instant offline system commands first
   • Otherwise routes to NovaAiProvider (Gemini Primary → Groq / OpenAI-Compatible)
   • OutputSchemaValidator parses structured ToolCall + ExpectedState criteria
       │
       ▼
[4. Permission & Safety Gate]
   • PermissionAuditor checks if required permission/service is active
     (If missing → halts immediately and returns real PERMISSION_REQUIRED state)
   • ActionConfirmationGate checks risk level
     (If HIGH_RISK [call, send message, delete, payment, external post] →
      pauses and prompts user via UI + Voice for explicit Confirm/Cancel)
       │
       ▼
[5. Action Execution]
   • Standard Android API / Intent path (SystemIntentExecutor / DeviceControlExecutor)
   • OR Accessibility path (AccessibilityActionPerformer node action / gesture)
       │
       ▼
[6. Mandatory Real-State Verification]
   • VerificationEngine inspects actual post-action result (Intent resolution,
     foreground package change, target node change, or setting state)
   • If unverified: retries up to max 2 bounded attempts or reports honest failure
       │
       ▼
[7. Real Result & Output]
   • Records verified step outcome in TaskLogDao (Room)
   • Updates NovaAccessibilityOverlay & Compose UI
   • Speaks concise result via AndroidTtsEngine
```

---

## 5. Permission Strategy

Nova enforces **strict least-privilege, just-in-time permission handling**:

- **Install-Time (Normal) Permissions**: `INTERNET`, `ACCESS_NETWORK_STATE`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `SET_ALARM`.
- **Package Visibility (`<queries>`)**: Declares targeted `<queries>` in `AndroidManifest.xml` for launcher activities (`ACTION_MAIN` / `CATEGORY_LAUNCHER`) and standard communication/media intents instead of broad Play-restricted permissions where possible.
- **Runtime Dangerous Permissions**:
  - `RECORD_AUDIO`: Requested only when the user activates voice input. If denied, Nova seamlessly operates in text-only mode and displays the real `RECORD_AUDIO` permission status.
  - `POST_NOTIFICATIONS` (API 33+): Requested when enabling background task status notifications or reminders.
  - `READ_CONTACTS` (Optional): Requested only if the user asks Nova to look up a contact by name; if denied, Nova asks for the phone number or opens the dialer intent directly.
- **User-Toggled Special Services**:
  - `BIND_ACCESSIBILITY_SERVICE`: Never assumed active. Nova checks `AccessibilityManager` in real time and deep-links to `Settings.ACTION_ACCESSIBILITY_SETTINGS` with clear disclosure when a cross-app automation tool requires it.
  - `BIND_NOTIFICATION_LISTENER_SERVICE`: Strictly opt-in via `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
  - **Zero `SYSTEM_ALERT_WINDOW` Requirement**: Floating assistant UI is rendered via `TYPE_ACCESSIBILITY_OVERLAY` inside `NovaAccessibilityService`, eliminating a separate overlay permission prompt and working on Android Go devices.

---

## 6. AI & Voice Architecture

- **Provider Abstraction (`NovaAiProvider`)**:
  - Unified suspend interface accepting system instructions, sanitized screen state, conversation history, and declarative tool schemas, returning a typed `AiPlanResult` (`ToolCall`, `DirectAnswer`, `ClarificationNeeded`, or `ProviderError`).
  - **Primary Provider**: `GeminiAiProvider` (supporting `BuildConfig.GEMINI_API_KEY` from AI Studio Secrets / `.env` or user-configured API key in Settings, defaulting to fast low-latency Gemini Flash models).
  - **Secondary Adapters**: `GroqAiProvider` and `OpenAiCompatibleProvider` (allowing users to plug in Groq, OpenAI, Together, DeepSeek, or a LAN Ollama/vLLM server).
  - **Offline Fast-Path**: `LocalDeterministicParser` handles direct device commands (*"open <app>"*, *"set alarm for <time>"*, *"flashlight on/off"*, *"volume up/down"*, *"go home/back"*, *"scroll down/up"*) with zero network dependency.
- **Voice Pipeline (`VoiceSessionController`)**:
  - Uses Android's native `SpeechRecognizer` and `TextToSpeech` (running in system services rather than bloating app RAM).
  - Exposes a single immutable `StateFlow<VoiceSessionState>` (`Unavailable`, `PermissionMissing`, `Idle`, `Listening(partialText)`, `Processing`, `Speaking(utterance)`, `Error(code)`).
  - **Barge-In**: Tapping the mic or triggering voice input immediately invokes `tts.stop()` and transitions cleanly to `Listening`.

---

## 7. Agent & Tool Architecture

- **Declarative Tool Registry (`NovaToolRegistry`)**:
  - Every tool declares: `name`, `parameterSchema`, `executionChannel` (`ANDROID_API` vs. `ACCESSIBILITY`), `requiredPermissions`, and `riskClassification` (`SAFE`, `SENSITIVE_CONFIRM`, `DESTRUCTIVE_CONFIRM`).
- **Bounded Multi-Step Execution (`NovaAgentOrchestrator`)**:
  - Hard cap of **8 steps per task** and **2 recovery attempts per failed step** to prevent infinite loops or runaway API usage.
  - Exposes a coroutine `Job` tied to a physical **Stop** button on both the full-screen UI and the floating Accessibility overlay so the user can abort execution in `<50ms`.

---

## 8. Accessibility Architecture

- **Event-Driven, On-Demand Inspection**:
  - Unlike legacy assistants that traverse the entire view tree on every `TYPE_WINDOW_CONTENT_CHANGED` event (causing severe UI lag and battery drain), `NovaAccessibilityService` only caches lightweight window metadata (`packageName`, `className`, `lastEventTimestamp`) on events, and performs full `AccessibilityNodeInfo` tree traversal **only on demand** when the Agent requests a snapshot or verifies an action.
- **Semantic Node Filtering (`SemanticScreenExtractor`)**:
  - Filters out invisible, zero-area, and decorative layout containers.
  - Assigns each actionable or informative node a compact index `[1..N]` (capped at a configurable limit, e.g., 80 nodes on low-RAM devices) with `text`, `contentDescription`, short `viewId`, `className`, `boundsInScreen`, and capability flags (`clickable`, `editable`, `scrollable`, `checked`).
- **Action Execution (`AccessibilityActionPerformer`)**:
  - **Click**: Targets the semantic node by index/selector; if `isClickable == false`, walks up the parent hierarchy to the nearest clickable ancestor before invoking `ACTION_CLICK`. Falls back to `dispatchGesture` center-tap only if standard `ACTION_CLICK` returns `false`.
  - **Type**: Focuses the node and dispatches `ACTION_SET_TEXT` with `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`.
  - **Scroll**: Invokes `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD` on scrollable containers, with smooth `GestureDescription` swipe fallback.

---

## 9. Vision & OCR Architecture (Low-RAM Safe)

- **Accessibility-First Rule**: 95%+ of Android UI interactions use `SemanticScreenExtractor` (taking `<5ms` and `<100KB` RAM).
- **On-Demand Fallback Only**: `OnDemandScreenVision` is invoked **only** when:
  1. The user explicitly asks Nova to visually inspect the screen/image, **or**
  2. The active window contains `<2` meaningful accessibility nodes (e.g., custom Canvas, WebView without accessibility nodes, or image content).
- **Memory-Safe Capture**:
  - Uses `AccessibilityService.takeScreenshot()` on Android 11+ (`API 30+`). On Android 7–10 (`API 24–29`), gracefully reports `UNSUPPORTED_SDK_FOR_SCREENSHOT` rather than running heavy `MediaProjection` buffers.
  - Immediately downscales hardware bitmaps to max `720p` (`Bitmap.Config.RGB_565`), compresses to WebP, and calls `bitmap.recycle()` before sending to multimodal AI or OCR.

---

## 10. Memory Architecture

- **Single Consolidated Room Database (`NovaDatabase`)**:
  - `ConversationSessionEntity` & `ConversationTurnEntity`: Real timestamped user inputs, tool executions, verification outcomes, and assistant responses.
  - `MemoryFactEntity`: Explicit or user-confirmed semantic facts (`key`, `value`, `category`, `createdAt`, `lastUsedAt`) such as preferred apps or contact nicknames.
  - `TaskAuditLogEntity`: Immutable record of every executed device/accessibility action, target package, and verification status (`VERIFIED_SUCCESS`, `FAILED`, `USER_CANCELLED`, `BLOCKED_BY_PERMISSION`).
  - `ScheduledReminderEntity`: Real scheduled reminders and routines synced with `AlarmManager` / `WorkManager`.

---

## 11. Background & Scheduler Architecture

- **Zero Permanent Heavy Background Daemons**:
  - When Nova is idle, **no** `ForegroundService`, wake lock, socket server, or polling loop runs.
- **Scoped `NovaActiveTaskService`**:
  - Started as a `ForegroundService` *only* when a multi-step cross-app automation or background voice turn is actively running so Android's Low Memory Killer (`lmkd`) does not terminate the coroutine mid-task while another heavy app is in the foreground.
  - Automatically calls `stopSelf()` the moment the task finishes, fails, or is cancelled.
- **Scheduled Reminders (`NovaSchedulerManager`)**:
  - Uses `AlarmManager` (for exact user reminders, checking `canScheduleExactAlarms()` on API 31+ with graceful fallback) and `WorkManager` for periodic maintenance.

---

## 12. Security & Privacy Architecture

1. **API Key Protection**: Reads `BuildConfig.GEMINI_API_KEY` injected via `.env` / Secrets Gradle Plugin, and stores any user-entered provider keys in private app storage excluded from cloud backups (`backup_rules.xml` & `data_extraction_rules.xml` configured with strict `<exclude>` rules).
2. **Pre-Transmission Redaction (`SensitiveDataRedactor`)**:
   - Automatically masks any `AccessibilityNodeInfo` where `isPassword == true` as `[REDACTED_PASSWORD]`.
   - Scrubs credit card patterns, SSNs, and 4–8 digit OTP codes from screen snapshots before constructing LLM prompts.
3. **Prompt Injection Defense**:
   - Untrusted third-party screen text and notification bodies are wrapped in isolated, non-instructional XML/JSON delimiters in `PromptAssembler`, and any tool call triggered while reading untrusted external content must pass `ActionConfirmationGate`.
4. **Mandatory Confirmation for Sensitive Actions (`ActionConfirmationGate`)**:
   - Sending SMS/messages, placing direct phone calls, deleting items, modifying system-critical settings, or submitting forms requires explicit user confirmation (via floating overlay button or voice confirmation).

---

## 13. Low-End Android Optimization Strategy (2–4 GB RAM)

- **Single-Database, Single-Activity Architecture**: Eliminates multi-Activity memory duplication and multi-DB SQLite connection pools.
- **Lazy Initialization**: Retrofit/OkHttp clients, TTS engine, and Room DAOs are initialized lazily on first use, keeping cold start fast.
- **Low-End Device Mode (Auto-Detected via `ActivityManager.isLowRamDevice` or Total RAM `< 4 GB`)**:
  - Disables continuous decorative animations in Compose UI.
  - Caps serialized Accessibility nodes per snapshot to `50` interactive nodes.
  - Disables bitmap screenshot caching and enforces strict node-tree-only automation unless explicitly requested.
- **Dependency Diet**: Uses only Apache-2.0 AndroidX, Compose, Room, Retrofit, OkHttp, and Moshi libraries; avoids bundling local multi-hundred-MB LLM engines (`llama.cpp` / ONNX) inside the APK.

---

## 14. Dependency & License Strategy

All dependencies used by Nova are already declared in `gradle/libs.versions.toml` and carry permissive **Apache License 2.0** (or MIT/EPL for test-only libraries), safe for commercial distribution:

| Dependency Group | License | Commercial Use | Action in Nova |
| :--- | :--- | :--- | :--- |
| **AndroidX Core, Activity, Lifecycle, Navigation, Room, DataStore, Work** | Apache-2.0 | Permitted | Active core foundation |
| **Jetpack Compose (BOM, UI, Material 3, Icons)** | Apache-2.0 | Permitted | Active UI layer |
| **Square Retrofit 2.12, OkHttp 4.10, Moshi 1.15** | Apache-2.0 | Permitted | Active AI provider HTTP & JSON layer |
| **Kotlin Coroutines 1.10** | Apache-2.0 | Permitted | Active concurrency & `StateFlow` layer |
| **Firebase AI / AppCheck (Unused without `google-services.json`)** | Apache-2.0 | Permitted | Comment out in `app/build.gradle.kts` so unconfigured Firebase SDKs do not bloat APK or crash at runtime |
| **JUnit 4 & Robolectric 4.16** | EPL-1.0 / MIT | Test-only (not in APK) | Active JVM verification suite |

An **Open Source Licenses & Attributions** view will be included in Nova's Settings screen displaying the required Apache-2.0 notices.

---

## 15. Testing Strategy

Following each milestone, we verify code quality and real behavior via:
1. **Build Verification**: `compile_applet` to ensure zero Kotlin, KSP, or resource compilation errors.
2. **Local JVM & Robolectric Tests (`gradle :app:testDebugUnitTest`)**:
   - Unit tests for `SensitiveDataRedactor`, `OutputSchemaValidator`, `LocalDeterministicParser`, `ActionConfirmationGate`, and `VerificationEngine`.
   - Robolectric tests for `NovaDatabase` (Room DAOs), `PermissionAuditor` state reporting, and Compose UI states (verifying that missing permissions or unconfigured keys render honest status banners rather than mock data).

---

## 16. Development Milestones (Incremental Order)

- **Milestone 1 — Core Foundation, Real System Telemetry, Room Memory & Material You UI Shell**:
  - Re-architect package structure under `com.nova.assistant`, update app identity to **Nova** (`metadata.json` & `strings.xml`), clean up unused Firebase dependencies in `app/build.gradle.kts`, harden `backup_rules.xml`, implement `NovaDatabase` (Room), `PermissionAuditor` (real runtime & service status checks), `DeviceControlExecutor` / `SystemIntentExecutor` (real installed apps list via `PackageManager`, real battery/network telemetry, alarms, dialer, settings intents), and the adaptive Material You 4-tab UI (`Assistant`, `Permissions & Services`, `Memory & Audit Log`, `Settings`).
- **Milestone 2 — Multi-Provider AI Layer, Voice Engine (STT/TTS) & Offline Command Parser**:
  - Implement `NovaAiProvider`, `GeminiAiProvider`, `GroqAiProvider`, `OpenAiCompatibleProvider`, `LocalDeterministicParser`, `SensitiveDataRedactor`, and `VoiceSessionController` (`AndroidSpeechRecognizerEngine` + `AndroidTtsEngine` with real microphone permission flow and barge-in).
- **Milestone 3 — Accessibility Service, Semantic Screen Reader, Floating Overlay & Verification Loop**:
  - Implement `NovaAccessibilityService`, `accessibility_service_config.xml`, `SemanticScreenExtractor`, `AccessibilityActionPerformer`, `NovaAccessibilityOverlay` (`TYPE_ACCESSIBILITY_OVERLAY`), `ActionConfirmationGate`, `VerificationEngine`, and `NovaAgentOrchestrator` for verified multi-step cross-app automation.
- **Milestone 4 — On-Demand Vision Fallback, Notification Listener & Scheduled Reminders**:
  - Implement `OnDemandScreenVision` (`API 30+` `takeScreenshot` fallback), `NovaNotificationService`, `NovaSchedulerManager`, `NovaActiveTaskService`, and comprehensive Robolectric/unit test suite.

---

## 17. Technical Limitations (Honest Platform Boundaries)

1. **Android Accessibility Activation**: Android forbids apps from programmatically enabling their own `AccessibilityService` without root/ADB. The user must toggle **Nova Accessibility Service** on once in Android Settings.
2. **DRM / `FLAG_SECURE` Windows**: Banking apps and DRM video players set `WindowManager.LayoutParams.FLAG_SECURE`, which causes Android to block `takeScreenshot()` and sometimes redact accessibility node text. Nova will detect this and report `BLOCKED_BY_SECURE_WINDOW` honestly.
3. **API 24–29 Screenshot Limitation**: `AccessibilityService.takeScreenshot()` requires Android 11 (`API 30+`). On Android 7–10 (`API 24–29`), Nova relies exclusively on the Accessibility Node Tree.
4. **Hardware Speech Recognizer Availability**: `SpeechRecognizer.isRecognitionAvailable(context)` requires a system speech recognition service on the device; if absent, Nova reports `SPEECH_RECOGNITION_UNAVAILABLE` and falls back to text input.

---

## 18. First Implementation Milestone (What Should Be Built First)

**Milestone 1: Core Foundation, Room Database, Real Permission/Device Auditor, Standard Android Tool Executors & Material You UI Shell**

Specifically, in Milestone 1 we will:
1. Update `metadata.json` and `res/values/strings.xml` to name the application **Nova**, and optimize `app/build.gradle.kts` + `AndroidManifest.xml` with required permissions and `<queries>`.
2. Build `NovaDatabase` (Room) with real DAOs and entities for conversations, user memories, and verified task execution logs (starting empty—0% mock data).
3. Build `PermissionAuditor` and `SystemIntentExecutor` / `DeviceControlExecutor` to query **real** device state (actual granted/denied permissions, actual Accessibility service bound status, actual installed launchable apps on the device, real battery level, network connectivity, and real system Intent execution).
4. Build the adaptive Material You Compose UI (`NovaMainActivity` with `Assistant`, `System & Permissions`, `Memory & Logs`, and `Settings` screens) and verify with `compile_applet` and Robolectric tests.
