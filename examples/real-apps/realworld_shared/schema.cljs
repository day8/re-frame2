(ns realworld-shared.schema
  "The canonical Conduit WIRE contract — the Malli shapes of every payload the
   RealWorld API returns, shared by both RealWorld examples
   (`realworld_http/` and `realworld_resources/`).

   These are transport-neutral: they describe the bytes on the wire, not any
   one app's app-db or machine state, so they are NOT part of the
   architecture comparison the two examples exist to draw. Extracting them here
   means the two apps validate responses against ONE definition and can never
   quietly drift apart. The one DURABLE shape that also belongs here is
   `SessionUser` — the token-free user both apps persist at `[:auth :user]`,
   which is mechanically the wire `User` minus its credential; deriving it once
   beside the wire shape it mirrors keeps the wire/durable split in a single
   place and is identical across both architectures. Each app's own `schema.cljs`
   still owns its app-db slice + machine `:data` schemas (those genuinely differ
   between the managed-HTTP and resources architectures) and imports these wire
   shapes directly, exactly as both apps already import `realworld-shared.avatar`
   / `realworld-shared.markdown`.

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
   carrier denylist.) The DURABLE user an app stores at `[:auth :user]` is a
   separate contract too — token-free, validated against `SessionUser` below,
   not this wire shape. See the keep-secrets how-to:
   ../../../docs/core/how-to/keep-secrets-out-of-traces.md"
  [:map
   [:email    :string]
   [:token    {:sensitive? true} :string]
   [:username :string]
   [:bio      [:maybe :string]]
   [:image    [:maybe :string]]])

;; ----------------------------------------------------------------------------
;; DURABLE DERIVATION — the token-free session user both apps STORE
;; ----------------------------------------------------------------------------
;;
;; `User` above is the WIRE shape; `SessionUser` is what survives into durable
;; app-db. They are deliberately DISTINCT contracts — this is the one durable
;; shape that lives beside the wire shapes because it IS a wire shape minus its
;; credential, and both apps store it identically.

(def SessionUser
  "The DURABLE session user — the wire `User` with the credential stripped off.

   Wire and durable are DISTINCT contracts, and this is where they part. `User`
   above REQUIRES `:token`, because a login / register / restore / settings reply
   that arrives WITHOUT a JWT is a broken reply that decode must reject. But the
   JWT is a credential, and durable app-db keeps it in exactly ONE classified
   place — `[:auth :token]`. The identity an app stores at `[:auth :user]` is
   written token-free (each app's `:auth/store-session` `dissoc`s it), so the
   durable slice needs its OWN, token-free shape: this one. Validating the
   durable user against the wire `User` would demand the very `:token` the app
   deliberately dropped — post-commit validation would reject the token-free
   commit and roll the whole login back.

   This is the identity/profile half of `User` — every field EXCEPT the
   credential. Keep the two in step: an identity field added to the wire `User`
   belongs here too. Written as vector-form Malli (rather than a `malli.util`
   derivation of `User`) so the schema-walker can still introspect every per-slot
   flag when this embeds in an app's `AuthSlice`."
  [:map
   [:email    :string]
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
