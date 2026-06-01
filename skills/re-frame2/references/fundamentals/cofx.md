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

Verified in `implementation/core/src/re_frame/cofx.cljc` (the `reg-cofx` and `inject-cofx` fns). Metadata may carry:

- `:doc` — one-sentence what-and-why
- `:schema` — Malli schema for the injected value (Spec 010 §Validation order step 2)
- `:platforms` — `#{:client :server}`; defaults to both. Cofx with mismatched platforms emit `:rf.cofx/skipped-on-platform` instead of running. Mirrors the `reg-fx` contract per `spec/011-SSR.md` §Effect handling on the server — a client-only cofx (browser locale, localStorage, navigator-info) silently no-ops when injected under an SSR-server frame so it neither blows up under JVM render nor injects nonsense values. The event handler still runs; only the injection is skipped. Example: `^{:platforms #{:client}} (rf/reg-cofx :browser-locale (fn [ctx] (assoc-in ctx [:coeffects :browser-locale] js/navigator.language)))` is safe to register on both platforms; under an `:ssr-server` frame the injection is skipped and the handler sees `nil` for `:browser-locale`.

`inject-cofx` returns an **interceptor** — pass it in the event's interceptors vector (the positional slot, not the metadata-map):

```clojure
(rf/reg-event-fx :id
  [(rf/inject-cofx :now) (rf/inject-cofx :random-id)]   ;; interceptors slot
  (fn [{:keys [db now random-id]} [_ payload]] ...))
```

## Standard cofx

The runtime ships `:db` and `:event` — both are already populated on the context before the chain runs (the standard `:db` / `:event` cofx in `cofx.cljc`), so explicitly injecting them is a no-op. They exist for symmetry with v1. Everything else (current time, browser language, localStorage values, ids) is user-registered.

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

## When `reg-cofx` is overkill — the inline-interceptor escape hatch

