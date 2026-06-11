(ns fixtures.trace-diagnostic-timestamp
  "NEGATIVE fixture: a trace/diagnostic `:detected-at` timestamp read inside a
  `(trace/emit! ...)` call. EP-0010 allowlists trace + diagnostics that do not
  influence durable writes; the `trace/emit!` wrapper in the window exempts the
  read even though `:detected-at` is in the durable-key set. Mirrors the real
  router/diagnostics.cljc site. Must stay GREEN (0 findings)."
  (:require [re-frame.interop :as interop]
            [re-frame.trace :as trace]))

(defn warn-unknown-opt
  [event unknown]
  (trace/emit! :warning
               :rf.warning/unknown-dispatch-opt
               {:event       event
                :unknown     unknown
                :detected-at (interop/now-ms)}))
