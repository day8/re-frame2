(ns re-frame.realm
  "The runtime realm — the process-default container that owns the non-durable
  operational layer an app runs in (EP-0013 D1, accepted 2026-06-11;
  COLLAPSED to default-only under EP-0023, rf2-afdlyr, 2026-06-19).

  ## EP-0023: collapsed to a single default realm; INTERNAL substrate

  EP-0023 (graduated 2026-06-16) moves the PUBLIC model to
  `image -> frame -> event stream` — a frame's resolved image generation, not a
  realm, is the isolation boundary. The EP-0013 multi-realm substrate (the
  public `construct-realm` / `dispose-realm!` lifecycle, the host-transient
  inventory hatch, and the `app-value` install!/reinstall! seating path) was
  RETIRED under the realm-substrate collapse (Mike 2026-06-19, rf2-afdlyr): it
  had no consumer — non-default realms existed only in the realm-conformance
  tests. What remains is the SINGLE process-default realm and its
  retained-internal seams:

    - the default realm's registrar (the process-global `(kind, id) →
      metadata` atom, the single source of truth `reg-*` writes through);
    - the adapter SELECTION (the `re-frame.substrate.adapter` access seam);
    - the installed-app PROJECTION seam (`installed-app`, feeding the Xray
      Module-view via the `:app-value/project` hook);
    - the realm-targeted registrar-query readers (`realm-registrations` /
      `realm-handler-meta` / `realm-handler-ids`) + `realm-ids` / `realm-frames`
      — now always resolving the one default realm, kept as the INTERNAL/TOOLING
      generation-bypass seam Xray + the pair-MCP read directly off this ns.

  None of this is current public composition vocabulary — the public path is
  `rf/image` + `rf/make-frame`. The realm is implementation structure tooling
  may read but should label as such.

  ## The registrar is owned, not copied

  The default realm OWNS the existing process-global `re-frame.registrar` atom
  (`kind->id->metadata`) by holding a reference to it in its `:registrar` slot.
  The registry shape, the `register!` / `lookup` / `registrations` read+write
  API, and every public `reg-*` / frame signature are unchanged. With a single
  realm the default realm's registrar IS the process-global atom, so every
  `reg-*` / dispatch / subscribe path is byte-identical to a realm-free design.

  ## Production elision

  This ns allocates a small realm map for the default realm at process load
  and exposes pure readers. It has no trace emit sites and no DEBUG-gated
  branches — the realm record is operational metadata, not a dev surface, so
  it survives `:advanced` + `goog.DEBUG=false` builds intact (it carries no
  feature sentinels and no per-feature `:require`, so the bundle-isolation
  gate is unaffected)."
  (:require [re-frame.error        :as error]
            [re-frame.registrar    :as registrar]
            [re-frame.source-store :as source-store]
            [re-frame.late-bind    :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the default realm id -------------------------------------------------

(def ^:const default-realm-id
  "The process default realm's id. Stable, process-unique. Existing
  one-argument / ambient-looking surfaces (`reg-*`, adapter install, the
  registrar query API) resolve through this realm when no explicit realm is
  supplied; absence of `:rf.realm/id` means THIS realm, an explicit
  documented rule (Spec-Schemas §`:rf/realm`, EP-0013 issue 2 disposition)."
  :rf.realm/default)

;; ---- the realm record -----------------------------------------------------
;;
;; Per Spec-Schemas §`:rf/realm` the realm is an open map (an implementation
;; MAY use a host record for efficiency, but MUST expose this data
;; projection for tooling/conformance). D1 keeps it a plain map — none of
;; these fields is a public read surface in D1, and a map IS the data
;; projection. The shipped D1 slots:
;;
;;   :rf.realm/id     stable, process-unique keyword (the default is
;;                    :rf.realm/default)
;;   :registrar       the realm's registrar — the `(kind, id) → descriptor`
;;                    table it dispatches/subscribes/resolves against. The
;;                    default realm holds a REFERENCE to the existing
;;                    process-global `registrar/kind->id->metadata` atom, so
;;                    the registry shape + read/write API are unchanged.
;;   :source-store    the realm's EP-0023 provenance source store — the
;;                    `kind → id → provenance-ns → descriptor` table every
;;                    `reg-*` ALSO writes through (alongside the resolver-map
;;                    write). The default realm holds a REFERENCE to the
;;                    process-global `source-store/kind->id->ns->descriptor`
;;                    atom (the byte-identical single-realm path).
;;   :adapter         the realm's adapter SELECTION (the installed adapter
;;                    spec map / capability); render roots own concrete
;;                    instances. Absent until `install-adapter!` seats one;
;;                    the install/dispose lifecycle is realm-owned as of
;;                    stage 4 (EP-0013 issue 5 — `re-frame.substrate.adapter`
;;                    is now the access seam over this slot).
;;   :adapter-disposed? the realm-owned adapter-lifecycle breadcrumb. `true`
;;                    iff the most recent adapter lifecycle event on this
;;                    realm was a successful `dispose-adapter!` with no
;;                    subsequent install — distinguishes `never installed`
;;                    (fresh realm) from `installed then torn down` so the
;;                    delegation surfaces raise the right diagnosis
;;                    (`:rf.error/no-adapter-installed` vs
;;                    `:rf.error/adapter-disposed`). Absent on a fresh realm
;;                    (treated as `false`). NOT the D2/D3-reserved
;;                    `:lifecycle` slot, which is REALM disposal — this is the
;;                    adapter sub-lifecycle that stage 4 moved behind the realm.
;;   :capabilities    :rf.capability/* → service map/record. Absent in the
;;                    bare default realm; late-bind hooks bridge in (D1
;;                    §Late-bind compatibility).
;;   :frames          the set of frame ids registered in this realm (unique
;;                    WITHIN the realm). Frame records still live in the
;;                    process `frame/frames` atom in D1; this set is the
;;                    realm-owned membership view.
;;
;; D2/D3-RESERVED, never required in D1: :app (the installed app VALUE),
;; :lifecycle ({:disposed? …}). Left absent here.

(defn make-realm
  "Build a realm map. INTERNAL — there is NO public `rf/realm` constructor
  in D1 (EP-0013 issue 1); this is the internal record factory the default
  realm and tests use.

  `opts` may carry `:registrar` (the realm's `(kind, id) → descriptor`
  table — defaults to the process-global `registrar/kind->id->metadata` so a
  realm with no explicit registrar shares the surface existing specs call
  \"global\"), `:source-store` (the realm's EP-0023 provenance source store —
  defaults to the process-global `source-store/kind->id->ns->descriptor`,
  rf2-9fn4is), `:adapter` (selection), and `:capabilities`. The frame set
  starts empty.

  Per Spec-Schemas §`:rf/realm` the realm is an open map; only `:rf.realm/id`
  is required, and the data projection IS the map."
  ([id] (make-realm id nil))
  ([id opts]
   (cond-> {:rf.realm/id id
            ;; The default registrar is the existing process-global atom —
            ;; ownership moves to the realm, the shape does not. A realm
            ;; created with an explicit `:registrar` (hermetic test realm)
            ;; gets its own table; the registry API operates on whichever
            ;; atom the realm hands it (D2 work). In D1 only the default
            ;; realm exists at runtime, so this defaults to the global atom.
            :registrar (get opts :registrar registrar/kind->id->metadata)
            ;; The source store mirrors the registrar's ownership (rf2-9fn4is):
            ;; the default realm holds the process-global provenance store; a
            ;; realm created with an explicit `:source-store` (a hermetic
            ;; constructed realm) gets its own table so `reg-*`'s
            ;; `record-descriptor!` write — keyed by `(active-source-store)`,
            ;; which honors the `*source-store*` binding `seat-into-realm!`
            ;; sets — lands in the realm's own store, not the default's.
            :source-store (get opts :source-store
                               source-store/kind->id->ns->descriptor)}
     (contains? opts :adapter)      (assoc :adapter      (:adapter opts))
     (contains? opts :capabilities) (assoc :capabilities (:capabilities opts)))))

;; ---- the default realm ----------------------------------------------------
;;
;; The process creates ONE default realm at boot, holding the existing
;; process-global registrar atom. It is the compatibility surface for every
;; one-argument / ambient-looking call. A single-realm app never mentions a
;; realm — it carries the default realm implicitly (the EP-0002 refinement
;; pattern). The realm is held in an atom so the install path can record the
;; realm's installed app value at the realm boundary; the live mutation in the
;; collapsed single-realm substrate is the realm-owned adapter SELECTION (the
;; install/dispose lifecycle, stage 4); the frame-membership set is DERIVED
;; (never stored), so it is not a mutation of this atom.

(defonce
  ^{:doc "The process realm registry: realm-id → realm map. Holds exactly one
  entry — the default realm (the realm substrate is collapsed to default-only
  under EP-0023; non-default realm construction was retired). Held as an atom
  so the default realm's realm-owned mutable slots — the adapter SELECTION
  (install/dispose lifecycle, stage 4) and the installed-app `:app` value — can
  be updated in place. The registry is process-wide (Spec-Schemas §`:rf/realm`)."}
  realms
  (atom {default-realm-id (make-realm default-realm-id)}))

(defn- update-realm!
  "Apply `f` (+ `args`) to the realm map registered under `rid`, in place in
  the `realms` registry. INTERNAL — the single mutation seam for the
  realm-owned mutable slots (the installed-app `:app`, the adapter SELECTION).
  Returns the updated realm map.

  FAILS CLOSED on an UNKNOWN id (rf2-c6armm.6 #2): a realm is an isolation
  boundary, so a mutation aimed at a realm that was never constructed must NOT
  silently no-op (the prior posture) — a typo'd or never-constructed id would
  then drop the write on the floor while the caller believes it seated state.
  The id reaching here is already resolved (callers pass `(realm-id …)`, so nil
  has become `default-realm-id`, which is seeded at boot and always present);
  any OTHER id that is not in the registry is an explicit unknown target and
  THROWS `:rf.error/unknown-realm` BEFORE mutating, naming the id + the live
  realm ids. This guards the realm-owned mutation seams (`set-installed-app!`
  / `install-realm-adapter!` / …) — the silent default/global fallback is the
  wrong failure mode for an isolation boundary."
  [rid f & args]
  (let [snapshot @realms]
    (when-not (contains? snapshot rid)
      (error/throw-error!
        :rf.error/unknown-realm
        're-frame.realm/update-realm!
        (str "realm " rid " is not registered — a realm-owned"
             " mutation cannot target a realm that does not exist."
             " Absence defaults to the default realm; an unknown"
             " explicit id does not.")
        {:recovery :construct-the-realm-first
         :extra    {:realm        rid
                    :known-realms (set (keys snapshot))}})))
  (rid (swap! realms
              (fn [m]
                ;; Single-threaded host: the guard above already proved `rid`
                ;; present. Re-check inside the swap so a (theoretical) drop
                ;; between deref and swap can never resurrect the realm via a
                ;; spurious `(f nil …)` — leave the registry untouched instead.
                (if (contains? m rid)
                  (apply update m rid f args)
                  m)))))

(defn default-realm
  "Return the process default realm map — the realm that backs every
  existing one-argument / ambient-looking `reg-*` / adapter-install /
  registrar-query call shape. Absence of an explicit realm means THIS realm
  (Spec-Schemas §`:rf/realm`; EP-0013 issue 2 disposition). INTERNAL in D1 —
  not a public read surface."
  []
  (get @realms default-realm-id))

(defn realm
  "Return the realm map for `id`, or nil. INTERNAL. With the realm substrate
  collapsed to default-only there is one entry at runtime — the default realm;
  this reader resolves a realm map by id for the internal registrar-seating and
  tooling-query seams."
  [id]
  (get @realms id))

(defn realm-ids
  "Return the set of installed realm ids — the keys of the process `realms`
  registry. With the realm substrate collapsed to default-only (EP-0023) this
  is always exactly `#{:rf.realm/default}`: non-default realm construction was
  retired, so no realm joins or leaves the registry at runtime.

  Retained as the internal / tooling enumeration seam — Xray's module-view +
  host-registry read it (always resolving the single default realm) directly off
  this ns, and the pair-MCP emits it as a runtime-call form. It is NOT public
  composition vocabulary (the public model targets a single process-local frame
  — EP-0023 §Surface dispositions); tooling reads it as internal substrate."
  []
  (set (keys @realms)))

