# 13 - Testing

You like to sleep at night and you want to add tests without launching a browser for every sneeze. This chapter teaches the re-frame2 testing stance: test each behaviour at the cheapest layer that can truthfully observe it, then use Story plans for flow-shaped behaviour you also want humans to browse.

The architecture pays for tests because most of the app is data and functions.

| Thing | Cheap test |
|---|---|
| `reg-event-db` handler | Call the handler function or dispatch in a test frame. |
| `reg-event-fx` handler | Assert on the returned effect map or override effects. |
| Subscription | `compute-sub` against a db value. |
| View | Call the view and inspect hiccup. |
| Machine | `machine-transition` or dispatch in a frame. |
| Flow | `story/run` or `story/is` over an inline plan or variant. |

## Fresh frames

Use a fresh frame per test when you want to drive the real cascade.

```clojure
(rf/with-new-frame [f (rf/make-frame {:on-create [:counter/initialise]})]
  (rf/dispatch-sync [:counter/inc] {:frame f})
  (is (= 1 (:counter/value (rf/get-frame-db f)))))
```

Isolation is not a moral virtue you remember after coffee. It is a primitive.

## Effects as data

```clojure
(def seen (atom []))

(rf/with-fx-overrides {:analytics/track #(swap! seen conj %)}
  (rf/dispatch-sync [:session/start 42]))

(is (= [{:event :session/start :user-id 42}] @seen))
```

You did not mock the world. You replaced one named effect.

## Story tests

A Story variant is not just a screenshot. In re-frame2 it can be an executable plan: world, script, expectations, and evidence. The public shape is intentionally small:

```clojure
(story/is {:world {:setup [[:dispatch-sync [:counter/initialise 0]]]}
           :script [[:dispatch-sync [:counter/inc]]]
           :expect [[:rf.assert/path-equals [:counter/value] 1]]}
          {:runner :headless})
```

A registered Story uses the same plan compiler. That means the example a human explores can become the regression a test runner executes.

## `:cannot-run` is honesty

A headless runner cannot prove a visual snapshot. A hiccup runner cannot prove browser focus. A good tool says `:cannot-run` instead of pretending. The right response is not "make every test browser-based." The right response is to run the browser assertions in the browser tier and keep the cheap ones cheap.

## Pitfall: testing the framework twice

Do not write giant end-to-end tests for every feature because the framework made it possible to replay events. Most value sits in focused handler, subscription, machine, and plan tests. Browser tests earn their keep only when the browser is the thing under test.
