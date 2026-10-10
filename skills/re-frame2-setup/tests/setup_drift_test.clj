;;;; tests/setup_drift_test.clj — the re-frame2-setup skill's contract claims.
;;;;
;;;; Lock 0: the thirteen files in `references/first-counter.md` and the four
;;;; UIx files in `references/entry-namespace.md` are generated regions
;;;; rendered from `tools/template/` by `tests/first_counter_derivation.clj`,
;;;; and must equal that render — so a hand edit inside a region, or a
;;;; template change the leaves were not regenerated for, fails here. What the
;;;; template emits is tools/template's own suite to test. The other locks
;;;; hold the hand-written prose to the scaffold: the reduced day-one set (no
;;;; schemas, no Xray, no devtools preload, no CSP), lockstep as a build
;;;; discipline, the publication-state branch, and the zero-interview executor.
;;;;
;;;; It does not build the scaffold: the black-box `setup-skill-default-
;;;; scaffold-mounts-test` in tools/template/test/day8/re_frame2_template/
;;;; emitted_test_run_test.clj (behind RF2_TEMPLATE_RUN_EMITTED_TESTS=1) does.
;;;;
;;;; Run: bb tests/setup_drift_test.clj   (from skills/re-frame2-setup/)

(ns setup-drift-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def ^:private setup-root (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
(def ^:private repo-root (-> setup-root .getParentFile .getParentFile))

(defn- doc [parent rel] (delay (slurp (io/file parent rel))))

(def ^:private deps-versions-md   (doc setup-root "references/deps-versions.md"))
(def ^:private entry-namespace-md (doc setup-root "references/entry-namespace.md"))
(def ^:private shadow-cljs-md     (doc setup-root "references/shadow-cljs.md"))
(def ^:private first-counter-md   (doc setup-root "references/first-counter.md"))
(def ^:private skill-md           (doc setup-root "SKILL.md"))
(def ^:private readme-md          (doc setup-root "README.md"))
(def ^:private docs-setup-page-md (doc repo-root "docs/skills/re-frame2-setup.md"))
(def ^:private skills-index-md    (doc repo-root "skills/README.md"))
(def ^:private reagent-template-deps (doc repo-root "tools/template/resources/day8/re_frame2_template/_reagent/deps.edn"))
(def ^:private uix-template-deps     (doc repo-root "tools/template/resources/day8/re_frame2_template/_uix/deps.edn"))

(defn- has? [text needle]
  (boolean (if (string? needle) (str/includes? text needle) (re-find needle text))))

(defn- claims
  "Each row is [label text needle why]; the text must carry the needle (a
   string, or a regex)."
  [rows]
  (doseq [[label text needle why] rows]
    (is (has? text needle) (str label " must carry " (pr-str (str needle)) ": " why))))

(defn- offenders
  "[label token] for every token a labelled body carries."
  [labelled-bodies tokens]
  (vec (for [[label body] labelled-bodies, token tokens :when (str/includes? body token)] [label token])))

(defn- mvn-version
  "The :mvn/version pinned for `coord` in a deps.edn body, or nil."
  [deps-body coord]
  (some-> (re-find (re-pattern (str (java.util.regex.Pattern/quote coord) "\\s*\\{:mvn/version\\s+\"([^\"]+)\""))
                   deps-body)
          second))

;; ---------------------------------------------------------------------------
;; Lock 0 — the leaves are the template's emission
;; ---------------------------------------------------------------------------

;; Loaded, not run: its script guard keys off babashka.file.
(load-file (.getPath (io/file setup-root "tests/first_counter_derivation.clj")))

(def ^:private first-counter-files (delay (first-counter-derivation/extract-files @first-counter-md)))
(def ^:private uix-files (delay (first-counter-derivation/extract-files @entry-namespace-md)))

(def ^:private regenerate-hint
  "Regenerate with `bb tests/first_counter_derivation.clj` from skills/re-frame2-setup/; the generated regions are never hand-edited.")

(defn- assert-region-matches-render! [label files rendered]
  (is (seq rendered) (str label ": the renderer produced no files, so the comparison would be vacuous"))
  (is (= [] (sort (for [path (set (concat (keys files) (keys rendered)))
                        :when (not= (get files path) (get rendered path))]
                    path)))
      (str label ": these paths differ from (or are missing from) the template's emission. " regenerate-hint)))

(deftest first-counter-region-is-the-template-render
  (assert-region-matches-render! "first-counter.md" @first-counter-files (first-counter-derivation/reagent-files)))

(deftest uix-region-is-the-template-render
  (assert-region-matches-render! "entry-namespace.md §UIx greenfield" @uix-files (first-counter-derivation/uix-swap-files)))

(deftest generated-regions-carry-no-placeholders
  (is (= [] (for [[label files] [["first-counter.md" @first-counter-files] ["entry-namespace.md" @uix-files]]
                  [path body] files
                  :when (some #(str/includes? body %) ["{{" "<VERSION>" "<SHA>" "PLACEHOLDER"])]
              [label path]))
      "the default route writes no <VERSION>/<SHA> and no unsubstituted {{key}}")
  (let [deps (get @first-counter-files "deps.edn" "")]
    (is (some? (mvn-version deps "day8/re-frame2"))
        "first-counter.md's deps.edn must pin day8/re-frame2 with a literal :mvn/version")
    (is (= (mvn-version deps "day8/re-frame2") (mvn-version deps "day8/re-frame2-reagent"))
        "core and the adapter must be pinned at one version (lockstep)")))

(defn- committed-byte-size
  "Size as git stores the file: UTF-8, LF line endings."
  [^java.io.File f]
  (count (.getBytes (str/replace (slurp f) "\r\n" "\n") "UTF-8")))

(deftest every-leaf-meets-the-family-byte-ceiling
  ;; skills/README.md §Leaf size discipline.
  (is (= [] (for [f (.listFiles (io/file setup-root "references"))
                  :when (and (str/ends-with? (.getName f) ".md") (> (committed-byte-size f) 16384))]
              [(.getName f) (committed-byte-size f)]))
      "every reference leaf must be <= 16 KB (LF-normalised); trim the prose around a generated region, not the region")
  (is (<= (count (str/split-lines @skill-md)) 500) "SKILL.md exceeds the 500-line orchestrator ceiling"))

(deftest default-route-reads-one-leaf
  (claims
   [["SKILL.md" @skill-md "references/first-counter.md" "the default route reads first-counter.md"]
    ["SKILL.md" @skill-md "nothing else needs reading" "first-counter.md is the whole default"]
    ["README.md" @readme-md "Both routes land on the same canonical scaffold" "Lock 0 makes this literally true"]]))

;; ---------------------------------------------------------------------------
;; Lock 1 — lockstep is a build-time discipline. The runtime carries no
;; per-artefact version metadata, so no boot-time guard exists to promise.
;; ---------------------------------------------------------------------------

(deftest deps-versions-frames-lockstep-as-build-discipline
  (let [body @deps-versions-md]
    (claims
     [["deps-versions.md" body #"build/dependency discipline|not a boot-time runtime check" "lockstep is build-time"]
      ["deps-versions.md" body #"unsupported|undefined" "a mixed set is unsupported, not caught at runtime"]
      ["deps-versions.md" body "version_lockstep_test.clj" "point at where enforcement really lives"]])
    (is (not (re-find #"checked at boot time|enforced at boot|validated at boot" body))
        "deps-versions.md must not promise a boot-time version check that does not exist")))

;; Lock 1b — the hand-written prose agrees with the derived blocks it
;; explains. Both expectations are READ OUT of the generated blocks, so a
;; template bump moves them with the scaffold.
(defn- npm-package-names
  "The `dependencies` / `devDependencies` keys of a package.json body."
  [pkg-body]
  (->> (re-seq #"(?s)\"(?:dev)?[Dd]ependencies\"\s*:\s*\{(.*?)\}" pkg-body)
       (mapcat (fn [[_ inner]] (map second (re-seq #"\"([^\"]+)\"\s*:" inner))))
       distinct
       sort))

(deftest prose-leaves-agree-with-the-derived-npm-and-build-shape
  (let [packages (npm-package-names (get @first-counter-files "package.json" ""))
        aliases  (second (re-find #":deps\s*\{:aliases\s+(\[[^\]]*\])" (get @first-counter-files "shadow-cljs.edn" "")))]
    (is (seq packages) (str "control: no npm package could be read out of the derived package.json. " regenerate-hint))
    (is (= [] (remove #(str/includes? @deps-versions-md %) packages))
        "deps-versions.md must name every package the derived package.json declares; a recovering author restores that roster")
    (is (and aliases (str/includes? @shadow-cljs-md aliases))
        (str "shadow-cljs.md must carry the derived alias vector " aliases
             "; dropping :dev takes re-frame2-story off the classpath and fails `compile app`"))))

;; Spec 006's "UIx 2.x" is the hooks API family, which ships as
;; com.pitch/uix.core 1.x; there is no 2.x coordinate to chase.
(deftest uix-version-target-divergence-is-flagged
  (claims
   [["entry-namespace.md" @entry-namespace-md #"UIx 2\.x|version target" "explain the UIx version target"]
    ["entry-namespace.md" @entry-namespace-md #"known-good|tested" "the template pin is the tested set"]]))

;; ---------------------------------------------------------------------------
;; Lock 3 — no Xray host or preload is scaffold wiring, on any route.
;; ---------------------------------------------------------------------------

(deftest skill-carries-no-xray-host-wiring
  (is (= [] (offenders [["SKILL.md" @skill-md] ["README.md" @readme-md] ["first-counter.md" @first-counter-md]
                        ["shadow-cljs.md" @shadow-cljs-md] ["entry-namespace.md" @entry-namespace-md]
                        ["deps-versions.md" @deps-versions-md]]
                       ["data-rf-xray-host" "rf2-xray-host" "--rf-xray" "420px"
                        ":devtools/preloads" "day8.re-frame2-xray.preload"]))
      "Xray, its host column and its devtools preload attach later, by Xray's own recipe"))

;; Lock 4 — the row is headed by the error shadow-cljs 3.4.10 actually prints
;; for a missing CLJS namespace, and its cause is the Maven classpath.
(deftest reagent-dom-row-diagnoses-maven-not-npm
  (let [row (re-find #"(?m)^- \*\*`The required namespace \"reagent\.dom\.client\" is not available`.*$" @skill-md)]
    (is (and row (str/includes? row "reagent/reagent"))
        "SKILL.md's reagent.dom.client row must name the reagent/reagent Maven coordinate")
    (is (and row (re-find #"classpath|deps\.edn|Maven" row)) "and frame the fix as a classpath problem")
    (is (and row (not (str/includes? row "npm install react react-dom")))
        "a missing CLJS namespace is never fixed by installing npm packages")))

;; Lock 5 — CSP is a later, explicit step: a strict dev CSP is the
;; blank-first-page trap.
(deftest default-route-is-csp-free
  (is (= [] (offenders [["SKILL.md" @skill-md] ["first-counter.md" @first-counter-md]]
                       ["Content-Security-Policy" "unsafe-eval" "frame-ancestors"]))
      "SKILL.md and first-counter.md teach no CSP on the default route"))

;; Lock 5b — under `:deps {:aliases [:shadow :dev]}` the launcher ignores
;; shadow-cljs.edn's `:source-paths`; deps.edn's `:paths` and the `:shadow`
;; alias's `:extra-paths` own source discovery.
(deftest shadow-cljs-leaf-attributes-source-paths-to-deps-edn
  (claims
   [["shadow-cljs.md" @shadow-cljs-md #"is inert here|were ignored|ignores this key" ":source-paths is ignored under :deps"]
    ["shadow-cljs.md" @shadow-cljs-md ":paths" "deps.edn's :paths holds the app dir"]
    ["shadow-cljs.md" @shadow-cljs-md ":extra-paths" "the :shadow alias's :extra-paths holds the test dir"]
    ["SKILL.md" @skill-md "ignores `shadow-cljs.edn`'s `:source-paths`" "the router states the same rule"]]))

;; Lock 6 — a fresh project's shadow-cljs is a local devDependency.
(deftest first-counter-verify-command-uses-npx
  (claims [["first-counter.md" @first-counter-md "npx shadow-cljs watch app" "bare shadow-cljs is not on PATH"]]))

;; Lock 7 — UIx authors are routed to the substrate views, not Reagent's reg-view.
(deftest uix-not-routed-to-reagent-reg-view-counter
  (claims
   [["SKILL.md" @skill-md "does NOT use `reg-view`" "UIx authors must be routed to the substrate views"]
    ["first-counter.md" @first-counter-md #"Reagent only|Reagent-only" "the leaf flags itself as Reagent-only"]
    ["first-counter.md" @first-counter-md #"(?s)use-sub.*entry-namespace\.md|entry-namespace\.md.*use-sub"
     "the leaf redirects UIx authors to entry-namespace.md's use-sub path"]]))

;; Lock 8 — bare `npm install react react-dom` writes npm's latest.
(deftest js-module-react-row-uses-pinned-baseline
  (let [row (re-find #"(?m)^- \*\*`Cannot find module 'react'`.*$" @skill-md)]
    (is (and row (str/includes? row "pinned")) "the JS-module React row must recover from the pinned baseline")
    (is (and row (re-find #"reproducibility|cardinal rule" row)) "and give the reproducibility reason")
    (is (and row (str/includes? row "Don't run bare `npm install react react-dom`"))
        "and forbid bare npm install react react-dom")))

;; Lock 9 — the greenfield coordinate BRANCHES on publication state. Retire it
;; deliberately only when every day8/re-frame2* coordinate resolves.
(deftest deps-guidance-branches-on-actual-publication-state
  (claims
   [["deps-versions.md" @deps-versions-md #"not published|NOT on Clojars|not on Clojars|have not published|not yet published"
     "name the coordinate that does not resolve yet"]
    ["deps-versions.md" @deps-versions-md ":git/sha" "the only working manual route for an unresolvable coordinate"]
    ["deps-versions.md" @deps-versions-md #"After publication|Post-publish|post-publish|AFTER PUBLICATION"
     "label :mvn/version as the post-publish destination"]
    ["SKILL.md" @skill-md ":local/root \"<RE_FRAME2>/implementation/core\"" "step 2's pre-publish rewrite"]
    ["SKILL.md" @skill-md "generator route" "the generator emits the same unresolvable coordinate"]]))

;; Lock 10 — schemas are pay-as-you-go: the artefact arrives with the first
;; reg-app-schema, which throws loudly without it (Spec 010).
(deftest schemas-are-pay-as-you-go-not-day-one
  (is (= [] (offenders (seq @first-counter-files)
                       ["re-frame.schemas" "reg-app-schema" "register-schema!" "CounterDb" "re-frame2-schemas"]))
      "the default scaffold carries no schema artefact, require or registration")
  (claims
   [["deps-versions.md" @deps-versions-md ":rf.error/schemas-artefact-missing" "the loud throw without the artefact"]
    ["deps-versions.md" @deps-versions-md "re-frame.schemas" "require it before reg-app-schema"]
    ["SKILL.md" @skill-md "no schemas" "schemas are not day-one"]]))

;; Lock 11 — the default build carries no devtools preload.
(deftest default-shadow-build-carries-no-devtools-preload
  (is (not (str/includes? (get @first-counter-files "shadow-cljs.edn" ":devtools") ":devtools"))
      "the default shadow-cljs.edn carries no :devtools map; a preload is a later, explicit step"))

(deftest xray-is-a-next-step-not-day-one
  (is (= [] (offenders [["SKILL.md" @skill-md] ["first-counter.md" @first-counter-md]]
                       ["day8/re-frame2-xray" "day8.re-frame2-xray"]))
      "Xray is not day-one on either route")
  (claims
   [["SKILL.md" @skill-md "no Xray" "Xray is not day-one"]
    ["SKILL.md" @skill-md "Next steps" "the optional attachments route through the generated README's Next steps"]]))

;; Lock 12 — the skill runs the generator itself and asks nothing when no pin is supplied.
(deftest pin-default-is-zero-interview
  (is (not (re-find #"(?i)stop and ask" @deps-versions-md))
      "deps-versions.md must not stop to ask for a pin: the template baseline is the default")
  (claims
   [["deps-versions.md" @deps-versions-md #"default pin is the generator template's baseline|an author-supplied pin overrides"
     "the template baseline is the no-pin default"]
    ["SKILL.md" @skill-md #"no clarification round|never a reason to stop and ask|zero-interview" "state the zero-interview default"]]))

(deftest skill-executes-the-scaffold-and-reports-the-url
  (let [skill @skill-md
        fm    (second (re-find #"(?s)^---\r?\n(.*?)\r?\n---" skill))]
    (is (and fm (str/includes? fm "npm install") (str/includes? fm "shadow-cljs compile"))
        "allowed-tools must grant npm install and shadow-cljs compile")
    (claims
     [["SKILL.md" skill "The skill runs both commands itself" "the skill is the executor"]
      ["SKILL.md" skill "http://localhost:8280/" "report the actual dev URL"]
      ["SKILL.md" skill #"never exits|never terminates|does not exit" "a foreground watch blocks until the tool times out"]
      ["SKILL.md" skill #"detached|background" "run the watch detached"]
      ["SKILL.md" skill #"not the mount|does not prove the mount|don't claim the mount|compile success alone"
       "compile success proves the build, not the browser mount"]])))

;; ---------------------------------------------------------------------------
;; Lock 13 — the public entry-ramp docs stay in sync with the contract.
;; ---------------------------------------------------------------------------

(def ^:private literal-artefact-count
  "A literal count of the lockstep roster (`all ten`, `fourteen coordinates`),
   scoped to artefact nouns so `twelve files` stays legal."
  #"(?i)\ball\s+(?:ten|eleven|twelve|thirteen|fourteen)\s+(?:[^\s.;:,()]+\s+){0,3}?(?:artefacts?|artifacts?|coordinates|coords|ship)\b|\b(?:ten|eleven|twelve|thirteen|fourteen)\s+(?:[^\s.;:,()]+\s+){0,3}?(?:artefacts?|artifacts?|coordinates|coords)\b")

(deftest docs-setup-page-no-stale-artefact-count
  (is (= [] (for [[label body] [["docs/skills/re-frame2-setup.md" @docs-setup-page-md]
                                ["SKILL.md" @skill-md] ["references/deps-versions.md" @deps-versions-md]]
                  :let [hit (re-find literal-artefact-count body)]
                  :when hit]
              [label hit]))
      "state lockstep as a rule and point at spec/Conventions.md §Lockstep versioning, never a count that goes stale")
  ;; Deliberately omitted from the description (spec/design.md §6): it also
  ;; matches the non-trivial-existing-app case the skill routes away.
  (is (not (str/includes? (str/lower-case @docs-setup-page-md) "add re-frame2 to my repo"))
      "docs/skills/re-frame2-setup.md must not list \"add re-frame2 to my repo\" as a trigger"))

(deftest skills-index-template-form-carries-pre-split-caveat
  (claims
   [["skills/README.md" @skills-index-md "tools/template" "it mentions the generator template"]
    ["skills/README.md" @skills-index-md #"Pre-split|pre-split|isn't published yet|not published yet|can't resolve"
     "a generator mention carries the pre-split caveat"]
    ["skills/README.md" @skills-index-md ":local/root" "and the working :local/root route"]]))

;; ---------------------------------------------------------------------------
;; Lock 15 — the UIx route is the template's four-file swap of the same
;; Xray-free, schema-free scaffold.
;; ---------------------------------------------------------------------------

(deftest both-templates-are-xray-free-and-schema-free
  (is (= [] (offenders [["_reagent/deps.edn" @reagent-template-deps] ["_uix/deps.edn" @uix-template-deps]]
                       ["re-frame2-xray" "re-frame2-schemas"]))
      "if the template ships one deliberately, update Lock 15 and the skill's day-one rule together"))

(deftest uix-route-shares-the-default-build-wiring
  (let [body @entry-namespace-md]
    (is (not (str/includes? body ":builds")) "the UIx route ships the default shadow-cljs.edn unchanged")
    (is (not (re-find #"(?s)```(html|css)\r?\n" body)) "and the default index.html / app.css unchanged")
    (is (str/includes? body "identical to the Reagent scaffold") "entry-namespace.md must say the other nine files are shared")))

(deftest uix-route-is-a-four-file-swap
  (is (= #{"deps.edn" "src/acme/my_app/core.cljs" "src/acme/my_app/views.cljs" "src/acme/my_app/stories.cljs"}
         (set (first-counter-derivation/substrate-swap-paths)))
      "the template varies a different file set per substrate; regenerate the leaves and update SKILL.md's four-file-swap rule")
  (is (str/includes? @skill-md "four-file swap") "SKILL.md states the UIx route as a four-file swap"))

(let [{:keys [fail error]} (run-tests 'setup-drift-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
