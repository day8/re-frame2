(ns re-frame.freehand.bench.b6-mount-dom-cljs-test
  "B6's MOUNT row — Freehand against Reagent (and UIx) on one React floor.

  The question this file answers is the operator's, verbatim: *how fast is
  Freehand relative to Reagent?* Every earlier B-spine row compares
  Freehand to itself; this one puts a second and a third substrate on the
  same page, building the same DOM, into the same React, and prices all
  of them against the same hand-written `createElement` floor.

  ## Mount is only half, and it is the misleading half

  Reagent's design point is not mount. It is fine-grained ratom
  reactivity on UPDATE, and a mount-only answer would flatter whichever
  substrate happens to construct elements fastest while saying nothing
  about the thing Reagent is for.
  [[re-frame.freehand.bench.b6-update-dom-cljs-test]] is the other half,
  and neither is quotable without the other.

  ## What gates and what does not

    - **DETERMINISTIC, and these gate**: canonical-DOM equality across
      EVERY arm, and an element count against written arithmetic, at both
      the stress and the small size. That equality is the entire fairness
      guarantee of the comparison — without it, two arms are being timed
      while building two different pages, and the predecessor report
      records a first run in which exactly that was nearly believed.
    - **EVIDENCE, and this gates nothing**: the timings. D021 sets no
      threshold, and one set here would be measuring this workstation.
      The clock row asserts only that the timer moved.

  ## The honest reading of W2

  W2 is 300 leaf boundaries whose bodies read nothing. Freehand's
  compiled tier can PROVE that and drop the ViewCell; Reagent has no such
  concept, and neither does UIx. It is therefore the shape that maximally
  overstates Freehand's advantage, it is reported with that warning
  attached, and W1 and W3 are the headline shapes.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom/client" :as react-dom-client]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-floor :as floor]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-reagent :as rg]
            [re-frame.freehand.bench.b6-uix :as ux]
            [re-frame.freehand.bench.b6-witnesses :as fh]
            [re-frame.freehand.bench.b6-witnesses-compiled :as fhc]
            [re-frame.freehand.bench.provenance :as prov]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [uix.core :refer [$]]
            [uix.dom :as uix-dom]))

;; ---------------------------------------------------------------------------
;; Fixture sizes — parameters, not language constants
;; ---------------------------------------------------------------------------

(def ^:private w1-rows 300)
(def ^:private w1-small-rows 6)
(def ^:private w2-n 300)
(def ^:private w3-fields 12)

(def ^:private rounds 5)
(def ^:private sampling {:warmup 5 :samples 20})

(defn w1-elements
  "Three for the skeleton — the section, the heading and the list — then
  four per row: the row, its image, its label and its number. Arithmetic,
  so the gate is against a written expectation rather than against
  whatever the mount happened to produce."
  [rows]
  (+ 3 (* 4 rows)))

(defn w2-elements [n] (+ 1 n))

(defn w3-elements
  "Three for the skeleton — the form, the fieldset and the submit button —
  then four per field: the wrapper, the label, the input and the error
  line."
  [fields]
  (+ 3 (* 4 fields)))

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(defn- react-root-arm
  "An arm over a bare `react-dom/client` root — the floor and the UIx arm
  both mount this way, because neither has a mount door of its own."
  [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (react-dom-client/createRoot container)]
                (.render r (element-of props))
                r))
   :unmount (fn [r] (.unmount r))})

(defn- freehand-arm
  [id view]
  {:id      id
   :mount   (fn [container props n]
              (v/mount [view props] container
                       {:disambiguator (keyword "b6" (str (name id) "-" n))}))
   :unmount (fn [mounted] (v/unmount! mounted))})

(defn- reagent-arm
  "Reagent's own mount door — `reagent.dom.client/create-root` and
  `render`, which is what a Reagent application calls."
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (rdc/create-root container)]
                (rdc/render r (form-of props))
                r))
   :unmount (fn [r] (rdc/unmount r))})

(defn- uix-arm
  [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (uix-dom/create-root container)]
                (uix-dom/render-root (element-of props) r)
                r))
   :unmount (fn [r] (uix-dom/unmount-root r))})

