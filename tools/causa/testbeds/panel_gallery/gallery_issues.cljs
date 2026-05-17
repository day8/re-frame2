(ns panel-gallery.gallery-issues
  "Story coverage for the **Issues tab** of the new 6-tab Causa chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine).

  The Issues tab body is the `issues-ribbon/Panel` view: the issue
  feed over `:trace-buffer` filtered to `:error` / `:warning` /
  `:info` op-types. Each variant seeds its frame's `:trace-buffer`
  via REAL Causa init events fired into the variant frame."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-issues :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Issues tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-issues
    {:axis :feature
     :doc  "Causa Issues tab — the issue-feed over trace-buffer
            filtered to error/warning/info (per spec/018 §5.6)."})

  (story/reg-story :story.causa.issues
    {:doc        "Visual gallery of the Causa Issues tab under varying
                 issue load + category mix. Each variant seeds its
                 frame's :trace-buffer via :rf.causa/sync-trace-buffer;
                 the panel projection keeps only issue op-types."
     :component  :panel-gallery.issues/Panel
     :tags       #{:dev :feature/causa-issues}
     :substrates #{:reagent}})

  ;; ----- 1. no issues ------------------------------------------------
  (story/reg-variant :story.causa.issues/no-issues
    {:doc        "Empty buffer. Panel renders the :no-issues empty-
                 state ('All clear')."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. no issues but events present -----------------------------
  (story/reg-variant :story.causa.issues/no-issues-but-events
    {:doc        "Only success-path events; panel still renders
                 :no-issues. Per `issues_ribbon_helpers/issue-event?`
                 non-issue ops drop silently."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/no-issues-but-events-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 3. schema-violation -----------------------------------------
  (story/reg-variant :story.causa.issues/schema-violation
    {:doc        "Two `:rf.schema/violation` issues + one regular
                 error. The description column lifts the :path slot
                 into the one-line summary."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/schema-violation-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. hydration-mismatch ---------------------------------------
  (story/reg-variant :story.causa.issues/hydration-mismatch
    {:doc        "Two `:rf.ssr/hydration-mismatch` warnings + one
                 HTTP error. Prefix chip row surfaces the `:rf.ssr`
                 prefix as its own axis with ≥2 chips."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/ssr-hydration-mismatch-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. exception ------------------------------------------------
  (story/reg-variant :story.causa.issues/exception
    {:doc        "Four handler exceptions across distinct handler-ids
                 each carrying an `:exception-message` slot. The
                 description column lifts the message verbatim."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/handler-exception-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. multiple issues stacked ----------------------------------
  (story/reg-variant :story.causa.issues/multiple-stacked
    {:doc        "Eight issues in a tight time window — pins the
                 feed's rendering when issues stack quickly (one
                 fault cascade producing many warnings + errors).
                 Mixes exceptions, schema, SSR, HTTP, dispatch loop,
                 advisory."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/multiple-issues-stacked-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 7. one issue ------------------------------------------------
  (story/reg-variant :story.causa.issues/one-issue
    {:doc        "Single error event — panel surfaces one feed row.
                 Minimum non-empty case."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/one-issue-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 8. severity mix --------------------------------------------
  (story/reg-variant :story.causa.issues/severity-mix
    {:doc        "Six issues — two of each severity. Exercises the
                 chip-row counts at exact balance so the chip ladder
                 is readable at a glance."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/severity-mix-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 9. dozens of issues ----------------------------------------
  (story/reg-variant :story.causa.issues/dozens-of-issues
    {:doc        "Two dozen issues spanning multiple categories and
                 severities; exercises the feed list at typical mid-
                 session depth."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/dozens-of-issues-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 10. redacted -----------------------------------------------
  (story/reg-variant :story.causa.issues/redacted
    {:doc        "Issue whose `:event` tag carries `:rf/redacted`
                 markers — the description renders the marker
                 verbatim per Spec 009 §Privacy."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/redacted-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 11. recovery spans (panel-specific) -------------------------
  (story/reg-variant :story.causa.issues/recovery-spans
    {:doc        "Three issues carrying `:recovery` slots (`:rollback`
                 / `:retry` / `:skip`) + one `:no-recovery`. Pins
                 the panel's recovery-chip rendering across the
                 spread per Spec 009 §Recovery taxonomy."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/recovery-spans-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.issues/all
    {:doc      "All eleven Issues tab variants in one auto-grid.
                Scroll to see the panel's response across no-issues
                / no-issues-but-events / schema / hydration /
                exception / stacked / single / severity-mix / dozens
                / redacted / recovery-spans."
     :layout   :variants-grid
     :story    :story.causa.issues
     :columns  2
     :tags     #{:dev}}))

(register-all!)
