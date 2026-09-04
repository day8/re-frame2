(ns re-frame.bench.hicasso.shapes.large-template-dom-cljs-test
  "**SHAPE 2'S WITNESS** — the ~1,200-element template is one boundary,
  and it renders the census's page (rf2-2rtt6.51).

  Four claims:

  1. **The page is the size the arithmetic predicts.** `chrome + tags +
     69 x 17` is computed from constants in the source and compared
     against the DOM. A markup edit that changes a card's element count
     fails here instead of silently re-baselining what \"the ~1,200-element
     shape\" means.
  2. **It is ONE boundary** — the defining property of the shape, and the
     reason it prices the hiccup interpreter rather than the shell.
  3. **141 subscription reads land on that one boundary**, in one
     read-set entry, over 141 cells. The reads sit inside a `for`, inside
     a plain helper called from inside it — the collector's authoring
     claim at a rung no per-read hook surface can reach.
  4. **The template is live.** A write moves the DOM, and the page's one
     body re-runs exactly once for it — which is also the honest cost of
     this decomposition and the reason shape 3 exists.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.shapes.card :as rf.bench.hicasso.shapes.card]
            [re-frame.bench.hicasso.shapes.large-template :as rf.bench.hicasso.shapes.large-template]
            [re-frame.bench.hicasso.shapes.model :as rf.bench.hicasso.shapes.model]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.bench.hicasso.arm1.runtime/reset-runtime!))}))

(def ^:private frame-id ::shape-large-template)

(def ^:private predicted-reads
  "What the page's single boundary reads:

      page chrome  [:conduit/your-feed?] [:conduit/slugs] [:conduit/tags]   3
      per card     [:conduit/article slug] [:conduit/favorite-pending? slug] 2

  so `3 + 2 x 69`."
  (+ 3 (* 2 rf.bench.hicasso.shapes.large-template/article-count)))

(defn- skip! [why]
  (is true (str "shape 2's witness needs a real React DOM — " why)))

(defn- fresh! []
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.shapes.large-template/make-frame! frame-id)
  (rf.bench.hicasso.shapes.large-template/reseed! frame-id)
  (rf.bench.hicasso.shapes.large-template/reset-runs!)
  frame-id)

(defn- mount! []
  (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) frame-id [rf.bench.hicasso.shapes.large-template/page {}]))

(defn- q [handle sel] (.querySelector (:container handle) sel))
(defn- q* [handle sel] (array-seq (.querySelectorAll (:container handle) sel)))

;; ---------------------------------------------------------------------------
;; 1 — the size is arithmetic, not a measurement
;; ---------------------------------------------------------------------------

(deftest the-page-is-the-size-the-arithmetic-predicts
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [predicted (rf.bench.hicasso.shapes.large-template/element-arithmetic)
                measured  (rf.bench.hicasso.lane/element-count (:container handle))]
            (is (= predicted measured)
                (str "chrome " rf.bench.hicasso.shapes.large-template/chrome-elements " + tags " rf.bench.hicasso.shapes.large-template/tag-count
                     " + " rf.bench.hicasso.shapes.large-template/article-count " cards x " rf.bench.hicasso.shapes.card/elements-per-card
                     " = " predicted "; the DOM holds " measured))
            (is (<= 1150 measured 1250)
                (str "and that is the charter's ~1,200-element shape (" measured ")")))
          (testing "the census's own chrome is all there"
            (is (some? (q handle ".home-page > .banner .logo-font")))
            (is (= 2 (count (q* handle ".feed-toggle .nav-item"))))
            (is (some? (q handle "[data-testid=\"global-feed-tab\"].active"))
                "the default tab is the global feed")
            ;; `[data-testid="tag-list"]`, not `.tag-list`: the census gives
            ;; the sidebar's tag cloud and every card's own tag row the SAME
            ;; class, so the bare selector answers 10 + 69x2 = 148. Its own
            ;; collision, faithfully ported — and the reason the sidebar is
            ;; addressed by the test id the census also wrote.
            (is (= rf.bench.hicasso.shapes.large-template/tag-count
                   (count (q* handle "[data-testid=\"tag-list\"] > .tag-pill")))))
          (testing "and the cards are the model's"
            (is (= rf.bench.hicasso.shapes.large-template/article-count (count (q* handle ".article-list > .article-preview"))))
            (doseq [i [0 1 34 68]]
              (let [a (rf.bench.hicasso.shapes.model/article i)]
                (is (= (:title a)
                       (.-textContent (q handle (str "[data-testid=\"article-preview-"
                                                     (:slug a) "\"] h1"))))
                    (str "card " i " carries the model's title"))
                (is (= (str (:favoritesCount a))
                       (.-textContent (q handle (str "[data-testid=\"favorites-count-"
                                                     (:slug a) "\"]"))))
                    (str "card " i " carries the model's favourites count")))))
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 + 3 — one boundary, 141 reads, one read-set entry
;; ---------------------------------------------------------------------------

(deftest the-whole-page-is-one-boundary
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [{:keys [boundaries edges entries cells]} (rf.bench.hicasso.arm1.runtime/stats)]
            (is (= 1 boundaries)
                (str "a large TEMPLATE is one boundary interpreting many "
                     "elements; this page reports " boundaries))
            (is (= predicted-reads edges)
                (str "and it holds all " predicted-reads " edges itself"))
            (is (= predicted-reads cells)
                "one cell per unique (frame, query), and every query here is distinct")
            (is (= 1 entries)
                "one read SEQUENCE, so one cached subscribe/getSnapshot pair"))
          (is (= 1 @rf.bench.hicasso.shapes.large-template/!body-runs) "and its body ran once to build the page")
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — the template is live, and re-running it is what it costs
;; ---------------------------------------------------------------------------

