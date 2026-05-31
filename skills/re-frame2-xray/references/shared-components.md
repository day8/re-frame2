# shared-components — the chrome every L4 panel reuses + iconography

Companion to [`panels.md`](panels.md). When you answer "where does X
live?", citing the shared component beats describing the behaviour from
each panel's perspective.

## Shared components

Three components are consumed by every (or nearly every) L4 panel.

### `edn_inspector/render`

The single canonical data renderer — lazy collapsible tree + inline
diff highlighting + keyword accent + clickable paths. Lives at
[`tools/xray/src/day8/re_frame2_xray/edn_inspector/render.cljs`](../../../tools/xray/src/day8/re_frame2_xray/edn_inspector/render.cljs)
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
opts out** per rf2-o6yqq — the L2 list owns its spine navigation). Lives
at
[`tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc).
MVP: chronological walk through the L2 spine. Hit-target sizing per
§021 §17.1.5 (28×20px, 4px vertical padding for AA target-size).
Keyboard `←` / `→` global binding. Disabled state at spine ends.

Per-panel stretch filters (e.g. "next epoch with ⚠" — now driven off the
issues-ribbon signal rather than an Issues tab — "next route activity"
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

Six Dynamic tabs (Epoch is `:order -1`, the leftmost / default-landing
slot). There is **no Issues tab** (rf2-gbz39) and **no Event tab**
(rf2-5gl5r, replaced by Epoch) — issues surface inline in the Epoch
cascade, the L2 pink-wash, and the issues-ribbon signal.

L2 row badges (live impl `l2_timeline.cljc`): `⚠` issue · `◆` machine
transition · `🌐` HTTP activity · `⚡` fx-emit child dispatch · `⏲` timer
dispatch. **L2 issue pink-wash** (rf2-b8guz): a cascade carrying an issue
washes its whole L2 row pink (`:bg-issue-row`) — the per-row "this epoch
is broken" signal that replaced the dedicated Issues tab.

Cross-panel arrows: `⤴` jump-to-panel from popover (`:accent-violet`,
12px) · `↳` cause-attribution chip (`:text-tertiary`, 11px) · `→`
inline state transition (`:text-primary`, mono).
