(ns xspike.arm-b
  "SPIKE (rf2-k97c.1) ARM B — the SAME panel re-authored in Hicasso and
  mounted through Hicasso's own root API, reusing its collector.

  What is REUSED unchanged from production Xray:
    * the subs                (`:rf.xray/app-db-state`, `:rf.xray/app-db-current+diff`)
    * the section model       (`panels.app-db-diff-helpers/current-state-sections`,
                               reached through the sub)
    * the expansion machinery (`views.edn-inspector-state` — the same
                               app-db slice, the same sub id, the same
                               `toggle-node` event, the same
                               `resolve-expanded?` projection)
    * the display bound       (`panels.app-db-diff-format/display-value`)

  What is RE-AUTHORED, and what each conversion cost:
    * the panel root      `rf/reg-view` -> `h/defview`, positional -> props map
    * the two sub reads   `@(rf/subscribe q)` -> `(h/sub q)`
    * the section body    plain hiccup fns copied (they are pure data
                          producers and needed no change) EXCEPT the one
                          hiccup HEAD they emit
    * the value renderer  `[ei/edn-inspector v opts]` — a Reagent-shaped
                          `reg-view` head, and a FORM-2 component — is
                          illegal as a Hicasso head, so it is re-authored
                          as a `defview` with the per-mount closure state
                          replaced by the stable `:site-id` the panel
                          already passes
    * the toggle          `#(dispatch [...])` -> the intent VECTOR at
                          `:on-click` (a `defview` binds no `dispatch`)

  THROWAWAY."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]
            [day8.re-frame2-xray.views.edn-inspector-state :as es]
            [day8.re-frame2-xray.shell :as xray-shell]
            [day8.re-frame2-xray.theme.tokens :refer [tokens mono-stack sans-stack]]))

(def panel-id :rf.xray/app-db)

;; ---------------------------------------------------------------------------
;; The EDN tree — re-authored. Recursion is by plain fns producing hiccup
;; (legal: they are never in HEAD position); only the boundary is a defview.
;; ---------------------------------------------------------------------------

(def ^:private row-style
  {:fontFamily mono-stack :fontSize "12px" :lineHeight "18px"
   :whiteSpace "pre" :color (:text-primary tokens)})

(def ^:private toggle-style
  {:cursor "pointer" :userSelect "none" :color (:accent tokens)
   :background "transparent" :border "none" :padding "0 4px 0 0"
   :fontFamily mono-stack :fontSize "12px"})

(defn- branch? [v] (or (map? v) (vector? v) (seq? v) (set? v)))

(defn- children-of [v]
  (cond
    (map? v)    (map (fn [[k vv]] [k vv]) v)
    (vector? v) (map-indexed (fn [i vv] [i vv]) v)
    (set? v)    (map-indexed (fn [i vv] [i vv]) (vec v))
    (seq? v)    (map-indexed (fn [i vv] [i vv]) (vec v))
    :else       nil))

(defn- leaf-str [v]
  (let [s (pr-str v)]
    (if (> (count s) 200) (str (subs s 0 200) " …") s)))

(defn- node
  "One node of the tree. `expansion` is the map read ONCE at the boundary;
  `site-id` is the stable per-surface id the panel already supplies, and
  it is what makes the per-mount closure the Reagent widget needed
  unnecessary here."
  [{:keys [expansion site-id depth path label value]}]
  (let [default?   (< depth 3)
        expanded?  (es/resolve-expanded? expansion panel-id site-id path default?)
        br?        (branch? value)
        indent     (* depth 12)]
    [:div {:style (assoc row-style :paddingLeft (str indent "px"))
           :data-testid (str "xspike-node-" (pr-str path))}
     (if br?
       [:button {:style toggle-style
                 :data-testid (str "xspike-toggle-" (pr-str path))
                 ;; A `defview` binds no `dispatch`, so the write is stated
                 ;; as an INTENT VECTOR — the Hicasso spelling.
                 :on-click [:rf.xray.edn-inspector/toggle-node
                            panel-id site-id path expanded?]}
        (if expanded? "▾" "▸")]
       [:span {:style {:paddingLeft "14px"}} ""])
     [:span {:style {:color (:accent tokens)}} (str (pr-str label) " ")]
     (if br?
       [:span (str (cond (map? value) "{…}" (set? value) "#{…}" :else "[…]")
                   " " (count value))]
       [:span (leaf-str value)])
     (when (and br? expanded?)
       (into [:div]
             (map (fn [[k vv]]
                    ^{:key (pr-str k)}
                    [:div (node {:expansion expansion :site-id site-id
                                 :depth (inc depth) :path (conj path k)
                                 :label k :value vv})]))
             (children-of value)))]))

