(ns re-frame.sub-mutation-cljs-test
  "CLJS coverage for the `sub-mutation` reactive-read sugar — a thin fn over
  `(subscribe [:rf.mutation/state {:instance <instance>}])` returning a
  reaction over a mutation instance's view-model. It lives on the
  `re-frame.resources` façade (NOT `re-frame.core`) per the resources
  bundle-isolation invariant, so the test reaches it as
  `resources/sub-mutation`.

  Concerns covered:
    - happy path: the sugar reads the post-execute (`:pending`) instance state
      and is value-equal to the canonical `[:rf.mutation/state {:instance …}]`
      subscription vector form; the `{:instance …}` wrapping lives inside the
      sugar (callers pass just the instance);
    - adversarial: an unknown instance id yields the idle empty-state shape
      (the sub never errors / fetches);
    - adversarial: the `{:frame f}` opts passthrough resolves the instance from
      the named frame, isolated from the ambient default frame.

  ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up.
  Per EP-0003 §Mutations."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; load-bearing side-effecting require: the façade registers the
            ;; :rf.mutation/* events + subs (and `sub-mutation` itself).
            [re-frame.resources :as resources]
            ;; production HTTP fx surface (so the transport feature probe
            ;; resolves); the actual fetch is overridden by the no-op below.
            [re-frame.http.managed]
            [re-frame.schemas]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a no-op so `:rf.mutation/execute`
  mints a `:pending` instance deterministically without a live fetch. Composed
  INSIDE the reset-runtime fixture (one `use-fixtures` call)."
  [f]
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (f))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter})
  capturing-transport-fixture)

(defn- save-article-request
  [{:keys [slug]} _ctx]
  {:request {:method :put :url (str "/api/articles/" slug) :body {:slug slug}}})

(defn- reg-save-mutation! []
  (rf/reg-mutation :m/save {:params-schema [:map [:slug :string]]} save-article-request))

(deftest sub-mutation-returns-instance-reaction
  (testing "sub-mutation reads the post-execute :pending instance state,
            value-equal to [:rf.mutation/state {:instance …}]"
    (reg-save-mutation!)
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :form/save-1}])
    (let [state @(resources/sub-mutation :form/save-1)]
      (is (= :pending (:status state)) "the sugar reads the post-execute :status")
      (is (true? (:pending? state)) "the derived :pending? boolean is projected")
      ;; the sugar wraps the bare instance in {:instance …} and resolves
      ;; through the SAME framework sub as the vector form.
      (is (= @(rf/subscribe [:rf.mutation/state {:instance :form/save-1}])
             @(resources/sub-mutation :form/save-1))
          "sub-mutation value == [:rf.mutation/state {:instance …}] value"))))

(deftest sub-mutation-unknown-instance-idle-state
  (testing "sub-mutation yields the idle empty-state for an unknown instance id
            (adversarial — a sub never errors / fetches)"
    (let [state @(resources/sub-mutation :form/never)]
      (is (= :idle (:status state)) "unknown instance → idle status")
      (is (false? (:pending? state)) "idle is not pending")
      (is (= @(rf/subscribe [:rf.mutation/state {:instance :form/never}])
             @(resources/sub-mutation :form/never))
          "idle empty-state value == the vector form"))))

(deftest sub-mutation-frame-opts-passthrough
  (testing "the {:frame f} opts passthrough resolves the instance from the
            named frame, isolated from the ambient default frame (adversarial)"
    (reg-save-mutation!)
    (rf/reg-frame :sub-mutation/f2 {:doc "second frame for the passthrough test"})
    ;; execute the mutation ONLY in :sub-mutation/f2 — the default frame never
    ;; runs it, so its instance row stays idle.
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :form/save-2}]
                      {:frame :sub-mutation/f2})
    (is (= :pending (:status @(resources/sub-mutation :form/save-2
                                                      {:frame :sub-mutation/f2})))
        "{:frame f2} reads the instance that ran in f2")
    (is (= @(rf/subscribe :sub-mutation/f2 [:rf.mutation/state {:instance :form/save-2}])
           @(resources/sub-mutation :form/save-2 {:frame :sub-mutation/f2}))
        "opts passthrough == the 2-arity frame-targeted vector subscription")
    (is (= :idle (:status @(resources/sub-mutation :form/save-2)))
        "the ambient default frame has no such instance — frame isolation holds")))
