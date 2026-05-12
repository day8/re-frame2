;;;; tests/shim/shim_test.clj — bb-runnable shell-shim integration tests.
;;;;
;;;; For each documented script under `skills/re-frame-pair2/scripts/`,
;;;; assert exit code + stdout edn shape against a stubbed nREPL.
;;;;
;;;; Approach:
;;;;
;;;;   1. Spin up `stub_nrepl.clj` in a child bb process. It picks a free
;;;;      TCP port and writes it to `<tmpdir>/target/shadow-cljs/nrepl.port`
;;;;      — the canonical location ops.clj reads (per its
;;;;      `port-file-candidates`).
;;;;   2. Run each shim via Process API with cwd = <tmpdir> so the
;;;;      port-file lookup hits the stub.
;;;;   3. Parse stdout as edn and assert shape.
;;;;
;;;; Coverage rationale:
;;;;
;;;;   - tests/runtime/ exercises the pure CLJS helpers (parse-rf2-coord,
;;;;     epoch-matches?, etc.) — no nREPL.
;;;;   - tests/shim/ (this file) exercises the bash → bb → nREPL contract
;;;;     against a stub, so the shim wiring is validated per-push.
;;;;   - tests/e2e/ exercises the same shims against a live shadow-cljs
;;;;     build — slow, gated on a fixture being up.
;;;;
;;;; Run:    bb tests/shim/shim_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns shim-test
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]])
  (:import (java.io File)))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private skill-root
  ;; this file lives at skills/re-frame-pair2/tests/shim/shim_test.clj;
  ;; resolve the skill root absolutely so the tests don't depend on cwd.
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)   ;; tests/shim/
      (.getParentFile)   ;; tests/
      (.getParentFile))) ;; skills/re-frame-pair2/

(def ^:private scripts-dir
  (io/file skill-root "scripts"))

(defn- tmpdir
  "Create a fresh tmp directory rooted in tests/shim/target/run-<n>/."
  []
  (let [base   (io/file skill-root "tests" "shim" "target" "run")
        n      (str (System/currentTimeMillis) "-" (.hashCode (Thread/currentThread)))
        target (io/file (str (.getPath base) "-" n))]
    (.mkdirs target)
    target))

(defn- rmrf [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)] (rmrf child)))
    (.delete f)))

;; ---------------------------------------------------------------------------
;; Stub lifecycle
;; ---------------------------------------------------------------------------

(def ^:private stub-script
  (io/file skill-root "tests" "shim" "stub_nrepl.clj"))

