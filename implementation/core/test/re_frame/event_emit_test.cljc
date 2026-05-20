(ns re-frame.event-emit-test
  "Per rf2-rirbq — the always-on event-emit substrate. Substrate-level
  contract: one record per processed event, fan-out to every
  registered listener, listener exceptions are swallowed, registry is
  symmetric under register/unregister, record shape is tight (no
  trace-bus keys).

  Companion to `re-frame.event-emit-elision-prod-test` (CLJS, prod-
  mode elision smoke). This file runs on the default JVM / Node test
  paths; the prod-elision file runs only under `:advanced` +
  `goog.DEBUG=false`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.event-emit :as event-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-trace-listeners!)
  (event-emit/clear-event-emit-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- 1. Listener fires once per processed event --------------------------

(deftest listener-fires-on-event
  (testing "A registered listener receives exactly one record per
            processed event, carrying the tight {:event :event-id
            :frame :time :outcome :elapsed-ms} shape."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :evt/inc
                       (fn [db _] (update db :n (fnil inc 0))))
      (rf/dispatch-sync [:evt/inc])
      (is (= 1 (count @seen))
          "listener fired exactly once for one dispatch")
      (let [r (first @seen)]
        (is (= [:evt/inc]      (:event r)))
        (is (= :evt/inc        (:event-id r)))
        (is (= :rf/default     (:frame r)))
        (is (= :ok             (:outcome r)))
        (is (number? (:time r))     ":time is a wall-clock millis number")
        (is (integer? (:elapsed-ms r)) ":elapsed-ms is an integer ms count")
        (is (not (neg? (:elapsed-ms r)))
            ":elapsed-ms is non-negative (max 0 (- end start) shape)")
        (is (= #{:event :event-id :frame :time :outcome :elapsed-ms}
               (set (keys r)))
            "record carries ONLY the tight Spec 009 keys — no trace-bus enrichment")))))

(deftest listener-marks-handler-exception-as-error-outcome
  (testing "When a handler throws, the listener record's :outcome is
            :error. The cascade does NOT abort (the runtime catches
            the handler exception internally) and the dispatch
            returns."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :evt/throw
                       (fn [_db _]
                         (throw (ex-info "kaboom" {:cause :test}))))
      (rf/dispatch-sync [:evt/throw])
      (is (= 1 (count @seen)))
      (is (= :error (:outcome (first @seen)))))))

;; ---- 2. Listener exceptions are swallowed --------------------------------

