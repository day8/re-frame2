(ns re-frame.ssr.ring
  "Ring/Pedestal host adapter for re-frame2 SSR. Per Spec 011 §HTTP
  response contract and the rf2-ny6v7 host-adapter brief.

  Spec 011 §357–363 names the host adapter as the layer responsible for
  materialising the runtime's `:response` accumulator into the wire
  format its server framework expects. The SSR runtime never writes to
  a network socket directly; it owns the request lifecycle (frame
  create → drain → response read → frame destroy) and a structured
  response shape; the host adapter wires that shape to a real HTTP
  server.

  This namespace ships that adapter for Ring (https://github.com/ring-clojure).
  Ring is the canonical Clojure HTTP-server abstraction; the
  request/response maps documented at the Ring Concepts wiki page
  (`:status`, `:headers`, `:body` on responses; `:uri`,
  `:request-method`, `:headers`, etc. on requests) are the wire shape
  this adapter consumes and produces. Pedestal, HttpKit, Reitit-ring,
  and Jetty all accept Ring-shaped handlers, so a single adapter covers
  the bulk of the Clojure HTTP ecosystem.

  Public surface:

    (ssr-handler opts)  → ring-handler-fn
    (ssr-middleware opts) → ((handler) → wrapped-handler)
    *current-request*   — dynamic var bound for the duration of each
                          request; the `:rf.server/request` cofx
                          (rf2-e825b) reads from this binding.

  Per-request flow inside `ssr-handler`:

    1. Bind `*current-request*` to the Ring request map.
    2. Build a per-request server frame via `(rf/make-frame ...)`
       with `:platform :server`, the caller's :on-create event (with
       the request map conj'd as an argument), and the caller's
       optional :fx-overrides / :ssr config.
    3. The drain runs synchronously inside make-frame's :on-create
       dispatch path (Spec 002 §dispatch).
    4. Read the response accumulator via `ssr/get-response` (flushes
       any pending error projection per Spec 011 §Server error
       projection).
    5. If :redirect is set on the response, emit a Ring response with
       just status + Location header — no body, no payload (Spec 011
       §Redirect precedence step 4).
    6. Otherwise render the caller's `:root-view` against the frame
       (with-frame binds *current-frame* across the render), wrap in
       the caller's `:html-shell` envelope, and emit a Ring response
       with status / headers / body.
    7. Materialise structured cookies to Set-Cookie headers per
       RFC 6265 — re-frame.ssr stores cookies as structured maps
       (Spec 011 §Cookie shape); the host adapter is where the wire
       string is built so user code never touches the per-attribute
       quoting / encoding pitfalls.
    8. Destroy the per-request frame in a `finally` block — pending
       :dispatch-later calls, sub-cache reactions, and trace-buffer
       state all release on destroy (Spec 002 §Destroy).

  Coordination with rf2-e825b (`:rf.server/request` cofx):

    rf2-e825b lands the cofx itself; this adapter is the binding site.
    The cofx reads from `re-frame.ssr.ring/*current-request*`; this
    namespace binds the var per request. If rf2-e825b hasn't merged
    yet, the binding is a harmless no-op — handlers that try to read
    via `(inject-cofx :rf.server/request)` get nil until the cofx
    lands.

  Head/meta integration (rf2-4dra9):

    The adapter resolves the active route's `:head` registration via
    `rf/active-head` after the drain settles; the produced
    `:rf/head-model` is rendered to its inner-head HTML fragment via
    `rf/head-model->html` and threaded into the shell as the `:head`
    opt. Custom shells (`:html-shell` opt) receive the resolved head
    fragment in the merged opts map. Routes that don't declare `:head`
    fall back to the default head model (Spec 011 §Default head); the
    fragment is empty when there is no useful head data.

  Out of scope here (deferred / other beads):

    - Streaming SSR (rf2-olb64)            — `render-to-string` is
                                             a single-shot emitter; this
                                             adapter mirrors that.
    - Async Ring handler (3-arity)         — synchronous-only in v1;
                                             extension is additive."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]))

;; ---- *current-request* — rf2-e825b binding site ---------------------------

(def ^:dynamic *current-request*
  "The active Ring request map, bound for the duration of one HTTP
  request inside `ssr-handler` / `ssr-middleware`. The
  `:rf.server/request` cofx (rf2-e825b — `re-frame.ssr` registers the
  cofx itself; the host adapter is the binding site) reads from this
  var. nil outside an SSR request."
  nil)

;; ---- cookie serialisation (RFC 6265) -------------------------------------
;;
;; Per Spec 011 §Cookie shape, re-frame.ssr stores cookies as structured
;; maps and lets the host adapter materialise the Set-Cookie wire string.
;; This intentionally keeps the per-attribute quoting / encoding details
;; out of user code.

(def ^:private ^DateTimeFormatter rfc1123-formatter
  ;; Set-Cookie's :Expires uses RFC 7231 IMF-fixdate (a fixed-format
  ;; subset of RFC 1123): "Sun, 06 Nov 1994 08:49:37 GMT".
  (.withZone DateTimeFormatter/RFC_1123_DATE_TIME ZoneOffset/UTC))

(defn- url-encode
  "URL-encode a cookie value. java.net.URLEncoder emits `+` for space
  (form-encoded shape, RFC 1866), but RFC 6265 cookie values follow
  RFC 3986 percent-encoding — space is `%20`. Post-process the
  form-encoded output to convert `+` back to `%20` so the wire shape is
  RFC-3986-clean."
  [s]
  (-> (URLEncoder/encode (str s) (.name StandardCharsets/UTF_8))
      (str/replace "+" "%20")))

(defn- same-site-token [v]
  (case v
    :strict "Strict"
    :lax    "Lax"
    :none   "None"
    ;; tolerant of string-shaped values
    (cond
      (string? v) v
      (keyword? v) (str/capitalize (name v))
      :else (str v))))

(defn cookie->set-cookie-header
  "Serialise one re-frame.ssr cookie map to a Set-Cookie header value
  per RFC 6265 §4.1. Per Spec 011 §Cookie shape — the cookie's :name /
  :value are required; everything else is an attribute appended after
  semicolons. The :value is URL-encoded.

  Public surface so tests, alt host adapters (Pedestal, HttpKit), and
  user code that needs a one-off serialisation can call it directly."
  [{:keys [name value max-age secure http-only same-site path domain expires]
    :as cookie}]
  (when (nil? name)
    (throw (ex-info ":rf.error/cookie-missing-name"
                    {:reason "cookie map must carry :name"
                     :cookie cookie})))
  (let [parts (cond-> [(str (clojure.core/name name)
                            "="
                            (url-encode (or value "")))]
                ;; Order doesn't matter to the RFC, but the canonical
                ;; serving order in most libraries is:
                ;;   Max-Age, Domain, Path, Expires, Secure, HttpOnly, SameSite
                (some? max-age)  (conj (str "Max-Age=" max-age))
                (some? domain)   (conj (str "Domain=" domain))
                (some? path)     (conj (str "Path=" path))
                (some? expires)
                (conj (str "Expires="
                           (.format rfc1123-formatter
                                    (ZonedDateTime/ofInstant
                                      (Instant/ofEpochMilli expires)
                                      ZoneOffset/UTC))))
                (true? secure)    (conj "Secure")
                (true? http-only) (conj "HttpOnly")
                (some? same-site) (conj (str "SameSite=" (same-site-token same-site))))]
    (str/join "; " parts)))

;; ---- header materialisation ----------------------------------------------
;;
;; re-frame.ssr stores headers internally as an ordered vector of
;; [name value] pairs (case-insensitive name match). Ring accepts
;; headers as a map of name → string OR name → vector-of-strings;
;; multiple values under one name go via a vector. We collapse repeated
;; pairs into vectors so multi-valued headers (Set-Cookie, Vary,
;; Link, ...) round-trip correctly.

(defn- merge-pair-into-header-map [m [k v]]
  (let [existing (get m k)]
    (cond
      (nil? existing)        (assoc m k v)
      (string? existing)     (assoc m k [existing v])
      (vector? existing)     (assoc m k (conj existing v))
      :else                  (assoc m k [existing v]))))

(defn- headers->ring-map
  "Collapse an ordered vec-of-[name value] pairs into Ring's
  `{name string-or-vec}` shape, preserving the multi-valued case."
  [pairs]
  (reduce merge-pair-into-header-map {} pairs))

(defn- append-set-cookies
  "For every cookie map in the response's :cookies vector, append one
  Set-Cookie header to the headers map. Returns the updated headers
  map."
  [headers-map cookies]
  (reduce
    (fn [m cookie]
      (merge-pair-into-header-map m ["Set-Cookie"
                                     (cookie->set-cookie-header cookie)]))
    headers-map
    cookies))

;; ---- response materialisation --------------------------------------------

(defn- ssr-response->ring-response
  "Materialise the runtime's resolved response accumulator (per
  Spec 011 §HTTP response contract) into a Ring response map. The
  `:body` arg is the rendered HTML (or nil for redirect-only
  responses). `:redirect` short-circuits per Spec 011 §Redirect
  precedence — status + Location header, no body."
  [{:keys [status headers cookies redirect]} body]
  (if redirect
    (let [{:keys [location url to] redirect-status :status} redirect
          target (or location url to)]
      {:status  (or redirect-status status 302)
       :headers (-> (headers->ring-map headers)
                    (append-set-cookies cookies)
                    (cond-> target (assoc "Location" target)))
       :body    ""})
    {:status  (or status 200)
     :headers (-> (headers->ring-map headers)
                  (append-set-cookies cookies))
     :body    (or body "")}))

