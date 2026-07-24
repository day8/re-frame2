(ns re-frame.freehand.evidence
  "ONE versioned, occurrence-keyed evidence schema — and the four things
  every projection in it has to say about itself.

  D020 rules that Freehand exposes one read-only evidence surface shared
  by interpreted and compiled execution, keyed by a runtime occurrence,
  and that it uses D012's vocabulary throughout: *every evidence
  projection states its scope, basis, completeness relative to that scope,
  and loss.* The sentence that ruling turns on is the shortest one in
  either document:

  > **\"Unknown\" must not look like \"none\".**

  Which is what this namespace is for. Breadth is not the problem — a
  tool can always ask for another roster. The problem is a projection that
  reports what it happened to see as though it were what there is, and
  every field here exists to make that shape impossible to write down.

  ## The four axes

      {:scope     :possible-sites          ; what this covers
       :basis     :static-proof            ; how it knows
       :complete? true                     ; only ever RELATIVE to :scope
       :loss      nil}                     ; or {:reason … :dropped …}

  `:scope` and `:basis` are separate on purpose, because a single ranking
  such as *proven > declared > observed* is wrong. A runtime capture can
  be complete for one committed generation and incomplete as a claim about
  all executions; a static manifest can completely list possible sites
  without saying which ran. Neither dominates, and a projection that
  named only one of them would be making the other claim silently.

  `:complete?` means nothing on its own — it is always completeness FOR
  the stated scope. And `:loss` is the honest half: `nil` says nothing
  was dropped, and a loss map says a reason and a `:dropped` count that is
  either a number or the explicit `:unknown`. An ABSENT `:dropped` is
  refused, because that is exactly the shape in which unknown comes to
  look like none.

  ## What is refused, and why each refusal is the ruling

  [[projection]] is the only door, and it throws rather than emitting when

    - a cap was hit and the projection still claims completeness. Any cap
      sets `:complete? false` and records explicit loss (D020) — a
      truncated roster reported as total is the failure mode this whole
      schema exists to prevent;
    - an `:opaque` basis claims completeness. A projection that cannot see
      inside a host boundary cannot be complete about what is inside it;
    - a `:declaration` basis claims completeness without naming what
      ENFORCES the declaration. An unenforced annotation is a claim, not
      proof (D012);
    - a loss is stated without a `:dropped` count, or with a reason
      outside [[loss-reasons]].

  ## Occurrence-keyed

  A record is keyed by a runtime OCCURRENCE plus a monotonic
  `:generation`, never by a query key, a lexical site id, or a
  caller-supplied controller id. Those are three different things and the
  reason to keep them apart is that the first two are implementation
  detail — an interpreted cell knows a query key, a compiled one knows a
  lexical site — so a tool keyed on either would be a tool that behaves
  differently depending on how a view was lowered. A caller-supplied
  application id may ride along as ordinary public props data; it does not
  replace runtime identity.

  ## Every id here spells its namespace in full

  `:re-frame.freehand.evidence/v1`, `:re-frame.freehand.evidence/defect`
  — Freehand's reserved keyword root is the public namespace written out
  rather than an `:rf.<area>/*` segment, so an id names its substrate
  wherever it surfaces: source, data keywords, a refused record's
  ex-data, generated documentation, AI context. Nothing here reaches for
  the donor's `:rf.ui.evidence/*` spelling, which belongs to a different
  artefact's projection and would make this schema look like a
  continuation of it.

  ## Retention is Spec 009's, and this namespace holds nothing

  [[retention]] names the existing per-frame retained-event ring and its
  one knob. There is no Freehand history store, no root-level retention
  option, and no state of any kind in this namespace — it is values and
  the functions that refuse malformed ones. Mounted occurrence records are
  live projections, and when an emission cap is hit the loss is reported
  in data rather than silently truncated. Freehand adds evidence records
  and loss markers, not another observability product.

  Normative owner: `docs/design/freehand/decisions/`
  `D020-tool-evidence-retention-and-warning-policy.md`, with the
  scope/basis/completeness/loss vocabulary from
  `D012-declared-reads-and-evidence-levels.md`."
  (:require [clojure.string :as str]
            [re-frame.freehand.descriptor :as descriptor]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The version
;; ---------------------------------------------------------------------------

(def schema
  "The schema version every record carries.

  A consumer validates this FIRST and refuses a record it does not
  recognise, exactly as a structural tree consumer validates
  `:rf.ui/tree-version`. Versioning an evidence surface is not ceremony:
  the alternative is tools inferring the shape from the fields that happen
  to be present, which makes every field an accidental ABI."
  :re-frame.freehand.evidence/v1)

;; ---------------------------------------------------------------------------
;; The vocabularies — closed, because an open one is not a claim
;; ---------------------------------------------------------------------------

(def basis-kinds
  "How a projection knows what it says. D012's four, and no fifth.

  Closed because a basis is what a reader uses to decide how far to trust
  the projection, and a vocabulary anyone may extend cannot carry that
  weight."
  {:static-proof
   "proved from what the compiler can see — complete for compiler-visible sites"

   :declaration
   "asserted by the author — proof only where something enforces the assertion"

   :observation
   "seen happening — complete for the run or corpus it names, never for all runs"

   :opaque
   "deliberately unavailable: work behind a boundary this substrate does not own"})

(def scopes
  "The NAMED scopes, beside the map-shaped ones [[scope?]] also accepts."
  {:possible-sites
   "every site the grammar can see in one declaration, active or not"

   :private-host-work
   "work behind a host boundary — named so its absence is a fact rather than a gap"})

(def loss-reasons
  "Why a projection dropped something. Closed, and each reason is a
  different remedy for the reader: a cap is a knob, a missing analysis is
  a lowering choice, an opaque host is neither."
  {:cap
   "an emission cap was reached and entries beyond it were not carried"

   :no-static-analysis
   "the declaration is interpreted, so there is no analysis to report"

   :host-opaque
   "the host would not say, and no amount of asking would change that"})

(def lowerings
  "The two ways a declaration runs. A record names one, because the whole
  point of one schema is that a tool does NOT have to care — and it can
  only afford not to care if the difference is stated rather than
  inferred."
  #{:interpreted :compiled})

(def unknown
  "The explicit `:dropped` value for a projection that knows it lost
  something and cannot say how much.

  A named value rather than `nil` or an absent key, because both of those
  read as \"none\" at a glance and one of them reads as \"none\" to
  `(get loss :dropped)` as well."
  :unknown)

;; ---------------------------------------------------------------------------
;; The predicates behind the ledger
;; ---------------------------------------------------------------------------

(defn scope?
  "True when `s` is a scope a projection may claim: one of [[scopes]], or
  a NON-EMPTY map naming the run it covers — `{:committed-generation 812}`,
  `{:corpus :component-gallery :runs 240}`.

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
  scoped to one corpus is incomplete as a claim about all runs while
  having dropped nothing at all."
  [l]
  (or (nil? l)
      (and (map? l)
           (contains? loss-reasons (:reason l))
           (let [d (get l :dropped ::absent)]
             (or (nat-int? d) (= unknown d))))))

(defn occurrence?
  "True when `o` is a runtime occurrence key — a map naming its `:parent`
  occurrence (nil at a root) and the `:key` distinguishing it among that
  parent's children.

  Both keys must be PRESENT and either may be nil, because a root has no
  parent and an unkeyed only-child has no key, and those are facts the
  record states rather than omits."
  [o]
  (and (map? o) (contains? o :parent) (contains? o :key)))

(defn- non-blank? [v] (and (string? v) (not (str/blank? v))))

;; ---------------------------------------------------------------------------
;; The projection ledger
;; ---------------------------------------------------------------------------

(def projection-fields
  "The four axes, as data.

  A vector rather than a `when-not` cascade for the same reason
  `re-frame.freehand.bench.provenance` uses one: the suite that proves
  every axis is required ENUMERATES this, so a fifth axis added later is
  covered without anyone remembering to cover it, and a reader can check
  the whole obligation against D012 on one screen."
  [{:key      :scope
    :names    "the scope"
    :valid?   scope?
    :expected (str "one of " (pr-str (vec (sort (keys scopes))))
                   ", or a non-empty map naming the generation, corpus or run this covers")}

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

(def projection-invariants
  "The cross-field rules. Each one is a shape in which a projection would
  claim more than it knows, stated as a predicate so it cannot be
  forgotten at a call site."
  [{:holds?  (fn [{:keys [complete? loss]}] (not (and complete? (some? loss))))
    :message (str "it claims completeness and also reports loss. Any cap sets "
                  ":complete? false and records explicit loss — a truncated roster "
                  "reported as total is the one failure this schema exists to prevent")}

   {:holds?  (fn [{:keys [basis complete?]}] (not (and (= :opaque basis) complete?)))
    :message (str "it claims completeness on an :opaque basis. A projection that "
                  "cannot see inside a boundary cannot be complete about what is "
                  "inside it — name the absence, do not claim to have surveyed it")}

   {:holds?  (fn [{:keys [basis complete?] :as p}]
               (not (and (= :declaration basis) complete? (not (contains? p :enforced-by)))))
    :message (str "it claims completeness on a :declaration basis without naming "
                  "what enforces the declaration. An unenforced annotation is a "
                  "claim, not proof — add :enforced-by, or say :complete? false")}])

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
       ". Every evidence projection states its scope, basis, completeness relative to "
       "that scope, and loss; \"unknown\" must not look like \"none\" (D012, D020)."))

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
                      {:re-frame.freehand.evidence/defect :incomplete-projection :defects ds})))
    p))

