(ns re-frame.story.ui.explain-panel-test
  "JVM coverage of the Explain panel's pure projection (spec/020 §4 /
  spec/017 §Explain API): the ordered section inventory and its
  present?/absent marking, `explain-for`'s error trapping and ambient arg
  layers, and the string shapers. The React render lives in
  `explain_panel_cljs_test.cljs`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.explain-panel :as rf.story.ui.explain-panel]))

;; A representative explain map in the slot shape the plan compiler emits,
;; with every slot populated.
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
;; explain-sections — inventory + ordering + present?/absent
;; ---------------------------------------------------------------------------

(deftest sections-cover-every-spec-slot-in-order
  (testing "the section inventory renders every spec/020 §4 / spec/017
            §Explain API slot, in the provenance → composition → args →
            lowering → execution → metadata order"
    (is (= ["source-chain" "parent-chain" "compose"
            "merge" "strict-conflicts"
            "args" "substitutions" "effective-args" "view-args-validation"
            "network" "sub-overrides" "fidelity"
            "setup-order" "script-order" "checks" "assertions" "required-runner"
            "platforms" "tags" "source"]
           (mapv :id (rf.story.ui.explain-panel/explain-sections full-explain))))))

(deftest sections-are-present-only-when-their-slot-has-content
  (is (every? :present? (rf.story.ui.explain-panel/explain-sections full-explain))
      "a fully-featured plan marks every section present")
  (is (not-any? :present? (rf.story.ui.explain-panel/explain-sections {}))
      "an empty explain map marks every section absent (rendered 'not available', not dropped)"))

;; ---------------------------------------------------------------------------
;; explain-for — error trapping over the pure compiler
;; ---------------------------------------------------------------------------

(deftest explain-for-traps-unknown-variant
  (testing "an unregistered keyword target surfaces as :error, not a throw"
    (let [result (rf.story.ui.explain-panel/explain-for :story.nope/missing)]
      (is (nil? (:explain result)))
      (is (not (str/blank? (:error result)))))))

;; ---------------------------------------------------------------------------
;; explain-for — the ambient arg layers
;;
;; The panel is scenario-facing, so it shows the args the variant actually
;; renders with: global-args beneath the story's `:args` beneath the
;; variant's own. Pinned in both directions.
;; ---------------------------------------------------------------------------

(deftest explain-for-folds-story-and-global-args
  (let [globals-before (rf.story.config/get-global-args)]
    (rf.story.registrar/clear-all!)
    (try
      (rf.story.registrar/reg-story*   :story.explain.args {:args {:heading "Sign in"}})
      (rf.story.registrar/reg-variant* :story.explain.args/inherits {})
      (rf.story.registrar/reg-variant* :story.explain.args/overrides {:args {:heading "Register"}})
      (testing "a variant with no args of its own shows its STORY's args"
        (let [{:keys [explain]} (rf.story.ui.explain-panel/explain-for
                                  :story.explain.args/inherits)]
          (is (= {:heading "Sign in"} (:args explain)))
          (is (= {:heading "Sign in"} (:effective-args explain)))))
      (testing "a variant's OWN args still override its story's"
        (let [{:keys [explain]} (rf.story.ui.explain-panel/explain-for
                                  :story.explain.args/overrides)]
          (is (= {:heading "Register"} (:args explain)))
          (is (= {:heading "Register"} (:effective-args explain)))))
      (testing "global-args sit beneath the story layer"
        (rf.story.config/set-global-args! {:theme :dark :heading "global"})
        (is (= {:theme :dark :heading "Sign in"}
               (get-in (rf.story.ui.explain-panel/explain-for :story.explain.args/inherits)
                       [:explain :effective-args]))))
      (finally
        (rf.story.config/set-global-args! globals-before)
        (rf.story.registrar/clear-all!)))))

;; ---------------------------------------------------------------------------
;; string shapers
;; ---------------------------------------------------------------------------

(deftest chain-label-joins-with-arrows
  (is (= ":story.cp/base  →  :story.cp/child"
         (rf.story.ui.explain-panel/chain-label [:story.cp/base :story.cp/child]))))

(deftest conflict-summary-names-winner-and-losers
  (is (= (str ":fx-overrides / :rf.http/managed — :variant wins over :frag/auth"
              " (variant-owned-wins) = :variant-stub")
         (rf.story.ui.explain-panel/conflict-summary
           (first (:strict-conflicts full-explain))))
      "names the field and key, the winner over the fully qualified loser,
       the rule, and the winning value"))
