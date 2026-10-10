;;;; tests/runtime/dry_run_sink_pin_test.clj — `dispatch-dry-run` runs the
;;;; cascade under the framework effect SINK (`re-frame.fx/*effect-sink*`), so
;;;; effects are recorded and never executed. The sink's behaviour is proven at
;;;; the core executor by
;;;; implementation/core/test/re_frame/dry_run_effect_sink_cljs_test.cljc;
;;;; this pins that the preload binds it, and that a caller `:fx-overrides`
;;;; is refused loudly rather than silently ignored.
;;;;
;;;; Run: bb tests/runtime/dry_run_sink_pin_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dry-run-sink-pin-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest dispatch-dry-run-binds-the-effect-sink-and-refuses-overrides
  (let [form (rt/defn-named 'dispatch-dry-run)]
    (is (rt/mentions? 'rf.fx/*effect-sink* form)
        "dispatch-dry-run must bind re-frame.fx/*effect-sink* (spelled rf.fx/ under the preload's alias)")
    (is (rt/mentions? :fx-overrides-unsupported form)
        "dispatch-dry-run must refuse a caller :fx-overrides with :fx-overrides-unsupported")))

(let [{:keys [fail error]} (run-tests 'dry-run-sink-pin-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
