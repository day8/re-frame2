(ns realworld-resources.scope
  "The resource cache SCOPE for this app — the fail-closed leak boundary,
   expressed as a NAMED resource-scope resolver (`reg-resource-scope`). See
   scope: ../../../docs/resources/glossary.md#scope, and the worked walkthrough
   in ../../../docs/resources/how-to/add-auth.md.

   Scope is the cache's tenant / user / permission boundary, and it MUST fail
   closed: a resource never silently defaults to a shared cache. This example
   reads two KINDS of server-state, so it demonstrates BOTH scope policies:

   - PUBLIC reads — the global article list, a tag-filtered list, an article's
     detail, an author profile, the popular-tags sidebar, an article's comments.
     These are the same for every viewer, so each resource declares the explicit,
     auditable `:scope :rf.scope/global` claim.

   - SESSION reads — the authenticated user's personalised feed
     (`/articles/feed`). What that returns depends on WHO is asking, so it carries
     a session scope `[:rf.scope/session {:username …}]`. A logged-out user must
     never see the previous user's feed from cache.

   ## One named resolver, every site

   The session/feed seam answers one fact — \"who is the current viewer?\" — and a
   named resource-scope resolver states that fact ONCE rather than wiring it by
   hand at every site. `reg-resource-scope :realworld/session` with declared db
   inputs names that fact once, and every site references it as
   `{:from-db :realworld/session}`:

   - the feed RESOURCE declares `:scope {:from-db :realworld/session}`
     (resources.cljs), so a subscription resolves the scope itself — no view
     threads a scope payload, and the sub re-keys reactively across login /
     logout;
   - the home ROUTE declares the feed as a `:resources` entry with
     `:scope {:from-db :realworld/session}` (routing.cljs) — resolved against the
     navigation handler's app-db at route entry, owned by the nav-token, released
     on leave. No `:on-match` event, no app-minted lease;
   - the favourite / unfavourite MUTATIONS carry a per-target invalidation
     descriptor `{:scope {:from-db :realworld/session} :tags #{[:feed]}}`
     (mutations.cljs) — one mutation invalidates global article tags AND the
     session feed, so no app-level cross-scope patch is needed;
   - LOGOUT resolves the concrete old scope with the pure
     `rf/resolve-resource-scope` helper against the coeffect db and clears it
     (auth.cljs).

   `nil` is the fail-closed unresolved condition everywhere: a logged-out
   subscription is the loud \"scope unresolved\" diagnostic, not a silent shared
   read; a logged-out route entry / invalidation descriptor resolves nil and does
   nothing (no feed to reach); logout resolves nil and skips the clear."
  (:require [re-frame.core :as rf]
            ;; Resources ship `reg-resource-scope`; requiring the ns wires the
            ;; hooks so the macro resolves.
            [re-frame.resources]))

;; ============================================================================
;; THE NAMED SESSION SCOPE RESOLVER
;; ============================================================================
;;
;; `reg-resource-scope` registers a PURE resolver under an id; every derived-scope
;; site references it as `{:from-db :realworld/session}`. The declared `:inputs`
;; name the single app-db fact that decides the scope — the authenticated user's
;; `:username` — so the runtime re-resolves the scope only when THAT path changes
;; (login / logout / account switch), and tooling can explain which fact decides a
;; resource's identity. `:resolve` is pure: it derives the canonical session-scope
;; value, or `nil` when logged out (the fail-closed unresolved condition).

(rf/reg-resource-scope :realworld/session
  {:doc     "The current session's resource cache scope — the per-user leak
             boundary made explicit and auditable. Derived from the one app-db
             fact that decides it (the authenticated user's username); nil when
             logged out (fail-closed)."
   :inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username
                [:rf.scope/session {:username username}]))})

;; ============================================================================
;; CONVENIENCE — the concrete value for the few non-resource sites that want it
;; ============================================================================

(defn session-scope
  "The concrete session cache scope for a given user map, or nil when logged
   out — a plain data value `[:rf.scope/session {:username …}]`. The resource
   sites use the named `{:from-db :realworld/session}` reference (resolved by
   the runtime); this helper is kept for the rare site that needs the concrete
   value in hand (e.g. building a non-resource diagnostic)."
  [user]
  (when-let [username (:username user)]
    [:rf.scope/session {:username username}]))
