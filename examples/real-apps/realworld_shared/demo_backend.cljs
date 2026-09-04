(ns realworld-shared.demo-backend
  "The in-process demo Conduit backend shared by both RealWorld examples
   (`realworld_http/` and `realworld_resources/`).

   Both examples ship without a real server so you can clone-and-run on a plane.
   The way each app runs offline is identical: it overrides `:rf.http/managed`
   with an in-process stub that hands the request to this backend. Since both
   the managed-HTTP events AND the resource / mutation runtime lower every fetch
   and write onto `:rf.http/managed`, this one backend gets to play the entire
   Conduit API for either architecture — it is transport-neutral, so it lives
   here, once, instead of drifting as two hand-maintained copies.

   IT REMEMBERS WHAT YOU WRITE. This is a small STATEFUL model, not a lookup
   table: a seeded state value plus ONE pure transition,

       (transition state args-map) -> [next-state reply]

   so a write lands in the state the next read consults. Favourite an article
   and the refetch it triggers still says favourited; post a comment and the
   refetch its invalidation causes comes back with your comment in it. That
   matters most for the resources example, whose entire headline is
   read -> write -> invalidate -> refetch: a backend that answered the refetch
   out of a frozen seed corpus would erase every write the moment it succeeded,
   and the example would be busy demonstrating the opposite of its own claim.

   Four pieces, and nothing else:

   - THE SEED — `seed-articles` plus the seeded comments, favourites and demo
     user. Immutable data. Nothing reads it after boot; it is the value
     `fresh-state` starts from, never a fallback a read quietly consults.
   - `fresh-state` — a brand-new backend world. This is the reset boundary: a
     launching app, a fresh mount and a test each take their own, so nobody
     inherits anybody else's writes.
   - `transition` — the pure router: current state + an `:rf.http/managed`
     args-map in, `[next-state reply]` out, where the reply is `{:ok payload}`
     or `{:failure {:kind … :tags …}}`.
   - `respond` — the `:rf.http/managed` adapter each app wires under its own
     fx-id, holding its own `defonce`d state atom. It delegates to the
     framework-shipped `:rf.http/managed-canned-success` /
     `:rf.http/managed-canned-failure` with `:after-ms`, so the deferred reply
     rides `:dispatch-later` — tape-visible and time-travel-safe, never a raw
     `js/setTimeout`.

   THE SIMPLIFICATIONS, stated rather than hidden. The demo world has exactly
   ONE user, so `favorited` and `following` are that user's flags no matter who
   is asking, and any login succeeds as them. Every generated value is
   deterministic — comment ids come off a counter and timestamps off a fixed
   epoch plus a tick — so an identical request sequence against a fresh state
   produces an identical reply sequence, in either app. Nothing persists across
   a browser reload: the state lives in the page, so a reload is a fresh world,
   which is exactly why the demo always opens logged out.

   The reply delay is small but non-zero on purpose: without a beat of delay
   you'd never see the `:loading` / `:pending` states flash by. A demo knob, not
   a production value.

   The RealWorld spec lives at
   https://github.com/gothinkster/realworld/tree/main/api."
  (:require [clojure.string :as str]
            [re-frame.registrar :as rf.registrar]))

(def reply-delay-ms
  "How long the demo backend waits before handing back each canned reply (via
   the canned fx's `:after-ms`, dispatched through `:dispatch-later` — so it's
   visible in the tape and time-travel-safe). Just enough that the `:loading` /
   `:pending` states are actually observable."
  20)

;; ============================================================================
;; DETERMINISTIC TIME
;; ============================================================================
;;
;; No `js/Date.now`, no `rand-int`. Timestamps come off a fixed epoch plus the
;; state's own `:tick`, which every write bumps — so the Nth write in a sequence
;; always carries the same instant, and two runs of the same sequence are
;; byte-identical. That is what lets a test assert on a saved comment's id and
;; `createdAt` instead of merely on its shape.

(def ^:private epoch-ms
  "2026-01-01T00:00:00.000Z, in milliseconds. The demo world's origin."
  1767225600000)