(defn capped
  "Answer `p` with `k`'s collection truncated to `limit` and the loss
  stated — or `p` unchanged, and still validated, when nothing was
  dropped.

  This is the honest half made cheap. A cap enforced at the call site
  is a cap someone will one day apply without also flipping
  `:complete?`; applied here it cannot be, because the truncation and the
  loss account are the same expression."
  [p k limit]
  (let [xs (get p k)
        n  (count xs)]
    (if (<= n limit)
      (projection p)
      (projection (assoc p
                         k         (vec (take limit xs))
                         :complete? false
                         :loss      {:reason :cap :dropped (- n limit)})))))

;; ---------------------------------------------------------------------------
;; Diagnostics — stable id, a coordinate, and a way out
;; ---------------------------------------------------------------------------

(defn- source-coord?
  [s]
  (and (map? s) (non-blank? (:file s)) (pos-int? (:line s))))

(def diagnostic-fields
  "What a diagnostic owes its reader, as data.

  Three things, and the third is the one that is usually missing. An id
  makes the finding addressable across releases; a coordinate makes it
  reachable; a recovery makes it actionable. A diagnostic with the first
  two is a search result."
  [{:key      :id
    :names    "the stable diagnostic id"
    :valid?   qualified-keyword?
    :expected "a namespaced keyword that does not change between releases, so a suppression, a lint config and a search still resolve"}

   {:key      :source
    :names    "the source coordinate"
    :valid?   source-coord?
    :expected "{:file \"…\" :line n} pointing at the form the finding is about"}

   {:key      :recovery
    :names    "the recovery"
    :valid?   non-blank?
    :expected "one sentence saying what to do instead — a finding that cannot say that is a complaint"}])

