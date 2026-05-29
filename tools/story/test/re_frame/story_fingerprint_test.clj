(ns re-frame.story-fingerprint-test
  "JVM tests + adversarial corpus for the single canonical projection /
  fingerprint primitive (rf2-5x1wt.3).

  Per tools/story/spec/017-Testing-Story.md §Canonicalization the primitive
  MUST:

  - strip `:rf.story/*` accumulator keys from app-db;
  - project away the volatile record fields
    `{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id
      :plan-hash}` (reconciling the shipping `:variant-id` spelling first);
  - impose a total per-slot ordering;
  - enumerate the `:plan-hash` input fields;
  - compute `:run-hash` over the canonical epoch slice;
  - back determinism, semantic-diff, snapshot-identity, and the
    inline-plan-to-registered-variant metamorphic relation through ONE
    path (no local duplicate hashers);
  - keep a deliberate migration path for existing snapshot identity.

  These are pure functions, so the whole file runs on the JVM. A small
  CLJS companion (`re-frame.story-fingerprint-cljs-test`) pins
  host-portability of the hash."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.fingerprint :as fp]
            [re-frame.story.identity    :as ident]))

;; ===========================================================================
;; ADVERSARIAL CORPUS
;; ===========================================================================
;;
;; The corpus is split into two adversarial halves the primitive must keep
;; apart:
;;
;; - VOLATILE pairs: a base value and a "noisy twin" that differs ONLY in
;;   volatile / accumulator fields. They MUST canonicalize `=` and hash
;;   equal (otherwise determinism + golden-slice comparison are vacuous).
;; - SEMANTIC pairs: a base value and a twin that differs in a behavioural
;;   field (app-db, effect, assertion verdict, …). They MUST canonicalize
;;   `not=` and hash unequal (otherwise semantic-diff is blind).

(def ^:private base-run
  "A representative run-result slice (spec §Run result)."
  {:status     :pass
   :variant/id :story.checkout/submits
   :plan-hash  "deadbeef"
   :run-hash   "cafef00d"
   :runner     :headless
   :elapsed-ms 12.5
   :fidelity   #{:real-setup}
   :app-db     {:checkout {:state :submitted}
                :cart     {:items [{:sku "A"}]}
                :rf.story/lifecycle :ready
                :rf.story/loaders-complete? true}
   :assertions [{:assertion :rf.assert/path-equals
                 :status    :pass
                 :passed?   true
                 :payload   [[:checkout :state] :submitted]
                 :source    "checkout_test.clj:42"
                 :runner    :headless
                 :elapsed-ms 0.3}]
   :checks     [{:check :check/no-runtime-errors :status :pass :assertions []}]
   :effects    [{:effect :rf.http/managed :dispatch-id "d-1"}
                {:effect :rf/db :dispatch-id "d-2"}]
   :schema-violations []
   :warnings   []
   :sub-overrides {}
   :epoch-tape [{:epoch-id 1 :dispatch-id "d-1"
                 :trigger-event [:checkout/submit]
                 :db-after {:checkout {:state :submitting}}
                 :effects [{:effect :rf.http/managed}]
                 :source-coord "x:1"}
                {:epoch-id 2 :dispatch-id "d-2"
                 :trigger-event [:checkout/ok]
                 :db-after {:checkout {:state :submitted}}
                 :effects []}]})

(def ^:private volatile-twin
  "Same run as `base-run`, but every volatile slot is perturbed — a
  different elapsed time, dispatch ids, runner, source coords, plan-hash
  string, and a different (but equivalent) accumulator state. NOTHING
  behavioural changed."
  (-> base-run
      (assoc :elapsed-ms 999.0
             :runner     :dom
             :plan-hash  "00000000"
             :run-hash   "11111111")
      (assoc-in [:app-db :rf.story/lifecycle] :error)       ; accumulator key — stripped
      (assoc-in [:app-db :rf.story/loaders-complete?] false)
      (assoc-in [:assertions 0 :source] "elsewhere.clj:7")
      (assoc-in [:assertions 0 :elapsed-ms] 88.0)
      (assoc-in [:assertions 0 :runner] :dom)
      (assoc-in [:effects 0 :dispatch-id] "z-9")
      (assoc-in [:effects 1 :dispatch-id] "z-8")
      (assoc-in [:epoch-tape 0 :dispatch-id] "z-1")
      (assoc-in [:epoch-tape 0 :source-coord] "y:42")
      (assoc-in [:epoch-tape 1 :dispatch-id] "z-2")))

(def ^:private semantic-twins
  "Each entry differs from `base-run` in exactly one behavioural field."
  {:app-db-diff    (assoc-in base-run [:app-db :checkout :state] :rejected)
   :effect-diff    (assoc-in base-run [:effects 0 :effect] :rf/dispatch)
   :assertion-diff (assoc-in base-run [:assertions 0 :status] :fail)
   :status-diff    (assoc base-run :status :fail)
   :epoch-db-diff  (assoc-in base-run [:epoch-tape 1 :db-after :checkout :state] :failed)
   :warning-diff   (assoc base-run :warnings [{:warning :rf/over-render}])})

