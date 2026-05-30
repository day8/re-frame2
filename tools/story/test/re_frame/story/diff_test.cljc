(ns re-frame.story.diff-test
  "Tests for the semantic diff over canonical run artifacts —
  `diff-run-artifacts` + the pure `diff-runs` core (rf2-5x1wt.9,
  spec/017-Testing-Story.md §Semantic diff).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE: the facet diffs (`diff-app-db`, `diff-effects`,
    `diff-schema-violations`, `diff-trace-ops`, …) and the diagnostic-only
    `diff-sub-runs` (NOT wired into `diff-runs` — outside the `:same?` slice,
    rf2-e6uod / rf2-5l0a5) and the
    assembler (`diff-runs`) over HAND-BUILT run-results — the §A5 acceptance
    bullets:
      • a small readable diff for an app-db change;
      • an effect-only diff;
      • a schema-error diff;
    plus the load-bearing correctness property — volatile per-run noise
    (frame ids, timestamps, epoch / dispatch / trace ids) is stripped FIRST,
    so noise never reads as a difference while a real change always does.
  - HEADLESS (against a live frame): `diff-run-artifacts` replays two
    artifacts into fresh frames and diffs the results — two equal programs
    diff `{:same? true}` (no volatile drift), a divergent program surfaces a
    readable app-db facet."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.epoch     :as epoch]
            [re-frame.frame     :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.artifact    :as artifact]
            [re-frame.story.diff        :as diff]
            [re-frame.story.fingerprint :as fingerprint]))

;; ===========================================================================
;; PURE: app-db delta  (§A5 — a small readable diff for an app-db change)
;; ===========================================================================

