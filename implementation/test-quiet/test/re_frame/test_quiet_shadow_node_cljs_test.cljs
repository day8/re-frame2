(ns re-frame.test-quiet-shadow-node-cljs-test
  "Contract pin for the CLJS `:node-test` entry point
  `re-frame.test-quiet.shadow-node`.

  The shadow-node runner carries custom CLI parsing, var-filtering, an
  exit-code defmethod, and a global `console.warn` stub. This namespace
  pins pure helpers in-process and uses child processes for discovery and
  exit paths that would tear down the current node runner.

  Contracts pinned:

   - `--test=` SELECTION: comma-split into symbols, simple symbols are
     namespace selectors and qualified symbols are single-var selectors.
     Unknown args and unmatched selectors are fatal at the process
     boundary, never a green run.
   - EXIT-CODE INTEGRITY: the `:end-run-tests` defmethod exits 0 on a
     green summary and 1 on a red one, and two safeguards stop a run
     draining to a false green — the red warning replay is wrapped so a
     throw cannot pre-empt the nonzero exit, and `execute-cli` seeds
     `process.exitCode = 1` so a run that never dispatches the defmethod
     still fails. All four are pinned at the REAL process boundary, the
     two safeguards each through their own fault fixture: invoking the
     defmethod in-process would call `js/process.exit`.
   - EXECUTED-TEST FLOOR: a `--test=` run whose selector matches but whose
     fixture runs nothing exits nonzero, at the process boundary through
     its own fault fixture.
   - The buffered `console.warn` ring is bounded, and replayed in full on a
     red run."
  ;; NB: must NOT require re-frame.test-quiet.shadow-node — that ns is
  ;; `:dev/always` and expands the test-ns-enumeration macro, so a test
  ;; requiring it forms a compile cycle. Pure CLI parsing lives in the
  ;; `-cli` ns; the console.warn stub it installs is live at runtime
  ;; anyway because shadow-node is the :node-test build's :main.
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [re-frame.test-quiet.shadow-node-cli :as rf.test-quiet.shadow-node-cli]
            [re-frame.test-quiet.warn-buffer :as rf.test-quiet.warn-buffer]))

;; ----------------------------------------------------------------------
;; Pure helpers.

