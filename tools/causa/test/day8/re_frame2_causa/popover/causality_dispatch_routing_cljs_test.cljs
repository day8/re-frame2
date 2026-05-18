(ns day8.re-frame2-causa.popover.causality-dispatch-routing-cljs-test
  "Click-time frame-routing tests for the Causality popover
  (rf2-w8lxg). Sibling of rf2-smvvz's settings popup fix (PR #1465).

  ## The bug these tests defend against

  The popover mounts via `[rf/frame-provider {:frame :rf/causa} …]`
  in the shell. Subscribes inside the popover resolve through React
  context — at RENDER time, React's `_currentValue` for the
  `frame-context` is set to `:rf/causa` while the body of the
  frame-provider's children is rendering, so `(rf/subscribe …)`
  picks up the right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-key-down` fire LATER — after
  render commits and React has POPPED `_currentValue` back to the
  context's default (`:rf/default`). At click time the 3-tier frame
  resolution chain (dynamic var → React-context tier → `:rf/default`)
  falls all the way through, the dispatch lands on `:rf/default`'s
  router, and the `:rf.causa/causality-popover-*` handler reduces
  `:rf/default`'s db — leaving Causa's `:causality-popover-*` slots
  untouched. Symptom: backdrop click, ✕ button, Esc, LR/TB toggle
  all silently no-op.

  The fix in `popover/causality.cljs` (+ `causality_graph.cljs`) is
  mechanical: every `rf/dispatch` from a deferred handler carries
  `{:frame :rf/causa}` so the envelope's `:frame` is set at call time
  and never depends on the click-time React-context read.

  ## How these tests reproduce the click-time path

  Each test plucks the handler off the rendered hiccup and invokes
  it OUTSIDE any `with-frame` binding — simulating the browser's
  click-fires-after-render reality. Click handlers use queued
  `rf/dispatch` (not `dispatch-sync`); the router drain is async via
  `goog.async.nextTick`. Tests use `test-support/poll-until` to await
  the drain before asserting."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.popover.causality :as popover]
            [day8.re-frame2-causa.popover.causality-graph :as pop-graph]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture (map shape per cljs.test/async requirement) ---------------

(def ^:private fixture-snap (atom nil))

(defn- setup-runtime! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (pop-graph/reset-elk-state-for-test!)
  (reset! fixture-snap (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- teardown-runtime! []
  (when-let [snap @fixture-snap]
    (test-support/restore-registrar! snap)
    (reset! fixture-snap nil))
  (reset! frame/frames {}))

(use-fixtures :each
  {:before setup-runtime!
   :after  teardown-runtime!})

;; ---- hiccup walker -----------------------------------------------------

(declare expand-tree)

(defn- expand-tree
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- hiccup-seq [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

;; ---- click-time helpers ------------------------------------------------

(defn- on-click [node] (:on-click (second node)))
(defn- on-key-down [node] (:on-key-down (second node)))

(defn- fake-event []
  #js {:preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- fake-key-event [key]
  #js {:key             key
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- await-causa-db
  "Poll until `pred` of `:rf/causa`'s app-db returns truthy."
  [pred label]
  (test-support/poll-until
    #(pred (rf/get-frame-db :rf/causa))
    {:label label :timeout-ms 1000}))

(defn- render-open-popover []
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open]))
  (rf/with-frame :rf/causa (popover/Popover)))

;; ---- tests --------------------------------------------------------------

(deftest close-button-click-closes-popover-from-default-frame-context
  (testing "rf2-w8lxg — clicking the ✕ button from OUTSIDE the
            :rf/causa frame-provider's render context still flips
            :rf/causa's :causality-popover-open? to false. Without the
            explicit `{:frame :rf/causa}` opt on the dispatch, the
            click would land on :rf/default and the popover would
            stay open."
    (let [rendered  (render-open-popover)
          close-btn (find-by-testid rendered "rf-causa-popover-close")
          handler   (on-click close-btn)]
      (is (some? handler) "close button exposes an :on-click handler")
      (is (true? (boolean (:causality-popover-open? (rf/get-frame-db :rf/causa))))
          "pre-condition: popover open on :rf/causa")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(false? (boolean (:causality-popover-open? %)))
                            "causality-popover-open? flips false after ✕ click")
            (.then (fn [_]
                     (is (false? (boolean (:causality-popover-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :causality-popover-open? flips to false")
                     (is (nil? (:causality-popover-open? (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest backdrop-click-closes-popover-from-default-frame-context
  (testing "rf2-w8lxg — clicking the backdrop from OUTSIDE the
            :rf/causa frame-provider's render context still closes the
            popover."
    (let [rendered (render-open-popover)
          backdrop (find-by-testid rendered "rf-causa-popover-backdrop")
          handler  (on-click backdrop)]
      (is (some? handler) "backdrop exposes an :on-click handler")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(false? (boolean (:causality-popover-open? %)))
                            "causality-popover-open? flips false after backdrop click")
            (.then (fn [_]
                     (is (false? (boolean (:causality-popover-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :causality-popover-open? flips to false")
                     (is (nil? (:causality-popover-open? (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by backdrop click")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest esc-keydown-closes-popover-from-default-frame-context
  (testing "rf2-w8lxg — Esc keydown from OUTSIDE the :rf/causa frame-
            provider's render context still closes the popover."
    (let [rendered (render-open-popover)
          dialog   (find-by-testid rendered "rf-causa-popover-dialog")
          handler  (on-key-down dialog)]
      (is (some? handler) "dialog exposes an :on-key-down handler")
      (handler (fake-key-event "Escape"))
      (async done
        (-> (await-causa-db #(false? (boolean (:causality-popover-open? %)))
                            "causality-popover-open? flips false after Esc")
            (.then (fn [_]
                     (is (false? (boolean (:causality-popover-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :causality-popover-open? flips to false")
                     (is (nil? (:causality-popover-open? (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by Esc dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest lr-toggle-click-flips-layout-from-default-frame-context
  (testing "rf2-w8lxg — clicking the LR toggle from OUTSIDE the
            :rf/causa frame-provider's render context still flips
            :rf/causa's :causality-popover-layout from :tb (default)
            to :lr. Without the explicit frame opt the dispatch
            would land on :rf/default and the layout would stay
            frozen on :tb."
    (let [rendered (render-open-popover)
          lr-btn   (find-by-testid rendered "rf-causa-popover-layout-lr")
          handler  (on-click lr-btn)]
      (is (some? handler) "LR button exposes an :on-click handler")
      (is (= :tb (:causality-popover-layout (rf/get-frame-db :rf/causa)))
          "pre-condition: default layout is :tb on :rf/causa")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(= :lr (:causality-popover-layout %))
                            "layout flips :lr after LR click")
            (.then (fn [_]
                     (is (= :lr (:causality-popover-layout (rf/get-frame-db :rf/causa)))
                         ":rf/causa's :causality-popover-layout flips to :lr")
                     (is (nil? (:causality-popover-layout (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the layout dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))
