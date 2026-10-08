(ns re-frame.story.tags-cljs-test
  "Tests for the shared effective-tag resolver. `re-frame.story.tags` is pure
  data → data, so inheritance layers are explicit `{id → body}` maps and every
  test runs on the JVM and on node (the `-cljs-test` suffix opts it into
  `:node-test`)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.tags :as rf.story.tags]
            [re-frame.story.plan :as rf.story.plan]))

(deftest resolve-markers-strips-and-subtracts
  (is (= #{:team/qa} (rf.story.tags/resolve-markers #{:team/qa :role/dev :role/!dev}))
      "a namespaced marker drops itself and cancels its namespaced base"))

(deftest extends-chain-tags-is-defensive
  (testing "a missing parent stops the walk without raising"
    (let [m {:story.a/child {:extends :story.a/ghost :tags #{:docs}}}]
      (is (= #{:docs} (rf.story.tags/extends-chain-tags :story.a/child m)))))
  (testing "a cycle stops the walk without raising"
    (let [m {:story.a/x {:extends :story.a/y :tags #{:dev}}
             :story.a/y {:extends :story.a/x :tags #{:docs}}}]
      (is (= #{:dev :docs} (rf.story.tags/extends-chain-tags :story.a/x m))))))

(deftest effective-tags-inherited-tag-removed-by-marker
  (let [m {:story.login/base  {:tags #{:dev :test}}
           :story.login/child {:extends :story.login/base :tags #{:!dev}}}]
    (is (= #{:test} (rf.story.tags/effective-tags :story.login/child {:variant m})))))

(deftest effective-tags-story-fallback-only-when-chain-empty
  (let [variants {:story.t/no-tags  {}
                  :story.t/own-tags {:tags #{:test}}}
        stories  {:story.t {:tags #{:dev :docs}}}
        lookups  {:variant variants :story stories}]
    (testing "a variant that declares none inherits the parent story's tags"
      (is (= #{:dev :docs} (rf.story.tags/effective-tags :story.t/no-tags lookups))))
    (testing "a variant that declares its own tags does NOT union with the story"
      (is (= #{:test} (rf.story.tags/effective-tags :story.t/own-tags lookups))))))

(deftest effective-tags-empty-when-nothing-declared
  (is (= #{} (rf.story.tags/effective-tags :story.none/v {:variant {:story.none/v {}}}))))

(deftest resolve-body-tags-projects-effective-onto-each-body
  (is (= {:story.p/base  {:tags #{:dev}}
          :story.p/child {:extends :story.p/base :tags #{:docs}}}
         (rf.story.tags/resolve-body-tags
           {:story.p/base  {:tags #{:dev}}
            :story.p/child {:extends :story.p/base :tags #{:!dev :docs}}}))))

(deftest plan-tags-resolve-markers-through-extends
  (let [m {:story.login/filled {:tags #{:dev :test} :setup []}
           :story.login/error  {:extends :story.login/filled :tags #{:!dev} :setup []}}
        p (rf.story.plan/variant-plan :story.login/error {:lookup m})]
    (is (= #{:test} (:tags p)))
    (is (= #{:test} (get-in p [:explain :tags])))))

(deftest plan-tags-story-fallback-via-story-lookup
  (let [p (rf.story.plan/variant-plan :story.fb/v {:lookup       {:story.fb/v {:setup []}}
                                                   :story-lookup {:story.fb {:tags #{:dev :docs}}}})]
    (is (= #{:dev :docs} (:tags p)))))
