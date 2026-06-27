(ns infinite-feed.core
  "A load-more / infinite-scroll timeline built as one growing resource.

   The whole idea: the feed is ONE cache entry the runtime owns and grows. The
   view reads the merged list passively and dispatches a single causal
   `:rf.resource/load-more`. The runtime holds the page list, the cursor, the
   in-flight flag, and the append — the app writes none of them. It is a
   `reg-resource` with `:infinite true`, owned by the route, read through the
   passive infinite subscription family.

   The how-to walks this exact code, step by step:
   docs/resources/how-to/paginate-a-feed.md (§Load more).

   Four facts the example shows:

   - ONE FEED, MANY PAGES. `:infinite true` plus a `:next-page-param` function
     makes the resource a growing ordered sequence of pages held as one cache
     entry. Route entry ensures page 0; the view reads the merged list.
   - LOAD-MORE IS A CAUSAL EVENT. The button dispatches
     `[:rf.resource/load-more …]` and the runtime derives the next page param
     from the loaded tail. The event carries a `:cause` but no `:owner`: the
     route already owns the feed, so load-more extends that one owned entry
     rather than minting a second owner. (Owner keeps it alive; cause explains
     why it grew — see docs/resources/glossary.md#owner--cause.)
   - THE TERMINAL IS `nil`. `:next-page-param` returning nil is the single
     end-of-feed signal. The view reads `:has-next-page?` and shows an
     end-of-feed marker instead of the button.
   - THREE ERROR CHANNELS. A load-more failure (page N>0) keeps every
     accumulated page visible and surfaces `:page-error` while the feed stays
     `:loaded`. A page-0 first-load failure with no data settles `:error` and
     `:status :error`. `:error` is for first-load, `:page-error` for load-more,
     so the view shows the full error screen on one and the inline retry on the
     other — distinct axes, never conflated.

   The view reads the combined `[:rf.resource/infinite-state …]` view-model —
   `:items` (the merged flat list, the headline read), `:has-next-page?`,
   `:fetching-next?`, `:page-error`, `:loading?`, `:has-data?` — and dispatches
   one causal event. Scope is a fail-closed leak boundary: this feed is public
   (the same for every viewer), so it carries the explicit `:rf.scope/global`
   claim. A per-user feed would carry a scope resolver instead. See
   docs/resources/glossary.md#scope.

   This feed's pages are ENVELOPED — each page is `{:items [...] :page-info
   {…}}`, not a bare vector — so the resource declares the required
   `:page->items` accessor. The runtime is loud over guessing: an envelope page
   with no accessor raises `:rf.error/infinite-missing-page-accessor` at the
   merge. (Pages that are already bare vectors flatten by identity and need no
   accessor.)

   The example ships no backend, so it overrides `:rf.http/managed` with a
   per-cursor canned stub that delegates to the framework-shipped
   `:rf.http/managed-canned-success` — the same reply shape a live server would
   produce. Every page fetch exercises a real fetch, in-flight dedupe, stale
   suppression, and the passive status flow. A small reply delay lets the
   load-more spinner render before each page lands."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.views]
            ;; Managed HTTP — the built-in resource transport. Loading the ns
            ;; registers the `:rf.http/managed` fx that each page fetch runs on.
            ;; See docs/resources/glossary.md#managed-http.
            [re-frame.http.managed]
            ;; The framework-shipped canned-success fx the demo stub delegates
            ;; to. Requiring it is the explicit opt-in for a demo / test app
            ;; with no backend.
            [re-frame.http.test-support]
            ;; Resources. Requiring the ns at app boot wires the registrations
            ;; (including the infinite sub family); without it `rf/reg-resource`
            ;; throws :rf.error/resources-artefact-missing.
            [re-frame.resources]
            ;; Routing. The resources artefact binds its `:resources`
            ;; route-metadata key into routing, so loading both is what makes a
            ;; route's `:resources` key accepted. Route entry ensures page 0 of
            ;; an infinite resource (not the whole accumulation).
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; THE INFINITE RESOURCE — one growing feed, pages held as the value
;; ============================================================================
;;
;; An infinite resource is an ordinary resource (identity + scope + request)
;; with two additions:
;;
;;   :infinite true       makes :next-page-param required;
;;   :next-page-param     a pure (last-page all-pages) -> next-param-or-nil
;;                        function. nil is the single terminal signal.
;;
;; The :request keeps its (params ctx) shape. For an infinite resource the ctx
;; (its second arg) carries this page's context:
;; {:rf.resource/page-param p :rf.resource/page-index i}. Page 0 is fetched
;; with page-param nil; each load-more passes the derived next cursor.
;;
;; The page param is NOT part of the feed's cache identity — two load-more
;; calls extend ONE entry, they do not create two cache keys. Only the identity
;; params (here: nothing — a single public feed) name the feed; a filter/sort
;; change yields a DIFFERENT feed instance, not a mutation of this one.

(def ^:private page-size 8)

(rf/reg-resource :feed/timeline
  {:doc "The public activity timeline — an infinite (load-more) feed."

   :infinite       true

   ;; The feed-IDENTITY params. This demo has one public feed, so the identity
   ;; is empty; a real app puts filter/sort/search here (a change → a new feed
   ;; instance). The per-page cursor is NEVER here — it is the page param.
   :params-schema  [:map]

   ;; Public feed: the same for every viewer → the explicit global claim. A
   ;; per-user feed would carry a scope resolver instead.
   :scope          :rf.scope/global

   ;; The pages are ENVELOPED ({:items … :page-info …}), so the REQUIRED
   ;; accessor tells the runtime how to flatten each page into the merged
   ;; `:items` list. (A bare-vector page would need no accessor.)
   :page->items    :items

   ;; Derive the NEXT page param from the last loaded page. Returns nil to
   ;; signal end-of-feed (the single terminal; `:has-next-page?` is then false).
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))   ;; nil ⇒ no more pages

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; A feed tag so a write can invalidate the whole feed by tag (coarse but
   ;; correct). See docs/resources/glossary.md#cache-tag.
   :tags           (fn [_feed-params _data] #{[:feed :timeline]})}
  ;; :request keeps its (params ctx) shape; the ctx carries this page's context
  ;; (page-param nil ⇒ first page).
  (fn [_feed-params {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get
               :url    "/api/timeline"
               :params (cond-> {:limit page-size :page-index page-index}
                         page-param (assoc :cursor page-param))}
     :decode  :json}))

;; ============================================================================
;; DEMO BACKEND — per-cursor canned :rf.http/managed override
;; ============================================================================
;;
;; This example ships no server, so it overrides `:rf.http/managed` (the fx
;; every page fetch runs on) with a stub that synthesises one enveloped page
;; per cursor. The stub reads the `:cursor` request param (absent ⇒ page 0),
;; slices the demo dataset, and returns
;; {:items [...] :page-info {:next-cursor …}} — the same enveloped shape a real
;; cursor-paginated server would produce, so the feed lifecycle (page-0 ensure,
;; load-more cursor derivation, in-flight dedupe, the nil terminal) runs end to
;; end. It delegates to the framework-shipped `:rf.http/managed-canned-success`
;; with `:after-ms`, so the reply rides framework `:dispatch-later` and stays
;; visible on the trace tape and safe to time-travel.

(def ^:private total-items 26)

(def ^:private demo-items
  "A flat dataset the stub pages over by cursor (the offset of the next row)."
  (vec (for [i (range total-items)]
         {:id i :title (str "Activity item #" (inc i))})))

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply (the canned-success fx's
   `:after-ms`). Small but non-zero so the load-more spinner is visible. A demo
   knob, not a production value."
  140)

(defn- demo-page-for-cursor
  "Synthesise one enveloped page starting at `offset` (the cursor; nil ⇒ 0).
   The next-cursor is the offset of the following page, or nil at the end —
   exactly the nil terminal `:next-page-param` reads."
  [cursor]
  (let [offset (or cursor 0)
        items  (->> demo-items (drop offset) (take page-size) vec)
        next   (+ offset (count items))]
    {:items     items
     :page-info {:next-cursor (when (< next total-items) next)}}))

;; This override stands in for the backend. It synthesises the per-cursor page,
;; then hands the rest to `:rf.http/managed-canned-success` via the registrar,
;; adding `:after-ms` so the reply rides framework `:dispatch-later`. It reuses
;; the incoming args-map, which keeps the reply addressing (the per-page reply
;; handlers the resource runtime set up) intact.
(rf/reg-fx :infinite-feed.demo/http-stub
  {:doc       "Demo override for `:rf.http/managed`: synthesises one enveloped
               page per request cursor so the feed runs standalone, then
               delegates to `:rf.http/managed-canned-success` with `:after-ms`."
   :platforms #{:client}}
  (fn fx-managed-feed-demo [frame-ctx args-map]
    (let [cursor  (get-in args-map [:request :params :cursor])
          payload (demo-page-for-cursor cursor)
          stub    (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))

;; ============================================================================
;; ROUTES — route entry ensures PAGE 0 (the first load), and OWNS the feed
;; ============================================================================
;;
;; A route declares an infinite resource exactly as it declares any resource.
;; Route entry ensures page 0 under the route's owner; route leave releases it.
;; Load-more is a user-caused event during the route's lifetime, not a step in
;; the route's load plan. `:blocking?` blocks on page 0 only.
;; See docs/resources/how-to/paginate-a-feed.md (§Let the route own the feed).

(rf/reg-route :infinite-feed.app/home
  {:doc  "Landing page."} "/")

(rf/reg-route :infinite-feed.app/timeline
  {:doc   "The feed — ensures PAGE 0 of the infinite :feed/timeline on entry."
   :resources
   [{:resource  :feed/timeline
     :params    (fn [_route] {})
     :blocking? true}]} "/timeline")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."} "/_404")

;; ============================================================================
;; PAGES — passive reads; the runtime owns the accumulation
;; ============================================================================
;;
;; The view is passive: it reads the combined infinite view-model and dispatches
;; one causal event. The query map is the feed identity (resource + scope +
;; params) — the same shape the route's `:resources` plan ensured under, so the
;; sub reads the entry the route owns.

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
  ;; This route's `:resources` metadata ensured page 0 on entry. The view reads
  ;; the whole feed view-model passively via `[:rf.resource/infinite-state …]`.
  (let [feed @(subscribe [:rf.resource/infinite-state feed-query])]
    [:div
     [:h1 "Timeline"]
     (cond
       ;; First load (page 0), no usable data yet → skeleton.
       (:loading? feed)
       [:p {:data-testid "feed-skeleton"} "Loading the feed…"]

       ;; First load (page 0) failed with no usable data → full error screen.
       ;; A page-0 failure with no accumulated pages settles `:error` and
       ;; `:status :error`, just like a scalar resource. `:error` is for
       ;; first-load failure; a load-more (page N>0) failure uses `:page-error`
       ;; instead and keeps the feed. See docs/resources/glossary.md#resource-status.
       (:error feed)
       [:p.error {:data-testid "feed-error"} "Could not load the feed."]

       :else
       [:<>
        ;; The merged flat list — the headline `:items` read. The runtime
        ;; concatenates pages in load order and memoises the merge; the view
        ;; just renders rows.
        (into [:ul {:data-testid "feed-list"}]
              (for [item (:items feed)]
                ^{:key (:id item)} [feed-row item]))

        ;; A load-more failure (page N>0 — we have data) keeps every page
        ;; visible and surfaces :page-error — the inline retry. This is the
        ;; third error channel: a load-more failure leaves the feed intact,
        ;; unlike the first-load :error full-screen above.
        (when (:page-error feed)
          [:p.warn {:data-testid "feed-page-error"}
           "Couldn't load more — tap retry."])

        ;; The load-more affordance: a spinner while a page is in flight, the
        ;; button while there is a next page, an end-of-feed marker otherwise.
        ;; The button dispatches one causal event and the runtime derives the
        ;; next page param from the loaded tail. The event carries a `:cause`
        ;; but no `:owner` — the route already owns the feed, so load-more just
        ;; extends it (owner keeps it alive; cause explains why it grew).
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
;; The React root is created lazily inside `run` (not at ns-load) so a test can
;; mount the app in its own frame. The app establishes its frame in one spot:
;; the render-root `frame-provider {:id …}`. On first mount the provider creates
;; the `app-frame` and applies its config — `:url-bound? true` so the frame owns
;; the browser URL, and the `:rf.http/managed` override that points page fetches
;; at the canned stub so the example runs standalone. The provider also scopes
;; that frame, so every in-tree dispatch/subscribe resolves to it. On hot reload
;; it reuses the same frame. The feed needs no boot seed: route entry's
;; `:resources` plan ensures page 0, so the frame carries no `:initial-events`.
;; See docs/guide/glossary.md#frame-provider.

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; The provider creates the app frame (with its config) on first mount and
    ;; reuses it on hot reload. Route entry ensures page 0, so no `:initial-events`.
    (rdc/render @react-root
                [rf/frame-provider {:id           app-frame
                                    :doc          "Infinite-feed demo frame."
                                    :url-bound?   true
                                    :fx-overrides {:rf.http/managed :infinite-feed.demo/http-stub}}
                 [root-view]])))
