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

   This test closes the gap: for each substrate (Reagent / UIx)
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
        reagent / uix substrate; running the bundle therefore
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
   genuinely different greenfield scaffold — a hand-written counter wired
   with `:rf/default` + `make-frame` + `with-frame` + `register-schema!` +
   `dispatch-sync` + `frame-provider {:frame …}`, extracted STRAIGHT FROM
   the skill's reference markdown. Because the skill ships its recipe as
   copyable fenced blocks (not as the generator's resource tree), no
   equivalence assertion against the template can prove the skill path
   compiles — only a real materialise + compile can.

   `setup-skill-scaffold-compiles-test` (below) closes that gap for EACH
   documented manual route — Reagent and UIx — because both
   are executable manual scaffolds the skill ships copyable source for:

     * Reagent — the one-file counter (`references/first-counter.md` →
       `src/your_app/core.cljs`).
     * UIx — the substrate-neutral dataflow
       (`references/shared-dataflow.md` → `events.cljs` + `subs.cljs` +
       `schema.cljs`) plus the substrate entry ns + views
       (`references/entry-namespace.md` → `core.cljs` + `views.cljs`).

   All three share the `shadow-cljs.edn` + `index.html` + `css/app.css`
   from `references/shadow-cljs.md`. For each route the fixture
   synthesises the day-one `deps.edn` the skill documents
   (`references/deps-versions.md`): core + the selected adapter + schemas
   + Xray + the substrate's own Maven deps + the `:shadow` alias. It
   asserts those DIRECT day-one coordinates are present BEFORE the
   `:local/root` rewrite (`assert-skill-deps-shape!`) — the
   dependency-honesty net — then rewrites the framework coords via
   `rewrite-deps-for-local-run!`, links node_modules, and compiles the
   `:app` build (Xray preload + `[data-rf-xray-host]` host column
   asserted).

   ## Why the pre-rewrite dependency-shape assertion is load-bearing

   A compile alone cannot prove the skill's deps.edn is dependency-HONEST.
   `day8/re-frame2-xray` (a day-one dep) itself depends on
   `day8/re-frame2-schemas`, and each adapter `:local/root` pulls its own
   substrate Maven dep transitively — so a scaffold whose OWN deps.edn
   omits the direct `schemas` (or substrate) coordinate still compiles
   green through those transitive edges. That is exactly the false-green
   the skill must not teach: if the skill tells a consumer `schemas` is a
   day-one dep, the net must PROVE it is present as a DIRECT coordinate,
   not merely resolvable. `assert-skill-deps-shape!` pins every direct
   day-one coordinate the skill documents before any rewrite, so dropping
   one fails the fixture even though the compile would still succeed.

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
             :refer [tmp-dir delete-recursively run-template!
                     run-template-opts! repo-root]])
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
        ;; The EXPERIMENTAL :ui substrate's artefact is implementation/ui
        ;; (day8/re-frame2-ui), NOT a per-substrate adapter under
        ;; implementation/adapters/.
        adapter-path  (if (= :ui substrate)
                        "implementation/ui"
                        (str "implementation/adapters/" (name substrate)))
        rewritten
        (cond-> (-> deps
                    (assoc-in [:deps 'day8/re-frame2]
                              {:local/root (rel-of "implementation/core")})
                    (assoc-in [:deps adapter-coord]
                              {:local/root (rel-of adapter-path)})
                    (assoc-in [:deps 'day8/re-frame2-schemas]
                              {:local/root (rel-of "implementation/schemas")}))
          ;; The :ui scaffold deliberately ships NO Xray coord (minimal
          ;; consumer shape), so the Xray rewrite is presence-gated —
          ;; an unconditional assoc-in would ADD the coord to a scaffold
          ;; whose contract is exactly that it is absent.
          (contains? (:deps deps) 'day8/re-frame2-xray)
          (assoc-in [:deps 'day8/re-frame2-xray]
                    {:local/root (rel-of "tools/xray")})
          ;; The with-story scaffold adds day8/re-frame2-story
          ;; (re-frame.story + re-frame.story.* live under tools/story/).
          ;; Rewrite it the same way so the with-story behavioural tier
          ;; resolves + compiles against the in-repo Story source.
          (contains? (:deps deps) 'day8/re-frame2-story)
          (assoc-in [:deps 'day8/re-frame2-story]
                    {:local/root (rel-of "tools/story")})

          ;; The SSR scaffold adds day8/re-frame2-ssr + day8/re-frame2-ssr-ring
          ;; (re-frame.ssr + re-frame.ssr.ring live under implementation/ssr +
          ;; implementation/ssr-ring). Rewrite them the same way so the
          ;; emitted headless JVM ssr_test.clj resolves + runs against the
          ;; in-repo SSR source.
          (contains? (:deps deps) 'day8/re-frame2-ssr)
          (assoc-in [:deps 'day8/re-frame2-ssr]
                    {:local/root (rel-of "implementation/ssr")})

          (contains? (:deps deps) 'day8/re-frame2-ssr-ring)
          (assoc-in [:deps 'day8/re-frame2-ssr-ring]
                    {:local/root (rel-of "implementation/ssr-ring")}))]
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

;; --- SSR DOM-adoption browser proof ---------------------------------------
;;
;; A short Clojure script that renders the REAL `/` response through the emitted
;; `server/make-handler` (server-painted `#app` markup + the `__rf_payload`
;; script + the matching `data-rf-render-hash`), rewrites the `/main.js`
;; bootstrap to the static `/js/main.js` path, and spits it to
;; `resources/public/_ssr_proof.html`. The `ssr-hydration-dom-proof.cjs` driver
;; then serves that public root and drives Chromium to prove the scaffold's
;; client boot ADOPTS the server DOM (hydrate-root) rather than mounting a fresh
;; tree (create-root). The test always scaffolds `acme/my-app`, so the server
;; namespace is fixed. `/main.js` occurs only in the emitted bootstrap, so the
;; unquoted replace is unambiguous.
;;
;; The form is written to a `.clj` script and run as `clojure -M <file>`, NOT
;; passed via `-e`: a quoted `-e` form does not survive Windows ProcessBuilder
;; argument escaping (the inner string quotes get mangled), whereas a script
;; file reaches clojure.main byte-for-byte.
(def ^:private ssr-proof-capture-script
  (str "(require '[re-frame.core :as rf] '[re-frame.ssr :as ssr] 'acme.my-app.server)\n"
       "(rf/init! ssr/adapter)\n"
       "(let [h ((resolve (symbol \"acme.my-app.server\" \"make-handler\")) \"resources/public\")\n"
       "      body (str (:body (h {:uri \"/\" :request-method :get})))\n"
       "      html (clojure.string/replace body \"/main.js\" \"/js/main.js\")]\n"
       "  (spit \"resources/public/_ssr_proof.html\" html)\n"
       "  (println \"PROOF_HTML_BYTES\" (count html)))\n"))

(defn- run-ssr-dom-adoption-proof!
  "Stage the real server-painted `/` page, then drive Chromium to prove the
  generated scaffold adopts the server DOM. Assumes `shadow compile app`
  already built the bundle and the deps are `:local/root`-rewritten. Node-gated:
  records a passing skip assertion when `node` is unavailable."
  [^java.io.File root ^java.io.File proj]
  (testing "reagent-ssr — browser DOM-adoption proof (hydrate-root adopts server DOM)"
    (if-not @node-available?
      (is true
          "`node` unavailable — skipping the SSR DOM-adoption browser proof
           (static-parse + hydrate-root/create-root shape coverage still applies)")
      (do
        ;; Render the real `/` response through the emitted server and stage it.
        (let [script (io/file proj "_ssr_proof_capture.clj")
              _      (spit script ssr-proof-capture-script)
              {:keys [exit out]}
              (run-process! ["clojure" "-M" (.getName script)] proj)]
          (is (zero? exit)
              (str "capturing the server-painted SSR page (clojure -M script over "
                   "server/make-handler on `/`) exited " exit ". Output:\n" out))
          (is (.isFile (io/file proj "resources/public/_ssr_proof.html"))
              "the captured _ssr_proof.html was written for the browser proof"))
        ;; Drive Chromium: the exact server node must be ADOPTED (its expando
        ;; survives) and its handler must go live. Reverting the payload branch
        ;; to create-root makes this fail — a fresh node with no expando.
        (let [driver    (.getCanonicalPath
                          (io/file root "tools/template/test-support/ssr-hydration-dom-proof.cjs"))
              pub-root  (.getCanonicalPath (io/file proj "resources/public"))
              impl-root (.getCanonicalPath (io/file root "implementation"))
              node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
              {:keys [exit out]}
              (run-process! ["node" driver pub-root impl-root]
                            proj {"NODE_PATH" node-path})]
          ;; Exit 2 = the driver could not launch Chromium (a tier that enables
          ;; this proof without `npx playwright install --with-deps`); record a
          ;; documented skip so the tier stays green. Exit 0 = adopted; any
          ;; other exit = the proof FAILED (fresh mount / dead handler).
          (if (= 2 exit)
            (is true
                (str "Chromium unavailable — skipping the SSR DOM-adoption "
                     "browser proof (the real tooth runs where a browser is "
                     "provisioned). Output:\n" out))
            (is (zero? exit)
                (str "the SSR DOM-adoption browser proof exited " exit
                     " — the generated scaffold did not adopt the server-painted "
                     "DOM (hydrate-root) on a payload-backed boot: the live #app "
                     "node was not the server node, or its handler was dead. "
                     "Output:\n" out))))))))

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
  compile classpath; a broken UIx `core.cljs` (a wrong
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
           ;; The emitted :shadow alias is deps-only (NO :main-opts — the
           ;; npx wrapper appends its own `-m`, and a second one kills
           ;; shadow's arg parser), so the pure-JVM route here names the
           ;; CLI ns explicitly.
           (testing (str label " — shadow-cljs compile "
                         (string/join " " compile-targets))
             (let [{:keys [exit out]}
                   (run-process! (into ["clojure" "-M:shadow"
                                        "-m" "shadow.cljs.devtools.cli"
                                        "compile"]
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
                     (run-process! ["clojure" "-M:shadow"
                                    "-m" "shadow.cljs.devtools.cli"
                                    "release" "app"]
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
                                "bundle."))))))))))
       (finally
         (delete-recursively tmp))))))

(defn- run-ssr-emitted-test!
  "Compile both halves of an emitted SSR project, for the given `:css` mode
   (nil = plain, `:tailwind` = the Tailwind cell).

   The browser build compiles core.cljc's hydration branch. `clojure -M:test`
   runs the JVM render and Ring-handler tests — including the live-shell CSS
   scaffold + contained static-asset serving (ssr_test.clj), which is what the
   emitted npm test script and CI invoke. The browser compile needs
   project-local node_modules; the tests themselves need no Node runtime or DOM.

   Running BOTH css modes (plain + Tailwind) through the real make-handler is
   what proves the documented `:css` + `:include-ssr?` combination composes on
   the actual SSR response (rf2-3fc89f.26).

   `browser-proof?` (plain css only) additionally drives the compiled bundle in
   Chromium to prove the client boot ADOPTS the server-painted DOM
   (`hydrate-root`) rather than mounting a fresh `create-root` tree (rf2-w1k3i).
   DOM adoption is css-invariant, so the Tailwind cell skips it."
  ([] (run-ssr-emitted-test! nil true))
  ([css] (run-ssr-emitted-test! css false))
  ([css browser-proof?]
   (let [root (repo-root)
         tmp  (tmp-dir (str "rf2-template-run-reagent-ssr-"
                            (if css (name css) "plain") "-"))]
    (try
      (let [proj (run-template-opts! tmp "acme/my-app"
                                     (cond-> {:substrate :reagent :include-ssr? true}
                                       css (assoc :css css)))]
        (rewrite-deps-for-local-run! root proj :reagent)
        (let [linked?   (link-node-modules! root proj)
              node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
              env-overrides {"NODE_PATH" node-path}]
          (is linked?
              (str "project-local node_modules must resolve for the SSR "
                   "`:app` (:browser) compile — it ignores NODE_PATH. "
                   "Symlink/junction into " (.getPath proj)
                   " failed; ensure implementation/node_modules exists "
                   "(`npm install` in implementation/) and the OS allows "
                   "a symlink or `mklink /J` junction."))

          ;; --- client compile ----------------------------------------------
          ;; `shadow compile app` pulls core.cljc's #?(:cljs) hydration branch
          ;; (reagent.dom.client interop, ssr/hydrate!, ^:export init) onto the
          ;; compile classpath — the client half ssr_test.clj (JVM :clj) never
          ;; touches. A broken react-dom.client interop / adapter API rename /
          ;; macro-expansion error fails here rather than on a newcomer's first
          ;; `npx shadow-cljs watch app`.
          (testing "reagent-ssr — clojure -M:shadow compile app (client :cljs hydration branch)"
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow"
                                 "-m" "shadow.cljs.devtools.cli"
                                 "compile" "app"]
                                proj env-overrides)]
              (is (zero? exit)
                  (str "`clojure -M:shadow compile app` exited " exit
                       " for the emitted SSR scaffold. The client #?(:cljs) "
                       "hydration branch of core.cljc did not compile against "
                       "the in-repo source (reagent/re-frame2-* rewritten to "
                       ":local/root). Output:\n" out))))

          ;; --- server gate -------------------------------------------------
          (testing "reagent-ssr — clojure -M:test (headless JVM ssr_test.clj)"
            (let [{:keys [exit out]} (run-process! ["clojure" "-M:test"] proj)]
              (is (zero? exit)
                  (str "`clojure -M:test` exited " exit
                       " for the emitted SSR scaffold. The headless JVM "
                       "ssr_test.clj gate did not pass against the in-repo SSR "
                       "source (implementation/ssr + ssr-ring rewritten to "
                       ":local/root). Output:\n" out))
              ;; cljs.test's default reporter prints
              ;;   "Ran N tests containing M assertions."
              ;;   "0 failures, 0 errors."
              ;; Pin both lines so a silent zero-exit (no tests discovered by
              ;; the cognitect runner) doesn't false-green.
              (is (re-find #"Ran \d+ tests? containing \d+ assertions" out)
                  (str "expected 'Ran N tests' summary line — a silent "
                       "zero-test run would otherwise pass. Got:\n" out))
              (is (re-find #"0 failures, 0 errors" out)
                  (str "expected '0 failures, 0 errors' line in output. "
                       "Got:\n" out))))

          ;; --- browser proof (DOM adoption) -------------------------------
          ;; Prove the payload-backed client boot ADOPTS the server-painted
          ;; DOM (hydrate-root), not a fresh create-root mount. Reuses the
          ;; just-built bundle + the real server render. Plain css only —
          ;; DOM adoption is css-invariant (rf2-w1k3i).
          (when browser-proof?
            (run-ssr-dom-adoption-proof! root proj))))
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
  ;; paying the :advanced cost on UIx too.
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

(deftest ui-emitted-tests-run-test
  ;; The EXPERIMENTAL :ui scaffold's behavioural proof: the `:app`
  ;; compile puts core.cljs (rf/init! ui/adapter + ui/mount +
  ;; ui/frame-root) and the compiled `defview` views through a REAL
  ;; shadow build with the re-frame.ui compiler build hook live — the
  ;; post-S6 one-setting contract actually harvesting registries — and
  ;; the `:test` compile + node run boots the scaffold's dataflow
  ;; (events/subs against the plain-atom substrate). A broken ui/mount
  ;; grammar, a compiler rejection of the emitted views, or a missing
  ;; hook would fail here rather than on a newcomer's first
  ;; `npx shadow-cljs watch app`.
  (testing "the emitted EXPERIMENTAL :ui app compiles (compiled views +
            build hook on a real shadow build) + events_test.cljs runs
            green"
    (if-not @enabled?
      (skip-if-disabled! :ui)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :ui))))))

(deftest reagent-with-story-emitted-tests-run-test
  ;; The only tier that actually shadow-compiles +
  ;; node-runs the `:include-story? true` scaffold. Reagent-only because
  ;; with-story is currently Reagent-only (hooks.clj data-fn guard). Same
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

(deftest reagent-ssr-emitted-tests-run-test
  ;; The SSR scaffold's TWO halves: the client `:cljs` hydration branch via
  ;; `clojure -M:shadow compile app` (core.cljc's #?(:cljs)
  ;; reagent.dom.client interop + ssr/hydrate! + ^:export init, which the
  ;; JVM `ssr_test.clj` :clj-only load never compiles), and the server
  ;; render via the headless JVM gate (`ssr_test.clj`) run through the
  ;; emitted deps.edn `:test` alias (`clojure -M:test`) — the exact path
  ;; `npm test` and the generated CI invoke on the SSR scaffold.
  ;; Reagent-only because `:include-ssr?` currently supports Reagent. Needs
  ;; `clojure` on PATH plus a project-local node_modules for the `:app`
  ;; compile (React via reagent; no `node` RUNTIME — the browser compile is
  ;; JVM/Closure-driven and the JVM SSR render is a pure hiccup -> HTML
  ;; emitter), and rides the same RF2_TEMPLATE_RUN_EMITTED_TESTS gate.
  (testing "the emitted SSR Reagent app's client :cljs branch compiles
            (`shadow compile app`) + its ssr_test.clj runs green via
            `clojure -M:test` (the JVM gate npm/CI now invoke)"
    (if-not @enabled?
      (skip-if-disabled! "reagent-ssr")
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when @clojure-cli-available?
            (run-ssr-emitted-test!))))))

(deftest reagent-ssr-tailwind-emitted-tests-run-test
  ;; The {:include-ssr? true :css :tailwind} matrix cell run end-to-end
  ;; (rf2-3fc89f.26). Same two-halves shape as the plain SSR cell above, but
  ;; the Tailwind CSS overlay is in play — so the emitted ssr_test.clj's
  ;; live-shell assertions run against the Tailwind head (the
  ;; @tailwindcss/browser dev compiler + the Tailwind /css/app.css entry) on
  ;; the REAL make-handler path. Running BOTH css modes through the real
  ;; handler is what proves the documented `:css` + `:include-ssr?`
  ;; combination composes on the actual SSR response, not just in the unused
  ;; static index.html.
  (testing "the emitted SSR+Tailwind Reagent app's client :cljs branch
            compiles + its ssr_test.clj runs green via `clojure -M:test`
            (the live shell loads the Tailwind compiler + serves the CSS)"
    (if-not @enabled?
      (skip-if-disabled! "reagent-ssr-tailwind")
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when @clojure-cli-available?
            (run-ssr-emitted-test! :tailwind))))))

