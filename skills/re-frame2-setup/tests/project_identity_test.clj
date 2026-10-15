;;;; Manual setup keeps the template's project-identity rules.
;;;; Run: bb tests/project_identity_test.clj

(ns project-identity-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

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

(def ^:private identity-section
  "SKILL.md's `## Project identity` block, or \"\" when absent."
  (delay
    (let [body @skill-md]
      (if-let [start (str/index-of body "## Project identity")]
        (let [rest' (subs body start)
              end   (str/index-of rest' "\n## " 1)]
          (if end (subs rest' 0 end) rest'))
        ""))))

(deftest generator-preserves-case-outside-npm-name
  (is (= {:namespace "Acme.MyApp" :nested-dirs "Acme/MyApp" :npm-name "myapp"}
         (hook-identity {:top "Acme" :main "MyApp"}))))

(deftest manual-route-validates-npm-name-before-writing
  (let [section @identity-section]
    (is (str/includes? section "[a-z0-9~-][a-z0-9._~-]*")
        "the manual route states npm's name rule")
    (is (str/includes? section "214") "the manual route states npm's length ceiling")
    (is (every? #(str/includes? section %) ["before" "write"])
        "the npm check precedes writing")))

(deftest skill-md-states-the-derived-identity-for-the-worked-example
  (doseq [v ["com/acme/my_cool_app" "com.acme/my-cool-app" "com.acme.my-cool-app.core/init"
             "my-app.my-app" "myapp"]]
    (is (str/includes? @identity-section v)
        (str "Project identity must show the derived `" v "`"))))

(deftest the-token-rename-instruction-is-retired
  (doseq [[label body] [["SKILL.md" @skill-md] ["first-counter.md" @first-counter-md]]]
    (is (not (re-find #"(?i)renam(e|ed)[^.\n]{0,60}consistent" body))
        (str label " must not give a consistent token rename as the whole operation")))
  (is (str/includes? @first-counter-md "Project identity")
      "first-counter.md must route a named project to SKILL.md §Project identity"))

(let [{:keys [fail error]} (run-tests 'project-identity-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
