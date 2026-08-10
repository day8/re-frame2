(ns re-frame.hicasso.tool
  "The Hicasso TOOL-TIER reader door — the four reads Xray and the AI pair
  consume, and the only door either of them has (rf2-hic-023).

  `lanes/testing-xray.md` rules that Hicasso ships *a dev-only
  adapter-neutral evidence provider*, that it does *not expose the raw
  internal sink*, and that it does *not invent evidence the interpreted
  runtime cannot know*. Those three sentences are the whole design of
  this namespace, and the third is the hard one.

  ## Projected, never accumulated

  Every read below is a pure projection of state the runtime ALREADY
  retains, taken at the moment the tool asks:

  | What is read                            | Who owns it |
  |-----------------------------------------|-------------|
  | the read-set entry cache (`!entries`)   | [[re-frame.hicasso.impl.collector]] |
  | the cell table and its reader lists     | the same |
  | the frame-ops table (`!frame-ops`)      | [[re-frame.hicasso.impl.frames]] |
  | the retained event ring                 | Spec 009, under its one knob |

  There is **no accumulator, no occurrence index, no history store and no
  second knob**. The runtime's three tables are current state with no time
  axis; the one history any read here folds is Spec 009's ring, folded at
  READ time and kept by nobody. That is not tidiness — a second retention
  mechanism would be a second thing to disable in production, a second
  place a payload could survive a request, and a second answer to *how far
  back does this go?* that disagrees with the first
  ([[re-frame.hicasso.evidence/retention]]).

  It is also why each read can be asked when it will be honest and no
  earlier. [[read-mounted-boundaries]] and [[read-read-attribution]]
  answer about NOW and say nothing about what once was. [[read-intents]]
  and [[explain-render]] answer only as far back as the configured window
  reaches, and report the rest as loss.

  ## A boundary's identity is its READ SET, because that is what is kept

  The runtime mints no boundary identity. A registration is
  `#js {reads, notify, cells}` — the object the heap ladder prices — and
  it deliberately carries no view name, no source coordinate and no id;
  `codec/mark-boundary!` is *no registry, no map* on purpose. Two
  boundaries reading the same set are not merely similar to this runtime,
  they are INDISTINGUISHABLE: they share one read-set entry, one
  `subscribe` closure and one `getSnapshot`.

  So a boundary here is keyed by its edge set, and `:instances` counts how
  many hold it. That is the exact granularity the runtime retains — no
  finer, and no coarser — and it is what makes the mounted roster and the
  attribution roster JOIN: both derive the key from the same read-key set
  by the same total order.

  What is thereby unknown is stated, not omitted. `:view` and `:source`
  are [[re-frame.hicasso.evidence/unknown]] under an `:opaque` naming
  projection, because naming every live boundary would need either a
  registry or a field on the priced registration — a standing memory cost
  this producer will not levy for a panel's benefit.

  ## Causality is never inferred from adjacency

  Spec SN §10: *timing proximity never proves commit, paint, hidden, or
  suspended state.* [[explain-render]] therefore has a proven half and an
  uncorrelated half, and they are separate fields.

  Proven: a cell's `epoch` is re-stamped by every commit that moved its
  value, so the reads at a boundary's HIGHEST epoch are the ones that
  moved most recently — a fact, read off the table, not a guess.

  Uncorrelated: the commit seam records no cascade id, so no run in Spec
  009's window can be joined to a boundary's re-run. Candidate runs are
  offered as LEADS and the projection says `:uncorrelated` in the same
  breath. Whether React actually re-ran, retried, abandoned, or bailed the
  boundary out is React's to know and DevTools' to answer — `:host-opaque`,
  every time.

  ## Privacy

  Query vectors pass `re-frame.elision/elide-wire-value` — the SAME
  off-box egress projector the Pair MCP direct reads and Xray already use
  — carrying the read's own frame and query so a route sub's re-rooted
  declarations are applied. It fails CLOSED: a frameless or destroyed-frame
  read redacts the whole query rather than shipping it under no policy.

  **A BOUNDARY'S KEY IS A PROJECTION TOO, and that is not a detail.** The
  identity here is the read set, so a key built from raw sub-keys would
  carry every query argument a moment after the `:query` FIELD had been
  projected — and carry it further, because a key is what joins two
  rosters, prints in a boundary's label and becomes a DOM id. It shipped
  that way once and the merged-PR audit of #7789 caught it. Every exported
  key element is now a [[read-identity]]: frame, registration id, and the
  projected query. The join is unaffected — both rosters derive keys
  through the one function, so identical read sets still produce identical
  keys — while the arguments stay behind the projector on every path out
  of this namespace, including after frame destruction.

  Retained-window events are carried as an event ID and an argument
  COUNT. The event vector itself is not carried at any classification,
  because the classification model is fail-open — an event that declared
  nothing ships its arguments raw through the egress chokepoint — and a
  door that promised *no application data* while routing an undeclared
  payload through would be making the promise falsely. The suite's
  seeded-value row caught exactly that before this door shipped.

  **No read VALUE is carried by any read here, at all.** What a boundary
  read is a fact about the boundary; what it read AS is arbitrary
  application data, and an evidence surface that carried it would be a
  second egress path for user data beside the one Spec 015 governs. The
  cells hold live reactions whose deref IS that data, so this is a
  property of the projections and not of the state they read — which is
  why it has a witness of its own.

  ## DEV-ONLY

  Every read answers `nil` under `:advanced` + `goog.DEBUG=false`. Nothing
  in `re-frame.hicasso` requires this namespace, so a production
  application never loads it at all; rf2-hic-024 owns the sentinel-based
  proof.

  Normative owner: `docs/design/hicasso/product/specification.md` §10,
  `docs/design/hicasso/product/lanes/testing-xray.md` §Evidence contract."
  (:require [re-frame.elision :as elision]
            [re-frame.hicasso.evidence :as evidence]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.generation :as generation]
            [re-frame.interop :as interop]
            [re-frame.trace.tooling :as trace-tooling]))

