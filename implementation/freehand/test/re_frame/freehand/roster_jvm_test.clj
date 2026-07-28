(ns re-frame.freehand.roster-jvm-test
  "The roster's declarations are RESOLVED — rf2-drpa3.182.7 acceptance 1.

  `roster-cljs-test` proves the roster is well-formed data. Well-formed
  data is not an identity: a record can name `re-frame.freehand.form` as
  its source and `…-dom-cljs-test` as its mounted entry, validate perfectly,
  and both be strings pointing at nothing. A record that survives the
  deletion of the thing it describes is a comment.

  This file closes that. It needs a filesystem, so it is the JVM's:

    * every namespace a record names — source and proof alike — resolves to
      a file that exists;
    * every proof namespace CITES the law it claims to prove, by id, in its
      own text, so a reader who opens the suite the roster sent them to
      finds the id rather than having to infer the connection;
    * and a mounted entry is a mounted suite — it rides the browser lane's
      own naming, rather than being a headless file the record described as
      mounted.

  The last one matters more than it looks. The browser lane is selected by
  filename suffix, so a record naming a `-cljs-test` namespace as its
  `:mounted` tier would be pointing at a suite that never runs in a
  browser, and every projection would go on reporting a mounted proof that
  does not exist."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.freehand.roster :as roster])
  (:import [clojure.lang ReaderConditional]))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(def ^:private extensions
  "The extensions a namespace may live under, in the order the roster looks.
  `.cljc` first because most of the substrate is cross-host; `.cljs` is
  there because a mounted suite is ClojureScript-only and is still a file
  this JVM run must be able to find on the test classpath."
  [".cljc" ".clj" ".cljs"])

(defn- ns->resource
  "The classpath resource backing namespace symbol `ns-sym`, or nil."
  [ns-sym]
  (let [stem (-> (name ns-sym)
                 (str/replace "-" "_")
                 (str/replace "." "/"))]
    (some (fn [ext] (io/resource (str stem ext))) extensions)))

(defn- source-of
  "The source text of namespace symbol `ns-sym`, or nil where it resolves to
  no file. Nil rather than a throw, so a row that also has something to say
  about a missing file says it."
  [ns-sym]
  (some-> (ns->resource ns-sym) slurp))

(defn- declared-namespaces
  "Every namespace `record` names, as `[field ns-sym]` pairs — its sources
  and every proof at every tier. One sequence so the resolution row below
  treats a source and a proof identically: both are claims about a file."
  [record]
  (concat (map (fn [s] [:source s]) (get-in record [:fh/record :source]))
          (map (juxt :tier :ns) (roster/proofs record))))

(deftest every-namespace-the-roster-names-exists
  (testing "Per rf2-drpa3.182.7 acceptance 1: a record's declarations are
            addresses, not adjectives. Every source namespace and every
            proof namespace resolves to a file on the classpath, so
            deleting or renaming the thing a record describes reds THIS row
            — naming the law — rather than leaving the roster quietly
            describing something that is gone."
    (doseq [record roster/records
            [field ns-sym] (declared-namespaces record)]
      (is (some? (ns->resource ns-sym))
          (str (:fh/id record) " — " field " names " ns-sym
               ", which resolves to no file on the classpath")))))

(deftest the-roster-names-at-least-one-namespace-per-record
  (testing "NON-VACUITY for the row above. A record whose declarations were
            all empty would pass every resolution check by having nothing to
            resolve, which is the failure mode a table-driven gate is most
            prone to."
    (doseq [record roster/records]
      (is (<= 3 (count (declared-namespaces record)))
          (str (:fh/id record) " names its source and at least one proof")))))

;; ---------------------------------------------------------------------------
;; The suite the roster sends a reader to NAMES the law
;; ---------------------------------------------------------------------------

