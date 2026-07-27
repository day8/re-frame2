(ns re-frame.ssr.response
  "HTTP response accumulator and the seven `:rf.server/*` effect handlers.

  The runtime owns a per-request response accumulator in a
  framework-private side-channel atom keyed by frame-id — `response-slots`
  below. Standard server-only fx populate the slot during the drain;
  the host adapter consumes the resolved value after drain to build the
  wire response.

  The accumulator must not live in `app-db`. Keeping it symmetric with the
  request side channel has two important consequences:

  - **Privacy.** `app-db` is the hydration payload's source (the
    `:rf/app-db` slice ships to the client on bootstrap). The response
    accumulator routinely carries server-only data — `Set-Cookie`
    headers (auth tokens, session ids), internal `X-*` headers, redirect
    URLs that may encode internal hostnames or secrets. Storing the
    accumulator in `app-db` would default-leak that surface onto the
    wire and force every host adapter to remember a defensive
    `(dissoc :rf/response)` before serialising the payload — a privacy
    caller must remember to apply. Side-channel storage enforces the boundary.
  - **Atomicity.** Each response effect swaps one per-frame accumulator.
    Response writes never replace application state or expose partially built
    response data through subscriptions.

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

  Frame teardown releases the response slot together with the other SSR side
  channels."
  (:require [clojure.string]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.egress :as egress]
            [re-frame.ssr.http-validation :as http-validation]
            [re-frame.trace :as trace])
  #?(:clj (:import [java.net URI URISyntaxException])))

