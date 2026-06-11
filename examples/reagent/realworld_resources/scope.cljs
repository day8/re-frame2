(ns realworld-resources.scope
  "The resource cache SCOPE for this app — the fail-closed leak boundary
   (Spec 016 §Scope resolution).

   Scope is the cache's tenant / user / permission boundary, and it MUST fail
   closed: a resource never silently defaults to a shared cache. This example
   reads two KINDS of server-state, so it demonstrates BOTH scope policies:

   - PUBLIC reads — the global article list, a tag-filtered list, an article's
     detail, an author profile, the popular-tags sidebar, an article's
     comments. These are the same for every viewer, so each resource declares
     the explicit, auditable `:scope :rf.scope/global` claim.

   - SESSION reads — the authenticated user's personalised feed (`/articles/
     feed`). What that returns depends on WHO is asking, so it carries an
     explicit session scope `[:rf.scope/session {:username …}]`. A logged-out
     user must never see the previous user's feed from cache.

   The read-side seam (Spec 016 §Subscription-side scope resolution) is the
   load-bearing bit: a subscription is PURE, so it can't run a (route, ctx)
   resolver. If a route ensured the feed under a session scope but a view
   subscribed WITHOUT a scope, the sub would resolve a different scope and read
   `:idle` forever — a permanent skeleton with no error. Resources close that
   the re-frame2 way: loudly. So the view derives the SAME session scope from
   app-db through this sub and passes it on the subscription payload.

   The clean recipe (docs/guide/27-resources §Subscription-side resolution):
   derive the session scope from app-db in a sub, and pass it on every
   session-scoped `:rf.resource/*` event AND subscription so the entry the
   route ensured and the entry the view reads are the SAME scoped key."
  (:require [re-frame.core :as rf]))

(defn session-scope
  "The session cache scope for the currently-authenticated user, or nil when
   logged out. A plain data value (`[:rf.scope/session {:username …}]`) — the
   leak boundary made explicit and auditable. Used by the route `:scope`
   resolver (for the personalised feed) and the matching view subscription."
  [user]
  (when-let [username (:username user)]
    [:rf.scope/session {:username username}]))

(rf/reg-sub :session/scope
  {:doc "The current session's resource cache scope (or nil when logged out),
         derived from the auth slice. A view passes this on every
         session-scoped `:rf.resource/state` subscription so it reads the SAME
         scoped key the owning route ensured under (Spec 016
         §Subscription-side scope resolution) — never a silent cross-scope
         `:idle`."}
  (fn [db _]
    (session-scope (get-in db [:auth :user]))))
