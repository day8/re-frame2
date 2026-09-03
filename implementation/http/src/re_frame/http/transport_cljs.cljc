(ns re-frame.http.transport-cljs
  "CLJS (Fetch-API) platform adapter for `:rf.http/managed`.

  Platform transport details live here so the
  shared attempt-and-retry lifecycle (`re-frame.http.transport`) reads as
  platform-neutral Clojure and each platform's reader-conditional code has
  a named home.

  This namespace owns the browser/Node Fetch path: issuing a single attempt
  (`cljs-fetch`), the same-origin/cross-origin heuristic the CORS classifier
  uses (`cross-origin?`), and the Fetch-rejection → `:rf.http/*` failure
  classifier (`classify-cljs-error`). The shared lifecycle calls
  `cljs-fetch` to issue an attempt and `classify-cljs-error` to map a
  rejection; it never reaches back into this namespace's internals.

  Everything here is `#?(:cljs …)` — on the JVM this namespace loads as
  effectively empty (the shared lifecycle only calls these fns under its
  `:cljs` reader branch). Keeping the conditionals local to the adapter is
  this boundary keeps CLJS interop out of the shared loop."
  (:require [clojure.string         :as str]
            [re-frame.error         :as rf.error]
            [re-frame.http.decode   :as rf.http.decode]
            [re-frame.http.encoding :as rf.http.encoding]
            [re-frame.http.privacy  :as rf.http.privacy]
            [re-frame.interop       :as rf.interop]
            [re-frame.trace         :as rf.trace]))

;; ---- platform transport: CLJS Fetch ---------------------------------------

