(ns day8.re-frame2-xray.views.resizable-table-drag-cljs-test
  "rf2-65015d — column-drag pointer teardown.

  The resizable-table header gutter attaches window-level
  pointermove/up/cancel listeners on pointerdown. A missed pointerup
  or a pointercancel (touch gesture preempt, context menu, pointer
  leaving to another window) MUST tear the listeners down so a drag
  never strands a listener or piles a second pair on the next
  pointerdown (each orphan re-dispatching `resize-pair-tick` on every
  window pointermove).

  Node-test has no `js/window`, so the real addEventListener calls are
  skipped; these tests drive the drag STATE lifecycle — the observable
  proxy for the listener lifecycle, since `detach-window-listeners!`
  removes the listeners AND clears the state in one place. Mirrors
  `resize_handle_cljs_test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.popout-document :as popout-document]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

;; ---- fixture ------------------------------------------------------------
;; drag-state is a module-level defonce; clear any drag a test left
;; running so the lifecycle never leaks between tests.

(use-fixtures :each
  {:after (fn [] (when (rt/dragging?) (rt/simulate-cancel!)))})

;; ---- stubs --------------------------------------------------------------

(defn- stub-cell [width] #js {:offsetWidth width})

(defn- stub-pointer-event
  "PointerEvent-shaped stub carrying exactly the slots on-pointer-down
  reads: a currentTarget whose parentElement.querySelector resolves the
  two adjacent column cells by their `data-rf-xray-resizable-col`
  selector, plus clientX and no-op prevent/stop."
  [client-x left-id left-w right-id right-w]
  (let [by-sel {(str "[data-rf-xray-resizable-col='" (name left-id) "']")  (stub-cell left-w)
                (str "[data-rf-xray-resizable-col='" (name right-id) "']") (stub-cell right-w)}
        grid   #js {:querySelector (fn [sel] (get by-sel sel))}
        gutter #js {:parentElement grid}]
    #js {:currentTarget   gutter
         :clientX         client-x
         :preventDefault  (fn [])
         :stopPropagation (fn [])}))

;; ---- pointercancel tears down (the core acceptance) ---------------------

(deftest pointercancel-tears-down-drag
  (testing "a pointercancel mid-drag removes the listeners + clears the
            drag state (no stuck drag), and commits the last tick so
            stored widths match what is on screen"
    (let [d  (atom [])
          df (fn [ev] (swap! d conj ev))]
      (is (false? (rt/dragging?)) "no drag in progress at fixture start")
      (rt/on-pointer-down df :tbl :a :b (stub-pointer-event 100 :a 120 :b 80))
      (is (true? (rt/dragging?)) "pointerdown started a drag")
      (rt/simulate-move! 130)   ;; delta +30 → tick
      (rt/simulate-cancel!)     ;; system preempt
      (is (false? (rt/dragging?))
          "pointercancel tore the drag down — no listener/state stranded")
      (is (some #(= :rf.xray.column-widths/resize-pair-tick (first %)) @d)
          "the move ticked during the drag")
      (is (some #(= :rf.xray.column-widths/resize-pair-commit (first %)) @d)
          "cancel committed the last tick (stored == displayed)"))))

(deftest missed-pointerup-then-new-drag-never-orphans
  (testing "a missed pointerup leaves the drag state set; the next
            pointerdown defensively tears it down before attaching a
            fresh set, so a single up/cancel fully clears the state —
            no orphaned drag pinned, no listener pile-up"
    (let [d  (atom [])
          df (fn [ev] (swap! d conj ev))]
      ;; First drag — pointerup is NEVER delivered.
      (rt/on-pointer-down df :tbl :a :b (stub-pointer-event 100 :a 120 :b 80))
      (is (true? (rt/dragging?)))
      ;; A new drag begins with the old one still notionally live.
      (rt/on-pointer-down df :tbl :a :b (stub-pointer-event 200 :a 120 :b 80))
      (is (true? (rt/dragging?)) "still exactly one drag live")
      ;; One teardown clears everything — if the defensive detach had
      ;; NOT run, an orphan would remain after this single up.
      (rt/simulate-up!)
      (is (false? (rt/dragging?))
          "one teardown clears the state — the prior drag left no orphan"))))

(deftest pointerup-commits-and-tears-down
  (testing "the ordinary pointerup path still commits once + clears"
    (let [d  (atom [])
          df (fn [ev] (swap! d conj ev))]
      (rt/on-pointer-down df :tbl :a :b (stub-pointer-event 100 :a 120 :b 80))
      (rt/simulate-move! 90)    ;; delta -10
      (rt/simulate-up!)
      (is (false? (rt/dragging?)) "pointerup cleared the drag state")
      (is (= 1 (count (filter #(= :rf.xray.column-widths/resize-pair-commit (first %)) @d)))
          "exactly one commit per drag (one localStorage write, not one per pixel)"))))

;; ---- the gutter's own window (rf2-3x7nj.25.5) ---------------------------

(deftest drag-binds-the-gutters-own-window
  (testing "rf2-3x7nj.25.5 — in the pop-out, `js/window` is the OPENER,
            whose listeners the pop-out's pointer events never reach, so
            the Trace table's gutters could not be dragged there. The drag
            must bind to the gutter's OWN window (its document's
            `defaultView`), and detach from that same one."
    (popout-document/with-opener-globals
      (fn [{opener-window-listeners :window-listeners}]
        (let [{pdoc :doc pwin-listeners :window-listeners}
              (popout-document/mk-document)
              d  (atom [])
              df (fn [ev] (swap! d conj ev))
              e  (stub-pointer-event 100 :a 120 :b 80)]
          (set! (.. e -currentTarget -ownerDocument) pdoc)
          (rt/on-pointer-down df :tbl :a :b e)
          (is (= 1 (count (popout-document/listeners-on pwin-listeners "pointermove")))
              "the move listener sits on the POP-OUT window")
          (is (popout-document/detached? opener-window-listeners)
              "and the opener window received no listener at all")
          (when-let [on-move (first (popout-document/listeners-on pwin-listeners "pointermove"))]
            (on-move #js {:clientX 130}))
          (is (some #(= [:rf.xray.column-widths/resize-pair-tick :tbl :a 150 :b 50] %) @d)
              "a pop-out pointer move resizes the pair")
          (when-let [on-up (first (popout-document/listeners-on pwin-listeners "pointerup"))]
            (on-up #js {}))
          (is (false? (rt/dragging?)) "the pop-out release ends the drag")
          (is (some #(= :rf.xray.column-widths/resize-pair-commit (first %)) @d)
              "and commits it")
          (is (popout-document/detached? pwin-listeners)
              "detaching from the SAME window the drag attached to"))))))
