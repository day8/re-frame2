# shared-components — the chrome every L4 panel reuses + iconography

Companion to [`panels.md`](panels.md). When you answer "where does X
live?", citing the shared component beats describing the behaviour from
each panel's perspective.

## Shared components

Three components are consumed by every (or nearly every) L4 panel.

### `edn-inspector/render-node`

The single canonical data renderer — lazy collapsible tree + inline
diff highlighting + keyword accent + clickable paths. The public entry
point is the `render-node` fn in the
`day8.re-frame2-xray.views.edn-inspector` namespace, which lives at
[`tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs`](../../../tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs)
per §021 §10. Every panel that shows data — app-db, Epoch coeffects /
side-effect args / inline exception ex-data, Views sub values, Trace raw
trace-event maps — goes through this renderer (§021 §10.6 — binding).

Locked capabilities (§021 §10.1): lazy collapsible tree · inline diff
(no side-by-side) · minimal keyword-only type coloring · clickable
paths for **cross-panel propagation only** (no blame popover, no
copy-path, no copy-value, no "show epoch that last changed this" —
explicitly stripped per §021 §10.5).

Lazy-expansion heuristic (§021 §10.4): depth ≤ 2 expanded · depth 3
expanded if ≤ 10 children · depth ≥ 4 collapsed · changed children
force ancestor chain open · per-panel `:default-depth` override (app-db
defaults depth-3-collapsed; Epoch / Trace payloads default
depth-2-expanded).

Operator expansion state persists in app-db
(`:rf.xray.edn-inspector/expansion {<path>}`) per epoch + path.

### `film_strip/header`

Shared `[◀ Prev] [Next ▶]` header consumed by most L4 panels (**Trace
opts out** — the L2 list owns its spine navigation). Lives
at
[`tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc).
MVP: chronological walk through the L2 spine. Hit-target sizing per
§021 §17.1.5 (28×20px, 4px vertical padding for AA target-size).
Disabled state at spine ends. Navigation is via the rendered
`◀ Prev` / `Next ▶` buttons plus the wired focus-gated `j` / `k` spine
keys (`:rf.xray/focus-event-prev` / `-next`, per `keybinding.cljs`) —
the component is pure (no global keydown listener), and `keybinding.cljs`
wires **no** `ArrowLeft` / `ArrowRight` spine handler. Do not document
arrow-key navigation until `keybinding.cljs` actually implements it.

Per-panel stretch filters (e.g. "next epoch with ⚠" — driven off the
issues-ribbon signal — "next route activity"
for Routes, "next epoch that touched THIS machine" for the Machine tab)
slot into the header's filter slot.

### `focus_resolver` + `find-epoch-record`

Shared focus-resolution at
[`tools/xray/src/day8/re_frame2_xray/panels/shared/focus_resolver.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/focus_resolver.cljc).
Resolves the focused epoch's record from `:rf.xray/focus` (per
[`018-Event-Spine.md`](../../../tools/xray/spec/018-Event-Spine.md))
with the **head-fallback contract** — when no historical epoch is
focused, every L4 panel scopes to the most-recent epoch in the buffer
(not "no data" — head IS a valid focus). Used by Epoch, Views,
app-db, Trace, Machine, Routes for symmetric "spine at head" empty
states.

The evicted-epoch placeholder (§021 §10.7 — `"Epoch evicted from
buffer — increase :epoch-history to retain more"`) is also resolved
here, so the film-strip ◀ / ▶ keeps working when the operator scrubs
past an evicted row.

## Iconography quick reference

Per §021 §17.1.5 (binding; HCM-safe because glyph alone carries
signal, colour is never alone):

| Tab (Dynamic) | Mnem | Icon | Stripe token |
|---|---|---|---|
| Epoch | `e` | `⚡` | `:accent-violet` |
| app-db | `a` | `◐` | `:cyan` |
| Views | `v` | `◉` | `:cyan` |
| Trace | `t` | `⬢` | `:orange` |
| Machine | `m` | `◆` | `:green` |
| Routes | `r` | `🌐` | `:yellow` |
| Resources | `s` | — | (family-tinted rows; no single stripe) |
| Graph | `g` | — | `:magenta` (violet — the algebra lens) |
| Modules *(tab labelled **Frames**)* | `u` | — | (family-tinted rows; no single stripe) |

Nine Dynamic tabs (Epoch is `:order -1`, the leftmost / default-landing
slot; **Resources** `:order 7`, **Graph** `:order 8`, and **Modules**
`:order 9` are the three cross-feature lenses, each self-registered
through `reg-l4-tab!`; the **Modules** and **Graph** tabs are L4-only —
focusable, no standalone mount facade). There is **no Issues tab** and
**no Event tab** — the Epoch tab is the "what happened" surface, and issues
surface inline in the Epoch
cascade, the L2 pink-wash, and the issues-ribbon signal.

L2 row badges (live impl `l2_timeline.cljc`): `⚠` issue · `◆` machine
transition · `🌐` HTTP activity · `⚡` fx-emit child dispatch · `⏲` timer
dispatch. **L2 issue pink-wash**: a cascade carrying an issue
washes its whole L2 row pink (`:bg-issue-row`) — the per-row "this epoch
is broken" signal. There is no dedicated Issues tab.

Cross-panel arrows: `⤴` jump-to-panel from popover (`:accent-violet`,
12px) · `↳` cause-attribution chip (`:text-tertiary`, 11px) · `→`
inline state transition (`:text-primary`, mono).
