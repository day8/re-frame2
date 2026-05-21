(ns day8.re-frame2-template.template-test
  "JVM tests for the deps-new template body (rf2-c2770, rf2-dolpf §2.2-2.4).

   Strategy:

     1. Generate a tmp app via `org.corfield.new/create` for each
        substrate (Reagent / UIx / Helix). Driving the deps-new entry
        fn in-process exercises the same `data-fn` / `template-fn` /
        `post-process-fn` pipeline a shell-out `clojure -Tnew create`
        would — without spawning a JVM per substrate.
     2. Walk the generated tree and assert the expected file shape.
     3. Read the generated `deps.edn`, parse it as EDN, and assert the
        expected substrate-adapter coord is present.
     4. Assert the `:include-story?` flag branches:
          - default path emits no story files / coords
          - true on Reagent emits stories.cljs + with-stories core +
            day8/re-frame2-story coord + qrcode-generator npm dep
          - true on non-Reagent substrates throws with a clear message

   Mirrors the surface checks that previously lived in the clj-new test
   suite at `test/clj/new/re_frame2_test.clj` (removed in rf2-40vmd /
   §2.5 along with the clj-new template body)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template!]]))

;; --- Test helpers ----------------------------------------------------------
;;
;; tmp-dir / delete-recursively / template-resource-dir / run-template!
;; live in the shared `test-support` ns (rf2-5v619, D1).

(defn- read-edn [^java.io.File f]
  (edn/read-string (slurp f)))

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
   "lefthook.yml"
   ;; Baseline CI workflow (rf2-k2z79).
   ".github/workflows/ci.yml"
   "dev/user.clj"
   "dev/scratch.cljs"
   "resources/public/index.html"
   "resources/public/css/app.css"])

