(ns day8.re-frame2-xray.panels.module-view
  "Module-view tab — the EP-0013 disposition-6 demand-trigger surface
  (rf2-wtg9z4) PLUS the EP-0023 `image -> frame` public model (rf2-32siq3.12).

  ## What this tab shows

  TWO models, side by side — the cohesive home for runtime-structure
  inspection (per Mike's 'cohesive sub-domains get their own tab' ruling). It
  is a BROWSE surface (Static-style — registry-wide, not event-coupled).

  ### EP-0013 substrate (retained internally)

  The (realm, frame) ADDRESS SPACE of the running process (EP-0013
  disposition 3): every installed runtime realm, and the frames each realm
  holds, plus the per-module ownership / capability / descriptor provenance.

  ### EP-0023 public model (rf2-32siq3.12)

  EP-0023 makes the PUBLIC architecture `image -> frame -> event stream`,
  partially superseding the EP-0013 app/realm surface while RETAINING the
  realm machinery as the internal substrate above. This tab surfaces the
  public nouns:

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

  The EP-0023 sections are DEMAND-GATED (like the realm dimension): a process
  not using `rf/make-frame` image-loaded frames renders the calm no-image
  caption — the honest not-using-images state.

      │ REALMS                                                          │
      │   :rf.realm/default  · 2 frames                                 │
      │     frames: :app/main  :app/cart                                │
      │ ─────────────────────────────────────────────────────────────  │
      │ MODULES                                                         │
      │   :shop/cart                                                    │
      │     owns      :app-db [:cart]                                   │
      │     requires  :rf.capability/http                               │
      │     registers 2 descriptors (event · sub)                       │

  ## The provenance graduation (rf2-at0oen — the seam SHIPPED)

  EP-0013 disposition 6 kept per-module descriptor PROVENANCE / metadata
  (`:owns` · `:requires` · the owner-stamped descriptors) INTERNAL until a
  Module-view demanded it — and this is that view. The follow-up beads
  (rf2-imquoq → rf2-at0oen) shipped the realm→installed-app READ
  seam `re-frame.realm/installed-app`: `(realm/installed-app realm)` returns a
  running realm's installed app value WITHOUT installing anything (EP-0023
  retained this as internal substrate; pl97nd.2 removed the `rf/installed-app`
  facade alias). A realm seated via the install path returns the RICH
  constructed value whose `:modules` map carries each module's `:owns` /
  `:requires` and its owner-stamped descriptors; a realm on the `reg-*` sugar /
  load-order path
  has no `:modules` (the registrar projection declares no module), so the
  MODULES section renders the honest no-module caption rather than
  fabricated rows. (EP-0015 classification is FRAME-owned, declared on
  `reg-frame` — not a per-module fact, so it is not surfaced here.)

  ## Substrate seams used (internal — EP-0023 retained-internal)

  EP-0023 removed the realm/install/query family from the public facade
  (pl97nd.2) and retained the realm machinery as the internal installation
  substrate. This tab reads it through the OWNING internal namespaces directly
  (a tool may, exactly as it already reads `re-frame.frame` /
  `re-frame.live-frame`; bundle isolation forbids `implementation/` requiring
  from `tools/`, not the reverse):

    - `re-frame.realm/realm-ids`     — the installed realm ids;
    - `rf/frame-ids`                 — the live frame ids (retained PUBLIC);
    - `re-frame.frame/frame-realm`   — a frame's realm (the frame-side address);
    - `re-frame.realm/installed-app` — a realm's installed app value, for the
                                       per-module provenance (EP-0013
                                       disposition 6).

  ## Zero-ceremony (single-realm unchanged)

  A single-realm process resolves `re-frame.realm/realm-ids` to exactly
  `#{:rf.realm/default}`; the tab renders one realm with the realm
  dimension implicit (no realm grouping ceremony) — the (realm) axis is
  spelled only when more than one realm exists, exactly as a single-frame
  app never spells a frame.

  ## Pure hiccup + helpers

  Same contract as every Xray panel — pure hiccup, no Reagent / UIx /
  Helix. The pure data → data projection (address-space assembly, the
  realm-row shape, single/multi-realm classification) lives in
  `module_view_helpers.cljc` so the algebra runs under the JVM unit-test
  target."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.realm :as realm]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.panels.module-view-helpers :as h]
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

(def ^:private realm-row-style
  {:display       "flex"
   :align-items   "baseline"
   :gap           "8px"
   :padding       "2px 0"
   :font-family   mono-stack
   :font-size     "12px"})

(def ^:private realm-id-style
  {:color       (:accent tokens)
   :font-weight 600})

(def ^:private realm-meta-style
  {:color (:text-tertiary tokens)})

(def ^:private realm-frames-style
  {:color       (:text-secondary tokens)
   :padding     "1px 0 4px 18px"
   :font-family mono-stack
   :font-size   "11px"})

(def ^:private awaiting-caption-style
  {:color       (:text-tertiary tokens)
   :font-family sans-stack
   :font-size   "12px"
   :line-height 1.5})

(def ^:private module-id-style
  {:color       (:accent tokens)
   :font-weight 600
   :font-family mono-stack
   :font-size   "12px"
   :padding     "4px 0 1px 0"})

(def ^:private module-fact-style
  {:color       (:text-secondary tokens)
   :font-family mono-stack
   :font-size   "11px"
   :padding     "0 0 0 18px"})

(def ^:private module-fact-label-style
  {:color (:text-tertiary tokens)})

(def ^:private module-realm-header-style
  {:color          (:text-tertiary tokens)
   :font-family    sans-stack
   :font-size      "11px"
   :letter-spacing "0.4px"
   :text-transform "uppercase"
   :padding        "6px 0 2px 0"})

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

;; ---- module row ----------------------------------------------------------

(defn- owns-summary
  "A compact one-line rendering of a module's `:owns` declarations —
  `:app-db [:cart]  :routes :shop/checkout`. Empty when the module owns
  nothing. Pure string."
  [owns]
  (->> owns
       (sort-by (comp str key))
       (keep (fn [[kind paths]]
               (when (seq paths)
                 (str kind " " (str/join " " (map pr-str paths))))))
       (str/join "   ")))

(defn- registers-summary
  "A compact rendering of the registry kinds a module owns —
  `2 descriptors (event · sub)` (the count, then the dot-joined kinds in
  parens; singular `1 descriptor` for one). Pure string."
  [module-row]
  (let [{:keys [registration-kinds registration-count]} module-row]
    (if (seq registration-kinds)
      (str registration-count " " (common/pluralize registration-count "descriptor")
           " (" (str/join " · " (map name registration-kinds)) ")")
      "no registrations")))

(defn- module-row
  "Render one module's provenance: its id + owned surfaces + capability
  requirements + the descriptor kinds it registers. Pure hiccup."
  [{:keys [module-id owns requires source] :as m-row}]
  (let [mid-name (str module-id)]
    [:div {:data-testid (str "rf-xray-module-view-module-" mid-name)}
     [:div {:data-testid (str "rf-xray-module-view-module-" mid-name "-id")
            :style       module-id-style}
      mid-name]
     (when (seq owns)
       [:div {:style module-fact-style}
        [:span {:style module-fact-label-style} "owns      "]
        (owns-summary owns)])
     (when (seq requires)
       [:div {:style module-fact-style}
        [:span {:style module-fact-label-style} "requires  "]
        (str/join "  " (sort-by str requires))])
     [:div {:style module-fact-style}
      [:span {:style module-fact-label-style} "registers "]
      (registers-summary m-row)]
     (when source
       [:div {:style module-fact-style}
        [:span {:style module-fact-label-style} "source    "]
        (str (:ns source)
             (when (:line source) (str ":" (:line source))))])]))

;; ---- realm row -----------------------------------------------------------

(defn- realm-row
  "Render one realm: its id + frame count, with the realm's frames listed
  beneath. Pure hiccup."
  [{:keys [realm frames frame-count] :as _realm-row}]
  (let [rid-name (name realm)]
    [:div {:data-testid (str "rf-xray-module-view-realm-" rid-name)}
     [:div {:style realm-row-style}
      [:span {:data-testid (str "rf-xray-module-view-realm-" rid-name "-id")
              :style       realm-id-style}
       (str realm)]
      [:span {:style realm-meta-style}
       (str "· " frame-count " " (common/pluralize frame-count "frame"))]]
     (when (seq frames)
       [:div {:data-testid (str "rf-xray-module-view-realm-" rid-name "-frames")
              :style       realm-frames-style}
        (str "frames: " (str/join "  " (map str frames)))])]))

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
  caption (EP-0023's public model is opt-in over the retained substrate).
  Pure hiccup."
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

(defn- modules-section-body
  "The MODULES section body — every realm's modules, grouped by realm only
  when the process is multi-realm (zero-ceremony: a single-realm process
  lists its modules with the realm dimension implicit). The empty-state is a
  THREE-WAY decision (rf2-e0mq7a):

    1. some realm has module rows → render the module list.
    2. some realm carries PROVENANCE (a constructed, installed app) but NO
       realm has any modules (every constructed app has `:modules []`) →
       render the zero-module-app caption — the installed-but-empty state.
    3. no realm carries provenance at all (load-order / sugar-only) → render
       the no-provenance caption.

  Cases 2 and 3 are distinct: a zero-module constructed app must NOT render the
  load-order caption (which falsely claims the process never installed an app).
  Pure hiccup."
  [realms multi-realm?]
  (cond
    (h/any-modules? realms)
    (into [:div {:data-testid "rf-xray-module-view-modules-list"}]
          (for [{:keys [realm modules] :as _r} realms
                :when (seq modules)]
            (with-meta
              [:div {:data-testid (str "rf-xray-module-view-modules-realm-"
                                       (name realm))}
               ;; Spell the realm header only when more than one realm exists
               ;; (zero-ceremony — single-realm keeps the realm implicit).
               (when multi-realm?
                 [:div {:style module-realm-header-style} (str realm)])
               (into [:div]
                     (for [m modules]
                       (with-meta (module-row m)
                         {:key (str (:module-id m))})))]
              {:key (str realm)})))

    ;; Provenance present (constructed + installed) but no modules anywhere —
    ;; the honest installed-but-empty state, NOT the load-order caption.
    (h/any-provenance? realms)
    [:div {:data-testid "rf-xray-module-view-modules-zero-module-app"
           :style       awaiting-caption-style}
     h/zero-module-app-caption]

    ;; No provenance on any realm — the load-order / sugar-only process.
    :else
    [:div {:data-testid "rf-xray-module-view-modules-empty"
           :style       awaiting-caption-style}
     h/no-modules-caption]))

(rf/reg-view Panel
  "The Module-view tab's root. Renders the EP-0023 PUBLIC model FIRST — the
  FRAMES/IMAGES section (`image -> frame -> event stream`: every live
  image-loaded frame as an execution context carrying its resolved image's
  `[kind id]` descriptors, rf2-32siq3.12) — then the RETAINED-INTERNAL
  EP-0013 substrate below: the REALMS section (the (realm, frame) address
  space, disposition 3) and the MODULES section (per-module ownership /
  capability / descriptor provenance read off each realm's installed app
  value via `re-frame.realm/installed-app`, disposition 6 · rf2-at0oen).
  Subscribes to `:rf.xray/image-view` (the public model) and
  `:rf.xray/module-view` (the substrate). A process running entirely on the
  `reg-*` sugar / load-order path has no constructed app value, so the MODULES
  section shows the honest no-module caption — which names the retained-internal
  app-composition substrate remedy and points back at the image/frame public
  model."
  []
  (let [{:keys [realms multi-realm?] :as _data}
        @(rf/subscribe [:rf.xray/module-view])
        {:keys [frame-count] :as image-view}
        @(rf/subscribe [:rf.xray/image-view])]
    [:section {:data-testid "rf-xray-module-view"
               :style       panel-root-style}
     [:div {:style panel-scroll-container-style}
      ;; FRAMES / IMAGES — the EP-0023 PUBLIC model: every live image-loaded
      ;; frame as an execution context carrying its resolved image (the
      ;; generation's [kind id] descriptors), plus the frame-derived
      ;; resolution path. Rendered FIRST — EP-0023 is the public model; the
      ;; EP-0013 realm/module sections below are the retained internal
      ;; substrate (rf2-32siq3.12).
      (section/section-row
        {:label  "Frames"
         :testid "rf-xray-module-view-frames"
         :count* frame-count}
        (frames-section-body image-view))
      ;; REALMS — the installed realms and each realm's frames (EP-0013
      ;; substrate, retained internally).
      (section/section-row
        {:label  "Realms"
         :testid "rf-xray-module-view-realms"
         :count* (count realms)}
        (into [:div {:data-testid "rf-xray-module-view-realms-list"}]
              (for [r realms]
                (with-meta (realm-row r) {:key (str (:realm r))}))))
      ;; MODULES — the per-module ownership / capability / descriptor
      ;; provenance, read from each realm's installed app value via
      ;; `re-frame.realm/installed-app` (EP-0013 disposition 6, rf2-at0oen).
      (section/section-row
        {:label  "Modules"
         :testid "rf-xray-module-view-modules"}
        (modules-section-body realms multi-realm?))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Module-view tab's Xray-side registrations
  (rf2-wtg9z4). Registers the address-space composite + the Dynamic L4
  tab."
  []
  ;; `:rf.xray/module-view` — the projected (realm, frame) address space PLUS
  ;; the per-module provenance read off each realm's installed app value.
  ;; Reads the INTERNAL realm/frame substrate seams (`re-frame.realm/realm-ids`
  ;; · `re-frame.frame/frame-realm`) + the retained-public `rf/frame-ids`, and
  ;; the realm→installed-app read seam (`re-frame.realm/installed-app`, EP-0013
  ;; disposition 6) — EP-0023 retained-internal, read off the owning namespaces
  ;; directly (pl97nd.2 removed the `rf/*` facade arities). Projects via the
  ;; pure `module-view-helpers/project-module-view`. Read-only — enumerating
  ;; realms/frames and reading installed app values pins nothing and dispatches
  ;; nothing (`installed-app` is a STATIC read of the install-time value, not a
  ;; routing path).
  ;;
  ;; It does NOT compose off an `:rf.xray/*` app-db slot because the
  ;; address space is a process-global fact (realms + frames live in the
  ;; framework's registries, not Xray's app-db). The sub reads them
  ;; directly at recompute time; a tab activation (`:rf.xray/select-tab`)
  ;; re-renders the panel which re-derefs. (A future live-refresh signal
  ;; can compose this off a frame/realm-lifecycle tick if the operator
  ;; wants push updates; today the browse-on-open shape matches the other
  ;; Static-style surfaces.)
  (rf/reg-sub :rf.xray/module-view
    (fn [_db _query]
      (h/project-module-view (realm/realm-ids)
                             (rf/frame-ids)
                             frame/frame-realm
                             realm/installed-app)))

  ;; `:rf.xray/image-view` — the EP-0023 PUBLIC `image -> frame` model
  ;; (rf2-32siq3.12). Reads the EP-0023 live-frame registry + sealed image
  ;; generations via the fail-soft `image-view-reads` seam and projects via
  ;; the pure `image-view-helpers/project-image-view`: every live
  ;; image-loaded frame as an execution context carrying its resolved image
  ;; (the generation's [kind id] descriptors). Read-only — enumerating live
  ;; frames + reading sealed generations pins nothing and dispatches nothing
  ;; (a sealed generation is an immutable VALUE, not a routing path). Like
  ;; `:rf.xray/module-view` it does NOT compose off an `:rf.xray/*` app-db
  ;; slot: the live-frame registry is a process-global fact (it lives in
  ;; `re-frame.live-frame`, not Xray's app-db); the sub reads it directly at
  ;; recompute time and a tab activation re-renders the panel which
  ;; re-derefs. Xray inspects the target frame as DATA here; Xray's OWN
  ;; image (`image-view-reads/xray-image`) is a separate registration set
  ;; that never mixes with a target frame's image (EP-0023 §Xray Beside The
  ;; Target).
  (rf/reg-sub :rf.xray/image-view
    (fn [_db _query]
      (image-reads/image-view-data)))

  ;; Register the Dynamic Module-view tab with the internal L4 tab
  ;; registry. Order 9 — after the Derivation-Graph (order 8), keeping the
  ;; cross-feature runtime-structure tabs adjacent. Label is the domain
  ;; noun "Modules" (the app-value module is the tab's subject). Like the
  ;; Derivation-Graph tab this is a reg-l4-tab! surface only — it exposes
  ;; no standalone `mount-*!` facade, so it is NOT in `panel-enum` (that
  ;; enum carries the mountable surface; an L4-only tab is shell-internal).
  (panel-registry/reg-l4-tab!
    {:id    :module-view
     :label "Modules"
     :mnem  "u"
     :modes #{:dynamic}
     :order 9
     :panel Panel})

  nil)
