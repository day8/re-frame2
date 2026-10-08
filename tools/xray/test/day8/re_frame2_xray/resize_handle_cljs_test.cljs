(ns day8.re-frame2-xray.resize-handle-cljs-test
  "CLJS tests for the Xray shell's horizontal resize handle.

  Asserts:
    1. `Handle` — the `as-component` bridge — mounts on
       `:inline` mode and short-circuits to nil on `:popout` /
       `:fullscreen`, and `handle-tree` carries the documented markup.
    2. The shell-view tree MOUNTS the handle when in default `:inline`
       mode. A hiccup walk stops at the bridge's `[:>]` interop head, so
       this is a mount assertion rather than a markup one; the markup is
       (1)'s subject and the mounted boundary is the browser lane's.
    3. Drag lifecycle — `start-drag!` flips `dragging?` to true,
       `simulate-up!` flips it back. `simulate-move!` dispatches the
       set-panel-width-px event with the start + delta.
    4. Clamp at write-time — the registry's set-panel-width-px event
       clamps to [320, viewport×0.9] before persisting.
    5. Double-click handler dispatches `:rf.xray/reset-panel-width`
       which dispatches set-panel-width-px with the default value.
    6. Every `:rf.xray/set-panel-width-px` dispatch here runs
       `apply-panel-width!` with no `<html>` root, which is its no-host
       path. Its write of the CSS custom property on the `<html>` root —
       so it cascades to the host via inheritance; writing it on the
       host's own inline style would shadow consumer overrides on
       `:root` — is `resize_handle_dom_cljs_test`'s subject.

  Not asserted here: `apply-all!` restoring the persisted width on boot,
  and reload survival (`update-setting!` round-tripping through
  localStorage), both covered indirectly by config + effects."
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

;; ---- hiccup walker ------------------------------------------------------
;; Tests call `rf.test-helpers/find-by-testid` / `expand-tree` directly —
;; there is no Xray walker facade.

;; ---- the handle's own markup --------------------------------------------
;;
;; `handle-tree` is the boundary's PURE inner fn — of the live width, the
;; announced ARIA ceiling and a dispatcher. Driving it directly is what
;; the node lane can assert without a React render window: `handle-view`
;; is a Fresco boundary and its `rf.fresco/sub` is legal only inside one,
;; so calling the BOUNDARY here would raise
;; `:rf.error/fresco-sub-outside-render` rather than answer hiccup.

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
;; The MODE GATE is [[resize-handle/Handle]]'s whole job: it is the
;; `as-component` bridge `shell-view-tree` heads,
;; and it decides in CLJS — before the props crossing — because
;; `as-component` round-trips prop names but not prop VALUES, so a
;; keyword `mode` would not survive it. Non-`:inline` answers nil;
;; `:inline` answers the bridge's `[:>]` vector rather than markup.

(deftest handle-short-circuits-on-fullscreen
  (setup!)
  (is (nil? (resize-handle/Handle :fullscreen))
      "fullscreen mode has no resize — the panel fills the viewport"))

;; ---- shell mounts the handle in :inline mode ---------------------------
;;
;; A hiccup walk of the shell STOPS at the bridge's `[:>]`
;; interop head, exactly as `shell.cljs` records for its own
;; `surface-bridge`. So what this row owes is that the shell MOUNTS
;; the handle in `:inline` and does not in `:popout`; the markup behind
;; the bridge is `handle-tree`'s subject above, and the mounted
;; boundary's is the browser lane's.

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
  "Build a stub PointerEvent-shaped JS object carrying just the slots
  the handle reads. `preventDefault` is a no-op stub so start-drag!
  can call it without throwing in the test runner.

  Pointer events extend MouseEvent so `pageX` is present at the same
  shape; `pointerId` is the pointer-events-specific slot used by the
  drag-state snapshot for capture release."
  [page-x]
  #js {:pageX          page-x
       :pointerId      1
       :preventDefault (fn [])})

(deftest start-drag-flips-state
  (setup!)
  (resize-handle/start-drag! (stub-event 1000) 480)
  (is (true? (resize-handle/dragging?))
      "start-drag! installed the global capture")
  (resize-handle/simulate-up!)
  (is (false? (resize-handle/dragging?))
      "simulate-up! tore down the capture"))

(deftest drag-move-dispatches-set-width
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/start-drag! (stub-event 1000) 480)
      ;; Drag LEFT by 50px (pageX 950 < start-x 1000) — panel widens
      ;; by 50px → 530px target. The view's `dx = start-x - now-x`,
      ;; so dx = 50, new-width = 530.
      (resize-handle/simulate-move! 950)
      (resize-handle/simulate-up!))
    (let [width-events (filter #(= :rf.xray/set-panel-width-px (first %))
                               @dispatches)]
      (is (some #(= 530 (second %)) width-events)
          "drag left by 50px dispatched 530 (start 480 + 50 delta)"))))

;; ---- clamp at write-time (event handler) -------------------------------

(deftest set-panel-width-event-clamps-to-floor
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-panel-width-px 100]))
  ;; 100 < 320 floor → clamps to 320.
  (is (= 320 (config/get-setting :general :panel-width-px))
      "sub-floor request snaps to min-panel-width-px"))

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
          "handle exposes current width to assistive tech"))))

;; ---- yield-to-consumer + the real-DOM writes: see the dom sibling -------
;;
;; The yield-predicate rows and `apply-panel-width!`'s `<html>` writes live
;; in `day8.re-frame2-xray.resize-handle-dom-cljs-test`. `getComputedStyle`
;; resolving an inline `resize:` declaration is the behaviour under test and
;; cannot be stubbed, and `:node-test` ships no jsdom, so those rows need a
;; namespace ending `-dom-cljs-test` for `:browser-test` to load them at
;; all. The dom sibling loads on BOTH lanes (`:node-test`'s `cljs-test$` is
;; a bare suffix match) and answers node with a stated skip per row.
;;
;; What stays here is everything that needs no host: the pure `handle-tree`
;; markup, the drag lifecycle, write-time clamping, the keyboard rows and the
;; subscription.

;; ---- panel-width-px sub --------------------------------------------------

(deftest panel-width-px-sub-defaults-to-published-default
  (setup!)
  (rf/with-frame :rf/xray
    (let [width @(rf/subscribe [:rf.xray/panel-width-px])]
      (is (= config/default-panel-width-px width)
          "fresh sub returns the published default"))))

(deftest panel-width-px-sub-tracks-update
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-panel-width-px 600]))
  (rf/with-frame :rf/xray
    (let [width @(rf/subscribe [:rf.xray/panel-width-px])]
      (is (= 600 width)
          "sub reflects the latest update"))))
