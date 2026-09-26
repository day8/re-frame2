# Scripts

This chapter is about a variant's **`:script`** — the slot that turns a variant body into a deterministic, replayable sequence of dispatches, DOM gestures, waits, and assertions. The core is **a tagged-step grammar** that a runner walks in order against the variant's frame. Around it sit the **`:rf.assert/*` assertions** (seven of them events that ride the `[:dispatch-sync …]` rail, the rest evaluated against the run's evidence), the **record-don't-throw** discipline (failures append to the run rather than aborting), the **`:cannot-run`** refusal (a step the runner can't observe is refused, never silently passed), and the **recorder** (which authors a `:script` body from canvas interaction).

The public authoring slots are **`:setup`** (preconditions) and **`:script`** (behaviour under test); you execute a variant with the three verbs `rf.story/run` / `rf.story/is` / `rf.story/explain`.

!!! note "Where the normative contract lives"

    The normative contract — the four-bucket plan, the three verbs, `:cannot-run`,
    composition, the schema floor, and the epoch-tape evidence projection — lives in
    [`017-Testing-Story.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/017-Testing-Story.md).
    `:script` is the only spelling there is: the recorder emits it too
    ([tutorial chapter 5](../05-recorder-and-cannot-run.md)), and a variant body
    is closed, so any other key for a script is rejected at registration.

## The grammar — tagged step forms

Every step is a tagged vector. The runner iterates the script in order, settles each step into the variant's frame, and records the result. Not every step is legal in both `:setup` and `:script`.

| Step | Semantics | Minimum runner |
|---|---|---|
| `[:dispatch event-vec]` | dispatch the event and advance when the runner reaches `settled-boundary` (in headless, the run-to-fixed-point drain). | `:headless` |
| `[:dispatch-sync event-vec]` | low-level synchronous dispatch; the seven event-backed `:rf.assert/*` assertions ride this rail. | `:headless` |
| `[:wait-until predicate]` | settle on a condition (`[:db path expected]` / `[:db path :pred fn]` / `[:queue-empty]`), checked once the preceding step has settled; a condition that does not hold fails the step with a reason naming it, such as `wait-until [:db [:form :ready?] true] never became true`. | depends on predicate |
| `[:wait ms]` | wall-clock sleep. `run` and `is` honour it; `assert-deterministic` refuses a program containing one as `:cannot-run`. | runner-dependent |
| `[:assert assertion-vec]` | checkpoint assertion at this point in the script. **Illegal in `:setup`.** | depends on assertion |
| `[:assert-db path value]` | checkpoint: the app-db value at `path` equals `value`. `[:assert-db path :pred f]` tests it with a predicate instead. | `:headless` |
| `[:flush-presence]` / `[:flush-presence ms]` | advance the presence clock the host installed for enter and exit transitions, to quiescence or by `ms`. With no clock installed the step is `:cannot-run`. | `:headless` |
| `[:click selector]` | DOM click. | `:dom` |
| `[:type selector text]` | DOM text input. | `:dom` |
| `[:focus selector]` | DOM focus. | `:dom` |
| `[:assert-dom selector :visible \| :hidden \| :text txt]` | DOM-shape assertion: the element is present, is absent or hidden, or has that text. | `:dom` |

A `:dispatch` or `:dispatch-sync` step takes an optional third element, `{:rf.cofx {...}}`, the coeffects to present to the handler; the recorder writes the clock reading this way, as `{:rf.cofx {:rf/time-ms 1790412881152}}`. A step whose tag is known but whose arguments are the wrong shape errors the run with `:rf.error/story-bad-step` before any step runs.

## Script shapes and plays

`:script` takes a step vector or a map around one:

```clojure
:script {:name      "happy path"   ; optional
         :auto-run? true           ; optional; true unless set false
         :script    [[:dispatch [:counter/inc]]
                     [:assert-db [:count] 1]]}
```

The map is closed: a misspelt key such as `:autorun?` throws at registration. `:auto-run? false` stops the script from running on mount and keeps `run`, `is` and the Tests tab from running it; the toolbar's **Re-run** still runs it.

`:plays` holds several named scripts instead, each `{:name "..." :script [...] :auto-run? bool}` with a required, unique `:name`. The first play auto-runs unless it sets `:auto-run? false`; the others run only when they set `:auto-run? true` or when you pick them from the toolbar's play dropdown. A variant declares `:script` or `:plays`, never both.

A bare event vector (`[:my/event …]`) is read as `[:dispatch [:my/event …]]`. A vector whose first element is a step tag is always read as that step, so an app event named `:click`, `:wait`, `:focus` or `:dispatch` is written in full as `[:dispatch [:click …]]`.

## The seven canonical `:rf.assert/*` events

The assertion vocabulary auto-registers at Story load (from the first `reg-*` call). They record results rather than throwing.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)`. The workhorse. |
| `:rf.assert/path-matches` | `[path schema]` | the value at `path` validates against a Malli schema. |
| `:rf.assert/sub-equals` | `[query-vec expected]` | `(= @(subscribe query-vec) expected)`. **Honesty rule:** NOT satisfied by a `:sub-overrides` pin — it evaluates through `compute-sub`, which an override never touches. |
| `:rf.assert/dispatched?` | `[event-vec]`, `[event-id]` or `[pred]` | was a matching event dispatched into the frame during the run, in `:setup` or `:script`? An event vector must match exactly, an event id matches any event with that id, and a predicate receives each dispatched event vector. |
| `:rf.assert/state-is` | `[machine-id state]` | the active state of a `reg-machine` machine. |
| `:rf.assert/no-warnings` | `[]` | no warning-severity trace event (`:op-type :warning`) fired during the run — any operation namespace, not only `:rf.warning/*`. |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | the fx was emitted; the optional `pred` is a unary fn over the matched fx-id keyword. |

Plus `:rf.assert/schema-error`, which is evaluated against the epoch tape rather than dispatched and needs the `:schema` capability. It declares a schema violation the run is expected to produce, such as `[:rf.assert/schema-error {:where :event :event :login/flow}]`, or `[:rf.assert/schema-error]` for any one, and fails when no matching violation happens. Each declaration consumes one matching violation, and any violation left unconsumed fails the run.

### Further assertion ids

These ids are evaluated by the runner or against the epoch tape rather than dispatched as events, so they belong in `:assertions`, a check or an `[:assert …]` step. An assertion id outside this page's two tables fails plan construction with `:rf.error/story-unknown-assertion`.

| Id | Checks | Needs |
|---|---|---|
| `:rf.assert/dom-visible`, `:rf.assert/dom-hidden`, `:rf.assert/dom-text` | The element is present, absent, or has the text. `[:assert-dom sel :text "0"]` is shorthand for `[:assert [:rf.assert/dom-text sel "0"]]`. | `:dom` |
| `:rf.assert/a11y-structural` | Structural accessibility over the rendered hiccup. | `:hiccup-structure` |
| `:rf.assert/a11y` | An axe-style accessibility scan. | `:a11y-engine` |
| `:rf.assert/visual-snapshot` | Captured pixels against a baseline. The snapshot identity is not pixels and never passes it. | `:pixels` |
| `:rf.assert/caused` | `[:rf.assert/caused {:event id :sub sub-id :min n :max n}]`: the event caused at least `:min` (default 1) recomputes of the sub, or renders of a `:view`. | `:reactive-counts` |
| `:rf.assert/no-cascade-rerender` | The same spec, with `:max` defaulting to 0: the event caused no further recompute or render. | `:reactive-counts` |

Every Story runner today reports `:rf.assert/a11y` and `:rf.assert/visual-snapshot` as `:cannot-run`: no runner produces the `:a11y-engine` or `:pixels` evidence they need, so neither can pass a run. Each one's own row reads `:cannot-run` too, naming the evidence it never received (`:missing-evidence #{:a11y}` or `#{:pixels}`): an a11y row passes only on an axe scan of the variant's frame, and a visual row has no captured pixels to pass on.

## Terminal vs checkpoint

The same assertion atom lives in two positions:

- In **`:assertions`** it is **terminal** — auto-runs after the script settles, against the final state.
- As an `[:assert …]` step in **`:script`** it is a **checkpoint** — must hold at that exact point.

`[:assert …]` is rejected in `:setup` at plan-compile time with `:rf.error/story-assert-in-setup`: setup establishes preconditions; it does not judge.

## Record-don't-throw

Every assertion records its result and the script continues. A failing assertion does not abort the run — the runner walks every remaining step, accumulates every record, and the result asks "did every entry pass?" at the end. A script with eight assertions where three fail still runs all eight. This diverges from Storybook's throw-on-first-failure, which is partly forced on it by JavaScript's async-throw model; re-frame2's run-to-completion drain gives Story room to do better.

```clojure
(rf.story/reg-variant :story.counter/clicked-three-times
  {:doc    "Counter after three increments from zero."
   :setup  [[:counter/initialise 0]]
   :script [[:dispatch-sync [:counter/inc]]
            [:dispatch-sync [:counter/inc]]
            [:dispatch-sync [:counter/inc]]
            [:dispatch-sync [:rf.assert/dispatched? [:counter/inc]]]]
   :tags   #{:dev :docs :test}})
```

`:rf.assert/dispatched?` sees every event the run dispatched, in `:setup` as well as `:script`, so an event that setup already dispatches satisfies it whatever the script does.

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

A headless run also records the step itself as `:runner-cannot-attempt-step`, with a `:message` such as `no DOM — cannot click "[data-test=submit]"`.

The cost-ordered runners (`:headless` → `:hiccup` → `:cljs-reactive` → `:dom` → `:browser`) each advertise a set of capability tokens; each step/assertion declares the tokens it needs; the plan's `:required-runner` is the union; a runner is valid iff its tokens are a superset. A run uses the runner you pass, `:headless` by default; under `{:runner :auto}` or `{:escalate true}` the cheapest valid runner is chosen. The aggregation rule: a variant whose only unmet assertions are `:cannot-run` is itself `:cannot-run` — never a silent pass. (Full runner model: [tutorial chapter 5](../05-recorder-and-cannot-run.md).)

## Privacy posture

`:rf.assert/*` records build their `:actual` / `:expected` / `:payload` / `:reason` slots through the wire-elision walker before landing in the result — no slot carries a raw secret for a sensitive path. Durable app-db classification is declared on the **variant body** and lowered into the frame's elision registry as commit-plane classification effects: a variant declares its sensitive paths via the `:sensitive` slot on its body, and an assertion against such a path records `:rf/redacted`, not the raw value. The `:rf/redacted` sentinel is a first-class legal `:expected` value — author it directly to pin the redaction contract:

```clojure
(rf.story/reg-variant :story.auth/login
  {:setup     [[:auth/login {:user "alice" :password "..."}]]
   :sensitive {:app-db [[:auth :token]]}
   :script    [[:dispatch-sync [:rf.assert/path-equals [:auth :token] :rf/redacted]]]})
```

A passing assertion proves the observation surface saw the sentinel, not the secret. (There is no `add-marks` / `set-marks` mutation surface — classification is declared on the variant body and lowered through commit-plane effects.)

## The recorder

Story's canvas recorder captures dispatched events and DOM interactions into a paste-ready `:script` body. The facade exposes the recording lifecycle on `re-frame.story`:

| Fn | Signature | Description |
|---|---|---|
| `start-recording!` | `(start-recording! variant-id) → state-map` | begin recording user-source dispatches against the variant's frame, stopping any recording in flight; returns the new recorder state. |
| `stop-recording!` | `(stop-recording!) → state-map` | stop; the returned state carries `:recording? false`, the captured `:events` in order and the source `:variant-id`. |
| `clear-recording!` | `(clear-recording!) → state-map` | drop the buffer; return to idle, and return the idle state. |
| `recording?` | `(recording?) → bool` | is a recording in flight? |
| `recorder-state` | `(recorder-state) → map` | read-only recorder state. |
| `gen-play-snippet` | `(gen-play-snippet events opts) → string` | render captured events as a paste-ready `reg-variant` EDN snippet (each event wrapped as a `[:dispatch-sync …]` step). |

The richer DOM-capture-aware translator (tagged `:click` / `:type` / `:wait` steps derived from the capture stream) lives in `re-frame.story.recorder.play-export`. It exposes `recording->script-body` (capture → normalised `:script` body map — re-exported on the facade as the runtime counterpart to `gen-play-snippet`), `render-script-body` (body → EDN), and `render-variant-form` (full `reg-variant` form → EDN). Both translators emit the public `:script` slot. Authors wanting the rich DOM-derived DSL `:require` the sub-namespace directly.

## A complete worked example

```clojure
;; A variant whose script mixes DOM gestures, dispatches, and assertions.
(rf.story/reg-variant :story.login/error-then-recovery
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
