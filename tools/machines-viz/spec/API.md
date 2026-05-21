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

`props` is a map. The component destructures the keys below with
defaults and ignores unrecognised keys; it does not run a closed-
schema reject. The component is presentation-only — the host pulls
the definition + the live snapshot and passes them in (this keeps the
chart testable in isolation and avoids coupling it to a framework
registry).

### Props

| Prop | Required | Default | Meaning |
|---|---|---|---|
| `:machine-id` | no | `nil` | Identifies the machine. Surfaces as the chart's aria-label and on every per-node `:data` payload (read by tests + hosts). |
| `:definition` | no | `nil` | The machine definition map. When `nil` the chart renders an empty-state placeholder. The component does NOT subscribe to a framework registry directly — hosts pull the definition via `(rf/machine-meta machine-id)` and pass it in. |
| `:current-state` | no | `nil` | The live `:state` keyword/vector for the active-state highlight. `nil` renders no highlight. |
| `:from-highlight` | no | `nil` | Focused-event lens origin (a `:state` value). |
| `:to-highlight` | no | `nil` | Focused-event lens landing (a `:state` value). |
| `:sim?` | no | `nil` | Flips the highlight palette to amber for the simulator path. |
| `:on-state-click` | no | `nil` | `(fn [path] ...)`. Fires when the user clicks a state node; `path` is the clicked node's path. No-op'd when `:read-only?` is true. |
| `:read-only?` | no | `nil` | When `true`, all `:on-*` callbacks are no-op'd. The viewer page sets this. |
| `:direction` | no | `:tb` | Layout axis. `:tb` lays the chart top-to-bottom; `:lr` left-to-right. Fed to elkjs as `elk.direction`. |
| `:layout-options` | no | `nil` | Host-side elkjs `layoutOptions` overrides merged on top of `default-elk-options` (`chart.cljs/default-elk-options`). The `:direction` arg drives `elk.direction`. |
| `:density` | no | `:regular` | Density variant — `:compact` / `:regular` / `:cosy`. Resolves the geometry + typography map via `visual-constants/chart-for-density`; the resolved map is threaded through the projector onto every node/edge `:data` so the xyflow node/edge components render at the chosen density. The chart root surfaces the resolved density as `data-density`. `nil` ≡ `:regular`; an unknown density throws at render time. Per [§Density](#density) and rf2-32gw5. |
| `:height` | no | `"100%"` | Outer wrapper height (CSS string). xyflow requires a non-zero parent height. |
| `:show-minimap?` | no | `false` | When `true`, render xyflow's built-in MiniMap. |
| `:show-controls?` | no | `true` | When `true`, render xyflow's built-in zoom/pan/fit Controls. |
| `:show-background?` | no | `true` | When `true`, render xyflow's dot-pattern Background. |
| `:after-ring-specs` | no | `nil` | rf2-uv1on. Optional vector of presentation-ready `:after`-timer ring-specs (each `{:node-id :fraction :color :cancelled? :tooltip :testid}`). When non-empty the chart mounts the `chart.overlays.after-rings` overlay as a sibling of the canvas; it walks the rendered node DOM to position each ring. The host owns the trace→spec projection + the scrubber-aware fraction. `nil` / empty → no overlay. |
| `:after-ring-tick` | no | `nil` | Opaque value the host bumps to force the after-rings overlay to re-measure + repaint (Causa passes `now-ms`; Lock #8 — one rAF clock per chart, owned host-side). |
| `:on-after-ring-hover` | no | `nil` | `(fn [node-id] ...)`. Hover-enter callback the overlay wires on each ring. |
| `:on-after-ring-leave` | no | `nil` | `(fn [node-id] ...)`. Hover-leave callback the overlay wires on each ring. |
| `:spawn-all-join` | no | `nil` | rf2-3ow55 (xyflow Phase 2). A presentation-ready `:spawn-all` join-spec — `{:node-id <string> :join <:all\|:any\|{:n N}\|{:fn _}> :children [{:key <kw> :done? :failed? :cancelled? :note}] :resolved? <bool?> :on-all-complete :on-any-failed}`. When present the chart mounts the `chart.overlays.spawn-all-join` inspector beside the spawn-all-bearing state, showing each spawned child + the join state. The host (Causa) projects the spec from its `:rf.machine.spawn-all/*` trace buffer; machines-viz owns only positioning + paint. `nil` → no inspector. |
| `:on-spawn-child-click` | no | `nil` | `(fn [child-key] ...)`. Fires on a join-inspector child-row click; Causa pivots to the child instance. |
| `:cancellation-cascade` | no | `nil` | rf2-3ow55 (xyflow Phase 2). A presentation-ready cascade-spec — `{:node-id <string> :parent-label <string?> :from-state <kw?> :steps [{:kind <:exit\|:destroy\|:abort\|:cleanup> :label <string> :note :delta-ms}]}`. When present (and `:steps` is non-empty) the chart mounts the `chart.overlays.cancellation-cascade` waterfall beneath the parent state, turning the scattered abort/destroy traces into one decision laid out vertically. The host projects the spec from the cancellation trace cluster. `nil` / no steps → dormant. |
| `:overlay-tick` | no | `nil` | rf2-3ow55. Opaque value the host bumps to force the `:spawn-all` + cascade overlays to re-measure + repaint (mirrors `:after-ring-tick`). |
| `:testid` | no | `"rf-mv-chart"` | Root wrapper `data-testid` so tests + hosts can find the chart. |

### Parallel-region rendering (rf2-lkwev, xyflow Phase 2)

A `{:type :parallel :regions {...}}` machine renders EVERY region as a
distinct orthogonal zone (Stately parity), superseding the Phase 1
first-region-only projection. Each region surfaces a synthetic
`:region?` compound container node — `chart.layout/parse-definition`
mints a `region__<region-id>` node-id for it, tags each region state
with `:region` + `:parent-id`, and flags the result `:parallel? true`.
`chart.projection/xyflow-graph` projects the container as a
`type: "parallel-region"` xyflow node (rendered by
`chart.nodes.parallel-region-node` with a distinct dashed boundary +
header label whose colour rotates per region index) and assigns each
state a `parentNode`/`:extent "parent"` so xyflow's sub-flow mechanic
nests the states inside the zone; elkjs lays the states out inside
each region's bounding box via `elk.hierarchyHandling
INCLUDE_CHILDREN`. The chart root surfaces `data-region-count`; region
containers are excluded from `data-node-count` + the aria-label state
count (they are zone chrome, not states).

### Substrate adapters (rf2-yg9he, xyflow Phase 2)

The xyflow `MachineChart` is a Reagent component, but xyflow IS React,
so the chart bottoms out at a React element tree.
`reagent.core/reactify-component` lifts it to a plain React class any
host mounts. The shared bridge `adapters.react-chart` reactifies once
(`MachineChartReactClass`) + exposes `chart-element` (CLJS props map →
React element); thin per-substrate shells present an idiomatic surface:

```clojure
;; UIx host
(:require [day8.re-frame2-machines-viz.adapters.uix :as mv-uix])
($ mv-uix/MachineChart {:machine-id :auth/flow :definition defn})

;; Helix host
(:require [day8.re-frame2-machines-viz.adapters.helix :as mv-helix]
          [helix.core :refer [$]])
($ mv-helix/MachineChart {:machine-id :auth/flow :definition defn})
```

All three substrates render the SAME component through one bridge —
there is no per-substrate fork of the chart.

The chart's click surface is `:on-state-click`, invoked with the
clicked node's `path`; the host resolves source coords for that path
against `(rf/machine-meta machine-id)` and opens the editor (per
[Causa 003 §Source-coord integration](../../causa/spec/003-Machine-Inspector.md#source-coord-integration)).
The overlay callbacks (`:on-spawn-child-click`, `:on-after-ring-hover`,
`:on-after-ring-leave`) fire with the relevant child-key / node-id.

### What renders

For the supplied `:definition`, the chart shows:

- **A directional state-chart.** Nodes are states (compound states
  nested visually via xyflow sub-flows; `{:type :parallel}` machines
  render every region as a distinct orthogonal zone — see
  [§Parallel-region rendering](#parallel-region-rendering-rf2-lkwev-xyflow-phase-2)).
  Edges are transitions, labelled with their triggering event id;
  `:after` edges read `after(<delay>)` and `:always` edges read
  `always` (per `chart.layout/edge-label`).
- **The current state highlights.** When `:current-state` is set the
  matching node carries a static active tint + bolder stroke; compound
  states' active child is resolved recursively via
  `chart.layout/highlight-id`. The highlight is a static affordance
  (the heartbeat pulse was retired 2026-05-20 per rf2-2sez0); the only
  continuous animation is the `:after` countdown rings overlay, which
  pauses when the chart is backgrounded.
- **Focused-event lens highlights.** `:from-highlight` / `:to-highlight`
  tint the origin + landing nodes of a focused transition; `:sim?`
  flips that palette to amber for the simulator path.
- **`:final?` states.** Rendered with a doubled border + checkmark
  glyph (per `chart.nodes/state-node`).
- **State-tag badges.** Each state node carries a tag pill per state
  tag (per `chart.nodes/tag-pill`; Spec 005 §State tags).
- **`:after` countdown rings (overlay, host-fed).** When the host
  passes a non-empty `:after-ring-specs` vector the chart mounts the
  `chart.overlays.after-rings` overlay as a sibling of the canvas; it
  walks the rendered node DOM to position a filling arc on each source
  state. The host owns the trace→spec projection and bumps
  `:after-ring-tick` to repaint at up to 60Hz when the chart is visible
  (per [DESIGN-RATIONALE Lock #8](./DESIGN-RATIONALE.md)). The chart
  emits no `:spawn` / `:spawn-all` edge of its own.
- **`:spawn-all` join inspector (overlay, host-fed).** When the host
  passes a `:spawn-all-join` spec the chart mounts the
  `chart.overlays.spawn-all-join` inspector beside the spawn-all-bearing
  state, showing the spawned children + the join state. There is
  deliberately no `spawn` topology edge — `:spawn` / `:spawn-all` are
  state-entry actions, so spawned children surface through this overlay,
  not as a row of nodes (per `chart.projection/choose-edge-type` and
  [Causa 003 §`:spawn-all` viz](../../causa/spec/003-Machine-Inspector.md#spawn-all-viz)).
- **Cancellation cascade (overlay, host-fed).** When the host passes a
  `:cancellation-cascade` spec with non-empty `:steps` the chart mounts
  the `chart.overlays.cancellation-cascade` waterfall beneath the parent
  state.

What does **not** render (the chart is presentation-only — the host
supplies the data and decides on the callbacks):

- Transition history ribbon (Causa's chrome; lives in
  `tools/causa/`).
- Source-coord chips with editor-URL handler wiring (the chart fires
  `:on-state-click`; the host opens the file).
- A machine picker dropdown (the host owns machine selection and pulls
  `:definition` + `:current-state` itself).
- Microstep flashes for an `:always` cascade — the shipped chart renders
  `:always` transitions as plain `always`-labelled edges; per-microstep
  flash animation is not part of the presentation-only component.

### Data sources

The chart is **presentation-only**: it consumes nothing from the
framework registry or the trace bus directly. The host (Causa, Story,
the viewer page) reads the framework's public surfaces and projects
them into the chart's props. The table below maps each framework
surface to the prop the host derives from it.

| Framework surface (host reads) | Chart prop the host derives |
|---|---|
| `(rf/machine-meta machine-id)` | `:definition` — the registered topology (states, transitions, guards, actions). |
| `[:rf/machines <id>]` slot in frame `app-db` | `:current-state` — the live `:state` driving the active highlight. |
| `:rf.machine/transition` trace events | `:from-highlight` / `:to-highlight` — the focused-event lens. |
| `:rf.machine.timer/scheduled` / `-fired` / `-stale-after` | `:after-ring-specs` (+ `:after-ring-tick`) — the countdown-ring overlay. |
| `:rf.machine.spawn-all/started` / `-all-completed` / `-some-completed` / `-any-failed` | `:spawn-all-join` (+ `:overlay-tick`) — the join inspector overlay. |
| cancellation trace cluster (`:rf.machine` abort / destroy events) | `:cancellation-cascade` (+ `:overlay-tick`) — the cascade overlay. |

The host owns every trace→spec projection; the chart only positions and
paints what it is handed. This keeps the chart testable in isolation and
avoids coupling it to a framework registry.

Source: the host-side surfaces are lifted from
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
- The resolved density surfaces on the chart's root wrapper element
  as `data-density="<compact|regular|cosy>"` so hosts and tests can
  read the active density without re-reading the bound prop.

### Implementation notes

- `MachineChart` resolves the `:density` prop ONCE per render via
  `visual-constants/chart-for-density` (rf2-k647w). The resolved
  map is threaded through the projector (`chart.projection/xyflow-
  graph`) onto every node + edge `:data` as `{:chart <density-map>}`.
- The xyflow node + edge components recover that map off their
  `:data` prop (`chart.nodes/chart-constants` does `js->clj` on the
  `clj->js`-ed `:chart` entry) and read their geometry / typography
  off it. xyflow invokes these components OUTSIDE any dynamic-binding
  scope, so the density travels in the data, not in a dynamic Var.
- A node/edge payload without a `:chart` entry falls back to
  `visual-constants/chart-regular`, so the regular density stays
  pixel-identical to the pre-rf2-k647w hardcoded numbers.
- Direct projection tests that want a non-default density pass it
  through `xyflow-graph`'s `:chart` option and assert on the emitted
  `:data`; production code always goes through the `:density` prop.

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
findings 1+2 — gitignored working note; audit-bead rf2-j3iwt). Once an
implementation couples the live snapshot / decoration props to graph
recompute, the coupling is hard to remove without rewriting the
rendering pipeline. The MUSTs below exist so that coupling never lands.
The shipped chart is presentation-only — the host projects the
framework's registry + trace bus into props (per [§Data sources](#data-sources))
— so these invariants govern how the component reacts to **prop
changes**, not to direct registry / trace subscriptions.

### Topology props and runtime-highlight props MUST be strictly separate

`MachineChart` maintains **two disjoint planes**, fed by two disjoint
prop groups:

- **Topology / layout plane.** The node positions, edge routes, and
  compound-state nesting. Derived solely from the structural props
  `:definition`, `:direction`, and `:layout-options` (the host pulls
  `:definition` from `(rf/machine-meta machine-id)`). This plane is
  **structural**: changing it requires re-laying out the graph via
  elkjs.
- **Runtime-highlight plane.** The active-state affordance (static
  tint + bolder stroke; pulse retired 2026-05-20 per rf2-2sez0), the
  focused-event lens tint, the `:after` countdown-ring overlay, the
  `:spawn-all` join inspector, the cancellation cascade, and every
  other per-trace decoration. Derived solely from the decoration props
  `:current-state`, `:from-highlight`, `:to-highlight`, `:sim?`,
  `:after-ring-specs`, `:after-ring-tick`, `:spawn-all-join`,
  `:cancellation-cascade`, and `:overlay-tick`. This plane is
  **decorative**: changing it MUST NOT touch the topology plane.

The decoration props MUST NOT participate in any computation whose
output reaches the layout plane. The two planes share no caches.

### Highlight / overlay prop changes MUST change attrs/classes only

A render driven by a change to any decoration prop (a new
`:current-state`, `:from-highlight` / `:to-highlight`, an
`:after-ring-tick` bump, a new `:spawn-all-join` / `:cancellation-cascade`
spec, an `:overlay-tick` bump) MUST mutate **only**:

- DOM attributes (`class`, `style.opacity`, `style.transform` on
  decoration layers, SVG `stroke-dasharray` / `stroke-dashoffset`,
  ARIA labels).
- CSS-driven animations (the transition glow, the
  `prefers-reduced-motion` step animation). The heartbeat pulse
  was retired 2026-05-20 (rf2-2sez0).

Such a render MUST NOT:

- Re-run layout (no `elk`/`dagre`/custom layout call).
- Re-measure topology nodes (no `getBBox`, no `getBoundingClientRect`
  in any code path reached by a decoration-prop change — overlay
  re-measure of its own anchor DOM is permitted and is gated by the
  host's tick props).
- Insert or remove topology DOM nodes (state nodes, edge paths,
  compound containers). Decoration overlays (rings, the join
  inspector, the cascade) MAY mount and unmount; topology MUST NOT.
- Mutate any value the topology plane recomputes from.

A decoration-prop update arriving at 60Hz (e.g. an `:after-ring-tick`
bump) MUST cost less than one paint frame end-to-end on the chart's
hot path.

### Layout-invalidation boundary is load-bearing

`MachineChart` keys its elkjs layout pass on the
`[:definition :direction :layout-options]` tuple (per `chart.cljs`): a
new layout runs **only** when that tuple changes, and the previous
positions are kept in-flight to avoid an empty-chart flash. The **only**
triggers permitted to invalidate the topology / layout plane are:

1. **A new `:definition`.** A changed definition map — including a
   `reg-machine` hot-reload re-registration the host re-pulls via
   `(rf/machine-meta machine-id)` and re-passes — re-runs layout.
2. **A `:direction` change** (`:tb` ⇄ `:lr`).
3. **A `:layout-options` change** — host-side elkjs `layoutOptions`
   overrides.
4. **Container resize** of the chart's bounding box (xyflow's own
   fit/measure; the elk pass itself is keyed on the tuple above).

No other code path may invalidate layout. In particular:

- A `:current-state` / `:from-highlight` / `:to-highlight` change
  MUST NOT reach the layout pipeline — it only re-tints existing nodes.
- An `:after-ring-tick` / `:overlay-tick` bump or a new
  `:after-ring-specs` / `:spawn-all-join` / `:cancellation-cascade`
  spec MUST NOT reach the layout pipeline — these mount / repaint
  decoration overlays only.

Implementations MUST place an explicit comment marking the
layout-invalidation boundary as load-bearing in the code that owns
it (the function that decides "should layout re-run?" — the
`this-key`/`layout-key` guard in `chart.cljs`). The comment MUST cite
this section and DESIGN-RATIONALE Lock #9 and Lock #11.

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
  - `:machine-id` from the payload's `:chart-state` (per
    [§Share-URL payload schema](#share-url-payload-schema)).
  - `:definition` from the payload's `:definition`.
  - `:current-state` set to the payload snapshot's `:state` keyword
    (the share schema carries the state name only; there is no
    runtime `:data` to render).
  - `:read-only?` set to `true`, which no-op's `:on-state-click`.
  - The decoration-overlay props (`:after-ring-specs`,
    `:spawn-all-join`, `:cancellation-cascade`, the tick props) left
    unset — a static share has no live trace bus to project them from.
- A single banner at the top of the page reads: **"This is a
  static machine chart, not a Causa session — interactions are
  disabled."**
- A "show idle" toggle below the banner clears `:current-state` so the
  chart renders the machine at rest. Per
  [DESIGN-RATIONALE Lock #5](./DESIGN-RATIONALE.md).
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
   [:definition  MachineDefinition]     ;; the topology (states, transitions, guards, actions, :spawn, :spawn-all, :after)
   [:snapshot   {:optional true}        ;; the current-state name at share time — name ONLY, no :data
    [:map {:closed true}
     [:state    keyword?]]]])           ;; the registered state-id; nothing else permitted in :snapshot
```

The `:snapshot` map is `{:closed true}` and carries `:state` only.
Runtime `:data` is structurally absent from the share payload; the
encoder neither reads nor serialises it. The decoder rejects any
`:snapshot` carrying additional keys with `:invalid-chart-state`.
The viewer page mounts `MachineChart` with `:current-state` set to the
snapshot's `:state` keyword (there is no runtime `:data` to render);
the chart highlights the state node without any data-driven affordance.
(`:frame-id` is a payload provenance field — the registered machine's
frame at share time — not a `MachineChart` prop: the viewer hands the
chart the `:definition` directly rather than resolving a frame.)

`:source-coords` is **not a top-level key** of `ChartState`. Source
coords live only in the operator-side `(rf/machine-meta machine-id)`
return value and never traverse the share pipeline. The viewer page
renders with `:read-only?` true so `:on-state-click` is no-op'd (per
[§Read-only viewer](#read-only-viewer)), so the absence of source
coords is observationally invisible.

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
- `:spawn-all` rows of mini-machines — Mermaid has no
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

## SCXML import / export (v1.1, rf2-6urjd)

SCXML is the W3C standard for statecharts. Round-tripping through
SCXML lets re-frame2 machines be shared with non-CLJS tooling —
external workflow systems, Erlang `gen_statem`-derived tools,
Stately's importers, the xstate-visualizer. Same pure-data posture
as the Mermaid emitter: a machine definition in, an XML string out;
and the inverse on the read side.

```clojure
(:require [day8.re-frame2-machines-viz.scxml :as scxml])

(scxml/spec->scxml machine-spec)
;; => "<?xml version=\"1.0\" ...?>\n<scxml ...>...</scxml>"

(scxml/scxml->spec scxml-string)
;; => the parsed machine spec
```

### Round-trip

```clojure
(= machine-spec (-> machine-spec scxml/spec->scxml scxml/scxml->spec))
```

holds for the supported subset.

### Supported grammar subset

| Re-frame2 | SCXML mapping |
|---|---|
| `:initial`                            | `<scxml initial="...">` |
| `:states` (flat)                      | `<state id="...">` |
| `:states` (compound)                  | nested `<state>` with `initial` |
| `:final? true`                        | `<final id="...">` |
| `:on {:event :target}`                | `<transition event="event" target="target"/>` |
| `:on {:event {:target ... :guard G}}` | `<transition cond="G" .../>` |
| `:after {ms :target}`                 | `<transition event="after.ms" target="target"/>` |
| `:always [...]`                       | `<transition target="..."/>` (eventless) |
| `{:type :parallel :regions ...}`      | `<parallel>` containing region `<state>`s |
| Namespaced ids (`:auth/login`)        | `auth.login` (dot-separated; SCXML id grammar) |
| Vector-path targets                   | dot-joined `parent.child.grandchild` |

### Not supported (lossy or omitted)

- `:spawn-all` rows — omitted; the parent state renders without
  spawn affordances.
- `:tags` — re-frame2-specific; not part of W3C SCXML.
- `:action`s and guard FN bodies — only the *names* survive
  (SCXML `cond="name"` for guards; entry/exit `<script>` would
  require evaluation context, so names are preserved as XML
  comments on imports/exports).
- Source-coord metadata — stripped at export time (same posture as
  share-URL encoding; see [Principles §No session data in shares](./Principles.md)).

### Error modes

| `:reason` | Meaning |
|---|---|
| `:scxml/invalid-spec` | Input spec missing `:initial` / `:states` (or `:type :parallel` / `:regions`). |
| `:scxml/parse-error`  | Input XML is malformed or missing the `<scxml>` root. |

## AI-generate-a-machine (v1.1, rf2-1bncf)

A pure library fn that takes a natural-language prompt and returns
a normalised re-frame2 machine spec. The LLM call is pluggable —
the fn accepts an injected `:resolver` so callers wire in whichever
LLM bridge fits their environment (Anthropic API / OpenAI API /
local Ollama / Causa's chat seam / re-frame2-pair-mcp).

```clojure
(:require [day8.re-frame2-machines-viz.ai-generate :as ai])

(ai/generate-machine "a login flow with idle, loading, success and error states"
                     {:resolver (fn [prompt] (call-anthropic prompt))})
;; => {:initial :idle
;;     :states  {:idle    {:on {:login :loading}}
;;               :loading {:on {:ok :success :err :failed}}
;;               :success {:final? true}
;;               :failed  {:final? true}}}
```

### Contract

- `(generate-machine user-prompt opts)` returns the validated spec
  the same shape `reg-machine` accepts and `(rf/machine-meta id)`
  returns.
- `opts` recognises:
  - `:resolver` — `(fn [prompt-string] llm-response-string)`. Required.
    The namespace ships no default LLM bridge — production callers
    inject one, tests inject a stub returning canned EDN.

### Two-layer design

The implementation separates the I/O boundary (the injected
resolver) from the parse/validate step (this ns). The fn:

1. Composes `system-prompt + user-prompt` into a single string via
   `ai/build-prompt` (the canonical system prompt lives at
   `ai/system-prompt`, exposed as a Var for audit / multi-turn
   composition).
2. Hands the prompt to `:resolver` and waits for a string response.
3. Strips fenced code blocks (```clojure / ```edn / bare) tolerantly,
   so the LLM may emit prose around the EDN form.
4. Parses the EDN form and validates it carries `:initial` + non-
   empty `:states` (or `:type :parallel` + non-empty `:regions`).

### Reserved namespaces

Generated machines use re-frame2's normal id conventions — feature-
prefixed keywords (`:auth/idle`, `:cart/loading`), hyphenated bare
names (`:idle`, `:loading-failed`). The system prompt asks the LLM
to follow them; the parser does not enforce them (an LLM that
emits `:loadingFailed` produces a working spec the caller can
clean up or accept as-is).

### Error modes

| `:reason` | Meaning |
|---|---|
| `:ai-generate/no-resolver`  | `:resolver` opt was not provided. |
| `:ai-generate/parse-failed` | Resolver output could not be parsed as EDN. |
| `:ai-generate/invalid-spec` | Parsed value was not a valid machine shape. |

### Determinism

The fn itself is deterministic given a deterministic resolver. LLM
resolvers are not deterministic by default; for reproducible tests
inject a stub mapping known prompts to canned EDN responses (see
the AI-generate test ns for examples).

## Public CLJS API surface — summary

```clojure
day8.re-frame2-machines-viz.chart/MachineChart    ; component (Reagent)
day8.re-frame2-machines-viz.adapters.react-chart/MachineChartReactClass ; rf2-yg9he — reactified React class
day8.re-frame2-machines-viz.adapters.react-chart/chart-element          ; rf2-yg9he — CLJS props → React element
day8.re-frame2-machines-viz.adapters.uix/MachineChart   ; rf2-yg9he — UIx shell ($-mountable)
day8.re-frame2-machines-viz.adapters.helix/MachineChart ; rf2-yg9he — Helix shell ($-mountable)
day8.re-frame2-machines-viz.share/encode-share-url
day8.re-frame2-machines-viz.share/decode-share-url
day8.re-frame2-machines-viz.mermaid/emit          ; pure fn — definition → string
day8.re-frame2-machines-viz.scxml/spec->scxml     ; v1.1 — pure fn
day8.re-frame2-machines-viz.scxml/scxml->spec     ; v1.1 — pure fn
day8.re-frame2-machines-viz.ai-generate/generate-machine ; v1.1 — pluggable LLM seam
day8.re-frame2-machines-viz.ai-generate/build-prompt     ; v1.1 — prompt composer
day8.re-frame2-machines-viz.ai-generate/system-prompt    ; v1.1 — Var
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
(modulo the clipboard). The v1.1 SCXML + AI-generate surfaces are
pure-data and JVM-callable.

## See also

- [`000-Vision.md`](./000-Vision.md) — scope + non-goals + roadmap.
- [`Principles.md`](./Principles.md) — load-bearing principles.
- [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) — locks; cites Causa 003 lift-points. Lock #8, Lock #9, and Lock #11 are the rationales behind [§Performance invariants](#performance-invariants).
- [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md) — embedding-host contract; the source spec these surfaces lifted from.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the registry the chart visualises.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) — the trace bus the live-highlight consumes.
