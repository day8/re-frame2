(ns day8.re-frame2-xray.events-list-seam-cljs-test
  "CLJS tests for the L2/L3 events-list seam resize handle.

  Asserts:
    1. The seam's markup carries the documented testid, role, and ARIA
       slots (`separator`, horizontal orientation, live `aria-valuenow`).
    2. The shell tree carries the seam BETWEEN the event-list and the
       tab-bar (DOM-order contract — the seam IS the boundary).
    3. Drag lifecycle — `start-seam-drag!` flips `seam-dragging?` to
       true, and a release or a cancel flips it back. `seam-simulate-move!`
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
    9. The L2 list's inline style carries no `:resize` — there is no
       browser-native corner-grip; the seam is the sole vertical-resize
       affordance.
   10. The seam cursor is `row-resize` — the affordance hover signal."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-helpers.popout-document
             :as popout-document]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: plain-atom + the `:runtime`
  ;; reset tier (sentinels + trace rings + persisted settings); `:post-reset`
  ;; force-clears the module-level seam-drag defonce (survives the runtime
  ;; reset) so no stale drag leaks between tests.
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :post-reset (fn [] (resize-handle/seam-simulate-up!))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup walker ------------------------------------------------------
;; Tests call `rf.test-helpers/find-by-testid` directly — there is no Xray
;; walker facade.
;; `all-testids` (every carried testid in pre-order, for the DOM-order seam
;; assertion) is not exposed by test-helpers, so it is expressed over
;; `rf.test-helpers/find-by-testid-prefix` with the empty-prefix (matches every string
;; testid, depth-first).

(defn- all-testids
  "Return the testids of every hiccup node in `tree` that carries one,
  in pre-order. Used to assert DOM-order — the seam must sit between
  `rf-xray-event-list` and `rf-xray-tab-bar`."
  [tree]
  (map (comp :data-testid rf.test-helpers/attrs) (rf.test-helpers/find-by-testid-prefix tree "")))

;; ---- the seam's markup -------------------------------------------------
;;
;; The seam is `resize-handle/seam-handle-view`, a Fresco BOUNDARY. Its
;; `rf.fresco/sub` is
;; legal only inside a boundary render, so calling the boundary from the
;; node lane would raise `:rf.error/fresco-sub-outside-render` rather
;; than answer hiccup. These rows drive the boundary's PURE inner fn
;; instead, reading the same sub the boundary reads so `aria-valuenow`
;; is the LIVE height rather than a fixture constant.

(defn- seam-markup
  "The seam's node as `seam-handle-view` composes it.

  The DISPATCHER is `(:dispatch (rf/capture-frame))`, the same door the
  boundary uses — not `rf/dispatch`. That is what routes through
  `rf/dispatch-impl`, so the `with-redefs` row below sees the event;
  passing `rf/dispatch` instead makes it silently observe nothing."
  []
  (resize-handle/seam-handle-tree
    @(rf/subscribe [:rf.xray/events-list-height-px])
    (resize-handle/aria-max-events-list-height-px)
    (:dispatch (rf/capture-frame))))

;; ---- seam markup shape -------------------------------------------------

(deftest seam-handle-renders-with-aria-shape
  (setup!)
  (rf/with-frame :rf/xray
    (let [props (second (seam-markup))]
      (is (= ["rf-xray-event-list-seam" "separator" "horizontal" "Resize events list" 0
              config/min-events-list-height-px]
             ((juxt :data-testid :role :aria-orientation :aria-label :tab-index :aria-valuemin) props))
          "a tab-reachable WAI-ARIA separator floored at the published minimum")
      (is (number? (:aria-valuenow props))
          "live aria-valuenow exposes the current height to AT"))))

(deftest seam-handle-style-uses-row-resize-cursor
  ;; No native page pan and no text lasso while the seam is dragged.
  (setup!)
  (rf/with-frame :rf/xray
    (is (= ["row-resize" "none" "none"]
           ((juxt :cursor :touch-action :user-select) (:style (second (seam-markup))))))))

;; ---- shell DOM-order contract -------------------------------------------

(deftest shell-mounts-seam-between-list-and-tab-bar
  ;; The seam IS the L2/L3 boundary, so it sits between them in DOM order.
  (setup!)
  (rf/with-frame :rf/xray
    (let [testids (clj->js (all-testids (dynamic-shell-tree/shell-view-tree {:mode :inline})))]
      (is (< 0
             (.indexOf testids "rf-xray-event-list")
             (.indexOf testids "rf-xray-event-list-seam")
             (.indexOf testids "rf-xray-tab-bar"))
          "event list, then seam, then tab bar"))))

;; ---- drag lifecycle -----------------------------------------------------

