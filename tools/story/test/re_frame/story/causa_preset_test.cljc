(ns re-frame.story.causa-preset-test
  "Tests for per-story Causa preset (rf2-q9kv5).

  Coverage layers:

  - **Pure data** (JVM + CLJS): `merge-preset` deep-merge semantics,
    `resolve-preset` story+variant resolution.
  - **CLJS-only side-effects**: `apply-preset!` feature-detect graceful
    no-op when Causa is absent; `apply-preset!` dispatches the right
    events when shimmed handlers are in place.

  This namespace is `.cljc` so the pure surface runs on both JVM and
  CLJS test runners; CLJS-only blocks exercise the dispatch path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.causa-preset :as causa-preset]
            #?@(:cljs [[re-frame.core :as rf]
                       [re-frame.frame :as frame]
                       [re-frame.registrar :as registrar]
                       [re-frame.substrate.plain-atom :as plain-atom]])))

;; ---- fixtures -----------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  #?(:cljs (do (registrar/clear-all!)
               (reset! frame/frames {})
               (try (rf/init! plain-atom/adapter) (catch :default _ nil))
               (frame/ensure-default-frame!)))
  (story/install-canonical-vocabulary!))

(use-fixtures :each {:before reset-all!})

;; ---- pure: merge-preset --------------------------------------------------

(deftest merge-preset-handles-nils
  (testing "two nils merge to {}"
    (is (= {} (causa-preset/merge-preset nil nil)))))

(deftest merge-preset-variant-overrides-story
  (testing "variant slot wins over story slot at the top level"
    (let [story {:open? true :tab :event-detail}
          vari  {:tab :issues}]
      (is (= {:open? true :tab :issues}
             (causa-preset/merge-preset story vari))))))

(deftest merge-preset-deep-merges-filters
  (testing ":filters merge respects :in / :out separately"
    (let [story {:filters {:in [:keep/me]}}
          vari  {:filters {:out [:drop/me]}}]
      (is (= {:filters {:in [:keep/me] :out [:drop/me]}}
             (causa-preset/merge-preset story vari))))))

(deftest merge-preset-variant-filters-override-story
  (testing "matching filter axes prefer variant value"
    (let [story {:filters {:in [:story/in] :out [:story/out]}}
          vari  {:filters {:in [:variant/in]}}]
      (is (= {:filters {:in [:variant/in] :out [:story/out]}}
             (causa-preset/merge-preset story vari))))))

;; ---- pure: resolve-preset ------------------------------------------------

(deftest resolve-preset-returns-nil-when-no-preset
  (testing "story + variant with no :causa slot resolves to nil"
    (story/reg-story :story.no-preset
      {:doc "no preset" :component :Some.view})
    (story/reg-variant :story.no-preset/v
      {:doc "v"})
    (is (nil? (causa-preset/resolve-preset :story.no-preset/v)))))

(deftest resolve-preset-reads-story-slot
  (testing "story :causa is returned when variant has none"
    (story/reg-story :story.story-preset
      {:doc "preset on story"
       :component :Some.view
       :causa {:open? true :tab :issues}})
    (story/reg-variant :story.story-preset/v
      {:doc "v"})
    (let [p (causa-preset/resolve-preset :story.story-preset/v)]
      (is (= true     (:open? p)))
      (is (= :issues  (:tab   p))))))

(deftest resolve-preset-merges-story-and-variant
  (testing "variant :causa overrides story slot, :filters deep-merge"
    (story/reg-story :story.both
      {:doc "preset on both"
       :component :Some.view
       :causa {:open? true
               :tab   :event-detail
               :filters {:in [:keep/x]}}})
    (story/reg-variant :story.both/v
      {:doc "v"
       :causa {:tab :issues
               :filters {:out [:drop/y]}}})
    (let [p (causa-preset/resolve-preset :story.both/v)]
      (is (= true                                (:open? p)))
      (is (= :issues                             (:tab   p)))
      (is (= {:in [:keep/x] :out [:drop/y]}      (:filters p))))))

;; ---- CLJS-only: apply-preset! --------------------------------------------

#?(:cljs
   (deftest cljs-apply-preset-no-causa-no-op
     (testing "apply-preset! is a no-op when Causa is not on the classpath"
       (story/reg-story :story.no-causa
         {:doc "no causa"
          :component :Some.view
          :causa {:open? true :tab :issues}})
       (story/reg-variant :story.no-causa/v
         {:doc "v"})
       ;; The test build has no Causa namespace loaded, so
       ;; causa-available? is false and apply-preset! returns nil
       ;; without dispatching.
       (is (false? (causa-preset/causa-available?))
           "this test assumes Causa is NOT on the classpath")
       (is (nil? (causa-preset/apply-preset! :story.no-causa/v))))))

#?(:cljs
   (deftest cljs-apply-preset-nil-on-missing-preset
     (testing "no :causa slot → no work, returns nil even when Causa would be available"
       (story/reg-story :story.nilpre
         {:doc "no slot"
          :component :Some.view})
       (story/reg-variant :story.nilpre/v
         {:doc "v"})
       (is (nil? (causa-preset/apply-preset! :story.nilpre/v))))))

;; ---- CLJS-only: project-root propagator (rf2-r1uod) ----------------------

#?(:cljs
   (deftest cljs-propagate-project-root-no-causa-no-op
     (testing "propagate-project-root! is a no-op when Causa config is not on the classpath"
       ;; Seed Story's project-root via configure! — exercises the
       ;; whole configure! → set-project-root! → propagator pipeline.
       (story/configure! {:project-root "C:/Users/me/code/my-app"})
       ;; The test build has no Causa namespace loaded, so
       ;; causa-config-available? is false and propagate-project-root!
       ;; returns nil without touching the wire.
       (is (false? (causa-preset/causa-config-available?))
           "this test assumes Causa is NOT on the classpath")
       (is (nil? (causa-preset/propagate-project-root!))
           "no propagation when Causa is absent")
       ;; Reset so subsequent tests don't see the seeded value.
       (story/configure! {:project-root nil}))))

#?(:cljs
   (deftest cljs-propagate-project-root-nil-when-unset
     (testing "propagate-project-root! returns nil when Story has no project-root configured"
       ;; Clear any prior seed (the fixture resets registrar but not
       ;; the config atom).
       (story/configure! {:project-root nil})
       (is (nil? (causa-preset/propagate-project-root!))
           "no propagation when Story's project-root is nil"))))

;; CLJS-only tests for the keybinding-disable bridge (rf2-q7who.1)
;; live in `re-frame.story.causa-preset-cljs-test` — separate file so
;; the `:node-test` build's `cljs-test$` ns-regexp picks them up (the
;; .cljc file's namespace name does not).
