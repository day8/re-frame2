# app-db: one map, one write path

Your application's state has to live somewhere, and something has to change it.
In re-frame2 it lives in **[app-db](glossary.md#app-db)**: one immutable Clojure map
per [frame](glossary.md#frame). Event handlers return the next map; the
[event pipeline](glossary.md#event-pipeline) writes it.

```text
event + world → handler → {:db next-db} → atomic commit
```

## A complete live counter

The counter from the [Introduction](introduction.md) gains a second fact,
`:step-size`, and a decrement button. Both facts are seeded by `:initialise`. Click
the buttons (edit the cell and press **`Ctrl-Enter`** / **`Cmd-Enter`** if you change
the code):

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
   [:span {:style {:margin-left "1.5em"}} "step " @(subscribe [:step-size]) ":"]
   [:button {:on-click #(dispatch [:step-size/set {:step-size 1}])} "1"]
   [:button {:on-click #(dispatch [:step-size/set {:step-size 10}])} "10"]])

[rf/frame-root {:id :app
                :initial-events [[:initialise]]}
 [stepping-counter]]
```

Handler parameters use [destructuring](../cljs/index.md#destructuring).
`{:keys [db]}` takes app-db from the world map. `[_ {:keys [step-size]}]` ignores
the event id and takes `:step-size` from the payload map.

The events produce a sequence of complete map values:

```clojure
[:initialise]
;; => {:value 0 :step-size 1}

[:step-size/set {:step-size 10}]
;; => {:value 0 :step-size 10}

[:inc]
;; => {:value 10 :step-size 10}
```

Each event returns a replacement for the whole value. Outside this in-browser
environment a real app also needs boot wiring: see the
[counter example](../../examples/core/counter) or
[Boot and mount an app](how-to/boot-and-mount-an-app.md).

## The write path

Everything your app keeps as state between events sits in one map of ordinary nested
data, with no framework-imposed shape. A todo list, which later pages use, might hold:

```clojure
{:todos   {1 {:id 1 :title "Buy milk"     :done? false}
           2 {:id 2 :title "Walk the dog" :done? true}}
 :showing :all}
```

There is one normal way to change it: dispatch an [event](glossary.md#event); the
[handler](glossary.md#event-handler) returns an [effect map](glossary.md#effect-map)
that may include `:db`; the runtime [commits](glossary.md#commit) that new map
atomically. Handlers compute the next value; they do not mutate the old one.

```clojure
(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))
```

`update-in` returns a new map. The runtime later moves the app-db *reference* from
the old value to the new one in a single commit. (The place is `app-db`; the value
currently in it is usually bound as `db`.)

That gives you three useful properties:

- no view sees a half-written state;
- old values can be inspected, diffed, or restored;
- handlers are pure functions you can unit test.

A handler may return no `:db` key (only `:fx`, say) and leave app-db alone, or return
the *same* `db` object it was handed so the runtime skips a no-op write.

!!! warning "`{:db nil}`"

    app-db is always a map. An accidental `{:db nil}` is coerced to `{}` with a dev
    warning (`:rf.warning/db-nil-coerced`). To clear state on purpose, write
    `{:db {}}`.

??? info "Coming from Redux?"

    app-db is the single store; a handler is a pure function that returns the next
    state as data (`{:db …}`), and the runtime commits it. No combined reducers, no
    prescribed slice shape — one ordinary Clojure map. Immutability is by
    construction: `update-in` cannot mutate, so no spread-operator discipline is needed.

??? info "From re-frame v1"

    One app-db, and handlers return a new value, as in v1. app-db holds *only* your
    application data; framework bookkeeping lives next door in
    [runtime-db](#yours-and-the-frameworks-next-door). v1's `reg-event-db` and
    `reg-event-fx` are one `reg-event` that always returns an effect map.

## Initial state is an event

Every frame starts with `app-db = {}`. There is no config option to seed it; seeding
is itself an event, run through the same pipeline as every later change and listed
under `:initial-events` on the frame (or `frame-root`).

The counter already did this with `:initialise`. A larger app is the same idea, and
can list several events:

```clojure
(rf/reg-event :todo/initialise
  (fn [_world _event]
    {:db {:todos {} :showing :all}}))

[rf/frame-root {:id :app
                :initial-events [[:todo/initialise]
                                 [:todo/add "Buy milk"]]}
 [todo-app]]
```

Each initial event's pipeline runs synchronously, in order, through its immediate
commit before the next begins. If a handler starts asynchronous work, its reply
arrives later through another event; setup does not wait for the host operation. By
the time setup finishes, app-db includes the immediate `:db` commits from the initial
events. Prefer a named map payload when an initialise event carries options:
`[:initialise {:user-id 42}]` with `[_ {:keys [user-id]}]`.

When all you need is a literal starting map, the framework's own `:rf/set-db` event
does it without a handler of yours:

```clojure
[rf/frame-root {:id :app :initial-events [[:rf/set-db {:value 0 :step-size 1}]]}
 [stepping-counter]]
```

[Frames](frames.md) covers the other frame options.

## Shape the map around the domain

Use ordinary maps and vectors. Prefer stable domain paths (`:todos`, `:showing`)
over scattering presentation flags next to every fact. Views stay thin; they read
what they need through subscriptions.

## Store facts, derive conclusions

Put facts in app-db. Let [subscriptions](subscriptions.md) derive the conclusions
views show ([flows](flows.md) cover derivations you deliberately materialise back
into app-db).

Good:

```clojure
{:todos {1 {:id 1 :title "Buy milk"     :done? false}
         2 {:id 2 :title "Walk the dog" :done? true}}}
```

Poor:

```clojure
{:todos           {...}
 :remaining-count 1
 :all-done?       false}
```

`:remaining-count` and `:all-done?` are conclusions. Stored next to the todos, they
have to be updated by every handler that touches a todo, and one that forgets leaves
them wrong. Derive them with a subscription instead.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| State "vanished" after a handler | Accidental `{:db nil}` | Write `{:db {}}` to clear; watch for `:rf.warning/db-nil-coerced` |
| Two facts disagree | A conclusion was stored next to its facts | Derive in a [subscription](subscriptions.md) (or a [flow](flows.md) if handlers must read it) |
| Initial UI shows empty values | No seed event | List an initialise event (or `[:rf/set-db {…}]`) in `:initial-events` |
| Frame creation throws `:rf.error/initial-db-retired` or `:rf.error/on-create-retired` | The frame options carry `:initial-db` or `:on-create` | Seed through `:initial-events` |
| Machine or route state doesn't reflect what you wrote into app-db | Framework state lives in runtime-db, not app-db | Dispatch the subsystem's events instead |

## Advanced

### Yours, and the framework's next door

A running frame also holds [**runtime-db**](glossary.md#runtime-db): framework
bookkeeping (machine snapshots, the current route, the resource cache, …) under
reserved `:rf.runtime/*` keys. The [two partitions](glossary.md#the-two-partitions)
are separate: app-db is yours, and runtime-db is the framework's. Read runtime-db
through the framework's subscriptions and change it by dispatching the framework's
events; don't recreate its keys in app-db. An ordinary `:db` effect cannot wipe a
machine snapshot. [Frames](frames.md) goes deeper.

### Keeping secrets out of traces

Tools and traces see a copy of app-db. To redact a path in that copy, return a
classification effect beside `:db`. `:sensitive` redacts the value and `:large`
replaces it with a size marker; `:clear-sensitive` and `:clear-large` undo them:

```clojure
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     :sensitive [[:auth :token] [:auth :refresh-token]]}))
```

Your handlers and subscriptions still read the real value. Classifying a path before
anything is stored there is fine, so a seed event is the natural place.
[Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md) covers the details.
