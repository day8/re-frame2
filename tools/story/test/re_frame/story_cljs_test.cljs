(ns re-frame.story-cljs-test
  "CLJS smoke tests for re-frame2-story registration.

  The bulk of registration / schema / extends coverage lives in the
  JVM test ns (`re-frame.story-test`) — those tests run faster, on
  more hosts, and exercise the macros from a non-Reagent environment.

  This namespace covers the CLJS-specific surface: a smoke
  registration round-trip to confirm the macros emit working code in
  a CLJS compile, and the canonical tag install."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

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

;; ---- canonical tag set ---------------------------------------------------

(deftest cljs-canonical-tags-installed
  (testing "the seven canonical inclusion tags + five canonical :state/* magnitude tags load on the CLJS side"
    (let [tags (rf.story/list-tags)]
      (is (= (into rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)
             tags))
      (testing "the five state tags are exactly the :state/* magnitudes"
        (is (= #{:state/empty :state/small :state/medium :state/large :state/special}
               rf.story.schemas/canonical-state-tags))))))

;; ---- :state/* axis smoke ------------------------------------------------
;;
;; The `:state/*` axis is projected onto every variant, so it must be
;; registered: otherwise the registrar's tag-membership check raises
;; `:rf.error/unknown-tag` on the FIRST gallery ns load, the whole
;; inventory aborts and the panel gallery `/#/stories` renders empty. This
;; smoke locks the canonical install so a `reg-variant` carrying every
;; `:state/*` value AT ONCE succeeds without throwing.

(deftest cljs-state-axis-tags-survive-variant-registration
  (testing "a variant tagged with the full :state/* axis registers cleanly (no :rf.error/unknown-tag)"
    (rf.story/reg-story :story.cljs.state-axis-smoke
      {:doc       "canonical :state/* axis smoke."
       :component :app.cljs/comp
       :tags      #{:dev}})
    (rf.story/reg-variant :story.cljs.state-axis-smoke/all-state-magnitudes
      {:doc    "every :state/* tag at once."
       :setup [[:init]]
       :tags   (into #{:dev} rf.story.schemas/canonical-state-tags)})
    (is (rf.story/registered? :variant :story.cljs.state-axis-smoke/all-state-magnitudes))))
