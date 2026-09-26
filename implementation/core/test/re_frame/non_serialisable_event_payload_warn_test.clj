(ns re-frame.non-serialisable-event-payload-warn-test
  "Conventions §Event payloads SHOULD be serialisable data.
  `build-envelope` walks a dispatched event's payload for a host handle (fn /
  Promise / AbortController / DOM node / RegExp — the set
  `re-frame.reply/host-handle?` polices for the reply-map / reply-target
  data-only invariant, minus instants) and, when found, emits
  `:rf.warning/non-serialisable-event-payload`. An instant is EDN (`#inst`),
  so it never warns here, while the reply invariant keeps refusing it.

  This is a SHOULD, not the `:rf.cofx` structural-EDN MUST: the warning is
  observational (`:recovery :no-recovery`), never a throw, and dev-only —
  `rf.interop/debug-enabled?`-gated (the elision probe verifies DCE separately).

  ## Posture split

  `dispatch-proceeds-unchanged-despite-the-warning` is posture-
  independent — it reads app-db — and runs under
  `scripts/test-core-prod-gate.sh` as written. It is the load-bearing half of
  a SHOULD-level lint: a diagnostic that silently changed dispatch behaviour
  would be the actual defect.

  Everything ABOUT the warning is dev-only by design and sits
  inside a `(when rf.interop/debug-enabled? …)` arm —
  `silent-on-plain-data-payload` included, even though it would pass
  under the gate. It would pass for the wrong reason: `(is (empty? (payload-
  warnings recorded)))` over a trace stream that is empty for EVERY payload
  would certify a plain-data map as clean without the walker ever having run.
  Each of those deftests keeps an unguarded app-db witness so the production
  lane still executes the dispatch it is reasoning about."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.reply :as rf.reply]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- payload-warnings [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/non-serialisable-event-payload (:operation ev))))
           @recorded))

(deftest silent-on-plain-data-payload
  (testing "a plain-data event payload never fires the lint"
    (rf/reg-event :payload-lint/noop
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
    (let [recorded (record-traces! ::lint)]
      (rf/dispatch-sync [:payload-lint/noop {:a 1 :b [1 2 3] :c #{:x :y}}])
      ;; ALWAYS-ON WITNESS: the payload walk ran over a real
      ;; dispatch that really committed — the precondition for reading
      ;; anything off the diagnostic channel below.
      (is (= {:a 1 :b [1 2 3] :c #{:x :y}} (:seen (rf/app-db-value :rf/default)))
          "the plain-data payload reached the handler intact")
      ;; Dev-instrumentation arm (see ns docstring §Posture
      ;; split). A NEGATIVE over the trace stream: under the gate the stream
      ;; is empty whatever the payload contained.
      (when rf.interop/debug-enabled?
        (is (empty? (payload-warnings recorded)))))))

(deftest fires-on-fn-valued-payload
  (testing "a fn nested in the event payload fires exactly one warning naming
   the offending path"
    (rf/reg-event :payload-lint/noop
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
    (let [recorded (record-traces! ::lint)]
      (rf/dispatch-sync [:payload-lint/noop {:on-done (fn [] :nope)}])
      ;; ALWAYS-ON WITNESS: the fn-valued payload is carried
      ;; THROUGH to the handler unaltered. The lint neither strips nor
      ;; rejects — `:recovery :no-recovery` in production terms.
      (is (fn? (:on-done (:seen (rf/app-db-value :rf/default))))
          "the host handle reached the handler untouched — the lint is observational")
      ;; Dev-instrumentation arm (see ns docstring §Posture split).
      (when rf.interop/debug-enabled?
        (let [warns (payload-warnings recorded)]
          (is (= 1 (count warns)))
          (let [w (first warns)
                t (:tags w)]
            (is (= :payload-lint/noop (:event-id t)))
            (is (some? (:path t)))
            (is (string? (:reason t)))
            (is (= :no-recovery (:recovery w))
                ":recovery is hoisted to the top-level trace event, not nested under :tags")))))))

(def ^:private an-instant #inst "2024-01-02T03:04:05.000-00:00")

(deftest silent-on-instant-payload
  (testing "an instant is EDN (#inst) and round-trips through pr-str /
   read-string, so neither host spelling of one fires the lint"
    (rf/reg-event :payload-lint/noop
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
    (let [recorded (record-traces! ::lint)
          payload  {:at      an-instant
                    :instant (java.time.Instant/parse "2024-01-02T03:04:05Z")
                    :nested  [{:when an-instant}]}]
      (is (= an-instant (read-string (pr-str an-instant)))
          "the premise: #inst round-trips through pr-str / read-string")
      (rf/dispatch-sync [:payload-lint/noop payload])
      ;; ALWAYS-ON WITNESS: the dispatch really committed.
      (is (= payload (:seen (rf/app-db-value :rf/default)))
          "the instant-bearing payload reached the handler intact")
      ;; Dev-instrumentation arm (see ns docstring §Posture split).
      (when rf.interop/debug-enabled?
        (is (empty? (payload-warnings recorded))
            "an instant never fires :rf.warning/non-serialisable-event-payload")))))

(deftest fires-on-host-object-payload-beside-an-instant
  (testing "a host object that is not an instant still fires, and an instant
   beside it is skipped: the one warning names the host object's path"
    (rf/reg-event :payload-lint/noop
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
    (let [recorded (record-traces! ::lint)]
      (rf/dispatch-sync [:payload-lint/noop {:at an-instant :pattern #"x"}])
      ;; ALWAYS-ON WITNESS: the host object is carried through unaltered.
      (is (instance? java.util.regex.Pattern
                     (:pattern (:seen (rf/app-db-value :rf/default))))
          "the host object reached the handler untouched — the lint is observational")
      ;; Dev-instrumentation arm (see ns docstring §Posture split).
      (when rf.interop/debug-enabled?
        (let [warns (payload-warnings recorded)]
          (is (= 1 (count warns)))
          (is (= [1 :pattern] (:path (:tags (first warns))))
              "the warning names the regex Pattern, not the instant beside it"))))))

(deftest reply-invariant-still-refuses-an-instant
  (testing "the reply-map / reply-target invariant uses host-handle? whole: the
   instant the payload lint accepts is still a host handle there"
    (is (true? (rf.reply/host-handle? an-instant)))
    (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :ok :value {:settled-at an-instant}}))
        "a Date in a data-only reply map is refused — a durable reply timestamp is an epoch-ms long")
    (is (= [:value :settled-at]
           (rf.reply/walk-find-host-handle {:value {:settled-at an-instant}})))))

(deftest dispatch-proceeds-unchanged-despite-the-warning
  (testing "the warning is observational only — the dispatch still commits"
    (rf/reg-event :payload-lint/set
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
    (rf/dispatch-sync [:payload-lint/set {:cb (fn [] nil)}] {:frame :rf/default})
    (is (contains? (:seen (rf/app-db-value :rf/default)) :cb)
        "the dispatch committed normally despite the non-serialisable payload")))
