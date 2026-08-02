(ns re-frame.bench.hicasso.shapes.census-clock-arms
  "THE CENSUS-REAL PAGES' CLOCK ARMS — the tier-1 shape roster's three
  mountable screens, each written out for every substrate the clock
  compares (rf2-2rtt6.56).

  The roster (rf2-2rtt6.51) authored the pages ONCE, in the candidate's
  own language — [[re-frame.bench.hicasso.shapes.ordinary]],
  [[re-frame.bench.hicasso.shapes.large-template]] and
  [[re-frame.bench.hicasso.shapes.feed]] — and published no timing row.
  This file is what a clock row needs and the roster deliberately does
  not carry: the SAME three pages in direct UIx, in stock Reagent, and as
  a plain-React floor, so a mount ratio can be taken same-run against the
  amendment's anchor (mount <= 1.10x direct UIx-on-subs, rf2-2rtt6.1,
  2026-08-02).

  ## Every arm is written out longhand

  The comparison is only worth taking if each arm is what a competent
  author of THAT substrate would write; a shared generator producing
  three dialects would measure the generator (hd8-witnesses' argument,
  kept). The canonical-DOM parity gate in the clock app is what proves
  the longhand copies still build one page — attribute names sorted,
  compared at stress size AND small size, before any clock is read, and
  proven able to answer false.

  ## What each substrate can and cannot spell, stated up front

  The three pages differ in exactly one thing — how many boundaries the
  same markup is cut into — and the substrates do not meet that variable
  equally. These are findings the rows are FOR, not defects in the arms:

  - **large-template (1,202 elements, ONE boundary, 141 per-instance
    reads).** The candidate and Reagent read per-instance subscriptions
    inside a `for` inside a plain helper; a hook may not run in either
    position, so NO one-boundary UIx page can carry the roster's 141
    per-instance reads. The UIx twin reads five coarse subscriptions
    (order, articles, tags, feed flag, pending set) at its fixed hook
    sites and renders the cards from data. Same page, same one boundary,
    a read shape the census screens do not have — stated on the row's
    stamp every time it prints.
  - **feed (5,129 elements, 301 boundaries).** All three substrates spell
    this decomposition natively — one component per card, two reads per
    card, three on the page — so this row is the census-real counterpart
    of the canonical M1 witness and the cleanest gated pair in the file.
  - **ordinary (51 elements, 7 boundaries).** The candidate and Reagent
    read the delete-status inside `(when mine? …)`; a hook cannot sit in
    a branch, so the UIx card reads it unconditionally (the dogfood UIx
    rendering records the same limit). Three extra reads on this page.

  ## The class strings are the codec's, byte for byte

  The Hicasso codec merges a tag's `.class` shorthand with a declared
  `:class` by joining with ONE space and by dropping an empty declared
  string entirely (`front/codec.cljs` `class-names`) — and the census
  card's declared class itself begins with a space, so a favorited
  button's merged value carries two. The twins and the floor spell the
  MERGED strings explicitly rather than each substrate's own shorthand
  merge, because the row is a comparison of pages, not of class-merge
  implementations — and because the explicit string is the cheaper form,
  any bias runs AGAINST the candidate, never for it.

  Normative owner: rf2-2rtt6.1 (standard, incl. the mount-gate
  amendment); this entry rf2-2rtt6.56."
  (:require ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.arm1.mount :as arm1-mount]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.feed :as feed]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.bench.hicasso.shapes.ordinary :as ordinary]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc]
            [uix.core :refer [$ defui]])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ===========================================================================
;; The three rows, their seeds and their arithmetic
;; ===========================================================================
;;
;; Stress seeds are the roster's own (`lt/seed`, `feed/seed`, and the
;; ordinary witness's `{:articles 2 :comments 5 :tags 2}`), so a clock row
;; is taken on exactly the page the roster's witnesses assert. Small seeds
;; exist for the parity gate — agreement at one size is not evidence until
;; the same comparison has been watched disagreeing at another.

