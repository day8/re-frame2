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
    (fn [parsed-args argument]
      (cond
        (= "--help" argument)
        (assoc parsed-args :help true)

        (= "--list" argument)
        (assoc parsed-args :list true)

        (str/starts-with? argument "--test=")
        (let [selector-text  (subs argument 7)
              test-selectors (->> (str/split selector-text ",")
                                  (map symbol))]
          (update parsed-args :test-syms into test-selectors))

        :else
        (update parsed-args :unknown-args (fnil conj []) argument)))
    {:test-syms []}
    args))

(defn- test-var-names
  "The `[namespace-symbol fully-qualified-symbol]` a test var's `{:ns :name}`
  metadata names — both rebuilt through `str`, deliberately.

  `(symbol <ns-symbol> <name-symbol>)` keeps the two parts UNCONVERTED, and
  ClojureScript hashes a symbol by hashing those parts as strings: a Symbol
  object has no `.length`, so every symbol built that way hashes to the same
  constant.  It stays `=` to the reader's `ns/name`, which is what made this
  invisible — a set finds such a key by `=` while it is small enough to be
  array-map-backed, and stops finding it above eight entries, where the set
  hashes.  So `--test=` selected correctly for up to eight qualified
  selectors and silently selected NOTHING at nine.  Measured, then fixed,
  while pointing the contract test at this function (rf2-6r9j.76); the copy
  of the predicate it replaced could not have found it.  The namespace half
  is rebuilt the same way so the two cannot drift apart."
  [test-var]
  (let [{test-namespace :ns test-name :name} (meta test-var)]
    [(symbol (str test-namespace))
     (symbol (str test-namespace) (str test-name))]))

(defn select-matching-test-vars
  "The `--test=` selection rule over an explicit `test-vars` collection: a
  SIMPLE symbol in `test-selectors` selects every var in that namespace, a
  QUALIFIED symbol selects exactly the var with that fully-qualified name.
  Vars are matched through their `{:ns :name}` metadata and returned in
  `test-vars` order.

  This IS the shipped selector — `shadow-node/find-matching-test-vars` is
  this function over `shadow.test.env/get-test-vars`.  It lives here, apart
  from the `:dev/always` runner ns, for the same reason `unmatched-selectors`
  below does: a test cannot require that ns without forming a compile cycle,
  so a rule kept there could only be pinned by a second, handwritten copy of
  the predicate — which is exactly the false green this move closes
  (rf2-6r9j.76)."
  [test-selectors test-vars]
  (let [selected-namespaces  (->> test-selectors (filter simple-symbol?) set)
        selected-var-symbols (->> test-selectors (filter qualified-symbol?) set)]
    (filter (fn [test-var]
              (let [[test-namespace test-var-symbol] (test-var-names test-var)]
                (or (contains? selected-namespaces test-namespace)
                    (contains? selected-var-symbols test-var-symbol))))
            test-vars)))

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
    (let [parsed-floor (js/Number (str/trim raw))]
      (if (and (js/Number.isInteger parsed-floor)
               (not (neg? parsed-floor)))
        parsed-floor
        ::invalid))))

(defn unmatched-selectors
  "The subset of `test-selectors` that matched no var in `matched-test-vars`.

  `matched-test-vars` is the seq `find-matching-test-vars` returns — each var
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
  [test-selectors matched-test-vars]
  (let [matched-names       (map test-var-names matched-test-vars)
        matched-namespaces  (set (map first matched-names))
        matched-var-symbols (set (map second matched-names))]
    (remove (fn [selector]
              (if (qualified-symbol? selector)
                (contains? matched-var-symbols selector)
                (contains? matched-namespaces selector)))
            test-selectors)))
