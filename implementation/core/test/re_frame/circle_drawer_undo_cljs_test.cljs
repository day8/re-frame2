(ns re-frame.circle-drawer-undo-cljs-test
  "Behavioural contract for the Circle Drawer example's undo/redo engine
  (`seven-guis.circle-drawer.core` — rf2-r5rsgz). The 7GUIs undo/redo
  challenge keeps history-as-data via a single `:drawer/undoable`
  interceptor plus sibling `:drawer/undo` / `:drawer/redo` events; before
  this test only its ns-load schema-scoping was covered
  (`re-frame.example-frame-scoping-cljs-test`), never the actual behaviour.

  Why HERE and not under examples/: the example tree is test-free
  (rf2-8cevm), and the entry ns is a Reagent-coupled `.cljs`-only namespace.
  Requiring it under the consolidated `:node-test` build fires its ns-load
  `reg-interceptor` / `reg-event` forms; we then drive the real handlers with
  `dispatch-sync` — the same posture as the sibling example tests.

  We dispatch on an ANONYMOUS frame (not `:rf/default`) on purpose: the
  example binds its `[:drawer]` app-schema to `:rf/default` at ns-load, but
  the interceptor + events resolve from the (global) registrar / default
  image regardless of frame. Driving an anon frame therefore exercises the
  undo behaviour in isolation, without coupling to the coordinate schema
  (validated separately in the frame-scoping test).

  Contracts pinned:
    - `:drawer/add-circle` records exactly ONE undo step and clears redo;
    - a whole slider RESIZE (open + N draft drags + close) collapses to
      exactly ONE undo step — the drag is draft-only and skips the
      interceptor;
    - undo restores the prior `:circles` and parks the current on `:redo`;
      redo round-trips;
    - a new action after undo clears `:redo`, and `:next-id` (outside the
      snapshot) keeps climbing so ids never reissue;
    - the interceptor's `not=` guard records NO undo step for an undoable
      handler that leaves `:circles` unchanged."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; Required for its ns-load side effects: the `:drawer/undoable`
            ;; interceptor + the drawer events/handlers under test.
            [seven-guis.circle-drawer.core]))

;; `:ambient-frame nil` — this suite creates its own top-level (anon) frames,
;; so it wants a clear ambient scope (the frame-scoping sibling uses the same
;; opt-out for the same reason).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

(defn- drawer
  "The `:drawer` slice of frame `f`'s app-db."
  [f]
  (:drawer (rf/app-db-value f)))

(defn- boot!
  "A fresh anon frame with the drawer initialised to a blank canvas."
  []
  (let [f (frame/make-anon-frame-record! {:doc "circle-drawer undo test"})]
    (rf/dispatch-sync [:drawer/initialise] {:frame f})
    f))

(deftest initialise-seeds-empty-history
  (let [d (drawer (boot!))]
    (is (= [] (:circles d)))
    (is (= [] (:undo d)))
    (is (= [] (:redo d)))
    (is (= 1 (:next-id d)) "id dispenser starts at 1")))

(deftest add-circle-records-exactly-one-undo-step
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
    (let [d (drawer f)]
      (is (= 1 (count (:circles d))) "one circle added")
      (is (= {:id 1 :x 10 :y 20 :radius 30} (first (:circles d)))
          "circle takes the dispensed id + default radius")
      (is (= 1 (count (:undo d))) "exactly ONE undo step recorded")
      (is (= [] (peek (:undo d)))
          "the recorded snapshot is the pre-add (empty) :circles")
      (is (= [] (:redo d)) "redo is cleared by a new undoable action")
      (is (= 2 (:next-id d)) "next-id advanced past the issued id"))))

