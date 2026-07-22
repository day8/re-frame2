(ns re-frame.freehand.bench.measure-cljs-test
  "D021's split, checked at the door that constructs a measurement.

  The falsifiability suite proves the split holds END TO END — a violated
  property reds, a moved timing does not. These tests prove the narrower
  thing that makes that possible: a fixture author cannot file a timing
  as a gate, and cannot let a deterministic property degrade to
  advisory, because the construction refuses both."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.bench.measure :as m]))

(defn- defect
  "The `:bench/defect` a rejected spec throws with, or nil when the spec
  was accepted."
  [spec]
  (try
    (m/measurement spec)
    nil
    (catch #?(:clj Exception :cljs :default) e
      (or (:bench/defect (ex-data e)) :threw-without-a-defect))))

(defn- message
  [spec]
  (try (m/measurement spec) nil
       (catch #?(:clj Exception :cljs :default) e (ex-message e))))

(def ^:private a-gate
  {:id :test/omitted-cells :doc "the exact omitted-ViewCell count" :observable :count :expect 26})

(def ^:private an-evidence
  {:id :test/self-time-ms :doc "substrate self time" :observable :duration-ms})

;; ---------------------------------------------------------------------------
;; The vocabulary IS the ruling
;; ---------------------------------------------------------------------------

(deftest enforcement-is-a-total-function-of-the-observable
  (testing "D021: deterministic properties gate; wall-clock and byte
            distributions are evidence. Two classes, two enforcements,
            and no third answer for an author to reach for."
    (is (= {:count :gate :flag :gate :value :gate
            :duration-ms :evidence :bytes :evidence}
           (into {} (map (juxt key #(m/enforcement-for (key %)))) m/observables)))
    (is (nil? (m/enforcement-for :vibes)))))

(deftest an-unknown-observable-is-refused
  (testing "the observable decides gate-versus-evidence, so it cannot be
            an open vocabulary — an unrecognised one has no enforcement
            to derive."
    (is (= :measurement-observable (defect (assoc a-gate :observable :feels-fast))))
    (is (= :measurement-id (defect (assoc a-gate :id :unqualified))))
    (is (= :measurement-doc (defect (dissoc a-gate :doc))))))

;; ---------------------------------------------------------------------------
;; Direction 1 — a timing measurement cannot be registered as a gate
;; ---------------------------------------------------------------------------

(deftest a-timing-cannot-be-registered-as-a-gate
  (testing "restating a duration or a byte count as a gate is refused,
            naming both what was stated and what the observable compels."
    (doseq [observable [:duration-ms :bytes]]
      (let [spec {:id :test/thing :doc "a magnitude" :observable observable :enforcement :gate}]
        (is (= :enforcement-restated (defect spec)))
        (is (re-find #"published evidence" (message spec)))))))

(deftest a-threshold-on-evidence-is-refused
  (testing "D021 sets no numeric threshold on wall-clock or byte
            distributions. Every pass/fail-shaped key is refused on
            evidence — that is the folklore-threshold non-goal, made
            mechanical."
    (doseq [k (sort m/pass-fail-keys)]
      (is (= :threshold-on-evidence (defect (assoc an-evidence k 12)))
          (str "an evidence measurement carrying " k " was accepted")))
    (is (re-find #"attributed and dispositioned by a human"
                 (message (assoc an-evidence :max-ms 12))))))

(deftest evidence-has-no-route-to-a-verdict
  (testing "even holding a normalised evidence measurement, there is no
            call that turns its observation into a pass/fail."
    (let [ev (m/measurement an-evidence)]
      (is (m/evidence? ev))
      (is (not (m/gate? ev)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (m/gate-failure ev 999999.0)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (m/instability-failure ev [1.0 2.0]))))))

;; ---------------------------------------------------------------------------
;; Direction 2 — a deterministic property cannot degrade to advisory
;; ---------------------------------------------------------------------------

(deftest a-property-cannot-be-registered-as-evidence
  (testing "the direction people forget: a correctness regression hiding
            behind benchmark noise because someone relabelled its gate."
    (doseq [observable [:count :flag :value]]
      (let [spec {:id :test/thing :doc "a property" :observable observable
                  :expect 1 :enforcement :evidence}]
        (is (= :enforcement-restated (defect spec)))
        (is (re-find #"hard release gates" (message spec)))))))

(deftest a-gate-without-an-expectation-is-refused
  (testing "a gate with nothing to violate is advisory wearing a gate's
            name."
    (is (= :gate-without-expectation (defect (dissoc a-gate :expect))))
    (is (re-find #"advisory wearing" (message (dissoc a-gate :expect))))))

(deftest a-gate-names-itself-when-it-fails
  (testing "criterion 2's requirement in the small: the failure carries
            the measurement id, its one-line claim, the expectation and
            the observation."
    (let [g (m/measurement a-gate)]
      (is (nil? (m/gate-failure g 26)))
      (let [f (m/gate-failure g 25)]
        (is (= :test/omitted-cells (:measurement f)))
        (is (= 26 (:expected f)))
        (is (= 25 (:observed f)))
        (is (re-find #"the exact omitted-ViewCell count" (:message f)))
        (is (re-find #":test/omitted-cells" (:message f)))))))

(deftest a-predicate-expectation-works-too
  (testing "an exact value is the common case; a predicate is the
            escape hatch, and reports as one."
    (let [g (m/measurement (assoc a-gate :expect even?))]
      (is (nil? (m/gate-failure g 26)))
      (is (some? (m/gate-failure g 25)))
      (is (re-find #"the declared predicate" (:message (m/gate-failure g 25)))))))

(deftest a-property-that-is-not-deterministic-cannot-gate
  (testing "either the workload has an undeclared input, or the
            observable was misfiled and is really a distribution."
    (let [g (m/measurement a-gate)]
      (is (nil? (m/instability-failure g [26 26 26])))
      (let [f (m/instability-failure g [26 26 25])]
        (is (= :test/omitted-cells (:measurement f)))
        (is (= [26 25] (:observed f)))
        (is (re-find #"distinct values" (:message f)))))))

;; ---------------------------------------------------------------------------
;; Normalisation and summary
;; ---------------------------------------------------------------------------

(deftest normalisation-is-idempotent
  (testing "a workload is normalised at registration and again at run
            time; measuring the harness twice would be a fine way to
            publish a wrong number."
    (let [once (m/measurement a-gate)]
      (is (= once (m/measurement once)))
      (is (= :gate (:enforcement once))))))

(deftest a-distribution-is-summarised-never-judged
  (testing "D021: a median without its distribution is not release
            evidence."
    (let [s (m/summarise [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0 10.0])]
      (is (= 10 (:n s)))
      (is (= 1.0 (:min s)))
      (is (= 5.0 (:p50 s)))
      (is (= 10.0 (:p95 s)))
      (is (= 10.0 (:max s)))
      (is (= 5.5 (:mean s)))
      (is (= #{:n :min :p50 :p95 :p99 :max :mean} (set (keys s)))
          "a summary carries no verdict, no budget and no comparator"))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/summarise [])))))

(deftest the-clock-resolves-better-than-a-millisecond
  (testing "a harness whose clock could not see a view render would
            publish a distribution of rounding error."
    (let [t0 (m/now-ms)
          _  (dotimes [_ 1000] (reduce + (range 100)))
          t1 (m/now-ms)]
      (is (number? t0))
      (is (>= t1 t0)))))
