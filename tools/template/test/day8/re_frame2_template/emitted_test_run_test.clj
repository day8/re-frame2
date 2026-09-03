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

   ## The setup skill's default scaffold

   `skills/re-frame2-setup/references/first-counter.md` ships the twelve
   files the skill's default route writes, rendered from THIS template by
   the skill's `tests/first_counter_derivation.clj`. Two tests at the
   bottom consume that leaf as a consumer would: an ungated one asserts
   the leaf equals a real deps-new emission byte for byte, and a gated
   black-box one materialises the shipped files into a fresh project,
   applies the documented pre-publish coordinate step, compiles, boots the
   page in Chromium, clicks 0 -> 1, and proves the proof bites."
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

(def ^:private emitted-tests-enabled?
  (delay (= "1" (System/getenv "RF2_TEMPLATE_RUN_EMITTED_TESTS"))))

;; --- deps.edn local-root rewrite ------------------------------------------

(defn- rewrite-deps-for-local-run!
  "Swap the two `day8/re-frame2*` :mvn/version coords in a project's
  deps.edn for `:local/root` paths into the in-repo source tree, then
  write it back. Core and the substrate adapter are the whole set: since
  rf2-zq34m both callers hand this a deps.edn carrying exactly those two
  framework coords (the emitted scaffold; the setup skill's derived leaf,
  which `mount-skill-scaffold!` asserts against `reduced-day-one-coords`
  right after this call), and `template_test.clj` forbids every other
  `day8/re-frame2*` coordinate in an emitted project. A future fixture
  that deliberately adds an optional artefact adds its rewrite here
  together with a test that reaches the new arm — speculative arms for
  coordinates no caller declares are what this docstring replaced."
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
        (-> deps
            (assoc-in [:deps 'day8/re-frame2]
                      {:local/root (rel-of "implementation/core")})
            (assoc-in [:deps adapter-coord]
                      {:local/root (rel-of adapter-path)}))]
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
      (let [process-builder (ProcessBuilder.
                 ^java.util.List ["cmd" "/c" "mklink" "/J"
                                  (.getPath dst)
                                  (.getCanonicalPath src)])]
        (.redirectErrorStream process-builder true)
        (let [process (.start process-builder)]
          (slurp (.getInputStream process))
          (zero? (.waitFor process))))
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
   (let [process-builder (ProcessBuilder. ^java.util.List cmd)]
     (.directory process-builder dir)
     (.redirectErrorStream process-builder true)
     (let [process-environment (.environment process-builder)]
       (doseq [[k v] env-overrides]
         (.put process-environment k v)))
     (let [process        (.start process-builder)
           process-output (slurp (.getInputStream process))
           exit-code      (.waitFor process)]
       {:exit exit-code :out process-output}))))

(def ^:private clojure-cli-available?
  (delay
    (try
      (let [process-builder (ProcessBuilder. ^java.util.List ["clojure" "--help"])]
        (.redirectErrorStream process-builder true)
        (zero? (.waitFor (.start process-builder))))
      (catch Throwable _ false))))

