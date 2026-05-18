(ns panel-gallery.gallery-event
  "Story coverage for the **Event tab** of the new 6-tab Causa chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine).

  The Event tab body is the `event-detail/Panel` view: the six-domino
  cascade view of the focused event. Each variant seeds its frame's
  `:trace-buffer` (and `:selected-dispatch-id` where relevant) via
  REAL Causa init events fired into the variant frame.

  ## Why real init events

  Variant `:events` dispatch via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. Causa's
  registered handlers (`:rf.causa/sync-trace-buffer`,
  `:rf.causa/select-dispatch-id`) write via `(assoc db ...)` — so
  Story's `:rf.story/*` runtime slots survive untouched.

  ## Frame isolation

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame; `:rf.causa/event-detail` reads the seeded
  `:trace-buffer` + `:selected-dispatch-id` slots. Each variant
  therefore observes its own bespoke trace buffer in isolation; no
  two variants share state."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Event tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-event
    {:axis :feature
     :doc  "Causa Event tab — the six-domino cascade view per
            spec/018-Event-Spine.md §5.1."})

  (story/reg-tag :state/empty
    {:axis :state-magnitude :doc "No traces in the buffer."})
  (story/reg-tag :state/small
    {:axis :state-magnitude :doc "≤ 5 cascades / fanout rows."})
  (story/reg-tag :state/medium
    {:axis :state-magnitude :doc "Tens of cascades or fanout rows."})
  (story/reg-tag :state/large
    {:axis :state-magnitude :doc "Hundreds of rows; exercises overflow."})
  (story/reg-tag :state/special
    {:axis :state-magnitude :doc "Edge state — redacted, large-elided, error."})

  (story/reg-story :story.causa.event
    {:doc        "Visual gallery of the Causa Event tab under varying
                 cascade depth + payload shape. Each variant seeds
                 its frame's :trace-buffer via Causa's real init
                 events; the rendered panel reads from the variant
                 frame in isolation."
     :component  :panel-gallery.event/Panel
     :tags       #{:dev :feature/causa-event}
     :substrates #{:reagent}})

  ;; ----- 1. empty cascade buffer ---------------------------------------
  (story/reg-variant :story.causa.event/empty-cascade
    {:doc        "No cascades in the buffer. Panel renders the silent
                 empty-state container (rf2-b9f6z — no prose; the L2
                 event-list is the focus surface)."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. simple cascade (3 cascades, no selection) ------------------
  (story/reg-variant :story.causa.event/simple-cascade
    {:doc        "Three cascades, no selection. Panel renders the
                 cascade-list rows; clicking one would set selection,
                 but the gallery shows the unselected resting state."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/n-cascades 3)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. deep cascade (12 cascades) ---------------------------------
  (story/reg-variant :story.causa.event/deep-cascade
    {:doc        "Twelve sequential cascades simulating an auth bootstrap
                 chain (login → profile → permissions → cache → audit →
                 telemetry → flags → prefs → nav → cart → inbox →
                 presence). Exercises the event-list's overflow + scroll
                 behaviour beyond the default 8-row visible window."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/very-deep-cascade-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. cascade with :rf/redacted marks ---------------------------
  (story/reg-variant :story.causa.event/redacted-marks
    {:doc        "Selected cascade carries a `:sensitive?`-redacted
                 event whose password and totp slots arrive as
                 `:rf/redacted`. The panel's `format-edn` surfaces the
                 marker verbatim — Spec 009 §Privacy in action."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/redacted-cascade-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. cascade with :rf.size/large-elided sentinel ---------------
  (story/reg-variant :story.causa.event/large-elided
    {:doc        "Selected cascade's event payload is large-elided per
                 Spec 009 §Size elision. The panel renders the
                 `:rf.size/large-elided` marker map verbatim — handle,
                 source, original-size, truncated-preview all visible."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/large-payload-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. cascade with handler exception ----------------------------
  (story/reg-variant :story.causa.event/exception
    {:doc        "Selected cascade carries a handler exception
                 (`:rf.error/handler-threw`) alongside the domino emits.
                 The panel's `other-row` branch surfaces the error row
                 with severity dot + exception-message."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/exception-cascade-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. cascade with managed HTTP --------------------------------
  (story/reg-variant :story.causa.event/managed-http
    {:doc        "Cascade pair simulating a managed HTTP flow:
                 `:cart/refresh` dispatches the HTTP fx, then a
                 follow-up `:cart/loaded` cascade is dispatched by
                 the response callback. Exercises the cross-cascade
                 chain a managed-fx flow produces."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/managed-http-cascade-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 8. cascade triggering a machine -----------------------------
  (story/reg-variant :story.causa.event/machine-trigger
    {:doc        "Cascade pair where the handler triggered a state-
                 machine transition: `:user/save` flips :idle →
                 :saving; `:user/save-success` flips :saving → :saved
                 → :idle (via microstep). Surfaces
                 `:rf.machine/transition` and
                 `:rf.machine.microstep/transition` rows in the
                 cascade detail."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/machine-triggering-cascade-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 9. detail multi-fx (medium fanout) --------------------------
  (story/reg-variant :story.causa.event/multi-fx-medium
    {:doc        "One cascade selected; eight effects, four subs, three
                 renders. Mid-size cascade — every domino is populated
                 enough to read but the panel still fits one screen."
     :events     [[:rf.causa/sync-trace-buffer
                   (fixtures/cascade-with-counts
                     {:dispatch-id 100
                      :event-vec   [:checkout/submit {:order-id 42}]
                      :id-base     50
                      :effects     8
                      :subs        4
                      :renders     3})]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 10. detail multi-fx (large fanout) --------------------------
  (story/reg-variant :story.causa.event/multi-fx-large
    {:doc        "One cascade selected; thirty-five effects, fifteen
                 subs, twelve renders. The effects row scrolls
                 vertically inside the cascade-detail layout; the
                 gallery exercises the panel's behaviour under fanout
                 storm."
     :events     [[:rf.causa/sync-trace-buffer
                   (fixtures/cascade-with-counts
                     {:dispatch-id 100
                      :event-vec   [:dashboard/refresh-all]
                      :id-base     50
                      :effects     35
                      :subs        15
                      :renders     12})]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 11. cascade-list at 300 (storm) -----------------------------
  (story/reg-variant :story.causa.event/cascade-list-300
    {:doc        "Three hundred cascades. Exercises the overflow
                 indicator (`overflow.capped-list`) and gives the
                 reviewer a feel for the panel under storm load."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/n-cascades 300)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 12. cascade-list with mixed perf-tier dots ------------------
  ;;
  ;; Panel-specific axis: `event-detail` is the panel that renders
  ;; perf-tier coloured dots on `:duration-ms` (rf2-6ja23). Five
  ;; cascades with durations spanning the 1ms / 6ms / 22ms / 87ms /
  ;; 312ms tiers exercise every tier in one variant.
  (story/reg-variant :story.causa.event/perf-tiers
    {:doc        "Five cascades whose `:run-end` :duration-ms spans
                 every perf-tier swatch in the ladder (1 / 6 / 22 / 87
                 / 312 ms). Selecting any cascade in the live UI would
                 swap to the cascade-detail with the matching tier
                 dot; the gallery shows the cascade-list resting state
                 so all five tiers are visible side-by-side."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/long-handler-buffer)]
                  [:rf.causa/select-dispatch-id 102 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 13. event-lens 6-section happy path (rf2-zh2qc) -------------
  (story/reg-variant :story.causa.event/lens-simple
    {:doc        "Event lens 6-section default: EVENT + DISPATCH SITE
                 + HANDLER + EFFECTS RETURNED + EFFECTS HANDLERS RAN
                 all populated. INTERCEPTORS absent (silent-by-default
                 — no user interceptors on the chain). Exercises the
                 rf2-twt7m substrate keys end-to-end."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/event-lens-simple-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 14. event-lens with many fx + inline managed-fx ---------------
  (story/reg-variant :story.causa.event/lens-many-fx
    {:doc        "Event lens with six fx-handlers in §6 EFFECTS
                 HANDLERS RAN, one of them a managed HTTP fx that
                 mounts its record-panel INLINE beneath the row per
                 §8.3 of the findings doc."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/event-lens-many-fx-buffer)]
                  [:rf.causa/select-dispatch-id 200 nil]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 15. event-lens handler-threw -----------------------------------
  (story/reg-variant :story.causa.event/lens-handler-threw
    {:doc        "Handler threw mid-run. §5 EFFECTS RETURNED + §6
                 EFFECTS HANDLERS RAN are ABSENT; the cascade-outcome
                 glyph is ✗ red and the Issues-tab footer is the ONE
                 inline cross-reference per §7.5."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/event-lens-handler-threw-buffer)]
                  [:rf.causa/select-dispatch-id 300 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 16. event-lens hydration completed (no mismatches) ------------
  (story/reg-variant :story.causa.event/lens-hydration-completed
    {:doc        ":rf.ssr/hydrated event — outcome line carries the
                 SSR✓ badge; §5 carries the dedicated hydration-
                 outcome row with :duration-ms / :subs-ran /
                 :mismatches 0 (no jump-to-Issues affordance)."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/event-lens-hydration-completed-buffer)]
                  [:rf.causa/select-dispatch-id 400 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 17. event-lens hydration with mismatches ----------------------
  (story/reg-variant :story.causa.event/lens-hydration-mismatches
    {:doc        ":rf.ssr/hydrated event with :mismatches 3 — §5
                 hydration-outcome row carries the jump-to-Issues
                 affordance so the developer can pivot to the
                 bisector."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/event-lens-hydration-mismatch-buffer)]
                  [:rf.causa/select-dispatch-id 401 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 18. event-lens user-injected COEFFECTS (rf2-jhhqt) ------------
  (story/reg-variant :story.causa.event/lens-with-coeffects
    {:doc        "Event lens with the §3 COEFFECTS section populated by
                 two user-injected coeffects (`:now` + `:local-storage`)
                 stamped on `:event/do-fx :tags :coeffects`. The
                 substrate has already filtered the framework defaults
                 (`:db` `:event` `:frame` `:source` `:trace-id`) so the
                 panel just renders what arrived."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/event-lens-with-coeffects-buffer)]
                  [:rf.causa/select-dispatch-id 110 nil]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- workspace --------------------------------------------------

  (story/reg-workspace :Workspace.causa.event/all
    {:doc      "All twelve Event tab variants in one auto-grid. Mike
                scrolls; the panel's response across empty / simple /
                deep / redacted / large-elided / exception / managed
                HTTP / machine-trigger / fanout / storm is visible at
                a glance."
     :layout   :variants-grid
     :story    :story.causa.event
     :columns  2
     :tags     #{:dev}}))

;; Register at namespace load — the side-effecting boot pattern every
;; testbed stories ns uses. Idempotent: `register-all!` is safe to
;; call repeatedly.
(register-all!)
