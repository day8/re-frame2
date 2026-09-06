(ns re-frame.hicasso.tool
  "The tool-tier reader door — the four reads Xray and the AI pair consume,
  and the only door either of them has.

  Each read takes no argument and answers a `re-frame.hicasso.evidence`
  envelope projected from state the runtime already retains: the read-set
  entry cache, the cell table and its reader lists, the frame-ops table,
  and Spec 009's per-frame retained-event ring, folded at read time and
  kept by nobody. Every read answers nil under `:advanced` +
  `goog.DEBUG=false`, and nothing in `re-frame.hicasso` requires this
  namespace, so a production application never loads it.

  A boundary's identity is its read set, projected: a registration is
  `#js {reads, notify, cells}` and two boundaries reading one set share
  one entry, so a row is one distinct edge set and `:instances` counts
  who holds it. In a dev build the entry also carries the names of the
  declared views holding it — counted at React's commit and released at
  its cleanup, like the reference itself — so a row says `:views`, each
  with the source coordinate `defview` captured, and a harness body with
  no name leaves `:views` as `evidence/unknown`.

  Privacy: every query passes `re-frame.elision/elide-wire-value` with
  its own frame and query, boundary keys included, and it fails closed —
  a frameless or destroyed-frame read redacts the whole query. No read
  value and no event vector is carried at any classification; the intent
  stream carries an event id and an argument count.

  Why one door with no consumer discriminator: two consumers handed two
  shapes drift, and a roster that could be emitted without its loss
  eventually is. The contract is
  docs/design/hicasso/product/lanes/testing-xray.md §Evidence contract."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.hicasso.evidence :as rf.hicasso.evidence]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.error :as rf.hicasso.impl.error]
            [re-frame.hicasso.impl.frames :as rf.hicasso.impl.frames]
            [re-frame.hicasso.impl.generation :as rf.hicasso.impl.generation]
            [re-frame.interop :as rf.interop]
            [re-frame.trace.tooling :as rf.trace.tooling]))

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
  (vec (sort-by (fn [item] (pr-str (key-fn item))) xs)))

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
  (rf.elision/elide-wire-value query-v {:frame frame-id :query-v query-v}))

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
        :else                                 rf.hicasso.evidence/unknown))

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
  `ordered`'s order.

  Derived identically from a registration's `.-reads` and from a read-set
  entry's `.-set`, which is what makes `read-mounted-boundaries` and
  `read-read-attribution` join. See the namespace docstring for why the
  edge set is the identity the runtime actually retains.

  **Every element is a `read-identity`, never a raw sub-key.** The raw
  sub-key carries the application's own query VECTOR, arguments and all,
  so a key built from it would be an egress path for exactly the data
  `projected-query` exists to project — and a worse one, because a key
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
     :epoch    (if-some [^js cell (get @rf.hicasso.impl.collector/!cells sub-key)]
                 (.-epoch cell)
                 rf.hicasso.evidence/unknown)}))

;; ---------------------------------------------------------------------------
;; The view names — the dev-only stamp, resolved to a coordinate
;; ---------------------------------------------------------------------------

(defn- view-rows
  "`names` — the set of declared views `impl.collector/entry-views` says
  hold a read-set entry in a dev build — as a row's `:views`: one
  `{:view :source}` per declared view, sorted by name, or
  `evidence/unknown` when no named body holds the entry. `:source` is the
  coordinate `defview` handed `impl.error/declaring!`, or `:unknown` for
  a name minted outside the macro."
  [names]
  (if (seq names)
    (mapv (fn [view-name]
            {:view view-name
             :source (or (rf.hicasso.impl.error/source-of view-name) rf.hicasso.evidence/unknown)})
          (sort names))
    rf.hicasso.evidence/unknown))

(defn- views-by-read-set
  "A `js/Map` from each entry's key SET — the object a registration's
  `.-reads` shares by reference — to that entry's `:views`, so an
  attribution reader is named through the entry it came from rather than
  through a second registry."
  []
  (let [views-by-read-set-map (js/Map.)]
    (doseq [[_ bucket] @rf.hicasso.impl.collector/!entries
            ^js entry  bucket]
      (.set views-by-read-set-map
            (.-set entry)
            (view-rows (rf.hicasso.impl.collector/entry-views entry))))
    views-by-read-set-map))

;; ---------------------------------------------------------------------------
;; Read 1 — mounted boundaries
;; ---------------------------------------------------------------------------

