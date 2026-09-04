(ns re-frame.bench.hicasso.slice-echo-window-dom-cljs-test
  "THE INSTRUMENT'S OWN SELF-TEST (rf2-xa8wo, deliverable 2).

  `slice-echo-clock-app` measures one discrete interaction on the slice
  application through to the paint that follows it. This file asks
  whether it does, and it is a CORRECTNESS test of the instrument rather
  than a benchmark: **no assertion here is a line on a latency**, no
  figure is published, and nothing is compared to `U1`–`U4`.

  ## The two claims this file exists to discriminate

  There are two, and they fail in opposite directions.

  **Does the window reach a PAINT?**
  [[the-window-extends-past-the-commit-by-the-injected-cost]] answers it,
  and it is described below.

  **Does the echo prove a SLICE-APPLICATION echo?** A scripted
  interaction sets its control up by mutating it, so a check taken over
  that same control's state can read true with the application removed —
  and the first version of this instrument had two of them. The three
  SABOTAGE rows are the answer:
  [[the-echo-refuses-the-setup-mutation-alone]] runs the driver's own
  built-in negative control, and
  [[a-keystroke-whose-handler-never-runs-does-not-verify]] and
  [[a-toggle-whose-handler-never-runs-does-not-verify]] fire the real DOM
  event with the React root cut off from it in the capture phase, so the
  user agent's own mutation still happens and nothing else does. All three
  must REFUSE. A green here is the failure: it would mean the arms verify
  something the application never did.

  ## The one inequality that carries the whole claim

  [[the-window-extends-past-the-commit-by-the-injected-cost]] is the
  discriminating row, and it is worth reading before the others.

  Every clock this lane had before this one stops when the commit returns.
  The control arm spends `blocked-ms` on the main thread STRICTLY BETWEEN
  the commit and the frame, so a commit-bounded window sees none of it and
  a paint-bounded window sees all of it. The assertion is therefore

      (>= (- :ms :commit-ms) blocked-ms)

  which reads *at least `blocked-ms` of this window lies after the point
  the old window stopped*. It is flake-proof in the direction a shared CI
  runner can move it: a busy loop that spins for `blocked-ms` takes at
  least that long, and load can only make the left-hand side larger. That
  is why the row is stated as a floor over an injected duration rather
  than as a band around a measured one — a band would be a threshold in a
  correctness gate, which is exactly the thing this bead refuses to add.

  ## What is NOT asserted, deliberately

  The positive control's own verdict. `control-verdict-strict` adjudicates
  a difference of per-round medians, and adjudicating it here would mean
  running the full schedule on a shared runner and then believing the
  answer — a latency threshold in a PR gate by another name. Its
  ARITHMETIC is pinned below on synthetic readings, where it cannot flake;
  its VERDICT belongs to the quiet-box run.

  ## Runtime

  The DOM rows need a real browser and carry the `-dom-cljs-test` suffix
  so `:browser-test` runs them; each degrades to a stated skip under
  `:node-test`, which is the posture every other `*-dom` suite in this
  tree keeps. The pure rows run on both."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.slice-echo-clock-app :as rf.bench.hicasso.slice-echo-clock-app]
            [re-frame.hicasso.examples.slice.routes :as rf.hicasso.examples.slice.routes]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; The MAP shape, because every DOM row is `async`: `cljs.test` refuses
     ;; an async test under a fn-form fixture and ABORTS THE WHOLE NAMESPACE
     ;; — silently as far as the row is concerned, and taking every suite
     ;; scheduled after it in the same bundle with it. `examples.slice.
     ;; flow-dom-cljs-test` carries the same three keys for the same reason.
     :async?        true
     :init-fn       (fn []
                      ;; React's `act` queue is not the browser's scheduler,
                      ;; and every reading this instrument takes is taken
                      ;; outside it — `rf.bench.hicasso.lane/leave-act-environment!`'s own
                      ;; argument, applied to the suite that drives it.
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; The reset restores the registrar to a baseline
                      ;; captured when THIS FORM was evaluated, which is
                      ;; before `slice.routes` finished loading — so the
                      ;; route the instrument navigates to would not exist.
                      (rf.hicasso.examples.slice.routes/register!))}))