(deftest diff-app-db-readable-delta
  (testing "an unchanged app-db has no delta"
    (is (nil? (diff/diff-app-db {:n 1 :user {:name "ada"}}
                                {:n 1 :user {:name "ada"}}))))

  (testing "a single changed leaf yields a one-entry :changed at its path"
    (let [d (diff/diff-app-db {:n 1 :user {:name "ada"}}
                              {:n 2 :user {:name "ada"}})]
      (is (= [{:path [:n] :baseline 1 :current 2}] (:changed d)))
      (is (nil? (:added d)))
      (is (nil? (:removed d)))
      (is (= #{:changed} (set (keys d)))
          "ONLY the differing slot appears — the unchanged :user path is silent")))

  (testing "an added and a removed leaf are localised to their paths"
    (let [d (diff/diff-app-db {:a 1 :gone 9}
                              {:a 1 :new 7})]
      (is (= [{:path [:new] :current 7}] (:added d)))
      (is (= [{:path [:gone] :baseline 9}] (:removed d)))
      (is (nil? (:changed d)))))

  (testing "nested leaf changes report the full key path"
    (let [d (diff/diff-app-db {:user {:profile {:age 30}}}
                              {:user {:profile {:age 31}}})]
      (is (= [{:path [:user :profile :age] :baseline 30 :current 31}]
             (:changed d)))))

  ;; rf2-bd6ei — a run that clears app-db to {} must not emit a spurious root
  ;; leaf {:path [] :current {}} / {:path [] :baseline {}}; the real
  ;; populated-vs-empty difference must still diff correctly.
  (testing "current cleared to {} reports the removed key, NOT a spurious [] leaf"
    (let [d (diff/diff-app-db {:a 1} {})]
      (is (= [{:path [:a] :baseline 1}] (:removed d))
          "the populated baseline's key is :removed")
      (is (nil? (:added d))
          "NO spurious {:path [] :current {}} root leaf for the empty side")
      (is (= #{:removed} (set (keys d)))
          "only the genuine difference, nothing at the [] root")))

  (testing "baseline empty {} vs populated current is the symmetric case"
    (let [d (diff/diff-app-db {} {:a 1})]
      (is (= [{:path [:a] :current 1}] (:added d)))
      (is (nil? (:removed d))
          "NO spurious {:path [] :baseline {}} root leaf for the empty side")
      (is (= #{:added} (set (keys d))))))

  (testing "a NON-ROOT empty map is STILL a semantic leaf ({:k {}} vs {:k {:a 1}})"
    (let [d (diff/diff-app-db {:k {}} {:k {:a 1}})]
      (is (= [{:path [:k :a] :current 1}] (:added d)))
      (is (= [{:path [:k] :baseline {}}] (:removed d))
          "the [:k] empty-map leaf is preserved — only the ROOT is special-cased")))

  (testing "two empty roots are equal — no delta"
    (is (nil? (diff/diff-app-db {} {})))))

;; ===========================================================================
;; PURE: effect-only diff  (§A5 — an effect-only diff)
;; ===========================================================================

(deftest diff-effects-multiset-delta
  (testing "identical effect rows produce no delta"
    (is (nil? (diff/diff-effects {:effects [{:fx :http/get :args {:url "/a"}}]}
                                 {:effects [{:fx :http/get :args {:url "/a"}}]}))))

  (testing "an effect emitted only by current is :only-current"
    (let [d (diff/diff-effects
              {:effects [{:fx :http/get :args {:url "/a"}}]}
              {:effects [{:fx :http/get :args {:url "/a"}}
                         {:fx :analytics/track :args {:event :viewed}}]})]
      (is (= [{:fx :analytics/track :args {:event :viewed}}] (:only-current d)))
      (is (nil? (:only-baseline d)))))

  (testing "the SAME effect twice vs once is a multiset difference (the surplus)"
    (let [d (diff/diff-effects
              {:effects [{:fx :db/save} {:fx :db/save}]}
              {:effects [{:fx :db/save}]})]
      (is (= [{:fx :db/save}] (:only-baseline d))
          "baseline emitted it twice, current once — one surplus row")
      (is (nil? (:only-current d))))))

;; ===========================================================================
;; PURE: schema-error diff  (§A5 — a schema-error diff)
;; ===========================================================================

(deftest diff-schema-violations-by-selector
  (testing "the same violation surface on both sides is no diff"
    (let [v {:where :event :failing-id :checkout/submit
             :selector [:event :checkout/submit]}]
      (is (nil? (diff/diff-schema-violations {:schema-violations [v]}
                                             {:schema-violations [v]})))))

  (testing "a violation surface only current failed on is :only-current"
    (let [base    {:schema-violations []}
          current {:schema-violations
                   [{:where :event :failing-id :checkout/submit
                     :selector [:event :checkout/submit]}]}
          d       (diff/diff-schema-violations base current)]
      (is (= [[:event :checkout/submit]] (:only-current d))
          "current introduced a schema failure at [:event :checkout/submit]")
      (is (nil? (:only-baseline d)))))

  (testing "the selector is recomputed when a record omits it (no :selector slot)"
    (let [current {:schema-violations
                   [{:where :app-db :registered-path [:user] :path [:user :age]}]}
          d       (diff/diff-schema-violations {:schema-violations []} current)]
      (is (= [[:app-db [:user] [:user :age]]] (:only-current d))))))

;; ===========================================================================
;; PURE: trace-op spine + sub-runs + status
;; ===========================================================================

(defn- tape-with-ops
  "A minimal epoch tape whose single epoch carries trace events with the
  given `:operation`s, in order."
  [ops]
  [{:epoch-id 1
    :trace-events (mapv (fn [op] {:operation op :op-type :event}) ops)}])

(deftest diff-trace-ops-causal-spine
  (testing "identical op sequences are no diff"
    (is (nil? (diff/diff-trace-ops
                {:epoch-tape (tape-with-ops [:rf.event/run-start :rf.event/run-end])}
                {:epoch-tape (tape-with-ops [:rf.event/run-start :rf.event/run-end])}))))

  (testing "a diverging op sequence reports both spines + the first divergence"
    (let [d (diff/diff-trace-ops
              {:epoch-tape (tape-with-ops [:a :b :c])}
              {:epoch-tape (tape-with-ops [:a :x :c])})]
      (is (= [:a :b :c] (:baseline d)))
      (is (= [:a :x :c] (:current d)))
      (is (= 1 (:first-divergence d)) "index 1 (:b vs :x) is the first divergence")))

  (testing "a dropped trailing op is a diff with no in-range first-divergence"
    (let [d (diff/diff-trace-ops
              {:epoch-tape (tape-with-ops [:a :b :c])}
              {:epoch-tape (tape-with-ops [:a :b])})]
      (is (= [:a :b :c] (:baseline d)))
      (is (= [:a :b] (:current d)))
      (is (nil? (:first-divergence d))
          "current is a proper prefix — only the length differs"))))

(deftest diff-sub-runs-multiset-delta
  (testing "diff-sub-runs is a DIAGNOSTIC fn — called directly it still
            reports a view-fact delta (rf2-e6uod / rf2-5l0a5)"
    (let [d (diff/diff-sub-runs
              {:sub-runs [{:query [:visible-todos] :value 3}]}
              {:sub-runs [{:query [:visible-todos] :value 5}]})]
      (is (= [{:query [:visible-todos] :value 3}] (:only-baseline d)))
      (is (= [{:query [:visible-todos] :value 5}] (:only-current d))))))

(deftest diff-status-headline
  (testing "a pass → fail flip is reported"
    (is (= {:baseline :pass :current :fail}
           (diff/diff-status {:status :pass} {:status :fail}))))
  (testing "a shared status is no diff"
    (is (nil? (diff/diff-status {:status :pass} {:status :pass})))))

;; ===========================================================================
;; PURE: warnings / assertions / checks / sub-overrides / fidelity facets
;; (rf2-rv9tt — the run-hash slice slots that previously had NO facet)
;; ===========================================================================

(deftest diff-warnings-multiset-delta
  (testing "identical warning rows produce no delta"
    (let [w {:operation :slow-sub :category :performance}]
      (is (nil? (diff/diff-warnings {:warnings [w]} {:warnings [w]})))))

  (testing "a warning raised only by baseline is :only-baseline"
    (let [d (diff/diff-warnings
              {:warnings [{:operation :slow-sub :category :performance}]}
              {:warnings []})]
      (is (= [{:operation :slow-sub :category :performance}] (:only-baseline d)))
      (is (nil? (:only-current d))))))

(deftest diff-assertions-verdict-delta
  (testing "the same assertion verdict on both sides is no diff"
    (let [a {:assertion :rf.assert/path-equals :payload [[:n] 1] :status :pass}]
      (is (nil? (diff/diff-assertions {:assertions [a]} {:assertions [a]})))))

  (testing "a pass → fail verdict flip is a one-entry :changed keyed by selector"
    (let [d (diff/diff-assertions
              {:assertions [{:assertion :rf.assert/path-equals :payload [[:n] 1]
                             :status :pass}]}
              {:assertions [{:assertion :rf.assert/path-equals :payload [[:n] 1]
                             :status :fail}]})]
      (is (= [{:selector [:rf.assert/path-equals [[:n] 1]]
               :baseline :pass :current :fail}]
             (:changed d)))
      (is (nil? (:added d)))
      (is (nil? (:removed d)))))

  (testing "an assertion only current evaluated is :added by selector"
    (let [d (diff/diff-assertions
              {:assertions []}
              {:assertions [{:assertion :rf.assert/no-warnings :payload []
                             :status :fail}]})]
      (is (= [{:selector [:rf.assert/no-warnings []] :current :fail}] (:added d)))))

  (testing "the payload disambiguates two same-id assertions at different paths"
    (let [d (diff/diff-assertions
              {:assertions [{:assertion :rf.assert/path-equals :payload [[:a] 1]
                             :status :pass}
                            {:assertion :rf.assert/path-equals :payload [[:b] 2]
                             :status :pass}]}
              {:assertions [{:assertion :rf.assert/path-equals :payload [[:a] 1]
                             :status :pass}
                            {:assertion :rf.assert/path-equals :payload [[:b] 2]
                             :status :fail}]})]
      (is (= [{:selector [:rf.assert/path-equals [[:b] 2]]
               :baseline :pass :current :fail}]
             (:changed d))
          "only the [:b] assertion flipped — the [:a] one is silent"))))

(deftest diff-checks-verdict-delta
  (testing "the same check verdict on both sides is no diff"
    (let [c {:check :checkout/valid :status :pass :assertions []}]
      (is (nil? (diff/diff-checks {:checks [c]} {:checks [c]})))))

  (testing "a check that flipped pass → fail is a one-entry :changed by check id"
    (let [d (diff/diff-checks
              {:checks [{:check :checkout/valid :status :pass :assertions []}]}
              {:checks [{:check :checkout/valid :status :fail :assertions []}]})]
      (is (= [{:selector :checkout/valid :baseline :pass :current :fail}]
             (:changed d))
          "the check identity is its id; the underlying records are not the key"))))

(deftest diff-sub-overrides-keyed-delta
  (testing "identical override maps produce no delta"
    (let [m {[:login/state] :error}]
      (is (nil? (diff/diff-sub-overrides {:sub-overrides m} {:sub-overrides m})))))

  (testing "an override whose pinned value differs is :changed by query vector"
    (let [d (diff/diff-sub-overrides
              {:sub-overrides {[:login/state] :error}}
              {:sub-overrides {[:login/state] :loading}})]
      (is (= [{:query [:login/state] :baseline :error :current :loading}]
             (:changed d)))))

  (testing "an override only current carries is :added"
    (let [d (diff/diff-sub-overrides
              {:sub-overrides {}}
              {:sub-overrides {[:item 7] {:name "x"}}})]
      (is (= [{:query [:item 7] :current {:name "x"}}] (:added d))))))

(deftest diff-fidelity-set-delta
  (testing "identical fidelity rung sets produce no delta"
    (is (nil? (diff/diff-fidelity {:fidelity #{:real-setup}}
                                  {:fidelity #{:real-setup}}))))

  (testing "a rung current rested on but baseline did not is :only-current"
    (let [d (diff/diff-fidelity {:fidelity #{:real-setup}}
                                {:fidelity #{:real-setup :sub-overrides}})]
      (is (= #{:sub-overrides} (:only-current d)))
      (is (nil? (:only-baseline d)))))

  (testing "rungs on each side that the other lacks are split"
    (let [d (diff/diff-fidelity {:fidelity #{:real-setup}}
                                {:fidelity #{:sub-overrides}})]
      (is (= #{:real-setup} (:only-baseline d)))
      (is (= #{:sub-overrides} (:only-current d))))))

;; ===========================================================================
;; PURE: the assembler — :same? and the multi-facet readable diff
;; ===========================================================================

(deftest diff-runs-same-when-behaviourally-identical
  (testing "two runs equal under canonicalize diff to {:same? true}"
    (let [r {:status :pass :app-db {:n 1} :effects [] :sub-runs []
             :epoch-tape (tape-with-ops [:rf.event/run-start])}]
      (is (= {:same? true} (diff/diff-runs r r))))))

;; rf2-sn7nh — diff-runs' :same? gate is canonicalize equality, so it
;; inherited the rf2-lvrqa map/vector collision (a map<->vector flip in
;; app-db read as :same? true, and the :app-db facet never ran because the
;; gate suppressed the whole diff first) and the rf2-4gwja fn-slot
;; nondeterminism (a same-semantics fn in app-db read as a false :changed).
;; The foundation fixes land both here for free.
(deftest diff-runs-inherits-lvrqa-and-4gwja-fixes
  (testing "a map<->vector flip in app-db is NO LONGER :same? true and
            surfaces a readable :app-db facet (rf2-sn7nh / rf2-lvrqa)"
    (let [d (diff/diff-runs {:status :pass :app-db {:k {:a 1}}}
                            {:status :pass :app-db {:k [:a 1]}})]
      (is (false? (:same? d)) "the collision is witnessed, not suppressed")
      (is (contains? (:facets d) :app-db) "the :app-db facet localises it")))
  (testing "an empty-map<->empty-vector flip is also witnessed (rf2-lvrqa)"
    (is (false? (:same? (diff/diff-runs {:status :pass :app-db {:k {}}}
                                        {:status :pass :app-db {:k []}})))))
  (testing "a same-semantics fn in app-db is :same? true — no false :changed
            from object-identity noise (rf2-sn7nh / rf2-4gwja)"
    (is (= {:same? true}
           (diff/diff-runs {:status :pass :app-db {:cb (fn [] 1)}}
                           {:status :pass :app-db {:cb (fn [] 1)}})))))

(deftest diff-runs-collects-only-differing-facets
  (testing "an app-db-only change surfaces ONLY the :app-db facet"
    (let [base {:status :pass :app-db {:n 1} :effects [] :sub-runs []
                :epoch-tape (tape-with-ops [:rf.event/run-start])}
          cur  (assoc base :app-db {:n 2})
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (= #{:app-db} (:facets d)))
      (is (= [{:path [:n] :baseline 1 :current 2}] (get-in d [:app-db :changed])))
      (is (not (contains? d :effects)))))

  (testing "a status + effect change surfaces BOTH facets, named in :facets"
    (let [base {:status :pass :app-db {} :effects [] :sub-runs []
                :epoch-tape (tape-with-ops [:e])}
          cur  {:status :fail :app-db {} :sub-runs []
                :effects [{:fx :http/get :outcome :error}]
                :epoch-tape (tape-with-ops [:e])}
          d    (diff/diff-runs base cur)]
      (is (= #{:status :effects} (:facets d)))
      (is (= {:baseline :pass :current :fail} (:status d)))
      (is (= [{:fx :http/get :outcome :error}] (get-in d [:effects :only-current]))))))

;; ===========================================================================
;; PURE: the assembler covers EVERY run-hash slice slot (rf2-rv9tt)
;; — each previously-silent surface now names its facet through diff-runs
;; ===========================================================================

(deftest diff-runs-covers-every-slice-surface
  (testing "a warnings-ONLY delta surfaces the :warnings facet (was {:facets #{}})"
    (let [base {:status :pass :warnings []}
          cur  {:status :pass :warnings [{:operation :slow-sub :category :perf}]}
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (= #{:warnings} (:facets d)))
      (is (seq (:facets d)) "the previously-silent warnings delta is now named")))

  (testing "an assertions-ONLY verdict flip surfaces the :assertions facet"
    (let [base {:status :pass :assertions [{:assertion :rf.assert/eq :payload [1 1]
                                            :status :pass}]}
          cur  {:status :pass :assertions [{:assertion :rf.assert/eq :payload [1 1]
                                            :status :fail}]}
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (= #{:assertions} (:facets d)))
      (is (= [{:selector [:rf.assert/eq [1 1]] :baseline :pass :current :fail}]
             (get-in d [:assertions :changed])))))

  (testing "a checks-ONLY verdict flip surfaces the :checks facet"
    (let [base {:status :pass :checks [{:check :c/login :status :pass :assertions []}]}
          cur  {:status :pass :checks [{:check :c/login :status :fail :assertions []}]}
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (= #{:checks} (:facets d)))))

  (testing "a sub-overrides-ONLY delta surfaces the :sub-overrides facet"
    (let [base {:status :pass :sub-overrides {[:login/state] :error}}
          cur  {:status :pass :sub-overrides {[:login/state] :loading}}
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (= #{:sub-overrides} (:facets d)))))

  (testing "a fidelity-ONLY delta surfaces the :fidelity facet"
    (let [base {:status :pass :fidelity #{:real-setup}}
          cur  {:status :pass :fidelity #{:real-setup :sub-overrides}}
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (= #{:fidelity} (:facets d))))))

;; ===========================================================================
;; PURE: facet-set == canonical slice keys (rf2-e6uod / rf2-5l0a5)
;; — the diff's facet set is EXACTLY the run-hash slice :same? is judged over,
;;   so no facet is dead (fires on a slot outside the slice) and no slice slot
;;   is uncovered. `:trace-ops` is the readable projection of the `:epoch-tape`
;;   slot, so it stands in for it 1:1 in the equality.
;; ===========================================================================

(deftest facet-set-equals-canonical-slice
  (testing "facet-fns keys == run-hash-input-keys (with :trace-ops ⇄ :epoch-tape)"
    (let [facet-names (set (keys diff/facet-fns))
          slice-keys  (set fingerprint/run-hash-input-keys)]
      (is (= (disj facet-names :trace-ops)
             (disj slice-keys :epoch-tape))
          "the facet set and the canonical slice are the SAME surface — the
           only naming difference is :trace-ops (the readable :epoch-tape
           projection) standing in for :epoch-tape")
      (is (contains? facet-names :trace-ops)
          ":trace-ops covers the :epoch-tape slice slot")
      (is (contains? slice-keys :epoch-tape))))

  (testing ":sub-runs is NOT a facet — it is deliberately outside the slice"
    (is (not (contains? (set (keys diff/facet-fns)) :sub-runs))
        ":sub-runs carries no diff-runs facet (over-recomputed evidence, not a
         determinism input)")
    (is (not (contains? (set fingerprint/run-hash-input-keys) :sub-runs))
        ":sub-runs is excluded from the run-hash slice — the facet set honors
         that exclusion rather than overstating coverage")))

(deftest diff-runs-sub-runs-only-delta-is-same
  (testing "two runs differing ONLY in :sub-runs diff to {:same? true} — the
            :sub-runs delta does NOT decide :same? (it is outside the slice,
            rf2-e6uod / rf2-5l0a5)"
    (let [base {:status :pass :app-db {:n 1}
                :sub-runs [{:query [:visible-todos] :value 3}]}
          cur  (assoc base :sub-runs [{:query [:visible-todos] :value 5}])
          d    (diff/diff-runs base cur)]
      (is (= {:same? true} d)
          "a pure :sub-runs delta is behaviourally identical to the gate — so
           diff-runs agrees with the determinism / golden verdict")
      (is (not (contains? (:facets d) :sub-runs))
          "no :sub-runs facet ever fires through diff-runs"))))

;; ===========================================================================
;; PURE: the non-empty-:facets INVARIANT (rf2-rv9tt — the masterpiece guarantee)
;; ===========================================================================

(deftest diff-runs-never-returns-empty-facets
  (testing "an :epoch-tape divergence that is NOT a trace-op change falls back
            to a coarse :slice-keys facet naming the diverging slot"
    ;; The trace-op spine is identical (both have op :go); the divergence is a
    ;; NON-op epoch field. No specific facet fires, so the invariant fallback
    ;; must name the :epoch-tape slice key rather than return :facets #{}.
    (let [base {:status :pass
                :epoch-tape [{:epoch-id 1 :outcome :ok :db-after {:n 1}
                              :trace-events [{:operation :go :op-type :event}]}]}
          cur  {:status :pass
                :epoch-tape [{:epoch-id 1 :outcome :ok :db-after {:n 2}
                              :trace-events [{:operation :go :op-type :event}]}]}
          d    (diff/diff-runs base cur)]
      (is (false? (:same? d)))
      (is (seq (:facets d)) "INVARIANT: :same? false ⟹ :facets non-empty")
      (is (= #{:slice-keys} (:facets d)))
      (is (= [{:slice-key :epoch-tape}] (:slice-keys d))
          "the coarse fallback names WHICH run-hash slot perturbed the judgement")))

  (testing "INVARIANT property: across many slice-key perturbations, a
            :same? false diff NEVER carries an empty :facets set"
    ;; One perturbation per run-hash slice key — the exact slice :same? is
    ;; judged over. Each must yield a non-empty :facets (a named facet or the
    ;; coarse fallback), never the undiagnosable {:same? false :facets #{}}.
    (let [base {:status     :pass
                :app-db     {:n 1}
                :epoch-tape [{:epoch-id 1 :outcome :ok :db-after {:n 1}
                              :effects [] :sub-runs []
                              :trace-events [{:operation :go :op-type :event}]}]
                :assertions [{:assertion :rf.assert/eq :payload [1 1] :status :pass}]
                :checks     [{:check :c/x :status :pass :assertions []}]
                :effects    [{:fx :http/get}]
                :schema-violations []
                :warnings   []
                :sub-overrides {}
                :fidelity   #{:real-setup}}
          perturbations
          [(assoc base :status :fail)
           (assoc base :app-db {:n 2})
           (update base :epoch-tape
                   (fn [t] (assoc-in t [0 :trace-events]
                                     [{:operation :stop :op-type :event}])))
           (assoc base :assertions [{:assertion :rf.assert/eq :payload [1 1]
                                     :status :fail}])
           (assoc base :checks [{:check :c/x :status :fail :assertions []}])
           (assoc base :effects [{:fx :analytics/track}])
           (assoc base :schema-violations [{:selector [:event :go]}])
           (assoc base :warnings [{:operation :slow :category :perf}])
           (assoc base :sub-overrides {[:login/state] :error})
           (assoc base :fidelity #{:real-setup :sub-overrides})]]
      (doseq [cur perturbations]
        (let [d (diff/diff-runs base cur)]
          (is (false? (:same? d)) (str "perturbation should differ: " (pr-str cur)))
          (is (seq (:facets d))
              (str "INVARIANT VIOLATED — empty :facets for: " (pr-str cur))))))))

;; ===========================================================================
;; PURE: the load-bearing property — volatile noise is stripped FIRST
;; ===========================================================================

(defn- noisy-run
  "A run-result whose epoch tape + app-db carry per-run stamps a fresh-frame
  replay would write differently every time (frame id, epoch id, committed-at,
  trace :id / :time), wrapping the same semantic `db-after`."
  [{:keys [frame epoch-id committed-at trace-id db-after]}]
  {:status :pass
   :app-db db-after
   :frame  frame
   :epoch-tape
   [{:epoch-id epoch-id :frame frame :committed-at committed-at
     :outcome :ok :db-before {} :db-after db-after
     :effects [] :sub-runs [] :renders []
     :trace-events [{:operation :rf.event/run-start :op-type :event
                     :id trace-id :time committed-at
                     :tags {:rf.trace/event-id :app/go}}]}]})

(deftest diff-runs-strips-volatile-noise
  (testing "two runs differing ONLY in per-run stamps diff to {:same? true}"
    (let [a (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                        :committed-at 1000 :trace-id 17 :db-after {:n 1}})
          b (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                        :committed-at 2000 :trace-id 88 :db-after {:n 1}})]
      (is (= {:same? true} (diff/diff-runs a b))
          "frame ids, epoch ids, timestamps, trace ids are NOT differences")))

  (testing "a real app-db change IS detected even amid the per-run noise"
    (let [a (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                        :committed-at 1000 :trace-id 17 :db-after {:n 1}})
          c (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                        :committed-at 2000 :trace-id 88 :db-after {:n 2}})
          d (diff/diff-runs a c)]
      (is (false? (:same? d)))
      (is (contains? (:facets d) :app-db))
      (is (= [{:path [:n] :baseline 1 :current 2}] (get-in d [:app-db :changed]))
          "the strip surfaces the SEMANTIC change, not the volatile drift"))))

(deftest diff-runs-strips-story-accumulator-keys
  (testing ":rf.story/* accumulator keys in app-db are stripped before diffing"
    (let [a {:status :pass :app-db {:n 1 :rf.story/probe :a}}
          b {:status :pass :app-db {:n 1 :rf.story/probe :b}}]
      (is (= {:same? true} (diff/diff-runs a b))
          "the accumulator key differs but is not semantic — stripped first"))))

;; ===========================================================================
;; HEADLESS: diff-run-artifacts over real replays  (live frame)
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest diff-run-artifacts-equal-programs-are-same
  (testing "replaying the SAME program twice into fresh frames diffs {:same? true}
            — fresh-frame volatile drift causes no false difference"
    (rf/reg-event-db :diff/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [a (artifact/make-run-artifact
              {:event-program [[:dispatch [:diff/inc]] [:dispatch [:diff/inc]]]})]
      (is (= {:same? true} (diff/diff-run-artifacts a a))
          "two fresh-frame replays of one program are behaviourally identical"))))

(deftest diff-run-artifacts-divergent-program-surfaces-app-db-facet
  (testing "two programs producing different final app-db surface a readable
            :app-db facet"
    (rf/reg-event-db :diff/set (fn [db [_ v]] (assoc db :v v)))
    (let [a (artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 1]]]})
          b (artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 2]]]})
          d (diff/diff-run-artifacts a b)]
      (is (false? (:same? d)))
      (is (contains? (:facets d) :app-db))
      (is (= [{:path [:v] :baseline 1 :current 2}]
             (get-in d [:app-db :changed]))))))

(deftest diff-run-artifacts-accepts-a-run-result-directly
  (testing "a run-result side is used as-is (the pure path) — diffing an
            artifact replay against a hand-built result still works"
    (rf/reg-event-db :diff/set (fn [db [_ v]] (assoc db :v v)))
    (let [art    (artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 9]]]})
          result {:status :pass :app-db {:v 9} :effects [] :sub-runs []
                  :epoch-tape []}
          d      (diff/diff-run-artifacts art result)]
      ;; The artifact replay's app-db {:v 9} matches the hand-built result's
      ;; {:v 9}; the trace-op spine differs (the replay has trace events, the
      ;; hand-built result none), so the diff is NOT :same? but the app-db
      ;; facet is absent.
      (is (or (:same? d)
              (not (contains? (:facets d) :app-db)))
          "app-db agrees across the artifact-vs-result diff"))))
