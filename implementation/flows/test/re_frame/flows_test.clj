(ns re-frame.flows-test
  "JVM smoke coverage for Spec 013 — Flows.

  This file backstops the conformance fixtures in
  spec/conformance/fixtures/flow-*.edn. Those fixtures describe canonical
  flow shapes as data and are driven against the live runtime by
  `re-frame.flows-conformance-test` (the flows artefact's own conformance
  gate, which claims the `:flow/*` capability set). The tests here exercise
  the same paths against the JVM reference implementation directly — a
  focused, debuggable companion to the data-driven gate so a regression in
  any of these shapes surfaces as a plain unit-test failure:

    - reg-flow / clear-flow round-trip
    - dirty-check (=-equal inputs do NOT recompute)
    - topological sort (B reads what A wrote; one drain pass)
    - cycle detection at registration time
    - hot-reload preserves the output value when the new body is
      value-equivalent on current inputs
    - :rf.fx/reg-flow / :rf.fx/clear-flow toggle round-trip
    - clear-all / lifecycle interaction with the per-frame registry"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.flows.registry :as registry]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace]))

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
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; 1. reg-flow / clear-flow lifecycle (registry side)
;; ---------------------------------------------------------------------------

(deftest reg-flow-populates-registry
  (testing "reg-flow stores the flow under [frame-id flow-id] in the per-frame registry"
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))
    (is (contains? (get (flows/flows-snapshot) :rf/default) :area)
        "the flow lives under :rf/default's slot of the per-frame registry")
    ;; Per rf2-en00bk the per-frame `flows` store is the SOLE store — the flow
    ;; is introspectable via the frame-scoped `flow-meta-at`, NOT a frame-blind
    ;; registrar `:flow` slot (which is RESERVED-but-empty).
    (is (some? (flows/flow-meta-at :area {:frame :rf/default}))
        "the flow is discoverable via the frame-scoped flow-meta-at")
    (is (nil? (registrar/lookup :flow :area))
        "the :flow registrar kind is RESERVED-but-empty — no slot is written (rf2-en00bk)")))

(deftest clear-flow-removes-from-registry-and-vacates-output-slot
  (testing "clear-flow removes the flow and dissoc-in's its output path"
    ;; clear-flow's update-in path math takes a different branch for
    ;; single-element :output-path vectors. Use a two-element :output-path here
    ;; so the (>= 2 elements) branch is exercised.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:rect {:w 3 :h 4}}}))
    (rf/reg-flow :area {:inputs [[:rect :w] [:rect :h]] :output-path [:rect :area]} (fn [w h] (* w h)))
    (rf/dispatch-sync [:seed])
    (is (= 12 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "flow ran on the drain after :seed and materialised :rect/:area")
    (flows/clear-flow :area)
    (is (not (contains? (get (flows/flows-snapshot) :rf/default) :area))
        "the per-frame registry no longer carries :area")
    (is (not (contains? (get (rf/app-db-value :rf/default) :rect) :area))
        "clear-flow dissoc'd the leaf at the flow's :output-path"))
  (testing "calling clear-flow on an unknown id is a no-op (does not throw)"
    (flows/clear-flow :no-such-flow)))

(deftest clear-flow-prunes-empty-frame-slot-from-registry
  ;; Clearing the LAST flow on a frame dissocs the frame-id key from the
  ;; per-frame `@flows` registry entirely, not leaving a `{frame-id {}}` husk.
  ;; (Distinct from the leaf-only app-db vacation husk covered by the next
  ;; deftest — this is about the registry map, not app-db.) Symmetric with
  ;; `teardown-on-frame-destroy!`'s `(swap! flows dissoc frame-id)`.
  (testing "clearing the sole flow on a frame removes the frame-id key from flows-snapshot"
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))
    (is (contains? (flows/flows-snapshot) :rf/default)
        "precondition: the frame slot exists while a flow is registered")
    (flows/clear-flow :area)
    (is (not (contains? (flows/flows-snapshot) :rf/default))
        "the frame-id key is GONE from @flows — no {frame-id {}} husk remains")))

(deftest clear-flow-vacates-leaf-only-leaving-empty-parent-husk
  ;; clear-flow's vacation contract is LEAF-ONLY. When the cleared flow's leaf
  ;; was the sole key under its parent, an empty parent map remains —
  ;; deliberate, not a leak. Pruning empty ancestor maps would risk deleting
  ;; unrelated sibling slots that happen to be empty, so the leaf-only
  ;; behaviour is the correct contract. The flow's *value* is fully gone (the
  ;; spec's "vacate the slot" requirement); only the structural empty-map
  ;; parent persists.
  (testing "clearing a flow whose leaf is the sole key under its parent leaves an empty parent map"
    (rf/reg-event :seed-wizard (fn [{:keys [db]} _] {:db {:wizard {}}}))
    (rf/reg-flow :wizard/result {:inputs [[:wizard :seed]] :output-path [:wizard :result]} (fn [_] 42))
    ;; Drive a drain so the flow materialises [:wizard :result].
    (rf/reg-event :touch-wizard (fn [{:keys [db]} _] {:db (assoc-in db [:wizard :seed] 1)}))
    (rf/dispatch-sync [:seed-wizard])
    (rf/dispatch-sync [:touch-wizard])
    (is (= 42 (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "flow materialised its output at the leaf")
    (flows/clear-flow :wizard/result)
    (let [db (rf/app-db-value :rf/default)]
      (is (not (contains? (get db :wizard) :result))
          "the leaf value is fully vacated")
      (is (contains? db :wizard)
          "the parent key persists (leaf-only vacation)")
      (is (= {} (dissoc (get db :wizard) :seed))
          "only the empty husk (plus unrelated sibling :seed) remains under the parent"))))

(deftest clear-flow-nested-path-before-first-compute-does-not-write-nil-parent
  ;; When a flow with a nested `:output-path` (e.g. `[:step-2 :result]`) is
  ;; cleared BEFORE any drain has run the flow's output, the parent slot
  ;; `:step-2` doesn't exist in app-db. A naïve
  ;; `(update-in cur [:step-2] dissoc :result)` would return
  ;; `(dissoc nil :result) ⇒ nil`, producing `{:step-2 nil}` — a spurious nil
  ;; parent. The robust path (`dissoc-in-safe`) leaves app-db unchanged when
  ;; the parent was never materialised.
  (testing "clear-flow on nested-path flow before first compute leaves app-db unchanged"
    (rf/reg-flow :pending {:inputs [[:n]] :output-path [:step-2 :result]} (fn [_] "never-runs"))
    (let [db-before (rf/app-db-value :rf/default)]
      (flows/clear-flow :pending)
      (let [db-after (rf/app-db-value :rf/default)]
        (is (= db-before db-after)
            "app-db is unchanged when clearing a never-materialised nested-path flow")
        (is (not (contains? db-after :step-2))
            "no spurious `:step-2 nil` parent was created")))))

(deftest clear-flow-noop-dissoc-does-not-rewrite-the-container
  ;; `clear-flow` skips `replace-container!` when the dissoc branch was a no-op
  ;; (the slot was never materialised / already absent). Without that guard,
  ;; clearing an absent slot would install a value-equal-but-fresh db reference
  ;; and trigger a needless O(n) reactive sub-graph invalidation walk — costly
  ;; during teardown, where clearing absent slots is common.
  ;;
  ;; The value-equality test above (`...before-first-compute...`) proves
  ;; the db VALUE is unchanged; this test proves the db REFERENCE is
  ;; unchanged — i.e. the container was not rewritten at all. On the JVM
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
      (flows/clear-flow :pending)
      (let [db-ref-after (rf/app-db-value :rf/default)]
        (is (identical? db-ref-before db-ref-after)
            "the app-db container reference is UNCHANGED — clear-flow skipped replace-container! for the no-op dissoc (rf2-2vpac)"))))
  (testing "clearing a single-element-path flow whose top-level key is absent also skips the rewrite"
    ;; The length-1 branch (`(dissoc db (first path))`) is a no-op when the
    ;; key is absent — `(identical? new-db db)` holds, so the guard skips
    ;; the write here too. Same precondition: never drive the drain.
    (rf/reg-event :seed2 (fn [{:keys [db]} _] {:db {:other 1}}))
    (rf/dispatch-sync [:seed2])
    (rf/reg-flow :absent-top {:inputs [[:n]] :output-path [:never-written]} (fn [_] "never-runs")) ;; single-element path, never materialised
    (let [db-ref-before (rf/app-db-value :rf/default)]
      (flows/clear-flow :absent-top)
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
      (flows/clear-flow :area3)
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
    (is (nil? (flows/clear-flow :pending))
        "clear-flow returns nil (no throw) when the intermediate is a non-map")
    (is (= 1 (:step-2 (rf/app-db-value :rf/default)))
        ":step-2 is preserved as its scalar value — clear-flow did not corrupt it")
    ;; Sanity: siblings untouched.
    (is (= 3 (:foo (rf/app-db-value :rf/default))))
    (is (= 4 (:bar (rf/app-db-value :rf/default))))))

(deftest clear-flow-handles-single-element-path
  (testing "rf2-aqt7: clear-flow with a single-element :output-path dissocs the top-level key"
    ;; A flow whose :output-path is a one-element vector [:area]. A naïve
    ;; (update-in cur [] dissoc :area) would leave :area in app-db (and
    ;; silently introduce an {nil nil} entry); the length-1 special-case
    ;; dissocs the top-level key directly.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:area]} (fn [w h] (* w h)))
    (rf/dispatch-sync [:seed])
    (is (= 12 (get (rf/app-db-value :rf/default) :area))
        "flow ran on the drain after :seed and materialised :area")
    (flows/clear-flow :area)
    (let [db (rf/app-db-value :rf/default)]
      (is (not (contains? db :area))
          "single-element :output-path is dissoc'd cleanly")
      (is (not (contains? db nil))
          "no spurious {nil nil} entry from update-in on empty path")
      (is (= {:w 3 :h 4} db)
          "siblings of the cleared key are untouched"))))