(deftest listener-exception-is-swallowed
  (testing "Per the substrate contract: a buggy listener cannot break
            the cascade OR prevent sibling listeners from running.
            Listener throws are caught inside `dispatch-on-event!`
            and silently dropped — no recursive emit, no propagation
            to user code."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :test/throws
        (fn [_record]
          (throw (ex-info "listener went boom" {}))))
      (rf/register-event-emit-listener!
        :test/sibling
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :evt/quiet (fn [db _] db))
      ;; Must NOT throw — the listener's exception is swallowed.
      (is (nil? (rf/dispatch-sync [:evt/quiet]))
          "dispatch-sync returned nil despite the listener throw")
      (is (= 1 (count @seen))
          "the sibling listener still received the record — fan-out is
           defensive across listeners"))))

;; ---- 3. Unregister-then-re-register is symmetric -------------------------

(deftest unregister-then-re-register
  (testing "Unregistering a listener stops it receiving subsequent
            events; re-registering the same id reattaches it. The
            registry is a plain atom — symmetric under register /
            unregister / register."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :evt/noop (fn [db _] db))
      (rf/dispatch-sync [:evt/noop])
      (is (= 1 (count @seen)) "listener fired before unregister")
      (rf/unregister-event-emit-listener! :test/recorder)
      (rf/dispatch-sync [:evt/noop])
      (is (= 1 (count @seen)) "listener silent after unregister")
      ;; Re-register and dispatch again.
      (rf/register-event-emit-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/dispatch-sync [:evt/noop])
      (is (= 2 (count @seen))
          "listener fired again after re-registration under the same id"))))

;; ---- 4. Multiple listeners are independent --------------------------------

(deftest multiple-listeners-independent
  (testing "Every registered listener receives every record,
            independently of every other. Adding a listener does not
            affect other listeners' delivery; removing one does not
            affect siblings."
    (let [a (atom [])
          b (atom [])]
      (rf/register-event-emit-listener!
        :test/listener-a
        (fn [record] (swap! a conj record)))
      (rf/register-event-emit-listener!
        :test/listener-b
        (fn [record] (swap! b conj record)))
      (rf/reg-event-db :evt/once (fn [db _] db))
      (rf/dispatch-sync [:evt/once])
      (is (= 1 (count @a)))
      (is (= 1 (count @b)))
      (rf/unregister-event-emit-listener! :test/listener-a)
      (rf/dispatch-sync [:evt/once])
      (is (= 1 (count @a)) ":listener-a stayed silent after unregister")
      (is (= 2 (count @b)) ":listener-b still fired for the second dispatch"))))

;; ---- 5. Record shape carries no trace-bus enrichment ----------------------

(deftest record-shape-is-tight-no-trace-bus-keys
  (testing "Per rf2-rirbq §record shape: the listener record carries
            ONLY {:event :event-id :frame :time :outcome
            :elapsed-ms}. No :dispatch-id, :parent-dispatch-id,
            :rf.trace/trigger-handler, :tags, :op-type, :id (the
            trace event-id counter), :source, :origin, or any other
            trace-bus key."
    (let [seen (atom nil)]
      (rf/register-event-emit-listener!
        :test/shape
        (fn [record] (reset! seen record)))
      (rf/reg-event-db :evt/shape (fn [db _] db))
      ;; Dispatch with a :source opt so we can prove the listener
      ;; record does NOT carry it (trace-bus territory).
      (rf/dispatch-sync [:evt/shape] {:source :test})
      (let [r @seen]
        (is (some? r))
        (is (= #{:event :event-id :frame :time :outcome :elapsed-ms}
               (set (keys r)))
            "exactly the Spec 009 tight-record key set, nothing else")
        (is (not (contains? r :dispatch-id)))
        (is (not (contains? r :parent-dispatch-id)))
        (is (not (contains? r :tags)))
        (is (not (contains? r :op-type)))
        (is (not (contains? r :source)))
        (is (not (contains? r :origin)))
        (is (not (contains? r :rf.trace/trigger-handler)))))))

;; ---- 6. (removed) handler-meta :sensitive? drop ---------------------------
;;
;; The handler-meta `:sensitive?` annotation has been removed. Event-emit
;; records are no longer dropped based on handler-level sensitivity; per-path
;; elision (driven by the per-frame `:rf/elision` registry, populated from
;; app-schema `:sensitive?` slot meta) is the load-bearing privacy surface
;; here. Path-marked classification supersedes the previous handler-level
;; short-circuit.

(deftest non-sensitive-handler-meta-fires-normally
  (testing "Handlers continue to fan out — handler-meta `:sensitive?`
            no longer short-circuits the substrate."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :evt/normal
                       (fn [db _] (assoc db :touched true)))
      (rf/dispatch-sync [:evt/normal "payload"])
      (is (= 1 (count @seen))
          "handler fans out normally")
      (is (= [:evt/normal "payload"] (:event (first @seen)))
          "the elided event payload reaches the listener"))))

;; ---- 7. No registered listeners → no-op (hot-path floor) -----------------

(deftest no-listeners-is-cheap-noop
  (testing "When no listeners are registered, the dispatch path
            remains functional and silent. The substrate short-
            circuits to a single deref-and-empty-check — observable
            here only by the absence of side-effects."
    (rf/reg-event-db :evt/quiet (fn [db _] db))
    ;; No listeners; just confirm dispatch settles cleanly.
    (is (nil? (rf/dispatch-sync [:evt/quiet]))
        "dispatch settled with no listeners present — no error, no throw")))
