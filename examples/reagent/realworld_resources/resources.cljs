(ns realworld-resources.resources
  "The resource registry — every read in the app, expressed as a named, cached,
   framework-managed bit of server-state. See the resources guide:
   ../../../docs/resources/concepts.md; resource glossary:
   ../../../docs/resources/glossary.md#resource.

   The pattern repeats for every read. You `reg-resource` it once. Something —
   a route entry, an event — CAUSES the fetch. And your views just read the
   cache, passively, through `[:rf.resource/*]` subs. Notice what's missing:
   there are no `:status` or `:loading?` fields in app-db for any of these reads.
   The framework owns that lifecycle so you don't have to.

   A read's cache identity is `[scope resource-id canonical-params]`. Anything
   that changes the remote read goes in `:params` — slug, username, tag. `:scope`
   is the leak boundary (see realworld-resources.scope,
   ../../../docs/resources/glossary.md#scope). And `:tags` are what let a WRITE
   invalidate the right reads (see realworld-resources.mutations) — that's the
   read→write→invalidate→refetch loop, ../../../docs/resources/glossary.md#invalidate.

   The lifecycle here is real, not decorative — `:stale-after-ms` and
   `:gc-after-ms` are set so you can watch it work. A `:loaded` entry goes stale
   after its window; a re-`ensure` then refetches into `:fetching` while keeping
   the old data on screen (that's stale-while-revalidate). An inactive entry —
   one nobody owns — becomes GC-eligible after its own window. And re-`ensure`-ing
   a still-fresh entry is just a cache-hit: no fetch, no dedupe, nothing to see."
  (:require [clojure.string]
            [re-frame.core :as rf]
            ;; Managed HTTP — the one built-in transport for resources and
            ;; mutations. Loading the ns registers the `:rf.http/managed` fx the
            ;; runtime lowers each ensure/refetch onto.
            [re-frame.http.managed]
            ;; The resources runtime. Requiring it at boot wires the hooks and
            ;; registrations; without it, `rf/reg-resource` has nothing to call.
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]))

;; ============================================================================
;; SHARED POLICY
;; ============================================================================

(def stale-after-ms
  "Reads go stale after a minute. Re-`ensure` a stale `:loaded` entry and it
   refetches (stale-while-revalidate); re-`ensure` a fresh one and it's a
   cache-hit."
  60000)

(def gc-after-ms
  "An inactive entry — one with no live owner — becomes GC-eligible five minutes
   after it goes quiet."
  (* 5 60 1000))

;; ============================================================================
;; PAGINATION — every server-visible list parameter lives in `:params`
;; ============================================================================
;;
;; The Conduit list endpoints page with `limit` / `offset`; the UI is 1-indexed
;; with a fixed page size. Here's the nice part: pagination is just declarative
;; on resources. `:page` is another `:params` key and nothing more, so page N and
;; page N+1 are genuinely different cache entries. Back-navigate to a page you've
;; already seen and it's a free cache-hit. `:keep-previous?` on the route entry
;; keeps the current page on screen while the next one loads, so there's no
;; flicker. No `:status` field, no hand-rolled page-cache map — the framework
;; carries all of it. See ../../../docs/resources/how-to/paginate-a-feed.md.

(def page-size
  "The fixed Conduit list page size — the official client's value. The demo stub
   synthesises enough articles that several pages actually exist to page through
   (see realworld-resources.http)."
  10)

(defn page->limit-offset
  "Turn a 1-indexed `:page` (nil → page 1) into the Conduit `limit` / `offset`
   query pair. Pure, and the one place page arithmetic lives — a page is just
   another canonical-params key, so there's no reason to do this maths twice."
  [page]
  (let [p (max 1 (or page 1))]
    {:limit  page-size
     :offset (* (dec p) page-size)}))

(defn- with-pagination
  "Tack the `limit` / `offset` pair (derived from `:page`) onto a list URL,
   whether or not it already has a query string. Keeps the resource `:request`
   fns short while every page stays its own distinct cache key."
  [url page]
  (let [{:keys [limit offset]} (page->limit-offset page)
        sep (if (clojure.string/includes? url "?") "&" "?")]
    (str url sep "limit=" limit "&offset=" offset)))

;; A simple rule runs through all of this: reads retry, writes don't. Each read's
;; `:request` returns the shared `rh/data-fetch-retry` policy in its managed-HTTP
;; args, and `:retry` rides through the resource lowering untouched — so a
;; transport blip, a 5xx, or a timeout retries with backoff + jitter. A 4xx does
;; not; the request shape was valid, so retrying would just be optimism. Mutations
;; stay retry-free. See managed HTTP:
;; ../../../docs/resources/glossary.md#managed-http.

;; ============================================================================
;; PUBLIC READS — :scope :rf.scope/global (same for every viewer)
;; ============================================================================
;;
;; `:scope :rf.scope/global` is an explicit, auditable promise: this read is the
;; same for every user, tenant, and locale. There's no implicit default to lean
;; on — leave the scope off and registration fails loudly rather than guessing.
;; Failing closed beats a silent shared cache. See scope:
;; ../../../docs/resources/glossary.md#scope.

(rf/reg-resource :realworld/articles
  {:doc            "The global article list, optionally filtered by `:tag` and
                    paginated by `:page` (1-indexed). Every server-visible option
                    — both the tag and the page — lives in params, so the
                    tag-filtered list, the unfiltered list, and each page are all
                    distinct cache entries. Back-navigate to a page you've already
                    loaded and it's a cache-hit; the route's `:keep-previous?`
                    keeps the prior page on screen while the next loads."
   :params-schema  [:map
                    [:tag  {:optional true} [:maybe :string]]
                    [:page {:optional true} [:maybe :int]]]
   :data-schema    schema/ArticlesResponse
   :scope          :rf.scope/global
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   ;; Tag the list itself AND every article in it. That way, favoriting one
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
   ;; Tag both the per-article identity and the list identity, so a save or
   ;; favorite invalidates the detail page and the lists in one go.
   :tags           (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (rh/full-url (str "/articles/" slug))}
     :decode  schema/ArticleResponse
     :retry   rh/data-fetch-retry}))

