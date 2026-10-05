(ns re-frame.flows-test
  "JVM coverage for Spec 013 — Flows: the registry side of the clear
  lifecycle, registration validation and its error ids, cycle detection,
  hot-reload invalidation, frame routing, and the drain ordering `:fx` and
  the `:db` install observe.

  The canonical flow shapes — dirty-check, topological order, hot-reload,
  frame scoping, teardown — are described as data in
  spec/conformance/fixtures/flow-*.edn and driven against the live runtime
  by `re-frame.flows-conformance-test` (the flows artefact's own
  conformance gate, which claims the `:flow/*` capability set).

  The `:rf.fx/reg-flow` / `:rf.fx/clear-flow` settle is pinned in
  `re-frame.flows-settle-on-dispatch-test`."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset -------------------------------------------------------
;;
;; The standard per-process runtime reset is owned by
;; `re-frame.test-support/make-reset-runtime-fixture`: it folds the ns-load
;; registrar baseline back over the live registrar (run-order independence),
;; resets `frame/frames`, drops ALL flow state through the
;; `:flows/reset-flows!` hook (registry + last-inputs +
;; abandoned-output-paths, so a stale dirty-check entry from a sibling test
;; can't make a re-registration silently no-op), clears the schemas
;; per-frame side-table, (re)installs the plain-atom adapter + ensures
;; `:rf/default`, and binds `:rf/default` as the carried-invariant ambient
;; scope so the ambient `reg-flow` / `dispatch-sync` calls in the bodies
;; below carry a frame stamp (EP-0002).

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest exact-owner-loss-in-first-derive-stops-the-flow-tail
  ;; Mutation teeth: removing the post-derive owner check publishes :killer's
  ;; output; removing the per-flow loop check invokes :later against same-id B;
  ;; letting destroy+throw win over owner loss leaks an obsolete A exception.
  (let [id         :flow/destroy-owner
        later-runs (atom 0)
        destroyed  (CountDownLatch. 1)
        release    (CountDownLatch. 1)]
    (rf/make-frame {:id id})
    (rf/reg-flow :killer
      {:frame id :inputs [[:seed]] :output-path [:derived]}
      (fn [_]
        (rf.frame/destroy-frame! id)
        (.countDown destroyed)
        (.await release 10 TimeUnit/SECONDS)
        (throw (ex-info "obsolete derive failure" {}))))
    (rf/reg-flow :later
      {:frame id :inputs [[:derived]] :output-path [:later]}
      (fn [v] (swap! later-runs inc) v))
    (rf/reg-event :flow/destroy-owner-event
      (fn [_ _] {:db {:seed 1}}))
    (let [dispatch-a (future
                       (rf/dispatch-sync [:flow/destroy-owner-event]
                                         {:frame id}))]
      (is (.await destroyed 10 TimeUnit/SECONDS)
          "A was destroyed while its derive remained on the stack")
      (rf/make-frame {:id id})
      (.countDown release)
      (is (nil? (deref dispatch-a 5000 ::timeout))
          "destroy+throw in A's derive is inert after exact-owner loss"))
    (is (zero? @later-runs) "no later flow callback runs")
    (is (= {} (rf.frame/frame-app-db-value id))
        "A's pending handler/flow transition never commits into B")))

;; ---------------------------------------------------------------------------
;; 1. reg-flow / clear-flow lifecycle (registry side)
;; ---------------------------------------------------------------------------

(deftest clear-flow-prunes-empty-frame-slot-from-registry
  ;; Clearing the LAST flow on a frame dissocs the frame-id key from the
  ;; per-frame `@flows` registry entirely, not leaving a `{frame-id {}}` husk.
  ;; (This is the registry map, not app-db, whose vacation is leaf-only.)
  ;; Symmetric with
  ;; `teardown-on-frame-destroy!`'s `(swap! flows dissoc frame-id)`.
  (testing "clearing the sole flow on a frame removes the frame-id key from flows-snapshot"
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))
    (is (contains? (rf.flows/flows-snapshot) :rf/default)
        "precondition: the frame slot exists while a flow is registered")
    (rf/clear :flow :area)
    (is (not (contains? (rf.flows/flows-snapshot) :rf/default))
        "the frame-id key is GONE from @flows — no {frame-id {}} husk remains")))

