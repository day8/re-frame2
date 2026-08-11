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

  ## The other half: the INSTRUMENT'S own transactions and boundaries

  Presence is what the clock is FOR; the rows below it are about the
  clock as a thing installed on a page. `mount!` installs it before it
  seeds the frame — a timer armed by an `:initial-events` step has to be
  this mount's — so a step that FAILS escapes before any handle exists to
  release the hold through, and a clock left installed does not fail the
  run, it hangs it or greens it (rf2-4mvd). And what the clock hands back
  at the boundary is a claim of its own: ids that cannot be confused with
  the platform's, and an interval that resumes on the deadline it had
  left rather than a fresh full period (rf2-w2e6). Those rows read the
  five platform functions directly, and drive a captured scheduler that
  records what it was asked for.

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

(rf/reg-event ::boom
  ;; A setup step that FAILS, deterministically. The chain catches the
  ;; handler-body throw in band and strict construction turns it into
  ;; `:rf.error/initial-events-step-failed` — which is the escape route
  ;; rf2-4mvd is about: it leaves `mount!` from `mint-frame!`, before the
  ;; frame, the container, the root or the handle exist.
  (fn [_ _] (throw (js/Error. "the seeding step fails, deliberately"))))

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
;; Reading the PLATFORM — the five functions the clock replaces
;; ---------------------------------------------------------------------------

(def ^:private surface-names
  "The whole of what an install replaces — each key under the name a red
  should call it by. One place, so that a row asserting the platform came
  back cannot assert about four of them and leave the fifth frozen."
  {:set-timeout    "js/setTimeout"
   :clear-timeout  "js/clearTimeout"
   :set-interval   "js/setInterval"
   :clear-interval "js/clearInterval"
   :date-now       "js/Date.now"})

(defn- platform-surface
  "The five functions as they are RIGHT NOW. Identity is the whole of the
  reading: two of these maps agree exactly when the same functions are
  installed."
  []
  {:set-timeout    (.-setTimeout js/globalThis)
   :clear-timeout  (.-clearTimeout js/globalThis)
   :set-interval   (.-setInterval js/globalThis)
   :clear-interval (.-clearInterval js/globalThis)
   :date-now       (.-now js/Date)})

(defn- install-surface!
  "Put `surface` back on the globals. Every row below that can be red is
  red about a clock that stayed installed, so each of them repairs the
  page unconditionally once it has taken its reading — a witness that
  detected the leak and then left it in place would take the rest of the
  browser run down with it."
  [surface]
  (set! (.-setTimeout js/globalThis)    (:set-timeout surface))
  (set! (.-clearTimeout js/globalThis)  (:clear-timeout surface))
  (set! (.-setInterval js/globalThis)   (:set-interval surface))
  (set! (.-clearInterval js/globalThis) (:clear-interval surface))
  (set! (.-now js/Date)                 (:date-now surface))
  nil)

(defn- surface-is!
  "Assert `actual` is `expected`, function by function so a red names the
  one that is wrong."
  [expected actual why]
  (doseq [[k nm] surface-names]
    (is (identical? (get expected k) (get actual k)) (str nm " — " why))))

(defn- recorder
  "A recording scheduler over `platform`. Installed BEFORE a clocked
  mount, it is what `install-clock!` captures and what a release hands
  back to — so a row can read what the clock asked the platform for, and
  with which ids.

  It DELEGATES every call to the genuine function. A stub that only
  recorded would strand the runtime's own reapers at handover and turn
  `assert-clean!` red for a reason with nothing to do with the row.

  And it hands out ids of its own, counting up from 1 — which is where a
  fresh page's timer ids start, and therefore exactly the domain a
  virtual handle must not be able to walk into. A row that armed only
  virtual timers could never see a collision at all.

  Answers `{:surface … :log … :cancel! …}`: the surface to install, the
  log as an atom of `{:kind :timeout|:interval|:cleared, :f, :delay,
  :args, :id}`, and a `cancel!` that stops a logged entry's real timer
  without logging."
  [platform]
  (let [g     js/globalThis
        !log  (atom [])
        !live (atom {})
        !next (atom 0)
        arm   (fn [kind real-fn]
                (fn [f delay & args]
                  (let [real (.apply real-fn g (to-array (list* f delay args)))
                        id   (swap! !next inc)]
                    (swap! !live assoc id real)
                    (swap! !log conj {:kind kind :f f :delay delay :args (vec args) :id id})
                    id)))
        ;; One clearer for both kinds, which is the platform's own rule.
        stop! (fn [id]
                (when-some [real (get @!live id)]
                  (swap! !live dissoc id)
                  (.call (:clear-timeout platform) g real)
                  true))
        clear (fn [id]
                (when (stop! id) (swap! !log conj {:kind :cleared :id id}))
                js/undefined)]
    {:log     !log
     :cancel! (fn [entry] (stop! (:id entry)) nil)
     :surface {:set-timeout    (arm :timeout (:set-timeout platform))
               :clear-timeout  clear
               :set-interval   (arm :interval (:set-interval platform))
               :clear-interval clear
               :date-now       (:date-now platform)}}))

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

