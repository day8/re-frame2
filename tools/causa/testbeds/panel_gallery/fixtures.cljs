(ns panel-gallery.fixtures
  "Pure fixture builders for the Causa panel gallery (rf2-1o7mp).

  Story variants seed state by firing REAL Causa init events
  (`:rf.causa/sync-trace-buffer`, `:rf.causa/select-dispatch-id`)
  against the variant frame. Those handlers preserve `db` via `assoc`,
  so Story's `:rf.story/*` runtime slots survive untouched per
  `tools/story/spec/002-Runtime.md` §Coexistence with hosting
  application state.

  This namespace exposes pure builder functions that synthesize
  trace-event vectors shaped exactly as
  `re-frame.trace.projection/group-cascades` projects them:

      {:id <int>
       :op-type   :event | :fx | :sub/run | :view | :error | :warning | ...
       :operation :event/dispatched | :event | :event/do-fx | :rf.fx/handled | ...
       :tags      {:dispatch-id <int>
                   :event       <event-vec>     ;; on :event/dispatched
                   :phase       :run-start | :run-end
                   :fx-id       <kw>            ;; on :fx
                   :sub-id      <kw>            ;; on :sub/run
                   :render-key  [<view-id> <args>] ;; on :view
                   :frame       <frame-id>}}

  The mirror of this shape lives in
  `tools/causa/test/day8/re_frame2_causa/panels/event_detail_cljs_test.cljs`
  (`cascade-evs`); the gallery uses the same template so any future
  panel projection change shows up identically in both places.

  Builders return plain vectors; the variant `:events` slot wraps each
  in `[:rf.causa/sync-trace-buffer <buffer>]` for the seed dispatch.")

;; ---- domino-row builders ------------------------------------------------
;;
;; Eight-event template per cascade — one of each row the event-detail
;; panel renders. id-base lets a caller stack many cascades without id
;; collision. Optional frame-id rides on every emit so cross-frame
;; cascades surface the panel's `:frame` annotation in the cascade list.

(defn cascade-evs
  "Synthesize the eight trace events for a single cascade. Mirrors the
  `cascade-evs` helper in event_detail_cljs_test so the gallery exercises
  exactly the rows the unit tests pin.

  Returns a vector of trace-event maps shaped per
  `re-frame.trace.projection/group-cascades`."
  ([dispatch-id event-vec id-base]
   (cascade-evs dispatch-id event-vec id-base nil))
  ([dispatch-id event-vec id-base frame-id]
   (let [tag (cond-> {:dispatch-id dispatch-id}
               frame-id (assoc :frame frame-id))]
     [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
       :tags (assoc tag :event event-vec)}
      {:id (+ id-base 2) :op-type :event :operation :event
       :tags (assoc tag :phase :run-start)}
      {:id (+ id-base 3) :op-type :event :operation :event
       :tags (assoc tag :phase :run-end :duration-ms 4)}
      {:id (+ id-base 4) :op-type :event :operation :event/do-fx
       :tags tag}
      {:id (+ id-base 5) :op-type :fx :operation :rf.fx/handled
       :tags (assoc tag :fx-id :db)}
      {:id (+ id-base 6) :op-type :fx :operation :rf.fx/handled
       :tags (assoc tag :fx-id :dispatch)}
      {:id (+ id-base 7) :op-type :sub/run :operation :sub/run
       :tags (assoc tag :sub-id :sub/foo)}
      {:id (+ id-base 8) :op-type :view :operation :view/render
       :tags (assoc tag :render-key [:app/root nil])}])))

