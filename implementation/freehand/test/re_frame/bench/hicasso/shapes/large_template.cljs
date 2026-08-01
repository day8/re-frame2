(ns re-frame.bench.hicasso.shapes.large-template
  "**TIER-1 SHAPE 2 — LARGE TEMPLATES**, the ~1,200-element shape
  (charter §Use cases A2; rf2-2rtt6.51).

  RealWorld's home feed — `ui_views.cljs:223-274`, banner, feed toggle,
  article list and popular-tags sidebar — **as one boundary**. Every card,
  every tag pill and every element of the chrome is interpreted inside a
  single `defview` body. That is the shape's definition: not \"a big
  page\", but *one template that is big*, which is the rung that prices
  **the hiccup interpreter** with the boundary shell held at one.

  ## The page

      chrome 19 + tags 10 + 69 cards x 17 = 1,202 elements, ONE boundary

  [[element-arithmetic]] is that sum, and
  `large_template_dom_cljs_test` asserts the DOM matches it. A page whose
  size is arithmetic rather than a measurement is a page a witness can
  check; a page whose size is whatever the DOM says is a page that
  re-baselines itself on every markup edit.

  ## Two deviations from the census screen, both named

  1. **Unpaginated.** Conduit paginates at ten articles a page, so
     Conduit's own home page is a ~200-element screen. The shape the
     charter names is ~1,200, and the honest way to get there from this
     screen is the variant every real feed grew into — one long page — so
     the port drops the `pagination` subtree along with the page division
     that is its only reason to exist. The census's own `pagination` view
     renders nothing when there is one page, so this is the branch it
     already has.
  2. **No auth-conditional chrome.** The census hides the *Your Feed* tab
     from a signed-out reader. The roster's reader is always signed in
     (`model/signed-in-user`), so the branch would have one answer for the
     life of every witness, and a constant conditional in a page whose
     size is asserted is a trap rather than a fidelity.

  Everything else is Conduit's: class names, nesting, `data-testid`
  retail, the `favoritesCount` field names, the tag pills.

  ## What this shape is really asking

  **~141 subscription reads in one body, and the shell still costs two
  hooks.** Sixty-nine cards read two subs each, the chrome reads three
  more, and every one of those reads sits inside a `for`, inside a plain
  helper called from inside that `for`
  ([[re-frame.bench.hicasso.shapes.card/card]]) — which is the collector's
  authoring claim taken to a rung no hook-shaped read surface can reach at
  all. `hook_budget_dom_cljs_test` counts it at React's own dispatcher:
  two, the same two the one-read boundary pays.

  It is also the shape most exposed to the runtime's own read-set
  machinery. One boundary with ~141 reads means a 141-entry scratch, a
  141-key read-set entry, and a 141-term `getSnapshot` sum. Nothing here
  changed to accommodate that, and nothing needed to.

  ## Reading the body

  The whole page is one `let` and one nested hiccup literal. There is no
  \"large template\" idiom — no `into`, no `apply`, no fragment ceremony,
  no keys on anything that is not in a `for`. The card is a function call
  because a card here is not a component; making it one is
  [[re-frame.bench.hicasso.shapes.feed]], and the diff between the two
  files is the deliberate finding.

  `.cljc`-compatible by construction (HD-020(d))."
  (:require [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.shapes.card :as card]
            [re-frame.bench.hicasso.shapes.model :as m])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def article-count
  "Sixty-nine cards. Chosen so the page lands on the charter's
  ~1,200-element shape — and, not by coincidence, within one element of
  the predecessor's own 1,203-element `W1` witness, so a reader comparing
  the two is comparing pages of the same size."
  69)

(def tag-count 10)

(def chrome-elements
  "Every element of the page that is not a card and not a tag pill:

      home-page, banner, banner container, h1, p,
      page container, row, col-md-9, feed-toggle, ul.nav,
      li, a, li, a, article-list,
      col-md-3, sidebar, p, tag-list

  Nineteen. Counted by hand and then held there by
  `the-page-is-the-size-the-arithmetic-predicts`, which fails on a markup
  edit rather than absorbing it."
  19)

(def seed
  "The seed the shape is mounted on."
  {:articles article-count :tags tag-count})

(defn element-arithmetic
  "The page's element count, predicted rather than measured."
  []
  (+ chrome-elements tag-count (* card/elements-per-card article-count)))

(def !body-runs
  "How many times the page's one body has run."
  (atom 0))

(defn reset-runs! [] (reset! !body-runs 0) nil)

(defview page
  "The whole feed, in one boundary."
  [_]
  (swap! !body-runs inc)
  (let [your-feed? (sub [:conduit/your-feed?])]
    [:div.home-page
     [:div.banner
      [:div.container
       [:h1.logo-font "conduit"]
       [:p "A place to share your knowledge."]]]
     [:div.container.page
      [:div.row
       [:div.col-md-9
        [:div.feed-toggle
         [:ul.nav.nav-pills.outline-active
          [:li.nav-item
           [:a.nav-link {:href        "#"
                         :data-testid "your-feed-tab"
                         :class       (when your-feed? "active")
                         :on-click    ^{:re-frame.hicasso/prevent? true}
                                      [:conduit/show-your-feed]}
            "Your Feed"]]
          [:li.nav-item
           [:a.nav-link {:href        "#"
                         :data-testid "global-feed-tab"
                         :class       (when-not your-feed? "active")
                         :on-click    ^{:re-frame.hicasso/prevent? true}
                                      [:conduit/show-global-feed]}
            "Global Feed"]]]]
        [:div.article-list {:data-testid "article-list"}
         (for [slug (sub [:conduit/slugs])]
           ;; A plain call. It inlines into THIS boundary and donates its
           ;; two reads to it — the whole difference between this shape and
           ;; the next one.
           (card/card slug))]]
       [:div.col-md-3
        [:div.sidebar
         [:p "Popular Tags"]
         [:div.tag-list {:data-testid "tag-list"}
          (for [tag (sub [:conduit/tags])]
            [:a.tag-pill.tag-default {:key         tag
                                      :href        "#"
                                      :data-testid (str "tag-" tag)}
             tag])]]]]]]))

(defn make-frame! [frame-id] (m/make-frame! frame-id seed))
(defn reseed! [frame-id] (m/reseed! frame-id seed))
