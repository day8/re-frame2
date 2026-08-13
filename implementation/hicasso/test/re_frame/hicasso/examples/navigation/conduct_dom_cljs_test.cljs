(ns re-frame.hicasso.examples.navigation.conduct-dom-cljs-test
  "L4 — WHAT THE BROWSER SAYS ABOUT NAVIGATION CONDUCT (rf2-hic-042;
  specification §7's routing row).

  The row's required proof is *deep link, back/forward, pending
  mutation*, and its additional surface is *dirty-leave, scroll
  restoration and focus-on-route recipes*. Those three proofs and two of
  those recipes are what this file measures, on
  `re-frame.hicasso.examples.navigation` — an ordinary two-route
  application written on the public door.

  ## Why every row here needs an engine

  Prefetch, scroll restoration and focus are the three behaviours in this
  programme that pass most easily by accident. A scroll position that
  happens to be 0 reads the same whether restoration worked or nothing
  happened at all; a focus that landed on `<body>` is indistinguishable
  from a focus that was never asked for, if all you assert is *something
  is focused*. `document.activeElement` and `window.scrollY` are not
  derivable from a tree, so `:node-test` compiles this namespace and each
  row degrades there to a STATED skip — honest, and not a measurement.

  The one row that is NOT engine-dependent is
  [[the-element-the-recipe-focuses-is-a-heading-with-the-panes-name]],
  and it runs in both lanes on purpose: it is the structural half of the
  correlation, asserting through `ht/role` and `ht/accessible-name` that
  the marker the recipe aims at sits on a real heading carrying a real
  name. Without it the browser rows would prove only that focus moved to
  *an element*, which is not the claim.

  ## The two directions, per behaviour

  **Focus.** Positive: a completed navigation puts focus on the pane it
  arrived at (deep link, and Back). Too-eager: a navigation that was
  BLOCKED must move nothing, and an ordinary re-render must move nothing
  — a recipe hung off a render, or off an *attempted* navigation, passes
  the positives and fails both of these, and would take a user out of the
  field they are still typing in.

  **Focus, third direction: the SCOPE.** Every row here mounts exactly
  one application, and on a page with one root a recipe scoped to the
  document and a recipe scoped to its own root are indistinguishable —
  which is how the document-wide version survived the audits of #7970 and
  #8031. So one row,
  [[a-deep-link-focuses-inside-the-applications-own-root]], mounts a
  marked DECOY heading ahead of the application and asserts both halves:
  that `document.querySelector` over the shared marker answers the decoy
  rather than this application's heading, and that focus lands on this
  application's heading anyway. Without the decoy the scope would be
  untested; without the first half the decoy might not be in the way.

  Every focus row reads the application's OWN node — `focused-heading`
  polls on `identical?` against the heading inside this mount's
  container, never on *something with the marker has focus*, because the
  weaker predicate is satisfied by exactly the defect above.

  **Scroll.** Positive: a forward navigation lands at the top, and Back
  restores the exact offset the page was left at. Too-eager: a
  navigation carrying `:scroll false` must move nothing, and a Back to a
  URL that was never visited must NOT inherit some other URL's saved
  position — an implementation that restored *the last position it had*
  passes the positive row and fails that one.

  Both scroll rows first assert that scrolling MOVED the page, before
  asserting anything about where it ended up. `window.scrollTo` on a
  document shorter than the viewport is a silent no-op that reads back
  0, which is also what a correct scroll-to-top reads: the precondition
  is what stops the whole scroll half being green on a page that never
  scrolled.

  ## What this file deliberately does not witness

  **Prefetch.** `re-frame.routing` warms a destination from all three
  credible-intent positions (`:on-mouse-enter` / `:on-focus` /
  `:on-touch-start`, the closed class `re-frame.routing.link/
  prefetch-intent-keys` holds), and `re-frame.ui.route-link-dom-cljs-test`
  drives them with real DOM events. Hicasso's own `h/route-link` DECLINES
  `:prefetch` outright in v0 and fails loud at the link site
  (`:rf.error/hicasso-route-link-prefetch-declined`), so there is nothing
  on this application's surface to witness. Admitting the key is a
  naming-ledger question that is still open (row 36 — *keep as taught*,
  status open), not this bead's to settle."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.navigation.events :as events]
            [re-frame.hicasso.examples.navigation.routes :as routes]
            [re-frame.hicasso.examples.navigation.subs :as subs]
            [re-frame.hicasso.examples.navigation.views :as views]
            [re-frame.hicasso.test :as ht]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.routing :as routing]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a navigation-conduct witness needs a real browser — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; The host-side scroll cache is a module-level
                      ;; `defonce` and survives the registrar reset, so a
                      ;; position saved by one row would still be there for
                      ;; the next — which is precisely what the
                      ;; never-visited-URL row is written to rule out.
                      (routing/reset-scroll-cache!)
                      (routes/register!))}))