(def rows
  {:large-template
   {:seed     lt/seed
    :small-a  {:articles 6 :tags 10}
    :small-b  {:articles 7 :tags 10}
    :elements (fn [{:keys [articles]}]
                (+ lt/chrome-elements lt/tag-count (* 17 articles)))}
   :feed
   {:seed     feed/seed
    :small-a  {:articles 6 :tags 10}
    :small-b  {:articles 7 :tags 10}
    :elements (fn [{:keys [articles]}]
                (+ lt/chrome-elements lt/tag-count (* 17 articles)))}
   :ordinary
   {:seed     {:articles 2 :comments 5 :tags 2}
    :small-a  {:articles 2 :comments 3 :tags 2}
    :small-b  {:articles 2 :comments 4 :tags 2}
    :elements (fn [{:keys [comments]}]
                (let [mine (count (filter #(zero? (mod % 4)) (range comments)))]
                  (ordinary/element-arithmetic comments mine)))}})

(defn- doubled
  "The seed at TWICE the row's card count — what the positive control
  builds. The chrome does not double, so the control's predicted ratio is
  the ARITHMETIC one, not 2.00: 1.9759 (large-template), 1.9944 (feed),
  1.7255 (ordinary). The driver adjudicates against these."
  [row-key]
  (let [{:keys [seed]} (get rows row-key)]
    (if (= row-key :ordinary)
      (update seed :comments * 2)
      (update seed :articles * 2))))

(defn elements-at [row-key seed]
  ((:elements (get rows row-key)) seed))

(defn ctl-predicted
  "predicted ctl-2x / floor, from the element arithmetic."
  [row-key]
  (/ (elements-at row-key (doubled row-key))
     (elements-at row-key (:seed (get rows row-key)))))

;; ===========================================================================
;; The frame roster — one frame per reactive arm per row
;; ===========================================================================

(defn frame-of [row-key arm-id]
  (keyword (str "c56." (name row-key)) (name arm-id)))

(defonce ^:private frame-dispatch
  ;; frame keyword -> the frame-locked dispatch fn, primed at boot outside
  ;; every measured window (hd8-witnesses' pattern) so no React-spine arm
  ;; pays to CONSTRUCT a dispatch fn during a render.
  (atom {}))

(defn prime-frame! [frame-kw]
  (swap! frame-dispatch assoc frame-kw (:dispatch (rf/capture-frame frame-kw)))
  nil)

(defn- dispatch-for [frame-kw] (get @frame-dispatch frame-kw))

(defn ensure-frames!
  "Create and seed one frame per (row, reactive arm), and prime each
  frame's dispatch. Boot-time, outside every window."
  [arm-ids]
  (doseq [row-key (keys rows)
          arm-id  arm-ids
          :when   (not= arm-id :floor)]
    (let [fid (frame-of row-key arm-id)]
      (m/make-frame! fid (:seed (get rows row-key)))
      (m/reseed! fid (:seed (get rows row-key)))
      (prime-frame! fid)))
  nil)

(defn reseed-row!
  "Reseed every reactive arm's frame for `row-key` at `seed` — the parity
  gate's door for mounting the same arms at another size."
  [row-key arm-ids seed]
  (doseq [arm-id arm-ids
          :when  (not= arm-id :floor)]
    (m/reseed! (frame-of row-key arm-id) seed))
  nil)

;; ===========================================================================
;; The class strings the census pages put in the DOM
;; ===========================================================================

(def ^:private favorite-base "btn btn-outline-primary btn-sm pull-xs-right")

(defn- favorite-class
  "The favourite button's merged class, byte-identical to the Hicasso
  codec's shorthand merge over the census card's own declared value. The
  declared value begins with a space, so the merged string carries TWO
  before `active`; an empty declared value merges to the shorthand alone."
  [favorited pending?]
  (let [declared (cond-> "" favorited (str " active") pending? (str " optimistic"))]
    (if (seq declared) (str favorite-base " " declared) favorite-base)))

(defn- nav-class [active?] (if active? "nav-link active" "nav-link"))

;; ===========================================================================
;; ARM: the React floor — the calibrator, no substrate at all
;; ===========================================================================
;;
;; Hand-written `createElement` over a plain seeded value (`m/seed-db`
;; called directly — no frame, no subscription, no handler indirection).
;; One hoisted inert handler, for the reason hd8's floor hoists its own:
;; the calibrator must not be billed for a closure per element that no
;; rival mints per element either.

(defn- fel
  ([tag props] (react/createElement tag props))
  ([tag props & children] (apply react/createElement tag props children)))

(def ^:private inert (fn [_] nil))

(defn- floor-card [slug article]
  (let [{:keys [title description createdAt favoritesCount favorited author tagList]} article]
    (fel "div" #js {:key slug :className "article-preview"
                    :data-testid (str "article-preview-" slug)}
         (fel "div" #js {:className "article-meta"}
              (fel "a" #js {:className "author-link"
                            :href (str "#/profile/" (:username author))}
                   (fel "img" #js {:className "user-pic"
                                   :src (m/avatar-src (:image author)) :alt ""}))
              (fel "div" #js {:className "info"}
                   (fel "a" #js {:className "author"
                                 :href (str "#/profile/" (:username author))}
                        (:username author))
                   (fel "span" #js {:className "date"} createdAt))
              (fel "button" #js {:type "button"
                                 :data-testid (str "favorite-" slug)
                                 :className (favorite-class favorited false)
                                 :onClick inert}
                   (fel "i" #js {:className "ion-heart"})
                   " "
                   (fel "span" #js {:data-testid (str "favorites-count-" slug)}
                        favoritesCount)))
         (fel "a" #js {:className "preview-link"
                       :href (str "#/article/" slug)
                       :data-testid (str "article-link-" slug)}
              (fel "h1" nil title)
              (fel "p" nil description)
              (fel "span" nil "Read more...")
              (fel "ul" #js {:className "tag-list"}
                   (into-array
                     (map (fn [tag]
                            (fel "li" #js {:key tag
                                           :className "tag-default tag-pill tag-outline"}
                                 tag))
                          tagList)))))))

