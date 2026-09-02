(ns day8.re-frame2-template.template-test
  "The shape contract for the emitted project.

   Every test drives `org.corfield.new/create` in-process — the same
   `data-fn` / `template-fn` / `post-process-fn` pipeline a shell-out
   `clojure -Tnew create` runs — and then reads the generated tree as a
   black box:

     1. The emitted file set is EXACTLY the twelve-file manifest, for
        both substrates (a set equality, not a containment check).
     2. `deps.edn` / `shadow-cljs.edn` / `package.json` parse and carry
        the substrate's coordinates, the two builds, and an npm-valid name.
     3. Nothing retired reappears: no advanced coordinate, no Xray npm
        package, no preload, no layout host, no variant file, no removed
        option in any emitted text.
     4. The argument gate: Reagent is the default, `:substrate` is strict
        on value and shape, and every retired flag (`:include-story?`,
        `:include-ssr?`, `:css`) fails as an UNKNOWN key.

   The checks that carry a negative assertion also carry a witness that
   the instrument bites, so a green here is never the \"no matches\" kind."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template!
                     run-template-opts! read-edn file-exists?]]))

;; --- The contract ----------------------------------------------------------

(def ^:private manifest
  "The twelve files every substrate emits for `acme/my-app`."
  #{".gitignore"
    "README.md"
    "deps.edn"
    "package.json"
    "shadow-cljs.edn"
    "resources/public/index.html"
    "resources/public/css/app.css"
    "src/acme/my_app/core.cljs"
    "src/acme/my_app/events.cljs"
    "src/acme/my_app/subs.cljs"
    "src/acme/my_app/views.cljs"
    "test/acme/my_app/events_test.cljs"})

