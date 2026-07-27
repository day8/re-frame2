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
    (doseq [record roster/spine
            [field ns-sym] (declared-namespaces record)]
      (is (some? (ns->resource ns-sym))
          (str (:fh/id record) " — " field " names " ns-sym
               ", which resolves to no file on the classpath")))))

(deftest the-roster-names-at-least-one-namespace-per-record
  (testing "NON-VACUITY for the row above. A record whose declarations were
            all empty would pass every resolution check by having nothing to
            resolve, which is the failure mode a table-driven gate is most
            prone to."
    (doseq [record roster/spine]
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
    (doseq [record            roster/spine
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
    (doseq [record roster/spine
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
    (doseq [record roster/spine
            entry  (roster/tier record :structural)]
      (is (not (str/ends-with? (name (:ns entry)) "-dom-cljs-test"))
          (str (:fh/id record) " — the structural tier names a browser suite, "
               (:ns entry))))))

;; ---------------------------------------------------------------------------
;; `:residue :none` is a claim a checker can refuse — acceptance 3
;; ---------------------------------------------------------------------------

(def ^:private emptiness-assertion
  "The shapes a residue assertion takes in this corpus. A leak assertion is
  always an ABSENCE read after teardown — an exact zero or an empty
  collection off the substrate's own book — so these are what one looks
  like, not an arbitrary keyword list.

  `residue-clean!` is the SHARED one (rf2-n9rzw): the mounted-lifecycle
  facade's assertion, read after teardown over every root the test created
  plus whatever substrate books the caller names. It leads the list because
  it is what a mounted suite should reach for; the inline spellings behind
  it are the local reads that predate it and remain perfectly good evidence.

  `nothing-survived` is included because the async surrogate factors its
  absence check into a named helper and calls it from every case; a rule
  that only recognised the inline spellings would refuse the most thorough
  suite in the set."
  [#"residue-clean!" #"\(zero\?" #"\(empty\?" #"nothing-survived"
   #"\(= \[\] " #"\(= 0 \(count"])

(deftest a-residue-none-claim-is-backed-by-an-emptiness-assertion
  (testing "Per rf2-drpa3.182.7 acceptance 3: mounted cleanup is EXACT, and
            `:residue :none` is the record saying so. An unenforced `:none`
            would be the most expensive kind of green — a leaked React root
            contaminates every later suite sharing the process, and this
            corpus has seen one leak produce failures across dozens of
            unrelated suites — so the claim is checked rather than read.

            Every mounted projection of a `:residue :none` record must read
            an absence after teardown. A record whose suites only unmount
            and remove, without asserting what survived, says
            `:unasserted` — which is honest, countable, and what two of the
            three spine members currently say."
    (doseq [record roster/spine
            :when  (= :none (get-in record [:fh/record :evidence :residue]))
            entry  (roster/tier record :mounted)]
      (let [res  (ns->resource (:ns entry))
            text (some-> res slurp)]
        (is (and text (some #(re-find % text) emptiness-assertion))
            (str (:fh/id record) " claims :residue :none, but its mounted proof "
                 (:ns entry) " makes no emptiness assertion — either it asserts "
                 "what survived, or the record says :unasserted"))))))

(deftest the-residue-rule-would-refuse-a-suite-that-asserts-nothing
  (testing "NON-VACUITY for the row above, which is otherwise a rule that
            has only ever seen input it passes. A teardown that unmounts and
            removes and reads nothing is exactly the suite the rule exists
            to refuse, so it is run against that text directly."
    (let [teardown-only "(defn- teardown! [c r] (.unmount r) (.remove c) nil)"
          with-absence  (str teardown-only "\n(is (zero? (connection-count)))")]
      (is (not-any? #(re-find % teardown-only) emptiness-assertion)
          "an unmount-and-remove teardown is not a residue assertion")
      (is (some #(re-find % with-absence) emptiness-assertion)
          "and reading a book empty afterwards is"))))

(deftest the-residue-rule-refuses-the-shared-teardown-without-the-shared-assertion
  (testing "The mutation the shared facade makes possible, and the one this
            rule now has to survive (rf2-n9rzw). Consuming
            `mount-support`'s lifecycle is not the same as reading its
            books: a suite can call `destroy-root!` — a real, correct
            teardown — and never call `residue-clean!`, which is precisely
            the state FH-CTRL-018 and FH-REACT-007 were in.

            A rule that recognised the facade by NAME rather than by the
            assertion would go green on the first half and stop refusing
            anything, so it is run against both halves directly. A shared
            assertion that cannot fail is the defect this whole seam exists
            to remove."
    (let [shared-teardown "(ms/destroy-root! container root)"
          shared-assert   (str shared-teardown
                               "\n(ms/residue-clean! \"FH-CTRL-018 — after teardown\")")]
      (is (not-any? #(re-find % shared-teardown) emptiness-assertion)
          "tearing down through the shared lifecycle asserts nothing on its own")
      (is (some #(re-find % shared-assert) emptiness-assertion)
          "and reading the shared residue assertion afterwards is what earns the claim"))))

(deftest the-spine-records-what-it-has-not-yet-earned
  (testing "Per rf2-drpa3.182.7 acceptance 3: the roster's value here is
            that the gap is COUNTABLE rather than hidden. FH-BEHAVIOR-005
            earns `:residue :none` — both its projections read the
            substrate's books empty after teardown, as exact zeros. The
            other two tear down and assert nothing, and say so.

            This row is a statement of the CURRENT state, and it is meant to
            be edited when the mounted-lifecycle facade lands and the other
            two earn `:none`. It is here so that happens deliberately."
    (is (= {:FH-REACT-007    :unasserted
            :FH-CTRL-018     :unasserted
            :FH-BEHAVIOR-005 :none}
           (into {} (map (fn [id]
                           [id (get-in (roster/by-id id)
                                       [:fh/record :evidence :residue])])
                         roster/spine-ids))))))

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
