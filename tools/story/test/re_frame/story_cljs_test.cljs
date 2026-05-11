(ns re-frame.story-cljs-test
  "CLJS smoke tests for re-frame2-story Stage 2.

  The bulk of registration / schema / extends coverage lives in the
  JVM test ns (`re-frame.story-test`) — those tests run faster, on
  more hosts, and exercise the macros from a non-Reagent environment.

  This namespace covers the CLJS-specific surface: the `goog-define`
  flag at `re-frame.story.config/enabled?`, and a smoke registration
  round-trip to confirm the macros emit working code in a CLJS
  compile."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.config :as config]
            [re-frame.story.schemas :as schemas]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

;; ---- the compile-time flag ----------------------------------------------

(deftest enabled-flag-is-true-in-test-build
  (testing "Story is enabled in this CLJS test build"
    (is (true? config/enabled?))))

;; ---- macros emit working code -------------------------------------------

(deftest cljs-smoke-reg-story-and-variant
  (testing "reg-story + reg-variant macros register against the side-table in CLJS"
    (story/reg-story :story.cljs.smoke
      {:doc       "CLJS smoke test."
       :component :app.cljs/comp
       :tags      #{:dev}})
    (story/reg-variant :story.cljs.smoke/default
      {:doc    "default state"
       :events [[:init]]
       :tags   #{:dev}})
    (is (story/registered? :story   :story.cljs.smoke))
    (is (story/registered? :variant :story.cljs.smoke/default))))

(deftest cljs-form-b-desugars
  (testing "Form-B :variants desugars on the CLJS side"
    (story/reg-story :story.cljs.form-b
      {:doc       "Form-B test."
       :component :app.cljs/comp
       :variants  {:a {:events [[:init-a]]}
                   :b {:events [[:init-b]]}}})
    (is (story/registered? :variant :story.cljs.form-b/a))
    (is (story/registered? :variant :story.cljs.form-b/b))))

;; ---- canonical tag set ---------------------------------------------------

(deftest cljs-canonical-tags-installed
  (testing "the seven canonical tags load on the CLJS side"
    (is (= schemas/canonical-tags (story/list-tags)))))
