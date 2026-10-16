(ns realworld-resources.schema
  "App-db + machine Malli schemas for the RealWorld-on-resources (Conduit)
   example.

   The WIRE shapes — every payload the RealWorld API returns and the seven
   response envelopes — are the transport-neutral contract shared by both
   RealWorld examples, so they live in `realworld-shared.schema` (imported as
   `ws` below). In this architecture the wire shapes pull double duty: they're
   the resource `:data-schema` values AND the `:decode` schemas inside each
   resource / mutation `:request`, consumed directly from the shared ns at those
   sites. See schema: ../../../docs/core/glossary.md#schema.

   What stays HERE is what's genuinely local: the handful of app-db schemas this
   architecture still owns. Notice how few there are — there are no app-db slice
   schemas for any of the reads, because the cached server-state lives in the
   framework-owned runtime partition (`:rf.runtime/resources`), not in app-db.
   All app-db actually holds here is the small auth slice and the per-form
   drafts.

   The RealWorld API spec is documented at:
     https://github.com/gothinkster/realworld/tree/main/api"
  (:require [re-frame.core :as rf]
            ;; The shared wire contract — User/Profile/Article/Comment + the
            ;; seven response envelopes. The auth slice below embeds the
            ;; token-free `ws/SessionUser` (the durable half of the wire User).
            [realworld-shared.schema :as ws]
            ;; The schemas runtime. Loading it registers the hooks, so
            ;; rf/reg-app-schemas has something to resolve at the call site below.
            [re-frame.schemas]))

;; ============================================================================
;; APP-DB SLICES — only what app-db still owns
;; ============================================================================
;;
;; With the cached reads living in runtime-db, app-db is left with very little to
;; validate: just the auth slice and the two auth form drafts. (The settings form
;; is a mutation instance plus a small draft slice; the auth machine snapshot
;; lives in runtime-db and is validated by its own `:data-schema`, not an
;; app-schema.)

(def AuthSlice
  "The auth slice. :user holds the durable, token-free session user
   (`ws/SessionUser` — the wire User minus its credential, since
   `:auth/store-session` `dissoc`s the token before storing); :token is the JWT
   (or nil). Validating :user against the wire `ws/User` here would demand the
   very :token the durable copy deliberately drops, and post-commit validation
   would roll the login back. The resource cache scope isn't stored here — it's
   derived from :user's :username by the named session resolver (see
   realworld-resources.scope). :return-to is the optional post-login bounce-back
   target routing.cljs's `:rf.route/entry-denied` handler stashes: the denial
   payload's `:destination`, a `:rf/route-destination` and a valid
   `:rf.route/navigate` request in its own right, so the bounce-back returns to the
   exact URL (path, params, query, and #fragment), not just the bare route.
   `:auth/post-login-redirect` and `:auth/settle-deferred-entry` read and clear it
   (auth.cljs)."
  [:map
   [:user      [:maybe ws/SessionUser]]
   [:token     [:maybe :string]]
   ;; Mirror `:rf/route-destination` (spec/Spec-Schemas.md) EXACTLY — it is what
   ;; lands here, verbatim, off the `:rf.route/entry-denied` payload. The address
   ;; branch is MINIMAL (`{:to id}` plus only the non-empty of params / query /
   ;; fragment), so demanding all four keys would make a bare `/settings` denial
   ;; fail validation and roll the stash back; and the raw `{:url …}` escape has
   ;; to be storable too. The twin in realworld_http/schema.cljs is identical.
   [:return-to {:optional true}
    [:or
     [:map {:closed true}
      [:to       :keyword]
      [:params   {:optional true} :map]
      [:query    {:optional true} :map]
      [:fragment {:optional true} [:maybe :string]]]
     [:map {:closed true}
      [:url      :string]
      [:fragment {:optional true} [:maybe :string]]]]]])

(def FormSlice
  "The standard form-draft slice, shared by the login / register / settings
   drafts in app-db. The submission lifecycle isn't here — that's a mutation
   instance (`:rf/mutation`) — so this slice carries only the editable draft
   plus a little touched-field bookkeeping.

   Session ownership is deliberately NOT here. This slice is page state, rebuilt
   from scratch on every route entry by whoever is signed in at the time, so a
   `:session-owner` recorded on it is overwritten by the next visitor to open
   the page — which is exactly the account-switch hole `:settings/replied` exists
   to close. The settings save records its owner at `[:settings-save-owner]`
   instead; settings.cljs carries the full why."
  [:map
   [:draft   :map]
   [:touched [:set :keyword]]
   [:submit-attempted? {:optional true} :boolean]])

(def AuthFlowData
  "The :data slot of the :auth/flow machine snapshot."
  [:map [:error [:maybe :string]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; A path schema checks a slice only where the candidate app-db has it (Spec 010
;; §When schemas are checked), so these schemas are bare:
;;
;;   - `[:auth]`, `[:auth :login-form]` and `[:auth :register-form]` are each
;;     seeded by their OWN event: `:auth/initialise` is its own
;;     `:initial-events` step (it restores the saved session first), and the two
;;     form drafts are separate dispatches fanned out from `:app/initialise`
;;     (core.cljs). A seed is never checked against a sibling it has not
;;     written yet.
;;
;;   - `[:settings-form]` is absent by design: nothing seeds it at boot and
;;     nothing should. `:settings/load` seeds the draft FROM the authenticated
;;     user on settings-route entry (settings.cljs), and that route is
;;     auth-guarded — so for an anonymous visitor, and for a signed-in one who
;;     never opens Settings, the path is absent for the whole life of the app.
;;     Seeding it at boot would only manufacture an empty draft from a user who
;;     isn't there yet, to be overwritten on entry.
;;
;; A schema is a program registration, like an event, so this runs at ns-load
;; with no frame context; the frame the `frame-root {:id …}` form in core.cljs
;; creates at the render root resolves it. It is not a frame creation or seed.

(def app-db-schemas
  "This app's app-db schema registry as a `{path -> schema}` VALUE, registered
   once below. A harness can name the set as data — to register it from its own
   namespace, or to read a slice's schema."
  {[:auth]                 AuthSlice
   [:auth :login-form]     FormSlice
   [:auth :register-form]  FormSlice
   [:settings-form]        FormSlice
   ;; Present only while a settings save is on the wire: the username it was
   ;; issued as, written by `:settings/submit` and retired by
   ;; `:settings/replied`. `:maybe` because `:settings/submit` writes
   ;; `(auth/session-owner db)`, which is nil when nobody is signed in — the
   ;; answer `owns-session?` refuses.
   [:settings-save-owner]  [:maybe :string]})

(rf/reg-app-schemas app-db-schemas)
