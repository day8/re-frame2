(ns re-frame2-pair-mcp.sensitive-filter-test
  "Unit tests for the spec/009 §Privacy default-suppress filter on
  `:sensitive? true` events.

  Spec 009 mandates that framework-published forwarders (Sentry /
  Honeybadger, re-frame2-pair server, Xray-MCP) MUST default-drop trace events
  whose registration declared `:sensitive? true`. The runtime stamps
  the flag at the top level of every emitted trace event; the
  forwarder's job is to gate egress on it.

  These tests pin `sensitive-event?` / `sensitive-epoch?` /
  `strip-sensitive` / `scrub-snapshot-sensitive` directly from
  `re-frame2-pair-mcp.tools.sensitive` — a rename or signature change
  surfaces as a failing test rather than a silent contract drift.

  The `wire-pipeline-epoch-vector-*` deftests at the foot drive the
  `:epoch-vector` arm of `run-wire-pipeline` — the SAME call site
  `trace-window` and `watch-epochs` route projected epoch vectors
  through — so the `sensitive-epoch?` predicate cannot be bypassed by
  the tool response path."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.tools.sensitive :as sensitive]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

;; ---------------------------------------------------------------------------
;; sensitive-event? — the boolean predicate.
;; ---------------------------------------------------------------------------

(deftest sensitive-event-true-stamp-detected
  (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? true})))

(deftest sensitive-event-false-stamp-passes
  (is (not (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? false}))))

(deftest sensitive-event-absent-stamp-passes
  ;; Per spec/009: "Consumers treat absent as `false`."
  (is (not (sensitive/sensitive-event? {:operation :rf.event/dispatched}))))

(deftest sensitive-event-non-true-truthy-drops-fail-closed
  ;; Fail-closed: the literal `true` drops AND any non-boolean truthy
  ;; value drops too. The `:rf/trace-event` schema types `:sensitive?`
  ;; as a boolean; a string `"true"` or keyword `:yes` is a contract
  ;; violation that means an upstream serialisation bug has coerced the
  ;; boolean into the wrong shape. A fail-OPEN posture would silently
  ;; leak sensitive events on such drift. re-frame2-pair-mcp delegates
  ;; to `re-frame.mcp-base.sensitive/sensitive-event?` so the contract
  ;; is byte-identical across the MCP triplet.
  (with-redefs [js/console (clj->js {:warn (fn [& _])})] ; absorb the contract-drift warning
    (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? "true"}))
    (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? :yes}))
    (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? 1}))
    (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? ["any" "truthy"]}))))

(deftest sensitive-event-non-map-input-passes
  (is (not (sensitive/sensitive-event? nil)))
  (is (not (sensitive/sensitive-event? [:sensitive? true])))
  (is (not (sensitive/sensitive-event? "anything"))))

;; ---------------------------------------------------------------------------
;; strip-sensitive — the default-suppress filter applied per batch.
;; ---------------------------------------------------------------------------

(deftest strip-sensitive-default-drops-true-stamps
  (let [evts [{:id 1 :sensitive? false}
              {:id 2 :sensitive? true}
              {:id 3}
              {:id 4 :sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
    (is (= 2 dropped))))

(deftest strip-sensitive-include-opt-in-passes-everything
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? false}
              {:id 3 :sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive evts true)]
    (is (= evts kept))
    (is (zero? dropped))))

(deftest strip-sensitive-empty-batch-zero-overhead
  (let [[kept dropped] (sensitive/strip-sensitive [] false)]
    (is (= [] kept))
    (is (zero? dropped))))

(deftest strip-sensitive-no-sensitive-events-zero-drop
  (let [evts [{:id 1} {:id 2 :sensitive? false} {:id 3}]
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= evts kept))
    (is (zero? dropped))))

(deftest strip-sensitive-all-sensitive-drops-all
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? true}
              {:id 3 :sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= [] kept))
    (is (= 3 dropped))))

