(ns re-frame.ui.reactive-falsy-escape-cljs-test
  "rf2-vxgfnd.172 — flush listener containment must preserve a FALSY escape.

  `notify-listeners!` contains each listener so one throwing consumer cannot
  starve its siblings, then rethrows the FIRST escape after every listener has
  delivered (rf2-owwbyl). JavaScript permits throwing falsy values, so the
  presence of an escape MUST be tracked independently of the escape's own
  truthiness:

    - a listener that `throw null`s is a real escape; the pre-fix `(or acc e)` +
      `some?` presence test left the accumulator nil and silently lost it;
    - a first listener that `throw false`s, followed by a truthy Error, had its
      falsy first escape overwritten by the later truthy one — violating the
      first-escape order/identity contract.

  These are CLJS-only vectors (`throw null` / `throw false`); on the JVM a
  Throwable is always truthy, so JVM behaviour is unchanged and this fixture is
  `.cljs`. Existing fixtures throw only truthy `ex-info`, so they cannot detect
  truthiness-based loss."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- flush-capturing
  "Drain the whole registry through the real `flush-pending!` primitive. Returns
  `[:threw v]` carrying the EXACT rethrown escape value (which may be nil/false/
  Error), or `:clean` when the flush completed without a rethrow."
  []
  (try (reactive/flush-pending!) :clean
       (catch :default e [:threw e])))

;; ===========================================================================
;; A `throw null` is a real escape: it must be rethrown AFTER every same-cell
;; and sibling-cell listener delivered — never silently swallowed.
;; ===========================================================================

(deftest throw-null-is-rethrown-after-all-listeners-deliver
  (reactive/reset-scheduler!)
  (let [same    (atom 0)
        sibling (atom 0)
        cell-a  (reactive/make-cell ::a)
        cell-b  (reactive/make-cell ::b)]
    ;; cell-a: a listener that throws JavaScript null, then a same-cell sibling.
    (reactive/subscribe cell-a (fn [] (throw nil)))
    (reactive/subscribe cell-a (fn [] (swap! same inc)))
    ;; cell-b: a sibling-cell listener in the same batch.
    (reactive/subscribe cell-b (fn [] (swap! sibling inc)))
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (let [outcome (flush-capturing)]
      (testing "containment held — every same-cell AND sibling-cell listener delivered"
        (is (= 1 @same)    "cell-a's second (same-cell) listener fired")
        (is (= 1 @sibling) "cell-b's sibling-cell listener fired"))
      (testing "the falsy escape is PRESERVED — `throw null` is rethrown exactly"
        (is (= [:threw nil] outcome)
            "pre-fix `(or acc nil)` + `some?` left the accumulator nil and lost it"))
      (testing "no cell is left dirty/pending solely because a listener threw"
        (is (= 0 (reactive/pending-cell-count)))))))

;; ===========================================================================
;; A first `throw false`, then a later truthy Error: the FIRST (false) wins.
;; ===========================================================================

(deftest first-false-escape-is-preserved-over-a-later-error
  (reactive/reset-scheduler!)
  (let [cell (reactive/make-cell ::c)]
    ;; first listener throws JavaScript false; a later listener throws a truthy Error.
    (reactive/subscribe cell (fn [] (throw false)))
    (reactive/subscribe cell (fn [] (throw (ex-info "later" {:n 2}))))
    (reactive/mark-dirty! cell 1)
    (let [outcome (flush-capturing)]
      (testing "the FIRST escape (false) is rethrown — not the later truthy Error"
        (is (= [:threw false] outcome)
            "pre-fix `(or acc e)` overwrote the falsy first escape with the later
             truthy one, violating first-escape order/identity")))))

;; ===========================================================================
;; Truthy Error behaviour is UNCHANGED, and the scheduler drains clean after a
;; throw — no cell stranded dirty/pending.
;; ===========================================================================

(deftest truthy-error-rethrown-and-scheduler-drains-clean-afterward
  (reactive/reset-scheduler!)
  (let [sibling (atom 0)
        boom    (ex-info "boom" {})
        cell-a  (reactive/make-cell ::a)
        cell-b  (reactive/make-cell ::b)]
    (reactive/subscribe cell-a (fn [] (throw boom)))
    (reactive/subscribe cell-b (fn [] (swap! sibling inc)))
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (let [outcome (flush-capturing)]
      (is (= [:threw boom] outcome) "the exact truthy escape is rethrown by identity")
      (is (= 1 @sibling) "the sibling cell still delivered before the rethrow"))
    (testing "nothing lingers dirty solely because a listener threw"
      (is (= 0 (reactive/pending-cell-count))))
    (testing "a clean follow-up drain succeeds"
      (reactive/mark-dirty! cell-b 3)
      (is (= :clean (flush-capturing)) "the next drain flushes cleanly")
      (is (= 2 @sibling)))))
