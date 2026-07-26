(ns re-frame.prod-gate-naming-drift-test
  "rf2-f7qj4 — a namespace whose NAME claims the production/debug gate must
  either reach that gate for real, or say out loud that it does not.

  ## The failure mode this closes

  `re-frame.interop/debug-enabled?` is read ONCE, at namespace-load time, from
  `-Dre-frame.debug` / `RE_FRAME_DEBUG`. A suite that reaches it with
  `with-redefs` runs AFTER the framework has loaded and cannot change one
  thing the gate decided at load — it pins a rebindable Var, not a posture.

  rf2-9c2jf was a TOTAL `dispatch-sync` failure under the documented
  production gate — handler run ZERO times — that stayed green for as long as
  it existed. Part of why nobody caught it: the roster of suites calling
  themselves \"production gate\" tests looked full, and a reviewer reading the
  file list had no way to see that not one of them ran under the gate. The
  names were the camouflage.

  rf2-f7qj4 re-docstringed the three offenders. A docstring decays; this test
  is what stops the next one being written.

  ## The rule

  DOMAIN — every `.clj` / `.cljc` file under `implementation/core/test/` whose
  FILE NAME contains `prod_gate`, `jvm_gate`, or `debug_gate`. Those three
  tokens are the ones that assert, in the file listing itself, \"this exercises
  the JVM production/debug gate\". Deliberately narrow: `trace_gate` and the
  `prod_elision` suites make a different claim and are out of scope.

  A file in the domain is HONEST when it does at least one of:

    a. carries `^:prod-gate` metadata — it belongs to the real
       `jvm-core-prod-gate` lane, which puts `-Dre-frame.debug=false` on the
       JVM command line via the `:prod-gate` alias's `:jvm-opts`;
    b. contains the literal `-Dre-frame.debug=false` — it relaunches a child
       JVM with the property on the command line (the
       `re-frame.prod-gate-dispatch-jvm-test` pattern);
    c. contains the disclaimer sentinel below — it states in its own docstring
       that it is NOT THE LOAD-TIME GATE, so the file listing stops lying.

  This namespace satisfies its own rule through (c): the sentinel is `def`'d
  below, and this suite makes no claim to run under any particular posture.

  ## Posture-independence

  Every assertion here is a pure filesystem + string check. It holds in dev
  posture and under `-Dre-frame.debug=false` alike, so this namespace runs in
  the ordinary `clojure -M:test` suite AND joins `jvm-core-prod-gate`
  automatically (that lane's roster is an EXCLUSION list — a new namespace
  joins by default)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private claim-tokens
  "File-name substrings that CLAIM the JVM production/debug gate."
  ["prod_gate" "jvm_gate" "debug_gate"])

(def ^:private disclaimer
  "The sentinel a claiming suite writes into its ns docstring to say it does
  not reach the load-time gate. Kept as one exact string so the disclaimer is
  greppable rather than a paraphrase every author reinvents."
  "NOT THE LOAD-TIME GATE")

(def ^:private prod-gate-tag "^:prod-gate")
(def ^:private jvm-property "-Dre-frame.debug=false")

(defn- test-namespace-dir
  "The on-disk `test/re_frame` directory, resolved off the CLASSPATH rather
  than the process CWD — `clojure -M:test` and the `jvm-core-prod-gate` lane
  both run from `implementation/core`, but nothing guarantees a third caller
  will. Anchored on a file that exists ONLY under `test/` so the `src/`
  `re_frame` directory cannot win the lookup."
  ^java.io.File []
  (some-> (io/resource "re_frame/prod_gate_lane_pin_test.clj")
          io/as-file
          .getParentFile))

(defn- claiming-files
  "Every file in the domain: a `.clj` / `.cljc` source under `test/re_frame`
  whose name contains one of `claim-tokens`."
  []
  (->> (some-> (test-namespace-dir) .listFiles seq)
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %)))
       (filter (fn [^java.io.File f]
                 (some #(str/includes? (.getName f) %) claim-tokens)))
       (sort-by #(.getName ^java.io.File %))
       vec))

(defn- honest? [^java.io.File f]
  (let [content (slurp f)]
    (or (str/includes? content prod-gate-tag)
        (str/includes? content jvm-property)
        (str/includes? content disclaimer))))

(deftest the-domain-scan-still-finds-files
  (testing "rf2-f7qj4 — the guard on the guard. If `test-namespace-dir` stops
            resolving (a moved test root, a packaged classpath) or the token
            match stops hitting, the check below passes VACUOUSLY and the
            naming lie is free to come back. A silently-empty scan is the
            failure mode this whole namespace exists to prevent, so it is a
            hard red."
    (is (some? (test-namespace-dir))
        (str "could not resolve `test/re_frame` from the classpath via "
             "`re_frame/prod_gate_lane_pin_test.clj` — has the anchor file "
             "been renamed or the test root moved?"))
    (is (<= 3 (count (claiming-files)))
        (str "expected at least the three known gate-claiming files "
             "(prod_gate_lane_pin_test, prod_gate_dispatch_jvm_test, "
             "jvm_prod_gate_integration_test); found "
             (mapv #(.getName ^java.io.File %) (claiming-files))))))

(deftest every-gate-claiming-namespace-is-honest-about-the-gate
  (testing "rf2-f7qj4 — a test file whose NAME says it exercises the JVM
            production/debug gate must either reach that gate for real
            (`^:prod-gate` in the `jvm-core-prod-gate` lane, or a child JVM
            launched with `-Dre-frame.debug=false`) or carry the disclaimer
            sentinel in its docstring. `with-redefs` on
            `re-frame.interop/debug-enabled?` is neither: the flag is read
            once at namespace-load time, so a rebind cannot reach it."
    (let [liars (remove honest? (claiming-files))]
      (is (empty? liars)
          (str "these files NAME the production/debug gate but neither reach "
               "it nor disclaim it: "
               (mapv #(.getName ^java.io.File %) liars)
               "\n\nFix by one of:"
               "\n  a. run in the real lane — tag the deftests `^:prod-gate`"
               "\n     (see re-frame.prod-gate-lane-pin-test) and add the ns to"
               "\n     scripts/test-core-prod-gate.sh's lane;"
               "\n  b. relaunch a child JVM with `" jvm-property "` on its"
               "\n     command line (see re-frame.prod-gate-dispatch-jvm-test);"
               "\n  c. state in the ns docstring that the suite is `"
               disclaimer "`,"
               "\n     naming what it DOES pin (a rebindable Var is a real"
               "\n     contract — it is just not the production posture).")))))
