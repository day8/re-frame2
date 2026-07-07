(ns re-frame.story.compose-test
  "Tests for strict `:compose` composition — fragments, checks, total merge
  order, variant-owned-wins, and the silent-conflict failure (rf2-5x1wt.15).

  Per `tools/story/spec/017-Testing-Story.md` §`:compose` / §Merge rules /
  §Conflict resolution + `ai/findings/NewTestStory` §B3. The compiler is a
  pure data → data fn, so every test runs on both the JVM and CLJS without
  a host: fragment / check / variant bodies are supplied through explicit
  `:lookup` / `:fragment-lookup` / `:check-lookup` maps of RAW bodies."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.plan :as plan]
            [re-frame.story.schemas :as schemas]))

;; ---- helpers ------------------------------------------------------------

(defn- compose-plan
  "Compile a keyword `target` with explicit fragment + check lookup maps.
  `opts` defaults: `:lookup variants`, `:fragment-lookup fragments`,
  `:check-lookup checks`."
  [target {:keys [variants fragments checks]
           :or   {variants {} fragments {} checks {}}}]
  (plan/variant-plan target {:lookup          variants
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

(deftest fragment-script-composes
  (testing "a composed fragment's :script appends before the variant's own script"
    (let [fragments {:fragment.checkout/ready-to-submit
                     {:setup  [[:dispatch [:cart/add {:sku "A"}]]]
                      :script [[:dispatch [:checkout/open]]
                               [:dispatch [:checkout/type-address {:postcode "2000"}]]]}}
          variants  {:story.checkout/submits
                     {:compose [:fragment.checkout/ready-to-submit]
                      :script  [[:dispatch [:checkout/submit]]]}}
          p (compose-plan :story.checkout/submits
                          {:variants variants :fragments fragments})]
      (testing "fragment script lands first, variant script after"
        (is (= [[:dispatch [:checkout/open]]
                [:dispatch [:checkout/type-address {:postcode "2000"}]]
                [:dispatch [:checkout/submit]]]
               (:script p))))
      (testing "fragment setup also folds in"
        (is (= [[:dispatch [:cart/add {:sku "A"}]]]
               (get-in p [:world :setup])))))))

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
;; rf2-k23efg — composed-fragment :script must be EXECUTED, not just
;; reported. The runtime drives `[:world :scripts]` (the named-play set),
;; NEVER the reported top-level `:script` slot — before the fix,
;; `compose-script` folded only into the latter, so a composed fragment's
;; script never actually ran (plan/explain looked right; nothing happened).
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
;; rf2-2g7ebs — composed-fragment :loaders / :loaders-teardown / :decorators
;;
;; `ctx` (the per-field `:extends`-chain merge) never read frag-layers for
;; these three slots before the fix — silently dropped from every composed
;; variant even though the Fragment schema permits them.
;; ===========================================================================

(deftest fragment-loaders-compose-and-append
  (testing "a composed fragment's :loaders append BEFORE the variant's own"
    (let [fragments {:fragment/socket {:loaders [[:socket/open]]}}
          variants  {:story.x/v {:compose [:fragment/socket]
                                 :loaders [[:socket/subscribe]]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (= [[:socket/open] [:socket/subscribe]]
             (get-in p [:world :loaders]))))))

(deftest fragment-loaders-teardown-composes-and-appends
  (testing "a composed fragment's :loaders-teardown appends BEFORE the
            variant's own"
    (let [fragments {:fragment/socket {:loaders-teardown [[:socket/close]]}}
          variants  {:story.x/v {:compose          [:fragment/socket]
                                 :loaders-teardown [[:socket/unsubscribe]]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (= [[:socket/close] [:socket/unsubscribe]]
             (get-in p [:world :loaders-teardown]))))))

(deftest fragment-loaders-alone-still-fold-in
  (testing "a fragment's :loaders fold in even when the variant declares
            none of its own"
    (let [fragments {:fragment/socket {:loaders [[:socket/open]]}}
          variants  {:story.x/v {:compose [:fragment/socket]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (= [[:socket/open]] (get-in p [:world :loaders]))))))

(deftest fragment-decorators-compose-in-globals-story-fragment-variant-order
  (testing "a composed fragment's :decorators fold into [:world :decorators]
            BETWEEN the ambient (globals) layer and the variant chain's own
            decorators (globals -> story -> fragment -> variant)"
    (let [fragments {:fragment/themed {:decorators [:decorator/fragment-theme]}}
          variants  {:story.x/v {:compose    [:fragment/themed]
                                 :decorators [:decorator/variant-theme]}}
          p (plan/variant-plan :story.x/v
              {:lookup            variants
               :fragment-lookup   fragments
               :global-decorators [:decorator/global]})]
      (is (= [:decorator/global :decorator/fragment-theme :decorator/variant-theme]
             (get-in p [:world :decorators]))))))

;; ===========================================================================
;; Check composition + identity preservation
;; ===========================================================================

(deftest check-composes
  (testing "a composed check adds its id to [:expect :checks] (identity preserved)"
    (let [checks   {:check/no-runtime-errors
                    {:assertions [[:rf.assert/no-warnings]]}}
          variants {:story.x/v {:compose [:check/no-runtime-errors]
                                :script  [[:dispatch [:go]]]}}
          p (compose-plan :story.x/v
                          {:variants variants :checks checks})]
      (testing "the check-id (not its inlined assertions) rides :expect :checks"
        (is (= [:check/no-runtime-errors] (get-in p [:expect :checks]))))
      (testing "explain classifies the composed entry as a check"
        (is (= [{:kind :check :id :check/no-runtime-errors}]
               (get-in p [:explain :compose])))))))

(deftest compose-mixes-fragments-and-checks-in-declared-order
  (testing "a :compose list interleaving fragments + checks resolves each kind"
    (let [fragments {:fragment/seed {:setup [[:dispatch [:seed]]]}}
          checks    {:check/clean {:assertions [[:rf.assert/no-warnings]]}}
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
;; (rf2-ufosd — a check inherited + re-composed, or composed twice, must not
;;  appear N times; the runner would otherwise expand it into N duplicate
;;  grouped check records for one logical check id.)

(deftest inherited-check-recomposed-appears-once
  (testing "a check both INHERITED (via :extends) and re-named in :compose rides :expect :checks once"
    (let [checks   {:check/clean {:assertions [[:rf.assert/no-warnings]]}}
          variants {:story.k/parent {:checks [:check/clean]}
                    :story.k/child  {:extends :story.k/parent
                                     :compose [:check/clean]}}
          p (compose-plan :story.k/child
                          {:variants variants :checks checks})]
      (is (= [:check/clean] (get-in p [:expect :checks]))
          "the check appears exactly once despite inherit + compose")
      (is (= [:check/clean] (get-in p [:explain :checks]))))))

(deftest check-composed-twice-appears-once
  (testing "a check named twice in one :compose list rides :expect :checks once"
    (let [checks   {:check/clean {:assertions [[:rf.assert/no-warnings]]}}
          variants {:story.k/dup {:compose [:check/clean :check/clean]}}
          p (compose-plan :story.k/dup
                          {:variants variants :checks checks})]
      (is (= [:check/clean] (get-in p [:expect :checks]))))))

(deftest check-dedup-preserves-first-seen-order
  (testing "dedup keeps first-seen order: own check, then a distinct composed check"
    (let [checks   {:check/a {:assertions [[:rf.assert/no-warnings]]}
                    :check/b {:assertions [[:rf.assert/no-warnings]]}}
          variants {:story.k/order {:checks  [:check/a]
                                    :compose [:check/b :check/a]}}
          p (compose-plan :story.k/order
                          {:variants variants :checks checks})]
      (testing "own :check/a first, then the new composed :check/b — :check/a not repeated"
        (is (= [:check/a :check/b] (get-in p [:expect :checks])))))))

;; ===========================================================================
;; :extends inheritance — checks inherit; assertions + script do not
;; ===========================================================================

(deftest parent-check-inherits
  (testing "a parent variant's :checks inherit to the child (inheritable form)"
    (let [variants {:story.k/parent {:checks [:check/no-runtime-errors]}
                    :story.k/child  {:extends :story.k/parent
                                     :checks  [:check/extra]}}
          p (compose-plan :story.k/child {:variants variants})]
      (is (= [:check/no-runtime-errors :check/extra]
             (get-in p [:expect :checks]))))))

(deftest parent-assertion-does-not-inherit
  (testing "a parent variant's ordinary :assertions do NOT inherit (verdict is local)"
    (let [variants {:story.a/parent
                    {:assertions [[:rf.assert/path-equals [:s] :parent]]}
                    :story.a/child
                    {:extends    :story.a/parent
                     :assertions [[:rf.assert/path-equals [:s] :child]]}}
          p (compose-plan :story.a/child {:variants variants})]
      (is (= [[:rf.assert/path-equals [:s] :child]]
             (get-in p [:expect :assertions]))
          "only the child's own terminal assertions survive"))))

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
          opts      {:variants variants :fragments fragments}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-compose-nested-fragment"
            (compose-plan :story.x/v opts)))
      (let [data (try (compose-plan :story.x/v opts)
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
        (is (= :rf.error/story-compose-nested-fragment (:rf.error/id data)))
        (is (= :fragment/nested (:fragment/id data)))
        (is (= :compose (:offending-key data)))))))

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
          opts      {:variants variants :fragments fragments}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-compose-conflict"
            (compose-plan :story.x/v opts)))
      (let [data (try (compose-plan :story.x/v opts)
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))
            c    (first (:conflicts data))]
        (is (= :rf.error/story-compose-conflict (:rf.error/id data)))
        (is (= :fx-overrides (:field c)))
        (is (= :rf.http/fetch (:key c)))
        (is (= #{:fragment/http-a :fragment/http-b} (set (:sources c))))
        (is (= #{:stub-a :stub-b} (set (:values c))))))))

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
        (let [c (first (get-in p [:explain :strict-conflicts]))]
          (is (= :fx-overrides (:field c)))
          (is (= :rf.http/fetch (:key c)))
          (is (= :variant-stub (:winner c)))
          (is (= :variant (:winning-source c)))
          (is (= #{:fragment/http-a :fragment/http-b} (set (:losing-sources c))))
          (is (= :variant-owned-wins (:rule c))))))))

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
;; (rf2-sylr6 — resolution iterates an internal hash-map keyed by the
;;  override id; without a stable key order the :resolved / :unresolved
;;  vectors followed hash-map iteration order, which differs across CLJS /
;;  JVM. The fix sorts by key, so explain diffs + error messages reproduce.)

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
          opts      {:variants variants :fragments fragments}
          keys-of   (fn [p] (mapv :key (get-in p [:explain :strict-conflicts])))
          p         (compose-plan :story.x/v opts)
          order     (keys-of p)]
      (testing "every owned key surfaces a resolved entry"
        (is (= #{:rf.http/alpha :rf.http/beta :rf.http/mid :rf.http/zeta}
               (set order))))
      (testing "the order is the stable sort, not the (out-of-order) declared order"
        (is (= (sort-by pr-str order) order)))
      (testing "recompiling yields the IDENTICAL order (no hash-map iteration leak)"
        (is (= order (keys-of (compose-plan :story.x/v opts))))
        (is (= order (keys-of (compose-plan :story.x/v opts))))))))

;; ===========================================================================
;; No `:resolve-conflicts` escape hatch in P1
;; ===========================================================================

(deftest resolve-conflicts-rejected-by-schema
  (testing ":resolve-conflicts is rejected by the P1 variant schema (no escape hatch)"
    (let [explain (schemas/validate
                    :variant {:resolve-conflicts {[:fx-overrides :rf.http/fetch] :stub-a}})]
      (is (some? explain) ":resolve-conflicts must fail variant-body validation"))))

(deftest resolve-conflicts-absent-from-plan
  (testing "a composed plan carries no :resolve-conflicts surface"
    (let [fragments {:fragment/http {:fx-overrides {:rf.http/fetch :stub}}}
          variants  {:story.x/v {:compose [:fragment/http]}}
          p (compose-plan :story.x/v
                          {:variants variants :fragments fragments})]
      (is (not (contains? p :resolve-conflicts)))
      (is (not (contains? (:world p) :resolve-conflicts)))
      (is (not (contains? (:explain p) :resolve-conflicts))))))

;; ===========================================================================
;; Inline plan composing registered fragments/checks
;; ===========================================================================

(deftest inline-plan-composes-registered-fragments
  (testing "an inline map plan MAY compose registered fragments + checks (§Inline plan)"
    (let [fragments {:fragment/seed {:setup [[:dispatch [:seed]]]}}
          checks    {:check/clean {:assertions [[:rf.assert/no-warnings]]}}
          p (plan/variant-plan
              {:variant/id :inline/v
               :compose    [:fragment/seed :check/clean]
               :script     [[:dispatch [:go]]]}
              {:fragment-lookup fragments :check-lookup checks})]
      (is (= [[:dispatch [:seed]]] (get-in p [:world :setup])))
      (is (= [[:dispatch [:go]]] (:script p)))
      (is (= [:check/clean] (get-in p [:expect :checks]))))))
