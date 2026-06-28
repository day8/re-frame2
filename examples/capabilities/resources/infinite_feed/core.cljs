(ns infinite-feed.core
  "An infinite-scroll timeline — one feed that grows a page at a time.

   Here's the whole idea. The feed is a single cache entry, and the runtime
   owns it. The view reads the merged list and, when you want more, dispatches
   one event: `:rf.resource/load-more`. That's it. The runtime keeps the page
   list, tracks the cursor, flips the in-flight flag, and appends the next page.
   The app writes none of it. Mechanically it's a `reg-resource` with
   `:infinite true`, owned by the route, and read through the passive infinite
   subscription family.

   The how-to walks this exact code, page by page:
   docs/resources/how-to/paginate-a-feed.md (§Load more).

   Four things to notice as you read:

   - One feed, many pages. `:infinite true` plus a `:next-page-param` function
     turns the resource into a growing, ordered run of pages kept as one cache
     entry. The route ensures page 0; the view reads the merged list and never
     thinks about pages at all.
   - Load-more is just an event. The button dispatches `[:rf.resource/load-more
     …]` and the runtime works out the next page param from the tail it already
     loaded. The event carries a `:cause` but no `:owner` — the route already
     owns the feed, so load-more grows that one entry instead of minting a
     second owner. (Owner keeps it alive; cause explains why it grew. See
     docs/resources/glossary.md#owner--cause.)
   - The terminal is `nil`. When `:next-page-param` returns nil, that's the end
     of the feed — one signal, no flags. The view reads `:has-next-page?` and
     swaps the button for an end-of-feed marker.
   - Three error channels, and they don't get crossed. A load-more failure
     (page N>0) keeps every page you've already scrolled to and surfaces
     `:page-error` while the feed stays `:loaded`. A page-0 first-load failure
     with nothing to show settles `:error` with `:status :error`. So `:error`
     means \"the feed never loaded\" and `:page-error` means \"loading more
     failed\" — the view shows a full error screen for one and an inline retry
     for the other. Different questions, different answers.

   The view reads one combined view-model, `[:rf.resource/infinite-state …]`:
   `:items` (the merged flat list — the read you care about most),
   `:has-next-page?`, `:fetching-next?`, `:page-error`, `:loading?`,
   `:has-data?`. Then it dispatches one causal event. Scope is the leak
   boundary, and it fails closed: this feed is public — the same for every
   viewer — so it makes the explicit `:rf.scope/global` claim. A per-user feed
   would carry a scope resolver instead. See docs/resources/glossary.md#scope.

   One wrinkle worth flagging: this feed's pages are *enveloped*. Each page is
   `{:items [...] :page-info {…}}`, not a bare vector, so the resource has to
   tell the runtime how to dig the rows out — that's the `:page->items`
   accessor. The runtime would rather shout than guess: an enveloped page with
   no accessor raises `:rf.error/infinite-missing-page-accessor` right at the
   merge. (Pages that are already bare vectors flatten by identity, so they need
   no accessor.)

   There's no backend here, so the example overrides `:rf.http/managed` with a
   per-cursor canned stub that hands off to the framework's
   `:rf.http/managed-canned-success` — the same reply shape a live server would
   send. So every page fetch still runs the real thing: a real fetch, in-flight
   dedupe, stale-reply suppression, the passive status flow. A small reply delay
   gives the load-more spinner a moment on screen before each page lands."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.views]
            ;; Managed HTTP — the built-in transport resources fetch over.
            ;; Loading it registers the `:rf.http/managed` fx that every page
            ;; fetch runs on. See docs/resources/glossary.md#managed-http.
            [re-frame.http.managed]
            ;; The framework's canned-success fx that our demo stub hands off
            ;; to. Requiring it is the explicit "yes, this app has no backend"
            ;; opt-in — handy for demos and tests, deliberately out of the way
            ;; for real apps.
            [re-frame.http.test-support]
            ;; Resources. Requiring it at boot wires up the registrations —
            ;; including the infinite sub family this view reads. Skip it and
            ;; `rf/reg-resource` throws :rf.error/resources-artefact-missing.
            [re-frame.resources]
            ;; Routing. The resources artefact teaches routing about the
            ;; `:resources` route key, so loading both is what lets a route
            ;; declare its resources. On entry a route ensures page 0 of an
            ;; infinite feed — just the first page, not the whole pile.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; THE INFINITE RESOURCE — one growing feed, its pages held as the value
;; ============================================================================
;;
;; An infinite resource is just an ordinary resource (identity + scope +
;; request) with two extra keys:
;;
;;   :infinite true       turns on paging and makes :next-page-param required;
;;   :next-page-param     a pure (last-page all-pages) -> next-param-or-nil.
;;                        Return nil and you've reached the end — that's the
;;                        only end-of-feed signal there is.
;;
;; The :request keeps its familiar (params ctx) shape. The twist: for an
;; infinite resource that second arg, the ctx, carries the page being fetched —
;; {:rf.resource/page-param p :rf.resource/page-index i}. Page 0 comes through
;; with page-param nil; every load-more after that passes the cursor the runtime
;; derived from the previous page.
;;
;; And here's the key bit: the page param is NOT part of the feed's identity.
;; Two load-more calls grow ONE entry — they don't spawn two cache keys. Only
;; the identity params name the feed (here there are none — one public feed for
;; everyone). Change a filter or sort and you get a *different* feed instance,
;; not a mutation of this one.

(def ^:private page-size 8)

(rf/reg-resource :feed/timeline
  {:doc "The public activity timeline — an infinite (load-more) feed."

   :infinite       true

   ;; The params that *identify* the feed. This demo has one public feed, so
   ;; there's nothing to identify — an empty map. A real app would put
   ;; filter/sort/search here, where any change spins up a fresh feed. Note what
   ;; doesn't belong here: the per-page cursor. That's the page param, not
   ;; identity.
   :params-schema  [:map]

   ;; A public feed — same content for every viewer — so it makes the explicit
   ;; global claim. A per-user feed would carry a scope resolver instead.
   :scope          :rf.scope/global

   ;; The pages are enveloped — {:items … :page-info …} — so this accessor is
   ;; required: it tells the runtime how to pull the rows out of each page for
   ;; the merged `:items` list. (Bare-vector pages wouldn't need it.)
   :page->items    :items

   ;; Work out the next page param from the page we just loaded. Return nil and
   ;; the feed is done — `:has-next-page?` flips to false and the button becomes
   ;; an end-of-feed marker.
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))   ;; nil ⇒ no more pages

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; Tag the feed so a write somewhere can invalidate the whole thing in one
   ;; stroke — coarse, but exactly right for a timeline. See
   ;; docs/resources/glossary.md#cache-tag.
   :tags           (fn [_feed-params _data] #{[:feed :timeline]})}
  ;; :request keeps its (params ctx) shape; the ctx carries the page being
  ;; fetched (page-param nil means this is the first page).
  (fn [_feed-params {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get
               :url    "/api/timeline"
               :params (cond-> {:limit page-size :page-index page-index}
                         page-param (assoc :cursor page-param))}
     :decode  :json}))

;; ============================================================================
;; DEMO BACKEND — a per-cursor canned :rf.http/managed override
;; ============================================================================
;;
;; There's no server behind this example, so we override `:rf.http/managed` (the
;; fx every page fetch runs on) with a stub that fakes one enveloped page per
;; cursor. It reads the `:cursor` request param (missing means page 0), slices
;; the demo dataset, and returns {:items [...] :page-info {:next-cursor …}} —
;; the very shape a real cursor-paginated server would send. So the whole feed
;; lifecycle runs for real: ensuring page 0, deriving the next cursor on
;; load-more, deduping in-flight fetches, hitting the nil terminal. It hands off
;; to the framework's `:rf.http/managed-canned-success` with `:after-ms`, which
;; means the reply travels by `:dispatch-later` — so it shows up on the trace
;; tape and you can time-travel right through it.

(def ^:private total-items 26)

(def ^:private demo-items
  "The flat dataset the stub pages over. The cursor is just an offset into this
   vector — the row the next page should start at."
  (vec (for [i (range total-items)]
         {:id i :title (str "Activity item #" (inc i))})))

(def ^:private demo-reply-delay-ms
  "How long the stub sits on each canned reply before sending it (the
   canned-success fx's `:after-ms`). Small but non-zero, just enough that the
   load-more spinner gets a moment on screen. A demo knob — not something you'd
   ship."
  140)

(defn- demo-page-for-cursor
  "Fake one enveloped page starting at `offset` (the cursor; nil means 0). The
   next-cursor points at where the following page would start, or nil once we've
   run out — which is exactly the terminal `:next-page-param` is watching for."
  [cursor]
  (let [offset (or cursor 0)
        items  (->> demo-items (drop offset) (take page-size) vec)
        next   (+ offset (count items))]
    {:items     items
     :page-info {:next-cursor (when (< next total-items) next)}}))

;; This is our stand-in for the backend. It fakes the page for the incoming
;; cursor, then hands everything else off to `:rf.http/managed-canned-success`
;; (looked up via the registrar), tacking on `:after-ms` so the reply rides
;; `:dispatch-later`. It reuses the incoming args-map untouched, which keeps the
;; reply addressing — the per-page reply handlers the resource runtime set up —
;; pointing where it should.
(rf/reg-fx :infinite-feed.demo/http-stub
  {:doc       "Demo override for `:rf.http/managed`: fakes one enveloped page
               per request cursor so the feed runs with no backend, then hands
               off to `:rf.http/managed-canned-success` with `:after-ms`."
   :platforms #{:client}}
  (fn fx-managed-feed-demo [frame-ctx args-map]
    (let [cursor  (get-in args-map [:request :params :cursor])
          payload (demo-page-for-cursor cursor)
          stub    (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))

;; ============================================================================
;; ROUTES — the route owns the feed and ensures page 0 on entry
;; ============================================================================
;;
;; A route declares an infinite resource the same way it declares any other.
;; Enter the route and it ensures page 0 under the route's owner; leave and it
;; releases the feed. Everything after page 0 — every load-more — is a thing the
;; user did *during* the route's lifetime, not a step in the route's load plan.
;; `:blocking?` waits on page 0 and nothing more.
;; See docs/resources/how-to/paginate-a-feed.md (§Let the route own the feed).

(rf/reg-route :infinite-feed.app/home
  {:doc  "Landing page."} "/")

(rf/reg-route :infinite-feed.app/timeline
  {:doc   "The feed — ensures page 0 of the infinite :feed/timeline on entry."
   :resources
   [{:resource  :feed/timeline
     :params    (fn [_route] {})
     :blocking? true}]} "/timeline")

(rf/reg-route :rf.route/not-found
  {:doc  "Where unmatched URLs land."} "/_404")

;; ============================================================================
;; PAGES — the view just reads; the runtime keeps the pile of pages
;; ============================================================================
;;
;; The view stays passive. It reads the combined infinite view-model and
;; dispatches one causal event — that's the whole job. The query map below is
;; the feed's identity (resource + scope + params), the same shape the route's
;; `:resources` plan ensured under, so the sub reads exactly the entry the route
;; owns.

(def ^:private feed-query
  {:resource :feed/timeline :scope :rf.scope/global :params {}})

(reg-view home-page []
  [:div
   [:h1 "Infinite feed demo"]
   [:p "A load-more / infinite-scroll timeline as a first-class re-frame2
        resource. The feed is ONE growing cache entry; the view reads the
        merged list passively and dispatches a causal load-more. No app-db
        list slice, no cursor threading, no append reducer — the runtime owns
        it all."]
   [:p [rf/route-link {:to :infinite-feed.app/timeline
                       :data-testid "route-link-timeline"}
        "Open the timeline →"]]])

(reg-view feed-row [{:keys [title]}]
  [:li {:data-testid "feed-row"} title])

(reg-view timeline-page []
  ;; The route's `:resources` already ensured page 0 on the way in. All the view
  ;; does is read the whole feed view-model, passively, through
  ;; `[:rf.resource/infinite-state …]`.
  (let [feed @(subscribe [:rf.resource/infinite-state feed-query])]
    [:div
     [:h1 "Timeline"]
     (cond
       ;; Page 0 is still loading and there's nothing to show yet → skeleton.
       (:loading? feed)
       [:p {:data-testid "feed-skeleton"} "Loading the feed…"]

       ;; Page 0 failed and left us with nothing → the full error screen. With
       ;; no pages to fall back on, the feed settles `:error` / `:status :error`,
       ;; just like a plain scalar resource would. Remember the split: `:error`
       ;; is a first-load failure; a load-more (page N>0) failure uses
       ;; `:page-error` and keeps the feed.
       ;; See docs/resources/glossary.md#resource-status.
       (:error feed)
       [:p.error {:data-testid "feed-error"} "Could not load the feed."]

       :else
       [:<>
        ;; The merged flat list — the `:items` read everything else is here to
        ;; support. The runtime stitches the pages together in load order and
        ;; memoises the result; the view just renders the rows.
        (into [:ul {:data-testid "feed-list"}]
              (for [item (:items feed)]
                ^{:key (:id item)} [feed-row item]))

        ;; The third error channel. A load-more failure (page N>0 — we already
        ;; have data) keeps every page on screen and raises :page-error for an
        ;; inline retry. Unlike the first-load :error above, the feed stays put.
        (when (:page-error feed)
          [:p.warn {:data-testid "feed-page-error"}
           "Couldn't load more — tap retry."])

        ;; The load-more affordance, in three moods: a spinner while a page is
        ;; in flight, the button while there's a next page, an end-of-feed
        ;; marker once there isn't. The button dispatches one causal event and
        ;; lets the runtime derive the next page param from the loaded tail. It
        ;; carries a `:cause` but no `:owner` — the route already owns the feed,
        ;; so load-more just grows it (owner keeps it alive; cause says why it
        ;; grew).
        (cond
          (:fetching-next? feed)
          [:p {:data-testid "feed-loading-more"} "Loading more…"]

          (:has-next-page? feed)
          [:button {:data-testid "feed-load-more"
                    :on-click    #(dispatch
                                   [:rf.resource/load-more
                                    (assoc feed-query :cause [:user :feed/load-more])])}
           (if (:page-error feed) "Retry" "Load more")]

          :else
          [:p {:data-testid "feed-end"} "— you're all caught up —"])])]))

(reg-view not-found-page []
  [:div
   [:h1 "Not found"]
   [:p [rf/route-link {:to :infinite-feed.app/home :data-testid "route-link-home"} "Home"]]])

(reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :infinite-feed.app/home     [home-page]
    :infinite-feed.app/timeline [timeline-page]
    :rf.route/not-found         [not-found-page]
    [home-page]))

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; The React root is created lazily inside `run`, not at ns-load, so a test can
;; mount this app in its own frame. There's exactly one place the app stands its
;; frame up: the render-root `frame-provider {:id …}`. On first mount the
;; provider creates `app-frame` and applies its config — `:url-bound? true` so
;; the frame owns the browser URL, and the `:rf.http/managed` override that
;; aims page fetches at the canned stub so the demo runs on its own. The
;; provider also scopes the frame, so every dispatch and subscribe inside the
;; tree resolves to it. Hot reload? It reuses the same frame. And there's no
;; boot seed to write: route entry's `:resources` plan ensures page 0, so the
;; frame carries no `:initial-events`.
;; See docs/core/glossary.md#frame-provider.

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; The provider creates the app frame (config and all) on first mount and
    ;; reuses it on hot reload. Route entry ensures page 0, so there's no need
    ;; for `:initial-events`.
    (rdc/render @react-root
                [rf/frame-provider {:id           app-frame
                                    :doc          "Infinite-feed demo frame."
                                    :url-bound?   true
                                    :fx-overrides {:rf.http/managed :infinite-feed.demo/http-stub}}
                 [root-view]])))
