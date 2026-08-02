(ns re-frame.bench.hicasso.shapes.route-link-dom-cljs-test
  "**THE FIFTH TIER-1 SHAPE'S CLICK WITNESS** (rf2-2rtt6.54).

  The census counts 106 route-links and the charter names the form
  tier-1. The roster's ported anchors are now
  [[re-frame.bench.hicasso.front.route-link/route-link]] calls, and this
  file witnesses the half a grammar test cannot: **a real `MouseEvent`
  on a mounted ported card, a real route change through the routing
  cascade, and the page re-rendering on the navigation commit** — which
  is also a framework runtime sub (`[:rf/route]`) read through the arm's
  collector, end to end.

  Four claims:

  1. **The ported anchors are real routed anchors.** The card's three
     links and the comment byline mount as `HTMLAnchorElement`s whose
     hrefs the ROUTER synthesised — no hand-built URL survives the port.
  2. **A plain click navigates.** The route slice moves to the link's
     destination, and the `[:rf/route]`-reading boundary re-renders —
     the navigation commit reaches the index like any other.
  3. **A modifier click stays native.** Nothing dispatches, nothing
     moves, nothing re-renders; the browser keeps open-in-new-tab.
  4. **`::h/prevent` composes as the declarative veto.** A route-link
     whose `:on-click` carries `[::h/prevent [:conduit/show-your-feed]]`
     cancels its navigation and dispatches the app intent instead — the
     cancelable-navigation case the prevent head was built for — while
     its unvetoed sibling (the mutation control) still navigates.

  Navigation of the HARNESS page is suppressed by a document-level guard,
  exactly as `freehand/route_link_matrix_dom_cljs_test` suppresses it.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt :refer [sub]]
            [re-frame.bench.hicasso.front.route-link :as link]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.card :as card]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.bench.hicasso.shapes.ordinary :as ordinary]
            [re-frame.subs :as subs]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::shape-route-link)

(def ^:private seed {:articles 2 :comments 4 :tags 2})

(defn- skip! [why]
  (is true (str "the route-link witness needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; The page: the ported card, the ported comment column's byline shape,
;; a route display, and the veto pair
;; ---------------------------------------------------------------------------

(def !runs (atom {}))
(defn- ran! [k] (swap! !runs update k (fnil inc 0)) nil)
(defn- reset-runs! [] (reset! !runs {}) nil)
(defn- runs [k] (get @!runs k 0))

(defview where-am-i
  "The `[:rf/route]` display — a framework runtime sub read through the
  collector, so claim 2's re-render is the arm's own machinery answering
  for the navigation commit."
  [_]
  (ran! :where)
  (let [route (sub [:rf/route])]
    [:span {:data-testid "where-am-i"}
     (if-some [id (:route-id route)]
       (str (name id) "/" (get-in route [:params :username]
                                   (get-in route [:params :slug] "")))
       "nowhere")]))

(defview card-host
  "The ported census card, mounted whole — its three route-links are the
  anchors the clicks below land on."
  [_]
  (ran! :card-host)
  (card/card (:slug (m/article 0))))

(defview veto-pair
  [_]
  (ran! :veto-pair)
  [:div
   (link/route-link {:to :conduit.profile/show :params {:username "jane"}
                     :class "vetoed" :data-testid "vetoed-link"
                     :on-click [:re-frame.hicasso/prevent [:conduit/show-your-feed]]}
     "vetoed")
   (link/route-link {:to :conduit.profile/show :params {:username "riku"}
                     :class "unvetoed" :data-testid "unvetoed-link"}
     "control")])

(defview nav-page
  [_]
  (ran! :page)
  [:div.nav-page
   [where-am-i {}]
   [veto-pair {}]
   [card-host {}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh! []
  (lane/leave-act-environment!)
  (m/make-frame! frame-id seed)
  (m/reseed! frame-id seed)
  (reset-runs!)
  frame-id)

(defn- mount! []
  (mount/root! (mount/fresh-container!) frame-id [nav-page {}]))

(defn- q [handle sel] (.querySelector (:container handle) sel))

(defn- text [handle testid]
  (some-> (q handle (str "[data-testid=\"" testid "\"]")) (.-textContent)))

(defn- current-route []
  (subs/subscribe-once [:rf/route] {:frame frame-id}))

(defn- click!
  "Fire a real `MouseEvent` at `node` under a document-level guard that
  keeps a natively-handled click from navigating the harness page."
  ([node] (click! node #js {}))
  ([node event-init]
   (let [guard (fn [e] (.preventDefault e))]
     (.addEventListener js/document "click" guard)
     (try
       (.dispatchEvent node (js/MouseEvent. "click"
                                            (js/Object.assign
                                              #js {:bubbles true :cancelable true}
                                              event-init)))
       (finally (.removeEventListener js/document "click" guard))))))

(defn- settled
  "Give the routing cascade its drain and React its flush, then run
  `assert-fn` and `done`."
  [assert-fn done]
  (js/setTimeout (fn []
                   (mount/settle!)
                   (assert-fn)
                   (done))
                 15))

;; ===========================================================================
;; 1 — the ported anchors are real routed anchors
;; ===========================================================================

(deftest the-ported-census-anchors-are-real-routed-anchors
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [author (:username (:author (m/article 0)))]
            (doseq [[sel href] [[".author-link" (str "/profile/" author)]
                                [".author"      (str "/profile/" author)]
                                [".preview-link" (str "/article/" (:slug (m/article 0)))]]]
              (let [a (q handle sel)]
                (is (instance? js/HTMLAnchorElement a)
                    (str sel " is a real anchor"))
                (is (= href (.getAttribute a "href"))
                    (str sel "'s href is the router's synthesis — the port no "
                         "longer hand-builds the URL the router owns")))))
          (finally (mount/release! handle)))))))

(deftest the-comment-byline-is-a-routed-anchor-too
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [ordinary/screen {}])]
        (try
          (let [a (q handle ".comment-author")]
            (is (instance? js/HTMLAnchorElement a))
            (is (re-matches #"/profile/.+" (.getAttribute a "href"))
                "ordinary.cljs's byline names a route, not a URL"))
          (finally (mount/release! handle)))))))

