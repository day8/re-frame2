(ns realworld.article-editor-test
  "Headless tests for realworld.article-editor — create flow and
   navigation guard. Retrofitted under rf2-o8t6 to use Spec 014's
   `:rf.http/managed` substrate via the framework-shipped canned-stub fxs.
   Refactored under rf2-vxeg to query the `:ui/article-editor` parallel
   machine (regions `:mode` x `:lifecycle`) instead of the prior
   mode-flag + lifecycle-status shape."
  (:require [re-frame.core :as rf]
            [realworld.article-editor]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn editor-create-test []
  (th/reg-canned-success! :rf.http/managed.canned-editor-save
                          {:article {:slug "hello-world"
                                     :title "Hello"
                                     :description "Short"
                                     :body "Body"
                                     :tagList ["demo"]
                                     :createdAt "2026-05-01"
                                     :updatedAt "2026-05-01"
                                     :favorited false
                                     :favoritesCount 0
                                     :author {:username "alice" :bio nil :image nil :following false}}})

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-editor-save}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    ;; The :mode region starts at :create; the :lifecycle region starts
    ;; at :idle.
    (assert (= :create (rf/compute-sub [:editor/mode] (rf/get-frame-db f))))
    (assert (= :idle   (rf/compute-sub [:editor/status] (rf/get-frame-db f))))
    (rf/dispatch-sync [:editor/edit-field :title "Hello"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :description "Short"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :body "Body"] {:frame f})
    (rf/dispatch-sync [:editor/submit] {:frame f})
    ;; A successful submit advances :mode → :edit and :lifecycle → :saved.
    (assert (= :saved (rf/compute-sub [:editor/status] (rf/get-frame-db f))))
    (assert (= :edit  (rf/compute-sub [:editor/mode] (rf/get-frame-db f))))
    (assert (false? (rf/compute-sub [:editor/dirty?] (rf/get-frame-db f))))))

(defn editor-can-leave-test []
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (assert (true? (rf/compute-sub [:editor/can-leave?] (rf/get-frame-db f))))
    (rf/dispatch-sync [:editor/edit-field :title "Changed"] {:frame f})
    (assert (false? (rf/compute-sub [:editor/can-leave?] (rf/get-frame-db f))))))
