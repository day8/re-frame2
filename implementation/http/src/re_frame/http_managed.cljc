(ns re-frame.http-managed
  "Spec 014 — `:rf.http/managed` and family.

  A first-class managed HTTP request fx with built-in decoding,
  retry-with-backoff, abort, schema-driven decode, and reply-to-origin
  dispatch. Per spec/014-HTTPRequests.md.

  ## Public surface (registered at ns-load)

  - `:rf.http/managed`                  — issue a managed request
  - `:rf.http/managed-abort`            — abort by `:request-id`
  - `:rf.http/managed-canned-success`   — test stub
  - `:rf.http/managed-canned-failure`   — test stub

  Plus `(with-managed-request-stubs stubs body)` helper for test ergonomics.

  ## Hosts

  - **CLJS:** Fetch-API-backed.
  - **JVM:** `java.net.http.HttpClient`-backed. Per-row CLJS-only keys
    (`:abort-signal`, `:mode`, `:cache`, `:referrer`, `:integrity`) are
    no-ops on JVM with a one-line trace per occurrence.

  ## Production elision

  Trace events (`:rf.http/retry-attempt`, `:rf.warning/decode-defaulted`,
  the `:rf.error/*` from failures) gate on `interop/debug-enabled?`.
  The `:rf.http/managed` fx itself is dev+prod (user-facing). The canned
  stub fxs gate on `interop/debug-enabled?` so they elide in production.

  ## Artefact (rf2-5kpd, fifth per-feature split per rf2-5vjj Strategy B)

  This namespace ships in `day8/re-frame-2-http`, separate from the core
  artefact (`day8/re-frame-2`). The core artefact's `re-frame.core`
  re-exports of `install-managed-request-stubs!` /
  `uninstall-managed-request-stubs!` / `with-managed-request-stubs*` /
  `with-managed-request-stubs` look this namespace's entry points up via
  the `re-frame.late-bind` hook table — loading this namespace publishes
  the hooks AND registers the `:rf.http/managed`,
  `:rf.http/managed-abort`, `:rf.http/managed-canned-success`, and
  `:rf.http/managed-canned-failure` fxs. Apps that don't issue any
  managed-HTTP requests don't drag the in-flight request registry, the
  Fetch / HttpClient transport adapters, the encode / decode pipeline,
  the retry-with-backoff machinery, the eight-category `:rf.http/*`
  failure taxonomy, or any of the `:rf.http/*` keyword strings onto the
  classpath."
  (:require [re-frame.fx        :as fx]
            [re-frame.interop   :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.trace     :as trace]
            #?@(:clj  [[clojure.string :as str]]
                :cljs [[clojure.string :as str]]))
  #?(:clj (:import [java.net URI URLEncoder]
                   [java.net.http HttpClient HttpRequest
                                  HttpRequest$BodyPublisher HttpRequest$BodyPublishers
                                  HttpResponse HttpResponse$BodyHandlers]
                   [java.time Duration]
                   [java.util.concurrent CompletableFuture])))

;; ---- in-flight request registry -------------------------------------------
;;
;; Per Spec 014 §Aborts: `:request-id` keys map to `request-handle` records
;; so a subsequent `:rf.http/managed-abort` can cancel an in-flight request,
;; and a fresh request with the same `:request-id` supersedes the previous
;; one (`:reason :request-id-superseded`).

(defonce ^:private in-flight
  ;; request-id → request-handle map. The handle is implementation-specific
  ;; (CLJS: AbortController; JVM: CompletableFuture). The :abort-fn value
  ;; is the no-arg fn the runtime calls to cancel.
  (atom {}))

(defn- record-in-flight! [request-id handle]
  (when request-id
    (swap! in-flight assoc request-id handle))
  nil)

(defn- clear-in-flight! [request-id]
  (when request-id
    (swap! in-flight dissoc request-id))
  nil)

(defn- lookup-in-flight [request-id]
  (when request-id
    (get @in-flight request-id)))

