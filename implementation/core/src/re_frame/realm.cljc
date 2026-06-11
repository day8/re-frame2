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

  This is EP-0013 staging 1-3: an internal realm record + a default realm +
  realm-owned registrar + a frame realm-reference. **No public `rf/realm`
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
;;   :adapter         the realm's adapter SELECTION (capability); render
;;                    roots own concrete instances. Absent until installed.
;;   :capabilities    :rf.capability/* → service map/record. Absent in the
;;                    bare default realm; late-bind hooks bridge in (D1
;;                    §Late-bind compatibility).
;;   :frames          the set of frame ids registered in this realm (unique
;;                    WITHIN the realm). Frame records still live in the
;;                    process `frame/frames` atom in D1; this set is the
;;                    realm-owned membership view.
;;   :host-transient  subsystem-id → HostTransientDescriptor. Absent until a
;;                    subsystem moves behind the realm (EP-0013 issue 5 — a
;;                    later D1 slice / stage 4).
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
;; boundary; in D1 the only mutation is the frame-membership set.

(defonce
  ^{:doc "The process realm registry: realm-id → realm map. D1 holds exactly
  one entry — the default realm. Held as an atom so a later stage can add
  realms and so the default realm's frame-membership set can be updated in
  place. The registry is process-wide; realm ids are unique within a
  process (Spec-Schemas §`:rf/realm`)."}
  realms
  (atom {default-realm-id (make-realm default-realm-id)}))

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
