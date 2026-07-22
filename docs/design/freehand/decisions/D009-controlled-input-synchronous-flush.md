# D009 — What exactly is the controlled-input synchronous flush law?

Status: **Open**

Horizon: **Immediate**

## Decision

Define which event sites enter the synchronous controlled-input “door,” what work
is flushed before the native listener returns, and how interpreted and compiled
modes prove the same behavior.

This must be decided before the reactor and emitters are absorbed because it joins
event classification, frame scheduling, React coordination, and browser
correctness.

## Why this is a real problem

In a controlled input, the DOM value is only provisional until application state
round-trips back through render:

```clojure
(v/defview title-field [{:keys [value]}]
  [:input {:value value
           :on-input [:document/title-edited ::v/value]}])
```

If dispatch is merely placed in the ordinary microtask batch, React may reconcile
the old `value` before the new state is committed. The visible symptoms are lost
characters, a jumping caret, broken selection, or failed IME composition. Making
every event synchronous would fix that class of bug by imposing its cost and
reentrancy on the entire system.

The useful design is a narrow, mechanically recognizable exception.

The documents already differ at one edge: the Codex spine lists
`:on-before-input` as a door event, while Fable's worked door is limited to
`:on-input` and `:on-change`. The ruling here must either amend that spine row or
provide the missing `beforeinput` payload/composition law; it cannot leave the two
lists as accidental implementation choices.

## Settled constraints

- Freehand controls are value-in and intent-out; substrate-local mirrored state is
  not the correctness mechanism.
- Presence of normalized `:value` or `:checked`, including `nil`, is what makes a
  supported native node controlled.
- A site produces one event vector or `nil`; there is no miniature batch of events.
- The event is materialized from the live callback payload before dispatch.
- Foreign components are opaque. Their qualified adapter or React wrapper owns any
  synchronous protocol they require.
- Both execution modes call one scheduling implementation and must pass one real
  browser matrix.
- Ordinary events retain host-checkpoint batching.

## Options

### A. Never flush synchronously

Consequences:

- Scheduling is uniform and simple.
- Controlled correctness depends on React timing and fails under plausible
  reconciliation schedules.
- Authors are pushed toward local mirrors or uncontrolled inputs, contradicting the
  one-state-system posture.

### B. Use one narrow door and synchronously flush the observing frame

An eligible native event dispatches and drains re-frame immediately, then the host
flushes dirty ViewCells observing that frame before returning to React's discrete
event reconciliation.

Consequences:

- The state round-trip is complete within the user action.
- It reuses the donor ViewCell scheduler and keeps one semantic rule across modes.
- Unrelated dirty cells on the same frame may ride the flush, coupling keystroke
  latency to background work.
- The coupling is visible, measurable, and can be reduced with ordinary boundary
  and subscription granularity.

### C. Flush only the controlled field's owning occurrence

Consequences:

- It promises lower latency during background activity.
- Other occurrences may commit an older snapshot of the same frame, and React or
  the external-store bridge may flush more pending work than requested anyway.
- It requires a new targeted scheduling proof rather than absorbing the known
  frame-scoped mechanism.
- Correctness under shared derived subscriptions, multiple roots, and concurrent
  rendering is substantially harder to establish.

### D. Patch the DOM or keep a local mirror, then reconcile later

Consequences:

- Immediate typing can appear responsive without a synchronous frame flush.
- React and Freehand temporarily disagree about the controlled value.
- Selection, reset, validation, frame retarget, SSR/hydration, and debugging gain a
  second source of truth.
- This replaces a scheduling problem with a state-consistency protocol.

## Recommendation

Choose **B: a narrow synchronous door with a frame-scoped flush** for the first
implementation. It is the smallest correctness rule backed by the donor runtime.
Do not promise targeted flush isolation until a prototype proves it under React's
actual scheduling behavior.

A site is door-eligible only when all of these facts hold:

