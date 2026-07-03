(ns re-frame.trace.frame-accessor-cljs-test
  "Cross-tool parity test for the canonical raw-trace-event frame reader
  (`re-frame.trace/trace-event-frame` / `frame-of`) — rf2-7737vq Stage A.

  Mike's ruling: raw trace events carry frame identity ONLY at
  `[:tags :frame]`; there is no public top-level `:frame` on the raw
  trace-event shape. Derived / projection records (event bundles,
  `:rf/epoch-record`s, dispatch consequences, cursor / summary records)
  carry frame identity at top-level `:frame`. One canonical reader,
  owned by the Spec 009 / trace contract, reads the raw shape:
  `(get-in trace-event [:tags :frame])`.

  Pure-data — no fixture, no frame, no router; JVM and CLJS run the same
  suite (the accessor is a tag read with no platform-specific arms).
  This is the SSOT acceptance test that every tool consumer (Xray,
  Story, story-mcp, machines-viz, re-frame2-pair) reads frame off a raw
  event through — Stage B migrates those call sites onto it."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.trace :as trace]
            [re-frame.trace.projection :as p]))

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
      (is (= :app/main (trace/trace-event-frame raw))
          "frame is read from [:tags :frame]")
      (is (= :app/main (trace/frame-of raw))
          "frame-of is the same accessor (alias)"))))

(deftest trace-event-frame-is-its-implementation
  (testing "the reader IS (get-in ev [:tags :frame]) — pinned so the wire shape can't drift"
    (let [raw {:tags {:frame :req/scoped}}]
      (is (= (get-in raw [:tags :frame])
             (trace/trace-event-frame raw))))))

(deftest trace-event-frame-nil-when-not-frame-qualified
  (testing "a frameless raw event (registry-time / boot-time, outside any cascade) reads nil"
    (is (nil? (trace/trace-event-frame {:operation :rf.registry/handler-registered
                                        :op-type   :rf.registry
                                        :tags      {}})))
    (is (nil? (trace/trace-event-frame {:tags nil})))
    (is (nil? (trace/trace-event-frame {})))))

(deftest trace-event-frame-ignores-top-level-frame-on-raw-shape
  (testing "the raw shape has NO public top-level :frame — a stray top-level :frame is NOT the raw frame slot"
    ;; The ruling: raw events carry frame ONLY at [:tags :frame]. The
    ;; canonical reader deliberately does not fall back to a top-level
    ;; :frame on the raw shape (that slot belongs to projection records).
    ;; A raw event whose :tags lack :frame reads nil even if some
    ;; top-level :frame is present — pinning the single-source contract.
    (let [raw-with-stray-top-level {:frame :stray/top-level
                                    :tags  {:rf.trace/dispatch-id 3}}]
      (is (nil? (trace/trace-event-frame raw-with-stray-top-level))
          "the raw reader does not read top-level :frame"))))

(deftest frame-of-alias-identity
  (testing "frame-of and trace-event-frame resolve to the same fn value"
    (is (= trace/trace-event-frame trace/frame-of))))

;; ---- derived / projection records: frame at TOP-LEVEL :frame --------------

(deftest projection-records-carry-frame-top-level
  (testing "group-by-event records expose frame at top-level :frame (NOT under :tags)"
    ;; The producer stamps :frame under [:tags :frame] on every raw event;
    ;; the projection hoists it to the cascade record's top-level :frame
    ;; slot (the bare record/projection vocabulary, Tool-Pair §Identity
    ;; spellings). This is the derived-record half of the two-layer ruling.
    (let [raw-events [{:op-type :rf.event :operation :rf.event/dispatched
                       :id 1 :tags {:frame :counter/a
                                    :rf.event/v [:inc]
                                    :rf.trace/dispatch-id 100}}
                      {:op-type :rf.sub :operation :rf.sub/run
                       :id 2 :tags {:frame :counter/a
                                    :rf.trace/dispatch-id 100}}]
          [record] (p/group-by-event raw-events)]
      (is (= :counter/a (:frame record))
          "the projected cascade record carries frame at top-level :frame")
      ;; And the canonical RAW reader still reads each underlying raw
      ;; event's frame off [:tags :frame] — the two layers coexist.
      (is (every? #(= :counter/a (trace/trace-event-frame %)) raw-events)
          "the raw events the record was projected from carry frame at [:tags :frame]"))))
