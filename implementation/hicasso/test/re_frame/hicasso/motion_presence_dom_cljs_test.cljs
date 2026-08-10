(ns re-frame.hicasso.motion-presence-dom-cljs-test
  "THE PRESENCE/MOTION POSTURE, WITNESSED (rf2-hic-053).

  > Motion belongs to CSS, to the compositor and to the host. This runtime
  > owns exactly one thing about it, and that thing is retention.
  >
  > — [[re-frame.hicasso.motion]]

  A posture is only worth stating if something would go red when it stops
  being true. Three claims follow from that paragraph and each has a row
  here:

  | claim | row |
  |---|---|
  | an interrupted transition cancels on the node it already had | [[an-exit-interrupted-by-re-entry-cancels-on-the-node-it-already-had]] |
  | a rapid toggle arms one deadline per exit and never extends a live one | [[a-rapid-toggle-arms-one-deadline-per-exit-and-never-extends-a-live-one]] |
  | the per-frame work of a transition is zero — the frame budget | [[re-deriving-mid-flight-is-a-no-op-on-every-frame-of-the-window]] and the DOM row's rAF/interval census |
  | an unmount mid-transition leaves no timer behind | [[an-unmount-mid-transition-clears-the-timer-it-armed]] |

  ## Why the frame budget is a COUNT and not a stopwatch

  §6 of the specification asks that *dragging and animation remain inside
  their frame budget, normally by keeping high-rate mechanics local to a
  native host*. The honest reading of that for this module is not \"our
  per-frame work fits in 16.7 ms\" — it is that **there is no per-frame
  work to measure**. Presence arms one timer per outstanding deadline and
  does nothing whatever between frames: no `requestAnimationFrame`, no
  interval, no per-frame callback, no state write. So the instrument is a
  census, and the same section is what makes that the right choice —
  *noisy clock distributions are adjudicated in pinned evidence runs
  rather than converted into flaky PR thresholds*. A wall-clock threshold
  here would be a slower way to learn less.

  The headless half of the census is
  [[re-deriving-mid-flight-is-a-no-op-on-every-frame-of-the-window]]: the
  machine is re-derived at every 60 Hz frame of a retention window and
  must come back **equal every time**, with an unmoved
  `pending-signature`. That signature is literally the React half's effect
  dependency, so a signature that does not move is an effect that does not
  re-run — no timer re-armed, no render forced, on any frame. It is also
  the exact assertion that a *relative* deadline would fail: recompute
  `now + timeout-ms` per step and the signature moves every frame, the
  effect re-runs every frame, and a child's retention is extended past its
  terminal bound.

  The DOM half is the rAF/interval census across a real transition, which
  is the only place \"we never asked for a frame\" can actually be read.

  ## Why the teardown row needs a real DOM, and how a timer is attributed

  The timers are the React half's — armed inside `useEffect`, cleared by
  the function it returns — so nothing about them is observable without a
  mounted component. The row therefore states a skip in `:node-test`, as
  every DOM claim in this package does.

  Attribution is by DELAY, and deliberately: the row retains for four
  seconds, so a timer armed for `>= 1000 ms` inside its window is
  presence's retention timer and nothing else's. React's own scheduler
  does not arm anything of that order, and the alternative — subtracting
  React's timers by counting them — would be a claim about React's
  internals that a version bump could silently invalidate. The row also
  asserts that **exactly one** such timer was armed before it asserts that
  it was cleared, because \"every orphaned timer was cleared\" is
  vacuously true of a row where the exit never started.

  ## The controls, and what each of them reds on

  Every row was run against a deliberate break and the reds are quoted in
  the PR body:

  - re-entry ceasing to cancel (`impl.presence/step`'s `:unmounting`
    branch) reds the interruption row;
  - a relative rather than absolute deadline reds the frame-budget row on
    every frame of the window;
  - dropping `(js/clearTimeout expiry)` from the React half's cleanup reds
    the teardown row with the retention timer named."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.presence :as presence]
            [re-frame.hicasso.motion :as motion]
            [re-frame.test-support :as test-support]))

(def ^:private frame-kw ::frame)

;; Registered ABOVE `use-fixtures`: the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is evaluated, so a
;; registration written below it is erased before the first row runs.
(rf/reg-sub ::toasts (fn [db _] (:toasts db)))
(rf/reg-event ::seed (fn [_ [_ ts]] {:db {:toasts ts}}))
(rf/reg-event ::set (fn [{:keys [db]} [_ ts]] {:db (assoc db :toasts ts)}))

;; The UIx adapter, for the reason the package smoke gives: plain-atom has
;; no reactivity layer, so a subscription under it never notifies and the
;; DOM row's "the tray re-rendered" premise would pass by never firing.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The headless half — the machine, with no React and no clock
;; ---------------------------------------------------------------------------

