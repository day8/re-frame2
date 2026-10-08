(ns re-frame.story.diff-test
  "Tests for the semantic diff over canonical run artifacts —
  `diff-run-artifacts` + the pure `diff-runs` core
  (spec/017-Testing-Story.md §Semantic diff): the facet diffs and the
  assembler over hand-built run-results, then `diff-run-artifacts` over real
  replays."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.epoch     :as rf.epoch]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.artifact    :as rf.story.artifact]
            [re-frame.story.diff        :as rf.story.diff]
            [re-frame.story.fingerprint :as rf.story.fingerprint]))

;; ===========================================================================
;; PURE: facet diffs
;; ===========================================================================

(deftest diff-app-db-readable-delta
  (doseq [[label baseline current expected]
          [["one changed leaf; the unchanged nested path is silent"
            {:n 1 :user {:name "ada"}} {:n 2 :user {:name "ada"}}
            {:changed [{:path [:n] :baseline 1 :current 2}]}]
           ["an added and a removed leaf are localised to their paths"
            {:a 1 :gone 9} {:a 1 :new 7}
            {:added [{:path [:new] :current 7}] :removed [{:path [:gone] :baseline 9}]}]
           ;; An empty ROOT contributes no leaf, so a cleared app-db never
           ;; reports a spurious {:path []} entry.
           ["current cleared to {} reports only the removed key"
            {:a 1} {}
            {:removed [{:path [:a] :baseline 1}]}]
           ["a non-root empty map is still a leaf"
            {:k {}} {:k {:a 1}}
            {:added [{:path [:k :a] :current 1}] :removed [{:path [:k] :baseline {}}]}]]]
    (testing label
      (is (= expected (rf.story.diff/diff-app-db baseline current))))))

(deftest diff-effects-multiset-delta
  (testing "an effect emitted only by current is :only-current"
    (is (= {:only-current [{:fx :analytics/track :args {:event :viewed}}]}
           (rf.story.diff/diff-effects
             {:effects [{:fx :http/get :args {:url "/a"}}]}
             {:effects [{:fx :http/get :args {:url "/a"}}
                        {:fx :analytics/track :args {:event :viewed}}]}))))
  (testing "the SAME effect twice vs once is a multiset difference (the surplus)"
    (is (= {:only-baseline [{:fx :db/save}]}
           (rf.story.diff/diff-effects {:effects [{:fx :db/save} {:fx :db/save}]}
                                       {:effects [{:fx :db/save}]})))))

(deftest diff-schema-violations-by-selector
  (is (= {:only-current [[:event :checkout/submit]]}
         (rf.story.diff/diff-schema-violations
           {:schema-violations []}
           {:schema-violations [{:where :event :failing-id :checkout/submit
                                 :selector [:event :checkout/submit]}]}))))

(defn- tape-with-ops
  "A minimal epoch tape whose single epoch carries trace events with the
  given `:operation`s, in order."
  [ops]
  [{:epoch-id 1
    :trace-events (mapv (fn [op] {:operation op :op-type :event}) ops)}])

(deftest diff-trace-ops-causal-spine
  (testing "a diverging op sequence reports both spines + the first divergence"
    (is (= {:baseline [:a :b :c] :current [:a :x :c] :first-divergence 1}
           (rf.story.diff/diff-trace-ops {:epoch-tape (tape-with-ops [:a :b :c])}
                                         {:epoch-tape (tape-with-ops [:a :x :c])}))))
  (testing "a dropped trailing op is a diff with no in-range first-divergence"
    (is (= {:baseline [:a :b :c] :current [:a :b]}
           (rf.story.diff/diff-trace-ops {:epoch-tape (tape-with-ops [:a :b :c])}
                                         {:epoch-tape (tape-with-ops [:a :b])})))))

(deftest diff-assertions-verdict-delta
  (is (= {:changed [{:selector [:rf.assert/path-equals [[:n] 1]]
                     :baseline :pass :current :fail}]}
         (rf.story.diff/diff-assertions
           {:assertions [{:assertion :rf.assert/path-equals :payload [[:n] 1] :status :pass}]}
           {:assertions [{:assertion :rf.assert/path-equals :payload [[:n] 1] :status :fail}]}))))

(deftest diff-checks-verdict-delta
  (is (= {:changed [{:selector :checkout/valid :baseline :pass :current :fail}]}
         (rf.story.diff/diff-checks
           {:checks [{:check :checkout/valid :status :pass :assertions []}]}
           {:checks [{:check :checkout/valid :status :fail :assertions []}]}))
      "the check identity is its id, not its records"))

;; ===========================================================================
;; PURE: the assembler — :same? and the multi-facet readable diff
;; ===========================================================================

;; :same? is canonicalize equality, so a map<->vector flip in app-db must not
;; read as :same? (that would suppress the whole diff), and a same-semantics
;; fn in app-db must not read as a false difference.
(deftest diff-runs-sees-collection-kind-flips-and-ignores-fn-identity
  (is (contains? (:facets (rf.story.diff/diff-runs {:status :pass :app-db {:k {:a 1}}}
                                                   {:status :pass :app-db {:k [:a 1]}}))
                 :app-db)
      "a map<->vector flip is witnessed and localised to :app-db")
  (is (= {:same? true}
         (rf.story.diff/diff-runs {:status :pass :app-db {:cb (fn [] 1)}}
                                  {:status :pass :app-db {:cb (fn [] 1)}}))
      "fn identity is not a difference"))

