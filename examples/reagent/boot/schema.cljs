(ns boot.schema
  "Malli schemas for the boot example. See schemas in the guide
   (docs/guide/glossary.md#schema).

   The schemas describe three things: the wire shape each mocked
   endpoint returns, the boot-machine snapshot's `:data`, and the child
   loader's `:data`.

   Two kinds of schema surface appear, picked by what each one validates:

   - **App-db slices** — the `[:boot/staging]` slot and the four
     top-level slices (`[:config]`, `[:flags]`, `[:user]`, `[:routes]`)
     live at fixed app-db paths, so they attach with `rf/reg-app-schema`
     on those paths. App schemas validate the app-db partition only.
   - **Machine `:data`** — both the `:app/boot` machine and the spawned
     `:boot/loader` child declare a `[:schemas :data]` slot on
     `reg-machine` (see `boot.cljs`) that validates the snapshot's `:data`.
     `BootData` describes the `:app/boot` machine's `:data`; `LoaderData`
     describes each spawned loader's `:data`, validated at spawn time. A
     spawned loader gets a generated id like `:boot/loader#0`, so its
     snapshot sits at a per-instance path no fixed `reg-app-schema` could
     reach. Machine snapshots live in runtime-db, not app-db, so
     `reg-app-schema` is not the surface for them
     (docs/guide/glossary.md#runtime-db)."
  (:require [re-frame.core :as rf]
            ;; Schemas ship in a separate artefact. The require registers
            ;; `rf/reg-app-schema`.
            [re-frame.schemas]
            ;; The Malli adapter ns publishes the default validator. The
            ;; require is the CLJS opt-in for Malli validation — without it
            ;; the default validator soft-passes and no failure trace fires
            ;; for malformed app-db slices.
            [re-frame.schemas.malli])
  (:require-macros [re-frame.core :refer [with-frame]]))

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
;; BOOT-MACHINE :data SHAPE — :app/boot
;; ============================================================================
;;
;; The boot machine's snapshot lives in runtime-db. Its `:state` cycles
;; `:configuring → :loading-deps → :hydrating → :ready` (terminal), with
;; `:failed` (terminal) reached if any child errors. `:data` carries the
;; per-phase progress slot and the loaded payloads.
;;
;; `BootData` describes the `:data` slot only, not the whole
;; `{:state … :data …}` snapshot. It attaches via the `:app/boot` machine's
;; `[:schemas :data]` slot on `reg-machine` (see `boot.cljs`). Every payload
;; slot is `:maybe` because they are nil through the staging/loading phases
;; and only fill on entering `:hydrating`.

(def BootData
  [:map
   [:phase  [:maybe :keyword]]
   [:config [:maybe Config]]
   [:flags  [:maybe Flags]]
   [:user   [:maybe User]]
   [:routes [:maybe Routes]]
   [:error  [:maybe :any]]])

;; ============================================================================
;; CHILD LOADER :data SHAPE — :boot/loader (one instance per asset)
;; ============================================================================
;;
;; The `:data` slot of the `:boot/loader` child machine. Each loader holds
;; the parent-id + child-id + staging-key + URL it was spawned with, fetches
;; once, and dispatches `:boot/asset-loaded` (or `:boot/asset-failed`) back
;; to its parent on transition into `:done` (or `:failed`). Attached via the
;; child machine's `[:schemas :data]` slot on `(reg-machine :boot/loader ...)`
;; in `boot.cljs`; it describes `:data` only and validates each spawned
;; instance's initial `:data` at spawn time. The per-child `:data` fn (see
;; :app/boot) plants identity only, so the fetch-result fields below are
;; optional — they appear later, as the loader moves through `:loading` into
;; `:done` / `:failed`.

(def LoaderData
  [:map
   ;; Identity, planted by the parent's per-child `:data` fn — present from
   ;; spawn (see :app/boot in boot.cljs).
   [:parent-id   :keyword]
   [:child-id    :keyword]
   [:staging-key :keyword]
   [:url         :string]
   ;; Filled in only once the fetch replies (the `:asset/replied` action
   ;; writes :payload or :error). Absent at spawn, so both are optional.
   [:payload     {:optional true} :any]
   [:error       {:optional true} [:maybe :any]]
   ;; Address keys the spawn fx stamps into the spawned child's initial
   ;; :data. Declared optional so the schema documents them; a Malli :map is
   ;; open, so they'd pass undeclared too.
   [:rf/self-id   {:optional true} :any]
   [:rf/parent-id {:optional true} :any]
   [:rf/invoke-id {:optional true} :any]])

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

;; The runtime rolls back post-commit on a failing app-db schema. The slots
;; below are nil before the boot machine writes them, so every registration
;; is wrapped in :maybe to pass during the staging/loading phases.

;; Machine snapshots are runtime-db state, not app-db, so a `reg-app-schema`
;; on a machine-snapshot path would validate nothing. The `:app/boot`
;; machine's own `:schemas {:data schema/BootData}` (on `reg-machine` in
;; boot.cljs) is its snapshot-`:data` surface. The `reg-app-schema` calls
;; below validate only the app-db slices.

;; `reg-app-schema` is frame-local: a bare ns-load call under no scope
;; raises `:rf.error/no-frame-context`. This example's app runs in the
;; `:rf/default` frame (see `core/run`), so name the frame explicitly here
;; via `with-frame` — the registrations carry a frame stamp even though they
;; land at module-load before `init!`.
(with-frame :rf/default
  ;; The :spawn-all children stage their payloads into [:boot/staging]
  ;; before signalling completion. The :enter-hydrating action reads the
  ;; staging slot and promotes each payload into the top-level slot below.
  ;; Registered here so the staging writes are schema-validated like every
  ;; other slice.
  (rf/reg-app-schema [:boot/staging] {:schema [:maybe BootStagingSlice]})

  ;; The boot machine writes its final payloads into top-level app-db slices
  ;; on entering `:hydrating`. These are the slices the main app reads via
  ;; subs once the boot reaches `:ready`.
  (rf/reg-app-schema [:config] {:schema [:maybe Config]})
  (rf/reg-app-schema [:flags]  {:schema [:maybe Flags]})
  (rf/reg-app-schema [:user]   {:schema [:maybe User]})
  (rf/reg-app-schema [:routes] {:schema [:maybe Routes]}))
