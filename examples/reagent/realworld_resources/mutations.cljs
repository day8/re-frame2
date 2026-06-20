(ns realworld-resources.mutations
  "The MUTATION registry — every RealWorld write as a named, causal write to
   remote state that invalidates / patches / populates the cached resource
   reads it affected (Spec 016 §Mutations, EP-0003 §Mutations).

   This is the half the focused `examples/reagent/resources/` lifecycle demo
   does NOT show: the read→write→invalidate→refetch loop end-to-end in a full
   app. A resource is \"a sub you read and a cause you fire\"; a mutation is
   \"a cause you fire and an instance you watch\".

   Each mutation:
   - lowers its `:request` through the SAME managed-HTTP transport resources
     use (the runtime owns reply addressing — the app `:request` MUST NOT
     supply `:request-id` / `:on-success` / `:on-failure`);
   - declares `:invalidates` — the resource `:tags` the write makes stale on
     success, fed straight into the landed `:rf.resource/invalidate-tags`
     (scoped, owner-aware: mounted-and-owned reads refetch, inactive ones go
     stale). The detail page and any list refetch with NO further wiring;
   - some also declare `:populates` — seeding the affected resource entry from
     the write's own reply BEFORE the invalidation, so the change appears
     immediately without waiting for the refetch round-trip.

   Runtime state is keyed by mutation INSTANCE id (not mutation id), so two
   concurrent submissions never clobber each other. A view watches an instance
   through the passive `[:rf.mutation/state {:instance …}]` sub
   (`{:pending? :success? :error? :settled? :result :error :optimistic?}`).

   OPTIMISTIC ROLLBACK (Spec 016 §Optimistic mutations, EP-0019). The
   favorite / unfavorite writes below declare `:optimistic-tags` — the heart
   flips and the count moves on click, BEFORE the request is sent, across every
   cached read showing that article (the detail, every list, the session feed)
   in one tag-addressed apply. The runtime records the inverse itself, so the
   reply settles deterministically: an `:ok` reply COMMITS (the `:populates`
   seed overwrites the optimistic value with the server's), an `:error` reply
   ROLLS BACK (the recorded `:before` is restored verbatim — the heart flips
   back). `:on-conflict :invalidate` (the default) governs a contested rollback:
   if a concurrent write moved the entry while ours was in flight, the stale
   inverse is NOT restored — the read path refetches the authoritative value.

   Writes do NOT retry by default (reads-retry / writes-don't, Spec 014); a
   mutation arms `:retry` only if its `:request` declares it, and none here do."
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]))

;; ============================================================================
;; FAVORITE / UNFAVORITE  —  the optimistic write (EP-0019)
;; ============================================================================
;;
;; Favoriting is the canonical optimistic mutation: a tiny, reversible change
;; (a boolean flip + a count of one) the user expects to land INSTANTLY. The
;; same article appears in many cached reads at once — the detail, the home
;; list, an author's articles, a profile's favorited tab, the session feed —
;; and a favorite must flip in ALL of them the moment the heart is clicked,
;; then settle to the server's authoritative value (and flip BACK everywhere if
;; the write fails).
;;
;; OPTIMISTIC-TAGS — the tag-addressed forward apply (Spec 016 §Optimistic
;; mutations). The author cannot enumerate every cache key showing this article
;; by hand (lists are paginated, scopes differ, entries come and go), so the
;; optimistic apply is TAG-ADDRESSED: it patches every cached entry carrying
;; `[:article slug]` — reusing the SAME tag index `:invalidates` matches
;; against. One descriptor covers the global reads (detail + every list), a
;; second covers the session feed via the same `{:from-db :realworld/session}`
;; resolver the feed resource declares. The detail stores `{:article …}` and
;; the lists store `{:articles […]}`, so the patch fn (`apply-fav` below)
;; handles both envelope shapes. The session target is FAIL-CLOSED: logged out,
;; the resolver returns nil and that target is silently dropped — an optimistic
;; apply writes the cache, so it has a read's leak boundary, never an implicit
;; global write.
;;
;; THE INVERSE IS RUNTIME-RECORDED — the author writes only the FORWARD patch.
;; The runtime snapshots each touched entry's `:before` (verbatim, by reference)
;; and its `:revision` at apply time. The reply then settles deterministically:
;;   - :ok    → COMMIT. `:populates` seeds the detail with the server's full
;;              Article (the authoritative value overwrites the optimistic one),
;;              then `:invalidates` refetches the lists / feed to truth.
;;   - :error → ROLLBACK. The recorded `:before` is restored verbatim — every
;;              heart flips back, every count returns. No manual undo, no
;;              app-db bookkeeping.
;;   - a competing write moved an entry while ours was in flight → `:on-conflict`
;;              (default `:invalidate`) marks that entry stale and lets the read
;;              path refetch, rather than restoring a now-stale inverse.
;;
;; CROSS-SCOPE INVALIDATION IN ONE MUTATION (EP-0016 D2). `:invalidates` is the
;; success-time counterpart: a vector of PER-TARGET DESCRIPTORS, each naming its
;; own scope. The global descriptor refetches the article + lists; the session
;; descriptor refetches the feed via the same resolver. One mutation, two
;; scopes — no app-level cross-scope patch, no home-page watcher.

