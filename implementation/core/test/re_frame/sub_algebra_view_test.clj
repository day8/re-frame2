(ns re-frame.sub-algebra-view-test
  "Tests for the STATIC derivation/process algebra view of subscriptions
  (EP-0014 slice-2, rf2-gge6mf). Per [spec/Derivations.md] (graduated from
  EP-0014) and the `:rf/derivation-node` shape in [spec/Spec-Schemas.md].

  `re-frame.subs.tooling/sub-algebra-view` lowers every registered
  subscription — a layer-1 `:db` reader, a static declared-input sub, a parametric
  input-fn sub, the framework `reg-runtime-sub`, and the framework
  `reg-frame-state-sub` — into the normalized algebra node every declared
  fact/process shares: a `:derivation` whose ephemeral output fact is
  evaluated `:on-demand` and owned by its `:subscription-cache-entry`.

  These tests pin the registrar-derived projection: the fixed
  classifications (`:kind` / `:storage` / `:evaluation` / `:lifecycle` /
  `:materialized?`), the per-kind declared-input lowering, the output fact
  id, the source-form metadata, the opaque `:derive` body token, and the
  source coordinates. The live cache-entry view (the realized parametric
  edges) is the CLJS counterpart, pinned in the reagent runtime suite.

  Slice-2 ships NO public accessor (EP-0014 issue-1 disposition): the view
  lives in the bundle-isolated `re-frame.subs.tooling` sibling, reached on
  JVM through the `re-frame.subs/sub-algebra-view` convenience alias, and
  consumed by Xray + the conformance fixtures. There is no
  `re-frame.core/sub-algebra-view` public facade export.

  ## Posture split (rf2-d2841)

  The ALGEBRA is posture-independent and asserted without a guard: the fixed
  classifications, the per-kind declared-input lowering, the output fact id,
  the `:derive` token, `:schema`, and the registry semantics all run under
  `scripts/test-core-prod-gate.sh` as well as `clojure -M:test`. This is the
  same shape `sub-topology-test` took in the second rf2-d2841 pass — the
  topology around the metadata is production-real; only the REFLECTION
  METADATA on it is not.

  Two slots are reflection metadata and elided in production: the auto-
  captured `:source` coords (`:ns` / `:line` / `:file`, suppressed by
  `source-coords/merge-coords` under the gate) and `:doc` (stripped at the
  `rf.registrar/register!` chokepoint). Their assertions are kept verbatim
  inside `(when rf.interop/debug-enabled? …)` arms marked `rf2-d2841` —
  including the negative in `no-schema-or-doc-when-absent`, which under the
  gate would pass because `:doc` is elided WHOLESALE rather than because this
  registration omitted it. `:schema` is load-bearing and stays outside the
  arm in both deftests, which is what keeps that pair honest."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; The five fixed classifications EVERY subscription algebra node carries
;; (Derivations §Worked equivalence — the subscription is the canonical
;; ephemeral / on-demand / cache-entry member of the algebra).
(def fixed-classifications
  {:kind          :derivation
   :storage       :ephemeral
   :evaluation    :on-demand
   :lifecycle     :subscription-cache-entry
   :materialized? false})

(defn- has-fixed-classifications? [node]
  (= fixed-classifications (select-keys node (keys fixed-classifications))))

;; ---- empty / shape contract ----------------------------------------------

(deftest empty-registry-returns-empty-map
  (testing "(sub-algebra-view) returns {} (not nil) when no subs are registered"
    (rf.registrar/clear-all!)
    (is (= {} (rf.subs.tooling/sub-algebra-view)))
    (is (map? (rf.subs.tooling/sub-algebra-view)))))

(deftest jvm-alias-mirrors-the-tooling-fn
  (testing "the JVM `re-frame.subs/sub-algebra-view` alias is the tooling fn"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (is (= (rf.subs.tooling/sub-algebra-view)
           (rf.subs/sub-algebra-view))
        "the JVM convenience alias projects identically to the tooling sibling")))

;; ---- reg-sub :db (layer-1) -----------------------------------------------

(deftest layer-1-sub-exposes-its-algebra-view
  (testing "a layer-1 reg-sub exposes the full ephemeral / on-demand / cache-entry node"
    (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
    (let [node ((rf.subs.tooling/sub-algebra-view) :cart/items)]
      (is (some? node))
      (is (has-fixed-classifications? node)
          "layer-1 sub carries the fixed derivation / ephemeral / on-demand / cache-entry classifications")
      (is (= :cart/items (:id node)))
      (is (= {:kind :reg-sub :id :cart/items} (:source-form node)))
      (is (= [:fact :cart/items] (:output node))
          "the output is an ephemeral fact addressed by the sub id")
      (is (= [[:db []]] (:inputs node))
          "a layer-1 reader hands the WHOLE app-db to the body — conservative declared input is [:db []]")
      (is (not (contains? node :input-producer))
          "a :db reader has no input producer")
      (is (fn? (:derive node))
          "the body fn is surfaced as an opaque :derive token"))))

(deftest single-arity-returns-one-node
  (testing "(sub-algebra-view id) returns the single node, nil when unregistered"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (is (= ((rf.subs.tooling/sub-algebra-view) :n)
           (rf.subs.tooling/sub-algebra-view :n))
        "the one-arity form equals the entry in the all-subs map")
    (is (nil? (rf.subs.tooling/sub-algebra-view :ghost))
        "an unregistered sub id projects to nil")))

;; ---- reg-sub static inputs --------------------------------------------------

(deftest static-sub-lowers-each-input-to-a-sub-edge
  (testing "a static declared-input sub lowers each literal input query-vector to a [:sub q] declared input"
    (rf/reg-sub :cart/items      (fn [db _] (:items db)))
    (rf/reg-sub :pricing/discounts (fn [db _] (:discounts db)))
    (rf/reg-sub :cart/total
                {:inputs [[:cart/items] [:pricing/discounts]]}
                (fn [[items discounts] _] [items discounts]))
    (let [node ((rf.subs.tooling/sub-algebra-view) :cart/total)]
      (is (has-fixed-classifications? node))
      (is (= [:fact :cart/total] (:output node)))
      (is (= [[:sub [:cart/items]]
              [:sub [:pricing/discounts]]]
             (:inputs node))
          "each declared input is a [:sub query-vector] declared input, in declaration order")
      (is (not (contains? node :input-producer))))))

(deftest static-sub-preserves-query-vector-args
  (testing "static declared inputs preserve the full declared query-vector args"
    (rf/reg-sub :upstream (fn [db [_ _arg]] (:n db)))
    (rf/reg-sub :downstream {:inputs [[:upstream :some-arg]]} (fn [[u] _] u))
    (is (= [[:sub [:upstream :some-arg]]]
           (:inputs ((rf.subs.tooling/sub-algebra-view) :downstream)))
        "the declared input carries the full declared query-vector, args and all")))

;; ---- reg-sub parametric input-fn -----------------------------------------

(deftest parametric-sub-reports-the-parametric-marker
  (testing "a parametric input-fn sub reports the :parametric inputs marker + an opaque :input-producer"
    ;; The realized edge set depends on the concrete outer query-v and is
    ;; NOT statically enumerable — the static graph reports the :parametric
    ;; marker (the don't-execute rule, Derivations §Static and live graphs);
    ;; the live cache view reports the realized [:sub …] edges.
    (rf/reg-sub :article/by-id        (fn [db [_ id]] (get-in db [:articles id])))
    (rf/reg-sub :comments/for-article (fn [db [_ id]] (get-in db [:comments id])))
    (rf/reg-sub :article/page
                {:inputs (fn [[_ id]]
                  [[:article/by-id id]
                   [:comments/for-article id]])}
                (fn [[article comments] [_ id]]
                  {:id id :article article :comments comments}))
    (let [node ((rf.subs.tooling/sub-algebra-view) :article/page)]
      (is (has-fixed-classifications? node))
      (is (= [:fact :article/page] (:output node)))
      (is (= :parametric (:inputs node))
          "the static graph reports the :parametric marker, never fabricated edges")
      (is (not (vector? (:inputs node)))
          "the parametric edge set must NOT be presented as a static vector")
      (is (fn? (:input-producer node))
          "a parametric node surfaces the input-fn as an opaque :input-producer token"))))

;; ---- reg-runtime-sub -----------------------------------------------------

(deftest runtime-sub-declares-a-runtime-partition-input
  (testing "a framework reg-runtime-sub lowers to a [:runtime []] declared input"
    ;; reg-runtime-sub is framework-internal (not on the rf/ facade) — call
    ;; through re-frame.subs directly. Its signal source is the runtime-db
    ;; projection, so its conservative declared input is [:runtime []].
    (rf.subs/reg-runtime-sub :rf.route/params
                          (fn [runtime-db _] (get-in runtime-db [:rf.runtime/routing :current :params])))
    (let [node ((rf.subs.tooling/sub-algebra-view) :rf.route/params)]
      (is (some? node))
      (is (has-fixed-classifications? node)
          "a runtime sub is still an ephemeral / on-demand / cache-entry derivation")
      (is (= [:fact :rf.route/params] (:output node)))
      (is (= [[:runtime []]] (:inputs node))
          "the runtime sub reads the runtime-db partition — declared input is [:runtime []]")
      (is (= {:kind :reg-sub :id :rf.route/params} (:source-form node))))))

;; ---- reg-frame-state-sub -------------------------------------------------

(deftest frame-state-sub-declares-a-frame-state-input
  (testing "a framework reg-frame-state-sub lowers to a [:frame-state []] declared input"
    ;; A :frame-state sub reads across BOTH partitions (the whole frame-state
    ;; value); the input vocabulary reserves [:frame-state path] for exactly
    ;; this framework-internal cross-partition read.
    (rf.subs/reg-frame-state-sub :rf/whole-state
                              (fn [frame-state _] frame-state))
    (let [node ((rf.subs.tooling/sub-algebra-view) :rf/whole-state)]
      (is (some? node))
      (is (has-fixed-classifications? node))
      (is (= [:fact :rf/whole-state] (:output node)))
      (is (= [[:frame-state []]] (:inputs node))
          "the frame-state sub reads across partitions — declared input is [:frame-state []]"))))

;; ---- source-coords / schema / doc passthrough ----------------------------

(deftest source-coords-surface-in-the-node
  (testing ":ns / :line / :file captured by reg-sub surface under :source"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [node ((rf.subs.tooling/sub-algebra-view) :n)
          source (:source node)]
      ;; Always-on witness (rf2-d2841): the sub is lowered into the algebra
      ;; regardless of posture — the precondition for reading any coord off it.
      (is (some? node)
          "the registration is projected into the algebra view in BOTH postures")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
      ;; Source coords are reflection metadata, elided in production.
      (when rf.interop/debug-enabled?
        (is (some? source) ":source map is present when the registration carried coords")
        (is (some? (:ns source))     ":ns captured at the call site")
        (is (number? (:line source)) ":line captured at the call site")
        (is (some? (:file source))   ":file captured at the call site")))))

(deftest schema-and-doc-pass-through
  (testing ":schema and :doc supplied on the registration meta surface in the node"
    (rf/reg-sub :priced
                {:doc    "a priced item"
                 :schema :app.money/amount}
                (fn [db _] (:price db)))
    (let [node ((rf.subs.tooling/sub-algebra-view) :priced)]
      ;; `:schema` is LOAD-BEARING (precomputed marks / introspection) and is
      ;; retained in production — no guard.
      (is (= :app.money/amount (:schema node))
          "the declared output :schema surfaces as a node fact")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
      (when rf.interop/debug-enabled?
        (is (= "a priced item" (:doc node)))))))

(deftest no-schema-or-doc-when-absent
  (testing ":schema / :doc are absent when the registration didn't supply them"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [node ((rf.subs.tooling/sub-algebra-view) :n)]
      ;; `:schema` is retained in production, so its absence here really is
      ;; "the registration did not supply one" — posture-independent.
      (is (not (contains? node :schema)))
      ;; rf2-d2841 — dev-instrumentation arm. A NEGATIVE about a key the gate
      ;; elides WHOLESALE: outside the arm it passes because `:doc` never
      ;; exists in production, not because this registration omitted it.
      (when rf.interop/debug-enabled?
        (is (not (contains? node :doc)))))))

;; ---- registry semantics --------------------------------------------------

(deftest reregistration-replaces-the-node
  (testing "re-registering a sub replaces its algebra node (last-write-wins)"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (is (= [[:db []]] (:inputs ((rf.subs.tooling/sub-algebra-view) :a))))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :a {:inputs [[:b]]} (fn [[b] _] b))
    (let [node ((rf.subs.tooling/sub-algebra-view) :a)]
      (is (= [[:sub [:b]]] (:inputs node))
          "the re-registered declared-input chain replaces the prior :db reader's inputs")
      (is (has-fixed-classifications? node)))))

(deftest cleared-sub-is-removed
  (testing "(clear-sub id) removes the sub from the algebra view"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (is (contains? (rf.subs.tooling/sub-algebra-view) :a))
    (rf/clear :sub :a)
    (let [view (rf.subs.tooling/sub-algebra-view)]
      (is (not (contains? view :a)))
      (is (contains? view :b)))))