;; ===========================================================================
;; MANUAL setup-skill scaffold fixture
;; ===========================================================================
;;
;; A second materialise+compile fixture that proves the `re-frame2-setup`
;; SKILL's hand-written greenfield scaffold compiles against the in-repo
;; source tree — for EACH documented manual route (Reagent / UIx),
;; each with exactly its documented direct day-one dependencies. The skill
;; ships its recipe as copyable fenced blocks with the current
;; schema-bearing boot ceremony (`:rf/default` + `make-frame` +
;; `with-frame` + `register-schema!` + `dispatch-sync` + `frame-provider
;; {:frame …}`), so no equivalence assertion against the generator template
;; can prove the skill path compiles — only a real compile can. Two things
;; the net proves that the cheap gates cannot: (1) each route's snippets
;; compile (syntax, namespace graph, adapter-root call, views); and (2) the
;; synthesised deps.edn is dependency-HONEST — every direct day-one
;; coordinate the skill documents is present, not silently resolved through
;; a transitive Xray → schemas (or adapter → substrate) edge
;; (`assert-skill-deps-shape!`). This is a semantic-drift NET (in-repo
;; source compile), NOT a published-coordinate proof.

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
                            "fixture extractor.")
                       {:lang lang :where where})))
     (first blocks))))

;; --- deps.edn synthesis (skill documents the :git/sha / :local/root shape) --

