(ns re-frame.freehand.bench.node-lane-cljs-test
  "The ClojureScript evidence lane, checked as a LANE.

  The React arm's own counters are checked next door
  (`re-frame.freehand.bench.b1-react-cljs-test`). What is checked here is
  the thing that suite cannot see: whether the workloads it exercises are
  reachable from a command anyone can run, and whether that command
  notices when they stop being.

  The distinction is the whole reason this file exists. A workload
  registers itself at load, so a test namespace that requires it directly
  makes it present in the test registry whether or not any RUNNER requires
  it — which is exactly how two registered React workloads came to have
  full test coverage and no runnable lane at all. So the guard below is
  tested against a registry handed to it, including registries that are
  deliberately missing an arm, rather than against the ambient one only."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            ;; The ENTRY, and deliberately not the workload namespace. If
            ;; this require were `…bench.b1-react`, every assertion below
            ;; would hold with the lane's own require deleted.
            [re-frame.freehand.bench.node :as node]))

(def ^:private provenance
  "The revision a TEST supplies. A ClojureScript test process is not a
  build pipeline: no environment variable, no git, and nothing it could
  truthfully detect. The published run detects its own."
  {:revision "test-fixture-revision"})

;; ---------------------------------------------------------------------------
;; The lane is whole
;; ---------------------------------------------------------------------------

(deftest the-lane-names-the-two-react-workloads
  (testing "The roster the entry publishes, transcribed here and read off
            the arm there. The entry reads its ids from the workload
            namespace so the require cannot become decorative; this is the
            other end of that — an arm that quietly renamed a workload
            would leave the entry compiling and this sentence failing with
            both spellings in it."
    (is (= [:B1/react-template :B1/react-small-template] node/lane-workloads))))

(deftest the-node-entry-loads-the-cljs-only-workloads
  (testing "Requiring the entry is what puts the ClojureScript-only
            workloads in the registry — that is the entry's whole job, and
            the assertion is over the registry the entry produced rather
            than over its require form."
    (is (= [] (node/absent-lane-workloads (bench/registered)))
        (str "the entry namespace is loaded, so every id in "
             (pr-str node/lane-workloads) " should be registered"))))

(deftest the-guard-names-the-arm-that-stopped-registering
  (testing "The guard, driven from the failing side. An empty registry
            loses both arms and says so in declaration order; a registry
            missing one loses exactly that one. This is what makes the
            check above non-vacuous: the guard is demonstrated to answer
            differently when the lane is broken."
    (is (= node/lane-workloads (node/absent-lane-workloads {}))
        "a registry holding nothing is missing every lane workload")
    (doseq [id node/lane-workloads]
      (is (= [id] (node/absent-lane-workloads (dissoc (bench/registered) id)))
          (str "dropping " id " from the registry leaves exactly it absent")))))

(deftest a-broken-lane-says-which-arm-it-could-not-find
  (testing "And it says so in a sentence naming the ids, because the
            operator reading a failed scheduled run has the command's
            stderr and nothing else."
    (let [msg (node/lane-defect-message (node/absent-lane-workloads {}))]
      (doseq [id node/lane-workloads]
        (is (re-find (re-pattern (str ":" (namespace id) "/" (name id))) msg)
            (str "the refusal names " id))))))

;; ---------------------------------------------------------------------------
;; The lane publishes evidence, through the one runner
;; ---------------------------------------------------------------------------

(defn- lane-outcome
  "Run exactly the lane's workloads, read out of the live registry by the
  ids the entry declares — so a renamed or vanished workload is a failure
  here rather than a quietly shorter report."
  []
  (bench/run (mapv #(get (bench/registered) %) node/lane-workloads) provenance))

(deftest the-lane-publishes-both-react-records-with-their-provenance
  (testing "Both arms answer a full result record: the provenance a
            published number needs, at least one distribution, and at
            least one deterministic property. This is the artefact the
            scheduled workflow uploads, minus the printing."
    (let [{:keys [results]} (lane-outcome)]
      (is (= node/lane-workloads (mapv :benchmark results))
          "one record per lane workload, in declaration order")
      (doseq [r results]
        (let [label (str (:benchmark r) ": ")]
          (is (= "test-fixture-revision" (:revision r)) (str label "names its revision"))
          (is (seq (:fixture r)) (str label "names its fixture parameters"))
          (is (keyword? (get-in r [:build :optimizations])) (str label "names its build mode"))
          (is (boolean? (get-in r [:build :instrumentation?])) (str label "names its instrumentation mode"))
          (is (string? (get-in r [:host :runtime])) (str label "names its runtime"))
          (is (keyword? (get-in r [:host :hardware-class])) (str label "names its hardware class"))
          (is (pos-int? (get-in r [:sampling :samples])) (str label "names its sample policy"))
          (is (contains? #{:before-vs-after :interpreted-vs-compiled
                           :absorbed-vs-donor :self-vs-end-to-end}
                         (get-in r [:baseline :kind]))
              (str label "names one of D021's four baselines"))
          (is (seq (:distribution r)) (str label "publishes at least one distribution"))
          (is (seq (:properties r)) (str label "gates at least one deterministic property")))))))

(deftest the-lane-obeys-the-one-exit-code-law
  (testing "Green means every deterministic property held, and that is the
            only thing the exit code reads. The lane adds no verdict of
            its own — it delegates to the same runner the JVM lane uses."
    (let [{:keys [exit-code gate-failures]} (lane-outcome)]
      (is (= [] gate-failures) "no deterministic property was violated")
      (is (zero? exit-code) "so the lane exits 0"))))
