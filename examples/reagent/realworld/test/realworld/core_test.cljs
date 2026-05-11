(ns realworld.core-test
  "Top-level smoke for realworld.core — boots the app and checks that
   the per-feature initialisers populate the expected slices.

   Every managed-HTTP call is routed via a `:rf.http/managed` override
   that delegates to the framework-shipped `:rf.http/managed-canned-success`
   stub (Spec 014 §Testing). Each call resolves with an empty
   map success — enough to exercise the on-create initialise fanout
   without any per-feature-shape assumptions."
  (:require [re-frame.core :as rf]
            [realworld.core]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

;; A generic success stub: every :rf.http/managed call resolves :success
;; with an empty map. Tests register a richer canned response when they
;; need specific payloads.
(th/reg-canned-success! :rf.http/managed.canned-success-empty {})

(defn app-smoke-test []
  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:rf.http/managed      :rf.http/managed.canned-success-empty
                                                :auth.session/persist :rf/no-op}})]
    ;; After init: auth + articles slices and the :realworld/tags
    ;; and :settings/form machine snapshots are present. The tags
    ;; machine replaces the slice-form `:tags` resource; the
    ;; settings/form machine replaces the slice-form `:settings`
    ;; resource.
    (let [db (rf/get-frame-db f)]
      (assert (contains? db :auth))
      (assert (contains? db :articles))
      (assert (contains? (:rf/machines db) :realworld/tags))
      (assert (contains? (:rf/machines db) :settings/form)))))
