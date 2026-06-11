(ns fixture.negative.frame-trace-tag
  "NEGATIVE fixture: the SANCTIONED trace / error-record :frame tag. The
  frame id is READ from the :rf.frame/id coeffect and STAMPED onto a trace
  under the :frame tag — the value flows :rf.frame/id -> :frame tag. Must
  stay GREEN (rf2-7d30s error-emit frame-stamp pass)."
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.trace :as trace]))

(defn emit-with-frame [ctx]
  (let [frame-id (interceptor/get-coeffect ctx :rf.frame/id)]
    ;; Sanctioned: :frame TAG on a trace event (value = the :rf.frame/id read).
    (trace/emit! :warning :rf.warning/something {:frame frame-id})))