(deftest reg-flow-validates-required-keys
  (testing "missing :id throws"
    (is (thrown? Throwable
                 (rf/reg-flow nil {:inputs [[:n]] :output-path [:x]} identity))))
  (testing ":inputs must be a vector"
    (is (thrown? Throwable
                 (rf/reg-flow :bad {:inputs :not-a-vec :output-path [:x]} identity))))
  (testing ":derive must be a fn"
    (is (thrown? Throwable
                 (rf/reg-flow :bad {:inputs [[:n]] :output-path [:x]} 42))))
  (testing ":output-path must be a vector"
    (is (thrown? Throwable
                 (rf/reg-flow :bad {:inputs [[:n]] :output-path :not-a-vec} identity)))))

(deftest reg-flow-missing-id-and-bad-output-carry-canonical-error-ids
  ;; Companion to `reg-flow-error-carries-canonical-rf-error-id-slot`
  ;; (which pins the :inputs / cycle discriminators) and the dedicated
  ;; bad-inputs / bad-path tests. The two remaining `validate-flow`
  ;; rules — `:rf.error/flow-missing-id` and `:rf.error/flow-bad-output`
  ;; — had only bare `(thrown? Throwable ...)` coverage, so a regression
  ;; that changed EITHER discriminator id (read by `:on-error` policies
  ;; and Xray's error widget keyed on `:rf.error/id`, per Spec 009 §The
  ;; thrown-error shape) would pass silently. Pin both ids + the
  ;; canonical shape slots so the table's discriminator coverage is
  ;; total.
  (testing "a flow with no :id throws :rf.error/flow-missing-id"
    (let [ex   (try (rf/reg-flow nil {:inputs [[:n]] :output-path [:x]} identity)
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-missing-id (:rf.error/id data))
          ":rf.error/id carries the missing-id discriminator")
      ;; The message is the human :reason sentence + the trailing
      ;; [:rf.error/<id>] token; assert the token substring, not equality.
      (is (re-find #"\[:rf\.error/flow-missing-id\]" (ex-message ex))
          "message carries the [:rf.error/flow-missing-id] token")
      (is (= 'rf/reg-flow (:where data))     ":where names the user-facing surface")
      (is (= :fix-registration (:recovery data)) ":recovery names the disposition")
      (is (string? (:reason data))           ":reason is a human-readable sentence")))
  (testing "a flow whose :derive is not a fn throws :rf.error/flow-bad-output"
    (let [ex   (try (rf/reg-flow :bad {:inputs [[:n]] :output-path [:x]} 42)
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-bad-output (:rf.error/id data))
          ":rf.error/id carries the bad-output discriminator")
      ;; Assert the [:rf.error/<id>] token substring, not equality.
      (is (re-find #"\[:rf\.error/flow-bad-output\]" (ex-message ex))
          "message carries the [:rf.error/flow-bad-output] token")
      (is (= 'rf/reg-flow (:where data))     ":where names the user-facing surface")
      (is (= :fix-registration (:recovery data)) ":recovery names the disposition"))))

(deftest reg-flow-id-must-be-a-keyword
  ;; The public FlowMeta schema requires `[:id :keyword]` (Spec-Schemas
  ;; §FlowMeta) and the `:flow-id` slot is emitted unchanged into `:rf.flow/*`
  ;; trace + error payloads, so a non-keyword id would leak an arbitrary shape
  ;; downstream. `reg-flow` rejects a present-but-non-keyword id at the API
  ;; boundary with the dedicated `:rf.error/flow-bad-id` discriminator (a
  ;; member of the `:rf.error/flow-bad-*` family). nil/absent is
  ;; `:rf.error/flow-missing-id` (the absent-id case fires first); a keyword
  ;; id passes.
  (testing "a nil :id throws :rf.error/flow-missing-id (absent), NOT flow-bad-id"
    (let [ex (try (rf/reg-flow nil {:inputs [[:n]] :output-path [:x]} identity)
                  (catch Throwable t t))]
      (is (= :rf.error/flow-missing-id (:rf.error/id (ex-data ex)))
          "a nil id is the missing-id case (some? nil is false)")))
  (testing "a string :id throws :rf.error/flow-bad-id"
    (let [ex   (try (rf/reg-flow "creds" {:inputs [[:n]] :output-path [:x]} identity)
                    (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-bad-id (:rf.error/id data))
          ":rf.error/id carries the bad-id discriminator")
      ;; Assert the [:rf.error/<id>] token substring, not equality.
      (is (re-find #"\[:rf\.error/flow-bad-id\]" (ex-message ex))
          "message carries the [:rf.error/flow-bad-id] token")
      (is (= 'rf/reg-flow (:where data))         ":where names the user-facing surface")
      (is (= :fix-registration (:recovery data))  ":recovery names the disposition")
      (is (string? (:reason data))               ":reason is a human-readable sentence")))
  (testing "a number :id throws :rf.error/flow-bad-id"
    (let [ex (try (rf/reg-flow 42 {:inputs [[:n]] :output-path [:x]} identity)
                  (catch Throwable t t))]
      (is (= :rf.error/flow-bad-id (:rf.error/id (ex-data ex)))
          "a numeric id is rejected as bad-id")))
  (testing "a map :id throws :rf.error/flow-bad-id"
    (let [ex (try (rf/reg-flow {:k 1} {:inputs [[:n]] :output-path [:x]} identity)
                  (catch Throwable t t))]
      (is (= :rf.error/flow-bad-id (:rf.error/id (ex-data ex)))
          "a map id is rejected as bad-id")))
  (testing "a keyword :id is accepted (no throw)"
    (rf/reg-event :ok/init (fn [{:keys [db]} _] {:db db}))
    (is (= :ok/flow
           (rf/reg-flow :ok/flow {:inputs [[:n]] :output-path [:x]} identity))
        "a keyword id registers cleanly and reg-flow returns the id")))

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
(defn- flow-bad-inputs? [^Throwable t]
  (= :rf.error/flow-bad-inputs (:rf.error/id (ex-data t))))

(defn- flow-bad-path? [^Throwable t]
  (= :rf.error/flow-bad-path (:rf.error/id (ex-data t))))

(deftest reg-flow-error-carries-canonical-rf-error-id-slot
  ;; Per Spec 009 §The thrown-error shape: every thrown runtime error
  ;; carries the discriminator under the canonical `:rf.error/id` slot
  ;; (NOT the legacy `:error` slot), and the message string is the
  ;; stringified kw so `.getMessage` pivots to the same category.
  (testing "flow validation throw stamps :rf.error/id (canonical discriminator)"
    (let [ex (try
               (rf/reg-flow :bad {:inputs :not-a-vector :output-path [:out]} (fn [_ _] nil))
               (catch Throwable t t))
          data (ex-data ex)]
      (is (some? ex) "registration threw")
      (is (= :rf.error/flow-bad-inputs (:rf.error/id data))
          ":rf.error/id slot carries the discriminator keyword")
      ;; Assert the [:rf.error/<id>] token substring, not equality.
      (is (re-find #"\[:rf\.error/flow-bad-inputs\]" (ex-message ex))
          "message carries the [:rf.error/flow-bad-inputs] token")
      (is (nil? (:error data))
          "the legacy :error slot is gone (rename, not back-compat shim)")
      (is (= 'rf/reg-flow (:where data)) ":where names the user-facing surface")
      (is (= :fix-registration (:recovery data)) ":recovery names the disposition")
      (is (string? (:reason data)) ":reason is a human-readable sentence")))
  (testing "flow cycle throw stamps :rf.error/id"
    (flows/reset-flows!)
    (flows/reset-last-inputs!)
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
    (let [ex (try
               (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)
               (catch Throwable t t))
          data (ex-data ex)]
      (is (= :rf.error/flow-cycle (:rf.error/id data))
          "cycle throw carries :rf.error/id (topo.cljc inlined shape)")
      (is (nil? (:error data)) "legacy :error slot is gone on the cycle throw"))))

(deftest reg-flow-rejects-bare-keyword-inputs
  (testing ":inputs [:foo :bar] is rejected (vector of bare keywords, not vector-of-paths)"
    ;; Without the up-front check this would pass validate-flow and then throw
    ;; with (count :foo) somewhere deep in topo's prefix?.
    (let [ex (try
               (rf/reg-flow :bad {:inputs [:foo :bar] :output-path [:out]} (fn [_ _] nil))
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs")
      (is (= [:foo :bar] (:bad-entries (ex-data ex)))
          "ex-data names the offending entries (both bare keywords)"))))

(deftest reg-flow-rejects-mixed-input-shapes
  (testing ":inputs [[:foo] :bar] is rejected (one bare keyword among well-formed paths)"
    (let [ex (try
               (rf/reg-flow :bad {:inputs [[:foo] :bar] :output-path [:out]} (fn [_ _] nil))
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs")
      (is (= [:bar] (:bad-entries (ex-data ex)))
          "only the bare-keyword entry is named — the vector entry is fine"))))

(deftest reg-flow-rejects-empty-input-path
  (testing ":inputs [[]] is rejected (empty path is not a meaningful app-db read)"
    (let [ex (try
               (rf/reg-flow :bad {:inputs [[]] :output-path [:out]} (fn [_] nil))
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs"))))

(deftest reg-flow-rejects-collection-input-elements
  (testing ":inputs [[[:nested]]] is rejected (path step is a vector, not a scalar key)"
    (let [ex (try
               (rf/reg-flow :bad {:inputs [[[:nested]]] :output-path [:out]} (fn [_] nil))
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs"))))

(deftest reg-flow-rejects-empty-path
  (testing ":output-path [] is rejected (would make this flow a prerequisite of every other flow)"
    ;; (prefix? [] anything) returns true, so an empty-path flow would become
    ;; depends-on for every other flow in the frame. Per Spec 013 §Dependency
    ;; rule this is never what the caller means, so it is rejected.
    (let [ex (try
               (rf/reg-flow :bad {:inputs [[:n]] :output-path []} identity)
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-path? ex)
          "error id is :rf.error/flow-bad-path")
      (is (re-find #"non-empty" (:reason (ex-data ex)))
          "ex-data :reason mentions the non-empty requirement"))))

(deftest reg-flow-rejects-collection-path-elements
  (testing ":output-path [[:nested]] is rejected (path step is a vector, not a scalar key)"
    (let [ex (try
               (rf/reg-flow :bad {:inputs [[:n]] :output-path [[:nested]]} identity)
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-path? ex)
          "error id is :rf.error/flow-bad-path")
      (is (= [[:nested]] (:bad-elements (ex-data ex)))
          "ex-data names the offending element(s)"))))

(defn- flow-reserved-output-path? [^Throwable t]
  (= :rf.error/flow-reserved-output-path (:rf.error/id (ex-data t))))

(deftest reg-flow-rejects-runtime-partition-rooted-output-path
  ;; rf2-923gl4 — a flow `:output-path` may NOT be rooted at the reserved
  ;; runtime-db partition key (`:rf.db/runtime`). The leading key is reserved
  ;; for the INPUT side (`runtime-input?`); a flow output is always an app-db
  ;; write, and `evaluate-flow!` `assoc-in`s the derived value into the app-db
  ;; partition — so a `[:rf.db/runtime …]` output would write the reserved key
  ;; INSIDE app-db, enabling namespace-squat / spurious-topo-edge / false-cycle
  ;; footguns and leaving the flows.cljc prose invariant unenforced. Pre-fix
  ;; the registration was accepted; the rule rejects it at the API boundary.
  (testing "a multi-segment :rf.db/runtime-rooted :output-path is rejected"
    (let [ex (try
               (rf/reg-flow :bad {:inputs [[:n]] :output-path [registry/runtime-partition-key :cur]} identity)
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-reserved-output-path? ex)
          "error id is :rf.error/flow-reserved-output-path (distinct from the flow-bad-path shape family)")
      (is (= 'rf/reg-flow (:where (ex-data ex))) ":where names the user-facing surface")
      (is (= :fix-registration (:recovery (ex-data ex))) ":recovery names the disposition")
      (is (= [registry/runtime-partition-key] (:bad-elements (ex-data ex)))
          "ex-data names the offending leading segment")
      (is (re-find #":rf\.db/runtime" (:reason (ex-data ex)))
          ":reason names the reserved partition key")))
  (testing "a single-segment [:rf.db/runtime] :output-path is likewise rejected"
    (let [ex (try
               (rf/reg-flow :bad2 {:inputs [[:n]] :output-path [registry/runtime-partition-key]} identity)
               (catch Throwable t t))]
      (is (flow-reserved-output-path? ex)
          "a bare [:rf.db/runtime] output-path is rejected too")))
  (testing ":rf.db/runtime is legal DEEPER in an output-path (only the leading
            position is reserved — it names an ordinary app-db key there)"
    ;; The reservation is positional: only a LEADING :rf.db/runtime confuses
    ;; the topo/dirty-check partition logic. A :rf.db/runtime key nested under
    ;; a real app-db root is just an ordinary keyword key and must still pass.
    (is (some? (rf/reg-flow :deep {:inputs [[:n]] :output-path [:app registry/runtime-partition-key]} identity))
        "a non-leading :rf.db/runtime segment registers cleanly")
    (flows/clear-flow :deep)))

(deftest reg-flow-accepts-shared-domain-path-elements
  (testing "path elements across the SHARED EP-0012 segment domain all pass
            (keyword / string / integer / symbol / boolean — and, after the
            rf2-t3cfil widening to re-frame.path/segment?, UUID / instant /
            nil too)"
    ;; Flow `valid-path-element?` delegates to the shared
    ;; `re-frame.path/segment?` rather than a flows-private scalar enumeration,
    ;; so a flow path may focus through any concrete EDN identity segment the
    ;; `:rf/path` algebra supports — including a UUID-keyed map
    ;; (`{:by-id {#uuid "…" …}}`), an instant key, and the nil key. Each
    ;; round-trips through reg-flow without throwing; the shared validation
    ;; admits the common scalar case alongside these.
    (doseq [[label elt] [[:kw      :kw]
                         [:string  "str"]
                         [:int     42]
                         [:symbol  'sym]
                         [:bool    true]
                         [:uuid    #uuid "00000000-0000-0000-0000-000000000001"]
                         [:instant #inst "2026-06-12T00:00:00.000-00:00"]
                         [:nilkey  nil]]]
      (let [flow-id (keyword "elt" (name label))]
        (is (some? (rf/reg-flow flow-id {:inputs [[:root elt]] :output-path [:out elt]} identity))
            (str "shared-domain path segment " (pr-str elt) " is accepted"))
        (flows/clear-flow flow-id)))))

(deftest reg-flow-accepts-empty-inputs-vector
  (testing ":inputs [] is allowed (one-shot flow with no app-db dependencies)"
    ;; The well-formedness checks reject malformed entries inside :inputs,
    ;; but an empty :inputs vector itself remains valid — a zero-arg flow
    ;; that fires once and stays put (no path can change to dirty it). Pin
    ;; this so the new every?-based checks don't accidentally reject the
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
;; (registry.cljc ~L558-612) fail-CLOSES on a malformed classification shape
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
;; EP-0025-added `:rf.error/flow-bad-marks` family, which had ZERO coverage
;; (rf2-r87zhb). Spec 015:325 names `:rf.error/flow-bad-marks` normatively.
;;
;; Branch on the canonical `:rf.error/id` discriminator, never on the
;; (human-sentence) message string.

(defn- flow-bad-marks? [^Throwable t]
  (= :rf.error/flow-bad-marks (:rf.error/id (ex-data t))))

(defn- reg-flow-throwing
  "reg-flow `flow-map`, returning the thrown Throwable (or nil if it did not
  throw). Centralises the try/catch so each malformation test reads as a
  single assertion against the canonical id + ex-data shape."
  [flow-map]
  ;; rf2-bqstzr — the 3-slot grammar: id is slot 1, :derive is the value slot,
  ;; the remaining reflection keys are the metadata middle slot.
  (try (rf/reg-flow (:id flow-map) (dissoc flow-map :id :derive) (:derive flow-map)) nil
       (catch Throwable t t)))

(deftest reg-flow-rejects-non-vector-sensitive
  (testing ":sensitive must be a vector of output subpaths — a non-vector is
            rejected :rf.error/flow-bad-marks with :bad-key / :bad-value"
    (let [bad {:secret :leak}                      ; a map, not a vector
          ex  (reg-flow-throwing {:id          :bad/sensitive-shape
                                  :inputs      [[:n]]
                                  :derive      identity
                                  :output-path [:out]
                                  :sensitive   bad})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (re-find #"\[:rf\.error/flow-bad-marks\]" (ex-message ex))
          "message carries the [:rf.error/flow-bad-marks] token")
      (is (= :sensitive (:bad-key (ex-data ex)))
          "ex-data names the offending key")
      (is (= bad (:bad-value (ex-data ex)))
          "ex-data carries the offending value for the diagnostic"))))

(deftest reg-flow-rejects-malformed-sensitive-entry
  (testing ":sensitive entries must each be a vector of path segments — a bare
            keyword entry is rejected :rf.error/flow-bad-marks with :bad-entries"
    ;; [:token] (a bare keyword) is NOT a valid output subpath — a subpath is a
    ;; vector of scalar keys ([] = whole output). The diagnostic distinguishes
    ;; "you passed a non-vector" (above) from "one of your entries is malformed".
    (let [ex (reg-flow-throwing {:id          :bad/sensitive-entry
                                 :inputs      [[:n]]
                                 :derive      identity
                                 :output-path [:out]
                                 :sensitive   [[:ok] :token]})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (= :sensitive (:bad-key (ex-data ex)))
          "ex-data names the offending key")
      (is (= [:token] (:bad-entries (ex-data ex)))
          "only the malformed entry is named — the well-formed [:ok] is fine"))))

(deftest reg-flow-rejects-non-vector-large
  (testing ":large must be a vector of output subpaths — a non-vector is
            rejected :rf.error/flow-bad-marks with :bad-key / :bad-value"
    (let [bad "blob"                               ; a string, not a vector
          ex  (reg-flow-throwing {:id          :bad/large-shape
                                  :inputs      [[:n]]
                                  :derive      identity
                                  :output-path [:out]
                                  :large       bad})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (= :large (:bad-key (ex-data ex)))
          "ex-data names the offending key")
      (is (= bad (:bad-value (ex-data ex)))
          "ex-data carries the offending value"))))

(deftest reg-flow-rejects-malformed-large-entry
  (testing ":large entries must each be a vector of path segments — a
            collection path step is rejected :rf.error/flow-bad-marks"
    ;; [[:nested]] is a path whose step is itself a vector (not a scalar key),
    ;; so it is not a valid output subpath.
    (let [ex (reg-flow-throwing {:id          :bad/large-entry
                                 :inputs      [[:n]]
                                 :derive      identity
                                 :output-path [:out]
                                 :large       [[:ok] [[:nested]]]})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (= :large (:bad-key (ex-data ex)))
          "ex-data names the offending key")
      (is (= [[[:nested]]] (:bad-entries (ex-data ex)))
          "only the malformed entry is named — the well-formed [:ok] is fine"))))

(deftest reg-flow-rejects-boolean-sensitive?-spelling
  (testing "the boolean :sensitive? spelling is rejected on a flow output
            (EP-0025) — :rf.error/flow-bad-marks, with :use :sensitive pointing
            at the correct whole-output spelling :sensitive [[]]"
    ;; EP-0025 removed flow input→output propagation; the boolean :sensitive?
    ;; spelling stays rejected (use :sensitive [[]] for a whole-output mark).
    (let [ex (reg-flow-throwing {:id          :bad/sensitive?-spelling
                                 :inputs      [[:n]]
                                 :derive      identity
                                 :output-path [:out]
                                 :sensitive?  true})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (= :sensitive? (:bad-key (ex-data ex)))
          "ex-data names the rejected :sensitive? key")
      (is (= true (:bad-value (ex-data ex)))
          "ex-data carries the offending boolean value")
      (is (= :sensitive (:use (ex-data ex)))
          "ex-data :use points at the correct :sensitive spelling"))))

(deftest reg-flow-rejects-output-sensitivity-propagation-key
  (testing ":rf.egress/output-sensitivity is removed (EP-0025 — no flow
            input→output propagation) and rejected :rf.error/flow-bad-marks"
    (let [ex (reg-flow-throwing {:id                            :bad/output-sensitivity
                                 :inputs                        [[:n]]
                                 :derive                        identity
                                 :output-path                   [:out]
                                 :rf.egress/output-sensitivity  :inherit})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (= :rf.egress/output-sensitivity (:bad-key (ex-data ex)))
          "ex-data names the removed propagation key")
      (is (= :inherit (:bad-value (ex-data ex)))
          "ex-data carries the offending (removed enum) value"))))

(deftest reg-flow-rejects-non-boolean-large?
  (testing ":large?, when present, must be a boolean — a non-boolean (e.g. 1)
            is rejected :rf.error/flow-bad-marks with :bad-key / :bad-value"
    (let [ex (reg-flow-throwing {:id          :bad/large?-shape
                                 :inputs      [[:n]]
                                 :derive      identity
                                 :output-path [:out]
                                 :large?      1})]
      (is (some? ex) "registration threw")
      (is (flow-bad-marks? ex) "error id is :rf.error/flow-bad-marks")
      (is (= :large? (:bad-key (ex-data ex)))
          "ex-data names the offending key")
      (is (= 1 (:bad-value (ex-data ex)))
          "ex-data carries the offending non-boolean value"))))

(deftest reg-flow-bad-marks-installs-no-flow-row-and-no-elision-declaration
  (testing "a malformed flow-classification shape is rejected BEFORE any state
            mutates: no flow row lands in the per-frame registry, and no
            elision declaration is installed (mirrors
            reg-flow-against-destroyed-frame-rejects-and-mutates-nothing)"
    ;; validate-flow is the first call in reg-flow — before frame-id / the
    ;; swap! that writes the flows row and before write-flow-output-marks!
    ;; folds the classification declarations into the frame elision registry.
    ;; So a rejected registration must leave all three surfaces untouched.
    (let [flows-before     (flows/flows-snapshot)
          sensitive-before (elision/sensitive-declarations :rf/default)
          large-before     (elision/declarations :rf/default)
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
      (is (= flows-before (flows/flows-snapshot))
          "no flow row was installed — the per-frame flows registry is unchanged")
      (is (not (contains? (get (flows/flows-snapshot) :rf/default) :bad/no-leak))
          "specifically: the rejected flow id is absent from the registry")
      (is (= sensitive-before (elision/sensitive-declarations :rf/default))
          "no :sensitive elision declaration was installed (the well-formed
           [:secret] mark did not leak past the malformed :large rejection)")
      (is (= large-before (elision/declarations :rf/default))
          "no :large elision declaration was installed"))))

(deftest reg-flow-detects-cycles-at-registration
  (testing ":a depends on :b, :b depends on :a — registering the second throws"
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
    (is (thrown? Throwable
                 (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity))
        "the cyclic registration unwinds and throws :rf.error/flow-cycle")
    (is (not (contains? (get (flows/flows-snapshot) :rf/default) :b))
        "cycle-detection rolls back the partial registration of :b")))

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
      (is (= 3 (count cycle))
          "two-flow cycle has length 3 (n+1, including the closing repeat)")
      (is (= (first cycle) (last cycle))
          ":cycle closes on itself (first = last)")
      (is (= #{:a :b} (set cycle))
          ":cycle names both offending flow ids")
      ;; Spec 013 example: {:cycle [:a :b :a]}. Either :a or :b may
      ;; legally be the starting node (the impl picks deterministically
      ;; via sort-by hash; the spec leaves the starting node
      ;; implementation-defined) — assert one of the two valid
      ;; closures.
      (is (contains? #{[:a :b :a] [:b :a :b]} cycle)
          "the cycle path is one of the two valid two-flow closures")))

  (testing "three-flow cycle: :a → :b → :c → :a"
    ;; Reset and build a longer chain. The reg-flow ordering matters
    ;; because the cycle is detected on the registration that closes
    ;; it — register :a, :b first (no cycle yet), then :c closes.
    (flows/reset-flows!)
    (flows/reset-last-inputs!)
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

(deftest reg-flow-replacement-that-introduces-cycle-preserves-prior-registration
  ;; `reg-flow` runs cycle detection on a PROSPECTIVE flow-map BEFORE
  ;; mutating; on failure nothing is written and the prior registration stays
  ;; intact. A rollback path that wrote the new entry FIRST and then dissoc'd
  ;; by id on a detected cycle would DELETE the prior registration as well as
  ;; the just-written one — so a hot-reload that accidentally introduced a
  ;; cycle would silently vacate the previously-working flow. This test pins
  ;; that the prior registration survives.
  (testing "a cyclic reg-flow REPLACEMENT must not silently delete the prior registration"
    ;; Set up a non-cyclic two-flow graph where REPLACING :b is what
    ;; closes the cycle. Cannot use a self-cycle on :b (topo-sort skips
    ;; the `id = other` self-edge via `(not= id other)`), so we set
    ;; :a's :inputs to point at :b's :output-path. After replacement of :b's
    ;; inputs to point at :a's :output-path, the cycle :a → :b → :a closes.
    ;;
    ;; 1. :a reads [:b], writes [:a]. Currently no cycle because :b is
    ;;    not yet registered.
    (rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} (fn [b] (str "A-from-B-" b)))
    (is (contains? (get (flows/flows-snapshot) :rf/default) :a)
        "initial :a registers cleanly")

    ;; 2. :b reads an unrelated path [:source], writes [:b]. Graph is
    ;;    :b → :a (one-way), no cycle. The prior `reg-flow-detects-
    ;;    cycles-at-registration` test pins the INITIAL-cycle case
    ;;    (where :b at first registration closes the cycle). This test
    ;;    pins the REPLACEMENT case — :b registers cleanly first, then
    ;;    its replacement is what would close the cycle.
    (let [original-b-output (fn [src] (str "B-of-" src))]
      (rf/reg-flow :b {:inputs [[:source]] :output-path [:b]} original-b-output)
      (is (contains? (get (flows/flows-snapshot) :rf/default) :b)
          "initial :b registers cleanly (reads [:source]; no cycle)")

      ;; 3. RE-register :b with :inputs [[:a]]. :a already reads [:b],
      ;;    so the prospective graph closes :a → :b → :a. Cycle
      ;;    detection MUST reject the replacement.
      (is (thrown? Throwable
            (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} (fn [a] (str "B-from-A-" a))))
          "the cyclic replacement of :b throws :rf.error/flow-cycle")

      ;; 4. THE KEY ASSERTION: the prior :b is STILL in the registry —
      ;;    not silently deleted. A naive rollback that wrote the new
      ;;    registration first and then dissoc'd it by id on rejection
      ;;    WOULD have vacated the prior registration along with the
      ;;    just-written one. The atomic prospective-map swap prevents
      ;;    this: cycle/overlap detection runs on the prospective map
      ;;    inside the swap! update fn and never commits on rejection,
      ;;    so the prior registration is left untouched.
      (is (contains? (get (flows/flows-snapshot) :rf/default) :b)
          "after a failed cyclic replacement, the prior :b registration is preserved")
      (let [b-after (get-in (flows/flows-snapshot) [:rf/default :b])]
        (is (= [[:source]] (:inputs b-after))
            "prior :b's :inputs are intact ([[:source]], not the rejected [[:a]])")
        (is (identical? original-b-output (:derive b-after))
            "prior :b's :derive fn has the SAME identity (not the rejected new fn)"))
      ;; And the per-frame store — the single source of truth (rf2-en00bk) —
      ;; must still resolve the prior :b for this frame.
      (is (some? (flows/flow-meta-at :b {:frame :rf/default}))
          "the per-frame flow store for :b is still populated"))))

;; ---------------------------------------------------------------------------
;; 2. Dirty-check / re-evaluation
;; ---------------------------------------------------------------------------

(deftest flow-recomputes-on-input-change
  (testing "mutating an input path causes the flow to fire and the output to update"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:w 0 :h 0}}))
    (rf/reg-event :w!   (fn [{:keys [db]} [_ w]] {:db (assoc db :w w)}))
    (rf/reg-event :h!   (fn [{:keys [db]} [_ h]] {:db (assoc db :h h)}))
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* w h)))
    (rf/dispatch-sync [:init])
    (is (= 0 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "first drain after :init fires the flow with 0 × 0 = 0")
    (rf/dispatch-sync [:w! 5])
    (is (= 0 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        ":h is still 0 → 5 × 0 = 0; flow ran")
    (rf/dispatch-sync [:h! 6])
    (is (= 30 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "5 × 6 = 30 after both inputs are populated")))

(deftest flow-noop-on-equal-input-rewrite
  (testing "rewriting an input path with an =-equal value does NOT re-fire the flow"
    (let [calls (atom 0)]
      (rf/reg-event :init      (fn [{:keys [db]} _] {:db {:n 5}}))
      (rf/reg-event :replace-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} (fn [n]
                              (swap! calls inc)
                              (* 2 n)))
      (rf/dispatch-sync [:init])
      (is (= 1 @calls) "first drain fires the flow once (initial evaluation)")
      (is (= 10 (get-in (rf/app-db-value :rf/default) [:derived :doubled])))
      ;; Replace :n with the same value (5 → 5).
      (rf/dispatch-sync [:replace-n 5])
      (is (= 1 @calls)
          ":n was replaced with =-equal value; flow did NOT recompute")
      (is (= 10 (get-in (rf/app-db-value :rf/default) [:derived :doubled]))
          "output unchanged")
      ;; Now flip :n to a different value.
      (rf/dispatch-sync [:replace-n 7])
      (is (= 2 @calls)
          ":n changed to 7; flow recomputed")
      (is (= 14 (get-in (rf/app-db-value :rf/default) [:derived :doubled]))))))

(deftest flow-no-recompute-when-unrelated-path-changes
  (testing "writing an unrelated path does not re-fire a flow whose inputs are stable"
    (let [calls (atom 0)]
      (rf/reg-event :init      (fn [{:keys [db]} _] {:db {:user {:name "alice"} :other 0}}))
      (rf/reg-event :bump-other (fn [{:keys [db]} _] {:db (update db :other inc)}))
      (rf/reg-flow :user/uppercase-name {:inputs [[:user :name]] :output-path [:user :uppercase-name]} (fn [n]
                              (swap! calls inc)
                              (when n (.toUpperCase ^String n))))
      (rf/dispatch-sync [:init])
      (is (= 1 @calls) "first evaluation always fires")
      (is (= "ALICE" (get-in (rf/app-db-value :rf/default)
                             [:user :uppercase-name])))
      (dotimes [_ 5] (rf/dispatch-sync [:bump-other]))
      (is (= 1 @calls)
          ":other changed but [:user :name] did not; flow stayed quiet"))))

;; ---------------------------------------------------------------------------
;; 3. Topological sort — B depends on A; one drain settles both
;; ---------------------------------------------------------------------------

(deftest flow-topo-sort-cascades-in-one-drain
  (testing "B reads what A wrote; topo sort places A first; one drain settles both"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:w 2 :h 3}}))
    (rf/reg-event :w!   (fn [{:keys [db]} [_ w]] {:db (assoc db :w w)}))
    ;; A: :area depends on :w :h, writes :rect/:area.
    (rf/reg-flow :rect/area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* w h)))
    ;; B: :area*2 depends on :rect/:area, writes :rect/:area*2.
    (rf/reg-flow :rect/area-doubled {:inputs [[:rect :area]] :output-path [:rect :area*2]} (fn [a] (* 2 a)))
    (rf/dispatch-sync [:init])
    (let [db (rf/app-db-value :rf/default)]
      (is (= 6  (get-in db [:rect :area]))   "A fired with 2 × 3 = 6")
      (is (= 12 (get-in db [:rect :area*2])) "B fired in the same drain with 6 × 2 = 12"))
    (rf/dispatch-sync [:w! 5])
    (let [db (rf/app-db-value :rf/default)]
      (is (= 15 (get-in db [:rect :area]))   "A re-fired: 5 × 3 = 15")
      (is (= 30 (get-in db [:rect :area*2])) "B saw A's new output and re-fired: 30"))))

(deftest flow-topo-sort-handles-prefix-overlap
  (testing "B's :inputs is a prefix of A's :output-path — A still runs before B (Spec 013 §Dependency rule)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:user {:name "alice"} :note ""}}))
    ;; A writes deep at [:user :uppercase] — its :output-path is rooted in
    ;; the same prefix as B's input.
    (rf/reg-flow :user/uppercase {:inputs [[:user :name]] :output-path [:user :uppercase]} (fn [n] (when n (.toUpperCase ^String n))))
    ;; B's input is [:user] — a prefix of A's :output-path. Per Spec 013,
    ;; the dependency rule fires in either prefix direction.
    (rf/reg-flow :user/note {:inputs [[:user]] :output-path [:summary :note]} (fn [u]
                            (str "user-keys:"
                                 (pr-str (vec (sort (keys u)))))))
    (rf/dispatch-sync [:init])
    (let [db (rf/app-db-value :rf/default)]
      (is (= "ALICE" (get-in db [:user :uppercase]))
          "A wrote :user :uppercase")
      (is (= "user-keys:[:name :uppercase]"
             (get-in db [:summary :note]))
          "B saw both :name and the just-written :uppercase in one drain"))))

;; ---------------------------------------------------------------------------
;; 4. Hot-reload — re-registration preserves output when bodies agree
;; ---------------------------------------------------------------------------

(deftest flow-hot-reload-preserves-equivalent-output
  (testing "re-registering a flow with a body that produces the same output keeps the output stable"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-event :tick (fn [{:keys [db]} _] {:db (update db :tick (fnil inc 0))}))
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} (fn [n] (* 2 n)))
    (rf/dispatch-sync [:init])
    (is (= 10 (get-in (rf/app-db-value :rf/default) [:derived :doubled])))
    ;; Re-register with a body that produces the SAME output for the
    ;; current input. Per Spec 013 §Re-registration the next drain
    ;; re-evaluates; the user-visible output stays 10.
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} (fn [n] (+ n n)))
    (rf/dispatch-sync [:tick])
    (is (= 10 (get-in (rf/app-db-value :rf/default) [:derived :doubled]))
        "value-equivalent re-registration leaves the output stable")))

(deftest flow-hot-reload-new-body-recomputes-on-next-drain
  (testing "if the new body would produce a different value, the next drain materialises it"
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
;; 5. Toggle via :rf.fx/reg-flow / :rf.fx/clear-flow
;; ---------------------------------------------------------------------------

(deftest fx-reg-flow-and-clear-flow-round-trip
  (testing ":rf.fx/reg-flow registers; :rf.fx/clear-flow removes; the output path is dissoc'd"
    (rf/reg-event :init  (fn [{:keys [db]} _] {:db {:wizard {:foo 3 :bar 4}}}))
    (rf/reg-event :enter (fn [_ _]
                              {:fx [[:rf.fx/reg-flow [:step-2/computed {:inputs [[:wizard :foo] [:wizard :bar]] :output-path [:wizard :result]} (fn [foo bar] (+ foo bar))]]]}))
    (rf/reg-event :foo!  (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:wizard :foo] v)}))
    (rf/reg-event :leave (fn [_ _]
                              {:fx [[:rf.fx/clear-flow :step-2/computed]]}))
    (rf/reg-event :touch (fn [{:keys [db]} _] {:db db}))   ;; no-op; exists only to drain
    (rf/dispatch-sync [:init])
    (is (nil? (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "no flow yet — :result is unset")
    ;; Register the flow during :enter. Per Spec 013 §Sequencing the flow
    ;; first runs on the NEXT event drain.
    (rf/dispatch-sync [:enter])
    (is (contains? (get (flows/flows-snapshot) :rf/default) :step-2/computed)
        "registry now carries :step-2/computed")
    ;; Drive a drain with a benign event; the flow first-fires here.
    (rf/dispatch-sync [:foo! 5])
    (is (= 9 (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "flow ran on this drain with 5 + 4 = 9")
    ;; Now clear via fx. The registry removal is immediate (never deferred),
    ;; but `:fx` walks AFTER :leave's own flow transform / `:db` install
    ;; (rf2-2qd9wp / Spec 013 §Sequencing — the same one-event lag
    ;; `:rf.fx/reg-flow` already documents in
    ;; `fx-reg-flow-mid-event-lag-and-followup-dispatch-workaround`): the
    ;; app-db vacate is recorded as an abandoned path and dissoc'd from the
    ;; pending `:db` on the NEXT drain, not this one.
    (rf/dispatch-sync [:leave])
    (is (not (contains? (get (flows/flows-snapshot) :rf/default) :step-2/computed))
        "registry slot removed immediately")
    (is (= 9 (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        ":result is UNCHANGED on :leave's own drain — the vacate lags one event, mirroring :rf.fx/reg-flow's lag")
    ;; Drive one more drain to apply the deferred vacate.
    (rf/dispatch-sync [:touch])
    (is (not (contains? (get (rf/app-db-value :rf/default) :wizard) :result))
        ":rf.fx/clear-flow's dissoc-in lands on the NEXT drain")))

(deftest fx-reg-flow-mid-event-lag-and-followup-dispatch-workaround
  ;; Pin the LEAST-OBVIOUS flow behaviour as an explicit contract: a flow
  ;; registered mid-event via `:rf.fx/reg-flow` does NOT
  ;; compute on THAT event's drain (the `:fx` walk runs after the flow
  ;; transform — Spec 013 §Sequencing / §Drain integration), and the
  ;; documented workaround (a follow-up no-op `:dispatch` from the SAME
  ;; handler) materialises the initial value on the dispatched event's
  ;; drain. Locks the lag so a future "synchronous re-walk" change can't
  ;; silently alter it without flipping this test.
  (rf/reg-event :init (fn [{:keys [db]} _] {:db {:wizard {:foo 3 :bar 4}}}))
  ;; Bare register — NO follow-up dispatch. Demonstrates the lag.
  (rf/reg-event :enter-bare
    (fn [_ _]
      {:fx [[:rf.fx/reg-flow [:step-2/computed {:inputs [[:wizard :foo] [:wizard :bar]] :output-path [:wizard :result]} (fn [foo bar] (+ foo bar))]]]}))
  ;; Register + a follow-up no-op `:dispatch` on the same handler — the
  ;; documented "I need the value now" workaround.
  (rf/reg-event :enter-with-settle
    (fn [_ _]
      {:fx [[:rf.fx/reg-flow [:step-2/computed {:inputs [[:wizard :foo] [:wizard :bar]] :output-path [:wizard :result]} (fn [foo bar] (+ foo bar))]]
            [:dispatch [:wizard/settle]]]}))
  (rf/reg-event :wizard/settle (fn [{:keys [db]} _] {:db db}))   ;; no-op; exists only to drain

  (testing "the lag — a mid-event reg-flow does NOT compute on its own drain"
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:enter-bare])
    (is (contains? (get (flows/flows-snapshot) :rf/default) :step-2/computed)
        "flow IS registered after :enter-bare")
    (is (nil? (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "but :result is STILL unset — the flow did not run on the registering event's drain"))

  (testing "the workaround — a follow-up :dispatch materialises the value on the next drain"
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:enter-with-settle])
    (is (= 7 (get-in (rf/app-db-value :rf/default) [:wizard :result]))
        "the :dispatch [:wizard/settle] re-triggered the drain, so the flow computed 3 + 4 = 7")))

;; ---------------------------------------------------------------------------
;; 6. clean-state interaction: the per-frame store is the single source of
;;    truth (rf2-en00bk — the `:flow` registrar slot is RESERVED-but-empty)
;; ---------------------------------------------------------------------------

(deftest flow-store-is-the-single-source-of-truth
  (testing "reg-flow writes ONLY the per-frame `flows` store, never a registrar :flow slot; reset-flows! clears it"
    (rf/reg-flow :one {:inputs [[:a]] :output-path [:slots :one]} identity)
    (rf/reg-flow :two {:inputs [[:a]] :output-path [:slots :two]} identity)
    ;; The flows live in the per-frame store, introspectable via flow-meta-at.
    (is (some? (flows/flow-meta-at :one {:frame :rf/default})))
    (is (some? (flows/flow-meta-at :two {:frame :rf/default})))
    ;; The registrar `:flow` slot is RESERVED-but-empty — never written.
    (is (nil? (registrar/lookup :flow :one))
        ":flow registrar slot is empty — flows own their per-frame store (rf2-en00bk)")
    (is (nil? (registrar/lookup :flow :two)))
    ;; `reset-flows!` (NOT registrar/clear-all!) is what clears the flow store.
    (flows/reset-flows!)
    (is (nil? (flows/flow-meta-at :one {:frame :rf/default})))
    (is (nil? (flows/flow-meta-at :two {:frame :rf/default})))))

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
    (is (some? (get-in (flows/last-inputs-snapshot) [:double :rf/default]))
        "last-inputs has the dirty-check entry before reset")
    (flows/reset-flows!)
    (is (empty? (flows/flows-snapshot))
        "flow registry is empty after reset-flows!")
    (is (empty? (flows/last-inputs-snapshot))
        "last-inputs is ALSO empty after reset-flows! (rf2-mb65w)")))

(deftest reset-flows-allows-re-registration-without-stale-skip
  ;; The footgun guard: re-register the same flow id with the same inputs
  ;; after a `reset-flows!`. A stale `last-inputs` entry surviving the reset
  ;; would =-equal new inputs and the first drain would silently emit
  ;; `:rf.flow/skip` instead of `:rf.flow/computed` — the new body never
  ;; running.
  (testing "after reset-flows! the re-registered flow re-evaluates on next drain"
    (let [calls (atom 0)]
      (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 5}}))
      ;; First registration + drain — populates last-inputs.
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (swap! calls inc) (* 2 n)))
      (rf/dispatch-sync [:init])
      (is (= 1 @calls) "flow body ran on the first drain")
      ;; Reset BOTH atoms via the public reset-flows! — then re-register
      ;; the IDENTICAL flow against the IDENTICAL inputs.
      (flows/reset-flows!)
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (swap! calls inc) (* 2 n)))
      (rf/dispatch-sync [:init])
      (is (= 2 @calls)
          "after reset-flows! the freshly-registered flow evaluates again — last-inputs was cleared so no stale skip"))))

(deftest reset-flows-clears-per-frame-state
  (testing "(flows/reset-flows!) clears the per-frame registry; reg-flow repopulates fresh"
    (rf/reg-flow :one {:inputs [[:a]] :output-path [:slots :one]} identity)
    (is (contains? (get (flows/flows-snapshot) :rf/default) :one))
    (flows/reset-flows!)
    (schemas/clear-schemas-by-frame!)
    (is (empty? (get (flows/flows-snapshot) :rf/default))
        "per-frame map is empty after reset")
    (rf/reg-flow :one {:inputs [[:a]] :output-path [:slots :one]} identity)
    (is (contains? (get (flows/flows-snapshot) :rf/default) :one)
        "re-registration after reset works without raising")))

;; ---------------------------------------------------------------------------
;; 7. clear-flow :frame opt routing — multi-frame registrar-slot retention
;;
;; This deftest pins `clear-flow`'s `:frame` opt routing within the flows
;; artefact's own test alias — three branches in registry.cljc:
;;
;; 1. Frame opt routing: `(clear-flow :foo {:frame :left})` removes the
;;    flow from `:left`'s per-frame map only; sibling frame `:right`'s
;;    identically-named flow stays intact.
;; 2. "Last frame holding id" registrar-slot retention (registry.cljc
;;    line ~254 `not-any?`): when the same flow id is registered against
;;    two frames, clearing it from one frame must NOT unregister the
;;    `:flow` registrar slot — the other frame still needs it for
;;    hot-reload tracking. Clearing from the second frame then unregisters.
;; 3. app-db `dissoc-in` is frame-local: clearing on `:left` only
;;    dissoc-in's `:left`'s app-db; `:right`'s app-db is untouched.
;;
;; Spec 013 §Frame-scoping calls all three properties out normatively.
;; This deftest pins them inside the flows slice's own gate so the
;; artefact doesn't rely on smoke_test.clj catching regressions.
;; ---------------------------------------------------------------------------

(deftest clear-flow-routes-via-frame-opt
  (testing "the same flow id registers independently against two frames"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
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
    (is (contains? (get (flows/flows-snapshot) :left)  :compute)
        ":left's per-frame registry slot carries :compute")
    (is (contains? (get (flows/flows-snapshot) :right) :compute)
        ":right's per-frame registry slot carries :compute"))

  (testing "clear-flow on one frame leaves the sibling frame's registry slot intact"
    ;; Branch 1: per-frame registry routing.
    (flows/clear-flow :compute {:frame :left})
    (is (not (contains? (get (flows/flows-snapshot) :left)  :compute))
        ":left's slot was removed")
    (is (contains? (get (flows/flows-snapshot) :right) :compute)
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

  (testing "single-store (rf2-en00bk): clear on :left leaves :right's per-frame entry intact; no frame-blind slot to realign"
    ;; Under the per-frame single store, :right's entry is authoritative in
    ;; place — there is no shared registrar `:flow` slot to keep aligned. Per-
    ;; frame introspection shows :left cleared, :right still owning the id.
    (is (nil? (flows/flow-meta-at :compute {:frame :left}))
        ":left's per-frame flow entry is gone after the clear")
    (is (some? (flows/flow-meta-at :compute {:frame :right}))
        ":right's per-frame flow entry is intact — it still registers the id")
    (is (nil? (registrar/lookup :flow :compute))
        "the :flow registrar slot is RESERVED-but-empty throughout (rf2-en00bk)"))

  (testing "clearing from the second (last) frame drops the final per-frame entry"
    (flows/clear-flow :compute {:frame :right})
    (is (not (contains? (get (flows/flows-snapshot) :right) :compute))
        ":right's slot is now gone")
    (is (nil? (flows/flow-meta-at :compute {:frame :right}))
        ":right's per-frame flow entry is gone")
    (is (nil? (registrar/lookup :flow :compute))
        "the :flow registrar slot stays empty — single-store, nothing to unregister (rf2-en00bk)")))

;; ---------------------------------------------------------------------------
;; 7b. rf2-8mxnnk — reg-flow / clear-flow normalize a FRAME-VALUE `:frame`
;;
;; The public frame-target contract (EP-0024) admits a frame VALUE
;; (make-frame's return token) EVERYWHERE a frame-id keyword is accepted; the
;; internal normalization seam (`frame/frame-target->id`) funnels a value to
;; its id. The flows READ path (`flow-meta-at`) already normalized; the WRITE
;; paths (`reg-flow` / `clear-flow`) did NOT, so an explicit `{:frame <value>}`
;; keyed the per-frame store by the raw map — a live frame read as not-live at
;; registration, and a clear silently missed the flow. This pins that both
;; write paths normalize, so register-by-value + read-by-id + read-by-value +
;; clear-by-value all observe the SAME flow.
;; ---------------------------------------------------------------------------

(deftest reg-and-clear-flow-normalize-frame-value-target
  (testing "reg-flow with an explicit FRAME-VALUE `:frame` registers the flow
            (does NOT report the live frame as not-live), and flow-meta-at
            observes the SAME flow whether keyed by id or by value"
    (let [frame-val (rf/make-frame {:id :fv/host})]
      (is (frame/frame-value? frame-val)
          "make-frame returns a frame VALUE, not a bare keyword")
      ;; Pre-fix this THREW :rf.error/flow-frame-not-live because the raw
      ;; value keyed the `frame/frame` liveness probe (which keys `@frames`
      ;; by the bare id) and missed the live frame.
      (is (= :area
             (rf/reg-flow :area
                          {:frame       frame-val
                           :inputs      [[:w] [:h]]
                           :output-path [:rect :area]}
                          (fn [w h] (* (or w 0) (or h 0)))))
          "reg-flow against a frame VALUE succeeds (frame normalized to its id)")
      ;; Read by id AND by value resolve the same registered flow.
      (is (some? (flows/flow-meta-at :area {:frame :fv/host}))
          "flow-meta-at by frame-id finds the flow registered via the value")
      (is (some? (flows/flow-meta-at :area {:frame frame-val}))
          "flow-meta-at by frame VALUE finds the same flow")
      (is (= (flows/flow-meta-at :area {:frame :fv/host})
             (flows/flow-meta-at :area {:frame frame-val}))
          "by-id and by-value observe the IDENTICAL flow meta")
      ;; The store is keyed by the normalized id, not the raw value map.
      (is (contains? (get (flows/flows-snapshot) :fv/host) :area)
          "the per-frame store is keyed by the normalized frame-id")
      (is (not (contains? (flows/flows-snapshot) frame-val))
          "the store is NOT keyed by the raw frame-value map"))

    (testing "clear-flow with an explicit FRAME-VALUE `:frame` clears the flow
              registered under the frame-id (does not silently miss it)"
      (let [frame-val (rf/make-frame {:id :fv/host})]  ; idempotent re-make; same id
        (flows/clear-flow :area {:frame frame-val})
        (is (nil? (flows/flow-meta-at :area {:frame :fv/host}))
            "clear-by-value removed the flow observed by id")
        (is (nil? (flows/flow-meta-at :area {:frame frame-val}))
            "clear-by-value removed the flow observed by value")
        (is (not (contains? (get (flows/flows-snapshot) :fv/host) :area))
            "the per-frame store slot is gone after the value-keyed clear")))))

;; ---------------------------------------------------------------------------
;; 8. `_hot-reload-hook` defonce-idempotency on namespace reload
;;
;; The flows registry installs a registrar replacement-hook
;; (`invalidate-flow-on-replace!`) once at namespace load via
;; `(defonce ^:private _hot-reload-hook
;; (registrar/add-replacement-hook! ...))`. A plain `def` would push a
;; duplicate hook into `re-frame.registrar/replacement-hooks` on every
;; namespace reload — and every subsequent flow re-registration would
;; invalidate `last-inputs` twice (functionally harmless because `dissoc` is
;; idempotent, but a silent bookkeeping leak that would compound across many
;; hot-reload cycles in long dev sessions).
;;
;; Pin the idempotency: `(require 're-frame.flows.registry :reload)`
;; MUST NOT push a duplicate hook.
;; ---------------------------------------------------------------------------

(deftest hot-reload-hook-is-defonce-idempotent
  (testing "reloading re-frame.flows.registry does NOT install a duplicate replacement-hook"
    (let [hooks-var (resolve 're-frame.registrar/replacement-hooks)
          before    (count @(deref hooks-var))]
      (require 're-frame.flows.registry :reload)
      (let [after (count @(deref hooks-var))]
        (is (= before after)
            "the hook count is unchanged across a namespace reload — `defonce` guards the install")))))

;; ---------------------------------------------------------------------------
;; 9. Frame-scoping coverage lives in `clear-flow-routes-via-frame-opt`
;; above (registration routing, app-db dissoc, registrar-slot retention,
;; AND the post-clear re-drain check).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; 9a. invalidate-flow-on-replace! is frame-scoped
;;
;; Spec 013 §Re-registration scopes the invalidation to `[frame-id flow-id]`.
;; A replacement hook that wiped every frame's row under the flow id would
;; have a re-registration on frame `:left` clear `:right`'s last-inputs row
;; too, causing unnecessary recompute on `:right`'s next drain and weakening
;; frame isolation.
;; ---------------------------------------------------------------------------

(deftest hot-reload-on-one-frame-does-not-invalidate-sibling-frames-last-inputs
  (testing "Per rf2-jfpf3: re-register :shared on :left; :right's last-inputs row survives"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    ;; Register :shared against both frames with the same shape.
    (rf/reg-flow :shared {:frame :left :inputs [[:n]] :output-path [:result]} (fn [n] (* 2 (or n 0))))
    (rf/reg-flow :shared {:frame :right :inputs [[:n]] :output-path [:result]} (fn [n] (* 100 (or n 0))))
    ;; Drive a drain on each frame so both have last-inputs rows.
    (rf/dispatch-sync [:seed 5] {:frame :left})
    (rf/dispatch-sync [:seed 5] {:frame :right})
    (let [li (flows/last-inputs-snapshot)]
      (is (some? (get-in li [:shared :left]))
          "before re-registration: :left's last-inputs row is populated")
      (is (some? (get-in li [:shared :right]))
          "before re-registration: :right's last-inputs row is populated"))
    ;; Re-register :shared on :left with a NEW body — should invalidate
    ;; :left's row ONLY.
    (rf/reg-flow :shared {:frame :left :inputs [[:n]] :output-path [:result]} (fn [n] (* 7 (or n 0))))
    (let [li (flows/last-inputs-snapshot)]
      (is (nil? (get-in li [:shared :left]))
          "after re-registration on :left: :left's last-inputs row was dropped (re-evaluate on next drain)")
      (is (some? (get-in li [:shared :right]))
          ":right's last-inputs row is PRESERVED — re-registration on :left did not invalidate :right (rf2-jfpf3)"))))

;; ---------------------------------------------------------------------------
;; 9b. SINGLE-STORE (rf2-en00bk): per-frame entries are independent and
;;     authoritative; there is no frame-blind registrar `:flow` slot.
;;
;; The double-store kept a frame-BLIND registrar `:flow` slot carrying the
;; MOST-RECENTLY-REGISTERED frame's metadata, which had to be hand-realigned to
;; a surviving owner on clear / destroy — a workaround forced ONLY because the
;; single slot could not represent FRAME-DIVERGENT-PER-ID flows. With the
;; per-frame `flows` atom as the SOLE store (the rf2-0frdi schemas precedent
;; applied to flows), each frame's entry is authoritative in place: the SAME
;; flow-id registered against two frames returns each frame's OWN divergent
;; definition via `flow-meta-at`, and a clear / destroy on one frame never
;; disturbs the other. The frame-attribution + `:different-fn?` hot-reload
;; signals the realignment used to serve are now driven directly by `reg-flow`
;; (see `flow-hot-reload-different-fn?-reflects-real-body-swap`). The whole
;; realignment cluster of tests is REPLACED by the single-store invariant
;; below.
;; ---------------------------------------------------------------------------

(deftest per-frame-store-keeps-frame-divergent-definitions
  (testing "the SAME flow-id registered against two frames returns each frame's OWN divergent definition via flow-meta-at; no frame-blind registrar slot"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (let [f-left  (fn [n] (* 2 (or n 0)))
          f-right (fn [n] (* 100 (or n 0)))]
      (rf/reg-flow :shared {:frame :left :inputs [[:n]] :output-path [:result-left]} f-left)
      (rf/reg-flow :shared {:frame :right :inputs [[:m]] :output-path [:result-right]} f-right)
      ;; Each frame's flow-meta-at returns its OWN divergent definition — the
      ;; very thing a frame-blind slot could never represent.
      (is (= f-left  (:derive (flows/flow-meta-at :shared {:frame :left})))
          ":left's flow-meta-at carries :left's :derive")
      (is (= f-right (:derive (flows/flow-meta-at :shared {:frame :right})))
          ":right's flow-meta-at carries :right's :derive (divergent, not overwritten)")
      (is (= [:result-left]  (:output-path (flows/flow-meta-at :shared {:frame :left}))))
      (is (= [:result-right] (:output-path (flows/flow-meta-at :shared {:frame :right}))))
      ;; The registrar `:flow` slot is RESERVED-but-empty throughout.
      (is (nil? (registrar/lookup :flow :shared))
          "no frame-blind registrar :flow slot is written (rf2-en00bk)"))))

(deftest clear-flow-of-one-frame-leaves-sibling-authoritative-in-place
  (testing "clearing one frame's entry leaves the sibling's per-frame entry intact and authoritative; no slot to re-point (rf2-en00bk)"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (let [f-left  (fn [n] (* 2 (or n 0)))
          f-right (fn [n] (* 100 (or n 0)))]
      (rf/reg-flow :shared {:frame :left :inputs [[:n]] :output-path [:result]} f-left)
      (rf/reg-flow :shared {:frame :right :inputs [[:n]] :output-path [:result]} f-right)
      ;; Clear :right. :left still holds :shared — its entry is unchanged.
      (flows/clear-flow :shared {:frame :right})
      (is (nil? (flows/flow-meta-at :shared {:frame :right}))
          ":right's per-frame entry is gone")
      (is (= f-left (:derive (flows/flow-meta-at :shared {:frame :left})))
          ":left's entry is intact and authoritative IN PLACE — no realignment needed")
      (is (nil? (registrar/lookup :flow :shared))
          "registrar :flow slot stays empty (rf2-en00bk)"))))

(deftest clear-flow-non-owner-frame-leaves-owner-intact
  (testing "clearing a frame that does NOT register the id leaves the registering frame's entry untouched (rf2-en00bk)"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-flow :shared {:frame :right :inputs [[:n]] :output-path [:result]} (fn [n] n))
    ;; :left never registered :shared — clearing it is a frame-local no-op for
    ;; :right's entry.
    (flows/clear-flow :shared {:frame :left})
    (is (some? (flows/flow-meta-at :shared {:frame :right}))
        ":right's entry survives — the clear on :left could not touch it")
    (is (nil? (flows/flow-meta-at :shared {:frame :left}))
        ":left never held :shared")))

(deftest clear-flow-last-frame-drops-the-final-per-frame-entry
  (testing "clearing the only registering frame drops the final per-frame entry; the registrar slot was empty all along (rf2-en00bk)"
    (rf/reg-flow :solo {:inputs [[:n]] :output-path [:result]} (fn [n] n))
    (is (some? (flows/flow-meta-at :solo {:frame :rf/default})))
    (is (nil? (registrar/lookup :flow :solo))
        "registrar :flow slot is RESERVED-but-empty even while the flow is live")
    (flows/clear-flow :solo)
    (is (nil? (flows/flow-meta-at :solo {:frame :rf/default}))
        "the per-frame entry is gone after the clear")
    (is (nil? (registrar/lookup :flow :solo))
        "registrar :flow slot remains empty — nothing to unregister (rf2-en00bk)")))

;; ---------------------------------------------------------------------------
;; 9b-ii. Same-frame re-registration with a CHANGED :output-path vacates the
;;        old output path from app-db.
;;
;; Re-registering an existing flow-id on the SAME frame with a DIFFERENT
;; :output-path moves the flow's output and vacates the old path. Leaving the
;; previous output path materialised in app-db would let downstream reads see
;; stale derived state at the abandoned slot.
;; ---------------------------------------------------------------------------

(deftest same-frame-reregister-changed-path-vacates-old-path
  (testing "Per rf2-73pi1: re-registering on the same frame with a new :output-path
            clears the OLD path from app-db; the new path computes on the
            next drain"
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-event :tick (fn [{:keys [db]} _] {:db (update db :tick (fnil inc 0))}))
    ;; Register :move at [:old]; drain so [:old] materialises.
    (rf/reg-flow :move {:inputs [[:n]] :output-path [:old]} (fn [n] (* 2 (or n 0))))
    (rf/dispatch-sync [:seed 3])
    (is (= 6 (:old (rf/app-db-value :rf/default)))
        "precondition: :old materialised (3 * 2)")
    ;; Re-register the SAME id on the SAME frame at a DIFFERENT path.
    (rf/reg-flow :move {:inputs [[:n]] :output-path [:new]} (fn [n] (* 3 (or n 0))))
    (is (not (contains? (rf/app-db-value :rf/default) :old))
        ":old was vacated from app-db on the same-frame :output-path change (rf2-73pi1)")
    ;; Drive a drain so the re-registered flow (last-inputs invalidated)
    ;; recomputes at the new path.
    (rf/dispatch-sync [:tick])
    (let [db (rf/app-db-value :rf/default)]
      (is (= 9 (:new db))
          ":new materialised at the moved path (3 * 3) on the next drain")
      (is (not (contains? db :old))
          ":old stays absent — no stale derived state at the abandoned slot"))))

(deftest same-frame-reregister-same-path-leaves-app-db-untouched
  (testing "Per rf2-73pi1: a same-frame re-registration that KEEPS the :output-path
            does NOT vacate the value (negative control — only a :output-path
            CHANGE triggers the vacate)"
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-flow :keep {:inputs [[:n]] :output-path [:out]} (fn [n] (* 2 (or n 0))))
    (rf/dispatch-sync [:seed 4])
    (is (= 8 (:out (rf/app-db-value :rf/default))))
    ;; Re-register on the same frame with a NEW body but the SAME path.
    (rf/reg-flow :keep {:inputs [[:n]] :output-path [:out]} (fn [n] (* 5 (or n 0))))
    (is (= 8 (:out (rf/app-db-value :rf/default)))
        ":out is NOT vacated — same :output-path, so the prior value survives until the next recompute")))

;; ---------------------------------------------------------------------------
;; 9c. :rf.registry/handler-replaced reflects real :flow body swaps and
;;     is suppressed by shape on idempotent reloads.
;;
;; Two layered contracts converge here:
;;
;;   (1) `:handler-fn` is stamped on the flow registry metadata so the
;;       registrar's `:different-fn?` calculation compares the flow body
;;       across re-registrations. `reg-flow` stamps `:handler-fn` alongside
;;       `:derive`, so the cross-kind registrar trace surface (Spec 001) works
;;       for flows too. (Storing the body only under `:derive` would leave both
;;       `:handler-fn` reads nil and `:different-fn?` always `false`.)
;;
;;   (2) Spec 009 B4 hot-reload dedup by shape. The registrar consults the
;;       trace.tooling dedup-by-shape table on every emit: identical shape on
;;       re-register emits ZERO `:rf.registry/handler-replaced` events; a real
;;       body change emits exactly one.
;;
;; Together: identity reload → 0 emits (B4 dedup-suppressed); real
;; `:derive` body swap → 1 emit with `:different-fn? true` (the `:handler-fn`
;; stamp makes the comparison meaningful).
;; ---------------------------------------------------------------------------

(deftest flow-hot-reload-different-fn?-reflects-real-body-swap
  (testing "Per rf2-v5ttb (`:handler-fn` stamping) + rf2-g1b2m B4 dedup-by-shape: a real `:derive` swap emits one `:rf.registry/handler-replaced` with `:different-fn? true`; an identity reload is suppressed (0 emits)."
    (let [captured (atom [])]
      (re-frame.trace/register-listener!
        ::handler-replaced-recorder
        (fn [ev]
          (when (= :rf.registry/handler-replaced (:operation ev))
            (swap! captured conj ev))))
      (try
        (let [body-v1 (fn [n] (* 2 n))]
          (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} body-v1)
          ;; (a) Real body swap — different `:handler-fn` identity, so
          ;; the B4 dedup table sees a shape change and allows the emit.
          ;; Exactly one `:rf.registry/handler-replaced` fires with
          ;; `:different-fn? true` (the `:handler-fn` stamp on the flow
          ;; metadata makes the comparison meaningful).
          (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 100 n)))
          (is (= 1 (count @captured))
              "one :rf.registry/handler-replaced fired for the body-swap registration")
          (is (true? (-> @captured first :tags :different-fn?))
              ":different-fn? true on real body change (rf2-v5ttb fix)")
          ;; (b) Idempotent reload — re-register with the SAME fn
          ;; identity as the previous registration. The B4 dedup table
          ;; has already recorded that shape, so the re-emit is suppressed:
          ;; ZERO `:rf.registry/handler-replaced` events.
          (reset! captured [])
          (let [body-v2 (fn [n] (* 3 n))]
            (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} body-v2)
            ;; First registration of `body-v2` shape is genuine — allow.
            (is (= 1 (count @captured))
                "baseline emit for the new shape so the dedup table records it")
            (reset! captured [])
            ;; Now re-register IDENTICALLY — same fn identity, same
            ;; meta. B4 dedup must suppress.
            (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} body-v2)
            (is (empty? @captured)
                "B4 dedup-by-shape (rf2-g1b2m) suppresses the re-emit for an identity reload — 0 :rf.registry/handler-replaced events")))
        (finally
          (re-frame.trace/unregister-listener! ::handler-replaced-recorder))))))

(deftest flow-hot-reload-invalidates-last-inputs
  (testing "re-registering a flow re-evaluates even when inputs are unchanged"
    (rf/reg-event :init   (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-event :inc-n  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    ;; v1 flow: doubles :n at [:doubled].
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
    (rf/dispatch-sync [:init])
    (is (= 10 (:doubled (rf/app-db-value :rf/default))))
    (rf/dispatch-sync [:inc-n])
    (is (= 12 (:doubled (rf/app-db-value :rf/default))))
    ;; Re-register with a NEW formula. Inputs haven't changed yet — but the
    ;; flow body did, so the next drain should re-evaluate.
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 100 n)))
    ;; Trigger ANY event to drive the drain (no input change).
    (rf/dispatch-sync [:inc-n])
    (is (= 700 (:doubled (rf/app-db-value :rf/default)))
        "after re-registration the flow body re-evaluates on the next drain")))

;; ---------------------------------------------------------------------------
;; 10. Ordering: flows transform the pending `:db` effect as the OUTERMOST
;;     `:after` — after the rest of the `:after` chain reshapes the db, and
;;     BEFORE the `:db` install + BEFORE `:fx` (Spec 013 §Drain integration).
;;
;; These pin the observable consequences of that ordering:
;;   (a) `:fx` sees the flow-derived app-db (preserved guarantee);
;;   (b) the reactive cascade (subs) sees the flow-derived db;
;;   (c) flows run BEFORE the `:db` install (the value installed already
;;       carries flow output — single install of the flow-augmented db);
;;   (d) flows run AFTER the `:after` chain reshape (path-scoped handlers
;;       still feed the FULL db to flows — see
;;       `flow-reads-full-db-under-path-scoped-handler`); and
;;   (e) a user `:after` interceptor — which runs BEFORE the outermost flow
;;       transform — sees the handler's PRE-flow `:db` effect (flow output
;;       reaches it via app-db post-install, not via the chain).
;; ---------------------------------------------------------------------------

(deftest user-after-interceptor-precedes-flow-transform
  (testing "rf2-u0zz5 (e): a user `:after` runs BEFORE the outermost flow transform"
    ;; The flow doubles :n into [:doubled]. A user `:after` interceptor —
    ;; which runs BEFORE the outermost flow transform — captures the
    ;; effects' :db. It must see the handler's write but the PRE-flow
    ;; :doubled value (carried from the prior drain), NOT the freshly
    ;; flow-computed one. The flow output is observable in app-db AFTER
    ;; install — asserted at the end. This pins the deliberate ordering:
    ;; flows are outermost so they read the full reshaped db; user :after
    ;; interceptors precede them.
    (let [seen-db (atom :unset)]
      (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 0}}))
      ;; EP-0022 reference-only: register the capture interceptor, reference by id.
      (rf/reg-interceptor :test/capture-after
        {:after (fn [ctx]
                  (reset! seen-db (get-in ctx [:effects :db]))
                  ctx)})
      (rf/reg-event :set-n
        {:interceptors [:test/capture-after]}
        (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
      ;; init: flow first-computes 0 * 2 = 0 into [:doubled].
      (rf/dispatch-sync [:init])
      (is (= 0 (:doubled (rf/app-db-value :rf/default)))
          "after :init the flow wrote :doubled = 0")
      (reset! seen-db :unset)
      ;; set-n 7: the user :after captures the pending :db effect BEFORE
      ;; the outermost flow transform recomputes :doubled.
      (rf/dispatch-sync [:set-n 7])
      (is (map? @seen-db)
          "the user after-interceptor captured the effects' :db")
      (is (= 7 (:n @seen-db))
          "the user :after saw the handler's own write")
      (is (= 0 (:doubled @seen-db))
          "the user :after saw the PRE-flow :doubled (0, carried from the prior
           drain) — it ran BEFORE the outermost flow transform recomputed it
           (rf2-u0zz5 ordering)")
      ;; The recomputed flow output IS in app-db after install — the
      ;; deliverable path for consumers that need flow output.
      (is (= 14 (:doubled (rf/app-db-value :rf/default)))
          "the recomputed flow output (7 * 2 = 14) landed in the installed app-db"))))

(deftest fx-sees-flow-derived-app-db
  (testing "rf2-u0zz5 (b): an :fx entry reading app-db sees the flow output (preserved)"
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

(deftest reactive-cascade-sees-flow-derived-db
  (testing "rf2-u0zz5 (c): a subscription over the flow's :output-path sees the flow output"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
    (rf/reg-sub :doubled (fn [db _] (:doubled db)))
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:set-n 9])
    (is (= 18 @(rf/subscribe [:doubled]))
        "the sub recomputed against the flow-augmented db install (9 * 2 = 18)")))

(deftest flow-reads-full-db-under-path-scoped-handler
  (testing "rf2-u0zz5: a flow reading a full-db path sees the FULL reshaped db
            even when the triggering handler is `[:rf.interceptor/path …]`-scoped"
    ;; The handler writes the :counter slice (path-scoped). The flow reads
    ;; the FULL-DB path [:counter :n] and writes [:counter :doubled]. The
    ;; flow transform must run against the FULL db (after the path
    ;; interceptor splices the slice back), NOT the bare slice — otherwise
    ;; `(get-in slice [:counter :n])` would be nil and the flow would
    ;; mis-compute.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:counter {:n 0}}}))
    (rf/reg-event :inc
                     {:interceptors [[:rf.interceptor/path [:counter]]]}
                     (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-flow :counter/doubled {:inputs [[:counter :n]] :output-path [:counter :doubled]} (fn [n] (* 2 (or n 0))))
    (rf/dispatch-sync [:seed])
    (rf/dispatch-sync [:inc])
    (let [db (rf/app-db-value :rf/default)]
      (is (= 1 (get-in db [:counter :n]))
          "the path-scoped handler incremented :counter/:n")
      (is (= 2 (get-in db [:counter :doubled]))
          "the flow read the FULL-db [:counter :n] (=1) and wrote 1 * 2 = 2 —
           it ran against the reshaped full db, not the path slice"))
    (rf/dispatch-sync [:inc])
    (let [db (rf/app-db-value :rf/default)]
      (is (= 2 (get-in db [:counter :n])))
      (is (= 4 (get-in db [:counter :doubled]))
          "second increment: flow recomputed 2 * 2 = 4 off the full db"))))

(deftest flow-runs-before-db-install
  (testing "rf2-u0zz5 (c): a single :db install carries the flow output, AND
            the :rf.event/db-changed trace fires once with the flow output"
    ;; Flows transform the pending :db effect, so the cascade performs
    ;; exactly ONE app-db install — of the flow-augmented value. We pin
    ;; this by (1) capturing app-db AT the :rf.event/db-changed trace emit
    ;; (which fires at install) and asserting it already carries the flow
    ;; output, and (2) asserting db-changed fired exactly once (no second
    ;; install from a separate post-install flow mutation, as the prior
    ;; design produced).
    (let [db-at-changed (atom :unset)
          changed-count (atom 0)]
      (re-frame.trace/register-listener!
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
             (the prior design produced a separate post-install flow mutation)")
        (is (= 12 (:doubled @db-at-changed))
            "the db installed at :rf.event/db-changed already carried the flow output —
             flows ran before install (rf2-u0zz5)")
        (finally
          (re-frame.trace/unregister-listener! ::db-changed-recorder))))))
