(ns realworld-resources.mutations
  "The mutation registry — every write in the app, expressed as a named, causal
   write to remote state that then invalidates / patches / populates whichever
   cached reads it touched. See ../../../docs/resources/glossary.md#mutation and
   ../../../docs/resources/how-to/invalidate-after-a-mutation.md.

   A handy way to hold the two halves in your head: a resource is 'a sub you read
   and a cause you fire'; a mutation is 'a cause you fire and an instance you
   watch'. Put them together and you get the read→write→invalidate→refetch loop,
   end to end.

   Every mutation here does the same three-ish things:
   - it lowers its `:request` through the same managed-HTTP transport the reads
     use. The runtime owns reply addressing, so your `:request` must not supply
     `:request-id` / `:on-success` / `:on-failure` — leave those to the framework;
   - it declares `:invalidates` — the resource `:tags` this write makes stale on
     success. It's scoped and owner-aware: mounted, owned reads refetch; inactive
     ones simply go stale. The detail page and any affected list refetch with no
     further wiring from you;
   - some also declare `:populates` — seeding the affected entry straight from the
     write's own reply, before the invalidation, so the change shows up
     immediately instead of waiting on a refetch round-trip.

   Runtime state is keyed by mutation INSTANCE id, not mutation id, so two
   submissions in flight at once never step on each other. A view watches an
   instance through the passive `[:rf/mutation {:instance …}]` sub
   (`{:pending? :success? :error? :settled? :result :error :optimistic?}`).

   Optimistic rollback is the fun part
   (../../../docs/resources/glossary.md#optimistic-update--rollback). The
   favorite / unfavorite writes below declare `:optimistic-tags`: the heart flips
   and the count moves the instant you click, before the request is even sent,
   across every cached read showing that article (the detail, every list, the
   session feed) in one tag-addressed apply. You only write the forward change —
   the runtime records the inverse for you, so the reply settles by itself. An
   `:ok` reply commits (the `:populates` seed overwrites your optimistic guess
   with the server's truth); an `:error` reply rolls back (the recorded `:before`
   is restored verbatim and the heart flips back, no apology needed).
   `:on-conflict :invalidate` (the default) handles the awkward case: if a
   competing write moved the entry while yours was in flight, the now-stale
   inverse is NOT restored — the read path just refetches the authoritative value.

   And, as everywhere: writes don't retry by default. A mutation arms `:retry`
   only if its `:request` asks for it, and none of these do."
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]))

;; ============================================================================
;; FAVORITE / UNFAVORITE  —  the optimistic write
;; ============================================================================
;;
;; Favoriting is the textbook optimistic mutation: a tiny, reversible change — a
;; boolean flip and a count of one — that the user expects to land the instant
;; they click. The catch is that the same article shows up in a lot of places at
;; once: the detail page, the home list, an author's articles, a profile's
;; favorited tab, the session feed. The heart has to flip in all of them
;; immediately, then settle to whatever the server says — and flip back
;; everywhere if the write fails. That's a lot to coordinate by hand. So we don't.
;;
;; `:optimistic-tags` is the forward apply, addressed by tag. You can't realistically
;; list every cache key showing this article — lists are paginated, scopes differ,
;; entries come and go — so instead of naming keys, you name a tag: patch every
;; cached entry carrying `[:article slug]`, reusing the very same tag index that
;; `:invalidates` matches against. One descriptor covers the global reads (detail
;; plus every list); a second covers the session feed, via the same
;; `{:from-db :realworld/session}` resolver the feed resource itself uses. The
;; detail stores `{:article …}` while the lists store `{:articles […]}`, so the
;; patch fn (`apply-fav`, below) handles both envelope shapes. The session target
;; is fail-closed: logged out, the resolver returns nil and that target quietly
;; drops — an optimistic apply writes the cache, so it respects a read's leak
;; boundary and never sneaks in an implicit global write.
;;
;; You write only the forward patch; the runtime records the inverse. At apply
;; time it snapshots each touched entry's `:before` and `:revision`, and from then
;; on the reply settles itself:
;;   - :ok    → commit. `:populates` seeds the detail with the server's full
;;              Article (truth overwrites your optimistic guess), then
;;              `:invalidates` refetches the lists / feed.
;;   - :error → rollback. The recorded `:before` goes back verbatim — every heart
;;              flips back, every count returns. No manual undo, no app-db
;;              bookkeeping on your side.
;;   - someone moved an entry while yours was in flight → `:on-conflict` (default
;;              `:invalidate`) marks that entry stale and lets the read path
;;              refetch, instead of restoring an inverse that's now out of date.
;;
;; One mutation, two scopes. `:invalidates` is the success-time counterpart of the
;; optimistic apply: a vector of per-target descriptors, each naming its own
;; scope. The global descriptor refetches the article and lists; the session
;; descriptor refetches the feed through the same resolver. No app-level
;; cross-scope patching, no home-page watcher keeping things in sync.

