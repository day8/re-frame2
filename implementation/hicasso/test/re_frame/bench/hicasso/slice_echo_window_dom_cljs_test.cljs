(ns re-frame.bench.hicasso.slice-echo-window-dom-cljs-test
  "THE INSTRUMENT'S OWN SELF-TEST (rf2-xa8wo, deliverable 2).

  `slice-echo-clock-app` measures one discrete interaction on the slice
  application through to the paint that follows it. This file asks
  whether it does, and it is a CORRECTNESS test of the instrument rather
  than a benchmark: **no assertion here is a line on a latency**, no
  figure is published, and nothing is compared to `U1`–`U4`.

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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.slice-echo-clock-app :as clock]
            [re-frame.hicasso.examples.slice.routes :as slice-routes]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
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
                      ;; outside it — `lane/leave-act-environment!`'s own
                      ;; argument, applied to the suite that drives it.
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; The reset restores the registrar to a baseline
                      ;; captured when THIS FORM was evaluated, which is
                      ;; before `slice.routes` finished loading — so the
                      ;; route the instrument navigates to would not exist.
                      (slice-routes/register!))}))

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
  "Mount the slice through the instrument's own [[clock/boot!]], run
  `f` — which answers a promise — and tear the root down afterwards
  whatever happened.

  A FRESH frame id per mount, because a suite that mounts several times
  in one process would otherwise hand `h/mount!` a frame that already
  holds the previous row's `app-db`, and the seeded article's title —
  which the keystroke row types over — would be whatever the last row
  left there."
  [f]
  (let [container (lane/fresh-container!)]
    (-> (clock/boot! container (fresh-frame-id))
        (.then (fn [_] (f)))
        (.then (fn [v] (clock/teardown!) v)
               (fn [e] (clock/teardown!) (throw e))))))

(defn- plan-for [arm-id]
  (:plan (first (filter #(= arm-id (:id %)) clock/arms))))

(defn- one-window
  "One reading from `arm-id`'s plan, built and taken the way
  [[clock/measure-one!]] builds and takes it."
  [arm-id]
  (clock/window! ((plan-for arm-id) nil)))

(defn- fail-async [done]
  (fn [e]
    (is false (str "the instrument threw rather than answering a reading: " e))
    (done)))

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
                         (is (= (.-value (js/document.querySelector "#slice-title"))
                                (:echo echo))
                             "and it is still there afterwards")
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
                         (is (> ms commit-ms)
                             "and this window too runs past the commit")))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest the-window-extends-past-the-commit-by-the-injected-cost
  (testing "THE DISCRIMINATING ROW. `:ctl-blocked` spends
           `clock/blocked-ms` on the main thread strictly between the
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
                         (is (>= (- ms commit-ms) clock/blocked-ms)
                             (str "at least " clock/blocked-ms "ms of the window lies "
                                  "after the point a flushSync window stops"))
                         (is (:verified? echo)
                             "and the blocked frame still carried the echo")))))
            (.then (fn [_] (done)) (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The instrument end to end, at a schedule small enough for a PR gate
;; ---------------------------------------------------------------------------

(deftest the-whole-schedule-runs-and-every-echo-verifies
  (testing "`lane/rounds-async!` drives all four arms over the real
           application, and the echo tally reads `0 unverified of M` with
           M the whole plan — warm-up visits included, since they bank
           their verification too.

           The schedule is TINY here and the module's own is not: this row
           asks whether the instrument runs, and a run that reads it wants
           `clock/sampling` and `clock/rounds`."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (let [sampling {:warmup 1 :samples 2}
              rounds   1]
          (-> (with-mounted-slice
                (fn []
                  (.then (lane/rounds-async! clock/arms sampling rounds clock/measure-one!)
                         (fn [{:keys [readings samples]}]
                           (is (= (* rounds (:samples sampling) (count clock/arms))
                                  (count samples))
                               "every measured visit was banked for the guard")
                           (is (= rounds (count readings)))
                           (is (every? (fn [round]
                                         (= (count clock/arms) (count round)))
                                       readings)
                               "and every arm has a reading in every round")
                           (is (= {:writes (* rounds
                                              (+ (:warmup sampling) (:samples sampling))
                                              (count clock/arms))
                                   :unverified 0}
                                  (clock/verification))
                               "0 unverified of M — every window, warm-up included,
                                reached a frame that carried its own echo")))))
              (.then (fn [_] (done)) (fail-async done))))))))

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
      (is (= [50.0 50.0] (clock/control-per-round readings))
          "each round pairs its own two medians")
      (let [v (lane/control-verdict-strict clock/blocked-ms
                                           (clock/control-per-round readings)
                                           clock/control-slack)]
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
          v        (lane/control-verdict-strict clock/blocked-ms
                                                (clock/control-per-round readings)
                                                clock/control-slack)]
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
    (is (>= clock/blocked-ms (* 2.0 16.7))
        "at least two rendering intervals at 60 Hz")
    (let [{:keys [band predicted]} (lane/control-verdict-strict
                                     clock/blocked-ms
                                     [clock/blocked-ms]
                                     clock/control-slack)]
      (is (= clock/blocked-ms predicted)
          "the control predicts the injected duration itself — an additive
           prediction that needs no model of the application")
      (is (<= (first band) clock/blocked-ms (second band))
          "and its band is centred on it"))))

(deftest the-arm-roster-is-the-four-rows-the-file-documents
  (testing "The namespace docstring names four rows and says which
           estimands they can and cannot serve. A fifth added silently
           would leave that prose describing an instrument that no longer
           exists — the drift class this lane keeps paying for."
    (is (= [:idle-frame :keystroke :toggle :ctl-blocked] (mapv :id clock/arms))
        "floor first, so it leads the schedule")
    (is (= [:ctl-blocked] (mapv :id (filter :control? clock/arms)))
        "and exactly one of them is a control")
    (is (pos? (:warmup clock/sampling)))
    (is (pos? (:samples clock/sampling)))
    (is (pos? clock/rounds))))
