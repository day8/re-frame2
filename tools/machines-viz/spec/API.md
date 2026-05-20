# API

The consolidated user-facing surface. Implementer-readable: every
symbol a consumer of Machines-Viz might reach for.

This doc is a **reference**. The normative descriptions for the
embedding-host side of these surfaces live in
[`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md);
where the two drift, this doc owns the **component contract** and
the **share-URL encoding**, and Causa 003 owns the **panel chrome**.

## Installation

```clojure
;; consumer deps.edn — typically transitive through Causa or Story
{:deps {day8/re-frame2-machines-viz {:mvn/version "..."}}}
```

For Causa users: pulled transitively via `day8/re-frame2-causa`.
For Story users with a machine panel: pulled transitively via
`day8/re-frame2-story`. Direct dependents (a custom dev shell that
wants a chart without the rest of Causa) declare it themselves.

## `MachineChart` component

The single chart-rendering surface. Substrate-agnostic — exported
by every substrate adapter Machines-Viz ships against (Reagent /
UIx / Helix).

### Namespace

```clojure
(:require [day8.re-frame2-machines-viz.chart :as viz])
```

### Signature

```clojure
[viz/MachineChart props]
```

`props` is a map; the schema below is closed (the chart rejects
unrecognised keys at registration time).

### Props

| Prop | Required | Default | Meaning |
|---|---|---|---|
| `:machine-id` | yes | n/a | The id of the registered machine to render. The chart resolves the definition via `(rf/machine-meta machine-id)` and the snapshot via `[:rf/machines machine-id]`. |
| `:frame-id` | yes | n/a | The frame within which to resolve the registration and the snapshot. Hosts that observe only one frame can hard-code it; multi-frame hosts (Causa, Story) pass the user-selected frame here. |
| `:on-state-click` | no | `nil` | `(fn [state-id state-meta] ...)`. Fires when the user clicks a state node. Hosts wire this to their click semantics (Causa: jump to source; Story: highlight in chrome; viewer: no-op). |
| `:on-transition-click` | no | `nil` | `(fn [from-state event-id to-state transition-meta] ...)`. Fires on a transition edge click. |
| `:on-guard-click` | no | `nil` | `(fn [guard-name source-meta] ...)`. Fires when the user clicks a guard label inside a transition's tooltip. |
| `:on-action-click` | no | `nil` | `(fn [action-name source-meta] ...)`. Fires when the user clicks an action label inside a state's entry/exit tooltip. |
| `:read-only?` | no | `false` | If `true`, all `:on-*` callbacks are no-op'd regardless of what the host passes. The viewer page sets this. |
| `:show-microsteps?` | no | `true` | Whether intermediate `:always` microstep nodes are rendered. |
| `:show-after-rings?` | no | `true` | Whether `:after` countdown rings render on the source states. |
| `:show-invoke-all?` | no | `true` | Whether `:invoke-all` children render as a row of mini-machines (vs. a collapsed single icon). |
| `:show-spawned?` | no | `true` | Whether dynamically `:rf.machine/spawn`-ed children appear in the parent's spawn-tray. |
| `:height` | no | `nil` (flex) | Fixed height in pixels. Useful for uniform-ribbon layouts. |
| `:width` | no | `nil` (flex) | Fixed width in pixels. |
| `:initial-zoom` | no | `1.0` | Starting zoom factor. The user can zoom/pan after mount; the prop only sets the initial value. |
| `:auto-pan?` | no | `false` | Whether the chart auto-pans on every transition to keep the active state visible. Causa's panel sets this from its localStorage toggle; the viewer page leaves it off. |
| `:current-state-override` | no | `nil` | A `{:state ... :data ...}` map that overrides the live snapshot. Used by the read-only viewer page to render the shared snapshot — when sourced from a share URL, `:data` is always absent (the share schema carries `:state` only; per [§Share-URL payload schema](#share-url-payload-schema)). Out-of-scope for live charts. |
| `:direction` | no | `:tb` | Layout axis. `:tb` lays the chart top-to-bottom (rank flows down); `:lr` left-to-right (rank flows right). Promoted from an internal `chart.elk-layout/->elk-graph` arg to a top-level prop per rf2-ikdi3 so wide vs. narrow hosts can pick the axis without forking the file. |
| `:layout-options` | no | `nil` | ELK `layoutOptions` overrides — a map of string → string keys that merge ON TOP of the canonical defaults (`chart.elk-layout/default-layout-options`). Hosts tighten / widen / swap individual knobs (`"elk.spacing.nodeNode"` for density, `"elk.layered.crossingMinimization.strategy"` for rank-order discipline, etc.) without re-stating the whole map. The `:direction` arg always wins for `"elk.direction"`. Per rf2-ikdi3. |
| `:layout-engine` | no | `:auto` | Engine selector — `:auto` (cached ELK when available, layered fallback otherwise — the historical behaviour), `:elk` (cached ELK only; returns nothing when not ready so the host can choose 'wait + retry' over 'render the inferior engine'), `:layered` (force the layered fallback — useful for deterministic screenshot tests). Per rf2-ikdi3 — the two-engine reality (layered fallback + ELK) is now surfaced explicitly rather than hidden behind a single entry point. |
| `:density` | no | `:regular` | Density variant — `:compact` / `:regular` / `:cosy`. Picks the geometry + typography map from `visual-constants/chart-for-density`; the chart's `corner-radius` lock (rf2-g6cig) is invariant across every density so the chart's visual character stays consistent — only quantity scales. Per [§Density](#density) and rf2-32gw5. |

The four `:on-*` callback shapes are mirrored from
[Causa 003 §Source-coord integration](../../causa/spec/003-Machine-Inspector.md#source-coord-integration);
the `*-meta` argument carries the registered source-coord map for the
clicked element.

### What renders

For the selected machine, the chart shows:

- **A directional state-chart.** Nodes are states (compound states
  nested visually). Edges are transitions, labelled with their
  triggering event id.
- **The current state pulses.** Compound states' active child is
  highlighted recursively. The pulse is the only continuous
  animation; backgrounded charts pause it.
- **Tooltips.** Hover a state for its tags + entry/exit actions;
  hover an edge for its guard + action functions.
- **`:invoke` / `:invoke-all` spawned children.** Per
  `:show-invoke-all?`, render as a horizontal row of mini-machines
  with the join-condition label below. Per
  [Causa 003 §`:invoke-all` viz](../../causa/spec/003-Machine-Inspector.md#invoke-all-viz).
- **`:after` countdown rings.** A filling arc on the source state
  while a timer is scheduled; updates at 60Hz when the chart is
  visible (per [DESIGN-RATIONALE Lock #8](./DESIGN-RATIONALE.md)).
- **`:final?` states.** Rendered with a doubled border + checkmark
  glyph.
- **State-tag badges.** Each state node carries a coloured ring
  per active tag union member (per Spec 005 §State tags).
- **Microstep flashes.** Per `:show-microsteps?`, intermediate
  states in an `:always` cascade flash briefly before the cascade
  resolves.

What does **not** render (the chart fires the callback and the
host decides):

- Transition history ribbon (Causa's chrome; lives in
  `tools/causa/`).
- Source-coord chips with editor-URL handler wiring (the chart
  fires `:on-*-click`; the host opens the file).
- A machine picker dropdown (the host owns frame + machine
  selection).

### Data sources

The chart reads from the framework's public surfaces; it does not
introduce new registries.

| Surface | Used for |
|---|---|
| `(rf/machine-meta machine-id)` | Resolves the registered definition (states, transitions, guards, actions, source coords). |
| `[:rf/machines <id>]` slot in frame `app-db` | Live current-state + data snapshot; deref drives the pulse. |
| `:rf.machine/transition` trace events | Edge glow on the matching transition. |
| `:rf.machine.microstep/transition` events | Microstep flashes. |
| `:rf.machine.timer/scheduled` / `-fired` / `-stale-after` | `:after` countdown ring scheduling. |
| `:rf.machine.invoke-all/started` / `-all-completed` / `-some-completed` / `-any-failed` | `:invoke-all` row updates. |
| `:rf.machine/spawned` / `-destroyed` | Spawn-tray contents on the parent. |
| `:rf.machine/done` | `:final?` entry highlight before auto-destroy. |
| `:rf.machine/system-id-bound` / `-released` | Aliased addressing in the spawn-tray (gensym shows `:gensym-42 (:auth/main)`). |

Source: lifted from
[Causa 003 §Data sources](../../causa/spec/003-Machine-Inspector.md#data-sources).

## Density

The chart ships **three named density variants**. Hosts pick one via
the `:density` prop on `MachineChart`; the same machine renders at
different physical sizes without forking the renderer.

| Density | When to pick it |
|---|---|
| `:compact` | Story's 50-chart panel grid; thumbnail listings; any surface where the chart is one of many and the user is scanning the grid for shape rather than reading individual labels. Walks the typography back to the spec/007-UX-IA refused-floor (state 11px / edge 9px) — the refused-floor was set for dense data-grid surfaces, and this density IS that surface. Geometry tightens ~25% (paddings, dot-grid spacing, pill height). |
| `:regular` | The default. Causa's machines tab; the read-only viewer page; any embedded host that picks no density at all. Typography sits at the chart-floor (state 13px / edge 11px per rf2-gg7ws). The constants in `visual-constants/chart-regular` are the same constants `visual-constants/chart` aliases. |
| `:cosy` | Single-chart presentation displays — Causa's machines tab on a wide monitor, a standalone viewer on a projector, an editor's docs-page screenshot. Walks the typography up to state 15px / edge 13px. Geometry loosens ~25%. |

### Identity vs. quantity

Density scales **quantity**, not **identity**. The same chart at all
three densities reads as the same chart — the rounded-rect 'data, not
product' character (rf2-g6cig) holds. Specifically:

- **`corner-radius` is locked at 6 across every density** per the
  rf2-g6cig lock. A 'compact' chart with `corner-radius 4` would
  read as a different chart, not a smaller one; a 'cosy' chart with
  `corner-radius 10` would read as 'product chrome'. The lock means
  the chart's silhouette is the same at every scale.
- **`edge-label-backplate-opacity` is invariant** across densities
  (rf2-gg7ws collision-avoidance v1 — the backplate's contrast
  budget doesn't scale with type size).
- **`dot-grid-alpha` is invariant** — the backdrop's subtlety
  doesn't track density.

Every other geometry / typography knob (`stroke-width`, paddings,
pill geometry, every `*-px` font size, the dot-grid `spacing-px` /
`radius-px`) tracks the density axis monotonically: `:compact <
:regular < :cosy`. The three named maps in `visual-constants` share
the SAME key set (asserted by `visual-constants-cljs-test`).

### Resolution rules

- `:density nil` or unspecified ≡ `:regular`.
- `:density` ∈ `:compact` / `:regular` / `:cosy` resolves to the
  matching named map via `visual-constants/chart-for-density`.
- Any other value throws an `ex-info` at render time — picking an
  unknown density is a programmer error, not a runtime fallback.
- The resolved density surfaces on the root `<svg>` element as
  `data-density="<compact|regular|cosy>"` so hosts and tests can
  read the active density without re-reading the bound prop.

### Implementation notes

- The render entry-point (`chart.svg/render`) rebinds the dynamic
  Var `visual-constants/*chart*` to the chosen density's map for
  the duration of the call. Helpers destructure off `vc/*chart*`,
  not off the namespace-level `vc/chart` alias.
- Hiccup construction is eager (`into` over `for`) so every density-
  resolved constant is captured in the returned data structure;
  the dynamic binding unwinds before the hiccup is handed to
  React.
- Direct hiccup-walking tests that want a non-default density can
  `binding [vc/*chart* (vc/chart-for-density :compact)] ...` and
  call helpers directly; production code always goes through the
  `:density` prop.

Per rf2-32gw5 (resolves the `visual-constants.cljc` doc-string's
'a future density toggle (compact / cosy / comfy) a one-knob change'
forward-promise; naming settled on compact / regular / cosy per the
bead title — `regular` is the load-bearing default name now that the
chart-floor lift (rf2-gg7ws) put the previous-default size at the
floor of three rungs rather than the only rung).

## Performance invariants

This section is **load-bearing and conformance-level**, not guidance.
The chart's runtime hot path is the single largest regression
surface for Machines-Viz (per `ai/findings/perf-audit-machines-viz-2026-05-14.md`
findings 1+2 — gitignored working note; audit-bead rf2-j3iwt). Once an implementation couples
live snapshot / trace ticks to graph recompute, the coupling is hard
to remove without rewriting the rendering pipeline. The MUSTs below
exist so that coupling never lands.

### Topology state and runtime-highlight state MUST be strictly separate

`MachineChart` maintains **two disjoint state planes**:

- **Topology / layout plane.** The node positions, edge routes,
  compound-state nesting, and the chart's measured viewbox. Derived
  from `(rf/machine-meta machine-id)` plus the user's expand/collapse
  state. This plane is **structural**: changing it requires re-laying
  out the graph.
- **Runtime-highlight plane.** The active-state affordance (static
  tint + bolder stroke; pulse retired 2026-05-20 per rf2-2sez0),
  transition edge glow, microstep flash, `:after` countdown ring
  progress,
  `:invoke-all` row state, spawn-tray contents, timer state, and
  every other per-trace decoration. Derived from `[:rf/machines <id>]`
  plus the `:rf.machine/*` trace bus. This plane is **decorative**:
  changing it MUST NOT touch the topology plane.

Implementations MUST hold these planes in disjoint reactive
subscriptions. The runtime-highlight plane MUST NOT participate in
any computation whose output reaches the layout plane. The two
planes share no caches.

### Transition / microstep / timer updates MUST change attrs/classes only

Live updates triggered by `[:rf/machines <id>]` snapshot changes or
by any `:rf.machine/*` trace event MUST mutate **only**:

- DOM attributes (`class`, `style.opacity`, `style.transform` on
  decoration layers, SVG `stroke-dasharray` / `stroke-dashoffset`,
  ARIA labels).
- CSS-driven animations (the transition glow, the microstep flash,
  the `prefers-reduced-motion` step animation). The heartbeat pulse
  was retired 2026-05-20 (rf2-2sez0).

Live updates MUST NOT:

- Re-run layout (no `dagre`/`elk`/custom layout call).
- Re-measure nodes (no `getBBox`, no `getBoundingClientRect` in any
  code path reached by a trace tick or a snapshot deref).
- Insert or remove topology DOM nodes (state nodes, edge paths,
  compound containers). Decoration overlays (rings, glows, badges)
  MAY mount and unmount; topology MUST NOT.
- Mutate any reactive value that the topology plane subscribes to.

A trace event that arrives at 60Hz MUST cost less than one paint
frame end-to-end on the chart's hot path.

### Layout-invalidation boundary is load-bearing

The **only** triggers permitted to invalidate the topology / layout
plane are:

1. **Machine (re-)registration.** `reg-machine` (or its hot-reload
   re-invocation) for the bound `:machine-id` within the bound
   `:frame-id`. Detected via the framework's registry-change signal
   (per Spec 001) — not via `:rf.machine/*` trace events.
2. **User-driven compound-state expand / collapse.** The chart's
   own UI event, scoped to the chart instance.
3. **A `:show-*` prop transition** that adds or removes topology
   (e.g. `:show-invoke-all?` flipping false → true reveals the
   row of mini-machines; that row is topology, not decoration).
   Transitions of `:show-after-rings?` are decoration-only and MUST
   NOT trigger layout.
4. **Container resize** of the chart's bounding box (only when the
   layout algorithm depends on available width / height).

No other code path may invalidate layout. In particular:

- `:rf.machine/transition`, `:rf.machine.microstep/transition`,
  `:rf.machine.timer/scheduled` / `-fired` / `-stale-after`,
  `:rf.machine.invoke-all/*`, `:rf.machine/spawned` / `-destroyed`,
  `:rf.machine/done`, `:rf.machine/system-id-bound` / `-released`
  trace events MUST NOT reach the layout pipeline.
- A `[:rf/machines <id>]` snapshot deref MUST NOT reach the layout
  pipeline.
- `:auto-pan?` MUST be implemented as a viewport translate (a CSS
  transform on the chart's outer group), never as a re-layout. The
  auto-pan code path MUST NOT call `getBBox` on topology nodes
  inside a trace-event handler; the active-node bbox MUST be cached
  during the last layout pass.

Implementations MUST place an explicit comment marking the
layout-invalidation boundary as load-bearing in the code that owns
it (the function or watcher that decides "should layout re-run?").
The comment MUST cite this section and DESIGN-RATIONALE Lock #9 and
Lock #11.

### One chart-level, visibility-gated animation clock

`:after` countdown rings and any continuous animation in the chart
MUST be driven by a **single, per-chart-instance animation clock**.
(The active-state heartbeat pulse was retired 2026-05-20 per
rf2-2sez0; only the `:after` countdown rings remain on the clock.
The transition glow is event-driven and resolves to a stable end-
state, so it does not consume the clock.)

- The clock is **one** `requestAnimationFrame` loop (or equivalent)
  per `MachineChart` instance. It MUST NOT be one loop per ring,
  per node, per state, or per timer.
- The clock is **visibility-gated**. It MUST start when the chart
  becomes visible (per `IntersectionObserver` and/or
  `document.visibilityState`) and MUST stop when the chart leaves
  the viewport or the document is hidden.
- The clock drives ring fills by reading the framework's
  authoritative timer state on each tick; it does not own the
  timer. The framework's clock keeps running regardless of the
  chart's visibility (per Lock #8); the chart's clock is purely
  presentational.
- A chart with no scheduled `:after` timers MUST stop its clock
  entirely until the next snapshot or trace tick wakes it.

Implementations MUST NOT create `setInterval`, `setTimeout`, or
`requestAnimationFrame` registrations per-node, per-ring, or
per-timer. A 50-chart Story grid with 5 rings each MUST run at most
50 animation loops total — not 250.

Per [DESIGN-RATIONALE Lock #8](./DESIGN-RATIONALE.md) (visibility
gating) and [Lock #11](./DESIGN-RATIONALE.md) (the layout/runtime
separation this section codifies).

## Read-only viewer

A static page at the canonical hosted URL (or self-hosted by the
consumer) that renders a chart decoded from a URL fragment.

### URL shape

```
https://day8.github.io/re-frame2-machines-viz/viewer.html#machine=<base64-edn>
```

Or, when self-hosted:

```
https://acme.example.com/path/to/viewer.html#machine=<base64-edn>
```

### Behaviour

- On page load, the viewer reads `location.hash`, strips the
  leading `#machine=`, base64url-decodes, transit-reads, and
  validates the envelope (per [§Share-URL payload schema](#share-url-payload-schema)
  below).
- Validation failure → a banner: "This share-URL is malformed or
  was produced by a newer Machines-Viz." No chart renders.
- Validation success → the viewer mounts `MachineChart` with:
  - `:machine-id` and `:frame-id` from the payload's
    `:chart-state` (per [§Share-URL payload schema](#share-url-payload-schema)).
  - `:read-only?` set to `true`.
  - `:current-state-override` from the payload's snapshot.
  - All `:show-*` flags default to `true`.
  - `:auto-pan?` off.
- A single banner at the top of the page reads: **"This is a
  static machine chart, not a Causa session — interactions are
  disabled."**
- A "show idle" toggle below the banner clears
  `:current-state-override` so the chart renders the machine at
  rest. Per [DESIGN-RATIONALE Lock #5](./DESIGN-RATIONALE.md).
- The page is statically hostable. Per
  [DESIGN-RATIONALE Lock #7](./DESIGN-RATIONALE.md), the
  canonical hosted instance at `day8.github.io` is a convenience,
  not a contract; consumers can self-host.

### What the viewer never does

- It never transmits the URL fragment to a server. The fragment is
  read client-side via `location.hash`; nothing sends it.
- It never loads transit events, app-db slices, or any data
  outside the validated payload schema.
- It never receives runtime `:data` — the share payload's
  `:snapshot` carries `:state` only (per
  [§Share-URL payload schema](#share-url-payload-schema)). The
  viewer cannot display data values because there are none to
  display.
- It never receives local-filesystem `:source-coords` — they are
  not part of the share schema. The viewer has no editor handler
  wired, so source coords would be inert anyway; excluding them
  prevents accidental disclosure of workstation paths.
- It never enables `:on-*` callbacks. Hosts requesting a "click in
  the viewer goes to my docs site" would have to fork the page;
  the canonical viewer is read-only end-to-end.

Source: lifted from
[Causa 003 §Share affordance](../../causa/spec/003-Machine-Inspector.md#share-affordance).

## Share-URL encoding

### Encoder

```clojure
(:require [day8.re-frame2-machines-viz.share :as share])

(share/encode-share-url chart-state)
;; => "https://day8.github.io/re-frame2-machines-viz/viewer.html#machine=..."

(share/encode-share-url chart-state {:host "https://acme.example.com/viewer.html"})
;; => "https://acme.example.com/viewer.html#machine=..."
```

`chart-state` is a map with the schema in
[§Share-URL payload schema](#share-url-payload-schema) below. The
encoder:

1. Validates `chart-state` against the schema. Anything outside the
   allowlist is silently dropped — including runtime `:data` on
   `:snapshot` and any `:source-coords` the caller passes (per
   [Principles §No session data in shares](./Principles.md)).
2. Strips metadata off `:definition` (registered definitions carry
   source-coord meta per Spec 001; that meta must not propagate
   into the share payload).
3. Canonicalises map / set ordering (per
   [Principles §Reproducible from the registry alone](./Principles.md)).
4. Wraps in the versioned envelope.
5. EDN-prints → transit-writes → base64url-encodes.
6. Wraps the fragment into the `:host` URL.

### Decoder

```clojure
(share/decode-share-url url)
;; => {:rf.machines-viz.share/v "1"
;;     :rf.machines-viz.share/chart {:machine-id :auth/login-flow ...}
;;     :rf.machines-viz.share/created 1736000000000}
;; or throws :rf.machines-viz.share/decode-failed with :reason
```

Failure modes:

| `:reason` | Meaning |
|---|---|
| `:malformed-fragment` | The `#machine=` fragment isn't valid base64url. |
| `:malformed-payload` | Decoded bytes aren't valid transit. |
| `:missing-envelope` | The payload is missing `:rf.machines-viz.share/v` or `:rf.machines-viz.share/chart`. |
| `:unknown-version` | `:rf.machines-viz.share/v` is newer than the decoder knows. |
| `:invalid-chart-state` | `:rf.machines-viz.share/chart` doesn't validate. |

### Pipeline

```
chart-state  →  validate + canonicalise  →  envelope wrap
             →  EDN-print  →  transit-write  →  base64url  →  URL fragment
```

Per [DESIGN-RATIONALE Lock #3](./DESIGN-RATIONALE.md).

### Share-URL payload schema

Share URLs are **viewer-side artefacts** — they exist solely to let
a remote recipient render the chart. The schema is deliberately
narrow: it carries the machine topology and the active-state
**name** for visual continuity, and nothing else. Two classes of
data are structurally excluded:

- **Runtime `:data`** — the machine's per-snapshot data value is
  not part of the share payload. A well-intentioned operator
  clicking "Copy as Share URL" must not be able to exfiltrate
  tokens, form contents, request payloads, or any other sensitive
  value the running machine has accumulated. Per
  [Principles §No session data in shares](./Principles.md).
- **Local-filesystem `:source-coords`** — source coordinates carry
  absolute file paths (per [`spec/Spec-Schemas.md` §`:rf/source-coord-meta`](../../../spec/Spec-Schemas.md#rfsource-coord-meta))
  which reveal usernames, workstation layout, and internal repo
  structure. Source-coord chips are an editor-side affordance for
  the operator running Causa; the viewer page has **no editor
  handler wired** and cannot use them. They are dropped at encode
  time.

```clojure
(def ShareEnvelope
  [:map
   [:rf.machines-viz.share/v      :string]   ;; encoding version, "1" at v1.0
   [:rf.machines-viz.share/chart  ChartState]
   [:rf.machines-viz.share/created :int]])   ;; wall-clock ms at encode time

(def ChartState
  [:map
   [:machine-id  keyword?]              ;; the registered machine's id
   [:frame-id    keyword?]              ;; the registered machine's frame
   [:definition  MachineDefinition]     ;; the topology (states, transitions, guards, actions, :invoke, :invoke-all, :after)
   [:snapshot   {:optional true}        ;; the current-state name at share time — name ONLY, no :data
    [:map {:closed true}
     [:state    keyword?]]]])           ;; the registered state-id; nothing else permitted in :snapshot
```

The `:snapshot` map is `{:closed true}` and carries `:state` only.
Runtime `:data` is structurally absent from the share payload; the
encoder neither reads nor serialises it. The decoder rejects any
`:snapshot` carrying additional keys with `:invalid-chart-state`.
The viewer page mounts `MachineChart` with `:current-state-override`
set to `{:state <state-name>}` (and `:data` therefore `nil`); the
chart pulses the state node without any data-driven affordance.

`:source-coords` is **not a top-level key** of `ChartState`. Source
coords live only in the operator-side `(rf/machine-meta machine-id)`
return value and never traverse the share pipeline. The viewer page
renders with `:on-state-click` / `:on-transition-click` etc.
no-op'd (per [§Read-only viewer](#read-only-viewer)), so the
absence of source coords is observationally invisible.

The `MachineDefinition` shape matches the `reg-machine` registered
definition (per Spec 005 §Snapshot shape + §Transition table grammar)
with **only** the topology slots included — `:guards` and `:actions`
maps are encoded as **names only** (their fn bodies are not
serialised; consumers re-resolve against their own registry if they
want to run the machine). The encoder **strips metadata** off the
definition before serialisation (registered definitions carry
source-coord meta per Spec 001; that meta does not propagate). The
viewer page never resolves guards or actions; it only renders.

Anything not in the schema is silently dropped by the encoder. New
top-level keys (and any future expansion of `:snapshot`) require
an explicit `:rf.machines-viz.share/allow?` opt-in plus an
operator-controlled redaction hook (per
[Principles §No session data in shares](./Principles.md)); CI
enforces.

### URL length

For typical machines (~20 states, ~30 transitions) the encoded URL
is under 4KB. Common URL limits sit around 8KB; we have headroom
for moderately-sized charts.

Charts large enough to exceed 8KB surface a fallback affordance
(in the host that called `encode-share-url` — typically Causa's
share menu): **"Copy as EDN fragment instead"** which puts the
EDN on the clipboard instead of the URL. Per
[Causa 003 §Share affordance §Performance](../../causa/spec/003-Machine-Inspector.md#performance_1).

## Exporters

### PNG

```clojure
(:require [day8.re-frame2-machines-viz.export :as export])

(export/chart-as-png! chart-element)
;; => Promise resolving to a Blob (image/png at 2x DPR)

(export/copy-png-to-clipboard! chart-element)
;; => Promise; clipboard contains the PNG + a text/plain alt-text sidecar
```

The PNG is rasterised at 2x DPR on a transparent background; the
current-state highlight is included. An alt-text sidecar (a
`text/plain` clipboard payload) summarises the machine: id, current
state, node count, transition count.

### SVG

```clojure
(export/chart-as-svg chart-element)
;; => String — image/svg+xml with embedded fonts

(export/copy-svg-to-clipboard! chart-element)
;; => Promise; clipboard contains the SVG as image/svg+xml
```

The SVG includes `<title>` and `<desc>` elements summarising the
machine (same content as the PNG sidecar). Fonts are embedded so the
SVG renders identically when pasted into a doc or a Figma frame.

### Share URL

```clojure
(export/share-url chart-element)
;; => URL string

(export/copy-share-url-to-clipboard! chart-element)
;; => Promise; clipboard contains the URL as text/plain
```

`chart-element` is the in-DOM element rendered by `MachineChart`
(or a hiccup-equivalent reference). The export functions derive the
payload from the element's bound props + the live snapshot.

### Mermaid `stateDiagram-v2`

```clojure
(:require [day8.re-frame2-machines-viz.mermaid :as mermaid]
          [day8.re-frame2-machines-viz.export  :as export])

(mermaid/emit definition)
;; => String — a fenced ```mermaid block containing a
;;    `stateDiagram-v2` rendering of the machine's static topology

(mermaid/emit definition {:fenced? false :header-comment? false})
;; => String — just the diagram body, no markdown fence, no caveat

(export/chart-as-mermaid chart-element)
;; => String — convenience wrapper that pulls `definition` off the
;;    bound chart-element and calls `mermaid/emit`

(export/copy-mermaid-to-clipboard! chart-element)
;; => Promise; clipboard contains the fenced markdown block as
;;    text/plain — paste into a GitHub README / PR description /
;;    Notion / any Mermaid-aware renderer and it renders inline.
```

`mermaid/emit` is the load-bearing pure function; it takes the same
normalised machine definition `(rf/machine-meta machine-id)` returns
(per [Spec 005 §Transition table grammar](../../../spec/005-StateMachines.md#transition-table-grammar))
and emits a string suitable for paste. It is substrate-independent
and DOM-independent — callable from JVM tests, from the JS bundle,
and from the read-only viewer.

The emitter is **static-topology only**:

- States render as Mermaid nodes; compound `:states` render as
  `state X { ... }` blocks with their own `[*] --> initial`.
- Event transitions render as `from --> to : event` edges. `:*`
  wildcard edges render with `*` as the label. Multiple-candidate
  vectors render every target-bearing guarded branch.
- `:after` transitions render as plain edges labelled
  `after(<delay>)`; the countdown-ring semantics are lossy.
- `:always` transitions render as plain edges labelled `always`;
  microstep timing remains lossy.
- Top-level fallback `:on` renders from a synthetic `root fallback`
  node because Mermaid has no exact deepest-wins fallback primitive.
- `:type :parallel` machines render as independent region state
  trees inside a synthetic parallel root. Broadcast macrostep
  semantics are lossy.
- `:final?` states render a `state --> [*]` terminal edge.
- `:initial` becomes `[*] --> <initial>`.

The following data does **not** survive the round-trip:

- `:after` timer rings — Mermaid `stateDiagram-v2` has no
  countdown-ring vocabulary. The timer's `:target` edge still
  renders (as a plain event-less edge), but the countdown semantics
  are lost.
- `:invoke-all` rows of mini-machines — Mermaid has no
  spawn-and-join row grammar that maps cleanly; the row is omitted
  entirely.
- Parallel-region broadcast macrosteps — regions render, but Mermaid
  cannot express that one event is broadcast through every active
  region before the snapshot commits.
- Microstep flashes, transition glow, `:tags`, guard evaluation,
  actions — none of these are static topology and none round-trip.
  Guard ids may appear on edge labels, but their runtime truth
  semantics do not.

The omission is flagged in a `%% comment` at the top of the emitted
block, so a reader who pastes the output into a doc sees the
lossy-round-trip caveat without consulting the spec. The full
topology renders correctly in the SVG / share-URL viewer; Mermaid
is the Markdown-paste lane only.

Source: per [DESIGN-RATIONALE Lock #4](./DESIGN-RATIONALE.md) (the
"Mermaid covers the static-paste lane" lift, revised 2026-05-15 per
rf2-deo2i — Mermaid emit promoted from v1.1 to v1.0 as a thin
static-topology exporter), and
[Spec 005 §Future §Diagram export](../../../spec/005-StateMachines.md#diagram-export-from-transition-tables)
(the framework-level forward-pointer the tool-side exporter
realises).

### Accessibility

Per [Causa 003 §Accessibility](../../causa/spec/003-Machine-Inspector.md#accessibility)
(the share-affordance subsection; not the panel-level one — that's
the second `#accessibility_1`) and
[Lock #10 above](./DESIGN-RATIONALE.md):

- **SVG**: `<title>` + `<desc>` summarise the machine textually.
- **PNG**: a `text/plain` alt-text payload rides on the clipboard
  alongside the image, with the same summary.
- **Both**: a screen reader pasting the artefact into a document
  has the same overview the sighted user has.

The chart's in-place alt-view (for screen-reader navigation of the
live chart itself, not the export) is a **v1.1 commitment**, per
[`000-Vision.md` §v1.1 candidates](./000-Vision.md#v11-candidates-not-committed)
and Causa 003 §Accessibility. v1.0 ships without it; the embedding
host's transition-history ribbon + machine picker carry the
accessible surface in the meantime.

## Public CLJS API surface — summary

```clojure
day8.re-frame2-machines-viz.chart/MachineChart    ; component
day8.re-frame2-machines-viz.share/encode-share-url
day8.re-frame2-machines-viz.share/decode-share-url
day8.re-frame2-machines-viz.mermaid/emit          ; pure fn — definition → string
day8.re-frame2-machines-viz.export/chart-as-png!
day8.re-frame2-machines-viz.export/chart-as-svg
day8.re-frame2-machines-viz.export/chart-as-mermaid
day8.re-frame2-machines-viz.export/share-url
day8.re-frame2-machines-viz.export/copy-png-to-clipboard!
day8.re-frame2-machines-viz.export/copy-svg-to-clipboard!
day8.re-frame2-machines-viz.export/copy-mermaid-to-clipboard!
day8.re-frame2-machines-viz.export/copy-share-url-to-clipboard!
```

No global state, no init function. The component is referentially
transparent over its props; the share / export functions are pure
(modulo the clipboard).

## See also

- [`000-Vision.md`](./000-Vision.md) — scope + non-goals + roadmap.
- [`Principles.md`](./Principles.md) — load-bearing principles.
- [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) — locks; cites Causa 003 lift-points. Lock #8, Lock #9, and Lock #11 are the rationales behind [§Performance invariants](#performance-invariants).
- [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md) — embedding-host contract; the source spec these surfaces lifted from.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the registry the chart visualises.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) — the trace bus the live-highlight consumes.
