(ns re-frame.sub-resource-cljs-test
  "CLJS coverage for the `sub-resource` reactive-read sugar — a thin fn over
  `(subscribe [:rf.resource/state <query>])` returning a reaction over a
  resource instance's view-model. It lives on the `re-frame.resources` façade
  (NOT `re-frame.core`) per the resources bundle-isolation invariant, so the
  test reaches it as `resources/sub-resource`.

  Concerns covered:
    - happy path: the sugar reads the post-ensure (`:loading`) resource state
      and is value-equal to the canonical `[:rf.resource/state <query>]`
      subscription vector form; `query` (the `{:resource :scope :params}` map)
      passes straight through;
    - adversarial: an un-ensured query yields the idle empty-state shape (a sub
      never fetches);
    - adversarial: the `{:frame f}` opts passthrough resolves the entry from the
      named frame, isolated from the ambient default frame.

  ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up.
  Per Spec 016 §Subscriptions."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; load-bearing side-effecting require: the façade registers the
            ;; :rf.resource/* events + subs (and `sub-resource` itself).
            [re-frame.resources :as resources]
            ;; production HTTP fx surface (so the transport feature probe
            ;; resolves); the actual fetch is overridden by the no-op below.
            [re-frame.http.managed]
            [re-frame.schemas]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a no-op so `:rf.resource/ensure`
  writes its `:loading` entry deterministically without a live fetch. Composed
  INSIDE the reset-runtime fixture (one `use-fixtures` call)."
  [f]
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (f))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter})
  capturing-transport-fixture)

(defn- article-spec []
  {:scope         :rf.scope/global
   :params-schema [:map [:slug :string]]
   :tags          (fn [{:keys [slug]} _data] #{[:article slug]})})

(defn- article-request [{:keys [slug]} _ctx]
  {:request {:method :get :url (str "/api/articles/" slug)}})

(def ^:private query
  {:resource :sub/article :scope :rf.scope/global :params {:slug "w"}})

(defn- ensure!
  "Ensure the article resource (→ `:loading`) under an explicit lease owner, in
  the frame named by `opts` (default the ambient frame)."
  ([] (ensure! {}))
  ([opts]
   (rf/dispatch-sync [:rf.resource/ensure
                      {:resource :sub/article :scope :rf.scope/global
                       :params {:slug "w"} :owner [:lease :s 1]}]
                     opts)))

(deftest sub-resource-returns-state-reaction
  (testing "sub-resource reads the post-ensure :loading state, value-equal to
            [:rf.resource/state <query>]"
    (rf/reg-resource :sub/article (article-spec) article-request)
    (ensure!)
    (let [state @(resources/sub-resource query)]
      (is (= :loading (:status state)) "the sugar reads the post-ensure :status")
      (is (true? (:loading? state)) "the derived :loading? boolean is projected")
      ;; the sugar passes the query straight through to the SAME framework sub
      ;; as the vector form.
      (is (= @(rf/subscribe [:rf.resource/state query])
             @(resources/sub-resource query))
          "sub-resource value == [:rf.resource/state <query>] value"))))

(deftest sub-resource-un-ensured-idle-state
  (testing "sub-resource yields the idle empty-state for an un-ensured query
            (adversarial — a sub never fetches)"
    (rf/reg-resource :sub/article (article-spec) article-request)
    (let [state @(resources/sub-resource query)]
      (is (= :idle (:status state)) "un-ensured query → idle status")
      (is (false? (:loading? state)) "idle is not loading")
      (is (false? (:has-data? state)) "idle has no data")
      (is (= @(rf/subscribe [:rf.resource/state query])
             @(resources/sub-resource query))
          "idle empty-state value == the vector form"))))

(deftest sub-resource-frame-opts-passthrough
  (testing "the {:frame f} opts passthrough resolves the entry from the named
            frame, isolated from the ambient default frame (adversarial)"
    (rf/reg-resource :sub/article (article-spec) article-request)
    (rf/reg-frame :sub-resource/f2 {:doc "second frame for the passthrough test"})
    ;; ensure the resource ONLY in :sub-resource/f2 — the default frame never
    ;; ensures it, so its entry stays idle.
    (ensure! {:frame :sub-resource/f2})
    (is (= :loading (:status @(resources/sub-resource query {:frame :sub-resource/f2})))
        "{:frame f2} reads the entry ensured in f2")
    (is (= @(rf/subscribe :sub-resource/f2 [:rf.resource/state query])
           @(resources/sub-resource query {:frame :sub-resource/f2}))
        "opts passthrough == the 2-arity frame-targeted vector subscription")
    (is (= :idle (:status @(resources/sub-resource query)))
        "the ambient default frame has no such entry — frame isolation holds")))
