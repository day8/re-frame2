(ns re-frame.test-quiet.shadow-node
  "CLJS `:main` entry point for shadow-cljs's `:node-test` target.

  Mirrors `shadow.test.node/main` shape but installs the quiet
  reporter overrides on `cljs.test/report` before invoking the test
  runner.  Wired into `implementation/shadow-cljs.edn`'s `:node-test`
  build via `:main re-frame.test-quiet.shadow-node/main`.

  Requiring `re-frame.test-quiet` at the top is the install — the
  CLJS `defmethod`s install at load time.  By the time shadow's
  `run-all-tests` walks namespaces, the overrides are in place.

  Also stubs `js/console.warn` so first-time runtime warnings
  (`reg-view non-DOM root`, `reagent-slim keyword-on-non-HTML-prop`,
  etc.) don't leak to test stdout on the success path.  Tests that
  assert warning content all use the local `with-captured-console-warn`
  pattern — they save `(.-warn js/console)`, install a recording shim,
  run the body, then restore.  When our stub is in place, the saved
  value IS the stub; the recording shim still receives the calls; the
  restored value reverts to the stub.  Net effect: in-test capture
  works unchanged; out-of-test side-effect warnings are silenced."
  {:dev/always true}
  (:require
    [re-frame.test-quiet]
    [shadow.test.env :as env]
    [shadow.test :as st]
    [cljs.test :as ct]
    [clojure.string :as str]))

;; Silence stray runtime warnings on green. See ns docstring for the
;; capture-compatibility rationale.  Installed at ns-load time so it's
;; in place before shadow's `run-all-tests` walks the test ns set.
(when (and (exists? js/console)
           (fn? (.-warn js/console)))
  (set! (.-warn js/console) (fn [& _])))

;; ----------------------------------------------------------------------
;; The single override shadow.test.node ships — exit the node process
;; with the appropriate code.  Stays here because removing it would
;; break CI's pass/fail signal.

(defmethod ct/report [:cljs.test/default :end-run-tests] [m]
  (if (ct/successful? m)
    (js/process.exit 0)
    (js/process.exit 1)))

;; ----------------------------------------------------------------------
;; Test-data reset (mirrors shadow.test.node/reset-test-data!).
;;
;; `:dev/always true` on the ns is required: `env/get-test-data` is a
;; macro that expands at compile time and must re-resolve every cycle.

(defn ^:dev/after-load reset-test-data! []
  (-> (env/get-test-data)
      (env/reset-test-data!)))

;; ----------------------------------------------------------------------
;; CLI arg parsing — same shape shadow.test.node ships so the existing
;; `npm run test:cljs -- --test=foo` form keeps working.

(defn parse-args [args]
  (reduce
    (fn [opts arg]
      (cond
        (= "--help" arg)
        (assoc opts :help true)

        (= "--list" arg)
        (assoc opts :list true)

        (str/starts-with? arg "--test=")
        (let [test-arg (subs arg 7)
              test-syms
              (->> (str/split test-arg ",")
                   (map symbol))]
          (update opts :test-syms into test-syms))

        :else
        (do (println (str "Unknown arg: " arg))
            opts)))
    {:test-syms []}
    args))

(defn find-matching-test-vars [test-syms]
  (let [test-namespaces (->> test-syms (filter simple-symbol?) (set))
        test-var-syms   (->> test-syms (filter qualified-symbol?) (set))]
    (->> (env/get-test-vars)
         (filter (fn [the-var]
                   (let [{:keys [name ns]} (meta the-var)]
                     (or (contains? test-namespaces ns)
                         (contains? test-var-syms (symbol ns name)))))))))

(defn execute-cli [{:keys [test-syms help list] :as _opts}]
  (let [test-env (ct/empty-env)]
    (cond
      help
      (do (println "Usage:")
          (println "  --list (list known test names)")
          (println "  --test=<ns-to-test>,<fqn-symbol-to-test> (run test for namespace or single var, separated by comma)"))

      list
      (doseq [[ns ns-info] (->> (env/get-tests) (sort-by first))]
        (println "Namespace:" ns)
        (doseq [var (:vars ns-info)
                :let [m (meta var)]]
          (println (str "  " (:ns m) "/" (:name m))))
        (println "---------------------------------"))

      (seq test-syms)
      (let [test-vars (find-matching-test-vars test-syms)]
        (st/run-test-vars test-env test-vars))

      :else
      (st/run-all-tests test-env nil))))

(defn main [& args]
  (reset-test-data!)
  (if env/UI-DRIVEN
    (js/console.log "Waiting for UI ...")
    (let [opts (parse-args args)]
      (execute-cli opts))))
