# `:spawn` — declarative child machines

## When to load

Reach for this leaf when a state hosts an asynchronous activity whose lifetime must match the state's lifetime: an HTTP request, a websocket session, a sub-machine running a sub-flow. For wall-clock guards on that activity, use the parent state's `:after`. For "fan out N children and join when done", see `:spawn-all` below. For the cancellation/cleanup contract, see `cancellation.md`.

> **Mental model — think in xstate, map onto re-frame2.** `:spawn` IS xstate's `invoke` (state-bound child actor), renamed to the slot `:spawn` and aligned with the imperative `:rf.machine/spawn` fx — there is no `:invoke` slot. Sketch the child-actor flow the `invoke` way, then translate the slot name and watch the divergences: `invoke onError` *does* map — to the `:spawn` map's **`:on-error` transition** + an `:error? true` final leaf ([§`:on-error`](#on-error--child-failure-control-flow)); `onDone` maps to `:final?` + `:on-done` + `:output-key`. Omitted: no `:onSnapshot` (read via `sub-machine`), no `autoForward` (forward explicitly with `:fx [[:dispatch ...]]`), only **one `:spawn` per state** (xstate admits a vector). Full list: Spec 005 §Deliberate name divergence — `:spawn` and §Deliberate omissions vs xstate.

## Canonical declaration

```clojure
{:authenticating
 {:spawn {:machine-id :rf.http/managed
           :data       {:request {:method :post
                                  :url    "/api/login"
                                  :body   credentials}}}
  :after  {30000 :auth-failed}                       ;; wall-clock guard — spans retries
  :on     {:succeeded :authenticated
           :failed    :auth-failed}}}
```

Spec 005 §Declarative `:spawn` §Worked example (verbatim shape). While the parent machine sits in `:authenticating`, an actor of `:rf.http/managed` exists at `[:rf.runtime/machines :snapshots <id>]`. The runtime spawns it on entry and destroys it on exit (Spec 005 §Desugaring rules; implementation in `re-frame.machines.lifecycle-fx.registration` desugar + `re-frame.machines.lifecycle-fx.destroy`).

## `:spawn` spec keys

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to spawn (registered id, or inline transition table) | exactly one |
| `:data` | initial data for the child — literal map or `(fn [{:keys [snapshot event]}] data)` (single context-map arg) | optional |
| `:on-spawn` | `(fn [{:keys [data id]}] _)` — **advisory only; its return is DROPPED.** Do NOT try to record the child id by returning `(assoc data ...)` — that value is discarded and a non-nil return emits `:rf.warning/on-spawn-return-ignored`. To record the id, read the runtime spawn registry or dispatch a self-event (see below). | optional |
| `:on-done` | `(fn [{:keys [data result]}] new-data)` — fires when the child enters a **non-error** `:final?` state; `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). See [§Final states](#final-states--final--on-done--output-key) below. | optional |
| `:on-error` | an **`:on`-shaped transition spec** — keyword target, vector-path target, single map `{:target :guard :action}`, or guarded candidate vector — fired when the child **FAILS** (reaches an `:error? true` `:final?` leaf, or one of its actions throws). re-frame2's spelling of XState v5 `invoke onError`: a transition (control flow), not just a callback. The target resolves at the `:spawn`-bearing state's own level (a keyword is a sibling). See [§`:on-error`](#on-error--child-failure-control-flow) below. | optional |
| `:start` | event vector dispatched to the newborn after spawn | optional |
| `:fixed-actor-id` | explicit actor-address input instead of gensym (per-state singleton) — the explicit-address identity (was the overloaded `:spawn-id`) | optional |
| `:id-prefix` | base for the gensym'd actor id; defaults to `:machine-id` | optional |

Verbatim from Spec 005 §Spec-spec keys. The runtime stamps `:rf/parent-id` (the parent's registration id) + `:rf/invoke-id` (the declarative invocation path — the absolute prefix-path of the `:spawn`-bearing state node; was `:rf/spawn-id`) onto the spawn args so the destroy fx can locate the actor on exit.

## Final states — `:final?` / `:on-done` / `:output-key`

re-frame2 ships first-class final-state-with-parent-notification — the xstate `onDone` shape. A leaf state declares `:final? true`; the parent's `:spawn` declares `:on-done`.

```clojure
;; Child machine — declares its terminal state with :final? + :output-key.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-ok {:target :done
                               :action (fn [{data :data ev :event}]
                                         {:data (assoc data :token (second ev))})}}}
    :done    {:final?     true
              :output-key :token}}})