(defn- toggle-article-fav
  "Flip one Article's `:favorited` flag and nudge `:favoritesCount` by ±1. Pure,
   and it clamps the count at zero so an over-eager unfavorite can't go negative.
   Shared by the favorite and unfavorite forward patches, so the optimistic shape
   is written down exactly once."
  [favorited? article]
  (when article
    (-> article
        (assoc :favorited favorited?)
        (update :favoritesCount (fn [n] (max 0 (+ (or n 0) (if favorited? 1 -1))))))))

(defn- apply-fav
  "The forward optimistic patch over one cached entry's `:data`, for any read
   showing this article. It copes with both stored shapes: the detail's
   `{:article …}` envelope and a list's `{:articles […]}` envelope, editing the
   matching article in place by slug and leaving everything else alone.
   `favorited?` is the desired post-click state. Note there's no undo logic here —
   the runtime records the inverse, so this fn never has to explain how to take
   itself back."
  [favorited? slug data]
  (cond-> data
    (contains? data :article)
    (update :article #(toggle-article-fav favorited? %))

    (contains? data :articles)
    (update :articles
            (fn [articles]
              (mapv (fn [a]
                      (if (= slug (:slug a)) (toggle-article-fav favorited? a) a))
                    articles)))))

(defn- optimistic-fav-tags
  "The `:optimistic-tags` plan, shared by favorite (`favorited? true`) and
   unfavorite (`favorited? false`): patch every entry tagged `[:article slug]` in
   the global scope (detail + lists) and in the session scope (the feed) — the
   same two scopes `:invalidates` covers. The session target is fail-closed, so
   it's simply dropped when logged out."
  [favorited? slug]
  [{:scope :rf.scope/global
    :tags  #{[:article slug]}
    :patch #(apply-fav favorited? slug %)}
   {:scope {:from-db :realworld/session}
    :tags  #{[:feed]}
    :patch #(apply-fav favorited? slug %)}])

(defn- fav-invalidates
  "Shared `:invalidates` body for favorite/unfavorite. `slug` always reaches the
   detail + every list that already has it cached. `username` — the ACTING user,
   who this article's own Favorited-Articles tab belongs to — is optional in
   `:params-schema` (older/direct callers may omit it), so its list-identity tag
   only goes in when present. This is the one tag `slug` can't stand in for: a
   newly-favorited article isn't yet a member of any cached Favorited-Articles
   page, so `[:article slug]` never reaches it there. `:invalidates` has no
   `:db`/ctx of its own to source this from, so `:ui/favorite` threads it in via
   `:params` from the acting user's session at the call site — not from the
   decoded reply, whose `:author` here names the ARTICLE's author, not the
   person who clicked the heart."
  [{:keys [slug username]}]
  [{:scope :rf.scope/global
    :tags  (cond-> #{[:article slug] [:article-list]}
             username (conj [:favorited-articles username]))}
   {:scope {:from-db :realworld/session}
    :tags  #{[:feed]}}])

(rf/reg-mutation :realworld/favorite
  {:doc             "Favorite an article (optimistic). POST /articles/:slug/favorite."
   :params-schema   [:map [:slug :string] [:username {:optional true} :string]]
   :scope           :rf.scope/global
   ;; Forward: flip the heart on and bump the count across the detail, every list,
   ;; and the session feed — immediately on click, before the request goes out.
   :optimistic-tags (fn [{:keys [slug]}] (optimistic-fav-tags true slug))
   ;; Commit: the reply is the full updated Article, so seed the detail with it and
   ;; let the server's exact count overwrite the optimistic guess. It's the same
   ;; `{:article …}` envelope the resource stores, so the populated key reads just
   ;; like a fetched one — and because it's authoritative, it's exempt from this
   ;; same mutation's `[:article slug]` refetch.
   :populates       (fn [{:keys [slug]} result]
                      {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global} result})
   ;; Reconcile: refetch the lists (global) and the feed (session) to truth. See
   ;; `fav-invalidates` for why `[:favorited-articles username]` needs threading.
   :invalidates     (fn [params _result] (fav-invalidates params))
   ;; The conflict policy. It's the default, spelled out here so it's visible: if a
   ;; failure rollback finds that a competing write already moved a touched entry,
   ;; refetch it rather than restoring an inverse that's now stale.
   :on-conflict     :invalidate}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (rh/full-url (str "/articles/" slug "/favorite"))}
     :decode  schema/ArticleResponse}))

(rf/reg-mutation :realworld/unfavorite
  {:doc             "Unfavorite an article (optimistic). DELETE /articles/:slug/favorite."
   :params-schema   [:map [:slug :string] [:username {:optional true} :string]]
   :scope           :rf.scope/global
   :optimistic-tags (fn [{:keys [slug]}] (optimistic-fav-tags false slug))
   :populates       (fn [{:keys [slug]} result]
                      {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global} result})
   ;; The unfavorited article is already a tagged member of any cached
   ;; Favorited-Articles page it appears on, so `[:article slug]` alone would
   ;; reach it there too — naming `[:favorited-articles username]` (via the same
   ;; `fav-invalidates`) just keeps the pair symmetric and states the real
   ;; intent explicitly, rather than relying on member-tag coincidence.
   :invalidates     (fn [params _result] (fav-invalidates params))
   :on-conflict     :invalidate}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete :url (rh/full-url (str "/articles/" slug "/favorite"))}
     :decode  schema/ArticleResponse}))

