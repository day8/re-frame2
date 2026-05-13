# Principles

The load-bearing principles. When a design call has two reasonable
options, these are the tie-breakers. Implementers and contributors
should be able to read this doc and reach the same answers
Machines-Viz already reached.

These are downstream of the framework's
[Principles](../../../spec/Principles.md) and Causa's
[Principles](../../causa/spec/Principles.md); they are
*Machines-Viz-specific*. Where they overlap, this doc cites instead
of repeating.

## Observation only — no new runtime surfaces

Machines-Viz is a **downstream consumer** of re-frame2's
instrumentation surface. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

If a chart needs data the framework doesn't emit, the answer is to
**add to the framework's instrumentation** (via a spec amendment in
`spec/009-Instrumentation.md` or `spec/005-StateMachines.md`) — not
to bolt a parallel surface onto Machines-Viz.

This is the same posture Causa takes
([Causa Principles §Observation only](../../causa/spec/Principles.md)).
The rule applies twice in this jar: once because the framework
should not grow tooling-only surfaces, once because every host that
embeds `MachineChart` must be able to count on identical behaviour.

## Embedding-host-agnostic — one component, many hosts

`MachineChart` does not know its host. Causa wraps it in the
Machine Inspector panel; Story wraps it in a per-variant ribbon;
the read-only viewer page wraps it in plain HTML. The component's
contract is the same in every case:

- Inputs: `:machine-id`, `:frame-id`, `:on-state-click`,
  `:on-transition-click`, plus the read-only/no-op flags the
  viewer uses.
- Outputs: rendered SVG + the four callback events fired on user
  interaction.
- No assumptions: the component does **not** assume the host
  surfaces a frame picker, a transition-history ribbon, an editor
  URL handler, or any other Causa-shaped affordance. Hosts add
  those.

When two hosts disagree on a behaviour, the resolution lives in
this jar — not in either host. Hosts that need different behaviour
pass it via props or via the (small) configuration surface in
[`API.md`](./API.md).

## Bundle isolation by classpath

Per [`tools/README.md`](../../README.md), tools are kept off
consumer apps' production classpaths **structurally**. Machines-Viz
inherits the contract: nothing in `implementation/` may `:require`
from this jar. Causa requires this jar; Story requires this jar;
the framework runtime does not.

This is non-negotiable. A consumer who deploys a re-frame2 app to
production must not ship a single byte of charting code. The
mechanism is the same as the substrate-adapter split: the wrong
artefact is absent from the classpath.

## EDN-first on the wire

Share-URLs encode the chart payload as EDN, not JSON. EDN preserves
the keywords, sets, vectors, and nested maps that
`reg-machine` definitions carry; JSON would lose the type
information and force a re-coercion layer.

The encoding pipeline:

```
chart-state  →  EDN-print  →  transit-write  →  base64url  →  URL fragment
```

Transit handles the binary-compactness; base64url is URL-safe; the
fragment is invisible to servers and browsers' navigation logs (the
hash component is never sent in the HTTP request). The decoding
pipeline runs the inverse. Same EDN-first posture the MCP triplet
takes (per `ai/findings/sweep-tools-vs-sota-2026-05-13.md` §EDN-
first wire vocabulary).

A versioned envelope keys the encoding so future format evolution
is non-breaking:

```clojure
{:rf.machines-viz.share/v "1"           ;; encoding version
 :rf.machines-viz.share/chart   { ... } ;; the payload
 :rf.machines-viz.share/created <ms>}   ;; encode-time wall-clock
```

A decoder that reads a newer-than-known version refuses to render
and surfaces "this chart was shared from a newer Machines-Viz; try
upgrading."

## No session data in shares

The share-URL serialises **only** machine topology + a single
current-state snapshot.

Forbidden in a share payload:

- Trace events (no event vectors; no dispatch ids; no causal walk).
- Epoch history (no app-db slices outside `[:rf/machines <id>]`).
- User-input data (no form values, no inputs the user typed).
- Conversation buffers (no AI-co-pilot history).
- Cross-machine state unless the sharer's chart explicitly includes
  it via `:invoke-all`.
- Any data from any panel other than the machine chart.

The principle exists because the framework's runtime carries
sensitive data by default (per
[Spec 009 §Privacy](../../../spec/009-Instrumentation.md)). A share-
URL that exfiltrates trace events is a privacy hole; we close it
structurally. Per Causa Lock #4 — Session export, lifted here.

The encoder is the gatekeeper: it accepts a `chart-state` value and
silently drops anything outside the allowlist before serialising.
The allowlist lives in [`API.md`](./API.md) §Share-URL payload
schema; CI tests enforce that any new top-level key requires an
explicit `:rf.machines-viz.share/allow?` opt-in.

## Reproducible from the registry alone

A shared chart's topology must be reproducible byte-for-byte from
the same `reg-machine` call. Two consumers with the same re-frame2
build and the same machine definition should encode to the same
EDN regardless of when they encoded.

This means:

- **Map keys sort deterministically** (alphabetical by name, then
  namespace) before EDN serialisation.
