(ns re-frame.story-substrate-isolation-test
  "JVM test pinning Story's substrate-isolation contract (rf2-k7zdq).

  Story's UI-shell substrate is Reagent (`003-Render-Shell.md` §UI shell substrate); per-variant
  multi-substrate rendering (UIx) is OPT-IN via
  `register-substrate!` from the consuming app at boot — Story core
  does NOT `:require` any UIx namespace. That contract means
  a host app can embed Story without dragging UIx into its
  classpath unless it elects to render variants under that
  substrate.

  This test walks every source file under `tools/story/src/` and
  asserts the contract — no source ns may `:require` a
  `uix.core` / `uix.dom` ns. References
  to the *keyword* `:uix` (substrate-ids in the enum,
  docstring callouts, sentinel comments) are permitted; what is
  forbidden is a fully-qualified namespace require that would pull
  the adapter onto Story's classpath.

  Companion to `implementation/scripts/check-bundle-isolation.cjs`
  which guards the OUTPUT side (counter bundle must not contain
  Story sentinel strings); this test guards the INPUT side (Story
  source must not require UIx nses)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ----- helpers ------------------------------------------------------------

(defn- src-root
  "Resolve `tools/story/src/` on disk, cwd-independently. The per-tool
  `:test` alias runs from `tools/story` (a cwd-relative `(io/file \"src\")`
  works), but the tools-root aggregate (`tools/deps.edn :test`, rf2-f2tkbt)
  runs from `tools/`, where a bare `src` would miss. `src` is a classpath
  `:paths` root, so a known story source (`re_frame/story.cljc`) is a
  classpath resource on the JVM regardless of cwd; its `src`-relative parent
  chain is the src-root. Falls back to the cwd-relative path if the resource
  is absent (e.g. a jar). Mirrors the xray guard tests' `src-root`."
  []
  (let [marker (io/resource "re_frame/story.cljc")]
    (if (and marker (= "file" (.getProtocol marker)))
      ;; .../src/re_frame/story.cljc → up to .../src
      (-> (io/file (.toURI marker)) .getParentFile .getParentFile)
      (io/file "src"))))

(defn- src-files
  "Walk tools/story/src/ and return every .cljc / .cljs / .clj file as
  a `java.io.File`, resolving the src root via `src-root` so the walk is
  cwd-independent (per-tool `clojure -M:test` AND the tools-root aggregate)."
  []
  (let [root (src-root)]
    (when (.isDirectory root)
      (->> (file-seq root)
           (filter #(.isFile ^java.io.File %))
           (filter (fn [^java.io.File f]
                     (let [n (.getName f)]
                       (or (str/ends-with? n ".cljc")
                           (str/ends-with? n ".cljs")
                           (str/ends-with? n ".clj")))))))))

(def ^:private forbidden-require-patterns
  "Namespace prefixes that, if `:require`-d from Story source, would
  drag the corresponding adapter onto Story's classpath. Reagent is
  intentionally NOT in this list — Story's UI shell IS Reagent per
  `003-Render-Shell.md` §UI shell substrate."
  [#"\[\s*uix\.core"
   #"\[\s*uix\.dom"])

(defn- offending-requires
  "Return a seq of `{:file path :match line}` for every forbidden
  require pattern found in `body`. Reads the file body as a single
  string; matches against require-form bracket prefixes that survive
  whitespace / newlines."
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
(rf2-k7zdq — multi-substrate is opt-in via register-substrate!)"
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
    (let [enum-file (io/file (src-root) "re_frame" "story" "schemas.cljc")
          body      (slurp enum-file)]
      (is (str/includes? body ":reagent"))
      (is (str/includes? body ":uix")))))