1. It is a known native node, not a foreign component.
2. Its final normalized props contain `:value` or `:checked`.
3. The firing attribute is `:on-input` or `:on-change`.
4. Its selected handler outcome is synchronously known to be one event vector or
   `nil`: a vector, an options map containing a vector, or synchronous `v/event`.
5. Any forwarded props preserve the owned value and handler contract through
   `v/spread-safe`.

`nil` means no dispatch and requires no door flush. `v/handler`, promises, arbitrary
bare callbacks, and foreign callback protocols are not eligible.
An options map remains eligible only while its listener options do not move the
site onto a different native attachment lane; `:capture` and `:passive` are
excluded until that lane passes the same browser proof.

Do **not** put `:on-before-input` through the door initially. `beforeinput` fires
before the DOM mutation, so `target.value` is not generally the candidate value and
IME behavior needs its own evidence. It may remain a normal event site; admitting it
to the door requires a browser-backed projection and composition contract, not a
name added to a list.

The semantic predicate is based on final normalized props. The compiler may
precompute it when the grammar proves the facts; otherwise it must emit the common
runtime predicate or reject compilation. Promotion must never silently change an
input from synchronous to batched. `v/spread-safe` exists to retain proof through a
reusable wrapper; an opaque foreign spread cannot claim the guarantee.

The absorbed emitter must carry a statically proved door classification through a
dynamic vector/options handler site; emitting an unconditionally asynchronous
dynamic handler would violate promotion parity. Development evidence should report
both forms of classification flapping—controlled prop present/absent and handler
class vector/options/closure—and compilation must either pin the common class or
reject the site.

The synchronous sequence is:

```text
native callback
  → select and materialize one intent
  → dispatch against the exact committed frame
  → drain re-frame synchronously
  → flush dirty ViewCells observing that frame through the host
  → return to React/native event processing
```

The public guarantee is the observable round-trip, not a promise about a particular
React API. React may flush additional pending work. That honest coupling belongs in
the performance trace and acceptance harness.

The contention harness must record the input boundary's commit and the frame's
settlement/presentation channel. A narrower local commit can look faster while the
same dirty work still completes before paint; only the user-visible channel can
justify a future targeted-flush design.

## Consequences to verify

- Real-browser tests cover rapid typing, caret movement, range selection, paste,
  deletion, programmatic reset, and IME composition.
- Tests run with unrelated dirty work already pending on the same frame and report
  event-to-commit p95/p99, dropped characters, and caret/composition failures.
- Controlled `nil` values and checked inputs use the same presence predicate.
- Interpreted and compiled versions of the same field produce identical dispatch,
  drain, render, and trace order.
- A flapping event classification is diagnosed; compilation either pins the common
  rule or refuses the site.
- Nested or multiple roots sharing a frame have an explicit host test before the
  deletion gate is considered green.

If the background-contention benchmark fails its budget, the next experiment is a
targeted scheduler that preserves the same public same-tick guarantee. It is an
implementation optimization, not permission to introduce local mirrors or weaken
correctness.

## Dependencies and what this unlocks

This depends on event projection/materialization (D006), callback outcome
classification (D008), frame-scoped ViewCell scheduling, and the React host bridge.
It unlocks the controlled-field and buffered-field pilots, the compiled door proof,
browser conformance, and eventual deletion of the donor artifact.

## Design sources

- [Codex design, §4 “Controlled inputs”](../codex-design.md#controlled-inputs)
  states the common normalized-node predicate and synchronous drain guarantee.
- [Codex design, §8 “Measurement obligations”](../codex-design.md#measurement-obligations)
  requires controlled editing to survive unrelated dirty work.
- [Fable design, §2.3 “The event grammar”](../fable-design.md#23-the-event-grammar)
  specifies the narrow door and proof conditions.
- [Fable design, Appendix A.2b](../fable-design.md#a2b-the-door-under-a-pending-batched-window)
  makes the frame-wide latency coupling explicit.
