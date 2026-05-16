(ns panel-gallery.time-travel-fixtures
  "Pure fixture builders for the Causa time-travel panel gallery
  (rf2-8r20i, Phase 2).

  The time-travel panel reads its rows from `:rf.causa/time-travel`,
  a composite over:

    - `:rf.causa/target-frame`           — the frame whose history
                                            the scrubber is bound to
    - `:rf.causa/epoch-history`          — vector of `:rf/epoch-record`s
    - `:rf.causa/selected-epoch-id`      — drives the cursor + actions
    - `:rf.causa/pinned-snapshots`       — pinned-to-frame epochs
    - `:rf.causa/time-travel-label-input` — text in the label input

  Each variant seeds via `:rf.causa/sync-epoch-history` — the canonical
  seed event used by the trace-bus integration. The handler `assoc`s
  the history vector into Causa's frame app-db; Story's `:rf.story/*`
  runtime slots survive untouched per `tools/story/spec/002-Runtime.md`
  §Coexistence with hosting application state. Where a variant needs
  pinned snapshots it dispatches `:rf.causa/pin-current` after the
  history seed so the pin store is populated by the canonical event-
  driven path.

  ## epoch-record shape

  Per `spec/004-AppDbDiff.md` an `:rf/epoch-record` is:

      {:epoch-id      <opaque>          ; stable id, ring-buffer key
       :frame         :rf/default
       :committed-at  <ms>
       :event-id      <kw>
       :trigger-event <event-vec>
       :db-before     <app-db>
       :db-after      <app-db>
       :trace-events  []}               ; per-epoch trace slice

  The time-travel sub reads `:epoch-id`, `:trigger-event`,
  `:committed-at`, and (for chip projection) `:db-after`. The fixtures
  populate just enough for the panel to render — the diff algorithm
  is exercised on the app-db-diff variants, not here.")

;; ---- epoch-record builder ----------------------------------------------

(defn epoch-record
  "Minimal `:rf/epoch-record` for time-travel render purposes."
  [{:keys [epoch-id event db-before db-after]}]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  (* 1000 epoch-id)
   :event-id      (first event)
   :trigger-event event
   :db-before     (or db-before {})
   :db-after      (or db-after {})
   :trace-events  []})

(defn n-epochs
  "Build `n` simple epochs `:counter` goes 0 → n. Stable epoch-ids so
  the scrubber's slot-index nav is testable."
  [n]
  (->> (range 1 (inc n))
       (mapv (fn [i]
               (epoch-record
                 {:epoch-id i
                  :event    [:counter/inc]
                  :db-before {:counter (dec i)}
                  :db-after  {:counter i}})))))

;; ---- buffer builders ----------------------------------------------------

(defn empty-history
  "No epochs. Panel renders the empty-state copy."
  []
  [])

(defn five-epochs
  "Five epochs, mid-cap. Slider has five slots; chip row absent."
  []
  (n-epochs 5))

(defn fifty-epochs
  "Fifty epochs — exercises the scrubber's slider rendering at a
  typical mid-session depth. The slider's slot count matches."
  []
  (n-epochs 50))

(defn mid-scrub-history
  "Twelve epochs with a mid-history selection. Surfaces the slider
  cursor at slot 5 of 11 (zero-indexed) so a reviewer sees the
  scrubber positioned in the middle."
  []
  (n-epochs 12))

(defn restore-failure-history
  "Single epoch with a known epoch-id absent from history's tail —
  pre-seeded for the 'restore-from-empty' axis. The variant fires
  `:rf.causa/reset-to-epoch` against a stale id; the
  `:rf.causa.fx/restore-epoch` reg-fx records the failure and
  `:rf.causa/last-restore-failure` surfaces it. The gallery
  rendering is purely the post-failure resting state — the panel
  still renders the chip / track row over the surviving history."
  []
  (n-epochs 3))

(defn cap-warning-history
  "Six epochs paired with five pins so the variant can pin a sixth
  (or the panel renders the cap warning when pins reach the cap).
  The pin cap default is 32, so for gallery purposes the warning
  variant requires the cap override; the panel's resting state with
  six epochs + zero pins is the simpler visible axis. The actual
  cap-warning render is exercised in the unit tests."
  []
  (n-epochs 6))

(defn cross-frame-history
  "Eight epochs spanning two frames (`:rf/default` + `:tenant/alpha`).
  Each epoch annotates `:frame` accordingly so the panel's per-row
  frame badge (where rendered) surfaces the cross-frame mix. The
  panel itself reads the target-frame off `:rf.causa/target-frame`
  and renders history for that frame; cross-frame history is the
  axis a reviewer needs to see when picking the picker."
  []
  (->> (range 1 9)
       (mapv (fn [i]
               (let [fid (if (odd? i) :rf/default :tenant/alpha)]
                 (-> (epoch-record
                       {:epoch-id i
                        :event    [:demo/poke i]
                        :db-before {:counter (dec i)}
                        :db-after  {:counter i}})
                     (assoc :frame fid)))))))

(defn dense-event-history
  "Six epochs each carrying a verbose trigger-event vector so the
  panel's trigger-event rendering (when surfaced) is visible at a
  realistic width. Exercises the panel's text-truncation discipline."
  []
  (->> (range 1 7)
       (mapv (fn [i]
               (epoch-record
                 {:epoch-id i
                  :event    [:checkout/submit {:order-id (+ 1000 i)
                                               :items    (vec (range 8))
                                               :promo    "SAVE10"
                                               :addr     {:city "Sydney"
                                                          :state "NSW"}}]
                  :db-before {:cart {:status :idle}}
                  :db-after  {:cart {:status :submitted
                                     :order-id (+ 1000 i)}}})))))
