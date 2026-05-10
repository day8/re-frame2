(ns re-frame.flows-trace-test
  "JVM coverage for Spec 009 §Flow trace events / Spec 013 §Flow tracing
  — verifies the five `:rf.flow/*` lifecycle events fire with the
  documented payloads. The conformance fixture
  `flow-lifecycle-emits-traces.edn` describes the same shapes as data;
  this file exercises them against the JVM reference implementation
  directly so a regression surfaces as a unit-test failure even when the
  conformance harness is skipping the fixture (the reference harness
  skips `:flow/basic` capability fixtures until the runner wires the
  flow-body realiser through).

  Per rf2-2s1o: `:flow` op-type and `:rf.flow/*` operation vocabulary
  added for re-frame-10x v2's flow panel."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- per-test reset / trace recorder -------------------------------------

(def ^:dynamic ^:private *captured* nil)

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (let [captured (atom [])]
    (binding [*captured* captured]
      (trace/register-trace-cb!
        ::flow-trace-recorder
        (fn [ev]
          ;; Filter to flow op-type only — keeps assertions tight.
          (when (= :flow (:op-type ev))
            (swap! captured conj ev))))
      (try
        (test-fn)
        (finally
          (trace/remove-trace-cb! ::flow-trace-recorder))))))

(use-fixtures :each reset-runtime)

(defn- by-op
  "Filter the captured trace events by :operation, returning the matching
  events in capture order."
  [op]
  (filterv #(= op (:operation %)) @*captured*))

;; ---------------------------------------------------------------------------
;; 1. :rf.flow/registered fires after reg-flow successfully registers
;; ---------------------------------------------------------------------------

(deftest reg-flow-emits-registered-trace
  (testing "reg-flow fires :rf.flow/registered with :flow-id, :inputs, :path, :frame"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]})
    (let [evs (by-op :rf.flow/registered)]
      (is (= 1 (count evs))
          "exactly one :rf.flow/registered fired for the reg-flow call")
      (let [ev (first evs)]
        (is (= :flow (:op-type ev))                      "op-type :flow")
        (is (= :rf.flow/registered (:operation ev))      "operation :rf.flow/registered")
        (let [tags (:tags ev)]
          (is (= :area              (:flow-id tags))     ":flow-id in tags")
          (is (= [[:w] [:h]]        (:inputs tags))      ":inputs in tags")
          (is (= [:rect :area]      (:path tags))        ":path in tags")
          (is (= :rf/default        (:frame tags))       ":frame in tags"))))))

