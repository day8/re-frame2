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

Verified in `implementation/core/src/re_frame/cofx.cljc:44` (`reg-cofx`) and `cofx.cljc:57` (`inject-cofx`).

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

## Common gotchas

- **`inject-cofx` returns an interceptor, not the value.** It must go in the positional interceptors vector, not the metadata-map (the metadata-map's `:interceptors` key is silently dropped — see [events.md](events.md)).
- **The injected key convention is the cofx-id itself.** Stash under `[:coeffects :my/cofx-id]` so destructuring with `{:my/keys [...]}` works cleanly. If `:spec` validation is declared on the cofx metadata, the validator looks up under the cofx-id key (`cofx.cljc:33-36`).
- **Order matters.** Interceptors run in vector order; a cofx that depends on another cofx's value must come after it.
- **Two-arg form is for parameterised injection.** Use `(inject-cofx :random-int max-value)` when the same cofx-id needs a different value per attachment.
- **Missing registration is a runtime warning, not a throw.** `inject-cofx` of an unregistered id prints `re-frame2: no cofx registered for ...` and continues (`cofx.cljc:78`). Easy to miss — grep for the cofx-id in your registrations on `:rf.error/no-such-fx`-style surprises.

## Deeper material

Cofx validation (`:spec`), the late-bind seam for `:schemas/validate-cofx!`, full coeffect-map shape: `SKILL-REDIRECT.md` → **EP — Schemas (010)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/cofx.cljc` @ main `89bd9c3`. Re-verify line numbers after cofx-chain or schema-validation changes.*
