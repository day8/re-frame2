# Inspecting and testing

<a id="10-inspecting-and-testing"></a>

<a id="inspecting-and-testing"></a>
<a id="inspecting-and-testing-machines"></a>

Test the login table with `machine-transition` before running its effects.
When a live flow behaves differently, inspect its snapshot and the event
that changed it.

## Unit-test with `machine-transition`

A transition is a pure function of the definition, a snapshot and a trigger. The [first machine's test](tutorial.md#step-6--test-it-a-transition-is-a-pure-function) imports [`login-flow`](tutorial.md#the-complete-machine) and calls `rf.machines/machine-transition` on it directly:

Cover a refused trigger as well as a successful one:

```clojure
(ns app.login-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]
            [app.login :refer [login-flow]]))

(deftest empty-credentials-do-not-submit
  (let [before {:state :idle :data {:attempts 0 :error nil}}
        result (rf.machines/machine-transition
                 login-flow before [:auth.login/submit {:email "" :password ""}])]
    (is (= :ok (:status result)))
    (is (false? (:handled? result)))
    (is (= before (:snapshot result)))
    (is (empty? (:fx result)))))
```

Add cases for unknown triggers, the last permitted retry and a thrown
callback when the table has one. The result is one plain map:

```clojure
{:status :ok  :snapshot {:state … :data … :tags …} :fx [[:rf.http/managed …] …] :handled? true}   ;; success
{:status :error :error {:kind :rf.error/machine-action-exception :exception … …}} ;; a guard or action threw
```

- `:snapshot` is the next snapshot; an event no transition takes returns `:status :ok` with the snapshot unchanged, `:fx []` and `:handled? false`. A transition that fires and changes nothing reports `:handled? true`, so a test can tell a declined event from an accepted no-op.
- `:fx` is the effects vector in emission order.
- `:error` carries the diagnostics when a guard, action or `:data` fn throws (`:kind :rf.error/machine-action-exception`, with the `:exception` and the throwing ref) or a runaway `:always` / `:raise` cycle hits its depth limit (`:kind :rf.error/machine-always-depth-exceeded` or `:rf.error/machine-raise-depth-exceeded`). A failure carries no snapshot — nothing was committed.
- Mistakes in the call itself — a malformed `:state`, a guard or action keyword with no entry in the definition — throw an `:rf.error/*` `ex-info` rather than returning `:status :error`, exactly as `reg-machine` would.

Effects are asserted as data: the HTTP request is not performed, and the test
inspects the returned `:fx` description. This call starts from the snapshot you
provide; it does not boot a singleton, execute timers or actors, or run the
registered schema-validation boundary. Test those behaviors through a frame.

## Three useful test levels

| Level | What it tests | When to use |
|---|---|---|
| `machine-transition` | table logic, guards, action effects | default |
| unregistered handler (`make-machine-handler`) | the event handler `reg-machine` would register, built without registering it | rare |
| registered test frame | dispatch, tracing, spawn/destroy, actor messaging | actor-heavy integration |

Keep most tests at the first level. It is fast, deterministic, and does not require a browser.

The third level runs the real pipeline in a fresh frame, so it is the one that exercises spawned actors and replies. Stub the HTTP the table issues ([Test a pipeline run](../core/testing/pipeline-runs.md) has the recipe):

```clojure
(ns app.login-frame-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.test-support :as http-test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [app.login]))                  ;; registers :auth.login/flow

(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest failed-login-shows-the-error
  (http-test-support/with-request-stubs
    {[:post "/api/login"] {:reply {:failure {:kind :rf.http/http-4xx :status 401}}}}
    (fn []
      (rf/dispatch-sync [:auth.login/flow [:auth.login/submit {:email "a@b.com"
                                                               :password "x"}]])
      (let [snap (rf/subscribe-once [:rf/machine :auth.login/flow])]
        (is (= :error-shown (:state snap)))
        (is (= 1 (get-in snap [:data :attempts])))))))
```

## What failure means

At the pure testing surface, a guard or action that throws yields the `:status :error` result above rather than an exception from the test call.

At runtime, the same failure aborts the macrostep atomically. The previous snapshot remains visible. The error is reported as `:rf.error/machine-action-exception` (a thrown guard does not fall through to the next candidate).

## Read the live snapshot

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting
;;     :data  {:attempts 1 :error nil}
;;     :tags  #{:auth/busy}}
```

The [snapshot](glossary.md#snapshot) is `nil` before the first event addressed to a **singleton**. A spawned actor's snapshot exists from the moment it is spawned. For busy, read-only, connected and similar questions, ask a [tag](tags.md) instead; `[:rf.machine/has-tag? id tag]` returns `false` for an unknown or not-yet-started machine.

## Use Xray

When a flow misbehaves, [Xray's Machine Inspector](../xray/08-machine-inspector.md) shows which event moved the machine there, read from the trace stream.

A good debugging loop:

1. reproduce the behaviour;
2. click the event row in Xray;
3. open the machine inspector;
4. compare before-state and after-state;
5. inspect the guard and action records;
6. if the topology is surprising, inspect the static machine definition.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `machine-transition` is unresolved | Required from `re-frame.machines`, not `rf/` | `(:require [re-frame.machines :as rf.machines])` |
| Guard threw and a later candidate did not run | Thrown guards abort the macrostep | Fix the guard; do not rely on fall-through after a throw |
| Test expected `:rf.http/managed` and got none | `:entry` did not run, or the table under test is not the `defmachine` value | Import `login-flow`; start from `:idle` so `:submitting` entry fires |

A `nil` snapshot from `[:rf/machine id]` is covered in
[First machine → Troubleshooting](tutorial.md#troubleshooting).
## Advanced

### Trace records

Machines emit trace records through the standard trace bus. There is no separate machine log.

You will most often see records for:

- transition selected (`:rf.machine/transition`);
- guard evaluated (`:rf.machine/guard-evaluated`);
- action ran (`:rf.machine/action-ran`);
- timer scheduled, fired, cancelled, or stale (`:rf.machine.timer/scheduled`, `:rf.machine.timer/fired`, `:rf.machine.timer/cancelled`, `:rf.machine.timer/stale-after`);
- actor spawned, finished, or destroyed (`:rf.machine.spawn/spawned`, `:rf.machine/done`, `:rf.machine/destroyed`);
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
        :outcome   {:fx [[:rf.http/managed …]]}}}   ;; the return value; :ok when it returns nil; :rf.error/action-threw
```

Those records are what Xray renders. You can also tap the stream yourself in development with `(rf/register-listener! :trace …)`.

### Jump to source with `handler-meta`

Machine guards and actions are addressable through handler metadata.

```clojure
(rf/handler-meta {:source :store :kind :machine-guard :id [:auth.login/flow :under-retry-limit]})

(rf/handler-meta {:source :store :kind :machine-action :id [:auth.login/flow :issue-request]})
```

In development this can include captured source, file, line, and handler function metadata. Production builds elide development-only source details.

### Testing registered definitions

If a machine is already registered and you want its registered definition, read it off the registration. There is no `machine-meta` accessor: a machine is an `:event` registration carrying `:rf/machine? true`, and its spec is stored under the reserved `:rf/machine` key.

```clojure
(rf.machines/machine-transition
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id :auth.login/flow}))
  snapshot
  trigger)
```

Most tests should import the transition table value directly. Use registered metadata when the registration itself is part of what you are testing.
