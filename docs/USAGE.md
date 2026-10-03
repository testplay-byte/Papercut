# 📖 Usage Guide — start to finish

Every step of the Papercut pipeline, from installing to printing a digitized page.

## 0 · Install

1. GitHub → this repo → **Releases** → latest → download
   **papercut-release.apk** and install it
   (allow "install unknown apps" for your browser/file manager).
2. Open Papercut.

## 1 · First launch — connect storage

1. Tap **Choose storage folder** and pick any folder you own (Documents works well).
2. Papercut creates `Papercut/Default/` inside it and remembers the location.
   Your scans are always plain files there — openable by any other app.

## 2 · First launch — connect your AI

Papercut ships presets but **no keys**. Add one:

1. Bottom bar → **Settings** → **AI providers** → tap a provider tile
   (start with **Google AI Studio** — free Gemini keys: aistudio.google.com → *Get API key*).
2. **＋ Add API key** → give it a name, paste the key → **Save key**.
   ✅ The key is encrypted with the device keystore; only its name and masked
   form (`sk-3••••••••c4a9`) are ever shown.
3. Tap a **model** inside the provider (e.g. `gemini-2.0-flash`) → it becomes
   the active model (the ACTIVE badge moves).
4. Have several keys? Add them all — Papercut rotates them automatically:
   the least-used healthy key runs each job; a rate-limited key rests 60s,
   a rejected key rests 10 min (visible on the key card: *benched 58s*).

**Using your own server instead?** `＋ Add / manage providers` → paste the
base URL ending in `/v1` (e.g. `http://192.168.1.20:11434/v1` for Ollama),
comma-separate the model names, create, add a key, select it. Any
OpenAI-compatible vision endpoint works.

## 3 · Scan

1. Tap the big **scan button** (bottom-center). Allow camera access once.
2. Frame the page inside the white corner brackets. Hold the phone however you
   like — the page stays upright in the file regardless of your tilt.
3. Pick the pill options:
   - **Text** → digital twin (exact layout, tables, formulas — slower, better)
   - **Notes** → structured study summary (faster)
   - **Single / Batch** — batch keeps you in the camera for multi-page capture
   - ⚡ flash: off → on → auto
4. Press the shutter. With **Auto-digitize** on (Settings → Behavior), the page
   immediately enters the AI queue (pill: *Digitizing N…*).
   - Single mode: you jump straight to the Viewer.
   - Batch mode: keep shooting; tap the 🖼 icon when done.

## 4 · Library

- Folders are bento tiles with cover photo + counts. **Long-press** = rename/delete.
- **＋ New folder** tile; **＋ scan** button captures into the current folder.
- Stat tiles up top show **total scans** and **digitized** across everything.

## 5 · Viewer — review, fix, restore

Open any scan:

- Toggle **Photo ⇄ Digital** to compare the twin against the original page.
- **Re-improve** → optional note (*"the table on the right was dropped"*) →
  the AI re-runs with your note appended; the previous HTML is kept automatically.
- When a backup exists, the strip **Current / Previous** + **Keep new** /
  **Restore previous** resolves it in one tap.
- While queued/working you see a single clean overlay — cancel-safe, error cards
  show the reason (e.g. *Rate limited by provider (429)*).

## 6 · Export & share

- **Export 4K** → the HTML renders at A4/300dpi and lands in
  **Pictures/Papercut-export** as PNG (print-quality). MathJax formulas are
  fully typeset before capture — no blank crops.
- **Share** → hands the digitized `.html` to any app (browser, email, Drive);
  the file leaves via a private share cache, your library folder is never exposed.

## 7 · Prompts (make the AI yours)

Settings → **Prompts** → switch **Text twin / Notes**, edit, **Save** (or
**Reset to default**). The Text default is the full digital-typesetter spec:
fixed-width container, locked-block justification, two-column solution grids,
staircase SVG figure wrapping, MathJax everywhere, and a strict
*"every number in the book must appear in your code"* audit.

## 8 · Changing storage or removing keys

- Settings → **Storage** → pick a different folder (files are NOT migrated —
  move them yourself with any file manager, Papercut reads the same layout).
- Settings → provider → key card → **Remove** (also purges the secret).

## Troubleshooting

| Symptom | Cause & fix |
|---|---|
| *No AI provider configured* | Settings → pick a provider, model, key |
| *Provider rejected the API key (401/403)* | key typo/expended → add the right one |
| *Every key is cooling down* | hit rate limits → wait ~60s or add more keys |
| *Model returned no content* | pick a **vision-capable** model (text-only models can't see pages) |
| *Provider error (404)* | base URL missing the `/v1` suffix |
| Blank export PNG | very long page — retry Export 4K (auto half-scale fallback) |
| Camera black screen | deny-then-regrant in system app settings, reopen scanner |
