(ns day8.re-frame2-xray.static.routes.simulate-url-cljs-test
  "View tests for the Static Routes Simulate-URL header surface
  (rf2-o5f5f.3).

  ## Scope

  The 6-rule rank cascade itself lives in
  `panels/routing-helpers/simulate-url` and is covered by
  `routing_helpers_cljs_test.cljc`. This file covers the VIEW layer:

    - input bound to `:rf.xray.static.routes/sim-url`;
    - clear button visible when input non-blank;
    - result block renders one candidate row per match, with the
      winner highlighted.

  Drives the view through [[panel-tree]] below — the node lane's
  reproduction of `panel/Panel`'s reads (rf2-k97c.3 made that root an
  `rf.fresco/defview` boundary) — so the dispatch round-trip still lands
  through the registered subs/events."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.routes.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; into one owner: plain-atom adapter + the default `:all` reset tier,
  ;; which already includes the trace-collector ring reset the old init
  ;; called a SECOND, redundant time.
  (xray-test-support/make-xray-runtime-fixture))

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

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

;; ---- the node lane's door onto the panel --------------------------------

(defn- panel-tree
  "The hiccup the rows below walk.

  rf2-k97c.3 — `panel/Panel` is now an `rf.fresco/defview` boundary, a
  real React function component whose body may only run inside a React
  render window, so calling `panel/Panel` no longer answers hiccup.
  This helper REPRODUCES THE BOUNDARY'S READS EXACTLY — the same
  four queries in the same ORDER — and hands their values to
  `panel/panel-tree`, so every row below asserts on the same hiccup it
  asserted on before, and the dispatch round-trip still lands through the
  registered subs / events as this file's ns docstring promises.

  KEPT IN STEP WITH `static/routes/panel_cljs_test`'s private twin, which
  is the same reproduction. Two copies is the established repair at two
  files (#9578); the shared-composer form
  (`test_helpers/static_machines_tree`) is what a THIRD consumer would
  earn.

  Call it inside `(rf/with-frame :rf/xray …)` — it subscribes ambiently."
  []
  (let [data       @(rf/subscribe [:rf.xray.static.routes/tab-data])
        expanded   @(rf/subscribe [:rf.xray.static.routes/expanded])
        sim-open   @(rf/subscribe [:rf.xray.static.routes/sim-nav-open])
        routes-map @(rf/subscribe [:rf.xray/registered-routes])]
    (panel/panel-tree data
                      {:expanded   expanded
                       :sim-open   sim-open
                       :routes-map routes-map}
                      (:dispatch (rf/capture-frame))
                      identity)))

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"}})

;; ---- input chrome -------------------------------------------------------

(deftest simulate-url-input-renders
  (testing "Simulate-URL input + label render when routes are present"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (let [tree (panel-tree)]
        (is (some? (find-by-testid tree "rf-xray-static-routes-sim"))
            "Simulate-URL section present")
        (is (some? (find-by-testid tree "rf-xray-static-routes-sim-input"))
            "Simulate-URL input present")
        (is (nil? (find-by-testid tree "rf-xray-static-routes-sim-clear"))
            "clear button absent when input blank")))))

(deftest simulate-url-clear-button-visible-when-input-set
  (testing "clear button surfaces when sim-url is non-blank"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url "/cart"]
                        {:frame :rf/xray})
      (let [tree (panel-tree)]
        (is (some? (find-by-testid tree "rf-xray-static-routes-sim-clear"))
            "clear button surfaces when input is non-blank")))))

;; ---- result block -------------------------------------------------------

(deftest simulate-url-renders-winner-candidate-row
  (testing "/cart resolves to :route/cart as winner"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url "/cart"]
                        {:frame :rf/xray})
      (let [tree (panel-tree)
            winner (find-by-testid tree "rf-xray-static-routes-sim-candidate-route/cart")]
        (is (some? winner) "winner candidate row rendered")
        (is (= "true" (:data-winner (second winner)))
            "winner row carries data-winner=\"true\"")))))

(deftest simulate-url-renders-result-block-on-no-match
  (testing "result block still surfaces when no routes match — informs the user"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url "/no-such-path"]
                        {:frame :rf/xray})
      (let [tree (panel-tree)
            candidates (find-all-by-testid-prefix
                         tree "rf-xray-static-routes-sim-candidate-")]
        (is (some? (find-by-testid tree "rf-xray-static-routes-sim-result"))
            "result surface still renders")
        (is (= 0 (count candidates))
            "no candidate rows when nothing matches")))))

(deftest simulate-url-blank-input-no-result
  (testing "blank input → result block absent"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      ;; Default — no sim-url set.
      (let [tree (panel-tree)]
        (is (nil? (find-by-testid tree "rf-xray-static-routes-sim-result"))
            "no result block when input is blank")))))

(deftest simulate-url-clear-event-empties-input
  (testing "set-sim-url with empty string drops the slot"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url "/cart"]
                        {:frame :rf/xray})
      (is (= "/cart" @(rf/subscribe [:rf.xray.static.routes/sim-url])))
      (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url ""]
                        {:frame :rf/xray})
      (is (nil? @(rf/subscribe [:rf.xray.static.routes/sim-url]))
          "empty input drops the slot"))))