(defn cascade-with-counts
  "Build a cascade with caller-controlled fanout. `counts` is a map of
  `{:effects N :subs N :renders N}`; each non-zero count emits that
  many additional rows of the matching `:op-type`. Always emits the
  three baseline event/do-fx rows so the cascade has a recognisable
  spine; never emits the eighth `:view/render` row when `:renders 0`.

  Synthetic fx-ids / sub-ids / render-keys cycle through a small pool
  so the panel renders distinguishable values rather than 30 identical
  rows."
  [{:keys [dispatch-id event-vec id-base frame-id
           effects subs renders]
    :or {effects 1 subs 0 renders 0}}]
  (let [tag (cond-> {:dispatch-id dispatch-id}
              frame-id (assoc :frame frame-id))
        spine [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
                :tags (assoc tag :event event-vec)}
               {:id (+ id-base 2) :op-type :event :operation :event
                :tags (assoc tag :phase :run-start)}
               {:id (+ id-base 3) :op-type :event :operation :event
                :tags (assoc tag :phase :run-end :duration-ms 7)}
               {:id (+ id-base 4) :op-type :event :operation :event/do-fx
                :tags tag}]
        fx-pool [:db :dispatch :http :rf.machine/transition :rf.flow/computed
                 :navigate :persist :metrics]
        sub-pool [:auth/user :route/active :counter/value :cart/items
                  :ui/theme :i18n/locale :session/heartbeat :feature/flags
                  :perf/budget :error/recent]
        render-pool [[:app/root nil] [:nav/bar nil] [:counter/badge nil]
                     [:cart/list nil] [:auth/menu {:variant :compact}]
                     [:perf/ribbon nil] [:settings/tab {:tab :general}]
                     [:modal/host nil]]
        fx-rows (mapv (fn [i]
                        {:id (+ id-base 100 i) :op-type :fx :operation :rf.fx/handled
                         :tags (assoc tag :fx-id (nth fx-pool (mod i (count fx-pool))))})
                      (range effects))
        sub-rows (mapv (fn [i]
                         {:id (+ id-base 1000 i) :op-type :sub/run :operation :sub/run
                          :tags (assoc tag :sub-id (nth sub-pool (mod i (count sub-pool))))})
                       (range subs))
        render-rows (mapv (fn [i]
                            {:id (+ id-base 10000 i) :op-type :view :operation :view/render
                             :tags (assoc tag :render-key
                                          (nth render-pool (mod i (count render-pool))))})
                          (range renders))]
    (-> []
        (into spine)
        (into fx-rows)
        (into sub-rows)
        (into render-rows))))

(defn cascade-with-other
  "Single cascade carrying non-domino rows under `:other` — errors,
  warnings, and machine transitions. Exercises `event-detail`'s
  `other-row` render branch."
  [dispatch-id id-base]
  (let [tag {:dispatch-id dispatch-id}]
    [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
      :tags (assoc tag :event [:user/save-profile {:id 7}])}
     {:id (+ id-base 2) :op-type :event :operation :event
      :tags (assoc tag :phase :run-start)}
     {:id (+ id-base 3) :op-type :event :operation :event
      :tags (assoc tag :phase :run-end :duration-ms 12)}
     {:id (+ id-base 4) :op-type :event :operation :event/do-fx
      :tags tag}
     {:id (+ id-base 5) :op-type :fx :operation :rf.fx/handled
      :tags (assoc tag :fx-id :db)}
     ;; Non-domino rows — surface under :other.
     {:id (+ id-base 6) :op-type :rf.error/handler-exception
      :operation :rf.error/handler-exception
      :tags (assoc tag :event [:user/save-profile {:id 7}])}
     {:id (+ id-base 7) :op-type :rf.warning/large-value-unschema'd
      :operation :rf.warning/large-value-unschema'd
      :tags (assoc tag :sub-id :user/profile)}
     {:id (+ id-base 8) :op-type :rf.machine/transition
      :operation :rf.machine/transition
      :tags (assoc tag :machine-id :user/save :from :idle :to :saving)}]))

;; ---- buffer builders ----------------------------------------------------
;;
;; Each builder returns the trace-buffer vector ready to be passed
;; verbatim to `:rf.causa/sync-trace-buffer`. The seed event in
;; `core.cljs` writes the vector into the variant frame's app-db
;; under `:trace-buffer`; the `:rf.causa/trace-buffer` sub then reads
;; it on the standard reactive path.

(defn empty-buffer [] [])

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

(defn n-cascades-cross-frame
  "Like `n-cascades` but every other cascade rides on a non-default
  frame-id so the panel's per-row frame annotation is exercised."
  [n]
  (->> (range n)
       (mapcat (fn [i]
                 (let [fid (case (mod i 3)
                             0 nil
                             1 :tenant/alpha
                             2 :tenant/beta)]
                   (cascade-evs (+ 200 i)
                                [:tenant/poke i]
                                (* (inc i) 50)
                                fid))))
       vec))

