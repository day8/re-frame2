(ns re-frame.freehand.bench.node
  "The ClojureScript/Node entry to the B-spine — the same runner, on the
  host the React arm can actually run on.

      npm run --silent bench:freehand-node > out/freehand-bench-node.edn

  ## Why a second entry, and why it is not a second harness

  `clojure -M:bench` is a JVM process. Every workload it can reach is
  `.cljc`, which is the whole of B1's structural arm and B2's elision
  census — but [[re-frame.freehand.bench.b1-react]] measures the
  interpreted React emitter, and [[re-frame.freehand.react]] is
  ClojureScript-only. A workload that registers itself at load into a
  suite no command loads is an instrument nobody can read: the counters
  exist, the tests exercise them, and a programmer weighing a candidate
  change to the emitter still has no standing command to run and no
  published artefact to compare against.

  So this namespace is an ENTRY, not a harness. It requires the
  ClojureScript-only workloads, then delegates to
  [[re-frame.freehand.bench.runner/-main]] — the same registry, the same
  provenance, the same gate/evidence split, the same `;;`-commented EDN
  on stdout, and the same exit-code law where 1 means a deterministic
  property was violated and nothing else. There is no second report
  shape to keep in step and no second place a threshold could be added.

  ## What this lane publishes that the JVM lane cannot

  The React arm, plus the `.cljc` workloads AS THE BROWSER'S ENGINE RUNS
  THEM. The second half is not a duplicate of the JVM lane: a result
  record names its `:host` and its `:build`, and a V8 number under
  `:optimizations :none` is a different measurement from the same
  workload on a JIT-warmed JVM. Publishing both is how D021's fourth
  baseline — substrate self time versus end-to-end host time — stays
  answerable on the host applications actually ship to.

  ## The lane's one guard, held down from both ends

  [[lane-workloads]] names the workloads this entry EXISTS to publish,
  and [[absent-lane-workloads]] is checked before the suite runs. The two
  ways that could silently stop being true are closed separately:

    - the require is deleted — the roster below is read off the arm's own
      `workloads`, so the require is load-bearing and its removal is a
      compile error, not a quieter report;
    - the arm loads but no longer registers — the runtime check fails the
      command with the ids it could not find.

  Which is the same posture as
  [[re-frame.freehand.bench.provenance/result]]: a report missing the
  thing it was run for is refused, not quietly emitted.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand.bench :as bench]
            ;; The ClojureScript-only arm. Requiring it is what registers
            ;; it, exactly as the JVM runner requires the `.cljc` ones —
            ;; one line per arm, nothing discovered by scanning.
            [re-frame.freehand.bench.b1-react :as b1-react]
            [re-frame.freehand.bench.runner :as runner]))

(def lane-workloads
  "The workload ids this lane exists to publish — the ones no JVM process
  can reach.

  Read off the arm rather than transcribed from it, so the require above
  cannot become decorative: delete it and this namespace stops compiling.
  The transcription lives in the suite instead, where a workload that
  quietly changed its id is a failing sentence naming both spellings."
  (mapv :id b1-react/workloads))

(defn absent-lane-workloads
  "The ids in [[lane-workloads]] that `registry` does not hold, in
  declaration order — empty when the lane is whole.

  Takes the registry rather than reading it, so the guard is testable
  against a registry that is deliberately missing an arm. A guard only
  ever exercised against the live registry is a guard nobody has seen
  fail."
  [registry]
  (into [] (remove #(contains? registry %)) lane-workloads))

(defn lane-defect-message
  "The one-line sentence a broken lane fails with."
  [absent]
  (str "This ClojureScript evidence lane exists to publish " (count absent)
       (if (= 1 (count absent)) " workload" " workloads")
       " that no JVM process can reach, and the registry holds "
       (if (= 1 (count absent)) "it" "them") " not: "
       (pr-str absent)
       ". Requiring a workload namespace is what registers it, so the require was "
       "dropped or the workload no longer registers under this id. Refusing rather "
       "than publishing a shorter report that still looks like evidence."))

(defn -main
  [& args]
  (let [absent (absent-lane-workloads (bench/registered))]
    (if (seq absent)
      (do (js/console.error (lane-defect-message absent))
          (js/process.exit 1))
      (apply runner/-main args))))
