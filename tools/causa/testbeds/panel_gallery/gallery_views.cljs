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
  `event-detail-view`, `app-db-diff-view`, `subscriptions-view`. All
  three are registered via `reg-view`, so their `render-fn` is
  wrapped with `:contextType frame-context` by
  `re-frame.views/reg-view*`. The Reagent component-vector form
  `[event-detail/event-detail-view]` is therefore safe here — React
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
            [day8.re-frame2-causa.panels.subscriptions :as subscriptions]
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
   [event-detail/event-detail-view]])

(defn- app-db-diff-panel
  "Embedded mount of the Causa app-db-diff panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/app-db-diff` reads to the variant frame's app-db, where
  the seed events have written `:epoch-history`,
  `:selected-epoch-id`, `:pinned-slices-store`, and
  `:focused-slice-path`.

  `app-db-diff/app-db-diff-view` is a `reg-view` registration whose
  body composes `app-db-diff-sections/*` plain fns via Reagent
  vectors — the facade is itself the frame-aware render boundary."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-app-db-diff-card"}
   [app-db-diff/app-db-diff-view]])

(defn- subscriptions-panel
  "Embedded mount of the Causa subscriptions panel for one Story
  variant. The variant-frame provider above this tree routes
  `:rf.causa/subscriptions-data` reads to the variant frame's app-db,
  where the seed events have written `:sub-cache-override`,
  `:sub-error-cache`, `:selected-sub`, and `:sub-filters`.

  `subscriptions/subscriptions-view` is a `reg-view` whose body
  invokes `(subscriptions-views/subscriptions-panel)` via plain
  function call (rf2-043uz) — that internal call form keeps the
  leaf's subscribes inside the facade reg-view wrapper's render so
  frame-context resolves correctly. The gallery wrapper mounts the
  facade view as a Reagent vector — safe here because reg-view
  threads `:contextType` through React."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-subscriptions-card"}
   [subscriptions/subscriptions-view]])

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
  nil)
