(ns re-frame.test-quiet.shadow-node-cli
  "Pure CLI-arg parsing for the `:node-test` runner
  `re-frame.test-quiet.shadow-node` (rf2-vespg).

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
  `{:test-syms [..] :help? :list?}` (flags present only when set).

   - `--help` / `--list` set their boolean flag.
   - `--test=a,b` splits on comma into symbols and accumulates into
     `:test-syms` (repeated `--test=` flags accumulate).  A simple
     symbol selects a whole namespace; a qualified symbol selects a
     single var.
   - any other arg is reported as unknown and otherwise ignored."
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
        (do (println (str "Unknown arg: " arg))
            opts)))
    {:test-syms []}
    args))