(defn- toggle-article-fav
  "Toggle one Article map's `:favorited` flag and step `:favoritesCount` by
   ±1. Pure; clamps the count at zero. Shared by the favorite + unfavorite
   forward patches so the optimistic shape is declared once."
  [favorited? article]
  (when article
    (-> article
        (assoc :favorited favorited?)
        (update :favoritesCount (fn [n] (max 0 (+ (or n 0) (if favorited? 1 -1))))))))

(defn- apply-fav
  "The FORWARD optimistic patch over ONE cached entry's `:data`, for any read
   showing this article. Handles both stored shapes: the detail's `{:article
   …}` envelope and a list's `{:articles […]}` envelope (it edits the matching
   article in place by slug, leaving the rest untouched). `favorited?` is the
   desired post-click state. The runtime records the inverse, so this fn never
   has to describe how to undo itself."
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
  "The `:optimistic-tags` plan shared by favorite (`favorited? true`) and
   unfavorite (`favorited? false`): patch every entry tagged `[:article slug]`
   in the global scope (detail + lists) AND in the session scope (the feed),
   the same two scopes `:invalidates` covers. The session target is fail-closed
   — dropped when logged out."
  [favorited? slug]
  [{:scope :rf.scope/global
    :tags  #{[:article slug]}
    :patch #(apply-fav favorited? slug %)}
   {:scope {:from-db :realworld/session}
    :tags  #{[:feed]}
    :patch #(apply-fav favorited? slug %)}])

(rf/reg-mutation :realworld/favorite
  {:doc             "Favorite an article (optimistic). POST /articles/:slug/favorite."
   :params-schema   [:map [:slug :string]]
   :scope           :rf.scope/global
   ;; FORWARD: flip the heart on + bump the count across the detail, every list,
   ;; and the session feed, immediately on click (phase 1.5, before the request).
   :optimistic-tags (fn [{:keys [slug]}] (optimistic-fav-tags true slug))
   ;; COMMIT: the reply is the full updated Article; seed the detail with it so
   ;; the authoritative value (the server's exact count) overwrites the
   ;; optimistic guess. Same `{:article …}` envelope the resource stores, so the
   ;; populated key reads identically to a fetched one (Spec 016 §Populate is an
   ;; authoritative load — the populated detail key is exempt from this same
   ;; mutation's `[:article slug]` refetch).
   :populates       (fn [{:keys [slug]} result]
                      {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global} result})
   ;; RECONCILE: refetch the lists (global) and the feed (session) to truth.
   :invalidates     (fn [{:keys [slug]} _result]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug] [:article-list]}}
                       {:scope {:from-db :realworld/session}
                        :tags  #{[:feed]}}])
   ;; ROLLBACK conflict policy (the default, named here for the dogfood): on a
   ;; failure rollback where a competing write moved a touched entry, refetch it
   ;; rather than restoring a stale inverse.
   :on-conflict     :invalidate}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (rh/full-url (str "/articles/" slug "/favorite"))}
     :decode  schema/ArticleResponse}))

(rf/reg-mutation :realworld/unfavorite
  {:doc             "Unfavorite an article (optimistic). DELETE /articles/:slug/favorite."
   :params-schema   [:map [:slug :string]]
   :scope           :rf.scope/global
   :optimistic-tags (fn [{:keys [slug]}] (optimistic-fav-tags false slug))
   :populates       (fn [{:keys [slug]} result]
                      {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global} result})
   :invalidates     (fn [{:keys [slug]} _result]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug] [:article-list]}}
                       {:scope {:from-db :realworld/session}
                        :tags  #{[:feed]}}])
   :on-conflict     :invalidate}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete :url (rh/full-url (str "/articles/" slug "/favorite"))}
     :decode  schema/ArticleResponse}))

;; ============================================================================
;; FOLLOW / UNFOLLOW
;; ============================================================================
;;
;; Following changes a profile's `:following` flag, so it invalidates that
;; profile read; the reply is a full Profile so `:populates` seeds the banner
;; immediately.

(rf/reg-mutation :realworld/follow
  {:doc           "Follow a user. POST /profiles/:username/follow."
   :params-schema [:map [:username :string]]
   :scope         :rf.scope/global
   ;; Seed the banner from the reply (the whole `{:profile …}` envelope, the
   ;; `:realworld/profile` resource's stored shape) so it flips immediately.
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
;; Posting or deleting a comment changes the article's comment collection, so
;; both invalidate `[:comments slug]`. The mounted article page owns the
;; comments read, so the list refetches automatically. (No `:populates` — the
;; comments collection is a list the refetch re-reads authoritatively, and the
;; post reply is a single Comment, not the whole list.)

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
;; Updating settings PUTs the User and returns the saved User. The example
;; reads the saved user out of the mutation INSTANCE result (`:rf.mutation/
;; result`) and pushes it into the auth slice; there is no profile resource
;; keyed by the current user to populate, so `:invalidates` clears the public
;; `[:profile username]` read so a later profile visit re-reads the new bio.

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
