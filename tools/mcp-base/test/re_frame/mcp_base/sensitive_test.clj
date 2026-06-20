(ns re-frame.mcp-base.sensitive-test
  "Tests for the spec/009 §Privacy default-suppress filter shared
  across the MCP pair. The predicate and the filter
  must stay byte-identical across re-frame2-pair-mcp and story-mcp
  — these tests pin the contract."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.sensitive :as sensitive]))

;; ---------------------------------------------------------------------------
;; sensitive-event? — the boolean predicate.
;; ---------------------------------------------------------------------------

(deftest sensitive-event?-true-stamp-detected
  (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? true})))

(deftest sensitive-event?-false-stamp-passes
  (is (not (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? false}))))

(deftest sensitive-event?-absent-stamp-passes
  ;; Per spec/009: "Consumers treat absent as `false`."
  (is (not (sensitive/sensitive-event? {:operation :rf.event/dispatched}))))

(deftest sensitive-event?-non-true-truthy-drops-fail-closed
  ;; Fail-closed: the literal `true` drops AND any
  ;; non-boolean truthy value drops too. The `:rf/trace-event` schema
  ;; types `:sensitive?` as a boolean; a string `"true"` or keyword
  ;; `:yes` is a contract violation that means an upstream
  ;; serialisation bug has coerced the boolean into the wrong shape.
  ;; A fail-OPEN posture would silently leak sensitive events on such
  ;; drift; fail-CLOSED drops AND logs.
  ;; The expected contract-drift WARN is quieted by the central quiet
  ;; runner's stderr buffer — no local `*err*` sink needed;
  ;; it stays buffered on green and replays on red.
  (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? "true"}))
  (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? :yes}))
  (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? 1}))
  (is (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? ["any" "truthy"]})))

(deftest sensitive-event?-non-map-input-passes
  (is (not (sensitive/sensitive-event? nil)))
  (is (not (sensitive/sensitive-event? [:sensitive? true])))
  (is (not (sensitive/sensitive-event? "anything"))))

(deftest sensitive-event?-explicit-false-passes
  ;; Fail-closed posture does NOT change the explicit-false
  ;; / nil path — those remain non-sensitive. Only truthy non-boolean
  ;; values get the fail-closed drop.
  (is (not (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? false})))
  (is (not (sensitive/sensitive-event? {:operation :rf.event/dispatched :sensitive? nil}))))

