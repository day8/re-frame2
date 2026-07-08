(ns re-frame.non-serialisable-event-payload-warn-test
  "Per rf2-70h9wn — Conventions §Event payloads SHOULD be serialisable data.
  `build-envelope` walks a dispatched event's payload for a host handle (fn /
  Promise / AbortController / DOM node / Date / RegExp — the same closed set
  `re-frame.reply/host-handle?` polices for the reply-map / reply-target
  data-only invariant) and, when found, emits
  `:rf.warning/non-serialisable-event-payload`.

  This is a SHOULD, not the `:rf.cofx` structural-EDN MUST: the warning is
  observational (`:recovery :no-recovery`), never a throw, and dev-only —
  `interop/debug-enabled?`-gated (the elision probe verifies DCE separately)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
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
    (rf/reg-event :payload-lint/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::lint)]
      (rf/dispatch-sync [:payload-lint/noop {:a 1 :b [1 2 3] :c #{:x :y}}])
      (is (empty? (payload-warnings recorded))))))

(deftest fires-on-fn-valued-payload
  (testing "a fn nested in the event payload fires exactly one warning naming
   the offending path"
    (rf/reg-event :payload-lint/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::lint)]
      (rf/dispatch-sync [:payload-lint/noop {:on-done (fn [] :nope)}])
      (let [warns (payload-warnings recorded)]
        (is (= 1 (count warns)))
        (let [w (first warns)
              t (:tags w)]
          (is (= :payload-lint/noop (:event-id t)))
          (is (some? (:path t)))
          (is (string? (:reason t)))
          (is (= :no-recovery (:recovery w))
              ":recovery is hoisted to the top-level trace event, not nested under :tags"))))))

(deftest dispatch-proceeds-unchanged-despite-the-warning
  (testing "the warning is observational only — the dispatch still commits"
    (rf/reg-event :payload-lint/set
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
    (rf/dispatch-sync [:payload-lint/set {:cb (fn [] nil)}] {:frame :rf/default})
    (is (contains? (:seen (rf/app-db-value :rf/default)) :cb)
        "the dispatch committed normally despite the non-serialisable payload")))
