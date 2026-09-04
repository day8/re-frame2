(ns panel-gallery.fixtures
  "Pure fixture builders for the Xray panel gallery.

  Story variants seed state by firing REAL Xray init events
  (`:rf.xray/sync-trace-buffer`, `:rf.xray/select-dispatch-id`)
  against the variant frame. Those handlers preserve `db` via `assoc`,
  so Story's `:rf.story/*` runtime slots survive untouched per
  `tools/story/spec/002-Runtime.md` §Coexistence with hosting
  application state.

  This namespace exposes pure builder functions that synthesize
  trace-event vectors shaped exactly as
  `re-frame.trace.projection/group-by-event` projects them:

      {:id <int>
       :op-type   :rf.event | :fx | :rf.sub/run | :view | :error | :warning | ...
       :operation :rf.event/dispatched | :rf.event/run-start | :rf.event/run-end | :rf.fx/do-fx | :rf.fx/handled | ...
       :tags      {:rf.trace/dispatch-id <int>
                   :rf.event/v       <event-vec>     ;; on :rf.event/dispatched
                   :rf.trace/phase       :run-start | :run-end
                   :rf.fx/id       <kw>            ;; on :rf.event/fx
                   :rf.sub/id      <kw>            ;; on :rf.sub/run
                   :rf.view/render-key  [<view-id> <args>] ;; on :view
                   :frame       <frame-id>}}

  The `cascade-evs` helper template is the gallery's single shared
  fixture surface, so any future panel projection change has one place
  to drive variant seeds from.

  Builders return plain vectors; the variant `:setup` slot wraps each
  in `[:rf.xray/sync-trace-buffer <buffer>]` for the seed dispatch.")

;; ---- domino-row builders ------------------------------------------------
;;
;; Eight-event template per cascade — one of each row a focused-epoch
;; panel renders. id-base lets a caller stack many cascades without id
;; collision. Optional frame-id rides on every emit so cross-frame
;; cascades surface a panel's `:frame` annotation in its cascade list.

(defn cascade-evs
  "Synthesize the eight trace events for a single cascade. The canonical
  gallery seed template.

  Returns a vector of trace-event maps shaped per
  `re-frame.trace.projection/group-by-event`."
  ([dispatch-id event-vec id-base]
   (cascade-evs dispatch-id event-vec id-base nil))
  ([dispatch-id event-vec id-base frame-id]
   (let [tag (cond-> {:dispatch-id dispatch-id}
               frame-id (assoc :frame frame-id))]
     [{:id (+ id-base 1) :op-type :rf.event :operation :rf.event/dispatched
       :tags (assoc tag :rf.event/v event-vec)}
      {:id (+ id-base 2) :op-type :rf.event :operation :rf.event/run-start
       :tags (assoc tag :rf.trace/phase :run-start)}
      {:id (+ id-base 3) :op-type :rf.event :operation :rf.event/run-end
       :tags (assoc tag :rf.trace/phase :run-end :duration-ms 4)}
      {:id (+ id-base 4) :op-type :rf.fx :operation :rf.fx/do-fx
       :tags tag}
      {:id (+ id-base 5) :op-type :rf.fx :operation :rf.fx/handled
       :tags (assoc tag :rf.fx/id :db)}
      {:id (+ id-base 6) :op-type :rf.fx :operation :rf.fx/handled
       :tags (assoc tag :rf.fx/id :dispatch)}
      {:id (+ id-base 7) :op-type :rf.sub :operation :rf.sub/run
       :tags (assoc tag :rf.sub/id :sub/foo)}
      {:id (+ id-base 8) :op-type :rf.view :operation :rf.view/render
       :tags (assoc tag :rf.view/render-key [:app/root nil])}])))

;; ---- buffer builders ----------------------------------------------------
;;
;; Each builder returns the trace-buffer vector ready to be passed
;; verbatim to `:rf.xray/sync-trace-buffer`. The seed event in
;; `core.cljs` writes the vector into the variant frame's app-db
;; under `:trace-buffer`; the `:rf.xray/trace-buffer` sub then reads
;; it on the standard reactive path.

(defn n-cascades
  "Build `n` shallow cascades, each with the canonical 8-row template
  and a unique `:dispatch-id` / event vector. Useful for cascade-list
  variants where the panel renders one row per cascade."
  [n]
  (->> (range n)
       (mapcat (fn [i]
                 (cascade-evs (+ 100 i)
                              [:demo/event-N i]
                              (* (inc i) 50))))
       vec))