(deftest strip-sensitive-fail-closed-drops-malformed-truthy
  ;; A transport bug that coerces `:sensitive? true` into
  ;; `:sensitive? "true"` (string) or `:sensitive? :yes` (keyword) MUST
  ;; NOT silently leak the event. The fail-closed posture drops the
  ;; malformed-truthy event (with a stderr warning) so the contract
  ;; drift is visible to operators.
  ;; Expected WARN quieted by the central quiet-runner stderr buffer
  ;; — no local `*err*` sink needed.
  (let [evts [{:id 1 :sensitive? false}
              {:id 2 :sensitive? "true"} ; malformed-truthy → drop
              {:id 3}
              {:id 4 :sensitive? :yes}]  ; malformed-truthy → drop
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
    (is (= 2 dropped))))

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

;; ---------------------------------------------------------------------------
;; Default posture — the load-bearing assertion for the spec/009 MUST.
;; ---------------------------------------------------------------------------

(deftest spec-009-default-posture-is-suppress
  (testing "the default (include-sensitive? omitted ⇒ false) suppresses"
    (let [sensitive-batch [{:operation :rf.event/dispatched
                            :tags      {:rf.trace/event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive sensitive-batch false)]
      (is (= [] kept) "sensitive event must NOT reach the agent surface by default")
      (is (= 1 dropped))))
  (testing "include-sensitive? true is the documented opt-in"
    (let [sensitive-batch [{:operation :rf.event/dispatched
                            :tags      {:rf.trace/event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (sensitive/strip-sensitive sensitive-batch true)]
      (is (= sensitive-batch kept))
      (is (zero? dropped)))))

;; ---------------------------------------------------------------------------
;; scrub-snapshot — strip sensitive trace events from per-frame slices.
;; ---------------------------------------------------------------------------

(deftest scrub-snapshot-strips-sensitive-from-traces
  (let [snap {:rf/default
              {:app-db  {:user/name "ada" :password "secret"}
               :traces  [{:id 1 :sensitive? false}
                         {:id 2 :sensitive? true}
                         {:id 3}]
               :epochs  [{:event-id :foo} {:event-id :auth/sign-in :sensitive? true}]
               :machines {}}
              :stories
              {:app-db {} :traces [{:id 10 :sensitive? true}]}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 3 dropped))
    (is (= [{:id 1 :sensitive? false} {:id 3}]
           (get-in out [:rf/default :traces])))
    (is (= [{:event-id :foo}]
           (get-in out [:rf/default :epochs])))
    (is (= [] (get-in out [:stories :traces])))))

(deftest scrub-snapshot-leaves-non-trace-slices-alone
  ;; `scrub-snapshot` is a TRACE/EPOCH sensitivity filter only — NOT the
  ;; complete snapshot privacy boundary. It deliberately does not descend
  ;; into :app-db / :sub-cache / :machines, so those slices pass through
  ;; UNPROJECTED. The caller's egress pipeline (EP-0015 `project-egress`
  ;; under the frame's classification + an `:rf.egress/*` profile) is
  ;; responsible for projecting those non-trace slices BEFORE they cross
  ;; an MCP/tool boundary. This test pins the shallow-by-design contract:
  ;; the scrubber output is NOT already-projected full-snapshot output.
  (let [snap {:rf/default
              {:app-db    {:password "still-here" :sensitive? true}
               :sub-cache {:user/profile {:sensitive? true :data "x"}}
               :machines  {:auth {:state :idle}}
               :traces    [{:id 1}]}}
        [out _] (sensitive/scrub-snapshot snap false)]
    (is (= {:password "still-here" :sensitive? true}
           (get-in out [:rf/default :app-db]))
        "non-trace slices ride through raw — the CALLER's egress projector must project them")
    (is (= {:user/profile {:sensitive? true :data "x"}}
           (get-in out [:rf/default :sub-cache])))
    (is (= {:auth {:state :idle}}
           (get-in out [:rf/default :machines])))))

(deftest scrub-snapshot-is-not-a-full-snapshot-projector
  ;; A consumer must not mistake `scrub-snapshot` output for
  ;; already-projected full-snapshot output. The function does ONE thing —
  ;; filter sensitive trace events out of :traces / :epochs — and leaves
  ;; the value-carrying slices (:app-db / :sub-cache / :machines) exactly
  ;; as it found them. There is NO write-time redaction model standing in
  ;; for the egress projection EP-0015 requires; this test fails loudly if
  ;; a future change starts redacting non-trace payloads here (which would
  ;; wrongly imply the scrubber is the complete privacy boundary) OR stops
  ;; filtering traces (its actual job).
  (let [secret-db {:password "leak-me" :token {:value "abc"}}
        snap      {:rf/default
                   {:app-db   secret-db
                    :traces   [{:id 1 :sensitive? true} {:id 2}]}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    ;; The trace filter DID run (its job): the sensitive trace event is gone.
    (is (= 1 dropped) "sensitive trace event was dropped")
    (is (= [{:id 2}] (get-in out [:rf/default :traces])))
    ;; The value-carrying slice is byte-identical — UNPROJECTED. A caller
    ;; that ships this slice off-box without running `project-egress` over
    ;; it leaks `secret-db`; the scrubber explicitly does not own that.
    (is (identical? secret-db (get-in out [:rf/default :app-db]))
        "scrub-snapshot leaves :app-db unprojected — it is NOT a full-snapshot privacy boundary")))

(deftest scrub-snapshot-include-opt-in-passes-everything
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? true}
                                    {:id 2 :sensitive? true}]}}
        [out dropped] (sensitive/scrub-snapshot snap true)]
    (is (= snap out))
    (is (zero? dropped))))

(deftest scrub-snapshot-non-map-input-passes-through
  (let [[out dropped] (sensitive/scrub-snapshot nil false)]
    (is (nil? out))
    (is (zero? dropped))))

(deftest scrub-snapshot-non-map-frame-values-pass-through-untouched
  ;; `scrub-snapshot`'s per-frame reduce-kv only scrubs a frame value
  ;; when `(map? v)`; a non-map value rides the `:else v` arm untouched.
  ;; The runtime snapshot is a map of frame-id → frame-map, but the
  ;; helper is defensive against a slot whose value isn't a frame map
  ;; (a scalar marker, a nil sentinel). This pins the leave-alone
  ;; guarantee for every non-map shape, so a regression that mangled
  ;; non-map slots (e.g. `(scrub-frame v)` unconditionally) and
  ;; NPE'd/threw rather than passing through would be caught.
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? true} {:id 2}]}
              :meta-count  7              ;; scalar — must survive verbatim
              :flag        :rf.size/large-elided ;; keyword — survives
              :nilslot     nil            ;; nil — survives
              :listslot    [1 2 3]}       ;; vector (non-map) — survives
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 1 dropped) "the one map frame's sensitive trace is still dropped")
    (is (= [{:id 2}] (get-in out [:rf/default :traces]))
        "map frame scrubbed")
    (is (= 7 (:meta-count out))   "scalar frame value untouched")
    (is (= :rf.size/large-elided (:flag out)) "keyword frame value untouched")
    (is (contains? out :nilslot)  "nil frame value key preserved")
    (is (nil? (:nilslot out))     "nil frame value untouched")
    (is (= [1 2 3] (:listslot out)) "vector frame value untouched")))

