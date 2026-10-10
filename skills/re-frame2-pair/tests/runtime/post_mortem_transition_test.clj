;;;; tests/runtime/post_mortem_transition_test.clj — the post-mortem recipe in
;;;; references/recipes.md must attribute a bad state to the epoch that CAUSED
;;;; it. `find-where` returns the NEWEST match, and every epoch after the fault
;;;; still carries the bad value, so a predicate testing `:db-after` alone
;;;; blames whatever dispatched most recently. This lifts the recipe's own
;;;; predicate out of the leaf and RUNS it over a contrasting history.
;;;;
;;;; Run: bb tests/runtime/post_mortem_transition_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns post-mortem-transition-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [runtime-support :as rt]))

(def ^:private recipes-md
  (let [f (io/file (-> (java.io.File. *file*) .getAbsoluteFile .getParentFile .getParentFile .getParentFile)
                   "references" "recipes.md")]
    (delay (slurp f))))

;; seed -> fault -> two unrelated events: something goes wrong and the app keeps running.
(def ^:private epochs
  [{:trigger-event [:review/seed]  :db-before {}                     :db-after {:auth-state :active}}
   {:trigger-event [:auth/expire]  :db-before {:auth-state :active}  :db-after {:auth-state :expired}}
   {:trigger-event [:ui/tick]      :db-before {:auth-state :expired} :db-after {:auth-state :expired :tick 1}}
   {:trigger-event [:ui/hover]     :db-before {:auth-state :expired :tick 1}
                                   :db-after  {:auth-state :expired :tick 1 :hover true}}])

(defn- find-where*
  "The shipped `find-where`'s semantics, newest match wins; the test below
   pins the shipped fn to that shape."
  [pred records]
  (->> records reverse (filter pred) first))

(deftest find-where-is-still-newest-match-wins
  (let [form (rt/defn-named 'find-where)]
    (is (rt/mentions? 'reverse form) "find-where walks the history newest first")
    (is (rt/mentions? 'first form) "and returns the first match")))

(defn- published-predicate
  "The `(fn [e] …)` the recipe's find-where example publishes."
  []
  (let [text @recipes-md
        i    (str/index-of text "(re-frame2-pair.runtime/find-where")
        _    (assert i "recipes.md contains no find-where post-mortem example")
        j    (str/index-of text "(fn [e]" i)
        _    (assert j "the post-mortem example carries no (fn [e] ...) predicate")]
    (eval (read-string (subs text j)))))

(deftest post-mortem-recipe-selects-the-transition-not-the-latest-holder
  (let [pred (published-predicate)]
    (testing "seed -> fault -> unrelated -> unrelated selects the fault"
      (is (= [:auth/expire] (:trigger-event (find-where* pred epochs)))
          "selecting [:ui/tick] / [:ui/hover] means the predicate matches on :db-after alone"))
    (testing "a second genuine transition selects the LATEST transition"
      (is (= [:auth/expire-again]
             (:trigger-event
              (find-where* pred (conj epochs
                                      {:trigger-event [:auth/renew] :db-before {:auth-state :expired}
                                       :db-after {:auth-state :active}}
                                      {:trigger-event [:auth/expire-again] :db-before {:auth-state :active}
                                       :db-after {:auth-state :expired}}
                                      {:trigger-event [:ui/tick] :db-before {:auth-state :expired}
                                       :db-after {:auth-state :expired :tick 2}}))))))
    (testing "a history that starts bad claims no culprit"
      (is (nil? (find-where* pred (drop 2 epochs)))
          "no retained epoch transitions INTO the bad value"))
    (testing "the recipe tells the agent how to report that nil"
      (is (re-find #"(?i)nil answer is information|not retained|before that|already bad" @recipes-md)))))

(let [{:keys [fail error]} (run-tests 'post-mortem-transition-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