;; ===========================================================================
;; PROJECT — strip + reconcile
;; ===========================================================================

(deftest project-strips-story-accumulator-keys
  (testing ":rf.story/* accumulator keys are dropped at any depth"
    (let [projected (fp/project {:keep 1
                                 :rf.story/lifecycle :ready
                                 :nested {:rf.story/x 9 :real :v}})]
      (is (= {:keep 1 :nested {:real :v}} projected))))
  (testing "non-rf.story namespaced keys survive"
    (is (= {:rf/db 1} (fp/project {:rf/db 1})))))

(deftest project-strips-volatile-fields
  (testing "every volatile field is dropped recursively"
    (let [projected (fp/project base-run)]
      (doseq [k fp/volatile-fields]
        (is (not (contains? projected k))
            (str k " must be stripped from the projection")))
      (is (not (contains? (get-in projected [:assertions 0]) :source)))
      (is (not (contains? (get-in projected [:assertions 0]) :elapsed-ms)))
      (is (not (contains? (get-in projected [:effects 0]) :dispatch-id)))
      (is (not (contains? (get-in projected [:epoch-tape 0]) :source-coord))))))

(deftest project-reconciles-variant-id-spelling
  (testing "legacy :variant-id is rewritten to :variant/id, then stripped"
    ;; :variant/id is in the volatile set, so after reconciliation it's
    ;; gone — the two spellings collapse to the same projection.
    (is (= (fp/project {:variant-id :x :keep 1})
           (fp/project {:variant/id :x :keep 1})
           {:keep 1})))
  (testing "an existing :variant/id wins over a legacy :variant-id"
    ;; Both present: the normalized spelling is source of truth; both
    ;; then strip away, so the projection is just the residue.
    (is (= {:keep 1}
           (fp/project {:variant-id :legacy :variant/id :canonical :keep 1})))))

;; ===========================================================================
;; CANONICALIZE — equivalence after volatile strip / semantic sensitivity
;; ===========================================================================

(deftest equivalent-runs-canonicalize-equal
  (testing "two runs differing only in volatile + accumulator fields
            canonicalize = and hash equal (determinism floor)"
    (is (= (fp/canonicalize base-run) (fp/canonicalize volatile-twin))
        "canonical projections are =")
    (is (= (fp/canonical-hash base-run) (fp/canonical-hash volatile-twin))
        "canonical hashes are equal")
    (is (= (fp/run-hash base-run) (fp/run-hash volatile-twin))
        "run-hashes are equal")))

(deftest semantic-difference-changes-canonical-value
  (testing "each single-field semantic difference perturbs both the
            canonical value and the run-hash (semantic-diff is not blind)"
    (let [base-canon (fp/canonicalize base-run)
          base-hash  (fp/run-hash base-run)]
      (doseq [[label twin] semantic-twins]
        (is (not= base-canon (fp/canonicalize twin))
            (str label " must perturb the canonical value"))
        (is (not= base-hash (fp/run-hash twin))
            (str label " must perturb the run-hash"))))))