(def ^:private witnesses
  "The three witnesses, each with its arms, its fixture and its written
  element arithmetic. A table, because the method is one method and three
  near-copies of a driver would be three places for it to drift."
  [{:id      :W1
    :doc     "a large template — 300 rows under one boundary each, rich attributes"
    :headline? true
    :props   {:rows w1-rows}
    :small   {:rows w1-small-rows}
    :elements (w1-elements w1-rows)
    :small-elements (w1-elements w1-small-rows)
    :arms    [(react-root-arm :floor       (fn [{:keys [rows]}] (floor/w1 rows)))
              (freehand-arm   :freehand-interpreted fh/w1)
              (freehand-arm   :freehand-compiled    fhc/w1)
              (reagent-arm    :reagent     (fn [{:keys [rows]}] [rg/w1 rows]))
              (uix-arm        :uix         (fn [{:keys [rows]}] ($ ux/w1 {:rows rows})))]}

   {:id      :W2
    :doc     "300 sub-free leaf boundaries — the ELISION-MAXIMISING shape"
    :headline? false
    :warning "Freehand's compiled tier proves these bodies sub-free and drops
              300 ViewCells. Reagent and UIx have no elision concept and cannot.
              This row therefore OVERSTATES Freehand's advantage and must not be
              quoted as the headline."
    :props   {:n w2-n}
    :small   {:n 6}
    :elements (w2-elements w2-n)
    :small-elements (w2-elements 6)
    :arms    [(react-root-arm :floor       (fn [{:keys [n]}] (floor/w2 n)))
              (freehand-arm   :freehand-interpreted fh/w2)
              (freehand-arm   :freehand-compiled    fhc/w2)
              (reagent-arm    :reagent     (fn [{:keys [n]}] [rg/w2 n]))
              (uix-arm        :uix         (fn [{:keys [n]}] ($ ux/w2 {:n n})))]}

   {:id      :W3
    :doc     "an ordinary 12-field form with controlled inputs — the shape most applications are made of"
    :headline? true
    :props   {:fields w3-fields}
    :small   {:fields 3}
    :elements (w3-elements w3-fields)
    :small-elements (w3-elements 3)
    :arms    [(react-root-arm :floor       (fn [{:keys [fields]}] (floor/w3 fields)))
              (freehand-arm   :freehand-interpreted fh/w3)
              (freehand-arm   :freehand-compiled    fhc/w3)
              (reagent-arm    :reagent     (fn [{:keys [fields]}] [rg/w3 fields]))
              (uix-arm        :uix         (fn [{:keys [fields]}] ($ ux/w3 {:fields fields})))]}])

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (root/reset-registry!) (fr/reset-boundaries!) (h/leave-act-environment!))
   :after  (fn [] (root/reset-registry!) (fr/reset-boundaries!))})

;; ===========================================================================
;; The fairness gate — canonical DOM, every arm, before any clock
;; ===========================================================================

(defn- assert-parity!
  [{:keys [id arms]} props expected-elements what]
  (let [{:keys [mounts canon counts agree? disagree]} (h/parity arms props)]
    (try
      (is agree?
          (str (name id) " / " what
               ": every arm built the SAME page, compared as canonical DOM"
               (when-not agree?
                 (str " — these disagreed with the floor: " (pr-str disagree)
                      "\nfloor: " (pr-str (subs (get canon :floor "") 0 (min 600 (count (get canon :floor "")))))
                      "\nother: " (pr-str (into {} (map (fn [k] [k (subs (get canon k "") 0 (min 600 (count (get canon k ""))))])) disagree))))))
      (doseq [[arm-id n] counts]
        (is (= expected-elements n)
            (str (name id) " / " what " / " (name arm-id)
                 ": built the " expected-elements " elements the witness's arithmetic predicts")))
      (is (pos? (count (get canon :floor ""))) "and it built a page, rather than nothing at all")
      (finally
        (doseq [m mounts] (h/release! m))))))

(deftest every-arm-builds-the-same-page-at-stress
  (testing "The fairness guarantee, stated first because nothing below it
            means anything without it: the React floor, interpreted
            Freehand, compiled Freehand, Reagent and UIx each mount their
            own declaration of the same three witnesses, and the browser
            builds ONE page from all five — compared as canonical DOM,
            with attribute names sorted so the comparison is of the DOM
            rather than of the browser's insertion-ordered serialiser."
    (if-not (h/browser?)
      (is true "a real browser mount is required — the browser job runs this row")
      (doseq [{:keys [props elements] :as w} witnesses]
        (assert-parity! w props elements "stress")))))

(deftest every-arm-builds-the-same-page-at-small
  (testing "The same equality at the small realistic size D021 requires
            beside every stress case, so parity is not a property of one
            synthetic extreme."
    (if-not (h/browser?)
      (is true "a real browser mount is required — the browser job runs this row")
      (doseq [{:keys [small small-elements] :as w} witnesses]
        (assert-parity! w small small-elements "small")))))