(def ^:private substrate-dep-symbols
  "The substrate's own Maven coords the skill documents as DIRECT day-one
  deps beyond the four re-frame2 coords — the JS-substrate libraries each
  route's copied source requires. Reagent: `reagent/reagent`
  (`references/deps-versions.md`). UIx: `com.pitch/uix.core` +
  `com.pitch/uix.dom`
  (`references/entry-namespace.md` §UIx greenfield). Versions are
  harvested from the generator template (source of truth); this map only
  names the coordinates each route must carry directly."
  {:reagent ['reagent/reagent]
   :uix     ['com.pitch/uix.core 'com.pitch/uix.dom]})

(defn- harvest-skill-deps-inputs
  "Emit the generator template for `substrate` once and read back
  everything the synthesised skill deps.edn must keep in lockstep with
  the template: the substrate-invariant clojure / clojurescript pins +
  the `:shadow` alias (shadow-cljs / tools.namespace build deps), plus
  the substrate's own Maven coords (`substrate-dep-symbols`) at the exact
  versions the template pins. Harvesting from the template — the source
  of truth the skill mirrors — keeps the fixture drift-free: there is
  nothing to hand-maintain here.

  Returns {:clojure .. :clojurescript .. :shadow-alias .. :substrate-deps ..}."
  [substrate]
  (let [tmp (tmp-dir (str "rf2-skill-pins-" (name substrate) "-"))]
    (try
      (let [proj (run-template! tmp "acme/my-app" substrate)
            deps (edn/read-string (slurp (io/file proj "deps.edn")))]
        {:clojure        (get-in deps [:deps 'org.clojure/clojure :mvn/version])
         :clojurescript  (get-in deps [:deps 'org.clojure/clojurescript :mvn/version])
         :shadow-alias   (get-in deps [:aliases :shadow])
         :substrate-deps (into {}
                               (map (fn [sym] [sym (get-in deps [:deps sym])]))
                               (get substrate-dep-symbols substrate))})
      (finally
        (delete-recursively tmp)))))

(defn- skill-deps-edn
  "The synthesised greenfield `deps.edn` for the setup-skill scaffold in
  `substrate`, matching `references/deps-versions.md`'s documented day-one
  shape: core + the selected substrate adapter + schemas + Xray (the FOUR
  re-frame2 day-one coords the skill documents for every substrate) + the
  substrate's own Maven deps (reagent/reagent for Reagent;
  com.pitch/uix.{core,dom} for UIx) + the
  required `:shadow` alias.

  `day8/re-frame2-schemas` is a DIRECT day-one coordinate — the counter
  attaches a whole-app-db schema and `:require`s `re-frame.schemas`
  (`first-counter.md` / `shared-dataflow.md`), and `deps-versions.md`
  lists it as a day-one coord. It is NOT left to resolve transitively via
  Xray; `assert-skill-deps-shape!` pins that honesty before the
  `:local/root` rewrite.

  The four framework `day8/re-frame2*` coords carry a placeholder
  `:mvn/version`; `rewrite-deps-for-local-run!` swaps them for
  `:local/root` paths into the monorepo before the compile, so the
  placeholder version is never resolved. The substrate Maven deps resolve
  normally from Clojars."
  [substrate {:keys [clojure clojurescript shadow-alias substrate-deps]}]
  (let [adapter-coord (symbol "day8" (str "re-frame2-" (name substrate)))]
    {:paths ["src"]
     :deps  (merge {'org.clojure/clojure       {:mvn/version clojure}
                    'org.clojure/clojurescript {:mvn/version clojurescript}
                    'day8/re-frame2            {:mvn/version "PLACEHOLDER"}
                    adapter-coord              {:mvn/version "PLACEHOLDER"}
                    'day8/re-frame2-schemas    {:mvn/version "PLACEHOLDER"}
                    'day8/re-frame2-xray       {:mvn/version "PLACEHOLDER"}}
                   substrate-deps)
     :aliases {:shadow shadow-alias}}))

;; --- dependency-honesty net ------------------------------------------------

(defn- required-direct-coords
  "The DIRECT day-one coordinate keys the skill documents for `substrate`
  (`references/deps-versions.md` + `entry-namespace.md`): core + the
  selected adapter + schemas + Xray + the substrate's own Maven deps. The
  synthesised deps.edn must carry every one as a DIRECT `:deps` key — none
  may be left to resolve transitively (Xray → schemas; adapter
  `:local/root` → substrate dep)."
  [substrate]
  (concat ['day8/re-frame2
           (symbol "day8" (str "re-frame2-" (name substrate)))
           'day8/re-frame2-schemas
           'day8/re-frame2-xray]
          (get substrate-dep-symbols substrate)))

(defn- assert-skill-deps-shape!
  "The dependency-honesty teeth: assert the SYNTHESISED deps map carries
  every direct day-one coordinate the skill documents for `substrate`
  (`required-direct-coords`), BEFORE `rewrite-deps-for-local-run!` points
  anything at `:local/root`. A compile alone cannot supply this teeth —
  `day8/re-frame2-xray` depends on `day8/re-frame2-schemas`, and each
  adapter `:local/root` pulls its own substrate Maven dep transitively, so
  a compile stays green even when the scaffold's OWN deps.edn omits the
  direct coordinate. Only a shape assertion on the direct keys catches
  that dishonesty. Returns `deps-map`."
  [substrate deps-map]
  (let [direct (:deps deps-map)]
    (doseq [coord (required-direct-coords substrate)]
      (is (contains? direct coord)
          (str "setup-skill deps-honesty (" (name substrate) "): the "
               "synthesised deps.edn must carry a DIRECT `" coord "` "
               "coordinate — references/deps-versions.md documents it as a "
               "day-one dep. It is absent, yet a compile would still go "
               "green because "
               (cond
                 (= coord 'day8/re-frame2-schemas)
                 "day8/re-frame2-xray depends on schemas transitively"
                 (contains? (set (get substrate-dep-symbols substrate)) coord)
                 (str "the " (name substrate) " adapter :local/root pulls it "
                      "transitively")
                 :else
                 "it resolves through another coord transitively")
               " — masking the missing DIRECT dependency the skill teaches. "
               "Add it to skill-deps-edn (and confirm the skill reference "
               "still documents it as day-one)."))))
  deps-map)

;; --- materialisation -------------------------------------------------------

(defn- materialise-skill-scaffold!
  "Write the setup-skill scaffold for `substrate` into `proj-dir` from the
  skill's own markdown fenced blocks (the load-bearing surfaces) plus a
  synthesised, dependency-honest framework `deps.edn`. Returns proj-dir.

  Shared across substrates (from `references/shadow-cljs.md`):
    deps.edn                        (synthesised — see skill-deps-edn;
                                     shape-asserted before any rewrite)
    shadow-cljs.edn                 (the day-one :builds block)
    resources/public/index.html     (the html block)
    resources/public/css/app.css    (the css block)

  Reagent route (from `references/first-counter.md`):
    src/your_app/core.cljs          (the one-file counter block)

  UIx route (shared-dataflow.md + entry-namespace.md):
    src/your_app/events.cljs        (shared-dataflow.md events block)
    src/your_app/subs.cljs          (shared-dataflow.md subs block)
    src/your_app/schema.cljs        (shared-dataflow.md schema block)
    src/your_app/core.cljs          (entry-namespace.md substrate core block)
    src/your_app/views.cljs         (entry-namespace.md substrate views block)"
  [^java.io.File proj-dir substrate pins]
  (let [refs        @skill-setup-refs
        shadow-md   (slurp (io/file refs "shadow-cljs.md"))
        ;; The day-one shadow-cljs.edn build — the FIRST ```clojure block
        ;; in shadow-cljs.md that carries a `:builds` map (later clojure
        ;; blocks are the deps.edn :shadow alias fragment + the
        ;; ^:dev/after-load hook snippet). Substrate-invariant.
        shadow-edn  (single-fenced-block shadow-md "clojure"
                                         "in shadow-cljs.md (the :builds block)"
                                         #(string/includes? % ":builds"))
        index-html  (single-fenced-block shadow-md "html" "in shadow-cljs.md")
        app-css     (single-fenced-block shadow-md "css" "in shadow-cljs.md")
        deps        (skill-deps-edn substrate pins)]
    (.mkdirs (io/file proj-dir "src/your_app"))
    (.mkdirs (io/file proj-dir "resources/public/css"))
    ;; Dependency-honesty net: prove every direct day-one coord the skill
    ;; documents is present BEFORE rewrite-deps-for-local-run! can inject a
    ;; :local/root (or a transitive Xray/adapter edge can mask an omission).
    (assert-skill-deps-shape! substrate deps)
    (spit (io/file proj-dir "deps.edn")
          (with-out-str (pprint/pprint deps)))
    (spit (io/file proj-dir "shadow-cljs.edn") shadow-edn)
    (spit (io/file proj-dir "resources/public/index.html") index-html)
    (spit (io/file proj-dir "resources/public/css/app.css") app-css)
    (if (= substrate :reagent)
      ;; Reagent: the whole-file counter — the only ```clojure block in
      ;; first-counter.md. ns `your-app.core` → src/your_app/core.cljs.
      (let [first-counter (slurp (io/file refs "first-counter.md"))
            core-cljs     (single-fenced-block first-counter "clojure"
                                               "in first-counter.md")]
        (spit (io/file proj-dir "src/your_app/core.cljs") core-cljs))
      ;; UIx: the substrate-neutral dataflow (events/subs/schema
      ;; from shared-dataflow.md) + the substrate entry ns + views (from
      ;; entry-namespace.md). This is the COMPLETE emitted project for a
      ;; non-Reagent greenfield — nothing from first-counter.md.
      (let [shared      (slurp (io/file refs "shared-dataflow.md"))
            entry       (slurp (io/file refs "entry-namespace.md"))
            sub-name    (name substrate)
            events      (single-fenced-block
                          shared "clojure" "(events.cljs) in shared-dataflow.md"
                          #(string/includes? % "(ns your-app.events"))
            subs        (single-fenced-block
                          shared "clojure" "(subs.cljs) in shared-dataflow.md"
                          #(string/includes? % "(ns your-app.subs"))
            schema      (single-fenced-block
                          shared "clojure" "(schema.cljs) in shared-dataflow.md"
                          #(string/includes? % "(ns your-app.schema"))
            ;; Select the substrate core/views blocks by a
            ;; substrate-unique token: UIx core requires `uix.dom`,
            ;; UIx views use `defui`. (One-arm `case` — a future
            ;; substrate route fails loudly here.)
            core-token  (case substrate :uix "uix.dom")
            views-token (case substrate :uix "defui")
            core-cljs   (single-fenced-block
                          entry "clojure"
                          (str "(" sub-name " core.cljs) in entry-namespace.md")
                          #(and (string/includes? % "(ns your-app.core")
                                (string/includes? % core-token)))
            views-cljs  (single-fenced-block
                          entry "clojure"
                          (str "(" sub-name " views.cljs) in entry-namespace.md")
                          #(and (string/includes? % "(ns your-app.views")
                                (string/includes? % views-token)))]
        (spit (io/file proj-dir "src/your_app/events.cljs") events)
        (spit (io/file proj-dir "src/your_app/subs.cljs")   subs)
        (spit (io/file proj-dir "src/your_app/schema.cljs") schema)
        (spit (io/file proj-dir "src/your_app/core.cljs")   core-cljs)
        (spit (io/file proj-dir "src/your_app/views.cljs")  views-cljs)))
    proj-dir))

;; --- the fixture -----------------------------------------------------------

(defn- compile-skill-scaffold! [substrate]
  (let [root (repo-root)
        pins (harvest-skill-deps-inputs substrate)
        tmp  (tmp-dir (str "rf2-skill-scaffold-" (name substrate) "-"))]
    (try
      (let [proj (io/file (.toString tmp) "my-app")]
        (.mkdirs proj)
        (materialise-skill-scaffold! proj substrate pins)
        ;; The synthesised deps.edn carries placeholder :mvn/version
        ;; framework coords; rewrite them to :local/root against the
        ;; monorepo — same path the generator-template variants take.
        (rewrite-deps-for-local-run! root proj substrate)
        (let [linked?   (link-node-modules! root proj)
              node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
              env       {"NODE_PATH" node-path}]
          (is linked?
              (str "project-local node_modules must resolve for the setup-skill "
                   (name substrate) " scaffold's `:app` (:browser) compile — it "
                   "ignores NODE_PATH. Symlink/junction into " (.getPath proj)
                   " failed; ensure implementation/node_modules exists "
                   "(`npm install` in implementation/) and the OS allows a "
                   "symlink or `mklink /J` junction."))

          ;; --- assert the scaffold wires the day-one Xray contract BEFORE
          ;; compiling (cheap structural locks on the extracted snippets).
          ;; shadow-cljs.edn + index.html are substrate-invariant. ----------
          (testing (str "setup-skill " (name substrate)
                        " scaffold wires the day-one Xray preload + host column")
            (let [shadow-text (slurp (io/file proj "shadow-cljs.edn"))
                  html-text   (slurp (io/file proj "resources/public/index.html"))]
              (is (string/includes? shadow-text "day8.re-frame2-xray.preload")
                  (str "the skill's day-one shadow-cljs.edn block "
                       "(references/shadow-cljs.md) no longer wires "
                       "`:devtools {:preloads [day8.re-frame2-xray.preload]}`. "
                       "Xray is a day-one dep and index.html ships the host "
                       "column — the canonical block must wire the preload that "
                       "fills it."))
              (is (string/includes? html-text "data-rf-xray-host")
                  (str "the skill's index.html block (references/shadow-cljs.md) "
                       "no longer carries the `[data-rf-xray-host]` Xray layout "
                       "host column."))))

          ;; --- compile the :app build -------------------------------------
          (testing (str "setup-skill " (name substrate)
                        " scaffold — clojure -M:shadow compile app")
            ;; The harvested :shadow alias is deps-only (no :main-opts),
            ;; so name the shadow CLI ns explicitly — same rationale as
            ;; the template variants above.
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow"
                                 "-m" "shadow.cljs.devtools.cli"
                                 "compile" "app"] proj env)]
              (is (zero? exit)
                  (str "`clojure -M:shadow compile app` exited " exit
                       " for the MANUAL setup-skill " (name substrate)
                       " scaffold. The skill's hand-written greenfield counter "
                       "(skills/re-frame2-setup/references/"
                       (if (= substrate :reagent)
                         "first-counter.md"
                         "shared-dataflow.md + entry-namespace.md")
                       " + shadow-cljs.md) no longer compiles against the "
                       "in-repo re-frame2 source. Its schema-bearing boot "
                       "ceremony (`make-frame` → `with-frame` / "
                       "`register-schema!` / `dispatch-sync` → scoped "
                       "`frame-provider {:frame …}` render) diverges from the "
                       "generator template, so the template variants above "
                       "can't catch this. Output:\n" out))
              ;; A zero-exit with no emitted bundle would false-green.
              (let [bundle (io/file proj "resources/public/js/main.js")]
                (is (and (.isFile bundle) (pos? (.length bundle)))
                    (str "`compile app` must emit a non-empty "
                         "resources/public/js/main.js for the setup-skill "
                         (name substrate) " scaffold. Bundle: "
                         (if (.isFile bundle)
                           (str (.length bundle) " bytes")
                           "absent"))))))))
      (finally
        (delete-recursively tmp)))))

(deftest setup-skill-scaffold-compiles-test
  ;; The interim real-compile cover for the MANUAL setup-skill scaffold,
  ;; for EACH documented substrate route (Reagent / UIx) — each
  ;; materialised solely from shipped skills/re-frame2-setup references and
  ;; compiled with exactly its documented direct day-one dependencies. The
  ;; per-PR published-coordinate buildability gate stays deferred to
  ;; publication. Semantic-drift net: proves the skill's own fenced
  ;; snippets compile against the monorepo source AND that each route's
  ;; deps.edn is dependency-honest (assert-skill-deps-shape!), NOT that a
  ;; published coord resolves. Same `RF2_TEMPLATE_RUN_EMITTED_TESTS` gate
  ;; as the template variants above; `:app`-only compile (the skill's
  ;; day-one block ships a single `:app` build, no `:test` build).
  (testing "the re-frame2-setup skill's hand-written greenfield scaffold
            compiles against the in-repo source for EACH documented
            substrate (Reagent / UIx), each with exactly its
            documented direct day-one dependencies"
    (if-not @enabled?
      (skip-if-disabled! "setup-skill-scaffold")
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when @clojure-cli-available?
            (doseq [substrate [:reagent :uix]]
              (testing (str "— " (name substrate) " route")
                (compile-skill-scaffold! substrate))))))))
