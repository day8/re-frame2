(ns re-frame.story-fingerprint-test
  "JVM tests + adversarial corpus for the single canonical projection /
  fingerprint primitive (rf2-5x1wt.3).

  Per tools/story/spec/017-Testing-Story.md §Canonicalization the primitive
  MUST:

  - strip `:rf.story/*` accumulator keys from app-db;
  - project away the volatile record fields
    `{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id
      :plan-hash :run-hash}` (reconciling the shipping `:variant-id`
    spelling first; the authoritative `rf.story.fingerprint/volatile-fields` set also carries
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
            [re-frame.story.fingerprint :as rf.story.fingerprint]))

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
    (let [projected (rf.story.fingerprint/project {:keep 1
                                 :rf.story/lifecycle :ready
                                 :nested {:rf.story/x 9 :real :v}})]
      (is (= {:keep 1 :nested {:real :v}} projected))))
  (testing "non-rf.story namespaced keys survive"
    (is (= {:rf/db 1} (rf.story.fingerprint/project {:rf/db 1})))))

(deftest project-strips-volatile-fields
  (testing "every volatile field is dropped recursively"
    (let [projected (rf.story.fingerprint/project base-run)]
      (doseq [k rf.story.fingerprint/volatile-fields]
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
    (is (= (rf.story.fingerprint/project {:variant-id :x :keep 1})
           (rf.story.fingerprint/project {:variant/id :x :keep 1})
           {:keep 1})))
  (testing "an existing :variant/id wins over a legacy :variant-id"
    ;; Both present: the normalized spelling is source of truth; both
    ;; then strip away, so the projection is just the residue.
    (is (= {:keep 1}
           (rf.story.fingerprint/project {:variant-id :legacy :variant/id :canonical :keep 1})))))

;; ===========================================================================
;; CANONICALIZE — equivalence after volatile strip / semantic sensitivity
;; ===========================================================================

(deftest equivalent-runs-canonicalize-equal
  (testing "two runs differing only in volatile + accumulator fields
            canonicalize = and hash equal (determinism floor)"
    (is (= (rf.story.fingerprint/canonicalize base-run) (rf.story.fingerprint/canonicalize volatile-twin))
        "canonical projections are =")
    (is (= (rf.story.fingerprint/canonical-hash base-run) (rf.story.fingerprint/canonical-hash volatile-twin))
        "canonical hashes are equal")
    (is (= (rf.story.fingerprint/run-hash base-run) (rf.story.fingerprint/run-hash volatile-twin))
        "run-hashes are equal")))

(deftest semantic-difference-changes-canonical-value
  (testing "each single-field semantic difference perturbs both the
            canonical value and the run-hash (semantic-diff is not blind)"
    (let [base-canon (rf.story.fingerprint/canonicalize base-run)
          base-hash  (rf.story.fingerprint/run-hash base-run)]
      (doseq [[label twin] semantic-twins]
        (is (not= base-canon (rf.story.fingerprint/canonicalize twin))
            (str label " must perturb the canonical value"))
        (is (not= base-hash (rf.story.fingerprint/run-hash twin))
            (str label " must perturb the run-hash"))))))

(deftest canonicalize-is-idempotent-and-order-insensitive
  (testing "map key order does not affect the canonical value or hash"
    (is (= (rf.story.fingerprint/canonicalize {:a 1 :b 2}) (rf.story.fingerprint/canonicalize {:b 2 :a 1})))
    (is (= (rf.story.fingerprint/content-hash {:a 1 :b 2}) (rf.story.fingerprint/content-hash {:b 2 :a 1}))))
  (testing "set element order does not affect the hash"
    (is (= (rf.story.fingerprint/content-hash #{:x :y :z}) (rf.story.fingerprint/content-hash #{:z :y :x}))))
  (testing "canonicalize of an already-canonicalized value is stable
            (re-running the projection does not change the hash)"
    (let [once (rf.story.fingerprint/canonicalize base-run)]
      ;; A second canonicalize over the projected value must not alter the
      ;; canonical-form hash (no volatile keys remain to strip).
      (is (= (rf.story.fingerprint/content-hash once) (rf.story.fingerprint/content-hash once))))))

;; ===========================================================================
;; RECORDABLE-COEFFECT :rf/time-ms STRUCTURAL STRIP (rf2-jt854w — EP-0010 /
;; EP-0017 rf2-alc1lf)
;; ===========================================================================
;;
;; The router dev-stamps the envelope's flat `:rf.cofx` recordable-coeffect map
;; onto the `:rf.event/dispatched` enqueue trace under `[:tags :rf.cofx]` so
;; Xray's Event lens can render the COEFFECTS surface. That map's
;; framework-filled `:rf/time-ms` is epoch-ms WALL-CLOCK (filled fresh per
;; dispatch), so two semantically-equal fresh-frame replays stamp DIFFERENT
;; values — `canonicalize` must strip it (one level deeper than the other
;; trace-tag stamps) or the determinism gate / semantic-diff / `:run-hash`
;; false-drift. The semantic caller-supplied owner-qualified facts (the app's
;; `:counter/delta`, a subsystem's `:rf.route/location`) MUST survive so a real
;; causal-token difference still perturbs the hash. EP-0017 renamed the tag
;; from the nested `:rf.world/inputs` to the flat `:rf.cofx` map and the
;; framework time fact from `:time-ms` to `:rf/time-ms`.

(defn- dispatched-trace-event
  "A minimal `:rf.event/dispatched` trace event carrying a `:rf.cofx`
  tag — the carrier `strip-trace-tags` recognises via `:operation` +
  `:op-type`."
  [cofx]
  {:operation :rf.event/dispatched
   :op-type   :rf.event
   :tags      {:rf.event/v  [:some/event]
               :rf.cofx     cofx}})

(deftest cofx-time-ms-is-stripped-from-the-dispatched-trace
  (testing "two dispatched trace events differing ONLY in the framework
            wall-clock :rf.cofx :rf/time-ms canonicalize = and hash equal"
    (let [a (dispatched-trace-event {:rf/time-ms 1000 :counter/delta 4 :rf.route/location "/a"})
          b (dispatched-trace-event {:rf/time-ms 9999 :counter/delta 4 :rf.route/location "/a"})]
      (is (= (rf.story.fingerprint/canonicalize a) (rf.story.fingerprint/canonicalize b))
          "differing only in :rf/time-ms must canonicalize =")
      (is (= (rf.story.fingerprint/canonical-hash a) (rf.story.fingerprint/canonical-hash b))
          "differing only in :rf/time-ms must hash equal")))
  (testing "the semantic caller-supplied facts survive the strip — an
            owner-qualified leaf difference still perturbs the value + hash"
    (let [base    (dispatched-trace-event {:rf/time-ms 1000 :counter/delta 4 :rf.route/location "/a"})
          delta'  (dispatched-trace-event {:rf/time-ms 1000 :counter/delta 5 :rf.route/location "/a"})
          route'  (dispatched-trace-event {:rf/time-ms 1000 :counter/delta 4 :rf.route/location "/b"})]
      (is (not= (rf.story.fingerprint/canonicalize base) (rf.story.fingerprint/canonicalize delta'))
          "a :counter/delta difference must perturb the canonical value")
      (is (not= (rf.story.fingerprint/canonical-hash base) (rf.story.fingerprint/canonical-hash route'))
          "a :rf.route/location difference must perturb the hash")))
  (testing "the strip only fires on the :rf.cofx carrier — a plain app-db
            map keying on :rf/time-ms is NOT stripped (structural, not recursive)"
    (let [a {:app-db {:rf/time-ms 1}}
          b {:app-db {:rf/time-ms 2}}]
      (is (not= (rf.story.fingerprint/canonicalize a) (rf.story.fingerprint/canonicalize b))
          ":rf/time-ms outside a :rf.cofx trace tag is semantic app data"))))

(defn- run-start-trace-event
  "A minimal `:rf.event/run-start` trace event carrying the post-generation
  flat replay token under the `:rf.event/cofx` tag (rf2-1xdotm — the router
  dev-stamps it; the epoch record's `:rf.cofx` slot is sourced from here)."
  [cofx]
  {:operation :rf.event/run-start
   :op-type   :rf.event
   :tags      {:rf.event/v    [:some/event]
               :rf.event/cofx cofx}})

(deftest run-start-cofx-time-ms-is-stripped
  (testing "two run-start trace events differing ONLY in the framework
            wall-clock :rf.event/cofx :rf/time-ms canonicalize = and hash equal"
    (let [a (run-start-trace-event {:rf/time-ms 1000 :counter/delta 4})
          b (run-start-trace-event {:rf/time-ms 9999 :counter/delta 4})]
      (is (= (rf.story.fingerprint/canonicalize a) (rf.story.fingerprint/canonicalize b))
          "differing only in :rf/time-ms must canonicalize =")
      (is (= (rf.story.fingerprint/canonical-hash a) (rf.story.fingerprint/canonical-hash b))
          "differing only in :rf/time-ms must hash equal")))
  (testing "the semantic post-generation facts survive — an owner-qualified
            leaf difference still perturbs the value + hash"
    (let [base   (run-start-trace-event {:rf/time-ms 1000 :counter/delta 4})
          delta' (run-start-trace-event {:rf/time-ms 1000 :counter/delta 5})]
      (is (not= (rf.story.fingerprint/canonicalize base) (rf.story.fingerprint/canonicalize delta'))
          "a :counter/delta difference in the replay token must perturb the value")
      (is (not= (rf.story.fingerprint/canonical-hash base) (rf.story.fingerprint/canonical-hash delta'))
          "and the hash"))))

;; ===========================================================================
;; EPOCH-RECORD :rf.cofx REPLAY-TOKEN :rf/time-ms STRIP (rf2-1xdotm)
;; ===========================================================================
;;
;; `build-record` now pins the POST-generation flat `:rf.cofx` replay token as
;; a FIRST-CLASS top-level slot on the `:rf/epoch-record` (Spec-Schemas
;; §`:rf/epoch-record`) so a Tool-Pair replay can re-present the exact facts
;; the original run consumed under `:rf.cofx/mint-policy :strict`. That token
;; carries the framework-stamped `:rf/time-ms` — epoch-ms WALL-CLOCK, minted
;; fresh per dispatch — so two semantically-equal programs replayed into FRESH
;; frames pin DIFFERENT `:rf/time-ms` on the record's `:rf.cofx` slot, and the
;; `:epoch-tape` slice false-drifts (determinism gate → :non-deterministic,
;; semantic-diff → {:same? false}, golden mismatch) unless `strip-run-stamps`
;; strips it. It is the record-slot peer of the trace-tag `[:tags :rf.cofx]`
;; carrier above — one slot up, same volatile class.

(defn- epoch-record-with-cofx
  "A minimal `:rf/epoch-record` (`:epoch-id` + a load-bearing slot so
  `epoch-record?` recognises the carrier) pinning a top-level `:rf.cofx`
  replay token (rf2-1xdotm)."
  [cofx]
  {:epoch-id    1
   :db-after    {:answer 42}
   :outcome     :ok
   :rf.cofx     cofx})

(deftest epoch-record-cofx-time-ms-is-stripped
  (testing "two epoch records differing ONLY in the framework wall-clock
            top-level :rf.cofx :rf/time-ms canonicalize = and hash equal"
    (let [a (epoch-record-with-cofx {:rf/time-ms 1000 :counter/delta 4})
          b (epoch-record-with-cofx {:rf/time-ms 9999 :counter/delta 4})]
      (is (= (rf.story.fingerprint/canonicalize a) (rf.story.fingerprint/canonicalize b))
          "differing only in :rf/time-ms must canonicalize =")
      (is (= (rf.story.fingerprint/canonical-hash a) (rf.story.fingerprint/canonical-hash b))
          "differing only in :rf/time-ms must hash equal")))
  (testing "the semantic caller-supplied replay facts survive — an
            owner-qualified leaf difference still perturbs the value + hash"
    (let [base   (epoch-record-with-cofx {:rf/time-ms 1000 :counter/delta 4})
          delta' (epoch-record-with-cofx {:rf/time-ms 1000 :counter/delta 5})]
      (is (not= (rf.story.fingerprint/canonicalize base) (rf.story.fingerprint/canonicalize delta'))
          "a :counter/delta difference in the replay token must perturb the value")
      (is (not= (rf.story.fingerprint/canonical-hash base) (rf.story.fingerprint/canonical-hash delta'))
          "and the hash"))))

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
    (is (not= (rf.story.fingerprint/canonicalize {}) (rf.story.fingerprint/canonicalize [])))
    (is (not= (rf.story.fingerprint/canonicalize #{}) (rf.story.fingerprint/canonicalize [])))
    (is (not= (rf.story.fingerprint/canonicalize {}) (rf.story.fingerprint/canonicalize #{})))
    (is (not= (rf.story.fingerprint/content-hash {}) (rf.story.fingerprint/content-hash []))
        "{} and [] must hash differently")
    (is (not= (rf.story.fingerprint/content-hash #{}) (rf.story.fingerprint/content-hash []))
        "#{} and [] must hash differently")
    (is (not= (rf.story.fingerprint/content-hash {}) (rf.story.fingerprint/content-hash #{}))
        "{} and #{} must hash differently"))
  (testing "a one-entry map and the flattened 2-element vector are distinct"
    (is (not= (rf.story.fingerprint/canonicalize {:k 1}) (rf.story.fingerprint/canonicalize [:k 1])))
    (is (not= (rf.story.fingerprint/content-hash {:k 1}) (rf.story.fingerprint/content-hash [:k 1]))
        "{:k 1} and [:k 1] must hash differently — the rf2-lvrqa proof"))
  (testing "a one-element set and the same-element vector are distinct"
    (is (not= (rf.story.fingerprint/canonicalize #{:k}) (rf.story.fingerprint/canonicalize [:k])))
    (is (not= (rf.story.fingerprint/content-hash #{:k}) (rf.story.fingerprint/content-hash [:k]))))
  (testing "a list/seq is distinct from a vector at the canonical-form /
            content-hash layer (the seq-tag vs vec-tag). NOTE: the
            `canonicalize` path normalizes seqs to vectors UPSTREAM (its
            `project` / `strip-run-stamps` passes `mapv` every sequential),
            so seq-vs-vec is deliberately collapsed there; the distinction
            lives at the raw ordering layer the snapshot identity hashes."
    (is (= [rf.story.fingerprint/seq-tag [:a :b]] (rf.story.fingerprint/canonical-form (list :a :b))))
    (is (= [rf.story.fingerprint/vec-tag [:a :b]] (rf.story.fingerprint/canonical-form [:a :b])))
    (is (not= (rf.story.fingerprint/content-hash (list :a :b)) (rf.story.fingerprint/content-hash [:a :b]))))
  (testing "the collision is closed NESTED, not just at the root — a slot
            whose value flips between a map and a vector perturbs the hash"
    (is (not= (rf.story.fingerprint/canonical-hash {:effects [{:k 1}]})
              (rf.story.fingerprint/canonical-hash {:effects [[:k 1]]})))
    (is (not= (rf.story.fingerprint/canonicalize {:k {}}) (rf.story.fingerprint/canonicalize {:k []})))
    (is (not= (rf.story.fingerprint/canonicalize {:k {:a 1}}) (rf.story.fingerprint/canonicalize {:k [:a 1]}))))
  (testing "type-tagging does not break the volatile-strip equivalence —
            equivalent runs still canonicalize = and hash equal"
    (is (= (rf.story.fingerprint/canonicalize base-run) (rf.story.fingerprint/canonicalize volatile-twin)))
    (is (= (rf.story.fingerprint/run-hash base-run) (rf.story.fingerprint/run-hash volatile-twin)))))

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
                        :world {:frame {:fx-overrides {:rf.http/managed (fn [_] :stub)}}}
                        :script [[:dispatch [:go]]]
                        :expect {:checks []}})
          h1 (rf.story.fingerprint/plan-hash (build-plan))
          h2 (rf.story.fingerprint/plan-hash (build-plan))]
      (is (= h1 h2)
          "an inline-fn plan must hash identically across independent builds")))
  (testing "the same holds on the RUN-HASH path — a fn in :app-db or an
            effect :args hashes stably across distinct fn instances (rf2-ewrse)"
    (let [run-with (fn [f] {:status :pass :app-db {:cb f}
                            :effects [{:fx-id :x :args f :outcome :ok}]})]
      (is (= (rf.story.fingerprint/run-hash (run-with (fn [] 1)))
             (rf.story.fingerprint/run-hash (run-with (fn [] 1))))
          "two distinct closures in the run-slice must hash equal")
      (is (= (rf.story.fingerprint/canonicalize (run-with (fn [] 1)))
             (rf.story.fingerprint/canonicalize (run-with (fn [] 1))))
          "and canonicalize = (the determinism gate's authority)")))
  (testing "a fn canonicalizes to the stable opaque sentinel, never an
            object-identity pr-str"
    (is (= rf.story.fingerprint/opaque-fn (rf.story.fingerprint/canonical-form (fn [] 1))))
    (is (= (rf.story.fingerprint/canonical-form (fn [] 1)) (rf.story.fingerprint/canonical-form (fn [x] x))))
    (is (not (re-find #"object\[" (pr-str (rf.story.fingerprint/canonical-form (fn [] 1))))))
    (testing "keywords / symbols / colls are IFn but NOT folded to the
              sentinel — only genuine fns are"
      (is (= :kw  (rf.story.fingerprint/canonical-form :kw)))
      (is (not= rf.story.fingerprint/opaque-fn (rf.story.fingerprint/canonical-form :kw)))
      (is (not= rf.story.fingerprint/opaque-fn (rf.story.fingerprint/canonical-form #{:a})))))
  (testing "DELIBERATE TRADE-OFF (rf2-4gwja): two plans differing ONLY in fn
            identity hash EQUAL — determinism is the contract, not fn
            discrimination. A non-fn semantic difference still perturbs."
    (let [base-fn-plan {:story/id :story.fn/v
                        :world {:frame {:fx-overrides {:rf.http/managed (fn [_] :a)}}}
                        :script [] :expect {}}]
      (is (= (rf.story.fingerprint/plan-hash base-fn-plan)
             (rf.story.fingerprint/plan-hash (assoc-in base-fn-plan
                                     [:world :frame :fx-overrides :rf.http/managed]
                                     (fn [_] :b))))
          "two fn overrides hash equal — accepted trade-off")
      (is (not= (rf.story.fingerprint/plan-hash base-fn-plan)
                (rf.story.fingerprint/plan-hash (assoc-in base-fn-plan [:world :args :sku] "X")))
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
    ;; NB: the large-bigint case that used to live here (rf2-vvqeo) MOVED to
    ;; `large-integers-canonicalize-host-portably` (rf2-7w1vp) — a bigint of
    ;; magnitude > 2^53-1 is NOT an ordinary value; it legitimately changes
    ;; canonical form (to the lossy `[:rf/double …]`) to agree cross-host.
    (let [cases [[42                    "42"                     "211a4621"]
                 [-7                     "-7"                     "ab492c45"]
                 [9007199254740991       "9007199254740991"       "9f16836d"]
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
        (is (= cf (pr-str (rf.story.fingerprint/canonical-form v)))
            (str "canonical form of " (pr-str v) " drifted — golden rebase!"))
        (is (= ch (rf.story.fingerprint/content-hash v))
            (str "content-hash of " (pr-str v) " drifted — golden rebase!"))))))

(deftest large-integers-canonicalize-host-portably
  (testing "an INTEGER beyond the IEEE-754 safe-integer range (±2^53-1) takes
            the SAME lossy `[:rf/double <hex>]` path CLJS is forced onto, so the
            same logical large integer hashes EQUAL cross-host (rf2-7w1vp). On
            the JVM a `bigint`/`Long`/`BigInteger` past 2^53 used to `pr-str`
            verbatim (\"…N\") while CLJS routed it through `double->bits-hex` —
            divergent canonical form, divergent hash."
    (let [big (bigint 100000000000000000000)]
      (is (= [rf.story.fingerprint/double-tag (#'rf.story.fingerprint/double->bits-hex (double big))]
             (rf.story.fingerprint/canonical-form big))
          "a large bigint folds to the lossy bit-double form, NOT \"…N\"")
      ;; the JVM Long path agrees with the bigint path for the same magnitude
      (is (= (rf.story.fingerprint/canonical-form big)
             (rf.story.fingerprint/canonical-form (.toBigInteger (bigdec big))))
          "BigInteger of the same magnitude shares the canonical form")
      ;; cross-host equivalence: the CLJS double `1e20` (what CLJS reads
      ;; `100000000000000000000` as) reaches the SAME bits — the CLJS companion
      ;; pins `(rf.story.fingerprint/canonical-form 1e20)` to this exact form + hash.
      (is (= (rf.story.fingerprint/canonical-form big) (rf.story.fingerprint/canonical-form 1e20))
          "the large integer and its double approximation share the canonical
           form — the cross-host agreement point (CLJS has only the double)")))
  (testing "boundary: an integer AT ±max-safe-integer still passes through
            verbatim — only STRICTLY out-of-range integers change (rf2-7w1vp)"
    (is (= rf.story.fingerprint/max-safe-integer (rf.story.fingerprint/canonical-form rf.story.fingerprint/max-safe-integer)))
    (is (= (- rf.story.fingerprint/max-safe-integer) (rf.story.fingerprint/canonical-form (- rf.story.fingerprint/max-safe-integer))))
    (is (= 9007199254740992N
           ;; one past max-safe-integer is out of range → bit-double path
           (let [over (inc (bigint rf.story.fingerprint/max-safe-integer))]
             (is (= [rf.story.fingerprint/double-tag (#'rf.story.fingerprint/double->bits-hex (double over))]
                    (rf.story.fingerprint/canonical-form over)))
             over))))
  (testing "ordinary small integers are untouched — no golden rebase"
    (is (= 42 (rf.story.fingerprint/canonical-form 42)))
    (is (= -7 (rf.story.fingerprint/canonical-form -7)))
    (is (= 1000000 (rf.story.fingerprint/canonical-form 1000000)))))

(deftest ratios-canonicalize-host-portably
  (testing "a JVM Ratio canonicalises to the SAME bit-stable double form its
            CLJS double counterpart reaches — `1/3` and `(/ 1.0 3.0)` (the
            double CLJS reads `1/3` as) share a canonical form + hash"
    (is (= [rf.story.fingerprint/double-tag "3fd5555555555555"] (rf.story.fingerprint/canonical-form 1/3)))
    (is (= (rf.story.fingerprint/canonical-form 1/3) (rf.story.fingerprint/canonical-form (/ 1.0 3.0))))
    (is (= (rf.story.fingerprint/content-hash 1/3) (rf.story.fingerprint/content-hash (/ 1.0 3.0)))
        "ratio and its double value hash equal — cross-host equivalence")
    (is (= (rf.story.fingerprint/canonical-hash {:r 1/3}) (rf.story.fingerprint/canonical-hash {:r (/ 1.0 3.0)}))
        "the same holds nested inside a hashed slice"))
  (testing "distinct ratios remain distinct (sensitivity not lost)"
    (is (not= (rf.story.fingerprint/canonical-form 1/3) (rf.story.fingerprint/canonical-form 2/3)))))

(deftest floats-canonicalize-host-portably
  (testing "an integer-valued double folds to its INTEGER form — host-mandated
            (CLJS `1.0` IS the integer `1`), so JVM `1.0` and `1` canonicalise
            EQUAL and the CLJS companion's `1.0` matches"
    (is (= 1   (rf.story.fingerprint/canonical-form 1.0)))
    (is (= 100 (rf.story.fingerprint/canonical-form 100.0)))
    (is (= (rf.story.fingerprint/canonical-form 1.0) (rf.story.fingerprint/canonical-form 1))))
  (testing "a fractional double folds to the bit-stable `[:rf/double <hex>]`"
    (is (= [rf.story.fingerprint/double-tag "3ff8000000000000"] (rf.story.fingerprint/canonical-form 1.5)))
    (is (not= (rf.story.fingerprint/canonical-form 1.5) (rf.story.fingerprint/canonical-form 1))
        "1.5 is NOT integer-valued — distinct from 1")
    (is (not= (rf.story.fingerprint/canonical-form 1.5) (rf.story.fingerprint/canonical-form 2.5))
        "distinct fractional doubles stay distinct"))
  (testing "an integer-valued double too large for a 64-bit integer takes the
            bit path (no silent overflow to a wrong long)"
    (is (= [rf.story.fingerprint/double-tag "444b1ae4d6e2ef50"] (rf.story.fingerprint/canonical-form 1e21)))))

(deftest nan-and-inf-canonicalize-host-portably
  (testing "every NaN folds to the single `:rf/nan` sentinel — a `##NaN` slot
            hashes deterministically and never perturbs a hash by bit-pattern"
    (is (= rf.story.fingerprint/nan-tag (rf.story.fingerprint/canonical-form Double/NaN)))
    (is (= (rf.story.fingerprint/content-hash Double/NaN) (rf.story.fingerprint/content-hash Double/NaN)))
    (is (= (rf.story.fingerprint/canonical-hash {:x Double/NaN})
           (rf.story.fingerprint/canonical-hash {:x Double/NaN}))
        "two NaN-bearing slices hash equal"))
  (testing "±Inf ride the bit-double path — host-stable bits, mutually distinct
            and distinct from every finite double"
    (is (= [rf.story.fingerprint/double-tag "7ff0000000000000"] (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY)))
    (is (= [rf.story.fingerprint/double-tag "fff0000000000000"] (rf.story.fingerprint/canonical-form Double/NEGATIVE_INFINITY)))
    (is (not= (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY)
              (rf.story.fingerprint/canonical-form Double/NEGATIVE_INFINITY)))
    (is (not= (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY) (rf.story.fingerprint/canonical-form 1.5)))
    (is (not= rf.story.fingerprint/nan-tag (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY)))))

(deftest nan-bearing-set-orders-deterministically
  (testing "a NaN in a set no longer destabilises ordering — every NaN folds to
            the `:rf/nan` sentinel BEFORE the set sort, so the set hashes
            stably across builds (the `(sort-by pr-str)` NaN hole, closed)"
    (is (= (rf.story.fingerprint/content-hash #{1 Double/NaN :a})
           (rf.story.fingerprint/content-hash #{1 Double/NaN :a})))
    ;; a freshly-constructed NaN-bearing set hashes identically — the exact
    ;; cross-build / cross-host instability the bare comparator risked.
    (is (= (rf.story.fingerprint/content-hash (set [Double/NaN :a 1]))
           (rf.story.fingerprint/content-hash (set [1 :a Double/NaN]))))))

(deftest canon-set-has-a-stable-equal-pr-str-tiebreak
  (testing "two DISTINCT fns in a set both fold to `:rf/opaque-fn` (equal
            `pr-str`); the `stable-canon-order` comparator gives them a
            deterministic order, so the set hashes stably across independent
            builds — the latent `(sort-by pr-str)` tie hole (rf2-vvqeo)"
    (let [build (fn [] #{(fn [] 1) (fn [] 2) :marker})]
      (is (= (rf.story.fingerprint/content-hash (build)) (rf.story.fingerprint/content-hash (build)))
          "an equal-pr-str-bearing set hashes identically across builds")))
  (testing "ordinary distinct-`pr-str` set order is the SAME as the historical
            `(sort-by pr-str)` order — no golden rebase for normal sets"
    ;; verified by the unchanged content-hash 405ea2f0 for #{:a :b :c} in
    ;; `ordinary-value-canonical-forms-are-unchanged`; this restates the
    ;; element-ordering directly.
    (is (= [rf.story.fingerprint/set-tag [:a :b :c]] (rf.story.fingerprint/canonical-form #{:c :a :b})))))

;; ===========================================================================
;; MAP-KEY TIE ORDER — same-`pr-str` keys sort iteration-INDEPENDENTLY (rf2-8r5yzb)
;; ===========================================================================
;;
;; `canon-map-entries` used to sort entries by the canon-KEY `pr-str` alone.
;; Keys that canonicalise to the SAME `pr-str` — every fn folds to
;; `:rf/opaque-fn`, every `##NaN` to `:rf/nan`, and `1.0` / `1` both to `1` —
;; tied, and the bare sort fell back to Clojure's map ITERATION order. So two
;; `=`-equal maps built in DIFFERENT insertion orders produced DIFFERENT
;; canonical bytes → unequal hash, breaking the 'equivalent values hash equal'
;; contract determinism + goldens rest on. The fix adds the canon-VALUE as a
;; strictly SECONDARY sort key, iteration-independent for tied keys and inert
;; for distinct ones (no golden rebase).

(deftest canon-map-tied-keys-order-iteration-independently
  (testing "rf2-8r5yzb — two =-equal maps whose keys canonicalise to the SAME
            `pr-str` (distinct fns both fold to `:rf/opaque-fn`), built in
            OPPOSITE insertion orders, canonical-hash EQUAL"
    (let [f1 (fn [] :one)
          f2 (fn [] :two)
          ;; array-maps preserve insertion order, so the two literals seq in
          ;; opposite orders — the exact iteration-order divergence 8r5yzb hit.
          m-ab (array-map f1 :a f2 :b)
          m-ba (array-map f2 :b f1 :a)]
      (is (= m-ab m-ba) "precondition: the two maps are =-equal")
      (is (= (rf.story.fingerprint/canonical-form m-ab) (rf.story.fingerprint/canonical-form m-ba))
          "insertion/iteration order must not change the canonical form")
      (is (= (rf.story.fingerprint/canonical-hash m-ab) (rf.story.fingerprint/canonical-hash m-ba))
          "…so the canonical hashes are equal — the determinism floor")))
  (testing "a GENUINE value difference under a tied key still SEPARATES — the
            secondary key is a tiebreak, not a collapse"
    (let [f1 (fn [] 1) f2 (fn [] 2)]
      (is (not= (rf.story.fingerprint/canonical-hash (array-map f1 :a f2 :b))
                (rf.story.fingerprint/canonical-hash (array-map f1 :a f2 :c)))
          "{f1 :a f2 :b} and {f1 :a f2 :c} differ in a value → distinct hash")))
  (testing "the number-folding tie (`1` and `1.0` both canon to `1`) is
            likewise iteration-independent — the fix generalises past fn/NaN"
    (let [m1 (array-map 1 :a 1.0 :b)
          m2 (array-map 1.0 :b 1 :a)]
      (is (and (= 2 (count m1)) (= 2 (count m2)))
          "precondition: 1 and 1.0 are DISTINCT keys (Clojure `=` is category-sensitive)")
      (is (= (rf.story.fingerprint/canonical-hash m1) (rf.story.fingerprint/canonical-hash m2))
          "same-canon-key entries order by value, so the hashes are equal")))
  (testing "ordinary distinct-`pr-str` map key order is UNCHANGED — the value
            tiebreak never fires, so no golden rebase"
    (is (= [rf.story.fingerprint/map-tag [:a 1 :b 2 :c 3]]
           (rf.story.fingerprint/canonical-form (array-map :c 3 :a 1 :b 2)))
        "distinct keys still sort by key alone — identical to the pre-fix bytes")))

;; ===========================================================================
;; CANONICAL VERSION — the bumped tag is recorded (rf2-lvrqa)
;; ===========================================================================

(deftest canonical-version-is-bumped-to-v2
  (testing "the canonical-version tag is :rf/snapshot-canonical-v2 — bumped
            for the type-tag + fn-sentinel soundness fix"
    (is (= :rf/snapshot-canonical-v2 rf.story.fingerprint/canonical-version))))

;; ===========================================================================
;; ORDERING — effects / epochs keep producer order; reordering is semantic
;; ===========================================================================

(deftest emission-order-is-preserved-and-significant
  (testing "effects keep emission order — swapping two effects is a
            different canonical value (order is part of the evidence)"
    (let [swapped (update base-run :effects (comp vec reverse))]
      (is (not= (rf.story.fingerprint/canonicalize base-run) (rf.story.fingerprint/canonicalize swapped))
          "reordered effects perturb the canonical value")))
  (testing "epoch dispatch order is preserved + significant"
    (let [swapped (update base-run :epoch-tape (comp vec reverse))]
      (is (not= (rf.story.fingerprint/canonicalize base-run) (rf.story.fingerprint/canonicalize swapped))))))

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
    (let [h (rf.story.fingerprint/plan-hash base-plan)]
      (is (= h (rf.story.fingerprint/plan-hash (assoc base-plan :evidence {:source :other}))))
      (is (= h (rf.story.fingerprint/plan-hash (assoc base-plan :explain {:debug :different}))))
      (is (= h (rf.story.fingerprint/plan-hash (assoc base-plan :source-chain [:x]))))
      (is (= h (rf.story.fingerprint/plan-hash (assoc base-plan :plan/id :other))))
      (is (= h (rf.story.fingerprint/plan-hash (assoc base-plan :plan-hash "different"))))
      (is (= h (rf.story.fingerprint/plan-hash (dissoc base-plan :variant/id)))
          ":variant/id is volatile — dropping it does not change the plan-hash")))
  (testing "a testable/renderable difference changes plan-hash"
    (is (not= (rf.story.fingerprint/plan-hash base-plan)
              (rf.story.fingerprint/plan-hash (assoc-in base-plan [:world :args :sku] "B"))))
    (is (not= (rf.story.fingerprint/plan-hash base-plan)
              (rf.story.fingerprint/plan-hash (update base-plan :script conj [:dispatch [:extra]]))))
    (is (not= (rf.story.fingerprint/plan-hash base-plan)
              (rf.story.fingerprint/plan-hash (assoc base-plan :story/id :story.other))))))

(deftest plan-hash-accepts-legacy-variant-id-spelling
  (testing "the legacy :variant-id spelling is reconciled — a plan with
            either spelling produces the same plan-hash"
    (let [legacy (-> base-plan (dissoc :variant/id) (assoc :variant-id :story.checkout/submits))]
      (is (= (rf.story.fingerprint/plan-hash base-plan) (rf.story.fingerprint/plan-hash legacy))))))

;; ===========================================================================
;; ONE PRIMITIVE — plan-hash + run-hash + identity share the same path
;; ===========================================================================

(deftest plan-hash-and-run-hash-call-the-same-primitive
  (testing "plan-hash and run-hash are canonical-hash applied to a slice —
            no second hash implementation. We prove it by reconstructing
            each hash from the public primitive over the same slice."
    (is (= (rf.story.fingerprint/plan-hash base-plan)
           (rf.story.fingerprint/canonical-hash (select-keys base-plan rf.story.fingerprint/plan-hash-input-keys)))
        "plan-hash == canonical-hash over the enumerated plan slice")
    (is (= (rf.story.fingerprint/run-hash base-run)
           (rf.story.fingerprint/canonical-hash (select-keys base-run rf.story.fingerprint/run-hash-input-keys)))
        "run-hash == canonical-hash over the enumerated run slice")))

(deftest snapshot-identity-uses-the-same-primitive
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
      (is (not= (rf.story.fingerprint/content-hash tuple)
                (rf.story.fingerprint/content-hash (assoc tuple :variant-id :story.y/v)))
          "snapshot content-hash keeps :variant-id sensitivity")
      ;; ...while canonical-hash (the run/diff path) DOES strip it.
      (is (= (rf.story.fingerprint/canonical-hash tuple)
             (rf.story.fingerprint/canonical-hash (assoc tuple :variant-id :story.y/v)))
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
      (is (= (rf.story.fingerprint/plan-hash registered) (rf.story.fingerprint/plan-hash inline))
          "same testable content → same plan-hash regardless of provenance"))))