;; ---- html shell ----------------------------------------------------------
;;
;; A small, sane default. Users with their own envelope (custom
;; `<head>`, asset-hash-pinned `<script>` tags, JSON-LD blocks, ...)
;; pass an :html-shell option to override. The default exists so a
;; first-time user gets a working SSR endpoint without writing string
;; concatenation glue.

(defn default-html-shell
  "The default HTML envelope. Returns a string wrapping the rendered
  body in a minimal-but-runnable document. Override via `:html-shell`
  in `ssr-handler` opts when you need custom <head> / scripts / styles.

  Args:
    body-html — the string returned by re-frame.ssr/render-to-string
    payload-edn — the hydration payload, pre-serialised with pr-str
    opts — the caller's adapter opts (merged with any per-request
           overrides); standard keys :title / :head / :body-end /
           :script-src / :app-element-id influence the envelope."
  [body-html payload-edn
   {:keys [title head body-end script-src app-element-id lang]
    :or   {title           "re-frame2 app"
           app-element-id  "app"
           script-src      "/main.js"
           lang            "en"}}]
  (str "<!DOCTYPE html>"
       "<html lang=\"" lang "\">"
       "<head>"
       "<meta charset=\"utf-8\">"
       "<title>" title "</title>"
       (or head "")
       "</head>"
       "<body>"
       "<div id=\"" app-element-id "\">" body-html "</div>"
       "<script id=\"__rf_payload\" type=\"application/edn\">"
       payload-edn
       "</script>"
       (when script-src
         (str "<script src=\"" script-src "\"></script>"))
       (or body-end "")
       "</body>"
       "</html>"))