(deftest reg-flow-cycle-does-NOT-emit-registered
  (testing "when reg-flow throws cycle, no :rf.flow/registered fires for the rejected flow"
    (rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
    ;; one event so far for :a
    (is (= 1 (count (by-op :rf.flow/registered))))
    (is (thrown? Throwable
                 (rf/reg-flow {:id :b :inputs [[:a]] :output identity :path [:b]})))
    ;; Still just the one — :b's registration unwound before the trace.
    (is (= 1 (count (by-op :rf.flow/registered)))
        "only :a's register trace; :b's was rolled back")))

;; ---------------------------------------------------------------------------
;; 2. :rf.flow/computed fires when a flow recomputes
;; ---------------------------------------------------------------------------

(deftest flow-computed-fires-on-input-change
  (testing "first drain after registration emits :rf.flow/computed with :input-values, :result, :path, :frame"
    (rf/reg-event-db :init (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:init])
    (let [computes (by-op :rf.flow/computed)]
      (is (pos? (count computes))
          "first drain after init computes the flow at least once")
      (let [ev (last computes)]
        (is (= :flow (:op-type ev)))
        (let [tags (:tags ev)]
          (is (= :area         (:flow-id tags)))
          (is (= [3 4]         (:input-values tags))     ":input-values are the raw vec")
          (is (= 12            (:result tags))           ":result is the computed value")
          (is (= [:rect :area] (:path tags)))
          (is (= :rf/default   (:frame tags))))))))

;; ---------------------------------------------------------------------------
;; 3. :rf.flow/skip fires when value-equal input rewrite suppresses recompute
;; ---------------------------------------------------------------------------

(deftest flow-skip-fires-on-value-equal-rewrite
  (testing "writing :n with =-equal value emits :rf.flow/skip not :rf.flow/computed"
    (rf/reg-event-db :init       (fn [_ _] {:n 5}))
    (rf/reg-event-db :replace-n  (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    ;; Reset capture so we look only at the :replace-n drain.
    (reset! *captured* [])
    (rf/dispatch-sync [:replace-n 5])
    (let [skips    (by-op :rf.flow/skip)
          computes (by-op :rf.flow/computed)]
      (is (= 1 (count skips))
          ":n was replaced with =-equal value; one :rf.flow/skip fired")
      (is (zero? (count computes))
          "the value-equal rewrite did NOT trigger a recompute trace")
      (let [tags (:tags (first skips))]
        (is (= :double             (:flow-id tags)))
        (is (= :inputs-value-equal (:reason tags))
            ":reason names the suppression cause (rf2-719e value-equal recompute suppression)")
        (is (= :rf/default         (:frame tags)))))))

(deftest flow-skip-then-computed-on-real-change
  (testing "skip fires on equal rewrite; subsequent real change fires :rf.flow/computed"
    (rf/reg-event-db :init      (fn [_ _] {:n 5}))
    (rf/reg-event-db :replace-n (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    (reset! *captured* [])
    (rf/dispatch-sync [:replace-n 5])    ;; same value → skip
    (rf/dispatch-sync [:replace-n 7])    ;; new value  → compute
    (is (= 1 (count (by-op :rf.flow/skip))))
    (is (= 1 (count (by-op :rf.flow/computed))))))

;; ---------------------------------------------------------------------------
;; 4. :rf.flow/cleared fires when clear-flow runs
;; ---------------------------------------------------------------------------

(deftest clear-flow-emits-cleared-trace
  (testing "clear-flow emits :rf.flow/cleared with :flow-id, :path, :frame"
    (rf/reg-event-db :seed (fn [_ _] {:rect {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:rect :w] [:rect :h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:seed])
    (reset! *captured* [])
    (rf/clear-flow :area)
    (let [evs (by-op :rf.flow/cleared)]
      (is (= 1 (count evs)))
      (let [tags (:tags (first evs))]
        (is (= :area         (:flow-id tags)))
        (is (= [:rect :area] (:path tags)))
        (is (= :rf/default   (:frame tags)))))))

(deftest clear-flow-on-unknown-id-emits-nothing
  (testing "clear-flow on an unregistered id is a no-op and emits no trace"
    (rf/clear-flow :no-such-flow)
    (is (zero? (count (by-op :rf.flow/cleared))))))

;; ---------------------------------------------------------------------------
;; 5. :rf.flow/failed fires when the :output fn throws
;; ---------------------------------------------------------------------------

(deftest flow-failed-fires-when-output-throws
  (testing "a flow whose :output fn throws emits :rf.flow/failed; the exception propagates"
    (rf/reg-event-db :init       (fn [_ _] {:n 1}))
    (rf/reg-event-db :bump       (fn [db _] (update db :n inc)))
    (rf/reg-flow {:id     :boom
                  :inputs [[:n]]
                  :output (fn [_] (throw (ex-info "boom" {:why :test})))
                  :path   [:doomed]})
    (reset! *captured* [])
    ;; The router catches the cascade-level throw and emits
    ;; :rf.error/flow-eval-exception per Spec 009 §Error contract; our
    ;; concern is that the per-flow :rf.flow/failed fired before that.
    (rf/dispatch-sync [:init])
    (let [evs (by-op :rf.flow/failed)]
      (is (= 1 (count evs))
          ":rf.flow/failed fires once on the first drain (initial evaluation throws)")
      (let [tags (:tags (first evs))]
        (is (= :boom (:flow-id tags)))
        (is (some? (:ex tags))     ":ex carries the thrown exception")
        (is (= :rf/default (:frame tags)))
        (is (= [1] (:inputs tags)) ":inputs records what was read just before the throw")))
    ;; Driving another input change re-attempts (last-inputs was not
    ;; advanced on the failed path) — :rf.flow/failed fires again.
    (reset! *captured* [])
    (rf/dispatch-sync [:bump])
    (is (= 1 (count (by-op :rf.flow/failed)))
        "subsequent input change re-attempts and :rf.flow/failed fires again")))

;; ---------------------------------------------------------------------------
;; 6. End-to-end sample: all five events fire across a typical lifecycle
;; ---------------------------------------------------------------------------

(deftest typical-lifecycle-fires-all-five-events
  (testing "register → first compute → skip on equal rewrite → real recompute → clear"
    (rf/reg-event-db :init      (fn [_ _] {:n 3}))
    (rf/reg-event-db :replace-n (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:doubled]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:replace-n 3])     ;; same → skip
    (rf/dispatch-sync [:replace-n 4])     ;; change → compute
    (rf/clear-flow :double)
    (is (= 1 (count (by-op :rf.flow/registered))))
    (is (pos?  (count (by-op :rf.flow/computed))))
    (is (= 1 (count (by-op :rf.flow/skip))))
    (is (= 1 (count (by-op :rf.flow/cleared))))
    (is (zero? (count (by-op :rf.flow/failed))))))
