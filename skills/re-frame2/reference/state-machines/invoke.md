# `:invoke` — declarative child machines

## When to load

Reach for this leaf when a state hosts an asynchronous activity whose lifetime must match the state's lifetime: an HTTP request, a websocket session, a sub-machine running a sub-flow. For wall-clock guards on that activity, use the parent state's `:after`. For "fan out N children and join when done", see `:invoke-all` below. For the cancellation/cleanup contract, see `cancellation.md`.

## Canonical declaration

```clojure
{:authenticating
 {:invoke {:machine-id :rf.http/managed
           :data       {:request {:method :post
                                  :url    "/api/login"
                                  :body   credentials}}}
  :after  {30000 :auth-failed}                       ;; wall-clock guard — spans retries
  :on     {:succeeded :authenticated
           :failed    :auth-failed}}}
```

Spec 005 §Declarative `:invoke` §Worked example (verbatim shape). While the parent machine sits in `:authenticating`, an actor of `:rf.http/managed` exists at `[:rf/machines <id>]`. The runtime spawns it on entry and destroys it on exit (Spec 005 §Desugaring rules; implementation at `implementation/machines/src/re_frame/machines.cljc:796-872`).

## `:invoke` spec keys

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to spawn (registered id, or inline transition table) | exactly one |
| `:data` | initial data for the child — literal map or `(fn [snapshot event] data)` | optional |
| `:on-spawn` | `(fn [data spawned-id] new-data)` — advisory; record the child id in the parent's `:data` if you want | optional |
| `:start` | event vector dispatched to the newborn after spawn | optional |
| `:invoke-id` | explicit id instead of gensym (per-state singleton) | optional |
| `:id-prefix` | base for the gensym'd actor id; defaults to `:machine-id` | optional |

