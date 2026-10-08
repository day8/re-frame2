(ns re-frame.trace-listener-nested-emission-order-cljs-test
  "Per-listener event order survives NESTED emission (Spec 009 §The listener
  contract point 4): when listener L0 emits B while the outer event A is still
  being fanned out, no listener may observe B before A — and the nested `emit!`
  returns only after B reached every listener (Spec 009 §Emitting trace events).

  A plain fan-out loop breaks the first law: B would reach the still-unvisited
  L1 before A did, so a tool folding `:rf.flow/cleared` then a nested
  `:rf.flow/registered` would end with a live flow marked cleared. The shared
  `re-frame.trace.tooling/*fanout-ctx*` schedule advances A to the remaining
  listeners first, then delivers B, before the nested `emit!` returns.

  The listeners live in a small array-map, so the first registered fans out
  first on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]
            [re-frame.trace                :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private outer :trace.order/outer)
(def ^:private inner :trace.order/inner)

(defn- ops-only
  "Just this test's two operations, in the order the listener saw them."
  [evs]
  (filterv #{outer inner} (mapv :operation evs)))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug nested-emit-completes-synchronously
  ;; L0 (emitter, first) emits `inner` while handling `outer`, then checks — the
  ;; instant the nested `emit!` returns — whether L1 (observer) already has it.
  (let [seen-observer       (atom [])
        seen-emitter        (atom [])
        observer-caught-up? (atom :not-recorded)]
    (rf.trace.tooling/register-listener! ::emitter
      (fn [ev]
        (swap! seen-emitter conj ev)
        (when (= outer (:operation ev))
          (rf.trace/emit! :info inner {})
          (reset! observer-caught-up?
                  (boolean (some #{inner} (ops-only @seen-observer)))))))
    (rf.trace.tooling/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (rf.trace/emit! :info outer {})
      (is (true? @observer-caught-up?)
          (str "the observer had NOT received inner when the nested emit "
               "returned. Recorded: " (pr-str @observer-caught-up?)))
      (is (= [outer inner] (ops-only @seen-observer))
          "the observer sees outer before the reentrantly-emitted inner")
      (is (= [outer inner] (ops-only @seen-emitter))
          "the emitter also receives its own nested inner, after outer")
      (finally
        (rf.trace.tooling/unregister-listener! ::emitter)
        (rf.trace.tooling/unregister-listener! ::observer)))))

(deftest ^:requires-debug nested-exception-isolation-preserves-order
  ;; A listener that throws between the emitter and the observer neither stops
  ;; delivery nor reorders it.
  (let [seen-observer (atom [])
        threw?        (atom 0)]
    (rf.trace.tooling/register-listener! ::emitter
      (fn [ev]
        (when (= outer (:operation ev))
          (rf.trace/emit! :info inner {}))))
    (rf.trace.tooling/register-listener! ::thrower
      (fn [_ev]
        (swap! threw? inc)
        (throw (ex-info "listener boom" {}))))
    (rf.trace.tooling/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (rf.trace/emit! :info outer {})
      (is (= [outer inner] (ops-only @seen-observer))
          "a throwing intermediate listener does not reorder or drop the observer's stream")
      (is (pos? @threw?) "the throwing listener was actually invoked (isolation, not skip)")
      (finally
        (rf.trace.tooling/unregister-listener! ::emitter)
        (rf.trace.tooling/unregister-listener! ::thrower)
        (rf.trace.tooling/unregister-listener! ::observer)))))
