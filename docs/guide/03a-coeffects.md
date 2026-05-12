# 03a — Coeffects

Chapter 03 set up the symmetry that re-frame2 leans on: a handler is a pure function from inputs to outputs. The outputs are an **effect map** — `:db`, `:fx`, and friends — describing what the runtime should do next. This chapter is about the matching half on the way in: the **coeffects map**.

Coeffects are the *side-causes*. They're the data a handler reads from the world that isn't `app-db` — the current wall-clock time, a freshly-generated UUID, a value retrieved from `localStorage`, the browser's preferred language. Just as effects keep the handler from *performing* side-effects, coeffects keep the handler from *causing* them — no `(js/Date.)` in the handler body, no `(.getItem js/localStorage ...)`, no `(random-uuid)`. Inputs arrive in the coeffects map; the handler stays a function.

You'll meet this surface the moment an event needs to stamp something with the current time, persist with a new id, or seed itself from a previously-saved value. If you've already seen `(inject-cofx :now)` or `(inject-cofx :todo.storage/todos)` in someone else's code and wondered what it was doing, this chapter is the answer.

It's optional like 04a and 05a — a side-track between 03 and 04. The core path doesn't require writing your own coeffect; `:db` and `:event` are wired in automatically. Pick this up the first time you want a handler to read something the runtime hasn't already put under its nose.

## The side-cause

Re-read the `reg-event-fx` shape from chapter 03 and the first argument changes meaning:

```clojure
(rf/reg-event-fx :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :count inc)}))
```

The handler destructures `:db` out of its first argument. That first argument *is* the coeffects map. Chapter 03 didn't dwell on it because every handler so far has only needed `:db` and `:event`, both of which the runtime stages automatically:

| Coeffect | What it is | Set by |
|---|---|---|
| `:db` | The frame's `app-db` value at drain start. | The drain loop, before the interceptor chain runs. |
| `:event` | The dispatched event vector. | The dispatch envelope. |

These two are always present; you can rely on them being there. Everything beyond `:db` and `:event` — every other piece of "data from outside the handler" — is opt-in, named, and injected by a small piece of machinery: a registered **cofx**, fetched into the coeffects map by an **`inject-cofx`** interceptor.

The symmetry with effects is exact:

| | Inputs | Outputs |
|---|---|---|
| **Where** | `:coeffects` | `:effects` |
| **Built-in** | `:db`, `:event` | `:db` |
| **Registered** | `reg-cofx` | `reg-fx` |
| **Identified by** | keyword id | keyword id |
| **Side-effecty work happens in** | the cofx handler | the fx handler |

A handler reads from `:coeffects`. It writes to `:effects`. The runtime fills `:coeffects` before the handler runs (via cofx handlers); the runtime drains `:effects` after the handler returns (via fx handlers). The handler in the middle stays pure.

## Why a registry, not a function call

The easy thing — and the wrong thing — is to call `(js/Date.)` directly in the handler:

```clojure
;; ❌ Don't do this
(rf/reg-event-db :todo/add
  (fn [db [_ title]]
    (let [now (js/Date.)
          id  (random-uuid)]
      (assoc-in db [:todos id] {:id id :title title :created-at now}))))
```

Three things are wrong with it, and they're the same three things that were wrong with calling `js/fetch` from a handler in chapter 03:

**The handler isn't pure.** Same inputs no longer produce the same outputs. Calling the handler twice with `({} [:todo/add "buy milk"])` gives a different `:created-at` and a different `:id` each time. The test framework can't pin a value down without monkey-patching `js/Date` and `random-uuid` globally.

