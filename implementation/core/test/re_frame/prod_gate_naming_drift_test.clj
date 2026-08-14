(ns re-frame.prod-gate-naming-drift-test
  "rf2-f7qj4 — a namespace whose NAME claims the production/debug gate must
  either reach that gate for real, or say out loud that it does not.

  ## The failure mode this closes

  `re-frame.interop/debug-enabled?` is read ONCE, at namespace-load time, from
  `-Dre-frame.debug` / `RE_FRAME_DEBUG`. A suite that reaches it with
  `with-redefs` runs AFTER the framework has loaded and cannot change one
  thing the gate decided at load — it pins a rebindable Var, not a posture.

  rf2-9c2jf was a TOTAL `dispatch-sync` failure under the documented
  production gate — handler run ZERO times — that stayed green for as long as
  it existed. Part of why nobody caught it: the roster of suites calling
  themselves \"production gate\" tests looked full, and a reviewer reading the
  file list had no way to see that not one of them ran under the gate. The
  names were the camouflage.

  rf2-f7qj4 re-docstringed the three offenders. A docstring decays; this test
  is what stops the next one being written.

  ## There was a fourth offender, and the walk could not see it (rf2-sk5hf)

  This scan used to enumerate `implementation/core/test/re_frame` with
  `.listFiles` — one artefact's test tree, at depth 1. Both narrowings were
  invisible in the same way: the sanity test below floored on \"at least three
  files\", seven satisfied it, and a floor on whether the walk found ANYTHING
  says nothing about whether it found EVERYTHING.

  The DEPTH narrowing was latent. Six directories nest under
  `core/test/re_frame` (`adapter`, `bench`, `bench/lane_cache_fixtures`,
  `substrate`, `trace`, `views`) and not one of them held a claiming file, so
  the subtree and the depth-1 listing returned the same seven.

  The ARTEFACT narrowing was not. `implementation/epoch/test/re_frame/`
  `epoch_jvm_prod_gate_test.clj` NAMES the JVM production gate in its filename
  and pins it entirely with `with-redefs` — the precise shape rf2-f7qj4 exists
  to stop — and it had sat outside the walk since rf2-f7qj4 landed, because
  that bead's sweep only ever looked at core. It is not a tree this ratchet
  fails to be responsible for, either: an epoch test file arms
  `implementation_jvm` (`.github/scripts/report-changed-surfaces.sh`), which is
  the SAME condition `jvm-core` gates on, so this suite was armed, ran, walked
  past `implementation/epoch/` and reported green. Three more artefacts
  (`freehand`, `routing`, `ssr`) carry claiming files too; those were honest
  already, by real lanes.

  Sharper still, `re-frame.interop-debug-gate-test`'s docstring asserted the
  epoch suite \"carries the same caveat\". It did not. A cross-reference is not
  a check.

  So the domain is now every artefact's `test/` tree, walked recursively from
  one positively-identified root. See `implementation-root` for why the root is
  identified by what it CARRIES rather than by where it sits.

  ## The rule

  DOMAIN — every `.clj` / `.cljc` file under any artefact's `test/` tree
  beneath `implementation/`, whose FILE NAME contains `prod_gate`, `jvm_gate`,
  or `debug_gate`. Those three tokens are the ones that assert, in the file
  listing itself, \"this exercises the JVM production/debug gate\".
  Deliberately narrow in the TOKEN dimension: `trace_gate` and the
  `prod_elision` suites make a different claim and are out of scope. Not narrow
  in the TREE dimension, and rf2-sk5hf is why.

  A file in the domain is HONEST when it does at least one of:

    a. carries `^:prod-gate` metadata — it belongs to a real prod-gate lane,
       which puts `-Dre-frame.debug=false` on the JVM command line via that
       artefact's `:prod-gate` alias `:jvm-opts` (`jvm-core-prod-gate` and its
       `freehand` / `routing` / `ssr` siblings);
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

  This is no longer the tree that gets walked; it is how the tree that DOES get
  walked is found."
  ^java.io.File []
  (some-> (io/resource "re_frame/prod_gate_lane_pin_test.clj")
          io/as-file
          .getParentFile))

