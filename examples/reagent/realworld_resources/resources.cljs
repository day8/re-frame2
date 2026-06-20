(ns realworld-resources.resources
  "The RESOURCE registry — every RealWorld read as a named, cached,
   runtime-managed server-state read (Spec 016 §Public API / §Resource
   registration spec).

   This is the half of the example that replaces the `:rf.http/managed`
   sibling's hand-rolled Pattern-RemoteData slices (`{:status :data :error
   :loaded-at :attempt}` per read). Here each read is `reg-resource`d once;
   route entry / events CAUSE the fetch, and views read the runtime cache
   PASSIVELY through `[:rf.resource/*]` subs. No `:status` / `:loading?`
   app-db fields exist for these reads — the framework owns the lifecycle.

   Identity = `[scope resource-id canonical-params]` (Spec 016 §Resource
   identity). Every variable that changes the remote read lives in `:params`
   (slug, username, tag); `:scope` is the leak boundary (see
   realworld-resources.scope). `:tags` let a WRITE invalidate the right reads
   (see realworld-resources.mutations) — that is the read→write→invalidate→
   refetch loop this whole variant exists to show.

   `:stale-after-ms` / `:gc-after-ms` are exercised so the lifecycle is real:
   a `:loaded` entry goes stale after the window (a re-`ensure` then refetches
   into `:fetching`, keeping prior data visible — stale-while-revalidate), and
   an inactive entry (no owner) is GC-eligible after its window. A fresh
   re-`ensure` is a cache-hit (no fetch, no dedupe) — `:rf.resource/cache-hit`.

   STATUS. Resources is a POST-V1 optional artefact and the read-resource
   runtime + mutations have LANDED (EP-0003, final on main 2026-06-11), so all
   of this runs live. The example tree is test-free."
  (:require [clojure.string]
            [re-frame.core :as rf]
            ;; Managed HTTP ships in day8/re-frame2-http — the single built-in
            ;; resource/mutation transport (Spec 016 §Transport). Loading the
            ;; ns registers the `:rf.http/managed` fx the runtime lowers each
            ;; ensure/refetch onto.
            [re-frame.http.managed]
            ;; Resources ship in day8/re-frame2-resources. Requiring the ns at
            ;; app boot wires the late-bind hooks + registrations; without it,
            ;; `rf/reg-resource` throws :rf.error/resources-artefact-missing.
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
   inactive (Spec 016 §Stale and GC scheduling)."
  (* 5 60 1000))

;; ============================================================================
;; PAGINATION — every server-visible list parameter lives in `:params`
;; ============================================================================
;;
;; The Conduit list endpoints page with `limit` / `offset`; the UI is
;; 1-indexed and the page size is fixed (Spec 016 §Paginated and previous
;; data). The headline this exercises: pagination is DECLARATIVE on resources.
;; The `:page` is just another `:params` key, so page N and page N+1 are
;; DISTINCT cache entries (canonical-params identity) — back-navigating to a
;; previously-loaded page is a cache-hit (no fetch), and `:keep-previous?` on
;; the route entry keeps the prior page visible while the next first-loads
;; (no flicker). No `:status` / `:loading?` app-db field, no manual page-cache
;; map — the framework owns it.

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

;; Reads retry / writes don't (Spec 014). Each read's `:request` returns the
;; shared `rh/data-fetch-retry` policy in its managed-HTTP args; `:retry`
;; passes through the resource lowering UNCHANGED (Spec 016 §Transport), so a
;; transport blip / 5xx / timeout retries with backoff+jitter (NOT a 4xx — the
;; request shape was valid). Mutations (writes) stay retry-free.

;; ============================================================================
;; PUBLIC READS — :scope :rf.scope/global (same for every viewer)
;; ============================================================================
;;
;; The `:scope :rf.scope/global` here is the explicit, AUDITABLE claim that
;; this read is identical for every user/tenant/locale (Spec 016 §Scope
;; resolution). There is NO implicit default — a missing scope policy is a
;; loud :rf.error/resource-missing-scope-policy at registration. Xray
;; enumerates every `:rf.scope/global` resource as the standing
;; security-review list.

(rf/reg-resource :realworld/articles
  {:doc            "The global article list, optionally filtered by `:tag` and
                    paginated by `:page` (1-indexed). EVERY server-visible
                    option (the tag AND the page) is in params, so a
                    tag-filtered list, the unfiltered list, and each page are
                    DISTINCT cache entries (Spec 016 §Paginated and previous
                    data). Back-navigating to a previously-loaded page is a
                    cache-hit; the route's `:keep-previous?` keeps the prior
                    page visible while the next first-loads."
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
                    the parent identity (Spec 016 §Sub-resources are ordinary
                    resources)."
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
;; carries a session scope rather than `:rf.scope/global`. EP-0016 names that
;; scope ONCE — `reg-resource-scope :realworld/session` (see scope.cljs) — and
;; the resource declares `:scope {:from-db :realworld/session}`: a derived-scope
;; REFERENCE the runtime resolves at every site against app-db.
;;
;; This is the EP-0016 idiom that replaces the prior `:rf.scope/from-caller`
;; hand-wiring (where the route ensured the feed from an event and every view
;; threaded a `:session/scope` payload). Now:
;;   - a `[:rf.resource/state {:resource :realworld/feed :params {…}}]` sub
;;     resolves the scope ITSELF — no view passes a `:scope` payload — and
;;     re-keys reactively across login / logout (Spec 016 §A `{:from-db …}`
;;     subscription re-keys when the resolver's inputs change);
;;   - the home route owns the feed as a declarative `:resources` entry with
;;     `:scope {:from-db :realworld/session}` (routing.cljs);
;;   - logged out, the reference resolves nil — fail-closed: the sub is the
;;     loud "scope unresolved" condition, never a silent shared-cache read.

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
