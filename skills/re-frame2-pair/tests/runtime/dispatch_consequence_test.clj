;;;; tests/runtime/dispatch_consequence_test.clj — `dispatch-consequence!`, the
;;;; MCP `dispatch` tool's default path, echoes the parsed event under
;;;; `:resolved` (the tool descriptor promises it). Its validation against the
;;;; frame's registrations is exercised for real by the fixture's
;;;; runtime_frame_image_test.cljs; its delegations by pure_delegation_test.clj.
;;;;
;;;; Run: bb tests/runtime/dispatch_consequence_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dispatch-consequence-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest consequence-echoes-resolved-event
  (is (rt/mentions? :resolved (rt/defn-named 'dispatch-consequence!))
      "dispatch-consequence! must echo the parsed event under :resolved"))

(let [{:keys [fail error]} (run-tests 'dispatch-consequence-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
