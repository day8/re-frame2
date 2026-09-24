(ns re-frame.impl-source-corpus
  "THE IMPLEMENTATION SOURCE CORPUS — every artefact's production
  `.clj` / `.cljc` / `.cljs` source, and the ONE definition of it that the
  source-scanning conformance ratchets share.

  ## Why this namespace exists

  Two ratchets scan this corpus for the emit / fan-out sites they govern:
  `re-frame.error-catalogue-channel-conformance-test` (every emitted
  `:rf.*` diagnostic category carries a Spec 009 catalogue row) and
  `re-frame.egress-chokepoint-conformance-test` (every always-on
  union-record fan-out caller routes through `project-egress`). They share
  this ONE definition, because two copies of an enumeration drift, and a
  fix to one copy leaves the other broken.

  The adapter artefacts nest one level deeper than the rest, at
  `implementation/adapters/<name>/src`. A DEPTH-1 enumeration
  (`.listFiles(implementation/)` mapped to `<child>/src`) would not treat
  them as roots and would never walk them. `implementation/adapters/*/src`
  DOES arm the `implementation` JVM tier, so on an adapter PR both suites
  would arm, run, walk past the adapter tree entirely, and report green —
  an uncatalogued `:rf.error/*` channel or an unrouted payload-bearing
  fan-out added in an adapter would pass both ratchets.

  `src-roots` therefore finds roots by RECURSIVE SEARCH for directories
  named `src`, which cannot drift when the next artefact nests, and
  `corpus-cross-check` is the floor that catches a narrowed walk — a
  sanity test asserting only `(seq roots)` is satisfied perfectly well by
  a walk that skips every adapter file.

  ## JVM-only

  This is `file-seq` + `slurp` over the repo, so only the JVM
  `clojure -M:test` runner can use it. It is a `test/` support namespace,
  not a test: it declares the namespace its path spells (the runner's
  discovery rule) and holds no `deftest`, so discovery loads it and
  reports nothing for it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- posix
  "`f`'s path with forward slashes, so one path predicate reads the same on
  Windows and POSIX."
  [^java.io.File f]
  (str/replace (.getPath f) "\\" "/"))

(def implementation-root
  "`implementation/`, resolved from the JVM test CWD.

  The JVM lane runs each artefact's tests from that artefact's own
  directory, so from `implementation/core/` the tree is `..` —
  the same anchor `no-rf-default-floor-lint-test` and
  `warn-once-clear-governance-test` use. The other candidates
  cover a REPL run from a different working directory.

  A candidate is accepted only if it actually CARRIES `core/src`. The walk
  below is recursive and so has a blast radius: a candidate of `../..` —
  the repo ROOT — would sweep `tools/`, `examples/` and `migration/` into
  two ratchets scoped to `implementation/`. Identifying the root positively
  is what makes the recursion safe."
  (->> ["../../implementation" ".." "../implementation"]
       (map io/file)
       (filter #(.isDirectory (io/file % "core" "src")))
       first))

(def src-roots
  "Every artefact's non-test source root under `implementation/`, found by
  recursive search for directories named `src`.

  Depth-independent BY CONSTRUCTION: `implementation/core/src`
  sits at depth 1, `implementation/adapters/reagent/src` at depth 2, and a
  future artefact may nest deeper still. A `src` directory reached through
  a `test/` path is not production source and is dropped."
  (when implementation-root
    (->> (file-seq implementation-root)
         (filter #(.isDirectory ^java.io.File %))
         (filter #(= "src" (.getName ^java.io.File %)))
         (remove #(str/includes? (posix %) "/test/"))
         (sort-by posix)
         vec)))

(def ^:private source-file-exts
  #{".clj" ".cljc" ".cljs"})

(defn- source-file?
  [^java.io.File f]
  (let [n (.getName f)]
    (boolean (some #(str/ends-with? n %) source-file-exts))))

(defn- test-source?
  "True for anything that is test material rather than production source.
  `src` roots carry only production source, so this is belt-and-braces
  against a root that ever nests a `test/` tree or a stray `*_test` file."
  [^java.io.File f]
  (let [p (posix f)
        n (.getName f)]
    (or (str/includes? p "/test/")
        (boolean (some #(str/ends-with? n (str "_test" %)) source-file-exts)))))

(defn non-test-source-files
  "Every production `.clj` / `.cljc` / `.cljs` file under the artefact src
  roots. A pure filesystem walk — no classpath load — so it sees every
  artefact regardless of which are on the running suite's classpath."
  []
  (->> src-roots
       (mapcat file-seq)
       (filter #(.isFile ^java.io.File %))
       (filter source-file?)
       (remove test-source?)))

(defn- path-shaped-corpus
  "The same corpus computed the OTHER way — the shape
  `no-rf-default-floor-lint-test` and `warn-once-clear-governance-test`
  use: walk `implementation/` whole and keep files whose path contains
  `/src/`. It never asks where a root is, so it cannot inherit a depth
  assumption from `src-roots`."
  []
  (->> (file-seq implementation-root)
       (filter #(.isFile ^java.io.File %))
       (filter source-file?)
       (filter #(str/includes? (posix %) "/src/"))
       (remove test-source?)))

(defn corpus-cross-check
  "Where the two enumerations DISAGREE, as
  `{:missing #{path…} :extra #{path…}}` — both empty when they agree.

  `:missing` is source the path-shaped walk finds and `src-roots` does not
  — against a depth-1 enumeration it names every adapter file. `:extra` is
  the converse — a root reaching outside `implementation/**/src/`.

  This is the coverage floor. A sanity test asserting `(seq roots)` and
  nothing about coverage passes a walk that skips an entire artefact
  family as readily as a correct one. Two enumerations of two different shapes over one corpus is
  a claim a silent narrowing cannot satisfy — and it also pins these two
  ratchets to the same corpus as the repo's other source-scanning lints,
  so the four cannot drift apart about what `implementation/` means."
  []
  (let [ours   (into #{} (map posix) (non-test-source-files))
        theirs (into #{} (map posix) (path-shaped-corpus))]
    {:missing (into (sorted-set) (remove ours) theirs)
     :extra   (into (sorted-set) (remove theirs) ours)}))
