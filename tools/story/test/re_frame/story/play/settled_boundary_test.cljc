(ns re-frame.story.play.settled-boundary-test
  "The `settled-boundary` primitive (spec/017-Testing-Story.md §Script and
  `settled-boundary`): the boundary ladder, and `dispatch-and-settle!`
  against a live frame, which drains to fixed point, refuses a step needing
  a richer boundary than the runner provides with `:cannot-run`, and
  reports a flush error or timeout, never a silent pass."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.core   :as rf]
            [re-frame.frame  :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.story.play.settled-boundary :as rf.story.play.settled-boundary]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.registrar :as rf.registrar]))

;; ---- pure: the boundary ladder -------------------------------------------

(deftest boundary-ladder-ordering
  (is (= [:headless :cljs-reactive :dom :browser] rf.story.play.settled-boundary/boundary-levels))
  (is (rf.story.play.settled-boundary/boundary>= :dom :headless))
  (is (rf.story.play.settled-boundary/boundary>= :headless :headless))
  (is (not (rf.story.play.settled-boundary/boundary>= :headless :dom))))

(deftest unknown-boundary-fails-closed
  (is (not (rf.story.play.settled-boundary/boundary>= :nonsense :headless)))
  (is (not (rf.story.play.settled-boundary/boundary>= :headless :nonsense)))
  (is (= :headless (rf.story.play.settled-boundary/hooks-provided-boundary {:provides :bogus}))
      "a runner providing no recognised boundary is assumed headless-only"))

(deftest max-boundary-picks-richer
  (is (= :dom     (rf.story.play.settled-boundary/max-boundary :headless :dom)))
  (is (= :browser (rf.story.play.settled-boundary/max-boundary :browser :cljs-reactive))))

;; ---- pure: step → required boundary --------------------------------------

(deftest step-required-boundary-mapping
  (are [step boundary] (= boundary (rf.story.play.settled-boundary/step-required-boundary step))
    [:dispatch [:e]]           :headless
    [:click "button"]          :dom
    [:type "input" "x"]        :dom
    [:assert-dom "div" :visible] :dom
    ;; unknown / untagged steps default to :headless
    [:no/such-step]            :headless))

;; ---- headless drain: against a live frame --------------------------------

(def ^:private bf :story.boundary/frame)

(defn- reset-frame! [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id bf :doc "settled-boundary drain test frame"})
  (test-fn))

(use-fixtures :each reset-frame!)

(deftest headless-dispatch-drains-to-fixed-point
  ;; :chain/a queues :chain/b, which queues :chain/c: all settle before return
  (rf/reg-event :chain/a
    (fn [{:keys [db]} _]
      {:db (update db :hops (fnil conj []) :a)
       :fx [[:dispatch [:chain/b]]]}))
  (rf/reg-event :chain/b
    (fn [{:keys [db]} _]
      {:db (update db :hops (fnil conj []) :b)
       :fx [[:dispatch [:chain/c]]]}))
  (rf/reg-event :chain/c
    (fn [{:keys [db]} _] {:db (update db :hops (fnil conj []) :c)}))
  (is (= {:status :settled :boundary :headless}
         (rf.story.play.settled-boundary/dispatch-and-settle!
           bf [:chain/a] rf.story.play.settled-boundary/headless-flush-hooks :headless [:dispatch [:chain/a]])))
  (is (= [:a :b :c] (:hops (rf/app-db-value bf)))))

(deftest headless-refuses-dom-required-step
  (let [fired (atom false)]
    (rf/reg-event :dom/should-not-fire
      (fn [{:keys [db]} _] (reset! fired true) {:db db}))
    (is (= {:status            :cannot-run
            :required-boundary :dom
            :provided-boundary :headless
            :reason            :runner-below-required-boundary
            :step              [:click "button"]}
           (rf.story.play.settled-boundary/dispatch-and-settle!
             bf [:dom/should-not-fire] rf.story.play.settled-boundary/headless-flush-hooks
             :dom [:click "button"])))
    (is (false? @fired) "the event is NOT dispatched when the boundary cannot be satisfied")))