- **Set members sort the same way.**
- **Source coords** are encoded only when present in the
  definition's metadata — never synthesised at encode time.
- **No timestamps** other than the explicit `:rf.machines-viz.share/created`
  envelope key; the payload itself is timeless.

Reproducibility lets two consumers diff their charts when they
disagree about a machine's behaviour. The diff is meaningful only
if the encoding is canonical.

The only non-reproducible bit is the **current-state snapshot** —
which is purely a visual-highlight hint. The viewer has a "show
idle" toggle that clears it.

## Read-only is the default

The default `MachineChart` is a viewer. Interactions are limited
to:

- Click a state node → fire `:on-state-click` (host decides what to
  do: Causa jumps to source; the viewer page no-ops).
- Click an edge → fire `:on-transition-click` (host decides).
- Hover an edge / state → reveal a tooltip (this is the
  component's own affordance; no host wiring).

Machines-Viz **never** dispatches into the framework runtime. It
**never** calls `restore-epoch`. It **never** writes to `app-db`.
Same posture Causa takes
([Causa Principles §Read-only by default](../../causa/spec/Principles.md))
— the difference is that Causa's posture has a "mutate by
confirmation" escape (re-dispatch via right-click etc.); Machines-
Viz has no escape. The component is observation, full stop.

If a host needs to drive the runtime from a chart click (Causa's
"jump to source", say), the host wires that through its own
machinery — Machines-Viz fires the callback and stops there.

## Charts pulse only the active state

The only continuous animation in a chart is the active state's
1.2s heartbeat pulse (lifted from Causa 007-UX-IA §Animation —
only the active machine's node pulses). Everything else is
event-driven:

- Transition: one ~250ms edge-glow on the matching event.
- Microstep: one 150ms intermediate node flash.
- `:after` countdown: a fill ring that updates at 60Hz **only**
  when the chart is visible; backgrounded charts pause the fill.
- `:invoke-all` join completes: one ~400ms row-collapse animation.

No looping animations except the heartbeat. No "look at me I'm
running" continuous strobes. Every animation respects
`prefers-reduced-motion`; reduced motion clamps durations to 0
except a 1-frame opacity tween where layout needs to settle.

This is the framework's animation-communicates-not-decorates
principle, applied to charts.

## Colour is never alone

Every coloured marker pairs with a shape, icon, or text:

- Active state: green fill + a filled-glyph badge + the state-name
  label.
- `:after` countdown ring: amber arc + the countdown-clock icon +
  the remaining-ms text.
- `:final?` state: doubled border + a "✓" glyph.
- `:invoke-all` failed child: red fill + an "!" badge + "failed"
  label.
- Stale microstep node: grey + dashed border + "(microstep)" text.

The colour-blind path is reachable without hue. Same posture
Causa takes
([Causa 007-UX-IA §Colour is never alone](../../causa/spec/007-UX-IA.md)).

## Restraint over completeness

The temptation to grow a chart component into a full editor is
real. The component is striking; the editor is striking-er;
visualisation-as-product is a peer-set norm.

We resist:

- No drag-to-reposition (the layout is computed; the user does not
  edit it).
- No state-rename inside the chart (the user edits source; the
  chart re-layouts).
- No transition-add gesture (the user edits source).
- No saved-chart registry (the share-URL is the only persistence
  affordance; bookmark it, drop it in a doc).
- No multi-user / multiplayer canvas.

If a chart feature is in the v1 ship list (per
[`000-Vision.md`](./000-Vision.md) §Scope and roadmap), it lands.
Otherwise: defer to v1.1, or let Stately Studio own the lane. The
machine inspector's job is to **show the cascade**, not to
**author the machine**.

## Production elision is non-negotiable

The chart depends on the framework's trace bus + the
`[:rf/machines <id>]` snapshot, both of which are gated on
`re-frame.interop/debug-enabled?` in production (per
[Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code)).
Machines-Viz contributes its own sentinels to the elision verifier
(`npm run test:elision`); CI blocks any leak.

Production builds (`:advanced` + `goog.DEBUG=false`) elide every
surface this jar consumes. The chart is invisible in production —
not "renders with a warning," not "degrades gracefully," **absent
from the classpath**.

## Backed by the framework's principles

When in doubt, defer to the framework's
[Principles](../../../spec/Principles.md):

- **Regularity over cleverness** — one obvious way to render a
  state, an edge, a microstep.
- **Named things over anonymous things** — every state has a
  stable id; every transition has a stable selector; every callback
  prop has a stable name.
- **Public query surfaces** — Machines-Viz reads only what the
  framework's public registrar / trace bus / `[:rf/machines]`
  surface exposes.
- **Deterministic execution** — Machines-Viz's rendering is a
  function of `(reg-machine def, snapshot, trace-history)`; no
  hidden side-effects.

Machines-Viz is a downstream artefact of the framework's
AI-first discipline. The principles above are what *Machines-Viz
adds* over the framework's baseline; everything below is
inherited.
