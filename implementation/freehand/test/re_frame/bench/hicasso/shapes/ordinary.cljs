(ns re-frame.bench.hicasso.shapes.ordinary
  "**TIER-1 SHAPE 1 — ORDINARY VIEWS**, the ~50-element form/list/layout
  screen (charter §Use cases A1; rf2-2rtt6.51).

  Ported, not invented: this is the comment column of RealWorld's article
  page —
  `examples/real-apps/realworld_resources/ui_views.cljs:325-411`, the
  `comment-form` + `comment-card` pair and the
  `div.row > div.col-xs-12.col-md-8.offset-md-2` that holds them. It is
  the shape's three nouns in one screen and in one place: **layout** (the
  page/container/row/column chrome), **form** (a controlled textarea with
  a submit and an in-flight rule), **list** (keyed cards with a
  per-row author-only control).

  ## What the port changed, and what it did not

  Three lines differ from the census original, and each is a claim rather
  than a convenience:

  1. **The row reads its own comment.** The census threads the comment
     down as a prop — `[comment-card {:key (:id c) :comment c …}]` — which
     is right for a substrate whose list boundary re-renders wholesale and
     wrong for re-frame2, where the point of a row boundary is that it
     reads its OWN row and moves when that row moves. So the row takes
     `id` and reads `[:conduit/comment id]`. Same argument
     `jsfb-model` makes about an index-keyed vector.
  2. **`current-user` is no longer threaded.** The census passes it into
     every card because a hook-shaped read has to sit at a fixed site and
     the fixed site is the page. `sub` is an ordinary call, so the card
     reads it where it uses it, and one prop disappears from the call site
     and from the row's argument vector. That deletion is the collector's
     whole ergonomic claim, stated on a real screen.
  3. **The delete-status read moved inside the branch that uses it.** The
     census reads `[:rf/mutation {:instance [:delete-comment slug id]}]`
     unconditionally, at the top of the body, because it has to. Here it
     sits inside `(when mine? …)`, so a card showing somebody else's
     comment holds **two edges** and a card showing your own holds
     **three**. A branch not taken contributes no edge — that is the
     property `arm1_dogfood_dom_cljs_test` states as a number, and
     `ordinary_dom_cljs_test` restates it on this screen.

  Everything else is the census's: Conduit's class names, its
  `data-testid` retail, its element nesting, its `mine?` rule, its
  disabled-while-pending discipline, and its `favoritesCount`-era
  camelCase field names.

  ## What the port could not keep

  `ui/route-link` — the census's author byline is a route-link, and the
  census counts **106 of them** and calls the form tier-1. This arm has
  no `route-link` (`front.intent`'s docstring says so in as many words),
  so the byline is spelled `[:a {:href …}]`. That is a v0 gap, filed, not
  a design position.

  ## Reading the body

  Nothing here is a Hicasso idiom an author has to learn: a `let`, a
  `when`, a `for`, ordinary destructuring, hiccup vectors, and event
  intents as data. The two things that are not plain Clojure —
  `defview` minting a boundary, and `:re-frame.hicasso/value` as the
  placeholder for the typed value — are one concept each.

  Body forms are `.cljc`-compatible by construction (HD-020(d)): no
  interop, no JS literal, no React import. SSR is out of v0; the
  constraint that keeps it reachable is not."
  (:require [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.shapes.model :as m])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; ---------------------------------------------------------------------------
;; The screen's size, as arithmetic
;; ---------------------------------------------------------------------------
;;
;; Written down for the same reason the large template's is: a page whose
;; size is arithmetic is a page a witness can CHECK, and a page whose size
;; is whatever the DOM says is a page that re-baselines itself on every
;; markup edit. "~50 elements" is the charter's phrase for this shape, and
;; a band would have let a card silently gain or lose an element inside it.

(def chrome-elements
  "article-page, container.page, row, column, h2, hr, comments-list."
  7)

(def form-elements
  "form.card, card-block, textarea, card-footer, button."
  5)

(def elements-per-card
  "card, card-block, card-text, card-footer, author link, avatar,
  date-posted."
  7)

(def elements-per-own-card
  "The same, plus the author-only delete control and its icon — the two
  elements the `(when mine? …)` branch contributes."
  9)

(defn element-arithmetic
  "The screen's element count, predicted rather than measured."
  [comment-count mine-count]
  (+ chrome-elements
     form-elements
     (* elements-per-card (- comment-count mine-count))
     (* elements-per-own-card mine-count)))

(def !body-runs
  "How many bodies have run, by view name. The narrow-write claims on this
  screen are counted here rather than asserted about."
  (atom {}))

(defn- ran! [view-name]
  (swap! !body-runs update view-name (fnil inc 0))
  nil)

(defn reset-runs! [] (reset! !body-runs {}) nil)

(defn runs-of [view-name] (get @!body-runs view-name 0))

;; ---------------------------------------------------------------------------
;; The list — one keyed card per comment
;; ---------------------------------------------------------------------------

(defn- profile-href
  "A plain function called from a body: it inlines, mints no boundary, and
  is where a `route-link` would go if this arm had one."
  [username]
  (str "#/profile/" username))

(defview comment-card
  "One comment — a keyed row. The delete control shows only for the
  reader's own comments, and the in-flight read that greys it out is read
  **inside that branch**, so it is an edge only the rows that can use it
  pay for."
  [{:keys [id]}]
  (ran! :comment-card)
  (let [{:keys [body createdAt author]} (sub [:conduit/comment id])
        mine? (= (:username (sub [:conduit/user])) (:username author))]
    [:div.card {:data-testid (str "comment-card-" id)}
     [:div.card-block
      [:p.card-text {:data-testid "comment-body"} body]]
     [:div.card-footer
      [:a.comment-author {:href (profile-href (:username author))}
       [:img.comment-author-img {:src (m/avatar-src (:image author)) :alt ""}]
       " "
       (:username author)]
      [:span.date-posted createdAt]
      (when mine?
        [:button.mod-options {:type        "button"
                              :data-testid (str "delete-comment-" id)
                              :aria-label  "Delete comment"
                              :disabled    (sub [:conduit/delete-pending? id])
                              :on-click    [:conduit/delete-comment id]}
         [:i.ion-trash-a]])]]))

;; ---------------------------------------------------------------------------
;; The form — the controlled textarea, the submit, the in-flight rule
;; ---------------------------------------------------------------------------

(defview comment-form
  "The composer. `:on-submit` carries an intent and takes the
  census-weighted default — it auto-prevents, so there is no
  `preventDefault` to remember and no options map to spell. The textarea
  is controlled through the synchronous door: `:value` is a `sub`,
  `:on-input` is an intent carrying the value placeholder."
  [_]
  (ran! :comment-form)
  (let [pending? (sub [:conduit/comment-pending?])]
    [:form.card.comment-form {:data-testid "comment-form"
                              :on-submit   [:conduit/post-comment]}
     [:div.card-block
      [:textarea.form-control {:data-testid "comment-body-input"
                               :rows        3
                               :placeholder "Write a comment..."
                               :value       (sub [:conduit/draft m/comment-draft-key])
                               :disabled    pending?
                               :on-input    [:conduit/edit-draft m/comment-draft-key
                                             :re-frame.hicasso/value]}]]
     [:div.card-footer
      [:button.btn.btn-sm.btn-primary {:type        "submit"
                                       :data-testid "comment-submit"
                                       :disabled    pending?}
       (if pending? "Posting…" "Post Comment")]]]))

;; ---------------------------------------------------------------------------
;; The layout — the census's own container/row/column chrome
;; ---------------------------------------------------------------------------

(defview screen
  "The comment column of the article page. The list boundary reads the
  order and nothing else, so a comment's own edit does not reach it."
  [_]
  (ran! :screen)
  [:div.article-page
   [:div.container.page
    [:div.row
     [:div.col-xs-12.col-md-8.offset-md-2
      [:h2.comments-heading "Comments"]
      [comment-form {}]
      [:hr]
      [:div.comments-list {:data-testid "comments-list"}
       (for [id (sub [:conduit/comment-ids])]
         [comment-card {:key id :id id}])]]]]])
