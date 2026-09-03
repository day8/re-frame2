(ns re-frame.ssr.ring.headers
  "Header materialisation for the Ring host adapter.

  re-frame.ssr stores headers internally as an ordered vector of
  `[name value]` pairs (case-insensitive name match). Ring accepts
  headers as a map of name → string OR name → vector-of-strings;
  multiple values under one name go via a vector. We collapse repeated
  pairs into vectors so multi-valued headers (Set-Cookie, Vary,
  Link, ...) round-trip correctly."
  (:require [clojure.string :as str]
            [re-frame.interop :as interop]
            [re-frame.ssr.ring.cookie :as cookie]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

(defn existing-header-key
  "Return the key already present in Ring header map `ring-headers` whose name
  matches `header-name` case-insensitively (RFC 7230 §3.2 — header field names are
  case-insensitive tokens), or nil when no case variant is present. The
  first-seen spelling is the one retained, so `merge-pair-into-header-map`
  folds later case variants back under this returned key. Mirrors the
  lower-case matching rule `strip-header` uses so both single-source the
  case-insensitive header-name comparison."
  [ring-headers header-name]
  (let [normalized-name (str/lower-case (str header-name))]
    (some (fn [existing-key]
            (when (= normalized-name (str/lower-case (str existing-key)))
              existing-key))
          (keys ring-headers))))

(defn merge-pair-into-header-map
  "Fold a `[name value]` header pair into the accumulating Ring headers
  map. Repeated names preserve order in a vector.

  Header field names are case-insensitive (RFC 7230 §3.2), so pairs whose
  names differ only by ASCII case are ONE logical header: they collapse
  under the first-seen spelling (the stable emitted key) with their values
  kept in declaration order. This mirrors `strip-header`'s case-insensitive
  rule and honours the ns docstring's case-insensitive name-match promise.

  Ring requires strings or vectors of strings. Because schema validation is
  optional, this wire boundary unconditionally stringifies non-nil scalar
  values and emits a development warning. Nil remains nil instead of being
  fabricated into an empty header."
  [ring-headers [header-name header-value]]
  (when (and interop/debug-enabled?
             (some? header-value)
             (not (string? header-value)))
    (trace/emit! :warning :rf.ssr/ssr-non-string-header-value
                 {:where      :ssr-ring/merge-pair-into-header-map
                  :header     header-name
                  :value-type (some-> header-value class .getName)
                  :reason     (str "header " (pr-str header-name) " carried a non-string "
                                   "value of type "
                                   (or (some-> header-value class .getName) "nil")
                                   " — Ring header values must be strings (or a "
                                   "vector of strings); the materialiser coerces it "
                                   "to its string form so the wire shape is valid, "
                                   "but this is host-dependent and almost certainly "
                                   "a caller bug")
                  :recovery   :coerced-to-string}))
  (let [wire-value (if (or (nil? header-value) (string? header-value))
                     header-value
                     (str header-value))
        ;; Fold under the first-seen spelling of this logical header so
        ;; case variants (Vary / vary / VARY) land in ONE Ring map entry.
        emitted-key (or (existing-header-key ring-headers header-name)
                        header-name)
        existing-value (get ring-headers emitted-key)]
    (cond
      (nil? existing-value)
      (assoc ring-headers emitted-key wire-value)

      (vector? existing-value)
      (assoc ring-headers emitted-key (conj existing-value wire-value))

      :else
      (assoc ring-headers emitted-key [existing-value wire-value]))))

(defn append-set-cookies
  "For every cookie map in the response's :cookies vector, append one
  Set-Cookie header to the headers map. Returns the updated headers
  map."
  [headers-map cookies]
  (reduce
    (fn [ring-headers cookie-map]
      (merge-pair-into-header-map
        ring-headers
        ["Set-Cookie" (cookie/cookie->set-cookie-header cookie-map)]))
    headers-map
    cookies))

(defn strip-header
  "Remove every entry whose name equals `header-name` (case-insensitively)
  from a Ring header map. RFC 7230 §3.2 makes header names case-insensitive
  tokens, and `merge-pair-into-header-map` preserves whatever casing the fx
  supplied, so a header can land under any casing (`Content-Type`,
  `content-type`, `CONTENT-TYPE`, …); we drop EVERY key whose lower-case
  matches. Shared by the `:content-type` override
  (`headers->ring-map+content-type-override`) and the Content-Length strip
  (`strip-content-length`) so both single-source the case-insensitive
  header-removal rule."
  [headers-map header-name]
  (let [normalized-name (str/lower-case (str header-name))]
    (into {}
          (remove (fn [[existing-name _header-value]]
                    (= normalized-name
                       (str/lower-case (str existing-name)))))
          headers-map)))

(defn strip-content-length
  "Remove any `Content-Length` header (case-insensitively) from a Ring
  response header map. The adapter assembles bodies after the event drain,
  so an app-supplied length cannot describe either the final string or the
  streaming input. Let the Ring server own transfer framing."
  [headers-map]
  (strip-header headers-map "content-length"))

(defn headers->ring-map+content-type-override
  "Collapse an ordered vec-of-[name value] pairs into Ring's
  `{name string-or-vec}` shape, then apply the handler's `:content-type`
  override. A non-nil override removes every case variant before associating
  one canonical `Content-Type`; nil preserves the runtime- or app-supplied
  header. The option must be an override because the runtime always seeds a
  default Content-Type.

  Ordering / multi-value semantics of the fold are unchanged
  (`merge-pair-into-header-map`): per-name multi-value order preserved via
  `conj`, across-name order is the JDK HAMT iteration order — stable but
  not first-seen; Ring servers don't promise cross-name header order on the
  wire either."
  [header-pairs content-type]
  (let [ring-headers (reduce merge-pair-into-header-map {} header-pairs)]
    (if (nil? content-type)
      ring-headers
      (assoc (strip-header ring-headers "content-type")
             "Content-Type"
             content-type))))
