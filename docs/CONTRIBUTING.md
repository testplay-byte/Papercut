# 🤝 Contributing

Papercut is built so that **any developer or AI agent can understand the whole
project from the docs alone**. Start here:

1. `README.md` — what the app is
2. `docs/ARCHITECTURE.md` — where every responsibility lives and why
3. `docs/UI-DESIGN.md` — the design system and per-screen plans
4. `docs/USAGE.md` — the pipeline end to end (what a change must keep working)

## Ground rules

- **Never build APKs locally.** All compilation happens in GitHub Actions
  (`.github/workflows/android.yml`). Locally: read, edit, run unit tests with a
  plain `gradlew :app:testDebugUnitTest` ONLY if Gradle is already cached — this
  project's policy treats even that as optional; CI is the compiler.
- **No secrets, datasets, or personal files in this repo.** Settings JSON, API
  keys, scanned documents — all device-local. Docs may describe data formats but
  must not include real examples from a user's library.
- **Design discipline:** new UI uses `core/design` tokens and atoms. No raw hex
  colors outside `PaperTokens.kt`, no re-implementing pill/switch/badge/tile,
  no ripple/focus indicators (scale-on-press is the rule).
- **Threading discipline:** disk/network calls belong in repositories on
  `Dispatchers.IO`. Composables and ViewModels never touch the filesystem directly.
- **Architecture direction:** `feature → core`, never sideways between features,
  never `core → feature`. New shared logic goes to `core/`.

## Where things go

| Change type | Home |
|---|---|
| New provider API dialect | `core/data/AiClient` (+ `ProviderFormat`) |
| Queue/rotation policy | `core/domain/ProcessingQueue` |
| On-disk layout | `core/data/ScanRepository` |
| New screen | `feature/<name>/` (screen + ViewModel only) |
| New reusable widget | `core/design/PaperComponents.kt` (and document in UI-DESIGN) |
| Prompts shipped as defaults | `core/domain/PromptTemplates.kt` |

## Tests

Pure logic is unit-tested under `app/src/test` (see `OrientationTrackerTest` —
the 45° regression suite). New pure-logic classes should arrive with tests;
UI-heavy classes stay untested rather than brittle.

## Release & signing

The `release` CI job builds a **signed** release APK: the keystore lives only as
the `RELEASE_KEYSTORE_BASE64` repo secret (decoded into the runner's temp dir,
never checked out), and `app/build.gradle.kts` wires it in only when the CI env
vars are present. Local `assembleRelease` without them builds unsigned — by design.
The workflow also runs `apksigner verify` so a bad signature fails the build.
⚠️ The keystore is the release identity of the app: it exists ONLY on the owner's
machine (`KSCAN/signing/`) and in repo secrets. Back it up — losing it means
users must reinstall, and leaking it means anyone can ship fake updates.

## Dev environment

Android Studio (Ladybug+), JDK 17. Open the folder root (`new/`), let Gradle
sync from the wrapper. `local.properties` is auto-generated locally and gitignored.
