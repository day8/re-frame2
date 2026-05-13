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
encoding, transition-history ribbon, and `:invoke-all` viz before
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
  their docs (per
  [`ai/findings/xstate-advanced-features-2026-05-13.md`](../../../ai/findings/)).
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

## Lock #4 — Markdown-embed: Mermaid covers it, not us

**Locked 2026-05-13 (Mike).** **Machines-Viz does not emit
Mermaid at v1.** Spec 005's post-v1 `(machine->mermaid)` exporter
fills the Markdown-embed lane.

### Question

Should Machines-Viz emit Mermaid `stateDiagram-v2` as one of its
export formats?

### Options considered

- **Ship Mermaid emit at v1.0.** Differentiator vs Stately
  Visualizer (which has no Markdown-embed posture). Easy code
  (the topology round-trips cleanly).
- **Defer to v1.1.** The static topology fits Mermaid; `:after`
  rings, microstep replay, `:invoke-all` joins do not. The output
  is a partial chart; users may not realise.
- **Don't ship at all.** Spec 005's
  `(rf/machine->mermaid definition)` is already enumerated as a
  post-v1 framework deliverable. Machines-Viz duplicates it.

### Pick

**Don't ship at all in Machines-Viz; defer to the framework's
`(machine->mermaid)`.**

### Why

- **The framework's exporter lives upstream.** A consumer who
  wants a Markdown chart can call `(rf/machine->mermaid)` directly
  on the registered definition; no need to drag Machines-Viz
  into the toolchain.
- **No duplication of partial-output semantics.** If we ship
  Mermaid emit here, we have to explain "the rings are lost," "the
  microsteps are flattened," "the join-condition becomes a
  comment." The framework-level exporter explains it once.
- **Live wins for interactive cases.** The share-URL viewer
  renders the full topology including rings and joins; Mermaid
  doesn't. The two are complementary, not competitive.
- **Cheaper to walk away.** If usage data later shows demand for
  Mermaid emit inside Machines-Viz (say, because users want a
  one-click "share as Mermaid block" affordance), it can be added
  in v1.1 as a thin wrapper around the framework's exporter.

The share-URL viewer is the "paste this into a doc and have it
render interactively" affordance; `(machine->mermaid)` is the
"paste this into a Markdown file" affordance. Both lanes are
covered by complementary surfaces.

---

## Lock #5 — Current-state in share-URL: snapshot, not history

**Locked 2026-05-13 (Mike, lifted from Causa 003).** **The
share-URL payload carries the topology + a single current-state
snapshot. No transition history; no event vector; no app-db
slices.**

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
- **Topology + a single current-state snapshot.** The chart
  renders with the snapshot's state highlighted. The recipient
  has a "show idle" toggle to clear it.

### Pick

**Topology + a single current-state snapshot.**

### Why

- **Visual continuity** — the recipient sees what the sharer was
  looking at, the canonical case for sharing a chart.
- **Reproducible-from-registry-alone** holds for the topology;
  the snapshot is the only non-reproducible bit and is purely a
  visual hint.
- **Privacy holds** — no event vectors, no app-db slices, no user
  inputs. A snapshot is the machine's `:state` keyword plus its
  `:data` value (which the machine author controls); if the
  machine author put sensitive data in `:data`, they should treat
  the registered machine as code, which is the same rule that
  applies to source. The share-URL does not leak run-time data.
- **"Show idle" toggle** — the viewer page offers a one-click
  "clear active state" if the recipient wants to discuss the
  machine in the abstract. Causa 003 §Share-URL §Read-only viewer
  specifies this affordance.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Share-URL §NOT a session export + §Privacy posture.

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

**60Hz when visible, paused when backgrounded.**

### Why

- **The timer is the source of truth, not the ring.** The ring
  is a visualisation of the timer; pausing the ring does not pause
  the timer.
- **Backgrounded panels burn no CPU.** A Story page with 50
  variants, each with a chart, each with a ring, would consume
  significant CPU if every ring rendered at 60Hz. Visibility-
  gating reduces it to "only the visible charts pay the cost."
- **`prefers-reduced-motion` clamps the ring to a step
  animation** — not a smooth fill. The user controls.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Performance + §Animation (the broader UX baseline lives in
[Causa 007-UX-IA](../../causa/spec/007-UX-IA.md)).

---

## Lock #9 — Layout: recompute on registry change, not on transition

**Locked 2026-05-13 (Mike, lifted from Causa 003).** **Chart
layout runs only on registry change (machine re-registered) or
compound-state expand/collapse. Live transitions update node
highlights without re-layout.**

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

**On registry change + on compound expand/collapse only.**

### Why

- **Stable graphs are easier to read.** A chart that re-layouts
  on every transition makes the user re-orient on every event;
  cognitive cost wins over visual flourish.
- **Performance** — re-layout is O(nodes × edges) at minimum;
  60Hz re-layout on a 50-state chart is expensive.
- **Hot-reload** triggers a registry-change event, which triggers
  a layout pass — so editing a machine and saving causes the
  chart to redraw correctly without manual intervention.

Source: lifted from Causa
[`003-Machine-Inspector.md`](../../causa/spec/003-Machine-Inspector.md)
§Performance.

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
| `:after` countdown rings — render rules | §Performance | [`API.md`](./API.md) §Countdown rings + Lock #8 above |
| Layout-recompute rules | §Performance | Lock #9 above |
| `:invoke-all` viz — row of mini-machines | §`:invoke-all` viz | future 001-Rendering.md (post-scaffold; not in this PR) |
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
  rendering case (compound, parallel, `:after`, `:invoke-all`,
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
- [`ai/findings/xstate-advanced-features-2026-05-13.md`](../../../ai/findings/) — visualizer-as-product deprecation rationale.
- [`ai/findings/sweep-tools-vs-sota-2026-05-13.md`](../../../ai/findings/) §machines-viz — peer-comparison + gap analysis.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) §Future — `(machine->mermaid)` exporter forward-pointer.