(rf/reg-resource :realworld/comments
  {:doc            "Comments for an article (public). It's a sub-resource of the
                    article, but there's nothing special about it — just an
                    ordinary resource whose params carry the parent's identity
                    (the slug)."
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
  {:doc            "Articles a profile has authored (public). Paginated by
                    `:page`, so each page is its own cache entry."
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
  {:doc            "Articles a profile has favorited (public). GET
                    `/articles?favorited=:username` — the read behind the
                    profile's Favorited-Articles tab. Paginated by `:page`. It
                    tags both its own list identity and every article in it, so
                    the favorite / unfavorite mutations (which stale
                    `[:article slug]`) refetch this list for free — unfavorite an
                    article from the tab and it drops out on the next refetch, no
                    extra wiring required."
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
;; The personalised feed is different from everything above: what it returns
;; depends on WHO is asking. So it can't be `:rf.scope/global` — it carries a
;; session scope instead. Rather than wire "who's the viewer?" into every site by
;; hand, a named resolver states that fact once — `reg-resource-scope
;; :realworld/session` (see scope.cljs) — and the resource just points at it with
;; `:scope {:from-db :realworld/session}`, a reference the runtime resolves
;; against app-db wherever it's used. The payoff:
;;   - a `[:rf.resource/state {:resource :realworld/feed :params {…}}]` sub
;;     resolves the scope itself — no view ever passes a `:scope` payload — and
;;     re-keys reactively the moment you log in or out;
;;   - the home route owns the feed as a plain `:resources` entry carrying
;;     `:scope {:from-db :realworld/session}` (routing.cljs);
;;   - logged out, the reference resolves to nil, fail-closed: the sub becomes a
;;     loud 'scope unresolved' signal, never a quiet read of someone else's cache.
;; See the named resolver in scope.cljs and
;; ../../../docs/resources/how-to/add-auth.md.

(rf/reg-resource :realworld/feed
  {:doc            "The authenticated user's feed (`/articles/feed`). Session-
                    scoped through the named `{:from-db :realworld/session}`
                    resolver, which buys two guarantees: a logged-out user never
                    sees a previous user's feed out of cache, and a login or
                    logout re-keys every live feed subscription on its own.
                    Paginated by `:page`, which rides the params on top of the
                    session scope — so each (scope, page) pair is a distinct cache
                    entry."
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