(deftest scrub-snapshot-frame-without-trace-or-epoch-slices-is-no-op
  ;; `scrub-frame`'s `cond->` only updates `:traces` / `:epochs` when
  ;; present. A frame map carrying NEITHER slice (an `:app-db`-only
  ;; frame, or a frame whose only slices are the left-alone
  ;; `:sub-cache` / `:machines`) must return byte-equal — the `cond->`
  ;; falls through both clauses. This exercises that both-absent
  ;; fall-through.
  (let [snap {:rf/default {:app-db    {:user/name "ada"}
                           :sub-cache {:user/profile {:data "x"}}
                           :machines  {:auth {:state :idle}}}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (zero? dropped) "no trace / epoch slices ⇒ nothing dropped")
    (is (= snap out)
        "a frame with neither :traces nor :epochs is returned unchanged")))

(deftest scrub-snapshot-frame-with-only-epochs-slice-scrubbed
  ;; Companion to the existing :stories-only-:traces frame in
  ;; `scrub-snapshot-strips-sensitive-from-traces`: pin the symmetric
  ;; `cond->` arm where ONLY `:epochs` is present (no `:traces`). Both
  ;; single-slice arms now have positive coverage.
  (let [snap {:rf/default {:epochs [{:event-id :foo}
                                    {:event-id :auth/sign-in :sensitive? true}
                                    {:event-id :bar}]}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 1 dropped))
    (is (= [{:event-id :foo} {:event-id :bar}]
           (get-in out [:rf/default :epochs]))
        "only-:epochs frame has its epoch slice scrubbed")))

(deftest scrub-snapshot-handles-lazy-seq-slice-values
  ;; Regression pin: `:traces` and `:epochs`
  ;; arrive as vectors in the typical runtime emission, but the
  ;; instrumentation API doesn't guarantee that — a slice composed via
  ;; `concat` / `map` / `filter` produces a lazy seq. The `(vec items)`
  ;; wrap inside `scrub-slice` normalises before `strip-sensitive`
  ;; runs; this test pins the contract so a future refactor that
  ;; removes the wrap doesn't silently break the lazy-input case.
  (let [snap   {:rf/default
                {:traces (map identity [{:id 1 :sensitive? false}
                                        {:id 2 :sensitive? true}
                                        {:id 3}])
                 :epochs (filter (constantly true)
                                 [{:event-id :foo}
                                  {:event-id :auth/sign-in :sensitive? true}])}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 2 dropped))
    (is (= [{:id 1 :sensitive? false} {:id 3}]
           (get-in out [:rf/default :traces])))
    (is (= [{:event-id :foo}]
           (get-in out [:rf/default :epochs])))))

(deftest scrub-snapshot-strip-fn-arity-delegates-to-custom-predicate
  ;; The three-arity form admits a caller-supplied strip-fn
  ;; matching the `[items include?] => [kept dropped]` contract. Pin
  ;; the contract so a future refactor of the helper can't silently
  ;; drop the delegation.
  (let [strip-by-id-2 (fn [items _include?]
                        (let [kept (filterv #(not= 2 (:id %)) items)
                              n    (- (count items) (count kept))]
                          [kept n]))
        snap          {:rf/default {:traces [{:id 1} {:id 2} {:id 3} {:id 2}]}}
        [out dropped] (sensitive/scrub-snapshot snap false strip-by-id-2)]
    (is (= 2 dropped))
    (is (= [{:id 1} {:id 3}] (get-in out [:rf/default :traces])))))

;; ---------------------------------------------------------------------------
;; Slice-shape discipline + fail-closed for malformed slices.
;; `scrub-snapshot` only normalises + scrubs a genuinely `sequential?`
;; `:traces` / `:epochs` batch; non-sequential shapes pass through
;; byte-identical, and a single sensitive-event map is DROPPED
;; fail-closed. An unconditional `(vec …)` of any present value would
;; mangle non-sequential shapes (`nil → []`, `"oops" → [\o \o …]`, a
;; single event map → a vector of map-entries) AND, for a malformed
;; single SENSITIVE event map, report zero drops while carrying the
;; secret straight through (fail-OPEN) — which the `sequential?` guard
;; prevents.
;; ---------------------------------------------------------------------------

(deftest scrub-snapshot-nil-slice-passes-through-unchanged
  ;; A nil `:traces` / `:epochs` slice is not a batch; it
  ;; must survive as nil, not be coerced to `[]`.
  (let [snap {:rf/default {:traces nil :epochs nil}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (zero? dropped))
    (is (contains? (get out :rf/default) :traces))
    (is (nil? (get-in out [:rf/default :traces]))
        "nil :traces survives as nil (no `[]` mangle)")
    (is (nil? (get-in out [:rf/default :epochs]))
        "nil :epochs survives as nil")))

(deftest scrub-snapshot-scalar-slice-passes-through-unchanged
  ;; A scalar slice is not a batch.
  (let [snap {:rf/default {:traces 7 :epochs :marker}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (zero? dropped))
    (is (= 7 (get-in out [:rf/default :traces])))
    (is (= :marker (get-in out [:rf/default :epochs])))))

(deftest scrub-snapshot-string-slice-passes-through-unchanged
  ;; A string is `seqable?` but NOT a trace-event batch.
  ;; An unconditional `(vec "oops")` would produce `[\o \o \p \s]`.
  (let [snap {:rf/default {:traces "oops"}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (zero? dropped))
    (is (= "oops" (get-in out [:rf/default :traces]))
        "string slice survives verbatim (no char-vector mangle)")))

(deftest scrub-snapshot-non-sensitive-single-map-slice-passes-through
  ;; A single (non-sensitive) event MAP is not a batch; it
  ;; passes through byte-identical rather than becoming a vec of entries.
  (let [snap {:rf/default {:traces {:id 1 :op :something}}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (zero? dropped))
    (is (= {:id 1 :op :something} (get-in out [:rf/default :traces]))
        "non-sensitive single map survives verbatim (no entry-vec mangle)")))

(deftest scrub-snapshot-sensitive-single-map-slice-dropped-fail-closed
  ;; A single MAP slice that is itself a sensitive event must be
  ;; DROPPED, not shipped raw. An unconditional `(vec …)` would turn
  ;; `{:id 1 :sensitive? true}` into `[[:id 1] [:sensitive? true]]`,
  ;; report zero drops, and carry the secret across the boundary.
  ;; Fail-closed: drop + count.
  (let [snap {:rf/default {:traces {:id 1 :sensitive? true :payload "SECRET"}}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 1 dropped) "the malformed sensitive single-map is counted as a drop")
    (is (= [] (get-in out [:rf/default :traces]))
        "the sensitive single-map is dropped (empty batch), not shipped raw")
    ;; The secret never appears anywhere in the scrubbed output.
    (is (not (clojure.string/includes? (pr-str out) "SECRET"))
        "no trace of the secret survives in the scrubbed snapshot")))

(deftest scrub-snapshot-sensitive-single-map-epochs-dropped-fail-closed
  ;; The probe shape: nil `:traces` (passes through) + a malformed
  ;; sensitive single-map `:epochs` (dropped).
  (let [snap {:rf/default {:traces nil :epochs {:id 1 :sensitive? true}}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 1 dropped))
    (is (nil? (get-in out [:rf/default :traces])) ":traces nil survives")
    (is (= [] (get-in out [:rf/default :epochs]))
        "sensitive single-map :epochs dropped fail-closed")))

(deftest scrub-snapshot-sequential-batch-still-scrubbed
  ;; The legitimate path: a vector batch is normalised + scrubbed.
  ;; (Companion to the lazy-seq test which pins the seq case.)
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? true} {:id 2}]
                           :epochs [{:event-id :a :sensitive? true} {:event-id :b}]}}
        [out dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 2 dropped))
    (is (= [{:id 2}] (get-in out [:rf/default :traces])))
    (is (= [{:event-id :b}] (get-in out [:rf/default :epochs])))))

