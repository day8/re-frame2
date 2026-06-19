(ns day8.re-frame2-xray.settings.popup-dispatch-routing-cljs-test
  "Click-time frame-routing tests for the Xray Settings popup (rf2-smvvz).

  Sibling to `popup_cljs_test.cljs`. Lives in a separate ns so the
  `use-fixtures` map shape required by `cljs.test/async` does not
  conflict with the fn-form `make-reset-runtime-fixture` the existing
  render / open-state tests use.

  ## The bug these tests defend against

  The Settings popup mounts via `[rf/frame-provider-existing {:frame :rf/xray}
  …]` in the shell. Subscribes inside the popup resolve through React
  context — at RENDER time, React's `_currentValue` for the
  `frame-context` is set to `:rf/xray` while the body of the
  frame-provider's children is rendering, so `(rf/subscribe …)` picks
  up the right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` fire
  LATER — after render commits and React has POPPED `_currentValue`
  back to the context's default (`:rf/default`). At click time the
  3-tier frame resolution chain (dynamic var → React-context tier →
  `:rf/default`) falls all the way through, the dispatch lands on
  `:rf/default`'s router, and the `:rf.xray/settings-*` handler
  reduces `:rf/default`'s db — leaving Xray's `:settings-open?` flag
  untouched. Symptom: X button does nothing, tabs do not switch, Esc
  does not close — the modal is stuck.

  The fix in `view.cljs` is mechanical: every `rf/dispatch` from a
  deferred handler carries `{:frame :rf/xray}` so the envelope's
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
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.popup :as popup]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture (map shape per cljs.test/async requirement) ---------------

(def ^:private fixture-snap (atom nil))

(defn- setup-runtime! []
  ;; rf2-sdqsla — `reset-runtime!` folds sentinel + trace-collector +
  ;; settings reset into one call.
  (xray-test-support/reset-runtime!)
  ;; Capture the framework registrar so we can restore it post-test,
  ;; matching the body of `test-support/make-reset-runtime-fixture`.
  (reset! fixture-snap (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! plain-atom/adapter)
  (frame/ensure-default-frame!)
  ;; Xray's :rf.xray/* registrations + the :rf/xray frame.
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

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

(defn- await-xray-db
  "Poll until `pred` of `:rf/xray`'s app-db returns truthy. Settles
  the async router drain that queued click-dispatches go through."
  [pred label]
  (test-support/poll-until
    #(pred (rf/app-db-value :rf/xray))
    {:label label :timeout-ms 1000}))

(defn- render-open-modal []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (rf/with-frame :rf/xray (popup/Modal)))

;; ---- tests --------------------------------------------------------------

(deftest x-button-click-closes-modal-from-default-frame-context
  (testing "rf2-smvvz — clicking the ✕ button from OUTSIDE the
            :rf/xray frame-provider's render context still flips
            :rf/xray's :settings-open? to false. Without the explicit
            `{:frame :rf/xray}` opt on the dispatch, the click would
            land on :rf/default and the modal would stay open."
    (let [rendered  (render-open-modal)
          close-btn (find-by-testid rendered "rf-xray-settings-close")
          handler   (on-click close-btn)]
      (is (some? handler) "close button exposes an :on-click handler")
      (handler (fake-event)) ; outside any with-frame — same as a browser click
      (async done
        (-> (await-xray-db #(false? (boolean (:settings-open? %)))
                            "settings-open? flips false after X click")
            (.then (fn [_]
                     (is (false? (boolean (:settings-open? (rf/app-db-value :rf/xray))))
                         ":rf/xray's :settings-open? flips to false")
                     (is (nil? (:settings-open? (rf/app-db-value :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest backdrop-click-closes-modal-from-default-frame-context
  (testing "rf2-smvvz — clicking the backdrop from OUTSIDE the
            :rf/xray frame-provider's render context still closes the
            modal."
    (let [rendered (render-open-modal)
          backdrop (find-by-testid rendered "rf-xray-settings-backdrop")
          handler  (on-click backdrop)]
      (is (some? handler) "backdrop exposes an :on-click handler")
      (handler (fake-event))
      (async done
        (-> (await-xray-db #(false? (boolean (:settings-open? %)))
                            "settings-open? flips false after backdrop click")
            (.then (fn [_]
                     (is (false? (boolean (:settings-open? (rf/app-db-value :rf/xray))))
                         ":rf/xray's :settings-open? flips to false")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest esc-keydown-closes-modal-from-default-frame-context
  (testing "rf2-smvvz — Esc keydown from OUTSIDE the :rf/xray frame-
            provider's render context still closes the modal."
    (let [rendered (render-open-modal)
          dialog   (find-by-testid rendered "rf-xray-settings-dialog")
          handler  (on-key-down dialog)]
      (is (some? handler) "dialog exposes an :on-key-down handler")
      (handler (fake-key-event "Escape"))
      (async done
        (-> (await-xray-db #(false? (boolean (:settings-open? %)))
                            "settings-open? flips false after Esc")
            (.then (fn [_]
                     (is (false? (boolean (:settings-open? (rf/app-db-value :rf/xray))))
                         ":rf/xray's :settings-open? flips to false")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest tab-click-switches-section-from-default-frame-context
  (testing "rf2-smvvz — clicking a tab button from OUTSIDE the
            :rf/xray frame-provider's render context still updates
            :rf/xray's :settings-active-tab. Without the explicit
            frame opt, every tab click would land on :rf/default and
            the popup would stay frozen on the :general default.

            Targets the Buffer tab (rf2-wknb3 retired the Filters
            tab that this test originally exercised; the routing
            assertion is independent of which tab is clicked as long
            as it differs from the default :general)."
    (let [rendered (render-open-modal)
          tab-node (find-by-testid rendered "rf-xray-settings-tab-buffer")
          handler  (on-click tab-node)]
      (is (some? handler) "buffer tab exposes an :on-click handler")
      (handler (fake-event))
      (async done
        (-> (await-xray-db #(= :buffer (:settings-active-tab %))
                            "active-tab flips :buffer after click")
            (.then (fn [_]
                     (is (= :buffer (:settings-active-tab (rf/app-db-value :rf/xray)))
                         "tab click flips :settings-active-tab to :buffer")
                     (is (nil? (:settings-active-tab (rf/app-db-value :rf/default)))
                         ":rf/default's db is NOT polluted by the tab dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))
