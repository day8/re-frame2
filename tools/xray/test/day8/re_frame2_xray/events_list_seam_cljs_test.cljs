(ns day8.re-frame2-xray.events-list-seam-cljs-test
  "CLJS tests for the L2/L3 events-list seam resize handle (rf2-t2dsh).

  Asserts:
    1. `SeamHandle` mounts with the documented testid, role, and ARIA
       slots (`separator`, horizontal orientation, live `aria-valuenow`).
    2. The shell tree carries the seam BETWEEN the event-list and the
       tab-bar (DOM-order contract — the seam IS the boundary).
    3. Drag lifecycle — `start-seam-drag!` flips `seam-dragging?` to
       true, `seam-simulate-up!` flips it back. `seam-simulate-move!`
       dispatches the set-events-list-height-px event with the start +
       delta.
    4. Drag math — drag DOWN grows the list; drag UP shrinks it.
    5. Clamp at write-time — the registry's set-events-list-height-px
       event clamps to [min, viewport×0.7] before persisting.
    6. Double-click handler dispatches `:rf.xray/reset-events-list-
       height` which restores the default.
    7. Keyboard navigation — ArrowDown/Up, Shift+arrow coarse step,
       Home/End clamp-overshoot, Enter/Space reset.
    8. The L2 event-list reads its height from the sub — sub updates
       lift the rendered :height style.
    9. The previously-shipped `:resize \"vertical\"` corner-grip is
       gone — the L2 list's inline style no longer carries
       `:resize`. Documents the disposition of the prior affordance
       (retired in favour of the seam).
   10. The seam cursor is `row-resize` — the affordance hover signal."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  ;; rf2-sdqsla — `reset-runtime!` folds sentinel + trace-collector +
  ;; settings reset into one call.
  (xray-test-support/reset-runtime!)
  ;; Force-cleanup any stale seam-drag state from a previous test (the
  ;; module-level defonce atom survives fixture reset).
  (resize-handle/seam-simulate-up!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- hiccup walker (mirrors shell_cljs_test) ---------------------------

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

(defn- all-testids
  "Return the testids of every hiccup node in `tree` that carries one,
  in pre-order. Used to assert DOM-order — the seam must sit between
  `rf-xray-event-list` and `rf-xray-tab-bar`."
  [tree]
  (->> tree
       hiccup-seq
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (:data-testid (second node)))))))

;; ---- SeamHandle component shape ----------------------------------------

(deftest seam-handle-renders-with-aria-shape
  (setup!)
  (rf/with-frame :rf/xray
    (let [tree (resize-handle/SeamHandle)
          props (second tree)]
      (is (some? tree)
          "SeamHandle returns a hiccup tree")
      (is (= "rf-xray-event-list-seam" (:data-testid props))
          "testid is the documented contract")
      (is (= "separator" (:role props))
          "role is the WAI-ARIA separator pattern")
      (is (= "horizontal" (:aria-orientation props))
          "orientation is horizontal (row-resize seam)")
      (is (= "Resize events list" (:aria-label props))
          "label describes the operation")
      (is (= 0 (:tab-index props))
          "seam is keyboard-reachable via tab")
      (is (number? (:aria-valuenow props))
          "live aria-valuenow exposes the current height to AT")
      (is (= config/min-events-list-height-px (:aria-valuemin props))
          "aria-valuemin matches the published floor"))))

(deftest seam-handle-style-uses-row-resize-cursor
  (setup!)
  (rf/with-frame :rf/xray
    (let [tree (resize-handle/SeamHandle)
          style (:style (second tree))]
      (is (= "row-resize" (:cursor style))
          "seam hover cursor signals vertical-axis resize")
      (is (string? (:box-shadow style))
          "always-visible hairline accent rides as inline box-shadow")
      (is (= "none" (:touch-action style))
          "touch-action: none disables native page-pan during drag")
      (is (= "none" (:user-select style))
          "user-select: none prevents text-lasso during drag"))))

;; ---- shell DOM-order contract -------------------------------------------

(deftest shell-mounts-seam-between-list-and-tab-bar
  (testing "rf2-t2dsh — the seam handle sits between the L2 event list
            and the L3 tab bar in DOM order. The seam IS the
            boundary; placing it anywhere else (above the events-
            ribbon, below the tab-bar) would break the click-and-drag
            semantics."
    (setup!)
    (rf/with-frame :rf/xray
      (let [tree    (shell/shell-view {:mode :inline})
            testids (all-testids tree)
            list-idx (.indexOf (clj->js testids) "rf-xray-event-list")
            seam-idx (.indexOf (clj->js testids) "rf-xray-event-list-seam")
            tabs-idx (.indexOf (clj->js testids) "rf-xray-tab-bar")]
        (is (pos? list-idx) "L2 event-list present")
        (is (pos? seam-idx) "L2/L3 seam present")
        (is (pos? tabs-idx) "L3 tab-bar present")
        (is (< list-idx seam-idx)
            "seam appears AFTER the event-list in pre-order")
        (is (< seam-idx tabs-idx)
            "seam appears BEFORE the tab-bar in pre-order")))))