(defn- floor-feed-page
  "The home feed — both the large-template row's page and the feed row's
  page, because the two rows are the SAME markup at two boundary
  decompositions and a floor has no boundaries to decompose."
  [{:keys [order articles tags your-feed?]}]
  (fel "div" #js {:className "home-page"}
       (fel "div" #js {:className "banner"}
            (fel "div" #js {:className "container"}
                 (fel "h1" #js {:className "logo-font"} "conduit")
                 (fel "p" nil "A place to share your knowledge.")))
       (fel "div" #js {:className "container page"}
            (fel "div" #js {:className "row"}
                 (fel "div" #js {:className "col-md-9"}
                      (fel "div" #js {:className "feed-toggle"}
                           (fel "ul" #js {:className "nav nav-pills outline-active"}
                                (fel "li" #js {:className "nav-item"}
                                     (fel "a" #js {:href "#"
                                                   :data-testid "your-feed-tab"
                                                   :className (nav-class your-feed?)
                                                   :onClick inert}
                                          "Your Feed"))
                                (fel "li" #js {:className "nav-item"}
                                     (fel "a" #js {:href "#"
                                                   :data-testid "global-feed-tab"
                                                   :className (nav-class (not your-feed?))
                                                   :onClick inert}
                                          "Global Feed"))))
                      (fel "div" #js {:className "article-list"
                                      :data-testid "article-list"}
                           (into-array
                             (map (fn [slug] (floor-card slug (get articles slug)))
                                  order))))
                 (fel "div" #js {:className "col-md-3"}
                      (fel "div" #js {:className "sidebar"}
                           (fel "p" nil "Popular Tags")
                           (fel "div" #js {:className "tag-list"
                                           :data-testid "tag-list"}
                                (into-array
                                  (map (fn [tag]
                                         (fel "a" #js {:key tag
                                                       :className "tag-pill tag-default"
                                                       :href "#"
                                                       :data-testid (str "tag-" tag)}
                                              tag))
                                  tags)))))))))

