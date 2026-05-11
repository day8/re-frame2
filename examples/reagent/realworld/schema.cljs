(ns realworld.schema
  "Malli schemas for the RealWorld (Conduit) example.

   These describe the shape of every wire payload the RealWorld API returns,
   plus the shape of each app-db slice that holds them. The schemas are
   registered with re-frame2 via `reg-app-schema` for path-based validation
   per Spec 010.

   The RealWorld API spec is documented at:
     https://github.com/gothinkster/realworld/tree/main/api

   Wire-shape conventions (from the spec):
   - Articles, profiles, and users come wrapped under a top-level singular
     key (`{:article {...}}`, `{:profile {...}}`, `{:user {...}}`); list
     responses come under the plural key (`{:articles [...]}`, etc.).
   - Datetimes are ISO-8601 strings.
   - Authentication tokens are JWT strings, returned as the `:token` field
     of the `User` payload after login or registration."
  (:require [re-frame.core :as rf]
            ;; Per rf2-p7va, re-frame.schemas ships in
            ;; day8/re-frame2-schemas. Loading the ns here registers
            ;; its late-bind hooks so rf/reg-app-schema resolves at
            ;; the call sites below.
            [re-frame.schemas]))

;; ============================================================================
;; WIRE SHAPES — what the RealWorld API returns
;; ============================================================================

(def User
  "The authenticated user's profile. Returned by /users/login,
   /users (register), and /user (current user)."
  [:map
   [:email    :string]
   [:token    :string]
   [:username :string]
   [:bio      [:maybe :string]]
   [:image    [:maybe :string]]])

(def Profile
  "Another user's public profile. Returned by /profiles/:username."
  [:map
   [:username  :string]
   [:bio       [:maybe :string]]
   [:image     [:maybe :string]]
   [:following :boolean]])

(def Article
  "A single article. Returned by /articles/:slug and embedded in
   list responses."
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

(def Tag :string)

;; ============================================================================
;; WIRE-RESPONSE WRAPPERS
;; ============================================================================
;;
;; The Conduit API wraps every payload in a singular/plural top-level key.
;; These schemas describe the wire-shape envelope; they are passed as the
;; `:decode` key to `:rf.http/managed` per Spec 014 §Schema-driven decode.

(def UserResponse
  "POST /users/login, POST /users (register), GET /user."
  [:map [:user User]])

(def ProfileResponse
  "GET /profiles/:username, POST/DELETE /profiles/:username/follow."
  [:map [:profile Profile]])

(def ArticleResponse
  "GET /articles/:slug, POST /articles, PUT /articles/:slug,
   POST/DELETE /articles/:slug/favorite."
  [:map [:article Article]])

(def ArticlesResponse
  "GET /articles, GET /articles/feed."
  [:map
   [:articles      [:vector Article]]
   [:articlesCount {:optional true} :int]])

(def CommentResponse
  "POST /articles/:slug/comments."
  [:map [:comment Comment]])

(def CommentsResponse
  "GET /articles/:slug/comments."
  [:map [:comments [:vector Comment]]])

(def TagsResponse
  "GET /tags."
  [:map [:tags [:vector :string]]])

;; ============================================================================
;; APP-DB SLICES — Pattern-RemoteData shape per resource
;; ============================================================================
;;
;; Every slice that holds remote data follows the standard 5-key shape from
;; spec/Pattern-RemoteData.md.

(def RequestSlice
  "The standard remote-data lifecycle slice. Generic over the :data type."
  [:map
   [:status         [:enum :idle :loading :fetching :loaded :error]]
   [:data           {:default nil} :any]
   [:error          {:default nil} [:maybe :any]]
   [:loaded-at      {:default nil} [:maybe :int]]
   [:attempt        {:default 0}   :int]
   [:stale-after-ms {:optional true} [:maybe :int]]])

(def AuthSlice
  "The auth slice. :user holds the current :User payload (or nil);
   :token is the JWT (or nil). The auth machine snapshot itself lives at
   [:rf/machines :auth/flow]."
  [:map
   [:user  [:maybe User]]
   [:token [:maybe :string]]])

(def AuthFlowSnapshot
  [:map
   [:state [:enum :idle :submitting :authed :error :restoring]]
   [:data  [:map
            [:error [:maybe :string]]]]])

(def TagsSnapshot
  "Snapshot shape for the `:realworld/tags` machine — the
   :data-region machine variant of Pattern-RemoteData (rf2-0i4y). The
   state-keyword IS the Pattern-RemoteData status enum; `:data` carries
   the items, error, loaded-at, and attempt fields that the slice form
   would store in the slice itself."
  [:map
   [:state [:enum :idle :loading :fetching :loaded :error]]
   [:data  [:map
            [:tags      [:vector :string]]
            [:error     [:maybe :any]]
            [:loaded-at [:maybe :int]]
            [:attempt   :int]]]])

(def FormSlice
  [:map
   [:draft :any]
   [:submitted [:maybe :any]]
   [:status :keyword]
   [:errors :map]
   [:touched [:set :keyword]]
   [:submit-error [:maybe :string]]])

(def EditorSlice
  [:map
   [:mode [:enum :create :edit]]
   [:slug [:maybe :string]]
   [:draft [:map
            [:title :string]
            [:description :string]
            [:body :string]
            [:tagList :string]]]
   [:baseline [:map
               [:title :string]
               [:description :string]
               [:body :string]
               [:tagList :string]]]
   [:submitted [:maybe :any]]
   [:status :keyword]
   [:errors :map]
   [:touched [:set :keyword]]
   [:submit-error [:maybe :string]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; Path-based schema attachment per Spec 010. The framework validates writes
;; to these paths in development.

(rf/reg-app-schema [:auth]                     AuthSlice)
(rf/reg-app-schema [:rf/machines :auth/flow]   AuthFlowSnapshot)
(rf/reg-app-schema [:articles]                 RequestSlice)
(rf/reg-app-schema [:articles :data]           [:vector Article])
(rf/reg-app-schema [:article]                  RequestSlice)
(rf/reg-app-schema [:article :data]            [:maybe Article])
;; The `:tags` slice from the slice-form era is gone; the popular-tags
;; lifecycle is now the `:realworld/tags` machine (rf2-0i4y, the
;; :data-region machine variant of Pattern-RemoteData). The snapshot
;; lives at `[:rf/machines :realworld/tags]`.
(rf/reg-app-schema [:rf/machines :realworld/tags] TagsSnapshot)
(rf/reg-app-schema [:profile]                  RequestSlice)
(rf/reg-app-schema [:profile :data]            [:maybe Profile])
(rf/reg-app-schema [:profile.articles]         RequestSlice)
(rf/reg-app-schema [:profile.articles :data]   [:vector Article])
(rf/reg-app-schema [:profile.favorites]        RequestSlice)
(rf/reg-app-schema [:profile.favorites :data]  [:vector Article])
(rf/reg-app-schema [:comments]                 RequestSlice)
(rf/reg-app-schema [:comments :data]           [:vector Comment])
(rf/reg-app-schema [:feed]                     RequestSlice)
(rf/reg-app-schema [:feed :data]               [:vector Article])
(rf/reg-app-schema [:comment-form]             FormSlice)
(rf/reg-app-schema [:editor]                   EditorSlice)
(rf/reg-app-schema [:settings]                 FormSlice)