(deftest every-proof-namespace-cites-the-law-it-proves
  (testing "Per rf2-drpa3.182.7 acceptance 1: a claim has ONE identity
            across every projection, and failures name it. A roster row
            that sent a reader to a suite which never mentions the id would
            have the identity running one way only — findable from the
            roster, invisible from the code. Every proof namespace carries
            its `FH-…` id in its own text, so the link is legible from both
            ends and a `git grep FH-CTRL-018` finds the whole vertical."
    (doseq [record            roster/records
            {:keys [tier ns]} (roster/proofs record)]
      (let [id  (:fh/id record)
            res (ns->resource ns)]
        (is (and res (str/includes? (slurp res) id))
            (str id " — the " (name tier) " proof " ns
                 " does not cite the law it proves"))))))

;; ---------------------------------------------------------------------------
;; A mounted entry is a MOUNTED suite
;; ---------------------------------------------------------------------------

(deftest a-mounted-tier-names-a-suite-that-runs-in-a-browser
  (testing "The browser lane selects by filename suffix, so `-dom-cljs-test`
            is not a style — it is what makes a suite run against a real
            `react-dom/client` commit. A record naming an ordinary
            `-cljs-test` namespace as its mounted tier would advertise a
            mounted proof that the browser lane never schedules, and no
            other projection would notice."
    (doseq [record roster/records
            entry  (roster/tier record :mounted)]
      (is (str/ends-with? (name (:ns entry)) "-dom-cljs-test")
          (str (:fh/id record) " — the mounted tier names " (:ns entry)
               ", which the browser lane does not select")))))

(deftest a-structural-tier-does-not-name-a-browser-suite
  (testing "The converse, and it is a real error rather than a symmetry
            exercise: a structural tier is the claim that a law is provable
            HEADLESSLY, on both hosts, from one `.cljc`. Pointing it at a
            `-dom-cljs-test` would make the record say a browser run is the
            headless proof."
    (doseq [record roster/records
            entry  (roster/tier record :structural)]
      (is (not (str/ends-with? (name (:ns entry)) "-dom-cljs-test"))
          (str (:fh/id record) " — the structural tier names a browser suite, "
               (:ns entry))))))

;; ---------------------------------------------------------------------------
;; Reading a namespace as FORMS — what the compiler receives, rather than
;; what the file looks like
;; ---------------------------------------------------------------------------
;;
;; Every rule below this line asks a question about a PROGRAM: does a test
;; that runs reach this call, does this namespace define a test at all. A
;; scanner over characters cannot answer either, and the merged-PR audit of
;; #7178 proved it against this file's own predecessor. It blanked line
;; comments and string literals with a four-state character walk — careful,
;; driven, and blind to `#_`, so a reader-discarded `#_(ms/residue-clean! …)`
;; satisfied a rule about calling it while the compiled program contained no
;; call. An orphan helper whose body called it and which no test invoked
;; satisfied it too.
;;
;; Neither is fixable with another token. `#_` discards the NEXT FORM, so
;; `#_ #_ a b` discards two and `#_ (a (b))` discards a whole tree; any rule
;; spelled over characters is wrong on those while looking right on the one
;; case its author had in mind. And reachability is not a lexical property at
;; all. So the reader is asked instead — a discarded form simply is not in
;; what it answers — and the calls are followed.

