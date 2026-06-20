(ns day8.re-frame2-xray.panels.module-view
  "Module-view tab — the EP-0023 `image -> frame -> event stream` public model
  (rf2-32siq3.12).

  ## What this tab shows

  Runtime-structure inspection (per Mike's 'cohesive sub-domains get their own
  tab' ruling). It is a BROWSE surface (Static-style — registry-wide, not
  event-coupled).

  EP-0023 makes the PUBLIC architecture `image -> frame -> event stream`. This
  tab surfaces the public nouns:

    - **IMAGES** — an image presented as a registration-set VALUE: the
      resolved generation's `[kind id]` descriptors (\"which registrations are
      visible to this frame?\"), with each descriptor's provenance (source
      namespace / inline / framework standard).
    - **FRAMES** — a frame presented as an EXECUTION CONTEXT that points at
      the ONE resolved image generation it runs.
    - **frame-derived RESOLUTION** — the lookup path `target frame -> resolved
      image generation -> registration resolution`: what a given frame
      resolves a `(kind, id)` to, via its generation. The same id resolves to
      DIFFERENT descriptors in frames running different images.

  Xray itself runs in its OWN image/frame and inspects the target frame as
  DATA (EP-0023 §Xray Beside The Target) — `image_view_reads/xray-image` is
  Xray's separate registration set; the inspector never shares the target's.

  The FRAMES/IMAGES section is DEMAND-GATED: a process not using `rf/make-frame`
  image-loaded frames renders the calm no-image caption — the honest
  not-using-images state.

  ## Pure hiccup + helpers

  Same contract as every Xray panel — pure hiccup, no Reagent / UIx /
  Helix. The pure data → data projection (the image-view shape) lives in
  `image_view_helpers.cljc` so the algebra runs under the JVM unit-test
  target."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.image-view-helpers :as ih]
            [day8.re-frame2-xray.panels.image-view-reads :as image-reads]
            [day8.re-frame2-xray.theme.section :as section]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- styles --------------------------------------------------------------

(def ^:private panel-root-style
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      "14px"})

(def ^:private panel-scroll-container-style
  {:flex 1 :overflow "auto"})

(def ^:private awaiting-caption-style
  {:color       (:text-tertiary tokens)
   :font-family sans-stack
   :font-size   "12px"
   :line-height 1.5})

(def ^:private frame-id-style
  {:color       (:accent tokens)
   :font-weight 600
   :font-family mono-stack
   :font-size   "12px"
   :padding     "4px 0 1px 0"})

(def ^:private frame-fact-style
  {:color       (:text-secondary tokens)
   :font-family mono-stack
   :font-size   "11px"
   :padding     "0 0 0 18px"})

(def ^:private frame-fact-label-style
  {:color (:text-tertiary tokens)})

(def ^:private descriptor-row-style
  {:color       (:text-secondary tokens)
   :font-family mono-stack
   :font-size   "11px"
   :padding     "0 0 0 36px"})

(def ^:private descriptor-prov-style
  {:color (:text-tertiary tokens)})

;; ---- EP-0023 image/frame row (rf2-32siq3.12) -----------------------------

(defn- descriptor-rows
  "Render a frame's resolved image as its `[kind id]` descriptor list —
  the image presented as a registration-set VALUE (EP-0023 §Image). Each row
  is `kind/id   <provenance>`. Capped to keep the browse calm; the count line
  carries the full total. Pure hiccup."
  [{:keys [descriptors descriptor-count] :as _image}]
  (let [shown (take 24 descriptors)]
    (into [:div]
          (concat
            (for [{:keys [kind id provenance]} shown]
              [:div {:style descriptor-row-style}
               (str kind " " id)
               " "
               [:span {:style descriptor-prov-style}
                (ih/provenance-summary provenance)]])
            (when (> descriptor-count (count shown))
              [[:div {:style descriptor-prov-style}
                (str "… " (- descriptor-count (count shown)) " more")]])))))

(defn- frame-row
  "Render one live frame as an EXECUTION CONTEXT pointing at its resolved
  image generation (EP-0023 §Frame). Shows the frame id, the image summary (N
  descriptors · K kinds), capability requirements, and the resolved `[kind
  id]` descriptor set (the image as a value). Pure hiccup."
  [{:keys [frame-id image capabilities has-adapter?] :as _frame-row}]
  (let [fid-name (if frame-id (str frame-id) "<anonymous>")]
    [:div {:data-testid (str "rf-xray-module-view-frame-" fid-name)}
     [:div {:data-testid (str "rf-xray-module-view-frame-" fid-name "-id")
            :style       frame-id-style}
      fid-name]
     [:div {:style frame-fact-style}
      [:span {:style frame-fact-label-style} "image     "]
      (ih/image-row-summary image)
      ;; Name the composed image ids when present (an anonymous image carries
      ;; no `:rf.image/id` → a nil entry; drop it rather than print "nil").
      (when-let [named (seq (remove nil? (:images image)))]
        (str "  " (str/join " " (map pr-str named))))]
     (when (seq capabilities)
       [:div {:style frame-fact-style}
        [:span {:style frame-fact-label-style} "caps      "]
        (str/join "  " (sort-by str capabilities))])
     (when has-adapter?
       [:div {:style frame-fact-style}
        [:span {:style frame-fact-label-style} "adapter   "]
        "active-substrate binding"])
     [:div {:style frame-fact-style}
      [:span {:style frame-fact-label-style} "resolves  "]
      "this frame resolves (kind id) through its image generation"]
     (descriptor-rows image)]))

