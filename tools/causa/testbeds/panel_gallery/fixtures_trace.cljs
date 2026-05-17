(ns panel-gallery.fixtures-trace
  "Pure fixture builders for the Causa Trace tab gallery (rf2-sszlr —
  rebuild for new 6-tab Causa shape).

  The trace panel reads its rows from `:rf.causa/trace-feed`, a
  composite over:

    - `:rf.causa/trace-buffer`   — the raw trace ring buffer
    - `:rf.causa/trace-filters`  — the active 9-axis filter map

  Each variant seeds via `:rf.causa/sync-trace-buffer` — the canonical
  seed event used by `mount.cljs/open!` to publish the trace-bus
  contents into Causa's frame app-db. The handler `assoc`s the buffer
  vector into Causa's frame app-db; Story's `:rf.story/*` runtime slots
  survive untouched per `tools/story/spec/002-Runtime.md` §Coexistence
  with hosting application state.

  ## Trace-event shape (per Spec 009 §Trace bus + trace_helpers/project-row)

  Each event the trace panel projects carries:

      {:id          <int>
       :time        <ms>
       :op-type     <kw>            ;; :event / :fx / :sub/run / :view /
                                   ;;  :error / :warning / :info / ...
       :operation   <kw>
       :source      <kw-or-nil>
       :tags        {:dispatch-id  <int>
                     :event        <event-vec>
                     :origin       <kw>
                     :frame        <kw>
                     :event-id     <kw>
                     :handler-id   <kw>
                     :source       <kw>
                     :severity     <kw>
                     :sub-id       <kw>
                     :fx-id        <kw>
                     :render-key   <[view-id args]>
                     :reason       <string>
                     ...}}

  The fixtures here keep the shape minimal but exercise all 9 filter
  axes the trace panel renders chip rows for: op-type / severity /
  source / origin / frame / operation / event-id / handler-id /
  dispatch-id.")

;; ---- per-event builders -------------------------------------------------

(defn- ev
  "Build a single trace event with the canonical 9-axis tag surface."
  [{:keys [id time op-type operation source origin frame
           event-id handler-id dispatch-id severity event-vec
           sub-id fx-id reason render-key]
    :or {time 1000}}]
  {:id        id
   :time      time
   :op-type   op-type
   :operation operation
   :source    source
   :tags      (cond-> {}
                origin      (assoc :origin origin)
                frame       (assoc :frame frame)
                event-id    (assoc :event-id event-id)
                handler-id  (assoc :handler-id handler-id)
                dispatch-id (assoc :dispatch-id dispatch-id)
                source      (assoc :source source)
                severity    (assoc :severity severity)
                event-vec   (assoc :event event-vec)
                sub-id      (assoc :sub-id sub-id)
                fx-id       (assoc :fx-id fx-id)
                reason      (assoc :reason reason)
                render-key  (assoc :render-key render-key))})

;; ---- buffer builders ----------------------------------------------------

(defn empty-buffer
  "No events — panel renders the :no-events empty-state copy."
  []
  [])

(defn ten-events-buffer
  "Ten events spanning the canonical op-types and a couple of frames /
  origins. Each row distinguishable; chip rows surface op-type +
  source + origin + frame axes with ≥2 values each."
  []
  (vec
    (concat
      ;; Three dispatch + run events for cascade 100 (default frame).
      [(ev {:id 1 :time 1000 :op-type :event :operation :event/dispatched
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :dispatch-id 100
            :event-vec [:cart/add :apple]})
       (ev {:id 2 :time 1001 :op-type :event :operation :event
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100})
       (ev {:id 3 :time 1002 :op-type :fx :operation :rf.fx/handled
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100
            :fx-id :db})
       (ev {:id 4 :time 1003 :op-type :sub/run :operation :sub/run
            :source :ui :origin :app :frame :rf/default :dispatch-id 100
            :sub-id :cart/items-count})
       (ev {:id 5 :time 1004 :op-type :view :operation :view/render
            :source :ui :origin :app :frame :rf/default :dispatch-id 100})]
      ;; Cross-origin / cross-frame second cascade.
      [(ev {:id 6 :time 1100 :op-type :event :operation :event/dispatched
            :source :timer :origin :story :frame :rf/causa
            :event-id :story/tick :dispatch-id 101
            :event-vec [:story/tick]})
       (ev {:id 7 :time 1101 :op-type :event :operation :event
            :source :timer :origin :story :frame :rf/causa
            :event-id :story/tick :handler-id :story/tick-h :dispatch-id 101})
       (ev {:id 8 :time 1102 :op-type :fx :operation :rf.fx/handled
            :source :timer :origin :story :frame :rf/causa
            :event-id :story/tick :dispatch-id 101 :fx-id :dispatch})]
      ;; Warning + error rows surface the severity axis chip-row.
      [(ev {:id 9 :time 1200 :op-type :warning :operation :rf.warning/large-value-unschema'd
            :source :http :origin :app :frame :rf/default :dispatch-id 100
            :sub-id :user/profile :severity :warning
            :reason "payload truncated at 1MB"})
       (ev {:id 10 :time 1201 :op-type :error :operation :rf.error/handler-threw
            :source :http :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100
            :severity :error :reason "handler threw NPE"})])))

