# Events

## When to load

Authoring an event handler with `reg-event` — or working out what the dispatched event vector should look like.

## The one mental model

re-frame's event model is **coeffects in, effects out**: a handler reads the facts it is given and returns a description of what should happen. There is **one** public event-registration form, `reg-event`. The db write is one effect among others — `{:db ...}` — not a special return shape. (EP-0018.)

```clojure
(rf/reg-event id           handler)
(rf/reg-event id metadata? handler)

;; handler :: (fn [coeffects event-vec] effects-map-or-nil)
```

- `coeffects` is the coeffects map (current `:db`, the `:event` vector, and any declared `:rf.cofx/requires` facts — see [cofx.md](cofx.md)).
- The return is the **closed effects map**, or `nil` for a no-op.
- The second argument is the event vector; `(:event coeffects)` is the same value. Handlers that don't read it use `_`.

Verified in `implementation/core/src/re_frame/events.cljc` (the `reg-event` fn) and `implementation/core/src/re_frame/core.cljc` (the macro layer). The `normalise-args` helper accepts:

- `(reg-event :id handler)`
- `(reg-event :id {:doc "..." :schema ...} handler)`
- `(reg-event :id {:doc "..." :interceptors [:icpt-ref1 :icpt-ref2]} handler)`  ← the superset form

