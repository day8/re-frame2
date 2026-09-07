# 9. Inspecting and testing

<a id="inspecting-and-testing"></a>
<a id="inspecting-and-testing-machines"></a>

Machines are ordinary re-frame2 state and ordinary re-frame2 events.

That gives you four inspection surfaces:

| Surface | Use it to answer |
|---|---|
| `[:rf/machine id]` | What state is this machine in now? |
| `[:rf.machine/has-tag? id tag]` | Is this semantic state true? |
| [Xray Machine Inspector](../xray/08-machine-inspector.md) | What did this event do? |
| `machine-transition` | Is this transition table correct? |

## Read the live snapshot

The primary read is the framework subscription. There is no named-read-sugar function:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting
;;     :data  {:attempts 1 :error nil}
;;     :tags  #{:auth/busy}}
```

The [snapshot](glossary.md#snapshot) is `nil` before the first event addressed to a **singleton**. A spawned actor's snapshot exists from the moment it is spawned.

For views, prefer projection subscriptions:

```clojure
(rf/reg-sub :auth.login/error {:inputs [[:rf/machine :auth.login/flow]]}
  (fn [[m] _]
    (get-in m [:data :error])))
```

Now the view reads `[:auth.login/error]` instead of destructuring the whole machine everywhere.

## Ask by tag

```clojure
@(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])
;; => true or false
```

Use this for busy, read-only, connected, terminal, and similar semantic questions. It returns `false` for an unknown or not-yet-started machine.

See [Tags](tags.md) for collapsing several states into one render decision.

## Use Xray

When a flow misbehaves, the useful question is often not "what is the current state?" but "which event moved it there?"

[Xray's Machine Inspector](../xray/08-machine-inspector.md) answers that from the trace stream.

A good debugging loop:

1. reproduce the behaviour;
2. click the event row in Xray;
3. open the machine inspector;
4. compare before-state and after-state;
5. inspect the guard and action records;
6. if the topology is surprising, inspect the static machine definition.

The dynamic view explains one transition. The static view explains the table.

## Trace records

Machines emit trace records through the standard trace bus. There is no separate machine log.

You will most often see records for:

- transition selected (`:rf.machine/transition`);
- guard evaluated (`:rf.machine/guard-evaluated`);
- action ran (`:rf.machine/action-ran`);
- timer scheduled, fired, cancelled, or stale;
- actor spawned or destroyed;
- unhandled event no-op (`:rf.machine.event/unhandled-no-op`).

A guard trace tells you:

```clojure
{:operation :rf.machine/guard-evaluated
 :tags {:actor-id :auth.login/flow
        :guard-id :under-retry-limit
        :state    :submitting
        :outcome  :pass}}   ;; :pass | :fail | :threw
```

An action trace tells you:

```clojure
{:operation :rf.machine/action-ran
 :tags {:actor-id  :auth.login/flow
        :action-id :issue-request
        :phase     :entry
        :outcome   :ok}}    ;; return value, :ok, or :rf.error/action-threw
```

Those records are what Xray renders. You can also tap the stream yourself in development with `(rf/register-listener! :trace …)`.

## Jump to source with `handler-meta`

Machine guards and actions are addressable through handler metadata.

```clojure
(rf/handler-meta {:source :store :kind :machine-guard :id [:auth.login/flow :under-retry-limit]})

(rf/handler-meta {:source :store :kind :machine-action :id [:auth.login/flow :issue-request]})
```

In development this can include captured source, file, line, and handler function metadata. Production builds elide development-only source details.

## Unit-test with `machine-transition`

A transition table is a value. A transition is a pure function of:

```clojure
(definition, snapshot, trigger)
```

Import `login-flow` from the [first machine](tutorial.md#the-complete-machine) and call it directly:

```clojure
(ns app.login-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]
            [app.login :refer [login-flow]]))

(deftest login-flow-test
  ;; cf. examples/capabilities/machines/state_machine_walkthrough
  ;; :idle --submit--> :submitting; :entry describes the HTTP fx
  (let [{:keys [status snapshot fx]}
        (rf.machines/machine-transition
          login-flow
          {:state :idle :data {:attempts 0 :error nil}}
          [:auth.login/submit {:email "a@b.com"
                               :password "secret"}])]
    (is (= :ok status))
    (is (= :submitting (:state snapshot)))
    (is (= :rf.http/managed (ffirst fx))))

  ;; two failures already recorded; the third locks out and is still counted
  (let [{:keys [status snapshot]}
        (rf.machines/machine-transition
          login-flow
          {:state :submitting :data {:attempts 2 :error nil}}
          [:auth.login/failure {:error {:message "bad creds"}}])]
    (is (= :ok status))
    (is (= :locked-out (:state snapshot)))
    (is (= 3 (get-in snapshot [:data :attempts])))))
```

The result is one plain map:

```clojure
{:status :ok  :snapshot {:state … :data … :tags …} :fx [[:rf.http/managed …] …]}   ;; success
{:status :error :error {:kind :rf.error/machine-action-exception :exception … …}} ;; a guard or action threw
```

- `:snapshot` is the next snapshot; an event no transition matches returns `:status :ok` with the snapshot unchanged and `:fx []`.
- `:fx` is the effects vector in emission order.
- `:error` carries the diagnostics when a guard, action or `:data` fn throws (`:kind :rf.error/machine-action-exception`, with the `:exception` and the throwing ref) or a runaway `:always` / `:raise` cycle hits its depth limit (`:kind :rf.error/machine-always-depth-exceeded` or `:rf.error/machine-raise-depth-exceeded`). A failure carries no snapshot — nothing was committed.
- Mistakes in the call itself — a malformed `:state`, a guard or action keyword with no entry in the definition — throw an `:rf.error/*` `ex-info` rather than returning `:status :error`, exactly as `reg-machine` would.

Effects are asserted as data. The HTTP request is not performed in this test; the returned `:fx` description is inspected.

`machine-transition` and `machine-meta` live on `re-frame.machines`, not the `rf/` facade.

## Testing registered definitions

If a machine is already registered and you want its registered definition, use machine metadata:

```clojure
(rf.machines/machine-transition
  (rf.machines/machine-meta :auth.login/flow)
  snapshot
  trigger)
```

Most tests should import the transition table value directly. Use registered metadata when the registration itself is part of what you are testing.

## Three useful test levels

| Level | What it tests | When to use |
|---|---|---|
| `machine-transition` | table logic, guards, action effects | default |
| unregistered handler | lowering and handler-level integration | rare |
| registered test frame | dispatch, tracing, spawn/destroy, actor messaging | actor-heavy integration |

Keep most tests at the first level. It is fast, deterministic, and does not require a browser.

## What failure means

A throwing guard or action becomes a failure result at the pure testing surface. It does not escape as an exception from the test call: `(result/fail? r)` is true and `(result/info r)` carries the diagnostic.

At runtime, the same failure aborts the macrostep atomically. The previous snapshot remains visible. The error is reported as `:rf.error/machine-action-exception` (a thrown guard does not fall through to the next candidate).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `[:rf/machine id]` is `nil` | No event has addressed that singleton yet | Dispatch first, or fall back to the definition's `:initial` |
| `machine-transition` is unresolved | Required from `re-frame.machines`, not `rf/` | `(:require [re-frame.machines :as rf.machines])` |
| Guard threw and a later candidate did not run | Thrown guards abort the macrostep | Fix the guard; do not rely on fall-through after a throw |
| Test expected `:rf.http/managed` and got none | `:entry` did not run, or the table under test is not the `defmachine` value | Import `login-flow`; start from `:idle` so `:submitting` entry fires |
