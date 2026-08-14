(ns re-frame.bench.hicasso.inpage-ladder-app
  "THE IN-PAGE MOUNT TERM, DECOMPOSED (rf2-409ab).

  `rf2-cno31` published the acceptance arm's mount as
  `hicasso 16.015 = 8.254 taskNet + 6.100 in-page` against
  `uix 13.742 = 8.246 taskNet + 3.900 in-page`, floor 12.010 — so the
  arms' FRAME halves are indistinguishable (8.254 vs 8.246) and the whole
  +2.2 ms deficit lives in the IN-PAGE half. This entry decomposes that
  6.100 ms.

  ## The door and the window, stated first

  Every arm here mounts through `lane/mount-arm!` — `createRoot` +
  `.render` inside ONE `react-dom/flushSync`, `performance.now()` on
  either side — which is **the same window, on the same page, at the same
  door** the published in-page term is read through
  (`shapes/census_clock_app/sample!` calls exactly that function and
  publishes `(:ms mnt)`). The `ship` arm below therefore reproduces the
  published 6.100 ms quantity, and every other arm is that arm with one
  term removed. Nothing here goes through `page.click`; the protocol door
  question `rf2-emvod` raised does not arise, because no figure on this
  page is a `TaskDuration` at all.

  **DIAGNOSTIC, not published.** The clock is the in-page window. It
  attributes cost BETWEEN terms of one mount; it is not the clock of
  record and no figure here is a gate row. `rf2-8nqsl` is why that
  sentence has to be here: an in-page window mis-reads a substrate arm's
  RATIO to the floor by hundreds of points, because it sees only the
  script half. That is precisely why it is the right instrument for THIS
  question — the quantity under decomposition IS the script half.

  ## The ladder, and what each subtraction names

  All fifteen arms build the acceptance page (`large-template`, 1,202
  elements, ONE boundary, 141 per-instance reads, 207 route-links) and
  every non-control arm's canonical DOM is proven byte-identical to the
  candidate's before a clock is read.

  | arm | what it is | subtraction |
  |---|---|---|
  | `ship` | the real [[re-frame.bench.hicasso.shapes.large-template/page]] through the real shell | the published 6.100 |
  | `local` | the same body, re-spelled in THIS namespace | fidelity gate: `local / ship` |
  | `nolink` | `local` with the card's three `route-link`s spelled as literal-href anchors | `local - nolink` = **the routing term** |
  | `nohiccup` | the 141 reads, then a hiccup tree built ONCE at boot | `nolink - nohiccup` = **hiccup materialisation** |
  | `nowalk` | the 141 reads, then a React element tree built ONCE at boot | `nohiccup - nowalk` = **the codec walk** |
  | `noreads` | the frozen element tree, no reads | `nowalk - noreads` = **the 141 reads AND their commit** |
  | `nomemo` | `noreads` minted WITHOUT `codec/memoize-boundary!` | `noreads - nomemo` = **the HD-028 memo wrapper's fiber** |
  | `bare` | a plain React function component, no shell, no hooks | `nomemo - bare` = **the shell: 2 hooks, the fence, the entry** |
  | `coarse` | `local` at the TWIN's read shape — five coarse reads | `local - coarse` = **the read-shape asymmetry, on our arm** |
  | `floor` | the census floor arm - hand-written `createElement` | the calibrator |
  | `uix` | the real UIx twin | the published 3.900 |
  | `uixlocal` | the twin, re-spelled here | fidelity gate: `uixlocal / uix` |
  | `uixnolink` | the twin with literal-href anchors | `uixlocal - uixnolink` = **the twin's routing term** |
  | `uixbare` | the twin's 5 reads, then the frozen element tree | `uixnolink - uixbare` = **the twin's `$` markup** |
  | `ctl-2x` | the floor at twice the cards | the positive control |

  `bare` is the shared base: React mounting 1,202 elements it was handed.
  Everything above it on either arm is what that arm ADDS, and the two
  arms' additions are the answer to \"what does UIx not do that we do?\".

  ## The ablation arms are written HERE (rf2-2rtt6.32)

  A local arm timed against a foreign one compares call conventions as
  much as terms, so `local` is a re-spelling of the real page in this
  namespace and the whole ladder descends from it. Its fidelity is
  checked twice: canonical DOM byte-identity with `ship` at boot (fatal),
  and the `local / ship` timing ratio published on every run.

  ## The frozen trees, and what freezing does NOT change

  `nohiccup`'s hiccup and `nowalk`'s element tree are built once at boot,
  inside a real body door on a capture frame, from `nolink`'s body. React
  elements are immutable and every sample mounts a FRESH root, so React
  performs a full mount either way; what the freeze removes is the
  building, which is the term being priced. The frozen tree's intent
  closures are bound to the capture frame — never clicked, and invisible
  to the DOM.

  Owner bead: rf2-409ab. Driver: `run.cjs` with
  HICASSO_INIT_FN=re-frame.bench.hicasso.inpage-ladder-app/-main."
  (:require ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.arm1.mount :as arm1-mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt :refer [sub]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.route-link :refer [route-link]]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.census-clock-arms :as arms]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [uix.core :refer [$ defui]])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def row-key :large-template)