(defn- write-cases! [^File cwd cases]
  (let [f (io/file cwd "target" "shim-cases.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str cases))))

(defn- wait-for-port-file [^File cwd timeout-ms]
  (let [pf (io/file cwd "target" "shadow-cljs" "nrepl.port")
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (.exists pf) (Integer/parseInt (str/trim (slurp pf)))
        (> (System/currentTimeMillis) deadline) nil
        :else (do (Thread/sleep 50) (recur))))))

(defn- start-stub [^File cwd cases]
  (write-cases! cwd cases)
  (let [proc (p/process {:dir cwd :err :inherit}
                        "bb" (.getAbsolutePath stub-script))
        port (wait-for-port-file cwd 5000)]
    (when-not port
      (p/destroy-tree proc)
      (throw (ex-info "stub-nrepl did not write port file within 5s" {})))
    {:proc proc :port port}))

(defn- stop-stub [{:keys [proc]}]
  (when proc
    (p/destroy-tree proc)
    @proc))

;; ---------------------------------------------------------------------------
;; Shim invocation
;; ---------------------------------------------------------------------------

(defn- run-shim
  "Invoke `bb ops.clj <subcmd> [args...]` from <cwd>. We call ops.clj
   directly rather than the .sh wrappers so the tests run on Windows
   bb without needing bash. The shims themselves are 3-line `exec bb
   ops.clj` wrappers — running ops.clj proves the same contract.

   Returns {:exit :stdout :stderr}."
  [^File cwd subcmd & args]
  (let [ops (io/file scripts-dir "ops.clj")
        cmd (into ["bb" (.getAbsolutePath ops) subcmd] args)
        res @(apply p/process {:dir cwd :out :string :err :string} cmd)]
    {:exit   (:exit res)
     :stdout (-> res :out str)
     :stderr (-> res :err str)}))

(defn- parse-edn [s]
  (try (edn/read-string s) (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Healthy-app case set
;; ---------------------------------------------------------------------------
;;
;; A small canned table the stub uses to answer cljs-eval requests. The
;; needles are substring-matched against the *inner CLJS form* (the
;; string inside the cljs-eval wrapper), so test authors target the
;; meaningful payload rather than the shadow-api wrapper.

(def healthy-cases
  {:default "nil"
   :matches
   {;; sentinel probe
    "(some? (and (exists? js/globalThis)" "true"
    ;; health
    "(re-frame-pair2.runtime/health)"
    (pr-str
      {:ok?                       true
       :session-id                "stub-session-xyz"
       :debug-enabled?            true
       :coord-annotation-enabled? true
       :last-click-capture?       true
       :frames                    [:rf/default]
       :selected-frame            nil
       :operating-frame           :rf/default
       :ambiguous-frame?          false
       :epoch-history-depth       256
       :epoch-counts              {:rf/default 0}
       :pair-epoch-count          0})
    ;; eval round-trip smoke
    "(+ 1 2)" "3"
    ;; pair-dispatch-sync! shape
    "pair-dispatch-sync!"
    (pr-str {:ok? true :epoch-id "ep-1" :event [:counter/inc] :frame :rf/default})
    ;; epochs-in-last-ms
    "epochs-in-last-ms"
    (pr-str [{:epoch-id "ep-1" :event-id :counter/inc :committed-at 1000}])
    ;; watch op poll — return a single matching epoch then empty
    "epochs-since"
    (pr-str {:epochs [] :id-aged-out? false :head-id "ep-1"})
    ;; tail-build probe
    "registrar-handler-ref" "\"hash-abc-1\""}})

(def missing-preload-cases
  ;; sentinel probe returns false → ops.clj emits :runtime-not-preloaded
  {:default "nil"
   :matches {"(some? (and (exists? js/globalThis)" "false"}})

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(defn- with-stub [cases f]
  (let [cwd (tmpdir)]
    (try
      (let [stub (start-stub cwd cases)]
        (try
          (f cwd)
          (finally (stop-stub stub))))
      (finally
        (rmrf cwd)))))

(deftest eval-cljs-round-trip
  (testing "ops eval `(+ 1 2)` → returns {:ok? true :value 3}"
    (with-stub healthy-cases
      (fn [cwd]
        (let [r (run-shim cwd "eval" "(+ 1 2)")]
          (is (zero? (:exit r)) (str "exit: " (:exit r) " stderr: " (:stderr r)))
          (let [edn (parse-edn (:stdout r))]
            (is (map? edn))
            (is (true? (:ok? edn)))
            (is (= 3 (:value edn)))))))))

(deftest discover-healthy
  (testing "ops discover with healthy preload → returns {:ok? true ...}"
    (with-stub healthy-cases
      (fn [cwd]
        (let [r   (run-shim cwd "discover")
              edn (parse-edn (:stdout r))]
          (is (zero? (:exit r)))
          (is (map? edn))
          (is (true? (:ok? edn)))
          (is (= [:rf/default] (:frames edn))))))))

(deftest discover-runtime-not-preloaded
  (testing "ops discover when sentinel is missing → :runtime-not-preloaded"
    (with-stub missing-preload-cases
      (fn [cwd]
        (let [r   (run-shim cwd "discover")
              edn (parse-edn (:stdout r))]
          (is (zero? (:exit r)))
          (is (false? (:ok? edn)))
          (is (= :runtime-not-preloaded (:reason edn)))
          (is (string? (:hint edn)) "hint present"))))))

(deftest dispatch-sync-success
  (testing "ops dispatch --sync returns the canned pair-dispatch result"
    (with-stub healthy-cases
      (fn [cwd]
        (let [r   (run-shim cwd "dispatch" "[:counter/inc]" "--sync")
              edn (parse-edn (:stdout r))]
          (is (zero? (:exit r)))
          (is (map? edn))
          (is (true? (:ok? edn)))
          (is (= "ep-1" (:epoch-id edn))))))))

(deftest trace-recent-shape
  (testing "ops trace-recent 3000 returns the window envelope with epochs"
    (with-stub healthy-cases
      (fn [cwd]
        (let [r   (run-shim cwd "trace-recent" "3000")
              edn (parse-edn (:stdout r))]
          (is (zero? (:exit r)))
          (is (map? edn))
          (is (true? (:ok? edn)))
          (is (= 3000 (:window-ms edn)))
          (is (vector? (:epochs edn)))
          (is (every? :epoch-id (:epochs edn))))))))

(deftest tail-build-soft-without-probe
  (testing "ops tail-build with no --probe → :soft? true"
    (with-stub healthy-cases
      (fn [cwd]
        (let [r   (run-shim cwd "tail-build")
              edn (parse-edn (:stdout r))]
          (is (zero? (:exit r)))
          (is (true? (:ok? edn)))
          (is (true? (:soft? edn))
              "no probe ⇒ shim returns soft confirmation (per docs/TESTING §probe-based reload)"))))))

(deftest unknown-subcommand
  (testing "ops <bogus> exits non-zero with structured edn"
    ;; doesn't need the stub; the dispatcher rejects before reaching nREPL
    (let [cwd (tmpdir)]
      (try
        (let [r (run-shim cwd "no-such-op")]
          (is (= 1 (:exit r)))
          (let [edn (parse-edn (:stdout r))]
            (is (false? (:ok? edn)))
            (is (= :unknown-subcommand (:reason edn)))))
        (finally (rmrf cwd))))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'shim-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
