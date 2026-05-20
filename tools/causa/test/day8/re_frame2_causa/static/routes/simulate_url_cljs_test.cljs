(ns day8.re-frame2-causa.static.routes.simulate-url-cljs-test
  "View tests for the Static Routes Simulate-URL header surface
  (rf2-o5f5f.3).

  ## Scope

  The 6-rule rank cascade itself lives in
  `panels/routing-helpers/simulate-url` and is covered by
  `routing_helpers_cljs_test.cljc`. This file covers the VIEW layer:

    - input bound to `:rf.causa.static.routes/sim-url`;
    - clear button visible when input non-blank;
    - result block renders one candidate row per match, with the
      winner highlighted.

  Drives the view via the `panel/Panel` root so the dispatch round-
  trip lands through the registered subs/events."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.routes.panel :as panel]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers -----------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"}})

;; ---- input chrome -------------------------------------------------------

(deftest simulate-url-input-renders
  (testing "Simulate-URL input + label render when routes are present"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim"))
            "Simulate-URL section present")
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-input"))
            "Simulate-URL input present")
        (is (nil? (find-by-testid tree "rf-causa-static-routes-sim-clear"))
            "clear button absent when input blank")))))

(deftest simulate-url-clear-button-visible-when-input-set
  (testing "clear button surfaces when sim-url is non-blank"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url "/cart"]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-clear"))
            "clear button surfaces when input is non-blank")))))

;; ---- result block -------------------------------------------------------

(deftest simulate-url-renders-winner-candidate-row
  (testing "/cart resolves to :route/cart as winner"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url "/cart"]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)
            winner (find-by-testid tree "rf-causa-static-routes-sim-candidate-route/cart")]
        (is (some? winner) "winner candidate row rendered")
        (is (= "true" (:data-winner (second winner)))
            "winner row carries data-winner=\"true\"")))))

(deftest simulate-url-renders-result-block-on-no-match
  (testing "result block still surfaces when no routes match — informs the user"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url "/no-such-path"]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)
            candidates (find-all-by-testid-prefix
                         tree "rf-causa-static-routes-sim-candidate-")]
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-result"))
            "result surface still renders")
        (is (= 0 (count candidates))
            "no candidate rows when nothing matches")))))

(deftest simulate-url-blank-input-no-result
  (testing "blank input → result block absent"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      ;; Default — no sim-url set.
      (let [tree (panel/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-static-routes-sim-result"))
            "no result block when input is blank")))))

(deftest simulate-url-clear-event-empties-input
  (testing "set-sim-url with empty string drops the slot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url "/cart"]
                        {:frame :rf/causa})
      (is (= "/cart" @(rf/subscribe [:rf.causa.static.routes/sim-url])))
      (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url ""]
                        {:frame :rf/causa})
      (is (nil? @(rf/subscribe [:rf.causa.static.routes/sim-url]))
          "empty input drops the slot"))))
