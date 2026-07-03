(ns fixture.live-where-cofx-source
  "Positive fixture — a LIVE :where :cofx as a runtime value in testbed
  source (outside any comment/string). The gate MUST fire on it.")

;; A testbed asserting on the trace shape. The map value below is LIVE code,
;; not a comment/string, so the retired :where :cofx value must be flagged.
(def expected-trace
  {:operation :rf.error/schema-validation-failure
   :tags      {:where :cofx}})
