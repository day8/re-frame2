# 01 - Introduction

You want the basic foot in the door: what is re-frame2, what problem is it solving, and why does everyone keep drawing arrows between boxes like they have discovered electricity. By the end of this chapter you will understand the core loop well enough to read a small app without blinking, and you will have run one in the page.

A re-frame2 app is not a pile of components. It is a data loop. A user does something, an event records that fact, a handler computes the next state, subscriptions derive readable values, views render those values, and effects sit at the edge where the world is allowed to be messy.

That sounds grand, so here is the whole thing as a counter. Click into the cell and press `Ctrl-Enter` or `Cmd-Enter` to run it. Then edit it. Break it. The app is small enough that it cannot hide from you.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

(rf/reg-event-db :counter/initialise
  (fn [_db _event]
    {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event]
    (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event]
    (update db :counter/value dec)))

(rf/reg-sub :counter/value
  (fn [db _query]
    (:counter/value db)))

(defn counter []
  [:div {:style {:display "flex" :gap "0.75rem" :align-items "center"}}
   [:button {:on-click #(rf/dispatch [:counter/dec])} "-"]
   [:strong @(rf/subscribe [:counter/value])]
   [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]])

(rf/dispatch-sync [:counter/initialise])
[counter]
```

Try changing the `inc` in `:counter/inc` to `(partial + 10)`, re-evaluate, and click `+`. You just changed application behaviour by editing one pure function. No mock store. No callback tunnel. No tense ceremony involving a browser debugger and a look of quiet panic.

## The loop

The counter has five visible pieces.

| Piece | What it means |
|---|---|
| `app-db` | The current application value, one immutable map. |
| Event vector | A small data fact like `[:counter/inc]`. |
| Event handler | A registered function that computes the next state or returns effects. |
| Subscription | A named derivation from state to something a view wants. |
| View | A function that turns subscription values into hiccup. |

The important part is not that the pieces exist. Any framework can be explained by inventing nouns. The important part is that each piece has one job and one direction. Views do not mutate state. Handlers do not poke the DOM. HTTP does not leak into random component lifecycle callbacks. The architecture is a refusal to let causes smear themselves everywhere.

## Why the ceremony earns its keep

For a toy counter, the shape is more code than `useState`. That is true, and pretending otherwise is how documentation loses the reader in the first ten minutes. If your product is literally a counter, use `useState` and go have lunch.

The ceremony pays off when the app stops being tiny. Once events, subscriptions, effects, schemas, tests, Story, and Xray all speak the same data-shaped language, you get leverage. A test can run the same event as production. Story can mount the same view in a variant frame. Xray can show the same epoch the runtime committed. The debugger is not guessing. It is reading the same nouns your app uses.

## The one sentence model

A re-frame2 app is an event-driven state machine whose state is an immutable map, whose reads are cached derivations, whose views are data renderers, and whose side effects are named requests at the edge.

That sentence is dense. The rest of the guide unpacks it without asking you to swallow it whole.
