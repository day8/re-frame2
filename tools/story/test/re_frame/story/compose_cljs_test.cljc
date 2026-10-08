(ns re-frame.story.compose-cljs-test
  "Tests for strict `:compose` composition — fragments, checks, total merge
  order, variant-owned-wins, and the silent-conflict failure (spec/017
  §`:compose` / §Merge rules / §Conflict resolution). The compiler is pure
  data → data, so bodies come through explicit lookup maps and every test
  runs on the JVM and on node (the `-cljs-test` suffix opts it into
  `:node-test`)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.plan :as rf.story.plan]))

(defn- compose-plan
  [target {:keys [variants fragments checks]
           :or   {variants {} fragments {} checks {}}}]
  (rf.story.plan/variant-plan target {:lookup          variants
                                      :fragment-lookup fragments
                                      :check-lookup    checks}))

(defn- compose-error
  "The ex-data of compiling `target`, or nil when it compiles."
  [target opts]
  (try (compose-plan target opts) nil
       (catch #?(:clj Exception :cljs :default) e (ex-data e))))

;; ===========================================================================
;; Fragment composition — setup + script append in declared order
;; ===========================================================================

(deftest fragment-setup-composes
  (testing "a composed fragment's :setup lands before the variant's own, and
            its :args fold into the effective args its setup substitutes"
    (let [fragments {:fragment.cart/with-sku
                     {:args  {:sku "A"}
                      :setup [[:dispatch [:cart/add {:sku [:arg :sku]}]]]}}
          variants  {:story.checkout/submits
                     {:compose [:fragment.cart/with-sku]
                      :setup   [[:dispatch [:checkout/open]]]}}
          p (compose-plan :story.checkout/submits
                          {:variants variants :fragments fragments})]
      (is (= {:setup [[:dispatch [:cart/add {:sku "A"}]]
                      [:dispatch [:checkout/open]]]
              :args  {:sku "A"}}
             (select-keys (:world p) [:setup :args]))))))

(deftest two-fragments-compose-in-declared-order
  (testing "two fragments' setup + script append in declared order, and the
            script lands in the EXECUTED primary play, not only the reported
            top-level :script (the runtime drives [:world :scripts])"
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
             (:script p)
             (:script (first (get-in p [:world :scripts]))))))))

(deftest fragment-decorators-compose-in-globals-story-fragment-variant-order
  (let [fragments {:fragment/themed {:decorators [:decorator/fragment-theme]}}
        variants  {:story.x/v {:compose    [:fragment/themed]
                               :decorators [:decorator/variant-theme]}}
        p (rf.story.plan/variant-plan :story.x/v
            {:lookup            variants
             :fragment-lookup   fragments
             :global-decorators [:decorator/global]})]
    (is (= [:decorator/global :decorator/fragment-theme :decorator/variant-theme]
           (get-in p [:world :decorators])))))

;; ===========================================================================
;; Check composition
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

;; ===========================================================================
;; :extends — script does not inherit
;; ===========================================================================

(deftest parent-script-does-not-inherit
  (let [variants {:story.s/parent {:script [[:dispatch [:parent-step]]]}
                  :story.s/child  {:extends :story.s/parent
                                   :script  [[:dispatch [:child-step]]]}}
        p (compose-plan :story.s/child {:variants variants})]
    (is (= [[:dispatch [:child-step]]] (:script p))
        "script is behaviour under test — a child does not silently run the parent's")))

;; ===========================================================================
;; Failures — non-flat fragments and unknown ids
;; ===========================================================================

