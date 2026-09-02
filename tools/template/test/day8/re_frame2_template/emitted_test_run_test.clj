(ns day8.re-frame2-template.emitted-test-run-test
  "Behavioural tier for the emitted scaffold.

   The sibling files cover two cheap signals — `template_test.clj` (the
   generated-tree shape) and `template_emission_test.clj` (a static parse
   of the emitted `.cljs` against the framework surface). Neither compiles
   or runs anything. This tier does: for each substrate it generates a tmp
   app, swaps the `day8/re-frame2*` coords in the emitted `deps.edn` for
   `:local/root` paths into the in-repo source tree, compiles the `:app`
   and `:test` builds, runs `out/node-test.js`, loads the REAL emitted
   `index.html` in Chromium and proves it paints the counter and moves it
   0 → 1, and (Reagent) builds the `:advanced` release.

   ## The two consumer-realism teeth

   A compile-and-run tier that resolves everything from the monorepo is
   blind in two specific ways, and real defects shipped GREEN through it
   because of them. Both masks are closed here:

     * NODE_MODULES JUNCTION. `link-node-modules!` junctions
       `implementation/node_modules` into every emitted project, so the
       emitted `package.json` is never consulted — a scaffold that fails
       to declare a compile-required npm package still compiles. Closed by
       `assert-emitted-package-json-complete!`, which reads the `:browser`
       build's own `manifest.edn` and asserts every npm package the build
       resolved is one `npm install` would have produced from the EMITTED
       `package.json`.
     * NO DEV-PAGE BOOT PROOF. Nothing loaded the emitted `index.html` in
       a browser. Closed by `run-dev-page-boot-proof!` + its
       `test-support/dev-page-boot-proof.cjs` driver, which serves the
       emitted `resources/public`, loads the real page and proves it
       mounts, increments, and raises zero uncaught pageerrors — and by
       `run-broken-boot-witness!`, which breaks the page's mount node and
       requires that same driver to go RED, so a green proof is never the
       inert kind.

   Both must run BEFORE the optional `:advanced` release build, which
   overwrites the dev bundle and its manifest.

   ## Gating

   Default off (opt-in via `RF2_TEMPLATE_RUN_EMITTED_TESTS=1`): a cold
   shadow-cljs compile + Chromium per substrate is ~30–60 s, and the tier
   needs a populated `implementation/node_modules` (`npm ci` there) plus a
   Playwright Chromium. CI's `jvm-tools-template` job provides both. When
   the env var is unset every deftest records a single documented skip
   assertion and exits.

   ## The MANUAL setup-skill scaffold fixture

   `setup-skill-scaffold-compiles-test` materialises the `re-frame2-setup`
   SKILL's hand-written greenfield scaffold straight from its reference
   markdown — for each documented route (Reagent / UIx) — synthesises the
   day-one `deps.edn` it documents, and compiles the `:app` build against
   the in-repo source. It does not go through the template's emission at
   all; it is the interim real-compile cover for the skill's own
   snippets, riding the same gate."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template! repo-root]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; --- Gating ----------------------------------------------------------------

(def ^:private enabled?
  (delay (= "1" (System/getenv "RF2_TEMPLATE_RUN_EMITTED_TESTS"))))

;; --- deps.edn local-root rewrite ------------------------------------------

(defn- rewrite-deps-for-local-run!
  "Swap the `day8/re-frame2*` :mvn/version coords in a project's deps.edn
  for `:local/root` paths into the in-repo source tree, then write it
  back. Core and the substrate adapter are always rewritten; schemas and
  Xray only when the deps.edn names them (the template emits neither, the
  setup-skill scaffold names both on its Reagent route) — an unconditional
  assoc would ADD a coordinate the project does not declare."
  [^java.io.File root ^java.io.File proj-dir substrate]
  (let [deps-file (io/file proj-dir "deps.edn")
        deps      (edn/read-string (slurp deps-file))
        rel-of    (fn [target]
                    (-> (.relativize (.toPath (.getCanonicalFile proj-dir))
                                     (.toPath (.getCanonicalFile (io/file root target))))
                        .toString
                        (string/replace "\\" "/")))
        adapter-coord (symbol "day8" (str "re-frame2-" (name substrate)))
        adapter-path  (str "implementation/adapters/" (name substrate))
        rewritten
        (cond-> (-> deps
                    (assoc-in [:deps 'day8/re-frame2]
                              {:local/root (rel-of "implementation/core")})
                    (assoc-in [:deps adapter-coord]
                              {:local/root (rel-of adapter-path)}))
          (contains? (:deps deps) 'day8/re-frame2-schemas)
          (assoc-in [:deps 'day8/re-frame2-schemas]
                    {:local/root (rel-of "implementation/schemas")})
          (contains? (:deps deps) 'day8/re-frame2-xray)
          (assoc-in [:deps 'day8/re-frame2-xray]
                    {:local/root (rel-of "tools/xray")}))]
    (spit deps-file (with-out-str (pprint/pprint rewritten)))))

;; --- node_modules symlink --------------------------------------------------

(defn- junction-node-modules!
  "Windows fall-back when a symlink can't be created: a directory
  *junction* (`mklink /J`) reparse-points `dst` → `src` and — unlike a
  symbolic link — needs no `SeCreateSymbolicLinkPrivilege`. Returns true
  on success; false off Windows, where the symlink path already succeeded."
  [^java.io.File src ^java.io.File dst]
  (when (string/starts-with? (string/lower-case (System/getProperty "os.name")) "windows")
    (try
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
  "Make `<repo>/implementation/node_modules` available inside the emitted
  project as `node_modules`, so React resolves. The shadow-cljs `:browser`
  build searches ONLY the project-local `node_modules` at compile time (it
  does not honour `NODE_PATH`), so this is mandatory, not belt-and-braces.
  Symlink first; on Windows without Developer Mode fall back to a
  junction. Returns true once `node_modules/react` resolves."
  [^java.io.File root ^java.io.File proj-dir]
  (let [src         (io/file root "implementation/node_modules")
        dst         (io/file proj-dir "node_modules")
        resolvable? #(.isDirectory (io/file dst "react"))]
    (cond
      (not (.isDirectory src)) false
      (.exists dst)            (resolvable?)
      :else
      (do
        (try
          (Files/createSymbolicLink (.toPath dst)
                                    (.toPath (.getCanonicalFile src))
                                    (into-array FileAttribute []))
          (catch Throwable _
            (junction-node-modules! src dst)))
        (resolvable?)))))

;; --- Process invocation ----------------------------------------------------

(defn- run-process!
  "Run a command in `dir` and return {:exit n :out s}. stderr is merged
  into stdout. Inherits the parent's environment plus `env-overrides`."
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

;; --- emitted package.json completeness ------------------------------------
;;
;; MASK: this tier junctions `<repo>/implementation/node_modules` into every
;; emitted project, so the emitted `package.json` is NEVER consulted: every
;; npm package the compile graph reaches resolves out of the monorepo's tree
;; regardless of what the scaffold declares. An emitted `package.json` that
;; omits a compile-required package compiles GREEN here and fails on the
;; FIRST `npx shadow-cljs watch app` a real consumer runs.
;;
;; THE TOOTH: after `shadow compile app`, shadow writes `manifest.edn` into
;; the build's `:output-dir` listing every source in the build — including
;; the `node_modules/<pkg>/…` files it resolved. Assert that every npm
;; package in there is one `npm install` would actually have produced from
;; the EMITTED `package.json`: declared directly, or pulled in transitively
;; by something declared. Derived from the real compile graph, not a string
;; pin.

(defn- json-string-map-keys
  "The keys of the JSON object at top-level `field` in `json-text`, as a
  set of strings; nil when absent. Only ever applied to npm dependency
  maps, whose values are always strings, so a `[^}]*` body match is exact."
  [json-text field]
  (when-let [[_ body] (re-find (re-pattern (str "\"" field "\"\\s*:\\s*\\{([^}]*)\\}"))
                               json-text)]
    (set (map second (re-seq #"\"([^\"]+)\"\s*:" body)))))

(defn- top-level-npm-package
  "`node_modules/react/index.js` → `react`;
  `node_modules/@scope/pkg/dist/x.js` → `@scope/pkg`."
  [resource-path]
  (let [tail (last (string/split resource-path #"node_modules/"))
        segs (string/split tail #"/")]
    (if (string/starts-with? (first segs) "@")
      (str (first segs) "/" (second segs))
      (first segs))))

(defn- manifest-npm-packages
  [^java.io.File manifest]
  (->> (re-seq #"\"([^\"]*node_modules/[^\"]+)\"" (slurp manifest))
       (map (comp top-level-npm-package second))
       set))

(defn- npm-install-closure
  "Every package `npm install` would materialise from a top-level
  dependency set, resolved against the monorepo's installed tree: the seeds
  plus the transitive closure of each package's own `dependencies` +
  `peerDependencies`."
  [^java.io.File node-modules seeds]
  (loop [seen #{} queue (vec seeds)]
    (if-let [pkg (first queue)]
      (if (seen pkg)
        (recur seen (subvec queue 1))
        (let [pj   (io/file node-modules pkg "package.json")
              next (when (.isFile pj)
                     (let [t (slurp pj)]
                       (into (or (json-string-map-keys t "dependencies") #{})
                             (or (json-string-map-keys t "peerDependencies") #{}))))]
          (recur (conj seen pkg) (into (subvec queue 1) next))))
      seen)))

(defn- assert-emitted-package-json-complete!
  "Requires a finished `shadow compile app` and must run BEFORE any
  `release` build overwrites its manifest."
  [^java.io.File root ^java.io.File proj label]
  (testing (str label " — emitted package.json declares every npm package the "
                "compile graph resolves")
    (let [manifest (io/file proj "resources/public/js/manifest.edn")
          pkg-json (io/file proj "package.json")]
      (is (.isFile manifest)
          (str "`shadow compile app` must emit resources/public/js/manifest.edn for "
               label))
      (is (.isFile pkg-json)
          (str "the scaffold must emit a package.json for " label))
      (when (and (.isFile manifest) (.isFile pkg-json))
        (let [resolved  (manifest-npm-packages manifest)
              pj-text   (slurp pkg-json)
              declared  (into (or (json-string-map-keys pj-text "dependencies") #{})
                              (or (json-string-map-keys pj-text "devDependencies") #{}))
              installed (npm-install-closure (io/file root "implementation/node_modules")
                                             declared)
              missing   (sort (remove installed resolved))]
          (println (str "  [package.json completeness] " label ": build resolves "
                        (count resolved) " npm package(s) "
                        (pr-str (sort resolved))
                        (if (seq missing)
                          (str " -- UNDECLARED: " (pr-str (vec missing)))
                          " -- all reachable from the emitted package.json")))
          ;; Vacuous-pass guard: every dev build resolves React at minimum.
          (is (seq resolved)
              (str "the " label " build's manifest.edn must list at least one "
                   "node_modules source; an empty resolved set means the manifest "
                   "was not parsed. Manifest: " (.getPath manifest)))
          (is (empty? missing)
              (str "EMITTED package.json IS INCOMPLETE for " label ": the compile "
                   "graph resolves " (pr-str missing) " but the scaffold neither "
                   "declares it nor pulls it in transitively — a real consumer's "
                   "`npm install` would not install it and their first "
                   "`npx shadow-cljs watch app` fails with a missing JS "
                   "dependency. Declare it in _shared/package.json, pinned "
                   "lockstep with implementation/package.json."
                   "\n  resolved by the build: " (pr-str (sort resolved))
                   "\n  declared by the scaffold: " (pr-str (sort declared)))))))))

;; --- browser-proof verdicts ------------------------------------------------
;;
;; The driver exits 2 when Chromium is not launchable. Under CI that is a
;; hard FAILURE (every job that turns this tier on also installs a browser);
;; locally it is a documented skip under an unmissable banner. A skip that
;; reads as a pass is exactly how the proof went unexecuted for months.

(def ^:private browser-proofs-required?
  "`CI` is the de-facto standard flag; export `CI=1` to opt a local run
  into the strict behaviour."
  (delay (not (string/blank? (System/getenv "CI")))))

(defn- announce-browser-skip!
  [what out]
  (println)
  (println "!!! ================================================================")
  (println (str "!!! NOT PROVEN -- SKIPPED: " what))
  (println "!!! Chromium is not launchable here, so this browser proof did NOT run.")
  (println "!!! This run stays green, but NOTHING about the emitted page is proven.")
  (println "!!! (Under CI this is a hard failure — see browser-proofs-required?.)")
  (when-let [line (first (remove string/blank? (string/split-lines (str out))))]
    (println (str "!!! driver: " (string/trim line))))
  (println "!!! ================================================================")
  (println))

(defn- check-browser-proof!
  "Turn the driver's exit code into a verdict. 0 passes and echoes the
  driver's own verdict line; 2 is a local skip or a CI failure; anything
  else failed with `failure-msg`."
  [what echo-tag exit out failure-msg]
  (cond
    (and (= 2 exit) (not @browser-proofs-required?))
    (do (announce-browser-skip! what out)
        (is true (str "Chromium unavailable — " what " did not run. Output:\n" out)))

    (= 2 exit)
    (is false
        (str what " did NOT run: Chromium was not launchable, and `CI` is set. "
             "Every job that sets RF2_TEMPLATE_RUN_EMITTED_TESTS=1 also runs "
             "`npx playwright install --with-deps chromium`, so a CI job that "
             "finds no browser is BROKEN, not excused. Output:\n" out))

    :else
    (do (when (zero? exit)
          (when-let [line (last (remove string/blank? (string/split-lines (str out))))]
            (println (str "  [" echo-tag "] " (string/trim line)))))
        (is (zero? exit) failure-msg))))

;; --- emitted dev-page boot proof ------------------------------------------

(def ^:private boot-proof-driver-rel
  "tools/template/test-support/dev-page-boot-proof.cjs")

(defn- run-boot-proof-driver!
  "Serve the emitted `resources/public` and drive Chromium over the real
  `index.html` + dev bundle. Returns the driver's {:exit :out}."
  [^java.io.File root ^java.io.File proj label env-overrides]
  (let [driver    (.getCanonicalPath (io/file root boot-proof-driver-rel))
        pub-root  (.getCanonicalPath (io/file proj "resources/public"))
        impl-root (.getCanonicalPath (io/file root "implementation"))
        node-path (.getCanonicalPath (io/file root "implementation/node_modules"))]
    (run-process! ["node" driver pub-root impl-root label]
                  proj (merge {"NODE_PATH" node-path} env-overrides))))

(defn- run-dev-page-boot-proof!
  "The positive proof: the page a newcomer opens after `npx shadow-cljs
  watch app` mounts the counter, moves it 0 → 1 on click, and raises zero
  uncaught pageerrors. Assumes `shadow compile app` already built the dev
  bundle, so it must run BEFORE any `release` build replaces it. Returns
  the driver's exit code, or nil when `node` is unavailable."
  [^java.io.File root ^java.io.File proj label]
  (testing (str label " — Chromium dev-page boot proof (emitted index.html + "
                "dev bundle mounts, zero pageerror)")
    (if-not @node-available?
      (do (announce-browser-skip! (str "dev-page boot proof -- " label)
                                  "`node` is not on PATH")
          (is true "`node` unavailable — skipping the emitted dev-page boot proof")
          nil)
      (let [{:keys [exit out]} (run-boot-proof-driver! root proj label {})]
        (check-browser-proof!
          (str "dev-page boot proof -- " label)
          "dev-page boot"
          exit out
          (str "the emitted dev-page boot proof exited " exit " for " label
               " — the page a newcomer opens after `npx shadow-cljs watch app` "
               "did not boot cleanly: either #app never painted (a broken "
               ":init-fn, a namespace that throws on load, a bundle that did "
               "not load), the counter did not move 0 -> 1, or Chromium raised "
               "an uncaught pageerror. Output:\n" out))
        exit))))

(defn- run-broken-boot-witness!
  "The red witness for the boot proof: rename the page's mount node so
  `core.cljs`'s `getElementById \"app\"` finds nothing, run the SAME
  driver, and require it to fail (exit 1 — not 0, and not the
  browser-missing 2). Then restore the page byte-for-byte. Only meaningful
  after the positive proof actually ran (exit 0)."
  [^java.io.File root ^java.io.File proj label]
  (testing (str label " — broken-boot witness (the boot proof goes RED on a page "
                "whose mount node is missing)")
    (let [index    (io/file proj "resources/public/index.html")
          original (slurp index)
          broken   (string/replace original "id=\"app\"" "id=\"app-broken\"")]
      (is (not= original broken)
          "the witness must actually change the page: index.html carries id=\"app\"")
      (try
        (spit index broken)
        (let [{:keys [exit out]}
              (run-boot-proof-driver! root proj (str label " (broken)")
                                      ;; The driver waits this long for #app
                                      ;; to paint before failing; keep the
                                      ;; deliberate failure cheap.
                                      {"RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS" "8000"})]
          (is (= 1 exit)
              (str "the dev-page boot proof must go RED (exit 1) on a page with "
                   "no #app mount node; it exited " exit
                   ". A green here means the proof is inert. Output:\n" out))
          ;; Say so on the green path too: a witness that speaks only when
          ;; it fails is indistinguishable in the run output from one that
          ;; never ran.
          (when (= 1 exit)
            (println (str "  [broken-boot witness] " label ": the boot proof went RED "
                          "(exit 1) on a page with no #app mount node, as required."))))
        (finally
          (spit index original)))
      (is (= original (slurp index))
          "index.html is restored byte-for-byte after the witness"))))

;; --- The orchestration -----------------------------------------------------

(defn- compile-and-run-emitted-test!
  "For one substrate: generate a tmp app, rewrite deps.edn → :local/root,
  link node_modules, `clojure -M:shadow compile app test`, prove the
  emitted package.json is complete, boot the real page in Chromium (and,
  when `boot-witness?`, prove that proof bites), run `node
  out/node-test.js`, and when `release?` build the `:advanced` release.

  Every substrate compiles BOTH the `:app` build — the only build that
  pulls its `core.cljs` and views onto the compile classpath — and the
  `:test` build that runs `events_test.cljs`. No other gate compiles the
  generated app under `:advanced`, so the Reagent variant runs the release
  too; the `:app` module + `^:export init` shape is substrate-invariant."
  [substrate {:keys [release? boot-witness?]}]
  (let [root  (repo-root)
        label (name substrate)
        tmp   (tmp-dir (str "rf2-template-run-" label "-"))]
    (try
      (let [proj (run-template! tmp "acme/my-app" substrate)]
        (rewrite-deps-for-local-run! root proj substrate)
        (let [linked?   (link-node-modules! root proj)
              node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
              env       {"NODE_PATH" node-path}]
          (is linked?
              (str "project-local node_modules must resolve for the `:app` "
                   "(:browser) compile — it ignores NODE_PATH. Symlink/junction "
                   "into " (.getPath proj) " failed; ensure "
                   "implementation/node_modules exists (`npm ci` in "
                   "implementation/) and the OS allows a symlink or `mklink /J`."))

          ;; --- compile ---------------------------------------------------
          ;; The emitted :shadow alias is deps-only, so name the CLI ns.
          (testing (str label " — shadow-cljs compile app test")
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow" "-m" "shadow.cljs.devtools.cli"
                                 "compile" "app" "test"]
                                proj env)]
              (is (zero? exit)
                  (str "`clojure -M:shadow compile app test` exited " exit
                       " for " label ". Output:\n" out))))

          ;; --- the two teeth, before any release overwrites the dev bundle
          (assert-emitted-package-json-complete! root proj label)
          (let [proof-exit (run-dev-page-boot-proof! root proj label)]
            (when (and boot-witness? (= 0 proof-exit))
              (run-broken-boot-witness! root proj label)))

          ;; --- run ----------------------------------------------------------
          (testing (str label " — node out/node-test.js")
            (let [bundle (io/file proj "out/node-test.js")]
              (is (.isFile bundle)
                  (str "the compile step produced out/node-test.js for " label))
              (when (.isFile bundle)
                (let [{:keys [exit out]}
                      (run-process! ["node" "out/node-test.js"] proj env)]
                  (is (zero? exit)
                      (str "`node out/node-test.js` exited " exit " for " label
                           ". Output:\n" out))
                  ;; Pin both summary lines so a silent zero-exit (no tests
                  ;; discovered) cannot false-green.
                  (is (re-find #"Ran \d+ tests? containing \d+ assertions" out)
                      (str "expected the cljs.test summary line. Got:\n" out))
                  (is (re-find #"0 failures, 0 errors" out)
                      (str "expected '0 failures, 0 errors'. Got:\n" out))))))

          ;; --- :advanced release build ---------------------------------------
          (when release?
            (testing (str label " — clojure -M:shadow release app (:advanced)")
              (let [{:keys [exit out]}
                    (run-process! ["clojure" "-M:shadow" "-m" "shadow.cljs.devtools.cli"
                                   "release" "app"]
                                  proj env)]
                (is (zero? exit)
                    (str "`clojure -M:shadow release app` exited " exit " for "
                         label ". An :advanced-only break (Closure DCE/externs, "
                         "^:export munging) ships green from the dev compile and "
                         "surfaces only on a newcomer's `npm run release`. "
                         "Output:\n" out))
                (let [bundle (io/file proj "resources/public/js/main.js")]
                  (is (and (.isFile bundle) (pos? (.length bundle)))
                      (str "`shadow release app` must emit a non-empty "
                           "resources/public/js/main.js for " label ". Bundle: "
                           (if (.isFile bundle)
                             (str (.length bundle) " bytes")
                             "absent")))))))))
      (finally
        (delete-recursively tmp)))))

(defn- skip-if-disabled!
  "When the gate is off, record a passing assertion that documents the
  skip — so the green-run line count is stable across enabled/disabled
  modes."
  [label]
  (is true
      (str "RF2_TEMPLATE_RUN_EMITTED_TESTS unset — skipping behavioural "
           "compile+run for " label ". Static-parse coverage still applies "
           "(template_emission_test.clj).")))

;; --- Tests -----------------------------------------------------------------

(deftest reagent-emitted-tests-run-test
  (testing "the emitted Reagent app compiles, its focused test runs green, the
            real page boots and moves 0 -> 1 (and the proof bites), and the
            :advanced release build is clean"
    (if-not @enabled?
      (skip-if-disabled! :reagent)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :reagent {:release? true :boot-witness? true}))))))

(deftest uix-emitted-tests-run-test
  (testing "the emitted UIx app compiles, its focused test runs green, and the
            real page boots and moves 0 -> 1"
    (if-not @enabled?
      (skip-if-disabled! :uix)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :uix {}))))))

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
  the `:shadow` alias (shadow-cljs build dep), plus the substrate's own
  Maven coords (`substrate-dep-symbols`) at the exact versions the
  template pins. Harvesting from the template — the source of truth the
  skill mirrors — keeps the fixture drift-free: there is nothing to
  hand-maintain here.

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
  shape: core + the selected substrate adapter + schemas + Xray on the
  REAGENT route only (the UIx route ships no Xray — its day-one framework
  set is three coords; rf2-hki2j / rf2-p6f6u) + the substrate's own Maven
  deps (reagent/reagent for Reagent; com.pitch/uix.{core,dom} for UIx) +
  the required `:shadow` alias.

  `day8/re-frame2-schemas` is a DIRECT day-one coordinate — the counter
  attaches a whole-app-db schema and `:require`s `re-frame.schemas`
  (`first-counter.md` / `shared-dataflow.md`), and `deps-versions.md`
  lists it as a day-one coord. It is NOT left to resolve transitively via
  Xray; `assert-skill-deps-shape!` pins that honesty before the
  `:local/root` rewrite.

  The framework `day8/re-frame2*` coords carry a placeholder
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
                    'day8/re-frame2-schemas    {:mvn/version "PLACEHOLDER"}}
                   (when (= substrate :reagent)
                     {'day8/re-frame2-xray {:mvn/version "PLACEHOLDER"}})
                   substrate-deps)
     :aliases {:shadow shadow-alias}}))

;; --- dependency-honesty net ------------------------------------------------

(defn- required-direct-coords
  "The DIRECT day-one coordinate keys the skill documents for `substrate`
  (`references/deps-versions.md` + `entry-namespace.md`): core + the
  selected adapter + schemas + Xray (REAGENT route only — the UIx route
  ships no Xray, rf2-hki2j) + the substrate's own Maven deps. The
  synthesised deps.edn must carry every one as a DIRECT `:deps` key — none
  may be left to resolve transitively (Xray → schemas; adapter
  `:local/root` → substrate dep)."
  [substrate]
  (concat ['day8/re-frame2
           (symbol "day8" (str "re-frame2-" (name substrate)))
           'day8/re-frame2-schemas]
          (when (= substrate :reagent) ['day8/re-frame2-xray])
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
                 (and (= coord 'day8/re-frame2-schemas)
                      (= substrate :reagent))
                 "day8/re-frame2-xray depends on schemas transitively"
                 (contains? (set (get substrate-dep-symbols substrate)) coord)
                 (str "the " (name substrate) " adapter :local/root pulls it "
                      "transitively")
                 :else
                 "it may resolve through another coord transitively")
               " — masking the missing DIRECT dependency the skill teaches. "
               "Add it to skill-deps-edn (and confirm the skill reference "
               "still documents it as day-one)."))))
  deps-map)

;; --- materialisation -------------------------------------------------------

(defn- materialise-skill-scaffold!
  "Write the setup-skill scaffold for `substrate` into `proj-dir` from the
  skill's own markdown fenced blocks (the load-bearing surfaces) plus a
  synthesised, dependency-honest framework `deps.edn`. Returns proj-dir.

  Build wiring is PER-ROUTE (rf2-hki2j): the Reagent route takes the
  Xray-wired blocks from `references/shadow-cljs.md`; the UIx route takes
  the Xray-free variants from `references/entry-namespace.md` §UIx
  greenfield (no :devtools preload, no [data-rf-xray-host] aside, no
  .rf2-xray-host CSS — the panel cannot mount on element substrates):
    deps.edn                        (synthesised — see skill-deps-edn;
                                     shape-asserted before any rewrite)
    shadow-cljs.edn                 (the route's :builds block)
    resources/public/index.html     (the route's html block)
    resources/public/css/app.css    (the route's css block)

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
        reagent?    (= substrate :reagent)
        ;; Per-route build-wiring source: Reagent reads shadow-cljs.md
        ;; (the FIRST ```clojure block carrying a `:builds` map — later
        ;; clojure blocks are the deps.edn :shadow alias fragment etc.);
        ;; UIx reads the Xray-free variants in entry-namespace.md §UIx
        ;; greenfield (its only :builds/html/css blocks).
        wiring-md   (slurp (io/file refs (if reagent?
                                           "shadow-cljs.md"
                                           "entry-namespace.md")))
        wiring-where (if reagent?
                       "in shadow-cljs.md"
                       "in entry-namespace.md §UIx greenfield")
        shadow-edn  (single-fenced-block wiring-md "clojure"
                                         (str wiring-where " (the :builds block)")
                                         #(string/includes? % ":builds"))
        index-html  (single-fenced-block wiring-md "html" wiring-where)
        app-css     (single-fenced-block wiring-md "css" wiring-where)
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

          ;; --- assert the scaffold wires the PER-ROUTE Xray contract BEFORE
          ;; compiling (cheap structural locks on the extracted snippets).
          ;; Reagent ships the day-one preload + host; the UIx route ships
          ;; NEITHER (Xray cannot mount on element substrates — rf2-hki2j /
          ;; rf2-p6f6u). -----------------------------------------------------
          (testing (str "setup-skill " (name substrate)
                        " scaffold wires the per-route Xray contract")
            (let [shadow-text (slurp (io/file proj "shadow-cljs.edn"))
                  html-text   (slurp (io/file proj "resources/public/index.html"))]
              (if (= substrate :reagent)
                (do
                  (is (string/includes? shadow-text "day8.re-frame2-xray.preload")
                      (str "the skill's day-one shadow-cljs.edn block "
                           "(references/shadow-cljs.md) no longer wires "
                           "`:devtools {:preloads [day8.re-frame2-xray.preload]}`. "
                           "Xray is a Reagent-route day-one dep and index.html "
                           "ships the host column — the canonical block must "
                           "wire the preload that fills it."))
                  (is (string/includes? html-text "data-rf-xray-host")
                      (str "the skill's index.html block (references/shadow-cljs.md) "
                           "no longer carries the `[data-rf-xray-host]` Xray layout "
                           "host column.")))
                (do
                  (is (and (not (string/includes? shadow-text "day8.re-frame2-xray"))
                           (not (string/includes? shadow-text ":devtools")))
                      (str "the skill's UIx shadow-cljs.edn block "
                           "(references/entry-namespace.md §UIx greenfield) "
                           "wires a devtools preload — the UIx route ships no "
                           "Xray (the panel cannot mount on element "
                           "substrates; rf2-hki2j)."))
                  (is (not (string/includes? html-text "data-rf-xray-host"))
                      (str "the skill's UIx index.html block "
                           "(references/entry-namespace.md §UIx greenfield) "
                           "carries the `[data-rf-xray-host]` column — no "
                           "panel can fill it on this route (rf2-hki2j)."))))))

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