(defonce
  ^{:doc "Per-frame storage for the HTTP response accumulator. Keys are
  frame-ids; values are the accumulator map (`{:status :headers :cookies
  :redirect ...}` plus internal `:rf.server/_status-writes` /
  `:rf.server/_redirect-writes` bookkeeping). Framework-private —
  not stored in `app-db` so the accumulator never rides the hydration
  payload to the client. Cleared per-frame by the
  `:ssr/on-frame-destroyed` hook."}
  response-slots
  (atom {}))

;; ---- the reserved `:rf.server/*` ARGS SHAPE gate (rf2-dtpfv) -------------
;;
;; WHAT WAS BROKEN. Spec 010 §Validation-order step 5 validates an fx's args
;; against the `:schema` on its registration meta, and Spec 010 §Per-step
;; recovery row 5 says the offending fx is `:skipped`. That gate is
;; `re-frame.schemas.validate/validate-fx!`, whose body is
;; `(if interop/debug-enabled? (run-validation …) true)` — and
;; `interop/debug-enabled?` is read ONCE at namespace-load time. Under
;; `-Dre-frame.debug=false` the boundary therefore does not run AT ALL and
;; nothing is skipped: a malformed reserved fx RUNS, and its args land on the
;; per-request response accumulator that `ssr/get-response` publishes to every
;; host adapter. Measured at that surface before this gate existed:
;;
;;   [:rf.server/set-status "not-an-int"]              → :status "not-an-int"
;;   [:rf.server/set-header {:name "X-Foo"}]           → ["X-Foo" nil]
;;   [:rf.server/set-cookie {:name "session"}]         → {:name "session"}
;;   [:rf.server/safe-redirect {:location "/ok"
;;                              :status "not-int"}]    → the malformed redirect
;;
;; THE RULING (rf2-dtpfv, option (b)). The general step-5 gate for USER fx
;; stays dev-only — trust-the-programmer covers user declarations, and paying
;; a hot-path validation cost in every app to protect seven closed framework
;; effects is posture-hostile. What trust-the-programmer does NOT cover is the
;; framework's own wire-adjacent contract: these seven fx are a CLOSED set the
;; framework itself publishes, their args feed the HTTP response head, and the
;; accumulator is a PUBLIC host-adapter surface whose shape must not depend on
;; which build you are running. So the reserved family guards its own args
;; UNCONDITIONALLY, in every build.
;;
;; This is not a new ownership pattern — it COMPLETES the one already in this
;; file. `validate-header-name!` / `validate-header-value!` /
;; `validate-cookie-name!` / `validate-cookie-attr!` /
;; `validate-redirect-location!` all `error/throw-error!` unconditionally
;; before `swap-response!`, covering the CR/LF/NUL + token-grammar injection
;; surface. They are the WIRE-GRAMMAR half. This section is the SHAPE half:
;; the published TYPE contract of each args map (Spec 011 §Standard fx), which
;; nothing enforced outside a debug build.
;;
;; MECHANISM — throw-through-containment, not skip-silently. The guards throw
;; the canonical `re-frame.error/throw-error!` BEFORE the first
;; `swap-response!`. The registered-fx containment in `re-frame.fx` then
;; supplies the wanted semantics with zero new machinery: the accumulator is
;; never mutated, sibling fx in the same `:fx` vector still run, an ALWAYS-ON
;; `:rf.error/fx-handler-exception` record survives production, and SSR's error
;; projection serves the sanitised 500. Fail-closed is right for a
;; wire-adjacent programmer error that survived to production — a silently
;; dropped `Set-Cookie` under a 200 is worse than an honest, observable 500.
;;
;; TAXONOMY — the line this gate does NOT cross. A malformed framework CALL is
;; a programmer error and throws. An untrusted-INPUT policy decision — a
;; `:rf.server/safe-redirect` target that parses but points off-origin — stays
;; the existing non-projecting emit-and-no-op (see `safe-redirect-fx`). A
;; caller-untrusted `:location` that is a STRING reaches the five-step gate
;; exactly as before; only a `:location` that is not a string at all (a
;; missing key, a keyword, a URI object) is a call the programmer got wrong.
;;
;; POSTURE. The recovery PATH still differs by build, and that is documented
;; rather than hidden: dev-with-schemas gets the Malli step-5 skip plus its
;; rich `:where :fx-args` diagnostic and the page still renders; production (or
;; a schemas-less dev build) gets this guard's throw → containment → always-on
;; record → sanitised 500. What no longer differs is the thing that matters:
;; `get-response` never yields a malformed `:rf/response` shape in ANY build.
;;
;; COST. Cheap predicates over a closed set — `integer?`, `string?`,
;; `boolean?`, a range compare, a table walk of at most eight cookie attrs. No
;; Malli, no schemas-artefact dependency, no runtime schema interpretation, and
;; nothing at all on the user-fx path.

(def ^:private min-http-status
  "Lowest status code the HTTP status line admits (RFC 9110 §15)."
  100)

(def ^:private max-http-status
  "Highest status code the HTTP status line admits (RFC 9110 §15)."
  599)

(defn- reject-arg!
  "Throw the catalogued `:rf.error/server-fx-args-invalid` (Spec 009) for a
  reserved `:rf.server/*` fx argument that violates its published TYPE
  contract. ONE category for the whole shape surface, with the offending
  `:key` carried as data — the same decision `:rf.error/cookie-invalid-
  attribute` records for the injection surface a few forms below: the
  violation class is one fact, and WHICH key carried it is data, not a
  distinct error id.

  `value` never reaches the payload raw. A cookie `:value` is a session
  token and a header `:value` routinely carries app-owned content, so both
  the message and the ex-data slot carry `error/diag-value-summary` — the
  EP-0015-safe SHAPE summary (`{:type …:count …:head …}`), which is also
  precisely what a type violation needs to be diagnosable."
  [fx-id arg-key value expected]
  (error/throw-error!
    :rf.error/server-fx-args-invalid
    'rf.ssr/response
    (str fx-id " " arg-key " must be " expected "; got "
         (pr-str (error/diag-value-summary value))
         ". Fix the fx args map at the dispatch site — the reserved"
         " :rf.server/* fx guard their own args in every build, so this"
         " rejection does not depend on -Dre-frame.debug.")
    {:recovery :supply-a-well-formed-fx-argument
     :extra    {:fx-id    fx-id
                :key      arg-key
                :value    (error/diag-value-summary value)
                :expected expected}}))

(defn- validate-args-map!
  "Throw `:rf.error/server-fx-args-invalid` unless the fx's args value is a
  map. Runs FIRST on every map-args fx so a non-map (a bare string, a vector)
  fails with the catalogued category rather than as a raw host
  `IllegalArgumentException` out of the first `contains?` / `get`."
  [fx-id args]
  (when-not (map? args)
    (reject-arg! fx-id :args args "a map"))
  args)

(defn- validate-status!
  "Throw `:rf.error/server-fx-args-invalid` unless `status` is an integer in
  the RFC 9110 §15 status-code range. A non-integer status is the bead's
  headline defect: it rode onto the accumulator, and only the Ring adapter's
  `fail-closed-status` (host-specific, and silent on a production JVM)
  stopped it reaching a wire. Shared by `:rf.server/set-status` and by both
  redirect fx, whose `:status` flows through to `:status` per Spec 011
  §Redirect precedence step 1."
  [fx-id status]
  (when-not (and (integer? status)
                 (<= min-http-status status max-http-status))
    (reject-arg! fx-id :status status
                 (str "an integer HTTP status code in "
                      min-http-status "–" max-http-status)))
  status)

(defn- validate-string-arg!
  "Throw `:rf.error/server-fx-args-invalid` unless `v` is a string. Used for
  the args a host adapter writes VERBATIM onto the response head."
  [fx-id arg-key v]
  (when-not (string? v)
    (reject-arg! fx-id arg-key v "a string"))
  v)

(def ^:private cookie-attr-shape
  "The published TYPE contract of the `:rf.server/cookie` attributes, as a
  table of `[attr-key required? pred expected]`. Kept as an explicit literal
  for the same reason `checked-cookie-attrs` below is: the accepted shape is
  auditable in place rather than implied by a call sequence.

  It mirrors `re-frame.ssr.server-fx-schemas/cookie` ROW FOR ROW, including
  that schema's three documented INGRESS TOLERANCES — `:max-age`, `:expires`
  and `:same-site` accept their canonical type OR a string, because apps
  build cookie attrs from host data that arrives as strings and the
  per-attribute CR/LF gate must SEE those strings to reject a forged
  `\"3600\\r\\nSet-Cookie: admin=1\"`. A string `:expires` is deliberately
  tolerated here and rejected later by the Ring materialiser
  (`:rf.error/cookie-invalid-expires`), exactly as the schema's docstring
  says. `ssr-reserved-fx-guards-test`'s acceptance corpus drives this table
  and the Malli schema from one literal so the two cannot drift.

  `:name` is NOT here — it keeps its own richer gate
  (`validate-cookie-name!`), which deliberately admits a keyword / symbol to
  mirror the Ring materialiser's `clojure.lang.Named` check."
  [[:value     true  string?  "a string"]
   [:path      false string?  "a string"]
   [:domain    false string?  "a string"]
   [:max-age   false #(or (integer? %) (string? %))
    "an integer or a string"]
   [:expires   false #(or (integer? %) (string? %))
    "an integer of epoch millis, or a string"]
   [:same-site false #(or (contains? #{:strict :lax :none} %) (string? %))
    "one of :strict / :lax / :none, or a string"]
   [:secure    false boolean? "a boolean"]
   [:http-only false boolean? "a boolean"]])

(defn- validate-cookie-shape!
  "Walk `cookie-attr-shape` and throw `:rf.error/server-fx-args-invalid` on
  the first attribute that is missing-though-required or present with the
  wrong type. An explicit `nil` counts as absent for an optional attribute —
  the same `(some? v)` convention `validate-cookie!` already applies to the
  injection gate."
  [fx-id cookie]
  (doseq [[k required? ok? expected] cookie-attr-shape
          :let [v (get cookie k)]]
    (cond
      (nil? v)      (when required?
                      (reject-arg! fx-id k v (str expected ", and it is required")))
      (not (ok? v)) (reject-arg! fx-id k v expected)))
  cookie)

;; Header-value injection gate. set-header / append-header / redirect flow
;; caller-controlled
;; strings straight into the Ring response map; an attacker who controls
;; a header value with embedded CR/LF can split the header and forge
;; second-and-later headers on the wire (RFC 7230 §3.2.4 explicitly
;; bans CTLs in header values). We reject at the fx boundary rather
;; than the Ring materialiser so misuse surfaces with the dispatching
;; event in scope rather than as a deep host-adapter exception.
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
  caller-trusted path: no URL-shape check is applied, so a raw space or
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

;; Header-name and cookie-field gates run at the effect boundary. The Ring
;; host adapter re-validates at materialisation time, but the SSR layer is also the
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
  whitespace, or separators. Same gate as the cookie-name validator."
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
  Applied at the effect boundary so every host adapter receives the same
  validation."
  [n]
  (when (nil? n)
    (error/throw-error!
      :rf.error/cookie-invalid-name
      'rf.ssr/response
      "cookie :name is required; supply a non-nil :name on the cookie map."
      {:recovery :supply-a-cookie-name
       :extra    {:name n}}))
  ;; Type-guard before `(name n)`: a cookie `:name` is only a
  ;; string or a Named (keyword / symbol); anything else (a Long `42`, a
  ;; vector `[]`, a map `{}`, a boolean `true`, …) makes `name` throw a raw
  ;; host exception (`ClassCastException` on the JVM) with nil ex-data,
  ;; bypassing the documented `:rf.error/cookie-invalid-name` contract and
  ;; reaching adapter / `:on-error` code as a generic host exception. The
  ;; args-schema boundary catches this only when a validator is live and the
  ;; schema pins the type — when schemas are absent or soft-pass (Spec 010),
  ;; the fx boundary is the last line of defence, so reject the unsupported
  ;; TYPE loudly through the same structured error id. Mirrors the Ring
  ;; materialiser's guard; the JVM branch matches Ring's
  ;; `clojure.lang.Named` check exactly (the fx runs
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

(def ^:private checked-cookie-attrs
  "The cookie attributes a host adapter concatenates verbatim into the
  Set-Cookie wire form and that the fx boundary therefore CR/LF/NUL-gates
  (`:value`, `:path`, `:domain`, `:max-age`, `:same-site`, `:expires`). An
  explicit greppable literal — the checked set is auditable in place rather
  than implied by a call sequence, and every member on an injection-char
  failure throws the SINGLE catalogued `:rf.error/cookie-invalid-attribute`
  (Spec 009), carrying the offending attribute in the payload's `:attribute`
  slot rather than in a per-attribute error id. `:name` is not
  here — it carries its own RFC 6265 §4.1.1 token-grammar +
  type gate (`validate-cookie-name!` → `:rf.error/cookie-invalid-name`)."
  [:value :path :domain :max-age :same-site :expires])

(defn- validate-cookie-attr!
  "Throw the catalogued `:rf.error/cookie-invalid-attribute` (Spec 009) if
  the cookie attribute string carries a forbidden wire-grammar char. Applied
  to EVERY attribute a host adapter concatenates into the Set-Cookie wire
  form — the members of `checked-cookie-attrs`.

  Two grammars, one error id:
    - `:value` is percent-encoded by the host serialiser, so it bans only
      the CR / LF / NUL header-splitting chars (a `;` in `:value` is safe —
      it serialises as `%3B`).
    - every OTHER attribute (`:path` / `:domain` / `:max-age` /
      `:same-site` / `:expires`) is concatenated VERBATIM after `; `
      separators, so it additionally bans the raw `;` RFC 6265 §4.1.1
      cookie-attribute delimiter — a `;` there escapes its attribute and
      fabricates extra attributes (`SameSite=None`, `Secure`, `Max-Age=0`,
      …), defeating the structured-cookie boundary.

  The value is `str`-coerced first because callers legitimately pass
  non-string forms (`:max-age` as an int, `:expires` as an epoch-millis
  long); the ban applies to the serialised form a host adapter would emit.
  The offending attribute rides the payload's `:attribute` slot — the SAME
  error id + `{:attribute :value}` emit shape the Ring materialiser throws,
  so the fx boundary and the wire serialiser share one catalogued
  vocabulary: the injection class is one fact, and WHICH attribute carried
  the injection char is data, not a distinct error id. Spec 011 §CRLF
  fail-fast mandates checking EVERY attribute, not just :value — an attacker
  who controls any one attribute must not be able to re-enter the header
  line as CRLF- or delimiter-bearing payload)."
  [attr-key v]
  (let [s      (str v)
        ;; `:value` is percent-encoded downstream, so `;` there is data;
        ;; every other attribute is concatenated verbatim and must also
        ;; reject the `;` cookie-attribute delimiter.
        value? (= attr-key :value)
        bad?   (if value?
                 (http-validation/contains-injection-char? s)
                 (http-validation/contains-cookie-attr-injection-char? s))]
    (when bad?
      (error/throw-error!
        :rf.error/cookie-invalid-attribute
        'rf.ssr/response
        (str "cookie attribute " attr-key " " (pr-str s)
             (if value?
               " contains CR/LF/NUL — forbidden by RFC 7230 §3.2.4"
               (str " contains CR/LF/NUL or a `;` — forbidden by"
                    " RFC 7230 §3.2.4 and RFC 6265 §4.1.1 (the `;` is the"
                    " cookie-attribute delimiter)"))
             " (header-splitting / attribute injection). Strip "
             (if value? "CR/LF/NUL" "CR/LF/NUL and `;`")
             " from the cookie " (name attr-key) ".")
        {:recovery :remove-injection-chars-from-cookie-attr
         :extra    {:attribute attr-key
                    :value     v}}))
    s))

(defn- validate-cookie!
  "Run name and field validators across the cookie map. Gate the structured
  cookie at the effect boundary so misuse surfaces with
  the dispatching event in scope rather than as a deep adapter exception.

  CRLF-checks EVERY attribute a host adapter serialises into the
  Set-Cookie line, not just `:value` (Spec 011 §CRLF
  fail-fast). The fx boundary is the single enforcement point for
  non-Ring host adapters (Pedestal / HttpKit / custom) that read the
  accumulator and serialise cookies themselves; the Ring materialiser
  re-checks at wire-write time, but the two boundaries must not diverge
  on what they accept. `:max-age` and `:expires` are commonly non-string
  (int / instant) — `validate-cookie-attr!` `str`-coerces before the
  CR/LF/NUL ban, so a string `:max-age` sourced from request context
  (`\"3600\\r\\nSet-Cookie: admin=1\"`) is rejected at the fx boundary
  rather than splitting the header on a non-Ring host."
  [fx-id cookie]
  (validate-args-map! fx-id cookie)
  ;; Name first, so a name-less cookie keeps the catalogued
  ;; `:rf.error/cookie-invalid-name` it has always thrown; SHAPE next
  ;; (rf2-dtpfv), so a `:value`-less cookie no longer reaches the
  ;; accumulator in a release build; the injection gate last, on values now
  ;; known to be well-typed.
  (validate-cookie-name!  (:name cookie))
  (validate-cookie-shape! fx-id cookie)
  (doseq [attr-key checked-cookie-attrs
          :let  [v (get cookie attr-key)]
          :when (some? v)]
    (validate-cookie-attr! attr-key v))
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
  on the process-local frame address — O(small-map) swap, no app-db
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
  `:ssr/on-frame-destroyed` late-bind hook. Idempotent — tolerates a frame-id
  with no slot."
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
  emits `:rf.warning/multiple-status-set`. Throws
  `:rf.error/server-fx-args-invalid` — in EVERY build (rf2-dtpfv) — unless
  the status is an integer in 100–599, so a string status can no longer
  reach `ssr/get-response`."
  [{:keys [frame]} status]
  (validate-status! :rf.server/set-status status)
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
  §3.2.6 token grammar, and `:rf.error/header-invalid-value` on a value
  carrying CR/LF/NUL. Throws `:rf.error/server-fx-args-invalid` — in EVERY
  build (rf2-dtpfv) — on a non-string `:name` / `:value`, so a `:value`-less
  call can no longer put `[\"X-Foo\" nil]` on the accumulator."
  [{:keys [frame]} {:keys [name value] :as args}]
  (validate-args-map!    :rf.server/set-header args)
  (validate-header-name! name)
  ;; The string gate runs AFTER the grammar gate so a nil / keyword `:name`
  ;; keeps the catalogued `:rf.error/header-invalid-name` it has always
  ;; thrown ("" and ":x-foo" both fail the token grammar); what it adds is
  ;; the case the grammar cannot see — a symbol or a number, which `(str …)`
  ;; renders as a perfectly legal token and which therefore used to pass.
  (validate-string-arg!  :rf.server/set-header :name  name)
  (validate-string-arg!  :rf.server/set-header :value value)
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [r] (update r :headers replace-header name value))))

