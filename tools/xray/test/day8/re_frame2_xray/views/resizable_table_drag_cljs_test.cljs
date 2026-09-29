(ns day8.re-frame2-xray.views.resizable-table-drag-cljs-test
  "Column-drag pointer teardown.

  The resizable-table header gutter attaches window-level
  pointermove/up/cancel listeners on pointerdown. A missed pointerup
  or a pointercancel (touch gesture preempt, context menu, pointer
  leaving to another window) MUST tear the listeners down so a drag
  never strands a listener or piles a second pair on the next
  pointerdown (each orphan re-dispatching `resize-pair-tick` on every
  window pointermove).

  Node-test has no `js/window`, so a gutter with no document attaches
  nothing and those rows drive the drag STATE lifecycle. The state is
  not a proxy for the listeners where a second drag overwrites it, so
  the rows that must see the listeners themselves give the gutter a
  document whose window records them (`popout-document/mk-document`).
  Mirrors `resize_handle_cljs_test`."
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
  (testing "a missed pointerup leaves the first drag's listeners bound;
            the next pointerdown defensively detaches them before
            attaching a fresh set, so the window holds exactly one set,
            a move dispatches once, and one up leaves no listener"
    ;; The gutter's document supplies a recording window, so the rows
    ;; below read the listeners actually attached and removed. The drag
    ;; state cannot stand in for them: the second pointerdown overwrites
    ;; it whether or not the first drag's listeners were detached.
    (let [{pdoc :doc wl :window-listeners} (popout-document/mk-document)
          d     (atom [])
          df    (fn [ev] (swap! d conj ev))
          down! (fn [client-x]
                  (let [e (stub-pointer-event client-x :a 120 :b 80)]
                    (set! (.. e -currentTarget -ownerDocument) pdoc)
                    (rt/on-pointer-down df :tbl :a :b e)))
          on    (fn [ev-name] (popout-document/listeners-on wl ev-name))]
      ;; First drag — pointerup is NEVER delivered.
      (down! 100)
      (let [first-move (first (on "pointermove"))]
        (is (fn? first-move) "the first drag bound a move listener")
        ;; A new drag begins with the old one still bound.
        (down! 200)
        (is (true? (rt/dragging?)) "still exactly one drag live")
        (doseq [ev-name ["pointermove" "pointerup" "pointercancel"]]
          (is (= 1 (count (on ev-name)))
              (str "exactly one " ev-name " listener — the first drag's was detached")))
        (is (not-any? #(identical? first-move %) (on "pointermove"))
            "the surviving move listener is the new drag's, not the first's")
        (reset! d [])
        (doseq [f (on "pointermove")] (f #js {:clientX 230}))
        (is (= [[:rf.xray.column-widths/resize-pair-tick :tbl :a 150 :b 50]] @d)
            "one window move dispatches one tick — no orphan re-dispatching")
        (doseq [f (on "pointerup")] (f #js {}))
        (is (false? (rt/dragging?))
            "one teardown clears the state")
        (is (popout-document/detached? wl)
            "and leaves no listener on the window")))))

(deftest pointerup-commits-and-tears-down
  (testing "the ordinary pointerup path commits once + clears"
    (let [d  (atom [])
          df (fn [ev] (swap! d conj ev))]
      (rt/on-pointer-down df :tbl :a :b (stub-pointer-event 100 :a 120 :b 80))
      (rt/simulate-move! 90)    ;; delta -10
      (rt/simulate-up!)
      (is (false? (rt/dragging?)) "pointerup cleared the drag state")
      (is (= 1 (count (filter #(= :rf.xray.column-widths/resize-pair-commit (first %)) @d)))
          "exactly one commit per drag (one localStorage write, not one per pixel)"))))

;; ---- the gutter's own window --------------------------------------------

(deftest drag-binds-the-gutters-own-window
  (testing "in the pop-out, `js/window` is the OPENER, whose listeners
            the pop-out's pointer events never reach, so binding there
            would leave the Trace table's gutters undraggable. The drag
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
