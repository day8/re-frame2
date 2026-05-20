(ns day8.re-frame2-causa.export.cascade
  "Per-cascade structured export (rf2-0us27, v1.1 pulled forward).

  ## Why this ns exists

  Lock 4 (`ai/findings/causa-sota-alternatives-2026-05-13.md` §10)
  decided no whole-session export at v1.0 — serialisation, redaction,
  and version-skew risk make the share unit too coarse to round-trip
  cleanly across teams. The pressure-test (Reactime / Replay.io /
  fulcro-inspect) confirmed the lock but also surfaced a genuinely
  narrower share-unit that v1.0 doesn't address: ONE focused cascade.

  v1.1 explores per-cascade export — the user picks a cascade in
  Causa's L2 list, clicks 'Export this epoch', and gets a single
  EDN document carrying everything needed to triage that cascade in
  isolation. No replay, no time-travel: a structured artefact a
  teammate (or an AI) can read end-to-end without booting the app.

  ## Output shape

  `project-cascade` returns a single map shaped:

      {:rf.causa.export/version    1
       :exported-at                <iso-string|nil>
       :epoch-id                   <epoch-id|nil>
       :dispatch-id                <opaque-id|nil>
       :frame                      <frame-id|nil>
       :event                      <event-vec|nil>
       :dispatched                 <trace-event|nil>   ;; full :event/dispatched
       :handler                    <trace-event|nil>   ;; :event/run-end (last wins)
       :fx                         <trace-event|nil>   ;; :event/do-fx
       :coeffects                  <coeffect-map|nil>  ;; hoisted from handler
       :effects                    [<trace-event> ...] ;; :op-type :fx
       :subs                       [<trace-event> ...] ;; :sub/run + :sub/create
       :renders                    [<trace-event> ...] ;; :view/render
       :other                      [<trace-event> ...] ;; errors, flows, …
       :timing                     {:started-ms <n|nil>
                                    :ended-ms   <n|nil>
                                    :duration-ms <n|nil>
                                    :event-count <int>}
       :app-db                     {:before <db|nil>
                                    :after  <db|nil>
                                    :diff   [<triple> ...]}
       :trace-events               [<trace-event> ...] ;; raw, oldest-first
       :issues                     [<issue> ...]}      ;; warnings/errors

  The map is plain Clojure data — vectors, maps, keywords. JSON
  conversion is a one-liner (`cheshire`/`js->clj` on the caller's
  side) because every key/value is a primitive or a nested
  collection thereof. EDN serialisation roundtrips via the standard
  `pr-str` / `clojure.edn/read-string` pair.

  ## What is NOT exported

  - **Reagent/React render trees.** `:view/render` trace events
    capture the render call (component name, frame, dispatch-id);
    the rendered VDOM is intentionally absent. Lock 4's serialisation
    argument holds at the React-tree level — VDOM is non-portable
    across substrate versions.

  - **Sensitive data.** This ns is pure projection over the cascade
    record + the trace events the buffer holds; redaction lives
    upstream (Spec 009 §Privacy — `:sensitive?` events are stripped
    at ingest by `trace-bus/collect-trace!`). An exported cascade is
    no more sensitive than what Causa already shows on screen.

  - **Cross-cascade context.** The export is INTENTIONALLY narrow —
    one cascade, its db-before/after, its trace events. No prior /
    subsequent cascades, no full epoch history, no machine timelines
    spanning sibling cascades. The narrow share unit is the whole
    point per the v1.1 design: triage one cascade in isolation.

  ## Round-trip discipline

  `project-cascade` is pure-data + JVM-runnable + idempotent. The
  output map's keys are stable; consumers may rely on key presence
  even when the value is nil (so a teammate's tool can detect
  'this cascade had no handler' vs 'this field was never exported').
  Schema additions are MINOR-version bumps to `:rf.causa.export/
  version`; field removals / shape changes are MAJOR.

  ## Caller contract

  Callers supply:

    - `cascade`    — a `:rf.causa/cascades`-shaped map (the
                     `re-frame.trace.projection/group-cascades`
                     output entry).
    - `epoch`      — optional `:rf/epoch-record` from
                     `:rf.causa/epoch-history` matching the cascade.
                     When absent, `:app-db` shows nils.
    - `opts`       — `{:exported-at <iso-string-or-nil>}`. The
                     exported-at slot is caller-supplied so the
                     pure projection stays JVM-time-deterministic;
                     CLJS callers pass `(js/Date.).toISOString()`.

  The projection is tolerant: nil cascade returns a minimal envelope
  with `:dispatch-id nil` so downstream consumers can detect 'nothing
  to export'."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ---- schema version -----------------------------------------------------

(def schema-version
  "Bumped on additive schema changes; carried in `:rf.causa.export/
  version` so downstream tools can fall back gracefully for
  v(future)+. Start at 1; bump on additions / shape changes."
  1)

;; ---- helpers ------------------------------------------------------------

(defn- trace-event-time
  "Pull `:time` off a trace event. Trace envelopes carry `:time`
  top-level when emitted via `re-frame.trace/emit!`. Returns nil for
  events without a time (e.g. fixtures)."
  [ev]
  (when (map? ev) (:time ev)))

(defn- collect-event-times
  "Walk the cascade record's trace event slots and return a vector of
  every `:time` we can find. Used to compute the timing envelope."
  [{:keys [dispatched handler fx effects subs renders other]}]
  (let [evs (concat [dispatched handler fx] effects subs renders other)]
    (into [] (keep trace-event-time) evs)))

(defn- compute-timing
  "Derive `{:started-ms :ended-ms :duration-ms :event-count}` from the
  cascade's trace events. Returns a map with nil values when no times
  resolve (e.g. JVM-only fixtures). `:event-count` is the total trace
  events folded across all domino slots."
  [cascade]
  (let [times (collect-event-times cascade)
        n     (+ (count (:effects cascade))
                 (count (:subs cascade))
                 (count (:renders cascade))
                 (count (:other cascade))
                 (if (:dispatched cascade) 1 0)
                 (if (:handler cascade) 1 0)
                 (if (:fx cascade) 1 0))]
    (if (seq times)
      (let [t0 (apply min times)
            t1 (apply max times)]
        {:started-ms  t0
         :ended-ms    t1
         :duration-ms (- t1 t0)
         :event-count n})
      {:started-ms  nil
       :ended-ms    nil
       :duration-ms nil
       :event-count n})))

(defn- extract-coeffects
  "The framework's handler trace event carries the resolved coeffect
  map on the `:event/run-end` envelope under `:coeffects` (Spec 009
  §Handler envelopes). Pull it out as a top-level slot so the export
  reader doesn't have to know the trace event's internal layout."
  [handler-event]
  (when (map? handler-event)
    (or (:coeffects handler-event)
        (get-in handler-event [:tags :coeffects]))))

(defn- diff-triples
  "Diff the epoch's `:db-before` / `:db-after`. Lifted from
  `panels.app-db-diff-helpers/diff-paths` in spirit — but kept inline
  + simpler here so the export ns has no dep on panel internals
  (production wiring uses the panel helper; the inline form keeps
  this ns standalone-callable from MCP / JVM contexts where the
  panel ns is not on the classpath).

  Returns a vector of triples `[{:op :added|:modified|:removed
  :path [...] :before _ :after _}]` covering paths whose value
  differs between `before` and `after`. Operates at the top-map
  level — nested values surface as MODIFIED on their parent path
  (good enough for export; the panel's deeper structural diff is a
  rendering concern)."
  [before after]
  (cond
    (and (nil? before) (nil? after)) []
    (and (map? before) (map? after))
    (let [ks-b   (set (keys before))
          ks-a   (set (keys after))
          added  (mapv (fn [k] {:op :added :path [k] :before nil :after (get after k)})
                       (sort (set/difference ks-a ks-b)))
          removed (mapv (fn [k] {:op :removed :path [k] :before (get before k) :after nil})
                        (sort (set/difference ks-b ks-a)))
          changed (mapv (fn [k] {:op :modified :path [k]
                                 :before (get before k)
                                 :after  (get after k)})
                        (sort (filter (fn [k]
                                        (and (contains? ks-b k)
                                             (contains? ks-a k)
                                             (not= (get before k) (get after k))))
                                      ks-a)))]
      (into [] cat [added changed removed]))
    (not= before after)
    [{:op :modified :path [] :before before :after after}]
    :else
    []))

(defn- extract-issues
  "Surface errors / warnings out of the cascade's `:other` bucket as
  a flat issue vector. Trace events with `:op-type :error` or
  `:warning` are issues; the renderer (e.g. an AI agent triaging the
  export) reads them off the top-level `:issues` slot rather than
  walking `:other` itself."
  [{:keys [other]}]
  (into []
        (comp (filter map?)
              (filter #(contains? #{:error :warning} (:op-type %)))
              (map (fn [ev]
                     {:severity (:op-type ev)
                      :message  (or (:message ev)
                                    (get-in ev [:tags :message])
                                    (:operation ev))
                      :event    ev})))
        other))

(defn project-cascade
  "Pure data → structured export map. See ns docstring for the
  output shape + caller contract.

  - `cascade`  — `:rf.causa/cascades`-shaped entry (or nil).
  - `opts`     — `{:epoch       <epoch-record-or-nil>
                   :exported-at <iso-string-or-nil>}`.

  JVM + CLJS. Idempotent. Stable key set."
  ([cascade]
   (project-cascade cascade nil))
  ([cascade {:keys [epoch exported-at] :as _opts}]
   (let [{:keys [dispatch-id frame event dispatched handler
                 fx effects subs renders other]} (or cascade {})
         timing      (compute-timing cascade)
         issues      (extract-issues cascade)
         coeffects   (extract-coeffects handler)
         db-before   (:db-before epoch)
         db-after    (:db-after epoch)
         epoch-id    (or (:epoch-id epoch))
         raw-trace   (or (:trace-events epoch) [])]
     {:rf.causa.export/version schema-version
      :exported-at             exported-at
      :epoch-id                epoch-id
      :dispatch-id             dispatch-id
      :frame                   frame
      :event                   event
      :dispatched              dispatched
      :handler                 handler
      :fx                      fx
      :coeffects               coeffects
      :effects                 (vec (or effects []))
      :subs                    (vec (or subs []))
      :renders                 (vec (or renders []))
      :other                   (vec (or other []))
      :timing                  timing
      :app-db                  {:before db-before
                                :after  db-after
                                :diff   (diff-triples db-before db-after)}
      :trace-events            (vec raw-trace)
      :issues                  issues})))

;; ---- serialisation ------------------------------------------------------

(defn to-edn-string
  "Render an export map to an EDN string via `pr-str`. The export map
  is pure-data so `pr-str` is sufficient — every value is either a
  primitive, a keyword, a vector/map of same, or a fully-printable
  Clojure datatype. Tests and the share-modal copy path both go
  through here so the wire shape stays in one place."
  [export-map]
  (binding [*print-length* nil
            *print-level*  nil]
    (pr-str export-map)))

(defn suggested-filename
  "Derive a filesystem-safe filename for a download. Combines a
  short `:dispatch-id` excerpt with the exported-at timestamp. JVM
  + CLJS. Pure fn."
  [{:keys [dispatch-id exported-at]}]
  (let [id-str (cond
                 (number? dispatch-id) (str dispatch-id)
                 (some? dispatch-id)   (str dispatch-id)
                 :else                 "cascade")
        clean  (-> id-str
                   (str/replace #"[^A-Za-z0-9._-]+" "-")
                   (str/replace #"^-+|-+$" ""))
        ts     (when exported-at
                 (-> exported-at
                     (str/replace #"[:.]" "-")))]
    (str "causa-cascade-"
         (if (seq clean) clean "unknown")
         (when (seq ts) (str "-" ts))
         ".edn")))
