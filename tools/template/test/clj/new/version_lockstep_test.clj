(ns clj.new.version-lockstep-test
  "Pin-lockstep guard for the template's inline version literals
  (rf2-0kcsu).

  Principle P5 (tools/template/spec/Principles.md) declares that the
  template's three pin literals — `:rf2-version`, `:shadow-version`,
  `:react-version` — are bumped in lockstep with their external sources
  of truth:

    - `:rf2-version`    ↔ repo-root `VERSION`
    - `:shadow-version` ↔ `implementation/package.json` :devDependencies/shadow-cljs
    - `:react-version`  ↔ `implementation/package.json` :devDependencies/react (and react-dom)

  History (rf2-8v20r): the template literal `:react-version` drifted to
  `18.3.1` while `implementation/package.json` had moved to `19.2.0`.
  Doctrine was right; the literal silently drifted with no automated
  check. This test reads both sources of truth on disk and asserts the
  entry-fn literals match, so the next bump can't drift silently.

  The test runs free (JVM, no shadow), under the standard
  `clojure -M:test` invocation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clj.new.re-frame2 :as rft]
            [clj.new.templates :as tmpl]))

;; --- Repo-root discovery (same pattern as template_emission_test) -------

(defn- repo-root
  "Absolute path of the repo root. The template test JVM is launched
  from `tools/template/` (clein default), but a manual `clojure -X:test`
  may launch from the repo root — walk up from `user.dir` until we find
  a sibling `implementation/package.json`."
  []
  (let [cwd (io/file (System/getProperty "user.dir"))]
    (loop [d cwd]
      (cond
        (nil? d)
        (throw (ex-info "Couldn't locate repo root (no implementation/package.json above cwd)"
                        {:cwd cwd}))

        (.isFile (io/file d "implementation/package.json"))
        d

        :else
        (recur (.getParentFile d))))))

;; --- Source-of-truth readers --------------------------------------------

(defn- read-version-file
  "Read repo-root `VERSION` and return its trimmed contents (e.g.
  `\"0.0.1.alpha\"`). Throws if missing."
  []
  (let [f (io/file (repo-root) "VERSION")]
    (when-not (.isFile f)
      (throw (ex-info "VERSION file missing at repo root" {:file (.getPath f)})))
    (string/trim (slurp f))))

(defn- read-package-json-pin
  "Read a pin from `implementation/package.json`. `section` is one of
  `:devDependencies` / `:dependencies`; `pkg` is the package name string
  (e.g. `\"react\"`). Returns the pin string (e.g. `\"19.2.0\"`).

  We deliberately use a simple regex parse rather than dragging in a
  JSON library — the template test artefact has no JSON dep today, and
  the package.json shape is stable enough that a regex (looking for
  `\"pkg\": \"value\"` inside the section) reads simply and fails loudly
  on shape drift."
  [section pkg]
  (let [text (slurp (io/file (repo-root) "implementation/package.json"))
        ;; Find the section (`"devDependencies": { ... }`). The closing
        ;; brace ends at the first top-level `}` after the section name.
        section-name (case section
                       :devDependencies "devDependencies"
                       :dependencies    "dependencies"
                       (throw (ex-info "Unknown section" {:section section})))
        section-re   (re-pattern (str "\"" section-name "\":\\s*\\{([^}]*)\\}"))
        section-body (some-> (re-find section-re text) second)
        _            (when-not section-body
                       (throw (ex-info (str "Couldn't find :" section-name " section in implementation/package.json")
                                       {:section section-name})))
        pin-re       (re-pattern (str "\"" pkg "\":\\s*\"([^\"]+)\""))
        pin          (some-> (re-find pin-re section-body) second)]
    (when-not pin
      (throw (ex-info (str "Couldn't find pin for " pkg " in :" section-name)
                      {:pkg pkg :section section-name})))
    pin))

;; --- Template literal extraction ----------------------------------------
;;
;; The entry fn assembles the substitution data map inside the
;; `(let [data ...])` binding. The three pin literals are static — they
;; don't depend on caller args — so we can recover them by emitting a
;; tmp app and reading the substituted package.json + deps.edn. This
;; tests the literals as actually-consumed (the same value that flows
;; into a generated app), not the source string parsed out of the .clj.

(defn- tmp-dir [prefix]
  (let [f (java.nio.file.Files/createTempDirectory
            prefix
            (into-array java.nio.file.attribute.FileAttribute []))]
    (.toAbsolutePath f)))