#?(:cljs
   (defn- fetch-headers->map
     "Flatten a Fetch `Headers` object into a plain Clojure map, matching
     the JVM transport's `jvm-headers->map` response-headers SHAPE
     contract (Spec 014 §Request envelope: `string → string`,
     or `string → vector of strings` for a multi-valued header).

     `Headers.forEach`
     iterates ONE pair per (case-folded) name with multi-valued headers
     COMMA-FOLDED into a single string — and for `Set-Cookie` specifically
     the Fetch spec keeps it OUT of the combined view (`forEach` yields
     only the LAST `Set-Cookie` line; the earlier lines are dropped). That
     diverged from the JVM, where `jvm-headers->map` rides every wire line
     of a multi-valued header as a vector element — so a two-cookie
     response decoded to a 2-element `[\"session=…\" \"csrf=…\"]` vector on
     the JVM but a single (last-cookie-only) string on CLJS, silently
     LOSING the first `Set-Cookie` and comma-folding any other repeated
     header. Comma-folding `Set-Cookie` is invalid because cookie attribute
     values may themselves contain commas.

     The Fetch API exposes `Headers.getSetCookie()` precisely to recover
     the unfolded `Set-Cookie` lines (browser + Node 18.7+ / undici). We
     use it to restore the per-line vector shape so a multi-valued
     `Set-Cookie` decodes IDENTICALLY on both hosts; a single `Set-Cookie`
     stays a plain string (matching the JVM's single-valued fast path).
     Other repeated headers remain comma-folded by `forEach` — Fetch
     offers no unfold for them and that residual is a documented Fetch
     limitation, not a data-loss bug (only `Set-Cookie` both loses lines
     AND is RFC-illegal to fold)."
     [^js fetch-headers]
     (let [out #js {}]
       (.forEach fetch-headers
                 (fn [v k] (aset out k v)))
       (let [m (js->clj out)
             ;; Recover the unfolded `Set-Cookie` lines so the
             ;; shape matches the JVM (single → string, multi → vector of
             ;; the verbatim wire lines, every line preserved).
             set-cookies (when (fn? (.-getSetCookie fetch-headers))
                           (vec (.getSetCookie fetch-headers)))]
         (case (count set-cookies)
           0 m
           1 (assoc m "set-cookie" (first set-cookies))
           (assoc m "set-cookie" set-cookies))))))

#?(:cljs
   (defn cljs-fetch
     "Issue a single HTTP attempt via Fetch. Returns a Promise that resolves
     to `{:ok? bool :status N :status-text S :headers M :body-text S}` on
     transport-OK, or rejects with an ex-info classified by the caller.

     `cljs-fetch` owns ONLY the internal `AbortController`
     (`internal-controller`), whose `signal` is the single one Fetch
     accepts. The caller's external `:abort-signal` is NOT wired here.
     Per rf2-3fc89f.9, external cancellation is REQUEST-lifecycle-owned:
     the shared lifecycle (`re-frame.http.transport`) binds the external
     signal to the CURRENT phase handle's canonical `:abort-fn` (which
     aborts THIS internal controller when it fires), so abort precedence,
     the once-only reply CAS, registry cleanup, and backoff-timer
     cancellation apply uniformly regardless of lifecycle phase — rather
     than an attempt-local `internal-controller.abort()` that bypasses all
     of them. The internal controller still arms the per-attempt timeout
     independently of any external signal.

     `:sensitive?` and `:frame` are carried only so an
     invalid request header (a `Headers.append` `TypeError` on a bad name
     or a CR/LF value) can be surfaced as a redacted `:rf.warning/http-
     header-invalid` trace (omit the bad pair, continue) rather than
     ESCAPING the managed path as `:rf.error/fx-handler-exception` — the
     same managed-error contract `jvm-build-request` already honours on
     the JVM."
     [{:keys [method url headers body credentials mode redirect cache referrer
              integrity timeout-ms internal-controller decode
              sensitive?]}]
     (let [init     #js {}
           _        (do (aset init "method" (str/upper-case (name method)))
                        (when (seq headers)
                          ;; Build a Fetch `Headers` object and
                          ;; `.append` each normalised wire pair. A scalar
                          ;; header value yields one pair; a vector/seq value
                          ;; yields one pair per element (`encoding/normalize-
                          ;; header-pairs`), and `.append` ACCUMULATES repeated
                          ;; names into the multi-valued wire form (`Accept: a`
                          ;; + `Accept: b`) rather than overwriting.
                          ;;
                          ;; Surface a Fetch `Headers.append`
                          ;; validation throw via a `:rf.warning/http-header-
                          ;; invalid` trace rather than letting it ESCAPE the
                          ;; managed HTTP path. The Fetch `Headers` `.append`
                          ;; rejects an invalid header name (empty, control
                          ;; chars, separators) or a value carrying `\r`/`\n`
                          ;; (the response-splitting guard) by throwing a
                          ;; `TypeError`. Catch per `.append` and emit the redacted
                          ;; warning naming the offending header (value omitted —
                          ;; values may carry secrets; URL routed through
                          ;; `privacy/prepare-emit-tags` so a denylisted query
                          ;; param is scrubbed and `:sensitive?` is stamped),
                          ;; omit the bad pair, and continue with the valid
                          ;; headers. A stray bad header no longer sinks an
                          ;; otherwise-valid request; the trace is the alarm.
                          ;; Spec 014 §Body encoding + §Request envelope require
                          ;; request-prep failures to stay on the managed path,
                          ;; not escape as fx errors.
                          (let [h (js/Headers.)]
                            (doseq [[k v] (rf.http.encoding/normalize-header-pairs headers)]
                              (try
                                (.append h k v)
                                (catch :default e
                                  (when rf.interop/debug-enabled?
                                    (rf.trace/emit! :warning :rf.warning/http-header-invalid
                                                 (rf.http.privacy/prepare-emit-tags
                                                   {:url    url
                                                    :header k
                                                    :cause  (or (some-> ^js e .-message)
                                                                (str e))}
                                                   (true? sensitive?)))))))
                            (aset init "headers" h)))
                        (when (some? body) (aset init "body" body))
                        (when credentials  (aset init "credentials" (name credentials)))
                        (when mode         (aset init "mode"        (name mode)))
                        (when redirect     (aset init "redirect"    (name redirect)))
                        (when cache        (aset init "cache"       (name cache)))
                        (when referrer     (aset init "referrer"    referrer))
                        (when integrity    (aset init "integrity"   integrity))
                        (when internal-controller
                          (aset init "signal" (.-signal internal-controller))))
           timeout-handle (atom nil)
           ;; rf2-6ecc6 — a real measured elapsed for the synthetic
           ;; timeout, so CLJS `:elapsed-ms` carries the same SEMANTICS as
           ;; the JVM's (a measured wall-clock delta `>= :limit-ms`), not a
           ;; synthetic constant always `== :limit-ms`. `performance.now()`
           ;; is monotonic where present (browser + Node 16+); we fall back
           ;; to `Date.now()` on targets that lack it. Captured before the
           ;; timer is armed so the delta spans the whole attempt.
           now-ms   (fn []
                      (if (and (exists? js/performance)
                               (fn? (.-now js/performance)))
                        (.now js/performance)
                        (js/Date.now)))
           started-ms (now-ms)
           ;; rf2-4zldh — the per-attempt timeout must bound the WHOLE
           ;; attempt, headers AND body read. A slow-loris upstream can
           ;; send headers promptly and then stall the body
           ;; (`.text()` / `.blob()` / `.arrayBuffer()` / `.formData()`)
           ;; indefinitely (Spec 014 §`:timeout-ms` security defaults
           ;; :323). The earlier shape cleared the timer the instant the
           ;; Response (headers) resolved and only THEN began the body
           ;; read, leaving the body-reader promise pending forever and
           ;; the in-flight handle live. The fix: race the timer against
           ;; the FULL fetch→body-read chain and clear the timer only
           ;; when that chain SETTLES (`.finally`), not when headers
           ;; arrive. The timeout still aborts `internal-controller`
           ;; (which also rejects an in-progress body reader) and rejects
           ;; with the canonical `:rf.error/http-timeout` ex-info — the
           ;; same shape `classify-cljs-error` maps to `:rf.http/timeout`
           ;; and routes through `maybe-retry!` → `finalise-failure!`,
           ;; whose once-only `:finalised?` CAS clears the in-flight
           ;; registry and respects abort precedence (no double-abort).
           read-body
           (fn [resp]
             (let [ok?     (.-ok resp)
                   headers (fetch-headers->map (.-headers resp))
                   base    {:ok?         ok?
                            :status      (.-status resp)
                            :status-text (.-statusText resp)
                            :headers     headers}
                   ;; rf2-5zj6t — a Fetch Response body may be
                   ;; consumed only once, so the body-reader is
                   ;; chosen up front from the resolved `:decode`
                   ;; mode (mirroring `decode-response-body`).
                   ;; Binary modes (`:blob` / `:array-buffer` /
                   ;; `:form-data`) read the native body and ride
                   ;; under `:body-binary`; everything else (and
                   ;; every non-2xx response, whose raw text is
                   ;; what the 4xx/5xx failure paths carry) reads
                   ;; `.text()` into `:body-text`. Decode runs
                   ;; only on 2xx, so a non-OK response always
                   ;; takes the text path regardless of `:decode`.
                   binary-read-mode (when ok?
                                      (rf.http.decode/binary-read-kind decode headers))]
               (case binary-read-mode
                 :blob
                 (-> (.blob resp)
                     (.then (fn [b] (assoc base :body-binary b))))

                 :array-buffer
                 (-> (.arrayBuffer resp)
                     (.then (fn [b] (assoc base :body-binary b))))

                 :form-data
                 (-> (.formData resp)
                     (.then (fn [b] (assoc base :body-binary b))))

                 ;; nil / text-based decode → read text.
                 (-> (.text resp)
                     (.then (fn [body-text]
                              (assoc base :body-text body-text)))))))
           ;; The full attempt: fetch the Response, then read its body.
           ;; This is the promise the timeout races against — the timer
           ;; covers the body read, not just the headers.
           attempt-promise (-> (js/fetch url init)
                               (.then read-body))
           timeout-promise
           (js/Promise. (fn [_ reject]
                          ;; Per Spec 014 §`:timeout-ms` security
                          ;; defaults: BOTH `nil` and `0` are
                          ;; explicit opt-outs (no per-attempt
                          ;; timeout). `0` is truthy in CLJS, so
                          ;; `(when timeout-ms …)` would arm a
                          ;; `setTimeout(…, 0)` that aborts the
                          ;; request on the next macrotask — the
                          ;; opposite of "unbounded". The
                          ;; `(pos? …)` guard collapses
                          ;; `nil`/`0`/negative to "no timeout".
                          (when (and timeout-ms
                                     (pos? timeout-ms)
                                     internal-controller)
                            (reset! timeout-handle
                                    (js/setTimeout
                                      (fn []
                                        (try (.abort internal-controller "timeout")
                                             (catch :default _ nil))
                                        (reject (rf.error/thrown-ex-info
                                                  :rf.error/http-timeout
                                                  ':rf.http/managed
                                                  (str "the request exceeded its " timeout-ms "ms timeout and was aborted")
                                                  {:recovery :no-recovery
                                                   :extra
                                                   {;; co-stamp the registry-hook signal the
                                                    ;; downstream classifier branches on
                                                    :rf.http/timeout? true
                                                    ;; rf2-6ecc6 — a MEASURED wall-clock delta
                                                    ;; (whole ms, monotonic where available),
                                                    ;; not the synthetic `timeout-ms` constant.
                                                    ;; The setTimeout fires at ~`timeout-ms`, so
                                                    ;; this is `>= :limit-ms` by the scheduling
                                                    ;; margin — the SAME semantics the JVM path
                                                    ;; reports via `System/nanoTime`, closing the
                                                    ;; prior cross-host `:elapsed-ms` divergence.
                                                    :elapsed-ms       (js/Math.round (- (now-ms) started-ms))
                                                    :limit-ms         timeout-ms}})))
                                      timeout-ms)))))
           promise
           (-> (js/Promise.race #js [attempt-promise timeout-promise])
               ;; rf2-4zldh — clear the timer only once the WHOLE attempt
               ;; (headers + body read) settles, success or failure.
               ;; `.finally` runs on both branches and on the timeout
               ;; rejection itself, so the timer is always disarmed
               ;; before this promise hands off to `handle-response!` /
               ;; the `.catch` → `maybe-retry!` sink. Returning `nil`
               ;; keeps `.finally` value-transparent (it forwards the
               ;; original settlement, not the handler's return).
               (.finally (fn []
                           (when-let [h @timeout-handle] (js/clearTimeout h))
                           nil)))]
       promise)))

;; ---- external AbortSignal → request-lifecycle binding (rf2-3fc89f.9) -------
;;
;; An external `:abort-signal` is a REQUEST-lifecycle cancellation source, not
;; an attempt-local one. It must route to whichever canonical in-flight handle
;; owns the CURRENT phase (live-fetch or sleeping-backoff) and flip that
;; handle's `:aborted?` / `:finalised?` cells through its canonical
;; `:abort-fn`, exactly like `:rf.http/managed-abort` / supersede /
;; actor-destroy do — so abort precedence, the once-only reply CAS, registry
;; cleanup, and backoff-timer cancellation all apply uniformly (Spec 014
;; §`:abort-signal` external + §Abort precedence). The shared lifecycle
;; (`re-frame.http.transport`) owns the binding lifecycle: it BINDS on entry
;; to each phase (detaching the prior phase's listener) and DETACHES on every
;; terminal path, so a long-lived shared/parent `AbortController` never
;; accumulates completed-attempt listeners.
;;
;; The binding is a single mutable cell over ONE signal; `bind-external-abort!`
;; swaps the live listener on phase-ownership transfer (never a second
;; concurrent listener), and `detach-external-abort!` is idempotent.

#?(:cljs
   (defn make-external-abort
     "Create the phase-neutral external-abort binding over `signal` (the
     caller's `:abort-signal`). Returns a stateful binding value the shared
     lifecycle threads through the request ctx (`:external-abort`) and rebinds
     phase-by-phase, or nil when `signal` is nil (no external cancellation
     source). The binding holds the signal plus the currently-attached
     listener (nil until the first `bind-external-abort!`)."
     [signal]
     (when signal
       (atom {:signal signal :listener nil}))))

#?(:cljs
   (defn detach-external-abort!
     "Idempotently remove the currently-attached abort listener from the
     binding's signal. A no-op on a nil binding or when no listener is
     attached. Called on every phase-ownership transfer (before re-binding)
     and on every terminal completion path, so a shared/parent signal retains
     no completed-attempt listeners."
     [binding]
     (when binding
       (let [{:keys [signal listener]} @binding]
         (when listener
           (.removeEventListener signal "abort" listener)
           (swap! binding assoc :listener nil))))
     nil))

