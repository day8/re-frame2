(ns day8.re-frame2-template.emitted-test-run-test
  "Behavioural test for the template's emitted unit-test scaffold.

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
   pretending the smoke ran.

   ## The MANUAL setup-skill scaffold fixture

   The four substrate variants above materialise the GENERATOR template.
   The setup skill (`skills/re-frame2-setup/`) teaches a SECOND,
   genuinely different greenfield scaffold — a single-file Reagent
   counter wired with `:app/main` + `:initial-events` + a bare
   `frame-provider`, where the template compiles `:rf/default` +
   `with-frame` + `dispatch-sync` across split events/subs/views/schema
   files. Because the skill boot ceremony diverges from the template's,
   no equivalence assertion can prove the skill path compiles — only a
   real materialise + compile can.

   `setup-skill-scaffold-compiles-test` (below) closes that gap: it
   extracts the load-bearing fenced code blocks STRAIGHT FROM the skill
   markdown (`references/first-counter.md` → `src/your_app/core.cljs`;
   `references/shadow-cljs.md` → `shadow-cljs.edn` + `index.html` +
   `css/app.css`), synthesises the framework `deps.edn` the skill
   documents as `:git/sha`/`:local/root` (the skill deliberately ships
   no copy-complete deps.edn — the coords branch on publication state),
   rewrites the framework coords to `:local/root` via
   `rewrite-deps-for-local-run!`, links node_modules, and compiles the
   `:app` build. It asserts the build wires the Xray preload and the
   index.html ships the `[data-rf-xray-host]` host column.

   This is a SEMANTIC-DRIFT NET — it proves the skill's own snippets
   compile against the in-repo monorepo source, NOT that a published
   coordinate resolves. The per-PR published-coordinate buildability gate
   stays DEFERRED to publication; this fixture is the interim real-compile
   cover, riding the same `RF2_TEMPLATE_RUN_EMITTED_TESTS` gate as the
   template variants above."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template! repo-root]])
  ;; java.nio types used directly by `link-node-modules!` below. The
  ;; tmp-dir / delete-recursively helpers that need Path / LinkOption /
  ;; FileVisitOption live in the shared test-support ns.
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; --- Helpers ---------------------------------------------------------------
;;
;; tmp-dir / delete-recursively / template-resource-dir / run-template! /
;; repo-root live in the shared `test-support` ns. The shared
;; `run-template!` takes an optional 4th `include-story?` arg, so the
;; with-story behavioural tier below reuses it directly.

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
                    (assoc-in [:deps 'day8/re-frame2-xray]
                              {:local/root (rel-of "tools/xray")})
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

  Every variant compiles BOTH the `:app` (`:browser`) build and the
  `:test` (`:node-test`) build. Compiling `:app` is what shadow-compiles
  each substrate's entry point — `core.cljs`'s react-dom interop
  (`create-root` / `render-root` / `.render`), the adapter require, and
  the `defui` / `defnc` views. `events_test.cljs` requires only events +
  subs, so the `:test` build alone never pulls `core.cljs` onto the
  compile classpath; a broken UIx or Helix `core.cljs` (a wrong
  react-dom interop call, an adapter API rename, a view that won't
  compile) would otherwise ship green from shape + static-parse alone
  and surface only when a user runs `npx shadow-cljs watch app`.

  When `include-story?` is true the generated project is the with-story
  scaffold (`core_with_stories.cljs`, `deps_with_story.edn` with the
  extra day8/re-frame2-story coord, `stories.cljs`); its `:app` build's
  `:init-fn` (`core/init`) transitively requires `core_with_stories.cljs`
  / `stories.cljs` / re-frame.story, so a broken with-story compile
  (re-frame.story API drift, a malformed deps_with_story.edn, a
  stories.cljs that won't load) fails here too rather than shipping
  green.

  ## :advanced release build

  When the optional `release?` opt is true, the variant ALSO runs
  `clojure -M:shadow release app` — a `:advanced`/Closure-optimised
  compile — after the dev compile+run. NO other gate anywhere compiles
  the generated app under `:advanced`: every other template compile is
  dev `:none` (this fn's `compile app test`, the generated ci.yml's
  `compile test`), so `:advanced`-only failures (Closure DCE, externs,
  `^:export` munging, `:closure-define` elision) first hit a newcomer's
  `npm run release`. The release tier asserts exit 0, a non-empty
  release bundle (`resources/public/js/main.js`), and the cut-from-
  release invariant: the dev-only `:devtools/preloads
  [day8.re-frame2-xray.preload]` is stripped from the release build, so
  the Xray preload ns must NOT appear in the optimised bundle. It is
  caller-gated to the Reagent variants (default + with-story) — the
  `:advanced`-specific risks (Story `:closure-define` elision lives in
  the Reagent with-story scaffold; the `:app` module + `^:export init`
  shape is substrate-invariant) are exercised there without paying the
  ~30–60 s `:advanced` cost on all four variants.

  Caller is responsible for the env-var gate — this fn always runs."
  ([substrate] (compile-and-run-emitted-test! substrate false))
  ([substrate include-story?]
   (compile-and-run-emitted-test! substrate include-story? {}))
  ([substrate include-story? {:keys [release?] :or {release? false}}]
   (let [root  (repo-root)
         label (variant-label substrate include-story?)
         ;; Compile both the `:app` (:browser) build — the only build that
         ;; pulls each substrate's entry point (`core.cljs`) and views
         ;; onto the compile classpath — and the `:test` (:node-test)
         ;; build that runs `events_test.cljs`. Every substrate compiles
         ;; both, so a broken substrate entry point fails the gate.
         compile-targets ["app" "test"]
         tmp   (tmp-dir (str "rf2-template-run-" label "-"))]
     (try
       (let [proj (run-template! tmp "acme/my-app" substrate include-story?)]
         (rewrite-deps-for-local-run! root proj substrate)
         (let [linked? (link-node-modules! root proj)
               ;; NODE_PATH covers the `node` *run* step (Node honours it
               ;; at module-resolution time) and the `:node-test` *compile*
               ;; (shadow's node target falls back to it). The `:browser`
               ;; (`:app`) compile does NOT honour NODE_PATH — it searches
               ;; only the project-local node_modules. Every variant now
               ;; compiles `:app`, so all of them hard-require `linked?`
               ;; (a real symlink/junction), not just the with-story tier.
               node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
               env-overrides {"NODE_PATH" node-path}]
           (is linked?
               (str "project-local node_modules must resolve for the "
                    "`:app` (:browser) compile — it ignores NODE_PATH. "
                    "Symlink/junction into " (.getPath proj)
                    " failed; ensure implementation/node_modules exists "
                    "(`npm install` in implementation/) and the OS allows "
                    "a symlink or `mklink /J` junction."))

           ;; --- compile -----------------------------------------------------
           (testing (str label " — shadow-cljs compile "
                         (string/join " " compile-targets))
             (let [{:keys [exit out]}
                   (run-process! (into ["clojure" "-M:shadow" "compile"]
                                       compile-targets)
                                 proj env-overrides)]
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
                       (run-process! ["node" "out/node-test.js"] proj env-overrides)]
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
                       (str "expected '0 failures, 0 errors' line in output. Got:\n" out))))))

           ;; --- :advanced release build -------------------------------------
           ;; No other gate compiles the generated app under :advanced —
           ;; every other template compile is dev :none. Run
           ;; `clojure -M:shadow release app` so an :advanced-only failure
           ;; (Closure DCE/externs, ^:export munging, :closure-define
           ;; elision) is caught here rather than on a newcomer's first
           ;; `npm run release`. The :browser (:app) compile ignores
           ;; NODE_PATH, so the project-local node_modules symlink/junction
           ;; (`linked?`) is the same hard requirement as the dev compile.
           (when release?
             (testing (str label " — clojure -M:shadow release app (:advanced)")
               (let [{:keys [exit out]}
                     (run-process! ["clojure" "-M:shadow" "release" "app"]
                                   proj env-overrides)]
                 (is (zero? exit)
                     (str "`clojure -M:shadow release app` exited " exit
                          " for " label ". An :advanced-only break "
                          "(Closure DCE/externs, ^:export munging, "
                          ":closure-define elision) ships green from the "
                          "dev :none compile and surfaces only on a "
                          "newcomer's `npm run release`. Output:\n" out))
                 ;; The :app module is :main, so the optimised bundle lands
                 ;; at resources/public/js/main.js. A zero-exit with an
                 ;; empty/absent bundle would false-green.
                 (let [release-bundle (io/file proj "resources/public/js/main.js")]
                   (is (and (.isFile release-bundle)
                            (pos? (.length release-bundle)))
                       (str "`shadow release app` must emit a non-empty "
                            "resources/public/js/main.js for " label
                            ". Bundle: "
                            (if (.isFile release-bundle)
                              (str (.length release-bundle) " bytes")
                              "absent")))
                   ;; Cut-from-release invariant: :devtools/preloads
                   ;; [day8.re-frame2-xray.preload] is dev-only and stripped
                   ;; from release. The Xray preload ns must not appear in
                   ;; the optimised bundle (mangled goog ns segments survive
                   ;; verbatim in shadow's :advanced output for top-level
                   ;; provides). Guards against a regression that wires Xray
                   ;; into release or whose dev/release split misfires.
                   (when (.isFile release-bundle)
                     (let [bundle-text (slurp release-bundle)]
                       (is (not (string/includes?
                                  bundle-text
                                  "day8.re_frame2_xray.preload"))
                           (str "Xray preload ns must be CUT from the "
                                ":advanced release bundle for " label
                                " (:devtools/preloads is dev-only). Found "
                                "'day8.re_frame2_xray.preload' in "
                                "resources/public/js/main.js — the "
                                "cut-from-release invariant is broken."))
                       ;; events.cljs registers the default error-sink
                       ;; trace listener behind
                       ;; `(when ^boolean goog.DEBUG ...)`, so Closure
                       ;; must DCE both the listener closure AND its
                       ;; substituted "[acme.my-app]" console marker out
                       ;; of the `:advanced` + `goog.DEBUG=false` bundle.
                       ;; If the gate is dropped (or someone re-registers
                       ;; the listener ungated), the marker string
                       ;; survives verbatim — assert its absence so a
                       ;; dev-only console listener can't leak into the
                       ;; production bundle and ship green.
                       (is (not (string/includes?
                                  bundle-text
                                  "[acme.my-app]"))
                           (str "The dev-only error-sink listener marker "
                                "\"[acme.my-app]\" must be DCE'd from the "
                                ":advanced release bundle for " label
                                ". Its presence means the "
                                "`(when ^boolean goog.DEBUG ...)` gate "
                                "around events.cljs's register-listener! "
                                "is gone — a dev-only console listener "
                                "closure is leaking into the production "
                                "bundle (rf2-ek857f F2)."))))))))))
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
  ;; The Reagent default variant also runs the :advanced
  ;; release build (`release? true`). The `:app` module + `^:export init`
  ;; shape is substrate-invariant, so the default Reagent variant is the
  ;; canonical place to assert the release path compiles green and the
  ;; dev-only Xray preload is cut from the optimised bundle, without
  ;; paying the :advanced cost on UIx + Helix too.
  (testing "the emitted Reagent app's events_test.cljs compiles + runs green
            and the :advanced release build is clean"
    (if-not @enabled?
      (skip-if-disabled! :reagent)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :reagent false {:release? true}))))))

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
  ;; The only tier that actually shadow-compiles +
  ;; node-runs the `:include-story? true` scaffold. Reagent-only because
  ;; with-story is Reagent-only in v1 (hooks.clj data-fn guard). Same
  ;; events_test.cljs as the default path runs; the value here is that
  ;; the with-story core (`core_with_stories.cljs` requiring
  ;; re-frame.story + the stories ns), `deps_with_story.edn`, and
  ;; `stories.cljs` are all on the compile classpath — a broken
  ;; with-story compile fails the build before `node` ever runs.
  ;;
  ;; The with-story variant ALSO runs the :advanced release
  ;; build (`release? true`). This is where the Story `:closure-define`
  ;; elision risk lives: core_with_stories.cljs documents the
  ;; `:closure-defines {re-frame.story.config/enabled? false}` elision,
  ;; and a regression that makes the elided `:advanced` build fail to
  ;; compile (a reg-* macro that won't elide cleanly, a dead-code path
  ;; Closure rejects) ships green from the dev :none compile. NB the
  ;; template does NOT set that closure-define by default — `enabled?`
  ;; defaults true — so this tier asserts the release build COMPILES
  ;; (and the Xray preload is cut), not that Story body code is elided;
  ;; Story-body-elision is a documented opt-in (flip the closure-define),
  ;; not the default scaffold's invariant.
  (testing "the emitted with-story Reagent app compiles (story scaffold
            on the classpath) + events_test.cljs runs green + the
            :advanced release build is clean"
    (if-not @enabled?
      (skip-if-disabled! "reagent-with-story")
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :reagent true {:release? true}))))))

