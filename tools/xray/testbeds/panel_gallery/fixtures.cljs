(ns panel-gallery.fixtures
  "Trace-buffer seeds for the Xray panel gallery's chrome, settings and
  filters variants.

  Story variants seed state by firing REAL Xray init events
  (`:rf.xray/sync-trace-buffer`, `:rf.xray/select-dispatch-id`)
  against the variant frame. Those handlers preserve `db` via `assoc`,
  so Story's `:rf.story/*` runtime slots survive untouched per
  `tools/story/spec/002-Runtime.md` §Coexistence with hosting
  application state.

  ## Built from the canonical builders, not typed here

  Every event comes from `day8.re-frame2-xray.test-helpers.trace-event-builders`
  (rf2-tyivx), the one namespace that mirrors the substrate's emit shapes,
  and is then given the envelope `trace/emit!` stamps on every emit inside
  a dispatch run: a unique `:id`, a `:time`, and the run's
  `:rf.trace/dispatch-id` + `:frame` under `:tags`.

  That envelope is the whole point. `re-frame.trace.projection/group-by-event`
  groups the L2 event list by `[:tags :rf.trace/dispatch-id]`, and an event
  without one lands in the `:ungrouped` bucket the L2 list hides. The
  hand-typed template this replaced put `:dispatch-id` under `:tags`, so
  every seeded cascade grouped to `:ungrouped` and all seventeen variants
  that seed from here painted an EMPTY event list while their docs promised
  rows (rf2-y8doi.28). `panel_gallery_trace_fixtures_cljs_test` now pins
  that these seeds project to one visible L2 row per cascade.

  Builders return plain vectors; the variant `:setup` slot wraps each
  in `[:rf.xray/sync-trace-buffer <buffer>]` for the seed dispatch."
  (:require [day8.re-frame2-xray.test-helpers.trace-event-builders :as teb]))

(defn- in-run
  "Stamp a builder event with the envelope `trace/emit!` gives an emit inside
  dispatch run `dispatch-id` on `frame-id`."
  [ev id dispatch-id frame-id]
  (-> ev
      (assoc :id id :time (* 10 id))
      (update :tags assoc :rf.trace/dispatch-id dispatch-id :frame frame-id)))

(defn cascade
  "The trace events of ONE dispatch run of `event-vec`: dispatched, handler
  run-end, the fx pass and its two handled fx, one sub run and one view
  render. `id-base` lets a caller stack many cascades without id collision."
  ([dispatch-id event-vec id-base]
   (cascade dispatch-id event-vec id-base :rf/default))
  ([dispatch-id event-vec id-base frame-id]
   (->> [(teb/dispatched-ev event-vec :ui)
         (teb/run-end-ev 4)
         (teb/do-fx-ev {:db {} :fx [[:dispatch [:demo/next]]]})
         (teb/fx-handled-ev :db {} 0)
         (teb/fx-handled-ev :dispatch [:demo/next] 0)
         (teb/sub-run-ev [:sub/foo] true nil 1 1)
         (teb/view-rendered-ev :app/root [[:sub/foo]] 1
                               {:render-key [:app/root nil]})]
        (map-indexed (fn [i ev] (in-run ev (+ id-base i 1) dispatch-id frame-id)))
        vec)))

(defn cascades
  "`n` cascades, each a distinct dispatch run of `[:demo/event-N i]` — one L2
  event-list row apiece. The seed the chrome, settings and filters variants
  pass to `:rf.xray/sync-trace-buffer`."
  [n]
  (->> (range n)
       (mapcat (fn [i] (cascade (+ 100 i) [:demo/event-N i] (* (inc i) 50))))
       vec))