(defn n-events
  "Generate `n` events with cyclical axes — fans out across 4 op-types,
  3 origins, 3 frames, 4 sources. Useful for filling the buffer with
  realistic shape variety."
  [n]
  (let [op-pool      [:event :fx :sub/run :view]
        ops          {:event :event/dispatched
                      :fx    :rf.fx/handled
                      :sub/run :sub/run
                      :view  :view/render}
        source-pool  [:ui :timer :http :devtools]
        origin-pool  [:app :pair :story :test]
        frame-pool   [:rf/default :rf/causa :tenant/alpha]
        sub-pool     [:auth/user :cart/items :ui/theme :perf/budget]
        fx-pool      [:db :dispatch :http :navigate]]
    (mapv (fn [i]
            (let [op    (nth op-pool (mod i (count op-pool)))
                  op-id (get ops op)
                  did   (+ 100 (quot i 4))
                  tag   (cond-> {:dispatch-id did
                                 :origin (nth origin-pool (mod i (count origin-pool)))
                                 :frame  (nth frame-pool (mod i (count frame-pool)))
                                 :event-id (keyword (str "demo-" (mod i 5))
                                                    (str "event-" i))}
                          (= op :sub/run) (assoc :sub-id (nth sub-pool (mod i (count sub-pool))))
                          (= op :fx)      (assoc :fx-id (nth fx-pool (mod i (count fx-pool)))))]
              {:id        (+ i 1)
               :time      (+ 10000 (* 10 i))
               :op-type   op
               :operation op-id
               :source    (nth source-pool (mod i (count source-pool)))
               :tags      tag}))
          (range n))))

(defn hundred-events-buffer
  "One hundred events spanning all four op-types, three frames, three
  origins, four sources. The cap (200 per common/panel-row-cap) is not
  hit; cap-eviction indicator stays quiet."
  []
  (n-events 100))

(defn thousand-events-buffer
  "One thousand events — exercises the 200-row cap (common/panel-row-cap)
  and surfaces the overflow indicator. Per
  `overflow_indicator.cljc` §capped-list, the panel renders 200 rows +
  one '... N rows hidden' indicator row at the head."
  []
  (n-events 1000))

(defn filtered-active-buffer
  "A buffer with two op-types and a pre-active filter (set by the
  variant after seeding via :rf.causa/set-trace-filter). The panel
  renders the chip ladder with the active chip highlit. Returns the
  buffer; the variant fires the filter event after seed."
  []
  (vec
    (concat
      (for [i (range 5)]
        (ev {:id (+ i 1) :time (+ 1000 i) :op-type :event :operation :event/dispatched
             :source :ui :origin :app :frame :rf/default :dispatch-id (+ 100 i)
             :event-id :cart/add :event-vec [:cart/add :apple]}))
      (for [i (range 5)]
        (ev {:id (+ i 6) :time (+ 1500 i) :op-type :fx :operation :rf.fx/handled
             :source :timer :origin :app :frame :rf/default :dispatch-id (+ 100 i)
             :fx-id :db})))))

(defn redacted-buffer
  "A buffer whose dispatched-event payload carries `:rf/redacted`
  markers (per Spec 009 §Privacy). The panel's description column
  renders the marker verbatim — the upstream emit sets it; the panel
  surfaces it."
  []
  [(ev {:id 1 :time 1000 :op-type :event :operation :event/dispatched
        :source :ui :origin :app :frame :rf/default
        :event-id :auth/sign-in :dispatch-id 100
        :event-vec [:auth/sign-in {:email "ada@example.com"
                                   :password :rf/redacted
                                   :totp :rf/redacted}]})
   (ev {:id 2 :time 1001 :op-type :event :operation :event
        :source :ui :origin :app :frame :rf/default
        :event-id :auth/sign-in :handler-id :auth/sign-in-h :dispatch-id 100})
   (ev {:id 3 :time 1002 :op-type :fx :operation :rf.fx/handled
        :source :ui :origin :app :frame :rf/default
        :event-id :auth/sign-in :dispatch-id 100 :fx-id :http})])

