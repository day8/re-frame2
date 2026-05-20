# DESIGN-RATIONALE

The direction-setting decisions that shape Machines-Viz. Each entry
captures:

- **The question** that was being decided.
- **Options considered** that were walked through.
- **The pick** that was locked.
- **The why** — the reasoning trail.
- **Date locked** + the locker.

This doc is the load-bearing artefact of the per-tool spec
convention. It exists so that a future contributor (human or AI) can
read it and not re-ask the same questions. Where the spec text reads
"per lock #N in DESIGN-RATIONALE.md", this is where to come.

A subset of these locks are **lifted from
[`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)**.
Causa Spec 003 specified `MachineChart`'s prop surface, share-URL
encoding, transition-history ribbon, and `:spawn-all` viz before
this tool had its own spec folder. The scaffold (rf2-x50eu)
migrates those decisions into Machines-Viz's home; Causa's 003
remains the authoritative source for the **embedding-side**
contract (transition history ribbon UX, source-coord jumps,
Causa's panel chrome). Where the two could drift, this doc cites
back to 003.

---

## Lock #1 — Scope: component-not-product

**Locked 2026-05-13 (Mike).** **Ship as one embeddable component
plus a read-only viewer page.** No paste-and-render editor, no
saved-chart registry, no multiplayer canvas.

### Question

What surface does Machines-Viz ship at v1.0?

### Options considered

- **A Stately-Visualizer equivalent.** A web app where the user
  pastes a `reg-machine` body and watches it render. Rejected as
  product-shaped.
- **A registry SaaS shell.** Save charts, share via URL or iframe,
  Day8-hosted. Rejected as product-shaped and on the privacy axis.
- **A multi-user canvas.** Multiplayer editing à la Stately Studio.
  Rejected — out of lane.
- **One embeddable component + a static read-only viewer page.**
  Causa and Story embed the component; the viewer page renders a
  shared URL. The framework owns the registry (via `reg-machine`);
  Machines-Viz owns the rendering.

### Pick

**One embeddable component + a static read-only viewer page.**

### Why

- **Stately's own Visualizer is officially deprecated** within
  their docs (per `ai/findings/xstate-advanced-features-2026-05-13.md`,
  a gitignored working note).
  The commercial Stately Editor is the recommended replacement.
  Investing in the deprecated-product lane is chasing a sunset.
- **Restraint principle** — a chart component the framework's own
  tools embed has clear scope. A visualiser product accretes
  features (registry, sharing UX, collaboration, billing, auth).
  Each feature is "easy to add, hard to remove."
- **Privacy by surface** — a Day8-hosted registry means Day8 sees
  customers' machines. A static viewer + a client-side share-URL
  means we see nothing. The privacy bet beats the utility bet.
- **Bundle isolation holds trivially** — the component is the
  only artefact; the viewer is a separate page. No conditional
  compilation, no per-host build tricks.
- **Mermaid covers the static-paste lane** — Spec 005's post-v1
  `(machine->mermaid)` exporter handles Markdown-embed cases; the
  share-URL viewer handles interactive cases. Both lanes are
  served without growing this jar.

---

## Lock #2 — `MachineChart` prop contract

**Locked 2026-05-13 (Mike, lifted from Causa 003).**
**Props: `:machine-id`, `:frame-id`, `:on-state-click`,
`:on-transition-click` (plus read-only flags).**

### Question

What does the host pass to `MachineChart` to render a chart?

### Options considered

- **Pass the machine definition directly.** The host pulls the
  registration via `(rf/machine-meta id)` and passes the
  transition table to the chart. Rejected — couples hosts to the
  framework's internal definition shape and makes hot-reload
  fragile (the chart wouldn't re-pick-up edits without explicit
  host plumbing).
- **Pass the snapshot directly.** The host derefs
  `[:rf/machines <id>]` and passes the snapshot to the chart.
  Rejected — the chart loses access to history (transition
  events, microstep replay, `:after` rings); it would have to
  re-derive snapshot transitions from frame deltas.
- **Pass an id + a frame.** The chart resolves the registration
  via `(rf/machine-meta machine-id)` and subscribes to
  `[:rf/machines machine-id]` within `frame-id`. The host stays
  thin; the chart owns its data plane.

### Pick

**Pass an id + a frame, plus two callbacks.**

```clojure
[viz/MachineChart {:machine-id          :auth/login-flow
                   :frame-id            :app/main
                   :on-state-click      (fn [state-id state-meta] ...)
                   :on-transition-click (fn [from event-id to] ...)
                   :read-only?          false      ;; viewer page sets true
                   :show-microsteps?    true       ;; per-chart preference
                   :show-after-rings?   true
                   :show-invoke-all?    true}]
```

### Why

- **Thin host interface** — the host doesn't need to thread the
  definition or the snapshot. Causa's panel is a one-liner;
  Story's per-variant panel is the same.
- **Hot-reload safe** — the chart re-reads `(rf/machine-meta id)`
  on every mount and on registry-change traces; the host doesn't
  have to plumb hot-reload through.
- **Callback shape locks the host's freedom** — Causa wires
  `:on-state-click` to "jump to source"; Story wires it to
  "highlight in the per-variant chrome"; the viewer page no-ops
  both. Each host chooses, the component stays neutral.
- **Per-chart visibility toggles ride on props** — `:show-microsteps?`
  etc. let the host (or the chart's own settings menu) toggle
  granularity without re-mounting. Defaults are conservative;
  hosts opt in.
- **Read-only flag opts out of clicks** — the viewer page sets
  `:read-only? true` so neither callback fires regardless of
  what the host passes.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Embedding posture. The prop names matched there are reproduced
here verbatim.

---

## Lock #3 — Share-URL encoding: EDN → transit → base64url

**Locked 2026-05-13 (Mike, lifted from Causa 003).** **Pipeline
is `chart-state → EDN-print → transit-write → base64url → URL
fragment`. Versioned envelope; topology + current-state snapshot
only.**

### Question

How does Machines-Viz encode a chart for sharing?

### Options considered

- **JSON.** Lossy for keywords, sets, namespaced keys. Rejected
  on the EDN-first principle.
- **EDN raw + URL-encode.** Verbose; large machines blow past the
  URL length limit fast.
- **Transit-write + base64url, unversioned.** Compact; URL-safe.
  Rejected on the format-evolution axis — without a version
  envelope, future encoding changes are breaking.
- **EDN → transit-write → base64url + versioned envelope.** The
  envelope keys (`:rf.machines-viz.share/v`,
  `:rf.machines-viz.share/chart`,
  `:rf.machines-viz.share/created`) ride at the outermost level;
  decoders dispatch on `:rf.machines-viz.share/v`.

### Pick

**EDN → transit-write → base64url + versioned envelope.**

### Why

- **EDN preserves the registered shape** — keywords, sets, nested
  maps, namespaced keys all round-trip. JSON would force re-
  coercion.
- **Transit is compact** — typical machines (~20 states, ~30
  transitions) encode to a sub-4KB URL after base64url. Common
  URL limits sit around 8KB; we have headroom for moderate-sized
  charts.
- **Base64url is URL-safe** — no `+` or `/` in the alphabet; no
  percent-encoding contention.
- **Fragment, not query** — URL fragments are never sent in HTTP
  requests; the share-URL is invisible to servers and to
  browsers' navigation logs. The chart never traverses a server
  by accident.
- **Versioned envelope is the cheapest evolution insurance** —
  any future change to the payload schema bumps `:rf.machines-viz.share/v`;
  old decoders refuse old payloads with a clear message rather
  than rendering garbage.
- **Charts exceeding URL limits** — the `Copy as edn fragment
  instead` fallback puts the EDN on the clipboard for paste-into-
  a-doc. Per Causa 003 §Share affordance §Performance.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Share affordance §Share URL + §Performance.

---

## Lock #4 — Markdown-embed: Mermaid emit ships at v1.0

**Locked 2026-05-13 (Mike); revised 2026-05-15 (Mike, per
rf2-deo2i — Mermaid emit promoted from v1.1 to v1.0 as a thin
static-topology exporter).** **Machines-Viz ships a Mermaid
`stateDiagram-v2` exporter at v1.0 alongside the PNG / SVG /
share-URL trio. The emitter is pure-data, JVM-side, and lossy by
design: `:after` target edges render while rings are omitted,
parallel regions render as independent region trees without broadcast
macrostep semantics, `:spawn-all` rows are omitted, and the loss is
flagged in the emitted output.**

### Question

Should Machines-Viz emit Mermaid `stateDiagram-v2` as one of its
export formats?

### Options considered

- **Ship Mermaid emit at v1.0** (this pick, revised 2026-05-15).
  Differentiator vs Stately Visualizer (which has no Markdown-embed
  posture). The static topology round-trips cleanly; `:after` rings
  and `:spawn-all` rows are flagged as omitted in a leading
  `%% comment`, while `:after` target edges and parallel-region
  trees still render as lossy static topology. Users who want the
  full topology fall back to the share-URL viewer / SVG export.
- **Defer to v1.1.** The static topology fits Mermaid; `:after`
  rings, microstep replay, `:spawn-all` joins do not. The output
  is a partial chart; users may not realise. *Original 2026-05-13
  pick; revised away from 2026-05-15.*
- **Don't ship at all in Machines-Viz; defer to the framework's
  `(rf/machine->mermaid)`.** Spec 005's post-v1
  `(rf/machine->mermaid definition)` is already enumerated as a
  framework deliverable; Machines-Viz duplicates it.

### Pick

**Ship Mermaid emit at v1.0 — as
`day8.re-frame2-machines-viz.mermaid/emit` (the pure data fn) plus
`export/chart-as-mermaid` + `export/copy-mermaid-to-clipboard!` (the
DOM-side wrappers).**

### Why

- **The static-paste lane is wide.** "Paste this into a GitHub
  README and it renders" is Mermaid's killer feature; every peer
  state-chart tool (Stately Studio, XState Inspector, plain
  text-based stateCharts) hits the lane. A v1 surface that ships
  PNG / SVG / share-URL but omits Mermaid leaves the cheapest
  paste-into-Markdown affordance on the floor.
- **The emitter is small.** Pure-data, JVM-callable, no
  substrate coupling. ~150 lines of CLJC; the spec footprint is
  larger than the code footprint. The "duplication with the
  framework-level `(rf/machine->mermaid)`" worry from the original
  2026-05-13 pick is misplaced — Machines-Viz consumes a
  `definition` map (from `(rf/machine-meta id)`), the framework
  consumes the same `definition` map; the two are the same
  function on the same input, and either one being absent leaves
  a gap. Per [Principles §Observation only](./Principles.md),
  Machines-Viz can host the canonical implementation since the
  function is observation-only (pure-data, no runtime surface);
  the framework-level forward-pointer in
  [Spec 005 §Future §Diagram export](../../../spec/005-StateMachines.md#diagram-export-from-transition-tables)
  remains as a re-export surface for consumers who pull
  `re-frame2` without the tools jar.
- **Lossy-output semantics is documented inline.** The emitter
  prepends a `%% Generated by re-frame2-machines-viz · :after
  rings + :spawn-all rows omitted` comment to every output, plus
  the parallel-region broadcast-semantics caveat.
  Readers who paste the output into a doc see the caveat without
  consulting the spec; readers who suppress the comment via
  `{:header-comment? false}` are taking responsibility explicitly.
- **The Mermaid lane and the share-URL lane are complementary,
  not competitive.** The share-URL viewer renders the full
  topology including rings and joins; Mermaid doesn't. Users
  reach for whichever surface fits their channel: PR description →
  Mermaid; live-debugging chat → share-URL viewer.
- **Test cost is low.** Pure-data emitter + JVM `clojure -M:test`
  exercising the canonical idle → loading → success/error fixture
  plus compound-state, namespaced-id, and wildcard-edge cases.
  No browser test, no shadow-cljs build dependency.

### Why the 2026-05-13 → 2026-05-15 revision

The original 2026-05-13 lock argued the framework-level
`(rf/machine->mermaid)` covered the lane and Machines-Viz would
duplicate. Re-reviewing post-rf2-deo2i: (a) the framework-level
exporter is post-v1 (per Spec 005 §Future) with no concrete
landing-bead; (b) consumers reach for Mermaid via Machines-Viz's
clipboard / share affordances (the same UI that hosts PNG / SVG /
share-URL), not via a framework-level fn call; (c) the function is
data-only and substrate-independent, so it can live in
Machines-Viz without violating the bundle-isolation contract; and
(d) the "we'll add it in v1.1 as a thin wrapper around the
framework's exporter" plan in the original lock is strictly more
work than landing the emitter in Machines-Viz directly.

Source: lifted to v1.0 per rf2-deo2i; the framework-level
`(rf/machine->mermaid)` forward-pointer in Spec 005 §Future
remains as a re-export surface (a thin shim that calls
`day8.re-frame2-machines-viz.mermaid/emit`), to be wired when the
framework ships its `re-frame.machines` public API.

---

## Lock #5 — Current-state in share-URL: state name only, no `:data`

**Locked 2026-05-13 (Mike, lifted from Causa 003; tightened
2026-05-14 per rf2-li3o4).** **The share-URL payload carries the
topology + the current state's name (`:state` keyword only). No
runtime `:data`; no transition history; no event vector; no
app-db slices; no source-coords.**

### Question

What state-information does the share-URL carry?

### Options considered

- **Topology only.** The chart renders with no active state; the
  recipient sees the machine "at rest." Rejected — the sharer's
  intent is usually "look at the state I was in"; a topology-only
  share loses that context.
- **Topology + a transition history.** The chart can replay the
  last N transitions on load. Rejected — encodes session data,
  violates the no-session-export principle (Causa Lock #4 lifted),
  inflates the URL beyond practical limits.
- **Topology + full snapshot (state + `:data`).** The original
  lift from Causa 003. Rejected (rf2-li3o4) — `:data` is operator-
  side runtime accumulation; a well-intentioned "Copy as Share
  URL" click would exfiltrate tokens, form contents, and request
  payloads. The accident-class beats the visual-continuity
  marginal benefit.
- **Topology + the state name only.** The chart highlights the
  active state node; `:data` is unavailable to the viewer (which
  has no per-data affordance anyway — the chart's data display
  is operator-side, in Causa's inspector chrome). The recipient
  has a "show idle" toggle to clear the highlight.

### Pick

**Topology + the state name only (`:snapshot` is `{:closed true}`
with `:state` only).**

### Why

- **Visual continuity holds** — the recipient sees which state the
  sharer was in; the static active-state affordance needs the
  state name only.
  The chart's data display is operator-side chrome (Causa's
  inspector panel), not a `MachineChart` affordance.
- **Privacy is structural, not prose** — the encoder cannot emit
  `:data` because the schema is `{:closed true}` on `:snapshot`.
  Operators cannot accidentally share runtime values; an
  operator who wants to share a data value pastes it explicitly
  via a different channel.
- **Reproducible-from-registry-alone** holds for the topology;
  the state name is the only non-reproducible bit and is purely
  a visual hint.
- **"Show idle" toggle** — the viewer page offers a one-click
  "clear active state" if the recipient wants to discuss the
  machine in the abstract. Causa 003 §Share-URL §Read-only viewer
  specifies this affordance.
- **No source-coords either** — source coords carry absolute file
  paths; the viewer has no editor handler wired; they are
  structurally absent from the share schema. Definition metadata
  is stripped before serialisation so macro-captured coords cannot
  leak through `:definition` either.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Share-URL §NOT a session export + §Privacy posture, then
tightened per the rf2-li3o4 security audit (the lift carried a
broader `:data :any` than this jar's Principles permitted; the
schema now matches the Principles).

---

## Lock #6 — Read-only viewer: same component, click handlers no-op'd

**Locked 2026-05-13 (Mike, lifted from Causa 003).** **The
viewer page mounts the same `MachineChart` component with
`:read-only? true`. No transition-history ribbon (there's no
history); a single banner labels it as a static snapshot.**

### Question

What renders inside the read-only viewer page?

### Options considered

- **A reduced-feature `StaticMachineChart` component.** Separate
  artefact, narrower surface. Rejected on duplication grounds.
- **The same `MachineChart` with click handlers no-op'd.** The
  `:read-only? true` flag suppresses callback firing; the
  component renders normally.

### Pick

**Same component, `:read-only? true`.**

### Why

- **One renderer, one set of bugs.** The static viewer and the
  embedded inspector go down identical render paths; any
  rendering bug surfaces in both.
- **The component already encapsulates the rendering.** Hiding
  the transition-history ribbon is the host's responsibility —
  the viewer page's host renders no ribbon, Causa's host renders
  one.
- **The banner labels the surface** — "This is a static machine
  chart, not a Causa session — interactions are disabled." Per
  Causa 003 §Read-only viewer.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Share-URL §Read-only viewer.

---

## Lock #7 — Hosting posture: statically hostable, client-side rendering

**Locked 2026-05-13 (Mike).** **The viewer page is a single
static HTML file + the Machines-Viz CLJS bundle; no server logic;
no Day8-hosted infrastructure.**

### Question

How does the read-only viewer page get to a user?

### Options considered

- **Day8-hosted SaaS shell** with a backing database for saved
  charts. Rejected on Lock #1 (component-not-product) plus
  privacy (no Day8 sees customer machines).
- **Day8-hosted static page** with no backend; client-side
  decoding. Cleanest of the hosted options.
- **Statically hostable; consumer hosts the page themselves**
  alongside their app. No Day8 dependency.

### Pick

**Statically hostable; the canonical hosted instance lives at
`day8.github.io/re-frame2-machines-viz/` (or equivalent docs URL)
but is **not load-bearing** — every consumer can self-host.**

### Why

- **No Day8-side service to operate.** The page is a static
  asset; if `day8.github.io` goes down, every self-hoster keeps
  working.
- **Privacy holds maximally.** Even the canonical hosted page
  decodes client-side; the URL fragment is never transmitted.
- **Embed in docs without iframe gymnastics.** The viewer page
  can be loaded by a `<script>` tag in the consumer's own docs
  site; the consumer chooses where it lives.
- **The canonical instance is a convenience, not a contract.**
  Users sharing a URL with the canonical viewer get one-click
  rendering; users who don't trust day8.github.io can self-host
  the page and point share-URLs at their own instance.

---

## Lock #8 — `:after` countdown rings: 60Hz when visible, paused when not

**Locked 2026-05-13 (Mike, lifted from Causa 003).** **`:after`
countdown rings render at 60Hz while the chart is on-screen;
backgrounded charts pause the ring render but the underlying
timer keeps running.**

### Question

How do `:after` countdown rings update?

### Options considered

- **Continuous 60Hz.** Smooth; uses more CPU than necessary when
  the chart isn't visible.
- **Event-driven via trace stream.** The ring updates only on
  `:rf.machine.timer/scheduled` / `-fired` events. Choppy.
- **60Hz when visible, paused when backgrounded.** The
  `IntersectionObserver` / `document.visibilityState` gates the
  render loop; the framework's clock (per Spec 005) keeps
  running, so the timer fires correctly whether or not the ring
  is rendering.

### Pick

**60Hz when visible, paused when backgrounded — driven by a single
per-chart animation clock, never per-node / per-ring timers.**

### Why

- **The timer is the source of truth, not the ring.** The ring
  is a visualisation of the timer; pausing the ring does not pause
  the timer.
- **Backgrounded panels burn no CPU.** A Story page with 50
  variants, each with a chart, each with a ring, would consume
  significant CPU if every ring rendered at 60Hz. Visibility-
  gating reduces it to "only the visible charts pay the cost."
- **One clock per chart, not per ring.** Per-node /
  per-ring `requestAnimationFrame` loops scale O(rings × charts);
  a single per-chart clock scales O(charts). A 50-chart × 5-ring
  Story grid means 50 loops, not 250. This is enforced as a MUST
  in [API.md §Performance invariants](./API.md#performance-invariants)
  and is part of the load-bearing layout/runtime separation in
  Lock #11 below.
- **`prefers-reduced-motion` clamps the ring to a step
  animation** — not a smooth fill. The user controls.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Performance + §Animation (the broader UX baseline lives in
[Causa 007-UX-IA](../../causa/spec/007-UX-IA.md)). The
single-animation-clock clarification was added 2026-05-14 per the
perf-audit findings (rf2-j3iwt) and rf2-t1yvw.

---

## Lock #9 — Layout: recompute on registry change, not on transition

**Locked 2026-05-13 (Mike, lifted from Causa 003); tightened
2026-05-14 (Mike, per rf2-t1yvw + perf-audit rf2-j3iwt).** **Chart
layout MUST run only on machine (re-)registration, user-driven
compound-state expand/collapse, a `:show-*` prop transition that
adds or removes topology, or chart container resize. Live
transitions, microsteps, timer updates, and snapshot derefs MUST
update node attributes/classes only — never re-lay-out.**

### Question

When does Machines-Viz recompute chart layout?

### Options considered

- **On every transition.** Smooth animation; flickers on large
  charts as the layout shifts.
- **Never** — fixed layout from first registration. Compound-
  state expand/collapse is broken.
- **On registry change + on compound expand/collapse only.**
  The layout is stable across transitions; the highlight moves
  through a stable graph.

### Pick

**On machine (re-)registration + on user-driven compound
expand/collapse + on a `:show-*` topology-affecting prop transition
+ on container resize. No other trigger.**

The exhaustive enumeration of permitted layout-invalidation triggers
— and the explicit denylist of trace events and snapshot derefs —
lives in
[API.md §Performance invariants §Layout-invalidation boundary is
load-bearing](./API.md#performance-invariants). Implementations MUST
place an explicit comment marking the layout-invalidation boundary
as load-bearing, citing that section and this lock.

### Why

- **Stable graphs are easier to read.** A chart that re-layouts
  on every transition makes the user re-orient on every event;
  cognitive cost wins over visual flourish.
- **Performance** — re-layout is O(nodes × edges) at minimum;
  60Hz re-layout on a 50-state chart is expensive. The highest-
  risk regression in this jar is a future impl coupling
  trace-tick or snapshot deref to layout; once that coupling
  lands it is hard to remove without rewriting the rendering
  pipeline (per `ai/findings/perf-audit-machines-viz-2026-05-14.md`
  finding 1 — gitignored working note; audit-bead rf2-j3iwt).
  The MUST in
  [API.md §Performance invariants](./API.md#performance-invariants)
  exists so that coupling cannot accidentally land.
- **Hot-reload** triggers a registry-change event, which triggers
  a layout pass — so editing a machine and saving causes the
  chart to redraw correctly without manual intervention.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Performance; tightened to MUST 2026-05-14 per rf2-t1yvw + audit-bead
rf2-j3iwt.

---

## Lock #10 — Source migration from Causa 003

**Locked 2026-05-13 (Mike).** **The following content migrates
from Causa 003 to this jar's spec when implementation work
begins. Until then, Causa 003 remains the read-the-spec source of
truth.**

### Migrating content

| Topic | Causa 003 section | Lands in Machines-Viz spec at |
|---|---|---|
| `MachineChart` prop contract | §Embedding posture | [`API.md`](./API.md) §MachineChart (already lifted) |
| Share-URL encoding pipeline | §Share affordance §Share URL + §Performance | [`API.md`](./API.md) §Share-URL encoding (already lifted) |
| Read-only viewer behaviour | §Share-URL §Read-only viewer | [`API.md`](./API.md) §Read-only viewer (already lifted) |
| `:after` countdown rings — render rules | §Performance | [`API.md`](./API.md) §Performance invariants + Lock #8 above |
| Layout-recompute rules | §Performance | [`API.md`](./API.md) §Performance invariants + Lock #9 above |
| Topology / runtime-highlight separation (load-bearing) | (originated here 2026-05-14 per rf2-t1yvw) | [`API.md`](./API.md) §Performance invariants + Lock #11 below |
| `:spawn-all` viz — row of mini-machines | §`:spawn-all` viz | future 001-Rendering.md (post-scaffold; not in this PR) |
| PNG / SVG export — accessibility | §Accessibility | [`API.md`](./API.md) §Exporters |
| Privacy posture for share-URL | §Privacy posture | [Principles §No session data in shares](./Principles.md) (already lifted) |
| Auto-pan on transition | §Auto-pan | future 001-Rendering.md |

### What stays in Causa 003 (the embedding-host side)

- **Transition-history ribbon UX** — Causa's chrome around the
  chart; not part of `MachineChart` itself.
- **Source-coord jumps via Causa's editor-URL handler** — the
  framework registry exposes coords; Causa wires the URL handler;
  Machines-Viz fires `:on-state-click` and stops there.
- **Causa's machine picker dropdown.**
- **Causa's panel header + auto-pan toggle persistence.**

### Why

The split is **chart-component-concerns vs embedding-host-
concerns**. Everything that's about rendering / encoding /
export lives here; everything that's about Causa's panel chrome
or Story's per-variant ribbon lives in those tools' spec.

The migration is staged: this scaffold PR (rf2-x50eu) covers
[`API.md`](./API.md) + this rationale + Principles + Vision. The
detailed rendering / layout / export specs (a future
`001-Rendering.md`, possibly more) land as separate beads once
implementation work picks up.

Until those land, **Causa 003 is the source of truth for
unmigrated content**, and this DESIGN-RATIONALE cites back to it.

---

## Lock #11 — Topology / layout state and runtime-highlight state are strictly separate

**Locked 2026-05-14 (Mike, per rf2-t1yvw + perf-audit rf2-j3iwt).**
**`MachineChart` maintains two disjoint state planes — a topology /
layout plane and a runtime-highlight plane. The two share no
caches, no subscriptions, no derived values. Runtime-highlight
updates MUST mutate attrs / classes only; they MUST NOT reach the
layout plane.**

### Question

How does `MachineChart` keep the live-trace and snapshot hot path
from regressing into a full graph recompute?

### Options considered

- **One unified reactive graph.** Every reactive value flows into a
  single `MachineChart` render function; the renderer figures out
  what changed. Rejected — when the framework's reactive substrate
  is too lenient about granularity (or the renderer's diffing
  misses), trace ticks bleed into layout work. The audit
  (rf2-j3iwt) flagged this as the highest-risk future regression.
- **Disjoint planes by convention.** Document the separation in
  prose; trust the implementer to maintain it. Rejected — the perf-
  audit explicitly recommended raising this above guidance because
  "once the coupling lands, it is hard to remove without rewriting
  the rendering pipeline."
- **Disjoint planes as a load-bearing invariant.** A MUST in the
  spec, an explicit comment at the layout-invalidation boundary in
  the implementation, and a forbidden-paths list (trace events,
  snapshot derefs) that fence layout off from the hot path.

### Pick

**Disjoint planes as a load-bearing invariant.** Two state planes:

- **Topology / layout plane** — node positions, edge routes, compound
  nesting, the chart's measured viewbox. Owned by
  `(rf/machine-meta machine-id)` + the user's expand/collapse state +
  the `:show-*` props that affect topology presence.
- **Runtime-highlight plane** — static active-state affordance
  (tint + bolder stroke; the pulse was retired 2026-05-20 per
  rf2-2sez0), edge glow, microstep flash, `:after` ring progress,
  `:spawn-all` row state, spawn-tray contents, timer state. Owned
  by `[:rf/machines <id>]` + the `:rf.machine/*` trace bus.

The runtime-highlight plane MUST NOT participate in any computation
whose output reaches the topology plane. The two planes share no
caches.

The exhaustive normative rules live in
[API.md §Performance invariants](./API.md#performance-invariants):

- Topology vs runtime-highlight separation (MUST).
- Trace/snapshot updates change attrs/classes only (MUST).
- Layout-invalidation boundary enumeration + load-bearing comment
  requirement.
- Single chart-level visibility-gated animation clock for `:after`
  rings (MUST; never per-node timers).

### Why

- **Highest-risk future regression.** The perf-audit (rf2-j3iwt)
  identified this as the single largest regression surface for
  Machines-Viz. Once coupling between trace ticks and layout lands
  in an implementation, removing it requires rewriting the pipeline.
  Pinning the invariant before implementation lands is cheap;
  unpinning a coupled implementation later is expensive.
- **Two state planes match the data sources.** The topology comes
  from a registry that changes on edit; the runtime highlight
  comes from a snapshot + trace bus that change at runtime
  frequency. The implementation should reflect the same split.
- **Trace events arrive at 60Hz; layout is O(nodes × edges).**
  Coupling the two means every event pays the layout cost;
  decoupling means events pay only the attr-mutation cost. A 50-
  state chart with a 60Hz trace stream is the worst case the spec
  must protect against.
- **Spec is the artefact, code is downstream.** Per the project
  posture (CLAUDE.md), the spec is the load-bearing description.
  Pinning the invariant in the spec ensures every future
  implementer (human or AI) reaches the same answer without re-
  litigating it.
- **Visibility-gated single animation clock falls out of the same
  separation.** A per-ring `requestAnimationFrame` would cross the
  plane boundary (each ring would own its own subscription to the
  timer); a single chart-level clock that reads the framework
  timer respects it. Lock #8 codifies the visibility gating; this
  lock codifies the single-clock posture.

Per `ai/findings/perf-audit-machines-viz-2026-05-14.md` findings 1+2
(gitignored working note; audit-bead rf2-j3iwt).

---

## Open questions

The locks above cover the v1.0 surface. Open questions deferred
to implementation:

- **Layout algorithm** — Dagre? ELK? Custom? (Causa 003 doesn't
  pick; defer until first cut.)
- **SVG primitive vs Canvas vs HTML/CSS** — Causa 003 hints
  "SVG primitive" (per §Share affordance §Performance) but
  doesn't lock; defer to a `001-Rendering.md` bead.
- **Component substrate** — `MachineChart` is registered via
  `reg-view` (Spec 004) so it works across Reagent / UIx /
  Helix; the registration spec lives in a future capability doc.
- **Test corpus shape** — a snapshot-tested fixture per
  rendering case (compound, parallel, `:after`, `:spawn-all`,
  `:final?`); shape mirrors the framework's conformance corpus.
  Defer to implementation.
- **Edge labels on dense graphs** — Causa 003 doesn't address
  label collision; defer.

Each of these will land as a Locked entry above when picked, or
as a bead against this jar.

---

## See also

- [`tools/causa/spec/003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md) — the source spec these locks migrated from; embedding-host contract.
- [`tools/causa/spec/DESIGN-RATIONALE.md`](../../causa/spec/DESIGN-RATIONALE.md) — Causa's locks; #4 (no session export) is lifted into Lock #5 above.
- `ai/findings/xstate-advanced-features-2026-05-13.md` — visualizer-as-product deprecation rationale (gitignored working note).
- `ai/findings/sweep-tools-vs-sota-2026-05-13.md` §machines-viz — peer-comparison + gap analysis (gitignored working note).
- `ai/findings/perf-audit-machines-viz-2026-05-14.md` — perf audit findings 1+2 underpinning Lock #11 + the API.md §Performance invariants MUSTs; audit-bead rf2-j3iwt (gitignored working note).
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) §Future — `(machine->mermaid)` exporter forward-pointer.
