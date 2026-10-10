(ns fixtures.runtime-require
  "POSITIVE fixture for the RUNTIME REQUIRE surface — a shape a census over
  `ns` forms alone misses.

  This is NOT an `(ns ... (:require ...))` edge. It is a top-level runtime
  `(require '[...])`, which clj-kondo's namespace-usage analysis does not
  report at all, so a census built on that analysis is one edge short for
  every such require. A ratchet built on the ns form alone reads this file
  green (1 finding: the bare `directory` alias)."
  (:require [re-frame.core :as rf]))

;; Deferred so the late-bind directory is not a load-order dependency.
(defn ensure-directory! []
  (require '[re-frame.late-bind.directory :as directory])
  (rf/console :log (directory/entries)))
