(ns day8.re-frame2-template.version-lockstep-test
  "Template pins track their independent sources of truth (Principles P5).
   The pair fixture rides this lane because package.json changes arm it."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [day8.re-frame2-template.hooks :as hooks]
            [day8.re-frame2-template.test-support
             :refer [read-edn tmp-dir delete-recursively repo-root run-template!]]))

(defn- read-version-file []
  (let [f (io/file (repo-root) "VERSION")]
    (when-not (.isFile f)
      (throw (ex-info "VERSION file missing at repo root" {:file (.getPath f)})))
    (string/trim (slurp f))))

(defn- pin-from-json-text [text pkg]
  (some-> (re-find (re-pattern (str "\"" pkg "\":\\s*\"([^\"]+)\"")) text)
          second))

(defn- read-package-json-pin
  ([pkg] (read-package-json-pin "implementation/package.json" pkg))
  ([rel-path pkg]
   (let [text    (slurp (io/file (repo-root) rel-path))
         section (fn [name]
                   (some-> (re-find (re-pattern (str "\"" name "\":\\s*\\{([^}]*)\\}")) text)
                           second))
         pin     (some #(some-> (section %) (pin-from-json-text pkg))
                       ["dependencies" "devDependencies"])]
     (when-not pin
       (throw (ex-info (str "Couldn't find pin for " pkg " in " rel-path)
                       {:pkg pkg :package-json rel-path})))
     pin)))

(defn- read-impl-deps-pin [rel-path sym]
  (let [deps       (read-edn (io/file (repo-root) rel-path))
        alias-maps (mapcat (juxt :extra-deps :replace-deps) (vals (:aliases deps)))
        pin        (some #(get-in % [sym :mvn/version]) (cons (:deps deps) alias-maps))]
    (when-not pin
      (throw (ex-info (str "Couldn't find an :mvn/version pin for " sym " in " rel-path)
                      {:coord sym :deps-file rel-path})))
    pin))

(deftest pair-fixture-react-lockstep
  (let [fixture "skills/re-frame2-pair/tests/fixture/package.json"
        packages ["react" "react-dom"]]
    (is (= (mapv read-package-json-pin packages)
           (mapv #(read-package-json-pin fixture %) packages))
        "the pair fixture's React pins track implementation/package.json")))

(defn- base-version [pin]
  (string/replace-first pin #"^[~^=]+" ""))

(deftest template-pin-literals-lockstep
  (let [tmp (tmp-dir "rf2-template-lockstep-pins-")]
    (try
      (let [root     (run-template! tmp "acme/my-app" :reagent)
            pj-text  (slurp (io/file root "package.json"))
            deps     (read-edn (io/file root "deps.edn"))
            expected {"day8/re-frame2" (read-version-file)
                      "react"         (read-package-json-pin "react")
                      "react-dom"     (read-package-json-pin "react-dom")
                      "shadow-cljs"   (read-package-json-pin "shadow-cljs")
                      "@xyflow/react" (base-version (read-package-json-pin "@xyflow/react"))
                      "elkjs"         (base-version (read-package-json-pin "elkjs"))}
            emitted  (assoc (into {} (for [pkg ["react" "react-dom" "shadow-cljs"
                                                "@xyflow/react" "elkjs"]]
                                      [pkg (pin-from-json-text pj-text pkg)]))
                            "day8/re-frame2" (get-in deps [:deps 'day8/re-frame2 :mvn/version]))]
        (is (= expected emitted)))
      (finally (delete-recursively tmp)))))

(def ^:private substrate-lib-sources
  {:reagent ['reagent/reagent    "implementation/adapters/reagent/deps.edn"]
   :uix     ['com.pitch/uix.core "implementation/adapters/uix/deps.edn"]})

(deftest substrate-deps-lockstep
  ;; Each substrate resource owns a separate copy of these pins.
  (let [core-deps "implementation/core/deps.edn"
        clj-pins  (into {} (for [sym '[org.clojure/clojure org.clojure/clojurescript]]
                            [sym (read-impl-deps-pin core-deps sym)]))]
    (doseq [substrate (sort (keys @#'hooks/substrate-registry))]
      (let [tmp (tmp-dir (str "rf2-template-lockstep-" (name substrate) "-"))]
        (try
          (let [deps     (read-edn (io/file (run-template! tmp "acme/my-app" substrate) "deps.edn"))
                expected (if-let [[sym src] (substrate-lib-sources substrate)]
                           (assoc clj-pins sym (read-impl-deps-pin src sym))
                           clj-pins)]
            (is (= expected
                   (update-vals (select-keys (:deps deps) (keys expected)) :mvn/version))
                (str substrate " pins track the implementation artefacts")))
          (finally (delete-recursively tmp)))))))
