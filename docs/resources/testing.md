# Testing resources

You can [author](concepts.md) and [build](tutorial/index.md) resources. This page is
how you **prove** the cache.

Resources split into three lanes — register, cause, project — and that split is
what makes them testable: a test *causes* with an ordinary dispatch, answers the
network with the same canned replies a [pipeline-run test](../core/testing/pipeline-runs.md)
uses, and *reads* the outcome through projections. No live server, no waiting, no
browser.

> **Cause with a dispatch, answer with a canned reply, read the cache projection.**

A test reads what a view reads — the `:rf/resource` and `:rf/mutation` subscriptions — minus the reactive runtime: `rf/compute-sub` computes a subscription against `rf/frame-state-value`, which carries both [partitions](../core/glossary.md#the-two-partitions), because resource entries and mutation instances live in runtime-db ([Test a subscription](../core/testing/subscriptions.md)). The booleans a view branches on — `:has-data?`, `:stale?`, `:success?` — exist only there. `rf/resource-state` and `rf/mutation-state` are the tool/test snapshot of the durable runtime row underneath: facts such as `:status`, `:data` and `:error`, never derived booleans, so a boolean read off them is `nil`. The setup every test below shares:

```clojure
(ns my-app.resources-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.test-support :as http-test-support]     ;; canned stubs — test-only, never in production requires
            [re-frame.substrate.plain-atom :as plain-atom]         ;; the JVM substrate a frame needs
            [re-frame.test-support :as ts]
            [my-app.resources]))             ;; loading the ns registers the resources

(use-fixtures :each                            ;; resets the resource caches too
  (ts/make-reset-runtime-fixture {:adapter       plain-atom/adapter
                                  :ambient-frame nil}))   ;; nil: each test makes its own frame
```

## 1. A read, end to end

Ensure is the cause; the stub answers; the projection settles — all inside one `dispatch-sync` drain:

```clojure
(deftest article-loads-into-the-cache
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/articles/intro"]
       {:reply {:ok {:article {:slug "intro" :title "Welcome"}}}}}
      (fn []
        (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/article
                                                :params   {:slug "intro"}
                                                :cause    [:manual :test/setup]}])
        (let [state (rf/compute-sub [:rf/resource {:resource :realworld/article
                                                   :params   {:slug "intro"}}]
                                    (rf/frame-state-value f))]
          (is (= :loaded (:status state)))
          (is (true? (:has-data? state)))
          (is (= "Welcome" (get-in state [:data :article :title]))))))))
```

The registration under test is the same shape as [the model](concepts.md#register-a-resource)
and [tutorial Part 2](tutorial/02-server-data.md). Three contracts worth pinning:

- **A subscription never fetches.** Read the state *before* any cause fires and it's `:idle` — a test that asserts that is the honest check for "my view was a permanent skeleton because I forgot the cause."
- **A first-load failure is `:error` with no data.** Stub `{:reply {:failure {:kind :rf.http/http-5xx :status 503}}}` and assert `:status :error`, `:has-data? false`, and the failure's `:kind` — [branch on the category, never the prose](../core/errors.md#test-the-structure-not-the-string).
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

A write's cache consequences are declared (`:invalidates`, `:populates`), so the test drives the write and asserts the consequence. The read it invalidates is *setup*, not the subject — so it rides the frame's `:initial-events`, with the stub table wrapped around frame creation (stubs bind for their dynamic extent, and the seed fetches as the frame boots). Staleness is the observable: an entry with no live owner is *marked stale* by an invalidation rather than refetched, which makes `:stale?` the clean assertion. Watch the list, not the article: the favorite [registered in tutorial Part 4](tutorial/04-mutations-and-invalidation.md#register-the-write) `:populates` the article from its reply, and a populated entry counts as freshly loaded:

```clojure
(deftest favorite-invalidates-the-list
  (http-test-support/with-request-stubs
    {[:get  "/api/articles"]                {:reply {:ok {:articles [{:slug "intro"}]}}}
     [:post "/api/articles/intro/favorite"] {:reply {:ok {:article {:slug "intro" :favorited true}}}}}
    (fn []
      ;; the frame boots with the list already loaded — through the canned reply
      (rf/with-new-frame [f (rf/make-frame
                              {:initial-events
                               [[:rf.resource/ensure {:resource :realworld/articles
                                                      :params   {}
                                                      :cause    [:manual :test/setup]}]]})]
        ;; run the write…
        (rf/dispatch-sync [:rf.mutation/execute {:mutation :realworld/favorite
                                                 :params   {:slug "intro"}
                                                 :instance [:favorite "intro"]
                                                 :cause    [:manual :test/favorite]}])
        ;; …and assert both sides: the instance settled, the list went stale.
        (is (true? (:success? (rf/compute-sub [:rf/mutation {:instance [:favorite "intro"]}]
                                              (rf/frame-state-value f)))))
        (is (true? (:stale? (rf/compute-sub [:rf/resource {:resource :realworld/articles :params {}}]
                                            (rf/frame-state-value f)))))))))
```

!!! warning "Gotcha — a scope mismatch is a silent miss, and a test is where you catch it"

    A mutation's `:invalidates` matches only entries *in its resolved scope*. Invalidate a global tag while the read lives session-scoped (or the reverse) and nothing matches, nothing refreshes, and no error is raised — the [dev warning](concepts.md#writes-invalidate-by-tag--causally) fires, but a test like the one above turns the silent miss into a red assertion: `:stale?` simply never flips. If your writes cross scopes, this is the test to write per pairing.

A mutation that `:populates` asserts the other consequence — the target key reads `:loaded` with the reply's value, no second fetch. And a `:reply-to` continuation is just a dispatch: point it at a probe event that writes app-db and assert with `ts/assert-path-equals`.

## 4. Exactly these requests

The stub table answers requests; it doesn't count them. When the claim is about *reach* — a favourite costs one POST plus the refetches its invalidation earns, and nothing more — keep a ledger. Register an HTTP interceptor whose `:before` appends each outgoing request: every request the managed pipeline issues runs the frame's `:before` chain, stubbed or real, so three lines see all of them with no test-only helper.

```clojure
(deftest favorite-issues-exactly-these-requests
  (http-test-support/with-request-stubs
    {[:get  "/api/articles"]                {:reply {:ok {:articles [{:slug "intro"}]}}}
     [:get  "/api/articles/feed"]           {:reply {:ok {:articles [{:slug "intro"}]}}}
     [:get  "/api/articles/intro"]          {:reply {:ok {:article {:slug "intro"}}}}
     [:post "/api/articles/intro/favorite"] {:reply {:ok {:article {:slug "intro" :favorited true}}}}}
    (fn []
      ;; a signed-in reader on a page that owns the list, the feed and the detail
      (rf/with-new-frame [f (rf/make-frame
                              {:initial-events
                               (into [[:rf/set-db {:auth {:user {:username "alice"} :token "jwt"}}]]
                                     (for [[resource params] [[:realworld/articles {}]
                                                              [:realworld/feed {}]
                                                              [:realworld/article {:slug "intro"}]]]
                                       [:rf.resource/ensure {:resource resource
                                                             :params   params
                                                             :owner    [:test :page]
                                                             :cause    [:manual :test/setup]}]))})]
        (let [issued (atom [])]
          ;; the ledger: every request the managed pipeline issues runs :before once
          (rf/reg-http-interceptor :test/issued
            {:before (fn [ctx] (swap! issued conj ((juxt :method :url) (:request ctx))) ctx)})
          (rf/dispatch-sync [:rf.mutation/execute {:mutation :realworld/favorite
                                                   :params   {:slug "intro"}
                                                   :instance [:favorite "intro"]
                                                   :cause    [:manual :test/favorite]}])
          ;; one POST; the list and the feed refetch; the detail took the reply instead
          (is (= {[:post "/api/articles/intro/favorite"] 1
                  [:get  "/api/articles"]                1
                  [:get  "/api/articles/feed"]           1}
                 (frequencies @issued))))))))
```

`:realworld/favorite` here is registered the way [tutorial Part 4](tutorial/04-mutations-and-invalidation.md#register-the-write) registers its favorite: `:populates` seeds the detail from the POST's reply, and `:invalidates` stales the list (viewer scope) and the feed (session scope). So the answer is one POST and two GETs. The detail is missing from the ledger because the reply populated it, and a mutation's own invalidation skips a key it populated — drop `:populates` and this same test fails with a third GET, for `/api/articles/intro`. The ensures carry an `:owner` because only an owned entry refetches when invalidated; an unowned one is just marked stale.

!!! note "Why not record through `rf/with-fx-overrides`?"

    Overriding `:rf.http/managed` with a recording fn *replaces* the transport, so nothing replies: the ledger shows the POST, the mutation stays `:pending`, and the refetches its reply would cause never happen. `with-request-stubs` is built on that same override, so the two don't stack either — whichever binding is innermost wins. The interceptor sits inside the pipeline instead, so the stubs keep answering.

## What lives elsewhere

- **The route as the cause** — a route's `:resources` entries ensure on navigation; drive them via [Testing routes](../routing/testing.md) (navigate, then read the projections here).
- **The transport underneath** — retry policies, failure categories, the reply envelope: [Managed HTTP](../async/http.md) and [Test a pipeline run](../core/testing/pipeline-runs.md).
- **The five statuses and their invariants** — the model these assertions lean on: [Server state: resources](concepts.md#what-a-view-sees-five-statuses). The [tutorial's Part 5](tutorial/05-test-and-ship.md) tests the whole RealWorld slice in this style.
