(ns fixtures.ambient-ok-getter-escape
  "NEGATIVE fixture: the conscious-allowlist escape applied to a CALL-WRAPPED
  browser read (rf2-vcjpx). `ambient_ok_escape.cljc` proves the escape works
  over an `(interop/now-ms)` clock read; this proves the same opt-out is
  available over the getter spellings the widened roster now matches — a
  deliberate diagnostic storage read in a durable-write namespace stays
  possible with a reviewed annotation, rather than the widening leaving an
  author no way out but to delete the read.

  Without the marker each `assoc` below is a finding. Must stay GREEN
  (0 findings)."
  (:require [re-frame.interop :as interop]))

(defn note-last-restore
  [entry]
  #_:rf.world/ambient-ok   ;; reviewed: dev-only breadcrumb, never folded into durable state
  (assoc entry :restored-at (.getItem js/localStorage "last-restore")))

(defn note-entry-url
  [entry]
  #_:rf.world/ambient-ok   ;; reviewed: diagnostic only, not consulted for durable writes
  (assoc entry :detected-at (.-href js/location)))
