(ns re-frame.freehand.roster
  "The Freehand FIXTURE ROSTER — one record per executable law, joined from
  the two files that already describe it, so a claim has ONE identity.

  ## The problem the roster solves

  A Freehand law is already addressed twice. The conformance index
  ([`spec/conformance/freehand/conformance-index.md`](../../../../../spec/conformance/freehand/conformance-index.md))
  rows its id, its one-line statement, the canonical spec paragraph it
  cites, the modes and hosts it binds, and its status. The fixture
  (`spec/conformance/freehand/fixtures/<id>.edn`) carries the VALUES the
  law is proven by. Neither says which source implements it, which mounted
  suite is its browser entry, what evidence a run should leave, or whether
  the prose that describes it is executable.

  Those four are what a reader has to reconstruct by hand today, and a
  reconstruction is not an identity: a fixture can be renamed, a mounted
  suite deleted, or a guide paragraph left describing behaviour nothing
  runs, and no projection notices. The roster closes that by making the
  missing four DECLARED — on the fixture, under `:fh/record` — and then
  JOINING the fixture with its index row into one value.

  ## No new store

  The record lives ON THE FIXTURE rather than in a roster file, on purpose.
  A third file describing the same law would be a third thing to keep in
  step; the fixture is already the file a slice edits when its law changes,
  and it already carries `:fh/id`. So the roster adds a field to a record
  that exists rather than a record to a directory.

  The join is likewise a READ, not a copy. `:modes`, `:hosts`, `:status`
  and `:paragraph` are parsed out of the index row at macro-expansion time
  and are never restated in the fixture.

  What is NOT checked, and deliberately: that a fixture's `:fh/law` matches
  its index row's word for word. The two state the same law at different
  lengths on purpose — the row is the addressed one-line statement, the
  fixture's is the header over the values below it — and every row in the
  ledger is written that way, so gating equality would force a hundred
  prose rewrites to satisfy a law the corpus never held. The relationship
  the join DOES hold is the id: a fixture whose `:fh/id` disagrees with the
  file it is filed under, or with a row the index does not carry, is a
  COMPILE failure in [[read-roster]] rather than a record that quietly
  drops out.

  ## Enrolling — a witness adds no line to any shared file

  Membership is a property of the FIXTURE: a law is rostered exactly when
  its fixture CARRIES the `:fh/record` key — see [[enrolled?]], which is
  key presence and not truthiness, because the difference is a false green.
  Nothing here lists the members, so the roster grows without a shared file
  to edit — which matters because the control witnesses (rf2-drpa3.182.8
  through .12) land in parallel, and a hand-maintained membership vector
  would have made five workers queue behind one line of it.

  So a witness writes, in ITS OWN files only:

    1. a row in the conformance index addressing its id, with a fixture
       path and status `active` (the index owns addressing — see that
       directory's `README.md`);
    2. `:fh/record` on its fixture, naming `:source`, `:evidence`, `:prose`
       and at least one proof tier;
    3. the suites that tier names.

  Then every projection below carries it, and every rule below holds it.
  The ones a new record meets first:

    * a proof namespace RESOLVES to a file, and CITES its own id in its
      text (`roster-jvm-test`), so the vertical is legible from both ends;
    * a `:mounted` tier names a `-dom-cljs-test` namespace — the suffix
      the browser lane selects on — and a `:structural` tier does not;
    * a tier the index row's host axis rules out is a defect, so a record
      cannot advertise a browser proof for a JVM-only law;
    * `:residue :none` is refused unless a `deftest` in the mounted proof
      REACHES the shared residue assertion (see [[residue-statuses]]);
    * `:prose :executable` is refused unless every proof namespace the
      record names defines a test (see [[prose-statuses]]);
    * and every message names the id.

  ## Failures name the id

  Every value this namespace answers — a record, a defect, a projection
  mismatch — carries `:fh/id`. That is acceptance 1 of rf2-drpa3.182.7 made
  mechanical: a red row is attributable to its law without a lookup, in
  every projection, rather than reporting a line number in a file whose
  name a reader then has to decode.

  ## Cross-host

  ClojureScript cannot read a file at runtime, so the roster is read at
  MACRO-EXPANSION time — on the JVM for both hosts — through
  `re-frame.build.spec-resource`, exactly as
  [[re-frame.freehand.conformance/fixture]] reads a fixture and for the
  same cache-invalidation reason. The JVM suite and the ClojureScript suite
  therefore project the same bytes.

  Enrolment-by-discovery makes the read WIDER than the roster: every
  fixture the index names is read, because whether a fixture carries a
  record is a question only its bytes answer. That width is what makes the
  cache edge hold in both directions — adding a row to the index
  invalidates through the index, and adding `:fh/record` to a fixture that
  was already addressed invalidates through that fixture — where a read
  scoped to the members would have registered a dependency only on files
  that were ALREADY members and missed every new one.

  ### The cache edge, MEASURED rather than inherited

  A macro that inlines a data file can DECOUPLE from it. If the compiling
  namespace carries no build-dependency edge back to the file the macro
  read, editing the data does not invalidate the cache, the compiled suite
  goes on asserting the PREVIOUS content, and it reports green — the worst
  failure available to a table-driven gate, because it is indistinguishable
  from success. This corpus has paid for that lesson more than once, which
  is why `spec-resource` exists and why nothing here calls `slurp`.

  `conformance-index.md` is a NEW read through that reader — every earlier
  consumer read `.edn` fixtures — so the edge was measured rather than
  assumed, the only way that counts: mutate the file, clear NOTHING, and
  require red.

      1. warm cache, unmodified          1271 tests, 0 failures   (1 compiled)
      2. FH-CTRL-018's applicability
         cell edited in the index,
         `.shadow-cljs` left alone       1 failure                (2 compiled)
      3. reverted, cache still warm      1271 tests, 0 failures   (2 compiled)

  Step 2 red on `a-browser-only-law-does-not-claim-the-jvm`, reading
  `{:modes #{:compiled :interpreted} :hosts #{:browser :jvm}}` where the
  suite expected `{:modes #{:interpreted} :hosts #{:browser}}` — the
  mutation itself, arriving through the cache. The edge holds in both
  directions.

  Enrolment-by-discovery widened that read to every fixture the index
  names, and the widening was measured the same way rather than assumed —
  it is a stronger claim than the first, because it is about a file the
  roster had NEVER read:

      1. warm cache, unmodified          1311 tests, 0 failures   (3 compiled)
      2. `:fh/record` added to
         `fh-call-001.edn`, a law that
         was not enrolled and whose
         fixture nothing here had read,
         `.shadow-cljs` left alone       3 rows red               (3 compiled)
      3. reverted, cache still warm      1311 tests, 0 failures   (3 compiled)

  Step 2 red on `enrolment-is-a-property-of-the-fixture`, on
  `the-roster-is-sound` and on the resolution rows, every message naming
  FH-CALL-001 — a record ARRIVING through the cache, which is the case a
  witness creates the first time it enrols.

  If a future change makes either step 2 pass, the roster has decoupled
  from the file it read. The fix is to restore the dependency edge; it is
  NOT to clear the cache, because clearing the cache is the broken state's
  own behaviour and hides exactly the defect being looked for.

  Dev/test scope ONLY. This namespace lives under `test/` and nothing in a
  production bundle may reach it."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.set :as set]
                    [clojure.string :as str]
                    [re-frame.build.spec-resource :as spec-resource])
     :cljs (:require [clojure.set :as set]
                     [clojure.string :as str]))
  #?(:cljs (:require-macros [re-frame.freehand.roster :refer [roster]])))