(defn- floor-comment-card [id c user]
  (let [{:keys [body createdAt author]} c
        mine? (= (:username user) (:username author))]
    (fel "div" #js {:key id :className "card"
                    :data-testid (str "comment-card-" id)}
         (fel "div" #js {:className "card-block"}
              (fel "p" #js {:className "card-text" :data-testid "comment-body"} body))
         (apply fel "div" #js {:className "card-footer"}
                (fel "a" #js {:className "comment-author"
                              :href (str "#/profile/" (:username author))}
                     (fel "img" #js {:className "comment-author-img"
                                     :src (m/avatar-src (:image author)) :alt ""})
                     " "
                     (:username author))
                (fel "span" #js {:className "date-posted"} createdAt)
                (when mine?
                  [(fel "button" #js {:type "button"
                                      :className "mod-options"
                                      :data-testid (str "delete-comment-" id)
                                      :aria-label "Delete comment"
                                      :onClick inert}
                        (fel "i" #js {:className "ion-trash-a"}))])))))

(defn- floor-ordinary-screen [{:keys [comment-order comments user]}]
  (fel "div" #js {:className "article-page"}
       (fel "div" #js {:className "container page"}
            (fel "div" #js {:className "row"}
                 (fel "div" #js {:className "col-xs-12 col-md-8 offset-md-2"}
                      (fel "h2" #js {:className "comments-heading"} "Comments")
                      (fel "form" #js {:className "card comment-form"
                                       :data-testid "comment-form"
                                       :onSubmit inert}
                           (fel "div" #js {:className "card-block"}
                                (fel "textarea" #js {:className "form-control"
                                                     :data-testid "comment-body-input"
                                                     :rows 3
                                                     :placeholder "Write a comment..."
                                                     :value ""
                                                     :onChange inert}))
                           (fel "div" #js {:className "card-footer"}
                                (fel "button" #js {:className "btn btn-sm btn-primary"
                                                   :type "submit"
                                                   :data-testid "comment-submit"}
                                     "Post Comment")))
                      (fel "hr" nil)
                      (fel "div" #js {:className "comments-list"
                                      :data-testid "comments-list"}
                           (into-array
                             (map (fn [id] (floor-comment-card id (get comments id) user))
                                  comment-order))))))))

(defn- floor-element [row-key db]
  (if (= row-key :ordinary)
    (floor-ordinary-screen db)
    (floor-feed-page {:order      (:order db)
                      :articles   (:articles db)
                      :tags       (:tags db)
                      :your-feed? (boolean (:your-feed? db))})))

;; ===========================================================================
;; ARM: direct UIx — the amendment's anchor
;; ===========================================================================
;;
;; The hd8 frontier arm's shape, on the census pages: `use-current-frame`
;; (the narrow context read) feeding the two-arity `use-subscribe`, and
;; the primed [[dispatch-for]] lookup for handlers — adapter-independent,
;; which is what lets this arm ride the Reagent run unchanged for a
;; mount-only row.

;; The coarse subscriptions ONLY the one-boundary UIx page reads. A hook
;; cannot sit inside the `for` where the census page reads per-instance,
;; so the UIx large-template twin reads whole collections at fixed sites.
;; Registered here, not in the roster's model: the roster's read shapes
;; are the census's, and this one is not.
(rf/reg-sub :census56/articles (fn [db _] (:articles db)))
(rf/reg-sub :census56/pending (fn [db _] (:pending db)))

(defn- ux-card-body
  "One census card from DATA — the only card the one-boundary UIx page
  can render. A plain function (no hooks), so it inlines into the page's
  single component."
  [slug article pending? d]
  (let [{:keys [title description createdAt favoritesCount favorited author tagList]} article]
    ($ :div.article-preview {:key slug :data-testid (str "article-preview-" slug)}
       ($ :div.article-meta
          ($ :a.author-link {:href (str "#/profile/" (:username author))}
             ($ :img.user-pic {:src (m/avatar-src (:image author)) :alt ""}))
          ($ :div.info
             ($ :a.author {:href (str "#/profile/" (:username author))} (:username author))
             ($ :span.date createdAt))
          ($ :button {:type        "button"
                      :data-testid (str "favorite-" slug)
                      :class       (favorite-class favorited pending?)
                      :on-click    (fn [_] (d [:conduit/favorite slug]))}
             ($ :i.ion-heart) " "
             ($ :span {:data-testid (str "favorites-count-" slug)} favoritesCount)))
       ($ :a.preview-link {:href        (str "#/article/" slug)
                           :data-testid (str "article-link-" slug)}
          ($ :h1 title)
          ($ :p description)
          ($ :span "Read more...")
          ($ :ul.tag-list
             (for [tag tagList]
               ($ :li.tag-default.tag-pill.tag-outline {:key tag} tag)))))))

(defn- ux-chrome
  "The feed page's chrome around `card-elements` — shared by the two UIx
  feed twins the way the roster shares it between its own two pages: the
  chrome is not what the rows vary."
  [your-feed? tags d card-elements]
  ($ :div.home-page
     ($ :div.banner
        ($ :div.container
           ($ :h1.logo-font "conduit")
           ($ :p "A place to share your knowledge.")))
     ($ :div.container.page
        ($ :div.row
           ($ :div.col-md-9
              ($ :div.feed-toggle
                 ($ :ul.nav.nav-pills.outline-active
                    ($ :li.nav-item
                       ($ :a {:href        "#"
                              :data-testid "your-feed-tab"
                              :class       (nav-class your-feed?)
                              :on-click    (fn [e]
                                             (.preventDefault e)
                                             (d [:conduit/show-your-feed]))}
                          "Your Feed"))
                    ($ :li.nav-item
                       ($ :a {:href        "#"
                              :data-testid "global-feed-tab"
                              :class       (nav-class (not your-feed?))
                              :on-click    (fn [e]
                                             (.preventDefault e)
                                             (d [:conduit/show-global-feed]))}
                          "Global Feed"))))
              ($ :div.article-list {:data-testid "article-list"} card-elements))
           ($ :div.col-md-3
              ($ :div.sidebar
                 ($ :p "Popular Tags")
                 ($ :div.tag-list {:data-testid "tag-list"}
                    (for [tag tags]
                      ($ :a.tag-pill.tag-default {:key         tag
                                                  :href        "#"
                                                  :data-testid (str "tag-" tag)}
                         tag)))))))))

(defui ux-lt-page
  "SHAPE 2 in UIx — the whole feed as ONE component. Six hooks at fixed
  sites; the cards are data renders. This is the page a competent UIx
  author writes when told the screen must be one boundary, and its read
  shape (five coarse reads) is NOT the census's (141 per-instance) —
  the stamp says so on every row."
  [_props]
  (let [frame      (uixa/use-current-frame)
        order      (uixa/use-subscribe frame [:conduit/slugs])
        articles   (uixa/use-subscribe frame [:census56/articles])
        tags       (uixa/use-subscribe frame [:conduit/tags])
        your-feed? (uixa/use-subscribe frame [:conduit/your-feed?])
        pending    (uixa/use-subscribe frame [:census56/pending])
        d          (dispatch-for frame)]
    (ux-chrome your-feed? tags d
               (for [slug order]
                 (ux-card-body slug (get articles slug)
                               (contains? pending [:favorite slug]) d)))))

(defui ux-article-card
  "SHAPE 3's card in UIx — one component per card, the census's own
  per-instance read pair, three hooks."
  [{:keys [slug]}]
  (let [frame    (uixa/use-current-frame)
        article  (uixa/use-subscribe frame [:conduit/article slug])
        pending? (uixa/use-subscribe frame [:conduit/favorite-pending? slug])
        d        (dispatch-for frame)]
    (ux-card-body slug article pending? d)))

(defui ux-feed-page
  "SHAPE 3 in UIx — the same feed, one boundary per card."
  [_props]
  (let [frame      (uixa/use-current-frame)
        order      (uixa/use-subscribe frame [:conduit/slugs])
        tags       (uixa/use-subscribe frame [:conduit/tags])
        your-feed? (uixa/use-subscribe frame [:conduit/your-feed?])
        d          (dispatch-for frame)]
    (ux-chrome your-feed? tags d
               (for [slug order]
                 ($ ux-article-card {:key slug :slug slug})))))

(defui ux-comment-card
  "SHAPE 1's comment row in UIx. The delete-status read is unconditional
  — a hook cannot sit inside `(when mine? …)` — so every card pays the
  edge the census page charges only to the reader's own (the dogfood UIx
  rendering records the same limit)."
  [{:keys [id]}]
  (let [frame    (uixa/use-current-frame)
        c        (uixa/use-subscribe frame [:conduit/comment id])
        user     (uixa/use-subscribe frame [:conduit/user])
        deleting? (uixa/use-subscribe frame [:conduit/delete-pending? id])
        d        (dispatch-for frame)
        {:keys [body createdAt author]} c
        mine?    (= (:username user) (:username author))]
    ($ :div.card {:data-testid (str "comment-card-" id)}
       ($ :div.card-block
          ($ :p.card-text {:data-testid "comment-body"} body))
       ($ :div.card-footer
          ($ :a.comment-author {:href (str "#/profile/" (:username author))}
             ($ :img.comment-author-img {:src (m/avatar-src (:image author)) :alt ""})
             " "
             (:username author))
          ($ :span.date-posted createdAt)
          (when mine?
            ($ :button.mod-options {:type        "button"
                                    :data-testid (str "delete-comment-" id)
                                    :aria-label  "Delete comment"
                                    :disabled    deleting?
                                    :on-click    (fn [_] (d [:conduit/delete-comment id]))}
               ($ :i.ion-trash-a)))))))

(defui ux-comment-form [_props]
  (let [frame    (uixa/use-current-frame)
        pending? (uixa/use-subscribe frame [:conduit/comment-pending?])
        draft    (uixa/use-subscribe frame [:conduit/draft m/comment-draft-key])
        d        (dispatch-for frame)]
    ($ :form.card.comment-form {:data-testid "comment-form"
                                :on-submit   (fn [e]
                                               (.preventDefault e)
                                               (d [:conduit/post-comment]))}
       ($ :div.card-block
          ($ :textarea.form-control
             {:data-testid "comment-body-input"
              :rows        3
              :placeholder "Write a comment..."
              :value       draft
              :disabled    pending?
              :on-input    (fn [e] (d [:conduit/edit-draft m/comment-draft-key
                                       (.. e -target -value)]))}))
       ($ :div.card-footer
          ($ :button.btn.btn-sm.btn-primary {:type        "submit"
                                             :data-testid "comment-submit"
                                             :disabled    pending?}
             (if pending? "Posting…" "Post Comment"))))))

(defui ux-ordinary-screen
  "SHAPE 1 in UIx — the article page's comment column, the roster's own
  seven-boundary decomposition."
  [_props]
  (let [frame (uixa/use-current-frame)
        ids   (uixa/use-subscribe frame [:conduit/comment-ids])]
    ($ :div.article-page
       ($ :div.container.page
          ($ :div.row
             ($ :div.col-xs-12.col-md-8.offset-md-2
                ($ :h2.comments-heading "Comments")
                ($ ux-comment-form {})
                ($ :hr)
                ($ :div.comments-list {:data-testid "comments-list"}
                   (for [id ids]
                     ($ ux-comment-card {:key id :id id})))))))))

;; ===========================================================================
;; ARM: stock Reagent — co-instrumented, never a second gate
;; ===========================================================================
;;
;; `reg-view`, because only a reg-view-registered component carries the
;; `:contextType` wiring that resolves the frame from context and injects
;; the lexical `subscribe`/`dispatch` a Reagent application contains.
;; Reactions are not hooks, so Reagent CAN spell everything the census
;; pages spell: per-instance derefs inside a `for` inside a helper (the
;; one-boundary page), and a conditional deref inside `(when mine? …)`.
;; The Reagent twins are therefore read-shape-identical to the candidate
;; on all three rows — the UIx twin is not, on two — and that asymmetry
;; is part of what these rows report.

(defn- rg-card-markup
  "The census card in Reagent hiccup, from one article's subscriptions.
  A plain function: the one-boundary page calls it and its derefs land on
  the caller; the feed's card boundary wraps it."
  [slug article pending? dispatch]
  (let [{:keys [title description createdAt favoritesCount favorited author tagList]} article]
    [:div.article-preview {:key slug :data-testid (str "article-preview-" slug)}
     [:div.article-meta
      [:a.author-link {:href (str "#/profile/" (:username author))}
       [:img.user-pic {:src (m/avatar-src (:image author)) :alt ""}]]
      [:div.info
       [:a.author {:href (str "#/profile/" (:username author))} (:username author)]
       [:span.date createdAt]]
      [:button {:type        "button"
                :data-testid (str "favorite-" slug)
                :class       (favorite-class favorited pending?)
                :on-click    (fn [_] (dispatch [:conduit/favorite slug]))}
       [:i.ion-heart] " "
       [:span {:data-testid (str "favorites-count-" slug)} favoritesCount]]]
     [:a.preview-link {:href        (str "#/article/" slug)
                       :data-testid (str "article-link-" slug)}
      [:h1 title]
      [:p description]
      [:span "Read more..."]
      [:ul.tag-list
       (for [tag tagList]
         [:li.tag-default.tag-pill.tag-outline {:key tag} tag])]]]))

(defn- rg-chrome [your-feed? tags dispatch card-forms]
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
         [:a {:href        "#"
              :data-testid "your-feed-tab"
              :class       (nav-class your-feed?)
              :on-click    (fn [e]
                             (.preventDefault e)
                             (dispatch [:conduit/show-your-feed]))}
          "Your Feed"]]
        [:li.nav-item
         [:a {:href        "#"
              :data-testid "global-feed-tab"
              :class       (nav-class (not your-feed?))
              :on-click    (fn [e]
                             (.preventDefault e)
                             (dispatch [:conduit/show-global-feed]))}
          "Global Feed"]]]]
      [:div.article-list {:data-testid "article-list"} card-forms]]
     [:div.col-md-3
      [:div.sidebar
       [:p "Popular Tags"]
       [:div.tag-list {:data-testid "tag-list"}
        (for [tag tags]
          [:a.tag-pill.tag-default {:key         tag
                                    :href        "#"
                                    :data-testid (str "tag-" tag)}
           tag])]]]]]])

