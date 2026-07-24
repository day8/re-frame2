(ns re-frame.freehand.bench.b2-dom-cljs-test
  "B2's mount storm, actually mounted.

  [[re-frame.freehand.bench.b2]] gates the exact number of ViewCell shells
  a mount of its roster omits, read off each declaration's manifest. That
  count is decidable without a mount and is deliberately taken without
  one — but it left the rest of D021's B2 row unowned. A cells-shaped
  mount storm, compared as interpreted, compiled-retained and
  compiled-elided, publishing mount time and RETAINED runtime shells, is a
  claim about a page; the static workload put no page on screen.

  This file mounts one. The same roster, at two scales, through four arms:

    - the capability-free roster INTERPRETED — every shell retained,
      because interpretation cannot prove a body sub-free;
    - the same roster COMPILED — every shell elided, by proof;
    - the three-read roster INTERPRETED and COMPILED — every shell
      retained in both, because the bodies really do read state.

  The free pair is the elision comparison and the read pair is the
  control: without the read pair, a runtime omission count of zero and a
  mis-targeted probe would be the same reading.

  ## Deterministic here; evidence there

    - DETERMINISTIC, and these gate: the number of occurrences of each
      declaration counted off `document`; the wrapper React actually
      reconciled for each declaration; the runtime omitted/retained shell
      totals folded from those two; their agreement with
      [[re-frame.freehand.bench.b2/omitted]] over the very plan that was
      mounted; and `innerHTML` equality between each arm and its twin, so
      the comparison is over matched observable output.
    - EVIDENCE, and this gates nothing: mount wall time. It is summarised
      as a distribution, published with its provenance, and has no route
      to a verdict — a mount-time threshold in a browser test is the
      folklore threshold D021 forbids, and would be measuring the CI
      runner.

  ## What the retained count is, and what it is not

  `total - manifest-omitted` is arithmetic on a static verdict, and
  calling it a runtime retained-object count would be a category error.
  The number published here is folded from two RUNTIME observations: how
  many occurrences of a declaration the browser actually built
  (`querySelectorAll`), and which wrapper React actually reconciled for
  it (the emitter's boundary cache, whose signature is minted when the
  component is). Neither factor is read off the manifest. The manifest's
  prediction is then required to agree with it, which is the cross-check
  rather than the source.

  ## Provenance, in a browser

  A browser has no environment and no git. `re-frame.freehand.bench.provenance`
  says what to do about that: a bundle that is going to be measured names
  its revision at compile time through the documented closure-define, and
  a bundle that does not cannot emit a citable record. This test build
  does not set it, so the record published below is complete in every
  field EXCEPT the revision — which is gated, so a record that lost its
  fixture scale, build mode or host would red. A release-lane run sets
  the define and the record becomes emittable with no change here.

  The records go to the browser console as EDN, which the browser runner
  buffers and flushes on failure or under `RF2_VERBOSE_TESTS=1`. A run
  that wants the evidence therefore asks for it:

      RF2_VERBOSE_TESTS=1 npm run test:browser

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b2 :as b2]
            [re-frame.freehand.bench.b2.interpreted :as b2i]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The fixture parameters — sizes, not language constants
;; ---------------------------------------------------------------------------

(def ^:private stress-scale
  "Per-type occurrence count for the mounted storm.

  Its own parameter, deliberately not the static workload's: that
  workload sums manifest verdicts and its size costs arithmetic, while
  this one builds every boundary in a real browser inside the suite's
  wall-clock budget. Both are fixture parameters and both are published,
  so no reader has to guess which storm a number came from."
  50)

(def ^:private small-scale
  "The small realistic case D021 requires beside every stress case."
  2)

(def ^:private samples 5)
(def ^:private warmup 1)

;; ---------------------------------------------------------------------------
;; The arms, and how an occurrence of each is recognised in the DOM
;; ---------------------------------------------------------------------------

(def ^:private free-selectors
  "One selector per capability-free declaration, in roster order — the
  outermost element each body renders, so a match is one occurrence."
  ["span.label" "section.card" "ul.list" "div.host"])

(def ^:private read-selectors
  "One selector per three-read declaration, in roster order."
  ["section.panel" "tr.row" "div.widget"])

(def ^:private arms
  "The four mounted arms.

  `:lowering` and `:view-cell` are the WRITTEN expectation each arm's
  runtime census is gated against — the wrapper signature React must be
  observed to have reconciled for every declaration in the roster. They
  are written here rather than derived from the declarations, because a
  gate that computes its expectation the way the subject computes its
  answer is not a gate."
  [{:id :free/interpreted :roster b2i/free-views :selectors free-selectors
    :lowering :interpreted :view-cell nil}
   {:id :free/compiled    :roster b2/free-views  :selectors free-selectors
    :lowering :compiled    :view-cell :elided}
   {:id :read/interpreted :roster b2i/read-views :selectors read-selectors
    :lowering :interpreted :view-cell nil}
   {:id :read/compiled    :roster b2/read-views  :selectors read-selectors
    :lowering :compiled    :view-cell :present}])