(defn- stub-event
  "Build a stub PointerEvent-shaped JS object carrying just the slots
  the seam handle reads. `preventDefault` is a no-op stub so
  `start-seam-drag!` can call it without throwing in the test runner."
  [page-y]
  #js {:pageY          page-y
       :pointerId      1
       :preventDefault (fn [])})

;; Dragging DOWN grows the list: `seam-drag-binds-the-seams-own-document`
;; below drives a 500 → 600 move from 200px and reads exactly 300.

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

(deftest seam-drag-binds-the-seams-own-document
  (testing "in the pop-out, `js/document` names the
            OPENER's document. A seam drag bound there would never track
            the pop-out pointer or see its release, would override the
            host page's cursor, and would let a later hover over the host
            page drive the pop-out's list height. The drag must bind to the seam's
            OWN document, and detach from that same one."
    (popout-document/with-opener-globals
      (fn [{opener-listeners :listeners opener-doc :doc}]
        (let [{pdoc :doc plisteners :listeners} (popout-document/mk-document)
              dispatches (atom [])
              e          #js {:pageY          500
                              :pointerId      1
                              :currentTarget  #js {:ownerDocument pdoc}
                              :preventDefault (fn [])}]
          (resize-handle/start-seam-drag! e 200
                                          (fn [ev] (swap! dispatches conj ev)))
          (is (= 1 (count (popout-document/listeners-on plisteners "pointermove")))
              "the move listener sits on the POP-OUT document")
          (is (popout-document/detached? opener-listeners)
              "and the host page's document received no listener at all")
          (is (= "row-resize" (.. pdoc -body -style -cursor))
              "the drag cursor is the pop-out body's")
          (is (= "" (.. opener-doc -body -style -cursor))
              "and the host page's cursor is untouched")
          (when-let [on-move (first (popout-document/listeners-on plisteners "pointermove"))]
            (on-move #js {:pageY 600}))
          (is (= [[:rf.xray/set-events-list-height-px 300]] @dispatches)
              "a pop-out pointer move resizes the list")
          (when-let [on-up (first (popout-document/listeners-on plisteners "pointerup"))]
            (on-up #js {}))
          (is (false? (resize-handle/seam-dragging?))
              "the pop-out release ends the drag")
          (is (popout-document/detached? plisteners)
              "detaching from the SAME document the drag attached to")
          (is (= "" (.. pdoc -body -style -cursor))
              "and restoring the pop-out body's cursor"))))))

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

(deftest clamp-events-list-height-pure-helper-snaps-non-numeric
  ;; A malformed persisted payload never leaves the list at an unusable size.
  (are [v expected] (= expected (config/clamp-events-list-height-px v 1000))
    "bogus" config/default-events-list-height-px
    200     200
    -50     config/min-events-list-height-px
    5000    700))   ; viewport × 0.7

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
        (let [tree    (seam-markup)
              handler (:on-double-click (second tree))]
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

(defn- keydown-dispatches
  "Every event `handle-seam-keydown!` dispatches for one keypress at a
  200px list height."
  [key shift?]
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event key shift?)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-seam-keydown! event 200))
    @dispatches))

(deftest seam-keydown-bindings-dispatch-their-documented-event
  (setup!)
  (are [key shift? expected] (some #(= expected %) (keydown-dispatches key shift?))
    "ArrowDown" false [:rf.xray/set-events-list-height-px 208] ; grow by the 8px fine step
    "ArrowUp"   false [:rf.xray/set-events-list-height-px 192] ; shrink by the 8px fine step
    "ArrowDown" true  [:rf.xray/set-events-list-height-px 232] ; Shift: the 32px coarse step (8 × 4)
    "Enter"     false [:rf.xray/reset-events-list-height]
    " "         false [:rf.xray/reset-events-list-height]))

(deftest seam-keydown-home-and-end-overshoot-to-the-clamps
  (setup!)
  ;; Home overshoots and End undershoots; the registry clamp snaps the
  ;; dispatched height to the ceiling and the floor respectively.
  (are [key] (some #(= :rf.xray/set-events-list-height-px (first %))
                   (keydown-dispatches key false))
    "Home"
    "End"))

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
  (testing "the L2 list's inline :height style reads from
            the events-list-height-px sub. After a set-events-list-
            height-px dispatch, the rendered tree carries the new
            height literal (px-suffixed string)."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-events-list-height-px 360]))
    (rf/with-frame :rf/xray
      (let [tree  (dynamic-shell-tree/shell-view-tree {:mode :inline})
            list  (rf.test-helpers/find-by-testid tree "rf-xray-event-list")
            style (:style (second list))]
        (is (= "360px" (:height style))
            "list :height updates to the persisted seam-handle value")))))

