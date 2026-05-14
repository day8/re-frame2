(ns boot.schema
  "Malli schemas for the boot example.

   The example demonstrates Pattern-Boot: a single boot state machine
   owns the initialisation graph. Three parallel sub-fetches (config,
   feature flags, initial user) run via `:invoke-all`; the boot
   machine reaches `:ready` only when every child reports done. The
   schemas describe the wire shape returned by each mocked endpoint
   and the boot-machine snapshot itself."
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
   to demonstrate `:invoke-all`."
  [:vector
   [:map
    [:id   :keyword]
    [:path :string]]])

;; ============================================================================
;; BOOT-MACHINE SNAPSHOT
;; ============================================================================
;;
;; The boot machine lives at [:rf/machines :app/boot]. Its `:state`
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

(def LoaderSnapshot
  "Snapshot shape for the generic `:boot/loader` child machine.
   Each child loader holds the parent-id + child-id + URL it was
   spawned with, fetches once, and dispatches `:boot/asset-loaded`
   (or `:boot/asset-failed`) back to its parent on transition into
   `:done` (or `:failed`)."
  [:map
   [:state [:enum :idle :loading :done :failed]]
   [:data  [:map
            [:parent-id :keyword]
            [:child-id  :keyword]
            [:url       :string]
            [:payload   :any]
            [:error     [:maybe :any]]]]])

(def BootStagingSlice
  "Shape of `[:boot/staging]` — the per-child hand-off slot the
   `:invoke-all` children write into and the parent's
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

(rf/reg-app-schema [:rf/machines :app/boot] [:maybe BootSnapshot])

;; The :invoke-all children stage their payloads into [:boot/staging]
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
