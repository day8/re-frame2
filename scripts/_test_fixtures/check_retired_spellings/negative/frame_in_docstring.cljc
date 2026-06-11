(ns fixture.negative.frame-in-docstring
  "NEGATIVE fixture: the retired shapes appearing only inside \"...\"-string
  literals (docstrings + prose) are documentation. String masking must keep
  the gate GREEN — including a multi-line docstring that spans the shapes."
  (:require [re-frame.interceptor :as interceptor]))

(defn handler
  "Reads the frame stamp. Historically this used the bare :frame coeffect via
  (interceptor/get-coeffect ctx :frame), (:frame (:coeffects ctx)), and
  (get-in ctx [:coeffects :frame]). The SSR redirect once accepted
  [:rf.server/redirect {:url x}] / {:to x}; both are retired. The canonical
  spellings are :rf.frame/id and :location respectively."
  [ctx]
  (interceptor/get-coeffect ctx :rf.frame/id))
