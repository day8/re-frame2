(ns realworld-resources.http
  "HTTP helpers + the demo backend stub for the RealWorld-on-resources
   example.

   Resources and mutations lower onto `:rf.http/managed` (Spec 016 §Transport:
   managed HTTP is the single built-in resource/mutation transport), so the
   SAME canned-stub override the `:rf.http/managed` sibling uses serves both
   reads and writes here. The stub routes by URL + method to a canned
   Conduit-shaped reply, deferred via `:after-ms` (`:dispatch-later`, NOT raw
   `js/setTimeout`) so the runtime's `:loading` UI state is observable and
   replies stay time-travel-safe.

   Two helpers:
   - `failure->message` — project a Spec 014 `:rf.http/*` failure envelope (the
     shape resource `:error` / `:refresh-error` and mutation `:error` carry) to
     a human-readable string.
   - `install-demo-backend!` — register the stub fx + wire it as the
     `:rf.http/managed` override on the demo frame.

   The RealWorld spec lives at https://github.com/gothinkster/realworld.
   Production points `api-base` at https://api.realworld.io/api; the upstream
   spec ships a Node/Postgres reference backend on http://localhost:3000/api."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]))

(def api-base
  "Default API base URL. The resource / mutation `:request` fns build full
   URLs from this; the demo stub matches on the path suffix so the base is a
   demo-seam knob, not load-bearing here."
  "https://api.realworld.io/api")

(defn full-url [path]
  (str api-base path))

;; ============================================================================
;; RETRY POLICY (Spec 014 §Retry and backoff)
;; ============================================================================
;;
;; Reads retry / writes don't (Spec 014). Each read resource's `:request`
;; returns a Spec 014 managed-HTTP args map, and `:retry` passes through the
;; resource lowering UNCHANGED (Spec 016 §Transport), so a resource arms retry
;; simply by including this policy in its return. Writes (mutations) stay
;; retry-free — a write's intent is one submission per click; a 5xx surfaces
;; as `:error` so the user retries themselves.

(def data-fetch-retry
  "Standard retry policy for read-only data fetches (lists, article detail,
   comments, profiles, tags, the feed). Retries transport blips, 5xx, and
   timeouts — NOT 4xx (the request shape was valid; retrying won't help).
   Three attempts total with exponential backoff + jitter."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; ============================================================================
;; FAILURE PROJECTION
;; ============================================================================

(defn failure->message
  "Project a Spec 014 failure envelope (the inner `:failure` map — the shape a
   resource `:error` / `:refresh-error` and a mutation `:error` carry) to a
   human-readable string. The Conduit API returns `{:errors {:body [\"...\"]}}`
   shapes for 4xx validation failures; surface those when present, otherwise
   fall back to a category-driven message keyed on the closed `:rf.http/*`
   taxonomy."
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
;; The realworld example ships without a backend; the demo routes
;; `:rf.http/managed` through a per-URL stub that delegates to
;; `:rf.http/managed-canned-success` (Spec 014 §Testing). The resource /
;; mutation runtime lowers each fetch/write onto `:rf.http/managed`, so this
;; one override stands in for the whole Conduit API.

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply (via the canned-success
   fx's `:after-ms`, dispatched through `:dispatch-later` — observable in the
   tape, time-travel-safe, NOT raw `js/setTimeout`). Small but non-zero so the
   `:loading` / `:pending` UI states are observable. A demo-seam knob, not a
   production value."
  20)

(def ^:private demo-articles
  [{:slug "hello-conduit"
    :title "Hello, Conduit"
    :description "A short greeting from the realworld-resources stub."
    :body "This article is served by the demo :rf.http/managed override that
           resources + mutations lower onto."
    :tagList ["intro" "demo"]
    :createdAt "2026-01-01T00:00:00Z"
    :updatedAt "2026-01-01T00:00:00Z"
    :favorited false
    :favoritesCount 0
    :author {:username "stub-bot" :bio "A friendly stub." :image "" :following false}}
   {:slug "second-article"
    :title "Second article"
    :description "A second short article."
    :body "More canned demo content."
    :tagList ["demo"]
    :createdAt "2026-02-01T00:00:00Z"
    :updatedAt "2026-02-01T00:00:00Z"
    :favorited false
    :favoritesCount 0
    :author {:username "stub-bot" :bio "A friendly stub." :image "" :following false}}])

(def ^:private demo-tags ["intro" "demo" "clojure" "re-frame"])

(def ^:private demo-user
  "Canned User payload for the auth POSTs (/users/login, /users register,
   /user restore) and the settings PUT (/user)."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn- demo-article-by-slug [slug]
  (or (some #(when (= slug (:slug %)) %) demo-articles)
      (first demo-articles)))

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
      ;; --- auth POSTs (the auth machine still issues these via managed HTTP) ---
      (and (= method :post) (str/ends-with? u "/users/login")) {:user demo-user}
      (and (= method :post) (str/ends-with? u "/users"))       {:user demo-user}

      ;; GET /user — session restore. Empty payload → decode fails into the
      ;; failure branch so the app starts unauthenticated (the demo never
      ;; auto-restores). PUT /user — settings update (a mutation).
      (and (= method :get) (str/ends-with? u "/user"))  {}
      (and (= method :put) (str/ends-with? u "/user"))  {:user demo-user}

      ;; --- write mutations -------------------------------------------------
      ;; POST /articles — create-article. Echo a full Article built from the
      ;; submitted body so the editor's save reaction can navigate to its slug.
      (and (= method :post) (str/ends-with? u "/articles"))
      (let [a (some-> req :body :article)]
        {:article (merge (first demo-articles)
                         {:slug (or (some-> (:title a) str/lower-case
                                            (str/replace #"[^a-z0-9]+" "-")
                                            (str/replace #"(^-+|-+$)" ""))
                                    "new-article")
                          :title (:title a) :description (:description a)
                          :body (:body a) :tagList (vec (:tagList a))})})

      ;; PUT /articles/:slug — update-article. Echo the updated Article keyed by
      ;; the URL slug, merged with the submitted body.
      (and (= method :put) (re-find #"/articles/([^/?]+)$" u))
      (let [slug (second (re-find #"/articles/([^/?]+)$" u))
            a    (some-> req :body :article)]
        {:article (merge (demo-article-by-slug slug)
                         {:slug slug :title (:title a) :description (:description a)
                          :body (:body a) :tagList (vec (:tagList a))})})

      ;; DELETE /articles/:slug — delete-article. No body (the delete mutation
      ;; decodes `:auto`, which tolerates 204/empty).
      (and (= method :delete) (re-find #"/articles/([^/?]+)$" u))
      {}

      ;; POST /articles/:slug/comments — the post-comment mutation.
      (and (= method :post) (re-find #"/articles/[^/]+/comments$" u))
      (canned-comment (some-> req :body :comment :body))

      ;; DELETE /articles/:slug/comments/:id — the delete-comment mutation.
      (and (= method :delete) (re-find #"/articles/[^/]+/comments/[^/]+$" u))
      {}

      ;; POST/DELETE /articles/:slug/favorite — favorite / unfavorite. Echo a
      ;; full Article so the mutation's :populates can seed the detail entry.
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
      (str/includes? u "/articles/feed")        {:articles [] :articlesCount 0}
      (re-find #"/articles/[^/]+/comments" u)    {:comments []}
      (re-find #"/articles/[^/?]+$" u)           {:article (demo-article-by-slug
                                                            (second (re-find #"/articles/([^/?]+)" u)))}
      (or (str/ends-with? u "/articles")
          (str/includes? u "/articles?"))        {:articles demo-articles
                                                   :articlesCount (count demo-articles)}
      (str/includes? u "/tags")                  {:tags demo-tags}
      (str/includes? u "/profiles/")             {:profile {:username "stub-bot" :bio ""
                                                            :image "" :following false}}
      :else {})))

(rf/reg-fx :realworld-resources.demo/http-stub
  {:doc       "Demo override for :rf.http/managed: routes by URL + method to
               canned Conduit-shaped responses so the example (reads via
               resources, writes via mutations) runs standalone without a
               backend. Delegates to the framework-shipped
               `:rf.http/managed-canned-success` (Spec 014 §Testing) with the
               per-URL payload + `:after-ms` (the deferred reply rides
               `:dispatch-later` — tape-visible, time-travel-safe, NOT raw
               `js/setTimeout`)."
   :platforms #{:server :client}}
  (fn fx-managed-demo-stub [frame-ctx args-map]
    (let [payload (demo-payload-for-args args-map)
          stub    (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))
