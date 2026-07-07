(ns re-frame.story.play.settled-boundary-test
  "Contract tests for the `settled-boundary` primitive (rf2-5x1wt.2,
  spec/017-Testing-Story.md §Script and `settled-boundary`).

  Two layers:

  - PURE (JVM + CLJS): the boundary ladder, step→boundary mapping,
    `:cannot-run` refusal shape, and the headless flush-hooks default.
  - HEADLESS DRAIN (JVM + CLJS, against a live frame): `dispatch-and-settle!`
    drains the frame queue + synchronous redispatches to fixed point; a
    step requiring a richer boundary than the runner provides refuses with
    `:cannot-run` (never a silent pass); a flush error is reported as
    `:error`, never swallowed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core   :as rf]
            [re-frame.frame  :as frame]
            [re-frame.interop :as interop]
            [re-frame.story.play.settled-boundary :as boundary]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.registrar :as registrar]))

;; ---- pure: the boundary ladder -------------------------------------------

(deftest boundary-ladder-ordering
  (testing "the ladder is cheapest→richest and `boundary>=` respects it"
    (is (= [:headless :cljs-reactive :dom :browser] boundary/boundary-levels))
    (is (boundary/boundary>= :dom :headless))
    (is (boundary/boundary>= :headless :headless))
    (is (boundary/boundary>= :browser :dom))
    (is (not (boundary/boundary>= :headless :dom)))
    (is (not (boundary/boundary>= :cljs-reactive :browser)))))

(deftest unknown-boundary-fails-closed
  (testing "unknown boundaries compare false (fail-closed)"
    (is (not (boundary/boundary>= :nonsense :headless)))
    (is (not (boundary/boundary>= :headless :nonsense)))
    (is (false? (boundary/boundary? :nonsense)))
    (is (true?  (boundary/boundary? :dom)))))

(deftest max-boundary-picks-richer
  (testing "max-boundary returns the richer of two, treating unknowns as weakest"
    (is (= :dom      (boundary/max-boundary :headless :dom)))
    (is (= :dom      (boundary/max-boundary :dom :headless)))
    (is (= :browser  (boundary/max-boundary :browser :cljs-reactive)))
    (is (= :headless (boundary/max-boundary :nonsense :nonsense)))
    (is (= :dom      (boundary/max-boundary :nonsense :dom)))))

;; ---- pure: step → required boundary --------------------------------------

(deftest step-required-boundary-mapping
  (testing "[:dispatch …] needs only :headless; DOM steps need :dom"
    (is (= :headless (boundary/step-required-boundary [:dispatch [:e]])))
    (is (= :headless (boundary/step-required-boundary [:dispatch-sync [:e]])))
    (is (= :headless (boundary/step-required-boundary [:assert-db [:k] 1])))
    (is (= :dom      (boundary/step-required-boundary [:click "button"])))
    (is (= :dom      (boundary/step-required-boundary [:type "input" "x"])))
    (is (= :dom      (boundary/step-required-boundary [:assert-dom "div" :visible]))))
  (testing "unknown / untagged steps default to :headless"
    (is (= :headless (boundary/step-required-boundary [:no/such-step])))
    (is (= :headless (boundary/step-required-boundary "not-a-step")))))

;; ---- pure: :cannot-run refusal shape -------------------------------------

(deftest cannot-run-refusal-shape
  (testing "refusal carries required + provided + reason + step"
    (let [r (boundary/cannot-run-refusal :dom :headless [:click "b"])]
      (is (= :cannot-run (:status r)))
      (is (= :dom        (:required-boundary r)))
      (is (= :headless   (:provided-boundary r)))
      (is (= :runner-below-required-boundary (:reason r)))
      (is (= [:click "b"] (:step r)))))
  (testing "an explicit reason (e.g. flush timeout) is preserved"
    (let [r (boundary/cannot-run-refusal :dom :headless nil :flush-timeout)]
      (is (= :flush-timeout (:reason r)))
      (is (not (contains? r :step)) "nil step is omitted"))))

