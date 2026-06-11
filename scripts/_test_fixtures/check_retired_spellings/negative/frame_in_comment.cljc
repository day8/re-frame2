(ns fixture.negative.frame-in-comment
  "NEGATIVE fixture: the retired shapes appearing only in `;`-comments are
  documentation, not reintroduced code. Comment masking must keep the gate
  GREEN."
  (:require [re-frame.interceptor :as interceptor]))

(defn handler [ctx]
  ;; Historical note: we used to do (interceptor/get-coeffect ctx :frame)
  ;; and (:frame (:coeffects ctx)) and (get-in ctx [:coeffects :frame]) —
  ;; all retired in favour of :rf.frame/id. We also dropped the redirect
  ;; :url / :to keys on [:rf.server/redirect {:url ...}] in favour of :location.
  (interceptor/get-coeffect ctx :rf.frame/id))
