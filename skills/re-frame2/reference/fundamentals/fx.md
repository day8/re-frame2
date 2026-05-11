# Effects (fx)

## When to load

Authoring a custom effect handler with `reg-fx`, or working out what to put inside the `:fx` vector returned from `reg-event-fx`.

## Canonical signature

```clojure
(rf/reg-fx :fx-id           handler-fn)
(rf/reg-fx :fx-id metadata? handler-fn)

;; handler-fn :: (fn [ctx args]) -> any (return value ignored)
;;   ctx  :: {:frame <frame-id> :event <originating-event-vector-or-nil>}
;;   args :: whatever the `:fx` entry put in the second slot
```

Verified in `implementation/core/src/re_frame/fx.cljc:33`. Metadata may carry:

- `:doc` — one-sentence what-and-why
- `:spec` — Malli schema for `args` (dev-time validation)
- `:platforms` — `#{:client :server}`; defaults to both. Fx with mismatched platforms emit `:rf.fx/skipped-on-platform` instead of running.

## The fx-map shape

`reg-event-fx` handlers return a **closed-shape** map. Only two top-level keys are legal:

```clojure
{:db <new-db>
 :fx [[:fx-id args]
      [:fx-id args]
      ...]}
```

Each `:fx` entry is a 2-vector: `[fx-id args]`. The runtime walks them in source order, synchronously, after `:db` commits (`fx.cljc:4-9`). Any other top-level key — `:dispatch`, `:dispatch-later`, `:http`, etc — emits `:rf.error/effect-map-shape` and is dropped (`events.cljc:81`). v2 deliberately removed v1's auto-routed top-level effect keys.

The reserved fx-ids the core runtime handles directly (`fx.cljc:10-22`):

| fx-id | args | Effect |
|---|---|---|
| `:dispatch` | event-vector | Enqueue at back of frame's router |
| `:dispatch-later` | `{:ms n :event ev}` | Enqueue after `n` ms |
| `:rf.fx/reg-flow` / `:rf.fx/clear-flow` | flow spec | (Flows artefact; Spec 013) |

Machine fx-ids (`:rf.machine/spawn`, `:rf.machine/destroy`) ship in `day8/re-frame2-machines`; they register through the same `reg-fx` path when that artefact is loaded.

## Canonical mini-example

From `examples/reagent/todomvc/events.cljs`:

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
(rf/reg-event-fx :todo/add
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

- **Return shape is closed.** Top-level `:dispatch`, `:dispatch-later`, `:http`, etc. are dropped with a `:rf.error/effect-map-shape` trace. Wrap them inside `:fx`.
- **Fx handlers receive `(ctx, args)`, not just `args`.** The first arg is `{:frame ... :event ...}`. Ignore it with `_ctx` if you don't need it.
- **Returning a value from an fx handler does nothing.** Side effects are the point. `:rf.fx/handled` is emitted on success so the epoch projection records the run.
- **`:platforms #{:client}` makes the fx skip silently on server.** A `:rf.fx/skipped-on-platform` warning fires — fine for browser-only side effects, but check this if a fx mysteriously doesn't run under SSR.
- **Fx errors are isolated.** A throw inside one fx emits `:rf.error/fx-handler-exception` but does not abort the `:fx` walk. Don't rely on later entries seeing earlier failures.
- **Override the fx surface per frame**, not globally: `(rf/reg-frame :frame-id {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})`. Used heavily for test stubs and stories (`frame.cljc:115-118`).

## Deeper material

Per-frame fx overrides, the full reserved fx-id table, `:rf.http/managed`, flows, machine fx-ids: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — HTTP requests (014)**, **EP — Flows (013)**.

---

*Derived from `implementation/core/src/re_frame/fx.cljc`, `implementation/core/src/re_frame/events.cljc`, and `implementation/core/src/re_frame/frame.cljc` @ main `89bd9c3`. Re-verify line numbers after fx-walker or fx-overrides changes.*
