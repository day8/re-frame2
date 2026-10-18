(ns re-frame.test-quiet-shadow-node-cljs-test
  "CLI selection, bounded diagnostics and real-process exit contracts."
  ;; Requiring shadow-node here would form a compile cycle.
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [re-frame.test-quiet.shadow-node-cli :as rf.test-quiet.shadow-node-cli]
            [re-frame.test-quiet.warn-buffer :as rf.test-quiet.warn-buffer]))

(deftest parse-args-test-selection
  (is (= {:test-syms '[a b c d]}
         (rf.test-quiet.shadow-node-cli/parse-args ["--test=a,b" "--test=c,d"]))))

(deftest warn-buffer-is-bounded-and-materialised
  ;; A subvec retains the discarded head through its backing vector.
  (let [cap    rf.test-quiet.warn-buffer/warn-buffer-cap
        buffer (reduce (fn [current-buffer entry]
                         (rf.test-quiet.warn-buffer/bound-conj current-buffer [entry]))
                       []
                       (range (* 4 cap)))]
    (is (= (mapv vector (range (* 3 cap) (* 4 cap))) buffer))
    (is (instance? cljs.core/PersistentVector buffer))))

(defn- fake-var
  "Test-var metadata for the selector."
  [test-namespace test-name]
  (with-meta (fn []) {:ns test-namespace :name test-name}))

(deftest find-matching-test-vars-filtering
  ;; Nine selectors force hashed set lookup instead of the small-set equality path.
  (let [many-vars (mapv #(fake-var 'many.ns (symbol (str "t" %))) (range 9))
        many-syms (mapv #(symbol "many.ns" (str "t" %)) (range 9))]
    (is (= many-vars
           (vec (rf.test-quiet.shadow-node-cli/select-matching-test-vars many-syms many-vars))))))

(deftest unmatched-selectors-guard
  (is (= '[gone.ns my.ns/c-test missing.ns/x]
         (rf.test-quiet.shadow-node-cli/unmatched-selectors
           '[my.ns gone.ns my.ns/a-test my.ns/c-test missing.ns/x]
           [(fake-var 'my.ns 'a-test) (fake-var 'my.ns 'b-test)]))))

(deftest min-tests-floor-resolution
  (is (= [1 1 0 3000
          :re-frame.test-quiet.shadow-node-cli/invalid
          :re-frame.test-quiet.shadow-node-cli/invalid
          :re-frame.test-quiet.shadow-node-cli/invalid]
         (mapv rf.test-quiet.shadow-node-cli/parse-min-tests
               [nil "   " "0" " 3000 " "1O" "-1" "1.5"]))))

(def ^:private node-child-process (js/require "child_process"))

(def ^:private spawn-timeout-env-var
  "Override for the spawn ceiling, in milliseconds."
  "RF2_SPAWN_TIMEOUT_MS")

(def ^:private default-spawn-timeout-ms
  "Bound child runs; RF2_SPAWN_TIMEOUT_MS overrides the default ceiling."
  600000)

(defn- resolve-spawn-timeout-ms
  "Reject malformed timeout overrides."
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

(def ^:private lagging-reader-relay
  "Node source for `node -e <this> <bundle> <args...>`: run the bundle and
  copy its stdout on, but only from a second after its first byte is
  readable. Meanwhile the pipe fills, so a runner that leaves output queued
  when it exits loses it, while one that writes synchronously waits.

  It holds back with a `readable` listener rather than a paused stream:
  Node resumes a paused child stdout when the child exits, discarding what
  is buffered, whereas a `readable` listener keeps it until read."
  (str "const c=require('child_process').spawn(process.execPath,"
       "process.argv.slice(1),{stdio:['ignore','pipe','inherit']});"
       "let open=false,timer=null;"
       "const drain=()=>{let d;while((d=c.stdout.read())!==null)process.stdout.write(d);};"
       "c.stdout.on('readable',()=>{if(open)drain();"
       "else if(!timer)timer=setTimeout(()=>{open=true;drain();},1000);});"
       "c.on('exit',(code)=>{process.exitCode=(code===null?1:code);});"))

(defn- spawn-runner
  "Run this bundle in a child without inherited verbose output, behind
  `lagging-reader-relay` when `lagging-reader?`."
  ([runner-args] (spawn-runner runner-args {}))
  ([runner-args {:keys [env lagging-reader?]}]
   (let [child-environment (doto (js/Object.assign #js {} js/process.env)
                             (js-delete "RF2_VERBOSE_TESTS")
                             (js/Object.assign (clj->js env)))
         spawn-result (.spawnSync node-child-process
                                  (aget js/process.argv 0) ; the node binary
                                  (apply array
                                         (concat (when lagging-reader? ["-e" lagging-reader-relay])
                                                 [(aget js/process.argv 1)]
                                                 runner-args))
                                  #js {:encoding  "utf8"
                                       :timeout   spawn-timeout-ms
                                       :maxBuffer spawn-max-buffer-bytes
                                       :env       child-environment})]
     {:status (.-status spawn-result)
      :stdout (or (.-stdout spawn-result) "")
      :stderr (or (.-stderr spawn-result) "")
      :error  (.-error spawn-result)})))

(defn- spawn-error-explanation [err]
  (str "Runner spawn failed: " (pr-str err)
       (when (and (some? err) (= "ETIMEDOUT" (.-code err)))
         (str "; timeout " spawn-timeout-ms " ms (override "
              spawn-timeout-env-var ")."))))

(defn- non-blank-lines [s]
  (->> (str/split-lines s) (map str/trim) (remove str/blank?)))

(def ^:private green-fixture-ns "re-frame.test-quiet-green-fixture-cljs-test")

(deftest real-shadow-node-green-run-is-quiet
  (let [{:keys [status stdout stderr error]} (spawn-runner [(str "--test=" green-fixture-ns)])]
    (is (= [nil 0] [error status]) (str stdout stderr (when error (spawn-error-explanation error))))
    (is (= ["Ran 2 tests containing 2 assertions." "0 failures, 0 errors."]
           (non-blank-lines stdout))
        "a green run emits only the canonical summary")))

(deftest real-shadow-node-qualified-selector-runs-exactly-that-var
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" green-fixture-ns "/a-passing-test")])]
    (is (= [nil 0] [error status]) (str stdout stderr (when error (spawn-error-explanation error))))
    (is (= ["Ran 1 tests containing 1 assertions." "0 failures, 0 errors."]
           (non-blank-lines stdout)))))

(deftest unknown-arg-is-fatal-not-false-green
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--tests=" green-fixture-ns)])]
    (is (= [nil 1] [error status]) (str stdout stderr (when error (spawn-error-explanation error))))
    (is (str/includes? stdout (str "Unknown arg: --tests=" green-fixture-ns)) stdout)))

(deftest unmatched-selector-is-fatal-at-real-runner
  (let [{:keys [status stdout stderr error]}
        (spawn-runner ["--test=definitely.absent.namespace"])]
    (is (= [nil 1] [error status]) (str stdout stderr (when error (spawn-error-explanation error))))
    (is (str/includes? stdout "no tests matched --test= selector(s): definitely.absent.namespace")
        stdout)))

(def ^:private red-warn-fixture-ns "re-frame.test-quiet-red-warn-fixture-cljs-test")

(deftest red-run-replays-warnings-and-exits-nonzero
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" red-warn-fixture-ns)]
                      {:env {:RF2_TQ_RED_WARN_FIXTURE "1"}})]
    (is (= [nil 1] [error status]) (str stdout stderr (when error (spawn-error-explanation error))))
    (is (str/includes? stderr "[test-quiet] console.warn: RED-WARN-FIXTURE-MARKER") stderr)
    (is (str/includes? stdout "FAIL in") stdout)))

