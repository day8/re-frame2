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

  The schema-validation failure throws an ex-info with
  `:rf.error/id :rf.error/http-schema-validation-failed` (the canonical
  discriminator per Spec 009); the caller
  (`http-transport/handle-response!`) classifies as
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

(def ^:private binary-decode-kinds
  "Decode modes whose result is a native binary/structured Fetch body
  rather than a string. These resolve the response via `.blob()` /
  `.arrayBuffer()` / `.formData()` instead of `.text()` (rf2-5zj6t)."
  #{:blob :array-buffer :form-data})

(defn binary-read-kind
  "Resolve the user-supplied `:decode` (+ response `headers` for the
  `:auto` sniff) to the binary Fetch body-read kind (`:blob` /
  `:array-buffer` / `:form-data`) or `nil` when the resolved decoder is
  text-based (`:json` / `:text` / a fn / a Malli schema / any other
  keyword). Mirrors the resolution `decode-response-body` performs so
  the CLJS transport can choose the right Fetch reader BEFORE the body
  is consumed (a Response body may only be read once). `:auto` over a
  non-text / non-JSON Content-Type sniffs to `:blob`, so an image
  fetched without an explicit `:decode` still reads as binary (rf2-5zj6t)."
  [decode headers]
  (let [resolved (cond
                   (fn? decode)        nil
                   (nil? decode)       (sniff-decoder (content-type-of headers))
                   (= :auto decode)    (sniff-decoder (content-type-of headers))
                   :else               decode)]
    (when (contains? binary-decode-kinds resolved)
      resolved)))

;; Memoised resolves (per rf2-tja2y). The Malli vars never rebind at
;; runtime; resolving once per JVM / once per CLJS runtime is enough,
;; and the deref asymmetry (JVM `requiring-resolve` returns a Var,
;; CLJS `resolve` returns the value directly) is normalised behind
;; the delays so the call sites in `malli-decode` invoke the cached
;; fn directly (per rf2-exycf).
;;
;; CLJS `resolve` is a compile-time macro that requires a literal
;; quoted symbol — we cannot factor the symbol behind a runtime fn
;; arg without breaking CLJS analysis. Each delay therefore inlines
;; its symbol.

(defonce ^:private malli-decode-fn
  (delay #?(:clj  (try (some-> (requiring-resolve 'malli.core/decode) deref)
                       (catch Throwable _ nil))
            :cljs (try (resolve 'malli.core/decode)
                       (catch :default _ nil)))))

(defonce ^:private malli-transformer-fn
  (delay #?(:clj  (try (some-> (requiring-resolve 'malli.transform/json-transformer) deref)
                       (catch Throwable _ nil))
            :cljs (try (resolve 'malli.transform/json-transformer)
                       (catch :default _ nil)))))

(defonce ^:private malli-validate-fn
  (delay #?(:clj  (try (some-> (requiring-resolve 'malli.core/validate) deref)
                       (catch Throwable _ nil))
            :cljs (try (resolve 'malli.core/validate)
                       (catch :default _ nil)))))

(defonce ^:private malli-absent-warned?
  ;; rf2-ee38b.7 — one-shot latch so the "schema supplied but Malli
  ;; absent" warning fires once per runtime, not once per response. The
  ;; degraded path is steady-state for a Malli-less app, so a per-request
  ;; trace would be noise; the single warning makes the silent no-op
  ;; visible without flooding the trace surface.
  (atom false))

(defn- warn-malli-absent! [schema]
  ;; Visible-degradation trace per Spec 014 §JSON decoder hardening
  ;; ("no silent fallback"). When a real schema rides `:decode` but
  ;; Malli is not on the classpath, the decode/validate delays resolve
  ;; to nil and validation is skipped — unchecked data flows to
  ;; `:accept`. Emit a `:rf.warning/http-malli-absent` so the dropped
  ;; validation is observable rather than silent.
  (when (and interop/debug-enabled?
             (compare-and-set! malli-absent-warned? false true))
    (trace/emit! :warning :rf.warning/http-malli-absent
                 {:reason (str "a `:decode` schema was supplied but malli.core is "
                               "not on the classpath; schema validation is SKIPPED "
                               "and the parsed value flows to `:accept` unchecked. "
                               "Add the Malli dependency to enable schema-driven decode.")
                  :schema schema})))

