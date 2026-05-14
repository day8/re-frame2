(ns re-frame.ssr.ring.response
  "Response materialisation — runtime accumulator → Ring response map.

  Per Spec 011 §HTTP response contract. `:redirect` short-circuits per
  §Redirect precedence — status + Location header, no body."
  (:require [re-frame.ssr.ring.headers :as headers]))

(set! *warn-on-reflection* true)

(defn ssr-response->ring-response
  "Materialise the runtime's resolved response accumulator (per
  Spec 011 §HTTP response contract) into a Ring response map. The
  `:body` arg is the rendered HTML (or nil for redirect-only
  responses). `:redirect` short-circuits per Spec 011 §Redirect
  precedence — status + Location header, no body."
  [{:keys [status headers cookies redirect]} body]
  (if redirect
    (let [{:keys [location url to] redirect-status :status} redirect
          target (or location url to)]
      {:status  (or redirect-status status 302)
       :headers (-> (headers/headers->ring-map headers)
                    (headers/append-set-cookies cookies)
                    (cond-> target (assoc "Location" target)))
       :body    ""})
    {:status  (or status 200)
     :headers (-> (headers/headers->ring-map headers)
                  (headers/append-set-cookies cookies))
     :body    (or body "")}))