(deftest strip-sensitive-fail-closed-drops-malformed-truthy
  ;; A transport bug that coerces `:sensitive? true` into
  ;; `:sensitive? "true"` (string) or `:sensitive? :yes` (keyword) MUST
  ;; NOT silently leak the event past the re-frame2-pair-mcp wire boundary. The
  ;; fail-closed posture (inherited from `re-frame.mcp-base.sensitive`)
  ;; drops the malformed-truthy event so the contract drift is visible
  ;; to operators on stderr / js/console.warn.
  (with-redefs [js/console (clj->js {:warn (fn [& _])})] ; absorb the warning
    (let [evts [{:id 1 :sensitive? false}
                {:id 2 :sensitive? "true"} ; malformed-truthy → drop
                {:id 3}
                {:id 4 :sensitive? :yes}]  ; malformed-truthy → drop
          [kept dropped] (sensitive/strip-sensitive evts false)]
      (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
      (is (= 2 dropped)))))

;; ---------------------------------------------------------------------------
;; Default posture — the load-bearing assertion for the spec/009 MUST.
;; ---------------------------------------------------------------------------

(deftest spec-009-default-posture-is-suppress
  (testing "the default (include-sensitive omitted ⇒ false) suppresses"
    (let [sensitive-batch [{:operation :rf.event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive sensitive-batch false)]
      (is (= [] kept) "sensitive event must NOT reach the agent surface by default")
      (is (= 1 dropped))))
  (testing "include-sensitive true is the documented opt-in"
    (let [sensitive-batch [{:operation :rf.event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive sensitive-batch true)]
      (is (= sensitive-batch kept))
      (is (zero? dropped)))))

;; ---------------------------------------------------------------------------
;; Snapshot scrubber — sensitive trace events stripped from per-frame
;; :traces / :epochs slices; other slices pass through unchanged.
;; ---------------------------------------------------------------------------

(deftest snapshot-scrubber-strips-sensitive-from-traces
  ;; The epoch slice uses the record-level rollup `:rf.epoch/sensitive?`
  ;; — the key the runtime epoch assembler writes — not the
  ;; trace-event-level unqualified `:sensitive?`.
  (let [snap {:rf/default
              {:app-db  {:user/name "ada" :password "secret"}
               :traces  [{:id 1 :sensitive? false}
                         {:id 2 :sensitive? true}
                         {:id 3}]
               :epochs  [{:event-id :foo} {:event-id :auth/sign-in :rf.epoch/sensitive? true}]
               :machines {}}
              :stories
              {:app-db {} :traces [{:id 10 :sensitive? true}]}}
        [out dropped] (sensitive/scrub-snapshot-sensitive snap false)]
    (is (= 3 dropped))
    (is (= [{:id 1 :sensitive? false} {:id 3}]
           (get-in out [:rf/default :traces])))
    (is (= [{:event-id :foo}]
           (get-in out [:rf/default :epochs])))
    (is (= [] (get-in out [:stories :traces])))))

(deftest snapshot-scrubber-leaves-non-trace-slices-alone
  ;; The CLIENT-SIDE scrubber's sole job is dropping whole sensitive
  ;; trace / epoch ITEMS; it must NOT touch :app-db / :sub-cache /
  ;; :machines. Those slices are projected UPSTREAM, server-side, before
  ;; they reach this scrubber: :app-db / :sub-cache through
  ;; `re-frame.core/elide-wire-value`, and the :machines runtime-db slice
  ;; fail-closed to `:rf/redacted` by default (EP-0015 / EP-0001 — Spec 011
  ;; §Off-box redaction). Here the slices arrive already-projected, so the
  ;; scrubber passes them through verbatim even when they carry literal
  ;; "sensitive"-looking shapes.
  (let [snap {:rf/default
              {:app-db    {:password "still-here" :sensitive? true}
               :sub-cache {:user/profile {:sensitive? true :data "x"}}
               :machines  {:auth {:state :idle}}
               :traces    [{:id 1}]}}
        [out _] (sensitive/scrub-snapshot-sensitive snap false)]
    (is (= {:password "still-here" :sensitive? true}
           (get-in out [:rf/default :app-db])))
    (is (= {:user/profile {:sensitive? true :data "x"}}
           (get-in out [:rf/default :sub-cache])))
    (is (= {:auth {:state :idle}}
           (get-in out [:rf/default :machines])))))

(deftest snapshot-scrubber-include-opt-in-passes-everything
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? true}
                                    {:id 2 :sensitive? true}]}}
        [out dropped] (sensitive/scrub-snapshot-sensitive snap true)]
    (is (= snap out))
    (is (zero? dropped))))

