(ns realworld-resources.resources
  "The RESOURCE registry — every RealWorld read as a named, cached,
   runtime-managed server-state read. See the resources guide:
   ../../../docs/resources/concepts.md; resource glossary:
   ../../../docs/resources/glossary.md#resource.

   Each read is `reg-resource`d once; route entry / events CAUSE the fetch, and
   views read the runtime cache PASSIVELY through `[:rf.resource/*]` subs. No
   `:status` / `:loading?` app-db fields exist for these reads — the framework
   owns the lifecycle.

   A read's cache identity is `[scope resource-id canonical-params]`. Every
   variable that changes the remote read lives in `:params` (slug, username,
   tag); `:scope` is the leak boundary (see realworld-resources.scope,
   ../../../docs/resources/glossary.md#scope). `:tags` let a WRITE invalidate
   the right reads (see realworld-resources.mutations) — the
   read→write→invalidate→refetch loop, ../../../docs/resources/glossary.md#invalidate.

   `:stale-after-ms` / `:gc-after-ms` are exercised so the lifecycle is real: a
   `:loaded` entry goes stale after the window (a re-`ensure` then refetches into
   `:fetching`, keeping prior data visible — stale-while-revalidate), and an
   inactive entry (no owner) is GC-eligible after its window. A fresh re-`ensure`
   of a fresh entry is a cache-hit (no fetch, no dedupe)."
  (:require [clojure.string]
            [re-frame.core :as rf]
            ;; Managed HTTP — the single built-in resource/mutation transport.
            ;; Loading the ns registers the `:rf.http/managed` fx the runtime
            ;; lowers each ensure/refetch onto.
            [re-frame.http.managed]
            ;; Resources runtime. Requiring the ns at app boot wires the hooks +
            ;; registrations; without it `rf/reg-resource` throws.
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]))

;; ============================================================================
;; SHARED POLICY
;; ============================================================================

(def stale-after-ms
  "Reads go stale after a minute. A re-`ensure` of a stale `:loaded` entry
   refetches (stale-while-revalidate); a fresh one is a cache-hit."
  60000)

(def gc-after-ms
  "An inactive entry (no live owner) is GC-eligible five minutes after going
   inactive."
  (* 5 60 1000))

;; ============================================================================
;; PAGINATION — every server-visible list parameter lives in `:params`
;; ============================================================================
;;
;; The Conduit list endpoints page with `limit` / `offset`; the UI is 1-indexed
;; and the page size is fixed. The point this exercises: pagination is
;; DECLARATIVE on resources. The `:page` is just another `:params` key, so page N
;; and page N+1 are DISTINCT cache entries — back-navigating to a
;; previously-loaded page is a cache-hit (no fetch), and `:keep-previous?` on the
;; route entry keeps the prior page visible while the next first-loads (no
;; flicker). No `:status` / `:loading?` app-db field, no manual page-cache map —
;; the framework owns it. See ../../../docs/resources/how-to/paginate-a-feed.md.

(def page-size
  "The fixed Conduit list page size (the official client's value). The demo
   stub synthesises enough articles that several pages exist (see
   realworld-resources.http)."
  10)

(defn page->limit-offset
  "Map a 1-indexed `:page` (nil → page 1) to the Conduit `limit` / `offset`
   query pair. Pure — a page is just another canonical-params key, so this is
   the only place page math lives."
  [page]
  (let [p (max 1 (or page 1))]
    {:limit  page-size
     :offset (* (dec p) page-size)}))

(defn- with-pagination
  "Append the `limit` / `offset` query pair derived from `:page` to a list URL
   that already carries (or lacks) a query string. Keeps the resource
   `:request` fns terse while every page stays a distinct cache key."
  [url page]
  (let [{:keys [limit offset]} (page->limit-offset page)
        sep (if (clojure.string/includes? url "?") "&" "?")]
    (str url sep "limit=" limit "&offset=" offset)))

;; Reads retry; writes don't. Each read's `:request` returns the shared
;; `rh/data-fetch-retry` policy in its managed-HTTP args; `:retry` passes through
;; the resource lowering unchanged, so a transport blip / 5xx / timeout retries
;; with backoff+jitter (NOT a 4xx — the request shape was valid). Mutations
;; (writes) stay retry-free. See managed HTTP:
;; ../../../docs/resources/glossary.md#managed-http.

;; ============================================================================
;; PUBLIC READS — :scope :rf.scope/global (same for every viewer)
;; ============================================================================
;;
;; The `:scope :rf.scope/global` here is the explicit, AUDITABLE claim that this
;; read is identical for every user/tenant/locale. There is NO implicit default —
;; a missing scope policy fails loud at registration. See scope:
;; ../../../docs/resources/glossary.md#scope.