(defn read-forms
  "Every top-level form of Clojure source `text`, as the READER produces
  them. Pure and exported: a checker nobody can drive is the thing the
  audits keep finding.

  `clojure.tools.reader` rather than `clojure.core/read` for two reasons
  that both matter here: it is the reader ClojureScript itself reads these
  files with, and it takes the settings a real proof namespace needs —
  `#js` and every other tagged literal degrade to their value, and
  `::alias/kw` resolves — where core's reader throws. `#=` eval is off:
  reading a file must never run it.

  Reader conditionals are PRESERVED rather than resolved. The roster does
  not know which host compiles a `.cljc` proof, so both branches belong to
  some compiled program and [[top-level-forms]] and [[symbols-in]] walk
  into both.

  A source that will not read THROWS, and deliberately. A checker that
  swallowed the failure would answer 'no call here' for a file it never
  managed to look at — a red for the wrong reason at best, and at worst a
  green from a rule that reads the empty program."
  [^String text]
  (binding [rdr/*default-data-reader-fn* (fn [_tag v] v)
            rdr/*read-eval*              false
            rdr/*alias-map*              identity]
    (let [reader (rt/string-push-back-reader text)
          eof    (Object.)
          opts   {:eof eof :read-cond :preserve}]
      (loop [forms []]
        (let [form (rdr/read opts reader)]
          (if (identical? form eof)
            forms
            (recur (conj forms form))))))))

(defn- branch-forms
  "The forms of every branch of preserved reader conditional `rc`. A
  splicing `#?@` branch is a collection of forms; a plain one is a single
  form."
  [rc]
  (let [bs (take-nth 2 (rest (:form rc)))]
    (if (:splicing? rc) (apply concat bs) bs)))

(defn- top-level-forms
  "`forms` with every top-level reader conditional replaced by the forms of
  all its branches. A `.cljc` suite that wraps its `deftest`s in `#?(:cljs
  …)` — several in this corpus do — would otherwise present the analysis
  with one opaque object where its tests are."
  [forms]
  (mapcat (fn [form]
            (if (instance? ReaderConditional form)
              (top-level-forms (branch-forms form))
              [form]))
          forms))

(defn- symbols-in
  "Every symbol occurring anywhere in `form`.

  Reader conditionals are walked into: they are the one shape a plain
  collection walk does not descend, and a call reached only from a `.cljs`
  branch is still a call."
  [form]
  (let [acc (volatile! (transient #{}))]
    ((fn walk [x]
       (cond
         (symbol? x)                     (vswap! acc conj! x)
         (instance? ReaderConditional x) (walk (:form x))
         (coll? x)                       (run! walk x)
         :else                           nil))
     form)
    (persistent! @acc)))

(defn- head-symbol
  "The symbol in head position of `form`, or nil when `form` is not a list
  headed by one."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (first form)))

(defn- definition-form?
  "Is `form` a top-level DEFINITION — something whose body runs only when
  something else reaches its name?

  `def`-prefixed head, and a symbol where the name goes. The second half is
  what keeps `(default-options …)` from reading as a definition of nothing."
  [form]
  (when-let [h (head-symbol form)]
    (and (str/starts-with? (name h) "def")
         (symbol? (second form)))))

(defn- test-form?
  "Is `form` a `deftest` — a definition the RUNNER reaches on its own?"
  [form]
  (when-let [h (head-symbol form)]
    (str/starts-with? (name h) "deftest")))

(defn- call-graph
  "`forms` split into what EXECUTES and what only executes when something
  reaches it: `{:roots #{sym …} :nodes {name #{sym …}}}`.

  A `deftest` is a root because the runner calls it. So is any top-level
  form that is not a definition, because loading the namespace runs it —
  which is what makes a helper registered through `use-fixtures` reachable
  without special-casing `use-fixtures`. `(ns …)` contributes nothing but
  aliases, and `(comment …)` is a body that never runs, so both are
  dropped; the second is a false green the predecessor rule had and this
  one does not.

  A definition's own name is excluded from its body's symbols (the scan
  starts past the head and the name), so `(defn residue-clean! …)` is not a
  call to itself.

  Locals are NOT tracked: a `let` binding or a parameter whose name equals
  a top-level definition's would count as a reference to it. That is an
  over-approximation of reachability, accepted because the alternative is a
  resolving analyzer and the shape it would matter for — one namespace
  where a helper and a local share a name AND the helper is otherwise dead
  — does not occur. The false greens this exists to close are not that."
  [forms]
  (reduce (fn [graph form]
            (cond
              (contains? #{'ns 'comment} (head-symbol form))
              graph

              (definition-form? form)
              (let [syms (symbols-in (drop 2 form))]
                (cond-> (update-in graph [:nodes (second form)] (fnil into #{}) syms)
                  (test-form? form) (update :roots into syms)))

              :else
              (update graph :roots into (symbols-in form))))
          {:roots #{} :nodes {}}
          (top-level-forms forms)))

(defn reachable-symbols
  "Every symbol the program in `forms` can reach from something that runs —
  the roots of [[call-graph]], closed over the definitions they name.

  Exported so the rows below can drive reachability itself rather than only
  its two consumers."
  [forms]
  (let [{:keys [roots nodes]} (call-graph forms)]
    (loop [reached roots]
      (let [grown (reduce-kv (fn [acc nm syms]
                               (if (contains? reached nm) (into acc syms) acc))
                             reached
                             nodes)]
        (if (= grown reached) reached (recur grown))))))

;; ---------------------------------------------------------------------------
;; `:residue :none` is a claim a checker can refuse — acceptance 3
;; ---------------------------------------------------------------------------

(def ^:private residue-assertion
  "The shared assertion the rf2-drpa3.182.7 acceptance names, matched by its
  SIMPLE name so `ms/residue-clean!`, a `:refer`red `residue-clean!` and any
  other alias all count while `my-residue-clean!` does not.

  Naming exactly one assertion is the point. A predecessor rule listed five
  look-alike spellings (`(zero? …)`, `(empty? …)`, `nothing-survived`, …) so
  that a suite reading its own book inline still counted, and the cost was
  that any of them anywhere in the file counted, prose included. One
  assertion is both narrower and stronger, and it carries its own
  non-vacuity: `residue-clean!` reds a suite that tore no root down through
  the facade, so reaching it cannot be satisfied by a mount that never
  happened."
  "residue-clean!")

(defn residue-asserted?
  "Does a test in `source` REACH the shared residue assertion?

  Reachability rather than presence, and that is the whole difference. A
  helper whose body calls it and which no test invokes is text in a file
  that the run never executes — the 'helper never called after teardown'
  class the merged-PR audit of #7105 named and the audit of #7178 found
  still open. Following the calls from the things that run is what answers
  the question the claim actually makes."
  [source]
  (boolean (some #(= residue-assertion (name %))
                 (reachable-symbols (read-forms source)))))

;; ---------------------------------------------------------------------------
;; `:prose :executable` is a claim a checker can refuse — acceptance 4
;; ---------------------------------------------------------------------------

(defn defines-a-test?
  "Does `source` define at least one test the runner will execute?

  The floor under a record's prose status. `:executable` and
  `:expected-failure` both say the prose describes something a RUN does —
  the behaviour in the first case, the diagnostic in the second — and a
  proof namespace that defines no test runs nothing, whatever the record
  says about it."
  [source]
  (boolean (some test-form? (top-level-forms (read-forms source)))))

(deftest a-residue-none-claim-is-reached-by-a-test-that-runs
  (testing "Per rf2-drpa3.182.7 acceptance 3: mounted cleanup is EXACT, and
            `:residue :none` is the record saying so. An unenforced `:none`
            would be the most expensive kind of green — a leaked React root
            contaminates every later suite sharing the process, and this
            corpus has seen one leak produce failures across dozens of
            unrelated suites — so the claim is checked rather than read.

            In every mounted projection of a `:residue :none` record, a
            `deftest` REACHES `mount-support/residue-clean!` — directly or
            through the helpers it calls — so the assertion runs after
            teardown over the facade's own books plus whatever substrate
            books the record names. A record whose suites only unmount and
            remove says `:unasserted`: honest, countable, and what two of
            the three initial members said until they took the shared
            assertion (rf2-n9rzw).

            What this row proves and what it does not, stated plainly. The
            executed evidence is the browser lane's: `residue-clean!` runs
            in Chromium and its messages name the law. What is checked HERE
            is that the run REACHES the call — so a `:none` cannot be made
            by a suite that never gets there, which is where the two
            previous versions of this rule both failed. The first accepted
            any of five look-alike tokens anywhere in the file (merged-PR
            audit of #7105); the second required a call site but read the
            file as characters, so a `#_`-discarded call and a helper
            nothing invoked both satisfied it (merged-PR audit of #7178)."
    (doseq [record roster/records
            :when  (= :none (get-in record [:fh/record :evidence :residue]))
            entry  (roster/tier record :mounted)]
      (let [text (source-of (:ns entry))]
        (is (and text (residue-asserted? text))
            (str (:fh/id record) " claims :residue :none, but no test in its "
                 "mounted proof " (:ns entry) " reaches "
                 "mount-support/residue-clean! — either it reads the books "
                 "empty after teardown, or the record says :unasserted"))))))

(deftest the-residue-rule-refuses-every-shape-that-only-looks-like-a-proof
  (testing "NON-VACUITY, and specifically for the false positives two
            merged-PR audits reproduced against two predecessor rules. A
            gate that has only ever seen input it passes has not been
            tested, and this one guards a claim whose failure mode is silent
            contamination of unrelated suites.

            The first four rows below are the #7105 set, which the character
            scanner already refused. The next four are the #7178 set, which
            it did not: every one of them was reproduced answering TRUE
            against the shipped rule, and each is here because a token-level
            fix would have closed at most one of them."
    (doseq [[note source]
            [["an unmount-and-remove teardown asserts nothing about what survived"
              "(defn- teardown! [c r] (.unmount r) (.remove c) nil)"]

             ["tearing down through the SHARED lifecycle is still not reading its books"
              "(defn- finish! [c r] (ms/destroy-root! c r))"]

             ["a COMMENT naming the assertion is prose about the code, not the code"
              ";; every row ends at ms/residue-clean!, which reads the books empty\n(ms/destroy-root! c r)"]

             ["and so is the assertion named inside a docstring"
              "(defn finish! \"tears down, then ms/residue-clean!\" [c r] (ms/destroy-root! c r))"]

             ["an UNRELATED emptiness read is not a residue assertion"
              "(is (= [] (rows-rendered container)))\n(is (zero? (retry-count)))"]

             ["nor is the assertion named in a message string"
              "(is (pos? n) \"call ms/residue-clean! after teardown\")"]

             ["nor a var whose name merely ends in it"
              "(def my-residue-clean! 1)"]

             ;; --- the shapes the character scanner passed -----------------
             ["a READER-DISCARDED call is not in the program the compiler receives"
              "(deftest t (mount!) #_(ms/residue-clean! \"FH-PROBE-001 — after teardown\"))"]

             ["and `#_ #_` discards TWO forms, which is why this is the reader's
               job and not a token's — a scanner that skipped 'the #_ and the
               next form' would pass this while looking right on the row above"
              (str "(deftest t (mount!)\n"
                   "  #_ #_ (ms/residue-clean! \"first\") (ms/residue-clean! \"second\"))")]

             ["an ORPHAN helper is a definition, not an invocation: nothing the
               runner reaches ever calls it, so the assertion never executes"
              (str "(defn- released! [where] (ms/residue-clean! where))\n"
                   "(deftest t (mount!) (ms/destroy-root! container root))")]

             ["and a call inside a `(comment …)` form is a body that never runs"
              "(comment (ms/residue-clean! \"FH-PROBE-001\"))"]]]
      (is (not (residue-asserted? source)) note))

    (doseq [[note source]
            [["a call through the facade's alias is the proof"
              "(deftest t (ms/residue-clean! \"FH-CTRL-018 — after teardown\"))"]

             ["so is a referred call"
              "(deftest t (residue-clean! where [[\"the connection table\" #(count @table)]]))"]

             ["and a call carrying books, after a shared teardown, in the real shape"
              (str "(deftest t\n"
                   "  (ms/destroy-root! container root)\n"
                   "  ;; and now read what survived it\n"
                   "  (ms/residue-clean! where [[\"the registry\" #(root/live-root-ids)]]))")]

             ["a helper the suite CALLS is the proof wherever it is written —
               which is the shape all four real mounted projections use"
              (str "(defn- released! [where] (ms/residue-clean! where))\n"
                   "(deftest t (mount!) (released! \"FH-PROBE-001 — after teardown\"))")]

             ["and so is one reached through another helper"
              (str "(defn- released! [w] (ms/residue-clean! w))\n"
                   "(defn- finish! [c r w] (ms/destroy-root! c r) (released! w))\n"
                   "(deftest t (finish! container root \"FH-PROBE-001\"))")]

             ["a helper the runner reaches through `use-fixtures` executes too —
               a top-level form is run by loading the namespace, so this needs
               no rule of its own"
              (str "(defn- clean! [t] (t) (ms/residue-clean! \"FH-PROBE-001\"))\n"
                   "(use-fixtures :each clean!)")]

             ["and a call in a reader-conditional branch is a call in the
               program that branch compiles into"
              "(deftest t #?(:cljs (ms/residue-clean! \"FH-PROBE-001\") :clj nil))"]]]
      (is (residue-asserted? source) note))))

(deftest the-reader-is-what-decides-what-is-in-the-program
  (testing "The two rules above are exactly as good as [[read-forms]] and
            [[reachable-symbols]], so both are driven rather than trusted.

            This replaces a driven character scanner, which is the point
            worth keeping: that scanner was careful, its own row asserted
            the shapes a naive stripper gets wrong, and it was still blind
            to `#_` — because a discard is a property of the READER's
            grammar and nothing spelled over characters has access to it.
            Asking the reader is not a bigger hammer; it is the only thing
            that can answer the question."
    (testing "a discard removes the form, and `#_ #_` removes two"
      (is (= '[(a) (c)] (read-forms "(a) #_(b) (c)")))
      (is (= '[(a) (d)] (read-forms "(a) #_ #_ (b) (c) (d)")))
      (is (= '[(a) (c)] (read-forms "(a) #_(b (nested (deep))) (c)"))
          "and it removes a whole tree, not a line"))

    (testing "while the shapes that make a text scanner wrong are simply not
              text any more"
      (is (= '[(f "a ; b")] (read-forms "(f \"a ; b\") ; c"))
          "a semicolon inside a string never opened a comment")
      (is (= '[(f \; \")] (read-forms "(f \\; \\\")"))
          "and a char literal is a character")
      (is (= 1 (count (read-forms "(re-find #\"\\(zero\\?\" x)")))
          "a regex literal is one form, and its pattern is not code"))

    (testing "a real proof namespace reads — every tagged literal degrades,
              so `#js` in a mounted suite is a value rather than a throw"
      (is (seq (read-forms "(def o #js {:a 1}) (def xs #js [1 2])")))
      (is (seq (read-forms (source-of 're-frame.freehand.host-mounted-dom-cljs-test)))
          "and so does the one that carries nine of them"))

    (testing "reachability is closed over the definitions the roots name, and
              stops at the ones nothing names"
      (is (contains? (reachable-symbols (read-forms "(defn- h [] (target)) (deftest t (h))"))
                     'target))
      (is (not (contains? (reachable-symbols (read-forms "(defn- h [] (target)) (deftest t (other))"))
                          'target))
          "an orphan definition contributes nothing")
      (is (not (contains? (reachable-symbols (read-forms "(defn target [] 1) (deftest t (other))"))
                          'target))
          "and a definition is not a call to itself"))))

(deftest an-executable-prose-claim-names-suites-that-execute
  (testing "Per rf2-drpa3.182.7 acceptance 4, clause 2: a record's `:prose`
            status must be a property rather than a spelling.

            `:executable` and `:expected-failure` both say the prose
            describes what a RUN does. Until this row, the only thing held
            about either was membership of a closed set — the record could
            name a views file, a helper namespace or a fixture module as its
            proof and still declare its prose executable, with the whole
            gate green. That is precisely the shape `:residue :none` had
            before it was held to a call (merged-PR audits #7105, #7178),
            and it is the same fix: read the file and ask.

            `:illustrative` is exempt, and that is what makes it worth
            saying. A record that declines the executable claim is making no
            claim to check; a vocabulary where every value cost the same
            would not be a vocabulary.

            What this row does NOT reach is the guide prose itself — that a
            paragraph labelled illustrative shows no runnable code. Joining
            a law to the page that teaches it needs a guide key on
            `:fh/record`, which lives under `spec/`; rf2-fby7o owns that
            half."
    (doseq [record roster/records
            :when  (contains? roster/executable-prose-statuses
                              (get-in record [:fh/record :prose]))
            {:keys [tier ns]} (roster/proofs record)]
      (let [text (source-of ns)]
        (is (and text (defines-a-test? text))
            (str (:fh/id record) " declares :prose "
                 (get-in record [:fh/record :prose]) ", but its "
                 (name tier) " proof " ns " defines no test — a namespace "
                 "that runs nothing cannot be what makes prose executable"))))))

(deftest the-executable-prose-rule-refuses-a-namespace-that-runs-nothing
  (testing "NON-VACUITY for the row above, driven the same way the residue
            rule is — including against REAL files, because a rule refuted
            only by strings has never met the corpus it guards."
    (doseq [[note source]
            [["a helper namespace defines no test"
              "(ns x) (defn- helper [] 1)"]

             ["a discarded test is not a test"
              "(ns x) #_(deftest t (is true))"]

             ["nor is one inside a `(comment …)` form"
              "(ns x) (comment (deftest t (is true)))"]

             ["nor one written in a comment"
              "(ns x) ;; (deftest t (is true))\n(def views [])"]]]
      (is (not (defines-a-test? source)) note))

    (doseq [[note source]
            [["a test is a test" "(ns x) (deftest t (is true))"]

             ["including one a `.cljc` suite puts behind a reader conditional,
               which several in this corpus do"
              "(ns x) #?(:cljs (deftest t (is true)))"]]]
      (is (defines-a-test? source) note))

    (testing "and against the corpus: the views namespace a record could name
              as a proof runs nothing, while the suite it should name does"
      (is (not (defines-a-test? (source-of 're-frame.freehand.behavior-views)))
          "behavior-views renders the declarations the FH-BEHAVIOR-* suites use")
      (is (not (defines-a-test? (source-of 're-frame.freehand.mount-support)))
          "and the shared mounted-lifecycle facade is a facade")
      (is (defines-a-test? (source-of 're-frame.freehand.host-door-cljs-test))
          "while FH-REACT-007's structural proof does define tests"))))

(deftest every-record-declares-a-residue-claim-it-can-be-held-to
  (testing "Per rf2-drpa3.182.7 acceptance 3: the roster's value here is
            that the residue claim is COUNTABLE rather than hidden. This
            row is the census, and it holds in both directions — a record
            regressing from `:none` to `:unasserted` reds, and so does a
            newly enrolled witness whose claim is neither.

            It is stated over the WHOLE roster rather than over a hardcoded
            three, which is what lets the control witnesses enrol without
            editing this file. `:evidence` is a required record key, so
            there is no third answer — a record cannot decline to say.

            The initial three are named separately because acceptance 3 was
            about them, and the gap each closed was a different one.
            FH-CTRL-018 tore its root down and asserted nothing about what
            survived. FH-REACT-007 reset its registries in a per-test
            `:init-fn`, which is cleanup BEFORE a test rather than evidence
            about the one that just ran — a retained boundary was masked by
            the next reset instead of reported. Both now end at the shared
            `residue-clean!`, read after teardown (rf2-n9rzw)."
    (doseq [record roster/records]
      (is (contains? roster/residue-statuses
                     (get-in record [:fh/record :evidence :residue]))
          (str (:fh/id record) " declares what a mounted run leaves behind")))
    (is (= {"FH-BEHAVIOR-005" :none
            "FH-CTRL-018"     :none
            "FH-REACT-007"    :none}
           (into {} (map (fn [id]
                           [id (get-in (roster/by-id id)
                                       [:fh/record :evidence :residue])])
                         roster/initial-spine-ids)))
        "and the three initial-spine members have each EARNED :none")))

;; ---------------------------------------------------------------------------
;; The resolver itself
;; ---------------------------------------------------------------------------

(deftest the-resolver-answers-nil-for-a-namespace-that-is-not-there
  (testing "NON-VACUITY for every resolution row above. A resolver that
            answered truthy for anything would make each of them pass over
            whatever the roster happened to contain."
    (is (nil? (ns->resource 're-frame.freehand.no-such-namespace-at-all)))
    (is (some? (ns->resource 're-frame.freehand.roster))
        "and it finds one that is")
    (is (some? (ns->resource 're-frame.freehand.form))
        "including a .cljc source")))
