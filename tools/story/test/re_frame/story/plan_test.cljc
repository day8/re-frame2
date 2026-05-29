(ns re-frame.story.plan-test
  "Tests for the variant-plan compiler + explain base (rf2-5x1wt.10).

  Per `tools/story/spec/017-Testing-Story.md` §Four-bucket authoring
  model + `ai/findings/NewTestStory` §B1. The compiler is a pure
  data → data fn, so every test runs on both the JVM and CLJS without a
  host: variant bodies are supplied through an explicit `:lookup` map of
  RAW bodies (the parent-chain resolution is the compiler's job, so the
  test bodies still carry `:extends` rather than relying on the
  registrar's eager merge)."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [re-frame.story.plan :as plan]))

;; ---- helpers ------------------------------------------------------------

(defn- plan-of
  "Compile a keyword `target` against a raw-body lookup `m`."
  [target m]
  (plan/variant-plan target {:lookup m}))

;; ---- simple variant compiles --------------------------------------------

(deftest simple-variant-compiles
  (testing "a simple variant compiles to a plan with the four-bucket shape"
    (let [m {:story.counter/at-five
             {:setup      [[:dispatch [:counter/init 5]]]
              :script     [[:dispatch [:counter/inc]]]
              :assertions [[:rf.assert/path-equals [:count] 6]]}}
          p (plan-of :story.counter/at-five m)]
      (is (= :story.counter/at-five (:variant/id p)))
      (is (contains? p :world))
      (is (contains? p :script))
      (is (contains? p :expect))
      (is (= [[:dispatch [:counter/init 5]]] (get-in p [:world :setup])))
      (is (= [[:dispatch [:counter/inc]]] (:script p)))
      (is (= [[:rf.assert/path-equals [:count] 6]]
             (get-in p [:expect :assertions]))))))

(deftest normalized-plan-has-world-script-expect
  (testing "normalized plan always carries :world / :script / :expect"
    (let [m {:story.x/y {:setup [[:dispatch [:a]]]}}
          p (plan-of :story.x/y m)]
      (is (vector? (get-in p [:world :setup])))
      (is (vector? (:script p)))
      (is (map? (:expect p)))
      (is (= #{:client} (get-in p [:world :platforms])))
      ;; setup-only variant: no script
      (is (= [] (:script p))))))

;; ---- inline map target ---------------------------------------------------

(deftest inline-map-target-compiles
  (testing "a map target compiles the same way as a registered keyword"
    (let [p (plan/variant-plan {:variant/id :story.inline/v
                                :setup  [[:dispatch [:a]]]
                                :script [[:dispatch [:b]]]})]
      (is (= :story.inline/v (:variant/id p)))
      (is (= [[:dispatch [:a]]] (get-in p [:world :setup])))
      (is (= [[:dispatch [:b]]] (:script p))))))

;; ---- args + [:arg key] substitution -------------------------------------

(deftest variant-with-args-compiles
  (testing "args resolve and [:arg key] placeholders substitute"
    (let [m {:story.cart/add
             {:args   {:sku "A" :qty 2}
              :setup  [[:dispatch [:cart/add {:sku [:arg :sku] :qty [:arg :qty]}]]]
              :script [[:dispatch [:cart/touch {:sku [:arg :sku]}]]]}}
          p (plan-of :story.cart/add m)]
      (is (= {:sku "A" :qty 2} (get-in p [:world :args])))
      (is (= [[:dispatch [:cart/add {:sku "A" :qty 2}]]]
             (get-in p [:world :setup])))
      (is (= [[:dispatch [:cart/touch {:sku "A"}]]] (:script p)))
      (testing "explain records the substitutions"
        (let [subs (get-in p [:explain :substitutions])]
          (is (= #{{:key :sku :value "A"} {:key :qty :value 2}}
                 (set subs))))))))

(deftest missing-arg-fails
  (testing "a [:arg key] referencing an undeclared arg fails plan construction"
    (let [m {:story.bad/arg
             {:args  {:sku "A"}
              :setup [[:dispatch [:cart/add {:sku [:arg :sku] :qty [:arg :qty]}]]]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-missing-arg"
            (plan-of :story.bad/arg m)))
      (let [data (try (plan-of :story.bad/arg m)
                      (catch #?(:clj Exception :cljs :default) e
                        (ex-data e)))]
        (is (= :rf.error/story-missing-arg (:rf.error/id data)))
        (is (= :qty (:arg data)))
        (is (re-find #"qty" (:reason data)))))))

;; ---- parent chain (:extends) --------------------------------------------

(deftest variant-with-parent-compiles
  (testing "context inherits root→child; setup appends; script/assertions are child-only"
    (let [m {:story.login/filled
             {:args       {:user "ann"}
              :setup      [[:dispatch [:auth/fill {:user [:arg :user]}]]]
              :assertions [[:rf.assert/path-equals [:auth :state] :filled]]
              :tags       #{:test}}
             :story.login/error-after-submit
             {:extends    :story.login/filled
              :script     [[:dispatch [:auth/login-pressed]]]
              :assertions [[:rf.assert/path-equals [:auth :state] :error]]}}
          p (plan-of :story.login/error-after-submit m)]
      (testing "source chain is root-first"
        (is (= [:story.login/filled :story.login/error-after-submit]
               (:source-chain p))))
      (testing "context (args) inherits"
        (is (= {:user "ann"} (get-in p [:world :args]))))
      (testing "setup inherited from parent (substituted with inherited arg)"
        (is (= [[:dispatch [:auth/fill {:user "ann"}]]]
               (get-in p [:world :setup]))))
      (testing "script is child-only"
        (is (= [[:dispatch [:auth/login-pressed]]] (:script p))))
      (testing "terminal assertions are child-only (verdict is local)"
        (is (= [[:rf.assert/path-equals [:auth :state] :error]]
               (get-in p [:expect :assertions]))))
      (testing "tags are additive"
        (is (= #{:test} (:tags p)))))))

(deftest parent-setup-appends-before-child-setup
  (testing "parent + child :setup APPEND in root→child order (silent-regression site)"
    (let [m {:story.s/parent {:setup [[:dispatch [:p1]] [:dispatch [:p2]]]}
             :story.s/child  {:extends :story.s/parent
                              :setup   [[:dispatch [:c1]]]}}
          p (plan-of :story.s/child m)]
      (is (= [[:dispatch [:p1]] [:dispatch [:p2]] [:dispatch [:c1]]]
             (get-in p [:world :setup]))))))

(deftest missing-parent-fails
  (testing "an :extends referencing an unregistered variant fails"
    (let [m {:story.x/child {:extends :story.x/ghost}}]
      (is (= :rf.error/story-extends-unknown
             (try (plan-of :story.x/child m)
                  (catch #?(:clj Exception :cljs :default) e
                    (:rf.error/id (ex-data e)))))))))

(deftest extends-cycle-fails
  (testing "an :extends cycle fails plan construction"
    (let [m {:story.c/a {:extends :story.c/b}
             :story.c/b {:extends :story.c/a}}]
      (is (= :rf.error/story-extends-cycle
             (try (plan-of :story.c/a m)
                  (catch #?(:clj Exception :cljs :default) e
                    (:rf.error/id (ex-data e)))))))))

(deftest unknown-keyword-target-fails
  (testing "a keyword target with no registered body fails"
    (is (= :rf.error/story-unknown-variant
           (try (plan-of :story.none/here {})
                (catch #?(:clj Exception :cljs :default) e
                  (:rf.error/id (ex-data e))))))))

;; ---- shipping-vocabulary normalization ----------------------------------

(deftest events-normalizes-to-world-setup
  (testing "shipping :events lowers to [:world :setup]"
    (let [m {:story.legacy/e {:events [[:counter/init 3]]}}
          p (plan-of :story.legacy/e m)]
      (is (= [[:counter/init 3]] (get-in p [:world :setup]))))))

(deftest play-script-normalizes-to-script
  (testing "shipping :play-script lowers to :script (bare vectors lift to :dispatch)"
    (let [m {:story.legacy/p
             {:play-script [[:dispatch-sync [:counter/init 3]]
                            [:counter/inc]
                            [:wait 50]]}}
          p (plan-of :story.legacy/p m)]
      (is (= [[:dispatch-sync [:counter/init 3]]
              [:dispatch [:counter/inc]]
              [:wait 50]]
             (:script p))))))

(deftest play-script-map-form-normalizes
  (testing ":play-script map form lowers its :script"
    (let [m {:story.legacy/pm
             {:play-script {:script [[:dispatch [:a]]] :auto-run? false}}}
          p (plan-of :story.legacy/pm m)]
      (is (= [[:dispatch [:a]]] (:script p))))))

(deftest plays-preserved-as-named-scripts
  (testing ":plays normalizes to the primary :script and preserves all named scripts"
    (let [m {:story.legacy/multi
             {:plays [{:name "happy" :script [[:dispatch [:h]]]}
                      {:name "sad"   :script [[:dispatch [:s]]]}]}}
          p (plan-of :story.legacy/multi m)]
      (testing "primary script is the first play"
        (is (= [[:dispatch [:h]]] (:script p))))
      (testing "all named scripts preserved under [:world :scripts]"
        (is (= ["happy" "sad"] (mapv :name (get-in p [:world :scripts]))))
        (is (= [[[:dispatch [:h]]] [[:dispatch [:s]]]]
               (mapv :script (get-in p [:world :scripts]))))))))

;; ---- checks (inheritable) ------------------------------------------------

(deftest checks-inherit-through-extends
  (testing "checks are the inheritable expectation form (root→child)"
    (let [m {:story.k/parent {:checks [:check/no-runtime-errors]}
             :story.k/child  {:extends :story.k/parent
                              :checks  [:check/extra]}}
          p (plan-of :story.k/child m)]
      (is (= [:check/no-runtime-errors :check/extra]
             (get-in p [:expect :checks]))))))

;; ---- required-runner -----------------------------------------------------

(deftest headless-variant-needs-no-tokens
  (testing "an app-db-only variant requires only :app-db (resolves to :headless)"
    (let [m {:story.r/h
             {:setup      [[:dispatch [:a]]]
              :assertions [[:rf.assert/path-equals [:x] 1]]}}
          p (plan-of :story.r/h m)]
      (is (= #{:app-db} (:required-runner p))))))

(deftest dom-step-requires-dom-token
  (testing "a DOM script step lifts the required-runner to include :dom"
    (let [m {:story.r/d
             {:script [[:dispatch [:a]] [:click "[data-test=go]"]]}}
          p (plan-of :story.r/d m)]
      (is (contains? (:required-runner p) :dom))
      (is (contains? (:required-runner p) :app-db)))))

;; ---- explain -------------------------------------------------------------

(deftest explain-includes-source-chain-and-substitutions
  (testing "explain shows the source chain, parent chain, merge decisions, and substitutions"
    (let [m {:story.e/parent {:args  {:n 1}
                              :setup [[:dispatch [:seed [:arg :n]]]]}
             :story.e/child  {:extends :story.e/parent
                              :script  [[:dispatch [:go]]]}}
          ex (plan/explain :story.e/child {:lookup m})]
      (is (= [:story.e/parent :story.e/child] (:source-chain ex)))
      (is (= [:story.e/parent] (:parent-chain ex)))
      (is (= {:n 1} (:args ex)))
      (is (= [{:key :n :value 1}] (:substitutions ex)))
      (is (= [[:dispatch [:seed 1]]] (:setup-order ex)))
      (is (= [[:dispatch [:go]]] (:script-order ex)))
      ;; rf2-5x1wt.15 — the merge vocabulary names the inherited / compose /
      ;; own layering now that `:compose` lands between the parent merge and
      ;; the variant-owned values. Setup appends inherited→compose→own;
      ;; script appends through `:compose` only, never `:extends`.
      (is (= :append-inherited-compose-own (get-in ex [:merge :setup])))
      (is (= :compose-then-child (get-in ex [:merge :script])))
      (testing "no :compose on a plain :extends variant"
        (is (= [] (:compose ex)))
        (is (= [] (:strict-conflicts ex)))))))

;; ===========================================================================
;; View arg schemas (rf2-5x1wt.12 — spec §View arg schemas)
;; ===========================================================================
;;
;; A registered view MAY expose an explicit-input (props) schema on its
;; `:view` metadata. The compiler copies it into [:world :view-args-schema],
;; records [:world :effective-args], validates the effective args against
;; the schema before render, and FAILS plan construction on a missing-
;; required or malformed view input. These tests thread an explicit
;; `:view-lookup` (a {view-id → view-meta} map) so they run host-free on
;; both the JVM and CLJS. The `:component` arg-lookup precedence verifies
;; the live-framework key resolution (`:rf/props` → `:spec` → `:schema`).

(def ^:private malli-validator
  "A `{:validate :explain}` pair backed by Malli (on Story's classpath),
  matching the injectable shape the renderer threads from the late-bind
  hook. Used for the malformed-value tests; the required-key floor needs
  no validator."
  {:validate (fn [schema value] (m/validate schema value))
   :explain  (fn [schema value] (m/explain schema value))})

(deftest view-args-schema-copied-into-plan
  (testing "the :component view's props schema is copied to [:world :view-args-schema]"
    (let [view-schema [:map [:label :string] [:count :int]]
          m {:story.widget/ok
             {:component :views/widget
              :args      {:label "Hi" :count 3}}}
          p (plan/variant-plan :story.widget/ok
                               {:lookup      m
                                :view-lookup {:views/widget {:rf/props view-schema}}})]
      (is (= view-schema (get-in p [:world :view-args-schema])))
      (testing "effective-args are recorded (the resolved args at plan time)"
        (is (= {:label "Hi" :count 3} (get-in p [:world :effective-args])))))))

(deftest valid-effective-args-render
  (testing "valid effective-args compile cleanly (no plan failure)"
    (let [m {:story.widget/valid
             {:component :views/widget
              :args      {:label "Hi" :count 3}}}
          p (plan/variant-plan :story.widget/valid
                               {:lookup      m
                                :view-lookup {:views/widget
                                              {:rf/props [:map [:label :string] [:count :int]]}}
                                :validator-fns malli-validator})]
      (is (= :story.widget/valid (:variant/id p)))
      (is (= :ok (get-in p [:explain :view-args-validation :status]))))))

(deftest missing-required-arg-fails-before-render
  (testing "a missing required view input FAILS plan construction"
    (let [m {:story.widget/missing
             {:component :views/widget
              :args      {:label "Hi"}}}   ; :count required, absent
          opts {:lookup      m
                :view-lookup {:views/widget {:rf/props [:map [:label :string] [:count :int]]}}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-view-args-invalid"
            (plan/variant-plan :story.widget/missing opts)))
      (let [data (try (plan/variant-plan :story.widget/missing opts)
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
        (is (= :rf.error/story-view-args-invalid (:rf.error/id data)))
        (testing "the failure reports the missing key, schema path, and source variant"
          (is (= :story.widget/missing (:variant/id data)))
          (is (= [{:key :count :schema :int :path [:count]}] (:missing data))))))))

(deftest optional-arg-may-be-absent
  (testing "an entry marked {:optional true} is NOT a required input"
    (let [m {:story.widget/opt
             {:component :views/widget
              :args      {:label "Hi"}}}
          p (plan/variant-plan :story.widget/opt
                               {:lookup      m
                                :view-lookup {:views/widget
                                              {:rf/props [:map
                                                          [:label :string]
                                                          [:count {:optional true} :int]]}}})]
      (is (= {:label "Hi"} (get-in p [:world :effective-args])))
      (is (= :ok (get-in p [:explain :view-args-validation :status]))))))

(deftest malformed-arg-reports-schema-path-and-source-variant
  (testing "a malformed value FAILS with the schema path + source variant"
    (let [m {:story.widget/bad
             {:component :views/widget
              :args      {:label "Hi" :count "three"}}}  ; :count must be :int
          opts {:lookup        m
                :view-lookup   {:views/widget {:rf/props [:map [:label :string] [:count :int]]}}
                :validator-fns malli-validator}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-view-args-invalid"
            (plan/variant-plan :story.widget/bad opts)))
      (let [data (try (plan/variant-plan :story.widget/bad opts)
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))
            bad  (first (:malformed data))]
        (is (= :rf.error/story-view-args-invalid (:rf.error/id data)))
        (is (= :story.widget/bad (:variant/id data))
            "the failure carries the source variant")
        (is (= :count (:key bad)))
        (is (= [:count] (:path bad)) "reports the Malli schema path")
        (is (= "three" (:value bad)))
        (is (some? (:explain bad)) "carries the validator explanation")))))

(deftest no-view-schema-no-validation
  (testing "a view with no props schema leaves the plan unvalidated (slots absent)"
    (let [m {:story.widget/none
             {:component :views/widget
              :args      {:anything 1}}}
          p (plan/variant-plan :story.widget/none
                               {:lookup      m
                                :view-lookup {:views/widget {}}})]  ; no schema slot
      (is (nil? (get-in p [:world :view-args-schema])))
      (is (nil? (get-in p [:explain :view-args-validation])))
      (testing "effective-args still recorded"
        (is (= {:anything 1} (get-in p [:world :effective-args])))))))

(deftest no-component-no-validation
  (testing "a variant with no :component is never view-validated"
    (let [m {:story.plain/v {:args {:x 1} :setup [[:dispatch [:a]]]}}
          p (plan/variant-plan :story.plain/v {:lookup m})]
      (is (nil? (get-in p [:world :view-args-schema])))
      (is (= {:x 1} (get-in p [:world :effective-args]))))))

(deftest schema-key-precedence
  (testing ":rf/props wins over :spec which wins over :schema"
    (let [props  [:map [:a :string]]
          spec   [:map [:b :string]]
          schema [:map [:c :string]]]
      (testing ":rf/props chosen when present"
        (is (= props (plan/view-args-schema {:rf/props props :spec spec :schema schema}))))
      (testing ":spec chosen when no :rf/props (the live Spec 010 slot)"
        (is (= spec (plan/view-args-schema {:spec spec :schema schema}))))
      (testing ":schema chosen as the last-resort alias"
        (is (= schema (plan/view-args-schema {:schema schema}))))
      (testing "nil when no schema slot present"
        (is (nil? (plan/view-args-schema {:title "x"})))))))

(deftest derived-effective-args-validation-unit
  (testing "validate-effective-args required-key floor needs no validator"
    (let [schema [:map [:label :string] [:count :int]]]
      (testing "all present → :ok"
        (is (= :ok (:status (plan/validate-effective-args
                              schema {:label "x" :count 1})))))
      (testing "missing required → :invalid with the missing entry"
        (let [r (plan/validate-effective-args schema {:label "x"})]
          (is (= :invalid (:status r)))
          (is (= [{:key :count :schema :int :path [:count]}] (:missing r)))
          (is (= [] (:malformed r)) "no malformed without a validator")))
      (testing "malformed value soft-passes without a validator (floor only)"
        ;; :count present but wrong type — with no validator the floor
        ;; can only check presence, so this is :ok at floor level.
        (is (= :ok (:status (plan/validate-effective-args
                              schema {:label "x" :count "nope"})))))
      (testing "malformed value is caught WITH a validator"
        (let [r (plan/validate-effective-args
                  schema {:label "x" :count "nope"} malli-validator)]
          (is (= :invalid (:status r)))
          (is (= :count (:key (first (:malformed r))))))))))

(deftest view-args-boundary-is-distinct-from-sub-overrides
  (testing "view-args schema validates explicit args; :sub-overrides ride a separate slot"
    (let [m {:story.boundary/v
             {:component     :views/widget
              :args          {:label "Hi"}
              :sub-overrides {[:widget/state] :error}}}
          p (plan/variant-plan :story.boundary/v
                               {:lookup      m
                                :view-lookup {:views/widget {:rf/props [:map [:label :string]]}}})]
      (testing "the view-args schema covers explicit args only"
        (is (= [:map [:label :string]] (get-in p [:world :view-args-schema]))))
      (testing ":sub-overrides lower to their own [:world :render :sub-overrides] slot"
        (is (= {[:widget/state] :error}
               (get-in p [:world :render :sub-overrides]))))
      (testing "the two contracts are not conflated — sub-overrides are NOT in view-args-schema"
        (is (not (contains? (set (get-in p [:world :view-args-schema]))
                            [:widget/state])))))))
