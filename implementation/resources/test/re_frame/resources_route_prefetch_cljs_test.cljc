(ns re-frame.resources-route-prefetch-cljs-test
  "EP-0037 R3 — the Resources-side WARM-mode prefetch plan
  (`re-frame.resources.route/route-resource-warm-plan` + `on-route-prefetch-fx`,
  published as `:routing/on-route-prefetch`).

  Directly asserts the warm-mode DELTA over an activation plan: every ensure is
  OWNERLESS (no `:owner` → GC-eligible), carries cause `[:route-prefetch
  route-id]`, and `:blocking?` is inert (no blocking classification threads onto
  the ensure); a planning failure carries `:plan-cause :prefetch` and NO
  `:nav-token`, and dispatches no partial ensures. The end-to-end behaviour
  (isolation, dedupe, reuse-on-activation) is proven by the
  `ep-0037-r3-prefetch-*` conformance fixtures. Per Spec 016 §Route-plan
  prefetch — warm-mode.

  Dual-target (`.cljc`): the JVM runner picks it up via the `.*-test$` ns regex;
  Shadow's `:node-test` via `cljs-test$` — the warm plan is host-neutral. The
  `-cljs-test` suffix is load-bearing: a dual-target file whose ns ends in a
  plain `-test` compiles nowhere but the JVM and reads as covered (rf2-dn6v7)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   ;; load-bearing side-effecting requires: register the resources runtime + the
   ;; late-bound :routing/* integration hooks.
   [re-frame.resources :as rf.resources]
   [re-frame.resources.route :as rf.resources.route]))

(def ^:private res-id :prefetch/warm-res)

(defn- with-resource [f]
  (rf.resources/reg-resource
    res-id
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_params _ctx] {:request {:method :get :url "/api/warm"}}))
  (try (f)
       (finally (rf.resources/clear-resource res-id))))

(use-fixtures :each with-resource)

(defn- branch-with-warm-res [route-id]
  [{:route-id   route-id
    :route-meta {:resources [{:resource res-id :blocking? true}]}}])

(deftest warm-plan-ensures-are-ownerless-cause-only-and-blocking-inert
  (let [{:keys [fx warmed plan-error]}
        (rf.resources.route/on-route-prefetch-fx
          {:route-id     :route/article
           :params       {}
           :query        {}
           :fragment     nil
           :app-db       {}
           :branch       (branch-with-warm-res :route/article)
           :branch-error nil})
        [dispatch-fx] fx
        [_ [ensure-id payload]] dispatch-fx]
    (testing "exactly one ownerless ensure was planned"
      (is (nil? plan-error))
      (is (= 1 warmed))
      (is (= 1 (count fx)))
      (is (= :dispatch (first dispatch-fx)))
      (is (= :rf.resource/ensure ensure-id)))
    (testing "the ensure is OWNERLESS (GC-eligible) with the warm cause"
      (is (not (contains? payload :owner))
          "a warm ensure carries NO :owner so the entry stays GC-eligible")
      (is (= [:route-prefetch :route/article] (:cause payload))))
    (testing ":blocking? is INERT — it never threads onto the warm ensure"
      (is (not (contains? payload :blocking?)))
      (is (not (contains? payload :keep-previous?))))
    (testing "the ensure targets the declared resource + spec-policy scope"
      (is (= res-id (:resource payload)))
      (is (= :rf.scope/global (:scope payload)))
      (is (= {} (:params payload))))))

(deftest warm-plan-branch-error-is-a-prefetch-planning-failure
  (let [{:keys [fx warmed plan-error]}
        (rf.resources.route/route-resource-warm-plan
          {:id :route/child}
          {:app-db       {}
           :branch       [{:route-id :route/child :route-meta nil}]
           :branch-error {:kind :unknown-parent :route-id* :route/ghost}})]
    (testing "no partial ensures are dispatched"
      (is (= [] fx))
      (is (= 0 warmed)))
    (testing "the planning failure carries :plan-cause :prefetch and NO nav-token"
      (is (some? plan-error))
      (is (= :prefetch (:plan-cause plan-error)))
      (is (not (contains? plan-error :nav-token))
          "a warm-mode failure owns no route state — no nav-token slot")
      (is (= :rf.error/resource-route-plan (:rf.error/id plan-error)))
      (is (= :route/child (:route-id plan-error))))))

(deftest warm-plan-is-a-noop-when-no-branch-contributor-declares-resources
  (testing "a resource-free branch warms nothing (nil hook result — no work)"
    (is (nil? (rf.resources.route/on-route-prefetch-fx
                {:route-id     :route/plain
                 :params       {}
                 :query        {}
                 :fragment     nil
                 :app-db       {}
                 :branch       [{:route-id :route/plain :route-meta nil}]
                 :branch-error nil})))))
