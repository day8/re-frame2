(ns realworld-resources.routing
  "Routes for the RealWorld-on-resources example. See the routing guide:
   ../../../docs/routing/concepts.md.

   The big idea here is worth saying plainly: entering a route is what causes the
   page's server-state to load — declaratively, via `:resources` route metadata.
   No view kicks off a fetch. On entry the runtime marks each listed resource
   active with owner `[:route route-id nav-token]` and ensures it with cause
   `[:route-entry route-id nav-token]`; on leave it releases the owner by token
   and suppresses any straggling reply by generation. The views just read the
   cache, passively. Navigation is the cause; the data follows.

   A few knobs shape how each route resource behaves. `:blocking?` holds the route
   transition pending until the resource settles; a non-blocking one fetches in
   the background. `:keep-previous?` keeps the current list on screen while a new
   filter or page loads. `:when` makes a resource conditional without resorting to
   sentinel nil params.

   (On the server, that same `:blocking?` flag becomes the SSR wait point. This
   example is client-only; the worked demo of resource SSR preload + hydration is
   `examples/capabilities/ssr/resources_ssr/`. See ../../../docs/ssr/concepts.md.)

   The session-scoped personalised feed is a declarative route resource too — no
   special-casing. The home route declares it with
   `:scope {:from-db :realworld/session}`, a named resolver reference (see
   scope.cljs) the runtime resolves against the navigation handler's app-db at
   route entry. So the route owns the feed under its nav-token and releases it on
   leave, exactly like the public reads. The feed is ADMITTED by route data — its
   `:when` reads the `?feed=following` query arm — so a logged-out visitor on the
   bare home page never asks for it. On the following-feed arm a nil session is a
   whole-plan planning error, by design: a route `:scope` that is present and
   resolves nil is never a silent omission (see `home-resources`).

   One wiring note: loading both `re-frame.resources` and `re-frame.routing` is
   what gets the `:resources` route-metadata key accepted, since resources
   late-binds it into routing."
  (:require [re-frame.core :as rf]
            ;; The routing runtime. Loading it triggers its hook + reg-sub
            ;; registrations; without it the reg-route calls have nothing to hook
            ;; into. Aliased so ROUTER WIRING below can build `url-strategy` off
            ;; `rf.routing/with-base-path` + `rf.routing/history-url-strategy`.
            [re-frame.routing :as rf.routing]
            ;; Loading resources is what makes `:resources` route-metadata
            ;; accepted — it's the late-bound routing extension.
            [re-frame.resources]
            ;; For `auth/restoring-session?` — the ONE definition of the cold-boot
            ;; window in which identity is not yet known, shared with the
            ;; `:auth/viewer-resolving?` sub so the denial handler below and the app
            ;; shell can never disagree about it. (No cycle: auth requires http /
            ;; schema / scope, none of which requires this ns.)
            [realworld-resources.auth :as auth]))

;; ============================================================================
;; ROUTES — each declares the server-state its page needs via `:resources`
;; ============================================================================

;; The route resources for the home page and its tag-filtered sibling. Both routes
;; plan the same three reads — global list, popular tags, session feed — and
;; differ in only one spot: where the active tag comes from. The `/tag/:tag` route
;; pulls it from path params; the bare home route has none. So `home-resources`
;; takes a `tag-fn` and lets each route hand in its own tag source.
(defn- home-resources [tag-fn]
  [{:resource  :realworld/articles
    ;; Route → resource params: the active tag (a param, or nil) and `?page=`
    ;; both flow into identity. Every server-visible list option earns a place in
    ;; the cache key.
    :params    (fn [route] {:tag  (tag-fn route)
                            ;; Default to page 1, so the canonical no-`?page=` URL
                            ;; owns the SAME `{:page 1}` key the views subscribe
                            ;; through (`(or (:page q) 1)`). A raw nil would mint a
                            ;; `{:page nil}` entry no view ever reads, which leaves
                            ;; first-page lists stubbornly empty.
                            :page (or (get-in route [:query :page]) 1)})
    :blocking? true
    :keep-previous? true}
   {:resource  :realworld/tags
    :params    (fn [_route] {})
    :blocking? false}
   ;; The personalised feed — a declarative route resource, scoped by the named
   ;; `{:from-db :realworld/session}` resolver. The runtime resolves the scope
   ;; against the navigation handler's app-db at route entry, owns it under the
   ;; route nav-token, and releases it on leave, just like the public reads above.
   ;;
   ;; The feed is planned ONLY on the following-feed arm (`?feed=following`, the
   ;; official-contract token), and that admission is route-derived on purpose:
   ;; `:when` receives the resolved route and the reserved ctx — never app-db —
   ;; so "when signed in" is not something it can ask. It doesn't need to. The
   ;; home view shows the feed only on that arm, so the bare `/` never wants it,
   ;; and a logged-out visitor lands on a plan that forms. On the following-feed
   ;; arm a nil session (nobody signed in) is a whole-plan planning error, BY
   ;; DESIGN: a route `:scope` that is present and resolves nil is never a silent
   ;; omission — that rule is what keeps one user's feed from ever being read
   ;; under another identity — and the route slice carries the error for the
   ;; shell to show. `?page=` flows into params here like every other paginated
   ;; list.
   {:resource  :realworld/feed
    :scope     {:from-db :realworld/session}
    :when      (fn [route _ctx] (= "following" (get-in route [:query :feed])))
    ;; Default to page 1 — the feed subscription reads `(or (:page q) 1)` too, so
    ;; the route has to own `{:page 1}` on the bare URL.
    :params    (fn [route] {:page (or (get-in route [:query :page]) 1)})
    :blocking? false
    :keep-previous? true}])