Verbatim from Spec 005 §Spec-spec keys (`spec/005-StateMachines.md:1821`). The runtime stamps `:rf/parent-id` (the parent's registration id) + `:rf/invoke-id` (the absolute prefix-path of the `:invoke`-bearing state node) onto the spawn args so the destroy fx can locate the actor on exit.

## Terminal contract — re-frame2 has no `onDone`

A child reaching a "done" state does **not** automatically signal the parent. There is no `final?: true` marker, no `onDone` callback. The child explicitly dispatches back:

```clojure
;; Inside the child's terminal-state action:
(rf/reg-machine :auth-flow
  {:initial :running
   :states
   {:running
    {:on {:server-ok {:target :done
                      :action (fn [data _]
                                {:fx [[:dispatch
                                       [(:rf/parent-id data)
                                        [:succeeded (:user-token data)]]]]})}}}
    :done {}}})
```

The parent's `:rf/parent-id` is stamped onto the child's `:data` at spawn time (`machines.cljc:2278`, Spec 005 §Runtime stamps on the spawned actor's `:data`). The child reads it and dispatches `[<parent-id> [:succeeded ...]]`; the parent's `:on :succeeded :authenticated` transition fires.

Per Spec 005 §Deliberate omissions vs xstate (`spec/005-StateMachines.md:1922`): the parent reads `(:rf/parent-id data)`; the child dispatches back via the standard `:dispatch` fx. No new mechanism. Same shape as the `:rf.http/managed` wrapper's terminal `:succeeded` / `:failed` events.

## Composition with explicit `:entry` / `:exit`

A state may declare both `:invoke` AND user-supplied `:entry` / `:exit`. Ordering is **wire-level concatenation**: user-entry runs first, then the auto-spawn; user-exit runs first, then the auto-destroy (Spec 005 §Composition with explicit `:entry` / `:exit`; `machines.cljc:889`). The user's `:exit` action gets to read the actor's final snapshot before auto-destroy clears it.

## `:invoke-all` — spawn-and-join

When the parent needs to fan out N children and resume on a join condition (boot hydration, parallel asset loads), use `:invoke-all` (Spec 005 §Spawn-and-join via `:invoke-all`; `spec/conformance/fixtures/invoke-all-join-all-completes.edn`):

```clojure
{:hydrating
 {:invoke-all
  {:children         [{:id :cfg  :machine-id :load-config       :on-spawn :record-cfg}
                      {:id :user :machine-id :load-user-profile  :on-spawn :record-user}
                      {:id :dash :machine-id :load-dashboards    :on-spawn :record-dash}]
   :join             :all                            ;; :all / :any / {:n N} / {:fn pred}
   :on-child-done    :child/done                     ;; child-keyword the children dispatch on success
   :on-child-error   :child/error                    ;; child-keyword the children dispatch on failure
   :on-all-complete  [:assets-loaded]                ;; parent event when :all fires
   :on-any-failed    [:asset-load-failed]            ;; parent event when any child fails
   :on-some-complete [:partial-load]}                ;; parent event when :n or :any fires

  :on    {:assets-loaded     :ready
          :asset-load-failed :error
          :partial-load      :degraded}}}
```

Child id is the `:id` field inside each `:children` entry (NOT the `:machine-id`); each child dispatches `[:child/done :cfg & extra]` (or `:child/error`) back to the parent. The runtime intercepts these at the parent's machine boundary, updates join-state at `[:rf/spawned <parent-id> <invoke-id>]`, evaluates the join condition, and fires the resolved parent event — automatically cancelling surviving siblings (`:cancel-on-decision?` defaults to `true`).

Validation happens at registration (`machines.cljc:1653`): `:on-child-done` / `:on-child-error` are required keywords, `:on-all-complete` is required when `:join :all` (the default), `:on-some-complete` is required for `:any` / `{:n N}` / `{:fn pred}`.

## Common gotchas

- **Pick exactly one of `:machine-id` or `:definition`.** Registration rejects both forms or neither (`spec/005-StateMachines.md:1920`).
- **No `:timeout-ms` on `:invoke` or `:invoke-all`.** Wall-clock guards live on the parent state's `:after`. Use `:after {30000 :timeout-target}` — when the timer fires, the standard exit cascade destroys the in-flight child and the parent transitions. Per rf2-3y3y the `:timeout-ms` slot is dropped; registration throws `:rf.error/invoke-timeout-ms-removed`.
- **`:on-spawn` is advisory.** Per rf2-t07u Option A revised, the runtime tracks the spawn-id at `[:rf/spawned <parent-id> <invoke-id>]` itself — you no longer need `:on-spawn` to write the id under any specific `:data` slot for destroy to work. Most apps still set `:on-spawn (fn [data id] (assoc data :pending id))` so other transitions can address the child by name.
- **`:data` is a literal map or `(fn [snap ev] data)` — not arbitrary code.** When the fn form is used, it runs at state entry against the post-action snapshot (`machines.cljc:782`). If it throws, the transition halts with `:rf.error/machine-action-exception` and the snapshot does NOT commit.
- **`:start` runs after spawn; if absent the runtime dispatches a synthetic `[:rf.machine/spawned]`.** Per rf2-ijm7, every spawned actor receives `[:rf.machine/spawned]` if no `:start` was declared — generic child machines can declare a leaf `:on :rf.machine/spawned :target ...` transition that fires the actor's first work on entry.
- **Path convention for `:on-spawn`:** the callback receives `:data` directly. Write `(assoc data :pending id)`, not `(assoc-in snap [:data :pending] id)`. Uniform with `:guard` and `:action`.
- **One `:invoke` per state.** Multiple children per state → refactor into a compound state where each substate invokes one of the actors, or use `:invoke-all`. Validated at registration; `:invoke` + `:invoke-all` together throws `:rf.error/machine-invoke-all-with-invoke` (`machines.cljc:1669`).

## Deeper material

For the full declarative-`:invoke` desugaring rules, composition with hierarchical states, and the `:invoke-all` join-semantics matrix, see `SKILL-REDIRECT.md` → *EP — State machines (005)* §Declarative `:invoke` and §Spawn-and-join via `:invoke-all`. For the canonical worked example exercising `:invoke` + `:after` + hierarchical states, see `SKILL-REDIRECT.md` → *Pattern — WebSocket*.
