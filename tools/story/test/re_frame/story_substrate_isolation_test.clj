(ns re-frame.story-substrate-isolation-test
  "JVM test pinning Story's substrate-isolation contract: the UI shell is
  Reagent (`003-Render-Shell.md` §UI shell substrate), and UIx rendering is
  opt-in through `register-substrate!` from the consuming app, so no Story
  source may `:require` a `uix.core` / `uix.dom` namespace — a host app
  embedding Story does not get UIx on its classpath. Keyword references to
  `:uix` are fine. `implementation/scripts/check-bundle-isolation.cjs` guards
  the output side; this guards the input side."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ----- helpers ------------------------------------------------------------

(defn- src-root
  "`tools/story/src/` on disk, found from a known source on the classpath so
  the walk is independent of the working directory (a REPL rooted at the repo
  root included). Falls back to the cwd-relative path when the resource is
  not a file; the `(is (seq files))` below turns a mis-resolved root into a
  loud failure rather than a vacuous pass."
  []
  (let [marker (io/resource "re_frame/story.cljc")]
    (if (and marker (= "file" (.getProtocol marker)))
      ;; .../src/re_frame/story.cljc → up to .../src
      (-> (io/file (.toURI marker)) .getParentFile .getParentFile)
      (io/file "src"))))

(defn- src-files
  "Every .cljc / .cljs / .clj file under `src-root`."
  []
  (let [root (src-root)]
    (when (.isDirectory root)
      (->> (file-seq root)
           (filter #(.isFile ^java.io.File %))
           (filter (fn [^java.io.File f]
                     (some #(str/ends-with? (.getName f) %) [".cljc" ".cljs" ".clj"])))))))

(def ^:private forbidden-require-patterns
  "Namespace prefixes that, if `:require`-d from Story source, would
  drag the corresponding adapter onto Story's classpath. Reagent is
  intentionally NOT in this list — Story's UI shell IS Reagent per
  `003-Render-Shell.md` §UI shell substrate."
  [#"\[\s*uix\.core"
   #"\[\s*uix\.dom"])

(defn- offending-requires
  "`{:file :match :pattern}` for each forbidden require pattern found in `f`'s body."
  [^java.io.File f]
  (let [body (slurp f)
        path (.getPath f)]
    (for [pat   forbidden-require-patterns
          :let  [m (re-find pat body)]
          :when m]
      {:file path :match m :pattern (str pat)})))

;; ----- the contract test --------------------------------------------------

(deftest story-source-must-not-require-uix
  (testing "no namespace under tools/story/src/ may :require uix.*
(multi-substrate is opt-in via register-substrate!)"
    (let [files     (src-files)
          offences  (mapcat offending-requires files)]
      (is (seq files) "expected to find source files under tools/story/src/")
      (is (empty? offences)
          (str "Story source files require forbidden UIx namespaces:\n"
               (str/join "\n" (map (fn [{:keys [file pattern match]}]
                                     (str "  " file
                                          "  (pattern " pattern
                                          " matched " (pr-str match) ")"))
                                   offences))
               "\n\nPer `002-Runtime.md` §Substrate hooks + `003-Render-Shell.md` §UI shell substrate Story's UI shell is Reagent; UIx "
               "substrates plug in at boot via "
               "`re-frame.story.ui.multi-substrate/register-substrate!`. "
               "Story core MUST NOT drag those adapters onto its classpath."))))

  (testing "the substrate enum still advertises :reagent + :uix
(consumer-app registration surface — keyword refs only, not requires)"
    ;; Validate against the schema VALUE: a substring probe of
    ;; schemas.cljc is satisfied by the docstring alone.
    (is (m/validate rf.story.schemas/SubstrateSet #{:reagent}))
    (is (m/validate rf.story.schemas/SubstrateSet #{:uix}))))