(defn- supersede!
  "If a request is already in flight under `request-id`, abort it with
  `:reason :request-id-superseded`. Per Spec 014 §`:request-id` (internal)."
  [request-id]
  (when-let [prev (lookup-in-flight request-id)]
    (clear-in-flight! request-id)
    (try
      ((:abort-fn prev) :request-id-superseded)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn clear-all-in-flight!
  "Test-time helper: drop the in-flight registry. Test fixtures use this
  between runs."
  []
  (reset! in-flight {})
  nil)

;; ---- per-frame request interceptor chain (rf2-6y3q) -----------------------
;;
;; Per Spec 014 §Middleware: each frame has an ordered chain of
;; request-side interceptors that fire before `:rf.http/managed` issues a
;; request. The shape matches the event-handler interceptor idiom — each
;; interceptor is a map `{:id <kw> :before (fn [ctx] ctx')}` — so authors
;; reuse what they already know.
;;
;; The `ctx` map carried through the chain has the documented shape:
;;
;;   {:request <request-map>      ;; the :request map from the args
;;    :args    <full-args-map>    ;; the full :rf.http/managed args
;;    :frame   <frame-id>         ;; resolved frame id
;;    :event   <origin-event>}    ;; originating event vector or nil
;;
;; A `:before` fn returns the (possibly-modified) ctx. The runtime
;; threads the chain in registration order; the final `:request` is what
;; the transport ships. After-style interceptors (response transforms)
;; are out of scope for v1; the slot `:after` is reserved for future
;; extension.
;;
;; Storage is per-frame in a `defonce` atom keyed `frame-id → [interceptor ...]`,
;; mirroring the `re-frame.flows/flows` pattern. Frame-scoped: an
;; interceptor registered against frame A does not fire for a request
;; dispatched from frame B.

(defonce
  ^{:doc "frame-id → vector of {:id :before} interceptor maps. Per-frame
  so each frame's HTTP middleware chain is isolated. Order is
  registration-order; clearing an id and re-registering re-appends to
  the end."}
  interceptors
  (atom {}))

(defn- valid-interceptor?
  [m]
  (and (map? m)
       (keyword? (:id m))
       (fn? (:before m))))

(defn reg-http-interceptor
  "Register a request-side HTTP interceptor on a frame's `:rf.http/managed`
  middleware chain. Per Spec 014 §Middleware.

  The interceptor map shape is:

    {:frame  <frame-id>            ;; default :rf/default
     :id     <keyword>              ;; required, addressable for clear
     :before (fn [ctx] ctx')}       ;; required, request-side transform

  `ctx` carries `:request` (the request map), `:args` (the full
  `:rf.http/managed` args), `:frame` (the frame-id), and `:event` (the
  originating event vector). The fn returns a (possibly-modified) ctx.

  Re-registering an id replaces the slot in place (keeping registration
  order). Order is preserved across replace; first registration wins
  for position.

  Throws `:rf.error/http-bad-interceptor` if the shape is invalid."
  [{:keys [frame id before] :as interceptor}]
  (when-not (valid-interceptor? interceptor)
    (throw (ex-info ":rf.error/http-bad-interceptor"
                    {:where    'reg-http-interceptor
                     :recovery :no-recovery
                     :received interceptor
                     :reason   "interceptor must be a map with :id (keyword) and :before (fn)"})))
  (let [frame-id (or frame :rf/default)
        slot     (cond-> {:id id}
                   before (assoc :before before))]
    (swap! interceptors update frame-id
           (fn [chain]
             (let [chain (or chain [])
                   idx   (->> chain
                              (keep-indexed (fn [i v] (when (= (:id v) id) i)))
                              first)]
               (if idx
                 (assoc chain idx slot)
                 (conj chain slot)))))
    (when interop/debug-enabled?
      (trace/emit! :info :rf.http.interceptor/registered
                   {:frame frame-id
                    :id    id}))
    id))

(defn clear-http-interceptor
  "Unregister an HTTP interceptor by id from a frame's chain.

  Single-arity: clear by id on `:rf/default`.
  Two-arity: clear by id on the named frame.
  No-arg form not supported — explicit ids only."
  ([id] (clear-http-interceptor :rf/default id))
  ([frame id]
   (let [frame-id (or frame :rf/default)
         existed? (some? (some (fn [v] (when (= (:id v) id) v))
                               (get @interceptors frame-id)))]
     (swap! interceptors update frame-id
            (fn [chain]
              (vec (remove (fn [v] (= (:id v) id)) chain))))
     (when (and existed? interop/debug-enabled?)
       (trace/emit! :info :rf.http.interceptor/cleared
                    {:frame frame-id
                     :id    id}))
     id)))

(defn clear-all-http-interceptors!
  "Test-time helper: drop the per-frame interceptor registry."
  []
  (reset! interceptors {})
  nil)

(defn- run-interceptor-chain!
  "Walk the registration-order interceptor chain for `frame-id`, threading
  `ctx` through each `:before`. Returns the final ctx, or throws
  `:rf.error/http-interceptor-failed` if any `:before` throws."
  [frame-id ctx]
  (let [chain (get @interceptors frame-id)]
    (reduce
      (fn [acc {:keys [id before]}]
        (if before
          (try
            (let [out (before acc)]
              (if (map? out)
                out
                (throw (ex-info "interceptor :before did not return a ctx map"
                                {:returned out}))))
            (catch #?(:clj Throwable :cljs :default) t
              (let [data (ex-info ":rf.error/http-interceptor-failed"
                                  {:where    'run-http-interceptor-chain!
                                   :recovery :no-recovery
                                   :frame    frame-id
                                   :interceptor-id id
                                   :url      (get-in acc [:request :url])
                                   :cause    #?(:clj  (.getMessage ^Throwable t)
                                                :cljs (.-message t))})]
                (when interop/debug-enabled?
                  (trace/emit-error! :rf.error/http-interceptor-failed
                                     (ex-data data)))
                (throw data))))
          acc))
      ctx
      chain)))

;; ---- query string + URL helpers -------------------------------------------

(defn- url-encode
  [s]
  #?(:clj  (-> (URLEncoder/encode (str s) "UTF-8")
               (.replace "+" "%20"))
     :cljs (js/encodeURIComponent (str s))))

(defn- params->query
  "Encode a `:params` map as a query string (no leading `?`)."
  [params]
  (->> params
       (map (fn [[k v]]
              (str (url-encode (if (keyword? k) (name k) (str k)))
                   "="
                   (url-encode v))))
       (str/join "&")))

(defn- merge-params
  "Merge `:params` onto `:url`. If the URL already has a `?`, append with `&`."
  [url params]
  (if (seq params)
    (let [qs (params->query params)
          sep (if (str/includes? url "?") "&" "?")]
      (str url sep qs))
    url))

;; ---- body encoding --------------------------------------------------------

(defn- realise-body
  "Per Spec 014 §Body encoding: if `:body` is a thunk, invoke it. Each
  attempt re-invokes the thunk to obtain a fresh handle."
  [body]
  (if (fn? body) (body) body))

(defn- json-stringify [v]
  #?(:clj  (let [write (try (requiring-resolve 'cheshire.core/generate-string)
                            (catch Throwable _ nil))]
             (if write
               (write v)
               (pr-str v)))
     :cljs (js/JSON.stringify (clj->js v))))

