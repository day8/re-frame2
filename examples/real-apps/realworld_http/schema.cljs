(ns realworld-http.schema
  "Malli schemas for the RealWorld (Conduit) example.

   The shapes, in one place. This file describes every payload the RealWorld
   API hands back AND the shape of each app-db slice that stores it, so the two
   never quietly drift apart. The slice schemas get wired up for path-based
   validation via `reg-app-schemas` at the bottom. See the schemas how-to:
   ../../../docs/core/how-to/validate-with-schemas.md

   The RealWorld API spec is documented at:
     https://github.com/gothinkster/realworld/tree/main/api

   A few wire conventions, straight from that spec:
   - Articles, profiles, and users arrive wrapped under a singular top-level
     key (`{:article {...}}`, `{:profile {...}}`, `{:user {...}}`); lists come
     under the plural one (`{:articles [...]}`, and so on).
   - Datetimes are ISO-8601 strings.
   - Auth tokens are JWT strings, riding in as the `:token` field of the
     `User` payload after login or registration."
  (:require [re-frame.core :as rf]
            ;; Schemas live in their own artefact; we require it to load it,
            ;; which registers the hooks that make `rf/reg-app-schemas` resolve
            ;; at the call site below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; WIRE SHAPES — what the RealWorld API returns
;; ============================================================================

(def User
  "The signed-in user, with their credentials. Returned by /users/login,
   /users (register), and /user (current user)."
  ;; Classify the JWT right at the slot that first carries it, with a per-slot
  ;; `:sensitive?` Malli property on the `:decode` schema. `UserResponse` is the
  ;; `:decode` schema for the login / register / session-restore replies, so
  ;; this is what keeps the token out of any off-box capture of the response
  ;; body. The DURABLE copy at [:auth :token] is a separate matter, classified
  ;; by `:auth/classify-token` (core.cljs) — classification doesn't propagate,
  ;; so every surface a secret touches has to declare it for itself. (The
  ;; Bearer header gets this for free, via the framework's built-in carrier
  ;; denylist.) See the keep-secrets how-to:
  ;; ../../../docs/core/how-to/keep-secrets-out-of-traces.md
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
;; Conduit never hands you a bare object — it always tucks the payload under a
;; singular or plural top-level key. These schemas describe that envelope, and
;; they're what you pass as `:decode` to `:rf.http/managed` so the body gets
;; validated on the way in. See the HTTP guide on `:decode`:
;; ../../../docs/async/http.md#validating-the-body-with-decode

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
;; APP-DB SLICES — the remote-data slice shape, one per resource
;; ============================================================================
;;
;; Every slice that holds remote data wears the same 5-key shape. Learn it
;; once, recognise it everywhere.

(def RequestSlice
  "The standard remote-data lifecycle slice, generic over whatever `:data` it
   holds.

   `:articles-count` is the RealWorld pagination total — the GRAND count of
   articles matching the query (before the limit/offset window), which is what
   the page count is computed from. It's optional because only the paginated
   list slices (`:articles`, `:feed`, `:profile.articles`,
   `:profile.favorites`) carry it; the single-resource slices (`:article`,
   `:profile`) have no use for it."
  [:map
   [:status         [:enum :idle :loading :fetching :loaded :error]]
   [:data           {:default nil} :any]
   [:error          {:default nil} [:maybe :any]]
   [:loaded-at      {:default nil} [:maybe :int]]
   [:attempt        {:default 0}   :int]
   [:articles-count {:optional true} :int]
   [:stale-after-ms {:optional true} [:maybe :int]]])

(def AuthSlice
  "The auth slice. :user holds the current :User payload (or nil); :token is
   the JWT (or nil). The auth machine's own snapshot lives elsewhere — over in
   runtime-db at [:rf.runtime/machines :snapshots :auth/flow].

   :return-to is the breadcrumb: the post-login bounce-back target the routing
   `:rf.route/entry-blocked` handler drops here (routing.cljs) when the
   `:can-enter` auth gate turns a logged-out user away from a `:requires-auth`
   route. `:auth/post-login-redirect` reads and clears it (auth.cljs). It only
   exists for the brief window between that redirect and the next successful
   login."
  [:map
   [:user      [:maybe User]]
   [:token     [:maybe :string]]
   [:return-to {:optional true}
    [:map
     [:id     :keyword]
     [:params [:maybe :map]]]]])

;; Machine snapshots aren't app-db — they live in the runtime-db partition at
;; [:rf.runtime/machines :snapshots <id>]. And `reg-app-schema` only polices
;; app-db, so you don't validate a snapshot with an app-schema on a runtime
;; path. Instead each machine carries its own `[:schemas :data]` slot
;; describing its snapshot's `:data` map, and the `*Data` schemas below are
;; what get attached there, at each machine's registration site (auth.cljs /
;; tags.cljs / settings.cljs).

(def AuthFlowData
  "The `:data` slot of the `:auth/flow` machine's snapshot."
  [:map
   [:error [:maybe :string]]])

(def TagsData
  "The `:data` slot of the `:realworld/tags` machine's snapshot — the case
   where the whole remote-data lifecycle lives in the machine. The
   state-keyword is the status enum, and `:data` here holds the items, error,
   loaded-at, and attempt that the slice form would otherwise keep in a slice."
  [:map
   [:tags      [:vector :string]]
   [:error     [:maybe :any]]
   [:loaded-at [:maybe :int]]
   [:attempt   :int]])

(def SettingsFormData
  "The `:data` slot of the `:settings/form` machine's snapshot — the case where
   the whole FORM lifecycle lives in the machine. The state-keyword is the
   lifecycle (`:neutral` / `:incorrect` / `:correct` + `:submitting`), and
   `:data` holds the draft, the per-field validation state, and the projected
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
  ;; The state vocabulary (`:mode` = :create/:edit, the lifecycle `:status`)
  ;; lives in the :ui/article-editor machine's snapshot over in runtime-db, NOT
  ;; in this slice — see `editor-slice` in article_editor.cljs. This slice is
  ;; just the data.
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
   ;; Where the :editor/can-submit? flow parks its output. Optional because the
   ;; flow's first computation only lands one event after :editor/initialise.
   [:can-submit? {:optional true} :boolean]
   [:submit-error [:maybe :string]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; Attach schemas to app-db paths, and the framework will validate writes to
;; those paths in development — a tripwire for the day a handler starts putting
;; the wrong shape somewhere.
;;
;; We use the bulk plural `rf/reg-app-schemas` here: one `{path -> schema}` map
;; reads far more cleanly than a tall stack of singular `reg-app-schema` calls.
;;
;; Every path below is an app-db path. Machine snapshots are deliberately
;; absent — they're runtime-db, not app-db, and get validated through each
;; machine's own `[:schemas :data]` (the *Data schemas above) instead.
;;
;; One wrinkle: `reg-app-schemas` is frame-local, so it needs a frame in
;; context — call it bare at ns-load and you'd get :rf.error/no-frame-context.
;; `with-frame` names the target, `:rf/default`, so these register against that
;; frame at load time, ready for the frame-provider in core.cljs to pick up
;; when it creates `:rf/default`.
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
