(ns re-frame.test-quiet.runner
  "JVM `:main` entry point that installs the quiet reporter, runs
  `cognitect.test-runner/test`, and decides the run's verdict from the
  summary that call returns.

  Each per-artefact `:test` alias names this namespace as its `-m` entry
  point in place of `cognitect.test-runner`. Arguments are parsed with
  cognitect's own `cli-options`, so its flags (`-d`, `-n`, `-r`, `-v`, `-i`,
  `-e`, `-H`) mean here what they mean there. A CLI error, and `-H`, are
  handed to `cognitect.test-runner/-main` whole, so the diagnostics and the
  usage text are cognitect's own.

  Requiring `re-frame.test-quiet` at the top of this namespace is the
  install — the `defmethod`s for `clojure.test/report` install at
  load time, before cognitect discovers a single test.

  ## Quiet contract

  The JVM and CLJS node runners use pass-through stdout plus bounded noise
  buffering. Both drop the buffer on green and replay it on red, but their
  capture scopes differ. Browser diagnostics are owned by the browser
  harness, not this runner.

   - STDOUT is pass-through. A green run prints cognitect's one
     `Running tests in #{...}` line, naming the directories it scanned,
     and then the reporter's summary:

         Running tests in #{\"test\"}

         Ran N tests containing M assertions.
         0 failures, 0 errors.

     Every other byte a test, a fixture or the CLI emits reaches stdout
     too — `-H` usage, CLI parse-error diagnostics, and any bare
     `(println ...)` a test makes. The CLJS node runner forwards stdout
     the same way.
   - STDERR/WARNINGS are BUFFERED, replayed on red, dropped on green.
     A green run must not flood stdout/stderr with the expected
     warnings that warning-heavy suites emit (e.g.
     `re-frame.mcp-base.sensitive`'s fail-closed contract-drift WARN on
     thousands of malformed `:sensitive?` stamps). This runner binds the
     test-driver thread's `*err*` and swaps the process-global `System/err`
     to a bounded ring buffer. Captured stderr is replayed to the real
     stderr only when the run is red and dropped on green.
     The CLJS node runner uses the same policy for a narrower scope: a
     `console.warn` stub plus the `[re-frame2]` dev-fallback line on
     `console.error`, not other `console.error` calls or direct
     `process.stderr` writes.  The asymmetry reflects what each runtime's
     noise actually is: CLJS first-run warnings come through
     `console.warn`, while any other `console.error` stays a real error
     channel; the JVM has no comparable convention, so it buffers the
     current-thread `*err*` plus process-global `System.err`. Tests that
     assert on warning text still capture `*err*` locally.

  ## The verdict belongs to this invocation

  `cognitect.test-runner/test` requires the selected namespaces, runs
  them, and RETURNS the `run-tests` summary; only cognitect's `-main`
  calls `System/exit`. So `-main` here calls `test` and owns everything
  after it — the red stderr replay, the fixture audit, the lane's claim
  and the exit code — deciding each from the summary `test` returned and
  from the namespaces this invocation selected. Nothing is installed on
  `clojure.test/report` for the purpose, so a `run-tests` nested inside a
  deftest is only a nested run: it prints its own tally and reaches
  neither the outer verdict nor the JVM.

  The exit code is 0 on green and 1 on red — a failure, an error, or a
  lane claim below that the run did not prove. A CLI error exits 1 from
  cognitect.

  ## What a lane claims, and what it must therefore prove

  Which proof a lane owes depends on what it claims:

   - a SUITE lane claims coverage, so it must prove it RAN: executing no
     test is red, however clean the tally.  `run-tests` over an empty namespace set reports `Ran 0 tests
     containing 0 assertions. / 0 failures, 0 errors.`, so a discovery set
     that silently collapses to nothing — a `-r`/`-n` selector matching no
     namespace, a `:test` alias missing its `:extra-paths [\"test\"]`, a
     renamed test file — would otherwise be indistinguishable from a green
     suite.  See `lane-claim-complaint`.
   - a PROBE lane claims only that its deps and classpath RESOLVE, so zero
     tests is its correct outcome and the coverage floor must not apply.
     Such a lane says so explicitly by passing `--probe` in its `:test`
     alias `:main-opts` (`implementation/adapters/reagent` is one: its
     `test/` tree is CLJS-only).  A probe still has to prove something
     mechanically: it must reach its summary having executed exactly zero
     tests.  Reaching the summary at all means deps resolved and the
     discovery dirs were scanned; and if the lane ever GAINS a JVM test it
     goes red, because it is then claiming coverage and must drop `--probe`
     and take the floor.

  `--probe` is this wrapper's only own flag and is stripped before args are
  parsed.  Nothing is printed on a green probe beyond the green run's own
  output: the probe's proof is the assertion, not an announcement.

  ## What the lane will DISCOVER

  Both rules above are about the tests that RAN.  Neither can see a test
  file that never entered the run at all — and there are two ways for one
  not to.  Cognitect's discovery reads each file's `(ns ...)` form and
  silently drops the ones it cannot read; and it returns a namespace once
  per FILE, so two files declaring the same one leave `require` loading
  whichever the classpath resolves while the other never loads, running a
  suite twice under a tally that goes UP rather than down.
  `discovery-defects` is the third rule and it fires BEFORE any test does;
  see its own docstring.

  ## What the lane's FIXTURES will do

  A namespace that WAS discovered, WAS loaded and DOES hold tests still
  runs none of them if its fixtures are DATA — `clojure.test` applies a
  fixture to the test thunk, and a map, set, vector, keyword or symbol
  invoked with one argument is a key lookup, so the thunk never runs.  Those
  five are what `uncallable-fixtures` reports, and reporting them is all it
  claims: applying the thunk is the requirement, it is much wider than `fn?`,
  and the rule refuses a closed set rather than admitting a list.
  `uncallable-fixtures` is the fourth rule.  It reads the namespaces this
  invocation SELECTED (`selected-namespaces`) once the run has returned, so
  a namespace a focused run merely loaded — a helper a selected test
  requires — cannot fail it.  See its own docstring.

  ## Namespace metadata selects too

  cognitect's `-i` and `-e` read VAR metadata.  This runner widens each
  keyword to the var's namespace (`with-namespace-tags`): a keyword on the
  `(ns ...)` form tags every var in it, so `(ns ^:requires-debug
  my.thing-test ...)` takes the whole namespace out of a lane passing
  `-e :requires-debug`, and `-i` includes it the same way.  A namespace
  dropped like that is still selected and REQUIRED, so a load-time failure
  in it reddens the run; it contributes no test and no assertion to the
  tally.  Var-level tags are the rule for a namespace whose suites are
  mixed, because a new untagged deftest there joins the lane by default,
  where a namespace-level tag would swallow it."
  (:require
    [re-frame.test-quiet]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [clojure.tools.namespace.file :as ns-file]
    [clojure.tools.namespace.find :as ns-find]
    [clojure.tools.namespace.parse :as ns-parse]
    [cognitect.test-runner]))