#?(:clj
   (defn- json-read*
     "Tiny pure-Clojure JSON reader. Returns Clojure data with keyword
     keys for objects. Sufficient for the on-the-wire shapes :rf.http/managed
     decodes; not a full RFC-8259 parser. Used only when Cheshire isn't
     on the classpath."
     [^String s]
     (let [n (count s)
           p (atom 0)]
       (letfn [(peek-c [] (when (< @p n) (.charAt s @p)))
               (skip-ws [] (while (let [c (peek-c)]
                                    (and c (Character/isWhitespace c)))
                             (swap! p inc)))
               (read-string-lit []
                 (swap! p inc)                         ;; opening "
                 (let [sb (StringBuilder.)]
                   (loop []
                     (let [c (peek-c)]
                       (cond
                         (nil? c) (throw (ex-info "unterminated string" {}))
                         (= c \")  (do (swap! p inc) (.toString sb))
                         (= c \\)
                         (do (swap! p inc)
                             (let [esc (peek-c)]
                               (swap! p inc)
                               (.append sb (case esc
                                             \"  \"
                                             \\ \\
                                             \/ \/
                                             \b \backspace
                                             \f \formfeed
                                             \n \newline
                                             \r \return
                                             \t \tab
                                             \u (let [hex (subs s @p (+ @p 4))]
                                                  (swap! p + 4)
                                                  (char (Integer/parseInt hex 16)))
                                             esc))
                               (recur)))
                         :else
                         (do (.append sb c) (swap! p inc) (recur)))))))
               (read-number []
                 (let [start @p]
                   (while (let [c (peek-c)]
                            (and c (or (Character/isDigit c)
                                       (#{\- \+ \. \e \E} c))))
                     (swap! p inc))
                   (let [t (subs s start @p)]
                     (if (or (.contains t ".") (.contains t "e") (.contains t "E"))
                       (Double/parseDouble t)
                       (Long/parseLong t)))))
               (read-keyword-tok []
                 (cond
                   (.startsWith (subs s @p) "true")  (do (swap! p + 4) true)
                   (.startsWith (subs s @p) "false") (do (swap! p + 5) false)
                   (.startsWith (subs s @p) "null")  (do (swap! p + 4) nil)
                   :else (throw (ex-info "bad token" {:at @p}))))
               (read-array []
                 (swap! p inc)                          ;; [
                 (skip-ws)
                 (if (= \] (peek-c))
                   (do (swap! p inc) [])
                   (loop [acc []]
                     (skip-ws)
                     (let [v (read-val)
                           _ (skip-ws)
                           c (peek-c)]
                       (cond
                         (= c \,) (do (swap! p inc) (recur (conj acc v)))
                         (= c \]) (do (swap! p inc) (conj acc v))
                         :else (throw (ex-info "expected , or ]" {:at @p})))))))
               (read-object []
                 (swap! p inc)                          ;; {
                 (skip-ws)
                 (if (= \} (peek-c))
                   (do (swap! p inc) {})
                   (loop [acc {}]
                     (skip-ws)
                     (let [k (keyword (read-string-lit))
                           _ (skip-ws)
                           _ (when (not= \: (peek-c))
                               (throw (ex-info "expected :" {:at @p})))
                           _ (swap! p inc)
                           _ (skip-ws)
                           v (read-val)
                           _ (skip-ws)
                           c (peek-c)
                           acc' (assoc acc k v)]
                       (cond
                         (= c \,) (do (swap! p inc) (recur acc'))
                         (= c \}) (do (swap! p inc) acc')
                         :else (throw (ex-info "expected , or }" {:at @p})))))))
               (read-val []
                 (skip-ws)
                 (let [c (peek-c)]
                   (cond
                     (= c \")  (read-string-lit)
                     (= c \{)  (read-object)
                     (= c \[)  (read-array)
                     (or (= c \-) (and c (Character/isDigit c))) (read-number)
                     :else     (read-keyword-tok))))]
         (skip-ws)
         (read-val)))))

(defn- json-parse [s]
  #?(:clj  (let [read (try (requiring-resolve 'cheshire.core/parse-string)
                           (catch Throwable _ nil))]
             (cond
               read       (read s true)
               (string? s) (try (json-read* s)
                                (catch Throwable _ s))
               :else      s))
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))

(defn- form-encode-body
  "URL-encoded form body from a Clojure map."
  [m]
  (params->query m))

(defn- encode-body
  "Per Spec 014 §Body encoding. Returns a tuple `[encoded-body content-type]`.
  `content-type` may be nil — the caller decides whether to set the header."
  [body request-content-type]
  (cond
    (nil? body)
    [nil nil]

    (= request-content-type :json)
    [(json-stringify body) "application/json"]

    (= request-content-type :form)
    [(form-encode-body body) "application/x-www-form-urlencoded"]

    (= request-content-type :text)
    [(str body) "text/plain"]

    (string? request-content-type)
    [(str body) request-content-type]

    ;; No explicit content-type — heuristics for clj colls (default to JSON).
    (or (map? body) (sequential? body) (set? body))
    [(json-stringify body) "application/json"]

    :else
    ;; pass-through (Blob / FormData / ArrayBuffer / pre-encoded string)
    [body nil]))

;; ---- decode pipeline ------------------------------------------------------

(defn- sniff-decoder
  "Per Spec 014 §`:auto`: sniff the response Content-Type header."
  [content-type]
  (let [ct (some-> content-type str/lower-case)]
    (cond
      (and ct (str/includes? ct "application/json")) :json
      (and ct (str/starts-with? ct "text/"))         :text
      :else                                          :blob)))

(defn- malli-decode
  "Run a Malli schema's `decode` over `value`, falling back to plain
  validate-or-throw if the transformer pipeline is unavailable. Throws
  on failure so the caller can classify as `:rf.http/decode-failure`."
  [schema value]
  (let [decode #?(:clj  (try (requiring-resolve 'malli.core/decode)
                             (catch Throwable _ nil))
                  :cljs (try (resolve 'malli.core/decode)
                             (catch :default _ nil)))
        transformer #?(:clj  (try (requiring-resolve 'malli.transform/json-transformer)
                                  (catch Throwable _ nil))
                       :cljs (try (resolve 'malli.transform/json-transformer)
                                  (catch :default _ nil)))
        validate #?(:clj  (try (requiring-resolve 'malli.core/validate)
                               (catch Throwable _ nil))
                    :cljs (try (resolve 'malli.core/validate)
                               (catch :default _ nil)))
        decoded (cond
                  (and decode transformer)
                  #?(:clj  ((deref decode) schema value ((deref transformer)))
                     :cljs (decode schema value (transformer)))
                  decode
                  #?(:clj  ((deref decode) schema value nil)
                     :cljs (decode schema value nil))
                  :else value)]
    (when validate
      (when-not #?(:clj  ((deref validate) schema decoded)
                   :cljs (validate schema decoded))
        (throw (ex-info "schema validation failed"
                        {:schema schema :value decoded :malli-error? true}))))
    decoded))

(defn- maybe-emit-decode-defaulted!
  "Per Spec 014 §`:auto`: emit `:rf.warning/decode-defaulted` when the
  user did NOT supply `:decode`."
  [{:keys [decode-supplied? request-id url content-type resolved-decoder]}]
  (when (and (not decode-supplied?) interop/debug-enabled?)
    (trace/emit! :warning :rf.warning/decode-defaulted
                 {:request-id       request-id
                  :url              url
                  :content-type     content-type
                  :resolved-decoder resolved-decoder})))

