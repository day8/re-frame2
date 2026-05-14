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
  - **Perf.** Pre-rf2-jbcmt every `:rf.server/*` fx swapped the WHOLE
    app-db container (`read-container → assoc → replace-container!`)
    just to update the accumulator. For a 7-fx response shape (typical
    login flow: `set-status` + 2× `set-cookie` + 3× `set-header` +
    `redirect`), that's seven full-app-db replacements per request —
    each one allocating a fresh container value with one key changed.
    The side-channel swap is O(small-map): one atom CAS against a
    `{frame-id → response-map}` table.

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
(def ^:private header-injection-chars-re
  #"[\r\n\x00]")

(defn- validate-header-value!
  "Throw `:rf.error/header-invalid-value` if the header value `v`
  contains CR / LF / NUL. Per RFC 7230 §3.2.4 — `field-value` is
  `*( field-content / obs-fold )` and obs-fold (CRLF + WSP) is
  deprecated; no CTLs allowed in `field-content`."
  [header-name v]
  (let [s (str v)]
    (when (re-find header-injection-chars-re s)
      (throw (ex-info ":rf.error/header-invalid-value"
                      {:reason   (str "header " (pr-str header-name)
                                      " value contains CR/LF/NUL — forbidden"
                                      " by RFC 7230 §3.2.4 (header-splitting"
                                      " injection)")
                       :header   header-name
                       :value    v
                       :recovery :no-recovery})))
    s))

(defn- validate-redirect-location!
  "Throw `:rf.error/redirect-invalid-location` if the redirect location
  `loc` contains CR / LF / NUL. Same CRLF-injection vector as a
  user-controlled `Location` header value: a query param like
  `?next=https://example.com%0d%0aSet-Cookie:%20stolen=1` URL-decodes
  into literal CRLF and would split the header on the wire."
  [loc]
  (let [s (str loc)]
    (when (re-find header-injection-chars-re s)
      (throw (ex-info ":rf.error/redirect-invalid-location"
                      {:reason   (str "redirect :location contains CR/LF/NUL"
                                      " — forbidden by RFC 7230 §3.2.4"
                                      " (header-splitting injection)")
                       :location loc
                       :recovery :no-recovery})))
    s))

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

(defn ensure-response
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
  on frame-id (rf2-jbcmt) — O(small-map) swap, no app-db ping-pong."
  [frame-id f]
  (let [next-resp (-> (swap! response-slots
                             update frame-id #(f (ensure-response %)))
                      (get frame-id))]
    next-resp))

(defn response-of
  "Read the current response accumulator (with defaults applied)."
  [frame-id]
  (ensure-response (get @response-slots frame-id)))

(defn clear-response!
  "Drop `frame-id`'s response slot. Called from
  `re-frame.ssr.request/on-frame-destroyed!` via the
  `:ssr/on-frame-destroyed` late-bind hook (rf2-fcj33). Idempotent —
  tolerates a frame-id with no slot."
  [frame-id]
  (swap! response-slots dissoc frame-id)
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

;; ---- handler fns for the six :rf.server/* fxs ----------------------------

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
    (when (and resp (> (count (get resp status-writes-key)) 1))
      (let [writes (get resp status-writes-key)]
        (trace/emit! :warning :rf.warning/multiple-status-set
                     {:writes       writes
                      :final-status (last writes)
                      :frame        frame
                      :recovery     :warned-and-replaced})))))

(defn set-header-fx
  "Handler fn for `:rf.server/set-header`. Replaces any existing header
  with the same name (case-insensitive). Throws
  `:rf.error/header-invalid-value` on a value carrying CR/LF/NUL
  (rf2-hbty2)."
  [{:keys [frame]} {:keys [name value]}]
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [r] (update r :headers replace-header name value))))

(defn append-header-fx
  "Handler fn for `:rf.server/append-header`. Preserves any existing
  header with the same name — required for Set-Cookie-style multi-valued
  headers. Throws `:rf.error/header-invalid-value` on a value carrying
  CR/LF/NUL (rf2-hbty2)."
  [{:keys [frame]} {:keys [name value]}]
  (validate-header-value! name value)
  (swap-response!
    frame
    (fn [r] (update r :headers append-header-pair name value))))

(defn set-cookie-fx
  "Handler fn for `:rf.server/set-cookie`. Cookie attributes are stored
  as a structured map (RFC 6265 wire-form serialisation is host-adapter
  business)."
  [{:keys [frame]} cookie-map]
  (swap-response!
    frame
    (fn [r] (update r :cookies (fnil conj []) cookie-map))))

(defn delete-cookie-fx
  "Handler fn for `:rf.server/delete-cookie`. Sugar over set-cookie
  with :max-age 0 and an empty :value."
  [{:keys [frame]} {:keys [name path domain]}]
  (let [cookie (cond-> {:name    name
                        :value   ""
                        :max-age 0}
                 path   (assoc :path   path)
                 domain (assoc :domain domain))]
    (swap-response!
      frame
      (fn [r] (update r :cookies (fnil conj []) cookie)))))

(defn redirect-fx
  "Handler fn for `:rf.server/redirect`. Defaults :status to 302 if
  absent. Multiple writes emit `:rf.warning/multiple-redirects`
  (last-write-wins). Throws `:rf.error/redirect-invalid-location` on a
  location carrying CR/LF/NUL (rf2-hbty2) — a `?next=…` query-param
  redirect would otherwise let an attacker forge headers.

  **Caller-trusted `:location`** — accepts arbitrary URL strings without
  allowlist or relative-only gating. For caller-untrusted location
  strings (e.g. a `?next=` URL param), use `:rf.server/safe-redirect`
  (rf2-zfm8v) which parses the URL, rejects javascript:/data:/vbscript:
  schemes, and supports `:relative-only?` / `:allow [...]` policies.
  See Spec 011 §HTTP response contract §Standard fx."
  [{:keys [frame]} redirect-map]
  (let [;; Spec 011 accepts :location, :url, or :to.
        location  (or (:location redirect-map)
                      (:url      redirect-map)
                      (:to       redirect-map))
        _         (when (some? location)
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
    (when (and resp (> (count (get resp redirect-writes-key)) 1))
      (let [writes (get resp redirect-writes-key)]
        (trace/emit! :warning :rf.warning/multiple-redirects
                     {:writes         writes
                      :final-redirect (last writes)
                      :frame          frame
                      :recovery       :warned-and-replaced})))))

;; ---- :rf.server/safe-redirect (rf2-zfm8v) --------------------------------
;;
;; The caller-untrusted variant of :rf.server/redirect. Where redirect-fx
;; accepts arbitrary :location strings, safe-redirect-fx parses the URL
;; and runs a five-step gate before populating the :redirect slot:
;;
;;   1. URL must parse — :rf.error/safe-redirect-invalid-url on failure.
;;   2. Reject javascript: / data: / vbscript: schemes (no safe redirect
;;      interpretation; consistent with the rf2-vwcsq custom-editor
;;      scheme-rejection policy) — :rf.error/safe-redirect-scheme-rejected.
;;   3. :relative-only? true AND URL has a host →
;;      :rf.error/safe-redirect-host-disallowed (:reason :relative-only-violation).
;;   4. :allow [...] supplied AND URL's host not in allowlist →
;;      :rf.error/safe-redirect-host-disallowed (:reason :not-in-allowlist).
;;   5. Pass — set Location header (same shape as redirect-fx).
;;
;; All four failure modes EMIT (via re-frame.trace) rather than THROW.
;; Throwing would bubble out as :rf.error/fx-handler-exception and the
;; programmer reading the trace would see a generic fx-handler-exception
;; pointing at safe-redirect-fx rather than the specific category.
;; Emit-and-no-op preserves the dispatch context — the cascade continues,
;; the response's :redirect stays unchanged, and the programmer sees
;; the specific :rf.error/safe-redirect-* category.
;;
;; Mitigation for the open-redirect class (security audit 2026-05-14
;; §P3.2): an attacker-controlled ?next=... URL parameter cannot redirect
;; off-origin when the app uses safe-redirect-fx instead of redirect-fx.

(def ^:private rejected-schemes
  "Closed set of schemes the safe-redirect-fx rejects outright. Per
  rf2-zfm8v decision step 2 and rf2-vwcsq's custom-editor scheme policy."
  #{"javascript" "data" "vbscript"})

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

  Validation order (per Spec 009 §Error event catalogue):

    1. URL parses → :rf.error/safe-redirect-invalid-url on failure
    2. scheme ∈ #{javascript data vbscript} → :rf.error/safe-redirect-scheme-rejected
    3. :relative-only? true + URL has host → :rf.error/safe-redirect-host-disallowed
       (:reason :relative-only-violation)
    4. :allow supplied + host ∉ allow → :rf.error/safe-redirect-host-disallowed
       (:reason :not-in-allowlist)
    5. Pass → set :redirect (same shape as :rf.server/redirect)

  Mitigation for the open-redirect class (audit 2026-05-14 §P3.2):
  an attacker-controlled ?next=... URL parameter cannot redirect
  off-origin when the app uses safe-redirect-fx.

  Per rf2-zfm8v (Mike decision, Option A — ship safe-redirect-fx
  alongside redirect-fx, 2026-05-14)."
  [{:keys [frame]} {:keys [location relative-only? allow status]
                    :as redirect-map}]
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
          (let [scheme #?(:clj (.getScheme ^URI uri) :cljs nil)
                host   #?(:clj (.getHost   ^URI uri) :cljs nil)]
            (cond
              ;; Step 2: scheme rejection (post-parse path — covers
              ;; schemes whose body DID parse cleanly, e.g.
              ;; `javascript:foo` without parens).
              (and scheme (rejected-schemes (clojure.string/lower-case scheme)))
              (emit-safe-redirect-error! :rf.error/safe-redirect-scheme-rejected
                                         {:frame    frame
                                          :location location
                                          :scheme   scheme})

              ;; Step 3: :relative-only? gate
              (and relative-only? host)
              (emit-safe-redirect-error! :rf.error/safe-redirect-host-disallowed
                                         {:frame    frame
                                          :location location
                                          :host     host
                                          :reason   :relative-only-violation})

              ;; Step 4: :allow [...] allowlist
              (and (seq allow)
                   host
                   (not (contains? (set allow) host)))
              (emit-safe-redirect-error! :rf.error/safe-redirect-host-disallowed
                                         {:frame    frame
                                          :location location
                                          :host     host
                                          :reason   :not-in-allowlist
                                          :allow?   (vec allow)})

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
                (when (and resp (> (count (get resp redirect-writes-key)) 1))
                  (let [writes (get resp redirect-writes-key)]
                    (trace/emit! :warning :rf.warning/multiple-redirects
                                 {:writes         writes
                                  :final-redirect (last writes)
                                  :frame          frame
                                  :recovery       :warned-and-replaced})))))))))))