;; Parent machine — :on-done reads the child's reported result.
(rf/reg-machine :login
  {:initial :idle
   :states
   {:idle
    {:on {:submit :authenticating}}

    :authenticating
    {:spawn {:machine-id :auth-flow
              :on-done    (fn [{data :data result :result}] (assoc data :token result))}
     :on    {:auth/cancelled :idle}}}})
```

When `:auth-flow` enters `:done`, the runtime synchronously:

1. Reads the child's `:data` at `:output-key :token` — call it `result`.
2. Runs the parent's `:on-done` against the parent's `:data` with `result`.
3. Emits `:rf.machine/done` (`:machine-id` / `:output` / `:parent-id`).
4. Tears down the child via the existing destroy path with `:reason :rf.machine/finished` on the `:rf.machine/destroyed` trace.
5. Clears the child's `[:rf.runtime/machines :system-ids <sid>]` reverse-index entry (after `:on-done`).

`:output-key` is optional — when absent, `:on-done` receives `nil`. `:on-done` is optional — when absent, the auto-destroy still fires.

### Singleton symmetry — "final means final"

A **singleton** machine (registered top-level, no parent `:spawn`) that reaches `:final?` **also auto-destroys**. If you want a persistent terminal state, simply **omit `:final?`** and use an ordinary leaf state. This is intentional (D7) — `:final?` means final, not "the machine is in a stable state."

```clojure
;; If you write this, the singleton self-destructs once `:end` fires:
(rf/reg-machine :ephemeral
  {:initial :running
   :states  {:running {:on {:end :stopped}}
             :stopped {:final? true}}})    ;; <- machine handler unregisters on :end
```

### `:final?` constraints

- **Leaf-only.** A `:final?` state MUST NOT declare `:states` / `:initial` (registration rejects with `:rf.error/machine-final-state-compound`).
- **No transitions out.** A `:final?` state MUST NOT declare `:on`, `:always`, `:after`, `:spawn`, or `:spawn-all` (`:rf.error/machine-final-state-has-transitions`). `:entry` and `:exit` actions ARE permitted.
- **`:output-key` requires `:final?`.** A non-final state declaring `:output-key` is a registration error (`:rf.error/machine-output-key-without-final`).
- **Parallel regions.** A leaf in one region may be `:final?`; that region halts. The parent machine auto-destroys only when **every** region's active leaf is `:final?`.

Per Spec 005 §Final states (`spec/005-StateMachines.md`) for the full sub-decision matrix (D1-D10) and trace-ordering contract.

## `:on-error` — child-failure control flow

`:on-done` is the *success* notification (a `:data`-only callback when the child reaches a **non-error** `:final?` state). `:on-error` is its **symmetric failure counterpart**, but a **transition** rather than a callback — re-frame2's spelling of XState v5's `invoke onError`. When a spawned child FAILS, the parent **changes state** declaratively, at the `:spawn` site.

```clojure
;; Child designates an error terminal with :final? + :error? + :output-key.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-error {:target :failed
                                  :action (fn [{data :data ev :event}]
                                            {:data (assoc data :reason (second ev))})}
                   :server-ok    {:target :done
                                  :action (fn [{data :data ev :event}]
                                            {:data (assoc data :token (second ev))})}}}
    :done    {:final? true :output-key :token}
    :failed  {:final? true :error? true :output-key :reason}}})   ;; ← error terminal

;; Parent: :on-done (success → :data) AND :on-error (failure → transition).
(rf/reg-machine :login
  {:initial :idle
   :data    {}
   :states
   {:idle {:on {:submit :authenticating}}

    :authenticating
    {:spawn {:machine-id :auth-flow
              :on-done    (fn [{data :data result :result}] (assoc data :token result))
              :on-error   {:target :error                  ;; ← sibling of :authenticating
                           :action (fn [{data :data ev :event}]
                                     ;; ev = [:rf.machine.spawn/error <invoke-id> <error>]
                                     (assoc data :error (nth ev 2)))}}}

    :error {:on {:retry :authenticating}}}})
