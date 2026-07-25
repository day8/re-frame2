(ns re-frame.test-quiet.shadow-node-cli
  "Pure CLI-arg parsing for the `:node-test` runner
  `re-frame.test-quiet.shadow-node`.

  Lives apart from the runner so it can be unit-pinned: the runner ns
  carries `{:dev/always true}` and expands `shadow.test.env/get-test-data`
  (a macro that enumerates the build's test namespaces at compile time),
  which makes the runner compile-time-depend on every test ns in the
  build.  A test ns that required the runner directly would form a
  compile cycle.  `parse-args` has no such dependency, so factoring it
  here lets `re-frame.test-quiet-shadow-node-cljs-test` pin the `--test=`
  selection contract directly.

  Same shape `shadow.test.node` ships, so the existing
  `npm run test:cljs -- --test=foo` form keeps working."
  (:require [clojure.string :as str]))

(defn parse-args
  "Parse shadow-node CLI args into
  `{:test-syms [..] :help :list :unknown-args [..]}` (flags present
  only when set; `:unknown-args` present only when an arg was unknown).

   - `--help` / `--list` set their boolean flag.
   - `--test=a,b` splits on comma into symbols and accumulates into
     `:test-syms` (repeated `--test=` flags accumulate).  A simple
     symbol selects a whole namespace; a qualified symbol selects a
     single var.
   - any other arg is collected into `:unknown-args` (in input order)
     and otherwise ignored.  This parser is PURE — it does not print:
     the real CLI path (`shadow-node/execute-cli`) reports the
      unknowns, so a contract test can pin the parse result without
      leaking a non-summary line on a green run."
  [args]
  (reduce
    (fn [opts arg]
      (cond
        (= "--help" arg)
        (assoc opts :help true)

        (= "--list" arg)
        (assoc opts :list true)

        (str/starts-with? arg "--test=")
        (let [test-arg  (subs arg 7)
              test-syms (->> (str/split test-arg ",")
                             (map symbol))]
          (update opts :test-syms into test-syms))

        :else
        (update opts :unknown-args (fnil conj []) arg)))
    {:test-syms []}
    args))

;; ----------------------------------------------------------------------
;; Whole-suite test-count floor (rf2-qqzmf).
;;
;; `unmatched-selectors` below already refuses to call a `--test=` selection
;; that matched nothing a success. The whole-suite path had no such guard:
;; `shadow.build.test-util/find-test-namespaces` returns `[]` when a build's
;; `:ns-regexp` matches nothing and says nothing about it, so a one-character
;; suffix drift or a dropped `:source-paths` entry emptied a lane and still
;; printed `Ran 0 tests containing 0 assertions. / 0 failures, 0 errors.`
;; The floor generalises the selector guard to that path. Kept here, beside
;; it, because both express the same rule and both must stay unit-pinnable
;; without importing the `:dev/always` runner ns.

(def default-min-tests
  "Floor applied when `RF2_MIN_TESTS` is unset: a whole-suite build that
  discovered no test vars is a configuration error, not a pass." 1)

(defn parse-min-tests
  "Resolve the test-count floor from `raw` — an `RF2_MIN_TESTS` value, or
  nil/blank when unset. Returns the floor as a number, or `::invalid` when
  `raw` is present but not a non-negative integer.

  Same name and same semantics as the JVM runner's own
  `re-frame.test-quiet.runner/parse-min-tests`, including the refusal to
  treat a malformed value as \"unset\": `RF2_MIN_TESTS=1O` (letter O) quietly
  disabling the gate that catches silent non-execution would be the same bug
  in a new place."
  [raw]
  (if (or (nil? raw) (str/blank? raw))
    default-min-tests
    (let [n (js/Number (str/trim raw))]
      (if (and (js/Number.isInteger n) (not (neg? n))) n ::invalid))))

(defn unmatched-selectors
  "The subset of `test-syms` that matched no var in `matched-vars`.

  `matched-vars` is the seq `find-matching-test-vars` returns — each var
  carries `{:ns :name}` metadata.  A simple symbol (namespace selector)
  is satisfied if any matched var lives in that ns; a qualified symbol
  (single-var selector) is satisfied if a matched var has that
  fully-qualified name.  Returns the selectors with no match, in input
  order — empty when every selector matched at least one var.

  Pure (no `shadow.test.env` dependency) so it can be unit-pinned here
  rather than from the `:dev/always` runner ns, which forms a compile
  cycle. This guards against a `--test=<typo>` false green: a selection
  that matches nothing must be rejected, not
  reported as a 0-test success."
  [test-syms matched-vars]
  (let [matched-ns  (->> matched-vars (map (comp :ns meta)) set)
        matched-fqn (->> matched-vars
                         (map (fn [v] (let [{:keys [ns name]} (meta v)]
                                        (symbol ns name))))
                         set)]
    (remove (fn [sym]
              (if (qualified-symbol? sym)
                (contains? matched-fqn sym)
                (contains? matched-ns sym)))
            test-syms)))
