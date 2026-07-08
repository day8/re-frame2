(ns realworld-http.http
  "HTTP helpers for the RealWorld (Conduit) example.

   Every Conduit endpoint goes out via `:rf.http/managed`, the framework's
   managed HTTP fx. That's a deliberately good deal: the framework handles
   transport, decoding, status classification, retry-with-backoff, abort, and
   reply addressing, and all this example has to do in return is hand it a
   request map. See the HTTP guide: ../../../docs/async/http.md

   BACKEND MODES (see this example's README §Running against a real backend):
   - canned demo stub (DEFAULT) — `core.cljs` overrides `:rf.http/managed`
     with an in-process per-URL stub, so the app runs with NO network at all.
     `api-base` is never contacted; the stub matches on the path suffix.
   - official hosted API — point `api-base` at the hosted Conduit API,
     `https://api.realworld.show/api`, and drop the demo-stub override.
   - local reference backend — the upstream Node/Postgres backend on
     `http://localhost:3000/api`.

   This namespace adds just two small conveniences on top of
   `:rf.http/managed`:

   - `request` — builds the request map (`{:request {...} :decode ... :retry
     ...}`), baking in the API base URL and the JSON content-type so you don't
     repeat them at every call site. It pointedly does NOT touch the Bearer
     token: signing requests is the `:realworld/bearer-auth` interceptor's one
     job (`core.cljs`), and it reads the token from the carried frame on every
     request — so the header follows whichever frame the cascade is running
     under, not a hard-coded `:rf/default`.
   - `data-fetch-retry` — the house retry policy for read-only fetches:
     transport, 5xx, and timeout, but never 4xx. A 4xx means the request was
     valid even though the server was unhappy with it, so retrying changes
     nothing except how long you wait to hear the same no.

   The RealWorld spec lives at
   https://github.com/gothinkster/realworld/tree/main/api. This file registers
   no fx of its own — `core.cljs` is where `:rf.http/managed` gets pointed at
   the canned stub so the app runs offline."
  (:require [clojure.string]
            [re-frame.core :as rf]))

;; ============================================================================
;; CONFIG
;; ============================================================================

(def api-base
  "Where the API lives — the hosted Conduit backend. Out of the box you never
   reach it: the default run mode swaps `:rf.http/managed` for an in-process
   canned stub (`core.cljs`). This is the URL that springs back to life the
   moment you drop the stub to talk to the real hosted API. Pointing at a
   local upstream backend instead? Use `http://localhost:3000/api`. See the
   README §Running against a real backend."
  "https://api.realworld.show/api")

(defn full-url [path]
  (str api-base path))

;; ============================================================================
;; PAGINATION (official RealWorld spec — limit/offset query params)
;; ============================================================================
;;
;; Conduit paginates every article list — global, tag-filtered, the
;; authenticated feed, and a profile's authored / favorited lists — the
;; classic database way, with two query params:
;;
;;   ?limit=<page-size>&offset=<rows-to-skip>
;;
;; Alongside the page it hands back `articlesCount`: the GRAND total, the
;; number of articles matching the query BEFORE the limit/offset window — not
;; the size of this page. That distinction is the whole game, because the page
;; count comes from the grand total, not from what one page happened to
;; return. The canonical Conduit frontend draws a 1-indexed page-number
;; control and works out the page count as `(Math.ceil articlesCount / limit)`
;; at a fixed page size of 10.
;;
;; We mirror that shape, with one ergonomic twist: the ROUTE carries a
;; human-friendly 1-indexed `?page=N` (so Back/Forward and bookmarking just
;; work), and the request builder below quietly translates it into the wire's
;; 0-based `offset`. Humans count from 1; the wire counts from 0; this is the
;; seam where the two meet.

(def page-size
  "Articles per page: 10, to match the canonical Conduit frontend (which
   hardcodes `Math.ceil(articlesCount / 10)`)."
  10)

(defn page->offset
  "Turn a 1-indexed UI page number into the 0-based `offset` the wire wants
   (`offset = (page - 1) * limit`). A stray nil or sub-1 page — someone
   hand-typing `?page=0` into the URL bar — is clamped up to page 1, because
   page zero is not a thing."
  [page]
  (* (dec (max 1 (or page 1))) page-size))

(defn page-count
  "How many pages `articles-count` items fill at the fixed `page-size`:
   `(ceil articles-count / page-size)`, but never less than 1 — an empty list
   is still one page, it's just an empty one. Mirrors the official frontend's
   `Math.ceil(articlesCount / 10)`."
  [articles-count]
  (max 1 (long (js/Math.ceil (/ (or articles-count 0) page-size)))))

