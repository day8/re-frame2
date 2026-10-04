(ns re-frame.trace.frame-accessor-cljs-test
  "Cross-tool parity test for the canonical raw-trace-event frame reader
  (`re-frame.trace/trace-event-frame` / `frame-of`).

  Raw trace events carry frame identity ONLY at
  `[:tags :frame]`; there is no public top-level `:frame` on the raw
  trace-event shape. Derived / projection records (event bundles,
  `:rf/epoch-record`s, dispatch consequences, cursor / summary records)
  carry frame identity at top-level `:frame` (`group-by-event`'s half of
  that is pinned in `re-frame.trace.projection-cljs-test`). One canonical reader,
  owned by the Spec 009 / trace contract, reads the raw shape:
  `(get-in trace-event [:tags :frame])`.

  Pure-data — no fixture, no frame, no router; JVM and CLJS run the same
  suite (the accessor is a tag read with no platform-specific arms).
  This is the SSOT acceptance test for the one reader through which a
  tool consumer (Xray, Story, story-mcp, machines-viz, re-frame2-pair)
  reads frame off a raw event."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.trace :as rf.trace]))

;; ---- raw trace-event shape: frame lives ONLY at [:tags :frame] ------------

(deftest trace-event-frame-reads-tags-frame
  (testing "the canonical reader returns the frame for a raw event shaped {:tags {:frame …}}"
    (let [raw {:operation :rf.event/dispatched
               :op-type   :rf.event
               :id        1
               :time      0
               :tags      {:frame :app/main
                           :rf.event/v [:user/login]
                           :rf.trace/dispatch-id 7}}]
      (is (= :app/main (rf.trace/trace-event-frame raw))
          "frame is read from [:tags :frame]")
      (is (= :app/main (rf.trace/frame-of raw))
          "frame-of is the same accessor (alias)"))))

(deftest trace-event-frame-ignores-top-level-frame-on-raw-shape
  (testing "the raw shape has NO public top-level :frame — a stray top-level :frame is NOT the raw frame slot"
    ;; Raw events carry frame ONLY at [:tags :frame]. The
    ;; canonical reader deliberately does not fall back to a top-level
    ;; :frame on the raw shape (that slot belongs to projection records).
    ;; A raw event whose :tags lack :frame reads nil even if some
    ;; top-level :frame is present — pinning the single-source contract.
    (let [raw-with-stray-top-level {:frame :stray/top-level
                                    :tags  {:rf.trace/dispatch-id 3}}]
      (is (nil? (rf.trace/trace-event-frame raw-with-stray-top-level))
          "the raw reader does not read top-level :frame"))))
