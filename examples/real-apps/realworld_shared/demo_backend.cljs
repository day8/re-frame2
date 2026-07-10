(ns realworld-shared.demo-backend
  "The in-process demo Conduit backend shared by both RealWorld examples
   (`realworld_http/` and `realworld_resources/`).

   Both examples ship without a real server so you can clone-and-run on a plane.
   The way each app runs offline is identical: it overrides `:rf.http/managed`
   with an in-process stub that routes by URL + method to a canned
   Conduit-shaped reply. Since both the managed-HTTP events AND the resource /
   mutation runtime lower every fetch and write onto `:rf.http/managed`, this one
   backend gets to play the entire Conduit API for either architecture — the
   demo backend is transport-neutral, so it lives here, once, instead of drifting
   as two hand-maintained copies.

   ONE canonical corpus, ONE request router, ONE canned adapter:

   - the corpus — a synthesised article set sized so the official 10-per-page
     pagination spans several pages (otherwise there'd be nothing to page
     through), a favorited SUBSET (so a profile's Favorited tab is visibly
     different from My Articles), a tag set, and the demo user;
   - `payload-for-request` — the pure router: an `:rf.http/managed` args-map in,
     a Conduit-shaped payload out (or the `::decode-failure` sentinel for the one
     route the demo deliberately fails);
   - `respond` — the `:rf.http/managed` adapter each app wires under its own
     fx-id. It delegates to the framework-shipped
     `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure` with
     the routed payload plus `:after-ms` — the deferred reply rides
     `:dispatch-later`, so it's tape-visible and time-travel-safe, not a raw
     `js/setTimeout`.

   The reply delay is small but non-zero on purpose: without a beat of delay
   you'd never see the `:loading` / `:pending` states flash by. A demo knob, not
   a production value.

   The RealWorld spec lives at
   https://github.com/gothinkster/realworld/tree/main/api."
  (:require [clojure.string :as str]
            [re-frame.registrar :as registrar]))

(def reply-delay-ms
  "How long the demo backend waits before handing back each canned reply (via
   the canned fx's `:after-ms`, dispatched through `:dispatch-later` — so it's
   visible in the tape and time-travel-safe). Just enough that the `:loading` /
   `:pending` states are actually observable."
  20)

;; ============================================================================
;; THE CANONICAL CORPUS
;; ============================================================================
;;
;; A synthesised article set, sized so the official 10-per-page pagination spans
;; several pages. The first two articles have stable slugs (the article-detail
;; and write paths reference them); the rest are generated. `favorited` is a
;; deliberate subset, so a profile's Favorited-Articles tab looks different from
;; My Articles and an unfavorite visibly drops an article on the next refetch.

(def ^:private article-count 23)

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
    (merge {:slug           (str "article-" i)
            :title          (str "Article " i)
            :description    (str "Synthetic demo article #" i " (pagination set).")
            :body           (str "Canned demo content for article " i ".")
            :tagList        (if (even? i) ["demo" "clojure"] ["demo" "re-frame"])
            :createdAt      "2026-01-01T00:00:00Z"
            :updatedAt      "2026-01-01T00:00:00Z"
            :favorited      false
            :favoritesCount 0
            :author         {:username "stub-bot" :bio "A friendly stub." :image "" :following false}}
           (get stable i))))

(def articles
  "The canonical demo article set (see `article-count`)."
  (mapv gen-article (range article-count)))

(def ^:private favorited
  "The favorited subset — every third article. Backs a profile's Favorited tab,
   and being a subset is the point: it makes that tab visibly different from My
   Articles."
  (vec (take-nth 3 articles)))

(def ^:private tags ["intro" "demo" "clojure" "re-frame"])

(def ^:private user
  "The canned User payload — used for the auth POSTs (/users/login, /users
   register, /user restore) and the settings PUT (/user)."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn- article-by-slug [slug]
  (or (some #(when (= slug (:slug %)) %) articles)
      (first articles)))

(defn- canned-comment [body]
  (let [id (+ 1000 (rand-int 100000))]
    {:comment {:id id
               :createdAt "2026-05-13T00:00:00Z"
               :updatedAt "2026-05-13T00:00:00Z"
               :body (or body "stubbed comment")
               :author {:username "demo" :bio "Canned demo user." :image "" :following false}}}))

;; ============================================================================
;; PAGINATION
;; ============================================================================
;;
;; The Conduit list endpoints page with `limit` / `offset` query params, and the
;; router honours them properly: each page is a genuinely distinct slice of the
;; article set, and `articlesCount` always reports the full total (the pagination
;; control works the page count out from it). That honesty is what makes the
;; paginated-list identity and keep-previous behaviour real in the browser —
;; page N and page N+1 really do return different data under distinct keys.

(defn- query-int [u k]
  (when-let [m (re-find (re-pattern (str "[?&]" k "=([0-9]+)")) u)]
    (js/parseInt (second m) 10)))

(defn- paged-response
  "Slice `all` by the `limit` / `offset` in URL `u` (with no params, the whole
   list), wrapped in the Conduit `{:articles … :articlesCount <full-total>}`
   envelope."
  [u all]
  (let [limit  (or (query-int u "limit") (count all))
        offset (or (query-int u "offset") 0)]
    {:articles      (vec (take limit (drop offset all)))
     :articlesCount (count all)}))

;; ============================================================================
;; THE REQUEST ROUTER
;; ============================================================================

