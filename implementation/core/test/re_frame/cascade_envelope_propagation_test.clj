(ns re-frame.cascade-envelope-propagation-test
  "Per rf2-4jci1.1 — Spec/002 §Cascade propagation (line 1162) +
  §Drain-loop pseudocode `inheritable-envelope-keys` (lines 947-952).

  The dispatch envelope's `:fx-overrides`, `:interceptor-overrides`,
  `:trace-id`, `:origin`, `:frame` MUST propagate through
  `:fx [[:dispatch ...]]` cascades: when a handler returns an effect-map
  containing `:dispatch`, the dispatched child inherits the parent
  envelope's overrides. Same mechanism for `:dispatch-later`.

  `:source` is **excluded from inheritance** per rf2-ejtpd — each
  fx-emitted child stamps its own immediate-trigger value
  (`:fx-dispatch` / `:fx-dispatch-later`) so the trigger-kind axis
  reflects the substrate's actual dispatch site rather than the
  originating user event's trigger.

  JVM-only — the cascade propagation is platform-agnostic; the runtime
  paths under test do not depend on a CLJS host.

  EP-0002 (rf2-9wa0lf): under the carried-invariant frame contract the
  top-level `dispatch-sync` calls below must establish a frame scope — a
  bare dispatch under no scope now raises `:rf.error/no-frame-context`
  (there is no `:rf/default` floor). The fixture registers an ordinary
  `:rf/default` frame and pins `*current-frame*` to it (the fixture-level
  equivalent of `(with-frame :rf/default …)`); the CHILD dispatches
  (`:fx [[:dispatch …]]` / `:dispatch-later`) already carry the parent's
  `:frame` explicitly via `child-dispatch-opts`, so the cascade
  propagation under test is unchanged."
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
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002: establish an explicit frame scope (no synthesised default).
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

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
      (rf/reg-event :test/parent
        (fn [_ _]
          {:fx [[:test/http {:tag :from-parent}]
                [:dispatch [:test/child]]]}))
      (rf/reg-event :test/child
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
      (rf/reg-event :test/lvl-0
        (fn [_ _]
          {:fx [[:test/http {:lvl 0}]
                [:dispatch [:test/lvl-1]]]}))
      (rf/reg-event :test/lvl-1
        (fn [_ _]
          {:fx [[:test/http {:lvl 1}]
                [:dispatch [:test/lvl-2]]]}))
      (rf/reg-event :test/lvl-2
        (fn [_ _]
          {:fx [[:test/http {:lvl 2}]]}))

      (rf/dispatch-sync
        [:test/lvl-0]
        {:fx-overrides {:test/http :test/http.stub}})

      (is (= [{:lvl 0} {:lvl 1} {:lvl 2}] @http-stub)
          "override propagated through three levels of :dispatch cascade"))))

;; ---- :trace-id / :origin propagation; :source overridden by fx-handler -----

(deftest trace-id-origin-propagate-source-overridden-through-cascade
  (testing ":trace-id and :origin ride the child envelope; :source is OVERRIDDEN to :fx-dispatch"
    ;; Capture parent and child envelopes via the trace stream — every
    ;; :rf.event/dispatched event surfaces :origin on :tags and :source
    ;; hoisted to the top level.
    ;;
    ;; Per rf2-ejtpd: `:source` is NOT inherited through `:fx [[:dispatch ...]]`
    ;; cascades — the `:dispatch` fx handler stamps `:source :fx-dispatch`
    ;; on the child envelope, regardless of what the parent carried. The
    ;; parent's `:source :test` is recorded against the parent's own
    ;; envelope; the child reports the substrate's actual dispatch site.
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event :test/parent
          (fn [_ _]
            {:fx [[:dispatch [:test/child]]]}))
        (rf/reg-event :test/child (fn [{:keys [db]} _] {:db db}))

        (rf/dispatch-sync [:test/parent]
                          {:trace-id ::scoped-trace
                           :origin   :test
                           :source   :test})

        (let [dispatched   (->> @seen
                                 (filter #(= :rf.event/dispatched (:operation %))))
              parent-ev    (first (filter #(= [:test/parent] (get-in % [:tags :rf.event/v])) dispatched))
              child-ev     (first (filter #(= [:test/child]  (get-in % [:tags :rf.event/v])) dispatched))]
          (is (some? parent-ev) "parent's :rf.event/dispatched is captured")
          (is (some? child-ev)  "child's :rf.event/dispatched is captured")
          ;; :origin rides :tags (inherited); :source is hoisted to top-
          ;; level by trace/emit! per Spec 009 §Core fields.
          (is (= :test      (get-in parent-ev [:tags :rf.event/origin])) "parent carries :origin :test")
          (is (= :test      (:source parent-ev))                          "parent carries :source :test")
          (is (= :test      (get-in child-ev  [:tags :rf.event/origin]))
              ":origin :test propagated through the cascade")
          (is (= :fx-dispatch (:source child-ev))
              "child's :source is :fx-dispatch (stamped by the :dispatch fx; NOT inherited from parent)"))
        (finally (rf/unregister-listener! :trace ::rec))))))

;; ---- :envelope exposed on fx-handler ctx (rf2-4jci1.4) -------------------

(deftest fx-handler-ctx-carries-envelope-slot
  (testing "user fx-handler receives (:envelope m) — the parent dispatch envelope"
    (let [captured-envelopes (atom [])]
      (rf/reg-fx :test/capture-envelope
        (fn [m _args] (swap! captured-envelopes conj (:envelope m))))
      (rf/reg-event :test/run
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
      (rf/reg-event :test/parent
        (fn [_ _]
          {:fx [[:dispatch-later {:ms 1 :event [:test/child]}]]}))
      (rf/reg-event :test/child
        (fn [_ _]
          {:fx [[:test/http {:tag :deferred}]]}))

      (rf/dispatch-sync
        [:test/parent]
        {:fx-overrides {:test/http :test/http.stub}})

      (is (= :fired (deref done 2000 :timeout))
          ":dispatch-later fired the :test/child cascade")
      (is (= [{:tag :deferred}] @stub-fired)
          ":fx-overrides propagated into :dispatch-later's deferred dispatch"))))
