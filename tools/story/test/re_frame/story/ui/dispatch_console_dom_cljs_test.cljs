(ns re-frame.story.ui.dispatch-console-dom-cljs-test
  "Browser-lane half of the Dispatch Console's history persistence: real
  `.setItem` / `.getItem` round-trips through `save-history!` /
  `load-history!`, read back after the in-memory ratom is dropped to
  simulate a reload. The pure and node-lane rows are
  `re-frame.story.ui.dispatch-console-cljs-test`.

  `:browser-test` loads only `-dom-cljs-test` namespaces, and node has no
  `window.localStorage`, so a `(when (browser?) ...)` row in the sibling
  would run in neither lane. This file is ALSO selected by `:node-test`
  (its `cljs-test$` suffix match), so each row answers the node lane with a
  visible skip marker rather than holding zero assertions.

  The fixture clears only this suite's keys, through `clear-history!`: the
  browser lane runs every namespace on one page, so a blanket
  `localStorage.clear` would destroy another suite's slot mid-run."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.story.ui.dispatch-console :as rf.story.ui.dispatch-console]))

;; ---- host predicate ------------------------------------------------------

(defn- browser?
  "True when a working `js/window.localStorage` is present. FALSE under
  `:node-test`, TRUE under `:browser-test`; both targets load this ns."
  []
  (and (exists? js/window) (.-localStorage js/window)))

(def ^:private skip-msg
  "skipped: no localStorage (node lane — see ns docstring)")

(def ^:private variants
  [:story.hyd/v :story.clear/v])

(defn- reset-all! []
  (reset! rf.story.ui.dispatch-console/history-state {})
  (when (browser?)
    (doseq [vid variants]
      (try (rf.story.ui.dispatch-console/clear-history! vid)
           (catch :default _ nil))))
  (reset! rf.story.ui.dispatch-console/history-state {}))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- save → load across a simulated reload ------------------------------

(deftest current-history-hydrates-once
  (if-not (browser?)
    (is true skip-msg)
    (let [vid   :story.hyd/v
          entry (rf.story.ui.dispatch-console/build-history-entry
                  :ev/x nil :dispatch 17)]
      (rf.story.ui.dispatch-console/save-history! vid [entry])
      (reset! rf.story.ui.dispatch-console/history-state {})
      (is (= [entry] (rf.story.ui.dispatch-console/current-history vid))))))

(deftest clear-history-drops-storage
  (if-not (browser?)
    (is true skip-msg)
    (let [vid   :story.clear/v
          entry (rf.story.ui.dispatch-console/build-history-entry
                  :ev/x nil :dispatch 1)]
      (rf.story.ui.dispatch-console/append-history! vid entry)
      (reset! rf.story.ui.dispatch-console/history-state {})
      ;; Without this, the empty read below would pass against a storage
      ;; that never held anything.
      (is (= 1 (count (rf.story.ui.dispatch-console/load-history! vid)))
          "precondition: append-history! really did persist the entry")
      (rf.story.ui.dispatch-console/clear-history! vid)
      (reset! rf.story.ui.dispatch-console/history-state {})
      (is (= [] (rf.story.ui.dispatch-console/load-history! vid))
          "clear-history! removed the persisted slot too"))))
