# Coeffects (cofx)

## When to load

Registering a `reg-cofx` handler that injects an input (current time, localStorage value, generated id, ...) into the handler's coeffect map; or attaching one to an event via `inject-cofx`.

## Canonical signature

```clojure
(rf/reg-cofx :cofx-id           handler-fn)
(rf/reg-cofx :cofx-id metadata? handler-fn)

;; handler-fn :: (fn [ctx])         -> ctx                  ;; 1-arg form
;;             | (fn [ctx value])   -> ctx                  ;; 2-arg form (paired with inject-cofx ... value)
;;
;; The handler stashes data under (:coeffects ctx). The conventional key
;; is the cofx-id itself.

(rf/inject-cofx :cofx-id)
(rf/inject-cofx :cofx-id value)
```

Verified in `implementation/core/src/re_frame/cofx.cljc:77` (`reg-cofx`) and `cofx.cljc:134` (`inject-cofx`). Metadata may carry:

- `:doc` — one-sentence what-and-why
- `:spec` — Malli schema for the injected value (Spec 010 §Validation order step 2)
- `:platforms` — `#{:client :server}`; defaults to both. Cofx with mismatched platforms emit `:rf.cofx/skipped-on-platform` instead of running. Mirrors the `reg-fx` contract per `spec/011-SSR.md` §Effect handling on the server — a client-only cofx (browser locale, localStorage, navigator-info) silently no-ops when injected under an SSR-server frame so it neither blows up under JVM render nor injects nonsense values. The event handler still runs; only the injection is skipped. Example: `^{:platforms #{:client}} (rf/reg-cofx :browser-locale (fn [ctx] (assoc-in ctx [:coeffects :browser-locale] js/navigator.language)))` is safe to register on both platforms; under an `:ssr-server` frame the injection is skipped and the handler sees `nil` for `:browser-locale`.

`inject-cofx` returns an **interceptor** — pass it in the event's interceptors vector (the positional slot, not the metadata-map):

```clojure
(rf/reg-event-fx :id
  [(rf/inject-cofx :now) (rf/inject-cofx :random-id)]   ;; interceptors slot
  (fn [{:keys [db now random-id]} [_ payload]] ...))
```

## Standard cofx

The runtime ships `:db` and `:event` — both are already populated on the context before the chain runs (`cofx.cljc:92-104`), so explicitly injecting them is a no-op. They exist for symmetry with v1. Everything else (current time, browser language, localStorage values, ids) is user-registered.

## Canonical mini-example

From `examples/reagent/todomvc/db.cljs`:

```clojure
(rf/reg-cofx :todo.storage/todos
  {:doc "Inject the saved TodoMVC items from localStorage into coeffects."}
  (fn cofx-todo-storage-todos [ctx]
    (assoc-in ctx [:coeffects :todo.storage/todos]
              (some-> (.-localStorage js/globalThis)
                      (.getItem ls-key)
                      (storage->todos)))))
```

And the handler that ingests it (`examples/reagent/todomvc/events.cljs`):

```clojure
(rf/reg-event-fx :todo/initialise
  [(rf/inject-cofx :todo.storage/todos)]
  (fn [{:todo.storage/keys [todos]} _]
    {:db (assoc db/default-db :todos todos)}))
```

The cofx writes under `[:coeffects :todo.storage/todos]`; the handler destructures the same key off the coeffect map.

## Why coeffects instead of `(.-localStorage ...)` in the handler?

Pure handlers are testable, replayable (for re-frame-pair2 epoch restore), and serialisable (for SSR snapshots). A handler that reads `Date.now()` directly is non-deterministic; the same handler that destructures `now` from coeffects is a pure function of its inputs.

## Reading a sub from a handler — wrap as cofx

A handler that needs a sub's current value **must not** call `(rf/subscribe ...)` (or `rf/subscribe-once`) from inside its body. Subscriptions are a view-layer concern; reading one implicitly from a handler breaks per-handler purity — the same `[coeffects event]` pair would no longer fully determine the handler's output, and `subscribe` would silently establish a reaction in whatever evaluation context the drain loop happened to be in.

