(ns resources-research.runner
  (:require [cljs.test :as t]
            [re-frame.realworld-resources-cljs-test]
            [re-frame.realworld-resources-observability-cljs-test]
            [re-frame.example-realworld-resources-boot-seed-cljs-test]
            [day8.re-frame2-xray.panels.resources-helpers-cljs-test]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (println "RESEARCH-SUMMARY" (pr-str m))
  (set! (.-exitCode js/process) (if (t/successful? m) 0 1)))

(defn main []
  (println "gate root: <HOME>/code/re-frame2")
  (t/run-tests 're-frame.realworld-resources-cljs-test
               're-frame.realworld-resources-observability-cljs-test
               're-frame.example-realworld-resources-boot-seed-cljs-test
               'day8.re-frame2-xray.panels.resources-helpers-cljs-test))