(rf/reg-route :realworld/home
  {:doc   "Home: the global article list and popular tags, plus the personalised
           feed on the `?feed=following` arm (the official-contract token — note
           it's `following`, not `your`). That arm is the only one that PLANS the
           session feed read, so it needs a signed-in session; `?page=` paginates;
           both flow into the resources' params.
           `:keep-previous?` keeps the current page on screen while the next loads,
           so there's no flicker. The tag filter is its own `/tag/:tag` PATH route
           below — the tag rides as a route param, matching the official Conduit
           route shape."
   :query [:map
           [:feed {:optional true} :string]
           [:page {:optional true} :int]]
   :scroll   :top
   :resources (home-resources (fn [_route] nil))} "/")

(rf/reg-route :realworld/home-tag
  {:doc   "The tag-filtered article list, at the official RealWorld `/tag/:tag`
           PATH route. The active tag is a route param that flows into the
           articles resource's params, so each tag is its own cache entry; `?page=`
           paginates within it (`/tag/:tag?page=2`). Same three reads as the home
           route."
   :params [:map [:tag :string]]
   :query  [:map [:page {:optional true} :int]]
   :scroll :top
   :resources (home-resources (fn [route] (get-in route [:params :tag])))} "/tag/:tag")

(rf/reg-route :realworld.auth/login
  {:doc "Login page."} "/login")

(rf/reg-route :realworld.auth/register
  {:doc "Register page."} "/register")

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth). `:on-match` seeds the settings draft
          from the authenticated user once, on route entry. With the load happening
          there rather than in render, the view stays a pure Form-1 that never
          dispatches out of band — so a re-render can't re-run the load and stomp
          on edits you've got in flight."
   :on-match [[:settings/load]]
   :tags #{:requires-auth}
   :can-enter [:realworld-resources.routing/authed?]} "/settings")

