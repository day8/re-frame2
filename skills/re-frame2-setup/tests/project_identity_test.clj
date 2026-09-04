;;;; tests/project_identity_test.clj — the manual route's project-identity
;;;; rule, held to the generator template's own derivation.
;;;;
;;;; The setup skill has TWO routes to the same twelve files (SKILL.md
;;;; cardinal rule 4): the manual route writes `references/first-counter.md`'s
;;;; bodies itself, and the generator route shells `clojure -Tnew create …`.
;;;; Only the generator route derives the project's several identities — the
;;;; Clojure namespace, the source/test directory path, the npm package name
;;;; and the output directory are RELATED BUT DIFFERENT STRINGS, and
;;;; `tools/template/src/day8/re_frame2_template/hooks.clj` computes each one
;;;; separately. The manual route used to say only "rename `acme` / `my-app`
;;;; consistently", which is deterministic for the reference identity and
;;;; underspecified for every other one: a textual rename of
;;;; `com.acme/my-cool-app` naturally lands `src/com.acme/my_cool_app`, which
;;;; does not back the namespace `shadow-cljs.edn`'s `:init-fn` names, and the
;;;; scaffold dies at the terminating compile wearing an error that mentions
;;;; neither the name nor the rename.
;;;;
;;;; So SKILL.md now carries ONE identity rule, stated for both routes, and
;;;; this suite is what stops the two drifting apart. It does not restate the
;;;; rule — it EXECUTES the rule as SKILL.md words it and compares the result
;;;; against the template's real `data-fn`, for the four inputs whose answers
;;;; differ (dotted-qualified, bare, mixed-case, invalid-npm). A change to
;;;; either side that the other does not follow fails here.
;;;;
;;;; The oracle is loaded, not copied: `load-file` on the template's real
;;;; `hooks.clj`, the same way `tests/first_counter_derivation.clj` loads it
;;;; to render the scaffold leaves.
;;;;
;;;; What this suite does NOT cover: deps-new's own `preprocess-options`
;;;; split of `:name` into `:top` / `:main` runs before the template's hooks
;;;; and is not on Babashka's classpath, so the split is modelled here from
;;;; `tools/template/spec/API.md` §Errors (an unqualified name is doubled) and
;;;; cross-checked against the JVM tier, where
;;;; `tools/template/test/day8/re_frame2_template/template_test.clj`
;;;; `name-derivation-dotted-group-test` proves the whole pipeline end to end
;;;; for `com.acme/my-cool-app` against a real deps-new emission.
;;;;
;;;; Run locally:  bb tests/project_identity_test.clj   (from skills/re-frame2-setup/)
;;;; Exit:         0 = pass, non-zero = fail.
;;;;
;;;; CI: gated by the `skills-structural` job in .github/workflows/test.yml,
;;;; which loops `skills/re-frame2-setup/tests/*_test.clj`.
;;;;
;;;; NOT published — `package.json` :files excludes `tests/`.

