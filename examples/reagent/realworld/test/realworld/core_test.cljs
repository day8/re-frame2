(ns realworld.core-test
  "Top-level smoke for realworld.core — boots the app and checks that
   the per-feature initialisers populate the expected slices.

   Retrofitted under rf2-o8t6: the legacy `:http.canned-success` fixture
   has been replaced with a `:rf.http/managed` override that delegates
   to the framework-shipped `:rf.http/managed-canned-success` stub
   (Spec 014 §Testing). Every managed-HTTP call resolves with an empty
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
                                 :fx-overrides {:rf.http/managed     :rf.http/managed.canned-success-empty
                                                :auth.session/store  :rf/no-op
                                                :auth.session/clear  :rf/no-op}})]
    ;; After init: auth + articles slices and the :realworld/tags
    ;; machine snapshot are present. The tags machine replaces the
    ;; slice-form `:tags` resource (rf2-0i4y).
    (let [db (rf/get-frame-db f)]
      (assert (contains? db :auth))
      (assert (contains? db :articles))
      (assert (contains? (:rf/machines db) :realworld/tags)))))