;; ---------------------------------------------------------------------------
;; W4 — a clocked mount that FAILS puts the platform back (rf2-4mvd)
;; ---------------------------------------------------------------------------
;;
;; The install is `mount!`'s first act, deliberately: a timer armed by an
;; `:initial-events` step is one this mount has to be able to drive. That
;; ordering is what makes the failure interesting. Strict construction runs
;; the seeding inside `rf/make-frame`, so a step that fails leaves `mount!`
;; from `mint-frame!` — before a frame, a container, a root or a HANDLE
;; exists, and therefore before anything the caller could pass to `unmount!`.
;;
;; The row makes the step fail and then reads the globals. Asserting the
;; throw alone would prove nothing whatever: core raises
;; `:rf.error/initial-events-step-failed` whether or not the clock came down
;; with it, and a run that inherits a frozen `setTimeout` does not go red —
;; it hangs, or it goes GREEN on a surface it never exercised.

(deftest a-failed-clocked-mount-leaves-no-clock-installed
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM, so no root and no clock")
    (async done
      (let [platform (platform-surface)
            frames   (set (rf/frame-ids))
            thrown   (try (hm/mount! [tray] {:clock          true
                                             :initial-events [[::set [:a]] [::boom]]})
                          nil
                          (catch :default e e))
            after    (platform-surface)]

        (testing "premise: the seeding really failed, and the door delivered
                  CORE's refusal unchanged — same id, same failing step"
          (is (some? thrown) "mount! threw")
          (is (= :rf.error/initial-events-step-failed (:rf.error/id (ex-data thrown))))
          (is (= [::boom] (:event (ex-data thrown))))
          (is (= 1 (:step-index (ex-data thrown)))))

        (testing "the provisional frame is absent — core tore it down and the
                  facade never adopted it"
          (is (= frames (set (rf/frame-ids)))))

        (testing "AND THE CLOCK IS GONE. This is the reading the row exists
                  for: the hold was taken before the failing step ran, and
                  there was never a handle to give it back through, so
                  nothing but `mount!` itself could release it"
          (surface-is! platform after "the platform's own again after a failed clocked mount"))

        ;; Whatever the reading found, the page is the page from here on.
        (install-surface! platform)

        (testing "and the HOLD went with the globals, not just the globals:
                  the clock is holder-counted, so a leaked holder would show
                  up as the NEXT clocked mount's unmount failing to restore
                  anything"
          (let [m (two-toasts)]
            (is (not (identical? (:set-timeout platform) (.-setTimeout js/globalThis)))
                "premise: this mount really installed a clock of its own")
            (hm/unmount! m)
            (surface-is! platform (platform-surface)
                         "back after the only remaining holder let go")
            (install-surface! platform)
            (-> (hm/assert-clean! m)
                (.then (fn [report]
                         (is (true? (:clean? report)))
                         ;; The last word is the platform's: a real timer,
                         ;; on the real scheduler, actually firing.
                         (js/setTimeout
                           (fn []
                             (is true "a real timer still settles after a failed clocked mount")
                             (done))
                           0))))))))))

;; ---------------------------------------------------------------------------
;; W5 — the failure releases EXACTLY its own hold (rf2-4mvd)
;; ---------------------------------------------------------------------------
;;
;; The clock is page-wide because the thing it replaces is, so it is
;; holder-counted and two clocked mounts share one. That makes the repair
;; two-sided, and a row that only proved "the globals came back" would be
;; blind to the other side: a failed mount that RESTORED the platform would
;; take the instrument out from under a peer that is still driving it, and
;; the peer's next advance would move nothing.
;;
;; So this row runs the failure with a clocked peer standing, and asserts
;; both directions — the peer's clock survives the failure and still works,
;; and the peer's own teardown is then enough to put the platform back.

