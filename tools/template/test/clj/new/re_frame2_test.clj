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
  the generated project's root path as a `java.io.File`. The 3-arity
  form preserves the historical default (no `:include-story?`); the
  4-arity form lets per-test cases opt into Story scaffolding."
  ([tmp project-name substrate]
   (run-template! tmp project-name substrate nil))
  ([tmp project-name substrate include-story?]
   (let [dir-str   (.toString ^java.nio.file.Path tmp)
         proj-dir  (.getPath (io/file dir-str
                                      (tmpl/project-name project-name)))
         args      (cond-> []
                     substrate              (into [:substrate substrate])
                     (some? include-story?) (into [:include-story? include-story?]))]
     ;; clj-new's ->files reads *dir* (via project-dir) to decide where
     ;; to land the generated tree. Rebind it to our temp directory for
     ;; the duration of the template's main fn.
     (binding [tmpl/*dir* proj-dir]
       (apply rft/re-frame2 project-name args))
     (io/file proj-dir))))

(defn- read-edn [^java.io.File f]
  (edn/read-string (slurp f)))

(def ^:private clojure-cli-available?
  ;; Probe `clojure --help` once per JVM. The probe spawns a process
  ;; and blocks on its exit code (~hundreds of ms on Windows where
  ;; the `clojure` PowerShell shim re-launches a JVM); doing it per
  ;; substrate inflated suite time noticeably.
  (delay
    (try
      (let [pb (ProcessBuilder. ^java.util.List ["clojure" "--help"])]
        (.redirectErrorStream pb true)
        (let [p (.start pb)
              ok? (.waitFor p)]
          (zero? ok?)))
      (catch Throwable _ false))))

(def ^:private deps-resolve-enabled?
  ;; The per-substrate `clojure -P` smoke is gated behind an opt-in
  ;; env var. Rationale: the generated app pins re-frame2 to
  ;; v0.0.1.alpha (see VERSION at the repo root). Until the alpha
  ;; artefacts are actually published to Clojars, every `clojure -P`
  ;; invocation fails with "Could not find artifact day8/re-frame2"
  ;; and is treated as a known-skip — buying us nothing while costing
  ;; the dominant share of suite wall-time (~2s × 3 substrates on a
  ;; cold cache).
  ;;
  ;; Local fast loop:   default off — static shape checks above still
  ;;                    cover the template contract.
  ;; CI / release lane: set RF2_TEMPLATE_DEPS_RESOLVE=1 to re-enable.
  ;;                    Once an alpha publish lands, the smoke flips
  ;;                    from known-skip to a real signal automatically.
  (delay
    (= "1" (System/getenv "RF2_TEMPLATE_DEPS_RESOLVE"))))

(defn- run-clojure-P
  "Run `clojure -P` in the given directory. Returns {:exit n :out s},
   `:skipped` if the smoke is gated off, or nil if the clojure CLI is
   unavailable."
  [^java.io.File dir]
  (cond
    (not @deps-resolve-enabled?) :skipped
    (not @clojure-cli-available?) nil
    :else
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

        ;; -- Security baseline (rf2-sh3l8) --
        ;;
        ;; The generated index.html must carry a strict default-safe
        ;; Content-Security-Policy meta tag, and the README must
        ;; document a "Production hardening" section covering
        ;; server-side headers, nosniff, and Referrer-Policy. Together
        ;; these guarantee that an app deployed unchanged from the
        ;; scaffold has a browser-enforced mitigation layer, and that
        ;; the operator knows how to graduate the meta-tag fall-back to
        ;; real response headers in production.
        (let [index-text  (slurp (io/file root "resources/public/index.html"))
              readme-text (slurp (io/file root "README.md"))]
          (is (.contains index-text "Content-Security-Policy")
              "index.html ships a CSP meta tag")
          (is (.contains index-text "default-src 'self'")
              "index.html CSP uses default-src 'self'")
          (is (.contains index-text "frame-ancestors 'none'")
              "index.html CSP forbids framing (anti-clickjacking)")
          (is (.contains index-text "object-src 'none'")
              "index.html CSP forbids plugin objects")
          (is (.contains index-text "data-rf-causa-host")
              "index.html provides Causa's default true-inline layout host")
          (is (.contains readme-text "Production hardening")
              "README documents Production hardening")
          (is (.contains readme-text "X-Content-Type-Options")
              "README covers nosniff header")
          (is (.contains readme-text "Referrer-Policy")
              "README covers Referrer-Policy header"))

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
        ;;
        ;; Gating: the smoke is opt-in via RF2_TEMPLATE_DEPS_RESOLVE=1
        ;; (default off for the local fast loop). See `run-clojure-P`
        ;; above for rationale.
        (let [result (run-clojure-P root)]
          (cond
            (= :skipped result)
            nil  ;; gated off — fast-loop path, static shape checks above cover the contract

            (nil? result)
            nil  ;; clojure CLI unavailable on PATH — nothing to assert

            :else
            (let [{:keys [exit out]} result]
              (cond
                (zero? exit)
                (is true "`clojure -P` succeeded — alpha artefacts are publicly resolvable")

                (re-find #"Could not find artifact day8(:|/)re-frame2" out)
                ;; Silent-on-success (rf2-try1x): the expected-pre-alpha
                ;; case is a no-op. Static shape checks above still cover
                ;; the template contract.
                nil

                :else
                (is (zero? exit)
                    (str "`clojure -P` exited " exit " for " substrate
                         " generated app. Output:\n" out)))))))
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

;; --- :include-story? flag (rf2-t009p) ------------------------------------

(deftest default-path-emits-no-story-files-test
  (testing "default path (no :include-story?) does not emit stories.cljs
            and does not pull in the day8/re-frame2-story coord"
    (let [tmp (tmp-dir "rf2-template-no-story-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent)]
          (is (not (file-exists? root "src/acme/my_app/stories.cljs"))
              "stories.cljs is NOT emitted on the default path")
          (let [deps    (read-edn (io/file root "deps.edn"))
                pkg-txt (slurp (io/file root "package.json"))]
            (is (not (contains? (:deps deps) 'day8/re-frame2-story))
                "deps.edn does NOT reference day8/re-frame2-story on default path")
            (is (not (.contains pkg-txt "\"story\":"))
                "package.json does NOT carry a `story` npm script on default path")
            (is (not (.contains pkg-txt "\"qrcode-generator\""))
                "package.json does NOT carry the qrcode-generator npm dep
                 on the default path (Story-only)"))
          ;; The default-path core.cljs should still be the simple one —
          ;; no Story require, no hash-routing surface.
          (let [core-text (slurp (io/file root "src/acme/my_app/core.cljs"))]
            (is (not (.contains core-text "re-frame.story"))
                "default-path core.cljs does NOT require re-frame.story")
            (is (not (.contains core-text "#/stories"))
                "default-path core.cljs has no hash-routing scaffold")))
        (finally
          (delete-recursively tmp))))))

(deftest include-story-true-reagent-test
  (testing ":include-story? true on Reagent emits stories.cljs +
            the with-stories core variant, and wires the story coord"
    (let [tmp (tmp-dir "rf2-template-with-story-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent true)]
          ;; -- The Story scaffold lands --
          (is (file-exists? root "src/acme/my_app/stories.cljs")
              "stories.cljs is emitted under :include-story? true")
          ;; -- Story coord + npm script are wired --
          (let [deps    (read-edn (io/file root "deps.edn"))
                pkg-txt (slurp (io/file root "package.json"))]
            (is (contains? (:deps deps) 'day8/re-frame2-story)
                "deps.edn references day8/re-frame2-story")
            (is (= "0.0.1.alpha"
                   (get-in deps [:deps 'day8/re-frame2-story :mvn/version]))
                "story coord lockstep with the core version")
            (is (.contains pkg-txt "\"story\":")
                "package.json declares a `story` npm script")
            (is (.contains pkg-txt "\"qrcode-generator\"")
                "package.json declares the qrcode-generator npm dep
                 (Story's only direct npm dependency — re-frame.story.qr
                 wraps it for the share-canvas QR codes)"))
          ;; -- core.cljs is the hash-routing with-stories variant --
          (let [core-text (slurp (io/file root "src/acme/my_app/core.cljs"))]
            (is (.contains core-text "re-frame.story")
                "with-stories core.cljs requires re-frame.story")
            (is (.contains core-text "mount-shell!")
                "with-stories core.cljs mounts the Story shell")
            (is (.contains core-text "#/stories")
                "with-stories core.cljs routes #/stories to the shell")
            (is (.contains core-text "acme.my-app.stories")
                "with-stories core.cljs requires the stories ns so its
                 reg-* calls fire at boot"))
          ;; -- stories.cljs uses the four shipped reg-* macros and
          ;;    references the template's existing event/sub/view ids --
          (let [stories-text (slurp (io/file root "src/acme/my_app/stories.cljs"))]
            (is (.contains stories-text "story/reg-story")
                "stories.cljs uses reg-story")
            (is (.contains stories-text "story/reg-variant")
                "stories.cljs uses reg-variant")
            (is (.contains stories-text "story/reg-tag")
                "stories.cljs uses reg-tag")
            (is (.contains stories-text "story/reg-workspace")
                "stories.cljs uses reg-workspace")
            (is (.contains stories-text ":counter/initialise")
                "stories.cljs references the template's :counter/initialise event")
            (is (.contains stories-text ":counter/increment")
                "stories.cljs references the template's :counter/increment event")
            (is (.contains stories-text ":counter/value")
                "stories.cljs's :rf.assert/path-equals targets the
                 template's :counter/value app-db slot")
            (is (.contains stories-text ":acme.my-app.views/counter-app")
                "stories.cljs references the template's view by namespaced
                 id (Story renders by id, not by symbol)")))
        (finally
          (delete-recursively tmp))))))

(deftest include-story-non-reagent-rejected-test
  (testing ":include-story? true is rejected for non-Reagent substrates
            in v1 — UIx + Helix follow once Story's adapter coverage
            matches Reagent's"
    (let [tmp (tmp-dir "rf2-template-story-uix-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":include-story\? is Reagent-only"
                              (run-template! tmp "acme/my-app" :uix true))
            ":include-story? + :uix is rejected at the entry-fn")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":include-story\? is Reagent-only"
                              (run-template! tmp "acme/my-app" :helix true))
            ":include-story? + :helix is rejected at the entry-fn")
        (finally
          (delete-recursively tmp))))))

(deftest invalid-include-story-rejected-test
  (testing "non-boolean :include-story? value throws with a clear message"
    (let [tmp (tmp-dir "rf2-template-story-bad-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":include-story\? must be true or false"
                              (run-template! tmp "acme/my-app" :reagent "yes"))
            "non-boolean :include-story? is rejected")
        (finally
          (delete-recursively tmp))))))
