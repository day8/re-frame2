(ns re-frame.prod-gate-naming-drift-test
  "A namespace whose NAME claims the production/debug gate must
  either reach that gate for real, or say out loud that it does not.

  ## The failure mode this closes

  `re-frame.interop/debug-enabled?` is read ONCE, at namespace-load time, from
  `-Dre-frame.debug` / `RE_FRAME_DEBUG`. A suite that reaches it with
  `with-redefs` runs AFTER the framework has loaded and cannot change one
  thing the gate decided at load — it pins a rebindable Var, not a posture.

  A load-order defect can make `dispatch-sync` fail TOTALLY under the
  documented production gate — handler run ZERO times — while a suite that
  calls itself a \"production gate\" test through `with-redefs` stays
  green. The roster of such suites can look full, and a reviewer reading the
  file list has no way to see whether any of them runs under the gate. The
  names are the camouflage.

  A docstring decays; this test is what stops a misleading name being written.

  ## Why the walk spans every artefact, recursively

  A walk confined to one artefact's test tree, or to depth 1, narrows
  invisibly: a floor on whether the walk found ANYTHING says nothing about
  whether it found EVERYTHING.

  Claiming files sit outside core. `implementation/epoch/test/re_frame/`
  `epoch_jvm_prod_gate_test.clj` NAMES the JVM production gate in its filename
  and pins it entirely with `with-redefs`, so a core-only walk would never
  judge it. It is a tree this ratchet is responsible for, too: an epoch test
  file arms `implementation_jvm` (`.github/scripts/report-changed-surfaces.sh`),
  which is the SAME condition `jvm-core` gates on, so a core-only walk would be
  armed, run, walk past `implementation/epoch/` and report green. Two more
  artefacts (`routing`, `ssr`) carry claiming files too.

  A cross-reference is not a check either: one suite's docstring saying
  another \"carries the same caveat\" proves nothing about the other suite.

  So the domain is every artefact's `test/` tree, walked recursively from
  one positively-identified root. See `implementation-root` for why the root is
  identified by what it CARRIES rather than by where it sits.

  Two properties of the walk are CHECKED rather than CLAIMED. Its reach: a
  count of artefacts and files over a corpus this lopsided passes with any one
  of the small trees dropped, so reach is pinned by name
  (`required-artefacts`). And its exclusion of build output: a filter applied
  after `file-seq` has already descended through a tree costs the walk
  everything it claims to save, so pruning happens at descent
  (`source-bearing-dir?`). A claim in a docstring is not a property of the
  code, which is the whole thesis of this namespace turned back on itself.

  ## The rule

  DOMAIN — every `.clj` / `.cljc` file under any artefact's `test/` tree
  beneath `implementation/`, whose FILE NAME contains `prod_gate`, `jvm_gate`,
  or `debug_gate`. Those three tokens are the ones that assert, in the file
  listing itself, \"this exercises the JVM production/debug gate\".
  Deliberately narrow in the TOKEN dimension: `trace_gate` and the
  `prod_elision` suites make a different claim and are out of scope. Not narrow
  in the TREE dimension, for the reasons above.

  A file in the domain is HONEST when it does at least one of:

    a. carries `^:prod-gate` metadata — it belongs to a real prod-gate lane,
       which puts `-Dre-frame.debug=false` on the JVM command line via that
       artefact's `:prod-gate` alias `:jvm-opts` (`jvm-core-prod-gate` and its
       `epoch` / `routing` / `ssr` siblings);
    b. contains the literal `-Dre-frame.debug=false` — it relaunches a child
       JVM with the property on the command line (the
       `re-frame.prod-gate-dispatch-jvm-test` pattern);
    c. contains the disclaimer sentinel below — it states in its own docstring
       that it is NOT THE LOAD-TIME GATE, so the file listing stops lying.

  This namespace satisfies its own rule through (c): the sentinel is `def`'d
  below, and this suite makes no claim to run under any particular posture.

  ## Posture-independence

  Every assertion here is a pure filesystem + string check. It holds in dev
  posture and under `-Dre-frame.debug=false` alike, so this namespace runs in
  the ordinary `clojure -M:test` suite AND joins `jvm-core-prod-gate`
  automatically (that lane's roster is an EXCLUSION list — a new namespace
  joins by default)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private claim-tokens
  "File-name substrings that CLAIM the JVM production/debug gate."
  ["prod_gate" "jvm_gate" "debug_gate"])

