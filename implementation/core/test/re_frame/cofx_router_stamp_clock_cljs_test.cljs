(ns re-frame.cofx-router-stamp-clock-cljs-test
  "On CLJS the two interop clocks are different classes: `epoch-now-ms` reads
  wall-clock epoch ms (`js/Date.now`, about 1.78e12) and `now-ms` reads
  origin-relative elapsed ms (`performance.now`). The router stamps the durable
  `:rf/time-ms` from `epoch-now-ms`, and
  `re-frame.epoch-committed-at-clock-cljs-test` catches a stamp read from the
  wrong clock with a 1e12 floor. That floor discriminates only while the two
  clocks differ, which this checks; the JVM cannot see the difference because
  both of its clocks read wall time."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.interop :as rf.interop]))

(def ^:private wall-clock-floor 1e12)

(deftest clock-class-discriminator-sanity-cljs
  (is (> (rf.interop/epoch-now-ms) wall-clock-floor))
  (is (< (rf.interop/now-ms) wall-clock-floor)))
