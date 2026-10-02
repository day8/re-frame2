# Testing resources

This page shows how to test the cache: that a read loads, that a write invalidates
what it should, and that it sends exactly the requests you expect.

Dispatch the operation under test, answer the network with canned replies, and
assert the same subscriptions a view reads. These JVM tests need no live server.

A test reads the `:rf/resource` and `:rf/mutation` subscriptions without the reactive runtime: `rf/compute-sub` computes a subscription against `rf/frame-state-value`, which carries both [partitions](../core/glossary.md#the-two-partitions), because resource entries and mutation instances live in runtime-db ([Test a subscription](../core/testing/subscriptions.md)). The booleans a view branches on — `:has-data?`, `:stale?`, `:success?` — exist only in those subscriptions.

`rf/resource-state` and `rf/mutation-state` return the raw runtime row instead: facts such as `:status`, `:data` and `:error`, with no derived booleans, so a boolean read off them is `nil`. Prefer the subscriptions.

The setup every test below shares:

```clojure
(ns my-app.resources-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]
            [re-frame.http.test-support :as http-test-support]     ;; canned stubs — test-only, never in production requires
            [re-frame.substrate.plain-atom :as plain-atom]         ;; the JVM substrate a frame needs
            [re-frame.test-support :as ts]
            [re-frame.schemas]))
```

The snippets form one test namespace. Use the core, resources, HTTP and schemas
artefacts on its classpath; [Set up the test runner](../core/testing/index.md#set-up-the-test-runner)
shows the test alias. These small registrations make every example below
self-contained. In your application, require the production registrations instead.

```clojure
(rf/reg-resource :test/article
  {:scope :rf.scope/global
   :params-schema [:map [:slug :string]]
   :tags (fn [{:keys [slug]} _data] #{[:article slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)} :decode :json}))

(rf/reg-resource :test/articles
  {:scope :rf.scope/global :params-schema [:map]
   :tags (fn [_params _data] #{[:article-list]})}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/articles"} :decode :json}))

(rf/reg-resource-scope :test/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username [:rf.scope/session {:username username}])))

(rf/reg-mutation :test/favorite
  {:params-schema [:map [:slug :string]]
   :populates (fn [{:keys [slug]} result]
                {{:resource :test/article :params {:slug slug}
                  :scope :rf.scope/global} result})
   :invalidates (fn [{:keys [slug]} _result]
                  #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode :json}))
```

Install the fixture **after** these registrations: it captures their baseline,
resets each frame around a test, and restores registrations a test changes.

```clojure
(use-fixtures :each                            ;; resets the resource caches too
  (ts/make-reset-runtime-fixture {:adapter       plain-atom/adapter
                                  :ambient-frame nil}))   ;; nil: each test makes its own frame
```

The resources here are public, so global scope is deliberate. A real application's
viewer-specific flags, such as `favorited`, need the scoped registrations taught
in [tutorial Part 5](tutorial/05-mutations-and-invalidation.md#register-the-write).

## 1. A read, end to end

Ensure is the cause; the stub answers; the projection settles — all inside one `dispatch-sync` drain:

```clojure
(deftest article-loads-into-the-cache
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/articles/intro"]
       {:reply {:ok {:article {:slug "intro" :title "Welcome"}}}}}
      (fn []
        (rf/dispatch-sync [:rf.resource/ensure {:resource :test/article
                                                :params   {:slug "intro"}
                                                :cause    [:manual :test/setup]}])
        (let [state (rf/compute-sub [:rf/resource {:resource :test/article
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
- **A fresh ensure is a cache hit.** Ensure the same key twice against a stub table and the second dispatch fetches nothing — count the requests with the interceptor ledger in [section 4](#4-exactly-these-requests) to pin it. A request that matches no stub never reaches the network: it settles as a `:rf.http/transport` failure ("no stub matched").

Pin the distinction between initial failure and refresh failure with one more
test. Both failures use the same HTTP category; only the available data differs:

```clojure
(deftest failure-preserves-only-data-that-already-loaded
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [query [:rf/resource {:resource :test/article :params {:slug "intro"}}]
          fetch! #(rf/dispatch-sync
                    [:rf.resource/refetch {:resource :test/article :params {:slug "intro"}
                                           :cause [:manual :test/retry]}])
          state #(rf/compute-sub query (rf/frame-state-value f))
          unavailable {[:get "/api/articles/intro"]
                       {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}}]
      (is (= :idle (:status (state))))
      (http-test-support/with-request-stubs unavailable fetch!)
      (is (= :error (:status (state))))
      (is (false? (:has-data? (state))))
      (http-test-support/with-request-stubs
        {[:get "/api/articles/intro"] {:reply {:ok {:article {:title "Welcome"}}}}}
        fetch!)
      (http-test-support/with-request-stubs unavailable fetch!)
      (is (= :loaded (:status (state))))
      (is (= "Welcome" (get-in (state) [:data :article :title])))
      (is (nil? (:error (state))))
      (is (= :rf.http/http-5xx (get-in (state) [:refresh-error :kind]))))))
