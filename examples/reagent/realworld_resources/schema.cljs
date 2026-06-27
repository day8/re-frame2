(ns realworld-resources.schema
  "Malli schemas for the RealWorld-on-resources (Conduit) example.

   Mostly these describe the wire payloads the RealWorld API returns. Notice what
   they DON'T describe: there are no app-db slice schemas for any of the reads.
   That's because the cached server-state lives in the framework-owned runtime
   partition (`:rf.runtime/resources`), not in app-db — so there are no
   `:articles` / `:article` / `:comments` / `:profile` paths in app-db to
   validate in the first place. All app-db actually holds here is the small auth
   slice and the per-form drafts.

   The wire shapes pull double duty: they're the resource `:data-schema` values
   AND the `:decode` schemas inside each resource / mutation `:request`. See
   schema: ../../../docs/guide/glossary.md#schema.

   The RealWorld API spec is documented at:
     https://github.com/gothinkster/realworld/tree/main/api"
  (:require [re-frame.core :as rf]
            ;; The schemas runtime. Loading it registers the hooks, so
            ;; rf/reg-app-schemas has something to resolve at the call site below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; WIRE SHAPES — what the RealWorld API returns
;; ============================================================================

(def User
  "The authenticated user. Returned by /users/login, /users (register),
   /user (current user), and PUT /user (settings update)."
  ;; The JWT is classified right at the transient slot that introduces it, via a
  ;; per-slot `:sensitive?` Malli property on the `:decode` schema. Since
  ;; `UserResponse` is the `:decode` schema for the login / register / restore /
  ;; settings replies, this one declaration redacts the token from any off-box
  ;; capture of the response body. The durable copy at [:auth :token] is a
  ;; separate matter, classified by the `:sensitive` effect in core.cljs —
  ;; classification doesn't propagate, so each surface declares its own. See data
  ;; classification: ../../../docs/guide/glossary.md#data-classification.
  [:map
   [:email    :string]
   [:token    {:sensitive? true} :string]
   [:username :string]
   [:bio      [:maybe :string]]
   [:image    [:maybe :string]]])

(def Profile
  "Another user's public profile. Returned by /profiles/:username and the
   follow / unfollow writes."
  [:map
   [:username  :string]
   [:bio       [:maybe :string]]
   [:image     [:maybe :string]]
   [:following :boolean]])

(def Article
  "A single article. Returned by /articles/:slug and embedded in list
   responses."
  [:map
   [:slug           :string]
   [:title          :string]
   [:description    :string]
   [:body           :string]
   [:tagList        [:vector :string]]
   [:createdAt      :string]
   [:updatedAt      :string]
   [:favorited      :boolean]
   [:favoritesCount :int]
   [:author         Profile]])

(def Comment
  "An article comment. Returned by /articles/:slug/comments."
  [:map
   [:id        :int]
   [:createdAt :string]
   [:updatedAt :string]
   [:body      :string]
   [:author    Profile]])

;; ============================================================================
;; WIRE-RESPONSE WRAPPERS
;; ============================================================================
;;
;; The Conduit API wraps every payload in a singular or plural top-level key
;; (`{:article …}`, `{:articles […]}`, and so on). These schemas describe that
;; envelope, and they get passed as `:decode` inside each resource / mutation
;; `:request` — the resource transport lowers the decode onto managed HTTP.

(def UserResponse     [:map [:user User]])
(def ProfileResponse  [:map [:profile Profile]])
(def ArticleResponse  [:map [:article Article]])
(def ArticlesResponse [:map
                       [:articles      [:vector Article]]
                       [:articlesCount {:optional true} :int]])
(def CommentResponse  [:map [:comment Comment]])
(def CommentsResponse [:map [:comments [:vector Comment]]])
(def TagsResponse     [:map [:tags [:vector :string]]])

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
  "The auth slice. :user holds the current User payload (or nil); :token is the
   JWT (or nil). The resource cache scope isn't stored here — it's derived from
   :user's :username by the named session resolver (see
   realworld-resources.scope). :return-to is the optional post-login bounce-back
   target the routing auth-guard stashes."
  [:map
   [:user      [:maybe User]]
   [:token     [:maybe :string]]
   [:return-to {:optional true}
    [:map
     [:id     :keyword]
     [:params [:maybe :map]]]]])

(def FormSlice
  "The standard form-draft slice, shared by the login / register / settings
   drafts in app-db. The submission lifecycle isn't here — that's a mutation
   instance (`:rf.mutation/state`) — so this slice carries only the editable draft
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
;; would fail. This app runs in `:rf/default` (the `frame-provider {:id …}` form
;; in core.cljs creates it at the render root), so we name that id here. Worth
;; being precise about what this is: a frame-local registration scoped by id, not
;; a frame creation or seed. It binds the id at ns-load; the frame itself doesn't
;; exist until the provider first renders.

(with-frame :rf/default
  (rf/reg-app-schemas
    {[:auth]                 AuthSlice
     [:auth :login-form]     FormSlice
     [:auth :register-form]  FormSlice
     [:settings-form]        FormSlice}))
