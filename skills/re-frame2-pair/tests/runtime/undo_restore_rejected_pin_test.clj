;;;; tests/runtime/undo_restore_rejected_pin_test.clj — when
;;;; `rf.epoch/restore-epoch!` refuses a restore, `undo-step-back` and
;;;; `undo-to-epoch` must return the framework's refusal with `:ok? false
;;;; :restored? false`. The skill trains the agent to read `:ok?` first and
;;;; these sugars are reached through raw `eval-cljs`, so an `:ok? true`
;;;; there would read as success over a frame that never moved. They need a
;;;; live frame, so bb pins the source shape of both.
;;;;
;;;; Run: bb tests/runtime/undo_restore_rejected_pin_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns undo-restore-rejected-pin-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.walk :as walk]
            [runtime-support :as rt]))

(defn- rejected-arm
  "The rejected arm of the `(if (:ok? r) <success> <rejected>)` node that
   branches on `rf.epoch/restore-epoch!`'s envelope."
  [form]
  (let [found (atom nil)]
    (walk/postwalk (fn [node]
                     (when (and (seq? node) (= 'if (first node)) (= '(:ok? r) (second node)))
                       (reset! found node))
                     node)
                   form)
    (nth @found 3 nil)))

(defn- refused-restore-defaults
  "The default map `refused-restore` merges the framework's refusal over."
  []
  (let [found (atom nil)]
    (walk/postwalk (fn [node]
                     (when (and (seq? node) (= 'merge (first node)) (map? (second node)))
                       (reset! found (second node)))
                     node)
                   (rt/defn-named 'refused-restore))
    @found))

(deftest rejected-restore-arm-passes-the-refusal-through
  (doseq [sym '[undo-step-back undo-to-epoch]]
    (is (= '(refused-restore r epoch-id frame-id) (rejected-arm (rt/defn-named sym)))
        (str sym "'s rejected-restore arm must return the framework's refusal via refused-restore")))
  (is (= [false false] ((juxt :ok? :restored?) (refused-restore-defaults)))
      "refused-restore must read :ok? false :restored? false whatever the refusal carries"))

(let [{:keys [fail error]} (run-tests 'undo-restore-rejected-pin-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
