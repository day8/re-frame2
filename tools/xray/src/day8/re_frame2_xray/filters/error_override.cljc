(ns day8.re-frame2-xray.filters.error-override
  "Error-override filter bypass for the L2 event list (rf2-jqqsh9).

  ## What this is

  spec/018-Event-Spine.md §7 Error overrides: *\"When a filtered event raises
  an exception, surface it anyway.\"* An errored event that a FILTER (an
  OUT-pill match, a failure to match an active IN pill, or a mute) would hide
  is the silent-failure footgun the feature exists to prevent (§5.4:
  `error` is *never filtered out*). So the filter chain re-adds any errored
  event-bundle the pill/mute filters dropped, tagging it `:rf.xray/filter-
  bypassed? true` so the row can carry the *\"would normally be hidden, but it
  errored\"* cue.

  ## Frame is a VIEW SCOPE, not a filter

  spec/018 §7: the frame picker is a view scope, *not counted as hidden*. So
  the override operates on the frame-SCOPED list — an errored event from
  ANOTHER frame is never dragged back into the current frame's view (Xray
  observes ONE frame; frames are isolated contexts). The `scoped` arg is the
  view-scope-filtered list; `filtered` is `scoped` AFTER the pill + mute
  filters. Only bundles the pill/mute filters removed FROM `scoped` are
  candidates for re-adding.

  ## Pure data, JVM-portable

  `.cljc` so the bypass contract is pinned by the JVM test corpus without a
  CLJS runtime. The error classification delegates to the already-pure
  `event-status-colour/event-bundle-outcome` — the SAME `:error` classifier
  the Epoch outcome banner + L2 row colouring key off — so the bypass can
  never disagree with the rest of Xray about what counts as errored."
  (:require [day8.re-frame2-xray.panels.event.event-status-colour
             :as event-status-colour]))

(def filter-bypassed-key
  "The tag key stamped on an event-bundle the pill/mute filters would have
  hidden but that surfaces anyway because it errored (spec/018 §7). The row
  reads this to render the filter-bypass cue. Namespaced under `:rf.xray/` so
  it never collides with a projected event-bundle field."
  :rf.xray/filter-bypassed?)

(defn errored?
  "True iff `event-bundle` carries an error trace (its lifecycle outcome is
  `:error`, per `event-status-colour/event-bundle-outcome`). Pure predicate;
  JVM-runnable."
  [event-bundle]
  (= :error (:outcome (event-status-colour/event-bundle-outcome event-bundle))))

(defn apply-error-overrides
  "Re-add any errored event-bundle the pill/mute filters dropped, so an error a
  filter would hide still surfaces (spec/018 §7 Error overrides).

  - `scoped`   — the view-scope-filtered event-bundle list (frame is a view
                 scope, NOT a filter; an errored event outside the current
                 frame is already absent here and stays absent).
  - `filtered` — `scoped` AFTER the pill + mute filters.
  - `enabled?` — the `:rf.xray/filters-auto-hide-error-overrides?` posture
                 (default `true`). When false this is a no-op returning
                 `filtered` unchanged.

  Returns a `scoped`-ORDERED vector: every bundle the filters kept, plus every
  errored bundle they dropped (re-inserted at its original position and tagged
  `filter-bypassed-key` → `true`). A bundle the filters kept is returned
  as-is (never tagged). Pure data → data; JVM-runnable."
  [scoped filtered enabled?]
  (if-not enabled?
    (vec filtered)
    (let [kept (set filtered)]
      (reduce (fn [acc b]
                (cond
                  ;; survived the filters — keep verbatim (untagged).
                  (contains? kept b)   (conj acc b)
                  ;; dropped by a filter but errored — surface it anyway.
                  (errored? b)         (conj acc (assoc b filter-bypassed-key true))
                  ;; dropped by a filter and clean — stays hidden.
                  :else                acc))
              []
              scoped))))