;; ---------------------------------------------------------------------------
;; Single-map fail-closed branch routes through the caller-supplied strip-fn.
;; The single-map branch classifies via the SAME `strip-fn` the sequential
;; branch uses, NOT the base `sensitive-event?`. Classifying with the base
;; predicate instead would let a single-map slice sensitive ONLY by a UNION
;; signal the caller's predicate catches (re-frame2-pair-mcp's epoch rollup
;; `:rf.epoch/sensitive? true`, carrying no unqualified `:sensitive?` stamp)
;; slip past the guard to the `:else` arm and ship RAW with dropped=0 —
;; leaking past the defensive single-map guard.
;; ---------------------------------------------------------------------------

;; The pair-mcp UNION strip-fn (mirrors tools/re-frame2-pair-mcp
;; sensitive/strip-sensitive): base trace-event stamp OR the epoch-level
;; `:rf.epoch/sensitive?` rollup, classified via the shared fail-closed
;; `sensitive-stamp?`.
(defn- union-strip [items _include?]
  (let [drop? (fn [x] (or (sensitive/sensitive-event? x)
                          (and (map? x)
                               (sensitive/sensitive-stamp? (:rf.epoch/sensitive? x)))))
        kept  (filterv (complement drop?) items)]
    [kept (- (count items) (count kept))]))

