(ns re-frame.views.source-coord-warn-parity-cljs-test
  "Both source-coord annotation walks warn on the same roots: the Reagent
  hiccup walk (`re-frame.views.source-coord-annotation/inject-source-coord-attr`)
  and the React-hook walk (`re-frame.substrate.spine/inject-source-coord-attr`)
  warn on any non-nil un-annotatable root, and stay silent on nil (a view may
  render nothing). The message text is shared, so only the trigger can drift.
  Both walks are driven directly and observed through a `js/console.warn`
  stub."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.substrate.spine :as rf.substrate.spine]
            [re-frame.views.source-coord-annotation :as rf.views.source-coord-annotation]
            [re-frame.views.warn-once :as rf.views.warn-once]))

(def ^:private orig-console-warn (when (exists? js/console) (.-warn js/console)))

(defn console-warn-stub-fixture
  "Count `js/console.warn` calls, and clear the process-wide non-DOM-root
  warn-once cache on both sides of each test so no first-encounter warning
  crosses a test boundary."
  [test-fn]
  (rf.views.warn-once/clear-warned-non-dom-roots!)
  (let [calls (atom 0)]
    (set! (.-warn js/console) (fn [& _] (swap! calls inc)))
    (set! (.-rf2-warn-calls js/console) calls)
    (try (test-fn)
         (finally
           (set! (.-warn js/console) orig-console-warn)
           (rf.views.warn-once/clear-warned-non-dom-roots!)))))

(use-fixtures :each console-warn-stub-fixture)

(defn- warn-count [] @(.-rf2-warn-calls js/console))

;; Warnings the Reagent hiccup walk emits for `out`, from a fresh cache.
(defn- reagent-warned? [out]
  (rf.views.warn-once/clear-warned-non-dom-roots!)
  (let [before (warn-count)]
    (rf.views.source-coord-annotation/inject-source-coord-attr :rf.test/view "rf.test:view:1:1" out)
    (- (warn-count) before)))

;; Warnings the React-hook walk emits for `out`, routed through the same
;; warn-once helper the production wiring uses.
(defn- spine-warned? [out]
  (rf.views.warn-once/clear-warned-non-dom-roots!)
  (let [before  (warn-count)
        warn-fn (fn [id type-tag] (rf.views.warn-once/warn-non-dom-root! id type-tag))]
    (#'rf.substrate.spine/inject-source-coord-attr
      warn-fn :rf.test/view "rf.test:view:1:1" ":rf.test/view" out)
    (- (warn-count) before)))

(deftest un-annotatable-roots-warn-identically-on-both-walks
  (are [walk out warned] (= warned (walk out))
    reagent-warned? "just a string" 1
    spine-warned?   "just a string" 1
    reagent-warned? nil             0
    spine-warned?   nil             0))

(deftest fn-headed-vector-warns-on-reagent-walk
  (is (= 1 (reagent-warned? [(fn [] [:div]) "child"]))
      "a component-headed vector is not annotated, so it warns"))

(deftest interop-head-roots-pass-through-the-reagent-walk
  (testing "`:r>`, `:f>` and `:>` carry the component at position 1, the slot
            a DOM root's attrs map is spliced into, so they come back
            untouched with the non-DOM-root warning"
    (let [comp-fn (fn [] [:div])]
      (doseq [out [[:f> comp-fn "arg"]
                   [:r> comp-fn #js {} [:child]]
                   [:> comp-fn {:a 1}]]]
        (rf.views.warn-once/clear-warned-non-dom-roots!)
        (let [before (warn-count)
              result (rf.views.source-coord-annotation/inject-source-coord-attr
                       :rf.test/view "rf.test:view:1:1" out)]
          (is (identical? out result)
              (str (first out) " root comes back untouched, component still at position 1"))
          (is (= 1 (- (warn-count) before))
              (str (first out) " root takes the non-DOM-root warning")))))
    (testing "control: a DOM-tag root IS annotated by the same call"
      (is (= [:div {:data-rf2-source-coord "rf.test:view:1:1"
                    :data-rf-view          ":rf.test/view"} "hi"]
             (rf.views.source-coord-annotation/inject-source-coord-attr
               :rf.test/view "rf.test:view:1:1" [:div "hi"]))))))