(defn query-string
  "Assemble a `?k=v&k2=v2…` query string from a map of query params, with every
   value URL-encoded via `js/encodeURIComponent` so a tag, username, or other
   filter value carrying a reserved query character (`&`, `=`, `#`, a space,
   …) reaches the API as that literal value instead of corrupting the query
   string it's embedded in. Nil-valued params are dropped, and an empty (or
   all-nil) map yields `\"\"`, not a bare `\"?\"`."
  [params]
  (let [pairs (for [[k v] params :when (some? v)]
                (str (name k) "=" (js/encodeURIComponent (str v))))]
    (if (seq pairs)
      (str "?" (clojure.string/join "&" pairs))
      "")))

(defn paginate-path
  "Assemble a Conduit list-endpoint `path` (e.g. `/articles` or
   `/articles/feed`) plus optional `filters` — a map like `{:tag \"clojure\"}`,
   `{:author \"jake\"}`, or `{:favorited \"jake\"}`; `nil`/`{}` for the
   unfiltered list — plus the `limit`/`offset` pagination window for `page`
   (1-indexed). Every value goes through `query-string`, so it's properly
   URL-encoded rather than concatenated raw — a tag or username with a
   reserved query character no longer corrupts the request. The API base gets
   prepended later, by the request builder via `full-url`."
  [path filters page]
  (str path (query-string (assoc filters
                                  :limit page-size
                                  :offset (page->offset page)))))

;; ============================================================================
;; RETRY POLICIES
;; ============================================================================

(def data-fetch-retry
  "The house retry policy for read-only fetches — lists, profiles, article
   detail, comments. It retries the failures that a second try might fix
   (transport blips, 5xx, timeouts) and leaves 4xx alone, because a rejected
   request shape doesn't get any more valid the second time you send it.
   Three attempts total, with exponential backoff and a dash of jitter so a
   thundering herd doesn't all retry on the same beat."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; Writes don't get a retry policy. Login, register, and settings are one
;; submission per click — if the server 5xxs, that surfaces as an error and
;; the user decides whether to click again. Silently re-POSTing someone's form
;; behind their back is how you end up with two of everything.

;; ============================================================================
;; REQUEST BUILDER
;; ============================================================================

(defn request
  "Assemble a `:rf.http/managed` args map for a Conduit endpoint, so the call
   sites can stay short and declarative.

   The only keys you must supply are `:method` and `:path`. Everything else is
   optional and passes straight through to the managed-HTTP args map. See the
   HTTP guide: ../../../docs/async/http.md

   You won't find the Bearer header here, and that's by design: the frame-wide
   `:realworld/bearer-auth` interceptor (`core.cljs`) stamps it on every
   request from the live auth slice. So this builder has no `:frame` or
   `:auth?` knob to fuss with — one less thing to remember at the call site.

   A clj collection in `:body` gets JSON-encoded for you
   (`:request-content-type :json`), and `:decode` defaults to `:json` because
   RealWorld speaks JSON everywhere.

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
;; CLOCK — the framework's recordable time fact (:rf/time-ms)
;; ============================================================================
;;
;; Reading the clock is the classic way a "pure" handler quietly stops being
;; pure. `:loaded-at` is a durable write — it travels through epoch-restore,
;; SSR/hydration, and replay — and replay only works if re-running the same
;; reply event lands on the same `:loaded-at` every time. A fresh
;; `(.getTime (js/Date.))` inside the handler can't promise that: replay it
;; tomorrow and you get tomorrow's timestamp. So the handler must read the
;; clock as a recorded fact, not grab it live at the write site.
;;
;; The framework does the recording for you. It reads the wall clock once, at
;; the causal boundary, stamps it onto every dispatch envelope, and offers it
;; back as the recordable coeffect `:rf/time-ms`. A handler that wants to write
;; a timestamp just asks for it with `:rf.cofx/requires [:rf/time-ms]` and
;; reads it flat — staying pure, and staying replayable:
;;
;;     (rf/reg-event :feed/loaded
;;       {:rf.cofx/requires [:rf/time-ms]}
;;       (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
;;         {:db (assoc-in db [:feed :loaded-at] time-ms)}))
;;
;; In tests and replay fixtures you can pin an exact instant through the
;; dispatch opts (`{:rf.cofx {:rf/time-ms 1781078400123}}`); live code just
;; lets the router stamp the real time. See the coeffects guide:
;; ../../../docs/core/concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable

;; ============================================================================
;; FAILURE PROJECTION
;; ============================================================================

(defn failure->message
  "Turn a failure map (the classified `:rf.http/*` map that rides under
   `:error` on a `{:status :error :error {...}}` reply) into a sentence a
   human can actually read. Conduit reports
   4xx validation problems as `{:errors {:body [\"...\"]}}`, so we surface the
   server's own words when they're there, and fall back to a friendly
   category-based message when they aren't — because \"Network error — please
   try again\" beats a stack trace every time."
  [failure]
  (let [body (:body failure)
        ;; The :body might be raw response text, or it might be a JSON-shaped
        ;; failure map arriving via the `:rf.http/accept-failure` path. Try to
        ;; pull a message out of either shape.
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
