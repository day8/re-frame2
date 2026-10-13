(ns day8.re-frame2-xray.resize-handle-cljs-test
  "CLJS tests for the Xray shell's horizontal resize handle: the mode gate,
  the handle's markup, the drag and keyboard dispatches, write-time
  clamping, the reset, and the width sub. The `<html>` writes of
  `apply-panel-width!` are `resize_handle_dom_cljs_test`'s subject."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: plain-atom + the `:runtime`
  ;; reset tier (sentinels + trace rings + persisted settings); `:post-reset`
  ;; force-clears the module-level drag-state defonce (survives the runtime
  ;; reset) so no stale drag leaks between tests.
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :post-reset (fn [] (resize-handle/simulate-up!))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- the handle's own markup --------------------------------------------
;;
;; `handle-tree` is the boundary's PURE inner fn; the boundary's
;; `rf.fresco/sub` is legal only inside a render.

(defn- handle-markup
  "The handle's node as `handle-view` composes it.

  The DISPATCHER is `(:dispatch (rf/capture-frame))`, the same door the
  boundary uses — not `rf/dispatch`. That is what routes through
  `rf/dispatch-impl`, so the `with-redefs` rows below see the events;
  passing `rf/dispatch` instead makes them silently observe nothing."
  []
  (resize-handle/handle-tree
    @(rf/subscribe [:rf.xray/panel-width-px])
    (resize-handle/aria-max-panel-width-px)
    (:dispatch (rf/capture-frame))))

;; ---- mount on :inline / short-circuit on others ------------------------
;;
;; [[resize-handle/Handle]] gates on mode in CLJS, because `as-component`
;; round-trips prop names but not keyword VALUES. The gate is an allowlist
;; of `:inline`, so a mode that is neither `:inline` nor `:popout` answers nil.

(deftest handle-short-circuits-on-overlay
  (setup!)
  (is (nil? (resize-handle/Handle :overlay))
      "only the :inline rail has a width to drag"))

;; ---- shell mounts the handle in :inline mode ---------------------------
;;
;; A hiccup walk of the shell stops at the bridge's `[:>]` interop head, so
;; this row counts mounts; the markup behind the bridge is `handle-tree`'s.

(defn- interop-heads
  "Every `[:> component …]` node in the expanded tree, in pre-order.
  The shell carries one for the Fresco surface and, in `:inline` only,
  one for the resize handle."
  [tree]
  (->> (rf.test-helpers/expand-tree tree)
       ;; `sequential?` as the branch test walks hiccup vectors and the
       ;; lazy child seqs `for` produces, and deliberately does NOT
       ;; descend attribute maps — nothing mounts from one.
       (tree-seq sequential? seq)
       (filter #(and (vector? %) (= :> (first %))))))

(deftest shell-mounts-resize-handle
  (testing "the resize handle is mounted in the shell tree when mode
            is :inline (the default for the production right-rail
            mount)"
    (setup!)
    (rf/with-frame :rf/xray
      (let [inline (count (interop-heads
                            (dynamic-shell-tree/shell-view-tree {:mode :inline})))
            popout (count (interop-heads
                            (dynamic-shell-tree/shell-view-tree {:mode :popout})))]
        (is (= (inc popout) inline)
            "the :inline shell carries exactly ONE bridge more than
             :popout — the resize handle's")))))

;; ---- drag lifecycle ----------------------------------------------------

(defn- stub-event
  "A PointerEvent-shaped stub carrying just the slots the handle reads."
  [page-x]
  #js {:pageX          page-x
       :pointerId      1
       :preventDefault (fn [])})

(deftest drag-move-dispatches-set-width
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/start-drag! (stub-event 1000) 480)
      ;; Dragging LEFT by 50px widens the panel: dx = start-x - now-x.
      (resize-handle/simulate-move! 950)
      (resize-handle/simulate-up!))
    (is (false? (resize-handle/dragging?)) "the release ends the drag")
    (let [width-events (filter #(= :rf.xray/set-panel-width-px (first %))
                               @dispatches)]
      (is (some #(= 530 (second %)) width-events)
          "drag left by 50px dispatched 530 (start 480 + 50 delta)"))))

;; ---- clamp at write-time (event handler) -------------------------------

(deftest set-panel-width-event-clamps-to-floor
  (setup!)
  (rf/with-frame :rf/xray
    (is (= config/default-panel-width-px @(rf/subscribe [:rf.xray/panel-width-px]))
        "a fresh sub returns the published default")
    (rf/dispatch-sync [:rf.xray/set-panel-width-px 100])
    (is (= [320 320] [(config/get-setting :general :panel-width-px)
                      @(rf/subscribe [:rf.xray/panel-width-px])])
        "a sub-floor request snaps to min-panel-width-px, and the sub follows
         the write")))

;; ---- double-click reset -------------------------------------------------

(deftest reset-event-restores-default
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-panel-width-px 720]))
  (is (= 720 (config/get-setting :general :panel-width-px))
      "panel-width-px is 720 after explicit set")
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/reset-panel-width]))
  (is (= config/default-panel-width-px
         (config/get-setting :general :panel-width-px))
      "reset event restored the default"))

(deftest reset-event-returns-to-the-host-configured-width
  (setup!)
  (try
    (config/configure! {:rf.xray/settings {:general {:panel-width-px 640}}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-panel-width-px 720])
      (rf/dispatch-sync [:rf.xray/reset-panel-width]))
    (is (= [640 false]
           [(config/get-setting :general :panel-width-px)
            (config/persisted-override? [:general :panel-width-px])])
        "reset lands where a boot without the drag would, and stores no width")
    (finally
      (config/reset-settings!))))

(deftest double-click-handler-dispatches-reset
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch-impl (fn
                                      ([ev]       (swap! dispatches conj ev) nil)
                                      ([ev _opts] (swap! dispatches conj ev) nil))]
      (rf/with-frame :rf/xray
        (let [tree    (handle-markup)
              handler (:on-double-click (second tree))]
          (handler nil))))
    (is (some #(= [:rf.xray/reset-panel-width] %) @dispatches)
        "double-click dispatched the reset event")))