;; ---------------------------------------------------------------------------
;; Reading the page
;; ---------------------------------------------------------------------------

(def ^:private first-article
  "The article every row navigates to, and its title — which is also the
  accessible name of the heading a landing focus must reach."
  {:slug "deep-space" :title "Deep space, and how to get there"})

(defn- node [m sel] (.querySelector (:container m) sel))

(defn- heading [m] (node m "[data-route-heading]"))

(defn- active [] (.-activeElement js/document))

(defn- active-label
  "How a failure NAMES whatever holds focus. A route heading is named by
  its text, because that is what a user would hear; everything else by
  its id or its tag. Short enough to read in a failure message."
  []
  (let [el (active)]
    (cond
      (nil? el)                               "nothing"
      (.hasAttribute el "data-route-heading") (str "the route heading "
                                                   (pr-str (.-textContent el)))
      (seq (.-id el))                         (str "#" (.-id el))
      :else                                   (str "<" (.toLowerCase (.-tagName el)) ">"))))

(defn- read-sub [m query-v] (rf/subscribe-once query-v {:frame (:frame m)}))

(defn- decoy!
  "A marked heading belonging to something that is NOT this application —
  a shell header, a banner, a second root — appended to the document
  BEFORE the application's own root, so it wins document order.

  This is the near-miss the recipe's `:root` scope exists to close, and
  it is the control that makes the scope's witness mean anything: with
  the decoy in front of it, a recipe that resolved its selector against
  the document would focus the decoy, and the row would go red."
  []
  (let [h (.createElement js/document "h2")]
    (.setAttribute h "data-route-heading" "true")
    (.setAttribute h "tabindex" "-1")
    (.setAttribute h "id" "navigation-decoy")
    (set! (.-textContent h) "A shell heading that belongs to no route")
    (.appendChild js/document.body h)
    h))

(defn- at!
  "Mount the application with its first navigation already made — the deep
  link, and the shape every other row starts from."
  ([route] (at! route nil))
  ([route params]
   (hm/mount! [views/app {}]
              {:initial-events [[::events/seed]
                                [:rf.route/navigate (cond-> {:to route}
                                                      params (assoc :params params))]]})))

(defn- finish
  "Take the mount down and read it clean — and detach anything the row
  appended to the page ITSELF, which the facade knows nothing about. A
  decoy left behind would be in the way of every row that followed."
  ([m done] (finish m nil done))
  ([m extra-nodes done]
   (doseq [n extra-nodes] (try (.remove n) (catch :default _ nil)))
   (-> (hm/unmount! m) (hm/assert-clean!) (.then done))))

(defn- finish-after
  "End the row when `p` settles, reporting a rejection as a failure rather
  than letting a deadline hang the run — and tearing the mount down
  either way, so a stuck row cannot make the next row's reading wrong."
  ([p m done] (finish-after p m nil done))
  ([p m extra-nodes done]
   (-> p
       (.catch (fn [e]
                 (is false (str "the flow never settled: "
                                (or (ex-message e) (str e)) " "
                                (pr-str (ex-data e))))))
       (.then (fn [_] (finish m extra-nodes done))))))

;; ---------------------------------------------------------------------------
;; Waiting for a frame, and waiting long enough to disbelieve
;; ---------------------------------------------------------------------------

