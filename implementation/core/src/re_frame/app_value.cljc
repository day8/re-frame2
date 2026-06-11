(ns re-frame.app-value
  "The app value — the program-as-a-value (EP-0013 D2 stage 5, accepted
  2026-06-11; sequenced behind the merged D1 `re-frame.realm`).

  > The program is a value; the runtime is a container you install it into.

  Per [EP-0013](docs/EP/EP-0013-app-values-and-runtime-realms.md) §D2 (the
  app value) + §Public API Staging stage 5, the app value is the immutable
  description of a program: its registration descriptors grouped by registry
  kind, its capability requirements, and the source coordinates that declared
  each contract surface. Where D1 made registrar *ownership* explicit (a realm
  owns the `(kind, id) → metadata` table), D2 makes the *program* explicit:
  the registrations a realm carries are no longer load-order mutation seen
  only through the live registrar — they are an enumerable, recomputable
  VALUE projected over that registrar.

  ## Stage 5 is INTERNAL — no public surface, no facade, no manifest

  This is EP-0013 staging step 5: an internal app-value descriptor format +
  the projection seam, for the registrations that ALREADY exist in the
  default realm's registrar. **No public `rf/app` / `rf/module` constructor
  (stage 6), no public `rf/install!` / `rf/reinstall!` (stage 7), no
  `re-frame.core` facade export, and no api-manifest change ship here** —
  those names are reserved vocabulary and exposure graduates internal-first
  (EP-0013 issue 1). Everything here is reached only by `re-frame.realm` and
  the conformance tests. The headline is **zero ergonomic regression**: the
  ordinary namespace-load `reg-*` sugar path is byte-identical; nothing on
  the registration hot path changes.

  ## The app value is a RECOMPUTABLE PROJECTION, not a mirror

  The single most important property (EP-0013 §D2 + the bead ruling): the app
  value is a **pure projection** computed on demand from the realm's
  registrar atom — it is NOT a separately-maintained copy that registration
  has to keep in sync. The registrar is the single source of truth; the app
  value is a `(kind, id) → descriptor` re-grouping of what the registrar
  already holds, normalized into the app-value descriptor shape (Spec-Schemas
  draft §App Values). Consequences:

    * the SUGAR PATH is free: an ordinary `reg-*` call updates the registrar,
      and the next `(app-value realm)` reflects it with no extra work — there
      is nothing to invalidate, nothing to re-register, no possibility of
      desync (the projection IS the registrar, re-shaped);
    * it is RECOMPUTABLE: two projections taken at the same registrar state
      are equal values; a projection taken after a `reg-*` reflects the new
      descriptor;
    * it is correct under hot-reload for free: re-registration replaces the
      registrar slot, so the next projection carries the new handler/coords.

  The EP's later stages (6/7) add explicit `rf/app` / `rf/module`
  *construction* (compose modules into an app value as INERT data, before
  any realm) and `install!` / `reinstall!` (seat an explicit app value into a
  realm). This stage ships neither — it ships only the read direction: the
  default realm already HAS an installed program (the process-load `reg-*`
  registrations), and this projects it into the descriptor value the later
  stages and tooling will read and diff.

  ## Production elision

  Pure readers over the realm/registrar maps; no trace emit sites, no
  DEBUG-gated branches, no feature sentinels, and no per-feature `:require`
  (only `re-frame.realm` + `re-frame.late-bind`, both already in the core
  spine; the registrar atom is reached through `realm/registrar`, never
  required directly). The projection is operational metadata, not a dev
  surface — it survives `:advanced` + `goog.DEBUG=false` intact and is
  bundle-isolation neutral. (It is never *called* on the hot path; in stage 5
  it is reached only by tests, so in a production app it is dead code Closure
  DCE removes.)"
  (:require [re-frame.realm     :as realm]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the app-value top-level shape ----------------------------------------
;;
;; Per EP-0013 §App Values + the draft normalized shape (Spec-Schemas), an
;; app value contains (directly or through its modules, which stage 6 adds):
;;
;;   :rf.app/id     the app's id. In stage 5 the only app is the one the
;;                  default realm carries (the process-load registrations),
;;                  so the id is the realm's id (`:rf.realm/default`). Stage 6
;;                  gives an explicitly-constructed app value its own id.
;;   :registrations the normalized registration descriptors, grouped by
;;                  registry kind: `kind → id → descriptor`. This is the
;;                  re-grouping of the realm's registrar into the descriptor
;;                  shape (below).
;;   :requires      the set of `:rf.capability/*` requirements the program
;;                  declares. In stage 5 this is empty — capability
;;                  requirements are declared on MODULE values (stage 6/D3);
;;                  the default realm's load-order registrations carry none.
;;                  The slot is present (always `#{}` here) so the shape is
;;                  stable across the stage-6 graduation.
;;
;; D2/D3-RESERVED, never produced in stage 5: :modules (the module map — D3),
;; :diagnostics (composition diagnostics — stage 6's compose step). Left
;; absent here; an empty :registrations app is still a valid app value.

;; ---- the registration descriptor ------------------------------------------
;;
;; Per EP-0013 §Registration Descriptors, every registrar-backed registration
;; lowers to a descriptor. The registrar stores `kind → id → metadata`, where
;; the metadata map carries the handler under `:handler-fn`, the source coords
;; flat (`:ns` / `:file` / `:line` / `:column`, per Spec 001 §Source-coordinate
;; capture), and the rest of the Spec 001 registration metadata. The descriptor
;; NORMALIZES that into the app-value shape the EP draft shows:
;;
;;   :kind     the registry kind (Spec 001 taxonomy)
;;   :id       the registration id
;;   :handler  the registered handler / value / factory (the registrar's
;;             `:handler-fn`), or nil for kinds that carry none
;;   :source   the `{:ns :file :line :column}` source coordinate ENVELOPE,
;;             lifted out of the flat metadata (nil when the host captured
;;             none — programmatic / non-macro registrations)
;;   :metadata the remaining Spec 001 registration metadata — `:doc`,
;;             `:schema`, `:tags`, and the kind-specific extras (`:event/kind`,
;;             `:input-kind`, `:input-signals`, …) — with the handler and the
;;             lifted source-coord slots removed so the descriptor does not
;;             double-carry them.
;;
;; A descriptor is a pure function of one registrar metadata entry, so it is
;; deterministic and recomputable: same metadata → same descriptor value.

(def ^:private source-coord-keys
  "The flat source-coordinate slots the registrar metadata carries (Spec 001
  §Source-coordinate capture, CLJS reference). Lifted into the descriptor's
  `:source` envelope and removed from `:metadata` so a descriptor carries each
  coordinate exactly once."
  [:ns :file :line :column])

(defn- descriptor-source
  "Lift the source-coordinate envelope (`{:ns :file :line :column}`) out of a
  registrar metadata map, or nil when the host captured none (programmatic /
  non-macro registration). Mirrors the envelope EP-0013 §Source Coordinates
  pins; absence never changes behaviour (EP-0013 issue 8 disposition)."
  [metadata]
  (let [coords (select-keys metadata source-coord-keys)]
    (when (seq coords) coords)))

(defn descriptor
  "Normalize one registrar metadata entry into an app-value registration
  descriptor (EP-0013 §Registration Descriptors). Pure: a function of `kind`,
  `id`, and the registrar `metadata` map only — same inputs, same value.

  Lifts the handler out of `:handler-fn`, the source coords out of the flat
  `:ns`/`:file`/`:line`/`:column` slots into `:source`, and keeps the rest of
  the Spec 001 registration metadata under `:metadata` (with the handler and
  source-coord slots removed so each fact is carried once). INTERNAL."
  [kind id metadata]
  (let [source (descriptor-source metadata)
        ;; The remaining metadata: the Spec 001 registration map minus the
        ;; handler slot and the lifted source-coord slots. Kind-specific
        ;; extras (`:event/kind`, `:input-kind`, `:input-signals`,
        ;; `:interceptors`, …) stay here — they are part of the registration's
        ;; description, not framework-internal book-keeping.
        rest-meta (apply dissoc metadata :handler-fn source-coord-keys)]
    (cond-> {:kind kind
             :id   id}
      (contains? metadata :handler-fn) (assoc :handler (:handler-fn metadata))
      source                           (assoc :source source)
      (seq rest-meta)                  (assoc :metadata rest-meta))))

;; ---- the projection seam --------------------------------------------------
;;
;; `app-value` is the projection: realm → app value. It reads the realm's
;; registrar atom ONCE (a single deref — the registrar is the single source of
;; truth) and re-groups every `(kind, id) → metadata` entry into the
;; `(kind, id) → descriptor` app-value shape. Pure with respect to that deref:
;; two calls at the same registrar state return equal values.

(defn registrations->descriptors
  "Project a registrar snapshot (`kind → id → metadata`) into the app-value
  `:registrations` shape (`kind → id → descriptor`). Pure — a function of the
  snapshot map only. INTERNAL."
  [snapshot]
  (reduce-kv
    (fn [acc kind id->meta]
      (assoc acc kind
             (reduce-kv (fn [m id metadata]
                          (assoc m id (descriptor kind id metadata)))
                        {}
                        id->meta)))
    {}
    snapshot))

(defn app-value
  "Project the app value installed in `realm-or-id` (defaults to the default
  realm) over its registrar — the immutable, recomputable description of the
  program the realm is running (EP-0013 §D2 stage 5, INTERNAL).

  The app value is a PURE PROJECTION over the realm's registrar atom, not a
  stored mirror: it derefs the registrar once and re-groups every registration
  into the app-value descriptor shape. Because the registrar is the single
  source of truth, the ordinary `reg-*` sugar path keeps the app value current
  for free — the next call reflects any registration with no invalidation step
  and no possibility of desync.

  Returns:
    {:rf.app/id     <realm-id>            ;; the app the realm carries
     :registrations {kind {id descriptor}} ;; normalized descriptors by kind
     :requires      #{}}                  ;; capability requirements (empty in
                                          ;; stage 5 — declared on modules, D3)

  INTERNAL — there is NO public `rf/app` constructor and no public
  installed-app read surface in stage 5 (EP-0013 issue 1; stages 6/7). nil
  resolves to the default realm (absence = default realm, the D1 rule)."
  ([] (app-value realm/default-realm-id))
  ([realm-or-id]
   (let [rid       (realm/realm-id realm-or-id)
         registrar (realm/registrar (realm/realm rid))
         snapshot  (if registrar @registrar {})]
     {:rf.app/id     rid
      :registrations (registrations->descriptors snapshot)
      :requires      #{}})))

;; ---- the realm-side installed-app seam (the late-bind bridge) --------------
;;
;; The realm's `:app` slot (Spec-Schemas §`:rf/realm`, D2-reserved) is the
;; installed app VALUE. `re-frame.realm/installed-app` is the realm-side read
;; seam over that slot: it returns the stored `:app` if a future `install!`
;; (stage 7) seated one, else the recomputable projection. `re-frame.realm`
;; requires NOTHING from this ns (this ns requires it, projecting over its
;; registrar), so `installed-app` reaches the projection through the
;; `:app-value/project` late-bind hook published below — a static back-require
;; would cycle. The hook takes a realm-id and returns the projected app value;
;; published once at ns-load and never withdrawn (drift-tested via
;; `re-frame.late-bind.directory`).
;;
;; The projection IS the realm's app value in stage 5: with no construction
;; (stage 6) and no install (stage 7), the realm stores no `:app`, so the
;; recomputable projection of its registrar is exactly the program it runs.

(late-bind/set-fn! :app-value/project app-value)
