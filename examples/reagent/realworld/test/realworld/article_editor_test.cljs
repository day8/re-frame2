(ns realworld.article-editor-test
  "Headless tests for realworld.article-editor — create flow and
   navigation guard. Uses Spec 014's `:rf.http/managed` substrate via
   the framework-shipped canned-stub fxs. Queries the
   `:ui/article-editor` parallel machine (regions `:mode` x `:lifecycle`)."
  (:require [re-frame.core :as rf]
            [realworld.article-editor]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(defn editor-create-test []
  (th/reg-canned-success! :realworld.test/canned-editor-save
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

  (with-new-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-editor-save}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    ;; The :mode region starts at :create; the :lifecycle region starts
    ;; at :idle.
    (assert (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :mode/create] (rf/app-db-value f))))
    (assert (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :lifecycle/idle] (rf/app-db-value f))))
    ;; The :editor/can-submit? FLOW (Spec 013) starts false — the draft is
    ;; blank (invalid) and unchanged.
    (assert (false? (rf/compute-sub [:editor/can-submit?] (rf/app-db-value f))))
    (rf/dispatch-sync [:editor/edit-field :title "Hello"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :description "Short"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :body "Body"] {:frame f})
    ;; Now valid AND dirty → the flow materialised true into app-db at
    ;; [:editor :can-submit?] on the edit drains' post-walk.
    (assert (true? (rf/compute-sub [:editor/can-submit?] (rf/app-db-value f))))
    (rf/dispatch-sync [:editor/submit] {:frame f})
    ;; A successful submit advances :mode → :edit and :lifecycle → :saved.
    (assert (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :lifecycle/saved] (rf/app-db-value f))))
    (assert (true? (rf/compute-sub [:rf/machine-has-tag? :ui/article-editor :mode/edit] (rf/app-db-value f))))
    (assert (false? (rf/compute-sub [:editor/dirty?] (rf/app-db-value f))))))

(defn editor-can-leave-test []
  (with-new-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (assert (true? (rf/compute-sub [:editor/can-leave?] (rf/app-db-value f))))
    (rf/dispatch-sync [:editor/edit-field :title "Changed"] {:frame f})
    (assert (false? (rf/compute-sub [:editor/can-leave?] (rf/app-db-value f))))))
