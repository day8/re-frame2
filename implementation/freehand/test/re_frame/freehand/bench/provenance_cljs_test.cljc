(ns re-frame.freehand.bench.provenance-cljs-test
  "D021 §Release evidence artifact — a result record cannot be emitted
  without its provenance.

  The headline test omits each required field IN TURN, enumerating the
  ledger rather than restating it: a field added to
  `provenance/required-fields` is covered the moment it is added, and a
  field silently dropped from the ledger takes its own coverage with it,
  which is visible in the count assertion below."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.bench.provenance :as prov]))

(defn- complete
  "A result record that names everything D021 requires."
  []
  {:benchmark    :test/complete
   :revision     "0123456789abcdef0123456789abcdef01234567"
   :fixture      {:rows 200}
   :build        {:optimizations :advanced :instrumentation? false}
   :host         {:runtime "Node v24.0.0" :hardware-class :release-worker}
   :sampling     {:warmup 5 :samples 40}
   :baseline     {:kind :interpreted-vs-compiled :reference {:arm :interpreted}}
   :distribution {:test/self-time-ms {:n 40 :p50 1.0 :p95 2.0}}
   :status       :evidence})

(defn- dissoc-path
  [m path]
  (if (= 1 (count path))
    (dissoc m (first path))
    (update-in m (butlast path) dissoc (last path))))

(defn- rejected?
  [record]
  (try
    (prov/result record)
    false
    (catch #?(:clj Exception :cljs :default) e
      (:defects (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Criterion 1 — omit each provenance field in turn
;; ---------------------------------------------------------------------------

(deftest a-complete-record-is-emitted
  (testing "the fixture below is only meaningful if the COMPLETE record
            passes — otherwise every omission test is vacuous."
    (is (= (complete) (prov/result (complete))))
    (is (empty? (prov/defects (complete))))))

(deftest every-required-field-is-load-bearing
  (testing "D021: every stored result names the source revision, build
            mode, browser/runtime, hardware class, warm-up/sample policy,
            fixture parameters, and whether dev instrumentation was
            enabled. Omit each in turn; each must be refused."
    (doseq [{:keys [path names]} prov/required-fields]
      (let [record  (dissoc-path (complete) path)
            defects (rejected? record)]
        (is defects
            (str "a record missing " (pr-str path) " (" names ") was emitted anyway"))
        (when defects
          (is (= [{:defect :missing :path path}]
                 (mapv #(select-keys % [:defect :path]) defects))
              (str "the refusal for " (pr-str path) " must name that field and only that field")))))))

(deftest the-ledger-covers-the-whole-of-d021s-obligation
  (testing "the eight things D021 names — revision, fixture parameters,
            build/instrumentation mode, browser/runtime, hardware class,
            warm-up/sample policy, distribution, baseline — plus the
            workload id, each addressed by path."
    (is (= [[:benchmark]
            [:revision]
            [:fixture]
            [:build :optimizations]
            [:build :instrumentation?]
            [:host :runtime]
            [:host :hardware-class]
            [:sampling :warmup]
            [:sampling :samples]
            [:distribution]
            [:baseline :kind]
            [:baseline :reference]]
           prov/all-paths))))

(deftest the-refusal-message-names-every-field-that-failed
  (testing "a refusal a reader cannot act on is a stack trace. The
            message names each field, what was expected, and why."
    (let [record  (-> (complete) (dissoc :revision) (update :host dissoc :hardware-class))
          defects (rejected? record)
          msg     (try (prov/result record)
                       (catch #?(:clj Exception :cljs :default) e (ex-message e)))]
      (is (= 2 (count defects)))
      (is (re-find #"\[:revision\]" msg))
      (is (re-find #"\[:host :hardware-class\]" msg))
      (is (re-find #"not release evidence" msg)))))

;; ---------------------------------------------------------------------------
;; A present-but-wrong field is refused too
;; ---------------------------------------------------------------------------

(deftest a-present-but-meaningless-field-is-refused
  (testing "`missing` is about the OBLIGATION, not the key. A blank
            revision, an empty fixture, a nil instrumentation flag and an
            unnamed baseline each name nothing."
    (doseq [[path value] [[[:revision] "   "]
                          [[:fixture] {}]
                          [[:build :instrumentation?] nil]
                          [[:host :hardware-class] "the-machine-under-my-desk"]
                          [[:sampling :samples] 0]
                          [[:distribution] {}]
                          [[:baseline :kind] :faster-than-last-time]
                          [[:baseline :reference] {}]]]
      (let [defects (rejected? (assoc-in (complete) path value))]
        (is defects (str (pr-str path) " = " (pr-str value) " was accepted"))
        (when defects
          (is (= [path] (mapv :path defects))))))))

(deftest the-four-named-baselines-are-the-whole-vocabulary
  (testing "D021 §Baselines. A result with no comparator is a number."
    (is (= [:absorbed-vs-donor
            :before-vs-after
            :interpreted-vs-compiled
            :self-vs-end-to-end]
           (vec (sort (keys prov/baselines)))))))

;; ---------------------------------------------------------------------------
;; Detection — never a literal
;; ---------------------------------------------------------------------------

(deftest the-environment-is-detected-not-written-down
  (testing "hardware class is configured or detected. A benchmark record
            that hardcodes the machine it was written on is a record
            about nothing."
    (let [host (prov/detect-host)]
      (is (keyword? (:hardware-class host)))
      (is (string? (:runtime host)))
      (is (seq (:runtime host))))
    (is (keyword? (prov/detect-hardware-class)))
    (when-not (prov/env "RF2_HARDWARE_CLASS")
      (is (contains? #{:developer-workstation :shared-ci-runner}
                     (prov/detect-hardware-class))
          "unconfigured, the class is detected from the environment — never a machine name"))
    (let [build (prov/detect-build)]
      (is (keyword? (:optimizations build)))
      (is (boolean? (:instrumentation? build))))))
