(ns re-frame.story-layout-debug-test
  "JVM tests for Stage 6 (rf2-zhwd) — layout-debug decorator trio.

  Coverage:
  - The three decorators register under canonical ids.
  - `:kind :hiccup` schema validation passes.
  - The pure stylesheet builders return CSS strings scoped to a class.
  - The pure forced-state-classes helper returns deterministic output.
  - Decorator-resolution returns the registered decorators classified
    as `:hiccup` per IMPL-SPEC §5.3.

  The DOM-mutation behaviour (the wrap fns produce `[:div [:style] body]`)
  is exercised in JVM by calling the wrap fn directly — we can't
  exercise the actual browser behaviour, but the hiccup shape is
  JVM-testable."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as frame]
            [re-frame.machines        :as machines]
            [re-frame.registrar       :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story           :as story]
            [re-frame.story.config    :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.loaders   :as loaders]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-fixture [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (layout-debug/reset-wrap-counter!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-fixture)

;; ---- registration --------------------------------------------------------

(deftest three-decorators-register
  (testing "the three layout-debug decorators register under canonical ids"
    (let [decs (story/registrations :decorator)]
      (is (contains? decs layout-debug/id-measure))
      (is (contains? decs layout-debug/id-outline))
      (is (contains? decs layout-debug/id-pseudo)))))

(deftest decorator-bodies-are-hiccup-kind
  (testing "each layout-debug decorator declares :kind :hiccup"
    (doseq [id layout-debug/canonical-decorator-ids]
      (let [body (story/handler-meta :decorator id)]
        (is (= :hiccup (:kind body)))
        (is (fn? (:wrap body)))))))

(deftest canonical-decorator-ids-set
  (testing "the canonical ids set is the three expected decorators"
    (is (= #{:rf.story/layout-debug.measure
             :rf.story/layout-debug.outline
             :rf.story/layout-debug.pseudo}
           layout-debug/canonical-decorator-ids))))

;; ---- pure stylesheet builders -------------------------------------------

(deftest outline-stylesheet-scoped
  (testing "outline-stylesheet scopes every selector to the wrap class"
    (let [css (layout-debug/build-outline-stylesheet "rf-test-1")]
      (is (string? css))
      (is (re-find #"\.rf-test-1 \*" css))
      (is (re-find #"\.rf-test-1 div" css))
      (is (re-find #"\.rf-test-1 button" css)))))

(deftest measure-stylesheet-scoped
  (testing "measure-stylesheet targets :hover under the wrap class"
    (let [css (layout-debug/build-measure-stylesheet "rf-test-2")]
      (is (re-find #"\.rf-test-2 \*:hover" css))
      (is (re-find #"::before" css)))))

(deftest pseudo-stylesheet-includes-states
  (testing "pseudo-stylesheet has a rule per requested state"
    (let [css-h (layout-debug/build-pseudo-stylesheet "rf-test-3" #{:hover})
          css-hf (layout-debug/build-pseudo-stylesheet "rf-test-3" #{:hover :focus})
          css-all (layout-debug/build-pseudo-stylesheet "rf-test-3"
                                                       #{:hover :focus :active :visited})]
      (is (re-find #"force-hover" css-h))
      (is (not (re-find #"force-focus" css-h)))
      (is (re-find #"force-hover" css-hf))
      (is (re-find #"force-focus" css-hf))
      (is (re-find #"force-active" css-all))
      (is (re-find #"force-visited" css-all)))))

(deftest forced-state-classes-stable
  (testing "forced-state-classes produces deterministic ordering"
    (is (= "force-focus force-hover"
           (layout-debug/forced-state-classes #{:hover :focus})))
    (is (= "force-active force-focus force-hover"
           (layout-debug/forced-state-classes #{:hover :focus :active})))))

;; ---- wrap fn shape ------------------------------------------------------

(deftest measure-wrap-shape
  (testing "the measure decorator's wrap fn returns [:div [:style ...] body]"
    (let [body  {:wrap (-> (story/handler-meta :decorator layout-debug/id-measure)
                            :wrap)}
          wrap  (:wrap body)
          out   (wrap [:span "x"] {})]
      (is (vector? out))
      (is (= :div (first out)))
      (let [attrs (second out)]
        (is (true? (:data-rf-story-measure attrs)))
        (is (string? (:class attrs))))
      (let [style-form (nth out 2)]
        (is (vector? style-form))
        (is (= :style (first style-form)))
        (is (re-find #":hover" (second style-form)))))))

(deftest pseudo-wrap-with-ref-args
  (testing "pseudo wrap reads ref-args via :decorator/args"
    (let [body (story/handler-meta :decorator layout-debug/id-pseudo)
          wrap (:wrap body)
          out  (wrap [:span "x"] {:decorator/args [#{:hover :focus}]})
          attrs (second out)]
      (is (re-find #"force-focus" (:class attrs)))
      (is (re-find #"force-hover" (:class attrs))))))

(deftest pseudo-wrap-default-state
  (testing "pseudo wrap defaults to #{:hover} when no ref-args"
    (let [body (story/handler-meta :decorator layout-debug/id-pseudo)
          wrap (:wrap body)
          out  (wrap [:span "x"] {})
          attrs (second out)]
      (is (re-find #"force-hover" (:class attrs)))
      (is (not (re-find #"force-focus" (:class attrs)))))))

;; ---- decorator resolution -----------------------------------------------

(deftest layout-debug-resolves-as-hiccup
  (testing "a variant declaring a layout-debug decorator resolves into :hiccup"
    (story/reg-variant* :story.x/y
                        {:decorators [[layout-debug/id-outline]]})
    (let [pack (decorators/resolve-decorators :story.x/y)]
      (is (= 1 (count (:hiccup pack))))
      (is (= layout-debug/id-outline (-> pack :hiccup first :id)))
      (is (empty? (:errors pack))))))

(deftest multiple-layout-debug-decorators-compose
  (testing "two layout-debug decorators on a variant compose in order"
    (story/reg-variant* :story.x/multi
                        {:decorators [[layout-debug/id-outline]
                                      [layout-debug/id-pseudo #{:focus}]]})
    (let [pack (decorators/resolve-decorators :story.x/multi)]
      (is (= 2 (count (:hiccup pack))))
      (is (= [layout-debug/id-outline layout-debug/id-pseudo]
             (mapv :id (:hiccup pack)))))))

;; ---- wrap-id counter ----------------------------------------------------

(deftest wrap-counter-monotonic
  (testing "next-wrap-id returns monotonic distinct ids"
    (layout-debug/reset-wrap-counter!)
    (let [a (layout-debug/next-wrap-id)
          b (layout-debug/next-wrap-id)
          c (layout-debug/next-wrap-id)]
      (is (= "rf-story-debug-1" a))
      (is (= "rf-story-debug-2" b))
      (is (= "rf-story-debug-3" c)))))

;; ---- public API surface -------------------------------------------------

(deftest public-decorator-ids-exposed
  (testing "the three decorator ids are re-exported on re-frame.story"
    (is (= layout-debug/id-measure  story/layout-debug-measure-id))
    (is (= layout-debug/id-outline  story/layout-debug-outline-id))
    (is (= layout-debug/id-pseudo   story/layout-debug-pseudo-id))))