(defn error-buffer
  "A buffer where every row is an issue (error / warning / info) —
  exercises the severity chip row with all three levels populated."
  []
  [(ev {:id 1 :time 1000 :op-type :error :operation :rf.error/handler-threw
        :source :ui :origin :app :frame :rf/default
        :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100
        :severity :error :reason "handler threw NPE on :cart/add"})
   (ev {:id 2 :time 1010 :op-type :error :operation :rf.error/fx-failed
        :source :http :origin :app :frame :rf/default
        :event-id :report/upload :dispatch-id 101
        :severity :error :reason "HTTP 503 — upstream timeout"})
   (ev {:id 3 :time 1020 :op-type :warning :operation :rf.warning/large-value-unschema'd
        :source :http :origin :app :frame :rf/default :dispatch-id 100
        :sub-id :user/profile :severity :warning
        :reason "payload truncated at 1MB"})
   (ev {:id 4 :time 1030 :op-type :warning :operation :rf.warning/handler-replaced
        :source :devtools :origin :app :frame :rf/default
        :event-id :devtools/hot-reload :dispatch-id 102
        :severity :warning :reason "handler :cart/add replaced"})
   (ev {:id 5 :time 1040 :op-type :info :operation :rf.info/snapshot-restored
        :source :devtools :origin :app :frame :rf/default :dispatch-id 103
        :severity :info :reason "Restored from snapshot #42"})])

;; ---- panel-specific axes -----------------------------------------------
;;
;; Two extra axes the trace panel uniquely exercises:
;;
;;   A. Cross-frame mix — the :frame chip row surfaces 3+ values
;;      side-by-side (per Spec 009 §Canonical per-frame routing key).
;;   B. Source-coord population — every emit inside a dispatch carries
;;      :rf.trace/trigger-handler :source-coord; the per-row source-
;;      coord chip is the trace panel's signature affordance.

(defn cross-frame-buffer
  "Buffer spanning three frames evenly — exercises the panel's :frame
  chip row at full ladder. Panel-specific axis A."
  []
  (vec
    (for [i (range 12)]
      (let [fid (nth [:rf/default :rf/causa :tenant/alpha] (mod i 3))]
        (ev {:id (+ i 1) :time (+ 2000 (* 5 i))
             :op-type :event :operation :event/dispatched
             :source :ui :origin :app :frame fid :dispatch-id (+ 200 i)
             :event-id :tenant/poke :event-vec [:tenant/poke i]})))))

(defn source-coord-buffer
  "Buffer where every event carries a `:rf.trace/trigger-handler`
  `:source-coord` slot (per Spec 009 §Source-coord). Exercises the
  panel-specific source-coord chip rendering. Panel-specific axis B."
  []
  (vec
    (for [i (range 6)]
      (-> (ev {:id (+ i 1) :time (+ 3000 (* 5 i))
               :op-type :event :operation :event/dispatched
               :source :ui :origin :app :frame :rf/default :dispatch-id (+ 300 i)
               :event-id :counter/inc :event-vec [:counter/inc]})
          (assoc :rf.trace/trigger-handler
                 {:source-coord {:file (str "src/cart/handlers.cljs")
                                 :line (+ 10 i)}})))))

(defn flows-buffer
  "Buffer carrying re-frame **Flow** trace events
  (`:rf.flow/*` operations) mixed with regular event emits — pins
  the panel's rendering of the flow op-type alongside dominoes."
  []
  (vec
    (concat
      ;; Cascade rooted on a flow-triggering event.
      [(ev {:id 1 :time 1000 :op-type :event :operation :event/dispatched
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :dispatch-id 100
            :event-vec [:cart/add :apple]})
       (ev {:id 2 :time 1001 :op-type :event :operation :event
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100})
       (ev {:id 3 :time 1002 :op-type :fx :operation :rf.fx/handled
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :dispatch-id 100 :fx-id :db})]
      ;; Three flow-recomputed events — flow propagates downstream of
      ;; the db write. Operation surface per Spec 009 §Flow trace.
      [(ev {:id 4 :time 1003 :op-type :rf.flow/computed
            :operation :rf.flow/computed
            :source :flow :origin :app :frame :rf/default
            :dispatch-id 100 :sub-id :cart/total})
       (ev {:id 5 :time 1004 :op-type :rf.flow/computed
            :operation :rf.flow/computed
            :source :flow :origin :app :frame :rf/default
            :dispatch-id 100 :sub-id :cart/item-count})
       (ev {:id 6 :time 1005 :op-type :rf.flow/computed
            :operation :rf.flow/computed
            :source :flow :origin :app :frame :rf/default
            :dispatch-id 100 :sub-id :cart/badge})
       ;; Downstream render driven by the flow.
       (ev {:id 7 :time 1006 :op-type :view :operation :view/render
            :source :ui :origin :app :frame :rf/default :dispatch-id 100
            :render-key [:cart/badge nil]})])))
