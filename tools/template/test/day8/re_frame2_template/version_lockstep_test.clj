(ns day8.re-frame2-template.version-lockstep-test
  "Pin-lockstep guard for the template's inline version literals.

  Principle P5 (tools/template/spec/Principles.md) declares that the
  template's pin literals are bumped in lockstep with their external
  sources of truth in the implementation reference tree. The guarded
  pins:

    - `:rf2-version`    ↔ repo-root `VERSION`
    - `:shadow-version` ↔ `implementation/package.json` shadow-cljs
    - `:react-version`  ↔ `implementation/package.json` react (and react-dom)

  The substrate-lib + clojure(script) pins are guarded too. They need an
  explicit guard because the emitted-app smoke compiles against the
  TEMPLATE's own pin, never the impl tree's, so a drift between them would
  otherwise be invisible to every gate:

    - reagent/reagent          ↔ `implementation/core/deps.edn` :deps
    - com.pitch/uix.core       ↔ `implementation/adapters/uix/deps.edn` :deps
    - com.pitch/uix.dom        ↔ `implementation/adapters/uix/deps.edn`
                                  :test alias :extra-deps (the shipped
                                  uix adapter does NOT require uix.dom, so
                                  the impl pin lives in the test/testbed
                                  classpath; the template's :app needs it
                                  for its DOM mount)
    - lilactown/helix          ↔ `implementation/adapters/helix/deps.edn` :deps
    - org.clojure/clojure      ↔ `implementation/core/deps.edn` :deps
    - org.clojure/clojurescript ↔ `implementation/core/deps.edn` :deps

  Template-owned (NOT guarded — no single impl source-of-truth):
  cljfmt, clj-kondo/clj-kondo, org.clojure/tools.namespace. The impl
  tree pins none of these as deps.edn coords — clj-kondo is installed
  via a script in .github/workflows/lint.yml, cljfmt is not an impl dep,
  and tools.namespace is a template-only convenience for the generated
  `:shadow` alias. They are intentionally template-owned; bump them
  against upstream releases, not the impl tree. (If the impl tree ever
  grows a canonical pin for one of these, add it to the guarded set.)

  The package.json reader searches both `:dependencies` and
  `:devDependencies` (first hit wins) so the guard doesn't false-fail
  if the impl tree ever relocates a pin between the two sections.

  Without an automated check a template literal such as `:react-version`
  can silently drift away from `implementation/package.json`. This test
  reads both sources of truth on disk and asserts the entry-fn literals
  match, so a bump on one side can't drift silently from the other.

  The test runs free (JVM, no shadow), under the standard
  `clojure -M:test` invocation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [day8.re-frame2-template.test-support
             :refer [read-edn tmp-dir delete-recursively repo-root
                     run-template!]]))

;; --- Helpers ---------------------------------------------------------------
;;
;; repo-root / run-template! / tmp-dir / delete-recursively live in the
;; shared `test-support` ns. repo-root anchors on
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
  a reason unrelated to the template.

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

;; --- deps.edn :mvn/version readers --------------------------------------