(reg-view rg-lt-page []
  ;; ONE boundary, 141 per-instance derefs — the census page's own read
  ;; shape, which Reagent alone among the twins can spell.
  (rg-chrome @(subscribe [:conduit/your-feed?])
             @(subscribe [:conduit/tags])
             dispatch
             (doall
               (for [slug @(subscribe [:conduit/slugs])]
                 (rg-card-markup slug
                                 @(subscribe [:conduit/article slug])
                                 @(subscribe [:conduit/favorite-pending? slug])
                                 dispatch)))))

(reg-view rg-article-card [slug]
  (rg-card-markup slug
                  @(subscribe [:conduit/article slug])
                  @(subscribe [:conduit/favorite-pending? slug])
                  dispatch))

(reg-view rg-feed-page []
  (rg-chrome @(subscribe [:conduit/your-feed?])
             @(subscribe [:conduit/tags])
             dispatch
             (doall
               (for [slug @(subscribe [:conduit/slugs])]
                 ^{:key slug} [rg-article-card slug]))))

(reg-view rg-comment-card [id]
  (let [{:keys [body createdAt author]} @(subscribe [:conduit/comment id])
        mine? (= (:username @(subscribe [:conduit/user])) (:username author))]
    [:div.card {:data-testid (str "comment-card-" id)}
     [:div.card-block
      [:p.card-text {:data-testid "comment-body"} body]]
     [:div.card-footer
      [:a.comment-author {:href (str "#/profile/" (:username author))}
       [:img.comment-author-img {:src (m/avatar-src (:image author)) :alt ""}]
       " "
       (:username author)]
      [:span.date-posted createdAt]
      (when mine?
        ;; The conditional deref, exactly where the census page reads it.
        [:button.mod-options {:type        "button"
                              :data-testid (str "delete-comment-" id)
                              :aria-label  "Delete comment"
                              :disabled    @(subscribe [:conduit/delete-pending? id])
                              :on-click    (fn [_] (dispatch [:conduit/delete-comment id]))}
         [:i.ion-trash-a]])]]))

