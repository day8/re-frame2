(ns re-frame.ui.presence-dom-cljs-test
  "S4 presence (rf2-uckeg) client behaviour: the fake-clock exit scheduler and
  the mounted three-phase machine (Spec 004 §Presence).

    - the presence clock: advance-clock! fires DUE timers deterministically, in
      order, EXACTLY ONCE (the double-cleanup guard); a partial advance leaves a
      not-yet-due exit retained (the timeout-fires-before-exit adversarial case);
    - mounted: a keyed child enters :mounting → :present; a removed key is
      RETAINED :unmounting until flush-presence! reaches :timeout-ms, then it is
      removed and its ownership released exactly once; reinsertion re-enters."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.presence-runtime :as presence]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

;; ---------------------------------------------------------------------------
;; Fixtures — the async runtime reset PLUS a deterministic presence clock
;; (reset + wall-clock disabled, so advance-clock! is the SOLE removal driver).
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  {:before #(do (presence/reset-clock!) (presence/set-wall-clock! false))
   :after  #(do (presence/reset-clock!) (presence/set-wall-clock! true))})

(deftest advance-clock-fires-due-timers-in-order
  (let [fired (atom [])]
    (presence/schedule-exit! 300 #(swap! fired conj :a))
    (presence/schedule-exit! 100 #(swap! fired conj :b))
    (presence/schedule-exit! 200 #(swap! fired conj :c))
    (testing "a partial advance fires only the due timers, in fire-at order"
      (is (= 1 (presence/advance-clock! 100)) "one exit due at t=100")
      (is (= [:b] @fired))
      (is (= 2 (presence/pending-count)) "two exits still retained"))
    (testing "advancing the rest fires the remainder in order"
      (is (= 2 (presence/advance-clock! 250)) "c (200) then a (300) come due")
      (is (= [:b :c :a] @fired))
      (is (= 0 (presence/pending-count))))))

(deftest advance-to-quiescence-fires-everything
  (let [fired (atom 0)]
    (presence/schedule-exit! 300 #(swap! fired inc))
    (presence/schedule-exit! 999 #(swap! fired inc))
    (is (= 2 (presence/advance-clock!)) "no-arg advance drains to quiescence")
    (is (= 2 @fired))
    (is (zero? (presence/pending-count)))))

(deftest removal-is-exactly-once
  ;; the double-cleanup guard: firing a timer twice (a real setTimeout landing
  ;; after a test flush; a double advance) runs the removal ONCE.
  (let [runs (atom 0)
        id   (presence/schedule-exit! 100 #(swap! runs inc))]
    (presence/fire-timer! id)
    (presence/fire-timer! id)                 ; second fire: no-op
    (presence/advance-clock! 10000)           ; nothing left to fire
    (is (= 1 @runs) "terminal removal runs exactly once")))

(deftest cancel-does-not-fire
  ;; re-entry / unmount cancels a pending exit WITHOUT running its removal.
  (let [runs (atom 0)
        id   (presence/schedule-exit! 100 #(swap! runs inc))]
    (presence/cancel-timer! id)
    (presence/advance-clock! 10000)
    (is (= 0 @runs) "a cancelled exit never removes")
    (is (zero? (presence/pending-count)))))

;; ---------------------------------------------------------------------------
;; Mounted three-phase machine (jsdom / browser)
;; ---------------------------------------------------------------------------

(rf/reg-event ::set-toasts (fn [{:keys [db]} [_ ts]] {:db (assoc db :toasts ts)}))
(rf/reg-sub ::toasts (fn [db _] (:toasts db)))

(defview toast-card [{:keys [msg]}]
  [:li {:data-testid "toast" :data-msg msg
        :data-phase (name (ui/presence-phase))}
   msg])

(defview toast-list []
  (ui/presence {:timeout-ms 300}
    (for [t (ui/sub [::toasts])]
      [toast-card {:key (:id t) :msg (:msg t)}])))

(defn- toasts [root]
  (vec (.querySelectorAll (.-container root) "[data-testid='toast']")))

(defn- phase-of [root msg]
  (some #(when (= msg (.getAttribute % "data-msg"))
           (.getAttribute % "data-phase"))
        (toasts root)))

(defn- reject-unexpectedly! [done label e]
  (is false (str label ": " (some-> e ex-message)))
  (done))

(deftest mounted-presence-machine
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — covered in the browser job")
    (async done
      (let [f (rf/make-frame {:initial-events
                              [[::set-toasts [{:id 1 :msg "a"} {:id 2 :msg "b"}]]]})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [toast-list]]]
              (-> (uit/flush!)     ; settle the enter flip (:mounting → :present)
                  (.then (fn [_]
                           (testing "enter: both keyed children present"
                             (is (= 2 (count (toasts root))))
                             (is (= "present" (phase-of root "a")))
                             (is (= "present" (phase-of root "b"))))
                           ;; remove toast "b" from the source list
                           (uit/dispatch! f [::set-toasts [{:id 1 :msg "a"}]])
                           (uit/flush!)))
                  (.then (fn [_]
                           (testing "exit: the removed child is RETAINED :unmounting"
                             (is (= 2 (count (toasts root)))
                                 "b stays mounted while exiting")
                             (is (= "present" (phase-of root "a")))
                             (is (= "unmounting" (phase-of root "b"))))
                           ;; advance the clock by LESS than :timeout-ms — still retained
                           (uit/flush-presence! 100)))
                  (.then (fn [_]
                           (testing "timeout-fires-before-exit: below :timeout-ms, still retained"
                             (is (= 2 (count (toasts root))))
                             (is (= "unmounting" (phase-of root "b"))))
                           ;; advance past :timeout-ms — the safety bound removes it
                           (uit/flush-presence! 300)))
                  (.then (fn [_]
                           (testing "removal: the timeout removes the retained child"
                             (is (= 1 (count (toasts root))) "b is gone")
                             (is (= "a" (.-textContent (first (toasts root)))))
                             (is (zero? (presence/pending-count))
                                 "no retention timer left — cleanup ran"))))))
            (.then (fn [_] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (reject-unexpectedly! done "mounted presence rejected" e))))))))

(deftest reinsertion-interrupts-exit
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — covered in the browser job")
    (async done
      (let [f (rf/make-frame {:initial-events [[::set-toasts [{:id 1 :msg "a"}]]]})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [toast-list]]]
              (-> (uit/flush!)
                  (.then (fn [_]
                           ;; remove, then re-insert BEFORE the timeout fires
                           (uit/dispatch! f [::set-toasts []])
                           (uit/flush!)))
                  (.then (fn [_]
                           (is (= "unmounting" (phase-of root "a")) "a is exiting")
                           (uit/dispatch! f [::set-toasts [{:id 1 :msg "a"}]])
                           (uit/flush!)))
                  (.then (fn [_]
                           (testing "re-entry: the exit is interrupted, a is :present again"
                             (is (= 1 (count (toasts root))))
                             (is (= "present" (phase-of root "a")))
                             (is (zero? (presence/pending-count))
                                 "the pending exit timer was cancelled"))
                           ;; and a later flush-presence! does not remove it
                           (uit/flush-presence!)))
                  (.then (fn [_]
                           (is (= 1 (count (toasts root)))
                               "a survives — its exit was interrupted")))))
            (.then (fn [_] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (reject-unexpectedly! done "reinsertion rejected" e))))))))
