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
      │   Per-module ownership, capability requirements, classification │
      │   and descriptor provenance await a public realm→installed-app  │
      │   read seam (EP-0013 disposition 6 graduation).                 │

  ## The provenance graduation (why MODULES is scaffolded)

  EP-0013 disposition 6 keeps per-module descriptor PROVENANCE / metadata
  (`:owns` · `:requires` · EP-0015 classification · source coords)
  INTERNAL until a Module-view demands it — and this is that view. But
  reading those facts from a RUNNING process needs a CORE seam that has
  not shipped: there is no public read of a realm's installed app value,
  and the internal projection over a realm's registrar carries no module
  structure at all (the per-module facts exist only on a CONSTRUCTED app
  value — `rf/app` / `rf/module`). Per the rf2-wtg9z4 brief, this slice
  does NOT silently expand core scope to invent the seam — a follow-up
  bead is filed for the public realm→installed-app provenance read
  surface, and the MODULES section renders the calm awaiting-seam caption
  until it graduates. The row shapes already carry the
  `:owns` / `:requires` / `:classification` slots so the fill-in is a
  no-reshape change.

  ## Public seams used (all genuinely public today)

    - `rf/realm-ids`  — the installed realm ids (EP-0013, PR #4038);
    - `rf/frame-ids`  — the live frame ids;
    - `rf/frame-realm`— a frame's realm (the frame-side address half).

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

(rf/reg-view Panel
  "The Module-view tab's root — the (realm, frame) address space of the
  running process (EP-0013 disposition 3 · rf2-wtg9z4). Subscribes to
  `:rf.xray/module-view` (the projected address space) and renders a
  REALMS section (every installed realm + its frames) followed by a
  MODULES section that, until the per-module provenance seam graduates
  (rf2-wtg9z4 follow-up rf2-imquoq), reads the calm awaiting-seam caption."
  []
  (let [{:keys [realms provenance-available?] :as _data}
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
      ;; MODULES — the per-module ownership / capability / classification /
      ;; provenance facts. Scaffolded: until the core realm→installed-app
      ;; read seam graduates (rf2-wtg9z4 follow-up rf2-imquoq) this reads the calm
      ;; awaiting-seam caption rather than fabricating data.
      (section/section-row
        {:label  "Modules"
         :testid "rf-xray-module-view-modules"}
        (if provenance-available?
          ;; rf2-wtg9z4 follow-up rf2-imquoq fills this in from the graduated seam.
          [:div {:data-testid "rf-xray-module-view-modules-list"}]
          [:div {:data-testid "rf-xray-module-view-modules-awaiting"
                 :style       awaiting-caption-style}
           h/awaiting-provenance-caption]))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Module-view tab's Xray-side registrations
  (rf2-wtg9z4). Registers the address-space composite + the Dynamic L4
  tab."
  []
  ;; `:rf.xray/module-view` — the projected (realm, frame) address space.
  ;; Reads the PUBLIC realm/frame seams (`rf/realm-ids` · `rf/frame-ids` ·
  ;; `rf/frame-realm`) and projects via the pure
  ;; `module-view-helpers/project-module-view`. Read-only — enumerating
  ;; realms/frames pins nothing and dispatches nothing.
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
                             frame/frame-realm)))

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
