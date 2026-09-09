(ns re-frame.bench.hicasso.ledger-frame-dom-cljs-test
  "THE LEDGER FRAME CLOCK'S OWN SELF-TEST (rf2-xc0bw, deliverable 2).

  [[re-frame.bench.hicasso.ledger-frame-clock-app]] publishes a
  distribution over the intervals between consecutive frames while the
  ledger's virtualized list is scrolled. This file asks whether it does,
  and it is a CORRECTNESS test of the instrument rather than a
  benchmark: **no assertion here is a line on a latency**, no figure is
  published, and nothing is compared to `U1`-`U4`.

  ## Why this file exists at all

  The driver landed without ever having been executed. Every structural
  claim it makes is carried by a check the RUN performs — [[rf.bench.hicasso.ledger-frame-clock-app/boot!]]'s
  two clamp refusals, [[rf.bench.hicasso.ledger-frame-clock-app/prepare!]]'s reset-settled check, and both
  halves of [[rf.bench.hicasso.ledger-frame-clock-app/advance-discrimination!]] — and until this file none
  of them had fired once. A check that has never fired is a check nobody
  has seen discriminate, and the sibling instrument's own history is the
  argument: writing the self-test beside `slice-broad-clock-app` is what
  found that its two arms were not comparable.

  ## The three claims this file exists to discriminate

  They fail in different directions and no one row catches two of them.

  **Are these frames under a REAL scroll?** The instrument's whole claim
  is that its intervals are frames in which the ledger's virtualizer did
  a frame's worth of work. Two failures leave that claim standing while
  making it false.

  - *A check that cannot refuse.* If an advance were reported on a page
    nothing scrolled, every measured run would verify and the tally would
    be decorative. [[the-window-check-refuses-a-page-that-moved-when-it-
    should-not]] takes a REAL run whose page genuinely moves and requires
    the check to refuse it, which is the direction `:idle-frames` cannot
    exercise on itself.
  - *A scroll that never lands.* If the notification did not reach the
    vendor, every arm would publish this box's own frame grid.
    `ledger.virtualized-dom-cljs-test` records two runs of its own that
    met exactly this — `scrollTop` set, the `scrollTop` assertion
    passing, and the window unmoved — and
    [[a-gesture-the-virtualizer-never-hears-does-not-verify]] reproduces
    it deliberately, with the vendor cut off from its own `scroll` events
    in the capture phase.

  **Is the estimator FRAME INTERVALS?**
  [[a-blocked-frame-cannot-be-followed-sooner-than-the-block]] is the
  discriminating row and is worth reading before the others. It is
  described below.

  **Does the arithmetic downstream of the reading say what it claims?**
  The floor rule, the per-round minimum and the two descriptive counts
  are pinned on synthetic readings, where they cannot flake.

  ## The one inequality that carries the whole claim

  `:ctl-blocked` blocks the main thread for [[rf.bench.hicasso.ledger-frame-clock-app/blocked-ms]] INSIDE
  every frame, after that frame's scroll has been delivered. The frame
  that follows cannot begin until the block ends, so every interval the
  arm produces satisfies

      interval >= blocked-ms

  and the assertion is that floor over the run's MINIMUM. It is exact
  rather than approximate — every term is a reading from one monotone
  clock — and it is flake-proof in the direction a shared runner can move
  it, because load can only make an interval longer. A window-length
  estimator, or a reading that had drifted off the frame grid onto task
  boundaries, would not clear it.

  Its anti-vacuity half is the same row's second assertion: `:idle-frames`
  does no application work at all, and its smallest interval must sit
  BELOW the floor. Without that, an instrument that reported a constant
  would pass the inequality and mean nothing.

  ## What is NOT asserted, deliberately

  A latency. Nothing here reads `:scroll`'s distribution against anything,
  because that reading is `U4`'s and belongs to a pinned quiet-box window
  rather than to a shared PR runner. The positive control's own VERDICT is
  likewise not adjudicated over a real run — [[rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor]]'s
  arithmetic is pinned below on synthetic readings, where a slow box
  cannot reach it, and the verdict itself belongs to the run that takes
  the window.

  ## Runtime

  The DOM rows need a real browser and carry the `-dom-cljs-test` suffix
  so `:browser-test` runs them; each degrades to a stated skip under
  `:node-test`, which is the posture every other `*-dom` suite in this
  tree keeps. The pure rows run on both."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.ledger-frame-clock-app :as rf.bench.hicasso.ledger-frame-clock-app]
            [re-frame.hicasso.examples.ledger.vendor :as rf.hicasso.examples.ledger.vendor]
            [re-frame.hicasso.examples.ledger.views :as rf.hicasso.examples.ledger.views]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; The MAP shape, because every DOM row is `async`: `cljs.test` refuses
     ;; an async test under a fn-form fixture and ABORTS THE WHOLE NAMESPACE
     ;; — silently as far as the row is concerned, and taking every suite
     ;; scheduled after it in the same bundle with it. Both sibling suites
     ;; and `examples.ledger.virtualized-dom-cljs-test` carry the same keys.
     :async?        true
     ;; React's `act` queue is not the browser's scheduler, and every
     ;; reading this instrument takes is a real `requestAnimationFrame`
     ;; outside it. `rf.bench.hicasso.ledger-frame-clock-app/-main` leaves that environment before it boots;
     ;; the suite that drives the same code has to leave it too.
     ;;
     ;; NO `register!` CALL, unlike the two slice suites: `examples.ledger.
     ;; app`'s own docstring records that this application has one root, one
     ;; frame and NO ROUTE, so there is no late registration for the reset
     ;; to have captured a baseline in front of.
     :init-fn       rf.bench.hicasso.lane/leave-act-environment!}))

