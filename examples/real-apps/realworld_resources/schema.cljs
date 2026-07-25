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
   ;; fragment), so demanding all four keys made a bare `/settings` denial fail
   ;; validation and roll the stash back; and the raw `{:url …}` escape has to be
   ;; storable too (rf2-k85nd). The twin in realworld_http/schema.cljs is identical.
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
   plus a little touched-field bookkeeping."
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
;; reg-app-schemas is frame-local, so a bare ns-load call with no frame context
;; would fail. This app runs in `:rf/default` (the `frame-root {:id …}` form
;; in core.cljs creates it at the render root), so we name that id here. Worth
;; being precise about what this is: a frame-local registration scoped by id, not
;; a frame creation or seed. It binds the id at ns-load; the frame itself doesn't
;; exist until the provider first renders.

(rf/with-frame :rf/default
  (rf/reg-app-schemas
    {[:auth]                 AuthSlice
     [:auth :login-form]     FormSlice
     [:auth :register-form]  FormSlice
     [:settings-form]        FormSlice}))
