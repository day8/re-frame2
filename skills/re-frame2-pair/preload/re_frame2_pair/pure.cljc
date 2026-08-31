;;;; re-frame2-pair.pure — the genuinely-pure core of the pair preload runtime.
;;;;
;;;; This namespace holds the pure, side-effect-free helpers `runtime.cljs`
;;;; delegates to: cascade / consequence projections, the multi-frame
;;;; operating-frame resolver, the id-validation core, the epoch
;;;; timing + matcher, the snapshot-scope resolver, and the orient assembler.
;;;;
;;;; WHY IT EXISTS. `runtime.cljs` is the SHIPPED preload; it reads live
;;;; framework surfaces (`rf/frame-ids`, `rf/epoch-history`, …) and mutates
;;;; session atoms (the operating-frame pin, the app-db-hash cache, the
;;;; recorder registry). None of that runs deterministically off-box.
;;;; The DECISION logic underneath, however, is pure data → data. Extracting
;;;; it here lets the tests exercise the EXACT code the runtime ships (the
;;;; runtime `:require`s this ns and calls these fns) under a `:node-test`
;;;; build, rather than re-deriving the algorithms in a Babashka mirror that
;;;; can silently drift from the shipped preload.
;;;;
;;;; PURITY CONTRACT. Every fn here is a pure function of its arguments — no
;;;; atom reads, no clock, no DOM, no randomness. Where the runtime consults a
;;;; session gate (the raw-state posture, the operating
;;;; frame), the gate is threaded in as an ARGUMENT (`allow-raw-state?`,
;;;; `selected`, the live `frame-ids`) so the caller in
;;;; `runtime.cljs` supplies the live value and the tests supply a fixture
;;;; value.
;;;;
;;;; `.cljc` so the pure core is source-portable; the shipped preload and the
;;;; node-test build both compile it for CLJS.

