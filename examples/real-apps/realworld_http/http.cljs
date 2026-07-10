(ns realworld-http.http
  "HTTP helpers for the RealWorld (Conduit) example — this app's request-builder
   surface, built on the shared Conduit contract.

   Every Conduit endpoint goes out via `:rf.http/managed`, the framework's
   managed HTTP fx. That's a deliberately good deal: the framework handles
   transport, decoding, status classification, retry-with-backoff, abort, and
   reply addressing, and all this example has to do in return is hand it a
   request map. See the HTTP guide: ../../../docs/async/http.md

   The transport-neutral parts of that request map — query encoding, the read
   retry policy, the failure-taxonomy projection, and the pagination arithmetic
   — are the same for both RealWorld examples, so they live in
   `realworld-shared.http` (imported as `wh`). This namespace re-exposes them as
   the app's HTTP surface (so call sites keep a single `rh/*` import point) and
   adds the two conveniences that are genuinely local to this managed-HTTP
   architecture: the `request` builder and `paginate-path`.

   BACKEND MODES (see this example's README §Running against a real backend):
   - canned demo stub (DEFAULT) — `core.cljs` overrides `:rf.http/managed`
     with an in-process router built on `realworld-shared.demo-backend`, so the
     app runs with NO network at all. `api-base` is never contacted.
   - official hosted API — point `api-base` at the hosted Conduit API,
     `https://api.realworld.show/api`, and drop the demo-stub override.
   - local reference backend — the upstream Node/Postgres backend on
     `http://localhost:3000/api`."
  (:require [realworld-shared.http :as wh]))

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
;; SHARED CONTRACT — re-exposed as this app's HTTP surface
;; ============================================================================
;;
;; The definitions live once, in realworld-shared.http; here they're the app's
;; public helpers so the feature nses keep a single `rh/*` import point.

(def page-size
  "The fixed Conduit list page size (10). From the shared contract."
  wh/page-size)

(def data-fetch-retry
  "The house retry policy for read-only fetches (transport / 5xx / timeout,
   never 4xx). From the shared contract."
  wh/data-fetch-retry)

(def page-count
  "How many pages `articles-count` items fill at `page-size`, floored at 1.
   From the shared contract."
  wh/page-count)

(def failure->message
  "Project a classified `:rf.http/*` failure map into a human-readable
   sentence, preferring the server's own words. From the shared contract."
  wh/failure->message)

;; ============================================================================
;; PAGINATION PATH (this app's request builder)
;; ============================================================================
;;
;; Conduit paginates its article lists with `?limit=&offset=` query params (see
;; realworld-shared.http for the arithmetic and the 1-indexed `?page=N` twist).
;; This builder assembles the paginated list path; the shared helpers do the
;; encoding and the page maths.

(defn paginate-path
  "Assemble a Conduit list-endpoint `path` (e.g. `/articles` or
   `/articles/feed`) plus optional `filters` — a map like `{:tag \"clojure\"}`,
   `{:author \"jake\"}`, or `{:favorited \"jake\"}`; `nil`/`{}` for the
   unfiltered list — plus the `limit`/`offset` pagination window for `page`
   (1-indexed). Every value goes through `wh/query-string`, so it's properly
   URL-encoded rather than concatenated raw — a tag or username with a reserved
   query character no longer corrupts the request. The API base gets prepended
   later, by the request builder via `full-url`."
  [path filters page]
  (str path (wh/query-string (merge filters (wh/page->limit-offset page)))))

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
           on-success on-failure reply-to request-id abort-signal
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
                  ;; Reply addressing (Spec 014 §Reply addressing): `:reply-to`
                  ;; is the unified spelling (one target for both branches),
                  ;; `:on-success` / `:on-failure` the split sugar — pass
                  ;; whichever the call site supplied straight through.
                  (contains? args :reply-to)   (assoc :reply-to reply-to)
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
;; ../../../docs/core/coeffects.md#two-grades-ambient-and-recordable