(deftest clear-flow-noop-dissoc-does-not-rewrite-the-container
  ;; `clear-flow` skips `replace-container!` when the dissoc branch was a no-op
  ;; (the slot was never materialised / already absent). Without that guard,
  ;; clearing an absent slot would install a value-equal-but-fresh db reference
  ;; and trigger a needless O(n) reactive sub-graph invalidation walk — costly
  ;; during teardown, where clearing absent slots is common.
  ;;
  ;; This test proves the db REFERENCE is unchanged — i.e. the container was
  ;; not rewritten at all, so no spurious `{:step-2 nil}` parent was written
  ;; either. On the JVM
  ;; persistent maps are immutable, so two `app-db-value` reads return the
  ;; IDENTICAL object iff no `replace-container!` ran between them.
  ;; Precondition for the no-op branch: the flow's `:output-path` must never be
  ;; materialised. We seed app-db FIRST, then register the flow, then
  ;; clear it WITHOUT ever dispatching — so its `:derive` never runs and
  ;; its `:output-path` slot stays absent. (Driving a drain would compute the
  ;; flow and materialise the slot, turning the clear into a real dissoc.)
  (testing "clearing a never-materialised nested-path flow leaves the app-db container reference identical (no rewrite, no sub-cache invalidation)"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:other 1}}))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :pending {:inputs [[:n]] :output-path [:step-2 :result]} (fn [_] "never-runs"))
    (let [db-ref-before (rf/app-db-value :rf/default)]
      (rf/clear :flow :pending)
      (let [db-ref-after (rf/app-db-value :rf/default)]
        (is (identical? db-ref-before db-ref-after)
            "the app-db container reference is UNCHANGED — clear-flow skipped replace-container! for the no-op dissoc"))))
  (testing "clearing a single-element-path flow whose top-level key is absent also skips the rewrite"
    ;; The length-1 branch (`(dissoc db (first path))`) is a no-op when the
    ;; key is absent — `(identical? new-db db)` holds, so the guard skips
    ;; the write here too. Same precondition: never drive the drain.
    (rf/reg-event :seed2 (fn [{:keys [db]} _] {:db {:other 1}}))
    (rf/dispatch-sync [:seed2])
    (rf/reg-flow :absent-top {:inputs [[:n]] :output-path [:never-written]} (fn [_] "never-runs")) ;; single-element path, never materialised
    (let [db-ref-before (rf/app-db-value :rf/default)]
      (rf/clear :flow :absent-top)
      (is (identical? db-ref-before (rf/app-db-value :rf/default))
          "single-element absent key: container reference unchanged (no rewrite)")))
  (testing "POSITIVE control — clearing a MATERIALISED slot DOES rewrite the container (guard must not over-suppress real clears)"
    ;; The guard is `(when-not (identical? new-db db) (replace-container! ...))`.
    ;; When the slot was actually written, the dissoc produces a NEW db
    ;; reference, so the write MUST fire — otherwise the cleared value
    ;; would linger in app-db and stale subs would never invalidate.
    (rf/reg-event :seed3 (fn [{:keys [db]} _] {:db {:rect {:w 3 :h 4}}}))
    (rf/reg-flow :area3 {:inputs [[:rect :w] [:rect :h]] :output-path [:rect :area]} (fn [w h] (* w h)))
    (rf/dispatch-sync [:seed3])
    (is (= 12 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "precondition: the flow materialised [:rect :area]")
    (let [db-ref-before (rf/app-db-value :rf/default)]
      (rf/clear :flow :area3)
      (let [db-ref-after (rf/app-db-value :rf/default)]
        (is (not (identical? db-ref-before db-ref-after))
            "the container WAS rewritten — a real dissoc installs a fresh reference")
        (is (not (contains? (get db-ref-after :rect) :area))
            "and the leaf is gone from the installed value")))))

(deftest clear-flow-non-map-intermediate-is-noop
  ;; When an intermediate path step holds a non-map value (e.g. someone wrote
  ;; a scalar at `:step-2` before the flow's output ever materialised), a
  ;; naïve `(update-in cur [:step-2] dissoc :result)` would call
  ;; `(dissoc 1 :result)` and throw `ClassCastException`. The robust path
  ;; treats this as a no-op — the flow's `:output-path` never materialised, so
  ;; there's nothing to clear.
  (testing "clear-flow on a flow whose intermediate path step holds a scalar is a no-op (no throw)"
    ;; Seed a scalar at the parent slot. NO flow is active during this
    ;; drain — a flow whose `:output-path` is `[:step-2 :result]` would
    ;; `assoc-in` over the scalar (which throws on the JVM) and, per the
    ;; atomicity contract, abort the whole drain. The scalar-intermediate
    ;; case is about `clear-flow` robustness, not flow
    ;; evaluation, so we register the flow AFTER seeding and never drain
    ;; it — its `:output-path` stays un-materialised, which is exactly the
    ;; non-map-intermediate case `clear-flow` must treat as a no-op.
    (rf/reg-event :stamp-non-map (fn [{:keys [db]} _] {:db {:step-2 1 :foo 3 :bar 4}}))
    (rf/dispatch-sync [:stamp-non-map])
    ;; Register the flow (never drained) so the per-frame registry has the
    ;; entry to clear; its `:output-path` [:step-2 :result] never materialised.
    (rf/reg-flow :pending {:inputs [[:foo]] :output-path [:step-2 :result]} (fn [_] "never-stored"))
    ;; Clear must NOT throw, and must leave the scalar parent intact.
    (is (= :pending (rf/clear :flow :pending))
        "clear returns the id (no throw) when the intermediate is a non-map")
    (is (= 1 (:step-2 (rf/app-db-value :rf/default)))
        ":step-2 is preserved as its scalar value — the flow clear did not corrupt it")
    ;; Sanity: siblings untouched.
    (is (= 3 (:foo (rf/app-db-value :rf/default))))
    (is (= 4 (:bar (rf/app-db-value :rf/default))))))

;; ---------------------------------------------------------------------------
;; 1b. validate-flow well-formedness
;;
;; `validate-flow` fully checks `:inputs` and `:output-path` shape up front
;; rather than letting a malformed value boom deep in topo-sort. Without the
;; full check:
;;
;;   - `:inputs [:foo :bar]` (vector of bare keywords, NOT vector-of-paths)
;;     would pass and then throw inside topo-sort's `prefix?` on `(count :foo)`.
;;   - `:inputs [[:foo] :bar]` (mixed) would likewise pass, then explode on
;;     the bare-keyword entry.
;;   - `:output-path []` would pass; `(prefix? [] anything)` returns true,
;;     silently making the empty-path flow a depends-on prerequisite of EVERY
;;     other flow in the frame.
;;
;; These tests pin the contract: each malformation is rejected up front with a
;; stable error id (`:rf.error/flow-bad-inputs` or `:rf.error/flow-bad-path`)
;; and ex-data that names the offending entries so callers can fix their flow
;; map without a stack-trace scavenger hunt.

;; Branch on the canonical :rf.error/id discriminator, never on the
;; (human-sentence) message string.
(defn- reg-flow-throwing
  "reg-flow `flow-map`, returning the thrown Throwable (or nil if it did not
  throw). Centralises the try/catch so each malformation test reads as a
  single assertion against the canonical id + ex-data shape."
  [flow-map]
  ;; The 3-slot grammar: id is slot 1, :derive is the value slot,
  ;; the remaining reflection keys are the metadata middle slot.
  (try (rf/reg-flow (:id flow-map) (dissoc flow-map :id :derive) (:derive flow-map)) nil
       (catch Throwable t t)))

