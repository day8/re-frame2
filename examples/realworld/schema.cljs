(ns example.realworld.schema
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
  (:require [re-frame.core :as rf]))

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
;; APP-DB SLICES — Pattern-RemoteData shape per resource
;; ============================================================================
;;
;; Every slice that holds remote data follows the standard 5-key shape from
;; docs/specification/Pattern-RemoteData.md.

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
  "The auth slice. The :flow sub-slice is the snapshot of the auth state
   machine; :user holds the current :User payload (or nil); :token is
   the JWT (or nil)."
  [:map
   [:user  [:maybe User]]
   [:token [:maybe :string]]
   [:flow  [:map
            [:state   [:enum :idle :submitting :authed :error :restoring]]
            [:context :map]]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; Path-based schema attachment per Spec 010. The framework validates writes
;; to these paths in development.

(rf/reg-app-schema [:auth]                     AuthSlice)
(rf/reg-app-schema [:articles]                 RequestSlice)
(rf/reg-app-schema [:articles :data]           [:vector Article])
(rf/reg-app-schema [:tags]                     RequestSlice)
(rf/reg-app-schema [:profile]                  RequestSlice)
(rf/reg-app-schema [:comments]                 RequestSlice)
;; TODO — schemas for editor/, favorites/, follows/ slices land with their
;; corresponding feature implementations.
