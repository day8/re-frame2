(ns day8.re-frame2-causa.panels.common-helpers)

(defn now-ms
  "Return host-clock time in ms. Pure-ish — abstracted so test
  fixtures can stub via `with-redefs`. Cross-platform via
  `#?(:clj ... :cljs ...)`."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))
