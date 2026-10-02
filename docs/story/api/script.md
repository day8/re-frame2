# Scripts

This page covers a variant's `:script`: a vector of tagged steps (dispatches, DOM gestures, waits and assertions) that a runner walks in order against the variant's frame after `:setup`. It also covers the `:rf.assert/*` assertions, how failures are recorded, the `:cannot-run` status, and the recorder functions that write a script from canvas interaction.

The recorder writes the same `:script` shape ([chapter 5](../05-recorder-and-cannot-run.md#recording-a-script)).
A variant body is closed, so any other key for a script is rejected at
registration.

## The grammar: tagged step forms

Every step is a tagged vector. The runner iterates the script in order, settles each step into the variant's frame, and records the result. Not every step is legal in both `:setup` and `:script`.

| Step | Semantics | Minimum runner |
|---|---|---|
| `[:dispatch event-vec]` | Dispatch the event and wait for the frame's work to settle. | `:headless` |
| `[:dispatch-sync event-vec]` | low-level synchronous dispatch; dispatches immediately rather than placing the event on the queue. | `:headless` |
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
| `:rf.assert/sub-equals` | `[query-vec expected]` | `(= @(subscribe query-vec) expected)`. A `:sub-overrides` pin does not satisfy it: it evaluates through `compute-sub`, which an override never touches. |
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

`visual-snapshot` reports `:cannot-run` under every Story runner: no
runner captures and compares pixels. `a11y` reports `:cannot-run` without
a browser and a scan for that variant; in a browser it can evaluate the
a11y panel's stored scan. Selecting a runner does not run that scan.

### Schema-error

`[:rf.assert/schema-error spec]` matches one violation from the epoch tape.
`spec` is a selector map, not a Malli schema. For example:

```clojure
[:rf.assert/schema-error {:where :event :event :login/flow}]
```

The selector must match the violation's identity fields:

| `:where` | Matching fields |
| --- | --- |
| `:event` | `:event`, and `:path` when present. |
| `:cofx` | `:cofx`. |
| `:fx-args` | `:fx-args`. |
| `:sub-return` | `:sub-return` and `:query-v`. |
| `:app-db` | `:registered-path` and `:path`. |
| `:machine-data` | `:machine-id` and `:phase`. |
| Other surfaces | `:where` and `:failing-id`. |

Use the violation's recorded fields when writing a precise expectation.
`[:rf.assert/schema-error]` expects any one violation. Each declaration
consumes one occurrence; repeated violations require matching declarations.
Unconsumed violations prevent a pass even when final state is valid.

## Terminal vs checkpoint

The same assertion atom lives in two positions:

- In **`:assertions`** it is **terminal** — auto-runs after the script settles, against the final state.
- As an `[:assert …]` step in **`:script`** it is a **checkpoint** — must hold at that exact point.

`[:assert …]` is rejected in `:setup` at plan-compile time with `:rf.error/story-assert-in-setup`: setup establishes preconditions; it does not judge.

## Record-don't-throw

Every assertion records its result and the script continues. A failing assertion does not abort the run: the runner walks every remaining step and collects every record, so a script with eight assertions where three fail still runs all eight and reports all three. Storybook's play functions, by contrast, stop at the first failed expectation.

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

A step or assertion lacking required capability records `:cannot-run`:

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

The cost-ordered runners (`:headless` → `:hiccup` → `:cljs-reactive` → `:dom` → `:browser`) each advertise a set of capability tokens; each step/assertion declares the tokens it needs; the plan's `:required-runner` is the union; a runner is valid iff its tokens are a superset. A run uses the runner you pass, `:headless` by default; under `{:runner :auto}` or `{:escalate true}` the cheapest valid runner is chosen. The aggregation rule: a variant whose only unmet assertions are `:cannot-run` is itself `:cannot-run` — never a silent pass. The [runner guide](../runners.md) shows how to choose a suitable host.

## Privacy posture

An assertion record's `:actual`, `:expected`, `:payload` and `:reason` pass through redaction before they reach the result, so no record carries a raw value from a sensitive path. A variant declares its sensitive app-db paths in the `:sensitive` slot of its body ([Registration](registration.md#privacy--variant-body-classification)), and an assertion against such a path records `:rf/redacted`. `:rf/redacted` is a legal `:expected` value, so you can assert the redaction itself:

```clojure
(rf.story/reg-variant :story.auth/login
  {:setup     [[:auth/login {:user "alice" :password "..."}]]
   :sensitive {:app-db [[:auth :token]]}
   :script    [[:dispatch-sync [:rf.assert/path-equals [:auth :token] :rf/redacted]]]})
```

A pass proves the assertion saw the sentinel, not the secret.

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
;; Requires [re-frame.story :as rf.story] and login-form.stories.
(rf.story/reg-variant :story.login-form/submit-from-form
  {:extends :story.login-form/idle
   :decorators [[rf.story/force-fx-stub-id :rf.http/managed {}]]
   :script [[:type "[data-test=login-email]" "ada@example.com"]
            [:type "[data-test=login-password]" "wrong"]
            [:click "[data-test=login-submit]"]
            [:assert [:rf.assert/state-is :login/flow :submitting]]
            [:assert [:rf.assert/effect-emitted :rf.http/managed]]]
   :tags #{:dev :test}})
```

The stub records the HTTP effect without sending a request or a reply.
DOM gestures require a browser document. Under a headless runner they report
`:cannot-run`; the browser shell can execute them against the mounted view.

The [recorder exercise](../05-recorder-and-cannot-run.md) builds this script
from interaction. The [runtime reference](runtime.md) describes its result.
