# app-db: one map, one write path

The [introduction](introduction.md) walked the [event pipeline](glossary.md#event-pipeline);
[events](events.md) named the vocabulary. This page follows **application data**:
where it lives, and how it changes.

**[app-db](glossary.md#app-db)** is the application state for one
[frame](glossary.md#frame): one immutable Clojure map. Handlers return a
*description* of the next map; the [event pipeline](glossary.md#event-pipeline) is
what actually writes.

```text
event + world → handler → {:db next-db} → atomic commit
```

> **One map. One write path. Events in, new map out.**

Almost everything else in re-frame2 stays simple *because* of that.

## A complete live counter

The intro counter seeded `:value` through its `:initialise` event; the
`(:value db 0)` in its subscription was a defensive display fallback, not the
initializer. Here we reuse that pattern and add a second fact — `:step-size` — so
both begin explicit in one store. Click the buttons (edit the cell and press
**`Ctrl-Enter`** / **`Cmd-Enter`** if you change the code):

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [_world _event]
    {:db {:value 0 :step-size 1}}))

(rf/reg-event :step-size/set
  (fn [{:keys [db]} [_ {:keys [step-size]}]]
    {:db (assoc db :step-size step-size)}))

(rf/reg-event :inc
  (fn [{:keys [db]} _]
    {:db (update db :value + (:step-size db))}))

(rf/reg-event :dec
  (fn [{:keys [db]} _]
    {:db (update db :value - (:step-size db))}))

(rf/reg-sub :value (fn [db _] (:value db)))
(rf/reg-sub :step-size (fn [db _] (:step-size db)))

(rf/reg-view stepping-counter []
  [:div
   [:button {:on-click #(dispatch [:dec])} "−"]
   [:span " " @(subscribe [:value]) " "]
   [:button {:on-click #(dispatch [:inc])} "+"]
   [:span {:style {:margin-left "1.5em"}} "step:"]
   [:button {:on-click #(dispatch [:step-size/set {:step-size 1}])} "1"]
   [:button {:on-click #(dispatch [:step-size/set {:step-size 10}])} "10"]])

[rf/frame-root {:id :app
                :initial-events [[:initialise]]}
 [stepping-counter]]
```

Handler parameters use [destructuring](../cljs/index.md#destructuring).
`{:keys [db]}` takes app-db from the world map. `[_ {:keys [step-size]}]` ignores
the event id and takes `:step-size` from the payload map.

No second store. The events produce a sequence of complete map values:

```clojure
[:initialise]
;; => {:value 0 :step-size 1}

[:step-size/set {:step-size 10}]
;; => {:value 0 :step-size 10}

[:inc]
;; => {:value 10 :step-size 10}
```

Both facts begin in the initial map; later events return replacements for that whole
value. That is a **complete live counter**: [registrations](glossary.md#register), a
[frame-root](glossary.md#frame-root), and a view. (A real app still needs boot
wiring — [counter example](../../examples/core/counter) or
[Boot and mount an app](how-to/boot-and-mount-an-app.md).) The rest of the guide
grows this same counter one concept at a time.

## One map, one write path

Everything your app owns as state between events sits in one map — ordinary nested
data, with no framework-imposed shape:

```clojure
{:user {:id 42 :name "Mike"}
 :cart {:items [] :status :draft}
 :ui   {:active-panel :cart :modal nil}}
```

Exactly one normal **write path**: dispatch an [event](glossary.md#event); the
[handler](glossary.md#event-handler) returns an [effect map](glossary.md#effect-map)
that may include `:db`; the runtime [commits](glossary.md#commit) that new map
atomically. Handlers compute a proposed next value — they do not mutate the old one.

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ {:keys [item]}]]
    {:db (update-in db [:cart :items] conj item)}))
```

`update-in` returns a *new* map. The runtime later moves the app-db *reference* from
the old value to the new one in a single commit. (The place is `app-db`; the value
currently in it is usually bound as `db`.)

That gives you three useful properties:

- no view sees a half-written state;
- old values can be inspected, diffed, or restored;
- handlers are pure functions you can unit test.

A handler may return no `:db` key (only `:fx`, say) and leave app-db alone, or return
the *same* `db` object it was handed so the runtime skips a no-op write.

!!! warning "Gotcha — `{:db nil}`"

    app-db is always a map. An accidental `{:db nil}` is coerced to `{}` with a dev
    warning (`:rf.warning/db-nil-coerced`). To clear state on purpose, write
    `{:db {}}`.

??? info "Coming from Redux?"

    app-db is the single store; a handler is a pure function that returns the next
    state as data (`{:db …}`), and the runtime commits it. No combined reducers, no
    prescribed slice shape — one ordinary Clojure map. Immutability is by
    construction (`update-in` cannot mutate), not a spread-operator discipline.

??? info "From re-frame v1"

    One app-db, handlers return a new value — same spirit. What changed: app-db is
    *only* your application data. Framework bookkeeping lives next door in
    [runtime-db](#yours-and-the-frameworks-next-door), not under a `:rf/runtime`
    root inside your map.

## Initial state is an event

A frame's event fold starts with `app-db = {}`. There is no `:db` config slot to seed
it. Seeding is itself an event — the same pipeline as every later change — listed as
`:initial-events` on the frame (or `frame-root`).

The counter already did this with `:initialise`. A larger app is the same idea:

```clojure
(rf/reg-event :initialise
  (fn [_world _event]
    {:db {:session nil
          :ui {:route :home}}}))

[rf/frame-root {:id :app
                :initial-events [[:initialise]
                                 [:session/restore]]}
 [root-view]]
```

Each initial event's pipeline runs synchronously, in order, through its immediate
commit before the next begins. If a handler starts asynchronous work, its reply
arrives later through another event; setup does not wait for the host operation. By
the time setup finishes, app-db includes the immediate `:db` commits from the initial
events — no seeding side channel. Prefer a named map payload when an initialise
event carries options: `[:initialise {:user-id 42}]` with `[_ {:keys [user-id]}]`.
[Frames](frames.md) covers the rest of the registration grammar.

??? info "From re-frame v1"

    `:initial-db` and `:on-create` are gone. Seed with `:initial-events` and your own
    initialise event (or several), not a config map.

## Shape the map around the domain

Use ordinary maps and vectors. Prefer stable domain paths (`:session`, `:articles`,
`:ui`) over scattering presentation flags next to every fact. Views stay thin; they
read what they need through subscriptions.

## Store facts. Derive conclusions.

Put **facts** in app-db. Let [subscriptions](subscriptions.md) derive **view-facing
conclusions** (later, [flows](flows.md) cover derivations you intentionally
materialise back into app-db).

Good:

```clojure
{:cart {:items [{:sku "A" :price 10}
                {:sku "B" :price 20}]}}
```

Poor:

```clojure
{:cart {:items [...]
        :total 30
        :empty? false}}
```

`total` and `empty?` are conclusions. Store them next to the items and they will
drift — two copies of one truth is two chances to disagree. Derive them so there is
only one truth to update. That is the next page's job.

## Yours, and the framework's next door

For state your application owns between events, app-db is the write target. A running
frame also holds [**runtime-db**](glossary.md#runtime-db) — framework bookkeeping
(machine snapshots, route, resource cache, …) under reserved `:rf.runtime/*` keys.
[Two partitions](glossary.md#the-two-partitions): app-db is yours; runtime-db is the
framework's (read it via its subscriptions; influence it by dispatching its events —
never forge it by hand in app-db). An ordinary `:db` effect cannot wipe a machine
snapshot. [Frames](frames.md) goes deeper.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| State "vanished" after a handler | Accidental `{:db nil}` | Write `{:db {}}` to clear; watch for `:rf.warning/db-nil-coerced` |
| Two facts disagree | A conclusion was stored next to its facts | Derive in a [subscription](subscriptions.md) (or a [flow](flows.md) if handlers must read it) |
| Initial UI shows empty before first paint | No seed event | List an initialise event in `:initial-events` |
| Framework snapshot gone after your `:db` | You tried to own runtime keys in app-db | Leave runtime-db alone; dispatch the subsystem's events |

With this page you can seed a frame, keep facts in one map, write only through
events, and leave conclusions out of app-db. The left nav continues into how those
conclusions are named and cached.
