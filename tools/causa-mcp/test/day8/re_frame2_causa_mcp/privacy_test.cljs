(ns day8.re-frame2-causa-mcp.privacy-test
  "Unit tests for the B-1 privacy filter at the Causa-MCP boundary
  (rf2-8xzoe.11). Pins:

    - MUST 1 / MUST 2 — default-suppress `:sensitive? true` events on
      every trace-stream-shaped tool surface (`get-trace-buffer`,
      `subscribe`, `get-epoch-history`).
    - MUST 19 — the `:include-sensitive?` slot name is the cross-MCP
      opt-in vocabulary (pair2-mcp + story-mcp + causa-mcp share the
      identical arg).
    - Result-envelope counter shape — `:dropped-sensitive` is stamped
      iff the strip-step dropped at least one item; the zero-drop
      common path carries no counter (cross-MCP convention).

  These tests are the load-bearing pin for the 18 downstream tool
  beads — every dispatcher that emits trace-stream-shaped payloads
  calls `privacy/apply-to-result` once at the boundary; the contract
  here is the one those tools inherit."
  (:require [applied-science.js-interop :as j]
            [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.privacy :as privacy]))

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "B-1 lands the privacy boundary helpers downstream tool
            dispatchers will require"
    (is (fn? privacy/sensitive-event?))
    (is (fn? privacy/strip-sensitive))
    (is (fn? privacy/parse-include-sensitive))
    (is (fn? privacy/stamp-dropped-sensitive))
    (is (fn? privacy/apply-to-result))
    (is (false? privacy/include-sensitive-default)
        "spec/009 default-suppress posture: include-sensitive? defaults false")))

;; ---------------------------------------------------------------------------
;; sensitive-event? — the boolean predicate, delegated to mcp-base.
;; ---------------------------------------------------------------------------

(deftest sensitive-event?-true-stamp-detected
  (is (privacy/sensitive-event? {:operation :event/dispatched :sensitive? true})))

(deftest sensitive-event?-false-stamp-passes
  (is (not (privacy/sensitive-event? {:operation :event/dispatched :sensitive? false}))))

(deftest sensitive-event?-absent-stamp-passes
  ;; Per spec/009: "Consumers treat absent as `false`."
  (is (not (privacy/sensitive-event? {:operation :event/dispatched}))))

(deftest sensitive-event?-non-map-input-passes
  (is (not (privacy/sensitive-event? nil)))
  (is (not (privacy/sensitive-event? [:sensitive? true])))
  (is (not (privacy/sensitive-event? "anything"))))

;; ---------------------------------------------------------------------------
;; strip-sensitive — the per-batch default-suppress filter (MUST 1/2 spine).
;; ---------------------------------------------------------------------------

(deftest strip-sensitive-default-drops-true-stamps
  (let [evts [{:id 1 :sensitive? false}
              {:id 2 :sensitive? true}
              {:id 3}
              {:id 4 :sensitive? true}]
        [kept dropped] (privacy/strip-sensitive evts false)]
    (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
    (is (= 2 dropped))))

(deftest strip-sensitive-include-opt-in-passes-everything
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? false}
              {:id 3 :sensitive? true}]
        [kept dropped] (privacy/strip-sensitive evts true)]
    (is (= evts kept))
    (is (zero? dropped))))

(deftest strip-sensitive-empty-batch-zero-overhead
  (let [[kept dropped] (privacy/strip-sensitive [] false)]
    (is (= [] kept))
    (is (zero? dropped))))

(deftest strip-sensitive-no-sensitive-events-zero-drop-identity-vector
  ;; Fast-path (rf2-0q30r): when the scan finds no sensitive items the
  ;; original vector is returned identity-equal and `dropped` is zero.
  (let [evts [{:id 1} {:id 2 :sensitive? false} {:id 3}]
        [kept dropped] (privacy/strip-sensitive evts false)]
    (is (identical? evts kept)
        "common-path optimisation: identical-vector return when nothing drops")
    (is (zero? dropped))))

(deftest strip-sensitive-on-epoch-records-via-top-level-stamp
  ;; The causa runtime stamps the epoch-level `:sensitive?` rollup at
  ;; assembly time (rf2-isdwf) — the per-event predicate fires on the
  ;; same top-level slot whether the map is a trace event or an epoch
  ;; record. No special-case helper needed.
  (let [epochs [{:epoch-id 1 :event-id :cart/add}
                {:epoch-id 2 :event-id :auth/sign-in :sensitive? true}
                {:epoch-id 3 :event-id :nav/route}]
        [kept dropped] (privacy/strip-sensitive epochs false)]
    (is (= [{:epoch-id 1 :event-id :cart/add}
            {:epoch-id 3 :event-id :nav/route}]
           kept))
    (is (= 1 dropped))))

