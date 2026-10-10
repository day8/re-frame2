;;;; tests/project_identity_test.clj — the manual route's project-identity
;;;; rule, held to the generator template's own derivation.
;;;;
;;;; A project's namespace, source path and npm name are DIFFERENT transforms
;;;; of one `group/artefact` coordinate, and the template's `hooks.clj`
;;;; computes each separately. A "rename consistently" instruction lands
;;;; `com.acme/my-cool-app` at `src/com.acme/my_cool_app`, which backs no
;;;; namespace `:init-fn` can name. So SKILL.md §Project identity states one
;;;; rule for both routes, and this suite EXECUTES that rule as written and
;;;; compares it with the template's real `data-fn` (loaded, not copied) on the
;;;; inputs whose answers differ. deps-new's own `:name` split runs before the
;;;; hooks and is modelled here from tools/template/spec/API.md §Errors (an
;;;; unqualified name is doubled).
;;;;
;;;; Run: bb tests/project_identity_test.clj   (from skills/re-frame2-setup/)

(ns project-identity-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def ^:private setup-root (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
(def ^:private repo-root (-> setup-root .getParentFile .getParentFile))

(defn- slurp-lf [f] (str/replace (slurp f) "\r\n" "\n"))

(def ^:private skill-md         (delay (slurp-lf (io/file setup-root "SKILL.md"))))
(def ^:private first-counter-md (delay (slurp-lf (io/file setup-root "references/first-counter.md"))))

(load-file (.getPath (io/file repo-root "tools/template/src/day8/re_frame2_template/hooks.clj")))

(def ^:private data-fn (resolve 'day8.re-frame2-template.hooks/data-fn))

(defn- hook-identity
  "The identities the generator route derives, from the template's `data-fn`."
  [{:keys [top main]}]
  (select-keys (data-fn {:substrate :reagent :top top :main main}) [:namespace :nested-dirs :npm-name]))

;; SKILL.md §Project identity, transcribed. It works on the whole
;; coordinate, the form an agent applies, where the hook works on two segments.

(defn- coordinate
  "A name with no `/` is DOUBLED, as deps-new does."
  [nm]
  (if (str/includes? nm "/") nm (str nm "/" nm)))

(defn- doc-identity [nm]
  (let [c (coordinate nm)]
    {:namespace   (-> c (str/replace "/" ".") (str/replace "_" "-"))
     :nested-dirs (-> c (str/replace "." "/") (str/replace "-" "_"))
     :npm-name    (str/lower-case (subs c (inc (str/last-index-of c "/"))))}))

(def ^:private identities
  "`:top` / `:main` is deps-new's split of `:name`, before the hooks see it."
  [{:label "the reference identity" :name "acme/my-app"          :top "acme"     :main "my-app"}
   {:label "a dotted qualified name" :name "com.acme/my-cool-app" :top "com.acme" :main "my-cool-app"}
   {:label "a bare name"             :name "my-app"               :top "my-app"   :main "my-app"}
   {:label "a mixed-case name"       :name "Acme/MyApp"           :top "Acme"     :main "MyApp"}])

(def ^:private identity-section
  "SKILL.md's `## Project identity` block, or \"\" when absent."
  (delay
    (let [body @skill-md]
      (if-let [start (str/index-of body "## Project identity")]
        (let [rest' (subs body start)
              end   (str/index-of rest' "\n## " 1)]
          (if end (subs rest' 0 end) rest'))
        ""))))

(deftest documented-rule-matches-the-generator-derivation
  (doseq [{:keys [label name] :as id} identities]
    (is (= (hook-identity id) (doc-identity name))
        (str "the manual identity rule and the generator disagree for " label " (" name ")"))))

(deftest invalid-npm-artefact-fails-before-emission
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/template-npm-name-invalid"
                        (hook-identity {:top "acme" :main "_private"}))
      "the template must reject an npm-invalid artefact segment")
  (testing "SKILL.md gives the manual route the same pre-flight, before file one"
    (let [section @identity-section]
      (is (str/includes? section "[a-z0-9~-][a-z0-9._~-]*")
          "§Project identity must state npm's name rule verbatim; the manual route has no exception machinery")
      (is (str/includes? section "214") "§Project identity must state npm's 214-character ceiling")
      (is (every? #(str/includes? section %) ["before" "write"])
          "§Project identity must order the npm check BEFORE writing, as the generator does"))))

(deftest skill-md-states-the-derived-identity-for-the-worked-example
  (let [section @identity-section
        {:keys [namespace nested-dirs]} (doc-identity "com.acme/my-cool-app")]
    ;; The `:init-fn` row carries the namespace; the coordinate row the npm name.
    (doseq [v [nested-dirs "com.acme/my-cool-app" (str namespace ".core/init")
               (:namespace (doc-identity "my-app")) (:npm-name (doc-identity "Acme/MyApp"))]]
      (is (str/includes? section v)
          (str "§Project identity must show the derived `" v "`: the rule has to be readable off the page")))))

(deftest the-token-rename-instruction-is-retired
  (doseq [[label body] [["SKILL.md" @skill-md] ["first-counter.md" @first-counter-md]]]
    (is (not (re-find #"(?i)renam(e|ed)[^.\n]{0,60}consistent" body))
        (str label " must not give a consistent token rename as the whole operation")))
  (is (str/includes? @first-counter-md "Project identity")
      "first-counter.md must route a named project to SKILL.md §Project identity"))

(let [{:keys [fail error]} (run-tests 'project-identity-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