(def ^:private off-browser
  "no DOM on this runtime — every claim below is a real mount, a real
  scroll and a real run of frames, and :browser-test is where they are
  asked")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a mounted React root and a real frame grid are the whole
                 subject — " why)))

(defn- fail-async [done]
  (fn [e]
    (is false (str "the instrument threw rather than answering a reading: " e))
    (done)))

;; ---------------------------------------------------------------------------
;; One mounted ledger, torn down again
;; ---------------------------------------------------------------------------

(defonce ^:private !frame-n (atom 0))

(defn- fresh-frame-id
  "A FRESH frame id per mount. A suite that mounts several times in one
  process would otherwise hand `h/mount!` a frame that already holds the
  previous row's `app-db`, and the seed the ledger's geometry checks are
  derived against would be whatever the last row left there."
  []
  (keyword "re-frame.bench.hicasso.ledger-frame-dom-cljs-test"
           (str "frame-" (swap! !frame-n inc))))

(defn- with-mounted-ledger
  "Mount the ledger through the instrument's own [[rf.bench.hicasso.ledger-frame-clock-app/boot!]], run
  `f` — which answers a promise — and tear the root down afterwards
  whatever happened.

  `boot!` is used rather than a mount rolled here on purpose: its two
  clamp refusals and its viewport/spacer lookup are part of what this
  file is asking about, so every row below has already exercised them by
  the time its own assertions run."
  [f]
  (let [container (rf.bench.hicasso.lane/fresh-container!)]
    (-> (rf.bench.hicasso.ledger-frame-clock-app/boot! container (fresh-frame-id))
        (.then (fn [_] (f)))
        (.then (fn [v] (rf.bench.hicasso.ledger-frame-clock-app/teardown!) v)
               (fn [e] (rf.bench.hicasso.ledger-frame-clock-app/teardown!) (throw e))))))

;; ---------------------------------------------------------------------------
;; The sabotage — the virtualizer cut off from its own scroll events
;; ---------------------------------------------------------------------------

(defn- with-scroll-suppressed
  "Run `f` — which answers a promise — with `scroll` stopped in the
  CAPTURE phase at `js/document`, and restore the document afterwards
  whatever happened.

  WHY THE CAPTURE PHASE AT THE DOCUMENT. `examples.ledger.vendor` adds a
  plain DOM listener to the viewport node it owns, in a `useEffect` and
  deliberately not through React's `onScroll`. A capture-phase listener
  at `js/document` runs BEFORE the target's own listeners on every
  propagation path, so `stopPropagation` here means the event never
  reaches the vendor: `set-scroll!` does not run, its `useState` keeps
  the offset it had, and no new window is committed.

  WHAT IT DOES NOT SUPPRESS, and that is the point: the `scrollTop`
  WRITE. `stopPropagation` is not `preventDefault` and touches no
  property, so the offset still moves and
  [[rf.bench.hicasso.ledger-frame-clock-app/prepare!]]'s `scrollTop` assertion still passes. That is
  precisely the state `virtualized-dom-cljs-test` hit twice — the
  instrument's own scroll assertion green and the rendered window
  standing still — and it is the state a check over `scrollTop` alone
  would have verified."
  [f]
  (let [stop (fn [e] (.stopPropagation e))
        off! (fn [] (.removeEventListener js/document "scroll" stop true))]
    (.addEventListener js/document "scroll" stop true)
    (-> (js/Promise.resolve (f))
        (.then (fn [v] (off!) v)
               (fn [e] (off!) (throw e))))))

;; ---------------------------------------------------------------------------
;; A smaller schedule, held for the whole of an ASYNCHRONOUS run
;; ---------------------------------------------------------------------------

(defn- with-schedule
  "Run `f` — which answers a promise — with the driver's three schedule
  knobs replaced, and restore them afterwards whatever happened.

  **`with-redefs` CANNOT BE USED HERE, and the failure is silent.** It
  restores when its body RETURNS, and the body of every row below returns
  a promise immediately — so the knobs would be back at the module's own
  values long before the run that is supposed to be using them reads
  them. [[rf.bench.hicasso.ledger-frame-clock-app/run-schedule!]] takes `rf.bench.hicasso.lane/visit-plan`'s three arguments
  from these vars synchronously but [[rf.bench.hicasso.ledger-frame-clock-app/measure-one!]] reads
  `frames-per-run` inside a `.then`, so what a `with-redefs` here would
  actually produce is a TINY PLAN RUNNING FULL-LENGTH VISITS: no error,
  no warning, and a row whose arithmetic quietly stops describing the run
  it just took.

  The sibling suites never meet this because `rf.bench.hicasso.lane/rounds-async!` takes
  its schedule as ARGUMENTS; this driver's loop reads the vars, which is
  the price of banking a vector per visit."
  [{:keys [sampling rounds frames]} f]
  (let [sampling0 rf.bench.hicasso.ledger-frame-clock-app/sampling
        rounds0   rf.bench.hicasso.ledger-frame-clock-app/rounds
        frames0   rf.bench.hicasso.ledger-frame-clock-app/frames-per-run
        restore!  (fn []
                    (set! rf.bench.hicasso.ledger-frame-clock-app/sampling sampling0)
                    (set! rf.bench.hicasso.ledger-frame-clock-app/rounds rounds0)
                    (set! rf.bench.hicasso.ledger-frame-clock-app/frames-per-run frames0))]
    (set! rf.bench.hicasso.ledger-frame-clock-app/sampling sampling)
    (set! rf.bench.hicasso.ledger-frame-clock-app/rounds rounds)
    (set! rf.bench.hicasso.ledger-frame-clock-app/frames-per-run frames)
    (-> (js/Promise.resolve (f))
        (.then (fn [v] (restore!) v)
               (fn [e] (restore!) (throw e))))))

;; ---------------------------------------------------------------------------
;; The geometry, as the application states it
;; ---------------------------------------------------------------------------

(def ^:private geom
  "`rf.hicasso.examples.ledger.views/ledger`'s own geometry and the run's own model size, assembled
  the way [[rf.bench.hicasso.ledger-frame-clock-app/boot!]] assembles it. Derived rather than typed, so a
  change to the screen's row height cannot leave this file asserting
  against a page that no longer exists."
  {:row-height      rf.hicasso.examples.ledger.views/row-height
   :viewport-height rf.hicasso.examples.ledger.views/viewport-height
   :overscan        rf.hicasso.examples.ledger.views/overscan
   :total           rf.bench.hicasso.ledger-frame-clock-app/total})

(defn- window-at [top] (rf.hicasso.examples.ledger.vendor/window-from top geom))

(defn- start-top [] (* rf.bench.hicasso.ledger-frame-clock-app/start-row rf.hicasso.examples.ledger.views/row-height))

;; ---------------------------------------------------------------------------
;; The mount
;; ---------------------------------------------------------------------------

(deftest the-instrument-mounts-the-ledger-and-finds-its-scroll-viewport
  (testing "The population is `examples.ledger` on its own root, reached
           through `h/mount!` with the application's own initial events.
           No `.ledger-viewport` means no gesture and no
           `.ledger-spacer` means no observation, so every row below
           would be meaningless."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger
              (fn []
                (is (some? (.querySelector js/document ".ledger-viewport"))
                    "the scroll container the gesture is delivered to is on the page")
                (is (some? (.querySelector js/document ".ledger-spacer"))
                    "as is the row host the observation is read off")
                (is (number? ((rf.bench.hicasso.ledger-frame-clock-app/observer)))
                    "and the observation answers a MODEL INDEX rather than nil —
                     a spacer holding no row, or an aria-rowindex that is not a
                     number, is a frame whose reading means nothing")
                (js/Promise.resolve nil)))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest tearing-down-takes-the-root-off-the-page
  (testing "So a suite that mounts several times is not measuring several
           ledgers, each still holding a scroll listener on a node the
           next row is about to query."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger (fn [] (js/Promise.resolve nil)))
            (.then (fn [_]
                     (is (nil? (.querySelector js/document ".ledger-viewport"))
                         "the viewport is gone once the root is unmounted")
                     (done))
                   (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The reset every visit starts from
;; ---------------------------------------------------------------------------

(deftest the-reset-lands-the-window-where-the-geometry-says
  (testing "[[rf.bench.hicasso.ledger-frame-clock-app/prepare!]]'s fourth step, exercised. A visit that
           began on a stale window would still VERIFY — the scrolling
           arms advance either way — while its early frames were a
           gesture catching up rather than one being held, and nothing
           downstream would say so."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger
              (fn []
                (.then (rf.bench.hicasso.ledger-frame-clock-app/prepare!)
                       (fn [_]
                         (let [[from _] (window-at (start-top))]
                           (is (= from ((rf.bench.hicasso.ledger-frame-clock-app/observer)))
                               "the rendered window's first row is the row the
                                geometry puts at that offset")
                           (is (pos? from)
                               "and it is clear of window-from's (max 0 …) clamp,
                                so the window can move on the run's first frame"))))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest a-reset-the-virtualizer-never-hears-is-refused
  (testing "THE SABOTAGE, on the check that runs at the head of every
           visit. The offset still moves and the `scrollTop` assertion
           still passes; only the notification is missing. `prepare!`
           must REJECT rather than hand a run a page it never checked."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger
              (fn []
                (with-scroll-suppressed
                  (fn []
                    (.then (rf.bench.hicasso.ledger-frame-clock-app/prepare!)
                           (fn [_]
                             (is false
                                 "prepare! accepted a reset the virtualizer never saw"))
                           (fn [e]
                             (is (= :re-frame.bench.hicasso.ledger-frame-clock-app/reset-not-settled
                                    (:rf.error/id (ex-data e)))
                                 "and it says WHICH check refused, rather than failing
                                  three assertions away in whichever row read the DOM
                                  first")
                             (is (not= (:want-first (ex-data e)) (:saw-first (ex-data e)))
                                 "naming the row the geometry wanted and the row the
                                  page is actually showing")))))))
            (.then (fn [_] (done)) (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The verification, taken on real runs in both directions
;; ---------------------------------------------------------------------------

(deftest the-built-in-discrimination-runs-both-ways-before-anything-is-measured
  (testing "[[rf.bench.hicasso.ledger-frame-clock-app/advance-discrimination!]] takes one idle run and one
           scrolling run on the real page and requires the first to
           refuse an advance and the second to produce one. It REJECTS if
           either went the wrong way, so this row resolving at all is the
           proof.

           It also has to leave the tally as it found it: its two runs are
           not visits, and a denominator the arms share with a control is
           one a reader has to reconstruct."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger
              (fn []
                (.then (rf.bench.hicasso.ledger-frame-clock-app/advance-discrimination!)
                       (fn [_]
                         (is (= {:writes 0 :unverified 0} (rf.bench.hicasso.ledger-frame-clock-app/verification))
                             "the tally is reset behind it, so the first measured
                              visit is the first write")))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest a-gesture-the-virtualizer-never-hears-does-not-verify
  (testing "THE ROW THIS FILE EXISTS FOR. The page is prepared normally,
           and only THEN is the vendor cut off — so `prepare!`'s checks
           all pass and the failure is confined to the run itself. Thirty
           frames each write `scrollTop` and dispatch a real `scroll`
           event, none of which reaches the listener, and the rendered
           window is where it started.

           A check over `scrollTop` would have verified this run. The
           observation is taken off the MIRROR ONLY A COMMIT WRITES, so
           it cannot."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger
              (fn []
                (.then
                  (rf.bench.hicasso.ledger-frame-clock-app/prepare!)
                  (fn [_]
                    (with-scroll-suppressed
                      (fn []
                        (let [observe! (rf.bench.hicasso.ledger-frame-clock-app/observer)
                              work!    ((:per-frame (second rf.bench.hicasso.ledger-frame-clock-app/arms)))]
                          (.then (rf.bench.hicasso.ledger-frame-clock-app/frames! {:frames     rf.bench.hicasso.ledger-frame-clock-app/frames-per-run
                                                 :observe!   observe!
                                                 :per-frame! work!})
                                 (fn [{:keys [at seen]}]
                                   (let [v (rf.bench.hicasso.ledger-frame-clock-app/verify :scroll true seen)]
                                     (is (not (:verified? v))
                                         "the arm REFUSES a run in which the
                                          virtualizer never saw the gesture")
                                     (is (zero? (:unobserved v))
                                         "and it refuses for the RIGHT reason: every
                                          frame's window was readable, it simply did
                                          not move")
                                     (is (= 0 (:rows-gained v))
                                         "the rendered window is exactly where it
                                          started after the whole gesture")
                                     (is (= (dec rf.bench.hicasso.ledger-frame-clock-app/frames-per-run)
                                            (count (rf.bench.hicasso.ledger-frame-clock-app/intervals at)))
                                         "and the run still produced its K-1 readings,
                                          which is why a refusal is the only thing
                                          standing between this and a published
                                          distribution over the box's frame grid")))))))))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest the-window-check-refuses-a-page-that-moved-when-it-should-not
  (testing "The INVERTED direction, on a real run. `:idle-frames` asserts
           its window stands still, and a check that could not refuse
           movement would make that assertion — and therefore the whole
           standing negative control — vacuous.

           `:idle-frames` cannot exercise this on itself, because its own
           page never moves. So the run below does the scrolling arm's
           work while DECLARING the floor arm's expectation, which is the
           one combination the schedule never produces and the only one
           that asks the question."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger
              (fn []
                (.then (rf.bench.hicasso.ledger-frame-clock-app/measure-one! {:id        :probe-moved-while-idle
                                            :per-frame rf.bench.hicasso.ledger-frame-clock-app/scroller
                                            :advance?  false})
                       (fn [_]
                         (is (= {:writes 1 :unverified 1} (rf.bench.hicasso.ledger-frame-clock-app/verification))
                             "a run whose window advanced against an arm that
                              predicted stillness is REFUSED")))))
            (.then (fn [_] (done)) (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The estimator
;; ---------------------------------------------------------------------------

(deftest a-blocked-frame-cannot-be-followed-sooner-than-the-block
  (testing "THE DISCRIMINATING ROW. `:ctl-blocked` occupies
           `blocked-ms` of every frame AFTER that frame's scroll has been
           delivered, so the next frame cannot begin until the block ends
           and every interval it produces is at or above the injection.

           The bound is exact in the reading domain rather than a band —
           both terms are readings from one monotone clock — and load can
           only make an interval longer, so a shared runner cannot make
           this red. A distribution over window lengths, or one that had
           drifted onto task boundaries, would not clear it.

           The second assertion is the anti-vacuity half: `:idle-frames`
           does no application work at all and its smallest interval must
           sit BELOW the floor, so an instrument reporting a constant
           fails rather than passes."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-ledger
              (fn []
                (.then
                  (rf.bench.hicasso.ledger-frame-clock-app/measure-one! {:id        :ctl-blocked
                                       :per-frame rf.bench.hicasso.ledger-frame-clock-app/blocked-scroller
                                       :advance?  true})
                  (fn [blocked]
                    (.then
                      (rf.bench.hicasso.ledger-frame-clock-app/measure-one! {:id        :idle-frames
                                           :per-frame rf.bench.hicasso.ledger-frame-clock-app/idle-frames
                                           :advance?  false})
                      (fn [idle]
                        (is (= {:writes 2 :unverified 0} (rf.bench.hicasso.ledger-frame-clock-app/verification))
                            "both runs verified — the blocked one advanced its window
                             and the idle one did not")
                        (is (>= (:min (rf.bench.hicasso.lane/summarise blocked)) rf.bench.hicasso.ledger-frame-clock-app/blocked-ms)
                            (str "every one of the blocked arm's "
                                 (count blocked) " intervals is at or above the "
                                 rf.bench.hicasso.ledger-frame-clock-app/blocked-ms "ms it spent blocking"))
                        (is (< (:min (rf.bench.hicasso.lane/summarise idle)) rf.bench.hicasso.ledger-frame-clock-app/blocked-ms)
                            "and the arm that injected nothing produced an interval
                             below it, so the floor is not cleared by any reading
                             whatever")))))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest the-whole-schedule-runs-and-every-run-verifies
  (testing "[[rf.bench.hicasso.ledger-frame-clock-app/run-schedule!]] walks `rf.bench.hicasso.lane/visit-plan` and banks a
           VECTOR per visit — the one thing the lane's own loops cannot
           do — while leaving the arm ordering, the warm-up boundary and
           the round boundary over there.

           The schedule is TINY here and the module's own is not: this row
           asks whether the loop runs and books its visits correctly, and
           a run that reads a figure wants `rf.bench.hicasso.ledger-frame-clock-app/sampling`,
           `rf.bench.hicasso.ledger-frame-clock-app/rounds` and `rf.bench.hicasso.ledger-frame-clock-app/frames-per-run`."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (let [sampling {:warmup 1 :samples 1}
              rounds   1
              frames   4]
          (-> (with-schedule
                {:sampling sampling :rounds rounds :frames frames}
                (fn []
                  (with-mounted-ledger
                    (fn []
                      (.then (rf.bench.hicasso.ledger-frame-clock-app/run-schedule!)
                             (fn [{:keys [readings samples]}]
                               (is (= (* rounds (:samples sampling) (count rf.bench.hicasso.ledger-frame-clock-app/arms))
                                      (count samples))
                                   "one guard sample per MEASURED visit — that visit's
                                    median interval, not one per frame")
                               (is (= rounds (count readings)))
                               (is (every? (fn [round] (= (count rf.bench.hicasso.ledger-frame-clock-app/arms) (count round)))
                                           readings)
                                   "and every arm has a reading vector in every round")
                               (doseq [{:keys [id]} rf.bench.hicasso.ledger-frame-clock-app/arms]
                                 (is (= (* rounds (:samples sampling) (dec frames))
                                        (count (get (first readings) id)))
                                     (str id " banked K-1 intervals for each of its "
                                          "measured visits — which is also what proves "
                                          "the shortened schedule was still in force "
                                          "when the visits actually ran")))
                               (is (= {:writes     (* rounds
                                                      (+ (:warmup sampling)
                                                         (:samples sampling))
                                                      (count rf.bench.hicasso.ledger-frame-clock-app/arms))
                                       :unverified 0}
                                      (rf.bench.hicasso.ledger-frame-clock-app/verification))
                                   "0 unverified of M — every run, WARM-UP INCLUDED,
                                    went the way its own arm predicted")))))))
              (.then (fn [_] (done)) (fail-async done))))))))

;; ---------------------------------------------------------------------------
;; The reading's arithmetic, where it cannot flake
;; ---------------------------------------------------------------------------

(deftest the-estimator-is-the-gaps-between-frames-and-there-is-one-fewer
  (testing "`K` timestamps give `K-1` intervals. A frame run is not a
           window: there is no `t0` before it and no paint after it, and
           its total length is not a figure this instrument publishes."
    (is (= [2.0 3.0 4.0] (rf.bench.hicasso.ledger-frame-clock-app/intervals [1.0 3.0 6.0 10.0]))
        "each reading is the distance to the NEXT frame")
    (is (= 29 (count (rf.bench.hicasso.ledger-frame-clock-app/intervals (vec (range 30)))))
        "so a thirty-frame run banks twenty-nine readings")
    (is (= [] (rf.bench.hicasso.ledger-frame-clock-app/intervals [1.0]))
        "and a single frame is no reading at all")))

(deftest the-window-check-is-a-two-valued-question-asked-in-both-directions
  (testing "[[rf.bench.hicasso.ledger-frame-clock-app/verify]] compares the first frame's observed model
           index against the last's and asks whether that matches what
           the ARM predicted. Both directions bank into one tally: a
           floor run whose window advanced is exactly as damning as a
           scroll run whose window did not."
    (is (:verified? (rf.bench.hicasso.ledger-frame-clock-app/verify :scroll true [100 104 108]))
        "a scrolling arm whose window moved forward verifies")
    (is (not (:verified? (rf.bench.hicasso.ledger-frame-clock-app/verify :scroll true [100 100 100])))
        "and one whose window stood still is REFUSED")
    (is (:verified? (rf.bench.hicasso.ledger-frame-clock-app/verify :idle-frames false [100 100 100]))
        "a floor arm whose window stood still verifies")
    (is (not (:verified? (rf.bench.hicasso.ledger-frame-clock-app/verify :idle-frames false [100 104 108])))
        "and one whose window MOVED is refused — without this direction the
         observation could report movement on a page nothing scrolled and
         every reading would be verified by a check that cannot fail")
    (let [v (rf.bench.hicasso.ledger-frame-clock-app/verify :scroll true [100 nil 108])]
      (is (not (:verified? v))
          "a frame whose window could not be read is a frame whose reading
           means nothing")
      (is (= 1 (:unobserved v))
          "and the count is carried, so an operator is sent to the spacer
           rather than to the gesture"))
    (is (= {:expected :advance :observed :no-advance}
           (select-keys (rf.bench.hicasso.ledger-frame-clock-app/verify :scroll true [100 100]) [:expected :observed]))
        "the refusal names both sides rather than only failing")))

(deftest the-descriptive-counts-say-how-far-and-how-often
  (testing "`:advance` is published over every visit and adjudicates
           nothing. `rows-gained` is how far the window travelled;
           `frames-changed` is how many of the run's transitions carried a
           new window, which is a finding about the SUBJECT — a run that
           advanced on a quarter of its frames advanced for real and was
           dropping frames."
    (is (true? (rf.bench.hicasso.ledger-frame-clock-app/advanced? [100 108])))
    (is (false? (rf.bench.hicasso.ledger-frame-clock-app/advanced? [108 100])) "backwards is not an advance")
    (is (false? (rf.bench.hicasso.ledger-frame-clock-app/advanced? [100 nil])) "and an unreadable end is not one either")
    (is (= 8 (rf.bench.hicasso.ledger-frame-clock-app/rows-gained [100 104 108])))
    (is (nil? (rf.bench.hicasso.ledger-frame-clock-app/rows-gained [nil 108])) "nil rather than a fabricated zero")
    (is (= 2 (rf.bench.hicasso.ledger-frame-clock-app/frames-changed [1 1 2 2 3]))
        "two of the four transitions carried a new window")
    (is (= 0 (rf.bench.hicasso.ledger-frame-clock-app/frames-changed [1 1 1]))
        "a run the vendor never updated changed on none of them")))

;; ---------------------------------------------------------------------------
;; The control's rule, pinned on synthetic readings
;; ---------------------------------------------------------------------------

(deftest the-control-predicts-a-floor-and-every-round-must-clear-it
  (testing "[[rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor]] adjudicates round by round and
           not in aggregate, for the reason `rf.bench.hicasso.lane/control-verdict-strict`
           gives about its own band: a cross-round minimum cannot tell a
           control that held every round from one that held on average."
    (let [v (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0 [51.0 52.5 50.0] 16.7)]
      (is (:ok? v) "every round's minimum sits at or above the prediction")
      (is (= :every-round-floor (:rule v)))
      (is (empty? (:below v)))
      (is (:stated? v))
      (is (= 3 (:n (:measured v))) "and the per-round figures are summarised, not pooled")
      (is (= [51.0 52.5 50.0] (:per-round v))
          "carried into the record so a later reader can re-adjudicate WITHOUT
           re-running the window"))))

(deftest the-control-names-the-rounds-that-fell-below-the-floor
  (testing "An operator told only `FAILED` goes looking at the arms. A
           frame whose main thread was blocked for the whole of the
           prediction cannot be followed sooner than that, so a round
           below the floor means the instrument is not reading the frames
           it thinks it is."
    (let [v (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0 [51.0 49.0 40.0] 16.7)]
      (is (not (:ok? v)))
      (is (= [2 3] (mapv :round (:below v)))
          "each failing round is NAMED, one-based, in schedule order")
      (is (= [1.0 10.0] (mapv :short-by (:below v)))
          "and by how much it missed")
      (is (:stated? v) "the prediction itself was stated — this is a real failure"))))

(deftest the-control-refuses-a-prediction-that-is-not-stated
  (testing "Anti-vacuity, carried over from `rf.bench.hicasso.lane/control-verdict-strict`
           rather than reinvented. A floor of zero or less is cleared by
           any reading whatever, and a control with no rounds is the same
           thing said with no data. A walk profile once shipped a control
           whose own prediction had gone vacuous and reported that it saw
           what it never predicted."
    (let [v (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 0.0 [51.0 52.0] 16.7)]
      (is (not (:stated? v)))
      (is (not (:ok? v)) "a floor nothing can fall below is not a control that passed")
      (is (empty? (:below v)) "and no round is blamed for an unstated prediction"))
    (let [v (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0 [] 16.7)]
      (is (not (:stated? v)))
      (is (not (:ok? v)) "a control with no data is not a control that passed"))))

(deftest the-floor-is-reported-against-the-boxs-own-frame-grid-as-context
  (testing "`:versus-floor` says how many of this box's frames the
           injection spans, which a reader needs to know whether the floor
           could have been cleared without it. NOTHING is adjudicated
           against it — `:ok?` reads the same with it absent."
    (is (= 3.0 (:versus-floor (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0 [51.0] 16.6667)))
        "fifty milliseconds is three frames at 60 Hz")
    (is (nil? (:versus-floor (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0 [51.0] nil)))
        "and a run with no floor arm reports no ratio rather than a fabricated one")
    (is (= (:ok? (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0 [51.0] 16.6667))
           (:ok? (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0 [51.0] nil)))
        "the verdict does not move with the context")))

(deftest the-adjudicated-figure-is-a-round-minimum-and-not-a-median
  (testing "[[rf.bench.hicasso.ledger-frame-clock-app/control-per-round]] takes the SMALLEST interval each
           round produced, because a floor is a claim about the smallest
           reading. A median above the floor with a minimum below it is a
           control that failed, and an aggregate reporting the median
           would call it a pass."
    (let [readings [{:ctl-blocked [60.0 51.0 70.0]}
                    {:ctl-blocked [80.0 49.0 90.0]}]]
      (is (= [51.0 49.0] (rf.bench.hicasso.ledger-frame-clock-app/control-per-round readings))
          "one minimum per round")
      (is (not (:ok? (rf.bench.hicasso.ledger-frame-clock-app/control-verdict-floor 50.0
                                                  (rf.bench.hicasso.ledger-frame-clock-app/control-per-round readings)
                                                  16.7)))
          "so round two is caught — every median in it is above the floor and one
           of its readings is not"))))

;; ---------------------------------------------------------------------------
;; The knobs, and the geometry they have to fit
;; ---------------------------------------------------------------------------

(deftest the-arm-roster-is-the-three-rows-the-file-documents
  (testing "The namespace docstring names three arms and says what each
           one is for. A fourth added silently would leave that prose
           describing an instrument that no longer exists — the drift
           class this lane keeps paying for."
    (is (= [:idle-frames :scroll :ctl-blocked] (mapv :id rf.bench.hicasso.ledger-frame-clock-app/arms))
        "floor first, so it leads the schedule")
    (is (= [:ctl-blocked] (mapv :id (filter :control? rf.bench.hicasso.ledger-frame-clock-app/arms)))
        "and exactly one of them is a control")
    (is (= [false true true] (mapv :advance? rf.bench.hicasso.ledger-frame-clock-app/arms))
        "the floor arm predicts stillness and both scrolling arms predict movement")
    (is (pos? (:warmup rf.bench.hicasso.ledger-frame-clock-app/sampling)))
    (is (pos? (:samples rf.bench.hicasso.ledger-frame-clock-app/sampling)))
    (is (pos? rf.bench.hicasso.ledger-frame-clock-app/rounds))
    (is (< 1 rf.bench.hicasso.ledger-frame-clock-app/frames-per-run)
        "a run of one frame yields no interval at all")))

(deftest the-injected-control-duration-clears-the-frame-grid
  (testing "`blocked-ms` is chosen against the display's rendering
           interval and not by taste. A frame-bounded reading is quantised
           by the rendering opportunities the browser offers — about
           16.7 ms at 60 Hz — so an injection much smaller than one frame
           can be absorbed by the quantisation entirely, and a control
           that fails for the clock is not a control."
    (is (>= rf.bench.hicasso.ledger-frame-clock-app/blocked-ms (* 2.0 16.7))
        "at least two rendering intervals at 60 Hz — just under three, which is
         what the driver's own docstring rounds to")))

(deftest the-gesture-fits-the-model-at-both-ends
  (testing "[[rf.bench.hicasso.ledger-frame-clock-app/boot!]] refuses a knob set too near either edge of the
           model, because `rf.hicasso.examples.ledger.vendor/window-from` clamps `from` at zero and
           `to` at the last row and a CLAMPED WINDOW STANDS STILL — which
           would make an honest verification failure out of a knob.

           Asserted here against the same arithmetic, so the two refusals
           are exercised without a browser and a knob moved in the driver
           reds this row rather than three hundred visits into a run."
    (let [[from _]  (window-at (start-top))
          last-top  (+ (start-top) (* rf.bench.hicasso.ledger-frame-clock-app/frames-per-run rf.bench.hicasso.ledger-frame-clock-app/scroll-step-px))
          [_ to]    (window-at last-top)]
      (is (pos? from)
          "the gesture starts clear of window-from's (max 0 …) clamp")
      (is (< to (dec rf.bench.hicasso.ledger-frame-clock-app/total))
          "and ends clear of the model's last row")
      (is (= 1 (/ rf.bench.hicasso.ledger-frame-clock-app/scroll-step-px rf.hicasso.examples.ledger.views/row-height))
          "the step is ONE ROW, stated in the screen's own units rather than in
           pixels typed here")
      (is (< from to)
          "so the window the gesture traverses genuinely moves"))))
