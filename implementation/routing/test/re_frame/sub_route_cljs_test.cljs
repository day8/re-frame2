(ns re-frame.sub-route-cljs-test
  "CLJS coverage for the `sub-route` reactive-read sugar — a thin fn over
  `(subscribe [:rf/route])` returning a reaction over the current route slice.
  It lives on the `re-frame.routing` façade (NOT `re-frame.core`) per the
  routing bundle-isolation invariant, so the test reaches it as
  `routing/sub-route`.

  Concerns covered:
    - happy path: the sugar reads the active route slice and is value-equal
      to the canonical `[:rf/route]` subscription vector form;
    - adversarial: a frame that never navigated yields a nil slice;
    - adversarial: the `{:frame f}` opts passthrough resolves the slice from
      the named frame, isolated from the ambient default frame.

  ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up.
  Per Spec 012 §Subscriptions."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; load the routing artefact so its `:rf/route` reg-sub family, the
            ;; `:rf.route/*` events, and `sub-route` itself are available.
            [re-frame.routing :as routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn routing/reset-counters!}))

(defn- stub-push-url!
  "No-op `:rf.nav/push-url` — the real handler touches browser history, which
  the node-test runtime has no `window` for. Mirrors routing_subs_test.clj."
  []
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(deftest sub-route-returns-slice-reaction
  (testing "sub-route reads the active route slice, value-equal to [:rf/route]"
    (stub-push-url!)
    (rf/reg-route :route/home {} "/home")
    (rf/dispatch-sync [:rf.route/transitioned "/home"])
    (let [slice @(routing/sub-route)]
      (is (= :route/home (:route-id slice)) "sugar reads the active route-id")
      ;; the sugar resolves through the SAME framework sub as the vector form.
      (is (= @(rf/subscribe [:rf/route]) @(routing/sub-route))
          "sub-route value == [:rf/route] subscription value"))))

(deftest sub-route-nil-before-navigation
  (testing "sub-route yields a nil slice for a frame that never navigated
            (adversarial)"
    (rf/reg-frame :sub-route/fresh {:doc "frame with no navigation"})
    (is (nil? @(routing/sub-route {:frame :sub-route/fresh}))
        "no route slice before the first navigation")))

(deftest sub-route-frame-opts-passthrough
  (testing "the {:frame f} opts passthrough resolves the slice from the named
            frame, isolated from the ambient default frame (adversarial)"
    (stub-push-url!)
    (rf/reg-route :route/alpha {} "/alpha")
    (rf/reg-route :route/beta  {} "/beta")
    (rf/reg-frame :sub-route/f2 {:doc "second frame for the passthrough test"})
    ;; land DIFFERENT routes in the default frame and in :sub-route/f2.
    (rf/dispatch-sync [:rf.route/transitioned "/alpha"])
    (rf/dispatch-sync [:rf.route/transitioned "/beta"] {:frame :sub-route/f2})
    (is (= :route/beta (:route-id @(routing/sub-route {:frame :sub-route/f2})))
        "{:frame f2} reads f2's route slice")
    (is (= @(rf/subscribe :sub-route/f2 [:rf/route])
           @(routing/sub-route {:frame :sub-route/f2}))
        "opts passthrough == the 2-arity frame-targeted vector subscription")
    (is (= :route/alpha (:route-id @(routing/sub-route)))
        "the ambient default frame keeps its own slice — frame isolation holds")))
