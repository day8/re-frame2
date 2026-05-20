# Story — Assertions and Play

> The seven canonical `:rf.assert/*` events; their record-don't-throw
> semantics; play-sequence execution; the assertion-side interaction
> with `force-fx-stub` (the decorator itself lives in
> [`005-SOTA-Features.md`](005-SOTA-Features.md) §`force-fx-stub`).
> The contract Stage 5 implements.

## Canonical assertion vocabulary

Per
[spec/007 §Assertion vocabulary](../../../spec/007-Stories.md) the
canonical seven register at Story load. Each is a regular
`reg-event-fx` against the variant's frame. All **record** results
into `:assertions` (see below) rather than throwing.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches` | `[path malli-schema]` | `(m/validate schema (get-in @app-db path))` |
| `:rf.assert/sub-equals` | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?` | `[event-vec]` | Was this event dispatched against this frame? |
| `:rf.assert/state-is` | `[machine-id state]` | Active state of `reg-machine` machine-id is state. Pairs with the per-variant trace-buffer's `:rf.machine/guard-evaluated` + `:rf.machine/action-ran` ops (rf2-ec52e, per [spec/005-StateMachines.md](../../../spec/005-StateMachines.md) + [spec/009-Instrumentation.md §`:op-type` vocabulary](../../../spec/009-Instrumentation.md)) — failures of this assertion can be diagnosed against the captured guard/action trace via Causa's RHS view. |
| `:rf.assert/no-warnings` | `[]` | No `:rf.warn/*` events seen during play. |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | Did the variant's drain emit fx-id? See §`:rf.assert/effect-emitted` payload shape. |

Each handler returns a map of the form:

```clojure
{:assertion :rf.assert/path-equals
 :payload   [[:auth :status] :authenticated]
 :passed?   true
 :actual    :authenticated
 :expected  :authenticated
 :source    {:file "..." :line ...}}             ; from source-coord stamping
```

The play-runner collects these into `:assertions`. The list survives
the `run-variant` return.

## Record-don't-throw semantics

`:rf.assert/*` events **record** failures into the variant's
`:assertions` list and continue the play sequence. They do not throw.

