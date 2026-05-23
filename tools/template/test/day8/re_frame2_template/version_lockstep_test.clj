(ns day8.re-frame2-template.version-lockstep-test
  "Pin-lockstep guard for the template's inline version literals
  (rf2-0kcsu; deps-new port for rf2-c2770).

  Principle P5 (tools/template/spec/Principles.md) declares that the
  template's three pin literals — `:rf2-version`, `:shadow-version`,
  `:react-version` — are bumped in lockstep with their external sources
  of truth:

    - `:rf2-version`    ↔ repo-root `VERSION`
    - `:shadow-version` ↔ `implementation/package.json` shadow-cljs
    - `:react-version`  ↔ `implementation/package.json` react (and react-dom)

  The package.json reader searches both `:dependencies` and
  `:devDependencies` (first hit wins) so the guard doesn't false-fail
  if the impl tree ever relocates a pin between the two sections.

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
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively repo-root template-resource-dir]]
            [org.corfield.new :as deps-new]))

;; --- Helpers ---------------------------------------------------------------
;;
;; repo-root / template-resource-dir / tmp-dir / delete-recursively live
;; in the shared `test-support` ns (rf2-5v619, D1). repo-root anchors on
;; `implementation/core/src/re_frame` — the same repo root that holds
;; VERSION + implementation/package.json read below.

;; --- Source-of-truth readers --------------------------------------------

(defn- read-version-file
  "Read repo-root `VERSION` and return its trimmed contents (e.g.
  `\"0.0.1.alpha\"`). Throws if missing."
  []
  (let [f (io/file (repo-root) "VERSION")]
    (when-not (.isFile f)
      (throw (ex-info "VERSION file missing at repo root" {:file (.getPath f)})))
    (string/trim (slurp f))))

(defn- pin-from-json-text
  "Pull the version string for `pkg` out of a chunk of package.json text
  (`\"pkg\": \"value\"` → `\"value\"`). Returns nil when absent. Shared
  by both the source-of-truth reader below and the emitted-package.json
  `extract-pin` — one regex shape, written once."
  [text pkg]
  (let [pin-re (re-pattern (str "\"" pkg "\":\\s*\"([^\"]+)\""))]
    (some-> (re-find pin-re text) second)))

(defn- read-package-json-pin
  "Read a pin for `pkg` (e.g. `\"react\"`) from
  `implementation/package.json`, searching `:dependencies` AND
  `:devDependencies` (first hit wins). Returns the pin string (e.g.
  `\"19.2.0\"`).

  Searching both sections decouples this guard from an incidental
  layout choice in the impl package.json: today react / react-dom /
  shadow-cljs sit in `:devDependencies` (it is a test target, not a
  shipped lib), but if any of them ever moves to `:dependencies` the
  guard keeps working rather than false-failing the template suite for
  a reason unrelated to the template (rf2-ee38b.23 / completeness +
  correctness P3).

  We deliberately use a simple regex parse rather than dragging in a
  JSON library — the template test artefact has no JSON dep today, and
  the package.json shape is stable enough that a regex (the section
  body, then `\"pkg\": \"value\"` inside it) reads simply and fails
  loudly on shape drift."
  [pkg]
  (let [text     (slurp (io/file (repo-root) "implementation/package.json"))
        section  (fn [name]
                   ;; Find `"<name>": { ... }`; the closing brace ends at
                   ;; the first `}` after the section name.
                   (some-> (re-find (re-pattern (str "\"" name "\":\\s*\\{([^}]*)\\}"))
                                    text)
                           second))
        pin      (some #(some-> (section %) (pin-from-json-text pkg))
                       ["dependencies" "devDependencies"])]
    (when-not pin
      (throw (ex-info (str "Couldn't find pin for " pkg
                           " in :dependencies or :devDependencies of "
                           "implementation/package.json")
                      {:pkg pkg})))
    pin))

;; --- Template literal extraction ----------------------------------------
;;
;; The `data-fn` assembles the substitution data map. The three pin
;; literals are static — they don't depend on caller args — so we can
;; recover them by emitting a tmp app and reading the substituted
;; package.json + deps.edn. This tests the literals as actually-consumed
;; (the same value that flows into a generated app), not the source
;; string parsed out of the .clj.

(defn- emit-reagent! [tmp]
  (let [dir-str  (.toString ^java.nio.file.Path tmp)
        proj-dir (io/file dir-str "my-app")
        opts     {:template   'day8/re-frame2-template
                  :name       'acme/my-app
                  :target-dir (.getCanonicalPath proj-dir)
                  :src-dirs   [(template-resource-dir)]
                  :overwrite  :delete
                  :substrate  :reagent}]
    (deps-new/create opts)
    proj-dir))

(defn- extract-pin
  "Pull `\"pkg\": \"value\"` out of the emitted package.json text.
  Thin wrapper over the shared `pin-from-json-text`."
  [pj-text pkg]
  (pin-from-json-text pj-text pkg))

(defn- extract-rf2-version
  "Pull `'day8/re-frame2 {:mvn/version \"...\"}` out of the emitted
  deps.edn text."
  [deps-text]
  (let [m (re-find #"day8/re-frame2\s+\{:mvn/version\s+\"([^\"]+)\"\}" deps-text)]
    (some-> m second)))

;; --- The lockstep tests -------------------------------------------------

(deftest react-version-lockstep
  (testing "Template's :react-version literal matches implementation/package.json"
    (let [pkg-react     (read-package-json-pin "react")
          pkg-react-dom (read-package-json-pin "react-dom")
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
                   "tools/template/src/day8/re_frame2_template/hooks.clj."))
          (is (= pkg-react-dom tpl-react-dom)
              (str "Template react-dom pin (" tpl-react-dom ") must match "
                   "implementation/package.json :react-dom (" pkg-react-dom ")")))
        (finally
          (delete-recursively tmp))))))

(deftest shadow-version-lockstep
  (testing "Template's :shadow-version literal matches implementation/package.json"
    (let [pkg-shadow (read-package-json-pin "shadow-cljs")
          tmp        (tmp-dir "rf2-template-lockstep-shadow-")]
      (try
        (let [root       (emit-reagent! tmp)
              pj-text    (slurp (io/file root "package.json"))
              tpl-shadow (extract-pin pj-text "shadow-cljs")]
          (is (= pkg-shadow tpl-shadow)
              (str "Template :shadow-version (" tpl-shadow ") must match "
                   "implementation/package.json :shadow-cljs (" pkg-shadow ") — "
                   "P5 lockstep. Bump :shadow-version in "
                   "tools/template/src/day8/re_frame2_template/hooks.clj.")))
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
                   "tools/template/src/day8/re_frame2_template/hooks.clj.")))
        (finally
          (delete-recursively tmp))))))
