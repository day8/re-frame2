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
            [day8.re-frame2-causa.panels.ai-co-pilot :as ai-co-pilot]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.causality-graph :as causality-graph]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.subscriptions :as subscriptions]
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

(defn- subscriptions-panel
  "Embedded mount of the Causa subscriptions panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/subscriptions-data` reads to the variant frame's app-db,
  where the seed events have written `:sub-cache-override`,
  `:sub-error-cache`, `:selected-sub`, and `:sub-filters`.

  `subscriptions/Panel` is a `reg-view` whose body
  invokes `(subscriptions-views/subscriptions-panel)` via plain
  function call (rf2-043uz) — that internal call form keeps the
  leaf's subscribes inside the facade reg-view wrapper's render so
  frame-context resolves correctly. The gallery wrapper mounts the
  facade view as a Reagent vector — safe here because reg-view
  threads `:contextType` through React."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-subscriptions-card"}
   [subscriptions/Panel]])

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

(defn- causality-graph-panel
  "Embedded mount of the Causa causality-graph panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/causality-graph-data` reads to the variant frame's
  app-db, where the seed events have written `:trace-buffer` and
  (optionally) `:selected-dispatch-id`.

  `causality-graph/Panel` is a `reg-view` registration;
  the gallery wrapper mounts it as a Reagent vector — safe because
  reg-view threads `:contextType` through React.

  ## Note on cross-variant cache (rf2-rj40a)

  Per `causality-graph.cljs` §graph-layout-cache the panel maintains
  a `defonce`-backed cache keyed on `[cascades buffer]` identity.
  Distinct variant frames produce distinct buffers, so each variant's
  topology lands its own cache entry — the cache stays correct under
  the gallery's multi-variant render."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-causality-graph-card"}
   [causality-graph/Panel]])

(defn- ai-co-pilot-panel
  "Embedded mount of the Causa AI Co-Pilot canvas-form panel for one
  Story variant. The variant-frame provider above this tree routes
  the seven `:rf.causa/copilot-*` reads to the variant frame's
  app-db, where the seed event has written `:copilot-conversation`,
  `:copilot-input-text`, `:copilot-provider`, etc.

  `ai-co-pilot/Panel` is a `reg-view` registration whose
  body calls `(views/ai-co-pilot-view)` as a plain function per the
  rf2-043uz facade discipline — that internal call keeps the leaf's
  subscribes / dispatches inside the facade reg-view wrapper's
  render so the variant frame's context propagates. The gallery
  wrapper mounts the facade view as a Reagent vector — safe because
  reg-view threads `:contextType` through React."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-ai-co-pilot-card"}
   [ai-co-pilot/Panel]])

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
  (rf/reg-view* :panel-gallery.subscriptions/Panel subscriptions-panel)
  (rf/reg-view* :panel-gallery.time-travel/Panel   time-travel-panel)
  (rf/reg-view* :panel-gallery.trace/Panel         trace-panel)
  (rf/reg-view* :panel-gallery.issues-ribbon/Panel issues-ribbon-panel)
  (rf/reg-view* :panel-gallery.causality-graph/Panel causality-graph-panel)
  (rf/reg-view* :panel-gallery.ai-co-pilot/Panel    ai-co-pilot-panel)
  nil)
