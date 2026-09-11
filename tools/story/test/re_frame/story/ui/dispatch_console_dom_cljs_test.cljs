(ns re-frame.story.ui.dispatch-console-dom-cljs-test
  "Browser-lane half of the Dispatch Console's history persistence
  (rf2-q9kv5), promoted out of `re-frame.story.ui.dispatch-console-cljs-test`
  under rf2-r51p.

  ## What was wrong, and it was the file's LOCATION rather than its guard

  These rows sat inside `(when (browser?) ...)` in a namespace ending
  `-cljs-test`. `:node-test` selected that namespace and the guard was
  false there — this repo ships no jsdom, no happy-dom and no DOM shim in
  any dependency list, so `window.localStorage` is simply absent under
  Node — while `:browser-test`, whose `:ns-regexp` is
  `.*-dom-cljs-test$`, never loaded the file at all. They executed in
  NEITHER lane.

  Every row here is a real `.setItem` / `.getItem` round-trip through
  `save-history!` / `load-history!`, read back after the in-memory ratom
  is dropped to simulate a reload. That is real host-storage semantics,
  which rf2-r51p rules needs a real host rather than a stub.

  ## THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `cljs-test$` is a bare SUFFIX match that
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, and
  `implementation/shadow-cljs.edn` records that overlap as deliberate. So
  moving a row here ADDS the browser lane; it removes nothing. Each row
  answers the node lane with a VISIBLE marker assertion rather than a
  silent `when`, so no deftest here holds zero assertions.

  ## The fixture clears ONLY this suite's keys

  The sibling's `reset-all!` calls `.clear` on the whole of
  `localStorage`. That is harmless on a node lane that has none, but the
  BROWSER lane runs every namespace on one page, so a blanket wipe would
  destroy another suite's slot mid-run. This fixture removes only the
  variants below, through the panel's own `clear-history!`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
  [:story.persist/v :story.hyd/v :story.clear/v])

(defn- reset-all! []
  (reset! rf.story.ui.dispatch-console/history-state {})
  (when (browser?)
    (doseq [vid variants]
      (try (rf.story.ui.dispatch-console/clear-history! vid)
           (catch :default _ nil))))
  (reset! rf.story.ui.dispatch-console/history-state {}))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- save → load across a simulated reload ------------------------------

(deftest history-localstorage-roundtrip
  (testing "save-history! → load-history! survives a dropped ratom"
    (if-not (browser?)
      (is true skip-msg)
      (let [vid   :story.persist/v
            entry (rf.story.ui.dispatch-console/build-history-entry
                    :counter/inc nil :dispatch 1700000000000)]
        (rf.story.ui.dispatch-console/save-history! vid [entry])
        ;; Drop in-memory state to simulate a reload.
        (reset! rf.story.ui.dispatch-console/history-state {})
        (let [loaded (rf.story.ui.dispatch-console/load-history! vid)]
          (is (= 1 (count loaded)))
          (is (= :counter/inc (:event-id (first loaded)))))))))

(deftest current-history-hydrates-once
  (testing "current-history hydrates from localStorage on first access"
    (if-not (browser?)
      (is true skip-msg)
      (let [vid   :story.hyd/v
            entry (rf.story.ui.dispatch-console/build-history-entry
                    :ev/x nil :dispatch 17)]
        (rf.story.ui.dispatch-console/save-history! vid [entry])
        (reset! rf.story.ui.dispatch-console/history-state {})
        (let [h (rf.story.ui.dispatch-console/current-history vid)]
          (is (= 1 (count h)))
          (is (= :ev/x (:event-id (first h)))))))))

(deftest clear-history-drops-storage
  (testing "clear-history! wipes the PERSISTED slot, not just the ratom.

            The node half of this row (ratom emptied) stays in the
            sibling; only the storage claim lives here."
    (if-not (browser?)
      (is true skip-msg)
      (let [vid   :story.clear/v
            entry (rf.story.ui.dispatch-console/build-history-entry
                    :ev/x nil :dispatch 1)]
        (rf.story.ui.dispatch-console/append-history! vid entry)
        ;; Teeth: the old row asserted `(= [] (load-history! vid))` after
        ;; the clear, which passes just as happily against a storage that
        ;; never held anything. Prove the entry WAS persisted first, so
        ;; the empty read below is evidence that `clear-history!` removed
        ;; it rather than evidence that nothing ever landed.
        (reset! rf.story.ui.dispatch-console/history-state {})
        (is (= 1 (count (rf.story.ui.dispatch-console/load-history! vid)))
            "precondition: append-history! really did persist the entry")
        (rf.story.ui.dispatch-console/clear-history! vid)
        (reset! rf.story.ui.dispatch-console/history-state {})
        (is (= [] (rf.story.ui.dispatch-console/load-history! vid))
            "clear-history! removed the persisted slot too")))))