(defn- decode-response-body
  "Per Spec 014 §Decoding. Returns the decoded value or throws an
  ex-info that the caller maps to `:rf.http/decode-failure`."
  [{:keys [body-text headers decode decode-supplied? request-id url]}]
  (let [content-type (or (get headers "content-type")
                         (get headers "Content-Type"))
        decoder      (cond
                       (nil? decode)        :auto
                       (= :auto decode)     :auto
                       :else                decode)
        resolved     (cond
                       (= :auto decoder) (sniff-decoder content-type)
                       :else             decoder)]
    (when (or (= :auto decoder) (and (not decode-supplied?) (= decode :auto)))
      (maybe-emit-decode-defaulted!
        {:decode-supplied? decode-supplied?
         :request-id       request-id
         :url              url
         :content-type     content-type
         :resolved-decoder (cond
                             (keyword? resolved) resolved
                             :else               :auto)}))
    (cond
      (fn? decoder)
      (decoder body-text headers)

      (= :json resolved)
      (json-parse body-text)

      (= :text resolved)
      body-text

      (= :blob resolved)
      body-text

      (= :array-buffer resolved)
      body-text

      (= :form-data resolved)
      body-text

      ;; Malli schema (or anything keyword-like that isn't recognised above).
      (some? resolved)
      (let [parsed (try (json-parse body-text)
                        (catch #?(:clj Throwable :cljs :default) _ body-text))]
        (malli-decode resolved parsed))

      :else
      body-text)))

;; ---- :accept normalisation ------------------------------------------------

(defn- default-accept-fn
  "Per Spec 014 §`:accept` — domain-failure normalisation. The default
  `:accept` returns `{:ok decoded}` for 2xx, `{:failure ...}` otherwise."
  [{:keys [status]}]
  (fn [decoded]
    (cond
      (and (>= status 200) (< status 300))
      {:ok decoded}

      :else
      {:failure {:kind :http-status :status status :body decoded}})))

(defn- run-accept
  "Run the user's `:accept` fn (or the default) against `decoded`. Returns
  `{:ok v}` or `{:failure m}`."
  [accept-fn-or-nil decoded ctx]
  (let [accept (or accept-fn-or-nil (default-accept-fn ctx))]
    (accept decoded)))

;; ---- failure shape --------------------------------------------------------

(defn- failure-map
  "Build a failure map of the canonical shape per Spec 014 §Failure
  categories: `{:kind <:rf.http/...> ...kind-tags...}`."
  [kind tags]
  (assoc tags :kind kind))

;; ---- reply addressing -----------------------------------------------------

(defn- build-reply-event
  "Per Spec 014 §Reply addressing. Returns the event vector to dispatch,
  or nil when silenced.

  - explicit :on-success / :on-failure: append `{:kind ... ...}` as last
    arg of the supplied event vector.
  - default (omitted): originating event-id with `:rf/reply` merged into
    the original message.
  - explicit nil: silenced."
  [{:keys [origin-event explicit-on reply-payload]}]
  (let [supplied? (:supplied? explicit-on)
        value     (:value explicit-on)]
    (cond
      ;; explicit nil — silenced (caller passed :on-success nil / :on-failure nil)
      (and supplied? (nil? value))
      nil

      ;; explicit event vector — append payload
      (and supplied? (vector? value))
      (conj value reply-payload)

      ;; default — back to originator with :rf/reply merged into message
      :else
      (let [event-id (first origin-event)
            orig-msg (or (when (>= (count origin-event) 2) (nth origin-event 1))
                         {})
            merged   (cond
                       (map? orig-msg) (assoc orig-msg :rf/reply reply-payload)
                       :else           {:rf/reply reply-payload})]
        [event-id merged]))))

;; ---- backoff --------------------------------------------------------------

(defn- compute-backoff-ms
  "Per Spec 014 §Retry and backoff. Returns the delay in ms before the
  next attempt. `attempt` is the failing attempt number (1-based)."
  [{:keys [base-ms factor max-ms jitter] :or {base-ms 250 factor 2 max-ms 5000}} attempt]
  (let [raw     (* base-ms (Math/pow factor (max 0 (dec attempt))))
        capped  (min raw max-ms)
        jittered (if jitter
                   (let [j (* capped 0.25)
                         offset (* j (- 0.5 (rand)))]
                     (max 0 (+ capped offset)))
                   capped)]
    (long jittered)))

;; ---- transport (CLJS — Fetch) ---------------------------------------------

#?(:cljs
   (defn- ^:private fetch-headers->map [^js fetch-headers]
     (let [out #js {}]
       (.forEach fetch-headers
                 (fn [v k] (aset out k v)))
       (js->clj out))))

#?(:cljs
   (defn- cljs-fetch
     "Issue a single HTTP attempt via Fetch. Returns a Promise that resolves
     to `{:ok? bool :status N :status-text S :headers M :body-text S}` on
     transport-OK, or rejects with an ex-info classified by the caller."
     [{:keys [method url headers body credentials mode redirect cache referrer
              integrity timeout-ms abort-signal internal-controller]}]
     (let [init     #js {}
           _        (do (aset init "method" (str/upper-case (name method)))
                        (when (seq headers)
                          (let [h #js {}]
                            (doseq [[k v] headers] (aset h k v))
                            (aset init "headers" h)))
                        (when (some? body) (aset init "body" body))
                        (when credentials  (aset init "credentials" (name credentials)))
                        (when mode         (aset init "mode"        (name mode)))
                        (when redirect     (aset init "redirect"    (name redirect)))
                        (when cache        (aset init "cache"       (name cache)))
                        (when referrer     (aset init "referrer"    referrer))
                        (when integrity    (aset init "integrity"   integrity))
                        (cond
                          abort-signal        (aset init "signal" abort-signal)
                          internal-controller (aset init "signal" (.-signal internal-controller))))
           timeout-handle (atom nil)
           timeout-fired? (atom false)
           promise
           (-> (js/Promise.race
                 #js [(js/fetch url init)
                      (js/Promise. (fn [_ reject]
                                     (when (and timeout-ms internal-controller)
                                       (reset! timeout-handle
                                               (js/setTimeout
                                                 (fn []
                                                   (reset! timeout-fired? true)
                                                   (try (.abort internal-controller "timeout")
                                                        (catch :default _ nil))
                                                   (reject (ex-info "timeout"
                                                                    {:rf.http/timeout? true
                                                                     :elapsed-ms timeout-ms
                                                                     :limit-ms timeout-ms})))
                                                 timeout-ms)))))])
               (.then (fn [resp]
                        (when-let [h @timeout-handle] (js/clearTimeout h))
                        (-> (.text resp)
                            (.then (fn [body-text]
                                     {:ok? (.-ok resp)
                                      :status (.-status resp)
                                      :status-text (.-statusText resp)
                                      :headers (fetch-headers->map (.-headers resp))
                                      :body-text body-text}))))))]
       promise)))

;; ---- transport (JVM — java.net.http.HttpClient) --------------------------

#?(:clj
   (defonce ^:private jvm-http-client
     (delay (-> (HttpClient/newBuilder)
                (.connectTimeout (Duration/ofSeconds 10))
                (.build)))))

#?(:clj
   (defn- jvm-build-request
     [{:keys [method url headers body timeout-ms]}]
     (let [b (HttpRequest/newBuilder (URI/create url))
           publisher (cond
                       (nil? body) (HttpRequest$BodyPublishers/noBody)
                       (string? body) (HttpRequest$BodyPublishers/ofString ^String body)
                       (bytes? body) (HttpRequest$BodyPublishers/ofByteArray ^bytes body)
                       :else (HttpRequest$BodyPublishers/ofString ^String (str body)))]
       (.method b (str/upper-case (name method)) publisher)
       (when timeout-ms
         (.timeout b (Duration/ofMillis (long timeout-ms))))
       (doseq [[k v] headers]
         (try (.header b (str k) (str v))
              (catch Throwable _ nil)))
       (.build b))))

#?(:clj
   (defn- jvm-headers->map
     "Flatten Java HttpHeaders into a plain Clojure map of string->string."
     [^java.net.http.HttpHeaders hh]
     (into {}
           (for [[k vs] (.map hh)]
             [k (clojure.string/join "," vs)]))))

