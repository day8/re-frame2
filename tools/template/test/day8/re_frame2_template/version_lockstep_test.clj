(ns day8.re-frame2-template.version-lockstep-test
  "Pin-lockstep guard (Principle P5, tools/template/spec/Principles.md): each
  version pin the template emits matches its source of truth here —
  `:rf2-version` the repo-root `VERSION`; the shadow-cljs, React and Story
  shell npm pins `implementation/package.json`; each substrate's view-library
  and clojure(script) pins the implementation deps.edn files. The emitted-app
  smoke compiles against the TEMPLATE's pins, never the implementation's, so
  a drift is invisible to every other gate.

  One pin outside the template rides along: the re-frame2-pair fixture's
  React, which tracks `implementation/package.json`. It lives here because
  this suite is the gate the classifier arms on an `implementation/package.json`
  change, and no fixture-side lane runs then."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [day8.re-frame2-template.hooks :as hooks]
            [day8.re-frame2-template.test-support
             :refer [read-edn tmp-dir delete-recursively repo-root
                     run-template!]]))

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
  (`\"pkg\": \"value\"` → `\"value\"`). Returns nil when absent. Reads both
  the source of truth below and the emitted package.json — one regex
  shape, written once."
  [text pkg]
  (let [pin-re (re-pattern (str "\"" pkg "\":\\s*\"([^\"]+)\""))]
    (some-> (re-find pin-re text) second)))

(defn- read-package-json-pin
  "The pin for `pkg` in a repo-relative package.json (default
  `implementation/package.json`), from `dependencies` or `devDependencies`,
  so a pin moving between the two sections does not false-fail this suite.
  A regex rather than a JSON parser: this artefact has no JSON dependency,
  and the shape is stable enough to fail loudly on drift."
  ([pkg] (read-package-json-pin "implementation/package.json" pkg))
  ([rel-path pkg]
   (let [text     (slurp (io/file (repo-root) rel-path))
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
                            rel-path)
                       {:pkg pkg :package-json rel-path})))
     pin)))

