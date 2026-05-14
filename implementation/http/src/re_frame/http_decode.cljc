(ns re-frame.http-decode
  "Response-body decoding for `:rf.http/managed`. Per Spec 014 §Decoding
  / §`:auto` (rf2-5ijhk split).

  Extracted from `re-frame.http-encoding` per rf2-5ijhk — the original
  encoding namespace mixed five concerns; this sibling isolates the
  decode pipeline (`content-type-of`, `sniff-decoder`, `malli-decode`,
  `decode-response-body`).

  Decoding maps the response `body-text` + `headers` + user-supplied
  `:decode` to a Clojure value. The user's `:decode` can be:

   - `:auto` / omitted — sniff the response Content-Type header
                         (`:json` / `:text` / `:blob`).
   - `:json` / `:text` / `:blob` / `:array-buffer` / `:form-data` —
                         force a specific shape.
   - A Malli schema     — JSON-parse then run `malli.core/decode` with
                         the JSON-transformer, validating the result.
   - A fn               — `(fn [body-text headers] decoded)`. Full
                         control. Throws classify as
                         `:rf.http/decode-failure`.

  Malli decode requires `malli.core/decode` + optionally
  `malli.transform/json-transformer` + `malli.core/validate`. They are
  looked up via `requiring-resolve` (JVM) / `resolve` (CLJS) and
  memoised in `defonce`d delays (rf2-tja2y) — the http artefact does
  NOT depend on Malli at production-classpath time, so Malli-absent
  apps still load the namespace; the decode call falls through to a
  no-op (returns the parsed value) when Malli isn't on the classpath.

  The schema-validation failure throws an ex-info with `:malli-error?
  true`; the caller (`http-transport/handle-response!`) classifies as
  `:rf.http/decode-failure :schema-validation-failure? true`."
  (:require [clojure.string  :as str]
            [re-frame.http-privacy :as privacy]
            [re-frame.interop :as interop]
            [re-frame.trace   :as trace]
            [re-frame.util-json :as util-json]))

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

;; Memoised resolves (per rf2-tja2y). The Malli vars never rebind at
;; runtime; resolving once per JVM / once per CLJS runtime is enough,
;; and the deref asymmetry (JVM `requiring-resolve` returns a Var,
;; CLJS `resolve` returns the value directly) is normalised here so
;; the call site is platform-uniform per rf2-exycf.
;;
;; On CLJS `resolve` is the compile-time analyzer affordance — these
;; delays evaluate once at first call; subsequent decodes share the
;; cached fn.

(defn- resolve-fn
  "Resolve `sym` to its var-deref'd function value (or nil if absent).
  Threading the `#?(:clj :cljs)` here lets callers use the returned
  value uniformly without further reader conditionals."
  [sym]
  #?(:clj  (try (some-> (requiring-resolve sym) deref)
                (catch Throwable _ nil))
     :cljs (try (some-> (resolve sym))
                (catch :default _ nil))))

(defonce ^:private malli-decode-fn      (delay (resolve-fn 'malli.core/decode)))
(defonce ^:private malli-transformer-fn (delay (resolve-fn 'malli.transform/json-transformer)))
(defonce ^:private malli-validate-fn    (delay (resolve-fn 'malli.core/validate)))

(defn- malli-decode
  "Run a Malli schema's `decode` over `value`, falling back to plain
  validate-or-throw if the transformer pipeline is unavailable. Throws
  on failure so the caller can classify as `:rf.http/decode-failure`."
  [schema value]
  (let [decode      @malli-decode-fn
        transformer @malli-transformer-fn
        validate    @malli-validate-fn
        decoded     (cond
                      (and decode transformer) (decode schema value (transformer))
                      decode                   (decode schema value nil)
                      :else                    value)]
    (when validate
      (when-not (validate schema decoded)
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
