(ns re-frame.story.tags-test
  "Tests for the shared effective-tag resolver (rf2-n0vmq2).

  `re-frame.story.tags` is pure data → data, so every test runs on both the
  JVM and CLJS without a host: inheritance layers are supplied through
  explicit `{id → body}` lookup maps. The plan regressions drive the real
  compiler (`re-frame.story.plan`) with an explicit `:lookup`; the filter
  regression proves the snapshot projection feeds the sidebar filter."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.tags :as tags]
            [re-frame.story.plan :as plan]
            [re-frame.story.ui.state.filters :as filters]))

;; ---- pure marker helpers -------------------------------------------------

(deftest removal-marker?-recognises-bang-prefix
  (is (true?  (tags/removal-marker? :!dev)))
  (is (true?  (tags/removal-marker? :a/!b)))
  (is (false? (tags/removal-marker? :dev)))
  (is (false? (tags/removal-marker? :a/b)))
  (is (false? (tags/removal-marker? "not-a-keyword")))
  (is (false? (tags/removal-marker? nil))))

(deftest marker-base-strips-bang-preserving-namespace
  (is (= :dev (tags/marker-base :!dev)))
  (is (= :a/b (tags/marker-base :a/!b)))
  (testing "a non-marker is returned unchanged"
    (is (= :dev (tags/marker-base :dev)))))

(deftest resolve-markers-strips-and-subtracts
  (testing "a marker drops itself AND subtracts its base"
    (is (= #{:docs} (tags/resolve-markers #{:dev :!dev :docs}))))
  (testing "a marker with no matching base still never surfaces"
    (is (= #{} (tags/resolve-markers #{:!dev}))))
  (testing "a set with no markers is returned unchanged"
    (is (= #{:dev :docs} (tags/resolve-markers #{:dev :docs}))))
  (testing "namespaced marker cancels its namespaced base"
    (is (= #{:team/qa} (tags/resolve-markers #{:team/qa :role/dev :role/!dev})))))

;; ---- extends-chain union -------------------------------------------------

(deftest extends-chain-tags-unions-root-to-child
  (let [m {:story.a/parent {:tags #{:dev :test}}
           :story.a/child  {:extends :story.a/parent :tags #{:docs}}}]
    (is (= #{:dev :test :docs} (tags/extends-chain-tags :story.a/child m)))))

(deftest extends-chain-tags-is-defensive
  (testing "a missing parent stops the walk without raising"
    (let [m {:story.a/child {:extends :story.a/ghost :tags #{:docs}}}]
      (is (= #{:docs} (tags/extends-chain-tags :story.a/child m)))))
  (testing "a cycle stops the walk without raising"
    (let [m {:story.a/x {:extends :story.a/y :tags #{:dev}}
             :story.a/y {:extends :story.a/x :tags #{:docs}}}]
      (is (= #{:dev :docs} (tags/extends-chain-tags :story.a/x m))))))

;; ---- effective-tags: inheritance + markers -------------------------------

(deftest effective-tags-inherited-tag-removed-by-marker
  (testing "rf2-n0vmq2 — a child :extends a parent tagged :dev and declares
            :!dev; the inherited :dev is removed and :!dev never surfaces"
    (let [m {:story.login/base  {:tags #{:dev :test}}
             :story.login/child {:extends :story.login/base :tags #{:!dev}}}
          eff (tags/effective-tags :story.login/child {:variant m})]
      (is (= #{:test} eff))
      (is (not (contains? eff :dev)))
      (is (not (contains? eff :!dev))))))

(deftest effective-tags-story-fallback-only-when-chain-empty
  (let [variants {:story.t/no-tags  {}
                  :story.t/own-tags {:tags #{:test}}}
        stories  {:story.t {:tags #{:dev :docs}}}
        lookups  {:variant variants :story stories}]
    (testing "a variant that declares none inherits the parent story's tags"
      (is (= #{:dev :docs} (tags/effective-tags :story.t/no-tags lookups))))
    (testing "a variant that declares its own tags does NOT union with the story"
      (is (= #{:test} (tags/effective-tags :story.t/own-tags lookups))))))

(deftest effective-tags-empty-when-nothing-declared
  (is (= #{} (tags/effective-tags :story.none/v {:variant {:story.none/v {}}}))))

;; ---- resolve-body-tags projection ----------------------------------------

(deftest resolve-body-tags-projects-effective-onto-each-body
  (let [variants {:story.p/base  {:tags #{:dev}}
                  :story.p/child {:extends :story.p/base :tags #{:!dev :docs}}}
        projected (tags/resolve-body-tags variants)]
    (is (= #{:dev}  (:tags (:story.p/base projected))))
    (is (= #{:docs} (:tags (:story.p/child projected)))
        "the child's inherited :dev is cancelled by :!dev; :!dev is stripped")
    (testing "non-tag slots are preserved"
      (is (= :story.p/base (:extends (:story.p/child projected)))))))

;; ---- sidebar filter regression (snapshot projection → filter) ------------

(deftest filter-excludes-variant-that-removed-the-tag
  (testing "no visible :!dev chip / no :dev filter hit after resolution"
    (let [variants  {:story.f/base  {:tags #{:dev}}
                     :story.f/child {:extends :story.f/base :tags #{:!dev}}
                     :story.f/keeps {:tags #{:dev}}}
          projected (tags/resolve-body-tags variants)
          dev-hits  (set (keys (filters/filter-variants projected #{:dev})))]
      (testing "the child that removed :dev is filtered out"
        (is (not (contains? dev-hits :story.f/child))))
      (testing "variants that keep :dev (own + inherited-untouched) still match"
        (is (= #{:story.f/base :story.f/keeps} dev-hits)))
      (testing ":!dev is never a visible tag on any projected body"
        (is (not-any? (fn [[_ b]] (contains? (:tags b) :!dev)) projected))))))

;; ---- plan compilation regression -----------------------------------------

(deftest plan-tags-resolve-markers-through-extends
  (testing "rf2-n0vmq2 — plan :tags is the EFFECTIVE set (inherited :dev
            removed by :!dev), not the raw union"
    (let [m {:story.login/filled {:tags #{:dev :test} :events []}
             :story.login/error  {:extends    :story.login/filled
                                   :tags       #{:!dev}
                                   :events     []}}
          p (plan/variant-plan :story.login/error {:lookup m})]
      (is (= #{:test} (:tags p)))
      (is (= #{:test} (get-in p [:explain :tags])))
      (is (not (contains? (:tags p) :dev)))
      (is (not (contains? (:tags p) :!dev))))))

(deftest plan-tags-additive-through-extends-without-markers
  (testing "the additive :extends union is preserved when no marker is present"
    (let [m {:story.s/parent {:tags #{:test} :events []}
             :story.s/child  {:extends :story.s/parent :tags #{:docs} :events []}}
          p (plan/variant-plan :story.s/child {:lookup m})]
      (is (= #{:test :docs} (:tags p))))))

(deftest plan-tags-story-fallback-via-story-lookup
  (testing "a variant that declares no tags inherits the parent story's via
            the :story-lookup opt"
    (let [m {:story.fb/v {:events []}}
          stories {:story.fb {:tags #{:dev :docs}}}
          p (plan/variant-plan :story.fb/v {:lookup m :story-lookup stories})]
      (is (= #{:dev :docs} (:tags p))))))
