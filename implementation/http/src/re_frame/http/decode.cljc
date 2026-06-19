(ns re-frame.http.decode
  "Response-body decoding for `:rf.http/managed`. Per Spec 014 §Decoding
  / §`:auto` (rf2-5ijhk split).

  Extracted from `re-frame.http.encoding` per rf2-5ijhk — the original
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
            [re-frame.error   :as error]
            [re-frame.interop :as interop]
            [re-frame.trace   :as trace]
            [re-frame.http.json :as util-json]))

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

(defn- empty-2xx-json-body?
  "rf2-upexd.1 — cross-host parity (the oyw04 contract) for an empty /
  whitespace-only 2xx JSON body. An empty `200`/`204`-shaped body is a
  NORMAL HTTP outcome (the common PUT/DELETE/POST-with-no-content reply),
  NOT a programmer error — so the managed-cascade decode altitude must
  classify it IDENTICALLY on both hosts.

  Without this guard the two `util-json/json-parse` branches diverge: the
  JVM Cheshire reader surfaces an empty document as end-of-stream → nil,
  while the CLJS `js/JSON.parse(\"\")` THROWS a `SyntaxError` that the
  transport reclassifies as `:rf.http/decode-failure`. The helper-level
  divergence is DELIBERATELY pinned at the `util-json` unit altitude
  (util_json_cljs_test.cljs / util_json_test.clj — an empty hand-passed
  helper arg is a transport-layer programmer error). That reasoning is
  sound for the bare helper but WRONG at the `:rf.http/managed` cascade
  altitude. We therefore short-circuit the empty/blank case HERE, at the
  decode altitude, returning nil on BOTH hosts before `json-parse` is
  reached — so the helper keeps its pinned per-host semantics while the
  managed path stays host-symmetric (`{:kind :success :value nil}`)."
  [body-text]
  (and (string? body-text) (str/blank? body-text)))

