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

  ## Single default realm (realm-grouping removed — rf2-70owfr)

  The afdlyr realm-substrate collapse leaves a single default realm
  (`re-frame.realm/realm-ids` is always `#{:rf.realm/default}`), so the
  per-realm address-space grouping that this view used to render (one
  realm-row per realm, frames grouped by realm) was dead ceremony — there
  was never more than one realm. The MODULES section now reads the per-module
  provenance off the ONE default realm's installed app value directly. The
  frame/image dimension is the FRAMES section's concern (the EP-0023 public
  model), not a realm address-space here.

  ## Why a separate `.cljc` ns

  Mirrors every other Xray panel: the panel view paints + dispatches; the
  pure data → data projection (the module-row shape, the app-modules
  projection, the three-way empty-state classification) lives here so it
  runs under the JVM unit-test target (`clojure -M:test`).")

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

(defn project-module-view
  "Top-level projection — produce the per-module provenance the Module-view's
  MODULES section needs, read off the default realm's installed app value
  (EP-0013 disposition 6, rf2-at0oen). Pure data → data; JVM-testable.

  The afdlyr realm-substrate collapse leaves a single default realm, so the
  former (realm, frame) address-space grouping was removed (rf2-70owfr) — there
  is exactly one realm and the MODULES section reads its installed app directly.

  `app` is the default realm's installed app value
  (`(re-frame.realm/installed-app)`), or nil. Optional: the 0-arity (no app)
  renders module-less (no provenance), which keeps a no-runtime call site working.

  Returns:

      {:modules               [<module-row> …] | [] | nil
                                     ;; the installed app's modules, sorted by
                                     ;; module-id; nil when the app carries no
                                     ;; `:modules` (load-order / sugar-only — the
                                     ;; honest no-provenance case), `[]` for a
                                     ;; CONSTRUCTED zero-module app (rf2-e0mq7a)
       :requires              #{:rf.capability/* …}  ;; the app's union requires
       :provenance-available? true} ;; rf2-at0oen — the
                                    ;; `re-frame.realm/installed-app` read seam
                                    ;; has graduated, so the MODULES section
                                    ;; reads real per-module provenance.

  Pure data → data."
  ([] (project-module-view nil))
  ([app]
   (let [{:keys [modules requires]} (project-app-modules app)]
     {:modules               modules
      :requires              requires
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
  "True when the installed app carries module PROVENANCE — i.e. it was
  CONSTRUCTED and seated, so its projected `:modules` slot is a VECTOR (`[]` for a
  zero-module app, or a non-empty row vector). A no-provenance (load-order /
  sugar-only) app carries `:modules` nil and does NOT count. Used by the panel to
  decide whether the app is constructed at all — separating the no-provenance
  caption (no constructed app) from the zero-module-app caption (a constructed app
  with no modules) (rf2-e0mq7a). Takes the projected `:modules` value (nil / `[]`
  / a row vector). Pure data → bool; JVM-testable."
  [modules]
  (vector? modules))

(defn any-modules?
  "True when the installed app carries at least one MODULE ROW — i.e. it was
  constructed and seated with a NON-EMPTY `:modules` vector. A
  constructed-but-zero-module app (`:modules []`) carries provenance
  (`any-provenance?` is true) but NO module rows, so `any-modules?` is false for
  it. Used by the panel to decide whether to render the module list at all. Takes
  the projected `:modules` value. Pure data → bool; JVM-testable."
  [modules]
  (boolean (seq modules)))