;; ---------------------------------------------------------------------------
;; Deterministic order — the precondition of a byte-for-byte contract
;; ---------------------------------------------------------------------------

(defn- ordered
  "`xs` in ONE total order, whatever a hash map's iteration happened to
  be.

  Every roster below is sorted through here, and that is load-bearing
  rather than cosmetic: two consumers reading the same runtime state must
  receive the same BYTES, and a projection whose row order came from a
  `PersistentHashMap`'s seq is a projection that can differ between two
  calls that saw one runtime. `pr-str` because the things being ordered
  are sub-keys and key vectors — values whose printed form is total,
  stable and independent of when they were interned."
  [key-fn xs]
  (vec (sort-by (fn [x] (pr-str (key-fn x))) xs)))

;; ---------------------------------------------------------------------------
;; Privacy — the existing projectors, at this door
;; ---------------------------------------------------------------------------

(defn- projected-query
  "`query-v` through `re-frame.elision/elide-wire-value`, carrying the
  read's own frame and query.

  The frame is passed EXPLICITLY rather than left to the ambient carried
  scope, because a tool read happens outside any dispatch: the ambient
  frame at a panel's render is Xray's own, not the inspected
  application's, and eliding an application's query under the tool's
  policy would apply the wrong one. Passing the read's frame also arms the
  fail-closed arm — an unresolvable or destroyed frame redacts the whole
  query rather than walking it under no policy.

  `:query-v` seeds the route-sub egress path so a route read's re-rooted
  absolute declarations match the bare query, exactly as the Pair MCP
  direct reads do."
  [frame-id query-v]
  (elision/elide-wire-value query-v {:frame frame-id :query-v query-v}))

(defn- sub-id-of
  "The registration id a query names.

  Carried separately from the query on every row because it is the axis
  Spec 009's retained stream keys sub recomputes on, and therefore the
  only spelling a window join can be made over. It is a registration id,
  not application data, so it rides unprojected while the query VECTOR —
  which can carry arguments — does not."
  [query-v]
  (cond (keyword? query-v)                    query-v
        (and (vector? query-v) (seq query-v)) (nth query-v 0)
        :else                                 evidence/unknown))

(defn- read-identity
  "One read edge's EXPORTED identity: `[frame-id sub-id projected-query]`.

  Three fields rather than the raw sub-key's two, and the third is
  projected. The `sub-id` is carried explicitly because it is the one
  spelling that survives redaction: when a frame's policy elides a query
  whole, `[:cart/item \"…\"]` and `[:user/token \"…\"]` both project to the
  same sentinel, and without the registration id beside it a reader —
  and a boundary key — could no longer tell those two edges apart at all.

  This is therefore as fine an identity as the egress policy allows and
  no finer, which is the honest ceiling: where an application has
  declared its arguments sensitive, two boundaries differing only in
  those arguments ARE one row here, and `:read-orders` counts what
  folded in."
  [sub-key]
  (let [frame-id (nth sub-key 0)
        query-v  (nth sub-key 1)]
    [frame-id (sub-id-of query-v) (projected-query frame-id query-v)]))

