(ns day8.re-frame2-causa.settings.popup-dispatch-routing-cljs-test
  "Click-time frame-routing tests for the Causa Settings popup (rf2-smvvz).

  Sibling to `popup_cljs_test.cljs`. Lives in a separate ns so the
  `use-fixtures` map shape required by `cljs.test/async` does not
  conflict with the fn-form `reset-runtime-fixture` the existing
  render / open-state tests use.

  ## The bug these tests defend against

  The Settings popup mounts via `[rf/frame-provider {:frame :rf/causa}
  …]` in the shell. Subscribes inside the popup resolve through React
  context — at RENDER time, React's `_currentValue` for the
  `frame-context` is set to `:rf/causa` while the body of the
  frame-provider's children is rendering, so `(rf/subscribe …)` picks
  up the right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` fire
  LATER — after render commits and React has POPPED `_currentValue`
  back to the context's default (`:rf/default`). At click time the
  3-tier frame resolution chain (dynamic var → React-context tier →
  `:rf/default`) falls all the way through, the dispatch lands on
  `:rf/default`'s router, and the `:rf.causa/settings-*` handler
  reduces `:rf/default`'s db — leaving Causa's `:settings-open?` flag
  untouched. Symptom: X button does nothing, tabs do not switch, Esc
  does not close — the modal is stuck.

  The fix in `view.cljs` is mechanical: every `rf/dispatch` from a
  deferred handler carries `{:frame :rf/causa}` so the envelope's
  `:frame` is set at call time and never depends on the click-time
  React-context read.

  ## How these tests reproduce the click-time path

  Each test plucks the click handler off the rendered hiccup and
  invokes it OUTSIDE any `with-frame` binding — simulating the
  browser's click-fires-after-render reality. Click handlers use
  queued `rf/dispatch` (not `dispatch-sync`); the router drain is
  async via `goog.async.nextTick`. Tests use `test-support/poll-until`
  to await the drain before asserting."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.settings.popup :as popup]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture (map shape per cljs.test/async requirement) ---------------

(def ^:private fixture-snap (atom nil))

(defn- setup-runtime! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-settings!)
  ;; Capture the framework registrar so we can restore it post-test,
  ;; matching the body of `test-support/reset-runtime-fixture`.
  (reset! fixture-snap (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! plain-atom/adapter)
  (frame/ensure-default-frame!)
  ;; Causa's :rf.causa/* registrations + the :rf/causa frame.
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

;; ---- hiccup walker (mirrors popup_cljs_test) ---------------------------

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
  ;; Minimal object covering the methods the handlers call
  ;; (preventDefault, stopPropagation). Plain JS object so `(.x e)`
  ;; / `(.. e -x)` reads work.
  #js {:preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- fake-key-event [key]
  #js {:key             key
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- await-causa-db
  "Poll until `pred` of `:rf/causa`'s app-db returns truthy. Settles
  the async router drain that queued click-dispatches go through."
  [pred label]
  (test-support/poll-until
    #(pred (rf/get-frame-db :rf/causa))
    {:label label :timeout-ms 1000}))

(defn- render-open-modal []
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/settings-open]))
  (rf/with-frame :rf/causa (popup/Modal)))

;; ---- tests --------------------------------------------------------------

(deftest x-button-click-closes-modal-from-default-frame-context
  (testing "rf2-smvvz — clicking the ✕ button from OUTSIDE the
            :rf/causa frame-provider's render context still flips
            :rf/causa's :settings-open? to false. Without the explicit
            `{:frame :rf/causa}` opt on the dispatch, the click would
            land on :rf/default and the modal would stay open."
    (let [rendered  (render-open-modal)
          close-btn (find-by-testid rendered "rf-causa-settings-close")
          handler   (on-click close-btn)]
      (is (some? handler) "close button exposes an :on-click handler")
      (handler (fake-event)) ; outside any with-frame — same as a browser click
      (async done
        (-> (await-causa-db #(false? (boolean (:settings-open? %)))
                            "settings-open? flips false after X click")
            (.then (fn [_]
                     (is (false? (boolean (:settings-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :settings-open? flips to false")
                     (is (nil? (:settings-open? (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest backdrop-click-closes-modal-from-default-frame-context
  (testing "rf2-smvvz — clicking the backdrop from OUTSIDE the
            :rf/causa frame-provider's render context still closes the
            modal."
    (let [rendered (render-open-modal)
          backdrop (find-by-testid rendered "rf-causa-settings-backdrop")
          handler  (on-click backdrop)]
      (is (some? handler) "backdrop exposes an :on-click handler")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(false? (boolean (:settings-open? %)))
                            "settings-open? flips false after backdrop click")
            (.then (fn [_]
                     (is (false? (boolean (:settings-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :settings-open? flips to false")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest esc-keydown-closes-modal-from-default-frame-context
  (testing "rf2-smvvz — Esc keydown from OUTSIDE the :rf/causa frame-
            provider's render context still closes the modal."
    (let [rendered (render-open-modal)
          dialog   (find-by-testid rendered "rf-causa-settings-dialog")
          handler  (on-key-down dialog)]
      (is (some? handler) "dialog exposes an :on-key-down handler")
      (handler (fake-key-event "Escape"))
      (async done
        (-> (await-causa-db #(false? (boolean (:settings-open? %)))
                            "settings-open? flips false after Esc")
            (.then (fn [_]
                     (is (false? (boolean (:settings-open? (rf/get-frame-db :rf/causa))))
                         ":rf/causa's :settings-open? flips to false")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest tab-click-switches-section-from-default-frame-context
  (testing "rf2-smvvz — clicking a tab button from OUTSIDE the
            :rf/causa frame-provider's render context still updates
            :rf/causa's :settings-active-tab. Without the explicit
            frame opt, every tab click would land on :rf/default and
            the popup would stay frozen on the :general default."
    (let [rendered (render-open-modal)
          tab-node (find-by-testid rendered "rf-causa-settings-tab-filters")
          handler  (on-click tab-node)]
      (is (some? handler) "filters tab exposes an :on-click handler")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(= :filters (:settings-active-tab %))
                            "active-tab flips :filters after click")
            (.then (fn [_]
                     (is (= :filters (:settings-active-tab (rf/get-frame-db :rf/causa)))
                         "tab click flips :settings-active-tab to :filters")
                     (is (nil? (:settings-active-tab (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the tab dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))
