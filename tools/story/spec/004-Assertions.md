# Story — Assertions and Play

> The canonical `:rf.assert/*` events — seven dispatched as
> `reg-event` handlers plus one tape-evaluated (`:rf.assert/schema-error`),
> eight canonical ids in all; their record-don't-throw semantics;
> play-sequence execution; the assertion-side interaction
> with `force-fx-stub` (the decorator itself lives in
> [`005-SOTA-Features.md`](005-SOTA-Features.md) §`force-fx-stub`).
> The contract Stage 5 implements.

## Canonical assertion vocabulary

Per
[spec/007 §Assertion vocabulary](../../../spec/007-Stories.md) the
canonical **dispatched seven** register at Story load. Each is a regular
`reg-event` against the variant's frame. All **record** results
into `:assertions` (see below) rather than throwing. A net-new eighth
canonical id — `:rf.assert/schema-error` — is *recognised* but NOT
dispatched; it is tape-evaluated in the result boundary (see
[§`:rf.assert/schema-error` — the tape-evaluated eighth](#rfassertschema-error--the-tape-evaluated-eighth) below).

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches` | `[path malli-schema]` | `(m/validate schema (get-in @app-db path))` |
| `:rf.assert/sub-equals` | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?` | `[event-vec]` | Was this event dispatched against this frame? |
| `:rf.assert/state-is` | `[machine-id state]` | Active state of `reg-machine` machine-id is state. Pairs with the per-variant trace-buffer's `:rf.machine/guard-evaluated` + `:rf.machine/action-ran` ops (rf2-ec52e, per [spec/005-StateMachines.md](../../../spec/005-StateMachines.md) + [spec/009-Instrumentation.md §`:op-type` vocabulary](../../../spec/009-Instrumentation.md)) — failures of this assertion can be diagnosed against the captured guard/action trace via Xray's RHS view. |
| `:rf.assert/no-warnings` | `[]` | No `:rf.warn/*` events seen during play. |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | Did the variant's drain emit fx-id? See §`:rf.assert/effect-emitted` payload shape. |

### `:rf.assert/schema-error` — the tape-evaluated eighth

The seven above are dispatched as `reg-event` handlers. One further
canonical id ships and rounds the canonical set out to **eight**:

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/schema-error` | `[path malli-schema]` | EXPECTED-schema-violation declaration. Recognised so plan construction accepts it, but **not** installed as a `reg-event` handler — it is **tape-evaluated in the result boundary** (`re-frame.story.result`) by multiset-consumption match against the run's `:rf.warn/schema` tape, NOT dispatched into the frame. Per [`017-Testing-Story.md`](017-Testing-Story.md) §Schema rule. |

`re-frame.story.assertions/canonical-assertion-ids` is therefore a set
of **eight** — the dispatched seven plus `:rf.assert/schema-error`.
story-mcp's `list-assertions` surfaces it as the 8th canonical
assertion. There is deliberately no `:rf.assert/no-schema-errors`: a
schema-clean run is the knob-free runner FLOOR, refined by this
expectation rather than asserted as an opt-in.

Each handler returns a map of the form:

```clojure
{:assertion    :rf.assert/path-equals
 :payload      [[:auth :status] :authenticated]
 :passed?      true
 :actual       :authenticated
 :expected     :authenticated
 :source-coord {:file "..." :line ...}}          ; from source-coord stamping
```

> **Record slot name (rf2-ee38b.3).** The per-record source slot is
> `:source-coord` (the impl's `assertion-record` builder writes this
> key; the variant *body*'s coord slot is `:source`, per spec/001). The
> test-mode reader accepts either for robustness, but `:source-coord` is
> canonical for assertion records.

The play-runner collects these into `:assertions`. The list survives
the `run-variant` return.

## The `:rf.assert/*` events are the ONE assertion vocabulary (rf2-5o6yd)

**`:rf.assert/*` is the canonical, primary assertion vocabulary.** Every
assertion Story evaluates — wherever it is written — resolves to one
`:rf.assert/*` atom and produces ONE assertion-record shape. There is no
second assertion language. An assertion atom appears in exactly two
positions (spec/017 §Assertions — one atom, two positions):

1. **Terminal / inheritable** — a variant's `:assertions` slot (own-only)
   or a registered check's `:assertions` pack (inheritable via
   `:compose`). Each entry is a bare `[:rf.assert/… …]` atom.
2. **In-script checkpoint** — an `[:assert [:rf.assert/… …]]` step inside
   a `:script` body, evaluated at that exact point in the sequence.

### `:assert-db` / `:assert-dom` are script-step sugar

The `:assert-db` / `:assert-dom` steps in the `:script` step grammar
(spec/017 §Script step grammar) are **ergonomic sugar** over the
canonical atoms — NOT a parallel vocabulary. The plan compiler folds
every such step onto the canonical `[:assert <atom>]` checkpoint
(`re-frame.story.assertions/fold-script`, pure data → data) BEFORE the
run loop executes, so the runtime only ever sees the one assertion atom:

| Script-step sugar                    | Folds to canonical atom                       |
|--------------------------------------|-----------------------------------------------|
| `[:assert-db path expected]`         | `[:rf.assert/path-equals path expected]`      |
| `[:assert-db path :pred fn-or-sym]`  | `[:rf.assert/path-matches path [:fn …]]`      |
| `[:assert-dom sel :visible]`         | `[:rf.assert/dom-visible sel]`                |
| `[:assert-dom sel :hidden]`          | `[:rf.assert/dom-hidden sel]`                 |
| `[:assert-dom sel :text txt]`        | `[:rf.assert/dom-text sel txt]`               |

**Author guidance.** Reach for `:assert-db` / `:assert-dom` for the
common app-db-equality and DOM-presence checks (terser inline in a
`:script`). Drop to the explicit `[:assert [:rf.assert/…]]` checkpoint
(or the bare atom in `:assertions`) when you need an assertion the sugar
doesn't cover — `:sub-equals`, `:state-is`, `:no-warnings`,
`:effect-emitted`, `:dispatched?`. Both positions, and both spellings,
produce the same `:assertions` record.

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
§Four-phase lifecycle) drives the variant's `:play-script` (or each
entry in `:plays`) through the rich-DSL runner. Author event sequences
by wrapping each entry in `[:dispatch-sync <event-vec>]` — the legacy
`:play` event-vector slot was removed (rf2-0wrud); see
[`001-Authoring.md`](001-Authoring.md) §`:play-script`. For each step:

1. `:dispatch` / `:dispatch-sync` steps fire their event vector into
   the variant's frame.
2. Drain to completion between steps.
3. **`:rf.assert/*` events ride the `:dispatch-sync` rail.** Per
   rf2-yn825 the play-runner bridges a `[:dispatch-sync [:rf.assert/* …]]`
   step into the step result: the registered assertion handler records
   its result map into `:rf.story/assertions` on the variant frame, and
   the runner mirrors the recorded outcome (pass/fail) onto the step so
   the step-debugger + test pane read it without a second lookup.
4. If a step raises an unexpected exception, the runtime projects it as
   `:rf.error/exception` and continues (per
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

## Canvas assertion-strip (structured rows)

The variant frame's accumulated `:rf.story/assertions` vector renders
inline below the canvas render — and below each workspace cell — as a
**structured assertion-strip** (rf2-29lw1; C2 detail-panel reveal
polish #1826). Both call sites
share one leaf component (`re-frame.story.ui.assertion-strip`); the
`:test` mode pane keeps its richer per-row table, the inline strip is
the compact version of the same shape. The strip lifts five patterns
from Storybook's addon-tests interactions panel:

1. **Structured row** — status glyph (`✓` / `✗` / `⊘`) + assertion
   label + a one-line summary, not a raw `pr-str` of the record map.
2. **Auto-collapse pass · auto-expand fail** — failed rows seed open so
   the user lands on disclosed failures; passed / skipped rows stay
   collapsed. A click toggles any row.
3. **Token-coloured left border** — green / red / grey by status, so
   the eye sweeps the colour band before reading any text.
4. **Truncate long values** — the inline summary clamps to one line; a
   long `:expected` / `:actual` inside the expanded panel clamps too
   with a click-to-reveal-full chord (no modal — same row) per the C2
   detail-panel value-reveal polish (#1826).
5. **Group by dispatching event** — assertions cluster under the
   `:event` slot they were dispatched from; assertions outside a play
   step (phase-0 setup, decorator-throws) cluster under a leading
   `setup` group.

The pure projection helpers (`truncate`, `summary-line`,
`group-by-event`, `value-display`) are data → data, so the strip's
shape is unit-testable. The rendered rows carry stable `data-test`
hooks (`story-canvas-assertion-strip` / `-row` / `-glyph` / `-label` /
`-summary` / `-detail` / `-detail-reveal`) for the agent reader and the
Story/Xray gate. Per rf2-5lw9w each row vector carries a React `:key`
on the element Reagent hands to React (not on a function-call form) so
the row seq does not warn.

## Privacy

An assertion record is a value-bearing **observation surface** — it
serialises into the `:test`-mode pane, MCP `read-assertions`, and JSON-log
egress, all of which spec/015 lists as boundaries projection must guard. So
**no slot of the record may carry a raw secret for a sensitive path** — not
`:actual`, and (rf2-006y9b) not `:expected`, `:payload`, or `:reason`. The
record obeys the framework's path-level data-classification contract
([spec/015-Data-Classification.md](../../../spec/015-Data-Classification.md)).
The rules:

1. **Every value-bearing slot passes through
   `re-frame.elision/elide-wire-value` before landing in `:assertions`.**
   Durable app-db classification is carried by the **commit-plane effects** per
   [spec/015 §Durable app-db — the four commit-plane effects](../../../spec/015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects):
   a variant declares its sensitive / large paths from a handler body, as the
   `:sensitive` / `:large` commit-plane effects returned alongside the `:db`
   write (not on the variant's `reg-frame` config — that route was retired). A
   `:rf.assert/path-equals [:auth :token] ...` lookup against a slot classified
   sensitive records `:actual :rf/redacted`, NOT the raw value. The
   `evaluate-path-equals` / `evaluate-sub-equals` evaluators project
   `:actual` keyed on the asserted path; `evaluate-sub-equals` keys on the
   sub-vec's args path (`(rest sub-vec)`), so a parameterised sub
   `[:sub/id :user :ssn]` reading a sensitive `[:user :ssn]` redacts. A bare
   sub-id whose args carry no app-db path relies on sub-engine marker
   propagation (spec/015 §Derived sensitivity) — tracked separately.
2. **`:expected`, `:payload`, and `:reason` are projected too**
   (rf2-006y9b). An author who pins the *raw* expected value against a
   sensitive path does not leak it: `:expected` is projected against the
   same path before it lands on the record (→ `:rf/redacted`), `:payload`
   is rebuilt from the redacted expected (`[path :rf/redacted]`), and
   `:reason` prints the redacted form. The pass/fail outcome is unchanged —
   the equality is checked against the raw read, then both expected and
   actual are projected for the record.
3. **The `:rf/redacted` sentinel is a first-class legal `:expected`
   value.** An author writes the documented sentinel directly to pin the
   contract: a `:rf.assert/path-equals [:auth :token] :rf/redacted` against a
   sensitive path **passes** (the observation surface saw the sentinel) —
   the comparison treats a sentinel expected as satisfied when the projected
   `:actual` is `:rf/redacted`. Both the doc-following author (writes the
   sentinel) and the value-pinning author (writes the raw value) get a green
   assertion with a leak-free record.
4. **Cross-frame isolation holds.** A variant's frame-owned classification
   scopes to its own frame; an adjacent variant in a side-by-side pane sees
   only its own declared classification (per
   [`002-Runtime.md`](002-Runtime.md) §Per-variant frame allocation +
   spec/015 §Frame-owned durable classification, which is cross-frame-
   distinct by construction).
5. **Display contract — same posture as Xray.** The `:test` mode pane and
   the `[data-test="story-test-row-detail"]` disclosure render `:rf/redacted`
   per spec/015 §The display contract. A disclosure that revealed the
   underlying value would be non-conformant — `:rf/redacted` MUST NOT be
   expandable.

See also:

- [`000-Vision.md` §Privacy posture](000-Vision.md#privacy-posture-path-level-data-classification--spec-015)
  — the marquee posture statement covering all of Story's surfaces.
- [`002-Runtime.md` §Error projection §Privacy](002-Runtime.md#privacy)
  — the symmetric posture for `:rf.error/exception` assertion records.
- The `Assertion-with-redaction` row in
  [`015-Test-Coverage.md`](015-Test-Coverage.md) §Assertion vocabulary
  scenarios — exercised live by
  `assertion_redaction_cljs_test.cljs` (rf2-ee38b.3 wired the
  evaluator → `elide-wire-value` projection; bd:rf2-shy6n).

## Test-runner integration

Stage 5 ships the `story/assertions-passing?` predicate as the canonical
`cljs.test` / `clojure.test` surface:

```clojure
(deftest my-component-test
  (let [result @(story/run-variant :story.auth.login-form/happy-path {})]
    (is (story/assertions-passing? result))))
```

`assertions-passing?` accepts either the `run-variant` result map or its
`:assertions` vector and returns true iff every record has `:passed?
true` (an empty vector is vacuously passing — the
[spec/007 §Story-as-test duality](../../../implementation/...) contract:
a variant with no `:play-script` still "passes"). The result map's
`:assertions` slot now carries EVERY assertion outcome — both the
`:rf.assert/*` dispatch-step records AND the rich-DSL `:assert-db` /
`:assert-dom` step records (rf2-ee38b.3 closed the false-green gap where
rich-DSL assert failures landed only in the runner's `run-state` atom).

### Per-assertion `cljs.test/is` granularity — shipped via `story/is` (rf2-2yrb91)

The higher-fidelity per-assertion adapter this section once deferred is
**shipped** as the `story/is` verb of the spec/017 execution trilogy
([`017-Testing-Story.md` §Public execution API — the three verbs](017-Testing-Story.md#public-execution-api--the-three-verbs)).
`(story/is target opts)` runs the target, then reports each assertion
record to `clojure.test` / `cljs.test` at **per-assertion granularity** —
one `do-report` (`:pass` / `:fail` / `:error`) per record — so a script
with N assertions lands N separate results in the active test run's tally,
NOT one lumped outcome per variant. The pure projection
`re-frame.story.result/result->reports` (re-exported as
`story/result->reports`) turns the unified run-result into that ordered
vector of report maps; the impure `do-report` emission is the `story/is`
side. Details:

- **One report per record.** `result->reports` maps every unified
  assertion record through `assertion->report`, then appends ONE run-level
  report only when the run verdict is not carried by any single assertion
  (a tape-floor `:fail`, a run-level `:cannot-run`, or a vacuous-green
  `:pass` so a zero-assertion run still emits one positive signal). A
  `:cannot-run` assertion reports `:fail` (the runner proved nothing —
  never a silent pass).
- **`:source-coord`-driven reporting.** Each report's `:message` names the
  failing `:assertion` id + payload. The per-record source slot
  (`:source-coord` on the raw record, normalized to `:source` on the
  unified record — §Record slot name) rides the unified `:assertions`
  vector `result->reports` projects, so IDE / reporter surfaces resolve
  file/line off the record without a second lookup.
- **Async plumbing done.** `run-variant` / `story/run` return a Promise
  (CLJS) / `CompletableFuture` (JVM); `story/is` BLOCKS on the JVM (the
  canonical headless gate, firing reports synchronously and returning the
  unified result) and hands the promise back on CLJS — chain `then`, or use
  the `cljs.test` `(async done …)` form and call `story/report-result!`
  when it resolves. `report-result!` is the pure-report seam both runtimes
  share.

Coverage: the JVM bridge is exercised by
`re-frame.story.story-is-test` (`story-is-reports-per-assertion-pass`
proves two `:assert-db` steps in one `:script` fire **two** separate
reports); the pure CLJS projection by
`re-frame.story.result-test/result->reports-one-per-assertion`.

`story/assertions-passing?` remains the one-line boolean predicate for the
`(is (story/assertions-passing? result))` pattern above; `story/is` is the
richer per-assertion surface. Both consume the same unified `:assertions`
vector.

## Cross-references

- [`001-Authoring.md`](001-Authoring.md) — how `:play-script` and
  `:rf.assert/*` events appear in variant bodies.
- [`002-Runtime.md`](002-Runtime.md) — the lifecycle phase 4 that
  executes the play sequence.
- [`003-Render-Shell.md`](003-Render-Shell.md) — the play-stepper UI
  affordance.
- [`006-MCP-Surface.md`](006-MCP-Surface.md) — `read-failures` and
  `list-assertions` tools expose the assertion list to agents.