(deftest reg-flow-error-carries-canonical-rf-error-id-slot
  ;; Per Spec 009 §The thrown-error shape: every thrown runtime error carries
  ;; its discriminator under the canonical `:rf.error/id` slot (NOT an
  ;; `:error` slot), read by `:on-error` policies and Xray's error widget.
  ;; Each `validate-flow` shape rule throws its own id, and every one of them
  ;; builds the error through one helper, so the shape slots are read once.
  ;; The bad-inputs / bad-path / bad-marks tables below and the cycle tests
  ;; pin the remaining ids.
  (testing "each shape rule throws its own :rf.error/id"
    (are [id metadata derive-fn expected]
         (= expected (:rf.error/id (ex-data (try (rf/reg-flow id metadata derive-fn) nil
                                                 (catch Throwable t t)))))
      nil     {:inputs [[:n]] :output-path [:x]}          identity       :rf.error/flow-missing-id
      ;; The public FlowMeta schema requires a keyword id, and the `:flow-id`
      ;; trace / error slot carries it unchanged.
      "creds" {:inputs [[:n]] :output-path [:x]}          identity       :rf.error/flow-bad-id
      :bad    {:inputs :not-a-vector :output-path [:out]} (fn [_ _] nil) :rf.error/flow-bad-inputs
      :bad    {:inputs [[:n]] :output-path [:x]}          42             :rf.error/flow-bad-output))
  (testing "the thrown error carries the canonical shape"
    (let [ex (try
               (rf/reg-flow :bad {:inputs :not-a-vector :output-path [:out]} (fn [_ _] nil))
               (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      ;; Assert the [:rf.error/<id>] token substring, not equality.
      (is (re-find #"\[:rf\.error/flow-bad-inputs\]" (ex-message ex))
          "message carries the [:rf.error/flow-bad-inputs] token")
      (is (nil? (:error data))
          "ex-data carries no :error slot — :rf.error/id is the discriminator")
      (is (= 'rf/reg-flow (:where data)) ":where names the user-facing surface")
      (is (= :fix-registration (:recovery data)) ":recovery names the disposition")
      (is (string? (:reason data)) ":reason is a human-readable sentence"))))

(deftest reg-flow-rejects-malformed-inputs
  (testing "each malformed :inputs entry is rejected up front as
            :rf.error/flow-bad-inputs, and ex-data names only the bad entries"
    (are [inputs bad-entries]
         (= {:rf.error/id :rf.error/flow-bad-inputs :bad-entries bad-entries}
            (select-keys (ex-data (reg-flow-throwing {:id :bad :inputs inputs :derive identity
                                                      :output-path [:out]}))
                         [:rf.error/id :bad-entries]))
      [:foo :bar]   [:foo :bar]       ; bare keywords, not a vector of paths
      [[:foo] :bar] [:bar]            ; one bare keyword among well-formed paths
      [[]]          [[]]              ; an empty path reads nothing meaningful
      [[[:nested]]] [[[:nested]]])))  ; a path step that is a vector, not a scalar

(deftest reg-flow-rejects-malformed-output-path
  (testing "each malformed :output-path is rejected up front as
            :rf.error/flow-bad-path, naming the bad elements where there are any"
    (are [output-path bad-elements reason-re]
         (let [data (ex-data (reg-flow-throwing {:id :bad :inputs [[:n]] :derive identity
                                                 :output-path output-path}))]
           (and (= :rf.error/flow-bad-path (:rf.error/id data))
                (= bad-elements (:bad-elements data))
                (some? (re-find reason-re (str (:reason data))))))
      :not-a-vec  nil         #"must be a vector"
      ;; `(prefix? [] x)` holds for every x, so an empty path would make this
      ;; flow a prerequisite of every other flow in the frame
      []          nil         #"non-empty"
      [[:nested]] [[:nested]] #"path segment")))

(defn- flow-reserved-output-path? [^Throwable t]
  (= :rf.error/flow-reserved-output-path (:rf.error/id (ex-data t))))

(deftest reg-flow-rejects-runtime-partition-rooted-output-path
  ;; A flow `:output-path` may NOT be rooted at the reserved
  ;; runtime-db partition key (`:rf.db/runtime`). The leading key is reserved
  ;; for the INPUT side (`runtime-input?`); a flow output is always an app-db
  ;; write, and `evaluate-flow!` `assoc-in`s the derived value into the app-db
  ;; partition — so a `[:rf.db/runtime …]` output would write the reserved key
  ;; INSIDE app-db, enabling namespace-squat / spurious-topo-edge / false-cycle
  ;; footguns and leaving the flows.cljc prose invariant unenforced. The rule
  ;; rejects it at the API boundary.
  (testing "a multi-segment :rf.db/runtime-rooted :output-path is rejected"
    (let [ex (try
               (rf/reg-flow :bad {:inputs [[:n]] :output-path [rf.flows.registry/runtime-partition-key :cur]} identity)
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-reserved-output-path? ex)
          "error id is :rf.error/flow-reserved-output-path (distinct from the flow-bad-path shape family)")
      (is (= 'rf/reg-flow (:where (ex-data ex))) ":where names the user-facing surface")
      (is (= :fix-registration (:recovery (ex-data ex))) ":recovery names the disposition")
      (is (= [rf.flows.registry/runtime-partition-key] (:bad-elements (ex-data ex)))
          "ex-data names the offending leading segment")
      (is (re-find #":rf\.db/runtime" (:reason (ex-data ex)))
          ":reason names the reserved partition key")))
  (testing "a single-segment [:rf.db/runtime] :output-path is likewise rejected"
    (let [ex (try
               (rf/reg-flow :bad2 {:inputs [[:n]] :output-path [rf.flows.registry/runtime-partition-key]} identity)
               (catch Throwable t t))]
      (is (flow-reserved-output-path? ex)
          "a bare [:rf.db/runtime] output-path is rejected too")))
  (testing ":rf.db/runtime is legal DEEPER in an output-path (only the leading
            position is reserved — it names an ordinary app-db key there)"
    ;; The reservation is positional: only a LEADING :rf.db/runtime confuses
    ;; the topo/dirty-check partition logic. A :rf.db/runtime key nested under
    ;; a real app-db root is just an ordinary keyword key and must still pass.
    (is (some? (rf/reg-flow :deep {:inputs [[:n]] :output-path [:app rf.flows.registry/runtime-partition-key]} identity))
        "a non-leading :rf.db/runtime segment registers cleanly")
    (rf/clear :flow :deep)))

(deftest reg-flow-accepts-empty-inputs-vector
  (testing ":inputs [] is allowed (one-shot flow with no app-db dependencies)"
    ;; The well-formedness checks reject malformed entries inside :inputs,
    ;; but an empty :inputs vector itself remains valid — a zero-arg flow
    ;; that fires once and stays put (no path can change to dirty it). Pin
    ;; this so the every?-based checks don't accidentally reject the
    ;; legitimate empty-inputs case.
    (is (some? (rf/reg-flow :constant {:inputs [] :output-path [:k]} (fn [] 42))))))

;; ---------------------------------------------------------------------------
;; 1c. validate-flow data-classification well-formedness (EP-0025
;;     :rf.error/flow-bad-marks)
;;
;; EP-0025 lets a `reg-flow` registration carry data-classification keys
;; describing the SENSITIVITY / SIZE of the flow's OWN output value:
;; `:sensitive [paths]`, `:large [paths]`, and the `:large?` whole-output size
;; override (`[[]]` marks the whole output). `validate-flow`'s rejection table
;; fail-CLOSES on a malformed classification shape
;; rather than silently installing no redaction / large-elision — the worst
;; failure mode for a SAFETY feature is the author believing a slot is
;; protected when it is not.
;;
;; These rules fire AFTER the core `:id` / `:inputs` / `:derive` /
;; `:output-path` rules but BEFORE any registry / app-db / elision-declaration
;; state mutates (`validate-flow` is the first call in `reg-flow`). So a
;; rejected registration installs NO flow row and NO elision declaration. The
;; sibling `flow-missing-id` / `flow-bad-id` / `flow-bad-inputs` /
;; `flow-bad-output` / `flow-bad-path` / `flow-cycle` families are covered
;; above and in flows_destroy_frame_teardown_test; this block backstops the
;; EP-0025 `:rf.error/flow-bad-marks` family. Spec 015:325 names `:rf.error/flow-bad-marks` normatively.
;;
;; Branch on the canonical `:rf.error/id` discriminator, never on the
;; (human-sentence) message string.

(defn- flow-bad-marks? [^Throwable t]
  (= :rf.error/flow-bad-marks (:rf.error/id (ex-data t))))

(deftest reg-flow-rejects-malformed-classification-marks
  (testing "a malformed :sensitive / :large / :large? mark, or a removed
            classification spelling, is rejected :rf.error/flow-bad-marks with
            ex-data naming the key and what was wrong with it"
    (are [marks expected]
         (= (assoc expected :rf.error/id :rf.error/flow-bad-marks)
            (select-keys (ex-data (reg-flow-throwing (merge {:id :bad/marks :inputs [[:n]]
                                                             :derive identity :output-path [:out]}
                                                            marks)))
                         (conj (keys expected) :rf.error/id)))
      ;; :sensitive and :large must each be a vector of output subpaths ...
      {:sensitive {:secret :leak}}             {:bad-key :sensitive :bad-value {:secret :leak}}
      {:large "blob"}                          {:bad-key :large :bad-value "blob"}
      ;; ... and each entry a vector of path segments; only the bad entry is named
      {:sensitive [[:ok] :token]}              {:bad-key :sensitive :bad-entries [:token]}
      {:large [[:ok] [[:nested]]]}             {:bad-key :large :bad-entries [[[:nested]]]}
      ;; :large?, when present, must be a boolean
      {:large? 1}                              {:bad-key :large? :bad-value 1}
      ;; EP-0025: the whole output is classified with :sensitive [[]], and a flow
      ;; carries no input-to-output sensitivity propagation
      {:sensitive? true}                       {:bad-key :sensitive? :bad-value true :use :sensitive}
      {:rf.egress/output-sensitivity :inherit} {:bad-key :rf.egress/output-sensitivity :bad-value :inherit}))
  (testing "the message carries the [:rf.error/flow-bad-marks] greppability token"
    (is (re-find #"\[:rf\.error/flow-bad-marks\]"
                 (ex-message (reg-flow-throwing {:id :bad/marks :inputs [[:n]] :derive identity
                                                 :output-path [:out] :sensitive {:secret :leak}}))))))

(deftest reg-flow-bad-marks-installs-no-flow-row-and-no-elision-declaration
  (testing "a malformed flow-classification shape is rejected BEFORE any state
            mutates: no flow row lands in the per-frame registry, and no
            elision declaration is installed (mirrors
            reg-flow-against-destroyed-frame-rejects-and-mutates-nothing)"
    ;; validate-flow is the first call in reg-flow — before frame-id / the
    ;; swap! that writes the flows row and before write-flow-output-marks!
    ;; folds the classification declarations into the frame elision registry.
    ;; So a rejected registration must leave all three surfaces untouched.
    (let [flows-before     (rf.flows/flows-snapshot)
          sensitive-before (rf.elision/sensitive-declarations :rf/default)
          large-before     (rf.elision/declarations :rf/default)
          ex (reg-flow-throwing {:id          :bad/no-leak
                                 :inputs      [[:n]]
                                 :derive      identity
                                 :output-path [:out]
                                 ;; both a well-formed AND a malformed mark —
                                 ;; the malformed one must reject the WHOLE
                                 ;; registration, installing neither.
                                 :sensitive   [[:secret]]
                                 :large       :not-a-vector})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (= flows-before (rf.flows/flows-snapshot))
          "no flow row was installed — the per-frame flows registry is unchanged")
      (is (= sensitive-before (rf.elision/sensitive-declarations :rf/default))
          "no :sensitive elision declaration was installed (the well-formed
           [:secret] mark did not leak past the malformed :large rejection)")
      (is (= large-before (rf.elision/declarations :rf/default))
          "no :large elision declaration was installed"))))

(deftest reg-flow-cycle-error-carries-ordered-cycle-path
  ;; The cycle-error ex-data contract (per Spec 013 §Cycle detection /
  ;; Spec 009 §Error contract): `:cycle` is an ordered vector of flow ids with
  ;; a closing repeat — e.g. `[:a :b :a]` for the cycle :a → :b → :a. (An
  ;; unordered subset of stuck nodes would be useless for tooling rendering
  ;; the offending chain.) This test pins the ordered-closing-repeat shape.
  (testing "two-flow cycle: :cycle is [start ... start], length 3"
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
    (let [ex (try
               (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)
               (catch Throwable t t))
          data (ex-data ex)
          cycle (:cycle data)]
      (is (some? ex)        "registration threw")
      (is (vector? cycle)   ":cycle is a vector")
      ;; Spec 013 example: {:cycle [:a :b :a]}. Either :a or :b may
      ;; legally be the starting node (the impl picks deterministically
      ;; via sort-by hash; the spec leaves the starting node
      ;; implementation-defined) — assert one of the two valid
      ;; closures.
      (is (contains? #{[:a :b :a] [:b :a :b]} cycle)
          "the cycle path is one of the two valid two-flow closures: length 3 (n+1), closing on its start, naming both ids")))

  (testing "three-flow cycle: :a → :b → :c → :a"
    ;; Reset and build a longer chain. The reg-flow ordering matters
    ;; because the cycle is detected on the registration that closes
    ;; it — register :a, :b first (no cycle yet), then :c closes.
    (rf.flows/reset-flows!)
    (rf.flows/reset-last-inputs!)
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
    (rf/reg-flow :b {:inputs [[:c]] :output-path [:b]} identity)
    (let [ex (try
               (rf/reg-flow :c {:inputs [[:a]] :output-path [:c]} identity)
               (catch Throwable t t))
          cycle (:cycle (ex-data ex))]
      (is (some? ex) "three-flow cycle registration threw")
      (is (= 4 (count cycle))
          "three-flow cycle has length 4 (n+1)")
      (is (= (first cycle) (last cycle))
          ":cycle closes on itself")
      (is (= #{:a :b :c} (set (butlast cycle)))
          "all three offending ids appear in the path"))))

;; ---------------------------------------------------------------------------
;; 1c. Self-referential (single-node) dependency cycles
;;
;; A flow whose own :inputs overlap its own :output-path depends on itself.
;; A flow is a pure derivation of independently-owned facts, NOT a recurrence
;; over its own prior output (Spec 013 §Dependency rule), so a self-overlap is
;; a single-node cycle and must be rejected at registration — with the same
;; canonical `:rf.error/flow-cycle` used for multi-node cycles, closing-repeat
;; `[id id]`. Without this, the flow would silently become a stateful oscillator/
;; reducer that every UNRELATED event advances (1 → 2 → 3 …).
;; ---------------------------------------------------------------------------

(defn- flow-cycle? [^Throwable t]
  (= :rf.error/flow-cycle (:rf.error/id (ex-data t))))

(deftest reg-flow-self-cycle-replacement-preserves-prior-registration-and-output
  (testing "a hot-reload REPLACEMENT that introduces self-dependency is rejected
            and preserves the prior working flow, its dirty-check row, and its
            materialized output"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-event :tick (fn [{:keys [db]} _] {:db (update db :tick (fnil inc 0))}))
    (let [original-derive (fn [n] (* 2 n))]
      ;; A valid flow: reads [:n], writes [:derived :doubled]. Drain it so it
      ;; materializes an output AND seeds a dirty-check row.
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} original-derive)
      (rf/dispatch-sync [:init])
      (is (= 10 (get-in (rf/app-db-value :rf/default) [:derived :doubled]))
          "the valid flow materialized its output")
      (is (some? (rf.flows.registry/get-frame-flow-last-inputs :rf/default :double))
          "the valid flow seeded a dirty-check row")
      ;; Re-register :double so its INPUT now overlaps its OWN output — a
      ;; self-cycle. The replacement must be rejected.
      (let [ex (reg-flow-throwing {:id     :double
                                   :inputs [[:derived :doubled]]
                                   :derive (fn [d] (inc d))
                                   :output-path [:derived :doubled]})]
        (is (flow-cycle? ex) "the self-cyclic replacement is rejected"))
      ;; The prior working definition, its dirty-check row, and its output
      ;; must all survive — the rejection happened before any mutation.
      (let [after (get-in (rf.flows/flows-snapshot) [:rf/default :double])]
        (is (= [[:n]] (:inputs after))
            "prior :double's :inputs are intact ([[:n]], not the rejected self-input)")
        (is (identical? original-derive (:derive after))
            "prior :double's :derive fn has the SAME identity"))
      (is (some? (rf.flows.registry/get-frame-flow-last-inputs :rf/default :double))
          "the prior dirty-check row is preserved")
      (is (= 10 (get-in (rf/app-db-value :rf/default) [:derived :doubled]))
          "the prior materialized output is untouched"))))

;; ---------------------------------------------------------------------------
;; 4. Hot-reload — a re-registration re-evaluates on the next drain
;; ---------------------------------------------------------------------------

(deftest flow-hot-reload-invalidates-last-inputs
  (testing "re-registering a flow drops its dirty-check row, so the next drain
            evaluates the new body even though its inputs are unchanged"
    (rf/reg-event :init  (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-event :tick  (fn [{:keys [db]} _] {:db (update db :tick (fnil inc 0))}))
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} (fn [n] (* 2 n)))
    (rf/dispatch-sync [:init])
    (is (= 10 (get-in (rf/app-db-value :rf/default) [:derived :doubled])))
    ;; Re-register with a 100x body; same input still 5.
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} (fn [n] (* 100 n)))
    (rf/dispatch-sync [:tick])
    (is (= 500 (get-in (rf/app-db-value :rf/default) [:derived :doubled]))
        "after re-registration the new body produces 5 × 100 = 500")))

;; ---------------------------------------------------------------------------
;; 6. clean-state interaction: the per-frame store is the single source of
;;    truth (the `:flow` registrar slot is RESERVED-but-empty)
;; ---------------------------------------------------------------------------

(deftest reset-flows-clears-both-flows-and-last-inputs
  ;; `reset-flows!` resets BOTH the flow registry AND the dirty-check
  ;; `last-inputs` map. Clearing only `flows` would let a fixture / harness
  ;; calling `reset-flows!` standalone then re-registering the same flow-id
  ;; silently no-op the first evaluation when new-inputs =-equal a leftover
  ;; entry.
  (testing "reset-flows! drops both flow registry AND last-inputs in lockstep"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
    (rf/dispatch-sync [:init])
    (is (= 10 (:doubled (rf/app-db-value :rf/default)))
        "flow evaluated; last-inputs row populated for [:double :rf/default]")
    (is (some? (get-in (rf.flows/last-inputs-snapshot) [:double :rf/default]))
        "last-inputs has the dirty-check entry before reset")
    (rf.flows/reset-flows!)
    (is (empty? (rf.flows/flows-snapshot))
        "flow registry is empty after reset-flows!")
    (is (empty? (rf.flows/last-inputs-snapshot))
        "last-inputs is ALSO empty after reset-flows!")))

;; ---------------------------------------------------------------------------
;; 7. clear-flow :frame opt routing — multi-frame sibling isolation
;;
;; This deftest pins `clear-flow`'s `:frame` opt routing within the flows
;; artefact's own test alias — three branches in registry.cljc:
;;
;; 1. Frame opt routing: `(clear-flow :foo {:frame :left})` removes the
;;    flow from `:left`'s per-frame map only; sibling frame `:right`'s
;;    identically-named flow stays intact.
;; 2. Sibling isolation across the whole clear sequence: when the same flow
;;    id is registered against two frames, clearing it from one frame leaves
;;    the other frame's per-frame entry authoritative in place, and clearing
;;    the second frame drops the final entry. Under the single store
;;    the `:flow` registrar slot is RESERVED-but-empty — never
;;    written, so there is nothing to retain or unregister; the assertions
;;    below pin it `nil` throughout.
;; 3. app-db `dissoc-in` is frame-local: clearing on `:left` only
;;    dissoc-in's `:left`'s app-db; `:right`'s app-db is untouched.
;;
;; Spec 013 §Frame-scoping calls all three properties out normatively.
;; This deftest pins them inside the flows artefact's own gate so the
;; artefact doesn't rely on smoke_test.clj catching regressions.
;; ---------------------------------------------------------------------------

(deftest clear-flow-routes-via-frame-opt
  (testing "the same flow id registers independently against two frames"
    (rf/make-frame {:id :left :doc "left frame"})
    (rf/make-frame {:id :right :doc "right frame"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    ;; Register :compute against both frames with DIFFERENT :derive fns
    ;; so sibling-frame-untouched is observable in the materialised output.
    (rf/reg-flow :compute {:frame :left :inputs [[:n]] :output-path [:result]} (fn [n] (* 2 (or n 0))))
    (rf/reg-flow :compute {:frame :right :inputs [[:n]] :output-path [:result]} (fn [n] (* 100 (or n 0))))
    (rf/dispatch-sync [:seed 5] {:frame :left})
    (rf/dispatch-sync [:seed 5] {:frame :right})
    (is (= 10  (:result (rf/app-db-value :left)))
        "left frame's :compute used the 2x formula (5 * 2)")
    (is (= 500 (:result (rf/app-db-value :right)))
        "right frame's :compute used the 100x formula (5 * 100)")
    (is (contains? (get (rf.flows/flows-snapshot) :left)  :compute)
        ":left's per-frame registry slot carries :compute")
    (is (contains? (get (rf.flows/flows-snapshot) :right) :compute)
        ":right's per-frame registry slot carries :compute"))

  (testing "clear-flow on one frame leaves the sibling frame's registry slot intact"
    ;; Branch 1: per-frame registry routing.
    (rf/clear :flow :compute {:frame :left})
    (is (not (contains? (get (rf.flows/flows-snapshot) :left)  :compute))
        ":left's slot was removed")
    (is (contains? (get (rf.flows/flows-snapshot) :right) :compute)
        ":right's slot is untouched — flow STILL registered against :right"))

  (testing ":left's app-db output path is dissoc'd; :right's app-db is unchanged"
    ;; Branch 3: app-db dissoc-in is frame-local.
    (is (not (contains? (rf/app-db-value :left) :result))
        ":left's :result was dissoc'd by the frame-scoped clear")
    (is (= 500 (:result (rf/app-db-value :right)))
        ":right's :result is preserved (the previous compute's output)"))

  (testing "after clear, a re-drain does NOT recompute :left but DOES recompute :right"
    ;; Branch 4: the cleared flow truly stops firing. Re-seed both
    ;; frames and confirm :left's slot stays absent (no flow to run)
    ;; while :right's still-registered :compute recomputes off the new
    ;; input. The dissoc-only assertion above does not prove the flow stopped
    ;; firing on subsequent drains; this does.
    (rf/dispatch-sync [:seed 7] {:frame :left})
    (rf/dispatch-sync [:seed 7] {:frame :right})
    (is (not (contains? (rf/app-db-value :left) :result))
        ":left's :result stays absent — the cleared flow does not recompute")
    (is (= 700 (:result (rf/app-db-value :right)))
        ":right's :compute still active — 7 * 100 = 700"))

  (testing "single-store: clear on :left leaves :right's per-frame entry intact; no frame-blind slot to realign"
    ;; Under the per-frame single store, :right's entry is authoritative in
    ;; place — there is no shared registrar `:flow` slot to keep aligned. Per-
    ;; frame introspection shows :left cleared, :right still owning the id.
    (is (nil? (rf.flows/flow-meta {:frame :left :id :compute}))
        ":left's per-frame flow entry is gone after the clear")
    (is (some? (rf.flows/flow-meta {:frame :right :id :compute}))
        ":right's per-frame flow entry is intact — it still registers the id")
    (is (nil? (rf.registrar/lookup :flow :compute))
        "the :flow registrar slot is RESERVED-but-empty throughout"))

  (testing "clearing from the second (last) frame drops the final per-frame entry"
    (rf/clear :flow :compute {:frame :right})
    (is (not (contains? (get (rf.flows/flows-snapshot) :right) :compute))
        ":right's slot is now gone")
    (is (nil? (rf.flows/flow-meta {:frame :right :id :compute}))
        ":right's per-frame flow entry is gone")
    (is (nil? (rf.registrar/lookup :flow :compute))
        "the :flow registrar slot stays empty — single-store, nothing to unregister")))

;; ---------------------------------------------------------------------------
;; 7b. reg-flow / clear-flow normalize a FRAME-VALUE `:frame`
;;
;; The public frame-target contract (EP-0024) admits a frame VALUE
;; (make-frame's return token) EVERYWHERE a frame-id keyword is accepted; the
;; internal normalization seam (`frame/frame-target->id`) funnels a value to
;; its id. The flows READ path (`flow-meta`) normalizes, and so must the WRITE
;; paths (`reg-flow` / `clear-flow`): keying the per-frame store by the raw map
;; of an explicit `{:frame <value>}` would read a live frame as not-live at
;; registration and make a clear silently miss the flow. This pins that both
;; write paths normalize, so register-by-value + read-by-id + read-by-value +
;; clear-by-value all observe the SAME flow.
;; ---------------------------------------------------------------------------

(deftest reg-and-clear-flow-normalize-frame-value-target
  (testing "reg-flow with an explicit FRAME-VALUE `:frame` registers the flow
            (does NOT report the live frame as not-live), and flow-meta
            observes the SAME flow whether keyed by id or by value"
    (let [frame-val (rf/make-frame {:id :fv/host})]
      (is (rf.frame/frame-value? frame-val)
          "make-frame returns a frame VALUE, not a bare keyword")
      ;; Keying the `frame/frame` liveness probe (which keys `@frames` by the
      ;; bare id) with the raw value would miss the live frame and THROW
      ;; :rf.error/flow-frame-not-live.
      (is (= :area
             (rf/reg-flow :area
                          {:frame       frame-val
                           :inputs      [[:w] [:h]]
                           :output-path [:rect :area]}
                          (fn [w h] (* (or w 0) (or h 0)))))
          "reg-flow against a frame VALUE succeeds (frame normalized to its id)")
      ;; Read by id AND by value resolve the same registered flow.
      (is (some? (rf.flows/flow-meta {:frame :fv/host :id :area}))
          "flow-meta by frame-id finds the flow registered via the value")
      (is (some? (rf.flows/flow-meta {:frame frame-val :id :area}))
          "flow-meta by frame VALUE finds the same flow")
      (is (= (rf.flows/flow-meta {:frame :fv/host :id :area})
             (rf.flows/flow-meta {:frame frame-val :id :area}))
          "by-id and by-value observe the IDENTICAL flow meta")
      ;; The store is keyed by the normalized id, not the raw value map.
      (is (contains? (get (rf.flows/flows-snapshot) :fv/host) :area)
          "the per-frame store is keyed by the normalized frame-id")
      (is (not (contains? (rf.flows/flows-snapshot) frame-val))
          "the store is NOT keyed by the raw frame-value map"))

    (testing "clear-flow with an explicit FRAME-VALUE `:frame` clears the flow
              registered under the frame-id (does not silently miss it)"
      (let [frame-val (rf/make-frame {:id :fv/host})]  ; idempotent re-make; same id
        (rf/clear :flow :area {:frame frame-val})
        (is (nil? (rf.flows/flow-meta {:frame :fv/host :id :area}))
            "clear-by-value removed the flow observed by id")
        (is (nil? (rf.flows/flow-meta {:frame frame-val :id :area}))
            "clear-by-value removed the flow observed by value")
        (is (not (contains? (get (rf.flows/flows-snapshot) :fv/host) :area))
            "the per-frame store slot is gone after the value-keyed clear")))))

;; ---------------------------------------------------------------------------
;; 9. Frame-scoping coverage lives in `clear-flow-routes-via-frame-opt`
;; above (registration routing, app-db dissoc, per-frame sibling isolation,
;; AND the post-clear re-drain check).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; 9a. Re-registration invalidation is frame-scoped
;;
;; Spec 013 §Re-registration scopes the invalidation to `[frame-id flow-id]`.
;; `reg-flow` invalidates that one row directly. An invalidation that wiped
;; every frame's row under the flow id would have a re-registration on frame
;; `:left` clear `:right`'s last-inputs row too, causing unnecessary recompute
;; on `:right`'s next drain and weakening frame isolation.
;; ---------------------------------------------------------------------------

(deftest hot-reload-on-one-frame-does-not-invalidate-sibling-frames-last-inputs
  (testing "re-register :shared on :left; :right's last-inputs row survives"
    (rf/make-frame {:id :left :doc "left frame"})
    (rf/make-frame {:id :right :doc "right frame"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    ;; Register :shared against both frames with the same shape.
    (rf/reg-flow :shared {:frame :left :inputs [[:n]] :output-path [:result]} (fn [n] (* 2 (or n 0))))
    (rf/reg-flow :shared {:frame :right :inputs [[:n]] :output-path [:result]} (fn [n] (* 100 (or n 0))))
    ;; Drive a drain on each frame so both have last-inputs rows.
    (rf/dispatch-sync [:seed 5] {:frame :left})
    (rf/dispatch-sync [:seed 5] {:frame :right})
    (let [li (rf.flows/last-inputs-snapshot)]
      (is (some? (get-in li [:shared :left]))
          "before re-registration: :left's last-inputs row is populated")
      (is (some? (get-in li [:shared :right]))
          "before re-registration: :right's last-inputs row is populated"))
    ;; Re-register :shared on :left with a NEW body — should invalidate
    ;; :left's row ONLY.
    (rf/reg-flow :shared {:frame :left :inputs [[:n]] :output-path [:result]} (fn [n] (* 7 (or n 0))))
    (let [li (rf.flows/last-inputs-snapshot)]
      (is (nil? (get-in li [:shared :left]))
          "after re-registration on :left: :left's last-inputs row was dropped (re-evaluate on next drain)")
      (is (some? (get-in li [:shared :right]))
          ":right's last-inputs row is PRESERVED — re-registration on :left did not invalidate :right"))))

;; ---------------------------------------------------------------------------
;; 9b. Same-frame re-registration that KEEPS its :output-path leaves the
;;     prior output in app-db, on both hosts in
;;     `re-frame.flows-direct-reg-deferral-cljs-test`.
;;
;; Only a CHANGED :output-path vacates the old path: in a drain that move is
;; pinned in `re-frame.flows-lifecycle-drain-race-test`, and out of a drain
;; in `re-frame.flows-vector-parent-vacation-test`.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; 9c. Flow replacement evidence (per-frame decision, `:frame` attribution,
;;     `:different-fn?`, identical-reload suppression and reincarnation) runs
;;     on both hosts in `re-frame.flows-replace-clear-trace-incarnation-cljs-test`.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; 10. Ordering: flows transform the pending `:db` effect as the OUTERMOST
;;     `:after` — after the rest of the `:after` chain reshapes the db, and
;;     BEFORE the `:db` install + BEFORE `:fx` (Spec 013 §Drain integration).
;;
;; These pin two observable consequences of that ordering:
;;   (a) `:fx` sees the flow-derived app-db; and
;;   (b) flows run BEFORE the `:db` install (the value installed already
;;       carries flow output — single install of the flow-augmented db).
;; That flows run AFTER the rest of the `:after` chain (a path-scoped
;; handler still feeds the FULL db to flows) is pinned on both hosts in
;; `re-frame.flows-path-focused-no-db-cljs-test`.
;; ---------------------------------------------------------------------------

(deftest fx-sees-flow-derived-app-db
  (testing "(b) an :fx entry reading app-db sees the flow output"
    (let [fx-saw (atom :unset)]
      ;; A custom fx reads the live app-db when it runs; since :fx walks
      ;; after the flow-augmented install, it must see :doubled.
      (rf/reg-fx :test/peek-db
                 (fn [_m _args]
                   (reset! fx-saw (rf/app-db-value :rf/default))))
      (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/reg-event :go
                       (fn [_ [_ v]]
                         {:db {:n v}
                          :fx [[:test/peek-db {}]]}))
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
      (rf/dispatch-sync [:init])
      (rf/dispatch-sync [:go 5])
      (is (= 10 (:doubled @fx-saw))
          ":fx read the flow-derived :doubled (5 * 2 = 10) from app-db"))))

(deftest flow-runs-before-db-install
  (testing "(c) a single :db install carries the flow output, AND
            the :rf.event/db-changed trace fires once with the flow output"
    ;; Flows transform the pending :db effect, so the cascade performs
    ;; exactly ONE app-db install — of the flow-augmented value. We pin
    ;; this by (1) capturing app-db AT the :rf.event/db-changed trace emit
    ;; (which fires at install) and asserting it already carries the flow
    ;; output, and (2) asserting db-changed fired exactly once (no second
    ;; install from a separate post-install flow mutation).
    (let [db-at-changed (atom :unset)
          changed-count (atom 0)]
      (re-frame.trace.tooling/register-listener!
        ::db-changed-recorder
        (fn [ev]
          (when (= :rf.event/db-changed (:operation ev))
            (swap! changed-count inc)
            ;; At the db-changed emit the container has been replaced, so
            ;; reading the live app-db reflects the just-installed value.
            (reset! db-at-changed (rf/app-db-value :rf/default)))))
      (try
        (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 0}}))
        (rf/reg-event :set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
        (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
        (rf/dispatch-sync [:init])
        (reset! db-at-changed :unset)
        (reset! changed-count 0)
        (rf/dispatch-sync [:set-n 6])
        (is (= 1 @changed-count)
            "exactly one :rf.event/db-changed fired — a single, flow-augmented install
             (no separate post-install flow mutation)")
        (is (= 12 (:doubled @db-at-changed))
            "the db installed at :rf.event/db-changed already carried the flow output —
             flows ran before install")
        (finally
          (re-frame.trace.tooling/unregister-listener! ::db-changed-recorder))))))
