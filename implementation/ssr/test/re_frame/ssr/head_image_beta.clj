(ns re-frame.ssr.head-image-beta
  "Test-support namespace BETA — the provenance twin of
  `re-frame.ssr.head-image-alpha`. See that namespace's docstring for why
  these are files rather than runtime-created namespaces.

  BETA registers the SAME ids as ALPHA with different bodies, and it is
  loaded/registered SECOND in every test, so the registrar atom holds
  BETA's body while ALPHA's survives only in the provenance source store —
  reachable through an image that selects ALPHA's namespace."
  (:require [re-frame.core :as rf]
            [re-frame.ssr.head-image-alpha :as rf.ssr.head-image-alpha]))

(defn register!
  "Register BETA's body for ALPHA's `head-id` and `projector-id`."
  []
  (rf/reg-head rf.ssr.head-image-alpha/head-id
    {:doc "BETA's body for the shared head id."}
    (fn [db _route]
      {:title (str "beta:" (:marker db))}))
  (rf/reg-error-projector rf.ssr.head-image-alpha/projector-id
    {:doc "BETA's body for the shared projector id."}
    (fn [_trace-event]
      {:status 451 :code :beta :message "beta" :retryable? false})))
