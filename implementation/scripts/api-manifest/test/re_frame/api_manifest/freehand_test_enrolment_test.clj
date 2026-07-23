(ns re-frame.api-manifest.freehand-test-enrolment-test
  "The JVM lane's focused negative control for `re-frame.freehand.test`
  (rf2-drpa3.79).

  THE HOLE. The Freehand substrate's structural test surface — `render`,
  `find`, `find-all`, `attrs`, `text` (Spec 008) — shipped as a public
  authoring surface and was carried by no public-API inventory at all: the
  generator's `jvm-namespaces` named `re-frame.freehand` but not its test
  sibling, so the sidecar classified none of the five and the generated
  manifest rowed none of them. `gen --check` was GREEN the whole time, for the
  worst reason available: it never looked. A rename, a signature change, a
  removal, or an accidental sixth export in that namespace moved nothing on
  the gate.

  THE CONTROL. Enrolment is only worth having if it BITES, and a gate that
  reads a namespace it did not read yesterday is exactly the kind of change
  whose green proves nothing on its own. So these tests drive the LIVE
  namespace and the LIVE committed sidecar through the generator's own
  reconciliation, in both directions:

    - a public var ADDED to the namespace has no `:classification` entry, so
      `build-manifest` throws `Public vars with no sidecar classification`;
    - a public var REMOVED or RENAMED leaves its row resolving to nothing, so
      `build-manifest` throws `Stale sidecar :classification entries`;
    - the UNMUTATED sidecar builds clean, so both reds are the mutation
      talking rather than a standing failure.

  Each direction is simulated by mutating the SIDECAR rather than the source
  namespace, because the reconciliation is symmetric: dropping the row for a
  live var is indistinguishable — to the generator — from adding a var with no
  row, and adding a row no var answers to is indistinguishable from removing
  the var. Mutating the committed data keeps the control focused, hermetic and
  reversible inside one test run.

  The CLJS half of the same guard lives in the api-manifest probe
  (`probe/test/.../cljs_manifest_probe_cljs_test.cljs`), which reconciles the
  live analyzer surface against the same rows, fully-rowed in both
  directions."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.gen :as gen]))

(def ^:private freehand-test-ns
  "The enrolled namespace under control."
  "re-frame.freehand.test")

(def ^:private blessed-names
  "The five names that ARE the surface (Spec 008 §Freehand structural and
   mounted testing). Everything beneath them is `defn-`, so this set is exact
   by construction rather than by a `^:no-doc` carve-out."
  #{"render" "find" "find-all" "attrs" "text"})

;; ---------------------------------------------------------------------------
;; Enrolment + non-vacuity.
;; ---------------------------------------------------------------------------

(deftest the-namespace-is-jvm-introspected
  (testing "the generator enumerates the structural test surface — without
            this, every assertion below would be green over nothing"
    (is (contains? (set gen/jvm-namespaces) (symbol freehand-test-ns)))))

(deftest the-live-surface-is-exactly-the-five-names
  (testing "the namespace publishes the five blessed names and nothing else,
            so the rows below are the whole surface"
    (require (symbol freehand-test-ns))
    (is (= blessed-names
           (set (map (comp name key) (ns-publics (symbol freehand-test-ns))))))))

(deftest the-manifest-rows-are-classified-testing-tier
  (testing "each of the five is rowed at the `testing` tier, owned by Spec 008,
            and derived `:kind :fn` from the live Vars — a test-AUTHORING
            surface, classified the way `re-frame.ui.test` and
            `re-frame.test-support` are, not as a runtime view verb"
    (let [rows (->> (:vars (gen/build-manifest (gen/read-sidecar)))
                    (filter #(= freehand-test-ns (:namespace %))))]
      (is (= blessed-names (set (map :var rows))))
      (is (= #{:testing} (set (map :tier rows))))
      (is (= #{"008"} (set (map :owner rows))))
      (is (= #{:fn} (set (map :kind rows)))
          "all five are plain `defn`s — none is a mounted descriptor Var")
      (is (= #{false} (set (map :facade? rows)))
          "the surface is required directly, never through the re-frame.core facade")
      (is (= #{true} (set (map :runtime-verified? rows)))
          "JVM-introspected, so existence is verified rather than curated"))))

;; ---------------------------------------------------------------------------
;; The negative control — both directions.
;; ---------------------------------------------------------------------------

(defn- sidecar-without-classification
  "The REAL committed sidecar with `[freehand-test-ns var-str]`'s
   `:classification` entry removed — the shape a public var ADDED to the
   namespace presents to the generator: a live public var no sidecar row
   classifies."
  [var-str]
  (update (gen/read-sidecar) :classification dissoc [freehand-test-ns var-str]))

(defn- sidecar-with-extra-classification
  "The REAL committed sidecar carrying one EXTRA `:classification` entry for a
   name the namespace does not publish — the shape a public var REMOVED or
   RENAMED presents: a row that resolves to no live public var."
  [var-str]
  (assoc-in (gen/read-sidecar) [:classification [freehand-test-ns var-str]]
            {:tier :testing :owner "008" :status "EP-0036"}))

(deftest an-added-public-var-turns-the-gate-red
  (testing "a public var on the structural test surface with no classification
            fails generation and `--check` — the drift the unenrolled
            namespace made invisible"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Public vars with no sidecar classification"
          (gen/build-manifest (sidecar-without-classification "render"))))
    (testing "and the diagnostic NAMES the unclassified var"
      (try
        (gen/build-manifest (sidecar-without-classification "render"))
        (is false "expected build-manifest to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= [[freehand-test-ns "render"]] (:missing (ex-data e)))))))))

(deftest a-removed-public-var-turns-the-gate-red
  (testing "a rowed name the namespace no longer publishes (a rename or a
            removal) fails generation and `--check` — the other direction"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Stale sidecar :classification entries"
          (gen/build-manifest (sidecar-with-extra-classification "click!"))))
    (testing "and the diagnostic NAMES the row with no live var"
      (try
        (gen/build-manifest (sidecar-with-extra-classification "click!"))
        (is false "expected build-manifest to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= [[freehand-test-ns "click!"]] (:stale (ex-data e)))))))))

(deftest the-unmutated-sidecar-builds-clean
  (testing "POSITIVE CONTROL: the committed sidecar reconciles against the live
            surface without throwing, so the two reds above are the mutation
            talking and not a standing failure"
    (is (some? (gen/build-manifest (gen/read-sidecar))))))