The canonical shape is to wrap the sub read as a cofx and inject it. The cofx handler is the one place the impure read lives; the event handler stays a function of its coeffects map.

```clojure
;; Register a cofx that materialises the sub at injection time.
(rf/reg-cofx :user/current
  {:doc "Inject the value of the [:user/current] sub into coeffects."}
  (fn [ctx]
    (assoc-in ctx [:coeffects :user/current]
              (rf/subscribe-once [:user/current]))))

;; Inject and destructure like any other cofx.
(rf/reg-event-fx :order/place
  [(rf/inject-cofx :user/current)]
  (fn [{:keys [db user/current]} [_ order]]
    {:db (assoc-in db [:orders (:id order)]
                   (assoc order :placed-by current))}))
```

Two notes on the cofx body:

- **`rf/subscribe-once` is the right primitive** — it materialises, derefs, and unsubscribes in one call, so the cofx leaves no reaction behind. `@(rf/subscribe ...)` inside the cofx would also work but leaks the reaction until GC.
- **Parameterise with the 2-arg form.** If the sub takes args (`[:order/by-id 42]`), register the cofx binary and pass the args through `inject-cofx`:

```clojure
(rf/reg-cofx :sub/value
  (fn [ctx query-v]
    (assoc-in ctx [:coeffects :sub/value] (rf/subscribe-once query-v))))

;; Used:
(rf/reg-event-fx :order/cancel
  [(rf/inject-cofx :sub/value [:order/by-id 42])]
  (fn [{:keys [db sub/value]} _] ...))
```

There is deliberately **no `cofx-from-sub` shortcut helper** in `re-frame.core` (rf2-gw8j closed as won't-ship, 2026-05-13). The five-line `reg-cofx` wrapper above is the canonical shape; collapsing it into a one-liner would imply that subscribing-inside-handlers is the rule and the wrap is the workaround, when it is the other way around.

Narrative treatment of the same pattern (for humans): [`docs/guide/05-coeffects.md`](../../../../docs/guide/05-coeffects.md) §Reading a sub from a handler.

## Common gotchas

- **`inject-cofx` returns an interceptor, not the value.** It must go in the positional interceptors vector, not the metadata-map (the metadata-map's `:interceptors` key is silently dropped — see [events.md](events.md)).
- **The injected key convention is the cofx-id itself.** Stash under `[:coeffects :my/cofx-id]` so destructuring with `{:my/keys [...]}` works cleanly. If `:spec` validation is declared on the cofx metadata, the validator looks up under the cofx-id key (`cofx.cljc:33-36`).
- **Order matters.** Interceptors run in vector order; a cofx that depends on another cofx's value must come after it.
- **Two-arg form is for parameterised injection.** Use `(inject-cofx :random-int max-value)` when the same cofx-id needs a different value per attachment.
- **Missing registration is a structured error trace, not a throw.** `inject-cofx` of an unregistered id emits `:rf.error/no-such-cofx` (carrying `:cofx-id`, `:event-id`, and the optional 2-arity `:cofx-value`) and lets the ctx flow through unchanged (`cofx.cljc:~78,88`). Subscribe via `register-trace-cb!` (or watch through Causa / re-frame-pair2) to surface these.
- **`:platforms #{:client}` makes the cofx skip silently under an SSR-server frame.** A `:rf.cofx/skipped-on-platform` warning trace fires (carrying `:cofx-id`, `:frame`, `:platform`, `:registered-platforms`, and on the 2-arity form `:cofx-value`); the event handler still runs but reads `nil` for the injected key. Active platform comes from the frame's `:config :platform` (set by the `:ssr-server` preset) falling back to host `interop/platform`. Check this first if a cofx mysteriously doesn't fire under SSR. Spec: `spec/011-SSR.md` §Effect handling on the server.

## Deeper material

Cofx validation (`:spec`), the late-bind seam for `:schemas/validate-cofx!`, full coeffect-map shape: `SKILL-REDIRECT.md` → **EP — Schemas (010)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/cofx.cljc` @ main `9d548e18`. Re-verify line numbers after cofx-chain, schema-validation, or `:platforms`-gate changes.*
