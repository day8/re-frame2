(ns re-frame.trace-listener-test
  "Spec 009 — public trace listener contract + delivery semantics (rf2-h5by).

  Pins each contract claim the post-rf2-j7kv reconcile narrowed:

    1. `register-trace-cb!` is 2-arity (no opts). Returns the key.
    2. `remove-trace-cb!` returns nil and the listener stops receiving events.
    3. Synchronous, per-event delivery: `dispatch-sync` returns only after
       every registered listener has been invoked once per emitted trace
       event (no async wait, no batching).
    4. Event-emission order: a listener sees events in the order the runtime
       fired them. (Per Spec 009 §Resolved decisions, listener-call order
       across multiple listeners is NOT a contract — tools must not depend
       on it. We pin only event order, not listener order.)
    5. Point-event shape: every event carries `:operation` / `:op-type` /
       `:id` / `:time` / `:tags` and NO span-shape fields (no `:start`,
       `:end`, `:duration`, `:child-of`).
    6. Frame-aware tagging: trace events emitted on behalf of a specific
       frame carry `:frame frame-id` under `:tags`.
    7. Production elision is gated on `re-frame.interop/debug-enabled?`:
       `emit!` and the user-facing listener emit path are wrapped in the
       compile-time gate so Closure DCE strips them in `:advanced` builds
       with `goog.DEBUG=false`. Mirror of `trace-buffer-rides-debug-flag`.
    8. Re-registration with the same key replaces; only the last handler
       fires for that key. (Already pinned in trace-listener-lifecycle —
       not duplicated here.)

  JVM-only by intent; the listener API is platform-agnostic.

  Per bead rf2-h5by."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/clear-trace-buffer!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- dispatched-events
  [evs]
  (filterv #(and (= :event (:op-type %))
                 (= :event/dispatched (:operation %)))
           evs))

(def ^:private span-shape-keys
  "Span-shape fields explicitly excluded by Spec 009 §The trace event model:
  point events, not spans."
  #{:start :end :duration :child-of})

;; ---- 1. register-trace-cb! arity + return value ---------------------------

(deftest register-trace-cb-is-2-arity-and-returns-key
  (testing "register-trace-cb! takes (key cb) and returns the key"
    (let [k ::pin-arity
          ret (rf/register-trace-cb! k (fn [_ev]))]
      (is (= k ret)
          "register-trace-cb! returns the key per Spec 009 §The listener API")
      (rf/remove-trace-cb! k))))

(deftest remove-trace-cb-returns-nil
  (testing "remove-trace-cb! returns nil per Spec 009 §The listener API"
    (rf/register-trace-cb! ::r (fn [_ev]))
    (is (nil? (rf/remove-trace-cb! ::r)))))

;; ---- 2. Synchronous, per-event delivery -----------------------------------

(deftest synchronous-delivery
  (testing "listener has been called by the time dispatch-sync returns — no async wait"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::sync (fn [ev] (swap! seen conj ev)))
      (rf/reg-event-db :sync/ping (fn [db _] (assoc db :pinged? true)))
      ;; The contract: dispatch-sync returns only after every listener has
      ;; been invoked for every emitted trace event in the cascade. No
      ;; sleep, no Thread/yield, no future deref. If `(seq @seen)` is empty
      ;; here, delivery isn't synchronous.
      (rf/dispatch-sync [:sync/ping])
      (is (seq @seen)
          "listener was invoked synchronously, before dispatch-sync returned")
      (is (some #(and (= :event (:op-type %))
                      (= :event/dispatched (:operation %)))
                @seen)
          "the :event/dispatched trace was delivered synchronously")
      (rf/remove-trace-cb! ::sync))))

(deftest one-call-per-emitted-event
  (testing "the listener is invoked once per emitted event — no batching, no debounce"
    (let [calls (atom 0)]
      (rf/reg-event-db :ping (fn [db _] db))
      ;; Register the listener and clear the buffer in the same window so
      ;; we measure only the events emitted AFTER both are in place. The
      ;; contract is "one listener call per emitted event from this point".
      (rf/clear-trace-buffer!)
      (rf/register-trace-cb! ::counter (fn [_ev] (swap! calls inc)))
      ;; A single dispatch produces multiple trace events (run-start,
      ;; run-end, dispatched, do-fx, ...). Each must be delivered in its
      ;; own listener call.
      (rf/dispatch-sync [:ping])
      (let [n @calls
            buf-count (count (rf/trace-buffer))]
        (is (pos? n))
        (is (= n buf-count)
            (str "listener call count (" n ") must equal emitted-event count ("
                 buf-count ") — the listener fires once per event")))
      (rf/remove-trace-cb! ::counter))))

;; ---- 3. Event-emission order ---------------------------------------------