(defn- boundary-key
  "The tool-tier identity of a boundary: its read-key set PROJECTED, in
  [[ordered]]'s order.

  Derived identically from a registration's `.-reads` and from a read-set
  entry's `.-set`, which is what makes [[read-mounted-boundaries]] and
  [[read-read-attribution]] join. See the namespace docstring for why the
  edge set is the identity the runtime actually retains.

  **Every element is a [[read-identity]], never a raw sub-key.** The raw
  sub-key carries the application's own query VECTOR, arguments and all,
  so a key built from it would be an egress path for exactly the data
  [[projected-query]] exists to project — and a worse one, because a key
  is also what a panel prints in a label and hashes into a DOM id. The
  join survives because both sides derive the key by this one function:
  identical raw sets project to identical keys, and the projection is a
  pure function of the query and its frame."
  [read-key-set]
  (ordered identity (map read-identity read-key-set)))

(defn- read-row
  "One read edge, as a row — the query and where it lives, and NEVER what
  it returned.

  Takes the RAW sub-key because the cell table is keyed by it: the epoch
  lookup is an internal read against live runtime state, and nothing raw
  survives into the row this builds."
  [sub-key]
  (let [frame-id (nth sub-key 0)
        query-v  (nth sub-key 1)]
    {:sub-id   (sub-id-of query-v)
     :query    (projected-query frame-id query-v)
     :frame-id frame-id
     :epoch    (if-some [^js c (get @collector/!cells sub-key)]
                 (.-epoch c)
                 evidence/unknown)}))

;; ---------------------------------------------------------------------------
;; The two projections that name what is NOT known
;; ---------------------------------------------------------------------------

(def ^:private naming-projection
  "Why every boundary row states `:view` and `:source` as
  [[re-frame.hicasso.evidence/unknown]].

  A projection of its own rather than a comment, so the absence is DATA a
  panel can render and a schema check can enforce. Its basis is `:opaque`
  and it is `:complete? false` — the substrate retains no boundary→view
  association and never will, so this is not a gap waiting to be closed."
  {:scope     :mounted-boundaries
   :basis     :opaque
   :complete? false
   :loss      {:reason :opaque :dropped evidence/unknown}
   :view      evidence/unknown
   :source    evidence/unknown
   :why       (str "The runtime mints no boundary identity and keeps no view "
                   "registry: a registration is the read set, React's notifier "
                   "and the acquired cells, and nothing else. Naming every live "
                   "boundary would need a registry or a field on that object — a "
                   "standing memory cost this producer does not levy. A boundary "
                   "is therefore identified by the edge set it holds.")})