(defn- entry-rows
  "One row per DISTINCT projected edge set among the committed read-set
  entries, in `ordered` order.

  `refs > 0` is *mounted*: the entry's `subscribe` incremented it at
  commit and its cleanup decrements it at teardown, so a boundary whose
  body read nothing is counted here where the cell table cannot see it.
  Entries whose key arrays differ only in order, or that an egress policy
  folded onto one projected key, are one row, and `:read-orders` says how
  many folded in — the alternative is two rows with one exported
  identity, which gives a panel duplicate DOM ids and a consumer an
  ambiguous join. `:views` is the union of the views holding the folded
  entries, which the same subscribe/cleanup pair that moves `refs`
  keeps."
  []
  (let [live-entries (for [[_ bucket] @rf.hicasso.impl.collector/!entries
                           ^js entry  bucket
                           :when      (pos? (.-refs entry))]
                       entry)]
    (->> live-entries
         (group-by (fn [^js entry] (boundary-key (.-set entry))))
         (map (fn [[projected-boundary-key entries]]
                ;; The RAW sub-keys stay here and go no further: `read-row`
                ;; needs them to reach the cell table, and every field it
                ;; answers with is projected.
                (let [raw-read-keys
                      (ordered identity
                               (into #{}
                                     (mapcat (fn [^js entry]
                                               (seq (.-set entry))))
                                     entries))]
                  {:boundary    {:parent nil :key projected-boundary-key}
                   :views       (view-rows (into #{} (mapcat rf.hicasso.impl.collector/entry-views) entries))
                   :instances   (reduce + 0 (map (fn [^js entry]
                                                  (.-refs entry))
                                                entries))
                   :read-orders (count entries)
                   :frame       (let [frame-ids
                                      (into #{}
                                            (map #(nth % 0))
                                            projected-boundary-key)]
                                  (if (= 1 (count frame-ids))
                                    (first frame-ids)
                                    rf.hicasso.evidence/unknown))
                   :reads       (mapv read-row raw-read-keys)})))
         (ordered (comp :key :boundary)))))

(defn read-mounted-boundaries
  "Every Hicasso boundary mounted right now, one row per distinct edge
  set. `nil` in a production build.

      (tool/read-mounted-boundaries)
      ;; => {:schema     :re-frame.hicasso.evidence/v3
      ;;     :producer   :re-frame/hicasso
      ;;     :read       :mounted-boundaries
      ;;     :complete?  true
      ;;     :loss       nil
      ;;     :boundaries [{:boundary  {:parent nil :key [[:app/main :todo [:todo 7]]]}
      ;;                   :views     [{:view \"app.views/todo-row\"
      ;;                                :source {:ns app.views :file \"…\" :line 12 :column 1}}]
      ;;                   :instances 3
      ;;                   :read-orders 1
      ;;                   :frame     :app/main
      ;;                   :reads     [{:sub-id :todo :query [:todo 7]
      ;;                                :frame-id :app/main :epoch 4}]}]
      ;;     :generation 12}

  `:complete? true` is exact about under-reporting: the entry cache holds
  a claimed entry for every committed boundary, and a rendered-but-
  uncommitted one is outside the roster rather than missing from it. An
  empty `:boundaries` says no boundary holds a live read edge, and no
  more: the census is about subscription, not the screen — an
  Activity-hidden subtree that released its reads leaves the same census
  as an unmounted one, and a Suspense-fallback-hidden subtree stays
  subscribed and stays listed. React DevTools is the authority on
  visibility."
  []
  (when rf.interop/debug-enabled?
    (rf.hicasso.evidence/envelope :mounted-boundaries true nil
                       {:boundaries (entry-rows)
                        :generation (rf.hicasso.impl.generation/generation)})))

;; ---------------------------------------------------------------------------
;; Read 2 — read attribution (sub → boundary)
;; ---------------------------------------------------------------------------

(defn- edge-row
  "One subscription's reverse edge: who reads it, and how many.

  The reader list on the cell IS the reverse edge — one slot per reading
  boundary — so `:fan-out` is the slot count and `:readers` the distinct
  edge sets holding them, each named through `views` (see
  `views-by-read-set`)."
  [views-by-read-set-map sub-key ^js cell]
  (let [readers (.-readers cell)
        query-v (nth sub-key 1)]
    {:sub-id   (sub-id-of query-v)
     :query    (projected-query (nth sub-key 0) query-v)
     :frame-id (.-frameKw cell)
     :epoch    (.-epoch cell)
     :fan-out  (alength readers)
     :readers  (ordered :key
                        (into #{}
                              (map (fn [^js registration]
                                     {:parent nil
                                      :key    (boundary-key (.-reads registration))
                                      :views  (or (.get views-by-read-set-map
                                                        (.-reads registration))
                                                  rf.hicasso.evidence/unknown)}))
                              readers))}))

(defn read-read-attribution
  "Which boundaries read each subscription — the reverse edge, exactly.
  `nil` in a production build.

      (tool/read-read-attribution)
      ;; => {:schema … :producer … :read :read-attribution
      ;;     :complete? true :loss nil
      ;;     :edges [{:sub-id :todo :query [:todo 7] :frame-id :app/main
      ;;              :epoch 4 :fan-out 3
      ;;              :readers [{:parent nil :key [[:app/main :todo [:todo 7]]]
      ;;                         :views [{:view \"app.views/todo-row\" :source {…}}]}]}]}

  Exact without qualification: every cell's `readers` array is the key's
  reverse edge, maintained by the same commit and cleanup that acquire
  and release the reference. `:readers` carry the same keys
  `read-mounted-boundaries` states, so the two rosters join. A key
  nothing holds has no cell and is absent — it is not a subscription with
  zero readers, it is one this runtime is not holding."
  []
  (when rf.interop/debug-enabled?
    (let [views-by-read-set-map (views-by-read-set)]
      (rf.hicasso.evidence/envelope :read-attribution true nil
                         ;; Ordered by SUB-KEY before the row is built, never
                         ;; by a field on the row: the sub-key carries the raw
                         ;; query vector, and a sort key that rode on the row
                         ;; would be a second egress path for its arguments.
                         {:edges (mapv (fn [[sub-key cell]]
                                        (edge-row views-by-read-set-map sub-key cell))
                                       (ordered key @rf.hicasso.impl.collector/!cells))}))))

;; ---------------------------------------------------------------------------
;; Read 3 — the intent stream
;; ---------------------------------------------------------------------------

(defn- hicasso-frames
  "The frames this runtime dispatches through — the frame-ops table's
  keys, which is where every Hicasso intent's dispatch was captured."
  []
  (vec (sort-by pr-str (keys @rf.hicasso.impl.frames/!frame-ops))))

(defn- intent-row
  "One retained run, as an intent row: WHICH event, and how many arguments
  it carried — never the arguments themselves.

  **The dispatched VECTOR is deliberately not carried through
  `classification/redact-event-by-registration` — the single event-vector
  egress chokepoint, and the obvious thing to reach for — because that
  path leaks.** The chokepoint applies the classification the event's
  REGISTRATION declared, and EP-0025's model is fail-open: an event that
  declared nothing ships its arguments raw. A seeded secret dispatched
  under an unclassified event id would reach this envelope verbatim, and
  `re-frame.hicasso.tool-reads-cljs-test/no-read-carries-a-value` pins
  that it does not.

  An id and an arity are enough for the question this read answers —
  *what was dispatched, in what order* — and they make the door's promise
  uniform and absolute: **no read here carries application data, under any
  classification, declared or not.** A developer who needs the arguments
  has the Trace surface, which is where Spec 015 governs that egress and
  where a reader knows they are looking at application data.

  `:sub-ids` is what that run recomputed, which is the axis an
  `explain-render` candidate is matched on.

  `:frames` is a SET rather than the one frame whose ring this bundle was
  drawn from — see `intent-rows` for why one dispatch can surface in
  more than one ring, and why answering per ring would print one event
  twice."
  [frame-id bundle]
  (let [event (:event bundle)]
    {:frames      #{frame-id}
     :dispatch-id (:dispatch-id bundle)
     :event-id    (if (vector? event) (nth event 0) rf.hicasso.evidence/unknown)
     :arg-count   (if (vector? event) (dec (count event)) rf.hicasso.evidence/unknown)
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
  which is what lets this read promise order and mean it. Concatenating
  whole per-frame rings would not: with two frames live the stream would
  claim a sequence that never happened, because frame-id order is
  alphabetical, not temporal.

  A row whose bundle carries no id cannot be joined or placed, so it
  keeps its own identity and sorts last rather than merging with every
  other such row into one fictitious event."
  [windows frame-ids]
  (let [rows (into []
                   (mapcat (fn [frame-id]
                             (map-indexed
                               (fn [fragment-index bundle]
                                 (let [row (intent-row frame-id bundle)]
                                   (cond-> row
                                     (nil? (:dispatch-id row))
                                     (assoc :dispatch-id
                                            [::unjoinable frame-id fragment-index]))))
                               (get windows frame-id))))
                   frame-ids)]
    (->> (merge-fragments rows)
         (sort-by (fn [{:keys [dispatch-id]}]
                    (if (number? dispatch-id) dispatch-id js/Number.MAX_SAFE_INTEGER)))
         (mapv (fn [row]
                 (-> row
                     (update :frames #(vec (sort-by pr-str %)))
                     (update :sub-ids #(vec (sort-by pr-str %)))
                     (update :dispatch-id #(if (number? %) % rf.hicasso.evidence/unknown))))))))

(defn read-intents
  "What was dispatched inside Spec 009's retained window, oldest first,
  across every frame this runtime dispatches through. `nil` in a
  production build.

      (tool/read-intents)
      ;; => {:schema … :producer … :read :intents
      ;;     :complete? false
      ;;     :loss    {:reason :cap :dropped :unknown}
      ;;     :frames  [:app/main]
      ;;     :intents [{:frames [:app/main] :dispatch-id 41
      ;;                :event-id :todo/toggle :arg-count 1
      ;;                :sub-ids [:todo]}]}

  Order is reconstructed, not assumed: the rings are per frame and the
  stream is one, ordered by the process-monotonic `:dispatch-id` with
  the fragments of one dispatch merged (see `intent-rows`). Nothing is
  retained to answer this — the ring is Spec 009's, under its one knob
  `:rf.trace/events-retained`, so the window is always a cap and this
  read is never complete: an empty `:intents` under that loss means the
  window is empty, never that nothing was dispatched. Whether a run began
  at markup or at a timer is not recorded by the ring and is not claimed
  here."
  []
  (when rf.interop/debug-enabled?
    (let [frame-ids (hicasso-frames)
          windows   (into {} (map (fn [fid] [fid (rf.trace.tooling/trace-buffer fid)])) frame-ids)]
      (rf.hicasso.evidence/envelope :intents false {:reason :cap :dropped rf.hicasso.evidence/unknown}
                         {:frames  frame-ids
                          :intents (intent-rows windows frame-ids)}))))

;; ---------------------------------------------------------------------------
;; Read 4 — explain-render
;; ---------------------------------------------------------------------------

(defn- explanation
  "One boundary's explanation: the proven half, then the leads, never
  blended.

  Proven: every commit re-stamps a moved cell's epoch above its previous
  stamp, floored at the frame's commit basis, so `:latest-reads` — the
  reads at the boundary's own maximum epoch — are the ones whose values
  moved most recently, and `:snapshot` is the sum React itself compares.

  Leads: the commit seam records no cascade id, so no retained run can be
  JOINED to this boundary's re-run; `:candidates` are the runs that
  recomputed a read of this boundary, matched on `[frame-id sub-id]`
  within the boundary's OWN frames' rings, and the row's `:loss` says
  which of two things happened. With runs retained the search ran and
  only the join is missing — `:uncorrelated`, and `:candidates` may
  honestly be `[]`. With the window empty no search happened — `:cap`,
  and `:candidates` states `evidence/unknown`. Scoping
  to the boundary's own frames is what keeps activity in frame B from
  turning frame A's `:cap` into a false `:uncorrelated`, or B's runs into
  A's leads because two frames registered one sub id."
  [windows candidates-by-frame-sub boundary-row]
  (let [boundary-reads   (:reads boundary-row)
        frame-ids        (into #{} (map :frame-id) boundary-reads)
        retained-runs    (reduce + 0 (map #(count (get windows %)) frame-ids))
        epochs           (into []
                               (keep #(when (number? (:epoch %)) (:epoch %)))
                               boundary-reads)
        peak-epoch       (when (seq epochs) (reduce max epochs))
        searched-window? (pos? retained-runs)
        candidate-leads  (ordered
                           :dispatch-id
                           (into #{}
                                 (mapcat (fn [{:keys [frame-id sub-id]}]
                                           (get candidates-by-frame-sub
                                                [frame-id sub-id])))
                                 boundary-reads))]
    {:boundary     (:boundary boundary-row)
     :views        (:views boundary-row)
     :frame        (:frame boundary-row)
     :instances    (:instances boundary-row)
     :window       {:frames (vec (sort-by pr-str frame-ids))
                    :retained-runs retained-runs}
     :snapshot     (if (seq epochs) (reduce + epochs) rf.hicasso.evidence/unknown)
     :peak-epoch   (or peak-epoch rf.hicasso.evidence/unknown)
     ;; The READ IDENTITY, not the bare sub-id: `[:row 1]` and `[:row 2]`
     ;; are one sub-id and two different reads, and a Why view that
     ;; collapsed them would answer "`:row` moved" to a developer looking
     ;; at eight rows. The query is already projected on the read row, so
     ;; naming it here carries nothing new.
     :latest-reads (if (seq epochs)
                     (into []
                           (comp (filter #(= peak-epoch (:epoch %)))
                                 (map #(select-keys % [:sub-id :query :frame-id])))
                           boundary-reads)
                     rf.hicasso.evidence/unknown)
     :loss         (if searched-window?
                     {:reason :uncorrelated :dropped rf.hicasso.evidence/unknown}
                     {:reason :cap :dropped rf.hicasso.evidence/unknown})
     :candidates   (if searched-window? candidate-leads rf.hicasso.evidence/unknown)}))

(defn explain-render
  "Which reads changed, and which boundaries hold them — the honest half
  of *why did this boundary run*. `nil` in a production build.

      (tool/explain-render)
      ;; => {:schema … :producer … :read :explain-render
      ;;     :complete? false :loss {:reason :uncorrelated :dropped :unknown}
      ;;     :explanations [{:boundary {…} :views [{:view \"app.views/todo-row\" :source {…}}]
      ;;                     :frame :app/main :instances 1
      ;;                     :window {:frames [:app/main] :retained-runs 12}
      ;;                     :snapshot 9 :peak-epoch 5
      ;;                     :latest-reads [{:sub-id :todo :query [:todo 7]
      ;;                                     :frame-id :app/main}]
      ;;                     :loss {:reason :uncorrelated :dropped :unknown}
      ;;                     :candidates [{:dispatch-id 41 :event-id :todo/toggle
      ;;                                   :frame-id :app/main :sub-id :todo}]}]
      ;;     :window {:frames [:app/main] :retained-runs 12}}

  Incomplete by construction rather than by circumstance: the commit seam
  carries no cascade identity, so there is no id to join a retained run
  to a re-run, and a bigger ring does not change that. What is proven
  rides beside it — see `explanation` — and whether the boundary then
  RAN is React's to know: a notification delivered is not a render
  performed."
  []
  (when rf.interop/debug-enabled?
    (let [rows      (entry-rows)
          ;; Every frame that could hold a lead for one of these rows: the
          ;; frames this runtime dispatches through, PLUS any frame a
          ;; boundary reads from. A boundary reading across a frame the
          ;; frame-ops table does not name still has a window, and scoping
          ;; the search per boundary is only honest if that window is in it.
          frame-ids (vec (sort-by pr-str
                                  (into (set (hicasso-frames))
                                        (mapcat (fn [boundary-row]
                                                  (map :frame-id (:reads boundary-row))))
                                        rows)))
          windows   (into {}
                          (map (fn [frame-id]
                                 [frame-id (rf.trace.tooling/trace-buffer frame-id)]))
                          frame-ids)
          runs      (reduce + 0 (map count (vals windows)))
          ;; Keyed by [frame-id sub-id]: a run that recomputed `:todo` in
          ;; frame B is not a lead for a boundary reading `:todo` in frame
          ;; A, however alike the two ids look.
          leads     (for [frame-id frame-ids
                          bundle   (get windows frame-id)
                          sub-id   (keep #(get-in % [:tags :rf.sub/id])
                                         (:subs bundle))]
                      [[frame-id sub-id]
                       {:dispatch-id (:dispatch-id bundle)
                        :event-id    (when (vector? (:event bundle))
                                       (nth (:event bundle) 0))
                        :frame-id    frame-id
                        :sub-id      sub-id}])
          by-frame-sub
          (reduce (fn [candidates [candidate-key lead]]
                    (update candidates candidate-key (fnil conj #{}) lead))
                  {}
                  leads)]
      (rf.hicasso.evidence/envelope :explain-render false {:reason :uncorrelated :dropped rf.hicasso.evidence/unknown}
                         {:explanations (mapv #(explanation windows by-frame-sub %) rows)
                          :window       {:frames frame-ids :retained-runs runs}}))))
