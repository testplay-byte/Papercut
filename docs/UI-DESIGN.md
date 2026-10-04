# 🎨 UI Design — the Papercut Bento System (dark)

This app has a real design language now. The old prototype mixed hand-copied
widgets, raw hex colors in every screen, an inert Material theme, and focus
outlines/ripples on tap. All of that lives in code, not vibes: everything below
is implemented in `core/design/` and enforced by "no raw hex / no copy-paste
widgets outside design" review rules.

## Principles

1. **Dark bento.** Owner-chosen identity: near-black canvas, raised charcoal
   tiles with hairline borders (shadows read poorly on dark), big rounded
   corners. Content lives in tiles — Library grid, capture rail, settings
   sections. Simple, clean, professional density: minimal chrome, short copy.
2. **One accent.** Vivid orange-red (`#FF5A2E`) marks *action and value*:
   active states, key numbers, the scan FAB, the cut corner of the logo.
   Ink does the talking everywhere else.
3. **Tabular numbers.** Counts/metrics use `tnum` font features (via
   `TextStyle`) — digits never jitter when values change.
4. **Scale, not ripple.** Pressing anything shrinks it ~3%. No focus borders,
   no ripples, no highlights — the "weird border on tap" is designed out at the
   `Modifier.tap` / `pressScale` level so it cannot come back.
5. **One dark family everywhere.** v2 unified: scanner, editor and document hub
   share the same near-black surfaces as the rest of the app — the product now
   *looks* like one professional scanning tool top to bottom.

## Tokens (`PaperTokens.kt`)

| Token | Value | Used for |
|---|---|---|
| `Canvas` | `#0E0E11` | page background |
| `Tile` / `TilePressed` / `TileSunken` | `#1A1A1F` / `#232329` / `#141418` | surfaces & insets |
| `TileBorder` | `#2A2A31` | hairline tile separation |
| `Ink` / `InkSecondary` / `InkFaint` | `#F2F2F5` / `#9D9DA6` / `#8B8B97` | text ramp (≥4.5:1) |
| `Accent` / `AccentSoft` | `#FF5A2E` / 20% wash | action & selection |
| `Success/Warning/Error` | green/amber/red | status only |
| `PaperRadii` | tile 22 / small 14 / pill 999 | corners |
| `PaperGap` | 6/10/16/24/32 | spacing |
| `PaperMotion` | 120/220/320/200 ms | press/swap/enter/exit |

## Atoms (`PaperComponents.kt` / `PaperChrome.kt`) — the reused-everything set

- `BentoTile` — bordered rounded surface; optional `selected` (accent wash) and `onClick`
- `StatNumber` + `StatCaption` — the big-number/uppercase-label pair
- `SegmentedPill<T>` — typed options, sliding active capsule; used for every toggle (scan mode, versions…)
- `SmoothSwitch` — animated track+knob, built on tokens
- `StatusBadge` — Queued / Working / Digitized / Failed (exactly one per item)
- `ScreenHeader` / `BrandHeader` — title + optional caption, brand mark
- `IconAction` / `ActionBtn` / `FilterChip` — press-scale icon & label buttons
- `ConfirmSheet` — in-app destructive confirmation (never raw system dialogs for deletes)
- `MessageBus` + snackbar — app-wide transient feedback (no toasts)
- `Modifier.pressScale` + `Modifier.tap` — interaction primitives

## Per-screen plans (implementation notes for future changes)

**Onboarding (Welcome)** — one centered tile, ✂ mark, honest copy:
*what happens + why files stay yours*. Single step; no wizard chrome.

**Library** — `ScreenHeader` + two stat tiles (Total scans / Digitized) + folder
tiles (cover image, name, `done/total`, big count in accent) + dashed-feel
`TileSunken` "New folder" tile. Long-press opens manage sheet (rename/delete;
Default is protected in the repository, not just the UI). Floating ＋ scan FAB.

**Scanner (capture session)** — top row: close · `Text|Notes` mode pill · flash
cycle button. Canvas viewfinder: rule-of-thirds + corner brackets (pure
drawing). Bottom: big shutter (haptic tick, spinner while writing) and, once
pages exist, the **draft rail**: count + thumbnail strip (tap → editor, hold →
delete, orange dot = edited) + Done. Save sheet names the document (or confirms
append). Exiting with pages posts a *Draft kept* snackbar; Library then shows a
**Resume** banner until saved/discarded. Camera opens via ListenableFuture —
never blocks the UI thread.

**Crop editor** — page photo with a dimmed outside area and 4 draggable orange
corner handles (homography-corrected live preview via ✓-toggle "Preview");
rotate button, full-page reset, filter chips (Magic/Original/Gray/B&W). Edits
buffer in the VM — ✓ applies, back discards, hint text says so.

**Document hub** — rendered (corrected) page with pinch/double-tap zoom;
filmstrip of numbered thumbnails with live status dots; one action row:
`Page ⇄ Twin`, Digitize, Re-run (with correction note + mode), Export, Share;
secondary strip: Move ◀ ▶, Rotate, delete (confirmed), and `Keep new /
Restore` when a twin backup exists. Auto-flips to the twin the moment
digitizing finishes; errors show their reason under the header badge.
Working = one scrim overlay with a real Cancel. Top-right ＋ appends more pages.

**Settings** — three sections: *AI providers* (tiles: name, key count, masked
sample, ACTIVE marker), *Prompts*, *Behavior* (auto-digitize switch, default
mode), *Storage* (folder tile re-picks SAF tree). Real version from
`BuildConfig`, short privacy note. Provider detail: selectable model chips,
key cards with masked secret + usage + *cooling — retry in Ns*, add-key
dialog, custom-provider delete behind confirm.

**Library** — brand header (mark + name, live "N working"), document grid
(cover, `Np`, `M✓` digitized), search field + sort toggle, folder chips +
new-folder chip, long-press multi-select (batch Digitize / Delete), ⋮ manage
with confirm, empty state with the mark, accent ＋ scan FAB, resume-draft banner.

**Motion** — presses 120ms; selection swaps 220ms; sheets/dialogs standard
fade. Motion confirms input and tracks state — never decorative.