;; ---- payload construction ------------------------------------------------

(defn- build-payload
  "Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/schema-digest`. Schema-digest is supplied
  by the caller when their app participates in the schema-digest
  check; nil otherwise. `:or` defaults at destructure don't fire
  when an explicit nil flows in, so use `or` at the call site to
  default the version."
  [frame-id app-db render-hash {:keys [version schema-digest payload-keys]}]
  (let [db-slice (if (seq payload-keys)
                   (select-keys app-db payload-keys)
                   app-db)]
    (cond-> {:rf/version     (or version 1)
             :rf/frame-id    frame-id
             :rf/app-db      db-slice
             :rf/render-hash render-hash}
      schema-digest (assoc :rf/schema-digest schema-digest))))

;; ---- handler -------------------------------------------------------------

(defn- destroy-frame-quietly!
  "Best-effort frame teardown. Exceptions during destroy must not mask
  a real handler error; swallow + log to the trace stream is preferred
  over propagation."
  [frame-id]
  (try
    (rf/destroy-frame frame-id)
    (catch Throwable _ nil)))

(defn- render-body
  "Render the root view against the per-request frame. Wrapped here so
  view-time exceptions can be projected through the SSR error path
  per Spec 011 §View-time exceptions. Returns the HTML string."
  [frame-id root-view emit-hash?]
  (rf/with-frame frame-id
    (let [hiccup (cond
                   (vector? root-view) root-view
                   (fn?     root-view) (root-view)
                   :else
                   (throw (ex-info ":rf.error/invalid-root-view"
                                   {:reason   "root-view must be a hiccup vector or a 0-arity fn"
                                    :received root-view})))]
      (ssr/render-to-string hiccup {:doctype?   false
                                    :emit-hash? emit-hash?}))))

(defn- render-hash-for
  "Compute the structural render-tree hash. Mirrors render-body's
  root-view resolution so the payload's :rf/render-hash matches the
  data-rf-render-hash embedded in the wire HTML."
  [frame-id root-view]
  (rf/with-frame frame-id
    (let [hiccup (if (fn? root-view) (root-view) root-view)]
      (ssr/render-tree-hash hiccup))))

