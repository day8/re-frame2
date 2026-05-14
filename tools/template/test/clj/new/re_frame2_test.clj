(ns clj.new.re-frame2-test
  "JVM tests for day8/clj-template.re-frame2 (rf2-lrtc).

   Strategy:

     1. Generate a tmp app via the template's main fn for each substrate
        (Reagent / UIx / Helix).
     2. Walk the generated tree and assert the expected file shape.
     3. Read the generated `deps.edn`, parse it as EDN, and assert the
        expected substrate-adapter coord is present.
     4. Best-effort: if `clojure` is on PATH, run `clojure -P` against
        the generated `deps.edn` to confirm a successful deps-parse.
        The deps-parse step is informational — its absence does not
        fail the test (the static shape checks above are the harder
        contract)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clj.new.re-frame2 :as rft]
            [clj.new.templates :as tmpl]))

;; --- Test helpers ----------------------------------------------------------

(defn- tmp-dir
  "Create a fresh temp directory and return its absolute path. Caller is
  responsible for cleanup."
  [prefix]
  (let [f (java.nio.file.Files/createTempDirectory
            prefix
            (into-array java.nio.file.attribute.FileAttribute []))]
    (.toAbsolutePath f)))

(defn- delete-recursively
  "Recursively delete a directory."
  [^java.nio.file.Path path]
  (when (java.nio.file.Files/exists path (into-array java.nio.file.LinkOption []))
    (with-open [stream (java.nio.file.Files/walk
                         path
                         (into-array java.nio.file.FileVisitOption []))]
      (->> stream
           .iterator
           iterator-seq
           reverse
           (run! #(java.nio.file.Files/deleteIfExists ^java.nio.file.Path %))))))

(defn- run-template!
  "Run the template's main fn inside the given temp directory. Returns
  the generated project's root path as a `java.io.File`."
  [tmp project-name substrate]
  (let [dir-str   (.toString ^java.nio.file.Path tmp)
        proj-dir  (.getPath (io/file dir-str
                                     (tmpl/project-name project-name)))
        sub-args  (when substrate [:substrate substrate])]
    ;; clj-new's ->files reads *dir* (via project-dir) to decide where
    ;; to land the generated tree. Rebind it to our temp directory for
    ;; the duration of the template's main fn.
    (binding [tmpl/*dir* proj-dir]
      (apply rft/re-frame2 project-name sub-args))
    (io/file proj-dir)))

(defn- read-edn [^java.io.File f]
  (edn/read-string (slurp f)))

(defn- clojure-cli-available? []
  (try
    (let [pb (ProcessBuilder. ^java.util.List
                              ["clojure" "--help"])]
      (.redirectErrorStream pb true)
      (let [p (.start pb)
            ok? (.waitFor p)]
        (zero? ok?)))
    (catch Throwable _ false)))

(defn- run-clojure-P
  "Run `clojure -P` in the given directory. Returns {:exit n :out s} or
   nil if the clojure CLI is unavailable."
  [^java.io.File dir]
  (when (clojure-cli-available?)
    (let [pb (ProcessBuilder. ^java.util.List ["clojure" "-P"])
          _  (.directory pb dir)
          _  (.redirectErrorStream pb true)
          p  (.start pb)
          out (slurp (.getInputStream p))
          ec (.waitFor p)]
      {:exit ec :out out})))

(defn- file-exists?
  "True if `path` (relative to `root`) exists as a regular file."
  [^java.io.File root path]
  (.isFile (io/file root path)))

;; --- The expected per-substrate shape ------------------------------------

(def ^:private common-files
  ["deps.edn"
   "shadow-cljs.edn"
   "package.json"
   "README.md"
   ".gitignore"
   ;; Dev ergonomics bundle (rf2-r2jqo).
   ".editorconfig"
   ".clj-kondo/config.edn"
   ;; Formatter config (rf2-5ecqj).
   ".cljfmt.edn"
   ;; Git pre-commit hook config (rf2-s8xee).
   ".lefthook.yml"
   "dev/user.clj"
   "dev/scratch.cljs"
   "resources/public/index.html"
   "resources/public/css/app.css"])

(def ^:private per-substrate-sources
  ;; Generated under src/<nested-dirs>/ and test/<nested-dirs>/. For
  ;; project-name "acme/my-app" clj-new produces namespace
  ;; "acme.my-app" → nested-dirs "acme/my_app".
  ["src/acme/my_app/core.cljs"
   "src/acme/my_app/events.cljs"
   "src/acme/my_app/subs.cljs"
   "src/acme/my_app/views.cljs"
   "test/acme/my_app/events_test.cljs"])

(def ^:private substrate-coord
  {:reagent 'day8/re-frame2-reagent
   :uix     'day8/re-frame2-uix
   :helix   'day8/re-frame2-helix})

;; --- Tests ---------------------------------------------------------------

(defn- assert-shape!
  "For a given substrate, generate the app inside a tmp dir, walk the
  expected file tree, and assert deps.edn contains the expected coords."
  [substrate]
  (let [tmp (tmp-dir (str "rf2-template-" (name substrate) "-"))]
    (try
      (let [root (run-template! tmp "acme/my-app" substrate)]
        (doseq [p (concat common-files per-substrate-sources)]
          (is (file-exists? root p)
              (str "expected file " p " in generated tree for substrate " substrate)))

        ;; -- deps.edn structure --
        (let [deps (read-edn (io/file root "deps.edn"))]
          (is (map? deps) "deps.edn parses as a map")
          (is (contains? (:deps deps) 'day8/re-frame2)
              "deps.edn references day8/re-frame2 core")
          (is (contains? (:deps deps) (substrate-coord substrate))
              (str "deps.edn references " (substrate-coord substrate)))
          (let [version (get-in deps [:deps 'day8/re-frame2 :mvn/version])]
            (is (= "0.0.1.alpha" version)
                "core coord pinned to alpha-channel version")))

        ;; -- shadow-cljs.edn structure --
        (let [scs  (read-edn (io/file root "shadow-cljs.edn"))
              app  (get-in scs [:builds :app])
              tst  (get-in scs [:builds :test])]
          (is (= :browser (:target app))
              "shadow-cljs :app build targets :browser")
          (is (= 'acme.my-app.core/init
                 (get-in app [:modules :main :init-fn]))
              "init-fn matches generated namespace")
          (is (some #{"test"} (:source-paths scs))
              "shadow-cljs.edn :source-paths includes \"test\" so the emitted test file is discoverable")
          (is (= :node-test (:target tst))
              "shadow-cljs :test build targets :node-test")
          ;; Causa preload (rf2-y9zqc).
          (is (some #{'day8.re-frame2-causa.preload}
                    (get-in app [:devtools :preloads]))
              "shadow-cljs :app :devtools/preloads wires Causa"))

        ;; -- Causa coord in deps.edn (rf2-y9zqc) --
        (let [deps (read-edn (io/file root "deps.edn"))]
          (is (contains? (:deps deps) 'day8/re-frame2-causa)
              "deps.edn references day8/re-frame2-causa")
          (is (= "0.0.1.alpha"
                 (get-in deps [:deps 'day8/re-frame2-causa :mvn/version]))
              "Causa coord lockstep with core version"))

        ;; -- package.json sanity --
        (let [pj-text (slurp (io/file root "package.json"))]
          (is (.contains pj-text "\"shadow-cljs\"")
              "package.json declares shadow-cljs devDependency")
          (is (.contains pj-text "\"react\"")
              "package.json declares react"))

        ;; -- views.cljs picks up the substrate-specific shape --
        (let [views-text (slurp (io/file root "src/acme/my_app/views.cljs"))]
          (case substrate
            :reagent (is (.contains views-text "reg-view")
                         "Reagent views.cljs uses reg-view")
            :uix     (is (.contains views-text "defui")
                         "UIx views.cljs uses defui")
            :helix   (is (.contains views-text "defnc")
                         "Helix views.cljs uses defnc")))

        ;; -- README badges block (rf2-sufwn) --
        (let [readme-text (slurp (io/file root "README.md"))]
          (is (.contains readme-text "img.shields.io/badge/built")
              "README ships a 'built with re-frame2' badge")
          (is (.contains readme-text
                         (case substrate
                           :reagent "substrate-Reagent"
                           :uix     "substrate-UIx"
                           :helix   "substrate-Helix"))
              "README ships the per-substrate badge")
          (is (.contains readme-text "License-MIT")
              "README ships a License badge"))

        ;; -- best-effort deps-parse with `clojure -P` --
        ;;
        ;; The generated app pins re-frame2 to v0.0.1.alpha (per VERSION
        ;; at the repo root). Until the alpha artefacts are actually
        ;; published to Clojars, `clojure -P` will fail with an
        ;; "Error building classpath. Could not find artifact" message
        ;; — that's expected at this stage of the lifecycle, and
        ;; orthogonal to whether the template scaffolds correctly. We
        ;; treat the "artefact not on Clojars" outcome as a known-skip
        ;; rather than a test failure; once an alpha publish lands,
        ;; the assertion below becomes a real signal automatically.
        (when-let [{:keys [exit out]} (run-clojure-P root)]
          (cond
            (zero? exit)
            (is true "`clojure -P` succeeded — alpha artefacts are publicly resolvable")

            (re-find #"Could not find artifact day8(:|/)re-frame2" out)
            (println (str "  [skip] `clojure -P` cannot find day8/re-frame2 " substrate
                          " coords on Clojars — this is expected until an alpha "
                          "publish lands. Static shape checks above still cover "
                          "the template contract."))

            :else
            (is (zero? exit)
                (str "`clojure -P` exited " exit " for " substrate
                     " generated app. Output:\n" out)))))
      (finally
        (delete-recursively tmp)))))

(deftest reagent-default-substrate-test
  (testing "default (no :substrate arg) produces Reagent variant"
    (let [tmp (tmp-dir "rf2-template-default-")]
      (try
        (let [root (run-template! tmp "acme/my-app" nil)
              deps (read-edn (io/file root "deps.edn"))]
          (is (contains? (:deps deps) 'day8/re-frame2-reagent)
              "default substrate is Reagent"))
        (finally
          (delete-recursively tmp))))))

(deftest reagent-substrate-test
  (testing ":substrate :reagent produces the expected tree"
    (assert-shape! :reagent)))

(deftest uix-substrate-test
  (testing ":substrate :uix produces the expected tree"
    (assert-shape! :uix)))

(deftest helix-substrate-test
  (testing ":substrate :helix produces the expected tree"
    (assert-shape! :helix)))

(deftest invalid-substrate-rejected-test
  (testing "unknown :substrate value throws with a clear message"
    (let [tmp (tmp-dir "rf2-template-bad-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":substrate must be one of"
                              (run-template! tmp "acme/my-app" :svelte))
            "unknown substrate is rejected")
        (finally
          (delete-recursively tmp))))))
