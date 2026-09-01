;;;; tests/generator_route_test.clj — command-level fixture for the setup
;;;; skill's pre-publish GENERATOR route (rf2-h4q82).
;;;;
;;;; THE DEFECT THIS PINS. The skill runs the deps-new generator itself when
;;;; the author asks for that route. Its only documented pre-publish command
;;;; passed a RELATIVE local root:
;;;;
;;;;     clojure -Sdeps '{:deps {day8/re-frame2-template
;;;;                             {:local/root "tools/template"}}}' \
;;;;             -Tnew create :template day8/re-frame2-template :name acme/my-app
;;;;
;;;; A Clojure `:local/root` is resolved against the COMMAND'S working
;;;; directory. Run from the fresh directory the author is scaffolding, that
;;;; relative path means `<target>/tools/template`, which does not exist, and
;;;; tools.deps dies before deps-new ever loads the template:
;;;;
;;;;     Error building classpath. Local lib day8/re-frame2-template not
;;;;     found: <target>\tools\template
;;;;
;;;; Running the same literal from the re-frame2 checkout makes the dependency
;;;; resolve but relocates the emitted project into the checkout. So the route
;;;; had NO command that both found the template and wrote where the author
;;;; asked.
;;;;
;;;; WHY THIS IS NOT A STRING-COMPARISON TEST. A test asserting the README
;;;; contains some expected command text would have passed throughout the
;;;; defect's entire life — the broken command was exactly the command the
;;;; docs prescribed. Both arms below therefore make a REAL observation:
;;;;
;;;;   * The resolution arm (always runs, needs nothing but bb) takes the
;;;;     `:local/root` value out of the skill's own documented command, resolves
;;;;     it the way tools.deps would — against a freshly created directory used
;;;;     as the command's cwd — and asserts the result is a real directory
;;;;     holding the template's own `deps.edn` + hooks namespace. Reinstating
;;;;     the relative form makes that resolve under the temp target, where no
;;;;     such directory exists, and the arm goes red. The same arm runs the
;;;;     resolution ONCE against the old relative form as a control, so a green
;;;;     is never the "no matches" kind of green.
;;;;
;;;;   * The live arm (opt-in, `RF2_SETUP_RUN_GENERATOR=1`) shells the actual
;;;;     `clojure -Sdeps … -Tnew create …` command out of a clean directory
;;;;     created OUTSIDE this checkout, reports the generator's own exit code,
;;;;     and asserts the emitted manifest under the requested target. Its
;;;;     non-vacuity partner substitutes the relative local root while staying
;;;;     in that same clean directory and requires a non-zero exit naming the
;;;;     missing local lib UNDER the target, with nothing emitted.
;;;;
;;;; WHY THE LIVE ARM IS OPT-IN. It needs the Clojure CLI, the `-Tnew` deps-new
;;;; tool installed per deps-new's README, and a warm dependency cache. The
;;;; `skills-structural` CI job that loops `tests/*_test.clj` provisions
;;;; Babashka only, so the live arm cannot run there and this file must not
;;;; pretend otherwise: with the flag unset it prints a loud NOT RUN banner and
;;;; the resolution arm carries the gate. Run the live arm locally with:
;;;;
;;;;     RF2_SETUP_RUN_GENERATOR=1 bb tests/generator_route_test.clj
;;;;
;;;; Run locally:  bb tests/generator_route_test.clj   (from skills/re-frame2-setup/)
;;;; Exit:         0 = pass, non-zero = fail.
;;;;
;;;; NOT published — `package.json` :files excludes `tests/`.

(ns generator-route-test
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private setup-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)    ;; tests/
      (.getParentFile)))  ;; skills/re-frame2-setup/

(def ^:private repo-root
  (-> setup-root
      (.getParentFile)    ;; skills/
      (.getParentFile)))  ;; repo root

(defn- fwd
  "Absolute path with forward slashes. Java accepts `/` on Windows, and it
   keeps the path free of the `\\\\` escaping an EDN string would otherwise
   need — the portability property the documented command relies on."
  [^java.io.File f]
  (str/replace (.getCanonicalPath f) "\\" "/"))

(def ^:private readme-md
  (delay (slurp (io/file setup-root "README.md"))))

(def ^:private skill-md
  (delay (slurp (io/file setup-root "SKILL.md"))))

;; ---------------------------------------------------------------------------
;; The command the skill teaches, read out of the skill's own docs
;; ---------------------------------------------------------------------------
;;
;; The generator route is documented in README.md §Running the generator
;; pre-publish. `local-root-form` is the literal `:local/root` VALUE that
;; section prescribes; every assertion below is made against that value rather
;; than against a copy of it kept here, so a doc edit that reinstates a
;; cwd-relative root is what goes red.

(def ^:private generator-section
  (delay
    (let [body @readme-md
          start (str/index-of body "### Running the generator pre-publish")]
      (when start
        (let [rest-of (subs body (+ start 4))
              end (str/index-of rest-of "\n## ")]
          (if end (subs rest-of 0 end) rest-of))))))

