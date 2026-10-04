# 🏗️ Architecture

Papercut is one Gradle `:app` module organized into clean packages. We chose
**package-level modularity** over multi-module: it builds faster in CI, is
trivially navigable, and any human or AI agent can trace the whole graph without
chasing Gradle project edges.

```
com.papercut.app
├── PapercutApp / AppContainer   ← manual DI root (see below)
├── MainActivity                 ← edge-to-edge host, storage-permission gate composable
├── navigation/                  ← type-safe Routes + NavHost
├── di/PaperViewModel.kt         ← appViewModel{} helper (one ViewModel factory)
├── core/
│   ├── design/                  ← bento tokens + reusable atoms + MessageBus ONLY
│   ├── data/                    ← repositories + AI transport + renderer + PDF + pipeline
│   │   └── model/               ← serializable settings doc + document/page models
│   └── domain/                  ← ProcessingQueue, DraftStore, PromptTemplates,
│                                  OrientationTracker, PageGeometry (pure, tested)
└── feature/
    ├── onboarding · scanner · editor · document · library · settings · provider · prompt
```

## The dependency rule

`feature → core` , never `core → feature`, never `feature → feature`.

The one cross-feature reuse (high-res render, status badge, pill toggle) lives in
`core/` precisely so features don't import each other — the old prototype had
`feature.library` importing a 150-line function out of `feature.viewer`.

## Manual DI — AppContainer

`PapercutApp` (Application) builds one `AppContainer` holding singletons:

```
SettingsRepository ── SecretStore ── DocumentRepository ── AiClient ── HtmlRenderer ── ProcessingQueue
                                              └── DraftStore (capture session)   └── MessageBus (snackbars)
```

ViewModels get it via `appViewModel { c -> XViewModel(c) }` (`di/`). No Hilt, no
KSP, no annotations — the entire object graph is 10 lines in `PapercutApp.kt`.
For tests, construct a container with fakes; nothing reads a global singleton.

## State flow (MVVM, one direction)

```
Repository/Queue  --StateFlow-->  ViewModel  --collectAsState-->  Composable
                                     ^                                |
                                     └──────── user actions ──────────┘
```

- Every screen has **one ViewModel**; state survives navigation/rotation because
  it lives in the VM, not `remember`.
- `ProcessingQueue.statuses` is the **single** source for every status badge —
  one map, keyed `"$folder/$doc#$page"`. The old app scattered per-screen job
  state and stacked two overlays at once.
- ViewModels own their `viewModelScope`; repositories own their IO scope. No
  floating `CoroutineScope()` anywhere.

## AI layer — the part that changed most

- **Transport** (`core/data/AiClient`): Ktor + kotlinx-serialization, POSTs
  `<baseUrl>/chat/completions` with a multimodal content array (image data-URL +
  text). OpenAI-compatible → one dialect covers Gemini/OpenRouter/Groq/Ollama/LM
  Studio/custom.
- **Providers** (`SettingsRepository.builtInProviders` + user custom): name,
  base URL, model list, enabled flag. Persisted in the settings doc (no secrets).
- **Keys** (`SecretStore`): the settings doc stores `KeyEntry` **metadata only**
  (id, label, usage count, cooldown). The secret string lives in
  `EncryptedSharedPreferences` (Android Keystore AES-GCM). `AiClient` reads it at
  call time, uses it once, caches nothing.
- **Rotation** (`ProcessingQueue.pickKey`): least-recently-used among keys not in
  cooldown. On `429` → bench the key 60s; on `401/403` → bench 10min; other errors
  don't bench (they're the provider's fault, not the key's). Up to
  `MAX_ATTEMPTS_PER_TASK` across different keys before surfacing an error.

## Storage — DocumentRepository (v2)

Scoped Storage via SAF. User picks a root tree; inside it we create a
**document-centric** layout — a document is a folder, each page a numbered
file. Edits are non-destructive (stored in `meta.json`, applied on render):

```
Papercut/
  Default/                 (first folder, undeletable)
  <MyFolder>/
    <Doc Name>/
      meta.json            DocumentMeta { name, createdAt, pages[] }
      page-01.jpg          original capture (crop/rotation/filter in meta)
      page-01.html         current AI digital twin
      page-01.prev.html    single twin backup (from Re-run)
    IMG_<ts>.jpg           legacy v1 scan — auto-bridged as a 1-page document
```

- `PageGeometry` (pure JVM) solves the perspective homography and sanity-checks
  the crop quad; `PagePipeline` applies warp→rotate→filter to pixels; both are
  unit-tested in CI (`PageGeometryTest`).
- Reorder/delete (`setOrder`/`deletePage`) move photos, twins AND backups in
  lockstep so a page never pairs with the wrong twin. `savePageHtml` resolves
  the slot by photo identity, surviving a reorder mid-AI-call.
- `DraftStore` (app-scoped) holds the in-progress capture session so pages and
  crops survive navigation between Scanner and Editor.
- `PdfExporter` builds a valid PDF 1.4 in-memory (JPEG pages + hand-built xref)
  with zero external dependencies.
- All IO runs on `Dispatchers.IO` inside the repository (the old app hit disk on
  the main thread).

## Orientation — OrientationTracker

`core/domain/OrientationTracker` is **pure logic, no Android sensor** (testable —
see the unit test). The scanner feeds `OrientationEventListener` raw degrees:

1. **Low-pass filter** on the wrapped angle → no jitter reaching anything.
2. **Quadrant with deadband hysteresis** → `targetRotation` (the value that
   decides saved-image orientation) only flips when you're clearly inside a new
   90° zone. Hovering at 45° holds the last stable quadrant instead of flickering.
3. Icons/UI follow the **continuous** smoothed angle → motion looks alive.

This directly fixes the reported "45° glitchy" behavior of the old 4-way quantizer.

## High-res export — HtmlRenderer

Event-driven, replaces the old "sleep 3s + 1s then draw":
`loadDataWithBaseURL` → wait `onPageFinished` → if MathJax present, poll its
`startup.promise` (armed via `evaluateJavascript`) until typesetting is idle →
one `Choreographer` frame to guarantee paint landed → `measure/layout/draw` to a
2480px bitmap → **half-scale retry on `OutOfMemoryError`** (kept from old). The
WebView is created/destroyed per call on Main inside the renderer; callers just
`renderer.render(html): Bitmap?` and get a `busy` flow.

## Navigation — Routes + type-safe args

`navigation/Routes.kt` is a `sealed class`; screens call
`navController.navigate(Route.Folder("x"))`. URIs are `Uri.encode`-d; args are
read back with the same `Route.ARG_*` keys. The old string-template routes with
an encoded-URI query param were fragile — gone. Bottom bar hides via a small
explicit route set, not `startsWith` heuristics.

## Build & CI

- AGP 8.7.3 · Gradle 8.11.1 · Kotlin 2.0.21 · Compose BOM 2024.12 · minSdk 26 /
  target+compile 35. Proven Actions combination; we avoid bleeding-edge (the old
  app's AGP 9.0.1 / Gradle 9.2 is too new for stable CI).
- `Base64.getEncoder()` is used (API 26+), and `java.time`/`MediaStore`
  Downloads APIs are gated to the same floor — minSdk 26 lets us skip the old
  maxSdkVersion / Compat dance entirely.
- **`.github/workflows/android.yml`** builds the debug APK + runs unit tests on
  every push/PR. **No APK is ever built on the dev machine** (project rule).
- `gradlew` is pinned LF via `.gitattributes`; release signing is intentionally
  unconfigured until a key is added as a repo secret (see CONTRIBUTING).
