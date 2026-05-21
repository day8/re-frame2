# `:spawn` — declarative child machines

## When to load

Reach for this leaf when a state hosts an asynchronous activity whose lifetime must match the state's lifetime: an HTTP request, a websocket session, a sub-machine running a sub-flow. For wall-clock guards on that activity, use the parent state's `:after`. For "fan out N children and join when done", see `:spawn-all` below. For the cancellation/cleanup contract, see `cancellation.md`.

> **Mental model — think in xstate, map onto re-frame2.** `:spawn` IS xstate's `invoke` (state-bound child actor), but **this is the one slot re-frame2 deliberately renames** — it's the most semantically-loaded surface, and the rename breaks the "almost-correct xstate code" trap while aligning the declarative key with the imperative `:rf.machine/spawn` fx. So sketch the child-actor flow the xstate `invoke` way, then translate the slot name and watch the divergences: there is no `:onError` (errors flow through `:rf.error/*` + `:on-child-error`), no `:onSnapshot` (read the snapshot via `sub-machine`), no `autoForward` (forward events explicitly with `:fx [[:dispatch ...]]`), and only **one `:spawn` per state** (xstate admits a vector). `onDone` *does* map, to `:final?` + `:on-done` + `:output-key` below. The full divergence list is in Spec 005 §Deliberate name divergence — `:spawn` and §Deliberate omissions vs xstate.

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

Spec 005 §Declarative `:spawn` §Worked example (verbatim shape). While the parent machine sits in `:authenticating`, an actor of `:rf.http/managed` exists at `[:rf/machines <id>]`. The runtime spawns it on entry and destroys it on exit (Spec 005 §Desugaring rules; implementation at `implementation/machines/src/re_frame/machines.cljc:796-872`).

## `:spawn` spec keys

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to spawn (registered id, or inline transition table) | exactly one |
| `:data` | initial data for the child — literal map or `(fn [snapshot event] data)` | optional |
| `:on-spawn` | `(fn [data spawned-id] new-data)` — advisory; record the child id in the parent's `:data` if you want | optional |
| `:on-done` | `(fn [data result] new-data)` — fires when the child enters a `:final?` state; `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). See [§Final states](#final-states--final--on-done--output-key) below. | optional |
| `:start` | event vector dispatched to the newborn after spawn | optional |
| `:spawn-id` | explicit id instead of gensym (per-state singleton) | optional |
| `:id-prefix` | base for the gensym'd actor id; defaults to `:machine-id` | optional |

Verbatim from Spec 005 §Spec-spec keys (`spec/005-StateMachines.md:1821`). The runtime stamps `:rf/parent-id` (the parent's registration id) + `:rf/spawn-id` (the absolute prefix-path of the `:spawn`-bearing state node) onto the spawn args so the destroy fx can locate the actor on exit.

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
5. Clears the child's `[:rf/system-ids <sid>]` reverse-index entry (after `:on-done`).

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

## Composition with explicit `:entry` / `:exit`

A state may declare both `:spawn` AND user-supplied `:entry` / `:exit`. Ordering is **wire-level concatenation**: user-entry runs first, then the auto-spawn; user-exit runs first, then the auto-destroy (Spec 005 §Composition with explicit `:entry` / `:exit`; `machines.cljc:889`). The user's `:exit` action gets to read the actor's final snapshot before auto-destroy clears it.

## `:spawn-all` — spawn-and-join

When the parent needs to fan out N children and resume on a join condition (boot hydration, parallel asset loads), use `:spawn-all` (Spec 005 §Spawn-and-join via `:spawn-all`; `spec/conformance/fixtures/spawn-all-join-all-completes.edn`):

```clojure
{:hydrating
 {:spawn-all
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
- **No `:timeout-ms` on `:spawn` or `:spawn-all`.** Wall-clock guards live on the parent state's `:after`. Use `:after {30000 :timeout-target}` — when the timer fires, the standard exit cascade destroys the in-flight child and the parent transitions. The `:timeout-ms` slot is dropped; registration throws `:rf.error/spawn-timeout-ms-removed`.
- **`:on-spawn` is advisory.** The runtime tracks the spawn-id at `[:rf/spawned <parent-id> <invoke-id>]` itself — you no longer need `:on-spawn` to write the id under any specific `:data` slot for destroy to work. Most apps still set `:on-spawn (fn [{data :data id :id}] (assoc data :pending id))` so other transitions can address the child by name.
- **`:data` is a literal map or `(fn [snap ev] data)` — not arbitrary code.** When the fn form is used, it runs at state entry against the post-action snapshot (`machines.cljc:782`). If it throws, the transition halts with `:rf.error/machine-action-exception` and the snapshot does NOT commit.
- **`:start` runs after spawn; if absent the runtime dispatches a synthetic `[:rf.machine/spawned]`.** Every spawned actor receives `[:rf.machine/spawned]` if no `:start` was declared — generic child machines can declare a leaf `:on :rf.machine/spawned :target ...` transition that fires the actor's first work on entry.
- **Path convention for `:on-spawn`:** the callback receives `:data` directly. Write `(assoc data :pending id)`, not `(assoc-in snap [:data :pending] id)`. Uniform with `:guard` and `:action`.
- **One `:spawn` per state.** Multiple children per state → refactor into a compound state where each substate invokes one of the actors, or use `:spawn-all`. Validated at registration; `:spawn` + `:spawn-all` together throws `:rf.error/machine-spawn-all-with-spawn` (`machines.cljc:1669`).

## Deeper material

For the full declarative-`:spawn` desugaring rules, composition with hierarchical states, and the `:spawn-all` join-semantics matrix, see `SKILL-REDIRECT.md` → *EP — State machines (005)* §Declarative `:spawn` and §Spawn-and-join via `:spawn-all`. For the canonical worked example exercising `:spawn` + `:after` + hierarchical states, see `SKILL-REDIRECT.md` → *Pattern — WebSocket*.

---

*Derived from `implementation/machines/src/re_frame/machines.cljc` (declarative-`:spawn` desugar, `:spawn-all` join engine, `:on-spawn` stamping) @ main `89bd9c3`, and `spec/conformance/fixtures/spawn-all-*` fixtures. Re-verify after `:spawn`/`:spawn-all` runtime changes.*