(def ^:private per-substrate-sources
  ;; Generated under src/<nested-dirs>/ and test/<nested-dirs>/. For
  ;; project-name "acme/my-app" deps-new produces nested-dirs
  ;; "acme/my_app".
  ["src/acme/my_app/core.cljs"
   "src/acme/my_app/events.cljs"
   "src/acme/my_app/schema.cljs"
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
          ;; The literal pin VALUE is owned by version_lockstep_test.clj
          ;; (reads repo-root VERSION on disk); asserting a hard-coded
          ;; "0.0.1.alpha" here would duplicate it and false-fail the
          ;; moment VERSION bumps (rf2-5v619, D3). Present-check only.
          (is (some? (get-in deps [:deps 'day8/re-frame2 :mvn/version]))
              "core coord carries an :mvn/version pin"))

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
          ;; Pin value owned by version_lockstep_test.clj (rf2-5v619, D3).
          (is (some? (get-in deps [:deps 'day8/re-frame2-causa :mvn/version]))
              "Causa coord carries an :mvn/version pin"))

        ;; -- Schemas coord in deps.edn (rf2-48mij) --
        (let [deps (read-edn (io/file root "deps.edn"))]
          (is (contains? (:deps deps) 'day8/re-frame2-schemas)
              "deps.edn references day8/re-frame2-schemas (best-practice
               whole-app-db schema needs the artefact on the classpath
               for CLJS validation to fire)")
          ;; Pin value owned by version_lockstep_test.clj (rf2-5v619, D3).
          (is (some? (get-in deps [:deps 'day8/re-frame2-schemas :mvn/version]))
              "schemas coord carries an :mvn/version pin"))

        ;; -- Best-practice surface in events.cljs + schema.cljs (rf2-48mij) --
        (let [events-text (slurp (io/file root "src/acme/my_app/events.cljs"))
              schema-text (slurp (io/file root "src/acme/my_app/schema.cljs"))]
          (is (.contains events-text "register-listener!")
              "events.cljs registers an error-sink trace listener
               (errors-are-events-too best-practice)")
          (is (.contains events-text "re-frame.trace.tooling")
              "events.cljs uses the re-frame.trace.tooling/register-listener!
               surface — CLJS-only (the rf/... alias is JVM-only,
               per rf2-qwm0a)")
          (is (.contains events-text "re-frame.schemas")
              "events.cljs side-effect-loads re-frame.schemas so Malli
               publishes into the late-bind hook table before any
               reg-app-schema runs")
          (is (.contains events-text "re-frame.schemas.malli")
              "events.cljs also loads the Malli adapter (without it the
               default validator soft-passes per Spec 010)")
          (is (.contains events-text ":rf.http/managed")
              "events.cljs ships the commented HTTP failure-matrix
               exemplar so users see the canonical call shape")
          (is (.contains events-text ":rf.http/http-5xx")
              "events.cljs's HTTP exemplar uses the closed
               :rf.http/* category set in :retry :on")
          (is (.contains schema-text "reg-app-schema")
              "schema.cljs registers a whole-app-db schema")
          (is (.contains schema-text "CounterDb")
              "schema.cljs ships the CounterDb Malli schema"))

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

        ;; -- Per-substrate README badge (rf2-sufwn) --
        ;;
        ;; The badge LINE varies by substrate, so it stays in the
        ;; per-substrate shape test. The substrate-INVARIANT README/CI/
        ;; security text moved to `root-content-test` below — it comes
        ;; from `root/` and was needlessly re-run 3× (rf2-5v619, L3).
        (let [readme-text (slurp (io/file root "README.md"))]
          (is (.contains readme-text
                         (case substrate
                           :reagent "substrate-Reagent"
                           :uix     "substrate-UIx"
                           :helix   "substrate-Helix"))
              "README ships the per-substrate badge")))
      (finally
        (delete-recursively tmp)))))

(deftest root-content-test
  (testing "substrate-invariant root/ content (README best-practice +
            naming + badges, baseline CI workflow, security baseline) —
            generated once. These files come from root/ and are
            substrate-agnostic, so re-running them per substrate (the
            old assert-shape! shape) was 3× redundant + mislayered
            (rf2-5v619, L3)."
    (let [tmp (tmp-dir "rf2-template-root-content-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent)]
          ;; -- README best-practice + naming sections (rf2-48mij) --
          (let [readme-text (slurp (io/file root "README.md"))]
            (is (.contains readme-text "Best practices baked into the scaffold")
                "README has a Best practices section")
            (is (.contains readme-text "Errors are events too")
                "README documents the errors-are-events-too posture")
            (is (.contains readme-text "Typed app-db boundaries")
                "README documents the typed-at-boundaries posture")
            (is (.contains readme-text "closed failure-category set")
                "README documents the closed :rf.http/* failure-category set")
            (is (.contains readme-text "Naming conventions")
                "README documents the naming-conventions rules")
            (is (.contains readme-text "spec/Conventions.md")
                "README links to spec/Conventions.md for the normative catalogue"))

          ;; -- README substrate-invariant badges (rf2-sufwn) --
          (let [readme-text (slurp (io/file root "README.md"))]
            (is (.contains readme-text "img.shields.io/badge/built")
                "README ships a 'built with re-frame2' badge")
            (is (.contains readme-text "License-MIT")
                "README ships a License badge"))

          ;; -- Baseline CI workflow (rf2-k2z79) --
          (let [ci-text (slurp (io/file root ".github/workflows/ci.yml"))]
            (is (.contains ci-text "name: ci")
                ".github/workflows/ci.yml declares the ci workflow")
            (is (.contains ci-text "node-version: '22'")
                "ci.yml pins Node 22 LTS")
            (is (.contains ci-text "java-version: '21'")
                "ci.yml pins JDK 21 (matches re-frame2 reference build)")
            (is (.contains ci-text "actions/checkout@")
                "ci.yml uses actions/checkout with a SHA pin")
            (is (.contains ci-text "actions/setup-java@")
                "ci.yml uses actions/setup-java with a SHA pin")
            (is (.contains ci-text "actions/setup-node@")
                "ci.yml uses actions/setup-node with a SHA pin")
            (is (.contains ci-text "DeLaGuardo/setup-clojure@")
                "ci.yml uses DeLaGuardo/setup-clojure with a SHA pin")
            (is (.contains ci-text "npm test")
                "ci.yml runs `npm test` (delegates to shadow-cljs :node-test
                 per the emitted package.json)")
            (is (.contains ci-text "# acme/my-app")
                "ci.yml header substitutes {{name}}"))

          ;; -- Security baseline (rf2-sh3l8) --
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
                "README covers Referrer-Policy header")))
        (finally
          (delete-recursively tmp))))))

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
                              #":rf\.error/template-substrate-must-be-one-of"
                              (run-template! tmp "acme/my-app" :svelte))
            "unknown substrate is rejected")
        (finally
          (delete-recursively tmp))))))

;; --- :include-story? flag (rf2-t009p / rf2-dolpf §2.4) -------------------

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
            ;; Pin value owned by version_lockstep_test.clj (rf2-5v619, D3).
            (is (some? (get-in deps [:deps 'day8/re-frame2-story :mvn/version]))
                "story coord carries an :mvn/version pin")
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
                              #":rf\.error/template-include-story-reagent-only"
                              (run-template! tmp "acme/my-app" :uix true))
            ":include-story? + :uix is rejected at the entry-fn")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-include-story-reagent-only"
                              (run-template! tmp "acme/my-app" :helix true))
            ":include-story? + :helix is rejected at the entry-fn")
        (finally
          (delete-recursively tmp))))))

(deftest invalid-include-story-rejected-test
  (testing "non-boolean :include-story? value throws with a clear message"
    (let [tmp (tmp-dir "rf2-template-story-bad-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-bad-include-story-flag"
                              (run-template! tmp "acme/my-app" :reagent "yes"))
            "non-boolean :include-story? is rejected")
        (finally
          (delete-recursively tmp))))))
