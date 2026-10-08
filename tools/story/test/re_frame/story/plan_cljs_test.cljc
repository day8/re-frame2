(ns re-frame.story.plan-cljs-test
  "Tests for the variant-plan compiler + explain base.

  Per `tools/story/spec/017-Testing-Story.md` §Four-bucket authoring
  model. The compiler is a pure
  data → data fn, so every test runs on both the JVM and CLJS without a
  host: variant bodies are supplied through an explicit `:lookup` map of
  RAW bodies, `:extends` included, because the parent-chain resolution
  is the compiler's job (the registrar stores raw bodies too).

  Named `-cljs-test` so the `:node-test` build's `cljs-test$` ns-regexp
  selects it; a plain `-test` name would run it on the JVM only."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [re-frame.story.fingerprint :as rf.story.fingerprint]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.sub-overrides :as rf.story.sub-overrides]))

;; ---- helpers ------------------------------------------------------------

(defn- plan-of
  "Compile a keyword `target` against a raw-body lookup `m`."
  [target m]
  (rf.story.plan/variant-plan target {:lookup m}))

;; ---- normalized defaults --------------------------------------------------

(deftest platforms-default-to-client
  (is (= #{:client}
         (get-in (plan-of :story.x/y {:story.x/y {:setup [[:dispatch [:a]]]}})
                 [:world :platforms]))))

;; ---- :story/id stamp — plan-hash's cross-story collision guard

(deftest identical-variant-bodies-under-different-stories-do-not-collide
  (testing "two variants with structurally identical bodies under different
            parent stories compile to different plan-hashes, through the real
            compiler's :story/id stamp"
    (let [body {:setup      [[:dispatch [:counter/init 5]]]
                :script     [[:dispatch [:counter/inc]]]
                :assertions [[:rf.assert/path-equals [:count] 6]]}
          m    {:story.a/same body :story.b/same body}
          pa   (plan-of :story.a/same m)
          pb   (plan-of :story.b/same m)
          hashed-slots [:world :script :expect :required-runner :tags]]
      (is (= [:story.a :story.b] (mapv :story/id [pa pb])))
      (is (= (select-keys pa hashed-slots) (select-keys pb hashed-slots)))
      (is (not= (rf.story.fingerprint/plan-hash pa) (rf.story.fingerprint/plan-hash pb))))))

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
              :setup [[:dispatch [:cart/add {:sku [:arg :sku] :qty [:arg :qty]}]]]}}
          data (try (plan-of :story.bad/arg m)
                    (catch #?(:clj Exception :cljs :default) e
                      (ex-data e)))]
      (is (= :rf.error/story-missing-arg (:rf.error/id data)))
      (is (= :qty (:arg data)))
      (is (re-find #"qty" (:reason data))))))

(deftest args-and-argtypes-resolve-through-extends
  (testing "args + argtypes deep-merge root→child through :extends"
    (let [m {:story.at/parent
             {:args     {:sku "A" :qty 1}
              :argtypes {:sku {:control :text} :qty {:control :number}}}
             :story.at/child
             {:extends  :story.at/parent
              :args     {:qty 5}
              :argtypes {:qty {:control :range}}}}
          p (plan-of :story.at/child m)]
      (is (= {:sku "A" :qty 5} (get-in p [:world :args])))
      (is (= {:sku {:control :text} :qty {:control :range}}
             (get-in p [:world :argtypes]))))))

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

(deftest extends-depth-cap-fails
  (testing "an :extends chain longer than rf.story.plan/*max-extends-depth* fails plan construction"
    ;; A 50-link chain with no cycle: the depth cap is a separate bound with
    ;; its own error id, and the walk from the leaf hits it first.
    (let [chain-len 50
          m         (into {}
                          (for [i (range chain-len)]
                            (let [this   (keyword "story.chain" (str "n" i))
                                  parent (when (< (inc i) chain-len)
                                           (keyword "story.chain" (str "n" (inc i))))]
                              [this (cond-> {:setup []}
                                      parent (assoc :extends parent))])))]
      (binding [rf.story.plan/*max-extends-depth* 32]
        (is (= :rf.error/story-extends-chain-too-long
               (try (plan-of :story.chain/n0 m)
                    (catch #?(:clj Exception :cljs :default) e
                      (:rf.error/id (ex-data e))))))))))

(deftest unknown-keyword-target-fails
  (testing "a keyword target with no registered body fails"
    (is (= :rf.error/story-unknown-variant
           (try (plan-of :story.none/here {})
                (catch #?(:clj Exception :cljs :default) e
                  (:rf.error/id (ex-data e))))))))

;; ---- shipping-vocabulary normalization ----------------------------------

(deftest bare-setup-steps-lift-to-dispatch
  (testing ":setup lowers to [:world :setup], bare event vectors
            lifting to tagged [:dispatch …] (migration normalization — bare
            shorthand is the migration form, not the P1 public grammar)"
    (let [m {:story.legacy/e {:setup [[:counter/init 3]]}}
          p (plan-of :story.legacy/e m)]
      (is (= [[:dispatch [:counter/init 3]]] (get-in p [:world :setup]))))))

(deftest bare-script-steps-lift-to-dispatch
  (testing ":script lowers to the plan's :script (bare vectors lift to :dispatch)"
    (let [m {:story.legacy/p
             {:script [[:dispatch-sync [:counter/init 3]]
                            [:counter/inc]
                            [:wait 50]]}}
          p (plan-of :story.legacy/p m)]
      (is (= [[:dispatch-sync [:counter/init 3]]
              [:dispatch [:counter/inc]]
              [:wait 50]]
             (:script p))))))

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

;; ---- required-runner -----------------------------------------------------

(deftest dom-setup-step-requires-dom-token
  (testing "a DOM SETUP step alone lifts the required-runner to include :dom:
            the script is DOM-free and there are no DOM assertions"
    (let [m {:story.r/ds
             {:setup  [[:click "[data-test=open]"]]
              :script [[:dispatch [:a]]]}}
          p (plan-of :story.r/ds m)]
      (is (contains? (:required-runner p) :dom)
          ":dom is contributed by the setup step alone")
      (is (contains? (:required-runner p) :app-db)
          "the DOM-free :dispatch script still contributes :app-db"))))

(deftest required-runner-unions-across-every-auto-run-play
  (testing "a NON-first :auto-run? true play whose step lifts capability is
            unioned into :required-runner. `:auto` runner-selection trusts
            the slot verbatim, so missing it would pick a headless runner
            that fails mid-run instead of refusing with :cannot-run."
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
  (testing "explain shows the source chain, parent chain, args, substitutions and step order"
    (let [m {:story.e/parent {:args  {:n 1}
                              :setup [[:dispatch [:seed [:arg :n]]]]}
             :story.e/child  {:extends :story.e/parent
                              :script  [[:dispatch [:go]]]}}
          ex (rf.story.plan/explain :story.e/child {:lookup m})]
      (is (= [:story.e/parent :story.e/child] (:source-chain ex)))
      (is (= [:story.e/parent] (:parent-chain ex)))
      (is (= {:n 1} (:args ex)))
      (is (= [{:key :n :value 1}] (:substitutions ex)))
      (is (= [[:dispatch [:seed 1]]] (:setup-order ex)))
      (is (= [[:dispatch [:go]]] (:script-order ex))))))

;; ===========================================================================
;; View arg schemas (spec §View arg schemas)
;; ===========================================================================
;;
;; A registered view MAY expose an explicit-input (props) schema on its
;; `:view` metadata. The compiler copies it into [:world :view-args-schema],
;; records [:world :effective-args], validates the effective args against
;; the schema before render, and FAILS plan construction on a missing-
;; required or malformed view input. These tests thread an explicit
;; `:view-lookup` (a {view-id → view-meta} map) so they run host-free on
;; both the JVM and CLJS.

(def ^:private malli-validator
  "A `{:validate :explain}` pair backed by Malli, matching the injectable
  shape the renderer threads from the late-bind hook. The required-key
  floor needs no validator."
  {:validate (fn [schema value] (m/validate schema value))
   :explain  (fn [schema value] (m/explain schema value))})

(deftest view-args-schema-copied-into-plan
  (testing "the :component view's props schema is copied to [:world :view-args-schema]"
    (let [view-schema [:map [:label :string] [:count :int]]
          m {:story.widget/ok
             {:component :views/widget
              :args      {:label "Hi" :count 3}}}
          p (rf.story.plan/variant-plan :story.widget/ok
                               {:lookup      m
                                :view-lookup {:views/widget {:rf/props view-schema}}})]
      (is (= view-schema (get-in p [:world :view-args-schema])))
      (testing "effective-args are recorded (the resolved args at plan time)"
        (is (= {:label "Hi" :count 3} (get-in p [:world :effective-args])))))))

(deftest missing-required-arg-fails-before-render
  (testing "a missing required view input FAILS plan construction, reporting
            the missing key, its schema path and the source variant"
    (let [m {:story.widget/missing
             {:component :views/widget
              :args      {:label "Hi"}}}   ; :count required, absent
          opts {:lookup      m
                :view-lookup {:views/widget {:rf/props [:map [:label :string] [:count :int]]}}}
          data (try (rf.story.plan/variant-plan :story.widget/missing opts)
                    (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
      (is (= {:rf.error/id :rf.error/story-view-args-invalid
              :variant/id  :story.widget/missing
              :missing     [{:key :count :schema :int :path [:count]}]}
             (select-keys data [:rf.error/id :variant/id :missing]))))))

(deftest optional-arg-may-be-absent
  (testing "an entry marked {:optional true} is NOT a required input"
    (let [m {:story.widget/opt
             {:component :views/widget
              :args      {:label "Hi"}}}
          p (rf.story.plan/variant-plan :story.widget/opt
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
                :validator-fns malli-validator}
          data (try (rf.story.plan/variant-plan :story.widget/bad opts)
                    (catch #?(:clj Exception :cljs :default) e (ex-data e)))
          bad  (first (:malformed data))]
      (is (= {:rf.error/id :rf.error/story-view-args-invalid :variant/id :story.widget/bad}
             (select-keys data [:rf.error/id :variant/id])))
      (is (= {:key :count :path [:count] :value "three"}
             (select-keys bad [:key :path :value])))
      (is (some? (:explain bad)) "carries the validator explanation"))))

;; ===========================================================================
;; View-state subscription overrides
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
          p (rf.story.plan/variant-plan :story.login/error {:lookup m})]
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
        (is (= #{:real-setup} (get-in (rf.story.plan/variant-plan :story.f/setup-only {:lookup m})
                                      [:world :fidelity]))))
      (testing "setup + overrides → both rungs"
        (is (= #{:real-setup :sub-overrides}
               (get-in (rf.story.plan/variant-plan :story.f/hybrid {:lookup m})
                       [:world :fidelity]))))
      (testing "a bare render-as-mounted variant carries no :fidelity slot"
        (is (nil? (get-in (rf.story.plan/variant-plan :story.f/bare {:lookup m})
                          [:world :fidelity])))))))

(deftest sub-override-output-schema-mismatch-fails-before-render
  (testing "an override value violating the sub's OUTPUT schema fails plan construction"
    (let [m {:story.login/bad
             {:sub-overrides {[:login/attempts] "not-an-int"}}}
          ;; the sub carries an output schema on its :sub registrar :schema slot
          sub-lookup {:login/attempts {:schema [:int]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
            #"story-sub-override-invalid"
            (rf.story.plan/variant-plan :story.login/bad
                               {:lookup        m
                                :sub-lookup    sub-lookup
                                :validator-fns malli-validator})))))
  (testing "a value that SATISFIES the output schema compiles cleanly"
    (let [m {:story.login/ok {:sub-overrides {[:login/attempts] 3}}}
          sub-lookup {:login/attempts {:schema [:int]}}
          p (rf.story.plan/variant-plan :story.login/ok
                               {:lookup        m
                                :sub-lookup    sub-lookup
                                :validator-fns malli-validator})]
      (is (= :ok (get-in p [:explain :sub-overrides :validation :status])))
      (is (= 3 (get-in p [:world :render :sub-overrides [:login/attempts]])))))
  (testing "a sub with no output schema soft-passes (the host-free floor)"
    (let [m {:story.login/noschema {:sub-overrides {[:login/state] :whatever}}}
          p (rf.story.plan/variant-plan :story.login/noschema
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
          p (rf.story.plan/variant-plan :story.login/nov {:lookup m :sub-lookup sub-lookup})]
      (is (= "nope" (get-in p [:world :render :sub-overrides [:login/attempts]]))))))

(deftest sub-overrides-compose-through-fragments
  (testing "a composed fragment contributes :sub-overrides; variant chain wins per key"
    (let [frag {:fragment/error-state {:sub-overrides {[:login/state] :error
                                                       [:login/code]  500}}}
          m    {:story.login/composed
                {:compose       [:fragment/error-state]
                 :sub-overrides {[:login/code] 503}}} ; variant overrides the key
          p (rf.story.plan/variant-plan :story.login/composed
                               {:lookup          m
                                :fragment-lookup frag})]
      (is (= {[:login/state] :error
              [:login/code]  503}              ; variant value wins over the fragment
             (get-in p [:world :render :sub-overrides]))))))

(deftest sub-overrides-same-key-child-replaces-parent-through-extends
  (testing "a child overriding the SAME query key REPLACES the parent's pinned
            value: the variant chain wins per query key, exactly as
            :compose resolves it. A deep merge would pin a value neither
            author wrote — here {:items [1 2] :total 2 :status :empty}."
    (let [m {:story.cart/full  {:sub-overrides {[:cart/summary] {:items [1 2] :total 2}
                                                [:cart/open?]   true}}
             :story.cart/empty {:extends       :story.cart/full
                                :sub-overrides {[:cart/summary] {:status :empty}}}}
          p (rf.story.plan/variant-plan :story.cart/empty {:lookup m})]
      (is (= {[:cart/summary] {:status :empty}   ; child's value, wholesale
              [:cart/open?]   true}              ; untouched parent key inherited
             (get-in p [:world :render :sub-overrides]))))))

;; ---- :db-seed — the MIDDLE fidelity rung ---------------------------------
;;
;; `:db-seed` is the schema-checked direct app-db seed. The compiler lowers
;; it to `[:world :db-seed]` (`[:arg]` placeholders substituted, fragments +
;; the parent chain composed) and marks `[:world :fidelity]` with
;; `:db-seed`. The seeded-app-db schema validation is a RUN-TIME check — it
;; needs the frame's registered app-db schemas.

(deftest db-seed-lowers-and-marks-fidelity
  (testing "a :db-seed variant lowers to [:world :db-seed] and marks the rung"
    (let [m {:story.cart/seeded
             {:db-seed {:cart {:items [{:sku "A" :qty 2}]}
                        :user/id 42}}}
          p (rf.story.plan/variant-plan :story.cart/seeded {:lookup m})]
      (testing "the seed lowers verbatim to the world slot"
        (is (= {:cart {:items [{:sku "A" :qty 2}]} :user/id 42}
               (get-in p [:world :db-seed]))))
      (testing ":fidelity carries :db-seed (and no :real-setup — a pure seed variant)"
        (is (= #{:db-seed} (get-in p [:world :fidelity]))))
      (testing "explain surfaces the resolved seed + fidelity"
        (is (= {:cart {:items [{:sku "A" :qty 2}]} :user/id 42}
               (get-in p [:explain :db-seed :seed])))
        (is (= #{:db-seed} (get-in p [:explain :fidelity])))))))

(deftest db-seed-arg-substitution
  (testing "[:arg key] placeholders in a seed value resolve before lowering"
    (let [m {:story.cart/argseed
             {:args    {:qty 7}
              :db-seed {:cart {:qty [:arg :qty]}}}}
          p (rf.story.plan/variant-plan :story.cart/argseed {:lookup m})]
      (is (= {:cart {:qty 7}} (get-in p [:world :db-seed])))
      (is (some #(= {:key :qty :value 7} %) (get-in p [:explain :substitutions]))))))

(deftest db-seed-inherits-through-extends
  (testing "a child :db-seed deep-merges over the parent's seed (context flows down)"
    (let [m {:story.p/base  {:db-seed {:cart {:items []} :flags {:a true}}}
             :story.p/child {:extends :story.p/base
                             :db-seed {:cart {:items [1 2]}}}}
          p (rf.story.plan/variant-plan :story.p/child {:lookup m})]
      ;; deep-merge: child wins :cart, parent's :flags survives.
      (is (= {:cart {:items [1 2]} :flags {:a true}}
             (get-in p [:world :db-seed]))))))

(deftest db-seed-composes-through-fragments
  (testing "a composed fragment contributes :db-seed; the variant wins per key"
    (let [frag {:fragment/seed {:db-seed {:cart {:items []} :session :guest}}}
          m    {:story.cart/composed
                {:compose [:fragment/seed]
                 :db-seed {:session :member}}} ; variant overrides the key
          p (rf.story.plan/variant-plan :story.cart/composed
                               {:lookup m :fragment-lookup frag})]
      (is (= {:cart {:items []} :session :member}
             (get-in p [:world :db-seed]))))))

;; ---- pure resolver: render-path read + sub-assertion honesty -------------

(deftest sub-overrides-render-path-resolver-is-exact
  (testing "resolve returns the override value on an exact query-vector match"
    (let [ovr {[:login/state] :error [:item 7] {:sku "X"}}]
      (is (= :error      (rf.story.sub-overrides/resolve ovr [:login/state])))
      (is (= {:sku "X"}  (rf.story.sub-overrides/resolve ovr [:item 7])))))
  (testing "a non-exact query (different args / sub-id) MISSES — no fuzzing"
    (let [ovr {[:item 7] {:sku "X"}}]
      (is (rf.story.sub-overrides/miss? (rf.story.sub-overrides/resolve ovr [:item 8])))
      (is (rf.story.sub-overrides/miss? (rf.story.sub-overrides/resolve ovr [:item])))
      (is (rf.story.sub-overrides/miss? (rf.story.sub-overrides/resolve ovr [:other 7])))))
  (testing "an override whose VALUE is nil is a genuine hit (sentinel is distinct)"
    (let [ovr {[:login/user] nil}]
      (is (rf.story.sub-overrides/overridden? ovr [:login/user]))
      (is (nil? (rf.story.sub-overrides/resolve ovr [:login/user])))))
  (testing "read surfaces the override and skips real-read; misses fall through"
    (let [ovr {[:login/state] :error}]
      (is (= :error (rf.story.sub-overrides/with-overrides* ovr
                      #(rf.story.sub-overrides/read [:login/state]
                                           (fn [] (throw (ex-info "should not run" {})))))))
      (is (= :real  (rf.story.sub-overrides/with-overrides* ovr
                      #(rf.story.sub-overrides/read [:login/other] (fn [] :real))))))))

;; ===========================================================================
;; Assertion-atom fold (spec/017 §Assertions — one atom, two positions)
;; ===========================================================================
;;
;; The fold collapses terminal `:assertions` and EVERY in-script assertion
;; position onto ONE assertion atom: the shipping `:assert-db` /
;; `:assert-dom` sugar folds onto the canonical atoms; an unknown id FAILS
;; plan construction.

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
           assertion family (ids beyond the seven shipping ones)"
    (let [p (plan-of :story.x/dom
                     {:story.x/dom
                      {:script [[:assert-dom "#a" :visible]
                                [:assert-dom "#b" :hidden]
                                [:assert-dom "#c" :text "hello"]]}})]
      (is (= [[:assert [:rf.assert/dom-visible "#a"]]
              [:assert [:rf.assert/dom-hidden "#b"]]
              [:assert [:rf.assert/dom-text "#c" "hello"]]]
             (:script p))))))

;; ---- unknown assertion ids fail plan construction ------------------------

(deftest unknown-assertion-error-carries-structured-data
  (testing "the :rf.error/story-unknown-assertion ex-data names the bad id"
    (let [m {:story.x/bad3 {:assertions [[:rf.assert/whoops]]}}
          ex (try (plan-of :story.x/bad3 m) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (= :rf.error/story-unknown-assertion (:rf.error/id (ex-data ex))))
      (is (contains? (set (:offending-assertions (ex-data ex)))
                     [:rf.assert/whoops])))))

;; ---- :require-cause? opt validation ---------------------------------------
;;
;; `:require-cause? false` is the ONE opt-out that lets
;; `:rf.assert/no-cascade-rerender` evaluate its `[0,0]` default vacuously
;; when its named cause was not observed. The plan compiler rejects the key
;; on `:rf.assert/caused` and a non-boolean value on either id, BEFORE any
;; run.

(deftest non-boolean-require-cause-fails-plan-construction
  (testing "a non-boolean :require-cause? on :no-cascade-rerender FAILS —
           the opt is a strict boolean, never silently coerced"
    (let [m {:story.x/rc-bad
             {:assertions [[:rf.assert/no-cascade-rerender
                            {:event :e :require-cause? :nope}]]}}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-bad-assertion-opt"
            (plan-of :story.x/rc-bad m))))))

(deftest bad-assertion-opt-error-carries-structured-data
  (testing "the :rf.error/story-bad-assertion-opt ex-data names the bad atom"
    (let [m  {:story.x/rc-data
              {:assertions [[:rf.assert/caused {:event :e :require-cause? false}]]}}
          ex (try (plan-of :story.x/rc-data m) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (= :rf.error/story-bad-assertion-opt (:rf.error/id (ex-data ex))))
      (is (contains? (set (:offending-assertions (ex-data ex)))
                     [:rf.assert/caused {:event :e :require-cause? false}])))))

(deftest legal-require-cause-false-compiles
  (testing "{:require-cause? false} on :no-cascade-rerender is the sole legal
           use and compiles cleanly (terminal AND in-script)"
    (let [atom-v [:rf.assert/no-cascade-rerender {:event :e :require-cause? false}]]
      (is (= [atom-v]
             (get-in (plan-of :story.x/ok-rc {:story.x/ok-rc {:assertions [atom-v]}})
                     [:expect :assertions]))
          "terminal position compiles")
      (is (= [[:assert atom-v]]
             (:script (plan-of :story.x/ok-rc2
                               {:story.x/ok-rc2 {:script [[:assert atom-v]]}})))
          "in-script checkpoint compiles")))

  (testing "a plain :require-cause? true also compiles (the explicit default)"
    (let [atom-v [:rf.assert/no-cascade-rerender {:event :e :require-cause? true}]]
      (is (= [atom-v]
             (get-in (plan-of :story.x/ok-rc3 {:story.x/ok-rc3 {:assertions [atom-v]}})
                     [:expect :assertions]))))))

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

;; ---- malformed-step rejection before the fold -----------------------------
;;
;; The fold helpers assume a well-formed step, so a malformed `:assert-db` /
;; `:assert-dom` MUST be rejected with a structured `:rf.error/story-bad-step`
;; BEFORE the fold — never a raw host exception (IndexOutOfBounds / `No
;; matching clause`). The gate reuses the runner's `validate-script` so the
;; compiler and runtime agree on step shape.

(deftest malformed-assert-steps-are-rejected-with-a-structured-error-before-the-fold
  (doseq [[label step]
          [["an :assert-dom step with an unrecognised mode (NOT a raw `No
             matching clause` IllegalArgumentException from the fold)"
            [:assert-dom "#x" :weird]]
           ["an :assert-db step with too few elements (NOT a raw
             IndexOutOfBounds from the fold's nth)"
            [:assert-db [:n]]]
           ["an :assert-db step with trailing junk"
            [:assert-db [:n] :pred even? :extra]]]]
    (testing label
      (let [data (try (plan-of :story.x/bad-step {:story.x/bad-step {:script [step]}}) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                        (ex-data e)))]
        (is (= :rf.error/story-bad-step (:rf.error/id data)))
        (is (contains? (set (map :step (:offending-steps data))) step)
            "the ex-data names the offending step")))))
