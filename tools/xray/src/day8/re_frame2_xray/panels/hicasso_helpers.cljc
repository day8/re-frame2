(ns day8.re-frame2-xray.panels.hicasso-helpers
  "The pure algebra behind the Hicasso tab — data in, data out, and no
  runtime read anywhere (rf2-hic-023).

  It is a `.cljc` for the ordinary Xray reason: the projection from an
  evidence envelope to what a reader sees is where the honesty of this tab
  actually lives, so it runs under the JVM unit-test target rather than
  only inside a compiled panel.

  ## The one thing this namespace exists to get right

  Hicasso's evidence door refuses to encode unknown as an empty
  collection. That guarantee is worth nothing if the PANEL then renders
  `:unknown` and `[]` the same way — a reader who cannot tell them apart
  is in exactly the position the schema was built to spare them. So every
  absence here is turned into a [[loss-chip]] carrying its own `:kind`,
  its own testid suffix and its own sentence, and
  [[the five kinds are pairwise distinct]] is a property the suite
  asserts rather than a convention the renderers follow.

  ## Three empties, three different sentences

  A tab showing no rows can mean three unrelated things, and collapsing
  them is the same failure one level up:

  | [[presence]] | What it means |
  |---|---|
  | `:absent`   | this host is not running Hicasso, or this is a production build — the door answered `nil` |
  | `:mismatch` | Hicasso answered, stamping a schema THIS build was not taught to parse |
  | `:idle`     | Hicasso answered with an empty roster — and what THAT means is per view, see [[empty-copy]] |
  | `:live`     | there are rows |

  The third row used to carry one sentence for all four views, and that
  sentence was written for the mounted census: *nothing is mounted, a
  clean bill of health*. Under Intents the same words claimed a capped
  window proved nothing had been dispatched. Four scopes need four
  sentences ([[empty-copy]]) — an empty roster is a different fact in
  each, with a different remedy."
  (:require [clojure.string :as string]))

;; ---------------------------------------------------------------------------
;; The schema pin — consumer-owned, deliberately not the producer's var
;; ---------------------------------------------------------------------------

