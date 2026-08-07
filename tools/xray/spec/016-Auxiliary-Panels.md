# 016-Auxiliary-Panels

The normative home for the per-tab content contracts beyond the hero
4-layer chrome architecture in
[`018-Event-Spine.md`](./018-Event-Spine.md). The 9-tab Dynamic inventory in
[`000-Vision.md`](./000-Vision.md) §The tab inventory is the
top-level navigation map; this doc gives the per-tab implementation
contract a one-shot implementer needs — inputs (subs / events
consumed), main interactions, observable outputs — without having to
reverse-engineer `tools/xray/src/day8/re_frame2_xray/panels/*.cljs`.

The per-tab content this doc covers:

| Tab content | `:rf.xray/panels.*` | Source / phase |
|---|---|---|
| Epoch tab content (numbered cascade) | `epoch-panel` | rf2-sc3r1; supersedes the retired Event/Handler panel per rf2-5gl5r (see §9.1 of [`021`](./021-Dynamic-Panel-Designs.md)). Carries the inline issue surfacing (exception block + schema-fail step) per rf2-gbz39 Option (c) |
| Routing tab content (6th tab) | `routing` | rf2-nrbs9 — promoted from "lives in App-db + Trace" to its own L3 lens tab; see §Routing tab below |
| Flows (lives in Views tab "Re-rendered" group) | `flows` | Phase 5 (rf2-83irn); see §Flows content below |

## 12-panel inventory (rf2-crhr8 + rf2-3r3ao; rf2-gbz39)

Every Xray panel is independently mountable per
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract. The
4-tier surface inventory totals 12 panels — 10 independently
mountable, 2 internal sub-components. (The Issues tab + its
`issues-ribbon/Panel` were removed per rf2-gbz39 Option (c) — Mike
RULED the dedicated aggregate tab away; issues surface inline in the
Epoch panel + the L2 event-row pink-wash + the always-on issues ribbon
signal. The underlying `:rf.xray/issues-ribbon` projection survives in
`registry.cljs` as the ribbon signal's data source but no longer backs
a mountable panel.) The canonical Panel-component mount paths and
L3-tab backing (when applicable) are:

| # | Tier | Panel | Mount path (`day8.re-frame2-xray.panels.*`) | Backs L3 tab |
|---|---|---|---|---|
| 1  | 1 | Epoch tab            | `epoch-panel/Panel`                 | Epoch |
| 2  | 1 | App-db tab           | `app-db-diff/Panel`                 | App-db |
| 3  | 1 | Views tab            | `reactive-panel/Panel`              | Views |
| 4  | 1 | Trace tab            | `trace/Panel`                       | Trace |
| 5  | 1 | Machines tab         | `machine-inspector/Panel`           | Machines |
| 6  | 1 | Routing tab          | `routing/Panel`                     | Routing |
| 7  | 2 | App-DB segment-inspector popup    | `app-db-segment-inspector/Popup`        | — (overlay) |
| 8  | 2 | Cancellation-cascade side-panel    | `cancellation-cascade/SidePanel`        | — (overlay) |
| 9  | 2 | Cancellation-cascade popover       | `cancellation-cascade/Popover`          | — (overlay) |
| 10 | 3 | Managed-fx records list            | `panels/ManagedFxList`                  | standalone mount — no current panel embeds it (rf2-5gl5r retired the Event/Handler tab that originally hosted it). Consumers mount directly per the embedding contract ([`008-Embedding-Contract.md`](008-Embedding-Contract.md)). |
| 11 | 4 | After-rings overlay                | `machine-after-rings/AfterRingsOverlay` | sub of Machines tab |
| 12 | 4 | Sim side-rail                      | `static.machines.sim/SimRail`           | sub of Machines tab |

Panel-by-panel detail (subs / events / interactions) lives in the
sections below. Tier 4 sub-components are geometry-coupled to
`machine-inspector/Panel` and are NOT independently mountable; they
ship under `mount-machine-inspector!`. Modal overlays the 4-layer
shell owns (Settings dialog, command palette, share modal) are
shell chrome and NOT counted here.

### Performance — dropped (cross-link to Chrome DevTools)

The Performance panel is dropped from Xray. The framework already
emits User-Timing entries via Spec 009 — `rf:event:<id>`,
`rf:sub:<id>`, `rf:fx:<id>`, `rf:render:<component>`,
`rf:cascade:<dispatch-id>` — which Chrome DevTools renders natively in
its **Performance** tab → Timings track. Xray stops duplicating a
surface Chrome does better at higher quality (flamegraph, per-tick
zoom, INP overlay, layout-shift markers, scroll-jank attribution all
free).

To use Chrome DevTools for performance analysis:

1. Open Chrome DevTools → **Performance** tab.
2. Click record · perform the interaction · stop recording.
3. The Timings track shows the `rf:*` User-Timing entries inline with
   browser-level events (long tasks, layout shifts, INP).
4. Per-event hot-path information stays inside Xray's chrome —
   the ms duration in the Epoch panel + the L2 event-list `duration`
   column (rf2-pjjwh; the row-gutter tier dot was retired).

**Sensitive-data note for the cross-link:** Chrome DevTools cannot
mark-render `:rf/redacted` sentinels — any User-Timing entry name that
would leak sensitive data must be self-redacted at emission time by
the framework (Spec 009 / `re-frame.performance`). Xray documents
this as a constraint of using the DevTools cross-link; it is not a
Xray rendering surface.

### MCP Server panel — dropped

The MCP Server panel is dropped from Xray. The dedicated `xray-mcp`
artefact was envisaged but dropped entirely (rf2-hvl1g, 2026-05-19);
there is no Xray-curated MCP surface to render. AI access to the
running re-frame2 runtime goes through `tools/re-frame2-pair-mcp/`
over raw nREPL — see [`000-Vision.md`](./000-Vision.md) §Where Xray
fits and DESIGN-RATIONALE.md Lock #6 supersedence.

### AI co-pilot — dropped

The AI co-pilot panel is dropped from Xray. Xray is the human-only
observability surface; AI access is via `tools/re-frame2-pair-mcp/`. The
collapsed-rail cue glyph + the right-rail co-pilot panel + all
co-pilot panel namespaces (`panels/ai_co_pilot*`) die in a separate
deletion PR.

