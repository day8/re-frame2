(ns boot.schema
  "Malli schemas for the boot example.

   The example demonstrates Pattern-Boot: a single boot state machine
   owns the initialisation graph. Three parallel sub-fetches (config,
   feature flags, initial user) run via `:spawn-all`; the boot
   machine reaches `:ready` only when every child reports done. The
   schemas describe the wire shape returned by each mocked endpoint,
   the boot-machine snapshot itself, and the child loader's `:data`.

   Two attachment mechanisms appear here, picked by how the runtime
   addresses each snapshot:

   - The singleton `:app/boot` machine, the `[:boot/staging]` slot, and
     the four top-level slices live at fixed app-db paths, so they are
     attached with `rf/reg-app-schema` on those paths.
   - The `:boot/loader` child is spawned (once per asset) and gets a
     gensym'd id (e.g. `:boot/loader#0`), so its snapshot lives at an
     unknown per-instance path no fixed `reg-app-schema` can reach.
     `LoaderData` is therefore attached via the child machine's
     `:schema` slot on `reg-machine` (see `boot.cljs`), which validates
     each spawned instance's initial `:data` at spawn time, before the
     snapshot installs (Spec 005 §Schema validation §Spawn-time
     validation)."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves at the call sites below.
            [re-frame.schemas]
            ;; The CLJS default validator routes through the
            ;; late-bind hook `:schemas/malli-validate`, which the
            ;; `re-frame.schemas.malli` adapter ns publishes at load
            ;; time. The require is the canonical CLJS opt-in for
            ;; Malli validation — without it, the default validator
            ;; soft-passes per Spec 010 §Recommended soft-pass and
            ;; no failure trace fires for malformed app-db slices.
            [re-frame.schemas.malli]))

;; ============================================================================
;; WIRE SHAPES — what the mocked endpoints return
;; ============================================================================

(def Config
  "Static app configuration. In a real app this would arrive from a
   build-time /config endpoint; here the demo stub synthesises a
   plausible payload."
  [:map
   [:api-base    :string]
   [:env         [:enum :dev :staging :prod]]
   [:build       :string]
   [:title       :string]])

(def Flags
  "Feature flags. The map is open — apps add their own keys; the
   schema only fixes the well-known ones."
  [:map
   [:dark-mode?       :boolean]
   [:beta-channel?    :boolean]
   [:onboarding-skip? :boolean]])

(def User
  "Initial user record. In a real app this would arrive from /user
   after the session token is restored. Here the demo stub returns
   a static demo user."
  [:map
   [:id       :string]
   [:username :string]
   [:email    :string]])

(def Routes
  "Application route table. In a real app this might be hard-coded;
   here we fetch it so the boot graph has four parallel dependencies
   to demonstrate `:spawn-all`."
  [:vector
   [:map
    [:id   :keyword]
    [:path :string]]])

;; ============================================================================
;; BOOT-MACHINE SNAPSHOT
;; ============================================================================
;;
;; The boot machine lives at [:rf/runtime :machines :snapshots :app/boot]. Its `:state`
;; cycles `:configuring → :loading-deps → :hydrating → :ready`
;; (terminal), with `:failed` (terminal) reached if any child errors.
;; `:data` carries the per-phase progress slot and the loaded payloads.

(def BootSnapshot
  [:map
   [:state [:enum :configuring :loading-deps :hydrating :ready :failed]]
   [:data  [:map
            [:phase  [:maybe :keyword]]
            [:config [:maybe Config]]
            [:flags  [:maybe Flags]]
            [:user   [:maybe User]]
            [:routes [:maybe Routes]]
            [:error  [:maybe :any]]]]])

;; ============================================================================
;; CHILD LOADER :data SHAPE — :boot/loader (one instance per asset)
;; ============================================================================
;;
;; The `:data` slot of the generic `:boot/loader` child machine. Each
;; loader holds the parent-id + child-id + staging-key + URL it was
;; spawned with, fetches once, and dispatches `:boot/asset-loaded` (or
;; `:boot/asset-failed`) back to its parent on transition into `:done`
;; (or `:failed`). Attached via the child machine's `:schema` slot on
;; `(reg-machine :boot/loader ...)` in `boot.cljs` — the runtime owns
;; the surrounding snapshot, so the machine `:schema` describes `:data`
;; only (Spec 005 §Schema validation) and validates each spawned
;; instance's initial `:data` at spawn time, regardless of its gensym'd
;; id. The per-child `:data` fn (see :app/boot) plants identity only, so
;; the fetch-result fields below are optional — they appear later, as
;; the loader transitions through `:loading` into `:done` / `:failed`.

(def LoaderData
  [:map
   ;; Identity, planted by the parent's per-child spawn-spec `:data` fn —
   ;; present from spawn (see :app/boot in boot.cljs).
   [:parent-id   :keyword]
   [:child-id    :keyword]
   [:staging-key :keyword]
   [:url         :string]
   ;; Filled in only once the fetch replies (the `:asset/replied` action
   ;; writes :payload or :error). Absent at spawn — the per-child `:data`
   ;; fn plants identity only — so both are optional.
   [:payload     {:optional true} :any]
   [:error       {:optional true} [:maybe :any]]
   ;; Runtime-stamped address keys — the spawn fx stamps these into the
   ;; spawned actor's initial :data (Spec 005 §Reserved snapshot-internal
   ;; keys). Optional + declared so the schema documents them; a Malli
   ;; :map is open, so they'd pass undeclared too.
   [:rf/self-id   {:optional true} :any]
   [:rf/parent-id {:optional true} :any]
   [:rf/spawn-id  {:optional true} :any]])

(def BootStagingSlice
  "Shape of `[:boot/staging]` — the per-child hand-off slot the
   `:spawn-all` children write into and the parent's
   `:enter-hydrating` action reads from. Each key is optional because
   the slot is filled incrementally as children complete; once the
   join resolves all four keys carry their per-child payloads."
  [:map
   [:config {:optional true} [:maybe Config]]
   [:flags  {:optional true} [:maybe Flags]]
   [:user   {:optional true} [:maybe User]]
   [:routes {:optional true} [:maybe Routes]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================

;; The runtime rolls back post-commit on a failing app-db schema. The
;; slots below are absent (nil) before the boot machine writes them;
;; every registration is wrapped in :maybe so the validator passes
;; during the staging/loading phases.

(rf/reg-app-schema [:rf/runtime :machines :snapshots :app/boot] [:maybe BootSnapshot])

;; The :spawn-all children stage their payloads into [:boot/staging]
;; before signalling completion to the parent. The :enter-hydrating
;; action reads the staging slot and promotes each payload into the
;; canonical top-level slot below. Registered here so the staging
;; writes are schema-validated like every other slice.
(rf/reg-app-schema [:boot/staging] [:maybe BootStagingSlice])

;; The boot machine writes its final payloads into top-level app-db
;; slices on entering `:hydrating`. These are the slices the main app
;; reads via subs once the boot reaches `:ready`.
(rf/reg-app-schema [:config] [:maybe Config])
(rf/reg-app-schema [:flags]  [:maybe Flags])
(rf/reg-app-schema [:user]   [:maybe User])
(rf/reg-app-schema [:routes] [:maybe Routes])
