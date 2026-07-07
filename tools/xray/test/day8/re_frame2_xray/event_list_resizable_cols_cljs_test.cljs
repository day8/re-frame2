(ns day8.re-frame2-xray.event-list-resizable-cols-cljs-test
  "CLJS tests for the L2 event-list's user-resizable columns (rf2-6ni62).

  Asserts the alignment-guarantee design + the drag lifecycle:

    1. The column-divider testids render between header cells.
    2. Header + row column widths come from the SAME source — after a
       `:rf.xray/set-event-list-col-width` dispatch, the header cell's
       :width matches the row cell's :width column-for-column.
    3. The drag lifecycle (`col-divider-start-drag!` / `simulate-move!`
       / `simulate-up!`) dispatches the live update event.
    4. Keyboard handler — Arrow keys + Enter route through the
       registry events with the right shape.
    5. The clamp-at-write-time semantics — a sub-floor dispatch lands
       on the floor in the persisted slot.
    6. The reset event restores the column's default width.

  The drag-simulation pattern mirrors `resize_handle_cljs_test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  ;; rf2-sdqsla — `reset-runtime!` folds sentinel + trace-collector +
  ;; settings reset into one call.
  (xray-test-support/reset-runtime!)
  ;; Force-cleanup any stale drag-state from a previous test (the
  ;; module-level defonce atom survives fixture reset).
  (shell/col-divider-simulate-up!))

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

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

(defn- style-of [node]
  (when (and (vector? node) (map? (second node)))
    (:style (second node))))

;; ---- trace-bus helpers (one cascade so the L2 list renders) -------------

(defn- dispatch-trace-ev
  "Mirrors the helper in `shell_cljs_test`. Minimal `:rf.event/dispatched`
  trace event so the projection produces a one-cascade list."
  [id event-vec]
  {:id           id
   :op-type      :rf.event
   :operation    :rf.event/dispatched
   :tags         {:rf.event/v          event-vec
                  :frame               :rf/default
                  :rf.trace/dispatch-id id}})

;; ---- 1. dividers render between header cells ----------------------------

(deftest column-dividers-render-between-header-cells
  (testing "rf2-6ni62 — the L2 column header carries one divider per
            user-resizable column (source, timestamp, duration)"
    (setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree     (shell/shell-view)
            dividers (find-all-by-testid-prefix
                       tree "rf-xray-event-list-col-divider-")]
        (is (some? (find-by-testid tree "rf-xray-event-list-col-divider-source"))
            "source-column divider renders")
        (is (some? (find-by-testid tree "rf-xray-event-list-col-divider-timestamp"))
            "timestamp-column divider renders")
        (is (some? (find-by-testid tree "rf-xray-event-list-col-divider-duration"))
            "duration-column divider renders")
        ;; Each divider appears at least once in the header + once per
        ;; row, so we expect at minimum 6 (one row + header = 2 instances
        ;; × 3 divider ids).
        (is (<= 6 (count dividers))
            "at least one divider per (header + per row) × 3 columns")))))

;; ---- 2. header + row widths come from the same source -------------------

(defn- timer-cascade-trace-ev [id event]
  ;; A cascade with a dispatched-time so the row renders its time chip,
  ;; and an `:after-timer` source (post-rf2-1ve9h — collapsed from the
  ;; prior `:rf/dispatch-origin :timer`) so the source column tag
  ;; renders.
  (-> (dispatch-trace-ev id event)
      (assoc :time 1000)
      (assoc-in [:tags :source] :after-timer)))

(deftest header-and-row-column-widths-stay-aligned-after-settings-write
  (testing "rf2-6ni62 — after a settings-write changes a column's
            width, the header cell and the row cell both read the new
            width from the same `:rf.xray/event-list-col-widths` sub.
            This is the alignment-guarantee design — no need to simulate
            the full drag; assert the rendered widths match the
            settings."
    (setup!)
    (trace-collector/seed-trace-for-test! (timer-cascade-trace-ev 1 [:poll/tick]))
    ;; Set a non-default width via the production event surface.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-event-list-col-width :timestamp 120]))
    (rf/with-frame :rf/xray
      (let [tree        (shell/shell-view)
            h-timestamp (find-by-testid tree "rf-xray-event-list-col-timestamp")
            r-time      (find-by-testid tree "rf-xray-row-time-chip")]
        (is (some? h-timestamp) "header timestamp cell renders")
        (is (some? r-time)      "row time chip renders")
        (is (= "120px" (:width (style-of h-timestamp)))
            "header carries the persisted 120px width")
        (is (= "120px" (:width (style-of r-time)))
            "row carries the SAME 120px width — alignment preserved")))))

(deftest header-and-row-widths-default-when-settings-unwritten
  (testing "rf2-6ni62 — with no settings write, the resolved widths
            default to `event-list-col-default-widths`. Header + row
            read identical widths (the alignment guarantee on the
            silent default path)"
    (setup!)
    (trace-collector/seed-trace-for-test! (timer-cascade-trace-ev 1 [:poll/tick]))
    (rf/with-frame :rf/xray
      (let [tree     (shell/shell-view)
            h-source (find-by-testid tree "rf-xray-event-list-col-source")
            r-source (find-by-testid tree "rf-xray-row-origin-after-timer")
            h-time   (find-by-testid tree "rf-xray-event-list-col-timestamp")
            r-time   (find-by-testid tree "rf-xray-row-time-chip")
            h-dur    (find-by-testid tree "rf-xray-event-list-col-duration")
            r-dur    (find-by-testid tree "rf-xray-row-duration")]
        (is (= "52px" (:width (style-of h-source)) (:width (style-of r-source)))
            "source column defaults to 52px on header + row")
        (is (= "76px" (:width (style-of h-time)) (:width (style-of r-time)))
            "timestamp column defaults to 76px on header + row")
        (is (= "60px" (:width (style-of h-dur)) (:width (style-of r-dur)))
            "duration column defaults to 60px on header + row")))))

;; ---- 3. drag lifecycle --------------------------------------------------

(defn- stub-event [page-x]
  #js {:pageX          page-x
       :pointerId      1
       :preventDefault (fn [])})

(deftest start-drag-flips-state
  (setup!)
  (is (false? (shell/col-divider-dragging?))
      "no drag in progress at fixture start")
  (shell/col-divider-start-drag! (stub-event 1000) :source 52)
  (is (true? (shell/col-divider-dragging?))
      "start-drag! installed the global capture")
  (shell/col-divider-simulate-up!)
  (is (false? (shell/col-divider-dragging?))
      "simulate-up! tore down the capture"))

;; rf2-8i1tg3 — the drag delta is INVERTED relative to the naive
;; "drag right widens" intuition. `event id` (flex 1 1 auto) is the
;; row's sole elastic column, far to the LEFT of every divider; it
;; silently absorbs whatever a resizable column gives up or takes. If
;; dragging right GREW `col-id` (the pre-fix behaviour — see the two
;; tests this replaced), `event id` would shrink by the same amount,
;; and the divider itself — sitting at `event id`'s trailing edge,
;; transitively — would move LEFT while the pointer moved RIGHT: a
;; 2×dx-per-frame divergence, the exact "handle recedes from the
;; cursor" bug. Shrinking `col-id` on a rightward drag makes the
;; divider's rendered position track the pointer 1:1 (and matches
;; standard split-pane semantics: dragging the boundary TOWARD a
;; column shrinks it).

(deftest drag-right-narrows-col-and-tracks-cursor
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (shell/col-divider-start-drag! (stub-event 1000) :source 52)
      ;; Divider sits to the LEFT of `source`. Dragging RIGHT
      ;; (pageX 1080 > 1000, dx +80) NARROWS source by 80 → -28,
      ;; which clamps to the 40px floor at write-time (`set-event-
      ;; list-col-width` applies the clamp; this test only asserts
      ;; the DISPATCHED delta, not the clamped persisted value —
      ;; see `set-event-list-col-width-clamps-to-floor` below).
      (shell/col-divider-simulate-move! 1080)
      (shell/col-divider-simulate-up!))
    (let [width-events (filter #(= :rf.xray/set-event-list-col-width (first %))
                               @dispatches)]
      (is (seq width-events)
          "set-event-list-col-width was dispatched at least once")
      (is (some #(= [:rf.xray/set-event-list-col-width :source -28] %)
                width-events)
          "drag right by 80px dispatched 52 - 80 = -28 (pre-clamp delta) — the divider's OWN position tracks the pointer because `event id` absorbs the +80 this column gives up"))))

(deftest drag-left-widens-col-and-tracks-cursor
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (shell/col-divider-start-drag! (stub-event 1000) :timestamp 200)
      ;; Drag LEFT by 50px (pageX 950 < 1000, dx -50) WIDENS timestamp:
      ;; 200 - (-50) = 250.
      (shell/col-divider-simulate-move! 950)
      (shell/col-divider-simulate-up!))
    (let [width-events (filter #(= :rf.xray/set-event-list-col-width (first %))
                               @dispatches)]
      (is (some #(= [:rf.xray/set-event-list-col-width :timestamp 250] %)
                width-events)
          "drag left widens: 200 - (-50) = 250"))))

(deftest drag-cancel-tears-down-state
  (setup!)
  (shell/col-divider-start-drag! (stub-event 1000) :duration 60)
  (is (true? (shell/col-divider-dragging?)))
  (shell/col-divider-simulate-cancel!)
  (is (false? (shell/col-divider-dragging?))
      "pointercancel tore down the capture cleanly"))

;; ---- 4. clamp at write-time --------------------------------------------

(deftest set-event-list-col-width-clamps-to-floor
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-event-list-col-width :source 10]))
  (is (= 40 (get-in (config/get-setting :general :event-list-col-widths)
                    [:source]))
      "sub-floor source request clamps to its 40px floor"))

(deftest set-event-list-col-width-passes-in-range
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-event-list-col-width :timestamp 110]))
  (is (= 110 (get-in (config/get-setting :general :event-list-col-widths)
                     [:timestamp]))
      "in-range timestamp value persists verbatim"))

(deftest set-event-list-col-width-ignores-unknown-col-id
  (setup!)
  (let [before (config/get-setting :general :event-list-col-widths)]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-event-list-col-width :event-id 999]))
    (is (= before (config/get-setting :general :event-list-col-widths))
        "unknown col-id (`:event-id` — flex; never sized) is a no-op")))

;; ---- 5. reset --------------------------------------------------

(deftest reset-restores-default
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-event-list-col-width :source 200]))
  (is (= 200 (get-in (config/get-setting :general :event-list-col-widths)
                     [:source])))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/reset-event-list-col-width :source]))
  (is (= 52 (get-in (config/get-setting :general :event-list-col-widths)
                    [:source]))
      "reset event restored the source column to its default 52px"))

;; ---- 6. keyboard navigation --------------------------------------------

(defn- stub-key-event [key shift?]
  (let [prevented? (atom false)]
    {:event #js {:key            key
                 :shiftKey       shift?
                 :preventDefault (fn [] (reset! prevented? true))}
     :prevented? prevented?}))

(deftest keydown-arrow-right-widens
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowRight" false)]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (shell/col-divider-handle-keydown! event :source 60))
    (is (some #(= [:rf.xray/set-event-list-col-width :source 70] %)
              @dispatches)
        "ArrowRight adds the 10px fine step (60 + 10 = 70)")))

(deftest keydown-arrow-left-narrows
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowLeft" false)]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (shell/col-divider-handle-keydown! event :timestamp 100))
    (is (some #(= [:rf.xray/set-event-list-col-width :timestamp 90] %)
              @dispatches)
        "ArrowLeft subtracts the 10px fine step (100 - 10 = 90)")))

(deftest keydown-shift-arrow-uses-coarse-step
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowRight" true)]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (shell/col-divider-handle-keydown! event :duration 60))
    (is (some #(= [:rf.xray/set-event-list-col-width :duration 90] %)
              @dispatches)
        "Shift+ArrowRight uses the 30px coarse step (10 × 3)")))

(deftest keydown-enter-resets
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "Enter" false)]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (shell/col-divider-handle-keydown! event :source 200))
    (is (some #(= [:rf.xray/reset-event-list-col-width :source] %)
              @dispatches)
        "Enter dispatched the reset event for the source column")))

(deftest keydown-other-keys-return-false
  (setup!)
  (let [{:keys [event]} (stub-key-event "x" false)]
    (is (false? (shell/col-divider-handle-keydown! event :source 60))
        "non-binding keys return false so the caller does not preventDefault")))

;; ---- divider double-click reset -----------------------------------------

(deftest divider-double-click-handler-dispatches-reset
  (setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (rf/with-frame :rf/xray
        (let [tree    (shell/shell-view)
              divider (find-by-testid
                        tree "rf-xray-event-list-col-divider-source")
              handler (:on-double-click (second divider))]
          (is (fn? handler)
              "the divider carries on-double-click")
          (handler nil))))
    (is (some #(= [:rf.xray/reset-event-list-col-width :source] %)
              @dispatches)
        "double-click dispatched the source-column reset event")))

;; ---- accessibility ---------------------------------------------------

(deftest divider-carries-aria-attributes
  (testing "rf2-6ni62 — each divider exposes WAI-ARIA separator with
            role + orientation + valuemin/max/now so screenreaders + the
            host's a11y tree announce the resize affordance correctly"
    (setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/xray
      (let [tree    (shell/shell-view)
            divider (find-by-testid
                      tree "rf-xray-event-list-col-divider-source")
            props   (second divider)]
        (is (= "separator" (:role props)))
        (is (= "vertical" (:aria-orientation props)))
        (is (string? (:aria-label props))
            "aria-label is a non-empty string")
        (is (= 40 (:aria-valuemin props))
            "aria-valuemin reads the source-column floor")
        (is (= 52 (:aria-valuenow props))
            "aria-valuenow reads the current width (default 52px)")
        (is (= 0 (:tab-index props))
            "tab-index 0 puts the divider into the keyboard tab order")))))
