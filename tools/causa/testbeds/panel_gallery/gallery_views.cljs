(ns panel-gallery.gallery-views
  "Registered views referenced by Story variant `:component` slots in
  the Causa panel gallery (rf2-1o7mp, rf2-5nvk2).

  Story canvas resolves `:component` to a registered re-frame view via
  `(rf/view <id>)` (per `tools/story/src/re_frame/story/ui/canvas.cljs`).
  The variant frame-provider has already wrapped the rendered tree
  with `[frame-provider {:frame variant-id}]`, so any subscribe inside
  the panel resolves to the variant frame's app-db.

  Each gallery component is a thin wrapper around a single Causa
  panel's root view — it absorbs the Story-supplied args map and
  renders the panel inside a card that sets a fixed-ish height so the
  variants-grid layout stays visually uniform.

  ## Facade-mount discipline (rf2-043uz)

  Each gallery wrapper mounts the panel through its facade view —
  `event-detail/Panel`, `app-db-diff/Panel`, `subscriptions/Panel`. All
  three are registered via `reg-view`, so their `render-fn` is
  wrapped with `:contextType frame-context` by
  `re-frame.views/reg-view*`. The Reagent component-vector form
  `[event-detail/Panel]` is therefore safe here — React
  resolves the wrapped class's `:contextType`, the facade body reads
  `current-frame` correctly, and inside the facade body the per-
  panel discipline (function-call for plain-fn leaves, vector for
  reg-view leaves) takes over.

  The rf2-043uz lesson — function-call for leaves — applies INSIDE
  facade bodies (`(views/subscriptions-panel)` not
  `[views/subscriptions-panel]`); it does NOT apply at the gallery
  wrapper boundary, where every panel root we mount is itself a
  `reg-view` registration that carries `:contextType` through
  React."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            ;; Subscriptions panel deleted with rf2-21ob3 — replaced by
            ;; Views (spec/012-Views.md). Views gallery variants are
            ;; separate follow-on work.
            ;;
            ;; Causality-graph panel deleted with rf2-dqnuu — Causality
            ;; is now a popover (not a tab); see popover/causality.cljs.
            ;; A popover-gallery variant is a separate follow-on bead.
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.time-travel :as time-travel]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.theme.tokens :refer [tokens]]))

(def ^:private card-style
  "Card chrome around an embedded panel — gives the variants-grid a
  consistent visual shell with a labelled border so a reviewer can
  spot the panel boundary."
  {:height          "520px"
   :width           "100%"
   :display         "flex"
   :flex-direction  "column"
   :background      (:bg-1 tokens)
   :border          (str "1px solid " (:border-default tokens))
   :border-radius   "6px"
   :overflow        "hidden"
   :font-family     "system-ui, sans-serif"})

(defn- event-detail-panel
  "Embedded mount of the Causa event-detail panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/event-detail` and `:rf.causa/cascades` reads to the
  variant frame's app-db, where the seed events have written the
  `:trace-buffer` and `:selected-dispatch-id` slots."
  [_args]
  [:div {:style card-style
         :data-testid "panel-gallery-event-detail-card"}
   [event-detail/Panel]])

(defn- app-db-diff-panel
  "Embedded mount of the Causa app-db-diff panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/app-db-diff` reads to the variant frame's app-db, where
  the seed events have written `:epoch-history`,
  `:selected-epoch-id`, `:pinned-slices-store`, and
  `:focused-slice-path`.

  `app-db-diff/Panel` is a `reg-view` registration whose
  body composes `app-db-diff-sections/*` plain fns via Reagent
  vectors — the facade is itself the frame-aware render boundary."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-app-db-diff-card"}
   [app-db-diff/Panel]])

;; Subscriptions panel gallery wrapper deleted with rf2-21ob3 — the
;; Subs panel is replaced by Views (spec/012-Views.md). Views gallery
;; variants are separate follow-on work.

(defn- time-travel-panel
  "Embedded mount of the Causa time-travel panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/time-travel` reads to the variant frame's app-db, where
  the seed events have written `:epoch-history`, `:selected-epoch-id`,
  `:pin-store`, and `:label-input`.

  `time-travel/Panel` is a `reg-view` registration whose
  body composes inline hiccup + sub-views via plain function calls —
  the facade is itself the frame-aware render boundary. The gallery
  wrapper mounts it as a Reagent vector — safe because reg-view
  threads `:contextType` through React."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-time-travel-card"}
   [time-travel/Panel]])

(defn- trace-panel
  "Embedded mount of the Causa trace panel for one Story variant.
  The variant-frame provider above this tree routes
  `:rf.causa/trace-feed` reads to the variant frame's app-db, where
  the seed events have written `:trace-buffer` and (optionally)
  `:trace-filters`.

  `trace/Panel` is a `reg-view` registration; the gallery
  wrapper mounts it as a Reagent vector — safe because reg-view
  threads `:contextType` through React."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-trace-card"}
   [trace/Panel]])

(defn- issues-ribbon-panel
  "Embedded mount of the Causa issues-ribbon panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/issues-ribbon` reads to the variant frame's app-db,
  where the seed events have written `:trace-buffer` and (optionally)
  `:issues-active-severities` / `:issues-active-prefixes` /
  `:issues-since-ms`.

  `issues-ribbon/Panel` is a `reg-view` registration;
  the gallery wrapper mounts it as a Reagent vector — safe because
  reg-view threads `:contextType` through React."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-issues-ribbon-card"}
   [issues-ribbon/Panel]])

;; Causality-graph gallery wrapper deleted with rf2-dqnuu — the
;; causality-graph panel is replaced by the c-key triggered popover
;; (`popover/causality.cljs`). A gallery variant for the popover is a
;; separate follow-on bead.

(defn register!
  "Register every gallery view-id referenced by a variant `:component`.
  Uses `reg-view*` (the runtime-registration surface, per Spec 004)
  because the gallery view-ids are explicit panel-keyed keywords
  rather than auto-derived from a defn symbol — we want
  `:panel-gallery.event-detail/Panel` not
  `:panel-gallery.gallery-views/event-detail-panel`. Idempotent —
  re-registering the same id replaces the handler; subsequent calls
  are silent at the registry."
  []
  (rf/reg-view* :panel-gallery.event-detail/Panel  event-detail-panel)
  (rf/reg-view* :panel-gallery.app-db-diff/Panel   app-db-diff-panel)
  ;; Subscriptions gallery registration removed with rf2-21ob3.
  (rf/reg-view* :panel-gallery.time-travel/Panel   time-travel-panel)
  (rf/reg-view* :panel-gallery.trace/Panel         trace-panel)
  (rf/reg-view* :panel-gallery.issues-ribbon/Panel issues-ribbon-panel)
  ;; Causality-graph registration deleted with rf2-dqnuu.
  nil)
