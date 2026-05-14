(ns re-frame.http-encoding
  "Pure-fn helpers for the `:rf.http/managed` request/response pipeline.

  Extracted from `re-frame.http-managed` per rf2-3i9b. The fns here are
  fully pure — they touch no atoms and dispatch no events — so they are
  trivially testable and shared by the unified Fetch (CLJS) +
  `HttpClient` (JVM) transport in `re-frame.http-transport`.

  Surfaces:

  - URL / query-string encoding: `url-encode`, `params->query`,
    `merge-params`.
  - Body encoding: `realise-body`, `encode-body` (handles
    `:request-content-type` :json / :form / :text / explicit string,
    plus heuristics for raw Clojure colls).
  - Decode pipeline: `sniff-decoder`, `malli-decode`, `decode-response-
    body`, `maybe-emit-decode-defaulted!`.
  - `:accept` normalisation: `default-accept-fn`, `run-accept`.
  - Failure / reply addressing: `failure-map`, `build-reply-event`.
  - Backoff: `compute-backoff-ms`.

  Per Spec 014 §Body encoding, §Decoding, §`:auto`, §`:accept`,
  §Failure categories, §Reply addressing, §Retry and backoff."
  (:require [clojure.string  :as str]
            [re-frame.interop :as interop]
            [re-frame.http-privacy :as privacy]
            [re-frame.trace   :as trace]
            [re-frame.util-json :as util-json])
  #?(:clj (:import [java.net URLEncoder])))

;; ---- query string + URL helpers -------------------------------------------

(defn url-encode
  [s]
  #?(:clj  (-> (URLEncoder/encode (str s) "UTF-8")
               (.replace "+" "%20"))
     :cljs (js/encodeURIComponent (str s))))

(defn params->query
  "Encode a `:params` map as a query string (no leading `?`)."
  [params]
  (->> params
       (map (fn [[k v]]
              (str (url-encode (if (keyword? k) (name k) (str k)))
                   "="
                   (url-encode v))))
       (str/join "&")))

(defn merge-params
  "Merge `:params` onto `:url`. If the URL already has a `?`, append with `&`."
  [url params]
  (if (seq params)
    (let [qs (params->query params)
          sep (if (str/includes? url "?") "&" "?")]
      (str url sep qs))
    url))

;; ---- body encoding --------------------------------------------------------

(defn realise-body
  "Per Spec 014 §Body encoding: if `:body` is a thunk, invoke it. Each
  attempt re-invokes the thunk to obtain a fresh handle."
  [body]
  (if (fn? body) (body) body))

(defn- form-encode-body
  "URL-encoded form body from a Clojure map."
  [m]
  (params->query m))

(defn encode-body
  "Per Spec 014 §Body encoding. Returns a tuple `[encoded-body content-type]`.
  `content-type` may be nil — the caller decides whether to set the header."
  [body request-content-type]
  (cond
    (nil? body)
    [nil nil]

    (= request-content-type :json)
    [(util-json/json-stringify body) "application/json"]

    (= request-content-type :form)
    [(form-encode-body body) "application/x-www-form-urlencoded"]

    (= request-content-type :text)
    [(str body) "text/plain"]

    (string? request-content-type)
    [(str body) request-content-type]

    ;; No explicit content-type — heuristics for clj colls (default to JSON).
    (or (map? body) (sequential? body) (set? body))
    [(util-json/json-stringify body) "application/json"]

    :else
    ;; pass-through (Blob / FormData / ArrayBuffer / pre-encoded string)
    [body nil]))

;; ---- decode pipeline ------------------------------------------------------

(defn content-type-of
  "Per Spec 014 §Request envelope — HTTP header names are case-insensitive.
  Scan `headers` for a `content-type` key in any casing, returning the
  value (or nil). Tolerates keyword keys (rare, but used by some
  middlewares); ignores non-string non-keyword keys.

  Used by `decode-response-body` (response-side sniffing) and by the
  transport's request-side clash check. The JVM transport additionally
  normalises response headers to lower-case at the boundary
  (`jvm-headers->map`) to match the CLJS Fetch path — this helper is the
  belt to that braces: even a hand-constructed headers map with mixed
  casing (e.g. an interceptor `:before` that synthesises headers) is
  resolved correctly."
  [headers]
  (when (map? headers)
    (reduce-kv
      (fn [_ k v]
        (let [k-str (cond
                      (string? k)  k
                      (keyword? k) (name k)
                      :else        nil)]
          (if (and k-str (= "content-type" (str/lower-case k-str)))
            (reduced v)
            nil)))
      nil
      headers)))

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

