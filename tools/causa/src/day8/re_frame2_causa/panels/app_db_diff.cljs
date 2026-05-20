(ns day8.re-frame2-causa.panels.app-db-diff
  "App-DB Diff panel shell.

  The panel is sections-centric (rf2-gfxmk Phase 1): it renders the
  structural-diff engine's section vector for the selected epoch,
  pinned live slices, reserved runtime keys, or the 'Show me when this
  changed' result for a focused path.

  Prior to rf2-gfxmk the panel rendered one slice-mini-panel per
  diff triple (stacked before/after cljs-devtools trees per slice).
  The sections-per-cluster model replaces that: N path-headed sections,
  each containing a local annotated subtree with in-place gutters,
  smart-expand, and chip-collapsed `:same` siblings. See
  `ai/findings/2026-05-18-difftastic-in-causa.md` §3.1 for the design.

  Canonical exemplar of the panel facade pattern documented in
  `tools/causa/spec/Conventions.md` — facade owns the public
  `reg-view`, leaves expose plain fns + `install!`, the facade's
  `install!` chains leaf installs and returns `nil`.

  ## rf2-e9tb0 — pinned-watches dropped

  The pinned-slices strip was removed when path-segment click-to-
  inspect landed (Mike 2026-05-19 Q13). The diff already identifies
  changes surgically; pinning paths up-front is redundant when any
  prefix of any diff path can be opened in the segment inspector via
  one click on its breadcrumb segment. The escape-hatch use case
  ('let me see app-db in its entirety') is now served by clicking the
  root segment of any breadcrumb."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.diff.render :as diff-render]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.app-db-diff-downstream
             :as downstream]
            [day8.re-frame2-causa.panels.app-db-diff-events :as events]
            [day8.re-frame2-causa.panels.app-db-diff-sections
             :as sections]
            [day8.re-frame2-causa.panels.app-db-diff-subs :as subs]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack display-stack]]))

(rf/reg-view Panel
  "The App-DB Diff panel's root view."
  []
  (let [{:keys [target-frame
                history-empty?
                changed-sections
                changed-reserved
                focused-path
                focused-hits
                redacted-modified-count
                flow-writes
                diff-triples
                selected-epoch-id]}
        @(rf/subscribe [:rf.causa/app-db-diff])]
    [:section {:data-testid "rf-causa-app-db-diff"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      ;; rf2-5kfxe.8 — domain-coloured 3px left border via the
      ;; canonical `theme.tokens/accent-stripe-style` helper. App-db's
      ;; domain colour is `:cyan` — see `panel-domain->token`.
      ;; rf2-5kfxe.9 — the L4 title carries the display face
      ;; (Fraunces). Body / chrome stays Inter — only this <h1>
      ;; reaches for the serif so the visual hierarchy is
      ;; unmistakeable.
      [:h1 {:style (merge {:font-size   "20px"
                           :font-family display-stack
                           :font-weight 600
                           :letter-spacing "-0.01em"
                           :margin      0
                           :color       (:text-primary tokens)
                           :display     "flex"
                           :align-items "center"
                           :gap         "8px"}
                          (t/accent-stripe-style :app-db))}
       ;; rf2-ezx8w — spec/021 §17.1.5 per-panel header icon. ◐ in
       ;; :cyan (App-db's domain colour via panel-domain->token).
       [:span {:data-testid "rf-causa-app-db-panel-icon"
               :aria-hidden "true"
               :style       (t/panel-icon-style :app-db)}
        (:app-db t/panel-icon)]
       "App-db diff"]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        focused-path
        (sections/focus-result-panel focused-path focused-hits)

        history-empty?
        [:div
         (sections/empty-state target-frame)
         (sections/reserved-group changed-reserved)]

        :else
        [:div
         ;; rf2-bz1cl — redacted-paths-modified hint chip. nil when
         ;; count is 0, so the chip surface is absent unless the
         ;; cascade actually involved redacted slots in mutated
         ;; subtrees.
         (sections/redacted-modified-chip redacted-modified-count)
         ;; rf2-s8r6c — per-section origin tag chip is computed here:
         ;; the renderer takes the full per-leaf `diff-triples` + the
         ;; per-epoch `flow-writes` and attributes each section's
         ;; writer(s). When no flow fired, every section gets
         ;; `[fx :db]`; when flows fired, sections covering a flow's
         ;; `:write-path` get `[flow :flow-id]` (or mixed if
         ;; coalesced).
         ;; rf2-5kfxe.2 — pass `:epoch-id` so the renderer keys each
         ;; section by epoch + path. A new cascade lands as a fresh
         ;; React mount per section and the diff-flash keyframes
         ;; auto-play (yellow wash decaying to transparent over 400ms,
         ;; scaled by the `--rf-causa-motion-scale` seam).
         ;; rf2-op9v2 — `:extra-affordance-fn` lets us inject the
         ;; downstream-subs hover trigger into every section's
         ;; breadcrumb. The trigger renders its own popover when
         ;; hovered/focused; only one popover is open at a time per
         ;; `:rf.causa.app-db/popover-slot`.
         (diff-render/render-sections changed-sections "app-db-diff"
                                       {:flow-writes  flow-writes
                                        :diff-triples diff-triples
                                        :epoch-id     selected-epoch-id
                                        :extra-affordance-fn
                                        (fn [section-path]
                                          [downstream/hover-trigger
                                           section-path])})
         (sections/reserved-group changed-reserved)])]]))

(defn install!
  "Idempotent install for the App-DB Diff panel's Causa-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  ;; rf2-op9v2 — downstream-subs overlay subs + events (the hover
  ;; popover that lists subs/views downstream of each changed path).
  (downstream/install!)
  ;; rf2-2moh1 — register the Runtime App DB tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :app-db
     :label "App DB"
     :mnem  "a"
     :modes #{:runtime}
     :order 1
     :panel Panel})
  nil)
