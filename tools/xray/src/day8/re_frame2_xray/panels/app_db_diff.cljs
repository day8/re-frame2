(ns day8.re-frame2-xray.panels.app-db-diff
  "app-db tab — current-state inspector (rf2-okvit).

  The app-db tab is a CURRENT-STATE inspector in the re-frame-10x style:
  it renders the observed frame's LIVE `app-db` value, sectioned by
  reserved `:rf/*` area. It is NOT a diff — the diff / value-changed /
  'show me when this changed' affordances were dropped here (rf2-okvit).
  Diff rendering lives in the Epoch panel's `:db` + `:fx` section.

  ## Layout (rf2-okvit)

  The section model `app-db-diff-helpers/current-state-sections`
  produces drives the body:

    - a TOP section — the app-db MINUS every reserved `:rf/*` key (the
      user-domain app-db). Per spec/Conventions.md §Reserved app-db
      keys the runtime owns ONE top-level slot, `:rf/runtime`, and any
      `:rf.<subns>/*` keys the framework stashes at the root.
    - one section per operator-facing runtime area (per the
      `runtime-areas` table — machines, routing, spawned, system-ids,
      pending-navigation, elision — all nested under `:rf/runtime`).
      Map-of-instances areas (`:rf/machines`, `:rf/spawned`) FAN OUT to
      one named sub-section per instance (section title = the instance
      id, e.g. `:title/flow`). Singleton slices (the current-route
      slice at `[:rf/runtime :routing :current]` per spec/012 §The
      `:rf/route` slice, and the rest) render as one section each.
      Absent / empty areas still render, as an empty-state placeholder.

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
    survives there for the Epoch panel's diff surface + the MCP exporter;
    this panel reads only `:rf.xray/app-db-state`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.app-db-diff-events :as events]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack]]
            ))

;; ---- style hoists (rf2-mndut) -------------------------------------------
;;
;; Every literal `:style {...}` map in the Panel view below is hoisted to
;; ns-top defs so React's reconciler sees stable object identities across
;; re-renders (follow-on to rf2-qx414 / rf2-zlk6h / rf2-xjgdk / rf2-gjiog
;; / rf2-alsnz). `tokens` values resolve to `var(--rf-xray-*)` CSS strings
;; at ns load so the light/dark theme toggle continues to flip palette in
;; lockstep without re-evaluation (spec/007 §UX-IA).

(def ^:private panel-root-style
  "Outer `[:section]` chrome for the app-db Panel view."
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      "14px"})

(def ^:private panel-body-host-style
  "Scrolling host for the section list / flat-diff body."
  {:flex     1
   :overflow "auto"})

(rf/reg-view Panel
  "The app-db tab's root view — a current-state inspector sectioned by
  reserved `:rf/*` area.

  rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]` mode
  toggle (rf2-yqjrd) is retired. FULL+DIFF is the single rendering:
  every section renders LIVE current-state WITH the focused epoch's
  `:db-before` threaded so inline diff annotations paint. The auto-
  collapse of unchanged subtrees (rf2-fqcdd), the leaf-scalar `← was X`
  annotation (rf2-fyd8u), and the added/removed colouring (rf2-9d4j8)
  together give FULL+DIFF the density `:diff` used to provide and the
  comparison-context `:full` lacked, so the three-mode toggle (and its
  sub/event/slot trio) is gone."
  []
  (let [section-model @(rf/subscribe [:rf.xray/app-db-state])]
    [:section {:data-testid "rf-xray-app-db-diff"
               ;; rf2-xvu24 — canonical `data-rf-xray-diff-mode` axis on
               ;; the enclosing section. FULL+DIFF is the single mode
               ;; post-rf2-vv3m6 so the attribute is now a constant
               ;; (kept for selector compatibility — tools + e2e specs
               ;; can still pin "this section is rendering FULL+DIFF").
               :data-rf-xray-diff-mode "full+diff"
               :style       panel-root-style}
     ;; rf2-6xezz — the L4 tab strip is the panel-name source-of-truth;
     ;; content starts immediately under the tab bar.
     [:div {:style panel-body-host-style}
      (state/state-body section-model)]]))

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
