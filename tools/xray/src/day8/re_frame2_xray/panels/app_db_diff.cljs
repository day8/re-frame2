(ns day8.re-frame2-xray.panels.app-db-diff
  "app-db tab — current-state inspector (rf2-okvit).

  The app-db tab is a CURRENT-STATE inspector in the re-frame-10x style:
  it renders the observed frame's LIVE `app-db` value, sectioned by
  reserved `:rf/*` area. It is NOT a diff — the diff / value-changed /
  'show me when this changed' affordances were dropped here (rf2-okvit).
  Diff rendering still lives in the Event tab's `:db` + `:fx` section.

  ## Layout (rf2-okvit)

  The section model `app-db-diff-helpers/current-state-sections`
  produces drives the body:

    - a TOP section — the app-db MINUS every reserved `:rf/*` key (the
      user-domain app-db).
    - one section per reserved `:rf/*` area (per spec/Conventions.md
      §Reserved app-db keys: `:rf/machines`, `:rf/spawned`, `:rf/route`,
      `:rf/system-ids`, `:rf/pending-navigation`, `:rf/elision`).
      Map-of-instances areas (`:rf/machines`, `:rf/spawned`) FAN OUT to
      one named sub-section per instance (section title = the instance
      id, e.g. `:title/flow`). Singleton slices (`:rf/route` SINGULAR —
      the current-route slice per spec/012 §The `:rf/route` slice — and
      the rest) render as one section each. Absent / empty areas still
      render, as an empty-state placeholder.

  Values render through the canonical EDN widget's cljs-devtools
  current-state path (`views.edn-widget.widget/inspect`), the same
  engine re-frame-10x adopted.

  Canonical exemplar of the panel facade pattern documented in
  `tools/xray/spec/Conventions.md` — facade owns the public
  `reg-view`, leaves expose plain fns + `install!`, the facade's
  `install!` chains leaf installs and returns `nil`.

  ## Companion namespaces

  - `app-db-diff-state` — the current-state section renderers (this
    panel's body).
  - `app-db-diff-subs` / `app-db-diff-events` — subs + events. The
    composite diff sub (`:rf.xray/selected-epoch-diff` and friends)
    survives there for the Event tab's diff surface + the MCP exporter;
    this panel reads only `:rf.xray/app-db-state`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.app-db-diff-events :as events]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack]]
            [day8.re-frame2-xray.views.diff-mode-toggle :as diff-mode]))

(rf/reg-view Panel
  "The app-db tab's root view — a current-state inspector sectioned by
  reserved `:rf/*` area.

  rf2-yqjrd — carries the universal three-mode toggle
  `[diff][full][full+diff]` at the top. The toggle drives every
  section's rendering uniformly:

  - `:diff`      — pure-diff lens at the top of the panel (flat path
                   list of changes the focused epoch produced).
                   Per-section bodies are suppressed.
  - `:full`      — every section renders LIVE current-state with no
                   `:before` threading (plain browse mode).
  - `:full+diff` — every section renders LIVE current-state WITH the
                   focused epoch's `:db-before` threaded so inline
                   diff annotations paint (default; mode-3).

  Mode persists in `:rf.xray.app-db/view-mode` on Xray's app-db so the
  operator's preference survives focus shifts."
  []
  (let [mode          @(rf/subscribe [:rf.xray.app-db/view-mode])
        section-model @(rf/subscribe [:rf.xray/app-db-state])
        diff-triples  @(rf/subscribe [:rf.xray/selected-epoch-diff])]
    [:section {:data-testid "rf-xray-app-db-diff"
               :data-view-mode (name mode)
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     ;; rf2-yqjrd — universal three-mode toggle. Sits at the top of the
     ;; panel above the section list; one mode applies to every
     ;; section's body. Frame-anchored to `:rf/xray` per the same
     ;; rf2-p56sk / rf2-7sdja / rf2-kcaiz pattern the HANDLER `:db`
     ;; toggle uses.
     [:div {:style {:padding "12px 12px 0"
                    :display "flex"
                    :align-items "center"
                    :gap "8px"}}
      [:span {:style {:font-family sans-stack
                      :font-size "11px"
                      :font-weight 600
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"
                      :color (:text-secondary tokens)}}
       "View"]
      [diff-mode/diff-mode-toggle
       {:mode mode
        :testid "rf-xray-app-db-view-mode"
        :on-change (fn [m]
                     (rf/with-frame :rf/xray
                       (rf/dispatch [:rf.xray.app-db/set-view-mode m])))}]]
     ;; rf2-6xezz — the L4 tab strip is the panel-name source-of-truth;
     ;; content starts immediately under the tab bar.
     [:div {:style {:flex 1 :overflow "auto"}}
      (state/state-body section-model {:mode mode :diff-triples diff-triples})]]))

(defn install!
  "Idempotent install for the app-db tab's Xray-side registrations.
  Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  ;; rf2-2moh1 — register the Dynamic app-db tab with the internal L4
  ;; tab registry. rf2-okvit — label is lowercase "app-db" to match the
  ;; library's app-db naming.
  (panel-registry/reg-l4-tab!
    {:id    :app-db
     :label "app-db"
     :mnem  "a"
     :modes #{:dynamic}
     :order 1
     :panel Panel})
  nil)
