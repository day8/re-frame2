# Effects (fx)

## When to load

Authoring a custom effect handler with `reg-fx`, or working out what to put inside the `:fx` vector returned from a `reg-event` handler.

## Canonical signature

```clojure
(rf/reg-fx :fx-id           handler-fn)
(rf/reg-fx :fx-id metadata? handler-fn)

;; handler-fn :: (fn [ctx args]) -> any (return value ignored)
;;   ctx  :: {:frame <frame-id> :event <originating-event-vector-or-nil>}
;;   args :: whatever the `:fx` entry put in the second slot
```

Verified in `implementation/core/src/re_frame/fx.cljc` (the `reg-fx` fn). Metadata may carry:

- `:doc` — one-sentence what-and-why
- `:schema` — Malli schema for `args` (dev-time validation)
- `:platforms` — `#{:client :server}`; defaults to both. Fx with mismatched platforms emit `:rf.fx/skipped-on-platform` instead of running.

## The fx-map shape

A `reg-event` handler returns a **closed-shape** map — the next state and what to do. The closed set of legal top-level keys is `#{:db :rf.db/runtime :fx}`:

```clojure
{:db            <new-app-db>      ;; app-db partition — the everyday key
 :rf.db/runtime <new-runtime-db>  ;; runtime-db partition — framework-authority (see below)
 :fx [[:fx-id args]
      [:fx-id args]
      ...]}
```

Each `:fx` entry is a 2-vector: `[fx-id args]`. The runtime walks them in source order, synchronously, after the state partitions commit (`fx.cljc:4-9`). Any top-level key **outside** the closed set — `:dispatch`, `:dispatch-later`, `:http`, etc — emits `:rf.error/effect-map-shape` and is dropped (`police-effect-map-shape!` + `closed-effect-map-keys` in `events.cljc`). v2 deliberately removed v1's auto-routed top-level effect keys.

**`:rf.db/runtime` is the one new state-bearing key (EP-0001).** Ordinary app handlers write app data via `:db` and almost never touch it — it targets the runtime-db partition (machine snapshots, route slice, SSR metadata) and is reserved **by convention** for framework-authority writers (SSR hydrate, machines, routing, flows). It is **not** a shape error: a non-framework handler that emits it gets the `:rf.warning/app-handler-runtime-effect` dev diagnostic (it is not dropped). Don't flag a framework/SSR/machine handler's legitimate `:rf.db/runtime` write as illegal.

The reserved fx-ids the core runtime handles directly (`fx.cljc:10-16`):

| fx-id | args | Effect |
|---|---|---|
| `:dispatch` | event-vector | Enqueue at back of frame's router |
| `:dispatch-later` | `{:ms n :event ev}` | Enqueue after `n` ms |
| `:raise` | event-vector | Machine-internal: re-enter the machine locally as a pre-commit microstep in the same macrostep (state-machines leaves) |
| `:rf.fx/reg-flow` / `:rf.fx/clear-flow` | flow spec | (Flows artefact; Spec 013) |

Machine fx-ids (`:rf.machine/spawn`, `:rf.machine/destroy`) ship in `day8/re-frame2-machines`; they register through the same `reg-fx` path when that artefact is loaded.

## Canonical mini-example

From `examples/core/todomvc/events.cljs`:

```clojure
(rf/reg-fx :todo.storage/save
  {:doc       "Persist the TodoMVC items to localStorage."
   :platforms #{:client}}
  (fn fx-todo-storage-save [_ctx todos]
    (when-let [ls (.-localStorage js/globalThis)]
      (->> todos
           vals
           (mapv #(select-keys % [:id :title :completed]))
           (clj->js)
           (js/JSON.stringify)
           (.setItem ls db/ls-key)))))

;; Called via the fx vector:
(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    {:db (assoc-in db [:todos id] {...})
     :fx [[:todo.storage/save (:todos new-db)]]}))
```

## Ordering and atomicity

Per `fx.cljc:4-9`:

1. `:db` commits first, atomically.
2. `:fx` entries process in source order.
3. Each handler runs synchronously before the next entry begins.
4. Subscriptions observe the post-`:db` state.
5. One bad fx (exception, unknown id) traces and the walk continues — does not halt the rest.

## Common gotchas

- **Return shape is closed (`#{:db :rf.db/runtime :fx}`).** Top-level `:dispatch`, `:dispatch-later`, `:http`, etc. are outside the set and dropped with a `:rf.error/effect-map-shape` trace — wrap them inside `:fx`. `:rf.db/runtime` is inside the set (framework-authority runtime-db write), so it is never a shape error.
- **Fx handlers receive `(ctx, args)`, not just `args`.** The first arg is `{:frame ... :event ...}`. Ignore it with `_ctx` if you don't need it.
- **Returning a value from an fx handler does nothing.** Side effects are the point. `:rf.fx/handled` is emitted on success so the epoch projection records the run.
- **`:platforms #{:client}` makes the fx skip silently on server.** A `:rf.fx/skipped-on-platform` warning fires — fine for browser-only side effects, but check this if a fx mysteriously doesn't run under SSR.
- **Fx errors are isolated.** A throw inside one fx emits `:rf.error/fx-handler-exception` but does not abort the `:fx` walk. Don't rely on later entries seeing earlier failures.
- **Override the fx surface per frame**, not globally: `(rf/reg-frame :frame-id {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})`. Used heavily for test stubs and stories (`:fx-overrides` in `reg-frame`'s metadata, `frame.cljc`).

## Deeper material

Per-frame fx overrides, the full reserved fx-id table, `:rf.http/managed`, flows, machine fx-ids: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — HTTP requests (014)**, **EP — Flows (013)**.

---

*Derived from `implementation/core/src/re_frame/fx.cljc`, `implementation/core/src/re_frame/events.cljc`, and `implementation/core/src/re_frame/frame.cljc` @ main `89bd9c3`. Re-verify line numbers after fx-walker or fx-overrides changes.*
