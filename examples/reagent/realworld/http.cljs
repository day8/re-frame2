(ns realworld.http
  "HTTP helpers for the RealWorld (Conduit) example.

   Every Conduit endpoint goes out via `:rf.http/managed`, the
   framework-shipped managed HTTP fx. The framework owns transport,
   decoding, status classification, retry-with-backoff, abort, and reply
   addressing; the example just composes request maps. See the HTTP guide:
   ../../../docs/resources/http.md

   BACKEND MODES (see this example's README §Running against a real backend):
   - canned demo stub (DEFAULT) — `core.cljs` overrides `:rf.http/managed` with
     an in-process per-URL stub, so the app runs with NO network. `api-base` is
     not contacted; the stub matches on the path suffix.
   - official hosted API — point `api-base` at the current hosted Conduit API
     `https://api.realworld.show/api` and drop the demo-stub override.
   - local reference backend — the upstream Node/Postgres backend on
     `http://localhost:3000/api`.

   This namespace ships TWO small helpers on top of `:rf.http/managed`:

   - `request` — build a request map (`{:request {...} :decode ... :retry ...}`)
     that bakes in the API base URL and JSON content-type. The Bearer token
     is not read here: auth-header injection is centralised in the
     `:realworld/bearer-auth` HTTP interceptor (`core.cljs`), which reads the
     token from the carried frame's auth slice (`(:frame ctx)`) on every
     request — so the header tracks whichever frame the cascade runs under,
     not a hard-coded `:rf/default`.
   - `data-fetch-retry` — the standard retry policy for read-only fetches
     (transport, 5xx, timeout — not 4xx; the request was valid even when the
     server is sad).

   The RealWorld spec lives at
   https://github.com/gothinkster/realworld/tree/main/api. This file
   registers no fx — the demo entry (`core.cljs`) wires `:rf.http/managed`
   to a canned-stub override so the app runs without a network."
  (:require [clojure.string]
            [re-frame.core :as rf]))

;; ============================================================================
;; CONFIG
;; ============================================================================

(def api-base
  "Default API base URL — the current official hosted Conduit API. The DEFAULT
   run mode overrides `:rf.http/managed` with an in-process canned stub
   (`core.cljs`), so this base is not actually contacted out of the box; it is
   the base you keep when you drop the stub to run against the real hosted API.
   For the local upstream reference backend, set it to
   `http://localhost:3000/api`. See the README §Running against a real backend."
  "https://api.realworld.show/api")

(defn full-url [path]
  (str api-base path))

;; ============================================================================
;; PAGINATION (official RealWorld spec — limit/offset query params)
;; ============================================================================
;;
;; The Conduit API paginates every article list (global, tag-filtered, the
;; authenticated feed, and a profile's authored / favorited lists) with two
;; query params:
;;
;;   ?limit=<page-size>&offset=<rows-to-skip>
;;
;; and returns the GRAND total under `articlesCount` (the count BEFORE the
;; limit/offset window — how many articles match the query, not how many this
;; page returned). The canonical Conduit frontend renders a 1-indexed
;; page-number control and computes the page count as
;; `(Math.ceil articlesCount / limit)` with a fixed page size of 10. This
;; example follows that shape: the route carries a 1-indexed `?page=N` (so
;; back/forward and bookmarking work), and the request builder below converts
;; it to the wire's 0-based `offset`.

(def page-size
  "Articles per page. Matches the canonical Conduit frontend's fixed page
   size of 10 (the official client hardcodes `Math.ceil(articlesCount / 10)`)."
  10)

(defn page->offset
  "Convert a 1-indexed UI page number to the 0-based `offset` the Conduit
   API expects (`offset = (page - 1) * limit`). Clamps a stray nil / sub-1
   page (e.g. a hand-typed `?page=0` URL) to page 1."
  [page]
  (* (dec (max 1 (or page 1))) page-size))

(defn page-count
  "Total number of pages for `articles-count` items at the fixed `page-size`
   — `(ceil articles-count / page-size)`, never below 1 (an empty list is
   still one — empty — page). Mirrors the official frontend's
   `Math.ceil(articlesCount / 10)`."
  [articles-count]
  (max 1 (long (js/Math.ceil (/ (or articles-count 0) page-size)))))

(defn paginate-path
  "Append the `limit` + `offset` query params for `page` (1-indexed) to a
   list `path`, preserving any params the path already carries (e.g.
   `/articles?tag=foo` → `/articles?tag=foo&limit=10&offset=20`). The API
   base is added later by the request builder via `full-url`."
  [path page]
  (let [sep (if (clojure.string/includes? path "?") "&" "?")]
    (str path sep "limit=" page-size "&offset=" (page->offset page))))

;; ============================================================================
;; RETRY POLICIES
;; ============================================================================

(def data-fetch-retry
  "Standard retry policy for read-only data fetches (lists, profiles,
   article detail, comments). Retries transport blips, 5xx, and timeouts
   — not 4xx (the request shape was valid; retrying won't help). Three
   attempts total with exponential backoff + jitter."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; Login / register / settings do not retry — one form submission per
;; click. A 5xx surfaces as an error so the user can retry by clicking
;; again.

;; ============================================================================
;; REQUEST BUILDER
;; ============================================================================

(defn request
  "Build a `:rf.http/managed` args map for a Conduit endpoint.

   Required keys: `:method`, `:path`. Other keys are optional and map to the
   managed-HTTP args map. See the HTTP guide: ../../../docs/resources/http.md

   The Bearer auth header is not injected here — it is added by the
   frame-wide `:realworld/bearer-auth` HTTP interceptor (`core.cljs`), which
   reads the token from the carried frame's auth slice on every request.
   This builder therefore has no `:frame` / `:auth?` concern.

   Body, if a clj coll, is JSON-encoded (`:request-content-type :json`).
   Decode defaults to `:json` (RealWorld returns JSON everywhere).

   Example:
     {:fx [[:rf.http/managed
            (rh/request {:method :get
                         :path   \"/articles\"
                         :on-success [:articles/loaded]
                         :on-failure [:articles/load-failed]
                         :retry  rh/data-fetch-retry})]]}"
  [{:keys [method path body decode accept retry timeout-ms
           on-success on-failure request-id abort-signal
           extra-headers]
    :or   {method :get
           decode :json}
    :as   args}]
  (let [headers (assoc (or extra-headers {}) "Accept" "application/json")
        req     (cond-> {:method method
                         :url    (full-url path)
                         :headers headers}
                  body (assoc :body body
                              :request-content-type :json))
        out     (cond-> {:request req
                         :decode  decode}
                  retry        (assoc :retry retry)
                  timeout-ms   (assoc :timeout-ms timeout-ms)
                  accept       (assoc :accept accept)
                  request-id   (assoc :request-id request-id)
                  abort-signal (assoc :abort-signal abort-signal)
                  (contains? args :on-success) (assoc :on-success on-success)
                  (contains? args :on-failure) (assoc :on-failure on-failure))]
    out))

;; ============================================================================
;; CLOCK — the framework's provided recordable time fact (:rf/time-ms)
;; ============================================================================
;;
;; `:loaded-at` is a durable write — it rides epoch-restore, SSR/hydration,
;; and replay. A durable write must read its wall-clock fact from the causal
;; token, not from an ambient host read at the write site: replaying the
;; same reply event must reproduce the same `:loaded-at`, which a fresh
;; `(.getTime (js/Date.))` at handler-run time cannot guarantee.
;;
;; The framework stamps that fact once, at the causal boundary, onto every
;; dispatch envelope and registers it as the provided recordable coeffect
;; `:rf/time-ms`. A handler that stamps `:loaded-at` declares
;; `:rf.cofx/requires [:rf/time-ms]` and reads the value flat:
;;
;;     (rf/reg-event :feed/loaded
;;       {:rf.cofx/requires [:rf/time-ms]}
;;       (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
;;         {:db (assoc-in db [:feed :loaded-at] time-ms)}))
;;
;; Tests and replay fixtures supply an exact `:rf/time-ms` via the dispatch
;; opts (`{:rf.cofx {:rf/time-ms 1781078400123}}`); live code lets the
;; router stamp it. See the coeffects guide:
;; ../../../docs/guide/concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable

;; ============================================================================
;; FAILURE PROJECTION
;; ============================================================================

(defn failure->message
  "Project a failure map (the inner `:failure` map of an
   `{:kind :failure :failure {...}}` reply) to a human-readable string.
   The Conduit API returns `{:errors {:body [\"...\"]}}` shapes for 4xx
   validation failures; surface those when present, otherwise fall back to a
   category-driven message."
  [failure]
  (let [body (:body failure)
        ;; The :body for 4xx/5xx is the raw response text; some Conduit
        ;; errors come through as plain JSON-shaped failure maps from
        ;; `:rf.http/accept-failure` paths. Try to surface either.
        body-msg (cond
                   (and (map? body) (-> body :errors :body first)) (-> body :errors :body first)
                   (and (map? body) (-> body :errors first)) (let [[k v] (first (:errors body))]
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