(deftest satisfies-boundary-predicate
  (testing "a runner satisfies a step iff its provided boundary >= required"
    (is (boundary/satisfies-boundary? :dom :headless))
    (is (boundary/satisfies-boundary? :headless :headless))
    (is (not (boundary/satisfies-boundary? :headless :dom)))))

;; ---- pure: the default headless flush-hooks ------------------------------

(deftest headless-hooks-shape
  (testing "the default headless hooks provide :headless and route dispatch through the drain"
    (is (= :headless (boundary/hooks-provided-boundary boundary/headless-flush-hooks)))
    (is (fn? (:dispatch! boundary/headless-flush-hooks)))
    (is (fn? (get-in boundary/headless-flush-hooks [:flush! :headless])))))

(deftest hooks-provided-defaults-headless
  (testing "a hooks map with no :provides is assumed headless-only (fail-closed)"
    (is (= :headless (boundary/hooks-provided-boundary {})))
    (is (= :headless (boundary/hooks-provided-boundary {:provides :bogus})))
    (is (= :dom      (boundary/hooks-provided-boundary {:provides :dom})))))

;; ---- pure: flush-timeout result policy -----------------------------------

(deftest flush-timeout-policy
  (testing "a flush timeout reports :cannot-run or :error per policy, never a pass"
    (let [cr (boundary/flush-timeout-result :dom :headless [:click "b"])]
      (is (= :cannot-run (:status cr)))
      (is (= :flush-timeout (:reason cr))))
    (let [err (boundary/flush-timeout-result :dom :headless [:click "b"] :error)]
      (is (= :error (:status err)))
      (is (string? (:error err))))))

;; ---- headless drain: against a live frame --------------------------------

(def ^:private bf :story.boundary/frame)

