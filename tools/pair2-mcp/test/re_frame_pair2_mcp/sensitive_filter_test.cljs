(ns re-frame-pair2-mcp.sensitive-filter-test
  "Unit tests for the spec/009 §Privacy default-suppress filter on
  `:sensitive? true` events (rf2-zq0n1, follows rf2-a32kd).

  Spec 009 mandates that framework-published forwarders (Sentry /
  Honeybadger, pair2 server, Causa-MCP) MUST default-drop trace events
  whose registration declared `:sensitive? true`. The runtime stamps
  the flag at the top level of every emitted trace event; the
  forwarder's job is to gate egress on it.

  These tests pin `sensitive-event?` / `sensitive-epoch?` /
  `strip-sensitive` / `scrub-snapshot-sensitive` directly from
  `re-frame-pair2-mcp.tools.sensitive` — a rename or signature change
  surfaces as a failing test rather than a silent contract drift."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame-pair2-mcp.tools.sensitive :as sensitive]))

;; ---------------------------------------------------------------------------
;; sensitive-event? — the boolean predicate.
;; ---------------------------------------------------------------------------

(deftest sensitive-event-true-stamp-detected
  (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? true})))

(deftest sensitive-event-false-stamp-passes
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? false}))))

(deftest sensitive-event-absent-stamp-passes
  ;; Per spec/009: "Consumers treat absent as `false`."
  (is (not (sensitive/sensitive-event? {:operation :event/dispatched}))))

