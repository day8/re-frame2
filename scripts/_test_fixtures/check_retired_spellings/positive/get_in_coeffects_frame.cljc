(ns fixture.positive.get-in-coeffects-frame
  "POSITIVE fixture: the retired (get-in ctx [:coeffects :frame]) path read
  through the :coeffects map (rf2-1m6rf1).")

(defn handler [ctx]
  ;; RETIRED: get-in path read of the bare :frame coeffect.
  (let [frame (get-in ctx [:coeffects :frame])]
    (assoc ctx :seen frame)))
