;;;; tests/runtime/machine_describe_test.clj — the preload's MACHINE DOOR,
;;;; `machine-describe` and `machines-list`, which the MCP `handler-meta` /
;;;; `list-handlers` tools call by name for the virtual `:machine` kind.
;;;;
;;;; A machine spec nests FN VALUES under `:guards` / `:actions` (Spec 005);
;;;; `pr-str` of a fn is unreadable EDN, which the MCP codec tags
;;;; `:unserializable`, hiding the whole spec. So the door runs `strip-fns`.
;;;; The second test reads `fn-slot-sentinel` and `strip-fns` out of the
;;;; preload source and RUNS them, so it exercises the shipped walk.
;;;;
;;;; Run: bb tests/runtime/machine_describe_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns machine-describe-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest machine-door-is-public-strips-fns-reports-misses-and-sorts
  (let [describe (rt/defn-named 'machine-describe)
        list-fn  (rt/defn-named 'machines-list)]
    ;; An eval form shipped over nREPL cannot reach a defn-.
    (is (= 'defn (first describe)) "machine-describe must be a PUBLIC defn")
    (is (= 'defn (first list-fn)) "machines-list must be a PUBLIC defn")
    (is (rt/mentions? 'strip-fns describe) "machine-describe must run strip-fns over the spec it returns")
    (is (rt/mentions? :not-a-machine describe)
        "a miss returns {:ok? false :reason :not-a-machine}, which handler-meta renames to :not-registered")
    (is (rt/mentions? 'sort list-fn)
        "machines-list must sort: list-handlers documents one stable sorted vector for every kind")))

(def ^:private strip-fns
  (let [sentinel (some #(when (and (seq? %) (= 'def (first %)) (= 'fn-slot-sentinel (second %))) %)
                       rt/all-forms)
        walker   (rt/defn-named 'strip-fns)]
    (when (and sentinel walker)
      (binding [*ns* (create-ns 'machine-describe-test.shipped)]
        (refer-clojure)
        (eval sentinel)
        (eval walker)
        @(ns-resolve 'machine-describe-test.shipped 'strip-fns)))))

(deftest shipped-strip-fns-replaces-guard-and-action-fns
  (is (= {:initial :idle
          :states  {:idle {:on {:go :running}} :running {}}
          :data    {:retries 0}
          :guards  {:can-go? :rf/fn}
          :actions {:log! :rf/fn}}
         (strip-fns {:initial :idle
                     :states  {:idle {:on {:go :running}} :running {}}
                     :data    {:retries 0}
                     :guards  {:can-go? (fn [_ _] true)}
                     :actions {:log! (fn [_ _] nil)}}))
      "every fn slot becomes :rf/fn and nothing else changes"))

(let [{:keys [fail error]} (run-tests 'machine-describe-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