(deftest sensitive-event-non-true-truthy-drops-fail-closed
  ;; Fail-closed (rf2-ih7g4): the literal `true` drops AND any
  ;; non-boolean truthy value drops too. The `:rf/trace-event` schema
  ;; types `:sensitive?` as a boolean; a string `"true"` or keyword
  ;; `:yes` is a contract violation that means an upstream
  ;; serialisation bug has coerced the boolean into the wrong shape.
  ;; The previous fail-OPEN posture silently leaked sensitive events
  ;; on such drift. Pair2-mcp delegates to
  ;; `re-frame.mcp-base.sensitive/sensitive-event?` (rf2-vw4sq) so the
  ;; contract is byte-identical across the MCP triplet.
  (with-redefs [js/console (clj->js {:warn (fn [& _])})] ; absorb the contract-drift warning
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? "true"}))
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? :yes}))
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? 1}))
    (is (sensitive/sensitive-event? {:operation :event/dispatched :sensitive? ["any" "truthy"]}))))

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
  ;; rf2-ih7g4: a transport bug that coerces `:sensitive? true` into
  ;; `:sensitive? "true"` (string) or `:sensitive? :yes` (keyword) MUST
  ;; NOT silently leak the event past the pair2-mcp wire boundary. The
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
    (let [sensitive-batch [{:operation :event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive sensitive-batch false)]
      (is (= [] kept) "sensitive event must NOT reach the agent surface by default")
      (is (= 1 dropped))))
  (testing "include-sensitive true is the documented opt-in"
    (let [sensitive-batch [{:operation :event/dispatched
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
  (let [snap {:rf/default
              {:app-db  {:user/name "ada" :password "secret"}
               :traces  [{:id 1 :sensitive? false}
                         {:id 2 :sensitive? true}
                         {:id 3}]
               :epochs  [{:event-id :foo} {:event-id :auth/sign-in :sensitive? true}]
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
  ;; App-db payload redaction is `with-redacted`'s job, not the
  ;; forwarder's. The scrubber must NOT touch :app-db / :sub-cache /
  ;; :machines even when they carry literal "sensitive"-looking shapes.
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
;; sensitive-epoch? — defense-in-depth on the epoch-record shape (rf2-re2s3).
;;
;; Spec 009 §Privacy mandates that the runtime's epoch assembler computes a
;; top-level `:sensitive?` rollup at record-assembly time (rf2-isdwf, the
;; "epoch is sensitive iff any constituent trace event is sensitive" rule).
;; This forwarder-side guard is BELT-AND-BRACES on top of that — if the
;; rollup is absent (older runtime, missing late-bind hook, hand-built
;; record), we still detect sensitivity by walking the record's
;; `:trace-events` slot at egress.
;; ---------------------------------------------------------------------------

(deftest sensitive-epoch-top-level-stamp-detected
  (is (sensitive/sensitive-epoch? {:epoch-id 1 :event-id :auth/sign-in :sensitive? true})))

(deftest sensitive-epoch-constituent-trace-event-detected
  ;; The top-level rollup is absent (older runtime), but a constituent
  ;; trace event carries the stamp — the egress guard must still drop.
  (is (sensitive/sensitive-epoch?
        {:epoch-id 2
         :event-id :auth/sign-in
         :trace-events [{:op-type :event :operation :run-start}
                        {:op-type :event :operation :run-end
                         :sensitive? true}]})))

(deftest sensitive-epoch-no-stamps-passes
  (is (not (sensitive/sensitive-epoch?
             {:epoch-id 3
              :event-id :cart/add
              :trace-events [{:op-type :event :operation :run-start}
                             {:op-type :event :operation :run-end}]}))))

(deftest sensitive-epoch-empty-trace-events-passes
  (is (not (sensitive/sensitive-epoch? {:epoch-id 4 :trace-events []})))
  (is (not (sensitive/sensitive-epoch? {:epoch-id 5}))))

(deftest sensitive-epoch-non-map-input-passes
  (is (not (sensitive/sensitive-epoch? nil)))
  (is (not (sensitive/sensitive-epoch? [:trace-events [{:sensitive? true}]])))
  (is (not (sensitive/sensitive-epoch? "anything"))))

(deftest sensitive-epoch-explicit-false-rollup-still-walks-trace-events
  ;; A `:sensitive? false` rollup at the top is the assembler's claim
  ;; that no constituent is sensitive. If a constituent disagrees we
  ;; trust the constituent — defense-in-depth means we drop on EITHER
  ;; signal, never silently overrule a sensitive constituent.
  (is (sensitive/sensitive-epoch?
        {:epoch-id 6
         :sensitive? false
         :trace-events [{:operation :run-end :sensitive? true}]})))

;; ---------------------------------------------------------------------------
;; strip-sensitive on epoch records — the streaming-surface defense-in-depth
;; scenarios (rf2-re2s3). These match how trace-window-tool / watch-epochs-tool
;; feed epoch vectors through the same helper.
;; ---------------------------------------------------------------------------

(deftest strip-sensitive-drops-epoch-with-sensitive-constituent
  ;; Sensitive epoch (carried by trace-events) drops; non-sensitive
  ;; epoch passes through. Mirrors a `trace-window` payload where the
  ;; runtime rollup was absent but a constituent trace was stamped.
  (let [epochs [{:epoch-id 1
                 :event-id :cart/add
                 :trace-events [{:operation :run-end}]}
                {:epoch-id 2
                 :event-id :auth/sign-in
                 :trace-events [{:operation :run-end :sensitive? true}]}]
        [kept dropped] (sensitive/strip-sensitive epochs false)]
    (is (= 1 (count kept)))
    (is (= 1 (:epoch-id (first kept))))
    (is (= 1 dropped))))

(deftest strip-sensitive-passes-non-sensitive-epoch-vector-through
  (let [epochs [{:epoch-id 1 :event-id :cart/add :trace-events [{:operation :run-end}]}
                {:epoch-id 2 :event-id :cart/checkout :trace-events []}
                {:epoch-id 3 :event-id :nav/route}]
        [kept dropped] (sensitive/strip-sensitive epochs false)]
    (is (= epochs kept))
    (is (zero? dropped))))

(deftest strip-sensitive-mixed-batch-drops-sensitive-keeps-rest
  ;; Three sensitivity signals in one batch:
  ;;   - epoch 1: rollup absent, constituent stamped sensitive  → drop
  ;;   - epoch 2: rollup stamped sensitive                       → drop
  ;;   - epoch 3: clean                                          → keep
  ;;   - epoch 4: clean (no trace-events slot at all)            → keep
  (let [epochs [{:epoch-id 1
                 :event-id :auth/sign-in
                 :trace-events [{:operation :run-end :sensitive? true}]}
                {:epoch-id 2
                 :event-id :auth/recover
                 :sensitive? true
                 :trace-events [{:operation :run-end}]}
                {:epoch-id 3
                 :event-id :cart/add
                 :trace-events [{:operation :run-end}]}
                {:epoch-id 4 :event-id :nav/route}]
        [kept dropped] (sensitive/strip-sensitive epochs false)]
    (is (= [3 4] (mapv :epoch-id kept)))
    (is (= 2 dropped))))

(deftest strip-sensitive-include-opt-in-keeps-epoch-with-sensitive-constituent
  ;; `:include-sensitive true` is the documented escape hatch — even
  ;; epochs carrying sensitive constituents pass through unchanged.
  (let [epochs [{:epoch-id 1 :trace-events [{:operation :run-end :sensitive? true}]}
                {:epoch-id 2 :sensitive? true}]
        [kept dropped] (sensitive/strip-sensitive epochs true)]
    (is (= epochs kept))
    (is (zero? dropped))))