(defn- stamp
  "The ISO-8601 instant `tick` writes after the epoch (one second apiece)."
  [tick]
  (.toISOString (js/Date. (+ epoch-ms (* 1000 tick)))))

(defn- seed-stamp
  "The seed corpus reads backwards from the epoch, a day per article, so the
   corpus is genuinely newest-first and article 0 is the most recent."
  [i]
  (.toISOString (js/Date. (- epoch-ms (* 86400000 i)))))

;; ============================================================================
;; THE SEED
;; ============================================================================
;;
;; A synthesised article set, sized so the official 10-per-page pagination spans
;; several pages. The first two articles have stable slugs (the article-detail
;; and write paths reference them); the rest are generated. Articles are held
;; NEWEST FIRST, which is both what Conduit returns and what makes a freshly
;; created article show up at the top of page 1.
;;
;; A stored article is not quite the wire shape: it holds its author as a bare
;; username, and carries no `favorited` / `favoritesCount` at all. Both of those
;; are properties of the CURRENT state rather than of the article, so they are
;; computed on the way out by `article-wire` — which is precisely why a
;; favourite survives the refetch it causes.

(def ^:private article-count 23)

(def ^:private stub-author-username "stub-bot")

(def ^:private known-authors
  "Bios / avatars for the corpus authors who are not the demo user."
  {"stub-bot" {:bio "A friendly stub." :image ""}})

(defn- gen-article [i]
  (let [stable [{:slug "hello-conduit"
                 :title "Hello, Conduit"
                 :description "A short greeting from the shared demo backend."
                 ;; A markdown body, so the article-detail page (in either app)
                 ;; exercises the sanitized CommonMark renderer
                 ;; (realworld-shared.markdown/render): headings, bold/italic,
                 ;; inline and fenced code, a safe link, lists, a nested list, a
                 ;; table, a blockquote. The renderer emits hiccup, never raw
                 ;; HTML — so this is genuine markup, while any injected
                 ;; `<script>` or `javascript:` link in user content degrades
                 ;; harmlessly to inert escaped text. Prose is architecture-
                 ;; neutral: this corpus is shared by both RealWorld examples.
                 :body (str "# Hello, Conduit\n\n"
                            "This article is served by the demo `:rf.http/managed` "
                            "backend both RealWorld examples share, rendered as "
                            "**markdown** with *emphasis*.\n\n"
                            "See the [RealWorld spec](https://github.com/gothinkster/realworld) "
                            "for the reference behaviour.\n\n"
                            "## Highlights\n\n"
                            "- Sanitized by construction (hiccup, never raw HTML)\n"
                            "- Full CommonMark via `nextjournal/markdown`\n"
                            "  - tables, nested lists, images\n"
                            "  - safe-by-construction link/image schemes\n\n"
                            "| Feature | Status |\n"
                            "| --- | --- |\n"
                            "| markdown | CommonMark |\n"
                            "| links | scheme-allowlisted |\n\n"
                            "```clojure\n(rf/reg-event :hello (fn [{:keys [db]} _] {:db db}))\n```\n\n"
                            "> A blockquote, for good measure.")
                 :tagList ["intro" "demo"]}
                {:slug "second-article"
                 :title "Second article"
                 :description "A second short article."
                 :body "More canned demo content."
                 :tagList ["demo"]}]]
    (merge {:slug        (str "article-" i)
            :title       (str "Article " i)
            :description (str "Synthetic demo article #" i " (pagination set).")
            :body        (str "Canned demo content for article " i ".")
            :tagList     (if (even? i) ["demo" "clojure"] ["demo" "re-frame"])
            :createdAt   (seed-stamp i)
            :updatedAt   (seed-stamp i)
            :author      stub-author-username}
           (get stable i))))

(def seed-articles
  "The seeded demo article set, newest first (see `article-count`). This is
   `fresh-state`'s starting value and nothing else — no read falls back to it,
   which is what a mutate-then-read test pins."
  (mapv gen-article (range article-count)))