(defn deep-nested-buffer
  "A single root cascade plus four child cascades simulating
  programmatic re-dispatch from each handler. Each child carries its
  own dispatch-id so the cascade list shows five entries — the
  cascade-detail view of the root reveals the original event."
  []
  (->> [[100 [:auth/login {:user :ada}]]
        [101 [:auth/load-profile :ada]]
        [102 [:profile/fetch-permissions :ada]]
        [103 [:permissions/cache-load :ada]]
        [104 [:audit/note-login :ada]]]
       (map-indexed (fn [i [dispatch-id ev]]
                      (cascade-evs dispatch-id ev (* (inc i) 50))))
       (mapcat identity)
       vec))

(defn redacted-cascade-buffer
  "One cascade whose dispatched event carries a redaction marker on a
  password slot. The panel surfaces the marker verbatim — this is the
  visual edge for `:sensitive?` handlers per Spec 009."
  []
  (let [dispatch-id 100
        id-base 50
        tag {:dispatch-id dispatch-id}]
    [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
      :tags (assoc tag :event [:user/sign-in {:email "ada@example.com"
                                              :password :rf/redacted
                                              :totp     :rf/redacted}])}
     {:id (+ id-base 2) :op-type :event :operation :event
      :tags (assoc tag :phase :run-start)}
     {:id (+ id-base 3) :op-type :event :operation :event
      :tags (assoc tag :phase :run-end :duration-ms 9)}
     {:id (+ id-base 4) :op-type :event :operation :event/do-fx
      :tags tag}
     {:id (+ id-base 5) :op-type :fx :operation :rf.fx/handled
      :tags (assoc tag :fx-id :http)}]))

(defn large-payload-buffer
  "One cascade whose event payload is large-elided per Spec 009. The
  panel renders the marker shape verbatim — the upstream emit sets the
  marker; the panel's job is to surface it without trying to expand."
  []
  (let [dispatch-id 100
        id-base 50
        tag {:dispatch-id dispatch-id}]
    [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
      :tags (assoc tag :event [:report/upload
                               {:rf.size/large-elided
                                {:source :runtime-flagged
                                 :handle :report/payload-1234
                                 :original-size 4218543
                                 :truncated-preview "{\"rows\": [{...} ...]"}}])}
     {:id (+ id-base 2) :op-type :event :operation :event
      :tags (assoc tag :phase :run-start)}
     {:id (+ id-base 3) :op-type :event :operation :event
      :tags (assoc tag :phase :run-end :duration-ms 23)}
     {:id (+ id-base 4) :op-type :event :operation :event/do-fx
      :tags tag}
     {:id (+ id-base 5) :op-type :fx :operation :rf.fx/handled
      :tags (assoc tag :fx-id :http)}
     {:id (+ id-base 6) :op-type :fx :operation :rf.fx/handled
      :tags (assoc tag :fx-id :metrics)}]))

(defn other-rows-buffer
  "A buffer whose selected cascade carries non-domino rows — errors,
  warnings, machine transitions — so the panel's `other-row` branch
  renders."
  []
  (cascade-with-other 100 50))

(defn long-handler-buffer
  "A buffer of cascades whose `:run-end` rows carry a wide spread of
  `:duration-ms` values so the panel's perf-tier dot ladder is visible
  across the cascade list."
  []
  (->> [{:dispatch-id 100 :event-vec [:counter/increment] :id-base 50  :duration 1}
        {:dispatch-id 101 :event-vec [:cart/add-item :apple] :id-base 100 :duration 6}
        {:dispatch-id 102 :event-vec [:report/render-table] :id-base 150 :duration 22}
        {:dispatch-id 103 :event-vec [:dashboard/refresh] :id-base 200 :duration 87}
        {:dispatch-id 104 :event-vec [:scenario/replay] :id-base 250 :duration 312}]
       (mapcat (fn [{:keys [dispatch-id event-vec id-base duration]}]
                 (-> (cascade-evs dispatch-id event-vec id-base)
                     ;; Patch the run-end emit's :duration-ms so the
                     ;; perf-tier dot reflects the variant's intent.
                     (update 2 update :tags assoc :duration-ms duration))))
       vec))
