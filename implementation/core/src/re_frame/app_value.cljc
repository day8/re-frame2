(ns re-frame.app-value
  "The app value — the program-as-a-value PROJECTION (EP-0013 D2 stage 5
  projection accepted 2026-06-11; the EP-0013 construction + install/reinstall
  surface was RETIRED under the EP-0023 realm-substrate collapse, rf2-afdlyr,
  2026-06-19).

  ## EP-0023: only the projection seam survives; INTERNAL substrate

  EP-0023 (graduated 2026-06-16) moves the PUBLIC model to
  `image -> frame -> event stream`. The EP-0013 app/module CONSTRUCTION surface
  (`app` / `module` + the inspectors) and the INSTALL/SEATING surface
  (`install!` / `reinstall!` + their realm-threading machinery) were retired
  under the realm-substrate collapse (Mike 2026-06-19, rf2-afdlyr): they had no
  production consumer — `rf/app` is publicly REPLACED by `rf/image`, `rf/module`
  is RE-EXPRESSED as an image fragment (EP-0023 §Surface dispositions), and the
  image-assembly ns is the parallel (not stacked) composition mechanism. The
  construction layer was a redundant module-layer between the `reg-*` sugar path
  and EP-0023 image-assembly (rf2-kl6tpi); the install/seating machinery was
  exercised only by tests (rf2-csgz8l); the multi-realm bookkeeping went with
  the single-realm collapse (rf2-d4w0bm).

  ## What survives: the projection seam

  > The program is a value projected over the realm's registrar.

  Stage 5's PROJECTION seam is the one live half: read the program the default
  realm is already running by re-grouping its registrar into an enumerable,
  recomputable descriptor VALUE. It is INTERNAL — reached through the
  `:app-value/project` late-bind hook by `re-frame.realm/installed-app`, which
  the Xray Module-view consumes for per-module provenance.

  ## The app value is a RECOMPUTABLE PROJECTION, not a mirror

  The app value is a **pure projection** computed on demand from the realm's
  registrar atom — NOT a separately-maintained copy registration has to keep in
  sync. The registrar is the single source of truth; the app value is a
  `(kind, id) → descriptor` re-grouping of what the registrar already holds,
  normalized into the app-value descriptor shape (Spec-Schemas draft §App
  Values). Consequences:

    * the SUGAR PATH is free: an ordinary `reg-*` call updates the registrar,
      and the next `(app-value realm)` reflects it with no extra work — there
      is nothing to invalidate, nothing to re-register, no possibility of
      desync (the projection IS the registrar, re-shaped);
    * it is RECOMPUTABLE: two projections taken at the same registrar state
      are equal values;
    * it is correct under hot-reload for free: re-registration replaces the
      registrar slot, so the next projection carries the new handler/coords.

  ## Production elision

  Pure data over the realm/registrar maps; no trace emit sites, no DEBUG-gated
  branches, no feature sentinels, and no per-feature `:require` (only
  `re-frame.realm` + `re-frame.late-bind`, already in the core spine), so this
  ns stays a leaf on the realm/registrar spine and is bundle-isolation neutral.
  The projection is operational metadata, not a dev surface — it survives
  `:advanced` + `goog.DEBUG=false` intact."
  (:require [re-frame.realm     :as realm]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

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
;;             `:schema`, `:tags`, and the kind-specific extras (`:input-kind`,
;;             `:input-signals`, …) — with the handler and the
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
  `:ns`/`:file`/`:line`/`:column` slots into `:source`, the owning-module id out
  of `:owner` (present only when the registration was lowered from an
  installed MODULE — absent for ordinary `reg-*` sugar, which declares no
  module), and keeps the rest of the Spec 001 registration metadata under
  `:metadata` (with the handler, source-coord, and owner slots removed so each
  fact is carried once). INTERNAL."
  [kind id metadata]
  (let [source (descriptor-source metadata)
        ;; The remaining metadata: the Spec 001 registration map minus the
        ;; handler slot, the lifted source-coord slots, and the lifted `:owner`
        ;; provenance slot. Kind-specific extras (`:input-kind`,
        ;; `:input-signals`, `:interceptors`, …) stay here — they are part of the
        ;; registration's description, not framework-internal book-keeping.
        rest-meta (apply dissoc metadata :handler-fn :owner source-coord-keys)]
    (cond-> {:kind kind
             :id   id}
      (contains? metadata :handler-fn) (assoc :handler (:handler-fn metadata))
      (contains? metadata :owner)      (assoc :owner (:owner metadata))
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
  "Project the app value installed in `realm-or-id` (defaults to — and, under
  the default-only realm collapse, always — the default realm) over its
  registrar: the immutable, recomputable description of the program the realm
  is running (EP-0013 §D2 stage 5, INTERNAL).

  The app value is a PURE PROJECTION over the realm's registrar atom, not a
  stored mirror: it derefs the registrar once and re-groups every registration
  into the app-value descriptor shape. Because the registrar is the single
  source of truth, the ordinary `reg-*` sugar path keeps the app value current
  for free — the next call reflects any registration with no invalidation step
  and no possibility of desync.

  Returns:
    {:rf.app/id       <realm-id>          ;; the app the realm carries
     :registrations   {kind {id descriptor}} ;; normalized descriptors by kind
     :rf.app/requires #{}}                ;; capability requirements — always
                                          ;; empty for a projected app
                                          ;; (load-order registrations carry none)

  INTERNAL — `re-frame.realm/installed-app` reaches it through the
  `:app-value/project` late-bind hook (the Xray Module-view consumer). nil
  resolves to the default realm."
  ([] (app-value realm/default-realm-id))
  ([realm-or-id]
   (let [rid       (realm/realm-id realm-or-id)
         registrar (realm/registrar (realm/realm rid))
         snapshot  (if registrar @registrar {})]
     {:rf.app/id       rid
      :registrations   (registrations->descriptors snapshot)
      :rf.app/requires #{}})))

;; ---- the realm-side installed-app reconcile (the late-bind bridge) ---------
;;
;; `re-frame.realm/installed-app` is the realm-side read seam over the realm's
;; `:app` slot. `re-frame.realm` requires NOTHING from this ns (this ns requires
;; it, projecting over its registrar), so `installed-app` reaches this ns
;; through the late-bind hooks published below — a static back-require would
;; cycle. Both hooks are published once at ns-load and never withdrawn
;; (drift-tested via `re-frame.late-bind.directory`).
;;
;; With install!/reinstall! retired (rf2-csgz8l) the realm never stores an
;; `:app` value, so `installed-app` takes the no-stored-`:app` projection branch
;; in production. `reconcile-installed-app` survives as the stored-`:app` branch:
;; defensive code over the still-present `:app` slot — given a stored app value
;; it returns the LIVE registrar projection ENRICHED with the seated app's
;; module provenance (so coexisting `reg-*` sugar stays visible and the read
;; can never desync from `app-value`).

(defn reconcile-installed-app
  "Reconcile a realm's STORED `:app` value with the LIVE projection over its
  registrar, returning the value `re-frame.realm/installed-app` exposes when a
  stored app exists. INTERNAL.

  The result is the live projection (so it reflects coexisting `reg-*` sugar
  and can never desync from `app-value`) carrying the seated app's rich
  provenance overlaid:

    {:rf.app/id       <stored app id>           ;; the seated app's identity
     :registrations   {kind {id descriptor}}    ;; the LIVE registrar, owner-stamped
     :rf.app/requires <stored app :rf.app/requires> ;; the seated app's capabilities
     :modules         <stored app :modules>}    ;; per-module provenance

  `:rf.app/requires` and `:modules` come from the seated VALUE (load-order sugar
  declares neither). Pure given the registrar snapshot the projection reads."
  [realm-id stored-app]
  (-> (app-value realm-id)
      (assoc :rf.app/id       (:rf.app/id stored-app)
             :rf.app/requires (get stored-app :rf.app/requires #{}))
      (cond-> (contains? stored-app :modules)
        (assoc :modules (:modules stored-app)))))

(late-bind/set-fn! :app-value/project app-value)
(late-bind/set-fn! :app-value/reconcile-installed reconcile-installed-app)
