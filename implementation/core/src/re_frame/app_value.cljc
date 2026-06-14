(ns re-frame.app-value
  "The app value — the program-as-a-value (EP-0013 D2; stage 5 projection
  accepted 2026-06-11, stage 6 public constructors + composition added on top
  of it; sequenced behind the merged D1 `re-frame.realm`).

  > The program is a value; the runtime is a container you install it into.

  Per [EP-0013](docs/EP/EP-0013-app-values-and-runtime-realms.md) §D2 (the
  app value) + §Public API Staging stages 5-6, the app value is the immutable
  description of a program: its registration descriptors grouped by registry
  kind, its capability requirements, and the source coordinates that declared
  each contract surface. Where D1 made registrar *ownership* explicit (a realm
  owns the `(kind, id) → metadata` table), D2 makes the *program* explicit:
  the registrations a realm carries are no longer load-order mutation seen
  only through the live registrar — they are an enumerable, recomputable
  VALUE projected over that registrar.

  ## Two ways to obtain an app value: PROJECT a live realm, or CONSTRUCT one

  There are two complementary origins for an app value, both producing the
  same descriptor-grouped shape:

    * the **projection** (stage 5, `app-value`): read the program a realm is
      already running by re-grouping its registrar — the read direction over
      the load-order `reg-*` sugar path (see §The projection seam below);
    * the **construction** (stage 6, `module` + `app`): build an app value as
      INERT data from explicit module descriptors, BEFORE any realm — the
      surface large-SPA code uses to compose + inspect a program before it is
      installed (see §Public construction + composition below).

  ## Stage 5 (projection) is INTERNAL; stage 6 (construction) is PUBLIC

  Stage 5 shipped an internal app-value descriptor format + the projection
  seam, for the registrations that ALREADY exist in the default realm's
  registrar — INTERNAL, reached only by `re-frame.realm` and the conformance
  tests, with **zero ergonomic regression** (the ordinary namespace-load
  `reg-*` sugar path is byte-identical; nothing on the registration hot path
  changes).

  Stage 6 graduates the PUBLIC construction half: `module` + `app` (and the
  inspectors `app-registrations` / `app-owns` / `app-requires`), re-exported
  from `re-frame.core` as `rf/module` / `rf/app` / `rf/app-registrations` /
  `rf/app-owns` / `rf/app-requires`. These are the reserved-vocabulary names
  ruled in EP-0013 issue 1; `rf/realm` + `rf/install!` / `rf/reinstall!`
  (stage 7 — seating an app value into a realm) are NOT shipped here.
  Construction is PURE: a `module` / `app` call has no registration side
  effect (no realm, no registrar touch — the value is inert data per EP-0013
  issue 10's \"inert is load-bearing\" rule); the projection seam (stage 5)
  is untouched.

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

  Projection (stage 5) is the READ direction over the default realm's
  load-order program (the process-load `reg-*` registrations re-grouped into
  the descriptor value tooling reads and diffs). On top of it this ns now also
  ships the WRITE directions that complete D2: explicit `rf/app` / `rf/module`
  *construction* (stage 6 — compose modules into an app value as INERT data,
  before any realm) and `install!` / `reinstall!` (stage 7 — seat an explicit
  app value into a realm; see §Installation + §reinstall below). All three
  produce or consume the SAME descriptor-grouped shape, so a projected app and
  a constructed app are interchangeable to the inspectors and the installer.

  ## Production elision

  Pure data over the realm/registrar maps; no trace emit sites, no
  DEBUG-gated branches, no feature sentinels, and no per-feature `:require`
  (only `re-frame.realm` + `re-frame.registrar` + `re-frame.late-bind`, all
  already in the core spine; the lowering into a kind's real registration
  logic is reached through the `:app-value/install-descriptor!` late-bind hook,
  never a direct `:require` of the reg surfaces, so this ns stays a leaf on the
  realm/registrar spine and is bundle-isolation neutral). The projection +
  construction are operational metadata, not a dev surface — they survive
  `:advanced` + `goog.DEBUG=false` intact. None of these fns runs on the
  registration hot path (the `reg-*` sugar path is byte-identical), so in an
  app that never calls the construction/install surface they are dead code
  Closure DCE removes; an app that DOES call `rf/app` / `rf/install!` keeps
  exactly what it references."
  (:require [clojure.set        :as set]
            [re-frame.realm     :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the app-value top-level shape ----------------------------------------
;;
;; Per EP-0013 §App Values + the draft normalized shape (Spec-Schemas), an
;; app value contains (directly or through its modules):
;;
;;   :rf.app/id     the app's id. A PROJECTED app (stage 5) carries the
;;                  realm's id (`:rf.realm/default` for the default realm —
;;                  the program the realm carries). A CONSTRUCTED app (stage
;;                  6) carries the `:id` its `app` call declared.
;;   :registrations the normalized registration descriptors, grouped by
;;                  registry kind: `kind → id → descriptor`. For a projected
;;                  app this is the re-grouping of the realm's registrar; for
;;                  a constructed app it is the composition of its modules'
;;                  descriptors (collision-checked, owner-stamped).
;;   :rf.app/requires the set of `:rf.capability/*` requirements the program
;;                  declares. EMPTY for a projected app (capability
;;                  requirements are declared on MODULE values — the
;;                  default realm's load-order registrations carry none). For
;;                  a constructed app it is the union of its modules'
;;                  `:rf.module/requires`. The slot is always present. Owner-
;;                  qualified (`:rf.app/requires`) per EP-0007 one-name-per-
;;                  fact + the EP-0017 v5 line: a FACT about the app gets an
;;                  owner-qualified key (parallel to `:rf.app/id`); structural
;;                  section keys stay bare.
;;   :modules       (CONSTRUCTED apps only) the module map keyed by module id:
;;                  `module-id → module value`. A projected app carries no
;;                  modules (the load-order registrar has no module grouping),
;;                  so the slot is absent there. An app constructed from zero
;;                  modules carries an empty `:modules` map.
;;
;; A module value (stage 6, `module`) is a composable app-value FRAGMENT — the
;; same descriptor-grouped shape, plus a module id, the `:rf.module/owns`
;; ownership declarations, and the `:rf.module/requires` capability set. Both
;; ownership and requirements are FACTS about the module, so their keys are
;; owner-qualified (`:rf.module/*`) per EP-0007 one-name-per-fact + the EP-0017
;; v5 line — parallel to `:rf.cofx/requires`, `:rf.scope/*`, `:rf.capability/*`;
;; the structural section keys (`:id`, `:events`, `:subs`, `:routes`, …) stay
;; bare:
;;
;;   :rf.module/id  the module's id (stamped onto each descriptor's :owner).
;;   :registrations the module's own descriptors grouped by kind.
;;   :rf.module/owns the ownership declarations (`{:app-db [...] :routes [...]
;;                  :resources [...] ...}`) — not merely documentation;
;;                  composition surfaces them for tooling (overlap validation
;;                  beyond same-(kind,id) collision is a later slice).
;;   :rf.module/requires the module's `:rf.capability/*` requirements (empty
;;                  set when none declared).
;;   :source        the module's own source-coordinate envelope, when the
;;                  caller supplies one (absent otherwise — issue 8: absence
;;                  never changes behaviour).
;;
;; D3-RESERVED, never produced here: :diagnostics as a SUCCESS-path slot —
;; composition reports collisions by THROWING (issue 7: data is the primitive,
;; the ex-data IS the diagnostic), so a returned app value is always valid.

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
  `:ns`/`:file`/`:line`/`:column` slots into `:source`, the owning-module id out
  of `:owner` (present only when the registration was lowered from an
  `install!`-seated MODULE — `install!` carries `:owner` through into the
  registrar metadata, rf2-77ewnm; absent for ordinary `reg-*` sugar, which
  declares no module), and keeps the rest of the Spec 001 registration metadata
  under `:metadata` (with the handler, source-coord, and owner slots removed so
  each fact is carried once). Lifting `:owner` to the top level makes a PROJECTED
  installed descriptor structurally identical to the CONSTRUCTED one
  `module-descriptor` emits — one app-value vocabulary, two origins (EP-0013) —
  so a reconciled `installed-app` descriptor reads its provenance off the same
  top-level `:owner` whether it came from sugar or from a module. INTERNAL."
  [kind id metadata]
  (let [source (descriptor-source metadata)
        ;; The remaining metadata: the Spec 001 registration map minus the
        ;; handler slot, the lifted source-coord slots, and the lifted `:owner`
        ;; provenance slot. Kind-specific extras (`:event/kind`, `:input-kind`,
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
    {:rf.app/id       <realm-id>          ;; the app the realm carries
     :registrations   {kind {id descriptor}} ;; normalized descriptors by kind
     :rf.app/requires #{}}                ;; capability requirements — always
                                          ;; empty for a projected app
                                          ;; (requirements are declared on
                                          ;; MODULE values; load-order
                                          ;; registrations carry none)

  This PROJECTION fn is INTERNAL — there is no public read surface for a
  realm's projected installed app (the public app-value vocabulary is the
  stage-6 `rf/app` / `rf/module` CONSTRUCTORS + the `rf/install!` seating
  path, not this read seam). `re-frame.realm/installed-app` reaches it through
  the `:app-value/project` late-bind hook. nil resolves to the default realm
  (absence = default realm, the D1 rule)."
  ([] (app-value realm/default-realm-id))
  ([realm-or-id]
   (let [rid       (realm/realm-id realm-or-id)
         registrar (realm/registrar (realm/realm rid))
         snapshot  (if registrar @registrar {})]
     {:rf.app/id       rid
      :registrations   (registrations->descriptors snapshot)
      :rf.app/requires #{}})))

;; ---- public construction + composition (EP-0013 D2 stage 6) ---------------
;;
;; The CONSTRUCTION half: build an app value as INERT data from explicit
;; module descriptors, before any realm. `module` lowers a module-descriptor
;; map into a module value (the same descriptor-grouped shape the projection
;; produces, owner-stamped); `app` composes a vector of module values into an
;; app value. Both are PURE — no realm, no registrar, no side effect (a
;; `module` / `app` call is data, not registration — EP-0013 issue 10's
;; "inert is load-bearing").
;;
;; The construction descriptor shape MATCHES the projection's (`descriptor`
;; above), so a constructed app's `:registrations` and a projected app's are
;; the SAME shape — one app-value vocabulary, two origins. The only addition
;; is `:owner` (the module id), which the projection never carries (load-order
;; registrations have no module). The module-section input shape is the EP's
;; (`{:doc … :schema … :handler …}` per entry — a handler-and-metadata map),
;; not the registrar's flat `:handler-fn` metadata, so construction has its
;; own normalizer (`module-descriptor`) rather than reusing `descriptor`.

(def ^:private module-section->kind
  "The module-descriptor section keys (plural, per the EP module form) mapped
  to their Spec 001 registry kind (singular). A `module` map carries its
  registrations under these section keys (`:events {…}`, `:subs {…}`, …); each
  section lowers to descriptors under the corresponding registry kind. The set
  is the Spec 001 `registrar/kinds` taxonomy — adding a registry kind that a
  module can carry is a one-row addition here (kept in lockstep with the
  closed `registrar/kinds` set)."
  {:events          :event
   :subs            :sub
   :fx              :fx
   :cofx            :cofx
   :views           :view
   :frames          :frame
   :routes          :route
   :heads           :head
   :error-projectors :error-projector
   :flows           :flow
   :resources       :resource
   :mutations       :mutation
   :resource-scopes :resource-scope})

(def ^:private module-reserved-keys
  "Reserved top-level module keys that are NOT registration sections — lifted
  out before the section scan so a module's own `:id` / `:rf.module/owns` /
  `:rf.module/requires` / `:source` are never mistaken for a registration kind.
  `:rf.module/owns` and `:rf.module/requires` are owner-qualified FACT keys
  (EP-0007 / EP-0017 v5); `:id` and `:source` are structural slots."
  #{:id :rf.module/owns :rf.module/requires :source})

(defn- module-descriptor
  "Normalize one module registration entry into an app-value registration
  descriptor (EP-0013 §Registration Descriptors), stamped with its owning
  module id. Pure: a function of `kind`, `id`, the module id, and the entry
  map only.

  The entry map is the EP's module-form shape (`{:doc … :schema … :handler …
  :source …}` — a handler-and-metadata map), NOT the registrar's flat
  `:handler-fn` metadata. The handler is lifted out of `:handler`, the source
  coords out of `:source` (an explicit envelope a non-macro/code-gen host may
  supply — issue 8), and the rest of the entry kept under `:metadata` (with
  the lifted slots removed so each fact is carried once). INTERNAL."
  [kind id owner entry]
  (let [source    (:source entry)
        rest-meta (dissoc entry :handler :source)]
    (cond-> {:kind  kind
             :id    id
             :owner owner}
      (contains? entry :handler) (assoc :handler (:handler entry))
      (seq source)               (assoc :source source)
      (seq rest-meta)            (assoc :metadata rest-meta))))

(defn module
  "Construct a MODULE value — a composable app-value fragment (EP-0013 §Module
  Values And Feature Ownership), as INERT data. PUBLIC (`rf/module`).

  `descriptor-map` carries:

    :id                 the module id (required) — stamped onto every
               descriptor's `:owner` so composition can name the colliding
               source. Structural section key, BARE.
    :rf.module/owns     ownership declarations (`{:app-db [[:cart]] :routes […]
               :resources […] …}`) — the feature surfaces the module owns.
               Carried through to the app value for tooling (overlap
               validation beyond same-(kind,id) collision is a later slice).
               A FACT about the module, so owner-qualified (EP-0007 /
               EP-0017 v5) — parallel to `:rf.cofx/requires`, `:rf.scope/*`.
    :rf.module/requires the set of `:rf.capability/*` requirements (defaults
               to `#{}`). A FACT about the module — owner-qualified, naming
               the capability contract (as `:rf.cofx/requires` names the cofx
               contract). Values are already `:rf.capability/*`-qualified;
               this owner-qualifies the KEY.
    :source    the module's own source-coordinate envelope, when the host
               supplies one (optional — issue 8: absence never changes
               behaviour). Structural envelope slot, BARE.
    <sections> registration sections keyed by the plural section name
               (`:events {id entry} :subs {…} :routes {…} …`, per the EP
               module form); each `entry` is a `{:doc … :schema … :handler …
               :source …}` handler-and-metadata map. Structural section keys,
               BARE. The section→kind map is the Spec 001 registry taxonomy.

  Returns a module value:

    {:rf.module/id       <id>
     :registrations      {kind {id descriptor}}  ;; descriptors, :owner-stamped
     :rf.module/owns     <owns or {}>
     :rf.module/requires <requires set>
     :source             <source>}          ;; present only when supplied

  PURE — no realm, no registrar, no side effect. Throws `:rf.error/invalid-module`
  (an ex-info whose ex-data IS the diagnostic) when `:id` is missing — the one
  construction-time precondition (issue 7: data is the primitive)."
  [descriptor-map]
  (let [id (:id descriptor-map)]
    (when (nil? id)
      (throw (ex-info "rf/module requires an :id"
                      {:error/id :rf.error/invalid-module
                       :descriptor descriptor-map
                       :recovery :supply-a-module-id})))
    (let [registrations
          (reduce-kv
            (fn [acc section entries]
              (if-let [kind (module-section->kind section)]
                (assoc acc kind
                       (reduce-kv (fn [m eid entry]
                                    (assoc m eid (module-descriptor kind eid id entry)))
                                  {}
                                  entries))
                ;; A non-section, non-reserved top-level key is a malformed
                ;; module — fail loudly rather than silently dropping it.
                (if (contains? module-reserved-keys section)
                  acc
                  (throw (ex-info (str "rf/module: unknown section key " section)
                                  {:error/id :rf.error/invalid-module
                                   :module id
                                   :unknown-section section
                                   :recovery :remove-or-correct-the-section-key})))))
            {}
            descriptor-map)]
      (cond-> {:rf.module/id       id
               :registrations      registrations
               :rf.module/owns     (get descriptor-map :rf.module/owns {})
               :rf.module/requires (set (get descriptor-map :rf.module/requires #{}))}
        (seq (:source descriptor-map)) (assoc :source (:source descriptor-map))))))

;; ---- composition: modules → an app value ----------------------------------
;;
;; Composition is DETERMINISTIC and ORDER-STABLE (EP-0013 §Composition): given
;; the same module values it produces the same app value or the same ordered
;; diagnostic, regardless of namespace load timing or how the modules were
;; grouped. It MUST NOT silently use last-writer-wins for same-(kind,id)
;; conflicts — a collision THROWS `:rf.error/app-composition-collision`,
;; enumerating EVERY colliding source available (issue 7: the ex-data IS the
;; validation result; the family is a boot-time-reachable install failure, so
;; it routes through the EP-0008 promotion criterion at stage 7).
;;
;; The composition laws (EP-0013 §Composition + §Validation/Conformance) the
;; tests pin: composing with an empty module is identity; grouping modules
;; differently does not change the resulting app value; successful composition
;; preserves every input descriptor exactly once; failed composition reports
;; every colliding source.

(defn- merge-module-registrations
  "Fold one module's `:registrations` (`kind → id → descriptor`) into the
  accumulating app registrations, detecting same-(kind,id) collisions. On a
  collision throws `:rf.error/app-composition-collision` carrying BOTH the
  already-present descriptor's owner+source and the incoming one's, so the
  host can name every colliding source (EP-0013 §Composition). INTERNAL."
  [acc module-regs]
  (reduce-kv
    (fn [acc kind id->desc]
      (reduce-kv
        (fn [acc id desc]
          (if-let [existing (get-in acc [kind id])]
            (throw (ex-info (str "app composition collision: two modules register "
                                 kind " " id)
                            {:error/id :rf.error/app-composition-collision
                             :kind     kind
                             :id       id
                             :sources  [(cond-> {:module (:owner existing)}
                                          (:source existing) (assoc :source (:source existing)))
                                        (cond-> {:module (:owner desc)}
                                          (:source desc) (assoc :source (:source desc)))]
                             :recovery :rename-or-explicitly-replace}))
            (assoc-in acc [kind id] desc)))
        acc
        id->desc))
    acc
    module-regs))

(defn app
  "Construct an APP value by composing module values (EP-0013 §App Values +
  §Composition), as INERT data. PUBLIC (`rf/app`).

  `app-map` carries:

    :id      the app id (required) — the constructed app's `:rf.app/id`.
    :modules a vector (or seq) of module values (from `module`). Defaults to
             empty — an app of zero modules is a valid, empty app value.

  Returns an app value:

    {:rf.app/id       <id>
     :modules         {module-id module}      ;; the composed modules by id
     :registrations   {kind {id descriptor}}  ;; every module's descriptors,
                                               ;; collision-checked + owner-stamped
     :rf.app/requires #{:rf.capability/* …}}   ;; union of module :rf.module/requires

  DETERMINISTIC + ORDER-STABLE: the same modules compose to the same app value
  regardless of order, and a same-(kind,id) collision across modules THROWS
  `:rf.error/app-composition-collision` (the ex-data names every colliding
  source) rather than silently picking a winner — there is no last-writer-wins
  (EP-0013 §Composition; issue 7). PURE — no realm, no registrar, no side
  effect; an app value is inert until a stage-7 `install!` seats it.

  Duplicate MODULE ids are likewise NOT last-writer-wins (rf2-c6armm.1 #4):
  module id is the provenance key (`:modules` is keyed by it; descriptors carry
  it as `:owner`). Two `:modules` entries with the SAME `:rf.module/id` that are
  EXACTLY equal are deduped (idempotent re-listing); DIVERGENT same-id modules
  THROW `:rf.error/app-composition-collision` (`:kind :module`), since silently
  keeping one would make composition order-dependent.

  Throws `:rf.error/invalid-app` when `:id` is missing, or when a `:modules`
  entry is not a module value (a `module`-constructed map carrying
  `:rf.module/id`)."
  [app-map]
  (let [id      (:id app-map)
        modules (vec (get app-map :modules []))]
    (when (nil? id)
      (throw (ex-info "rf/app requires an :id"
                      {:error/id :rf.error/invalid-app
                       :app app-map
                       :recovery :supply-an-app-id})))
    (doseq [m modules]
      (when-not (and (map? m) (contains? m :rf.module/id))
        (throw (ex-info "rf/app :modules entries must be module values (from rf/module)"
                        {:error/id :rf.error/invalid-app
                         :app id
                         :bad-module m
                         :recovery :wrap-the-descriptor-in-rf-module}))))
    ;; Module id is the PROVENANCE key — `:modules` is keyed by it, every
    ;; descriptor's `:owner` carries it, and `app-owns` resolves through it. A
    ;; duplicate `:rf.module/id` must not silently last-writer-win (rf2-c6armm.1
    ;; #4 / .3 #3): that makes composition order-dependent — reversing module
    ;; order would change which module value `:modules` records and which source
    ;; a collision diagnostic names, even though the descriptors of BOTH modules
    ;; are folded in. Two modules with the same id that are EXACTLY equal are
    ;; deduped (idempotent re-listing is harmless); DIVERGENT same-id modules
    ;; THROW `:rf.error/app-composition-collision` (the ex-data IS the
    ;; diagnostic — EP-0013 §Composition: deterministic, order-stable, never
    ;; last-writer-wins).
    (let [by-id (reduce (fn [acc m]
                          (let [mid (:rf.module/id m)]
                            (if-let [prev (get acc mid)]
                              (if (= prev m)
                                acc            ; exact-equal duplicate — idempotent
                                (throw (ex-info
                                         (str "rf/app: two distinct modules share the id "
                                              mid " — module id is the provenance key, so a"
                                              " divergent duplicate makes composition"
                                              " order-dependent (not last-writer-wins).")
                                         {:error/id :rf.error/app-composition-collision
                                          :app      id
                                          :kind     :module
                                          :id       mid
                                          :recovery :give-each-module-a-unique-id})))
                              (assoc acc mid m))))
                        {}
                        modules)
          ;; Compose registrations + requires from the DEDUPED module set, so an
          ;; exact-equal duplicate does not double-fold (which would otherwise
          ;; trip the same-(kind,id) collision check spuriously).
          deduped (vals by-id)]
      {:rf.app/id       id
       :modules         by-id
       :registrations   (reduce (fn [acc m]
                                  (merge-module-registrations acc (:registrations m)))
                                {}
                                deduped)
       :rf.app/requires (reduce (fn [acc m] (into acc (:rf.module/requires m)))
                                #{}
                                deduped)})))

;; ---- public inspection over an app value (EP-0013 §Examples) --------------
;;
;; The three inspectors the EP's "A Whole App As Data" example shows — read an
;; app value WITHOUT installing it: enumerate its registrations by kind, find
;; the module that owns an app-db path, read its capability requirements. Pure
;; readers over the app-value data; no realm, no registrar. PUBLIC.

(defn app-registrations
  "Return the registration descriptors an app value carries for `kind`
  (`kind → {id descriptor}` for that one kind), or `nil` when the app declares
  none of that kind. The enumerable registration view that makes static
  dispatch-coverage checks possible WITHOUT installing the app (EP-0013
  §Validation/Conformance). PUBLIC (`rf/app-registrations`).

  Works over both constructed apps (`app`) and projected apps (`app-value`) —
  the `:registrations` shape is the same for both. Pure."
  [app-value kind]
  (get-in app-value [:registrations kind]))

(defn app-requires
  "Return the set of `:rf.capability/*` requirements an app value declares —
  the union of its modules' `:rf.module/requires` (EP-0013 §Capability Maps),
  read off the app value's `:rf.app/requires` slot. The explicit dependency
  surface a realm must satisfy before a stage-7 `install!` succeeds. PUBLIC
  (`rf/app-requires`). Pure — returns `#{}` for a projected app (load-order
  registrations declare no requirements)."
  [app-value]
  (get app-value :rf.app/requires #{}))

(defn app-owns
  "Return the module id that owns app-db `path` in an app value, or `nil` when
  no module declares it (EP-0013 §Module Values And Feature Ownership /
  §Examples `(rf/app-owns app [:cart])`). PUBLIC (`rf/app-owns`).

  Resolves against the modules' `:rf.module/owns {:app-db [...]}` declarations:
  returns the id of the module whose `:rf.module/owns :app-db` vector contains
  exactly `path`. Pure; returns `nil` for a projected app (no modules, hence no
  ownership declarations)."
  [app-value path]
  (some (fn [[mid m]]
          (when (some #(= % path) (get-in m [:rf.module/owns :app-db]))
            mid))
        (:modules app-value)))

;; ---- installation: seat an app value into a realm (EP-0013 D2 stage 7) -----
;;
;; The LAST D2 slice. `install!` seats an immutable app value as a realm's
;; program; `reinstall!` hot-reloads a realm by diffing the new app value
;; against the installed one and applying the delta. Together they close the
;; D2 loop: the program is a value (stage 6 construction), and the runtime is a
;; container you install it into (stage 7).
;;
;; Two responsibilities, in order (EP-0013 §Installation):
;;   1. CAPABILITY CHECK — the app value's `:rf.app/requires` (its `:rf.capability/*`
;;      dependency surface) must be satisfiable by the realm's `:capabilities`
;;      map. Fail LOUD (`:rf.error/missing-capability`) on the FIRST unmet
;;      capability, BEFORE any registrar mutation — installation must validate
;;      the app before making it visible (§Installation step 2: "validate that
;;      the realm satisfies the app's capability requirements"; §Capability
;;      maps: "installation fails if required capabilities are absent").
;;   2. DERIVE THE REGISTRAR — lower every descriptor back into the registrar's
;;      flat metadata shape and `register!` it, then record the seated app value
;;      in the realm's `:app` slot. The public contract is value replacement at
;;      the realm boundary (§Installation: "An implementation MAY mutate
;;      internal cells during installation; the public contract is value
;;      replacement at the realm boundary, not process-global table mutation as
;;      an architectural primitive").
;;
;; ## Zero ergonomic regression — the default-realm `reg-*` sugar path is UNTOUCHED
;;
;; `install!` is the EXPLICIT seating path. The ordinary namespace-load `reg-*`
;; sugar path stays exactly as it was: a `reg-*` call writes the default realm's
;; registrar in place, and the next `installed-app` projection reflects it
;; (EP-0013 §Default Realm And `reg-*` Sugar — sugar updates the default realm's
;; app value in place). `install!` does not run on the registration hot path,
;; touches nothing a `reg-*` call touches except through the same
;; `registrar/register!` it already uses, and the projection seam (stage 5) is
;; unchanged. Construction (stage 6) and the sugar path remain byte-identical.

(defn descriptor->registration-metadata
  "Lower an app-value registration descriptor back into the registrar's flat
  metadata shape — the inverse of `descriptor` / `module-descriptor`. Re-flattens
  the `:source` coordinate envelope into the flat `:ns`/`:file`/`:line`/`:column`
  slots, re-seats the handler under `:handler-fn`, folds the `:metadata` map back
  to top level, and carries the `:owner` (module provenance) through so the
  realm's registrar records which module installed each registration. Pure —
  a function of the descriptor only. INTERNAL."
  [{:keys [handler source metadata owner]}]
  (cond-> (merge metadata source)
    (some? handler) (assoc :handler-fn handler)
    (some? owner)   (assoc :owner owner)))

(defn- resolve-target-realm!
  "Resolve `realm-or-id` to the realm-id `install!` / `reinstall!` will seat
  into, validating that an EXPLICIT non-nil id names a real, registered realm
  BEFORE any registrar mutation (EP-0013 correctness, rf2-c6armm.1/.2).

  EP/API defaulting is for the ABSENCE of a realm, not for an arbitrary unknown
  explicit id (Runtime-Subsystems §The default realm: 'absence of a realm means
  the default realm … never a synthesised one'; Spec 002 §Frames reference
  realms; spec/API.md install! row). The pre-fix path silently fell back to the
  default/global registrar for a typo'd or never-constructed id — `seat-into-realm!`
  resolved `(realm/registrar (realm/realm rid))` to the default atom and
  `set-installed-app!`'s `update-realm!` no-op'd, so the program polluted the
  DEFAULT registrar while the requested realm recorded no installed app.

  Three cases:
    * nil               — the absence-is-default sugar. Resolves to the default
                          realm id (the byte-identical single-realm path).
    * the default id     — explicit default. Always valid (it is seeded at boot).
    * a realm MAP        — the caller holds the constructed realm value; trust its
                          `:rf.realm/id` (the `(-> (rf/realm …) (rf/install! app))`
                          compose path hands the map straight through).
    * any other keyword  — an EXPLICIT id. Must be registered in `realm/realms`,
                          else THROW `:rf.error/unknown-realm` (the ex-data IS the
                          diagnostic — naming the id + the live realm ids + the
                          recovery: construct it first).

  Returns the resolved realm-id keyword. INTERNAL."
  [realm-or-id]
  (cond
    (nil? realm-or-id)                          realm/default-realm-id
    (= realm-or-id realm/default-realm-id)      realm/default-realm-id
    (map? realm-or-id)                          (realm/realm-id realm-or-id)
    (some? (realm/realm realm-or-id))           realm-or-id
    :else
    (throw (ex-info (str "rf/install!: realm " realm-or-id
                         " is not registered — an explicit realm must be"
                         " constructed (rf/realm) before an app value can be"
                         " installed into it. Absence defaults to the default"
                         " realm; an unknown explicit id does not.")
                    {:error/id    :rf.error/unknown-realm
                     :realm       realm-or-id
                     :known-realms (realm/realm-ids)
                     :recovery    :construct-the-realm-first}))))

(defn- realm-capabilities
  "The realm's capability map (`:rf.capability/* → service`), or `{}` when the
  realm seats none. INTERNAL."
  [rid]
  (get (realm/realm rid) :capabilities {}))

(defn- check-capabilities!
  "Validate that `realm-id`'s capability map satisfies every `:rf.capability/*`
  the app value `:rf.app/requires`. Throws `:rf.error/missing-capability` (an ex-info
  whose ex-data IS the diagnostic — issue 7) on the FIRST unmet capability,
  naming the realm + the missing capability, BEFORE any registrar mutation
  (EP-0013 §Installation step 2 / §Capability maps). A no-op when the app
  requires nothing. INTERNAL."
  [rid app]
  (let [have (set (keys (realm-capabilities rid)))]
    (doseq [cap (app-requires app)]
      (when-not (contains? have cap)
        (throw (ex-info (str "rf/install!: realm " rid
                             " does not satisfy required capability " cap)
                        {:error/id   :rf.error/missing-capability
                         :realm      rid
                         :capability cap
                         :required   (app-requires app)
                         :available  have
                         :recovery   :install-capability}))))))

(defn- registrations-seq
  "Flatten an app value's `:registrations` (`kind → id → descriptor`) into a
  seq of `[kind id descriptor]` triples. INTERNAL."
  [app]
  (for [[kind id->desc] (:registrations app)
        [id desc]       id->desc]
    [kind id desc]))

(defn- frame-descriptor-ids
  "The `:frame` descriptor ids an app value carries (the keys of its
  `:registrations :frame` map), or `nil` when it declares no frames. INTERNAL."
  [app]
  (keys (get-in app [:registrations :frame])))

(defn- refuse-non-default-realm-frames!
  "Refuse `:frame` descriptors seated into a NON-default realm, BEFORE any
  lowering, throwing `:rf.error/realm-frames-unsupported` (rf2-c6armm.1).

  EP-0013 §Frames Reference Realms requires a frame to belong to the realm it
  is installed into (frame ids are unique WITHIN a realm; the same id in two
  realms is a tested legal case). But the realm-aware frame-registration path is
  a NOT-YET-SHIPPED EP-0013 slice (staging step 4 — 'thread realm through frame
  records / dispatch envelopes / subscription resolution'): `re-frame.frame`'s
  `reg-frame` is `[id metadata]`-only and HARDCODES `:realm default-realm-id` on
  every frame record, and live dispatch/subscribe resolve through the default
  registrar regardless of realm. So a `:frame` descriptor lowered into an
  explicit realm would:
    * create a frame STAMPED with the default realm, not the requested realm
      (the realm does not actually own its frames — `frame-realm` would report
      the default), and
    * key the live frame in the process-global `frame/frames` atom by frame-id
      ALONE, so the same frame id installed into two realms collides globally
      (one overwrites the other; both realms' membership sets stay empty).

  Until the realm-aware frame path lands (tracked: rf2-a15n62 — staging step 4),
  refuse loudly rather than silently mis-seat — fail-closed, the EP-0013 issue-12
  stance, and the masterpiece-correctness posture. The default realm is fine:
  `reg-frame`'s hardcoded default IS the target, so a default-realm `:frame`
  descriptor lowers correctly (the rf2-chc8vs wiring). INTERNAL."
  [rid app]
  (when-not (= rid realm/default-realm-id)
    (when-let [fids (seq (frame-descriptor-ids app))]
      (throw (ex-info (str "rf/install!: cannot seat :frame descriptor(s) "
                           (pr-str (vec (sort fids))) " into the non-default realm "
                           rid " — realm-aware frame registration is a later"
                           " EP-0013 slice (frames are still stamped with the"
                           " default realm and keyed globally by frame-id). A"
                           " :frame seated into an explicit realm would not be"
                           " owned by it and would collide across realms. Install"
                           " :frame descriptors into the default realm, or register"
                           " the frame through reg-frame.")
                      {:error/id    :rf.error/realm-frames-unsupported
                       :realm       rid
                       :frame-ids   (vec (sort fids))
                       :recovery    :install-frames-into-the-default-realm})))))

(defn- register-descriptor!
  "Seat one descriptor into the realm's registrar so it is dispatch/resolve-
  ready. A CONSTRUCTED descriptor (high-level `module` form) must be lowered
  through its kind's real registration logic (the event interceptor wrap, the
  sub input-signal parse, the frame container create + `:on-create`, …) — that
  logic lives in `re-frame.events` / `.subs` / `.fx` / `.cofx` / `.frame`,
  which this leaf ns must not require, so it is reached through the
  `:app-value/install-descriptor!` late-bind hook core publishes. The hook
  wires the EP-0013 step-7 first-format kinds (`:event`/`:sub`/`:fx`/`:cofx`/
  `:frame`) and returns truthy; for the step-8-DEFERRED kinds (`:route`/`:flow`/
  `:resource`/`:mutation`/`:view`/`:head`/`:error-projector`/`:resource-scope`)
  it THROWS `:rf.error/unsupported-descriptor-kind` (refuse-loudly, fail-closed
  per EP-0013 issue-12) rather than seat a malformed flat slot. The flat
  registrar lowering is the FALLBACK reached only when the hook returns falsy —
  a projected descriptor whose `:metadata` already carries the wrapped slots,
  with the hook unbound (a bundle that never loaded core's reg surfaces); it
  round-trips a projected descriptor unchanged. INTERNAL."
  [kind id desc]
  (let [lower (late-bind/get-fn :app-value/install-descriptor!)]
    (when-not (and lower (lower kind id desc))
      (registrar/register! kind id (descriptor->registration-metadata desc)))))

(defn- seat-into-realm!
  "Run `thunk` (the per-descriptor seating) with `registrar/*registrar*` bound
  to realm `rid`'s OWN registrar atom, so every `register!` / `unregister!` the
  lowering path issues targets THAT realm's `(kind, id) → metadata` table
  (EP-0013 stage 9 isolation). For the default realm the realm's registrar IS
  the process-global atom, so the binding rebinds to the same atom — the
  default-realm seating path is byte-identical. `install!` / `reinstall!`
  validate `rid` upstream (`resolve-target-realm!`), so an explicit unknown id
  never reaches here — it threw `:rf.error/unknown-realm` before any mutation
  (rf2-c6armm.1/.2). The nil-registrar arm below stays as a defensive no-op for
  any non-install caller; the install path always resolves a real registrar.

  ATOMIC (EP-0013 §Installation step 4 — \"attach the app and registrar
  atomically\"): the seating loop is all-or-nothing. The thunk seats a
  MULTI-descriptor app one descriptor at a time, and a malformed descriptor can
  throw PART-WAY (after kinds 1..N-1 already landed). To keep the realm's
  registrar and its `:app` slot from ever disagreeing, the registrar atom's
  value is captured BEFORE the loop and RESTORED on any throw, then the throw
  re-propagates — so a failed `install!` leaves the realm exactly as it was
  (no half-populated registrar; `install!` records no partial `:app`, since
  `set-installed-app!` runs only after the thunk returns cleanly). The
  default-realm path snapshots the process-global atom the same way; a
  successful seating restores nothing (the loop's writes stand). INTERNAL."
  [rid thunk]
  (let [reg (realm/registrar (realm/realm rid))]
    (binding [registrar/*registrar* reg]
      (if reg
        (let [snapshot @reg]
          (try
            (thunk)
            (catch #?(:clj Throwable :cljs :default) e
              ;; Roll back every registration the partial loop landed, so a
              ;; mid-stream failure leaves the realm's registrar untouched.
              (reset! reg snapshot)
              (throw e))))
        ;; No registrar atom resolved (an unknown id with no default fallback) —
        ;; nothing to snapshot; run the thunk as-is.
        (thunk)))))

;; `diff-registrations` + `refuse-live-frame-removal!` are defined under the
;; reinstall section below (their narrative lives with `reinstall!`), but
;; `install!`'s replacement step (rf2-c6armm.7 #1) now shares them to clear a
;; prior installed app's stale registrations and refuse a stale live-frame
;; removal. Forward-declared so the install! body can reference them without
;; reordering the reinstall narrative.
(declare diff-registrations refuse-live-frame-removal!)

(defn- stored-installed-app
  "The app value a PRIOR `install!`/`reinstall!` recorded in realm `rid`'s `:app`
  slot, or nil when none was seated. Unlike `realm/installed-app` (which falls
  back to the recomputable registrar projection), this reads ONLY the stored
  slot — `install!`'s replacement step clears the registrations of the
  PREVIOUSLY-INSTALLED app, and the load-order `reg-*` sugar program (which the
  projection would surface) is explicitly NOT a prior install to clear against
  (rf2-c6armm.7 #1 — sugar coexistence is preserved). INTERNAL."
  [rid]
  (:app (realm/realm rid)))

(defn install!
  "Seat an immutable app VALUE into a realm — make `app`'s registrations the
  program `realm-or-id` dispatches/subscribes/resolves against (EP-0013 D2
  stage 7, §Installation). PUBLIC (`rf/install!`).

  Steps, in order:

    1. CAPABILITY CHECK — every `:rf.capability/*` in `(app-requires app)` must
       be present in the realm's `:capabilities` map. The FIRST unmet one
       THROWS `:rf.error/missing-capability` (naming the realm + capability)
       BEFORE any registrar mutation, so an under-provisioned app never becomes
       partially visible (§Installation step 2; §Capability maps).
    2. PREFLIGHT THE KINDS — refuse the whole app loudly if ANY descriptor names
       a step-8-DEFERRED kind, BEFORE lowering a single descriptor
       (rf2-c6armm.8 #2). `install-descriptor!` also throws on a deferred kind,
       but mid-loop — after a wired `:frame` may already have lowered into a LIVE
       container the registrar-only rollback cannot undo. Preflighting closes
       that failed-install side-effect-leak window.
    3. DERIVE THE REGISTRAR (full replacement) — `install!` makes the realm's
       registrar BE the app's program (EP-0013 §Installation step 3, 'derive the
       realm registrar from the app value'; 'value replacement at the realm
       boundary'). A REPEATED `install!` therefore clears the
       PREVIOUSLY-INSTALLED app's registrations that the new app drops
       (rf2-c6armm.7 #1) — without it, `install! app1` then `install! app2`
       would leave app1's handlers resolvable while `installed-app` reports app2,
       so the installed-app value would stop being the source of truth for the
       realm registrar. The clear + the new seating run in ONE atomic
       `seat-into-realm!` snapshot, then the new value is recorded in `:app`.

  Replacement is scoped to the PRIOR INSTALLED app, not the whole registrar: the
  load-order `reg-*` sugar program is NOT cleared (it is not a prior install), so
  sugar registrations and installed registrations coexist exactly as before. A
  fresh realm (or a pure-sugar default realm) has no stored `:app`, so `install!`
  is purely additive there — byte-identical to today.

  `realm-or-id` defaults to the default realm — `(install! app)` seats `app`
  into the process default realm. Returns the realm map after install (its
  `:app` slot now holds `app`), so the call composes — `(-> realm (install! app))`.

  The ordinary `reg-*` sugar path is UNCHANGED: it writes the default realm's
  registrar in place, and the projection reflects it for free. `install!` is the
  EXPLICIT seating path for a constructed (stage-6) app value; it does not run
  on the registration hot path."
  ([app] (install! realm/default-realm-id app))
  ([realm-or-id app]
   ;; (0) resolve the target realm FIRST — an explicit non-nil unknown id throws
   ;; `:rf.error/unknown-realm` before any registrar mutation (rf2-c6armm.1/.2),
   ;; rather than silently seating into the default registrar. nil/default sugar
   ;; and the realm-map compose form are preserved.
   (let [rid (resolve-target-realm! realm-or-id)]
     ;; (1) capability check — fail loud before any mutation.
     (check-capabilities! rid app)
     ;; (1b) refuse :frame descriptors into a non-default realm BEFORE lowering
     ;; (rf2-c6armm.1) — the realm-aware frame path is a later EP-0013 slice, so a
     ;; :frame seated into an explicit realm would be stamped default + collide
     ;; globally. Throwing here (pre-lowering) also keeps install! atomic for live
     ;; frame state: no `reg-frame` runs, so no live container is created and then
     ;; orphaned by a partial rollback (the rollback restores the registrar atom
     ;; but not the side-channel `frame/frames` atom).
     (refuse-non-default-realm-frames! rid app)
     ;; (1c) PREFLIGHT the kinds (rf2-c6armm.8 #2): refuse the whole app loudly if
     ;; any descriptor names a step-8-DEFERRED kind, BEFORE the seating loop lowers
     ;; anything. The per-descriptor throw in `install-descriptor!` fires mid-loop,
     ;; AFTER a wired `:frame` in the same app may have lowered into a live frame
     ;; container (a side-channel write the registrar-only rollback does not undo)
     ;; — so a `:frames`-then-deferred-kind app could leak a live frame from a
     ;; failed install. Preflighting through the core hook (no-op when unbound; the
     ;; in-loop throw remains the backstop) closes that window pre-lowering.
     (when-let [refuse (late-bind/get-fn :app-value/refuse-unsupported-install!)]
       (refuse (map (fn [[kind id _]] [kind id]) (registrations-seq app))))
     ;; (1d) REPLACEMENT (rf2-c6armm.7 #1): a repeated install! makes the realm's
     ;; registrar BE the new app's program — clearing the PREVIOUSLY-INSTALLED
     ;; app's registrations the new app drops. Diff the new app against the realm's
     ;; STORED `:app` only (not the sugar projection, so sugar coexists), and refuse
     ;; the same removal-path edges reinstall! refuses BEFORE any mutation: a stale
     ;; `:removed` that still backs a LIVE frame container would be orphaned
     ;; (`refuse-live-frame-removal!`), and a `:removed` deferred kind is not
     ;; descriptor-removable (the core refuse hook — defensive; a stored `:app`
     ;; only ever held wired kinds, since deferred kinds refuse at install). With no
     ;; stored `:app` the stale set is empty and this is a no-op.
     (let [prior   (stored-installed-app rid)
           diff    (when prior
                     (diff-registrations (:registrations prior) (:registrations app)))
           removed (:removed diff)]
       (when (seq removed)
         (when-let [refuse (late-bind/get-fn :app-value/refuse-unsupported-removal!)]
           (refuse removed))
         (refuse-live-frame-removal! rid diff))
       ;; (2) derive the registrar, then record the seated value at the realm
       ;; boundary. The atomic seating thunk FIRST unregisters the stale set, THEN
       ;; lowers every new descriptor through its kind's real registration logic
       ;; (so a constructed handler becomes dispatch-ready), writing the realm's OWN
       ;; `(kind, id) → metadata` table — value replacement at the realm boundary,
       ;; not a new global-mutation primitive. The lowering path
       ;; (`register-descriptor!` → the reg-* fns → `registrar/register!`) targets
       ;; `registrar/*registrar*`, so binding it to the target realm's own atom
       ;; seats the program into THAT realm's table (EP-0013 stage 9 isolation). nil
       ;; resolves to the global atom — the default-realm path is byte-identical
       ;; (the realm's registrar IS the global atom, so the binding is a no-op
       ;; rebind to the same atom). The whole thunk is all-or-nothing: the registrar
       ;; snapshot is restored on any throw, so a failed replacement leaves the
       ;; realm exactly as it was.
       (seat-into-realm! rid
         (fn []
           (doseq [[kind id] removed]
             (registrar/unregister! kind id))
           (doseq [[kind id desc] (registrations-seq app)]
             (register-descriptor! kind id desc))))
       (realm/set-installed-app! rid app)))))

;; ---- reinstall: hot-reload a realm as an app-value diff --------------------
;;
;; `reinstall!` models hot reload as replacing one app value with another in the
;; same realm (EP-0013 §Hot Reload And Reinstall). It diffs the new app value
;; against the installed one and applies the delta:
;;   :added   — (kind, id) in NEW but not OLD → register
;;   :changed — (kind, id) in BOTH, different descriptor → re-register (the
;;              registrar's hot-reload replacement path fires + traces)
;;   :removed — (kind, id) in OLD but not NEW → unregister (removed
;;              registrations fail loudly on future lookup — §Hot Reload rule)
;; then records the new app value in the realm's `:app` slot. Returns the diff
;; (the `{:realm :reason :added :changed :removed}` shape the EP's example shows
;; — `:reason` echoes `opts`, defaulting to `:hot-reload`), so a dev tool can
;; show what a save changed. The diff is the value; trace +
;; cache invalidation ride the registrar's existing hot-reload surface (a
;; re-register fires the replacement hooks subs.cljc already uses to invalidate
;; its cache — §Hot Reload: "changed subscriptions invalidate the relevant
;; caches", "future lookups use the new registrar").
;;
;; This is the descriptor-only first slice (EP-0013 issue 12, PRECISED: the first
;; reinstall slice is descriptor-only). Refuse-loudly binds at the KIND BOUNDARY
;; in BOTH directions: the step-8-deferred kinds throw
;; `:rf.error/unsupported-descriptor-kind` on the ADD/CHANGED path (the
;; `install-descriptor!` lowering) AND on the REMOVAL path
;; (`refuse-unsupported-removal!`, rf2-cquy9u). So a deferred kind is neither
;; app-value-INSTALLABLE nor app-value-REMOVABLE in this slice — the descriptor
;; diff simply does not own it in either direction (it stays owned by its own
;; `reg-*`/`clear-*` sugar lifecycle). The per-kind live-instance
;; blocker/continue/migrate rule binds when each deferred kind becomes
;; installable. The per-kind hot-reload rules each subsystem already honours
;; (active machine instances continue with their captured spec, etc.) are
;; unchanged. ERRATA (rf2-cquy9u): the earlier "structurally unreachable through
;; the diff" claim held only for the REGISTER (add/changed) path — the kind
;; throw lived solely in `install-descriptor!`. The REMOVAL path reached the
;; registrar unconditionally, so a sugar-registered step-8 id omitted from a
;; reinstall could be silently `unregister!`-ed; `refuse-unsupported-removal!`
;; closes that. The ONE reachable WIRED live edge — `:frame` removal of a live
;; container — is refused loudly (`refuse-live-frame-removal!`) rather than
;; silently orphaned.

(defn- diff-registrations
  "Diff two `:registrations` maps (`kind → id → descriptor`) into added /
  changed / removed `[kind id]` vectors. `:added` = in NEW not OLD; `:changed` =
  in BOTH with a different descriptor value; `:removed` = in OLD not NEW.
  Order-stable (sorted by kind then id) so the diff is a deterministic value.
  INTERNAL."
  [old-regs new-regs]
  (let [old-keys (set (for [[k m] old-regs [id _] m] [k id]))
        new-keys (set (for [[k m] new-regs [id _] m] [k id]))
        added    (sort (set/difference new-keys old-keys))
        removed  (sort (set/difference old-keys new-keys))
        changed  (sort (for [[k id] (set/intersection old-keys new-keys)
                             :when (not= (get-in old-regs [k id])
                                         (get-in new-regs [k id]))]
                         [k id]))]
    {:added   (vec added)
     :changed (vec changed)
     :removed (vec removed)}))

(defn- refuse-live-frame-removal!
  "The ONE reachable live-instance edge in the descriptor-only slice
  (EP-0013 issue 12, PRECISED — see `reinstall!`'s docstring). Of the
  step-7 wired kinds, `:frame` is the only one that IS a live instance: a
  removed `:event`/`:fx`/`:cofx` has no live runtime instance (it
  fail-loud's on future use per EP-0013 §Hot Reload And Reinstall —
  \"removed registrations fail loudly on future use\"), a removed `:sub`'s
  disposal/loud-read is pre-existing per-kind hot-reload behaviour, and
  the step-8-deferred kinds are refused at the KIND BOUNDARY in BOTH
  directions — they throw `:rf.error/unsupported-descriptor-kind` on
  add/changed (`install-descriptor!`) AND on removal
  (`refuse-unsupported-removal!`, which runs just before this check), so no
  live instance of those classes is seated OR silently orphaned through the
  descriptor path. (ERRATA rf2-cquy9u: pre-fix this held only on the
  register path; the removal path unregistered unconditionally.) `:frame` —
  a WIRED kind that IS a live instance — is the exception this check owns: a
  removed `:frame` whose live container
  still exists would be ORPHANED — `registrar/unregister! :frame id` drops
  only the registrar SLOT, leaving the container live in the core frame
  registry (`@frame/frames`), still dispatchable/subscribable, with its
  `:on-destroy`, the machine teardown cascade, and sub-cache disposal that
  `destroy-frame!` runs all SKIPPED. That silent orphan is exactly what
  disposition 12 closed; refuse-loudly is the per-kind rule here.

  This is a TARGETED check against the core frame registry only (the
  `:realm/frames-by-realm` late-bind hook `re-frame.frame` publishes —
  realm-id → live, non-destroyed frame-ids), NOT the cross-subsystem
  blocker query the original disposition imagined. It throws BEFORE any
  mutation so a refused reinstall leaves the realm untouched. The
  diagnostic enumerates exactly the live frame-ids that block. The
  recovery is explicit `destroy-frame!` then reinstall — orphaning a live
  frame is never the framework's silent default. INTERNAL."
  [rid diff]
  (let [removed-frame-ids (->> (:removed diff)
                               (filter (fn [[kind _]] (= :frame kind)))
                               (map second)
                               set)]
    (when (seq removed-frame-ids)
      (let [frames-by-realm (when-let [f (late-bind/get-fn :realm/frames-by-realm)]
                              (f))
            live-in-realm   (get frames-by-realm rid #{})
            blocking        (set/intersection removed-frame-ids live-in-realm)]
        (when (seq blocking)
          (throw (ex-info (str "rf/reinstall!: cannot remove the live frame(s) "
                               (pr-str (sort blocking)) " from realm " rid
                               " — a :frame descriptor still backs a live frame"
                               " container. Removing it through the descriptor"
                               " diff would orphan the container (its :on-destroy,"
                               " machine teardown, and sub-cache disposal would be"
                               " skipped). Destroy the frame explicitly"
                               " (re-frame.frame/destroy-frame!) before reinstalling"
                               " without it.")
                          {:error/id       :rf.error/live-frame-removal-unsupported
                           :realm          rid
                           :live-frames    (vec (sort blocking))
                           :recovery       :destroy-frame-then-reinstall})))))))

(defn reinstall!
  "Hot-reload a realm by replacing its installed app VALUE with `new-app` —
  diff the new app value against the installed one and apply the delta
  (EP-0013 D2 stage 7, §Hot Reload And Reinstall). PUBLIC (`rf/reinstall!`).

  Unlike `install!` (which seats a program into a realm that has none),
  `reinstall!` REPLACES the realm's current program with a minimal delta:

    * `:added`   `[kind id]` present in `new-app` but not the installed app →
                 `register!`ed;
    * `:changed` `[kind id]` in both with a DIFFERENT descriptor → re-registered
                 (the registrar's hot-reload replacement path fires its
                 hooks + `:rf.registry/handler-replaced` trace, so changed
                 subscriptions invalidate their caches via the existing
                 replacement-hook surface);
    * `:removed` `[kind id]` in the installed app but not `new-app` →
                 `unregister!`ed (a removed registration fails loudly on future
                 lookup, per the §Hot Reload rules).

  Then records `new-app` in the realm's `:app` slot and returns the DIFF — the
  `{:realm :reason :added :changed :removed}` value the EP's example shows
  (`:reason` echoes `opts`' `:reason`, defaulting to `:hot-reload`), so a dev
  tool can show what a save changed.

  Re-running the capability check on the new app (its `:rf.app/requires` must still be
  satisfiable) preserves the install invariant across the reload — a reinstall
  that adds an unmet capability requirement THROWS `:rf.error/missing-capability`
  before any mutation, exactly as `install!` would.

  Descriptor-only slice (EP-0013 issue 12, PRECISED): the diff is over
  registration descriptors. Refuse-loudly binds at the KIND BOUNDARY in BOTH
  directions — the step-8-deferred kinds (`:route`/`:flow`/`:resource`/
  `:mutation`/`:resource-scope`/…) THROW `:rf.error/unsupported-descriptor-kind`
  on the ADD/CHANGED path (`register-descriptor!` → `install-descriptor!`) AND
  on the REMOVAL path (`refuse-unsupported-removal!`, rf2-cquy9u). A deferred
  kind is neither app-value-installable nor app-value-removable in this slice:
  the descriptor diff does not own it in either direction — it stays owned by
  its own `reg-*`/`clear-*` sugar lifecycle. So the live-instance classes the
  disposition worried about (machine actors, in-flight resources/mutations,
  route transitions) cannot be seated OR silently orphaned through the diff; the
  live-instance blocker/continue/migrate rule binds PER-KIND when each deferred
  kind becomes installable (defining that rule is a precondition of lifting the
  kind throw). The per-kind hot-reload rules each subsystem already honours
  (active machine instances continue with their captured spec; changed subs
  invalidate caches; removed registrations fail loudly on future use) are
  unchanged. (ERRATA rf2-cquy9u: the earlier \"structurally unreachable through
  the descriptor diff\" claim was true only on the REGISTER path; the removal
  path unregistered every removed `[kind id]` unconditionally, so a
  sugar-registered step-8 id omitted from a reinstall was silently
  unregistered. `refuse-unsupported-removal!` restores the claim's truth on the
  removal path.)

  The ONE reachable WIRED live-instance edge is `:frame` REMOVAL — `:frame` is the
  only wired kind that IS a live instance. A `:removed` `:frame` whose live
  container still exists is REFUSED loudly (`refuse-live-frame-removal!` →
  `:rf.error/live-frame-removal-unsupported`, enumerating the blocking
  frame-ids) BEFORE any mutation, rather than silently orphaning the container
  (which `registrar/unregister! :frame id` alone would, skipping the
  `destroy-frame!` teardown). The check reads only the core frame registry (the
  `:realm/frames-by-realm` hook), not cross-subsystem machinery. The recovery is
  explicit `destroy-frame!` then reinstall.

  Arities (the realm is the LEADING positional when present, matching
  `install!` + the EP's `(reinstall! shop-realm new-app {:reason …})` examples):
    `(reinstall! new-app)`             — default realm, no opts;
    `(reinstall! realm new-app)`       — explicit realm, no opts;
    `(reinstall! realm new-app opts)`  — explicit realm + `opts` (`:reason`).
  To pass `opts` against the default realm, name it explicitly:
  `(reinstall! re-frame.realm/default-realm-id new-app opts)`."
  ([new-app] (reinstall! realm/default-realm-id new-app nil))
  ([realm-or-id new-app] (reinstall! realm-or-id new-app nil))
  ([realm-or-id new-app opts]
   ;; Resolve + validate the target realm FIRST (rf2-c6armm.1/.2): a reinstall!
   ;; against an explicit unknown id throws `:rf.error/unknown-realm` before any
   ;; mutation, symmetric with install!. nil/default sugar is preserved.
   (let [rid     (resolve-target-realm! realm-or-id)
         ;; The app to diff against — the STORED `:app` a prior install!/reinstall!
         ;; seated, else (no prior install) the recomputable projection of the
         ;; realm's registrar, so the FIRST reinstall after a pure-sugar boot
         ;; still diffs against the live program. Diffing against the STORED app
         ;; (not `realm/installed-app`, which now reconciles the stored app WITH
         ;; the live projection, rf2-77ewnm) scopes replacement to the prior
         ;; install — coexisting `reg-*` sugar (including the framework's
         ;; boot-seeded deferred-kind registrations) is NEVER swept into
         ;; `:removed`, exactly as install!'s replacement step preserves it
         ;; (rf2-c6armm.7 #1 / AC3). Reconciling here instead would land every
         ;; coexisting sugar registration in `:removed` on a reinstall that does
         ;; not re-list it.
         old-app (or (stored-installed-app rid)
                     (realm/installed-app rid))
         diff    (diff-registrations (:registrations old-app)
                                     (:registrations new-app))]
     ;; The capability invariant holds across reloads too — fail loud before
     ;; any mutation if the new app raises a requirement the realm can't meet.
     (check-capabilities! rid new-app)
     ;; Kind-boundary refusal on the REMOVAL path (rf2-cquy9u), symmetric with
     ;; the add/changed path's throw (`register-descriptor!` →
     ;; `install-descriptor!`): a `:removed` step-8-DEFERRED kind THROWS
     ;; `:rf.error/unsupported-descriptor-kind` here — before any mutation —
     ;; rather than silently `unregister!`-ing a sugar-registered slot (which
     ;; would orphan its live instances, skipping the subsystem teardown). The
     ;; deferred-kind set lives in core; this leaf ns refuses through the
     ;; `:app-value/refuse-unsupported-removal!` hook (mirroring the install
     ;; hook), no-op when the hook is unbound.
     (when-let [refuse (late-bind/get-fn :app-value/refuse-unsupported-removal!)]
       (refuse (:removed diff)))
     ;; The ONE reachable WIRED live-instance edge (EP-0013 issue 12, PRECISED):
     ;; a `:removed` `:frame` that still backs a live container is refused loudly
     ;; here — before any mutation — rather than silently orphaned. Targeted
     ;; check against the core frame registry only; no cross-subsystem machinery.
     (refuse-live-frame-removal! rid diff)
     ;; Refuse :frame descriptors ADDED or CHANGED into a non-default realm
     ;; (rf2-c6armm.1), symmetric with install!'s `refuse-non-default-realm-frames!`:
     ;; the realm-aware frame path is a later EP-0013 slice, so a `:frame` seated
     ;; into an explicit realm would be stamped default + collide globally. Throw
     ;; before any mutation. (`:removed` :frame is already handled above.)
     (when-not (= rid realm/default-realm-id)
       (when-let [fids (seq (->> (concat (:added diff) (:changed diff))
                                 (filter (fn [[kind _]] (= :frame kind)))
                                 (map second)))]
         (throw (ex-info (str "rf/reinstall!: cannot seat :frame descriptor(s) "
                              (pr-str (vec (sort fids))) " into the non-default realm "
                              rid " — realm-aware frame registration is a later"
                              " EP-0013 slice. Reinstall :frame descriptors against"
                              " the default realm.")
                         {:error/id  :rf.error/realm-frames-unsupported
                          :realm     rid
                          :frame-ids (vec (sort fids))
                          :recovery  :install-frames-into-the-default-realm}))))
     ;; Apply the delta against the realm's OWN registrar (EP-0013 stage 9):
     ;; added + changed re-derive the registrar slot from the new descriptor
     ;; (lowered through its kind's real registration logic, so a reloaded
     ;; handler is dispatch-ready + the registrar's hot-reload replacement hooks
     ;; fire); removed unregister (future lookups fail loudly). The seating
     ;; binds `*registrar*` to the realm's atom, so a reinstall hot-reloads only
     ;; THAT realm's program — the default-realm path is byte-identical.
     (seat-into-realm! rid
       (fn []
         (doseq [[kind id] (concat (:added diff) (:changed diff))]
           (register-descriptor! kind id (get-in new-app [:registrations kind id])))
         (doseq [[kind id] (:removed diff)]
           (registrar/unregister! kind id))))
     (realm/set-installed-app! rid new-app)
     (assoc diff
            :realm  rid
            :reason (get opts :reason :hot-reload)))))

;; ---- the realm-side installed-app seam (the late-bind bridge) --------------
;;
;; The realm's `:app` slot (Spec-Schemas §`:rf/realm`, D2-reserved) is the
;; installed app VALUE. `re-frame.realm/installed-app` is the realm-side read
;; seam over that slot. `re-frame.realm` requires NOTHING from this ns (this ns
;; requires it, projecting over its registrar), so `installed-app` reaches this
;; ns through the late-bind hooks published below — a static back-require would
;; cycle. Both hooks are published once at ns-load and never withdrawn
;; (drift-tested via `re-frame.late-bind.directory`).
;;
;; THE SOURCE-OF-TRUTH INVARIANT (rf2-77ewnm). EP-0013 makes the realm's
;; REGISTRAR the single source of truth and reinterprets `reg-*` as default-realm
;; SUGAR — a sugar call updates the realm's installed app value in place
;; (EP-0013:138, :838). So `installed-app` MUST reflect every live registration,
;; sugar OR installed, in BOTH the pure-projection case and the mixed case where
;; `reg-*` sugar coexists with an `install!`-seated `:app`. A naive "return the
;; stored `:app` if present" read breaks this: an `install!`-seated snapshot is
;; frozen at install time, so coexisting sugar (registered before or after the
;; install — which `install!` deliberately preserves, rf2-c6armm.7 #1) would be
;; live in the registrar and visible to `app-value` / dispatch yet INVISIBLE to
;; the public `rf/installed-app` read. That desync makes the public read seam
;; ambiguous for Xray/tooling and weakens the source-of-truth claim.
;;
;; The contract (rf2-77ewnm AC2, option a): `rf/installed-app` is the LIVE
;; registrar projection ENRICHED with the seated app's module provenance. Two
;; cases:
;;
;;   - NO stored `:app` (pure sugar / load-order, or a fresh realm) — the
;;     recomputable projection over the registrar, module-less (`:project`
;;     hook). The honest no-provenance case; unchanged.
;;   - A stored `:app` (an `install!`-seated app value) — the SAME live
;;     projection, with the seated app's `:rf.app/id`, `:modules`, and
;;     `:rf.app/requires` overlaid (`:reconcile-installed` hook). The registrations are
;;     always the live registrar's (so coexisting sugar is visible and the read
;;     can never desync from `app-value` / dispatch); the rich install-time
;;     provenance the Xray Module-view feeds to `app-registrations` / `app-owns`
;;     / `app-requires` is preserved.
;;
;; A sugar registration that COLLIDES with an installed `(kind, id)` cannot occur
;; (the registrar holds one entry per `(kind, id)`); the projected descriptor for
;; an installed id carries the same `:owner` the seated app stamped, since
;; `install!` lowers `:owner` into the registrar metadata. So a reconciled
;; registration's `:owner` is correct whether it originated from sugar (no
;; `:owner`) or from a module (its `:owner`), and `app-owns` resolves through the
;; overlaid `:modules` exactly as it did for a pure install.

(defn reconcile-installed-app
  "Reconcile a realm's STORED `:app` (an `install!`-seated app value) with the
  LIVE projection over its registrar, returning the value `re-frame.realm/
  installed-app` exposes when a stored app exists (rf2-77ewnm). INTERNAL.

  The result is the live projection (so it reflects coexisting `reg-*` sugar
  and can never desync from `app-value` / dispatch) carrying the seated app's
  rich provenance overlaid:

    {:rf.app/id       <stored app id>           ;; the seated app's identity
     :registrations   {kind {id descriptor}}    ;; the LIVE registrar (sugar +
                                                 ;; installed), owner-stamped
     :rf.app/requires <stored app :rf.app/requires> ;; the seated app's
                                                 ;; capability surface (module-declared)
     :modules         <stored app :modules>}    ;; per-module provenance — so
                                                 ;; app-owns / app-requires read

  `:rf.app/requires` and `:modules` come from the seated VALUE (load-order sugar
  declares neither), so the Xray Module-view's per-module facts survive while
  the enumerable registration view stays the live source of truth. Pure given
  the registrar snapshot the projection reads."
  [realm-id stored-app]
  (-> (app-value realm-id)
      (assoc :rf.app/id       (:rf.app/id stored-app)
             :rf.app/requires (get stored-app :rf.app/requires #{}))
      (cond-> (contains? stored-app :modules)
        (assoc :modules (:modules stored-app)))))

(late-bind/set-fn! :app-value/project app-value)
(late-bind/set-fn! :app-value/reconcile-installed reconcile-installed-app)
