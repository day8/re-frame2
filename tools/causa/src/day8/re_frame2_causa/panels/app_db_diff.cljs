(ns day8.re-frame2-causa.panels.app-db-diff
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
  `tools/causa/spec/Conventions.md` — facade owns the public
  `reg-view`, leaves expose plain fns + `install!`, the facade's
  `install!` chains leaf installs and returns `nil`.

  ## Companion namespaces

  - `app-db-diff-state` — the current-state section renderers (this
    panel's body).
  - `app-db-diff-subs` / `app-db-diff-events` — subs + events. The
    composite diff sub (`:rf.causa/selected-epoch-diff` and friends)
    survives there for the Event tab's diff surface + the MCP exporter;
    this panel reads only `:rf.causa/app-db-state`.
  - `app-db-diff-downstream` — the downstream-subs hover popover
    (spec/021 §4.4). Re-wired into the current-state inspector
    (rf2-2lb7z): the trigger is mounted in each section header by
    `app-db-diff-state` (one per top-level user-domain key, one per
    reserved area `[:rf/area]`, one per fan-out instance `[:rf/area
    id]`). rf2-okvit had dropped the diff breadcrumbs that previously
    hosted it; this leaf re-injects it onto the section headers."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.app-db-diff-downstream
             :as downstream]
            [day8.re-frame2-causa.panels.app-db-diff-events :as events]
            [day8.re-frame2-causa.panels.app-db-diff-state :as state]
            [day8.re-frame2-causa.panels.app-db-diff-subs :as subs]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack]]))

(rf/reg-view Panel
  "The app-db tab's root view — a current-state inspector sectioned by
  reserved `:rf/*` area."
  []
  (let [section-model @(rf/subscribe [:rf.causa/app-db-state])]
    [:section {:data-testid "rf-causa-app-db-diff"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     ;; rf2-6xezz — the L4 tab strip is the panel-name source-of-truth;
     ;; content starts immediately under the tab bar.
     [:div {:style {:flex 1 :overflow "auto"}}
      (state/state-body section-model)]]))

(defn install!
  "Idempotent install for the app-db tab's Causa-side registrations.
  Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  ;; rf2-op9v2 — downstream-subs overlay subs + events. rf2-2lb7z
  ;; re-wired the hover trigger into the current-state inspector's
  ;; section headers (mounted by `app-db-diff-state`); install! here
  ;; registers the subs/events those triggers + the popover read.
  (downstream/install!)
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
