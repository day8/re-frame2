(ns day8.re-frame2-xray.panels.reactive-panel-disclosure-dispatch-routing-cljs-test
  "MOUNTED click-time regression for the Views panel's unchanged-subs
  disclosure controls (rf2-16y3x).

  Sibling in shape to `settings/popup_dispatch_routing_cljs_test.cljs`:
  it mounts the REAL facade `reg-view` (not the plain `reactive-panel`
  fn), plucks a deferred `:on-click` off the rendered hiccup, and invokes
  it OUTSIDE any `with-frame` binding — reproducing the browser reality
  that a React click fires AFTER render commits and the ambient frame
  scope has unwound.

  ## The two bugs these mounted tests defend against

  1. **Panel-local toggle leaked to `:rf/default`.** The unchanged-subs
     footer button installed a deferred `(fn [_e] (rf/dispatch …))` —
     a BARE global dispatch. After render scope unwinds the 3-tier frame
     resolution falls through to `:rf/default` (or emits
     `:rf.error/no-frame-context`), so the click never flipped Xray's
     `:reactive/show-unchanged?` and the disclosure stayed collapsed.
     The fix threads the facade `reg-view`-injected frame-aware
     `dispatch` down through `reactive-panel` → `unchanged-subs-section`,
     so the deferred click lands on the surrounding instance frame.

  2. **Settings pin had no UI.** The `:general :show-unchanged-subs?`
     pin (spec/021 §3.4) lost its control on 2026-05-27 while the slot
     stayed. Restored; the pin alone opens the disclosure.

  The earlier registry tests dispatched inside a manually-bound
  `:rf/xray` frame, which MASKED the click failure (they proved plumbing,
  not the deferred-click path). These tests render the actual `Panel` and
  fire its real deferred handler frameless, so the leak reproduces."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.panels.reactive-panel :as facade]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; A test-only event that writes the `:epoch-history` db slot the REAL
;; `:rf.xray/epoch-history` sub reads. Registered in `:post-reset` (a NEW
;; id, so no duplicate-image guard). Seeding the slot — rather than
;; re-registering the production composite (rejected by the duplicate-id
;; image guard once `register-xray-handlers!` has assembled the registry
;; image) — drives the FULLY-REAL composite + `project-record` + skip
;; projection, so these mounted tests exercise the real disclosure data
;; path end to end.
(defn- register-seed-event! []
  (rf/reg-event :rf2-16y3x.test/seed-epoch-history
    (fn [{:keys [db]} [_ history]]
      {:db (assoc db :epoch-history history)})))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (register-seed-event!)
                   (rf/make-frame {:id :rf/xray}))}))

;; ---- seeding -----------------------------------------------------------

(defn- skip-ev
  "A canonical `:rf.sub/skip` memo-hit trace event (the real substrate
  shape) so the production `project-record` projects a genuine
  `:subs-skipped` row."
  [sub-id]
  {:operation :rf.sub/skip
   :tags      {:rf.sub/id                    sub-id
               :rf.sub/query-v               [sub-id]
               :rf.sub/reason                :input-value-equal
               :rf.sub/input-paths-unchanged []}})

(defn- seed-memo-hits!
  "Seed the given frame's `:epoch-history` slot with ONE focused epoch
  record carrying two memo-hit `:rf.sub/skip` trace events, so the REAL
  `:rf.xray/reactive-data` composite head-falls-back onto it (nil focus →
  HEAD record) and `project-record` projects two `:subs-skipped` rows. The
  `:show-unchanged?` open-state stays the real OR of the two live disclosure
  axes off the frame's db (panel-local toggle slot + Settings pin slot)."
  [frame-id]
  (rf/with-frame frame-id
    (rf/dispatch-sync
      [:rf2-16y3x.test/seed-epoch-history
       [{:epoch-id     :ep-1
         :trace-events [(skip-ev :user/name) (skip-ev :cart/total)]}]])))

(defn- render-panel [frame-id]
  (rf/with-frame frame-id (facade/Panel)))

(defn- toggle-on-click [tree]
  (:on-click (second (th/find-by-testid tree "rf-xray-reactive-unchanged-toggle"))))