(defn- json-media-type?
  "rf2-houkno — RFC 6839 / IANA structured-syntax-suffix aware JSON
  media-type predicate. Strips parameters (the `;charset=…` tail) and the
  type prefix, then accepts a subtype of exactly `json` OR any subtype
  carrying the `+json` structured-syntax suffix. This recognises mainstream
  vendor JSON media types — `application/vnd.api+json` (JSON:API),
  `application/ld+json` (JSON-LD), `application/vnd.github+json` — that a
  bare `(str/includes? ct \"application/json\")` substring check wrongly
  rejected (a real footgun against correct servers).

  Examples that match: `application/json`, `application/json; charset=utf-8`,
  `application/vnd.api+json`, `application/ld+json`, `text/json`.
  Examples that do NOT: `application/edn`, `text/plain`, `application/xml`."
  [content-type]
  (when (string? content-type)
    (let [;; drop parameters (`; charset=…`, `; boundary=…`), trim, lower-case
          essence (-> content-type (str/split #";") first str/trim str/lower-case)
          ;; subtype is the part after the first "/"; tolerate a missing "/"
          subtype (let [idx (str/index-of essence "/")]
                    (if idx (subs essence (inc idx)) essence))]
      (boolean
        (or (= "json" subtype)
            (str/ends-with? subtype "+json"))))))

(defn- sniff-decoder
  "Per Spec 014 §`:auto`: sniff the response Content-Type header.
  rf2-houkno — JSON sniffing recognises RFC 6839 `+json` structured-syntax
  suffix media types (e.g. `application/vnd.api+json`) via `json-media-type?`,
  not just a bare `application/json` substring."
  [content-type]
  (let [ct (some-> content-type str/lower-case)]
    (cond
      (json-media-type? content-type)        :json
      (and ct (str/starts-with? ct "text/")) :text
      :else                                  :blob)))

(defn- json-content-type?
  "rf2-upexd.2 — does the response declare a JSON Content-Type? The
  Malli-schema decode path is JSON-ONLY (Spec 014 §Decoding §Schema-
  driven): the only Malli transformer the decoder wires is the
  `json-transformer` (see `malli-transformer-fn`), so a schema rides a
  JSON body by construction. A `nil` content-type is treated as
  JSON-eligible — many JSON APIs omit the header, and rejecting them
  would be a regression; only a Content-Type that is PRESENT and
  declares a NON-JSON MIME is rejected as a clear contract violation
  rather than silently JSON-parsing (and failing) a non-JSON body.

  rf2-houkno — JSON eligibility now honours RFC 6839 `+json` structured-
  syntax suffix media types (e.g. `application/vnd.api+json`,
  `application/ld+json`) via `json-media-type?`, not just a bare
  `application/json` substring — those carry valid JSON bodies by
  construction and were wrongly rejected as non-JSON before."
  [content-type]
  (or (nil? content-type)
      (json-media-type? content-type)))

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
        (error/throw-error!
          :rf.error/http-schema-validation-failed 'rf.http/decode-response-body
          "the decoded response body failed Malli schema validation; the caller classifies this as :rf.http/decode-failure"
          {:extra {:schema schema
                   :value  decoded}})))
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
  [{:keys [body-text body-binary headers decode max-decoded-keys]}]
  (let [content-type     (content-type-of headers)
        requested-decode (cond
                           (nil? decode)        :auto
                           (= :auto decode)     :auto
                           :else                decode)
        resolved         (cond
                           (= :auto requested-decode) (sniff-decoder content-type)
                           :else                      requested-decode)
        parse-opts       (when max-decoded-keys {:max-decoded-keys max-decoded-keys})]
    (cond
      ;; A custom `:decode` fn owns its own body handling: the empty-2xx
      ;; body normalisation (the `:json`/`:auto` arms' `empty-2xx-json-body?`
      ;; → nil) deliberately does NOT apply here — the fn receives the raw
      ;; `body-text` (e.g. `""` for an empty 2xx body) and decides itself.
      (fn? requested-decode)
      (requested-decode body-text headers)

      (= :json resolved)
      ;; rf2-upexd.1 — an empty/whitespace-only 2xx JSON body is a normal
      ;; HTTP outcome (empty-success-envelope); classify it as nil on BOTH
      ;; hosts before `json-parse` (which throws on CLJS for `""`). See
      ;; `empty-2xx-json-body?`.
      (if (empty-2xx-json-body? body-text)
        nil
        (util-json/json-parse body-text parse-opts))

      (= :text resolved)
      body-text

      ;; rf2-5zj6t — binary decode modes (`:blob` / `:array-buffer` /
      ;; `:form-data`, the `binary-decode-kinds` set) return the native
      ;; binary body the transport already read: the CLJS transport rides
      ;; it under `:body-binary` via `.blob()` / `.arrayBuffer()` /
      ;; `.formData()`, and (per rf2-a3wxe) the JVM transport rides the
      ;; raw `byte[]` it read via `BodyHandlers/ofByteArray` under the
      ;; SAME `:body-binary` key — so on BOTH real-transport hosts a
      ;; binary 2xx response arrives here with `body-binary` PRESENT and
      ;; this arm returns it verbatim (no lossy text fallback). The
      ;; `body-text` fallback fires only when `body-binary` is genuinely
      ;; absent — e.g. a synthetic / test ctx that populated only
      ;; `:body-text` — so the value is at least the payload, not nil.
      ;; (Pre-rf2-a3wxe the JVM read `.body` as a String and this arm
      ;; ALWAYS fell through to the lossy text fallback for binary modes;
      ;; a3wxe eliminated that — see `jvm-fetch`'s ofByteArray read.)
      ;; rf2-jkake.9 — the three modes collapse onto the shared
      ;; `binary-decode-kinds` set (the same set `binary-read-kind`
      ;; consults) rather than three identical cond arms.
      (contains? binary-decode-kinds resolved)
      (if (some? body-binary) body-binary body-text)

      ;; Malli schema (or anything keyword-like that isn't recognised above).
      ;; rf2-wu1n5 — re-raise a `:rf.error/malformed-json` ex-info
      ;; (truncated escape, too-many-keys) rather than treating the
      ;; body as plain text. Only NON-tagged throws fall through to the
      ;; text path, which preserves the existing tolerant-of-non-JSON
      ;; semantics for legacy callers.
      (some? resolved)
      ;; rf2-upexd.2 — the schema decode path is JSON-ONLY: the only
      ;; Malli transformer the decoder wires is the `json-transformer`,
      ;; so a schema rides a JSON body by construction (Spec 014
      ;; §Decoding §Schema-driven, tightened by this bead). A response
      ;; that DECLARES a non-JSON Content-Type (e.g. `application/edn`,
      ;; `text/plain`) under a schema `:decode` is a contract mismatch.
      ;; Previously the body was JSON-parsed unconditionally; a non-JSON
      ;; body's parse failure fell through to the raw text, then Malli
      ;; validated the STRING against the schema and almost always failed
      ;; — surfacing a confusing `:schema-validation-failure?` instead of
      ;; the real cause (the MIME mismatch). Reject up-front with a clear
      ;; tagged ex-info that the transport classifies as
      ;; `:rf.http/decode-failure` (NOT a schema-validation failure), so
      ;; the diagnostic names the actual problem. A nil/absent Content-
      ;; Type stays JSON-eligible (many JSON APIs omit the header).
      (if-not (json-content-type? content-type)
        (error/throw-error!
          :rf.error/http-schema-non-json-content-type 'rf.http/decode-response-body
          (str "a Malli `:decode` schema was supplied but the response "
               "declared a non-JSON Content-Type (" content-type "); the "
               "schema decode path is JSON-only (it wires Malli's "
               "json-transformer). The caller classifies this as "
               ":rf.http/decode-failure.")
          {:extra {:content-type content-type
                   :schema       resolved}})
        ;; rf2-upexd.1 — an empty/whitespace-only 2xx body parses to nil on
        ;; BOTH hosts (the JVM Cheshire reader already yields nil for an
        ;; empty document; CLJS would throw inside `json-parse`). Short-
        ;; circuit to nil before the parse so the schema sees the same value
        ;; cross-host; the schema then decides whether nil is acceptable
        ;; (e.g. `[:maybe ...]` passes, a required `:map` rejects) — a
        ;; host-symmetric outcome, not a per-host parse divergence.
        (let [parsed (if (empty-2xx-json-body? body-text)
                       nil
                       (try (util-json/json-parse body-text parse-opts)
                            (catch #?(:clj Throwable :cljs :default) e
                              (let [d (ex-data e)]
                                (if (= :rf.error/malformed-json (:rf.error/id d))
                                  (throw e)
                                  body-text)))))]
          (malli-decode resolved parsed)))

      :else
      body-text)))
