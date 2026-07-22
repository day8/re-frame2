(ns re-frame.freehand.bench.falsifiability-cljs-test
  "The B-spine's own gate: D021's asymmetry, proven in both directions.

  This is the deterministic property the harness itself is judged on, and
  it is the stable subset that runs in PR smoke — it needs no browser, no
  pinned worker and no calibration, because it asserts about EXIT CODES
  rather than about milliseconds.

  Criterion 3 is the one that gets skipped, so it is asserted twice here:
  once that a wildly moved wall-clock distribution still exits 0, and
  once that the distribution really did move. A harness that fails on a
  slow run has silently become a threshold, which D021 forbids; a proof
  that an unchanged timing did not red the build proves nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.falsifiability :as falsifiability]
            [re-frame.freehand.bench.runner :as runner]))

(def ^:private provenance
  "The revision a TEST supplies.

  A ClojureScript test process is not a build pipeline: no environment
  variable, no git, and nothing it could truthfully detect. That it
  cannot emit a record without being told is criterion 1 working, and is
  asserted directly in the provenance suite — so here the revision is
  simply supplied and the subject stays the gate/evidence asymmetry."
  {:revision "test-fixture-revision"})

;; ---------------------------------------------------------------------------
;; Criterion 2 — a deterministic property registered as a GATE reds
;; ---------------------------------------------------------------------------

(deftest a-violated-deterministic-property-reds-and-names-itself
  (testing "the deliberately violating fixture must fail, naming the
            property — a gate failure that cannot say what broke is a
            stack trace, not a release signal."
    (let [{:keys [exit-code gate-failures results]}
          (bench/run [falsifiability/violated-gate] provenance)
          failure (first gate-failures)]
      (is (= 1 exit-code))
      (is (= 1 (count gate-failures)))
      (is (= :falsifiability/node-count (:measurement failure)))
      (is (re-find #"the exact node count" (:message failure)))
      (is (re-find #"GATE FAILED" (:message failure)))
      (is (false? (get-in (first results) [:properties :falsifiability/node-count :pass?]))))))

(deftest a-property-that-holds-does-not-red
  (testing "non-vacuity for criterion 2: a harness that reds on
            everything would pass the test above and be worthless."
    (let [{:keys [exit-code gate-failures]} (bench/run [falsifiability/honoured-gate] provenance)]
      (is (= 0 exit-code))
      (is (empty? gate-failures)))))

;; ---------------------------------------------------------------------------
;; Criterion 3 — a timing registered as EVIDENCE never reds, however it moves
;; ---------------------------------------------------------------------------

(deftest a-wildly-moved-timing-still-exits-zero
  (testing "the same work done 50x over: the wall-clock distribution
            moves by that multiple and the run still exits 0. D021 sets
            no numeric threshold on timing — an adverse trend is
            attributed and dispositioned, never failed automatically."
    (let [{:keys [exit-code gate-failures results]} (bench/run [falsifiability/moved-timing] provenance)
          record (first results)]
      (is (= 0 exit-code))
      (is (empty? gate-failures))
      (is (pos? (get-in record [:distribution :falsifiability/render-ms :p50]))
          "the moved arm published a distribution")
      (is (true? (get-in record [:properties :falsifiability/node-count :pass?]))
          "the deterministic property is unchanged by the timing knob — which is
           what makes this arm a test of the timing and not of the property"))))

(deftest the-moved-timing-really-moved
  (testing "criterion 3's non-vacuity. Without this the assertion above
            is satisfied by a timing that did not change."
    (let [proof (falsifiability/prove provenance)]
      (is (number? (:move proof)))
      (is (<= 10 (:move proof))
          (str "the moved arm's p50 was only " (:move proof)
               "x the baseline's — nothing was proven about a wildly changed timing")))))

;; ---------------------------------------------------------------------------
;; Both directions at once — the harness's own gate
;; ---------------------------------------------------------------------------

(deftest the-asymmetry-holds-in-both-directions
  (testing "D021's whole point, as one assertion."
    (let [{:keys [arms proven? defects]} (falsifiability/prove provenance)]
      (is (empty? defects))
      (is proven?)
      (is (= 0 (get-in arms [:honoured-gate :exit-code])))
      (is (= 1 (get-in arms [:violated-gate :exit-code])))
      (is (= 0 (get-in arms [:baseline-timing :exit-code])))
      (is (= 0 (get-in arms [:moved-timing :exit-code]))))))

;; ---------------------------------------------------------------------------
;; The CLI carries the same contract to the process boundary
;; ---------------------------------------------------------------------------

(deftest the-cli-exit-code-is-the-run-outcome
  (testing "`-main` prints and exits; everything else about the CLI is
            this pure function, so the exit-code contract is testable
            without a process."
    (let [proof (runner/run-cli ["--prove"] provenance)]
      (is (= 0 (:exit-code proof)))
      (is (true? (get-in proof [:report :proven?])))
      (is (some #(re-find #"PROVEN both ways" %) (:summary proof))))
    (let [suite (runner/run-cli [] provenance)]
      (is (= 0 (:exit-code suite))
          "an empty evidence suite is honest, not red: B1-B5 arrive with their slices")
      (is (= :suite (get-in suite [:report :command]))))
    (let [help (runner/run-cli ["--help"] provenance)]
      (is (= 0 (:exit-code help)))
      (is (some #(re-find #"deterministic property was violated" %) (:summary help))))))

(deftest the-summary-rides-as-edn-comments
  (testing "stdout is both the thing a reviewer reads and the artefact a
            release cites, so the summary must never break the EDN."
    (let [{:keys [summary]} (runner/run-cli ["--prove"] provenance)]
      (is (seq summary))
      (is (every? string? summary))
      (is (not-any? #(re-find #"\r?\n" %) summary)
          "a multi-line summary line would escape its `;;` prefix"))))

;; ---------------------------------------------------------------------------
;; The proof arms are not evidence about Freehand
;; ---------------------------------------------------------------------------

(deftest the-proof-arms-stay-out-of-the-standing-suite
  (testing "one of them fails on purpose, and none of them measures
            anything a release should cite."
    (is (empty? (bench/registered)))))
