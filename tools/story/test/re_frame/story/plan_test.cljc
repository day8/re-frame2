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
            [re-frame.story.assertions :as assertions]
            [re-frame.story.fingerprint :as fingerprint]
            [re-frame.story.plan :as plan]
            [re-frame.story.sub-overrides :as sub-overrides]))

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

(deftest inline-map-target-without-variant-id-compiles
  (testing "a map target with NO :variant/id is allowed (spec: :variant/id
            optional) and compiles to a plan whose :variant/id is nil
            (rf2-8e2nd — variant-plan reads (:variant/id target), nil when
            absent)"
    (let [p (plan/variant-plan {:setup  [[:dispatch [:a]]]
                                :script [[:dispatch [:b]]]})]
      (is (nil? (:variant/id p))
          "the optional :variant/id resolves to nil")
      (is (= [[:dispatch [:a]]] (get-in p [:world :setup])))
      (is (= [[:dispatch [:b]]] (:script p)))
      (is (= [nil] (:source-chain p))
          "the single anonymous body is the whole source chain"))))

;; ---- :story/id stamp — plan-hash's cross-story collision guard (rf2-xk8oz4)

(deftest compiled-plan-is-stamped-with-parent-story-id
  (testing "a registered variant's compiled plan carries :story/id (the
            variant's namespace) — fingerprint's plan-hash-input-keys
            includes :story/id SPECIFICALLY so two variants under
            different stories with identical bodies do not collide, but
            compile-body never stamped it (rf2-xk8oz4): select-keys
            silently dropped the absent key, so plan-hash was actually
            taken over [:world :script :expect :required-runner :tags]
            alone"
    (let [m {:story.counter/at-five
             {:setup  [[:dispatch [:counter/init 5]]]
              :script [[:dispatch [:counter/inc]]]}}
          p (plan-of :story.counter/at-five m)]
      (is (= :story.counter (:story/id p))
          "the parent story id is derived from the variant id's namespace")))
  (testing "an inline map target with NO :variant/id stamps no :story/id
            (nothing to derive it from — render-transparent)"
    (let [p (plan/variant-plan {:setup [[:dispatch [:a]]]})]
      (is (not (contains? p :story/id))))))