(defn diagnostic
  "Answer `d` when it carries a stable id, a source coordinate and a
  recovery, otherwise throw naming what it lacks."
  [d]
  (let [ds (into []
                 (keep (fn [{:keys [key names valid? expected]}]
                         (let [v (get d key ::absent)]
                           (cond
                             (= ::absent v) {:defect :missing :key key :names names :expected expected}
                             (not (valid? v)) {:defect :invalid :key key :names names
                                               :expected expected :observed v}))))
                 diagnostic-fields)]
    (when (seq ds)
      (throw (ex-info (str "This diagnostic cannot be emitted: " (count ds)
                           (if (= 1 (count ds)) " problem" " problems")
                           " — " (str/join "; " (map defect-line ds))
                           ". A diagnostic is a stable id, a coordinate, and a way out.")
                      {:re-frame.freehand.evidence/defect :incomplete-diagnostic :defects ds})))
    d))

;; ---------------------------------------------------------------------------
;; The record
;; ---------------------------------------------------------------------------

(def record-fields
  "Everything an evidence record names before it may be emitted."
  [{:key      :schema
    :names    "the schema version"
    :valid?   #(= schema %)
    :expected (str "exactly " schema " — a consumer validates this first and refuses what it does not recognise")}

   {:key      :view-id
    :names    "the authoring view"
    :valid?   qualified-keyword?
    :expected "the declaration's id — the namespace-qualified keyword the view was declared under"}

   {:key      :lowering
    :names    "the lowering"
    :valid?   #(contains? lowerings %)
    :expected (str "one of " (pr-str (vec (sort lowerings)))
                   " — one schema for both modes means the mode is stated, not inferred")}

   {:key      :occurrence
    :names    "the runtime occurrence"
    :valid?   occurrence?
    :expected "{:parent <occurrence or nil> :key <any>} — runtime identity, never a query key, a lexical site id, or a caller-supplied controller id"}

   {:key      :generation
    :names    "the generation"
    :valid?   nat-int?
    :expected "the monotonic generation this record is keyed to, so two records about one occurrence are orderable"}

   {:key      :projections
    :names    "the projections"
    :valid?   #(and (map? %) (seq %))
    :expected "a non-empty map of projection-kind to projection — a record that projects nothing is not evidence"}])

