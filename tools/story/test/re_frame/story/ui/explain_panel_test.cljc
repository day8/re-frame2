(ns re-frame.story.ui.explain-panel-test
  "JVM-portable regression net for the Explain panel's pure projection
  (rf2-ba86n.9, spec/020 §4 / spec/017 §Explain API).

  Covers the host-free surface:

  - `explain-sections` — the ordered section inventory, the
    present?/absent distinction, and that EVERY spec-listed slot is
    rendered (absent ones marked, never dropped).
  - `explain-for`       — error-trapping over the pure plan compiler
    (a missing arg / unknown variant surfaces as `:error`, not a
    thrown exception).
  - the string shapers (`chain-label` / `conflict-summary` / `raw-edn`).

  CLJS-side (the React render of each `:render-as`, the raw-EDN toggle,
  copy-to-clipboard, the open!/scroll command) lives in
  `explain_panel_cljs_test.cljs`; this corpus pins the pure projection
  only — no host, no Reagent."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [re-frame.story.ui.explain-panel :as explain]))

;; ---------------------------------------------------------------------------
;; A representative explain map. Mirrors the slot shape the plan compiler
;; emits (re-frame.story.plan/build-plan): a composed, arg-substituted,
;; network + sub-override lowering plan so the present? branches are
;; exercised, plus the always-present provenance/execution/metadata slots.
;; ---------------------------------------------------------------------------

(def ^:private full-explain
  {:source-chain [:story.cp/base :story.cp/child]
   :parent-chain [:story.cp/base]
   :compose      [{:kind :fragment :id :frag/auth}
                  {:kind :check :id :check/a11y}]
   :merge        {:setup      :append-inherited-compose-own
                  :args       :deep-merge-inherited-compose-own
                  :checks     :inherit-then-compose
                  :assertions :child-only
                  :script     :compose-then-child
                  :fx-overrides :strict-variant-owned-wins
                  :interceptor-overrides :strict-variant-owned-wins}
   :strict-conflicts [{:field :fx-overrides
                       :key   :rf.http/managed
                       :winner :variant-stub
                       :winning-source :variant
                       :losing-sources [:frag/auth]
                       :rule  :variant-owned-wins}]
   :args         {:label "Hi" :count 3}
   :substitutions [{:key :label :value "Hi"}]
   :effective-args {:label "Hi" :count 3}
   :view-args-validation {:status :ok :missing [] :malformed []}
   :network      {:routes     {[:get "/api/cart"] {:reply {:ok {:items []}}}}
                  :lowered-to {:rf.http/managed :rf.http/managed-test-stub}}
   :sub-overrides {:overrides  {[:cart/total] 42}
                   :validation {:status :ok :violations []}}
   :fidelity     #{:real-setup :sub-overrides}
   :setup-order  [[:dispatch [:cp/init]]]
   :script-order [[:dispatch [:cp/inc]]]
   :checks       [:check/a11y]
   :assertions   [[:rf.assert/no-warnings]]
   :required-runner #{:dom}
   :platforms    #{:client}
   :tags         #{:test :a11y}
   :source       {:file "cp.cljc" :line 12 :column 3}})

;; ---------------------------------------------------------------------------
;; explain-sections — inventory + ordering + every spec-listed slot
;; ---------------------------------------------------------------------------

(def ^:private expected-section-ids
  ["source-chain" "parent-chain" "compose"
   "merge" "strict-conflicts"
   "args" "substitutions" "effective-args" "view-args-validation"
   "network" "sub-overrides" "fidelity"
   "setup-order" "script-order" "checks" "assertions" "required-runner"
   "platforms" "tags" "source"])

(deftest sections-cover-every-spec-slot-in-order
  (testing "the section inventory renders every spec/020 §4 / spec/017
            §Explain API slot, in the provenance → composition → args →
            lowering → execution → metadata order"
    (is (= expected-section-ids
           (mapv :id (explain/explain-sections full-explain)))))
  (testing "every section carries the descriptor slots the renderer reads"
    (doseq [s (explain/explain-sections full-explain)]
      (is (some? (:id s)))
      (is (some? (:label s)))
      (is (keyword? (:render-as s)))
      (is (contains? s :present?))
      (is (contains? s :value)))))

(deftest full-explain-marks-populated-slots-present
  (testing "with a fully-featured plan every section is present? true"
    (let [by-id (into {} (map (juxt :id identity)
                              (explain/explain-sections full-explain)))]
      (doseq [id expected-section-ids]
        (is (true? (boolean (:present? (get by-id id))))
            (str id " should be present in a fully-featured explain map"))))))

(deftest empty-explain-marks-optional-slots-absent
  (testing "an empty explain map renders the full inventory, with the
            optional slots marked not-available (never dropped)"
    (let [sections (explain/explain-sections {})
          by-id    (into {} (map (juxt :id identity) sections))]
      (is (= expected-section-ids (mapv :id sections))
          "the inventory is the same — absent slots are NOT dropped")
      (doseq [id expected-section-ids]
        (is (false? (boolean (:present? (get by-id id))))
            (str id " should be absent for an empty explain map")))
      (testing "absent sections carry a human 'when empty this means…' note"
        (is (every? (comp string? :note) (vals by-id)))))))

(deftest nil-explain-is-safe
  (testing "nil explain projects the same inventory, all absent — no throw"
    (let [sections (explain/explain-sections nil)]
      (is (= expected-section-ids (mapv :id sections)))
      (is (every? (complement :present?) sections)))))

(deftest network-and-sub-override-slots-carry-lowering
  (testing "the network section value carries routes + the managed-stub lowering"
    (let [net (->> (explain/explain-sections full-explain)
                   (filter #(= "network" (:id %)))
                   first)]
      (is (= :network (:render-as net)))
      (is (= {:rf.http/managed :rf.http/managed-test-stub}
             (get-in net [:value :lowered-to])))))
  (testing "the sub-override section value carries overrides + validation"
    (let [so (->> (explain/explain-sections full-explain)
                  (filter #(= "sub-overrides" (:id %)))
                  first)]
      (is (= :sub-ovr (:render-as so)))
      (is (= {:status :ok :violations []}
             (get-in so [:value :validation]))))))

;; ---------------------------------------------------------------------------
;; explain-for — error trapping over the pure compiler
;; ---------------------------------------------------------------------------

(deftest explain-for-traps-unknown-variant
  (testing "an unregistered keyword target surfaces as :error, not a throw"
    (let [result (explain/explain-for :story.nope/missing)]
      (is (nil? (:explain result)))
      (is (string? (:error result)))
      (is (not (str/blank? (:error result)))))))

(deftest explain-for-compiles-inline-plan
  (testing "an inline plan map compiles to an :explain map (no host needed)"
    (let [result (explain/explain-for {:variant/id :inline/x})]
      (is (nil? (:error result))
          (str "expected clean compile, got: " (:error result)))
      (is (map? (:explain result)))
      ;; the always-present provenance slot proves we got a real plan
      (is (= [:inline/x] (get-in result [:explain :source-chain]))))))

;; ---------------------------------------------------------------------------
;; string shapers
;; ---------------------------------------------------------------------------

(deftest chain-label-joins-with-arrows
  (is (= ":story.cp/base  →  :story.cp/child"
         (explain/chain-label [:story.cp/base :story.cp/child])))
  (testing "empty chain → empty string (caller renders the empty state)"
    (is (= "" (explain/chain-label [])))
    (is (= "" (explain/chain-label nil)))))

(deftest conflict-summary-names-winner-and-losers
  (let [s (explain/conflict-summary
            (first (:strict-conflicts full-explain)))]
    (is (str/includes? s ":variant wins"))
    (is (str/includes? s ":frag/auth")
        "names the full qualified losing source")
    (is (str/includes? s "variant-owned-wins"))))

(deftest raw-edn-roundtrips
  (testing "raw-edn produces a parseable EDN string of the explain map"
    (let [s (explain/raw-edn full-explain)]
      (is (string? s))
      (is (= full-explain (edn/read-string s))))))
