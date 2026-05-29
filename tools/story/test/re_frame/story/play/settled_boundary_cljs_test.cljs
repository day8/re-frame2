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
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.play.settled-boundary :as boundary]))

(def ^:private bf :story.boundary.cljs/frame)

(defn- reset-frame! [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (frame/ensure-default-frame!)
  (frame/reg-frame bf {:doc "settled-boundary cljs drain test frame"})
  (test-fn))

(use-fixtures :each reset-frame!)

;; ---- pure ladder math on the CLJS host -----------------------------------

(deftest ladder-and-refusal-on-cljs
  (testing "the boundary ladder + refusal helpers behave identically on CLJS"
    (is (= [:headless :cljs-reactive :dom :browser] boundary/boundary-levels))
    (is (boundary/boundary>= :dom :headless))
    (is (not (boundary/boundary>= :headless :dom)))
    (is (= :headless (boundary/step-required-boundary [:dispatch [:e]])))
    (is (= :dom      (boundary/step-required-boundary [:click "b"])))
    (is (= :headless (boundary/hooks-provided-boundary boundary/headless-flush-hooks)))))

;; ---- headless drain on the node host -------------------------------------

(deftest cljs-headless-dispatch-drains-to-fixed-point
  (testing "dispatch-and-settle! drains the frame queue AND synchronous
            re-dispatches to fixed point on CLJS — the whole cascade is
            settled synchronously when the call returns, NO setTimeout
            tick (the legacy :dispatch step relied on a yield here)"
    (rf/reg-event-fx :cljs.chain/a
      (fn [{:keys [db]} _]
        {:db (update db :hops (fnil conj []) :a)
         :fx [[:dispatch [:cljs.chain/b]]]}))
    (rf/reg-event-fx :cljs.chain/b
      (fn [{:keys [db]} _]
        {:db (update db :hops (fnil conj []) :b)
         :fx [[:dispatch [:cljs.chain/c]]]}))
    (rf/reg-event-db :cljs.chain/c
      (fn [db _] (update db :hops (fnil conj []) :c)))
    (let [res (boundary/dispatch-and-settle!
                bf [:cljs.chain/a] boundary/headless-flush-hooks
                :headless [:dispatch [:cljs.chain/a]])]
      (is (= :settled (:status res)))
      (is (= :headless (:boundary res)))
      (is (= [:a :b :c] (:hops (rf/get-frame-db bf)))
          "the queued re-dispatch cascade drained to fixed point before return"))))

(deftest cljs-headless-refuses-dom-step
  (testing "a :dom-requiring step under the headless runner refuses with
            :cannot-run and does NOT dispatch the event on CLJS"
    (let [fired (atom false)]
      (rf/reg-event-db :cljs.dom/should-not-fire
        (fn [db _] (reset! fired true) db))
      (let [res (boundary/dispatch-and-settle!
                  bf [:cljs.dom/should-not-fire] boundary/headless-flush-hooks
                  :dom [:click "button"])]
        (is (= :cannot-run (:status res)))
        (is (= :dom      (:required-boundary res)))
        (is (= :headless (:provided-boundary res)))
        (is (false? @fired) "fail-closed: the event is not dispatched")))))

(deftest cljs-flush-error-not-swallowed
  (testing "a throwing flush fn surfaces :error on CLJS, never a silent pass"
    (let [hooks {:provides  :dom
                 :dispatch! (fn [frame-id evec] (boundary/drain-sync! frame-id evec))
                 :flush!    {:dom (fn [_] (throw (ex-info "cljs flush boom" {})))}}]
      (rf/reg-event-db :cljs.dom/x (fn [db _] db))
      (let [res (boundary/dispatch-and-settle! bf [:cljs.dom/x] hooks :dom [:click "b"])]
        (is (= :error (:status res)))
        (is (re-find #"cljs flush boom" (:error res)))))))
