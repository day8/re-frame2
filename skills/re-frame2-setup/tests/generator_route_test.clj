;;;; The documented generator command resolves from its target directory.
;;;; Run: bb tests/generator_route_test.clj
;;;; Set RF2_SETUP_RUN_GENERATOR=1 to exercise the real command.

(ns generator-route-test
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def ^:private setup-root (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
(def ^:private repo-root (-> setup-root .getParentFile .getParentFile))

(defn- fwd
  "Canonical path with forward slashes, which Java accepts on Windows and
   which keeps an EDN string free of escaping."
  [^java.io.File f]
  (str/replace (.getCanonicalPath f) "\\" "/"))

(def ^:private generator-section
  "references/generator-route.md §The pre-publish command, or nil."
  (delay
    (let [f (io/file setup-root "references/generator-route.md")
          body (if (.isFile f) (slurp f) "")]
      (when-let [start (str/index-of body "## The pre-publish command")]
        (let [rest-of (subs body (+ start 3))
              end     (str/index-of rest-of "\n## ")]
          (if end (subs rest-of 0 end) rest-of))))))

(def ^:private local-root-form
  "The `:local/root` value the documented command passes, e.g. `<RE_FRAME2>/tools/template`."
  (delay (second (re-find #":local/root\s+\"([^\"]+)\"" (or @generator-section "")))))

(def ^:private checkout-placeholder "<RE_FRAME2>")

(defn- absolute-root []
  (str/replace (or @local-root-form "") checkout-placeholder (fwd repo-root)))

(defn- resolve-local-root
  "Resolve `root` as tools.deps resolves a `:local/root`: relative to the command's cwd."
  [^String root ^java.io.File cwd]
  (let [f (io/file root)]
    (if (.isAbsolute f) f (io/file cwd root))))

(defn- template-dir?
  "Is `f` the deps-new template: its deps.edn plus the hooks namespace deps-new loads?"
  [^java.io.File f]
  (and (.isDirectory f)
       (.isFile (io/file f "deps.edn"))
       (.isFile (io/file f "src/day8/re_frame2_template/hooks.clj"))))

(defn- fresh-dir!
  "An empty directory under the OS temp root, outside this checkout."
  [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)] (delete-tree! child)))
    (.delete f)))

(deftest documented-command-is-present-and-shaped-for-an-absolute-root
  (let [section (or @generator-section "")
        root    (or @local-root-form "")]
    (is (str/includes? section "-Tnew create :template day8/re-frame2-template")
        "references/generator-route.md §The pre-publish command must show the deps-new invocation the skill runs")
    (is (str/includes? root checkout-placeholder)
        (str "the documented :local/root \"" root "\" must be anchored to the re-frame2 checkout"))
    (is (not (str/includes? root "\\")) "the documented :local/root must use forward slashes"))
  (let [skill (slurp (io/file setup-root "SKILL.md"))]
    (is (str/includes? skill "references/generator-route.md")
        "SKILL.md's generator rule must point at the section carrying the command")
    (is (str/includes? skill "absolute") "SKILL.md's generator rule must require an ABSOLUTE :local/root")))

(deftest documented-local-root-resolves-to-the-template-from-a-fresh-target
  (let [target (fresh-dir! "rf2-setup-resolve-")]
    (try
      (is (template-dir? (resolve-local-root (absolute-root) target))
          "the documented :local/root must resolve to the deps-new template from a fresh target directory")
      (finally (delete-tree! target)))))

(def ^:private live? (= "1" (System/getenv "RF2_SETUP_RUN_GENERATOR")))

(defn- run-generator!
  "Run the documented generator command from `cwd` with `root` as the `:local/root`."
  [^java.io.File cwd ^String root]
  @(process/process
    ["clojure" "-Sdeps" (str "{:deps {day8/re-frame2-template {:local/root \"" root "\"}}}")
     "-Tnew" "create" ":template" "day8/re-frame2-template" ":name" "acme/my-app" ":substrate" ":uix"]
    {:dir cwd :out :string :err :string}))

(deftest live-generator-emits-under-the-requested-target
  (if-not live?
    (println "\n  [LIVE ARM NOT RUN] generator_route_test: set RF2_SETUP_RUN_GENERATOR=1 to run the real"
             "`clojure -Sdeps … -Tnew create …` arms. The resolution arm above ran.\n")
    (let [target (fresh-dir! "rf2-setup-generate-")]
      (try
        (testing "the documented command exits 0 and writes under the target"
          (let [{:keys [exit out err]} (run-generator! target (absolute-root))
                project (io/file target "my-app")]
            (is (zero? exit) (str "generator exit " exit "\nSTDOUT:\n" out "\nSTDERR:\n" err))
            (is (= [] (remove #(let [f (io/file project %)] (and (.isFile f) (pos? (.length f))))
                              ["deps.edn" "shadow-cljs.edn" "package.json" "src/acme/my_app/core.cljs"]))
                (str "these emitted files are missing or empty under " (.getPath project)))))
        (testing "nothing lands in the re-frame2 checkout"
          (is (= [] (filter #(.exists (io/file repo-root %)) ["my-app" "tools/template/my-app"]))))
        (finally (delete-tree! target))))))

(let [{:keys [fail error]} (run-tests 'generator-route-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