(deftest l2-list-no-longer-carries-native-resize
  (testing "rf2-t2dsh — the previous browser-native `:resize
            \"vertical\"` corner-grip is retired. The L2 list's inline
            style MUST NOT carry `:resize` — the seam handle is the
            sole vertical-resize affordance."
    (setup!)
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view {:mode :inline})
            list  (find-by-testid tree "rf-xray-event-list")
            style (:style (second list))]
        (is (some? list) "event-list container present")
        (is (nil? (:resize style))
            "no `:resize` declaration — corner-grip retired")))))

;; ---- drag lifecycle -----------------------------------------------------

(defn- stub-event
  "Build a stub PointerEvent-shaped JS object carrying just the slots
  the seam handle reads. `preventDefault` is a no-op stub so
  `start-seam-drag!` can call it without throwing in the test runner."
  [page-y]
  #js {:pageY          page-y
       :pointerId      1
       :preventDefault (fn [])})

(deftest seam-start-drag-flips-state
  (setup!)
  (is (false? (resize-handle/seam-dragging?))
      "no drag in progress at fixture start")
  (resize-handle/start-seam-drag! (stub-event 500) 200)
  (is (true? (resize-handle/seam-dragging?))
      "start-seam-drag! installed the global capture")
  (resize-handle/seam-simulate-up!)
  (is (false? (resize-handle/seam-dragging?))
      "seam-simulate-up! tore down the capture"))

(deftest seam-drag-down-grows-list
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/start-seam-drag! (stub-event 500) 200)
      ;; Drag DOWN by 100px (pageY 600 > start-y 500) — list grows by
      ;; 100px → 300px target. The view's `dy = now-y - start-y`,
      ;; so dy = 100, new-height = 300.
      (resize-handle/seam-simulate-move! 600)
      (resize-handle/seam-simulate-up!))
    (let [height-events (filter #(= :rf.xray/set-events-list-height-px (first %))
                                @dispatches)]
      (is (seq height-events)
          "set-events-list-height-px was dispatched at least once")
      (is (some #(= 300 (second %)) height-events)
          "drag down by 100px dispatched 300 (start 200 + 100 delta)"))))

(deftest seam-drag-up-shrinks-list
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/start-seam-drag! (stub-event 500) 250)
      ;; Drag UP by 100px (pageY 400 < start-y 500) — list shrinks
      ;; by 100px → 150px target. dy = -100, new-height = 150.
      (resize-handle/seam-simulate-move! 400)
      (resize-handle/seam-simulate-up!))
    (let [height-events (filter #(= :rf.xray/set-events-list-height-px (first %))
                                @dispatches)]
      (is (some #(= 150 (second %)) height-events)
          "drag up shrinks: 250 - 100 = 150"))))

(deftest seam-pointer-cancel-tears-down
  (setup!)
  (resize-handle/start-seam-drag! (stub-event 500) 200)
  (is (true? (resize-handle/seam-dragging?))
      "drag installed")
  (resize-handle/seam-simulate-cancel!)
  (is (false? (resize-handle/seam-dragging?))
      "pointercancel teardown path"))

;; ---- clamp at write-time ------------------------------------------------

(deftest set-events-list-height-event-clamps-to-floor
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-events-list-height-px 10]))
  ;; 10 < 48 floor → clamps to 48.
  (is (= config/min-events-list-height-px
         (config/get-setting :general :events-list-height-px))
      "sub-floor request snaps to min-events-list-height-px"))

(deftest set-events-list-height-event-persists-in-range-value
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-events-list-height-px 320]))
  (is (= 320 (config/get-setting :general :events-list-height-px))
      "in-range value persists verbatim through the round-trip"))

(deftest clamp-events-list-height-pure-helper-snaps-non-numeric
  (testing "rf2-t2dsh — pure helper falls back to the default for
            non-numeric input so a malformed persisted payload never
            leaves the list at an unusable size."
    (is (= config/default-events-list-height-px
           (config/clamp-events-list-height-px "bogus" 1000))
        "string input → default")
    (is (= config/default-events-list-height-px
           (config/clamp-events-list-height-px nil 1000))
        "nil input → default")
    (is (= 200 (config/clamp-events-list-height-px 200 1000))
        "in-range numeric passes through")
    (is (= config/min-events-list-height-px
           (config/clamp-events-list-height-px -50 1000))
        "negative → floor")
    (is (= 700 (config/clamp-events-list-height-px 5000 1000))
        "above ceiling → viewport × 0.7")))

