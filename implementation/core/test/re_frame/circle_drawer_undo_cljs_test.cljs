(ns re-frame.circle-drawer-undo-cljs-test
  "Undo/redo behaviour of the Circle Drawer example
  (`seven-guis.circle-drawer.core`): one `:drawer/undoable` interceptor plus
  sibling `:drawer/undo` / `:drawer/redo` events. Driven on an anonymous frame,
  so the `[:drawer]` schema the example binds to `:rf/default` stays out of the
  way; `re-frame.example-frame-scoping-cljs-test` covers that binding."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [seven-guis.circle-drawer.core]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- drawer
  "The `:drawer` slice of frame `f`'s app-db."
  [f]
  (:drawer (rf/app-db-value f)))

(defn- boot!
  "A fresh anon frame with the drawer initialised to a blank canvas."
  []
  (let [f (rf.frame/make-anon-frame-record! {:doc "circle-drawer undo test"})]
    (rf/dispatch-sync [:drawer/initialise] {:frame f})
    f))

(deftest add-circle-records-exactly-one-undo-step
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
    (is (= {:circles [{:id 1 :x 10 :y 20 :radius 30}]
            :next-id 2
            :dialog  nil
            :undo    [[]]
            :redo    []}
           (drawer f)))))

(deftest resize-via-dialog-collapses-to-one-undo-step
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
    (rf/dispatch-sync [:drawer/open-dialog 1] {:frame f})
    (rf/dispatch-sync [:drawer/dialog-drag 50] {:frame f})
    (rf/dispatch-sync [:drawer/dialog-drag 90] {:frame f})
    (rf/dispatch-sync [:drawer/close-dialog] {:frame f})
    (is (= {:circles [{:id 1 :x 10 :y 20 :radius 90}]
            :next-id 2
            :dialog  nil
            :undo    [[] [{:id 1 :x 10 :y 20 :radius 30}]]
            :redo    []}
           (drawer f))
        "the drags are draft-only, so the whole resize is one undo step holding the pre-resize circles")))

(deftest undo-then-redo-round-trips-circles
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
    (let [after-add (drawer f)]
      (rf/dispatch-sync [:drawer/undo] {:frame f})
      (is (= {:circles [] :next-id 2 :dialog nil :undo [] :redo [(:circles after-add)]}
             (drawer f)))
      (rf/dispatch-sync [:drawer/redo] {:frame f})
      (is (= after-add (drawer f))))))

(deftest new-action-after-undo-clears-redo-and-ids-never-reissue
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
    (rf/dispatch-sync [:drawer/undo] {:frame f})
    (rf/dispatch-sync [:drawer/add-circle 30 40] {:frame f})
    (is (= {:circles [{:id 2 :x 30 :y 40 :radius 30}]
            :next-id 3
            :dialog  nil
            :undo    [[]]
            :redo    []}
           (drawer f))
        ":next-id sits outside the undo snapshot, so the undone id 1 is never reissued")))

(deftest no-op-undoable-event-records-no-undo-step
  (let [f (boot!)]
    (rf/dispatch-sync [:drawer/add-circle 10 20] {:frame f})
    (let [after-add (drawer f)]
      (rf/dispatch-sync [:drawer/open-dialog 1] {:frame f})
      (rf/dispatch-sync [:drawer/close-dialog] {:frame f})
      (is (= after-add (drawer f))
          "close-dialog with an undragged draft leaves :circles unchanged, so the interceptor's not= guard records nothing"))))
