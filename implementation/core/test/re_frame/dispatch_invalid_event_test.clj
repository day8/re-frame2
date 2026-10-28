(ns re-frame.dispatch-invalid-event-test
  "A dispatch whose event is not an event vector — a vector headed by its
  event-id keyword (Spec-Schemas §`:rf/event`) — is refused with
  `:rf.error/invalid-event` before anything reads it (Spec 002 §Routing: the
  dispatch envelope). From `dispatch` and `dispatch-sync` the refusal is a
  throw the caller sees; from a `:dispatch` or `:dispatch-later` fx it lands
  in the fx walk, which reports it on the always-on channel and runs the
  sibling fx.

  Execution facts and always-on records are asserted unguarded, so the file
  also holds under `-Dre-frame.debug=false`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.error-emit/clear-error-listeners!)
  (rf.fx/reset-dispatch-later-timers!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id ::f})
  (test-fn)
  (rf.fx/reset-dispatch-later-timers!)
  (rf.error-emit/clear-error-listeners!))

(use-fixtures :each reset-runtime)

(defn- refusal
  "The ex-data of the ExceptionInfo `f` throws, or nil when it returns."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- recording-errors
  "Run `f` and return the always-on error records it produced."
  [f]
  (let [errors (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! errors conj %))
    (try (f) @errors
         (finally (rf.error-emit/unregister-error-listener! ::recorder)))))

(deftest a-non-event-is-refused-at-the-call-site
  (let [ran (atom 0)]
    (rf/reg-event ::target (fn [_ _] (swap! ran inc) nil))
    (doseq [bad [::target {::target 1} [] ["target"] [nil] nil '(::target)]]
      (testing (pr-str bad)
        (is (= [{:rf.error/id :rf.error/invalid-event :event bad}
                {:rf.error/id :rf.error/invalid-event :event bad}]
               (mapv #(select-keys (refusal %) [:rf.error/id :event])
                     [#(rf/dispatch-sync bad {:frame ::f})
                      #(rf/dispatch bad {:frame ::f})])))))
    (is (zero? @ran) "nothing was dispatched")
    (testing "the reason names the fix for a bare keyword"
      (is (re-find #"Wrap it as `\[:re-frame.dispatch-invalid-event-test/target\]`"
                   (:reason (refusal #(rf/dispatch-sync ::target {:frame ::f}))))))
    (testing "an event vector passes"
      (rf/dispatch-sync [::target] {:frame ::f})
      (is (= 1 @ran)))))

(deftest a-non-event-from-the-dispatch-fx-is-reported-and-the-walk-continues
  (let [ran     (atom 0)
        sibling (atom 0)]
    (rf/reg-event ::target (fn [_ _] (swap! ran inc) nil))
    (rf/reg-fx ::sibling (fn [_ _] (swap! sibling inc)))
    (rf/reg-event ::parent
      (fn [{:keys [db]} _]
        {:db (assoc db :parent-ran? true)
         :fx [[:dispatch ::target]
              [::sibling nil]]}))
    (let [errors (recording-errors #(rf/dispatch-sync [::parent] {:frame ::f}))]
      (is (= [{:rf.error/id :rf.error/invalid-event :failing-id :dispatch}]
             (mapv #(select-keys % [:rf.error/id :failing-id]) errors))
          "one always-on record names the :dispatch fx")
      (is (= [true 0 1] [(:parent-ran? (rf/app-db-value ::f)) @ran @sibling])
          "the parent committed, the child never ran, the sibling fx did"))))

(deftest a-non-event-from-dispatch-later-is-refused-before-the-timer-is-armed
  (let [ran (atom 0)]
    (rf/reg-event ::target (fn [_ _] (swap! ran inc) nil))
    (rf/reg-event ::arm (fn [_ _] {:fx [[:dispatch-later {:ms 0 :event ::target}]]}))
    (let [errors (recording-errors #(rf/dispatch-sync [::arm] {:frame ::f}))]
      ;; Long enough for an armed 0 ms timer to have fired and drained.
      (Thread/sleep 100)
      (is (= [{:rf.error/id :rf.error/invalid-event :failing-id :dispatch-later}]
             (mapv #(select-keys % [:rf.error/id :failing-id]) errors)))
      (is (zero? @ran)))))