(deftest scrub-snapshot-single-map-sensitive-by-union-signal-dropped
  ;; A single-MAP :epochs slice sensitive ONLY by the union signal
  ;; (`:rf.epoch/sensitive? true`, NO unqualified `:sensitive?` stamp).
  ;; Hardcoding the base `sensitive-event?` in the single-map branch
  ;; (which returns false here — no `:sensitive?` stamp) would let the
  ;; slice fall to the `:else` arm and PASS THROUGH RAW with dropped=0,
  ;; leaking the secret. The branch routes through the caller-supplied
  ;; union strip-fn, so the union sensitivity is honoured.
  (let [snap {:rf/default {:epochs {:rf.epoch/sensitive? true
                                    :event-id :auth/sign-in
                                    :secret "TOKEN_LEAK"}}}
        [out dropped] (sensitive/scrub-snapshot snap false union-strip)]
    (is (= 1 dropped)
        "single-map slice sensitive by the union signal is counted as a drop")
    (is (= [] (get-in out [:rf/default :epochs]))
        "the union-sensitive single-map :epochs is dropped (empty batch), not shipped raw")
    (is (not (clojure.string/includes? (pr-str out) "TOKEN_LEAK"))
        "the secret never survives in the scrubbed output (fail-closed)"))
  ;; Symmetric :traces shape.
  (let [snap {:rf/default {:traces {:rf.epoch/sensitive? true :secret "TRACE_LEAK"}}}
        [out dropped] (sensitive/scrub-snapshot snap false union-strip)]
    (is (= 1 dropped))
    (is (= [] (get-in out [:rf/default :traces])))
    (is (not (clojure.string/includes? (pr-str out) "TRACE_LEAK")))))

(deftest scrub-snapshot-single-map-not-sensitive-by-union-passes-through
  ;; Symmetric safety: a single-map slice the union strip-fn
  ;; does NOT classify as sensitive (no `:sensitive?` stamp, no
  ;; `:rf.epoch/sensitive?` rollup) still passes through byte-identically.
  ;; The single-map branch must not over-drop benign single maps.
  (let [snap {:rf/default {:epochs {:event-id :ui/click :data 42}}}
        [out dropped] (sensitive/scrub-snapshot snap false union-strip)]
    (is (zero? dropped))
    (is (= {:event-id :ui/click :data 42} (get-in out [:rf/default :epochs]))
        "a single map the caller's strip-fn keeps survives verbatim")))

;; ---------------------------------------------------------------------------
;; Malformed counter — operator-surface observability for the fail-
;; closed gate.
;; ---------------------------------------------------------------------------