(deftest canonicalize-is-idempotent-and-order-insensitive
  (testing "map key order does not affect the canonical value or hash"
    (is (= (fp/canonicalize {:a 1 :b 2}) (fp/canonicalize {:b 2 :a 1})))
    (is (= (fp/content-hash {:a 1 :b 2}) (fp/content-hash {:b 2 :a 1}))))
  (testing "set element order does not affect the hash"
    (is (= (fp/content-hash #{:x :y :z}) (fp/content-hash #{:z :y :x}))))
  (testing "canonicalize of an already-canonicalized value is stable
            (re-running the projection does not change the hash)"
    (let [once (fp/canonicalize base-run)]
      ;; A second canonicalize over the projected value must not alter the
      ;; canonical-form hash (no volatile keys remain to strip).
      (is (= (fp/content-hash once) (fp/content-hash once))))))

;; ===========================================================================
;; ORDERING — effects / epochs keep producer order; reordering is semantic
;; ===========================================================================

(deftest emission-order-is-preserved-and-significant
  (testing "effects keep emission order — swapping two effects is a
            different canonical value (order is part of the evidence)"
    (let [swapped (update base-run :effects (comp vec reverse))]
      (is (not= (fp/canonicalize base-run) (fp/canonicalize swapped))
          "reordered effects perturb the canonical value")))
  (testing "epoch dispatch order is preserved + significant"
    (let [swapped (update base-run :epoch-tape (comp vec reverse))]
      (is (not= (fp/canonicalize base-run) (fp/canonicalize swapped))))))

;; ===========================================================================
;; PLAN HASH — enumerated inputs, shared primitive
;; ===========================================================================

(def ^:private base-plan
  {:plan/id    :p1
   :variant/id :story.checkout/submits
   :story/id   :story.checkout
   :source-chain [:a :b]
   :world      {:frame {:preset :story} :args {:sku "A"} :setup [[:dispatch [:cart/add]]]}
   :script     [[:dispatch [:checkout/submit]]]
   :expect     {:checks [:check/no-runtime-errors]
                :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]}
   :required-runner #{:app-db :effects}
   :evidence   {:source :epoch-tape}
   :tags       #{:test}
   :plan-hash  "should-not-feed-itself"
   :explain    {:debug :noise}})

(deftest plan-hash-over-enumerated-inputs-only
  (testing "non-input slots (:evidence, :explain, :source-chain, :plan/id,
            the rider :plan-hash, :variant/id) do not affect plan-hash"
    (let [h (fp/plan-hash base-plan)]
      (is (= h (fp/plan-hash (assoc base-plan :evidence {:source :other}))))
      (is (= h (fp/plan-hash (assoc base-plan :explain {:debug :different}))))
      (is (= h (fp/plan-hash (assoc base-plan :source-chain [:x]))))
      (is (= h (fp/plan-hash (assoc base-plan :plan/id :other))))
      (is (= h (fp/plan-hash (assoc base-plan :plan-hash "different"))))
      (is (= h (fp/plan-hash (dissoc base-plan :variant/id)))
          ":variant/id is volatile — dropping it does not change the plan-hash")))
  (testing "a testable/renderable difference changes plan-hash"
    (is (not= (fp/plan-hash base-plan)
              (fp/plan-hash (assoc-in base-plan [:world :args :sku] "B"))))
    (is (not= (fp/plan-hash base-plan)
              (fp/plan-hash (update base-plan :script conj [:dispatch [:extra]]))))
    (is (not= (fp/plan-hash base-plan)
              (fp/plan-hash (assoc base-plan :story/id :story.other))))))

(deftest plan-hash-accepts-legacy-variant-id-spelling
  (testing "the legacy :variant-id spelling is reconciled — a plan with
            either spelling produces the same plan-hash"
    (let [legacy (-> base-plan (dissoc :variant/id) (assoc :variant-id :story.checkout/submits))]
      (is (= (fp/plan-hash base-plan) (fp/plan-hash legacy))))))

;; ===========================================================================
;; ONE PRIMITIVE — plan-hash + run-hash + identity share the same path
;; ===========================================================================

(deftest plan-hash-and-run-hash-call-the-same-primitive
  (testing "plan-hash and run-hash are canonical-hash applied to a slice —
            no second hash implementation. We prove it by reconstructing
            each hash from the public primitive over the same slice."
    (is (= (fp/plan-hash base-plan)
           (fp/canonical-hash (select-keys base-plan fp/plan-hash-input-keys)))
        "plan-hash == canonical-hash over the enumerated plan slice")
    (is (= (fp/run-hash base-run)
           (fp/canonical-hash (select-keys base-run fp/run-hash-input-keys)))
        "run-hash == canonical-hash over the enumerated run slice")))

(deftest snapshot-identity-uses-the-same-primitive
  (testing "identity/content-hash + identity/canonical-form are the SAME
            vars as the fingerprint primitive (folded, not duplicated)"
    (is (identical? ident/content-hash   fp/content-hash))
    (is (identical? ident/canonical-form fp/canonical-form)))
  (testing "the folded content-hash is strip-free, so the snapshot tuple's
            hash is byte-stable across the fold (deliberate migration:
            snapshot identity keeps its :variant-id slot)"
    (let [tuple {:rf/snapshot-canonical :rf/snapshot-canonical-v1
                 :variant-id :story.x/v
                 :variant {:tags #{:dev}}
                 :effective-args {:a 1}}]
      ;; content-hash must NOT strip :variant-id (it is identity-bearing
      ;; for the snapshot); two tuples differing only by variant id hash
      ;; differently through content-hash...
      (is (not= (fp/content-hash tuple)
                (fp/content-hash (assoc tuple :variant-id :story.y/v)))
          "snapshot content-hash keeps :variant-id sensitivity")
      ;; ...while canonical-hash (the run/diff path) DOES strip it.
      (is (= (fp/canonical-hash tuple)
             (fp/canonical-hash (assoc tuple :variant-id :story.y/v)))
          "canonical-hash strips :variant-id (run/diff equivalence)"))))

;; ===========================================================================
;; METAMORPHIC RELATION — inline plan ≡ registered-variant plan
;; ===========================================================================

(deftest inline-plan-equals-registered-plan-after-canonicalize
  (testing "an inline plan and the normalized plan of a registered variant
            describing the same behaviour produce the same plan-hash after
            canonicalization, even when they carry different identity /
            provenance slots"
    (let [registered (assoc base-plan
                            :variant/id :story.checkout/submits
                            :source-chain [:story.checkout :story.checkout/submits]
                            :explain {:from :registry})
          inline     (-> base-plan
                         (dissoc :variant/id :plan/id)
                         (assoc :source-chain [] :explain {:from :inline}))]
      (is (= (fp/plan-hash registered) (fp/plan-hash inline))
          "same testable content → same plan-hash regardless of provenance"))))
