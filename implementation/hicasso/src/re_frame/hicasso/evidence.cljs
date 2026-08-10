(ns re-frame.hicasso.evidence
  "ONE versioned, adapter-neutral evidence schema — and the four things
  every projection in it has to say about itself (rf2-hic-023).

  Spec SN §10 rules that Hicasso exposes one dev-only evidence surface
  that Xray and the AI pair consume UNCHANGED, and that every envelope
  carries schema, producer, operation, scope, basis, completeness and
  loss. `lanes/testing-xray.md` states the sentence the whole namespace
  turns on, in six words:

  > **Unknown is never encoded as an empty collection.**

  Which is what this file is for. Breadth is not the problem — a tool can
  always ask for another roster. The problem is a projection that reports
  what it happened to see as though it were what there IS, and every
  field here exists to make that shape impossible to write down.

  ## The four axes

      {:scope     :mounted-boundaries   ; what this covers
       :basis     :observation          ; how it knows
       :complete? true                  ; only ever RELATIVE to :scope
       :loss      nil}                  ; or {:reason … :dropped …}

  `:scope` and `:basis` are separate on purpose, because a single ranking
  such as *proven > declared > observed* is wrong. A live read can be
  complete for the instant it names and incomplete as a claim about any
  other instant; a declaration can completely list what an author said
  without saying what ran. Neither dominates, and a projection that named
  only one of them would be making the other claim silently.

  `:complete?` means nothing on its own — it is always completeness FOR
  the stated scope. And `:loss` is the honest half: `nil` says nothing
  was dropped, and a loss map says a reason from the closed
  [[loss-reasons]] and a `:dropped` count that is either a number or the
  explicit [[unknown]]. An ABSENT `:dropped` is refused, because that is
  exactly the shape in which unknown comes to look like none.

  ## The five loss states, and why the vocabulary is closed

  The bead names five and they are here in full — `unknown`, `opaque`,
  `host-opaque`, `cap`, `uncorrelated` — split across two closed
  vocabularies because two of them are ALSO how a projection knows what
  it says:

  - [[unknown]] is a VALUE. It is what a field states where the fact is
    not held, and it is the `:dropped` count a window that cannot count
    what it never saw reports. A named value rather than `nil` or an
    absent key, because both of those read as \"none\" at a glance and one
    of them reads as \"none\" to `(get loss :dropped)` as well.
  - `:opaque` and `:host-opaque` are both a [[basis-kinds]] and a
    [[loss-reasons]] member, because \"the substrate does not retain this\"
    is simultaneously how the projection knows (it does not) and what it
    lost.
  - `:cap` and `:uncorrelated` are losses only. A cap is a knob; an
    uncorrelated fact is a join that was never available to make. Two
    different remedies, so two different members.

  ## What is refused, and why each refusal is the ruling

  [[projection]] is the only door, and it throws rather than emitting
  when

    - a cap was hit and the projection still claims completeness. Any cap
      sets `:complete? false` and records explicit loss — a truncated
      roster reported as total is the failure this schema exists to
      prevent;
    - an `:opaque` or `:host-opaque` basis claims completeness. A
      projection that cannot see a fact cannot be complete about it;
    - a `:declaration` basis claims completeness without naming what
      ENFORCES the declaration. An unenforced annotation is a claim, not
      proof;
    - a loss is stated without a `:dropped` count, or with a reason
      outside [[loss-reasons]];
    - **a roster is EMPTY on an unseeing basis.** This is the clause the
      whole file exists for and it is the one the sibling schemas do not
      have. `{:basis :opaque … :boundaries []}` is the exact sentence
      \"unknown looks like none\", written down; the door refuses it and
      requires the roster to state [[unknown]] instead.

  ## Adapter-neutral, and what `:producer` is for

  Nothing in the vocabulary names a boundary, a cell, a React fiber or a
  `defview`. A tool consuming this schema is reading *a view substrate's
  evidence*, and `:producer` names which substrate produced the envelope
  — so a panel can render Hicasso and a successor through one parser, and
  a bug report can say which one answered. `:static-proof` is
  deliberately ABSENT from [[basis-kinds]]: Hicasso interprets, there is
  no analyzer, and offering a basis this producer can never claim would
  be an invitation to claim it.

  ## This namespace holds nothing

  No atom, no ring, no cache, no configuration. A projection is a value a
  caller built and handed to a reader, and history is Spec 009's
  per-frame retained-event ring under Spec 009's one knob — see
  [[retention]]. Hicasso adds evidence records and loss markers, not
  another observability product.

  Normative owner: `docs/design/hicasso/product/specification.md` §10 and
  `docs/design/hicasso/product/lanes/testing-xray.md` §Evidence contract."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The version and the producer
;; ---------------------------------------------------------------------------

(def schema
  "The schema version every envelope carries.

  A consumer validates this FIRST and refuses an envelope it does not
  recognise. Versioning an evidence surface is not ceremony: the
  alternative is tools inferring the shape from the fields that happen to
  be present, which makes every field an accidental ABI.

  ## v1 → v2, and why a version that lies is worse than none

  The #7789 audit repair changed the WIRE SHAPE without changing this
  literal, and the merged-PR audit of #7802 called that out as the one
  defect a version exists to prevent. Four changes, each of which a v1
  parser reads as something it is not:

  | Field | v1 | v2 |
  |---|---|---|
  | a boundary key element | `[frame-id query]` | `[frame-id sub-id projected-query]` |
  | an intent row's frame | `:frame-id`, singular | `:frames`, a vector |
  | `:latest-reads` | bare sub-ids | `{:sub-id :query :frame-id}` maps |
  | the `:host` projection | commit/paint/attempt | `:visibility` and `:hidden-retained` besides |

  A consumer pinned to v1 and handed a v2 envelope would take the sub-id
  in a key element for the query, iterate a map where it expected a
  keyword, and read an absent `:frame-id` as a frameless intent. The pin's
  whole purpose is that an evolved shape MISMATCHES rather than being
  mis-parsed as exact, so it is the version — not the reader — that has to
  move.

  **There is no v1 acceptance path and no compatibility adapter.** This is
  pre-alpha; the honest bump is a bump, and a shim would recreate exactly
  the silent misread the pin refuses. A v1-stamped envelope is a
  MISMATCH at every consumer, and
  `hicasso-helpers-cljs-test/the-superseded-v1-shape-is-refused-rather-than-mis-parsed`
  is the witness that it still bites."
  :re-frame.hicasso.evidence/v2)

(def producer
  "Which substrate produced the envelope.

  The schema is adapter-neutral, so a consumer that reads a roster of
  mounted boundaries cannot otherwise tell whose boundaries they are.
  Carried on every envelope rather than inferred from the read's name,
  because the read names are neutral too."
  :re-frame/hicasso)

;; ---------------------------------------------------------------------------
;; The vocabularies — closed, because an open one is not a claim
;; ---------------------------------------------------------------------------

(def basis-kinds
  "How a projection knows what it says. Four, and no fifth.

  Closed because a basis is what a reader uses to decide how far to trust
  the projection, and a vocabulary anyone may extend cannot carry that
  weight. `:static-proof` is absent by construction — this producer
  interprets, and a basis it can never honestly claim is a basis it would
  eventually claim dishonestly."
  {:observation
   "read off live runtime state — complete for the instant it names, never for any other"

   :declaration
   "asserted by the author — proof only where something enforces the assertion"

   :opaque
   "the substrate deliberately retains no such fact, and no amount of asking changes that"

   :host-opaque
   "React owns the fact and does not expose it — DevTools is the authority, not this producer"})

(def scopes
  "The NAMED scopes, beside the map-shaped ones [[scope?]] also accepts."
  {:mounted-boundaries
   "every boundary holding at least one live read edge RIGHT NOW — current state, never a history"

   :read-edges
   "every (frame, query) the runtime holds a cell for, and the boundaries reading it"

   :retained-window
   "what Spec 009's per-frame retained-event ring still holds, under its one knob"

   :host-private
   "work behind React's own boundary — named so its absence is a fact rather than a gap"})

(def loss-reasons
  "Why a projection could not carry something. Closed, and each reason is
  a different remedy for the reader: a cap is a knob, an opaque fact is a
  thing the substrate chose not to keep, a host-opaque one is React's to
  tell, and an uncorrelated one is a join that was never available."
  {:cap
   "a retention window or emission cap bounded what could be carried"

   :opaque
   "the substrate retains no such fact — deliberately, and permanently"

   :host-opaque
   "React holds the fact and does not publish it"

   :uncorrelated
   "the fact is real but joins to nothing, so no basis can be supplied for the link"})

(def unknown
  "The explicit value for a fact a projection does not hold, and the
  `:dropped` count for a loss it cannot size.

  A named value rather than `nil` or an absent key, because both of those
  read as \"none\" at a glance and one of them reads as \"none\" to
  `(get m :k)` as well. Renderers test for it with an `=` against this
  var so the marker is displayed as the absence it is."
  :unknown)

(def reads
  "The read operations this schema defines, and their questions.

  Closed, and stamped on every envelope as `:read`, so a consumer holding
  an envelope out of context still knows which question it answers. The
  four are the four Xray views."
  {:mounted-boundaries
   "which boundaries hold live read edges right now, over which frames"

   :read-attribution
   "which boundaries read each subscription — the reverse edge, exactly"

   :intents
   "what was dispatched inside the retained window, in order"

   :explain-render
   "which reads changed, and which boundaries that reached"})

;; ---------------------------------------------------------------------------
;; The predicates behind the ledger
;; ---------------------------------------------------------------------------

(defn scope?
  "True when `s` is a scope a projection may claim: one of [[scopes]], or
  a NON-EMPTY map naming the slice it covers — `{:frame :app/main}`,
  `{:retained-runs 12}`.

  An empty map is refused. `{}` is a scope that says nothing, and a
  completeness claim relative to nothing is a completeness claim about
  everything."
  [s]
  (or (contains? scopes s)
      (and (map? s) (seq s) (not (record? s)))))

(defn loss?
  "True when `l` is a legal loss: `nil`, or a map naming a reason from
  [[loss-reasons]] and a `:dropped` that is a count or [[unknown]].

  `nil` is legal and is not the same as incomplete: a projection honestly
  scoped to one instant is incomplete as a claim about any other instant
  while having dropped nothing at all."
  [l]
  (or (nil? l)
      (and (map? l)
           (contains? loss-reasons (:reason l))
           (let [d (get l :dropped ::absent)]
             (or (nat-int? d) (= unknown d))))))

(def unseeing-bases
  "The bases on which a roster CANNOT be a survey.

  A projection carrying one of these did not look, so an empty collection
  under it is not a finding — it is the absence of one, and the two must
  not print the same. [[projection]] refuses the empty and requires
  [[unknown]]."
  #{:opaque :host-opaque})

(def axis-keys
  "The envelope's own keys — everything a projection says ABOUT itself.

  Named as data so the empty-roster invariant can tell an axis from a
  roster without a list of roster names that would have to be kept in
  step with every read."
  #{:schema :producer :read :scope :basis :complete? :loss :enforced-by})

;; ---------------------------------------------------------------------------
;; The projection ledger
;; ---------------------------------------------------------------------------

(def projection-fields
  "The four axes, as data.

  A vector rather than a `when-not` cascade because the suite that proves
  every axis is required ENUMERATES this, so a fifth axis added later is
  covered without anyone remembering to cover it."
  [{:key      :scope
    :names    "the scope"
    :valid?   scope?
    :expected (str "one of " (pr-str (vec (sort (keys scopes))))
                   ", or a non-empty map naming the slice this covers")}

   {:key      :basis
    :names    "the basis"
    :valid?   #(contains? basis-kinds %)
    :expected (str "one of " (pr-str (vec (sort (keys basis-kinds)))))}

   {:key      :complete?
    :names    "the completeness claim"
    :valid?   boolean?
    :expected "true or false — completeness RELATIVE to the stated scope, never absolute"}

   {:key      :loss
    :names    "the loss account"
    :valid?   loss?
    :expected (str "nil when nothing was dropped, or {:reason <one of "
                   (pr-str (vec (sort (keys loss-reasons))))
                   "> :dropped <count or " unknown ">} — an absent :dropped reads as none")}])

(defn- empty-rosters
  "The roster keys whose value is an EMPTY collection — the axes
  excluded, because `:loss nil` and a map-shaped `:scope` are not
  rosters."
  [p]
  (into []
        (comp (remove (fn [[k _]] (contains? axis-keys k)))
              (filter (fn [[_ v]] (and (coll? v) (not (map? v)) (empty? v))))
              (map key))
        p))

(def projection-invariants
  "The cross-field rules. Each one is a shape in which a projection would
  claim more than it knows, stated as a predicate so it cannot be
  forgotten at a call site."
  [{:holds?  (fn [{:keys [complete? loss]}] (not (and complete? (some? loss))))
    :message (str "it claims completeness and also reports loss. Any cap sets "
                  ":complete? false and records explicit loss — a truncated roster "
                  "reported as total is the one failure this schema exists to prevent")}

   {:holds?  (fn [{:keys [basis complete?]}]
               (not (and (contains? unseeing-bases basis) complete?)))
    :message (str "it claims completeness on a basis that cannot see. A projection "
                  "that does not hold a fact cannot be complete about it — name the "
                  "absence, do not claim to have surveyed it")}

   {:holds?  (fn [{:keys [basis complete?] :as p}]
               (not (and (= :declaration basis) complete? (not (contains? p :enforced-by)))))
    :message (str "it claims completeness on a :declaration basis without naming "
                  "what enforces the declaration. An unenforced annotation is a "
                  "claim, not proof — add :enforced-by, or say :complete? false")}

   {:holds?  (fn [{:keys [basis] :as p}]
               (not (and (contains? unseeing-bases basis) (seq (empty-rosters p)))))
    :message (str "it answers with an EMPTY roster on a basis that cannot see — "
                  "the exact shape in which unknown comes to look like none. A "
                  "projection that did not look states " unknown " where the roster "
                  "would be, never `[]`, which a reader cannot tell from a survey "
                  "that found nothing")}])

(defn- field-defects
  [p]
  (into []
        (keep (fn [{:keys [key names valid? expected]}]
                (if-not (contains? p key)
                  {:defect :missing :key key :names names :expected expected}
                  (let [v (get p key)]
                    (when-not (valid? v)
                      {:defect :invalid :key key :names names :expected expected :observed v})))))
        projection-fields))

(defn defects
  "Answer a vector of defects in the projection `p` — empty when it may be
  emitted.

  Field defects first, then the cross-field invariants, and the
  invariants are only consulted once every field is well formed: an
  invariant over a missing field would report a second, derived complaint
  about the same mistake."
  [p]
  (let [ds (field-defects p)]
    (if (seq ds)
      ds
      (into [] (keep (fn [{:keys [holds? message]}]
                       (when-not (holds? p) {:defect :incoherent :message message})))
            projection-invariants))))

(defn- defect-line
  [{:keys [defect key names expected observed message]}]
  (case defect
    :missing    (str key " (" names ") is missing; expected " expected)
    :invalid    (str key " (" names ") is invalid — " (pr-str observed)
                     "; expected " expected)
    :incoherent message))

(defn defects-message
  "The one-line human sentence a refused projection throws with."
  [ds]
  (str "This evidence projection cannot be emitted: " (count ds)
       (if (= 1 (count ds)) " problem" " problems")
       " — " (str/join "; " (map defect-line ds))
       ". Every evidence projection states its scope, basis, completeness relative "
       "to that scope, and loss; unknown is never encoded as an empty collection."))

(defn projection
  "Answer `p` when it states all four axes coherently, otherwise throw
  naming everything that does not hold.

  The ONLY door. There is no lenient variant and no `:strict?` flag,
  because a projection that may be emitted without its completeness claim
  is a projection that eventually is — and the reader has no way to tell
  which one they are holding."
  [p]
  (let [ds (defects p)]
    (when (seq ds)
      (throw (ex-info (defects-message ds)
                      {:re-frame.hicasso.evidence/defect :incomplete-projection
                       :defects ds})))
    p))

(defn capped
  "Answer `p` with `k`'s collection truncated to `limit` and the loss
  stated — or `p` unchanged, and still validated, when nothing was
  dropped.

  This is the honest half made cheap. A cap enforced at the call site is
  a cap someone will one day apply without also flipping `:complete?`;
  applied here it cannot be, because the truncation and the loss account
  are the same expression."
  [p k limit]
  (let [xs (get p k)
        n  (count xs)]
    (if (<= n limit)
      (projection p)
      (projection (assoc p
                         k          (vec (take limit xs))
                         :complete? false
                         :loss      {:reason :cap :dropped (- n limit)})))))

;; ---------------------------------------------------------------------------
;; The envelope
;; ---------------------------------------------------------------------------

(def envelope-fields
  "What an envelope names before a projection is even consulted — the
  three identity axes on top of the four claim axes.

  Spec SN §10: *every envelope carries schema, producer, operation,
  scope, basis, completeness and loss.* The last four are
  [[projection-fields]]; these are the first three."
  [{:key      :schema
    :names    "the schema version"
    :valid?   #(= schema %)
    :expected (str "exactly " schema " — a consumer validates this first and refuses "
                   "what it does not recognise")}

   {:key      :producer
    :names    "the producing substrate"
    :valid?   #(= producer %)
    :expected (str "exactly " producer " — the schema is adapter-neutral, so the "
                   "envelope names whose evidence this is")}

   {:key      :read
    :names    "the read operation"
    :valid?   #(contains? reads %)
    :expected (str "one of " (pr-str (vec (sort (keys reads))))
                   " — an envelope out of context still says which question it answers")}])

(defn envelope
  "Answer `e` when it is a well-formed envelope, otherwise throw.

  An envelope IS a projection with three more axes, so it goes through
  [[projection]] as well — which is what makes \"every envelope carries
  scope, basis, completeness and loss\" a structural property of this door
  rather than a convention each read is asked to follow."
  [e]
  (let [ds (into []
                 (keep (fn [{:keys [key names valid? expected]}]
                         (let [v (get e key ::absent)]
                           (cond
                             (= ::absent v) {:defect :missing :key key :names names
                                             :expected expected}
                             (not (valid? v)) {:defect :invalid :key key :names names
                                               :expected expected :observed v}))))
                 envelope-fields)]
    (when (seq ds)
      (throw (ex-info (str "This evidence envelope cannot be emitted: " (count ds)
                           (if (= 1 (count ds)) " field is" " fields are")
                           " missing or malformed — " (str/join "; " (map defect-line ds))
                           ". One versioned, adapter-neutral schema (spec SN §10).")
                      {:re-frame.hicasso.evidence/defect :incomplete-envelope
                       :defects ds})))
    (projection e)))

;; ---------------------------------------------------------------------------
;; Retention — the existing axis, named as data
;; ---------------------------------------------------------------------------

(def retention
  "Where evidence history lives: the existing per-frame retained-event
  ring specified by Spec 009, and nowhere else.

  Data rather than a paragraph, because the claim is checkable and the
  suite checks it. Neither this namespace nor
  [[re-frame.hicasso.tool]] holds an atom, a ring, a cache or a
  configuration of its own; the runtime's own dirty set, cell table and
  read-set entry cache are current state with no time axis, and the only
  history a Hicasso evidence read folds is this ring, at READ time.

  There is a reason beyond tidiness. A second retention mechanism would
  be a second thing to disable in production, a second place a payload
  could survive a request, and a second answer to \"how far back does this
  go?\" that disagrees with the first."
  {:owner        :spec-009
   :mechanism    :per-frame-retained-event-ring
   :knob         :rf.trace/events-retained
   :configure-at [:trace-buffer :events-retained]})
