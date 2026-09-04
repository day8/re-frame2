(ns re-frame.story.play.settled-boundary-cljs-test
  "Node-runtime (CLJS) tests for the `settled-boundary` headless drain
  (rf2-5x1wt.2, spec/017-Testing-Story.md §Script and `settled-boundary`).

  Dispatch / settle behaviour is host-sensitive — the JVM `.cljc` suite
  covers the pure ladder + refusal shape; this ns confirms the headless
  boundary actually drains the frame queue and synchronous re-dispatches
  to fixed point on the CLJS host (where the legacy `:dispatch` step used
  a `setTimeout` yield rather than a synchronous drain). The pure ladder
  helpers themselves also run here so a host-specific regression in the
  ladder math would surface."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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

;; ---- pure ladder math on the CLJS host -----------------------------------

(deftest ladder-and-refusal-on-cljs
  (testing "the boundary ladder + refusal helpers behave identically on CLJS"
    (is (= [:headless :cljs-reactive :dom :browser] rf.story.play.settled-boundary/boundary-levels))
    (is (rf.story.play.settled-boundary/boundary>= :dom :headless))
    (is (not (rf.story.play.settled-boundary/boundary>= :headless :dom)))
    (is (= :headless (rf.story.play.settled-boundary/step-required-boundary [:dispatch [:e]])))
    (is (= :dom      (rf.story.play.settled-boundary/step-required-boundary [:click "b"])))
    (is (= :headless (rf.story.play.settled-boundary/hooks-provided-boundary rf.story.play.settled-boundary/headless-flush-hooks)))))

;; ---- headless drain on the node host -------------------------------------

(deftest cljs-headless-dispatch-drains-to-fixed-point
  (testing "dispatch-and-settle! drains the frame queue AND synchronous
            re-dispatches to fixed point on CLJS — the whole cascade is
            settled synchronously when the call returns, NO setTimeout
            tick (the legacy :dispatch step relied on a yield here)"
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
    (let [res (rf.story.play.settled-boundary/dispatch-and-settle!
                bf [:cljs.chain/a] rf.story.play.settled-boundary/headless-flush-hooks
                :headless [:dispatch [:cljs.chain/a]])]
      (is (= :settled (:status res)))
      (is (= :headless (:boundary res)))
      (is (= [:a :b :c] (:hops (rf/app-db-value bf)))
          "the queued re-dispatch cascade drained to fixed point before return"))))

(deftest cljs-headless-refuses-dom-step
  (testing "a :dom-requiring step under the headless runner refuses with
            :cannot-run and does NOT dispatch the event on CLJS"
    (let [fired (atom false)]
      (rf/reg-event :cljs.dom/should-not-fire
        (fn [{:keys [db]} _] (reset! fired true) {:db db}))
      (let [res (rf.story.play.settled-boundary/dispatch-and-settle!
                  bf [:cljs.dom/should-not-fire] rf.story.play.settled-boundary/headless-flush-hooks
                  :dom [:click "button"])]
        (is (= :cannot-run (:status res)))
        (is (= :dom      (:required-boundary res)))
        (is (= :headless (:provided-boundary res)))
        (is (false? @fired) "fail-closed: the event is not dispatched")))))

(deftest cljs-flush-error-not-swallowed
  (testing "a throwing flush fn surfaces :error on CLJS, never a silent pass"
    (let [hooks {:provides  :dom
                 :dispatch! (fn [frame-id evec] (rf.story.play.settled-boundary/drain-sync! frame-id evec))
                 :flush!    {:dom (fn [_] (throw (ex-info "cljs flush boom" {})))}}]
      (rf/reg-event :cljs.dom/x (fn [{:keys [db]} _] {:db db}))
      (let [res (rf.story.play.settled-boundary/dispatch-and-settle! bf [:cljs.dom/x] hooks :dom [:click "b"])]
        (is (= :error (:status res)))
        (is (re-find #"cljs flush boom" (:error res)))))))