#?(:cljs
   (defn bind-external-abort!
     "Route the binding's signal `abort` event to `cancel!` — a 0-arg thunk
     that fires the CURRENT phase handle's canonical `:abort-fn :user`. First
     DETACHES any listener from a prior phase (ownership transfer), so exactly
     one listener is ever live on the signal. If the signal is ALREADY
     aborted, fires `cancel!` synchronously and attaches nothing — the caller
     then short-circuits the rest of attempt/backoff setup once the canonical
     abort has won its once-only CAS. A no-op on a nil binding."
     [binding cancel!]
     (when binding
       (detach-external-abort! binding)
       (let [signal (:signal @binding)]
         (if (.-aborted signal)
           (cancel!)
           (let [listener (fn [_] (cancel!))]
             (.addEventListener signal "abort" listener)
             (swap! binding assoc :listener listener)))))
     nil))

#?(:cljs
   (defn- cross-origin?
     "Heuristic: is `url` cross-origin relative to the current page?
     Returns `false` for same-origin URLs (incl. relative + same-host
     protocol-relative), `data:`/`blob:`/`file:` schemes, and any URL the
     host can't parse. The conservative path returns `false` so we never
     misclassify a same-origin Fetch failure as CORS.

     `js/location.origin` is the comparison base in browser hosts; on
     non-browser CLJS targets (Node / shadow-cljs node tests) the global
     is absent and we return `false`.

     rf2-azrcs — resolution semantics:

     - The URL is parsed WITH `loc-origin` as the base (`js/URL. url base`),
       so relative (`/x`, `?q`, `#f`), protocol-relative (`//host/x`), and
       absolute (`https://host/x`) URLs all resolve through one path. A
       protocol-relative URL inherits the page SCHEME but carries its own
       HOST, so `//other.invalid/x` is genuinely cross-origin while
       `//app.example/x` (same host) is same-origin — the prior
       single-slash short-circuit misclassified BOTH as same-origin.
     - The `data:`/`blob:`/`file:` scheme exclusion is matched
       CASE-INSENSITIVELY (URL schemes are case-insensitive per RFC 3986
       §3.1). The prior lowercase-only prefix check let `DATA:`/`FILE:`
       fall through to the parse, where their parsed origin is the literal
       string `\"null\"` (≠ page origin) and so false-classified as CORS."
     [^String url]
     (try
       (let [loc-origin (some-> js/globalThis
                                (aget "location")
                                (aget "origin"))]
         (cond
           (nil? url)        false
           (nil? loc-origin) false
           ;; data:/blob:/file: schemes are not http(s) origins; treat
           ;; as not-cross-origin (transport errors there are not CORS).
           ;; Scheme match is case-insensitive (RFC 3986 §3.1).
           (let [lower (str/lower-case url)]
             (or (str/starts-with? lower "data:")
                 (str/starts-with? lower "blob:")
                 (str/starts-with? lower "file:")))   false
           :else
           ;; Parse WITH the page origin as the base so relative,
           ;; protocol-relative, and absolute URLs all resolve in one
           ;; path. Same-origin relatives inherit `loc-origin`; a
           ;; protocol-relative URL with a different host resolves to its
           ;; own (cross) origin.
           (let [parsed (js/URL. url loc-origin)
                 origin (.-origin parsed)]
             (and (string? origin)
                  (not= origin loc-origin)))))
       (catch :default _ false))))

