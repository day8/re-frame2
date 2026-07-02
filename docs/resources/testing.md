# Testing resources

Resources split into three lanes — register, cause, project — and that split is exactly what makes them testable: a test *causes* with an ordinary dispatch, answers the network with the same canned replies a [cascade test](../core/testing/cascades.md) uses, and *reads* the outcome through the projections. No live server, no waiting, no browser.

> **Cause with a dispatch, answer with a canned reply, read the cache projection.**

Two reads exist for tests specifically. In a view you always project through a subscription — but `rf/resource-state` and `rf/mutation-state` are the documented one-shot, non-reactive snapshots at an explicit frame, built for exactly this context (tools, unit tests, SSR serialisation). A test asserts on them directly:

```clojure
(ns my-app.resources-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.test-support]     ;; canned stubs — test-only, never in production requires
            [re-frame.test-support :as ts]
            [my-app.resources]))             ;; loading the ns registers the resources

(use-fixtures :each (ts/make-reset-runtime-fixture {}))   ;; resets the resource caches too
```

## 1. A read, end to end

Ensure is the cause; the stub answers; the projection settles — all inside one `dispatch-sync` drain:

```clojure
(deftest article-loads-into-the-cache
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/with-managed-request-stubs
      {[:get "/api/articles/intro"]
       {:reply {:ok {:article {:slug "intro" :title "Welcome"}}}}}
      (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/article
                                              :params   {:slug "intro"}
                                              :cause    [:manual :test/setup]}])
      (let [state (rf/resource-state {:resource :realworld/article
                                      :params   {:slug "intro"}
                                      :frame    f})]
        (is (= :loaded (:status state)))
        (is (true? (:has-data? state)))
        (is (= "Welcome" (get-in state [:data :article :title])))))))
```

Three properties worth pinning while you're here, because each is a contract, not an accident:

- **A subscription never fetches.** Read the state *before* any cause fires and it's `:idle` — a test that asserts that is the honest check for "my view was a permanent skeleton because I forgot the cause."
- **A first-load failure is `:error` with no data.** Stub `{:reply {:failure {:kind :rf.http/http-5xx :status 503}}}` and assert `:status :error`, `:has-data? false`, and the failure's `:kind` — [branch on the category, never the prose](../core/concepts/errors.md#test-the-structure-not-the-string).
- **A fresh ensure is a cache hit.** Ensure the same key twice against a stub table and the second dispatch fetches nothing — the stub table doubles as a coverage check, since an *unexpected* second request would hang at the unmatched effect rather than silently passing.

## 2. Scope resolvers are pure

A [scope resolver](concepts.md#the-scoped-key-a-leak-boundary-that-fails-closed) derives "whose cache?" from app-db, and `rf/resolve-resource-scope` runs it against any db value with no dispatch and no runtime — so the leak boundary unit-tests as a plain function, including its fail-closed edge:

```clojure
(deftest session-scope-resolves-and-fails-closed
  ;; logged in → the session scope key
  (is (= [:rf.scope/session {:username "alice"}]
         (rf/resolve-resource-scope {:auth {:user {:username "alice"}}}
                                    :realworld/session)))
  ;; logged out → nil, never a fall-through to a shared cache
  (is (nil? (rf/resolve-resource-scope {} :realworld/session))))
```

That second assertion is the one that matters: `nil` is the *fail-closed* answer, and everything downstream (route planning, subscriptions, clear-scope) treats it as "don't touch any cache" rather than "use the global one."

## 3. Invalidation and mutations

A write's cache consequences are declared (`:invalidates`, `:populates`), so the test drives the write and asserts the consequence. The read it invalidates is *setup*, not the subject — so it rides the frame's `:initial-events`, with the stub table wrapped around frame creation (stubs bind for their dynamic extent, and the seed fetches as the frame boots). Staleness is the observable: an entry with no live owner is *marked stale* by an invalidation rather than refetched, which makes `:stale?` the clean assertion:

```clojure
(deftest favorite-invalidates-the-article
  (rf/with-managed-request-stubs
    {[:get  "/api/articles/intro"]          {:reply {:ok {:article {:slug "intro"}}}}
     [:post "/api/articles/intro/favorite"] {:reply {:ok {:article {:slug "intro" :favorited true}}}}}
    ;; the frame boots with the article already loaded — through the canned reply
    (rf/with-new-frame [f (rf/make-frame
                            {:initial-events
                             [[:rf.resource/ensure {:resource :realworld/article
                                                    :params   {:slug "intro"}
                                                    :cause    [:manual :test/setup]}]]})]
      ;; run the write…
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :realworld/favorite
                                               :params   {:slug "intro"}
                                               :instance [:favorite "intro"]
                                               :cause    [:manual :test/favorite]}])
      ;; …and assert both sides: the instance settled, the read went stale.
      (let [m (rf/mutation-state {:instance [:favorite "intro"] :frame f})]
        (is (true? (:success? m))))
      (is (true? (:stale? (rf/resource-state {:resource :realworld/article
                                              :params   {:slug "intro"}
                                              :frame    f})))))))
```

!!! warning "Gotcha — a scope mismatch is a silent miss, and a test is where you catch it"

    A mutation's `:invalidates` matches only entries *in its resolved scope*. Invalidate a global tag while the read lives session-scoped (or the reverse) and nothing matches, nothing refreshes, and no error is raised — the [dev warning](concepts.md#writes-invalidate-by-tag--causally) fires, but a test like the one above turns the silent miss into a red assertion: `:stale?` simply never flips. If your writes cross scopes, this is the test to write per pairing.

A mutation that `:populates` asserts the other consequence — the target key reads `:loaded` with the reply's value, no second fetch. And a `:reply-to` continuation is just a dispatch: point it at a probe event that writes app-db and assert with `ts/assert-path-equals`.

## What lives elsewhere

- **The route as the cause** — a route's `:resources` entries ensure on navigation; drive them via [Testing routes](../routing/testing.md) (navigate, then read the projections here).
- **The transport underneath** — retry policies, failure categories, the reply envelope: [Managed HTTP](../async/http.md) and [Testing cascades](../core/testing/cascades.md).
- **The five statuses and their invariants** — the model these assertions lean on: [Server state: resources](concepts.md#what-a-view-sees-five-statuses). The [tutorial's Part 5](tutorial/05-test-and-ship.md) tests the whole RealWorld slice in this style.
