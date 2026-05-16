(ns panel-gallery.time-travel-stories
  "Story coverage for the Causa time-travel panel under gallery
  framing (rf2-8r20i, Phase 2).

  Eight variants, each one render of `time-travel-view` against a
  variant frame whose `:epoch-history` (and optionally
  `:selected-epoch-id`) has been seeded by REAL Causa init events
  fired into the variant frame.

  ## Why real init events

  Variant `:events` are dispatched via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. Causa's
  registered handlers (`:rf.causa/sync-epoch-history`,
  `:rf.causa/select-epoch`, `:rf.causa/pin-current`) write via
  `(assoc db ...)` — Story's `:rf.story/*` runtime slots survive
  untouched. Direct app-db assoc would wipe the lifecycle / loaders-
  complete / assertions slots and corrupt the variant.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame. `:rf.causa/time-travel` reads from the current
  frame's app-db, picking up `:epoch-history`, `:selected-epoch-id`,
  and the pin store. Each variant therefore observes its own
  bespoke history in isolation; no two variants share state."
  (:require [re-frame.story :as story]
            [panel-gallery.time-travel-fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

(defn register-gallery-view! []
  (gallery-views/register!))

(defn register-all!
  "Register the time-travel Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-time-travel
    {:axis :feature
     :doc  "Causa time-travel panel — epoch history scrubber, pins,
            and reset-to actions."})

  (story/reg-story :story.causa.time-travel
    {:doc        "Visual gallery of the Causa time-travel panel under
                 varying history depth. Each variant seeds its frame's
                 :epoch-history via :rf.causa/sync-epoch-history; the
                 rendered panel reads from the variant frame in
                 isolation."
     :component  :panel-gallery.time-travel/Panel
     :tags       #{:dev :feature/causa-time-travel}
     :substrates #{:reagent}})

  ;; ----- 1. empty history (depth 0) ---------------------------------
  (story/reg-variant :story.causa.time-travel/empty-history
    {:doc        "No epochs. Panel renders the empty-state copy
                 ('No epoch history for :rf/default yet.')."
     :events     [[:rf.causa/sync-epoch-history (fixtures/empty-history)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. five epochs (small) -------------------------------------
  (story/reg-variant :story.causa.time-travel/five-epochs
    {:doc        "Five epochs. Scrubber slider has five slots; no
                 selection so the cursor sits at the newest epoch."
     :events     [[:rf.causa/sync-epoch-history (fixtures/five-epochs)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. fifty epochs (medium) -----------------------------------
  (story/reg-variant :story.causa.time-travel/fifty-epochs
    {:doc        "Fifty epochs — typical mid-session scrubber depth.
                 The slider's slot count matches; counter reads 50/50."
     :events     [[:rf.causa/sync-epoch-history (fixtures/fifty-epochs)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. mid-scrub (cursor in the middle) ------------------------
  (story/reg-variant :story.causa.time-travel/mid-scrub
    {:doc        "Twelve epochs with the cursor at slot 6 of 12. The
                 slider position + counter span both surface the
                 mid-scrub state."
     :events     [[:rf.causa/sync-epoch-history (fixtures/mid-scrub-history)]
                  [:rf.causa/select-epoch 6]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 5. restore-from-empty (panel-specific axis A) --------------
  ;;
  ;; Panel-specific axis: time-travel uniquely surfaces the result of
  ;; `:rf.causa/reset-to-epoch` against history. The 'restore-from-
  ;; empty' variant seeds three epochs, then selects the newest — a
  ;; reset-to-pinned with a stale id would render the failure state.
  ;; The gallery captures the post-restore resting state: a populated
  ;; history with cursor anchored on the most recent epoch.
  (story/reg-variant :story.causa.time-travel/restore-from-empty
    {:doc        "Three epochs after a restore. The scrubber's
                 cursor is anchored on the newest epoch — the canonical
                 post-restore resting state."
     :events     [[:rf.causa/sync-epoch-history (fixtures/restore-failure-history)]
                  [:rf.causa/select-epoch 3]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. pinned-snapshot (panel-specific axis B) -----------------
  ;;
  ;; Panel-specific axis: the chip-row is unique to time-travel —
  ;; the pin store surfaces as attached / detached chips above the
  ;; scrubber. This variant pins the second epoch with a custom
  ;; label so the chip renders attached.
  (story/reg-variant :story.causa.time-travel/with-pin
    {:doc        "Six epochs with one user-labelled pin on epoch 2.
                 Chip row surfaces the attached chip with the label
                 'before-purchase'. Panel-specific axis: pin chip
                 rendering is unique to this panel."
     :events     [[:rf.causa/sync-epoch-history (fixtures/cap-warning-history)]
                  [:rf.causa/pin-current 2 "before-purchase"]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. cross-frame history (panel-specific axis C) -------------
  ;;
  ;; Panel-specific axis: time-travel binds to a single target-frame
  ;; at a time. This variant seeds a history with cross-frame entries
  ;; (alternating `:rf/default` / `:tenant/alpha`) so a reviewer can
  ;; see the panel's frame-binding header rendering.
  (story/reg-variant :story.causa.time-travel/cross-frame
    {:doc        "Eight epochs spanning two frames (`:rf/default` /
                 `:tenant/alpha`). The panel's header surfaces the
                 bound target-frame. Panel-specific axis: cross-frame
                 history shapes the picker UX."
     :events     [[:rf.causa/sync-epoch-history (fixtures/cross-frame-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. dense-event history (panel-specific axis D) -------------
  ;;
  ;; Panel-specific axis: time-travel renders the `:trigger-event`
  ;; tooltip (or label hover, depending on density). Verbose
  ;; trigger-event vectors exercise the text-truncation discipline.
  (story/reg-variant :story.causa.time-travel/dense-event
    {:doc        "Six epochs each with a verbose trigger-event
                 vector (`:checkout/submit` with an order map). The
                 panel's text-truncation behaviour is the axis under
                 stress."
     :events     [[:rf.causa/sync-epoch-history (fixtures/dense-event-history)]
                  [:rf.causa/select-epoch 4]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.time-travel/all
    {:doc      "All eight time-travel variants in one auto-grid.
                Scroll to see the panel's response across empty / 5 /
                50 epochs, mid-scrub, restore-from-empty, pinned
                snapshot, cross-frame, and dense trigger-event."
     :layout   :variants-grid
     :story    :story.causa.time-travel
     :columns  2
     :tags     #{:dev}}))

(register-all!)
