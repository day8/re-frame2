(ns re-frame.ssr.response
  "HTTP response accumulator and the six `:rf.server/*` effect handlers.

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

  The `:status` and `:redirect` slots are last-write-wins: each write
  replaces the previous one (Spec 011 §Multiple-status policy).

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
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy.url :as rf.privacy.url]
            [re-frame.ssr.egress :as rf.ssr.egress]
            [re-frame.ssr.http-validation :as rf.ssr.http-validation]
            [re-frame.trace :as rf.trace])
  #?(:clj (:import [java.net URI URISyntaxException])))

(defonce
  ^{:doc "Per-frame storage for the HTTP response accumulator. Keys are
  frame-ids; values are the accumulator map (`{:status :headers :cookies
  :redirect}`). Framework-private —
  not stored in `app-db` so the accumulator never rides the hydration
  payload to the client. Cleared per-frame by the
  `:ssr/on-frame-destroyed` hook."}
  response-slots
  (atom {}))

;; ---- the reserved `:rf.server/*` ARGS SHAPE gate -------------------------
;;
;; WHY IT EXISTS. Spec 010 §Validation-order step 5 validates an fx's args
;; against the `:schema` on its registration meta, and Spec 010 §Per-step
;; recovery row 5 says the offending fx is `:skipped`. That gate is
;; `re-frame.schemas.validate/validate-fx!`, whose body is
;; `(if interop/debug-enabled? (run-validation …) true)` — and
;; `interop/debug-enabled?` is read ONCE at namespace-load time. Under
;; `-Dre-frame.debug=false` the boundary therefore does not run AT ALL and
;; nothing is skipped: without this gate a malformed reserved fx would RUN, and
;; its args would land on the per-request response accumulator that
;; `ssr/get-response` publishes to every host adapter:
;;
;;   [:rf.server/set-status "not-an-int"]              → :status "not-an-int"
;;   [:rf.server/set-header {:name "X-Foo"}]           → ["X-Foo" nil]
;;   [:rf.server/set-cookie {:name "session"}]         → {:name "session"}
;;   [:rf.server/redirect {:location "/ok"
;;                         :status "not-int"}]         → the malformed redirect
;;
;; THE RULE. The general step-5 gate for USER fx
;; is dev-only — trust-the-programmer covers user declarations, and paying
;; a hot-path validation cost in every app to protect six closed framework
;; effects is posture-hostile. What trust-the-programmer does NOT cover is the
;; framework's own wire-adjacent contract: these six fx are a CLOSED set the
;; framework itself publishes, their args feed the HTTP response head, and the
;; accumulator is a PUBLIC host-adapter surface whose shape must not depend on
;; which build you are running. So the reserved family guards its own args
;; UNCONDITIONALLY, in every build.
;;
;; This is the ownership pattern the wire-grammar gates in this file follow.
;; `validate-header-name!` / `validate-header-value!` /
;; `validate-cookie-name!` / `validate-cookie-attr!` /
;; `validate-redirect-location!` all `error/throw-error!` unconditionally
;; before `swap-response!`, covering the CR/LF/NUL + token-grammar injection
;; surface. They are the WIRE-GRAMMAR half. This section is the SHAPE half:
;; the published TYPE contract of each args map (Spec 011 §Standard fx), which
;; nothing else enforces outside a debug build.
;;
;; MECHANISM — throw-through-containment, not skip-silently. The guards throw
;; the canonical `re-frame.error/throw-error!` BEFORE the first
;; `swap-response!`. The registered-fx containment in `re-frame.fx` then
;; supplies the wanted semantics with no machinery of its own: the accumulator is
;; never mutated, sibling fx in the same `:fx` vector still run, an ALWAYS-ON
;; `:rf.error/fx-handler-exception` record survives production, and SSR's error
;; projection serves the sanitised 500. Fail-closed is right for a
;; wire-adjacent programmer error that survived to production — a silently
;; dropped `Set-Cookie` under a 200 is worse than an honest, observable 500.
;;
;; TAXONOMY — the line this gate does NOT cross. A malformed framework CALL is
;; a programmer error and throws. An untrusted-INPUT policy decision — a
;; `:rf.server/redirect` target that parses but points off-origin — is the
;; non-projecting emit-and-no-op (see `redirect-fx`). A `:location` that is a
;; STRING reaches the URL gate; only a `:location` that is not a string at all
;; (a keyword, a URI object) is a call the programmer got wrong.
;;
;; POSTURE. Only the DIAGNOSTIC differs by build: dev-with-schemas gets the
;; Malli step-5 skip plus its rich `:where :fx-args` diagnostic; production (or
;; a schemas-less dev build) gets this guard's throw → containment → always-on
;; `:rf.error/fx-handler-exception` record. Both reach SSR's error projection,
;; which answers the sanitised 500 in every build. And the shape never differs:
;; `get-response` never yields a malformed `:rf/response` shape in ANY build.
;;
;; ONE SHAPE, NOT TWO. The guard here and the Malli `:rf.fx.server/*-args`
;; schemas in `re-frame.ssr.server-fx-schemas` are two enforcements of ONE
;; published contract, so wherever they disagreed the accumulator would be
;; posture-dependent again — the defect above, wearing a smaller hat. Two
;; places they could disagree are settled the same way: by the shape Spec 011
;; §Standard fx publishes.
;;
;;   AN OPTIONAL KEY PRESENT WITH `nil` IS ABSENT. `{:path nil}` and `{}` say
;;   the same thing in Clojure, and code that builds a cookie out of an options
;;   map (`{:secure (:secure? opts)}`) writes the first while meaning the
;;   second. The optional schema slots are `[:maybe …]`, so BOTH halves read a
;;   present `nil` as absent; a schema that did not would skip `{:secure nil}`
;;   in dev while the guard persisted it in production. A REQUIRED key is
;;   different — `{:value nil}` is a missing `:value`, and `nil` is not a
;;   status.
;;
;;   A COOKIE `:name` IS A STRING. `validate-cookie-name!` admits a keyword
;;   or symbol (`(name n)` accepts either), while the schemas, Spec 011 §Cookie
;;   shape and Spec-Schemas all publish `:string` — so without a type check
;;   `{:name :csrf}` would be skipped in dev, land in production, and reach
;;   every host adapter as a keyword where the contract promises a string.
;;   Publishing the tolerance instead would hand every non-Ring adapter a
;;   `Named` to unwrap, pushing work onto adapters. So the fx boundary holds
;;   `:name` to the published type, exactly as `set-header-fx` holds a header
;;   `:name` to it. The Ring materialiser keeps
;;   its own `Named` tolerance: it is the last line for a host that writes the
;;   accumulator directly, which is defence-in-depth, not this contract.
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
  EP-0015-safe SHAPE summary (`{:type … :count …}`), which is also
  precisely what a type violation needs to be diagnosable.

  That summary is content-free BY CONSTRUCTION: it carries a
  closed-vocabulary `:type` keyword and an integer `:count`, and nothing
  else, so this site may hand it a session token without further thought.
  WHICH argument failed rides the separate `:key` slot below rather than
  being recovered from the value: a structural identifier travels through
  an explicitly trusted field, and the summary is never asked to guess that
  an app-supplied value is structural."
  [fx-id arg-key value expected]
  (rf.error/throw-error!
    :rf.error/server-fx-args-invalid
    're-frame.ssr.response
    (str fx-id " " arg-key " must be " expected "; got "
         (pr-str (rf.error/diag-value-summary value))
         ". Fix the fx args map at the dispatch site — the reserved"
         " :rf.server/* fx guard their own args in every build, so this"
         " rejection does not depend on -Dre-frame.debug.")
    {:recovery :supply-a-well-formed-fx-argument
     :extra    {:fx-id    fx-id
                :key      arg-key
                :value    (rf.error/diag-value-summary value)
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
  the RFC 9110 §15 status-code range. Without it a non-integer status would
  ride onto the accumulator, and only the Ring adapter's
  `fail-closed-status` (host-specific, and silent on a production JVM)
  would stop it reaching a wire. Shared by `:rf.server/set-status` and by both
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
  "Throw `:rf.error/server-fx-args-invalid` unless `value` is a string. Used for
  the args a host adapter writes VERBATIM onto the response head."
  [fx-id arg-key value]
  (when-not (string? value)
    (reject-arg! fx-id arg-key value "a string"))
  value)

(def ^:private cookie-attr-shape
  "The published TYPE contract of the `:rf.server/cookie` attributes, as a
  table of `[attr-key required? pred expected]`. Spelled as an explicit literal
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

  `:name` is NOT here — it has its own richer gate
  (`validate-cookie-name!`) for the nil / unsupported-type / token-grammar
  cases, and `validate-cookie!` then holds it to the published `:string` the
  same way `set-header-fx` holds a header `:name` to it."
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
  wrong type.

  An explicit `nil` counts as absent for an OPTIONAL attribute — the same
  `(some? v)` convention `validate-cookie!` applies to the injection gate, the
  reading every downstream consumer takes (`ssr-ring`'s materialiser
  appends `Path=` only `(when path)`), and the one Clojure gives a map entry
  whose value is `nil`. `re-frame.ssr.server-fx-schemas/cookie` spells the same
  rule `[:maybe …]` so the dev boundary cannot disagree with this one. For a
  REQUIRED attribute `nil` is the violation it looks like: `{:value nil}` is a
  cookie with no value, which is exactly the malformation this gate exists to
  refuse."
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
;; Fail fast (throw) rather than strip-and-warn. A header
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
  "Throw `:rf.error/header-invalid-value` if the header `value`
  contains CR / LF / NUL. Per RFC 7230 §3.2.4 — `field-value` is
  `*( field-content / obs-fold )` and obs-fold (CRLF + WSP) is
  deprecated; no CTLs allowed in `field-content`."
  [header-name value]
  (let [header-value (str value)]
    (when (rf.ssr.http-validation/contains-injection-char? header-value)
      (rf.error/throw-error!
        :rf.error/header-invalid-value
        're-frame.ssr.response
        (str "header " (pr-str header-name)
             " value contains CR/LF/NUL — forbidden"
             " by RFC 7230 §3.2.4 (header-splitting"
             " injection). Strip CR/LF/NUL from the header value before"
             " setting it.")
        {:recovery :remove-injection-chars-from-header-value
         :extra    {:header header-name
                    :value  value}}))
    header-value))

(defn- validate-redirect-location!
  "Throw `:rf.error/redirect-invalid-location` if the redirect location
  `location` contains CR / LF / NUL. Same CRLF-injection vector as a
  user-controlled `Location` header value: a query param like
  `?next=https://example.com%0d%0aSet-Cookie:%20stolen=1` URL-decodes
  into literal CRLF and would split the header on the wire.

  `:rf.server/redirect` runs it before its URL gate, and no `:allow` policy
  reaches it: even `:allow :any` refuses a header-splitting character. It
  deliberately does NOT do any URL-shape / scheme / host validation — the URL
  gate in `redirect-fx` does that, emitting the specific
  `:rf.error/redirect-*` categories."
  [location]
  (let [location-string (str location)]
    (when (rf.ssr.http-validation/contains-injection-char? location-string)
      (rf.error/throw-error!
        :rf.error/redirect-invalid-location
        're-frame.ssr.response
        (str "redirect :location contains CR/LF/NUL"
             " — forbidden by RFC 7230 §3.2.4"
             " (header-splitting injection). Strip CR/LF/NUL from the"
             " redirect :location before setting it.")
        {:recovery :remove-injection-chars-from-location
         :extra    {:location location}}))
    location-string))

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
  [header-name]
  (let [header-name-string (str header-name)]
    (when-not (rf.ssr.http-validation/valid-token-name? header-name-string)
      (rf.error/throw-error!
        :rf.error/header-invalid-name
        're-frame.ssr.response
        (str "header :name " (pr-str header-name-string)
             " violates RFC 7230 §3.2.6 token grammar"
             " (no CTLs, whitespace, or separators"
             " ()<>@,;:\\\"/[]?={}). Use a token-grammar header name.")
        {:recovery :use-a-token-grammar-header-name
         :extra    {:header header-name}}))
    header-name-string))

(defn- validate-cookie-name!
  "Throw `:rf.error/cookie-invalid-name` if the cookie name is nil, is not a
  string / keyword / symbol, or violates the RFC 6265 §4.1.1 token grammar.
  Applied at the effect boundary so every host adapter receives the same
  validation.

  This is the NAME gate, not the whole contract: a keyword / symbol survives it
  (`(name cookie-name)` reads either) and is then refused by the always-on
  SHAPE gate in `validate-cookie!`, which holds `:name` to the `:string`
  Spec 011 §Cookie shape publishes. The split is deliberate and mirrors
  `set-header-fx`: the values this gate refuses throw its specific
  catalogued id, and what the type check adds — a name that is
  not a `Named` at all — is the case `(name cookie-name)` cannot survive."
  [cookie-name]
  (when (nil? cookie-name)
    (rf.error/throw-error!
      :rf.error/cookie-invalid-name
      're-frame.ssr.response
      "cookie :name is required; supply a non-nil :name on the cookie map."
      {:recovery :supply-a-cookie-name
       :extra    {:name cookie-name}}))
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
  (when-not (or (string? cookie-name)
                #?(:clj  (instance? clojure.lang.Named cookie-name)
                   :cljs (or (keyword? cookie-name) (symbol? cookie-name))))
    (rf.error/throw-error!
      :rf.error/cookie-invalid-name
      're-frame.ssr.response
      (str "cookie :name must be a string or a keyword/symbol; got "
           (pr-str cookie-name)
           #?(:clj (str " (a " (.getName (class cookie-name)) ")") :cljs "")
           ". Use a string or keyword token-grammar cookie name.")
      {:recovery :use-a-token-grammar-cookie-name
       :extra    {:name cookie-name}}))
  (let [cookie-name-string (#?(:clj clojure.core/name :cljs cljs.core/name)
                            cookie-name)]
    (when-not (rf.ssr.http-validation/valid-token-name? cookie-name-string)
      (rf.error/throw-error!
        :rf.error/cookie-invalid-name
        're-frame.ssr.response
        (str "cookie :name " (pr-str cookie-name-string)
             " violates RFC 6265 §4.1.1 token grammar"
             " (no CTLs, whitespace, or separators"
             " ()<>@,;:\\\"/[]?={}). Use a token-grammar cookie name.")
        {:recovery :use-a-token-grammar-cookie-name
         :extra    {:name cookie-name}}))
    cookie-name-string))

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
  [attribute-key attribute-value]
  (let [serialised-value (str attribute-value)
        ;; `:value` is percent-encoded downstream, so `;` there is data;
        ;; every other attribute is concatenated verbatim and must also
        ;; reject the `;` cookie-attribute delimiter.
        value-attribute? (= attribute-key :value)
        invalid? (if value-attribute?
                   (rf.ssr.http-validation/contains-injection-char? serialised-value)
                   (rf.ssr.http-validation/contains-cookie-attr-injection-char?
                     serialised-value))]
    (when invalid?
      (rf.error/throw-error!
        :rf.error/cookie-invalid-attribute
        're-frame.ssr.response
        (str "cookie attribute " attribute-key " " (pr-str serialised-value)
             (if value-attribute?
               " contains CR/LF/NUL — forbidden by RFC 7230 §3.2.4"
               (str " contains CR/LF/NUL or a `;` — forbidden by"
                    " RFC 7230 §3.2.4 and RFC 6265 §4.1.1 (the `;` is the"
                    " cookie-attribute delimiter)"))
             " (header-splitting / attribute injection). Strip "
             (if value-attribute? "CR/LF/NUL" "CR/LF/NUL and `;`")
             " from the cookie " (name attribute-key) ".")
        {:recovery :remove-injection-chars-from-cookie-attr
         :extra    {:attribute attribute-key
                    :value     attribute-value}}))
    serialised-value))

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
  ;; Name first, so a name-less cookie throws the catalogued
  ;; `:rf.error/cookie-invalid-name`; SHAPE next, so a `:value`-less cookie
  ;; never reaches the accumulator in a release build; the injection gate
  ;; last, on values known to be well-typed.
  (validate-cookie-name!  (:name cookie))
  ;; …then narrow `:name` to the published `:string`. A keyword / symbol name
  ;; passes the gate above — `(name :csrf)` is a fine token — but the schemas,
  ;; Spec 011 §Cookie shape and Spec-Schemas all publish `:string`, so without
  ;; this narrowing it would be skipped in dev and persisted in production,
  ;; reaching host adapters as a keyword where the contract promises a
  ;; string. Same ordering as
  ;; `set-header-fx`: grammar gate first with its own catalogued id, shape gate
  ;; second.
  (validate-string-arg!   fx-id :name (:name cookie))
  (validate-cookie-shape! fx-id cookie)
  (doseq [attr-key checked-cookie-attrs
          :let  [v (get cookie attr-key)]
          :when (some? v)]
    (validate-cookie-attr! attr-key v))
  cookie)

