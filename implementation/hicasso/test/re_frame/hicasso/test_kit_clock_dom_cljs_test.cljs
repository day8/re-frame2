(ns re-frame.hicasso.test-kit-clock-dom-cljs-test
  "THE MOUNTED FACADE'S CLOCK, WITNESSED (rf2-r7zq).

  `hm/advance-clock!` is an instrument for driving TIME, and the thing an
  instrument like that fails at is the thing it is easiest to write a
  green row for. A test that advances the clock and then asserts
  something that would have been true anyway proves nothing whatever; so
  does one whose subject leaves the page because a re-render dropped it
  rather than because a deadline passed.

  Every row here is therefore built around a condition that is FALSE
  before the advance and TRUE after it, with the advance as the only
  thing in between.

  ## The subject: presence, because it is the reason the door exists

  `h/presence` retains a dismissed child for `:timeout-ms` and drops it
  when the deadline passes. It is the shipped feature whose next step is
  a `setTimeout`, and it is what `rf2-5gka` — Story's presence bridge —
  is blocked on. Two facts about it decide the shape of the whole
  control, and both are asserted below rather than assumed:

  - the retirement is a `setTimeout` the React half arms
    (`impl.presence-react`), so the timer has to be virtual;
  - the callback then compares a DEADLINE against `Date.now`
    (`impl.presence/expire` takes `now`), so `Date.now` has to move with
    it. A fake timer alone fires on time and then decides that nothing
    has expired — green for the wrong reason, in the direction that
    flatters the instrument.

  [[the-clock-retires-a-retained-child-and-only-at-its-deadline]] is
  where those two meet: the child survives an advance to one millisecond
  short of its deadline and leaves on the next one. A clock that merely
  forced a re-render, or one whose callbacks read an unmoved `Date.now`,
  fails one half of that row each.

  ## Browser lane

  Every row needs a real document and a real React DOM: the timers are
  armed inside `useEffect` and nothing about them exists without a
  mounted component. `:node-test` compiles this namespace too
  (`:ns-regexp \"cljs-test$\"` matches `-dom-cljs-test`), and each row
  degrades there to a STATED skip rather than to a false green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.motion :as motion]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------
;;
;; Registered ABOVE `use-fixtures`, deliberately: the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED, so a
;; `reg-sub` written below it is erased before the first row runs.

(rf/reg-sub ::toasts (fn [db _] (:toasts db)))

(rf/reg-event ::set (fn [{:keys [db]} [_ ks]] {:db (assoc db :toasts (vec ks))}))

(def ^:private retention-ms
  "The retention window every row drives. A whole second, so that
  \"advanced to one millisecond short\" is a claim about the deadline
  rather than about rounding."
  1000)

(h/defview tray
  "A toast tray: presence over the keyed children a subscription
  supplies. The dismissed child declares its own exit appearance as data,
  which is what gives the rows below a second, independent reading of the
  same fact — the node's count, and the exit class on it."
  [_]
  [motion/presence {:timeout-ms retention-ms}
   (for [t (h/sub [::toasts])]
     [:div.toast {:key           t
                  ::h/unmounting {:class "toast toast--exit"}}
      (name t)])])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The MAP shape, because every row here is `async`. `cljs.test`
     ;; refuses an async test under a fn-form fixture and aborts the whole
     ;; run at this namespace.
     :async?        true
     :init-fn       (fn []
                      (sup/leave-act-environment!)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Reading the page
;; ---------------------------------------------------------------------------

(defn- toast-count [m] (.-length (.querySelectorAll (:container m) ".toast")))
(defn- exiting [m] (.querySelector (:container m) ".toast--exit"))

(defn- two-toasts
  "One clocked mount of the tray, seeded with two toasts."
  []
  (hm/mount! [tray] {:clock true :initial-events [[::set [:a :b]]]}))

;; ---------------------------------------------------------------------------
;; W1 — the discriminating row: retired at the deadline, and not before it
;; ---------------------------------------------------------------------------

(deftest the-clock-retires-a-retained-child-and-only-at-its-deadline
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM, so no effect runs and no timer is armed")
    (async done
      (let [m (two-toasts)]
        (testing "premise: both toasts are painted before anything leaves"
          (is (= 2 (toast-count m)))
          (is (nil? (exiting m))))

        (hm/dispatch-and-settle! m [::set [:a]])

        (testing "RETENTION: the dismissed toast outlives its data, wearing the
                  exit phase. This is the state every assertion below is
                  measured against — and on the wall clock it would stay this
                  way for a full second"
          (is (= 2 (toast-count m)) "the node survives the data — that is the module")
          (is (some? (exiting m)) "wearing the author's own `::h/unmounting` class"))

        (hm/advance-clock! m (dec retention-ms))

        (testing "ONE MILLISECOND SHORT — and it is still here. The retirement
                  belongs to the DEADLINE, not to the advance: a clock that
                  merely forced a re-render, or one that fired every armed timer
                  regardless of when it was due, would have dropped the child on
                  this line"
          (is (= 2 (toast-count m)))
          (is (some? (exiting m))))

        (hm/advance-clock! m 1)

        (testing "AT the deadline it leaves. Nothing between this reading and
                  the one above except one virtual millisecond — no dispatch, no
                  render, no wall-clock wait"
          (is (= 1 (toast-count m)))
          (is (nil? (exiting m)))
          (is (= "a" (.-textContent (.querySelector (:container m) ".toast")))
              "and the toast that remains is the one that was never dismissed"))

        (testing "teardown is clean. The positive control for the HANDOVER: the
                  runtime's own reapers were armed on the virtual clock inside
                  this window, and a release that dropped them instead of
                  re-arming them would report residue the runtime was about to
                  release"
          (-> (hm/unmount! m)
              (hm/assert-clean!)
              (.then (fn [report]
                       (is (true? (:clean? report)))
                       (is (nil? (:leaked report)))
                       (done)))))))))

