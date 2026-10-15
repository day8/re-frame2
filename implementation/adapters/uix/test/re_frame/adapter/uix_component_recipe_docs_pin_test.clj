(ns re-frame.adapter.uix-component-recipe-docs-pin-test
  "The copyable views.md recipe must equal its browser test, with line endings normalized."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private recipe-path
  "implementation/adapters/uix/test/re_frame/adapter/uix_component_recipe_dom_cljs_test.cljs")

(def ^:private page-path
  "docs/core/testing/views.md")

(def ^:private opening-line
  "(ns re-frame.adapter.uix-component-recipe-dom-cljs-test")

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

(defn- normalise [s]
  (str/trim-newline (str/replace s "\r\n" "\n")))

(defn- fenced-clojure-block-opening-with
  "Find the clojure fence whose body starts with the recipe namespace."
  [md line]
  (->> (re-seq #"(?s)```clojure\n(.*?)\n```" md)
       (map second)
       (some #(when (str/starts-with? % line) %))))

(deftest views-page-shows-the-recipe-verbatim
  (let [root   (repo-root)
        recipe (normalise (slurp (io/file root recipe-path)))
        page   (normalise (slurp (io/file root page-path)))]
    (is (= recipe (some-> (fenced-clojure-block-opening-with page opening-line) normalise))
        (str page-path " §4 and " recipe-path " have drifted apart; "
             "make the page's block the file's exact contents."))))