(deftest large-red-replay-is-not-truncated
  (let [{:keys [stdout stderr error]}
        (spawn-runner [(str "--test=" red-warn-fixture-ns)]
                      {:env {:RF2_TQ_RED_REPLAY_VOLUME "1"}})
        combined (str stdout stderr)]
    (is (nil? error) (spawn-error-explanation error))
    (is (every? #(str/includes? combined %)
                ["RED-REPLAY-VOLUME-0" "RED-REPLAY-TAIL-MARKER"])
        "large replay keeps its head and tail")))

(deftest large-red-fail-block-and-summary-are-not-truncated
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" red-warn-fixture-ns)]
                      {:env             {:RF2_TQ_RED_FAIL_VOLUME "1"}
                       :lagging-reader? true})
        ;; Asserted as booleans so a red prints this evidence, not the
        ;; megabyte of stdout an `includes?` form would carry.
        tail-arrived?    (str/includes? stdout "RED-FAIL-VOLUME-TAIL-MARKER")
        summary-arrived? (str/includes? stdout "1 failures, 0 errors.")
        evidence         (str (count stdout) " stdout chars, ending: "
                              (subs stdout (max 0 (- (count stdout) 400)))
                              "\nstderr: " stderr
                              (when error (spawn-error-explanation error)))]
    (is (= [nil 1] [error status]) evidence)
    (is tail-arrived? (str "the FAIL block arrives whole; " evidence))
    (is summary-arrived? (str "the summary after it arrives too; " evidence))))

(def ^:private exit-integrity-fixture-ns
  "re-frame.test-quiet-exit-integrity-fixture-cljs-test")

(deftest red-replay-throw-cannot-mask-the-nonzero-exit
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" exit-integrity-fixture-ns)]
                      {:env {:RF2_TQ_REPLAY_THROW_FIXTURE "1"}})
        combined (str stdout stderr)]
    (is (= [nil 1] [error status]) (str combined (when error (spawn-error-explanation error))))
    (is (str/includes? combined "console.warn message(s) buffered during this run")
        "the red replay was entered")
    (is (not (str/includes? combined "EXIT-INTEGRITY-REPLAY-TAIL-MARKER"))
        "the replay threw part-way")
    (is (not (str/includes? combined "EXIT-INTEGRITY-REPLAY-POISON"))
        "the replay exception was swallowed, not surfaced as a crash")))

(deftest run-that-never-dispatches-the-exit-defmethod-drains-nonzero
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" exit-integrity-fixture-ns)]
                      {:env {:RF2_TQ_NO_EXIT_DISPATCH_FIXTURE "1"}})]
    (is (str/includes? stdout "EXIT-INTEGRITY-NO-EXIT-DISPATCH-INSTALLED") stdout)
    (is (str/includes? stdout "0 failures, 0 errors.") stdout)
    (is (= [nil 1] [error status]) (str stdout stderr (when error (spawn-error-explanation error))))))

(def ^:private skipped-thunk-fixture-ns
  "re-frame.test-quiet-skipped-thunk-fixture-cljs-test")

(deftest focused-run-that-executes-no-test-exits-nonzero
  (let [{:keys [status stdout stderr error]}
        (spawn-runner [(str "--test=" skipped-thunk-fixture-ns)]
                      {:env {:RF2_TQ_SKIPPED_THUNK_FIXTURE "1"}})]
    (is (str/includes? stdout "SKIPPED-THUNK-FIXTURE-ARMED") stdout)
    (is (str/includes? stdout "Ran 0 tests containing 0 assertions.") stdout)
    (is (str/includes? stdout "ERROR: this run executed 0 test(s)") stdout)
    (is (= [nil 1] [error status]) (str stdout stderr (when error (spawn-error-explanation error))))))