(ns project-identity-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem
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

(def ^:private hooks-file
  (io/file repo-root "tools/template/src/day8/re_frame2_template/hooks.clj"))

(defn- slurp-lf
  "Read a file with its line endings normalised, so a Windows checkout
   (core.autocrlf=true) and a Linux CI runner see the same string."
  [f]
  (str/replace (slurp f) "\r\n" "\n"))

(def ^:private skill-md          (delay (slurp-lf (io/file setup-root "SKILL.md"))))
(def ^:private first-counter-md  (delay (slurp-lf (io/file setup-root "references/first-counter.md"))))

;; ---------------------------------------------------------------------------
;; The oracle — the template's own hook, loaded rather than copied
;; ---------------------------------------------------------------------------

(load-file (.getPath hooks-file))

(def ^:private data-fn (resolve 'day8.re-frame2-template.hooks/data-fn))

(defn- hook-identity
  "The identity values the generator route derives, straight out of the
   template's `data-fn`."
  [{:keys [top main]}]
  (select-keys (data-fn {:substrate :reagent :top top :main main})
               [:namespace :nested-dirs :npm-name]))

;; ---------------------------------------------------------------------------
;; The documented rule, executed
;;
;; These four functions ARE SKILL.md §Project identity, transcribed into
;; code. They deliberately work on the whole `group/artefact` coordinate
;; rather than on the two segments separately, because that is the form an
;; agent reading the rule can apply without a second thought — and the tests
;; below are what prove the one-string form agrees with the hook's
;; two-segment form on every input that could tell them apart.
;; ---------------------------------------------------------------------------

(defn- coordinate
  "Step A of the rule: normalise to `group/artefact`. A name with no `/` is
   DOUBLED — that is what deps-new does (tools/template/spec/API.md §Errors),
   so the manual route reproduces it rather than inventing a group."
  [nm]
  (if (str/includes? nm "/") nm (str nm "/" nm)))

(defn- doc-namespace
  "The whole coordinate, `/` → `.` and `_` → `-`."
  [nm]
  (-> (coordinate nm) (str/replace "/" ".") (str/replace "_" "-")))

(defn- doc-nested-dirs
  "The whole coordinate, `.` → `/` and `-` → `_`."
  [nm]
  (-> (coordinate nm) (str/replace "." "/") (str/replace "-" "_")))

(defn- doc-artefact
  "The artefact segment — everything after the `/`."
  [nm]
  (let [c (coordinate nm)]
    (subs c (inc (str/last-index-of c "/")))))

(defn- doc-npm-name
  "The artefact segment, lowercased."
  [nm]
  (str/lower-case (doc-artefact nm)))

(defn- doc-identity [nm]
  {:namespace   (doc-namespace nm)
   :nested-dirs (doc-nested-dirs nm)
   :npm-name    (doc-npm-name nm)})

;; ---------------------------------------------------------------------------
;; The inputs whose answers differ
;; ---------------------------------------------------------------------------

(def ^:private identities
  "`:top` / `:main` is deps-new's `preprocess-options` split, which runs
   before the template's hooks see the name. The bare row's `:top` and
   `:main` are the same string because deps-new doubles an unqualified name."
  [{:label "the reference identity"        :name "acme/my-app"          :top "acme"     :main "my-app"}
   {:label "a dotted qualified name"       :name "com.acme/my-cool-app" :top "com.acme" :main "my-cool-app"}
   {:label "a bare (unqualified) name"     :name "my-app"               :top "my-app"   :main "my-app"}
   {:label "a mixed-case name"             :name "Acme/MyApp"           :top "Acme"     :main "MyApp"}])

(def ^:private identity-section
  "SKILL.md's `## Project identity` block — the rule the manual route follows.
   Empty when the section is absent, so a missing section fails as a named
   assertion rather than as a NullPointerException three tests later."
  (delay
    (let [body  @skill-md
          start (str/index-of body "## Project identity")]
      (if start
        (let [rest' (subs body start)
              end   (str/index-of rest' "\n## " 1)]
          (if end (subs rest' 0 end) rest'))
        ""))))

(defn- contains-all? [body tokens]
  (every? #(str/includes? body %) tokens))

;; ---------------------------------------------------------------------------
;; 1. The oracle is live
;;
;; Every assertion below compares something against `data-fn`. If the
;; template moved and `load-file` quietly loaded a different shape, the
;; comparisons could agree with each other and mean nothing — so pin one
;; answer this suite does not compute, taken from the JVM tier's
;; `name-derivation-dotted-group-test`, which proves it against a real
;; deps-new emission.
;; ---------------------------------------------------------------------------

(deftest the-template-hook-is-the-oracle
  (testing "the loaded hook derives the identity the template's JVM tier proves end to end"
    (is (= {:namespace   "com.acme.my-cool-app"
            :nested-dirs "com/acme/my_cool_app"
            :npm-name    "my-cool-app"}
           (hook-identity {:top "com.acme" :main "my-cool-app"}))
        (str "tools/template/.../hooks.clj no longer derives the identity that "
             "template_test.clj's name-derivation-dotted-group-test proves against a "
             "real deps-new emission. The generator changed: re-read the hook and "
             "update SKILL.md §Project identity in the same commit."))))

;; ---------------------------------------------------------------------------
;; 2. Route parity — the documented rule and the generator agree
;; ---------------------------------------------------------------------------

(deftest documented-rule-matches-the-generator-derivation
  (testing "SKILL.md's identity rule, executed, equals the template hook's derivation"
    (doseq [{:keys [label name] :as id} identities]
      (is (= (hook-identity id) (doc-identity name))
          (str "the manual route's identity rule and the generator's derivation "
               "disagree for " label " (" name "). The two routes are advertised as "
               "landing on the same files; a divergence here is a scaffold whose "
               "namespace, source path and package name do not describe one project.")))))

(deftest the-three-forms-are-genuinely-different-strings
  (testing "a dotted qualified name separates namespace, path and npm name"
    (let [{:keys [namespace nested-dirs npm-name]} (doc-identity "com.acme/my-cool-app")]
      (is (= "com.acme.my-cool-app" namespace))
      (is (= "com/acme/my_cool_app" nested-dirs))
      (is (= "my-cool-app" npm-name))
      (is (= 3 (count (distinct [namespace nested-dirs npm-name])))
          "the three identity forms collapsed into one string — the rule stopped distinguishing them.")
      (is (not (str/includes? nested-dirs "."))
          (str "the source path kept a `.` from the group. `src/com.acme/my_cool_app` is the "
               "natural result of a token rename and does NOT back the namespace "
               "`shadow-cljs.edn` names — this is the failure the rule exists to prevent."))))
  (testing "a bare name is doubled, exactly as deps-new doubles it"
    (is (= {:namespace "my-app.my-app" :nested-dirs "my_app/my_app" :npm-name "my-app"}
           (doc-identity "my-app"))))
  (testing "a mixed-case name keeps its case in Clojure and loses it in npm"
    (is (= {:namespace "Acme.MyApp" :nested-dirs "Acme/MyApp" :npm-name "myapp"}
           (doc-identity "Acme/MyApp")))))

;; ---------------------------------------------------------------------------
;; 3. An npm-invalid artefact fails closed BEFORE any file is written
;; ---------------------------------------------------------------------------

(deftest invalid-npm-artefact-fails-before-emission
  (testing "the generator throws rather than emitting a project npm cannot name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/template-npm-name-invalid"
                          (hook-identity {:top "acme" :main "_private"}))
        "the template no longer rejects an npm-invalid artefact segment."))
  (testing "SKILL.md gives the manual route the same pre-flight, before file one"
    (let [section @identity-section]
      (is (seq section) "SKILL.md carries no `## Project identity` section.")
      (is (str/includes? section "[a-z0-9~-][a-z0-9._~-]*")
          (str "SKILL.md §Project identity no longer states npm's name rule verbatim. "
               "The manual route has no exception machinery — the rule IS the gate."))
      (is (str/includes? section "214")
          "SKILL.md §Project identity no longer states npm's 214-character ceiling.")
      (is (contains-all? section ["before" "write"])
          (str "SKILL.md §Project identity no longer orders the npm check BEFORE writing. "
               "The generator throws before it emits; a manual route that checks afterwards "
               "leaves the partial scaffold the generator never leaves.")))))

;; ---------------------------------------------------------------------------
;; 4. The rule is actually in the document the manual route reads
;;
;; Every expected string below is DERIVED from the hook, so this suite cannot
;; pass by agreeing with a stale transcription of it.
;; ---------------------------------------------------------------------------

(deftest skill-md-states-the-derived-identity-for-the-worked-example
  (testing "the section names each derived form for the dotted qualified example"
    (let [section @identity-section
          {:keys [namespace nested-dirs npm-name]} (doc-identity "com.acme/my-cool-app")]
      (is (seq section) "SKILL.md carries no `## Project identity` section.")
      (doseq [[what v] [["namespace" namespace]
                        ["source path" nested-dirs]
                        ["npm name" npm-name]
                        ["supplied coordinate" "com.acme/my-cool-app"]]]
        (is (str/includes? section v)
            (str "SKILL.md §Project identity does not show the derived " what " `" v
                 "`. The rule has to be readable off the page as three different "
                 "strings, not inferred.")))
      (is (str/includes? section (str namespace ".core/init"))
          (str "SKILL.md §Project identity does not show the derived `:init-fn` `"
               namespace ".core/init`. A namespace nobody can resolve is exactly how "
               "this defect surfaces at the terminating compile."))))
  (testing "the section states the boundary policies the reference name hides"
    (let [section @identity-section]
      (is (str/includes? section (:namespace (doc-identity "my-app")))
          (str "SKILL.md §Project identity does not state the doubled bare-name identity `"
               (:namespace (doc-identity "my-app")) "`. Bare is the input most likely to be "
               "typed and the one whose answer is least guessable."))
      (is (str/includes? section (:npm-name (doc-identity "Acme/MyApp")))
          (str "SKILL.md §Project identity does not state the lowercased npm name `"
               (:npm-name (doc-identity "Acme/MyApp")) "` for a mixed-case artefact — the "
               "one place the Clojure and npm forms part company on purpose.")))))

(deftest the-token-rename-instruction-is-retired
  (testing "neither SKILL.md nor first-counter.md gives a consistent rename as the whole operation"
    (doseq [[label body] [["SKILL.md" @skill-md] ["first-counter.md" @first-counter-md]]]
      (is (not (re-find #"(?i)renam(e|ed)[^.\n]{0,60}consistent" body))
          (str label " still instructs a consistent token rename for an author-supplied "
               "name. That is deterministic only for `acme/my-app`: the namespace, the "
               "source path and the npm name are different transforms of the same "
               "coordinate. Point at SKILL.md §Project identity instead."))))
  (testing "first-counter.md routes a named project to the one rule"
    (is (str/includes? @first-counter-md "Project identity")
        (str "first-counter.md no longer routes an author-supplied name to SKILL.md's "
             "identity rule. The leaf is the reference scaffold; it must not grow a "
             "second, hand-maintained copy of the derivation (design.md L13)."))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'project-identity-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