(def ^:private local-root-form
  "The `:local/root` value the documented command passes, e.g.
   `<RE_FRAME2>/tools/template`."
  (delay
    (some-> (re-find #":local/root\s+\"([^\"]+)\"" (or @generator-section ""))
            second)))

(def ^:private checkout-placeholder "<RE_FRAME2>")

(defn- resolve-local-root
  "Resolve `root` the way tools.deps resolves a `:local/root`: relative paths
   against the COMMAND'S working directory, absolute paths as given."
  [^String root ^java.io.File cwd]
  (let [f (io/file root)]
    (if (.isAbsolute f) f (io/file cwd root))))

(defn- template-dir?
  "Is `f` really the reviewed deps-new template — its own deps.edn plus the
   hooks namespace deps-new loads off the classpath?"
  [^java.io.File f]
  (and (.isDirectory f)
       (.isFile (io/file f "deps.edn"))
       (.isFile (io/file f "src/day8/re_frame2_template/hooks.clj"))))

(defn- fresh-dir!
  "Create an empty directory OUTSIDE this checkout, under the OS temp root."
  [prefix]
  (let [p (java.nio.file.Files/createTempDirectory
           prefix (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile p)))

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)] (delete-tree! child)))
    (.delete f)))

;; ---------------------------------------------------------------------------
;; Arm 1 — path resolution (always runs; bb only)
;; ---------------------------------------------------------------------------
;;
;; This is the arm that actually gates CI. It does not compare strings: it
;; performs the same resolution tools.deps performs and then asks the
;; filesystem whether the answer is the reviewed template.

(deftest documented-command-is-present-and-shaped-for-an-absolute-root
  (testing "README.md still carries the pre-publish generator command (premise of this suite)"
    (is (some? @generator-section)
        (str "README.md no longer has a '### Running the generator pre-publish' "
             "section. If the generator route was removed deliberately, revisit "
             "this suite; otherwise the command the skill executes has lost its "
             "documented home (rf2-h4q82)."))
    (let [section (or @generator-section "")]
      (is (str/includes? section "-Tnew create :template day8/re-frame2-template")
          (str "The generator section no longer shows the deps-new invocation "
               "the skill runs (rf2-h4q82)."))
      (is (some? @local-root-form)
          (str "The generator section no longer passes a :local/root at all. "
               "Pre-publish that is the only route that resolves the template "
               "(rf2-h4q82)."))))
  (testing "the documented :local/root is anchored to the checkout, not to the command's cwd"
    (let [root (or @local-root-form "")]
      (is (str/includes? root checkout-placeholder)
          (str "The documented :local/root is \"" root "\" — it does not name the "
               "re-frame2 checkout. A root that is not anchored to the checkout "
               "resolves against the author's own directory (rf2-h4q82)."))
      (is (str/ends-with? root "/tools/template")
          (str "The documented :local/root is \"" root "\" — it must end in "
               "/tools/template, the reviewed deps-new template directory "
               "(rf2-h4q82)."))
      (is (not (str/includes? root "\\"))
          (str "The documented :local/root carries a backslash. Render the path "
               "with forward slashes: Java accepts them on Windows, and it keeps "
               "the EDN string free of hand-authored escaping (rf2-h4q82)."))))
  (testing "SKILL.md states the two coordinates so the executing agent cannot conflate them"
    (let [skill @skill-md]
      (is (str/includes? skill "README.md#running-the-generator-pre-publish")
          (str "SKILL.md's generator rule no longer points at the section "
               "carrying the working command (rf2-h4q82)."))
      (is (str/includes? skill "absolute")
          (str "SKILL.md's generator rule no longer requires an ABSOLUTE "
               ":local/root. The relative form fails from the author's "
               "directory (rf2-h4q82).")))))

(deftest documented-local-root-resolves-to-the-template-from-a-fresh-target
  (let [target (fresh-dir! "rf2-setup-resolve-")]
    (try
      (testing "CONTROL: the OLD relative form does NOT resolve from the fresh target"
        ;; Exercise the instrument against an input it must flag. Without this,
        ;; a green below could equally mean "the check is inert".
        (let [resolved (resolve-local-root "tools/template" target)]
          (is (not (template-dir? resolved))
              (str "CONTROL FAILED: the relative \"tools/template\" resolved to a "
                   "real template directory from a freshly created target. The "
                   "resolution check below therefore proves nothing — investigate "
                   "before trusting it (rf2-h4q82).")))
        (let [resolved (resolve-local-root "tools/template" target)]
          (is (= (.getName resolved) "template")
              "CONTROL sanity: the relative form should resolve under the target.")))
      (testing "the DOCUMENTED form resolves to the reviewed template from that same fresh target"
        (let [root     (str/replace (or @local-root-form "") checkout-placeholder (fwd repo-root))
              resolved (resolve-local-root root target)]
          (is (template-dir? resolved)
              (str "The :local/root the skill documents does not resolve to the "
                   "reviewed deps-new template when the command runs from a fresh "
                   "target directory. Resolved to: " (.getPath resolved) ". This is "
                   "the rf2-h4q82 defect — tools.deps resolves a relative "
                   ":local/root against the COMMAND'S cwd, so from the directory "
                   "being scaffolded it means <target>/tools/template and the "
                   "command dies with 'Local lib day8/re-frame2-template not "
                   "found' before deps-new loads the template."))
          (is (not (str/starts-with? (fwd resolved) (str (fwd target) "/")))
              (str "The documented :local/root resolved UNDER the fresh target ("
                   (.getPath resolved) "). It must name the reviewed checkout "
                   "(rf2-h4q82)."))))
      (finally (delete-tree! target)))))

