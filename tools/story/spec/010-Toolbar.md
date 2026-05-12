# Story — Toolbar Surface

> The chrome-level toolbar that exposes every registered `reg-mode`
> tuple as a toggle chip. Storybook 8's `theme` / `viewport` / `locale`
> toolbar, refactored to re-frame2 idioms: one registry (`:mode`), one
> shell-state slot (`:active-modes`), one persistence key. The
> downstream surface rf2-p0mv specifies; the contract rf2-wk41
> (Backgrounds + Viewport addon UX) builds on.

## Why a dedicated toolbar surface

`reg-mode` (per [`001-Authoring.md`](001-Authoring.md) §reg-mode +
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §reg-mode-in-v1) ships
in v1 as a saved tuple of global args that deep-merges into a
variant's effective args. The Stage-4 controls panel already exposes
the modes as togglable chips — but only in the **right inspector
pane**, mixed in with per-variant controls. Storybook ships the same
primitive at the **chrome level**: a single toolbar that floats above
the whole shell so the active mode-set is visible regardless of which
variant or workspace is selected.

The toolbar surface is **independent of the mode-tabs primitive
([`007-Mode-Tabs.md`](007-Mode-Tabs.md))** — mode-tabs switch a
variant's *render mode* (`:dev` / `:docs` / `:test`); the toolbar
toggles *global args* deep-merged into every variant's effective
args. The two axes are orthogonal and visually separated: mode-tabs
sit **inside** the main pane (per-variant); the toolbar sits **above**
the three-pane shell (chrome-wide).

The controls-panel `mode-picker` is **superseded** by the toolbar.
The `(controls/mode-picker)` component is deleted by the impl bead
(rf2-impl-p0mv) — there is no per-variant mode-set, so a chrome-level
surface is the right home. The controls panel keeps args / decorator
sections only.

## Placement in the shell chrome

The toolbar renders as a horizontal strip **above** the three-pane
layout, between the (future) chrome header and the sidebar / canvas /
inspector row:

```
┌──────────────────────────────────────────────────────────────┐
│ [chip] [chip] [chip] [chip]   ●dark ●mobile   [reset]        │  ← toolbar
├──────────┬───────────────────────────────────┬───────────────┤
│ sidebar  │ Canvas │ Docs │ Tests │           │ controls      │  ← mode-tabs strip
│          ├───────────────────────────────────┤ ───────────── │
│ stories  │                                   │ scrubber      │
│ tags     │   <selected mode's pane>          │ ───────────── │
│ ws       │                                   │ trace         │
└──────────┴───────────────────────────────────┴───────────────┘
```

The strip renders inside the shell's root container, **before** the
flex-row that holds `[sidebar] [main] [right-panel]`. It always
renders — empty (with a single "no modes registered" placeholder) if
the registry has no `:mode` entries. Workspaces and single-variant
selections both honour the toolbar's `:active-modes` (workspaces
already pass `:active-modes` through to each variant render — see
[`003-Render-Shell.md`](003-Render-Shell.md) §Workspace layouts).

A `<header>` landmark wraps the strip with `role="toolbar"` and
`aria-label="Story modes"` so screen-reader users can jump to it via
the landmark navigator (mirrors the inspector pane's `<aside>` in
rf2-xc65).

## Data source

