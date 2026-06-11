(ns fixture.positive.get-coeffect-frame
  "POSITIVE fixture: the retired (get-coeffect ctx :frame) accessor read.
  The bare :frame event-context coeffect was retired by rf2-1m6rf1 in
  favour of :rf.frame/id. This file plants the retired read so the gate
  fires; the runtime no longer injects :frame so this read returns nil."
  (:require [re-frame.interceptor :as interceptor]))

(defn handler [ctx]
  ;; RETIRED: should read :rf.frame/id, not :frame.
  (let [frame (interceptor/get-coeffect ctx :frame)]
    (assoc ctx :seen frame)))
