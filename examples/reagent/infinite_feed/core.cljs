(ns infinite-feed.core
  "Worked example for the EP-0021 INFINITE RESOURCE primitive
   ([Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds)).

   A single growing feed — a load-more / infinite-scroll timeline — composed
   as proper re-frame2: a `reg-resource` with `:infinite true`, owned by the
   route, read entirely through the PASSIVE infinite subscription family, with
   accumulation driven by a CAUSAL `:rf.resource/load-more` event. There is no
   app-db list slice, no `:loading-more?` flag, no cursor threading, and no
   append reducer — the runtime owns all of it (that is the whole point of the
   primitive; before EP-0021 an app hand-rolled every one of those by hand).

   It teaches, in one cohesive app, the four facts the primitive surfaces:

   - ONE FEED, MANY PAGES — `:infinite true` plus a `:next-page-param`
     derivation makes the resource a growing ordered page sequence held as
     ONE cache entry. Route entry ensures PAGE 0; the view reads the merged
     list passively.
   - LOAD-MORE IS A CAUSAL EVENT — the \"Load more\" button dispatches
     `[:rf.resource/load-more …]`. The view never fetches and never advances
     a cursor; the runtime derives the next page param from the loaded tail.
     It carries a `:cause` but NO `:owner` — the ROUTE already owns the feed
     for its lifetime; load-more extends that one owned entry, it does not
     mint a second owner (owner keeps alive; cause explains why).
   - THE TERMINAL IS `nil` — `:next-page-param` returning nil is the single
     end-of-feed signal; the view reads `:has-next-page?` and shows an
     end-of-feed marker instead of the button.
   - THE THREE ERROR CHANNELS — a LOAD-MORE failure (page N>0) keeps every
     accumulated page visible and surfaces `:page-error` (\"couldn't load more —
     retry\") while the feed stays `:loaded` (the third channel). A PAGE 0
     FIRST-load failure with no accumulated pages settles the first-load
     `:error` channel + `:status :error` (Spec 016 reserves `:error` for
     first-load, `:page-error` for load-more), so the view shows the full error
     screen on `:error` and the inline retry on `:page-error` — distinct axes,
     never conflated.

   The view reads the combined `[:rf.resource/infinite-state …]` view-model —
   `:items` (the merged flat list, the headline read), `:has-next-page?`,
   `:fetching-next?`, `:page-error`, `:loading?`, `:has-data?` — and dispatches
   one causal event. Scope is the fail-closed leak boundary: this feed is
   public (the same for every viewer), so it carries the explicit, auditable
   `:rf.scope/global` claim (a per-user feed would carry a scope resolver).

   This feed's pages are ENVELOPED — each page is `{:items [...] :page-info
   {…}}`, not a bare vector — so the resource declares the REQUIRED
   `:page->items` accessor (`:items`). The runtime is loud over guessing: a
   non-vector page with no accessor raises `:rf.error/infinite-missing-page-
   accessor` at the merge. (A feed whose pages are already bare vectors needs
   no accessor — they flatten by identity.)

   The example ships no backend, so it overrides `:rf.http/managed` with a
   per-cursor canned stub that delegates to the framework-shipped
   `:rf.http/managed-canned-success` (Spec 014 §Testing — the same reply shape
   a live server would produce), so every page fetch exercises a REAL fetch,
   in-flight dedupe, generation/stale suppression, and the passive status flow.
   A small reply delay lets the load-more spinner render before each page
   lands. The example tree is test-free (rf2-8cevm); the example-specific
   composition coverage lives in
   `implementation/adapters/reagent/test/re_frame/infinite_feed_example_cljs_test.cljs`,
   and the infinite-resource runtime contract is pinned in
   `implementation/resources/test/`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.views]
            ;; Managed HTTP ships in day8/re-frame2-http — the single
            ;; built-in resource transport (Spec 016 §Transport). Loading
            ;; the ns registers the `:rf.http/managed` fx the resource
            ;; runtime lowers each page fetch onto.
            [re-frame.http.managed]
            ;; The framework-shipped canned-success fx the demo stub
            ;; delegates to (Spec 014 §Testing). Requiring it is the explicit
            ;; opt-in for a demo / test app with no backend.
            [re-frame.http.test-support]
            ;; Resources ship in day8/re-frame2-resources. Requiring the ns at
            ;; app boot wires the late-bind hooks + registrations (including
            ;; the infinite sub family); without it `rf/reg-resource` throws
            ;; :rf.error/resources-artefact-missing.
            [re-frame.resources]
            ;; Routing ships in day8/re-frame2-routing. The resources artefact
            ;; LATE-BINDS its `:resources` route-metadata extension into
            ;; routing, so loading both is what makes a route's `:resources`
            ;; key accepted (Spec 016 §Route integration). Route entry ensures
            ;; PAGE 0 of an infinite resource (not the whole accumulation).
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; THE INFINITE RESOURCE — one growing feed, pages held as the durable value
;; ============================================================================
;;
;; An infinite resource is an ordinary resource (identity + scope + request)
;; with two additions (Spec 016 §Registration — :infinite):
;;
;;   :infinite true           selects the infinite slice + makes
;;                            :next-page-param REQUIRED;
;;   :next-page-param         a PURE (last-page all-pages) -> next-param-or-nil
;;                            derivation. nil is the SINGLE terminal signal.
;;
;; The :request keeps its settled (params ctx) shape — for an infinite resource
;; the RESERVED ctx (its second arg) carries the resolved page context for THIS
;; page: {:rf.resource/page-param p :rf.resource/page-index i} (R8 — no new
;; arity). Page 0 is fetched with page-param nil; each load-more passes the
;; derived next cursor.
;;
;; The page param is NOT part of the feed's cache identity — two load-more
;; calls extend ONE entry, they do not create two cache keys. Only the identity
;; params (here: nothing — a single public feed) name the feed; a filter/sort
;; change would yield a DIFFERENT feed instance, not a mutation of this one.

(def ^:private page-size 8)

(rf/reg-resource :feed/timeline
  {:doc "The public activity timeline — an infinite (load-more) feed."

   :infinite       true

   ;; The feed-IDENTITY params. This demo has one public feed, so the identity
   ;; is empty; a real app puts filter/sort/search here (a change → a new feed
   ;; instance). The per-page cursor is NEVER here — it is the page param.
   :params-schema  [:map]

   ;; Public feed: the same for every viewer → the explicit, auditable global
   ;; claim. A per-user feed would carry a scope resolver instead.
   :scope          :rf.scope/global

   ;; The pages are ENVELOPED ({:items … :page-info …}), so the REQUIRED
   ;; accessor tells the runtime how to flatten each page into the merged
   ;; `:items` list. (A bare-vector page would need no accessor.)
   :page->items    :items

   ;; :request keeps its (params ctx) shape; the RESERVED ctx carries the
   ;; resolved page context for THIS page (page-param nil ⇒ first page).
   :request
   (fn [_feed-params {:rf.resource/keys [page-param page-index]}]
     {:request {:method :get
                :url    "/api/timeline"
                :params (cond-> {:limit page-size :page-index page-index}
                          page-param (assoc :cursor page-param))}
      :decode  :json})

   ;; Derive the NEXT page param from the last loaded page. Returns nil to
   ;; signal end-of-feed (the single terminal; `:has-next-page?` is then false).
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))   ;; nil ⇒ no more pages

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; A feed tag so a write could invalidate the whole feed by tag (coarse,
   ;; correct — R4); item-in-feed patching is the deferred optimistic axis.
   :tags           (fn [_feed-params _data] #{[:feed :timeline]})})

;; ============================================================================
;; DEMO BACKEND — per-cursor canned :rf.http/managed override
;; ============================================================================
;;
;; This example ships no server, so it overrides `:rf.http/managed` (the fx the
;; resource runtime lowers every page fetch onto) with a stub that synthesises
;; one enveloped page per cursor. The stub reads the `:cursor` request param
;; (absent ⇒ page 0), slices the demo dataset, and returns
;; {:items [...] :page-info {:next-cursor …}} — the SAME enveloped shape a real
;; cursor-paginated server would produce, so the feed lifecycle (page-0 ensure,
;; load-more cursor derivation, in-flight dedupe, the nil terminal) runs end to
;; end. It delegates to the framework-shipped `:rf.http/managed-canned-success`
;; with `:after-ms` so the reply rides framework `:dispatch-later` (tape-
;; visible, time-travel-safe, NOT raw js/setTimeout). Mirrors
;; examples/reagent/resources/core.cljs's stub.

(def ^:private total-items 26)

(def ^:private demo-items
  "A flat dataset the stub pages over by cursor (the offset of the next row)."
  (vec (for [i (range total-items)]
         {:id i :title (str "Activity item #" (inc i))})))

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply (via the canned-success fx's
   `:after-ms`, dispatched through `:dispatch-later` — tape-visible, NOT raw
   js/setTimeout). Small but non-zero so the load-more spinner is observable. A
   demo-seam knob, not a production value."
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

;; The resource runtime lowers every page fetch onto `:rf.http/managed`; this
;; override stands in for the backend. It synthesises the per-cursor page and
;; delegates to `:rf.http/managed-canned-success` (calling it via the registrar
;; with `:after-ms` so the canned reply rides framework `:dispatch-later`, and
;; the reply addressing the resource runtime put on the args-map — the PAGE
;; reply handlers — is preserved).
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
;; A route declares an infinite resource exactly as any resource (Spec 016
;; §Route integration). Route entry ensures PAGE 0 under the
;; `[:route route-id nav-token]` owner; route leave releases it. Load-more is a
;; user-caused event during the route's lifetime, NOT a route plan step.
;; `:blocking?` blocks on page 0 only.

(rf/reg-route :infinite-feed.app/home
  {:doc  "Landing page."
   :path "/"})

(rf/reg-route :infinite-feed.app/timeline
  {:doc   "The feed — ensures PAGE 0 of the infinite :feed/timeline on entry."
   :path  "/timeline"
   :resources
   [{:resource  :feed/timeline
     :params    (fn [_route] {})
     :blocking? true}]})

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."
   :path "/_404"})

;; ============================================================================
;; PAGES — passive reads; the runtime owns the accumulation
;; ============================================================================
;;
;; The view is PASSIVE: it reads the combined infinite view-model and dispatches
;; ONE causal event. The query map is the feed identity (resource + scope +
;; params) — the SAME shape the route's `:resources` plan ensured under, so the
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
  ;; The infinite resource was ensured (PAGE 0) by THIS route's `:resources`
  ;; metadata on entry. The view reads the WHOLE feed view-model passively via
  ;; `[:rf.resource/infinite-state …]`.
  (let [feed @(subscribe [:rf.resource/infinite-state feed-query])]
    [:div
     [:h1 "Timeline"]
     (cond
       ;; First load (page 0), no usable data yet → skeleton.
       (:loading? feed)
       [:p {:data-testid "feed-skeleton"} "Loading the feed…"]

       ;; First load (page 0) failed with no usable data → full error screen.
       ;; A page-0 FIRST-load failure with no accumulated pages settles the
       ;; first-load `:error` channel + `:status :error` (Spec 016 §Status
       ;; semantics — `:error` is reserved for first-load failure), exactly
       ;; like a scalar resource; it is NOT the `:page-error` load-more channel
       ;; (which is reserved for a page N>0 failure that keeps the feed). The
       ;; whole-feed `:refresh-error` channel stays nil here.
       (:error feed)
       [:p.error {:data-testid "feed-error"} "Could not load the feed."]

       :else
       [:<>
        ;; The merged flat list — the headline `:items` read. The runtime
        ;; concatenates pages in load order + memoises the merge; the view
        ;; just renders rows.
        (into [:ul {:data-testid "feed-list"}]
              (for [item (:items feed)]
                ^{:key (:id item)} [feed-row item]))

        ;; A LOAD-MORE failure (page N>0 — we have data) keeps every page
        ;; visible and surfaces :page-error — the inline "couldn't load more —
        ;; retry" affordance (the THIRD error channel, distinct from the
        ;; first-load :error full-screen above; a load-more failure never
        ;; collapses the feed).
        (when (:page-error feed)
          [:p.warn {:data-testid "feed-page-error"}
           "Couldn't load more — tap retry."])

        ;; The load-more affordance: a spinner while a page is in flight, the
        ;; button while there is a next page, an end-of-feed marker otherwise.
        ;; The view NEVER advances the cursor — it dispatches one causal event
        ;; (a `:cause`, NO `:owner`: the route already owns the feed, load-more
        ;; just extends it) and the runtime derives the next page param from
        ;; the loaded tail.
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
;; The React root is materialised lazily inside `run` (not at ns-load) per
;; examples/TESTING.md §Example mount-isolation convention. EP-0002: the app
;; establishes its frame explicitly (`reg-frame`), declares `:url-bound? true`
;; so it owns the browser URL, overrides `:rf.http/managed` with the per-cursor
;; canned stub (the example runs standalone), and wraps the render in a
;; `frame-provider` so every in-tree dispatch/subscribe resolves to it.

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame
    {:doc          "Infinite-feed demo frame."
     :url-bound?   true
     :fx-overrides {:rf.http/managed :infinite-feed.demo/http-stub}})
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [root-view]])))
