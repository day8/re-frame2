(ns day8.re-frame2-causa.panels.cancellation-cascade-helpers
  "Pure-data helpers for Causa's Cancellation-cascade visualiser
  (rf2-59e7k, parent rf2-5aw5v).

  ## What this is

  Per `tools/causa/spec/019-Cross-Cutting-Insight.md` §M.3 — when a
  parent machine destroys a child (`:spawn` exit, `:after` fire,
  `:spawn-all` cancel-on-decision, explicit `[:rf.machine/destroy <id>]`,
  or parent-frame teardown), every in-flight `:rf.http/managed` request
  the child held aborts. Each abort emits a
  `:rf.http/aborted-on-actor-destroy` trace event (per Spec 014 §Abort
  on actor destroy / rf2-wvkn). In today's Trace tab these scatter
  through the firehose alongside the `:rf.machine.lifecycle/destroyed`
  emit and the `:rf.machine/destroyed` enrichment — devs cannot
  reconstruct which abort came from which destroy.

  This namespace folds those scattered trace events into ONE record:

      {:parent-decision  {:event-vec <vec> :t <ms> :machine-id <id>
                          :dispatch-id <id>}
       :child-teardowns  [{:child-id <id> :t <ms>
                           :reason   <keyword> :inflight-count <int>}
                          ...]
       :effect-aborts    [{:fx <:http | :ws | :after | :machine-invoke>
                           :req {<...>}  ;; per-fx detail
                           :t <ms>
                           :cancel-cause <keyword>  ;; :actor-destroyed etc
                           :request-id <id-or-nil>
                           :url <str-or-nil>
                           :actor-id <id-or-nil>
                           :correlation-id <id-or-nil>
                           :trace-id <id>           ;; the trace event :id
                           :dispatch-id <id-or-nil>}
                          ...]
       :total-elapsed-ms <int-or-nil>
       :empty-kind       <nil | :no-trigger | :no-aborts>}

  `:total-elapsed-ms` is the wall-clock span from the parent decision
  to the last abort (nil if either endpoint is missing). `:empty-kind`
  is `:no-trigger` when no parent decision could be located,
  `:no-aborts` when a decision exists but no abort traces ride with
  it.

  ## Why a separate .cljc

  Same dual-target pattern every other panel helper uses (the JVM
  test target drives the algebra without a CLJS runtime). The view in
  `cancellation_cascade.cljs` is a thin renderer over this record.

  ## Detection strategy

  The cascade pivots on a single anchor: a `:rf.machine.lifecycle/
  destroyed` or `:rf.machine/destroyed` trace event whose `:reason`
  is one of the cancellation reasons (`:explicit`,
  `:parent-unmount-cascade`, `:parent-frame-destroyed`). The anchor's
  `:dispatch-id` (when present) is the cascade boundary; aborts that
  share the same `:dispatch-id` (or land within a small wall-clock
  window of the anchor for the actor-destroy case where the abort
  emits run outside the originating drain) are gathered into the
  visualiser.

  The decision row (\"parent decision\") is the most-recent
  `:event/dispatched` trace event within the cascade that the anchor
  belongs to — typically `[:auth/logout]`, `[:checkout/cancel]`, etc.

  Best-effort heuristic when the trace events lack the runtime tags
  we'd ideally read: fall back to a small wall-clock window
  (`+default-actor-destroy-window-ms+`) around the anchor; group every
  `:rf.http/aborted-on-actor-destroy` trace inside the window into the
  cascade. Divergence note: today's traces don't all carry
  `:cancel-cause`; we lift it off `:reason` / `:tags :reason` when
  available and default to `:actor-destroyed` for
  `:rf.http/aborted-on-actor-destroy` events (the canonical case).

  ## What this does NOT do

    - Rendering — the view ns does the SVG/hiccup work.
    - Cross-frame causality — single-cascade-anchor scope only.
    - Multi-anchor merging — each call returns ONE cascade. The subs
      pick which anchor to focus (focused-machine or focused-event)."
  (:require [clojure.string :as str]))

;; ---- canonical operation sets -------------------------------------------

(def ^:private destroy-operations
  "Trace operations that signal a machine-instance teardown. Per Spec
  009 §Trace events both pairs land on a destroy:

    - `:rf.machine.lifecycle/destroyed` (frame.cljc + lifecycle_fx)
    - `:rf.machine/destroyed` (fx.cljc on the destroy fx-id path)

  The pair is intentionally symmetric so consumers can subscribe to
  either; we accept either as a cascade anchor."
  #{:rf.machine.lifecycle/destroyed
    :rf.machine/destroyed})

