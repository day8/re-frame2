# shared-components — the chrome every L4 panel reuses + glyphs

Companion to [`panels.md`](panels.md). When you answer "where does X
live?", citing the shared component beats describing the behaviour from
each panel's perspective.

## Shared components

Two components are consumed by every (or nearly every) L4 panel, and
one navigation rule binds them all.

### `edn-inspector/render-node`

The single canonical data renderer — lazy collapsible tree + inline
diff highlighting + keyword accent + clickable paths. The public entry
point is the `render-node` fn in the
`day8.re-frame2-xray.views.edn-inspector` namespace, which lives at
[`tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs`](https://github.com/day8/re-frame2/blob/main/tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs)
per §021 §10. Every panel that shows data — app-db, Epoch coeffects /
side-effect args / inline exception ex-data, Views sub values, Trace raw
trace-event maps — goes through this renderer (§021 §10.6 — binding).

Locked capabilities (§021 §10.1): lazy collapsible tree · inline diff
(no side-by-side) · minimal keyword-only type coloring. The renderer's
**only** path gesture is **double-click / `Enter` to zoom** into a
container; `Esc` zooms up one level and the breadcrumb row above the
body re-roots to any ancestor in one tap. There is **no
cross-panel propagation**, no blame popover, no copy-path, no copy-value
and no "show epoch that last changed this" (§021 §10.5).

Lazy-expansion heuristic (§021 §10.4): depth ≤ 2 expanded · depth 3
expanded if ≤ 10 children · depth ≥ 4 collapsed · changed children
force ancestor chain open · per-panel `:default-depth` override (app-db
defaults depth-3-collapsed; Epoch / Trace payloads default
depth-2-expanded).

Operator expansion state persists in app-db
(`:rf.xray.edn-inspector/expansion {<path>}`) per epoch + path.

### Spine navigation — no shared per-panel header

There is **no shared prev/next header** for L4 panels. Spine navigation
belongs to the **L2 events list** and the chrome ribbon's `‹ › »` cluster
(previous · next · fast-forward to head) — see
[`021-Dynamic-Panel-Designs.md` §5.5](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md).
The one panel-owned stepper is the Machine tab's per-machine prev/next,
which jumps to the previous or next event that touched *that* machine
(`prev-next-nav` in `panels/machine_inspector.cljs`).
The focus-gated `j` / `k` spine keys
(`:rf.xray/focus-event-prev` / `-next`, per `keybinding.cljs`) are
the keyboard route; `keybinding.cljs` wires **no** `ArrowLeft` /
`ArrowRight` spine handler, so do not document arrow-key navigation.

### `focus_resolver` + `find-epoch-record`

Shared focus-resolution at
[`tools/xray/src/day8/re_frame2_xray/panels/shared/focus_resolver.cljc`](https://github.com/day8/re-frame2/blob/main/tools/xray/src/day8/re_frame2_xray/panels/shared/focus_resolver.cljc).
Resolves the focused epoch's record from `:rf.xray/focus` (per
[`018-Event-Spine.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/018-Event-Spine.md))
with the **head-fallback contract** — when no historical epoch is
focused, every L4 panel scopes to the most-recent epoch in the buffer
(not "no data" — head IS a valid focus). Used by Epoch, Views,
app-db, Trace, Machine, Routes for symmetric "spine at head" empty
states.

It also resolves the `:epoch-evicted` status, from which each panel
renders its evicted-epoch empty state (the Epoch tab reads "The selected
epoch was evicted from the history buffer. Pick a more recent event."),
so the ribbon's `‹ › »` nav keeps working when the operator scrubs past
an evicted row. Raising the Settings epoch-history depth retains more.

## Tab and row glyphs

**Tab buttons render their text label only** — no icon and no per-tab
colour stripe (`tab-button` in
[`shell.cljs`](https://github.com/day8/re-frame2/blob/main/tools/xray/src/day8/re_frame2_xray/shell.cljs));
the mnemonic rides in the button's `title`. The per-tab icon and stripe
table in §021 §17.1.5 is normative-future: do not describe tab icons to a
user.

The L2 event row is glyph-free too — a text `source` column, the `>`
caret and the issue pink-wash are all it carries; see
[`panels-epoch.md` §The L2 timeline grammar](panels-epoch.md#the-l2-timeline-grammar).
The tab order and scope matrix live in [`panels.md`](panels.md).

Arrows in the chrome: `↳` cause-attribution chip (`:text-tertiary`,
11px) · `→` inline state transition (`:text-primary`, mono). There is no
`⤴` jump-to-panel arrow.
