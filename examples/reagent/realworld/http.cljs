(ns realworld.http
  "HTTP helpers for the RealWorld (Conduit) example.

   This is the canonical Spec 014 demo. Every Conduit endpoint goes
   out via `:rf.http/managed` — the framework-shipped managed
   HTTP fx. The framework owns transport, decoding, status classification,
   retry-with-backoff, abort, and reply addressing; the example just
   composes request maps.

   This namespace ships TWO small helpers on top of `:rf.http/managed`:

   - `request` — build a request map (`{:request {...} :decode ... :retry ...}`)
     that bakes in the API base URL, JSON content-type, and a Bearer token
     pulled from the auth slice. Auth-on-by-default; pass `:auth? false` to
     omit the header (login / register / public reads).
   - `data-fetch-retry` — the standard retry policy used for read-only
     fetches (transport, 5xx, timeout — not 4xx; the user's request was
     valid even when the server is sad).

   The RealWorld spec lives at https://github.com/gothinkster/realworld/tree/main/api.
   Production points at https://api.realworld.io/api; locally the spec
   ships a Node + Postgres reference backend on http://localhost:3000/api.
   This file does not register any fx — the demo entry (`core.cljs`) wires
   `:rf.http/managed` to a canned-stub override so the CLJS test fixtures
   (realworld/test/realworld/) run without a network."
  (:require [re-frame.core :as rf]))

;; ============================================================================
;; CONFIG
;; ============================================================================

(def api-base
  "Default API base URL. In production set this from the build/env; for
   local development the realworld reference backend runs on :3000."
  "https://api.realworld.io/api")

(defn full-url [path]
  (str api-base path))

;; ============================================================================
;; RETRY POLICIES (Spec 014 §Retry and backoff)
;; ============================================================================

(def data-fetch-retry
  "Standard retry policy for read-only data fetches (lists, profiles,
   article detail, comments). Retries transport blips, 5xx, and timeouts
   — not 4xx (the request shape was valid; retrying won't help). Three
   attempts total with exponential backoff + jitter."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; Login / register / settings deliberately do NOT retry — the user's
;; intent is to submit one form once. A 5xx surfaces as an error so the
;; user can retry by clicking again.

;; ============================================================================
;; REQUEST BUILDER
;; ============================================================================

(defn- token-from-frame
  "Read the current JWT (or nil) from the auth slice on the given frame."
  [frame]
  (some-> (rf/get-frame-db (or frame :rf/default))
          :auth :token))

(defn request
  "Build a Spec 014 `:rf.http/managed` args map for a Conduit endpoint.

   Required keys: `:method`, `:path`. Other keys are optional and
   correspond to the Spec 014 args map (see spec/014-HTTPRequests.md).

   Auth header is injected by default when a token is present in the
   frame's auth slice. Pass `:auth? false` to omit (login / register
   / public reads).

   Body, if a clj coll, is JSON-encoded (`:request-content-type :json`).
   Decode defaults to `:json` (RealWorld returns JSON everywhere).

   Use:
     (rf/get-frame-db ...) reads `:frame` from the cofx; pass through
     here as `:frame` if you need a non-default frame.

   Example:
     {:fx [[:rf.http/managed
            (rh/request {:method :get
                         :path   \"/articles\"
                         :on-success [:articles/loaded]
                         :on-failure [:articles/load-failed]
                         :retry  rh/data-fetch-retry})]]}"
  [{:keys [method path body decode accept retry timeout-ms
           on-success on-failure request-id abort-signal
           frame auth? extra-headers]
    :or   {auth? true
           method :get
           decode :json}
    :as   args}]
  (let [token   (when auth? (token-from-frame frame))
        headers (cond-> (or extra-headers {})
                  token (assoc "Authorization" (str "Token " token))
                  true  (assoc "Accept" "application/json"))
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
;; CLOCK COEFFECT — :realworld/now
;; ============================================================================
;;
;; Reading wall-clock time inside a handler makes the handler impure;
;; the same event applied to the same db at two moments produces two
;; different `:loaded-at` stamps. Per Spec 002 §Cofx, side-effecting
;; inputs belong in coeffects so handlers stay pure functions of
;; `(coeffects, event) → effects`. Pulling the clock into a registered
;; cofx (rather than calling `(.getTime (js/Date.))` directly) gives
;; the example a single injection point that tests can override and
;; SSR can hydrate.

(rf/reg-cofx :realworld/now
  {:doc "Inject the current wall-clock time (ms since epoch) into
         coeffects under `:realworld/now`. Use `(rf/inject-cofx
         :realworld/now)` on any handler that stamps `:loaded-at`."}
  (fn cofx-realworld-now [coeffects]
    (assoc coeffects :realworld/now (.getTime (js/Date.)))))

;; ============================================================================
;; FAILURE PROJECTION
;; ============================================================================

(defn failure->message
  "Project a Spec 014 failure map (the inner `:failure` map of an
   `{:kind :failure :failure {...}}` reply) to a human-readable string.
   The Conduit API returns `{:errors {:body [\"...\"]}}` shapes for 4xx
   validation failures; surface those when present, otherwise fall back
   to a category-driven message."
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
