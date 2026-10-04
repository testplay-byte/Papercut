# 📖 Usage Guide — start to finish

Every step of the Papercut pipeline, from installing to printing a digitized page.

## 0 · Install

1. GitHub → this repo → **Releases** → latest → download
   **app-release.apk** and install it
   (allow "install unknown apps" for your browser/file manager).
2. Open Papercut.

## 1 · First launch — connect storage

1. Tap **Choose storage folder** and pick any folder you own (Documents works well).
2. Papercut creates `Papercut/Default/` inside it and remembers the location.
   Your scans are always plain files there — openable by any other app.

## 2 · First launch — connect your AI

Papercut ships presets but **no keys**. Add one:

1. Tap **Settings** (top-right gear on the Library) → **AI providers** → tap a
   provider tile (start with **Google AI Studio** — free Gemini keys:
   aistudio.google.com → *Get API key*).
2. **＋ Add API key** → give it a name, paste the key → **Save key**.
   ✅ The key is encrypted with the device keystore; only its name and masked
   form (`sk-3••••••••c4a9`) are ever shown.
3. Tap a **model** inside the provider (e.g. `gemini-2.0-flash`) → it becomes
   the active model (the ACTIVE badge moves).
4. Have several keys? Add them all — Papercut rotates them automatically:
   the least-used healthy key runs each job; a rate-limited key rests 60s,
   a rejected key rests 10 min (visible on the key card: *cooling — retry in 58s*).
   If every key is rejected the app says so instead of counting down.
5. **Local servers (Ollama / LM Studio)** need **no API key** — switch the
   provider on, tap **Edit**, and point **Base URL** at your PC's LAN address
   (e.g. `http://192.168.1.2:11434/v1`), then comma-separate the model names.

**Using your own server instead?** **＋ Add / manage providers** → paste the
base URL ending in `/v1` (e.g. `http://192.168.1.20:11434/v1` for Ollama),
comma-separate the model names, create, add a key, select it. Any
OpenAI-compatible vision endpoint works.

## 3 · Capture a document

1. Tap the big **＋ scan button** (bottom-right). Allow camera access once.
2. Frame the page inside the white corner brackets. Hold the phone however you
   like — the page stays upright in the file regardless of your tilt.
3. Pick the **Text / Notes** pill — this chooses the AI mode for the pages you
   shoot (Text = exact digital twin; Notes = structured summary).
4. Tap the shutter — your page lands in the **draft rail** at the bottom.
   Keep shooting multi-page documents without leaving the camera.
   - **Tap a thumbnail** → crop editor: drag the 4 orange corners over the
     page edges (live perspective correction), rotate, choose a filter
     (Magic / Original / Gray / B&W), ✓ to apply.
   - **Hold a thumbnail** → delete that page (an **Undo** snackbar follows — it
     only restores into the same session, never into a saved document).
5. Tap **Done** → name the document → **Save**. Closing early is safe: the
   Library shows a *Unsaved capture — Resume* banner until you save or discard.
6. With **Auto-digitize** on (Settings → Behavior), every saved page instantly
   enters the AI queue — watch the live counters in the document and Library.

## 4 · Library

- Documents are tiles with cover photo + page counts (`3p` · `2✓` digitized).
- **Search** by name, **sort** recent ↔ name, **folder chips** under the header,
  **＋** next to chips creates folders.
- **Long-press** a document to multi-select → batch **Digitize** or **Delete**;
  tap **⋮** on a tile for rename/delete with confirm.
- The top bar shows how many pages are working in the queue.

## 5 · Document hub — review, fix, restore

Open any document:

- The page shows its **corrected render** (crop + filter). Pinch to zoom,
  double-tap for 2.5×. The filmstrip below switches pages; badges show status.
- **Twin ⇄ Page** toggles between the photo and the digitized HTML — the view
  flips to the twin the moment *that page* finishes digitizing. Twins render at
  their authored 1080px width (the **Fit / 1:1** chip switches zoom).
- **Digitize** runs the AI on the current page (its stored Text/Notes mode);
  **Re-run** lets you type what went wrong (*"the table on the right was
  dropped"*) — the previous twin is kept automatically.
- When a backup exists: **Old twin** → **Keep new** / **Restore**.
- Working pages show one overlay with a **Cancel**; errors show the reason
  right under the badge (e.g. *Rate limited by provider (429)*).
- Page ops: **Move ◀ ▶**, **Rotate**, **＋ add pages** (top bar, back into the
  camera), delete (needs confirm).

## 6 · Export & share

From the document's **Export** sheet:

- **PDF — all pages** → corrected pages assembled into one PDF in
  **Downloads** (opens immediately).
- **PDF — share** → the same file straight to any app.
- **4K image** (digitized pages) → the twin rendered at A4/300dpi into
  **Pictures/Papercut** as PNG. MathJax formulas are fully typeset before
  capture — no blank crops.
- **Share** (action bar) → the current page's photo to any app.

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