```

**Two failure triggers** (both route to the same `:on-error` transition):

1. **The child reaches an `:error? true` `:final?` leaf.** The error payload is that leaf's `:output-key` slot (or `nil`). The child auto-destroys, then the failure routes to the parent.
2. **An uncaught child action exception** (`:rf.error/machine-action-exception`). The exception envelope rides into the transition's `:event`.

**The grammar.** `:on-error` is an `:on`-shaped transition spec — keyword target, vector-path target, single transition map `{:target :guard :action}`, or guarded candidate vector — resolved **relative to the `:spawn`-bearing state's own level** (a keyword target is a **sibling**), normalised + guard-resolved through the same candidate machinery as an `:on` clause (first-guard-pass-wins; an unguarded candidate is the fallback). The error payload rides the transition's `:event` (`(nth ev 2)`), so a guard / action can branch on it.

**Success vs failure are mutually exclusive per finish.** A **plain** `:final?` leaf fires `:on-done`; an `:error?` `:final?` leaf (or a throw) fires `:on-error` and SKIPS `:on-done`. Both may be declared on one `:spawn` — the runtime picks by how the child finished.

**`:on-error` is additive.** It does NOT replace the lower-level forms: the `:rf.error/*` trace events STILL fire, and the explicit dispatch-back escape hatch (`:fx [[:dispatch [parent-id [:failed err]]]]` + parent `:on {:failed :error}`) keeps working. Reach for `:on-error` for canonical XState-shaped failure routing; for the explicit dispatch when the child needs a richer app-shaped failure event.

> **`:error?` requires `:final?`.** `:error?` is only meaningful on a `:final?` leaf — it designates the error terminal. A non-final state declaring `:error?` is a registration error (`:rf.error/machine-error-flag-without-final`). A malformed `:on-error` shape is rejected at registration (`:rf.error/machine-bad-on-error-clause`).

Per Spec 005 §`:on-error` — child-failure control flow; validated in `re-frame.machines.lifecycle-fx.validation` (`validate-spawn-on-error!` + the `:error?`-without-`:final?` guard); runtime behaviour tested in `implementation/machines/test/re_frame/machines_on_error_cljs_test.cljc`.

## Composition with explicit `:entry` / `:exit`

A state may declare both `:spawn` AND user-supplied `:entry` / `:exit`. Ordering is **wire-level concatenation**: user-entry runs first, then the auto-spawn; user-exit runs first, then the auto-destroy (Spec 005 §Composition with explicit `:entry` / `:exit`; `re-frame.machines.lifecycle-fx.exit-cascade`). The user's `:exit` action gets to read the actor's final snapshot before auto-destroy clears it.

## `:spawn-all` — spawn-and-join

When the parent needs to fan out N children and resume on a join condition (boot hydration, parallel asset loads), use `:spawn-all` (Spec 005 §Spawn-and-join via `:spawn-all`; `spec/conformance/fixtures/spawn-all-join-all-completes.edn`):

```clojure
{:hydrating
 {:spawn-all
  {:children         [{:id :cfg  :machine-id :load-config       :on-spawn :record-cfg}
                      {:id :user :machine-id :load-user-profile  :on-spawn :record-user}
                      {:id :dash :machine-id :load-dashboards    :on-spawn :record-dash}]
   :join             :all                            ;; :all (default) or :any
   :on-child-done    :child/done                     ;; child-keyword the children dispatch on success
   :on-child-error   :child/error                    ;; child-keyword the children dispatch on failure
   :on-all-complete  [:assets-loaded]                ;; parent event when :all fires
   :on-any-failed    [:asset-load-failed]            ;; parent event when any child fails
   :on-some-complete [:partial-load]}                ;; parent event when :any fires

  :on    {:assets-loaded     :ready
          :asset-load-failed :error
          :partial-load      :degraded}}}
```

Child id is the `:id` field inside each `:children` entry (NOT the `:machine-id`); each child dispatches `[:child/done :cfg & extra]` (or `:child/error`) back to the parent. The runtime intercepts these at the parent's machine boundary, updates join-state at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`, evaluates the join condition, and fires the resolved parent event — unconditionally cancelling surviving siblings on join resolution.

Validation happens at registration (`re-frame.machines.lifecycle-fx.validation`): `:on-child-done` / `:on-child-error` are required keywords, `:on-all-complete` is required when `:join :all` (the default), `:on-some-complete` is required for `:any`.

## Common gotchas

- **Pick exactly one of `:machine-id` or `:definition`.** Registration rejects both forms or neither (Spec 005 §Spec-spec keys; validated in `re-frame.machines.lifecycle-fx.validation`).
- **No `:timeout-ms` on `:spawn` or `:spawn-all`.** Wall-clock guards live on the parent state's `:after`. Use `:after {30000 :timeout-target}` — when the timer fires, the standard exit cascade destroys the in-flight child and the parent transitions. The `:timeout-ms` slot is dropped; registration throws `:rf.error/spawn-timeout-ms-removed`.
- **`:on-spawn` is advisory — its return is DROPPED.** The runtime tracks the spawn-id at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` itself, so `:on-spawn` is never needed to write the id for destroy to work. Returning `(assoc data :pending id)` records nothing — the value is discarded and a non-nil return emits `:rf.warning/on-spawn-return-ignored`. To make the id addressable by name from later transitions, three working alternatives: (1) read the runtime spawn registry directly; (2) reference the child by its deterministic `:system-id` / `:fixed-actor-id`; (3) have `:on-spawn` dispatch a self-event (via the `:router/dispatch!` late-bind hook) whose transition runs an ordinary `:action` writing the id into `:data` — the canonical WebSocket pattern (`examples/patterns/websocket/connection.cljs`, `:ws/-socket-spawned` → `:record-socket-id`). Also available declaratively by dispatching `[:rf.machine/update-snapshot {:rf/machine-id <id> :rf/patch {...}}]` from a regular action's `:fx`.
- **`:data` is a literal map or `(fn [{:keys [snapshot event]}] data)` — a single context-map arg, not positional, and not arbitrary code.** When the fn form is used, it runs at state entry against the post-action snapshot (spawn desugar in `re-frame.machines.lifecycle-fx.registration`). If it throws, the transition halts with `:rf.error/machine-action-exception` and the snapshot does NOT commit.
- **`:start` runs after spawn; if absent the runtime dispatches a synthetic `[:rf.machine.spawn/spawned]`.** Every spawned actor receives `[:rf.machine.spawn/spawned]` if no `:start` was declared — generic child machines can declare a leaf `:on :rf.machine.spawn/spawned :target ...` transition that fires the actor's first work on entry.
- **Shape of `:on-spawn`:** the callback receives one context map `{:keys [data id]}` — `data` is the parent's current `:data` slot, `id` is the freshly-allocated child id. Uniform single-map shape with `:guard` and `:action`. It is an **observation hook**: do NOT return a modified `data` to persist the id (the return is dropped — see the advisory gotcha above). Use it for a side-effect such as dispatching a self-event; persist the id through that event's `:action`.
- **One `:spawn` per state.** Multiple children per state → refactor into a compound state where each substate invokes one of the actors, or use `:spawn-all`. Validated at registration; `:spawn` + `:spawn-all` together throws `:rf.error/machine-spawn-all-with-spawn` (`re-frame.machines.lifecycle-fx.validation`).

## Deeper material

For the full declarative-`:spawn` desugaring rules, composition with hierarchical states, and the `:spawn-all` join-semantics matrix, see `SKILL-REDIRECT.md` → *EP — State machines (005)* §Declarative `:spawn` and §Spawn-and-join via `:spawn-all`. For the canonical worked example exercising `:spawn` + `:after` + hierarchical states, see `SKILL-REDIRECT.md` → *Pattern — WebSocket*.

---

*Derived from the `re-frame.machines.lifecycle-fx.*` sub-namespaces (`registration` desugar, `join` engine, `destroy` / `exit-cascade`, `validation`) @ main `89bd9c3`, and `spec/conformance/fixtures/spawn-all-*` fixtures. Citations are symbol-level (machines.cljc was split); re-verify after `:spawn`/`:spawn-all` runtime changes.*