(deftest malformed-count-increments-on-fail-closed-drop
  ;; The fail-closed posture drops a non-boolean truthy
  ;; `:sensitive?` stamp AND increments a process-wide counter so
  ;; operator surfaces can see the contract drift. `malformed-count` /
  ;; `reset-malformed-count!` are public so this regression pin
  ;; can read the gate's activity.
  (sensitive/reset-malformed-count!)
  (is (zero? (sensitive/malformed-count))
      "precondition: counter starts at zero after reset")
  ;; Expected WARNs quieted by the central quiet-runner stderr buffer
  ;; — no local `*err*` sink needed.
  (sensitive/sensitive-event? {:sensitive? "true"})
  (sensitive/sensitive-event? {:sensitive? :yes})
  (sensitive/sensitive-event? {:sensitive? 1})
  (is (= 3 (sensitive/malformed-count))
      "every fail-closed drop bumps the counter once")
  ;; Well-formed stamps don't bump the counter.
  (sensitive/sensitive-event? {:sensitive? true})
  (sensitive/sensitive-event? {:sensitive? false})
  (sensitive/sensitive-event? {:sensitive? nil})
  (sensitive/sensitive-event? {})
  (is (= 3 (sensitive/malformed-count))
      "true / false / nil / absent stamps do NOT bump the counter")
  (sensitive/reset-malformed-count!))

(deftest reset-malformed-count!-zeroes-the-counter
  ;; Expected WARN quieted by the central quiet-runner stderr buffer
  ;; — no local `*err*` sink needed.
  (sensitive/sensitive-event? {:sensitive? "true"})
  (is (pos? (sensitive/malformed-count))
      "precondition: a fail-closed drop has bumped the counter")
  (is (zero? (sensitive/reset-malformed-count!))
      "reset returns the new value (zero)")
  (is (zero? (sensitive/malformed-count))
      "reset zeroes the counter for the next test"))

;; ---------------------------------------------------------------------------
;; The malformed-stamp diagnostic must not LEAK and must not
;; OVERCOUNT. Logs are an egress boundary on this privacy surface: a
;; malformed truthy `:sensitive?` stamp is wire-adjacent, untrusted data,
;; and a serialisation bug could carry a secret in it. The fail-closed
;; posture drops the event from MCP output; the diagnostic must NOT then
;; re-leak the value across the log boundary, and the malformed counter
;; must be a faithful PER-EVENT metric (one bump per dropped malformed
;; event), not a scan-strategy-dependent over-count.
;; ---------------------------------------------------------------------------

(deftest malformed-warning-redacts-raw-stamp-value
  ;; The contract-drift warning must carry a value-free type tag + a
  ;; fixed `:rf/redacted` sentinel — NEVER the raw stamp, which could
  ;; be a secret-bearing string / map / vector.
  (sensitive/reset-malformed-count!)
  (doseq [[stamp leak-needle] [["sk_live_SECRET_TOKEN_abc123" "sk_live_SECRET_TOKEN_abc123"]
                               [:secret/api-key-VALUE          "api-key-VALUE"]
                               [{:api_key "AKIA_LEAK"
                                 :token   "bearer_LEAK"}       "AKIA_LEAK"]
                               [["leaky-vector-ELEMENT"]       "leaky-vector-ELEMENT"]
                               [42424242                        "42424242"]]]
    (let [sw   (java.io.StringWriter.)
          _    (binding [*err* sw]
                 (sensitive/sensitive-event? {:operation :rf.event/dispatched
                                              :sensitive? stamp}))
          text (str sw)]
      (is (not (.contains text leak-needle))
          (str "malformed warning leaked the raw stamp payload for " (pr-str stamp)))
      (is (.contains text ":rf/redacted")
          (str "malformed warning must emit the :rf/redacted sentinel for " (pr-str stamp)))
      (is (.contains text "non-boolean truthy")
          "warning must still surface the fail-closed contract-drift reason")))
  (sensitive/reset-malformed-count!))

