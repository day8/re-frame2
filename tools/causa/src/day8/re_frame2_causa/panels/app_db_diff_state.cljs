(ns day8.re-frame2-causa.panels.app-db-diff-state
  "Current-state inspector sections for the app-db tab (rf2-okvit).

  The app-db tab is a CURRENT-STATE inspector — the re-frame-10x look —
  NOT a diff. This ns renders the section model
  `app-db-diff-helpers/current-state-sections` produces:

    - a TOP section — the app-db MINUS every reserved `:rf/*` key (the
      user-domain app-db).
    - one section per reserved `:rf/*` area (per spec/Conventions.md
      §Reserved app-db keys). Map-of-instances areas (`:rf/machines`,
      `:rf/spawned`) FAN OUT to one named sub-section per instance —
      section title = the instance id (e.g. `:title/flow`). Singleton
      slices (`:rf/route`, `:rf/system-ids`, `:rf/pending-navigation`,
      `:rf/elision`) render as one section each.

  Every reserved area renders even when absent/empty — an empty-state
  placeholder — so the developer sees the full reserved inventory.

  ## cljs-devtools current-state render

  Values render through the canonical EDN widget's `inspect` (the
  cljs-devtools / re-frame-10x current-state path), the same engine the
  Trace / Event-coeffects / machine-snapshot surfaces use. NOT the
  home-grown diff engine — this view carries no diff / value-changed
  affordances.

  The inspect call opts OUT of the EDN widget's universal ⎘ copy gesture
  (`:copy? false`, rf2-ilubp) — the app-db tab is a clean current-state
  view, not a per-block toolbox. Every other EDN-widget surface (Trace,
  segment-inspector, Event lens, Static panels) keeps the default-on
  copy gesture.

  Pure hiccup; reuses Causa's theme tokens so light/dark resolve."
  (:require [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.views.edn-widget.widget :as edn]))

;; ---- shared section chrome ----------------------------------------------

(defn- section-shell
  "A titled card surface used by every current-state section. `title`
  is hiccup (so callers can colour the reserved-area / instance-id
  label); `testid` hooks the section for tests; `body` is the section's
  content hiccup. Reuses Causa's `:bg-3` card + subtle-border chrome so
  light/dark resolve via the token CSS variables."
  [{:keys [testid title body]}]
  [:section {:data-testid testid
             :style       {:margin        "12px"
                           :background    (:bg-3 tokens)
                           :border        (str "1px solid " (:border-subtle tokens))
                           :border-radius "4px"
                           :overflow      "hidden"}}
   [:header {:style {:display         "flex"
                     :align-items     "center"
                     :gap             "8px"
                     :padding         "6px 12px"
                     :border-bottom   (str "1px solid " (:border-subtle tokens))
                     :font-family     sans-stack
                     :font-size       "11px"
                     :font-weight     600
                     :text-transform  "uppercase"
                     :letter-spacing  "0.5px"
                     :color           (:text-secondary tokens)}}
    [:span {:style {:overflow      "hidden"
                    :text-overflow "ellipsis"
                    :white-space   "nowrap"
                    :flex          1}}
     title]]
   [:div {:style {:padding "8px 12px"}}
    body]])

(defn- empty-body
  "Empty-state placeholder body for an absent / empty section. `label`
  is a short prose hint (e.g. \"no machines\")."
  [label]
  [:div {:style {:font-family sans-stack
                 :font-size   "12px"
                 :font-style  "italic"
                 :color       (:text-tertiary tokens)}}
   label])

(defn- value-body
  "Render a current-state VALUE via the canonical EDN widget's
  cljs-devtools inspect path (re-frame-10x look). `render-id` keeps
  adjacent inspects' testids independent across the panel.

  `f/display-value` runs first so giant string leaves collapse to the
  `:rf.size/large-elided` display marker before cljs-devtools renders —
  the same display-side bound the old slice renderer applied. This keeps
  a 20 KiB payload from flooding the inspector (and from leaking the raw
  bytes into the rendered text); the data-classification sentinels
  (`:rf/redacted` / `:rf/large`) still get their bespoke chip chrome via
  `edn/inspect`.

  `:copy? false` (rf2-ilubp) opts this render out of the EDN widget's
  universal ⎘ copy gesture — the app-db tab is a clean current-state
  view, not a per-block toolbox. Other EDN-widget surfaces keep it on."
  [value render-id]
  (edn/inspect (f/display-value value)
               (str "app-db-state/" render-id)
               {:copy? false}))

;; ---- top (user-domain) section ------------------------------------------

(defn top-section
  "The TOP section — the app-db MINUS every reserved `:rf/*` key (the
  user-domain app-db). Renders the whole user-domain value as a
  current-state tree. Empty-state when the user-domain app-db is empty
  (e.g. boot value / reserved-keys-only db)."
  [top]
  (section-shell
    {:testid "rf-causa-app-db-state-top"
     :title  [:span "app-db"]
     :body   (if (and (map? top) (empty? top))
               (empty-body "app-db has no user-domain keys yet.")
               (value-body top "top"))}))