(deftest parse-args-test-selection
  ;; Dropping a repeated flag would silently run fewer tests.
  (is (= {:test-syms '[a b c d]}
         (rf.test-quiet.shadow-node-cli/parse-args ["--test=a,b" "--test=c,d"]))))

(deftest warn-buffer-is-bounded-and-materialised
  ;; A `subvec` shares and retains its whole underlying vector, so the trimmed
  ;; ring must be a fresh PersistentVector or the bound would leak every
  ;; discarded warning until process exit.
  (let [cap    rf.test-quiet.warn-buffer/warn-buffer-cap
        buffer (reduce (fn [current-buffer entry]
                         (rf.test-quiet.warn-buffer/bound-conj current-buffer [entry]))
                       []
                       (range (* 4 cap)))]
    (is (= (mapv vector (range (* 3 cap) (* 4 cap))) buffer))
    (is (instance? cljs.core/PersistentVector buffer))))

(defn- fake-var
  "A stand-in for a test var: `meta` returns namespace/name metadata, matching
  what `cli/select-matching-test-vars` reads."
  [test-namespace test-name]
  (with-meta (fn []) {:ns test-namespace :name test-name}))

(deftest find-matching-test-vars-filtering
  ;; Nine, because eight is where ClojureScript's set representation changes:
  ;; up to eight entries a set finds a key by `=`, above that it hashes. A
  ;; qualified selector is matched against a symbol rebuilt from a var's
  ;; `{:ns :name}` METADATA, whose parts are symbols, `=` to the reader's
  ;; `ns/name` but not hash-equal to it, so unless the selector rebuilds it
  ;; through `str`, every qualified selector silently stops matching at the
  ;; ninth.
  (let [many-vars (mapv #(fake-var 'many.ns (symbol (str "t" %))) (range 9))
        many-syms (mapv #(symbol "many.ns" (str "t" %)) (range 9))]
    (is (= many-syms
           (mapv (fn [test-var]
                   (let [{test-namespace :ns test-name :name} (meta test-var)]
                     (symbol (str test-namespace) (str test-name))))
                 (rf.test-quiet.shadow-node-cli/select-matching-test-vars many-syms many-vars))))))

(deftest unmatched-selectors-guard
  ;; A matched namespace or var drops out; an absent namespace, an absent var,
  ;; and a wrong var name in a namespace that has other matches all survive,
  ;; in input order.
  (is (= '[gone.ns my.ns/c-test missing.ns/x]
         (rf.test-quiet.shadow-node-cli/unmatched-selectors
           '[my.ns gone.ns my.ns/a-test my.ns/c-test missing.ns/x]
           [(fake-var 'my.ns 'a-test) (fake-var 'my.ns 'b-test)]))))

(deftest min-tests-floor-resolution
  ;; Unset or blank is the default floor of 1; a malformed value is invalid,
  ;; never a silent fall back that would disable the gate.
  (is (= [1 1 0 3000
          :re-frame.test-quiet.shadow-node-cli/invalid
          :re-frame.test-quiet.shadow-node-cli/invalid
          :re-frame.test-quiet.shadow-node-cli/invalid]
         (mapv rf.test-quiet.shadow-node-cli/parse-min-tests
               [nil "   " "0" " 3000 " "1O" "-1" "1.5"]))))

;; ----------------------------------------------------------------------
;; Process-level rows. Each spawns the same built `out/node-test.js`
;; shadow-node runner this process is executing, through the real
;; `shadow-node/main` -> `parse-args` -> `execute-cli` path.

(def ^:private node-child-process (js/require "child_process"))

(def ^:private spawn-timeout-env-var
  "Override for the spawn ceiling, in milliseconds."
  "RF2_SPAWN_TIMEOUT_MS")

(def ^:private default-spawn-timeout-ms
  "Hard ceiling for a spawned focused runner. It is a backstop, not a
  performance assertion: every spawn re-loads the whole consolidated
  node-test build, which takes seconds on an idle box and minutes on a
  saturated one. A killed child is still RED, because every row asserts the
  spawn error is nil, so the ceiling only bounds how long a wedged child
  stalls the suite. 600 s sits above the slowest healthy child measured."
  600000)

(defn- resolve-spawn-timeout-ms
  "Parse the ceiling override. A malformed value THROWS rather than falling
  back: `RF2_SPAWN_TIMEOUT_MS=60O` (letter O) silently restoring a
  starvation-prone ceiling would be this defect wearing a hat."
  [raw]
  (if (or (nil? raw) (str/blank? (str raw)))
    default-spawn-timeout-ms
    (let [n (js/Number (str/trim (str raw)))]
      (when-not (and (js/Number.isInteger n) (pos? n))
        (throw (ex-info (str spawn-timeout-env-var "=\"" raw "\" is not a positive"
                             " integer number of milliseconds.  Leave it unset for"
                             " the default (" default-spawn-timeout-ms " ms).")
                        {:value raw})))
      n)))

(def ^:private spawn-timeout-ms
  (resolve-spawn-timeout-ms (aget js/process.env spawn-timeout-env-var)))

(def ^:private spawn-max-buffer-bytes
  "Output cap for a spawned focused runner, so a runaway child cannot exhaust
  this process's memory."
  (* 8 1024 1024))

(defn- spawn-runner
  "Re-spawn the built runner with `runner-args` and optional `:env` entries
  merged over the parent env; return `{:status :stdout :stderr :error}`.
  `:error` carries a spawn failure (ENOENT, ETIMEDOUT, ENOBUFS)."
  ([runner-args] (spawn-runner runner-args {}))
  ([runner-args {:keys [env]}]
   (let [child-environment (when env
                             (let [merged-environment
                                   (js/Object.assign #js {} js/process.env)]
                               (doseq [[environment-name environment-value] env]
                                 (aset merged-environment
                                       (name environment-name)
                                       environment-value))
                               merged-environment))
         spawn-result (.spawnSync node-child-process
                                  (aget js/process.argv 0) ; the node binary
                                  (apply array (aget js/process.argv 1) runner-args)
                                  (cond-> #js {:encoding  "utf8"
                                               :timeout   spawn-timeout-ms
                                               :maxBuffer spawn-max-buffer-bytes}
                                    child-environment
                                    (doto (aset "env" child-environment))))]
     {:status (.-status spawn-result)
      :stdout (or (.-stdout spawn-result) "")
      :stderr (or (.-stderr spawn-result) "")
      :error  (.-error spawn-result)})))

(defn- spawn-error-explanation
  "Message for the `(is (nil? err) ...)` pin every process-level row makes.
  A spawn killed at the ceiling is still a failure, but of the box rather than
  the diff, so the ETIMEDOUT case names the ceiling and the knob that moves it."
  [err]
  (str "spawning the real runner must not error; got: " (pr-str err)
       (when (and (some? err) (= "ETIMEDOUT" (.-code err)))
         (str "\n\n  The child was KILLED at the " spawn-timeout-ms " ms spawn"
              " ceiling, so BOTH of its streams are empty — it never produced"
              " a byte.\n  Each spawn re-loads the whole consolidated"
              " node-test build (~4200 dev modules), which costs ~10-13 s on"
              " an idle box\n  and has been measured above 400 s while sibling"
              " worker checkouts saturate the machine.  An oversubscribed box"
              "\n  is therefore a far likelier explanation than a defect in"
              " your change: re-run on a quiet box, or raise the ceiling with"
              "\n  " spawn-timeout-env-var "=<ms>.  A child that never ran"
              " cannot verify the contract, so this stays RED either way."))))

(defn- non-blank-lines [s]
  (->> (str/split-lines s) (map str/trim) (remove str/blank?)))

(def ^:private green-fixture-ns "re-frame.test-quiet-green-fixture-cljs-test")

(deftest real-shadow-node-green-run-is-quiet
  ;; The green fixture holds exactly two vars, so `Ran 2` also proves a simple
  ;; symbol selects EVERY var in its namespace; its qualified sibling is the
  ;; row below.
  (let [{:keys [status stdout stderr error]} (spawn-runner [(str "--test=" green-fixture-ns)])]
    (is (nil? error) (spawn-error-explanation error))
    (is (zero? status) (str stdout stderr))
    (is (= ["Ran 2 tests containing 2 assertions." "0 failures, 0 errors."]
           (non-blank-lines stdout))
        "a green run emits only the canonical summary")))

(deftest real-shadow-node-qualified-selector-runs-exactly-that-var
  ;; `Ran 2` here would mean the qualified selector was treated as a namespace
  ;; selector.
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" green-fixture-ns "/a-passing-test")])]
    (is (nil? error) (spawn-error-explanation error))
    (is (zero? status) (str stdout stderr))
    (is (= ["Ran 1 tests containing 1 assertions." "0 failures, 0 errors."]
           (non-blank-lines stdout)))))

(deftest unknown-arg-is-fatal-not-false-green
  ;; `--tests=` (a typo'd plural) is an unknown arg, not the `--test=`
  ;; selector, and must not fall through to a green whole-suite run.
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--tests=" green-fixture-ns)])]
    (is (nil? error) (spawn-error-explanation error))
    (is (= 1 status) (str stdout stderr))
    (is (str/includes? stdout (str "Unknown arg: --tests=" green-fixture-ns)) stdout)))

(deftest unmatched-selector-is-fatal-at-real-runner
  ;; A well-formed selector that matches no test var must not be a 0-test
  ;; green.
  (let [{:keys [status stdout stderr error]}
        (spawn-runner ["--test=definitely.absent.namespace"])]
    (is (nil? error) (spawn-error-explanation error))
    (is (= 1 status) (str stdout stderr))
    (is (str/includes? stdout "no tests matched --test= selector(s): definitely.absent.namespace")
        stdout)))

;; The red-warn fixture warns and fails only when armed by an environment
;; variable, so the whole-suite run stays green.
(def ^:private red-warn-fixture-ns "re-frame.test-quiet-red-warn-fixture-cljs-test")

(deftest red-run-replays-warnings-and-exits-nonzero
  ;; The warning the green-path stub withholds is replayed to stderr on red,
  ;; and the printed failure and the exit code agree.
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" red-warn-fixture-ns)]
                      {:env {:RF2_TQ_RED_WARN_FIXTURE "1"}})]
    (is (nil? error) (spawn-error-explanation error))
    (is (= 1 status) (str stdout stderr))
    (is (str/includes? stderr "[test-quiet] console.warn: RED-WARN-FIXTURE-MARKER") stderr)
    (is (str/includes? stdout "FAIL in") stdout)))

(deftest large-red-replay-is-not-truncated
  ;; The replay writes to fd 2 synchronously, so `js/process.exit 1` right
  ;; after it cannot drop the tail of a ring filled with large warnings: the
  ;; newest warning, replayed LAST, is the byte range an async write would
  ;; lose.
  (let [{:keys [stdout stderr error]}
        (spawn-runner [(str "--test=" red-warn-fixture-ns)]
                      {:env {:RF2_TQ_RED_REPLAY_VOLUME "1"}})
        combined (str stdout stderr)]
    (is (nil? error) (spawn-error-explanation error))
    (is (str/includes? combined "RED-REPLAY-VOLUME-0") "the head of the large replay")
    (is (str/includes? combined "RED-REPLAY-TAIL-MARKER") "the tail of the large replay")))

;; ----------------------------------------------------------------------
;; Exit-code integrity: the two safeguards, one fault fixture each. An
;; ordinary red or green run traverses neither, so each fixture enters its
;; own failure mode and emits a reached-state marker: a nonzero status alone
;; is also what a spawn error, a parse error or an unrelated crash look like.

(def ^:private exit-integrity-fixture-ns
  "re-frame.test-quiet-exit-integrity-fixture-cljs-test")

(deftest red-replay-throw-cannot-mask-the-nonzero-exit
  ;; Status alone cannot discriminate: without the guard the same exception
  ;; escapes and node exits 1 too. What discriminates is that the exception
  ;; was SWALLOWED, its message never reaching the output.
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" exit-integrity-fixture-ns)]
                      {:env {:RF2_TQ_REPLAY_THROW_FIXTURE "1"}})
        combined (str stdout stderr)]
    (is (nil? error) (spawn-error-explanation error))
    (is (= 1 status) combined)
    (is (str/includes? combined "console.warn message(s) buffered during this run")
        "the red replay was entered")
    (is (not (str/includes? combined "EXIT-INTEGRITY-REPLAY-TAIL-MARKER"))
        "the replay threw part-way")
    (is (not (str/includes? combined "EXIT-INTEGRITY-REPLAY-POISON"))
        "the replay exception was swallowed, not surfaced as a crash")))

