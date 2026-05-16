(ns panel-gallery.issues-ribbon-stories
  "Story coverage for the Causa issues-ribbon panel under gallery
  framing (rf2-8r20i, Phase 2).

  Nine variants, each one render of `issues-ribbon-view` against a
  variant frame whose `:trace-buffer` (and optionally
  `:issues-active-severities` / `:issues-active-prefixes`) has been
  seeded by REAL Causa init events fired into the variant frame.

  ## Why real init events

  Variant `:events` are dispatched via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. Causa's
  registered handlers (`:rf.causa/sync-trace-buffer`,
  `:rf.causa.issues/toggle-severity`, `:rf.causa.issues/toggle-prefix`)
  write via `(assoc db ...)` — Story's `:rf.story/*` runtime slots
  survive untouched. Direct app-db assoc would wipe the lifecycle /
  loaders-complete / assertions slots and corrupt the variant.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame. `:rf.causa/issues-ribbon` reads from the current
  frame's app-db (the seeded buffer + filters). Each variant therefore
  observes its own bespoke issues feed in isolation; no two variants
  share state."
  (:require [re-frame.story :as story]
            [panel-gallery.issues-ribbon-fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

(defn register-gallery-view! []
  (gallery-views/register!))

(defn register-all!
  "Register the issues-ribbon Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-issues-ribbon
    {:axis :feature
     :doc  "Causa issues-ribbon panel — unified feed of errors,
            warnings, schema violations, hydration mismatches."})

  (story/reg-story :story.causa.issues-ribbon
    {:doc        "Visual gallery of the Causa issues-ribbon panel
                 under varying issue depth + filter state. Each
                 variant seeds its frame's :trace-buffer via
                 :rf.causa/sync-trace-buffer; the rendered panel
                 reads from the variant frame in isolation."
     :component  :panel-gallery.issues-ribbon/Panel
     :tags       #{:dev :feature/causa-issues-ribbon}
     :substrates #{:reagent}})

  ;; ----- 1. no issues (empty buffer) --------------------------------
  (story/reg-variant :story.causa.issues-ribbon/empty
    {:doc        "No events in the buffer. Panel renders the
                 :no-issues empty-state ('All clear')."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. no issues but success events present --------------------
  (story/reg-variant :story.causa.issues-ribbon/no-issues-events-present
    {:doc        "Buffer has success-path events but no issues; the
                 panel projection filters them out and still renders
                 the :no-issues empty-state."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/no-issues-but-events-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 3. one issue (small) ---------------------------------------
  (story/reg-variant :story.causa.issues-ribbon/one-issue
    {:doc        "Single error event with exception message. Feed
                 renders one row; severity chip row surfaces
                 'error · 1' only."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/one-issue-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 4. dozens of issues (medium) -------------------------------
  (story/reg-variant :story.causa.issues-ribbon/dozens-of-issues
    {:doc        "Two dozen issues spanning multiple categories and
                 severities. Feed list at typical mid-session depth;
                 chip rows surface populated severity + prefix
                 ladders."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/dozens-of-issues-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 5. severity mix --------------------------------------------
  (story/reg-variant :story.causa.issues-ribbon/severity-mix
    {:doc        "Six issues — two of each severity. Chip-row
                 counts surface 'error · 2', 'warning · 2',
                 'advisory · 2' at exact balance."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/severity-mix-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 6. redacted slot present -----------------------------------
  (story/reg-variant :story.causa.issues-ribbon/redacted
    {:doc        "Issue whose `:event` tag carries `:rf/redacted`
                 markers on `:password` + `:totp`. Description
                 renders the marker verbatim per Spec 009 §Privacy."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/redacted-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. schema violation (panel-specific axis A) ----------------
  ;;
  ;; Panel-specific axis: schema-violation issues carry `:path`
  ;; under tags — the issues-ribbon `short-description` helper
  ;; lifts the path into the one-line summary. No other Causa panel
  ;; renders this projection.
  (story/reg-variant :story.causa.issues-ribbon/schema-violation
    {:doc        "Two schema violations + one regular error. The
                 path-detail surfaces in the description column for
                 the schema rows. Panel-specific axis."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/schema-violation-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. SSR hydration mismatch (panel-specific axis B) ----------
  ;;
  ;; Panel-specific axis: SSR-prefixed issues populate the prefix
  ;; chip row with `:rf.ssr` alongside other prefixes. This is the
  ;; ribbon's signature multi-domain feed — error and warning rows
  ;; from different parts of the framework all surface here.
  (story/reg-variant :story.causa.issues-ribbon/ssr-hydration-mismatch
    {:doc        "Two SSR hydration mismatches + one HTTP timeout.
                 Prefix chip row populates `rf.ssr` alongside
                 `rf.http`. Panel-specific axis: cross-domain feed."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/ssr-hydration-mismatch-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. handler exception with stack (panel-specific axis C) ----
  ;;
  ;; Panel-specific axis: handler-exception issues carry
  ;; `:exception-message` under tags — the description lifts it
  ;; verbatim. Four distinct exceptions exercise the panel's
  ;; description-column truncation discipline.
  (story/reg-variant :story.causa.issues-ribbon/handler-exception
    {:doc        "Four handler exceptions across distinct handler-ids
                 with verbose exception messages. The description
                 column surfaces the message verbatim. Panel-specific
                 axis."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/handler-exception-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.issues-ribbon/all
    {:doc      "All nine issues-ribbon variants in one auto-grid.
                Scroll to see the panel's response across empty /
                no-issues-events-present / one / dozens, severity
                mix, redacted, schema violation, SSR hydration
                mismatch, and handler exception."
     :layout   :variants-grid
     :story    :story.causa.issues-ribbon
     :columns  2
     :tags     #{:dev}}))

(register-all!)
