(ns re-frame.realm
  "The runtime realm — the container that owns the non-durable operational
  layer an app runs in (EP-0013 D1, accepted 2026-06-11).

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
  inventoried in the realm's `:host-transient` slot). **No public `rf/realm`
  constructor and no realm-targeted public query ship here** (EP-0013 issue
  1 — those names are reserved vocabulary, exposure graduates internal-first;
  D2/later stages). Everything in a single-realm app routes through ONE
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
  (:require [re-frame.registrar :as registrar]
            [re-frame.late-bind :as late-bind]))

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
  \"global\"), `:adapter` (selection), and `:capabilities`. The frame set
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
            :registrar (get opts :registrar registrar/kind->id->metadata)}
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
  realm-owned mutable slots (adapter SELECTION + host-transient). Returns the
  updated realm map. A no-op (returns nil) for an unknown realm — D1 only ever
  has the default realm at runtime, so this is defensive."
  [rid f & args]
  (rid (swap! realms
              (fn [m]
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
