(ns re-frame.routing-conduct-dom-cljs-test
  "NAVIGATION CONDUCT, MEASURED THROUGH THE REAL BROWSER URL (rf2-hic-042).

  A two-route application on routing's own public door, driven by the
  ACTUAL History API: `history.replaceState` for the cold deep link,
  routing's own `:rf.nav/push-url` (a real `pushState`) going forward,
  and `history.back()` / `history.forward()` — real `popstate` events,
  reaching the real `popstate` listener a `:url-bound? true` frame's
  lifecycle installs. Nothing here is dispatched at the framework in
  place of a browser gesture.

  ## Why this file exists beside the Hicasso navigation witness

  `re-frame.hicasso.examples.navigation.conduct-dom-cljs-test` (PR #7970)
  already measures focus-on-route and scroll restoration in a browser,
  and this file does not repeat what that one proves. The merged-PR audit
  of #7970 found two things it did NOT prove, and both are properties of
  ROUTING rather than of a view substrate, so they are witnessed here:

  1. **The focus recipe was document-global.** `document.querySelector`
     over a marker every route shares focuses the FIRST match anywhere on
     the page — so a second root, or a shell heading above the app, takes
     the landing focus instead. Every row over there mounts exactly one
     application, so the near-miss was legal and untested. The recipe
     below is scoped to its own root, and [[a-deep-link-through-the-real-url-focuses-inside-its-own-root]]
     mounts a DECOY marked heading ahead of the app to prove the scope is
     load-bearing rather than decorative.

  2. **No row exercised browser location or history at all.** The deep
     link was an `:initial-events` dispatch and Back was a hand-written
     `:rf.route/handle-url-change` carrying a synthetic `:popstate` — the
     conduct the door produces, but not the door. Here the deep link is
     a URL the browser is genuinely sitting on when the frame registers,
     and Back/Forward are the browser's own buttons.

  ## What this file deliberately does not witness

  **Prefetch.** All three positions of the closed intent class
  (`re-frame.routing.link/prefetch-intent-keys` — `:on-mouse-enter` /
  `:on-focus` / `:on-touch-start`) are driven with REAL DOM events in
  `re-frame.ui.route-link-dom-cljs-test` (rf2-2n3p, PR #8017), which also
  carries an assertion that reds the moment the class grows. Rebuilding
  that here would add a second copy of a covered proof and a second thing
  to keep in step with the law.

  ## The URL is borrowed, and given back

  Every row that moves the address bar records `js/location.href` first
  and `replaceState`s it back in its trailing step — the step both the
  success and failure paths reach. `pushState` does not reload, and no
  row ever goes back PAST the entry it started on, so the runner's
  execution context is never destroyed. Rows never navigate the page.

  Per Spec 012 §URL changes are events, §Scroll restoration, §Multi-frame
  routing. ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  discovers it; `:node-test` loads it too, where every engine-dependent
  row degrades to a STATED skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.substrate.adapter :as substrate]
            [re-frame.test-support :as test-support]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a navigation-conduct witness needs a real browser — " why)))

;; ---------------------------------------------------------------------------
;; The application: two routes, one root, one heading per pane
;; ---------------------------------------------------------------------------

(def ^:private root-id
  "The id of the element this application mounts into — and the scope its
  focus recipe is confined to. An application that mounts one root still
  shares a document with whatever else is on the page (a shell header, a
  second application, a portal), so the recipe names its own root rather
  than the document."
  "rf2-nav-conduct-root")

(def ^:private heading-selector
  "The one element per pane a completed navigation focuses. A data
  attribute rather than an id: it says *this is the heading the route
  arrived at*, which is a claim about the pane's role in navigation."
  "[data-route-heading]")

(def ^:private articles
  {"deep-space"    "Deep space, and how to get there"
   "shallow-water" "Shallow water"})

(def list-route ::list)
(def article-route ::article)

(def ^:private list-url "/rf2-nav-conduct/list")

(defn- article-url [slug] (str "/rf2-nav-conduct/article/" slug))

;; --- the focus-on-route recipe --------------------------------------------

(defn pane-shown
  "The routes' `:on-match` handler, as a plain fn so the structural row can
  read what it returns without a browser.

  It names `:root`, and that single key is the whole correction the audit
  of PR #7970 asked for: the effect resolves its heading INSIDE this
  application's own root, so a marked heading belonging to anything else
  on the page cannot take the landing focus."
  [_ _]
  {:fx [[::focus-heading {:root     (str "#" root-id)
                          :selector heading-selector}]]})

(rf/reg-event ::pane-shown
  {:doc "The routes' `:on-match` — a navigation COMMITTED, so ask for focus
  to move to whichever pane is now on screen. Fire-and-forget, and never
  taken on a navigation that was blocked or on an ordinary re-render."}
  pane-shown)

(rf/reg-fx ::focus-heading
  {:doc       "Move focus to the element `:selector` names WITHIN the element
  `:root` names, on the first frame after the commit that put it there, and
  WITHOUT scrolling. Either selector matching nothing is a no-op.

  Two lines carry the whole recipe.

  `:root` is the scope. Resolving `:selector` against the DOCUMENT takes the
  first match in document order, which is whichever marked heading the page
  happens to render first — a shell heading, or another application's root.
  That version passes every single-application witness and sends the user
  somewhere else the moment a second root exists.

  `:preventScroll` is what lets this recipe and scroll restoration COMPOSE.
  Focusing an element scrolls it into view, and this effect runs one frame
  AFTER `:rf.nav/scroll` has restored the offset a Back button asked for —
  so a plain `.focus()` here silently undoes scroll restoration on exactly
  the navigation restoration exists for, with both features looking
  installed the whole time."
   :platforms #{:client}}
  (fn [_ {:keys [root selector]}]
    ;; The lookup happens INSIDE the callback. `:on-match` dispatches during
    ;; the navigation cascade, before the substrate has committed the new
    ;; pane, so resolving now would answer the OLD pane's heading — the
    ;; version of this bug that still focuses something.
    (js/requestAnimationFrame
      (fn []
        (some-> (.querySelector js/document root)
                (.querySelector selector)
                (.focus #js {:preventScroll true}))))))

;; --- events, subs, routes, view -------------------------------------------

(rf/reg-event ::seed
  {:doc "The frame's `:initial-events` step."}
  (fn [_ _] {:db {:articles articles}}))

(rf/reg-sub ::title
  (fn [db [_ slug]] (get-in db [:articles slug])))

(defn- register-routes!
  "Register the witness's two routes.

  A FUNCTION rather than a namespace-load effect, for two reasons.
  `re-frame.test-support`'s reset fixture restores the registrar to a
  baseline captured when the `use-fixtures` form was evaluated, so a
  route registered at load is rolled back before the first row runs. And
  route PATHS are process-global in the shared node bundle: registering
  from here keeps these two out of the match table for every other
  namespace in it. The `/rf2-nav-conduct` leading segment is this
  witness's own either way (TESTING.md §Test authoring policy)."
  []
  (routing/reg-route list-route
    {:doc      "The article list."
     :on-match [[::pane-shown]]}
    list-url)
  (routing/reg-route article-route
    {:doc      "One article, by slug."
     :on-match [[::pane-shown]]}
    (article-url ":slug"))
  nil)

(rf/reg-view* ::app
  (fn app []
    (let [route  @(rf/subscribe [:rf.route/id])
          params @(rf/subscribe [:rf.route/params])
          slug   (:slug params)]
      [:main
       (condp = route
         list-route
         [:h1 {:data-route-heading true :tab-index -1} "Articles"]

         article-route
         [:h1 {:data-route-heading true :tab-index -1}
          (or @(rf/subscribe [::title slug]) "Unknown article")]

         [:p.no-route "no route"])
       ;; Tall enough that `window.scrollTo` genuinely moves the page. A
       ;; document shorter than the viewport answers 0 to `window.scrollY`
       ;; however far you ask it to scroll, and 0 is also the right answer
       ;; for a working scroll-to-top — so without real height the whole
       ;; scroll half of this file would be green on a page that never
       ;; moved. Both panes carry it, so the document does not shrink out
       ;; from under a saved offset on a navigation.
       [:div.rf2-nav-conduct-filler {:style {:height "4000px"}}]])))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     ;; This suite creates its own top-level url-bound frame, so it needs a
     ;; clear ambient scope: the frame's `:initial-events` (and routing's
     ;; synchronous initial URL sync) must drain as a top-level cascade
     ;; rather than as mid-cascade child-frame creation.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (routing/reset-counters!)
                      ;; The scroll cache is a module-level `defonce` and
                      ;; survives the runtime reset, so a position saved by
                      ;; one row would still be there for the next.
                      (routing/reset-scroll-cache!)
                      (register-routes!))}))

;; ---------------------------------------------------------------------------
;; Borrowing the address bar
;; ---------------------------------------------------------------------------

(def ^:private runner-url
  "The URL the test page was served from, captured at NAMESPACE LOAD —
  before any row has run, and so before anything in this file has touched
  the address bar. Every row that moves it puts this back."
  (when (browser?) (.-href js/location)))

(defn- at-url!
  "Put the browser on `url` WITHOUT adding a history entry and without
  reloading — the state a user is in when they paste a link and hit
  return, as far as a single-page bundle can reproduce it. Called before
  the url-bound frame exists, so routing's own initial sync is what reads
  it."
  [url]
  (.replaceState js/window.history nil "" url))

(defn- restore-runner-url! []
  (when (and (browser?) runner-url)
    (.replaceState js/window.history nil "" runner-url)))

(defn- path [] (.-pathname js/location))

;; ---------------------------------------------------------------------------
;; Mounting, reading, waiting
;; ---------------------------------------------------------------------------

(defn- decoy!
  "A marked heading belonging to something that is NOT this application —
  a shell header, or another root — appended to the document BEFORE the
  application's own root so it wins document order.

  This is the near-miss the whole scoping question is about, and the rows
  below assert both halves: that the decoy is what a document-wide lookup
  would find, and that it is not what holds focus."
  []
  (let [h (.createElement js/document "h1")]
    (.setAttribute h "data-route-heading" "true")
    (.setAttribute h "tabindex" "-1")
    (.setAttribute h "id" "rf2-nav-conduct-decoy")
    (set! (.-textContent h) "A shell heading that belongs to no route")
    (.appendChild js/document.body h)
    h))

(defn- mount!
  "Create the application's root element, append it to the document, and
  commit the first render synchronously so the pane is on the page in the
  same task the frame was created in."
  [frame-id]
  (let [container (.createElement js/document "div")]
    (.setAttribute container "id" root-id)
    (.appendChild js/document.body container)
    (let [root (rdc/create-root container)]
      (react-dom/flushSync
        (fn []
          (rdc/render root [rf/frame-provider {:frame frame-id}
                            [(rf/view ::app)]])))
      {:container container :root root})))

(defn- teardown!
  "Unmount, detach everything this row put on the page, give the address
  bar back, and drop the frame. Runs on the success and failure paths
  alike, so a row that threw cannot make the next row's reading wrong."
  [{:keys [container root]} extra-nodes frame-id]
  (try (.unmount root) (catch :default _ nil))
  (try (.remove container) (catch :default _ nil))
  (doseq [n extra-nodes] (try (.remove n) (catch :default _ nil)))
  (restore-runner-url!)
  ;; The page is shared with every other namespace in the bundle, and a row
  ;; that ends 900px down leaves the next one reading an offset it never set.
  (try (.scrollTo js/window 0 0) (catch :default _ nil))
  (try (rf/destroy-frame! frame-id) (catch :default _ nil))
  nil)

(defn- active [] (.-activeElement js/document))

(defn- active-label
  "How a failure NAMES whatever holds focus — short enough to read in a
  message, specific enough to tell the decoy from a pane's heading."
  []
  (let [el (active)]
    (cond
      (nil? el)                    "nothing"
      (seq (.-id el))              (str "#" (.-id el))
      (.hasAttribute el "data-route-heading")
      (str "the route heading " (pr-str (.-textContent el)))
      :else                        (str "<" (.toLowerCase (.-tagName el)) ">"))))

(defn- route-id [frame-id] (rf/subscribe-once [:rf.route/id] {:frame frame-id}))

(defn- app-heading [{:keys [container]}]
  (.querySelector container heading-selector))

(defn- focused-heading
  "Wait until the application's OWN heading holds focus, then answer it.
  Polls rather than counting frames, because a deadline reports which
  landing never arrived and a frame count reports only that a line was
  false."
  [m label]
  (-> (test-support/poll-until
        #(let [h (app-heading m)]
           (and (some? h) (identical? h (active))))
        {:label label})
      (.then (fn [_] (app-heading m)))))

(defn- settled-at
  "Wait until the route slice reads `expected`, then flush the render so
  the pane the assertions read is the pane the route names."
  [frame-id expected label]
  (-> (test-support/poll-until #(= expected (route-id frame-id)) {:label label})
      (.then (fn [_] (substrate/flush-render!) true))))

(defn- scroll-y [] (js/Math.round (.-scrollY js/window)))

(defn- scrolled-to!
  "Scroll the page to `y` and assert the engine took it. Every scroll row
  runs through here: a document that cannot scroll answers 0, and 0 is
  also the right answer for a working scroll-to-top, so an unasserted
  scroll would make the rest of a row meaningless without failing it."
  [y]
  (.scrollTo js/window 0 y)
  (is (= y (scroll-y))
      (str "precondition: the page must actually scroll to " y
           " — it reads " (scroll-y) ". Without this the assertions below"
           " are green on a page that never moved"))
  y)

(defn- finish
  "The single trailing step both paths reach. A rejection is reported as
  this row's failure rather than left to hang the run; teardown happens
  once, `done` is called once, and nothing follows it."
  [p m extra frame-id done]
  (-> p
      (.catch (fn [e]
                (is false (str "the flow never settled: "
                               (or (ex-message e) (str e)) " "
                               (pr-str (ex-data e))))
                nil))
      (.then (fn [_] (teardown! m extra frame-id) (done)))))

;; ---------------------------------------------------------------------------
;; The structural half — the recipe is SCOPED, and says so without a browser
;; ---------------------------------------------------------------------------

(deftest the-focus-recipe-names-a-root-and-not-the-document
  (let [[fx-id args] (first (:fx (pane-shown {} [::pane-shown])))]
    (testing "`:on-match` asks for exactly one effect, and it is the focus one"
      (is (= 1 (count (:fx (pane-shown {} [::pane-shown])))))
      (is (= ::focus-heading fx-id)))

    (testing "the effect carries a ROOT scope alongside the selector"
      (is (= (str "#" root-id) (:root args))
          "without `:root` the effect resolves its selector against the
           whole document and focuses the first marked heading on the page,
           whoever it belongs to — the defect the browser rows below mount
           a decoy to catch")
      (is (= heading-selector (:selector args))))))

;; ---------------------------------------------------------------------------
;; The deep link — a URL the browser is genuinely sitting on
;; ---------------------------------------------------------------------------

(deftest a-deep-link-through-the-real-url-focuses-inside-its-own-root
  (if-not (browser?)
    (skip! ":node-test has no location, history or focus model")
    (async done
      (let [slug     "deep-space"
            frame-id ::deep-link
            _        (at-url! (article-url slug))
            decoy    (decoy!)
            ;; The frame's registration is what installs the real popstate
            ;; listener AND syncs the current browser URL into the route
            ;; slice under cause `:initial`. Nothing dispatches the route:
            ;; the address bar is the input.
            _        (rf/make-frame {:id             frame-id
                                     :url-bound?     true
                                     :initial-events [[::seed]]})
            m        (mount! frame-id)]
        (-> (js/Promise.resolve
              (testing "the cold URL resolved itself — routing read the
                        address bar, not an initial event"
                (is (= (article-url slug) (path))
                    "precondition: the browser really is on the deep link")
                (is (= article-route (route-id frame-id))
                    "the url-bound frame's initial sync matched the URL the
                     browser was already showing")
                (is (= slug (:slug (rf/subscribe-once [:rf.route/params]
                                                      {:frame frame-id})))
                    "including its path parameter")))
            (.then (fn [_]
                     (substrate/flush-render!)
                     (testing "a document-wide lookup does NOT answer this
                               application's heading — the near-miss is live"
                       (is (some? (app-heading m))
                           "precondition: the pane rendered a marked heading of
                            its own")
                       (is (not (identical? (app-heading m)
                                            (.querySelector js/document heading-selector)))
                           "the decoy precedes the application in document order,
                            so `document.querySelector` over the shared marker
                            answers something that is not this application's
                            heading. A recipe scoped to the DOCUMENT would focus
                            that instead — and would still leave `activeElement`
                            on a marked heading, which is why the row below is
                            written against the application's OWN node rather
                            than against the marker")))
                     (focused-heading m "the deep link's landing focus")))
            (.then (fn [h]
                     (is (identical? h (active))
                         (str "focus is on " (active-label) ", not the article's
                              own heading. A URL opened cold is where a keyboard
                              or screen-reader user starts, and the browser moves
                              focus nowhere by itself — `<body>` holds it and the
                              first Tab press starts at the top of the document"))
                     (is (not (identical? decoy (active)))
                         "and NOT on the decoy — the root scope is what
                          decides this, and it is the only thing that does")
                     (is (= (get articles slug) (.-textContent h))
                         "the heading names the article that was opened, so the
                          landing is not merely somewhere but somewhere
                          identifiable")))
            (finish m [decoy] frame-id done))))))

;; ---------------------------------------------------------------------------
;; Back and Forward — the browser's own buttons
;; ---------------------------------------------------------------------------

(def ^:private deep-offset
  "Far enough down the list that landing back at the top would be
  unmistakable, and well inside the filler so nothing is clamped."
  900)

(deftest the-browsers-own-back-and-forward-move-the-route-the-url-and-the-page
  (if-not (browser?)
    (skip! ":node-test has no history, scroll or focus model")
    (async done
      (let [slug     "deep-space"
            frame-id ::back-forward
            _        (at-url! list-url)
            decoy    (decoy!)
            _        (rf/make-frame {:id             frame-id
                                     :url-bound?     true
                                     :initial-events [[::seed]]})
            m        (mount! frame-id)]
        (-> (focused-heading m "the list's landing focus")
            (.then
              (fn [_]
                (testing "forward: routing's own push moves the REAL address bar"
                  (scrolled-to! deep-offset)
                  (rf/dispatch-sync [:rf.route/navigate
                                     {:to article-route :params {:slug slug}}]
                                    {:frame frame-id})
                  (substrate/flush-render!)
                  (is (= article-route (route-id frame-id)))
                  (is (= (article-url slug) (path))
                      (str "`:rf.nav/push-url` is a real `history.pushState`, so"
                           " the address bar reads the destination; it reads "
                           (pr-str (path)) ". A copied link, a reload or a"
                           " bookmark all take the URL at its word"))
                  (is (= 0 (scroll-y))
                      (str "a forward navigation lands at the top and this one is"
                           " at " (scroll-y) ". Arriving at a new page already"
                           " scrolled down is the defect every router ships a"
                           " scroll strategy for")))

                ;; THE BACK BUTTON. Not a dispatch standing in for one: a
                ;; real `history.back()`, which fires a real `popstate` on
                ;; the browser's own task loop, which reaches the listener
                ;; the url-bound frame's lifecycle installed.
                (.back js/window.history)
                (settled-at frame-id list-route
                            "the real Back button's popstate reaching the route slice")))
            (.then
              (fn [_]
                (testing "Back restored the route, the URL and the offset"
                  (is (= list-url (path))
                      "the address bar went back with the route")
                  (is (= deep-offset (scroll-y))
                      (str "Back restored " (scroll-y) " rather than " deep-offset
                           ". The list is where the user was reading; sending"
                           " them to the top of it is the whole reason the"
                           " capture/restore pair exists")))
                (focused-heading m "the real Back button's landing focus")))
            (.then
              (fn [h]
                (is (= "Articles" (.-textContent h))
                    "focus is the LIST's heading — a recipe that focused
                     whatever it focused last would still be holding the
                     article heading, which has just been unmounted")
                (is (not (identical? decoy (active)))
                    (str "and not the decoy, which currently holds "
                         (active-label) " nowhere near it"))
                (is (= deep-offset (scroll-y))
                    (str "the landing focus scrolled the page back to " (scroll-y)
                         ". The focus recipe runs one frame AFTER the restore,"
                         " so a `.focus()` without `:preventScroll` silently"
                         " undoes scroll restoration on exactly the navigation"
                         " it exists for, with both features looking installed"))

                ;; AND FORWARD. The other half of the pair, and the one an
                ;; implementation that special-cases "going back" misses.
                (.forward js/window.history)
                (settled-at frame-id article-route
                            "the real Forward button's popstate reaching the route slice")))
            (.then
              (fn [_]
                (testing "Forward is a URL-driven door too"
                  (is (= (article-url slug) (path))
                      "the address bar went forward with the route")
                  (is (= 0 (scroll-y))
                      (str "the article was left at the top, so `:restore` puts"
                           " it back at the top — it reads " (scroll-y) ". The"
                           " list's offset (" deep-offset ") appearing here"
                           " would mean the cache had handed back somebody"
                           " else's position: a restore keyed by \"the last"
                           " thing saved\" rather than by URL")))
                (focused-heading m "the real Forward button's landing focus")))
            (.then
              (fn [h]
                (is (= (get articles slug) (.-textContent h))
                    "and focus followed Forward to the article's own heading")))
            (finish m [decoy] frame-id done))))))
