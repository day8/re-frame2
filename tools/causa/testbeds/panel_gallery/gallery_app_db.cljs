(ns panel-gallery.gallery-app-db
  "Story coverage for the **App-db tab** of the new 6-tab Causa chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine).

  The App-db tab body is the `app-db-diff/Panel` view: the changed-
  slices + reserved-keys group + 'Show me when this changed' walker.
  Each variant seeds its frame's `:epoch-history` via REAL Causa init
  events fired into the variant frame.

  rf2-e9tb0 — the pinned-watches strip was dropped in favour of the
  segment-inspector popup; the gallery's variants no longer touch
  `:pinned-slices-store`.

  ## Frame isolation

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame; `:rf.causa/app-db-diff` reads the seeded
  `:epoch-history` (and any `:selected-epoch-id` /
  `:focused-slice-path`). Each variant therefore observes its own
  bespoke history in isolation; no two variants share state."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-app-db :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the App-db tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-app-db
    {:axis :feature
     :doc  "Causa App-db tab — changed slices, reserved-keys group,
            and the 'Show me when this changed' walker (per spec/018-
            Event-Spine §5.2). Pinned-watches strip dropped under
            rf2-e9tb0."})

  (story/reg-story :story.causa.app-db
    {:doc        "Visual gallery of the Causa App-db tab under varying
                 app-db magnitude + payload shape. Each variant seeds
                 its frame's :epoch-history via
                 :rf.causa/sync-epoch-history; the rendered panel reads
                 from the variant frame in isolation."
     :component  :panel-gallery.app-db/Panel
     :tags       #{:dev :feature/causa-app-db}
     :substrates #{:reagent}})

  ;; ----- 1. tiny app-db ----------------------------------------------
  (story/reg-variant :story.causa.app-db/tiny-app-db
    {:doc        "Tiny three-key app-db. Panel renders the resting
                 minimal-state shape — counter + user + ui."
     :events     [[:rf.causa/sync-epoch-history (fixtures/tiny-app-db-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 2. empty (no epochs) -----------------------------------------
  (story/reg-variant :story.causa.app-db/empty
    {:doc        "No epochs in history. Panel renders the empty-state
                 + reserved scaffolding only (rf2-e9tb0 dropped the
                 pinned-watches strip)."
     :events     [[:rf.causa/sync-epoch-history (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 3. large app-db ---------------------------------------------
  (story/reg-variant :story.causa.app-db/large-app-db
    {:doc        "Large multi-tier app-db (~500 leaf keys across
                 :auth, :catalog, :cart, :prefs, :session). Mutates a
                 handful of slices so the diff renders against a
                 realistically-sized backdrop."
     :events     [[:rf.causa/sync-epoch-history (fixtures/large-app-db-buffer)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 4. sensitive paths ------------------------------------------
  (story/reg-variant :story.causa.app-db/sensitive-paths
    {:doc        "Epoch where multiple paths carry `:rf/redacted`
                 markers (across :auth, :user/profile, :billing).
                 Panel surfaces each marker verbatim per Spec 009
                 §Privacy + spec/015-Data-Classification."
     :events     [[:rf.causa/sync-epoch-history (fixtures/sensitive-paths-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. :rf.size/large-elided sentinels --------------------------
  (story/reg-variant :story.causa.app-db/large-sentinels
    {:doc        "Epoch where multiple slices carry `:rf.size/large-
                 elided` sentinels. Panel must render the marker shape
                 without trying to expand."
     :events     [[:rf.causa/sync-epoch-history (fixtures/large-sentinels-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. watched-keys diff highlighting ---------------------------
  (story/reg-variant :story.causa.app-db/watched-keys
    {:doc        "Three epochs in series mutating `:counter`,
                 `:user/profile`, and `:cart`. Selecting any epoch +
                 focusing a path exercises the cross-epoch 'Show me
                 when this changed' walker per spec/004 §Show me when
                 this changed."
     :events     [[:rf.causa/sync-epoch-history (fixtures/watched-keys-buffer)]
                  [:rf.causa/focus-slice-path [:user/profile]]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 7. single-key change ----------------------------------------
  (story/reg-variant :story.causa.app-db/single-key-change
    {:doc        "One epoch with a single top-level key mutation
                 (`:counter` from 5 → 6). Panel renders one changed-
                 slice card."
     :events     [[:rf.causa/sync-epoch-history (fixtures/single-key-change-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 8. five-key changes -----------------------------------------
  (story/reg-variant :story.causa.app-db/five-key-changes
    {:doc        "One epoch mutating five top-level keys — scalar,
                 nested map, vector slice, added flash, removed
                 legacy flag. Mid-size diff fits one screen."
     :events     [[:rf.causa/sync-epoch-history (fixtures/five-key-changes-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 9. nested deep ----------------------------------------------
  (story/reg-variant :story.causa.app-db/nested-deep
    {:doc        "Six-level-deep nested path (`[:tenant :acme
                 :department :eng :team :platform :project :causa
                 :status]`). Exercises the path-pr-str sort and the
                 path rendering in slice cards."
     :events     [[:rf.causa/sync-epoch-history (fixtures/nested-deep-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. large flat (100 keys) -----------------------------------
  (story/reg-variant :story.causa.app-db/large-flat
    {:doc        "One epoch mutating ~100 top-level keys at once.
                 Exercises the overflow / scroll behaviour of the
                 changed-slices stack under storm load."
     :events     [[:rf.causa/sync-epoch-history (fixtures/large-flat-buffer)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 11. mixed ops (panel-specific axis) -------------------------
  (story/reg-variant :story.causa.app-db/mixed-ops
    {:doc        "One epoch demonstrating every diff op (`:added` /
                 `:modified` / `:removed`) side-by-side. Surfaces the
                 op-colour ladder uniformly in a single card."
     :events     [[:rf.causa/sync-epoch-history (fixtures/mixed-ops-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 12. reserved keys (panel-specific axis) ---------------------
  (story/reg-variant :story.causa.app-db/reserved-keys
    {:doc        "Epoch mutating reserved app-db keys (`:rf/route`,
                 `:rf/machines`, `:rf/spawned`). The `[runtime]` group
                 surfaces these separately from user-key slices per
                 Spec Conventions §Reserved app-db keys."
     :events     [[:rf.causa/sync-epoch-history (fixtures/reserved-keys-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.app-db/all
    {:doc      "All twelve App-db tab variants in one auto-grid.
                Scroll to see the panel's response across tiny /
                empty / large / sensitive / large-sentinels /
                watched-keys / single / five-keys / nested / flat /
                mixed-ops / reserved-keys."
     :layout   :variants-grid
     :story    :story.causa.app-db
     :columns  2
     :tags     #{:dev}}))

(register-all!)
