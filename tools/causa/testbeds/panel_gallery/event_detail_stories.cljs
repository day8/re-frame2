(ns panel-gallery.event-detail-stories
  "Story coverage for the Causa event-detail panel under gallery framing
  (rf2-1o7mp).

  Twelve variants, each one render of `event-detail-view` against a
  variant frame whose `:trace-buffer` (and where relevant
  `:selected-dispatch-id` / `:selected-dispatch`) has been seeded by
  REAL Causa init events fired into the variant frame.

  ## Why real init events

  Variant `:events` are dispatched via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. Causa's
  registered handlers (`:rf.causa/sync-trace-buffer`,
  `:rf.causa/select-dispatch-id`) write via `(assoc db ...)` — so
  Story's `:rf.story/*` runtime slots survive untouched. Direct
  app-db assoc would wipe the lifecycle / loaders-complete / assertions
  slots and corrupt the variant.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]` (per `tools/story/src/re_frame/story/ui/canvas.cljs`).
  Subscriptions inside the rendered tree resolve to the variant frame.
  `:rf.causa/trace-buffer` reads `(get db :trace-buffer)` from the
  current frame's app-db — exactly the variant-frame slot the seed
  events wrote. Each variant therefore observes its own bespoke trace
  buffer in isolation; no two variants share state."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

;; ---- gallery view registration -----------------------------------------
;;
;; Story requires `:component` to be a registered view-id (per the
;; canvas's `(re-frame.core/view <id>)` lookup). The gallery's
;; component is a thin wrapper around `event-detail-view` so the panel
;; renders inside the variant's frame-provider unchanged. Wrapper
;; absorbs the Story-provided args map (the panel takes no args).

(defn register-gallery-view! []
  (gallery-views/register!))

;; ---- variants -----------------------------------------------------------

(defn register-all!
  "Register the event-detail Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-event-detail
    {:axis :feature
     :doc  "Causa event-detail panel — hero panel, six-domino cascade view."})

  (story/reg-tag :state/empty
    {:axis :state-magnitude :doc "No traces in the buffer."})
  (story/reg-tag :state/small
    {:axis :state-magnitude :doc "≤ 5 cascades / fanout rows."})
  (story/reg-tag :state/medium
    {:axis :state-magnitude :doc "Tens of cascades or fanout rows."})
  (story/reg-tag :state/large
    {:axis :state-magnitude :doc "Hundreds of rows; exercises overflow."})
  (story/reg-tag :state/special
    {:axis :state-magnitude :doc "Edge state — orphan, redacted, large-elided, error."})

  (story/reg-story :story.causa.event-detail
    {:doc        "Visual gallery of the Causa event-detail panel under
                 varying state magnitude. Each variant seeds its frame's
                 :trace-buffer (and selection slots where relevant) via
                 Causa's real init events; the rendered panel reads from
                 the variant frame in isolation."
     :component  :panel-gallery.event-detail/Panel
     :tags       #{:dev :feature/causa-event-detail}
     :substrates #{:reagent}})

  ;; ----- 1. empty cascade buffer ----------------------------------------
  (story/reg-variant :story.causa.event-detail/empty-buffer
    {:doc        "No cascades in the buffer. Panel renders the
                 'No cascades yet' empty-state copy alongside the
                 cascade-list container."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. cascade list, small (3) -------------------------------------
  (story/reg-variant :story.causa.event-detail/cascade-list-3
    {:doc        "Three cascades, no selection. Panel renders the
                 cascade-list rows; clicking one would set selection,
                 but the gallery shows the unselected resting state."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/n-cascades 3)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. cascade list, medium (30) -----------------------------------
  (story/reg-variant :story.causa.event-detail/cascade-list-30
    {:doc        "Thirty cascades. The cascade list is comfortably
                 scrollable; the panel's overflow indicator stays
                 quiet under the cap."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/n-cascades 30)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. cascade list, large (300) -----------------------------------
  (story/reg-variant :story.causa.event-detail/cascade-list-300
    {:doc        "Three hundred cascades. Exercises the overflow
                 indicator (`overflow.capped-list`) and gives the
                 reviewer a feel for the panel under storm load."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/n-cascades 300)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. detail, single fx -------------------------------------------
  (story/reg-variant :story.causa.event-detail/detail-single-fx
    {:doc        "One cascade selected; minimal effects (one :db fx).
                 The narrowest detail render — every domino present
                 but each at its smallest visual surface."
     :events     [[:rf.causa/sync-trace-buffer
                   (fixtures/cascade-with-counts
                     {:dispatch-id 100
                      :event-vec   [:counter/increment]
                      :id-base     50
                      :effects     1
                      :subs        1
                      :renders     1})]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 6. detail, medium fanout (8 effects) ---------------------------
  (story/reg-variant :story.causa.event-detail/detail-multi-fx-medium
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

  ;; ----- 7. detail, very large fanout (30+ effects) ---------------------
  (story/reg-variant :story.causa.event-detail/detail-multi-fx-large
    {:doc        "One cascade selected; thirty-five effects. The effects
                 row scrolls vertically inside the cascade-detail layout;
                 the gallery exercises the panel's behaviour under fanout
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

  ;; ----- 8. detail, deep cascade nesting (5 cascades, sequential) ------
  (story/reg-variant :story.causa.event-detail/detail-deep-cascade
    {:doc        "Five cascades simulating a programmatic re-dispatch
                 chain (login → load profile → fetch perms → cache →
                 audit). The cascade list shows the full chain; the
                 selected detail is the root login event with its own
                 effects/subs/renders."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/deep-nested-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 9. detail, redacted slot present -------------------------------
  (story/reg-variant :story.causa.event-detail/detail-redacted
    {:doc        "Selected cascade carries a `:sensitive?`-redacted
                 event whose password and totp slots arrive as
                 `:rf/redacted`. The panel's `format-edn` surfaces the
                 marker verbatim — Spec 009 §Privacy in action."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/redacted-cascade-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. detail, large-payload (elided marker) ----------------------
  (story/reg-variant :story.causa.event-detail/detail-large-payload
    {:doc        "Selected cascade's event payload is large-elided per
                 Spec 009 §Size elision. The panel renders the
                 `:rf.size/large-elided` marker map verbatim — handle,
                 source, original-size, truncated-preview all visible."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/large-payload-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 11. detail with :other rows (errors / warnings / machines) ----
  ;;
  ;; Panel-specific axis A: `event-detail` distinctly renders an
  ;; :other-row bucket for traces that don't fit the six-domino
  ;; vocabulary (errors, warnings, machine transitions). This variant
  ;; is the one place in the gallery that exercises that branch.
  (story/reg-variant :story.causa.event-detail/detail-other-rows
    {:doc        "Selected cascade carries non-domino rows — handler
                 exception, large-elision warning, machine transition.
                 Surfaces the panel's `other-row` render branch
                 (yellow-tone label, op-type + operation columns)."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/other-rows-buffer)]
                  [:rf.causa/select-dispatch-id 100 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 12. cascade list with mixed perf-tier dots --------------------
  ;;
  ;; Panel-specific axis B: `event-detail` is the panel that renders
  ;; perf-tier coloured dots on `:duration-ms` (rf2-6ja23). Five
  ;; cascades with durations spanning the 1ms / 6ms / 22ms / 87ms /
  ;; 312ms tiers exercise every tier in one variant.
  (story/reg-variant :story.causa.event-detail/cascade-list-perf-tiers
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

  ;; ----- workspace ------------------------------------------------------

  (story/reg-workspace :Workspace.causa.event-detail/all
    {:doc      "All twelve event-detail variants in one auto-grid.
                Mike scrolls; the panel's response to varying state
                magnitude is visible at a glance."
     :layout   :variants-grid
     :story    :story.causa.event-detail
     :columns  2
     :tags     #{:dev}}))

;; Register at namespace load — the side-effecting boot pattern every
;; testbed stories ns uses (cf. `cart-total.stories`). Idempotent:
;; `register-all!` is safe to call repeatedly.
(register-all!)
