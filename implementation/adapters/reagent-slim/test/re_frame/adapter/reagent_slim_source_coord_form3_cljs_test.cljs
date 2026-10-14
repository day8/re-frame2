(ns re-frame.adapter.reagent-slim-source-coord-form3-cljs-test
  "A real reagent-slim `create-class` (Form-3) through
  `re-frame.views.source-coord-annotation/inject-source-coord-attr`. A class
  root has no DOM node to annotate, so the wrapper must pass it through
  UNCHANGED. A slim class carries `cljsReagentClass` and `cljsReagentRender`,
  never stock Reagent's `prototype.reagentRender`, so a predicate keyed only
  on the stock marker would wrap it as a Form-2 fn and lose its React
  lifecycle."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.views.source-coord-annotation :as rf.views.source-coord-annotation]
            [reagent2.core :as r2]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

;; ---- helper: capture console.warn calls -----------------------------------

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns a
  vector of the joined-string messages observed. Restores the original on the
  way out, even if thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

;; ---- one-shot non-DOM-root warning, truthful ------------------------------

(deftest slim-form-3-warns-non-dom-root-once
  (testing "a Form-3 class root is a non-DOM root: the wrapper hands the class
            back UNCHANGED, so its React lifecycle is reagent2's own, and emits
            the documented one-shot warning per id. The warning fires EXACTLY
            ONCE across repeated renders (truthful, one-shot — the warned-set
            is a process-wide defonce cleared between tests by the
            reset-runtime fixture)."
    (let [slim-class (r2/create-class {:reagent-render (fn [] [:div])})
          outs       (atom [])
          warnings   (with-captured-console-warn
                       (fn []
                         (dotimes [_ 5]
                           (swap! outs conj
                                  (rf.views.source-coord-annotation/inject-source-coord-attr
                                    :rf.slim-src-coord/warn-once-f3
                                    "rf.slim-src-coord:warn-once-f3:1:1"
                                    slim-class)))))]
      (is (= [1 true true]
             [(count warnings)
              (str/includes? (str (first warnings)) "rf.slim-src-coord/warn-once-f3")
              (every? #(identical? slim-class %) @outs)])
          (str "the class passes through unchanged, with exactly one warning across 5"
               " passes naming the view-id; got " (pr-str warnings))))))
