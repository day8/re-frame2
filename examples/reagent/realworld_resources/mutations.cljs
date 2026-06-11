(ns realworld-resources.mutations
  "The MUTATION registry — every RealWorld write as a named, causal write to
   remote state that invalidates / patches / populates the cached resource
   reads it affected (Spec 016 §Mutations, EP-0003 §Mutations — landed, first
   public-beta gate).

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
   (`{:pending? :success? :error? :settled? :result :error}`).

   Optimistic ROLLBACK is deferred (Spec 016 §Deferred slices) — `:populates`
   here are forward-only seeds, not optimistic-then-rollback. Writes do NOT
   retry by default (reads-retry / writes-don't, Spec 014); a mutation arms
   `:retry` only if its `:request` declares it, and none here do."
  (:require [re-frame.core :as rf]
            [re-frame.http-managed]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]))

;; ============================================================================
;; FAVORITE / UNFAVORITE
;; ============================================================================
;;
;; Favoriting changes the article's favorited flag + count, so it invalidates
;; the article detail AND any list showing it (both carry `[:article slug]`).
;; It also `:populates` the detail entry from the write's own Article reply —
;; the heart change shows immediately, then the invalidation refetches the
;; lists. `:scope :rf.scope/global` is the mutation's resolved execution scope,
;; matching the public reads.
;;
;; CROSS-SCOPE INVALIDATION IN ONE MUTATION (EP-0016 D2). Favoriting affects
;; two KINDS of read living in two scopes: the public article + lists
;; (`:rf.scope/global`) and the authenticated user's personalised feed (the
;; session scope). A bare tag-set `:invalidates` resolves under ONE scope, so a
;; global mutation could never reach the session feed — the variant used to
;; paper over that with an explicit app-level session-scoped invalidation fired
;; from a home-page reaction (the rf2-em5ab8 interim patch). EP-0016 retires
;; that: `:invalidates` is a vector of PER-TARGET DESCRIPTORS, each naming its
;; own scope. One descriptor invalidates the global article tags; a second
;; names the session feed via the same `{:from-db :realworld/session}` resolver
;; the feed resource declares, resolved at settle time. One mutation, two
;; scopes — no app-level cross-scope patch, no home-page watcher.

(rf/reg-mutation :realworld/favorite
  {:doc           "Favorite an article. POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :post :url (rh/full-url (str "/articles/" slug "/favorite"))}
                     :decode  schema/ArticleResponse})
   ;; Seed the detail entry from the reply so the heart flips immediately. The
   ;; seeded value matches the `:realworld/article` resource's stored shape —
   ;; the whole `{:article …}` envelope its `:decode schema/ArticleResponse`
   ;; produces — so the populated entry reads identically to a fetched one
   ;; (Spec 016 §Populate is an authoritative load: the populated detail key is
   ;; exempt from this same mutation's `[:article slug]` refetch).
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global} result})
   ;; Per-target descriptors: global article tags in the global scope, the feed
   ;; tag in the session scope. The favourited list a profile shows also drops/
   ;; re-orders via the `[:article slug]` global tag it carries.
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])})

(rf/reg-mutation :realworld/unfavorite
  {:doc           "Unfavorite an article. DELETE /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :delete :url (rh/full-url (str "/articles/" slug "/favorite"))}
                     :decode  schema/ArticleResponse})
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global} result})
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])})

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
   :request       (fn [{:keys [username]} _ctx]
                    {:request {:method :post :url (rh/full-url (str "/profiles/" username "/follow"))}
                     :decode  schema/ProfileResponse})
   ;; Seed the banner from the reply (the whole `{:profile …}` envelope, the
   ;; `:realworld/profile` resource's stored shape) so it flips immediately.
   :populates     (fn [{:keys [username]} result]
                    {{:resource :realworld/profile :params {:username username} :scope :rf.scope/global} result})
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})})

(rf/reg-mutation :realworld/unfollow
  {:doc           "Unfollow a user. DELETE /profiles/:username/follow."
   :params-schema [:map [:username :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [username]} _ctx]
                    {:request {:method :delete :url (rh/full-url (str "/profiles/" username "/follow"))}
                     :decode  schema/ProfileResponse})
   :populates     (fn [{:keys [username]} result]
                    {{:resource :realworld/profile :params {:username username} :scope :rf.scope/global} result})
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})})

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
   :request       (fn [{:keys [slug body]} _ctx]
                    {:request {:method :post
                               :url    (rh/full-url (str "/articles/" slug "/comments"))
                               :body   {:comment {:body body}}}
                     :decode  schema/CommentResponse})
   :invalidates   (fn [{:keys [slug]} _result] #{[:comments slug]})})

(rf/reg-mutation :realworld/delete-comment
  {:doc           "Delete a comment. DELETE /articles/:slug/comments/:id."
   :params-schema [:map [:slug :string] [:id :int]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [slug id]} _ctx]
                    {:request {:method :delete
                               :url    (rh/full-url (str "/articles/" slug "/comments/" id))}
                     :decode  :auto})
   :invalidates   (fn [{:keys [slug]} _result] #{[:comments slug]})})

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
   :request       (fn [{:keys [username email bio image password]} _ctx]
                    {:request {:method :put
                               :url    (rh/full-url "/user")
                               :body   {:user (cond-> {:username username :email email
                                                       :bio bio :image image}
                                                (seq password) (assoc :password password))}}
                     :decode  schema/UserResponse})
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})})