All tab content shares the cross-panel substrate:

- **Pure hiccup** per [rf2-tijr](../../../spec/Conventions.md) — no
  Reagent / UIx references in the view. Frame isolation comes
  from the enclosing `[rf/frame-provider {:frame :rf/xray}]` in
  `shell.cljs`. Every `subscribe` / `dispatch` resolves to `:rf/xray`.
- **Read-only by default** per [`Principles.md`](./Principles.md). The
  panels write only to Xray's own `:rf.xray/*` app-db slots
  ([`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)).
- **Pure-data helpers under `_helpers.cljc`** — projection, filter
  application, status / outcome classification all live next to each
  panel as a `.cljc` sibling so the algebra runs under the JVM unit-
  test target. View files only render.
- **Trace-bus consumer** — every panel filters the
  `:rf.xray/trace-buffer` ring (per
  [`013-Trace-Consumer.md`](./013-Trace-Consumer.md)) to its slice; nothing here
  reads framework-level state directly.
- **Spine binding** — per-tab content reads
  `:rf.xray/focus` (see [`018-Event-Spine.md`](./018-Event-Spine.md)
  §6); selection in the L2 event list rebinds all per-tab content
  atomically. No panel reads `(peek history)`; no panel carries
  `:selected-*-id` slots.

## Event-detail panel — RETIRED 2026-05-27 (rf2-5gl5r)

> **Status — retired.** rf2-5gl5r deleted the Event/Handler panel
> (`panels/event_detail.cljs`) once the §9.1 Epoch panel (see
> [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md))
> reached feature parity. The canonical "what happened in this epoch"
> surface is now the Epoch panel — a numbered vertical cascade of
> every pipeline step (DISPATCH · COEFFECTS · HANDLER · FLOW · FX ·
> SUBSCRIPTIONS · VIEWS, conditional per the trace stream). The
> surviving cross-panel sub `:rf.xray/focused-event-bundle-detail` (renamed
> off the retired-panel name `:rf.xray/event-detail` per rf2-7ed9ms) +
> spine-shim events `:rf.xray/select-dispatch-id` / `:rf.xray/clear-selected-
> dispatch-id` were relocated to `registry.cljs` as cross-panel
> primitives (consumed by the trace panel's status bar,
> machine-inspector, cancellation-cascade, and the existing test
> corpus; rf2-nugvv removed `share.cljs`, which was also a consumer).

## Effects content — folded into Epoch panel

The pre-rewrite Effects panel is GONE. Its content folds into the
**Epoch panel** (tab 1 of 7) as the **"EFFECTS HANDLERS RAN"** section
of the numbered cascade — see
[`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §9.1
for the canonical Epoch-panel design and
[`018-Event-Spine.md`](./018-Event-Spine.md) §5.1 for the per-event
pipeline projection. (The folded content originally targeted the
Event/Handler tab; rf2-5gl5r retired that panel and the Epoch panel
now hosts the "fx handlers that ran" rows.)

Per-fx invocation status (`:error` / `:overridden` / `:skipped` /
`:ok`) renders as inline chips next to the fx-id in that section.
Aggregate "registered fxs" data (which historically lived in the
Effects panel) is reachable via the Cmd-K palette under the `:fx`
source — registered handler ids + invocation counts. No standalone
tab.

## Flows content — Epoch panel FLOW section (rf2-lo37i)

The pre-rewrite Flows panel is GONE. Flows surface as a dedicated
**section of the Epoch panel placed RIGHT AFTER the HANDLER** — flows
fire at the outermost `:after` interceptor, reshaping the pending
`:db` before it commits, so the FLOW section precedes EFFECTS
RETURNED / EFFECTS HANDLERS RAN. The canonical home for per-cascade
flow firings is [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md)
§9.1 (Epoch panel) + [`018-Event-Spine.md`](./018-Event-Spine.md) §5.1
(per-event pipeline projection). (rf2-lo37i originally added this
section to the Event/Handler tab; rf2-5gl5r retired that panel — the
section now lives in the Epoch panel's numbered cascade.) For each
flow that fired during the focused cascade the FLOW section lists,
in cascade order:

- `wrote <path>` — the flow's `:output` write target with the
  after-value rendered inline.
- `read <input-path-1> <input-path-2> …` — the flow's `:inputs`,
  shown so the reader can see which paths caused the recompute.

The FLOWS section sits between the HANDLER and the EFFECTS RETURNED /
EFFECTS HANDLERS RAN sections under the per-run view —
mirroring the runtime order where flows transform the pending db
before it commits and before any fx run (see 018 §5.1 wireframe + row
contract).

A **secondary** appearance is in the **Views tab** "Re-rendered"
group (cross-cutting): when a flow's downstream sub appears in a
view's *Rerendered because* list (per
[`012-Views.md`](./012-Views.md) §Three-group layout Re-rendered),
the sub-id carries a `⊳` flow-glyph prefix that distinguishes
flow-output subs from hand-written subs. Click-through from the
Views entry jumps to the Epoch panel's FLOW section for that cascade.

A registered-flows-overview is reachable via the Cmd-K palette under
the `:flow` source: flow-id, inputs, output path, last recompute. No
standalone tab.

## Recordable-coeffect content — Epoch panel RECORDABLE COEFFECTS section (rf2-9fyn40 · EP-0010 · EP-0017 §9)

The dispatch envelope's flat recordable-coeffect map `:rf.cofx` surfaces as
a dedicated **RECORDABLE COEFFECTS section of the Epoch panel placed RIGHT
AFTER DISPATCH SITE** — the EP-0010 "where did this state value come from?"
answer (the explicit time / id / randomness facts the fold consumed, so a
durable write reads as a function of prior frame-state PLUS recorded tokens
rather than ambient host reads). The section shows the handler's **declared
recordable leaves** (EP-0017 §9 — the most user-relevant facts on the
token). The canonical home for the per-cascade section is
[`018-Event-Spine.md`](./018-Event-Spine.md) §5.1 (section 1a — the
9-section event lens). **Silent-by-default** when the focused cascade
surfaced no `:rf.cofx` map.

> EP-0017 §9 renamed this surface from **WORLD INPUTS** (the
> `:rf.world/inputs` nested map keyed `:time-ms`) to **RECORDABLE
> COEFFECTS** (the flat `:rf.cofx` map keyed `:rf/time-ms`). "World inputs"
> was the vocabulary fracture EP-0017 closes: the recorded map *is* the
> recordable GRADE of coeffect. The ambient grade keeps its own COEFFECTS
> section (event-lens section 3); the two grades sit side by side.

The runtime stamps the flat map onto the `:rf.event/dispatched` trace under
`[:tags :rf.cofx]` (rf2-alc1lf · `router/emit-dispatched-trace!`,
DEBUG-gated so it rides the same whole-body production elision as the rest
of the dispatched emit). The section reads it decoupled — no require on
core; Xray consumes the WIRE FACTS.

**PRIVACY** (EP-0010 §Privacy / Open Issue 4, ruled 2026-06-11; EP-0017 §9
restates per leaf):

- `:rf/time-ms` is **ALWAYS safe to surface** (a wall-clock fact, never
  PII) and renders **verbatim**.
- **Every other leaf is value-bearing and REDACTS BY DEFAULT** — its value
  is routed through the same summarize/projection path the reply-envelope
  consumer uses (`resources-helpers/summarize`, mirroring
  `reply_envelope.cljc`'s wire-slot summarization), so the panel renders a
  privacy-preserving summary (type + bounded size + a redaction-aware
  preview; an upstream `:rf/redacted` / `:rf.size/large-elided` sentinel
  keeps its sentinel status) and **NEVER a raw value**. The KEY itself is
  owner-qualified vocabulary (the app's `:counter/delta`, a subsystem's
  `:rf.route/location`, …), not PII, so it rides verbatim as the row label.

No standalone tab; no palette overview (a recordable-coeffect map is
per-cascade, not a registry).

## Issues — the dedicated tab was REMOVED (rf2-gbz39, Option (c))

**Mike RULED Option (c) (2026-05-31): the dedicated Issues tab + its
aggregate `issues-ribbon/Panel` were removed.** The session-wide
aggregate / triage list the tab provided was consciously dropped.
Issues now surface through three kept surfaces:

- **Inline in the Epoch panel** — the "Exception Thrown" block +
  per-step pass/fail (rf2-ahhgn / rf2-wnvid); `:db` schema-fail in the
  EFFECT HANDLERS step (rf2-kt6js); slow-fx duration + amber.
- **The L2 event-row pink-wash** (rf2-b8guz) — rows whose epoch
  contains an issue.
- **The always-on issues ribbon signal** — the auto-open-on-error
  watcher reads the surviving `:rf.xray/issues-ribbon` projection
  (registered in `registry.cljs`) and pops Xray open on the first
  empty→non-empty transition. The cross-epoch "something is wrong" cue.

The issue projection (`:rf.xray/issues-ribbon`) reads the focused
epoch's `:trace-events` and projects the issue subset (errors +
warnings + advisories) per
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
§Error event catalogue. The pure-data algebra lives in
`panels/issues_ribbon_helpers.cljc` (also feeding the L2 pink-wash
predicate `l2-timeline/event-bundle-has-issue?`). What was a panel lens is
now purely a signal source.

(Historical: pre-rf2-gbz39 the Issues panel was a focused-epoch lens
per [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md)
§8; the rf2-jio48 rebuild had already dropped the legacy aggregate-feed
shape. The §8 panel-design contract is superseded by this removal.)

## Performance — dropped (see §Performance cross-link at top of file)

The Performance panel is dropped from Xray. Use Chrome DevTools'
Performance tab — the framework emits `rf:*` User-Timing entries
DevTools renders natively. See the §Performance cross-link block at
the top of this file.

Per-cascade duration stays inline in:

- The L2 event-list `duration` column (`1.2 ms`, right-aligned —
  rf2-pjjwh; the prior row-gutter tier dot was retired with the focus
  gutter).
- L4 Epoch panel cascade-outcome line (`⏱ 11ms · tier ●`).
- Event-list row hover tooltips (the row's `:title`).

That's the entire Xray-side perf surface post-rewrite.

## Routes — two verbs, two homes (rf2-o5f5f.3)

Per Mike's design call (2026-05-19) Routes appears in **both** the
Dynamic and the Static surfaces with **different verbs**. The
two-verbs-two-homes pattern is the normative shape:

- **Static Routes (browse-all).** Flat list of every registered
  route, substring search, Simulate-URL (hermetic — zero host nav
  mutation), per-row inline expand for the full registrar meta, and
  a per-row `→ Dynamic` jump chip. Event-INDEPENDENT — the panel
  reads the registrar, not the spine, so it surfaces whether or not
  the focused cascade touched routing.
- **Dynamic Routing (focused-event lens).** Narrows to the cascade
  in focus — `FROM`/`TO` chips when the cascade allocated a
  nav-token, an `◆ HERE` orientation glyph when it did not. Event-
  COUPLED — the lens IS the focused cascade's slice of routing
  concerns.

The pattern generalises (DESIGN-RATIONALE Lock #15 — "Two verbs, two
homes"): when a cohesive sub-domain has both a browse-all surface
and a focused-event lens, the browse-all surface lives in Static
mode (chrome silhouette signals event-INDEPENDENCE), the focused-
event lens lives in Dynamic (the spine signals event-coupling).
Machines follows the same pattern (browse-all in Static, focused-
event activity in Dynamic); Views and Events are positioned to
follow.

### Sub-domain inheritance

The Routes section that follows splits into two: **Static Routes**
(the browse-all home) + **Dynamic Routing** (the focused-event lens
home). Each home is its own normative surface; readers looking for
"the Routes panel" must check which mode they mean. Cross-link the
two homes via the Static `→ Dynamic` jump chip (rf2-o5f5f.3 surfaces
this; the chip dispatches `:rf.xray.static.routes/jump-to-dynamic`
which flips mode + selects the Dynamic Routing tab).

## Static Routes

Per rf2-o5f5f.3 / PR #1568. The Static-side home for Routes — flat-
list browse-all surface with hermetic Simulate-URL preview.

### Shape

- **Flat list sorted by `:path`** (lexicographic). No tree, no
  indentation, no depth — the routing-inheritance audit (2026-05-19)
  found that routes are flat in the spec + impl, `:parent` plays no
  role in matching, and the match-resolver is structural (6-rule
  rank on URL pattern).
- **Per-row chips**: route-id + path + doc, with letter badges
  (`M` / `L` / `T` / `P`) for routes carrying `:on-match` /
  `:can-leave` / `:tags` / `:parent`. Click the row chevron to
  expand inline.
- **Substring search** across route-id + path + doc.
- **Simulate-URL** — paste a URL, the panel ranks every matching
  route by its 6-rule `:rf.route/rank` tuple and highlights the
  winner. Load-bearing interactive surface; exposes the structural
  match contract per
  [`spec/012-Routing.md`](../../../spec/012-Routing.md) §Route
  ranking algorithm.
- **Per-row hermetic Simulate-navigation preview** — clicking the
  expanded row's `Simulate navigation` button renders an inline
  preview of `:on-match`'s runtime-db route slice + the matched params,
  **without calling the host's navigation fx**. The host's current-route
  slice (the framework-owned runtime-db state at
  `[:rf.runtime/routing :current]`, EP-0001 rf2-vzld77 — NOT app-db) is
  unchanged; this is a lens, not a verb. The preview match is
  **row-local**: it matches the
  SELECTED row's own compiled pattern against the entered URL — NOT the
  global rank winner (rf2-m9rx6). For overlapping routes (e.g. an exact
  route plus a lower-ranked splat fallback that both match
  `/checkout/payment`), previewing the fallback row reports ITS match +
  params even though the exact route is the global winner. (Global
  winner status is the Simulate-URL surface's concern, not the per-row
  preview's.) Pure-data projection via
  `routing_helpers/simulate-navigation-preview` (JVM-portable).
- **Per-row `→ Dynamic` jump chip** — flips Xray to Dynamic mode
  (`:rf.xray/set-mode :dynamic`) and selects the Dynamic Routing
  tab (`:rf.xray/select-tab :routing`). The two-verbs-two-homes
  affordance — the Static lens shows you the catalogue, the Dynamic
  lens shows you the focused event's slice of it.

### Inputs

- `:rf.xray/registered-routes` — flat `{<route-id> <meta>}` map.
  Shared with the Dynamic Routing lens.
- `:rf.xray.static.routes/query` — substring search input.
- `:rf.xray.static.routes/sim-url` — Simulate-URL input.
- `:rf.xray.static.routes/expanded` — set of expanded route-ids.
- `:rf.xray.static.routes/sim-nav-open` — set of route-ids whose
  hermetic Simulate-navigation preview is open.
- `:rf.xray.static.routes/tab-data` — view-facing composite per
  `routing_helpers/project-static-data`.

### Hermetic-preview contract

The Simulate-navigation preview MUST NOT call the host's navigation
fx (`:rf.route/url-requested`, `:rf.route/navigate`, `history.pushState`,
etc.). It MUST NOT write the host's current-route slice (the
framework-owned runtime-db state at `[:rf.runtime/routing :current]`,
EP-0001 rf2-vzld77). The preview is a pure-data
projection: given a route-id + a URL, return what `:on-match`'s
runtime-db route slice would look like if that URL navigated. The host stays
where it is; Xray is a lens, not a verb. (This is the load-bearing
distinction from the host's `:rf.route/navigate` fx — the Dynamic
lens picks that up if the user runs it; the Static preview never
does.)

### Empty state

When the host app registers no routes the panel renders only the
header + a terse `No routes registered.` one-liner. No `(none)`
placeholder. Search + Simulate-URL are hidden when the catalogue is
empty.

## Dynamic Routing

Per rf2-nrbs9 (tab promotion) + rf2-o5f5f.3 (focused-event narrowing).
The Dynamic-side home for Routes — focused-event lens that surfaces
`FROM` / `TO` chips when the cascade allocated a nav-token, or
`◆ HERE` orientation when it did not.

### Shape (per rf2-lq0ef + focused-event narrowing)

The lens is a **focused-event slice** of the routing catalogue. It
inherits the same flat-list catalogue + Simulate-URL surface
documented under §Static Routes — same routes-map, same 6-rule rank
contract — but adds the focused-cascade markers above the catalogue.
The catalogue serves as orientation; the markers are the load-bearing
signal.

### Inputs

- `:rf.xray/registered-routes` — shared with Static Routes.
- `:rf.xray/current-route-slice` — composite over
  `:rf.xray/target-frame-runtime-db` reading the current-route slice at
  `[:rf.runtime/routing :current]` (EP-0001 rf2-vzld77 — the route slice
  is framework-owned runtime-db state). Switching the L1 frame picker
  re-binds the lens.
- `:rf.xray/event-bundles` — the shared cascade projection. The composite
  scans the focused cascade's trace events for the routing-emit.
- `:rf.xray/focus` — the spine's focused dispatch-id + epoch.
- `:rf.xray.routing/query`, `:rf.xray.routing/sim-url`,
  `:rf.xray.routing/expanded` — Dynamic-side UI-state slots
  (separate from the Static slots so the two homes carry independent
  filter / expand state).
- `:rf.xray/routing-tab-data` — view-facing composite folding all
  the above per `routing_helpers/project-data`.

### Per-focused-event highlighting

| Marker | Trigger | Visual |
|---|---|---|
| `◆ HERE` | Current matched route — always when no navigation happened | Violet chip (`accent-violet`); left-border accent |
| `◆ FROM` | Cascade caused navigation — the prior route | Cyan chip; left-border accent |
| `◆ TO` | Cascade caused navigation — the new route | Green chip; left-border accent; replaces `◆ HERE` (TO is the new HERE) |

When the focused cascade has no routing impact, only `◆ HERE`
surfaces — orientation glyph.

### Detection contract — how the panel knows the cascade caused navigation

The composite scans the focused cascade's trace events for the routing
lifecycle emits (per [`spec/012-Routing.md`](../../../spec/012-Routing.md)
§Trace events — the runtime emits them in the order `nav-token/allocated`
→ `deactivated`? → `activated`?, inside both `:rf.route/navigate` and
`:rf.route/transitioned`). **Both ids are read off the focused cascade's
own trace events — never the live route slice.** Detection:

- The `:rf.route.nav-token/allocated` emit's `:tags :route-id` is the
  **TO** (the new route).
- The `:rf.route/deactivated` emit's `:tags :route-id` is the **FROM**
  (the prior route the cascade left). The runtime emits `deactivated`
  ONLY on a cross-route navigation (prior id ≠ new id), so its absence
  is itself the no-FROM signal.
- Two cases leave no `deactivated` emit, both correctly collapsing FROM
  to nil: the first navigation in the session (no prior route to
  leave), and a same-route re-navigation (params/query/fragment changed,
  route-id unchanged — surfacing a FROM equal to TO is noise anyway).

> **Why the cascade and not the live slice (rf2-m9rx6).** The live
> current-route slice is the route the app is on *now*, which drifts as
> navigation continues. Deriving FROM from it made the lens
> time-dependent: a normal navigation to B collapsed FROM (the live
> slice was already B), and focusing an older A→B cascade after the app
> moved on to C falsely reported C as FROM. The focused cascade carries
> `deactivated A` / `activated B` for its epoch unconditionally, so
> FROM A / TO B render correctly regardless of the live route. The live
> slice's `:route-id` is used ONLY for the HERE / current-orientation marker.

### Simulate-URL contract

The simulator walks the registered-routes map, calls
`re-frame.routing.match/match-against` on each route's compiled
pattern (or compiles it on the fly for test-only fixtures), and
ranks the matching candidates by `:rf.route/rank` descending — the
same order `match-url` walks the registry table. The first candidate
is the winner.

**Input normalisation (rf2-6nx8y).** The input is pasted by a human and
is commonly copied wholesale from the browser address bar — an
**absolute** URL with a `scheme://authority` origin. The simulator
normalises an absolute (or protocol-relative `//host/…`) URL to its
`pathname` — dropping the scheme + authority (userinfo / host / port) —
*before* the query/fragment strip, so a pasted
`https://app.example/cart?source=email#step-1` matches the registered
`/cart` pattern. A **relative** input (`/cart`, `cart/`, `?x`, `#y`) is
left untouched; only a `scheme://` (or leading `//`) marks an origin, so
a relative path that legitimately contains a `:` segment is not mistaken
for a scheme. `match-url` itself does not need this step (it only ever
sees host-relative URLs from `location`); the simulator adds it because
its source is human-pasted.

The result block surfaces:

- The normalised path (origin, query, and fragment stripped).
- Every matching candidate, with its `:rf.route/rank` tuple and the
  parsed `:params` map.
- The winner highlighted (green border + `WINNER` glyph).
- An empty result block when no route matches (i.e. `match-url` would
  return nil for this URL).

Query coercion and `:params` / `:query` schema validation are out of
scope for the simulator — the lens is about exposing the rank
cascade, not full match semantics. The same contract drives the
Static Routes Simulate-URL surface; the difference is the cascade
slice the markers are rendered against.

### Active route slice — params + query + fragment

Below the catalogue the panel renders a labelled grid for the active
slice:

    Params:    {:order-id "ord-1234"}
    Query:     {:source "cart"}
    Fragment:  "#step-3"

Absent slots render as `—` so the lens always shows the same
skeleton (predictable scanning).

### Empty state

When the host app registers no routes the panel renders only the
header + a terse `No routes registered.` one-liner. Identical to the
Static Routes empty state — the same registrar feeds both.

### Pre-rewrite app-db / trace overlap

The transition FSM state (`:idle` / `:loading` / `:error`) is still
part of the app-db slice (Spec 012) and still visible in the App-db
tab's diff. The Routes lens is the dedicated home; the App-db tab
shows the raw slice diff like any other key. Navigation trace events
(`:rf.route.nav-token/*` + `:rf.route/fragment-changed` (rf2-cj9fn) +
`:rf.route/registered` / `:rf.route/cleared` / `:rf.route/activated` /
`:rf.route/deactivated` (rf2-dn26r)) continue to
appear in the Trace tab when the `event` chip is ON (default) —
the Dynamic Routing lens does not duplicate the firehose, it
projects the single nav-event that pertains to the focused cascade.

### Vision (future)

- **Nav-token timeline (swimlanes)** popover trigger from the tab.
- **`:on-match` chain explicit** in the Epoch panel's "EFFECTS HANDLERS
  RAN" section when the focused cascade is a routing cascade
  (already noted under §Epoch panel — `:on-match` event chain (Routes)
  later in this doc).
- **Route-chain visualiser** — the `:parent`-chain walk for nested
  layouts (i.e. expand the inline `:parent` annotation into the full
  `:rf.route/chain` graph).

## MCP Server panel — dropped

The MCP Server panel is dropped from Xray. The dedicated `xray-mcp`
artefact was envisaged but dropped entirely (rf2-hvl1g, 2026-05-19);
there is no Xray-curated MCP surface to render. AI access to the
running re-frame2 runtime goes through `tools/re-frame2-pair-mcp/`
over raw nREPL — see [`000-Vision.md`](./000-Vision.md) §Where Xray
fits and DESIGN-RATIONALE.md Lock #6 supersedence.

Trace events tagged `:origin :re-frame2-pair-mcp` (the new agent-origin tag)
appear in the **Trace tab** like any other tagged trace event — visible
when the `event` chip is ON. No special-purpose tab; the Trace tab's
filter-pill UX (per [`018-Event-Spine.md`](./018-Event-Spine.md) §5.3)
covers "show me only what the agent did" via an IN pill on `:origin
re-frame2-pair-mcp`.

## Settings popup — v1 ships

The Settings popup modal (trigger: `,` / `s` / ribbon `⚙`) is the
transient overlay through which the user tunes Xray's preferences.
The architectural shape — modal not panel, persistence-on-commit,
section-per-row — is normatively specified in
[`018-Event-Spine.md`](./018-Event-Spine.md) §9. This section
backfills what v1 actually ships.

### v1 tab inventory

| Tab | What it carries |
|---|---|
| **General** (default) | Text-size slider (range 10–18 px; writes the `--rf-xray-text-size` CSS custom property on the shell root + `<html>` — the **user knob**, pre-existing) · Density radio (`:compact` / `:cosy`; writes the `--rf-xray-font-size` CSS custom property — the **type-scale anchor** per rf2-n8i2c, separately tracked from `--rf-xray-text-size`) · Panel-position radio (`:right-rail` / `:popout` / `:fullscreen` — routes to `mount/open!` / `mount/popout!` / `mount/open-overlay!` via the browser API exports) · Panel-width-px slot (number; default 480; written by the resize handle per [`007-UX-IA.md` §Resize affordance](./007-UX-IA.md#resize-affordance); no in-popup widget — the panel's drag handle is the affordance) · "Auto-open Xray when an issue is observed" checkbox |
The Filters tab was retired per rf2-wknb3 — full pill management lives in the top-ribbon filter strip (`filters/pills.cljs`, per [`018-Event-Spine.md`](./018-Event-Spine.md) §7), the per-pill edit popup (`filters/edit_popup.cljs`, `:rf.xray.filters/edit-popup-*` events), and the mute manager modal (rf2-ikuwt). The settings tab's only widget was an "Open auto-filter UI" button dispatching `:rf.xray.filters/open` — an event with no handler registered anywhere — plus a static explainer paragraph. With the management surfaces all canonical elsewhere, the discoverability pointer was redundant.

The Theme tab was retired per rf2-ou3pn — the top-ribbon sun/moon icon (`ribbon-theme-toggle` in `shell.cljs`) is now the canonical light/dark affordance. Both surfaces dispatched the identical `[:rf.xray/settings-update :theme nil <kw>]` event; the popup copy was pure redundancy. The `:use-system-colors?` HCM-override toggle relocated to **General → Power user** — the setting slot has always been `:general :use-system-colors?`; only its cosmetic home in the Theme section is gone with the tab.

**v1 ships:** the General tab visible above, plus Keybindings, Buffer, and Diff (catalogued in [`018-Event-Spine.md`](./018-Event-Spine.md) §9). The Theme tab was retired per rf2-ou3pn and the Filters tab per rf2-wknb3 — see the notes immediately under the table. Nothing further is deferred: per
[`018-Event-Spine.md`](./018-Event-Spine.md) §9, **Popout** folded
into General's Panel-position radio (no own tab) and **Actions** was
dropped (factory-reset stays code-only). A Telemetry tab shipped briefly in the initial popup landing
(rf2-9poxq) but was removed (rf2-jh9ws) — Xray transmits no
telemetry, and a toggle pretending to control a non-existent
endpoint was a broken affordance per the text audit (rf2-yn86j).
When telemetry actually ships, the tab returns with real wiring.

### Two CSS custom properties — `--rf-xray-text-size` vs `--rf-xray-font-size`

The General tab carries two independently-tracked CSS custom properties.
They are NOT the same var and they drive different surfaces:

| CSS var | Knob | Surface | Origin |
|---|---|---|---|
| `--rf-xray-text-size` | Text-size slider (10–18 px; default 13) | Xray surfaces that opt-in read `var(--rf-xray-text-size, 13px)` directly — primarily the event-list rows and a small set of inline-style call sites. | Pre-existing user knob |
| `--rf-xray-font-size` | Density radio (`:compact` 12 / `:cosy` 13 / `:comfy` 14 — `:comfy` catalogued for forward-compat, not surfaced in v1) | The whole `theme/tokens.cljc :type-scale` — every typographic size resolves through `calc(var(--rf-xray-font-size, 13px) * <multiplier>)`. Flipping the var rescales every typographic surface in lockstep on the next paint. | rf2-n8i2c / PR #1571 |

Each var has its own write path
(`settings/effects/apply-text-size!` for `--rf-xray-text-size`;
`settings/effects/apply-density-font-size!` for `--rf-xray-font-size`)
and they are persisted as separate settings slots
(`:general :text-size` and `:general :density`). The two knobs are
deliberately decoupled — a user who wants tighter row rhythm without
shrinking the type scale flips density to `:compact` while leaving
text-size at 13; a user who wants larger event-list rows without
rescaling the rest of the chrome bumps text-size while leaving
density at `:cosy`.

### Defaults

| Slot | Default | Rationale |
|---|---|---|
| `:general :text-size` | `13` (px) | Matches `theme/tokens.cljc :type-scale :body`. Writes `--rf-xray-text-size`. |
| `:general :density` | `:cosy` | Matches `theme/tokens.cljc :font-size-default` (13 px). Writes `--rf-xray-font-size` per rf2-n8i2c. |
| `:general :panel-position` | `:right-rail` | Matches the existing `:rf.xray/layout-host-selector` inline-host posture per [`015-Configuration.md`](./015-Configuration.md). |
| `:general :panel-width-px` | `480` | Matches the default inline-host `--rf-xray-inline-width` band. Clamped `[320, 0.9 × viewport-width-px]` on every write. Set by the drag handle (rf2-x8h9y); double-click resets to this default. |
| `:general :auto-open-on-error?` | `false` | The user is in their app, not asking Xray to interrupt them. |
| `:theme` | `:dark` | Xray is a dev tool; the canvas-and-chrome palette in `theme/tokens.cljc` is the dark one. |

### Persistence

- **Storage key:** `re-frame2.xray.settings.v1` (versioned so future
  schema changes can ignore stale payloads without colliding with the
  old shape).
- **One nested map, not one atom per knob** — the round-trip is a
  single `pr-str` of the whole settings shape; serialisation drift
  between knobs is structurally impossible. Loaded from localStorage
  at preload time via `config/load-settings-from-storage!`; applied to
  the live shell before first paint via
  `settings/effects/apply-all!`.
- **Dual-write on change.** `:rf.xray/settings-update [section key
  value]` writes through to (a) the in-process atom in `config.cljc`
  (canonical, drives the localStorage round-trip) AND (b) Xray's
  app-db at `[:settings <section> <key>]` (drives the immediate
  reactive re-render of the popup's controls). Without the dual-write
  the popup's radio buttons would not redraw until the user closed
  and reopened the modal.

### Auto-open-on-error semantics

When `:auto-open-on-error?` flips ON, a sub-watcher is installed
against the existing `:rf.xray/issues-ribbon` sub. On the **first
empty → non-empty** transition (and only when Xray is not already
visible) the watcher drives the late-bound **surface-preserving**
reopen `mount/toggle!` browser API export — **not** `mount/open!`.
Auto-open is a *reopen*, not an explicit surface request: the
"not already visible" guard proves the shell is hidden, so `toggle!`
shows whatever physical surface the shell was last realized on
(a hidden overlay reopens as the overlay, not silently re-parented
back inline). This is the same generic-reopen contract the
`Ctrl+Shift+C` toggle and the command palette's "show the shell
first" step honour — see [`011-Launch-Modes.md` §Closed
state](./011-Launch-Modes.md). Two install triggers (both idempotent): (1) on toggle
flip-on inside `:rf.xray/settings-update`, and (2) on first Xray
open via `mount/ensure-xray-frame!` when the persisted toggle is
already on. The install is a **defensive no-op pre-mount** — if the
`:rf/xray` frame isn't yet registered, the subscribe would return
nil and `(add-watch nil …)` would throw; the frame-presence guard
makes the early call safe and the watcher lands on first frame
registration. Detached on flip-off.

**Activation (rf2-lynzk).** The install MUST put the subscription on the
substrate's push path — `re-frame.interop/activate-derived-value!` — as
its FIRST act, before the baseline seed and before `add-watch`. A
subscription is **not** already live the instant `subscribe` returns.
On the ratom family (Reagent, reagent-slim) a subscription IS a bare
`reagent.ratom/Reaction`, built deliberately without `:auto-run`, and a
Reaction learns its sources ONLY through `deref-capture`; a plain deref
taken outside `*ratom-context*` runs the body raw and leaves the
reaction watching nothing. The `add-watch` then records a callback that
cannot fire, and auto-open-on-error never fires at all — silently, and
only on those adapters, while this section promises the behaviour
unconditionally. `:rf.xray/issues-ribbon` is a SIGNAL with no rendered
consumer (see [`018-Event-Spine.md` §5.4](./018-Event-Spine.md)), so no
component render supplies the capture context that hides this defect
elsewhere. The op is a no-op on adapters already push-based from birth
and idempotent on an already-activated reaction. Same law, same fix
order (activate → seed → watch) as the framework's own observation port.

**Baseline seeding (rf2-8i1tg3).** The edge-detector's baseline count
MUST be seeded from the reaction's value AT INSTALL TIME, before
`add-watch` — the reaction may already hold issues that predate the
install (e.g. a re-install after a focus-nav that already landed on an
issue-carrying epoch), and `add-watch` only fires on the NEXT change.
Leaving the baseline at its cold-start default of zero misclassifies
that pre-existing non-empty state as the empty→non-empty edge on the
first subsequent change, spuriously auto-opening Xray for an epoch the
watcher never actually saw transition.

### Bulk configure! escape hatch

`(xray-config/configure! {:rf.xray/settings <map>})` bulk-replaces the
whole settings map. Shape mirrors the defaults table above. The
popup's per-knob event surface is the normal write path; this key
is for hosts that want to ship a non-default starting posture (e.g.
a corporate fork that wants light theme as the factory default).

### Reset to defaults

`config/reset-settings-to-defaults!` clears the localStorage payload
and resets the in-memory atom to `default-settings`. No popup
affordance ships in v1 — factory-reset stays **code-only**. The
"Actions" tab that would have hosted a reset button was dropped per
[`018-Event-Spine.md`](./018-Event-Spine.md) §9; the only destructive
op with a UI affordance is "Clear buffer now" under the Buffer tab.

### Cross-references

- [`015-Configuration.md`](./015-Configuration.md) — host-facing
  `configure!` surface; `{:rf.xray/settings <map>}` bulk-set;
  `:rf.xray/editor` / `:rf.xray/project-root` /
  `:rf.xray/layout-host-selector` / `:rf.xray/auto-open?` /
  `:rf.xray/egress-profile` enumeration.
- [`018-Event-Spine.md`](./018-Event-Spine.md) §9 — full architectural
  contract (modal not panel, why; reset semantics; future sections).

## Vision — auxiliary content growth

### Epoch panel — per-fx wire-boundary diff

**Bug class:** "I dispatched event X; it issued an HTTP request; the
UI updated incorrectly. What went over the wire? What came back? What
did the handler apply?"

The Epoch panel's "EFFECTS HANDLERS RAN" section grows a **rich expand
block per managed-effect fx** showing the entire wire interaction:
request payload (post-elision) → wire transit (status / headers /
timing waterfall) → response → handler dispatched → app-db slice
touched. One template; five surfaces (HTTP, WebSocket, machine
`:spawn`, SSR `:rf.server/*`, flows). See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 F.1.

<!-- TODO(rf2-yylmr): future-design — the wire-boundary record-panel
embed point under the Epoch panel's "EFFECTS HANDLERS RAN" section
needs an explicit micro-spec when implementation lands; the prior
"fx handlers that ran" anchor was Event-tab-relative. -->

### Epoch panel — `:on-match` event chain (Routes)

When the focused cascade is a routing cascade
(`:rf.route/navigate` or `:rf.route/handle-url-change`), the Epoch
panel's "EFFECTS HANDLERS RAN" section adds a dedicated `:on-match`
dispatch chain sub-section showing each fire-and-forget loader event and
its drain duration. `:on-match` is fire-and-forget (EP-0037 R1): a
throwing loader is an ordinary Spec 009 `:rf.error/handler-exception`
attributed to the event, not a route `:on-error` consequence, and never
changes route readiness. See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.2 R.2.

### Epoch panel — retry timeline

When an `:rf.http/managed` retried, surface the per-attempt timeline
(attempt id · result · category · backoff interval · total elapsed)
under the fx row in the Epoch panel's "EFFECTS HANDLERS RAN" section.
See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 F.3.

### Epoch panel — head model inspector (SSR)

When the focused cascade involved a `reg-head` resolution, surface
inputs (db slice + route) → head model output → rendered HTML head,
in three columns under the Epoch panel. See
[`006-Hydration-Debugger.md`](006-Hydration-Debugger.md)
§Vision §Head model inspector.

### Pending-navigation card

When `:rf/pending-navigation` is set in app-db (a `:can-leave` guard
rejected), surface it as a yellow card at the top of the App-db tab.
Shows the requested URL, the reason, the rejecting route, the rejecting
guard, and three action buttons (re-evaluate / force continue /
cancel). (Pre rf2-gbz39 this also surfaced in the Issues tab; that tab
was removed per Option (c) — the App-db card + the issues ribbon signal
carry it now.) See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.2 R.6.

### Flow cascade-halt alarm

When `:rf.error/flow-eval-exception` fires, surface a high-priority
entry inline in the Epoch panel (+ the issues ribbon signal) listing
the subsequent flows that did NOT run (the cascade-halt clause of the
atomicity contract per Spec 013 §Failure semantics). (rf2-gbz39 removed
the Issues tab per Option (c); the alarm surfaces inline + via the
ribbon signal.) See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 F.4.

### Open-redirect / CRLF / trusted-shell advisories

Three security-class advisories surface at `:advisory` severity
(different from `:error` / `:warning`, not pushed to top) — inline in
the Epoch panel + via the issues ribbon signal (rf2-gbz39 removed the
dedicated Issues tab per Option (c)):

- Open-redirect when `:rf.server/redirect` uses caller-untrusted
  input (Spec 011 rf2-zfm8v).
- CRLF in header value when `:rf.error/header-invalid-value` fires.
- Trusted-shell opt advisory when `:head` / `:body-end` /
  `:script-src` carry caller-controlled strings.

Xray is a debugger, not a linter — advisories are quiet by default;
configurable to "loud" via Settings → Trace → "Security advisories".

### App-db tab — current-route slice always-visible

The current-route slice (the `:rf/route` runtime area, at
`[:rf.runtime/routing :current]` in runtime-db) is structured and small; it pins
at the top of the App-db tab under a `[reserved]` group banner,
always-expanded, with each sub-key on its own line. See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.2 R.11.

Note that the Routing tab (§Routing tab above, rf2-nrbs9) is the
primary lens for routing — including the route tree, current match,
and FROM/TO nav transitions. The App-db slice pin is the raw-data
echo for users who want to inspect the slice alongside other app-db
state.

### Historical — 6-tab aspiration superseded by 4-tab v1

rf2-ttnst (Mike 2026-05-19 §0ter.4 walkthrough; shipped via PR #1518)
originally locked the Settings popup at **6 tabs**: **General · Theme ·
Filters · Keybindings · Buffer · Diff**. Post-#2186 the popup ships
**4 tabs**: **General · Keybindings · Buffer · Diff** — the Theme tab
was retired per rf2-ou3pn (top-ribbon sun/moon icon is the canonical
light/dark affordance) and the Filters tab per rf2-wknb3 (full pill
management lives in the ribbon strip + per-pill edit popup + mute
manager modal). The Buffer tab inherited the `:general :epoch-history`
slider per rf2-pu9sb. The locked 4-tab inventory plus per-tab content
sits in [`007-UX-IA.md` §Settings popup](./007-UX-IA.md#settings-popup-modal-overlay)
(the canonical UX surface) and [`018-Event-Spine.md` §9](./018-Event-Spine.md#9-settings-popup)
(the architectural contract). The v1 reality is also captured earlier
in this document — see §Settings popup — v1 ships above. This Vision
block historically catalogued a different 8-row aspiration (Theme /
Density / Editor / Trace v1 + Keybindings / Buffer / Popout / Actions
future); it is superseded.

What landed under rf2-ttnst (and the subsequent retirements) that the earlier aspiration did not anticipate:

- **Density** folds into **General** (no separate tab).
- **Editor** + **Trace** fold into **General** as power-user knobs.
- **Diff** is its own tab (hiccup-diff opt-in + density-sensitive
  layout), not a sub-section of another tab.
- **Keybindings** v1 ships READ-ONLY; the chord-rebind UI is the v1.1
  follow-on.
- **Buffer** ships with the `:buffer/events-retained` numeric input
  (writes through to `(rf/configure! {:trace-buffer
  {:events-retained N}})` per rf2-5u03ig) + a "Clear buffer now"
  button (confirm modal). Two inputs that once sat here were removed:
  the `:buffer/retained-epochs` input (rf2-pu9sb — no substrate
  consumer; it was duplicate dead chrome for the epoch-history slider,
  which lives in General) and the inert
  `:buffer/app-db/inspector-collapse-threshold` input (rf2-5u03ig —
  no runtime consumer; the App-db inspector already auto-collapses on
  depth/width). The `:general :epoch-history` slider was briefly
  relocated here per rf2-pu9sb but reverted back to General
  2026-05-27.
- **Popout** folds into General's Panel-position radio — no own tab.
- **Actions** dropped — factory-reset stays code-only
  (`config/reset-settings!`); the "Clear buffer now" affordance under
  the Buffer tab covers the only destructive op users have asked for.

See [`007-UX-IA.md` §Settings popup](./007-UX-IA.md#settings-popup-modal-overlay)
"Dropped from earlier drafts" for the full deletion ledger.

## Cross-references

- [`000-Vision.md`](./000-Vision.md) — the canonical-questions + the
  9-tab Dynamic inventory.
- [`019-Cross-Cutting-Insight.md`](./019-Cross-Cutting-Insight.md) —
  the 5-idioms × 4-areas matrix driving the per-tab content growth
  above.
- [`007-UX-IA.md`](./007-UX-IA.md) — typography, colour tokens,
  density gradients, keyboard map.
- [`018-Event-Spine.md`](./018-Event-Spine.md) — 4-layer chrome,
  spine binding (`:rf.xray/focus`), per-tab content placement,
  Settings popup, data-classification rendering contract.
- [`012-Views.md`](./012-Views.md) — Views tab content (where Flows
  surface).
- [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) — the trace ring every tab
  filters from.
- [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) — the
  exhaustive `:rf.xray/*` subs + events + fxs each tab registers.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  — the framework's trace-event vocabulary tabs read; the User-Timing
  entries Chrome DevTools' Performance tab renders (cross-link
  replacing the dropped Performance panel).
- [`spec/002-Frames.md`](../../../spec/002-Frames.md) §`reg-fx`,
  §`:fx-overrides` — what the Epoch panel's "EFFECTS HANDLERS RAN"
  section surfaces.
- [`spec/013-Flows.md`](../../../spec/013-Flows.md) — what the Views
  tab surfaces (under "Re-rendered" group).
- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — the
  framework substrate the Routing tab projects: the registrar
  (`reg-route` + `(rf/registrations :route)`), the current-route
  slice (at `[:rf.runtime/routing :current]` in runtime-db), and the
  `:rf.route.nav-token/allocated` emit the panel scans for the
  FROM/TO marker derivation.
