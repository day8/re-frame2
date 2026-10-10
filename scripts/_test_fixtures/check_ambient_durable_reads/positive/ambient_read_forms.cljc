(ns fixtures.ambient-read-forms
  "POSITIVE fixture: one ambient read per pattern shape in `_AMBIENT_READ_FORMS`,
  each written into the same durable `:instance-id` key so only the read form
  varies. Each call-wrapped browser read also witnesses the bare host symbol
  it contains."
  (:require [re-frame.interop :as interop]))

(defn derive-instance-id-from-ambient-reads
  [rows]
  [(assoc (nth rows 0) :instance-id (interop/epoch-now-ms))
   (assoc (nth rows 1) :instance-id (js/Date.now))
   (assoc (nth rows 2) :instance-id (.now js/Date))
   (assoc (nth rows 3) :instance-id (rand 1000))
   (assoc (nth rows 4) :instance-id (random-uuid))
   (assoc (nth rows 5) :instance-id (.getItem js/localStorage "session"))
   (assoc (nth rows 6) :instance-id (.-href js/location))
   (assoc (nth rows 7) :instance-id (.matchMedia js/window "(min-width: 40em)"))
   (assoc (nth rows 8) :instance-id (some-> (.-localStorage js/globalThis)
                                            (.getItem "session")))])