(ns re-frame2-pair.pure
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ===========================================================================
;; Operating-frame resolution (multi-frame)
;; ===========================================================================

(defn reserved-tool-frame?
  "True when `frame-id` names a framework-reserved `:rf/*` TOOL frame — a
   devtool surface (Xray's `:rf/xray`, an SSR slot, …) the tooling mounted,
   NOT an app frame. Keys off the reserved-namespace RULE (namespace = \"rf\",
   minus the `:rf/default` app-frame carve-out per Conventions.md §Reserved
   namespaces), never a hardcoded id."
  [frame-id]
  (and (keyword? frame-id)
       (= "rf" (namespace frame-id))
       (not= :rf/default frame-id)))

(defn app-frame-ids
  "The APP frame ids — `frame-ids` with reserved `:rf/*` TOOL frames removed.
   `:rf/default` is retained (it is an app frame). Order/source mirrors the
   input `frame-ids`."
  [frame-ids]
  (vec (remove reserved-tool-frame? frame-ids)))

(defn resolve-operating-frame
  "Resolve the operating frame from the tiered inputs: explicit override →
   session pin (`selected`) → the sole registered APP frame → nil (ambiguous).
   Pure — the caller supplies the live `selected` value and `app-fids`."
  [override selected app-fids]
  (or override
      selected
      (when (= 1 (count app-fids))
        (first app-fids))))

(defn ambiguous-frame-envelope
  "Build the enriched `:ambiguous-frame` refusal envelope. `:reason
   :ambiguous-frame` is the sole machine discriminator; the other slots are
   additive recovery context. `extra` (optional) carries op-specific context
   (`:event` / `:query` / `:query-v`)."
  ([operation available-frames selected]
   (ambiguous-frame-envelope operation available-frames selected nil))
  ([operation available-frames selected extra]
   (merge
     {:ok?              false
      :reason           :ambiguous-frame
      :operation        operation
      :available-frames available-frames
      :selected-frame   selected
      :hint             (str "multiple app frames are registered and no frame is "
                             "selected, so " (name operation) " cannot pick a target. "
                             "Pass `frame` (one of " (pr-str available-frames) ") or pin one with "
                             "`select-frame!` / set-operating-frame, then retry.")}
     extra)))

;; ===========================================================================
;; Call-time id validation (dispatch / read-sub)
;; ===========================================================================

(defn levenshtein
  "Edit distance between two strings — the nearest-match ranking metric.
   Pure; no allocation beyond the rolling rows."
  [a b]
  (let [a (vec a) b (vec b)
        m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 1
             prev (vec (range (inc n)))]
        (if (> i m)
          (peek prev)
          (let [cur (reduce
                      (fn [row j]
                        (let [cost (if (= (nth a (dec i)) (nth b (dec j))) 0 1)]
                          (conj row (min (inc (peek row))
                                         (inc (nth prev j))
                                         (+ (nth prev (dec j)) cost)))))
                      [i]
                      (range 1 (inc n)))]
            (recur (inc i) cur)))))))

(defn nearest-ids
  "Up to `n` registered ids closest to `id` by edit distance over their
   `pr-str` rendering, nearest first. Ties broken by `pr-str`."
  ([id known] (nearest-ids id known 3))
  ([id known n]
   (let [target (pr-str id)]
     (->> known
          (sort-by (juxt #(levenshtein target (pr-str %)) pr-str))
          (take n)
          vec))))

(defn validate-against-known
  "Validate `id` against a `known` seq of registered ids under registrar
   `kind`. Returns the registered / unknown-id envelope with `:nearest`
   matches. Pure — the caller supplies the live `known` set."
  [kind id known]
  (if (some #(= id %) known)
    {:ok? true :kind kind :id id}
    (let [near (nearest-ids id known)]
      {:ok?         false
       :reason      :unknown-id
       :kind        kind
       :id          id
       :nearest     near
       :known-count (count known)
       :hint        (if (seq near)
                      (str "unknown " kind " " (pr-str id) "; did you mean "
                           (str/join ", " (map pr-str near)) "?")
                      (str "unknown " kind " " (pr-str id)
                           "; nothing is registered under " kind "."))})))

;; ===========================================================================
;; Epoch timing + matcher
;; ===========================================================================

(defn epoch-elapsed-ms
  "Wall-clock elapsed-ms for an epoch — span the FIRST `:rf.event/run-start`
   to the LAST `:rf.event/run-end` trace event on `:time`, so a synchronously-
   dispatched handler chain rolls up to the cascade's total hold time. nil
   when neither bracket is present."
  [{:keys [trace-events]}]
  (let [run-event? (fn [op ev]
                     (and (= :rf.event (:op-type ev))
                          (= op (:operation ev))))
        first-time (some (fn [ev] (when (run-event? :rf.event/run-start ev) (:time ev))) trace-events)
        last-time  (reduce (fn [acc ev]
                             (if (run-event? :rf.event/run-end ev)
                               (let [t (:time ev)] (if (and (number? t) (or (nil? acc) (> t acc))) t acc))
                               acc))
                           nil
                           trace-events)]
    (when (and (number? first-time) (number? last-time) (>= last-time first-time))
      (- last-time first-time))))

(defn parse-timing-pred
  "Parse a `:timing-ms` predicate value into a one-arg matcher fn. A number
   `N` is sugar for `>= N`; a comparison string (`\">N\"`, `\"<=N\"`, …)
   compares against the parsed threshold. nil for unparseable input."
  [v]
  (cond
    (number? v)
    (fn [ms] (and (number? ms) (>= ms v)))

    (string? v)
    (let [m (re-matches #"\s*(>=|<=|>|<|=)?\s*(-?\d+(?:\.\d+)?)\s*" v)]
      (when m
        (let [op   (or (nth m 1) ">=")
              n    #?(:cljs (js/parseFloat (nth m 2)) :clj (Double/parseDouble (nth m 2)))
              nan? #?(:cljs (js/isNaN n)              :clj (Double/isNaN n))]
          (when-not nan?
            (case op
              ">"  (fn [ms] (and (number? ms) (> ms n)))
              ">=" (fn [ms] (and (number? ms) (>= ms n)))
              "<"  (fn [ms] (and (number? ms) (< ms n)))
              "<=" (fn [ms] (and (number? ms) (<= ms n)))
              "="  (fn [ms] (and (number? ms) (= ms n)))
              nil)))))))

(defn origin-matches?
  "True when a cascade's `trace-events` carry an `:rf.event/dispatched` trace
   tagged with `:rf.event/origin` = `origin`.

   The SINGLE trace-tag rule for dispatch-origin classification — both
   `epoch-matches?`'s `:origin` axis (origin filtering) and `pair-origin?`
   (listener attribution) route through it, so the two never drift on how an
   epoch's origin is read off the cascade."
  [origin trace-events]
  (boolean
    (some (fn [t] (and (= :rf.event/dispatched (:operation t))
                       (= origin (get-in t [:tags :rf.event/origin]))))
          trace-events)))

(defn pair-origin?
  "True when an assembled epoch `record`'s cascade was dispatched with
   `:origin :pair` — i.e. THIS skill fired it. The single classification
   predicate the assembled-epoch listener uses to attribute pair epochs;
   funnels through `origin-matches?` (the same rule `epoch-matches?` uses)."
  [record]
  (origin-matches? :pair (:trace-events record)))

(defn epoch-matches?
  "Test an epoch record against a predicate map. Recognised keys: :event-id,
   :event-id-prefix, :effects, :touches-path, :sub-ran, :render, :origin,
   :frame, :timing-ms. Absent key = no constraint on that axis (AND-composed)."
  [pred {:keys [event-id sub-runs renders effects
                trace-events frame db-before db-after]}]
  (let [{p-eid    :event-id
         p-prefix :event-id-prefix
         p-fx     :effects
         p-path   :touches-path
         p-sub    :sub-ran
         p-render :render
         p-origin :origin
         p-frame  :frame
         p-timing :timing-ms} pred
        timing-fn (when (some? p-timing) (parse-timing-pred p-timing))]
    (boolean
      (and
        (if p-eid    (= p-eid event-id) true)
        (if p-prefix (some-> event-id str (str/starts-with? (str p-prefix))) true)
        (if p-fx     (some #(= p-fx (:fx-id %)) effects) true)
        (if p-path   (or (some? (get-in db-before p-path))
                         (some? (get-in db-after p-path)))
                     true)
        (if p-sub    (some #(or (= p-sub (:sub-id %))
                                (= p-sub (first (:query-v %))))
                           sub-runs) true)
        (if p-render (some #(= p-render (str (:render-key %))) renders) true)
        (if p-origin (origin-matches? p-origin trace-events) true)
        (if p-frame  (= p-frame frame) true)
        (if timing-fn (timing-fn (epoch-elapsed-ms {:trace-events trace-events})) true)))))

;; ===========================================================================
;; Pair-epoch attribution (frame-qualified, queued-correct, bounded)
;; ===========================================================================
;;
;; `last-pair-epoch` answers "which epoch did THIS skill most-recently fire in
;; this frame?". Attribution happens at the assembled-epoch listener — the one
;; point where BOTH synchronous AND queued dispatches settle — never eagerly at
;; dispatch time (which misses the queued path, where the head has not advanced
;; yet). Identity is stored frame-qualified (`{frame-id #{epoch-id ...}}`),
;; never as a bare epoch-id: `:epoch-id` is unique only WITHIN a frame history
;; (Spec-Schemas §`:rf/epoch-record`), so two frames can legitimately carry the
;; same id. The store is bounded to the frame's LIVE retained ring — rolled-over
;; ids and destroyed-frame entries are dropped — so it never becomes a second,
;; unbounded mirror of the epoch ring.

(defn attribute-pair-epoch
  "Pure next-state for the frame-qualified pair-attribution store.

     store      — current `{frame-id #{epoch-id ...}}`
     record     — the just-settled assembled epoch record (from the listener)
     registered — set of currently-registered frame-ids
     live-ids   — set of epoch-ids in `record`'s frame's live epoch ring

   Adds `record`'s epoch-id under its frame iff the cascade was `:origin :pair`
   (`pair-origin?`); then rebinds the store to live retained history:
   entries for frames absent from `registered` are dropped (frame-destruction
   cleanup), and the settling frame's set is intersected with `live-ids`
   (ring-rollover cleanup). A frame whose set empties is removed so the map
   never retains empty entries. The attributed epoch-id is retained even after
   its raw trace is elided for age, as long as it is still in `live-ids`."
  [store record registered live-ids]
  (let [frame-id (:frame record)
        base     (into {} (filter (fn [[fid _]] (contains? registered fid))) store)]
    (if (nil? frame-id)
      base
      (let [ids  (cond-> (get base frame-id #{})
                   (pair-origin? record) (conj (:epoch-id record)))
            kept (into #{} (filter live-ids) ids)]
        (if (seq kept)
          (assoc base frame-id kept)
          (dissoc base frame-id))))))

(defn pick-pair-epoch
  "The most recent record in `history` (oldest-first) whose `:epoch-id` is a
   member of `ids` (the frame's attributed pair epochs), or nil. Walks the ring
   backward so the newest pair epoch wins."
  [ids history]
  (some (fn [r] (when (contains? ids (:epoch-id r)) r))
        (reverse history)))

;; ===========================================================================
;; Cascade / consequence projection
;; ===========================================================================

(defn db-diff-summary
  "Depth-1 path summary of the db-before → db-after delta. Each path is a
   one-key vector; operators drill via `get-path`. Bounded by the depth-1 walk
   so a cascade-summary stays under the wire cap regardless of db size."
  [db-before db-after]
  (cond
    (and (map? db-before) (map? db-after))
    (let [ks-b   (set (keys db-before))
          ks-a   (set (keys db-after))
          common (set/intersection ks-b ks-a)]
      {:added-paths   (vec (sort (map vector (set/difference ks-a ks-b))))
       :removed-paths (vec (sort (map vector (set/difference ks-b ks-a))))
       :changed-paths (vec (sort (for [k common
                                       :when (not= (get db-before k) (get db-after k))]
                                   [k])))})

    (= db-before db-after)
    {:added-paths [] :removed-paths [] :changed-paths []}

    :else
    {:added-paths [] :removed-paths [] :changed-paths [[]]}))

(defn machine-transitions-summary
  "Project `:rf.machine/transition` trace events into compact
   `{:machine-id :from :to :phase}` maps, or nil when no machine activity."
  [trace-events]
  (let [picks (->> trace-events
                   (filter (fn [ev] (= :rf.machine/transition (:operation ev))))
                   (mapv (fn [ev]
                           (let [t (:tags ev)]
                             (cond-> {}
                               (:machine-id t) (assoc :machine-id (:machine-id t))
                               (:from t)       (assoc :from (:from t))
                               (:to t)         (assoc :to (:to t))
                               (:phase t)      (assoc :phase (:phase t)))))))]
    (when (seq picks) picks)))

(defn outcome-tier
  "Project the epoch's detailed `:outcome` cause onto the consumer-facing
   three-tier summary (`:ok` / `:blocked` / `:error`). Defaults to `:ok`."
  [outcome]
  (case outcome
    :ok                       :ok
    :halted-depth             :blocked
    :halted-destroy           :blocked
    :halted-handler-exception :error
    :ok))

(def cascade-error-ops
  "Closed set of cascade-level `:rf.error/*` trace ops that mark an epoch
   whose cascade contained a thrown handler / machine action. Mirrors Xray's
   `cascade-exception-ops` so the structured summary and the human Epoch panel
   agree on what counts as a throw."
  #{:rf.error/coeffect-exception
    :rf.error/interceptor-exception
    :rf.error/handler-exception
    :rf.error/fx-handler-exception
    :rf.error/no-such-fx
    :rf.error/flow-eval-exception
    :rf.error/machine-action-exception})

(defn cascade-errors
  "Project the contained cascade-exception trace events out of an epoch's
   `:trace-events` into compact descriptors, or nil when the cascade carried
   no contained throw."
  [trace-events]
  (let [picks (->> trace-events
                   (filter (fn [ev] (contains? cascade-error-ops (:operation ev))))
                   (mapv (fn [ev]
                           (let [t (:tags ev)]
                             (cond-> {:operation (:operation ev)}
                               (string? (:exception-message t))
                               (assoc :message (:exception-message t))
                               (:machine-id t) (assoc :machine-id (:machine-id t))
                               (:action-id t)  (assoc :action-id (:action-id t)))))))]
    (when (seq picks) picks)))

(defn redact-sensitive-event-vector
  "Egress guard for the cascade-summary `:event-vector` slot — the fail-closed
   projection the framework's `elide-trigger-event-slot` applies to a record's
   `:trigger-event`, reproduced here because the cascade-summary rides OUTSIDE
   the wire-path projection.

   Fail-closed (`allow-raw-state?` false — the published-build default): the
   head `<event-id>` keyword is RETAINED while every positional / map arg is
   replaced with `:rf/redacted`, so `[:login \"topsecret\"]` egresses as
   `[:login :rf/redacted]`. The fail-close fires on EVERY epoch (sensitive or
   not) — the event args are registration-owned transient payloads the app-db
   classification walker cannot prove safe. Raw only on the operator's
   `--allow-sensitive-reads` opt-in (`allow-raw-state?` true). A degenerate
   non-vector / empty slot redacts wholesale. Nil-preserving.

   `sensitive?` is threaded by callers (it governs the summary's
   `:sensitive?` annotation) but does NOT gate this redaction — the args fail
   closed whether or not it is set."
  [trigger-event sensitive? allow-raw-state?]
  (cond
    allow-raw-state?     trigger-event
    (nil? trigger-event) trigger-event
    (and (vector? trigger-event) (seq trigger-event))
    (into [(first trigger-event)]
          (repeat (dec (count trigger-event)) :rf/redacted))
    :else :rf/redacted))

(defn cascade-summary
  "Project an assembled `:rf/epoch-record` into the compact wire shape. Pure
   data — `(epoch-record, allow-raw-state?) → cascade-summary-map`. Returns
   nil for a nil record. `allow-raw-state?` is the caller's live raw-state
   gate; it governs the `:event-vector` fail-close."
  [{:keys [epoch-id event-id trigger-event frame outcome
           db-before db-after effects sub-runs renders trace-events]
    :as record}
   allow-raw-state?]
  (when record
    (let [diff        (db-diff-summary db-before db-after)
          fx-fired    (->> effects (map :fx-id) distinct vec)
          transitions (machine-transitions-summary trace-events)
          elapsed     (epoch-elapsed-ms record)
          sensitive?  (:rf.epoch/sensitive? record)
          errors      (cascade-errors trace-events)]
      (cond-> {:epoch-id        epoch-id
               :frame           frame
               :outcome         (if errors :error (outcome-tier outcome))
               :db-diff         diff
               :fx-fired        fx-fired
               :subs-recomputed (count (or sub-runs []))
               :renders         (count (or renders []))}
        event-id      (assoc :event-id event-id)
        trigger-event (assoc :event-vector
                             (redact-sensitive-event-vector trigger-event sensitive? allow-raw-state?))
        transitions   (assoc :machine-transitions transitions)
        elapsed       (assoc :elapsed-ms elapsed)
        errors        (assoc :errors errors)
        sensitive?    (assoc :sensitive? true)))))

(defn consequence-from-summary
  "Project a dispatch-sync success envelope into the dispatch-consequence
   shape. A cascade that changed NO app-db path AND fired NO effect is a
   visible no-op — UNLESS it contained a throw (the `:errors` slot excludes a
   thrown-action epoch from the quiescence heuristic)."
  [result]
  (let [{:keys [cascade-summary]} result
        {:keys [db-diff fx-fired outcome errors]} cascade-summary
        changed (vec (concat (:changed-paths db-diff)
                             (:added-paths db-diff)
                             (:removed-paths db-diff)))
        effects (vec (or fx-fired []))
        db-changed? (boolean (seq changed))
        threw?  (boolean (seq errors))
        no-op?  (and (not threw?) (not db-changed?) (empty? effects))]
    (-> result
        (assoc :db-changed?   db-changed?
               :changed-paths changed
               :effects-fired effects
               :no-op?        no-op?)
        (cond-> (= :error outcome) (assoc :outcome :error)))))

(defn restore-cascade-projection
  "Project a successful `restore-epoch` against its TARGET epoch record. The
   `:db-diff` is computed from `pre-db` (the db immediately before the
   restore) to the target's `:db-after` — 'what is now different from where I
   was?'. `:unreplayable-effects` lists every fx the ORIGINAL cascade fired
   (all already escaped the framework; the restore rewinds db only). The
   `:event-vector` fails closed through the same gate cascade-summary uses.
   Pure — the caller supplies `target` (the looked-up epoch record) and the
   live `allow-raw-state?` gate."
  [pre-db target frame-id target-epoch-id allow-raw-state?]
  (let [diff        (db-diff-summary pre-db (:db-after target))
        fx-fired    (->> (:effects target) (map :fx-id) distinct vec)
        transitions (machine-transitions-summary (:trace-events target))
        sensitive?  (:rf.epoch/sensitive? target)
        unreplayable (mapv (fn [eff]
                             (cond-> {:fx-id (:fx-id eff)}
                               (:coord eff) (assoc :coord (:coord eff))))
                           (:effects target))]
    {:cascade-summary
     (cond-> {:epoch-id        target-epoch-id
              :frame           frame-id
              :outcome         :ok
              :db-diff         diff
              :fx-fired        fx-fired
              :subs-recomputed (count (or (:sub-runs target) []))
              :renders         (count (or (:renders target) []))
              :restore?        true}
       (:event-id target)      (assoc :event-id (:event-id target))
       (:trigger-event target) (assoc :event-vector
                                       (redact-sensitive-event-vector
                                         (:trigger-event target) sensitive? allow-raw-state?))
       sensitive?              (assoc :sensitive? true)
       transitions             (assoc :machine-transitions transitions))
     :unreplayable-effects unreplayable}))

;; ===========================================================================
;; Coarse-grained snapshot — scope resolution
;; ===========================================================================

(def all-snapshot-slices
  [:app-db :sub-cache :machines :epochs :traces])

(defn resolve-snapshot-frames
  "Resolve the snapshot's `:frames` scope to a concrete frame-id vector.
   `:app` (default) = the app frames (reserved `:rf/*` tool frames excluded);
   `:all` = every registered frame; a vector = the listed frames verbatim."
  [frames-mode all-frame-ids app-fids]
  (cond
    (= :app frames-mode)      (vec app-fids)
    (= :all frames-mode)      (vec all-frame-ids)
    (sequential? frames-mode) (vec frames-mode)
    :else                     (vec app-fids)))

;; ===========================================================================
;; App-shape orientation summary — assembly
;; ===========================================================================

(def orient-registrar-kinds
  "The registrar kinds whose COUNTS the orientation summary reports (`:event`
   / `:sub` / `:fx` additionally surface their full sorted id vectors). Drill
   any other kind via `list-handlers {kind ...}`."
  [:event :sub :fx :cofx :interceptor :view :frame :route :flow :head
   :error-projector :resource :mutation :resource-scope])

(defn top-keys
  "The sorted top-level keys of an app-db value (nil when it is not a map).
   The cheap 'what state shape is this' read the orient summary carries per
   app frame."
  [db]
  (when (map? db) (vec (sort-by pr-str (keys db)))))

(defn assemble-orient
  "Assemble the compact app-shape orientation summary from its already-read
   pieces. Pure — `runtime.cljs` reads the live `health` / frames / per-frame
   app-db / registry-view / machines and hands them here."
  [{:keys [debug-enabled? all-frames operating-frame ambiguous-frame?
           runtime-instance-id app-fids app-db-top-keys registry machines]}]
  {:ok?      true
   :liveness {:debug-enabled?      debug-enabled?
              :frame-count         (count all-frames)
              :app-frame-count     (count app-fids)
              :ambiguous-frame?    ambiguous-frame?
              :runtime-instance-id runtime-instance-id}
   :frames   {:all       all-frames
              :app       app-fids
              :operating operating-frame}
   :app-db-top-keys app-db-top-keys
   :registry registry
   :machines (vec machines)})
