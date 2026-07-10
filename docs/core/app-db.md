# app-db: the one place

The [introduction](introduction.md) showed how re-frame2 **computes**. This page is where the data in that loop lives.

**[app-db](glossary.md#app-db)** is the application state for one [frame](glossary.md#frame): one immutable Clojure map. Handlers return a *description* of the next map; the [event pipeline](glossary.md#event-pipeline) is what actually writes.

```text
event + world → handler → {:db next-db} → atomic commit
```

> **One map. One write path. Events in, new map out.**

Almost everything else in re-frame2 stays simple *because* of that.

## A complete live counter

In the intro, the frame's app-db started as `{}` and the display showed `0` only because the subscription fell back when `:value` was missing. After the first click, app-db became `{:value 1}`.

This page makes the initial value **explicit** through an `:initialise` event, then adds a second fact — `:step-size` — in the same map. Still one store. Click the buttons (edit the cell and press **`Ctrl-Enter`** / **`Cmd-Enter`** if you change the code):

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

[rf/frame-provider {:id :app
                    :initial-events [[:initialise]]}
 [stepping-counter]]
```

No second store. After `:initialise`, the shape already holds both facts:

```clojure
{:value 0 :step-size 1}
```

— not a lone `{:value 0}` that later grows a second key by accident. You put both in the seed map (or add keys in later events the same way).

That is a **complete live counter**: [registrations](glossary.md#register), a [frame-provider](glossary.md#frame-provider) that creates and scopes the frame and runs the seed, and a view. (The playground mounts the trailing hiccup; a real app still needs boot wiring — [counter example](../../examples/core/counter) or [Boot and mount an app](how-to/boot-and-mount-an-app.md).) The rest of the guide grows this same counter one concept at a time.

## One map, one write path

Everything your app knows sits in one map — ordinary nested data, no imposed schema:

```clojure
{:user {:id 42 :name "Mike"}
 :cart {:items [] :status :draft}
 :ui   {:active-panel :cart :modal nil}}
```

Exactly one normal **write path**: dispatch an [event](glossary.md#event); the [handler](glossary.md#event-handler) returns an [effect map](glossary.md#effect-map) that may include `:db`; the runtime [commits](glossary.md#commit) that new map atomically. Handlers compute a proposed next value — they do not mutate the old one.

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ {:keys [item]}]]
    {:db (update-in db [:cart :items] conj item)}))
```

`update-in` returns a *new* map. The runtime later moves the app-db *reference* from the old value to the new one in a single commit. (The place is `app-db`; the value currently in it is usually bound as `db`.)

That gives you three useful properties:

- no view sees a half-written state;
- old values can be inspected, diffed, or restored;
- handlers are pure functions you can unit test.

A handler may return no `:db` key (only `:fx`, say) and leave app-db alone, or return the *same* `db` object it was handed so the runtime skips a no-op write.

!!! warning "Gotcha — `{:db nil}`"

    app-db is always a map. An accidental `{:db nil}` is coerced to `{}` with a dev warning (`:rf.warning/db-nil-coerced`). To clear state on purpose, write `{:db {}}`.

??? info "Coming from Redux?"

    app-db is the single store; a handler is a pure function that returns the next state as data (`{:db …}`), and the runtime commits it. No combined reducers, no prescribed slice shape — one ordinary Clojure map. Immutability is by construction (`update-in` cannot mutate), not a spread-operator discipline.

??? info "From re-frame v1"

    One app-db, handlers return a new value — same spirit. What changed: app-db is *only* your application data. Framework bookkeeping lives next door in [runtime-db](#yours-and-the-frameworks-next-door), not under a `:rf/runtime` root inside your map.

## Initial state is an event

A frame starts with `app-db = {}`. There is no `:db` config slot to seed it. Seeding is itself an event — the same pipeline as every later change — listed as `:initial-events` on the frame (or `frame-provider`).

The counter already did this with `:initialise`. A larger app is the same idea: a domain handler that returns the first map, then any follow-ups you need:

```clojure
(rf/reg-event :initialise
  (fn [_world _event]
    {:db {:session nil
          :ui {:route :home}}}))

[rf/frame-provider {:id :app
                    :initial-events [[:initialise]
                                     [:session/restore]]}
 [root-view]]
```

Steps run synchronously, in order, each to completion before the next. By the time setup finishes, app-db is whatever those handlers produced — no side channel. Prefer a named map payload when an initialise event carries options: `[:initialise {:user-id 42}]` with `[_ {:keys [user-id]}]`. [Frames](frames.md) covers the rest of the registration grammar.

??? info "From re-frame v1"

    `:initial-db` and `:on-create` are gone. Seed with `:initial-events` and your own initialise event (or several), not a config map.

## Shape the map around the domain

Use ordinary maps and vectors. Prefer stable domain paths (`:session`, `:articles`, `:ui`) over scattering presentation flags next to every fact. Views stay thin; they read what they need through subscriptions.

## Store facts. Derive conclusions.

Put **facts** in app-db. Let [subscriptions](subscriptions.md) derive **view-facing conclusions** (later, [flows](flows.md) cover derivations you intentionally materialise back into app-db).

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

`total` and `empty?` are conclusions. If you store them, they can drift from the items. Derive them so there is only one truth to update — that is the next page's job.

## Yours, and the framework's next door

For everything you write, app-db is the state that matters. A running frame also holds [**runtime-db**](glossary.md#runtime-db) — framework bookkeeping (machine snapshots, route, resource cache, …) under reserved `:rf.runtime/*` keys. [Two partitions](glossary.md#the-two-partitions): app-db is yours; runtime-db is the framework's (read it via its subscriptions; influence it by dispatching its events — never forge it by hand in app-db). An ordinary `:db` effect cannot wipe a machine snapshot. [Frames](frames.md) goes deeper.