(defn- implementation-root
  "`implementation/` — the tree the domain spans — three parents above the
  classpath anchor (`re_frame` → `test` → `core` → `implementation`).

  IDENTIFIED POSITIVELY, by carrying `core/src`, rather than trusted as a
  relative position. That is rf2-2cu7f's discipline and it was learnt the hard
  way: the enumeration that bead fixed listed the repo ROOT among its bases,
  which was inert against a depth-1 listing and would have swept `tools/`,
  `examples/` and `migration/` into two ratchets scoped to `implementation/`
  the moment the walk went recursive. A walk with a blast radius has to know
  what it is standing on.

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
  artefact's `test/` tree.

  `node_modules` is excluded. A vendored file's name is not this repo's claim
  to make, and a developer who has run `npm ci` would otherwise put tens of
  thousands of files in front of the token match."
  [^java.io.File f]
  (let [p (posix f)]
    (and (.isFile f)
         (some? (re-find #"\.cljc?$" (.getName f)))
         (str/includes? p "/test/")
         (not (str/includes? p "/node_modules/")))))

(defn- claiming-files
  "Every file in the domain: a `.clj` / `.cljc` source under any artefact's
  `test/` tree whose name contains one of `claim-tokens`.

  ONE recursive walk from ONE positively-identified root, selected by a path
  predicate. There is no root enumeration and no depth here, so there is
  nothing left for a later edit to narrow silently — which is what rf2-sk5hf
  found this doing in both dimensions at once."
  []
  (->> (some-> (implementation-root) file-seq)
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

(defn- claiming-artefacts
  "The artefacts contributing at least one file to the domain — `core`,
  `epoch`, `freehand`, `routing`, `ssr` as measured at rf2-sk5hf."
  []
  (if-let [root (implementation-root)]
    (into (sorted-set) (keep #(artefact-of root %)) (claiming-files))
    (sorted-set)))

(defn- honest? [^java.io.File f]
  (let [content (slurp f)]
    (or (str/includes? content prod-gate-tag)
        (str/includes? content jvm-property)
        (str/includes? content disclaimer))))

(deftest the-domain-scan-still-finds-files
  (testing "rf2-f7qj4 — the guard on the guard. If the root stops resolving (a
            moved test root, a packaged classpath) or the token match stops
            hitting, the check below passes VACUOUSLY and the naming lie is
            free to come back. A silently-empty scan is the failure mode this
            whole namespace exists to prevent, so it is a hard red.

            AND `(<= 3 (count ...))` WAS NOT THAT GUARD — rf2-sk5hf is the
            proof. For as long as this scan listed `core/test/re_frame` at
            depth 1 it found seven files, passed here comfortably, and reached
            neither a nested directory nor another artefact's test tree, where
            a real offender was sitting. A floor on whether the walk found
            ANYTHING says nothing about whether it found EVERYTHING, so the
            coverage claim below is about the walk's REACH."
    (is (some? (core-test-anchor-dir))
        (str "could not resolve `implementation/core/test/re_frame` from the "
             "classpath via `re_frame/prod_gate_lane_pin_test.clj` — has the "
             "anchor file been renamed or the test root moved?"))
    (is (some? (implementation-root))
        (str "resolved the classpath anchor but three parents above it is not "
             "`implementation/` — nothing there carries `core/src`. The "
             "positive identification is deliberate (see `implementation-root`); "
             "a recursive walk from the wrong root is worse than no walk."))
    (is (< 1 (count (claiming-artefacts)))
        (str "the domain spans ONE artefact. That is the rf2-sk5hf fail-open "
             "exactly: this ratchet is armed by `implementation_jvm`, which "
             "every artefact's test tree arms, so a walk confined to one of "
             "them runs and reports green over the others. Found: "
             (pr-str (vec (claiming-artefacts)))))
    (is (<= 8 (count (claiming-files)))
        (str "collapse detector, calibrated at 8 against the 11 files measured "
             "at rf2-sk5hf — deliberately just above the 7 that a walk narrowed "
             "back to `core/test/re_frame` returns, so that specific regression "
             "reds here as well as above. The artefact-reach claim is the "
             "durable one; this number will need moving. Found: "
             (mapv #(.getName ^java.io.File %) (claiming-files))))))

(deftest every-gate-claiming-namespace-is-honest-about-the-gate
  (testing "rf2-f7qj4 — a test file whose NAME says it exercises the JVM
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
               ;; Paths, not names: the domain spans artefacts (rf2-sk5hf), and
               ;; four of them ship a file called `*prod_gate_lane_pin_test`.
               (mapv #(if root
                        (subs (posix %) (inc (count (posix root))))
                        (posix %))
                     liars)
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
