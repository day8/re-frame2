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
  and are never restated in the fixture — and [[defects]] fails a fixture
  whose `:fh/law` disagrees with the index's, so the two files cannot drift
  apart quietly.

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

  If a future change makes step 2 pass, the roster has decoupled from the
  index. The fix is to restore the dependency edge; it is NOT to clear the
  cache, because clearing the cache is the broken state's own behaviour and
  hides exactly the defect being looked for.

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
  sketch as a contract."
  #{:executable :expected-failure :illustrative})

(def residue-statuses
  "What a record claims about what a mounted run LEAVES BEHIND — rf2-drpa3.182.7
  acceptance 3. Closed:

    `:none`        the mounted suites assert the absence: after teardown the
                   substrate's own books read empty, as an exact zero rather
                   than a threshold.
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
  "Every record names its canonical source and its prose status. The tier
  entries are optional because a law that binds only on one tier should say
  so by omission rather than by an empty placeholder — but [[defects]]
  requires at least one tier, so a record cannot claim a law is executable
  and name nowhere it executes."
  #{:source :prose})

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

  Total over garbage: a record that is not a map, or carries no id, answers
  a defect rather than throwing. A validator that throws on the input it
  exists to reject reports the first defect and hides the rest."
  [record]
  (let [id (:fh/id record)]
    (cond
      (not (map? record))
      [(defect nil :record (str "a roster record is a map; got " (pr-str record)))]

      (not (and (string? id) (seq id)))
      [(defect nil :fh/id (str "a roster record carries its :fh/id; got " (pr-str id)))]

      :else
      (let [{:keys [source prose evidence open] :as rec} (:fh/record record)
            present (set (keys rec))
            named   (select-keys rec tiers)]
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

(defn- join-defects
  "The defects that are properties of the JOIN — a record read against the
  index row that addresses it.

  Only one law is stated here, and deliberately only one. It is tempting to
  also require that a fixture's `:fh/law` match its index row's word for
  word, and that would be wrong: the two say the same law at different
  lengths on purpose — the index row is the addressed one-line statement, a
  fixture's `:fh/law` is the header over the values below it — and every
  row in the ledger is written that way. Gating equality would force a
  hundred prose rewrites to satisfy a law the corpus never held.

  What IS checkable is that a record does not claim a tier the ledger's
  host axis rules out."
  [{id :fh/id :keys [hosts fh/record]}]
  (when (and (map? record) (set? hosts))
    (keep (fn [[t possible]]
            (when (and (contains? record t)
                       (empty? (set/intersection hosts possible)))
              (defect id t
                      (str "claims a " (name t) " proof, but the index row binds "
                           "this law to " (pr-str (sort-by str hosts))
                           " — that tier can only run on "
                           (pr-str (sort-by str possible)) "."))))
          tier-hosts)))

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
   (defn- fixture-path
     [id]
     (str "conformance/freehand/fixtures/" (str/lower-case id) ".edn")))

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
                             [id {:fh/id      id
                                  :index/law  (nth cells 1)
                                  :paragraph  (nth cells 2)
                                  :index/cell (nth cells 3)
                                  :status     (keyword (nth cells 5))}])))))))
           (str/split-lines text))))

#?(:clj
   (defn ^:no-doc read-roster
     "Join the fixtures for `ids` with their index rows, in the
     macro-expansion environment `env`.

     Throws when an id is absent from the index, when its fixture is
     missing or misfiled, or when its applicability cell will not parse.
     Those are COMPILE failures on purpose: a roster that quietly shrank to
     the ids it could resolve would let a suite pass over a law it stopped
     loading, which is the worst failure a table-driven gate can have."
     [env ids]
     (let [rows (index-rows (spec-resource/slurp-resource env index-path))]
       (mapv (fn [id]
               (let [id  (name id)
                     row (or (get rows id)
                             (throw (ex-info (str "Freehand roster: " id " is not a row of "
                                                  index-path " — an id must be addressed "
                                                  "before it can be rostered.")
                                             {:fh/id id})))
                     fix (edn/read-string
                           (spec-resource/slurp-resource env (fixture-path id)))
                     app (or (parse-applicability (:index/cell row))
                             (throw (ex-info (str "Freehand roster: " id " has an "
                                                  "unparseable applicability cell "
                                                  (pr-str (:index/cell row)) ".")
                                             {:fh/id id})))]
                 (when-not (= id (:fh/id fix))
                   (throw (ex-info (str "Freehand roster: fixture for " id " declares :fh/id "
                                        (pr-str (:fh/id fix)) " — the file and the id disagree.")
                                   {:fh/id id :declared (:fh/id fix)})))
                 (merge (dissoc row :index/cell)
                        app
                        (select-keys fix [:fh/id :fh/law :fh/record]))))
             ids))))

#?(:clj
   (defmacro roster
     "Inline the joined roster records for `ids` as a quoted literal, read
     at macro-expansion time.

         (def spine (roster [:FH-REACT-007 :FH-CTRL-018 :FH-BEHAVIOR-005]))

     Identical data on the JVM and in ClojureScript, and — in
     ClojureScript — a build dependency of the calling namespace, so an
     edit to the index or to any rostered fixture recompiles this call
     site."
     [ids]
     (list 'quote (read-roster &env ids))))

;; ---------------------------------------------------------------------------
;; The spine — the members rf2-drpa3.182.7 integrates
;; ---------------------------------------------------------------------------

(def spine-ids
  "The three vertical fixtures the initial spine integrates, per
  rf2-drpa3.182.7 §INITIAL SPINE — the declared host, the serious form, and
  the deferred foreign-handle surrogate.

  The surrogate is rostered as `FH-BEHAVIOR-005` rather than under an id of
  its own, and that is the point of it: the recipe adds no verb, no
  scheduling policy and no contract, so it has no law to address. What it
  is, is the MOUNTED PROJECTION of the total-cleanup law under a
  Promise-returning third-party initializer — the shape where a release can
  arrive after the owner is gone. Minting a second id for the same law
  would have split one identity in exactly the way this roster exists to
  prevent.

  Named as data so a projection can assert enrolment. The roster grows for
  Maps, Framer Motion, SpreadJS, Radix and the control witnesses as those
  slices land; this vector is the seam they extend."
  [:FH-REACT-007 :FH-CTRL-018 :FH-BEHAVIOR-005])

(def spine
  "The joined roster records for [[spine-ids]] — the value every projection
  of the spine reads, on both hosts."
  (roster [:FH-REACT-007 :FH-CTRL-018 :FH-BEHAVIOR-005]))

(defn by-id
  "The rostered record for `id` (a string or keyword), or nil."
  ([id] (by-id spine id))
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
