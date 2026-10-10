;;;; tests/runtime/registrar_describe_test.clj — `registrar-describe` must
;;;; hand the MCP wire readable EDN. Handler meta carries raw fns (top-level
;;;; `:handler-fn`, and nested ones under the resources kinds' `:rf/resource` /
;;;; `:rf/mutation` / `:rf/resource-scope` specs); `pr-str` of a fn is
;;;; unreadable, so the whole response would surface as `:unserializable`.
;;;; `strip-fns` is what prevents that (its walk is RUN in
;;;; machine_describe_test.clj), and `:handler-fn-hash` is the wire-friendly
;;;; identity hot-reload probing and tail-build read instead.
;;;;
;;;; Run: bb tests/runtime/registrar_describe_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns registrar-describe-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest registrar-describe-strips-fns-and-carries-the-hash
  (let [form (rt/defn-named 'registrar-describe)]
    (is (rt/mentions? 'strip-fns form) "registrar-describe must run strip-fns over the meta map")
    (is (rt/mentions? :handler-fn-hash form) "registrar-describe must emit :handler-fn-hash")))

(let [{:keys [fail error]} (run-tests 'registrar-describe-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