;; ---- double-click reset -------------------------------------------------

(deftest reset-event-restores-default
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-events-list-height-px 360]))
  (is (= 360 (config/get-setting :general :events-list-height-px))
      "events-list-height-px is 360 after explicit set")
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/reset-events-list-height]))
  (is (= config/default-events-list-height-px
         (config/get-setting :general :events-list-height-px))
      "reset event restored the default"))

(deftest double-click-handler-dispatches-reset
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch-impl (fn
                                      ([ev]       (swap! dispatches conj ev) nil)
                                      ([ev _opts] (swap! dispatches conj ev) nil))]
      (rf/with-frame :rf/xray
        (let [tree    (resize-handle/SeamHandle)
              handler (:on-double-click (second tree))]
          (is (fn? handler)
              "the seam node carries on-double-click")
          (handler nil))))
    (is (some #(= [:rf.xray/reset-events-list-height] %) @dispatches)
        "double-click dispatched the reset event")))

;; ---- keyboard navigation ------------------------------------------------

(defn- stub-key-event [key shift?]
  (let [prevented? (atom false)]
    {:event #js {:key            key
                 :shiftKey       shift?
                 :preventDefault (fn [] (reset! prevented? true))}
     :prevented? prevented?}))

(deftest seam-keydown-arrow-down-grows
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowDown" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    (is (some #(= [:rf.xray/set-events-list-height-px 208] %) @dispatches)
        "ArrowDown adds the 8px fine step to current-height")))

(deftest seam-keydown-arrow-up-shrinks
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowUp" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    (is (some #(= [:rf.xray/set-events-list-height-px 192] %) @dispatches)
        "ArrowUp subtracts the 8px fine step from current-height")))

(deftest seam-keydown-shift-arrow-uses-coarse-step
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowDown" true)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    (is (some #(= [:rf.xray/set-events-list-height-px 232] %) @dispatches)
        "Shift+ArrowDown uses the 32px coarse step (8 × 4)")))

(deftest seam-keydown-home-overshoots-to-upper-clamp
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "Home" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    (is (some #(= :rf.xray/set-events-list-height-px (first %)) @dispatches)
        "Home dispatched a set-events-list-height-px (registry clamps to ceiling)")))

(deftest seam-keydown-end-undershoots-to-lower-clamp
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "End" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    (is (some #(= :rf.xray/set-events-list-height-px (first %)) @dispatches)
        "End dispatched a set-events-list-height-px (registry clamps to floor)")))

(deftest seam-keydown-enter-dispatches-reset
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "Enter" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    (is (some #(= [:rf.xray/reset-events-list-height] %) @dispatches)
        "Enter dispatched the reset event")))

(deftest seam-keydown-space-dispatches-reset
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event " " false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    (is (some #(= [:rf.xray/reset-events-list-height] %) @dispatches)
        "Space dispatched the reset event")))

(deftest seam-keydown-unrecognised-key-no-op
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "Tab" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (is (false? (resize-handle/handle-seam-keydown! event 200))
          "unrecognised key returns false (bubble normally)"))
    (is (empty? @dispatches)
        "no dispatches for unrecognised key")))

;; ---- sub drives the L2 list height -------------------------------------

(deftest list-height-tracks-sub-update
  (testing "rf2-t2dsh — the L2 list's inline :height style reads from
            the events-list-height-px sub. After a set-events-list-
            height-px dispatch, the rendered tree carries the new
            height literal (px-suffixed string)."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-events-list-height-px 360]))
    (rf/with-frame :rf/xray
      (let [tree  (shell/shell-view {:mode :inline})
            list  (find-by-testid tree "rf-xray-event-list")
            style (:style (second list))]
        (is (= "360px" (:height style))
            "list :height updates to the persisted seam-handle value")))))

(deftest events-list-height-sub-defaults-to-published-default
  (setup!)
  (rf/with-frame :rf/xray
    (let [height @(rf/subscribe [:rf.xray/events-list-height-px])]
      (is (= config/default-events-list-height-px height)
          "fresh sub returns the published default"))))

(deftest events-list-height-sub-tracks-update
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-events-list-height-px 280]))
  (rf/with-frame :rf/xray
    (let [height @(rf/subscribe [:rf.xray/events-list-height-px])]
      (is (= 280 height)
          "sub reflects the latest update"))))
