(ns realworld.schema
  "Malli schemas for the RealWorld (Conduit) example.

   These describe the shape of every wire payload the RealWorld API returns,
   plus the shape of each app-db slice that holds them. The schemas are
   registered with re-frame2 via `reg-app-schemas` (the bulk plural form)
   for path-based validation per Spec 010.

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
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schemas resolves at the call site below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; WIRE SHAPES — what the RealWorld API returns
;; ============================================================================

(def User
  "The authenticated user's profile. Returned by /users/login,
   /users (register), and /user (current user)."
  ;; EP-0015 (issue 5 / disposition 5): the JWT is owner-classified at the
  ;; slot that introduces it, via the EP-0005 per-slot `:sensitive?` malli
  ;; property. `UserResponse` is the `:decode` schema for the login / register
  ;; / session-restore replies, so this classification redacts the token out
  ;; of any off-box capture of the response body (the same fact the frame
  ;; `:sensitive` config redacts once it lands at [:auth :token] / the Bearer
  ;; header — declared here so it is also covered while still in flight as a
  ;; decoded reply, before the auth machine stores it).
  [:map
   [:email    :string]
   [:token    {:sensitive? true} :string]
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
  "The standard remote-data lifecycle slice. Generic over the :data type.

   `:articles-count` is the official RealWorld pagination total — the GRAND
   count of articles matching the query (before the limit/offset window), used
   to compute the page count. Optional because only the paginated article-list
   slices (`:articles`, `:feed`, `:profile.articles`, `:profile.favorites`)
   carry it; single-resource slices (`:article`, `:profile`) never do."
  [:map
   [:status         [:enum :idle :loading :fetching :loaded :error]]
   [:data           {:default nil} :any]
   [:error          {:default nil} [:maybe :any]]
   [:loaded-at      {:default nil} [:maybe :int]]
   [:attempt        {:default 0}   :int]
   [:articles-count {:optional true} :int]
   [:stale-after-ms {:optional true} [:maybe :int]]])

(def AuthSlice
  "The auth slice. :user holds the current :User payload (or nil);
   :token is the JWT (or nil). The auth machine snapshot itself lives in
   runtime-db at [:rf.runtime/machines :snapshots :auth/flow].

   :return-to is the optional post-login bounce-back target stashed by the
   routing auth-guard (routing.cljs) when an unauthenticated user is
   redirected away from a `:requires-auth` route; `:auth/post-login-redirect`
   reads and clears it (auth.cljs). Present only between the redirect and the
   next successful login."
  [:map
   [:user      [:maybe User]]
   [:token     [:maybe :string]]
   [:return-to {:optional true}
    [:map
     [:id     :keyword]
     [:params [:maybe :map]]]]])

;; Machine snapshots live in the framework-owned **runtime-db** partition at
;; [:rf.runtime/machines :snapshots <id>], NOT in app-db. `reg-app-schema`
;; validates the app-db partition only (EP-0001, Mike ruling #11), so a machine
;; snapshot's shape is validated through the machine's own `:data-schema` slot
;; (which describes the snapshot's `:data` map) rather than an app-schema on a
;; runtime path. The `*Data` schemas below are attached as `:data-schema` where
;; each machine is registered (auth.cljs / tags.cljs / settings.cljs).

(def AuthFlowData
  "The `:data` slot of the `:auth/flow` machine snapshot."
  [:map
   [:error [:maybe :string]]])

(def TagsData
  "The `:data` slot of the `:realworld/tags` machine snapshot — the
   :data-region machine variant of Pattern-RemoteData. The
   state-keyword IS the Pattern-RemoteData status enum; `:data` carries
   the items, error, loaded-at, and attempt fields that the slice form
   would store in the slice itself."
  [:map
   [:tags      [:vector :string]]
   [:error     [:maybe :any]]
   [:loaded-at [:maybe :int]]
   [:attempt   :int]])

(def SettingsFormData
  "The `:data` slot of the `:settings/form` machine snapshot — the
   :form-region machine variant of Pattern-Forms. The state-keyword
   IS the form lifecycle (`:neutral` / `:incorrect` / `:correct`
   + `:submitting`); `:data` carries the draft + per-field
   validation state + the projected submit-error string."
  [:map
   [:draft        :map]
   [:submitted    [:maybe :map]]
   [:errors       [:map-of :keyword [:vector :string]]]
   [:touched      [:set :keyword]]
   [:submit-error [:maybe :string]]
   [:loaded-at    [:maybe :int]]])

(def FormSlice
  [:map
   [:draft :any]
   [:submitted [:maybe :any]]
   [:status :keyword]
   [:errors :map]
   [:touched [:set :keyword]]
   [:submit-attempted? {:optional true} :boolean]
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
   [:submit-attempted? {:optional true} :boolean]
   ;; Materialised output of the :editor/can-submit? flow (Spec 013).
   ;; Optional because the flow's first walk (right after the handler,
   ;; before the db install) lands one event after :editor/initialise
   ;; (Spec 013 §Sequencing).
   [:can-submit? {:optional true} :boolean]
   [:submit-error [:maybe :string]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; Path-based schema attachment per Spec 010. The framework validates writes
;; to these paths in development.
;;
;; This example uses the bulk plural form `rf/reg-app-schemas`:
;; a feature-modular app declares 5–20 schemas in one place, so a single
;; `{path -> schema}` map reads more cleanly than a tower of singular
;; `reg-app-schema` calls. Source-coords for the bulk call stamp every
;; registered entry.

;; All paths here are app-db paths. Machine snapshots are NOT app-db — they
;; live in runtime-db and are validated through each machine's `:data-schema`
;; (the *Data schemas above), so they do not appear in this app-schema map.
;;
;; EP-0002 (rf2-5q7um6): reg-app-schemas is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; :rf/default (see `core/run`'s `reg-frame :rf/default`), so name it here.
(with-frame :rf/default
 (rf/reg-app-schemas
  {[:auth]                          AuthSlice
   [:articles]                      RequestSlice
   [:articles :data]                [:vector Article]
   [:article]                       RequestSlice
   [:article :data]                 [:maybe Article]
   [:profile]                       RequestSlice
   [:profile :data]                 [:maybe Profile]
   [:profile.articles]              RequestSlice
   [:profile.articles :data]        [:vector Article]
   [:profile.favorites]             RequestSlice
   [:profile.favorites :data]       [:vector Article]
   [:comments]                      RequestSlice
   [:comments :data]                [:vector Comment]
   [:feed]                          RequestSlice
   [:feed :data]                    [:vector Article]
   [:comment-form]                  FormSlice
   [:auth :login-form]              FormSlice
   [:auth :register-form]           FormSlice
   [:editor]                        EditorSlice}))
