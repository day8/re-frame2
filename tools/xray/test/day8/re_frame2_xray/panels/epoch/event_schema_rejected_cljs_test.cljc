(ns day8.re-frame2-xray.panels.epoch.event-schema-rejected-cljs-test
  "An event its handler's `:schema` REJECTS, produced by the real router
  and projected by the Epoch panel's projection.

  The router validates the dispatched event vector against the handler's
  `:schema` before the interceptor chain runs, and a failure skips the
  handler. So the HANDLER step reads SKIPPED, with the schema as its
  reason — never `no :db (handler returned no :db)`, which would say the
  handler ran.

  Every record here comes from the producer: the event is dispatched with
  `dispatch-sync`, the epoch listener hands back the record, and
  `proj/project` projects it. Nothing is hand-authored.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM) and
  shadow-cljs's `cljs-test$` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [day8.re-frame2-xray.panels.epoch.projection :as proj]
   [re-frame.core :as rf]
   [re-frame.epoch]
   [re-frame.schemas]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
   [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- capture-records!
  "Make `frame`, dispatch `event` into it, and return every epoch record the
  producer delivered to an epoch listener."
  [frame event]
  (let [records (atom [])]
    (rf/register-listener! :epoch ::records (fn [r] (swap! records conj r)))
    (try
      (rf/make-frame {:id frame})
      (rf/dispatch-sync event {:frame frame})
      (finally
        (rf/unregister-listener! :epoch ::records)))
    @records))

(defn- by-step [steps]
  (into {} (map (juxt :step identity)) steps))

(deftest a-schema-rejected-event-reads-its-handler-as-skipped
  (rf/reg-event ::set-n
    {:schema [:cat [:= ::set-n] :int]}
    (fn [{:keys [db]} [_ n]]
      {:db (assoc db :n n)}))
  (testing "a rejected event never reaches its handler, and HANDLER says so"
    (let [[record] (capture-records! ::bad-frame [::set-n "not-an-int"])
          by       (by-step (proj/project record))
          handler  (:handler by)]
      (is (some #(= :event (:where %)) (:violations (:dispatch by)))
          "the schema failure is on DISPATCH, where the event was refused")
      (is (= :skipped (proj/step-status handler))
          "HANDLER reads SKIPPED — it never ran, so it returned nothing")
      (is (= :event-schema (:skip-reason handler))
          "and carries the schema as the reason the view words its body from"))))