(def ^:private abort-operations
  "Trace operations that signal an in-flight effect being cancelled.

    - `:rf.http/aborted-on-actor-destroy` (Spec 014 §Abort on actor destroy)
    - `:rf.http/aborted` (failure category, when `:reason` is one of
      the cascade reasons; rarer)
    - `:rf.ws/aborted-on-actor-destroy` (Pattern-WebSocket; defensive —
      not all installs ship this)
    - `:rf.machine.timer/cancelled-on-resolution` (per Spec 005 §after
      timer lifecycle)
    - `:rf.machine.spawn/cancelled-on-join-resolution` (per Spec 005
      §Cancel-on-decision)"
  #{:rf.http/aborted-on-actor-destroy
    :rf.http/aborted
    :rf.ws/aborted-on-actor-destroy
    :rf.machine.timer/cancelled-on-resolution
    :rf.machine.spawn/cancelled-on-join-resolution})

(def ^:private cancellation-reasons
  "Reasons that classify a destroy as part of a cancellation cascade
  (per Spec 009 §Trace events L110-L119). `:rf.machine/finished` is
  excluded — it represents a natural termination, not a cancellation."
  #{:explicit
    :parent-unmount-cascade
    :parent-frame-destroyed
    :rf.machine/cancelled
    :actor-destroyed})

(def ^:const default-actor-destroy-window-ms
  "Best-effort wall-clock window (ms) around the anchor's `:time` for
  associating abort traces that lack a `:dispatch-id` link. Per the
  bead's divergence allowance: until the substrate stamps
  `:cancel-cause` + a back-link on every abort event, proximity is
  the structural fallback."
  100)

;; ---- predicates ---------------------------------------------------------

(defn destroy-event?
  "True iff `ev` is a machine-destroy trace event."
  [ev]
  (and (map? ev)
       (contains? destroy-operations (:operation ev))))

(defn abort-event?
  "True iff `ev` is an effect-abort trace event."
  [ev]
  (and (map? ev)
       (contains? abort-operations (:operation ev))))

(defn dispatched-event?
  "True iff `ev` is an `:event/dispatched` trace event."
  [ev]
  (and (map? ev)
       (= :event/dispatched (:operation ev))))

(defn- destroy-reason
  "Lift the cancellation `:reason` off the destroy trace event. Per
  Spec 009 the runtime stamps `:tags :reason` on both
  `:rf.machine/destroyed` (lifecycle_fx) and `:rf.machine.lifecycle/
  destroyed` (frame.cljc) emit sites. nil when absent."
  [ev]
  (get-in ev [:tags :reason]))

(defn cancellation-anchor?
  "True iff `ev` is a destroy event whose `:reason` marks it as part
  of a cancellation cascade (vs. a natural `:rf.machine/finished`)."
  [ev]
  (and (destroy-event? ev)
       (contains? cancellation-reasons (destroy-reason ev))))

;; ---- fx-id classification -----------------------------------------------