(defn- frames-section-body
  "The FRAMES / IMAGES section body — every live image-loaded frame, each as
  an execution context carrying its resolved image (its generation's `[kind
  id]` descriptors). When NO live frame runs a generation, the calm no-image
  caption (EP-0023's public model is opt-in). Pure hiccup."
  [{:keys [frames images?] :as _image-view}]
  (if images?
    (into [:div {:data-testid "rf-xray-module-view-frames-list"}]
          (for [{:keys [frame-id] :as fr} frames]
            (with-meta (frame-row fr)
              {:key (str (or frame-id "<anonymous>"))})))
    [:div {:data-testid "rf-xray-module-view-frames-empty"
           :style       awaiting-caption-style}
     ih/no-images-caption]))

;; ---- public view ---------------------------------------------------------

(rf/reg-view Panel
  "The Module-view tab's root. Renders the EP-0023 PUBLIC model — the
  FRAMES/IMAGES section (`image -> frame -> event stream`: every live
  image-loaded frame as an execution context carrying its resolved image's
  `[kind id]` descriptors, rf2-32siq3.12). Subscribes to `:rf.xray/image-view`.
  A process running entirely on the `reg-*` sugar / load-order path with no
  image-loaded frames renders the honest no-image caption."
  []
  (let [{:keys [frame-count] :as image-view}
        @(rf/subscribe [:rf.xray/image-view])]
    [:section {:data-testid "rf-xray-module-view"
               :style       panel-root-style}
     [:div {:style panel-scroll-container-style}
      ;; FRAMES / IMAGES — the EP-0023 PUBLIC model: every live image-loaded
      ;; frame as an execution context carrying its resolved image (the
      ;; generation's [kind id] descriptors), plus the frame-derived
      ;; resolution path (rf2-32siq3.12).
      (section/section-row
        {:label  "Frames"
         :testid "rf-xray-module-view-frames"
         :count* frame-count}
        (frames-section-body image-view))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Module-view tab's Xray-side registrations
  (rf2-wtg9z4). Registers the image/frame view composite + the Dynamic L4 tab."
  []
  ;; `:rf.xray/image-view` — the EP-0023 PUBLIC `image -> frame` model
  ;; (rf2-32siq3.12). Reads the EP-0023 live-frame registry + sealed image
  ;; generations via the fail-soft `image-view-reads` seam and projects via
  ;; the pure `image-view-helpers/project-image-view`: every live
  ;; image-loaded frame as an execution context carrying its resolved image
  ;; (the generation's [kind id] descriptors). Read-only — enumerating live
  ;; frames + reading sealed generations pins nothing and dispatches nothing
  ;; (a sealed generation is an immutable VALUE, not a routing path). It does
  ;; NOT compose off an `:rf.xray/*` app-db slot: the live-frame registry is a
  ;; process-global fact (it lives in `re-frame.live-frame`, not Xray's
  ;; app-db); the sub reads it directly at recompute time and a tab activation
  ;; re-renders the panel which re-derefs. Xray inspects the target frame as
  ;; DATA here; Xray's OWN image (`image-view-reads/xray-image`) is a separate
  ;; registration set that never mixes with a target frame's image (EP-0023
  ;; §Xray Beside The Target).
  (rf/reg-sub :rf.xray/image-view
    (fn [_db _query]
      (image-reads/image-view-data)))

  ;; Register the Dynamic Module-view tab with the internal L4 tab
  ;; registry. Order 9 — after the Derivation-Graph (order 8), keeping the
  ;; cross-feature runtime-structure tabs adjacent. Label is the domain
  ;; noun "Frames" (the image/frame model is the tab's subject). Like the
  ;; Derivation-Graph tab this is a reg-l4-tab! surface only — it exposes
  ;; no standalone `mount-*!` facade, so it is NOT in `panel-enum` (that
  ;; enum carries the mountable surface; an L4-only tab is shell-internal).
  (panel-registry/reg-l4-tab!
    {:id    :module-view
     :label "Frames"
     :mnem  "u"
     :modes #{:dynamic}
     :order 9
     :panel Panel})

  nil)