(deftest diff-runs-collects-only-differing-facets
  (let [base {:status :pass :app-db {} :effects [] :sub-runs []
              :epoch-tape (tape-with-ops [:e])}
        cur  (assoc base :status :fail :effects [{:fx :http/get :outcome :error}])]
    (is (= {:same?   false
            :facets  #{:status :effects}
            :status  {:baseline :pass :current :fail}
            :effects {:only-current [{:fx :http/get :outcome :error}]}}
           (rf.story.diff/diff-runs base cur)))))

;; The facet set is the run-hash slice :same? is judged over; :trace-ops is the
;; readable projection of the :epoch-tape slot.
(deftest facet-set-equals-canonical-slice
  (let [facet-names (set (keys rf.story.diff/facet-fns))
        slice-keys  (set rf.story.fingerprint/run-hash-input-keys)]
    (is (= (disj facet-names :trace-ops) (disj slice-keys :epoch-tape)))
    (is (contains? facet-names :trace-ops))
    (is (contains? slice-keys :epoch-tape))))

(deftest diff-runs-sub-runs-only-delta-is-same
  ;; :sub-runs is outside the run-hash slice, so diff-runs agrees with the
  ;; determinism and golden verdicts.
  (let [base {:status :pass :app-db {:n 1}
              :sub-runs [{:query [:visible-todos] :value 3}]}]
    (is (= {:same? true}
           (rf.story.diff/diff-runs base (assoc base :sub-runs [{:query [:visible-todos] :value 5}]))))))

(deftest diff-runs-facets-name-the-perturbed-slot-and-are-never-empty
  (testing "an :epoch-tape divergence no specific facet sees falls back to a
            :slice-keys facet naming the slot"
    (let [base {:status :pass
                :epoch-tape [{:epoch-id 1 :outcome :ok :db-after {:n 1}
                              :trace-events [{:operation :go :op-type :event}]}]}
          cur  (assoc-in base [:epoch-tape 0 :db-after :n] 2)]
      (is (= {:same? false :facets #{:slice-keys} :slice-keys [{:slice-key :epoch-tape}]}
             (rf.story.diff/diff-runs base cur)))))

  (testing "each slice-key perturbation names exactly its own facet"
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
          [[#{:status}            (assoc base :status :fail)]
           [#{:app-db}            (assoc base :app-db {:n 2})]
           [#{:trace-ops}         (assoc-in base [:epoch-tape 0 :trace-events]
                                            [{:operation :stop :op-type :event}])]
           [#{:assertions}        (assoc base :assertions [{:assertion :rf.assert/eq :payload [1 1]
                                                            :status :fail}])]
           [#{:checks}            (assoc base :checks [{:check :c/x :status :fail :assertions []}])]
           [#{:effects}           (assoc base :effects [{:fx :analytics/track}])]
           [#{:schema-violations} (assoc base :schema-violations [{:selector [:event :go]}])]
           [#{:warnings}          (assoc base :warnings [{:operation :slow :category :perf}])]
           [#{:sub-overrides}     (assoc base :sub-overrides {[:login/state] :error})]
           [#{:fidelity}          (assoc base :fidelity #{:real-setup :sub-overrides})]]]
      (doseq [[facets cur] perturbations]
        (is (= facets (:facets (rf.story.diff/diff-runs base cur)))
            (pr-str cur))))))

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
  (let [a (noisy-run {:frame :rf.test.replay/frame-aaa :epoch-id 5
                      :committed-at 1000 :trace-id 17 :db-after {:n 1}})
        b (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                      :committed-at 2000 :trace-id 88 :db-after {:n 1}})
        c (noisy-run {:frame :rf.test.replay/frame-zzz :epoch-id 99
                      :committed-at 2000 :trace-id 88 :db-after {:n 2}})]
    (is (= {:same? true} (rf.story.diff/diff-runs a b))
        "frame ids, epoch ids, timestamps, trace ids are NOT differences")
    (is (= {:same? false :facets #{:app-db}
            :app-db {:changed [{:path [:n] :baseline 1 :current 2}]}}
           (rf.story.diff/diff-runs a c))
        "a real app-db change is the only facet amid the per-run noise")))

;; ===========================================================================
;; HEADLESS: diff-run-artifacts over real replays  (live frame)
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest diff-run-artifacts-divergent-program-surfaces-app-db-facet
  (rf/reg-event :diff/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (let [a (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 1]]]})
        b (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 2]]]})
        d (rf.story.diff/diff-run-artifacts a b)]
    (is (contains? (:facets d) :app-db))
    (is (= [{:path [:v] :baseline 1 :current 2}] (get-in d [:app-db :changed])))))

(deftest diff-run-artifacts-accepts-a-run-result-directly
  ;; The replay records trace events the hand-built result lacks, so only the
  ;; app-db facet is pinned: it must agree.
  (rf/reg-event :diff/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (let [art    (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:diff/set 9]]]})
        result {:status :pass :app-db {:v 9} :effects [] :sub-runs [] :epoch-tape []}
        d      (rf.story.diff/diff-run-artifacts art result)]
    (is (or (:same? d) (not (contains? (:facets d) :app-db))))))