(defn classify-fx
  "Classify an abort trace event into one of the abort-row :fx tags
  the visualiser displays. Pure fn of the event."
  [ev]
  (case (:operation ev)
    :rf.http/aborted-on-actor-destroy        :http
    :rf.http/aborted                         :http
    :rf.ws/aborted-on-actor-destroy          :ws
    :rf.machine.timer/cancelled-on-resolution :after
    :rf.machine.spawn/cancelled-on-join-resolution :machine-invoke
    :unknown))

;; ---- cancel-cause projection --------------------------------------------

(defn cancel-cause
  "Project the `:cancel-cause` for an abort trace event. Per the
  divergence allowance: the substrate may or may not stamp this slot
  today. We fall back to a structural default derived from the abort's
  identity:

    - `:rf.http/aborted-on-actor-destroy` → `:actor-destroyed`
    - `:rf.ws/aborted-on-actor-destroy`   → `:actor-destroyed`
    - `:rf.machine.timer/cancelled-*`     → `:join-resolved`
    - `:rf.machine.spawn/cancelled-*`    → `:join-resolved`
    - any other abort with `:reason` tag  → that reason
    - otherwise                           → `:unknown`"
  [ev]
  (or (get-in ev [:tags :cancel-cause])
      (get-in ev [:tags :reason])
      (case (:operation ev)
        :rf.http/aborted-on-actor-destroy        :actor-destroyed
        :rf.ws/aborted-on-actor-destroy          :actor-destroyed
        :rf.machine.timer/cancelled-on-resolution :join-resolved
        :rf.machine.spawn/cancelled-on-join-resolution :join-resolved
        :unknown)))

;; ---- row projection -----------------------------------------------------

(defn- abort-row
  "Build one abort-row from an abort trace event."
  [ev]
  (let [tags (:tags ev)]
    {:fx             (classify-fx ev)
     :req            (select-keys tags [:request-id :url :method
                                        :timer-id :child-id :spawned-id
                                        :spawn-id])
     :t              (:time ev)
     :cancel-cause   (cancel-cause ev)
     :request-id     (:request-id tags)
     :url            (:url tags)
     :actor-id       (or (:actor-id tags) (:machine-id tags) (:spawned-id tags))
     :correlation-id (or (:request-id tags) (:correlation-id tags))
     :trace-id       (:id ev)
     :dispatch-id    (:dispatch-id tags)}))

(defn- teardown-row
  "Build one child-teardown row from a destroy trace event."
  [ev]
  (let [tags (:tags ev)]
    {:child-id       (or (:machine-id tags) (:spawned-id tags))
     :spawned-id     (:spawned-id tags)
     :parent-id      (:parent-id tags)
     :spawn-id      (:spawn-id tags)
     :t              (:time ev)
     :reason         (destroy-reason ev)
     :last-state     (:last-state tags)
     :inflight-count nil  ;; populated downstream from the gathered aborts
     :trace-id       (:id ev)
     :dispatch-id    (:dispatch-id tags)}))

(defn- decision-row
  "Build the parent-decision row from a `:event/dispatched` trace
  event. nil when the input isn't a dispatched event."
  [ev]
  (when (dispatched-event? ev)
    (let [tags (:tags ev)]
      {:event-vec   (:event tags)
       :t           (:time ev)
       :machine-id  (or (:machine-id tags) (:handler-id tags))
       :dispatch-id (:dispatch-id tags)
       :trace-id    (:id ev)})))

;; ---- cascade extraction -------------------------------------------------

(defn- find-anchor
  "Locate the cascade anchor in `trace-buffer`. The anchor is either:

    1. The destroy event matching `focus-id` (when `focus-kind`
       = `:machine-id`).
    2. The most-recent destroy event in the cascade identified by
       `focus-id` (when `focus-kind` = `:dispatch-id`).
    3. The most-recent cancellation-anchor in the buffer (when
       `focus-kind` = nil — the 'just show me the latest cascade' path).

  Returns the trace event map, or nil when no anchor can be found."
  [trace-buffer focus-kind focus-id]
  (let [evs (or trace-buffer [])]
    (case focus-kind
      :machine-id
      (->> evs
           (filter #(and (cancellation-anchor? %)
                         (let [tags (:tags %)]
                           (or (= focus-id (:machine-id tags))
                               (= focus-id (:spawned-id tags))
                               (= focus-id (:parent-id tags))))))
           (sort-by :time)
           last)

      :dispatch-id
      (->> evs
           (filter #(and (cancellation-anchor? %)
                         (= focus-id (get-in % [:tags :dispatch-id]))))
           (sort-by :time)
           last)

      ;; default: latest cancellation-anchor in the buffer
      (->> evs
           (filter cancellation-anchor?)
           (sort-by :time)
           last))))