(defn append-header-fx
  "Handler fn for `:rf.server/append-header`. Preserves any existing
  header with the same name — required for Set-Cookie-style multi-valued
  headers. Throws `:rf.error/header-invalid-name` on a name violating the
  RFC 7230 §3.2.6 token grammar, and `:rf.error/header-invalid-value` on a
  value carrying CR/LF/NUL. Throws `:rf.error/server-fx-args-invalid` — in
  EVERY build (rf2-dtpfv) — on a non-string `:name` / `:value`; see
  `set-header-fx` for why the string gate follows the grammar gate."
  [{:keys [frame]} {:keys [name value] :as args}]
  (validate-args-map!    :rf.server/append-header args)
  (validate-header-name! name)
  (validate-string-arg!  :rf.server/append-header :name  name)
  (validate-string-arg!  :rf.server/append-header :value value)
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [r] (update r :headers append-header-pair name value))))

(defn set-cookie-fx
  "Handler fn for `:rf.server/set-cookie`. Cookie attributes are stored
  as a structured map (RFC 6265 wire-form serialisation is host-adapter
  business). Throws `:rf.error/cookie-invalid-name` on a name violating
  the RFC 6265 §4.1.1 token grammar, and the single catalogued
  `:rf.error/cookie-invalid-attribute` (carrying the offending
  `:attribute` + `:value` in its payload) on a CRLF/NUL-bearing
  attribute string — rf2-z7gor gates the structured cookie at the fx
  boundary so non-ring host adapters get the same safety the ring
  materialiser (rf2-rpedl) provides at wire-write time, and rf2-xrk4w1
  collapses the per-attribute injection failures onto the one catalogued
  id the ring serialiser already throws. Throws
  `:rf.error/server-fx-args-invalid` — in EVERY build (rf2-dtpfv) — on an
  attribute violating its published type, so a `:value`-less cookie can no
  longer reach `ssr/get-response`."
  [{:keys [frame]} cookie-map]
  (validate-cookie! :rf.server/set-cookie cookie-map)
  (swap-response!
    frame
    (fn [r] (update r :cookies (fnil conj []) cookie-map))))