(rf/reg-route :realworld.editor/new
  {:doc       "Create a new article (requires auth). `:on-match` resets the editor
               slice and registers the can-submit flow; `:can-leave` blocks a
               navigate-away while the draft is dirty (see route guard,
               ../../../docs/routing/glossary.md#route-guard). No route
               `:resources` here — you're starting from a blank draft, so there's
               nothing to load."
   :tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-enter [:realworld-resources.routing/authed?]
   :can-leave [:editor/can-leave?]} "/editor")

(rf/reg-route :realworld.editor/edit
  {:doc       "Edit an existing article (requires auth). The article read is a
               declarative route `:resource`, exactly like every other page here:
               the runtime owns it under `[:route :realworld.editor/edit nav-token]`
               on entry and RELEASES that owner on every route leave — so leaving
               the editor for ANY route (home, a profile, settings, the saved
               article, or a new draft) drops the read with no view teardown and no
               hand-rolled owner. `:on-match [[:editor/load-article]]` resets the
               editor slice and asks to be told when that same read settles (an
               OWNERLESS `:reply-to [:editor/article-loaded]` ensure that joins the
               route's load) so it can seed the draft + baseline; it mints no owner
               of its own. It is NON-blocking: the form renders immediately and
               fills in when the read lands. `:can-leave` blocks a dirty
               navigate-away."
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :on-match  [[:editor/load-article]]
   :resources [{:resource  :realworld/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? false}]
   :can-enter [:realworld-resources.routing/authed?]
   :can-leave [:editor/can-leave?]} "/editor/:slug")

(rf/reg-route :realworld.article/show
  {:doc    "Article detail plus its comments. Both load on entry; the comments are
            a sub-resource keyed by the same slug."
   :params [:map [:slug :string]]
   :scroll :top
   :resources
   [{:resource  :realworld/article
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}
    {:resource  :realworld/comments
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :when      (fn [route _ctx] (some? (get-in route [:params :slug])))
     :blocking? false
     :keep-previous? true}]} "/article/:slug")

;; The profile SHELL. `:realworld.profile/show` (the authored-articles tab, the
;; canonical `/profile/:username` page) owns the shared profile-banner read
;; ONCE, and the favorites tab below declares `:parent :realworld.profile/show`
;; to inherit it — EP-0037 R2's effective parent-to-leaf resource plan. Before
;; R2 both tabs restated the byte-identical banner entry; now the banner has one
;; clear declaration and the two tabs cannot drift its `:blocking?`. The authored
;; list is this tab's OWN content, so it is gated with `:when` to the show leaf —
;; a navigation to the favorites child inherits the banner but not this list.
(rf/reg-route :realworld.profile/show
  {:doc    "A user's profile banner plus the articles they authored — the default
            profile tab, and the layout PARENT that owns the shared banner read
            for the favorites tab (EP-0037 R2). The `?page=` query paginates the
            authored list."
   :params [:map [:username :string]]
   :query  [:map [:page {:optional true} :int]]
   :resources
   [{:resource  :realworld/profile
     :params    (fn [route] {:username (get-in route [:params :username])})
     :blocking? true}
    {:resource  :realworld/author-articles
     ;; The authored list is THIS tab's own content — gated to the show leaf so
     ;; the favorites child (which shares this banner) does not also fetch it.
     :when      (fn [route _ctx] (= :realworld.profile/show (:id route)))
     ;; Default to page 1 to match the view's `(or (:page q) 1)` key.
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (or (get-in route [:query :page]) 1)})
     :blocking? false
     :keep-previous? true}]} "/profile/:username")

(rf/reg-route :realworld.profile/favorites
  {:doc    "A user's profile banner plus the articles they favorited — the second
            official profile tab (`/profile/:username/favorites`). The banner read
            is INHERITED from the `:realworld.profile/show` parent (EP-0037 R2 —
            declaring `:parent` composes its `:resources`), so this tab declares
            ONLY its own `:realworld/favorited-articles` list; `?page=` paginates
            it. Favoriting / unfavoriting from this tab invalidates `[:article
            slug]`, which this list carries — so it refetches, and an unfavorited
            article drops right out."
   :parent :realworld.profile/show
   :params [:map [:username :string]]
   :query  [:map [:page {:optional true} :int]]
   :resources
   [{:resource  :realworld/favorited-articles
     ;; Default to page 1 to match the view's `(or (:page q) 1)` key.
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (or (get-in route [:query :page]) 1)})
     :blocking? false
     :keep-previous? true}]} "/profile/:username/favorites")

(rf/reg-route :rf.route/not-found
  {:doc "Fallback when no other route matches."} "/_404")

