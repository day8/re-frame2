(ns re-frame.ssr.response
  "HTTP response accumulator + handler fns for the seven `:rf.server/*`
  server-side fxs (six original + `:rf.server/safe-redirect` per rf2-zfm8v).
  Per Spec 011 §HTTP response contract.

  The runtime owns a per-request response accumulator in a
  framework-private side-channel atom keyed by frame-id — `response-slots`
  below. Standard server-only fx populate the slot during the drain;
  the host adapter consumes the resolved value after drain to build the
  wire response.

  Storage substrate (rf2-jbcmt). Per Spec 011 §Response storage substrate
  the accumulator MUST NOT ride `app-db`. The substrate symmetry with
  `request-slots` is intentional:

  - **Privacy.** `app-db` is the hydration payload's source (the
    `:rf/app-db` slice ships to the client on bootstrap). The response
    accumulator routinely carries server-only data — `Set-Cookie`
    headers (auth tokens, session ids), internal `X-*` headers, redirect
    URLs that may encode internal hostnames or secrets. Storing the
    accumulator in `app-db` would default-leak that surface onto the
    wire and force every host adapter to remember a defensive
    `(dissoc :rf/response)` before serialising the payload — a privacy
    boundary that's a constant caller-vigilance burden is a leak waiting
    to happen. Side-channel storage makes the boundary self-enforcing.
  - **Perf.** Each `:rf.server/*` fx writes the accumulator with an
    O(small-map) atom CAS against the `{frame-id → response-map}` table.
    Storing it in `app-db` would instead swap the WHOLE app-db container
    per fx — for a typical 7-fx login flow (`set-status` + 2× `set-cookie`
    + 3× `set-header` + `redirect`), seven full-app-db replacements per
    request, each allocating a fresh container value for one changed key.

  Default shape (Spec 011 §HTTP response contract):

      {:status   200
       :headers  [[\"content-type\" \"text/html; charset=utf-8\"]]
       :cookies  []
       :redirect nil}

  Internal-only bookkeeping under `:rf.server/_status-writes` and
  `:rf.server/_redirect-writes` records every write so the runtime can
  emit `:rf.warning/multiple-status-set` / `:rf.warning/multiple-redirects`
  on the second-and-later write while still preserving last-write-wins
  semantics for the public `:status` / `:redirect` slots.

  All `reg-fx` calls live in the `re-frame.ssr` façade so a
  `(require 're-frame.ssr :reload)` after `(registrar/clear-all!)`
  re-installs them. This namespace exports handler fns only.

  `get-response` (the read surface that flushes pending error projections
  before reading) lives in `re-frame.ssr.error-listener` because it
  depends on the projector's drain — `response-of` here is the pure
  read used both internally and by the listener module.

  `clear-response!` is called by `re-frame.ssr.request/on-frame-destroyed!`
  via the `:ssr/on-frame-destroyed` late-bind hook (rf2-fcj33) so the
  slot is released on per-request frame teardown.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [clojure.string]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.ssr.http-validation :as http-validation]
            [re-frame.trace :as trace])
  #?(:clj (:import [java.net URI URISyntaxException])))

