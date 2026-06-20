# Scripts

This chapter is about a variant's **`:script`** — the slot that turns a variant body into a deterministic, replayable sequence of dispatches, DOM gestures, waits, and assertions. The core is **a tagged-step grammar** that a runner walks in order against the variant's frame. Around it sit the **seven canonical `:rf.assert/*` events** (the assertion vocabulary the `[:dispatch-sync …]` rail rides), the **record-don't-throw** discipline (failures append to the run rather than aborting), the **`:cannot-run`** refusal (a step the runner can't observe is refused, never silently passed), and the **recorder** (which authors a `:script` body from canvas interaction).

The public authoring slots are **`:setup`** (preconditions) and **`:script`** (behaviour under test); you execute a variant with the three verbs `story/run` / `story/is` / `story/explain`.

!!! note "One scoped spelling note — the recorder"

    The recorder still emits the older **`:play-script`** spelling for this slot,
    which the registrar lowers to `:script` on registration. That is a clean
    pre-alpha rename, not a long-lived compatibility layer. Everything you author
    uses `:script`; you only see `:play-script` in the recorder's generated output
    ([tutorial chapter 5](../05-recorder-and-cannot-run.md)). The normative contract
    — the four-bucket plan, the three verbs, `:cannot-run`, composition, the schema
    floor, and the epoch-tape evidence projection — lives in
    [`017-Testing-Story.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/017-Testing-Story.md).

## The grammar — tagged step forms

Every step is a tagged vector. The runner iterates the script in order, settles each step into the variant's frame, and records the result. Not every step is legal in both `:setup` and `:script`.

| Step | Semantics | Minimum runner |
|---|---|---|
| `[:dispatch event-vec]` | dispatch the event and advance when the runner reaches `settled-boundary` (in headless, the run-to-fixed-point drain). | `:headless` |
| `[:dispatch-sync event-vec]` | low-level synchronous dispatch; the canonical seven `:rf.assert/*` ride this rail. | `:headless` |
| `[:wait-until predicate]` | settle on a condition (`[:db path expected]` / `[:db path :pred fn]` / `[:queue-empty]`); deterministic; times out readably. | depends on predicate |
| `[:wait ms]` | bounded wall-clock sleep — the explicit determinism opt-out; the determinism gate refuses a script containing one with `:cannot-run`. | runner-dependent |
| `[:assert assertion-vec]` | checkpoint assertion at this point in the script. **Illegal in `:setup`.** | depends on assertion |
| `[:click selector]` | DOM click. | `:dom` |
| `[:type selector text]` | DOM text input. | `:dom` |
| `[:focus selector]` | DOM focus. | `:dom` |
| `[:assert-dom selector :visible \| :hidden \| :text txt]` | DOM-shape assertion. | `:dom` |

A bare event vector (`[:my/event …]`) is accepted only as a transitional migration lift to `[:dispatch …]`; the P1 public grammar is uniformly tagged, so an app event genuinely named `:dispatch` or `:click` is never silently un-dispatchable.

## The seven canonical `:rf.assert/*` events

The assertion vocabulary auto-registers at Story load (from the first `reg-*` call). They record results rather than throwing.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)`. The workhorse. |
| `:rf.assert/path-matches` | `[path schema]` | the value at `path` validates against a Malli schema. |
| `:rf.assert/sub-equals` | `[query-vec expected]` | `(= @(subscribe query-vec) expected)`. **Honesty rule:** NOT satisfied by a `:sub-overrides` pin — it evaluates through `compute-sub`, which an override never touches. |
| `:rf.assert/dispatched?` | `[event-vec]` | was this event dispatched into the frame during the script? (Script-phase only — not setup.) |
| `:rf.assert/state-is` | `[machine-id state]` | the active state of a `reg-machine` machine. |
| `:rf.assert/no-warnings` | `[]` | no `:rf.warn/*` events fired during the run. |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | the fx was emitted; the optional `pred` is a unary fn over the matched fx-id keyword. |

Plus the tape-evaluated `:rf.assert/schema-error` (minted by the result boundary against the epoch tape, not dispatched), which requires the `:schema` capability and fails the run on a tape schema violation.

## Terminal vs checkpoint

The same assertion atom lives in two positions:

- In **`:assertions`** it is **terminal** — auto-runs after the script settles, against the final state.
- As an `[:assert …]` step in **`:script`** it is a **checkpoint** — must hold at that exact point.

`[:assert …]` is rejected in `:setup` at plan-compile time with `:rf.error/story-assert-in-setup`: setup establishes preconditions; it does not judge.

## Record-don't-throw

Every assertion records its result and the script continues. A failing assertion does not abort the run — the runner walks every remaining step, accumulates every record, and the result asks "did every entry pass?" at the end. A script with eight assertions where three fail still runs all eight. This diverges from Storybook's throw-on-first-failure, which is partly forced on it by JavaScript's async-throw model; re-frame2's run-to-completion drain gives Story room to do better.

```clojure
(story/reg-variant :story.counter/clicked-three-times
  {:doc    "Counter after three increments from zero."
   :setup  [[:counter/initialise 0]]
   :script [[:dispatch-sync [:counter/inc]]
            [:dispatch-sync [:counter/inc]]
            [:dispatch-sync [:counter/inc]]
            [:dispatch-sync [:rf.assert/dispatched? [:counter/inc]]]]
   :tags   #{:dev :docs :test}})
```

Note the increments live in `:script`, not `:setup`: the `:rf.assert/dispatched?` accumulator only observes dispatches during the script phase. The general rule for hand-authored variants — **assertions about dispatches only see the script, not the setup.**

## `:cannot-run` — the third result state

A step or assertion the chosen runner cannot observe is refused, fail-closed, with the distinct **third** status (not pass, not fail):

```clojure
{:status           :cannot-run
 :required-runner  #{:dom}
 :available-runner #{:app-db :effects ...}
 :missing          #{:dom}
 :reason           :runner-lacks-capability
 :runner           :headless
 :unit             [:click "[data-test=submit]"]}
```

The cost-ordered runners (`:headless` → `:hiccup` → `:cljs-reactive` → `:dom` → `:browser`) each advertise a set of capability tokens; each step/assertion declares the tokens it needs; the plan's `:required-runner` is the union; a runner is valid iff its tokens are a superset, and the cheapest valid runner wins. The aggregation rule: a variant whose only unmet assertions are `:cannot-run` is itself `:cannot-run` — never a silent pass. (Full runner model: [tutorial chapter 5](../05-recorder-and-cannot-run.md).)

## Privacy posture

`:rf.assert/*` records build their `:actual` / `:expected` / `:payload` / `:reason` slots through the wire-elision walker before landing in the result — no slot carries a raw secret for a sensitive path. Durable app-db classification is **frame-owned** (EP-0015): a variant declares its sensitive paths via the `:sensitive` slot on its body, and an assertion against such a path records `:rf/redacted`, not the raw value. The `:rf/redacted` sentinel is a first-class legal `:expected` value — author it directly to pin the redaction contract:

```clojure
(story/reg-variant :story.auth/login
  {:setup     [[:auth/login {:user "alice" :password "..."}]]
   :sensitive {:app-db [[:auth :token]]}
   :script    [[:dispatch-sync [:rf.assert/path-equals [:auth :token] :rf/redacted]]]})
```

A passing assertion proves the observation surface saw the sentinel, not the secret. (There is no `add-marks` / `set-marks` mutation surface — classification is declared at frame creation; EP-0015 superseded the public post-creation model.)

## The recorder

Story's canvas recorder captures dispatched events and DOM interactions into a paste-ready `:script` body. The facade exposes the recording lifecycle on `re-frame.story`:

| Fn | Signature | Description |
|---|---|---|
| `start-recording!` | `(start-recording! variant-id) → nil` | begin recording user-source dispatches against the variant's frame. |
| `stop-recording!` | `(stop-recording!) → events-vec` | stop; return the captured events vector. |
| `clear-recording!` | `(clear-recording!) → nil` | drop the buffer; return to idle. |
| `recording?` | `(recording?) → bool` | is a recording in flight? |
| `recorder-state` | `(recorder-state) → map` | read-only recorder state. |
| `gen-play-snippet` | `(gen-play-snippet events opts) → string` | render captured events as a paste-ready `reg-variant` EDN snippet (each event wrapped as a `[:dispatch-sync …]` step). |

The richer DOM-capture-aware translator (tagged `:click` / `:type` / `:wait` steps derived from the capture stream) lives in `re-frame.story.recorder.play-export`. It exposes `recording->script-body` (capture → normalised `:script` body map — re-exported on the facade for the MCP write-back path), `render-script-body` (body → EDN), and `render-variant-form` (full `reg-variant` form → EDN). Both translators emit the public `:script` slot. Authors wanting the rich DOM-derived DSL `:require` the sub-namespace directly.

## A complete worked example

```clojure
;; A variant whose script mixes DOM gestures, dispatches, and assertions.
(story/reg-variant :story.login/error-then-recovery
  {:doc    "User enters the wrong password, then corrects it."
   :setup  [[:auth/initialise]]
   :script [[:type        "[data-test=username]" "alice"]
            [:type        "[data-test=password]" "wrong"]
            [:click       "[data-test=submit]"]
            [:wait-until  [:db [:auth :status] :error]]
            [:assert-dom  "[data-test=error]" :visible]
            [:assert-dom  "[data-test=error]" :text "Incorrect password."]
            [:type        "[data-test=password]" "correct"]
            [:click       "[data-test=submit]"]
            [:assert      [:rf.assert/path-equals [:auth :status] :authenticated]]
            [:assert      [:rf.assert/no-warnings]]]
   :tags   #{:dev :test}})
```

DOM steps require `:dom`, so this variant is `:cannot-run` under a headless runner and runs under `:dom` / `:browser`. The `[:wait-until …]` is the deterministic alternative to a bare `[:wait ms]`.

## See also

- [Registration](registration.md) — the `reg-variant` macro the `:script` slot lives on; the `force-fx-stub-id` decorator.
- [Runtime](runtime.md) — the variant lifecycle; the run-result the script feeds.
- [MCP surface](mcp-surface.md) — the gated agent-write path that emits a `:script` body.
- [Tutorial — The recorder, and `:cannot-run`](../05-recorder-and-cannot-run.md) — record a canvas interaction end-to-end.
- [Tutorial — The reveal: the variant *is* a test](../04-the-variant-is-a-test.md) — the three verbs and the unified run-result.
