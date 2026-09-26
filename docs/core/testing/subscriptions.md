# Test a subscription

A [subscription](../subscriptions.md) computes a value from app-db. `rf/compute-sub` runs a sub against an app-db **value** and returns the result. It runs on the JVM with no Reagent, no installed [adapter](../glossary.md#adapter) and no subscription cache.

The subs under test are the todo chain from [Subscriptions](../subscriptions.md#three-layers-one-graph): `:todo/todos` and `:todo/showing` read app-db, `:todo/all` turns the map into a sorted vector, and `:todo/visible` filters it.

```clojure
;; src/my_app/subs.cljc
(rf/reg-sub :todo/todos   (fn [db _] (:todos db)))
(rf/reg-sub :todo/showing (fn [db _] (:showing db)))

(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _]
    (vec (sort-by :id (vals todos)))))

(rf/reg-sub :todo/visible
  {:inputs [[:todo/all] [:todo/showing]]}
  (fn [[todos showing] _]
    (case showing
      :active (filterv (complement :done?) todos)
      :done   (filterv :done? todos)
      todos)))
```

```clojure
(ns my-app.subs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [re-frame.substrate.plain-atom :as plain-atom]   ;; the headless adapter frames need
            [my-app.subs]))    ;; loading the ns registers the subs

(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest visible-honours-showing
  (let [db {:todos   {1 {:id 1 :title "Buy milk"     :done? true}
                      2 {:id 2 :title "Walk the dog" :done? false}}
            :showing :active}]
    (is (= ["Walk the dog"]
           (mapv :title (rf/compute-sub [:todo/visible] db))))))
```

The test never computes `:todo/todos`, `:todo/all` or `:todo/showing`. Given the query vector and a `db`, `compute-sub` resolves the whole input chain in dependency order. It keeps no cache between calls, so the same query and db always return the same value. A sub that takes arguments tests the same way, with the arguments in the query vector: `(rf/compute-sub [:todo/by-id 2] db)`.

## Build the db with real events

A hand-written `db` literal encodes the shape of app-db. For a simple extractor that is fine. For a sub that depends on the shape your events produce, the literal can go stale without failing: when a handler changes that shape, the test keeps passing against a db your app no longer builds.

Instead, boot a test [frame](../glossary.md#frame) with `:initial-events` and compute the sub against the db those events produce:

```clojure
;; :todo/add and :todo/toggle are the handlers from Test an event handler;
;; :todo/set-showing does (assoc db :showing kw).
(deftest visible-after-events
  (rf/with-new-frame [f (rf/make-frame
                          {:fx-overrides   {:todo.storage/save (fn [_ _] nil)}
                           :initial-events [[:todo/add "Buy milk"]
                                            [:todo/add "Walk the dog"]
                                            [:todo/toggle 1]
                                            [:todo/set-showing :active]]})]
    (is (= ["Walk the dog"]
           (mapv :title (rf/compute-sub [:todo/visible] (rf/app-db-value f)))))))
```

Use a literal `db` when the sub is trivial and the shape is obvious, and seed with events when the shape matters. Either way, don't use `subscribe` plus deref in tests: it needs a live cache and an installed adapter, and adds nothing to a value assertion.

`compute-sub` checks the computation, not the reactive machinery: it proves the value is right, not that a view re-renders when it changes. Change propagation is the framework's job, so you don't re-test it per sub. When the thing under test is "the view updated", write a [view test](views.md).

## When the sub carries a `:schema`

Your assertions don't change. A sub registered with an output [`:schema`](../subscriptions.md#saying-things-about-a-sub-metadata) validates its value in dev, `compute-sub` included. A shape bug emits a `:rf.error/schema-validation-failure` error record, and the call returns `nil`. [Asserting on that error record](../errors.md#test-the-structure-not-the-string) is an ordinary listener-based test.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `compute-sub` returns `nil` unexpectedly | The sub body threw (`:rf.error/sub-exception`), its output failed its `:schema`, or an input names an unregistered sub (which computes to `nil` with no error record) | Check your error listener, then the input ids and the require list |
| Test passes but the app shows something else | The literal `db` no longer matches the shape your handlers build | Seed the db with `:initial-events` |

If a hand-rolled fixture or a REPL session does use the live cache, `(rf/clear-sub-cache! frame-id)` disposes every cached subscription for that frame. The reset fixture already discards frames between tests.
