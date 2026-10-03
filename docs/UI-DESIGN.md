# 🎨 UI Design — the Papercut Bento System

This app has a real design language now. The old prototype mixed hand-copied
widgets, raw hex colors in every screen, an inert Material theme, and focus
outlines/ripples on tap. All of that lives in code, not vibes: everything below
is implemented in `core/design/` and enforced by "no raw hex / no copy-paste
widgets outside design" review rules.

## Principles

1. **Bento grid.** Content lives in rounded tiles (24dp) floating on an iOS-gray
   canvas. Big things big, small things in mini-cards. Nothing edge-to-edge dense.
2. **One accent.** Vivid orange-red (`#FF4F26`) marks *action and value*: active
   states, key numbers, the scan FAB. Ink does the talking everywhere else.
3. **Tabular numbers.** Every count/metric uses bold 34sp with `tnum` —
   digits never jitter when values change. (`StatNumber`)
4. **Scale, not ripple.** Pressing anything shrinks it ~3%. No focus borders,
   no ripples, no highlights — the "weird border on tap" is designed out at the
   `Modifier.tap` / `pressScale` level so it cannot come back.
5. **Two modes.** Light bento (Library, Settings) ↔ Night focus (Scanner,
   Viewer) — same geometry, inverted palette tokens (`Night*` colors).

## Tokens (`PaperTokens.kt`)

| Token | Value | Used for |
|---|---|---|
| `Canvas` | `#EDEDF0` | page background |
| `Tile` / `TilePressed` / `TileSunken` | `#FAFAFC` / `#E9E9ED` / `#E2E2E7` | surfaces & insets |
| `Ink` / `InkSecondary` / `InkFaint` | `#1C1C1E` / `#6E6E73` / `#AEAEB2` | text ramp |
| `Accent` / `AccentSoft` | `#FF4F26` / `#FFE3DB` | action & selection |
| `Success/Warning/Error` | green/amber/red | status only |
| `NightCanvas/Tile/Ink…` | dark set | scanner/viewer |
| `PaperRadii` | tile 24 / small 14 / pill 999 | corners |
| `PaperGap` | 6/10/16/24/32 | spacing |
| `PaperMotion` | 120/220/320/200 ms | press/swap/enter/exit |

## Atoms (`PaperComponents.kt`) — the reused-everything set

- `BentoTile` — shadowed rounded surface; optional `selected` (accent-soft) and `onClick`
- `StatNumber` + `StatCaption` — the big-number/uppercase-label pair
- `SegmentedPill<T>` — typed options, sliding active capsule; used for every toggle (flash mode, scan mode, versions…)
- `SmoothSwitch` — animated iOS track+knob, built on tokens
- `StatusBadge` — Queued / Working / Digitized / Failed (exactly one per item)
- `ScreenHeader` — title + subtitle, consistent rhythm
- `Modifier.pressScale` + `Modifier.tap` — interaction primitives

## Per-screen plans (implementation notes for future changes)

**Onboarding (Welcome)** — one centered tile, ✂ mark, honest copy:
*what happens + why files stay yours*. Single step; no wizard chrome.

**Library** — `ScreenHeader` + two stat tiles (Total scans / Digitized) + folder
tiles (cover image, name, `done/total`, big count in accent) + dashed-feel
`TileSunken` "New folder" tile. Long-press opens manage sheet (rename/delete;
Default is protected in the repository, not just the UI). Floating ＋ scan FAB.

**Scanner (Night)** — reference taken from the old app's best screen, rebuilt:
top row = flash round-button + `Text|Notes` pill; below it `Single|Batch` pill;
live "Digitizing N…" pill only when the queue is busy. Canvas viewfinder:
rule-of-thirds + corner brackets (pure drawing, no assets). Shutter ring with
white core (checkmark after single capture, spinner while processing). Icons
softly counter-rotate with the smoothed tilt angle.

**Viewer (Night)** — photo ⇄ HTML via bottom chips; version strip
`Current|Previous` + `Keep new / Restore previous` appears only when a backup
exists; `StatusBadge` in the header; one scrim overlay while processing —
centered spinner + plain-language message, never two stacked.

**Settings** — three titled sections: *AI providers* (tiles: name, key count,
masked sample, ACTIVE marker), *Prompts* (one tile → editor), *Behavior*
(auto-digitize switch, default-mode pill), *Storage* (folder tile re-picks SAF
tree). Real version in the header (`BuildConfig`), privacy note at the bottom —
no dead links, no fake build numbers.

**Provider detail** — model list as selectable chips; key cards with masked
secret, usage count and live *benched Ns* cooldown; add-key dialog (masked
field); custom providers get delete; built-ins get re-point/reset.

**Prompts editor** — mode pill + one large multiline field; **draft is
saveable-local** (typed text never vanishes on background settings writes —
the old app's silent-reset bug is designed out by using `rememberSaveable`
keyed to mode only); Save / Reset-default rows with live char count.

**Bottom bar** — pill-shaped floating tile: Library · (raised accent scan FAB)
· Settings. Slides away on focus screens. Never on Scanner/Viewer.

**Motion** — presses 120ms; selection swaps 220ms; screens enter with
`AnimatedVisibility` slide (bar) and standard fade (nav). No parrot-spinning
decorative animation: motion exists to confirm input and track state.