(defn delete-cookie-fx
  "Handler fn for `:rf.server/delete-cookie`. Sugar over set-cookie
  with :max-age 0 and an empty :value. Applies the same name, path, and
  domain validation as `set-cookie-fx` — including the always-on
  `:rf.error/server-fx-args-invalid` shape gate (rf2-dtpfv), which reaches
  this fx's caller-supplied `:path` / `:domain` through the synthesised
  cookie (`:value` / `:max-age` are framework-supplied and well-formed by
  construction)."
  [{:keys [frame]} {:keys [name path domain] :as args}]
  (validate-args-map! :rf.server/delete-cookie args)
  (let [cookie (cond-> {:name    name
                        :value   ""
                        :max-age 0}
                 path   (assoc :path   path)
                 domain (assoc :domain domain))]
    (validate-cookie! :rf.server/delete-cookie cookie)
    (swap-response!
      frame
      (fn [r] (update r :cookies (fnil conj []) cookie)))))

;; The redirect target key is `:location`, matching the HTTP `Location` response
;; header, so it uses header vocabulary (routing/navigation surfaces may
;; use `:url` / `:to`; HTTP-response redirect surfaces use `:location`).
;; The unsupported `:url` and `:to` spellings have no compatibility alias. An
;; unsupported spelling
;; (`:url` / `:to`) must fail with a CLEAR diagnostic naming `:location`
;; rather than silently fall through to the no-target warning path — that
;; would hide the API vocabulary error behind a malformed-redirect warning.
(def ^:private retired-redirect-target-keys
  "Redirect-target spellings unsupported in favour of canonical `:location`.
  Detected on a `:rf.server/redirect` /
  `:rf.server/safe-redirect` args map so they fail loudly rather than
  degrade into the malformed-no-target path."
  [:url :to])

