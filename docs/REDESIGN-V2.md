# Papercut v2 — Product Redesign Spec (2026-10-04, field-report round 3)

Verdict from the user: the app "is not a professional scanning application,
has no features at all, no quality of life." This is the redo plan. Every
screen is re-thought around ONE product truth: **Papercut digitizes documents.**

## What a pro scanner does (and Papercut now does too)

Adobe Scan / CamScanner / Genius Scan baseline → Papercut parity:

1. **Capture session** — keep the camera open, shoot multiple pages, review
   strip at the bottom, retake/reorder/delete before saving.
2. **Document-centric storage** — a Document = N pages
   (`Papercut/<Folder>/<Doc>/page-01.jpg …`) with editable metadata, not loose
   scans. (Legacy single scans auto-appear as 1-page documents.)
3. **Crop & perspective correction** — per page: drag 4 corner handles, rotate,
   preview. Non-destructive (crop stored as normalized quad, warp applied at
   render/export).
4. **Filters** — Original / Magic Color / Grayscale / Black & White, per page,
   live preview. Non-destructive.
5. **Multi-page PDF export** — one tap: filtered pages → PDF → share/save
   (android.graphics.pdf, no dependency). Plus the existing high-res PNG export
   for AI-digitized HTML pages.
6. **AI digitize (our edge)** — per page, with the cleaned (cropped+filtered)
   image as input — better results than raw photos. Re-improve + one backup.
7. **Library done right** — search, sort, multi-select (batch digitize / delete
   / export), rename, page-count badges, cover from first page.
8. **Quality of life** — haptic feedback on key actions, snackbar instead of
   toasts everywhere, in-app confirmation sheets, progress with counts,
   "add pages" to an existing document, back-press protection on capture.

## What we fix structurally

- Viewer is now a **document pager** (page carousel + filmstrip thumbnails +
  per-page status chip + per-page actions), not a photo screen.
- Scanner gets a **DraftSession** shared store so pages/crops survive
  navigation to the crop editor (VM-per-route can't hold drafts).
- All image processing (warp/filters/PDF) is **pure, testable logic** —
  unit-tested in CI before it ever touches a device.
- Dark bento stays (user-approved) but with professional density: real app
  bars, tighter grids, fewer captions, one action hierarchy.

## Screen inventory (v2)

| Route | Screen | Core content |
|---|---|---|
| `library` | Library | search bar, sort chip, doc grid, multi-select mode, scan FAB |
| `folder/{f}` | FolderScreen | docs in one folder (same grid, scoped) |
| `scanner/{f}/{doc?}` | Scanner | live camera, mode pills, capture review strip, save/name sheet |
| `editor/{draftId}` | PageEditor | perspective corner-drag crop, rotate 90°, filter picker, before/after |
| `document/{f}/{d}` | DocumentScreen | page pager + filmstrip, per-page AI/filter/crop, page ops (rotate/reorder/delete), export sheet (PDF/image/HTML4K/share) |
| `settings` `provider/{id}` `prompts` | kept, polished | provider tiles → keys/models, prompts, storage |

## Data model (v2)

```
Papercut/<Folder>/<Doc Name>/
  meta.json           DocumentMeta { name, createdAt, pages[] }
  page-01.jpg         ORIGINAL capture (crop/filter/rotation stored in meta = non-destructive)
  page-01.html        current AI digitization (per page)
  page-01.prev.html   one AI backup (per page)
```
Legacy `IMG_*.jpg` + `*improve.html` files in folders list as 1-page documents
read-only-bridge (same page operations apply; new pages saved in doc dir).

## Rendering pipeline (per page)

`decode(sampled) → perspective warp (stored quad) → rotate (0/90/180/270) →
filter (ColorMatrix + ops)` — cached in the Document VM (max 4 pages warm),
all on Dispatchers.Default. AI uses the same rendered bitmap → the model now
sees clean, cropped, high-contrast pages.