(defn- read-impl-deps-pin
  "The `:mvn/version` for `sym` in the deps.edn at `rel-path`, from `:deps`
  or any alias's `:extra-deps` / `:replace-deps`. Throws when absent: a shape
  drift in the implementation tree must fail this guard, not skip it."
  [rel-path sym]
  (let [deps       (read-edn (io/file (repo-root) rel-path))
        alias-maps (mapcat (juxt :extra-deps :replace-deps) (vals (:aliases deps)))
        pin        (some #(get-in % [sym :mvn/version]) (cons (:deps deps) alias-maps))]
    (when-not pin
      (throw (ex-info (str "Couldn't find an :mvn/version pin for " sym
                           " in :deps or any alias of " rel-path
                           " — impl-tree shape drift; the lockstep guard "
                           "can't read its source of truth.")
                      {:coord sym :deps-file rel-path})))
    pin))

;; --- The emitted literals ------------------------------------------------
;;
;; The pins are read off a real emission — the values a generated app gets —
;; not off `hooks.clj`'s source. They are substrate-invariant, so one Reagent
;; emission recovers them all.

(defn- extract-rf2-version
  "Pull `'day8/re-frame2 {:mvn/version \"...\"}` out of the emitted
  deps.edn text."
  [deps-text]
  (let [m (re-find #"day8/re-frame2\s+\{:mvn/version\s+\"([^\"]+)\"\}" deps-text)]
    (some-> m second)))

;; --- The lockstep tests -------------------------------------------------

(deftest pair-fixture-react-lockstep
  ;; Not a template literal — see the ns docstring for why it rides here.
  (testing "re-frame2-pair fixture's react / react-dom match implementation/package.json"
    (let [fixture "skills/re-frame2-pair/tests/fixture/package.json"]
      (doseq [pkg ["react" "react-dom"]]
        (let [impl-pin    (read-package-json-pin pkg)
              fixture-pin (read-package-json-pin fixture pkg)]
          (is (= impl-pin fixture-pin)
              (str fixture " " pkg " (" fixture-pin ") must match "
                   "implementation/package.json (" impl-pin ") — the fixture "
                   "TRACKS the project's React. Bump its "
                   "devDependencies in the same change.")))))))

(defn- base-version
  "Strip a leading npm range operator so a range in
  `implementation/package.json` (`elkjs` is `^0.11.1` there) compares with
  the exact pin the template emits. The lockfile resolves that range to the
  same version."
  [pin]
  (string/replace-first pin #"^[~^=]+" ""))

(deftest template-pin-literals-lockstep
  (let [tmp (tmp-dir "rf2-template-lockstep-pins-")]
    (try
      (let [root      (run-template! tmp "acme/my-app" :reagent)
            pj-text   (slurp (io/file root "package.json"))
            deps-text (slurp (io/file root "deps.edn"))]
        (testing "Template's :react-version literal matches implementation/package.json"
          (let [pkg-react     (read-package-json-pin "react")
                pkg-react-dom (read-package-json-pin "react-dom")
                tpl-react     (pin-from-json-text pj-text "react")
                tpl-react-dom (pin-from-json-text pj-text "react-dom")]
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
                     "implementation/package.json :react-dom (" pkg-react-dom ")"))))
        (testing "Template's :shadow-version literal matches implementation/package.json"
          (let [pkg-shadow (read-package-json-pin "shadow-cljs")
                tpl-shadow (pin-from-json-text pj-text "shadow-cljs")]
            (is (= pkg-shadow tpl-shadow)
                (str "Template :shadow-version (" tpl-shadow ") must match "
                     "implementation/package.json :shadow-cljs (" pkg-shadow ") — "
                     "P5 lockstep. Bump :shadow-version in "
                     "tools/template/src/day8/re_frame2_template/hooks.clj."))))
        (testing "The two npm packages Story's shell needs match implementation/package.json"
          (doseq [[pkg literal] [["@xyflow/react" ":xyflow-version"]
                                 ["elkjs"         ":elkjs-version"]]]
            (let [impl-pin (read-package-json-pin pkg)
                  tpl-pin  (pin-from-json-text pj-text pkg)]
              (is (= (base-version impl-pin) tpl-pin)
                  (str "Template " literal " (" tpl-pin ") must match "
                       "implementation/package.json " pkg " (" impl-pin ") — P5 "
                       "lockstep. Bump " literal " in "
                       "tools/template/src/day8/re_frame2_template/hooks.clj.")))))
        (testing "Template's :rf2-version literal matches repo-root VERSION"
          (let [version-file (read-version-file)
                tpl-rf2      (extract-rf2-version deps-text)]
            (is (= version-file tpl-rf2)
                (str "Template :rf2-version (" tpl-rf2 ") must match "
                     "repo-root VERSION (" version-file ") — P5 lockstep. "
                     "Bump :rf2-version in "
                     "tools/template/src/day8/re_frame2_template/hooks.clj.")))))
      (finally
        (delete-recursively tmp)))))

;; --- substrate + clojure(script) lockstep --------------------------------
;;
;; Each `_<substrate>/deps.edn` carries its OWN literal copy of the clojure
;; and clojurescript pins and of its view library's pin, so every
;; substrate's emission is read: one copy drifting alone is exactly the
;; drift this guards. The substrates come off `hooks.clj`'s
;; `substrate-registry` (the single roster), so one added there is graded
;; here with no edit to this file.

(def ^:private substrate-lib-sources
  "Substrate -> its view library and the implementation deps.edn pinning it.
  Stock Reagent is a dep of the Reagent adapter artefact only: core stays
  Reagent-free because re-frame.views late-binds via the
  `:adapter/current-component` hook. com.pitch/uix.dom has no row, and that
  is the point rather than an omission: the emitted app mounts through
  `rf.adapter.uix/client-root` + `render!`, so uix.dom is not on its
  classpath and there is nothing to keep in step. `template_test.clj`
  carries the absence assertion, in `retired-coords`."
  {:reagent ['reagent/reagent    "implementation/adapters/reagent/deps.edn"]
   :uix     ['com.pitch/uix.core "implementation/adapters/uix/deps.edn"]})

(deftest substrate-deps-lockstep
  (testing "Every substrate's view-library, clojure and clojurescript pins match the implementation tree"
    (let [core-deps "implementation/core/deps.edn"
          clj-pins  (for [sym '[org.clojure/clojure org.clojure/clojurescript]]
                      [sym core-deps (read-impl-deps-pin core-deps sym)])]
      (doseq [substrate (sort (keys @#'hooks/substrate-registry))]
        (let [tmp (tmp-dir (str "rf2-template-lockstep-" (name substrate) "-"))]
          (try
            (let [deps (read-edn (io/file (run-template! tmp "acme/my-app" substrate)
                                          "deps.edn"))
                  lib  (when-let [[sym src] (substrate-lib-sources substrate)]
                         [sym src (read-impl-deps-pin src sym)])]
              (doseq [[sym src impl-pin] (cons lib clj-pins)
                      :when sym
                      :let [tpl-pin (get-in deps [:deps sym :mvn/version])]]
                (is (= impl-pin tpl-pin)
                    (str "Template " sym " pin for " substrate " (" tpl-pin ") must "
                         "match " src " (" impl-pin ") — P5 lockstep. Bump it in the _"
                         (name substrate) "/deps.edn template resource."))))
            (finally (delete-recursively tmp))))))))