;; ============================================================================
;; FOLLOW / UNFOLLOW
;; ============================================================================
;;
;; Following flips a profile's `:following` flag, so it invalidates that profile
;; read. The reply comes back as a full Profile, so `:populates` can seed the
;; banner right away rather than waiting for the refetch.

(rf/reg-mutation :realworld/follow
  {:doc           "Follow a user. POST /profiles/:username/follow."
   :params-schema [:map [:username :string]]
   :scope         :rf.scope/global
   ;; Seed the banner straight from the reply — the whole `{:profile …}` envelope,
   ;; which is exactly the `:realworld/profile` resource's stored shape — so it
   ;; flips immediately.
   :populates     (fn [{:keys [username]} result]
                    {{:resource :realworld/profile :params {:username username} :scope :rf.scope/global} result})
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})}
  (fn [{:keys [username]} _ctx]
    {:request {:method :post :url (rh/full-url (str "/profiles/" username "/follow"))}
     :decode  schema/ProfileResponse}))

(rf/reg-mutation :realworld/unfollow
  {:doc           "Unfollow a user. DELETE /profiles/:username/follow."
   :params-schema [:map [:username :string]]
   :scope         :rf.scope/global
   :populates     (fn [{:keys [username]} result]
                    {{:resource :realworld/profile :params {:username username} :scope :rf.scope/global} result})
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})}
  (fn [{:keys [username]} _ctx]
    {:request {:method :delete :url (rh/full-url (str "/profiles/" username "/follow"))}
     :decode  schema/ProfileResponse}))

;; ============================================================================
;; POST / DELETE COMMENT
;; ============================================================================
;;
;; Posting or deleting a comment changes the article's comment collection, so both
;; invalidate `[:comments slug]`. The mounted article page owns the comments read,
;; so the list refetches on its own. No `:populates` here, on purpose: the
;; comments collection is a list the refetch re-reads authoritatively, and the
;; post reply is a single Comment, not the whole list — so seeding from it would
;; only give a partial picture.

(rf/reg-mutation :realworld/post-comment
  {:doc           "Post a comment. POST /articles/:slug/comments."
   :params-schema [:map [:slug :string] [:body :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result] #{[:comments slug]})}
  (fn [{:keys [slug body]} _ctx]
    {:request {:method :post
               :url    (rh/full-url (str "/articles/" slug "/comments"))
               :body   {:comment {:body body}}}
     :decode  schema/CommentResponse}))

(rf/reg-mutation :realworld/delete-comment
  {:doc           "Delete a comment. DELETE /articles/:slug/comments/:id."
   :params-schema [:map [:slug :string] [:id :int]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result] #{[:comments slug]})}
  (fn [{:keys [slug id]} _ctx]
    {:request {:method :delete
               :url    (rh/full-url (str "/articles/" slug "/comments/" id))}
     :decode  :auto}))

;; ============================================================================
;; SETTINGS UPDATE
;; ============================================================================
;;
;; Updating settings PUTs the User and gets the saved User back. The continuation
;; reads that out of the mutation instance result (`:rf.mutation/result`) and
;; pushes it into the auth slice. There's no profile resource keyed by the current
;; user to populate, so instead `:invalidates` clears the public
;; `[:profile username]` read — that way a later visit to your own profile
;; re-reads the new bio.

(rf/reg-mutation :realworld/update-settings
  {:doc           "Update the current user's settings. PUT /user."
   :params-schema [:map
                   [:username :string]
                   [:email    :string]
                   [:bio      [:maybe :string]]
                   [:image    [:maybe :string]]
                   [:password {:optional true} [:maybe :string]]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})}
  (fn [{:keys [username email bio image password]} _ctx]
    {:request {:method :put
               :url    (rh/full-url "/user")
               :body   {:user (cond-> {:username username :email email
                                       :bio bio :image image}
                                (seq password) (assoc :password password))}}
     :decode  schema/UserResponse}))