(def ^:private retention-ms
  "The retention window the headless rows run on. Every instant below is
  supplied by the caller, so this is a duration in a value and never a
  wait."
  300)

(defn- toasts
  "The children the tray would hand presence for `ks`."
  [& ks]
  (mapv (fn [k] [:div.toast {:key k}]) ks))

(defn- painted
  "Two toasts, both settled — the state every headless row starts from."
  []
  (presence/settle (presence/step presence/initial (toasts :a :b) 0 retention-ms)))

(deftest an-exit-interrupted-by-re-entry-cancels-on-the-node-it-already-had
  (let [start   (painted)
        exiting (presence/step start (toasts :a) 1000 retention-ms)
        back    (presence/step exiting (toasts :a :b) 1100 retention-ms)]
    (testing "premise: :b is genuinely mid-exit, on a deadline, before it returns"
      (is (= {:a :present :b :unmounting} (presence/phases exiting)))
      (is (= 1300 (presence/next-deadline exiting))))

    (testing "the claim: it returns to :present rather than finishing and remounting"
      (is (= {:a :present :b :present} (presence/phases back)))
      (is (nil? (:deadline (get (:entries back) :b)))
          "a cancelled exit leaves no deadline behind"))

    (testing "and it returns to the SAME node — the key never left, and order is frozen"
      (is (= [:a :b] (:order back))
          "first-appearance order is frozen, so an exiting child cannot jump slot")
      (is (nil? (presence/next-deadline back))
          "nothing is outstanding, so the React half's effect arms no timer at all"))))

(deftest a-rapid-toggle-arms-one-deadline-per-exit-and-never-extends-a-live-one
  (let [start (painted)
        ;; out, back, out, and then two ordinary re-renders while the second
        ;; exit is in flight — the shape a user produces by clicking twice.
        s1    (presence/step start (toasts :a)    1000 retention-ms)
        s2    (presence/step s1    (toasts :a :b) 1050 retention-ms)
        s3    (presence/step s2    (toasts :a)    1100 retention-ms)
        s4    (presence/step s3    (toasts :a)    1150 retention-ms)
        s5    (presence/step s4    (toasts :a)    1200 retention-ms)
        seen  (mapv presence/next-deadline [s1 s2 s3 s4 s5])]
    (is (= [1300 nil 1400 1400 1400] seen)
        "one deadline per exit — and the second exit's is fixed at the instant it started")
    (is (= 2 (count (remove nil? (distinct seen))))
        "two exits, two deadlines: not one per render, and not one that keeps moving")
    (is (= 1400 (:deadline (get (:entries s5) :b)))
        "the deadline is the absolute instant :b started exiting, 50 ms of re-renders later")))

