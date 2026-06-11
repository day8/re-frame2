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
;;   :requires      the set of `:rf.capability/*` requirements the program
;;                  declares. EMPTY for a projected app (capability
;;                  requirements are declared on MODULE values — the
;;                  default realm's load-order registrations carry none). For
;;                  a constructed app it is the union of its modules'
;;                  `:requires`. The slot is always present.
;;   :modules       (CONSTRUCTED apps only) the module map keyed by module id:
;;                  `module-id → module value`. A projected app carries no
;;                  modules (the load-order registrar has no module grouping),
;;                  so the slot is absent there. An app constructed from zero
;;                  modules carries an empty `:modules` map.
;;
;; A module value (stage 6, `module`) is a composable app-value FRAGMENT — the
;; same descriptor-grouped shape, plus a module id, the `:owns` ownership
;; declarations, and the `:requires` capability set:
;;
;;   :rf.module/id  the module's id (stamped onto each descriptor's :owner).
;;   :registrations the module's own descriptors grouped by kind.
;;   :owns          the ownership declarations (`{:app-db [...] :routes [...]
;;                  :resources [...] ...}`) — not merely documentation;
;;                  composition surfaces them for tooling (overlap validation
;;                  beyond same-(kind,id) collision is a later slice).
;;   :requires      the module's `:rf.capability/*` requirements (empty set
;;                  when none declared).
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

(def ^:private module-source-coord-keys
  "Reserved top-level module keys that are NOT registration sections — lifted
  out before the section scan so a module's own `:id` / `:owns` / `:requires`
  / `:source` are never mistaken for a registration kind."
  #{:id :owns :requires :source})

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

    :id        the module id (required) — stamped onto every descriptor's
               `:owner` so composition can name the colliding source.
    :owns      ownership declarations (`{:app-db [[:cart]] :routes […]
               :resources […] …}`) — the feature surfaces the module owns.
               Carried through to the app value for tooling (overlap
               validation beyond same-(kind,id) collision is a later slice).
    :requires  the set of `:rf.capability/*` requirements (defaults to `#{}`).
    :source    the module's own source-coordinate envelope, when the host
               supplies one (optional — issue 8: absence never changes
               behaviour).
    <sections> registration sections keyed by the plural section name
               (`:events {id entry} :subs {…} :routes {…} …`, per the EP
               module form); each `entry` is a `{:doc … :schema … :handler …
               :source …}` handler-and-metadata map. The section→kind map is
               the Spec 001 registry taxonomy.

  Returns a module value:

    {:rf.module/id  <id>
     :registrations {kind {id descriptor}}  ;; descriptors, :owner-stamped
     :owns          <owns or {}>
     :requires      <requires set>
     :source        <source>}               ;; present only when supplied

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
                (if (contains? module-source-coord-keys section)
                  acc
                  (throw (ex-info (str "rf/module: unknown section key " section)
                                  {:error/id :rf.error/invalid-module
                                   :module id
                                   :unknown-section section
                                   :recovery :remove-or-correct-the-section-key})))))
            {}
            descriptor-map)]
      (cond-> {:rf.module/id  id
               :registrations registrations
               :owns          (get descriptor-map :owns {})
               :requires      (set (get descriptor-map :requires #{}))}
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

    {:rf.app/id     <id>
     :modules       {module-id module}        ;; the composed modules by id
     :registrations {kind {id descriptor}}    ;; every module's descriptors,
                                               ;; collision-checked + owner-stamped
     :requires      #{:rf.capability/* …}}     ;; union of module :requires

  DETERMINISTIC + ORDER-STABLE: the same modules compose to the same app value
  regardless of order, and a same-(kind,id) collision across modules THROWS
  `:rf.error/app-composition-collision` (the ex-data names every colliding
  source) rather than silently picking a winner — there is no last-writer-wins
  (EP-0013 §Composition; issue 7). PURE — no realm, no registrar, no side
  effect; an app value is inert until a stage-7 `install!` seats it.

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
    {:rf.app/id     id
     :modules       (into {} (map (juxt :rf.module/id identity)) modules)
     :registrations (reduce (fn [acc m]
                              (merge-module-registrations acc (:registrations m)))
                            {}
                            modules)
     :requires      (reduce (fn [acc m] (into acc (:requires m)))
                            #{}
                            modules)}))

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
  the union of its modules' `:requires` (EP-0013 §Capability Maps). The
  explicit dependency surface a realm must satisfy before a stage-7 `install!`
  succeeds. PUBLIC (`rf/app-requires`). Pure — returns `#{}` for a projected
  app (load-order registrations declare no requirements)."
  [app-value]
  (get app-value :requires #{}))

(defn app-owns
  "Return the module id that owns app-db `path` in an app value, or `nil` when
  no module declares it (EP-0013 §Module Values And Feature Ownership /
  §Examples `(rf/app-owns app [:cart])`). PUBLIC (`rf/app-owns`).

  Resolves against the modules' `:owns {:app-db [...]}` declarations: returns
  the id of the module whose `:owns :app-db` vector contains exactly `path`.
  Pure; returns `nil` for a projected app (no modules, hence no ownership
  declarations)."
  [app-value path]
  (some (fn [[mid m]]
          (when (some #(= % path) (get-in m [:owns :app-db]))
            mid))
        (:modules app-value)))

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
