(ns panel-gallery.fixtures-trace
  "Pure fixture builders for the Xray Trace tab gallery.

  ## Epoch-scoped feed

  The Trace panel is a lens on the spine's FOCUSED EPOCH, not the
  global trace bus. Its sub `:rf.xray/trace-feed` joins:

    - `:rf.xray/focus`          — carries `:epoch-id`
    - `:rf.xray/epoch-history`  — the per-frame ring of `:rf/epoch-record`
                                   maps (the framework's settling records)

  and renders the focused epoch's `:trace-events` slice (the complete
  domino trail for one settling). It reads the focused epoch's slice,
  not the trace bus / `:trace-buffer` — seeding the bus produces an
  empty panel.

  Each variant therefore seeds via `:rf.xray/sync-epoch-history` — the
  canonical seed event (`epoch.cljs`) that `assoc`s the history vector
  into Xray's frame app-db under `:epoch-history`. The handler is
  tagged `{:rf.trace/no-emit? true}`; Story's `:rf.story/*` runtime
  slots survive untouched per `tools/story/spec/002-Runtime.md`
  §Coexistence with hosting application state.

  ## Focus resolution (head-fallback)

  No variant pins `:rf.xray/focus`. With no trace bus seeded there are
  no cascades, so `compose-focus` leaves the focus `:epoch-id` nil; the
  shared `panels.shared.focus-resolver` then applies its head-fallback:
  nil focus + non-empty history → `:focused`, resolving to
  the HEAD record `(peek epoch-history)`. `:rf.xray/epoch-history` is
  oldest-first, so the variant's representative epoch is placed LAST in
  the history vector and is what the panel renders. Multi-op variants
  put older epochs ahead of the focused one to exercise a realistic
  ring without changing which epoch renders.

  ## Epoch-record shape (per spec/004-AppDbDiff + re-frame.epoch)

  Each `:rf/epoch-record` the resolver looks up carries:

      {:epoch-id      <int>             ; stable id, ring-buffer key
       :frame         :rf/default
       :committed-at  <ms>
       :event-id      <kw>              ; first elem of :trigger-event
       :trigger-event <event-vec>
       :trace-events  [<trace-event> ...]}  ; the domino trail (what the
                                            ; Trace panel projects)

  The Trace panel reads ONLY `:epoch-id` (for the head/feed key) and
  `:trace-events` (the rows), so these records populate just enough for
  a faithful render.

  ## Trace-event shape (per Spec 009 §Trace bus + trace_helpers/project-row)

  Each event the trace panel projects carries:

      {:id          <int>
       :time        <ms>
       :op-type     <kw>            ;; :rf.event / :rf.fx / :rf.sub / :rf.view /
                                   ;;  :error / :warning / :info / ...
       :operation   <kw>
       :source      <kw-or-nil>
       :tags        {:rf.trace/dispatch-id  <int>
                     :rf.event/v            <event-vec>
                     :rf.event/origin       <kw>
                     :frame                 <kw>
                     :rf.trace/event-id     <kw>
                     :handler-id            <kw>
                     :source                <kw>
                     :severity              <kw>
                     :rf.sub/id             <kw>
                     :rf.fx/id              <kw>
                     :rf.view/render-key    <[view-id args]>
                     :reason                <string>
                     ...}}

  The flat Trace panel projects each raw event into a row
  carrying `Δt · stage · area-badge · what-happened · target/detail ·
  duration` via `trace_helpers/project-feed-from-epoch`. There is no
  chip-filtering UI (the focused epoch IS the scope) — the
  fixtures keep the event shape minimal but exercise the full op-family
  vocabulary so the STAGE column + colour-coded left edge (resolved
  through the Epoch panel's `panels.epoch.badge` taxonomy) light up
  across the canonical pipeline steps: DISPATCH · COEFFECT · HANDLER ·
  FLOW · SIDE EFFECTS · SUBSCRIPTIONS · VIEWS, plus the cross-cutting
  ERROR / WARNING severity tiers.")

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
                origin      (assoc :rf.event/origin origin)
                frame       (assoc :frame frame)
                event-id    (assoc :rf.trace/event-id event-id)
                handler-id  (assoc :handler-id handler-id)
                dispatch-id (assoc :rf.trace/dispatch-id dispatch-id)
                source      (assoc :source source)
                severity    (assoc :severity severity)
                event-vec   (assoc :rf.event/v event-vec)
                sub-id      (assoc :rf.sub/id sub-id)
                fx-id       (assoc :rf.fx/id fx-id)
                reason      (assoc :reason reason)
                render-key  (assoc :rf.view/render-key render-key))})

