(ns fixture.positive.frame-of-coeffects
  "POSITIVE fixture: the retired (:frame (:coeffects ctx)) keyword-fn read
  off the :coeffects map.")

(defn handler [ctx]
  ;; RETIRED: keyword-fn read of the bare :frame coeffect.
  (let [frame (:frame (:coeffects ctx))]
    (assoc ctx :seen frame)))