;; ---------------------------------------------------------------------------
;; Frames — one per arm, so no arm's commit warms another arm's reads
;; ---------------------------------------------------------------------------

(def local-frames
  "The frames this namespace owns. `ship`, `uix`, `floor` and `ctl-2x`
  ride `census_clock_arms`' own frames, so their arms are the published
  ones and not a re-spelling of them."
  {:local     ::local
   :nolink    ::nolink
   :coarse    ::coarse
   :nohiccup  ::nohiccup
   :nowalk    ::nowalk
   :noreads   ::noreads
   :nomemo    ::nomemo
   :bare      ::bare
   :uixlocal  ::uixlocal
   :uixnolink ::uixnolink
   :uixbare   ::uixbare
   :capture   ::capture})

(def commit-frames
  "Eight identically-seeded frames the commit micro takes its entries
  from. Cells are global per (frame, query), so a commit measured twice
  on one frame measures a WARM one the second time."
  (mapv (fn [i] (keyword "re-frame.bench.hicasso.inpage-ladder-app" (str "commit" i)))
        (range 8)))

(defonce ^:private !dispatch
  ;; frame -> its frame-locked dispatch, primed at boot outside every
  ;; window, so no arm pays to CONSTRUCT a dispatch fn during a render.
  (atom {}))

(defn- seed-frame! [fid]
  (m/make-frame! fid lt/seed)
  (m/reseed! fid lt/seed)
  (arms/prime-frame! fid)
  (swap! !dispatch assoc fid (:dispatch (rf/capture-frame fid)))
  fid)

;; ---------------------------------------------------------------------------
;; `local` — the acceptance page re-spelled here, and its no-link twin
;; ---------------------------------------------------------------------------
;;
;; Copied from `shapes/large_template.cljs` and `shapes/card.cljs` at the
;; producing commit, minus the `!body-runs` counter (a witness's, not a
;; page's). The canonical-DOM gate at boot is what holds the copy honest.

(def ^:private inert
  "One hoisted handler for the no-link anchors. The floor hoists its own
  for the same reason: an ablation must not be billed for a closure per
  element that the arm above it does not mint either — and `route-link`'s
  click is a DATA vector, so it mints none."
  (fn [_] nil))

(defn- local-card
  "`shapes/card/card`, re-spelled. `link?` false spells the three
  route-links as literal-href anchors — the same three attribute sets,
  the same DOM, none of routing's work. The article and its pending flag
  are ARGUMENTS, so the same markup serves the census read shape (the
  caller reads per card) and the coarse one (the caller read the whole
  collection once)."
  [slug article pending? link?]
  (let [{:keys [title description createdAt favoritesCount favorited author tagList]} article
        username  (:username author)
        profile-a (fn [class child]
                    (if link?
                      (route-link {:to     :conduit.profile/show
                                   :params {:username username}
                                   :class  class}
                        child)
                      [:a {:class class :href (str "#/profile/" username)
                           :on-click inert}
                       child]))]
    [:div.article-preview {:key         slug
                           :data-testid (str "article-preview-" slug)}
     [:div.article-meta
      (profile-a "author-link" [:img.user-pic {:src (m/avatar-src (:image author)) :alt ""}])
      [:div.info
       (profile-a "author" username)
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
     (let [kids [[:h1 title]
                 [:p description]
                 [:span "Read more..."]
                 [:ul.tag-list
                  (for [tag tagList]
                    [:li.tag-default.tag-pill.tag-outline {:key tag} tag])]]]
       (if link?
         (apply route-link {:to          :conduit.article/show
                            :params      {:slug slug}
                            :class       "preview-link"
                            :data-testid (str "article-link-" slug)}
                kids)
         (into [:a {:class       "preview-link"
                    :href        (str "#/article/" slug)
                    :data-testid (str "article-link-" slug)
                    :on-click    inert}]
               kids)))]))

(defn- read-card
  "The census read shape: two reads per card, inside the `for`, donated to
  the enclosing boundary. This is the pair no hook surface can spell."
  [slug link?]
  (local-card slug
              (sub [:conduit/article slug])
              (sub [:conduit/favorite-pending? slug])
              link?))

(defn- page-chrome
  "The acceptance page around its cards — `shapes/large_template/page`'s
  body with the card list passed in, so the two read shapes differ in
  exactly one thing."
  [your-feed? tags cards]
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
        [:div.article-list {:data-testid "article-list"} cards]]
       [:div.col-md-3
        [:div.sidebar
         [:p "Popular Tags"]
         [:div.tag-list {:data-testid "tag-list"}
          (for [tag tags]
            [:a.tag-pill.tag-default {:key         tag
                                      :href        "#"
                                      :data-testid (str "tag-" tag)}
             tag])]]]]]])

