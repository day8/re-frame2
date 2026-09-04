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
            [re-frame.story :as rf.story]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

;; ---- the compile-time flag ----------------------------------------------

(deftest enabled-flag-is-true-in-test-build
  (testing "Story is enabled in this CLJS test build"
    (is (true? rf.story.config/enabled?))))

;; ---- static-mode? (rf2-8wgpm) -------------------------------------------

(deftest static-mode-flag-defaults-false-in-cljs-test-build
  (testing "re-frame.story.config/static-mode? defaults to false in the CLJS test build"
    ;; Per tools/story/spec/013-Static-Build.md the goog-define defaults
    ;; to false; only the `story:build` invocation (or a custom
    ;; downstream build mirroring its :closure-defines) flips it true.
    ;; The node-test build under shadow-cljs runs without overriding
    ;; the define, so we expect the default-false branch here.
    (is (false? rf.story.config/static-mode?)))
  (testing "the public probe (re-frame.story/static-mode?) reflects the flag"
    (is (false? (rf.story/static-mode?)))))

;; ---- macros emit working code -------------------------------------------

(deftest cljs-smoke-reg-story-and-variant
  (testing "reg-story + reg-variant macros register against the side-table in CLJS"
    (rf.story/reg-story :story.cljs.smoke
      {:doc       "CLJS smoke test."
       :component :app.cljs/comp
       :tags      #{:dev}})
    (rf.story/reg-variant :story.cljs.smoke/default
      {:doc    "default state"
       :setup [[:init]]
       :tags   #{:dev}})
    (is (rf.story/registered? :story   :story.cljs.smoke))
    (is (rf.story/registered? :variant :story.cljs.smoke/default))))

(deftest cljs-form-b-desugars
  (testing "Form-B :variants desugars on the CLJS side"
    (rf.story/reg-story :story.cljs.form-b
      {:doc       "Form-B test."
       :component :app.cljs/comp
       :variants  {:a {:setup [[:init-a]]}
                   :b {:setup [[:init-b]]}}})
    (is (rf.story/registered? :variant :story.cljs.form-b/a))
    (is (rf.story/registered? :variant :story.cljs.form-b/b))))

;; ---- canonical tag set ---------------------------------------------------

(deftest cljs-canonical-tags-installed
  (testing "the seven canonical inclusion tags + five canonical :state/* magnitude tags load on the CLJS side"
    (let [tags (rf.story/list-tags)]
      (is (= (into rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)
             tags))
      (testing "the seven inclusion tags are all present (rf2-k1k87 didn't drop any)"
        (is (every? tags rf.story.schemas/canonical-tags)))
      (testing "the five state tags are all present (rf2-k1k87 regression smoke)"
        (is (every? tags rf.story.schemas/canonical-state-tags))
        (is (= #{:state/empty :state/small :state/medium :state/large :state/special}
               rf.story.schemas/canonical-state-tags))))))

;; ---- :state/* axis regression smoke (rf2-k1k87) -------------------------
;;
;; Panel-gallery `/#/stories` rendered empty because the `:state/*` axis
;; was projected onto every variant but never registered — the registrar's
;; tag-membership check raised `:rf.error/unknown-tag` on the FIRST gallery
;; ns load and the whole inventory aborted. This smoke locks the
;; canonical install so a `reg-variant` carrying every `:state/*` value
;; AT ONCE succeeds without throwing.

(deftest cljs-state-axis-tags-survive-variant-registration
  (testing "a variant tagged with the full :state/* axis registers cleanly (no :rf.error/unknown-tag)"
    (rf.story/reg-story :story.cljs.state-axis-smoke
      {:doc       "rf2-k1k87 canonical :state/* axis smoke."
       :component :app.cljs/comp
       :tags      #{:dev}})
    (rf.story/reg-variant :story.cljs.state-axis-smoke/all-state-magnitudes
      {:doc    "every :state/* tag at once."
       :setup [[:init]]
       :tags   (into #{:dev} rf.story.schemas/canonical-state-tags)})
    (is (rf.story/registered? :variant :story.cljs.state-axis-smoke/all-state-magnitudes)))
  (testing "each :state/* tag carries the :state axis classifier"
    (let [by-axis (rf.story/tags-by-axis :state)]
      (is (= rf.story.schemas/canonical-state-tags by-axis)
          "every :state/* tag is registered on the :state axis"))))