(deftest the-parity-comparison-can-fail
  (testing "A comparison nobody has watched answer false is not evidence
            that two things agree. The same arm at two DIFFERENT sizes,
            through the same mount path, compared the same way, must
            disagree — and if it ever does not, every equality in this
            file is passing for a reason that has nothing to do with the
            substrates."
    (if-not (h/browser?)
      (is true "a real browser mount is required — the browser job runs this row")
      (let [arm (freehand-arm :freehand-interpreted fh/w1)
            a   (h/mount-arm! arm {:rows 6})
            b   (h/mount-arm! arm {:rows 7})]
        (try
          (is (not= (h/canonical (:container a)) (h/canonical (:container b)))
              "one row's difference is visible to the comparison the parity row is made with")
          (is (= (h/canonical (:container a)) (h/canonical (:container a)))
              "and the comparison is stable on one page")
          (finally (h/release! a) (h/release! b)))))))

;; ===========================================================================
;; The clock — evidence, gated against nothing
;; ===========================================================================

(defn- measure-witness!
  [{:keys [id doc props arms headline? warning elements]}]
  (let [raw     (mapv (fn [_] (h/round! arms props sampling)) (range rounds))
        norm    (mapv #(h/normalise % :floor) raw)
        summary (h/across-rounds (mapv :ratio norm))]
    (h/publish!
      (str "mount / " (name id))
      {:benchmark    (keyword "B6" (str "mount-" (name id)))
       :doc          doc
       :headline?    headline?
       :warning      warning
       :revision     (prov/detect-revision)
       :build        (prov/detect-build)
       :host         (prov/detect-host)
       :fixture      {:witness  id
                      :props    props
                      :elements elements
                      :arms     (mapv :id arms)
                      :reagent-version "2.0.1"
                      :uix-version     "1.4.4"
                      :measurement-method
                      (str "wall time across react-dom/flushSync around each arm's own "
                           "mount door, into a fresh container attached before the window; "
                           "arms interleaved at the SAMPLE level with the order rotating "
                           "on the sample index; " rounds " rounds of "
                           (:warmup sampling) " warmup + " (:samples sampling)
                           " samples per arm per round; every figure a ratio to the FLOOR "
                           "measured in that same round, because this workstation drifts "
                           "further across rounds than several of the effects being measured")}
       :sampling     sampling
       :baseline     {:kind      :cross-substrate
                      :reference {:arm  :floor
                                  :note "the same DOM built by hand with react/createElement
                                         and no substrate at all"}}
       :per-round    {:p50   (mapv :p50 norm)
                      :ratio (mapv :ratio norm)}
       :ratio-to-floor summary
       :status       :evidence})
    {:id id :summary summary :norm norm}))

(deftest mount-cost-against-the-shared-react-floor
  (testing "The measurement itself: five interleaved rounds per witness,
            every arm's p50 divided by the floor's p50 in the same round.
            Published as a distribution with provenance and a per-round
            range, and asserted against NOTHING except that the timer
            moved and the floor did not somehow come out at zero — D021
            sets no threshold and one set here would be measuring this
            box."
    (if-not (h/browser?)
      (is true "a real browser mount is required — the browser job runs this row")
      (doseq [w witnesses]
        (let [{:keys [summary norm]} (measure-witness! w)]
          (is (= rounds (count norm))
              (str (name (:id w)) ": every round produced a reading"))
          (is (every? (fn [{:keys [p50]}] (pos? (get p50 :floor)))
                      norm)
              (str (name (:id w)) ": the floor arm took measurable time in every round"))
          (is (= 1.0 (get-in summary [:floor :mean]))
              (str (name (:id w)) ": the floor is its own calibrator and normalises to exactly 1.0"))
          (is (every? #(pos? (:mean %)) (vals summary))
              (str (name (:id w)) ": every arm produced a positive ratio")))))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-arms-really-are-five-substrates
  (testing "A cross-substrate ratio is only a cross-substrate ratio if the
            arms are different substrates. The two Freehand arms are two
            lowerings of one declaration; the Reagent and UIx arms are
            plain functions of their own libraries; the floor is neither."
    (is (= :interpreted (:lowering (v/describe fh/w1)))
        "the Freehand reference arm is genuinely interpreted")
    (is (= :compiled (:lowering (v/describe fhc/w1)))
        "and the compared Freehand arm is genuinely compiled")
    (is (not= (:view-id (v/describe fh/w1)) (:view-id (v/describe fhc/w1)))
        "they are two declarations, in two namespaces")
    (is (false? (v/view? rg/w1)) "the Reagent arm is NOT a Freehand declaration")
    (is (fn? rg/w1) "it is an ordinary function returning Hiccup, as a Reagent user writes")
    (is (= 1203 (w1-elements w1-rows)) "W1's element arithmetic is arithmetic, not a recorded observation")
    (is (= 301 (w2-elements w2-n)) "and so is W2's")
    (is (= 51 (w3-elements w3-fields)) "and so is W3's")))
