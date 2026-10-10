;;;; tests/runtime/undo_restore_rejected_pin_test.clj — when
;;;; `rf/restore-epoch!` rejects a restore, `undo-step-back` and
;;;; `undo-to-epoch` must return `:ok? false :reason :restore-rejected`. The
;;;; skill trains the agent to read `:ok?` first and these sugars are reached
;;;; through raw `eval-cljs`, so an `:ok? true` there would read as success
;;;; over a frame that never moved. They need a live frame, so bb pins the
;;;; source shape of both.
;;;;
;;;; Run: bb tests/runtime/undo_restore_rejected_pin_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns undo-restore-rejected-pin-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.walk :as walk]
            [runtime-support :as rt]))

(defn- rejected-arm
  "The rejected arm of the `(if ok? <success> <rejected>)` node that branches
   on `rf/restore-epoch!`'s outcome."
  [form]
  (let [found (atom nil)]
    (walk/postwalk (fn [node]
                     (when (and (seq? node) (= 'if (first node)) (= 'ok? (second node)))
                       (reset! found node))
                     node)
                   form)
    (nth @found 3 nil)))

(deftest rejected-restore-arm-is-ok-false-restore-rejected
  (doseq [sym '[undo-step-back undo-to-epoch]]
    (is (= [false :restore-rejected] ((juxt :ok? :reason) (rejected-arm (rt/defn-named sym))))
        (str sym "'s rejected-restore arm must return :ok? false :reason :restore-rejected"))))

(let [{:keys [fail error]} (run-tests 'undo-restore-rejected-pin-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
