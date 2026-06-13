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

Verified in `implementation/core/src/re_frame/events.cljc` (`reg-event-db` / `reg-event-fx` / `reg-event-ctx` fns) and `implementation/core/src/re_frame/core.cljc` (the macro layer). The `normalise-args` helper accepts:

- `(reg-event-db :id handler)`
- `(reg-event-db :id {:doc "..." :schema ...} handler)`
- `(reg-event-db :id {:doc "..." :interceptors [icpt1 icpt2]} handler)`  ← the superset form
- `(reg-event-db :id [icpt1 icpt2] handler)`  ← sugar for `{:interceptors [icpt1 icpt2]}`
- `(reg-event-db :id {:doc "..."} [icpt1 icpt2] handler)`

The **metadata-map** is the one superset middle slot: reflection keys (`:doc`, `:schema`, `:tags`, `:platforms`, ...) **plus** a reserved `:interceptors` key. The positional **interceptors vector** is sugar for `{:interceptors [...]}` (`[i1 i2]` ≡ `{:interceptors [i1 i2]}`, identical semantics). Supplying interceptors in **both** slots at once is a loud `:rf.error/interceptors-supplied-twice` (one home per fact); a malformed `:interceptors` value is `:rf.error/reg-event-bad-interceptors`.

## Event vector shape

The dispatched value is a vector `[event-id & args]`. The handler receives the whole vector as its second argument:

```clojure
(rf/reg-event-db :todo/add
  (fn [db [_event-id title]]    ;; underscore the id; destructure args
    (update db :todos conj title)))

(rf/dispatch [:todo/add "buy milk"])
```

Dispatch is non-blocking — events queue and drain run-to-completion. `dispatch-sync` (`dispatch-sync!` in `router.cljc`) drains immediately and is for outside-the-runtime callers (test setup, REPL); calling it from inside a handler raises `:rf.error/dispatch-sync-in-handler`.

## Canonical mini-example

From `examples/reagent/counter/core.cljs` — the simplest shape, `reg-event-db`:

```clojure
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))
```

`reg-event-db` returns the new `app-db` directly. When a handler also needs to fire effects, use `reg-event-fx`, which returns an **effect map**. From `examples/reagent/todomvc/events.cljs`, an `fx`-handler that commits `:db` and persists via an fx:

```clojure
(rf/reg-event-fx :todo/delete
  (fn [{:keys [db]} [_ id]]
    (let [next-db (update db :todos dissoc id)]
      {:db next-db
       :fx [[:todo.storage/save (:todos next-db)]]})))
```

The effect map is a **closed shape**: the only legal top-level keys are `:db` (app-db partition), `:rf.db/runtime` (runtime-db partition), and `:fx` (see [fx.md](fx.md) for the rationale). Ordinary app handlers use `:db` and `:fx`; `:rf.db/runtime` is the framework-authority partition (EP-0001) — a non-framework handler that emits it gets a `:rf.warning/app-handler-runtime-effect` dev diagnostic but the write is **not** dropped (it is legal, just framework-reserved by convention). The coeffect first argument is the coeffects map. Its base is the always-staged framework coeffects — `:db` (current app-db value) and `:event` (the event vector), plus `:rf.db/runtime` (the runtime-db partition), `:rf.frame/id` (the runtime-context frame stamp), and `:rf.cofx` (the whole flat recordable-coeffect map). On top of that base, the runtime delivers **exactly the user facts the handler declares** in `:rf.cofx/requires`, flat under their ids. So declared-only delivery governs *user* leaves; the base framework coeffects are always present (and `:rf.cofx` is filtered out of the Xray COEFFECTS lens, which shows only declared leaves). EP-0017 — `inject-cofx` is removed; see [cofx.md](cofx.md).

## Common gotchas

- **`:dispatch` and `:dispatch-n` are NOT top-level effect keys in v2.** They moved into `:fx` as `[[:dispatch event]]` entries. The runtime emits `:rf.error/effect-map-shape` and drops any top-level key outside the closed set `#{:db :rf.db/runtime :fx}` (`police-effect-map-shape!` + `closed-effect-map-keys` in `events.cljc`). Note `:rf.db/runtime` is **inside** the closed set — it is the framework-authority runtime-db partition, not a shape error.
- **`:interceptors` is not a metadata key.** Pass the chain as the positional third argument, not as `{:interceptors [...]}`.
- **The event vector's first element is the event id.** Always destructure it as `[_ arg1 arg2]` — the id is in `args` because the whole vector is passed.
- **`reg-event-ctx` is rarely the right tool.** It hands you the raw interceptor context. Use it only when you need to manipulate the chain itself; otherwise `reg-event-db` or `reg-event-fx`.
- **Metadata-map fields surface to tooling, not to the runtime.** `:doc`, `:schema`, `:tags`, `:platforms` are read by Xray, re-frame2-pair, and the dev-time validator. They do not affect runtime behaviour except where called out (`:schema` for dev validation; `:platforms` on `reg-fx`).

## Deeper material

Full effect-map contract, interceptor chain composition, dispatch envelope shape, the dev-time `:schema` validator: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — Schemas (010)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/events.cljc` and `implementation/core/src/re_frame/core.cljc` @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after substantial registrar / events refactors.*