(defn- acceptance-hiccup
  "The census read shape — 141 per-instance reads in one body."
  [link?]
  (page-chrome (sub [:conduit/your-feed?])
               (sub [:conduit/tags])
               (for [slug (sub [:conduit/slugs])]
                 (read-card slug link?))))

(defn- coarse-hiccup
  "**The candidate at the CONTROL's read shape** — the same five coarse
  reads `ux-lt-page` makes, at five fixed sites, cards rendered from the
  collections. Byte-identical DOM, five reads instead of 141.

  This arm exists because the acceptance row is the one row on the census
  roster where the two arms cannot spell the same reads: a hook cannot
  sit inside a `for`, so the UIx twin reads collections where the census
  page reads instances, and the roster stamps that asymmetry on every
  row. `local − coarse` prices the asymmetry on OUR arm, and
  `coarse − uix` is what the two substrates cost each other once the read
  shapes match."
  [link?]
  (let [order    (sub [:conduit/slugs])
        articles (sub [:census56/articles])
        pending  (sub [:census56/pending])]
    (page-chrome (sub [:conduit/your-feed?])
                 (sub [:conduit/tags])
                 (for [slug order]
                   (local-card slug (get articles slug)
                               (contains? pending [:favorite slug]) link?)))))

(defview local-page  "The re-spelled acceptance page."       [_] (acceptance-hiccup true))
(defview nolink-page "The same page, literal-href anchors."  [_] (acceptance-hiccup false))
(defview coarse-page "The same page, the twin's read shape." [_] (coarse-hiccup true))

;; ---------------------------------------------------------------------------
;; The frozen trees — built once, at boot, inside a real body door
;; ---------------------------------------------------------------------------

(defonce ^:private !frozen-hiccup (atom nil))
(defonce ^:private !frozen-element (atom nil))
(defonce ^:private !roster (atom nil))

(defn- sub-pass!
  "The page's own 141 reads, in the page's own realization order,
  performed by the shipping collector."
  []
  (let [^js roster @!roster
        n          (alength roster)]
    (dotimes [i n] (sub (aget roster i)))
    nil))

(defview nohiccup-page "141 reads, then a hiccup tree built at boot."  [_] (sub-pass!) @!frozen-hiccup)
(defview nowalk-page   "141 reads, then an element tree built at boot." [_] (sub-pass!) @!frozen-element)
(defview noreads-page  "The element tree built at boot, no reads."      [_] @!frozen-element)

(def nomemo-page
  "`noreads-page` minted the long way and NOT handed to
  `codec/memoize-boundary!` — the one difference. `element-type` falls
  back to the head itself when there is no `hicassoMemo`, so this is the
  shell without React's wrapper fiber above it."
  (let [c (fn hicasso-boundary-nomemo [js-props]
            (rt/shell (fn [_] @!frozen-element) js-props))]
    (unchecked-set c "displayName" "inpage-ladder/nomemo")
    (codec/mark-boundary! c)))

(defn bare-component
  "No shell: no `useContext`, no `useSyncExternalStore`, no generation
  fence, no read-set entry, no commit. React mounting a tree it was
  handed, and nothing else."
  [_js-props]
  @!frozen-element)

(defui uixbare-page
  "The UIx twin's five coarse reads, then the frozen element tree. The
  UIx arm's own body work is `uixlocal − uixbare`."
  [_props]
  (let [frame (uixa/use-current-frame)]
    (uixa/use-subscribe frame [:conduit/slugs])
    (uixa/use-subscribe frame [:census56/articles])
    (uixa/use-subscribe frame [:conduit/tags])
    (uixa/use-subscribe frame [:conduit/your-feed?])
    (uixa/use-subscribe frame [:census56/pending])
    @!frozen-element))

;; ---------------------------------------------------------------------------
;; The UIx ladder — the same two rungs on the control arm
;; ---------------------------------------------------------------------------
;;
;; The candidate's ladder without a matching one on the twin answers half
;; the question. The twin pays a routing term too (`route-attrs`, three
;; syntheses per card, the same 207), and its `$` markup is element
;; creation the candidate reaches through an interpreter — so `uix` is
;; re-spelled here and stepped down the same way: routing off, then the
;; markup off. `census_clock_arms`' own private helpers are re-spelled
;; with it, for the rf2-2rtt6.32 reason the hicasso copy is.

(def ^:private favorite-base "btn btn-outline-primary btn-sm pull-xs-right")

(defn- favorite-class [favorited pending?]
  (let [declared (cond-> "" favorited (str " active") pending? (str " optimistic"))]
    (if (seq declared) (str favorite-base " " declared) favorite-base)))

(defn- nav-class [active?] (if active? "nav-link active" "nav-link"))