;; ---------------------------------------------------------------------------
;; Arm 2 — the live generator run (opt-in: RF2_SETUP_RUN_GENERATOR=1)
;; ---------------------------------------------------------------------------

(def ^:private live? (= "1" (System/getenv "RF2_SETUP_RUN_GENERATOR")))

(def ^:private emitted-manifest
  ["deps.edn" "shadow-cljs.edn" "package.json" "src/acme/my_app/core.cljs"])

(defn- run-generator!
  "Run the generator command the skill teaches, from `cwd`, with `root` as the
   `:local/root`. Returns {:exit :out :err}. `root` is passed verbatim so the
   caller can substitute the broken relative form for the non-vacuity arm."
  [^java.io.File cwd ^String root]
  (let [deps (str "{:deps {day8/re-frame2-template {:local/root \"" root "\"}}}")]
    (-> (process/process
         ["clojure" "-Sdeps" deps
          "-Tnew" "create"
          ":template" "day8/re-frame2-template"
          ":name" "acme/my-app"
          ":substrate" ":uix"]
         {:dir cwd :out :string :err :string})
        deref)))

(deftest live-generator-emits-under-the-requested-target
  (if-not live?
    (println (str "\n  [LIVE ARM NOT RUN] generator_route_test: the real "
                  "`clojure -Sdeps … -Tnew create …` arms need the Clojure CLI "
                  "and the -Tnew deps-new tool. Set RF2_SETUP_RUN_GENERATOR=1 to "
                  "run them. The path-resolution arm above ran and gated this "
                  "change.\n"))
    (let [target   (fresh-dir! "rf2-setup-generate-")
          abs-root (str/replace (or @local-root-form "") checkout-placeholder (fwd repo-root))]
      (try
        (testing "POSITIVE: the documented command exits 0 and writes under the target"
          (let [{:keys [exit out err]} (run-generator! target abs-root)
                project (io/file target "my-app")]
            (is (zero? exit)
                (str "Generator exit " exit " (expected 0).\nSTDOUT:\n" out
                     "\nSTDERR:\n" err))
            (is (.isDirectory project)
                (str "The generator did not create my-app/ under the requested "
                     "target " (.getPath target) ". deps-new emits into a child "
                     "named after :name's artefact (rf2-h4q82)."))
            (doseq [rel emitted-manifest]
              (let [f (io/file project rel)]
                (is (and (.isFile f) (pos? (.length f)))
                    (str "Emitted manifest is missing or empty: " rel
                         " under " (.getPath project) ". A skipped or "
                         "half-run generator must not pass (rf2-h4q82)."))))))
        (testing "CONTROL: no generated project or manifest lands in the re-frame2 checkout"
          (doseq [stray ["my-app" "tools/template/my-app"]]
            (is (not (.exists (io/file repo-root stray)))
                (str "The generator wrote " stray " into the re-frame2 checkout. "
                     "The command's cwd, not the template's location, decides "
                     "where the project lands (rf2-h4q82)."))))
        (testing "NON-VACUITY: the old relative :local/root fails before emission, in the same target"
          (let [before (fresh-dir! "rf2-setup-negative-")]
            (try
              (let [{:keys [exit out err]} (run-generator! before "tools/template")
                    combined (str out "\n" err)]
                (is (not (zero? exit))
                    (str "The relative :local/root \"tools/template\" exited 0 from "
                         "a clean target. It must fail — otherwise the positive arm "
                         "above proves nothing (rf2-h4q82).\nSTDOUT:\n" out
                         "\nSTDERR:\n" err))
                (is (str/includes? combined "day8/re-frame2-template not found")
                    (str "Expected tools.deps to report the local lib missing. "
                         "Got:\n" combined))
                (is (str/includes? (str/replace combined "\\" "/") (fwd before))
                    (str "Expected the missing local root to be reported UNDER the "
                         "target directory " (fwd before) " — that is the defect. "
                         "Got:\n" combined))
                (is (empty? (seq (.listFiles before)))
                    (str "The failing command emitted files into " (.getPath before)
                         ". It must fail before deps-new emits anything.")))
              (finally (delete-tree! before)))))
        (finally (delete-tree! target))))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'generator-route-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