(deftest run-that-never-dispatches-the-exit-defmethod-drains-nonzero
  ;; A GREEN run whose exit defmethod is a no-op: nothing but the seeded
  ;; `process.exitCode` is left to explain a nonzero status.
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" exit-integrity-fixture-ns)]
                      {:env {:RF2_TQ_NO_EXIT_DISPATCH_FIXTURE "1"}})]
    (is (nil? error) (spawn-error-explanation error))
    (is (str/includes? stdout "EXIT-INTEGRITY-NO-EXIT-DISPATCH-INSTALLED") stdout)
    (is (str/includes? stdout "0 failures, 0 errors.") stdout)
    (is (= 1 status) (str stdout stderr))))

;; ----------------------------------------------------------------------
;; Executed-test floor: a run is green only if it EXECUTED tests. The fault
;; fixture's selector matches a discovered var, so the unmatched-selector
;; guard passes; its `:once` fixture then never calls its thunk, leaving a
;; clean 0-test tally that only the executed count can refuse.

(def ^:private skipped-thunk-fixture-ns
  "re-frame.test-quiet-skipped-thunk-fixture-cljs-test")

(deftest focused-run-that-executes-no-test-exits-nonzero
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" skipped-thunk-fixture-ns)]
                      {:env {:RF2_TQ_SKIPPED_THUNK_FIXTURE "1"}})]
    (is (nil? error) (spawn-error-explanation error))
    (is (str/includes? stdout "SKIPPED-THUNK-FIXTURE-ARMED") stdout)
    (is (str/includes? stdout "Ran 0 tests containing 0 assertions.") stdout)
    (is (str/includes? stdout "ERROR: this run executed 0 test(s)") stdout)
    (is (= 1 status) (str stdout stderr))))