(deftest resize-via-dialog-collapses-to-one-undo-step
  (testing "open-dialog + N draft drags + close-dialog adds exactly ONE undo
            step; the drags themselves add none (draft-only, skip interceptor)"
    (let [f (boot!)]
      (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
      (let [undo-before (count (:undo (drawer f)))
            cid         (:id (first (:circles (drawer f))))]
        (rf/dispatch-sync [:drawer/open-dialog cid] {:frame f})
        (rf/dispatch-sync [:drawer/dialog-drag 50] {:frame f})
        (rf/dispatch-sync [:drawer/dialog-drag 70] {:frame f})
        (rf/dispatch-sync [:drawer/dialog-drag 90] {:frame f})
        ;; Mid-drag: the on-screen circle keeps its committed radius and NO
        ;; undo step has been recorded yet.
        (is (= undo-before (count (:undo (drawer f))))
            "dragging the slider records NO undo steps")
        (is (= 30 (:radius (first (:circles (drawer f)))))
            "the circle keeps its committed radius while dragging (draft-only)")
        (is (= 90 (:draft-radius (:dialog (drawer f))))
            "only the dialog draft moves during a drag")
        (rf/dispatch-sync [:drawer/close-dialog] {:frame f})
        (let [d (drawer f)]
          (is (= (inc undo-before) (count (:undo d)))
              "the entire resize collapses to exactly ONE undo step")
          (is (= 90 (:radius (first (:circles d))))
              "the drafted radius is committed on close")
          (is (nil? (:dialog d)) "dialog closed"))))))

(deftest undo-then-redo-round-trips-circles
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
    (let [after-add (:circles (drawer f))]
      (rf/dispatch-sync [:drawer/undo] {:frame f})
      (let [d (drawer f)]
        (is (= [] (:circles d)) "undo restored the pre-add (empty) :circles")
        (is (= 1 (count (:redo d))) "the current :circles were parked on :redo")
        (is (= after-add (peek (:redo d))) "…and it is exactly what was undone")
        (is (= [] (:undo d)) "the consumed undo step was popped"))
      (rf/dispatch-sync [:drawer/redo] {:frame f})
      (let [d (drawer f)]
        (is (= after-add (:circles d)) "redo restored the circle")
        (is (= [] (:redo d)) "the redo step was consumed")
        (is (= 1 (count (:undo d))) "current :circles parked back on :undo")))))

(deftest new-action-after-undo-clears-redo-and-ids-never-reissue
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})   ;; id 1, next-id -> 2
    (rf/dispatch-sync [:drawer/undo] {:frame f})               ;; circles [], redo has 1
    (is (= 1 (count (:redo (drawer f)))) "redo populated after undo")
    (rf/dispatch-sync [:drawer/add-circle 30 40] {:frame f})   ;; a NEW undoable action
    (let [d (drawer f)]
      (is (= [] (:redo d))
          "a new action after undo clears the redo stack (the undone future is gone)")
      (is (= 1 (count (:circles d))) "one circle present")
      (is (= 2 (:id (first (:circles d))))
          "the new circle got id 2 — :next-id sits OUTSIDE the undo snapshot, so ids never reissue")
      (is (= 3 (:next-id d)) "next-id keeps climbing"))))

(deftest no-op-undoable-event-records-no-undo-step
  (testing "close-dialog wears :drawer/undoable but, when the draft equals the
            committed radius, it leaves :circles unchanged — the interceptor's
            not= guard must then record NO undo step"
    (let [f (boot!)]
      (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
      (let [cid         (:id (first (:circles (drawer f))))
            undo-before (count (:undo (drawer f)))]
        (rf/dispatch-sync [:drawer/open-dialog cid] {:frame f})
        ;; close without dragging → draft == committed radius (30) → no change
        (rf/dispatch-sync [:drawer/close-dialog] {:frame f})
        (let [d (drawer f)]
          (is (= undo-before (count (:undo d)))
              "an undoable handler that changed nothing records NO undo step")
          (is (= 30 (:radius (first (:circles d)))) "radius unchanged")
          (is (nil? (:dialog d)) "dialog still closed"))))))
