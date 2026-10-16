(ns re-frame.trace-listener-nested-emission-order-cljs-test
  "Trace listeners are delivered from one FIFO queue owned by the outermost
  deliverer (Spec 009 §Listener invocation rules). When listener L0 emits B
  while the outer event A is being fanned out, B is queued: the nested `emit!`
  returns at once, no listener callback is re-entered, every listener sees A
  before B, and B reaches every listener before the outermost `emit!` returns.

  A plain recursive fan-out breaks the order: B would reach the still-unvisited
  L1 before A did, so a tool folding `:rf.flow/cleared` then a nested
  `:rf.flow/registered` would end with a live flow marked cleared. Driving B to
  every listener inside the nested `emit!` keeps the order but re-enters L0,
  whose callback for A is still on the stack.

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
;; end (see the `:prod-gate` alias in deps.edn).

(deftest ^:requires-debug nested-emit-is-queued-behind-the-outer-event
  ;; L0 (emitter, first) emits `inner` while handling `outer`, then records — the
  ;; instant the nested `emit!` returns — whether L1 (observer) already has it.
  (let [seen-observer       (atom [])
        seen-emitter        (atom [])
        emitter-depth       (atom 0)
        reentered?          (atom false)
        observer-caught-up? (atom :not-recorded)]
    (rf.trace.tooling/register-listener! ::emitter
      (fn [ev]
        (when (pos? @emitter-depth)
          (reset! reentered? true))
        (swap! emitter-depth inc)
        (try
          (swap! seen-emitter conj ev)
          (when (= outer (:operation ev))
            (rf.trace/emit! :info inner {})
            (reset! observer-caught-up?
                    (boolean (some #{inner} (ops-only @seen-observer)))))
          (finally
            (swap! emitter-depth dec)))))
    (rf.trace.tooling/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (rf.trace/emit! :info outer {})
      (is (false? @reentered?)
          (str "the emitter's callback was re-entered: its callback for inner "
               "started while its callback for outer was still on the stack"))
      (is (false? @observer-caught-up?)
          (str "the nested emit delivered inner before returning instead of "
               "queueing it. Observer already held inner: "
               (pr-str @observer-caught-up?)))
      (is (= [outer inner] (ops-only @seen-observer))
          "the observer sees outer, then inner, before the outermost emit returns")
      (is (= [outer inner] (ops-only @seen-emitter))
          "the emitter receives its own nested inner after outer, before the outermost emit returns")
      (finally
        (rf.trace.tooling/unregister-listener! ::emitter)
        (rf.trace.tooling/unregister-listener! ::observer)))))
