(ns day8.re-frame2-xray.spine-filters-cljs-test
  "Per-event-id mute filter: muting strips rows from
  `:rf.xray/filtered-event-bundles`, the row context menu, the mute manager
  and the L1 ribbon indicator. The handler rows that need real storage
  (mute / unmute / clear with persistence, hydration) live in
  `spine-filters-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset (plain-atom + the `:all`
  ;; tier, which resets the trace-collector rings); `:post-reset` carries
  ;; the raw-mute-slate tail.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn [] (spine-filters/clear-raw!))}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(defn- dispatch-trace-ev [id event-vec]
  {:id           id
   :op-type      :rf.event
   :operation    :rf.event/dispatched
   :tags         {:rf.event/v       event-vec
                  :frame       :rf/default
                  :rf.trace/dispatch-id id}})

;; -------------------------------------------------------------------------
;; (1) Pure helpers
;; -------------------------------------------------------------------------

(deftest filter-event-bundles-preserves-event-less-cascades
  (testing "cascades with no event vector (e.g. :ungrouped bucket) survive
            the mute filter — they're handled by the separate
            :show-ungrouped? opt-in"
    (let [cs [{:event [:a]} {:event nil :dispatch-id :ungrouped} {:event [:b]}]]
      (is (= [{:event nil :dispatch-id :ungrouped} {:event [:b]}]
             (spine-filters/filter-event-bundles cs #{:a}))))))

;; -------------------------------------------------------------------------
;; (6) Filtered-cascades composition
;; -------------------------------------------------------------------------

(deftest muting-strips-rows-from-filtered-event-bundles
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:user/mouse-move]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:order/submit]))
  ;; Sanity — all three rows present pre-mute.
  (let [before (frame-sub [:rf.xray/filtered-event-bundles])]
    (is (= 3 (count before))))
  ;; Mute :user/mouse-move.
  (frame-dispatch [:rf.xray/mute-event-id :user/mouse-move])
  (let [after (frame-sub [:rf.xray/filtered-event-bundles])
        ids   (mapv (fn [c] (first (:event c))) after)]
    (is (= [:auth/login :order/submit] ids)
        ":user/mouse-move stripped from filtered-event-bundles")))

;; -------------------------------------------------------------------------
;; (7) Row context menu open / close state
;; -------------------------------------------------------------------------
;;
;; THE TWO NODE-LANE DOORS. The row context menu and the mute manager
;; are FRESCO BOUNDARIES behind `as-component` bridges, so
;; `spine-filters/RowContextMenu` and
;; `/Modal` answer interop vectors rather than trees to walk, and
;; `dynamic-shell-tree/shell-view-tree` — which composes the shell's
;; hiccup — necessarily stops at them. The shipped markup is the pair of
;; PURE `*-tree` fns; each door below reproduces its boundary's gate and
;; reads exactly, same query vectors in the same order, so every row
;; asserts on the hiccup the boundary itself would build.
;;
;; This is the same substitution `modals-aria-cljs-test` makes for
;; `panels.cancellation-cascade/popover-tree`. Rows that assert on the
;; SHELL (the L2 event rows, the L1 ribbon indicator) walk the shell tree
;; — the ribbon indicator is a plain fn the ribbon CALLS, so it is part
;; of that tree.

;; THE DISPATCH THE DOORS PASS IS A WRAPPING FN, NOT THE BARE `rf/dispatch`,
;; and that is load-bearing for `end-to-end-mute-from-context-menu`.
;; `rf/dispatch` is a MACRO (core.cljc), so it expands to the `^:no-doc`
;; `rf/dispatch-impl` seam AT ITS CALL SITE. Handing the bare symbol to a
;; helper passes something that never expands, so the `with-redefs` on
;; `dispatch-impl` below cannot see the handler's dispatches and the
;; capture atom stays empty. Calling it inside a fn puts the expansion
;; where the boundary puts it — `RowContextMenuView` and `ModalView` both
;; build `(fn [ev] (rf/dispatch ev {:frame frame}))` — so these doors
;; reproduce the dispatch path as well as the reads.

(defn- door-dispatch
  "The frame-aware dispatcher the boundaries capture at render time,
  reproduced for the node lane. The ambient frame stands in for the
  boundary's `rf/current-frame-id`, which is why every door call below
  sits inside `(rf/with-frame :rf/xray …)`."
  [ev]
  (rf/dispatch ev))

(defn- row-context-menu-tree
  "The row context menu's markup for the current state: nil while
  closed, the menu otherwise. Mirrors
  `spine-filters/RowContextMenuView`'s gate and read."
  []
  (when-let [menu @(rf/subscribe [:rf.xray/row-context-menu])]
    (spine-filters/row-context-menu-tree door-dispatch menu)))

(defn- mute-manager-tree
  "The mute manager's markup for the current state: nil while closed,
  the dialog otherwise. Mirrors `spine-filters/ModalView`'s gate and
  reads."
  []
  (when @(rf/subscribe [:rf.xray/mute-manager-open?])
    (spine-filters/dialog-tree
      door-dispatch
      {:muted       @(rf/subscribe [:rf.xray/muted-event-ids])
       :positioning @(rf/subscribe [:rf.xray/modal-positioning])})))

(deftest open-row-context-menu-event-writes-slot
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-row-context-menu
                   {:event-id :user/mouse-move :x 100 :y 200}])
  (is (= {:event-id :user/mouse-move :x 100 :y 200}
         (frame-sub [:rf.xray/row-context-menu]))))

(deftest row-context-menu-renders-mute-and-hide-items
  ;; The 'Mute' item is driven by end-to-end-mute-from-context-menu.
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-row-context-menu
                   {:event-id :user/mouse-move :x 100 :y 200}])
  (rf/with-frame :rf/xray
    (let [tree (row-context-menu-tree)]
      (is (= ":user/mouse-move"
             (:data-rf-xray-event-id (second (rf.test-helpers/find-by-testid tree "rf-xray-row-context-menu"))))
          "the menu carries the row's event-id")
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-row-context-menu-hide-event-type"))
          "and the 'Always hide…' item"))))

;; -------------------------------------------------------------------------
;; (8) Mute manager modal
;; -------------------------------------------------------------------------

(deftest mute-manager-open-close
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-mute-manager])
  (is (true? (frame-sub [:rf.xray/mute-manager-open?])))
  (frame-dispatch [:rf.xray/close-mute-manager])
  (is (false? (frame-sub [:rf.xray/mute-manager-open?]))))

(deftest mute-manager-renders-muted-ids
  (xray-setup!)
  (frame-dispatch [:rf.xray/mute-event-id :a/x])
  (frame-dispatch [:rf.xray/mute-event-id :b/y])
  (frame-dispatch [:rf.xray/open-mute-manager])
  (rf/with-frame :rf/xray
    (let [tree (mute-manager-tree)]
      (is (= [true true true]
             (mapv #(some? (rf.test-helpers/find-by-testid tree %))
                   ["rf-xray-mute-manager-list"
                    "rf-xray-mute-manager-row-:a/x"
                    "rf-xray-mute-manager-row-:b/y"]))
          "the list section with one row per muted id"))))

(deftest mute-manager-empty-state
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-mute-manager])
  (rf/with-frame :rf/xray
    (let [tree  (mute-manager-tree)
          empty (rf.test-helpers/find-by-testid tree "rf-xray-mute-manager-empty")
          list  (rf.test-helpers/find-by-testid tree "rf-xray-mute-manager-list")]
      (is (some? empty) "empty-state copy mounts when no ids muted")
      (is (nil? list) "list section absent when empty"))))

;; -------------------------------------------------------------------------
;; (9) L1 ribbon indicator
;; -------------------------------------------------------------------------

(deftest ribbon-mute-indicator-hidden-when-no-mutes
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [tree (dynamic-shell-tree/shell-view-tree)
          ind  (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-mute-indicator")]
      (is (nil? ind) "indicator absent when no event-ids muted"))))

(deftest ribbon-mute-indicator-shows-when-mute-set-nonempty
  (xray-setup!)
  (frame-dispatch [:rf.xray/mute-event-id :a])
  (frame-dispatch [:rf.xray/mute-event-id :b])
  (rf/with-frame :rf/xray
    (is (= "2" (nth (rf.test-helpers/find-by-testid (dynamic-shell-tree/shell-view-tree)
                                                    "rf-xray-ribbon-mute-indicator-count")
                    2))
        "the count reads the set size")))

;; -------------------------------------------------------------------------
;; (10) End-to-end: open menu → click 'Mute' → row disappears
;; -------------------------------------------------------------------------

(deftest end-to-end-mute-from-context-menu
  (testing "open menu → click 'Mute' fires both the mute dispatch +
            the close-menu dispatch. We replace `re-frame.core/dispatch-impl`
            (the `^:no-doc` seam the row on-click's `rf/dispatch` macro call
            expands to) with a capturing fn so the test sees the dispatch
            payloads without depending on async drain timing; the real flow
            replays both dispatches via dispatch-sync to assert the
            downstream effects (row dropped, menu closed, indicator
            visible)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:user/mouse-move]))
    (let [dispatches (atom [])]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/open-row-context-menu
                           {:event-id :user/mouse-move :x 50 :y 50}])
        ;; Capture dispatches from the on-click handler.
        (with-redefs [rf/dispatch-impl (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree     (row-context-menu-tree)
                mute-btn (rf.test-helpers/find-by-testid tree "rf-xray-row-context-menu-mute")
                handler  (:on-click (second mute-btn))]
            (when handler (handler #js {:stopPropagation (fn [] nil)}))))
        ;; Both dispatches captured.
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/mute-event-id (first ev))
                         (= :user/mouse-move (second ev))))
                  @dispatches)
            "click fires :rf.xray/mute-event-id")
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/close-row-context-menu (first ev))))
                  @dispatches)
            "click also closes the menu")
        ;; Replay both via dispatch-sync to assert downstream effects.
        (rf/dispatch-sync [:rf.xray/mute-event-id :user/mouse-move])
        (rf/dispatch-sync [:rf.xray/close-row-context-menu])
        (let [tree (dynamic-shell-tree/shell-view-tree)]
          (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-event-row-1")))
          (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-event-row-2"))
              "muted row dropped from L2"))
        ;; Asserted through the door rather than the shell
        ;; tree. The boundary's own gate is what answers nil here, so this
        ;; row grades the close; looked for in the shell tree it would be
        ;; absent whatever the slot held, and pass vacuously.
        (is (nil? (row-context-menu-tree))
            "menu closed after click")))))
