(ns re-frame.bench.hicasso.shapes.model
  "THE TIER-1 SHAPE ROSTER'S STATE LAYER — one census-real app behind all
  four shapes (rf2-2rtt6.51).

  The charter's §Use cases names five tier-1 shapes as v0's definition of
  done. Four of them are *the everyday SPA spine* and they differ in
  exactly one thing: **how many boundaries the same markup is cut into,
  and how many of them one commit moves.**

  | shape | the page | boundaries | what a commit moves |
  |---|---|---|---|
  | 1 ordinary | the article page's comment column | ~8 | a few |
  | 2 large template | the whole feed, ONE boundary | **1** | that one |
  | 3 bulk | the same feed, one boundary per card | **300** | **all 300** |
  | 4 narrow | the same page as 3, mounted | 300 | **exactly one** |

  Putting them on one state layer is not tidiness. A shape roster whose
  four pages sit on four hand-written models is measuring four
  applications, and any difference read between two rows is
  unattributable — the same argument
  [[re-frame.bench.hicasso.front.dogfood]] makes for the three dogfood
  renderings and `jsfb-model` makes for the two js-framework-benchmark
  arms. Here it binds harder, because shapes 2/3/4 are deliberately **the
  same screen at three boundary decompositions**: if the model differed,
  the decomposition would not be the only variable and the roster would
  prove nothing about boundaries at all.

  ## The app is the census's, not an invention

  RealWorld (Conduit) — `examples/real-apps/realworld_resources/` — is
  the corpus the fitness harness census was taken on, and it is the app
  the earlier ergonomics work ported from (the article editor's four
  fields, `front/census_article_editor_cljs_test`). The shapes port its
  screens: the article page's comment column (shape 1) and the home feed
  (shapes 2/3/4). Field names are Conduit's own, down to the
  `favoritesCount` / `createdAt` camelCase the API hands back, because a
  port that renamed them would be a screen this repo wrote rather than
  one it found.

  **What the port leaves behind, and why.** The census screens read
  `[:rf/resource …]` and `[:rf/mutation …]` — framework subs, which the
  charter counts at **27% of read traffic**. Those are re-frame2
  capabilities, not view-layer surface, and standing them up here would
  put an HTTP-shaped resource cache behind a view-layer witness. The
  shapes read plain `reg-sub`s with the same *shapes* — a per-instance
  `:conduit/favorite-pending?` where the census reads
  `[:rf/mutation {:instance [:favorite slug]}]` — and the omission is
  filed rather than papered over: **framework subs are not exercised by
  this roster** (see the bead's Findings).

  ## The two writes the roster turns on

  `:conduit/favorite` is **the narrow write**: it changes one article, so
  exactly one `[:conduit/article slug]` moves, so under a correct index
  exactly one boundary re-runs. `:conduit/refresh-feed` is **the broad
  write**: every article moves in one commit, which is shape 3's
  make-or-break row. They live here rather than beside a view because
  both feed pages dispatch the same two, and a shape whose write differed
  from its neighbour's would not be the same page re-cut.

  Neither is synthetic. Favouriting an article is the single most-clicked
  control in Conduit, and a feed refresh is what a poll, a reconnect or a
  page change does to a cached list.

  ## Determinism

  Every string is a pure function of an index — no `rand`, no clock. Two
  mounts of the same seed build byte-identical DOM, which is what lets a
  witness compare a page against itself across a commit and read the
  difference as the commit's."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The seed data — Conduit's own field names, from a deterministic index
;; ---------------------------------------------------------------------------

(def ^:private authors
  "Four authors, so a feed of any size has a repeating byline the way a
  real one does, and so `mine?`-style per-row branches have both answers
  to take."
  ["jane" "riku" "amara" "tomas"])

(def ^:private tag-vocabulary
  ["clojure" "react" "hiccup" "state" "forms" "perf" "cljs" "design"
   "testing" "routing"])

(defn- author-for [i]
  (let [name (nth authors (mod i (count authors)))]
    {:username  name
     :image     name
     :following (odd? i)}))

(defn- tags-for
  "Two tags per article, drawn from the vocabulary by index. Two rather
  than a varying count on purpose: a card's element count is then a
  constant, so a page's total is arithmetic a witness can predict rather
  than a number it has to accept."
  [i]
  [(nth tag-vocabulary (mod i (count tag-vocabulary)))
   (nth tag-vocabulary (mod (+ i 3) (count tag-vocabulary)))])

(defn article
  "One Conduit article, as the API shapes it."
  [i]
  (let [slug (str "article-" i)]
    {:slug           slug
     :title          (str "Article " i)
     :description    (str "What article " i " is about.")
     :body           (str "The body of article " i ".")
     :createdAt      (str "2026-0" (inc (mod i 9)) "-1" (mod i 10))
     :favoritesCount (mod (* 7 i) 23)
     :favorited      (zero? (mod i 5))
     :author         (author-for i)
     :tagList        (tags-for i)}))

(defn comment-for
  "One comment on the article page. Every fourth is the signed-in
  reader's own, so the card's author-only delete control has both
  branches present on one page."
  [i]
  (let [mine? (zero? (mod i 4))]
    {:id        i
     :body      (str "Comment " i " on this article.")
     :createdAt (str "2026-07-2" (mod i 10))
     :author    (if mine?
                  {:username "me" :image "me"}
                  (author-for i))}))

(def signed-in-user
  "The reader the pages render for. Present rather than nil, because the
  signed-out branches of these screens render *less*, and a roster whose
  pages took the cheap branch would be understating every shape."
  {:username "me" :image "me"})

(def comment-draft-key
  "The draft key for the not-yet-posted comment. HD-009: the half-typed
  comment lives in app-db under one parametric subscription and one named
  event, not in a component-local cell."
  ::comment-draft)

(defn seed-db
  "`articles` articles, `comments` comments, one signed-in reader. The one
  place the shape is written out."
  [{:keys [articles comments tags]
    :or   {articles 20 comments 5 tags 10}}]
  {:articles      (into {} (map (fn [i] (let [a (article i)] [(:slug a) a]))) (range articles))
   :order         (mapv (fn [i] (:slug (article i))) (range articles))
   :comments      (into {} (map (fn [i] [i (comment-for i)])) (range comments))
   :comment-order (vec (range comments))
   :tags          (vec (take tags tag-vocabulary))
   :drafts        {}
   :pending       #{}
   :page          1
   :your-feed?    false
   :user          signed-in-user})

;; ---------------------------------------------------------------------------
;; Subscriptions — what a boundary reads
;; ---------------------------------------------------------------------------

(rf/reg-sub :conduit/user (fn [db _] (:user db)))

(rf/reg-sub :conduit/slugs (fn [db _] (:order db)))

(rf/reg-sub :conduit/article (fn [db [_ slug]] (get-in db [:articles slug])))

(rf/reg-sub :conduit/tags (fn [db _] (:tags db)))

(rf/reg-sub :conduit/your-feed? (fn [db _] (boolean (:your-feed? db))))

;; Registered and read by nothing, deliberately. `:conduit/go-to-page` is
;; the roster's **negative control** — a commit that moves a key no
;; boundary holds — and `bulk_dom_cljs_test/the-broad-write-can-answer-false`
;; needs it to move something real rather than nothing at all.
(rf/reg-sub :conduit/page (fn [db _] (:page db)))

(rf/reg-sub :conduit/comment-ids (fn [db _] (:comment-order db)))

(rf/reg-sub :conduit/comment (fn [db [_ id]] (get-in db [:comments id])))

(rf/reg-sub :conduit/draft (fn [db [_ k]] (get-in db [:drafts k] "")))

;; The mutation-shaped reads. The census spells these
;; `[:rf/mutation {:instance [:favorite slug]}]`; here they are ordinary
;; parametric subs over one `:pending` set, so the *read shape* a card
;; carries — one per-instance status read alongside its data read — is the
;; census's even though the machinery behind it is not.
(rf/reg-sub :conduit/favorite-pending?
  (fn [db [_ slug]] (contains? (:pending db) [:favorite slug])))

(rf/reg-sub :conduit/comment-pending?
  (fn [db _] (contains? (:pending db) [:post-comment])))

(rf/reg-sub :conduit/delete-pending?
  (fn [db [_ id]] (contains? (:pending db) [:delete-comment id])))

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(rf/reg-event :conduit/seed (fn [_ [_ opts]] {:db (seed-db opts)}))

(rf/reg-event :conduit/favorite
  ;; **THE NARROW WRITE.** One article changes, so exactly one
  ;; `[:conduit/article slug]` moves. Everything else in app-db is
  ;; untouched, including `:order` — which is what makes the list
  ;; boundary's non-re-render an assertion the witness can make.
  (fn [{:keys [db]} [_ slug]]
    {:db (update-in db [:articles slug]
                    (fn [a]
                      (let [now (not (:favorited a))]
                        (assoc a :favorited now
                                 :favoritesCount ((if now inc dec) (:favoritesCount a))))))}))

(rf/reg-event :conduit/refresh-feed
  ;; **THE BROAD WRITE** — shape 3's make-or-break commit. Every article
  ;; moves, in one commit, so every card boundary is dirty. `:order` does
  ;; NOT move, so the list boundary above them is not: the 300 re-renders
  ;; are the index's answer about the cards, not a parent rebuilding its
  ;; children.
  (fn [{:keys [db]} _]
    {:db (update db :articles
                 (fn [as]
                   (reduce-kv (fn [m slug a] (assoc m slug (update a :favoritesCount inc)))
                              {} as)))}))

(rf/reg-event :conduit/edit-draft
  (fn [{:keys [db]} [_ k v]] {:db (assoc-in db [:drafts k] v)}))

(rf/reg-event :conduit/post-comment
  ;; The draft clears by explicit caller revision (HD-019), never by
  ;; value equality.
  (fn [{:keys [db]} _]
    (let [body (get-in db [:drafts comment-draft-key] "")]
      (if (= "" body)
        {:db db}
        (let [id (inc (reduce max -1 (:comment-order db)))]
          {:db (-> db
                   (assoc-in [:comments id] {:id id :body body
                                             :createdAt "2026-08-01"
                                             :author signed-in-user})
                   (update :comment-order conj id)
                   (update :drafts dissoc comment-draft-key))})))))

(rf/reg-event :conduit/delete-comment
  (fn [{:keys [db]} [_ id]]
    {:db (-> db
             (update :comments dissoc id)
             (update :comment-order (fn [o] (filterv #(not= id %) o))))}))

(rf/reg-event :conduit/go-to-page
  (fn [{:keys [db]} [_ p]] {:db (assoc db :page p)}))

;; The feed toggle. A page-chrome write: it moves `[:conduit/your-feed?]`,
;; which the page boundary reads and no card does — so it re-runs the page
;; and none of its rows, which is the mirror image of the narrow write.
(rf/reg-event :conduit/show-your-feed
  (fn [{:keys [db]} _] {:db (assoc db :your-feed? true)}))

(rf/reg-event :conduit/show-global-feed
  (fn [{:keys [db]} _] {:db (assoc db :your-feed? false)}))

(rf/reg-event :conduit/begin
  ;; An in-flight write, so the pending branches of the ported screens are
  ;; reachable from a witness without an HTTP stack behind them.
  (fn [{:keys [db]} [_ instance]] {:db (update db :pending conj instance)}))

(rf/reg-event :conduit/settle
  (fn [{:keys [db]} [_ instance]] {:db (update db :pending disj instance)}))

;; ---------------------------------------------------------------------------
;; The frame lifecycle
;; ---------------------------------------------------------------------------

(defn make-frame!
  "Create (or idempotently replace) `frame-id`'s frame, seeded per `opts`."
  [frame-id opts]
  (rf/make-frame {:id frame-id :initial-events [[:conduit/seed opts]]})
  frame-id)

(defn reseed!
  "Return the frame to its seeded state, so one witness never reads a page
  a previous one already mutated. `make-frame!` is idempotent and will
  NOT replay its initial events for an id that already exists, so both
  halves are needed."
  [frame-id opts]
  (rf/with-frame frame-id (rf/dispatch-sync [:conduit/seed opts]))
  nil)

;; ---------------------------------------------------------------------------
;; Small pure helpers the ported markup uses
;; ---------------------------------------------------------------------------

(defn avatar-src
  "The census screens call `realworld-shared.avatar/avatar-src`; the
  examples tree is not on this classpath, so the one line it contributes
  is inlined rather than depended on.

  It answers a `data:` URI rather than a URL, and that is deliberate: the
  bulk page renders **300 `<img>` elements**, and a witness that is not
  about images should not open 300 network requests to find out whether a
  commit re-rendered a row. A hostname that does not resolve is worse than
  useless — it fills the console with `ERR_NAME_NOT_RESOLVED` and buries
  the failures a reader is actually looking for. The author's own token
  stays *in* the URI, so per-author distinctness survives into the DOM and
  a card comparison still compares something."
  [image]
  (str "data:," (or image "anon")))
