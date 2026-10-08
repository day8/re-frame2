(ns re-frame.adapter.reagent-slim-source-coord-warn-once-cljs-test
  "A view with a non-DOM root is exempt from source-coord annotation and
  warns once per id (Spec 006 §Documented exemption), on slim as on the
  Reagent bridge (`re-frame.source-coord-warn-once-cljs-test`): both go
  through `re-frame.views/warn-non-dom-root!` and its process-wide warned-set,
  so each test uses its own ids."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

;; ---- helper: capture console.warn calls ----------------------------------

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns
  a vector of the joined-string messages observed. Restores the original
  on the way out, even if thunk throws."
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

;; ---- Fragment root: warning fires exactly once per id ---------------------

(deftest fragment-root-warn-fires-once-across-multiple-renders-slim
  (testing "A Fragment-headed reg-view'd component under slim renders
            without the :data-rf2-source-coord attribute AND emits the
            documented warning EXACTLY ONCE — the second through fifth
            re-render do NOT re-emit. The warned-set is a `defonce` atom
            in re-frame.views (shared with the Reagent bridge), so a
            unique id per test avoids cross-test contamination."
    (rf/reg-view* :rf.slim-warn-once-test/fragment-multi
                  (fn [] [:<> [:p "a"] [:p "b"]]))
    (let [render   (rf/view :rf.slim-warn-once-test/fragment-multi)
          warnings (with-captured-console-warn
                     (fn [] (dotimes [_ 5] (render))))]
      (is (= [1 true true]
             [(count warnings)
              (str/includes? (str (first warnings)) "rf.slim-warn-once-test/fragment-multi")
              (str/includes? (str (first warnings)) "data-rf2-source-coord")])
          (str "exactly one warning across 5 renders, naming the view-id and the skipped attribute; got "
               (pr-str warnings))))))

;; ---- Per-id silencing is independent across ids --------------------------

(deftest warn-once-is-per-id-not-global-slim
  (testing "The warn-once contract is keyed by view-id under slim too.
            Two different non-DOM-rooted views each emit their OWN
            one-shot warning (not a single global gate)."
    (rf/reg-view* :rf.slim-warn-once-test/fragment-id-a
                  (fn [] [:<> [:p "a"]]))
    (rf/reg-view* :rf.slim-warn-once-test/fragment-id-b
                  (fn [] [:<> [:p "b"]]))
    (let [render-a (rf/view :rf.slim-warn-once-test/fragment-id-a)
          render-b (rf/view :rf.slim-warn-once-test/fragment-id-b)
          warnings (with-captured-console-warn
                     (fn [] (render-a) (render-b) (render-a) (render-b)))]
      (is (= [2 true true]
             [(count warnings)
              (boolean (some #(str/includes? % "fragment-id-a") warnings))
              (boolean (some #(str/includes? % "fragment-id-b") warnings))])
          (str "one warning per id across 4 renders; got " (pr-str warnings))))))
