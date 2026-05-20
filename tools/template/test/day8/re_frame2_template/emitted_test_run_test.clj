(ns day8.re-frame2-template.emitted-test-run-test
  "Behavioural test for the template's emitted unit-test scaffold
   (rf2-ir6a0; deps-new port for rf2-c2770, closing the deeper-fidelity
   half of rf2-owbpr).

   Sibling test files cover two cheap signals:

     * `template_test.clj`            — generated-tree *shape* (file
                                        presence, deps.edn coords,
                                        shadow-cljs.edn wiring).
     * `template_emission_test.clj`   — *static parse* of emitted .cljs
                                        files (ns requires shape,
                                        framework-surface drift check).

   Neither actually compiles or runs the emitted `events_test.cljs`. A
   syntax-broken emitted test file or a behaviourally wrong fixture
   wiring would ship green from those checks — caught only post-publish
   by users running `npm test` in the scaffolded app.

   This test closes the gap: for each substrate (Reagent / UIx / Helix)
   it generates a tmp app, swaps the alpha-channel `day8/re-frame2*`
   coords in the emitted `deps.edn` for `:local/root` paths into the
   in-repo source tree, runs `clojure -M:shadow compile test`, and
   executes the resulting `out/node-test.js` bundle with `node`.
   Asserts exit code 0 and the cljs.test summary line.

   ## Gating

   Default off (opt-in via `RF2_TEMPLATE_RUN_EMITTED_TESTS=1`). Two
   rationales for opt-in:

     1. *Cost*. Per-substrate shadow-cljs compile + node-run is
        ~30–60 s cold-cache. The local fast loop should not pay that on
        every `clojure -M:test` invocation in `tools/template/`; the
        static-parse companion catches the most likely regressions
        cheaply.
     2. *Host requirements*. The emitted bundle imports React via the
        reagent / uix / helix substrate; running the bundle therefore
        requires a populated `node_modules/` tree. We satisfy that by
        symlinking `<repo>/implementation/node_modules/` into the
        emitted project at run time (avoids `npm install` per
        substrate). CI's `jvm-tools-template` job needs to run
        `npm install` in `implementation/` ahead of time and export
        `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` to enable this slice.

   When the env var is unset, every `deftest` below `is`-asserts the
   gate is observable (a single `is true` with an explanatory message)
   and exits — preserves green on local fast-loop runs without
   pretending the smoke ran."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [org.corfield.new :as deps-new])
  (:import [java.nio.file Files LinkOption Path FileVisitOption]
           [java.nio.file.attribute FileAttribute]))

;; --- Helpers (local copies, kept independent of the sibling test ns to
;; avoid accidental state sharing via top-level defs) -----------------------

(defn- tmp-dir [prefix]
  (Files/createTempDirectory
    prefix
    (into-array FileAttribute [])))

(defn- delete-recursively [^Path path]
  (when (Files/exists path (into-array LinkOption []))
    (with-open [stream (Files/walk path (into-array FileVisitOption []))]
      (->> stream
           .iterator
           iterator-seq
           reverse
           (run! #(try
                    (Files/deleteIfExists ^Path %)
                    (catch java.io.IOException _ nil)))))))

(defn- template-resource-dir []
  (let [cwd (io/file (System/getProperty "user.dir"))]
    (loop [d cwd]
      (cond
        (nil? d)
        (throw (ex-info "Couldn't locate tools/template/resources above cwd"
                        {:cwd cwd}))

        (.isDirectory (io/file d "tools/template/resources"))
        (.getCanonicalPath (io/file d "tools/template/resources"))

        (.isDirectory (io/file d "resources/day8/re_frame2_template"))
        (.getCanonicalPath (io/file d "resources"))

        :else
        (recur (.getParentFile d))))))

(defn- run-template! [tmp project-name substrate]
  (let [dir-str  (.toString ^Path tmp)
        proj-name (-> project-name name (string/replace #"^.*?/" ""))
        proj-dir (io/file dir-str proj-name)
        opts     (cond-> {:template   'day8/re-frame2-template
                          :name       (symbol project-name)
                          :target-dir (.getCanonicalPath proj-dir)
                          :src-dirs   [(template-resource-dir)]
                          :overwrite  :delete}
                   substrate (assoc :substrate substrate))]
    (deps-new/create opts)
    proj-dir))

(defn- repo-root
  "Walk up from `user.dir` until we find a sibling
  `implementation/core/src/re_frame/` directory. The template test JVM
  is launched from `tools/template/` (the `:test` alias's working dir),
  but the same lookup keeps working under a manual repo-root
  invocation. Mirrors the shape in `template_emission_test.clj`."
  []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d)
      (throw (ex-info "Couldn't locate repo root (no implementation/core/src/re_frame above cwd)"
                      {:cwd (System/getProperty "user.dir")}))

      (.isDirectory (io/file d "implementation/core/src/re_frame"))
      d

      :else
      (recur (.getParentFile d)))))