;; ===========================================================================
;; 2 — a real click, a real route change, a real re-render
;; ===========================================================================

(deftest a-plain-click-navigates-and-the-page-re-renders
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)
            author (:username (:author (m/article 0)))]
        (is (= "nowhere" (text handle "where-am-i")) "no route yet")
        (reset-runs!)
        (click! (q handle ".author"))
        (settled
          (fn []
            (is (= :conduit.profile/show (:route-id (current-route)))
                "the routing cascade installed the link's destination")
            (is (= {:username author} (:params (current-route)))
                "with the census author's params — never a parsed URL")
            (is (= (str "show/" author) (text handle "where-am-i"))
                "and the [:rf/route]-reading boundary re-rendered: the
                 navigation commit reached the index like any other")
            (is (= 1 (runs :where)) "exactly one body ran for it")
            (is (= 0 (runs :card-host)) "the card did not — it reads no route")
            (mount/release! handle))
          done)))))

(deftest a-modifier-click-stays-native
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (reset-runs!)
        (click! (q handle ".author") #js {:metaKey true})
        (settled
          (fn []
            (is (nil? (:route-id (current-route)))
                "nothing dispatched — the modifier click kept its
                 open-in-new-tab meaning")
            (is (= "nowhere" (text handle "where-am-i")))
            (is (= 0 (runs :where)) "and nothing re-rendered")
            (mount/release! handle))
          done)))))

;; ===========================================================================
;; 3 — the prevent head is the declarative veto, and it composes
;; ===========================================================================

(deftest a-prevent-veto-cancels-the-navigation-and-dispatches-instead
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (is (false? (subs/subscribe-once [:conduit/your-feed?] {:frame frame-id})))
        (click! (q handle ".vetoed"))
        (js/setTimeout
          (fn []
            (mount/settle!)
            (is (nil? (:route-id (current-route)))
                "the navigation was vetoed — activate-link! saw
                 defaultPrevented and stood down")
            (is (true? (subs/subscribe-once [:conduit/your-feed?] {:frame frame-id}))
                "and the wrapped app intent dispatched in its place: one
                 click, one semantic event")
            ;; The MUTATION CONTROL: the same anchor without the veto
            ;; navigates, so the claim above cannot pass vacuously.
            (click! (q handle ".unvetoed"))
            (js/setTimeout
              (fn []
                (mount/settle!)
                (is (= :conduit.profile/show (:route-id (current-route)))
                    "the unvetoed sibling navigates — the veto, not the
                     machinery, is what cancelled the first click")
                (is (= {:username "riku"} (:params (current-route))))
                (mount/release! handle)
                (done))
              15))
          15)))))

;; ===========================================================================
;; Teardown
;; ===========================================================================

(deftest the-route-link-page-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (click! (q handle ".author"))
        (js/setTimeout
          (fn []
            (mount/settle!)
            (mount/unmount! handle)
            (js/setTimeout (fn []
                             (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                    (rt/residue)))
                             (rt/reset-runtime!)
                             (done))
                           8))
          15)))))
