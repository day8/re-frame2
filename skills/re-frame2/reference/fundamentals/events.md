# Events

## When to load

Authoring an event handler — `reg-event-db`, `reg-event-fx`, or `reg-event-ctx` — or working out what the dispatched event vector should look like.

## Canonical signatures

Two surfaces: a CLJ `defmacro` (captures `:ns` / `:line` / `:file`) and a CLJS `def` alias to the underlying fn.

```clojure
(rf/reg-event-db id           handler-fn)                   ;; (db, event) -> new-db
(rf/reg-event-db id metadata? interceptors? handler-fn)

(rf/reg-event-fx id           handler-fn)                   ;; (cofx, event) -> effect-map
(rf/reg-event-fx id metadata? interceptors? handler-fn)

(rf/reg-event-ctx id          handler-fn)                   ;; (context) -> context  (advanced)
```

Verified in `implementation/core/src/re_frame/events.cljc:188-225` (`reg-event-db`, `reg-event-fx`, `reg-event-ctx`) and `implementation/core/src/re_frame/core.cljc:167-224` (the macro layer). `normalise-args` (`events.cljc:171`) accepts:

- `(reg-event-db :id handler)`
- `(reg-event-db :id {:doc "..." :spec ...} handler)`
- `(reg-event-db :id [icpt1 icpt2] handler)`
- `(reg-event-db :id {:doc "..."} [icpt1 icpt2] handler)`

The **metadata-map** is reflective-only (`:doc`, `:spec`, `:tags`, `:platforms`, ...). The **interceptors vector** is positional. Putting `:interceptors` inside the metadata-map silently drops the chain — the runtime emits `:rf.warning/interceptors-in-metadata-map` to flag it (`events.cljc:38`).

## Event vector shape

The dispatched value is a vector `[event-id & args]`. The handler receives the whole vector as its second argument:

```clojure
(rf/reg-event-db :todo/add
  (fn [db [_event-id title]]    ;; underscore the id; destructure args
    (update db :todos conj title)))

(rf/dispatch [:todo/add "buy milk"])
```

Dispatch is non-blocking — events queue and drain run-to-completion. `dispatch-sync` (`router.cljc:390`) drains immediately and is for outside-the-runtime callers (test setup, REPL); calling it from inside a handler raises `:rf.error/dispatch-sync-in-handler`.

## Canonical mini-example

From `examples/reagent/counter/core.cljs`:

```clojure
(rf/reg-event-fx :counter/initialise
  (fn [_ctx _event]
    {:db {:count 5}
     :fx [[:counter/log :initialised]]}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))
```

The `:db` and `:fx` keys are the **only** legal top-level keys in an `fx`-handler return map (see [fx.md](fx.md) for the closed-shape rationale). The `_ctx` parameter is the coeffect map — `{:db <current-db-value> :event <event-vec>}` by default, plus anything `inject-cofx` injected (see [cofx.md](cofx.md)).

## Common gotchas

- **`:dispatch` and `:dispatch-n` are NOT top-level effect keys in v2.** They moved into `:fx` as `[[:dispatch event]]` entries. The runtime emits `:rf.error/effect-map-shape` and drops any non-`:db`/non-`:fx` top-level key (`events.cljc:81`).
- **`:interceptors` is not a metadata key.** Pass the chain as the positional third argument, not as `{:interceptors [...]}`.
- **The event vector's first element is the event id.** Always destructure it as `[_ arg1 arg2]` — the id is in `args` because the whole vector is passed.
- **`reg-event-ctx` is rarely the right tool.** It hands you the raw interceptor context. Use it only when you need to manipulate the chain itself; otherwise `reg-event-db` or `reg-event-fx`.
- **Metadata-map fields surface to tooling, not to the runtime.** `:doc`, `:spec`, `:tags`, `:platforms` are read by Causa, re-frame-pair2, and the dev-time validator. They do not affect runtime behaviour except where called out (`:spec` for dev validation; `:platforms` on `reg-fx`).

## Deeper material

Full effect-map contract, interceptor chain composition, dispatch envelope shape, the dev-time `:spec` validator: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — Schemas (010)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/events.cljc` and `implementation/core/src/re_frame/core.cljc` @ main `89bd9c3`. Re-verify line numbers after substantial registrar / events refactors.*