(def ^:private off-browser
  "no DOM on this runtime — every claim below is a real mount, a real DOM
  event and a real frame, and :browser-test is where they are asked")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a mounted React root needs a real DOM — " why)))

;; ---------------------------------------------------------------------------
;; One mounted slice, torn down again
;; ---------------------------------------------------------------------------

(defonce ^:private !frame-n (atom 0))

(defn- fresh-frame-id []
  (keyword "re-frame.bench.hicasso.slice-echo-window-dom-cljs-test"
           (str "frame-" (swap! !frame-n inc))))

(defn- with-mounted-slice
  "Mount the slice through the instrument's own [[rf.bench.hicasso.slice-echo-clock-app/boot!]], run
  `f` — which answers a promise — and tear the root down afterwards
  whatever happened.

  A FRESH frame id per mount, because a suite that mounts several times
  in one process would otherwise hand `h/mount!` a frame that already
  holds the previous row's `app-db`, and the seeded article's title —
  which the keystroke row types over — would be whatever the last row
  left there."
  [f]
  (let [container (rf.bench.hicasso.lane/fresh-container!)]
    (-> (rf.bench.hicasso.slice-echo-clock-app/boot! container (fresh-frame-id))
        (.then (fn [_] (f)))
        (.then (fn [v] (rf.bench.hicasso.slice-echo-clock-app/teardown!) v)
               (fn [e] (rf.bench.hicasso.slice-echo-clock-app/teardown!) (throw e))))))