(defn- gather-related-aborts
  "Pull every abort trace event that should be grouped under the
  anchor. Two paths in order of preference:

    1. Same `:dispatch-id` as the anchor (the strict structural link).
    2. Wall-clock window around the anchor's `:time`
       (`+default-actor-destroy-window-ms+`) — the best-effort fallback
       for when the actor-destroy abort fires outside the originating
       drain and so carries no `:dispatch-id` link.

  Sorted oldest-first by `:time`."
  [trace-buffer anchor]
  (let [evs           (or trace-buffer [])
        anchor-t      (:time anchor)
        anchor-disp   (get-in anchor [:tags :dispatch-id])
        anchor-actor  (or (get-in anchor [:tags :machine-id])
                          (get-in anchor [:tags :spawned-id]))
        window        default-actor-destroy-window-ms
        by-dispatch   (when anchor-disp
                        (filter #(and (abort-event? %)
                                      (= anchor-disp
                                         (get-in % [:tags :dispatch-id])))
                                evs))
        ;; Wall-clock fallback — include if the trace lacks the
        ;; dispatch-id link but lands inside the window AND either
        ;; mentions the actor or is an actor-destroy-shaped abort.
        by-window     (filter
                        (fn [ev]
                          (and (abort-event? ev)
                               (number? (:time ev))
                               (number? anchor-t)
                               (<= 0 (- (:time ev) anchor-t) window)
                               (let [ev-disp (get-in ev [:tags :dispatch-id])
                                     ev-actor (or (get-in ev [:tags :actor-id])
                                                  (get-in ev [:tags :machine-id]))]
                                 (and (or (nil? ev-disp)
                                          (not= ev-disp anchor-disp))
                                      (or (nil? anchor-actor)
                                          (nil? ev-actor)
                                          (= anchor-actor ev-actor)
                                          ;; actor-destroy-shaped abort with
                                          ;; no actor link — fold it in
                                          (= :rf.http/aborted-on-actor-destroy
                                             (:operation ev)))))))
                        evs)
        all           (concat by-dispatch by-window)
        ;; Dedup by trace-id; preserve order; sort oldest-first.
        unique        (->> all
                           (reduce (fn [{:keys [seen acc]} ev]
                                     (let [k (:id ev)]
                                       (if (and k (contains? seen k))
                                         {:seen seen :acc acc}
                                         {:seen (if k (conj seen k) seen)
                                          :acc  (conj acc ev)})))
                                   {:seen #{} :acc []})
                           :acc)]
    (->> unique
         (sort-by (fn [ev] [(or (:time ev) 0) (or (:id ev) 0)])))))

(defn- gather-related-teardowns
  "Pull every destroy trace event that rides with the anchor (same
  cascade or wall-clock window). The anchor itself is included. Each
  carries `:inflight-count` derived from the aborts that target the
  same actor.

  Sorted oldest-first by `:time`."
  [trace-buffer anchor aborts]
  (let [evs           (or trace-buffer [])
        anchor-t      (:time anchor)
        anchor-disp   (get-in anchor [:tags :dispatch-id])
        window        default-actor-destroy-window-ms
        by-dispatch   (when anchor-disp
                        (filter #(and (cancellation-anchor? %)
                                      (= anchor-disp
                                         (get-in % [:tags :dispatch-id])))
                                evs))
        by-window     (filter
                        (fn [ev]
                          (and (cancellation-anchor? ev)
                               (number? (:time ev))
                               (number? anchor-t)
                               (<= 0
                                   (Math/abs (- (:time ev) anchor-t))
                                   window)
                               (let [ev-disp (get-in ev [:tags :dispatch-id])]
                                 (or (nil? ev-disp)
                                     (nil? anchor-disp)
                                     (not= ev-disp anchor-disp)))))
                        evs)
        all           (concat by-dispatch by-window [anchor])
        seen          (volatile! #{})
        unique        (vec
                        (keep (fn [ev]
                                (let [k [(:operation ev) (:id ev)]]
                                  (when (and (not (contains? @seen k))
                                             (vswap! seen conj k))
                                    ev)))
                              all))
        ;; Build a count of aborts per actor id.
        counts-by-actor
        (reduce (fn [acc abort-ev]
                  (let [tags  (:tags abort-ev)
                        actor (or (:actor-id tags)
                                  (:machine-id tags)
                                  (:spawned-id tags))]
                    (if actor
                      (update acc actor (fnil inc 0))
                      acc)))
                {}
                (or aborts []))]
    (->> unique
         (sort-by (fn [ev] [(or (:time ev) 0) (or (:id ev) 0)]))
         (mapv (fn [ev]
                 (let [row    (teardown-row ev)
                       actor  (or (:child-id row) (:spawned-id row))
                       found  (when actor (get counts-by-actor actor))]
                   (assoc row :inflight-count (or found 0))))))))