```

## 2. Scope resolvers are pure

A [scope resolver](concepts.md#the-scoped-key-a-leak-boundary-that-fails-closed) derives "whose cache?" from app-db, and `rf/resolve-resource-scope` runs it against any db value with no dispatch and no runtime — so the leak boundary unit-tests as a plain function, including its fail-closed edge:

```clojure
(deftest session-scope-resolves-and-fails-closed
  ;; logged in → the session scope key
  (is (= [:rf.scope/session {:username "alice"}]
         (rf/resolve-resource-scope {:auth {:user {:username "alice"}}}
                                    :test/session)))
  ;; logged out → nil, never a fall-through to a shared cache
  (is (nil? (rf/resolve-resource-scope {} :test/session))))
```

Returning `nil` makes the missing identity explicit. A subscription raises an unresolved-scope error; route planning refuses that read. Resolve first and skip `clear-scope` when the result is `nil`, as the logout example does.

## 3. Invalidation and mutations

A write's cache consequences are declared (`:invalidates`, `:populates`), so the test drives the write and asserts the consequence.

The read it invalidates is *setup*, not the subject, so it goes in the frame's `:initial-events`. The stub table wraps frame creation, because stubs apply for their dynamic extent and the seed fetches as the frame boots. An entry with no owner is *marked stale* by an invalidation rather than refetched, which makes `:stale?` the clean assertion. Watch the list, not the article: the favorite registered above `:populates` the article from its reply, and a populated entry counts as freshly loaded:

```clojure
(deftest favorite-invalidates-the-list
  (http-test-support/with-request-stubs
    {[:get  "/api/articles"]                {:reply {:ok {:articles [{:slug "intro"}]}}}
     [:post "/api/articles/intro/favorite"] {:reply {:ok {:article {:slug "intro" :favorited true}}}}}
    (fn []
      ;; the frame boots with the list already loaded — through the canned reply
      (rf/with-new-frame [f (rf/make-frame
                              {:initial-events
                               [[:rf.resource/ensure {:resource :test/articles
                                                      :params   {}
                                                      :cause    [:manual :test/setup]}]]})]
        ;; run the write…
        (rf/dispatch-sync [:rf.mutation/execute {:mutation :test/favorite
                                                 :params   {:slug "intro"}
                                                 :instance [:favorite "intro"]
                                                 :cause    [:manual :test/favorite]}])
        ;; …and assert both sides: the instance settled, the list went stale.
        (is (true? (:success? (rf/compute-sub [:rf/mutation {:instance [:favorite "intro"]}]
                                              (rf/frame-state-value f)))))
        (is (true? (:stale? (rf/compute-sub [:rf/resource {:resource :test/articles :params {}}]
                                            (rf/frame-state-value f)))))))))
