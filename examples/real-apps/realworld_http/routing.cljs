(ns realworld-http.routing
  "Routes for the RealWorld (Conduit) example.

   Two things live here: the route table (the map of URL → what-to-do) and one
   app-specific auth guard that keeps logged-out visitors out of the private
   pages. Links are the framework's own `rf/route-link` (registered at
   `:route/link`); call sites just pass `:class` / `:data-testid` straight
   through to the underlying `<a>`. Beyond that, the example leans on the
   framework's routing surface as-is:

   - `reg-route`
   - `rf/route-link` (the registered view at `:route/link`)
   - `:rf.route/navigate`
   - `:rf.route/handle-url-change`
   - `:rf.route/continue` / `:rf.route/cancel`
   - `:rf.route/id` / `:rf.route/params` / `:rf.route/query`
   - `:rf.route/url-requested`
   - `with-base-path` — this example is served from a sub-path, so its
     `:url-strategy` wraps the default history strategy (see ROUTER WIRING
     below)"
  (:require [re-frame.core :as rf]
            ;; Routing lives in its own artefact. Requiring it registers the
            ;; hooks and subs that make the `rf/reg-route` calls below resolve.
            ;; This one IS aliased — `:rf.route/entry-blocked` below calls
            ;; `routing/match-url` directly, and ROUTER WIRING builds
            ;; `url-strategy` off `routing/with-base-path` +
            ;; `routing/history-url-strategy`. See the routing guide:
            ;; ../../../docs/routing/index.md
            [re-frame.routing :as routing]))

;; ============================================================================
;; ROUTES
;; ============================================================================

(rf/reg-route :realworld/home
  {:doc      "The landing page — the global feed, plus your feed once you're
              signed in.

              A note on URL shapes, because we follow the official contract to
              the letter: RealWorld uses `/?feed=following` for the
              authenticated feed (not `?feed=your`) and a PATH-param tag route,
              `/tag/:tag` (not `?tag=`). That's why the tag filter gets its own
              `:realworld/home-tag` route below, and this home route carries
              only `?feed=` (the following toggle) and `?page=`.

              `?page=N` is the 1-indexed page for the active feed. It rides the
              query so Back/Forward and bookmarks restore your spot; `:int`
              coercion turns the URL's `\"2\"` into a real `2`, and
              `:query-defaults` fills in page 1 when the key's missing."
   :query    [:map
              [:feed {:optional true} :string]
              [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:home/load]]
   :scroll   :top} "/")

(rf/reg-route :realworld/home-tag
  {:doc      "The tag-filtered list, at the official `/tag/:tag` PATH route.
              `?page=N` paginates within the tag exactly as the home feed does
              (`/tag/:tag?page=2`); `:query-defaults` fills page 1. It shares
              `:home/load` with the home route — that handler just reads the
              active tag straight off the route params."
   :params   [:map [:tag :string]]
   :query    [:map [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:home/load]]
   :scroll   :top} "/tag/:tag")

(rf/reg-route :realworld.auth/login
  {:doc  "Login page."} "/login")

(rf/reg-route :realworld.auth/register
  {:doc  "Register page."} "/register")

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth)."
   :on-match [[:settings/load]]
   :tags #{:requires-auth}
   :can-enter [:realworld.routing/authed?]} "/settings")

(rf/reg-route :realworld.editor/new
  {:doc       "Create a new article (requires auth)."
   :tags      #{:requires-auth}
   ;; Per-entry SLICE reset (not :editor/initialise): the :editor/can-submit?
   ;; flow is a boot-time singleton, so each visit only wipes the slice.
   :on-match  [[:editor/reset]]
   :can-leave [:editor/can-leave?]
   :can-enter [:realworld.routing/authed?]} "/editor")

(rf/reg-route :realworld.editor/edit
  {:doc       "Edit an existing article (requires auth)."
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]
   :can-enter [:realworld.routing/authed?]
   :on-match  [[:editor/load-article]]} "/editor/:slug")

(rf/reg-route :realworld.article/show
  {:doc      "Article detail page. The #comments fragment scrolls to comments."
   :params   [:map [:slug :string]]
   :on-match [[:article/load]
              [:comments/load]]
   :scroll   :top} "/article/:slug")

(rf/reg-route :realworld.profile/show
  {:doc      "A user's profile — articles they authored. `?page=N` paginates
              the authored list (official RealWorld limit/offset pagination)."
   :params   [:map [:username :string]]
   :query    [:map [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:profile/load]
              [:profile.articles/load]]} "/profile/:username")