;; ===========================================================================
;; MANUAL setup-skill scaffold fixture
;; ===========================================================================
;;
;; A second materialise+compile fixture that proves the `re-frame2-setup`
;; SKILL's hand-written greenfield scaffold compiles against the in-repo
;; source tree. The skill teaches a genuinely different boot ceremony from
;; the generator template (single-file `your-app.core` with `:app/main` +
;; `:initial-events` + a bare `frame-provider` + `reg-view`, vs. the template's
;; split files with `:rf/default` + `with-frame` + `dispatch-sync` + a
;; schema), so no equivalence assertion against the template can prove the
;; skill path compiles — only a real compile can. This is a semantic-drift
;; NET (in-repo source compile), NOT a published-coordinate proof.

;; --- fenced-block extraction -----------------------------------------------

(def ^:private skill-setup-refs
  "Absolute dir of the re-frame2-setup skill's reference snippets."
  (delay (io/file (repo-root) "skills/re-frame2-setup/references")))

(defn- fenced-blocks
  "Every fenced code block in `md-text` tagged exactly `lang` (e.g.
  \"clojure\" / \"html\" / \"css\"), in document order. CRLF-tolerant —
  the skill reference files ship Windows line endings. Returns the block
  bodies (the text BETWEEN the fences), each with trailing CRLF/CR
  normalised to LF so the emitted scaffold file is clean regardless of
  the markdown's line-ending style."
  [md-text lang]
  (->> (re-seq (re-pattern (str "(?s)```" lang "\\r?\\n(.*?)```")) md-text)
       (map (fn [[_ body]] (-> body
                               (string/replace "\r\n" "\n")
                               (string/replace "\r" "\n"))))))