(defn- find-decision
  "Locate the parent decision — the most-recent `:event/dispatched`
  trace event within the cascade defined by the anchor's
  `:dispatch-id`. Falls back to the most-recent dispatched event
  before the anchor's `:time` when the anchor has no `:dispatch-id`."
  [trace-buffer anchor]
  (let [evs         (or trace-buffer [])
        anchor-t    (:time anchor)
        anchor-disp (get-in anchor [:tags :dispatch-id])
        by-dispatch (when anchor-disp
                      (->> evs
                           (filter #(and (dispatched-event? %)
                                         (= anchor-disp
                                            (get-in % [:tags :dispatch-id]))))
                           (sort-by :time)
                           first))]
    (or by-dispatch
        (when (number? anchor-t)
          (->> evs
               (filter #(and (dispatched-event? %)
                             (number? (:time %))
                             (<= (:time %) anchor-t)))
               (sort-by :time)
               last)))))

(defn extract-cascade
  "Project a cancellation-cascade record from the trace buffer. Pure
  data → data.

  Inputs:
    `trace-buffer` — vector of trace events (Causa's mirror slot or
      a fixture). nil-safe.
    `focus`        — `{:kind <:machine-id | :dispatch-id | nil>
                       :id   <value-or-nil>}` or nil.

      `:kind :machine-id`  → anchor is the latest cancellation-destroy
                             for that machine-id.
      `:kind :dispatch-id` → anchor is the destroy with that
                             dispatch-id.
      `:kind nil` (or focus nil) → most-recent cancellation-destroy
                                   in the buffer.

  Returns the cascade record described in the ns docstring, or a
  shaped empty-state record when no anchor / aborts are present."
  ([trace-buffer]
   (extract-cascade trace-buffer nil))
  ([trace-buffer focus]
   (let [{:keys [kind id]} (or focus {})
         anchor   (find-anchor trace-buffer kind id)]
     (if (nil? anchor)
       {:parent-decision  nil
        :child-teardowns  []
        :effect-aborts    []
        :total-elapsed-ms nil
        :empty-kind       :no-trigger}
       (let [aborts        (gather-related-aborts trace-buffer anchor)
             teardowns     (gather-related-teardowns trace-buffer anchor aborts)
             decision-ev   (find-decision trace-buffer anchor)
             decision      (decision-row decision-ev)
             abort-rows    (mapv abort-row aborts)
             ;; Total elapsed: earliest event-time (decision when
             ;; present, else first teardown) → latest abort/teardown.
             start-t       (or (:t decision)
                               (some-> teardowns first :t)
                               (:time anchor))
             end-t         (or (some->> abort-rows
                                        (keep :t)
                                        seq
                                        (apply max))
                               (some->> teardowns
                                        (keep :t)
                                        seq
                                        (apply max))
                               (:time anchor))
             elapsed       (when (and (number? start-t) (number? end-t))
                             (max 0 (- end-t start-t)))]
         {:parent-decision  decision
          :child-teardowns  teardowns
          :effect-aborts    abort-rows
          :total-elapsed-ms elapsed
          :empty-kind       (if (empty? abort-rows) :no-aborts nil)})))))

