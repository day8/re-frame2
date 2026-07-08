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

(defn merge-pair-into-header-map
  "Fold a `[name value]` header pair into the accumulating Ring headers
  map. The accumulator normally carries `nil`, `string`, or `vector`
  values — the SSR runtime's `set-header`/`append-header` fxs gate
  header NAMES (RFC 7230 token grammar) and VALUES (CR/LF/NUL) but do
  NOT coerce a value to a string, so a non-string scalar (number,
  keyword, boolean) can reach this fold verbatim. The first occurrence
  `assoc`s the value in; a repeated name must still collapse it into a
  multi-value vector. The `:else` arm handles that case the same way the
  `vector?` arm does (existing scalar → `[existing v]`); without it the
  `cond` would return `nil`, silently wiping the ENTIRE accumulated
  header map mid-fold (Content-Type, Set-Cookie, everything folded so
  far) — a silent-failure header-loss bug.

  ## Fail-closed non-string coercion (rf2-v0qbng)

  Ring's `:headers` contract is value = string OR vector-of-strings. A
  number / keyword / boolean reaching this fold is undefined behaviour at
  the servlet layer — Jetty/http-kit may reject the response while
  committing it, or serialise it incorrectly, AFTER the handler has
  already returned a nominally-successful Ring map (so the `:on-error`
  path can't recover). The fx-args `:schema` boundary would catch a
  non-string value at dispatch time, but that boundary SOFT-PASSES when
  the optional schemas artefact is not on the production classpath (Spec
  010 §Recommended soft-pass) — which is the default ssr-ring runtime.
  The adapter is therefore the last line: it MUST emit a host-serialisable
  Ring value regardless of what slipped past the (optional) fx-args gate.

  We coerce a non-nil non-string value to its string form (`str`) BEFORE
  it lands in the accumulator, so every value the materialiser emits is a
  string (or, for repeated names, a vector of strings). The coercion runs
  on the production classpath — it is the correctness guarantee, not a
  diagnostic. A `nil` value is left as-is (`assoc`d under the nil-existing
  arm): an explicit nil header is dropped by Ring servers and carries no
  injection risk, and `(str nil)` = \"\" would fabricate an empty header.

  ## Dev-gated non-string warning (rf2-b0jlr)

  A non-string value here is almost certainly a CALLER bug, so alongside
  the production coercion we surface it: a dev-gated `:warning` trace
  naming the offending header key and the value's pre-coercion type.
  Production builds elide the whole check (the `interop/debug-enabled?`
  gate + `trace/emit!`'s own Closure-DCE gate), keeping the diagnostic off
  the hot path — but the coercion below is unconditional, so the wire shape
  is correct with or without the warning. Aligns with the no-silent-swallow
  posture (cf. the redirect-no-target warning in `pipeline`)."
  [m [k v]]
  (when (and interop/debug-enabled? (and (some? v) (not (string? v))))
    (trace/emit! :warning :rf.ssr/ssr-non-string-header-value
                 {:where      :ssr-ring/merge-pair-into-header-map
                  :header     k
                  :value-type (some-> v class .getName)
                  :reason     (str "header " (pr-str k) " carried a non-string "
                                   "value of type "
                                   (or (some-> v class .getName) "nil")
                                   " — Ring header values must be strings (or a "
                                   "vector of strings); the materialiser coerces it "
                                   "to its string form so the wire shape is valid, "
                                   "but this is host-dependent and almost certainly "
                                   "a caller bug")
                  :recovery   :coerced-to-string}))
  (let [v*       (if (or (nil? v) (string? v)) v (str v))
        existing (get m k)]
    (cond
      (nil? existing)        (assoc m k v*)
      (vector? existing)     (assoc m k (conj existing v*))
      :else                  (assoc m k [existing v*]))))

(defn append-set-cookies
  "For every cookie map in the response's :cookies vector, append one
  Set-Cookie header to the headers map. Returns the updated headers
  map."
  [headers-map cookies]
  (reduce
    (fn [m c]
      (merge-pair-into-header-map m ["Set-Cookie"
                                     (cookie/cookie->set-cookie-header c)]))
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
  (let [target (str/lower-case (str header-name))]
    (into {}
          (remove (fn [[k _v]] (= target (str/lower-case (str k)))))
          headers-map)))

(defn headers->ring-map+content-type-override
  "Collapse an ordered vec-of-[name value] pairs into Ring's
  `{name string-or-vec}` shape, then apply the handler's `:content-type`
  OVERRIDE (rf2-nncni3): when `content-type` is non-nil, EVERY existing
  Content-Type entry (any casing — RFC 7230 §3.2) is stripped from the
  folded map and a single canonical `Content-Type` header carrying
  `content-type` is assoc'd. A nil `content-type` leaves the folded map
  untouched — the runtime's default-seeded (Spec 011 §Status defaults) or
  app-`:rf.server/set-header`-set Content-Type flows to the wire verbatim.

  Why an OVERRIDE, not the earlier default-when-absent (rf2-uj9z8): the SSR
  runtime's `default-response` ALWAYS seeds
  `[\"content-type\" \"text/html; charset=utf-8\"]` on the accumulator, so a
  default-when-absent pass could NEVER fire for the ssr-ring runtime — the
  Content-Type was unconditionally already present, so the handler's
  documented `:content-type` opt was silently suppressed (rf2-nncni3). The
  opt is now a genuine override; it defaults to nil (removed from
  `handler-defaults` and `stream-handler`'s defaults merge), so an app's
  explicit `:rf.server/set-header \"content-type\"` stays in control when
  the caller does not pass the opt.

  Ordering / multi-value semantics of the fold are unchanged
  (`merge-pair-into-header-map`): per-name multi-value order preserved via
  `conj`, across-name order is the JDK HAMT iteration order — stable but
  not first-seen; Ring servers don't promise cross-name header order on the
  wire either."
  [pairs content-type]
  (let [m (reduce merge-pair-into-header-map {} pairs)]
    (if (nil? content-type)
      m
      (assoc (strip-header m "content-type") "Content-Type" content-type))))