;; ----------------------------------------------------------------------
;; Central stderr buffer + red replay.
;;
;; The symmetric counterpart to the CLJS node runner's `console.warn`
;; ring buffer (`re-frame.test-quiet.shadow-node`). The runner buffers
;; stderr in a bounded ring for the run and:
;;   - GREEN: drop the buffer silently (quiet on success);
;;   - RED:   replay it to the real stderr once `cognitect.test-runner/test`
;;            returns a red summary, so a failing run keeps the
;;            diagnostic context;
;;   - THREW: replay it on the way out, because a namespace that throws on
;;            load, or a throwing `:once` fixture, leaves `test` without a
;;            summary.
;;
;; clojure.test routes its assertion / FAIL / ERROR / summary output
;; through `clojure.test/*test-out*` (bound to the real `*out*`), NOT
;; through `*err*`, so buffering stderr never hides failure diagnostics —
;; the red FAIL/ERROR blocks reach the real stdout.

(def ^:private stderr-buffer-cap
  "Bounded stderr ring capacity (characters).  Caps memory + replay
  volume on a red run while keeping enough recent context to explain a
  failure. The newest `stderr-buffer-cap` characters are retained and
  older ones are dropped."
  (* 256 1024))

(defn- buffering-stderr-writer
  "A `java.io.Writer` over the shared `stderr-ring` that captures everything
  written to it (trimming `stderr-ring` to the newest `stderr-buffer-cap`
  characters) and forwards NOTHING to the real stderr.  The runner reads
  `stderr-ring` back once the run returns and replays it only on red
  (`run-lane!`); on green the buffer is dropped."
  ^java.io.Writer [^StringBuilder stderr-ring]
  (let [append-to-ring! (fn [^String text]
                  ;; Serialize the WHOLE append-plus-front-trim transaction on
                  ;; `stderr-ring`'s monitor. Both JVM stderr channels funnel here —
                  ;; `*err*` via one PrintWriter and raw `System.err` via the
                  ;; `System/setErr` PrintStream bridge (see `run-lane!`) — and those
                  ;; two wrappers serialize only their OWN calls under DISTINCT
                  ;; locks, so without this shared monitor a write from each
                  ;; channel can interleave inside this body: a `.append` racing
                  ;; a front-trim `.delete` tears StringBuilder's internal
                  ;; count/array and throws ArrayIndexOutOfBoundsException from an
                  ;; otherwise-valid test. StringBuilder (unlike StringBuffer) has
                  ;; no synchronized methods, so nothing else contends for this
                  ;; monitor; the replay snapshot in
                  ;; `replay-captured-stderr!` locks on the SAME ring, so a
                  ;; red replay never reads a half-applied mutation.
                  (locking stderr-ring
                    (.append stderr-ring text)
                    ;; Trim from the front once past the cap so the ring
                    ;; keeps the most-recent context (the characters nearest a
                    ;; failure), matching the CLJS ring's newest-N policy.
                    (let [ring-length (.length stderr-ring)]
                      (when (> ring-length stderr-buffer-cap)
                        (.delete stderr-ring 0
                                 (- ring-length stderr-buffer-cap))))))]
    (proxy [java.io.Writer] []
      (write
        ([value]
         (cond
           (string? value)  (append-to-ring! value)
           (integer? value) (append-to-ring!
                              (String. (char-array [(char value)])))
           (instance? (Class/forName "[C") value)
           (append-to-ring! (String. ^chars value))
           :else (throw (IllegalArgumentException.
                          (str "unexpected write arg: " (class value))))))
        ([char-buffer offset length]
         (append-to-ring!
           (if (string? char-buffer)
             (subs char-buffer offset (+ offset length))
             (String. ^chars char-buffer (int offset) (int length))))))
      (flush [])
      (close []))))

(defn- replay-captured-stderr!
  "Write whatever `stderr-ring` holds to `original-err`, under a
  `[test-quiet]` header saying `reason`, and flush it. An empty ring
  replays nothing.

  The snapshot is taken under the ring's monitor, the SAME lock every
  writer in `buffering-stderr-writer` takes, so the `.length` guard and the
  `.toString` copy observe a consistent ring rather than a mutation
  half-applied by a still-live background writer. The copy is made under
  the lock and the (unbounded) replay I/O outside it, so a writer is never
  blocked on the replay."
  [^StringBuilder stderr-ring ^java.io.Writer original-err reason]
  (let [captured-stderr (locking stderr-ring
                          (when (pos? (.length stderr-ring))
                            (.toString stderr-ring)))]
    (when captured-stderr
      (.write original-err
              (str "\n[test-quiet] buffered stderr replayed because "
                   reason ":\n"))
      (.write original-err captured-stderr)
      (when-not (.endsWith captured-stderr "\n")
        (.write original-err "\n"))
      (.flush original-err))))

