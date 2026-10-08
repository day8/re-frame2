(ns re-frame.story.play.settled-boundary-cljs-test
  "The headless drain on the CLJS host, where the router and the flush
  error catch differ from the JVM: the cascade settles synchronously, with
  no setTimeout yield, and a flush error surfaces."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core  :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.play.settled-boundary :as rf.story.play.settled-boundary]))

(def ^:private bf :story.boundary.cljs/frame)

(defn- reset-frame! [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id bf :doc "settled-boundary cljs drain test frame"})
  (test-fn))

(use-fixtures :each reset-frame!)

(deftest cljs-headless-dispatch-drains-to-fixed-point
  (rf/reg-event :cljs.chain/a
    (fn [{:keys [db]} _]
      {:db (update db :hops (fnil conj []) :a)
       :fx [[:dispatch [:cljs.chain/b]]]}))
  (rf/reg-event :cljs.chain/b
    (fn [{:keys [db]} _]
      {:db (update db :hops (fnil conj []) :b)
       :fx [[:dispatch [:cljs.chain/c]]]}))
  (rf/reg-event :cljs.chain/c
    (fn [{:keys [db]} _] {:db (update db :hops (fnil conj []) :c)}))
  (is (= {:status :settled :boundary :headless}
         (rf.story.play.settled-boundary/dispatch-and-settle!
           bf [:cljs.chain/a] rf.story.play.settled-boundary/headless-flush-hooks
           :headless [:dispatch [:cljs.chain/a]])))
  (is (= [:a :b :c] (:hops (rf/app-db-value bf)))))

(deftest cljs-flush-error-not-swallowed
  (rf/reg-event :cljs.dom/x (fn [{:keys [db]} _] {:db db}))
  (let [res (rf.story.play.settled-boundary/dispatch-and-settle!
              bf [:cljs.dom/x]
              {:provides  :dom
               :dispatch! (fn [frame-id evec] (rf.story.play.settled-boundary/drain-sync! frame-id evec))
               :flush!    {:dom (fn [_] (throw (ex-info "cljs flush boom" {})))}}
              :dom [:click "b"])]
    (is (= :error (:status res)))
    (is (re-find #"cljs flush boom" (:error res)))))
