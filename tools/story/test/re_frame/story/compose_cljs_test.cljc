(ns re-frame.story.compose-cljs-test
  "Tests for strict `:compose` composition — fragments, checks, total merge
  order, variant-owned-wins, and the silent-conflict failure.

  Per `tools/story/spec/017-Testing-Story.md` §`:compose` / §Merge rules /
  §Conflict resolution. The compiler is a
  pure data → data fn, so every test runs on both the JVM and CLJS without
  a host: fragment / check / variant bodies are supplied through explicit
  `:lookup` / `:fragment-lookup` / `:check-lookup` maps of RAW bodies.

  Named `-cljs-test` so the `:node-test` build's `cljs-test$` ns-regexp
  selects it; a bare `-test` name would run it on the JVM only."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ---- helpers ------------------------------------------------------------

(defn- compose-plan
  "Compile a keyword `target` with explicit fragment + check lookup maps.
  `opts` defaults: `:lookup variants`, `:fragment-lookup fragments`,
  `:check-lookup checks`."
  [target {:keys [variants fragments checks]
           :or   {variants {} fragments {} checks {}}}]
  (rf.story.plan/variant-plan target {:lookup          variants
                             :fragment-lookup fragments
                             :check-lookup    checks}))

;; ===========================================================================
;; Fragment composition — setup + script append in declared order
;; ===========================================================================

(deftest fragment-setup-composes
  (testing "a composed fragment's :setup appends before the variant's own setup"
    (let [fragments {:fragment.cart/with-sku
                     {:args  {:sku "A"}
                      :setup [[:dispatch [:cart/add {:sku [:arg :sku]}]]]}}
          variants  {:story.checkout/submits
                     {:compose [:fragment.cart/with-sku]
                      :setup   [[:dispatch [:checkout/open]]]}}
          p (compose-plan :story.checkout/submits
                          {:variants variants :fragments fragments})]
      (testing "fragment setup lands first, variant setup after (declared order)"
        (is (= [[:dispatch [:cart/add {:sku "A"}]]
                [:dispatch [:checkout/open]]]
               (get-in p [:world :setup]))))
      (testing "fragment args deep-merge into the effective args"
        (is (= {:sku "A"} (get-in p [:world :args])))))))

(deftest two-fragments-compose-in-declared-order
  (testing "two composed fragments' setup + script append in declared order"
    (let [fragments {:fragment/a {:setup  [[:dispatch [:a-setup]]]
                                  :script [[:dispatch [:a-script]]]}
                     :fragment/b {:setup  [[:dispatch [:b-setup]]]
                                  :script [[:dispatch [:b-script]]]}}
          variants  {:story.x/v {:compose [:fragment/a :fragment/b]
                                 :script  [[:dispatch [:own]]]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (= [[:dispatch [:a-setup]] [:dispatch [:b-setup]]]
             (get-in p [:world :setup])))
      (is (= [[:dispatch [:a-script]] [:dispatch [:b-script]] [:dispatch [:own]]]
             (:script p))))))

;; ===========================================================================
;; Composed-fragment :script must be EXECUTED, not just reported. The
;; runtime drives `[:world :scripts]` (the named-play set), NEVER the
;; reported top-level `:script` slot — so a `compose-script` folding only
;; into the latter would leave a composed fragment's script unrun while
;; rf.story.plan/explain looked right.
;; ===========================================================================

(deftest fragment-script-composes-into-executed-scripts
  (testing "a composed fragment's :script appends into the EXECUTED
            [:world :scripts] primary play, not just the reported
            top-level :script"
    (let [fragments {:fragment.checkout/ready-to-submit
                     {:script [[:dispatch [:checkout/open]]]}}
          variants  {:story.checkout/submits
                     {:compose [:fragment.checkout/ready-to-submit]
                      :script  [[:dispatch [:checkout/submit]]]}}
          p (compose-plan :story.checkout/submits
                          {:variants variants :fragments fragments})
          scripts (get-in p [:world :scripts])]
      (is (= 1 (count scripts)) "one primary auto-run play")
      (is (true? (:auto-run? (first scripts))))
      (is (= [[:dispatch [:checkout/open]] [:dispatch [:checkout/submit]]]
             (:script (first scripts)))
          "the executed play's script carries the composed fragment's
           script FIRST, then the variant's own — matching the reported
           top-level :script exactly")
      (is (= (:script (first scripts)) (:script p))
          "[:world :scripts]'s primary play and the reported top-level
           :script never diverge"))))

(deftest fragment-script-with-no-variant-script-still-executes
  (testing "a variant that composes a fragment's :script but authors NO
            script of its own still gets an executed primary play (not
            an empty [:world :scripts])"
    (let [fragments {:fragment/only-script
                     {:script [[:dispatch [:seed-only]]]}}
          variants  {:story.x/no-own-script
                     {:compose [:fragment/only-script]}}
          p (compose-plan :story.x/no-own-script
                          {:variants variants :fragments fragments})
          scripts (get-in p [:world :scripts])]
      (is (= 1 (count scripts)))
      (is (true? (:auto-run? (first scripts))))
      (is (= [[:dispatch [:seed-only]]] (:script (first scripts)))))))

;; ===========================================================================
;; Composed-fragment :loaders / :loaders-teardown / :decorators
;;
;; The Fragment schema permits these three slots, so `ctx` (the per-field
;; `:extends`-chain merge) reads frag-layers for them; skipping them would
;; silently drop them from every composed variant.
;; ===========================================================================

(deftest fragment-loaders-and-teardown-append-ahead-of-the-variants-own
  (are [slot fragment own expected]
       (= expected
          (get-in (compose-plan :story.x/v
                                {:variants  {:story.x/v (merge {:compose [:fragment/socket]} own)}
                                 :fragments {:fragment/socket fragment}})
                  [:world slot]))
    :loaders          {:loaders [[:socket/open]]}           {:loaders [[:socket/subscribe]]}
                      [[:socket/open] [:socket/subscribe]]
    :loaders-teardown {:loaders-teardown [[:socket/close]]} {:loaders-teardown [[:socket/unsubscribe]]}
                      [[:socket/close] [:socket/unsubscribe]]
    ;; a fragment's :loaders fold in even when the variant declares none of its own
    :loaders          {:loaders [[:socket/open]]}           {}
                      [[:socket/open]]))

(deftest fragment-decorators-compose-in-globals-story-fragment-variant-order
  (testing "a composed fragment's :decorators fold into [:world :decorators]
            BETWEEN the ambient (globals) layer and the variant chain's own
            decorators (globals -> story -> fragment -> variant)"
    (let [fragments {:fragment/themed {:decorators [:decorator/fragment-theme]}}
          variants  {:story.x/v {:compose    [:fragment/themed]
                                 :decorators [:decorator/variant-theme]}}
          p (rf.story.plan/variant-plan :story.x/v
              {:lookup            variants
               :fragment-lookup   fragments
               :global-decorators [:decorator/global]})]
      (is (= [:decorator/global :decorator/fragment-theme :decorator/variant-theme]
             (get-in p [:world :decorators]))))))

;; ===========================================================================
;; Check composition + identity preservation
;; ===========================================================================

(deftest compose-mixes-fragments-and-checks-in-declared-order
  (testing "a :compose list interleaving fragments + checks resolves each kind"
    (let [fragments {:fragment/seed {:setup [[:dispatch [:seed]]]}}
          checks    {:check/clean {:assertions [[:rf.assert/no-warnings]]}
                     :check/own   {:assertions [[:rf.assert/no-warnings]]}}
          variants  {:story.x/v {:compose [:fragment/seed :check/clean]
                                 :checks  [:check/own]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments :checks checks})]
      (is (= [[:dispatch [:seed]]] (get-in p [:world :setup])))
      (testing "own checks come first (inherited+own), composed checks append"
        (is (= [:check/own :check/clean] (get-in p [:expect :checks]))))
      (is (= [{:kind :fragment :id :fragment/seed}
              {:kind :check :id :check/clean}]
             (get-in p [:explain :compose]))))))

;; ---- check identity is a SET: a check rides :expect :checks ONCE ---------
;; (A check inherited + re-composed, or composed twice, must not
;;  appear N times; the runner would otherwise expand it into N duplicate
;;  grouped check records for one logical check id.)

(deftest a-check-rides-expect-checks-once-in-first-seen-order
  (let [checks {:check/clean {:assertions [[:rf.assert/no-warnings]]}
                :check/a     {:assertions [[:rf.assert/no-warnings]]}
                :check/b     {:assertions [[:rf.assert/no-warnings]]}}]
    (doseq [[label variants vid expected]
            [["a check both INHERITED (via :extends) and re-named in :compose rides once"
              {:story.k/parent {:checks [:check/clean]}
               :story.k/child  {:extends :story.k/parent :compose [:check/clean]}}
              :story.k/child [:check/clean]]
             ["a check named twice in one :compose list rides once"
              {:story.k/dup {:compose [:check/clean :check/clean]}}
              :story.k/dup [:check/clean]]
             ["dedup keeps first-seen order: own :check/a first, then the new
               composed :check/b — :check/a not repeated"
              {:story.k/order {:checks [:check/a] :compose [:check/b :check/a]}}
              :story.k/order [:check/a :check/b]]]]
      (testing label
        (let [p (compose-plan vid {:variants variants :checks checks})]
          (is (= expected (get-in p [:expect :checks]) (get-in p [:explain :checks]))))))))

;; ===========================================================================
;; :extends inheritance — checks inherit; assertions + script do not
;; ===========================================================================

(deftest parent-check-inherits
  (testing "a parent variant's :checks inherit to the child (inheritable form)"
    (let [variants {:story.k/parent {:checks [:check/no-runtime-errors]}
                    :story.k/child  {:extends :story.k/parent
                                     :checks  [:check/extra]}}
          ;; Every :checks id must resolve.
          checks   {:check/no-runtime-errors {:assertions [[:rf.assert/no-warnings]]}
                    :check/extra             {:assertions [[:rf.assert/no-warnings]]}}
          p (compose-plan :story.k/child {:variants variants :checks checks})]
      (is (= [:check/no-runtime-errors :check/extra]
             (get-in p [:expect :checks]))))))

(deftest parent-assertion-absent-when-child-silent
  (testing "a child with no :assertions does not pick up the parent's"
    (let [variants {:story.a/parent
                    {:assertions [[:rf.assert/path-equals [:s] :parent]]}
                    :story.a/child {:extends :story.a/parent}}
          p (compose-plan :story.a/child {:variants variants})]
      (is (= [] (get-in p [:expect :assertions]))))))

(deftest parent-script-does-not-inherit
  (testing "a parent variant's :script does NOT inherit through :extends"
    (let [variants {:story.s/parent {:script [[:dispatch [:parent-step]]]}
                    :story.s/child  {:extends :story.s/parent
                                     :script  [[:dispatch [:child-step]]]}}
          p (compose-plan :story.s/child {:variants variants})]
      (is (= [[:dispatch [:child-step]]] (:script p))
          "script is behaviour under test — a child does not silently run the parent's"))))

(deftest extends-context-flows-down-compose-adds-behaviour
  (testing ":extends inherits context; :compose adds behaviour on top"
    (let [fragments {:fragment/prefix {:script [[:dispatch [:prefix]]]}}
          variants  {:story.m/parent {:args  {:env :prod}
                                      :setup [[:dispatch [:parent-setup]]]}
                     :story.m/child  {:extends :story.m/parent
                                      :compose [:fragment/prefix]
                                      :script  [[:dispatch [:child-step]]]}}
          p (compose-plan :story.m/child
                          {:variants variants :fragments fragments})]
      (testing "parent context (args + setup) flows down"
        (is (= {:env :prod} (get-in p [:world :args])))
        (is (= [[:dispatch [:parent-setup]]] (get-in p [:world :setup]))))
      (testing "compose script prefixes the child's own script"
        (is (= [[:dispatch [:prefix]] [:dispatch [:child-step]]] (:script p)))))))

;; ===========================================================================
;; Flat fragments — a fragment composing a fragment FAILS
;; ===========================================================================

(deftest fragment-composing-fragment-fails
  (testing "a composed fragment that itself carries :compose FAILS (P1 flat fragments)"
    (let [fragments {:fragment/leaf   {:setup [[:dispatch [:leaf]]]}
                     :fragment/nested {:compose [:fragment/leaf]
                                       :setup   [[:dispatch [:nested]]]}}
          variants  {:story.x/v {:compose [:fragment/nested]}}
          opts      {:variants variants :fragments fragments}
          data      (try (compose-plan :story.x/v opts)
                          (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
      (is (= :rf.error/story-compose-nested-fragment (:rf.error/id data)))
      (is (= :fragment/nested (:fragment/id data)))
      (is (= :compose (:offending-key data))))))

(deftest fragment-extending-variant-fails
  (testing "a composed fragment carrying :extends also FAILS (flat fragments)"
    (let [fragments {:fragment/bad {:extends :story.x/parent
                                    :setup   [[:dispatch [:bad]]]}}
          variants  {:story.x/v {:compose [:fragment/bad]}}
          opts      {:variants variants :fragments fragments}]
      (is (= :rf.error/story-compose-nested-fragment
             (try (compose-plan :story.x/v opts)
                  (catch #?(:clj Exception :cljs :default) e
                    (:rf.error/id (ex-data e)))))))))

(deftest unknown-compose-id-fails
  (testing "a :compose id naming no registered fragment/check FAILS"
    (let [variants {:story.x/v {:compose [:fragment/ghost]}}
          opts     {:variants variants}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-compose-unknown"
            (compose-plan :story.x/v opts)))
      (is (= :fragment/ghost
             (:compose/id (try (compose-plan :story.x/v opts)
                               (catch #?(:clj Exception :cljs :default) e
                                 (ex-data e)))))))))

;; ===========================================================================
;; Strict-conflict resolution — variant-owned-wins + silent-conflict failure
;; ===========================================================================

(deftest two-fragments-conflict-when-variant-silent
  (testing "two composed fx-overrides on the SAME fx-id, differing, with a silent variant → HARD"
    (let [fragments {:fragment/http-a {:fx-overrides {:rf.http/fetch :stub-a}}
                     :fragment/http-b {:fx-overrides {:rf.http/fetch :stub-b}}}
          variants  {:story.x/v {:compose [:fragment/http-a :fragment/http-b]}}
          opts      {:variants variants :fragments fragments}
          data      (try (compose-plan :story.x/v opts)
                          (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
      (is (= :rf.error/story-compose-conflict (:rf.error/id data)))
      (is (= [{:field   :fx-overrides
               :key     :rf.http/fetch
               :sources [:fragment/http-a :fragment/http-b]
               :values  [:stub-a :stub-b]}]
             (:conflicts data))
          "the conflict names the field, the key, and each source's value in declared order"))))

(deftest identical-fragment-overrides-do-not-conflict
  (testing "two composed fragments setting the SAME fx-id to the SAME value → no conflict"
    (let [fragments {:fragment/http-a {:fx-overrides {:rf.http/fetch :stub}}
                     :fragment/http-b {:fx-overrides {:rf.http/fetch :stub}}}
          variants  {:story.x/v {:compose [:fragment/http-a :fragment/http-b]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (= {:rf.http/fetch :stub}
             (get-in p [:world :frame :fx-overrides]))))))

(deftest fragments-on-different-keys-do-not-conflict
  (testing "fragments overriding DIFFERENT fx-ids both fill in (no conflict)"
    (let [fragments {:fragment/http {:fx-overrides {:rf.http/fetch :http-stub}}
                     :fragment/ws   {:fx-overrides {:ws/send :ws-stub}}}
          variants  {:story.x/v {:compose [:fragment/http :fragment/ws]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (= {:rf.http/fetch :http-stub :ws/send :ws-stub}
             (get-in p [:world :frame :fx-overrides]))))))

(deftest variant-owned-fx-override-wins
  (testing "a variant-owned fx-override wins over composed-fragment overrides"
    (let [fragments {:fragment/http-a {:fx-overrides {:rf.http/fetch :stub-a}}
                     :fragment/http-b {:fx-overrides {:rf.http/fetch :stub-b}}}
          variants  {:story.x/v {:compose      [:fragment/http-a :fragment/http-b]
                                 :fx-overrides {:rf.http/fetch :variant-stub}}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (testing "the variant value wins; fragments do NOT conflict (variant owns the key)"
        (is (= :variant-stub
               (get-in p [:world :frame :fx-overrides :rf.http/fetch]))))
      (testing "explain records the resolved conflict: winner, losing sources, rule"
        (is (= [{:field          :fx-overrides
                 :key            :rf.http/fetch
                 :winner         :variant-stub
                 :winning-source :variant
                 :losing-sources [:fragment/http-a :fragment/http-b]
                 :rule           :variant-owned-wins}]
               (get-in p [:explain :strict-conflicts])))))))

(deftest variant-owned-fills-only-its-key
  (testing "variant-owned-wins is per-KEY: the variant fills its key, the fragment fills the rest"
    (let [fragments {:fragment/http {:fx-overrides {:rf.http/fetch :frag-fetch
                                                    :rf.http/post  :frag-post}}}
          variants  {:story.x/v {:compose      [:fragment/http]
                                 :fx-overrides {:rf.http/fetch :variant-fetch}}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (= {:rf.http/fetch :variant-fetch     ; variant owns this key
              :rf.http/post  :frag-post}         ; fragment fills the rest
             (get-in p [:world :frame :fx-overrides]))))))

(deftest interceptor-overrides-are-strict-too
  (testing ":interceptor-overrides follow the same strict-conflict rule"
    (let [fragments {:fragment/i-a {:interceptor-overrides {:guard :a}}
                     :fragment/i-b {:interceptor-overrides {:guard :b}}}
          variants  {:story.x/v {:compose [:fragment/i-a :fragment/i-b]}}
          opts      {:variants variants :fragments fragments}]
      (is (= :rf.error/story-compose-conflict
             (try (compose-plan :story.x/v opts)
                  (catch #?(:clj Exception :cljs :default) e
                    (:rf.error/id (ex-data e))))))
      (testing "variant-owned interceptor override wins"
        (let [vw {:variants {:story.x/w {:compose [:fragment/i-a :fragment/i-b]
                                         :interceptor-overrides {:guard :v}}}
                  :fragments fragments}
              p  (compose-plan :story.x/w vw)]
          (is (= {:guard :v}
                 (get-in p [:world :frame :interceptor-overrides]))))))))

;; ---- explain :strict-conflicts order is DETERMINISTIC --------------------
;; (Resolution iterates an internal hash-map keyed by the override id;
;;  without a stable key order the :resolved / :unresolved vectors would
;;  follow hash-map iteration order, which differs across CLJS / JVM.
;;  Resolution sorts by key, so explain diffs + error messages reproduce.)

(deftest strict-conflicts-explain-order-is-deterministic
  (testing "the resolved :strict-conflicts vector is in a stable, sorted key order"
    (let [;; a fragment setting MANY fx-ids, all owned by the variant →
          ;; one :variant-owned-wins resolved entry per key. The keys are
          ;; declared OUT of sorted order; the resolved order must still be
          ;; deterministic (sorted), independent of any map iteration order.
          fragments {:fragment/many
                     {:fx-overrides {:rf.http/zeta  :z
                                     :rf.http/alpha :a
                                     :rf.http/mid   :m
                                     :rf.http/beta  :b}}}
          variants  {:story.x/v
                     {:compose      [:fragment/many]
                      :fx-overrides {:rf.http/zeta  :vz
                                     :rf.http/alpha :va
                                     :rf.http/mid   :vm
                                     :rf.http/beta  :vb}}}
          p         (compose-plan :story.x/v {:variants variants :fragments fragments})]
      (is (= [:rf.http/alpha :rf.http/beta :rf.http/mid :rf.http/zeta]
             (mapv :key (get-in p [:explain :strict-conflicts])))
          "every owned key surfaces a resolved entry, in the stable sorted
           order rather than the out-of-order declared one — an exact
           vector on both lanes, so no hash-map iteration order leaks"))))

;; ===========================================================================
;; No `:resolve-conflicts` escape hatch in P1
;; ===========================================================================

(deftest resolve-conflicts-rejected-by-schema
  (testing ":resolve-conflicts is rejected by the P1 variant schema (no escape hatch)"
    (let [explain (rf.story.schemas/validate
                    :variant {:resolve-conflicts {[:fx-overrides :rf.http/fetch] :stub-a}})]
      (is (some? explain) ":resolve-conflicts must fail variant-body validation"))))

;; ===========================================================================
;; Inline plan composing registered fragments/checks
;; ===========================================================================

(deftest inline-plan-composes-registered-fragments
  (testing "an inline map plan MAY compose registered fragments + checks (§Inline plan)"
    (let [fragments {:fragment/seed {:setup [[:dispatch [:seed]]]}}
          checks    {:check/clean {:assertions [[:rf.assert/no-warnings]]}}
          p (rf.story.plan/variant-plan
              {:variant/id :inline/v
               :compose    [:fragment/seed :check/clean]
               :script     [[:dispatch [:go]]]}
              {:fragment-lookup fragments :check-lookup checks})]
      (is (= [[:dispatch [:seed]]] (get-in p [:world :setup])))
      (is (= [[:dispatch [:go]]] (:script p)))
      (is (= [:check/clean] (get-in p [:expect :checks]))))))