(reg-view rg-comment-form []
  (let [pending? @(subscribe [:conduit/comment-pending?])]
    [:form.card.comment-form {:data-testid "comment-form"
                              :on-submit   (fn [e]
                                             (.preventDefault e)
                                             (dispatch [:conduit/post-comment]))}
     [:div.card-block
      [:textarea.form-control {:data-testid "comment-body-input"
                               :rows        3
                               :placeholder "Write a comment..."
                               :value       @(subscribe [:conduit/draft m/comment-draft-key])
                               :disabled    pending?
                               :on-input    (fn [e] (dispatch [:conduit/edit-draft
                                                               m/comment-draft-key
                                                               (.. e -target -value)]))}]]
     [:div.card-footer
      [:button.btn.btn-sm.btn-primary {:type        "submit"
                                       :data-testid "comment-submit"
                                       :disabled    pending?}
       (if pending? "Posting…" "Post Comment")]]]))

(reg-view rg-ordinary-screen []
  [:div.article-page
   [:div.container.page
    [:div.row
     [:div.col-xs-12.col-md-8.offset-md-2
      [:h2.comments-heading "Comments"]
      [rg-comment-form]
      [:hr]
      [:div.comments-list {:data-testid "comments-list"}
       (doall
         (for [id @(subscribe [:conduit/comment-ids])]
           ^{:key id} [rg-comment-card id]))]]]]])