#?(:cljs
   (defn classify-cljs-error
     "Map a Fetch rejection / promise-error to a `:rf.http/*` failure shape.

     Per rf2-r40km (Spec 014 §Failure categories closed-set row
     `:rf.http/cors`): the Fetch API gives no formal signal for a CORS
     rejection — every CORS failure surfaces as a `TypeError` with a
     vendor-specific message (`Failed to fetch`, `Load failed`,
     `NetworkError when attempting to fetch resource`, …). We use a
     conservative heuristic: a `TypeError`-shaped rejection against a
     cross-origin URL classifies as `:rf.http/cors`; everything else
     stays at `:rf.http/transport`. Same-origin transport errors and
     non-`TypeError` rejections are never reclassified, so a real
     network drop on a same-origin endpoint still classifies correctly
     as `:rf.http/transport`.

     CLJS-only — the JVM transport (`classify-jvm-error`) never emits
     `:rf.http/cors` per Spec 014 (CORS is a browser-policy concern)."
     [^js err url]
     (let [data     (when (.-data err) (ex-data err))
           ;; `.-name` on a JS Error is the most stable signal we get
           ;; for the rejection class. Fetch CORS rejections are always
           ;; `TypeError`s; AbortErrors and rf2-bma05 ex-infos take
           ;; their own branches above.
           err-name (some-> err .-name)]
       (cond
         (:rf.http/timeout? data)
         {:kind       :rf.http/timeout
          :elapsed-ms (:elapsed-ms data)
          :limit-ms   (:limit-ms data)}

         (or (= "AbortError" err-name)
             (:rf.http/aborted? data))
         {:kind       :rf.http/aborted
          :request-id (:request-id data)
          :reason     (or (:reason data) :user)}

         ;; rf2-r40km — TypeError + cross-origin URL = CORS rejection.
         ;; Both signals required: the type narrows the universe to
         ;; Fetch-style transport rejections (network drops surface as
         ;; TypeErrors too), the cross-origin check separates CORS-
         ;; eligible URLs from same-origin ones (where CORS doesn't
         ;; apply by definition).
         (and (= "TypeError" err-name)
              (cross-origin? url))
         {:kind    :rf.http/cors
          :message (or (.-message err) (str err))
          :url     url}

         :else
         ;; rf2-6pcz0d — `:cause` is the rejection CLASS NAME string, not the
         ;; raw js/Error. A raw Error is not EDN-serializable, so it broke
         ;; off-box capture / Tool-Pair / Xray serialization of both the reply
         ;; (reply.cljc's EDN-serializable contract) and the `:rf.http/transport`
         ;; trace, AND slipped past the privacy `:cause` redactor's `(string?
         ;; …)` guard (privacy.cljc:364). `(.-name err)` matches BOTH sibling
         ;; paths: the JVM `classify-jvm-error` (`:cause cls`, transport_jvm.cljc)
         ;; and the CLJS `prepare-body!` (`:cause (some-> (.-name err))`,
         ;; transport.cljc). `:message` still carries the human message.
         {:kind    :rf.http/transport
          :message (or (.-message err) (str err))
          :cause   (some-> err .-name)}))))
