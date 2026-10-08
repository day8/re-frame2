(ns reagent2.impl.template-keyword-prop-warn-once-clear-cljs-test
  "The slim template's keyword-prop warn-once cache (`warned-keyword-prop`)
  is cleared by the chained `:adapter/clear-warn-once-caches!` hook, which
  the slim adapter registers at ns-load through
  `spine/install-clear-warn-once-step!` — so a test that already warned for
  a pair cannot swallow a later test's same-pair warning. Mirrors
  `re-frame.adapter.react-shared-suite/assert-chained-clear-warn-once-empties-cache`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ;; Loaded for its clear-step registration.
            [re-frame.adapter.reagent-slim]
            [re-frame.late-bind :as rf.late-bind]
            [reagent2.impl.template :as template]))

(defn- with-warn-spy
  "Run `f` with js/console.warn recording its joined args; return them."
  [f]
  (let [calls (atom [])
        orig  (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (f)
      @calls
      (finally
        (set! (.-warn js/console) orig)))))

(deftest chained-clear-warn-once-re-arms-keyword-prop-cache
  (testing "the chained clear hook re-arms the cache, so the SAME pair warns
            again"
    (let [k :rf2-rearm-test-k
          v :rf2-rearm-test-v
          phase-1 (with-warn-spy
                    (fn []
                      (template/convert-prop-value k v)
                      (template/convert-prop-value k v)
                      (template/convert-prop-value k v)))]
      (is (= 1 (count phase-1))
          (str "warn fires once within a phase; got " (pr-str phase-1)))
      ((rf.late-bind/get-fn :adapter/clear-warn-once-caches!))
      (let [phase-2 (with-warn-spy (fn [] (template/convert-prop-value k v)))]
        (is (= 1 (count phase-2))
            (str "the same pair warns again after the clear; got " (pr-str phase-2)))))))
