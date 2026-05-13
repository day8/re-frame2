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
| `:current-state-override` | no | `nil` | A `{:state ... :data ...}` map that overrides the live snapshot. Used by the read-only viewer page to render the shared snapshot. Out-of-scope for live charts. |

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
- It never enables `:on-*` callbacks. Hosts requesting a "click in
  the viewer goes to my docs site" would have to fork the page;
  the canonical viewer is read-only end-to-end.

Source: lifted from
[Causa 003 §Read-only viewer](../../causa/spec/003-Machine-Inspector.md#read-only-viewer).

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
   allowlist is silently dropped (per [Principles §No session data
   in shares](./Principles.md)).
2. Canonicalises map / set ordering (per
   [Principles §Reproducible from the registry alone](./Principles.md)).
3. Wraps in the versioned envelope.
4. EDN-prints → transit-writes → base64url-encodes.
5. Wraps the fragment into the `:host` URL.

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
   [:snapshot   {:optional true}        ;; the current-state snapshot at share time
    [:map
     [:state    keyword?]
     [:data     :any]
     [:meta?   {:optional true} :any]]]
   [:source-coords {:optional true}     ;; topology source-coords (per Spec 001) — only included if present in definition meta
    [:map-of :any :any]]])
```

The `MachineDefinition` shape matches the `reg-machine` registered
definition (per Spec 005 §Snapshot shape + §Transition table grammar)
with **only** the topology slots included — `:guards` and `:actions`
maps are encoded as **names only** (their fn bodies are not
serialised; consumers re-resolve against their own registry if they
want to run the machine). The viewer page never resolves them; it
only renders.

Anything not in the schema is silently dropped by the encoder. New
top-level keys require an explicit `:rf.machines-viz.share/allow?`
opt-in (per [Principles §No session data in shares](./Principles.md));
CI enforces.

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
day8.re-frame2-machines-viz.export/chart-as-png!
day8.re-frame2-machines-viz.export/chart-as-svg
day8.re-frame2-machines-viz.export/share-url
day8.re-frame2-machines-viz.export/copy-png-to-clipboard!
day8.re-frame2-machines-viz.export/copy-svg-to-clipboard!
day8.re-frame2-machines-viz.export/copy-share-url-to-clipboard!
```

No global state, no init function. The component is referentially
transparent over its props; the share / export functions are pure
(modulo the clipboard).

## See also

- [`000-Vision.md`](./000-Vision.md) — scope + non-goals + roadmap.
- [`Principles.md`](./Principles.md) — load-bearing principles.
- [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md) — locks; cites Causa 003 lift-points.
- [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md) — embedding-host contract; the source spec these surfaces lifted from.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the registry the chart visualises.
- [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) — the trace bus the live-highlight consumes.
