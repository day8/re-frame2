(ns re-frame.bench.hicasso.shapes.feed
  "**TIER-1 SHAPES 3 AND 4** — the same page, and the two writes that
  separate them (charter §Use cases A3/A4; rf2-2rtt6.51).

  > 3. Bulk re-render — ~300 boundaries on one commit; **the make-or-break
  >    row**.
  > 4. Narrow update — one cell moves in a large mounted page.

  One page carries both, because both are statements about **the same
  mounted page** and only the commit differs. Splitting them across two
  pages would make every comparison between them a comparison of two
  applications.

      [:conduit/refresh-feed]     every article moves  ->  300 bodies run   (shape 3)
      [:conduit/favorite slug]    one article moves    ->    1 body runs    (shape 4)

  Neither write is synthetic: a feed refresh is what a poll, a reconnect
  or a return-to-tab does to a cached list, and favouriting is Conduit's
  most-clicked control.

  ## The page is [[re-frame.bench.hicasso.shapes.large-template]], re-cut

  Same chrome, same card, same model, same intents. The **only** edit is
  the one the shape is about:

      ;; large-template, one boundary:
      (for [slug (sub [:conduit/slugs])]
        (card/card slug))

      ;; feed, one boundary per card:
      (for [slug (sub [:conduit/slugs])]
        [article-card {:key slug :slug slug}])

  plus the four lines of [[article-card]] below, whose entire body is the
  same `(card/card slug)`. `bulk_dom_cljs_test` asserts the two pages
  build byte-identical card DOM, so \"the only edit\" is a comparison and
  not a claim.

  ## What the page boundary reads, and why that is the whole design

  The page reads `[:conduit/slugs]`, `[:conduit/tags]` and
  `[:conduit/your-feed?]`. It does **not** read any article. So neither
  write above reaches it: the 300 re-renders on a refresh are the index
  answering for 300 cards, not a list rebuilding its children, and the one
  re-render on a favourite is one card. Both witnesses assert the page
  body's run count did not move, because that is the difference between a
  narrow update and a page that merely looks narrow.

  ## The cascade this page also makes visible

  A write the **page** reads — the feed toggle — re-renders the page, and
  React then re-renders all 300 card boundaries beneath it, because a
  Hicasso boundary is a plain function component with no props-equality
  bail-out. Reagent's default `shouldComponentUpdate` compares argv and
  stops exactly this cascade. `narrow_dom_cljs_test` measures it rather
  than leaving it to be discovered on a clock: see that file's
  `a-page-chrome-write-re-renders-every-row`. It is reported as a
  **finding**, not repaired here — repairing it is a runtime change, and
  the roster's job is to find out what the shapes cost.

  `.cljc`-compatible by construction (HD-020(d))."
  (:require [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.shapes.card :as card]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.bench.hicasso.shapes.model :as m])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def article-count
  "The charter's rung: ~300 boundaries on one commit."
  300)

(def tag-count 10)

(def seed {:articles article-count :tags tag-count})

(defn element-arithmetic
  "Predicted element count — the same chrome as the large template, the
  same card, a different number of them."
  []
  (+ lt/chrome-elements tag-count (* card/elements-per-card article-count)))

(def !card-runs
  "How many card bodies have run. Shape 3's claim is that one commit makes
  this move by exactly 300; shape 4's is that another makes it move by
  exactly one. Both are counted, not asserted about."
  (atom 0))

(def !page-runs
  "How many times the page body has run. Both claims need this NOT to
  move."
  (atom 0))

(defn reset-runs! [] (reset! !card-runs 0) (reset! !page-runs 0) nil)

(defn runs [] {:cards @!card-runs :page @!page-runs})

(defview article-card
  "One card, as a boundary. The whole body is the shared markup — see
  [[re-frame.bench.hicasso.shapes.card]] for why that is the point."
  [{:keys [slug]}]
  (swap! !card-runs inc)
  (card/card slug))

(defview page
  "The feed, one boundary per card."
  [_]
  (swap! !page-runs inc)
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
                         :on-click    [:re-frame.hicasso/prevent [:conduit/show-your-feed]]}
            "Your Feed"]]
          [:li.nav-item
           [:a.nav-link {:href        "#"
                         :data-testid "global-feed-tab"
                         :class       (when-not your-feed? "active")
                         :on-click    [:re-frame.hicasso/prevent [:conduit/show-global-feed]]}
            "Global Feed"]]]]
        [:div.article-list {:data-testid "article-list"}
         (for [slug (sub [:conduit/slugs])]
           [article-card {:key slug :slug slug}])]]
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

(defn slug-at
  "The slug of the `i`th article, for a witness that wants to name one row
  out of three hundred."
  [i]
  (:slug (m/article i)))
