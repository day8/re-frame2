(ns re-frame.freehand.pilot-virtual-table-scroll-seam-jvm-test
  "The JVM half of the virtual table's ONE host-shaped read.

  The pilot's `scroll-offset` is split per host for the reason the
  substrate's own `default-payload` is — `native-payload` in
  ClojureScript, `payload-map` on the JVM — because the structural host
  fires no native event and a callback's payload IS the map a test
  supplies. So the two halves are proven where each one lives: the live
  DOM event in `pilot-virtual-table-dom-cljs-test`, and the payload map
  here.

  JVM-only because that is the arm under test. A ClojureScript run would
  be asking the DOM reader to interpret a Clojure map, which is not a
  claim anyone wants to be true."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.pilot-virtual-table :as ui]))

(deftest the-scroll-offset-reads-off-the-structural-payload
  (testing "On the JVM the offset comes off the payload map, and it is
            the number the window is computed from — which is what makes
            a scroll assertable without a viewport."
    (is (= 3200 (ui/scroll-offset {:scroll-top 3200}))
        "the offset the payload carries")
    (is (= 0 (ui/scroll-offset {}))
        "and an absent offset is the top rather than a nil inside an event
         vector")
    (is (= 96 (:start (ui/window {:total 10000 :row-h 32 :viewport-h 640
                                  :scroll-top (ui/scroll-offset
                                                {:scroll-top 3200})})))
        "and the window it selects is the window the mounted table shows")))
