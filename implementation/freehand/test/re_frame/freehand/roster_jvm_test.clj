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
            [re-frame.freehand.roster :as roster]))

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
;; `:residue :none` is a claim a checker can refuse — acceptance 3
;; ---------------------------------------------------------------------------

(defn code-only
  "`text` with every line comment and string literal blanked out, so what
  remains is the CODE a namespace runs.

  Pure and exported, because the rule below is only as good as this is and
  a scanner nobody can exercise is the thing the merged-PR audit of #7105
  caught: the previous residue rule slurped a whole file and accepted any
  of five regex tokens ANYWHERE in it, so a comment naming the assertion,
  or an unrelated zero, satisfied it. Both false positives were reproduced.
  A claim backed by a sentence about the code is not backed by the code.

  Each removed character becomes a space rather than vanishing, so nothing
  is glued to its neighbour and every offset a caller might report survives.
  Four states are enough for Clojure: code, a string (where `\\` escapes
  the next character), a line comment (to end of line), and the one
  character after a `\\` char literal — which is what stops `\\;` and `\\\"`
  opening a comment or a string."
  [^String text]
  (let [n (count text)
        sb (StringBuilder.)]
    (loop [i 0, state :code]
      (if (>= i n)
        (str sb)
        (let [c (.charAt text i)]
          (case state
            :code    (cond
                       (= c \;)  (do (.append sb \space) (recur (inc i) :comment))
                       (= c \")  (do (.append sb \space) (recur (inc i) :string))
                       (= c \\)  (do (.append sb "  ") (recur (+ i 2) :code))
                       :else     (do (.append sb c) (recur (inc i) :code)))
            :comment (do (.append sb (if (= c \newline) \newline \space))
                         (recur (inc i) (if (= c \newline) :code :comment)))
            :string  (cond
                       (= c \\) (do (.append sb "  ") (recur (+ i 2) :string))
                       (= c \") (do (.append sb \space) (recur (inc i) :code))
                       :else    (do (.append sb (if (= c \newline) \newline \space))
                                    (recur (inc i) :string)))))))))

(def ^:private residue-call
  "An INVOCATION of the shared residue assertion — an open paren, an
  optional namespace alias, then `residue-clean!` in head position.

  The shared assertion is the one the rf2-drpa3.182.7 acceptance names, and
  naming exactly one is the point. The predecessor rule listed five
  look-alike spellings (`(zero? …)`, `(empty? …)`, `nothing-survived`, …)
  so that a suite reading its own book inline still counted; the cost was
  that ANY of those anywhere in the file counted, including in prose. One
  call to one assertion is both narrower and stronger, and it is the
  assertion that carries its own non-vacuity — `residue-clean!` reds a
  suite that tore no root down through the facade, so the call cannot be
  satisfied by a mount that never happened."
  #"\(\s*(?:[^\s()\\/]+/)?residue-clean![\s)]")

(defn residue-asserted?
  "Does `source` CALL the shared residue assertion? Pure, so the rows below
  can drive it with the exact shapes the audit reproduced."
  [source]
  (boolean (re-find residue-call (code-only source))))

(deftest a-residue-none-claim-is-backed-by-the-shared-residue-assertion
  (testing "Per rf2-drpa3.182.7 acceptance 3: mounted cleanup is EXACT, and
            `:residue :none` is the record saying so. An unenforced `:none`
            would be the most expensive kind of green — a leaked React root
            contaminates every later suite sharing the process, and this
            corpus has seen one leak produce failures across dozens of
            unrelated suites — so the claim is checked rather than read.

            Every mounted projection of a `:residue :none` record CALLS
            `mount-support/residue-clean!`, after teardown, over the
            facade's own books plus whatever substrate books it names.
            A record whose suites only unmount and remove says
            `:unasserted` — honest, countable, and what two of the three
            initial members said until they took the shared assertion
            (rf2-n9rzw).

            What this row proves and what it does not, stated plainly. The
            executed evidence is the browser lane's: `residue-clean!` runs
            in Chromium and its messages name the law. What is checked HERE
            is that the call SITE exists in every projection the record
            claims — so a `:none` cannot be made by a suite that never
            calls it, which is the gap the merged-PR audit of #7105 found
            when the rule was a five-token text resemblance."
    (doseq [record roster/records
            :when  (= :none (get-in record [:fh/record :evidence :residue]))
            entry  (roster/tier record :mounted)]
      (let [res  (ns->resource (:ns entry))
            text (some-> res slurp)]
        (is (and text (residue-asserted? text))
            (str (:fh/id record) " claims :residue :none, but its mounted proof "
                 (:ns entry) " never calls mount-support/residue-clean! — either "
                 "it reads the books empty after teardown, or the record says "
                 ":unasserted"))))))

(deftest the-residue-rule-refuses-every-shape-that-only-looks-like-a-proof
  (testing "NON-VACUITY, and specifically for the two false positives the
            merged-PR audit of #7105 reproduced against the predecessor
            rule. A gate that has only ever seen input it passes has not
            been tested, and this one guards a claim whose failure mode is
            silent contamination of unrelated suites."
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
              "(def my-residue-clean! 1)"]]]
      (is (not (residue-asserted? source)) note))

    (doseq [[note source]
            [["a call through the facade's alias is the proof"
              "(ms/residue-clean! \"FH-CTRL-018 — after teardown\")"]

             ["so is a referred call"
              "(residue-clean! where [[\"the connection table\" #(count @table)]])"]

             ["and a call carrying books, after a shared teardown, in the real shape"
              (str "(ms/destroy-root! container root)\n"
                   ";; and now read what survived it\n"
                   "(ms/residue-clean! where [[\"the registry\" #(root/live-root-ids)]])")]]]
      (is (residue-asserted? source) note))))

(deftest the-comment-stripper-leaves-the-code-and-only-the-code
  (testing "The rule above is exactly as good as this function, so it is
            driven rather than trusted — including the shapes that make a
            naive stripper wrong: a `;` inside a string, an escaped quote,
            and a char literal spelling either.

            Offsets are preserved (a removed character becomes a space), so
            the assertions below are about WHAT SURVIVES rather than about
            spacing."
    (let [tokens (fn [s] (remove empty? (str/split (code-only s) #"\s+")))]
      (is (= ["(f" "1)"] (tokens "(f 1) ; a comment"))
          "a line comment is gone")
      (is (= ["(f" ")"] (tokens "(f \"a ; b\") ; c"))
          "a semicolon inside a string neither opens a comment nor survives")
      (is (= ["(f" ")"] (tokens "(f \"she said \\\"hi\\\"\")"))
          "an escaped quote does not close the string early")
      (is (= ["(f" ")"] (tokens "(f \\; \\\")"))
          "a char literal semicolon or quote is neither delimiter")
      (is (= ["(a)" "(b)"] (tokens "(a) ;; gone\n(b)"))
          "a line comment ends at its newline, and the code after it survives")
      (is (= ["(re-find" "#" "x)"] (tokens "(re-find #\"\\(zero\\?\" x)"))
          "a regex literal is a string — its dispatch `#` is code, and the
           pattern inside it never is, so a rule looking for a call cannot
           be satisfied by one written in a pattern"))
    (testing "and the newline structure survives, so a reported line number would"
      (is (= 3 (count (str/split-lines (code-only "(a) ; x\n;; y\n(b)"))))))))

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
