(ns realworld-resources.http
  "HTTP helpers and the demo backend stub for the RealWorld-on-resources example.

   Because resources and mutations both lower onto `:rf.http/managed` — the one
   built-in transport for either (../../../docs/resources/glossary.md#managed-http)
   — a single canned-stub override can stand in for the entire API, reads and
   writes alike. The stub routes by URL + method to a canned Conduit-shaped reply,
   deferred by `:after-ms` (which rides `:dispatch-later`, not a raw
   `js/setTimeout`) so the runtime's `:loading` state is actually observable and
   the replies stay time-travel-safe.

   Two helpers live here:
   - `failure->message` — turn a `:rf.http/*` failure envelope (the shape a
     resource `:error` / `:refresh-error` and a mutation `:error` carry) into a
     human-readable string.
   - `install-demo-backend!` — register the stub fx and wire it as the
     `:rf.http/managed` override on the demo frame.

   Three ways to point the app at a backend (see the README §Running against a
   real backend):
   - canned demo stub (the default) — `core.cljs` overrides `:rf.http/managed`
     with an in-process per-URL stub, so the app runs with no network at all and
     `api-base` is never actually contacted (the stub matches on the path suffix).
   - the official hosted API — point `api-base` at `https://api.realworld.show/api`
     and drop the demo-stub override. The frame-wide `:realworld/bearer-auth`
     interceptor (core.cljs) already attaches the token, so authenticated calls
     just work against the real API.
   - a local reference backend — the upstream Node/Postgres backend on
     `http://localhost:3000/api`.

   For reference: the RealWorld spec lives at
   https://github.com/gothinkster/realworld, the current official hosted API is
   https://api.realworld.show/api, and the upstream spec ships a Node/Postgres
   reference backend on http://localhost:3000/api."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]))

(def api-base
  "Default API base URL — the current official hosted Conduit API. The resource
   and mutation `:request` fns build full URLs from this. In the default run mode
   it's effectively a knob you never turn: the demo stub overrides
   `:rf.http/managed` and matches on the path suffix, so the base is never
   contacted. Drop the stub to run against the real hosted API, or set this to
   `http://localhost:3000/api` for the local upstream reference backend. See the
   README §Running against a real backend."
  "https://api.realworld.show/api")

(defn full-url [path]
  (str api-base path))

;; ============================================================================
;; RETRY POLICY
;; ============================================================================
;;
;; Same rule as ever: reads retry, writes don't. A read resource's `:request`
;; returns a managed-HTTP args map, and `:retry` passes through the resource
;; lowering untouched — so a resource opts into retry simply by dropping this
;; policy into its return. Mutations stay retry-free, because a write means one
;; submission per click; if it 5xxes, that surfaces as `:error` and the user gets
;; to decide whether to try again.

(def data-fetch-retry
  "The standard retry policy for read-only data fetches — lists, article detail,
   comments, profiles, tags, the feed. It retries transport blips, 5xx, and
   timeouts, but not 4xx: the request shape was valid, so retrying would just be
   wishful thinking. Three attempts total, with exponential backoff + jitter."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; ============================================================================
;; FAILURE PROJECTION
;; ============================================================================

(defn failure->message
  "Turn a failure envelope — the inner `:failure` map a resource `:error` /
   `:refresh-error` and a mutation `:error` carry — into something a human can
   read. The Conduit API hands back `{:errors {:body [\"...\"]}}` shapes for 4xx
   validation failures, so prefer those when they're present; otherwise fall back
   to a category-driven message keyed off the closed `:rf.http/*` taxonomy."
  [failure]
  (let [body     (:body failure)
        body-msg (cond
                   (and (map? body) (-> body :errors :body first)) (-> body :errors :body first)
                   (and (map? body) (-> body :errors first))       (let [[k v] (first (:errors body))]
                                                                     (str (name k) ": " (first v)))
                   (string? body) body
                   :else nil)]
    (or body-msg
        (case (:kind failure)
          :rf.http/transport      "Network error — please try again."
          :rf.http/timeout        "Request timed out."
          :rf.http/http-4xx       (str "Request rejected (status " (:status failure) ").")
          :rf.http/http-5xx       (str "Server error (status " (:status failure) ").")
          :rf.http/decode-failure "Couldn't parse server response."
          :rf.http/accept-failure (or (-> failure :detail :message) "Unexpected response shape.")
          :rf.http/aborted        "Request cancelled."
          (:message failure))
        "Request failed.")))

;; ============================================================================
;; DEMO BACKEND STUB
;; ============================================================================
;;
;; The example ships without a backend, so the demo routes `:rf.http/managed`
;; through a per-URL stub that delegates to `:rf.http/managed-canned-success`.
;; Since the resource / mutation runtime lowers every fetch and write onto
;; `:rf.http/managed`, this one override gets to play the entire Conduit API.

(def ^:private demo-reply-delay-ms
  "How long the demo stub waits before handing back each canned reply (via the
   canned-success fx's `:after-ms`, dispatched through `:dispatch-later` — so it's
   visible in the tape and time-travel-safe, not a raw `js/setTimeout`). Small but
   non-zero, just enough that the `:loading` / `:pending` states are actually
   visible. A demo knob, not a production value."
  20)

;; A synthesised article set, sized so the official 10-per-page pagination spans
;; several pages — otherwise there'd be nothing to page through, and the
;; paginated-resource + keep-previous behaviour would be impossible to see in the
;; browser. The first two articles have stable slugs (the article-detail and
;; mutation paths reference them); the rest are generated. `demo-favorited` is a
;; deliberate subset, so the profile's Favorited-Articles tab looks different from
;; My Articles and an unfavorite visibly drops an article on the next refetch.
(def ^:private demo-article-count 23)

(defn- gen-article [i]
  (let [stable [{:slug "hello-conduit"
                 :title "Hello, Conduit"
                 :description "A short greeting from the realworld-resources stub."
                 ;; A markdown body, so the article-detail page gets to exercise
                 ;; the sanitized CommonMark renderer (realworld-shared.markdown/
                 ;; render): headings, bold/italic, inline and fenced code, a safe
                 ;; link, lists, tables, nested lists. The renderer emits hiccup,
                 ;; never raw HTML — so this is genuine markup, while any injected
                 ;; `<script>` or `javascript:` link in user content degrades
                 ;; harmlessly to inert escaped text.
                 :body (str "# Hello, Conduit\n\n"
                            "This article is served by the demo `:rf.http/managed` "
                            "override that **resources + mutations** lower onto, "
                            "rendered as *markdown*.\n\n"
                            "See the [RealWorld spec](https://github.com/gothinkster/realworld) "
                            "for the reference behaviour.\n\n"
                            "## Highlights\n\n"
                            "- Sanitized by construction (hiccup, never raw HTML)\n"
                            "- Full CommonMark via `nextjournal/markdown`\n"
                            "  - tables, nested lists, images\n"
                            "  - safe-by-construction link/image schemes\n\n"
                            "| Surface | Shape |\n"
                            "| --- | --- |\n"
                            "| reads | `reg-resource` |\n"
                            "| writes | `reg-mutation` |\n\n"
                            "```clojure\n(rf/reg-resource :realworld/article ...)\n```\n\n"
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

(def ^:private demo-articles
  (mapv gen-article (range demo-article-count)))

(def ^:private demo-favorited
  "The favorited subset — every third article. Backs the profile's Favorited tab,
   and being a subset is the point: it makes that tab visibly different from My
   Articles."
  (vec (take-nth 3 demo-articles)))

(def ^:private demo-tags ["intro" "demo" "clojure" "re-frame"])

(def ^:private demo-user
  "The canned User payload — used for the auth POSTs (/users/login, /users
   register, /user restore) and the settings PUT (/user)."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn- demo-article-by-slug [slug]
  (or (some #(when (= slug (:slug %)) %) demo-articles)
      (first demo-articles)))

;; --- pagination ---
;; The Conduit list endpoints page with `limit` / `offset` query params, and the
;; stub honours them properly: each page is a genuinely distinct slice of the
;; article set, and `articlesCount` always reports the full total (the pagination
;; control works the page count out from it). That honesty is what makes the
;; paginated-resource identity and keep-previous behaviour real in the browser —
;; page N and page N+1 really do return different data under distinct cache keys.

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

(defn- canned-comment [body]
  (let [id (+ 1000 (rand-int 100000))]
    {:comment {:id id
               :createdAt "2026-05-13T00:00:00Z"
               :updatedAt "2026-05-13T00:00:00Z"
               :body (or body "stubbed comment")
               :author {:username "demo" :bio "Canned demo user." :image "" :following false}}}))

(defn- demo-payload-for-args [args-map]
  (let [req    (:request args-map)
        u      (str (:url req))
        method (or (:method req) :get)]
    (cond
      ;; --- auth POSTs (the auth machine issues these through managed HTTP) ---
      (and (= method :post) (str/ends-with? u "/users/login")) {:user demo-user}
      (and (= method :post) (str/ends-with? u "/users"))       {:user demo-user}

      ;; GET /user — session restore. We return an empty payload on purpose: the
      ;; decode fails into the failure branch, so the demo always starts
      ;; unauthenticated and never auto-restores a session. PUT /user is the
      ;; settings update (a mutation).
      (and (= method :get) (str/ends-with? u "/user"))  {}
      (and (= method :put) (str/ends-with? u "/user"))  {:user demo-user}

      ;; --- write mutations -------------------------------------------------
      ;; POST /articles — create. Echo back a full Article built from the submitted
      ;; body, so the editor's save reaction has a slug to navigate to.
      (and (= method :post) (str/ends-with? u "/articles"))
      (let [a (some-> req :body :article)]
        {:article (merge (first demo-articles)
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
        {:article (merge (demo-article-by-slug slug)
                         {:slug slug :title (:title a) :description (:description a)
                          :body (:body a) :tagList (vec (:tagList a))})})

      ;; DELETE /articles/:slug — delete. No body; the delete mutation decodes
      ;; `:auto`, which is happy with a 204/empty.
      (and (= method :delete) (re-find #"/articles/([^/?]+)$" u))
      {}

      ;; POST /articles/:slug/comments — the post-comment mutation.
      (and (= method :post) (re-find #"/articles/[^/]+/comments$" u))
      (canned-comment (some-> req :body :comment :body))

      ;; DELETE /articles/:slug/comments/:id — the delete-comment mutation.
      (and (= method :delete) (re-find #"/articles/[^/]+/comments/[^/]+$" u))
      {}

      ;; POST/DELETE /articles/:slug/favorite — favorite / unfavorite. Echo a full
      ;; Article so the mutation's :populates can seed the detail entry.
      (re-find #"/articles/([^/]+)/favorite$" u)
      (let [slug (second (re-find #"/articles/([^/]+)/favorite$" u))
            base (demo-article-by-slug slug)]
        {:article (assoc base
                         :favorited (= method :post)
                         :favoritesCount (if (= method :post) 1 0))})

      ;; POST/DELETE /profiles/:username/follow — follow / unfollow.
      (re-find #"/profiles/([^/]+)/follow$" u)
      (let [username (second (re-find #"/profiles/([^/]+)/follow$" u))]
        {:profile {:username username :bio "" :image "" :following (= method :post)}})

      ;; --- resource reads --------------------------------------------------
      ;; The feed is empty in the demo — no followed authors — but it's still a
      ;; well-formed paged Conduit envelope, not a special case.
      (str/includes? u "/articles/feed")        {:articles [] :articlesCount 0}
      (re-find #"/articles/[^/]+/comments" u)    {:comments []}
      (re-find #"/articles/[^/?]+$" u)           {:article (demo-article-by-slug
                                                            (second (re-find #"/articles/([^/?]+)" u)))}
      ;; The profile Favorited-Articles tab — a distinct subset, so the tab differs
      ;; from My Articles and an unfavorite visibly removes an item.
      (re-find #"[?&]favorited=" u)              (paged-response u demo-favorited)
      ;; Every other list read (global list, tag-filtered, author) pages the full
      ;; set; `limit` / `offset` carve each page into a genuinely different slice.
      (or (str/ends-with? u "/articles")
          (str/includes? u "/articles?"))        (paged-response u demo-articles)
      (str/includes? u "/tags")                  {:tags demo-tags}
      (str/includes? u "/profiles/")             {:profile {:username "stub-bot" :bio ""
                                                            :image "" :following false}}
      :else {})))

(rf/reg-fx :realworld-resources.demo/http-stub
  {:doc       "The demo override for :rf.http/managed: routes by URL + method to
               canned Conduit-shaped responses, so the example (reads via
               resources, writes via mutations) runs standalone with no backend.
               It delegates to the framework-shipped
               `:rf.http/managed-canned-success` with the per-URL payload plus
               `:after-ms` — the deferred reply rides `:dispatch-later`, so it's
               tape-visible and time-travel-safe, not a raw `js/setTimeout`."
   :platforms #{:server :client}}
  (fn fx-managed-demo-stub [frame-ctx args-map]
    (let [payload (demo-payload-for-args args-map)
          stub    (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))