;; ---------------------------------------------------------------------------
;; W2 — `Date.now` moves with the timers, and the wall clock does not move
;; ---------------------------------------------------------------------------

(deftest the-virtual-instant-moves-and-the-wall-clock-does-not
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [m  (two-toasts)
            t0 (js/Date.now)]

        (hm/advance-clock! m 250)

        (testing "the instant moved exactly as far as it was told — which is
                  what lets a timer callback's own deadline comparison come out
                  right (`impl.presence/expire` takes `now`)"
          (is (= (+ t0 250) (js/Date.now))))

        (hm/advance-clock! m 60000)

        (testing "AND THE WALL CLOCK DID NOT MOVE WITH IT. The `Date`
                  CONSTRUCTOR reads the system clock and is deliberately not
                  this window's, so it is the honest second opinion: a minute
                  and a quarter-second have passed for the code under test and
                  no measurable time has passed for the machine"
          (is (= (+ t0 60250) (js/Date.now)))
          (is (< 30000 (- (js/Date.now) (.getTime (js/Date.))))
              "the virtual instant has run away from the machine's own clock"))

        (-> (hm/unmount! m) (hm/assert-clean!) (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; W3 — the window: installed by the mount, gone with it, and refused outside
;; ---------------------------------------------------------------------------

(deftest the-clock-is-the-mounts-window-and-nothing-wider
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM")
    (async done
      (let [platform (.-setTimeout js/globalThis)
            m        (two-toasts)]

        (testing "inside the window the platform's scheduler has been replaced"
          (is (not (identical? platform (.-setTimeout js/globalThis)))))

        (hm/unmount! m)

        (testing "and the teardown puts it back. An instrument that rewrote the
                  platform and left it rewritten would make every later suite in
                  the run its subject"
          (is (identical? platform (.-setTimeout js/globalThis))))

        (testing "the door then refuses, with the handle it was given as data.
                  An advance with no clock under it would move nothing and
                  assert nothing — the row would go green for exactly the reason
                  it was written to rule out"
          (let [thrown (try (hm/advance-clock! m 5) nil (catch :default e e))]
            (is (some? thrown))
            (is (= {:ms 5 :frame (:frame m)} (ex-data thrown)))))

        (-> (hm/assert-clean! m)
            (.then (fn [_]
                     (testing "and a mount that never asked for one is refused
                               the same way"
                       (let [plain  (hm/mount! [tray] {:initial-events [[::set [:a]]]})
                             thrown (try (hm/advance-clock! plain 5) nil
                                         (catch :default e e))]
                         (is (some? thrown))
                         (is (= {:ms 5 :frame (:frame plain)} (ex-data thrown)))
                         (-> (hm/unmount! plain)
                             (hm/assert-clean!)
                             (.then (fn [report]
                                      (is (true? (:clean? report)))
                                      (done)))))))))))))
