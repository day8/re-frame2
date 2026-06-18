(ns day8.re-frame2-xray.panels.module-view-helpers
  "Pure-data helpers for Xray's Module-view tab — the EP-0013 disposition-6
  demand-trigger surface (rf2-wtg9z4).

  ## What this view shows

  EP-0013 introduces *app values* (immutable descriptions of a program,
  composed from feature *modules*) and *runtime realms* (the containers a
  program is installed into). The Module-view is the disposition-6 NAMED
  DEMAND TRIGGER: the view that inspects, per module, the public
  descriptor facts EP-0013 disposition 6 rules —

    - **ownership** (`:owns`) — the app-db paths / routes / resources a
      module declares it owns;
    - **capability requirements** (`:requires`) — the `:rf.capability/*`
      a module's install demands;

  plus, as the trigger itself, **descriptor provenance** (the registry
  kinds and descriptor count each module owns, owner-stamped; source
  coordinates). EP-0015 classification is FRAME-owned (declared on
  `reg-frame`, not on a module), so it is NOT a per-module fact — the
  classification dimension lives on the frame side, not in `:modules`.

  ## The provenance graduation (rf2-at0oen — the seam SHIPPED)

  EP-0013 kept descriptor PROVENANCE / module metadata INTERNAL until a
  Module-view demanded it — and this is that view. The graduation was a
  CORE slice: the per-module facts above live on a CONSTRUCTED app value's
  `:modules` map (the app-composition substrate), and a RUNNING realm exposed
  no read of its installed app value. The follow-up beads
  (rf2-imquoq → rf2-at0oen) shipped the realm read seam
  `re-frame.realm/installed-app`: the realm→installed-app READ that yields a
  running realm's installed app value WITHOUT installing anything (EP-0023
  retained this as internal substrate; pl97nd.2 removed the `rf/installed-app`
  facade alias).

    - `(realm/installed-app realm)` returns the app value the realm runs;
    - a realm seated via the install path returns the RICH constructed value —
      its `:modules` map carries each module's `:owns` / `:requires` and
      its `:owner`-stamped descriptors;
    - a realm seated only through the `reg-*` sugar (load-order, no
      install) returns the registrar PROJECTION — registrations by kind,
      but NO `:modules` (load-order registrations declare no module). The
      MODULES section renders the honest no-module caption there, not
      fabricated rows.

  This slice reads `(realm/installed-app realm)` per realm and projects its
  `:modules` into the module rows below — no reshape from the scaffold: the
  realm-row already carried the `:modules` / `:owns` / `:requires` slots,
  now FILLED from the seam.

  ## Zero-ceremony (single-realm unchanged)

  In a single-realm process `re-frame.realm/realm-ids` returns exactly
  `#{:rf.realm/default}`. The view still renders (a Module-view is a
  browse surface, not an event-coupled lens), but it surfaces ONE realm
  with no realm-grouping ceremony — the (realm) dimension is implicit,
  exactly as the single-frame app never spells a frame. `multi-realm?`
  drives whether the realm dimension is foregrounded.

  ## Why a separate `.cljc` ns

  Mirrors every other Xray panel: the panel view paints + dispatches; the
  pure data → data projection (realm address-space assembly, the
  module-row shape, the single/multi-realm classification) lives here so
  it runs under the JVM unit-test target (`clojure -M:test`)."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.common-helpers :as common]))

;; ---- realm / frame address space (EP-0013 disposition 3) ----------------