(def ^:private seed-favorites
  "The slugs the demo user has already favourited — every third article. Being a
   SUBSET is the point: it makes their Favorited tab visibly different from My
   Articles, and an unfavourite visibly drops an article from it."
  (into #{} (map :slug) (take-nth 3 seed-articles)))

(def ^:private seed-comments
  "One seeded comment, so an article-detail page has something to render before
   you write anything. Authored by someone else, so the demo user gets no delete
   control over it — deleting is what your OWN comment is for."
  {"hello-conduit" [{:id        1
                     :body      "Nice to see this running with no server at all."
                     :author    stub-author-username
                     :createdAt (seed-stamp 0)
                     :updatedAt (seed-stamp 0)}]})

(def ^:private seed-user
  "The demo world's one user. `:token` is the credential `POST /users/login`
   issues and `GET /user` checks."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn fresh-state
  "A brand-new demo world, seeded and with nothing written to it yet.

   THIS IS THE RESET BOUNDARY. Each app's `:rf.http/managed` override holds its
   own `defonce`d atom of one of these, so the two demos never share a world;
   a test that wants a clean slate takes a fresh one rather than undoing writes,
   and a fresh mount can reset by `(reset! the-atom (fresh-state))`."
  []
  {:articles        seed-articles
   :comments        seed-comments
   :favorites       seed-favorites
   :following       #{}
   :user            seed-user
   ;; Tokens this world has issued. Empty at boot — which is why a cold start
   ;; never auto-restores a session.
   :sessions        #{}
   :next-comment-id 1000
   :tick            0})

;; ============================================================================
;; READING THE REQUEST
;; ============================================================================

(defn- path-of
  "The path half of `url` — everything before the `?`."
  [url]
  (first (str/split url #"\?" 2)))

(defn- query-params
  "The query half of `url`, decoded into a keyword-keyed map. Values arrive
   `js/encodeURIComponent`-encoded (realworld-shared.http/query-string), so a
   tag or username carrying a reserved character round-trips intact."
  [url]
  (if-let [q (second (str/split url #"\?" 2))]
    (into {}
          (for [pair (str/split q #"&")
                :when (seq pair)]
            (let [[k v] (str/split pair #"=" 2)]
              [(keyword (js/decodeURIComponent k))
               (js/decodeURIComponent (or v ""))])))
    {}))

(defn- query-int [params k]
  (when-let [v (get params k)]
    (let [n (js/parseInt v 10)]
      (when-not (js/isNaN n) n))))

(defn- bearer-token
  "The token on the request's `Authorization: Token <jwt>` header, if there is
   one. The frame-wide bearer interceptor attaches that header on its way to the
   transport; a request that never had a session has no header at all."
  [req]
  (let [header (or (get-in req [:headers "Authorization"])
                   (get-in req [:headers :Authorization]))]
    (some-> header (str/replace #"^(Token|Bearer)\s+" "") not-empty)))

;; ============================================================================
;; REPLIES
;; ============================================================================
;;
;; A transition returns `[next-state reply]`, and the reply is one of two
;; shapes. `respond` is the only thing that reads them.

(defn- ok [payload] {:ok payload})

(defn- not-found
  "An explicit Conduit-shaped 404. A missing article or comment FAILS — it never
   resolves to a plausible-looking first record, because a demo that answers
   `/article/does-not-exist` with somebody else's article is lying to you at
   exactly the moment you most need the truth."
  [what]
  {:failure {:kind :rf.http/http-4xx
             :tags {:status 404
                    :body   {:errors {:body [(str what " not found.")]}}}}})

(def ^:private restore-failure
  "The reply to a `GET /user` with no valid credential.

   It is a `:rf.http/decode-failure` rather than a 401 on purpose. A real
   backend refusing session restore returns a body that fails
   `:decode schema/UserResponse`, taking the auth machine down its `:on-failure`
   path — but `:rf.http/managed-canned-success` never runs `:decode`, so an
   empty-map SUCCESS would instead land on `:on-success` and drive the machine
   into a broken, half-authenticated state. Routing through
   `:rf.http/managed-canned-failure` reproduces the real failure path."
  {:failure {:kind :rf.http/decode-failure
             :tags {:schema-validation-failure? true}}})

;; ============================================================================
;; PROJECTING STATE ONTO THE WIRE
;; ============================================================================
;;
;; `favorited`, `favoritesCount` and `following` are facts about the CURRENT
;; world, not fields frozen onto a stored record — so every read computes them
;; from the state it was handed. That is the whole mechanism by which a write
;; survives the refetch it causes.

(defn- profile-wire [state username]
  (let [me (:user state)]
    (if (= username (:username me))
      ;; You cannot follow yourself.
      {:username  username
       :bio       (or (:bio me) "")
       :image     (or (:image me) "")
       :following false}
      (let [{:keys [bio image]} (get known-authors username)]
        {:username  username
         :bio       (or bio "")
         :image     (or image "")
         :following (contains? (:following state) username)}))))

(defn- article-wire [state article]
  (let [favorited? (contains? (:favorites state) (:slug article))]
    (-> article
        (assoc :author (profile-wire state (:author article)))
        (assoc :favorited favorited?)
        ;; One user in this world, so the count is that user's flag.
        (assoc :favoritesCount (if favorited? 1 0)))))

(defn- comment-wire [state cmt]
  (-> cmt
      (assoc :author (profile-wire state (:author cmt)))))

(defn- find-article [state slug]
  (some #(when (= slug (:slug %)) %) (:articles state)))

(defn- paged
  "Slice `all` by `limit` / `offset`, wrapped in the Conduit
   `{:articles … :articlesCount <total-matching>}` envelope. `articlesCount` is
   the size of the FILTERED set before the window — the page count comes off it,
   so it must describe what the query matched, not the corpus and not the page."
  [state params all]
  (let [limit  (or (query-int params :limit) (count all))
        offset (or (query-int params :offset) 0)]
    {:articles      (mapv #(article-wire state %) (take limit (drop offset all)))
     :articlesCount (count all)}))

(defn- matching-articles
  "The current articles matching the list query — `tag`, `author` and
   `favorited`, applied to CURRENT state. `favorited=<user>` only matches when
   that user is the demo world's one user; nobody else has favourites."
  [state {:keys [tag author favorited]}]
  (let [favorited-by-me? (= favorited (:username (:user state)))]
    (cond->> (:articles state)
      tag       (filterv #(some #{tag} (:tagList %)))
      author    (filterv #(= author (:author %)))
      favorited (filterv #(and favorited-by-me?
                               (contains? (:favorites state) (:slug %)))))))

(defn- slugify [title]
  (or (some-> title
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"(^-+|-+$)" "")
              not-empty)
      "untitled"))

(defn- free-slug
  "`base`, or `base-2` / `base-3` / … if that slug is already taken. Saving the
   same title twice gives you two articles rather than a 422, which is friendlier
   for a demo, and the suffix is deterministic."
  [state base]
  (if-not (find-article state base)
    base
    (loop [n 2]
      (let [candidate (str base "-" n)]
        (if (find-article state candidate)
          (recur (inc n))
          candidate)))))

(defn- rename-author
  "Rewrite the byline on everything `old` wrote. Conduit lets you change your
   username in settings, and a demo whose own articles then show a stranger's
   name would be a worse lie than the one this backend exists to fix."
  [state old new]
  (if (= old new)
    state
    (-> state
        (update :articles (partial mapv #(cond-> % (= old (:author %)) (assoc :author new))))
        (update :comments
                (fn [by-slug]
                  (reduce-kv (fn [m slug cs]
                               (assoc m slug (mapv #(cond-> % (= old (:author %)) (assoc :author new)) cs)))
                             {} by-slug))))))

;; ============================================================================
;; THE TRANSITION
;; ============================================================================

(defn transition
  "The one canonical demo-backend step: current `state` plus an
   `:rf.http/managed` args-map in, `[next-state reply]` out.

   Pure — no atoms, no clock, no randomness — so a test can walk a sequence of
   requests through it and assert on both halves, and both apps get identical
   replies from identical sequences because they call THIS.

   A read returns `[state …]` unchanged; a write returns the state its own
   effect is already visible in, so the very next read sees it."
  [state args-map]
  (let [req    (:request args-map)
        url    (str (:url req))
        path   (path-of url)
        params (query-params url)
        method (or (:method req) :get)
        body   (:body req)
        me     (:user state)]
    (cond
      ;; --- auth ------------------------------------------------------------
      ;; One user in this world, so any credentials log you in as them. Login
      ;; and register both issue the demo token, which is what `GET /user`
      ;; checks below.
      (and (= method :post) (or (str/ends-with? path "/users/login")
                                (str/ends-with? path "/users")))
      [(update state :sessions conj (:token me)) (ok {:user me})]

      ;; GET /user — session restore. Succeeds only for a token THIS world
      ;; issued. A cold boot has issued none (`fresh-state`'s `:sessions` is
      ;; empty) and the saved JWT belongs to a previous page's world, so restore
      ;; fails and the demo opens logged out — predictably, and for a reason the
      ;; model states rather than a special case that hardcodes it.
      (and (= method :get) (str/ends-with? path "/user"))
      (if (contains? (:sessions state) (bearer-token req))
        [state (ok {:user me})]
        [state restore-failure])

      ;; PUT /user — settings. The submitted identity fields win; `:password` is
      ;; ignored (this world has no password to change).
      (and (= method :put) (str/ends-with? path "/user"))
      (let [submitted (:user body)
            changed   (into {} (remove (comp nil? val))
                            (select-keys submitted [:email :username :bio :image]))
            next-user (merge me changed)]
        [(-> state
             (rename-author (:username me) (:username next-user))
             (assoc :user next-user))
         (ok {:user next-user})])

      ;; --- article lists ---------------------------------------------------
      ;; The feed is the articles by the authors you follow — empty until you
      ;; follow someone, and genuinely populated once you do.
      (and (= method :get) (str/ends-with? path "/articles/feed"))
      [state (ok (paged state params
                        (filterv #(contains? (:following state) (:author %))
                                 (:articles state))))]

      (and (= method :get) (str/ends-with? path "/articles"))
      [state (ok (paged state params (matching-articles state params)))]

      ;; POST /articles — create. The article is INSERTED, at the front, so the
      ;; navigation the editor performs on save finds it and page 1 shows it.
      (and (= method :post) (str/ends-with? path "/articles"))
      (let [a       (:article body)
            state'  (update state :tick inc)
            now     (stamp (:tick state'))
            article {:slug        (free-slug state (slugify (:title a)))
                     :title       (or (:title a) "")
                     :description (or (:description a) "")
                     :body        (or (:body a) "")
                     :tagList     (vec (:tagList a))
                     :createdAt   now
                     :updatedAt   now
                     :author      (:username me)}
            state'' (update state' :articles #(into [article] %))]
        [state'' (ok {:article (article-wire state'' article)})])

      ;; --- one article -----------------------------------------------------
      (re-find #"/articles/([^/]+)/comments/([^/]+)$" path)
      (let [[_ slug id] (re-find #"/articles/([^/]+)/comments/([^/]+)$" path)
            existing    (get-in state [:comments slug])]
        (cond
          (not (find-article state slug))               [state (not-found "Article")]
          (not (some #(= id (str (:id %))) existing))   [state (not-found "Comment")]
          :else
          [(assoc-in state [:comments slug]
                     (filterv #(not= id (str (:id %))) existing))
           (ok {})]))

      (re-find #"/articles/([^/]+)/comments$" path)
      (let [slug (second (re-find #"/articles/([^/]+)/comments$" path))]
        (cond
          (not (find-article state slug))
          [state (not-found "Article")]

          (= method :post)
          (let [state' (update state :tick inc)
                now    (stamp (:tick state'))
                cmt    {:id        (:next-comment-id state')
                        :body      (or (some-> body :comment :body) "")
                        :author    (:username me)
                        :createdAt now
                        :updatedAt now}
                state'' (-> state'
                            (update :next-comment-id inc)
                            (update-in [:comments slug] (fnil conj []) cmt))]
            [state'' (ok {:comment (comment-wire state'' cmt)})])

          :else
          [state (ok {:comments (mapv #(comment-wire state %)
                                      (get-in state [:comments slug] []))})]))

      (re-find #"/articles/([^/]+)/favorite$" path)
      (let [slug (second (re-find #"/articles/([^/]+)/favorite$" path))]
        (if-not (find-article state slug)
          [state (not-found "Article")]
          (let [state' (update state :favorites (if (= method :post) conj disj) slug)]
            [state' (ok {:article (article-wire state' (find-article state' slug))})])))

      (re-find #"/articles/([^/]+)$" path)
      (let [slug (second (re-find #"/articles/([^/]+)$" path))
            a    (find-article state slug)]
        (cond
          (nil? a) [state (not-found "Article")]

          ;; PUT — update in place. The slug stays put so the URL you are
          ;; already on keeps working.
          (= method :put)
          (let [submitted (:article body)
                state'    (update state :tick inc)
                updated   (merge a
                                 (into {} (remove (comp nil? val))
                                       (select-keys submitted [:title :description :body]))
                                 (when (contains? submitted :tagList)
                                   {:tagList (vec (:tagList submitted))})
                                 {:updatedAt (stamp (:tick state'))})
                state''   (update state' :articles
                                  (partial mapv #(if (= slug (:slug %)) updated %)))]
            [state'' (ok {:article (article-wire state'' updated)})])

          ;; DELETE — the article and everything hanging off it.
          (= method :delete)
          [(-> state
               (update :articles (partial filterv #(not= slug (:slug %))))
               (update :comments dissoc slug)
               (update :favorites disj slug))
           (ok {})]

          :else [state (ok {:article (article-wire state a)})]))

      ;; --- profiles --------------------------------------------------------
      (re-find #"/profiles/([^/]+)/follow$" path)
      (let [username (second (re-find #"/profiles/([^/]+)/follow$" path))
            state'   (update state :following (if (= method :post) conj disj) username)]
        [state' (ok {:profile (profile-wire state' username)})])

      (re-find #"/profiles/([^/]+)$" path)
      (let [username (second (re-find #"/profiles/([^/]+)$" path))]
        [state (ok {:profile (profile-wire state username)})])

      ;; --- tags ------------------------------------------------------------
      ;; Derived from the articles that are actually there, so a new article's
      ;; tags join the sidebar and a deleted article's leave it.
      (and (= method :get) (str/ends-with? path "/tags"))
      [state (ok {:tags (into [] (distinct) (mapcat :tagList (:articles state)))})]

      ;; A route this demo does not implement fails loudly rather than handing
      ;; back an empty map that some caller will mistake for data.
      :else
      [state {:failure {:kind :rf.http/http-4xx
                        :tags {:status 404
                               :body   {:errors {:body [(str "No demo route for "
                                                             (name method) " " path)]}}}}}])))

;; ============================================================================
;; THE CANNED ADAPTER
;; ============================================================================

(defn respond
  "The demo `:rf.http/managed` adapter. Step the app's own backend state, then
   hand the reply to the framework's canned fx:
   `:rf.http/managed-canned-success` for an `{:ok …}`,
   `:rf.http/managed-canned-failure` for a `{:failure …}` — both with
   `:after-ms`, so the reply is deferred through `:dispatch-later` rather than a
   raw timer.

   `state-atom` is the app's OWN world (a `defonce` beside its override fx), so
   the two demos never share one. The swap is a plain read-transition-write:
   ClojureScript is single-threaded, and keeping it explicit is what lets you
   read the transition's two return values without a retry loop in the way."
  [state-atom frame-ctx args-map]
  (let [[next-state reply] (transition @state-atom args-map)]
    (reset! state-atom next-state)
    (if-let [failure (:failure reply)]
      (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args-map
                               :after-ms reply-delay-ms
                               :kind     (:kind failure)
                               :tags     (:tags failure))))
      (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx (assoc args-map :after-ms reply-delay-ms :value (:ok reply)))))))