(def ^:private disclaimer
  "The sentinel a claiming suite writes into its ns docstring to say it does
  not reach the load-time gate. Kept as one exact string so the disclaimer is
  greppable rather than a paraphrase every author reinvents."
  "NOT THE LOAD-TIME GATE")

(def ^:private prod-gate-tag "^:prod-gate")
(def ^:private jvm-property "-Dre-frame.debug=false")

(def ^:private required-artefacts
  "The artefacts this walk MUST reach — a required SUBSET, not a census.

  A count-shaped guard — `(> artefact-count 1)`, `(>= file-count 8)` — is
  not a guard on reach: the tracked corpus is core=7, epoch=2, routing=1,
  ssr=1, so dropping routing OR ssr leaves 10 files across 3 artefacts
  (dropping epoch, 9) and BOTH assertions stay green while the honesty test
  goes blind to the omitted tree.

  A SUBSET rather than an equality: a new artefact with a claiming file must
  be scanned the moment it lands, and having to edit this set first would be
  the failure mode inverted — the walk narrowed by a red that looks like the
  gate working. So the set below is a floor on reach, and growth is silent by
  design. Removing an artefact from the repo is what edits it."
  #{"core" "epoch" "routing" "ssr"})

(defn- posix
  "`f`'s path with forward slashes, so one path predicate reads the same on
  Windows and POSIX."
  [^java.io.File f]
  (str/replace (.getPath f) "\\" "/"))

(defn- core-test-anchor-dir
  "The on-disk `implementation/core/test/re_frame` directory, resolved off the
  CLASSPATH rather than the process CWD — `clojure -M:test` and the
  `jvm-core-prod-gate` lane both run from `implementation/core`, but nothing
  guarantees a third caller will. Anchored on a file that exists ONLY under
  `test/` so the `src/` `re_frame` directory cannot win the lookup.

  This is not the tree that gets walked; it is how the tree that DOES get
  walked is found."
  ^java.io.File []
  (some-> (io/resource "re_frame/prod_gate_lane_pin_test.clj")
          io/as-file
          .getParentFile))

(defn- implementation-root
  "`implementation/` — the tree the domain spans — three parents above the
  classpath anchor (`re_frame` → `test` → `core` → `implementation`).

  IDENTIFIED POSITIVELY, by carrying `core/src`, rather than trusted as a
  relative position. An enumeration that listed the repo ROOT among its bases
  would be inert against a depth-1 listing but would sweep `tools/`,
  `examples/` and `migration/` into ratchets scoped to `implementation/` once
  the walk is recursive. A walk with a blast radius has to know what it is
  standing on.

  `re-frame.impl-source-corpus/implementation-root` answers the same question
  for the production-source corpus and is deliberately NOT reused here: it
  resolves from the process CWD, and this namespace's whole reason for using a
  classpath anchor is that the CWD is not guaranteed. Same discipline,
  different anchor — not a second copy of one definition."
  ^java.io.File []
  (when-let [anchor (core-test-anchor-dir)]
    (let [root (some-> anchor .getParentFile .getParentFile .getParentFile)]
      (when (and root (.isDirectory (io/file root "core" "src")))
        root))))

(defn- domain-file?
  "A candidate for the domain: a `.clj` / `.cljc` source living in some
  artefact's `test/` tree."
  [^java.io.File f]
  (and (.isFile f)
       (some? (re-find #"\.cljc?$" (.getName f)))
       (str/includes? (posix f) "/test/")))

(defn- source-bearing-dir?
  "Is `f` a directory this walk should DESCEND INTO?

  The exclusions are build outputs and vendored dependencies — trees that
  hold no authored repository source, so nothing in them can be a naming
  claim this repo is answerable for. They are excluded at DESCENT rather than
  after the fact, and the reason is measured on this tree: `implementation/`
  is 2,738 entries in a fresh checkout and 55,059 in a developer checkout
  that has built and installed — `.shadow-cljs` 37,322, `out` 10,310,
  `node_modules` 3,412, `target` 874. An exclusion
  applied in `domain-file?`, AFTER `file-seq` had already walked every one of
  those entries, would save none of that walk, and its cost would grow with
  whatever the developer happened to have built.

  Dot-directories go by prefix rather than by name so the caches nobody has
  invented yet are covered too. A narrowing here is not silent: the coverage
  assertion in `the-domain-scan-still-finds-files` pins the artefacts
  this walk must reach, so a prune that swallowed a real test tree reds
  naming it."
  [^java.io.File f]
  (let [n (.getName f)]
    (and (.isDirectory f)
         (not (str/starts-with? n "."))
         (not (contains? #{"node_modules" "out" "target"} n)))))

(defn- source-tree-seq
  "`file-seq` over `root`, pruned by `source-bearing-dir?`. `file-seq` is
  itself a `tree-seq` whose branch predicate is bare `.isDirectory`; this is
  that one predicate made choosier, which is the whole of the mechanism."
  [^java.io.File root]
  (tree-seq source-bearing-dir? #(seq (.listFiles ^java.io.File %)) root))

(defn- claiming-files
  "Every file in the domain: a `.clj` / `.cljc` source under any artefact's
  `test/` tree whose name contains one of `claim-tokens`.

  ONE recursive walk from ONE positively-identified root, selected by a path
  predicate. There is no root enumeration and no depth here, so there is
  nothing for a later edit to narrow silently in either dimension. The walk
  is pruned at descent (`source-bearing-dir?`); the reach that pruning must
  not cost is pinned by `required-artefacts`."
  []
  (->> (some-> (implementation-root) source-tree-seq)
       (filter domain-file?)
       (filter (fn [^java.io.File f]
                 (some #(str/includes? (.getName f) %) claim-tokens)))
       (sort-by posix)
       vec))

(defn- artefact-of
  "The artefact a domain file belongs to — the first path segment under
  `implementation/`."
  [^java.io.File root ^java.io.File f]
  (let [rp (posix root)
        fp (posix f)]
    (when (str/starts-with? fp (str rp "/"))
      (first (str/split (subs fp (inc (count rp))) #"/")))))

(defn- artefacts-of
  "The artefacts contributing at least one of `files` to the domain — `core`,
  `epoch`, `routing`, `ssr`."
  [^java.io.File root files]
  (if root
    (into (sorted-set) (keep #(artefact-of root %)) files)
    (sorted-set)))

(defn- rel
  "`f` relative to `implementation/`, for a failure message that can name four
  files called `prod_gate_lane_pin_test.clj`."
  [^java.io.File root ^java.io.File f]
  (if root
    (subs (posix f) (inc (count (posix root))))
    (posix f)))

(defn- honest? [^java.io.File f]
  (let [content (slurp f)]
    (or (str/includes? content prod-gate-tag)
        (str/includes? content jvm-property)
        (str/includes? content disclaimer))))

(deftest the-domain-scan-still-finds-files
  (testing "the guard on the guard. If the root stops resolving (a
            moved test root, a packaged classpath) or the token match stops
            hitting, the check below passes VACUOUSLY and the naming lie is
            free to come back. A silently-empty scan is the failure mode this
            whole namespace exists to prevent, so it is a hard red.

            AND `(<= 3 (count ...))` IS NOT THAT GUARD. A scan that listed
            `core/test/re_frame` at depth 1 would find seven files, pass here
            comfortably, and reach neither a nested directory nor another
            artefact's test tree. A floor on whether the walk found ANYTHING
            says nothing about whether it found EVERYTHING, so the coverage
            claim below is about the walk's REACH.

            AND NEITHER IS `(> artefact-count 1)`: a count over a lopsided
            corpus (core=7, the others one or two apiece) is satisfied by
            dropping any single artefact. The reach claim is made against
            `required-artefacts` by NAME, so a failure says which tree
            stopped being scanned."
    ;; The corpus is walked ONCE and read four times. `claiming-files` is a
    ;; recursive walk, and `is`'s message argument is evaluated whether or
    ;; not the assertion fails.
    (let [root      (implementation-root)
          files     (claiming-files)
          artefacts (artefacts-of root files)]
      (is (some? (core-test-anchor-dir))
          (str "could not resolve `implementation/core/test/re_frame` from the "
               "classpath via `re_frame/prod_gate_lane_pin_test.clj` — has the "
               "anchor file been renamed or the test root moved?"))
      (is (some? root)
          (str "resolved the classpath anchor but three parents above it is "
               "not `implementation/` — nothing there carries `core/src`. The "
               "positive identification is deliberate (see "
               "`implementation-root`); a recursive walk from the wrong root "
               "is worse than no walk."))
      (is (empty? (remove artefacts required-artefacts))
          (str "the walk does not reach every artefact that carries a "
               "claiming file. MISSING: "
               (pr-str (vec (sort (remove artefacts required-artefacts))))
               " — found only " (pr-str (vec artefacts)) ". That is the "
               "fail-open exactly: this ratchet is armed by "
               "`implementation_jvm`, which every artefact's test tree arms, "
               "so a walk confined to some of them runs and reports green "
               "over the rest. A count is not the guard — with core at seven "
               "files, dropping any ONE of the other three leaves 9 or 10 "
               "files across 3 artefacts, which every count-shaped assertion "
               "here passes. See `required-artefacts` for why this is a subset."))
      (is (<= 8 (count files))
          (str "collapse detector, calibrated at 8 against the 11 claiming "
               "files in the tree — deliberately just above the 7 that a "
               "walk narrowed back to `core/test/re_frame` returns, so that "
               "specific narrowing reds here as well as above. The "
               "artefact-reach claim is the durable one; this number will need "
               "moving. Found: " (mapv #(rel root %) files))))))

(deftest every-gate-claiming-namespace-is-honest-about-the-gate
  (testing "a test file whose NAME says it exercises the JVM
            production/debug gate must either reach that gate for real
            (`^:prod-gate` in the `jvm-core-prod-gate` lane, or a child JVM
            launched with `-Dre-frame.debug=false`) or carry the disclaimer
            sentinel in its docstring. `with-redefs` on
            `re-frame.interop/debug-enabled?` is neither: the flag is read
            once at namespace-load time, so a rebind cannot reach it."
    (let [root  (implementation-root)
          liars (remove honest? (claiming-files))]
      (is (empty? liars)
          (str "these files NAME the production/debug gate but neither reach "
               "it nor disclaim it: "
               ;; Paths, not names: the domain spans artefacts, and
               ;; four of them ship a file called `*prod_gate_lane_pin_test`.
               (mapv #(rel root %) liars)
               "\n\nFix by one of:"
               "\n  a. run in the real lane — tag the deftests `^:prod-gate`"
               "\n     (see re-frame.prod-gate-lane-pin-test) and add the ns to"
               "\n     your artefact's scripts/test-<artefact>-prod-gate.sh lane;"
               "\n  b. relaunch a child JVM with `" jvm-property "` on its"
               "\n     command line (see re-frame.prod-gate-dispatch-jvm-test);"
               "\n  c. state in the ns docstring that the suite is `"
               disclaimer "`,"
               "\n     naming what it DOES pin (a rebindable Var is a real"
               "\n     contract — it is just not the production posture).")))))