#?(:clj
   (defn- jvm-fetch
     "Issue a single HTTP attempt via java.net.http.HttpClient. Returns a
     CompletableFuture that completes with `{:ok? :status :status-text
     :headers :body-text}` or completes-exceptionally with an ex-info."
     [opts]
     (let [client ^HttpClient @jvm-http-client
           req    (jvm-build-request opts)
           future-resp (.sendAsync client req
                                   (HttpResponse$BodyHandlers/ofString))]
       (.thenApply future-resp
                   (reify java.util.function.Function
                     (apply [_ resp]
                       (let [^HttpResponse r resp
                             status (.statusCode r)]
                         {:ok? (and (>= status 200) (< status 300))
                          :status status
                          :status-text ""
                          :headers (jvm-headers->map (.headers r))
                          :body-text (.body r)})))))))

;; ---- per-row CLJS-only-key tracing on JVM ---------------------------------

#?(:clj
   (defn- emit-cljs-only-skipped! [k url]
     (when interop/debug-enabled?
       (trace/emit! :warning :rf.http/cljs-only-key-ignored-on-jvm
                    {:key k :url url}))))

#?(:clj
   (defn- check-cljs-only-keys! [{:keys [request abort-signal] :as args}]
     (let [url (:url request)]
       (doseq [k [:mode :cache :referrer :integrity]]
         (when (contains? request k)
           (emit-cljs-only-skipped! k url)))
       (when abort-signal
         (emit-cljs-only-skipped! :abort-signal url)))))

;; ---- attempt-and-retry loop (CLJS) ----------------------------------------

#?(:cljs
   (declare run-attempt-cljs!))

#?(:cljs
   (defn- dispatch-reply!
     [{:keys [origin-event explicit-on-success explicit-on-failure
              kind reply-payload frame]}]
     (let [explicit (case kind
                      :success explicit-on-success
                      :failure explicit-on-failure)
           ev (build-reply-event {:origin-event origin-event
                                  :explicit-on  explicit
                                  :reply-payload reply-payload
                                  :kind          kind})]
       (when ev
         (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
           (dispatch! ev (cond-> {} frame (assoc :frame frame))))))))

#?(:cljs
   (defn- finalise-success! [ctx accepted]
     (clear-in-flight! (:request-id ctx))
     (case (first (keys accepted))
       :ok      (dispatch-reply! (assoc ctx
                                        :kind :success
                                        :reply-payload {:kind :success
                                                        :value (:ok accepted)}))
       :failure (dispatch-reply! (assoc ctx
                                        :kind :failure
                                        :reply-payload {:kind :failure
                                                        :failure (assoc (failure-map :rf.http/accept-failure
                                                                                     {:detail (:failure accepted)
                                                                                      :decoded (:decoded ctx)})
                                                                        :request-id (:request-id ctx))})))))

#?(:cljs
   (defn- finalise-failure!
     "Final-failure dispatch (after retries exhausted or non-retriable)."
     [ctx failure]
     (clear-in-flight! (:request-id ctx))
     (when (and interop/debug-enabled?
                (= :rf.http/aborted (:kind failure)))
       ;; Aborted is its own category — the trace is a single error event.
       nil)
     (when interop/debug-enabled?
       (trace/emit-error! (:kind failure)
                          (assoc failure
                                 :request-id (:request-id ctx)
                                 :url        (:url ctx)
                                 :recovery   :no-recovery)))
     (dispatch-reply! (assoc ctx
                             :kind          :failure
                             :reply-payload {:kind    :failure
                                             :failure failure}))))

#?(:cljs
   (defn- maybe-retry!
     "Decide between retry, immediate-final-failure, and successful-completion.
     `failure` is the failure map for the just-finished attempt."
     [ctx failure]
     (let [{:keys [retry attempt request-id]} ctx
           {:keys [on max-attempts backoff]} retry
           on-set (or on #{})
           kind   (:kind failure)
           can-retry? (and (some? max-attempts)
                           (> max-attempts attempt)
                           (contains? on-set kind)
                           (not= :rf.http/aborted kind))]
       (if can-retry?
         (let [delay-ms (compute-backoff-ms (or backoff {}) attempt)]
           (when interop/debug-enabled?
             (trace/emit! :info :rf.http/retry-attempt
                          {:request-id   request-id
                           :url          (:url ctx)
                           :attempt      attempt
                           :max-attempts max-attempts
                           :failure      failure
                           :next-backoff-ms delay-ms}))
           (interop/set-timeout!
             (fn [] (run-attempt-cljs! (update ctx :attempt inc)))
             delay-ms))
         (do
           ;; Final attempt: emit retry-attempt with nil next-backoff if any retries occurred.
           (when (and interop/debug-enabled?
                      (some? max-attempts)
                      (> max-attempts 1))
             (trace/emit! :info :rf.http/retry-attempt
                          {:request-id      request-id
                           :url             (:url ctx)
                           :attempt         attempt
                           :max-attempts    max-attempts
                           :failure         failure
                           :next-backoff-ms nil}))
           (finalise-failure! ctx failure))))))

#?(:cljs
   (defn- classify-cljs-error
     "Map a Fetch rejection / promise-error to a `:rf.http/*` failure shape."
     [^js err]
     (let [data (when (.-data err) (ex-data err))]
       (cond
         (:rf.http/timeout? data)
         (failure-map :rf.http/timeout
                      {:elapsed-ms (:elapsed-ms data)
                       :limit-ms   (:limit-ms data)})

         (or (= "AbortError" (.-name err))
             (:rf.http/aborted? data))
         (failure-map :rf.http/aborted
                      {:request-id (:request-id data)
                       :reason     (or (:reason data) :user)})

         :else
         (failure-map :rf.http/transport
                      {:message (or (.-message err) (str err))
                       :cause   err})))))

