(ns re-frame.trace-listener-test
  "Spec 009 — the public trace-listener contract.

  Where each claim is pinned:
    - `register-listener!` returns its id, `unregister-listener!` returns nil,
      and the stream vocabulary is closed: this file.
    - Synchronous delivery: every test here reads its deliveries the moment
      `dispatch-sync` returns, so an async delivery fails them all.
    - Emission order (not listener order, which Spec 009 §Resolved decisions
      leaves unspecified): `events-delivered-in-emission-order`.
    - The canonical point-event envelope: `trace-stream-completeness` in
      `trace_test.clj`.
    - Frame tagging: `frame-isolation-trace-events-carry-only-their-own-frame`
      in `trace_buffer_test.clj` (the ring and listeners receive the same event).
    - Same-key replacement: `trace-listener-lifecycle` in `trace_test.clj`.
    - Production elision: `re-frame.trace-listener-elision-prod-test`, under
      `:advanced` + `goog.DEBUG=false`.

  JVM-only by intent; the listener API is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; init! does not synthesise :rf/default (EP-0002); emit sites need a
  ;; carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; Every deftest is ^:requires-debug: emit! is a no-op under
;; -Dre-frame.debug=false (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug in-cascade-emits-land-in-the-ring
  (testing "the ring holds exactly the listener events that carry a dispatch-id;
            frameless emits ride the live stream only"
    (let [seen (atom [])]
      (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
      (rf/register-listener! :trace ::record (fn [ev] (swap! seen conj ev)))
      (rf/dispatch-sync [:ping])
      (rf/unregister-listener! :trace ::record)
      (let [in-cascade (filter #(get-in % [:tags :rf.trace/dispatch-id]) @seen)]
        (is (seq in-cascade))
        (is (= (count in-cascade) (count (rf/trace-buffer :rf/default {:flat true}))))))))

(deftest ^:requires-debug events-delivered-in-emission-order
  (testing "a listener sees events in the order the runtime fired them"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::ordered (fn [ev] (swap! seen conj ev)))
      (rf/reg-event :ord/init (fn [_ _] {:db {:n 0}}))
      (rf/reg-event :ord/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
      (rf/dispatch-sync [:ord/init])
      (rf/dispatch-sync [:ord/inc])
      (rf/unregister-listener! :trace ::ordered)
      ;; Strictly increasing :ids also rule out a duplicated or batched delivery.
      (let [ids (map :id @seen)]
        (is (apply < ids) (str "ids in delivery order: " (pr-str ids)))))))

(deftest ^:requires-debug unknown-listener-stream-carries-canonical-thrown-error-shape
  (testing "an unknown stream throws the canonical thrown-error shape, :where naming the verb"
    (doseq [[verb-fn where-sym] [[#(rf/register-listener! :bogus ::k (fn [_])) 'rf/register-listener!]
                                 [#(rf/unregister-listener! :bogus ::k)     'rf/unregister-listener!]]]
      (let [data (ex-data (try (verb-fn) nil
                               (catch clojure.lang.ExceptionInfo ex ex)))]
        (is (= {:rf.error/id :rf.error/unknown-listener-stream
                :where       where-sym
                :recovery    :fix-registration
                :stream      :bogus
                :valid       #{:trace :epoch}}
               (dissoc data :reason)))
        (is (string? (:reason data)))))))

(deftest ^:requires-debug surviving-streams-still-register
  (testing ":trace registers and unregisters; :epoch is a member, so it never throws"
    (is (= ::still-here (rf/register-listener! :trace ::still-here (fn [_]))))
    (is (nil? (rf/unregister-listener! :trace ::still-here)))
    ;; :epoch returns its id with day8/re-frame2-epoch on the classpath and
    ;; nil without it; either way it does not throw the way :bogus does.
    (is (not= ::threw
              (try (rf/register-listener! :epoch ::ep (fn [_]))
                   (catch clojure.lang.ExceptionInfo _ ::threw))))
    (rf/unregister-listener! :epoch ::ep)))