;; ---------------------------------------------------------------------------
;; The record vocabulary — closed, because an open one is a comment
;; ---------------------------------------------------------------------------

(def prose-statuses
  "What the PROSE describing a law claims, per the rf2-drpa3.182.7 fixture
  record. Closed:

    `:executable`        the prose describes behaviour a suite runs, and the
                         code it shows is checked against canonical source.
    `:expected-failure`  the prose describes a refusal — the run proves the
                         diagnostic, not the success.
    `:illustrative`      the prose is a sketch and makes NO executable claim;
                         it is labelled as such where it appears.

  A record must pick one. `:illustrative` is the honest answer for a
  paragraph nothing runs, and saying so is what stops a reader treating a
  sketch as a contract.

  The first two make a claim about a RUN, so they are checked as one:
  `roster-jvm-test` refuses `:executable` or `:expected-failure` from a
  record whose proof namespaces define no test. Membership of this set is
  the shape of the value and nothing more — a vocabulary check on its own
  is exactly what `:residue :none` was before it was held to a call site
  (merged-PR audit #7178), and it is what makes `:illustrative` cost
  something to decline into rather than being the same declaration spelled
  differently."
  #{:executable :expected-failure :illustrative})

(def executable-prose-statuses
  "The [[prose-statuses]] that claim a RUN. `:illustrative` is deliberately
  absent: it is the record saying its prose makes no executable claim, and
  a status that exempted nothing would not be worth declaring."
  #{:executable :expected-failure})

(def residue-statuses
  "What a record claims about what a mounted run LEAVES BEHIND — rf2-drpa3.182.7
  acceptance 3. Closed:

    `:none`        the mounted suites assert the absence: after teardown the
                   substrate's own books read empty, as an exact zero rather
                   than a threshold. In every mounted projection of the
                   record, a `deftest` must REACH
                   `re-frame.freehand.mount-support/residue-clean!` — the one
                   shared assertion, checked by reading the namespace's forms
                   and following its calls, rather than by finding text that
                   resembles one (`roster-jvm-test`). Reachability rather
                   than presence, because a helper nothing invokes and a form
                   behind a `#_` are both text the compiled program never
                   contains.
    `:unasserted`  the suites tear their roots down but assert nothing about
                   residue. An honest gap, and countable.

  There is no third value, and in particular no way to say 'probably
  clean'. The distinction matters more than it looks: a leaked React root
  contaminates every later suite sharing the process, and this corpus has
  seen one leak produce failures across dozens of unrelated suites. A
  record that claimed `:none` because its teardown *looked* thorough would
  be the most expensive kind of green — so `:none` is a claim a checker
  can refuse, and `:unasserted` is what a suite says until it earns the
  other one."
  #{:none :unasserted})

(def tiers
  "The tiers a record may name a proof site in. Each is a real lane, not a
  label: `:structural` runs headlessly on both hosts through
  [[re-frame.freehand.test/render]], `:mounted` runs in Chromium against a
  real `react-dom/client` commit, `:ssr` runs the JVM tree to HTML."
  #{:structural :mounted :ssr})

(def record-keys
  "The closed key set of `:fh/record`. A key outside it is a defect rather
  than ignored data — an unknown key is almost always a typo for a real
  one, and silently dropping it is how a record stops describing its law.

  `:open` is the one that is not about what a law proves. It records a
  boundary of the law that is NOT settled, keyed by the bead that owns the
  question — and it earns a slot because the alternative is worse. A
  rostered expectation is a digest-pinned expectation: the moment a
  projection asserts today's behaviour at an unsettled boundary, whoever
  settles it has to fight the roster to land the fix. Naming the question
  in the record makes the gap VISIBLE to a reader and to a checker, and
  leaves the answer to the bead that owns it."
  #{:source :structural :mounted :ssr :evidence :open :prose})

(def ^:private required-record-keys
  "Every record names its canonical source, what a run LEAVES BEHIND, and
  its prose status. The tier entries are optional because a law that binds
  only on one tier should say so by omission rather than by an empty
  placeholder — but [[defects]] requires at least one tier, so a record
  cannot claim a law is executable and name nowhere it executes.

  `:evidence` is required rather than merely validated-when-present, and
  the difference is the whole point of the key. A record that omitted it
  would be making no claim about residue at all, while the roster's own
  narration says every record declares one — so a future member could have
  slipped past the residue rule by saying nothing, with the gate green
  (merged-PR audit #7098). Saying `:unasserted` is the honest way to
  decline; saying nothing is not one."
  #{:source :evidence :prose})

;; ---------------------------------------------------------------------------
;; Enrolment — membership is the KEY, not what is under it
;; ---------------------------------------------------------------------------

(defn enrolled?
  "Is `fixture` a member of the roster? Membership is KEY PRESENCE.

  The distinction is the whole rule. Enrolment is discovered rather than
  listed, so this predicate is the only thing standing between a fixture
  and [[defects]] — and [[defects]]' first job is to reject a `:fh/record`
  that is not a map. Spelled as truthiness (`(when (:fh/record fix) …)`,
  which is how it shipped), a fixture declaring `:fh/record nil` — a
  half-finished witness, a key whose value a merge dropped — VANISHED from
  the roster instead of reaching the validator that exists to report it.
  The gate stayed green by losing the record, which is the failure mode a
  discovery-driven roster is uniquely exposed to (merged-PR audit #7178).

  So `contains?`: a fixture that mentions `:fh/record` at all has opted in,
  and whatever it put there is [[defects]]' to judge."
  [fixture]
  (and (map? fixture) (contains? fixture :fh/record)))

;; ---------------------------------------------------------------------------
;; Applicability — the index's two-axis cell, as data
;; ---------------------------------------------------------------------------

(def ^:private mode-tokens
  "The mode axis: exactly one token per the index's applicability grammar."
  {"common"      #{:interpreted :compiled}
   "interpreted" #{:interpreted}
   "compiled"    #{:compiled}})

(def ^:private host-tokens
  "The unqualified host axis. `host:<name>` is admitted separately."
  {"jvm" :jvm "browser" :browser "ssr" :ssr})

(defn parse-applicability
  "Parse an index applicability cell — `common jvm browser`, `compiled
  browser`, `common host:vega` — into `{:modes #{…} :hosts #{…}}`.

  A qualified host reads as `[:host \"vega\"]` so the name survives; the
  roster is not the place to decide what a qualified boundary means. An
  unparseable cell answers `nil` rather than a partial map, so a caller
  reports the cell verbatim instead of asserting against a guess."
  [cell]
  (let [tokens (remove str/blank? (str/split (str/trim (or cell "")) #"\s+"))
        modes  (keep mode-tokens tokens)
        hosts  (keep (fn [t]
                       (or (host-tokens t)
                           (when (str/starts-with? t "host:")
                             [:host (subs t 5)])))
                     tokens)]
    (when (and (= 1 (count modes))
               (seq hosts)
               (= (count tokens) (+ (count modes) (count hosts))))
      {:modes (first modes)
       :hosts (set hosts)})))

;; ---------------------------------------------------------------------------
;; Validation — pure, cross-host, and every defect names the id
;; ---------------------------------------------------------------------------

(defn- defect
  [id field detail]
  {:fh/id id :field field :detail detail})

(defn- entry-defects
  [id tier entry]
  (let [{:keys [ns law]} entry]
    (cond-> []
      (not (map? entry))
      (conj (defect id tier (str "a tier entry is a map of :ns and :law; got "
                                 (pr-str entry))))

      (and (map? entry) (not (symbol? ns)))
      (conj (defect id tier (str "names its proof namespace as a SYMBOL, so a "
                                 "checker can resolve it; got " (pr-str ns))))

      (and (map? entry) (not (and (string? law) (seq law))))
      (conj (defect id tier "states the law this tier proves, in one line"))

      (and (map? entry) (seq (set/difference (set (keys entry)) #{:ns :law})))
      (conj (defect id tier (str "carries a key outside #{:ns :law}: "
                                 (pr-str (sort (set/difference (set (keys entry))
                                                               #{:ns :law})))))))))

(defn- tier-defects
  "A tier's value is always a NON-EMPTY VECTOR of entries, never a bare
  entry. One projection is the common case and two is not exotic — the
  deferred foreign-handle surrogate is a second mounted projection of the
  same total-release law — so the shape that admits both without a special
  case is the vector, uniformly. A bare map would make the reader ask which
  shape they were looking at every time."
  [id tier entries]
  (if-not (and (vector? entries) (seq entries))
    [(defect id tier (str "a tier is a NON-EMPTY VECTOR of {:ns … :law …} "
                          "entries — one per projection that proves this law "
                          "at this tier; got " (pr-str entries)))]
    (into (vec (mapcat #(entry-defects id tier %) entries))
          (let [nss (map :ns entries)]
            (when (not= (count nss) (count (distinct nss)))
              [(defect id tier (str "names the same namespace twice: "
                                    (pr-str (vec nss))))])))))

(defn defects
  "Every defect in `record`, as a vector — empty when the record is sound.

  Each entry is `{:fh/id … :field … :detail …}`. The id is on EVERY entry,
  including the ones about a missing id, so a report can be grouped by law
  without a lookup and a failure message reads as a sentence about a law
  rather than about a map.

  Total over garbage: a record that is not a map, or carries no id, or
  whose `:fh/record` is not a map, answers a defect rather than throwing. A
  validator that throws on the input it exists to reject reports the first
  defect and hides the rest — and the inputs here are exactly what a
  hand-edited or half-merged fixture produces. The `:fh/record` half of
  that was latent until [[enrolled?]] started letting such a fixture
  through: `(keys false)` throws, and while enrolment tested truthiness no
  record carrying `false` could ever arrive to find out."
  [record]
  (let [id (:fh/id record)]
    (cond
      (not (map? record))
      [(defect nil :record (str "a roster record is a map; got " (pr-str record)))]

      (not (and (string? id) (seq id)))
      [(defect nil :fh/id (str "a roster record carries its :fh/id; got " (pr-str id)))]

      :else
      (let [{:keys [source prose evidence open] :as rec} (:fh/record record)
            rec?    (map? rec)
            present (if rec? (set (keys rec)) #{})
            named   (if rec? (select-keys rec tiers) {})]
        (cond-> []
          (not (map? rec))
          (conj (defect id :fh/record
                        (str "carries a :fh/record map naming its canonical "
                             "source, its proof tiers, its evidence expectation "
                             "and its prose status; got " (pr-str rec))))

          (and (map? rec) (seq (set/difference present record-keys)))
          (conj (defect id :fh/record
                        (str "carries a key outside the closed record set "
                             (pr-str (sort record-keys)) ": "
                             (pr-str (sort (set/difference present record-keys))))))

          (and (map? rec) (seq (set/difference required-record-keys present)))
          (conj (defect id :fh/record
                        (str "omits a required key: "
                             (pr-str (sort (set/difference required-record-keys present))))))

          (and (map? rec) (contains? present :source)
               (not (and (vector? source)
                         (seq source)
                         (every? symbol? source))))
          (conj (defect id :source
                        (str "names its canonical source as a non-empty vector of "
                             "namespace SYMBOLS — the implementation the law is a "
                             "law ABOUT, so a reader reaches it without a search; got "
                             (pr-str source))))

          (and (map? rec) (contains? present :prose)
               (not (contains? prose-statuses prose)))
          (conj (defect id :prose
                        (str "declares one of " (pr-str (sort prose-statuses))
                             "; got " (pr-str prose))))

          (and (map? rec) (contains? present :evidence)
               (not (and (map? evidence) (seq evidence))))
          (conj (defect id :evidence
                        (str "states its evidence expectation as a non-empty map; got "
                             (pr-str evidence))))

          ;; Guarded on a NON-EMPTY evidence map: an empty one is already
          ;; reported just above, and a shape error that produced two
          ;; defects would say the same thing twice.
          (and (map? rec) (map? evidence) (seq evidence)
               (not (contains? residue-statuses (:residue evidence))))
          (conj (defect id :evidence
                        (str "declares what a mounted run leaves behind as one of "
                             (pr-str (sort residue-statuses)) "; got "
                             (pr-str (:residue evidence)))))

          ;; An `:open` entry is keyed by the bead that owns the question,
          ;; because "unsettled" without an owner is a shrug.
          (and (map? rec) (contains? present :open)
               (not (and (map? open)
                         (seq open)
                         (every? keyword? (keys open))
                         (every? (every-pred string? seq) (vals open)))))
          (conj (defect id :open
                        (str "records each unsettled boundary as <owning-bead-keyword> "
                             "-> one line stating the question; got " (pr-str open))))

          (and (map? rec) (empty? named))
          (conj (defect id :fh/record
                        (str "names no proof tier. A record must name at least one "
                             "of " (pr-str (sort tiers)) " — a law that executes "
                             "nowhere is prose.")))

          :always
          (into (mapcat (fn [[tier entries]] (tier-defects id tier entries)) named)))))))

(def ^:private tier-hosts
  "The hosts a proof tier can possibly run on. A tier claim is checked
  against the index row's own host axis, which is the one cross-file law
  the join can state without inventing one: a record cannot advertise a
  browser proof for a law the ledger says binds only on the JVM.

  `:structural` admits both because the structural walk is the one tier
  that genuinely runs on either host from a single `.cljc`."
  {:structural #{:jvm :browser}
   :mounted    #{:browser}
   :ssr        #{:ssr}})

(defn tier-hosts-of
  "The index row's host axis, reduced to the plain hosts a TIER runs on.

  A qualified boundary — `host:vega`, `host:google-maps` — reads as
  `[:host \"…\"]` so its name survives the parse, and it names a foreign
  door that exists only in a browser. So it counts as `:browser` here and
  nowhere else: a law narrowed to a named third-party host is still
  browser-bound, and a record proving it in a mounted suite is making the
  only claim available to it. Without this the ownership-routing witnesses
  — whose rows are exactly the `host:<name>` ones — could not declare the
  mounted tier they live on."
  [hosts]
  (into #{} (map (fn [h] (if (vector? h) :browser h))) hosts))

(defn- join-defects
  "The defects that are properties of the JOIN — a record read against the
  index row that addresses it.

  One law is stated here: a record does not claim a tier the ledger's host
  axis rules out. The other relationship between the two files — that they
  address the same id — is held earlier and harder, as a COMPILE failure in
  [[read-roster]], so by the time a record reaches here the id is not in
  question. What is deliberately never compared is the two prose statements;
  see this namespace's docstring for why byte equality would be the wrong
  law."
  [{id :fh/id :keys [hosts fh/record]}]
  (when (and (map? record) (set? hosts))
    (let [reachable (tier-hosts-of hosts)]
      (keep (fn [[t possible]]
              (when (and (contains? record t)
                         (empty? (set/intersection reachable possible)))
                (defect id t
                        (str "claims a " (name t) " proof, but the index row binds "
                             "this law to " (pr-str (sort-by str hosts))
                             " — that tier can only run on "
                             (pr-str (sort-by str possible)) "."))))
            tier-hosts))))

(defn roster-defects
  "Every defect across `records`, plus the two that are properties of the
  ROSTER rather than of any one record: a duplicated `:fh/id`, and a record
  claiming a proof tier its index row's host axis rules out.

  The failure value is a flat vector of defect maps, each naming its law."
  [records]
  (let [dupes (->> records
                   (map :fh/id)
                   frequencies
                   (keep (fn [[id n]] (when (and id (> n 1)) [id n]))))]
    (into (into (vec (mapcat defects records))
                (map (fn [[id n]]
                       (defect id :fh/id (str "appears " n " times in the roster; "
                                              "an id addresses exactly one law")))
                     dupes))
          (mapcat join-defects records))))

;; ---------------------------------------------------------------------------
;; Reading the two files — JVM only, at macro-expansion time
;; ---------------------------------------------------------------------------

#?(:clj
   (def ^:private index-path
     "conformance/freehand/conformance-index.md"))

#?(:clj
   (def ^:private fixture-cell-prefix
     "The Fixture cell is written repo-relative in backticks; `spec-resource`
     resolves against `spec/` itself. Trimming the root here is what keeps
     the index the single place a fixture path is spelled."
     "spec/conformance/freehand/fixtures/"))

#?(:clj
   (defn- fixture-path
     "The `spec-resource` path the row's Fixture cell names, or nil where it
     names none — a `planned` or `retired` row writes `—` there."
     [cell]
     (let [c (str/replace (str/trim (or cell "")) "`" "")]
       (when (str/starts-with? c fixture-cell-prefix)
         (subs c (count "spec/"))))))

#?(:clj
   (defn- index-rows
     "Every data row of the conformance index, keyed by id.

     The index is a Markdown table and this is a table read, not a Markdown
     parse: a data row is a line whose FIRST cell is a backticked `FH-…`
     id. Header rows, separators, the row-shape template in the preamble
     and every paragraph between sections all fail that test, so the reader
     needs no section state and cannot be confused by one."
     [text]
     (into {}
           (keep (fn [line]
                   (when (str/starts-with? (str/triml line) "|")
                     (let [cells (mapv str/trim (str/split line #"\|"))
                           ;; A leading `|` makes cells[0] empty.
                           cells (if (and (seq cells) (str/blank? (first cells)))
                                   (subvec cells 1)
                                   cells)]
                       (when (>= (count cells) 6)
                         (let [id (str/replace (nth cells 0) "`" "")]
                           (when (re-matches #"FH-[A-Z]+-\d{3}" id)
                             [id {:fh/id         id
                                  :index/law     (nth cells 1)
                                  :paragraph     (nth cells 2)
                                  :index/cell    (nth cells 3)
                                  :index/fixture (nth cells 4)
                                  :status        (keyword (nth cells 5))}])))))))
           (str/split-lines text))))

#?(:clj
   (defn- join-row
     "One row joined with the fixture it names, or nil when that fixture
     declares no `:fh/record` key at all — which is how a law declines
     enrolment, and the only way it can (see [[enrolled?]]).

     Throws when the fixture and the row disagree about the id, or when the
     row's applicability cell will not parse. Those are COMPILE failures on
     purpose: a roster that quietly shrank to the records it could resolve
     would let a suite pass over a law it stopped loading, which is the
     worst failure a table-driven gate can have."
     [env {:keys [fh/id index/cell index/fixture] :as row}]
     (when-let [path (fixture-path fixture)]
       (let [fix (edn/read-string (spec-resource/slurp-resource env path))]
         (when (enrolled? fix)
           (when-not (= id (:fh/id fix))
             (throw (ex-info (str "Freehand roster: " path " is named by index row " id
                                  " but declares :fh/id " (pr-str (:fh/id fix))
                                  " — the file and the id disagree.")
                             {:fh/id id :declared (:fh/id fix)})))
           (let [app (or (parse-applicability cell)
                         (throw (ex-info (str "Freehand roster: " id " has an "
                                              "unparseable applicability cell "
                                              (pr-str cell) ".")
                                         {:fh/id id})))]
             (merge (dissoc row :index/cell :index/fixture)
                    app
                    (select-keys fix [:fh/id :fh/law :fh/record]))))))))

#?(:clj
   (defn ^:no-doc read-roster
     "Every enrolled record, joined with its index row, in the
     macro-expansion environment `env` — id-sorted, so the value is stable
     across filesystems and a diff of it reads.

     Enrolment is DISCOVERED rather than listed: every fixture the index
     names is read, and the ones carrying `:fh/record` are the roster. A
     witness therefore enrols by editing its own two files and no shared
     one — see this namespace's docstring."
     [env]
     (let [rows (index-rows (spec-resource/slurp-resource env index-path))]
       (into [] (comp (map val) (keep #(join-row env %)))
             (sort-by key rows)))))

#?(:clj
   (defmacro roster
     "Inline every enrolled roster record as a quoted literal, read at
     macro-expansion time.

     Identical data on the JVM and in ClojureScript, and — in
     ClojureScript — a build dependency of the calling namespace on the
     index AND on every fixture the index names, so adding a row, or adding
     a record to a fixture already addressed, recompiles this call site."
     []
     (list 'quote (read-roster &env))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def records
  "Every enrolled record — the value every projection of the roster reads,
  on both hosts, id-sorted."
  (roster))

(def ids
  "The ids of [[records]], in the same order. Strings, as they appear in
  the index and in every failure message."
  (mapv :fh/id records))

(def initial-spine-ids
  "The three vertical fixtures rf2-drpa3.182.7 §INITIAL SPINE integrates —
  the declared host, the serious form, and the deferred foreign-handle
  surrogate. Named because acceptance 2 is about THESE three running
  through one roster; the roster itself is [[ids]], which grows past them.

  The surrogate is rostered as `FH-BEHAVIOR-005` rather than under an id of
  its own, and that is the point of it: the recipe adds no verb, no
  scheduling policy and no contract, so it has no law to address. What it
  is, is the MOUNTED PROJECTION of the total-cleanup law under a
  Promise-returning third-party initializer — the shape where a release can
  arrive after the owner is gone. Minting a second id for the same law
  would have split one identity in exactly the way this roster exists to
  prevent."
  ["FH-BEHAVIOR-005" "FH-CTRL-018" "FH-REACT-007"])

(defn by-id
  "The rostered record for `id` (a string or keyword), or nil."
  ([id] (by-id records id))
  ([records id]
   (let [id (name id)]
     (first (filter #(= id (:fh/id %)) records)))))

(defn tier
  "The vector of `{:ns … :law …}` entries `record` declares for `tier` —
  empty when the record names no proof at that tier.

  Empty rather than nil, so a caller folds over the result without asking
  whether the tier was declared. `(seq (tier record :mounted))` is the
  question 'is this law proven in a browser at all'."
  [record tier]
  (get-in record [:fh/record tier] []))

(defn proofs
  "Every proof `record` declares, as `{:tier … :ns … :law …}` maps in tier
  order. The projection a checker folds over when the question is about
  proofs rather than about one tier."
  [record]
  (for [t    [:structural :mounted :ssr]
        e    (tier record t)]
    (assoc e :tier t)))