(defn- delete-recursively [^java.nio.file.Path path]
  (when (java.nio.file.Files/exists path (into-array java.nio.file.LinkOption []))
    (with-open [stream (java.nio.file.Files/walk
                         path
                         (into-array java.nio.file.FileVisitOption []))]
      (->> stream
           .iterator
           iterator-seq
           reverse
           (run! #(java.nio.file.Files/deleteIfExists ^java.nio.file.Path %))))))

(defn- emit-reagent! [tmp]
  (let [dir-str  (.toString ^java.nio.file.Path tmp)
        proj-dir (.getPath (io/file dir-str (tmpl/project-name "acme/my-app")))]
    (binding [tmpl/*dir* proj-dir]
      (rft/re-frame2 "acme/my-app" :substrate :reagent))
    (io/file proj-dir)))

(defn- extract-pin
  "Pull `\"pkg\": \"value\"` out of the emitted package.json text."
  [pj-text pkg]
  (let [pin-re (re-pattern (str "\"" pkg "\":\\s*\"([^\"]+)\""))]
    (some-> (re-find pin-re pj-text) second)))

(defn- extract-rf2-version
  "Pull `'day8/re-frame2 {:mvn/version \"...\"}` out of the emitted
  deps.edn text."
  [deps-text]
  (let [m (re-find #"day8/re-frame2\s+\{:mvn/version\s+\"([^\"]+)\"\}" deps-text)]
    (some-> m second)))

;; --- The lockstep tests -------------------------------------------------

(deftest react-version-lockstep
  (testing "Template's :react-version literal matches implementation/package.json"
    (let [pkg-react     (read-package-json-pin :devDependencies "react")
          pkg-react-dom (read-package-json-pin :devDependencies "react-dom")
          tmp           (tmp-dir "rf2-template-lockstep-react-")]
      (try
        (let [root      (emit-reagent! tmp)
              pj-text   (slurp (io/file root "package.json"))
              tpl-react     (extract-pin pj-text "react")
              tpl-react-dom (extract-pin pj-text "react-dom")]
          ;; impl tree must keep react / react-dom in lockstep with
          ;; each other; if they ever diverge, the rationale should
          ;; be in DESIGN-RATIONALE and this test updates accordingly.
          (is (= pkg-react pkg-react-dom)
              "implementation/package.json pins react and react-dom to the same version")
          (is (= pkg-react tpl-react)
              (str "Template :react-version (" tpl-react ") must match "
                   "implementation/package.json :react (" pkg-react ") — "
                   "P5 lockstep. Bump :react-version in "
                   "tools/template/src/clj/new/re_frame2.clj."))
          (is (= pkg-react-dom tpl-react-dom)
              (str "Template react-dom pin (" tpl-react-dom ") must match "
                   "implementation/package.json :react-dom (" pkg-react-dom ")")))
        (finally
          (delete-recursively tmp))))))

(deftest shadow-version-lockstep
  (testing "Template's :shadow-version literal matches implementation/package.json"
    (let [pkg-shadow (read-package-json-pin :devDependencies "shadow-cljs")
          tmp        (tmp-dir "rf2-template-lockstep-shadow-")]
      (try
        (let [root       (emit-reagent! tmp)
              pj-text    (slurp (io/file root "package.json"))
              tpl-shadow (extract-pin pj-text "shadow-cljs")]
          (is (= pkg-shadow tpl-shadow)
              (str "Template :shadow-version (" tpl-shadow ") must match "
                   "implementation/package.json :shadow-cljs (" pkg-shadow ") — "
                   "P5 lockstep. Bump :shadow-version in "
                   "tools/template/src/clj/new/re_frame2.clj.")))
        (finally
          (delete-recursively tmp))))))

(deftest rf2-version-lockstep
  (testing "Template's :rf2-version literal matches repo-root VERSION"
    (let [version-file (read-version-file)
          tmp          (tmp-dir "rf2-template-lockstep-rf2-")]
      (try
        (let [root        (emit-reagent! tmp)
              deps-text   (slurp (io/file root "deps.edn"))
              tpl-rf2     (extract-rf2-version deps-text)]
          (is (= version-file tpl-rf2)
              (str "Template :rf2-version (" tpl-rf2 ") must match "
                   "repo-root VERSION (" version-file ") — P5 lockstep. "
                   "Bump :rf2-version in "
                   "tools/template/src/clj/new/re_frame2.clj.")))
        (finally
          (delete-recursively tmp))))))