;; ---------------------------------------------------------------------------
;; parse-include-sensitive — cross-MCP fixed arg name.
;; ---------------------------------------------------------------------------

(deftest parse-include-sensitive-default-false-when-absent
  ;; spec/009 MUST: the default is suppress. Every shape of "no input"
  ;; collapses to the same default-false posture.
  (is (false? (privacy/parse-include-sensitive nil)))
  (is (false? (privacy/parse-include-sensitive js/undefined)))
  (is (false? (privacy/parse-include-sensitive {})))
  (is (false? (privacy/parse-include-sensitive #js {})))
  (is (false? (privacy/parse-include-sensitive #js {:other "value"}))))

(deftest parse-include-sensitive-true-from-js-args-object
  ;; The MCP SDK hands the dispatcher a JS args object; the helper
  ;; reads the cross-server slot `include-sensitive?` from it.
  (is (true? (privacy/parse-include-sensitive #js {"include-sensitive?" true})))
  (is (true? (privacy/parse-include-sensitive #js {"include-sensitive?" "true"})))
  (is (true? (privacy/parse-include-sensitive #js {"include-sensitive?" "yes"})))
  (is (true? (privacy/parse-include-sensitive #js {"include-sensitive?" "1"}))))

(deftest parse-include-sensitive-false-from-js-args-object
  (is (false? (privacy/parse-include-sensitive #js {"include-sensitive?" false})))
  (is (false? (privacy/parse-include-sensitive #js {"include-sensitive?" "false"})))
  (is (false? (privacy/parse-include-sensitive #js {"include-sensitive?" "no"})))
  (is (false? (privacy/parse-include-sensitive #js {"include-sensitive?" "0"}))))

(deftest parse-include-sensitive-from-cljs-map
  ;; Some downstream paths may hand the helper a CLJS map (already
  ;; coerced from the MCP wire). Both keyword and stringified keys
  ;; resolve so the helper is robust across call shapes.
  (is (true?  (privacy/parse-include-sensitive {:include-sensitive? true})))
  (is (true?  (privacy/parse-include-sensitive {"include-sensitive?" true})))
  (is (false? (privacy/parse-include-sensitive {:include-sensitive? false}))))

(deftest parse-include-sensitive-unrecognised-value-defaults-suppress
  ;; Unrecognised raw values (e.g. a number, a random keyword, an
  ;; arbitrary object) collapse to the default-suppress posture per
  ;; the cross-MCP parse-boolean contract.
  (is (false? (privacy/parse-include-sensitive #js {"include-sensitive?" "maybe"})))
  (is (false? (privacy/parse-include-sensitive #js {"include-sensitive?" 42})))
  (is (false? (privacy/parse-include-sensitive {:include-sensitive? :perhaps}))))

;; ---------------------------------------------------------------------------
;; stamp-dropped-sensitive — counter shape on the envelope.
;; ---------------------------------------------------------------------------

(deftest stamp-dropped-sensitive-omits-slot-when-zero
  ;; Zero-drop common path carries no counter. A missing slot reads
  ;; as zero per the cross-MCP indicator-field convention; this keeps
  ;; the agent surface minimal on the hot path.
  (is (= {:events []}
         (privacy/stamp-dropped-sensitive {:events []} 0)))
  (is (= {:events [1 2 3]}
         (privacy/stamp-dropped-sensitive {:events [1 2 3]} 0)))
  (is (not (contains? (privacy/stamp-dropped-sensitive {:events []} 0)
                      :dropped-sensitive))))

(deftest stamp-dropped-sensitive-adds-slot-when-positive
  (is (= {:events [] :dropped-sensitive 2}
         (privacy/stamp-dropped-sensitive {:events []} 2)))
  (is (= {:events [{:id 1}] :dropped-sensitive 7}
         (privacy/stamp-dropped-sensitive {:events [{:id 1}]} 7))))

(deftest stamp-dropped-sensitive-non-positive-no-stamp
  ;; Defensive: negative or non-number inputs (shouldn't happen on the
  ;; happy path; defensive against future refactors) leave the envelope
  ;; untouched rather than stamping a nonsense value.
  (is (not (contains? (privacy/stamp-dropped-sensitive {:events []} -1)
                      :dropped-sensitive)))
  (is (not (contains? (privacy/stamp-dropped-sensitive {:events []} nil)
                      :dropped-sensitive))))

;; ---------------------------------------------------------------------------
;; apply-to-result — the per-tool boundary wrapper (the MUST 2 site).
;; ---------------------------------------------------------------------------

(deftest apply-to-result-default-strips-and-stamps-counter
  ;; MUST 1 + MUST 2: default-suppress at the MCP boundary, and the
  ;; counter surfaces in the envelope when at least one item drops.
  (let [evts [{:id 1}
              {:id 2 :sensitive? true}
              {:id 3 :sensitive? true}
              {:id 4}]
        out (privacy/apply-to-result {} :events evts false)]
    (is (= [{:id 1} {:id 4}] (:events out))
        "sensitive events MUST be dropped at the MCP boundary by default")
    (is (= 2 (:dropped-sensitive out))
        ":dropped-sensitive counter stamped when non-zero")))

(deftest apply-to-result-opt-in-passes-everything-no-stamp
  ;; MUST 19 (`:include-sensitive?` half): the documented opt-in
  ;; disables the gate. Counter is absent because nothing dropped.
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? true}]
        out (privacy/apply-to-result {} :events evts true)]
    (is (= evts (:events out)) "opt-in MUST pass every item through")
    (is (not (contains? out :dropped-sensitive))
        "opt-in path adds no counter — nothing was dropped")))

(deftest apply-to-result-clean-batch-no-counter
  ;; Counter is absent when zero items dropped — the cross-MCP
  ;; indicator-field convention (zero-drop common path is minimal).
  (let [evts [{:id 1} {:id 2 :sensitive? false} {:id 3}]
        out (privacy/apply-to-result {} :events evts false)]
    (is (= evts (:events out)))
    (is (not (contains? out :dropped-sensitive))
        "clean batch MUST NOT stamp the counter slot")))

(deftest apply-to-result-preserves-existing-envelope-keys
  ;; The wrapper is additive — pre-existing slots on the envelope
  ;; (cursor, next-cursor, remaining, mode, etc.) pass through.
  (let [evts [{:id 1 :sensitive? true} {:id 2}]
        out  (privacy/apply-to-result
               {:next-cursor "abc" :remaining 42 :mode :summary}
               :events evts false)]
    (is (= [{:id 2}] (:events out)))
    (is (= 1 (:dropped-sensitive out)))
    (is (= "abc"    (:next-cursor out)))
    (is (= 42       (:remaining out)))
    (is (= :summary (:mode out)))))

(deftest apply-to-result-empty-input
  (let [out (privacy/apply-to-result {} :events [] false)]
    (is (= [] (:events out)))
    (is (not (contains? out :dropped-sensitive)))))

(deftest apply-to-result-shape-for-trace-stream-tools
  ;; Pins the canonical shape every trace-stream-shaped tool uses:
  ;; `:trace-events` for `get-trace-buffer`, `:events` for
  ;; `subscribe` drain batches, `:epochs` for `get-epoch-history`.
  ;; The same wrapper services all three — uniform boundary site.
  (testing "get-trace-buffer-shape (:trace-events)"
    (let [out (privacy/apply-to-result
                {} :trace-events
                [{:op :a :sensitive? true} {:op :b}] false)]
      (is (= [{:op :b}] (:trace-events out)))
      (is (= 1 (:dropped-sensitive out)))))
  (testing "subscribe-shape (:events)"
    (let [out (privacy/apply-to-result
                {:tick 17} :events
                [{:op :a} {:op :b :sensitive? true}] false)]
      (is (= [{:op :a}] (:events out)))
      (is (= 1 (:dropped-sensitive out)))
      (is (= 17 (:tick out)))))
  (testing "get-epoch-history-shape (:epochs)"
    (let [out (privacy/apply-to-result
                {} :epochs
                [{:epoch-id 1}
                 {:epoch-id 2 :sensitive? true}
                 {:epoch-id 3}]
                false)]
      (is (= [{:epoch-id 1} {:epoch-id 3}] (:epochs out)))
      (is (= 1 (:dropped-sensitive out))))))

;; ---------------------------------------------------------------------------
;; The load-bearing spec/009 assertion.
;; ---------------------------------------------------------------------------

(deftest spec-009-default-posture-is-suppress-at-mcp-boundary
  (testing "default (no include-sensitive? arg) suppresses sensitive
            events at the MCP boundary before crossing into the agent
            surface (MUST 1 + MUST 2)"
    (let [sensitive-batch [{:operation :event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          incl?           (privacy/parse-include-sensitive nil) ; absent arg
          out             (privacy/apply-to-result {} :events sensitive-batch incl?)]
      (is (false? incl?)
          "parse-include-sensitive defaults to false (suppress)")
      (is (= [] (:events out))
          "sensitive event MUST NOT reach the agent surface by default")
      (is (= 1 (:dropped-sensitive out))
          "counter MUST surface the drop")))
  (testing "explicit `:include-sensitive? true` is the documented
            cross-MCP opt-in (MUST 19)"
    (let [sensitive-batch [{:operation :event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          incl?           (privacy/parse-include-sensitive
                            #js {"include-sensitive?" true})
          out             (privacy/apply-to-result {} :events sensitive-batch incl?)]
      (is (true? incl?))
      (is (= sensitive-batch (:events out))
          "opt-in path MUST pass sensitive events through unchanged")
      (is (not (contains? out :dropped-sensitive))
          "opt-in path stamps no counter"))))