```

!!! warning "Gotcha — a scope mismatch is a silent miss, and a test is where you catch it"

    A mutation's `:invalidates` matches only entries *in its resolved scope*. Invalidate a global tag while the read lives session-scoped (or the reverse) and nothing matches, nothing refreshes, and no error is raised — the [dev warning](concepts.md#writes-invalidate-by-tag--causally) fires, but a test like the one above turns the silent miss into a red assertion: `:stale?` simply never flips. If your writes cross scopes, this is the test to write per pairing.

A mutation that `:populates` asserts the other consequence — the target key reads `:loaded` with the reply's value, no second fetch. And a `:reply-to` continuation is just a dispatch: point it at a probe event that writes app-db and assert with `ts/assert-path-equals`.

## 4. Exactly these requests

The stub table answers requests; it doesn't count them. When the claim is about *reach* — a favorite costs one POST plus the refetch its invalidation earns, and nothing more — keep a ledger. Register an HTTP interceptor whose `:before` appends each outgoing request: every request the managed pipeline issues runs the frame's `:before` chain, stubbed or real, so three lines see all of them with no test-only helper.

```clojure
(deftest favorite-issues-exactly-these-requests
  (http-test-support/with-request-stubs
    {[:get  "/api/articles"]                {:reply {:ok {:articles [{:slug "intro"}]}}}
     [:get  "/api/articles/intro"]          {:reply {:ok {:article {:slug "intro"}}}}
     [:post "/api/articles/intro/favorite"] {:reply {:ok {:article {:slug "intro" :favorited true}}}}}
    (fn []
      (rf/with-new-frame [f (rf/make-frame
                              {:initial-events
                               (mapv (fn [[resource params]]
                                       [:rf.resource/ensure
                                        {:resource resource :params params
                                         :owner [:test :page] :cause [:manual :test/setup]}])
                                     [[:test/articles {}]
                                      [:test/article {:slug "intro"}]])})]
        (let [issued (atom [])]
          ;; Count only the write and its consequences, after setup has loaded.
          (rf/reg-http-interceptor :test/issued
            {:before (fn [ctx]
                       (swap! issued conj ((juxt :method :url) (:request ctx)))
                       ctx)})
          (rf/dispatch-sync [:rf.mutation/execute
                             {:mutation :test/favorite :params {:slug "intro"}
                              :instance [:favorite "intro"] :cause [:manual :test/favorite]}])
          (is (= {[:post "/api/articles/intro/favorite"] 1
                  [:get "/api/articles"] 1}
                 (frequencies @issued))))))))
```

The answer is one POST and one GET. The detail is absent from the ledger because
the reply populated it, and a mutation's own invalidation skips keys it populated.
Drop `:populates` and this test fails with a second GET, for `/api/articles/intro`.
The ensures carry an `:owner` because only an owned entry refetches on invalidation;
an unowned entry is marked stale, as section 3 asserts.

!!! note "Why not record through `rf/with-fx-overrides`?"

    Overriding `:rf.http/managed` with a recording fn *replaces* the transport, so nothing replies: the ledger shows the POST, the mutation stays `:pending`, and the refetches its reply would cause never happen. `with-request-stubs` is built on that same override, so the two don't stack either — whichever binding is innermost wins. The interceptor sits inside the pipeline instead, so the stubs keep answering.

## What lives elsewhere

- **The route as the cause** — a route's `:resources` entries ensure on navigation; drive them via [Testing routes](../routing/testing.md) (navigate, then read the projections here).
- **The transport underneath** — retry policies, failure categories, the reply envelope: [Managed HTTP](../async/http.md) and [Test a pipeline run](../core/testing/pipeline-runs.md).
- **The five statuses and their invariants** — the model these assertions lean on: [the model](concepts.md#what-a-view-sees-five-statuses). The [tutorial's Part 6](tutorial/06-test-and-ship.md) tests the whole RealWorld slice in this style.