(deftest a-failed-clocked-mount-releases-only-its-own-hold
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM, so no root and no clock")
    (async done
      (let [platform (platform-surface)
            peer     (two-toasts)
            clocked  (platform-surface)]

        (testing "premise: the peer is standing on a clock"
          (is (not (identical? (:set-timeout platform) (:set-timeout clocked)))))

        (let [thrown (try (hm/mount! [tray] {:clock true :initial-events [[::boom]]})
                          nil
                          (catch :default e e))]

          (testing "premise: the second clocked mount fails in its seeding"
            (is (= :rf.error/initial-events-step-failed (:rf.error/id (ex-data thrown)))))

          (testing "the peer's clock is UNDISTURBED — the failed call gave back
                    one hold, and one hold is not the installation"
            (surface-is! clocked (platform-surface) "still the clock the peer is using"))

          (testing "…and the peer can still DRIVE it. The strongest reading
                    here, because a restored platform would leave these
                    globals looking fine to `identical?` on a later install
                    and still move nothing on an advance"
            (hm/dispatch-and-settle! peer [::set [:a]])
            (is (= 2 (toast-count peer)) "retained, mid-exit")
            (hm/advance-clock! peer (dec retention-ms))
            (is (= 2 (toast-count peer)) "one millisecond short")
            (hm/advance-clock! peer 1)
            (is (= 1 (toast-count peer)) "and gone at its deadline")))

        (hm/unmount! peer)

        (testing "and now — the peer being the last holder — the platform comes
                  back. A failure that released NOTHING would leave a holder
                  standing and this reading would still be the clock's"
          (surface-is! platform (platform-surface)
                       "the platform's own again once the peer let go"))

        (install-surface! platform)

        (-> (hm/assert-clean! peer)
            (.then (fn [report]
                     (is (true? (:clean? report)))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; W6 — the two schedulers' handles are DISJOINT (rf2-w2e6)
;; ---------------------------------------------------------------------------
;;
;; The clock replaces the global scheduler but not the timers already armed
;; on the real one, so `clearTimeout` inside the window has to decide which
;; of two schedulers an id belongs to — and it decides by looking the number
;; up in its own table. That is only honest while the two id domains are
;; disjoint, which they were not: the platform numbers a fresh page's timers
;; from 1 and the clock numbered its own from 1 as well.
;;
;; The failure is silent in BOTH directions from one collision. A
;; `clearTimeout` meant for the real timer finds the virtual one and kills it
;; instead, leaving the real one running; and once the virtual timer has
;; fired, ordinary cleanup of its now-stale id is passed to the platform and
;; cancels the unrelated real timer.
;;
;; A row using only virtual timers cannot see any of this. This one arms two
;; real timers BEFORE the install, through a captured scheduler that hands
;; out the ids a fresh page hands out.

(deftest a-virtual-handle-cannot-alias-a-platform-one
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM, so no root and no clock")
    (async done
      (let [platform              (platform-surface)
            {:keys [log surface]} (recorder platform)
            !fired                (atom [])
            note                  (fn [k] (fn [] (swap! !fired conj k)))
            cleared?              (fn [id]
                                    (boolean (some (fn [e] (and (= :cleared (:kind e))
                                                                (= id (:id e))))
                                                   @log)))]
        (install-surface! surface)
        (let [r1 (js/setTimeout (note :real-1) 60000)
              r2 (js/setTimeout (note :real-2) 60000)
              m  (two-toasts)
              v1 (js/setTimeout (note :v1) 50)
              v2 (js/setTimeout (note :v2) 100)
              iv (js/setInterval (note :iv) 50)]

          (testing "premise: the scheduler the clock captured numbers its
                    timers the way a platform does — from 1, upward, positive
                    — and the first two of them are real timers armed BEFORE
                    the install"
            (is (= [1 2] [r1 r2])))

          (testing "so no virtual handle can be a platform handle — not these
                    two, and not any the platform has yet to issue. A platform
                    handle is positive BY DEFINITION (HTML's timer
                    initialisation steps), and the clock's count the other way,
                    which is the whole of the disjointness. Reading it as
                    `(not= r1 v1)` would prove only that these particular
                    numbers missed each other"
            (is (every? neg? [v1 v2 iv])))

          (testing "clearing a REAL id reaches the real clearer instead of
                    being swallowed by a virtual timer wearing that number"
            (js/clearTimeout r1)
            (is (cleared? r1) "the captured scheduler was asked to cancel it"))

          (testing "…and the virtual timers are untouched by it. A collision
                    would have cleared THIS one and left the real one running"
            (hm/advance-clock! m 50)
            (is (= [:v1 :iv] @!fired)))

          (testing "and a SPENT virtual id — the ordinary cleanup of a timer
                    that has already fired — cannot cancel an unrelated real
                    timer. It is out of the table, so it goes to the platform;
                    it just names nothing the platform holds"
            (hm/advance-clock! m 50)
            (is (= [:v1 :iv :v2 :iv] @!fired) "premise: v2 fired, so its id is stale")
            (js/clearTimeout v2)
            (is (not (cleared? r2)) "the real timer numbered 2 is still armed"))

          (testing "either clearer cancels either kind, as the platform's own
                    do — and on both sides of the boundary"
            (js/clearTimeout iv)
            (hm/advance-clock! m 500)
            (is (= [:v1 :iv :v2 :iv] @!fired) "a clearTimeout retired the VIRTUAL interval")
            (js/clearInterval r2)
            (is (cleared? r2) "and a clearInterval reached the REAL timeout"))

          (hm/unmount! m)
          (install-surface! platform)
          (-> (hm/assert-clean! m)
              (.then (fn [report]
                       (is (true? (:clean? report)))
                       (done)))))))))

;; ---------------------------------------------------------------------------
;; W7 — an interval hands back the deadline it had left (rf2-w2e6)
;; ---------------------------------------------------------------------------
;;
;; `release-clock!` promises every outstanding timer goes back to the real
;; scheduler "with the time it had left", and for a one-shot it did: `(:due
;; e) - now`. A repeating one was handed its whole PERIOD, so an interval
;; released one millisecond before its tick waited another full period for
;; it and every tick after that was out of phase — for the rest of the
;; page's life, and invisibly.
;;
;; Asserting that the interval eventually fires would not see this. The row
;; asserts the FIRST post-release deadline, by reading what the captured
;; scheduler was asked for, and then drives the handover by hand so that no
;; reading here depends on the wall clock.

(deftest an-interval-hands-back-the-deadline-it-had-left
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM, so no root and no clock")
    (async done
      (let [platform                      (platform-surface)
            {:keys [log surface cancel!]} (recorder platform)
            !ticks                        (atom [])
            intervals                     (fn [es] (filterv #(= :interval (:kind %)) es))]
        (install-surface! surface)
        (let [m (two-toasts)]
          (js/setInterval (fn [& args] (swap! !ticks conj (vec args))) retention-ms "tick" 7)
          (hm/advance-clock! m (dec retention-ms))
          (is (empty? @!ticks) "premise: one virtual millisecond short of the first tick")

          (let [mark (count @log)
                _    (hm/unmount! m)
                over (vec (drop mark @log))]
            (install-surface! platform)

            (testing "the handover asked the captured scheduler for no INTERVAL
                      at all. One here is the phase shift itself: a timer with
                      one millisecond to run, given a whole fresh second"
              (is (empty? (intervals over))))
            ;; Whatever that found, nothing this row armed is left repeating.
            (doseq [e (intervals over)] (cancel! e))

            (let [firsts (filterv #(and (= :timeout (:kind %)) (= 1 (:delay %))) over)]
              (testing "…it asked for a ONE-MILLISECOND timeout — what was left
                        of the tick the interval was already waiting for"
                (is (seq firsts)))

              ;; Cancel the real arming and fire it here, so the readings below
              ;; are this row's rather than the wall clock's.
              (doseq [e firsts] (cancel! e))
              (let [mark2 (count @log)]
                (doseq [e firsts] ((:f e)))

                (testing "firing it runs the interval's own callback — once, and
                          with the arguments it was armed with"
                  (is (= [["tick" 7]] @!ticks)))

                (testing "…and only THEN is the original cadence re-armed, at
                          the period the caller asked for"
                  (let [after (vec (drop mark2 @log))]
                    (is (some (fn [e] (and (= :interval (:kind e))
                                           (= retention-ms (:delay e))
                                           (= ["tick" 7] (:args e))))
                              after))
                    (doseq [e (intervals after)] (cancel! e))))))

            (-> (hm/assert-clean! m)
                (.then (fn [report]
                         (is (true? (:clean? report)))
                         (done))))))))))

;; ---------------------------------------------------------------------------
;; W8 — a THROWING tick does not disarm the handed-back interval (rf2-w2e6)
;; ---------------------------------------------------------------------------
;;
;; The handover arms the cadence from INSIDE the first tick's one-shot, so the
;; order of two statements decides whether a repeating timer survives its own
;; callback throwing. Written sequentially it does not: control leaves the
;; one-shot with the exception, the `setInterval` under it is never reached,
;; and the interval is gone for the rest of the page's life with nothing on
;; screen to say so.
;;
;; The platform does the opposite. HTML's timer initialisation steps report
;; the handler's exception (step 9.7) and then reinitialise the still-live
;; repeating timer (step 9.11), so a real `setInterval` keeps its cadence
;; across a throwing tick. A test kit that diverges from the platform on the
;; EXCEPTION path teaches a false model of the platform, which is the worst
;; thing a test kit can do.
;;
;; W7 above drives the same handover with a callback that returns, which is
;; exactly why it cannot see this. This row throws on the first bridged tick
;; and reads both halves of the platform's rule — the exception escapes, AND
;; the cadence is armed anyway — because keeping the timer alive by eating the
;; throw would only move the divergence.

(deftest a-throwing-tick-does-not-disarm-the-handed-back-interval
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no React DOM, so no root and no clock")
    (async done
      (let [platform                      (platform-surface)
            {:keys [log surface cancel!]} (recorder platform)
            !ticks                        (atom [])
            intervals                     (fn [es] (filterv #(= :interval (:kind %)) es))]
        (install-surface! surface)
        (let [m (two-toasts)]
          ;; The FIRST tick throws and every tick after it returns, so one row
          ;; can read the exception and the cadence that outlived it.
          (js/setInterval (fn [& args]
                            (swap! !ticks conj (vec args))
                            (when (= 1 (count @!ticks))
                              (throw (js/Error. "the first bridged tick throws, deliberately"))))
                          retention-ms "tick" 7)
          (hm/advance-clock! m (dec retention-ms))
          (is (empty? @!ticks) "premise: one virtual millisecond short of the first tick")

          (let [mark (count @log)
                _    (hm/unmount! m)
                over (vec (drop mark @log))]
            (install-surface! platform)

            (let [firsts (filterv #(and (= :timeout (:kind %)) (= 1 (:delay %))) over)]
              (testing "premise: the handover armed what was left of the tick the
                        interval was already waiting for"
                (is (seq firsts)))

              ;; Cancel the real arming and fire it here, so the readings below
              ;; are this row's rather than the wall clock's.
              (doseq [e firsts] (cancel! e))
              (let [mark2  (count @log)
                    thrown (atom [])]
                (doseq [e firsts]
                  (try ((:f e))
                       (catch :default err (swap! thrown conj (ex-message err)))))

                (testing "the exception is REPORTED rather than swallowed. A
                          bridge that kept the timer alive by eating the throw
                          would trade one divergence from the platform for
                          another, and this row would pass on the wrong half"
                  (is (= ["the first bridged tick throws, deliberately"] @thrown)))

                (testing "premise: the tick that threw did run, once, with the
                          arguments it was armed with"
                  (is (= [["tick" 7]] @!ticks)))

                (let [after   (vec (drop mark2 @log))
                      cadence (filterv (fn [e] (and (= :interval (:kind e))
                                                    (= retention-ms (:delay e))
                                                    (= ["tick" 7] (:args e))))
                                       after)]
                  (testing "AND THE CADENCE IS ARMED ANYWAY — this is the row.
                            Written sequentially the throw left the one-shot
                            before the `setInterval` under it, and the captured
                            scheduler was never asked for this at all"
                    (is (= 1 (count cadence))))

                  ;; Whatever that found, nothing this row armed is left
                  ;; repeating on the real scheduler.
                  (doseq [e (intervals after)] (cancel! e))

                  (testing "…and what it armed is the interval's OWN callback at
                            its own period, so the tick after the throwing one
                            lands the way every later tick will"
                    (doseq [e cadence] (apply (:f e) (:args e)))
                    (is (= [["tick" 7] ["tick" 7]] @!ticks))))))

            (-> (hm/assert-clean! m)
                (.then (fn [report]
                         (is (true? (:clean? report)))
                         (done))))))))))