(def consumed-evidence-schema
  "The Hicasso evidence-schema version THIS Xray build was written to
  parse.

  A consumer-owned LITERAL, and deliberately not
  `re-frame.hicasso.evidence/schema`. Deriving support from the producer's
  own var makes ANY producer bump silently \"supported\", so an evolved
  shape would be mis-parsed as exact and the version boundary would be
  nominal. Pinning a literal makes a producer that evolves its shape a
  DETECTABLE mismatch until this build is taught the new shape and this
  pin is bumped in lockstep.

  **v2 is that lockstep bump, and it is the point of the mechanism.** The
  #7789 audit repair evolved the wire shape — projected key elements,
  plural intent frames, `:latest-reads` as maps, new host fields — while
  leaving the stamp at v1, so for one increment this pin was accepting a
  shape it had NOT been taught and parsing it as exact (audit #7802). A
  version that lies is worse than no version: it converts a loud failure
  into a silent misread. There is no v1 acceptance path here — pre-alpha
  needs no compatibility adapter, and a shim would restore the very
  mis-parse the pin exists to refuse."
  :re-frame.hicasso.evidence/v2)

(def consumed-producer
  "The producing substrate this tab renders.

  Pinned beside the schema because the schema is adapter-neutral: a
  future substrate could stamp the same schema version with its own
  producer id, and this tab's rows are written against Hicasso's
  vocabulary of boundaries and read edges."
  :re-frame/hicasso)

(defn supported?
  "True when an envelope carries EXACTLY the schema and producer this
  build understands. Any other version — older or newer — degrades rather
  than mis-parses."
  [envelope]
  (and (map? envelope)
       (= consumed-evidence-schema (:schema envelope))
       (= consumed-producer (:producer envelope))))

;; ---------------------------------------------------------------------------
;; The five states, each with its own words
;; ---------------------------------------------------------------------------

(def unknown
  "The producer's explicit not-held marker.

  A literal here for the same reason [[consumed-evidence-schema]] is: this
  namespace parses a wire shape and must not derive its understanding of
  that shape from the producer it is meant to be able to disagree with."
  :unknown)

(defn unknown?
  "True when `x` is the producer's explicit [[unknown]] — the value a
  projection states where it does not hold a fact.

  Exposed so a renderer marks the absence rather than printing the
  keyword at a developer, and so the comparison lives beside the schema
  pin instead of being spelled out at each render site."
  [x]
  (= unknown x))

;; ---------------------------------------------------------------------------
;; The `:rf.sub` operation vocabulary — ONE predicate, two derivations
;; ---------------------------------------------------------------------------

(def sub-recompute-operations
  "The `:op-type :rf.sub` operations that mean a subscription body RAN.

  Spec 009 §`:op-type` vocabulary puts four operations through the trace
  projection's `:subs` slot — `:rf.sub/run`, `:rf.sub/create`,
  `:rf.sub/skip` and `:rf.sub/dispose` — and only the first two are work.
  `:rf.sub/skip` is a MEMO HIT: the input was `=` to last-seen, so the
  body was suppressed. `:rf.sub/dispose` is an eviction. Reading the slot
  without filtering the operation counts all four as recomputes.

  A literal set for the same reason [[consumed-evidence-schema]] is a
  literal: this namespace parses a wire shape, and a producer that grows
  a fifth operation has to be a decision here rather than a silent
  reclassification.

  **It lives in the shared algebra because two derivations consult it and
  they must not disagree.** They did: `hicasso-advisor` counted a skip as
  a memo hit and derived *searched?* from recompute runs alone, while
  `hicasso-causal` collected every tagged item in the same `:subs` slot
  without filtering operation. One tagged skip therefore made the advisor
  report `:basis :cap` — *no search happened, raise the retention knob* —
  about a window that had retained exactly that evidence, while the causal
  slice reported the same event as an evidenced *subscription recomputed*
  (rf2-hic-037, merged-PR audit #8027). Two definitions of *did work
  happen* is what produced the disagreement, and a third would be worse."
  #{:rf.sub/run :rf.sub/create})

(defn sub-recompute?
  "True when trace event `ev` is a subscription RECOMPUTE — its
  `:operation` is in [[sub-recompute-operations]].

  The one predicate both the advisor's timing fold and the causal slice's
  link-2 roster ask, so a window's recompute count and its recompute
  roster cannot describe two different sets of events."
  [ev]
  (contains? sub-recompute-operations (:operation ev)))

(defn sub-skip?
  "True when trace event `ev` is a MEMO HIT — the cell answered without
  running its body.

  Counted, never summed: a skip carries no duration because nothing ran.
  It is also not nothing — a skip is emitted only when the cell was
  CONSIDERED (Spec 009 §`:rf.sub/skip`: distinct from the case where no
  upstream change reached the sub's input at all), so a retained skip is
  positive evidence that the window observed this read and the memo held."
  [ev]
  (= :rf.sub/skip (:operation ev)))

(def loss-kinds
  "The five states a Hicasso evidence read can be in about a fact, and
  the ONE sentence each gets.

  Four are `:loss` reasons the producer states; `:unknown` is the value it
  states in a field's place, which a `:loss`-bearing projection usually
  carries as well. They are listed together because a reader does not care
  which of the producer's two vocabularies a state came from — they care
  what to do about it, and these are four different remedies plus one
  statement of fact.

  Each entry carries a `:short` for a chip and a `:says` for the sentence
  beneath it, and **no two are equal in either**. A panel that rendered
  two of these the same would put the reader back where the schema found
  them."
  {:cap
   {:short "capped"
    :says  (str "a retention window bounded this — Spec 009's ring holds only "
                "`:rf.trace/events-retained` runs, so what fell off it cannot "
                "be counted. A bigger buffer is the remedy")}

   :opaque
   {:short "opaque"
    :says  (str "the substrate keeps no such fact, deliberately and "
                "permanently. This is not a gap waiting to be closed — "
                "retaining it would cost every application memory for a "
                "panel's benefit")}

   :host-opaque
   {:short "host-opaque"
    :says  (str "React owns this and does not publish it. React DevTools and "
                "the browser performance tools are the authority; a timing "
                "coincidence here would be a guess wearing a number")}

   :uncorrelated
   {:short "uncorrelated"
    :says  (str "the fact is real but joins to nothing — there is no id "
                "linking the two sides, so any link shown would be adjacency "
                "presented as cause. Leads are offered instead")}

   :unknown
   {:short "unknown"
    :says  (str "the producer states this field as not held. It is not empty "
                "and it is not zero — nobody looked, or nobody could")}})

(defn loss-chip
  "One absence, ready to render: `{:kind :testid-suffix :short :says}` — or
  nil when there is nothing absent.

  `loss` is a producer loss map (`{:reason … :dropped …}`), a bare reason
  keyword, or nil. A nil loss with an [[unknown]] value still answers a
  chip, because a field stating `:unknown` without a loss account is
  itself the fifth state.

  The testid suffix is the kind's own name, which is what makes the five
  states DISTINGUISHABLE on the page and not merely in the data — a
  browser assertion can select `…-loss-cap` and never match a
  `…-loss-uncorrelated` row."
  ([loss] (loss-chip loss ::no-value))
  ([loss value]
   (let [reason (cond (map? loss)     (:reason loss)
                      (keyword? loss) loss
                      :else           nil)
         kind   (or reason (when (unknown? value) :unknown))]
     (when-some [entry (get loss-kinds kind)]
       (merge {:kind kind :testid-suffix (name kind)} entry
              (when (map? loss) {:dropped (:dropped loss)}))))))

(defn dropped-label
  "How much was dropped, in the reader's terms: a count, or the honest
  \"an unknown amount\".

  Never blank. A dropped account that rendered as nothing would be the
  absent `:dropped` the producer refuses to emit, reintroduced at the
  last step."
  [dropped]
  (cond
    (unknown? dropped) "an unknown amount"
    (number? dropped)  (str dropped " dropped")
    :else              "an unknown amount"))

;; ---------------------------------------------------------------------------
;; Presence — three empties, three sentences
;; ---------------------------------------------------------------------------

(defn presence
  "Which of the four states this tab is in for `envelope`. See the ns
  docstring's table.

  `:idle` is decided by `rows-empty?` rather than by re-deriving the
  roster here, because the four reads name their rosters differently and
  the caller already holds theirs."
  [envelope rows-empty?]
  (cond
    (nil? envelope)          :absent
    (not (supported? envelope)) :mismatch
    rows-empty?              :idle
    :else                    :live))

(def empty-copy
  "What an EMPTY roster means — PER VIEW, because it is a different fact
  in each of the four.

  The four reads have four scopes, and one shared sentence about an empty
  one can be true of at most a single scope. It was written for the
  mounted census (where the entry cache really is authoritative) and then
  shown under Intents, where an empty roster means a capped window and
  proves nothing about what was dispatched, and under Reads, where it is
  compatible with mounted boundaries that read nothing at all. A confident
  wrong answer is worse than a visible gap, so each view answers for its
  own scope and keeps its own remedy in view (rf2-hic-023, audit #7789).

  Each entry carries its own testid suffix, so a browser assertion cannot
  match the wrong view's empty."
  {:mounted
   {:testid-suffix "empty-mounted"
    :says (str "Hicasso is running and no boundary holds a live read edge. "
               "The read-set entry cache is authoritative about that, so this "
               "is a survey result and not an absence of evidence — but it is "
               "a statement about SUBSCRIPTION, not about the screen. A hidden "
               "subtree that released its reads leaves exactly this census, "
               "and only a later re-subscribe tells the two apart.")}
   :attribution
   {:testid-suffix "empty-attribution"
    :says (str "No subscription cell is currently held. This is not the same "
               "as nothing being mounted: a boundary whose body reads nothing "
               "still mounts and still holds an entry, and it has no edge to "
               "appear here. Check the Mounted view before concluding the "
               "application is idle.")}
   :intents
   {:testid-suffix "empty-intents"
    :says (str "The retained window holds nothing. This is a CAP, not a "
               "finding — Spec 009's ring keeps `:rf.trace/events-retained` "
               "runs and cannot say what fell off it, and a ring of size 0 "
               "cannot say whether anything was dispatched at all. Raise the "
               "retention knob to see further back.")}
   :explain
   {:testid-suffix "empty-explain"
    :says (str "There is no mounted boundary to explain. Why answers per "
               "boundary, so an empty roster here follows the mounted census "
               "and carries its qualifications — it is not a statement that "
               "nothing has re-run.")}
   :advisor
   {:testid-suffix "empty-advisor"
    :says (str "There is no boundary to rank. The advisor ranks the mounted "
               "census, so an empty roster here follows that census — it is "
               "not a verdict that nothing is hot, and it says nothing at all "
               "about lowering, React or layout, which this tab never "
               "measures.")}
   :causal
   {:testid-suffix "empty-causal"
    :says (str "There is no slice to draw: the advisor named no boundary to "
               "trace, so there is no chain to walk. A slice needs a mounted "
               "boundary AND a retained dispatch, and this is the first of "
               "those two missing.")}})

(def presence-copy
  "The sentence each non-live presence gets, and the testid it renders
  under.

  Written out rather than composed, because the whole point is that a
  reader can tell *not running Hicasso* from *running it with nothing
  mounted*, and prose assembled from a shared stem is how those two come
  to look alike again. The `:idle` case is not here — it is per view, in
  [[empty-copy]], because there is no one true sentence for it."
  {:absent
   {:testid-suffix "absent"
    :says (str "No Hicasso evidence on this host. The tool door answered nil, "
               "which means this application is not running Hicasso, or this "
               "is a production build — where the door is erased rather than "
               "empty.")}
   :mismatch
   {:testid-suffix "mismatch"
    :says (str "Hicasso answered with an evidence schema this Xray build was "
               "not taught to parse. Rows are suppressed rather than "
               "mis-parsed: an evolved shape read as though it were the "
               "expected one is worse than no rows at all.")}})

(defn state-copy
  "The copy for `state` in `view` — the one lookup a renderer needs.

  `:idle` resolves through [[empty-copy]] and therefore depends on the
  view; everything else is view-independent. Answering nil for `:live` is
  deliberate: there is nothing to say when there are rows."
  [state view]
  (if (= :idle state)
    (get empty-copy view)
    (get presence-copy state)))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn format-id
  "A keyword as a reader reads it — `:app/thing`, not `app/thing`."
  [x]
  (if (keyword? x) (str x) (pr-str x)))

(defn id-slug
  "A READABLE testid stem for `x`. Total: an unprintable value still
  yields a selectable id rather than an exception at render time.

  Lossy on purpose, and therefore never an identity on its own — it maps
  every run of non-alphanumerics to one `-` and folds case, so
  `[:a/b :c :d]`, `[:a-b :c :d]` and `[:a :b/c :d]` all come out
  `a-b-c-d`. [[identity-slug]] is what a React key and a DOM testid are
  made of; this is the half of it a human reads."
  [x]
  (-> (str (if (keyword? x) (subs (str x) 1) (pr-str x)))
      (string/replace #"[^a-zA-Z0-9]+" "-")
      (string/replace #"^-|-$" "")
      (string/lower-case)))

(def ^:private hex-digits "0123456789ABCDEF")

(defn- code-unit
  "One character's UTF-16 code unit.

  Both hosts iterate a string as UTF-16 code units, so both encoders
  agree character for character — which they must, because the suite that
  asserts these slugs is a `.cljc` and runs the same expectations under
  the JVM and under node."
  [c]
  #?(:clj  (int c)
     :cljs (.charCodeAt c 0)))

(defn- hex
  "`n` as exactly `width` uppercase hex digits."
  [n width]
  (apply str (map (fn [shift] (nth hex-digits (bit-and (bit-shift-right n shift) 15)))
                  (range (* 4 (dec width)) -1 -4))))

(defn- escaped
  "`s` rewritten into the alphabet `[A-Za-z0-9%u]`, REVERSIBLY.

  An ASCII alphanumeric survives as itself; every other character becomes
  `%` and its code unit in hex — two digits below 256, and `%u` plus four
  digits above it. `%` is itself escaped and `u` is not a hex digit, so
  the result parses back exactly one way and no two strings can encode to
  one. That is the entire job: [[id-slug]] is a MANY-to-one map, and
  many-to-one is how two rows came to share a React key."
  [s]
  (->> s
       (map (fn [c]
              (let [n (code-unit c)]
                (cond
                  (or (<= 48 n 57) (<= 65 n 90) (<= 97 n 122)) (str c)
                  (< n 256) (str "%" (hex n 2))
                  :else     (str "%u" (hex n 4))))))
       (string/join)))

(defn identity-slug
  "The React key and DOM testid for one identity string `s`: the readable
  [[id-slug]] stem, a `-`, then [[escaped]].

  TWO JOBS, WHICH ARE NOT THE SAME JOB — which is why the string has two
  halves. A React key must be UNIQUE or the reconciler treats two rows as
  one. A testid must be STABLE and select exactly one row, and it helps
  enormously if a human reading a failing selector can tell which row it
  named. Neither has to be pretty, and neither is the row's LABEL: the
  projected query and the frame are printed on the row itself, which is
  where a reader actually looks. So the stem carries the readability and
  the tail carries the identity, and neither has to compromise for the
  other.

  INJECTIVE, and provably so rather than by inspection of a few examples:
  `escaped` emits no `-`, so the LAST `-` in the result is always the
  join, and the tail behind it decodes to exactly one input string. The
  encoding that preceded this one was `id-slug` alone, which folded
  `[:a/b :c :d]`, `[:a-b :c :d]` and `[:a :b/c :d]` onto one `a-b-c-d` —
  three legal, distinct projected identities under one React key and one
  testid (audit #7820). Namespaced and hyphenated ids are ordinary
  programmer input, not an adversarial edge."
  [s]
  (str (id-slug s) "-" (escaped s)))

(def redacted
  "The egress projector's whole-value sentinel.

  A literal for the same reason [[unknown]] is one: this namespace reads a
  wire shape and states what it found, and a renderer that could not
  recognise the sentinel would print it at a developer as though it were
  a query."
  :rf/redacted)

(defn read-label
  "One read edge as a reader reads it, from the PROJECTED key element
  `[frame-id sub-id query]` the producer exports.

  The query is already projected when it arrives, so this prints it as
  found and invents nothing. When the projector redacted it WHOLE the
  query no longer identifies anything, so the registration id is named
  beside the sentinel — otherwise every redacted read on the page reads
  the same, and a reader loses the one distinction the producer took care
  to keep (rf2-hic-023, audit #7789)."
  [[_frame-id sub-id query]]
  (if (= redacted query)
    (str (format-id sub-id) " " (format-id query))
    (format-id query)))

(defn latest-read-label
  "One `:latest-reads` entry as a reader reads it.

  The producer answers `{:sub-id :query :frame-id}` rather than a bare
  sub-id, so this renders the projected QUERY — which is what tells two
  parameterizations of one registered sub apart. Falls back to the sub-id
  when the query redacted whole, exactly as [[read-label]] does."
  [{:keys [sub-id query]}]
  (read-label [nil sub-id query]))

(defn read-key-str
  "The WHOLE projected read identity as one string: frame, registration
  id, projected query — in the order the producer exports them.

  Every part is load-bearing and the frame is the one that was missing
  (rf2-hic-023, audit #7802). Frames are isolated contexts, so a read of
  `[:row 1]` in frame A and a read of `[:row 1]` in frame B are two
  different facts about two different applications, not one fact seen
  twice. Dropping the frame here made them one string, which made them one
  React key and one DOM testid.

  Projected fields only. The query arrives already projected and is
  printed as found; re-admitting the raw query to make a slug more
  readable would undo the producer's redaction at the last step, which is
  the exact escape the #7789 audit caught.

  ONE `pr-str` over the whole triple, not three joined by a separator: a
  separator BETWEEN printed components can be forged by a component that
  contains it, while `pr-str` quotes what it prints, so the brackets and
  the spaces between elements cannot be. It also normalises the triple,
  so an identity arriving as a seq does not mint a second React key for
  the same fact. This is the same canonical string the producer sorts its
  own rows by, so consumer and producer agree on what the identity is.

  DOMAIN, stated rather than assumed: injective over the EDN a projected
  identity is made of — keywords, strings, numbers, booleans, nil and
  sequential collections of those — because `pr-str` is reader-faithful
  there. It does not claim to separate what `pr-str` cannot print apart:
  a deliberately reader-hostile `(symbol \"a :b\")`, or two `=`-equal
  maps built in two insertion orders (which print in iteration order —
  the producer's canonical form, inherited here rather than a second one
  invented)."
  [[frame-id sub-id query]]
  (pr-str [frame-id sub-id query]))

(defn read-slug
  "A React key / DOM testid for ONE projected read identity — see
  [[read-key-str]] for why all three parts are in it, and
  [[identity-slug]] for why the string has a readable half and an
  injective half."
  [read-identity]
  (identity-slug (read-key-str read-identity)))

(defn boundary-label
  "A boundary's edge set as one line — the identity the runtime actually
  retains, spelled out.

  A read-free boundary answers the explicit phrase rather than an empty
  string: `[]` rendered as blank is the same failure as `:unknown`
  rendered as blank, one level down."
  [{:keys [key]}]
  (if (seq key)
    (string/join " + " (map read-label key))
    "(reads nothing)"))

(defn boundary-slug
  "A stable testid slug for a boundary key — the WHOLE projected key, one
  [[read-key-str]] per element.

  Never the raw query: a testid is a string a browser assertion selects on
  and a screenshot carries, so deriving one from an application's
  arguments would put those arguments in the DOM after the schema had
  projected them out of the data. The sub-id keeps two wholly-redacted
  reads selectable apart, and the FRAME keeps two frames' boundaries apart
  — it was dropped, so `[[:frame/a :row [:row 1]]]` and
  `[[:frame/b :row [:row 1]]]` both slugged `row-row-1`, giving two
  genuinely different boundaries one React key and one testid
  (audit #7802).

  A boundary reads a SET of cells, so the join BETWEEN elements has to be
  as unforgeable as the join inside one. Each element is already its own
  `pr-str`, so wrapping the space-joined elements in brackets is exactly
  `pr-str` of the normalised key — one reader-faithful string for the
  whole boundary, then [[identity-slug]] over it.

  A read-free key keeps its readable name, and no key with reads in it
  can mint that name: every other slug carries the `%` that
  [[escaped]] emits for the leading `[`."
  [{:keys [key]}]
  (if (seq key)
    (identity-slug (str "[" (string/join " " (map read-key-str key)) "]"))
    "reads-nothing"))

;; ---------------------------------------------------------------------------
;; The four view projections
;; ---------------------------------------------------------------------------

(def sub-modes
  "The tab's six views, in order.

  The first four are the questions Spec SN §10 says a developer asks of a
  view substrate, one sub-view each. The last two are rf2-hic-037's: the
  ADVISOR, which ranks and classifies and then refuses the routes its
  evidence cannot support, and the CAUSAL slice, which walks §10's chain
  link by link and labels every one it cannot evidence.

  They are sub-views of this tab rather than a tab of their own because
  they are derivations of exactly the same one-turn evidence — a second
  tab would take a second turn, and a mount landing between the two would
  give a ranking about a census the slice no longer agrees with."
  [{:id :mounted     :label "Mounted"     :mnem "m"
    :asks "which boundaries are mounted, over which frames"}
   {:id :attribution :label "Reads"       :mnem "r"
    :asks "which boundaries read each subscription"}
   {:id :intents     :label "Intents"     :mnem "i"
    :asks "what was dispatched, in order, inside the retained window"}
   {:id :explain     :label "Why"         :mnem "w"
    :asks "which reads changed, and what that can and cannot prove"}
   {:id :advisor     :label "Advisor"     :mnem "a"
    :asks "which boundary is hot, what owns the pressure, and the smallest route that addresses it"}
   {:id :causal      :label "Causal"      :mnem "c"
    :asks "one dispatch, walked link by link from event to paint, with every missing link named"}])

(def sub-mode-ids (into #{} (map :id) sub-modes))

(def default-sub-mode (:id (first sub-modes)))

(defn normalise-sub-mode
  "`m` when it names a view, otherwise [[default-sub-mode]]."
  [m]
  (if (contains? sub-mode-ids m) m default-sub-mode))

(defn mounted-rows
  "The Mounted view's rows: one per distinct edge set, carrying the
  instance count and the per-row absences already turned into chips.

  `[]` for an unsupported or absent envelope — [[presence]] is what tells
  the panel which empty it is holding, so this function does not have to
  guess."
  [envelope]
  (if-not (supported? envelope)
    []
    (mapv (fn [row]
            {:boundary   (:boundary row)
             :label      (boundary-label (:boundary row))
             :slug       (boundary-slug (:boundary row))
             :instances  (:instances row)
             :frame      (:frame row)
             :frame-chip (loss-chip nil (:frame row))
             :view-chip  (loss-chip (:loss (:naming envelope)) (:view row))
             :reads      (mapv (fn [r] {:sub-id (:sub-id r)
                                        :query  (:query r)
                                        :epoch  (:epoch r)}) (:reads row))})
          (:boundaries envelope))))

(defn attribution-rows
  "The Reads view's rows: one per live subscription cell, with its
  fan-out and the boundaries holding it.

  The row's identity is the edge's WHOLE projected identity — frame,
  registration id, projected query — in both the label and the slug. It
  was the sub-id alone, so `[:row 1]` in frame A and `[:row 2]` in frame B
  were two rows named `row` under one testid, and the view printed neither
  the query nor the frame that told them apart (audit #7802). Every field
  it prints is one the producer already projected; nothing raw is
  recovered to make a row legible."
  [envelope]
  (if-not (supported? envelope)
    []
    (mapv (fn [edge]
            (let [read-id [(:frame-id edge) (:sub-id edge) (:query edge)]]
              {:sub-id     (:sub-id edge)
               :slug       (read-slug read-id)
               :label      (read-label read-id)
               :query      (:query edge)
               :frame-id   (:frame-id edge)
               :frame-chip (loss-chip nil (:frame-id edge))
               :epoch      (:epoch edge)
               :fan-out    (:fan-out edge)
               :readers    (mapv (fn [b] {:label (boundary-label b)
                                          :slug  (boundary-slug b)}) (:readers edge))}))
          (:edges envelope))))

(defn intent-rows
  "The Intents view's rows: what was dispatched, newest LAST, as the ring
  holds them."
  [envelope]
  (if-not (supported? envelope)
    []
    (mapv (fn [i]
            {:dispatch-id (:dispatch-id i)
             :event-id    (:event-id i)
             ;; Slugged by DISPATCH-ID as well as event id: the stream is
             ;; ordered by dispatch and one event id can appear many times
             ;; in a window, so an event-only testid would name several rows.
             ;; Through the same injective encoding as a read key, for the
             ;; same reason: `id-slug` alone folded `:a/b-c` and `:a-b/c`
             ;; onto one intent testid (audit #7820).
             :slug        (identity-slug (pr-str [(:event-id i) (:dispatch-id i)]))
             :arg-count   (:arg-count i)
             :frames      (:frames i)
             :sub-ids     (:sub-ids i)})
          (:intents envelope))))

(defn explain-rows
  "The Why view's rows: the proven half and the uncorrelated half, kept
  apart on the row exactly as the producer keeps them apart in the data.

  `:proven` is what the epoch stamps establish. `:lead-chip` is the
  absence account for the other half, and it is the field a reader must
  not be able to mistake for an answer."
  [envelope]
  (if-not (supported? envelope)
    []
    (mapv (fn [ex]
            (let [leads (:candidates ex)]
              {:boundary     (:boundary ex)
               :label        (boundary-label (:boundary ex))
               :slug         (boundary-slug (:boundary ex))
               :frame        (:frame ex)
               ;; The frame is rendered, not merely carried. Two boundaries
               ;; reading the same query in two frames have the same label,
               ;; and frames are isolated contexts — so without the frame on
               ;; screen the reader is looking at two identical lines that
               ;; are two different facts (audit #7802).
               :frame-chip   (loss-chip nil (:frame ex))
               :instances    (:instances ex)
               :snapshot     (:snapshot ex)
               :peak-epoch   (:peak-epoch ex)
               ;; The producer now names the READ, not just its sub-id, so
               ;; `[:row 1]` and `[:row 2]` no longer answer as one `:row`.
               :latest-reads (if (unknown? (:latest-reads ex))
                               (:latest-reads ex)
                               (mapv latest-read-label (:latest-reads ex)))
               :proven?      (not (unknown? (:latest-reads ex)))
               :cause-chip   (loss-chip (:loss ex) (:cause ex))
               :leads        (if (unknown? leads) [] leads)
               :leads-known? (not (unknown? leads))}))
          (:explanations envelope))))

(defn read-summary
  "One muted line per view: what this envelope claims about itself.

  Rendered on every view, including the complete ones, because a reader
  who only ever sees a completeness claim when it is bad learns to read
  its absence as good news."
  [envelope]
  (when (supported? envelope)
    (let [chip (loss-chip (:loss envelope))]
      (string/join " · "
                   (remove nil?
                           [(str "scope " (format-id (:scope envelope)))
                            (str "basis " (format-id (:basis envelope)))
                            (if (:complete? envelope)
                              "complete for this scope"
                              "INCOMPLETE for this scope")
                            (when chip
                              (str (:short chip) " — "
                                   (dropped-label (:dropped chip))))])))))
