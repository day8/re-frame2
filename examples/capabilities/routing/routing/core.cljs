(ns routing.core
  "A small three-page routing app: home, an articles list, and an article
   detail page. See the [routing guide](../../../docs/routing/concepts.md).

   Here's the one idea worth taking away: the URL is just application state,
   and you read it through a subscription. Navigation, then, is just an event.
   So a route is a registration, the active route is a sub, and `root-view` is
   a plain `case` over `:rf.route/id` — pick a page by id and render it. No
   router object to hold, no route context to thread down through the tree.

   If you've used a router that hands you an object full of methods, this will
   feel almost suspiciously quiet. That's the point.

   The pieces of the routing surface this example leans on:

   - `reg-route` — declares the route table, one entry per page
   - `:rf.route/id` / `:rf.route/params` — read the active route as subs
   - `route-link` — renders links that drive navigation
   - `:rf/url-requested` — the event a user clicking an anchor fires
   - `:url-bound? true` — declared on the frame; its creation automatically
     wires up Back/Forward (popstate) and the first-load URL→state sync, so
     the address bar and your app-db stay in agreement"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Routing lives in its own artefact (day8/re-frame2-routing), so
            ;; you opt into it with a require. The require has a side effect:
            ;; it registers the route subs that the `rf/reg-route` calls below
            ;; lean on. Forget it and those calls greet you with
            ;; :rf.error/routing-artefact-missing — which is the runtime's
            ;; polite way of saying "you asked for routing but never packed it".
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; ROUTES
;; ============================================================================
;;
;; The route table: one `reg-route` per page, each pairing a route id with a
;; URL pattern. A pattern segment like `:id` is a hole the matcher fills in
;; and hands back as a param. This is the whole map of the app, written as
;; data — the kind of thing you can read top-to-bottom and just *know* where
;; the doors are.

(rf/reg-route :routing.app/home
  {:doc  "Landing page."} "/")

(rf/reg-route :routing.app/articles
  {:doc  "Articles list."} "/articles")

(rf/reg-route :routing.app/article-detail
  {:doc    "Detail page for one article."
   :params [:map [:id :string]]} "/articles/:id")

;; When a URL matches nothing, the runtime routes to :rf.route/not-found for
;; you. Register it like any other route and you decide what that page says.
(rf/reg-route :rf.route/not-found
  {:doc  "Fallback page for unmatched URLs."} "/_404")

;; ============================================================================
;; APP DATA
;; ============================================================================

;; Now the boring-but-honest part: the actual articles. Routing tells you
;; *which* page; this is the data the pages render.
;;
;; The articles live under :routing.app/articles-list. Route ids and
;; app-db/sub keys live in separate registries, so naming this key
;; :routing.app/articles (same as the route) wouldn't actually clash. But
;; giving it a distinct name keeps the example scannable — "which one's the
;; route, which one's the data?" should never be a puzzle.
(rf/reg-event :routing.app/initialise
  (fn [{:keys [db]} _]
    {:db {:routing.app/articles-list
     [{:id "intro" :title "Intro to re-frame2" :body "..."}
      {:id "ssr"   :title "Server rendering"  :body "..."}]}}))

(rf/reg-sub :routing.app/articles-list
  (fn [db _] (:routing.app/articles-list db)))

(rf/reg-sub :routing.app/article-by-id
  :<- [:routing.app/articles-list]
  (fn [articles [_ id]]
    (first (filter #(= id (:id %)) articles))))

;; ============================================================================
;; PAGES
;; ============================================================================
;;
;; One view per page. Links between them use `rf/route-link`, a small view the
;; routing artefact ships so you don't hand-roll anchors.
;;
;; It renders a real `<a href=...>` — so it looks and feels like a normal
;; link, and the href is even right if someone hovers or copies it. The trick
;; is in the click: a plain left-click is caught and turned into a
;; `:rf/url-requested` event (no full page reload), while modifier-clicks and
;; middle-clicks are waved straight through to the browser, because nobody
;; likes a link that's forgotten how to open in a new tab. Any extra HTML
;; attrs you pass (here `:data-testid`) ride along onto the `<a>`.
;;
;; See the routing guide, "Linking from views":
;; ../../../docs/routing/concepts.md#linking-from-views

(rf/reg-view home-page []
  [:div
   [:h1 "Welcome"]
   [:p [rf/route-link {:to :routing.app/articles
                       :data-testid "route-link-articles"}
        "See the articles →"]]])

(rf/reg-view articles-page []
  [:div
   [:h1 "Articles"]
   [:ul
    (for [{:keys [id title]} @(subscribe [:routing.app/articles-list])]
      ^{:key id}
      [:li [rf/route-link {:to :routing.app/article-detail
                           :params {:id id}
                           :data-testid (str "route-link-article-" id)}
            title]])]])

(rf/reg-view article-detail-page []
  (let [id      (:id @(subscribe [:rf.route/params]))
        article @(subscribe [:routing.app/article-by-id id])]
    ;; The "← Back" link is the same in both cases — found or not — so it's
    ;; page chrome, not page content. We let each branch decide only the bit
    ;; that actually differs (the article, or the apology) and render the link
    ;; once underneath. Say a thing once.
    [:div
     (if article
       [:<> [:h1 (:title article)]
            [:p (:body article)]]
       [:p "Article not found."])
     [:p [rf/route-link {:to :routing.app/articles
                         :data-testid "route-link-back-to-articles"}
          "← Back"]]]))

(rf/reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div
     [:h1 "Not found"]
     [:p (str "No route matches: " url)]
     [:p [rf/route-link {:to :routing.app/home
                         :data-testid "route-link-home"}
          "Home"]]]))

;; And here's the router, in its entirety. Subscribe to the active route id,
;; `case` over it, render the matching page. That's it — no <Routes>, no
;; <Switch>, no nesting. The URL changes, the sub updates, this re-renders the
;; new page. If you were expecting more machinery, that's the whole trick:
;; routing is just another sub feeding just another view.
(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :routing.app/home           [home-page]
    :routing.app/articles       [articles-page]
    :routing.app/article-detail [article-detail-page]
    :rf.route/not-found         [not-found-page]))

;; ============================================================================
;; MOUNT (+ router wiring)
;; ============================================================================

;; We stash the React root in an atom and create it lazily inside `run`,
;; rather than at namespace load. The rule (see examples/TESTING.md, the
;; mount-isolation convention) is that loading a namespace must touch no DOM:
;; the test harness loads several example namespaces side by side, and if each
;; eagerly grabbed `#app` they'd trample one another's `create-root`. Lazy
;; means whoever calls `run` wins the element, and only then.
(defonce react-root (atom nil))

;; The id of the one frame this whole app runs under. `:rf/default` looks
;; special, but it isn't — it's an ordinary id with no secret privileges, and
;; the runtime won't conjure it for you. You stand it up like any other (the
;; provider in `run` does exactly that). The one bit of magic worth knowing:
;; a frame "owns" the browser URL when it carries `:url-bound? true`, and this
;; app's provider flips that on. That's what connects the address bar to *this*
;; frame's route state. See the routing guide, "The browser is just another
;; event source":
;; ../../../docs/routing/concepts.md#the-browser-is-just-another-event-source
(def app-frame :rf/default)

(defn run []
  ;; Tell re-frame2 to render through Reagent. Every adapter namespace exports
  ;; an `adapter` var; hand it straight to `init!` and you're done.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; The frame provider is where the app frame actually comes to life — one
    ;; tidy spot for the whole setup. First mount: it creates the frame under
    ;; `app-frame`, flips on `:url-bound? true` so this frame owns the URL, and
    ;; fires `:initial-events` once to seed app-db with our articles. Hot
    ;; reload: it finds the frame already there and quietly skips the seed, so
    ;; your edits don't reset the page out from under you. From then on every
    ;; `dispatch` and `subscribe` inside `root-view` is wired to this frame.
    ;;
    ;; `:url-bound? true` is the whole story for the browser's own navigation
    ;; too: creating the frame installs its popstate listener automatically.
    ;; That does two jobs: right now, it syncs the current URL into state (so
    ;; a deep link or a refresh lands on the right page), and from here on,
    ;; every Back/Forward resolves whichever frame owns the URL at pop time
    ;; and updates its `:rf/route` slice. Idempotent, so hot reload never
    ;; stacks up duplicate listeners.
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :doc "Routing demo frame."
                                    :url-bound? true
                                    :initial-events [[:routing.app/initialise]]}
                 [root-view]])))