(defn realm-id
  "Return a realm's id. Accepts the realm map or, for ergonomic call sites,
  a realm-id keyword (returned unchanged). Returns `default-realm-id` for nil
  — absence is the default realm, the documented rule."
  [realm-or-id]
  (cond
    (nil? realm-or-id)     default-realm-id
    (keyword? realm-or-id) realm-or-id
    :else                  (:rf.realm/id realm-or-id)))

(defn registrar
  "Return the registrar atom a realm dispatches/resolves against — the
  `(kind, id) → descriptor` table. For the default realm this IS the
  existing process-global `registrar/kind->id->metadata`, so the registry
  read/write API is unchanged. Accepts a realm map; nil resolves to the
  default realm's registrar (absence = default realm). INTERNAL."
  ([] (registrar (default-realm)))
  ([realm-map]
   (:registrar (or realm-map (default-realm)))))

(defn source-store
  "Return the EP-0023 provenance source-store atom a realm records descriptors
  into — the `kind → id → provenance-ns → descriptor` table every `reg-*`
  writes through alongside the resolver map. For the default realm this IS the
  process-global `source-store/kind->id->ns->descriptor`, so the single-realm
  path is byte-identical; a hermetic constructed realm holds its OWN atom so its
  provenance descriptors stay isolated from the default store (rf2-9fn4is).
  Accepts a realm map; nil resolves to the default realm's source store
  (absence = default realm). A realm map missing this slot (a hand-built test
  realm map) falls back to the process-global store. INTERNAL — the default
  realm's source store IS the process-global atom, so the single-realm path is
  byte-identical."
  ([] (source-store (default-realm)))
  ([realm-map]
   (or (:source-store (or realm-map (default-realm)))
       source-store/kind->id->ns->descriptor)))

