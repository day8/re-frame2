(ns re-frame.hicasso.examples.fence-cljs-test
  "EVERY WITNESS APPLICATION UNDER `examples/` STAYS ON THE PUBLIC DOOR.

  The applications under `examples/` are evidence about the public door,
  and each is worth exactly the claim that it was written on that door and
  reaches nothing else: no `re-frame.hicasso.impl.*`, no benchmark tree,
  no development tool, no test kit. This file is that claim for all of
  them at once.

  ## The population is the directory, read on every run

  A fence over a hand-written roster fences a namespace minted tomorrow
  only if somebody remembers to type it, and a fence per package fences a
  package added tomorrow only if somebody remembers to write one — which
  is how `navigation` sat unfenced for a day beside four sibling fences
  (rf2-689l). So nothing here is typed. [[packages]] walks the examples
  roots on disk: every immediate subdirectory holding application code is
  a package; every `.cljs` / `.cljc` under it whose stem does not end in
  `cljs-test` is application code (the build's own suite rule,
  `:node-test`'s `cljs-test$`); and its dependencies are read off its `ns`
  form with the ClojureScript reader — every `:require`,
  `:require-macros`, `:use` and `:use-macros` libspec, which is the whole
  of what a ClojureScript namespace can depend on.

  It reads at RUN time, in the node lane, rather than at macro-expansion
  time. A macro's expansion is cached against the sources on its own
  dependency chain, and a package's files are not on this namespace's
  chain by construction, so an expansion-time read would keep answering
  from a warm cache after a forbidden require was planted. A run-time read
  has no cache to go stale in: the plant is red on the next run.

  ## Controls

  [[the-population-is-derived-and-non-empty]] refuses an empty walk (a
  moved tree would otherwise pass every fence vacuously) and requires the
  read to have produced the one edge every application has, a namespace
  depending on `re-frame.hicasso`. [[every-fence-predicate-fires]] shows
  each family one name it must catch and two it must not.
  [[a-planted-breach-is-named]] pushes an `ns` form carrying forbidden
  requires through the same functions the live row uses, so the pipeline
  is known to fire before [[no-package-reaches-past-the-public-door]] says
  it found nothing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.tools.reader :as reader]
            [cljs.tools.reader.reader-types :as reader-types]
            [clojure.string :as str]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(def ^:private roots
  "Where witness packages live, relative to the runner's working directory
  (`implementation/`, where `npm run test:cljs` runs).

  ONE root, and it exists. A second was listed here until 2026-09-04 —
  `hicasso/test_kit/test/re_frame/hicasso/examples`, on the reasoning that
  `shadow-cljs.edn` puts `hicasso/test_kit/test` on `:source-paths` so a
  witness could be written there while `hicasso/test` was held. That
  source path is real, but no `examples` directory has ever existed under
  it, so the entry named a root that was not there and the `existsSync`
  guard below made its absence silent (rf2-60jv). It is dropped rather
  than kept as a forward declaration: this fence is about what the tree
  has, and a speculative root is the kind of unpaid-for apparatus
  `rf2-6c12m` was opened to remove. **If a witness package is ever written
  under `test_kit/test`, add its root back here in the same commit** —
  nothing else fences it, which is the `navigation` failure above."
  ["hicasso/test/re_frame/hicasso/examples"])

(defn- source-files
  "Every `.cljs` / `.cljc` under `dir`, recursively."
  [dir]
  (for [entry (.readdirSync fs dir #js {:withFileTypes true})
        :let  [full (.join path dir (.-name entry))]
        file  (cond (.isDirectory entry)                      (source-files full)
                    (re-find #"\.clj[cs]$" (.-name entry))    [full])]
    file))

(defn- suite?
  "Is `file` a test suite, by the build's own rule?"
  [file]
  (str/ends-with? (str/replace (.-name (.parse path file)) "_" "-") "cljs-test"))

(defn- ns-form
  "The first form of `file`, read as ClojureScript — `#?` conditionals
  resolved for `:cljs`, so a `.cljc` reads as the compiler would read it."
  [file]
  (reader/read {:read-cond :allow :features #{:cljs} :eof nil}
               (reader-types/string-push-back-reader (.readFileSync fs file "utf8"))))

(defn- dependencies
  "Every namespace `form` names in a `:require`, `:require-macros`, `:use`
  or `:use-macros` clause, as strings — or nil when `form` is not an `ns`
  form, so a file the reader could not place is reported rather than
  fenced vacuously. A libspec is a bare symbol or a vector opening with
  one; a string (an npm module) names no namespace."
  [form]
  (when (and (seq? form) (= 'ns (first form)))
    (into (sorted-set)
          (comp (filter seq?)
                (filter #(#{:require :require-macros :use :use-macros} (first %)))
                (mapcat rest)
                (keep #(cond (symbol? %) % (and (vector? %) (symbol? (first %))) (first %)))
                (map str))
          (rest form))))

(defn- packages
  "`{package {file dependencies}}` for every package under [[roots]]: an
  immediate subdirectory holding at least one application source. A deeper
  directory belongs to the package above it."
  []
  (reduce (fn [acc [package files]] (update acc package merge files))
          (sorted-map)
          (for [root  roots
                :when (.existsSync fs root)
                entry (.readdirSync fs root #js {:withFileTypes true})
                :when (.isDirectory entry)
                :let  [files (remove suite? (source-files (.join path root (.-name entry))))]
                :when (seq files)]
            [(.-name entry)
             (into (sorted-map)
                   (map (fn [f] [(str/replace (.relative path root f) "\\" "/")
                                 (dependencies (ns-form f))]))
                   files)])))

(def ^:private population (delay (packages)))

(def ^:private forbidden
  "The four families application code may not name, each with the reason
  a failure should print. Predicates rather than a list, so a namespace
  minted tomorrow is fenced the day it lands."
  [{:label  "a Hicasso internal"
    :why    "the application is evidence about the public door; a reach past it makes the evidence worth nothing"
    :match? #(str/starts-with? % "re-frame.hicasso.impl.")}
   {:label  "the benchmark tree"
    :why    "`re-frame.bench.*` is the measured prototype the package was moved out of; a consumer has no access to it"
    :match? #(str/starts-with? % "re-frame.bench.")}
   {:label  "a development tool"
    :why    "`tools/` is bundle-isolated from production builds; nothing in `implementation/` may require it, and an application is not an exception"
    :match? #(or (str/starts-with? % "re-frame.xray")
                 (str/starts-with? % "re-frame.story")
                 (str/starts-with? % "re-frame.machines-viz"))}
   {:label  "the test kit"
    :why    "`re-frame.hicasso.test` and its mounted facade are dev/test surfaces off the artefact's published `:paths`; an application that required one would not ship"
    :match? #(or (= % "re-frame.hicasso.test")
                 (str/starts-with? % "re-frame.hicasso.test."))}])

(defn- breaches
  "`[package file dependency family why]` for every forbidden edge in
  `packages`, so a failure names the file to open."
  [packages]
  (vec (for [[package files]            packages
             [file deps]                files
             dep                        deps
             {:keys [label why match?]} forbidden
             :when                      (match? dep)]
         [package file dep label why])))

(deftest the-population-is-derived-and-non-empty
  (let [pop @population]
    (testing "at least one witness package was found under the examples roots"
      (is (pos? (count pop))
          (str "no witness package under " (pr-str roots) " — has the examples "
               "tree moved, or is the runner's working directory not `implementation/`?")))
    (testing "every application source opens with an `ns` form the reader could read"
      (doseq [[_ files] pop, [file deps] files]
        (is (some? deps) (str file " does not open with an `ns` form, so nothing fences it"))))
    (testing "the read produced the one edge every application has"
      (is (some (fn [[_ files]] (some (fn [[_ deps]] (contains? deps "re-frame.hicasso")) files)) pop)
          "no application namespace depends on `re-frame.hicasso`; the reader is not producing edges"))
    (println (str "hicasso example fence: " (count pop) " packages read off the directory: "
                  (str/join ", " (keys pop))))))

(deftest every-fence-predicate-fires
  ;; THE SABOTAGE CONTROL. The fence is green today because the applications
  ;; are clean, and it would be just as green if a predicate had been written
  ;; wrong — `"re-frame.hicasso.impl"` without the trailing dot, say. So each
  ;; is shown one name it must catch and two it must not, and the rows that
  ;; matter are the second and third: a fence that swallowed the public door
  ;; or core would fail every application rather than protect one.
  (let [caught {"a Hicasso internal" "re-frame.hicasso.impl.collector"
                "the benchmark tree" "re-frame.bench.hicasso.front.codec"
                "a development tool" "re-frame.xray.mount"
                "the test kit"       "re-frame.hicasso.test.mounted"}]
    (is (= (set (map :label forbidden)) (set (keys caught)))
        "every family has a sabotage row, and no row lacks a family")
    (doseq [{:keys [label match?]} forbidden]
      (is (true? (boolean (match? (caught label))))
          (str "the " label " predicate does not catch " (pr-str (caught label))))
      (is (false? (boolean (match? "re-frame.hicasso")))
          (str "the " label " predicate catches the PUBLIC DOOR"))
      (is (false? (boolean (match? "re-frame.core")))
          (str "the " label " predicate catches core")))))

(deftest a-planted-breach-is-named
  ;; The pipeline control: an `ns` form carrying two forbidden requires and
  ;; the public door, through the same reader and predicates the live row
  ;; uses. Both plants are named by package; the door is not.
  (let [form  '(ns re-frame.hicasso.examples.planted.views
                 (:require [re-frame.hicasso :as rf.hicasso]
                           [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector])
                 (:require-macros [re-frame.hicasso.test.mounted :as rf.hicasso.test.mounted]))
        found (breaches {"planted" {"planted/views.cljs" (dependencies form)}})]
    (is (= #{"re-frame.hicasso.impl.collector" "re-frame.hicasso.test.mounted"}
           (into #{} (map #(nth % 2)) found)))
    (is (every? #(= "planted" (first %)) found) "each breach names its package")))

(deftest no-package-reaches-past-the-public-door
  (doseq [[package files] @population]
    (testing package
      (is (= [] (breaches {package files}))
          (str package " reaches past the public door; each row reads "
               "[package file dependency family why]")))))