(deftest snapshot-scrubber-non-map-input-passes-through
  (let [[out dropped] (sensitive/scrub-snapshot-sensitive nil false)]
    (is (nil? out))
    (is (zero? dropped))))

;; ---------------------------------------------------------------------------
;; sensitive-epoch? — defense-in-depth on the epoch-record shape.
;;
;; Spec 009 §Privacy / Security.md §Epoch privacy mandate that the runtime's
;; epoch assembler computes a record-level `:rf.epoch/sensitive?` rollup at
;; record-assembly time (the "epoch is sensitive iff any constituent trace
;; event is sensitive OR a schema-declared sensitive app-db path resolves"
;; rule). `projected-record` preserves that QUALIFIED key verbatim through
;; off-box projection. This forwarder-side guard reads the
;; `:rf.epoch/sensitive?` key — the runtime never writes the UNqualified
;; `:sensitive?` on a record, so reading the unqualified key would leak
;; schema-derived sensitive epochs whose constituent traces are clean. It
;; is also BELT-AND-BRACES: if the rollup is absent (older runtime,
;; missing late-bind hook, hand-built record), it still detects sensitivity by
;; walking the record's `:trace-events` slot at egress.
;; ---------------------------------------------------------------------------

(deftest sensitive-epoch-rollup-true-detected
  ;; The runtime rollup key: `:rf.epoch/sensitive? true`, NO unqualified
  ;; `:sensitive?`, NO sensitive constituent trace event — the
  ;; schema-derived-sensitive shape.
  (is (sensitive/sensitive-epoch? {:epoch-id 1 :event-id :auth/sign-in :rf.epoch/sensitive? true})))

(deftest sensitive-epoch-rollup-false-passes
  ;; `:rf.epoch/sensitive? false` with no sensitive constituent ⇒ not sensitive.
  (is (not (sensitive/sensitive-epoch? {:epoch-id 1 :event-id :cart/add :rf.epoch/sensitive? false}))))

(deftest sensitive-epoch-unqualified-key-is-not-the-rollup
  ;; Guard against regressing to the unqualified key: the runtime never
  ;; writes a top-level `:sensitive?` on an epoch RECORD (only on trace
  ;; events). An epoch carrying ONLY an unqualified `:sensitive? true`
  ;; (no qualified rollup, no sensitive constituent trace event) is a
  ;; shape the runtime never emits; we don't treat the stray
  ;; unqualified key as the rollup signal — the qualified key is
  ;; authoritative. (The `:trace-events` walk still governs real cascades.)
  (is (not (sensitive/sensitive-epoch? {:epoch-id 1 :event-id :auth/sign-in :sensitive? true}))))

(deftest sensitive-epoch-rollup-malformed-truthy-fails-closed
  ;; A transport bug that coerces `:rf.epoch/sensitive? true` into a
  ;; string/keyword MUST NOT leak the record. The rollup is classified
  ;; through the shared fail-closed
  ;; `mcp-base.sensitive/sensitive-stamp?`, so malformed-truthy drops.
  (with-redefs [js/console (clj->js {:warn (fn [& _])})] ; absorb the warning
    (is (sensitive/sensitive-epoch? {:epoch-id 1 :rf.epoch/sensitive? "true"}))
    (is (sensitive/sensitive-epoch? {:epoch-id 2 :rf.epoch/sensitive? :yes}))
    (is (sensitive/sensitive-epoch? {:epoch-id 3 :rf.epoch/sensitive? 1}))))

(deftest sensitive-epoch-constituent-trace-event-detected
  ;; The top-level rollup is absent (older runtime), but a constituent
  ;; trace event carries the stamp — the egress guard must still drop.
  (is (sensitive/sensitive-epoch?
        {:epoch-id 2
         :event-id :auth/sign-in
         :trace-events [{:op-type :rf.event :operation :rf.event/run-start
                         :tags {:rf.trace/phase :run-start}}
                        {:op-type :rf.event :operation :rf.event/run-end
                         :tags {:rf.trace/phase :run-end}
                         :sensitive? true}]})))