;; ---- realm substrate collapsed to default-only (EP-0023, rf2-afdlyr) -------
;;
;; The EP-0013 PUBLIC realm CONSTRUCTOR (`construct-realm` / `register-realm!`)
;; and its disposal counterpart (`dispose-realm!`) were RETIRED under EP-0023's
;; realm-substrate collapse (Mike 2026-06-19, rf2-b0iye5): non-default realm
;; construction had no consumer — it stood up the only non-default realms that
;; ever existed, and they existed only in the realm-conformance tests. The
;; public model is `image -> frame -> event stream` (EP-0023); a frame's
;; resolved image generation, not a realm, is the isolation boundary. What
;; remains here is the single process-default realm: the registrar-seating
;; container the projection seam + the adapter selection ride on, plus the
;; tooling-query readers below (which now always resolve the one default realm).

;; ---- realm-targeted registrar queries (retained-internal/tooling) ----------
;;
;; The realm-targeted QUERY surface: read the registrations of a realm's OWN
;; registrar. With the realm substrate collapsed to default-only they always
;; resolve the single default realm (whose registrar IS the process-global
;; atom) — the per-realm targeting is vestigial, but the readers survive as the
;; INTERNAL/TOOLING generation-bypass seam: Xray's host-registry + static realm
;; helper read them directly off this ns, and the pair-MCP emits them as
;; runtime-call forms (rf2-10nggz — the public `:realm` registrar-query map
;; arity was already REMOVED; a registrar query is now always a frame-targeted
;; read). They are the realm-scoped counterparts of the process-global
;; `registrar/registrations` / `registrar/handler-meta` / `registrar/ids`.
;;
;; Each accepts a realm-or-id (a realm map, a realm-id keyword, or nil for the
;; default realm — the absence-is-default rule), resolves it to the realm's
;; registrar atom, and reads ONCE. Pure with respect to that read; an unknown
;; realm reads as empty rather than throwing.