(deftest re-deriving-mid-flight-is-a-no-op-on-every-frame-of-the-window
  (let [exiting (presence/step (painted) (toasts :a) 1000 retention-ms)
        frames  (vec (range 1016 1300 16))
        redone  (rest (reductions (fn [s now] (presence/step s (toasts :a) now retention-ms))
                                  exiting
                                  frames))]
    (testing "premise: the window really is walked frame by frame at 60 Hz"
      (is (= 18 (count frames)))
      (is (= (count frames) (count redone))))

    (testing "THE FRAME BUDGET: every frame of the retention window costs nothing"
      (is (= #{exiting} (set redone))
          "the machine is idempotent, so re-deriving mid-flight changes no value")
      (is (= #{(presence/pending-signature exiting)}
             (set (map presence/pending-signature redone)))
          "the effect's own dependency never moves: no timer re-armed, no render forced")
      (is (= #{1300} (set (map presence/next-deadline redone)))
          "an absolute deadline cannot be pushed forward by the passage of frames"))))

;; ---------------------------------------------------------------------------
;; The DOM half — the React component, its timers, and its silence
;; ---------------------------------------------------------------------------

(def ^:private dom-retention-ms
  "Four seconds, so that the retention timer is unmistakably still pending
  when the row unmounts mid-transition, and so that `>= 1000 ms` cleanly
  separates it from anything React arms."
  4000)

(def ^:private retention-floor 1000)

(h/defview tray
  "A toast tray: presence over the keyed children a subscription supplies,
  each declaring its own exit appearance as data."
  [_]
  [motion/presence {:timeout-ms dom-retention-ms}
   (for [t (h/sub [::toasts])]
     [:div.toast {:key            t
                  ::h/unmounting {:class       "toast toast--exit"
                                  :inert       true
                                  :aria-hidden true}}
      (name t)])])

(defn- skip!
  [why]
  (is true (str "a presence timer claim needs a real React DOM — " why)))

(defn- install-clock-ledger!
  "Instrument the global timer surface and answer `{:log :restore!}`.

  A ledger rather than a counter: the question is not how many timers were
  armed but whether any armed timer is still outstanding, so each arm is
  recorded with its id and its delay and both `clearTimeout` and the
  callback itself write back. `requestAnimationFrame` and `setInterval` are
  counted and delegated untouched — the module must never reach them, and
  counting is how that is read."
  []
  (let [orig-set   (.-setTimeout js/globalThis)
        orig-clear (.-clearTimeout js/globalThis)
        orig-int   (.-setInterval js/globalThis)
        orig-raf   (.-requestAnimationFrame js/globalThis)
        !log       (atom {:armed [] :cleared #{} :fired #{} :intervals 0 :rafs 0})]
    (set! (.-setTimeout js/globalThis)
          (fn [f & more]
            (let [!id     (volatile! nil)
                  wrapped (fn [& args]
                            (swap! !log update :fired conj @!id)
                            (apply f args))
                  id      (.apply orig-set js/globalThis (to-array (cons wrapped more)))]
              (vreset! !id id)
              (swap! !log update :armed conj {:id id :delay (first more)})
              id)))
    (set! (.-clearTimeout js/globalThis)
          (fn [id]
            (swap! !log update :cleared conj id)
            (.call orig-clear js/globalThis id)))
    (when orig-int
      (set! (.-setInterval js/globalThis)
            (fn [& args]
              (swap! !log update :intervals inc)
              (.apply orig-int js/globalThis (to-array args)))))
    (when orig-raf
      (set! (.-requestAnimationFrame js/globalThis)
            (fn [& args]
              (swap! !log update :rafs inc)
              (.apply orig-raf js/globalThis (to-array args)))))
    {:log      !log
     :restore! (fn []
                 (set! (.-setTimeout js/globalThis) orig-set)
                 (set! (.-clearTimeout js/globalThis) orig-clear)
                 (when orig-int (set! (.-setInterval js/globalThis) orig-int))
                 (when orig-raf (set! (.-requestAnimationFrame js/globalThis) orig-raf))
                 nil)}))

(defn- retention-timer-ids
  "The ids of the timers this row attributes to presence's retention — the
  long ones. See the namespace docstring for why the discriminator is the
  delay."
  [log]
  (into #{} (comp (filter #(>= (or (:delay %) 0) retention-floor)) (map :id))
        (:armed log)))

(defn- orphans
  "Armed, never cleared, never fired."
  [log]
  (set/difference (retention-timer-ids log) (:cleared log) (:fired log)))

(defn- toast-nodes [container] (.querySelectorAll container ".toast"))

(deftest an-unmount-mid-transition-clears-the-timer-it-armed
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM, so no effect runs and no timer is armed")
    (let [{:keys [log restore!]} (install-clock-ledger!)]
      (try
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (rf/make-frame {:id frame-kw})
        (rf/with-frame frame-kw (rf/dispatch-sync [::seed [:a :b]]))
        (let [container (mount/fresh-container!)
              handle    (mount/root! container frame-kw [tray])]
          (try
            (mount/settle!)
            (is (= 2 (.-length (toast-nodes container)))
                "premise: both toasts painted before anything leaves")
            (let [frames-before (select-keys @log [:rafs :intervals])]

              (rf/with-frame frame-kw (rf/dispatch-sync [::set [:a]]))
              (mount/settle!)

              (testing "RETENTION: the node outlives the data, wearing the exit phase"
                (is (= 2 (.-length (toast-nodes container)))
                    "the dismissed toast is still painted — that is the whole module")
                (let [exiting (.querySelector container ".toast--exit")]
                  (is (some? exiting)
                      "the author's own `::h/unmounting` class, merged onto the author's own node")
                  (when exiting
                    (is (= "true" (.getAttribute exiting "aria-hidden"))
                        "and the a11y attributes that belong in that phase, in the same map"))))

              (testing "premise: exactly one retention timer is armed and outstanding"
                (is (= 1 (count (retention-timer-ids @log))))
                (is (= 1 (count (orphans @log)))
                    "it must be outstanding at this point, or the next assertion means nothing"))

              (testing "THE FRAME BUDGET, in the DOM: the transition asked for no frames"
                (is (= frames-before (select-keys @log [:rafs :intervals]))
                    "zero requestAnimationFrame and zero setInterval across the whole window"))

              (mount/unmount! handle)

              (testing "TEARDOWN: an unmount mid-transition leaves no timer behind"
                (is (= #{} (orphans @log))
                    "every retention timer armed was cleared by the effect's own cleanup")
                (is (= 0 (.-length (toast-nodes container)))
                    "and the retained node went with the root rather than outliving it")))
            (finally (mount/release! handle))))
        (finally (restore!))))))