;; ----------------------------------------------------------------------
;; What a lane claims, and what it must therefore prove — the coverage
;; floor for a suite lane, `probe-flag` for a resolution lane.
;;
;; The project holds this standard in two other places:
;;   - `re-frame.test-quiet.shadow-node/execute-cli` rejects a `--test=`
;;     SELECTOR that matches no var, "precisely because run-test-vars over
;;     an empty set reports a 0-test success";
;;   - `re-frame.conformance-runner` asserts `(>= (count run) 150)` over the
;;     conformance CENSUS.
;; Both are places that claim COVERAGE, and both say the same thing: a run
;; that executed nothing is a configuration error, not a pass.  Neither is a
;; resolution probe — the standard is scoped this way, and the floor below
;; applies it to the whole-suite path every per-artefact `:test` alias takes
;; rather than widening it to lanes that claim something else (see the ns
;; docstring, and `probe-flag`).
;;
;; The floor is 1 rather than a per-artefact calibrated number.  1 is the
;; bound that can never go stale (no SUITE lane will ever legitimately ship
;; zero tests), while ~20 hand-maintained per-artefact numbers would have to
;; be re-ratcheted on ordinary churn and buy detection only for a PARTIAL
;; collapse, which this failure class does not produce in practice.

(def ^:private probe-flag
  "The one flag this wrapper owns, stripped before args reach cognitect.

  A lane passes it in its `:test` alias `:main-opts` to declare that it
  claims RESOLUTION, not coverage — its deps and classpath must load, and
  zero tests is its correct outcome. The declaration lives in the artefact's
  own `deps.edn` rather than in a job definition or an env var so that
  `clojure -M:test` behaves identically in CI and on a laptop, and so the
  exemption is self-documenting where the lane is defined."
  "--probe")