(deftest events-delivered-in-emission-order
  (testing "a listener sees events in the same order the runtime fired them"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::ordered (fn [ev] (swap! seen conj ev)))
      (rf/reg-event-db :ord/init (fn [_ _] {:n 0}))
      (rf/reg-event-db :ord/inc  (fn [db _] (update db :n inc)))
      (rf/dispatch-sync [:ord/init])
      (rf/dispatch-sync [:ord/inc])
      (rf/dispatch-sync [:ord/inc])
      (let [evs @seen
            ids (map :id evs)]
        (is (seq evs))
        (is (apply < ids)
            (str ":id is monotonically increasing in delivery order — "
                 "listener saw events in emission order. ids: " (pr-str ids))))
      (rf/remove-trace-cb! ::ordered))))

;; ---- 4. Point-event shape (no span fields) -------------------------------

(deftest events-are-point-shaped-not-span-shaped
  (testing "every emitted event has the canonical point-event keys and NO span-shape keys"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::shape (fn [ev] (swap! seen conj ev)))
      (rf/reg-event-fx :shape/handler (fn [_ _] {:db {:n 1}
                                                 :fx []}))
      (rf/dispatch-sync [:shape/handler])
      (let [evs @seen]
        (is (seq evs))
        (testing "every event has the canonical top-level keys"
          (is (every? (fn [ev]
                        (and (integer? (:id ev))
                             (number?  (:time ev))
                             (keyword? (:operation ev))
                             (keyword? (:op-type ev))
                             (map?     (:tags ev))))
                      evs)
              "Spec 009 §Core fields — :id :time :operation :op-type :tags"))
        (testing "no event carries span-shape fields anywhere"
          ;; Span shape would be a separate :start/:end pair, a :duration
          ;; on a single event, or a :child-of cross-event linkage. Per
          ;; Spec 009 §The trace event model, none of those exist.
          (let [violators (filter (fn [ev]
                                    (or (some #(contains? ev %)        span-shape-keys)
                                        (some #(contains? (:tags ev) %) span-shape-keys)))
                                  evs)]
            (is (empty? violators)
                (str "expected no span-shape keys; saw: "
                     (pr-str (vec (take 3 violators))))))))
      (rf/remove-trace-cb! ::shape))))

;; ---- 5. Frame-aware tagging -----------------------------------------------

(deftest trace-events-carry-frame-tag
  (testing "a trace event for a specific frame carries :frame frame-id under :tags"
    (let [seen (atom [])]
      (rf/reg-frame :frame/scoped {:doc "scoped"})
      (rf/register-trace-cb! ::framed (fn [ev] (swap! seen conj ev)))
      (rf/reg-event-db :framed/ping (fn [db _] (assoc db :ping? true)))
      (rf/dispatch-sync [:framed/ping] {:frame :frame/scoped})
      (let [dispatched (->> @seen
                            dispatched-events
                            (filter #(= [:framed/ping] (get-in % [:tags :event])))
                            first)]
        (is dispatched ":event/dispatched trace was delivered for the framed dispatch")
        (is (= :frame/scoped (get-in dispatched [:tags :frame]))
            ":frame frame-id is carried under :tags per Spec 009 §The trace event model"))
      (rf/remove-trace-cb! ::framed))))

(deftest different-frames-carry-distinct-frame-tags
  (testing "events emitted on behalf of different frames carry their respective :frame ids"
    (rf/reg-frame :frame/a {})
    (rf/reg-frame :frame/b {})
    (let [seen (atom [])]
      (rf/register-trace-cb! ::multi (fn [ev] (swap! seen conj ev)))
      (rf/reg-event-db :ping (fn [db _] db))
      (rf/dispatch-sync [:ping] {:frame :frame/a})
      (rf/dispatch-sync [:ping] {:frame :frame/b})
      (let [a (->> @seen dispatched-events
                   (filter #(= :frame/a (get-in % [:tags :frame])))
                   first)
            b (->> @seen dispatched-events
                   (filter #(= :frame/b (get-in % [:tags :frame])))
                   first)]
        (is a "frame :frame/a's :event/dispatched delivered with its frame tag")
        (is b "frame :frame/b's :event/dispatched delivered with its frame tag"))
      (rf/remove-trace-cb! ::multi))))

;; ---- 6. Production-elision contract (structural) -------------------------

(deftest emit-rides-debug-flag
  (testing "the listener-emission body is wrapped in interop/debug-enabled?"
    ;; Mirror of trace-buffer-rides-debug-flag in trace_buffer_test: the
    ;; production-elision contract is that no listener invocation, event-map
    ;; allocation, or buffer push happens when the flag is false at compile
    ;; time. We assert the source contains the gate at the right call sites;
    ;; combined with the `:elision-probe` build (Spec 009 §Production-elision
    ;; verification) that exercises the surface in production, this protects
    ;; the elision contract.
    (let [src (slurp "src/re_frame/trace.cljc")]
      (is (re-find #"\(defn-? emit![\s\S]*?interop/debug-enabled\?" src)
          "emit! body is gated on interop/debug-enabled?")
      (is (re-find #"\(defn-? emit-error![\s\S]*?interop/debug-enabled\?" src)
          "emit-error! body is gated on interop/debug-enabled?"))))