(def ^:private ux-link-model
  (delay (late-bind/require-fn! :routing/link-model 'inpage-ladder {} {})))

(def ^:private ux-activate-link!
  (delay (late-bind/require-fn! :routing/activate-link! 'inpage-ladder {} {})))

(defn- ux-route-attrs
  "`census_clock_arms/route-attrs`, re-spelled — the twins' hoisted-seam
  spelling of the same two routing calls."
  [frame to params]
  (let [{:keys [href payload native?]} (@ux-link-model {:to to :params params} frame)]
    {:href href :on-click (fn [e] (@ux-activate-link! e nil frame payload native?))}))

(defn- ux-local-card [slug article pending? d frame link?]
  (let [{:keys [title description createdAt favoritesCount favorited author tagList]} article
        username (:username author)
        prof     (fn [] (if link?
                          (ux-route-attrs frame :conduit.profile/show {:username username})
                          {:href (str "#/profile/" username) :on-click inert}))
        pic      (prof)
        nam      (prof)
        read     (if link?
                   (ux-route-attrs frame :conduit.article/show {:slug slug})
                   {:href (str "#/article/" slug) :on-click inert})]
    ($ :div.article-preview {:key slug :data-testid (str "article-preview-" slug)}
       ($ :div.article-meta
          ($ :a.author-link {:href (:href pic) :on-click (:on-click pic)}
             ($ :img.user-pic {:src (m/avatar-src (:image author)) :alt ""}))
          ($ :div.info
             ($ :a.author {:href (:href nam) :on-click (:on-click nam)} username)
             ($ :span.date createdAt))
          ($ :button {:type        "button"
                      :data-testid (str "favorite-" slug)
                      :class       (favorite-class favorited pending?)
                      :on-click    (fn [_] (d [:conduit/favorite slug]))}
             ($ :i.ion-heart) " "
             ($ :span {:data-testid (str "favorites-count-" slug)} favoritesCount)))
       ($ :a.preview-link {:href        (:href read)
                           :on-click    (:on-click read)
                           :data-testid (str "article-link-" slug)}
          ($ :h1 title)
          ($ :p description)
          ($ :span "Read more...")
          ($ :ul.tag-list
             (for [tag tagList]
               ($ :li.tag-default.tag-pill.tag-outline {:key tag} tag)))))))

(defn- ux-local-chrome [your-feed? tags d cards]
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
                       ($ :a {:href "#" :data-testid "your-feed-tab"
                              :class (nav-class your-feed?)
                              :on-click (fn [e] (.preventDefault e) (d [:conduit/show-your-feed]))}
                          "Your Feed"))
                    ($ :li.nav-item
                       ($ :a {:href "#" :data-testid "global-feed-tab"
                              :class (nav-class (not your-feed?))
                              :on-click (fn [e] (.preventDefault e) (d [:conduit/show-global-feed]))}
                          "Global Feed"))))
              ($ :div.article-list {:data-testid "article-list"} cards))
           ($ :div.col-md-3
              ($ :div.sidebar
                 ($ :p "Popular Tags")
                 ($ :div.tag-list {:data-testid "tag-list"}
                    (for [tag tags]
                      ($ :a.tag-pill.tag-default {:key tag :href "#" :data-testid (str "tag-" tag)}
                         tag)))))))))

(defn- ux-page-body [link? _props]
  (let [frame      (uixa/use-current-frame)
        order      (uixa/use-subscribe frame [:conduit/slugs])
        articles   (uixa/use-subscribe frame [:census56/articles])
        tags       (uixa/use-subscribe frame [:conduit/tags])
        your-feed? (uixa/use-subscribe frame [:conduit/your-feed?])
        pending    (uixa/use-subscribe frame [:census56/pending])
        d          (get @!dispatch frame)]
    (ux-local-chrome your-feed? tags d
                     (for [slug order]
                       (ux-local-card slug (get articles slug)
                                      (contains? pending [:favorite slug]) d frame link?)))))

(defui uixlocal-page  [props] (ux-page-body true props))
(defui uixnolink-page [props] (ux-page-body false props))

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(defn- react-root-arm [id element-of]
  {:id      id
   :mount   (fn [container _props _n]
              (let [r (react-dom-client/createRoot container)]
                (.render r (element-of))
                r))
   :unmount (fn [r] (.unmount r))})

(defn- hicasso-arm [id view]
  (let [fid (get local-frames id)]
    (react-root-arm id
      (fn [] (arm1-mount/provider fid (codec/as-element [view {}]))))))

(defn- uix-arm [id view]
  (let [fid (get local-frames id)]
    (react-root-arm id
      (fn [] ($ uixa/frame-provider {:frame fid} ($ view {}))))))

(defn- ladder-arms []
  [(arms/arm row-key :hicasso)                              ; :hicasso — `ship`
   (hicasso-arm :local    local-page)
   (hicasso-arm :coarse   coarse-page)
   (hicasso-arm :nolink   nolink-page)
   (hicasso-arm :nohiccup nohiccup-page)
   (hicasso-arm :nowalk   nowalk-page)
   (hicasso-arm :noreads  noreads-page)
   (hicasso-arm :nomemo   nomemo-page)
   (react-root-arm :bare
     (fn [] (arm1-mount/provider (:bare local-frames)
              (react/createElement bare-component nil))))
   (arms/arm row-key :floor)
   (arms/arm row-key :uix)
   (uix-arm :uixlocal  uixlocal-page)
   (uix-arm :uixnolink uixnolink-page)
   (uix-arm :uixbare   uixbare-page)
   (assoc (arms/arm row-key :ctl-2x) :control? true)])

