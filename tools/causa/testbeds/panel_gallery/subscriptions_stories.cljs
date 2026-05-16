(ns panel-gallery.subscriptions-stories
  "Story coverage for the Causa subscriptions panel under gallery
  framing (rf2-5nvk2, Phase 1b).

  Ten variants, each one render of `subscriptions-view` against a
  variant frame whose `:sub-cache-override` (and optionally
  `:sub-error-cache`, `:selected-sub`, `:sub-filters`,
  `:sub-chain-open?`) has been seeded by REAL Causa init events
  fired into the variant frame.

  ## Why real init events

  Variant `:events` are dispatched via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. The seed event
  `:rf.causa/set-sub-cache-override-for-test` writes via
  `(assoc db :sub-cache-override ov)` — Story's `:rf.story/*` runtime
  slots survive untouched. Direct app-db assoc would wipe the
  lifecycle / loaders-complete / assertions slots and corrupt the
  variant.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame. `:rf.causa/subscriptions-data` reads from the
  current frame's app-db, picking up `:sub-cache-override` per
  `subscriptions-subs/install!` line 12. Each variant therefore
  observes its own bespoke sub-cache in isolation; no two variants
  share state.

  ## Why no live `sub-cache` for the panel-gallery testbed

  The panel reads `(rf/sub-cache target)` when `:sub-cache-override`
  is absent — that's the live runtime cache, which here would be the
  variant frame's actual subs. The gallery's purpose is visual; the
  override slot exists precisely to let tests / galleries inject a
  representative cache shape without forcing the runtime to
  subscribe-then-derefa-real-graph. We use the canonical override
  surface."
  (:require [re-frame.story :as story]
            [panel-gallery.subscriptions-fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

(defn register-gallery-view! []
  (gallery-views/register!))

(defn register-all!
  "Register the subscriptions Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-subscriptions
    {:axis :feature
     :doc  "Causa subscriptions panel — rows + chain + status badges
            over the target frame's sub-cache."})

  (story/reg-story :story.causa.subscriptions
    {:doc        "Visual gallery of the Causa subscriptions panel under
                 varying state magnitude. Each variant seeds its frame's
                 :sub-cache-override via :rf.causa/set-sub-cache-override-
                 for-test; the rendered panel reads from the variant
                 frame in isolation."
     :component  :panel-gallery.subscriptions/Panel
     :tags       #{:dev :feature/causa-subscriptions}
     :substrates #{:reagent}})

  ;; ----- 1. 0 subs (empty cache) -------------------------------------
  (story/reg-variant :story.causa.subscriptions/zero-subs
    {:doc        "Empty cache. Panel renders the empty-state message
                 ('No subscriptions yet')."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/empty-cache)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. 5 subs (small fanout) ------------------------------------
  (story/reg-variant :story.causa.subscriptions/five-subs
    {:doc        "Five subs — typical small-app shape (auth / route /
                 counter / cart-count / theme). Two layers, mixed
                 ref-counts."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/small-cache)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. 50 subs (medium fanout) ----------------------------------
  (story/reg-variant :story.causa.subscriptions/fifty-subs
    {:doc        "Fifty subs across three layers — typical mid-sized
                 app shape. Demonstrates the rows list under normal
                 load."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/medium-cache)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. 200 subs (large / overflow) ------------------------------
  (story/reg-variant :story.causa.subscriptions/two-hundred-subs
    {:doc        "Two hundred subs across four layers — exercises the
                 overflow / scroll behaviour of the rows list under
                 storm load."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/large-cache)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. derived-chain (3-input chain view) -----------------------
  (story/reg-variant :story.causa.subscriptions/derived-chain
    {:doc        "Six subs forming a small derivation tree —
                 `:cart/total` depends on three layer-2 subs each of
                 which depends on its own layer-1 source. Opens the
                 chain side-panel focused on `:cart/total` so the
                 three-input fanout renders."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/derived-chain-cache)]
                  [:rf.causa/show-invalidation-chain [:cart/total]]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 6. redacted sub payload -------------------------------------
  (story/reg-variant :story.causa.subscriptions/redacted
    {:doc        "One sub whose computed `:value` carries
                 `:rf/redacted` markers on `:password` and `:totp`
                 slots. Per Spec 009 §Privacy the panel surfaces the
                 marker verbatim."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/redacted-cache)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. loading / re-running -------------------------------------
  (story/reg-variant :story.causa.subscriptions/loading
    {:doc        "Three subs in `:re-running` status (computation
                 in-flight). The remaining two are `:fresh` for
                 contrast — surfaces the in-flight badge alongside
                 a stable baseline."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/rerunning-cache)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. errored --------------------------------------------------
  ;;
  ;; The error variant seeds both the cache and the error cache. The
  ;; error cache is read directly off `:sub-error-cache` (no public
  ;; init event exists — the runtime trace-bus writes it). For test
  ;; purposes we register a tiny gallery-local event below that does
  ;; the assoc. This is the ONLY testbed-side handler the gallery
  ;; needs; the panels themselves stay untouched.
  (story/reg-variant :story.causa.subscriptions/error
    {:doc        "Two subs errored at the sub-cache level (handler
                 throw / missing input); panel renders the error
                 badge for each, with the message visible on hover."
     :events     [[:panel-gallery/seed-sub-cache-and-errors
                   (:cache  fixtures/errored-cache-entries)
                   (:errors fixtures/errored-cache-entries)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. invalidated mix (panel-specific axis A) -----------------
  ;;
  ;; Panel-specific axis: the subscriptions panel uniquely renders a
  ;; five-status filter header (`:error`, `:re-running`,
  ;; `:invalidated`, `:fresh`, `:cached-no-watcher`). This variant
  ;; surfaces every status chip in a single card so the badge ladder
  ;; is visible at a glance.
  (story/reg-variant :story.causa.subscriptions/invalidated-mix
    {:doc        "Five subs spanning four statuses (`:fresh`,
                 `:re-running`, `:invalidated`, `:cached-no-watcher`)
                 plus a derived `:fresh` sub. Panel-specific axis:
                 the status badge ladder is unique to this panel."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/invalidated-mix-cache)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. deep chain (panel-specific axis B) ---------------------
  ;;
  ;; Panel-specific axis: the subscriptions panel uniquely renders a
  ;; per-row layer dot (l1 / l2 / l3 / l4 / l5 colour swatch). This
  ;; variant exercises every layer in one card.
  (story/reg-variant :story.causa.subscriptions/deep-chain
    {:doc        "Five-layer derivation chain — exercises the layer-
                 dot ladder (l1 / l2 / l3 / l4 / l5) in a single
                 card. Panel-specific axis."
     :events     [[:rf.causa/set-sub-cache-override-for-test
                   (fixtures/deep-chain-cache)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.subscriptions/all
    {:doc      "All ten subscriptions variants in one auto-grid.
                Scroll to see the panel's response across 0 / 5 / 50
                / 200 subs, the derived chain side-panel, redacted
                value, in-flight + errored badges, and the layer
                ladder."
     :layout   :variants-grid
     :story    :story.causa.subscriptions
     :columns  2
     :tags     #{:dev}}))

(register-all!)