(defn- single-fenced-block
  "The unique fenced `lang` block in `md-text`, or the first block for
  which `pred` (optional) is truthy. Throws loudly if zero match — a
  skill-doc edit that renames the fence or moves the snippet must fail
  the fixture, not silently emit an empty scaffold file."
  ([md-text lang where] (single-fenced-block md-text lang where (constantly true)))
  ([md-text lang where pred]
   (let [blocks (filter pred (fenced-blocks md-text lang))]
     (when (empty? blocks)
       (throw (ex-info (str "setup-skill fixture: found no ```" lang
                            " fenced block " where
                            " — the skill snippet anchors moved; update the "
                            "fixture extractor (rf2-ae98go).")
                       {:lang lang :where where})))
     (first blocks))))

;; --- deps.edn synthesis (skill ships only the :git/sha / :local/root shape) -

(defn- harvest-template-pins
  "The setup skill documents its framework `deps.edn` as a `:git/sha` /
  `:local/root` shape with `<SHA>` / `<shadow-version>` placeholders
  (`references/deps-versions.md`) — it ships no copy-complete deps.edn,
  by design, because the coords branch on publication state. So we
  SYNTHESISE the deps.edn the skill describes, harvesting the
  substrate-invariant version pins (reagent + clojure + clojurescript +
  the `:shadow` alias's shadow-cljs/tools.namespace) from a one-shot
  generator emission. This keeps the fixture lockstep-drift-free: the
  pins always match what the template (and thus the skill, which mirrors
  it) currently ships — there is nothing to hand-maintain here.

  Returns {:reagent .. :clojure .. :clojurescript .. :shadow-alias ..}."
  []
  (let [tmp (tmp-dir "rf2-skill-pins-")]
    (try
      (let [proj (run-template! tmp "acme/my-app" :reagent)
            deps (edn/read-string (slurp (io/file proj "deps.edn")))]
        {:reagent       (get-in deps [:deps 'reagent/reagent :mvn/version])
         :clojure       (get-in deps [:deps 'org.clojure/clojure :mvn/version])
         :clojurescript (get-in deps [:deps 'org.clojure/clojurescript :mvn/version])
         :shadow-alias  (get-in deps [:aliases :shadow])})
      (finally
        (delete-recursively tmp)))))

(defn- skill-deps-edn
  "The synthesised greenfield `deps.edn` for the setup-skill scaffold,
  matching `references/deps-versions.md`'s documented shape: core +
  Reagent adapter + Xray (the day-one coords the skill counter actually
  needs — no schemas artefact, because the skill counter registers no
  app-db schema) + an explicit `reagent/reagent` pin + the required
  `:shadow` alias. The framework `day8/re-frame2*` coords carry a
  placeholder `:mvn/version`; `rewrite-deps-for-local-run!` swaps them
  for `:local/root` paths into the monorepo before the compile, so the
  placeholder version is never resolved."
  [{:keys [reagent clojure clojurescript shadow-alias]}]
  {:paths ["src"]
   :deps  {'org.clojure/clojure       {:mvn/version clojure}
           'org.clojure/clojurescript {:mvn/version clojurescript}
           'day8/re-frame2            {:mvn/version "PLACEHOLDER"}
           'day8/re-frame2-reagent    {:mvn/version "PLACEHOLDER"}
           'day8/re-frame2-xray       {:mvn/version "PLACEHOLDER"}
           'reagent/reagent           {:mvn/version reagent}}
   :aliases {:shadow shadow-alias}})

;; --- materialisation -------------------------------------------------------

(defn- materialise-skill-scaffold!
  "Write the setup-skill scaffold into `proj-dir` from the skill's own
  markdown fenced blocks (the load-bearing surfaces) plus a synthesised
  framework `deps.edn`. Returns proj-dir.

  Emitted tree:
    deps.edn                        (synthesised — see skill-deps-edn)
    shadow-cljs.edn                 (shadow-cljs.md day-one :builds block)
    src/your_app/core.cljs          (first-counter.md whole-file block)
    resources/public/index.html     (shadow-cljs.md html block)
    resources/public/css/app.css    (shadow-cljs.md css block)"
  [^java.io.File proj-dir pins]
  (let [refs            @skill-setup-refs
        first-counter   (slurp (io/file refs "first-counter.md"))
        shadow-md       (slurp (io/file refs "shadow-cljs.md"))
        ;; The whole-file Reagent counter — the only ```clojure block in
        ;; first-counter.md. ns is `your-app.core` → src/your_app/core.cljs.
        core-cljs       (single-fenced-block first-counter "clojure"
                                             "in first-counter.md")
        ;; The day-one shadow-cljs.edn build — the FIRST ```clojure block
        ;; in shadow-cljs.md that carries a `:builds` map (later clojure
        ;; blocks are the deps.edn :shadow alias fragment + the
        ;; ^:dev/after-load hook snippet).
        shadow-edn      (single-fenced-block shadow-md "clojure"
                                             "in shadow-cljs.md (the :builds block)"
                                             #(string/includes? % ":builds"))
        index-html      (single-fenced-block shadow-md "html" "in shadow-cljs.md")
        app-css         (single-fenced-block shadow-md "css" "in shadow-cljs.md")]
    (.mkdirs (io/file proj-dir "src/your_app"))
    (.mkdirs (io/file proj-dir "resources/public/css"))
    (spit (io/file proj-dir "deps.edn")
          (with-out-str (pprint/pprint (skill-deps-edn pins))))
    (spit (io/file proj-dir "shadow-cljs.edn") shadow-edn)
    (spit (io/file proj-dir "src/your_app/core.cljs") core-cljs)
    (spit (io/file proj-dir "resources/public/index.html") index-html)
    (spit (io/file proj-dir "resources/public/css/app.css") app-css)
    proj-dir))

;; --- the fixture -----------------------------------------------------------

(defn- compile-skill-scaffold! []
  (let [root (repo-root)
        pins (harvest-template-pins)
        tmp  (tmp-dir "rf2-skill-scaffold-")]
    (try
      (let [proj (io/file (.toString tmp) "my-app")]
        (.mkdirs proj)
        (materialise-skill-scaffold! proj pins)
        ;; The synthesised deps.edn carries placeholder :mvn/version
        ;; framework coords; rewrite them to :local/root against the
        ;; monorepo — same path the generator-template variants take.
        (rewrite-deps-for-local-run! root proj :reagent)
        (let [linked?   (link-node-modules! root proj)
              node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
              env       {"NODE_PATH" node-path}]
          (is linked?
              (str "project-local node_modules must resolve for the setup-skill "
                   "scaffold's `:app` (:browser) compile — it ignores NODE_PATH. "
                   "Symlink/junction into " (.getPath proj) " failed; ensure "
                   "implementation/node_modules exists (`npm install` in "
                   "implementation/) and the OS allows a symlink or `mklink /J` "
                   "junction."))

          ;; --- assert the scaffold wires the day-one Xray contract BEFORE
          ;; compiling (cheap structural locks on the extracted snippets) ----
          (testing "setup-skill scaffold wires the day-one Xray preload + host column"
            (let [shadow-text (slurp (io/file proj "shadow-cljs.edn"))
                  html-text   (slurp (io/file proj "resources/public/index.html"))]
              (is (string/includes? shadow-text "day8.re-frame2-xray.preload")
                  (str "the skill's day-one shadow-cljs.edn block "
                       "(references/shadow-cljs.md) no longer wires "
                       "`:devtools {:preloads [day8.re-frame2-xray.preload]}`. "
                       "Xray is a day-one dep and index.html ships the host "
                       "column — the canonical block must wire the preload that "
                       "fills it (rf2-ae98go)."))
              (is (string/includes? html-text "data-rf-xray-host")
                  (str "the skill's index.html block (references/shadow-cljs.md) "
                       "no longer carries the `[data-rf-xray-host]` Xray layout "
                       "host column (rf2-ae98go)."))))

          ;; --- compile the :app build -------------------------------------
          (testing "setup-skill scaffold — clojure -M:shadow compile app"
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow" "compile" "app"] proj env)]
              (is (zero? exit)
                  (str "`clojure -M:shadow compile app` exited " exit
                       " for the MANUAL setup-skill scaffold. The skill's "
                       "hand-written greenfield counter "
                       "(skills/re-frame2-setup/references/first-counter.md + "
                       "shadow-cljs.md) no longer compiles against the in-repo "
                       "re-frame2 source — its boot ceremony (`:app/main` + "
                       "`:initial-events` + bare `frame-provider` + `reg-view`) "
                       "diverges from the generator template, so the template "
                       "variants above can't catch this (rf2-ae98go). Output:\n"
                       out))
              ;; A zero-exit with no emitted bundle would false-green.
              (let [bundle (io/file proj "resources/public/js/main.js")]
                (is (and (.isFile bundle) (pos? (.length bundle)))
                    (str "`compile app` must emit a non-empty "
                         "resources/public/js/main.js for the setup-skill "
                         "scaffold. Bundle: "
                         (if (.isFile bundle)
                           (str (.length bundle) " bytes")
                           "absent"))))))))
      (finally
        (delete-recursively tmp)))))

(deftest setup-skill-scaffold-compiles-test
  ;; The interim real-compile cover for the MANUAL setup-skill
  ;; scaffold (the per-PR published-coordinate buildability gate stays
  ;; deferred to publication). Semantic-drift net: proves the skill's own
  ;; fenced snippets compile against the monorepo source, NOT that a
  ;; published coord resolves. Same `RF2_TEMPLATE_RUN_EMITTED_TESTS` gate
  ;; as the template variants above; `:app`-only compile (the skill's
  ;; day-one block ships a single `:app` build, no `:test` build).
  (testing "the re-frame2-setup skill's hand-written greenfield scaffold
            compiles against the in-repo source (Xray preload + host column wired)"
    (if-not @enabled?
      (skip-if-disabled! "setup-skill-scaffold")
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when @clojure-cli-available?
            (compile-skill-scaffold!))))))