(defn- reject-retired-redirect-keys!
  "Throw `:rf.error/redirect-retired-target-key` if `redirect-map` carries
  an unsupported redirect-target spelling (`:url` / `:to`). The canonical key
  is `:location`. The error names `:location` so the
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
  location carrying CR/LF/NUL — a `?next=…` query-param
  redirect would otherwise let an attacker forge headers — per Spec 011
  §CRLF fail-fast. The CR/LF/NUL header-splitting gate is the ONLY gate
  on this caller-trusted path: no URL-shape check is applied, so a raw
  space or other RFC 3986 shape quirk every browser accepts in a
  `Location` header passes through unchanged (URL-shape + origin
  validation is `:rf.server/safe-redirect`'s job).

  **Canonical redirect target key is `:location`.** The unsupported
  synonyms `:url` / `:to` throw `:rf.error/redirect-retired-
  target-key` naming `:location` — there is no back-compat alias.

  **Caller-trusted `:location`** — accepts arbitrary URL strings without
  allowlist or relative-only gating. For caller-untrusted location
  strings (e.g. a `?next=` URL param), use `:rf.server/safe-redirect`, which
  parses the URL, rejects javascript:/data:/vbscript:
  schemes, and supports `:relative-only?` / `:allow [...]` policies.
  See Spec 011 §HTTP response contract §Standard fx.

  Throws `:rf.error/server-fx-args-invalid` — in EVERY build (rf2-dtpfv) —
  on a non-string `:location` or a `:status` outside the integer 100–599
  range. The documented NO-TARGET graceful path is untouched: `:location`
  stays OPTIONAL, so a target-less redirect still sets `:redirect` and still
  falls through to the adapter's `:rf.ssr/ssr-redirect-no-target` warn→3xx."
  [{:keys [frame]} redirect-map]
  (validate-args-map! :rf.server/redirect redirect-map)
  (reject-retired-redirect-keys! redirect-map)
  (let [;; The canonical and only redirect target key.
        location  (:location redirect-map)
        _         (when (some? location)
                    ;; SHAPE first (rf2-dtpfv): the CR/LF/NUL gate below
                    ;; `str`-coerces, so a keyword or URI object used to
                    ;; sail through it and land on the accumulator.
                    (validate-string-arg! :rf.server/redirect :location location))
        _         (when (some? location)
                    ;; Shared CR/LF/NUL header-splitting gate only — the
                    ;; sole structural gate on the caller-trusted path
                    ;; (Spec 011 §CRLF fail-fast). No URL-shape check:
                    ;; `:rf.server/redirect` is caller-trusted, so a raw
                    ;; space or RFC 3986 shape quirk a browser accepts
                    ;; passes through. safe-redirect-fx runs
                    ;; this same gate plus its own emit-based parse.
                    (validate-redirect-location! location))
        status    (or (:status redirect-map) 302)
        ;; The redirect's :status flows through to the response :status
        ;; (Spec 011 §Redirect precedence step 1), so it is held to the same
        ;; always-on range gate as :rf.server/set-status.
        _          (validate-status! :rf.server/redirect status)
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

;; ---- :rf.server/safe-redirect ---------------------------------------------
;;
;; The caller-untrusted variant of :rf.server/redirect. Where redirect-fx
;; accepts arbitrary :location strings, safe-redirect-fx parses the URL
;; and gates on the parsed URL SHAPE — not merely on the presence of a
;; host — before populating the :redirect slot:
;;
;;   1. URL must parse — :rf.error/safe-redirect-invalid-url on failure.
;;   2. Reject javascript: / data: / vbscript: schemes —
;;      :rf.error/safe-redirect-scheme-rejected.
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
;; This fail-closed shape prevents caller-controlled targets from redirecting
;; off-origin, including opaque scheme-bearing forms such as
;; `http:evil.example.com` that defeat a host-presence-only test.

(def ^:private rejected-schemes
  "Closed set of schemes the safe-redirect effect rejects outright."
  #{"javascript" "data" "vbscript"})

(def ^:private allowed-schemes
  "Closed set of schemes a redirect Location may legitimately carry. A
  redirect target is either a relative reference (no scheme) or an
  absolute http(s) URL — every other scheme (mailto, ftp, file, tel, …)
  is rejected outright as a redirect target. The safe-redirect gate decides non-http(s)
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
  rather than as parse failures."
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

(defn- safe-redirect-tags
  "The `:rf.error/safe-redirect-*` DIAGNOSTIC tag map for a rejected redirect
  — built ONCE, so the production record and the dev trace cannot disagree
  about the same rejection.

  This is the RICH map: it goes to the dev trace as-is, and
  `dispatch-safe-redirect-record!` projects it down to a closed structural
  subset before the always-on axis sees it (rf2-6jqa8 AUDIT-REOPEN — see
  `egress/safe-redirect-record-tags`). That is the ordinary EP-0015
  relationship: a local operator sees their own process in full, and the
  off-box record is a strict projection of it, never a copy.

  EP-0015 (rf2-6jqa8): `:location` is BY CONSTRUCTION caller-untrusted —
  that is the entire reason `:rf.server/safe-redirect` exists as the sibling
  of the caller-trusted `:rf.server/redirect` — and a rejected target
  routinely looks like `?next=https://evil.example.com/cb?token=…`.
  `egress/redact-url-tag` scrubs the query / fragment carrier VALUES (keeping
  the structured path, and keeping the scheme and host, which on THIS path
  are the security signal) HERE, before the tags reach either axis. So the
  scrub covers the always-on production record exactly as it covers the dev
  trace.

  Nothing else in the map is caller data: `:frame` is a frame id, `:scheme`
  and `:host` are parsed URL components, `:reason` is a framework enum (one
  string on the parse-failure arm), `:allowlist` is the CALL'S OWN policy
  input, and `:recovery` is fixed."
  [tags]
  (-> (merge {:recovery :no-recovery} tags)
      (egress/redact-url-tag :location)))

(defn- dispatch-safe-redirect-record!
  "Fan the rejected redirect out on the ALWAYS-ON error axis (rf2-6jqa8) —
  the production-survivable sibling of the dev `trace/emit-error!` emitted
  beside it.

  WHY. Until this promotion a rejection reached the outside world ONLY
  through `trace/emit-error!`, gated on `interop/debug-enabled?`. The
  five-step gate itself is production-real and REJECTS correctly under
  `-Dre-frame.debug=false` — that half was never in doubt — but the
  rejection is a silent no-op on the wire (the fx returns nil, the response
  carries no redirect), so on a production JVM an attacker-supplied
  `?next=javascript:alert(1)` produced NO Sentry event, NO Datadog metric
  and NO frame-owned `:observability :errors` record. A security team could
  not see open-redirect probing against their own app.

  Note the asymmetry that motivated it: the CRLF / NUL gate on the SAME fx
  THROWS, so it rides `:rf.error/fx-handler-exception` and has always been
  always-on. Two halves of one security surface had opposite production
  observability.

  The record is the general NON-EVENT union shape — this is not a dispatched-
  event failure, so it rides `dispatch-error-record!` rather than the
  event-centric `dispatch-on-error!`. Reached through the
  `:error-emit/dispatch-error-record` late-bind hook, the same indirection
  `ssr/boot` and `ssr/error-projector` use; a no-op when the hook is unbound.
  Returns nil.

  EGRESS (rf2-6jqa8 AUDIT-REOPEN). `tags` arrives as the DIAGNOSTIC map — the
  scrubbed `:location`, the `:allowlist`, everything the dev trace shows — and
  this function is the ONE place that reaches an off-box shipper, so it is
  where the diagnostics are projected down to
  `egress/safe-redirect-record-tags`: a closed set of parsed STRUCTURAL
  components, no URL in any form, no allowlist. Projecting HERE rather than at
  the eight call sites is what makes it fail-closed — a future emit arm cannot
  forget, and a tag slot added upstream has no route to Sentry unless someone
  edits `egress/safe-redirect-record-slots` and reddens the test pinning it.

  Why a projection and not a wider scrub is argued in full in
  `re-frame.ssr.egress`; the short version is that a rejected target is an
  ARBITRARY FOREIGN URL, so the carrier scrub's keep-everything-but-the-
  carriers shape left userinfo credentials, path-borne tokens and
  attacker-chosen value-less query keys riding out verbatim."
  [operation tags]
  (when-let [dispatch-error-record!
             (late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      (assoc (egress/safe-redirect-record-tags tags)
             :error operation
             :time  (interop/now-ms))))
  nil)

(defn- emit-safe-redirect-error!
  "Fan a structured `:rf.error/safe-redirect-*` rejection along BOTH error
  axes and return nil so the fx body can `(or (emit-...) ...)` to a no-op.

  Axis 1 is the ALWAYS-ON `error-emit` record (rf2-6jqa8) — the half that
  reaches an off-box shipper in a production build, and which receives a
  closed STRUCTURAL PROJECTION of the map rather than the map. Axis 2 is the
  dev trace, which receives the diagnostics whole. The tag map is built once
  by [[safe-redirect-tags]], with the URL scrub applied there, so the two
  cannot disagree about the rejection they describe."
  [operation tags]
  (let [tags (safe-redirect-tags tags)]
    (dispatch-safe-redirect-record! operation tags)
    (trace/emit-error! operation tags))
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
  the parsed URL shape, not merely on host presence:

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

  An attacker-controlled `?next=...` parameter therefore cannot redirect
  off-origin, including through opaque URIs such as `http:evil.example.com`."
  [{:keys [frame]} {:keys [location relative-only? allow status]
                    :as redirect-map}]
  ;; Reject the unsupported `:url` / `:to` spellings with a clear
  ;; diagnostic naming `:location` before anything else — a safe-redirect
  ;; keyed on a retired spelling would otherwise present as a missing
  ;; (nil) `:location` and fail as a generic parse error, hiding the
  ;; vocabulary mistake.
  (validate-args-map! :rf.server/safe-redirect redirect-map)
  (reject-retired-redirect-keys! redirect-map)
  ;; SHAPE gate (rf2-dtpfv), always-on, before anything is parsed. Note the
  ;; taxonomy line this respects: a malformed CALL is a programmer error and
  ;; throws; an untrusted INPUT that is a well-typed string but points
  ;; off-origin stays the five-step gate's emit-and-no-op below. `:location`
  ;; is REQUIRED here (unlike the caller-trusted `:rf.server/redirect`'s
  ;; documented no-target path) because it is this fx's validation TARGET —
  ;; a target-less safe-redirect has no defensible interpretation. A blank
  ;; string is still a string and still takes the parse-failure arm.
  (validate-string-arg! :rf.server/safe-redirect :location location)
  (validate-status!     :rf.server/safe-redirect (or status 302))
  (when (some? relative-only?)
    (when-not (boolean? relative-only?)
      (reject-arg! :rf.server/safe-redirect :relative-only? relative-only?
                   "a boolean")))
  (when (some? allow)
    (when-not (and (sequential? allow) (every? string? allow))
      ;; A scalar `:allow "app.example.com"` would `seq` into CHARACTERS and
      ;; silently build a per-character allowlist — fail-closed, but a
      ;; policy the caller never wrote. Reject the call instead.
      (reject-arg! :rf.server/safe-redirect :allow allow
                   "a sequence of host strings")))
  ;; Run the CR/LF/NUL gate next — same defence-in-depth as the
  ;; caller-trusted redirect-fx. A safe-redirect caller
  ;; passing a CRLF-bearing location is presumably trying both vectors;
  ;; reject at the same fx boundary the trusted variant uses.
  (validate-redirect-location! location)

  ;; Validation order follows Spec 009 §Error event catalogue.
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
          ;; `:reason` is a closed framework keyword, not prose (rf2-6jqa8):
          ;; this slot rides the always-on record off-box, where an
          ;; aggregatable value is worth more than a sentence — and free
          ;; prose on an attacker-influenced arm is how raw material finds
          ;; its way back into a record. The sentence lives here, in the
          ;; source, where a reader who needs it is already standing.
          (emit-safe-redirect-error! :rf.error/safe-redirect-invalid-url
                                     {:frame    frame
                                      :location location
                                      :reason   :parse-failed})

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
                ;; The policy is based on URL shape, not host presence alone.
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
              ;; defensible redirect interpretation.
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
