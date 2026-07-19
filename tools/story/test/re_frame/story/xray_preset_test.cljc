(ns re-frame.story.xray-preset-test
  "Tests for per-story Xray preset (rf2-q9kv5).

  Coverage layers:

  - **Pure data** (JVM + CLJS): `merge-preset` deep-merge semantics,
    `resolve-preset` story+variant resolution.
  - **CLJS-only side-effects**: `apply-preset!` no-ops when a variant
    carries no `:xray` slot; `apply-preset!` dispatches the right
    events when shimmed handlers are in place; the project-root bridge
    reaches Xray's config slot.

  This namespace is `.cljc` so the pure surface runs on both JVM and
  CLJS test runners; CLJS-only blocks exercise the dispatch path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.xray-preset :as xray-preset]
            #?@(:cljs [[re-frame.core :as rf]
                       [re-frame.frame :as frame]
                       [re-frame.registrar :as registrar]
                       [day8.re-frame2-xray.config :as xray-config]
                       [re-frame.substrate.plain-atom :as plain-atom]])))

;; ---- fixtures -----------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  #?(:cljs (do (registrar/clear-all!)
               (reset! frame/frames {})
               (try (rf/init! plain-atom/adapter) (catch :default _ nil))
               (frame/ensure-default-frame!)))
  (story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: merge-preset --------------------------------------------------

(deftest merge-preset-handles-nils
  (testing "two nils merge to {}"
    (is (= {} (xray-preset/merge-preset nil nil)))))

(deftest merge-preset-variant-overrides-story
  (testing "variant slot wins over story slot at the top level"
    (let [story {:open? true :panel :epoch}
          vari  {:panel :trace}]
      (is (= {:open? true :panel :trace}
             (xray-preset/merge-preset story vari))))))

(deftest merge-preset-deep-merges-filters
  (testing ":filters merge respects :in / :out separately"
    (let [story {:filters {:in [:keep/me]}}
          vari  {:filters {:out [:drop/me]}}]
      (is (= {:filters {:in [:keep/me] :out [:drop/me]}}
             (xray-preset/merge-preset story vari))))))

(deftest merge-preset-variant-filters-override-story
  (testing "matching filter axes prefer variant value"
    (let [story {:filters {:in [:story/in] :out [:story/out]}}
          vari  {:filters {:in [:variant/in]}}]
      (is (= {:filters {:in [:variant/in] :out [:story/out]}}
             (xray-preset/merge-preset story vari))))))

;; ---- pure: resolve-preset ------------------------------------------------

(deftest resolve-preset-returns-nil-when-no-preset
  (testing "story + variant with no :xray slot resolves to nil"
    (story/reg-story :story.no-preset
      {:doc "no preset" :component :Some.view})
    (story/reg-variant :story.no-preset/v
      {:doc "v"})
    (is (nil? (xray-preset/resolve-preset :story.no-preset/v)))))

(deftest resolve-preset-reads-story-slot
  (testing "story :xray is returned when variant has none"
    (story/reg-story :story.story-preset
      {:doc "preset on story"
       :component :Some.view
       :xray {:open? true :panel :trace}})
    (story/reg-variant :story.story-preset/v
      {:doc "v"})
    (let [p (xray-preset/resolve-preset :story.story-preset/v)]
      (is (= true     (:open? p)))
      (is (= :trace   (:panel p))))))

(deftest resolve-preset-merges-story-and-variant
  (testing "variant :xray overrides story slot, :filters deep-merge"
    (story/reg-story :story.both
      {:doc "preset on both"
       :component :Some.view
       :xray {:open? true
               :panel :epoch
               :filters {:in [:keep/x]}}})
    (story/reg-variant :story.both/v
      {:doc "v"
       :xray {:panel :trace
               :filters {:out [:drop/y]}}})
    (let [p (xray-preset/resolve-preset :story.both/v)]
      (is (= true                                (:open? p)))
      (is (= :trace                              (:panel p)))
      (is (= {:in [:keep/x] :out [:drop/y]}      (:filters p))))))

;; ---- CLJS-only: apply-preset! --------------------------------------------

;; rf2-r8trk retired `cljs-apply-preset-no-xray-no-op`. It shimmed
;; `xray-available?` to `false` to exercise an absent-Xray posture that
;; the artefact cannot reach: `day8/re-frame2-xray` is a declared Story
;; dependency, so a build that resolves `re-frame.story.xray-preset` has
;; already resolved Xray's mount ns. The predicate it shimmed no longer
;; exists. `cljs-apply-preset-nil-on-missing-preset` below covers the
;; real no-work path (no `:xray` slot).

#?(:cljs
   (deftest cljs-apply-preset-nil-on-missing-preset
     (testing "no :xray slot → no work, returns nil even when Xray would be available"
       (story/reg-story :story.nilpre
         {:doc "no slot"
          :component :Some.view})
       (story/reg-variant :story.nilpre/v
         {:doc "v"})
       (is (nil? (xray-preset/apply-preset! :story.nilpre/v))))))

;; ---- CLJS-only: project-root propagator (rf2-r1uod) ----------------------

#?(:cljs
   (deftest cljs-propagate-project-root-reaches-xray
     (testing "propagate-project-root! bridges Story's root into Xray's config slot"
       ;; rf2-r8trk: this test previously asserted
       ;; `(false? (xray-config-available?))` — "this test assumes Xray
       ;; is NOT on the classpath". That assertion only ever passed
       ;; because the old `resolve-fn` namespace-property walk returned
       ;; a false-negative for a namespace that WAS present. Xray is now
       ;; a declared dependency and the bridge calls
       ;; `xray-config/configure!` through a direct `:require`, so the
       ;; honest assertion is that the propagation LANDS.
       ;;
       ;; Seed Story's project-root via configure! — exercises the whole
       ;; configure! → set-project-root! → propagator pipeline.
       (story/configure! {:rf.story/project-root "/home/me/code/my-app"})
       (try
         (is (= "/home/me/code/my-app" (xray-preset/propagate-project-root!))
             "the propagator returns the root it bridged into Xray's slot")
         (is (= "/home/me/code/my-app" (xray-config/get-project-root))
             "the value actually landed in Xray's own config slot")
         (finally
           ;; Reset BOTH slots so neighbouring tests see the baseline —
           ;; the bridge now writes through to Xray's global atom.
           (story/configure! {:rf.story/project-root nil})
           (xray-config/set-project-root! nil))))))

#?(:cljs
   (deftest cljs-propagate-project-root-nil-when-unset
     (testing "propagate-project-root! returns nil when Story has no project-root configured"
       ;; Clear any prior seed (the fixture resets registrar but not
       ;; the config atom).
       (story/configure! {:rf.story/project-root nil})
       (is (nil? (xray-preset/propagate-project-root!))
           "no propagation when Story's project-root is nil"))))

;; CLJS-only tests for the keybinding-disable bridge (rf2-q7who.1)
;; live in `re-frame.story.xray-preset-cljs-test` — separate file so
;; the `:node-test` build's `cljs-test$` ns-regexp picks them up (the
;; .cljc file's namespace name does not).
