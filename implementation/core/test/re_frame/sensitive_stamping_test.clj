(ns re-frame.sensitive-stamping-test
  "Trace-surface `:sensitive?` stamping and event-payload redaction. A
  handler's interceptor chain decides neither: an event's payload redacts
  through its registration's own `:sensitive` paths, and the top-level stamp
  comes from the emitters that decide sensitivity (Spec 009 §Privacy /
  sensitive data in traces).

  Every deftest that reads the `:trace` stream or the trace buffer carries
  `^:requires-debug`: neither exists under `-Dre-frame.debug=false`. The
  production-surviving redaction, on the always-on error record, is
  `privacy-production-egress-test`'s subject."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- install-sensitive! [frame-id paths]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive (mapv vec paths)}))))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- first-of [evs op]
  (first (filter #(= op (:operation %)) evs)))

(def ^:private login [:auth/login {:username "ada" :password "shh"}])

(defn- login-event-vectors
  "Register `:auth/login` with `opts`, dispatch `login`, and return every
  trace of the run that carries the event vector, as `[operation event-vector
  top-level-sensitive?]`."
  [opts]
  (rf/reg-event :auth/login opts
    (fn [{:keys [db]} [_ payload]] {:db (assoc db :last-login payload)}))
  (into []
        (keep (fn [ev]
                (when-let [v (get-in ev [:tags :rf.event/v])]
                  [(:operation ev) v (:sensitive? ev)])))
        (record-traces #(rf/dispatch-sync login))))

(deftest ^:requires-debug path-focus-changes-no-trace-payload
  (testing "with [:auth :password] classified, a handler focused at [:auth]
            emits the same event vectors and top-level stamps as the same
            handler with no path interceptor"
    (install-sensitive! :rf/default [[:auth :password]])
    (let [focused   (login-event-vectors {:interceptors [[:rf.interceptor/path [:auth]]]})
          unfocused (login-event-vectors {})]
      (is (some #(= :rf.event/run-start (first %)) focused)
          "producer control: the focused run's traces carry the event vector")
      (is (= unfocused focused))
      (is (every? #(= login (second %)) focused)
          "nothing in the chain declares the payload, so it rides raw"))))

(deftest ^:requires-debug registration-sensitive-redacts-the-payload-in-traces
  (testing "`:sensitive [[:password]]` on the reg-event redacts the payload in
            the run's traces; the handler body still sees the raw payload"
    (let [seen (atom nil)]
      (rf/reg-event :auth/login {:sensitive [[:password]]}
        (fn [{:keys [db]} [_ payload]]
          (reset! seen payload)
          {:db (assoc db :last-login payload)}))
      (let [evs      (record-traces #(rf/dispatch-sync login))
            redacted [:auth/login {:username "ada" :password :rf/redacted}]]
        (is (= (second login) @seen))
        (is (= redacted (get-in (first-of evs :rf.event/run-start) [:tags :rf.event/v])))
        (is (= redacted (get-in (first-of evs :rf.event/db-changed) [:tags :rf.event/v])))))))

(deftest ^:requires-debug trace-buffer-sensitive-filter
  (testing "the trace buffer's `:sensitive?` filter partitions on the top-level
            stamp, here a caller-supplied `:sensitive?` tag"
    (rf/clear-trace-buffer! :rf/default)
    (rf/configure! {:trace-buffer {:events-retained 100}})
    (rf/reg-event :sensitive/buf
      (fn [{:keys [db]} _]
        (rf.trace/emit! :rf.event :test/sensitive-step {:sensitive? true})
        {:db db}))
    (rf/reg-event :plain/buf
      (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:sensitive/buf])
    (rf/dispatch-sync [:plain/buf])
    (let [all   (rf/trace-buffer :rf/default {:flat true})
          sens  (rf/trace-buffer :rf/default {:flat true :sensitive? true})
          plain (rf/trace-buffer :rf/default {:flat true :sensitive? false})]
      (is (seq sens))
      (is (seq plain))
      (is (= (count all) (+ (count sens) (count plain))))
      (is (every? #(true? (:sensitive? %)) sens))
      (is (not-any? #(true? (:sensitive? %)) plain)))))

(deftest sensitive-predicate-fails-closed-on-a-malformed-stamp
  ;; `:sensitive?` is schema-typed boolean, so a non-boolean truthy stamp is a
  ;; contract violation and reads as SENSITIVE (a `true?` reading would forward
  ;; it). The result is always a boolean, never the stamp value.
  (doseq [[ev expected] [[{:sensitive? true}    true]
                         [{:sensitive? "false"} true]
                         [{:sensitive? false}   false]
                         [{:other :key}         false]
                         [nil                   false]]]
    (is (= expected (rf/sensitive? ev)) (pr-str ev))))