`reg-cofx` + `inject-cofx` is the canonical path. It earns its weight by giving the cofx an **id** — and an id is what lets you stub it in tests, hot-rebind it at the REPL (re-registering takes effect on the next dispatch with no event-handler re-registration), enumerate it from devtools (Xray's cofx list), and parameterise it across many call sites (one `:local-store` handler, many keys).

But many cofxes never claim those benefits. A handler that needs a current-millis stamp for one event, defined once in the same module, never stubbed — paying the two-line registry hop just to land a value under `[:coeffects :now]` is ceremony without payoff. For that case, the framework's interceptor primitive (Spec 002 §`:interceptors`, [event-state-cycle](event-state-cycle.md)) is the escape hatch: an inline `{:id ... :before (fn [ctx] (assoc-in ctx [:coeffects k] v))}` map is a legal participant in any event's interceptor vector, with no registry indirection.

### Decision rubric

**Use `reg-cofx` + `inject-cofx` when ANY of:**

- the cofx might be mocked/stubbed in tests by id
- you want hot-rebind at the REPL — re-registering picks up on the next dispatch without re-registering event handlers
- devtools (Xray, re-frame2-pair) should enumerate it
- it's parameterised by id (e.g. `:local-store`-by-key — one handler, many call-site keys)

**Use an inline interceptor map when ALL of:**

- defined once, used in a small set of events, in the same module
- never stubbed in tests
- the cofx body is trivial (a single `assoc-in` typically)

Default to `reg-cofx` for anything that names a generally-useful input (`:now`, `:new-id`, `:local-store`, browser locale, anything cross-cutting). Reach for the inline form only when the registry indirection visibly buys nothing.

### Worked example — both forms

```clojure
;; Registry path (preferred when reuse / stubbing / enumeration matter):
(rf/reg-cofx :now
  {:doc "Inject the current wall-clock time into coeffects under :now."}
  (fn [ctx] (assoc-in ctx [:coeffects :now] (.getTime (js/Date.)))))

(rf/reg-event-fx :ping
  [(rf/inject-cofx :now)]
  (fn [{:keys [db now]} _]
    {:db (assoc db :last-ping now)}))

;; Inline-interceptor path (preferred when ceremony outweighs benefit):
(def ^:private inject-now
  {:id     ::inject-now
   :before (fn [ctx] (assoc-in ctx [:coeffects :now] (.getTime (js/Date.))))})

(rf/reg-event-fx :ping
  [inject-now]
  (fn [{:keys [db now]} _]
    {:db (assoc db :last-ping now)}))
```

Both produce identical runtime behaviour for this event. The trade is per the rubric above — the inline form trades registry-id-addressability (and everything that flows from it) for one fewer indirection.

Design decision: **rf2-bku5r**. The narrative treatment for humans is at [`docs/guide/07-effects-and-coeffects.md`](../../../../docs/guide/07-effects-and-coeffects.md) §When `reg-cofx` is overkill.

## Why coeffects instead of `(.-localStorage ...)` in the handler?

Pure handlers are testable, replayable (for re-frame2-pair epoch restore), and serialisable (for SSR snapshots). A handler that reads `Date.now()` directly is non-deterministic; the same handler that destructures `now` from coeffects is a pure function of its inputs.

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

There is deliberately **no `cofx-from-sub` shortcut helper** in `re-frame.core`. The five-line `reg-cofx` wrapper above is the canonical shape; collapsing it into a one-liner would imply that subscribing-inside-handlers is the rule and the wrap is the workaround, when it is the other way around.

Narrative treatment of the same pattern (for humans): [`docs/guide/07-effects-and-coeffects.md`](../../../../docs/guide/07-effects-and-coeffects.md) §Reading a sub from a handler.

## Common gotchas

- **`inject-cofx` returns an interceptor, not the value.** It must go in the positional interceptors vector, not the metadata-map (the metadata-map's `:interceptors` key is silently dropped — see [events.md](events.md)).
- **The injected key convention is the cofx-id itself.** Stash under `[:coeffects :my/cofx-id]` so destructuring with `{:my/keys [...]}` works cleanly. If `:schema` validation is declared on the cofx metadata, the validator looks up under the cofx-id key (`maybe-validate-cofx!` in `cofx.cljc`).
- **Order matters.** Interceptors run in vector order; a cofx that depends on another cofx's value must come after it.
- **Two-arg form is for parameterised injection.** Use `(inject-cofx :random-int max-value)` when the same cofx-id needs a different value per attachment.
- **Missing registration is a structured error trace, not a throw.** `inject-cofx` of an unregistered id emits `:rf.error/no-such-cofx` (carrying `:cofx-id`, `:event-id`, and the optional 2-arity `:cofx-value`) and lets the ctx flow through unchanged (`cofx.cljc:~78,88`). Subscribe via `register-listener!` (or watch through Xray / re-frame2-pair) to surface these.
- **`:platforms #{:client}` makes the cofx skip silently under an SSR-server frame.** A `:rf.cofx/skipped-on-platform` warning trace fires (carrying `:cofx-id`, `:frame`, `:platform`, `:registered-platforms`, and on the 2-arity form `:cofx-value`); the event handler still runs but reads `nil` for the injected key. Active platform comes from the frame's `:config :platform` (set by the `:ssr-server` preset) falling back to the host-wide marker `(interop/active-platform)` (settable at boot via `(rf/init-platform :server|:client)`). Check this first if a cofx mysteriously doesn't fire under SSR. Spec: `spec/011-SSR.md` §Effect handling on the server.

## Deeper material

Cofx validation (`:schema`), the late-bind seam for `:schemas/validate-cofx!`, full coeffect-map shape: `SKILL-REDIRECT.md` → **EP — Schemas (010)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/cofx.cljc` @ main `9d548e18`. Citations are symbol-level; re-verify symbol homes after cofx-chain, schema-validation, or `:platforms`-gate changes.*