;; ---- cascade summarisers ------------------------------------------------

(defn cascade-summary
  "One-line summary line for the visualiser footer / collapsed header.
  Pure data → string."
  [cascade]
  (let [teardowns (count (:child-teardowns cascade))
        aborts    (count (:effect-aborts cascade))
        elapsed   (:total-elapsed-ms cascade)
        elapsed-s (when elapsed (str elapsed "ms"))]
    (cond
      (= :no-trigger (:empty-kind cascade))
      "No cancellation cascade in the trace window."

      (= :no-aborts (:empty-kind cascade))
      (str teardowns " child destroyed"
           (when (not= 1 teardowns) "s")
           " · 0 effects aborted")

      :else
      (str/join " · "
                (cond-> [(str teardowns " child"
                              (when (not= 1 teardowns) "ren")
                              " destroyed")
                         (str aborts " effect"
                              (when (not= 1 aborts) "s")
                              " aborted")]
                  elapsed-s (conj (str elapsed-s " elapsed")))))))

(defn group-by-cancel-cause
  "Group `:effect-aborts` by `:cancel-cause`. Returns a map
  cause → vector-of-rows. Order within a group is input order."
  [cascade]
  (->> (:effect-aborts cascade)
       (group-by :cancel-cause)))

(def ^:const default-collapse-threshold
  "Per the bead's contract — collapse aborts by default when there are
  more than N. The view exposes a 'Show all N' expander."
  10)

(defn should-collapse?
  "True when the abort list should be collapsed by default. Pure fn
  for unit testability."
  ([cascade] (should-collapse? cascade default-collapse-threshold))
  ([cascade threshold]
   (> (count (:effect-aborts cascade)) threshold)))

;; ---- formatters (view-side, kept in .cljc for test reuse) --------------

(defn format-time-ms
  "Render a wall-clock `:time` value as a short label. Best-effort —
  if `t` is nil returns `\"—\"`."
  [t]
  (if (number? t)
    (str t "ms")
    "—"))

(defn format-event-vec
  "Pretty-print an event vector for display in the parent-decision row."
  [event-vec]
  (cond
    (nil? event-vec) "—"
    (vector? event-vec)
    (try (pr-str event-vec) (catch #?(:clj Throwable :cljs :default) _ (str event-vec)))
    :else (str event-vec)))

(defn format-fx-label
  "Human label for an abort row's `:fx` tag. Includes the request
  method/URL when present."
  [{:keys [fx req url]}]
  (let [method (some-> (:method req) name str/upper-case)
        u      (or url (:url req))]
    (case fx
      :http           (str "HTTP " (or method "") (when u " ") (or u ""))
      :ws             (str "WS send" (when (:event req)
                                       (str " " (pr-str (:event req)))))
      :after          (str ":after timer fire"
                           (when (:timer-id req)
                             (str " " (pr-str (:timer-id req)))))
      :machine-invoke (str "machine-invoke"
                           (when (:child-id req)
                             (str " " (pr-str (:child-id req)))))
      (str (name (or fx :unknown))))))
