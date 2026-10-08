(ns re-frame.story.xray-dependency-honesty-test
  "Dependency-honesty gate for Story's Xray coupling.

  Story's shipped shell hard-`:require`s `day8.re-frame2-xray.*`
  namespaces, so `tools/story/deps.edn` must declare the
  `day8/re-frame2-xray` dependency. The repository-wide Shadow build
  carries `../tools/xray/src` on its GLOBAL `:source-paths`, so every
  in-repo build compiles whether or not Story declares it; without the
  declaration a fresh consumer whose only tool dependency is
  `day8/re-frame2-story` could not compile the shell at all.

  Required namespaces are found by parsing each source file's `ns` form
  (a grep is blind to a require split across a line wrap), and declared
  roots are read from `deps.edn` alone. Resolution never consults the
  live classpath, so a leaked `tools/xray/src` cannot satisfy the gate.

  JVM-only `.clj`: it reads files off disk."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]))

;; ---- locating the artefact ------------------------------------------------

(defn- artefact-root
  "Story's artefact root — the directory holding `deps.edn`.

  Derived from the classpath location of a known Story source file so
  the test does not depend on the JVM working directory."
  []
  (let [res (io/resource "re_frame/story/config.cljc")]
    (assert res "re_frame/story/config.cljc must be on the classpath")
    ;; <root>/src/re_frame/story/config.cljc → up 4 → <root>
    (->> (iterate #(.getParentFile ^java.io.File %) (io/file res))
         (drop 4)
         first)))

(defn- read-deps []
  (let [f (io/file (artefact-root) "deps.edn")]
    (assert (.exists f) (str "deps.edn not found at " f))
    (edn/read-string (slurp f))))

;; ---- what Story declares --------------------------------------------------

(defn- declared-source-roots
  "Map of dep-symbol → its on-disk `src` directory, for every
  `:local/root` dependency declared in Story's main `:deps` map.

  Main `:deps` ONLY — `:test` alias deps are deliberately excluded:
  the published Story jar carries only the main deps, and this gate is
  about what a fresh consumer gets."
  [deps]
  (into {}
        (keep (fn [[dep coord]]
                (when-let [lr (:local/root coord)]
                  [dep (io/file (artefact-root) lr "src")])))
        (:deps deps)))

;; ---- what Story requires --------------------------------------------------

(defn- source-files
  "Every `.cljs` / `.cljc` / `.clj` file under Story's `src`."
  []
  (->> (file-seq (io/file (artefact-root) "src"))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.clj[sc]?$" (.getName ^java.io.File %)))))

(defn- ns-form
  "Read a source file's leading `ns` form.

  Reader conditionals are resolved with the `:cljs` feature so the
  CLJS-side requires (where every Xray coupling lives) are visible.
  Only the FIRST form is read, so CLJS-only body syntax never reaches
  the Clojure reader."
  [^java.io.File f]
  (try
    (read-string {:read-cond :allow :features #{:cljs}} (slurp f))
    (catch Exception _ nil)))

(defn- required-symbols
  "All namespace symbols named in a `ns` form's `:require` clauses."
  [form]
  (when (and (seq? form) (= 'ns (first form)))
    (->> form
         (filter #(and (seq? %) (= :require (first %))))
         (mapcat rest)
         (map #(if (sequential? %) (first %) %))
         (filter symbol?))))

(defn- required-day8-namespaces
  "Every `day8.*` namespace hard-required by Story's own sources,
  as a map of namespace-symbol → set of requiring source file names."
  []
  (reduce (fn [acc f]
            (reduce (fn [acc' sym]
                      (if (str/starts-with? (str sym) "day8.")
                        (update acc' sym (fnil conj #{}) (.getName ^java.io.File f))
                        acc'))
                    acc
                    (required-symbols (ns-form f))))
          {}
          (source-files)))

(defn- ns->paths
  "Candidate relative file paths for a namespace symbol."
  [sym]
  (let [base (-> (str sym) (str/replace "-" "_") (str/replace "." "/"))]
    (map #(str base %) [".cljs" ".cljc" ".clj"])))

(defn- resolvable-under?
  "True when `sym` resolves to a real file under one of `roots`.
  Pure file-system resolution — the live classpath is never consulted."
  [roots sym]
  (boolean
    (some (fn [root]
            (some #(.exists (io/file root %)) (ns->paths sym)))
          roots)))

;; ---- the gate -------------------------------------------------------------

;; release-story.yml finds the coordinate by this literal `:local/root` to
;; rewrite it to `:mvn/version`, which keeps Story and Xray lockstep-versioned.
(deftest xray-is-declared-in-story-deps
  (is (= "../xray" (get-in (read-deps) [:deps 'day8/re-frame2-xray :local/root]))
      "tools/story/deps.edn must declare day8/re-frame2-xray at :local/root
       \"../xray\" in its main :deps"))

(deftest every-required-day8-namespace-is-declared
  (testing "every `day8.*` namespace Story's sources require resolves
            under a root Story itself declares"
    (let [roots    (vals (declared-source-roots (read-deps)))
          required (required-day8-namespaces)
          missing  (into (sorted-map)
                         (remove (fn [[sym _]] (resolvable-under? roots sym)))
                         required)]
      (is (seq required)
          "control: an empty required set would make this gate vacuous")
      (is (= {} (into {} missing))
          (str "these namespaces are required by Story's sources but are "
               "NOT reachable from any :local/root declared in "
               "tools/story/deps.edn: "
               (str/join ", "
                         (map (fn [[sym files]]
                                (str sym " (required by " (str/join ", " (sort files)) ")"))
                              missing)))))))

(deftest story-does-not-depend-on-story-from-xray
  (testing "the coupling is one-way Story → Xray: Xray requiring Story back
            would make a dependency cycle neither artefact could publish"
    (let [xray-src (io/file (artefact-root) ".." "xray" "src")
          cycles   (when (.exists xray-src)
                     (->> (file-seq xray-src)
                          (filter #(.isFile ^java.io.File %))
                          (filter #(re-find #"\.clj[sc]?$" (.getName ^java.io.File %)))
                          (keep (fn [f]
                                  (when (some #(str/starts-with? (str %) "re-frame.story")
                                              (required-symbols (ns-form f)))
                                    (.getName ^java.io.File f))))
                          sort))]
      (is (.isDirectory xray-src)
          "the Xray source root exists, so an empty scan means no cycle")
      (is (empty? cycles)
          (str "Xray sources must not require re-frame.story.* — found: "
               (str/join ", " cycles))))))
