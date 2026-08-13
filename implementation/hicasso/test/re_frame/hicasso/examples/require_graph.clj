(ns re-frame.hicasso.examples.require-graph
  "READ A WITNESS APPLICATION'S DEPENDENCY EDGES OFF THE ANALYZER
  (rf2-hic-025, rf2-hic-086).

  Both witness applications under `examples/` are evidence about the
  PUBLIC DOOR, and each is worth exactly as much as the claim that it was
  written on that door and nothing else. Their beads spell the claim the
  same way — *zero `impl` imports in app code, enforced by a test* — and
  a test can enforce it in three ways, only one of which is worth having:

  - **Read the `:require` list and believe it.** That is not a test.
  - **Grep the source text.** Better, and still a claim about
    characters: a runtime `(require …)`, an alias introduced by a
    `:refer`, or a namespace reached through `:use-macros` all evade a
    regex over the `ns` form, and a comment mentioning `impl` trips one.
  - **Ask the compiler.** ClojureScript's analyzer holds, for every
    analysed namespace, the set of namespaces it actually depends on —
    `:requires`, `:require-macros`, `:uses`, `:use-macros`. That set is
    what the build was built from. It is the same instrument
    `re-frame.api-manifest.cljs-publics` reads the live public surface
    from, and it is read the same way: from a `.clj` macro, off
    `cljs.env/*compiler*`, emitted into the calling ClojureScript as a
    literal.

  ## Why the emitted value cannot go stale

  Inlining at macro-expansion time normally hides the inlined thing from
  the build. It does not here, and the reason is structural rather than
  careful: the calling test namespace `:require`s every namespace it
  asks about, so shadow-cljs already has a dependency edge from the test
  to each of them. Change what `views.cljs` requires and `views` is
  recompiled, and the test namespace that depends on it is recompiled
  too — with this macro re-expanded against the new graph.

  ## Asking the compiler about WHICH namespaces (rf2-ccuw)

  A predicate over the wrong population proves nothing about the
  application, and for three releases the population was a hand-written
  vector — written twice per consumer, once as the roster and once as
  the argument to the macro below. So a namespace added to an
  application tomorrow was compiled, was loaded, was part of the
  application, and was invisible to every assertion in the file. Nothing
  went red. The claim each consumer makes silently narrowed from *the
  application* to *these seven files*, and rf2-hic-074 had already
  extended one of them after its report was written.

  So the population is asked for too. [[application-namespaces]] reads
  the package's own directory off the JVM classpath at expansion time
  and answers every ClojureScript source under it — the same walk
  shadow-cljs does to find them, so a file that the build can compile is
  a file this macro can see. The test-suite exclusion is the BUILD's own
  rule rather than a second one to keep in step: `:node-test` selects
  `cljs-test$`, so a namespace ending that way is a suite and not
  application code.

  ## The one thing the derived population cannot do

  It can only report a namespace the ANALYZER also knows about, and the
  analyzer knows a namespace because something on the calling test's
  `:require` chain reached it. That is not a hole, because it is
  reported: [[emit-dependency-graph]] omits a namespace the analyzer has
  no record of, so the graph's key set is narrower than the directory's
  and each consumer's `the-graph-is-populated` compares the two and
  names the file to add. A namespace that is genuinely part of an
  application is on that chain by construction — that is what wiring it
  in means — and the commit that wires it recompiles the consumer.

  ## Why it sits at the `examples/` root (rf2-urgk)

  It was written twice. rf2-hic-025 wrote it for the slice, rf2-hic-086
  wrote it again for the Todo witness, and neither branch could
  `:require` the other's namespace because the two beads ran in parallel
  off one base. Both copies were the same file bar the docstring. This
  is the one copy, at the root both applications sit under, consumed by
  all three `surface-cljs-test` namespaces.

  ## Scope

  Macro-expansion only, and JVM-only by construction (a `.clj`). It
  matches no test `ns-regexp` — `:node-test` selects `cljs-test$`,
  `:browser-test` selects `-dom-cljs-test$` — so it is compiled as a
  dependency of its consumers and run nowhere."
  (:require [cljs.analyzer.api :as ana-api]
            [cljs.env :as env]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.net URL)))

