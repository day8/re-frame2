;;;; tests/runtime/post_mortem_transition_test.clj
;;;;
;;;; rf2-fzbj.15 F2 — the post-mortem recipe must attribute the bad state
;;;; to the epoch that CAUSED it, not to the newest epoch that happens to
;;;; carry it.
;;;;
;;;; Why this test exists:
;;;;
;;;; `find-where` returns the NEWEST matching record — the right general
;;;; contract, and it does not change. The published post-mortem recipe
;;;; paired it with a predicate testing `:db-after` ALONE. Every epoch
;;;; after the fault still carries the bad value (nothing repaired it), so
;;;; the newest match is whatever dispatched most recently — on an active
;;;; UI, an unrelated event. Step 4 then tells the agent to report that
;;;; record as the culprit, so an apparently evidence-based forensic
;;;; answer blames the wrong handler, and the suggested edits land in
;;;; unrelated source.
;;;;
;;;; This is an EXECUTABLE pin, not a prose pin: it lifts the predicate
;;;; out of recipes.md and RUNS it over a contrasting epoch sequence. A
;;;; prose assertion ("mentions :db-before") would pass on a recipe that
;;;; named the key while still selecting the wrong record.
;;;;
;;;; Run: bb tests/runtime/post_mortem_transition_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns post-mortem-transition-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [runtime-support :as rt]))

(def ^:private skill-root
  (-> (java.io.File. *file*) .getAbsoluteFile .getParentFile .getParentFile .getParentFile))

(def ^:private recipes-md
  (delay (slurp (io/file skill-root "references" "recipes.md"))))

;; ---------------------------------------------------------------------------
;; The synthetic history: seed -> fault -> two unrelated events.
;;
;; The shape that breaks a state-only predicate is ordinary, not exotic:
;; something goes wrong, and the app keeps running.
;; ---------------------------------------------------------------------------

(def ^:private epochs
  [{:trigger-event [:review/seed]  :db-before {}                     :db-after {:auth-state :active}}
   {:trigger-event [:auth/expire]  :db-before {:auth-state :active}  :db-after {:auth-state :expired}}
   {:trigger-event [:ui/tick]      :db-before {:auth-state :expired} :db-after {:auth-state :expired :tick 1}}
   {:trigger-event [:ui/hover]     :db-before {:auth-state :expired :tick 1}
                                   :db-after  {:auth-state :expired :tick 1 :hover true}}])

(defn- find-where*
  "The shipped `find-where` semantics: newest match wins. Pinned against
   the real source below, so this mirror cannot drift silently."
  [pred records]
  (->> records reverse (filter pred) first))

(deftest find-where-is-still-newest-match-wins
  ;; The mirror above is only trustworthy while the shipped fn keeps this
  ;; shape — if `find-where` ever stopped reversing, the recipe's failure
  ;; mode would change and this test would be reasoning about the wrong
  ;; helper.
  (let [form (rt/defn-named 'find-where)]
    (is (some? form) "runtime.cljs must define `find-where`")
    (is (rt/form-contains? #(= 'reverse %) form)
        "find-where walks the history in reverse (newest first)")
    (is (rt/form-contains? #(= 'first %) form)
        "and returns the first match — so the NEWEST matching record wins")))

;; ---------------------------------------------------------------------------
;; The published predicate, lifted out of the recipe and executed.
;; ---------------------------------------------------------------------------

(defn- published-predicate
  "Read the `(fn [e] ...)` the post-mortem recipe publishes. `read-string`
   consumes exactly one balanced form, so slicing at its opening paren
   yields the predicate and nothing after it."
  []
  (let [text (str @recipes-md)
        ;; Anchor inside the post-mortem section's eval-cljs example.
        i    (str/index-of text "(re-frame2-pair.runtime/find-where")
        _    (assert i "recipes.md no longer contains a find-where post-mortem example")
        j    (str/index-of text "(fn [e]" i)
        _    (assert j "the post-mortem example no longer carries a (fn [e] ...) predicate")]
    (eval (read-string (subs text j)))))

(deftest post-mortem-recipe-selects-the-transition-not-the-latest-holder
  (let [pred (published-predicate)]
    (testing "the recipe's own predicate, run over seed -> fault -> unrelated -> unrelated"
      (is (= [:auth/expire] (:trigger-event (find-where* pred epochs)))
          (str "the published post-mortem predicate must select the epoch that CHANGED "
               ":auth-state to :expired. Selecting [:ui/tick] / [:ui/hover] means it is "
               "matching on :db-after alone, so it reports the newest epoch that merely "
               "CARRIES the bad value — the wrong-culprit report (rf2-fzbj.15 F2).")))

    (testing "a second genuine transition into the bad value selects the LATEST one"
      (let [repaired-then-broken
            (conj (vec epochs)
                  {:trigger-event [:auth/renew]  :db-before {:auth-state :expired}
                                                 :db-after  {:auth-state :active}}
                  {:trigger-event [:auth/expire-again] :db-before {:auth-state :active}
                                                       :db-after  {:auth-state :expired}}
                  {:trigger-event [:ui/tick]     :db-before {:auth-state :expired}
                                                 :db-after  {:auth-state :expired :tick 2}})]
        (is (= [:auth/expire-again] (:trigger-event (find-where* pred repaired-then-broken)))
            "newest TRANSITION, not newest holder and not the oldest transition")))

    (testing "records that all start bad yield no claimed origin"
      ;; The transition is older than the ring's horizon. `nil` is the
      ;; honest answer; the recipe tells the agent to say so rather than
      ;; fall back to the newest holder.
      (let [already-bad [{:trigger-event [:ui/tick]  :db-before {:auth-state :expired}
                                                     :db-after  {:auth-state :expired :tick 1}}
                         {:trigger-event [:ui/hover] :db-before {:auth-state :expired :tick 1}
                                                     :db-after  {:auth-state :expired :tick 1 :hover true}}]]
        (is (nil? (find-where* pred already-bad))
            "no retained epoch transitions INTO the bad value, so no culprit is claimed")))))

(deftest recipe-tells-the-agent-what-a-nil-answer-means
  ;; The executable pin above proves the predicate is right; this one
  ;; keeps the PROSE that reads its nil answer correctly, since that is
  ;; the step where an agent would otherwise improvise a wrong culprit.
  (let [text (str @recipes-md)]
    (is (str/includes? text ":db-before")
        "the post-mortem recipe must show the before/after transition evidence")
    (is (re-find #"(?i)nil answer is information|not retained|before that|already bad" text)
        "the recipe must tell the agent how to report a nil (no retained transition) answer")))

(let [{:keys [fail error]} (run-tests 'post-mortem-transition-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
