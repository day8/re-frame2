(ns re-frame.story-layout-debug-test
  "JVM tests for the layout-debug decorator trio: canonical registration as
  `:kind :hiccup` decorators, the pure stylesheet and forced-state builders,
  the hiccup each wrap fn returns, and decorator resolution
  (`002-Runtime.md` §Decorator composition order)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as rf.frame]
            [re-frame.machines        :as rf.machines]
            [re-frame.registrar       :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story           :as rf.story]
            [re-frame.story.config    :as rf.story.config]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.layout-debug :as rf.story.layout-debug]
            [re-frame.story.loaders   :as rf.story.loaders]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-fixture [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (rf.story.layout-debug/reset-wrap-counter!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-fixture)

;; ---- registration --------------------------------------------------------

(deftest decorator-bodies-are-hiccup-kind
  (testing "each layout-debug decorator declares :kind :hiccup"
    (doseq [id rf.story.layout-debug/canonical-decorator-ids]
      (let [body (rf.story/handler-meta :decorator id)]
        (is (= :hiccup (:kind body)))
        (is (fn? (:wrap body)))))))

;; ---- pure stylesheet builders -------------------------------------------

(deftest outline-stylesheet-scoped
  (testing "outline-stylesheet scopes every selector to the wrap class"
    (let [css (rf.story.layout-debug/build-outline-stylesheet "rf-test-1")]
      (is (string? css))
      (is (re-find #"\.rf-test-1 \*" css))
      (is (re-find #"\.rf-test-1 div" css))
      (is (re-find #"\.rf-test-1 button" css)))))

(deftest measure-stylesheet-scoped
  (testing "measure-stylesheet targets :hover under the wrap class"
    (let [css (rf.story.layout-debug/build-measure-stylesheet "rf-test-2")]
      (is (re-find #"\.rf-test-2 \*:hover" css))
      (is (re-find #"::before" css)))))

(deftest pseudo-stylesheet-includes-states
  (testing "pseudo-stylesheet has a rule for each requested state and no other"
    (doseq [states [#{:hover} #{:hover :focus} #{:hover :focus :active :visited}]
            :let [css (rf.story.layout-debug/build-pseudo-stylesheet "rf-test-3" states)]
            state [:hover :focus :active :visited]]
      (is (= (contains? states state) (boolean (re-find (re-pattern (str "force-" (name state))) css)))
          (str states " " state)))))

(deftest forced-state-classes-stable
  (testing "forced-state-classes produces deterministic ordering"
    (is (= "force-focus force-hover"
           (rf.story.layout-debug/forced-state-classes #{:hover :focus})))
    (is (= "force-active force-focus force-hover"
           (rf.story.layout-debug/forced-state-classes #{:hover :focus :active})))))

;; ---- wrap fn shape ------------------------------------------------------

(deftest measure-wrap-shape
  (testing "the measure decorator's wrap fn returns [:div attrs [:style css] body]"
    (let [wrap (:wrap (rf.story/handler-meta :decorator rf.story.layout-debug/id-measure))
          [tag attrs [style-tag css] :as out] (wrap [:span "x"] {})]
      (is (vector? out))
      (is (= [:div true true :style] [tag (:data-rf-story-measure attrs) (string? (:class attrs)) style-tag]))
      (is (re-find #":hover" css)))))

(deftest pseudo-wrap-with-ref-args
  (testing "the pseudo wrap reads its states from :decorator/args, defaulting to #{:hover}"
    (let [wrap  (:wrap (rf.story/handler-meta :decorator rf.story.layout-debug/id-pseudo))
          class #(:class (second (wrap [:span "x"] %)))]
      (is (= [true true] (map #(boolean (re-find % (class {:decorator/args [#{:hover :focus}]})))
                              [#"force-focus" #"force-hover"])))
      (is (= [true false] (map #(boolean (re-find % (class {})))
                               [#"force-hover" #"force-focus"]))))))

;; ---- decorator resolution -----------------------------------------------

(deftest layout-debug-resolves-as-hiccup
  (testing "a variant declaring a layout-debug decorator resolves into :hiccup"
    (rf.story/reg-variant* :story.x/y {:decorators [[rf.story.layout-debug/id-outline]]})
    (let [pack (rf.story.decorators/resolve-decorators :story.x/y)]
      (is (= [[rf.story.layout-debug/id-outline] true]
             [(mapv :id (:hiccup pack)) (empty? (:errors pack))])))))

;; ---- wrap-id counter ----------------------------------------------------

(deftest wrap-counter-monotonic
  (rf.story.layout-debug/reset-wrap-counter!)
  (is (= ["rf-story-debug-1" "rf-story-debug-2" "rf-story-debug-3"]
         (vec (repeatedly 3 rf.story.layout-debug/next-wrap-id)))))

;; ---- public API surface -------------------------------------------------

(deftest public-decorator-ids-exposed
  (testing "the public decorator-id Vars on re-frame.story carry the canonical
            ids, and they are the canonical set"
    (let [ids [:rf.story/layout-debug.measure :rf.story/layout-debug.outline
               :rf.story/layout-debug.pseudo]]
      (is (= ids [rf.story/layout-debug-measure-id rf.story/layout-debug-outline-id
                  rf.story/layout-debug-pseudo-id]))
      (is (= (set ids) rf.story.layout-debug/canonical-decorator-ids)))))
