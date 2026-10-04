# ✂️ Papercut

**Turn physical pages into pixel-perfect digital twins with your own AI.**

Papercut is an Android app: point the camera at a book page, notes sheet, or
document — your chosen vision model rebuilds it as clean, searchable HTML
(formulas via MathJax, tables, even diagrams redrawn as SVG), stored next to
the original photo as plain files you own.

> Rebuilt from scratch (2026) from an earlier prototype called **KSCAN** — same
> core idea, everything else re-engineered: architecture, security, UI, AI layer.

## ✨ What it does

| Step | What happens |
|------|--------------|
| 📷 Capture | CameraX multi-page sessions — keep shooting, reorder/delete from the draft rail, nothing lost when you navigate away |
| ✂️ Correct | Per-page perspective crop (drag the 4 corners), rotate, filters (Magic / Original / Gray / B&W) — all non-destructive |
| 🗂️ Document | Each job is a *document* (N pages) in your folder, with a plain `meta.json` + `page-NN.jpg` — files you own |
| 🧠 Digitize | Any page → clean HTML "digital twin" via **any OpenAI-compatible vision model** you configure (Text twin or Notes mode) |
| 🔁 Re-run | Not happy? Re-run with a one-line complaint ("fix exactly this") — the old twin is kept as a backup you can restore |
| 📤 Export | One-tap **multi-page PDF**, 4K PNG of a twin, or share — right from the document hub |

## 🧩 Bring your own AI

No keys baked in, no vendor lock-in. Papercut speaks the OpenAI chat-completions
dialect, so it works out of the box with presets for:

- **Google AI Studio (Gemini)** · **OpenAI** · **OpenRouter** · **Groq** · **Ollama / LM Studio** (local!)
- ...or add any custom provider by pasting its **base URL** and model names.

Multiple API keys per provider with **smart rotation**: a rate-limited key rests
for 60s and the next one takes over; you can see which key is cooling and why in
Settings. All keys live in **keystore-encrypted storage** on the device — never
in your scan folder, never in the cloud.

## 📱 Screens

- **Library** — document grid with search, sort, folder chips, multi-select (batch digitize / delete), resume-draft banner
- **Scanner** — dark capture session: viewfinder, flash/mode pills, live draft rail (tap to edit, hold to delete)
- **Crop editor** — corner-drag perspective correction with live warp preview, rotate, filters
- **Document hub** — corrected page + filmstrip, per-page digitize/twin/versions, PDF / 4K / share
- **Settings** — providers & keys, prompts editor, behavior switches, storage

## 🚀 Getting started

1. **Install** — grab **app-release.apk** from this repo's [Releases page](https://github.com/testplay-byte/Papercut/releases)
   (rolling `latest` build, refreshed automatically on every push; nothing is
   ever built on a developer machine).
2. **First launch** — pick a storage folder (Papercut creates `Papercut/Default`
   inside it), then open **Settings → AI providers** and add an API key for a
   preset (or add a custom provider).
3. **Capture** — tap the scan button, shoot one or many pages, tap a thumbnail
   to crop/clean each one, then **Done** → name it → save.
4. **Digitize** — with *Auto-digitize* on, every saved page queues instantly
   (Text twin or Notes, per page); otherwise tap **Digitize** in the document.
5. **Review & export** — flip Page ⇄ Twin, re-run with feedback, keep or
   restore versions, export the whole document as one crisp PDF.

Full walkthrough with every step: **[docs/USAGE.md](docs/USAGE.md)**.

## 🏗️ How it's built

Kotlin · Jetpack Compose (Material 3 base, custom **bento design system**) ·
CameraX · SAF `DocumentFile` storage · Ktor + kotlinx-serialization (AI transport) ·
MVVM with a manual DI container — no framework needed to understand it.

Architecture map, data flow, and every design decision: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.
UI system (tokens, components, per-screen plans): **[docs/UI-DESIGN.md](docs/UI-DESIGN.md)**.

## 🔒 Privacy

- Photos and digitized pages stay in **your folder** on your device.
- API keys stay in **encrypted device storage**.
- The only network calls are the AI requests you trigger — to the provider you configured.
- This repository contains **no datasets, no keys, no personal data** — only code and docs.

## ⚖️ License

Not chosen yet (see tracker notes).

---

*Built by [testplay-byte](https://github.com/testplay-byte). Contributions
welcome — start with [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md).*