(deftest sensitive-epoch-no-stamps-passes
  (is (not (sensitive/sensitive-epoch?
             {:epoch-id 3
              :event-id :cart/add
              :trace-events [{:op-type :rf.event :operation :rf.event/run-start
                              :tags {:rf.trace/phase :run-start}}
                             {:op-type :rf.event :operation :rf.event/run-end
                              :tags {:rf.trace/phase :run-end}}]}))))

(deftest sensitive-epoch-empty-trace-events-passes
  (is (not (sensitive/sensitive-epoch? {:epoch-id 4 :trace-events []})))
  (is (not (sensitive/sensitive-epoch? {:epoch-id 5}))))

(deftest sensitive-epoch-non-map-input-passes
  (is (not (sensitive/sensitive-epoch? nil)))
  (is (not (sensitive/sensitive-epoch? [:trace-events [{:sensitive? true}]])))
  (is (not (sensitive/sensitive-epoch? "anything"))))

(deftest sensitive-epoch-explicit-false-rollup-still-walks-trace-events
  ;; A `:rf.epoch/sensitive? false` rollup is the assembler's claim that
  ;; no constituent is sensitive. If a constituent disagrees we trust
  ;; the constituent — defense-in-depth means we drop on EITHER signal,
  ;; never silently overrule a sensitive constituent.
  (is (sensitive/sensitive-epoch?
        {:epoch-id 6
         :rf.epoch/sensitive? false
         :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end} :sensitive? true}]})))

;; ---------------------------------------------------------------------------
;; strip-sensitive on epoch records — the streaming-surface defense-in-depth
;; scenarios. These match how trace-window-tool / watch-epochs-tool feed
;; epoch vectors through the same helper.
;; ---------------------------------------------------------------------------

(deftest strip-sensitive-drops-epoch-with-sensitive-constituent
  ;; Sensitive epoch (carried by trace-events) drops; non-sensitive
  ;; epoch passes through. Mirrors a `trace-window` payload where the
  ;; runtime rollup was absent but a constituent trace was stamped.
  (let [epochs [{:epoch-id 1
                 :event-id :cart/add
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}
                {:epoch-id 2
                 :event-id :auth/sign-in
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end} :sensitive? true}]}]
        [kept dropped] (sensitive/strip-sensitive epochs false)]
    (is (= 1 (count kept)))
    (is (= 1 (:epoch-id (first kept))))
    (is (= 1 dropped))))

(deftest strip-sensitive-passes-non-sensitive-epoch-vector-through
  (let [epochs [{:epoch-id 1 :event-id :cart/add :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}
                {:epoch-id 2 :event-id :cart/checkout :trace-events []}
                {:epoch-id 3 :event-id :nav/route}]
        [kept dropped] (sensitive/strip-sensitive epochs false)]
    (is (= epochs kept))
    (is (zero? dropped))))

(deftest strip-sensitive-mixed-batch-drops-sensitive-keeps-rest
  ;; Three sensitivity signals in one batch:
  ;;   - epoch 1: rollup absent, constituent stamped sensitive  → drop
  ;;   - epoch 2: `:rf.epoch/sensitive?` rollup stamped sensitive → drop
  ;;   - epoch 3: clean                                          → keep
  ;;   - epoch 4: clean (no trace-events slot at all)            → keep
  (let [epochs [{:epoch-id 1
                 :event-id :auth/sign-in
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end} :sensitive? true}]}
                {:epoch-id 2
                 :event-id :auth/recover
                 :rf.epoch/sensitive? true
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}
                {:epoch-id 3
                 :event-id :cart/add
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}
                {:epoch-id 4 :event-id :nav/route}]
        [kept dropped] (sensitive/strip-sensitive epochs false)]
    (is (= [3 4] (mapv :epoch-id kept)))
    (is (= 2 dropped))))