;; ---- keyboard navigation (a11y) -----------------------------------------

(defn- stub-key-event [key shift?]
  (let [prevented? (atom false)]
    {:event #js {:key            key
                 :shiftKey       shift?
                 :preventDefault (fn [] (reset! prevented? true))}
     :prevented? prevented?}))

(defn- keydown-dispatches
  "Every event `handle-keydown!` dispatches for one keypress at a 500px
  panel width."
  [key shift?]
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event key shift?)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    @dispatches))

(deftest keydown-bindings-dispatch-their-documented-event
  (setup!)
  (are [key shift? expected] (some #(= expected %) (keydown-dispatches key shift?))
    "ArrowLeft"  false [:rf.xray/set-panel-width-px 508] ; widen by the 8px fine step
    "ArrowRight" false [:rf.xray/set-panel-width-px 492] ; narrow by the 8px fine step
    "ArrowLeft"  true  [:rf.xray/set-panel-width-px 532] ; Shift: the 32px coarse step (8 × 4)
    "Enter"      false [:rf.xray/reset-panel-width]      ; matches double-click
    " "          false [:rf.xray/reset-panel-width]))

(deftest keydown-home-and-end-overshoot-to-the-clamps
  (setup!)
  ;; Home overshoots and End undershoots; the registry clamp snaps the
  ;; dispatched width to the upper and lower bound respectively.
  (are [key] (some #(= :rf.xray/set-panel-width-px (first %))
                   (keydown-dispatches key false))
    "Home"
    "End"))

(deftest keydown-unrecognised-key-no-op
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "Tab" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (is (false? (resize-handle/handle-keydown! event 500))
          "unrecognised key returns false (no preventDefault, bubble normally)"))
    (is (empty? @dispatches)
        "no dispatches for unrecognised key")))

(deftest handle-renders-tabindex-and-aria-valuenow
  (setup!)
  (rf/with-frame :rf/xray
    (let [props (second (handle-markup))]
      (is (= ["rf-xray-resize-handle" 0 config/min-panel-width-px]
             ((juxt :data-testid :tab-index :aria-valuemin) props))
          "the documented testid, tab-reachable, floored at the minimum width")
      (is (number? (:aria-valuenow props))
          "handle exposes current width to assistive tech")
      ;; The separator role requires aria-valuemax beside min and now.
      (is (number? (:aria-valuemax props))
          "aria-valuemax is a number")
      (is (>= (:aria-valuemax props) (:aria-valuemin props))
          "aria-valuemax >= aria-valuemin"))))

;; The yield-to-consumer rows and `apply-panel-width!`'s `<html>` writes
;; need a real `getComputedStyle`: see `resize-handle-dom-cljs-test`.
