# Test a subscription

A [subscription](../subscriptions.md)'s computation is a pure function of its inputs and its query vector, so testing what it computes needs no reactive runtime, no DOM and no browser. `rf/compute-sub` runs a sub against an app-db **value** and returns the result. It runs on the JVM with no Reagent, no React, no installed [adapter](../glossary.md#adapter) and no live cache.

The sub under test here is the three-layer cart chain from [Subscriptions](../subscriptions.md#three-layers-one-graph): `:cart/items` and `:cart/category-filter` extract, `:cart/by-price` sorts, `:cart/visible` filters.

```clojure
(ns my-app.subs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.subs]))    ;; loading the ns registers the subs

(use-fixtures :each (ts/make-reset-runtime-fixture {}))

(deftest visible-items-honour-the-category-filter
  (let [db {:cart/items           [{:sku "a" :category "books"  :price 2}
                                   {:sku "b" :category "snacks" :price 1}]
            :cart/category-filter "books"}]
    ;; compute-sub resolves the whole declared-:inputs chain — :cart/items and
    ;; :cart/by-price run automatically as inputs.
    (is (= ["a"]
           (mapv :sku (rf/compute-sub [:cart/visible] db))))))
```

Nothing in the test computes `:cart/items` or `:cart/by-price`. Given the outer query vector and a `db`, `compute-sub` resolves the entire input chain in dependency order. It carries no cache between calls, so the same query vector and db always return the same value. A parametric sub tests the same way, with its arguments in the query vector: `(rf/compute-sub [:article/page "welcome"] db)`.

## Build the db with real events

A hand-written `db` literal encodes the internal shape of app-db. For a simple extractor that is fine. For a sub that depends on the shape your events actually produce, it goes stale without failing: the day a handler changes that shape, the test keeps passing against a db your app no longer builds.

Build the db the way your app builds it. Boot a test [frame](../glossary.md#frame) with `:initial-events` on `make-frame` — each step is an ordinary dispatch, drained in order while the frame is constructed — and compute the sub against the db that produces:

```clojure
;; Assumes my-app registers :cart/add-item, which conj's the item onto :cart/items.
(deftest cart-count-after-events
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:cart/add-item {:sku "BK-1"}]
                                                         [:cart/add-item {:sku "BK-2"}]]})]
    (is (= 2 (count (rf/compute-sub [:cart/items] (rf/app-db-value f)))))))
```

Use a literal `db` when the sub is trivial and the shape is obvious. Seed with real events when the shape matters, because that test runs against the db your handlers actually build. In either case, skip `subscribe` plus deref in tests: it needs a live cache and an installed adapter, and adds nothing to a value assertion.

`compute-sub` checks the computation, not the reactive machinery. It proves the value is right, not that a view re-renders when it changes. Change propagation (the equality gate, ref-counting, disposal) is the framework's job, so you don't re-test it per sub. When the thing under test is "the view updated", write a [view test](views.md) with the reset fixture, or a [pipeline-run test](pipeline-runs.md) asserting on committed state.

If a hand-rolled fixture or a REPL session does exercise the live cache, `(rf/clear-sub-cache! frame-id)` disposes every cached subscription for that frame. The reset fixture above already discards frames between tests, so a suite using it doesn't need the call.

## When the sub carries a `:schema`

Your assertions don't change. A sub registered with an output [`:schema`](../subscriptions.md#saying-things-about-a-sub-metadata) validates its computed value in dev, `compute-sub` included, so a shape bug in the computation raises a structured `:rf.error/schema-validation-failure` and the call returns `nil`. [Asserting on that error record](../errors.md#test-the-structure-not-the-string) is an ordinary listener-based test.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `compute-sub` returns `nil` unexpectedly | The sub body threw (`:rf.error/sub-exception`), its output failed its `:schema`, or an input names an unregistered sub, which computes to `nil` with no error record | Check your error listener, then re-read the input ids and the app namespace require |
| Test passes but the app shows something else | The literal `db` no longer matches the shape your handlers build | Seed the db with `:initial-events` instead |

??? info "For JavaScript developers"

    There is no React Testing Library, no `renderHook`, no jsdom and no provider wrapper here. A subscription test is a function call asserting on a value, and it runs on the JVM at unit-test speed. The reactive runtime exists to cache and notify in a live app; the logic itself is data in, data out.