(def ^:private control-ids #{:ctl-2x})

;; ---------------------------------------------------------------------------
;; The fairness gate — every non-control arm builds the SAME page
;; ---------------------------------------------------------------------------

(defn- canon-of [arm]
  (let [mnt (lane/mount-arm! arm {})
        s   (lane/canonical (:container mnt))
        n   (lane/element-count (:container mnt))]
    (lane/release! mnt)
    {:canonical s :elements n}))

(defn- parity-problems [arms']
  (let [reference (canon-of (first arms'))
        expected  (arms/expected-elements row-key :hicasso)]
    (into (if (= expected (:elements reference))
            []
            [{:arm :hicasso :problem :element-count
              :predicted expected :measured (:elements reference)}])
          (comp (drop 1)
                (remove #(control-ids (:id %)))
                (keep (fn [arm]
                        (let [{:keys [canonical elements]} (canon-of arm)]
                          (cond
                            (not= (:canonical reference) canonical)
                            ;; RELABELLED, not converted (rf2-2rtt6.121).
                            ;; This pair is only ever read as ours-vs-theirs
                            ;; on one refusal — a same-against-same
                            ;; comparison of two strings, for which code
                            ;; units are the honest unit. Nothing publishes
                            ;; it as a size.
                            {:arm (:id arm) :problem :canonical-dom-disagreement
                             :code-units-ours (count canonical)
                             :code-units-reference (count (:canonical reference))}
                            (not= expected elements)
                            {:arm (:id arm) :problem :element-count
                             :predicted expected :measured elements})))))
          arms')))

;; ---------------------------------------------------------------------------
;; The sampler — mount, read, release, settle, and prove the next mount cold
;; ---------------------------------------------------------------------------

(def ^:private sampling {:warmup 4 :samples 10})
(def ^:private rounds 6)

(defonce ^:private !cells-after-window (atom {}))

(defn- rounds-async!
  "The reflecting-schedule sampler, promise-chained. Every sample index
  visits every arm in [[lane/slot-order]]'s order; warm-up samples are
  taken and discarded; between samples the mount is released and ONE
  macrotask settles, which is what lets the cell reaper run and so what
  makes the next sample's 141 reads COLD again — the property the whole
  ladder is about."
  [arms' {:keys [warmup samples]} rounds']
  (let [k    (count arms')
        coll (lane/sample-collector)
        out  (atom [])]
    (-> (lane/chain
          nil
          (for [round (range rounds')
                s     (range (+ warmup samples))
                j     (lane/slot-order k s)]
            [round s j])
          (fn [_ [round s j]]
            (let [arm (nth arms' j)
                  mnt (lane/mount-arm! arm {})
                  ms  (:ms mnt)]
              ;; OUTSIDE the window: did the boundary's commit run inside
              ;; the flushSync we just timed? 141 live cells says yes.
              (when (= :hicasso (:id arm))
                (swap! !cells-after-window update (:cells (rt/stats)) (fnil inc 0)))
              (lane/release! mnt)
              (when (>= s warmup)
                (lane/collect! coll (name (:id arm)) ms)
                (swap! out conj [round (:id arm) ms]))
              (lane/settle!))))
        (.then (fn [_] {:rows @out :samples (:samples @coll)})))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt [x n] (.toFixed ^number x n))

(defn- trimmed-mean
  "The 10%-trimmed mean. **The deltas are quoted on this, and the reason
  is the clock and not a preference.** Chrome clamps `performance.now` to
  100 µs, so every single-mount reading is a grid value and so is a p50
  of them — a term worth 0.05 ms is invisible to a median and a term
  worth 0.25 ms is quoted to ±0.05. A mean over 72 quantised samples
  estimates the underlying mean off-grid, and the clamp's bias (identical
  for every arm through one door) cancels in a difference. Trimmed, not
  raw, because this box produces occasional 2× outliers that a raw mean
  would spend on the wrong arm."
  [xs]
  (let [v (vec (sort xs))
        n (count v)
        k (js/Math.floor (* 0.1 n))
        w (subvec v k (- n k))]
    (when (seq w) (/ (reduce + 0.0 w) (count w)))))

(defn- per-arm [rows ids]
  (into {}
        (map (fn [id]
               (let [xs (into [] (comp (filter #(= id (nth % 1))) (map #(nth % 2))) rows)]
                 [id (assoc (lane/summarise xs) :tmean (trimmed-mean xs))])))
        ids))

(defn- per-round-ratio
  "Each round's own `id / floor`, so a range is over rounds and never over
  raw samples pooled across a drifting box."
  [rows ids floor-id rounds']
  (let [round-p50 (fn [round id]
                    (:p50 (lane/summarise
                            (into [] (comp (filter #(and (= round (nth % 0))
                                                         (= id (nth % 1))))
                                           (map #(nth % 2)))
                                  rows))))]
    (into {}
          (map (fn [id]
                 (let [vs (mapv (fn [r] (/ (round-p50 r id) (round-p50 r floor-id)))
                                (range rounds'))]
                   [id {:mean (lane/round4 (/ (reduce + 0.0 vs) (count vs)))
                        :min  (lane/round4 (apply min vs))
                        :max  (lane/round4 (apply max vs))}])))
          ids)))

(defn- arm-line [id {:keys [p50 tmean min max]}]
  (str ";;   " (name id) ": tmean " (fmt tmean 4) "  p50 " (fmt p50 4)
       " [" (fmt min 4) " – " (fmt max 4) "] ms/mount"))

(defn- delta-line [label above below]
  (str ";;   " label ": " (fmt (- above below) 4) " ms"))

;; ---------------------------------------------------------------------------
;; Micro corroborations — the two terms a mount ladder cannot see alone
;; ---------------------------------------------------------------------------

(defn- ns-per-op [reps f]
  (let [sink (volatile! nil)
        t0   (lane/now-ms)]
    (dotimes [_ reps] (vreset! sink (f)))
    (/ (* 1e6 (- (lane/now-ms) t0)) reps)))

(def ^:private passes-per-window
  "Body-door passes inside ONE micro window. Chrome clamps
  `performance.now` to 100 µs; eight ~1,200-element passes hold the
  window in whole milliseconds, and it is the count `rf2-6c237`'s read
  profile used — so `reads-ms` below is directly comparable to its
  0.2875 ms/pass."
  8)

(defn- window-ms [reps f]
  (let [t0 (lane/now-ms)]
    (dotimes [_ reps] (f))
    (/ (- (lane/now-ms) t0) reps)))

(defn- micro-table
  "Independent readings of the three terms the mount ladder infers by
  subtraction, plus the one it cannot see at all.

  **A discarded hiccup tree is not a built one.** The first attempt at
  this table timed `(do (acceptance-hiccup false) [:span])` and read the
  page as CHEAPER than its own reads — because the page's cards live
  inside `for`, and a lazy seq nobody walks is never realized. Every row
  below therefore RETURNS what it builds, so the codec's walk forces it,
  and the walk is subtracted off by the frozen row beside it.

  No commit happens on the capture frame, so every read in these loops is
  cold, exactly as at a mount."
  []
  (let [fid (:capture local-frames)
        pass (fn [f] (/ (window-ms 30 (fn [] (dotimes [_ passes-per-window]
                                               (rt/render-body fid f {}))))
                        passes-per-window))]
    [[:build+walk+reads-ms (pass (fn [_] (acceptance-hiccup false)))]
     [:walk+reads-ms       (pass (fn [_] (sub-pass!) @!frozen-hiccup))]
     [:reads-ms            (pass (fn [_] (sub-pass!) [:span]))]
     [:empty-door-ms       (pass (fn [_] [:span]))]
     ;; One `route-link`'s late-bind resolution — the per-anchor asymmetry
     ;; the twins hoist and the candidate does not (207 of these a mount).
     [:late-bind-resolve-ns
      (ns-per-op 20000 (fn [] (late-bind/require-fn! :routing/link-model 'inpage-ladder {} {})))]]))

(defn- commit-half-ms
  "The commit half, through the runtime's own `commit-boundary!` seam, on
  eight identically-seeded frames per window — `rf2-6c237` read 0.7625 ms
  per 141-key commit and this is the same measurement at this commit.

  Entries are re-rendered before every window and every release is called
  after it, so each window's eight commits are COLD."
  []
  (let [reps 12
        one  (fn []
               (let [entries (mapv (fn [f]
                                     (rt/render-body f (fn [_] (sub-pass!) [:span]) {})
                                     (rt/last-reads))
                                   commit-frames)
                     t0       (lane/now-ms)
                     releases (mapv (fn [e] (rt/commit-boundary! e (fn [] nil))) entries)
                     ms       (- (lane/now-ms) t0)]
                 (doseq [r releases] (r))
                 ;; Synchronously, because the cell reaper's grace is a
                 ;; macrotask and this loop never yields — an un-reset
                 ;; runtime would make the next rep's 141 reads WARM and
                 ;; its commit a no-op.
                 (rt/reset-runtime!)
                 (/ ms (count commit-frames))))
        xs   (vec (repeatedly reps one))]
    (:p50 (lane/summarise xs))))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn- harvest-roster!
  "Mount the REAL acceptance page and read back the read-set entry the
  mount resolved: its key array IS the page's read sequence, in
  realization order, straight from the machinery under test. Fatal unless
  it is the arithmetic's 3 + 2 × 69 = 141 distinct reads on the
  1,202-element page."
  [fid]
  (let [container (arm1-mount/fresh-container!)
        handle    (arm1-mount/root! container fid [lt/page {}])
        ^js entry (rt/last-reads)
        ks        (.-keys entry)
        n         (lane/element-count container)
        expected  (lt/element-arithmetic)
        roster    (let [a #js []]
                    (dotimes [i (alength ks)] (.push a (nth (aget ks i) 1)))
                    a)
        distinct-n (count (into #{} (array-seq roster)))
        want       (+ 3 (* 2 lt/article-count))]
    (arm1-mount/unmount! handle)
    (rt/reset-runtime!)
    (when-not (= expected n)
      (throw (ex-info (str "harvest FAILED: page has " n " elements, expected " expected) {})))
    (when-not (and (= want (alength roster)) (= want distinct-n))
      (throw (ex-info (str "harvest FAILED: roster carries " (alength roster)
                           " reads (" distinct-n " distinct), expected " want) {})))
    {:roster roster :elements n}))

(defn- freeze!
  "Build the no-link page's hiccup and its React element tree ONCE, inside
  a real body door on the capture frame."
  [fid]
  (let [element (rt/render-body fid
                                (fn [_] (let [h (acceptance-hiccup false)]
                                          (reset! !frozen-hiccup h)
                                          h))
                                {})]
    (reset! !frozen-element element)
    (rt/reset-runtime!)
    nil))

(defn ^:export -main []
  (rf/init! uixa/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          ;; `census_clock_arms` owns the published `ship`, `uix`, `floor`
          ;; and `ctl-2x` arms, so its frames are made its way.
          (arms/ensure-frames! [:floor :hicasso :uix])
          (doseq [[_ fid] local-frames] (seed-frame! fid))
          (doseq [fid commit-frames] (seed-frame! fid))
          (let [{:keys [roster elements]} (harvest-roster! (:capture local-frames))]
            (reset! !roster roster)
            (js/console.log (str ";; harvest OK — " elements " elements, "
                                 (alength roster) " reads, from the real page's own entry"))
            (freeze! (:capture local-frames))
            (let [arms'    (ladder-arms)
                  problems (parity-problems arms')]
              (when (seq problems)
                (throw (ex-info (str "canonical-DOM parity FAILED: " (pr-str problems)) {})))
              (js/console.log (str ";; parity OK — " (count arms') " arms, "
                                   (- (count arms') (count control-ids))
                                   " of them byte-identical to the candidate's page"))
              (-> (lane/settle!)
                  (.then (fn [_] (rounds-async! arms' sampling rounds)))
                  (.then
                    (fn [{:keys [rows samples]}]
                      (let [ids  (mapv :id arms')
                            p50  (per-arm rows ids)
                            gv   (lane/guard! samples "in-page ladder (in-page flushSync window, diagnostic)")
                            ctl  (lane/control-verdict
                                   (arms/ctl-predicted row-key)
                                   (get (per-round-ratio rows [:ctl-2x] :floor rounds) :ctl-2x)
                                   0.25)
                            g    (fn [id] (:tmean (get p50 id)))]
                        (lane/record! :inpage-ladder-arms
                                      (into {} (map (fn [[k v]]
                                                      [k (-> v (update :min lane/round4)
                                                             (update :max lane/round4)
                                                             (update :p50 lane/round4)
                                                             (update :tmean lane/round4))]))
                                            p50))
                        (lane/record! :inpage-ladder-rounds
                                      (mapv (fn [[r id ms]] [r id (lane/round4 ms)]) rows))
                        (lane/record! :inpage-ladder-ratio-to-floor
                                      (per-round-ratio rows ids :floor rounds))
                        (lane/record! :inpage-ladder-cells-after-window @!cells-after-window)
                        (lane/record! :inpage-ladder-decomposition
                                      (into {} (map (fn [[k v]] [k (lane/round4 v)]))
                                            {:ship                (g :hicasso)
                                             :uix                 (g :uix)
                                             :bare                (g :bare)
                                             :floor               (g :floor)
                                             :deficit             (- (g :hicasso) (g :uix))
                                             :h-routing           (- (g :local) (g :nolink))
                                             :h-hiccup-build      (- (g :nolink) (g :nohiccup))
                                             :h-codec-walk        (- (g :nohiccup) (g :nowalk))
                                             :h-reads+commit      (- (g :nowalk) (g :noreads))
                                             :h-memo-fiber        (- (g :noreads) (g :nomemo))
                                             :h-shell             (- (g :nomemo) (g :bare))
                                             :h-total             (- (g :hicasso) (g :bare))
                                             :u-routing           (- (g :uixlocal) (g :uixnolink))
                                             :u-markup            (- (g :uixnolink) (g :uixbare))
                                             :u-reads+hooks       (- (g :uixbare) (g :bare))
                                             :u-total             (- (g :uix) (g :bare))
                                             :h-read-shape        (- (g :local) (g :coarse))
                                             :matched-shape-gap   (- (g :coarse) (g :uix))
                                             :coarse              (g :coarse)
                                             :fidelity-local-ship (/ (g :local) (g :hicasso))
                                             :fidelity-uixlocal-uix (/ (g :uixlocal) (g :uix))}))
                        (js/console.log ";; ==== IN-PAGE LADDER (ms per mount, in-page flushSync window; DIAGNOSTIC) ====")
                        (js/console.log (str ";;   design " rounds "x(" (:warmup sampling) "+"
                                             (:samples sampling) ")  page 1,202 el / 1 boundary / 141 reads / 207 links"))
                        (doseq [id ids] (js/console.log (arm-line id (get p50 id))))
                        (js/console.log ";; ==== THE DECOMPOSITION — deltas on the 10% trimmed mean ====")
                        (js/console.log (str ";;   copy fidelity: local/ship = " (fmt (/ (g :local) (g :hicasso)) 4)
                                             "   uixlocal/uix = " (fmt (/ (g :uixlocal) (g :uix)) 4)))
                        (js/console.log ";;   -- the candidate --")
                        (js/console.log (delta-line "routing term        (local - nolink)" (g :local) (g :nolink)))
                        (js/console.log (delta-line "hiccup build        (nolink - nohiccup)" (g :nolink) (g :nohiccup)))
                        (js/console.log (delta-line "codec walk          (nohiccup - nowalk)" (g :nohiccup) (g :nowalk)))
                        (js/console.log (delta-line "141 reads + commit  (nowalk - noreads)" (g :nowalk) (g :noreads)))
                        (js/console.log (delta-line "memo wrapper fiber  (noreads - nomemo)" (g :noreads) (g :nomemo)))
                        (js/console.log (delta-line "the 2-hook shell    (nomemo - bare)" (g :nomemo) (g :bare)))
                        (js/console.log (delta-line "  candidate total   (ship - bare)" (g :hicasso) (g :bare)))
                        (js/console.log ";;   -- the control --")
                        (js/console.log (delta-line "routing term        (uixlocal - uixnolink)" (g :uixlocal) (g :uixnolink)))
                        (js/console.log (delta-line "$ markup + card fns (uixnolink - uixbare)" (g :uixnolink) (g :uixbare)))
                        (js/console.log (delta-line "5 reads + hooks     (uixbare - bare)" (g :uixbare) (g :bare)))
                        (js/console.log (delta-line "  control total     (uix - bare)" (g :uix) (g :bare)))
                        (js/console.log ";;   -- the read shape, priced on OUR arm --")
                        (js/console.log (delta-line "141 per-instance vs 5 coarse (local - coarse)" (g :local) (g :coarse)))
                        (js/console.log (delta-line "matched read shape, ours - theirs (coarse - uix)" (g :coarse) (g :uix)))
                        (js/console.log ";;   -- the base and the deficit --")
                        (js/console.log (delta-line "React mounts 1,202 elements it was handed (bare)" (g :bare) 0.0))
                        (js/console.log (delta-line "floor (createElement inside the window)" (g :floor) 0.0))
                        (js/console.log (delta-line "THE DEFICIT         (ship - uix)" (g :hicasso) (g :uix)))
                        (js/console.log (str ";;   control: " (:why ctl)))
                        (js/console.log (str ";;   cells live immediately after the timed window, by count: "
                                             (pr-str @!cells-after-window)
                                             "  (141 = the boundary's commit ran INSIDE the window)"))
                        (let [micro  (micro-table)
                              mm     (into {} micro)
                              commit (commit-half-ms)]
                          (lane/record! :inpage-ladder-micro
                                        (assoc (into {} (map (fn [[k v]] [k (lane/round4 v)])) micro)
                                               :commit-half-ms (lane/round4 commit)))
                          (js/console.log ";; ==== MICRO (ms per body-door pass unless named otherwise) ====")
                          (doseq [[k v] micro]
                            (js/console.log (str ";;   " (name k) ": " (fmt v 4))))
                          (js/console.log (str ";;   commit-half-ms (per 141-key commit-boundary!): " (fmt commit 4)))
                          (js/console.log (str ";;   => hiccup build alone: "
                                               (fmt (- (:build+walk+reads-ms mm) (:walk+reads-ms mm)) 4) " ms/pass"))
                          (js/console.log (str ";;   => codec walk alone:   "
                                               (fmt (- (:walk+reads-ms mm) (:reads-ms mm)) 4) " ms/pass"))
                          (js/console.log (str ";;   => 141 cold reads:     "
                                               (fmt (- (:reads-ms mm) (:empty-door-ms mm)) 4) " ms/pass ("
                                               (fmt (* 1e3 (/ (- (:reads-ms mm) (:empty-door-ms mm)) 141)) 2)
                                               " µs/read; rf2-6c237 read 2.04)")))
                        (lane/record! :inpage-ladder-runtime (lane/runtime-label))
                        (when (:refuse? gv)
                          (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                        (when-not (:ok? ctl)
                          (set! (.-HICASSO_CONTROL_FAILED js/window) true))
                        (lane/assert-teardown-clean! "the in-page ladder")
                        (lane/done!)))))))))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))
