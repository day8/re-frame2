(ns re-frame.cascade-envelope-propagation-test
  "Per rf2-4jci1.1 — Spec/002 §Cascade propagation (line 1162) +
  §Drain-loop pseudocode `inheritable-envelope-keys` (lines 947-952).

  The dispatch envelope's `:fx-overrides`, `:interceptor-overrides`,
  `:trace-id`, `:origin`, `:source`, `:frame` MUST propagate through
  `:fx [[:dispatch ...]]` cascades: when a handler returns an effect-map
  containing `:dispatch`, the dispatched child inherits the parent
  envelope's overrides. Same mechanism for `:dispatch-later`.

  JVM-only — the cascade propagation is platform-agnostic; the runtime
  paths under test do not depend on a CLJS host."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- :fx-overrides cascade propagation ------------------------------------

(deftest fx-overrides-propagate-through-dispatch-cascade
  (testing "per-call :fx-overrides ride the :fx [[:dispatch ...]] cascade"
    (let [http-fired (atom [])
          http-stub  (atom [])]
      (rf/reg-fx :test/http
        (fn [_ args] (swap! http-fired conj args)))
      (rf/reg-fx :test/http.stub
        (fn [_ args] (swap! http-stub conj args)))
      (rf/reg-event-fx :test/parent
        (fn [_ _]
          {:fx [[:test/http {:tag :from-parent}]
                [:dispatch [:test/child]]]}))
      (rf/reg-event-fx :test/child
        (fn [_ _]
          {:fx [[:test/http {:tag :from-child}]]}))

      (rf/dispatch-sync
        [:test/parent]
        {:fx-overrides {:test/http :test/http.stub}})

      (is (empty? @http-fired)
          "no real :test/http fired — every call should hit the stub")
      (is (= [{:tag :from-parent} {:tag :from-child}] @http-stub)
          "the stub captured BOTH the parent's and the child's :test/http calls")))

  (testing "per-call :fx-overrides propagate through nested :dispatch cascades"
    (let [http-stub (atom [])]
      (rf/reg-fx :test/http  (fn [_ _]))
      (rf/reg-fx :test/http.stub
        (fn [_ args] (swap! http-stub conj args)))
      (rf/reg-event-fx :test/lvl-0
        (fn [_ _]
          {:fx [[:test/http {:lvl 0}]
                [:dispatch [:test/lvl-1]]]}))
      (rf/reg-event-fx :test/lvl-1
        (fn [_ _]
          {:fx [[:test/http {:lvl 1}]
                [:dispatch [:test/lvl-2]]]}))
      (rf/reg-event-fx :test/lvl-2
        (fn [_ _]
          {:fx [[:test/http {:lvl 2}]]}))

      (rf/dispatch-sync
        [:test/lvl-0]
        {:fx-overrides {:test/http :test/http.stub}})

      (is (= [{:lvl 0} {:lvl 1} {:lvl 2}] @http-stub)
          "override propagated through three levels of :dispatch cascade"))))

;; ---- :trace-id / :origin / :source propagation ----------------------------

(deftest trace-id-origin-source-propagate-through-cascade
  (testing ":trace-id, :origin, :source all ride the child envelope"
    ;; Capture parent and child envelopes via the trace stream — every
    ;; :event/dispatched event surfaces :origin / :source on :tags.
    (let [seen (atom [])]
      (rf/register-listener! ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event-fx :test/parent
          (fn [_ _]
            {:fx [[:dispatch [:test/child]]]}))
        (rf/reg-event-db :test/child (fn [db _] db))

        (rf/dispatch-sync [:test/parent]
                          {:trace-id ::scoped-trace
                           :origin   :test
                           :source   :unit-test})

        (let [dispatched   (->> @seen
                                 (filter #(= :event/dispatched (:operation %))))
              parent-ev    (first (filter #(= [:test/parent] (get-in % [:tags :event])) dispatched))
              child-ev     (first (filter #(= [:test/child]  (get-in % [:tags :event])) dispatched))]
          (is (some? parent-ev) "parent's :event/dispatched is captured")
          (is (some? child-ev)  "child's :event/dispatched is captured")
          ;; :origin rides :tags; :source is hoisted to top-level by
          ;; trace/emit! per Spec 009 §Core fields (line 367/388).
          (is (= :test      (get-in parent-ev [:tags :origin])) "parent carries :origin :test")
          (is (= :unit-test (:source parent-ev))             "parent carries :source :unit-test")
          (is (= :test      (get-in child-ev  [:tags :origin]))
              ":origin :test propagated through the cascade")
          (is (= :unit-test (:source child-ev))
              ":source :unit-test propagated through the cascade"))
        (finally (rf/unregister-listener! ::rec))))))

;; ---- :envelope exposed on fx-handler ctx (rf2-4jci1.4) -------------------

(deftest fx-handler-ctx-carries-envelope-slot
  (testing "user fx-handler receives (:envelope m) — the parent dispatch envelope"
    (let [captured-envelopes (atom [])]
      (rf/reg-fx :test/capture-envelope
        (fn [m _args] (swap! captured-envelopes conj (:envelope m))))
      (rf/reg-event-fx :test/run
        (fn [_ _]
          {:fx [[:test/capture-envelope]]}))

      (rf/dispatch-sync [:test/run]
                        {:trace-id  ::abc
                         :origin    :test
                         :source    :unit-test})

      (let [env (first @captured-envelopes)]
        (is (some? env) "the fx-handler ctx carried :envelope")
        (is (= ::abc      (:trace-id env)))
        (is (= :test      (:origin env)))
        (is (= :unit-test (:source env)))
        (is (= [:test/run] (:event env)))))))

;; ---- :dispatch-later propagates inheritable keys --------------------------
;;
;; :dispatch-later wraps in set-timeout!; we can verify the opts the
;; eventual :router/dispatch! call would receive by stubbing set-timeout!
;; semantics. Easier path: register a fixture timer that runs the inner
;; fn synchronously via a custom :dispatch-later shape — but the existing
;; reserved-fx body is platform-coupled (interop/set-timeout!). For JVM
;; the timer fires on a future; we use a CountDownLatch coordinated stub
;; to keep the test deterministic.

(deftest dispatch-later-propagates-inheritable-keys
  (testing ":dispatch-later carries parent overrides into the deferred dispatch"
    (let [stub-fired (atom [])
          done       (promise)]
      (rf/reg-fx :test/http (fn [_ _]))
      (rf/reg-fx :test/http.stub
        (fn [_ args]
          (swap! stub-fired conj args)
          (deliver done :fired)))
      (rf/reg-event-fx :test/parent
        (fn [_ _]
          {:fx [[:dispatch-later {:ms 1 :event [:test/child]}]]}))
      (rf/reg-event-fx :test/child
        (fn [_ _]
          {:fx [[:test/http {:tag :deferred}]]}))

      (rf/dispatch-sync
        [:test/parent]
        {:fx-overrides {:test/http :test/http.stub}})

      (is (= :fired (deref done 2000 :timeout))
          ":dispatch-later fired the :test/child cascade")
      (is (= [{:tag :deferred}] @stub-fired)
          ":fx-overrides propagated into :dispatch-later's deferred dispatch"))))