;; ---- buffer builders ----------------------------------------------------

(defn empty-buffer
  "No events — panel renders the :no-events empty-state copy."
  []
  [])

(defn ten-events-buffer
  "Ten events spanning the canonical op-types and a couple of frames /
  origins. Each row distinguishable; the STAGE column + colour-coded
  left edge light up across DISPATCH / HANDLER / SIDE EFFECTS /
  SUBSCRIPTIONS / VIEWS plus the ERROR / WARNING severity tiers."
  []
  (vec
    (concat
      ;; Three dispatch + run events for cascade 100 (default frame).
      [(ev {:id 1 :time 1000 :op-type :rf.event :operation :rf.event/dispatched
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :dispatch-id 100
            :event-vec [:cart/add :apple]})
       (ev {:id 2 :time 1001 :op-type :rf.event :operation :rf.event/run-end
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100})
       (ev {:id 3 :time 1002 :op-type :rf.fx :operation :rf.fx/handled
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100
            :fx-id :db})
       (ev {:id 4 :time 1003 :op-type :rf.sub :operation :rf.sub/run
            :source :ui :origin :app :frame :rf/default :dispatch-id 100
            :sub-id :cart/items-count})
       (ev {:id 5 :time 1004 :op-type :rf.view :operation :rf.view/render
            :source :ui :origin :app :frame :rf/default :dispatch-id 100})]
      ;; Cross-origin / cross-frame second cascade.
      [(ev {:id 6 :time 1100 :op-type :rf.event :operation :rf.event/dispatched
            :source :after-timer :origin :story :frame :rf/xray
            :event-id :story/tick :dispatch-id 101
            :event-vec [:story/tick]})
       (ev {:id 7 :time 1101 :op-type :rf.event :operation :rf.event/run-end
            :source :after-timer :origin :story :frame :rf/xray
            :event-id :story/tick :handler-id :story/tick-h :dispatch-id 101})
       (ev {:id 8 :time 1102 :op-type :rf.fx :operation :rf.fx/handled
            :source :after-timer :origin :story :frame :rf/xray
            :event-id :story/tick :dispatch-id 101 :fx-id :dispatch})]
      ;; Warning + error rows ride the severity colour on the left edge.
      [(ev {:id 9 :time 1200 :op-type :warning :operation :rf.warning/large-value-unschema'd
            :source :http :origin :app :frame :rf/default :dispatch-id 100
            :sub-id :user/profile :severity :warning
            :reason "payload truncated at 1MB"})
       (ev {:id 10 :time 1201 :op-type :error :operation :rf.error/handler-exception
            :source :http :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100
            :severity :error :reason "handler threw NPE"})])))

