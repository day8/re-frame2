(ns re-frame.source-coord-warn-once-cljs-test
  "Per Spec 006 §Documented exemption: non-DOM roots:

    'A registered view whose root element is one of [Fragment, host-
     component head, function/component head] is exempt from the
     annotation. The adapter MUST emit a one-shot warning per id (so
     the developer learns the pair-tool footgun without spamming the
     console on re-render) and MUST NOT inject the attribute in
     these cases.'

  `source_coord_dom_cljs_test` pins the attribute side; this file pins the
  warning: `re-frame.views/warn-non-dom-root!` records each id in a
  process-wide `defonce` set, so each test uses its own ids."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helper: capture console.warn calls ----------------------------------

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns
  a vector of the messages observed (each message is the joined string
  of the call's args). Restores the original on the way out, even if
  thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args]
              (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

;; ---- Fragment root: warning fires exactly once per id ---------------------

(deftest fragment-root-warn-fires-once-across-multiple-renders
  (testing "a Fragment-headed view emits the documented warning EXACTLY ONCE
            across five renders"
    (rf/reg-view* :rf.warn-once-test/fragment-multi
                  (fn [] [:<> [:p "a"] [:p "b"]]))
    (let [render   (rf/view :rf.warn-once-test/fragment-multi)
          warnings (with-captured-console-warn
                     (fn [] (dotimes [_ 5] (render))))]
      (is (= [1 true] [(count warnings)
                       (str/includes? (str (first warnings)) "rf.warn-once-test/fragment-multi")])
          (str "exactly one warning, naming the view-id; got " (pr-str warnings))))))

;; ---- Per-id silencing is independent across ids --------------------------

(deftest warn-once-is-per-id-not-global
  (testing "The warn-once contract is keyed by view-id. Two different
            non-DOM-rooted views each emit their OWN one-shot warning
            (not a single global gate)."
    (rf/reg-view* :rf.warn-once-test/fragment-id-a
                  (fn [] [:<> [:p "a"]]))
    (rf/reg-view* :rf.warn-once-test/fragment-id-b
                  (fn [] [:<> [:p "b"]]))
    (let [warnings (with-captured-console-warn
                     (fn []
                       ;; 2 warnings: not 1 (a global gate), not 4 (per render).
                       (let [render-a (rf/view :rf.warn-once-test/fragment-id-a)
                             render-b (rf/view :rf.warn-once-test/fragment-id-b)]
                         (render-a) (render-b)
                         (render-a) (render-b))))]
      (is (= [2 true true]
             [(count warnings)
              (boolean (some #(str/includes? % "fragment-id-a") warnings))
              (boolean (some #(str/includes? % "fragment-id-b") warnings))])
          (str "one warning per id across 4 renders; got " (pr-str warnings))))))

;; ---- Form-3 class root: preserve identity + warn -------------------------

(deftest form-3-class-root-is-not-misclassified-as-form-2
  (testing "A create-class value is callable but is a component class, not a
            Form-2 render fn. The source-coord walk must return the exact class
            so Reagent can install its lifecycle, and must still emit the
            standard one-shot non-DOM-root warning because this outer view has
            no concrete DOM node to annotate."
    (let [view-id ::form-3-class-root
          klass   (r/create-class
                    {:display-name "rf2-form-3-source-coord-probe"
                     :reagent-render (fn [] [:div "form-3"])})]
      (rf/reg-view* view-id (fn [] klass))
      (let [render   (rf/view view-id)
            outputs  (atom [])
            warnings (with-captured-console-warn
                       (fn []
                         (dotimes [_ 3]
                           (swap! outputs conj (render)))))]
        (is (= [true 1 true]
               [(every? #(identical? klass %) @outputs)
                (count warnings)
                (str/includes? (str (first warnings)) "form-3-class-root")])
            "the walker returns the exact create-class constructor every time — no Form-2 wrapper — and the unannotatable root warns once, naming the view")))))