(defn- plan-for [arm-id]
  (:plan (first (filter #(= arm-id (:id %)) rf.bench.hicasso.slice-echo-clock-app/arms))))

(defn- one-window
  "One reading from `arm-id`'s plan, built and taken the way
  [[rf.bench.hicasso.slice-echo-clock-app/measure-one!]] builds and takes it."
  [arm-id]
  (rf.bench.hicasso.slice-echo-clock-app/window! ((plan-for arm-id) nil)))

(defn- fail-async [done]
  (fn [e]
    (is false (str "the instrument threw rather than answering a reading: " e))
    (done)))

;; ---------------------------------------------------------------------------
;; The sabotage — the React root cut off from its own events
;; ---------------------------------------------------------------------------

(defn- with-events-suppressed
  "Run `f` — which answers a promise — with `types` stopped in the CAPTURE
  phase at `js/document`, and restore the document afterwards whatever
  happened.

  WHY THE CAPTURE PHASE AT THE DOCUMENT. React 19 attaches its listeners
  to the ROOT CONTAINER, which `rf.bench.hicasso.lane/fresh-container!` appends to
  `document.body` — so the container sits strictly below `js/document` on
  every propagation path, and a capture-phase `stopPropagation` here means
  the event never descends to it. The Hicasso handler does not run, so
  nothing dispatches, nothing writes and nothing commits.

  WHAT IT DOES NOT SUPPRESS, and that is the point: the USER AGENT'S OWN
  mutation. `stopPropagation` is not `preventDefault`, so a checkbox's
  activation behaviour still flips `checkedness` and this file's own
  `set-native-value!` still writes the property. A check taken over
  either of those would pass under this sabotage; the mirror check
  cannot."
  [types f]
  (let [stop (fn [e] (.stopPropagation e))
        off! (fn [] (doseq [t types] (.removeEventListener js/document t stop true)))]
    (doseq [t types] (.addEventListener js/document t stop true))
    (-> (js/Promise.resolve (f))
        (.then (fn [v] (off!) v)
               (fn [e] (off!) (throw e))))))

;; ---------------------------------------------------------------------------
;; The mount
;; ---------------------------------------------------------------------------

(deftest the-instrument-mounts-the-slice-and-finds-its-editor
  (testing "The population is the slice application on its article route,
           reached through `h/mount!` with the application's own initial
           events. If the editor is not on the page there is no controlled
           field to type into and every row below is meaningless."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice
              (fn []
                (let [field (js/document.querySelector "#slice-title")]
                  (is (some? field) "the editor's title field is on the page")
                  (is (seq (.-value field))
                      "and it is showing the seeded article's title")
                  (is (some? (js/document.querySelector "#slice-published"))
                      "as is the published checkbox the toggle row clicks"))
                (js/Promise.resolve nil)))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest tearing-down-takes-the-root-off-the-page
  (testing "So a suite that mounts twice is not measuring two slices."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice (fn [] (js/Promise.resolve nil)))
            (.then (fn [_]
                     (is (nil? (js/document.querySelector "#slice-title"))
                         "the editor is gone once the root is unmounted")
                     (done))
                   (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The window
;; ---------------------------------------------------------------------------

(deftest a-keystroke-window-reaches-a-frame-carrying-its-own-echo
  (testing "The reading resolves at all — which it can only do once a
           frame's rendering steps have run — and the character was
           already in the DOM when they did, so the frame that was painted
           is the frame that shows it."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice
              (fn []
                (.then (one-window :keystroke)
                       (fn [{:keys [ms commit-ms to-raf-ms raf-to-paint-ms echo]}]
                         (is (:verified? echo)
                             "the typed character was on the glass inside the frame's
                              rendering steps, before style, layout and paint")
                         (is (= (:expect (:echo echo)) (:rendered (:echo echo)))
                             "and the APPLICATION is what put it there: the mirror the
                              plan blanked before the interaction — the `value` content
                              attribute, which only React's commit writes — carries the
                              typed title again")
                         (is (= (.-value (js/document.querySelector "#slice-title"))
                                (:expect (:echo echo)))
                             "and it is still on the glass afterwards")
                         (is (<= commit-ms to-raf-ms ms)
                             "the decomposition is ordered: commit, then the frame's
                              rendering steps, then the task after the paint")
                         (is (<= 0 raf-to-paint-ms)
                             "with the rendering lifecycle after the callback, not before")
                         (is (> ms commit-ms)
                             "so the window is strictly longer than the commit-bounded
                              one every other clock on this lane takes")))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest a-toggle-window-reaches-a-frame-carrying-its-own-echo
  (testing "The second discrete path — `click`/`change` on a checkbox
           rather than `input` on a text field — through the same window."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice
              (fn []
                (.then (one-window :toggle)
                       (fn [{:keys [echo ms commit-ms]}]
                         (is (:verified? echo)
                             "the checkbox had flipped by the frame's rendering steps")
                         (is (= (:want-checked (:echo echo)) (:checked (:echo echo)))
                             "the user agent's activation left the box where the arm
                              predicted — a real fact, and NOT the discriminating one")
                         (is (= (:expect (:echo echo)) (:rendered (:echo echo)))
                             "THE DISCRIMINATING HALF: the title field's mirror, blanked
                              before the click, carries the model's title again — which
                              only a commit of the draft `::toggle-published` moved could
                              have written, and which clicking a checkbox cannot touch")
                         (is (> ms commit-ms)
                             "and this window too runs past the commit")))))
            (.then (fn [_] (done)) (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The sabotage rows — every one of them must REFUSE
;; ---------------------------------------------------------------------------

(deftest the-echo-refuses-the-setup-mutation-alone
  (testing "The driver's OWN negative control, exercised. `rf.bench.hicasso.slice-echo-clock-app/
           echo-discrimination!` performs the keystroke arm's setup
           mutation and nothing else — the native `value` write, with no
           DOM event and therefore no handler, no dispatch, no state write
           and no commit — and requires the arms' own check to refuse it.

           It answers the refused observation and REJECTS if the check
           passed, so this row resolving at all is the proof; the
           assertions below say which half did the discriminating."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice
              (fn []
                (.then (rf.bench.hicasso.slice-echo-clock-app/echo-discrimination!)
                       (fn [seen]
                         (is (= (:expect seen) (:glass seen))
                             "the setup mutation DID reach the glass — which is exactly
                              why a check over `.value` alone would have verified a
                              window in which the application did nothing")
                         (is (= rf.bench.hicasso.slice-echo-clock-app/committed-value-sentinel (:rendered seen))
                             "and the mirror still carries the sentinel, because no
                              commit ran to overwrite it")
                         (is (not= (:expect seen) (:rendered seen))
                             "so the check refuses")))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest a-keystroke-whose-handler-never-runs-does-not-verify
  (testing "THE SABOTAGE, on the arm the instrument actually schedules.
           The real `input` event fires on the real field, and the React
           root is cut off from it in the capture phase — so the Hicasso
           handler never runs, `::events/edit` is never dispatched, the
           draft never moves and nothing commits.

           `set-native-value!` still put the character on the glass. The
           version of this arm that shipped verified exactly that and
           would have passed here."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice
              (fn []
                (with-events-suppressed
                  ["input"]
                  (fn []
                    (.then (one-window :keystroke)
                           (fn [{:keys [echo]}]
                             (is (not (:verified? echo))
                                 "the arm REFUSES a window in which the application
                                  never saw the keystroke")
                             (is (= (:expect (:echo echo)) (:glass (:echo echo)))
                                 "even though the typed character is on the glass, put
                                  there by this file's own setup write")
                             (is (= rf.bench.hicasso.slice-echo-clock-app/committed-value-sentinel
                                    (:rendered (:echo echo)))
                                 "the mirror is untouched — no commit reached the
                                  field")))))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest a-toggle-whose-handler-never-runs-does-not-verify
  (testing "The same sabotage on the second event path. `HTMLElement.
           click()`'s activation behaviour is the user agent's and runs
           whatever any listener does, so `checked` still flips; only the
           application is missing.

           This is the row that retires the old toggle check: it asserted
           `checked`, and `checked` is true here."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice
              (fn []
                (with-events-suppressed
                  ["click"]
                  (fn []
                    (.then (one-window :toggle)
                           (fn [{:keys [echo]}]
                             (is (not (:verified? echo))
                                 "the arm REFUSES a window in which the application
                                  never saw the click")
                             (is (= (:want-checked (:echo echo)) (:checked (:echo echo)))
                                 "while the checkbox flipped anyway — the user agent
                                  did it, and a check over this would have passed")
                             (is (= rf.bench.hicasso.slice-echo-clock-app/committed-value-sentinel
                                    (:rendered (:echo echo)))
                                 "and the mirror is untouched")))))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest the-window-extends-past-the-commit-by-the-injected-cost
  (testing "THE DISCRIMINATING ROW. `:ctl-blocked` spends
           `rf.bench.hicasso.slice-echo-clock-app/blocked-ms` on the main thread strictly between the
           commit and the frame. A commit-bounded window sees none of it;
           this one must see all of it.

           A floor over an injected duration, never a band around a
           measured one — load can only make the left-hand side larger."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-mounted-slice
              (fn []
                (.then (one-window :ctl-blocked)
                       (fn [{:keys [ms commit-ms echo]}]
                         (is (>= (- ms commit-ms) rf.bench.hicasso.slice-echo-clock-app/blocked-ms)
                             (str "at least " rf.bench.hicasso.slice-echo-clock-app/blocked-ms "ms of the window lies "
                                  "after the point a flushSync window stops"))
                         (is (:verified? echo)
                             "and the blocked frame still carried the echo")))))
            (.then (fn [_] (done)) (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The instrument end to end, at a schedule small enough for a PR gate
;; ---------------------------------------------------------------------------

(deftest the-whole-schedule-runs-and-every-echo-verifies
  (testing "`rf.bench.hicasso.lane/rounds-async!` drives all four arms over the real
           application, and the echo tally reads `0 unverified of M` with
           M the whole plan — warm-up visits included, since they bank
           their verification too.

           The schedule is TINY here and the module's own is not: this row
           asks whether the instrument runs, and a run that reads it wants
           `rf.bench.hicasso.slice-echo-clock-app/sampling` and `rf.bench.hicasso.slice-echo-clock-app/rounds`."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (let [sampling {:warmup 1 :samples 2}
              rounds   1]
          (-> (with-mounted-slice
                (fn []
                  (.then (rf.bench.hicasso.lane/rounds-async! rf.bench.hicasso.slice-echo-clock-app/arms sampling rounds rf.bench.hicasso.slice-echo-clock-app/measure-one!)
                         (fn [{:keys [readings samples]}]
                           (is (= (* rounds (:samples sampling) (count rf.bench.hicasso.slice-echo-clock-app/arms))
                                  (count samples))
                               "every measured visit was banked for the guard")
                           (is (= rounds (count readings)))
                           (is (every? (fn [round]
                                         (= (count rf.bench.hicasso.slice-echo-clock-app/arms) (count round)))
                                       readings)
                               "and every arm has a reading in every round")
                           (is (= {:writes (* rounds
                                              (+ (:warmup sampling) (:samples sampling))
                                              (count rf.bench.hicasso.slice-echo-clock-app/arms))
                                   :unverified 0}
                                  (rf.bench.hicasso.slice-echo-clock-app/verification))
                               "0 unverified of M — every window, warm-up included,
                                reached a frame that carried its own echo")
                           (let [banked    (rf.bench.hicasso.slice-echo-clock-app/banked-structure)
                                 published (rf.bench.hicasso.slice-echo-clock-app/structure-over-measured
                                             rf.bench.hicasso.slice-echo-clock-app/arms sampling rounds banked)
                                 measured  (reduce
                                             (fn [m round]
                                               (reduce-kv
                                                 (fn [m id xs]
                                                   (update m id (fnil + 0) (count xs)))
                                                 m round))
                                             {} readings)]
                             (doseq [{:keys [id]} rf.bench.hicasso.slice-echo-clock-app/arms]
                               (is (= (get measured id)
                                      (:n (:commit (get published id)))
                                      (:n (:to-raf (get published id)))
                                      (:n (:raf-to-paint (get published id))))
                                   (str "THE POPULATIONS AGREE on " id ": every part of
                                         the published decomposition has the `n` of the
                                         summary it decomposes")))
                             (is (< (get measured :keystroke)
                                    (count (:commit (get banked :keystroke))))
                                 "and the two populations are genuinely different at this
                                  schedule — warm-up visits ARE banked — so a narrowing
                                  that silently became a no-op would fail the rows
                                  above rather than pass them vacuously"))))))
              (.then (fn [_] (done)) (fail-async done))))))))

;; ---------------------------------------------------------------------------
;; The populations, where the arithmetic can be pinned without a browser
;; ---------------------------------------------------------------------------

(deftest the-measured-mask-is-the-lanes-own-schedule
  (testing "`rf.bench.hicasso.slice-echo-clock-app/measured-mask` is derived from `rf.bench.hicasso.lane/visit-plan` — the
           one statement of the schedule both of the lane's loops walk —
           and not re-derived from `warmup`, `samples` and `slot-order`
           inside the driver. A mask that had drifted from the schedule
           would not fail; it would publish a distribution over the wrong
           visits and say nothing about it."
    (let [sampling {:warmup 2 :samples 3}
          rounds   2
          mask     (rf.bench.hicasso.slice-echo-clock-app/measured-mask rf.bench.hicasso.slice-echo-clock-app/arms sampling rounds)]
      (is (= (set (map :id rf.bench.hicasso.slice-echo-clock-app/arms)) (set (keys mask)))
          "every arm has a mask")
      (doseq [{:keys [id]} rf.bench.hicasso.slice-echo-clock-app/arms]
        (is (= (* rounds (+ (:warmup sampling) (:samples sampling)))
               (count (get mask id)))
            (str id " is visited once per sample index per round"))
        (is (= (* rounds (:samples sampling))
               (count (filter true? (get mask id))))
            (str id "'s measured visits are `samples` per round")))
      (is (= [false false true true true false false true true true]
             (get mask :keystroke))
          "and the warm-up block leads EVERY round, not just the first —
           `rf.bench.hicasso.lane/rounds!`'s own docstring prices that asymmetry"))))

(deftest the-record-labels-which-population-each-figure-is-taken-over
  (testing "`:summary` and `:structure` share the measured population
           because the second is published as the decomposition of the
           first. `:echo` deliberately does not: it is a count of
           refusals rather than a distribution, it decomposes nothing, and
           a verification is worth more the more windows it covers."
    (is (= {:summary   :measured-visits
            :structure :measured-visits
            :echo      :all-visits}
           rf.bench.hicasso.slice-echo-clock-app/populations))))

;; ---------------------------------------------------------------------------
;; The control's arithmetic, where it cannot flake
;; ---------------------------------------------------------------------------

(deftest the-control-adjudicates-a-difference-of-per-round-medians
  (testing "Pinned on synthetic readings rather than on a run. The
           prediction is ADDITIVE — the control injects a duration, not a
           multiple — so what each round contributes is
           `p50(:ctl-blocked) - p50(:keystroke)` in milliseconds."
    (let [readings [{:keystroke [10.0 12.0 14.0] :ctl-blocked [60.0 62.0 64.0]}
                    {:keystroke [20.0 20.0 20.0] :ctl-blocked [69.0 70.0 71.0]}]]
      (is (= [50.0 50.0] (rf.bench.hicasso.slice-echo-clock-app/control-per-round readings))
          "each round pairs its own two medians")
      (let [v (rf.bench.hicasso.lane/control-verdict-strict rf.bench.hicasso.slice-echo-clock-app/blocked-ms
                                           (rf.bench.hicasso.slice-echo-clock-app/control-per-round readings)
                                           rf.bench.hicasso.slice-echo-clock-app/control-slack)]
        (is (:ok? v) "a control that saw exactly what it predicted passes")
        (is (= :every-round (:rule v))
            "under the strict rule — these legs are tens of milliseconds and
             nowhere near the 100 microsecond clock clamp the overlap rule exists
             for")))))

(deftest the-control-refuses-a-window-that-did-not-see-the-injection
  (testing "Anti-vacuity for the row above. A commit-bounded window would
           see none of the injected cost, so its per-round differences
           would sit at zero — and the control has to say so."
    (let [readings [{:keystroke [10.0 12.0 14.0] :ctl-blocked [10.0 12.0 14.0]}
                    {:keystroke [20.0 20.0 20.0] :ctl-blocked [20.0 21.0 20.0]}]
          v        (rf.bench.hicasso.lane/control-verdict-strict rf.bench.hicasso.slice-echo-clock-app/blocked-ms
                                                (rf.bench.hicasso.slice-echo-clock-app/control-per-round readings)
                                                rf.bench.hicasso.slice-echo-clock-app/control-slack)]
      (is (not (:ok? v))
          "an instrument that cannot see a change its own arithmetic predicts
           cannot be trusted with an unpredicted one")
      (is (= 2 (count (:outside v)))
          "and both rounds are named rather than merely counted"))))

;; ---------------------------------------------------------------------------
;; The knobs, and the reasoning behind the one that is not a schedule knob
;; ---------------------------------------------------------------------------

(deftest the-injected-control-duration-clears-the-frame-grid
  (testing "`blocked-ms` is chosen against the display's rendering
           interval and not by taste. A paint-bounded window is quantised
           by the rendering opportunities the browser offers — about
           16.7 ms at 60 Hz — so an injection much smaller than one frame
           can be absorbed by the quantisation entirely, and a control
           that fails for the clock is not a control."
    (is (>= rf.bench.hicasso.slice-echo-clock-app/blocked-ms (* 2.0 16.7))
        "at least two rendering intervals at 60 Hz")
    (let [{:keys [band predicted]} (rf.bench.hicasso.lane/control-verdict-strict
                                     rf.bench.hicasso.slice-echo-clock-app/blocked-ms
                                     [rf.bench.hicasso.slice-echo-clock-app/blocked-ms]
                                     rf.bench.hicasso.slice-echo-clock-app/control-slack)]
      (is (= rf.bench.hicasso.slice-echo-clock-app/blocked-ms predicted)
          "the control predicts the injected duration itself — an additive
           prediction that needs no model of the application")
      (is (<= (first band) rf.bench.hicasso.slice-echo-clock-app/blocked-ms (second band))
          "and its band is centred on it"))))

(deftest the-arm-roster-is-the-four-rows-the-file-documents
  (testing "The namespace docstring names four rows and says which
           estimands they can and cannot serve. A fifth added silently
           would leave that prose describing an instrument that no longer
           exists — the drift class this lane keeps paying for."
    (is (= [:idle-frame :keystroke :toggle :ctl-blocked] (mapv :id rf.bench.hicasso.slice-echo-clock-app/arms))
        "floor first, so it leads the schedule")
    (is (= [:ctl-blocked] (mapv :id (filter :control? rf.bench.hicasso.slice-echo-clock-app/arms)))
        "and exactly one of them is a control")
    (is (pos? (:warmup rf.bench.hicasso.slice-echo-clock-app/sampling)))
    (is (pos? (:samples rf.bench.hicasso.slice-echo-clock-app/sampling)))
    (is (pos? rf.bench.hicasso.slice-echo-clock-app/rounds))))