(defn- reset-frame! [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (frame/ensure-default-frame!)
  (frame/reg-frame bf {:doc "settled-boundary drain test frame"})
  (test-fn))

(use-fixtures :each reset-frame!)

(deftest headless-dispatch-drains-to-fixed-point
  (testing "dispatch-and-settle! drains the queue AND synchronous
            re-dispatches to fixed point under the headless hooks — the
            cascade is fully settled when the call returns (no yield)"
    ;; :chain/a re-dispatches :chain/b (queued); :chain/b re-dispatches
    ;; :chain/c. A run-to-fixed-point drain settles all three before
    ;; dispatch-and-settle! returns.
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
    (let [res (boundary/dispatch-and-settle!
                bf [:chain/a] boundary/headless-flush-hooks :headless [:dispatch [:chain/a]])]
      (is (= :settled (:status res)))
      (is (= :headless (:boundary res)))
      ;; The entire synchronous cascade has settled — all three hops are
      ;; present immediately, no async tick required.
      (is (= [:a :b :c] (:hops (rf/app-db-value bf)))
          "queued re-dispatches drained to fixed point before return"))))

(deftest headless-refuses-dom-required-step
  (testing "a :dom-requiring step under the headless runner refuses with
            :cannot-run and does NOT dispatch the event (fail-closed,
            never a silent pass)"
    (let [fired (atom false)]
      (rf/reg-event :dom/should-not-fire
        (fn [{:keys [db]} _] (reset! fired true) {:db db}))
      (let [res (boundary/dispatch-and-settle!
                  bf [:dom/should-not-fire] boundary/headless-flush-hooks
                  :dom [:click "button"])]
        (is (= :cannot-run (:status res)))
        (is (= :dom      (:required-boundary res)))
        (is (= :headless (:provided-boundary res)))
        (is (false? @fired)
            "the event is NOT dispatched when the boundary cannot be satisfied")))))

(deftest richer-runner-satisfies-dom-and-runs-flush
  (testing "a runner that PROVIDES :dom satisfies a :dom step and runs the
            registered reactive + dom flush fns in ladder order"
    (let [flushed (atom [])
          hooks   {:provides  :dom
                   :dispatch! (fn [frame-id evec]
                                (boundary/drain-sync! frame-id evec))
                   :flush!    {:headless      (fn [_] (swap! flushed conj :headless))
                               :cljs-reactive (fn [_] (swap! flushed conj :reactive))
                               :dom           (fn [_] (swap! flushed conj :dom))}}]
      (rf/reg-event :dom/click (fn [{:keys [db]} _] {:db (assoc db :clicked true)}))
      (let [res (boundary/dispatch-and-settle! bf [:dom/click] hooks :dom [:click "b"])]
        (is (= :settled (:status res)))
        (is (= :dom     (:boundary res)))
        (is (true? (:clicked (rf/app-db-value bf))) "event dispatched")
        (is (= [:headless :reactive :dom] @flushed)
            "flushes run in ladder order up to and including the required boundary")))))

(deftest flush-error-is-reported-not-swallowed
  (testing "a throwing flush fn surfaces :error, never a silent pass
            (spec/017: a flush timeout/error NEVER reports a pass)"
    (let [hooks {:provides  :dom
                 :dispatch! (fn [frame-id evec] (boundary/drain-sync! frame-id evec))
                 :flush!    {:dom (fn [_] (throw (ex-info "flush boom" {})))}}]
      (rf/reg-event :dom/x (fn [{:keys [db]} _] {:db db}))
      (let [res (boundary/dispatch-and-settle! bf [:dom/x] hooks :dom [:click "b"])]
        (is (= :error (:status res)))
        (is (re-find #"flush boom" (:error res)))
        (is (not= :settled (:status res)) "a flush failure is never a settled pass")))))

(deftest timeout-ms-bounds-flush-phase-and-refuses
  (testing "a flush phase that exceeds the hooks' :timeout-ms stops the
            ladder and returns a fail-closed :cannot-run/:flush-timeout
            (never a settled pass) — the dispatch fired but the settle is
            refused, and the over-budget flush level does NOT run"
    (let [ran    (atom [])
          hooks  {:provides   :dom
                  ;; deadline is already past on entry (negative budget), so
                  ;; the loop refuses before running ANY richer flush —
                  ;; deterministic, no wall-clock sleep needed.
                  :timeout-ms -1
                  :dispatch!  (fn [frame-id evec]
                                (boundary/drain-sync! frame-id evec))
                  :flush!     {:headless      (fn [_] (swap! ran conj :headless))
                               :cljs-reactive (fn [_] (swap! ran conj :reactive))
                               :dom           (fn [_] (swap! ran conj :dom))}}]
      (rf/reg-event :timeout/fired (fn [{:keys [db]} _] {:db (assoc db :fired true)}))
      (let [res (boundary/dispatch-and-settle! bf [:timeout/fired] hooks :dom [:click "b"])]
        (is (= :cannot-run    (:status res)))
        (is (= :flush-timeout (:reason res)))
        (is (= :dom           (:required-boundary res)))
        (is (= :dom           (:provided-boundary res)))
        (is (= [:click "b"]   (:step res)))
        (is (not= :settled (:status res)) "a flush timeout is never a settled pass")
        (is (empty? @ran)
            "the over-budget flush phase ran no flush fn (deadline already past)")
        (is (true? (:fired (rf/app-db-value bf)))
            "the event was dispatched before the bounded flush phase refused")))))

(deftest timeout-ms-terminal-flush-over-budget-refuses
  (testing "when the TERMINAL (richest) flush itself blows the wall-clock
            budget, the settle is refused with :cannot-run/:flush-timeout —
            never a silent :settled pass. The deadline check after each
            flush MUST also cover the last flush in the ladder (rf2-65bnwl):
            the top-of-loop check passes for the terminal :dom level (budget
            not yet spent), the :dom flush then runs over budget, and the
            settle must NOT report :settled."
    (let [ran    (atom [])
          ;; A small positive budget: the :cljs-reactive flush stays within
          ;; it, then the terminal :dom flush deterministically pushes the
          ;; wall clock past the deadline by busy-spinning on now-ms (no
          ;; platform sleep — works on JVM + CLJS). This is the ONLY level
          ;; whose post-flush deadline check the buggy code skipped.
          budget 30
          hooks  {:provides   :dom
                  :timeout-ms budget
                  :dispatch!  (fn [frame-id evec]
                                (boundary/drain-sync! frame-id evec))
                  :flush!     {:cljs-reactive (fn [_] (swap! ran conj :reactive))
                               :dom           (fn [_]
                                                (swap! ran conj :dom)
                                                ;; burn well past the deadline
                                                ;; so the post-flush check is
                                                ;; unambiguously over budget
                                                (let [stop (+ (interop/now-ms)
                                                              (* 4 budget))]
                                                  (while (< (interop/now-ms) stop) nil)))}}]
      (rf/reg-event :timeout/terminal (fn [{:keys [db]} _] {:db (assoc db :fired true)}))
      (let [res (boundary/dispatch-and-settle! bf [:timeout/terminal] hooks :dom [:click "b"])]
        (is (not= :settled (:status res))
            "an over-budget TERMINAL flush is never a settled pass")
        (is (= :cannot-run    (:status res)))
        (is (= :flush-timeout (:reason res)))
        (is (= :dom           (:required-boundary res)))
        (is (= :dom           (:provided-boundary res)))
        (is (= [:click "b"]   (:step res)))
        (is (= [:reactive :dom] @ran)
            "the terminal flush DID run (it was within budget on entry); the
             refusal is detected by the post-flush deadline re-check")
        (is (true? (:fired (rf/app-db-value bf)))
            "the event was dispatched before the bounded flush phase refused")))))

(deftest timeout-ms-generous-budget-settles-normally
  (testing "a :timeout-ms larger than the flush phase lets settlement
            complete normally (the knob bounds, it does not break the
            happy path)"
    (let [ran   (atom [])
          hooks {:provides   :dom
                 :timeout-ms 60000
                 :dispatch!  (fn [frame-id evec]
                               (boundary/drain-sync! frame-id evec))
                 :flush!     {:cljs-reactive (fn [_] (swap! ran conj :reactive))
                              :dom           (fn [_] (swap! ran conj :dom))}}]
      (rf/reg-event :timeout/ok (fn [{:keys [db]} _] {:db (assoc db :ok true)}))
      (let [res (boundary/dispatch-and-settle! bf [:timeout/ok] hooks :dom [:click "b"])]
        (is (= :settled (:status res)))
        (is (= :dom     (:boundary res)))
        (is (= [:reactive :dom] @ran) "all flushes ran under a generous budget")
        (is (true? (:ok (rf/app-db-value bf))))))))

(deftest no-timeout-ms-is-unbounded
  (testing "with no :timeout-ms the flush phase is unbounded — settlement
            completes regardless of flush duration (the headless default)"
    (rf/reg-event :noop (fn [{:keys [db]} _] {:db db}))
    (let [res (boundary/dispatch-and-settle!
                bf [:noop] boundary/headless-flush-hooks :headless [:dispatch [:noop]])]
      (is (= :settled (:status res))))))

(deftest drain-sync-settles-synchronous-redispatch
  (testing "drain-sync! (the named headless boundary) is the framework
            dispatch-sync! drain — re-dispatched events settle before return"
    (rf/reg-event :seed/start
      (fn [{:keys [db]} _]
        {:db (assoc db :n 0)
         :fx [[:dispatch [:seed/bump]]]}))
    (rf/reg-event :seed/bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (boundary/drain-sync! bf [:seed/start])
    (is (= 1 (:n (rf/app-db-value bf)))
        "the queued :seed/bump drained synchronously within drain-sync!")))