**The boundary leaks into the body.** `js/Date` exists in the browser; on the JVM (where you want this handler's tests to run, per [chapter 10](10-testing.md)) it doesn't. Now the handler can't be tested without a CLJS runtime, even though the logic it expresses — "stamp this new todo with a creation time" — is host-neutral.

**There's no override surface.** You can't, for one event, ask "what does the handler do if the time is fixed at noon on January 1st, 2026?" without reaching into `js/Date` itself — every other handler in the same test run gets the same redefinition, and tearing it back down is fiddly.

Cofx fix all three by extracting the impurity into a registered handler with a name:

```clojure
(rf/reg-cofx :now
  (fn [ctx]
    (assoc-in ctx [:coeffects :now] (js/Date.))))

(rf/reg-cofx :new-id
  (fn [ctx]
    (assoc-in ctx [:coeffects :new-id] (random-uuid))))

(rf/reg-event-fx :todo/add
  [(rf/inject-cofx :now)
   (rf/inject-cofx :new-id)]
  (fn [{:keys [db now new-id]} [_ title]]
    {:db (assoc-in db [:todos new-id]
                   {:id new-id :title title :created-at now})}))
```

The handler is back to being a pure function. The two impure calls (`js/Date.`, `random-uuid`) are quarantined inside two named cofx handlers. Each can be overridden by re-registering against the same id — which is the whole testing story, [below](#testing-via-cofx-stubs).

## `reg-cofx` — registering an input

A cofx handler is a function from context to context. It receives the full context map (the same one interceptors thread, per [chapter 04a](04a-interceptors.md)) and returns a new context with the cofx value `assoc-in`'d under `[:coeffects <id>]`:

```clojure
(rf/reg-cofx :now
  {:doc "Inject the current wall-clock time into coeffects under :now."}
  (fn [ctx]
    (assoc-in ctx [:coeffects :now] (js/Date.))))
```

The convention is to inject under the same keyword you registered with — the cofx id and the coeffect key are the same keyword. The handler is free to inject under a different key, but tooling and validation assume the symmetric case, so deviate only with reason.

`reg-cofx` takes an optional metadata map between the id and the handler — the same shape every other `reg-*` accepts (per [Spec 001](../../spec/001-Registration.md)). `:doc` is the most common metadata key. `:spec` attaches a Malli schema that the runtime validates the injected value against ([Spec 010](../../spec/010-Schemas.md)) — a `:now` cofx with `{:spec [:fn inst?]}` will fail fast if some test stub forgets to return a date.

Two arities exist for the handler fn:

- **Unary** — `(fn [ctx] ...)`. Used when the cofx is parameterless. `:now`, `:new-id`, the TodoMVC `:todo.storage/todos` cofx, the `:browser/lang` cofx — anything where "there's only ever one answer."
- **Binary** — `(fn [ctx value] ...)`. Used when the cofx is parameterised by a value supplied at the `inject-cofx` call site. The classic example is `:local-store`, which takes the storage key to read from:

```clojure
(rf/reg-cofx :local-store
  (fn [ctx storage-key]
    (assoc-in ctx [:coeffects :local-store]
              (some-> (.-localStorage js/globalThis)
                      (.getItem storage-key)))))

;; Used:
(rf/reg-event-fx :prefs/load
  [(rf/inject-cofx :local-store "user-prefs")]
  (fn [{:keys [local-store]} _]
    {:db (-> (or local-store {}) (parse-prefs))}))
```

The binary form keeps the *what* (the registered cofx) generic and the *which* (the storage key, the bucket name, the page size) at the call site. One `:local-store` cofx handler serves every event in the app that needs a different key.

## `inject-cofx` — the interceptor

A cofx is registered once and used many times. The use-site is `inject-cofx`, the small interceptor that runs the registered cofx fn on the way in:

```clojure
(rf/reg-event-fx :todo/add
  [(rf/inject-cofx :now)]       ;; ← the interceptors slot from ch.03
  (fn [{:keys [db now]} [_ title]]
    ...))
```

`(inject-cofx :now)` returns an interceptor with a `:before` slot only — the cofx is an input, so there's nothing to do on the way out. The `:before` looks up `:now` in the `:cofx` registry, calls its handler with the context (and the second value, if `inject-cofx` was called binary), and the resulting context — now carrying `:now` under `:coeffects` — flows into the next interceptor and eventually the handler.

Two arities, matching `reg-cofx`:

- `(inject-cofx :id)` — no value. The cofx fn is called as `(handler ctx)`.
- `(inject-cofx :id value)` — passes `value` through. The cofx fn is called as `(handler ctx value)`.

Multiple cofx interceptors compose by listing them in source order:

```clojure
(rf/reg-event-fx :todo/add
  [(rf/inject-cofx :now)
   (rf/inject-cofx :new-id)
   (rf/inject-cofx :local-store "draft-todo")]
  (fn [{:keys [db now new-id local-store]} [_ title]]
    {:db (assoc-in db [:todos new-id]
                   {:id         new-id
                    :title      title
                    :created-at now
                    :seeded?    (some? local-store)})}))
```

Each cofx is independent. They run on the way in, in declaration order; by the time the handler runs, all four keys (`:db`, `:event`, `:now`, `:new-id`, `:local-store`) are present in its coeffects map.

A handler shape note worth pinning: **`reg-event-db` doesn't see injected cofx values.** Its handler signature is `(fn [db event] ...)`, not `(fn [coeffects event] ...)` — only `:db` is destructured. If you need a cofx value, the event registers under `reg-event-fx` (or `reg-event-ctx` for the rare cases that want the whole context map). The interceptors slot still works on `reg-event-db` for other purposes — a logger, an undo wrapper — but `inject-cofx` is wasted on it.

## Common cofxes

There's no closed list — every app registers the cofxes it needs. A few recur often enough to be worth naming.

### `:now`

The current wall-clock time. Useful for `:created-at` / `:updated-at` stamps, timeout calculations, age-based decisions:

```clojure
(rf/reg-cofx :now
  (fn [ctx]
    (assoc-in ctx [:coeffects :now] (js/Date.))))
```

A test that needs deterministic time re-registers it — see [below](#testing-via-cofx-stubs).

### `:new-id` / `:guid`

A freshly-generated UUID for a new entity:

```clojure
(rf/reg-cofx :new-id
  (fn [ctx]
    (assoc-in ctx [:coeffects :new-id] (random-uuid))))
```

In tests, re-register to return a deterministic id (`#uuid "00000000-0000-0000-0000-000000000001"`) so assertions don't have to fish through generated values.

### `:random-int`

A bounded random integer, parameterised by the upper bound:

```clojure
(rf/reg-cofx :random-int
  (fn [ctx upper-bound]
    (assoc-in ctx [:coeffects :random-int] (rand-int upper-bound))))

;; (rf/inject-cofx :random-int 10)
```

### `:local-store`

Read a value out of `localStorage` by key. TodoMVC's `:todo.storage/todos` cofx is exactly this shape, scoped to one project-specific key:

```clojure
(rf/reg-cofx :todo.storage/todos
  {:doc "Inject the saved TodoMVC items from localStorage into coeffects."}
  (fn [ctx]
    (assoc-in ctx [:coeffects :todo.storage/todos]
              (some-> (.-localStorage js/globalThis)
                      (.getItem "todos-reframe2")
                      (parse-todos)))))

(rf/reg-event-fx :todo/initialise
  [(rf/inject-cofx :todo.storage/todos)]
  (fn [{:todo.storage/keys [todos]} _]
    {:db (assoc default-db :todos todos)}))
```

`examples/reagent/todomvc/db.cljs` and its sibling `events.cljs` exercise the pattern end-to-end. The `:initialise` handler stays pure; the only place `localStorage.getItem` appears in the codebase is inside `reg-cofx`. SSR — where there's no `localStorage` — gets a `nil` for the cofx value because the `some->` short-circuits; the handler degrades gracefully without branching on platform.

## Testing via cofx stubs

This is the payoff. A handler that uses `(inject-cofx :now)` is testable without faking `js/Date`, mocking the clock, or threading a `Clock` argument through every call site. The test re-registers `:now` with a stub before the handler-under-test runs, and the framework's id-redirect picks up the new binding:

```clojure
(ns my-app.todo-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]))

(deftest todo-add-stamps-created-at
  (ts/with-fresh-registrar
    ;; Stub the :now cofx to return a fixed instant.
    (rf/reg-cofx :now
      (fn [ctx]
        (assoc-in ctx [:coeffects :now] #inst "2026-01-01T12:00:00.000Z")))
    (rf/reg-cofx :new-id
      (fn [ctx]
        (assoc-in ctx [:coeffects :new-id] #uuid "00000000-0000-0000-0000-000000000001")))

    (rf/with-frame [f (rf/make-frame {})]
      (rf/dispatch-sync [:todo/add "buy milk"])
      (let [todo (-> (rf/get-frame-db f)
                     :todos
                     (get #uuid "00000000-0000-0000-0000-000000000001"))]
        (is (= "buy milk" (:title todo)))
        (is (= #inst "2026-01-01T12:00:00.000Z" (:created-at todo)))))))
```

Three things to notice:

**The stubs are re-registrations, not mocks.** They live in the same registry as the production cofx handlers; they're addressed by the same keyword id. `inject-cofx` finds the re-registered version with no special test-mode flag. Per-frame and per-call overrides go further still — [Spec 002 §Per-frame and per-call overrides](../../spec/002-Frames.md#per-frame-and-per-call-overrides) covers `:interceptor-overrides` for stubbing the *interceptor itself* (e.g. swapping `:rf/inject-cofx-now` for one frame's events), useful when you want the stub scoped to one frame rather than to the whole test registry.

**`with-fresh-registrar` keeps the stubs scoped.** It snapshots the registrar around the body and restores on exit — production `:now` is intact for the next test. Without it, a test that re-registers `:now` leaves a stub in place for whatever runs next, which is the classic "passes alone, fails together" failure mode covered in [chapter 10 §Registrar isolation](10-testing.md#registrar-isolation-with-fresh-registrar).

**The handler-under-test never knew it was being tested.** It dispatched against a frame, asked for `:now` through `inject-cofx`, got back a fixed instant. No conditional in the handler body. No `if-test?` flag. The handler is the same shape in production and in the test; only the injected value changed.

This idiom generalises: any handler that depends on the outside world via cofx can be deterministically tested by re-registering its cofxes. Time becomes fixed, ids become predictable, `localStorage` returns whatever the test wants. The handler stays a function from values to values.

## Where the seams sit

A short orientation map for everything cofx-related you'll see in re-frame2 code:

- **The cofx fn shape.** `(fn [ctx])` or `(fn [ctx value])`, returning the ctx with `[:coeffects <id>]` assoc'd. The handler runs as an interceptor's `:before`; the [interceptor chapter](04a-interceptors.md) is the deep-dive on why that machinery exists.
- **The handler's view.** `reg-event-fx`'s first argument is the coeffects map. Destructure `:db`, `:event`, and any injected cofx keys you registered.
- **`reg-event-db` is unaffected.** `:db` and the event vector only; injected cofxes aren't visible. Promote to `reg-event-fx` if you need them.
- **`reg-event-ctx`** is the rare third surface — handler receives the full context map (both `:coeffects` and `:effects`). Most cofx-using code doesn't need it; reach for it when you genuinely want to reshape the context itself.

The forward link from [chapter 04a's table](04a-interceptors.md#the-context-map) points here: the `:coeffects` half of the context map is where every input lives, and `inject-cofx` is the canonical way to put something new in it.

## What we covered

- A coeffect is a *side-cause* — data the handler reads from the world that isn't `app-db`. It's the symmetric twin of an effect.
- `:db` and `:event` are wired in automatically. Everything else is registered with `reg-cofx` and pulled in with `(inject-cofx :id)` in the handler's interceptors slot.
- `reg-cofx` registers a cofx handler that takes the context (and optionally a value) and returns the context with the cofx value injected under `[:coeffects <id>]`.
- Multiple cofxes compose by listing multiple `inject-cofx` interceptors in source order.
- `reg-event-db` doesn't see injected cofx values; use `reg-event-fx` for any handler that needs them.
- Testing is by re-registration — stub `:now` to return a fixed instant, scope it with `with-fresh-registrar`, and the handler-under-test becomes deterministic.

## Next

- [04 — Views and frames](04-views-and-frames.md) — back to the core path: what's on the screen and how to keep different parts of the app isolated.
- [04a — Interceptors](04a-interceptors.md) — the wrapping primitive `inject-cofx` is built on. Read this if you want to write a custom interceptor that's *not* a cofx (a logger, an undo wrapper, a recorder).
- [10 — Testing](10-testing.md) — the registrar-isolation story (`with-fresh-registrar`, `reset-runtime-fixture`) and the per-frame / per-call override surface that complements cofx re-registration.
- [Spec 002 §Per-frame and per-call overrides](../../spec/002-Frames.md#per-frame-and-per-call-overrides) — the normative surface for `:interceptor-overrides`, including the `:rf/inject-cofx-now` override pattern.
