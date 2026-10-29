(ns re-frame.timer-example-cljs-test
  "`seven-guis.timer.core`'s two timing rules. Every tick is armed under one
   `:dispatch-later` `:id`, so arming a fresh tick replaces the one still
   pending and there is only ever one chain. And `:dispatch-later` fires not
   before its delay, never exactly on it, so elapsed time is measured between
   recorded clock samples rather than counted per callback.

   The `:dispatch-later` fx is captured through a function-value
   `:fx-overrides` entry, so no host timer is armed, and the clock is
   supplied as the recorded `:rf/time-ms` coeffect, so every elapsed value is
   exact. The replace itself is the runtime's, pinned by
   `re-frame.dispatch-later-id-test`; these tests pin that the example asks
   for it."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.integration-adapter :as rf.integration-adapter]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            [seven-guis.timer.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.integration-adapter/adapter}))

(def ^:private tick
  "The one tick, as `:dispatch-later` receives it."
  {:id :timer/tick :ms 100 :event [:timer/tick]})

(defn- elapsed-ms [f]
  (get-in (rf/app-db-value f) [:timer :elapsed-ms]))

(def ^:private t0 1781078400000)

(defn- dispatch-at! [f t event]
  (rf/dispatch-sync event {:frame f :rf.cofx {:rf/time-ms t}}))

(defn- with-captured-schedules
  "Run `body-fn` with [frame captured-atom] against a fresh frame whose
   `:dispatch-later` args are captured rather than armed."
  [body-fn]
  (let [captured      (atom [])
        capture-later (fn [_ctx args] (swap! captured conj args))]
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                         {:fx-overrides {:dispatch-later capture-later}})]
      (body-fn f captured))))

(deftest every-arm-names-the-one-tick-so-reset-replaces-it
  (with-captured-schedules
    (fn [f captured]
      (dispatch-at! f t0 [:timer/initialise])
      (dispatch-at! f (+ t0 100) [:timer/tick])
      (dispatch-at! f (+ t0 200) [:timer/reset])
      (is (= [tick tick tick] @captured)
          ":initialise, the tick and Reset each armed the same named tick, so each arm replaced the one pending and a single chain runs")
      (reset! captured [])
      (dispatch-at! f (+ t0 300) [:timer/tick])
      (is (= [100 [tick]] [(elapsed-ms f) @captured])
          "the tick after Reset measures from the Reset's anchor and keeps the one chain going"))))

(deftest elapsed-follows-recorded-time-not-callback-count
  (with-captured-schedules
    (fn [f captured]
      (dispatch-at! f t0 [:timer/initialise])
      (reset! captured [])
      ;; Scheduled for 100ms, delivered at +450ms: a callback counter credits 100.
      (dispatch-at! f (+ t0 450) [:timer/tick])
      (is (= 450 (elapsed-ms f))
          "a late callback advances elapsed by the 450ms that actually passed")
      (is (= [tick] @captured)
          "still inside the duration, so exactly one follow-up was armed")
      (dispatch-at! f (+ t0 550) [:timer/tick])
      (is (= 550 (elapsed-ms f))
          "the next sample measures from the re-anchored 450ms reading")
      (reset! captured [])
      (dispatch-at! f (+ t0 90000) [:timer/tick])
      (is (= 10000 (elapsed-ms f))
          "a hugely delayed sample clamps exactly to the 10s duration")
      (is (empty? @captured)
          "a completed timer schedules no further tick"))))

(deftest reset-and-rearm-establish-a-fresh-time-baseline
  (with-captured-schedules
    (fn [f captured]
      (dispatch-at! f t0 [:timer/initialise])
      (dispatch-at! f (+ t0 10000) [:timer/tick])
      ;; Finished, left idle for a minute, then the slider is dragged up.
      (reset! captured [])
      (dispatch-at! f (+ t0 70000) [:timer/set-duration 20000])
      (is (= [tick] @captured)
          "the re-arm armed exactly one fresh tick")
      (dispatch-at! f (+ t0 70100) [:timer/tick])
      (is (= 10100 (elapsed-ms f))
          "the first resumed sample adds the 100ms since the drag, not the idle minute")
      (reset! captured [])
      (dispatch-at! f (+ t0 80000) [:timer/reset])
      (is (= [tick] @captured)
          "Reset armed exactly one tick, replacing the one in flight")
      (dispatch-at! f (+ t0 80100) [:timer/tick])
      (is (= 100 (elapsed-ms f))
          "Reset zeroed elapsed and re-anchored the clock, so this sample adds 100ms"))))

(deftest shortening-below-elapsed-keeps-measured-time
  (with-captured-schedules
    (fn [f captured]
      (dispatch-at! f t0 [:timer/initialise])
      (dispatch-at! f (+ t0 5000) [:timer/tick])
      (reset! captured [])
      (dispatch-at! f (+ t0 5050) [:timer/set-duration 1000])
      (is (= 5000 (elapsed-ms f)) "the drag itself leaves elapsed alone")
      (is (empty? @captured) "and arms nothing, since the live chain is still in the air")
      (dispatch-at! f (+ t0 5100) [:timer/tick])
      (is (= 5000 (elapsed-ms f))
          "the pending tick holds the 5000ms measured; a deadline clamp would rewind it to 1000ms")
      (is (empty? @captured) "past its target, so the chain ends")
      (dispatch-at! f (+ t0 5200) [:timer/set-duration 3000])
      (is (empty? @captured) "a target still behind elapsed does not re-arm")
      (dispatch-at! f (+ t0 6000) [:timer/set-duration 8000])
      (is (= [tick] @captured)
          "a target past elapsed re-arms exactly one fresh tick")
      (dispatch-at! f (+ t0 6100) [:timer/tick])
      (is (= 5100 (elapsed-ms f))
          "the first resumed sample counts the 100ms since the re-arm"))))
