(ns day8.re-frame2-causa.panels.common-helpers)

(defn now-ms
  "Return host-clock time in ms. Pure-ish — abstracted so test
  fixtures can stub via `with-redefs`. Cross-platform via
  `#?(:clj ... :cljs ...)`."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn tag-of
  "Pull a tag value off a trace event. Trace events nest per-event
  metadata under `:tags`; the helper reads the slot defensively so
  test fixtures that supply a flat shape (no `:tags`) also work.
  Canonical tag-reader shared across every panel-helper that walks
  the Causa trace buffer."
  [ev k]
  (or (get-in ev [:tags k])
      (get ev k)))