(defn- dom-hooks
  "Hooks providing :dom, recording each flush level that runs into `ran`."
  [ran extra]
  (merge {:provides  :dom
          :dispatch! (fn [frame-id evec] (rf.story.play.settled-boundary/drain-sync! frame-id evec))
          :flush!    {:headless      (fn [_] (swap! ran conj :headless))
                      :cljs-reactive (fn [_] (swap! ran conj :reactive))
                      :dom           (fn [_] (swap! ran conj :dom))}}
         extra))

(deftest richer-runner-satisfies-dom-and-runs-flush
  ;; flushes run in ladder order up to and including the required boundary
  (let [ran (atom [])]
    (rf/reg-event :dom/click (fn [{:keys [db]} _] {:db (assoc db :clicked true)}))
    (is (= {:status :settled :boundary :dom}
           (rf.story.play.settled-boundary/dispatch-and-settle! bf [:dom/click] (dom-hooks ran {}) :dom [:click "b"])))
    (is (true? (:clicked (rf/app-db-value bf))))
    (is (= [:headless :reactive :dom] @ran))))

(deftest flush-error-is-reported-not-swallowed
  (rf/reg-event :dom/x (fn [{:keys [db]} _] {:db db}))
  (let [res (rf.story.play.settled-boundary/dispatch-and-settle!
              bf [:dom/x]
              {:provides  :dom
               :dispatch! (fn [frame-id evec] (rf.story.play.settled-boundary/drain-sync! frame-id evec))
               :flush!    {:dom (fn [_] (throw (ex-info "flush boom" {})))}}
              :dom [:click "b"])]
    (is (= :error (:status res)))
    (is (re-find #"flush boom" (:error res)))))

(def ^:private flush-timeout
  {:status            :cannot-run
   :required-boundary :dom
   :provided-boundary :dom
   :reason            :flush-timeout
   :step              [:click "b"]})

(deftest timeout-ms-bounds-flush-phase-and-refuses
  ;; the deadline is already past on entry, so no flush runs; the dispatch
  ;; itself has fired
  (let [ran (atom [])]
    (rf/reg-event :timeout/fired (fn [{:keys [db]} _] {:db (assoc db :fired true)}))
    (is (= flush-timeout
           (rf.story.play.settled-boundary/dispatch-and-settle!
             bf [:timeout/fired] (dom-hooks ran {:timeout-ms -1}) :dom [:click "b"])))
    (is (empty? @ran))
    (is (true? (:fired (rf/app-db-value bf))))))

(deftest timeout-ms-terminal-flush-over-budget-refuses
  ;; The terminal :dom flush starts inside the budget and overruns it, so
  ;; only the deadline re-check AFTER each flush can catch it.
  (let [ran    (atom [])
        budget 30
        hooks  {:provides   :dom
                :timeout-ms budget
                :dispatch!  (fn [frame-id evec]
                              (rf.story.play.settled-boundary/drain-sync! frame-id evec))
                :flush!     {:cljs-reactive (fn [_] (swap! ran conj :reactive))
                             :dom           (fn [_]
                                              (swap! ran conj :dom)
                                              (let [stop (+ (rf.interop/now-ms) (* 4 budget))]
                                                (while (< (rf.interop/now-ms) stop) nil)))}}]
    (rf/reg-event :timeout/terminal (fn [{:keys [db]} _] {:db (assoc db :fired true)}))
    (is (= flush-timeout
           (rf.story.play.settled-boundary/dispatch-and-settle! bf [:timeout/terminal] hooks :dom [:click "b"])))
    (is (= [:reactive :dom] @ran))
    (is (true? (:fired (rf/app-db-value bf))))))

(deftest timeout-ms-generous-budget-settles-normally
  (let [ran (atom [])]
    (rf/reg-event :timeout/ok (fn [{:keys [db]} _] {:db (assoc db :ok true)}))
    (is (= {:status :settled :boundary :dom}
           (rf.story.play.settled-boundary/dispatch-and-settle!
             bf [:timeout/ok] (dom-hooks ran {:timeout-ms 60000}) :dom [:click "b"])))
    (is (= [:headless :reactive :dom] @ran))
    (is (true? (:ok (rf/app-db-value bf))))))