(defn realm-frames
  "Group `frame-ids` by the realm each belongs to, using `realm-of` (a
  `frame-id → realm-id` resolver, normally `re-frame.frame/frame-realm`).
  Returns
  `{realm-id #{frame-id …}}`. A frame whose `realm-of` resolves to nil
  falls into the `:rf.realm/default` bucket (absence = default realm, the
  EP-0013 D1 rule). Pure data → data; JVM-testable."
  [frame-ids realm-of]
  (reduce
    (fn [acc fid]
      (let [rid (or (realm-of fid) :rf.realm/default)]
        (update acc rid (fnil conj #{}) fid)))
    {}
    frame-ids))

(defn project-module-row
  "Project one MODULE value (from `(realm/installed-app realm) :modules`) into
  the Module-view's module-row shape:

      {:module-id          <:rf.module/id>
       :owns               {:app-db [[:cart] …] :routes […] …}  ;; the module's
                                       ;; declared feature ownership
                                       ;; (`:rf.module/owns`), kept under the
                                       ;; panel-internal row key `:owns`
       :requires           #{:rf.capability/* …}  ;; its capability requirements
                                       ;; (`:rf.module/requires`), kept under
                                       ;; the panel-internal row key `:requires`
       :registration-kinds [<kind> …]  ;; the registry kinds it registers under,
                                        ;; sorted for stable render (provenance)
       :registration-count <int>       ;; total descriptors the module carries
       :source             {:ns … :file … :line … :column …}}  ;; or nil

  The module value is the descriptor EP-0013 §Module Values pins
  (`:rf.module/id` · `:rf.module/owns` · `:rf.module/requires` · `:registrations`
  (kind→id→descriptor, each `:owner`-stamped) · optional `:source`). This row surfaces
  the per-module PROVENANCE the disposition-6 demand trigger names: ownership,
  capability requirements, and the kinds/count of the descriptors the module
  owns. (EP-0015 classification is FRAME-owned — declared on `reg-frame`, not
  on a module — so it is NOT a module-row fact; see §6 of the panel spec.)

  Pure data → data; JVM-testable."
  [module]
  (let [registrations (:registrations module)
        kinds         (vec (sort-by str (keys registrations)))]
    {:module-id          (:rf.module/id module)
     :owns               (get module :rf.module/owns {})
     :requires           (set (get module :rf.module/requires #{}))
     :registration-kinds kinds
     :registration-count (reduce + 0 (map (comp count val) registrations))
     :source             (:source module)}))

(defn project-app-modules
  "Project a realm's installed APP VALUE (from `re-frame.realm/installed-app`)
  into the Module-view's per-realm module rows. Returns

      {:modules   [<module-row> …]      ;; sorted by module-id str, or nil when
                                        ;; the app carries no `:modules`
       :requires  #{:rf.capability/* …} ;; the app's union capability set
                                        ;; (read off the app value's
                                        ;; `:rf.app/requires` slot)}

  An app value carries `:modules` (a `{module-id module}` map) ONLY when it was
  CONSTRUCTED (the app-composition substrate); a realm seated through the `reg-*`
  sugar (load-order, no install) carries NO `:modules` — its installed app is
  the registrar projection (EP-0013 disposition 6, the honest no-provenance
  case). `nil` app (no seam value) is likewise module-less.

  The PRESENCE of the `:modules` KEY — not its non-emptiness — decides
  provenance (rf2-e0mq7a). The core app-value contract (app_value.cljc) is
  explicit: a projected/load-order app carries NO `:modules` slot, while an app
  CONSTRUCTED from zero modules carries an EMPTY `:modules {}` map. So:

    - `:modules` key ABSENT/nil → `:modules` projects to nil — the honest
      no-provenance (load-order / sugar-only) case.
    - `:modules` key PRESENT (even `{}`) → `:modules` projects to a VECTOR
      (`[]` for a zero-module constructed app) — an installed constructed app.
      This must NOT collapse to nil, or the panel falsely renders the
      load-order/no-provenance caption over a genuinely-installed zero-module
      app.

  `:modules` is therefore one of: nil (no provenance), `[]` (constructed, zero
  modules), or a non-empty row vector. Pure data → data; JVM-testable."
  [app]
  (let [modules (:modules app)]
    {:modules  (when (some? modules)
                 (->> (vals modules)
                      (sort-by (comp str :rf.module/id))
                      (mapv project-module-row)))
     :requires (set (get app :rf.app/requires #{}))}))

(defn project-realm-row
  "Project one realm into the Module-view's realm-row shape:

      {:realm          <realm-id>
       :frames         [<frame-id> …]   ;; sorted for stable render
       :frame-count    <int>
       ;; per-module provenance — filled from `(realm/installed-app realm)`
       ;; (EP-0013 disposition 6, rf2-at0oen). `:modules` is nil for a
       ;; load-order (sugar-only / module-less) app — the honest
       ;; no-provenance case; the realm renders its address row but no module
       ;; rows. `:modules` is an empty VECTOR `[]` for a CONSTRUCTED app with
       ;; zero modules (rf2-e0mq7a) — present-but-empty provenance, NOT the
       ;; no-provenance case; the realm carries an installed constructed app.
       :modules        [<module-row> …] | [] | nil
       :requires       #{:rf.capability/* …}  ;; the app's union requires
       :owns           nil                    ;; reserved — ownership lives per
                                              ;; module (`:modules … :owns`)
       :classification nil}                   ;; reserved — EP-0015
                                              ;; classification is frame-owned,
                                              ;; not a module fact (see §6)

  `realm-id` is the realm; `frames` is the set of frame-ids in that realm
  (from `realm-frames`); `app` is the realm's installed app value
  (`re-frame.realm/installed-app realm`), or nil. Pure data → data;
  JVM-testable."
  ([realm-id frames] (project-realm-row realm-id frames nil))
  ([realm-id frames app]
   (let [{:keys [modules requires]} (project-app-modules app)]
     {:realm          realm-id
      :frames         (vec (sort-by str frames))
      :frame-count    (count frames)
      :modules        modules
      :requires       requires
      :owns           nil
      :classification nil})))

(defn project-module-view
  "Top-level projection — produce every slot the Module-view needs from
  the (realm, frame) address space (EP-0013 disposition 3) PLUS the
  per-module provenance read off each realm's installed app value (EP-0013
  disposition 6, rf2-at0oen). Pure data → data; JVM-testable.

  `realm-ids` is the set of installed realm ids (`re-frame.realm/realm-ids`);
  `frame-ids` is the set/seq of live frame ids (retained-public `rf/frame-ids`);
  `realm-of` resolves a frame's realm (`re-frame.frame/frame-realm`);
  `installed-app-of` resolves a realm's installed app value
  (`re-frame.realm/installed-app`) — a `realm-id → app-value` fn. Optional: the
  3-arity (no resolver) renders the address space with no module provenance,
  which keeps the legacy address-only call site working.

  Returns:

      {:realms        [{:realm … :frames … :modules … :requires … …} …]
                                     ;; per realm, sorted by realm-id, each
                                     ;; carrying its installed app's modules
       :realm-count   <int>
       :multi-realm?  <bool>        ;; >1 realm → the realm dimension is
                                    ;; foregrounded; single-realm renders
                                    ;; with the (realm) dimension implicit
       :provenance-available? true} ;; rf2-at0oen — the
                                    ;; realm→installed-app read seam
                                    ;; (`re-frame.realm/installed-app`) has
                                    ;; graduated, so
                                    ;; the MODULES section reads real module
                                    ;; provenance. A realm whose installed app
                                    ;; carries no `:modules` (a load-order /
                                    ;; sugar-only app) renders no module rows —
                                    ;; the honest no-provenance case, NOT the
                                    ;; absent-seam caption.

  Every installed realm appears even when it holds no frames (a realm can
  exist with zero frames). Pure data → data."
  ([realm-ids frame-ids realm-of]
   (project-module-view realm-ids frame-ids realm-of (constantly nil)))
  ([realm-ids frame-ids realm-of installed-app-of]
   (let [by-realm (realm-frames frame-ids realm-of)
         ;; Every installed realm, plus any realm a frame resolves into
         ;; that somehow isn't in realm-ids (defensive — the two should
         ;; agree, but a stale frame must never strand the view).
         all-rids (into (set realm-ids) (keys by-realm))
         realms   (->> all-rids
                       (sort-by str)
                       (mapv (fn [rid]
                               (project-realm-row rid
                                                  (get by-realm rid #{})
                                                  (installed-app-of rid)))))]
     {:realms                realms
      :realm-count           (count realms)
      :multi-realm?          (> (count realms) 1)
      ;; rf2-at0oen — the `re-frame.realm/installed-app` read seam shipped;
      ;; the MODULES section reads real per-module provenance.
      :provenance-available? true})))

;; ---- no-provenance empty-state caption (rf2-at0oen) ---------------------

(def no-modules-caption
  "The calm empty-state caption the MODULES section renders when NO realm's
  installed app carries module provenance (rf2-at0oen). The
  `re-frame.realm/installed-app` seam HAS graduated (so `:provenance-available?`
  is true), but a process running entirely on the `reg-*` sugar / load-order
  path has no CONSTRUCTED app value — its installed app is the registrar
  projection, which carries no `:modules`. Names why the section is empty so the
  operator understands it is the honest no-module state, not a broken surface.

  Points the operator at the EP-0023 PUBLIC model (image → frame → event
  stream, the FRAMES/IMAGES section above) and frames the app-composition
  remedy as the RETAINED-INTERNAL EP-0013 installation substrate — NOT the
  central public vocabulary. EP-0023 removed that substrate's construction /
  install vocabulary from the public facade (pl97nd.2): the public model is
  `image → frame`, and the module-provenance facts here come from the internal
  substrate the public model rides on.

  This caption is for the NO-PROVENANCE case ONLY (`:modules` absent/nil on
  every realm). An installed CONSTRUCTED app that simply has zero modules
  (`:modules {}` → `[]`) carries provenance — it must render
  `zero-module-app-caption`, not this one (rf2-e0mq7a). Pure data → string."
  (str "No module provenance — this process is running on the load-order "
       "(reg-* sugar) path, whose installed value carries no modules. Module "
       "ownership / capability / descriptor provenance comes from the "
       "retained-internal app-composition substrate (compose an app from "
       "modules and install it) to surface those facts here. The PUBLIC model "
       "is image → frame → event stream (see the FRAMES/IMAGES section above) — "
       "this MODULES section reflects the EP-0013 installation substrate the "
       "public model rides on, kept as implementation structure."))

(def zero-module-app-caption
  "The empty-state caption the MODULES section renders when at least one realm
  carries module PROVENANCE (a CONSTRUCTED, installed app — `:modules` present)
  but no realm declares any modules — every constructed app was composed from
  ZERO modules (`:modules {}` → `[]`) (rf2-e0mq7a). Distinct from
  `no-modules-caption`: this app IS constructed and installed (it did NOT run on
  the load-order path), it simply owns no modules yet. The honest
  installed-but-empty state, NOT the no-provenance one. Pure data → string."
  (str "Installed app has zero modules — this process's app value was "
       "constructed and installed (the retained-internal app-composition "
       "substrate) but composed from no modules, so there is no per-module "
       "ownership, capability, or descriptor provenance to show. Add modules "
       "to the app to populate this section. The PUBLIC model is "
       "image → frame → event stream (see the FRAMES/IMAGES section above); "
       "this section reflects the EP-0013 installation substrate."))

(defn any-provenance?
  "True when at least one realm-row carries module PROVENANCE — i.e. some
  realm's installed app was CONSTRUCTED and seated, so its `:modules` slot is a
  VECTOR (`[]` for a zero-module app, or a non-empty row vector). A
  no-provenance (load-order / sugar-only) realm carries `:modules` nil and does
  NOT count. Used by the panel to decide whether ANY realm has a constructed app
  at all — separating the no-provenance caption (no constructed app anywhere)
  from the zero-module-app caption (a constructed app with no modules)
  (rf2-e0mq7a). Pure data → bool; JVM-testable."
  [realm-rows]
  (boolean (some (comp vector? :modules) realm-rows)))

(defn any-modules?
  "True when at least one realm-row carries at least one MODULE ROW — i.e. some
  realm's installed app was constructed and seated with a NON-EMPTY `:modules`
  vector. A constructed-but-zero-module app (`:modules []`) carries provenance
  (`any-provenance?` is true) but NO module rows, so `any-modules?` is false for
  it. Used by the panel to decide whether to render the module list at all.
  Pure data → bool; JVM-testable."
  [realm-rows]
  (boolean (some (comp seq :modules) realm-rows)))

(defn realm-summary-line
  "A one-line plain-language summary for a realm row — `<realm-id> · N
  frame(s)`. Pure data → string; JVM-testable."
  [{:keys [realm frame-count] :as _realm-row}]
  (str realm " · " frame-count " " (common/pluralize frame-count "frame")))
