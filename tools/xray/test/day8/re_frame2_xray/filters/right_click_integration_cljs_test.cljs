(ns day8.re-frame2-xray.filters.right-click-integration-cljs-test
  "Right-click event-row → OUT pill integration test (rf2-ak4ms).

  Wires:
   - drop a trace event into the buffer
   - render the shell
   - fire `on-context-menu` on the row
   - assert it dispatches :rf.xray/hide-event-type with the event-id

  Plus the OUT-pill → filtered-event-bundles round-trip: once a pill is
  installed via the canonical add-filter event, the L2 event list
  re-renders without the matching row."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

(declare expand-tree)

(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else tree))

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

(defn- dispatch-trace-ev [id event-vec]
  {:id           id
   :op-type      :rf.event
   :operation    :rf.event/dispatched
   :tags         {:rf.event/v       event-vec
                  :frame       :rf/default
                  :rf.trace/dispatch-id id}})

;; -------------------------------------------------------------------------
;; (1) Right-click row opens the context menu (rf2-ikuwt)
;; -------------------------------------------------------------------------

(defn- mk-context-event
  "Right-click event stub. Carries clientX/clientY so the menu can
  position itself at the cursor."
  []
  (let [called? (atom false)]
    {:event  #js {:preventDefault (fn [] (reset! called? true))
                  :clientX        128
                  :clientY        256}
     :called called?}))

(deftest right-click-row-opens-context-menu
  (testing "rf2-ikuwt — `on-context-menu` on a row fires
            `:rf.xray/open-row-context-menu` with the event-id +
            click coords. The browser context menu is suppressed via
            preventDefault."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 7 [:user/mouse-move {:x 1}]))
    (let [dispatches      (atom [])
          {:keys [event called]} (mk-context-event)]
      (with-redefs [rf/dispatch-impl (fn
                                        ([ev]       (swap! dispatches conj ev) nil)
                                        ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/xray
          (let [tree (shell/shell-view)
                row  (find-by-testid tree "rf-xray-event-row-7")
                h    (:on-context-menu (second row))]
            (is (some? row) "row mounted")
            (is (fn? h) "row has on-context-menu handler")
            (when h (h event)))))
      (is @called "preventDefault called so the browser menu is suppressed")
      (is (some (fn [ev]
                  (and (vector? ev)
                       (= :rf.xray/open-row-context-menu (first ev))
                       (= :user/mouse-move (:event-id (second ev)))
                       (= 128 (:x (second ev)))
                       (= 256 (:y (second ev)))))
                @dispatches)
          ":rf.xray/open-row-context-menu fired with event-id + coords"))))

(deftest hide-event-type-handler-pre-populates-popup
  (testing "the handler that on-context-menu dispatches opens the
            popup with OUT mode + pattern pre-filled — exercised
            directly via dispatch-sync"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/hide-event-type :user/mouse-move])
      (is (true? @(rf/subscribe [:rf.xray/edit-popup-open?])))
      (let [trig  @(rf/subscribe [:rf.xray/edit-popup-trigger])
            draft @(rf/subscribe [:rf.xray/edit-popup-draft])]
        (is (= :context (:source trig)))
        (is (= :out (:mode trig)))
        (is (= ":user/mouse-move" (:pattern draft))
            "draft pre-populated with the row's event-id")))))

(deftest right-click-then-save-installs-out-pill
  (testing "the full right-click → confirm path lands the pill in OUT"
    (xray-setup!)
    (rf/with-frame :rf/xray
      ;; Step 1: handler dispatched from right-click (verified above
      ;; via the rf/dispatch capture path).
      (rf/dispatch-sync [:rf.xray/hide-event-type :mouse-move])
      ;; Step 2: user clicks Apply in the popup.
      (rf/dispatch-sync [:rf.xray/save-edit-popup])
      (is (= [{:pattern :mouse-move}]
             (:out @(rf/subscribe [:rf.xray/active-filters])))
          "OUT pill installed via right-click flow")
      (is (false? @(rf/subscribe [:rf.xray/edit-popup-open?]))
          "popup closes after save"))))

;; -------------------------------------------------------------------------
;; (2) Once OUT pill is set, filtered-event-bundles drops the matching row
;; -------------------------------------------------------------------------

(deftest out-pill-removes-matching-row-from-event-list
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:mouse-move]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:order/submit]))
  (rf/with-frame :rf/xray
    ;; Sanity — all three rows present pre-filter.
    (let [tree (shell/shell-view)]
      (is (some? (find-by-testid tree "rf-xray-event-row-1")))
      (is (some? (find-by-testid tree "rf-xray-event-row-2")))
      (is (some? (find-by-testid tree "rf-xray-event-row-3"))))
    ;; Install the OUT pill via the canonical add-filter event.
    (rf/dispatch-sync [:rf.xray/add-filter :out {:pattern :mouse-move}])
    ;; Re-render and assert :mouse-move dropped.
    (let [tree (shell/shell-view)]
      (is (some? (find-by-testid tree "rf-xray-event-row-1")))
      (is (nil? (find-by-testid tree "rf-xray-event-row-2"))
          "row 2 (:mouse-move) filtered out")
      (is (some? (find-by-testid tree "rf-xray-event-row-3"))))))
