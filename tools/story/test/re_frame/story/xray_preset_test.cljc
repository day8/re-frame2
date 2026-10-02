(ns re-frame.story.xray-preset-test
  "Tests for per-story Xray preset.

  Scope: the PURE data surface only — `merge-preset` deep-merge
  semantics, `resolve-preset` story+variant resolution, `lower-filters`
  and the closed preset map. All run on the JVM.

  CLJS-only side-effect coverage (the mount / config / keybinding
  bridges) lives in `re-frame.story.xray-preset-cljs-test`, because
  only a `-cljs-test` namespace is discovered by the `:node-test`
  build. See the note at the foot of this file."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.xray-preset :as rf.story.xray-preset]))

;; ---- fixtures -----------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: merge-preset --------------------------------------------------

(deftest merge-preset-handles-nils
  (testing "two nils merge to {}"
    (is (= {} (rf.story.xray-preset/merge-preset nil nil)))))

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
    (is (= {:open? true :panel :trace}
           (rf.story.xray-preset/resolve-preset :story.story-preset/v)))))

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
    (is (= {:open?   true
            :panel   :trace
            :filters {:in [:keep/x] :out [:drop/y]}}
           (rf.story.xray-preset/resolve-preset :story.both/v)))))

;; ---- pure: lower-filters -------------------------------------------------
;;
;; The Story→Xray wire boundary. Story's schema accepts bare event-id
;; keywords; Xray's matcher reads a bare keyword as the `:never` kind.
;; These tests pin the translation itself — the LIVE application of the
;; lowered set against a real `:rf/xray` frame is asserted in the
;; `-cljs-test` sibling.

(deftest lower-filters-lowers-story-keywords-to-xray-pills
  (let [typed {:kind :machine :params {:machine-id :m/one}}]
    (are [filters lowered] (= lowered (rf.story.xray-preset/lower-filters filters))
      ;; a bare event-id keyword becomes Xray's {:pattern <kw>} pill
      {:out [:app/noise]}                 {:in [] :out [{:pattern :app/noise}]}
      ;; a missing axis lowers to [] — Xray's :active-filters slot expects
      ;; the full {:in [...] :out [...]} shape, never a nil axis
      {:in [:keep/x]}                     {:in [{:pattern :keep/x}] :out []}
      {}                                  {:in [] :out []}
      ;; every entry on each axis lowers
      {:in [:a/one :a/two] :out [:b/one]} {:in  [{:pattern :a/one} {:pattern :a/two}]
                                           :out [{:pattern :b/one}]}
      ;; an already-canonical typed pill passes through un-double-wrapped
      {:in [typed] :out [:b/two]}         {:in [typed] :out [{:pattern :b/two}]}
      ;; a non-map :filters slot lowers to nil rather than throwing
      nil                                 nil
      [:app/noise]                        nil)))

;; ---- the preset map is closed --------------------------------------------
;;
;; `:open?`, `:panel` and `:filters` are the whole preset. A slot the
;; schema does not declare, such as `:focus` or a typo'd `:pannel`,
;; rejects at registration, naming the key and where it sits, instead of
;; registering and doing nothing.

(defn- shape-error
  "Call `f` and return the thrown ex-data, or nil when it did not throw."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e))))

(deftest xray-preset-rejects-undeclared-slots
  (doseq [[kind id reg!] [[:story :story.closed-preset rf.story/reg-story*]
                          [:variant :story.closed-preset/v rf.story/reg-variant*]]
          :let [error-id (keyword "rf.error" (str (name kind) "-shape"))]]
    (testing (str "a " (name kind) " preset carrying :focus is rejected")
      (let [data (shape-error #(reg! id {:xray {:panel :trace :focus {:event-pos 5}}}))]
        (is (= error-id (:rf.error/id data))
            ":focus is not a preset slot, so the closed preset map refuses it")
        (is (str/includes? (str (:reason data)) ":focus in [:xray]")
            ":reason names the key and the preset map it sits in")
        (is (not (rf.story/registered? kind id))
            "nothing is registered")))
    (testing (str "a " (name kind) " preset carrying a typo'd slot is rejected")
      (let [data (shape-error #(reg! id {:xray {:pannel :trace}}))]
        (is (= error-id (:rf.error/id data)))
        (is (str/includes? (str (:reason data)) ":pannel in [:xray] (did you mean :panel?)"))))
    (testing (str "control: a " (name kind) " preset using only declared slots registers")
      (is (nil? (shape-error #(reg! id {:xray {:open? true :panel :trace
                                               :filters {:out [:app/noise]}}}))))
      (is (rf.story/registered? kind id)))))

;; ---- Why there are no CLJS-only tests in this file -----------------------
;;
;; This namespace is `re-frame.story.xray-preset-test`. The `:node-test`
;; build's ns-regexp is `cljs-test$` (implementation/shadow-cljs.edn), so
;; this name does NOT match and the namespace is never loaded by
;; `npm run test:cljs`. On the JVM the reader elides `#?(:cljs …)`. A
;; CLJS-only `deftest` placed here therefore runs on NO host — it is
;; dead code that reads as coverage.
;;
;; Keep this file to the pure `.cljc` surface (merge / resolve / lower /
;; the closed preset map), which the JVM lane runs. Anything CLJS-only
;; belongs in the live sibling `re-frame.story.xray-preset-cljs-test`.