;; ===========================================================================
;; The arms, wired to their mount doors
;; ===========================================================================

(def ^:private hicasso-page
  {:large-template lt/page
   :feed           feed/page
   :ordinary       ordinary/screen})

(def ^:private ux-page
  {:large-template ux-lt-page
   :feed           ux-feed-page
   :ordinary       ux-ordinary-screen})

(def ^:private rg-page
  {:large-template rg-lt-page
   :feed           rg-feed-page
   :ordinary       rg-ordinary-screen})

(defn- react-root-arm [id element-of]
  {:id      id
   :mount   (fn [container _props _n]
              (let [r (react-dom-client/createRoot container)]
                (.render r (element-of))
                r))
   :unmount (fn [r] (.unmount r))})

(defn arm
  "The lane arm for `arm-id` on `row-key` — `{:id :mount :unmount}`, the
  shape `lane/mount-arm!` times. `:floor` and `:ctl-2x` close over plain
  seeded values built at load; the reactive arms close over their frames."
  [row-key arm-id]
  (let [fid (frame-of row-key arm-id)]
    (case arm-id
      :floor   (let [db (m/seed-db (:seed (get rows row-key)))]
                 (react-root-arm :floor (fn [] (floor-element row-key db))))
      :ctl-2x  (let [db (m/seed-db (doubled row-key))]
                 (assoc (react-root-arm :ctl-2x (fn [] (floor-element row-key db)))
                        :control? true
                        :parity-exempt? true))
      :hicasso (react-root-arm :hicasso
                 (fn [] (arm1-mount/provider fid
                          (codec/as-element [(get hicasso-page row-key) {}]))))
      :uix     (react-root-arm :uix
                 (fn [] ($ uixa/frame-provider {:frame fid}
                           ($ (get ux-page row-key) {}))))
      :reagent {:id      :reagent
                :mount   (fn [container _props _n]
                           (let [r (rdc/create-root container)]
                             (rdc/render r [rf/frame-provider {:frame fid}
                                            [(get rg-page row-key)]])
                             r))
                :unmount (fn [r] (rdc/unmount r))})))