(defn n-events
  "Generate `n` events with cyclical axes — fans out across 4 op-types,
  3 origins, 3 frames, 4 sources. Useful for filling the buffer with
  realistic shape variety."
  [n]
  (let [op-pool      [:rf.event :rf.fx :rf.sub :rf.view]
        ops          {:rf.event :rf.event/dispatched
                      :rf.fx    :rf.fx/handled
                      :rf.sub   :rf.sub/run
                      :rf.view  :rf.view/render}
        source-pool  [:ui :after-timer :http :repl]
        origin-pool  [:app :pair :story :test]
        frame-pool   [:rf/default :rf/xray :tenant/alpha]
        sub-pool     [:auth/user :cart/items :ui/theme :perf/budget]
        fx-pool      [:db :dispatch :http :navigate]]
    (mapv (fn [i]
            (let [op    (nth op-pool (mod i (count op-pool)))
                  op-id (get ops op)
                  did   (+ 100 (quot i 4))
                  tag   (cond-> {:rf.trace/dispatch-id did
                                 :rf.event/origin (nth origin-pool (mod i (count origin-pool)))
                                 :frame  (nth frame-pool (mod i (count frame-pool)))
                                 :rf.trace/event-id (keyword (str "demo-" (mod i 5))
                                                    (str "event-" i))}
                          (= op :rf.sub) (assoc :rf.sub/id (nth sub-pool (mod i (count sub-pool))))
                          (= op :rf.fx)  (assoc :rf.fx/id (nth fx-pool (mod i (count fx-pool)))))]
              {:id        (+ i 1)
               :time      (+ 10000 (* 10 i))
               :op-type   op
               :operation op-id
               :source    (nth source-pool (mod i (count source-pool)))
               :tags      tag}))
          (range n))))

(defn hundred-events-buffer
  "One hundred events spanning all four op-types, three frames, three
  origins, four sources. A mid-density epoch — the flat list renders
  every row (no row cap)."
  []
  (n-events 100))

(defn thousand-events-buffer
  "One thousand events — a deep epoch. The flat list
  renders the whole epoch's trace in fire order; exercises the panel +
  the shared resizable-table under a large row count."
  []
  (n-events 1000))

(defn filtered-active-buffer
  "A buffer mixing two op-types (events + fx). The flat panel renders
  every row (no chip-filtering — the focused epoch IS
  the scope); used by the mixed-op-types variant."
  []
  (vec
    (concat
      (for [i (range 5)]
        (ev {:id (+ i 1) :time (+ 1000 i) :op-type :rf.event :operation :rf.event/dispatched
             :source :ui :origin :app :frame :rf/default :dispatch-id (+ 100 i)
             :event-id :cart/add :event-vec [:cart/add :apple]}))
      (for [i (range 5)]
        (ev {:id (+ i 6) :time (+ 1500 i) :op-type :rf.fx :operation :rf.fx/handled
             :source :after-timer :origin :app :frame :rf/default :dispatch-id (+ 100 i)
             :fx-id :db})))))

(defn redacted-buffer
  "A buffer whose dispatched-event payload carries `:rf/redacted`
  markers (per Spec 009 §Privacy). The row's target/detail column
  renders the event vector with the marker verbatim, and clicking the
  row opens the edn-inspector on the raw trace MAP (the marker survives
  there too) — the upstream emit sets it; the panel surfaces it."
  []
  [(ev {:id 1 :time 1000 :op-type :rf.event :operation :rf.event/dispatched
        :source :ui :origin :app :frame :rf/default
        :event-id :auth/sign-in :dispatch-id 100
        :event-vec [:auth/sign-in {:email "ada@example.com"
                                   :password :rf/redacted
                                   :totp :rf/redacted}]})
   (ev {:id 2 :time 1001 :op-type :rf.event :operation :rf.event/run-end
        :source :ui :origin :app :frame :rf/default
        :event-id :auth/sign-in :handler-id :auth/sign-in-h :dispatch-id 100})
   (ev {:id 3 :time 1002 :op-type :rf.fx :operation :rf.fx/handled
        :source :ui :origin :app :frame :rf/default
        :event-id :auth/sign-in :dispatch-id 100 :fx-id :http})])

