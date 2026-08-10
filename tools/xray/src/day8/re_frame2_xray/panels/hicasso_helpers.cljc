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
  pin is bumped in lockstep."
  :re-frame.hicasso.evidence/v1)

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
               "nothing has re-run.")}})

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
  "A testid-safe slug for `x`. Total: an unprintable value still yields a
  selectable id rather than an exception at render time."
  [x]
  (-> (str (if (keyword? x) (subs (str x) 1) (pr-str x)))
      (string/replace #"[^a-zA-Z0-9]+" "-")
      (string/replace #"^-|-$" "")
      (string/lower-case)))

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
  "A stable testid slug for a boundary key.

  Built from the sub-id AND the projected query, never the raw query: a
  testid is a string a browser assertion selects on and a screenshot
  carries, so deriving one from an application's arguments would put
  those arguments in the DOM after the schema had projected them out of
  the data. Including the sub-id keeps two wholly-redacted reads
  selectable apart."
  [{:keys [key]}]
  (if (seq key)
    (id-slug (string/join "-" (map (fn [[_f sid q]] (str (pr-str sid) "-" (pr-str q))) key)))
    "reads-nothing"))

;; ---------------------------------------------------------------------------
;; The four view projections
;; ---------------------------------------------------------------------------

(def sub-modes
  "The tab's four views, in order — the four questions Spec SN §10 says a
  developer actually asks, one sub-view each."
  [{:id :mounted     :label "Mounted"     :mnem "m"
    :asks "which boundaries are mounted, over which frames"}
   {:id :attribution :label "Reads"       :mnem "r"
    :asks "which boundaries read each subscription"}
   {:id :intents     :label "Intents"     :mnem "i"
    :asks "what was dispatched, in order, inside the retained window"}
   {:id :explain     :label "Why"         :mnem "w"
    :asks "which reads changed, and what that can and cannot prove"}])

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
  fan-out and the boundaries holding it."
  [envelope]
  (if-not (supported? envelope)
    []
    (mapv (fn [edge]
            {:sub-id   (:sub-id edge)
             :slug     (id-slug (:sub-id edge))
             :query    (:query edge)
             :frame-id (:frame-id edge)
             :epoch    (:epoch edge)
             :fan-out  (:fan-out edge)
             :readers  (mapv (fn [b] {:label (boundary-label b)
                                      :slug  (boundary-slug b)}) (:readers edge))})
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
             :slug        (id-slug (str (pr-str (:event-id i)) "-" (pr-str (:dispatch-id i))))
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
