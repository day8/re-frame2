(ns re-frame.story.xray-preset-test
  "Tests for per-story Xray preset (rf2-q9kv5).

  Coverage layers:

  - **Pure data** (JVM + CLJS): `merge-preset` deep-merge semantics,
    `resolve-preset` story+variant resolution.
  - **CLJS-only side-effects**: `apply-preset!` feature-detect graceful
    no-op when Xray is absent; `apply-preset!` dispatches the right
    events when shimmed handlers are in place.

  This namespace is `.cljc` so the pure surface runs on both JVM and
  CLJS test runners; CLJS-only blocks exercise the dispatch path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.xray-preset :as xray-preset]
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

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: merge-preset --------------------------------------------------

(deftest merge-preset-handles-nils
  (testing "two nils merge to {}"
    (is (= {} (xray-preset/merge-preset nil nil)))))

(deftest merge-preset-variant-overrides-story
  (testing "variant slot wins over story slot at the top level"
    (let [story {:open? true :tab :epoch}
          vari  {:tab :issues}]
      (is (= {:open? true :tab :issues}
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
       :xray {:open? true :tab :issues}})
    (story/reg-variant :story.story-preset/v
      {:doc "v"})
    (let [p (xray-preset/resolve-preset :story.story-preset/v)]
      (is (= true     (:open? p)))
      (is (= :issues  (:tab   p))))))

(deftest resolve-preset-merges-story-and-variant
  (testing "variant :xray overrides story slot, :filters deep-merge"
    (story/reg-story :story.both
      {:doc "preset on both"
       :component :Some.view
       :xray {:open? true
               :tab   :epoch
               :filters {:in [:keep/x]}}})
    (story/reg-variant :story.both/v
      {:doc "v"
       :xray {:tab :issues
               :filters {:out [:drop/y]}}})
    (let [p (xray-preset/resolve-preset :story.both/v)]
      (is (= true                                (:open? p)))
      (is (= :issues                             (:tab   p)))
      (is (= {:in [:keep/x] :out [:drop/y]}      (:filters p))))))

;; ---- CLJS-only: apply-preset! --------------------------------------------

#?(:cljs
   (deftest cljs-apply-preset-no-xray-no-op
     (testing "apply-preset! is a no-op when Xray is not on the classpath"
       (story/reg-story :story.no-xray
         {:doc "no xray"
          :component :Some.view
          :xray {:open? true :tab :issues}})
       (story/reg-variant :story.no-xray/v
         {:doc "v"})
       ;; rf2-ibpwr: post-fix `xray-available?` is a compile-time
       ;; symbol resolution check; Xray IS on the test classpath
       ;; (Story's `xray-embed.cljs` directly requires
       ;; `day8.re-frame2-xray.mount`). To exercise the no-Xray
       ;; no-op path we shim the predicate via `with-redefs` — the
       ;; production code path is what the predicate naturally
       ;; reports; the shim is the test surface for the absent-Xray
       ;; degraded posture.
       (with-redefs [xray-preset/xray-available? (constantly false)]
         (is (nil? (xray-preset/apply-preset! :story.no-xray/v))
             "apply-preset! returns nil when xray-available? is false")))))

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
   (deftest cljs-propagate-project-root-no-xray-no-op
     (testing "propagate-project-root! is a no-op when Xray config is not on the classpath"
       ;; Seed Story's project-root via configure! — exercises the
       ;; whole configure! → set-project-root! → propagator pipeline.
       (story/configure! {:rf.story/project-root "C:/Users/me/code/my-app"})
       ;; The test build has no Xray namespace loaded, so
       ;; xray-config-available? is false and propagate-project-root!
       ;; returns nil without touching the wire.
       (is (false? (xray-preset/xray-config-available?))
           "this test assumes Xray is NOT on the classpath")
       (is (nil? (xray-preset/propagate-project-root!))
           "no propagation when Xray is absent")
       ;; Reset so subsequent tests don't see the seeded value.
       (story/configure! {:rf.story/project-root nil}))))

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