(def ^:private substrate-coord
  {:reagent 'day8/re-frame2-reagent
   :uix     'day8/re-frame2-uix})

(def ^:private view-lib-coords
  {:reagent '#{reagent/reagent}
   :uix     '#{com.pitch/uix.core com.pitch/uix.dom}})

(def ^:private retired-coords
  "Coordinates the default scaffold no longer installs, anywhere in
   deps.edn — `:deps` or any alias."
  '#{day8/re-frame2-xray
     day8/re-frame2-story
     day8/re-frame2-ssr
     day8/re-frame2-ssr-ring
     day8/re-frame2-schemas
     day8/re-frame2-machines
     dev.weavejester/cljfmt
     clj-kondo/clj-kondo
     org.clojure/tools.namespace})

(def ^:private retired-text
  "Substrings that must not appear in ANY emitted text file: the retired
   options, the Xray preload / host / npm packages, the schema, error-sink
   and HTTP tutorials, the toolchain configs and the security policy."
  ["include-story?" "include-ssr?" ":css :tailwind" "tailwindcss"
   "day8.re-frame2-xray" "data-rf-xray-host" "rf2-xray-host"
   "@xyflow/react" "elkjs" "rf2-tools-sha"
   "re-frame.schemas" "reg-app-schema" "register-schema!"
   "register-listener!" ":rf.http/managed"
   "lefthook" "cljfmt" "clj-kondo" "tools.namespace"
   "Content-Security-Policy"])

;; --- Readers ---------------------------------------------------------------

(defn- emitted-files
  "Every regular file under `root`, as a set of forward-slash paths
   relative to `root`."
  [^java.io.File root]
  (let [base (.toPath (.getCanonicalFile root))]
    (into #{}
          (comp (filter #(.isFile ^java.io.File %))
                (map (fn [^java.io.File f]
                       (-> (.relativize base (.toPath (.getCanonicalFile f)))
                           str
                           (string/replace "\\" "/")))))
          (file-seq root))))

(defn- all-coords
  "Every coordinate a parsed deps.edn names — `:deps` plus every alias's
   `:extra-deps` / `:replace-deps` / `:deps`."
  [deps]
  (into (set (keys (:deps deps)))
        (mapcat (fn [alias] (mapcat keys ((juxt :extra-deps :replace-deps :deps) alias))))
        (vals (:aliases deps))))

(defn- retired-coords-in [deps]
  (set (filter retired-coords (all-coords deps))))

(defn- retired-text-in
  "`[relative-path substring]` for every retired substring found in an
   emitted text file."
  [^java.io.File root]
  (for [rel  (sort (emitted-files root))
        :let [text (slurp (io/file root rel))]
        s    retired-text
        :when (string/includes? text s)]
    [rel s]))

(defn- assert-no-scaffold-emitted!
  "The gate fired before any file was written."
  [^java.nio.file.Path tmp]
  (is (zero? (count (.listFiles (io/file (.toString tmp)))))
      "no scaffold is emitted when the argument gate throws"))

;; --- The per-substrate contract ------------------------------------------

(defn- assert-contract!
  [substrate]
  (let [tmp (tmp-dir (str "rf2-template-" (name substrate) "-"))]
    (try
      (let [root (run-template! tmp "acme/my-app" substrate)]
        ;; -- exactly the manifest --
        (is (= manifest (emitted-files root))
            (str substrate " must emit exactly the twelve-file manifest; "
                 "missing " (pr-str (sort (remove (emitted-files root) manifest)))
                 ", extra " (pr-str (sort (remove manifest (emitted-files root))))))

        ;; -- deps.edn --
        (let [deps (read-edn (io/file root "deps.edn"))]
          (is (map? deps) "deps.edn parses as a map")
          (is (= ["src"] (:paths deps)) "deps.edn :paths is [\"src\"]")
          (is (contains? (:deps deps) 'day8/re-frame2)
              "deps.edn names day8/re-frame2")
          (is (contains? (:deps deps) (substrate-coord substrate))
              (str "deps.edn names " (substrate-coord substrate)))
          (doseq [coord (view-lib-coords substrate)]
            (is (contains? (:deps deps) coord)
                (str "deps.edn names the view library " coord)))
          ;; The pin VALUES are version_lockstep_test.clj's; present-check only.
          (is (= (get-in deps [:deps 'day8/re-frame2 :mvn/version])
                 (get-in deps [:deps (substrate-coord substrate) :mvn/version]))
              "core and adapter ride one :mvn/version")
          (is (= #{:shadow} (set (keys (:aliases deps))))
              "deps.edn carries the :shadow alias and nothing else")
          (is (= ["test"] (get-in deps [:aliases :shadow :extra-paths]))
              ":shadow puts test/ on the classpath (and no dev/)")
          (is (nil? (get-in deps [:aliases :shadow :main-opts]))
              ":shadow is deps-only — `npx shadow-cljs` supplies its own -m")
          (is (contains? (get-in deps [:aliases :shadow :extra-deps]) 'thheller/shadow-cljs)
              ":shadow carries the shadow-cljs coordinate")
          (is (empty? (retired-coords-in deps))
              (str "deps.edn must name no retired coordinate; found "
                   (pr-str (retired-coords-in deps)))))

        ;; -- shadow-cljs.edn --
        (let [scs (read-edn (io/file root "shadow-cljs.edn"))
              app (get-in scs [:builds :app])
              tst (get-in scs [:builds :test])]
          (is (= {:aliases [:shadow]} (:deps scs))
              "shadow-cljs.edn reads its classpath from the :shadow alias")
          (is (= ["src" "test"] (:source-paths scs))
              ":source-paths is [\"src\" \"test\"]")
          (is (= #{:app :test} (set (keys (:builds scs))))
              "exactly the :app and :test builds")
          (is (= :browser (:target app)) ":app targets :browser")
          (is (= 'acme.my-app.core/init (get-in app [:modules :main :init-fn]))
              ":app :init-fn is the generated core/init")
          (is (not (contains? app :devtools))
              ":app wires no :devtools — no preload of any kind")
          (is (= :node-test (:target tst)) ":test targets :node-test")
          (is (= "-test$" (:ns-regexp tst)) ":test picks up *-test namespaces"))

        ;; -- package.json --
        (let [pj (slurp (io/file root "package.json"))]
          (is (string/includes? pj "\"name\": \"my-app\"")
              "package.json name is the npm-valid artefact segment, not acme/my-app")
          (is (string/includes? pj "\"private\": true") "package.json is private")
          (doseq [needle ["\"shadow-cljs\"" "\"react\"" "\"react-dom\""
                          "\"watch\"" "\"release\"" "\"test\""]]
            (is (string/includes? pj needle)
                (str "package.json carries " needle))))

        ;; -- the substrate's own view shape --
        (let [views (slurp (io/file root "src/acme/my_app/views.cljs"))]
          (is (string/includes? views (case substrate :reagent "reg-view" :uix "defui"))
              (str substrate " views.cljs uses its substrate's view form")))

        ;; -- nothing retired, anywhere in the emitted text --
        (is (empty? (retired-text-in root))
            (str "retired vocabulary in the emitted tree: "
                 (pr-str (retired-text-in root))))

        ;; -- the README's two escape hatches --
        (let [readme (slurp (io/file root "README.md"))]
          (is (string/includes? readme "docs/xray/01-installation.md")
              "README links the Xray installation page")
          (is (string/includes? readme "docs/story/index.md")
              "README links the Story page")
          (is (string/includes? readme "npx shadow-cljs watch app")
              "README says how to run")
          (is (string/includes? readme "npm test")
              "README says how to test")
          (is (string/includes? readme "npm run release")
              "README says how to release")))
      (finally
        (delete-recursively tmp)))))

(deftest reagent-contract-test
  (testing ":substrate :reagent emits the contract"
    (assert-contract! :reagent)))

(deftest uix-contract-test
  (testing ":substrate :uix emits the contract"
    (assert-contract! :uix)))

(deftest reagent-is-the-default-test
  (testing "no :substrate arg produces the Reagent scaffold"
    (let [tmp (tmp-dir "rf2-template-default-")]
      (try
        (let [root (run-template! tmp "acme/my-app" nil)
              deps (read-edn (io/file root "deps.edn"))]
          (is (contains? (:deps deps) 'day8/re-frame2-reagent)
              "default substrate is Reagent")
          (is (= manifest (emitted-files root))
              "the default emits the same manifest"))
        (finally
          (delete-recursively tmp))))))

;; --- The instruments bite ------------------------------------------------
;;
;; The manifest equality, the coordinate scan and the text scan are the
;; guards against an advanced dependency or file reappearing. Each is
;; exercised once against an input it must flag.

(deftest manifest-check-bites-test
  (testing "an extra emitted file breaks the manifest equality"
    (let [tmp (tmp-dir "rf2-template-witness-file-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent)]
          (is (= manifest (emitted-files root)) "control: clean tree matches")
          (spit (io/file root "dev/user.clj") "(ns user)")
          (is (not= manifest (emitted-files root))
              "a reappearing dev/user.clj is seen by the manifest check"))
        (finally
          (delete-recursively tmp))))))

(deftest retired-coord-check-bites-test
  (testing "a retired coordinate in :deps or in an alias is seen"
    (let [tmp (tmp-dir "rf2-template-witness-coord-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent)
              deps (read-edn (io/file root "deps.edn"))]
          (is (empty? (retired-coords-in deps)) "control: clean deps.edn")
          (is (= '#{day8/re-frame2-xray}
                 (retired-coords-in (assoc-in deps [:deps 'day8/re-frame2-xray]
                                              {:mvn/version "0"})))
              "an Xray coordinate in :deps is seen")
          (is (= '#{clj-kondo/clj-kondo}
                 (retired-coords-in (assoc-in deps [:aliases :clj-kondo :extra-deps
                                                    'clj-kondo/clj-kondo]
                                              {:mvn/version "0"})))
              "a clj-kondo coordinate in an alias is seen"))
        (finally
          (delete-recursively tmp))))))

(deftest retired-text-check-bites-test
  (testing "a retired substring in any emitted text file is seen"
    (let [tmp (tmp-dir "rf2-template-witness-text-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent)]
          (is (empty? (retired-text-in root)) "control: clean tree")
          (spit (io/file root "shadow-cljs.edn")
                (str (slurp (io/file root "shadow-cljs.edn"))
                     "\n;; :devtools {:preloads [day8.re-frame2-xray.preload]}\n"))
          (is (= [["shadow-cljs.edn" "day8.re-frame2-xray"]] (retired-text-in root))
              "a reappearing preload mention is seen, and named"))
        (finally
          (delete-recursively tmp))))))

;; --- Name derivation -----------------------------------------------------
;;
;; `acme/my-app` leaves the two derivation transforms doing only trivial
;; work; a dotted group + a multi-dash artefact exercises the dot→slash
;; and dash→underscore branches, the substituted `{{namespace}}`, and the
;; npm name.

(deftest name-derivation-dotted-group-test
  (testing "com.acme/my-cool-app nests under com/acme/my_cool_app, names the
            namespace com.acme.my-cool-app, and the npm package my-cool-app"
    (let [tmp (tmp-dir "rf2-template-dotted-name-")]
      (try
        (let [root (run-template! tmp "com.acme/my-cool-app" :reagent)]
          (is (= "my-cool-app" (.getName root))
              "the output dir is the group-stripped artefact")
          (doseq [rel ["src/com/acme/my_cool_app/core.cljs"
                       "src/com/acme/my_cool_app/events.cljs"
                       "src/com/acme/my_cool_app/subs.cljs"
                       "src/com/acme/my_cool_app/views.cljs"
                       "test/com/acme/my_cool_app/events_test.cljs"]]
            (is (file-exists? root rel) (str "expected " rel)))
          (is (string/includes? (slurp (io/file root "src/com/acme/my_cool_app/core.cljs"))
                                "(ns com.acme.my-cool-app.core")
              "the ns form keeps the dashes")
          (is (= 'com.acme.my-cool-app.core/init
                 (get-in (read-edn (io/file root "shadow-cljs.edn"))
                         [:builds :app :modules :main :init-fn]))
              "shadow-cljs :init-fn substitutes the derived namespace")
          (let [test-text (slurp (io/file root "test/com/acme/my_cool_app/events_test.cljs"))]
            (is (string/includes? test-text "[com.acme.my-cool-app.events]")
                "events_test.cljs requires the events ns by derived namespace"))
          (is (string/includes? (slurp (io/file root "package.json"))
                                "\"name\": \"my-cool-app\"")
              "package.json name is the artefact segment"))
        (finally
          (delete-recursively tmp))))))

(def ^:private npm-name-re
  "npm's rules for a new unscoped package name, as the test's own oracle:
   lowercase, URL-safe, no leading `.` or `_`."
  #"[a-z0-9~-][a-z0-9._~-]*")

(defn- emitted-npm-name [^java.io.File root]
  (second (re-find #"\"name\":\s*\"([^\"]*)\"" (slurp (io/file root "package.json")))))

(deftest npm-name-test
  (testing "the emitted package.json name is npm-valid, derived from the
            artefact segment, for qualified, dotted, bare and mixed-case names"
    (doseq [[project-name expected] [["acme/my-app"          "my-app"]
                                     ["com.acme/my-cool-app" "my-cool-app"]
                                     ["my-app"               "my-app"]
                                     ["Acme/MyApp"           "myapp"]]]
      (let [tmp (tmp-dir "rf2-template-npm-name-")]
        (try
          (let [root (run-template! tmp project-name :reagent)
                nm   (emitted-npm-name root)]
            (is (= expected nm) (str project-name " → " expected))
            (is (re-matches npm-name-re nm) (str nm " is npm-valid"))
            (is (not= project-name nm) "the Clojure name is never copied verbatim"))
          (finally
            (delete-recursively tmp))))))
  (testing "the qualified Clojure name copied verbatim is what the rule rejects"
    (is (nil? (re-matches npm-name-re "acme/my-app")))))

(deftest invalid-npm-name-rejected-test
  (testing "an artefact segment npm cannot take fails closed before any file lands"
    (let [tmp (tmp-dir "rf2-template-npm-name-bad-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-npm-name-invalid"
                              (run-template! tmp "acme/_private" :reagent))
            "a leading underscore is not an npm package name")
        (assert-no-scaffold-emitted! tmp)
        (finally
          (delete-recursively tmp))))))

;; --- The argument gate ---------------------------------------------------

(deftest invalid-substrate-rejected-test
  (testing "a :substrate outside the valid set throws, naming the set"
    (let [tmp (tmp-dir "rf2-template-bad-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-substrate-must-be-one-of"
                              (run-template! tmp "acme/my-app" :svelte)))
        ;; The retired :ui and :helix values take the same path as any other
        ;; unknown keyword — no shim, no alias, no deprecation message.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-substrate-must-be-one-of"
                              (run-template! tmp "acme/my-app" :ui)))
        (assert-no-scaffold-emitted! tmp)
        (finally
          (delete-recursively tmp))))))

(deftest non-keyword-substrate-rejected-test
  (testing "a non-keyword :substrate (string, symbol, number) is rejected, not coerced"
    (let [tmp (tmp-dir "rf2-template-non-kw-")]
      (try
        (doseq [raw ["reagent" 'reagent 42]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #":rf\.error/template-substrate-must-be-keyword"
                                (run-template! tmp "acme/my-app" raw))
              (str (pr-str raw) " is rejected")))
        (assert-no-scaffold-emitted! tmp)
        (finally
          (delete-recursively tmp))))))

(deftest retired-flags-are-unknown-test
  (testing "the retired :include-story? / :include-ssr? / :css keys fail as
            UNKNOWN — no alias, no deprecation warning, no compatibility path"
    (doseq [[flag value] [[:include-story? true]
                          [:include-story? false]
                          [:include-ssr?   true]
                          [:css            :tailwind]]]
      (let [tmp (tmp-dir "rf2-template-retired-flag-")]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #":rf\.error/template-unknown-flag"
                                (run-template-opts! tmp "acme/my-app" {flag value}))
              (str flag " is unknown"))
          (let [data (try (run-template-opts! tmp "acme/my-app" {flag value})
                          (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= [flag] (:unknown data))
                (str "ex-data names " flag " as the unknown key"))
            (is (= #{:substrate} (:accepted data))
                "ex-data names :substrate as the only accepted key"))
          (assert-no-scaffold-emitted! tmp)
          (finally
            (delete-recursively tmp)))))))

(deftest typo-keys-are-unknown-test
  (testing "a typo of any key fails closed rather than scaffolding the default"
    (doseq [opts [{:substrat :uix} {:include-story true} {:sub :uix}]]
      (let [tmp (tmp-dir "rf2-template-typo-")]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #":rf\.error/template-unknown-flag"
                                (run-template-opts! tmp "acme/my-app" opts))
              (str (pr-str opts) " is rejected"))
          (assert-no-scaffold-emitted! tmp)
          (finally
            (delete-recursively tmp)))))))

(deftest harness-keys-not-rejected-test
  (testing "deps-new's own harness keys (:overwrite, :src-dirs, :target-dir …)
            pass the gate and a valid invocation still scaffolds"
    (let [tmp (tmp-dir "rf2-template-harness-")]
      (try
        (let [proj (run-template-opts! tmp "acme/my-app" {:substrate :reagent})]
          (is (= manifest (emitted-files proj))
              "a valid invocation scaffolds with the gate active"))
        (finally
          (delete-recursively tmp))))))