(defn- next-frame []
  (js/Promise. (fn [resolve] (js/requestAnimationFrame #(resolve true)))))

(defn- two-frames
  "Wait two animation frames.

  This is what the NEGATIVE focus rows wait on, and the wait is the row.
  A correct implementation moves focus on no frame at all here, and the
  incorrect one — the recipe hung off a render, or off an attempted
  navigation — would move it on the FIRST, because that is the deferral
  the effect performs. So a row asserting *focus did not move* that read
  `document.activeElement` on the next line would be green because
  nothing had happened yet, which is the failure mode this whole file is
  arranged against. Two frames is one more than the wrong answer needs."
  []
  (.then (next-frame) (fn [_] (next-frame))))

(defn- focused-heading
  "Wait until THIS APPLICATION'S OWN route heading holds focus, then answer
  it. The positive focus rows poll rather than counting frames, because a
  deadline reports WHICH heading never arrived and a frame count reports
  only that a line was false.

  The predicate is `identical?` against the node inside this mount's
  container, and deliberately not *the focused element carries the
  marker*. The marker is a vocabulary, so a shell heading or another
  root is free to carry it too — and the weaker predicate is satisfied by
  a recipe that focused somebody else's heading, which is precisely the
  document-scoped defect the decoy row exists to catch."
  [m label]
  (-> (test-support/poll-until
        #(let [h (heading m)]
           (and (some? h) (identical? h (active))))
        {:label label})
      (.then (fn [_] (heading m)))))

;; ---------------------------------------------------------------------------
;; The page's scroll, asked of the engine
;; ---------------------------------------------------------------------------

(defn- scroll-to! [y] (.scrollTo js/window 0 y))

(defn- scroll-y [] (js/Math.round (.-scrollY js/window)))

(defn- scrolled-to!
  "Scroll the page to `y` and assert the engine took it. Every scroll row
  runs through here: a document that cannot scroll answers 0 to
  `window.scrollY`, and 0 is also the right answer for a working
  scroll-to-top, so an unasserted scroll would make the rest of the row
  meaningless without making it fail."
  [y]
  (scroll-to! y)
  (is (= y (scroll-y))
      (str "precondition: the page must actually scroll to " y
           " — it reads " (scroll-y) ". Without this the rows below are"
           " green on a page that never moved"))
  y)

;; ---------------------------------------------------------------------------
;; The structural half — what the recipe is aiming at
;; ---------------------------------------------------------------------------

(deftest the-element-the-recipe-focuses-is-a-heading-with-the-panes-name
  (let [tree (ht/tree [views/feed-page {}]
                      {:subs {[::subs/feed] [{:slug "deep-space" :title "Deep space"}]}})
        marked (ht/find tree #(= "true" (:data-route-heading (ht/attrs %))))]
    (testing "the marker the focus effect selects on exists, exactly once,
              and is on a real heading"
      (is (some? marked)
          "no element carries :data-route-heading — the recipe would focus
           nothing, and every browser row below would be asserting that a
           nil selector match is a no-op")
      (is (= 1 (count (ht/find-all tree #(= "true" (:data-route-heading (ht/attrs %))))))
          "exactly one: `querySelector` takes the first match in document
           order, so a second marker is a focus target chosen by accident
           of markup order")
      (is (= :heading (ht/role marked))
          "a heading, per `ht/role` — landing focus on an unlabelled
           `<div>` announces nothing, which is the version of this recipe
           that passes a browser row and helps nobody"))

    (testing "and it carries the pane's own accessible name, so arriving
              there says WHERE the user arrived"
      (is (= "Articles" (ht/accessible-name tree marked))))

    (testing "it is programmatically focusable and NOT a new Tab stop"
      (is (= -1 (:tab-index (ht/attrs marked)))
          "-1, not 0: the heading is a focus TARGET, not a stop on the Tab
           order. Without any tabindex the engine refuses focus outright
           and `.focus()` is a silent no-op; with 0 the recipe buys itself
           an extra Tab press for every keyboard user on every page"))))

(deftest the-recipe-names-a-root-and-not-the-document
  (let [fx           (:fx (events/pane-shown {} [::events/pane-shown]))
        [fx-id args] (first fx)]
    (testing "`:on-match` asks for exactly one effect, and it is the focus one"
      (is (= 1 (count fx)))
      (is (= ::events/focus-heading fx-id)))

    (testing "the effect carries a ROOT scope alongside the selector"
      (is (= events/root-selector (:root args))
          "without `:root` the effect resolves its selector against the whole
           document and focuses the first marked heading on the page, whoever
           it belongs to — the defect
           [[a-deep-link-focuses-inside-the-applications-own-root]] mounts a
           decoy to catch")
      (is (= events/heading-selector (:selector args))))

    (testing "and the shell RENDERS that root, so the scope names an element
              rather than nothing"
      ;; The other half of the same fact, and the half a `:root` key alone
      ;; cannot state: a recipe scoped to an id no view writes resolves to
      ;; nil and focuses nothing at all — a no-op that looks exactly like a
      ;; working recipe on a page where nothing else competes for the marker.
      (let [tree (ht/tree [views/app {}] {:subs {[:rf.route/id] routes/feed}})
            root (ht/find tree #(= events/root-id (:id (ht/attrs %))))]
        (is (some? root)
            (str "no element in the shell carries the id "
                 (pr-str events/root-id) " that " (pr-str events/root-selector)
                 " names"))
        (is (= :main (:tag root))
            "and it is the application's own root element")))))

;; ---------------------------------------------------------------------------
;; Focus — the positives
;; ---------------------------------------------------------------------------

(deftest a-deep-link-lands-focus-on-the-pane-it-opened
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at! routes/article {:slug (:slug first-article)})]
        (-> (focused-heading m "the deep link's landing focus")
            (.then (fn [h]
                     (is (identical? h (active))
                         (str "focus is on " (active-label) ", not the article's
                              heading. A URL opened cold is where a keyboard or
                              screen-reader user starts, and `pushState` moves
                              focus nowhere by itself — `<body>` holds it and the
                              first Tab press starts from the top of the document"))
                     (is (= (:title first-article) (.-textContent h))
                         "and the heading names the article that was opened, so
                          the landing is not merely somewhere but somewhere
                          identifiable")))
            (finish-after m done))))))

(deftest a-deep-link-focuses-inside-the-applications-own-root
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      ;; The decoy is appended BEFORE the mount, so it precedes the
      ;; application's container in document order and a document-wide
      ;; lookup reaches it first. Binding order is the whole mechanism.
      (let [decoy (decoy!)
            m     (at! routes/article {:slug (:slug first-article)})]
        (-> (js/Promise.resolve
              (testing "the near-miss is LIVE — a document-wide lookup does NOT
                        answer this application's heading"
                (is (some? (heading m))
                    "precondition: the pane rendered a marked heading of its own")
                (is (some? (.querySelector js/document events/root-selector))
                    (str "precondition: the shell rendered "
                         (pr-str events/root-selector) ", the root the recipe
                         scopes to. A scope that resolves to nothing focuses
                         nothing, which on a page with no decoy is
                         indistinguishable from a recipe that works"))
                (is (not (identical? (heading m)
                                     (.querySelector js/document events/heading-selector)))
                    "the decoy precedes the application in document order, so
                     `document.querySelector` over the shared marker answers
                     something that is not this application's heading. The
                     recipe as it stood ran exactly that lookup — and would
                     still have left `activeElement` on a marked heading, which
                     is why every assertion below is written against the
                     application's OWN node rather than against the marker")))
            (.then (fn [_] (focused-heading m "the deep link's landing focus")))
            (.then (fn [h]
                     (is (identical? h (active))
                         (str "focus is on " (active-label) ", not the article's
                              own heading"))
                     (is (not (identical? decoy (active)))
                         "and NOT on the decoy. The `:root` scope is the only
                          thing that decides this: drop it from the recipe and
                          the decoy takes the landing focus of every navigation
                          in this application")
                     (is (= (:title first-article) (.-textContent h))
                         "and the heading names the article that was opened, so
                          the landing is not merely somewhere but somewhere
                          identifiable")))
            (finish-after m [decoy] done))))))

(deftest going-back-lands-focus-on-the-pane-it-returned-to
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at! routes/article {:slug (:slug first-article)})]
        (-> (focused-heading m "the deep link's landing focus")
            (.then (fn [_]
                     ;; The Back button, as the framework sees it: one
                     ;; `:rf.route/handle-url-change` carrying `:popstate`.
                     ;; Dispatched rather than driven through real history
                     ;; because this mount's frame is not URL-bound — what
                     ;; is under test is the conduct the door produces, not
                     ;; the listener that opens it.
                     (hm/dispatch-and-settle!
                       m [:rf.route/handle-url-change "/navigation"
                          {:rf.route/cause :popstate}])
                     (is (= routes/feed (read-sub m [:rf.route/id]))
                         "the Back really landed, so the row below is not green
                          for want of anything happening")
                     (focused-heading m "the Back navigation's landing focus")))
            (.then (fn [h]
                     (is (identical? h (active))
                         (str "focus is on " (active-label) " after Back"))
                     (is (= "Articles" (.-textContent h))
                         "and it is the LIST's heading — a recipe that focused
                          whatever it focused last would still be holding the
                          article heading, which has just been unmounted")))
            (finish-after m done))))))

;; ---------------------------------------------------------------------------
;; Focus — the too-eager directions
;; ---------------------------------------------------------------------------

(deftest a-re-render-does-not-move-focus
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at! routes/article {:slug (:slug first-article)})]
        (-> (focused-heading m "the deep link's landing focus")
            (.then (fn [_]
                     ;; Put focus where a user would have it — in the field
                     ;; they are typing in — and then re-render underneath
                     ;; them.
                     (.focus (node m "#navigation-title"))
                     (hm/dispatch-and-settle!
                       m [::events/edit (:slug first-article) "Deep space, revisited"])
                     (is (= "Deep space, revisited" (.-value (node m "#navigation-title")))
                         "the re-render really happened")
                     (two-frames)))
            (.then (fn [_]
                     (is (identical? (node m "#navigation-title") (active))
                         (str "focus moved to " (active-label) " on a keystroke.
                              The recipe is hung off `:on-match`, which a render
                              does not fire; one hung off a render instead would
                              pass every positive row in this file and take the
                              user out of the field on every character"))))
            (finish-after m done))))))

(deftest a-blocked-navigation-moves-neither-the-route-nor-the-focus
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m    (at! routes/article {:slug (:slug first-article)})
            slug (:slug first-article)]
        (-> (focused-heading m "the deep link's landing focus")
            (.then (fn [_]
                     ;; The pending mutation: a typed, unsaved title. The
                     ;; article route's `:can-leave` guard reads it.
                     (hm/dispatch-and-settle! m [::events/edit slug "Half a title"])
                     (.focus (node m "#navigation-title"))

                     (hm/dispatch-and-settle! m [:rf.route/navigate {:to routes/feed}])

                     (testing "the leave is REFUSED, and parked rather than dropped"
                       (is (= routes/article (read-sub m [:rf.route/id]))
                           "the route did not move")
                       (is (some? (read-sub m [:rf/pending-navigation]))
                           "and the refusal is resumable — routing wrote one
                            pending value, which is the wiring point this
                            application reads")
                       (is (some? (node m ".leave-prompt"))
                           "the application showed it"))

                     (two-frames)))
            (.then (fn [_]
                     (is (identical? (node m "#navigation-title") (active))
                         (str "focus moved to " (active-label) " on a navigation
                              that never happened. This is the case a focus
                              recipe is most likely to get wrong and least
                              likely to be tested for: the user is mid-sentence
                              in an unsaved field, and an eager recipe answers a
                              refused navigation by taking them out of it"))

                     ;; Resume through the application's own wiring point,
                     ;; not through a hand-written event id.
                     (.click (node m ".leave-anyway"))
                     (hm/settle! m)
                     (is (= routes/feed (read-sub m [:rf.route/id]))
                         "the parked navigation resumed on confirmation")
                     (focused-heading m "the resumed navigation's landing focus")))
            (.then (fn [h]
                     (is (= "Articles" (.-textContent h))
                         "and NOW focus moves — so the row above is about a
                          navigation that was refused, not about a recipe that
                          never fires")))
            (finish-after m done))))))

;; ---------------------------------------------------------------------------
;; Scroll restoration — the positives
;; ---------------------------------------------------------------------------

(def ^:private deep-offset
  "Far enough down the list that landing back at the top would be
  unmistakable, and well inside the shell's height so nothing is clamped."
  900)

(deftest a-forward-navigation-lands-at-the-top-and-back-restores-the-offset
  (if-not (browser?)
    (skip! ":node-test has no scroll model")
    (async done
      (scroll-to! 0)
      (let [m (at! routes/feed)]
        (-> (focused-heading m "the list's landing focus")
            (.then (fn [_]
                     (scrolled-to! deep-offset)

                     (hm/dispatch-and-settle!
                       m [:rf.route/navigate {:to routes/article
                                              :params {:slug (:slug first-article)}}])
                     (is (= routes/article (read-sub m [:rf.route/id])))
                     (is (= 0 (scroll-y))
                         (str "a forward navigation lands at the top and this one
                              is at " (scroll-y) ". Arriving at a new page
                              already scrolled down is the defect every router
                              ships a scroll strategy for"))

                     ;; Back. `:rf.route/handle-url-change` defaults to
                     ;; `:restore`, and the position it looks up is the one
                     ;; `:rf.nav/capture-scroll` saved for `/navigation`
                     ;; when the navigation above left it.
                     (hm/dispatch-and-settle!
                       m [:rf.route/handle-url-change "/navigation"
                          {:rf.route/cause :popstate}])
                     (is (= routes/feed (read-sub m [:rf.route/id])))
                     (is (= deep-offset (scroll-y))
                         (str "Back restored " (scroll-y) " rather than "
                              deep-offset ". The list is where the user was
                              reading; sending them to the top of it is the
                              whole reason the capture/restore pair exists"))

                     ;; And the two recipes COMPOSE. Focus moves to the
                     ;; list's heading one frame after the restore — with
                     ;; `:preventScroll`, which is the only reason the
                     ;; offset asserted above is still there a frame later.
                     (focused-heading m "the Back navigation's landing focus")))
            (.then (fn [h]
                     (is (identical? h (active)))
                     (is (= deep-offset (scroll-y))
                         (str "the landing focus scrolled the page back to "
                              (scroll-y) ". Focusing an element scrolls it into
                              view, and the focus recipe runs one frame AFTER
                              the restore — so a `.focus()` without
                              `:preventScroll` silently undoes scroll
                              restoration on exactly the navigation it exists
                              for, with both features looking installed"))))
            (finish-after m done))))))

;; ---------------------------------------------------------------------------
;; Scroll restoration — the too-eager directions
;; ---------------------------------------------------------------------------

(deftest a-navigation-that-suppresses-scrolling-moves-nothing
  (if-not (browser?)
    (skip! ":node-test has no scroll model")
    (async done
      (scroll-to! 0)
      (let [m (at! routes/feed)]
        (-> (focused-heading m "the list's landing focus")
            (.then (fn [_]
                     (scrolled-to! 700)

                     (hm/dispatch-and-settle!
                       m [:rf.route/navigate {:to     routes/article
                                              :params {:slug (:slug first-article)}
                                              :scroll false}])
                     (is (= routes/article (read-sub m [:rf.route/id]))
                         "the navigation happened, so the row is not green for
                          want of one")
                     (is (= 700 (scroll-y))
                         (str "`:scroll false` suppresses the effect entirely and
                              the page reads " (scroll-y) ". An implementation
                              that always scrolled to the top would pass the
                              positive row above and fail only here"))

                     ;; And the focus recipe still runs — suppressing the
                     ;; scroll is not suppressing the navigation.
                     (focused-heading m "the suppressed-scroll navigation's landing focus")))
            (.then (fn [_]
                     (is (= 700 (scroll-y))
                         "and the landing focus did not scroll either")))
            (finish-after m done))))))

(deftest a-back-to-a-url-never-visited-inherits-no-other-urls-position
  (if-not (browser?)
    (skip! ":node-test has no scroll model")
    (async done
      (scroll-to! 0)
      (let [m (at! routes/feed)]
        (-> (focused-heading m "the list's landing focus")
            (.then (fn [_]
                     ;; Leave `/navigation` at a distinctive offset, so the
                     ;; cache holds exactly one position and it is one no
                     ;; other URL is entitled to.
                     (scrolled-to! deep-offset)
                     (hm/dispatch-and-settle!
                       m [:rf.route/navigate {:to routes/article
                                              :params {:slug (:slug first-article)}}])
                     (is (= 0 (scroll-y)))
                     (scrolled-to! 300)

                     ;; A Back to a URL this session has never left — so the
                     ;; cache has nothing keyed under it.
                     (hm/dispatch-and-settle!
                       m [:rf.route/handle-url-change "/navigation/article/shallow-water"
                          {:rf.route/cause :popstate}])
                     (is (= "shallow-water" (:slug (read-sub m [:rf.route/params])))
                         "the navigation happened")
                     (is (= 300 (scroll-y))
                         (str "a `:restore` with nothing saved for this URL must
                              leave the page where it is; it reads " (scroll-y)
                              ". The LIST's offset (" deep-offset ") appearing
                              here would mean the cache had handed back somebody
                              else's position — a restore keyed by \"the last
                              thing saved\" rather than by URL, which passes the
                              positive row and puts every user at an offset they
                              never chose"))
                     (two-frames)))
            (.then (fn [_]
                     (is (= 300 (scroll-y))
                         "and still, a frame later, after the landing focus")))
            (finish-after m done))))))
