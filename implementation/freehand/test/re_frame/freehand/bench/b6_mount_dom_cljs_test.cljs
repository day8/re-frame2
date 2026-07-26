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
  substrate constructs elements fastest while saying nothing about the
  thing Reagent is for.
  [[re-frame.freehand.bench.b6-update-dom-cljs-test]] is the other half,
  and neither is quotable without the other.

  ## What gates here, and what does not

    - **DETERMINISTIC, and these gate**: canonical-DOM equality across
      EVERY arm, and an element count against written arithmetic, at both
      the stress and the small size. That equality is the entire fairness
      guarantee of the comparison — without it, two arms are being timed
      while building two different pages, and the predecessor report
      records a first run in which exactly that was nearly believed.
    - **EVIDENCE, and this gates nothing**: the timings. D021 sets no
      threshold, and one set here would be measuring this workstation.

  ## The numbers this file takes are not the published ones

  This build is `:optimizations :none` with `goog.DEBUG true`, so Spec 009
  instrumentation, schema validation and trace emission are all live —
  and Reagent has no counterpart to any of them, so a development build
  systematically penalises Freehand. The reading that goes in the report
  is taken by [[re-frame.freehand.bench.b6-prod-app]] under `:advanced`
  with `goog.DEBUG false`. What this file is for is the gate: proving,
  every time the browser suite runs, that the five arms still build one
  page.

  ## The honest reading of W2

  W2 is 300 leaf boundaries whose bodies read nothing. Freehand's
  compiled tier can PROVE that and drop the ViewCell; Reagent has no such
  concept, and neither does UIx. It is therefore the shape that maximally
  overstates Freehand's advantage, it is reported with that warning
  attached, and W1 and W3 are the headline shapes.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-reagent :as rg]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b6-witnesses :as fh]
            [re-frame.freehand.bench.b6-witnesses-compiled :as fhc]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]))

(def ^:private rounds 3)
(def ^:private sampling {:warmup 3 :samples 10})

(use-fixtures :each
  {:before (fn [] (root/reset-registry!) (fr/reset-boundaries!) (h/leave-act-environment!))
   :after  (fn [] (root/reset-registry!) (fr/reset-boundaries!))})

;; ===========================================================================
;; The fairness gate — canonical DOM, every arm, before any clock
;; ===========================================================================

(defn- assert-parity!
  [{:keys [id] :as witness} props expected-elements what]
  (let [{:keys [mounts canon counts agree? disagree]} (rows/mount-parity witness props)]
    (try
      (is agree?
          (str (name id) " / " what
               ": every arm built the SAME page, compared as canonical DOM"
               (when-not agree?
                 (str " — these disagreed with the floor: " (pr-str disagree)))))
      (doseq [[arm-id n] counts]
        (is (= expected-elements n)
            (str (name id) " / " what " / " (name arm-id)
                 ": built the " expected-elements
                 " elements the witness's arithmetic predicts")))
      (is (pos? (count (get canon :floor "")))
          "and it built a page, rather than nothing at all")
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
      (doseq [{:keys [props elements] :as w} rows/mount-witnesses]
        (assert-parity! w props elements "stress")))))

(deftest every-arm-builds-the-same-page-at-small
  (testing "The same equality at the small realistic size D021 requires
            beside every stress case, so parity is not a property of one
            synthetic extreme."
    (if-not (h/browser?)
      (is true "a real browser mount is required — the browser job runs this row")
      (doseq [{:keys [small small-elements] :as w} rows/mount-witnesses]
        (assert-parity! w small small-elements "small")))))

(deftest the-parity-comparison-can-fail
  (testing "A comparison nobody has watched answer false is not evidence
            that two things agree. The same witness at two DIFFERENT sizes,
            compared the same way, must disagree — and if it ever does
            not, every equality in this file is passing for a reason that
            has nothing to do with the substrates."
    (if-not (h/browser?)
      (is true "a real browser mount is required — the browser job runs this row")
      (let [w  (first rows/mount-witnesses)
            a  (rows/mount-parity w {:rows 6})
            b  (rows/mount-parity w {:rows 7})]
        (try
          (is (not= (:reference a) (:reference b))
              "one row's difference is visible to the comparison the parity row is made with")
          (is (true? (:agree? a)) "and both sizes are internally consistent across arms")
          (is (true? (:agree? b)) "at the other size too")
          (finally
            (doseq [m (:mounts a)] (h/release! m))
            (doseq [m (:mounts b)] (h/release! m))))))))

;; ===========================================================================
;; The clock — evidence, gated against nothing
;; ===========================================================================

(deftest mount-cost-against-the-shared-react-floor
  (testing "The measurement, taken here only to prove the instrument runs
            and reports — the PUBLISHED numbers come from the `:advanced`
            production entry, because this build's instrumentation is live
            and Reagent has no counterpart to it. Asserted against NOTHING
            except that the timer moved and the floor normalises to
            itself; D021 sets no threshold and one set here would be
            measuring this box."
    (if-not (h/browser?)
      (is true "a real browser mount is required — the browser job runs this row")
      (doseq [w rows/mount-witnesses]
        (let [{:keys [summary norm]} (rows/measure-mount! w rounds sampling)]
          (is (= rounds (count norm))
              (str (name (:id w)) ": every round produced a reading"))
          (is (every? (fn [{:keys [p50]}] (pos? (get p50 :floor))) norm)
              (str (name (:id w)) ": the floor arm took measurable time in every round"))
          (is (= 1.0 (get-in summary [:floor :mean]))
              (str (name (:id w)) ": the floor is its own calibrator and normalises to 1.0"))
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
    (is (= 1203 (rows/w1-elements rows/w1-rows))
        "W1's element arithmetic is arithmetic, not a recorded observation")
    (is (= 301 (rows/w2-elements rows/w2-n)) "and so is W2's")
    (is (= 51 (rows/w3-elements rows/w3-fields)) "and so is W3's")))