(deftest strip-sensitive-malformed-count-is-exactly-one-per-event
  ;; `sensitive-event?` is side-effecting on the malformed path
  ;; (bump + log). The single-pass classifier classifies each event
  ;; exactly once, so the counter is a faithful per-event metric. A
  ;; two-pass shape (`some` pre-scan + `filterv` drop-scan) would run
  ;; the predicate twice per event and bump the counter ~2× per
  ;; malformed event.
  ;; Expected WARNs quieted by the central quiet-runner stderr buffer
  ;; — no local `*err*` sink needed.
  (sensitive/reset-malformed-count!)
  (let [evts          [{:id 1 :sensitive? false}
                       {:id 2 :sensitive? "true"} ; malformed → drop, bump 1
                       {:id 3}
                       {:id 4 :sensitive? :yes}    ; malformed → drop, bump 1
                       {:id 5 :sensitive? true}]   ; well-formed → drop, NO bump
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
    (is (= 3 dropped) "all three sensitive events drop")
    (is (= 2 (sensitive/malformed-count))
        "exactly one bump per MALFORMED event — not ~2× per scan"))
  (sensitive/reset-malformed-count!))

(deftest strip-sensitive-malformed-warning-emitted-once-per-event
  ;; One malformed event ⇒ exactly one warning line on stderr through
  ;; `strip-sensitive` (single-pass classification) — no double-log.
  (sensitive/reset-malformed-count!)
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
      (sensitive/strip-sensitive [{:id 1 :sensitive? "sk_live_ONCE"}] false))
    (let [lines (->> (clojure.string/split-lines (str sw))
                     (filter #(.contains ^String % "non-boolean truthy")))]
      (is (= 1 (count lines))
          "exactly one contract-drift warning per malformed event (no double-log)")
      (is (not (.contains (str sw) "sk_live_ONCE"))
          "and that one warning still does not leak the raw value")))
  (sensitive/reset-malformed-count!))

(deftest scrub-snapshot-malformed-count-is-exactly-one-per-event
  ;; `scrub-snapshot` delegates each slice to
  ;; `strip-sensitive`, so the per-event count guarantee must hold
  ;; through the snapshot scrubber too — one malformed trace event in a
  ;; `:traces` slice bumps the counter exactly once.
  ;; Expected WARNs quieted by the central quiet-runner stderr buffer
  ;; — no local `*err*` sink needed.
  (sensitive/reset-malformed-count!)
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? "true"}  ; malformed → 1 bump
                                    {:id 2}]
                           :epochs [{:event-id :auth :sensitive? :yes}]}} ; malformed → 1 bump
        [_ dropped] (sensitive/scrub-snapshot snap false)]
    (is (= 2 dropped))
    (is (= 2 (sensitive/malformed-count))
        "scrub-snapshot bumps the counter exactly once per malformed event"))
  (sensitive/reset-malformed-count!))

(deftest strip-sensitive-no-drop-preserves-identity
  ;; The single-pass classifier keeps the fast-path identity guarantee
  ;; — when nothing drops, return the ORIGINAL vector (no fresh
  ;; allocation).
  (let [evts [{:id 1} {:id 2 :sensitive? false} {:id 3}]
        [kept dropped] (sensitive/strip-sensitive evts false)]
    (is (identical? evts kept)
        "no-drop path must return the identical input vector (zero allocation)")
    (is (zero? dropped))))

(deftest scrub-snapshot-2-arity-delegates-to-strip-sensitive
  ;; The 2-arity form is the default-suppress shape used by story-mcp;
  ;; its contract that it delegates to `strip-sensitive` is
  ;; the load-bearing spec/009 §Privacy MUST. A regression that flipped
  ;; the default to a no-op (or any other predicate) wouldn't trip the
  ;; existing tests — those only exercise the 2-arity form's outputs
  ;; against trace-event-stamp inputs, never the parity-with-3-arity
  ;; contract directly. Pin it: the 2-arity output MUST equal the
  ;; 3-arity call with `strip-sensitive` explicit.
  (let [snap {:rf/default
              {:traces [{:id 1 :sensitive? false}
                        {:id 2 :sensitive? true}
                        {:id 3}
                        {:id 4 :sensitive? true}]
               :epochs [{:event-id :foo}
                        {:event-id :auth/sign-in :sensitive? true}]
               :machines {}}}
        two-arity   (sensitive/scrub-snapshot snap false)
        three-arity (sensitive/scrub-snapshot snap false sensitive/strip-sensitive)]
    (is (= two-arity three-arity)
        "2-arity MUST delegate to strip-sensitive — spec/009 §Privacy default")))