(defn- split-runner-args
  "Partition `args` into this wrapper's own flags and the args to forward
  verbatim to cognitect-test-runner. Returns `[{:probe? bool} forwarded]`.

  Only `--probe` is ours; everything else — including anything unrecognised —
  is forwarded, so cognitect keeps owning its own arg contract and its own
  parse-error diagnostics."
  [args]
  [{:probe? (boolean (some #{probe-flag} args))}
   (remove #{probe-flag} args)])

;; ----------------------------------------------------------------------
;; What the lane will DISCOVER — a file the runner cannot see is not a
;; file that passed.
;;
;; `cognitect.test-runner/test` builds its namespace set by READING each
;; source file's `(ns ...)` form:
;;
;;     (mapcat find/find-namespaces-in-dir dirs)
;;
;; and `clojure.tools.namespace.find/find-ns-decls-in-dir` is a `keep` over
;; a helper named `ignore-reader-exception` — which is precisely what it
;; does.  A file whose `(ns ...)` FORM the reader cannot read contributes NO
;; namespace, and downstream nothing can tell a file that was dropped from a
;; file that was never written.  One unescaped `"` inside the ns docstring
;; is enough.
;;
;; A single stray quote in one test file's ns docstring drops exactly that
;; file's deftests from `clojure -M:test`, and the run still prints
;; `0 failures, 0 errors.` and exits 0.  Exit code zero, honestly earned,
;; over a suite missing a whole file.
;;
;; NOTHING ELSE SEES IT, and that is not for want of guards.  The test-count
;; floor above is a COLLAPSE detector: one file's handful of tests sits far inside
;; the headroom any growing lane must leave itself.
;; `scripts/check_test_lane_bijection.py` matches the `(ns ` line with a
;; regex, so it matches the APPEARANCE of a declaration rather than the
;; declaration, and stays green over such a broken file.
;; Reading the form is the whole point, so the check lives HERE, in the one
;; process that already has the lane's classpath and the discovery library
;; on it, rather than in a script that would have to imitate a reader.
;;
;; THE RULE, and why it is one rule rather than three.  Every source file in
;; a discovery directory must declare — readably — the namespace its own
;; path spells.  That single sentence closes every silent door:
;;
;;   * an unreadable form declares nothing, so it cannot match its path;
;;   * a file declaring somebody else's namespace is loaded by nobody:
;;     `require` resolves the DECLARED name back to a path, so whatever
;;     lives at THAT path is what runs, and this file never does;
;;   * and two files cannot both spell one namespace, because their paths
;;     differ — so the shadowing pair, where one file's tests run twice and
;;     the other's never run at all, is caught by the same comparison.
;;
;; "The discovered count equals the file count" is the other candidate and
;; it is strictly weaker: a count is satisfiable by coincidence (a file lost
;; while another gains a second declaration nets zero), it cannot NAME the
;; file it is missing, and it is implied anyway — a total, injective map
;; from files to namespaces has exactly as many namespaces as it has files.
;; It is a consequence of this rule, not a second rule.
;;
;; SCOPE.  This is the JVM lane's discovery, so it covers every artefact
;; whose `:test` alias runs this wrapper — which is every JVM artefact in
;; the repo bar `migration/from-re-frame-v1/codemod`, which deliberately
;; calls cognitect directly.  The CLJS lanes discover through shadow-cljs's
;; own indexer and are not in this rule's reach.

(def ^:private discovery-platform
  "The platform cognitect discovers under.  `cognitect.test-runner/test`
  calls the single-arity `find/find-namespaces-in-dir`, which defaults to
  `find/clj`: `.clj` + `.cljc`, read with `{:read-cond :allow :features
  #{:clj}}`.  Named rather than inlined so the guard and the runner cannot
  drift apart about what a source file even is."
  ns-find/clj)

(defn discovery-dirs
  "The directories this run will scan, or nil when `args` do not parse.

  Resolved with cognitect's OWN option spec and its OWN default —
  `(or (:dir options) #{\"test\"})` — so there is no second, hand-rolled
  reading of `-d` to disagree with the first.  Unparseable args yield nil
  and the guard stands down: cognitect prints its parse diagnostics and
  exits 1 before discovery, so at that point there is nothing to guard."
  [args]
  (let [{:keys [options errors]} (cli/parse-opts args cognitect.test-runner/cli-options)]
    (when-not (seq errors)
      (or (:dir options) #{"test"}))))

(defn- ns->path
  "The classpath-relative path `require` resolves `ns-sym` to, under `ext`."
  [ns-sym ext]
  (str (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/")) ext))

(defn- relative-path
  "`file`'s path relative to `dir`, forward-slashed on every platform."
  [^java.io.File dir ^java.io.File file]
  (-> (.relativize (.toPath dir) (.toPath file)) str (str/replace "\\" "/")))

(defn- declared-namespace
  "What `file` declares to discovery: `[ns-sym nil]`, or `[nil complaint]`
  when it declares nothing discovery can use.

  `read-file-ns-decl` THROWS on a file the reader cannot read — that is why
  `find-ns-decls-in-dir` wraps this very call in `ignore-reader-exception`,
  and the exception it discards is the one sentence saying where the file is
  broken.  Catching it here instead is the whole difference between a red an
  operator can act on and a red they have to bisect, so the reader's own
  line and column travel with the complaint."
  [^java.io.File file]
  (try
    (if-let [decl (ns-file/read-file-ns-decl file (:read-opts discovery-platform))]
      [(ns-parse/name-from-ns-decl decl) nil]
      [nil "it holds no `(ns ...)` form."])
    (catch Exception e
      (let [{:keys [line col]} (ex-data e)
            msg (or (some-> (ex-message e) str/split-lines first)
                    (.getName (class e)))]
        [nil (str "the reader cannot read it"
                  (when line (str " (line " line ", column " col ")"))
                  ": " msg)]))))

(defn- scan
  "Every source file discovery will WALK under `dirs`, one map per physical
  file: `{:path :rel :ext :declared :complaint}`.

  The files come from `find/find-sources-in-dir` — the same walk
  `find-ns-decls-in-dir` makes, before it drops anything — so what follows
  compares what discovery SAW against what discovery KEEPS, rather than
  against a file-naming convention this guard invented.

  Deduplicated by CANONICAL path, so two `-d` roots naming one directory
  contribute each file once. The same file is one file; a collision below
  has to mean two of them, or this guard would redden a lane over its
  alias spelling rather than over its sources."
  [dirs]
  (->> (for [d               (sort dirs)
             :let            [dir (io/file d)]
             ^java.io.File f (ns-find/find-sources-in-dir dir discovery-platform)
             :let  [rel       (relative-path dir f)
                    [declared complaint] (declared-namespace f)]]
         {:path      (str/replace (.getPath f) "\\" "/")
          :canonical (.getCanonicalPath f)
          :rel       rel
          :ext       (subs rel (str/last-index-of rel "."))
          :declared  declared
          :complaint complaint})
       (reduce (fn [[seen acc] {:keys [canonical] :as file}]
                 (if (contains? seen canonical)
                   [seen acc]
                   [(conj seen canonical) (conj acc file)]))
               [#{} []])
       second))

(defn- source-file
  "The loose source file `url` names, as a forward-slashed canonical path,
  or nil when it names anything else.

  A resource answered from inside a jar is nil: a jar entry is never one of
  the loose source files the discovery walk just handed us, so it is `some
  other file` by the only definition that matters here."
  [^java.net.URL url]
  (when (= "file" (.getProtocol url))
    (try (str/replace (.getCanonicalPath (io/file (.toURI url))) "\\" "/")
         (catch Exception _ nil))))

(defn- resolved-through-classpath
  "The loose file THIS run's classpath answers the resource path `ns-path`
  with, or nil when nothing answers it or a jar does.

  Answering this one path is NOT the same as being what `require` loads:
  `require` never asks for the extension a discovered file happens to carry.
  That question is `load-winner`'s."
  [^String ns-path]
  (some-> (io/resource ns-path) source-file))

(def ^:private load-extensions
  "The order `require` asks the classpath for a namespace's source.

  `clojure.lang.RT/load` asks the WHOLE classpath for `<base>.clj`, and only
  when nothing anywhere answers does it ask for `<base>.cljc` — so a `.clj`
  in ANY root beats a `.cljc` in EVERY root, whatever the root order.
  Measured on Clojure 1.12.0: with a `.cljc` in the first root
  and a `.clj` of the same namespace in the second, `require` loads the
  `.clj`; swap the roots and it still loads the `.clj`; drop the `.clj` and
  it loads the `.cljc`. (`RT/load` also takes a compiled `__init.class` over
  either source when the class is newer; that door is not modelled here.)"
  [".clj" ".cljc"])

(defn- load-winner
  "The source `require` will ACTUALLY load for `ns-sym` on this run's
  classpath: a loose file's forward-slashed canonical path, the resource URL
  of anything else (a jar entry), or nil when nothing answers at all."
  [ns-sym]
  (some (fn [ext]
          (when-let [url (io/resource (ns->path ns-sym ext))]
            (or (source-file url) (str url))))
        load-extensions))

(defn- own-path-defect
  "Why `file` will not reach the runner as ITS OWN namespace, or nil.

  Either the reader cannot read its `(ns ...)` form — in which case
  discovery drops it and it contributes no namespace at all — or the name
  it declares resolves to some other file.

  `-d` CHOOSES WHERE COGNITECT SCANS; IT IS NOT A CLASSPATH ROOT.  Cognitect
  reads the namespace out of each discovered file and hands it to `require`,
  which resolves it against the unchanged classpath — so
  `-d test/re_frame`, narrowing a large tree during local debugging, is an
  ordinary selection and not a defect, even though every file under it then
  spells a path relative to `-d` that differs from its resource path.  Two
  answers therefore say a file spells its own path, and it is a defect if
  NEITHER does:

    - its path relative to the scan directory already IS its resource path
      (the default `-d test` lane, and every temp-dir fixture, which is why
      this arm must stay: nothing there is on the classpath at all); or
    - that resource path, on this run's real classpath, answers with THIS
      VERY FILE.

  The second arm is strictly weaker than the first, so it can only ever
  clear a file the path-string comparison alone would redden.

  NEITHER ANSWER SAYS THE FILE IS WHAT `require` LOADS.  Both look the
  namespace up under the extension the discovered file carries, while
  `require` asks the whole classpath for the `.clj` first (see
  `load-extensions`).  So a selected failing `.cljc` in one root and an
  unselected passing `.clj` of the same namespace in another would satisfy
  both, and `-main` would run the `.clj` and exit 0 without the selected
  failure ever executing.  A file that spells its own path is therefore
  STILL a defect when its namespace's `load-winner` is a source outside
  `discovered` — the canonical paths of every file this run walked.  A winner INSIDE it is a
  discovered sibling declaring the same namespace, which `collision-defects`
  names together with this file."
  [discovered {:keys [canonical rel ext declared complaint]}]
  (if complaint
    complaint
    (let [ns-path  (ns->path declared ext)
          self     (str/replace canonical "\\" "/")
          resolved (resolved-through-classpath ns-path)]
      (if-not (or (= rel ns-path) (= resolved self))
        (str "it declares `" declared "`, which `require` loads from `"
             ns-path "` - "
             (if resolved
               (str "and on this run's classpath that is `" resolved
                    "`, a different file.")
               (str "and nothing on this run's classpath answers to that"
                    " path, so `require` cannot load this file at all.")))
        (let [winner (load-winner declared)]
          (when (and winner
                     (not= winner self)
                     (not (contains? discovered winner)))
            (str "it declares `" declared "`, but `require` loads that"
                 " namespace from `" winner "`, a different file, so this"
                 " file never runs"
                 (if (and (= ext ".cljc") (str/ends-with? winner ".clj"))
                   (str ": Clojure takes a namespace's `.clj` before its"
                        " `.cljc` from anywhere on the classpath, whatever"
                        " the root order.")
                   "."))))))))

(defn- collision-defects
  "One `[path complaint]` per file for every namespace declared by MORE
  THAN ONE of `files`, each complaint naming the others.

  Applied only to files that already spell their own path, because a file
  that does not is named by `own-path-defect` and its declaration is
  already known bad.  Two files can both spell their own path and still
  collide, which is the whole reason this rule exists beside that one:

    - `probe/x_test.clj` beside `probe/x_test.cljc`, each resolving under
      its OWN extension;
    - one relative path repeated under two `-d` roots.

  Neither is two suites.  Discovery returns the name once PER FILE, so
  `require` loads whichever the classpath resolves and treats the rest as
  already loaded, and `run-tests` is then handed the same symbol twice:
  one file runs twice while the other never runs at all.  The tally goes
  UP, not down, so neither the summary nor the test-count floor can see
  the substitution."
  [files]
  (for [[declared group] (group-by :declared files)
        :when            (next group)
        :let             [paths (sort (map :path group))]
        {:keys [path]}   group]
    [path (str "it declares `" declared "`, which is also declared by "
               (str/join ", " (map #(str "`" % "`") (remove #{path} paths)))
               ". Discovery returns that name once per file, so `require`"
               " loads only the file the classpath resolves and the"
               " other(s) never run.")]))

(defn discovery-defects
  "Every source file under `dirs` that will NOT reach the runner as its own
  namespace, as `[path complaint]` pairs sorted by path.

  Empty is the only acceptable answer.  Two rules, both required: a file
  must declare a namespace that resolves to ITS OWN FILE — by its path
  relative to the scan directory, or by resolution against the real
  classpath, which is what makes a nested `-d` an ordinary selection rather
  than a defect — and that `require` will not load from some source outside
  the scan instead (`own-path-defect`); and no two files may declare the
  SAME one (`collision-defects`).  The first alone leaves the shadowing door
  open inside the scan, because it derives each file's extension from that
  file and so clears a `.clj`/`.cljc` pair — and a repeated relative path
  under two roots — where each half spells its own path perfectly and only
  one of them ever loads."
  [dirs]
  (let [files      (scan dirs)
        discovered (set (map #(str/replace (:canonical %) "\\" "/") files))
        defect     (partial own-path-defect discovered)]
    (vec
      (sort
        (concat (for [f files :let [complaint (defect f)] :when complaint]
                  [(:path f) complaint])
                (collision-defects (remove defect files)))))))

(defn- verify-discovery!
  "Refuse the run when any file in this lane's discovery directories will
  not reach the runner as its own namespace.

  Called before `-main` rebinds anything, so the diagnostic goes straight
  to the real stderr rather than into the red-replay ring, and exits 1
  before a single test runs.  ASCII only: this text goes through the
  platform-default stderr encoding, where an em dash renders as a
  replacement character on a Windows console.  Silent when clean."
  [args]
  (when-let [dirs (discovery-dirs args)]
    (when-let [defects (seq (discovery-defects dirs))]
      (binding [*out* *err*]
        (println (str "\n[test-quiet] ERROR: " (count defects) " file(s) under "
                      (pr-str (vec (sort dirs)))
                      " will not reach the runner as their own namespace:"))
        (doseq [[path complaint] defects]
          (println (str "  " path))
          (println (str "      " complaint)))
        (println (str "cognitect.test-runner discovers a namespace by READING each"
                      " file's `(ns ...)` form,\n"
                      "once per file, and both ways that can go wrong end in a"
                      " green run:\n\n"
                      "  - a file it CANNOT READ is dropped silently"
                      " (clojure.tools.namespace.find/find-ns-decls-in-dir\n"
                      "    is a `keep` over `ignore-reader-exception`), so it"
                      " contributes no namespace and its\n"
                      "    tests simply do not run. One stray quote in an ns"
                      " docstring is enough to drop\n"
                      "    a whole file from a green run.\n"
                      "  - two files declaring ONE namespace yield that name"
                      " TWICE. `require` loads whichever\n"
                      "    the classpath resolves and skips the rest as already"
                      " loaded, then `run-tests` gets the\n"
                      "    symbol twice: one file runs twice, the other never"
                      " runs, and the tally goes UP - so\n"
                      "    neither the summary nor the test-count floor can"
                      " see the substitution.\n\n"
                      "Either way the suite still prints `0 failures, 0 errors.`"
                      " and exits 0. Fix the file, or\n"
                      "move it out of the discovery directory.\n"))
        (flush))
      (System/exit 1))))

;; ----------------------------------------------------------------------
;; What the lane's FIXTURES will do — a fixture the runner cannot CALL is
;; a namespace that ran nothing.
;;
;; `cljs.test` accepts a MAP fixture — `(use-fixtures :each {:before f
;; :after g})` — and this repo's `*_cljs_test.cljs` files use it throughout.
;; `clojure.test` validates nothing: `join-fixtures` folds whatever it is
;; handed into the chain and calls it with the test thunk, and a map called
;; with one argument is a KEY LOOKUP that returns nil, so the thunk never
;; runs.  Maps are `IFn`, so nothing throws.  cljs.test asserts on this
;; ("Fixtures may not be of mixed types"); the JVM half is the unguarded one.
;;
;; WHAT THE RULE MAY NOT DO IS REFUSE HONEST CODE.  The requirement is that
;; the entry APPLY the thunk, not that it be `fn?`: a var referring to a
;; fixture function, a multimethod and a `reify`d `IFn` all invoke the thunk
;; while failing `fn?`, and a rule that listed the types it would accept
;; would refuse each of them in turn.  That list can never be finished,
;; because anyone may write a new `IFn`.  The set of values Clojure invokes as a LOOKUP
;; can be, so `callable-fixture?` subtracts that closed set from `ifn?`
;; instead — see its docstring for the direction the residual error runs in.
;;
;; MEASURED: a two-line namespace whose single `deftest` asserts `(= 1 2)`
;; reports `Ran 0 tests containing 0 assertions. / 0 failures, 0 errors.`
;; and exits 0 under the map form.
;;
;; WHY THIS IS A RUNTIME RULE AND NOT A SCAN.  A scan sees text, and
;; `(use-fixtures :each fixture)` where `fixture` is a symbol bound to a map
;; is spelled exactly like the legitimate fn form.  `use-fixtures` writes
;; the args it was handed into the namespace's metadata, so reading that
;; metadata back sees the VALUE either way.  The tree holds a static half —
;; `tools/story/test/re_frame/story/meta_fixtures_test.clj` — and it is
;; scoped to that one artefact's `test/` tree, so it cannot see any other
;; artefact's files.

(def ^:private fixture-meta-keys
  "The namespace-metadata keys `clojure.test/use-fixtures` writes.  Each
  holds the ARGS the call was handed, so a map literal and a symbol bound
  to a map land here indistinguishably — which is the point."
  [:clojure.test/once-fixtures :clojure.test/each-fixtures])

(defn- lookup-invocation?
  "Whether invoking `x` with one argument LOOKS THAT ARGUMENT UP rather than
  APPLYING `x` to it.

  `clojure.lang` gives exactly these five values that meaning — a map, a set
  or a vector reads the argument as a key or an index into itself; a keyword
  or a symbol reads ITSELF as a key in the argument — and the list is CLOSED,
  because `clojure.lang` fixes it.  Nothing anyone writes joins it: a
  user-defined `IFn` invokes whatever its own body invokes, which makes it a
  function, not a lookup."
  [x]
  (or (map? x) (set? x) (vector? x) (keyword? x) (symbol? x)))

(defn- callable-fixture?
  "Whether `clojure.test` will INVOKE `fixture` with the test thunk rather
  than LOOK the thunk up inside it.

  `join-fixtures` folds the entries with `compose-fixtures` into
  `(fn [g] (f1 (fn [] (f2 g))))`, so the contract is behavioural — each entry
  is applied to one argument and must CALL it — and behaviour is NOT decidable
  from a value.  This predicate does not pretend to decide it.  It decides the
  one thing that is decidable, and it is written as a SUBTRACTION from `ifn?`
  rather than as a list of accepted types, because the two populations of
  `IFn` are not symmetrical (MEASURED on Clojure 1.12):

    - The APPLYING population is OPEN.  A fn, a multimethod, a `reify` or
      `deftype` implementing `IFn`, a proxy, a Var forwarding to any of them:
      all apply their argument, and no enumeration of them stays complete,
      because the next one has not been written yet.  Any enumeration
      refuses honest, working code while announcing that the namespace ran
      nothing: `fn?` alone rejects `(use-fixtures :each #'lifecycle)`, and
      `fn?` or `MultiFn` or a Var resolving to one rejects
      `(reify clojure.lang.IFn (invoke [_ t] (t)))`.
    - The LOOKUP population is CLOSED — see `lookup-invocation?`.

  So the rule is `ifn?` MINUS the closed set, and everything else is accepted.
  Read the direction of the residual error deliberately: an exotic `IFn` that
  ignores its argument is accepted and its namespace stays silently zeroed,
  which is the defect this rule catches, surviving in a corner.  That is the right trade
  against a guard that reds idiomatic code, and it is the whole reason this is
  a subtraction — a guard that refuses valid code is worse than the defect it
  catches.

  A Var is in neither population: it is an INDIRECTION, and `Var.invoke`
  forwards to its root, so the question about a Var is the question about its
  root.  Hence the recursion rather than a blanket `var?` — a Var whose root
  is a map swallows the thunk exactly as the bare map does."
  [fixture]
  (if (var? fixture)
    (recur (deref fixture))
    (and (ifn? fixture)
         (not (lookup-invocation? fixture)))))

(defn uncallable-fixtures
  "Every `[ns-sym fixture-key fixture]` registered on `namespaces` that
  `clojure.test` will LOOK THE TEST THUNK UP IN rather than call, sorted by
  namespace name.

  Empty is the only acceptable answer, and it is the WEAKER of the two
  answers: emptiness says no fixture is one of the values Clojure invokes as
  a lookup, not that every fixture applies its thunk.  `callable-fixture?` is
  the test; see its docstring for why the rule subtracts a closed set from
  `ifn?` instead of listing the function types it accepts."
  [namespaces]
  (vec (sort-by (comp str first)
                (for [ns-obj    namespaces
                      meta-key  fixture-meta-keys
                      fixture   (get (meta ns-obj) meta-key)
                      :when     (not (callable-fixture? fixture))]
                  [(ns-name ns-obj) meta-key fixture]))))

(defn- uncallable-fixture-complaint
  "The one-screen operator message for `offenders`.  ASCII only: it goes
  through the platform-default stderr encoding, where an em dash renders as
  a replacement character on a Windows console."
  [offenders]
  (str "this lane registered " (count offenders)
       " fixture(s) that are DATA rather than functions, so the tests in the"
       " namespace(s) below did not run:\n"
       (str/join "\n" (for [[ns-sym meta-key fixture] offenders]
                        (str "  " ns-sym " " meta-key " -> "
                             (.getName (class fixture)))))
       "\nclojure.test applies each fixture to the test thunk. Invoking a"
       " map, set, vector, keyword or symbol with one argument LOOKS THAT"
       " ARGUMENT UP as a key or an index instead of calling it, so the thunk"
       " never runs and the tally stays green.\n"
       "Those five values are all this rule reports: it does not certify any"
       " other fixture, only that none here is one Clojure invokes as a"
       " lookup.\n"
       "A fixture must APPLY its argument -- a fn, a multimethod, a var"
       " referring to one, or any IFn that calls the thunk. The"
       " {:before f :after g} map is cljs.test-only --"
       " (use-fixtures :each (fn [t] (before) (t) (after))).\n"))

;; ----------------------------------------------------------------------
;; Which namespaces, and which of their vars, this invocation runs.

(defn selected-namespaces
  "The namespaces `cognitect.test-runner/test` selects for `options`, as
  symbols: every namespace `find-namespaces-in-dir` reads under the
  discovery dirs, kept by cognitect's own selector (`-n` / `-r`, or
  `.*-test$` when neither is given).

  Computed the way `test` computes it, from the same functions, because the
  summary `test` returns does not say which namespaces it ran — and the
  fixture audit has to be about exactly those, not about whatever else the
  JVM happens to have loaded."
  [options]
  (->> (or (:dir options) #{"test"})
       (map io/file)
       (mapcat ns-find/find-namespaces-in-dir)
       (filter (#'cognitect.test-runner/ns-filter options))))

(defn- namespace-tag
  "The `-i` / `-e` keyword `tag` as a predicate over a var's metadata that
  answers for the var's NAMESPACE too: true when the var carries `tag`, or
  when the `(ns ...)` form that interned it does."
  [tag]
  (fn [var-meta]
    (or (get var-meta tag)
        (some-> (:ns var-meta) meta (get tag)))))

(defn- with-namespace-tags
  "`options` with every `-i` and `-e` keyword widened by `namespace-tag`.

  cognitect's var filter applies these as `((apply some-fn tags) (meta v))`,
  so a predicate over the var's metadata stands exactly where the keyword
  did, and every other option reaches `test` untouched."
  [options]
  (reduce (fn [opts option-key]
            (cond-> opts
              (contains? opts option-key)
              (update option-key #(set (map namespace-tag %)))))
          options
          [:include :exclude]))

;; ----------------------------------------------------------------------
;; The verdict.

(defn- lane-claim-complaint
  "Why this run is red beyond its own tally, or nil.

  `uncallable-fixtures` over the `namespaces` this invocation selected is
  read first: an uncallable fixture zeroes its whole namespace, so a lane
  zeroed that way would otherwise be reported as the discovery problem it
  does not have.  Then the lane's claim: a probe lane must have executed no
  test, and a suite lane at least one (see the ns docstring).  ASCII only: this text goes through the
  platform-default stderr encoding, where an em dash renders as a
  replacement character on a Windows console."
  [{:keys [probe?]} summary namespaces]
  (let [test-count (or (:test summary) 0)
        uncallable (uncallable-fixtures namespaces)]
    (cond
      (seq uncallable)
      (uncallable-fixture-complaint uncallable)

      ;; A probe that reached its summary has already proved what it
      ;; claims: deps resolved and the discovery dirs were scanned. All
      ;; that is left is that it really is a probe.
      probe?
      (when (pos? test-count)
        (str "this lane is declared a classpath probe (" probe-flag
             ") but executed " test-count " test(s).\n"
             "A lane with tests claims COVERAGE, not resolution:"
             " drop " probe-flag " from its `:test` alias"
             " `:main-opts`\n"
             "so the test-count floor applies to it.\n"))

      (zero? test-count)
      (str "this run executed 0 test(s), below the floor of 1.\n"
           "A suite lane that discovered no tests is a"
           " configuration error: a `-r`/`-n` selector matching"
           " nothing, a `:test` alias\n"
           "missing its `:extra-paths [\"test\"]`, a renamed test"
           " file. It is not a pass. Failing the run"
           ".\n"
           "If this lane claims only that its classpath RESOLVES,"
           " declare that with " probe-flag " instead.\n"))))

(defn- run-lane!
  "Run `options` through `cognitect.test-runner/test` with stderr buffered,
  then decide the verdict and end the JVM with it.

  The test-driver thread's `*err*` is bound to the ring and the
  process-global `System/err` is swapped to it, so library code logging
  through either is captured.  The original `*err*` is kept for the replay
  and for this runner's own diagnostics, so neither is fed back into the
  ring.  On RED the ring is replayed under a `[test-quiet]` header; on
  green it is dropped.  cognitect has no catch around its `require` and
  run, so a namespace that throws on load, or a throwing `:once` fixture,
  leaves `test` without a summary: the ring is replayed on the way out and
  the throw rethrown, and `clojure.main` reports it and exits 1.

  stdout is flushed before the exit, because `System/exit` runs no
  `finally` and a bare `(print ...)` with no trailing newline would
  otherwise be lost exactly when a run needs it."
  [options lane-claim]
  (let [original-err  *err*
        stderr-ring   (StringBuilder.)
        stderr-writer (buffering-stderr-writer stderr-ring)
        ;; Raw `System/err` bytes reach the same ring as `*err*`. Each chunk
        ;; is decoded to a `String` and appended to the ring (this buffer is
        ;; the diagnostic/red-replay path only — never the green-run
        ;; summary). Note the encoding asymmetry between the two `write`
        ;; proxy arities:
        ;;   - write(byte[],off,len): a chatty library prints via
        ;;     `PrintStream.print(...)`/`write(byte[])`, which the autoFlush
        ;;     UTF-8 `PrintStream` below routes through this multi-byte
        ;;     arity. The chunk is decoded as one unit using the JVM's
        ;;     default charset, so non-ASCII fidelity depends on that
        ;;     default also being UTF-8.
        ;;   - write(int): the single-byte arity decodes that ONE byte on its
        ;;     own. A multi-byte UTF-8 sequence delivered one byte at a time
        ;;     — only a raw `System.err.write(int)` per byte hits this path;
        ;;     the `PrintStream` itself never splits a code point across
        ;;     single-byte writes — would decode each byte separately and
        ;;     mangle the character. This arity is therefore best-effort /
        ;;     ASCII-only by design. Buffering raw bytes to decode once at
        ;;     replay is not a clean fit here — the ring is a shared char
        ;;     `Writer` that `*err*` also writes into directly, so there is
        ;;     no single byte stream to defer-decode.
        system-err-bridge
        (proxy [java.io.OutputStream] []
          (write
            ([byte-value]
             ;; proxy dispatches by arity; the 1-arg form is write(int) —
             ;; the low 8 bits are the byte (0-255), so use
             ;; `unchecked-byte` (a plain `byte` cast throws for 128-255).
             (.write stderr-writer
                     (String. (byte-array [(unchecked-byte byte-value)]))))
            ([byte-buffer offset length]
             (.write stderr-writer
                     (String. ^bytes byte-buffer (int offset) (int length))))))]
    (System/setErr (java.io.PrintStream. system-err-bridge true "UTF-8"))
    (let [summary   (binding [*err* (java.io.PrintWriter. stderr-writer)]
                      (try
                        (cognitect.test-runner/test (with-namespace-tags options))
                        (catch Throwable thrown
                          (replay-captured-stderr! stderr-ring original-err
                                                   "the run threw before its summary")
                          (throw thrown))))
          red?      (pos? (+ (:fail summary 0) (:error summary 0)))
          complaint (lane-claim-complaint
                      lane-claim
                      summary
                      (keep find-ns (selected-namespaces options)))]
      (flush)
      (when red?
        (replay-captured-stderr! stderr-ring original-err "the run was RED"))
      (when complaint
        (.write original-err (str "\n[test-quiet] ERROR: " complaint))
        (.flush original-err))
      (System/exit (if (or red? complaint) 1 0)))))

(defn -main [& args]
  (let [;; This lane's claim. `--probe` is ours and is stripped from what
        ;; cognitect sees.
        [{:keys [probe?]} forwarded-args] (split-runner-args args)
        lane-claim {:probe? probe?}
        ;; And what this lane will DISCOVER, before a single test runs: a
        ;; file the reader cannot read is invisible to discovery, so no
        ;; later check has anything left to notice.
        _          (verify-discovery! forwarded-args)
        {:keys [options errors]} (cli/parse-opts forwarded-args
                                                 cognitect.test-runner/cli-options)]
    (if (or errors (:test-help options))
      (apply cognitect.test-runner/-main forwarded-args)
      (run-lane! options lane-claim))))