(defn error-buffer
  "A buffer where every row is an issue (error / warning / info) —
  exercises the cross-cutting severity rendering: the left EDGE rides
  the severity colour over the stage colour and the Δt leads with `!`
  so a failure stands out (spec/023 §7), across all three levels."
  []
  [(ev {:id 1 :time 1000 :op-type :error :operation :rf.error/handler-exception
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
;;   A. Cross-frame mix — the per-row `:frame` projection surfaces 3+
;;      frames across the flat list (per Spec 009 §Canonical per-frame
;;      routing key).
;;   B. Source-coord population — every emit inside a dispatch carries
;;      :rf.trace/trigger-handler :source-coord; the per-row `↗`
;;      open-in-editor glyph (riding the target/detail column) is the
;;      trace panel's jump-to-source affordance.

(defn cross-frame-buffer
  "Buffer spanning three frames evenly — exercises the panel's per-row
  `:frame` projection across the flat list. Panel-specific axis A."
  []
  (vec
    (for [i (range 12)]
      (let [fid (nth [:rf/default :rf/xray :tenant/alpha] (mod i 3))]
        (ev {:id (+ i 1) :time (+ 2000 (* 5 i))
             :op-type :rf.event :operation :rf.event/dispatched
             :source :ui :origin :app :frame fid :dispatch-id (+ 200 i)
             :event-id :tenant/poke :event-vec [:tenant/poke i]})))))

(defn source-coord-buffer
  "Buffer where every event carries a `:rf.trace/trigger-handler`
  `:source-coord` slot (per Spec 009 §Source-coord). Exercises the
  panel's per-row `↗` open-in-editor glyph. Panel-specific axis B."
  []
  (vec
    (for [i (range 6)]
      (-> (ev {:id (+ i 1) :time (+ 3000 (* 5 i))
               :op-type :rf.event :operation :rf.event/dispatched
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
      [(ev {:id 1 :time 1000 :op-type :rf.event :operation :rf.event/dispatched
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :dispatch-id 100
            :event-vec [:cart/add :apple]})
       (ev {:id 2 :time 1001 :op-type :rf.event :operation :rf.event/run-end
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :handler-id :cart/add-h :dispatch-id 100})]
      ;; Three flow-recomputed events — flows fire at the outermost
      ;; :after, right after the handler, transforming the pending :db
      ;; BEFORE the single deferred install. So computed emits precede
      ;; the :db commit (real emit order: computed → db-changed).
      ;; Operation surface per Spec 009 §Flow trace.
      [(ev {:id 3 :time 1002 :op-type :rf.flow/computed
            :operation :rf.flow/computed
            :source :flow :origin :app :frame :rf/default
            :dispatch-id 100 :sub-id :cart/total})
       (ev {:id 4 :time 1003 :op-type :rf.flow/computed
            :operation :rf.flow/computed
            :source :flow :origin :app :frame :rf/default
            :dispatch-id 100 :sub-id :cart/item-count})
       (ev {:id 5 :time 1004 :op-type :rf.flow/computed
            :operation :rf.flow/computed
            :source :flow :origin :app :frame :rf/default
            :dispatch-id 100 :sub-id :cart/badge})]
      ;; The :db commit — the single deferred install fires AFTER the
      ;; flows have reshaped the pending db.
      [(ev {:id 6 :time 1005 :op-type :rf.fx :operation :rf.fx/handled
            :source :ui :origin :app :frame :rf/default
            :event-id :cart/add :dispatch-id 100 :fx-id :db})
       ;; Downstream render driven by the flow.
       (ev {:id 7 :time 1006 :op-type :rf.view :operation :rf.view/render
            :source :ui :origin :app :frame :rf/default :dispatch-id 100
            :render-key [:cart/badge nil]})])))

;; ---- epoch-record + history builders ------------------------------------
;;
;; The Trace panel reads the FOCUSED EPOCH's `:trace-events`, resolved
;; via the shared focus-resolver over `:rf.xray/epoch-history`. These
;; builders wrap the per-event buffer builders above into `:rf/epoch-
;; record` maps so each variant seeds an epoch the panel can project.

(defn epoch-record
  "Build a minimal `:rf/epoch-record` carrying a `:trace-events` slice.
  The Trace panel reads only `:epoch-id` (head/feed key) and
  `:trace-events` (the domino-trail rows); the remaining slots mirror
  the framework's settling-record shape for fidelity."
  [{:keys [epoch-id event trace-events]}]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  (* 1000 (or epoch-id 0))
   :event-id      (first event)
   :trigger-event event
   :trace-events  (vec trace-events)})

(defn single-epoch-history
  "Wrap one trace-event vector into a one-record history. The single
  record is the head (oldest-first vector of one) so the focus-
  resolver's head-fallback renders it. `event` is the epoch's trigger
  event (cosmetic — surfaces in the record's `:event-id`)."
  [{:keys [epoch-id event trace-events]}]
  [(epoch-record {:epoch-id     epoch-id
                  :event        event
                  :trace-events trace-events})])

(defn empty-trace-history
  "One epoch whose `:trace-events` slice is empty — drives the panel's
  `:no-events` empty-state ('No events.'). Distinct from no history at
  all (`:no-focus`): the focus resolves to a real record that simply
  carries no trace rows."
  []
  (single-epoch-history
    {:epoch-id 1 :event [:counter/init] :trace-events (empty-buffer)}))

(defn short-trace-history
  "One epoch whose domino trail is the ten-event cascade — a normal,
  representative settling spanning every canonical op-type."
  []
  (single-epoch-history
    {:epoch-id 2 :event [:cart/add :apple]
     :trace-events (ten-events-buffer)}))

(defn medium-trace-history
  "One epoch carrying a 100-row domino trail. A mid-density epoch —
  the flat list renders every row (no row cap)."
  []
  (single-epoch-history
    {:epoch-id 3 :event [:dashboard/recompute-all]
     :trace-events (hundred-events-buffer)}))

(defn long-trace-history
  "One epoch carrying a 1000-row trail — a deep epoch. The flat list
  renders the whole epoch's trace in fire order (oldest-first)."
  []
  (single-epoch-history
    {:epoch-id 4 :event [:storm/replay]
     :trace-events (thousand-events-buffer)}))

(defn errors-trace-history
  "One epoch whose trail is all issues (errors / warnings / info) —
  every row carries a severity tier."
  []
  (single-epoch-history
    {:epoch-id 5 :event [:report/upload]
     :trace-events (error-buffer)}))

(defn flows-trace-history
  "A multi-op history: a prior counter epoch precedes the focused
  flow-cascade epoch. The focused (head) epoch carries the
  `:cart/add` cascade with three `:rf.flow/computed` recompute rows
  and a downstream render — the panel renders the flow op-type
  alongside the dominoes. The leading epoch exercises a realistic
  ring while head-fallback keeps the flow epoch in view."
  []
  [(epoch-record {:epoch-id 6 :event [:counter/inc]
                  :trace-events (ten-events-buffer)})
   (epoch-record {:epoch-id 7 :event [:cart/add :apple]
                  :trace-events (flows-buffer)})])

(defn mixed-op-types-history
  "One epoch whose trail mixes event + fx op-types (no chip-filtering
  — the feed renders every row)."
  []
  (single-epoch-history
    {:epoch-id 8 :event [:cart/add :apple]
     :trace-events (filtered-active-buffer)}))

(defn redacted-trace-history
  "One epoch whose dispatched-event payload carries `:rf/redacted`
  markers on `:password` + `:totp` — the panel surfaces the marker
  verbatim per Spec 009 §Privacy."
  []
  (single-epoch-history
    {:epoch-id 9 :event [:auth/sign-in]
     :trace-events (redacted-buffer)}))

(defn cross-frame-history
  "One epoch whose trail spans three frames evenly — exercises the
  panel's per-row frame projection across the full ladder."
  []
  (single-epoch-history
    {:epoch-id 10 :event [:tenant/poke]
     :trace-events (cross-frame-buffer)}))

(defn source-coord-history
  "One epoch whose every row carries a `:source-coord` slot — exercises
  the panel's per-row `↗` open-in-editor glyph (jump-to-editor)."
  []
  (single-epoch-history
    {:epoch-id 11 :event [:counter/inc]
     :trace-events (source-coord-buffer)}))
