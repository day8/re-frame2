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
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template! repo-root]])
  ;; java.nio types used directly by `link-node-modules!` below. The
  ;; tmp-dir / delete-recursively helpers that needed Path / LinkOption /
  ;; FileVisitOption moved to the shared test-support ns (rf2-5v619).
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; --- Helpers ---------------------------------------------------------------
;;
;; tmp-dir / delete-recursively / template-resource-dir / run-template! /
;; repo-root live in the shared `test-support` ns (rf2-5v619, D1). The
;; shared `run-template!` takes an optional 4th `include-story?` arg, so
;; the with-story behavioural tier below reuses it directly.

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
        (cond-> (-> deps
                    (assoc-in [:deps 'day8/re-frame2]
                              {:local/root (rel-of "implementation/core")})
                    (assoc-in [:deps adapter-coord]
                              {:local/root (rel-of (str "implementation/adapters/" (name substrate)))})
                    (assoc-in [:deps 'day8/re-frame2-causa]
                              {:local/root (rel-of "tools/causa")})
                    (assoc-in [:deps 'day8/re-frame2-schemas]
                              {:local/root (rel-of "implementation/schemas")}))
          ;; The with-story scaffold adds day8/re-frame2-story
          ;; (re-frame.story + re-frame.story.* live under tools/story/).
          ;; Rewrite it the same way so the with-story behavioural tier
          ;; resolves + compiles against the in-repo Story source.
          (contains? (:deps deps) 'day8/re-frame2-story)
          (assoc-in [:deps 'day8/re-frame2-story]
                    {:local/root (rel-of "tools/story")}))]
    (spit deps-file (with-out-str (pprint/pprint rewritten)))))

;; --- node_modules symlink --------------------------------------------------

(defn- junction-node-modules!
  "Windows fall-back when a symlink can't be created: a directory
  *junction* (`mklink /J`) reparse-points `dst` → `src` and — unlike a
  symbolic link — needs no `SeCreateSymbolicLinkPrivilege`, so it works
  on a stock Windows box without Developer Mode. Returns true on
  success. No-op (false) off Windows, where the symlink path already
  succeeded."
  [^java.io.File src ^java.io.File dst]
  (when (string/starts-with? (string/lower-case (System/getProperty "os.name")) "windows")
    (try
      ;; `cmd /c mklink /J <link> <target>` — both paths are passed as
      ;; native Windows paths (backslashes); ProcessBuilder doesn't run
      ;; the args through cmd's own parser, so no extra quoting needed.
      (let [pb (ProcessBuilder.
                 ^java.util.List ["cmd" "/c" "mklink" "/J"
                                  (.getPath dst)
                                  (.getCanonicalPath src)])]
        (.redirectErrorStream pb true)
        (let [p (.start pb)]
          (slurp (.getInputStream p))
          (zero? (.waitFor p))))
      (catch Throwable _ false))))

(defn- link-node-modules!
  "Make `<repo>/implementation/node_modules` available inside the
  emitted project as `node_modules`, so npm deps (React + peers)
  resolve. Two consumers need this:

    - the shadow-cljs `:browser` (`:app`) build resolves JS deps at
      *compile* time and searches ONLY the project-local `node_modules`
      — it does not honour `NODE_PATH`. The with-story tier compiles the
      `:app` build, so a real project-local `node_modules` is mandatory,
      not belt-and-braces.
    - `node` running the compiled `:node-test` bundle resolves React at
      *run* time, where `NODE_PATH` (set by the caller) is also honoured.

  Strategy: a directory symlink first; on Windows without
  `SeCreateSymbolicLinkPrivilege` (no Developer Mode) that fails, so we
  fall back to a directory *junction* (`mklink /J`), which needs no
  privilege. Returns true once `node_modules/react` resolves inside
  `proj-dir` — the caller asserts on it, because the `:browser` compile
  has no `NODE_PATH` safety net."
  [^java.io.File root ^java.io.File proj-dir]
  (let [src         (io/file root "implementation/node_modules")
        dst         (io/file proj-dir "node_modules")
        resolvable? #(.isDirectory (io/file dst "react"))]
    (cond
      (not (.isDirectory src))
      false

      (.exists dst)
      (resolvable?)

      :else
      (do
        (try
          (Files/createSymbolicLink (.toPath dst)
                                    (.toPath (.getCanonicalFile src))
                                    (into-array FileAttribute []))
          (catch Throwable _
            ;; Symlink-create requires SeCreateSymbolicLinkPrivilege on
            ;; Windows without Developer Mode. Fall back to a junction,
            ;; which doesn't.
            (junction-node-modules! src dst)))
        (resolvable?)))))

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

(defn- variant-label
  "A short human label distinguishing the default scaffold from the
  with-story scaffold, for tmp-dir prefixes + assertion messages."
  [substrate include-story?]
  (str (name substrate) (when include-story? "-with-story")))

(defn- compile-and-run-emitted-test!
  "For one substrate (optionally with-story): generate a tmp app,
  rewrite deps.edn → :local/root, link node_modules, run
  `clojure -M:shadow compile <targets>` (the with-story variant adds
  the `:app` build to the default `:test` build), then
  `node out/node-test.js`. Asserts both processes exit 0 and the
  expected cljs.test summary line is present.

  When `include-story?` is true the generated project is the with-story
  scaffold (`core_with_stories.cljs`, `deps_with_story.edn` with the
  extra day8/re-frame2-story coord, `stories.cljs`). The with-story
  variant additionally compiles the `:app` build — the only tier that
  actually shadow-compiles the with-story branch's distinctive code.
  `events_test.cljs` requires only events + subs, so the `:test` build
  alone never pulls `core_with_stories.cljs` / `stories.cljs` /
  re-frame.story onto the compile classpath; the `:app` build's
  `:init-fn` is `core/init`, which transitively requires all three.
  A broken with-story compile (re-frame.story API drift, a malformed
  deps_with_story.edn, a stories.cljs that won't load) therefore fails
  here rather than shipping green from string-presence / static-parse
  alone (rf2-5v619, G1; this also closes the `:app`-build half of L1).

  Caller is responsible for the env-var gate — this fn always runs."
  ([substrate] (compile-and-run-emitted-test! substrate false))
  ([substrate include-story?]
   (let [root  (repo-root)
         label (variant-label substrate include-story?)
         ;; Default path: compile only the node-test build (fast). The
         ;; with-story variant also compiles `:app`, the only build that
         ;; transitively pulls the with-story core + stories + Story
         ;; coord onto the classpath.
         compile-targets (if include-story? ["app" "test"] ["test"])
         tmp   (tmp-dir (str "rf2-template-run-" label "-"))]
     (try
       (let [proj (run-template! tmp "acme/my-app" substrate include-story?)]
         (rewrite-deps-for-local-run! root proj substrate)
         (let [linked? (link-node-modules! root proj)
               ;; NODE_PATH covers the `node` *run* step (Node honours it
               ;; at module-resolution time) and the `:node-test` *compile*
               ;; (shadow's node target falls back to it). The `:browser`
               ;; (`:app`) compile does NOT honour NODE_PATH — it searches
               ;; only the project-local node_modules — so the with-story
               ;; tier hard-requires `linked?` (a real symlink/junction).
               node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
               env-over  {"NODE_PATH" node-path}]
           (if include-story?
             (is linked?
                 (str "project-local node_modules must resolve for the "
                      "`:app` (:browser) compile — it ignores NODE_PATH. "
                      "Symlink/junction into " (.getPath proj)
                      " failed; ensure implementation/node_modules exists "
                      "(`npm install` in implementation/) and the OS allows "
                      "a symlink or `mklink /J` junction."))
             (is (or linked? (.isDirectory (io/file node-path)))
                 (str "implementation/node_modules must exist for `node` to "
                      "resolve React (run `npm install` in implementation/ first)")))

           ;; --- compile -----------------------------------------------------
           (testing (str label " — shadow-cljs compile "
                         (string/join " " compile-targets))
             (let [{:keys [exit out]}
                   (run-process! (into ["clojure" "-M:shadow" "compile"]
                                       compile-targets)
                                 proj env-over)]
               (is (zero? exit)
                   (str "`clojure -M:shadow compile "
                        (string/join " " compile-targets) "` exited " exit
                        " for " label ". Output:\n" out))))

           ;; --- run ---------------------------------------------------------
           (testing (str label " — node out/node-test.js")
             (let [bundle (io/file proj "out/node-test.js")]
               (is (.isFile bundle)
                   (str "Compile step produced out/node-test.js for " label))
               (when (.isFile bundle)
                 (let [{:keys [exit out]}
                       (run-process! ["node" "out/node-test.js"] proj env-over)]
                   (is (zero? exit)
                       (str "`node out/node-test.js` exited " exit
                            " for " label ". Output:\n" out))
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
         (delete-recursively tmp))))))

(defn- skip-if-disabled!
  "When the gate is off, record a passing assertion that documents the
  skip — so the green-run line count is stable across enabled/disabled
  modes and CI's grep doesn't have to special-case the gated path."
  [label]
  (is true
      (str "RF2_TEMPLATE_RUN_EMITTED_TESTS unset — skipping behavioural "
           "compile+run for " label
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

(deftest reagent-with-story-emitted-tests-run-test
  ;; G1 (rf2-5v619) — the only tier that actually shadow-compiles +
  ;; node-runs the `:include-story? true` scaffold. Reagent-only because
  ;; with-story is Reagent-only in v1 (hooks.clj data-fn guard). Same
  ;; events_test.cljs as the default path runs; the value here is that
  ;; the with-story core (`core_with_stories.cljs` requiring
  ;; re-frame.story + the stories ns), `deps_with_story.edn`, and
  ;; `stories.cljs` are all on the compile classpath — a broken
  ;; with-story compile fails the build before `node` ever runs.
  (testing "the emitted with-story Reagent app compiles (story scaffold
            on the classpath) + events_test.cljs runs green"
    (if-not @enabled?
      (skip-if-disabled! "reagent-with-story")
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :reagent true))))))