(def ^:private matched-pairs
  "Which arms must produce the same page. Promotion may change the
  wrapper; it may not change the DOM."
  [[:free/interpreted :free/compiled]
   [:read/interpreted :read/compiled]])

;; ---------------------------------------------------------------------------
;; The mount host
;; ---------------------------------------------------------------------------

(def ^:private fid :b2-dom/frame)

(v/defview storm
  "The storm: `scale` occurrences of every declaration in `roster`.

  Interpreted, and deliberately so — it is the harness, not an arm. One
  prop map serves every declaration because the rosters destructure
  disjoint keys, which keeps the occurrences of the four types identical
  across arms without a per-type fixture table nobody would keep matched."
  [{:keys [roster scale]}]
  [:div.storm
   (for [[t view] (map-indexed vector roster)]
     [:div.type {:key t}
      (for [i (range scale)]
        [view {:key i :text "leaf" :title "card" :items [0 1] :id i}])])])

(defn- register! []
  (rf/reg-sub :panel/total (fn [db [_ id]] (+ id (:base db))))
  (rf/reg-sub :panel/count (fn [db [_ id]] (- id (:base db))))
  (rf/reg-sub :row/value   (fn [db [_ id]] (* id (:base db))))
  (rf/reg-sub :widget/a    (fn [db [_ id]] (:base db)))
  (rf/reg-sub :widget/b    (fn [db [_ id]] id))
  (rf/reg-sub :widget/c    (fn [db _] (:base db)))
  (rf/reg-event :panel/act  (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :row/up     (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :row/down   (fn [{:keys [db]} _] {:db db})))

(defn- seed! []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {:base 2})
  fid)

;; ---------------------------------------------------------------------------
;; Mounting
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- release! [arm]
  (when arm
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (when-let [mounted (:mounted arm)]
      (.unmount (.-react-root ^root/Root mounted)))
    (.remove (:container arm)))
  nil)

(defn- mount-once!
  "One mount of `roster` at `scale`, timed.

  The two clock readings bracket the mount and nothing else: the census
  and every assertion happen afterwards, outside the reading, which is
  the same measurement-boundary discipline the structural arms keep."
  [roster scale tag]
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    (let [t0 (m/now-ms)]
      (-> (act #(v/mount [storm {:roster roster :scale scale}] container
                         {:frame fid :disambiguator tag}))
          (.then (fn [mounted]
                   {:container container :mounted mounted :ms (- (m/now-ms) t0)}))
          (.catch (fn [e] (.remove container) (js/Promise.reject e)))))))

(defn- sample-arm!
  "Mount and release the arm `(+ warmup samples)` times in turn, keeping
  the LAST mount live for the assertions and answering the measured
  mounts' wall times. Never two mounts at once: overlapping React `act`
  boundaries are unsupported, and a storm assembled inside one would be
  evidence about act's queue."
  [{:keys [id roster]} scale]
  (let [times (atom [])]
    (-> (reduce (fn [p i]
                  (.then p (fn [prev]
                             (release! prev)
                             (-> (mount-once! roster scale
                                              (keyword "b2" (str (namespace id) "-"
                                                                 (name id) "-" i)))
                                 (.then (fn [arm]
                                          (when (>= i warmup) (swap! times conj (:ms arm)))
                                          arm))))))
                (js/Promise.resolve nil)
                (range (+ warmup samples)))
        (.then (fn [live] (assoc live :samples @times))))))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

;; ---------------------------------------------------------------------------
;; The two runtime witnesses
;; ---------------------------------------------------------------------------