;; ---- reserved-area sections ---------------------------------------------

(defn- area-label
  "Render a reserved-area section title — the bare `:rf/*` key in the
  mode `accent` keyword colour."
  [area]
  [:span {:style {:font-family mono-stack
                  :color       (:accent tokens)}}
   (pr-str area)])

(defn instance-section
  "One fan-out sub-section for a single instance of a map-of-instances
  reserved area — section title = the instance id. Used per machine
  (`:rf/machines`) and per parent (`:rf/spawned`)."
  [area {:keys [id value]}]
  (section-shell
    {:testid (str "rf-causa-app-db-state-instance-"
                  (pr-str area) "-" (pr-str id))
     :title  [:span
              (area-label area)
              [:span {:style {:margin "0 6px" :color (:text-tertiary tokens)}}
               "›"]
              [:span {:style {:font-family mono-stack
                              :color       (:text-primary tokens)}}
               (pr-str id)]]
     :body   (value-body value (str (pr-str area) "/" (pr-str id)))}))

(defn instances-area
  "Render a map-of-instances reserved area (`:rf/machines`,
  `:rf/spawned`). Fans out to one `instance-section` per id. When the
  registry is empty/absent, renders a single empty-state section so the
  area is still visible in the inventory."
  [{:keys [area empty? instances]}]
  (if empty?
    (section-shell
      {:testid (str "rf-causa-app-db-state-area-" (pr-str area))
       :title  (area-label area)
       :body   (empty-body
                 (case area
                   :rf/machines "No machines registered."
                   :rf/spawned  "No spawned actors."
                   "Empty."))})
    (into [:div {:data-testid (str "rf-causa-app-db-state-area-" (pr-str area))}]
          (for [{:keys [id] :as inst} instances]
            (with-meta (instance-section area inst)
                       {:key (pr-str id)})))))

(defn singleton-area
  "Render a singleton-slice reserved area (`:rf/route`,
  `:rf/system-ids`, `:rf/pending-navigation`, `:rf/elision`) as ONE
  section. The section title is the singular slice name (e.g. `route`
  for `:rf/route`). Empty/absent slices render the empty-state body."
  [{:keys [area empty? value]}]
  (section-shell
    {:testid (str "rf-causa-app-db-state-area-" (pr-str area))
     :title  (area-label area)
     :body   (if empty?
               (empty-body
                 (case area
                   :rf/route              "No active route."
                   :rf/system-ids         "No system-id bindings."
                   :rf/pending-navigation "No navigation pending."
                   :rf/elision            "No elision declarations."
                   "Empty."))
               (value-body value (pr-str area)))}))

(defn area-section
  "Dispatch one reserved-area section entry (from
  `current-state-sections`'s `:areas`) to the matching renderer based
  on its `:kind`."
  [{:keys [kind] :as area-entry}]
  (case kind
    :instances (instances-area area-entry)
    :singleton (singleton-area area-entry)
    ;; Defensive — an unknown kind still renders the bare key so the
    ;; area never silently vanishes.
    (singleton-area area-entry)))

;; ---- panel body ----------------------------------------------------------

(defn state-body
  "Render the full current-state inspector body for the section model
  `current-state-sections` produces: the TOP user-domain section
  followed by one section group per reserved `:rf/*` area (in
  `:areas` order). Pure hiccup; nil-safe (a nil model degrades to an
  empty TOP + no areas)."
  [{:keys [top areas]}]
  (into [:div {:data-testid "rf-causa-app-db-state"}
         (top-section top)]
        (for [{:keys [area] :as area-entry} areas]
          (with-meta (area-section area-entry)
                     {:key (pr-str area)}))))