(defn- mvn-pin-in
  "Pull the `:mvn/version` string for coord `sym` out of a deps-map
  fragment `m` (e.g. a `:deps` map or an alias's `:extra-deps` map).
  Returns nil when `sym` is absent or has no `:mvn/version` (a
  :local/root / :git coord)."
  [m sym]
  (get-in m [sym :mvn/version]))

(defn- read-impl-deps-pin
  "Read the `:mvn/version` for coord `sym` from the deps.edn at
  `rel-path` (relative to repo-root). Searches `:deps` first, then every
  alias's `:extra-deps` / `:replace-deps` (first hit wins). Throws with a
  loud message if the coord isn't found with an `:mvn/version` anywhere —
  a shape drift in the impl tree must fail this guard, not silently
  skip it."
  [rel-path sym]
  (let [deps  (read-edn (io/file (repo-root) rel-path))
        alias-maps (->> (vals (:aliases deps))
                        (mapcat (juxt :extra-deps :replace-deps))
                        (remove nil?))
        pin   (or (mvn-pin-in (:deps deps) sym)
                  (some #(mvn-pin-in % sym) alias-maps))]
    (when-not pin
      (throw (ex-info (str "Couldn't find an :mvn/version pin for " sym
                           " in :deps or any alias of " rel-path
                           " — impl-tree shape drift; the lockstep guard "
                           "can't read its source of truth.")
                      {:coord sym :deps-file rel-path})))
    pin))

;; --- Template literal extraction ----------------------------------------
;;
;; The `data-fn` assembles the substitution data map. The three pin
;; literals are static — they don't depend on caller args — so we can
;; recover them by emitting a tmp app and reading the substituted
;; package.json + deps.edn. This tests the literals as actually-consumed
;; (the same value that flows into a generated app), not the source
;; string parsed out of the .clj.
;;
;; Emission uses the shared `run-template!` (test-support) — the
;; pin literals are substrate-invariant, so the Reagent default path
;; recovers them. One harness drives every emission rather than a
;; per-test re-implementation of the `org.corfield.new/create` opts map.

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
        (let [root      (run-template! tmp "acme/my-app" :reagent)
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
        (let [root       (run-template! tmp "acme/my-app" :reagent)
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
        (let [root        (run-template! tmp "acme/my-app" :reagent)
              deps-text   (slurp (io/file root "deps.edn"))
              tpl-rf2     (extract-rf2-version deps-text)]
          (is (= version-file tpl-rf2)
              (str "Template :rf2-version (" tpl-rf2 ") must match "
                   "repo-root VERSION (" version-file ") — P5 lockstep. "
                   "Bump :rf2-version in "
                   "tools/template/src/day8/re_frame2_template/hooks.clj.")))
        (finally
          (delete-recursively tmp))))))

;; --- substrate + clojure(script) lockstep --------------------------------
;;
;; The emitted deps.edn is valid EDN, so we read the pin straight off the
;; parsed map rather than regexing the text. `emit-deps-pin` generates
;; the per-substrate scaffold once and pulls the `:mvn/version` for a
;; coord out of its `:deps`.

(defn- emit-deps-pin
  "Generate the `substrate` scaffold into `tmp`, read its emitted
  deps.edn, and return the `:mvn/version` string for coord `sym` (nil if
  absent). Caller owns the tmp lifecycle so a single emission can be
  reused for several coord assertions."
  [tmp substrate sym]
  (let [root (run-template! tmp "acme/my-app" substrate)
        deps (read-edn (io/file root "deps.edn"))]
    (get-in deps [:deps sym :mvn/version])))

(deftest substrate-lib-lockstep
  (testing "Template's substrate-lib pins match the implementation adapter tree"
    ;; reagent/reagent — impl source of truth is core/deps.edn :deps
    ;; (re-frame.views is Reagent-coupled, so reagent rides core).
    (let [impl-reagent (read-impl-deps-pin "implementation/core/deps.edn"
                                           'reagent/reagent)
          tmp          (tmp-dir "rf2-template-lockstep-reagent-")]
      (try
        (let [tpl-reagent (emit-deps-pin tmp :reagent 'reagent/reagent)]
          (is (= impl-reagent tpl-reagent)
              (str "Template reagent/reagent pin (" tpl-reagent ") must match "
                   "implementation/core/deps.edn (" impl-reagent ") — P5 "
                   "lockstep. Bump reagent in the _reagent/deps.edn "
                   "template resource.")))
        (finally (delete-recursively tmp))))

    ;; com.pitch/uix.core — impl source of truth is adapters/uix/deps.edn :deps.
    ;; com.pitch/uix.dom — the shipped uix adapter does NOT require uix.dom,
    ;; so the impl pin lives in the :test alias's :extra-deps;
    ;; the template's :app build needs it for the DOM mount, so the template
    ;; pins it in :deps. Both must ride the same impl pin.
    (let [impl-uix-core (read-impl-deps-pin "implementation/adapters/uix/deps.edn"
                                            'com.pitch/uix.core)
          impl-uix-dom  (read-impl-deps-pin "implementation/adapters/uix/deps.edn"
                                            'com.pitch/uix.dom)
          tmp           (tmp-dir "rf2-template-lockstep-uix-")]
      (try
        (let [root         (run-template! tmp "acme/my-app" :uix)
              tpl-deps     (read-edn (io/file root "deps.edn"))
              tpl-uix-core (get-in tpl-deps [:deps 'com.pitch/uix.core :mvn/version])
              tpl-uix-dom  (get-in tpl-deps [:deps 'com.pitch/uix.dom :mvn/version])]
          (is (= impl-uix-core tpl-uix-core)
              (str "Template com.pitch/uix.core pin (" tpl-uix-core ") must "
                   "match implementation/adapters/uix/deps.edn (" impl-uix-core
                   ") — P5 lockstep. Bump uix.core in the _uix/deps.edn "
                   "template resource."))
          (is (= impl-uix-dom tpl-uix-dom)
              (str "Template com.pitch/uix.dom pin (" tpl-uix-dom ") must "
                   "match implementation/adapters/uix/deps.edn :test alias ("
                   impl-uix-dom ") — P5 lockstep. Bump uix.dom in the "
                   "_uix/deps.edn template resource.")))
        (finally (delete-recursively tmp))))

    ;; lilactown/helix — impl source of truth is adapters/helix/deps.edn :deps.
    (let [impl-helix (read-impl-deps-pin "implementation/adapters/helix/deps.edn"
                                         'lilactown/helix)
          tmp        (tmp-dir "rf2-template-lockstep-helix-")]
      (try
        (let [tpl-helix (emit-deps-pin tmp :helix 'lilactown/helix)]
          (is (= impl-helix tpl-helix)
              (str "Template lilactown/helix pin (" tpl-helix ") must match "
                   "implementation/adapters/helix/deps.edn (" impl-helix ") — "
                   "P5 lockstep. Bump helix in the _helix/deps.edn template "
                   "resource.")))
        (finally (delete-recursively tmp))))))

(deftest clojure-version-lockstep
  (testing "Template's clojure + clojurescript pins match implementation/core/deps.edn"
    ;; Both pins ride the impl core deps.edn :deps. They are
    ;; substrate-invariant in the template (every _<substrate>/deps.edn
    ;; carries the same two lines), so the Reagent emission recovers them.
    (let [impl-clj  (read-impl-deps-pin "implementation/core/deps.edn"
                                        'org.clojure/clojure)
          impl-cljs (read-impl-deps-pin "implementation/core/deps.edn"
                                        'org.clojure/clojurescript)
          tmp       (tmp-dir "rf2-template-lockstep-clj-")]
      (try
        (let [root      (run-template! tmp "acme/my-app" :reagent)
              deps      (read-edn (io/file root "deps.edn"))
              tpl-clj   (get-in deps [:deps 'org.clojure/clojure :mvn/version])
              tpl-cljs  (get-in deps [:deps 'org.clojure/clojurescript :mvn/version])]
          (is (= impl-clj tpl-clj)
              (str "Template org.clojure/clojure pin (" tpl-clj ") must match "
                   "implementation/core/deps.edn (" impl-clj ") — P5 lockstep. "
                   "Bump clojure in every _<substrate>/deps.edn template "
                   "resource."))
          (is (= impl-cljs tpl-cljs)
              (str "Template org.clojure/clojurescript pin (" tpl-cljs ") must "
                   "match implementation/core/deps.edn (" impl-cljs ") — P5 "
                   "lockstep. Bump clojurescript in every _<substrate>/deps.edn "
                   "template resource.")))
        (finally (delete-recursively tmp))))))