(defonce
  ^{:doc "Per-frame storage for the HTTP response accumulator. Keys are
  frame-ids; values are the accumulator map (`{:status :headers :cookies
  :redirect ...}` plus internal `:rf.server/_status-writes` /
  `:rf.server/_redirect-writes` bookkeeping). Framework-private —
  not stored in `app-db` so the accumulator never rides the hydration
  payload to the client (Spec 011 §Response storage substrate, rf2-jbcmt).
  Cleared per-frame by the `:ssr/on-frame-destroyed` hook (rf2-fcj33)."}
  response-slots
  (atom {}))

;; rf2-hbty2 / security audit 2026-05-14 §P1.3 — header-value injection
;; gate. set-header / append-header / redirect flow user-controlled
;; strings straight into the Ring response map; an attacker who controls
;; a header value with embedded CR/LF can split the header and forge
;; second-and-later headers on the wire (RFC 7230 §3.2.4 explicitly
;; bans CTLs in header values). We reject at the fx boundary rather
;; than the Ring materialiser so misuse surfaces with the dispatching
;; event in scope rather than as a deep Ring exception. Same pattern as
;; the cookie-attribute validator (rf2-rpedl).
;;
;; Decision: fail-fast (throw) rather than strip-and-warn. A header
;; value with CR/LF has no safe interpretation — strip-and-warn would
;; silently mutate the wire shape and leave a CRLF-shaped string-equal
;; comparison failing later in tests.
;;
;; The injection-char grammar is single-sourced in
;; `re-frame.ssr.http-validation/contains-injection-char?` — the SAME
;; predicate the Ring materialiser (`re-frame.ssr.ring.cookie`) enforces,
;; so the two boundaries can't drift on what counts as a header-splitting
;; char.

(defn- validate-header-value!
  "Throw `:rf.error/header-invalid-value` if the header value `v`
  contains CR / LF / NUL. Per RFC 7230 §3.2.4 — `field-value` is
  `*( field-content / obs-fold )` and obs-fold (CRLF + WSP) is
  deprecated; no CTLs allowed in `field-content`."
  [header-name v]
  (let [s (str v)]
    (when (http-validation/contains-injection-char? s)
      (error/throw-error!
        :rf.error/header-invalid-value
        'rf.ssr/response
        (str "header " (pr-str header-name)
             " value contains CR/LF/NUL — forbidden"
             " by RFC 7230 §3.2.4 (header-splitting"
             " injection). Strip CR/LF/NUL from the header value before"
             " setting it.")
        {:recovery :remove-injection-chars-from-header-value
         :extra    {:header header-name
                    :value  v}}))
    s))

(defn- validate-redirect-location!
  "Throw `:rf.error/redirect-invalid-location` if the redirect location
  `loc` contains CR / LF / NUL. Same CRLF-injection vector as a
  user-controlled `Location` header value: a query param like
  `?next=https://example.com%0d%0aSet-Cookie:%20stolen=1` URL-decodes
  into literal CRLF and would split the header on the wire.

  This is the SHARED CR/LF/NUL gate — both `:rf.server/redirect` (caller-
  trusted) and `:rf.server/safe-redirect` (caller-untrusted) run it as a
  defence-in-depth first step, and it is the ONLY structural gate on the
  caller-trusted path (rf2-ziv4gd: no URL-shape check — a raw space or
  RFC 3986 shape quirk a browser accepts in a `Location` header passes
  through). It deliberately does NOT do any URL-shape / scheme / host
  validation: `safe-redirect-fx` does its own full parse + scheme + host
  gate (emitting the specific `:rf.error/safe-redirect-*` categories) for
  the caller-untrusted path."
  [loc]
  (let [s (str loc)]
    (when (http-validation/contains-injection-char? s)
      (error/throw-error!
        :rf.error/redirect-invalid-location
        'rf.ssr/response
        (str "redirect :location contains CR/LF/NUL"
             " — forbidden by RFC 7230 §3.2.4"
             " (header-splitting injection). Strip CR/LF/NUL from the"
             " redirect :location before setting it.")
        {:recovery :remove-injection-chars-from-location
         :extra    {:location loc}}))
    s))

;; rf2-z7gor / security audit 2026-05-14 — header-name + cookie-field
;; gates at the fx boundary. The rf2-hbty2 work validated header
;; VALUES; header NAMES and the structured cookie map were still trusted.
;; The ring host adapter happens to re-validate at materialisation time
;; (rf2-rpedl in ssr-ring/cookie.clj), but the SSR layer is also the
;; integration boundary for Pedestal / HttpKit / other custom adapters
;; that consume the response shape directly — so the fx boundary is the
;; right place to enforce the invariant once, with the dispatching event
;; still in scope rather than as a deep adapter exception.
;;
;; RFC 7230 §3.2.6 token grammar (header names + RFC 6265 §4.1.1
;; cookie-name): `1*tchar` where `tchar` ∈ the visible US-ASCII set
;; MINUS the separators `( ) < > @ , ; : \ " / [ ] ? = { }` and
;; whitespace. The grammar is single-sourced in
;; `re-frame.ssr.http-validation/valid-token-name?` — the SAME predicate
;; the Ring materialiser (`re-frame.ssr.ring.cookie`) enforces on cookie
;; names, so the two boundaries can't drift on the token grammar.

(defn- validate-header-name!
  "Throw `:rf.error/header-invalid-name` if the header name violates the
  RFC 7230 §3.2.6 token grammar — empty, contains CR / LF / NUL,
  whitespace, or separators. Same gate as the cookie-name validator.
  Per rf2-z7gor §security-audit-2026-05-14."
  [n]
  (let [s (str n)]
    (when-not (http-validation/valid-token-name? s)
      (error/throw-error!
        :rf.error/header-invalid-name
        'rf.ssr/response
        (str "header :name " (pr-str s)
             " violates RFC 7230 §3.2.6 token grammar"
             " (no CTLs, whitespace, or separators"
             " ()<>@,;:\\\"/[]?={}). Use a token-grammar header name.")
        {:recovery :use-a-token-grammar-header-name
         :extra    {:header n}}))
    s))

(defn- validate-cookie-name!
  "Throw `:rf.error/cookie-invalid-name` if the cookie name is nil, is not a
  string / keyword / symbol, or violates the RFC 6265 §4.1.1 token grammar.
  Same shape as the rf2-rpedl validator in `ssr-ring/cookie.clj` — applied
  here at the fx boundary so non-ring host adapters get the same gate. Per
  rf2-z7gor + rf2-9t17id (the TYPE guard)."
  [n]
  (when (nil? n)
    (error/throw-error!
      :rf.error/cookie-invalid-name
      'rf.ssr/response
      "cookie :name is required; supply a non-nil :name on the cookie map."
      {:recovery :supply-a-cookie-name
       :extra    {:name n}}))
  ;; rf2-9t17id — type-guard BEFORE `(name n)`. A cookie `:name` is only a
  ;; string or a Named (keyword / symbol); anything else (a Long `42`, a
  ;; vector `[]`, a map `{}`, a boolean `true`, …) makes `name` throw a raw
  ;; host exception (`ClassCastException` on the JVM) with nil ex-data,
  ;; bypassing the documented `:rf.error/cookie-invalid-name` contract and
  ;; reaching adapter / `:on-error` code as a generic host exception. The
  ;; args-schema boundary catches this only when a validator is live and the
  ;; schema pins the type — when schemas are absent or soft-pass (Spec 010),
  ;; the fx boundary is the last line of defence, so reject the unsupported
  ;; TYPE loudly through the same structured error id. Mirrors the Ring
  ;; materialiser's guard (rf2-d95o1y in `re-frame.ssr.ring.cookie`); the JVM
  ;; branch matches Ring's `clojure.lang.Named` check exactly (the fx runs
  ;; server-only, `:platforms #{:server}`).
  (when-not (or (string? n)
                #?(:clj  (instance? clojure.lang.Named n)
                   :cljs (or (keyword? n) (symbol? n))))
    (error/throw-error!
      :rf.error/cookie-invalid-name
      'rf.ssr/response
      (str "cookie :name must be a string or a keyword/symbol; got "
           (pr-str n)
           #?(:clj (str " (a " (.getName (class n)) ")") :cljs "")
           ". Use a string or keyword token-grammar cookie name.")
      {:recovery :use-a-token-grammar-cookie-name
       :extra    {:name n}}))
  (let [s (#?(:clj clojure.core/name :cljs cljs.core/name) n)]
    (when-not (http-validation/valid-token-name? s)
      (error/throw-error!
        :rf.error/cookie-invalid-name
        'rf.ssr/response
        (str "cookie :name " (pr-str s)
             " violates RFC 6265 §4.1.1 token grammar"
             " (no CTLs, whitespace, or separators"
             " ()<>@,;:\\\"/[]?={}). Use a token-grammar cookie name.")
        {:recovery :use-a-token-grammar-cookie-name
         :extra    {:name n}}))
    s))

(defn- validate-cookie-attr!
  "Throw `:rf.error/cookie-invalid-<field>` if the cookie attribute
  string contains CR / LF / NUL. Applied to EVERY attribute that a host
  adapter concatenates into the Set-Cookie wire form — `:value`, `:path`,
  `:domain`, `:max-age`, `:same-site`, `:expires`. The value is
  `str`-coerced first because callers legitimately pass non-string
  forms (`:max-age` as an int, `:expires` as an instant); the CR/LF/NUL
  ban applies to the serialised form a host adapter would emit. Per
  rf2-z7gor (the fx-boundary gate) and rf2-kjf3m.1 (Spec 011 §CRLF
  fail-fast mandates checking EVERY attribute, not just :value — an
  attacker who controls any one attribute must not be able to re-enter
  the header line as CRLF-bearing payload)."
  [field-key v]
  (let [s (str v)]
    (when (http-validation/contains-injection-char? s)
      (let [error-kw (keyword "rf.error" (str "cookie-invalid-" (name field-key)))]
        (error/throw-error!
          error-kw
          'rf.ssr/response
          (str "cookie " field-key " " (pr-str s)
               " contains CR/LF/NUL — forbidden by"
               " RFC 7230 §3.2.4 (header-splitting"
               " injection). Strip CR/LF/NUL from the cookie "
               (name field-key) ".")
          {:recovery :remove-injection-chars-from-cookie-attr
           :extra    {:field field-key
                      :value v}})))
    s))

(defn- validate-cookie!
  "Run name + field validators across the cookie map. Per rf2-z7gor —
  gate the structured cookie at the fx boundary so misuse surfaces with
  the dispatching event in scope rather than as a deep adapter exception.

  CRLF-checks EVERY attribute a host adapter serialises into the
  Set-Cookie line, not just `:value` (rf2-kjf3m.1 / Spec 011 §CRLF
  fail-fast). The fx boundary is the single enforcement point for
  non-Ring host adapters (Pedestal / HttpKit / custom) that read the
  accumulator and serialise cookies themselves; the Ring materialiser
  re-checks at wire-write time, but the two boundaries must not diverge
  on what they accept. `:max-age` and `:expires` are commonly non-string
  (int / instant) — `validate-cookie-attr!` `str`-coerces before the
  CR/LF/NUL ban, so a string `:max-age` sourced from request context
  (`\"3600\\r\\nSet-Cookie: admin=1\"`) is rejected at the fx boundary
  rather than splitting the header on a non-Ring host."
  [cookie]
  (validate-cookie-name! (:name cookie))
  (when (some? (:value     cookie)) (validate-cookie-attr! :value     (:value     cookie)))
  (when (some? (:path      cookie)) (validate-cookie-attr! :path      (:path      cookie)))
  (when (some? (:domain    cookie)) (validate-cookie-attr! :domain    (:domain    cookie)))
  (when (some? (:max-age   cookie)) (validate-cookie-attr! :max-age   (:max-age   cookie)))
  (when (some? (:same-site cookie)) (validate-cookie-attr! :same-site (:same-site cookie)))
  (when (some? (:expires   cookie)) (validate-cookie-attr! :expires   (:expires   cookie)))
  cookie)

(def status-writes-key   :rf.server/_status-writes)
(def redirect-writes-key :rf.server/_redirect-writes)

(defn default-response
  "The default response accumulator. Spec 011 §Status defaults: status 200,
  default content-type text/html for HTML responses, no cookies, no redirect."
  []
  {:status   200
   :headers  [["content-type" "text/html; charset=utf-8"]]
   :cookies  []
   :redirect nil})

(defn- ensure-response
  "Return resp with defaults applied. nil-tolerant — a frame whose slot
  has never been touched by an :rf.server/* fx still resolves to the
  default response shape."
  [resp]
  (if resp
    (merge (default-response) resp)
    (default-response)))

(defn swap-response!
  "Mutate the response accumulator slot for `frame-id` with `f`. Returns
  the post-swap response map. The substrate is a side-channel atom keyed
  on the frame ADDRESS (rf2-jbcmt; rf2-bzw8gd) — O(small-map) swap, no app-db
  ping-pong. `frame-address` is the bare process-local frame id."
  [frame-id f]
  (let [addr      (frame/frame-address frame-id)
        next-resp (-> (swap! response-slots
                             update addr #(f (ensure-response %)))
                      (get addr))]
    next-resp))

(defn response-of
  "Read the current response accumulator (with defaults applied)."
  [frame-id]
  (ensure-response (get @response-slots (frame/frame-address frame-id))))

(defn clear-response!
  "Drop `frame-id`'s response slot. Called from
  `re-frame.ssr.request/on-frame-destroyed!` via the
  `:ssr/on-frame-destroyed` late-bind hook (rf2-fcj33). Idempotent —
  tolerates a frame-id with no slot. Keyed by the frame ADDRESS (rf2-bzw8gd)."
  [frame-id]
  (swap! response-slots dissoc (frame/frame-address frame-id))
  frame-id)

;; ---- header helpers ------------------------------------------------------

(defn- replace-header
  "Replace the (first matching, case-insensitive) header pair with [name value],
  or append if none matched. Subsequent matches are dropped — set-header
  replaces the entire header value per Spec 011 §Header replacement vs append."
  [headers name value]
  (let [normalised (str name)
        target     (clojure.string/lower-case normalised)
        [seen? pruned]
        (reduce
          (fn [[seen acc] [h-name _h-val :as pair]]
            (cond
              (not= (clojure.string/lower-case (str h-name)) target)
              [seen (conj acc pair)]

              seen
              [seen acc]    ;; drop subsequent matches

              :else
              [true (conj acc [normalised value])]))
          [false []]
          headers)]
    (if seen?
      pruned
      (conj pruned [normalised value]))))

(defn- append-header-pair
  "Append [name value] to headers — preserves any existing header with the
  same name. Per Spec 011 §Header replacement vs append; required for
  Set-Cookie-style multi-valued headers."
  [headers name value]
  (conj (vec headers) [(str name) value]))

(defn- warn-on-multiple-writes!
  "Emit `warning-id` (a `:rf.warning/multiple-*` trace) when more than one
  write to the last-write-wins slot recorded under `writes-key` has landed
  on `resp`. `final-key` (`:final-status` / `:final-redirect`) names the
  tag the consumer reads the winning write off. Shared by `set-status-fx`,
  `redirect-fx`, and `safe-redirect-fx` — all three record every write for
  the multi-write policy and warn on the second-and-later one while
  preserving last-write-wins for the public slot."
  [resp writes-key warning-id final-key frame]
  (let [writes (get resp writes-key)]
    (when (and resp (> (count writes) 1))
      (trace/emit! :warning warning-id
                   {:writes   writes
                    final-key (last writes)
                    :frame    frame
                    :recovery :warned-and-replaced}))))

;; ---- handler fns for the seven :rf.server/* fxs --------------------------

(defn set-status-fx
  "Handler fn for `:rf.server/set-status`. Last-write-wins; multi-write
  emits `:rf.warning/multiple-status-set`."
  [{:keys [frame]} status]
  (let [resp (swap-response!
               frame
               (fn [r]
                 (-> r
                     (update status-writes-key (fnil conj []) status)
                     (assoc :status status))))]
    (warn-on-multiple-writes! resp status-writes-key
                              :rf.warning/multiple-status-set :final-status frame)))

(defn set-header-fx
  "Handler fn for `:rf.server/set-header`. Replaces any existing header
  with the same name (case-insensitive). Throws
  `:rf.error/header-invalid-name` on a name violating the RFC 7230
  §3.2.6 token grammar (rf2-z7gor), and `:rf.error/header-invalid-value`
  on a value carrying CR/LF/NUL (rf2-hbty2)."
  [{:keys [frame]} {:keys [name value]}]
  (validate-header-name!  name)
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [r] (update r :headers replace-header name value))))

(defn append-header-fx
  "Handler fn for `:rf.server/append-header`. Preserves any existing
  header with the same name — required for Set-Cookie-style multi-valued
  headers. Throws `:rf.error/header-invalid-name` on a name violating the
  RFC 7230 §3.2.6 token grammar (rf2-z7gor), and
  `:rf.error/header-invalid-value` on a value carrying CR/LF/NUL
  (rf2-hbty2)."
  [{:keys [frame]} {:keys [name value]}]
  (validate-header-name!  name)
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [r] (update r :headers append-header-pair name value))))

(defn set-cookie-fx
  "Handler fn for `:rf.server/set-cookie`. Cookie attributes are stored
  as a structured map (RFC 6265 wire-form serialisation is host-adapter
  business). Throws `:rf.error/cookie-invalid-name` on a name violating
  the RFC 6265 §4.1.1 token grammar, and
  `:rf.error/cookie-invalid-{value,path,domain}` on a CRLF/NUL-bearing
  attribute string — rf2-z7gor gates the structured cookie at the fx
  boundary so non-ring host adapters get the same safety the ring
  materialiser (rf2-rpedl) provides at wire-write time."
  [{:keys [frame]} cookie-map]
  (validate-cookie! cookie-map)
  (swap-response!
    frame
    (fn [r] (update r :cookies (fnil conj []) cookie-map))))

(defn delete-cookie-fx
  "Handler fn for `:rf.server/delete-cookie`. Sugar over set-cookie
  with :max-age 0 and an empty :value. Same rf2-z7gor name + path +
  domain validation as `set-cookie-fx`."
  [{:keys [frame]} {:keys [name path domain]}]
  (let [cookie (cond-> {:name    name
                        :value   ""
                        :max-age 0}
                 path   (assoc :path   path)
                 domain (assoc :domain domain))]
    (validate-cookie! cookie)
    (swap-response!
      frame
      (fn [r] (update r :cookies (fnil conj []) cookie)))))

;; rf2-vngir / EP-0007 one-name-per-fact. The redirect target key is
;; canonically `:location` — this fx writes an HTTP `Location` response
;; header, so it uses header vocabulary (routing/navigation surfaces may
;; use `:url` / `:to`; HTTP-response redirect surfaces use `:location`).
;; The pre-alpha synonym set (`:location` / `:url` / `:to`) was pruned to
;; `:location` only; there is no back-compat alias. A retired spelling
;; (`:url` / `:to`) must fail with a CLEAR diagnostic naming `:location`
;; rather than silently fall through to the no-target warning path — that
;; would hide the API vocabulary error behind a malformed-redirect warning.
(def ^:private retired-redirect-target-keys
  "Redirect-target spellings retired in favour of the canonical
  `:location` (rf2-vngir). Detected on a `:rf.server/redirect` /
  `:rf.server/safe-redirect` args map so they fail loudly rather than
  degrade into the malformed-no-target path."
  [:url :to])

(defn- reject-retired-redirect-keys!
  "Throw `:rf.error/redirect-retired-target-key` if `redirect-map` carries
  a retired redirect-target spelling (`:url` / `:to`). The canonical key is
  `:location` (rf2-vngir / EP-0007). The error NAMES `:location` so the
  programmer rewrites the spelling rather than seeing the generic
  no-target warning the host adapter emits for a target-less redirect."
  [redirect-map]
  (let [retired (filterv #(contains? redirect-map %) retired-redirect-target-keys)]
    (when (seq retired)
      (error/throw-error!
        :rf.error/redirect-retired-target-key
        'rf.ssr/response
        (str "redirect target key(s) " (pr-str retired)
             " are retired — the canonical redirect"
             " target key is :location. The SSR redirect"
             " fx writes an HTTP Location response header,"
             " so it uses header vocabulary; rewrite "
             (pr-str (first retired)) " as :location."
             " (EP-0007 one-name-per-fact; no back-compat alias.)")
        {:recovery :rewrite-the-target-key-as-location
         :extra    {:retired-keys  retired
                    :canonical-key :location}}))))

(defn redirect-fx
  "Handler fn for `:rf.server/redirect`. Defaults :status to 302 if
  absent. Multiple writes emit `:rf.warning/multiple-redirects`
  (last-write-wins). Throws `:rf.error/redirect-invalid-location` on a
  location carrying CR/LF/NUL (rf2-hbty2) — a `?next=…` query-param
  redirect would otherwise let an attacker forge headers — per Spec 011
  §CRLF fail-fast. The CR/LF/NUL header-splitting gate is the ONLY gate
  on this caller-trusted path: no URL-shape check is applied, so a raw
  space or other RFC 3986 shape quirk every browser accepts in a
  `Location` header passes through unchanged (URL-shape + origin
  validation is `:rf.server/safe-redirect`'s job, rf2-ziv4gd).

  **Canonical redirect target key is `:location`** (rf2-vngir). The
  retired synonyms `:url` / `:to` throw `:rf.error/redirect-retired-
  target-key` naming `:location` — there is no back-compat alias.

  **Caller-trusted `:location`** — accepts arbitrary URL strings without
  allowlist or relative-only gating. For caller-untrusted location
  strings (e.g. a `?next=` URL param), use `:rf.server/safe-redirect`
  (rf2-zfm8v) which parses the URL, rejects javascript:/data:/vbscript:
  schemes, and supports `:relative-only?` / `:allow [...]` policies.
  See Spec 011 §HTTP response contract §Standard fx."
  [{:keys [frame]} redirect-map]
  (reject-retired-redirect-keys! redirect-map)
  (let [;; rf2-vngir: the canonical (and only) redirect target key.
        location  (:location redirect-map)
        _         (when (some? location)
                    ;; Shared CR/LF/NUL header-splitting gate only — the
                    ;; sole structural gate on the caller-trusted path
                    ;; (Spec 011 §CRLF fail-fast). No URL-shape check:
                    ;; `:rf.server/redirect` is caller-trusted, so a raw
                    ;; space or RFC 3986 shape quirk a browser accepts
                    ;; passes through (rf2-ziv4gd). safe-redirect-fx runs
                    ;; this same gate plus its own emit-based parse.
                    (validate-redirect-location! location))
        status    (or (:status redirect-map) 302)
        normalised (cond-> (assoc redirect-map :status status)
                     location (assoc :location location))
        resp (swap-response!
               frame
               (fn [r]
                 (-> r
                     (update redirect-writes-key (fnil conj []) normalised)
                     (assoc :redirect normalised)
                     ;; Spec 011 §Redirect precedence step 1: the
                     ;; redirect's :status flows through to the
                     ;; response :status so the host adapter writes
                     ;; the redirect status on the wire even if no
                     ;; explicit :rf.server/set-status fired.
                     (assoc :status status))))]
    (warn-on-multiple-writes! resp redirect-writes-key
                              :rf.warning/multiple-redirects :final-redirect frame)))

;; ---- :rf.server/safe-redirect (rf2-zfm8v) --------------------------------
;;
;; The caller-untrusted variant of :rf.server/redirect. Where redirect-fx
;; accepts arbitrary :location strings, safe-redirect-fx parses the URL
;; and gates on the parsed URL SHAPE — not merely on the presence of a
;; host — before populating the :redirect slot:
;;
;;   1. URL must parse — :rf.error/safe-redirect-invalid-url on failure.
;;   2. Reject javascript: / data: / vbscript: schemes (no safe redirect
;;      interpretation; consistent with the rf2-vwcsq custom-editor
;;      scheme-rejection policy) — :rf.error/safe-redirect-scheme-rejected.
;;   2b. Reject any scheme outside #{http https} outright — a redirect
;;      Location is only ever an http(s) absolute URL or a relative
;;      reference; mailto:, ftp:, and other schemes have no place as a
;;      redirect target — :rf.error/safe-redirect-scheme-rejected.
;;   2c. Reject a SCHEME-BEARING URL whose host is not extractable. An
;;      http(s) URI that is opaque (`http:evil.example.com` — scheme
;;      present, authority absent) or hierarchical-without-authority
;;      (`http:/evil` — no `//host`) parses cleanly but has nil host.
;;      Java's URI.getHost returns nil for these, so the host-based gates
;;      (steps 3/4) cannot see them — yet a browser given
;;      `Location: http:evil.example.com` navigates OFF-ORIGIN. These
;;      have no defensible redirect interpretation →
;;      :rf.error/safe-redirect-invalid-url (:reason :scheme-without-host).
;;   3. :relative-only? true AND the URL is NOT a relative reference →
;;      :rf.error/safe-redirect-host-disallowed (:reason :relative-only-violation).
;;      A relative reference is `scheme == nil AND authority == nil`
;;      (e.g. `/dashboard`, `dashboard`, `a/b`). A protocol-relative
;;      `//evil.example.com` HAS an authority and is therefore NOT
;;      relative — it is rejected under :relative-only? .
;;   4. :allow [...] supplied AND URL's host not in allowlist →
;;      :rf.error/safe-redirect-host-disallowed (:reason :not-in-allowlist).
;;      A non-relative target that reaches this gate has, after step 2c,
;;      an extractable host; if it is absent from the allowlist it is
;;      rejected.
;;   5. Pass — set Location header (same shape as redirect-fx).
;;
;; All failure modes EMIT (via re-frame.trace) rather than THROW.
;; Throwing would bubble out as :rf.error/fx-handler-exception and the
;; programmer reading the trace would see a generic fx-handler-exception
;; pointing at safe-redirect-fx rather than the specific category.
;; Emit-and-no-op preserves the dispatch context — the cascade continues,
;; the response's :redirect stays unchanged, and the programmer sees
;; the specific :rf.error/safe-redirect-* category.
;;
;; Mitigation for the open-redirect class (security audit 2026-05-14
;; §P3.2; scheme-bypass hardening rf2-v3eg3 finding 1): an
;; attacker-controlled ?next=... URL parameter cannot redirect off-origin
;; when the app uses safe-redirect-fx instead of redirect-fx — including
;; the scheme-bearing opaque-URI bypass (`http:evil.example.com`) that
;; defeats a host-presence-only gate.

(def ^:private rejected-schemes
  "Closed set of schemes the safe-redirect-fx rejects outright. Per
  rf2-zfm8v decision step 2 and rf2-vwcsq's custom-editor scheme policy."
  #{"javascript" "data" "vbscript"})

(def ^:private allowed-schemes
  "Closed set of schemes a redirect Location may legitimately carry. A
  redirect target is either a relative reference (no scheme) or an
  absolute http(s) URL — every other scheme (mailto, ftp, file, tel, …)
  is rejected outright as a redirect target. Per rf2-v3eg3 finding 1
  (scheme-bypass hardening): the safe-redirect gate decides non-http(s)
  schemes by rejecting them rather than by maintaining a per-app scheme
  allowlist — an opt-in scheme allowlist can be layered later if a real
  use case appears."
  #{"http" "https"})

(def ^:private scheme-prefix-re
  "Matches the scheme prefix of an absolute URL — `<scheme>:` where the
  scheme is the conformant grammar from RFC 3986 §3.1 (alpha + alphanum
  / `+` / `-` / `.`). Used pre-parse so rejected schemes whose
  scheme-specific part is not URI-valid (e.g. `data:text/html,<script>`
  with illegal `<` in the opaque part) still surface as scheme-rejected
  rather than as parse failures. Per rf2-zfm8v validation order."
  #"^\s*([A-Za-z][A-Za-z0-9+\-.]*):")

(defn- detect-scheme
  "Cheap pre-parse scheme detection. Returns the lowercased scheme
  string (e.g. \"javascript\") if `loc` begins with `<scheme>:`, else
  nil. Used to short-circuit the rejected-schemes check before URI
  parsing — `data:text/html,<script>` is a security-relevant input
  whose body fails java.net.URI parsing but whose scheme should still
  be the visible failure mode."
  [loc]
  (when (string? loc)
    (when-some [m (re-find scheme-prefix-re loc)]
      (clojure.string/lower-case (second m)))))

(defn- parse-url-safely
  "Parse `loc` as a URL. Returns the parsed URI on success, nil on a
  parse failure (caller emits the trace). Empty / blank strings count
  as unparseable — a redirect to an empty location has no defensible
  interpretation."
  [loc]
  #?(:clj
     (when (and (string? loc) (not (clojure.string/blank? loc)))
       (try
         (URI. ^String loc)
         (catch URISyntaxException _ nil)
         (catch NullPointerException _ nil)
         (catch IllegalArgumentException _ nil)))
     :cljs
     ;; Server-side fx (:platforms #{:server}) — the CLJS branch exists
     ;; only so this .cljc compiles; the fx is silently no-op'd by
     ;; :rf.fx/skipped-on-platform on client builds. Per Spec 011
     ;; §Effect handling on the server.
     nil))

(defn- emit-safe-redirect-error!
  "Emit a structured :rf.error/safe-redirect-* trace and return nil so
  the fx body can `(or (emit-...) ...)` to a no-op."
  [operation tags]
  (trace/emit-error! operation
                     (merge {:recovery :no-recovery} tags))
  nil)

(defn safe-redirect-fx
  "Handler fn for `:rf.server/safe-redirect`. The caller-untrusted variant
  of `redirect-fx` — validates `:location` against a five-step gate
  (parse → scheme → relative-only → allowlist → pass) before populating
  the response accumulator's `:redirect` slot.

  Args map:

    {:location       \"/dashboard\"      ;; or full URL
     :relative-only? true                  ;; reject any URL with a host
     :allow          [\"app.example.com\" \"alt.example.com\"]  ;; host allowlist
     :status         302}                  ;; defaults 302 if absent

  Validation order (per Spec 009 §Error event catalogue). The gate is on
  the parsed URL SHAPE, not merely on host presence (rf2-v3eg3 finding 1):

    1.  URL parses → :rf.error/safe-redirect-invalid-url on failure
    2.  scheme ∈ #{javascript data vbscript} → :rf.error/safe-redirect-scheme-rejected
    2b. scheme ∉ #{http https} → :rf.error/safe-redirect-scheme-rejected
        (:reason :scheme-not-allowed) — mailto:, ftp:, file:, … rejected
    2c. scheme present but host not extractable (opaque `http:evil` or
        authority-less `http:/evil`) → :rf.error/safe-redirect-invalid-url
        (:reason :scheme-without-host) — the scheme-bearing open-redirect
        bypass that defeats a host-presence-only gate
    3.  :relative-only? true + URL is NOT a relative reference (has a
        scheme OR an authority — incl. protocol-relative `//host`) →
        :rf.error/safe-redirect-host-disallowed (:reason :relative-only-violation)
    4.  :allow supplied + host ∉ allow → :rf.error/safe-redirect-host-disallowed
        (:reason :not-in-allowlist)
    5.  Pass → set :redirect (same shape as :rf.server/redirect)

  Mitigation for the open-redirect class (audit 2026-05-14 §P3.2;
  scheme-bypass hardening rf2-v3eg3 finding 1): an attacker-controlled
  ?next=... URL parameter cannot redirect off-origin when the app uses
  safe-redirect-fx — including scheme-bearing opaque URIs such as
  `http:evil.example.com`.

  Per rf2-zfm8v (Mike decision, Option A — ship safe-redirect-fx
  alongside redirect-fx, 2026-05-14)."
  [{:keys [frame]} {:keys [location relative-only? allow status]
                    :as redirect-map}]
  ;; rf2-vngir: reject the retired `:url` / `:to` spellings with a clear
  ;; diagnostic naming `:location` before anything else — a safe-redirect
  ;; keyed on a retired spelling would otherwise present as a missing
  ;; (nil) `:location` and fail as a generic parse error, hiding the
  ;; vocabulary mistake.
  (reject-retired-redirect-keys! redirect-map)
  ;; Run the CR/LF/NUL gate first — same defence-in-depth as the
  ;; caller-trusted redirect-fx (rf2-hbty2). A safe-redirect caller
  ;; passing a CRLF-bearing location is presumably trying both vectors;
  ;; reject at the same fx boundary the trusted variant uses.
  (validate-redirect-location! location)

  ;; Validation order per rf2-zfm8v / Spec 009 §Error event catalogue.
  ;;
  ;; Scheme rejection (step 2) runs BEFORE URL parse (step 1) for the
  ;; rejected-schemes set because schemes like `data:text/html,<script>`
  ;; carry illegal characters in their opaque part that java.net.URI
  ;; rejects at parse time. The security-relevant signal — "this scheme
  ;; is dangerous" — must surface as :rf.error/safe-redirect-scheme-rejected
  ;; rather than getting swallowed by :rf.error/safe-redirect-invalid-url.
  ;; A simple `<scheme>:` prefix match is enough to identify the rejected
  ;; schemes; the URI parser is still the source of truth for everything
  ;; else (host extraction, allowlist matching).
  (let [pre-scheme (detect-scheme location)]
    (if (and pre-scheme (rejected-schemes pre-scheme))
      (emit-safe-redirect-error! :rf.error/safe-redirect-scheme-rejected
                                 {:frame    frame
                                  :location location
                                  :scheme   pre-scheme})
      (let [uri (parse-url-safely location)]
        (cond
          ;; Step 1: parse failure
          (nil? uri)
          (emit-safe-redirect-error! :rf.error/safe-redirect-invalid-url
                                     {:frame    frame
                                      :location location
                                      :reason   "URL did not parse"})

          :else
          (let [scheme    #?(:clj (.getScheme    ^URI uri) :cljs nil)
                host      #?(:clj (.getHost       ^URI uri) :cljs nil)
                authority #?(:clj (.getAuthority  ^URI uri) :cljs nil)
                scheme-lc (when scheme (clojure.string/lower-case scheme))
                ;; A relative reference carries neither a scheme nor an
                ;; authority — `/dashboard`, `dashboard`, `a/b`. This is
                ;; the open-redirect-safe shape: the browser resolves it
                ;; against the current origin. A protocol-relative
                ;; `//evil.example.com` has an authority (host non-nil)
                ;; and is NOT relative. A scheme-bearing opaque URI
                ;; `http:evil.example.com` has a scheme and is NOT
                ;; relative — even though Java reports its host as nil.
                ;; (rf2-v3eg3 finding 1: shape, not host-presence.)
                relative? (and (nil? scheme) (nil? authority))]
            (cond
              ;; Step 2: scheme rejection (post-parse path — covers
              ;; schemes whose body DID parse cleanly, e.g.
              ;; `javascript:foo` without parens).
              (and scheme-lc (rejected-schemes scheme-lc))
              (emit-safe-redirect-error! :rf.error/safe-redirect-scheme-rejected
                                         {:frame    frame
                                          :location location
                                          :scheme   scheme})

              ;; Step 2b: any non-http(s) scheme is rejected outright. A
              ;; redirect Location is an http(s) absolute URL or a
              ;; relative reference — mailto:, ftp:, file:, tel:, etc.
              ;; have no defensible redirect interpretation and would
              ;; otherwise slip the host-based gates (nil host).
              ;; (rf2-v3eg3 finding 1.)
              (and scheme-lc (not (allowed-schemes scheme-lc)))
              (emit-safe-redirect-error! :rf.error/safe-redirect-scheme-rejected
                                         {:frame    frame
                                          :location location
                                          :scheme   scheme
                                          :reason   :scheme-not-allowed})

              ;; Step 2c: a scheme-bearing http(s) URL whose host is not
              ;; extractable — an opaque URI (`http:evil.example.com`) or
              ;; a hierarchical URI with no authority (`http:/evil`).
              ;; These parse cleanly with a nil host, so the host-based
              ;; gates below cannot see them, yet a browser navigates
              ;; off-origin on `Location: http:evil.example.com`. No
              ;; defensible redirect interpretation. (rf2-v3eg3 finding 1
              ;; — the scheme-bypass that defeated the host-presence-only
              ;; open-redirect gate.)
              (and scheme-lc (nil? host))
              (emit-safe-redirect-error! :rf.error/safe-redirect-invalid-url
                                         {:frame    frame
                                          :location location
                                          :scheme   scheme
                                          :reason   :scheme-without-host})

              ;; Step 3: :relative-only? gate — reject anything that is
              ;; NOT a relative reference (has a scheme OR an authority),
              ;; not merely anything whose host is extractable. This
              ;; closes the opaque-URI and protocol-relative bypasses.
              (and relative-only? (not relative?))
              (emit-safe-redirect-error! :rf.error/safe-redirect-host-disallowed
                                         {:frame    frame
                                          :location location
                                          :host     host
                                          :reason   :relative-only-violation})

              ;; Step 4: :allow [...] allowlist. After step 2c every
              ;; non-relative target reaching here has an extractable
              ;; host. A relative reference (no host) is allowed through
              ;; — :allow gates absolute targets, it does not block
              ;; same-origin relative redirects. DNS hostnames are
              ;; case-insensitive (RFC 1035 §2.3.3), so lower-case both
              ;; sides — matching the header/cookie token-grammar
              ;; treatment elsewhere in this file.
              (and (seq allow)
                   host
                   (not (contains? (into #{} (map clojure.string/lower-case) allow)
                                   (clojure.string/lower-case host))))
              (emit-safe-redirect-error! :rf.error/safe-redirect-host-disallowed
                                         {:frame    frame
                                          :location location
                                          :host     host
                                          :reason    :not-in-allowlist
                                          :allowlist (vec allow)})

              ;; Step 5: pass — populate :redirect, mirror redirect-fx's
              ;; status-flow-through behaviour. Strip :allow / :relative-only?
              ;; from the persisted shape — they're policy inputs, not part
              ;; of the wire redirect.
              :else
              (let [final-status (or status 302)
                    normalised   (-> redirect-map
                                     (dissoc :allow :relative-only?)
                                     (assoc :status   final-status
                                            :location location))
                    resp         (swap-response!
                                   frame
                                   (fn [r]
                                     (-> r
                                         (update redirect-writes-key
                                                 (fnil conj []) normalised)
                                         (assoc :redirect normalised)
                                         ;; Spec 011 §Redirect precedence step 1:
                                         ;; status flows through.
                                         (assoc :status final-status))))]
                (warn-on-multiple-writes! resp redirect-writes-key
                                          :rf.warning/multiple-redirects
                                          :final-redirect frame)))))))))