(deftest identical-variant-bodies-under-different-stories-do-not-collide
  (testing "rf2-xk8oz4 — two variants with STRUCTURALLY IDENTICAL bodies
            (same :world / :script / :expect / :required-runner / :tags)
            registered under DIFFERENT parent stories compile to DIFFERENT
            plan-hashes, driving the REAL compiler (not a hand-stamped
            plan — the test gap the bead called out). Pre-fix, compile-body
            never populated :story/id, so plan-hash's :story/id input was
            always absent and these collided."
    (let [body {:setup      [[:dispatch [:counter/init 5]]]
                :script     [[:dispatch [:counter/inc]]]
                :assertions [[:rf.assert/path-equals [:count] 6]]}
          m    {:story.a/same body :story.b/same body}
          pa   (plan-of :story.a/same m)
          pb   (plan-of :story.b/same m)]
      (is (= :story.a (:story/id pa)))
      (is (= :story.b (:story/id pb)))
      ;; every plan-hash-input-keys slot but :story/id is identical
      (is (= (:world pa) (:world pb)))
      (is (= (:script pa) (:script pb)))
      (is (= (:expect pa) (:expect pb)))
      (is (= (:required-runner pa) (:required-runner pb)))
      (is (= (:tags pa) (:tags pb)))
      (is (not= (fingerprint/plan-hash pa) (fingerprint/plan-hash pb))
          "the cross-story collision guard is now LIVE — identical bodies
           under different stories hash DIFFERENTLY"))))

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

(deftest args-and-argtypes-resolve-through-extends
  (testing "args + argtypes deep-merge root→child via the dedicated merge-key
            path (rf2-xutlo — they were dropped from context-keys; the
            merge-key deep-merge is the single source of truth, so this must
            still resolve correctly through :extends)"
    (let [m {:story.at/parent
             {:args     {:sku "A" :qty 1}
              :argtypes {:sku {:control :text} :qty {:control :number}}}
             :story.at/child
             {:extends  :story.at/parent
              :args     {:qty 5}                       ; child overrides qty
              :argtypes {:qty {:control :range}}}}     ; child overrides qty argtype
          p (plan-of :story.at/child m)]
      (testing "args deep-merge: inherited :sku kept, child :qty wins"
        (is (= {:sku "A" :qty 5} (get-in p [:world :args]))))
      (testing "argtypes deep-merge the same way"
        (is (= {:sku {:control :text} :qty {:control :range}}
               (get-in p [:world :argtypes]))))
      (testing "explain still surfaces the resolved args"
        (is (= {:sku "A" :qty 5} (get-in p [:explain :args])))))))

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
  (testing "shipping :events lowers to [:world :setup], bare event vectors
            lifting to tagged [:dispatch …] (rf2-5x1wt.17 migration
            normalization — bare shorthand is the migration form, not the
            P1 public grammar)"
    (let [m {:story.legacy/e {:events [[:counter/init 3]]}}
          p (plan-of :story.legacy/e m)]
      (is (= [[:dispatch [:counter/init 3]]] (get-in p [:world :setup])))))
  (testing "already-tagged setup steps round-trip unchanged"
    (let [m {:story.legacy/e2 {:setup [[:dispatch [:counter/init 3]]
                                       [:dispatch-sync [:counter/seed]]]}}
          p (plan-of :story.legacy/e2 m)]
      (is (= [[:dispatch [:counter/init 3]]
              [:dispatch-sync [:counter/seed]]]
             (get-in p [:world :setup]))))))

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

(deftest dom-setup-step-requires-dom-token
  (testing "a DOM SETUP step alone lifts the required-runner to include :dom
            — isolating the setup contribution (rf2-8e2nd). The script is
            DOM-free and there are NO DOM assertions, so :dom can ONLY have
            come from the [:click …] step in :setup (requirements walks
            `(map step-tokens setup)`)."
    (let [m {:story.r/ds
             {:setup  [[:click "[data-test=open]"]]
              :script [[:dispatch [:a]]]}}
          p (plan-of :story.r/ds m)]
      (is (contains? (:required-runner p) :dom)
          ":dom is contributed by the setup step alone")
      (is (contains? (:required-runner p) :app-db)
          "the DOM-free :dispatch script still contributes :app-db"))))

(deftest required-runner-unions-across-every-auto-run-play
  (testing "a NON-first :auto-run? true play whose step lifts capability
            (a :click DOM step) is unioned into :required-runner — not
            just the primary (first) play's tokens (rf2-m0cge5 finding 10).
            Before the fix, `:required-runner` was computed over the
            primary `:script` (the first play) alone even though
            `[:world :scripts]` retains every play and the runtime
            auto-runs each `:auto-run? true` one; `:auto` runner-selection
            trusts `:required-runner` verbatim, so it could pick a
            headless runner unable to execute the second play's DOM step
            — a spurious mid-run failure instead of an honest
            `:cannot-run` refusal at selection time."
    (let [m {:story.r/multi-autorun
             {:plays [{:name "first"  :auto-run? true
                       :script [[:dispatch [:a]]]}
                      {:name "second" :auto-run? true
                       :script [[:click "[data-test=go]"]]}]}}
          p (plan-of :story.r/multi-autorun m)]
      (is (contains? (:required-runner p) :dom)
          "the SECOND auto-run play's :click step lifts :required-runner
           to :dom, even though the first play never touches the DOM")
      (is (contains? (:required-runner p) :app-db)
          "the first play's :dispatch still contributes :app-db")))
  (testing "a play with :auto-run? false is NOT unioned — it never
            executes automatically, so its capability tokens correctly
            stay out of :required-runner"
    (let [m {:story.r/manual-dom
             {:plays [{:name "auto"   :auto-run? true
                       :script [[:dispatch [:a]]]}
                      {:name "manual" :auto-run? false
                       :script [[:click "[data-test=go]"]]}]}}
          p (plan-of :story.r/manual-dom m)]
      (is (not (contains? (:required-runner p) :dom))
          "the manually-triggered play's DOM step never auto-runs, so it
           does not lift :required-runner"))))

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
  (testing ":rf/props wins over :schema; :spec is DEAD (dropped post-M-54)"
    (let [props  [:map [:a :string]]
          spec   [:map [:b :string]]
          schema [:map [:c :string]]]
      (testing ":rf/props chosen when present (canonical, wins over :schema)"
        (is (= props (plan/view-args-schema {:rf/props props :schema schema}))))
      (testing ":schema chosen when no :rf/props (the post-M-54 location)"
        (is (= schema (plan/view-args-schema {:schema schema}))))
      (testing ":spec is NOT a resolution key — a view carrying ONLY :spec
                resolves no schema (the framework reads :schema only post-M-54;
                see migration §M-54). rf2-ayu6n: the stale :spec key is gone."
        (is (nil? (plan/view-args-schema {:spec spec}))))
      (testing ":rf/props still wins even when a dead :spec is also present"
        (is (= props (plan/view-args-schema {:rf/props props :spec spec :schema schema}))))
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
      (testing "the two contracts are not conflated — the view-args schema's
                map-entry keys are the explicit-arg keys only, never a
                sub-override query vector (rf2-p5ivc nit: the prior
                set-membership assertion here was tautological)"
        ;; A sub-override key is a QUERY VECTOR (`[:widget/state]`); the
        ;; view-args schema's entries are scalar arg keys (`:label`). Pull
        ;; the schema's entry keys and assert the sub-override query vector
        ;; (and its sub-id) are absent — the real conflation guard.
        (let [schema     (get-in p [:world :view-args-schema])
              entry-keys (set (map first (drop 1 schema)))]
          (is (= #{:label} entry-keys))
          (is (not (contains? entry-keys [:widget/state])))
          (is (not (contains? entry-keys :widget/state))))))))

;; ===========================================================================
;; View-state subscription overrides (rf2-5x1wt.13)
;; ===========================================================================
;;
;; `:sub-overrides` is the third, lower-fidelity rung of the fidelity
;; ladder — a map of exact subscription query vectors → data values the
;; renderer surfaces for view-state / design exploration. The compiler
;; resolves `[:arg key]` placeholders in the VALUES, validates each
;; resolved value against the subscription's OUTPUT schema (distinct from
;; the view-arg schema), lowers the map to `[:world :render
;; :sub-overrides]`, and marks `:fidelity`. These tests run host-free by
;; threading an explicit `:sub-lookup` map of {sub-id → sub-meta}.

(deftest sub-overrides-lower-and-mark-fidelity
  (testing "a view-state variant renders with exact query-vector overrides + :fidelity"
    (let [m {:story.login/error
             {:args          {:message "Invalid password"}
              :sub-overrides {[:login/state]    :error
                              [:login/error]    [:arg :message]
                              [:login/attempts] 1}}}
          p (plan/variant-plan :story.login/error {:lookup m})]
      (testing "overrides lower to [:world :render :sub-overrides] with exact query vectors"
        (is (= {[:login/state]    :error
                [:login/error]    "Invalid password"   ; [:arg :message] resolved
                [:login/attempts] 1}
               (get-in p [:world :render :sub-overrides]))))
      (testing ":fidelity carries :sub-overrides (and no :real-setup — pure design variant)"
        (is (= #{:sub-overrides} (get-in p [:world :fidelity]))))
      (testing "explain surfaces the resolved overrides + fidelity (overrides were used)"
        (is (= {[:login/state]    :error
                [:login/error]    "Invalid password"
                [:login/attempts] 1}
               (get-in p [:explain :sub-overrides :overrides])))
        (is (= #{:sub-overrides} (get-in p [:explain :fidelity]))))
      (testing "the [:arg] substitution into an override value is recorded"
        (is (some #(= {:key :message :value "Invalid password"} %)
                  (get-in p [:explain :substitutions])))))))

(deftest fidelity-ladder-real-setup-vs-overrides
  (testing "a setup-driven variant is :real-setup; adding overrides marks both rungs"
    (let [m {:story.f/setup-only
             {:setup [[:dispatch-sync [:counter/init 5]]]}
             :story.f/hybrid
             {:setup         [[:dispatch-sync [:counter/init 5]]]
              :sub-overrides {[:counter/badge] :hot}}
             :story.f/bare
             {:component :views/x}}]
      (testing "setup-only → #{:real-setup}, no :sub-overrides rung"
        (is (= #{:real-setup} (get-in (plan/variant-plan :story.f/setup-only {:lookup m})
                                      [:world :fidelity]))))
      (testing "setup + overrides → both rungs"
        (is (= #{:real-setup :sub-overrides}
               (get-in (plan/variant-plan :story.f/hybrid {:lookup m})
                       [:world :fidelity]))))
      (testing "a bare render-as-mounted variant carries no :fidelity slot"
        (is (nil? (get-in (plan/variant-plan :story.f/bare {:lookup m})
                          [:world :fidelity])))))))

(deftest sub-override-missing-arg-fails-plan-construction
  (testing "a missing arg in an override value FAILS plan construction"
    (let [m {:story.login/oops
             {:args          {} ; :message not declared
              :sub-overrides {[:login/error] [:arg :message]}}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
            #"story-missing-arg"
            (plan/variant-plan :story.login/oops {:lookup m}))))))

(deftest sub-override-output-schema-mismatch-fails-before-render
  (testing "an override value violating the sub's OUTPUT schema fails plan construction"
    (let [m {:story.login/bad
             {:sub-overrides {[:login/attempts] "not-an-int"}}}
          ;; the sub carries an output schema on its :sub registrar :schema slot
          sub-lookup {:login/attempts {:schema [:int]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
            #"story-sub-override-invalid"
            (plan/variant-plan :story.login/bad
                               {:lookup        m
                                :sub-lookup    sub-lookup
                                :validator-fns malli-validator})))))
  (testing "a value that SATISFIES the output schema compiles cleanly"
    (let [m {:story.login/ok {:sub-overrides {[:login/attempts] 3}}}
          sub-lookup {:login/attempts {:schema [:int]}}
          p (plan/variant-plan :story.login/ok
                               {:lookup        m
                                :sub-lookup    sub-lookup
                                :validator-fns malli-validator})]
      (is (= :ok (get-in p [:explain :sub-overrides :validation :status])))
      (is (= 3 (get-in p [:world :render :sub-overrides [:login/attempts]])))))
  (testing "a sub with no output schema soft-passes (the host-free floor)"
    (let [m {:story.login/noschema {:sub-overrides {[:login/state] :whatever}}}
          p (plan/variant-plan :story.login/noschema
                               {:lookup        m
                                :sub-lookup    {} ; no metadata for any sub
                                :validator-fns malli-validator})]
      (is (= :sub-overrides (first (get-in p [:world :fidelity]))))
      (is (= :whatever (get-in p [:world :render :sub-overrides [:login/state]])))))
  (testing "with no validator threaded, output-schema checking soft-passes"
    ;; The JVM-default path (no malli validator) checks shape only at the
    ;; renderer — a malformed value is not caught at plan construction.
    (let [m {:story.login/nov {:sub-overrides {[:login/attempts] "nope"}}}
          sub-lookup {:login/attempts {:schema [:int]}}
          p (plan/variant-plan :story.login/nov {:lookup m :sub-lookup sub-lookup})]
      (is (= "nope" (get-in p [:world :render :sub-overrides [:login/attempts]]))))))

(deftest sub-overrides-compose-through-fragments
  (testing "a composed fragment contributes :sub-overrides; variant chain wins per key"
    (let [frag {:fragment/error-state {:sub-overrides {[:login/state] :error
                                                       [:login/code]  500}}}
          m    {:story.login/composed
                {:compose       [:fragment/error-state]
                 :sub-overrides {[:login/code] 503}}} ; variant overrides the key
          p (plan/variant-plan :story.login/composed
                               {:lookup          m
                                :fragment-lookup frag})]
      (is (= {[:login/state] :error
              [:login/code]  503}              ; variant value wins over the fragment
             (get-in p [:world :render :sub-overrides])))
      (is (= #{:sub-overrides} (get-in p [:world :fidelity]))))))

;; ---- :db-seed — the MIDDLE fidelity rung (rf2-blw1q) ---------------------
;;
;; `:db-seed` is the schema-checked direct app-db seed. The compiler accepts
;; it (the schema no longer silently ignores it), lowers it to `[:world
;; :db-seed]` (`[:arg]` placeholders substituted, fragments + the parent
;; chain composed), and marks `[:world :fidelity]` with `:db-seed`. The
;; seeded-app-db schema validation is a RUN-TIME check (runtime_test) — it
;; needs the frame's registered app-db schemas.

(deftest db-seed-lowers-and-marks-fidelity
  (testing "a :db-seed variant lowers to [:world :db-seed] and marks the rung"
    (let [m {:story.cart/seeded
             {:db-seed {:cart {:items [{:sku "A" :qty 2}]}
                        :user/id 42}}}
          p (plan/variant-plan :story.cart/seeded {:lookup m})]
      (testing "the seed lowers verbatim to the world slot"
        (is (= {:cart {:items [{:sku "A" :qty 2}]} :user/id 42}
               (get-in p [:world :db-seed]))))
      (testing ":fidelity carries :db-seed (and no :real-setup — a pure seed variant)"
        (is (= #{:db-seed} (get-in p [:world :fidelity]))))
      (testing "explain surfaces the resolved seed + fidelity"
        (is (= {:cart {:items [{:sku "A" :qty 2}]} :user/id 42}
               (get-in p [:explain :db-seed :seed])))
        (is (= #{:db-seed} (get-in p [:explain :fidelity])))))))

(deftest db-seed-author-no-longer-silently-ignored
  (testing "an author's :db-seed is accepted by the schema (not dropped) and lowers"
    ;; The Variant schema validates the body at registration; reg-variant
    ;; would reject an unknown-shape :db-seed. Compiling the body proves the
    ;; slot is accepted AND survives into the plan (the pre-rf2-blw1q bug was
    ;; silent-accept-then-silent-ignore).
    (let [m {:story.x/seed {:db-seed {:k 1}}}
          p (plan/variant-plan :story.x/seed {:lookup m})]
      (is (= {:k 1} (get-in p [:world :db-seed])))
      (is (contains? (get-in p [:world :fidelity]) :db-seed)))))

(deftest db-seed-arg-substitution
  (testing "[:arg key] placeholders in a seed value resolve before lowering"
    (let [m {:story.cart/argseed
             {:args    {:qty 7}
              :db-seed {:cart {:qty [:arg :qty]}}}}
          p (plan/variant-plan :story.cart/argseed {:lookup m})]
      (is (= {:cart {:qty 7}} (get-in p [:world :db-seed])))
      (is (some #(= {:key :qty :value 7} %) (get-in p [:explain :substitutions]))))))

(deftest db-seed-inherits-through-extends
  (testing "a child :db-seed deep-merges over the parent's seed (context flows down)"
    (let [m {:story.p/base  {:db-seed {:cart {:items []} :flags {:a true}}}
             :story.p/child {:extends :story.p/base
                             :db-seed {:cart {:items [1 2]}}}}
          p (plan/variant-plan :story.p/child {:lookup m})]
      ;; deep-merge: child wins :cart, parent's :flags survives.
      (is (= {:cart {:items [1 2]} :flags {:a true}}
             (get-in p [:world :db-seed])))
      (is (= #{:db-seed} (get-in p [:world :fidelity]))))))

(deftest db-seed-composes-through-fragments
  (testing "a composed fragment contributes :db-seed; the variant wins per key"
    (let [frag {:fragment/seed {:db-seed {:cart {:items []} :session :guest}}}
          m    {:story.cart/composed
                {:compose [:fragment/seed]
                 :db-seed {:session :member}}} ; variant overrides the key
          p (plan/variant-plan :story.cart/composed
                               {:lookup m :fragment-lookup frag})]
      (is (= {:cart {:items []} :session :member}
             (get-in p [:world :db-seed])))
      (is (= #{:db-seed} (get-in p [:world :fidelity]))))))

(deftest db-seed-empty-is-no-rung
  (testing "an empty resolved :db-seed activates no rung + carries no world slot"
    (let [m {:story.cart/emptyseed {:db-seed {}}}
          p (plan/variant-plan :story.cart/emptyseed {:lookup m})]
      (is (nil? (get-in p [:world :db-seed])))
      (is (nil? (get-in p [:world :fidelity]))))))

(deftest compute-fidelity-three-rungs
  (testing "compute-fidelity computes each of the three rungs independently"
    (is (= #{} (plan/compute-fidelity {})))
    (is (= #{:real-setup}
           (plan/compute-fidelity {:setup [[:dispatch [:e]]]})))
    (is (= #{:db-seed}
           (plan/compute-fidelity {:db-seed {:k 1}})))
    (is (= #{:sub-overrides}
           (plan/compute-fidelity {:sub-overrides {[:q] 1}})))
    (testing "an empty seed / override map is treated as absent (no rung)"
      (is (= #{} (plan/compute-fidelity {:db-seed {} :sub-overrides {}}))))
    (testing "all three rungs together"
      (is (= #{:real-setup :db-seed :sub-overrides}
             (plan/compute-fidelity {:setup         [[:dispatch [:e]]]
                                     :db-seed       {:k 1}
                                     :sub-overrides {[:q] 1}}))))))

;; ---- pure resolver: render-path read + sub-assertion honesty -------------

(deftest sub-overrides-render-path-resolver-is-exact
  (testing "resolve returns the override value on an exact query-vector match"
    (let [ovr {[:login/state] :error [:item 7] {:sku "X"}}]
      (is (= :error      (sub-overrides/resolve ovr [:login/state])))
      (is (= {:sku "X"}  (sub-overrides/resolve ovr [:item 7])))))
  (testing "a non-exact query (different args / sub-id) MISSES — no fuzzing"
    (let [ovr {[:item 7] {:sku "X"}}]
      (is (sub-overrides/miss? (sub-overrides/resolve ovr [:item 8])))
      (is (sub-overrides/miss? (sub-overrides/resolve ovr [:item])))
      (is (sub-overrides/miss? (sub-overrides/resolve ovr [:other 7])))))
  (testing "an override whose VALUE is nil is a genuine hit (sentinel is distinct)"
    (let [ovr {[:login/user] nil}]
      (is (sub-overrides/overridden? ovr [:login/user]))
      (is (nil? (sub-overrides/resolve ovr [:login/user])))))
  (testing "read surfaces the override and skips real-read; misses fall through"
    (let [ovr {[:login/state] :error}]
      (is (= :error (sub-overrides/with-overrides* ovr
                      #(sub-overrides/read [:login/state]
                                           (fn [] (throw (ex-info "should not run" {})))))))
      (is (= :real  (sub-overrides/with-overrides* ovr
                      #(sub-overrides/read [:login/other] (fn [] :real))))))))

;; ===========================================================================
;; Assertion-atom fold (rf2-5x1wt.18, spec/017 §Assertions — one atom,
;; two positions)
;; ===========================================================================
;;
;; The fold collapses terminal `:assertions` and EVERY in-script assertion
;; position onto ONE assertion atom. A terminal `:assertions` entry and a
;; script `[:assert …]` entry produce the SAME atom shape; the shipping
;; `:assert-db` / `:assert-dom` sugar folds onto the canonical atoms; an
;; unknown id FAILS plan construction.

;; ---- one atom, two positions ---------------------------------------------

(deftest terminal-and-script-assertion-produce-same-atom-shape
  (testing "a terminal :assertions entry and an in-script [:assert …] entry
           resolve to the IDENTICAL assertion atom (one atom, two positions)"
    (let [atom-v [:rf.assert/path-equals [:n] 0]
          m {:story.counter/checkpoint
             {:script     [[:dispatch [:counter/dec]]
                           [:assert atom-v]]
              :assertions [atom-v]}}
          p (plan-of :story.counter/checkpoint m)
          terminal     (first (get-in p [:expect :assertions]))
          checkpoint   (-> p :script (->> (filter #(= :assert (first %))) first) second)]
      ;; same id, same payload, same vector — no per-position divergence
      (is (= atom-v terminal))
      (is (= atom-v checkpoint))
      (is (= terminal checkpoint)))))

;; ---- :assert-db fold ------------------------------------------------------

(deftest assert-db-folds-to-path-equals
  (testing ":assert-db equality form folds to the canonical [:assert
           [:rf.assert/path-equals …]] checkpoint — same result as authoring
           the atom directly"
    (let [folded   (plan-of :story.x/folded
                            {:story.x/folded {:script [[:assert-db [:count] 6]]}})
          authored (plan-of :story.x/authored
                            {:story.x/authored
                             {:script [[:assert [:rf.assert/path-equals [:count] 6]]]}})]
      (is (= [[:assert [:rf.assert/path-equals [:count] 6]]] (:script folded)))
      ;; the fold emits exactly what the author would have typed by hand
      (is (= (:script authored) (:script folded)))))
  (testing ":assert-db :pred form folds to :rf.assert/path-matches wrapping
           the predicate in a Malli [:fn …] schema (the one canonical way to
           express a predicate against a path)"
    (let [pred even?
          p (plan-of :story.x/pred
                     {:story.x/pred {:script [[:assert-db [:count] :pred pred]]}})
          step (first (:script p))]
      (is (= :assert (first step)))
      (is (= [:rf.assert/path-matches [:count] [:fn pred]] (second step))))))

;; ---- :assert-dom fold + runner requirement -------------------------------

(deftest assert-dom-folds-to-dom-family
  (testing ":assert-dom :visible / :hidden / :text fold onto the DOM
           assertion family (rf2-5x1wt.18, NET-NEW ids)"
    (let [p (plan-of :story.x/dom
                     {:story.x/dom
                      {:script [[:assert-dom "#a" :visible]
                                [:assert-dom "#b" :hidden]
                                [:assert-dom "#c" :text "hello"]]}})]
      (is (= [[:assert [:rf.assert/dom-visible "#a"]]
              [:assert [:rf.assert/dom-hidden "#b"]]
              [:assert [:rf.assert/dom-text "#c" "hello"]]]
             (:script p))))))

(deftest dom-fold-carries-dom-runner-requirement
  (testing "a folded :assert-dom step contributes the :dom capability token to
           :required-runner (the requirement rides the folded id)"
    (let [p (plan-of :story.x/dom-req
                     {:story.x/dom-req {:script [[:assert-dom "#x" :visible]]}})]
      (is (contains? (:required-runner p) :dom))))
  (testing "a headless-only :assert-db fold demands NO :dom token"
    (let [p (plan-of :story.x/db-req
                     {:story.x/db-req {:script [[:assert-db [:n] 1]]}})]
      (is (not (contains? (:required-runner p) :dom))))))

;; ---- unknown assertion ids fail plan construction ------------------------

(deftest unknown-terminal-assertion-id-fails-plan-construction
  (testing "an unknown id in terminal :assertions FAILS plan construction"
    (let [m {:story.x/bad {:assertions [[:rf.assert/typo [:n] 1]]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-unknown-assertion"
            (plan-of :story.x/bad m))))))

(deftest unknown-script-checkpoint-assertion-id-fails-plan-construction
  (testing "an unknown id in an in-script [:assert …] checkpoint FAILS plan
           construction (same id-validation as the terminal position)"
    (let [m {:story.x/bad2 {:script [[:assert [:rf.assert/nope]]]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-unknown-assertion"
            (plan-of :story.x/bad2 m))))))

(deftest unknown-assertion-error-carries-structured-data
  (testing "the :rf.error/story-unknown-assertion ex-data names the bad id"
    (let [m {:story.x/bad3 {:assertions [[:rf.assert/whoops]]}}
          ex (try (plan-of :story.x/bad3 m) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex))
      (is (= :rf.error/story-unknown-assertion (:rf.error/id (ex-data ex))))
      (is (contains? (set (:offending-assertions (ex-data ex)))
                     [:rf.assert/whoops])))))

(deftest every-shipping-and-folded-id-is-known
  (testing "the seven shipping ids + the folded DOM family pass id validation
           (a variant authoring each compiles cleanly)"
    (doseq [atom-v [[:rf.assert/path-equals [:n] 0]
                    [:rf.assert/path-matches [:n] :int]
                    [:rf.assert/sub-equals [:sub/x] 1]
                    [:rf.assert/dispatched? [:e]]
                    [:rf.assert/state-is :m :s]
                    [:rf.assert/no-warnings]
                    [:rf.assert/effect-emitted :fx]
                    [:rf.assert/dom-visible "#x"]
                    [:rf.assert/dom-hidden "#x"]
                    [:rf.assert/dom-text "#x" "t"]]]
      (is (= [atom-v]
             (get-in (plan-of :story.x/ok {:story.x/ok {:assertions [atom-v]}})
                     [:expect :assertions]))
          (str "expected " (pr-str atom-v) " to compile as a known assertion")))))

;; ---- malformed-step rejection before the fold (rf2-zha5z) -----------------
;;
;; The plan compiler folds shipping `:assert-db` / `:assert-dom` steps into
;; the canonical `[:assert …]` checkpoint. The fold helpers assume a
;; well-formed step, so a malformed one MUST be rejected with a structured
;; `:rf.error/story-bad-step` BEFORE the fold — never a raw host exception
;; (IndexOutOfBounds / `No matching clause`). The gate reuses the runner's
;; `validate-script` so the compiler and runtime agree on step shape.

(deftest malformed-assert-dom-mode-rejected-before-fold
  (testing "an :assert-dom step with an unrecognised mode FAILS plan
           construction with a structured story-bad-step (NOT a raw
           `No matching clause` IllegalArgumentException from the fold)"
    (let [m {:story.x/bad-dom {:script [[:assert-dom "#x" :weird]]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-bad-step"
            (plan-of :story.x/bad-dom m))))))

(deftest malformed-assert-db-arity-rejected-before-fold
  (testing "an :assert-db step with too few elements FAILS plan construction
           with a structured story-bad-step (NOT a raw IndexOutOfBounds from
           the fold's nth)"
    (let [m {:story.x/bad-db {:script [[:assert-db [:n]]]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-bad-step"
            (plan-of :story.x/bad-db m))))))

(deftest malformed-step-error-carries-structured-data
  (testing "the :rf.error/story-bad-step ex-data names the offending step"
    (let [m  {:story.x/bad4 {:script [[:assert-db [:n] :pred even? :extra]]}}
          ex (try (plan-of :story.x/bad4 m) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex))
      (is (= :rf.error/story-bad-step (:rf.error/id (ex-data ex))))
      (is (contains? (set (map :step (:offending-steps (ex-data ex))))
                     [:assert-db [:n] :pred even? :extra])))))

(deftest well-formed-assert-steps-still-compile
  (testing "well-formed :assert-db / :assert-dom steps fold cleanly past the
           new shape gate (the gate rejects only malformed steps)"
    (let [folded (plan-of :story.x/ok-mix
                          {:story.x/ok-mix
                           {:script [[:assert-db [:count] 6]
                                     [:assert-db [:n] :pred even?]
                                     [:assert-dom "#a" :visible]
                                     [:assert-dom "#b" :hidden]
                                     [:assert-dom "#c" :text "hi"]]}})]
      (is (= [[:assert [:rf.assert/path-equals [:count] 6]]
              [:assert [:rf.assert/path-matches [:n] [:fn even?]]]
              [:assert [:rf.assert/dom-visible "#a"]]
              [:assert [:rf.assert/dom-hidden "#b"]]
              [:assert [:rf.assert/dom-text "#c" "hi"]]]
             (:script folded))))))

;; ---- pure fold helpers (assertion-ns surface) ----------------------------

(deftest fold-helpers-are-pure-and-position-agnostic
  (testing "assertions/fold-assert-step folds the shipping sugar steps"
    (is (= [:assert [:rf.assert/path-equals [:n] 5]]
           (assertions/fold-assert-step [:assert-db [:n] 5])))
    (is (= [:assert [:rf.assert/dom-visible "#x"]]
           (assertions/fold-assert-step [:assert-dom "#x" :visible]))))
  (testing "fold-assert-step is identity for non-foldable steps"
    (is (= [:dispatch [:e]] (assertions/fold-assert-step [:dispatch [:e]])))
    (is (= [:assert [:rf.assert/no-warnings]]
           (assertions/fold-assert-step [:assert [:rf.assert/no-warnings]])))
    (is (= [:wait 10] (assertions/fold-assert-step [:wait 10]))))
  (testing "known-assertion-ids covers the seven shipping ids + the DOM family"
    (is (every? assertions/assertion-id-known? assertions/canonical-assertion-ids))
    (is (every? assertions/assertion-id-known? assertions/dom-assertion-ids))
    (is (not (assertions/assertion-id-known? :rf.assert/totally-made-up)))))