(def plumb-arm
  "THE TARE — one sample's plumbing (the protocol round trip, the rAF and
  timeout callbacks, the frame the browser produces), identical for every
  arm; every published figure subtracts it."
  {:id       :plumb
   :control? true
   :plumb?   true
   :mount    (fn [_container _props _n] nil)
   :unmount  (fn [_] nil)})

(defn expected-elements [row-key arm-id]
  (case arm-id
    :plumb  0
    :ctl-2x (elements-at row-key (doubled row-key))
    (elements-at row-key (:seed (get rows row-key)))))

;; ===========================================================================
;; The parity gate's raw material
;; ===========================================================================

(defn parity-at
  "Mount every reactive+floor arm of `row-key` with the reactive frames
  reseeded to `seed`, compare canonical DOM and element counts, release,
  and answer `{:agree? :counts :reference :disagree}`. The floor and the
  small sizes get their own plain seeded value, so the comparison never
  reuses the stress arm's data."
  [row-key arm-ids seed]
  (reseed-row! row-key arm-ids seed)
  (let [expected (elements-at row-key seed)
        db       (m/seed-db seed)
        arms     (mapv (fn [id]
                         (if (= id :floor)
                           (react-root-arm :floor (fn [] (floor-element row-key db)))
                           (arm row-key id)))
                       arm-ids)
        p        (lane/parity arms {})]
    (try
      {:agree?    (:agree? p)
       :counts    (:counts p)
       :expected  expected
       :reference (:reference p)
       :disagree  (:disagree p)}
      (finally
        (doseq [mnt (:mounts p)] (lane/release! mnt))))))

(defn parity-problems
  "Every row, at stress size and at a small size: every arm builds ONE
  page with the element count the arithmetic predicts. Answer a vector of
  problems; empty means the gate held. Ends with every frame back at its
  stress seed."
  [arm-ids]
  (reduce
    (fn [problems row-key]
      (let [{:keys [seed small-a]} (get rows row-key)
            judge (fn [acc s what]
                    (let [{:keys [agree? counts expected disagree]} (parity-at row-key arm-ids s)]
                      (cond-> acc
                        (not agree?)
                        (conj {:row row-key :size what
                               :problem :canonical-dom-disagreement :arms disagree})
                        (not (every? #(= expected %) (vals counts)))
                        (conj {:row row-key :size what :problem :element-count
                               :predicted expected :measured counts}))))]
        (let [acc (-> problems (judge small-a :small) (judge seed :stress))]
          (reseed-row! row-key arm-ids seed)
          acc)))
    []
    (keys rows)))

(defn parity-can-fail?
  "The same comparison, watched answering FALSE: the large-template row at
  six articles and at seven must disagree with each other while each size
  agrees within itself. Ends with the row back at its stress seed."
  [arm-ids]
  (let [{:keys [seed small-a small-b]} (get rows :large-template)
        a (parity-at :large-template arm-ids small-a)
        b (parity-at :large-template arm-ids small-b)]
    (reseed-row! :large-template arm-ids seed)
    (and (:agree? a) (:agree? b) (not= (:reference a) (:reference b)))))
