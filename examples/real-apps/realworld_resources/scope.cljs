(ns realworld-resources.scope
  "The resource cache SCOPE for this app — the fail-closed leak boundary,
   written as a named resource-scope resolver (`reg-resource-scope`). See scope:
   ../../../docs/resources/glossary.md#scope, and the worked walkthrough in
   ../../../docs/resources/how-to/add-auth.md.

   Scope is the cache's tenant / user / permission boundary, and the cardinal
   rule is that it fails closed: a resource never silently falls back to a shared
   cache. This app reads two kinds of server-state, which makes it a nice place to
   see both scope policies side by side:

   - Public reads — the global article list, a tag-filtered list, an article's
     detail, an author profile, the popular-tags sidebar, an article's comments.
     These look the same to every viewer, so each resource makes the explicit,
     auditable `:scope :rf.scope/global` claim.

   - Session reads — the authenticated user's personalised feed
     (`/articles/feed`). What that returns depends on who's asking, so it carries
     a session scope `[:rf.scope/session {:username …}]`. A logged-out user must
     never catch a glimpse of the previous user's feed out of cache.

   ## One named resolver, every site

   The whole session/feed seam turns on a single fact — 'who is the current
   viewer?' — and rather than answer it by hand at every site, a named resolver
   answers it once. `reg-resource-scope :realworld/session`, with declared db
   inputs, states that fact a single time, and every site just points at it with
   `{:from-db :realworld/session}`:

   - the feed RESOURCE declares `:scope {:from-db :realworld/session}`
     (resources.cljs), so a subscription resolves the scope itself — no view ever
     threads a scope payload, and the sub re-keys reactively across login/logout;
   - the home ROUTE declares the feed as a `:resources` entry with
     `:scope {:from-db :realworld/session}` (routing.cljs) — resolved against the
     navigation handler's app-db at route entry, owned by the nav-token, released
     on leave. No `:on-match` event, no app-minted lease;
   - the favourite / unfavourite MUTATIONS carry a per-target invalidation
     descriptor `{:scope {:from-db :realworld/session} :tags #{[:feed]}}`
     (mutations.cljs) — so one mutation can invalidate the global article tags AND
     the session feed, with no app-level cross-scope patching;
   - LOGOUT resolves the concrete old scope with the pure
     `rf/resolve-resource-scope` helper against the coeffect db and clears it
     (auth.cljs).

   And everywhere, `nil` is the fail-closed 'unresolved' answer. A logged-out
   subscription becomes a loud 'scope unresolved' signal rather than a quiet read
   of shared data; a logged-out route entry or invalidation descriptor resolves
   nil and simply does nothing (no feed to reach); logout resolves nil and skips
   the clear. One idea, applied consistently."
  (:require [re-frame.core :as rf]
            ;; Resources ships `reg-resource-scope`; requiring the ns wires up the
            ;; hooks so the macro has something to resolve against.
            [re-frame.resources]))

;; ============================================================================
;; THE NAMED SESSION SCOPE RESOLVER
;; ============================================================================
;;
;; `reg-resource-scope` registers a pure resolver under an id, and every
;; derived-scope site points at it with `{:from-db :realworld/session}`. Per the
;; canonical 3-slot grammar the resolver fn is the value (third) slot, and the
;; metadata middle slot's declared `:inputs` name the one app-db fact that
;; decides the scope — the authenticated user's `:username` — which earns two
;; things: the runtime re-resolves the scope only when that exact path changes
;; (login, logout, account switch), and tooling can tell you precisely which fact
;; decides a resource's identity. The resolver is pure: it builds the canonical
;; session-scope value, or returns `nil` when logged out (the fail-closed
;; 'unresolved' answer).

(rf/reg-resource-scope :realworld/session
  {:doc    "The current session's resource cache scope — the per-user leak
            boundary, made explicit and auditable. Derived from the single
            app-db fact that decides it (the authenticated user's username), and
            nil when logged out (fail-closed)."
   :inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username
      [:rf.scope/session {:username username}])))

;; ============================================================================
;; CONVENIENCE — the concrete value for the few non-resource sites that want it
;; ============================================================================

(defn session-scope
  "The concrete session cache scope for a given user map, or nil when logged out
   — just a plain data value, `[:rf.scope/session {:username …}]`. The resource
   sites all use the named `{:from-db :realworld/session}` reference and let the
   runtime resolve it; this helper is here for the rare spot that needs the
   concrete value in hand, like building a non-resource diagnostic."
  [user]
  (when-let [username (:username user)]
    [:rf.scope/session {:username username}]))
