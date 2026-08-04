(ns fixture.live-gen-requires-source)

;; ONE token per fixture: `:rf.gen/requires` read from a generation in live code.
(defn read-requires [gen]
  (:rf.gen/requires gen))
