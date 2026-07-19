(ns re-frame.story.xray-dependency-honesty-test
  "Dependency-honesty gate for Story's Xray coupling (rf2-r8trk).

  ## The bug this pins

  Story's shipped shell hard-`:require`s `day8.re-frame2-xray.*`
  namespaces from three sources — `re-frame.story.ui.xray-embed`
  (mount + panels), `re-frame.story.xray-preset` (mount + config +
  keybinding), and `re-frame.story.ui.evidence-spine` (core). But
  `tools/story/deps.edn` declared no `day8/re-frame2-xray` dependency.

  The repository-wide Shadow build masked the omission by carrying
  `../tools/xray/src` on its GLOBAL `:source-paths`, so every in-repo
  build compiled fine. A fresh consumer whose only tool dependency was
  `day8/re-frame2-story` could not compile the shell at all:

      No such namespace: day8.re-frame2-xray.mount
      ... in re_frame/story/xray_preset.cljc

  ## What this test asserts

  Every `day8.*` namespace Story's own sources hard-require must be
  reachable from Story's OWN declared dependency graph — the
  `:local/root` entries in `tools/story/deps.edn` — and not from a
  path some outer build happens to supply.

  ## The leak control

  `required-day8-namespaces` is computed by parsing each source file's
  `ns` form (not by grepping, which is blind to a require split across
  a line wrap), and `declared-source-roots` is computed from
  `deps.edn` alone. Resolution NEVER consults the live classpath, so a
  leaked `tools/xray/src` entry cannot satisfy the assertion: delete
  the `day8/re-frame2-xray` entry from `deps.edn` and this test reds
  even though the repository Shadow build still compiles. That is
  exactly the regression rf2-r8trk fixed.

  This is a JVM-only `.clj` test: it reads files off disk, so it runs
  under `clojure -M:test` from `tools/story` and is invisible to the
  shadow-cljs `:node-test` build."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.set :as set]
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

(deftest xray-is-declared-in-story-deps
  (testing "Story's shell hard-requires Xray, so `day8/re-frame2-xray`
            must be a declared dependency — not something an outer
            build's global :source-paths happens to supply."
    (let [coord (get-in (read-deps) [:deps 'day8/re-frame2-xray])]
      (is (some? coord)
          "tools/story/deps.edn must declare day8/re-frame2-xray in its
           main :deps — without it a consumer whose only tool dependency
           is day8/re-frame2-story cannot compile the Story shell")
      (is (= "../xray" (:local/root coord))
          "the dep rides tools/xray at :local/root during development;
           the release workflow rewrites it to :mvn/version, which is
           what keeps Story and Xray lockstep-versioned"))))

(deftest every-required-day8-namespace-is-declared
  (testing "LEAK CONTROL — every `day8.*` namespace Story's sources
            require must resolve under a root Story itself declares.
            Resolution reads deps.edn + the file system only, so a
            leaked tools/xray/src on some outer classpath cannot
            satisfy it: drop the day8/re-frame2-xray entry and this
            reds, even though the repository Shadow build still
            compiles."
    (let [roots    (vals (declared-source-roots (read-deps)))
          required (required-day8-namespaces)
          missing  (into (sorted-map)
                         (remove (fn [[sym _]] (resolvable-under? roots sym)))
                         required)]
      (is (seq required)
          "sanity: Story's sources must require at least one day8.*
           namespace — an empty set would make this gate vacuous")
      (is (= {} (into {} missing))
          (str "these namespaces are required by Story's sources but are "
               "NOT reachable from any :local/root declared in "
               "tools/story/deps.edn: "
               (str/join ", "
                         (map (fn [[sym files]]
                                (str sym " (required by " (str/join ", " (sort files)) ")"))
                              missing)))))))

(deftest xray-namespaces-resolve-under-the-declared-xray-root
  (testing "the specific Xray namespaces the shell composes resolve
            under the DECLARED xray root — this is the compile the bead
            proved impossible before the dep was declared."
    (let [xray-root (get (declared-source-roots (read-deps)) 'day8/re-frame2-xray)
          required  (->> (required-day8-namespaces)
                         keys
                         (filter #(str/starts-with? (str %) "day8.re-frame2-xray."))
                         sort)]
      (is (some? xray-root)
          "the declared xray root must resolve to a src directory")
      (is (seq required)
          "sanity: the shell must hard-require at least one Xray namespace")
      (doseq [sym required]
        (is (resolvable-under? [xray-root] sym)
            (str sym " must resolve under the declared day8/re-frame2-xray root"))))))

(deftest story-does-not-depend-on-story-from-xray
  (testing "the coupling is one-way Story → Xray. If Xray ever required
            Story back, the two artefacts would form a dependency cycle
            and neither could be published."
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
      (is (empty? cycles)
          (str "Xray sources must not require re-frame.story.* — found: "
               (str/join ", " cycles))))))

;; Sibling gap NOT fixed here (rf2-r8trk is scoped to the dependency
;; graph): `re-frame.story.xray-preset/filters-available?` probes
;; `day8.re-frame2-xray.filters.config/configure!`, a namespace Xray
;; does not expose. That detect is therefore always false and Story's
;; `:xray {:filters …}` preset slot never applies. Filed separately.
(deftest filters-config-namespace-is-genuinely-absent
  (testing "documents WHY xray-preset keeps one real feature-detect:
            Xray exposes no filters.config namespace, so unlike the
            mount/config/keybinding surfaces this one cannot become a
            direct :require. If Xray ever adds it, retire the probe."
    (let [xray-root (get (declared-source-roots (read-deps)) 'day8/re-frame2-xray)]
      (is (not (resolvable-under? [xray-root] 'day8.re-frame2-xray.filters.config))
          "if this reds, day8.re-frame2-xray.filters.config now exists —
           replace xray-preset's resolve-fn probe with a direct :require
           and delete filters-available?"))))