(def ^:private host-projection
  "What React owns and does not publish.

  Commit, paint, retry, abandonment, StrictMode duplication and Activity
  hide/reveal are all decided above this runtime, and a render measure is
  none of them. Stated once, on every envelope that would otherwise invite
  a reader to infer them from an epoch.

  `:visibility` and `:hidden-retained` are here because the real-React
  lifecycle witness established that this door's tables CANNOT tell three
  states apart, and a reader would otherwise assume they can (rf2-hic-023,
  merged-PR audit #7792):

  - an Activity-hidden subtree that has released its reads and an
    UNMOUNTED one are the same census row — namely, no row at all — until
    a later 0-to-1 re-subscribe reveals the retention retrospectively;
  - a Suspense-fallback-hidden subtree stays SUBSCRIBED, so it is present
    in every roster here while absent from the screen.

  Both are stated as [[re-frame.hicasso.evidence/unknown]] on a
  `:host-opaque` basis rather than guessed at. The alternative — inferring
  hidden-retained from an empty census, or labelling a subscribed row
  visible — is the exact fabrication the schema exists to refuse."
  {:scope           :host-private
   :basis           :host-opaque
   :complete?       false
   :loss            {:reason :host-opaque :dropped evidence/unknown}
   :commit          evidence/unknown
   :paint           evidence/unknown
   :attempt-outcome evidence/unknown
   :visibility      evidence/unknown
   :hidden-retained evidence/unknown
   :why             (str "Whether a notified boundary re-ran, retried, was "
                         "abandoned, was bailed out by its memo comparator, or "
                         "committed and painted is React's to know. So is "
                         "whether it is on screen: an Activity-hidden subtree "
                         "that released its reads is indistinguishable here "
                         "from an unmounted one, and a Suspense-fallback-hidden "
                         "subtree stays subscribed and so still appears in "
                         "every roster. These rosters are about SUBSCRIPTION, "
                         "not visibility. React DevTools and the browser "
                         "performance tools are the authority; this producer "
                         "does not restate them from timing adjacency.")})

;; ---------------------------------------------------------------------------
;; Read 1 — mounted boundaries
;; ---------------------------------------------------------------------------

(defn- entry-rows
  "One row per DISTINCT edge set among the committed read-set entries.

  `refs` is the count of boundaries that have subscribed against an entry
  and not yet unsubscribed — incremented in the entry's `subscribe` and
  decremented in its cleanup, which React calls at commit and at teardown
  respectively. So `refs > 0` IS *mounted*, and summing it is a complete
  census: a boundary whose body read NOTHING still mints and claims an
  entry (an empty one), so it is counted here where the cell table cannot
  see it at all.

  Two entries whose key ARRAYS differ only in order are one row, because
  their edge sets are equal and the edge set is this door's identity. The
  runtime distinguishes them — the entry compare is ordered — so the row
  says `:read-orders` when it has collapsed more than one.

  Rows are grouped by the PROJECTED [[boundary-key]], so `:read-orders`
  also counts entries folded together because an application declared the
  arguments that told them apart sensitive. That is the right place for
  that collapse to happen: the alternative is a census keyed on data the
  door has promised not to carry, or two rows sharing one exported
  identity — which would give a panel duplicate DOM ids and a consumer an
  ambiguous join."
  []
  (let [live (for [[_ bucket] @collector/!entries
                   ^js entry  bucket
                   :when      (pos? (.-refs entry))]
               entry)]
    (->> live
         (group-by (fn [^js entry] (boundary-key (.-set entry))))
         (map (fn [[bkey entries]]
                ;; The RAW sub-keys stay here and go no further: `read-row`
                ;; needs them to reach the cell table, and every field it
                ;; answers with is projected.
                (let [raw-keys (ordered identity
                                        (into #{} (mapcat (fn [^js e] (seq (.-set e)))) entries))]
                  {:boundary    {:parent nil :key bkey}
                   :view        evidence/unknown
                   :source      evidence/unknown
                   :instances   (reduce + 0 (map (fn [^js e] (.-refs e)) entries))
                   :read-orders (count entries)
                   :frame       (let [fs (into #{} (map #(nth % 0)) bkey)]
                                  (if (= 1 (count fs)) (first fs) evidence/unknown))
                   :reads       (mapv read-row raw-keys)})))
         (ordered (comp :key :boundary)))))

(defn read-mounted-boundaries
  "Every Hicasso boundary MOUNTED RIGHT NOW, inside the projection that
  says how far to trust the roster. `nil` in a production build.

      (tool/read-mounted-boundaries)
      ;; => {:schema     :re-frame.hicasso.evidence/v1
      ;;     :producer   :re-frame/hicasso
      ;;     :read       :mounted-boundaries
      ;;     :scope      :mounted-boundaries
      ;;     :basis      :observation
      ;;     :complete?  true
      ;;     :loss       nil
      ;;     :boundaries [{:boundary  {:parent nil :key [[:app/main :todo [:todo 7]]]}
      ;;                   :view      :unknown
      ;;                   :source    :unknown
      ;;                   :instances 3
      ;;                   :read-orders 1
      ;;                   :frame     :app/main
      ;;                   :reads     [{:sub-id :todo :query [:todo 7]
      ;;                                :frame-id :app/main :epoch 4}]}]
      ;;     :generation 12
      ;;     :naming     {…:basis :opaque   :view :unknown :source :unknown}
      ;;     :host       {…:basis :host-opaque :commit :unknown :paint :unknown}}

  `:complete? true` is a claim about UNDER-reporting and it is exact: the
  scope is *boundaries mounted now*, and the read-set entry cache holds a
  claimed entry for every one of them — including a boundary whose body
  read nothing, which retains no cell and is invisible to every other
  table. A rendered-but-not-yet-committed boundary is outside the scope
  rather than missing from the roster; its entry has no reference yet.

  An EMPTY `:boundaries` says exactly one thing: **no boundary holds a
  live read edge right now.** The entry cache is authoritative about that
  and this read is complete for it. What the empty does NOT establish is
  that nothing is retained above — an Activity-hidden subtree that has
  released its reads leaves the same empty census as an unmounted one, and
  only a later re-subscribe distinguishes them, retrospectively (audit
  #7792). Read the other way round, a row here is not proof the boundary
  is on SCREEN: a Suspense-fallback-hidden subtree stays subscribed and
  stays in this roster.

  Everything this read does not know is in `:naming` and `:host`, which
  are projections of their own with their own bases and their own losses —
  so a reader can never mistake *nothing is mounted* for *nothing could be
  seen*, nor *subscribed* for *visible*."
  []
  (when interop/debug-enabled?
    (evidence/envelope
      {:schema     evidence/schema
       :producer   evidence/producer
       :read       :mounted-boundaries
       :scope      :mounted-boundaries
       :basis      :observation
       :complete?  true
       :loss       nil
       :boundaries (entry-rows)
       :generation (generation/generation)
       :naming     (evidence/projection naming-projection)
       :host       (evidence/projection host-projection)})))

;; ---------------------------------------------------------------------------
;; Read 2 — read attribution (sub → boundary)
;; ---------------------------------------------------------------------------

(defn- edge-row
  "One subscription's reverse edge: who reads it, and how many.

  The reader list on the cell IS the reverse edge — one slot per reading
  boundary, which is simultaneously that boundary's reference to the cell
  (rf2-dabt3) — so this roster is not derived, computed or cached; it is
  the table itself, read out. `:fan-out` is the slot count and
  `:readers` the distinct edge sets holding them, so a key read by three
  boundaries of one shape reports `:fan-out 3` with one reader."
  [sub-key ^js cell]
  (let [readers (.-readers cell)
        query-v (nth sub-key 1)]
    {:sub-id   (sub-id-of query-v)
     :query    (projected-query (nth sub-key 0) query-v)
     :frame-id (.-frameKw cell)
     :epoch    (.-epoch cell)
     :fan-out  (alength readers)
     :readers  (ordered identity
                        (into #{}
                              (map (fn [^js reg] {:parent nil :key (boundary-key (.-reads reg))}))
                              readers))}))

(defn read-read-attribution
  "Which boundaries read each subscription — the reverse edge, exactly.
  `nil` in a production build.

      (tool/read-read-attribution)
      ;; => {:schema … :producer … :read :read-attribution
      ;;     :scope :read-edges :basis :observation :complete? true :loss nil
      ;;     :edges [{:sub-id :todo :query [:todo 7] :frame-id :app/main
      ;;              :epoch 4 :fan-out 3
      ;;              :readers [{:parent nil :key [[:app/main :todo [:todo 7]]]}]}]
      ;;     :host  {…}}

  THIS IS THE ONE READ THAT IS EXACT WITHOUT QUALIFICATION. The others
  fold a window or name what they cannot see; this one prints a table.
  Every cell's `readers` array is the key's reverse edge, maintained as
  one slot per reading boundary by the same commit and cleanup that
  acquire and release the reference — so there is no derivation to be
  stale, and a boundary that appears here demonstrably holds that key
  right now.

  `:readers` are the SAME keys [[read-mounted-boundaries]] states, derived
  from the same read-key sets by the same total order, so the two rosters
  join without a correlation step. A key nothing holds has no cell and is
  therefore absent — correctly: it is not a subscription with zero
  readers, it is a subscription this runtime is not holding."
  []
  (when interop/debug-enabled?
    (evidence/envelope
      {:schema    evidence/schema
       :producer  evidence/producer
       :read      :read-attribution
       :scope     :read-edges
       :basis     :observation
       :complete? true
       :loss      nil
       ;; Ordered by SUB-KEY before the row is built, never by a field on
       ;; the row: the sub-key carries the raw, unprojected query vector,
       ;; and a sort key that had to ride on the row to be sorted by would
       ;; be a second egress path for the arguments `projected-query`
       ;; exists to project.
       :edges     (mapv (fn [[sub-key cell]] (edge-row sub-key cell))
                        (ordered key @collector/!cells))
       :host      (evidence/projection host-projection)})))

;; ---------------------------------------------------------------------------
;; Read 3 — the intent stream
;; ---------------------------------------------------------------------------

(defn- hicasso-frames
  "The frames this runtime dispatches through — the frame-ops table's
  keys, which is where every Hicasso intent's dispatch was captured."
  []
  (vec (sort-by pr-str (keys @frames/!frame-ops))))

(defn- intent-row
  "One retained run, as an intent row: WHICH event, and how many arguments
  it carried — never the arguments themselves.

  **This is the one place the door was measurably wrong before its own
  witness ran, and the correction is worth stating.** Carrying the
  dispatched VECTOR through
  `classification/redact-event-by-registration` — the single event-vector
  egress chokepoint, and the obvious thing to reach for — leaks. The
  chokepoint applies the classification the event's REGISTRATION declared,
  and EP-0025's model is fail-open: an event that declared nothing ships
  its arguments raw. So a seeded secret dispatched under an unclassified
  event id reached this envelope verbatim, and the suite's seeded-value
  row caught it.

  An id and an arity are enough for the question this read answers —
  *what was dispatched, in what order* — and they make the door's promise
  uniform and absolute: **no read here carries application data, under any
  classification, declared or not.** A developer who needs the arguments
  has the Trace surface, which is where Spec 015 governs that egress and
  where a reader knows they are looking at application data.

  `:sub-ids` is what that run recomputed, which is the axis an
  [[explain-render]] candidate is matched on.

  `:frames` is a SET rather than the one frame whose ring this bundle was
  drawn from — see [[intent-rows]] for why one dispatch can surface in
  more than one ring, and why answering per ring would print one event
  twice."
  [frame-id bundle]
  (let [event (:event bundle)]
    {:frames      #{frame-id}
     :dispatch-id (:dispatch-id bundle)
     :event-id    (if (vector? event) (nth event 0) evidence/unknown)
     :arg-count   (if (vector? event) (dec (count event)) evidence/unknown)
     :sub-ids     (into #{} (keep #(get-in % [:tags :rf.sub/id])) (:subs bundle))}))

(defn- merge-fragments
  "Fold the ring fragments of ONE dispatch into one row.

  Spec 009's rings are per frame, and a dispatch that touched two frames
  is captured in both — so the same `:dispatch-id` can arrive twice. Two
  rows would print one user action as two events; keeping only the first
  would drop half of what it recomputed. Merging says the true thing:
  one dispatch, the frames it reached, everything it recomputed."
  [rows]
  (-> (reduce (fn [acc row]
                (update acc (:dispatch-id row)
                        (fn [seen]
                          (if seen
                            (-> seen
                                (update :frames into (:frames row))
                                (update :sub-ids into (:sub-ids row)))
                            row))))
              {}
              rows)
      vals))

(defn- intent-rows
  "The retained window as ONE stream: every fragment merged by dispatch,
  ordered by `:dispatch-id`, oldest first.

  The id is allocated process-monotonically at queue time
  (`re-frame.router`), so it IS the dispatch order across every frame —
  which is what lets this read promise order and mean it. The previous
  shape concatenated whole per-frame rings in frame-id ORDER, so with two
  frames live the stream claimed a sequence that never happened; frame-id
  order is alphabetical, not temporal.

  A row whose bundle carries no id cannot be joined or placed, so it
  keeps its own identity and sorts last rather than merging with every
  other such row into one fictitious event."
  [windows frame-ids]
  (let [rows (into []
                   (mapcat (fn [fid]
                             (map-indexed
                               (fn [i bundle]
                                 (let [row (intent-row fid bundle)]
                                   (cond-> row
                                     (nil? (:dispatch-id row))
                                     (assoc :dispatch-id [::unjoinable fid i]))))
                               (get windows fid))))
                   frame-ids)]
    (->> (merge-fragments rows)
         (sort-by (fn [{:keys [dispatch-id]}]
                    (if (number? dispatch-id) dispatch-id js/Number.MAX_SAFE_INTEGER)))
         (mapv (fn [row]
                 (-> row
                     (update :frames #(vec (sort-by pr-str %)))
                     (update :sub-ids #(vec (sort-by pr-str %)))
                     (update :dispatch-id #(if (number? %) % evidence/unknown))))))))

(defn read-intents
  "What was dispatched inside Spec 009's RETAINED WINDOW, oldest first,
  for every frame this runtime dispatches through. `nil` in a production
  build.

      (tool/read-intents)
      ;; => {:schema … :producer … :read :intents
      ;;     :scope {:frames [:app/main] :retained-runs 12}
      ;;     :basis :observation :complete? false
      ;;     :loss  {:reason :cap :dropped :unknown}
      ;;     :intents [{:frames [:app/main] :dispatch-id 41
      ;;                :event-id :todo/toggle :arg-count 1
      ;;                :sub-ids [:todo]}]
      ;;     :origin  {…:basis :opaque :intent-origin :unknown}}

  **ORDER IS THE POINT, so it is reconstructed rather than assumed.**
  Spec 009's rings are per frame; the stream is one, ordered by the
  process-monotonic `:dispatch-id` the router allocates at queue time, and
  fragments of one dispatch captured in two rings are merged into the one
  row they describe. See [[intent-rows]].

  NOTHING IS RETAINED TO ANSWER THIS. The stream is the per-frame
  retained-event ring Spec 009 already owns, under its single knob
  (`:rf.trace/events-retained`), folded here and kept by nobody. Hicasso
  adds no second window: an intent is an ordinary dispatch through the
  frame's captured bundle, so it is already in the ring the moment it
  fires, and a parallel intent buffer would be a second thing to disable
  in production for no fact the first does not have.

  **The window is ALWAYS a cap, so this read is never `:complete? true`.**
  A ring of size N cannot say what fell off it, and a ring of size 0 —
  retention disabled, which is the default in some hosts — cannot say
  whether anything was dispatched at all. Both report
  `{:reason :cap :dropped :unknown}`, and an empty `:intents` under that
  loss reads as *the window is empty*, never as *nothing was dispatched*.

  `:origin` is the second thing this read does not know. Every UI-driven
  event in a Hicasso application arrives through a lowered intent, but the
  ring records a dispatch and not what lowered it, so whether a given run
  began at markup or at a timer is `:opaque` — offered as a projection so
  a panel renders the qualifier rather than implying it away."
  []
  (when interop/debug-enabled?
    (let [frame-ids (hicasso-frames)
          windows   (into {} (map (fn [fid] [fid (trace-tooling/trace-buffer fid)])) frame-ids)
          rows      (intent-rows windows frame-ids)]
      (evidence/envelope
        {:schema    evidence/schema
         :producer  evidence/producer
         :read      :intents
         :scope     {:frames        frame-ids
                     :retained-runs (count rows)}
         :basis     :observation
         :complete? false
         :loss      {:reason :cap :dropped evidence/unknown}
         :intents   rows
         :origin    (evidence/projection
                      {:scope         {:frames frame-ids}
                       :basis         :opaque
                       :complete?     false
                       :loss          {:reason :opaque :dropped evidence/unknown}
                       :intent-origin evidence/unknown
                       :why           (str "The ring records that an event was "
                                            "dispatched, not what lowered it. A "
                                            "Hicasso intent and a timer reach it by "
                                            "the same door, so attributing a run to "
                                            "markup would be an inference this "
                                            "producer cannot support.")})}))))

;; ---------------------------------------------------------------------------
;; Read 4 — explain-render
;; ---------------------------------------------------------------------------

(defn- explanation
  "One boundary's explanation: the proven half, then the uncorrelated
  half, in that order and never blended.

  PROVEN. Every commit re-stamps each moved cell's epoch to at least one
  above its previous stamp, floored at the frame's commit basis, so a
  boundary's reads at its own MAXIMUM epoch are the ones whose values
  moved most recently. `:latest-reads` is that set, read off the table.
  `:snapshot` is the sum React itself compares — the same number
  `getSnapshot` answers — so a reader can see the value a bail-out would
  have been decided on.

  UNCORRELATED. The commit seam records no cascade id. Nothing in the
  retained window can therefore be JOINED to this boundary's re-run, and
  `:cause` is [[re-frame.hicasso.evidence/unknown]] every time — not
  occasionally, not when the ring is short, but structurally.
  `:candidates` are the runs that recomputed a subscription this boundary
  reads, offered as LEADS: presenting a lead as a cause is exactly the
  fabrication `:uncorrelated` exists to refuse.

  **The two loss reasons are DIFFERENT and a reader can drive between
  them.** With runs retained, the lead search really ran and the only
  thing missing is the join — `:uncorrelated`, and `:candidates` is a
  survey result that may honestly be `[]`. With the window empty —
  retention off, or nothing dispatched since the ring was released — no
  search happened, so `:candidates` states
  [[re-frame.hicasso.evidence/unknown]] and the reason is `:cap`. An `[]`
  in that state would be the fail-open shape this schema refuses.

  **BOTH HALVES ARE SCOPED TO THE BOUNDARY'S OWN FRAMES.** The window
  searched is the rings of the frames this boundary actually reads from,
  and a candidate must match a read on `[frame-id sub-id]` — not on the
  sub-id alone. Counting runs globally would let activity in frame B make
  a frame-A row claim its window was searched when frame A's ring is
  empty, which converts a `:cap` into an `:uncorrelated` and tells the
  reader a survey happened that did not. Matching on the sub-id alone
  would then offer B's runs as A's leads for no better reason than that
  two frames registered the same sub id — the definition of a lead
  fabricated from a coincidence (audit #7789).

  A read-free boundary reads from no frame, so it searches nothing and
  reports `:cap`. That is exact: there is no window in which its cause
  could be found, because it holds nothing that could have moved."
  [windows candidates-by-frame-sub row]
  (let [reads   (:reads row)
        frames  (into #{} (map :frame-id) reads)
        runs    (reduce + 0 (map #(count (get windows %)) frames))
        epochs  (into [] (keep #(when (number? (:epoch %)) (:epoch %))) reads)
        peak    (when (seq epochs) (reduce max epochs))
        looked? (pos? runs)
        leads   (ordered :dispatch-id
                         (into #{}
                               (mapcat (fn [{:keys [frame-id sub-id]}]
                                         (get candidates-by-frame-sub [frame-id sub-id])))
                               reads))]
    (evidence/projection
      {:scope        {:boundary (:key (:boundary row))
                      :frames   (vec (sort-by pr-str frames))
                      :retained-runs runs}
       :basis        :observation
       :complete?    false
       :loss         (if looked?
                       {:reason :uncorrelated :dropped evidence/unknown}
                       {:reason :cap :dropped evidence/unknown})
       :boundary     (:boundary row)
       :frame        (:frame row)
       :instances    (:instances row)
       :snapshot     (if (seq epochs) (reduce + epochs) evidence/unknown)
       :peak-epoch   (or peak evidence/unknown)
       ;; The READ IDENTITY, not the bare sub-id: `[:row 1]` and `[:row 2]`
       ;; are one sub-id and two different reads, and a Why view that
       ;; collapsed them would answer "`:row` moved" to a developer looking
       ;; at eight rows. The query is already projected on the read row, so
       ;; naming it here carries nothing new (audit #7789).
       :latest-reads (if (seq epochs)
                       (into []
                             (comp (filter #(= peak (:epoch %)))
                                   (map #(select-keys % [:sub-id :query :frame-id])))
                             reads)
                       evidence/unknown)
       :cause        evidence/unknown
       :candidates   (if looked? leads evidence/unknown)})))

(defn explain-render
  "Which reads changed, and which boundaries hold them — the honest half
  of *why did this boundary run*. `nil` in a production build.

      (tool/explain-render)
      ;; => {:schema … :producer … :read :explain-render
      ;;     :scope :mounted-boundaries :basis :observation
      ;;     :complete? false :loss {:reason :uncorrelated :dropped :unknown}
      ;;     :explanations [{:boundary {…} :frame :app/main :instances 1
      ;;                     :snapshot 9 :peak-epoch 5
      ;;                     :latest-reads [{:sub-id :todo :query [:todo 7]
      ;;                                     :frame-id :app/main}]
      ;;                     :cause :unknown
      ;;                     :candidates [{:dispatch-id 41 :event-id :todo/toggle
      ;;                                   :frame-id :app/main :sub-id :todo}]
      ;;                     :basis :observation :complete? false
      ;;                     :loss {:reason :uncorrelated :dropped :unknown}}]
      ;;     :host {…}}

  THE OUTER ENVELOPE IS INCOMPLETE ON PURPOSE, and it is the only read
  here whose incompleteness is structural rather than circumstantial. A
  bigger ring does not fix it and neither does a longer session: Hicasso's
  commit seam carries no cascade identity, so there is no id to look up
  and no join to make. Every explanation therefore states `:cause
  :unknown` with `{:reason :uncorrelated}`, and offers `:candidates` —
  the retained runs that recomputed a subscription the boundary reads —
  as leads.

  What IS proven rides beside it and is worth as much: `:latest-reads`
  names the boundary's reads whose values moved most recently, off the
  cells' own epoch stamps; `:snapshot` is the exact number React compares.
  A developer asking *which of my reads moved* gets an answer; a developer
  asking *and therefore which event caused it* gets candidates and a
  label, which is the truthful shape of that answer here.

  Whether the boundary then RAN is `:host-opaque` — see `:host`. React
  consults its own scheduled work and its memo comparator above this
  runtime, and a notification delivered is not a render performed."
  []
  (when interop/debug-enabled?
    (let [rows      (entry-rows)
          ;; Every frame that could hold a lead for one of these rows: the
          ;; frames this runtime dispatches through, PLUS any frame a
          ;; boundary reads from. A boundary reading across a frame the
          ;; frame-ops table does not name still has a window, and scoping
          ;; the search per boundary is only honest if that window is in it.
          frame-ids (vec (sort-by pr-str
                                  (into (set (hicasso-frames))
                                        (mapcat (fn [r] (map :frame-id (:reads r))))
                                        rows)))
          windows   (into {} (map (fn [fid] [fid (trace-tooling/trace-buffer fid)])) frame-ids)
          runs      (reduce + 0 (map count (vals windows)))
          ;; Keyed by [frame-id sub-id]: a run that recomputed `:todo` in
          ;; frame B is not a lead for a boundary reading `:todo` in frame
          ;; A, however alike the two ids look.
          leads     (for [fid    frame-ids
                          bundle (get windows fid)
                          sid    (keep #(get-in % [:tags :rf.sub/id]) (:subs bundle))]
                      [[fid sid] {:dispatch-id (:dispatch-id bundle)
                                  :event-id    (when (vector? (:event bundle))
                                                 (nth (:event bundle) 0))
                                  :frame-id    fid
                                  :sub-id      sid}])
          by-frame-sub (reduce (fn [m [k lead]] (update m k (fnil conj #{}) lead)) {} leads)]
      (evidence/envelope
        {:schema       evidence/schema
         :producer     evidence/producer
         :read         :explain-render
         :scope        :mounted-boundaries
         :basis        :observation
         :complete?    false
         :loss         {:reason :uncorrelated :dropped evidence/unknown}
         :explanations (mapv #(explanation windows by-frame-sub %) rows)
         :window       {:frames frame-ids :retained-runs runs}
         :host         (evidence/projection host-projection)}))))
