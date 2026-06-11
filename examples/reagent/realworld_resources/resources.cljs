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
   of this runs live. The example tree is test-free (rf2-8cevm)."
  (:require [re-frame.core :as rf]
            ;; Managed HTTP ships in day8/re-frame2-http — the single built-in
            ;; resource/mutation transport (Spec 016 §Transport). Loading the
            ;; ns registers the `:rf.http/managed` fx the runtime lowers each
            ;; ensure/refetch onto.
            [re-frame.http-managed]
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
  {:doc            "The global article list, optionally filtered by `:tag`.
                    Every server-visible option (the tag) is in params, so a
                    tag-filtered list and the unfiltered list are DISTINCT
                    cache entries (Spec 016 §Paginated and previous data)."
   :params-schema  [:map [:tag {:optional true} [:maybe :string]]]
   :data-schema    schema/ArticlesResponse
   :scope          :rf.scope/global
   :request        (fn [{:keys [tag]} _ctx]
                     {:request {:method :get
                                :url    (rh/full-url (if tag
                                                       (str "/articles?tag=" tag)
                                                       "/articles"))}
                      :decode  schema/ArticlesResponse})
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   ;; Tag the list identity AND every article it contains, so favoriting an
   ;; article (which tags `[:article slug]`) invalidates any list showing it.
   :tags           (fn [_params data]
                     (into #{[:article-list]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))})

(rf/reg-resource :realworld/article
  {:doc            "Article detail by slug (public)."
   :params-schema  [:map [:slug :string]]
   :data-schema    schema/ArticleResponse
   :scope          :rf.scope/global
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (rh/full-url (str "/articles/" slug))}
                      :decode  schema/ArticleResponse})
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   ;; Tag BOTH the per-article identity and the list identity so a save /
   ;; favorite invalidates the detail and the lists together.
   :tags           (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})})

(rf/reg-resource :realworld/comments
  {:doc            "Comments for an article (public). A sub-resource of the
                    article modelled as an ordinary resource whose params carry
                    the parent identity (Spec 016 §Sub-resources are ordinary
                    resources)."
   :params-schema  [:map [:slug :string]]
   :data-schema    schema/CommentsResponse
   :scope          :rf.scope/global
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (rh/full-url (str "/articles/" slug "/comments"))}
                      :decode  schema/CommentsResponse})
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [{:keys [slug]} _data] #{[:comments slug]})})

(rf/reg-resource :realworld/profile
  {:doc            "A user's public profile banner (public)."
   :params-schema  [:map [:username :string]]
   :data-schema    schema/ProfileResponse
   :scope          :rf.scope/global
   :request        (fn [{:keys [username]} _ctx]
                     {:request {:method :get :url (rh/full-url (str "/profiles/" username))}
                      :decode  schema/ProfileResponse})
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [{:keys [username]} _data] #{[:profile username]})})

(rf/reg-resource :realworld/author-articles
  {:doc            "Articles authored by a profile (public)."
   :params-schema  [:map [:username :string]]
   :data-schema    schema/ArticlesResponse
   :scope          :rf.scope/global
   :request        (fn [{:keys [username]} _ctx]
                     {:request {:method :get :url (rh/full-url (str "/articles?author=" username))}
                      :decode  schema/ArticlesResponse})
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [{:keys [username]} data]
                     (into #{[:author-articles username]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))})

(rf/reg-resource :realworld/tags
  {:doc            "The popular-tags sidebar (public)."
   :params-schema  [:map]
   :data-schema    schema/TagsResponse
   :scope          :rf.scope/global
   :request        (fn [_params _ctx]
                     {:request {:method :get :url (rh/full-url "/tags")}
                      :decode  schema/TagsResponse})
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [_params _data] #{[:tags]})})

;; ============================================================================
;; SESSION READ — an explicit session scope (whose feed?)
;; ============================================================================
;;
;; The authenticated user's personalised feed depends on WHO is asking, so it
;; carries a session scope rather than `:rf.scope/global`. The route supplies
;; the concrete scope via its `:scope` resolver (reading the auth slice from
;; ctx); the view supplies the SAME scope on its subscription payload via the
;; `:session/scope` sub (Spec 016 §Subscription-side scope resolution). The
;; declared spec-side policy is `:rf.scope/from-caller`: the scope MUST come
;; from the use site (the route resolver / the sub payload), and a reach with
;; no scope is a loud use-time error — never a silent shared-cache read.

(rf/reg-resource :realworld/feed
  {:doc            "The authenticated user's feed (`/articles/feed`). Session-
                    scoped: a logged-out user must never see a prior user's
                    feed from cache."
   :params-schema  [:map]
   :data-schema    schema/ArticlesResponse
   :scope          :rf.scope/from-caller
   :request        (fn [_params _ctx]
                     {:request {:method :get :url (rh/full-url "/articles/feed")}
                      :decode  schema/ArticlesResponse})
   :stale-after-ms stale-after-ms
   :gc-after-ms    gc-after-ms
   :tags           (fn [_params data]
                     (into #{[:feed]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))})
