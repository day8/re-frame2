(ns day8.re-frame2-template.emitted-test-run-test
  "Compile and boot emitted and setup-skill scaffolds, with failure witnesses.
   Opt in with RF2_TEMPLATE_RUN_EMITTED_TESTS=1; CI provisions npm and Chromium."
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

;; --- The scaffolded name ----------------------------------------------------

(def ^:private emitted-project-name
  "The browser heading identifies this emission rather than a stale bundle."
  "acme/my-app")

;; --- deps.edn local-root rewrite ------------------------------------------

(def ^:private substrate-local-roots
  "Explicit package roots: substrate names do not determine package paths."
  {:reagent "implementation/adapters/reagent"
   :uix     "implementation/adapters/uix"})

(defn- substrate-local-root
  "Reject missing registrations and moved package directories before rewriting deps."
  [^java.io.File root substrate]
  (let [rel (or (get substrate-local-roots substrate)
                (throw (ex-info (str "No local root registered for substrate "
                                     substrate " — add its row to "
                                     "`substrate-local-roots`.")
                                {:substrate substrate
                                 :known     (set (keys substrate-local-roots))})))]
    (when-not (.isDirectory (io/file root rel))
      (throw (ex-info (str "`substrate-local-roots` maps " substrate " to "
                           rel ", which is not a directory under " root
                           " — the framework package has moved.")
                      {:substrate substrate :expected rel})))
    rel))

(defn- rewrite-deps-for-local-run!
  "Rewrite core, the adapter and dev Story to the in-repo source tree."
  [^java.io.File root ^java.io.File proj-dir substrate]
  (let [deps-file (io/file proj-dir "deps.edn")
        deps      (edn/read-string (slurp deps-file))
        rel-of    (fn [target]
                    (-> (.relativize (.toPath (.getCanonicalFile proj-dir))
                                     (.toPath (.getCanonicalFile (io/file root target))))
                        .toString
                        (string/replace "\\" "/")))
        ;; The COORDINATE is derivable — `day8/re-frame2-<substrate>` holds
        ;; for every substrate. The PATH is not; see `substrate-local-roots`.
        adapter-coord (symbol "day8" (str "re-frame2-" (name substrate)))
        adapter-path  (substrate-local-root root substrate)
        rewritten
        (cond-> (-> deps
                    (assoc-in [:deps 'day8/re-frame2]
                              {:local/root (rel-of "implementation/core")})
                    (assoc-in [:deps adapter-coord]
                              {:local/root (rel-of adapter-path)}))
          (get-in deps [:aliases :dev :extra-deps 'day8/re-frame2-story])
          (assoc-in [:aliases :dev :extra-deps 'day8/re-frame2-story]
                    {:local/root (rel-of "tools/story")}))]
    (spit deps-file (with-out-str (pprint/pprint rewritten)))))

;; --- node_modules symlink --------------------------------------------------

(defn- junction-node-modules!
  "Windows fallback when symlink privileges are unavailable."
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
  "The browser compiler requires project-local node_modules; NODE_PATH is insufficient."
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
  "Run in dir with environment overrides and merged stderr; return {:exit :out}."
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

;; Shared node_modules can hide undeclared packages; grade the emitted npm install closure.

(defn- json-string-map-keys
  "Read keys from a dependency JSON object whose values are strings."
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
  "Declared packages plus their installed dependencies and peerDependencies."
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
;; reads as a pass would leave the proof unexecuted with nobody noticing.

(def ^:private browser-proofs-required?
  "A missing Chromium is a failure under CI, a reported skip locally."
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
  "Accept exit 0, treat exit 2 as a local skip or CI failure, and reject other exits."
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

;; --- emitted page boot proof ----------------------------------------------

(def ^:private boot-proof-driver-rel
  "tools/template/test-support/page-boot-proof.cjs")

(def ^:private bundle-mode-blurb
  "Name the user command whose bundle failed."
  {:dev     "the page a newcomer opens after `npx shadow-cljs watch app`"
   :release (str "the page a newcomer DEPLOYS after `npm run release` — the "
                 ":advanced bundle, where Closure renames un-inferrable "
                 "property access and drops code behind `goog.DEBUG`, so a "
                 "defect invisible in dev surfaces only here")})

(defn- run-boot-proof-driver!
  "Serve resources/public and check the actual page in the specified bundle mode."
  [^java.io.File root ^java.io.File proj label mode env-overrides]
  (let [driver    (.getCanonicalPath (io/file root boot-proof-driver-rel))
        pub-root  (.getCanonicalPath (io/file proj "resources/public"))
        impl-root (.getCanonicalPath (io/file root "implementation"))
        node-path (.getCanonicalPath (io/file root "implementation/node_modules"))]
    (run-process! ["node" driver pub-root impl-root label]
                  proj (merge {"NODE_PATH"                (str node-path)
                               "RF2_TEMPLATE_BUNDLE_MODE" (name mode)}
                              env-overrides))))

(defn- run-page-boot-proof!
  "Require the matching bundle to mount, increment and raise no pageerrors."
  [^java.io.File root ^java.io.File proj label mode env-overrides]
  (let [what (str (name mode) "-bundle boot proof -- " label)]
    (testing (str label " — Chromium " (name mode) "-bundle boot proof (emitted "
                  "index.html + " (name mode) " bundle mounts, zero pageerror)")
      (if-not @node-available?
        (do (announce-browser-skip! what "`node` is not on PATH")
            (is true (str "`node` unavailable — skipping the emitted "
                          (name mode) "-bundle boot proof"))
            nil)
        (let [{:keys [exit out]} (run-boot-proof-driver! root proj label mode
                                                         env-overrides)]
          (check-browser-proof!
            what
            (str (name mode) "-bundle boot")
            exit out
            (str "the emitted " (name mode) "-bundle boot proof exited " exit
                 " for " label " — " (bundle-mode-blurb mode)
                 " did not boot cleanly: either #app never painted (a broken "
                 ":init-fn, a namespace that throws on load, a bundle that did "
                 "not load), the counter did not move 0 -> 1, Chromium raised "
                 "an uncaught pageerror, or the page was not running the "
                 (name mode) " bundle at all. Output:\n" out))
          exit)))))

(defn- run-broken-boot-witness!
  "Remove the mount node, require driver exit 1, then restore the page byte-for-byte."
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
              (run-boot-proof-driver! root proj (str label " (broken)") :dev
                                      ;; The driver waits this long for #app
                                      ;; to paint before failing; keep the
                                      ;; deliberate failure cheap.
                                      {"RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS" "8000"})]
          (is (= 1 exit)
              (str "the dev-bundle boot proof must go RED (exit 1) on a page with "
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

;; --- the :advanced release proof -------------------------------------------

(defn- run-release-proof!
  "Build and boot the optimized bundle; require it to replace the recorded dev bundle."
  [^java.io.File root ^java.io.File proj label env dev-bundle-length]
  (let [bundle    (io/file proj "resources/public/js/main.js")
        released?
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
            (when (zero? exit)
              (let [emitted? (and (.isFile bundle) (pos? (.length bundle)))
                    replaced? (and emitted? (not= dev-bundle-length (.length bundle)))]
                (is emitted?
                    (str "`shadow release app` must emit a non-empty "
                         "resources/public/js/main.js for " label ". Bundle: "
                         (if (.isFile bundle)
                           (str (.length bundle) " bytes")
                           "absent")))
                ;; The release writes over the dev bundle. If it did not, the
                ;; browser half below would be grading the artefact the dev
                ;; proof already passed.
                (is replaced?
                    (str "the `:advanced` release did not replace the dev bundle for "
                         label ": resources/public/js/main.js is still "
                         dev-bundle-length " bytes. Everything after this point "
                         "would be proving the DEV bundle a second time."))
                (when replaced?
                  (println (str "  [release bundle] " label ": dev " dev-bundle-length
                                " bytes -> :advanced " (.length bundle) " bytes")))
                replaced?))))]
    ;; The browser half — the actual "boots" claim, and the deliverable. Only
    ;; when the release genuinely produced a new bundle: run it over a failed
    ;; release and every verdict below is about the dev artefact instead, which
    ;; is the confusion this whole function exists to prevent. Pinning the
    ;; heading is positive evidence the optimised bundle rendered the emitted
    ;; `views.cljs` rather than anything left in the output directory.
    (when released?
      (run-page-boot-proof! root proj label :release
                            {"RF2_TEMPLATE_EXPECT_H1" emitted-project-name}))))

;; --- The orchestration -----------------------------------------------------

(defn- compile-and-run-emitted-test!
  "Per substrate: compile both builds, check npm closure, boot, run tests, then release and boot."
  [substrate {:keys [boot-witness?]}]
  (let [root  (repo-root)
        label (name substrate)
        tmp   (tmp-dir (str "rf2-template-run-" label "-"))]
    (try
      (let [proj (run-template! tmp emitted-project-name substrate)]
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
          ;; The emitted :shadow alias is deps-only, so name the CLI ns. `compile
          ;; app` boots stories/init, so Story's :dev alias rides along; the
          ;; release below runs without it, so a release that reached Story
          ;; would fail to compile.
          (testing (str label " — shadow-cljs compile app test")
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow:dev" "-m" "shadow.cljs.devtools.cli"
                                 "compile" "app" "test"]
                                proj env)]
              (is (zero? exit)
                  (str "`clojure -M:shadow:dev compile app test` exited " exit
                       " for " label ". Output:\n" out))))

          ;; --- the two teeth, before the release overwrites the dev bundle
          (assert-emitted-package-json-complete! root proj label)
          (let [proof-exit (run-page-boot-proof! root proj label :dev
                                                 {"RF2_TEMPLATE_EXPECT_H1" emitted-project-name})]
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

          ;; --- :advanced release build + its own boot proof -------------------
          ;; Last, and only here: it overwrites the dev bundle and its
          ;; manifest, which everything above reads.
          (run-release-proof! root proj label env
                              (.length (io/file proj "resources/public/js/main.js")))))
      (finally
        (delete-recursively tmp)))))

(defn- skip-if-disabled!
  "Record an explicit skip assertion when the expensive tier is disabled."
  [label]
  (is true
      (str "RF2_TEMPLATE_RUN_EMITTED_TESTS unset — skipping behavioural "
           "compile+run for " label ". Static-parse coverage still applies "
           "(template_emission_test.clj).")))

;; --- Tests -----------------------------------------------------------------

(deftest reagent-emitted-tests-run-test
  (testing "the emitted Reagent app compiles, its focused test runs green, the
            real page boots and moves 0 -> 1 (and the proof bites), and the
            :advanced release build boots the same way"
    (if-not @emitted-tests-enabled?
      (skip-if-disabled! :reagent)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :reagent {:boot-witness? true}))))))

(deftest uix-emitted-tests-run-test
  (testing "the emitted UIx app compiles, its focused test runs green, the real
            page boots and moves 0 -> 1, and the :advanced release build boots
            the same way"
    (if-not @emitted-tests-enabled?
      (skip-if-disabled! :uix)
      (do (is @clojure-cli-available?
              "`clojure` CLI must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (is @node-available?
              "`node` must be on PATH when RF2_TEMPLATE_RUN_EMITTED_TESTS=1")
          (when (and @clojure-cli-available? @node-available?)
            (compile-and-run-emitted-test! :uix {}))))))

;; Playwright takes wait options third; the wrong slot silently uses its 30-second default.

(def ^:private timeout-fixture-budget-ms
  "The failure must name this budget rather than the library default of 30000."
  1000)

(defn ^:private timeout-fixture-page
  "Fixture HTML with dev-bundle globals so the driver reaches its mount/click waits."
  [title body script]
  (str "<meta charset=\"utf-8\">\n"
       "<title>" title "</title>\n"
       "<div id=\"app\">" body "</div>\n"
       "<script>window.goog = {}; window.cljs = {};\n" script "</script>\n"))

(def ^:private timeout-fixtures
  "One failure per driver wait and a responsive control under the same deadline."
  [{:name            "never-mounts"
    :wait            "the MOUNT wait"
    :expect-timeout? true
    :page            (timeout-fixture-page "never-mounts" "" "")}
   {:name            "dead-click"
    :wait            "the CLICK wait"
    :expect-timeout? true
    :page            (timeout-fixture-page
                       "dead-click"
                       "<h1>dead click</h1><button>+1</button><span>0</span>"
                       "")}
   {:name            "responsive"
    :wait            "both waits (control)"
    :expect-timeout? false
    :page            (timeout-fixture-page
                       "responsive"
                       "<h1>responsive</h1><button>+1</button><span>0</span>"
                       (str "document.querySelector('#app button')"
                            ".addEventListener('click', function () {\n"
                            "  document.querySelector('#app span').textContent = '1';\n"
                            "});\n"))}])

(defn ^:private run-timeout-fixture!
  "Run a fixture through the real driver under timeout-fixture-budget-ms."
  [^java.io.File root {:keys [name page]}]
  (let [tmp  (tmp-dir (str "rf2-template-proof-timeout-" name "-"))
        proj (.toFile tmp)]
    (try
      (let [public (io/file proj "resources/public")]
        (.mkdirs public)
        (spit (io/file public "index.html") page)
        (run-boot-proof-driver!
          root proj name :dev
          {"RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS" (str timeout-fixture-budget-ms)}))
      (finally (delete-recursively tmp)))))

(deftest boot-proof-honours-the-configured-timeout-test
  (testing "RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS governs the driver's mount
            and click waits, not merely its page load"
    (if-not @emitted-tests-enabled?
      (skip-if-disabled! "the boot proof's own timeout contract")
      (if-not @node-available?
        (do (announce-browser-skip! "the boot proof's timeout contract"
                                    "`node` is not on PATH")
            (is true "`node` unavailable — skipping the driver timeout contract"))
        (doseq [{:keys [name wait expect-timeout?] :as fixture} timeout-fixtures]
          (testing (str name " — " wait)
            (let [{:keys [exit out]} (run-timeout-fixture! (repo-root) fixture)
                  budget-named       (str "Timeout " timeout-fixture-budget-ms "ms exceeded")]
              (cond
                (and (= 2 exit) (not @browser-proofs-required?))
                (do (announce-browser-skip! (str "driver timeout contract — " name) out)
                    (is true (str "Chromium unavailable — " name " did not run")))

                (= 2 exit)
                (is false
                    (str "the driver timeout contract did NOT run for " name
                         ": Chromium was not launchable and `CI` is set. Output:\n" out))

                expect-timeout?
                (do (is (= 1 exit)
                        (str name " must FAIL: " wait " can never be satisfied on this "
                             "page. Output:\n" out))
                    ;; The verdict, and the whole point. An exit 1 alone is
                    ;; earned either way — with the options object in the arg
                    ;; slot the driver still fails, 30 seconds later, naming
                    ;; Playwright's default. It is the NUMBER that says whose
                    ;; deadline was in force.
                    (is (string/includes? out budget-named)
                        (str "the driver must name the CONFIGURED budget ("
                             budget-named "). Naming 30000ms instead means the "
                             "timeout reached Playwright as a page ARGUMENT "
                             "rather than as an option, and every wait in this "
                             "file is running unbounded by the value it asked "
                             "for. Output:\n" out)))

                :else
                (is (zero? exit)
                    (str "CONTROL — a page that mounts and reacts immediately must "
                         "still PASS under the same short budget, or the two "
                         "verdicts above prove only that " timeout-fixture-budget-ms
                         "ms is too little for any page. Output:\n" out))))))))))

;; Compare shipped skill leaves to real emissions, then compile and boot the shipped files.

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
  "Extract path/body pairs from a leaf generated region, including fenced file bodies."
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
      (project-files (run-template! tmp emitted-project-name substrate))
      (finally (delete-recursively tmp)))))

