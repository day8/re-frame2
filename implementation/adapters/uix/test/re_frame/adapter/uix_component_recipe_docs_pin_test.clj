(ns re-frame.adapter.uix-component-recipe-docs-pin-test
  "The UIx component-test recipe is shown VERBATIM in
   `docs/core/testing/views.md` §4, so what a reader copies is what the
   `:browser-test` lane runs. This pins the two to each other: the page's
   fenced `clojure` block that opens with the recipe's `ns` form must equal
   `uix_component_recipe_dom_cljs_test.cljs`, byte for byte (line endings
   normalised). Edit either side and this goes red until the other follows."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private recipe-path
  "implementation/adapters/uix/test/re_frame/adapter/uix_component_recipe_dom_cljs_test.cljs")

(def ^:private page-path
  "docs/core/testing/views.md")

(def ^:private opening-line
  "(ns re-frame.adapter.uix-component-recipe-dom-cljs-test")

(defn- repo-root
  "The nearest ancestor of the working directory carrying `mkdocs.yml`."
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
  "The body of the first ```clojure fence in `md` whose first line is `line`,
   or nil when the page carries none."
  [md line]
  (->> (re-seq #"(?s)```clojure\n(.*?)\n```" md)
       (map second)
       (some #(when (str/starts-with? % line) %))))

(deftest views-page-shows-the-recipe-verbatim
  (let [root   (repo-root)
        recipe (normalise (slurp (io/file root recipe-path)))
        page   (normalise (slurp (io/file root page-path)))
        shown  (fenced-clojure-block-opening-with page opening-line)]
    (testing "the recipe file opens with the ns form the page is pinned on"
      (is (str/starts-with? recipe opening-line)))
    (testing "the page carries a fenced clojure block opening with that ns form"
      (is (some? shown)))
    (testing "and that block is the recipe file, byte for byte"
      (is (= recipe (some-> shown normalise))
          (str page-path " §4 and " recipe-path " have drifted apart; "
               "make the page's block the file's exact contents.")))))