(defn- occurrences
  "How many occurrences of each declaration the BROWSER built — one
  `querySelectorAll` per roster entry, in roster order."
  [container selectors]
  (mapv #(.-length (.querySelectorAll container %)) selectors))

(defn- reconciled-wrapper
  "The wrapper React actually ran for `view`: the signature of the
  component the emitter minted and reconciled on, `[lowering view-cell]`.
  Read off the boundary cache, which is written when the component is
  built — so this is what happened, not what a manifest predicted."
  [view]
  (:signature (get (fr/boundary-cache) (:view-id (v/describe view)))))

(defn- shell-census
  "The arm's shell census: each declaration's mounted OCCURRENCE count,
  filed under the wrapper React reconciled for it.

  Both inputs are observations of a live page — occurrences counted off
  `document`, classified by the wrapper the emitter minted. Nothing here
  subtracts a manifest verdict from a mount count; the manifest's own
  prediction is compared against this total afterwards, which is the
  cross-check and not the source.

  What this is NOT: a count of live, allocated or retained ViewCell
  OBJECTS. It counts DOM occurrences and classifies each by the wrapper
  TYPE React selected for its declaration — an `-occurrences` count, not
  an object-liveness reading. Retained-object evidence (allocation,
  survival past unmount) would need a heap probe this bench deliberately
  does not carry, and its keys say `-occurrences` so no reader mistakes
  the one for the other."
  [container roster selectors]
  (reduce (fn [acc [view sel]]
            (let [n (.-length (.querySelectorAll container sel))]
              (if (= :elided (second (reconciled-wrapper view)))
                (update acc :omitted-shell-occurrences + n)
                (update acc :retained-shell-occurrences + n))))
          {:omitted-shell-occurrences 0 :retained-shell-occurrences 0}
          (map vector roster selectors)))

;; ---------------------------------------------------------------------------
;; The published record
;; ---------------------------------------------------------------------------

(defn evidence-record
  "The result record for one mounted arm — every provenance field D021
  requires that a browser can name, the mount-time distribution, and the
  wrapper-classified occurrence census as a published property.

  The census counts DOM occurrences classified by reconciled wrapper type;
  it is not a live-object reading, and `:census-method` in the fixture
  says so, so a reader never takes a `-occurrences` count for a
  retained-ViewCell-object count.

  Public because a record nobody can build outside the assertion that
  publishes it is a record nobody can check."
  [{:keys [id]} scale census counts times]
  {:benchmark    (keyword "B2" (str "mounted-" (namespace id) "-" (name id)))
   :doc          "a cells-shaped mount storm, mounted in a real browser"
   :revision     (prov/detect-revision)
   :fixture      {:arm                 id
                  :scale               scale
                  :occurrences         counts
                  :boundaries          (reduce + 0 counts)
                  :measurement-method
                  (str "wall time bracketing one v/mount inside a React act boundary, "
                       "host node to committed DOM")
                  :census-method
                  (str "each declaration's occurrence count off document, classified by "
                       "the wrapper signature React reconciled for it — wrapper-classified "
                       "DOM occurrences, NOT a count of live/allocated/retained ViewCell "
                       "objects; retained-object liveness evidence would need a heap probe "
                       "this bench does not carry and remains open")}
   :build        (prov/detect-build)
   :host         (prov/detect-host)
   :sampling     {:warmup warmup :samples (count times)}
   :baseline     {:kind      :interpreted-vs-compiled
                  :reference {:arm  :interpreted
                              :note (str "the interpreted arm of the same roster, mounted at "
                                         "the same scale into the same page")}}
   :distribution {:B2/mount-ms (assoc (m/summarise times)
                                      :doc        "wall time for one mount of the storm"
                                      :observable :duration-ms)}
   :properties   census
   :status       :evidence})

(defn- publish!
  "Write the record to the console as EDN, prefixed with whether it is
  citable. Evidence that is not attributable to a revision is still worth
  reading and is not worth citing, and the line says which it is.

  A citable record goes through `provenance/result` — the ONE documented
  emission door — so the same validation that governs the JVM lane governs
  a browser record too, rather than a bypass that console-logs whatever it
  was handed. A record missing only its revision (this build compiles none
  in) is printed as NOT CITABLE without forcing it through the door, which
  would throw; a release-lane build that sets the revision closure-define
  makes it citable with no change here."
  [record]
  (let [ds (prov/defects record)]
    (if (seq ds)
      (js/console.log
        (str ";; B2 mounted-storm evidence — NOT CITABLE ("
             (count ds) " provenance field(s) unnamed by this build)\n"
             (pr-str record)))
      (js/console.log
        (str ";; B2 mounted-storm evidence — citable\n"
             (pr-str (prov/result record)))))))

;; ===========================================================================
;; The storm, mounted — every deterministic claim at one scale
;; ===========================================================================

(defn- assert-arm!
  "Every deterministic claim about one mounted arm, plus its published
  evidence. Answers the arm's `innerHTML` so the matched-output check can
  compare twins."
  [{:keys [id roster selectors lowering view-cell] :as arm} scale live]
  (let [{:keys [container samples]} live
        counts  (occurrences container selectors)
        census  (shell-census container roster selectors)
        plan    (b2/plan roster scale)
        elides? (= :elided view-cell)
        total   (reduce + 0 counts)]
    (is (= (vec (repeat (count selectors) scale)) counts)
        (str id ": every declared occurrence mounted — " scale
             " of each of " (count selectors) " declarations, counted off document"))
    (doseq [[view sel] (map vector roster selectors)]
      (let [sig (reconciled-wrapper view)]
        (is (some? sig)
            (str id " / " sel ": React minted a boundary for this declaration"))
        (is (= [lowering view-cell] sig)
            (str id " / " sel ": React reconciled the wrapper this declaration earned"))))
    (is (= (if elides? total 0) (:omitted-shell-occurrences census))
        (str id ": the occurrences whose reconciled wrapper omitted the shell"))
    (is (= (if elides? 0 total) (:retained-shell-occurrences census))
        (str id ": the occurrences whose reconciled wrapper retained the shell"))
    (is (= (b2/omitted plan) (:omitted-shell-occurrences census))
        (str id ": the manifest's static prediction and the mounted page agree "
             "about how many occurrences omit the shell"))
    (publish! (evidence-record arm scale census counts samples))
    (.-innerHTML container)))

(defn- storm-at!
  [scale done]
  (register!)
  (seed!)
  (let [pages (atom {})]
    (-> (reduce (fn [p arm]
                  (.then p (fn [prev]
                             (release! prev)
                             (-> (sample-arm! arm scale)
                                 (.then (fn [live]
                                          (swap! pages assoc (:id arm)
                                                 (assert-arm! arm scale live))
                                          live))))))
                (js/Promise.resolve nil)
                arms)
        (.then (fn [live]
                 (release! live)
                 (doseq [[a b] matched-pairs]
                   (is (= (get @pages a) (get @pages b))
                       (str a " and " b
                            " built the same page — the arms differ by lowering, "
                            "so the comparison is over matched observable output")))
                 (is (every? pos? (map count (vals @pages)))
                     "and every arm built a page, rather than nothing at all")))
        (.catch (fn [e] (is false (str "a B2 arm's mount rejected: " e))))
        (.finally done))))

(deftest the-mount-storm-mounts-and-elides-at-stress-scale
  (testing "D021's B2 row as a mount rather than an arithmetic: the
            capability-free roster and the three-read roster, each in both
            lowerings, mounted at the storm scale. Every declared
            occurrence is counted off `document`; the wrapper React
            reconciled for each declaration is read back off the emitter's
            own cache; and the omitted-shell total those two produce is
            required to equal what the manifest predicted for the very
            plan that was mounted."
    (if-not (browser?)
      (skip! "the browser job runs the mount storm")
      (async done (storm-at! stress-scale done)))))

(deftest the-mount-storm-mounts-and-elides-at-small-scale
  (testing "The same storm at the small realistic size D021 requires
            beside every stress case, so the elision claim is not a
            property of one synthetic extreme."
    (if-not (browser?)
      (skip! "the browser job runs the mount storm")
      (async done (storm-at! small-scale done)))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-two-lowerings-really-do-disagree-about-the-shell
  (testing "Every count above is only evidence of elision if the arms
            disagree about whether a shell is needed. The compiled free
            roster claims `:elided` and carries a manifest; its
            interpreted twin carries none, and a mode with no analysis
            cannot elide anything. If those ever coincide, an omitted
            count of zero and an omitted count of everything become the
            same reading."
    (doseq [[c i] (map vector b2/free-views b2i/free-views)]
      (is (= :compiled (:lowering (v/describe c))) "the compiled roster is compiled")
      (is (= :interpreted (:lowering (v/describe i))) "and its twin is interpreted")
      (is (= :elided (:view-cell (v/manifest c))) "the compiled twin elides its shell")
      (is (nil? (v/manifest i)) "and the interpreted twin has no analysis to elide with"))
    (doseq [[c i] (map vector b2/read-views b2i/read-views)]
      (is (= :present (:view-cell (v/manifest c)))
          "a three-read declaration keeps its shell even compiled")
      (is (nil? (v/manifest i)) "and its interpreted twin keeps one too, unproven"))
    (is (pos? (b2/omitted (b2/plan b2/free-views stress-scale)))
        "the static prediction the mount is cross-checked against is not zero")
    (is (zero? (b2/omitted (b2/plan b2i/free-views stress-scale)))
        "and an interpreted plan predicts no omissions at all")))

(deftest the-published-record-names-everything-but-the-revision
  (testing "The published evidence is gated on its PROVENANCE, which is
            the one thing about a number that is deterministic. Every
            field D021 requires must be named — fixture scale, build
            mode, runtime, hardware class, sampling policy, baseline,
            distribution — and only the revision may be missing, because
            a browser has no git and this test build does not compile one
            in. A record that quietly lost its scale or its build mode
            would red here."
    (let [census {:omitted-shell-occurrences 8 :retained-shell-occurrences 0}
          record (evidence-record (first arms) small-scale census [2 2 2 2] [1.0 2.0 3.0])]
      (is (empty? (remove #(= [:revision] (:path %)) (prov/defects record)))
          "every provenance field a browser can name is named")
      (is (= 3 (:n (:B2/mount-ms (:distribution record))))
          "the distribution summarises the samples it was given")
      (is (= :duration-ms (:observable (:B2/mount-ms (:distribution record))))
          "and mount time is a duration — which is what makes it evidence and not a gate"))))
