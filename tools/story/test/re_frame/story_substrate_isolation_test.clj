(ns re-frame.story-substrate-isolation-test
  "JVM test pinning Story's substrate-isolation contract (rf2-k7zdq).

  Story's UI-shell substrate is Reagent (IMPL-SPEC §8); per-variant
  multi-substrate rendering (UIx / Helix) is OPT-IN via
  `register-substrate!` from the consuming app at boot — Story core
  does NOT `:require` any UIx or Helix namespace. That contract means
  a host app can embed Story without dragging UIx / Helix into its
  classpath unless it elects to render variants under those
  substrates.

  This test walks every source file under `tools/story/src/` and
  asserts the contract — no source ns may `:require` a
  `uix.core` / `uix.dom` / `helix.core` / `helix.dom` ns. References
  to the *keywords* `:uix` / `:helix` (substrate-ids in the enum,
  docstring callouts, sentinel comments) are permitted; what is
  forbidden is a fully-qualified namespace require that would pull
  the adapter onto Story's classpath.

  Companion to `implementation/scripts/check-bundle-isolation.cjs`
  which guards the OUTPUT side (counter bundle must not contain
  Story sentinel strings); this test guards the INPUT side (Story
  source must not require UIx/Helix nses)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ----- helpers ------------------------------------------------------------

(defn- src-files
  "Walk tools/story/src/ and return every .cljc / .cljs / .clj file as
  a `java.io.File`. The classpath element `src` resolves to the same
  directory whether tests run via `clojure -M:test` from `tools/story`
  or via the top-level shadow build."
  []
  (let [root (io/file "src")]
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
  IMPL-SPEC §8."
  [#"\[\s*uix\.core"
   #"\[\s*uix\.dom"
   #"\[\s*helix\.core"
   #"\[\s*helix\.dom"
   #"\[\s*helix\.hooks"])

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

(deftest story-source-must-not-require-uix-or-helix
  (testing "no namespace under tools/story/src/ may :require uix.* / helix.*
(rf2-k7zdq — multi-substrate is opt-in via register-substrate!)"
    (let [files     (src-files)
          offences  (mapcat offending-requires files)]
      (is (seq files) "expected to find source files under tools/story/src/")
      (is (empty? offences)
          (str "Story source files require forbidden UIx / Helix namespaces:\n"
               (str/join "\n" (map (fn [{:keys [file pattern match]}]
                                     (str "  " file
                                          "  (pattern " pattern
                                          " matched " (pr-str match) ")"))
                                   offences))
               "\n\nPer IMPL-SPEC §2.2 + §8 Story's UI shell is Reagent; UIx and "
               "Helix substrates plug in at boot via "
               "`re-frame.story.ui.multi-substrate/register-substrate!`. "
               "Story core MUST NOT drag those adapters onto its classpath."))))

  (testing "the substrate enum still advertises :reagent + :uix + :helix
(consumer-app registration surface — keyword refs only, not requires)"
    (let [enum-file (io/file "src/re_frame/story/schemas.cljc")
          body      (slurp enum-file)]
      (is (str/includes? body ":reagent"))
      (is (str/includes? body ":uix"))
      (is (str/includes? body ":helix")))))