(defn decode-response-body
  "Per Spec 014 §Decoding. Returns the decoded value or throws an
  ex-info that the caller maps to `:rf.http/decode-failure`."
  [{:keys [body-text headers decode decode-supplied? request-id url sensitive?]}]
  (let [content-type (content-type-of headers)
        decoder      (cond
                       (nil? decode)        :auto
                       (= :auto decode)     :auto
                       :else                decode)
        resolved     (cond
                       (= :auto decoder) (sniff-decoder content-type)
                       :else             decoder)]
    ;; Per Spec 014 §`:auto`: emit `:rf.warning/decode-defaulted` when
    ;; the user did NOT supply `:decode` and we fell back to auto-
    ;; sniffing. Per rf2-2p8wr the URL passes through
    ;; `privacy/prepare-emit-tags`, which redacts denylisted query-
    ;; string param values and stamps `:sensitive?` when applicable.
    (when (and (not decode-supplied?)
               (= :auto decoder)
               interop/debug-enabled?)
      (trace/emit! :warning :rf.warning/decode-defaulted
                   (privacy/prepare-emit-tags
                     {:request-id       request-id
                      :url              url
                      :content-type     content-type
                      :resolved-decoder (if (keyword? resolved) resolved :auto)}
                     (true? sensitive?))))
    (cond
      (fn? decoder)
      (decoder body-text headers)

      (= :json resolved)
      (util-json/json-parse body-text)

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
      (let [parsed (try (util-json/json-parse body-text)
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

(defn run-accept
  "Run the user's `:accept` fn (or the default) against `decoded`. Returns
  `{:ok v}` or `{:failure m}`."
  [accept-fn-or-nil decoded ctx]
  (let [accept (or accept-fn-or-nil (default-accept-fn ctx))]
    (accept decoded)))

;; ---- failure shape --------------------------------------------------------

(defn failure-map
  "Build a failure map of the canonical shape per Spec 014 §Failure
  categories: `{:kind <:rf.http/...> ...kind-tags...}`."
  [kind tags]
  (assoc tags :kind kind))

;; ---- reply addressing -----------------------------------------------------

(defn resolve-origin-event
  "Per Spec 014 §Reply addressing — single source of truth for the
  originating event vector used as the default reply target.

  Resolution order:
   1. `(:event frame-ctx)` — the originating event the runtime threads
      through `do-fx`'s 5-arity (when the fx fired from inside an event
      handler).
   2. `(:rf.http/origin-event args-map)` — an explicit override on the
      args map (machine-shape wrapper sets this when there is no
      ambient event, e.g. spawn-time entry actions).
   3. `[:rf.http/managed]` — synthetic fallback so the reply addressing
      machinery (`build-reply-event`) always has a non-nil event-id.

  Extracted from the three verbatim copies in `http-managed`
  (`normalise-args`, `managed-handler`) and `http-machine-wrapper`
  (`origin-event-from`) per rf2-622e3."
  [frame-ctx args-map]
  (or (:event frame-ctx)
      (:rf.http/origin-event args-map)
      [:rf.http/managed]))

(defn build-reply-event
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

(defn compute-backoff-ms
  "Per Spec 014 §Retry and backoff. Returns the delay in ms before the
  next attempt. `attempt` is the failing attempt number (1-based).

  Per Spec 014 line 250 — `:jitter true` adds random ±25% jitter to
  each delay (offset uniformly in [-0.25 × capped, +0.25 × capped])."
  [{:keys [base-ms factor max-ms jitter] :or {base-ms 250 factor 2 max-ms 5000}} attempt]
  (let [raw      (* base-ms (Math/pow factor (max 0 (dec attempt))))
        capped   (min raw max-ms)
        ;; (- 1.0 (* 2.0 (rand))) is uniform in [-1.0, +1.0]; scaled by
        ;; 0.25 × capped this yields a true ±25% offset matching the
        ;; spec's stated range. (The earlier impl used (- 0.5 (rand)) ×
        ;; 0.25 × capped which is only ±12.5%.)
        jittered (if jitter
                   (let [offset (* capped 0.25 (- 1.0 (* 2.0 (rand))))]
                     (max 0 (+ capped offset)))
                   capped)]
    (long jittered)))
