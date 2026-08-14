(ns re-frame.bench.hicasso.shapes.card
  "THE CENSUS'S ARTICLE CARD — written **once**, so shapes 2 and 3 differ
  in one thing (rf2-2rtt6.51).

  Ported from RealWorld's `article-preview`
  (`examples/real-apps/realworld_resources/ui_views.cljs:111-143`), the
  card that carries Conduit's home feed and every profile list.

  ## Why it is a plain function

  Shape 2 (large templates) and shape 3 (bulk re-render) are **the same
  screen at two boundary decompositions** — one boundary interpreting
  ~1,200 elements, or ~300 boundaries interpreting ~17 each. The roster
  is only evidence about boundaries if nothing else differs between the
  two pages, so the markup is written here, once, and:

  - [[re-frame.bench.hicasso.shapes.large-template]] **calls** it —
    `(card slug)` — and it inlines into the page's single boundary
    (HD-016: a plain function call mints no boundary of its own, and its
    `sub` reads are donated to the enclosing one);
  - [[re-frame.bench.hicasso.shapes.feed]] **wraps** it — one `defview`
    whose whole body is `(card slug)` — and it becomes a boundary.

  **That is the entire diff between the two shapes**, and it is the
  headline authoring result of the roster: the decision \"is this row a
  component?\" costs one `defview` and one pair of brackets at the call
  site, and changes nothing about the markup, the reads, the intents or
  the DOM. `bulk_dom_cljs_test` asserts the two pages build byte-identical
  cards, so the claim is a comparison rather than a reading of this
  docstring.

  ## The two reads, and why both stay

  A card reads its article and its favourite-in-flight status — the
  census's own pair (`[:rf/mutation {:instance [:favorite slug]}]` there,
  `[:conduit/favorite-pending? slug]` here). Both are kept because the
  read *count* per row is what the per-read budget is priced against, and
  a port that dropped the status read to make a page cheaper would be
  quoting a row the census does not have.

  ## The three links are route-links (rf2-2rtt6.54)

  The census card carries three `ui/route-link`s — both author bylines
  and the preview link — and until rf2-2rtt6.54 this port spelled them
  `[:a {:href (str \"#/profile/\" …)}]`: a faithful port of the MARKUP
  and an unfaithful one of the AUTHORING, hand-building the URL the
  router owns. They are now
  [[re-frame.bench.hicasso.front.route-link/route-link]] calls — the
  author names a route and params and never sees a URL. A route-link is a
  plain function producing ONE `<a>`, reading NO subscription, so
  [[elements-per-card]] and the card's two-read count are exactly what
  they were; what changed per card is three `route-url` synthesis calls
  per render, priced on the routing artefact's own render path.
  (`shapes/route_link_dom_cljs_test` owns the click witnesses.)

  `.cljc`-compatible by construction: no interop, no JS literal."
  (:require [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.front.route-link :refer [route-link]]
            [re-frame.bench.hicasso.shapes.model :as m]))

(def elements-per-card
  "The element count of one rendered card, with the two tags
  [[re-frame.bench.hicasso.shapes.model/tags-for]] gives every article.

  Written down because a page's total is then arithmetic a witness can
  **predict** — `chrome + n * elements-per-card` — rather than a number it
  has to accept from the DOM it is supposed to be checking. A markup edit
  that forgets to update it fails
  `large_template_dom_cljs_test/the-page-is-the-size-the-arithmetic-predicts`
  rather than silently re-baselining the shape."
  17)

(defn card
  "One article card. A plain function: called from a body it inlines, and
  the two `sub` reads below are donated to whichever boundary is running."
  [slug]
  (let [{:keys [title description createdAt favoritesCount favorited author tagList]}
        (sub [:conduit/article slug])
        pending? (sub [:conduit/favorite-pending? slug])]
    ;; `:key` on the root, in the attribute map, because the card is a
    ;; **keyed list child in both shapes**: a seq element in the large
    ;; template, and a boundary's root under a keyed `[article-card {:key
    ;; …}]` in the feed. Native-element keys live in the props position
    ;; (HD-016), and a key React does not need is a key React ignores — so
    ;; one spelling serves both decompositions and neither call site has to
    ;; know which one it is in.
    [:div.article-preview {:key         slug
                           :data-testid (str "article-preview-" slug)}
     [:div.article-meta
      (route-link {:to :conduit.profile/show :params {:username (:username author)}
                   :class "author-link"}
        [:img.user-pic {:src (m/avatar-src (:image author)) :alt ""}])
      [:div.info
       (route-link {:to :conduit.profile/show :params {:username (:username author)}
                    :class "author"}
         (:username author))
       [:span.date createdAt]]
      [:button.btn.btn-outline-primary.btn-sm.pull-xs-right
       {:type        "button"
        :data-testid (str "favorite-" slug)
        :class       (cond-> ""
                       favorited (str " active")
                       pending?  (str " optimistic"))
        :on-click    [:conduit/favorite slug]}
       [:i.ion-heart] " "
       [:span {:data-testid (str "favorites-count-" slug)} favoritesCount]]]
     (route-link {:to :conduit.article/show :params {:slug slug}
                  :class "preview-link" :data-testid (str "article-link-" slug)}
       [:h1 title]
       [:p description]
       [:span "Read more..."]
       [:ul.tag-list
        (for [tag tagList]
          [:li.tag-default.tag-pill.tag-outline {:key tag} tag])])]))
