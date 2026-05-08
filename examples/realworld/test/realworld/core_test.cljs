(ns realworld.core-test
  "Top-level smoke for realworld.core — boots the app and checks that
   the per-feature initialisers populate the expected slices. Extracted
   from realworld/core.cljs under rf2-4v73.

   The test relies on a generic `:http.canned-success` stub (every :http
   call resolves :on-success with an empty map). It used to live next to
   the production :http fx in realworld/http.cljs; per the rf2-4v73
   cleanup it now lives with the test that uses it."
  (:require [re-frame.core :as rf]
            [realworld.core])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

;; ----------------------------------------------------------------------------
;; Test stub  (id-valued override per Spec 002 §override seam).
;;
;; Tests bind :http -> :http.canned-success in :fx-overrides on make-frame so
;; no real network calls happen. Per-test stubs (:http.canned-failure, etc.)
;; live alongside the test that needs them.
;; ----------------------------------------------------------------------------

(rf/reg-fx :http.canned-success
  {:doc       "Test stub: every :http call resolves :on-success with an
               empty map. Tests register a richer canned response when they
               need specific payloads."
   :platforms #{:client :server}}
  (fn fx-http-canned-success [{:keys [frame]} {:keys [on-success]}]
    (when on-success
      (rf/dispatch (conj on-success {}) {:frame frame}))))

(defn app-smoke-test []
  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:http :http.canned-success
                                                :auth.session/store :rf/no-op
                                                :auth.session/clear :rf/no-op}})]
    ;; After init: auth, articles, and tags slices are present.
    (let [db (rf/get-frame-db f)]
      (assert (contains? db :auth))
      (assert (contains? db :articles))
      (assert (contains? db :tags)))))
