(ns re-frame.bench.hicasso.slice-broad-window-dom-cljs-test
  "THE BROAD-UPDATE CLOCK'S OWN SELF-TEST (rf2-9wmqd, item (b)).

  [[re-frame.bench.hicasso.slice-broad-clock-app]] times one broad
  application operation on the slice's feed page, on the Hicasso arm and
  on the UIx donor arm beside it, and divides the two. This file asks
  whether the two arms are COMPARABLE — and it is a correctness test of
  the instrument rather than a benchmark: **no assertion here is a line on
  a latency**, no figure is published, and nothing is compared to `U3`,
  `C3` or `C4`.

  It is the browser-suite row PR #8599 left owed. That driver already
  carries six instrument-integrity controls that run IN it — a
  canonical-DOM parity gate with its own negative control, four echo
  negative controls, the tally, the arm-order guard and the additive
  positive control — and in-driver is the stronger place for them, by the
  sibling `slice-echo-window-dom-cljs-test`'s own argument: the driver is
  what a quiet-box window runs and a suite is not. What a suite is for is
  proving a REPAIR, and this file proves two.

  ## THE TWO CLAIMS, AND WHY CANONICAL DOM CANNOT CARRY EITHER

  A cross-arm ratio needs two arms doing the same work on the same page.
  The driver's parity gate answers *the same PAGE* and answers it well.
  Neither claim below is about the page.

  **Do the two arms make the same READS?** Two pages can serialise
  identically while one of them subscribes to a value the other reads only
  in a branch it never takes. PR #8599's donor did exactly that with
  `[::rf.hicasso.examples.slice.subs/t :feed/empty]`, on a seed that is never empty, so the donor
  carried a subscription and a hook the Hicasso arm did not — work in the
  DENOMINATOR, moving `Hicasso / UIx` in the numerator's favour.
  [[the-donor-reads-nothing-the-hicasso-arm-does-not]] compares the two
  frames' subscription caches, and
  [[the-roster-gate-catches-an-unconditional-branch-local-read]] replants
  the removed read and requires the comparison to catch it.

  **Do PAIRED ARMS see the same page STATE?** The parity gate runs once,
  on the seeded page, before the first window; every arm moves its own
  page afterwards. The audit's replay of `rf.bench.hicasso.lane/visit-plan` found `:theme`
  running in the non-seed locale on 4 of its 12 measured visits per round
  against `:donor-theme`'s 8 of 12, so the comparative theme figure was a
  ratio between two populations.
  [[paired-arms-see-the-same-governed-state-mix]] asserts the repair over
  the schedule itself, and
  [[the-mix-gate-catches-a-rig-whose-arms-inherit-each-others-state]] is
  its negative control: it models the rig PR #8599 shipped and requires
  the same comparison to report the same 4-against-8.

  ## WHAT THIS FILE DELIBERATELY DOES NOT RE-ADJUDICATE

  **The canonical-DOM parity gate.** `rf.bench.hicasso.slice-broad-clock-app/assert-parity!` is a RELEASE
  gate by construction: under `goog.DEBUG` each Hicasso boundary emits its
  own `data-rf2-source-coord`, the UIx arm emits none, and the two pages
  are correctly not the same page. This suite compiles in dev, so a row
  asserting that equality here would either fail honestly or have to strip
  attributes until it passed — and a gate re-stated with an exemption is a
  weaker gate wearing a stronger one's name. The driver owns it, `run.cjs`
  builds `release`, and the mount row below asks only what a dev compile
  can honestly answer.

  **The control's own verdict**, for the sibling's reason:
  `control-verdict-strict` adjudicates a difference of per-round medians,
  and adjudicating it on a shared runner would be a latency threshold in a
  PR gate by another name.

  ## THE HARNESS HALF, WHICH COST A CI CYCLE TO FIND

  Every DOM row here boots on FRESH frame ids. That is not hygiene: a
  second mount on a used id leaves the Hicasso arm rendered and DEAF,
  because the collector's cell table is process-global and keyed by
  `(frame, query)` while the fixture retires a frame without going through
  the disposal hook that repairs those cells. [[with-both-arms]] and
  `rf.bench.hicasso.slice-broad-clock-app/boot!`'s §MOUNTING TWICE carry the mechanism and the measurement.

  Two of the rows below are what found it, and they were written for
  something else — which is the argument for having them. The read-roster
  row reported the Hicasso arm's cache as `#{}` against the donor's 34,
  and the schedule row reported `9 unverified of 18`, exactly the nine
  Hicasso-side windows. The same schedule row also caught a real
  disagreement in the driver's own contract: `rf.bench.hicasso.slice-broad-clock-app/visits` published the
  last visit INDEX under a docstring promising a COUNT.

  ## Runtime

  The DOM rows need a real browser and the `-dom-cljs-test` suffix puts
  them on `:browser-test`; each degrades to a stated skip under
  `:node-test`, which is the posture every other `*-dom` suite in this
  tree keeps. The schedule rows are pure arithmetic over
  `rf.bench.hicasso.lane/visit-plan` and run on both."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.slice-broad-clock-app :as rf.bench.hicasso.slice-broad-clock-app]
            [re-frame.bench.hicasso.slice-echo-clock-app :as rf.bench.hicasso.slice-echo-clock-app]
            [re-frame.hicasso.examples.slice.routes :as rf.hicasso.examples.slice.routes]
            [re-frame.hicasso.examples.slice.subs :as rf.hicasso.examples.slice.subs]
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.test-support :as rf.test-support]
            [uix.core :as uix :refer [$ defui]]
            [uix.dom :as uix-dom]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; The MAP shape, because every DOM row is `async`: `cljs.test` refuses
     ;; an async test under a fn-form fixture and ABORTS THE WHOLE NAMESPACE
     ;; — silently as far as the row is concerned, and taking every suite
     ;; scheduled after it in the same bundle with it. The sibling suite and
     ;; `examples.slice.flow-dom-cljs-test` carry the same three keys.
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
                      ;; route both arms navigate to would not exist.
                      (rf.hicasso.examples.slice.routes/register!))}))