(deftest a-write-moves-the-dom-and-re-runs-the-one-body-once
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [slug   (:slug (rf.bench.hicasso.shapes.model/article 7))
                before (:favoritesCount (rf.bench.hicasso.shapes.model/article 7))
                count- (fn [] (.-textContent
                                (q handle (str "[data-testid=\"favorites-count-" slug "\"]"))))]
            (is (= (str before) (count-)) "the first paint is right — which proves nothing on its own")
            (reset! rf.bench.hicasso.shapes.large-template/!body-runs 0)
            (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/favorite slug])
            (rf.bench.hicasso.arm1.mount/settle!)
            (is (= (str (inc before)) (count-))
                "a later write reaches the DOM — the half a first render cannot tell you")
            (is (some? (q handle (str "[data-testid=\"favorite-" slug "\"].active")))
                "and the favourited class flipped with it")
            (is (= 1 @rf.bench.hicasso.shapes.large-template/!body-runs)
                "the page's one body re-ran exactly once — which is also the honest
                 cost of this decomposition: a one-card write re-interprets all
                 1,202 elements, and that is what shape 3 exists to change")
            (testing "every other card is untouched"
              (let [other (:slug (rf.bench.hicasso.shapes.model/article 8))]
                (is (= (str (:favoritesCount (rf.bench.hicasso.shapes.model/article 8)))
                       (.-textContent (q handle (str "[data-testid=\"favorites-count-"
                                                     other "\"]"))))))))
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

(deftest the-feed-toggle-flips-the-chrome
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (is (some? (q handle "[data-testid=\"global-feed-tab\"].active")))
          (is (nil? (q handle "[data-testid=\"your-feed-tab\"].active")))
          (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/show-your-feed])
          (rf.bench.hicasso.arm1.mount/settle!)
          (is (some? (q handle "[data-testid=\"your-feed-tab\"].active")))
          (is (nil? (q handle "[data-testid=\"global-feed-tab\"].active")))
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; The one authoring spelling this roster uses that nothing else witnesses
;; ---------------------------------------------------------------------------
;;
;; The census's feed tabs are anchors, so a port that keeps them has to opt
;; `preventDefault` IN — `[::h/prevent [:conduit/show-your-feed]]` at the
;; event position — where the comment form's `:on-submit` gets it from the
;; position's own default. Every other assertion in the roster reaches its
;; intents through `rf.bench.hicasso.arm1.runtime/dispatch!`, which would never have exercised the
;; spelling at all. Fired here as a real cancelable click, with the
;; un-decorated intent on the same page as the control: if the pair both
;; prevented, or neither did, the decorator would not be the thing doing it.
;;
;; The head replaced `^{::h/prevent? true}` metadata (HD-026) because
;; metadata does not participate in `=`. That is a claim about data, and
;; `front/intent_cljs_test/a-prevented-intent-is-assertable-by-equality`
;; is where it is asserted; this file answers the other half — that the
;; browser's `canceled` flag actually moves, which no equality can show.

(defn- click!
  "A real, cancelable click, dispatched at the node. Answers the event, so
  the caller can read `defaultPrevented` off it."
  [node]
  (let [ev (js/MouseEvent. "click" #js {:bubbles true :cancelable true})]
    (.dispatchEvent node ev)
    (rf.bench.hicasso.arm1.mount/settle!)
    ev))

(deftest the-prevent-head-is-what-prevents
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (testing "an anchor carrying [::h/prevent [:conduit/show-your-feed]]
                   dispatches AND prevents, so the href=\"#\" does not navigate"
            (let [ev (click! (q handle "[data-testid=\"your-feed-tab\"]"))]
              (is (true? (.-defaultPrevented ev)))
              (is (some? (q handle "[data-testid=\"your-feed-tab\"].active"))
                  "and the intent reached the model")))
          (testing "the control: an ordinary intent on the same page dispatches
                   and does NOT prevent"
            (let [slug (:slug (rf.bench.hicasso.shapes.model/article 4))
                  ev   (click! (q handle (str "[data-testid=\"favorite-" slug "\"]")))]
              (is (false? (.-defaultPrevented ev)))
              (is (= (str (inc (:favoritesCount (rf.bench.hicasso.shapes.model/article 4))))
                     (.-textContent (q handle (str "[data-testid=\"favorites-count-"
                                                   slug "\"]"))))
                  "and it reached the model too — so the difference between the
                   two is the decorator and nothing else")))
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; Teardown
;; ---------------------------------------------------------------------------

(deftest the-page-leaves-no-residue
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/favorite (:slug (rf.bench.hicasso.shapes.model/article 2))])
        (rf.bench.hicasso.arm1.mount/settle!)
        (is (pos? (:cell-refs (rf.bench.hicasso.arm1.runtime/stats))))
        (rf.bench.hicasso.arm1.mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rf.bench.hicasso.arm1.runtime/residue))
                             "141 edges and 141 cells, all released by React's own cleanup")
                         (rf.bench.hicasso.arm1.runtime/reset-runtime!)
                         (done))
                       8)))))
