(ns realworld-shared.schema
  "The canonical Conduit WIRE contract — the Malli shapes of every payload the
   RealWorld API returns, shared by both RealWorld examples
   (`realworld_http/` and `realworld_resources/`).

   These are transport-neutral: they describe the bytes on the wire, not any
   one app's app-db or machine state, so they are NOT part of the
   architecture comparison the two examples exist to draw. Extracting them here
   means the two apps validate responses against ONE definition and can never
   quietly drift apart. Each app's own `schema.cljs` still owns its app-db slice
   + machine `:data` schemas (those genuinely differ between the managed-HTTP and
   resources architectures) and imports these wire shapes directly, exactly as
   both apps already import `realworld-shared.avatar` / `realworld-shared.markdown`.

   The RealWorld API spec is documented at:
     https://github.com/gothinkster/realworld/tree/main/api

   A few wire conventions, straight from that spec:
   - Articles, profiles, and users arrive wrapped under a singular top-level
     key (`{:article {...}}`, `{:profile {...}}`, `{:user {...}}`); lists come
     under the plural one (`{:articles [...]}`, and so on).
   - Datetimes are ISO-8601 strings.
   - Auth tokens are JWT strings, riding in as the `:token` field of the
     `User` payload after login or registration.

   Pure Malli data — no re-frame, no registration, no requires. Each app's own
   schema ns is where these get wired into `reg-app-schemas` / a machine
   `:data` slot.")

;; ============================================================================
;; WIRE SHAPES — what the RealWorld API returns
;; ============================================================================

(def User
  "The signed-in user, with their credentials. Returned by /users/login,
   /users (register), /user (current user), and PUT /user (settings update).

   The JWT is classified right at the slot that first carries it, with a
   per-slot `:sensitive?` Malli property. Since `UserResponse` is the `:decode`
   schema for the login / register / session-restore / settings replies, this
   one declaration keeps the token out of any off-box capture of the response
   body. The DURABLE copy at [:auth :token] is a separate matter, classified by
   each app's own `:sensitive` classification — classification doesn't
   propagate, so every surface a secret touches has to declare it for itself.
   (The outbound Bearer header gets this for free, via the framework's built-in
   carrier denylist.) See the keep-secrets how-to:
   ../../../docs/core/how-to/keep-secrets-out-of-traces.md"
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
;; WIRE-RESPONSE WRAPPERS — the seven Conduit response envelopes
;; ============================================================================
;;
;; Conduit never hands you a bare object — it always tucks the payload under a
;; singular or plural top-level key. These schemas describe that envelope, and
;; they're what each app passes as `:decode` so the body gets validated on the
;; way in. See the HTTP guide on `:decode`:
;; ../../../docs/async/http.md#validating-the-body-with-decode

(def UserResponse
  "POST /users/login, POST /users (register), GET /user, PUT /user."
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
