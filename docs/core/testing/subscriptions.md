# Test a subscription

A [subscription](../concepts/subscriptions.md) is a formula over facts. You test a formula the spreadsheet way: hand it the cells, look at the answer.

That works because a subscription's computation is a pure function of `(inputs, query-v)` — so you need no reactive runtime, no DOM, and no browser to test what it *computes*. `rf/compute-sub` runs a sub's body against an app-db **value** and returns the result. It runs on the JVM: no Reagent, no React, no installed [adapter](../glossary.md#adapter), no live cache.

> **A subscription test is `compute-sub` against a db value — the whole `:<-` chain resolves for you.**

That's the whole recipe. The rest of this page is choosing where the db value comes from — and knowing the one thing a sub test doesn't prove.

The sub under test here is the three-layer cart chain from [Subscriptions](../concepts/subscriptions.md#three-layers-one-graph): `:cart/items` and `:cart/category-filter` extract, `:cart/by-price` sorts, `:cart/visible` filters.

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
    ;; compute-sub resolves the whole :<- chain — :cart/items and
    ;; :cart/by-price run automatically as inputs.
    (is (= ["a"]
           (mapv :sku (rf/compute-sub [:cart/visible] db))))))
```

Notice what you didn't write: nothing in the test computes `:cart/items` or `:cart/by-price`. Pass `compute-sub` the outer query vector and a `db`, and it resolves the entire input chain for you, in dependency order. It's **pure** — the same `(query-v, db)` always returns the same value, with no cache carried between calls — and that's what makes it the workhorse for sub tests. A parametric sub tests the same way, the arguments riding the query vector: `(rf/compute-sub [:article/page "welcome"] db)`.

## The sharper variant: drive real events first

A hand-rolled literal `db` has a hidden cost: the test now knows the internal structure of app-db. For a very simple reader, fine — that's the escape hatch. But for anything that depends on the *shape* your events actually produce, it rots silently: the day a handler changes that shape, the test keeps passing against a db your app no longer builds. Green, and wrong.

The fix is to build the db the way your app builds it — through events. Boot a test [frame](../glossary.md#frame) through real events — `:initial-events` on `make-frame`, each step an ordinary dispatch drained in order at construction — and read the sub against the db that produces. The events aren't the subject here; they're setup, so they ride the frame's construction rather than the test body:

```clojure
(deftest cart-count-after-events
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:cart/add-item {:sku "BK-1"}]
                                                         [:cart/add-item {:sku "BK-2"}]]})]
    (is (= 2 (count (rf/compute-sub [:cart/items] (rf/app-db-value f)))))))
```

!!! note "Two styles, one rule of thumb"

    `compute-sub` against a literal `db` when the reader is trivial and the shape is obvious. Seed with real events and read `(rf/app-db-value f)` when the db shape matters — that test exercises the same db your handlers actually build, so it can't drift from reality. And skip `subscribe` + deref in tests altogether: the reactive runtime is pure overhead for a value assertion, and it needs a live cache and an installed adapter.

Now the boundary, said plainly, because it spares you a whole category of useless test: `compute-sub` runs the *computation*, not the *reactive machinery*. It proves the value is right — not that a view re-renders when it changes. That's by design. Change propagation (the equality gate, ref-counting, disposal) is the framework's contract, not your code, so you don't re-test it per sub. When the thing under test genuinely is "the view updated", that's a [view test](views.md) with the app fixture, or a [pipeline-run test](pipeline-runs.md) asserting on committed state.

## When the sub carries a `:schema`

Nothing about your assertions changes. A sub registered with output [`:schema`](../concepts/subscriptions.md#saying-things-about-a-sub-metadata) metadata validates its computed value at the `:sub-return` boundary in dev — so a shape bug in the computation surfaces as a structured `:rf.error/schema-validation-failure` rather than a downstream view choking on it. And [asserting on that record](../concepts/errors.md#test-the-structure-not-the-string) is itself an ordinary listener-based test.

??? info "For JavaScript developers"

    Pause on what's *absent* from this page. No React Testing Library, no `renderHook`, no jsdom, no provider wrapper to set up — a subscription test is a plain function call asserting on a plain value, and it runs on the JVM at unit-test speed. That's the payoff of computation functions being pure: the reactive runtime exists only to *cache and notify* in a live app; the *logic* is just data in, data out, testable in isolation.