The toolbar reads `(registrar/handlers :mode)` on every render — a
fresh registry snapshot per `re-frame.story.ui.state/registry-
snapshot`. Newly-registered modes appear immediately; hot-reloaded
modes pick up via the existing fingerprint poll (per
[`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle). No
caching layer.

The strip's left-to-right chip order is **alphabetic by mode id** —
stable across renders and across machines, matching the existing
controls-panel `mode-picker` ordering. v1.1 may add an explicit
`:order` slot on `reg-mode` if that ordering proves wrong; for v1
alphabetic-by-id is the lock.

## Mode tuple — confirmed shape

A `reg-mode` registration's body is a `:rf/mode` schema-validated
map:

```clojure
{:doc  "Optional human description."          ; :string, optional
 :args {<arg-key> <value>}}                   ; ArgMap, required
```

The "mode tuple" the toolbar consumes is the registry pair
`[mode-id body]` where `body` is the above map. The toolbar reads
`mode-id` for the chip label (rendered as `(str mode-id)`), `:doc` for
the chip's `title=` tooltip, and ignores `:args` itself — `:args` is
deep-merged downstream by `re-frame.story.args/resolve-args`.

### Optional grouping — `:axis` (v1)

A `reg-mode` body MAY carry an optional `:axis` keyword that groups
modes for the toolbar's chip layout:

```clojure
(rf/reg-mode :Mode.app/dark-theme
  {:doc  "Dark theme."
   :axis :theme
   :args {:theme :dark}})

(rf/reg-mode :Mode.app/light-theme
  {:doc  "Light theme."
   :axis :theme
   :args {:theme :light}})

(rf/reg-mode :Mode.app/mobile-viewport
  {:doc  "Mobile viewport."
   :axis :viewport
   :args {:viewport :mobile}})
```

When `:axis` is present, the toolbar renders one labelled group per
axis with **single-select-within-axis** semantics (toggling
`:Mode.app/dark-theme` deactivates `:Mode.app/light-theme`).
Modes lacking `:axis` render in a trailing un-grouped section with
**multi-select** semantics (any subset can be active simultaneously).

`:axis` is opt-in; existing `reg-mode` bodies (e.g. the
`counter_with_stories` example's `:Mode.app/dark` / `:Mode.app/light`)
continue to work — they fall into the un-grouped trailing section
with multi-select semantics until the author opts into axes.

The `:rf/mode` schema (in
[`schemas.cljc`](../src/re_frame/story/schemas.cljc)) is extended to
accept the optional `:axis` keyword. The schema change is additive —
unchanged bodies remain valid.

### Rationale for `:axis`

Storybook ships dedicated `theme` / `viewport` / `locale` selectors;
each is a single-select axis (one viewport at a time). Story's
`reg-mode` is more general — a mode is just a saved args tuple —
but the **toolbar UX** needs the single-select-within-axis affordance
or `theme:dark` and `theme:light` can both be active, producing an
ambiguous deep-merge (last-declared wins, which is invisible to the
user).

Rather than registering separate `reg-theme-mode` / `reg-viewport-
mode` macros (which would proliferate registries against the
**no-new-framework-registries** principle from
[`Principles.md`](Principles.md)), `:axis` is one optional slot that
lets a single `:mode` registry host both single-select and
multi-select chips. This is the locked answer to the Storybook-
parallels question — `theme` / `viewport` / `locale` are
`reg-mode` registrations with `:axis :theme` / `:axis :viewport`
/ `:axis :locale`, not separate primitives.

## Chip rendering and interaction

### Chip visual contract

Each chip is a `<button role="button">`:

| State    | Background | Foreground | `aria-pressed` |
|----------|-----------:|-----------:|---------------:|
| Inactive | `#37373d`  | `#cccccc`  | `false`        |
| Active   | `#0e639c`  | `white`    | `true`         |
| Hover    | `#454547`  | `#cccccc`  | (unchanged)    |

Reuses the `:chip` / `:chip-active` styles from
`re-frame.story.ui.controls/styles` to keep the chrome chip vocabulary
consistent across the controls panel (decorator list) and the
toolbar.

The chip label is `(str mode-id)` truncated at 28 chars with a CSS
`text-overflow: ellipsis`; the full id + `:doc` tooltip lives on
`title=`.

### Selection semantics — by axis

| `:axis` slot           | Selection mode            | Toggle action                                         |
|------------------------|---------------------------|-------------------------------------------------------|
| Present (e.g. `:theme`)| Single-select within axis | Adds the toggled mode; removes any other mode in same axis |
| Absent                 | Multi-select              | Flips the toggled mode on/off independently           |

Implementation:

```clojure
;; Single-select within axis (pure data → data):
(defn toggle-mode
  "Toggle `mode-id` against the current `active-modes` vector.
  Honors `:axis` semantics — single-select within axis, multi-select
  otherwise. Returns the new active-modes vector."
  [active-modes mode-id]
  (let [body (registrar/handler-meta :mode mode-id)
        axis (:axis body)]
    (cond
      ;; Currently active → deactivate (regardless of axis).
      (some #(= % mode-id) active-modes)
      (vec (remove #(= % mode-id) active-modes))

      ;; Axis-grouped → drop siblings sharing the axis, then add.
      axis
      (let [siblings (set (filter
                            (fn [mid]
                              (= axis (:axis (registrar/handler-meta :mode mid))))
                            active-modes))]
        (conj (vec (remove siblings active-modes)) mode-id))

      ;; Un-grouped → multi-select, just append.
      :else
      (conj (vec active-modes) mode-id))))
```

Pure data → data; JVM-testable. Lives in `re-frame.story.ui.state`
alongside `set-active-modes` (which the impl bead generalises to
delegate to `toggle-mode`).

### Reset affordance

A `[reset]` button at the right edge of the strip clears
`:active-modes` to `[]`. Renders only when at least one mode is
active. Mirrors the controls panel's `[reset overrides]` button shape.

## State location

The active mode-set lives in **shell-state `:active-modes`** — the
existing `re-frame.story.ui.state` slot
([`state.cljc`](../src/re_frame/story/ui/state.cljc)). No new slot is
added; this spec **reuses** the slot that the canvas, multi-substrate,
docs, test-mode, controls panel, and share-URL builders already
consume.

That makes the toolbar a **read/write surface against a slot that
seven render paths already read**:

- `canvas/canvas` — passes `:active-modes` to `run-variant`.
- `multi_substrate/render` — same.
- `workspace/workspace-view` — same.
- `docs/docs-view` — uses `:active-modes` for the args-resolved view.
- `test_mode/test-view` — same.
- `controls/args-editor` — same (resolves args for display).
- `share/build-share-url` — encodes `:active-modes` into the URL.

No reroute needed. The impl bead deletes the controls-panel
`mode-picker` and points its previous toggle path at the toolbar's
`toggle-mode` helper.

### Persistence — chrome-wide localStorage

The toolbar's selection is **chrome-wide** (one selection for the
whole shell instance), so persistence is a single localStorage key —
**not** per-variant (unlike mode-tabs, which is per-variant per
[`007-Mode-Tabs.md`](007-Mode-Tabs.md)):

```
re-frame.story/active-modes   →   "[:Mode.app/dark :Mode.app/mobile]"
```

The value is a `pr-str`-encoded vector of mode ids; `read-string` on
load. Mirrors the mode-tabs `safe-local-storage` defensive pattern —
unparseable / unreadable storage silently degrades to in-memory only.

A `hydrate-modes-from-storage!` runs once at shell mount (idempotent;
only writes the slot when it is the default empty vector). Mode ids
that no longer resolve at the registrar (stale storage after a
`reg-mode` rename) are silently dropped at hydrate time — the toolbar
shows an unknown chip for at most one render cycle before re-render
prunes it.

### URL deep-link — already wired

`re-frame.story.share/build-params` already encodes `:active-modes`
into the share URL as the `modes=` query param (per
[`share.cljc`](../src/re_frame/story/share.cljc) lines 65-96). The
inverse hydrate (parse `?modes=...` on shell mount, seed
`:active-modes`) is implementation scope for the impl bead — the
contract from this spec is "if the URL carries `modes=`, the toolbar
opens with those chips active." The URL takes precedence over
localStorage on hydrate (last-shared wins over last-used).

## Interaction with Storybook addons — confirmed mappings

The Storybook 8 toolbar primitives map onto `reg-mode` as follows:

| Storybook addon | re-frame2 form                                       | Axis            |
|-----------------|------------------------------------------------------|-----------------|
| Theme switcher  | `(reg-mode :M.theme/<name> {:axis :theme :args {:theme ...}})` | `:theme`   |
| Viewport addon  | `(reg-mode :M.viewport/<name> {:axis :viewport :args {:viewport ...}})` | `:viewport` |
| Locale switcher | `(reg-mode :M.locale/<name> {:axis :locale :args {:locale ...}})` | `:locale` |
| Backgrounds     | `(reg-mode :M.bg/<name> {:axis :background :args {:background ...}})` | `:background` |

Each is a **registered mode tuple**, not a separate primitive. rf2-wk41
(Backgrounds + Viewport addon UX) ships canonical `reg-mode`
registrations for the four standard viewports (mobile / tablet / desktop
/ ultra-wide) and the three standard backgrounds (light / dark /
transparent), bundled as opt-in registrations a project pulls in
with `(re-frame.story.modes.standard/register-all!)`. That's a v1.1
UX-polish bead; the toolbar surface this spec specifies is v1.

## `with-mode` accessor — programmatic + reactive

Three callers need to read the active mode-set:

1. **Story code at registration time** — the `:args` deep-merge handles
   this; nothing to expose. The variant body declares `:modes #{...}`
   for the **cells** it wants iterated; the toolbar overlays a runtime
   set on top.

2. **Story body view code** — a variant's `:component` may want to
   branch on the active theme/viewport without re-binding via `:args`.
   The framework cofx surface (already declared in
   [`API.md`](API.md) §Coeffects) ships:

   ```clojure
   ;; Registered by re-frame.story at install-canonical-vocabulary!
   :story/active-modes   → vector of mode ids currently active
   :story/active-args    → deep-merged args from all active modes
   ```

   The existing `:story/mode` cofx (per
   [`API.md`](API.md) line 96 — "the active mode for the variant")
   is **generalised** to `:story/active-modes` (plural) to match the
   toolbar's multi-select reality. Single-mode call sites read the
   first element. Schema change in `:rf/cofx` registration; documented
   in [`API.md`](API.md) update.

3. **Reactive view code** — a Reagent view can subscribe via a
   pre-registered subscription (Stage 6 picks this up; spec'd here as
   a forward contract):

   ```clojure
   @(rf/subscribe [:story/active-modes])
     => [:Mode.app/dark :Mode.app/mobile]

   @(rf/subscribe [:story/active-args])
     => {:theme :dark :viewport :mobile ...}
   ```

   Backed by a reaction off `state/shell-state-atom` — the toolbar's
   writes propagate through Reagent's signal graph the same way
   sidebar / canvas selection does. JVM-side: same call returns the
   plain-atom-deref view (no reaction wrapper).

The spec does **not** ship a `with-mode` macro — a macro adds zero
ergonomics over the cofx / sub surface, and a macro on top of a
pure-data registration body would breach the EDN-first principle.
Story body code reads modes via cofx (in event handlers) or sub (in
views).

## Test surface

The Playwright spec
`examples/reagent/counter_with_stories/counter_with_stories.spec.cjs`
gains a `section 5: toolbar` block exercising:

- The strip renders above the three-pane layout.
- All registered modes from `stories.cljs` appear as chips
  (`:Mode.app/dark`, `:Mode.app/light`).
- Clicking a chip flips its `aria-pressed` attribute.
- The chip's active state survives navigation across variants
  (chrome-wide, not per-variant).
- Reloading the page restores the active-mode set from
  localStorage.
- `?modes=:Mode.app/dark` query string opens the page with the dark
  chip pre-selected.

The `counter_with_stories` example gets one additional `reg-mode`
registration with `:axis :theme` to exercise the single-select
semantics; the existing un-axis-tagged modes exercise the multi-
select path.

Selectors:

- The strip: `[data-test="story-toolbar"]`
- Each chip: `[data-toolbar-mode="<mode-id>"]`
- The reset button: `[data-test="story-toolbar-reset"]`

## Visual style

Strip background `#252526`, 1px solid `#444` bottom border, 8px
vertical / 12px horizontal padding, 6px gap between chips. Axis group
labels: 10px uppercase `#9a9a9a` with 4px right-margin. Matches the
existing controls-panel and mode-tabs aesthetic. 32px total strip
height — small enough not to dominate the canvas, large enough to
support 11px chip text.

## Out-of-scope at this surface

- **Backgrounds + viewport addon UX** — owned by rf2-wk41. This spec
  defines the toolbar substrate; rf2-wk41 ships the canonical
  registrations and any addon-specific affordances (e.g. a viewport-
  resize overlay).
- **Workspace-local toolbar** — workspaces inherit the chrome's
  `:active-modes` for now; if workspaces want their own toolbar
  scoping it lands as a v2 follow-up.
- **Toolbar customisation API** — projects cannot register their own
  chip widgets in v1; the chip is uniform (`button` with mode id
  label). If the design-token panel (v1.1) needs richer chips, the
  toolbar spec gets extended at that point.
- **Keyboard shortcuts** — chip activation via keyboard works (tab
  + enter); dedicated hotkeys (`t` for theme, `v` for viewport) are
  v1.1.

## Foundational status

Per the cross-reference chain:

- **rf2-p0mv (this spec)** — toolbar substrate.
- **rf2-wk41** — Backgrounds + Viewport addon UX; ships canonical
  `reg-mode` registrations bundled as `re-frame.story.modes.standard`,
  consumes the toolbar.

The toolbar's contract guarantees:

1. The strip renders above the three-pane layout whenever the shell
   is mounted.
2. Every registered `:mode` appears as a chip in alphabetic order
   (within its `:axis` group, if any).
3. Selection writes to shell-state `:active-modes`, which the canvas,
   workspace, controls, docs, test-mode, multi-substrate, and share
   modules already consume.
4. Selection persists chrome-wide via localStorage and is restorable
   via URL query string.
5. The Storybook addon parallels (theme / viewport / locale /
   background) are `reg-mode` registrations with an `:axis` slot, not
   separate primitives — preserving the
   no-new-framework-registries principle.

See [`007-Mode-Tabs.md`](007-Mode-Tabs.md) for the orthogonal
per-variant mode-tabs primitive that sits inside the main pane.