(rf/reg-route :realworld.profile/favorites
  {:doc      "A user's profile — articles they have favorited. `?page=N`
              paginates the favorited list."
   :params   [:map [:username :string]]
   :query    [:map [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:profile/load]
              [:profile.favorites/load]]} "/profile/:username/favorites")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback when no other route matches."} "/_404")

;; ============================================================================
;; AUTH GATE — :can-enter
;; ============================================================================
;;
;; Route-level auth is the framework's `:can-enter` guard — the first-class
;; mirror of `:can-leave`. Each `:requires-auth` route (`:realworld.user/settings`,
;; `:realworld.editor/new`, `:realworld.editor/edit`) declares
;; `:can-enter [:realworld.routing/authed?]`. The runtime consults that guard on
;; the ONE navigation gate, so a protected page can't be reached logged-out by
;; ANY door — programmatic nav, a link click, a URL-bar deep-link, or a
;; Back/Forward — with zero per-door plumbing. See the routing guide on guards:
;; ../../../docs/routing/concepts.md#blocking-a-navigation
;;
;; The guard and `:rf.route/entry-blocked` handler share the framework's single
;; pending-navigation path. That keeps target capture, cancellation, and resume
;; semantics in routing rather than duplicating them for each navigation entry
;; point.

;; The guard sub. `true` → OK to enter. It reads the durable `[:auth :user]`
;; presence (not the machine's `:authed` state), so a deep-link that arrives
;; DURING session restore judges a logged-in user correctly. `:can-enter` subs
;; receive the pending target as a second arg — unused here, since the answer is
;; the same for every protected route (are you signed in?).
(rf/reg-sub :realworld.routing/authed?
  {:doc "The :can-enter auth guard: true when a user is signed in. Read by the
         :requires-auth routes' :can-enter slot."}
  :<- [:auth/user]
  (fn [user _] (some? user)))

;; The block handler — the enter-block mirror of a confirm dialog. When
;; `:can-enter` rejects, the runtime writes `:rf/pending-navigation` (with the
;; target the user aimed at) and dispatches `:rf.route/entry-blocked`. This
;; handler turns that into a login redirect and stashes the target for the
;; post-login bounce-back. `:auth/post-login-redirect` (auth.cljs) reads and
;; clears `[:auth :return-to]` on a successful interactive login and navigates
;; there. Both the rejection and the eventual resume therefore remain ordinary,
;; traceable routing events.
;;
;; The pending-nav slot carries `:rejecting-route` (the target route-id) and
;; `:requested-url` — the FULL URL the user aimed at, which the runtime built
;; through `route-url` (path + query + #fragment). We resolve the whole address
;; off it, not just the params, so the stash is the EXACT place they were
;; headed: a deep-link like `/editor/my-slug?draft=1#preview` bounces back with
;; its query and fragment intact, not stranded on a bare `/editor/my-slug`. The
;; stash is itself a valid `:rf.route/navigate` request; the bounce-back
;; (auth.cljs) returns there with `:replace? true` so `/login` never lands on
;; the back stack.
(rf/reg-event :rf.route/entry-blocked
  {:doc "Steer a logged-out visitor who tried to enter a :requires-auth route to
         login, remembering the FULL address they were headed for (path,
         params, query, and #fragment) so the post-login bounce-back returns to
         the exact URL."}
  (fn [{:keys [db]} [_ {:keys [rejecting-route requested-url]}]]
    (let [{:keys [params query fragment]} (routing/match-url requested-url)]
      {:db (assoc-in db [:auth :return-to] {:to       rejecting-route
                                            :params   (or params {})
                                            :query    (or query {})
                                            :fragment fragment})
       :fx [[:dispatch [:rf.route/navigate {:to :realworld.auth/login}]]]})))

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

;; This example might be served from a sub-path — a host staging lots of demos
;; side by side could mount it at /realworld/ — even though on its own it'd
;; live at /. `with-base-path` is a strategy combinator: it wraps
;; the default history strategy so the base path is stripped off every
;; inbound URL and re-added to every outbound one, at the framework's four
;; egress/ingress consult points (Spec 012 §URL strategies) — so the whole
;; route table above, and the rest of the cascade, are written as if this app
;; owned `/`. Declared as this app's `:url-strategy` on the URL-owning frame
;; in core.cljs; the frame's `:url-bound? true` creation installs the
;; base-path-aware popstate listener AND does the first-load URL→state sync
;; automatically, so this namespace needs no separate listener or install call.
(def url-strategy
  (routing/with-base-path routing/history-url-strategy "/realworld"))