(def ^:private node-available?
  (delay
    (try
      (let [process-builder (ProcessBuilder. ^java.util.List ["node" "--version"])]
        (.redirectErrorStream process-builder true)
        (zero? (.waitFor (.start process-builder))))
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
        (let [package-json      (io/file node-modules pkg "package.json")
              dependencies-next (when (.isFile package-json)
                                  (let [package-json-text (slurp package-json)]
                                    (into (or (json-string-map-keys package-json-text "dependencies") #{})
                                          (or (json-string-map-keys package-json-text "peerDependencies") #{}))))]
          (recur (conj seen pkg) (into (subvec queue 1) dependencies-next))))
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
              package-json-text (slurp pkg-json)
              declared  (into (or (json-string-map-keys package-json-text "dependencies") #{})
                              (or (json-string-map-keys package-json-text "devDependencies") #{}))
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
  [what driver-output]
  (println)
  (println "!!! ================================================================")
  (println (str "!!! NOT PROVEN -- SKIPPED: " what))
  (println "!!! Chromium is not launchable here, so this browser proof did NOT run.")
  (println "!!! This run stays green, but NOTHING about the emitted page is proven.")
  (println "!!! (Under CI this is a hard failure — see browser-proofs-required?.)")
  (when-let [line (first (remove string/blank? (string/split-lines (str driver-output))))]
    (println (str "!!! driver: " (string/trim line))))
  (println "!!! ================================================================")
  (println))

(defn- check-browser-proof!
  "Turn the driver's exit code into a verdict. 0 passes and echoes the
  driver's own verdict line; 2 is a local skip or a CI failure; anything
  else failed with `failure-msg`."
  [what echo-tag exit driver-output failure-msg]
  (cond
    (and (= 2 exit) (not @browser-proofs-required?))
    (do (announce-browser-skip! what driver-output)
        (is true (str "Chromium unavailable — " what " did not run. Output:\n" driver-output)))

    (= 2 exit)
    (is false
        (str what " did NOT run: Chromium was not launchable, and `CI` is set. "
             "Every job that sets RF2_TEMPLATE_RUN_EMITTED_TESTS=1 also runs "
             "`npx playwright install --with-deps chromium`, so a CI job that "
             "finds no browser is BROKEN, not excused. Output:\n" driver-output))

    :else
    (do (when (zero? exit)
          (when-let [line (last (remove string/blank? (string/split-lines (str driver-output))))]
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
    (if-not @emitted-tests-enabled?
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
    (if-not @emitted-tests-enabled?
      (skip-if-disabled! :uix)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :uix {}))))))

;; ===========================================================================
;; The setup skill's default scaffold — derivation lock + black-box fixture
;; ===========================================================================
;;
;; `skills/re-frame2-setup/references/first-counter.md` carries, between
;; `<!-- BEGIN generated … -->` / `<!-- END generated -->` markers, the
;; twelve files the skill's default route writes — one `### `path`` heading
;; and one fenced block per file — rendered from this template for its
;; reference project `acme/my-app` by the skill's
;; `tests/first_counter_derivation.clj`. `references/entry-namespace.md`
;; carries the three files the `:uix` substrate swaps, the same way.
;;
;;   * `setup-skill-leaves-are-the-template-emission-test` (ungated, cheap):
;;     runs the REAL deps-new pipeline for `acme/my-app` on both substrates
;;     and asserts each leaf's generated blocks equal the emitted files byte
;;     for byte, and that the UIx leaf carries exactly the files whose
;;     content differs between the two emissions. The skill's own bb suite
;;     compares the leaves against its renderer; this arm compares them
;;     against the generator itself, so a divergence between the two
;;     instruments cannot hide.
;;
;;   * `setup-skill-default-scaffold-mounts-test` (gated like the template
;;     tiers): materialises the leaf's files into a fresh temp project — the
;;     exact shipped source, not a re-derivation — applies the documented
;;     pre-publish step (the two framework coords → `:local/root`, SKILL.md
;;     step 2), links `node_modules`, compiles `app` + `test`, proves the
;;     emitted `package.json` is complete, boots the real page in Chromium
;;     (the heading `acme/my-app` painted, the counter reads 0, a click moves
;;     it to 1, zero pageerrors), runs the starter test under Node, and
;;     proves the proof bites twice — the broken-mount witness (build / init
;;     wiring) and a broken-click witness (`views.cljs` dispatching an event
;;     nobody registers, recompiled: the page paints, the click never moves
;;     the counter).

(def ^:private skill-setup-refs
  "Absolute dir of the re-frame2-setup skill's reference leaves."
  (delay (io/file (repo-root) "skills/re-frame2-setup/references")))

(def ^:private generated-begin "<!-- BEGIN generated by tests/first_counter_derivation.clj -->")
(def ^:private generated-end   "<!-- END generated -->")

(def ^:private regenerate-hint
  (str "Regenerate with `bb tests/first_counter_derivation.clj` from "
       "skills/re-frame2-setup/ — the bodies inside a leaf's generated region "
       "are this template's emission and are never hand-edited."))

(defn- normalize-line-endings [text]
  (-> text (string/replace "\r\n" "\n") (string/replace "\r" "\n")))

(defn- leaf-files
  "path → body from a leaf's generated region: each `### `path`` heading
   followed by a 3- or 4-backtick fenced block. The same shape the skill's
   derivation script writes and its bb suite reads back."
  [md-text]
  (let [text   (normalize-line-endings md-text)
        start  (string/index-of text generated-begin)
        end    (string/index-of text generated-end)
        region (if (and start end) (subs text start end) "")]
    (into (sorted-map)
          (for [[_ path _ body] (re-seq #"(?s)### `([^`\n]+)`\n\n(`{3,4})[a-z]*\n(.*?)\n\2\n"
                                        region)]
            [path (str body "\n")]))))

(defn- read-leaf-files [leaf]
  (leaf-files (slurp (io/file @skill-setup-refs leaf))))

(defn- project-files
  "path → body (LF) for every regular file under `root`."
  [^java.io.File root]
  (into (sorted-map)
        (for [f (file-seq root) :when (.isFile f)]
          [(-> (.relativize (.toPath root) (.toPath f)) str (string/replace "\\" "/"))
           (normalize-line-endings (slurp f))])))

(defn- emit-reference-project!
  "A real deps-new emission of `acme/my-app` for `substrate`, as path → body."
  [substrate]
  (let [tmp (tmp-dir (str "rf2-setup-emit-" (name substrate) "-"))]
    (try
      (project-files (run-template! tmp "acme/my-app" substrate))
      (finally (delete-recursively tmp)))))

(defn- assert-leaf-equals-emission! [leaf files expected]
  (is (seq files) (str leaf " carries no generated region — the extractor found nothing."))
  (is (= (set (keys expected)) (set (keys files)))
      (str leaf ": file set differs from the template's emission. Missing "
           (pr-str (sort (remove (set (keys files)) (keys expected))))
           ", extra " (pr-str (sort (remove (set (keys expected)) (keys files))))
           ". " regenerate-hint))
  (doseq [[path body] expected :when (contains? files path)]
    (is (= body (get files path))
        (str leaf ": `" path "` differs from what deps-new emits. " regenerate-hint))))

(deftest setup-skill-leaves-are-the-template-emission-test
  (testing "the setup skill's generated regions equal a real deps-new emission for acme/my-app, byte for byte"
    (let [reagent    (emit-reference-project! :reagent)
          uix        (emit-reference-project! :uix)
          differs    (into (sorted-map)
                           (filter (fn [[path body]] (not= body (get reagent path))) uix))
          ;; The display label rides `{{substrate-label}}` into two files the
          ;; skill does not re-ship: their UIx bodies must be the Reagent
          ;; bodies with the label swapped, and nothing more.
          label-only (into (sorted-map)
                           (filter (fn [[path body]]
                                     (= body (string/replace (get reagent path "") "Reagent" "UIx")))
                                   differs))
          swapped    (apply dissoc differs (keys label-only))]
      (is (= 12 (count reagent))
          (str "the template's Reagent emission is not twelve files: " (pr-str (keys reagent))))
      (assert-leaf-equals-emission! "first-counter.md" (read-leaf-files "first-counter.md") reagent)
      (is (= #{"README.md" "package.json"} (set (keys label-only)))
          (str "the files that differ between substrates by the display label alone are "
               (pr-str (keys label-only)) " — the setup skill's UIx route tells the author "
               "to swap the label in README.md and package.json; revisit that sentence."))
      (is (= #{"deps.edn" "src/acme/my_app/core.cljs" "src/acme/my_app/views.cljs"}
             (set (keys swapped)))
          (str "the two substrates now differ structurally in " (pr-str (keys swapped))
               " — the setup skill's UIx route is documented as a three-file swap; "
               "regenerate the leaves and update SKILL.md cardinal rule 3 together."))
      (assert-leaf-equals-emission! "entry-namespace.md §UIx greenfield"
                                    (read-leaf-files "entry-namespace.md") swapped))))

;; --- materialisation -------------------------------------------------------

(defn- materialise-leaf!
  "Write `files` (path → body) under `proj-dir` exactly as shipped."
  [^java.io.File proj-dir files]
  (doseq [[path body] files]
    (let [target-file (io/file proj-dir path)]
      (.mkdirs (.getParentFile target-file))
      (spit target-file body)))
  proj-dir)

(def ^:private reduced-day-one-coords
  #{'org.clojure/clojure 'org.clojure/clojurescript
    'day8/re-frame2 'day8/re-frame2-reagent 'reagent/reagent})

;; --- the click-path witness ------------------------------------------------

(defn- run-broken-click-witness!
  "The red witness for the click path: make `views.cljs` dispatch an event
  nobody registers, recompile, run the SAME driver, and require it to fail
  (exit 1) — the page still paints (init and the mount are untouched), but
  the click never moves the counter. Restores `views.cljs` byte for byte;
  the compiled bundle is left broken because nothing after this reads it."
  [^java.io.File root ^java.io.File proj label env]
  (testing (str label " — broken-click witness (the boot proof goes RED when the "
                "button dispatches an unregistered event)")
    (let [views    (io/file proj "src/acme/my_app/views.cljs")
          original (slurp views)
          broken   (string/replace original ":counter/increment" ":counter/incremnt")]
      (is (not= original broken)
          "the witness must actually change views.cljs: it dispatches :counter/increment")
      (try
        (spit views broken)
        (let [{:keys [exit out]}
              (run-process! ["clojure" "-M:shadow" "-m" "shadow.cljs.devtools.cli"
                             "compile" "app"]
                            proj env)]
          (is (zero? exit)
              (str "the broken-click recompile must itself succeed — an unregistered "
                   "event id is a runtime miss, not a compile error; exited " exit
                   ". Output:\n" out)))
        (let [{:keys [exit out]}
              (run-boot-proof-driver! root proj (str label " (broken click)")
                                      {"RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS" "8000"})]
          (is (= 1 exit)
              (str "the dev-page boot proof must go RED (exit 1) when the +1 button "
                   "dispatches an unregistered event; it exited " exit
                   ". A green here means the click tooth is inert. Output:\n" out))
          (when (= 1 exit)
            (println (str "  [broken-click witness] " label ": the boot proof went RED "
                          "(exit 1) with the button dispatching an unregistered event, "
                          "as required."))))
        (finally
          (spit views original)))
      (is (= original (slurp views))
          "views.cljs is restored byte-for-byte after the witness"))))

;; --- the fixture -----------------------------------------------------------

(defn- mount-skill-scaffold! []
  (let [root  (repo-root)
        label "setup-skill default scaffold"
        tmp   (tmp-dir "rf2-setup-scaffold-")]
    (try
      (let [files (read-leaf-files "first-counter.md")
            proj  (io/file (.toString tmp) "my-app")]
        (.mkdirs proj)
        (is (= 12 (count files))
            (str "first-counter.md must carry the twelve-file manifest; found "
                 (count files) ": " (pr-str (keys files))))
        (materialise-leaf! proj files)

        ;; --- SKILL.md step 2: point the two framework coords at the checkout.
        ;; `rewrite-deps-for-local-run!` is the function the template tiers
        ;; use; it rewrites core + the adapter and nothing else, which is
        ;; exactly the leaf's day-one set. The shape assertion below is the
        ;; dependency-honesty tooth: the shipped deps.edn must carry the
        ;; reduced set directly, not resolve anything through a transitive
        ;; edge, and the step must leave both framework coords as :local/root.
        (rewrite-deps-for-local-run! root proj :reagent)
        (let [deps (edn/read-string (slurp (io/file proj "deps.edn")))]
          (is (= reduced-day-one-coords (set (keys (:deps deps))))
              (str "the shipped default deps.edn must carry exactly the reduced day-one "
                   "set (clojure, clojurescript, core, the Reagent adapter, reagent); got "
                   (pr-str (sort (keys (:deps deps)))) ". " regenerate-hint))
          (is (every? #(contains? (get-in deps [:deps %]) :local/root)
                      ['day8/re-frame2 'day8/re-frame2-reagent])
              "the documented pre-publish step must leave both framework coords as :local/root"))

        (let [linked?   (link-node-modules! root proj)
              node-path (.getCanonicalPath (io/file root "implementation/node_modules"))
              env       {"NODE_PATH" node-path}]
          (is linked?
              (str "project-local node_modules must resolve for the shipped scaffold's "
                   "`:app` (:browser) compile — it ignores NODE_PATH. Symlink/junction "
                   "into " (.getPath proj) " failed; ensure implementation/node_modules "
                   "exists (`npm ci` in implementation/) and the OS allows a symlink or "
                   "`mklink /J` junction."))

          ;; --- compile: both builds the shipped shadow-cljs.edn declares ------
          (testing (str label " — clojure -M:shadow compile app test")
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow" "-m" "shadow.cljs.devtools.cli"
                                 "compile" "app" "test"]
                                proj env)]
              (is (zero? exit)
                  (str "`clojure -M:shadow compile app test` exited " exit
                       " for the setup skill's SHIPPED default scaffold "
                       "(skills/re-frame2-setup/references/first-counter.md, the twelve "
                       "files an author gets). Output:\n" out))
              (let [bundle (io/file proj "resources/public/js/main.js")]
                (is (and (.isFile bundle) (pos? (.length bundle)))
                    (str "`compile app` must emit a non-empty resources/public/js/main.js "
                         "for the shipped scaffold. Bundle: "
                         (if (.isFile bundle) (str (.length bundle) " bytes") "absent"))))))

          ;; --- the consumer-realism tooth, before anything overwrites the bundle
          (assert-emitted-package-json-complete! root proj label)

          ;; --- the mounted counter: heading, 0, click, 1, zero pageerrors -----
          ;; RF2_TEMPLATE_EXPECT_H1 pins the heading TEXT: `acme/my-app` comes
          ;; from the shipped views.cljs, so a passing proof is positive
          ;; evidence that the leaf's source — not a stale bundle — is what
          ;; the browser rendered.
          (let [proof-exit
                (testing (str label " — Chromium dev-page boot proof (heading acme/my-app "
                              "painted, counter 0 -> 1, zero pageerror)")
                  (let [{:keys [exit out]}
                        (run-boot-proof-driver! root proj label
                                                {"RF2_TEMPLATE_EXPECT_H1" "acme/my-app"})]
                    (check-browser-proof!
                      (str "dev-page boot proof -- " label)
                      "dev-page boot"
                      exit out
                      (str "the shipped scaffold's dev-page boot proof exited " exit
                           " — the page an author opens after `npx shadow-cljs watch app` "
                           "did not paint the heading `acme/my-app` with the counter at 0, "
                           "or the click did not move it to 1, or Chromium raised an "
                           "uncaught pageerror. Output:\n" out))
                    exit))]

            ;; --- the starter test the scaffold ships, under Node ----------------
            (testing (str label " — node out/node-test.js (the shipped events_test.cljs)")
              (let [bundle (io/file proj "out/node-test.js")]
                (is (.isFile bundle)
                    "the compile step produced out/node-test.js for the shipped scaffold")
                (when (.isFile bundle)
                  (let [{:keys [exit out]} (run-process! ["node" "out/node-test.js"] proj env)]
                    (is (zero? exit)
                        (str "`node out/node-test.js` exited " exit
                             " for the shipped scaffold. Output:\n" out))
                    (is (re-find #"Ran \d+ tests? containing \d+ assertions" out)
                        (str "expected the cljs.test summary line. Got:\n" out))
                    (is (re-find #"0 failures, 0 errors" out)
                        (str "expected '0 failures, 0 errors'. Got:\n" out))))))

            ;; --- the proof must bite, on both axes AC5 names --------------------
            (when (= 0 proof-exit)
              (run-broken-boot-witness! root proj label)
              (run-broken-click-witness! root proj label env)))))
      (finally
        (delete-recursively tmp)))))

(deftest setup-skill-default-scaffold-mounts-test
  ;; The black-box fixture for the setup skill's default route: the EXACT
  ;; shipped source (not a re-derivation) is what gets materialised, so a
  ;; regression in the leaf itself — a hand edit inside the generated
  ;; region, a stale render, a placeholder — reaches the compile and the
  ;; browser. Same RF2_TEMPLATE_RUN_EMITTED_TESTS gate as the template
  ;; tiers; in-repo :local/root coords, not a published-coordinate proof.
  (testing "the setup skill's shipped default scaffold resolves, compiles, boots in
            Chromium with the heading and 0, moves to 1 on click, runs its starter
            test, and the proof goes red when the mount node or the click path breaks"
    (if-not @emitted-tests-enabled?
      (skip-if-disabled! "setup-skill default scaffold")
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (mount-skill-scaffold!))))))