#?(:cljs
   (defn- run-attempt-cljs!
     "Issue one Fetch attempt, then dispatch reply or retry."
     [ctx]
     (let [{:keys [request decode decode-supplied? accept timeout-ms
                   request-id abort-signal]} ctx
           method (or (:method request) :get)
           url    (merge-params (:url request) (:params request))
           [enc-body ct] (encode-body (realise-body (:body request))
                                      (:request-content-type request))
           headers (cond-> (or (:headers request) {})
                     (and ct (not (get (or (:headers request) {}) "content-type"))
                          (not (get (or (:headers request) {}) "Content-Type")))
                     (assoc "Content-Type" ct))
           internal-controller (when-not abort-signal (js/AbortController.))]
       ;; Register the abort handle.
       (record-in-flight! request-id
                          {:abort-fn (fn [reason]
                                       (try
                                         (cond
                                           internal-controller
                                           (.abort internal-controller (clj->js reason))
                                           ;; External signal — user owns it.
                                           :else nil)
                                         (finalise-failure!
                                           (assoc ctx :url url)
                                           (failure-map :rf.http/aborted
                                                        {:request-id request-id
                                                         :reason     reason}))
                                         (catch :default _ nil)))
                           :url url})
       (-> (cljs-fetch {:method method
                        :url url
                        :headers headers
                        :body enc-body
                        :credentials (:credentials request)
                        :mode (:mode request)
                        :redirect (:redirect request)
                        :cache (:cache request)
                        :referrer (:referrer request)
                        :integrity (:integrity request)
                        :timeout-ms timeout-ms
                        :abort-signal abort-signal
                        :internal-controller internal-controller})
           (.then (fn [{:keys [ok? status status-text headers body-text]}]
                    (let [ctx' (assoc ctx :url url)]
                      ;; Per Spec 014 §Failure categories: status classification
                      ;; runs BEFORE decode. 4xx/5xx route to :rf.http/http-4xx /
                      ;; :rf.http/http-5xx with the raw body-text — decode never
                      ;; fires on a non-success response, so an HTML 404 from a
                      ;; JSON endpoint classifies as :rf.http/http-4xx (not
                      ;; :rf.http/decode-failure). Decode runs only on 2xx; if
                      ;; that fails the failure category is :rf.http/decode-failure.
                      (cond
                        (and (>= status 400) (< status 500))
                        (maybe-retry! ctx'
                                      (failure-map :rf.http/http-4xx
                                                   {:status status
                                                    :status-text status-text
                                                    :body body-text
                                                    :headers headers}))

                        (>= status 500)
                        (maybe-retry! ctx'
                                      (failure-map :rf.http/http-5xx
                                                   {:status status
                                                    :status-text status-text
                                                    :body body-text
                                                    :headers headers}))

                        ok?
                        (try
                          (let [decoded (decode-response-body
                                          {:body-text body-text
                                           :headers   headers
                                           :decode    decode
                                           :decode-supplied? decode-supplied?
                                           :request-id request-id
                                           :url        url})
                                accepted (run-accept accept decoded {:status status})]
                            (cond
                              (contains? accepted :ok)
                              (finalise-success! ctx' accepted)

                              (contains? accepted :failure)
                              (finalise-failure!
                                ctx'
                                (failure-map :rf.http/accept-failure
                                             {:detail  (:failure accepted)
                                              :decoded decoded
                                              :request-id request-id}))))
                          (catch :default e
                            (let [d (ex-data e)]
                              (maybe-retry!
                                ctx'
                                (failure-map :rf.http/decode-failure
                                             {:body-text body-text
                                              :cause     (.-message e)
                                              :malli-error? (boolean (:malli-error? d))})))))

                        :else
                        ;; Non-2xx that didn't fall in 4xx/5xx (e.g., 1xx/3xx
                        ;; that the runtime didn't follow) — surface as 4xx-shaped
                        ;; failure with the raw body-text.
                        (finalise-failure!
                          ctx'
                          (failure-map :rf.http/http-4xx
                                       {:status status
                                        :status-text status-text
                                        :body body-text
                                        :headers headers}))))))
           (.catch (fn [err]
                     (let [failure (classify-cljs-error err)]
                       (maybe-retry! (assoc ctx :url url) failure))))))))

;; ---- attempt-and-retry loop (JVM) -----------------------------------------

#?(:clj
   (declare run-attempt-jvm!))

#?(:clj
   (defn- dispatch-reply-jvm!
     [{:keys [origin-event explicit-on-success explicit-on-failure
              kind reply-payload frame]}]
     (let [explicit (case kind
                      :success explicit-on-success
                      :failure explicit-on-failure)
           ev (build-reply-event {:origin-event origin-event
                                  :explicit-on  explicit
                                  :reply-payload reply-payload
                                  :kind          kind})]
       (when ev
         (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
           (dispatch! ev (cond-> {} frame (assoc :frame frame))))))))

#?(:clj
   (defn- finalise-success-jvm! [ctx accepted]
     (clear-in-flight! (:request-id ctx))
     (cond
       (contains? accepted :ok)
       (dispatch-reply-jvm! (assoc ctx
                                   :kind :success
                                   :reply-payload {:kind  :success
                                                   :value (:ok accepted)}))

       (contains? accepted :failure)
       (dispatch-reply-jvm! (assoc ctx
                                   :kind :failure
                                   :reply-payload {:kind    :failure
                                                   :failure (failure-map :rf.http/accept-failure
                                                                         {:detail     (:failure accepted)
                                                                          :decoded    (:decoded ctx)
                                                                          :request-id (:request-id ctx)})})))))

#?(:clj
   (defn- finalise-failure-jvm!
     [ctx failure]
     (clear-in-flight! (:request-id ctx))
     (when interop/debug-enabled?
       (trace/emit-error! (:kind failure)
                          (assoc failure
                                 :request-id (:request-id ctx)
                                 :url        (:url ctx)
                                 :recovery   :no-recovery)))
     (dispatch-reply-jvm! (assoc ctx
                                 :kind          :failure
                                 :reply-payload {:kind    :failure
                                                 :failure failure}))))

#?(:clj
   (defn- maybe-retry-jvm!
     [ctx failure]
     (let [{:keys [retry attempt request-id]} ctx
           {:keys [on max-attempts backoff]} retry
           on-set (or on #{})
           kind   (:kind failure)
           can-retry? (and (some? max-attempts)
                           (> max-attempts attempt)
                           (contains? on-set kind)
                           (not= :rf.http/aborted kind))]
       (if can-retry?
         (let [delay-ms (compute-backoff-ms (or backoff {}) attempt)]
           (when interop/debug-enabled?
             (trace/emit! :info :rf.http/retry-attempt
                          {:request-id   request-id
                           :url          (:url ctx)
                           :attempt      attempt
                           :max-attempts max-attempts
                           :failure      failure
                           :next-backoff-ms delay-ms}))
           ;; Sleep + retry on a worker thread to avoid blocking the caller.
           (.start (Thread.
                     ^Runnable
                     (fn []
                       (try (Thread/sleep delay-ms) (catch InterruptedException _ nil))
                       (run-attempt-jvm! (update ctx :attempt inc))))))
         (do
           (when (and interop/debug-enabled?
                      (some? max-attempts)
                      (> max-attempts 1))
             (trace/emit! :info :rf.http/retry-attempt
                          {:request-id      request-id
                           :url             (:url ctx)
                           :attempt         attempt
                           :max-attempts    max-attempts
                           :failure         failure
                           :next-backoff-ms nil}))
           (finalise-failure-jvm! ctx failure))))))

