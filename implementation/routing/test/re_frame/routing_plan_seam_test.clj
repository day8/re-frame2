(ns re-frame.routing-plan-seam-test
  "Focused tests for the ONE resolved-target / route-plan seam
  `re-frame.routing.resolve` (EP-0037 R0b).

  Pins the ResolvedTarget fact shape, the route-plan every door builds
  (`:source` / `:cause` / `:target` / `:branch` / `:leaf-plan`), the
  parent-to-leaf branch derivation (shared with the `:rf.route/chain` sub),
  the behaviour-preserving leaf resource plan (the route's `:on-match`
  loaders), and the R0 diagnostic projection. Per Spec 012 §The one planning
  pipeline and §Resolved target and the plan diagnostic projection."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.routing :as routing]
            [re-frame.routing.resolve :as resolver]
            [re-frame.routing.subs :as routing-subs]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- ResolvedTarget: facts, not intent ------------------------------------

(deftest resolved-target-reflects-facts-verbatim
  (testing "the ResolvedTarget carries the resolved FACTS (facts say :route-id, intent says :to)"
    (is (= {:route-id :route/article
            :params   {:slug "routing-as-data"}
            :query    {:tab "comments"}
            :fragment "reply-42"
            :url      "/articles/routing-as-data?tab=comments#reply-42"}
           (resolver/resolved-target
             {:route-id :route/article
              :params   {:slug "routing-as-data"}
              :query    {:tab "comments"}
              :fragment "reply-42"
              :url      "/articles/routing-as-data?tab=comments#reply-42"}))))
  (testing "resolved-target never re-normalises — an empty :query stays {} exactly as the door resolved it"
    (is (= {} (:query (resolver/resolved-target {:route-id :route/home :params {} :query {}}))))))

;; ---- branch + leaf plan ---------------------------------------------------

(deftest branch-of-is-the-parent-to-leaf-chain
  (routing/reg-route :route/dashboard {} "/dashboard")
  (routing/reg-route :route/reports {:parent :route/dashboard} "/dashboard/reports")
  (routing/reg-route :route/report {:parent :route/reports} "/dashboard/reports/:id")
  (testing "the plan branch is [parent-most … leaf], shared with the :rf.route/chain sub reduction"
    (is (= [:route/dashboard :route/reports :route/report]
           (resolver/branch-of :route/report)))
    (is (= (routing-subs/chain-from-meta :route/report)
           (resolver/branch-of :route/report))
        "branch-of and the chain sub can never disagree — one :parent walk")))

(deftest leaf-plan-of-is-the-behaviour-preserving-on-match-loader
  (routing/reg-route :route/article
    {:on-match [[:article/load] [:comments/load]]} "/articles/:slug")
  (routing/reg-route :route/home {} "/")
  (testing "the leaf plan is the route's :on-match loader vector (the loaders that already fire)"
    (is (= [[:article/load] [:comments/load]] (resolver/leaf-plan-of :route/article))))
  (testing "a route with no :on-match has an empty leaf plan"
    (is (= [] (resolver/leaf-plan-of :route/home))))
  (testing "an unregistered / not-found target has an empty leaf plan"
    (is (= [] (resolver/leaf-plan-of :rf.route/not-found)))))

;; ---- the route plan every door builds -------------------------------------

(deftest route-plan-carries-source-cause-target-branch-leaf-plan
  (routing/reg-route :route/section {} "/section")
  (routing/reg-route :route/article
    {:parent :route/section :on-match [[:article/load]]} "/section/:slug")
  (let [target (resolver/resolved-target
                 {:route-id :route/article :params {:slug "x"} :query {}
                  :fragment nil :url "/section/x"})
        plan   (resolver/route-plan {:source {:to :route/article :params {:slug "x"}}
                                     :cause  :navigate
                                     :target target})]
    (testing "the plan carries the caller's source and cause"
      (is (= {:to :route/article :params {:slug "x"}} (:source plan)))
      (is (= :navigate (:cause plan))))
    (testing "the plan's :target IS the ResolvedTarget the door commits (load-bearing, not a copy)"
      (is (= target (:target plan))))
    (testing "the plan derives the parent-to-leaf branch and the leaf resource plan from the target"
      (is (= [:route/section :route/article] (:branch plan)))
      (is (= [[:article/load]] (:leaf-plan plan))))))

(deftest route-plan-is-cause-parametric-across-the-doors
  (routing/reg-route :route/home {} "/")
  (let [target (resolver/resolved-target {:route-id :route/home :params {} :query {} :url "/"})]
    (testing "every door builds the plan through the same fn, differing ONLY in cause"
      (doseq [cause resolver/causes]
        (let [plan (resolver/route-plan {:source {:url "/"} :cause cause :target target})]
          (is (= cause (:cause plan)))
          (is (= target (:target plan)))
          (is (= [:route/home] (:branch plan))))))))

;; ---- the R0 diagnostic projection -----------------------------------------

(deftest plan-projection-exposes-only-the-r0-keys
  (routing/reg-route :route/home {} "/")
  (let [plan (resolver/route-plan
               {:source {:url "/"} :cause :popstate
                :target (resolver/resolved-target {:route-id :route/home :params {} :query {} :url "/"})})
        proj (resolver/plan-projection plan)]
    (testing "the projection is exactly the R0 keys — the minimum needed to prove the shared spine"
      (is (= #{:source :cause :target :branch :leaf-plan} (set (keys proj))))
      (is (= (set resolver/r0-projection-keys) (set (keys proj)))))
    (testing "the projection is a pure view of the plan the doors already build"
      (is (= (select-keys plan resolver/r0-projection-keys) proj)))))
