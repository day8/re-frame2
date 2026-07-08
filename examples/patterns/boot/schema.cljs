(ns boot.schema
  "Malli schemas for the boot example. New to schemas? The guide has the
   tour (docs/core/glossary.md#schema).

   These schemas cover three things: the wire shape each mocked endpoint
   returns, the boot machine's snapshot `:data`, and the child loader's
   `:data`.

   They attach in two different ways, and the difference is just *where
   the thing being validated lives*:

   - **App-db slices** sit at fixed app-db paths — `[:boot/staging]` plus
     the four top-level slices (`[:config]`, `[:flags]`, `[:user]`,
     `[:routes]`). Those attach with `rf/reg-app-schema` on the path; an
     app schema validates the app-db partition only.
   - **Machine `:data`** can't go through `reg-app-schema`, because a
     snapshot lives in runtime-db, not app-db (docs/core/glossary.md#runtime-db).
     Instead, each machine carries its own `[:schemas :data]` slot on
     `reg-machine` (see `boot.cljs`). `BootData` validates the `:app/boot`
     machine's `:data`; `LoaderData` validates each spawned loader's
     `:data` at spawn time. (A spawned loader gets a generated id like
     `:boot/loader#0`, so its snapshot lands at a per-instance path no
     fixed `reg-app-schema` could ever name.)"
  (:require [re-frame.core :as rf]
            ;; Schemas ship in their own artefact; this require registers
            ;; `rf/reg-app-schema`.
            [re-frame.schemas]
            ;; This one turns Malli validation on. The Malli adapter ns
            ;; publishes the default validator, and requiring it is the CLJS
            ;; opt-in — skip it and the validator soft-passes, so a malformed
            ;; slice slides through with no failure trace.
            [re-frame.schemas.malli]))

;; ============================================================================
;; WIRE SHAPES — what the mocked endpoints return
;; ============================================================================

(def Config
  "Static app configuration. In a real app this rides in from a
   build-time /config endpoint; here the demo stub makes up a
   plausible payload."
  [:map
   [:api-base    :string]
   [:env         [:enum :dev :staging :prod]]
   [:build       :string]
   [:title       :string]])

(def Flags
  "Feature flags. The map is open on purpose — apps bring their own
   keys; the schema only pins down the well-known ones."
  [:map
   [:dark-mode?       :boolean]
   [:beta-channel?    :boolean]
   [:onboarding-skip? :boolean]])

(def User
  "The initial user record. In a real app this arrives from /user once
   the session token is restored; here the demo stub just returns a
   fixed demo user."
  [:map
   [:id       :string]
   [:username :string]
   [:email    :string]])

(def Routes
  "The app's route table. A real app might hard-code this — we fetch it
   instead, purely so the boot graph has a fourth dependency and the
   parallel `:spawn-all` step has something to fan out over."
  [:vector
   [:map
    [:id   :keyword]
    [:path :string]]])

;; ============================================================================
;; BOOT-MACHINE :data SHAPE — :app/boot
;; ============================================================================
;;
;; The boot machine's snapshot lives in runtime-db. Its `:state` walks
;; `:configuring → :loading-deps → :hydrating → :ready` (terminal), branching
;; to `:failed` (terminal) if any child errors. `:data` carries the current
;; phase and the loaded payloads.
;;
;; `BootData` describes the `:data` slot only — not the whole
;; `{:state … :data …}` snapshot — and attaches through the machine's
;; `[:schemas :data]` slot on `reg-machine` (see `boot.cljs`). Every payload
;; slot is `:maybe`, because it's nil right up until `:hydrating` fills it in.

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
;; The `:data` of a `:boot/loader` child. Each loader knows the parent-id,
;; child-id, staging-key, and URL it was spawned with, fetches once, and on
;; reaching `:done` (or `:failed`) reports `:boot/asset-loaded` (or
;; `:boot/asset-failed`) back to its parent. It attaches through the child's
;; `[:schemas :data]` slot on `(reg-machine :boot/loader ...)` in `boot.cljs`,
;; describing `:data` only and checking each spawned instance at spawn time.
;; At spawn the parent plants identity and nothing else, so the result fields
;; below are optional — they show up later, as the loader works through
;; `:loading` into `:done` / `:failed`.

(def LoaderData
  [:map
   ;; The identity the parent's `:data` fn plants — there from spawn (see
   ;; :app/boot in boot.cljs).
   [:parent-id   :keyword]
   [:child-id    :keyword]
   [:staging-key :keyword]
   [:url         :string]
   ;; Written only once the fetch replies — the `:asset/replied` action sets
   ;; one of :payload / :error. Nothing at spawn, hence optional.
   [:payload     {:optional true} :any]
   [:error       {:optional true} [:maybe :any]]
   ;; Address keys the spawn fx stamps into the child's initial :data. A Malli
   ;; :map is open, so they'd pass undeclared anyway — we list them just so
   ;; the schema names them out loud.
   [:rf/self-id   {:optional true} :any]
   [:rf/parent-id {:optional true} :any]
   [:rf/invoke-id {:optional true} :any]])

(def BootStagingSlice
  "Shape of `[:boot/staging]` — the hand-off slot the `:spawn-all`
   children write into and the parent's `:enter-hydrating` action reads
   back. Every key is optional because the slot fills in piecemeal, one
   child at a time; by the moment the join resolves, all four are
   present and carrying their payloads."
  [:map
   [:config {:optional true} [:maybe Config]]
   [:flags  {:optional true} [:maybe Flags]]
   [:user   {:optional true} [:maybe User]]
   [:routes {:optional true} [:maybe Routes]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================

;; A failing app-db schema makes the runtime roll the commit back, post-commit.
;; Each slot below starts out nil — the boot machine fills it in later — so
;; every registration wears a `:maybe` to stay valid through the loading phases.
;; (No machine snapshots here: those validate via the machine's own
;; `:schemas {:data ...}` in boot.cljs. A `reg-app-schema` on a snapshot path
;; would guard nothing, since snapshots live in runtime-db, not app-db.)

;; One wrinkle worth knowing: `reg-app-schema` is frame-local, so a bare
;; ns-load call with no frame in scope raises `:rf.error/no-frame-context`.
;; This app lives in the `:rf/default` frame (see `core/run`), so we name that
;; frame explicitly with `with-frame`. The registrations then carry a frame
;; stamp even though they run at module-load, before `init!`.
(rf/with-frame :rf/default
  ;; The :spawn-all children stage their payloads into [:boot/staging] before
  ;; signalling done, and :enter-hydrating reads them back out. We register
  ;; the slot so those staging writes get schema-checked like everything else.
  (rf/reg-app-schema [:boot/staging] [:maybe BootStagingSlice])

  ;; The boot machine promotes its final payloads into these top-level slices
  ;; on entering `:hydrating` — the very slices the main app reads through subs
  ;; once the boot hits `:ready`.
  (rf/reg-app-schema [:config] [:maybe Config])
  (rf/reg-app-schema [:flags]  [:maybe Flags])
  (rf/reg-app-schema [:user]   [:maybe User])
  (rf/reg-app-schema [:routes] [:maybe Routes]))
