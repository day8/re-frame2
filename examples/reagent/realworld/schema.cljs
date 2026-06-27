(ns realworld.schema
  "Malli schemas for the RealWorld (Conduit) example.

   These describe the shape of every wire payload the RealWorld API returns,
   plus the shape of each app-db slice that holds them. The schemas are
   registered for path-based validation via `reg-app-schemas`. See the
   schemas how-to: ../../../docs/guide/how-to/validate-with-schemas.md

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
            ;; Schemas ship in the re-frame2-schemas artefact. Requiring
            ;; the ns registers its hooks so `rf/reg-app-schemas` resolves
            ;; at the call site below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; WIRE SHAPES — what the RealWorld API returns
;; ============================================================================

(def User
  "The authenticated user's profile. Returned by /users/login,
   /users (register), and /user (current user)."
  ;; Classify the JWT at the transient slot that introduces it, via the
  ;; per-slot `:sensitive?` Malli property on the `:decode` schema.
  ;; `UserResponse` is the `:decode` schema for the login / register /
  ;; session-restore replies, so this redacts the token out of any off-box
  ;; capture of the response body. The durable copy at [:auth :token] is
  ;; classified separately by `:auth/classify-token` (core.cljs) —
  ;; classification does not propagate, so each surface a secret crosses is
  ;; declared on its own; the Bearer header is on the framework's built-in
  ;; carrier denylist. See the keep-secrets how-to:
  ;; ../../../docs/guide/how-to/keep-secrets-out-of-traces.md
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
;; `:decode` key to `:rf.http/managed`. See the HTTP guide on `:decode`:
;; ../../../docs/resources/http.md#validating-the-body-with-decode

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
;; APP-DB SLICES — remote-data slice shape per resource
;; ============================================================================
;;
;; Every slice that holds remote data follows the same 5-key shape.

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

;; Machine snapshots live in the runtime-db partition at
;; [:rf.runtime/machines :snapshots <id>], not in app-db. `reg-app-schema`
;; validates the app-db partition only, so a machine snapshot's shape is
;; validated through the machine's own `[:schemas :data]` slot (which
;; describes the snapshot's `:data` map) rather than an app-schema on a
;; runtime path. The `*Data` schemas below are attached as `[:schemas :data]`
;; where each machine is registered (auth.cljs / tags.cljs / settings.cljs).

(def AuthFlowData
  "The `:data` slot of the `:auth/flow` machine snapshot."
  [:map
   [:error [:maybe :string]]])

(def TagsData
  "The `:data` slot of the `:realworld/tags` machine snapshot, where the
   remote-data lifecycle lives entirely in the machine. The state-keyword
   is the status enum; `:data` carries the items, error, loaded-at, and
   attempt fields the slice form would store in the slice itself."
  [:map
   [:tags      [:vector :string]]
   [:error     [:maybe :any]]
   [:loaded-at [:maybe :int]]
   [:attempt   :int]])

(def SettingsFormData
  "The `:data` slot of the `:settings/form` machine snapshot, where the
   form lifecycle lives entirely in the machine. The state-keyword is the
   lifecycle (`:neutral` / `:incorrect` / `:correct` + `:submitting`);
   `:data` carries the draft + per-field validation state + the projected
   submit-error string."
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
  ;; The state vocabulary (`:mode` = :create/:edit, lifecycle `:status`)
  ;; lives in the :ui/article-editor machine snapshot (runtime-db), not this
  ;; app-db slice — see `editor-slice` in article_editor.cljs. The slice
  ;; carries only the data.
  [:map
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
   [:errors :map]
   [:touched [:set :keyword]]
   [:submit-attempted? {:optional true} :boolean]
   ;; Output of the :editor/can-submit? flow. Optional because the flow's
   ;; first walk lands one event after :editor/initialise.
   [:can-submit? {:optional true} :boolean]
   [:submit-error [:maybe :string]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; Path-based schema attachment. The framework validates writes to these
;; paths in development.
;;
;; This example uses the bulk plural form `rf/reg-app-schemas`: a single
;; `{path -> schema}` map reads more cleanly than a tower of singular
;; `reg-app-schema` calls.
;;
;; All paths here are app-db paths. Machine snapshots are not app-db — they
;; live in runtime-db and are validated through each machine's
;; `[:schemas :data]` (the *Data schemas above), so they do not appear here.
;;
;; `reg-app-schemas` is frame-local, so it needs a frame in context; a bare
;; ns-load call would raise :rf.error/no-frame-context. `with-frame` names
;; the target frame, `:rf/default`, so the schemas register against that id
;; at load time. The frame-provider in core.cljs creates `:rf/default` and
;; picks these schemas up.
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