#?(:clj
   (defn- classify-jvm-error [^Throwable t]
     (let [cause (or (.getCause t) t)
           msg   (.getMessage cause)
           cls   (.getName (class cause))]
       (cond
         (or (instance? java.net.http.HttpTimeoutException cause)
             (and msg (str/includes? (str/lower-case msg) "timed out")))
         (failure-map :rf.http/timeout {:elapsed-ms nil :limit-ms nil :message msg})

         (or (instance? java.util.concurrent.CancellationException cause)
             (and msg (str/includes? (str/lower-case msg) "abort")))
         (failure-map :rf.http/aborted {:reason :user :message msg})

         :else
         (failure-map :rf.http/transport {:message msg :cause cls})))))

#?(:clj
   (defn- run-attempt-jvm!
     [ctx]
     (let [{:keys [request decode decode-supplied? accept timeout-ms request-id]} ctx
           method (or (:method request) :get)
           url    (merge-params (:url request) (:params request))
           [enc-body ct] (encode-body (realise-body (:body request))
                                      (:request-content-type request))
           headers (cond-> (or (:headers request) {})
                     (and ct (not (get (or (:headers request) {}) "content-type"))
                          (not (get (or (:headers request) {}) "Content-Type")))
                     (assoc "Content-Type" ct))
           ctx' (assoc ctx :url url)
           handle {:abort-fn (fn [_reason]
                               (finalise-failure-jvm!
                                 ctx'
                                 (failure-map :rf.http/aborted
                                              {:request-id request-id
                                               :reason     :user})))
                   :url url}]
       (record-in-flight! request-id handle)
       (try
         (let [^CompletableFuture cf
               (jvm-fetch {:method method
                           :url url
                           :headers headers
                           :body enc-body
                           :timeout-ms timeout-ms})]
           (.whenComplete cf
                          (reify java.util.function.BiConsumer
                            (accept [_ result throwable]
                              (cond
                                throwable
                                (maybe-retry-jvm! ctx' (classify-jvm-error throwable))

                                :else
                                (let [{:keys [ok? status status-text headers body-text]} result]
                                  ;; Per Spec 014 §Failure categories: status
                                  ;; classification runs BEFORE decode. 4xx/5xx
                                  ;; route to :rf.http/http-4xx / :rf.http/http-5xx
                                  ;; with the raw body-text — decode never fires
                                  ;; on a non-success response, so an HTML 404 from
                                  ;; a JSON endpoint classifies as :rf.http/http-4xx
                                  ;; (not :rf.http/decode-failure). Decode runs
                                  ;; only on 2xx; if that fails the failure category
                                  ;; is :rf.http/decode-failure.
                                  (cond
                                    (and (>= status 400) (< status 500))
                                    (maybe-retry-jvm!
                                      ctx'
                                      (failure-map :rf.http/http-4xx
                                                   {:status status
                                                    :status-text status-text
                                                    :body body-text
                                                    :headers headers}))

                                    (>= status 500)
                                    (maybe-retry-jvm!
                                      ctx'
                                      (failure-map :rf.http/http-5xx
                                                   {:status status
                                                    :status-text status-text
                                                    :body body-text
                                                    :headers headers}))

                                    ok?
                                    (try
                                      (let [decoded (decode-response-body
                                                      {:body-text body-text
                                                       :headers   headers
                                                       :decode    decode
                                                       :decode-supplied? decode-supplied?
                                                       :request-id request-id
                                                       :url        url})
                                            accepted (run-accept accept decoded {:status status})]
                                        (finalise-success-jvm!
                                          (assoc ctx' :decoded decoded) accepted))
                                      (catch Throwable e
                                        (let [d (ex-data e)]
                                          (maybe-retry-jvm!
                                            ctx'
                                            (failure-map :rf.http/decode-failure
                                                         {:body-text body-text
                                                          :cause (.getMessage e)
                                                          :malli-error? (boolean (:malli-error? d))})))))

                                    :else
                                    ;; Non-2xx that didn't fall in 4xx/5xx (e.g.
                                    ;; 1xx/3xx that the runtime didn't follow) —
                                    ;; surface as 4xx-shaped failure with the raw
                                    ;; body-text.
                                    (finalise-failure-jvm!
                                      ctx'
                                      (failure-map :rf.http/http-4xx
                                                   {:status status
                                                    :status-text status-text
                                                    :body body-text
                                                    :headers headers})))))))))
         (catch Throwable t
           (maybe-retry-jvm! ctx' (classify-jvm-error t)))))))

;; ---- the public fxs -------------------------------------------------------

(defn- normalise-args
  "Validate + normalise the args map. Returns a context ready for the
  per-host attempt loop."
  [{:keys [request decode accept retry timeout-ms
           on-success on-failure request-id abort-signal]
    :or   {timeout-ms 30000}
    :as   args-map}
   frame-ctx]
  (let [origin-event (or (:event frame-ctx)
                         (:rf.http/origin-event args-map)
                         [:rf.http/managed])
        frame        (or (:frame frame-ctx) :rf/default)]
    {:request           request
     :decode            decode
     :decode-supplied?  (some? decode)
     :accept            accept
     :retry             retry
     :timeout-ms        timeout-ms
     :origin-event      origin-event
     :explicit-on-success
     {:supplied? (contains? args-map :on-success)
      :value     on-success}
     :explicit-on-failure
     {:supplied? (contains? args-map :on-failure)
      :value     on-failure}
     :request-id        request-id
     :abort-signal      abort-signal
     :frame             frame
     :attempt           1}))

(defn- managed-handler
  "The public `:rf.http/managed` fx body. `frame-ctx` carries `:frame`
  and (when threaded by the runtime, per the do-fx 5-arity) `:event` —
  the originating event vector used for default reply addressing per
  Spec 014 §Reply addressing.

  Per Spec 014 §Middleware (rf2-6y3q): before normalising args, the
  per-frame interceptor chain is walked. Each `:before` transforms a
  ctx `{:request :args :frame :event}`; the runtime threads its return
  value through the rest of the chain. A throw inside any `:before`
  classifies as `:rf.error/http-interceptor-failed`; the request is
  not dispatched."
  [frame-ctx args-map]
  #?(:clj (check-cljs-only-keys! args-map))
  (let [frame-id     (or (:frame frame-ctx) :rf/default)
        origin-event (or (:event frame-ctx)
                         (:rf.http/origin-event args-map)
                         [:rf.http/managed])
        ctx0         {:request (:request args-map)
                      :args    args-map
                      :frame   frame-id
                      :event   origin-event}
        ctx          (run-interceptor-chain! frame-id ctx0)
        args-map'    (assoc args-map :request (:request ctx))
        normalised   (normalise-args args-map' frame-ctx)
        request-id   (:request-id normalised)]
    (when request-id (supersede! request-id))
    #?(:cljs (run-attempt-cljs! normalised)
       :clj  (run-attempt-jvm!  normalised))
    nil))

(defn- managed-abort-handler
  "Public `:rf.http/managed-abort` fx. Args is the request-id (any value)."
  [_frame-ctx request-id]
  (when-let [handle (lookup-in-flight request-id)]
    (clear-in-flight! request-id)
    (try ((:abort-fn handle) :user)
         (catch #?(:clj Throwable :cljs :default) _ nil)))
  nil)

(defn- origin-event-from
  [frame-ctx args-map]
  (or (:event frame-ctx)
      (:rf.http/origin-event args-map)
      [:rf.http/managed]))

(defn- canned-success-handler
  "Stub fx — synthesises a success reply per Spec 014 §Testing."
  [frame-ctx args-map]
  (let [{:keys [on-success]} args-map
        value (get args-map :value {:stubbed true})
        reply {:kind :success :value value}
        ev    (build-reply-event {:origin-event (origin-event-from frame-ctx args-map)
                                  :explicit-on  {:supplied? (contains? args-map :on-success)
                                                 :value     on-success}
                                  :reply-payload reply
                                  :kind          :success})]
    (when ev
      (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
        (dispatch! ev (cond-> {} (:frame frame-ctx) (assoc :frame (:frame frame-ctx))))))
    nil))

(defn- canned-failure-handler
  [frame-ctx args-map]
  (let [{:keys [on-failure]} args-map
        kind  (or (:kind args-map) :rf.http/transport)
        tags  (or (:tags args-map) {})
        failure (assoc tags :kind kind)
        reply {:kind :failure :failure failure}
        ev    (build-reply-event {:origin-event (origin-event-from frame-ctx args-map)
                                  :explicit-on  {:supplied? (contains? args-map :on-failure)
                                                 :value     on-failure}
                                  :reply-payload reply
                                  :kind          :failure})]
    (when ev
      (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
        (dispatch! ev (cond-> {} (:frame frame-ctx) (assoc :frame (:frame frame-ctx))))))
    nil))

;; ---- registration ---------------------------------------------------------

(fx/reg-fx :rf.http/managed
           {:doc "Spec 014 — managed HTTP request."}
           managed-handler)

(fx/reg-fx :rf.http/managed-abort
           {:doc "Spec 014 — abort an in-flight :rf.http/managed by request-id."}
           managed-abort-handler)

;; The two canned-stub fxs are gated on `interop/debug-enabled?` so they
;; elide in production. Per Spec 014 §Testing — "Don't ship the canned-
;; stub fxs as production-eligible".
#?(:clj  (when interop/debug-enabled?
           (fx/reg-fx :rf.http/managed-canned-success
                      {:doc "Spec 014 — synthesised success reply (test stub)."}
                      canned-success-handler)
           (fx/reg-fx :rf.http/managed-canned-failure
                      {:doc "Spec 014 — synthesised failure reply (test stub)."}
                      canned-failure-handler))
   :cljs (do
           (fx/reg-fx :rf.http/managed-canned-success
                      {:doc "Spec 014 — synthesised success reply (test stub)."}
                      canned-success-handler)
           (fx/reg-fx :rf.http/managed-canned-failure
                      {:doc "Spec 014 — synthesised failure reply (test stub)."}
                      canned-failure-handler)))

;; ---- with-managed-request-stubs ------------------------------------------

(defn- stub-handler
  [stubs frame-ctx args-map]
  (let [req    (:request args-map)
        method (or (:method req) :get)
        url    (:url req)
        entry  (get stubs [method url])
        reply  (:reply entry)]
    (cond
      (and entry (contains? reply :ok))
      (canned-success-handler frame-ctx (assoc args-map :value (:ok reply)))

      (and entry (contains? reply :failure))
      (canned-failure-handler frame-ctx
                              (-> args-map
                                  (assoc :kind (or (:kind (:failure reply))
                                                   :rf.http/transport))
                                  (assoc :tags (dissoc (:failure reply) :kind))))

      :else
      (canned-failure-handler frame-ctx
                              (assoc args-map
                                     :kind :rf.http/transport
                                     :tags {:message "no stub matched"
                                            :method  method
                                            :url     url})))))

(def ^:private stub-fx-id :rf.http/managed-test-stub)

(defn install-managed-request-stubs!
  "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
  Registers a per-call fx-override target that consults `stubs` and
  synthesises the configured reply.

  Use with `:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}`
  on `dispatch-sync`, or wrap the test body via `with-managed-request-stubs`.

  Per Spec 014 §Testing — the framework ships canonical stub fxs."
  [stubs]
  (fx/reg-fx stub-fx-id
             {:doc "with-managed-request-stubs synthesised stub"}
             (fn [frame-ctx args-map]
               (stub-handler stubs frame-ctx args-map)))
  stub-fx-id)

(defn uninstall-managed-request-stubs!
  []
  (fx/unregister-fx stub-fx-id)
  nil)

(defn with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Test-time helper."
  [stubs thunk]
  (try
    (install-managed-request-stubs! stubs)
    (thunk)
    (finally
      (uninstall-managed-request-stubs!))))

#?(:clj
   (defmacro with-managed-request-stubs
     "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
     Installs a per-call fx-override on `:rf.http/managed` that consults
     the stub map, synthesises the configured reply, and runs `body`.

     Per Spec 014 §Testing."
     [stubs & body]
     `(with-managed-request-stubs* ~stubs (fn [] ~@body))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.core needs to call into the test-time helpers but per
;; rf2-5kpd ships in the core artefact — it cannot `:require` this
;; namespace because the http artefact is optional (apps that don't
;; issue any managed-HTTP requests don't carry it). Publish entry
;; points through the late-bind hook registry; consumers look the fns
;; up at call time. See re-frame.late-bind.

(late-bind/set-fn! :http/install-managed-request-stubs!   install-managed-request-stubs!)
(late-bind/set-fn! :http/uninstall-managed-request-stubs! uninstall-managed-request-stubs!)
(late-bind/set-fn! :http/with-managed-request-stubs*      with-managed-request-stubs*)
(late-bind/set-fn! :http/clear-all-in-flight!             clear-all-in-flight!)
;; rf2-6y3q — per-frame request-side interceptor chain (Spec 014 §Middleware).
(late-bind/set-fn! :http/reg-http-interceptor             reg-http-interceptor)
(late-bind/set-fn! :http/clear-http-interceptor           clear-http-interceptor)
(late-bind/set-fn! :http/clear-all-http-interceptors!     clear-all-http-interceptors!)
