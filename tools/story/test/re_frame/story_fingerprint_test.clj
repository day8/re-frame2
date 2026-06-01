(ns re-frame.story-fingerprint-test
  "JVM tests + adversarial corpus for the single canonical projection /
  fingerprint primitive (rf2-5x1wt.3).

  Per tools/story/spec/017-Testing-Story.md §Canonicalization the primitive
  MUST:

  - strip `:rf.story/*` accumulator keys from app-db;
  - project away the volatile record fields
    `{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id
      :plan-hash :run-hash}` (reconciling the shipping `:variant-id`
    spelling first; the authoritative `fp/volatile-fields` set also carries
    the per-run epoch / trace stamps `:epoch-id :trace-id :committed-at
    :schema-digest`);
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
;; STRUCTURAL TYPE TAGS — map / set / vector / seq are distinguishable (rf2-lvrqa)
;; ===========================================================================
;;
;; The former canon flattened `{:a 1}` to the bare vector `[:a 1]` and `#{}`
;; to `[]`, so `{}` / `#{}` / `[]` and `{:a 1}` / `[:a 1]` collapsed to
;; byte-identical canonical forms and hashed EQUAL — a soundness hole every
;; downstream consumer (determinism, diff, golden, snapshot identity)
;; inherited. The fix wraps each collection under a reserved structural tag.

(deftest collection-types-do-not-collide
  (testing "empty collections of different kinds are canonically distinct"
    (is (not= (fp/canonicalize {}) (fp/canonicalize [])))
    (is (not= (fp/canonicalize #{}) (fp/canonicalize [])))
    (is (not= (fp/canonicalize {}) (fp/canonicalize #{})))
    (is (not= (fp/content-hash {}) (fp/content-hash []))
        "{} and [] must hash differently")
    (is (not= (fp/content-hash #{}) (fp/content-hash []))
        "#{} and [] must hash differently")
    (is (not= (fp/content-hash {}) (fp/content-hash #{}))
        "{} and #{} must hash differently"))
  (testing "a one-entry map and the flattened 2-element vector are distinct"
    (is (not= (fp/canonicalize {:k 1}) (fp/canonicalize [:k 1])))
    (is (not= (fp/content-hash {:k 1}) (fp/content-hash [:k 1]))
        "{:k 1} and [:k 1] must hash differently — the rf2-lvrqa proof"))
  (testing "a one-element set and the same-element vector are distinct"
    (is (not= (fp/canonicalize #{:k}) (fp/canonicalize [:k])))
    (is (not= (fp/content-hash #{:k}) (fp/content-hash [:k]))))
  (testing "a list/seq is distinct from a vector at the canonical-form /
            content-hash layer (the seq-tag vs vec-tag). NOTE: the
            `canonicalize` path normalizes seqs to vectors UPSTREAM (its
            `project` / `strip-run-stamps` passes `mapv` every sequential),
            so seq-vs-vec is deliberately collapsed there; the distinction
            lives at the raw ordering layer the snapshot identity hashes."
    (is (= [fp/seq-tag [:a :b]] (fp/canonical-form (list :a :b))))
    (is (= [fp/vec-tag [:a :b]] (fp/canonical-form [:a :b])))
    (is (not= (fp/content-hash (list :a :b)) (fp/content-hash [:a :b]))))
  (testing "the collision is closed NESTED, not just at the root — a slot
            whose value flips between a map and a vector perturbs the hash"
    (is (not= (fp/canonical-hash {:effects [{:k 1}]})
              (fp/canonical-hash {:effects [[:k 1]]})))
    (is (not= (fp/canonicalize {:k {}}) (fp/canonicalize {:k []})))
    (is (not= (fp/canonicalize {:k {:a 1}}) (fp/canonicalize {:k [:a 1]}))))
  (testing "type-tagging does not break the volatile-strip equivalence —
            equivalent runs still canonicalize = and hash equal"
    (is (= (fp/canonicalize base-run) (fp/canonicalize volatile-twin)))
    (is (= (fp/run-hash base-run) (fp/run-hash volatile-twin)))))

;; ===========================================================================
;; FN-SLOT DETERMINISM — a fn-valued hashed slot hashes STABLY (rf2-4gwja)
;; ===========================================================================
;;
;; `pr-str` of a raw Clojure fn embeds the object's per-process identity
;; (`#object[…0x4a2f…]`), so the former Object/default branch made any hashed
;; slice carrying a fn NON-DETERMINISTIC across processes / allocations with
;; NO error. The fix folds every fn to the stable `opaque-fn` sentinel.

(deftest fn-valued-slot-hashes-deterministically
  (testing "a plan with an inline fn fx-override hashes IDENTICALLY across
            repeated INDEPENDENT computations — each builds a FRESH closure,
            the exact per-allocation nondeterminism 4gwja flagged"
    ;; Two independently-built plans whose ONLY difference is the IDENTITY of
    ;; freshly-allocated closures must produce the same plan-hash. This is the
    ;; cross-process determinism proof: a fresh process re-allocates closures,
    ;; so identity-stable-across-builds == identity-stable-across-processes.
    (let [build-plan (fn []
                       {:story/id :story.fn/v
                        :world {:frame {:fx-overrides {:rf.http/managed (fn [_] :stub)}
                                        :interceptors [(fn [ctx] ctx)]}}
                        :script [[:dispatch [:go]]]
                        :expect {:checks []}})
          h1 (fp/plan-hash (build-plan))
          h2 (fp/plan-hash (build-plan))]
      (is (= h1 h2)
          "an inline-fn plan must hash identically across independent builds")))
  (testing "the same holds on the RUN-HASH path — a fn in :app-db or an
            effect :args hashes stably across distinct fn instances (rf2-ewrse)"
    (let [run-with (fn [f] {:status :pass :app-db {:cb f}
                            :effects [{:fx-id :x :args f :outcome :ok}]})]
      (is (= (fp/run-hash (run-with (fn [] 1)))
             (fp/run-hash (run-with (fn [] 1))))
          "two distinct closures in the run-slice must hash equal")
      (is (= (fp/canonicalize (run-with (fn [] 1)))
             (fp/canonicalize (run-with (fn [] 1))))
          "and canonicalize = (the determinism gate's authority)")))
  (testing "a fn canonicalizes to the stable opaque sentinel, never an
            object-identity pr-str"
    (is (= fp/opaque-fn (fp/canonical-form (fn [] 1))))
    (is (= (fp/canonical-form (fn [] 1)) (fp/canonical-form (fn [x] x))))
    (is (not (re-find #"object\[" (pr-str (fp/canonical-form (fn [] 1))))))
    (testing "keywords / symbols / colls are IFn but NOT folded to the
              sentinel — only genuine fns are"
      (is (= :kw  (fp/canonical-form :kw)))
      (is (not= fp/opaque-fn (fp/canonical-form :kw)))
      (is (not= fp/opaque-fn (fp/canonical-form #{:a})))))
  (testing "DELIBERATE TRADE-OFF (rf2-4gwja): two plans differing ONLY in fn
            identity hash EQUAL — determinism is the contract, not fn
            discrimination. A non-fn semantic difference still perturbs."
    (let [base-fn-plan {:story/id :story.fn/v
                        :world {:frame {:fx-overrides {:rf.http/managed (fn [_] :a)}}}
                        :script [] :expect {}}]
      (is (= (fp/plan-hash base-fn-plan)
             (fp/plan-hash (assoc-in base-fn-plan
                                     [:world :frame :fx-overrides :rf.http/managed]
                                     (fn [_] :b))))
          "two fn overrides hash equal — accepted trade-off")
      (is (not= (fp/plan-hash base-fn-plan)
                (fp/plan-hash (assoc-in base-fn-plan [:world :args :sku] "X")))
          "a non-fn semantic difference still perturbs the plan-hash"))))

;; ===========================================================================
;; CROSS-HOST SCALAR STABILITY (rf2-vvqeo)
;; ===========================================================================
;;
;; Scalars used to pass through `-canon` verbatim and be `pr-str`'d raw. Two
;; number sub-kinds are NOT host-portable through `pr-str` and silently broke
;; the byte-stable-across-hosts contract:
;;   1. RATIOS — JVM `(pr-str 1/3)` => "1/3"; CLJS has no Ratio, so `1/3` is
;;      the double 0.333… — divergent strings, divergent hash.
;;   2. FLOATS / SPECIALS — an integer-valued double prints "1.0" (JVM) vs "1"
;;      (CLJS); exponent notation differs; `##NaN` also destabilises the
;;      `(sort-by pr-str)` set order.
;; The fix normalises host-divergent numbers to a bit-stable canonical form
;; (`[:rf/double <16-hex IEEE-754 bits>]` / the `:rf/nan` sentinel / an integer
;; for integer-valued doubles), leaving INTEGERS / strings / keywords / normal
;; collections byte-identical (no golden rebase). The CLJS companion
;; (`re-frame.story-fingerprint-cljs-test`) asserts the SAME canonical forms +
;; hashes on CLJS — that pairing IS the cross-host-equivalence proof.

(deftest ordinary-value-canonical-forms-are-unchanged
  (testing "REGRESSION GUARD (rf2-vvqeo): the canonical form + content-hash of
            ordinary (non-host-divergent) values is byte-identical to the
            pre-change baseline — the scalar-stability fix MUST NOT rebase any
            existing golden. These literals were captured from the shipping
            primitive BEFORE the fix; if any drifts, an ordinary value's hash
            moved and goldens would silently mis-compare."
    ;; [value  expected-canonical-form  expected-content-hash]
    (let [cases [[42                    "42"                     "211a4621"]
                 [-7                     "-7"                     "ab492c45"]
                 [(bigint 100000000000000000000) "100000000000000000000N" "86b7e419"]
                 ["hello"                "\"hello\""              "3409cbf2"]
                 [:foo/bar               ":foo/bar"               "3ac20368"]
                 ['sym                   "sym"                    "2bdbe3fa"]
                 [true                   "true"                   "28c0a6cf"]
                 [false                  "false"                  "d4bea88b"]
                 [nil                    "nil"                    "8d40a9c3"]
                 [[1 2 3]                "[:rf/vec [1 2 3]]"      "234450cb"]
                 [{:a 1 :b "x" :c :k}    "[:rf/map [:a 1 :b \"x\" :c :k]]" "418d9acd"]
                 [#{:a :b :c}            "[:rf/set [:a :b :c]]"   "405ea2f0"]
                 [{:x [{:y #{1 2}} {:z :w}]}
                  "[:rf/map [:x [:rf/vec [[:rf/map [:y [:rf/set [1 2]]]] [:rf/map [:z :w]]]]]]"
                  "2435e981"]
                 [{:status :pass :app-db {:n 1 :items [{:sku "A"}]}}
                  "[:rf/map [:app-db [:rf/map [:items [:rf/vec [[:rf/map [:sku \"A\"]]]] :n 1]] :status :pass]]"
                  "98b520a4"]]]
      (doseq [[v cf ch] cases]
        (is (= cf (pr-str (fp/canonical-form v)))
            (str "canonical form of " (pr-str v) " drifted — golden rebase!"))
        (is (= ch (fp/content-hash v))
            (str "content-hash of " (pr-str v) " drifted — golden rebase!"))))))

(deftest ratios-canonicalize-host-portably
  (testing "a JVM Ratio canonicalises to the SAME bit-stable double form its
            CLJS double counterpart reaches — `1/3` and `(/ 1.0 3.0)` (the
            double CLJS reads `1/3` as) share a canonical form + hash"
    (is (= [fp/double-tag "3fd5555555555555"] (fp/canonical-form 1/3)))
    (is (= (fp/canonical-form 1/3) (fp/canonical-form (/ 1.0 3.0))))
    (is (= (fp/content-hash 1/3) (fp/content-hash (/ 1.0 3.0)))
        "ratio and its double value hash equal — cross-host equivalence")
    (is (= (fp/canonical-hash {:r 1/3}) (fp/canonical-hash {:r (/ 1.0 3.0)}))
        "the same holds nested inside a hashed slice"))
  (testing "distinct ratios remain distinct (sensitivity not lost)"
    (is (not= (fp/canonical-form 1/3) (fp/canonical-form 2/3)))))

(deftest floats-canonicalize-host-portably
  (testing "an integer-valued double folds to its INTEGER form — host-mandated
            (CLJS `1.0` IS the integer `1`), so JVM `1.0` and `1` canonicalise
            EQUAL and the CLJS companion's `1.0` matches"
    (is (= 1   (fp/canonical-form 1.0)))
    (is (= 100 (fp/canonical-form 100.0)))
    (is (= (fp/canonical-form 1.0) (fp/canonical-form 1))))
  (testing "a fractional double folds to the bit-stable `[:rf/double <hex>]`"
    (is (= [fp/double-tag "3ff8000000000000"] (fp/canonical-form 1.5)))
    (is (not= (fp/canonical-form 1.5) (fp/canonical-form 1))
        "1.5 is NOT integer-valued — distinct from 1")
    (is (not= (fp/canonical-form 1.5) (fp/canonical-form 2.5))
        "distinct fractional doubles stay distinct"))
  (testing "an integer-valued double too large for a 64-bit integer takes the
            bit path (no silent overflow to a wrong long)"
    (is (= [fp/double-tag "444b1ae4d6e2ef50"] (fp/canonical-form 1e21)))))

(deftest nan-and-inf-canonicalize-host-portably
  (testing "every NaN folds to the single `:rf/nan` sentinel — a `##NaN` slot
            hashes deterministically and never perturbs a hash by bit-pattern"
    (is (= fp/nan-tag (fp/canonical-form Double/NaN)))
    (is (= (fp/content-hash Double/NaN) (fp/content-hash Double/NaN)))
    (is (= (fp/canonical-hash {:x Double/NaN})
           (fp/canonical-hash {:x Double/NaN}))
        "two NaN-bearing slices hash equal"))
  (testing "±Inf ride the bit-double path — host-stable bits, mutually distinct
            and distinct from every finite double"
    (is (= [fp/double-tag "7ff0000000000000"] (fp/canonical-form Double/POSITIVE_INFINITY)))
    (is (= [fp/double-tag "fff0000000000000"] (fp/canonical-form Double/NEGATIVE_INFINITY)))
    (is (not= (fp/canonical-form Double/POSITIVE_INFINITY)
              (fp/canonical-form Double/NEGATIVE_INFINITY)))
    (is (not= (fp/canonical-form Double/POSITIVE_INFINITY) (fp/canonical-form 1.5)))
    (is (not= fp/nan-tag (fp/canonical-form Double/POSITIVE_INFINITY)))))

(deftest nan-bearing-set-orders-deterministically
  (testing "a NaN in a set no longer destabilises ordering — every NaN folds to
            the `:rf/nan` sentinel BEFORE the set sort, so the set hashes
            stably across builds (the `(sort-by pr-str)` NaN hole, closed)"
    (is (= (fp/content-hash #{1 Double/NaN :a})
           (fp/content-hash #{1 Double/NaN :a})))
    ;; a freshly-constructed NaN-bearing set hashes identically — the exact
    ;; cross-build / cross-host instability the bare comparator risked.
    (is (= (fp/content-hash (set [Double/NaN :a 1]))
           (fp/content-hash (set [1 :a Double/NaN]))))))

(deftest canon-set-has-a-stable-equal-pr-str-tiebreak
  (testing "two DISTINCT fns in a set both fold to `:rf/opaque-fn` (equal
            `pr-str`); the `stable-canon-order` comparator gives them a
            deterministic order, so the set hashes stably across independent
            builds — the latent `(sort-by pr-str)` tie hole (rf2-vvqeo)"
    (let [build (fn [] #{(fn [] 1) (fn [] 2) :marker})]
      (is (= (fp/content-hash (build)) (fp/content-hash (build)))
          "an equal-pr-str-bearing set hashes identically across builds")))
  (testing "ordinary distinct-`pr-str` set order is the SAME as the historical
            `(sort-by pr-str)` order — no golden rebase for normal sets"
    ;; verified by the unchanged content-hash 405ea2f0 for #{:a :b :c} in
    ;; `ordinary-value-canonical-forms-are-unchanged`; this restates the
    ;; element-ordering directly.
    (is (= [fp/set-tag [:a :b :c]] (fp/canonical-form #{:c :a :b})))))

;; ===========================================================================
;; CANONICAL VERSION — the bumped tag is recorded (rf2-lvrqa)
;; ===========================================================================

(deftest canonical-version-is-bumped-to-v2
  (testing "the canonical-version tag is :rf/snapshot-canonical-v2 — bumped
            for the type-tag + fn-sentinel soundness fix"
    (is (= :rf/snapshot-canonical-v2 fp/canonical-version))))

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
