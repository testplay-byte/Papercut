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
| 📷 Scan | CameraX scanner, single or batch mode, upright captures with smooth orientation handling |
| 🧠 Digitize | The page image + your prompt go to **any OpenAI-compatible vision model** you configure |
| 📄 Output | A full HTML "digital twin" saved as `IMG_*.html` beside the `IMG_*.jpg` |
| 🔁 Re-run | Not happy? Re-run with a one-line complaint ("fix exactly this") — old result is kept as a backup you can restore |
| 🖼️ Export | The HTML renders to a 4K A4 PNG for printing/sharing |

## 🧩 Bring your own AI

No keys baked in, no vendor lock-in. Papercut speaks the OpenAI chat-completions
dialect, so it works out of the box with presets for:

- **Google AI Studio (Gemini)** · **OpenAI** · **OpenRouter** · **Groq** · **Ollama / LM Studio** (local!)
- …or add any custom provider by pasting its **base URL** and model names.

Multiple API keys per provider with **smart rotation**: a rate-limited key gets
benched for 60s and the next one takes over; you can see which key is cooling
and why in Settings. All keys live in **keystore-encrypted storage** on the
device — never in your scan folder, never in the cloud.

## 📱 Screens

- **Library** — bento grid of folders with big tabular counts (scans / digitized)
- **Scanner** — dark focus mode, viewfinder grid, flash/mode/batch pills
- **Viewer** — photo ⇄ digitized HTML, version backup strip, export/share
- **Settings** — providers & keys, prompts editor, behavior switches, storage

## 🚀 Getting started

1. **Install** — grab the latest debug APK from this repo's GitHub Actions runs
   (releases are published here; nothing is built on the developer's machine).
2. **First launch** — pick a storage folder (Papercut creates `Papercut/Default`
   inside it), then open **Settings → AI providers** and add an API key for a
   preset (or add a custom provider).
3. **Scan** — hit the center button, frame the page, capture. With *Auto-digitize*
   on, the AI queue starts immediately; status shows on every scan card.
4. **Review** — open the scan: check the twin, re-run with feedback if anything
   is off, keep new or restore previous.

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