(defn- fake-event []
  #js {:preventDefault (fn []) :stopPropagation (fn [])})

(defn- await-xray-db [frame-id pred label]
  (test-support/poll-until
    #(pred (rf/app-db-value frame-id))
    {:label label :timeout-ms 1000}))

;; ---- default collapsed -------------------------------------------------

(deftest disclosure-default-collapsed
  (testing "rf2-16y3x — with neither the panel-local toggle nor the
            Settings pin set, the disclosure is collapsed: the footer
            toggle renders (2 memo-hit subs) but the dim row list does not."
    (seed-memo-hits! :rf/xray)
    (let [tree (render-panel :rf/xray)]
      (is (some? (th/find-by-testid tree "rf-xray-reactive-unchanged-toggle"))
          "the footer toggle renders")
      (is (re-find #"Show 2 unchanged subs"
                   (th/text-content (th/find-by-testid tree "rf-xray-reactive-unchanged-toggle")))
          "collapsed label counts the memo-hit subs")
      (is (nil? (th/find-by-testid tree "rf-xray-reactive-unchanged-list"))
          "the dim row list is hidden while collapsed"))))

;; ---- local click expands (through the frame-bound dispatcher) ----------

(deftest local-toggle-click-expands-through-frame-bound-dispatch
  (testing "rf2-16y3x — a REAL deferred click on the panel-local toggle,
            fired OUTSIDE the render frame scope, flips :rf/xray's
            :reactive/show-unchanged? (no leak to :rf/default, no
            no-frame-context) and a re-render then shows the dim rows."
    (seed-memo-hits! :rf/xray)
    (let [tree    (render-panel :rf/xray)
          handler (toggle-on-click tree)]
      (is (fn? handler) "the toggle exposes an :on-click handler")
      ;; Fire outside any with-frame — exactly a browser click after render.
      (handler (fake-event))
      (async done
        (-> (await-xray-db :rf/xray
                           #(true? (boolean (:reactive/show-unchanged? %)))
                           ":reactive/show-unchanged? flips true after the click")
            (.then (fn [_]
                     (is (true? (boolean (:reactive/show-unchanged? (rf/app-db-value :rf/xray))))
                         "the deferred click landed on :rf/xray's frame")
                     (is (nil? (:reactive/show-unchanged? (rf/app-db-value :rf/default)))
                         ":rf/default's db was NOT polluted (no bare-dispatch leak)")
                     ;; Re-render: the composite now folds in the flipped axis.
                     (let [tree2 (render-panel :rf/xray)]
                       (is (some? (th/find-by-testid tree2 "rf-xray-reactive-unchanged-list"))
                           "the dim memo-hit row list now renders")
                       (is (seq (th/find-by-testid-prefix
                                  tree2 "rf-xray-reactive-unchanged-row-__user_name_"))
                           "a memo-hit row renders after expand (readable slug stem, injective suffix)"))))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))

;; ---- Settings pin expands (the configured axis, alone) -----------------

(deftest settings-pin-expands-disclosure
  (testing "rf2-16y3x — the `:general :show-unchanged-subs?` Settings pin
            alone (panel-local toggle still OFF) expands the disclosure per
            spec/021 §3.4 — the local + configured axes compose."
    (seed-memo-hits! :rf/xray)
    ;; Flip ONLY the Settings pin (the panel-local toggle stays default OFF).
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update :general :show-unchanged-subs? true]))
    (let [tree (render-panel :rf/xray)]
      (is (false? (boolean (:reactive/show-unchanged? (rf/app-db-value :rf/xray))))
          "the panel-local quick-toggle is still OFF")
      (is (some? (th/find-by-testid tree "rf-xray-reactive-unchanged-list"))
          "the Settings pin alone opens the disclosure")
      (is (= "true"
             (get (second (th/find-by-testid tree "rf-xray-reactive-unchanged-toggle"))
                  :aria-expanded))
          "the toggle reports expanded"))))

;; ---- frame isolation ---------------------------------------------------

(deftest local-click-changes-only-that-instance
  (testing "rf2-16y3x — with two frame-scoped panels mounted, a click on
            instance A's toggle changes ONLY A's disclosure state; instance
            B's :reactive/show-unchanged? is untouched (per-instance frame,
            not a shared singleton)."
    (rf/make-frame {:id :xray-cell-2})
    (seed-memo-hits! :rf/xray)
    (seed-memo-hits! :xray-cell-2)
    (let [tree-a  (render-panel :rf/xray)
          ;; mount B too (its own frame-bound dispatcher captured)
          _tree-b (render-panel :xray-cell-2)
          handler (toggle-on-click tree-a)]
      (handler (fake-event))
      (async done
        (-> (await-xray-db :rf/xray
                           #(true? (boolean (:reactive/show-unchanged? %)))
                           "instance A flips true")
            (.then (fn [_]
                     (is (true? (boolean (:reactive/show-unchanged? (rf/app-db-value :rf/xray))))
                         "instance A's disclosure expanded")
                     (is (false? (boolean (:reactive/show-unchanged? (rf/app-db-value :xray-cell-2))))
                         "instance B is untouched — driving A did not move B")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))
