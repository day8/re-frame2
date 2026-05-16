(ns panel-gallery.gallery-views
  "Registered views referenced by Story variant `:component` slots in
  the Causa panel gallery (rf2-1o7mp).

  Story canvas resolves `:component` to a registered re-frame view via
  `(rf/view <id>)` (per `tools/story/src/re_frame/story/ui/canvas.cljs`).
  The variant frame-provider has already wrapped the rendered tree
  with `[frame-provider {:frame variant-id}]`, so any subscribe inside
  the panel resolves to the variant frame's app-db.

  Each gallery component is a thin wrapper around a single Causa
  panel's root view — it absorbs the Story-supplied args map and
  renders the panel inside a card that sets a fixed-ish height so the
  variants-grid layout stays visually uniform."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
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
  (rf/reg-view* :panel-gallery.event-detail/Panel event-detail-panel)
  nil)