The "play continues, all failures collected" model is more debuggable
and aligns with re-frame's run-to-completion drain semantics
([Spec 002](../../../spec/002-Frames.md)). It mirrors devcards' behaviour;
diverges from Storybook (which throws). Storybook's choice is
constrained by JavaScript's async-throw mess; we have no such
constraint. See [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
§record-not-throw.

Each `:rf.assert/*` handler returns a map describing the assertion
result; the play-runner concatenates these into the variant's
`:assertions` list. `run-variant`'s test-runner adapter (Stage 5)
post-processes the `:assertions` list and translates failures into
the host test framework's failure signal — `cljs.test`'s `is`,
kaocha's reporter, etc.

## `:rf.assert/effect-emitted` payload shape

The only assertion whose payload carries an **optional second slot**.
Both shapes are legal:

- **`[fx-id]`** — the assertion **passes** iff `fx-id` was emitted at
  least once during play (the variant's frame accumulates emitted
  fx-ids into its per-frame `:emitted-fx` slot; see
  [`002-Runtime.md`](002-Runtime.md) §`:rf.assert/effect-emitted`
  under `force-fx-stub`).
- **`[fx-id pred]`** — the assertion **passes** iff `fx-id` was
  emitted **and** `(pred fx-id)` returns a truthy value. `pred` is a
  unary fn whose single argument is the fx-id keyword that was
  matched. Exceptions thrown by `pred` count as a `false` return; the
  assertion records as failing rather than propagating.

The optional `pred` slot is deliberately a unary fn over the fx-id
keyword, not over the fx-args map. The play-runner's emitted-fx
accumulator tracks **which fx-ids fired**, not the per-call fx-args
payload — preserving arg-level granularity would require a parallel
accumulator on the trace bus and was rejected as out of scope for v1
(see [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §record-not-throw
for the same set-not-list trade-off the assertion family takes).

Authors who need an argument-level assertion compose two checks: an
`:rf.assert/effect-emitted` for the fx-id (set membership) plus an
`:rf.assert/path-equals` against the slot in `app-db` the fx writes
through.

## Play sequence execution

The runtime's phase 4 (per [`002-Runtime.md`](002-Runtime.md)
§Four-phase lifecycle) iterates the `:play` event vector. For each
event:

1. `dispatch-sync` the event into the variant's frame.
2. Drain to completion.
3. If the event id is one of the canonical seven, record the
   assertion result returned by the handler into `:assertions`.
4. If the event raises an unexpected exception, project it as
   `:rf.error/exception` and continue (per
   [`002-Runtime.md`](002-Runtime.md) §Error projection).

The play-stepper UI affordance pauses between events, surfaces the
intermediate `:assertions` list, and offers a re-dispatch hook. The
in-canvas chrome that exposes step / pause / rewind / step-back /
breakpoint controls over this hook is specified in
[`009-Test-Mode.md` §Play step-debugger](009-Test-Mode.md#play-step-debugger-rf2-ulw5m)
(rf2-ulw5m).

## `force-fx-stub` interaction

The `force-fx-stub` decorator is Story's universal effect-mocking
primitive — one decorator covers HTTP, websockets, analytics,
storage, navigation, geolocation, and anything else registered with
`reg-fx`. The marketing-tier framing, the Storybook comparison, and
the authoring contract live in
[`005-SOTA-Features.md`](005-SOTA-Features.md) §`force-fx-stub`.

This document covers the assertion-side interaction. A stubbed fx
still counts as **emitted** for the purposes of
`:rf.assert/effect-emitted` (the fx-id flows through the dispatch
pipeline; the stub intercepts the *handler*, not the emission). A
variant that stubs `:http` and asserts
`:rf.assert/effect-emitted :http` therefore passes both the stub and
the assertion in a single play sequence. The variant's
`:emitted-fx` slot records the emission per
[`002-Runtime.md`](002-Runtime.md) §`:rf.assert/effect-emitted`
under `force-fx-stub`.

## Privacy

Assertion records are an observation surface and obey the framework's
path-level data-classification contract
([spec/015-Data-Classification.md](../../../spec/015-Data-Classification.md)).
The rules:

1. **`:actual` / `:expected` / `:payload` pass through
   `re-frame.elision/elide-wire-value` before landing in
   `:assertions`.** If a variant declared per-frame marks via
   `(re-frame.core/add-marks <variant-id> {path mark, ...})` or
   `(re-frame.core/set-marks <variant-id> {path mark, ...})` (per
   [spec/015 §2. App-db marks (per frame)](../../../spec/015-Data-Classification.md#2-app-db-marks-per-frame--add-marks--set-marks)),
   then a `:rf.assert/path-equals [:auth :token] :rf/redacted` lookup
   against a path-marked-sensitive slot records `:actual :rf/redacted`,
   NOT the raw value. Same posture for `:rf.assert/sub-equals` against
   a sub whose output is sensitive (per
   [spec/015 §2. App-db → subs](../../../spec/015-Data-Classification.md#2-app-db--subs)).
2. **The sentinel literal is a legal `:expected` value.** Authors
   write the `:rf/redacted` sentinel directly into the assertion to
   pin the redaction contract: a passing
   `:rf.assert/path-equals [:auth :token] :rf/redacted` proves the
   observation surface saw a sentinel, NOT the secret.
3. **Cross-frame isolation holds.** A variant's `add-marks` /
   `set-marks` declaration scopes to its own frame; an adjacent
   variant in a side-by-side pane sees only its own declared marks
   (per [`002-Runtime.md`](002-Runtime.md) §Per-variant frame
   allocation + spec/015 §App-db marks scoping).
4. **Display contract — same posture as Causa.** The `:test` mode
   pane and the `[data-test="story-test-row-detail"]` disclosure
   render `:rf/redacted` per spec/015 §Display contract. A disclosure
   that revealed the underlying value would be non-conformant — see
   spec/015 §The display contract.

See also:

- [`000-Vision.md` §Privacy posture](000-Vision.md#privacy-posture-path-level-data-classification--spec-015)
  — the marquee posture statement covering all of Story's surfaces.
- [`002-Runtime.md` §Error projection §Privacy](002-Runtime.md#privacy)
  — the symmetric posture for `:rf.error/exception` assertion records.
- The `Assertion-with-redaction` row in
  [`015-Test-Coverage.md`](015-Test-Coverage.md) §Assertion vocabulary
  scenarios — substrate state and the deferred bd:rf2-shy6n integration.

## Test-runner integration

Stage 5 ships a `cljs.test` adapter:

```clojure
(deftest my-component-test
  (run-variant-as-test :story.auth.login-form/happy-path))
```

The adapter:

1. Runs the variant via `run-variant` (see
   [`002-Runtime.md`](002-Runtime.md) §Programmatic API).
2. Iterates the returned `:assertions` list.
3. Calls `cljs.test/is` for each — passing assertion → `is` pass;
   failing assertion → `is` fail with the assertion's `:expected` /
   `:actual` shape.
4. The `:source` slot on each assertion drives `is`-style
   file/line reporting.

A generic adapter shape covers kaocha and other test frameworks via
their reporter protocols.

## Cross-references

- [`001-Authoring.md`](001-Authoring.md) — how `:play` and
  `:rf.assert/*` events appear in variant bodies.
- [`002-Runtime.md`](002-Runtime.md) — the lifecycle phase 4 that
  executes the play sequence.
- [`003-Render-Shell.md`](003-Render-Shell.md) — the play-stepper UI
  affordance.
- [`006-MCP-Surface.md`](006-MCP-Surface.md) — `read-failures` and
  `list-assertions` tools expose the assertion list to agents.
