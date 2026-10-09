(ns re-frame.non-serialisable-event-payload-warn-test
  "Conventions §Event payloads SHOULD be serialisable data. A dispatched
  payload carrying a host handle (fn / Promise / AbortController / DOM node /
  RegExp — `re-frame.reply/host-handle?` minus instants, which are EDN) emits
  the dev-only `:rf.warning/non-serialisable-event-payload`; the dispatch
  itself is never altered.

  ## Posture split

  Each deftest's app-db witness is posture-independent: the dispatch commits
  whatever the payload carries. Everything about the warning sits in a
  `(when rf.interop/debug-enabled? …)` arm — the negatives too, which over the
  gate's empty trace stream would pass without the walker ever running."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (rf/reg-event :payload-lint/noop
      (fn [{:keys [db]} [_ payload]] {:db (assoc db :seen payload)}))
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

(defn- seen []
  (:seen (rf/app-db-value :rf/default)))

(def ^:private an-instant #inst "2024-01-02T03:04:05.000-00:00")

(deftest silent-on-plain-data-payload
  (testing "plain data, instants (EDN #inst) included, never fires the lint"
    (let [recorded (record-traces! ::lint)]
      (doseq [payload [{:a 1 :b [1 2 3] :c #{:x :y}}
                       {:at      an-instant
                        :instant (java.time.Instant/parse "2024-01-02T03:04:05Z")
                        :nested  [{:when an-instant}]}]]
        (rf/dispatch-sync [:payload-lint/noop payload])
        (is (= payload (seen)) "the payload reached the handler intact"))
      (when rf.interop/debug-enabled?
        (is (empty? (payload-warnings recorded)))))))

(deftest fires-on-fn-valued-payload
  (testing "a fn nested in the payload reaches the handler untouched and fires
            exactly one warning naming the offending path"
    (let [recorded (record-traces! ::lint)]
      (rf/dispatch-sync [:payload-lint/noop {:on-done (fn [] :nope)}])
      (is (fn? (:on-done (seen))))
      (when rf.interop/debug-enabled?
        ;; :recovery rides the top-level trace event, not :tags
        (is (= [[:payload-lint/noop true true :no-recovery]]
               (mapv (fn [{:keys [tags recovery]}]
                       [(:event-id tags) (some? (:path tags)) (string? (:reason tags)) recovery])
                     (payload-warnings recorded))))))))

(deftest fires-on-host-object-payload-beside-an-instant
  (testing "a host object that is not an instant still fires, and the instant
            beside it is skipped: the one warning names the host object's path"
    (let [recorded (record-traces! ::lint)]
      (rf/dispatch-sync [:payload-lint/noop {:at an-instant :pattern #"x"}])
      (is (instance? java.util.regex.Pattern (:pattern (seen))))
      (when rf.interop/debug-enabled?
        (is (= [[1 :pattern]]
               (mapv (comp :path :tags) (payload-warnings recorded))))))))
