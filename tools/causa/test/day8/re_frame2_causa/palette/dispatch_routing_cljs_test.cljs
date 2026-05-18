(ns day8.re-frame2-causa.palette.dispatch-routing-cljs-test
  "Click-time frame-routing tests for the Causa command palette
  (rf2-w8lxg). Sibling of rf2-smvvz's settings popup fix (PR #1465).

  ## The bug these tests defend against

  The palette modal mounts via `[rf/frame-provider {:frame :rf/causa}
  …]` in the shell. Subscribes inside the palette resolve through
  React context — at RENDER time, React's `_currentValue` for the
  `frame-context` is set to `:rf/causa` while the body of the
  frame-provider's children is rendering, so `(rf/subscribe …)`
  picks up the right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` /
  `:on-mouse-enter` fire LATER — after render commits and React has
  POPPED `_currentValue` back to the context's default
  (`:rf/default`). At click time the 3-tier frame resolution chain
  (dynamic var → React-context tier → `:rf/default`) falls all the
  way through, the dispatch lands on `:rf/default`'s router, and the
  `:rf.causa/palette-*` handler reduces `:rf/default`'s db — leaving
  Causa's palette slots untouched. Symptom: backdrop click does not
  close, arrow keys do not move the cursor, Esc does not close, input
  text does not update the query — the palette appears frozen.

  The fix in `palette/view.cljs` is mechanical: every `rf/dispatch`
  from a deferred handler carries `{:frame :rf/causa}` so the
  envelope's `:frame` is set at call time and never depends on the
  click-time React-context read.

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
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.palette :as palette]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture (map shape per cljs.test/async requirement) ---------------

(def ^:private fixture-snap (atom nil))

(defn- setup-runtime! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-settings!)
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

;; ---- hiccup walker (mirrors popup_dispatch_routing test) ---------------

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
(defn- on-change [node] (:on-change (second node)))

(defn- fake-event []
  #js {:preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- fake-key-event [key]
  #js {:key             key
       :ctrlKey         false
       :metaKey         false
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- fake-change-event [value]
  #js {:target          #js {:value value}
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- await-causa-db
  "Poll until `pred` of `:rf/causa`'s app-db returns truthy."
  [pred label]
  (test-support/poll-until
    #(pred (rf/get-frame-db :rf/causa))
    {:label label :timeout-ms 1000}))

(defn- render-open-palette []
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open]))
  (rf/with-frame :rf/causa (palette/Modal)))

;; ---- tests --------------------------------------------------------------

(deftest backdrop-click-closes-palette-from-default-frame-context
  (testing "rf2-w8lxg — clicking the backdrop from OUTSIDE the
            :rf/causa frame-provider's render context still closes the
            palette. Without the explicit `{:frame :rf/causa}` opt the
            click would land on :rf/default and the palette would stay
            open."
    (let [rendered (render-open-palette)
          backdrop (find-by-testid rendered "rf-causa-palette-backdrop")
          handler  (on-click backdrop)]
      (is (some? handler) "backdrop exposes an :on-click handler")
      ;; Pre-condition — palette is open on :rf/causa.
      (is (true? (boolean (:palette-open? (rf/get-frame-db :rf/causa)))))
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(false? (boolean (:palette-open? %)))
                            "palette-open? flips false after backdrop click")
            (.then (fn [_]
                     (is (false? (boolean (:palette-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :palette-open? flips to false")
                     (is (nil? (:palette-open? (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest esc-keydown-closes-palette-from-default-frame-context
  (testing "rf2-w8lxg — Esc keydown on the palette input from OUTSIDE
            the :rf/causa frame-provider's render context still closes
            the palette."
    (let [rendered (render-open-palette)
          input    (find-by-testid rendered "rf-causa-palette-input")
          handler  (on-key-down input)]
      (is (some? handler) "input exposes an :on-key-down handler")
      (handler (fake-key-event "Escape"))
      (async done
        (-> (await-causa-db #(false? (boolean (:palette-open? %)))
                            "palette-open? flips false after Esc")
            (.then (fn [_]
                     (is (false? (boolean (:palette-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :palette-open? flips to false")
                     (is (nil? (:palette-open? (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by Esc dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest arrow-down-keydown-moves-cursor-from-default-frame-context
  (testing "rf2-w8lxg — ArrowDown keydown on the palette input from
            OUTSIDE the :rf/causa frame-provider's render context still
            updates :rf/causa's :palette-cursor. Without the explicit
            frame opt the dispatch would land on :rf/default and the
            cursor would never move."
    (let [rendered (render-open-palette)
          input    (find-by-testid rendered "rf-causa-palette-input")
          handler  (on-key-down input)]
      (is (some? handler) "input exposes an :on-key-down handler")
      ;; Pre-condition — cursor at 0.
      (is (= 0 (:palette-cursor (rf/get-frame-db :rf/causa))))
      (handler (fake-key-event "ArrowDown"))
      (async done
        (-> (await-causa-db #(pos? (or (:palette-cursor %) 0))
                            "palette-cursor advances past 0 after ArrowDown")
            (.then (fn [_]
                     (is (pos? (or (:palette-cursor (rf/get-frame-db :rf/causa)) 0))
                         ":rf/causa's :palette-cursor advances after ArrowDown")
                     (is (nil? (:palette-cursor (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the cursor dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest arrow-up-keydown-routes-to-causa-frame
  (testing "rf2-w8lxg — ArrowUp keydown also routes through the
            explicit frame opt. Move cursor to 2 first, then ArrowUp
            should bring it back toward 0 against :rf/causa's db."
    (let [_ (rf/with-frame :rf/causa
              (rf/dispatch-sync [:rf.causa/palette-open])
              (rf/dispatch-sync [:rf.causa/palette-cursor-set 2]))
          rendered (rf/with-frame :rf/causa (palette/Modal))
          input    (find-by-testid rendered "rf-causa-palette-input")
          handler  (on-key-down input)]
      (is (= 2 (:palette-cursor (rf/get-frame-db :rf/causa))))
      (handler (fake-key-event "ArrowUp"))
      (async done
        (-> (await-causa-db #(< (or (:palette-cursor %) 0) 2)
                            "palette-cursor decrements after ArrowUp")
            (.then (fn [_]
                     (is (< (or (:palette-cursor (rf/get-frame-db :rf/causa)) 0) 2)
                         ":rf/causa's :palette-cursor decrements after ArrowUp")
                     (is (nil? (:palette-cursor (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by ArrowUp")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest input-on-change-updates-query-from-default-frame-context
  (testing "rf2-w8lxg — typing into the palette input from OUTSIDE the
            :rf/causa frame-provider's render context still updates
            :rf/causa's :palette-query. Without the explicit frame opt
            every keystroke would land on :rf/default and the
            displayed query would freeze."
    (let [rendered (render-open-palette)
          input    (find-by-testid rendered "rf-causa-palette-input")
          handler  (on-change input)]
      (is (some? handler) "input exposes an :on-change handler")
      (handler (fake-change-event "search"))
      (async done
        (-> (await-causa-db #(= "search" (:palette-query %))
                            "palette-query flips to 'search' after on-change")
            (.then (fn [_]
                     (is (= "search" (:palette-query (rf/get-frame-db :rf/causa)))
                         ":rf/causa's :palette-query reflects the typed text")
                     (is (nil? (:palette-query (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by typing")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))