(defn- assert-leaf-equals-emission! [leaf files expected]
  (is (= expected files)
      (str leaf ": generated files differ from the template emission. " regenerate-hint)))

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
      (assert-leaf-equals-emission! "first-counter.md" (read-leaf-files "first-counter.md") reagent)
      (is (= #{"README.md" "package.json"} (set (keys label-only)))
          (str "the files that differ between substrates by the display label alone are "
               (pr-str (keys label-only)) " — the setup skill's UIx route tells the author "
               "to swap the label in README.md and package.json; revisit that sentence."))
      (is (= #{"deps.edn" "src/acme/my_app/core.cljs" "src/acme/my_app/views.cljs"
               "src/acme/my_app/stories.cljs"}
             (set (keys swapped)))
          (str "the two substrates now differ structurally in " (pr-str (keys swapped))
               " — the setup skill's UIx route is documented as a four-file swap; "
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
              (run-process! ["clojure" "-M:shadow:dev" "-m" "shadow.cljs.devtools.cli"
                             "compile" "app"]
                            proj env)]
          (is (zero? exit)
              (str "the broken-click recompile must itself succeed — an unregistered "
                   "event id is a runtime miss, not a compile error; exited " exit
                   ". Output:\n" out)))
        (let [{:keys [exit out]}
              (run-boot-proof-driver! root proj (str label " (broken click)") :dev
                                      {"RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS" "8000"})]
          (is (= 1 exit)
              (str "the dev-bundle boot proof must go RED (exit 1) when the +1 button "
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
        (is (= 13 (count files))
            (str "first-counter.md must carry the thirteen-file manifest; found "
                 (count files) ": " (pr-str (keys files))))
        (materialise-leaf! proj files)

        ;; --- SKILL.md step 2: point the framework coords at the checkout.
        ;; `rewrite-deps-for-local-run!` is the function the template tiers
        ;; use; it rewrites core, the adapter and Story's :dev coordinate, which
        ;; is exactly the leaf's set. The shape assertion below is the
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
          (testing (str label " — clojure -M:shadow:dev compile app test")
            (let [{:keys [exit out]}
                  (run-process! ["clojure" "-M:shadow:dev" "-m" "shadow.cljs.devtools.cli"
                                 "compile" "app" "test"]
                                proj env)]
              (is (zero? exit)
                  (str "`clojure -M:shadow:dev compile app test` exited " exit
                       " for the setup skill's SHIPPED default scaffold "
                       "(skills/re-frame2-setup/references/first-counter.md, the thirteen "
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
                (testing (str label " — Chromium dev-bundle boot proof (heading acme/my-app "
                              "painted, counter 0 -> 1, zero pageerror)")
                  (let [{:keys [exit out]}
                        (run-boot-proof-driver! root proj label :dev
                                                {"RF2_TEMPLATE_EXPECT_H1" emitted-project-name})]
                    (check-browser-proof!
                      (str "dev-bundle boot proof -- " label)
                      "dev-bundle boot"
                      exit out
                      (str "the shipped scaffold's dev-bundle boot proof exited " exit
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

            ;; --- the proof must bite, on both axes ------------------------------
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