(defn dependency-edges
  "The set of namespace symbols `ns-sym` depends on, read off the
  analyzer compilation env `state`.

  All four edge kinds, unioned: `:requires` and `:uses` are the
  ClojureScript ones, `:require-macros` and `:use-macros` the Clojure
  ones a `:require-macros` / `(:require … :refer-macros)` establishes. A
  check that read only `:requires` would be blind to exactly the door a
  macro reaches through — which matters here, because `h/defview` IS a
  macro and the door is reached both ways.

  Three edges are dropped, and all three are the COMPILER's rather than
  the author's: the namespace's own name (the analyzer records a
  self-edge for the alias-less form), `cljs.core`, and `goog` — the
  Closure Library root every analysed ClojureScript namespace carries
  whether or not a line of its source mentions it. Nothing else is
  filtered; in particular no `re-frame.*` edge is, which is the half a
  filter could quietly hollow out.

  A plain function rather than macro-only code so it is readable, and so
  its filtering is one expression a reader can check."
  [state ns-sym]
  (let [info (ana-api/find-ns state ns-sym)]
    (into (sorted-set)
          (remove #{ns-sym 'cljs.core 'cljs.core$macros 'goog})
          (concat (vals (:requires info))
                  (vals (:require-macros info))
                  (vals (:uses info))
                  (vals (:use-macros info))))))

(defn- package-directories
  "Every directory on the JVM classpath that holds `rel-path`.

  Plural, and deliberately: `io/resource` answers the FIRST root a
  relative path resolves under, and this repository has more than one
  test root by design — `hicasso/test_kit/test` exists precisely so a
  witness can be written while `hicasso/test` is held by another branch.
  A single-root lookup would narrow the population silently, which is
  the bug this whole namespace exists to stop having."
  [rel-path]
  (->> (.getResources (.getContextClassLoader (Thread/currentThread)) rel-path)
       enumeration-seq
       (filter #(= "file" (.getProtocol ^URL %)))
       (map io/file)
       (filter #(.isDirectory ^File %))))

(defn application-namespaces
  "Every APPLICATION namespace under the package `package-sym`, read off
  the classpath rather than typed.

  ClojureScript sources only (`.cljs`, `.cljc`), walked recursively so a
  subdirectory cannot escape, and test suites excluded by the build's own
  rule: `:node-test`'s `ns-regexp` is `cljs-test$`, so a namespace ending
  that way is a suite. Test suites are held to different rules than the
  application on purpose — a test may reach past a door the application
  may not.

  Throws rather than answers empty. An empty population passes every
  fence VACUOUSLY, and a macro that returns `[]` because a source root
  moved would turn three suites green while proving nothing."
  [package-sym]
  (let [package  (name package-sym)
        rel-path (-> package (str/replace "." "/") (str/replace "-" "_"))
        dirs     (package-directories rel-path)
        nss      (into (sorted-set)
                       (for [^File dir  dirs
                             ^File file (file-seq dir)
                             :when      (.isFile file)
                             :let       [rel (-> (.toPath dir)
                                                 (.relativize (.toPath file))
                                                 str
                                                 (str/replace "\\" "/"))
                                         [_ stem ext] (re-matches #"(.+)\.([^./]+)" rel)]
                             :when      (and stem (#{"cljs" "cljc"} ext))
                             :let       [ns-name (str package "."
                                                      (-> stem
                                                          (str/replace "/" ".")
                                                          (str/replace "_" "-")))]
                             :when      (not (str/ends-with? ns-name "cljs-test"))]
                         (symbol ns-name)))]
    (when (empty? nss)
      (throw (ex-info (str "no application namespace found for package " package
                           " — the fence would pass vacuously")
                      {:package package :classpath-path rel-path :directories (mapv str dirs)})))
    (vec nss)))

(defmacro emit-application-namespaces
  "Expand to a literal sorted vector of `\"<namespace>\"` strings: every
  application namespace under package `package-sym`, as the package
  DIRECTORY has them.

  This is the population, and it is the half [[emit-dependency-graph]]
  is checked against — a name here with no entry there is a file the
  analyzer never saw."
  [package-sym]
  (mapv str (application-namespaces package-sym)))

(defmacro emit-dependency-graph
  "Expand to a literal `{\"<namespace>\" [\"<dependency>\" …]}` map for
  every application namespace under package `package-sym`, read from the
  ClojureScript analyzer's compilation environment.

  A namespace the analyzer has no record of is OMITTED rather than
  emitted with an empty edge set: an empty edge set is indistinguishable
  from a clean namespace and passes every fence vacuously, whereas an
  absent key is a difference the consumer's `the-graph-is-populated`
  prints against [[emit-application-namespaces]].

  Strings rather than symbols so the emitted value is plain data a
  failing assertion prints legibly, and sorted so a report is stable."
  [package-sym]
  (into (sorted-map)
        (keep (fn [s]
                (when (ana-api/find-ns env/*compiler* s)
                  [(str s) (mapv str (dependency-edges env/*compiler* s))])))
        (application-namespaces package-sym)))
