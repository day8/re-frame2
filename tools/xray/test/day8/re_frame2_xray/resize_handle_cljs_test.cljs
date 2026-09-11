(ns day8.re-frame2-xray.resize-handle-cljs-test
  "CLJS tests for the Xray shell's horizontal resize handle (rf2-x8h9y).

  Asserts:
    1. `Handle` — the `as-component` bridge since rf2-k97c.3 — mounts on
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
    6. `apply-panel-width!` writes the CSS custom property on the
       `<html>` root so it cascades to the host via inheritance
       (rf2-6fqr5 — writing it on the host's own inline style would
       shadow consumer overrides on `:root`).
    7. `apply-all!` restores the persisted width on boot.
    8. Reload survival — `update-setting!` round-trips through
       localStorage (covered indirectly by config + effects)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8): plain-atom + the `:runtime`
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
;; The private expand-tree / hiccup-seq / find-by-testid copies were
;; semantically identical to `re-frame.test-helpers`; tests call
;; `rf.test-helpers/find-by-testid` directly (rf2-vj80u8 — no Xray walker facade).

;; ---- the handle's own markup (rf2-k97c.3) -------------------------------
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

(deftest handle-tree-carries-the-documented-testid
  (setup!)
  (rf/with-frame :rf/xray
    (let [tree (handle-markup)]
      (is (some? tree)
          "handle-tree returns a hiccup tree")
      (is (= "rf-xray-resize-handle" (:data-testid (second tree)))
          "the testid is the documented contract"))))

;; ---- mount on :inline / short-circuit on others ------------------------
;;
;; The MODE GATE is [[resize-handle/Handle]]'s whole remaining job since
;; rf2-k97c.3: it is the `as-component` bridge `shell-view-tree` heads,
;; and it decides in CLJS — before the props crossing — because
;; `as-component` round-trips prop names but not prop VALUES, so a
;; keyword `mode` would not survive it. Non-`:inline` still answers nil;
;; `:inline` answers the bridge's `[:>]` vector rather than markup.

(deftest handle-mounts-the-bridge-on-inline-mode
  (setup!)
  (let [tree (resize-handle/Handle :inline)]
    (is (some? tree)
        "Handle mounts on :inline")
    (is (= :> (first tree))
        "it mounts through the as-component bridge's interop head")))

(deftest handle-short-circuits-on-popout
  (setup!)
  (is (nil? (resize-handle/Handle :popout))
      "popout mode has no handle — the OS-window's chrome handles resize"))

(deftest handle-short-circuits-on-fullscreen
  (setup!)
  (is (nil? (resize-handle/Handle :fullscreen))
      "fullscreen mode has no resize — the panel fills the viewport"))

;; ---- shell mounts the handle in :inline mode ---------------------------
;;
;; rf2-k97c.3 — a hiccup walk of the shell STOPS at the bridge's `[:>]`
;; interop head, exactly as `shell.cljs` already records for its own
;; `surface-bridge`. So what these two rows owe is that the shell MOUNTS
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
        (is (pos? inline)
            "the :inline shell carries interop heads")
        (is (= (inc popout) inline)
            "the :inline shell carries exactly ONE bridge more than
             :popout — the resize handle's")))))

(deftest shell-omits-resize-handle-in-popout
  (testing "popout mode hides the handle — separate OS window owns resize"
    (setup!)
    (rf/with-frame :rf/xray
      (let [tree (dynamic-shell-tree/shell-view-tree {:mode :popout})]
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-resize-handle"))
            "no handle markup in :popout mode")
        (is (nil? (resize-handle/Handle :popout))
            "and the mount site itself answers nil, which is what puts
             it there — the row above cannot distinguish an absent
             handle from one hidden behind a bridge")))))

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
  (is (false? (resize-handle/dragging?))
      "no drag in progress at fixture start")
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
      (is (seq width-events)
          "set-panel-width-px was dispatched at least once")
      (is (some #(= 530 (second %)) width-events)
          "drag left by 50px dispatched 530 (start 480 + 50 delta)"))))

(deftest drag-right-narrows-panel
  (setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/start-drag! (stub-event 1000) 800)
      ;; Drag RIGHT by 100px — pageX 1100 > start-x 1000 → dx = -100,
      ;; new-width = 700.
      (resize-handle/simulate-move! 1100)
      (resize-handle/simulate-up!))
    (let [width-events (filter #(= :rf.xray/set-panel-width-px (first %))
                               @dispatches)]
      (is (some #(= 700 (second %)) width-events)
          "drag right narrows: 800 - 100 = 700"))))

;; ---- clamp at write-time (event handler) -------------------------------

(deftest set-panel-width-event-clamps-to-floor
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-panel-width-px 100]))
  ;; 100 < 320 floor → clamps to 320.
  (is (= 320 (config/get-setting :general :panel-width-px))
      "sub-floor request snaps to min-panel-width-px"))

