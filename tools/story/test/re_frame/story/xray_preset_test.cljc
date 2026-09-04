(ns re-frame.story.xray-preset-test
  "Tests for per-story Xray preset (rf2-q9kv5).

  Scope: the PURE data surface only — `merge-preset` deep-merge
  semantics and `resolve-preset` story+variant resolution. Both run on
  JVM and CLJS.

  CLJS-only side-effect coverage (the mount / config / keybinding
  bridges) lives in `re-frame.story.xray-preset-cljs-test`, because
  only a `-cljs-test` namespace is discovered by the `:node-test`
  build. See the note at the foot of this file."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.xray-preset :as rf.story.xray-preset]
            #?@(:cljs [[re-frame.core :as rf]
                       [re-frame.frame :as rf.frame]
                       [re-frame.registrar :as rf.registrar]
                       [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]])))

;; ---- fixtures -----------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  #?(:cljs (do (rf.registrar/clear-all!)
               (reset! rf.frame/frames {})
               (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
               (rf.frame/ensure-default-frame!)))
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: merge-preset --------------------------------------------------

(deftest merge-preset-handles-nils
  (testing "two nils merge to {}"
    (is (= {} (rf.story.xray-preset/merge-preset nil nil)))))

(deftest merge-preset-variant-overrides-story
  (testing "variant slot wins over story slot at the top level"
    (let [story {:open? true :panel :epoch}
          vari  {:panel :trace}]
      (is (= {:open? true :panel :trace}
             (rf.story.xray-preset/merge-preset story vari))))))

(deftest merge-preset-deep-merges-filters
  (testing ":filters merge respects :in / :out separately"
    (let [story {:filters {:in [:keep/me]}}
          vari  {:filters {:out [:drop/me]}}]
      (is (= {:filters {:in [:keep/me] :out [:drop/me]}}
             (rf.story.xray-preset/merge-preset story vari))))))

(deftest merge-preset-variant-filters-override-story
  (testing "matching filter axes prefer variant value"
    (let [story {:filters {:in [:story/in] :out [:story/out]}}
          vari  {:filters {:in [:variant/in]}}]
      (is (= {:filters {:in [:variant/in] :out [:story/out]}}
             (rf.story.xray-preset/merge-preset story vari))))))

;; ---- pure: resolve-preset ------------------------------------------------

(deftest resolve-preset-returns-nil-when-no-preset
  (testing "story + variant with no :xray slot resolves to nil"
    (rf.story/reg-story :story.no-preset
      {:doc "no preset" :component :Some.view})
    (rf.story/reg-variant :story.no-preset/v
      {:doc "v"})
    (is (nil? (rf.story.xray-preset/resolve-preset :story.no-preset/v)))))

(deftest resolve-preset-reads-story-slot
  (testing "story :xray is returned when variant has none"
    (rf.story/reg-story :story.story-preset
      {:doc "preset on story"
       :component :Some.view
       :xray {:open? true :panel :trace}})
    (rf.story/reg-variant :story.story-preset/v
      {:doc "v"})
    (let [p (rf.story.xray-preset/resolve-preset :story.story-preset/v)]
      (is (= true     (:open? p)))
      (is (= :trace   (:panel p))))))

(deftest resolve-preset-merges-story-and-variant
  (testing "variant :xray overrides story slot, :filters deep-merge"
    (rf.story/reg-story :story.both
      {:doc "preset on both"
       :component :Some.view
       :xray {:open? true
               :panel :epoch
               :filters {:in [:keep/x]}}})
    (rf.story/reg-variant :story.both/v
      {:doc "v"
       :xray {:panel :trace
               :filters {:out [:drop/y]}}})
    (let [p (rf.story.xray-preset/resolve-preset :story.both/v)]
      (is (= true                                (:open? p)))
      (is (= :trace                              (:panel p)))
      (is (= {:in [:keep/x] :out [:drop/y]}      (:filters p))))))

;; ---- pure: lower-filters (rf2-q5pd6) -------------------------------------
;;
;; The Story→Xray wire boundary. Story's schema accepts bare event-id
;; keywords; Xray's matcher reads a bare keyword as the `:never` kind.
;; These tests pin the translation itself — the LIVE application of the
;; lowered set against a real `:rf/xray` frame is asserted in the
;; `-cljs-test` sibling.

(deftest lower-filters-wraps-keywords-as-pattern-pills
  (testing "a bare event-id keyword becomes Xray's {:pattern <kw>} pill"
    (is (= {:in [] :out [{:pattern :app/noise}]}
           (rf.story.xray-preset/lower-filters {:out [:app/noise]})))))

(deftest lower-filters-normalises-both-axes
  (testing "a preset declaring only one axis still yields the full
            {:in [...] :out [...]} shape Xray's :active-filters slot
            expects — a missing axis must not land as nil in the slot"
    (is (= {:in [{:pattern :keep/x}] :out []}
           (rf.story.xray-preset/lower-filters {:in [:keep/x]})))
    (is (= {:in [] :out []}
           (rf.story.xray-preset/lower-filters {})))))

(deftest lower-filters-lowers-every-entry
  (testing "multiple pills per axis all lower"
    (is (= {:in  [{:pattern :a/one} {:pattern :a/two}]
            :out [{:pattern :b/one}]}
           (rf.story.xray-preset/lower-filters {:in [:a/one :a/two] :out [:b/one]})))))

(deftest lower-filters-passes-maps-through
  (testing "an already-canonical typed pill survives the boundary
            un-double-wrapped (no {:pattern {:kind …}} nesting)"
    (let [typed {:kind :machine :params {:machine-id :m/one}}]
      (is (= {:in [typed] :out [{:pattern :b/two}]}
             (rf.story.xray-preset/lower-filters {:in [typed] :out [:b/two]}))))))

(deftest lower-filters-nil-on-non-map
  (testing "a non-map :filters slot lowers to nil rather than throwing"
    (is (nil? (rf.story.xray-preset/lower-filters nil)))
    (is (nil? (rf.story.xray-preset/lower-filters [:app/noise])))))

;; ---- Why there are no CLJS-only tests in this file -----------------------
;;
;; This namespace is `re-frame.story.xray-preset-test`. The `:node-test`
;; build's ns-regexp is `cljs-test$` (implementation/shadow-cljs.edn), so
;; this name does NOT match and the namespace is never loaded by
;; `npm run test:cljs`. On the JVM the reader elides `#?(:cljs …)`. A
;; CLJS-only `deftest` placed here therefore runs on NO host — it is
;; dead code that reads as coverage.
;;
;; rf2-r8trk found three such tests here and moved them to the live
;; sibling `re-frame.story.xray-preset-cljs-test`. One of them had been
;; asserting `(false? (xray-config-available?))` under the comment "this
;; test assumes Xray is NOT on the classpath" — a claim that was false
;; even then, and that nothing could catch because the test never ran.
;;
;; Keep this file to the pure `.cljc` surface (merge / resolve), which
;; genuinely runs on both hosts. Anything CLJS-only belongs in the
;; `-cljs-test` sibling.