(defn- resolve-head-html
  "Resolve the active route's `:head` against `frame-id` (or the default
  head when the route doesn't declare one). Returns the inner-head HTML
  fragment as a string. Exceptions during resolution degrade gracefully
  to an empty string so a buggy head fn can't take down the request —
  the trace surface still carries the throw for monitoring.

  Per Spec 011 §Head/meta contract (rf2-4dra9)."
  [frame-id]
  (try
    (let [model (rf/active-head frame-id)]
      (rf/head-model->html model))
    (catch Throwable _ "")))

(defn- on-create-with-request
  "Conj the Ring request map onto the caller's :on-create event vector
  so handlers can read it as an event arg. The `:rf.server/request`
  cofx (rf2-e825b) is the canonical read path; this is the fallback
  for handlers that don't inject the cofx and want the request as a
  positional arg, matching the worked example in
  examples/reagent/ssr/core.cljc."
  [on-create request]
  (cond
    (nil? on-create)    nil
    (vector? on-create) (conj on-create request)
    :else
    (throw (ex-info ":rf.error/invalid-on-create"
                    {:reason   ":on-create must be a vector (event)"
                     :received on-create}))))

(defn ssr-handler
  "Return a Ring-shaped (synchronous) handler that renders one
  re-frame2 SSR request per call.

  Required opts:

    :on-create   — the event vector dispatched at frame creation. The
                   Ring request map is conj'd as the last arg so handlers
                   can read it positionally (and via the
                   `:rf.server/request` cofx once rf2-e825b lands).
    :root-view   — either a hiccup vector (e.g. `[:app/root]`) OR a
                   0-arity fn returning hiccup. Rendered against the
                   per-request frame after the drain settles.

  Optional opts:

    :fx-overrides   — per-frame `:fx-overrides` map, passed through
                      verbatim to `(rf/make-frame ...)`. Useful for
                      stubbing `:rf.http/managed` during tests.
    :ssr            — per-frame `:ssr` config map (e.g.
                      `{:dev-error-detail? true
                        :public-error-id   :myapp/projector}`).
    :emit-hash?     — embed `data-rf-render-hash` on the root element
                      (default true).
    :version        — hydration payload's `:rf/version` (default 1).
    :schema-digest  — hydration payload's `:rf/schema-digest`, when
                      the app participates in the digest check.
    :payload-keys   — coll of top-level app-db keys to ship in the
                      payload's `:rf/app-db`. Default: ship the whole
                      app-db. Use to slice large per-request state
                      down to what the client genuinely needs.
    :html-shell     — (body-html payload-edn opts) → string. Defaults
                      to `default-html-shell`. Replace to inject custom
                      <head>, scripts, JSON-LD, etc.
    :content-type   — Content-Type header for HTML responses. Default
                      \"text/html; charset=utf-8\" (matches the SSR
                      runtime's default in the response accumulator).
    :on-error       — (request throwable) → ring-response. Called when
                      the per-request frame setup OR render throws.
                      Defaults to a minimal 500 response. The SSR
                      runtime's error projector handles trace-emitted
                      errors during drain; this hook covers the
                      exceptions the projector can't see (Ring-layer
                      throws, render-time CLJ exceptions).

  Returns:

    (fn handler [ring-request] ring-response)

  Per-request lifecycle (see ns docstring for full detail):

    bind *current-request*
      → make-frame                  (drains :on-create synchronously)
        → read get-response          (flushes error projections)
        → branch on :redirect
        → render-to-string + payload
        → materialise to Ring map
      → finally: destroy-frame!

  Example:

    (require '[ring.adapter.jetty :as jetty]
             '[re-frame.core :as rf]
             '[re-frame.ssr.ring :as ssr-ring])

    (rf/init! (requiring-resolve 'ssr-ring-app/ssr-adapter))
    (def handler
      (ssr-ring/ssr-handler {:on-create [:rf/server-init]
                             :root-view [:app/root]
                             :html-shell ssr-ring-app/shell}))
    (jetty/run-jetty handler {:port 3000 :join? false})"
  [{:keys [on-create root-view fx-overrides ssr-config emit-hash?
           version schema-digest payload-keys html-shell content-type
           on-error]
    :or   {emit-hash?    true
           html-shell    default-html-shell
           content-type  "text/html; charset=utf-8"
           on-error      (fn default-on-error [_req ^Throwable t]
                           {:status  500
                            :headers {"Content-Type" "text/plain; charset=utf-8"}
                            :body    (str "SSR error: " (.getMessage t))})}
    :as   opts}]
  (when-not on-create
    (throw (ex-info ":rf.error/ssr-ring-missing-on-create"
                    {:reason "ssr-handler requires :on-create (an event vector)"})))
  (when-not root-view
    (throw (ex-info ":rf.error/ssr-ring-missing-root-view"
                    {:reason "ssr-handler requires :root-view (a hiccup vector or 0-arity fn)"})))
  (fn ring-handler [request]
    (binding [*current-request* request]
      (let [frame-id
            (try
              (rf/make-frame
                (cond-> {:doc       "ssr-ring per-request frame"
                         :platform  :server
                         :on-create (on-create-with-request on-create request)}
                  fx-overrides (assoc :fx-overrides fx-overrides)
                  ssr-config   (assoc :ssr           ssr-config)))
              (catch Throwable t
                (on-error request t)))]
        (cond
          ;; on-error returned a Ring response (frame creation threw).
          (map? frame-id) frame-id

          (nil? frame-id)
          (on-error request (ex-info ":rf.error/ssr-ring-make-frame-returned-nil"
                                     {:reason "make-frame returned nil"}))

          :else
          (try
            (let [response (ssr/get-response frame-id)
                  ;; rf/get-frame-db returns the value (plain map), not
                  ;; the container. Per
                  ;; implementation/core/src/re_frame/core.cljc:889 —
                  ;; value-form accessor; no deref needed.
                  app-db   (rf/get-frame-db frame-id)]
              (cond
                ;; Redirect — short-circuit per Spec 011 §Redirect precedence.
                (some? (:redirect response))
                (ssr-response->ring-response response nil)

                :else
                (let [body-html   (render-body frame-id root-view emit-hash?)
                      hash-str    (render-hash-for frame-id root-view)
                      payload     (build-payload frame-id app-db hash-str
                                                 {:version       version
                                                  :schema-digest schema-digest
                                                  :payload-keys  payload-keys})
                      payload-edn (pr-str payload)
                      ;; rf2-4dra9: resolve the active route's :head
                      ;; (or default-head fallback) and pass the rendered
                      ;; fragment as the :head opt. Callers that supplied
                      ;; an explicit :head opt take precedence — they
                      ;; chose to bypass route-driven head resolution.
                      head-html   (or (:head opts) (resolve-head-html frame-id))
                      shell-opts  (assoc opts :head head-html)
                      html        (html-shell body-html payload-edn shell-opts)
                      ;; Ensure Content-Type is set; the SSR runtime
                      ;; defaults [:rf/response :headers] to include
                      ;; content-type so this is usually a no-op, but
                      ;; we let opts override and we trust the runtime's
                      ;; default in absence.
                      response*   (update response :headers
                                          (fn [pairs]
                                            (let [m (headers->ring-map pairs)]
                                              (if (or (get m "content-type")
                                                      (get m "Content-Type"))
                                                pairs
                                                (conj (vec pairs)
                                                      ["Content-Type" content-type])))))]
                  (ssr-response->ring-response response* html))))
            (catch Throwable t
              (on-error request t))
            (finally
              (destroy-frame-quietly! frame-id))))))))

;; ---- middleware ----------------------------------------------------------

(defn ssr-middleware
  "Return Ring middleware that delegates to `ssr-handler` for the
  requests its `:match?` predicate accepts, and to the wrapped handler
  otherwise.

  Useful when SSR is one of several handlers in a Ring stack — e.g.
  static-asset middleware in front, JSON-API routes alongside.

  Opts are `ssr-handler`'s opts plus:

    :match?  — (request) → boolean. When truthy, SSR renders. When
               falsy, the call falls through to the wrapped handler.
               Default: matches every GET request.

  Example:

    (def app
      (-> default-handler
          (ssr-ring/ssr-middleware
            {:on-create [:rf/server-init]
             :root-view [:app/root]
             :match? (fn [req] (= :get (:request-method req)))})
          wrap-static-assets))"
  [{:keys [match?] :as opts}]
  (let [match? (or match? (fn default-match? [req]
                            (= :get (:request-method req))))
        ssr   (ssr-handler (dissoc opts :match?))]
    (fn middleware [handler]
      (fn wrapped [request]
        (if (match? request)
          (ssr request)
          (handler request))))))