The **metadata-map** is the one superset middle slot: reflection keys (`:doc`, `:schema`, `:tags`, `:platforms`, `:rf.cofx/requires`, ...) **plus** a reserved `:interceptors` key. The historical positional interceptor vector is retired: `(reg-event :id [i1 i2] handler)` now throws `:rf.error/reg-event-bad-middle-slot`, and `(reg-event :id {:doc "..."} [i1 i2] handler)` throws `:rf.error/reg-event-bad-arity`. A malformed `:interceptors` value is `:rf.error/reg-event-bad-interceptors`. The `:interceptors` vector carries **interceptor references** — a bare keyword id (`:auth/required`) or an `[id arg]` factory ref (`[:rf.interceptor/path [:cart]]`) — never inline interceptor maps or values; an inline value in a public chain is `:rf.error/inline-interceptor-removed`. Register the behaviour once with `reg-interceptor`, then reference it by id (see [Interceptors as named program members](#interceptors-as-named-program-members) below).

## Event vector shape

The dispatched value is a vector `[event-id & args]`. The handler receives the whole vector as its second argument:

```clojure
(rf/reg-event :todo/add
  (fn [{:keys [db]} [_event-id title]]   ;; underscore the id; destructure args
    {:db (update db :todos conj title)}))

(rf/dispatch [:todo/add "buy milk"])
```

Dispatch is non-blocking — events queue and drain run-to-completion. `dispatch-sync` (`dispatch-sync!` in `router.cljc`) drains immediately and is for outside-the-runtime callers (test setup, REPL); calling it from inside a handler raises `:rf.error/dispatch-sync-in-handler`.

## Canonical mini-example

The simplest shape — a handler whose only effect is a db write returns `{:db ...}`:

```clojure
(rf/reg-event :counter/initialise
  (fn [_cofx _event] {:db {:counter/value 5}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))
```

Read `{:db ...}` positively: **an event returns the next state and what to do.** The next state is the `:db` effect; "what to do" is everything else (the `:fx` vector). A handler that grows from "just a db write" to "a db write plus an effect" only adds a key — no signature change, no conversion.

When a handler also needs to fire effects, add `:fx`. A handler that commits `:db` and persists via an fx:

```clojure
(rf/reg-event :todo/delete
  (fn [{:keys [db]} [_ id]]
    (let [next-db (update db :todos dissoc id)]
      {:db next-db
       :fx [[:todo.storage/save (:todos next-db)]]})))
```

The effect map is a **closed shape**: the only legal top-level keys are `:db` (app-db partition), `:rf.db/runtime` (runtime-db partition), and `:fx` (see [fx.md](fx.md) for the rationale). Ordinary app handlers use `:db` and `:fx`; `:rf.db/runtime` is the framework-authority partition (EP-0001) — a non-framework handler that emits it gets a `:rf.warning/app-handler-runtime-effect` dev diagnostic but the write is **not** dropped (it is legal, just framework-reserved by convention). The coeffect first argument is the coeffects map. Its base is the always-staged framework coeffects — `:db` (current app-db value) and `:event` (the event vector), plus `:rf.db/runtime` (the runtime-db partition), `:rf.frame/id` (the runtime-context frame stamp), and `:rf.cofx` (the whole flat recordable-coeffect map). On top of that base, the runtime delivers **exactly the user facts the handler declares** in `:rf.cofx/requires`, flat under their ids. So declared-only delivery governs *user* leaves; the base framework coeffects are always present (and `:rf.cofx` is filtered out of the Xray COEFFECTS lens, which shows only declared leaves). EP-0017 — `inject-cofx` is removed; see [cofx.md](cofx.md).

## Keep "events are pure functions of state" teachable — extract a pure helper

The wrap is thin, but the *state transition itself* should stay a plain function: extract it and call it inside the handler. The pure fn is the testable, composable core; the `reg-event` wrapper is the thin shell that names the event and states the effect.

```clojure
;; the state transition — a plain, bare-callable, unit-testable fn
(defn inc-counter [db] (update db :counter/value inc))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _] {:db (inc-counter db)}))
```

Test `inc-counter` directly with no runtime; the handler stays a one-liner. This keeps the "an event is a pure function of state" model intact while every handler speaks the uniform coeffects-in/effects-out shape.

## Interceptors as named registered members

Full-context work — rewriting coeffects, replacing the event, skipping the handler, adding or removing effects, focusing `:db` on a slice — lives in **interceptors**. An interceptor is load-bearing structure, so it is a **registered, named** member with the same properties as every other `reg-*` member: an id, source coordinates, metadata, hot-reload behaviour, and trace/Xray visibility. Register it once with `reg-interceptor`, then reference it by id from a chain.

```clojure
;; Register the behaviour once — it has a name, a doc, and (optionally) :before / :after.
(rf/reg-interceptor :auth/required
  {:doc "Require a logged-in user; short-circuit to the login flow otherwise."}
  {:before require-auth})

;; Reference it by id in any event's :interceptors chain.
(rf/reg-event :cart/add
  {:interceptors [:auth/required
                  [:rf.interceptor/path [:cart]]]}
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))         ;; db here IS the [:cart] slice
```

Two reference shapes:

- a **bare keyword** id (`:auth/required`) — references a static registered interceptor;
- an **`[id arg]` vector** (`[:rf.interceptor/path [:cart]]`) — references a parameterized interceptor **factory** with exactly one EDN-serializable argument.

Inline interceptor maps, values, or Vars in a public chain are a registration error — `:rf.error/inline-interceptor-removed`; the recovery is to register the behaviour and reference it by id. `->interceptor` is **not** the public authoring form (the framework keeps an internal lowering constructor only). Because chains carry serializable refs, an event's interceptor wiring prints, diffs, moves through a story, rides in an image's selected registration set, and is overridable by exact reference — `{:interceptor-overrides {:auth/required :story/skip-auth, [:rf.interceptor/path [:cart]] nil}}` swaps or removes a named ref per-frame or per-dispatch.

**Standard `[:rf.interceptor/path path-vector]`** is the one framework-standard interceptor. It focuses the handler's `:db` coeffect onto the named app-db slice and re-widens the returned slice afterwards, so the handler reads and returns slice-relative state. It preserves the frame-commit `identical?` no-op: a path-focused handler that returns its slice unchanged still commits as a no-op. There is no public `rf/path` value constructor — the chain language is uniform keywords and `[id arg]` refs.

**Frame-level `:interceptors`** is the "global within this frame" mechanism: `(rf/reg-frame :dev/main {:interceptors [:dev/record-events]})` applies the referenced behaviour to every event dispatched in that frame.

## Common gotchas

- **`:dispatch` and `:dispatch-n` are NOT top-level effect keys in v2.** They moved into `:fx` as `[[:dispatch event]]` entries. The runtime emits `:rf.error/effect-map-shape` and drops any top-level key outside the closed set `#{:db :rf.db/runtime :fx}` (`police-effect-map-shape!` + `closed-effect-map-keys` in `events.cljc`). Note `:rf.db/runtime` is **inside** the closed set — it is the framework-authority runtime-db partition, not a shape error.
- **A db-only handler still returns a map.** The next app-db is the `:db` effect: `{:db new-db}`, never a bare `new-db`. A bare map return that isn't the effects map is a shape error.
- **`nil` / `{}` is the no-op return.** Returning `nil` or `{}` commits nothing. Use this (or `{:db db}` — the unchanged db `identical?`-short-circuits to a no-op) for the "I decided not to change anything" branch; you don't pay for the `else` arm.
- **`:interceptors` lives in event metadata, and carries refs.** Put per-event chains in the metadata map: `{:interceptors [:icpt-ref1 :icpt-ref2]}`. The old positional vector middle slot is rejected, and chain entries are **references** (a bare keyword id, or an `[id arg]` factory ref), never inline interceptor values. The standard `[:rf.interceptor/path [:cart]]` ref focuses the `:db` coeffect on a slice and reinserts it after, so the handler returns `{:db slice}`.
- **The event vector's first element is the event id.** Always destructure it as `[_ arg1 arg2]` — the id is in `args` because the whole vector is passed.
- **Full-context work is a *registered* interceptor, not a special handler.** There is no public `reg-event-ctx`. When you need to manipulate the interceptor context itself (capture, short-circuit via `:rf/skip-handler?`, install an effect directly), register the behaviour with `(rf/reg-interceptor :my/icpt {:doc "..."} {:before ... :after ...})` and reference it by id under `:interceptors` — interceptors are the public `context -> context` primitive, and they are named program members, not anonymous values. (`->interceptor` is not the public authoring form; `reg-interceptor` is.)
- **Metadata-map fields surface to tooling, not to the runtime.** `:doc`, `:schema`, `:tags`, `:platforms` are read by Xray, re-frame2-pair, and the dev-time validator. They do not affect runtime behaviour except where called out (`:schema` for dev validation; `:platforms` on `reg-fx`).

## Deeper material

Full effect-map contract, interceptor chain composition, dispatch envelope shape, the dev-time `:schema` validator: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — Schemas (010)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/events.cljc` and `implementation/core/src/re_frame/core.cljc`; the one-form event model is EP-0018. Citations are symbol-level; re-verify symbol homes after substantial registrar / events refactors.*
