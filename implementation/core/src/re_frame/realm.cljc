(ns re-frame.realm
  "The runtime realm — the container that owns the non-durable operational
  layer an app runs in (EP-0013 D1, accepted 2026-06-11).

  ## EP-0023: this is INTERNAL substrate, NOT current public composition vocabulary

  EP-0023 (graduated 2026-06-16) moves the PUBLIC model to
  `image -> frame -> event stream` and **RETAINS this realm machinery as the
  internal installation substrate** — it is NOT deleted, but it is also no
  longer the taught public architecture (EP-0023 §Backwards Compatibility:
  \"This EP retains EP-0013's D1 runtime-realm machinery as the internal
  installation boundary\"; §Surface dispositions: the D1 realm container is
  \"Retained internally … It stops being the beginner-facing public
  architecture\"). The realm remains a valid implementation substrate for
  registrar seating, adapter/capability storage, host-transient ownership,
  disposal, and compatibility during migration. Where this ns re-exports names
  through the `re-frame.core` facade (`rf/realm`, `rf/dispose-realm!`,
  `rf/realm-ids`, `rf/installed-app`), those are retained-internal / migration
  / tooling surfaces, NOT current public composition vocabulary — the public
  path is `rf/image` + `rf/make-frame` (EP-0023 §Surface dispositions; the
  EP-0013 names below describe the retained substrate, not the model a new
  reader should compose against). The EP-0013 staging narrative below is
  preserved as the design record of this retained substrate.

  Per [Runtime-Subsystems §Runtime realms](spec/Runtime-Subsystems.md) and
  Spec-Schemas §`:rf/realm`, a runtime realm owns the registrar it
  dispatches/subscribes/resolves against, the installed adapter SELECTION,
  the capability map, the frame registry (frame ids are unique *within* a
  realm), and the host-transient subsystem state — distinct from the
  *durable* `:rf.runtime/*` subsystems, which live inside the frames the
  realm owns.

  ## D1 is INTERNAL — no public source break, no public constructor

  This is EP-0013 staging 1-4: an internal realm record + a default realm +
  realm-owned registrar + a frame realm-reference (stages 1-3), plus the
  realm-owned adapter SELECTION + host-transient subsystem descriptor table
  (stage 4 — `re-frame.substrate.adapter` is now the access seam over the
  realm's `:adapter` slot, and the framework's host-transient side-tables are
  inventoried in the realm's `:host-transient` slot).

  Stage 8 (rf2-blibek) adds the realm-targeted QUERY readers
  (`realm-registrations` / `realm-handler-meta` / `realm-handler-ids`) — read
  the registrations of a SPECIFIED realm rather than the implicit default.
  The PUBLIC surface is the map-shaped facade form `(rf/registrations {:realm
  r :kind k})` / `(rf/handler-meta {:realm r :kind k :id id})` (EP-0013
  open-issue 11 — map-shaped, unambiguous against the existing keyword
  arities); the no-arg / keyword-arity default-realm calls stay byte-identical.

  Stage 9 (rf2-swrf4k — the LAST EP-0013 impl slice) graduates the PUBLIC
  realm CONSTRUCTOR `construct-realm` (re-exported as `rf/realm`; ruled
  `rf/realm`, NEVER `rf/runtime`, EP-0013 issue 1). A constructed realm is
  HERMETIC by default (its OWN registrar atom) and REGISTERED in the `realms`
  registry by id, so `install!` seats a program into its own table and the
  realm-targeted query surface above reads only that realm's registrations —
  N realms isolate (EP-0013 §Realm Conformance). The seating is realm-scoped
  via `re-frame.registrar/*registrar*`, which `app-value/install!` binds to the
  target realm's own atom for the duration of seating; live DISPATCH through a
  non-default realm's own registrar (frame→realm lookup routing) is the future
  slice the EP flags. Everything in a single-realm app still
  routes through ONE
  process-created **default realm** (`:rf.realm/id` = `:rf.realm/default`),
  so today's single-app ergonomics are byte-identical: a single-realm app
  never spells a realm, exactly as a single-frame app never spells a frame
  outside its root (the EP-0002 refinement pattern). Absence of an explicit
  realm means the default realm — an explicit documented rule, never
  synthesis.

  ## The registrar is owned, not copied

  D1 changes registrar *ownership*, not the registrar *shape*. The default
  realm OWNS the existing process-global `re-frame.registrar` atom
  (`kind->id->metadata`) by holding a reference to it in its `:registrar`
  slot. The registry shape, the `register!` / `lookup` / `registrations`
  read+write API, and every public `reg-*` / frame signature are unchanged.
  Existing specs call the default realm's registrar \"global\"; under D1 it
  is the default realm's, and the surface is identical. D2 (the app value —
  registrations as immutable descriptors) is sequenced behind D1; this ns
  holds today's mutable registrar behind the realm unchanged and leaves the
  `:app` slot absent.

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
;;                    atom (the byte-identical single-realm path); a hermetic
;;                    constructed realm gets its OWN fresh atom so its
;;                    provenance descriptors never pollute the default store
;;                    (rf2-9fn4is). `seat-into-realm!` binds
;;                    `source-store/*source-store*` to this atom for the
;;                    duration of seating, mirroring the `:registrar` binding.
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
;;   :host-transient  subsystem-id → HostTransientDescriptor — the realm-owned
;;                    registry of the framework's non-durable process-mutable
;;                    side-tables (HTTP in-flight handles, routing nav
;;                    counters, machine timers, flow last-input caches, …).
;;                    Stage 4 (EP-0013 issue 5) makes the realm the OWNER of
;;                    this descriptor table; the side-table atoms still live in
;;                    their producing artefacts (reached through the late-bind
;;                    reset hooks), and a descriptor registered here records
;;                    the subsystem's scope / teardown / test-reset so the
;;                    realm is the single inventory of what must be torn down on
;;                    realm/frame destroy. Absent until a subsystem registers.
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
;; pattern). The realm is held in an atom so the (future, D2) install! /
;; reinstall! path can replace the realm's installed app at the realm
;; boundary; the live mutations in D1 are the realm-owned adapter SELECTION
;; (the install/dispose lifecycle, stage 4) and the host-transient descriptor
;; table; the frame-membership set is DERIVED (never stored), so it is not a
;; mutation of this atom.

(defonce
  ^{:doc "The process realm registry: realm-id → realm map. D1 holds exactly
  one entry — the default realm. Held as an atom so a later stage can add
  realms and so the default realm's realm-owned mutable slots — the adapter
  SELECTION (install/dispose lifecycle, stage 4) and the host-transient
  descriptor table — can be updated in place. The registry is process-wide;
  realm ids are unique within a process (Spec-Schemas §`:rf/realm`)."}
  realms
  (atom {default-realm-id (make-realm default-realm-id)}))

(defn- update-realm!
  "Apply `f` (+ `args`) to the realm map registered under `rid`, in place in
  the `realms` registry. INTERNAL — the single mutation seam for the
  realm-owned mutable slots (the installed-app `:app`, the adapter SELECTION,
  the host-transient inventory). Returns the updated realm map.

  FAILS CLOSED on an UNKNOWN id (rf2-c6armm.6 #2): a realm is an isolation
  boundary, so a mutation aimed at a realm that was never constructed must NOT
  silently no-op (the prior posture) — a typo'd or never-constructed id would
  then drop the write on the floor while the caller believes it seated state.
  The id reaching here is already resolved (callers pass `(realm-id …)`, so nil
  has become `default-realm-id`, which is seeded at boot and always present);
  any OTHER id that is not in the registry is an explicit unknown target and
  THROWS `:rf.error/unknown-realm` BEFORE mutating, naming the id + the live
  realm ids. This guards the non-`install!` mutation seams (`set-installed-app!`
  / `install-realm-adapter!` / `register-host-transient!` / …) the same way
  `app-value/resolve-target-realm!` already guards `install!` — the silent
  default/global fallback is the wrong failure mode for an isolation boundary."
  [rid f & args]
  (let [snapshot @realms]
    (when-not (contains? snapshot rid)
      (error/throw-error!
        :rf.error/unknown-realm
        'rf/install!
        (str "rf/realm " rid " is not registered — a realm-owned"
             " mutation cannot target a realm that was never"
             " constructed. Absence defaults to the default realm;"
             " an unknown explicit id does not.")
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
  "Return the realm map for `id`, or nil. INTERNAL. D1 only ever has the
  default realm at runtime; this reader exists for the (realm, frame)
  addressing model the later stages build on."
  [id]
  (get @realms id))

(defn realm-ids
  "Return the set of installed realm ids — the keys of the process `realms`
  registry. Always includes `default-realm-id` (the process default realm,
  seeded at ns-load and never disposed); a single-realm app returns exactly
  `#{:rf.realm/default}`. Constructed realms (`construct-realm`) join the set
  and disposed realms (`dispose-realm!`) drop out of it, so this is the live
  enumeration of which realms exist right now.

  The realm-side half of the (realm, frame) addressing model: a tool reads the
  installed realms here and a frame's realm via `re-frame.frame/frame-realm`,
  the two together being the full address (EP-0013 disposition 3).
  EP-0013-PUBLIC (`rf/realm-ids`) — the realm-targeted query surface needs a
  public way to enumerate realms (the stage-8 `{:realm …}` query forms take a
  realm id but nothing public told a tool WHICH ids exist). Under EP-0023 this
  is a retained-internal / tooling surface over the internal installation
  boundary, NOT current public composition vocabulary (the `(realm, frame)`
  address it serves is publicly replaced by a single process-local frame
  target — EP-0023 §Surface dispositions / §Id Spaces); tooling may still
  enumerate realms but should label them as internal substrate."
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
  (absence = default realm). A realm map constructed BEFORE this slot existed
  (a never-disposed pre-fix default-realm record, or a hand-built test realm
  map) falls back to the process-global store. INTERNAL — `seat-into-realm!`
  binds `source-store/*source-store*` to this atom for the seating duration."
  ([] (source-store (default-realm)))
  ([realm-map]
   (or (:source-store (or realm-map (default-realm)))
       source-store/kind->id->ns->descriptor)))

;; ---- the public realm constructor (EP-0013 stage 9, rf2-swrf4k) ------------
;;
;; The PUBLIC realm CONSTRUCTOR — the LAST EP-0013 impl slice graduates the
;; reserved `rf/realm` vocabulary (EP-0013 issue 1; ruled `rf/realm`, NEVER
;; `rf/runtime` — "runtime" already names runtime-db / `:rf.runtime/*` /
;; Runtime-Architecture, so a realm constructor called `runtime` is a permanent
;; EP-0007 hazard). `make-realm` above is the pure record FACTORY the default
;; realm + tests use; this is the public BUILD-AND-REGISTER constructor a caller
;; uses to stand up an explicit realm to `install!` an app value into and target
;; queries against (parallel apps / hermetic test isolation / multi-tenant).
;;
;; Two things distinguish a constructed realm from the implicit default:
;;
;;   1. it is HERMETIC by default — it gets its OWN fresh `(kind, id) →
;;      metadata` registrar atom (not the process-global one the default realm
;;      holds), so an app installed into it lives in its own table. Two realms
;;      can hold different handlers for the same event id without collision
;;      (EP-0013 §Realm Conformance), and a hermetic test installs exactly the
;;      program it needs without clearing a process-global registrar. A caller
;;      MAY pass an explicit `:registrar` atom to share one (the default realm's
;;      own pattern), but the public default is isolation.
;;
;;   2. it is REGISTERED in the process `realms` registry under its `:id`, so it
;;      resolves by id keyword through `realm` / `realm-registrations` / the
;;      stage-8 `{:realm …}` query forms, and `install!` can seat into it by id
;;      or by the realm map. The default realm is the one already-registered
;;      entry; a constructed realm joins it. Realm ids are unique within a
;;      process — constructing a realm whose id already exists THROWS rather
;;      than silently clobbering a live realm.
;;
;; A constructed realm carries its own `:adapter` SELECTION + `:capabilities`
;; map when supplied, so it can run a different substrate root than another
;; realm (the multi-adapter-root direction the conformance matrix proves).

(defn register-realm!
  "Insert `realm-map` into the process `realms` registry under its
  `:rf.realm/id`. INTERNAL — the single mutation seam that adds a realm to the
  registry (the default realm is seeded at ns-load; constructed realms join via
  `construct-realm`). Returns the realm map. Does NOT guard id uniqueness — the
  public `construct-realm` owns that check so this stays a plain setter."
  [realm-map]
  (swap! realms assoc (:rf.realm/id realm-map) realm-map)
  realm-map)

(defn construct-realm
  "Construct + register a realm — an explicit container a caller installs
  an app value into and targets queries against (EP-0013 stage 9).
  EP-0013-PUBLIC (`rf/realm`); EP-0023 RETAINS `rf/realm` as the INTERNAL
  installation substrate — it stops being the beginner-facing public
  architecture (the public model targets a FRAME via `rf/make-frame`, the
  frame determines the image generation used for registration resolution —
  EP-0023 §Surface dispositions). Retained-internal / migration / tooling
  surface, not current public composition vocabulary.

  `opts` (a map) carries:

    :id           the realm id (required) — a stable, process-unique keyword.
                  Constructing a realm whose id is already registered THROWS
                  `:rf.error/realm-id-conflict` (realm ids are unique within a
                  process; a silent clobber would orphan a live realm's program).
    :registrar    (optional) an explicit `(kind, id) → metadata` atom to share.
                  OMITTED ⇒ the realm gets its OWN fresh empty atom (HERMETIC —
                  the public default), so an app installed into it lives in its
                  own table, isolated from every other realm.
    :adapter      (optional) the realm's adapter SELECTION (the substrate spec
                  map) — a realm may carry its own adapter/root, so N realms can
                  run N different substrate roots.
    :capabilities (optional) the `:rf.capability/* → service` map a stage-7
                  `install!` capability-checks the installed app against.

  The realm's `:app` slot is INSTALL-OWNED state, NOT a public constructor input
  (rf2-c6armm.2 #1): an app value is INERT until `install!` seats its descriptors
  into the realm's registrar AND records the value. Accepting a public `:app` here
  would record an installed-app value WITHOUT seating any descriptor, so
  `installed-app` (which prefers the stored `:app`) would report a program the
  registrar does not hold, and the FIRST `reinstall!` would diff against that
  phantom app and only apply the delta — never populating the registrar with the
  base program. So `construct-realm` does NOT accept `:app`; seat a program with
  `(-> (rf/realm {:id …}) (rf/install! app))`.

  Returns the constructed realm map (now in the `realms` registry), so the call
  composes: `(-> (rf/realm {:id …}) (rf/install! app))`.

  Throws `:rf.error/invalid-realm` when `:id` is missing or when an `:app` key is
  supplied (install-owned state is not a constructor input), and
  `:rf.error/realm-id-conflict` when `:id` is already registered."
  [opts]
  (let [id (:id opts)]
    (when (nil? id)
      (error/throw-error!
        :rf.error/invalid-realm
        'rf/realm
        "rf/realm requires an :id — supply a realm id (the process-unique key the realm is registered under)."
        {:recovery :supply-a-realm-id
         :extra    {:opts opts}}))
    ;; An `:app` here would create a FALSE installed-app state — the value is
    ;; recorded without its descriptors being seated (rf2-c6armm.2 #1). The
    ;; realm's `:app` slot is install-owned; reject it loudly at the constructor.
    (when (contains? opts :app)
      (error/throw-error!
        :rf.error/invalid-realm
        'rf/realm
        (str "rf/realm: :app is install-owned state, not a constructor"
             " input — an app value is inert until rf/install! seats it."
             " Use (-> (rf/realm {:id " id "}) (rf/install! app)).")
        {:recovery :install-the-app-with-rf-install
         :extra    {:realm id}}))
    (when (contains? @realms id)
      (error/throw-error!
        :rf.error/realm-id-conflict
        'rf/realm
        (str "rf/realm: a realm with id " id " is already registered"
             " — use a unique realm id or dispose the existing realm first.")
        {:recovery :use-a-unique-realm-id-or-dispose-the-existing-realm
         :extra    {:realm id}}))
    (let [;; Hermetic by default: a fresh OWN registrar atom unless the caller
          ;; shares one explicitly. This is the isolation the public realm
          ;; constructor exists to provide. A fresh OWN source store rides
          ;; alongside (rf2-9fn4is) so the realm's EP-0023 provenance descriptors
          ;; never pollute the process-default source store — the isolation the
          ;; own-registrar gives the resolver map, extended to the source store.
          realm-map (cond-> (make-realm id {:registrar    (get opts :registrar    (atom {}))
                                            :source-store (get opts :source-store (atom {}))})
                      (contains? opts :adapter)      (assoc :adapter      (:adapter opts))
                      (contains? opts :capabilities) (assoc :capabilities (:capabilities opts)))]
      (register-realm! realm-map))))

;; `dispose-realm!` (the teardown counterpart of `construct-realm`) lives at the
;; END of this ns: it walks the realm-owned adapter + host-transient teardown
;; seams (`dispose-realm-adapter!` / `host-transient` / `clear-host-transient!`),
;; which are defined further down, so it is placed after them (rf2-kq0yfb).

;; ---- realm-targeted registrar queries (EP-0013 D1 stage 8, rf2-blibek) ----
;;
;; The realm-targeted QUERY surface: read the registrations of a SPECIFIED
;; realm rather than the implicit default. A realm owns its `(kind, id) →
;; metadata` registrar atom (the default realm holds a reference to the
;; process-global atom; a hermetic realm created with an explicit `:registrar`
;; holds its own), so these readers resolve the realm's OWN atom and snapshot
;; it — "realm-targeted registrar queries return ONLY that realm's
;; registrations" (EP-0013 §Realm Conformance; open-issue 11). They are the
;; realm-scoped counterparts of the process-global `registrar/registrations` /
;; `registrar/handler-meta` / `registrar/ids` that the public map-shaped facade
;; forms (`(rf/registrations {:realm r :kind k})`, …) are built on.
;;
;; Each accepts a realm-or-id (a realm map, a realm-id keyword, or nil for the
;; default realm — the absence-is-default rule), resolves it to the realm's
;; registrar atom, and reads ONCE. Pure with respect to that read; an unknown
;; realm (no registry entry, so no registrar atom) reads as empty rather than
;; throwing — querying a realm that was never created is the empty program,
;; not an error.

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
  OWN registrar (defaults to the default realm), or `{}` when the realm
  registers none of that kind (or the realm is unknown). The realm-scoped
  counterpart of `registrar/registrations` — it reads only THIS realm's
  registrar, so a hermetic realm with its own registrar returns only its
  registrations (EP-0013 §Realm Conformance). INTERNAL — the public map-shaped
  `(rf/registrations {:realm r :kind k})` form is built on it."
  [realm-or-id kind]
  (if-let [reg (realm-registrar realm-or-id)]
    (get @reg kind {})
    {}))

(defn realm-handler-meta
  "Return the registration metadata map for `[kind id]` in `realm-or-id`'s OWN
  registrar (defaults to the default realm), or `nil` when that realm has no
  such registration (or the realm is unknown). The realm-scoped counterpart of
  `registrar/handler-meta` — it reads only THIS realm's registrar. INTERNAL —
  the public map-shaped `(rf/handler-meta {:realm r :kind k :id id})` form is
  built on it."
  [realm-or-id kind id]
  (when-let [reg (realm-registrar realm-or-id)]
    (-> @reg (get kind) (get id))))

(defn realm-handler-ids
  "Return the set of ids registered under `kind` in `realm-or-id`'s OWN
  registrar (defaults to the default realm), or `#{}` when none. The
  realm-scoped counterpart of `registrar/ids`. INTERNAL — the public map-shaped
  `(rf/handler-ids {:realm r :kind k})` form is built on it."
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
;; Stage 5 is INTERNAL + read-only: there is NO construction (stage 6) and no
;; `install!` (stage 7) yet, so the realm STORES no constructed app value and
;; the `:app` slot stays absent. The installed app is therefore the
;; RECOMPUTABLE PROJECTION over the realm's own registrar — `re-frame.app-value`
;; owns the descriptor format and the projection fn. Because the registrar is
;; the single source of truth, the ordinary `reg-*` sugar path keeps this app
;; value current for free: a registration updates the registrar, and the next
;; `installed-app` reflects it with no invalidation step and no desync.
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

    - NO stored `:app` (pure `reg-*` sugar / load-order, or a fresh realm) —
      the recomputable projection over the realm's registrar
      (`re-frame.app-value/app-value`, via the `:app-value/project` hook),
      module-less (load-order registrations declare no module).
    - A stored `:app` (an `install!`-seated value, stage 7) — that SAME live
      projection ENRICHED with the seated app's `:rf.app/id`, `:modules`, and
      `:rf.app/requires` provenance (via the `:app-value/reconcile-installed` hook).
      The registrations stay the live registrar's, so coexisting sugar that
      `install!` deliberately preserves (rf2-c6armm.7 #1) is visible here too;
      the rich per-module provenance the Xray Module-view feeds to
      `app-registrations` / `app-owns` / `app-requires` is preserved.

  The reader's contract — \"give me the realm's live installed app value\" — is
  stable across the stage-6/7 graduation; only its internals change (project,
  then overlay the seated provenance when a stored app exists).

  RE-EXPORTED as `rf/installed-app` (EP-0013 disposition 6, rf2-imquoq):
  the realm→installed-app read seam graduated from internal WHEN the Xray
  Module-view demanded the per-module provenance an `install!`-seated `:app`
  carries (`:modules` + `:rf.module/owns` / `:rf.module/requires` /
  `:owner`-stamped descriptors). EP-0023 RETAINS `rf/installed-app` as a
  tooling / migration surface, NOT current public composition vocabulary — the
  public model inspects a frame's resolved image generation; tools may still
  read this internal installation boundary but should label it as
  implementation structure (EP-0023 §Surface dispositions).
  A read of the LIVE registration value (provenance from the install-time
  snapshot) — it does NOT route live dispatch through a non-default realm (the
  deferred runtime-routing slice). This var stays the canonical implementation;
  the facade `def` re-exports it unchanged."
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

;; ---- seating the app VALUE into the realm's :app slot (stage 7) ------------
;;
;; Stage 7 adds the WRITE half of the `:app` slot: `install!` / `reinstall!`
;; (owned by `re-frame.app-value`) lower an immutable app value into the
;; realm's registrar AND record the seated value here, so the realm STORES the
;; rich constructed app value (carrying `:modules` + `:owner`-stamped
;; descriptors) and `installed-app` returns it in preference to the
;; recomputable projection. This is the single realm-owned mutation seam over
;; the slot — the value is replaced at the realm boundary (EP-0013 §Installation:
;; "the public contract is value replacement at the realm boundary"), the
;; registrar-lowering + diff logic stays in `app-value` (which owns the
;; descriptor format). nil resolves to the default realm (absence = default
;; realm). Returns the realm map.

(defn set-installed-app!
  "Seat `app` as the realm's `:app` slot — the installed app VALUE the realm is
  running. The realm-owned write counterpart of `installed-app`'s read seam
  (stage 7). The registrar-lowering that makes the program actually resolvable
  is `re-frame.app-value/install!`'s job; this seam only records the seated
  value at the realm boundary. nil resolves to the default realm. Returns the
  updated realm map. INTERNAL — no public realm-mutation surface ships."
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
;; per process" diagnostic behave exactly as before. A future multi-realm
;; runtime gets a per-realm adapter for free: the seam already keys on a realm.
;;
;; Why the realm OWNS it rather than substrate.adapter holding it: the realm
;; is the value that owns the non-durable operational layer (the registrar,
;; the adapter, the capability map, the host-transient state). Concentrating
;; the adapter selection there — instead of in a leaf substrate ns — is what
;; lets D2/later replace the whole operational environment at the realm
;; boundary in one place. There is no cycle: this ns requires only
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

;; ---- realm-owned host-transient subsystem registry (stage 4) --------------
;;
;; The framework's host-transient subsystem state — the non-durable
;; process-mutable side-tables (HTTP in-flight handles + abort controllers,
;; routing nav counters + scroll caches, machine timers + spawn-order helpers,
;; flow last-input caches, resource work-ledgers, SSR side channels, adapter
;; render roots/disposers) — is OWNED BY the realm (Runtime-Subsystems
;; §Host-Transient Subsystem State: "the owner of the table is the realm, not
;; an arbitrary namespace-level singleton").
;;
;; Stage 4 makes the realm the OWNER of the host-transient DESCRIPTOR
;; inventory: `subsystem-id → :rf/host-transient-descriptor`. The side-table
;; atoms continue to live in their producing artefacts and are reset/torn down
;; through the existing late-bind reset + frame-destroy hooks (the
;; `re-frame.test-support` reset-hook-table and the per-artefact
;; `*/on-frame-destroyed!` hooks) — moving the bytes is not what this stage
;; does. What it does is give the realm the single, queryable record of WHICH
;; host-transient subsystems exist and HOW each is scoped / torn down /
;; test-reset, so realm/frame teardown has one inventory to walk rather than a
;; scattering of namespace-level singletons. Each descriptor's `:durability`
;; is `:none` — host-transient state MUST NOT ride the wire (no snapshot, no
;; SSR, no restore), per the descriptor contract (Spec-Schemas
;; §`:rf/host-transient-descriptor`).

(defn register-host-transient!
  "Register a host-transient subsystem `descriptor` (a
  `:rf/host-transient-descriptor` map carrying at least `:id`) under its
  `:id` in the realm's `:host-transient` inventory. The realm becomes the
  owner of the record of this non-durable side-table. nil resolves to the
  default realm. Returns the descriptor. INTERNAL."
  ([descriptor] (register-host-transient! default-realm-id descriptor))
  ([realm-or-id descriptor]
   (update-realm! (realm-id realm-or-id)
                  update :host-transient
                  (fnil assoc {}) (:id descriptor) descriptor)
   descriptor))

(defn host-transient
  "Return the realm's host-transient subsystem inventory
  (`subsystem-id → descriptor`), or the descriptor for a single `subsystem-id`
  when supplied. nil realm resolves to the default realm. Returns nil when the
  realm holds no host-transient registry (none registered yet). INTERNAL — the
  realm-owned read seam for the framework's non-durable side-table inventory."
  ([] (host-transient default-realm-id))
  ([realm-or-id]
   (:host-transient (realm (realm-id realm-or-id))))
  ([realm-or-id subsystem-id]
   (get (:host-transient (realm (realm-id realm-or-id))) subsystem-id)))

(defn clear-host-transient!
  "Drop the realm's host-transient subsystem inventory (the descriptor
  records, not the side-table contents — those are reset through each
  subsystem's own reset hook). nil resolves to the default realm. Returns nil.
  INTERNAL — the inventory counterpart of the per-subsystem reset hooks; used
  by cold-start test fixtures so a realm starts with an empty inventory."
  ([] (clear-host-transient! default-realm-id))
  ([realm-or-id]
   (update-realm! (realm-id realm-or-id) dissoc :host-transient)
   nil))

;; ---- realm disposal — the teardown counterpart of construct-realm ----------
;;
;; Placed at the END of the ns because it walks the realm-owned adapter +
;; host-transient teardown seams defined above (`realm-adapter` /
;; `dispose-realm-adapter!` / `host-transient` / `clear-host-transient!`).
;; Disposing a realm MUST release the operational resources the realm owns —
;; a bare `(swap! realms dissoc rid)` would ORPHAN the seated adapter + every
;; host-transient subsystem the realm inventoried (rf2-kq0yfb).

(defn- dispose-realm-host-transient!
  "Walk realm `rid`'s host-transient subsystem inventory and run each
  descriptor's `:teardown` token, then drop the inventory. The `:teardown` is
  the realm-dispose cleanup hook the descriptor declares (Spec-Schemas
  §`:rf/host-transient-descriptor`); it is invoked with `rid` so a
  realm-scoped subsystem tears down THIS realm's entries. A descriptor that
  declares no `:teardown` (the inventory is a queryable record only — its
  side-table bytes are reset through the late-bind reset hooks) is skipped,
  and a non-callable `:teardown` token is ignored. INTERNAL — the
  host-transient arm of `dispose-realm!`."
  [rid]
  (doseq [[_ descriptor] (host-transient rid)]
    (when-let [td (:teardown descriptor)]
      (when (fn? td)
        (td rid))))
  (clear-host-transient! rid))

(defn dispose-realm!
  "Dispose a constructed realm: tear down its adapter + host-transient
  subsystem state, then drop it from the process `realms` registry (releasing
  its own registrar for GC). nil / the default realm id is a NO-OP — the
  default realm is never disposed (it backs the byte-identical single-realm
  path). Returns nil. The teardown counterpart of `construct-realm` for
  hermetic-test cleanup and multi-tenant realm lifecycle. EP-0013-PUBLIC
  (`rf/dispose-realm!`); EP-0023 RETAINS `rf/dispose-realm!` as the
  retained-internal / migration / tooling surface (the realm is the internal
  installation boundary, not the public model — EP-0023 §Surface
  dispositions). The public disposal boundary is a frame (`destroy-frame!`).

  Per EP-0013 §Realm Conformance disposing a realm MUST release the operational
  resources the realm owns, not merely drop the registry entry (a bare `dissoc`
  would ORPHAN the seated adapter + the host-transient subsystems AND leave every
  frame the realm OWNS still addressable — rf2-kq0yfb / rf2-yueuvi). The teardown
  walks the realm-owned seams, in order:

    1. FRAMES — destroy every frame the realm OWNS (rf2-yueuvi). The realm owns
       the frame registry + their lifecycle/disposal state (Runtime-Subsystems
       §What a realm owns), so disposing the realm MUST end those frames'
       lifecycle, not leave stale records addressable. Routed through the
       `:frame/destroy-realm-frames!` late-bind hook (a static `realm` → `frame`
       require would cycle), which runs the full per-frame `destroy-frame!`
       recipe for each owned frame. Runs FIRST — before the realm's adapter +
       host-transient state is torn down — so each frame's own teardown can still
       walk the realm's LIVE host-transient inventory and reach the seated
       adapter. No-op when the frame ns is not loaded or the realm owns no frames.
    2. ADAPTER — run the seated adapter's own `:dispose-adapter!` fn (read
       directly off the realm's `:adapter` slot, so this leaf ns needs no
       `substrate.adapter` require), then clear the slot + set the disposed
       breadcrumb via `dispose-realm-adapter!`. Mirrors
       `substrate.adapter/dispose-adapter!` for the default realm.
    3. HOST-TRANSIENT — walk the realm's host-transient inventory, running each
       descriptor's `:teardown` token (the realm-dispose cleanup hook), then
       drop the inventory via `clear-host-transient!`.
    4. REGISTRY — `dissoc` the realm so its program + own registrar are no
       longer reachable.

  The teardown runs only for a CONSTRUCTED realm that is STILL registered; for
  the default realm (and nil) the whole call is a no-op — the default realm's
  adapter/host-transient lifecycle is owned by `substrate.adapter` + the
  per-subsystem reset hooks, and the realm itself is never disposed.

  IDEMPOTENT (rf2-yueuvi): disposing an ALREADY-disposed (or never-constructed)
  realm is a clean no-op rather than a throw — once the registry entry is gone
  the realm owns no live frames / adapter / host-transient inventory to release,
  and the realm-owned mutation seams (`clear-host-transient!` →
  `update-realm!`) FAIL CLOSED on an unknown id. A defensive double-dispose
  (e.g. a `finally` after a body that already disposed) must not raise."
  [realm-or-id]
  (let [rid (realm-id realm-or-id)]
    (when (and (not (= rid default-realm-id))
               ;; Idempotency guard: only tear down a realm that is STILL
               ;; registered. A second dispose (or a never-constructed id) finds
               ;; nothing to release and no-ops cleanly, rather than driving the
               ;; fail-closed realm-owned mutation seams (rf2-yueuvi).
               (contains? @realms rid))
      ;; (1) frames: end the lifecycle of every frame the realm OWNS so no stale
      ;; frame record stays addressable after disposal (rf2-yueuvi). FIRST, so
      ;; each frame's destroy can still reach the realm's live adapter +
      ;; host-transient inventory (torn down in steps 2-3 below).
      (when-let [destroy-realm-frames! (late-bind/get-fn :frame/destroy-realm-frames!)]
        (destroy-realm-frames! rid))
      ;; (2) adapter: run the seated adapter's own teardown, then clear the slot.
      (when-let [adapter (realm-adapter rid)]
        (when-let [f (:dispose-adapter! adapter)]
          (f))
        (dispose-realm-adapter! rid))
      ;; (3) host-transient: walk the inventory's teardown tokens, then drop it.
      (dispose-realm-host-transient! rid)
      ;; (4) registry: drop the realm so its registrar + program are unreachable.
      (swap! realms dissoc rid)))
  nil)