(defn default-response
  "The default response accumulator. Spec 011 §Status defaults: status 200,
  default content-type text/html for HTML responses, no cookies, no redirect."
  []
  {:status   200
   :headers  [["content-type" "text/html; charset=utf-8"]]
   :cookies  []
   :redirect nil})

(defn- ensure-response
  "Return `response` with defaults applied. nil-tolerant — a frame whose slot
  has never been touched by an :rf.server/* fx still resolves to the
  default response shape."
  [response]
  (if response
    (merge (default-response) response)
    (default-response)))

(defn swap-response!
  "Mutate the response accumulator slot for `frame-id` with `update-fn`. Returns
  the post-swap response map. The substrate is a side-channel atom keyed
  on the process-local frame address — O(small-map) swap, no app-db
  ping-pong. `frame-address` is the bare process-local frame id."
  [frame-id update-fn]
  (let [frame-address (rf.frame/frame-address frame-id)
        updated-response (-> (swap! response-slots
                                    update frame-address
                                    #(update-fn (ensure-response %)))
                             (get frame-address))]
    updated-response))

(defn response-of
  "Read the current response accumulator (with defaults applied)."
  [frame-id]
  (ensure-response (get @response-slots (rf.frame/frame-address frame-id))))

(defn clear-response!
  "Drop `frame-id`'s response slot. Called from
  `re-frame.ssr.request/on-frame-destroyed!` via the
  `:ssr/on-frame-destroyed` late-bind hook. Idempotent — tolerates a frame-id
  with no slot."
  [frame-id]
  (swap! response-slots dissoc (rf.frame/frame-address frame-id))
  frame-id)

;; ---- header helpers ------------------------------------------------------

(defn- replace-header
  "Replace the (first matching, case-insensitive) header pair with [name value],
  or append if none matched. Subsequent matches are dropped — set-header
  replaces the entire header value per Spec 011 §Header replacement vs append."
  [headers header-name header-value]
  (let [normalised-header-name (str header-name)
        target-header-name     (clojure.string/lower-case normalised-header-name)
        [matched? updated-headers]
        (reduce
          (fn [[matched? accumulated-headers]
               [existing-name _existing-value :as header-pair]]
            (cond
              (not= (clojure.string/lower-case (str existing-name))
                    target-header-name)
              [matched? (conj accumulated-headers header-pair)]

              matched?
              [matched? accumulated-headers]    ;; drop subsequent matches

              :else
              [true (conj accumulated-headers
                          [normalised-header-name header-value])]))
          [false []]
          headers)]
    (if matched?
      updated-headers
      (conj updated-headers [normalised-header-name header-value]))))

(defn- append-header-pair
  "Append [name value] to headers — preserves any existing header with the
  same name. Per Spec 011 §Header replacement vs append; required for
  Set-Cookie-style multi-valued headers."
  [headers header-name header-value]
  (conj (vec headers) [(str header-name) header-value]))

;; ---- handler fns for the six :rf.server/* fxs ----------------------------

(defn set-status-fx
  "Handler fn for `:rf.server/set-status`. Last-write-wins. Throws
  `:rf.error/server-fx-args-invalid` — in EVERY build — unless
  the status is an integer in 100–599, so a string status never
  reaches `ssr/get-response`."
  [{:keys [frame]} status]
  (validate-status! :rf.server/set-status status)
  (swap-response! frame #(assoc % :status status)))

(defn set-header-fx
  "Handler fn for `:rf.server/set-header`. Replaces any existing header
  with the same name (case-insensitive). Throws
  `:rf.error/header-invalid-name` on a name violating the RFC 7230
  §3.2.6 token grammar, and `:rf.error/header-invalid-value` on a value
  carrying CR/LF/NUL. Throws `:rf.error/server-fx-args-invalid` — in EVERY
  build — on a non-string `:name` / `:value`, so a `:value`-less
  call never puts `[\"X-Foo\" nil]` on the accumulator."
  [{:keys [frame]} {:keys [name value] :as args}]
  (validate-args-map!    :rf.server/set-header args)
  (validate-header-name! name)
  ;; The string gate runs AFTER the grammar gate so a nil / keyword `:name`
  ;; throws the catalogued `:rf.error/header-invalid-name`
  ;; ("" and ":x-foo" both fail the token grammar); what it adds is
  ;; the case the grammar cannot see — a symbol or a number, which `(str …)`
  ;; renders as a perfectly legal token and which would therefore pass the
  ;; grammar gate alone.
  (validate-string-arg!  :rf.server/set-header :name  name)
  (validate-string-arg!  :rf.server/set-header :value value)
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [response]
      (update response :headers replace-header name value))))

(defn append-header-fx
  "Handler fn for `:rf.server/append-header`. Preserves any existing
  header with the same name — required for Set-Cookie-style multi-valued
  headers. Throws `:rf.error/header-invalid-name` on a name violating the
  RFC 7230 §3.2.6 token grammar, and `:rf.error/header-invalid-value` on a
  value carrying CR/LF/NUL. Throws `:rf.error/server-fx-args-invalid` — in
  EVERY build — on a non-string `:name` / `:value`; see
  `set-header-fx` for why the string gate follows the grammar gate."
  [{:keys [frame]} {:keys [name value] :as args}]
  (validate-args-map!    :rf.server/append-header args)
  (validate-header-name! name)
  (validate-string-arg!  :rf.server/append-header :name  name)
  (validate-string-arg!  :rf.server/append-header :value value)
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [response]
      (update response :headers append-header-pair name value))))

(defn set-cookie-fx
  "Handler fn for `:rf.server/set-cookie`. Cookie attributes are stored
  as a structured map (RFC 6265 wire-form serialisation is host-adapter
  business). Throws `:rf.error/cookie-invalid-name` on a name violating
  the RFC 6265 §4.1.1 token grammar, and the single catalogued
  `:rf.error/cookie-invalid-attribute` (carrying the offending
  `:attribute` + `:value` in its payload) on a CRLF/NUL-bearing
  attribute string — the structured cookie is gated at the fx
  boundary so non-ring host adapters get the same safety the ring
  materialiser provides at wire-write time, and the per-attribute
  injection failures share the one catalogued id the ring serialiser
  throws. Throws
  `:rf.error/server-fx-args-invalid` — in EVERY build — on an
  attribute violating its published type, so a `:value`-less cookie never
  reaches `ssr/get-response`. `:name` is held to the published `:string`;
  an optional attribute present with `nil` reads as absent."
  [{:keys [frame]} cookie-map]
  (validate-cookie! :rf.server/set-cookie cookie-map)
  (swap-response!
    frame
    (fn [response]
      (update response :cookies (fnil conj []) cookie-map))))

(defn delete-cookie-fx
  "Handler fn for `:rf.server/delete-cookie`. Sugar over set-cookie
  with :max-age 0 and an empty :value. Applies the same name, path, and
  domain validation as `set-cookie-fx` — including the always-on
  `:rf.error/server-fx-args-invalid` shape gate, which reaches
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
      (fn [response]
        (update response :cookies (fnil conj []) cookie)))))

;; ---- :rf.server/redirect --------------------------------------------------
;;
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
  Detected on a `:rf.server/redirect` args map so they fail loudly rather than
  degrade into the malformed-no-target path."
  [:url :to])

(defn- reject-retired-redirect-keys!
  "Throw `:rf.error/redirect-retired-target-key` if `redirect-map` carries
  an unsupported redirect-target spelling (`:url` / `:to`). The canonical key
  is `:location`. The error names `:location` so the
  programmer rewrites the spelling rather than seeing the generic
  no-target warning the host adapter emits for a target-less redirect."
  [redirect-map]
  (let [retired-keys (filterv #(contains? redirect-map %)
                              retired-redirect-target-keys)]
    (when (seq retired-keys)
      (rf.error/throw-error!
        :rf.error/redirect-retired-target-key
        're-frame.ssr.response
        (str "redirect target key(s) " (pr-str retired-keys)
             " are retired — the canonical redirect"
             " target key is :location. The SSR redirect"
             " fx writes an HTTP Location response header,"
             " so it uses header vocabulary; rewrite "
             (pr-str (first retired-keys)) " as :location;"
             " there is no alias.")
        {:recovery :rewrite-the-target-key-as-location
         :extra    {:retired-keys  retired-keys
                    :canonical-key :location}}))))

;; THE URL GATE. A redirect `:location` is routinely built from untrusted
;; input — a `?next=` parameter after sign-in is the canonical case — so the
;; one redirect fx is safe by default. It parses the URL and gates on the
;; parsed URL SHAPE, not merely on the presence of a host, before populating
;; the :redirect slot:
;;
;;   1. URL must parse — :rf.error/redirect-invalid-url on failure.
;;   2. Reject javascript: / data: / vbscript: schemes —
;;      :rf.error/redirect-scheme-rejected.
;;   2b. Reject any scheme outside #{http https} outright — a redirect
;;      Location is only ever an http(s) absolute URL or a relative
;;      reference; mailto:, ftp:, and other schemes have no place as a
;;      redirect target — :rf.error/redirect-scheme-rejected.
;;   2c. Reject a SCHEME-BEARING URL whose host is not extractable. An
;;      http(s) URI that is opaque (`http:evil.example.com` — scheme
;;      present, authority absent) or hierarchical-without-authority
;;      (`http:/evil` — no `//host`) parses cleanly but has nil host.
;;      Java's URI.getHost returns nil for these, so the host-based gates
;;      (steps 3/4) cannot see them — yet a browser given
;;      `Location: http:evil.example.com` navigates OFF-ORIGIN. These
;;      have no defensible redirect interpretation →
;;      :rf.error/redirect-invalid-url (:reason :scheme-without-host).
;;   3. No :allow AND the URL is NOT a relative reference →
;;      :rf.error/redirect-host-disallowed (:reason :relative-only-violation).
;;      A relative reference is `scheme == nil AND authority == nil` AND
;;      the raw string does not begin `//` (e.g. `/dashboard`, `dashboard`,
;;      `a/b`). A protocol-relative `//evil.example.com` is NOT relative,
;;      and neither is `///evil.example.com`: java.net.URI reports that one
;;      with no authority at all, but a browser skips the extra slashes and
;;      takes the next segment as the HOST.
;;   4. :allow [...] AND the URL is not a relative reference AND its host is
;;      not in the allowlist →
;;      :rf.error/redirect-host-disallowed (:reason :not-in-allowlist).
;;      An empty :allow names no host, so it refuses every absolute target.
;;      A non-relative target whose host java.net.URI cannot extract
;;      (`//evil_example/path`, `///evil.example/path`) names no allowlisted
;;      host, so it is rejected rather than waved through.
;;   5. Pass — set :redirect.
;;
;; `:allow :any` is the explicit trusted spelling. It skips steps 3 and 4 and
;; nothing else, so a target the app built itself must still parse and carry
;; an http(s) scheme or none. There is no other policy language.
;;
;; All failure modes EMIT (via re-frame.trace) rather than THROW.
;; Throwing would bubble out as :rf.error/fx-handler-exception and the
;; programmer reading the trace would see a generic fx-handler-exception
;; pointing at redirect-fx rather than the specific category.
;; Emit-and-no-op preserves the dispatch context — the cascade continues,
;; the response's :redirect stays unchanged, and the programmer sees
;; the specific :rf.error/redirect-* category.
;;
;; This fail-closed shape prevents caller-controlled targets from redirecting
;; off-origin, including opaque scheme-bearing forms such as
;; `http:evil.example.com` that defeat a host-presence-only test.

(def ^:private rejected-schemes
  "Closed set of schemes the redirect gate rejects outright.

  This set and [[allowed-schemes]] are also the always-on record's CLASS
  vocabulary: their union is mirrored by `egress/scheme-classes`, which turns
  a caller-chosen scheme string into one of these framework-owned keywords
  before it can egress. A scheme added here must be added there too, and a
  parity test in `re-frame.ssr-redirect-production-test` reddens if it
  is not — the failure would otherwise be silent, the new scheme degrading to
  `:other` on every dashboard that was watching for it."
  #{"javascript" "data" "vbscript"})

(def ^:private allowed-schemes
  "Closed set of schemes a redirect Location may legitimately carry. A
  redirect target is either a relative reference (no scheme) or an
  absolute http(s) URL — every other scheme (mailto, ftp, file, tel, …)
  is rejected outright as a redirect target. The redirect gate decides non-http(s)
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
  string (e.g. \"javascript\") if `location` begins with `<scheme>:`, else
  nil. Used to short-circuit the rejected-schemes check before URI
  parsing — `data:text/html,<script>` is a security-relevant input
  whose body fails java.net.URI parsing but whose scheme should still
  be the visible failure mode."
  [location]
  (when (string? location)
    (when-some [scheme-match (re-find scheme-prefix-re location)]
      (clojure.string/lower-case (second scheme-match)))))

#?(:cljs
   (def ^:private uri-chars-re
     "The characters java.net.URI admits in a URI reference: RFC 3986's
     unreserved and reserved characters and `%`, plus any non-ASCII character
     that is neither a control character nor a space separator — the second
     class below, everything above U+00A0 but Unicode's space separators. So
     `/café` and `/search?q=東京` parse as on the JVM, while a reference
     carrying a raw space, a no-break space, a backslash, `<` or a quote does
     not, as java.net.URI refuses it."
     #"^(?:[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]|[^\x00-\xA0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000])*$"))

#?(:cljs
   (def ^:private uri-reference-re
     "RFC 3986 Appendix B's reference grammar, through the authority: group 2
     is the scheme and group 4 the authority, present (possibly empty) exactly
     when the reference carries `//`."
     #"^(([^:/?#]+):)?(//([^/?#]*))?"))

#?(:cljs
   (def ^:private host-re
     "A hostname or an IP literal — the hosts java.net.URI extracts from a
     server-based authority. Anything else leaves the host nil."
     #"^(?:\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\.?)$"))

#?(:cljs
   (defn- authority-host
     "The host of `authority` with userinfo and port stripped, or nil when
     what remains is not a hostname or an IP literal."
     [authority]
     (let [host-port (subs authority (inc (.lastIndexOf authority "@")))
           host      (if (clojure.string/starts-with? host-port "[")
                       (re-find #"^\[[^\]]*\]" host-port)
                       (clojure.string/replace host-port #":[0-9]*$" ""))]
       (when (and host (re-matches host-re host))
         host))))

(defn- parse-url-safely
  "Parse `location` as a URI reference. Returns `{:scheme :host :authority}`,
  each nil where the reference has none, or nil on a parse failure (the
  caller emits the trace). Empty / blank strings count as unparseable — a
  redirect to an empty location has no defensible interpretation.

  The JVM parses with java.net.URI. CLJS has no such parser, so it reads the
  same three components with RFC 3986's own reference grammar and refuses
  what java.net.URI refuses that matters to the gate: a character outside
  its character set fails the parse, as does a scheme outside RFC 3986's
  scheme grammar (so a non-ASCII character cannot open one), and a host
  survives only as a hostname or an IP literal."
  [location]
  (when (and (string? location) (not (clojure.string/blank? location)))
    #?(:clj
       (try
         (let [uri (URI. ^String location)]
           {:scheme    (.getScheme uri)
            :host      (.getHost uri)
            :authority (.getAuthority uri)})
         (catch URISyntaxException _ nil)
         (catch NullPointerException _ nil)
         (catch IllegalArgumentException _ nil))
       :cljs
       (when (re-matches uri-chars-re location)
         (let [[_ _ scheme _ authority] (re-find uri-reference-re location)
               authority                (not-empty authority)]
           (when (or (nil? scheme) (re-matches #"[A-Za-z][A-Za-z0-9+.-]*" scheme))
             {:scheme    scheme
              :host      (some-> authority authority-host)
              :authority authority}))))))

(defn- gate-refusal
  "Run the URL gate over `location` — a string the CR/LF/NUL gate has already
  passed — under the `:allow` policy. Returns nil when the redirect may be
  written, else the `[error-id tags]` of the first check it fails, in the
  order the block above lists. The prefix half of step 2 runs BEFORE the
  parse, because a `data:text/html,<script>` body fails java.net.URI parsing
  and must still surface as a scheme rejection rather than as an invalid URL."
  [location allow]
  (let [detected-scheme (detect-scheme location)]
    (if (and detected-scheme (rejected-schemes detected-scheme))
      [:rf.error/redirect-scheme-rejected {:scheme detected-scheme}]
      (if-some [{:keys [scheme host authority]} (parse-url-safely location)]
        (let [normalised-scheme (some-> scheme clojure.string/lower-case)
              ;; A relative reference carries neither a scheme nor an
              ;; authority — `/dashboard`, `dashboard`, `a/b` — and the
              ;; browser resolves it against the current origin. A raw `//`
              ;; prefix is a NETWORK-PATH reference whatever the parser makes
              ;; of it: java.net.URI parses `///evil.example/path` with NO
              ;; authority, yet a browser skips the extra slashes and
              ;; navigates to https://evil.example/path.
              relative?         (and (nil? scheme)
                                     (nil? authority)
                                     (not (clojure.string/starts-with? location "//")))]
          (cond
            ;; Step 2: a rejected scheme whose body DID parse
            ;; (`javascript:foo` without parens).
            (and normalised-scheme (rejected-schemes normalised-scheme))
            [:rf.error/redirect-scheme-rejected {:scheme scheme}]

            ;; Step 2b: any other non-http(s) scheme.
            (and normalised-scheme (not (allowed-schemes normalised-scheme)))
            [:rf.error/redirect-scheme-rejected {:scheme scheme :reason :scheme-not-allowed}]

            ;; Step 2c: a scheme-bearing URL with no extractable host.
            (and normalised-scheme (nil? host))
            [:rf.error/redirect-invalid-url {:scheme scheme :reason :scheme-without-host}]

            ;; A relative reference stays on the request's own origin, and
            ;; `:allow :any` waives the host check.
            (or relative? (= :any allow))
            nil

            ;; Step 3: relative-only, the default.
            (nil? allow)
            [:rf.error/redirect-host-disallowed {:host host :reason :relative-only-violation}]

            ;; Step 4: the allowlist. DNS hostnames are case-insensitive
            ;; (RFC 1035 §2.3.3), so lower-case both sides — matching the
            ;; header/cookie token-grammar treatment elsewhere in this file.
            (not (and host
                      (contains? (into #{} (map clojure.string/lower-case) allow)
                                 (clojure.string/lower-case host))))
            [:rf.error/redirect-host-disallowed {:host      host
                                                 :reason    :not-in-allowlist
                                                 :allowlist (vec allow)}]))
        ;; Step 1: parse failure. `:reason` is a closed framework keyword,
        ;; not prose: this slot rides the always-on record off-box, where an
        ;; aggregatable value is worth more than a sentence — and free prose
        ;; on an attacker-influenced arm is how raw material finds its way
        ;; back into a record.
        [:rf.error/redirect-invalid-url {:reason :parse-failed}]))))

(defn- redirect-gate-tags
  "The `:rf.error/redirect-*` DIAGNOSTIC tag map for a refused redirect
  — built ONCE, so the production record and the dev trace cannot disagree
  about the same rejection.

  This is the RICH map: it goes to the dev trace as-is, and
  `dispatch-redirect-gate-record!` projects it down to a closed structural
  subset before the always-on axis sees it (see
  `egress/redirect-record-tags`). That is the ordinary EP-0015
  relationship: a local operator sees their own process in full, and the
  off-box record is a strict projection of it, never a copy. Note that the
  projection is by NAME as well as by slot — the record's `:scheme-class` is a
  classification of this map's `:scheme`, not a copy of it — so nothing here
  can widen the record by accident.

  EP-0015: a refused `:location` is caller-untrusted — an untrusted
  `?next=` parameter is the case the gate exists for — and a rejected target
  routinely looks like `?next=https://evil.example.com/cb?token=…`.
  `re-frame.privacy.url/redact-url-tag` — core's ONE URL-carrier policy, reachable from
  here because it lives in the only artefact SSR depends on —
  scrubs the query / fragment carrier VALUES (keeping the structured path, and
  keeping the scheme and host, which on THIS path are the security signal)
  HERE, before the tags reach either axis. So the scrub covers the always-on
  production record exactly as it covers the dev trace.

  What this map carries, and why that is fine HERE: `:scheme` and
  `:host` are parsed out of the caller's URL, so they are caller-authored text
  however structural they look, and
  `:allowlist` is the call's own security configuration. All three are exactly
  right for a programmer reading their own process, and all three are excluded
  from the off-box record for that same reason. `:frame` is a frame id,
  `:reason` is a framework enum, and `:recovery` is fixed."
  [tags]
  (-> (merge {:recovery :no-recovery} tags)
      (rf.privacy.url/redact-url-tag :location)))

(defn- dispatch-redirect-gate-record!
  "Fan the refused redirect out on the ALWAYS-ON error axis —
  the production-survivable sibling of the dev `trace/emit-error!` emitted
  beside it.

  WHY. `trace/emit-error!` is gated on `interop/debug-enabled?`. The
  URL gate itself is production-real and REJECTS correctly under
  `-Dre-frame.debug=false`, but the
  rejection is a silent no-op on the wire (the fx returns nil, the response
  carries no redirect), so with the dev trace alone an attacker-supplied
  `?next=javascript:alert(1)` on a production JVM would produce NO Sentry
  event, NO Datadog metric and NO frame-owned `:observability :errors`
  record. A security team could not see open-redirect probing against their
  own app.

  The CRLF / NUL gate on the SAME fx THROWS, so it rides
  `:rf.error/fx-handler-exception` and is always-on; without this record
  the two halves of one security surface would have opposite production
  observability.

  The record is the general NON-EVENT union shape — this is not a dispatched-
  event failure, so it rides `dispatch-error-record!` rather than the
  event-centric `dispatch-on-error!`. Reached through the
  `:error-emit/dispatch-error-record` late-bind hook, the same indirection
  `ssr/boot` and `ssr/error-projector` use; a no-op when the hook is unbound.
  Returns nil.

  EGRESS. `tags` arrives as the DIAGNOSTIC map — the
  scrubbed `:location`, the `:host`, the `:allowlist`, everything the dev trace
  shows — and this function is the ONE place that reaches an off-box shipper,
  so it is where the diagnostics are projected down to
  `egress/redirect-record-tags`: `#{:frame :recovery :reason
  :scheme-class}` and nothing else. Projecting HERE rather than at each
  rejection arm is what makes it fail-closed — a future arm cannot forget,
  and a tag slot added upstream has no route to Sentry unless someone edits
  `egress/redirect-record-slots` and reddens the test pinning it.

  Why a projection and not a wider scrub is argued in full in
  `re-frame.ssr.egress`; the short version is that a rejected target is an
  ARBITRARY FOREIGN URL, so the carrier scrub's keep-everything-but-the-
  carriers shape would leave userinfo credentials, path-borne tokens and
  attacker-chosen value-less query keys riding out verbatim.

  And why CLASSES rather than the parsed components themselves is the second
  half of the same argument: parsing tells you where a substring sat in the
  grammar, not who wrote it. `:scheme` and `:host` are caller-authored on
  every arm that carries them — a scheme is arbitrary text under RFC 3986
  §3.1, a rejected host is by definition one the app did not authorise — so
  each could carry a sentinel and each could be varied per request to flood a
  metrics dimension. The record carries the classified `:scheme-class` and
  no host at all, leaving no value in it that the caller chose."
  [operation tags]
  (when-let [dispatch-error-record!
             (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      (assoc (rf.ssr.egress/redirect-record-tags tags)
             :rf.error/id operation
             :time  (rf.interop/now-ms))))
  nil)

(defn- emit-redirect-gate-error!
  "Fan a structured `:rf.error/redirect-*` gate refusal along BOTH error
  axes and return nil.

  Axis 1 is the ALWAYS-ON `error-emit` record — the half that
  reaches an off-box shipper in a production build, and which receives a
  closed STRUCTURAL PROJECTION of the map rather than the map. Axis 2 is the
  dev trace, which receives the diagnostics whole. The tag map is built once
  by [[redirect-gate-tags]], with the URL scrub applied there, so the two
  cannot disagree about the rejection they describe."
  [operation tags]
  (let [tags (redirect-gate-tags tags)]
    (dispatch-redirect-gate-record! operation tags)
    (rf.trace/emit-error! operation tags))
  nil)

(defn- validate-allow!
  "Throw `:rf.error/server-fx-args-invalid` unless `allow` is absent, `:any`,
  or a sequence of host strings. A scalar `:allow \"app.example.com\"` would
  `seq` into CHARACTERS and silently build a per-character allowlist —
  fail-closed, but a policy the caller never wrote. Reject the call instead."
  [allow]
  (when-not (or (nil? allow)
                (= :any allow)
                (and (sequential? allow) (every? string? allow)))
    (reject-arg! :rf.server/redirect :allow allow
                 ":any or a sequence of host strings")))

(defn redirect-fx
  "Handler fn for `:rf.server/redirect`. Relative-only by default: `:allow`
  names the hosts permitted beyond the request's own origin, and `:allow :any`
  is the explicit trusted spelling. A `:location` that fails the URL gate is
  refused — the fx emits the specific `:rf.error/redirect-*` category and
  writes no redirect — so an attacker-controlled `?next=...` parameter cannot
  redirect off-origin, including through opaque URIs such as
  `http:evil.example.com`. See Spec 011 §HTTP response contract §Standard fx.

  Args map:

    {:location \"/dashboard\"            ;; relative, or an absolute URL :allow names
     :allow    [\"app.example.com\"]     ;; or :any; absent means relative-only
     :status   302}                    ;; defaults to 302

  A malformed CALL throws before the gate runs, in every build: a non-map
  args value, a retired `:url` / `:to` key (`:rf.error/redirect-retired-
  target-key`, naming `:location`, with no alias), a non-string `:location`,
  a `:status` outside the integer 100–599 range, a malformed `:allow`, or a
  `:relative-only?` key (each `:rf.error/server-fx-args-invalid`), then CR /
  LF / NUL in `:location` (`:rf.error/redirect-invalid-location`, Spec 011
  §CRLF fail-fast), which no `:allow` waives. The URL gate then runs in the
  order the block above lists.

  `:location` is optional: a target-less redirect sets `:redirect` and
  falls through to the adapter's `:rf.ssr/ssr-redirect-no-target` warn→3xx.
  Last-write-wins, and the redirect's `:status` flows through to the
  response `:status`."
  [{:keys [frame]} {:keys [location allow status] :as redirect-map}]
  (validate-args-map! :rf.server/redirect redirect-map)
  (reject-retired-redirect-keys! redirect-map)
  ;; SHAPE gate, always-on, before anything is parsed. Note the taxonomy line
  ;; this respects: a malformed CALL is a programmer error and throws; an
  ;; untrusted INPUT that is a well-typed string but points off-origin stays
  ;; the URL gate's emit-and-no-op below. The CR/LF/NUL gate `str`-coerces,
  ;; so a keyword or URI object would otherwise sail through it and land on
  ;; the accumulator.
  (when (some? location)
    (validate-string-arg! :rf.server/redirect :location location))
  ;; The redirect's :status flows through to the response :status (Spec 011
  ;; §Redirect precedence step 1), so it is held to the same always-on range
  ;; gate as :rf.server/set-status.
  (validate-status! :rf.server/redirect (or status 302))
  (validate-allow! allow)
  (when (some? (:relative-only? redirect-map))
    (reject-arg! :rf.server/redirect :relative-only? (:relative-only? redirect-map)
                 (str "absent: the redirect is relative-only by default,"
                      " and :allow names any other host")))
  (when (some? location)
    (validate-redirect-location! location))
  (if-some [[error-id tags] (when (some? location)
                              (gate-refusal location allow))]
    (emit-redirect-gate-error! error-id (assoc tags :frame frame :location location))
    (let [final-status        (or status 302)
          ;; `:allow` is a policy input, not part of the wire redirect.
          normalised-redirect (-> redirect-map
                                  (dissoc :allow)
                                  (assoc :status final-status))]
      (swap-response!
        frame
        (fn [response]
          (-> response
              (assoc :redirect normalised-redirect)
              ;; Spec 011 §Redirect precedence step 1: the redirect's :status
              ;; flows through to the response :status so the host adapter
              ;; writes the redirect status on the wire even if no explicit
              ;; :rf.server/set-status fired.
              (assoc :status final-status)))))))