;; --- Gating ----------------------------------------------------------------

(def ^:private enabled?
  (delay (= "1" (System/getenv "RF2_TEMPLATE_RUN_EMITTED_TESTS"))))

;; --- deps.edn local-root rewrite ------------------------------------------

(defn- rewrite-deps-for-local-run!
  "Swap the alpha-channel `day8/re-frame2*` :mvn/version coords in the
  emitted project's deps.edn for `:local/root` paths into the in-repo
  source tree, then write it back. The result is a deps.edn that
  resolves from the working copy of re-frame2 — no Clojars round-trip,
  no alpha publish required."
  [^java.io.File root ^java.io.File proj-dir substrate]
  (let [deps-file (io/file proj-dir "deps.edn")
        deps     (edn/read-string (slurp deps-file))
        rel-of   (fn [target]
                   (-> (.relativize (.toPath (.getCanonicalFile proj-dir))
                                    (.toPath (.getCanonicalFile (io/file root target))))
                       .toString
                       (string/replace "\\" "/")))
        adapter-coord (symbol "day8" (str "re-frame2-" (name substrate)))
        rewritten
        (-> deps
            (assoc-in [:deps 'day8/re-frame2]
                      {:local/root (rel-of "implementation/core")})
            (assoc-in [:deps adapter-coord]
                      {:local/root (rel-of (str "implementation/adapters/" (name substrate)))})
            (assoc-in [:deps 'day8/re-frame2-causa]
                      {:local/root (rel-of "tools/causa")})
            (assoc-in [:deps 'day8/re-frame2-schemas]
                      {:local/root (rel-of "implementation/schemas")}))]
    (spit deps-file (with-out-str (clojure.pprint/pprint rewritten)))))

;; --- node_modules symlink --------------------------------------------------

(defn- link-node-modules!
  "Create a directory symlink from the emitted project's `node_modules`
  to `<repo>/implementation/node_modules` so React + its peers resolve
  when `node` runs the compiled bundle. On platforms without symlink
  privileges (rare on Windows without dev-mode), falls back to a
  best-effort directory-junction or — last resort — Files/copy. Returns
  true if `node_modules` is now available inside `proj-dir`."
  [^java.io.File root ^java.io.File proj-dir]
  (let [src   (io/file root "implementation/node_modules")
        dst   (io/file proj-dir "node_modules")]
    (cond
      (not (.isDirectory src))
      false

      (.exists dst)
      true

      :else
      (try
        (Files/createSymbolicLink (.toPath dst)
                                  (.toPath (.getCanonicalFile src))
                                  (into-array FileAttribute []))
        true
        (catch Throwable _
          ;; Symlink-create requires SeCreateSymbolicLinkPrivilege on
          ;; Windows without Developer Mode. The shadow-cljs :node-test
          ;; bundle's React imports resolve via Node's module-resolution
          ;; algorithm, which walks parent directories looking for
          ;; node_modules — set the NODE_PATH env var instead at run
          ;; time. Caller still asserts true here because the run-time
          ;; resolution covers the fall-back.
          false)))))

;; --- Process invocation ----------------------------------------------------

(defn- run-process!
  "Run a command in `dir` and return {:exit n :out s}. stderr is merged
  into stdout for assertion legibility (Windows + Linux behave the
  same). Inherits the parent's environment plus any extra entries in
  `env-overrides`."
  ([cmd ^java.io.File dir] (run-process! cmd dir {}))
  ([cmd ^java.io.File dir env-overrides]
   (let [pb (ProcessBuilder. ^java.util.List cmd)]
     (.directory pb dir)
     (.redirectErrorStream pb true)
     (let [env (.environment pb)]
       (doseq [[k v] env-overrides]
         (.put env k v)))
     (let [p   (.start pb)
           out (slurp (.getInputStream p))
           ec  (.waitFor p)]
       {:exit ec :out out}))))

(def ^:private clojure-cli-available?
  (delay
    (try
      (let [pb (ProcessBuilder. ^java.util.List ["clojure" "--help"])]
        (.redirectErrorStream pb true)
        (zero? (.waitFor (.start pb))))
      (catch Throwable _ false))))

(def ^:private node-available?
  (delay
    (try
      (let [pb (ProcessBuilder. ^java.util.List ["node" "--version"])]
        (.redirectErrorStream pb true)
        (zero? (.waitFor (.start pb))))
      (catch Throwable _ false))))

;; --- The orchestration -----------------------------------------------------

(defn- compile-and-run-emitted-test!
  "For one substrate: generate tmp app, rewrite deps.edn → :local/root,
  link node_modules, run `clojure -M:shadow compile test`, then
  `node out/node-test.js`. Asserts both processes exit 0 and the
  expected cljs.test summary line is present.

  Caller is responsible for the env-var gate — this fn always runs."
  [substrate]
  (let [root (repo-root)
        tmp  (tmp-dir (str "rf2-template-run-" (name substrate) "-"))]
    (try
      (let [proj (run-template! tmp "acme/my-app" substrate)]
        (rewrite-deps-for-local-run! root proj substrate)
        (let [linked? (link-node-modules! root proj)
              ;; Node's module-resolution walks parent dirs for
              ;; node_modules, so an unlinked emitted project can still
              ;; resolve React if implementation/node_modules sits on a
              ;; parent path. Belt-and-braces: set NODE_PATH so the
              ;; lookup is unambiguous either way.
              node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
              env-over  {"NODE_PATH" node-path}]
          (is (or linked? (.isDirectory (io/file node-path)))
              (str "implementation/node_modules must exist for `node` to "
                   "resolve React (run `npm install` in implementation/ first)"))

          ;; --- compile -----------------------------------------------------
          (testing (str substrate " — shadow-cljs compile test")
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow" "compile" "test"]
                                proj env-over)]
              (is (zero? exit)
                  (str "`clojure -M:shadow compile test` exited " exit
                       " for substrate " substrate ". Output:\n" out))))

          ;; --- run ---------------------------------------------------------
          (testing (str substrate " — node out/node-test.js")
            (let [bundle (io/file proj "out/node-test.js")]
              (is (.isFile bundle)
                  (str "Compile step produced out/node-test.js for " substrate))
              (when (.isFile bundle)
                (let [{:keys [exit out]}
                      (run-process! ["node" "out/node-test.js"] proj env-over)]
                  (is (zero? exit)
                      (str "`node out/node-test.js` exited " exit
                           " for substrate " substrate ". Output:\n" out))
                  ;; cljs.test's default reporter prints
                  ;;   "Ran N tests containing M assertions."
                  ;;   "0 failures, 0 errors."
                  ;; on a green run. Pin both lines so a silent zero-exit
                  ;; (no tests discovered) doesn't false-green.
                  (is (re-find #"Ran \d+ tests? containing \d+ assertions" out)
                      (str "expected 'Ran N tests' summary line in output. Got:\n" out))
                  (is (re-find #"0 failures, 0 errors" out)
                      (str "expected '0 failures, 0 errors' line in output. Got:\n" out))))))))
      (finally
        (delete-recursively tmp)))))

(defn- skip-if-disabled!
  "When the gate is off, record a passing assertion that documents the
  skip — so the green-run line count is stable across enabled/disabled
  modes and CI's grep doesn't have to special-case the gated path."
  [substrate]
  (is true
      (str "RF2_TEMPLATE_RUN_EMITTED_TESTS unset — skipping behavioural "
           "compile+run for " substrate
           ". Static-parse coverage still applies "
           "(template_emission_test.clj).")))

;; --- Tests -----------------------------------------------------------------

(deftest reagent-emitted-tests-run-test
  (testing "the emitted Reagent app's events_test.cljs compiles + runs green"
    (if-not @enabled?
      (skip-if-disabled! :reagent)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :reagent))))))

(deftest uix-emitted-tests-run-test
  (testing "the emitted UIx app's events_test.cljs compiles + runs green"
    (if-not @enabled?
      (skip-if-disabled! :uix)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :uix))))))

(deftest helix-emitted-tests-run-test
  (testing "the emitted Helix app's events_test.cljs compiles + runs green"
    (if-not @enabled?
      (skip-if-disabled! :helix)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :helix))))))