(deftest set-panel-width-event-persists-in-range-value
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-panel-width-px 720]))
  (is (= 720 (config/get-setting :general :panel-width-px))
      "in-range value persists verbatim through the round-trip"))

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
          (is (fn? handler)
              "the handle node carries on-double-click")
          (handler nil))))
    (is (some #(= [:rf.xray/reset-panel-width] %) @dispatches)
        "double-click dispatched the reset event")))

;; ---- keyboard navigation (rf2-70u8q a11y) -------------------------------

(defn- stub-key-event [key shift?]
  (let [prevented? (atom false)]
    {:event #js {:key            key
                 :shiftKey       shift?
                 :preventDefault (fn [] (reset! prevented? true))}
     :prevented? prevented?}))

(deftest keydown-arrow-left-widens
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowLeft" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    (is (some #(= [:rf.xray/set-panel-width-px 508] %) @dispatches)
        "ArrowLeft adds the 8px fine step to current-width")))

(deftest keydown-arrow-right-narrows
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowRight" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    (is (some #(= [:rf.xray/set-panel-width-px 492] %) @dispatches)
        "ArrowRight subtracts the 8px fine step from current-width")))

(deftest keydown-shift-arrow-uses-coarse-step
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "ArrowLeft" true)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    (is (some #(= [:rf.xray/set-panel-width-px 532] %) @dispatches)
        "Shift+ArrowLeft uses the 32px coarse step (8 × 4)")))

(deftest keydown-home-overshoots-to-upper-clamp
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "Home" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    (is (some #(= :rf.xray/set-panel-width-px (first %)) @dispatches)
        "Home dispatched a set-panel-width-px (registry clamp snaps to upper bound)")))

(deftest keydown-end-undershoots-to-lower-clamp
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "End" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    (is (some #(= :rf.xray/set-panel-width-px (first %)) @dispatches)
        "End dispatched a set-panel-width-px (registry clamp snaps to lower bound)")))

(deftest keydown-enter-dispatches-reset
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event "Enter" false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    (is (some #(= [:rf.xray/reset-panel-width] %) @dispatches)
        "Enter dispatched the reset event (matches double-click)")))

(deftest keydown-space-dispatches-reset
  (setup!)
  (let [dispatches (atom [])
        {:keys [event]} (stub-key-event " " false)]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (resize-handle/handle-keydown! event 500))
    (is (some #(= [:rf.xray/reset-panel-width] %) @dispatches)
        "Space dispatched the reset event")))

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
    (let [tree (handle-markup)
          props (second tree)]
      (is (= 0 (:tab-index props))
          "handle is keyboard-reachable via tab")
      (is (number? (:aria-valuenow props))
          "handle exposes current width to assistive tech")
      (is (= config/min-panel-width-px (:aria-valuemin props))
          "handle exposes minimum width to assistive tech"))))

;; ---- yield-to-consumer + the real-DOM writes: see the dom sibling -------
;;
;; rf2-r51p MOVED the yield-predicate rows and `apply-panel-width!`'s
;; `<html>` writes out to `day8.re-frame2-xray.resize-handle-dom-cljs-test`.
;; They were guarded by `(when (exists? js/document) ...)` in THIS file,
;; whose name does not end `-dom-cljs-test`, so `:browser-test` never loaded
;; them while `:node-test` -- which ships no jsdom -- skipped every one:
;; ELEVEN assertions executing in neither lane. `getComputedStyle` resolving
;; an inline `resize:` declaration is the behaviour under test and cannot be
;; stubbed, so the repair was the file LOCATION rather than the guard. The
;; dom sibling loads on BOTH lanes (`:node-test`'s `cljs-test$` is a bare
;; suffix match) and answers node with a stated skip per row.
;;
;; What stays here is everything that needs no host: the pure `handle-tree`
;; markup, the drag lifecycle, write-time clamping, the keyboard rows and the
;; subscription -- plus `apply-panel-width-handles-missing-root` below, which
;; asserts the NO-host path and so belongs on the node lane precisely because
;; there is no host here.

;; ---- apply-panel-width! (CSS var write) --------------------------------

(deftest apply-panel-width-handles-missing-root
  ;; Even without a layout host present, the call should not throw.
  (is (nil? (settings-effects/apply-panel-width! 480))
      "no-op safe pre-mount"))

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