;; ============================================================================
;; AUTH GATE — :can-enter
;; ============================================================================
;;
;; Route-level auth is the framework's `:can-enter` guard, the entry-side mirror
;; of `:can-leave`: each `:requires-auth` route above declares
;; `:can-enter [:realworld-resources.routing/authed?]` and the runtime consults it
;; inside the ONE navigation planning pipeline. So a protected page is unreachable
;; while logged out through EVERY door — programmatic nav, an anchor click, a
;; URL-bar deep link, a reload, Back/Forward — with no per-door plumbing.
;;
;; This app used to spell the same gate as a frame-wide interceptor over the
;; navigation events, and the retirement is worth recording rather than quietly
;; erasing (rf2-k85nd). An interceptor has to normalise every door ITSELF:
;; `:rf.route/navigate` in three request forms (a route id, the `{:url …}` escape
;; hatch, an in-place query/#fragment edit that names no route at all),
;; `:rf.route/url-requested`, and `:rf.route/handle-url-change` — some forty lines
;; of `match-url` and current-slice resolution whose only job is completeness, and
;; whose bug is always the SAME bug: the door it forgot is the door that lets a
;; logged-out visitor in. Spec 012 §Redirects and guards opens by saying it
;; outright — "Auth-on-a-route is `:can-enter`, not an interceptor" — and spells out
;; the fail-OPEN case an interceptor has to remember for itself. `:can-enter` has
;; nothing to enumerate.
;; A frame interceptor is still the right tool when the policy genuinely is not
;; about routes — a maintenance-mode lockout, a feature flag over a whole section.
;; See ../../../docs/routing/how-to/require-sign-in-on-a-route.md

;; The guard sub. `true` → OK to enter. It reads the durable `[:auth :user]`
;; presence, not the machine's `:authed` state, because the durable slice is what
;; a reload rebuilds. `:can-enter` subs receive the pending target as a second
;; arg — unused here, since the answer is the same for every protected route.
;;
;; It does NOT try to report "still finding out": `:can-enter` is a closed boolean
;; by design, and a tri-state guard would push "maybe" into every app's auth sub.
;; Mid-restore the honest answer is `false` — there is no user — and the DENIAL
;; HANDLER below is where "no user YET" is told apart from "no user, full stop".
(rf/reg-sub :realworld-resources.routing/authed?
  {:doc "The :can-enter auth guard: true when a user is signed in. Read by the
         :requires-auth routes' :can-enter slot."
   :inputs [[:auth/user]]}
  (fn [[user] _] (some? user)))

;; The denial handler — the FRESH-RETURN recipe (Spec 012 §Entry is terminal).
;; Entry denial is TERMINAL: the runtime commits nothing and parks nothing (no
;; route slice, no URL push, no `:on-match`, and no route `:resources` planned), so
;; there is no paused transition to resume. The recipe is three ordinary steps:
;; stash the denied destination, replace-navigate to login, and after a successful
;; sign-in dispatch a FRESH navigate at the stash. The guard re-evaluates on that
;; attempt because it IS an ordinary new attempt — no bypass, no resume.
;;
;; `:destination` arrives already resolved — a `:rf/route-destination` carrying
;; path params, query and #fragment, and a valid `:rf.route/navigate` request in
;; its own right — so the stash is the EXACT address the reader wanted and needs no
;; `match-url` re-derivation. That is the whole forty lines the retired
;; interceptor's `resolve-nav-target` existed to reproduce.
;;
;; THE ONE BRANCH. A refusal has two meanings, and only the handler can tell them
;; apart:
;;
;;   "you are not signed in"  → bounce to login. The ordinary case.
;;   "we don't know yet"      → STAY PUT and wait. A cold boot with a saved JWT
;;                              whose `GET /user` is still in flight: the reader
;;                              may well be signed in, and bouncing them to login
;;                              here is the bug this branch prevents. Nothing has
;;                              committed, so nothing protected is exposed while we
;;                              wait; `:auth/settle-deferred-entry` (auth.cljs)
;;                              picks the stash up the moment restore settles.
;;
;; Both branches are fail-CLOSED: the deferred branch grants nothing, it only
;; declines to give up. Registering this handler is optional — the framework ships
;; a no-op default, which would make a denial a silent hard deny.
(rf/reg-event :rf.route/entry-denied
  {:doc "Steer a logged-out visitor who tried to enter a :requires-auth route to
         login, remembering the FULL destination they were headed for (route,
         params, query, and #fragment) so the return lands on the exact URL.
         While a cold-boot session restore is still in flight the visitor's
         identity is unknown, so the bounce is DEFERRED rather than taken —
         `:auth/settle-deferred-entry` resolves the stash once restore settles."}
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    (cond-> {:db (assoc-in db [:auth :return-to] destination)}
      (not (auth/restoring-session? db))
      (assoc :fx [[:dispatch [:rf.route/navigate {:to       :realworld.auth/login
                                                  :replace? true}]]]))))

;; ============================================================================
;; ROUTER WIRING  (base-path-aware)
;; ============================================================================
;;
;; Each entry is served from its OWN deployment sub-path (a host mounting the
;; demos side by side), but the routes above are written as if the app owned
;; `/`. `with-base-path` wraps the default history strategy so that prefix is
;; stripped/re-added automatically at the framework's four egress/ingress
;; consult points (Spec 012 §URL strategies). The frame's `:url-bound? true`
;; creation installs the base-path-aware popstate listener AND does the first
;; URL→route sync itself — which is the trick that fires the route's
;; `:resources` ensures on entry, with zero hand-rolled listener or explicit
;; install call.
;;
;; DEPLOYMENT BASE IS ENTRY-SPECIFIC. The entry names its own base-path
;; strategy: `core.cljs` (build `:examples/realworld-resources`) mounts under
;; `/realworld-resources`. `strip-base-path` fails safe on `/`, so the strategy
;; also boots correctly when the build is served at the server root.
(def url-strategy
  (rf.routing/with-base-path rf.routing/history-url-strategy "/realworld-resources"))
