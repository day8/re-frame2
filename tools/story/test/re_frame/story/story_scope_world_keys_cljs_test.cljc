(ns re-frame.story.story-scope-world-keys-cljs-test
  "A STORY-level `:substrates` / `:component` declaration reaches the
  compiled plan's `[:world …]`, under the variant chain.

  `rf.story.plan/variant-plan` folds both keys from the variant body and
  its `:extends` chain first, then from the parent story — the normal
  authoring shape of `001-Authoring.md`, the story carrying the subject
  while variants vary by args. Both slots have plan-side readers:
  `canonical/render-host-scope` takes the substrate off
  `[:world :substrates]`, and `rf.story.render/prepare-render` takes the
  subject off `[:world :component]` with no fallback of its own.

  `.cljc` with a `-cljs-test` ns, so the JVM runner and `:node-test` each
  run every row."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.render :as rf.story.render]))

;; The plan's DEFAULT lookups read both registrars: the Story side-table for
;; the parent story, and the framework `:view` registrar for the props schema.
(use-fixtures :each
  (fn [t]
    (rf.story.registrar/clear-all!)
    (rf.registrar/clear-kind! :view)
    (t)))

(defn- reg-view-meta!
  "Register a `:view` slot carrying `metadata` on the framework registrar —
  the slot the plan compiler's default view-lookup reads."
  [view-id metadata]
  (rf.registrar/register! :view view-id
                                 (assoc metadata :handler-fn (fn [_] nil))))

;; ---- :substrates ----------------------------------------------------------

(deftest the-variant-still-wins-over-its-story
  (rf.story.registrar/reg-story* :story.scope-win
    {:doc "story default" :component :views/probe :substrates #{:reagent}})
  (rf.story.registrar/reg-variant* :story.scope-win/override
    {:doc "this one variant is authored against another layer"
     :substrates #{:uix}})
  (is (= #{:uix}
         (get-in (rf.story.plan/variant-plan :story.scope-win/override)
                 [:world :substrates]))))

(deftest an-extends-chain-still-wins-over-its-story
  (rf.story.registrar/reg-story* :story.scope-ext
    {:doc "story default" :component :views/probe :substrates #{:reagent}})
  (rf.story.registrar/reg-variant* :story.scope-ext/base
    {:doc "base" :substrates #{:uix}})
  (rf.story.registrar/reg-variant* :story.scope-ext/child
    {:doc "child declares nothing of its own" :extends :story.scope-ext/base})
  (is (= #{:uix}
         (get-in (rf.story.plan/variant-plan :story.scope-ext/child)
                 [:world :substrates]))))

(deftest an-empty-variant-set-declares-nothing
  (testing "`#{}` on the variant is not a declaration, as the canvas's
            `resolve-substrate-set` reads it, so the story's set lands"
    (rf.story.registrar/reg-story* :story.scope-empty
      {:doc "story declares" :component :views/probe :substrates #{:uix}})
    (rf.story.registrar/reg-variant* :story.scope-empty/v
      {:doc "declares an empty set" :substrates #{}})
    (is (= #{:uix}
           (get-in (rf.story.plan/variant-plan :story.scope-empty/v)
                   [:world :substrates])))))

;; ---- :component -----------------------------------------------------------

(deftest a-story-level-component-reaches-the-plan-and-the-render-inputs
  (rf.story.registrar/reg-story* :story.scope-cmp
    {:doc "the parent carries the subject" :component :views/probe})
  (rf.story.registrar/reg-variant* :story.scope-cmp/v
    {:doc "varies by args only" :args {:label "Go"}})
  (is (= :views/probe
         (get-in (rf.story.render/prepare-render :story.scope-cmp/v)
                 [:render-inputs :view]))))

(deftest a-variant-component-still-overrides-its-story
  (rf.story.registrar/reg-story* :story.scope-cmp-ovr
    {:doc "parent subject" :component :views/parent})
  (rf.story.registrar/reg-variant* :story.scope-cmp-ovr/v
    {:doc "names its own subject" :component :views/own})
  (is (= :views/own
         (get-in (rf.story.plan/variant-plan :story.scope-cmp-ovr/v)
                 [:world :component]))))

(deftest a-missing-required-view-input-fails-under-a-story-level-component
  (testing "the view-args schema of a story-level subject is enforced
            exactly as a variant-level one's"
    (reg-view-meta! :views/strict {:rf/props [:map [:label :string]]})
    (rf.story.registrar/reg-story* :story.scope-strict
      {:doc "parent carries the subject" :component :views/strict})
    (rf.story.registrar/reg-variant* :story.scope-strict/v {:doc "supplies no args"})
    (let [e (try (rf.story.plan/variant-plan :story.scope-strict/v)
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is (= :rf.error/story-view-args-invalid (:rf.error/id (ex-data e))))
      (is (= :views/strict (:component (ex-data e)))))))