(deftest strip-sensitive-drops-schema-derived-sensitive-epoch-rollup-only
  ;; Regression guard: a schema-derived sensitive epoch:
  ;; `:rf.epoch/sensitive? true` (the runtime rollup), NO unqualified
  ;; `:sensitive?`, and NO sensitive constituent trace event. A predicate
  ;; that read `:sensitive?` (which is absent here) would let this survive
  ;; `strip-sensitive` and ship its metadata across the MCP boundary.
  ;; Instead it default-DROPs and increments `:dropped-sensitive` under
  ;; the default `include-sensitive false` gate.
  (let [epochs [{:epoch-id 1
                 :event-id :auth/sign-in
                 :rf.epoch/sensitive? true
                 :rf.epoch/redacted-modified-paths-count 2
                 :outcome :rf.epoch/committed
                 ;; constituents are CLEAN — sensitivity came from a
                 ;; schema-declared sensitive app-db path, not a stamp
                 :trace-events [{:operation :rf.event/run-start :tags {:rf.trace/phase :run-start}}
                                {:operation :rf.event/run-end   :tags {:rf.trace/phase :run-end}}]}
                {:epoch-id 2
                 :event-id :cart/add
                 :rf.epoch/sensitive? false
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}]
        [kept dropped] (sensitive/strip-sensitive epochs false)]
    (is (= [2] (mapv :epoch-id kept))
        "schema-derived sensitive epoch (rollup-only) MUST be dropped by default")
    (is (= 1 dropped)
        ":dropped-sensitive must count the rollup-only sensitive record")))

(deftest strip-sensitive-include-opt-in-keeps-epoch-with-sensitive-constituent
  ;; `:include-sensitive true` is the documented escape hatch — even
  ;; epochs carrying sensitive constituents OR a `:rf.epoch/sensitive?`
  ;; rollup pass through unchanged.
  (let [epochs [{:epoch-id 1 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end} :sensitive? true}]}
                {:epoch-id 2 :rf.epoch/sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive epochs true)]
    (is (= epochs kept))
    (is (zero? dropped))))

;; ---------------------------------------------------------------------------
;; Integration through the :epoch-vector wire pipeline.
;;
;; `run-wire-pipeline` with `:kind :epoch-vector` is the EXACT call site
;; `trace-window` (trace_window.cljs:138) and `watch-epochs`
;; (watch_epochs.cljs) route projected epoch vectors through before the
;; payload crosses the MCP boundary. Its first step is `strip-sensitive`,
;; so the `sensitive-epoch?` predicate governs the tool response path —
;; these tests assert the leak cannot be bypassed by the pipeline.
;; The pipeline reports the drop count on `:indicators :dropped` (the
;; `:dropped-sensitive` indicator the tools surface on the envelope).
;; ---------------------------------------------------------------------------

(deftest wire-pipeline-epoch-vector-drops-schema-derived-sensitive-record
  ;; The leak, through the real egress pipeline. A projected epoch with
  ;; `:rf.epoch/sensitive? true`, NO unqualified `:sensitive?`, and clean
  ;; constituent trace events must be DROPPED under the default gate and
  ;; counted in `:dropped`.
  (let [epochs [{:epoch-id 1
                 :event-id :auth/sign-in
                 :rf.epoch/sensitive? true
                 :rf.epoch/redacted-modified-paths-count 1
                 :outcome :rf.epoch/committed
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}
                {:epoch-id 2
                 :event-id :cart/add
                 :rf.epoch/sensitive? false
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}]
        {:keys [value indicators]}
        (wp/run-wire-pipeline epochs {:kind :epoch-vector :incl? false :mode :diff :dedup? false})]
    (is (= [2] (mapv :epoch-id value))
        "the schema-derived sensitive record MUST NOT reach the agent surface by default")
    (is (= 1 (:dropped indicators))
        "the pipeline reports the dropped-sensitive count for the record")
    ;; The dropped record's metadata (event-id, timing, outcome, …) is gone.
    (is (not-any? #(= :auth/sign-in (:event-id %)) value)
        "no metadata of the dropped sensitive epoch survives on the wire")))

(deftest wire-pipeline-epoch-vector-include-sensitive-passes-rollup-record
  ;; The documented opt-in still passes the rollup-marked record through.
  (let [epochs [{:epoch-id 1 :event-id :auth/sign-in :rf.epoch/sensitive? true
                 :trace-events [{:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}]}]
        {:keys [value indicators]}
        (wp/run-wire-pipeline epochs {:kind :epoch-vector :incl? true :mode :diff :dedup? false})]
    (is (= [1] (mapv :epoch-id value))
        ":include-sensitive true is the documented escape hatch")
    (is (zero? (:dropped indicators)))))