(defn record
  "Answer `r` when it is a well-formed evidence record, otherwise throw.

  Every projection under `:projections` goes through [[projection]], so
  \"every evidence record carries scope, basis, completeness and loss\" is
  a structural property of this door rather than a convention record kinds
  are asked to follow."
  [r]
  (let [ds (into []
                 (keep (fn [{:keys [key names valid? expected]}]
                         (let [v (get r key ::absent)]
                           (cond
                             (= ::absent v) {:defect :missing :key key :names names :expected expected}
                             (not (valid? v)) {:defect :invalid :key key :names names
                                               :expected expected :observed v}))))
                 record-fields)]
    (when (seq ds)
      (throw (ex-info (str "This evidence record cannot be emitted: " (count ds)
                           (if (= 1 (count ds)) " field is" " fields are")
                           " missing or malformed — " (str/join "; " (map defect-line ds))
                           ". One versioned, occurrence-keyed schema (D020).")
                      {:re-frame.freehand.evidence/defect :incomplete-record :defects ds})))
    (doseq [[kind p] (:projections r)]
      (try
        (projection p)
        (catch #?(:clj Exception :cljs :default) e
          (throw (ex-info (str "The " kind " projection of this evidence record cannot be "
                               "emitted: " #?(:clj (.getMessage ^Exception e) :cljs (.-message e)))
                          {:re-frame.freehand.evidence/defect :incomplete-projection
                           :projection         kind
                           :view-id            (:view-id r)
                           :defects            (:defects (ex-data e))})))))
    r))

;; ---------------------------------------------------------------------------
;; The record kinds Freehand can state today
;; ---------------------------------------------------------------------------

(defn manifest-projection
  "What a declaration's analysis makes statically knowable, stated with
  its scope, basis, completeness and loss.

  A COMPILED declaration answers `:possible-sites` on a `:static-proof`
  basis, complete and lossless: the analyzer saw the whole body, the
  grammar is finite, and the roster is every site there can be.

  An INTERPRETED declaration answers the same scope on an `:opaque` basis,
  INCOMPLETE, and reports `{:reason :no-static-analysis :dropped :unknown}`.
  That is the shape of honesty this schema exists for. There is no analysis
  step, so the number of sites the declaration could reach is not merely
  large or uncounted — it is unknowable without running it, and a
  projection that answered `:loss nil` there would be saying it dropped
  nothing when what it means is that it never looked."
  [view]
  (if-let [m (descriptor/manifest view)]
    (projection {:scope     :possible-sites
                 :basis     :static-proof
                 :complete? true
                 :loss      nil
                 :manifest  m})
    (projection {:scope     :possible-sites
                 :basis     :opaque
                 :complete? false
                 :loss      {:reason :no-static-analysis :dropped unknown}
                 :manifest  nil})))

(def a11y-basis
  "The basis a static accessibility finding is emitted under, and the
  reason there is exactly one.

  `re-frame.freehand.compiler.a11y` fires only where the defect is
  PROVABLE from the literal AST the compiler already built, and goes
  silent the instant a dynamic child, a foreign component, a spread or a
  runtime-computed aria value introduces information it cannot see. So a
  finding is `:static-proof` and can be nothing else: there is no
  heuristic tier to be `:observation` about and no annotation to be
  `:declaration` about."
  :static-proof)

(defn a11y-projection
  "The static accessibility findings for one declaration, honestly scoped.

  The scope is the sites this GRAMMAR can prove, never \"accessibility\":
  the roster is complete for what static proof reaches and says nothing
  whatever about what it does not, and stating that as the scope is what
  stops an empty roster reading as a clean bill of health.

  Every finding goes through [[diagnostic]], so a finding that cannot name
  a coordinate and a recovery is refused here rather than shipped to a
  reader who then has to guess."
  [findings]
  (projection {:scope     {:provable-in :re-frame.freehand/v1}
               :basis     a11y-basis
               :complete? true
               :loss      nil
               :findings  (mapv diagnostic findings)}))

;; ---------------------------------------------------------------------------
;; Retention — the existing axis, named as data
;; ---------------------------------------------------------------------------

(def retention
  "Where evidence history lives: the existing per-frame retained-event
  ring specified by Spec 009, and nowhere else.

  Data rather than a paragraph, because the claim is checkable and the
  suite checks it. This namespace holds no atom, no ring, no cache and no
  configuration of its own; a projection is a value a caller built and
  handed to a reader. The knob is `:rf.trace/events-retained` — one
  number, per frame, overridable through
  `(rf/configure! {:trace-buffer {:events-retained N}})` — and Freehand
  adds no second one.

  There is a reason beyond tidiness. A second retention mechanism would
  be a second thing to disable in production, a second place a payload
  could survive a request, and a second answer to \"how far back does this
  go?\" that disagrees with the first."
  {:owner        :spec-009
   :mechanism    :per-frame-retained-event-ring
   :knob         :rf.trace/events-retained
   :configure-at [:trace-buffer :events-retained]})
