(ns realworld-resources.scope
  "The resource cache SCOPE for this app — the fail-closed leak boundary,
   written as named resource-scope resolvers (`reg-resource-scope`). See scope:
   ../../../docs/resources/glossary.md#scope, and the worked walkthrough in
   ../../../docs/core/how-to/add-auth.md.

   Scope is the cache's tenant / user / permission boundary, and the cardinal
   rule is that it fails closed: a resource never silently falls back to a shared
   cache. The lesson worth internalising: **'public' is an access policy, not a
   cache-identity proof.** An endpoint can be readable anonymously and STILL
   return bytes that vary with the (optional) authenticated viewer — Conduit's
   articles carry a per-viewer `favorited` flag, profiles a per-viewer
   `following` flag (see `realworld-shared.schema`). Every fact that can change
   the response bytes has to participate in the cache identity, or one viewer's
   representation leaks to the next. So this app reads three kinds of
   server-state, with three scope policies side by side:

   - Truly invariant reads — the popular-tags sidebar (`/tags`). The same bytes
     for every viewer, forever. This is the ONLY read that earns the explicit,
     auditable `:scope :rf.scope/global` claim.

   - Optional-auth reads — the article list, a tag-filtered list, an article's
     detail, an author profile, an article's comments, a profile's authored /
     favorited lists. All are readable logged-out, but their payloads embed
     `favorited` / `following` RELATIVE TO THE CURRENT VIEWER. So they carry a
     VIEWER scope `[:rf.scope/viewer {:username …}]` (or `[:rf.scope/viewer
     :anonymous]` for a confirmed logged-out reader) — Alice, a confirmed
     anonymous viewer, and Bob never share a cache entry carrying someone else's
     relationship flags.

   - Session reads — the authenticated user's personalised feed
     (`/articles/feed`). What that returns depends on who's asking, so it carries
     a session scope `[:rf.scope/session {:username …}]`. A logged-out user has
     no feed at all, so this one is nil (fail-closed) when logged out.

   ## Two named resolvers, every site

   Both the viewer and the session seams turn on the single fact — 'who is the
   current viewer?' — and rather than answer it by hand at every site, named
   resolvers answer it once. `reg-resource-scope :realworld/viewer` and
   `reg-resource-scope :realworld/session`, with declared db inputs, state that
   fact a single time, and every site just points at one with
   `{:from-db :realworld/viewer}` / `{:from-db :realworld/session}`:

   - the six optional-auth RESOURCES declare `:scope {:from-db :realworld/viewer}`
     and the feed declares `:scope {:from-db :realworld/session}`
     (resources.cljs), so a subscription resolves the scope itself — no view ever
     threads a scope payload, and the sub re-keys reactively across login/logout;
   - the home ROUTE plans the feed as a `:resources` entry with
     `:scope {:from-db :realworld/session}` (routing.cljs); the viewer reads take
     their scope from their spec policy at route entry. Both resolve against the
     navigation handler's app-db, owned by the nav-token, released on leave;
   - the MUTATIONS carry per-target invalidation / populate / optimistic-patch
     descriptors naming `{:from-db :realworld/viewer}` (the article / profile /
     comment reads) and `{:from-db :realworld/session}` (the feed) — so one
     mutation reaches every scope it touched, with no app-level cross-scope
     patching (mutations.cljs / article_editor.cljs / views.cljs);
   - LOGOUT resolves the concrete old scopes with the pure
     `rf/resolve-resource-scope` helper against the coeffect db and clears BOTH
     the departing viewer's reads and their session feed (auth.cljs).

   And `nil` is the fail-closed 'unresolved' answer. The session resolver is nil
   whenever logged out. The viewer resolver is nil only in ONE transient window —
   a saved token is present but the durable user has not resolved yet (cold-boot
   session restore in flight): the viewer is genuinely UNKNOWN, so it fails closed
   rather than label a token-authenticated read shareable under an anonymous
   identity. A nil subscription becomes a loud 'scope unresolved' signal rather
   than a quiet read of shared data; a nil route entry / invalidation descriptor
   resolves to nothing; logout resolves nil for an already-absent scope and skips
   the clear. One idea, applied consistently."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; Resources ships `reg-resource-scope`; requiring the ns wires up the
            ;; hooks so the macro has something to resolve against.
            [re-frame.resources]))

;; ============================================================================
;; THE VIEWER-REPRESENTATION SCOPE RESOLVER
;; ============================================================================
;;
;; The optional-auth reads (articles, article, comments, profile, author- and
;; favorited-articles) are readable logged-out, but every payload embeds the
;; CURRENT viewer's `favorited` / `following` relationship flags. So their cache
;; identity is the viewer, not the world. This resolver derives that viewer from
;; the auth slice and distinguishes THREE conditions the cache must keep apart:
;;
;;   - AUTHENTICATED — a durable username is present → `[:rf.scope/viewer
;;     {:username …}]`. Each signed-in reader gets their own representation.
;;   - CONFIRMED ANONYMOUS — no username AND no saved token → `[:rf.scope/viewer
;;     :anonymous]`. A logged-out reader's representation (all flags false) is a
;;     real, shareable identity — but only among genuinely anonymous readers.
;;   - UNRESOLVED — no username YET, but a saved token IS present (cold-boot
;;     restore is mid-flight) → `nil`, FAIL-CLOSED. The token would make the
;;     read authenticated (the bearer interceptor decorates it), so its bytes are
;;     the token-holder's, NOT anonymous's — labelling them shareable under the
;;     anonymous identity is exactly the cross-viewer leak. So the read simply
;;     does not fire until restore settles, at which point `:auth/ensure-viewer-
;;     route` (auth.cljs) re-plans the active route under the now-resolved viewer.
;;
;; The `:token` input is read ONLY for its PRESENCE — it never rides the derived
;; scope (which carries at most a username). The framework classifies the
;; scope-resolution trace's inputs as fail-closed off-box egress-sensitive, so
;; the raw JWT never leaves the box through this seam either.

(rf/reg-resource-scope :realworld/viewer
  {:doc    "The current viewer's resource cache scope for the optional-auth reads
            — the per-reader representation boundary. `[:rf.scope/viewer
            {:username …}]` when signed in, `[:rf.scope/viewer :anonymous]` when
            confirmed logged out, and nil (fail-closed) while a saved token is
            present but the user has not restored yet."
   :inputs {:username [:db [:auth :user :username]]
            :token    [:db [:auth :token]]}}
  (fn [{:keys [username token]} _ctx]
    (cond
      username           [:rf.scope/viewer {:username username}]
      (str/blank? token) [:rf.scope/viewer :anonymous]
      :else              nil)))

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
