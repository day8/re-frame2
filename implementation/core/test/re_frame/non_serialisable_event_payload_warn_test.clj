(ns re-frame.non-serialisable-event-payload-warn-test
  "Per rf2-70h9wn — Conventions §Event payloads SHOULD be serialisable data.
  `build-envelope` walks a dispatched event's payload for a host handle (fn /
  Promise / AbortController / DOM node / Date / RegExp — the same closed set
  `re-frame.reply/host-handle?` polices for the reply-map / reply-target
  data-only invariant) and, when found, emits
  `:rf.warning/non-serialisable-event-payload`.

  This is a SHOULD, not the `:rf.cofx` structural-EDN MUST: the warning is
  observational (`:recovery :no-recovery`), never a throw, and dev-only —
  `rf.interop/debug-enabled?`-gated (the elision probe verifies DCE separately).

  ## Posture split (rf2-d2841)

  `dispatch-proceeds-unchanged-despite-the-warning` is already posture-
  independent — it reads app-db — and runs under
  `scripts/test-core-prod-gate.sh` unchanged. It is the load-bearing half of
  a SHOULD-level lint: a diagnostic that silently changed dispatch behaviour
  would be the actual defect.

  Everything ABOUT the warning is dev-only by design and is kept verbatim
  inside a `(when rf.interop/debug-enabled? …)` arm marked `rf2-d2841` —
  `silent-on-plain-data-payload` included, even though it currently passes
  under the gate. It passes for the wrong reason: `(is (empty? (payload-
  warnings recorded)))` over a trace stream that is empty for EVERY payload
  would certify a plain-data map as clean without the walker ever having run.
  Each of those deftests keeps an unguarded app-db witness so the production
  lane still executes the dispatch it is reasoning about."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace/clear-listeners!)
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
      ;; ALWAYS-ON WITNESS (rf2-d2841): the payload walk ran over a real
      ;; dispatch that really committed — the precondition for reading
      ;; anything off the diagnostic channel below.
      (is (= {:a 1 :b [1 2 3] :c #{:x :y}} (:seen (rf/app-db-value :rf/default)))
          "the plain-data payload reached the handler intact")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture
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
      ;; ALWAYS-ON WITNESS (rf2-d2841): the fn-valued payload is carried
      ;; THROUGH to the handler unaltered. The lint neither strips nor
      ;; rejects — `:recovery :no-recovery` in production terms.
      (is (fn? (:on-done (:seen (rf/app-db-value :rf/default))))
          "the host handle reached the handler untouched — the lint is observational")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
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

(deftest dispatch-proceeds-unchanged-despite-the-warning
  (testing "the warning is observational only — the dispatch still commits"
    (rf/reg-event :payload-lint/set
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
    (rf/dispatch-sync [:payload-lint/set {:cb (fn [] nil)}] {:frame :rf/default})
    (is (contains? (:seen (rf/app-db-value :rf/default)) :cb)
        "the dispatch committed normally despite the non-serialisable payload")))