(defn payload-for-request
  "Route an `:rf.http/managed` args-map to a canned Conduit-shaped payload.

   Returns the `::decode-failure` sentinel (not a payload) for the one route the
   demo wants to FAIL — GET /user, session restore. A real backend returning
   `{}` there would fail `:decode schema/UserResponse` and take the auth machine
   down its `:on-failure` path; but `:rf.http/managed-canned-success` never runs
   `:decode`, so an empty-map *success* would instead land on `:on-success` and
   drive the machine to a broken, half-authenticated state. Routing this through
   `:rf.http/managed-canned-failure` (see `respond`) reproduces the real failure
   path — the demo always opens logged-out and never auto-restores a session."
  [args-map]
  (let [req    (:request args-map)
        u      (str (:url req))
        method (or (:method req) :get)]
    (cond
      ;; --- auth POSTs (the auth machine issues these through managed HTTP) ---
      (and (= method :post) (str/ends-with? u "/users/login")) {:user user}
      (and (= method :post) (str/ends-with? u "/users"))       {:user user}

      ;; GET /user — session restore (the one deliberately-failing route, above).
      ;; PUT /user is the settings update.
      (and (= method :get) (str/ends-with? u "/user"))  ::decode-failure
      (and (= method :put) (str/ends-with? u "/user"))  {:user user}

      ;; --- write mutations -------------------------------------------------
      ;; POST /articles — create. Echo back a full Article built from the
      ;; submitted body, so the editor's save reaction has a slug to navigate to.
      (and (= method :post) (str/ends-with? u "/articles"))
      (let [a (some-> req :body :article)]
        {:article (merge (first articles)
                         {:slug (or (some-> (:title a) str/lower-case
                                            (str/replace #"[^a-z0-9]+" "-")
                                            (str/replace #"(^-+|-+$)" ""))
                                    "new-article")
                          :title (:title a) :description (:description a)
                          :body (:body a) :tagList (vec (:tagList a))})})

      ;; PUT /articles/:slug — update. Echo the updated Article, keyed by the URL
      ;; slug and merged with the submitted body.
      (and (= method :put) (re-find #"/articles/([^/?]+)$" u))
      (let [slug (second (re-find #"/articles/([^/?]+)$" u))
            a    (some-> req :body :article)]
        {:article (merge (article-by-slug slug)
                         {:slug slug :title (:title a) :description (:description a)
                          :body (:body a) :tagList (vec (:tagList a))})})

      ;; DELETE /articles/:slug — delete. No body; the delete decodes happily
      ;; from a 204/empty.
      (and (= method :delete) (re-find #"/articles/([^/?]+)$" u))
      {}

      ;; POST /articles/:slug/comments — post a comment.
      (and (= method :post) (re-find #"/articles/[^/]+/comments$" u))
      (canned-comment (some-> req :body :comment :body))

      ;; DELETE /articles/:slug/comments/:id — delete a comment.
      (and (= method :delete) (re-find #"/articles/[^/]+/comments/[^/]+$" u))
      {}

      ;; POST/DELETE /articles/:slug/favorite — favorite / unfavorite. Echo a
      ;; full Article so the write's authoritative reply can seed the detail.
      (re-find #"/articles/([^/]+)/favorite$" u)
      (let [slug (second (re-find #"/articles/([^/]+)/favorite$" u))
            base (article-by-slug slug)]
        {:article (assoc base
                         :favorited (= method :post)
                         :favoritesCount (if (= method :post) 1 0))})

      ;; POST/DELETE /profiles/:username/follow — follow / unfollow.
      (re-find #"/profiles/([^/]+)/follow$" u)
      (let [username (second (re-find #"/profiles/([^/]+)/follow$" u))]
        {:profile {:username username :bio "" :image "" :following (= method :post)}})

      ;; --- reads -----------------------------------------------------------
      ;; The feed is empty in the demo — no followed authors — but it's still a
      ;; well-formed paged Conduit envelope, not a special case.
      (str/includes? u "/articles/feed")        {:articles [] :articlesCount 0}
      (re-find #"/articles/[^/]+/comments" u)    {:comments []}
      (re-find #"/articles/[^/?]+$" u)           {:article (article-by-slug
                                                            (second (re-find #"/articles/([^/?]+)" u)))}
      ;; A profile's Favorited-Articles tab — a distinct subset, so the tab
      ;; differs from My Articles and an unfavorite visibly removes an item.
      (re-find #"[?&]favorited=" u)              (paged-response u favorited)
      ;; Every other list read (global list, tag-filtered, author) pages the full
      ;; set; `limit` / `offset` carve each page into a genuinely different slice.
      (or (str/ends-with? u "/articles")
          (str/includes? u "/articles?"))        (paged-response u articles)
      (str/includes? u "/tags")                  {:tags tags}
      (str/includes? u "/profiles/")             {:profile {:username "stub-bot" :bio ""
                                                            :image "" :following false}}
      :else {})))

;; ============================================================================
;; THE CANNED ADAPTER
;; ============================================================================

(defn respond
  "The demo `:rf.http/managed` adapter. Route the request, then hand the payload
   to the framework's canned fx: `:rf.http/managed-canned-success` for a payload
   (with `:after-ms` so the reply is deferred through `:dispatch-later`), or
   `:rf.http/managed-canned-failure` for the `::decode-failure` sentinel. Each
   app wires this under its own fx-id via `:fx-overrides` — there is no explicit
   install call to make."
  [frame-ctx args-map]
  (let [payload (payload-for-request args-map)]
    (if (= ::decode-failure payload)
      (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args-map
                               :after-ms reply-delay-ms
                               :kind     :rf.http/decode-failure
                               :tags     {:schema-validation-failure? true})))
      (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx (assoc args-map :after-ms reply-delay-ms :value payload))))))