(deftest nested-fragment-fails
  (testing "a composed fragment carrying :compose or :extends FAILS (fragments are flat)"
    (doseq [[offending-key fragment] [[:compose {:compose [:fragment/leaf]
                                                 :setup   [[:dispatch [:nested]]]}]
                                      [:extends {:extends :story.x/parent
                                                 :setup   [[:dispatch [:bad]]]}]]]
      (is (= {:rf.error/id   :rf.error/story-compose-nested-fragment
              :fragment/id   :fragment/nested
              :offending-key offending-key}
             (select-keys (compose-error :story.x/v
                                         {:variants  {:story.x/v {:compose [:fragment/nested]}}
                                          :fragments {:fragment/leaf   {:setup [[:dispatch [:leaf]]]}
                                                      :fragment/nested fragment}})
                          [:rf.error/id :fragment/id :offending-key]))))))

(deftest unknown-compose-id-fails
  (is (= {:rf.error/id :rf.error/story-compose-unknown :compose/id :fragment/ghost}
         (select-keys (compose-error :story.x/v {:variants {:story.x/v {:compose [:fragment/ghost]}}})
                      [:rf.error/id :compose/id]))))

;; ===========================================================================
;; Strict-conflict resolution — variant-owned-wins + silent-conflict failure
;; ===========================================================================

(deftest two-fragments-conflict-when-variant-silent
  (let [fragments {:fragment/http-a {:fx-overrides {:rf.http/fetch :stub-a}}
                   :fragment/http-b {:fx-overrides {:rf.http/fetch :stub-b}}}
        variants  {:story.x/v {:compose [:fragment/http-a :fragment/http-b]}}]
    (is (= {:rf.error/id :rf.error/story-compose-conflict
            :conflicts   [{:field   :fx-overrides
                           :key     :rf.http/fetch
                           :sources [:fragment/http-a :fragment/http-b]
                           :values  [:stub-a :stub-b]}]}
           (select-keys (compose-error :story.x/v {:variants variants :fragments fragments})
                        [:rf.error/id :conflicts]))
        "the conflict names the field, the key, and each source's value in declared order")))

(deftest fragments-that-agree-or-touch-different-keys-do-not-conflict
  (doseq [[fragments expected]
          [[{:fragment/http-a {:fx-overrides {:rf.http/fetch :stub}}
             :fragment/http-b {:fx-overrides {:rf.http/fetch :stub}}}
            {:rf.http/fetch :stub}]
           [{:fragment/http-a {:fx-overrides {:rf.http/fetch :http-stub}}
             :fragment/http-b {:fx-overrides {:ws/send :ws-stub}}}
            {:rf.http/fetch :http-stub :ws/send :ws-stub}]]]
    (is (= expected
           (get-in (compose-plan :story.x/v
                                 {:variants  {:story.x/v {:compose [:fragment/http-a :fragment/http-b]}}
                                  :fragments fragments})
                   [:world :frame :fx-overrides])))))

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
      (is (= {:rf.http/fetch :variant-fetch
              :rf.http/post  :frag-post}
             (get-in p [:world :frame :fx-overrides]))))))

(deftest interceptor-overrides-are-strict-too
  (testing ":interceptor-overrides follow the same strict-conflict rule"
    (let [fragments {:fragment/i-a {:interceptor-overrides {:guard :a}}
                     :fragment/i-b {:interceptor-overrides {:guard :b}}}
          variants  {:story.x/v {:compose [:fragment/i-a :fragment/i-b]}}]
      (is (= :rf.error/story-compose-conflict
             (:rf.error/id (compose-error :story.x/v {:variants variants :fragments fragments}))))
      (testing "variant-owned interceptor override wins"
        (let [vw {:variants {:story.x/w {:compose [:fragment/i-a :fragment/i-b]
                                         :interceptor-overrides {:guard :v}}}
                  :fragments fragments}
              p  (compose-plan :story.x/w vw)]
          (is (= {:guard :v}
                 (get-in p [:world :frame :interceptor-overrides]))))))))

;; Resolution walks a map keyed by override id, so explain sorts by key to
;; read the same on the JVM and CLJS.
(deftest strict-conflicts-explain-order-is-deterministic
  (let [fragments {:fragment/many
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
           (mapv :key (get-in p [:explain :strict-conflicts]))))))

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