(rf/reg-resource :realworld/articles
  {:doc            "The global article list, optionally filtered by `:tag` and
                    paginated by `:page` (1-indexed). EVERY server-visible
                    option (the tag AND the page) is in params, so a
                    tag-filtered list, the unfiltered list, and each page are
                    DISTINCT cache entries. Back-navigating to a
                    previously-loaded page is a cache-hit; the route's
                    `:keep-previous?` keeps the prior page visible while the next
                    first-loads."
   :params-schema  [:map
                    [:tag  {:optional true} [:maybe :string]]
                    [:page {:optional true} [:maybe :int]]]
   :data-schema    schema/ArticlesResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   ;; Tag the list identity AND every article it contains, so favoriting an
   ;; article (which tags `[:article slug]`) invalidates any list showing it.
   :tags           (fn [_params data]
                     (into #{[:article-list]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [{:keys [tag page]} _ctx]
    {:request {:method :get
               :url    (-> (if tag
                             (str "/articles?tag=" tag)
                             "/articles")
                           rh/full-url
                           (with-pagination page))}
     :decode  schema/ArticlesResponse
     :retry   rh/data-fetch-retry}))

(rf/reg-resource :realworld/article
  {:doc            "Article detail by slug (public)."
   :params-schema  [:map [:slug :string]]
   :data-schema    schema/ArticleResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   ;; Tag BOTH the per-article identity and the list identity so a save /
   ;; favorite invalidates the detail and the lists together.
   :tags           (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (rh/full-url (str "/articles/" slug))}
     :decode  schema/ArticleResponse
     :retry   rh/data-fetch-retry}))

(rf/reg-resource :realworld/comments
  {:doc            "Comments for an article (public). A sub-resource of the
                    article modelled as an ordinary resource whose params carry
                    the parent identity (the slug)."
   :params-schema  [:map [:slug :string]]
   :data-schema    schema/CommentsResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [{:keys [slug]} _data] #{[:comments slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (rh/full-url (str "/articles/" slug "/comments"))}
     :decode  schema/CommentsResponse
     :retry   rh/data-fetch-retry}))

(rf/reg-resource :realworld/profile
  {:doc            "A user's public profile banner (public)."
   :params-schema  [:map [:username :string]]
   :data-schema    schema/ProfileResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [{:keys [username]} _data] #{[:profile username]})}
  (fn [{:keys [username]} _ctx]
    {:request {:method :get :url (rh/full-url (str "/profiles/" username))}
     :decode  schema/ProfileResponse
     :retry   rh/data-fetch-retry}))

(rf/reg-resource :realworld/author-articles
  {:doc            "Articles authored by a profile (public). Paginated by
                    `:page` — each page is a distinct cache entry."
   :params-schema  [:map
                    [:username :string]
                    [:page {:optional true} [:maybe :int]]]
   :data-schema    schema/ArticlesResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [{:keys [username]} data]
                     (into #{[:author-articles username]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [{:keys [username page]} _ctx]
    {:request {:method :get
               :url    (-> (str "/articles?author=" username)
                           rh/full-url
                           (with-pagination page))}
     :decode  schema/ArticlesResponse
     :retry   rh/data-fetch-retry}))

(rf/reg-resource :realworld/favorited-articles
  {:doc            "Articles a profile has FAVORITED (public). GET
                    `/articles?favorited=:username` — the backing read for the
                    profile's Favorited-Articles tab. Paginated by `:page`.
                    Tags both its own list identity AND every article it
                    contains, so the existing favorite / unfavorite mutations
                    (which stale `[:article slug]`) refetch this list with no
                    extra wiring — favoriting from the tab drops the article
                    out of it on the next refetch."
   :params-schema  [:map
                    [:username :string]
                    [:page {:optional true} [:maybe :int]]]
   :data-schema    schema/ArticlesResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [{:keys [username]} data]
                     (into #{[:favorited-articles username]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [{:keys [username page]} _ctx]
    {:request {:method :get
               :url    (-> (str "/articles?favorited=" username)
                           rh/full-url
                           (with-pagination page))}
     :decode  schema/ArticlesResponse
     :retry   rh/data-fetch-retry}))

(rf/reg-resource :realworld/tags
  {:doc            "The popular-tags sidebar (public)."
   :params-schema  [:map]
   :data-schema    schema/TagsResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [_params _data] #{[:tags]})}
  (fn [_params _ctx]
    {:request {:method :get :url (rh/full-url "/tags")}
     :decode  schema/TagsResponse
     :retry   rh/data-fetch-retry}))

;; ============================================================================
;; SESSION READ — a named `{:from-db …}` scope resolver (whose feed?)
;; ============================================================================
;;
;; The authenticated user's personalised feed depends on WHO is asking, so it
;; carries a session scope rather than `:rf.scope/global`. A named resolver
;; states that scope ONCE — `reg-resource-scope :realworld/session` (see
;; scope.cljs) — and the resource declares `:scope {:from-db :realworld/session}`:
;; a reference the runtime resolves at every site against app-db. The result:
;;   - a `[:rf.resource/state {:resource :realworld/feed :params {…}}]` sub
;;     resolves the scope ITSELF — no view passes a `:scope` payload — and
;;     re-keys reactively across login / logout;
;;   - the home route owns the feed as a declarative `:resources` entry with
;;     `:scope {:from-db :realworld/session}` (routing.cljs);
;;   - logged out, the reference resolves nil — fail-closed: the sub is the loud
;;     "scope unresolved" condition, never a silent shared-cache read.
;; See the named resolver in scope.cljs and
;; ../../../docs/resources/how-to/add-auth.md.

(rf/reg-resource :realworld/feed
  {:doc            "The authenticated user's feed (`/articles/feed`). Session-
                    scoped via the named `{:from-db :realworld/session}`
                    resolver: a logged-out user must never see a prior user's
                    feed from cache, and a login / logout re-keys every live
                    feed subscription automatically. Paginated by `:page` — the
                    page rides the params on top of the session scope, so each
                    (scope, page) is a distinct cache entry."
   :params-schema  [:map [:page {:optional true} [:maybe :int]]]
   :data-schema    schema/ArticlesResponse
   :scope          {:from-db :realworld/session}
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [_params data]
                     (into #{[:feed]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    (-> (rh/full-url "/articles/feed")
                           (with-pagination page))}
     :decode  schema/ArticlesResponse
     :retry   rh/data-fetch-retry}))