(defn- realm-registrar
  "Resolve `realm-or-id` (a realm map, realm-id keyword, or nil) to the
  realm's registrar atom, or nil when the realm is unknown. nil resolves to
  the default realm. INTERNAL."
  [realm-or-id]
  (cond
    (nil? realm-or-id) (registrar (default-realm))
    (map? realm-or-id) (registrar realm-or-id)
    :else              (when-let [r (realm realm-or-id)]
                         (registrar r))))

(defn realm-registrations
  "Return the `{id metadata}` map registered under `kind` in `realm-or-id`'s
  registrar (defaults to — and, under the default-only collapse, always — the
  default realm), or `{}` when none of that kind is registered (or the realm is
  unknown). The realm-scoped counterpart of `registrar/registrations`. INTERNAL
  substrate — survives for the INTERNAL/TOOLING generation-bypass seam (Xray's
  host-registry, the pair-MCP runtime-call form)."
  [realm-or-id kind]
  (if-let [reg (realm-registrar realm-or-id)]
    (get @reg kind {})
    {}))

(defn realm-handler-meta
  "Return the registration metadata map for `[kind id]` in `realm-or-id`'s
  registrar (defaults to / always the default realm), or `nil` when there is no
  such registration (or the realm is unknown). The realm-scoped counterpart of
  `registrar/handler-meta`. INTERNAL substrate — the INTERNAL/TOOLING
  generation-bypass seam (Xray's host-registry, the pair-MCP runtime-call form)."
  [realm-or-id kind id]
  (when-let [reg (realm-registrar realm-or-id)]
    (-> @reg (get kind) (get id))))

(defn realm-handler-ids
  "Return the set of ids registered under `kind` in `realm-or-id`'s registrar
  (defaults to / always the default realm), or `#{}` when none. The realm-scoped
  counterpart of `registrar/ids`. INTERNAL substrate."
  [realm-or-id kind]
  (-> (realm-registrations realm-or-id kind) keys set))

;; ---- realm-owned frame registry (membership view) -------------------------
;;
;; The frame registry is realm-owned (Spec 002 §Frames reference realms,
;; Runtime-Subsystems §What a realm owns). In D1 the frame RECORDS still live
;; in the process `re-frame.frame/frames` atom — moving them into per-realm
;; tables is a later stage. The realm owns the MEMBERSHIP VIEW, derived from
;; that atom by filtering on each frame's `:realm` slot, so there is ONE
;; source of truth and no desync with the many `(reset! frame/frames {})`
;; test fixtures. Frame ids are unique within a realm, not globally (EP-0013
;; issue 3) — the same id in two realms is a tested legal case, so membership
;; is computed per-realm.
;;
;; `re-frame.frame` requires THIS ns (for `default-realm-id` on the frame
;; record), so a static back-require would cycle; the membership reader is
;; reached through the `:realm/frames-by-realm` late-bind hook, which frame
;; publishes at ns-load. Returns `nil` when the hook is unbound (frame ns not
;; yet loaded), which the public reader treats as the empty set.

(defn realm-frames
  "Return the set of frame ids whose `:realm` reference is `rid` (defaults
  to the default realm). The realm-owned membership view, derived live from
  `re-frame.frame/frames` via the `:realm/frames-by-realm` hook — single
  source of truth, no separately-stored set. Returns `#{}` when the frame ns
  is not yet loaded. INTERNAL."
  ([] (realm-frames default-realm-id))
  ([rid]
   (if-let [by-realm (late-bind/get-fn :realm/frames-by-realm)]
     (get (by-realm) rid #{})
     #{})))

;; ---- the installed app VALUE — the projection seam (EP-0013 D2 stage 5) ----
;;
;; The realm's `:app` slot (Spec-Schemas §`:rf/realm`, D2-reserved) is the
;; installed app VALUE — the program-as-a-value the realm is running. D2
;; makes the registrations a realm carries an enumerable, recomputable VALUE
;; projected over its registrar, rather than load-order mutation seen only
;; through the live registrar.
;;
;; With install!/reinstall! retired (rf2-csgz8l) the realm STORES no app value
;; and the `:app` slot stays absent, so the installed app is the RECOMPUTABLE
;; PROJECTION over the realm's own registrar — `re-frame.app-value` owns the
;; descriptor format and the projection fn. Because the registrar is the single
;; source of truth, the ordinary `reg-*` sugar path keeps this app value current
;; for free: a registration updates the registrar, and the next `installed-app`
;; reflects it with no invalidation step and no desync.
;;
;; `re-frame.app-value` requires THIS ns (it projects over the realm's
;; registrar), so a static back-require would cycle; the projection is reached
;; through the `:app-value/project` late-bind hook, which `app-value` publishes
;; at ns-load. Returns `nil` when the hook is unbound (app-value ns not yet
;; loaded) — the realm has no enumerated installed-app projection until then.

(defn installed-app
  "Return the app VALUE installed in `realm-or-id` (defaults to the default
  realm) — the realm's program as a recomputable value (EP-0013 D2 stage 5).
  Returns `nil` when the app-value ns is not yet loaded.

  This is the realm-side read seam over the D2-reserved `:app` slot. EP-0013
  makes the realm's REGISTRAR the single source of truth and reinterprets
  `reg-*` as default-realm SUGAR that updates the installed app value in place
  (EP-0013:138, :838), so the read ALWAYS reflects the LIVE registrar — sugar
  AND installed registrations alike — never a frozen snapshot that could desync
  from `app-value` / dispatch (rf2-77ewnm). Two cases:

    - NO stored `:app` (pure `reg-*` sugar / load-order) — the recomputable
      projection over the realm's registrar (`re-frame.app-value/app-value`, via
      the `:app-value/project` hook), module-less (load-order registrations
      declare no module). This is the live read in the collapsed substrate (the
      install!/reinstall! write path that seated a `:app` value was retired —
      rf2-csgz8l — so the realm never stores a `:app`; the stored branch below
      survives as defensive code over the still-present `:app` slot).
    - A stored `:app` — that SAME live projection ENRICHED with the seated app's
      `:rf.app/id`, `:modules`, and `:rf.app/requires` provenance (via the
      `:app-value/reconcile-installed` hook).

  EP-0023 INTERNAL/TOOLING substrate, NOT current public composition vocabulary
  — the public model inspects a frame's resolved image generation. The Xray
  Module-view reads this seam (directly off this ns) for the per-module
  provenance an installed `:app` would carry; it is a read of the LIVE
  registration value, never a live-dispatch route."
  ([] (installed-app default-realm-id))
  ([realm-or-id]
   (let [rid    (realm-id realm-or-id)
         stored (:app (realm rid))]
     (if stored
       ;; A stored `:app` exists — reconcile it with the live projection so
       ;; coexisting sugar is visible and the seated provenance is preserved
       ;; (rf2-77ewnm). When the reconcile hook is somehow unbound (app-value ns
       ;; not loaded) the stored value is the best available read.
       (if-let [reconcile (late-bind/get-fn :app-value/reconcile-installed)]
         (reconcile rid stored)
         stored)
       ;; No stored `:app` — the recomputable projection IS the realm's program.
       (when-let [project (late-bind/get-fn :app-value/project)]
         (project rid))))))

;; ---- the realm's :app slot — the installed-app write seam ------------------
;;
;; The realm-owned write counterpart of `installed-app`'s read seam: record an
;; app VALUE in the realm's `:app` slot. nil resolves to the default realm.
;; Returns the realm map. INTERNAL — no public realm-mutation surface ships.
;; (The EP-0013 install!/reinstall! path that drove this was retired under the
;; realm-substrate collapse, rf2-csgz8l; the setter survives as the slot's
;; write seam alongside the still-present `:app` slot.)

(defn set-installed-app!
  "Seat `app` as the realm's `:app` slot — the installed app VALUE. The
  realm-owned write counterpart of `installed-app`'s read seam. nil resolves to
  the default realm. Returns the updated realm map. INTERNAL."
  ([app] (set-installed-app! default-realm-id app))
  ([realm-or-id app]
   (update-realm! (realm-id realm-or-id) assoc :app app)))

;; ---- realm-owned adapter SELECTION (EP-0013 D1 stage 4, rf2-0lq5cd) --------
;;
;; The adapter SELECTION — the installed adapter spec map and the
;; adapter-disposed breadcrumb — is OWNED BY the realm (Runtime-Subsystems
;; §Adapter Ownership: "adapter ownership belongs to a realm or render root,
;; not to the process as such"). Stage 4 moves the two process-global
;; `defonce` cells that used to live in `re-frame.substrate.adapter` into the
;; realm record's `:adapter` + `:adapter-disposed?` slots. `substrate.adapter`
;; becomes the ACCESS SEAM over these slots: its public surface
;; (`install-adapter!` / `current-adapter` / `current-adapter-spec` /
;; `dispose-adapter!` / `adapter-disposed?` / the delegation fns) is
;; byte-identical, and in a single-realm app every call routes through the one
;; default realm — so the install/dispose lifecycle and every "single adapter
;; per process" diagnostic behave exactly as before.
;;
;; Why the realm OWNS it rather than substrate.adapter holding it: the realm
;; is the value that owns the non-durable operational layer (the registrar,
;; the adapter, the capability map). Concentrating the adapter selection there
;; — instead of in a leaf substrate ns — keeps the operational environment in
;; one place. There is no cycle: this ns requires only
;; `registrar` + `late-bind`, neither of which pulls `substrate.adapter`, so
;; `substrate.adapter` statically requires THIS ns (no late-bind indirection).
;;
;; Two slots, mirroring the prior two-cell shape:
;;   :adapter           the installed adapter spec map, or absent/nil.
;;   :adapter-disposed? the dispose breadcrumb (absent ⇒ false ⇒ never
;;                      installed; true ⇒ installed then torn down).

(defn realm-adapter
  "Return the adapter SELECTION (the installed adapter spec map) for a realm,
  or nil when none is seated. Accepts a realm map or realm-id keyword; nil
  resolves to the default realm (absence = default realm). The realm-owned
  read seam `re-frame.substrate.adapter/current-adapter-spec` is built on.
  INTERNAL."
  ([] (realm-adapter default-realm-id))
  ([realm-or-id]
   (:adapter (realm (realm-id realm-or-id)))))

(defn realm-adapter-disposed?
  "Return the realm's adapter-lifecycle breadcrumb — `true` iff the most
  recent adapter lifecycle event on the realm was a successful
  `dispose-realm-adapter!` with no subsequent install (absence ⇒ `false` ⇒
  the adapter was never installed). nil resolves to the default realm.
  INTERNAL."
  ([] (realm-adapter-disposed? default-realm-id))
  ([realm-or-id]
   (boolean (:adapter-disposed? (realm (realm-id realm-or-id))))))

(defn install-realm-adapter!
  "Seat `adapter` (the spec map) as the realm's SELECTION and clear the
  dispose breadcrumb. The single mutation point for adapter install — the
  realm-owned counterpart of the prior process-global cell. Returns
  `adapter`. nil resolves to the default realm. Does NOT enforce the
  single-adapter-per-realm guard — `re-frame.substrate.adapter/install-adapter!`
  owns that check (it reads `realm-adapter` first and throws
  `:rf.error/adapter-already-installed`), so this seam stays a plain setter the
  guard composes over. INTERNAL."
  ([adapter] (install-realm-adapter! default-realm-id adapter))
  ([realm-or-id adapter]
   (update-realm! (realm-id realm-or-id)
                  #(assoc % :adapter adapter :adapter-disposed? false))
   adapter))

(defn dispose-realm-adapter!
  "Clear the realm's adapter SELECTION and set the dispose breadcrumb. The
  realm-owned counterpart of the prior process-global dispose. Idempotent:
  clearing an already-absent adapter just (re)sets the breadcrumb. Returns
  nil. nil resolves to the default realm. Does NOT call the adapter's own
  `:dispose-adapter!` fn — `re-frame.substrate.adapter/dispose-adapter!` owns
  that (it reads the seated adapter, runs its teardown, THEN clears the slot
  here), so this seam stays a pure state transition the lifecycle composes
  over. INTERNAL."
  ([] (dispose-realm-adapter! default-realm-id))
  ([realm-or-id]
   (update-realm! (realm-id realm-or-id)
                  #(-> % (dissoc :adapter) (assoc :adapter-disposed? true)))
   nil))

(defn reset-realm-adapter-lifecycle!
  "Reset the realm's adapter SELECTION + breadcrumb to a never-installed cold
  state (adapter absent, breadcrumb false). The realm-owned counterpart of the
  prior `reset-lifecycle-state-for-tests!` cold-start seam. nil resolves to
  the default realm. INTERNAL — NOT part of the runtime contract; cold-start
  test fixtures use it to wipe lifecycle state so the no-adapter-installed
  throw can be asserted independently of the adapter-disposed throw."
  ([] (reset-realm-adapter-lifecycle! default-realm-id))
  ([realm-or-id]
   (update-realm! (realm-id realm-or-id)
                  #(-> % (dissoc :adapter) (dissoc :adapter-disposed?)))
   nil))