(defn- malli-decode
  "Run a Malli schema's `decode` over `value`, falling back to plain
  validate-or-throw if the transformer pipeline is unavailable. Throws
  on failure so the caller can classify as `:rf.http/decode-failure`.

  Per rf2-ee38b.7: when Malli is absent entirely (decode AND validate
  both nil), the parsed value is returned UNVALIDATED — but a one-shot
  `:rf.warning/http-malli-absent` trace fires so the degraded path is
  visible (it was previously a silent no-op, the anti-pattern §JSON
  decoder hardening calls out)."
  [schema value]
  (let [decode      @malli-decode-fn
        transformer @malli-transformer-fn
        validate    @malli-validate-fn
        decoded     (cond
                      (and decode transformer) (decode schema value (transformer))
                      decode                   (decode schema value nil)
                      :else                    value)]
    ;; Malli wholly absent (no decode, no validate) → schema validation
    ;; was skipped. Surface the degradation once.
    (when (and (nil? decode) (nil? validate))
      (warn-malli-absent! schema))
    (when validate
      (when-not (validate schema decoded)
        (throw (ex-info ":rf.error/http-schema-validation-failed"
                        {:rf.error/id :rf.error/http-schema-validation-failed
                         :where       'rf.http/decode-response-body
                         :recovery    :no-recovery
                         :reason      "the decoded response body failed Malli schema validation; the caller classifies this as :rf.http/decode-failure"
                         :schema      schema
                         :value       decoded}))))
    decoded))

(defn decode-response-body
  "Per Spec 014 §Decoding. Returns the decoded value or throws an
  ex-info that the caller maps to `:rf.http/decode-failure`.

  Per rf2-wu1n5 the JSON path enforces a per-call keyword-cap; the
  `:max-decoded-keys` slot (from the request args' `:rf.http/max-decoded-keys`,
  defaulted at the handler) is threaded into `util-json/json-parse`.
  The Malli-schema branch propagates the cap-throw rather than
  swallowing it — a `:rf.error/id :rf.error/malformed-json`
  (`:cause :too-many-keys`) is a security-relevant signal and must
  surface as `:rf.http/decode-failure`, not be masked behind a malli
  rejection."
  [{:keys [body-text body-binary headers decode decode-supplied? request-id url
           sensitive? max-decoded-keys]}]
  (let [content-type     (content-type-of headers)
        requested-decode (cond
                           (nil? decode)        :auto
                           (= :auto decode)     :auto
                           :else                decode)
        resolved         (cond
                           (= :auto requested-decode) (sniff-decoder content-type)
                           :else                      requested-decode)
        parse-opts       (when max-decoded-keys {:max-decoded-keys max-decoded-keys})]
    ;; Per Spec 014 §`:auto`: emit `:rf.warning/decode-defaulted` when
    ;; the user did NOT supply `:decode` and we fell back to auto-
    ;; sniffing. Per rf2-2p8wr the URL passes through
    ;; `privacy/prepare-emit-tags`, which redacts denylisted query-
    ;; string param values and stamps `:sensitive?` when applicable.
    (when (and (not decode-supplied?)
               (= :auto requested-decode)
               interop/debug-enabled?)
      (trace/emit! :warning :rf.warning/decode-defaulted
                   (privacy/prepare-emit-tags
                     {:request-id       request-id
                      :url              url
                      :content-type     content-type
                      :resolved-decoder (if (keyword? resolved) resolved :auto)}
                     (true? sensitive?))))
    (cond
      (fn? requested-decode)
      (requested-decode body-text headers)

      (= :json resolved)
      (util-json/json-parse body-text parse-opts)

      (= :text resolved)
      body-text

      ;; rf2-5zj6t — binary decode modes return the native Fetch body
      ;; the transport already read via `.blob()` / `.arrayBuffer()` /
      ;; `.formData()`. On hosts that have no binary body (the JVM
      ;; transport reads `.body` as a String) `body-binary` is absent and
      ;; we fall back to the raw `body-text` so the value is at least the
      ;; payload, not nil.
      (= :blob resolved)
      (if (some? body-binary) body-binary body-text)

      (= :array-buffer resolved)
      (if (some? body-binary) body-binary body-text)

      (= :form-data resolved)
      (if (some? body-binary) body-binary body-text)

      ;; Malli schema (or anything keyword-like that isn't recognised above).
      ;; rf2-wu1n5 — re-raise a `:rf.error/malformed-json` ex-info
      ;; (truncated escape, too-many-keys) rather than treating the
      ;; body as plain text. Only NON-tagged throws fall through to the
      ;; text path, which preserves the existing tolerant-of-non-JSON
      ;; semantics for legacy callers.
      (some? resolved)
      (let [parsed (try (util-json/json-parse body-text parse-opts)
                        (catch #?(:clj Throwable :cljs :default) e
                          (let [d (ex-data e)]
                            (if (= :rf.error/malformed-json (:rf.error/id d))
                              (throw e)
                              body-text))))]
        (malli-decode resolved parsed))

      :else
      body-text)))
