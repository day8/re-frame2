(ns realworld-resources.schema
  "Malli schemas for the RealWorld-on-resources (Conduit) example.

   These describe the wire payloads the RealWorld API returns. Unlike the
   `:rf.http/managed` sibling (`examples/reagent/realworld/`), this variant
   does NOT register Pattern-RemoteData app-db slice schemas for the reads —
   the cached server-state lives in the framework-owned runtime partition
   (`:rf.runtime/resources`), not in app-db, so there are no `:articles` /
   `:article` / `:comments` / `:profile` slice paths to validate. What stays
   in app-db here is only the small auth slice and the per-form drafts.

   The wire shapes double as resource `:data-schema` values and as the
   `:decode` schemas inside each resource / mutation `:request` (Spec 014
   §Schema-driven decode lowered through the resource transport).

   The RealWorld API spec is documented at:
     https://github.com/gothinkster/realworld/tree/main/api"
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas. Loading
            ;; the ns registers its late-bind hooks so rf/reg-app-schemas
            ;; resolves at the call site below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; WIRE SHAPES — what the RealWorld API returns
;; ============================================================================

(def User
  "The authenticated user. Returned by /users/login, /users (register),
   /user (current user), and PUT /user (settings update)."
  ;; EP-0015 (issue 5 / disposition 5): the JWT is owner-classified at the
  ;; slot that introduces it, via the EP-0005 per-slot `:sensitive?` malli
  ;; property — `UserResponse` is the `:decode` schema for the login / register
  ;; / restore / settings replies, so this redacts the token out of any off-box
  ;; capture of the response body (complementing the frame `:sensitive` config
  ;; in core.cljs, which redacts the same fact once it lands at [:auth :token]
  ;; / the Bearer header).
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
;; The Conduit API wraps every payload in a singular/plural top-level key.
;; These schemas describe the wire envelope; they are passed as `:decode`
;; inside each resource / mutation `:request` (Spec 014 §Schema-driven decode,
;; which the resource transport lowers onto managed HTTP).

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
;; In the resources variant the cached reads live in runtime-db, not app-db.
;; The only app-db schema is the auth slice plus the two auth form drafts.
;; (The settings form here is a mutation instance + a small draft slice;
;; the auth machine snapshot lives in runtime-db and is validated by its own
;; `:data-schema`, not an app-schema — EP-0001 Mike ruling #11.)

(def AuthSlice
  "The auth slice. :user holds the current User payload (or nil); :token is
   the JWT (or nil). :scope-key holds the resource cache scope this session
   reads under (see realworld-resources.scope). :return-to is the optional
   post-login bounce-back target stashed by the routing auth-guard."
  [:map
   [:user      [:maybe User]]
   [:token     [:maybe :string]]
   [:return-to {:optional true}
    [:map
     [:id     :keyword]
     [:params [:maybe :map]]]]])

(def FormSlice
  "The standard Pattern-Forms draft slice for the login / register / settings
   drafts that still live in app-db. (Submission lifecycle moves to a mutation
   INSTANCE — `:rf.mutation/state` — for the settings form, so the slice here
   carries only the editable draft + touched-field bookkeeping.)"
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
;; EP-0002: reg-app-schemas is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; :rf/default (see core/run's reg-frame :rf/default), so name it here.

(with-frame :rf/default
  (rf/reg-app-schemas
    {[:auth]                 AuthSlice
     [:auth :login-form]     FormSlice
     [:auth :register-form]  FormSlice
     [:settings-form]        FormSlice}))