(def ^:private off-browser
  "no DOM on this runtime — the rows below mount two real roots, take real
  subscription caches off two real frames and fire real DOM events, and
  :browser-test is where they are asked")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "two mounted React roots need a real DOM — " why)))

(defn- fail-async [done]
  (fn [e]
    (is false (str "the instrument threw rather than answering: " e))
    (done)))

;; ---------------------------------------------------------------------------
;; Both arms mounted, both torn down again
;; ---------------------------------------------------------------------------

(defonce ^:private !frame-n (atom 0))

(defn- fresh-frame-ids
  "A pair of frame ids no earlier row in this process has used."
  []
  (let [n (swap! !frame-n inc)]
    {:hicasso (keyword "re-frame.bench.hicasso.slice-broad-window-dom-cljs-test"
                       (str "hicasso-" n))
     :donor   (keyword "re-frame.bench.hicasso.slice-broad-window-dom-cljs-test"
                       (str "donor-" n))}))

(defn- with-both-arms
  "Mount both arms through the instrument's own [[rf.bench.hicasso.slice-broad-clock-app/boot!]], run `f` —
  which answers a promise — and tear both roots down afterwards whatever
  happened.

  FRESH FRAME IDS PER MOUNT, and this is load-bearing rather than tidy.
  `rf.bench.hicasso.slice-broad-clock-app/boot!`'s §MOUNTING TWICE carries the measurement: the Hicasso
  collector's `!cells` table is process-global and keyed by
  `(frame, query)`, the repair for a retired reaction rides a disposal
  hook, and the `:each` fixture retires a frame by resetting
  `re-frame.frame/frames` rather than through that hook. A second mount on
  a used id therefore renders once and is then DEAF — empty sub-cache, no
  watches, no re-render — while looking perfectly healthy on the glass.

  This suite's first cut booted on `rf.bench.hicasso.slice-broad-clock-app/`'s two default ids and CI read
  exactly that: the mount row green, and every row after it red. It is the
  discipline `slice-echo-window-dom-cljs-test` already keeps."
  [f]
  (-> (rf.bench.hicasso.slice-broad-clock-app/boot! (fresh-frame-ids))
      (.then (fn [_] (f)))
      (.then (fn [v] (rf.bench.hicasso.slice-broad-clock-app/teardown!) v)
             (fn [e] (rf.bench.hicasso.slice-broad-clock-app/teardown!) (throw e)))))

(defn- arm-of [id]
  (first (filter #(= id (:id %)) rf.bench.hicasso.slice-broad-clock-app/arms)))

;; ---------------------------------------------------------------------------
;; The read roster — what each frame has actually subscribed to
;; ---------------------------------------------------------------------------

(defn- roster
  "The set of query vectors live in `frame-id`'s subscription cache.

  `rf.subs.tooling/sub-cache-snapshot` is the tool-facing read of the same
  per-frame cache BOTH substrates go through — Hicasso's collector calls
  `rf.hicasso.examples.slice.subs/subscribe` for every `h/sub` edge, and the UIx adapter's
  `use-subscribe` calls it for every hook — so the two rosters are
  comparable without either arm being asked to report on itself.

  IT IS THE REALISED SUBGRAPH, NOT THE BOUNDARY READ SET, and the
  difference is worth stating because it makes this comparison STRONGER
  rather than looser. A cache entry exists for every reaction that was
  materialised, so a declared-input sub's inputs are in here beside the values a
  body asked for: reading `[::rf.hicasso.examples.slice.subs/current-page]` puts `[::rf.hicasso.examples.slice.subs/listed]`,
  `[::rf.hicasso.examples.slice.subs/page]`, `[:rf.route/query]` and `[:rf/route]` in the roster
  too. A donor whose extra read were hidden one layer down — a different
  input chain reaching the same value — would still show up."
  [frame-id]
  (set (keys (rf.subs.tooling/sub-cache-snapshot frame-id))))

(defn- hicasso-roster [] (roster (:hicasso (rf.bench.hicasso.slice-broad-clock-app/frames))))
(defn- donor-roster [] (roster (:donor (rf.bench.hicasso.slice-broad-clock-app/frames))))

(defn- donor-only
  "What the donor arm reads that the Hicasso arm does not. THE ONE THAT
  MATTERS: an entry here is work in the denominator of `C3`."
  []
  (set/difference (donor-roster) (hicasso-roster)))

(defui empty-label-probe
  "One UNCONDITIONAL read of `[::rf.hicasso.examples.slice.subs/t :feed/empty]` on the donor frame.

  This is exactly the shape `slice-donor-views/feed-page` carried before
  rf2-9wmqd's repair — a hook cannot be conditional, so a branch-local
  string read beside the rows and the heading became a read the arm makes
  on every render of a feed that is never empty. It is replanted here, in
  the suite rather than in the arm, so
  [[the-roster-gate-catches-an-unconditional-branch-local-read]] has a
  fault to catch."
  [_]
  ($ :span.empty-label-probe (rf.adapter.uix/use-subscribe [::rf.hicasso.examples.slice.subs/t :feed/empty])))

(defn- with-probe-mounted
  "Render [[empty-label-probe]] into its own root on the DONOR frame, run
  `f` — which answers a promise — and take the root down again.

  Its own container, so it cannot move either arm's page and cannot reach
  the driver's canonical-DOM gate; what it moves is the donor frame's
  subscription cache, which is the thing under test."
  [f]
  (let [c    (rf.bench.hicasso.lane/fresh-container!)
        root (uix-dom/create-root c {:identifier-prefix "probe"})
        drop! (fn []
                (uix-dom/unmount-root root)
                (when (.-parentNode c) (.removeChild (.-parentNode c) c)))]
    (uix-dom/render-root ($ rf.adapter.uix/frame-provider {:frame (:donor (rf.bench.hicasso.slice-broad-clock-app/frames))}
                            ($ empty-label-probe {}))
                         root)
    (-> (rf.bench.hicasso.slice-echo-clock-app/after-paint)
        (.then (fn [_] (rf.bench.hicasso.slice-echo-clock-app/after-paint)))
        (.then (fn [_] (f)))
        (.then (fn [v] (drop!) v)
               (fn [e] (drop!) (throw e))))))

;; ---------------------------------------------------------------------------
;; Replaying the schedule — where the state mix can be settled without a
;; browser at all
;; ---------------------------------------------------------------------------

(defn- established-runs
  "`{arm-id [pre-state …]}` over the MEASURED visits of one whole run,
  taking each arm's pre-state from the driver's own [[rf.bench.hicasso.slice-broad-clock-app/pre-state]]
  and from the arm's own visit index.

  The index is counted the way [[rf.bench.hicasso.slice-broad-clock-app/claim-visit!]] counts it — every
  visit the plan makes, warm-up included — because the driver's counter is
  advanced once per `measure-one!` and `measure-one!` is called for both."
  [arms sampling rounds]
  (let [!n (atom {})]
    (reduce (fn [acc {:keys [arm measured?]}]
              (let [i (get (swap! !n update (:id arm) (fnil inc -1)) (:id arm))]
                (if measured?
                  (update acc (:id arm) (fnil conj []) (rf.bench.hicasso.slice-broad-clock-app/pre-state arm i))
                  acc)))
            {}
            (rf.bench.hicasso.lane/visit-plan arms sampling rounds))))

(defn- other-of [roster* now]
  (first (remove #(= % now) roster*)))

(defn- inherited-runs
  "The same replay under a model of the rig PR #8599 SHIPPED: no arm
  establishes anything, so what an arm reads is whatever the arms
  scheduled before it left on ITS OWN FRAME.

  The asymmetry the model carries is the driver's own roster, not an
  invention: `:locale` and `:ctl-blocked` both move the Hicasso frame's
  locale while only `:donor-locale` moves the donor's, and `:theme` /
  `:donor-theme` move one theme each."
  [arms sampling rounds]
  (let [!st (atom {:hicasso {:locale rf.bench.hicasso.slice-broad-clock-app/seed-locale :theme rf.bench.hicasso.slice-broad-clock-app/seed-theme}
                   :donor   {:locale rf.bench.hicasso.slice-broad-clock-app/seed-locale :theme rf.bench.hicasso.slice-broad-clock-app/seed-theme}})]
    (reduce (fn [acc {:keys [arm measured?]}]
              (let [{:keys [id side alternates]} arm
                    before (get @!st side)
                    acc'   (if measured? (update acc id (fnil conj []) before) acc)]
                (when alternates
                  (swap! !st update-in [side alternates]
                         (fn [v] (other-of (if (= :locale alternates)
                                             rf.bench.hicasso.slice-broad-clock-app/locales
                                             rf.bench.hicasso.slice-broad-clock-app/themes)
                                           v))))
                acc'))
            {}
            (rf.bench.hicasso.lane/visit-plan arms sampling rounds))))

(def ^:private compared-pairs
  "The three pairs whose two halves are subtracted or divided by each
  other, and which therefore have to be drawn from one population.

  The first two are the driver's published `:comparative` figures. The
  third is the POSITIVE CONTROL and its denominator: `control-per-round`
  subtracts `:locale`'s per-round median from `:ctl-blocked`'s, and a
  subtraction over two populations is as unreadable as a ratio over two."
  [[:locale :donor-locale]
   [:theme :donor-theme]
   [:ctl-blocked :locale]])

(defn- mix [runs id]
  (frequencies (get runs id)))

(defn- non-seed-locales [runs id]
  (count (remove #(= rf.bench.hicasso.slice-broad-clock-app/seed-locale (:locale %)) (get runs id))))

(defn- non-seed-themes [runs id]
  (count (remove #(= rf.bench.hicasso.slice-broad-clock-app/seed-theme (:theme %)) (get runs id))))

;; The audit's own schedule, written out rather than read off the module,
;; so the numbers below stay the numbers the audit measured however the
;; module's knobs move afterwards.
(def ^:private audit-sampling {:warmup 8 :samples 12})
(def ^:private audit-rounds 5)

;; ---------------------------------------------------------------------------
;; The state mix
;; ---------------------------------------------------------------------------

(deftest paired-arms-see-the-same-governed-state-mix
  (testing "Each arm's page state is a pure function of its own visit
           index, so two arms that are divided or subtracted by each other
           see the SAME multiset of pre-states — the same locales in the
           same numbers, and the same themes.

           Asserted over several schedules, and the last two are the
           point: `rf.bench.hicasso.slice-broad-clock-app/sampling`'s own docstring says a run reading this
           instrument will raise `:samples`, so a repair that held at 12
           and failed at 13 would fail exactly when it was first relied
           on."
    (doseq [[sampling rounds] [[audit-sampling audit-rounds]
                               [rf.bench.hicasso.slice-broad-clock-app/sampling rf.bench.hicasso.slice-broad-clock-app/rounds]
                               [{:warmup 3 :samples 6} 5]
                               [{:warmup 8 :samples 13} 5]
                               [{:warmup 8 :samples 20} 5]]]
      (let [runs (established-runs rf.bench.hicasso.slice-broad-clock-app/arms sampling rounds)]
        (doseq [{:keys [id]} rf.bench.hicasso.slice-broad-clock-app/arms]
          (is (= (* rounds (:samples sampling)) (count (get runs id)))
              (str id " has one measured pre-state per measured visit at "
                   (pr-str sampling))))
        (doseq [[a b] compared-pairs]
          (is (= (mix runs a) (mix runs b))
              (str a " and " b " are drawn from one population at "
                   (pr-str sampling) " over " rounds " rounds")))))))

(deftest the-mix-gate-catches-a-rig-whose-arms-inherit-each-others-state
  (testing "ANTI-VACUITY for the row above, and the audit's finding
           reproduced. Replay the identical schedule under a model of the
           rig PR #8599 shipped — every arm reading whatever the arms
           before it left on its own frame — and the same comparison has
           to REFUSE, with the numbers the audit measured.

           A green here would mean the row above passes whatever the arms
           see, and every state-mix claim this instrument could make would
           be worthless."
    (let [runs (inherited-runs rf.bench.hicasso.slice-broad-clock-app/arms audit-sampling audit-rounds)]
      (is (not= (mix runs :theme) (mix runs :donor-theme))
          "the published theme comparative's two halves are NOT one population")
      (is (= (* audit-rounds 4) (non-seed-locales runs :theme))
          ":theme runs in the non-seed locale on 4 of its 12 measured visits per round")
      (is (= (* audit-rounds 8) (non-seed-locales runs :donor-theme))
          "and :donor-theme on 8 of 12 — twice as often, on the other frame")
      (is (not= (mix runs :ctl-blocked) (mix runs :locale))
          "and the positive control is not drawn from its own denominator's
           population either")
      (is (= (* audit-rounds 8) (non-seed-themes runs :ctl-blocked))
          ":ctl-blocked runs in the non-seed THEME on 8 of 12 per round")
      (is (= (* audit-rounds 4) (non-seed-themes runs :locale))
          "against :locale's 4 of 12 — the same divergence with the
           dimensions swapped, which the audit did not name and the replay
           finds"))
    (testing "and the repair closes both at the same schedule, so the
             refusals above are about the rig and not about the replay"
      (let [runs (established-runs rf.bench.hicasso.slice-broad-clock-app/arms audit-sampling audit-rounds)]
        (is (= (mix runs :theme) (mix runs :donor-theme)))
        (is (= (mix runs :ctl-blocked) (mix runs :locale)))))))

(deftest every-arm-takes-both-directions-in-equal-numbers
  (testing "`locale-plan` and `theme-plan` are written for a rotor — the
           target is always the value the page is NOT showing — and each
           claims its arm takes both directions in equal numbers. Under
           [[rf.bench.hicasso.slice-broad-clock-app/pre-state]] that is a property of the arm rather than a
           consequence of the plan, so it can be asserted.

           The floor alternates nothing and holds the seed on every
           visit, which is what makes it a floor."
    (let [runs (established-runs rf.bench.hicasso.slice-broad-clock-app/arms rf.bench.hicasso.slice-broad-clock-app/sampling rf.bench.hicasso.slice-broad-clock-app/rounds)
          n    (* rf.bench.hicasso.slice-broad-clock-app/rounds (:samples rf.bench.hicasso.slice-broad-clock-app/sampling))]
      (doseq [{:keys [id alternates]} rf.bench.hicasso.slice-broad-clock-app/arms]
        (case alternates
          :locale (do (is (= (/ n 2) (non-seed-locales runs id))
                          (str id " opens half its measured windows in each locale"))
                      (is (zero? (non-seed-themes runs id))
                          (str id " never moves the theme, so it holds the seed's")))
          :theme  (do (is (= (/ n 2) (non-seed-themes runs id))
                          (str id " opens half its measured windows in each theme"))
                      (is (zero? (non-seed-locales runs id))
                          (str id " never moves the locale, so it holds the seed's")))
          (do (is (zero? (non-seed-locales runs id))
                  (str id " alternates nothing and holds the seeded locale"))
              (is (zero? (non-seed-themes runs id))
                  (str id " alternates nothing and holds the seeded theme"))))))))

;; ---------------------------------------------------------------------------
;; The roster, on the arms themselves
;; ---------------------------------------------------------------------------

(deftest both-arms-mount-their-feed-page-and-render-it
  (testing "The population is the slice application on its FEED route, one
           copy through `h/mount!` and one through `uix.dom`, each on its
           own frame. If either page is not there, every row below is
           meaningless.

           What is asked is what a DEV compile can honestly answer: both
           pages exist, both carry the application's title in the seeded
           locale and both wear the seeded theme's surface. The canonical
           equality of the two is the driver's own release-mode gate — see
           the namespace docstring."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-both-arms
              (fn []
                (let [{:keys [hicasso donor]} (rf.bench.hicasso.slice-broad-clock-app/pages)]
                  (is (pos? (count hicasso)) "the Hicasso arm rendered a page")
                  (is (pos? (count donor)) "and so did the donor arm"))
                (doseq [side [:hicasso :donor]]
                  (let [loc (rf.bench.hicasso.slice-broad-clock-app/locale-echo-check side rf.bench.hicasso.slice-broad-clock-app/seed-locale)
                        thm (rf.bench.hicasso.slice-broad-clock-app/theme-echo-check side rf.bench.hicasso.slice-broad-clock-app/seed-theme)]
                    (is (= (:expect loc) (:rendered loc))
                        (str "the " (name side) " arm's <h1> carries the seeded"
                             " locale's title"))
                    (is (= (:expect thm) (:rendered thm))
                        (str "and its root wears the seeded theme's surface"))))
                (js/Promise.resolve nil)))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest the-donor-reads-nothing-the-hicasso-arm-does-not
  (testing "THE READ-ROSTER ROW. `C3` divides the Hicasso arm by the donor
           arm, so a read the donor makes and the Hicasso arm does not is
           work in the denominator and moves the ratio in the numerator's
           favour. On the seeded page there must be none.

           The one asymmetry that remains runs the other way and is
           documented at `slice-donor-views/app`: this arm renders no route
           branch, so it does not read `[:rf.route/id]`. An arm doing LESS
           is the arm the ratio divides BY, so the omission cannot flatter
           the numerator."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-both-arms
              (fn []
                (let [hic (hicasso-roster)
                      don (donor-roster)]
                  ;; ANTI-VACUITY FIRST. An empty roster would make every
                  ;; difference below empty and every claim vacuous, and a
                  ;; production compile elides the snapshot's body entirely.
                  (is (< 10 (count hic))
                      "the Hicasso arm's cache holds a real page's worth of reads")
                  (is (< 10 (count don))
                      "and so does the donor arm's")
                  (is (contains? hic [::rf.hicasso.examples.slice.subs/feed])
                      "both arms read the feed off the same registration")
                  (is (contains? don [::rf.hicasso.examples.slice.subs/feed]))
                  (is (contains? hic [::rf.hicasso.examples.slice.subs/t :app/title])
                      "and both read the title string")
                  (is (contains? don [::rf.hicasso.examples.slice.subs/t :app/title]))
                  (is (contains? don [:rf.route/query])
                      "and the roster reaches INPUTS, not just the values a body
                       asked for: neither page reads the route's query directly,
                       and it is here because `[::rf.hicasso.examples.slice.subs/page]` declares it as an input — so a
                       read hidden one layer down would still show up")

                  (is (empty? (set/difference don hic))
                      (str "the donor reads NOTHING the Hicasso arm does not — "
                           (pr-str (set/difference don hic))))

                  (is (not (contains? don [::rf.hicasso.examples.slice.subs/t :feed/empty]))
                      "in particular the empty-state string, which the Hicasso
                       body reads only in its empty branch and the seed is never
                       empty (rf2-9wmqd's repair)")
                  (is (not (contains? hic [::rf.hicasso.examples.slice.subs/t :feed/empty]))
                      "and the Hicasso arm does not read it either — so the two
                       agree by BOTH not reading it, rather than by the donor
                       having been matched to a read that was never taken")

                  (is (contains? hic [:rf.route/id])
                      "the shell's route read is on the Hicasso arm")
                  (is (not (contains? don [:rf.route/id]))
                      "and not on the donor's — the documented asymmetry, and it
                       runs in the denominator's favour"))
                (js/Promise.resolve nil)))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest the-roster-gate-catches-an-unconditional-branch-local-read
  (testing "THE SABOTAGE on the row above, and it is the audit's finding
           replanted rather than an invented fault: one UIx boundary on the
           donor frame reading `[::rf.hicasso.examples.slice.subs/t :feed/empty]` unconditionally,
           which is what `slice-donor-views/feed-page` did before the
           repair.

           The comparison must go from EMPTY to naming exactly that read.
           A green here — no difference detected with the read plainly
           added — would mean the row above passes whatever the two arms
           subscribe to."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-both-arms
              (fn []
                (is (empty? (donor-only))
                    "before the plant, the donor reads nothing extra")
                (with-probe-mounted
                  (fn []
                    (is (= #{[::rf.hicasso.examples.slice.subs/t :feed/empty]} (donor-only))
                        "and with one unconditional branch-local read added on
                         the donor frame, the comparison names it and nothing
                         else")
                    (js/Promise.resolve nil)))))
            (.then (fn [_] (done)) (fail-async done)))))))

;; ---------------------------------------------------------------------------
;; The pre-state, on the arms themselves
;; ---------------------------------------------------------------------------

(deftest establishing-a-pre-state-puts-the-arms-page-in-it
  (testing "The bridge between the replay rows and the instrument. Those
           rows assert a property of [[rf.bench.hicasso.slice-broad-clock-app/pre-state]], which is
           arithmetic; this one asserts that
           [[rf.bench.hicasso.slice-broad-clock-app/establish-pre-state!]] actually lands that arithmetic on
           the arm's own page, read back through the driver's own echo
           checks rather than through a second reader written here.

           Both parities of the visit index, on both frames, in both
           dimensions."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (-> (with-both-arms
              (fn []
                (rf.bench.hicasso.lane/chain
                  nil
                  (for [id    [:locale :donor-locale :theme :donor-theme :ctl-blocked]
                        visit [0 1]]
                    [(arm-of id) visit])
                  (fn [_ [arm visit]]
                    (.then (rf.bench.hicasso.slice-broad-clock-app/establish-pre-state! arm visit)
                           (fn [_]
                             (let [{:keys [locale theme]} (rf.bench.hicasso.slice-broad-clock-app/pre-state arm visit)
                                   side (:side arm)
                                   loc  (rf.bench.hicasso.slice-broad-clock-app/locale-echo-check side locale)
                                   thm  (rf.bench.hicasso.slice-broad-clock-app/theme-echo-check side theme)]
                               (is (= (:expect loc) (:rendered loc))
                                   (str (:id arm) " visit " visit ": the page's <h1> is in "
                                        locale))
                               (is (= (:want loc) (:glass loc))
                                   (str (:id arm) " visit " visit ": and its <select> agrees"))
                               (is (= (:expect thm) (:rendered thm))
                                   (str (:id arm) " visit " visit ": the root wears "
                                        theme "'s surface"))
                               nil)))))))
            (.then (fn [_] (done)) (fail-async done)))))))

(deftest the-whole-schedule-runs-and-every-window-advances-its-own-counter
  (testing "`rf.bench.hicasso.lane/rounds-async!` drives all six arms over the two real
           applications, every echo verifies, and — the half this file
           needs — each arm's visit counter has advanced exactly once per
           visit the plan made for it. That counter is the index
           [[rf.bench.hicasso.slice-broad-clock-app/pre-state]] is a function of, so a counter that
           advanced twice, or not at all, would leave the replay rows
           asserting a property of a schedule the driver does not run.

           The schedule is TINY here and the module's own is not: this row
           asks whether the instrument runs, and a run that reads it wants
           `rf.bench.hicasso.slice-broad-clock-app/sampling` and `rf.bench.hicasso.slice-broad-clock-app/rounds`."
    (if-not (browser?)
      (skip! off-browser)
      (async done
        (let [sampling {:warmup 1 :samples 2}
              rounds   1
              per-arm  (* rounds (+ (:warmup sampling) (:samples sampling)))]
          (-> (with-both-arms
                (fn []
                  (.then (rf.bench.hicasso.lane/rounds-async! rf.bench.hicasso.slice-broad-clock-app/arms sampling rounds rf.bench.hicasso.slice-broad-clock-app/measure-one!)
                         (fn [{:keys [readings samples]}]
                           (is (= (* rounds (:samples sampling) (count rf.bench.hicasso.slice-broad-clock-app/arms))
                                  (count samples))
                               "every measured visit was banked for the guard")
                           (is (= rounds (count readings)))
                           (is (every? (fn [round] (= (count rf.bench.hicasso.slice-broad-clock-app/arms) (count round)))
                                       readings)
                               "and every arm has a reading in every round")
                           (is (= {:writes (* per-arm (count rf.bench.hicasso.slice-broad-clock-app/arms)) :unverified 0}
                                  (rf.bench.hicasso.slice-broad-clock-app/verification))
                               "0 unverified of M — every window, warm-up included,
                                reached a frame that carried its own echo")
                           (is (= (into {} (map (fn [{:keys [id]}] [id per-arm])) rf.bench.hicasso.slice-broad-clock-app/arms)
                                  (rf.bench.hicasso.slice-broad-clock-app/visits))
                               "and every arm's visit counter advanced exactly once
                                per visit — warm-up visits included, because the
                                pre-state is established for those too")))))
              (.then (fn [_] (done)) (fail-async done))))))))

;; ---------------------------------------------------------------------------
;; The roster the file documents, where it can be pinned without a browser
;; ---------------------------------------------------------------------------

(deftest the-arm-roster-is-the-six-rows-the-file-documents
  (testing "The namespace docstring names six rows and says which estimands
           they can and cannot serve, and each row now also declares the
           SIDE it runs on and the state dimension it MOVES. A seventh
           added silently, or an arm whose declaration drifted from what
           its plan does, would leave that prose describing an instrument
           that no longer exists — the drift class this lane keeps paying
           for."
    (is (= [:idle-frame :locale :donor-locale :theme :donor-theme :ctl-blocked]
           (mapv :id rf.bench.hicasso.slice-broad-clock-app/arms))
        "floor first, so it leads the schedule")
    (is (= [:ctl-blocked] (mapv :id (filter :control? rf.bench.hicasso.slice-broad-clock-app/arms)))
        "and exactly one of them is a control")
    (is (= {:idle-frame   [:hicasso nil]
            :locale       [:hicasso :locale]
            :donor-locale [:donor   :locale]
            :theme        [:hicasso :theme]
            :donor-theme  [:donor   :theme]
            :ctl-blocked  [:hicasso :locale]}
           (into {} (map (juxt :id (juxt :side :alternates))) rf.bench.hicasso.slice-broad-clock-app/arms))
        "every arm declares its side and the dimension it moves")
    (doseq [[a b] compared-pairs]
      (is (= (:alternates (arm-of a)) (:alternates (arm-of b)))
          (str a " and " b " move the same dimension — otherwise there is
               nothing for a comparative to hold constant")))
    (is (= 2 (count rf.bench.hicasso.slice-broad-clock-app/locales)) "the rotor's roster is two locales")
    (is (= 2 (count rf.bench.hicasso.slice-broad-clock-app/themes)) "and two themes")
    (is (contains? (set rf.bench.hicasso.slice-broad-clock-app/locales) rf.bench.hicasso.slice-broad-clock-app/seed-locale))
    (is (contains? (set rf.bench.hicasso.slice-broad-clock-app/themes) rf.bench.hicasso.slice-broad-clock-app/seed-theme))
    (is (pos? (:warmup rf.bench.hicasso.slice-broad-clock-app/sampling)))
    (is (pos? (:samples rf.bench.hicasso.slice-broad-clock-app/sampling)))
    (is (even? (:samples rf.bench.hicasso.slice-broad-clock-app/sampling))
        "an ODD `:samples` would leave each arm's measured block one visit
         short of a whole number of rotor turns, so the two directions
         would not be taken in equal numbers")
    (is (pos? rf.bench.hicasso.slice-broad-clock-app/rounds))))

(deftest the-record-labels-which-population-each-figure-is-taken-over
  (testing "`:summary`, `:structure`, `:comparative`, `:over-floor` and
           `:resolution` are all taken over the measured visits, because
           each of the last three is built out of the first two and a ratio
           whose numerator and denominator are drawn from different
           populations is not a ratio.
           `:echo` deliberately is not: it is a count of refusals rather
           than a distribution, and a verification is worth more the more
           windows it covers."
    (is (= {:summary     :measured-visits
            :structure   :measured-visits
            :comparative :measured-visits
            :over-floor  :measured-visits
            :resolution  :measured-visits
            :echo        :all-visits}
           rf.bench.hicasso.slice-broad-clock-app/populations))))
