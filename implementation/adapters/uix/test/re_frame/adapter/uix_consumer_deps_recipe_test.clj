(ns re-frame.adapter.uix-consumer-deps-recipe-test
  "Published UIx recipes must name every required coordinate at the template version; the aggregate compile hides missing consumer dependencies."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private adapter-deps-path
  "implementation/adapters/uix/deps.edn")

(def ^:private template-deps-path
  "The generator template owns the published UIx dependency versions."
  "tools/template/resources/day8/re_frame2_template/_uix/deps.edn")

(def ^:private example-sources
  ["examples/substrates/uix/counter/core.cljs"
   "examples/substrates/uix/login/core.cljs"
   "examples/substrates/uix/dashboard/core.cljs"])

(def ^:private recipe-pages
  "Documents publishing copyable UIx dependency recipes."
  ["spec/Conventions.md"
   "docs/core/how-to/use-uix.md"
   "examples/substrates/uix/counter/README.md"
   "examples/substrates/uix/login/README.md"
   "examples/substrates/uix/dashboard/README.md"])

(def ^:private ns->coordinate
  "Maven owner of each UIx namespace the examples may require."
  {"uix.core" 'com.pitch/uix.core
   "uix.dom"  'com.pitch/uix.dom})

(defn- repo-root
  "Find the nearest ancestor carrying mkdocs.yml."
  []
  (loop [dir (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
    (cond
      (nil? dir)
      (throw (ex-info "repo root not found: no mkdocs.yml above user.dir"
                      {:user-dir (System/getProperty "user.dir")}))

      (.exists (io/file dir "mkdocs.yml")) dir
      :else (recur (.getParentFile dir)))))

(defn- slurp-at [root rel]
  (let [f (io/file root rel)]
    (when-not (.exists f)
      (throw (ex-info (str "missing file: " rel) {:path (str f)})))
    (slurp f)))

(defn- deps-map
  "Read shipping :deps without aliases."
  [root rel]
  (-> (slurp-at root rel) edn/read-string :deps))

(defn- required-uix-namespaces
  "UIx namespaces required by the example source."
  [src]
  (->> (re-seq #"\[(uix\.[a-zA-Z0-9._-]+)[\s\]]" src)
       (map second)
       set))

(defn- recipe-coordinates
  "Published UIx coordinate versions, including fenced blocks and tables."
  [md]
  (into {}
        (for [[_ nm v] (re-seq #"com\.pitch/(uix\.[a-z]+)\s+\{:mvn/version\s+\"([^\"]+)\"\}" md)]
          [nm v])))

(deftest adapter-ships-uix-core-but-not-uix-dom
  (let [deps (deps-map (repo-root) adapter-deps-path)]
    (is (= [true false] [(contains? deps 'com.pitch/uix.core)
                         (contains? deps 'com.pitch/uix.dom)])
        (str adapter-deps-path " must ship com.pitch/uix.core and must not ship"
             " com.pitch/uix.dom; if that changed deliberately, this whole guard"
             " needs revisiting."))))

(deftest every-required-uix-namespace-has-an-owner-coordinate
  (let [root (repo-root)]
    (doseq [rel example-sources]
      (testing rel
        (let [required (required-uix-namespaces (slurp-at root rel))]
          (testing "the scan has signal — the file's uix.core require is seen"
            (is (contains? required "uix.core")
                (str rel " does not appear to require uix.core: either it "
                     "stopped being a UIx view file, or the require-scanning "
                     "regex has gone blind.")))
          (testing "and every uix.* namespace it requires has a known owner"
            (is (empty? (remove ns->coordinate required))
                (str rel " requires " (pr-str (vec (remove ns->coordinate required)))
                     " — no consumer coordinate in this test owns it. Add the "
                     "owner to ns->coordinate AND to every recipe in "
                     (pr-str recipe-pages) "."))))))))

(deftest published-recipes-name-every-required-uix-coordinate-in-lockstep
  (let [root       (repo-root)
        template   (deps-map root template-deps-path)
        core-ver   (get-in template ['com.pitch/uix.core :mvn/version])
        dom-ver    (get-in template ['com.pitch/uix.dom :mvn/version])
        owners     (->> example-sources
                        (mapcat #(required-uix-namespaces (slurp-at root %)))
                        set)]
    (testing "the template pins uix.core — the one version source"
      (is (some? core-ver) (str template-deps-path " has no com.pitch/uix.core.")))
    (testing "and deliberately does NOT pin uix.dom"
      (is (nil? dom-ver)
          (str template-deps-path " pins com.pitch/uix.dom at "
               (pr-str dom-ver) ", but the emitted app mounts through"
               " `rf.adapter.uix/client-root`. If the pin was deliberate, this"
               " guard and `retired-coords` in the template suite both need"
               " revisiting rather than relaxing.")))
    (doseq [rel recipe-pages]
      (testing rel
        (let [found (recipe-coordinates (slurp-at root rel))]
          (doseq [nm (sort owners)]
            (testing (str "names com.pitch/" nm " at the template's version")
              (is (= core-ver (get found nm))
                  (str rel " pins com.pitch/" nm " at " (pr-str (get found nm))
                       " (nil: not named, so a consumer copying it cannot resolve"
                       " what the examples require) but " template-deps-path
                       " pins " core-ver ".")))))))))
