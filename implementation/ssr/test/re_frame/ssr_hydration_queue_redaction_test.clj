(ns re-frame.ssr-hydration-queue-redaction-test
  "The hydration payload is a production JVM boundary, and a declaration that
  reaches inside a persistent queue redacts each queued element there, as it
  does a vector's (Spec 015 §collection-matching). The walk itself is
  `re-frame.elision`'s, and core's `elision-queue-walk-cljs-test` pins it on
  both hosts; this pins the payload."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private toasts
  [{:email "queued-ssr-secret" :text "hi"}])

(defn- payload-toasts
  "`[queue? toasts]` of the hydration payload of a server frame whose app-db
  holds `coll` under `:toasts`, with `[:toasts :email]` classified sensitive."
  [frame-id coll]
  (rf/reg-classification ::toasts {:sensitive [[:toasts :email]]})
  (rf/reg-event ::seed (fn [{:keys [db]} [_ c]] {:db (assoc db :toasts c)}))
  (rf/make-frame {:id frame-id :platform :server})
  (rf/dispatch-sync [::seed coll] {:frame frame-id})
  (let [shipped (get-in (rf.ssr/hydration-payload frame-id {:payload [:toasts]})
                        [:rf/app-db :toasts])]
    [(instance? clojure.lang.PersistentQueue shipped) (vec shipped)]))

(deftest a-queued-element-redacts-in-the-hydration-payload
  (is (= [true [{:email :rf/redacted :text "hi"}]]
         (payload-toasts ::queued (into clojure.lang.PersistentQueue/EMPTY toasts)))
      "the queued element's email redacts, and the payload carries a queue")
  (is (= [false [{:email :rf/redacted :text "hi"}]]
         (payload-toasts ::vectored toasts))
      "control: the same element in a vector"))