(h/defview edn-inspector
  "ARM B's value renderer. The production widget is a Reagent FORM-2
  component whose outer body allocates a per-mount id, a ResizeObserver
  and a projection cache in closure; a Hicasso boundary is a real React
  function component and has no form-2, so the per-mount id is replaced
  by the stable `:site-id` the App-DB panel already passes."
  [{:keys [value site-id header]}]
  (let [expansion (h/sub [es/expansion-slot])]
    [:div {:style {:border (str "1px solid " (:border-default tokens))
                   :borderRadius "4px" :padding "6px" :marginBottom "8px"
                   :background (:bg-1 tokens)}
           :data-testid (str "xspike-inspector-" (pr-str site-id))}
     (when header
       [:div {:style {:fontFamily sans-stack :fontSize "11px"
                      :color (:text-secondary tokens) :marginBottom "4px"}}
        (pr-str site-id)])
     (node {:expansion expansion :site-id site-id :depth 0
            :path [] :label :root :value value})]))

;; ---------------------------------------------------------------------------
;; The section body — plain hiccup producers, copied. Only `value-body`
;; changed: its ONE hiccup head had to be re-pointed at the port above.
;; ---------------------------------------------------------------------------

(def ^:private section-shell-style
  {:marginBottom "10px"})

(defn- value-body [value _before render-id title]
  [edn-inspector {:value   (f/display-value value)
                  :site-id [:rf.xray/app-db render-id]
                  :header  title}])

(defn- section [testid title body]
  [:section {:data-testid testid :style section-shell-style}
   [:h3 {:style {:fontFamily sans-stack :fontSize "11px"
                 :textTransform "uppercase" :color (:text-secondary tokens)
                 :margin "6px 0 4px 0"}}
    title]
   body])

(defn- state-body [{:keys [top areas]}]
  (into [:div {:data-testid "rf-xray-app-db-state"}
         (section "rf-xray-app-db-state-top" "app-db"
                  (value-body top nil "top" "app-db"))]
        (map (fn [{:keys [area kind value instances]}]
               ^{:key (pr-str area)}
               [:div (section (str "rf-xray-app-db-state-" (name area))
                              (pr-str area)
                              (if (= kind :instances)
                                (into [:div]
                                      (map (fn [{:keys [id] :as inst}]
                                             ^{:key (pr-str id)}
                                             [:div (value-body (:value inst) nil
                                                               (str (name area) "-" (pr-str id))
                                                               (pr-str id))]))
                                      instances)
                                (value-body value nil (name area) (pr-str area))))])
              areas)))

;; ---------------------------------------------------------------------------
;; The panel — `rf/reg-view` -> `h/defview`, positional args -> props map
;; ---------------------------------------------------------------------------

(h/defview Panel
  "ARM B copy of the production app-db tab root view."
  [_props]
  (let [section-model     (h/sub [:rf.xray/app-db-state])
        selected-epoch-id (:epoch-id (h/sub [:rf.xray/app-db-current+diff]))]
    [:section {:data-testid            "rf-xray-app-db-diff"
               :data-rf-xray-diff-mode "full+diff"
               :data-xspike-epoch      (pr-str selected-epoch-id)
               :style {:height "100%" :display "flex" :flexDirection "column"
                       :background (:bg-2 tokens) :color (:text-primary tokens)
                       :fontFamily sans-stack :fontSize "14px"}}
     [:div {:style {:flex 1 :overflow "auto"}}
      (state-body section-model)]]))

;; ---------------------------------------------------------------------------
;; The tool-owned root — Hicasso's own, never the host adapter's :render
;; ---------------------------------------------------------------------------

(defonce ^:private root (h/client-root))
(defonce ^:private live? (atom false))

(defn mounted? [] @live?)

(defn mount! [node-el]
  (when-not @live?
    (h/render! root
               [h/frame-provider {:frame xray-shell/default-frame-id}
                [Panel {}]]
               node-el)
    (reset! live? true))
  nil)

(defn unmount! []
  (when @live?
    (reset! live? false)
    (h/unmount! root))
  nil)
