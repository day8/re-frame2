(ns day8.re-frame2-xray.panels.module-view
  "Module-view tab — the EP-0013 disposition-6 demand-trigger surface
  (rf2-wtg9z4).

  ## What this tab shows

  The (realm, frame) ADDRESS SPACE of the running process (EP-0013
  disposition 3): every installed runtime realm, and the frames each
  realm holds. It is a BROWSE surface (Static-style — registry-wide, not
  event-coupled), the cohesive home for app-value / realm / module
  inspection (per Mike's 'cohesive sub-domains get their own tab' ruling —
  realm/module structure earns its own L4 tab rather than piling into
  App-db).

      │ REALMS                                                          │
      │   :rf.realm/default  · 2 frames                                 │
      │     frames: :app/main  :app/cart                                │
      │ ─────────────────────────────────────────────────────────────  │
      │ MODULES                                                         │
      │   :shop/cart                                                    │
      │     owns      :app-db [:cart]                                   │
      │     requires  :rf.capability/http                               │
      │     registers 1 event · 1 sub                                   │

  ## The provenance graduation (rf2-at0oen — the seam SHIPPED)

  EP-0013 disposition 6 kept per-module descriptor PROVENANCE / metadata
  (`:owns` · `:requires` · the owner-stamped descriptors) INTERNAL until a
  Module-view demanded it — and this is that view. The follow-up beads
  (rf2-imquoq → rf2-at0oen) shipped the public realm→installed-app READ
  seam `rf/installed-app` (PR #4061): `(rf/installed-app realm)` returns a
  running realm's installed app value WITHOUT installing anything. A realm
  seated via `rf/install!` returns the RICH constructed value whose
  `:modules` map carries each module's `:owns` / `:requires` and its
  owner-stamped descriptors; a realm on the `reg-*` sugar / load-order path
  has no `:modules` (the registrar projection declares no module), so the
  MODULES section renders the honest no-module caption rather than
  fabricated rows. (EP-0015 classification is FRAME-owned, declared on
  `reg-frame` — not a per-module fact, so it is not surfaced here.)

  ## Public seams used (all genuinely public today)

    - `rf/realm-ids`     — the installed realm ids (EP-0013, PR #4038);
    - `rf/frame-ids`     — the live frame ids;
    - `rf/frame-realm`   — a frame's realm (the frame-side address half);
    - `rf/installed-app` — a realm's installed app value, for the per-module
                           provenance (EP-0013 disposition 6, PR #4061).

  ## Zero-ceremony (single-realm unchanged)

  A single-realm process resolves `rf/realm-ids` to exactly
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
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.module-view-helpers :as h]
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
  `1 event · 1 sub`. Pure string."
  [module-row]
  (let [{:keys [registration-kinds registration-count]} module-row]
    (if (seq registration-kinds)
      (str registration-count " descriptor"
           (when (not= 1 registration-count) "s")
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
       (str "· " frame-count " frame" (when (not= 1 frame-count) "s"))]]
     (when (seq frames)
       [:div {:data-testid (str "rf-xray-module-view-realm-" rid-name "-frames")
              :style       realm-frames-style}
        (str "frames: " (str/join "  " (map str frames)))])]))

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
  "The Module-view tab's root — the (realm, frame) address space of the
  running process (EP-0013 disposition 3) PLUS the per-module provenance read
  off each realm's installed app value (EP-0013 disposition 6 · rf2-at0oen).
  Subscribes to `:rf.xray/module-view` and renders a REALMS section (every
  installed realm + its frames) followed by a MODULES section listing each
  realm's modules with their ownership / capability / descriptor provenance.
  A process running entirely on the `reg-*` sugar / load-order path has no
  constructed app value, so the MODULES section shows the honest no-module
  caption."
  []
  (let [{:keys [realms multi-realm?] :as _data}
        @(rf/subscribe [:rf.xray/module-view])]
    [:section {:data-testid "rf-xray-module-view"
               :style       panel-root-style}
     [:div {:style panel-scroll-container-style}
      ;; REALMS — the installed realms and each realm's frames.
      (section/section-row
        {:label  "Realms"
         :testid "rf-xray-module-view-realms"
         :count* (count realms)}
        (into [:div {:data-testid "rf-xray-module-view-realms-list"}]
              (for [r realms]
                (with-meta (realm-row r) {:key (str (:realm r))}))))
      ;; MODULES — the per-module ownership / capability / descriptor
      ;; provenance, read from each realm's installed app value via
      ;; `rf/installed-app` (EP-0013 disposition 6, rf2-at0oen).
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
  ;; Reads the PUBLIC realm/frame seams (`rf/realm-ids` · `rf/frame-ids` ·
  ;; `rf/frame-realm`) and the realm→installed-app read seam
  ;; (`rf/installed-app`, EP-0013 disposition 6, PR #4061), and projects via
  ;; the pure `module-view-helpers/project-module-view`. Read-only —
  ;; enumerating realms/frames and reading installed app values pins nothing
  ;; and dispatches nothing (`rf/installed-app` is a STATIC read of the
  ;; install-time value, not a routing path).
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
      (h/project-module-view (rf/realm-ids)
                             (rf/frame-ids)
                             frame/frame-realm
                             rf/installed-app)))

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
