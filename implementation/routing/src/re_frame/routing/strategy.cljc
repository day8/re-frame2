(ns re-frame.routing.strategy
  "URL-strategy seam for re-frame2 routing.

  Per Spec 012 §URL strategies. The router is PATH-FORM on the write
  side: `route-url` builds `/active`, `match-url` reads `/active`, and
  the standard history fxs `pushState`/`replaceState` a path-form URL.
  A **`:url-strategy`** is a frame-level config map consulted at exactly
  four integration surfaces — the two history fxs, the `route-link` href
  render, and the URL-change listener. Everything else —
  `route-url`, `match-url`, the whole navigation cascade — stays PURE
  and PATH-FORM. The strategy is the thin skin between the router's
  path-form model and the browser's chosen address-bar form.

  THE CONTRACT. A strategy is a map with five keys:

    {:encode            (fn [path] href)
     :decode            (fn [] path)
     :push!             (fn [href])
     :replace!          (fn [href])
     :install-listener! (fn [on-change] teardown)}

  - `:encode` maps a path-form app URL (`/active?q=milk`) to the browser
    href form (`#/active?q=milk` for hash; unchanged for history). Pure and
    host-agnostic.
  - `:decode` reads the CURRENT browser URL and returns its path-form
    (`#/active` → `/active`; `pathname+search+hash` → itself for history).
    It reads `window.location` on CLJS and returns `/` without a browser.
  - `:push!` / `:replace!` drive `window.history` with the ENCODED href
    — `:push!` adds a history entry, `:replace!` overwrites the current
    one. They are RAW window.history legs: `:encode` is the SINGLE
    outbound encoding authority (the nav fxs encode the path-form URL to
    its final href once and hand it here), so `:push!` / `:replace!` must
    not re-encode. A custom strategy's legs receive the already-encoded href
    verbatim. Side-effecting, CLJS-only.
  - `:install-listener!` installs the browser URL-change listener and
    returns a 0-arg teardown thunk. `on-change` is a 1-arg fn the
    listener calls with the DECODED path-form URL on every browser-driven
    change (popstate for history, hashchange for hash). CLJS-only.

  `:encode` / `:decode` are inverses over the app-relative URL: for
  every path `p`, `(decode)` at a URL the browser reached via
  `(push! (encode p))` yields `p` back. This round-trip is the property
  the conformance fixtures pin (both shipped strategies).

  TWO STRATEGIES SHIP, and the line holds at two:
  - `history-url-strategy` (the DEFAULT) — HTML5 History, path-form.
  - `hash-url-strategy` — `#`-prefixed, for no-server-rewrite static
    hosting and secretary-era v1 migrations.
  Memory URLs need no third strategy: a frame that does not declare
  `:url-bound? true` is already URL-free (Spec 012 §Multi-frame routing),
  so a non-url-bound frame IS the 'memory' case, spec-free.

  SSR does not execute strategy side effects. On the server there is no
  address bar: the request URL is fed in path-form via
  `:rf.route/handle-url-change`, the view renders against the slice, and
  `route-link` emits its path-form `<a href>` shell. A hash never reaches the
  server. The pure `:encode` and fallback `:decode` functions remain present
  on the JVM; `:push!`, `:replace!`, and `:install-listener!` are CLJS-only.

  Internal namespace; the public facade is `re-frame.routing`, which
  re-exports the two shipped strategies."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]))

;; ---- history strategy (default) ------------------------------------------
;; Path-form. `:encode` / `:decode` are identity over the app-relative URL —
;; the path IS the browser URL. `:push!` / `:replace!` drive HTML5 History
;; with the path unchanged; `:install-listener!` wires `popstate`.

(defn history-encode
  "History strategy `:encode` — a path-form URL is already its own href.
  Identity; PURE; host-agnostic."
  [path]
  path)

(defn history-decode
  "History strategy `:decode` — read the current browser URL as an
  app-relative path string `pathname + search + hash`. CLJS-only;
  returns `\"/\"` on the JVM / when no `window.location` is available
  (SSR / node)."
  []
  #?(:cljs
     (if (and (exists? js/window) (.-location js/window))
       (let [loc (.-location js/window)]
         (str (.-pathname loc) (.-search loc) (.-hash loc)))
       "/")
     :clj "/"))

#?(:cljs
   (defn history-push!
     "History strategy `:push!` — `history.pushState` the path-form href."
     [href]
     (.pushState js/window.history nil "" href)))

#?(:cljs
   (defn history-replace!
     "History strategy `:replace!` — `history.replaceState` the path-form
     href."
     [href]
     (.replaceState js/window.history nil "" href)))

#?(:cljs
   (defn history-install-listener!
     "History strategy `:install-listener!` — install a `window`
     `popstate` listener that calls `on-change` with the decoded (path-form)
     current URL. Returns a 0-arg teardown thunk. Does NOT do the initial
     sync — the caller (`re-frame.routing.history/install-listener-for-owner!`,
     reached through the `reconcile-url-listener!` frame-lifecycle op) drives
     that once at install, under cause `:initial` rather than through this
     `on-change`, so it happens for every strategy uniformly."
     [on-change]
     (let [handler (fn [_event] (on-change (history-decode)))]
       (.addEventListener js/window "popstate" handler)
       (fn teardown [] (.removeEventListener js/window "popstate" handler)))))

(def history-url-strategy
  "The DEFAULT URL strategy: HTML5 History, path-form. `route-url` builds
  `/active`, `pushState` pushes `/active`, and `popstate` drives the
  URL-owner frame with the decoded path.

  A frame that declares no `:url-strategy` uses this. Per Spec 012 §URL
  strategies. The `:push!` / `:replace!` / `:install-listener!` keys are
  present on CLJS only; on the JVM the map carries just `:encode` / `:decode`
  (SSR ignores strategies — see the namespace docstring)."
  (merge {:encode history-encode
          :decode history-decode}
         #?(:cljs {:push!             history-push!
                   :replace!          history-replace!
                   :install-listener! history-install-listener!}
            :clj  {})))

;; ---- hash strategy -------------------------------------------------------
;; `#`-prefixed. `:encode` maps a path-form URL to `#<path>`; `:decode`
;; strips the leading `#` off `window.location.hash`. `:push!` sets the
;; hash (via pushState so a history entry is created); `:replace!` swaps
;; it with replaceState; `:install-listener!` wires `hashchange`.

(defn hash-encode
  "Hash strategy `:encode` — prefix a path-form URL with `#`. `/active`
  → `#/active`; `/` → `#/`. PURE; host-agnostic. An already-`#`-prefixed
  input is returned unchanged (idempotent), so callers that pass a raw
  hash href don't get double-hashed."
  [path]
  (cond
    (nil? path)                         "#/"
    (clojure.string/starts-with? path "#") path
    :else                               (str "#" path)))

(defn hash-decode
  "Hash strategy `:decode` — read `window.location.hash`, strip the
  leading `#`, and return the path-form URL. An empty hash decodes to
  `\"/\"` (the root route). CLJS-only; returns `\"/\"` on the JVM / no
  `window` (SSR)."
  []
  #?(:cljs
     (if (and (exists? js/window) (.-location js/window))
       (let [raw (.. js/window -location -hash)]
         (if (or (nil? raw) (= "" raw) (= "#" raw))
           "/"
           (let [stripped (subs raw 1)]        ;; drop the leading '#'
             (if (clojure.string/starts-with? stripped "/")
               stripped
               (str "/" stripped)))))
       "/")
     :clj "/"))

#?(:cljs
   (defn hash-push!
     "Hash strategy `:push!` — push a NEW history entry carrying the
     already-encoded hash href. `:encode` is the single
     outbound encoding authority — the nav fxs encode the path-form URL to
     `#/active` ONCE and hand it here — so this leg pushes the href
     UNCHANGED and must NOT re-encode, or a base-path deploy would
     double-hash (`#/demos#/active`). Uses `pushState` (not a bare
     `location.hash =`) so the entry is created deterministically without
     also firing a synchronous `hashchange` mid-drain."
     [href]
     (.pushState js/window.history nil "" href)))

#?(:cljs
   (defn hash-replace!
     "Hash strategy `:replace!` — overwrite the current history entry with
     the already-encoded hash href via `replaceState` (no new entry). RAW,
     like `hash-push!`: `:encode` is the single outbound
     encoding authority, so this leg replaces with the href UNCHANGED — no
     internal `hash-encode`."
     [href]
     (.replaceState js/window.history nil "" href)))

#?(:cljs
   (defn hash-install-listener!
     "Hash strategy `:install-listener!` — install a `window` `hashchange`
     listener that calls `on-change` with the decoded (path-form) hash.
     Returns a 0-arg teardown thunk. Like the history variant it does not
     do the initial sync (the caller drives that once)."
     [on-change]
     (let [handler (fn [_event] (on-change (hash-decode)))]
       (.addEventListener js/window "hashchange" handler)
       (fn teardown [] (.removeEventListener js/window "hashchange" handler)))))

(def hash-url-strategy
  "The hash URL strategy: `#`-prefixed URLs (`#/active`) for
  no-server-rewrite static hosting and secretary-era v1 migrations.
  Declare it on the URL-owning frame:

      (rf/make-frame {:id :app
                      :url-bound?   true
                      :url-strategy rf.routing/hash-url-strategy})

  `route-url` still builds path-form `/active`; the strategy `:encode`s it
  to `#/active` at the `route-link` href and the history fxs, and `:decode`s
  `window.location.hash` back to `/active` for the URL-change listener. The
  rest of routing is unchanged. Per Spec 012 §URL strategies. `:push!` /
  `:replace!` / `:install-listener!` are CLJS-only (SSR ignores strategies)."
  (merge {:encode hash-encode
          :decode hash-decode}
         #?(:cljs {:push!             hash-push!
                   :replace!          hash-replace!
                   :install-listener! hash-install-listener!}
            :clj  {})))

;; ---- base-path combinator -------------------------------------------------
;;
;; A frame served from a deployment SUB-PATH — a host mounting several demos
;; side by side, so an app that owns `/` on its own instead lives at
;; `/realworld/` — needs that prefix STRIPPED off every inbound URL before it
;; reaches the router (so `route-url` / `match-url` / the whole cascade stay
;; base-agnostic, app-relative path-form) and RE-ADDED to every outbound
;; href / history URL (so the address bar and `route-link` hrefs carry the
;; real mount-point path).
;;
;; `with-base-path` is a combinator over an existing strategy, not a third
;; shipped strategy: base-path handling is orthogonal to address-bar FORM
;; (history vs hash). It wraps the four egress/ingress consult points —
;; `:encode` / `:decode` / `:push!` / `:replace!` / `:install-listener!` —
;; so the wrapped strategy's own form (identity vs `#`-prefix) is preserved
;; underneath the base-path prefix/strip. It wraps `:encode`, `:decode`, and
;; listener ingress; the already-encoded `:push!` / `:replace!` legs pass
;; through unchanged.

(defn- normalize-base-path
  "Normalize a base-path string: nil / blank -> `\"\"` (no base); else
  ensure a leading `/` and strip a trailing `/`. Pure."
  [base]
  (let [b (or base "")]
    (if (clojure.string/blank? b)
      ""
      (let [b (if (clojure.string/starts-with? b "/") b (str "/" b))]
        (if (and (> (count b) 1) (clojure.string/ends-with? b "/"))
          (subs b 0 (dec (count b)))
          b)))))

(defn strip-base-path
  "Strip `base` off the front of path-form `url`, returning the app-relative
  `/`-rooted remainder. `url` is under the base only when it EQUALS `base` (the
  mount root) or starts with `(str base \"/\")` (a path-SEGMENT boundary) — a
  bare string-prefix test would mis-slice a prefix-sharing SIBLING (base `/app`
  must NOT strip `/application`, `/apple`, `/app-admin`). A `url` that is not
  under the base — including such a sibling, or a fully unrelated URL — is
  returned unchanged (defensive: fails safe rather than mis-slicing the path).
  A blank `base` is a no-op. Pure."
  [base url]
  (if (and (seq base)
           (or (= url base)
               (clojure.string/starts-with? url (str base "/"))))
    (let [remainder (subs url (count base))]
      (if (clojure.string/starts-with? remainder "/")
        remainder
        (str "/" remainder)))
    url))

(defn with-base-path
  "Wrap `strategy` (`history-url-strategy` / `hash-url-strategy` / a custom
  strategy map) so every egress/ingress consult point accounts for a
  deployment BASE PATH. Per Spec 012 §URL strategies:

    - `:decode`            strips `base` off the wrapped strategy's decoded
                           path-form URL.
    - `:encode`            re-adds `base` to the wrapped strategy's encoded
                           href — the single outbound base-composition point.
                           `route-link` renders this href and the
                           nav fxs push it, so the mount-point prefix sits
                           OUTSIDE the address-bar form (`/demos#/active` for a
                           hash app, `/demos/active` for a history app).
    - `:push!` / `:replace!` are NOT re-wrapped — `:encode` is the single
                           outbound encoding authority, so the base already
                           rides the encoded href the nav fxs hand these RAW
                           legs. Re-adding it here would double it into the
                           fragment on a hash app (`#/demos#/active`).
    - `:install-listener!` strips `base` off each browser-driven change
                           before calling `on-change`, so the dispatched
                           `:rf.route/handle-url-change` URL is always
                           app-relative.

  `route-url` / `match-url` and the rest of the navigation cascade stay
  PATH-FORM and base-agnostic; only these consult points ever see the base
  path — exactly like the two shipped strategies' address-bar-form seam.

  `base` is normalized (a leading `/` is added if missing; a trailing `/`
  is stripped). A blank/nil `base` returns `strategy` UNCHANGED — the common
  no-sub-path app pays no wrapping cost. Only `:install-listener!` among the
  side-effecting keys is wrapped (base-stripping ingress); `:push!` /
  `:replace!` pass through unwrapped from the inner strategy, since `:encode`
  already carries the base outbound. The wrapped `:install-listener!` is
  CLJS-only, mirroring the two shipped strategies (SSR ignores strategies).

  Declare on the URL-owning frame:

      (rf/make-frame {:id :app
                      :url-bound?   true
                          :url-strategy (routing/with-base-path
                                          routing/history-url-strategy
                                          \"/realworld\")})"
  [strategy base]
  (let [b (normalize-base-path base)]
    (if (clojure.string/blank? b)
      strategy
      (merge strategy
             {:encode (fn [path] (str b ((:encode strategy) path)))
              :decode (fn [] (strip-base-path b ((:decode strategy))))}
             ;; :push! / :replace! are deliberately not wrapped:
             ;; `:encode` is the single outbound encoding authority — the nav
             ;; fxs encode once and hand these RAW legs the final base-prefixed
             ;; href — so the inner strategy's :push!/:replace! pass through via
             ;; `merge` unchanged. Re-adding the base here would double it into
             ;; the fragment on a hash app (`#/demos#/active`). Only the ingress
             ;; listener is wrapped: it STRIPS the base so `on-change` stays
             ;; app-relative.
             #?(:cljs
                (cond-> {}
                  (:install-listener! strategy)
                  (assoc :install-listener!
                         (fn [on-change]
                           ((:install-listener! strategy)
                            (fn [decoded] (on-change (strip-base-path b decoded)))))))
                :clj {})))))

;; ---- frame-config resolution ---------------------------------------------
;; A frame declares its URL strategy in its `make-frame` config map under
;; `:url-strategy`, exactly as it declares `:url-bound?`. The four consult
;; points resolve the ACTIVE strategy by reading the URL owner's config,
;; defaulting to the history strategy when unset. This mirrors
;; `re-frame.routing.nav-fx/url-bound?-from-config`.

;; ---- custom-strategy validation ------------------------------------------
;; Per Spec 012 §URL strategies a `:url-strategy` is a map of CALLABLE legs.
;; A typo, a partial hand-rolled adapter, or a dev hot-reload intermediate
;; value used to enter the frame registry VERBATIM and only fail later —
;; deep in a consult point — as a host-specific raw nil-function / TypeError
;; (`url-strategy-from-config` returned every truthy config value unchecked;
;; rf2-j538f7.11). `validate-url-strategy!` fails LOUD with a canonical
;; structured error when the shape is wrong.
;;
;; THE VALIDATION SEAM IS FRAME CONSTRUCTION (rf2-ktmto9 / rf2-ecb4sx). The
;; registration-time `preflight-frame-config!` runs this check at the sole
;; frame-config commit chokepoint (`re-frame.frame/upsert-frame!`), BEFORE the
;; strategy can ever enter the `frames` store the consult points read. Because
;; that is the ONE config writer into the store (pinned by the store-invariant
;; + no-bypass tests in `routing_url_strategy_test`), the four consult points
;; are TRUSTED READS — they resolve an already-validated strategy VERBATIM and
;; do NOT re-validate per consult (the ~90 ns/consult `route-link` used to pay
;; per render; rf2-ecb4sx). `url-strategy-from-config` keeps only a dev-only
;; (`interop/debug-enabled?`, DCE'd in production) tripwire so a future
;; config-write bypass still fails loud in development.

(def ^:private url-strategy-required-legs
  "The CALLABLE legs a custom `:url-strategy` must carry, per host. `:encode`
  / `:decode` are pure and host-agnostic and required on BOTH hosts (SSR
  `route-link` renders through `:encode`). The three side-effecting browser
  legs `:push!` / `:replace!` / `:install-listener!` are required on CLJS but
  are reader-conditionally ABSENT from the shipped JVM strategies (SSR never
  executes them), so JVM validation does NOT require them (Spec 012 §URL
  strategies — SSR does not execute strategy side effects)."
  #?(:cljs [:encode :decode :push! :replace! :install-listener!]
     :clj  [:encode :decode]))

(defn validate-url-strategy!
  "Fail-loud shape/callability check for an explicitly-declared
  `:url-strategy`, per Spec 012 §URL strategies. `strategy` must be a MAP
  carrying a CALLABLE (`fn?`) value for every host-required leg
  (`url-strategy-required-legs`); extension keys are permitted and preserved.
  Throws the canonical `:rf.error/invalid-url-strategy` — naming the
  missing/non-callable legs and the offending value — when the shape is wrong;
  returns `strategy` UNCHANGED on success so callers can thread it.

  The fail-loud validation seam is frame construction: `preflight-frame-config!`
  calls this at the registration chokepoint (rf2-ktmto9), and it also backs the
  dev-only consult tripwire in `url-strategy-from-config` (rf2-ecb4sx).
  `where-sym` names the surface for the diagnostic (`'rf/make-frame`: the
  strategy is declared in the frame's `make-frame` config); `context` merges
  call-site slots (e.g. a frame id) into the ex-data."
  [strategy where-sym context]
  (when-not (map? strategy)
    (throw (error/thrown-ex-info
             :rf.error/invalid-url-strategy where-sym
             (str ":url-strategy must be a map carrying callable "
                  (clojure.string/join " / " url-strategy-required-legs)
                  " legs, but it was " (pr-str strategy))
             {:extra (merge {:url-strategy strategy
                             :required     (vec url-strategy-required-legs)}
                            context)})))
  (let [missing (vec (remove #(fn? (get strategy %)) url-strategy-required-legs))]
    (when (seq missing)
      (throw (error/thrown-ex-info
               :rf.error/invalid-url-strategy where-sym
               (str ":url-strategy is missing a callable value for "
                    (clojure.string/join " / " missing)
                    " — a URL strategy must carry callable "
                    (clojure.string/join " / " url-strategy-required-legs)
                    " legs")
               {:extra (merge {:url-strategy strategy
                               :required     (vec url-strategy-required-legs)
                               :missing      missing}
                              context)}))))
  strategy)

(defn preflight-frame-config!
  "Registration-time PREFLIGHT over a frame's FINAL expanded `make-frame`
  config (rf2-ktmto9). PURE validation — no writes, no side effects, no
  strategy-leg execution (shape/callability is the enforceable static
  contract; probing `:push!` / `:install-listener!` would itself cause
  browser effects).

  PRESENCE semantics: a config with NO `:url-strategy` key is a no-op —
  omission alone selects the default `history-url-strategy`. A PRESENT key
  — INCLUDING an explicit `nil` — is an explicit declaration and must be a
  valid strategy map, so `{:url-strategy nil}` fails loud
  (`:rf.error/invalid-url-strategy`) exactly like any other malformed
  value. The ex-data carries `{:frame frame-id}` so the diagnostic names
  the offending frame.

  Published as the `:routing/preflight-frame-config!` late-bind hook on
  BOTH hosts; the frame engine `re-frame.frame/upsert-frame!` (the one
  frame-config commit
  chokepoint) invokes it with the final expanded config BEFORE any
  candidate-derived write — the frame-record build, the
  trace-policy flags, the frames swap, the `:initial-events` setup
  dispatch, and any trace emit — so a malformed declaration leaves NO
  residue on a first registration and preserves every previously committed
  value (and the installed URL listener) on a re-registration. Returns nil."
  [frame-id config]
  (when (contains? config :url-strategy)
    (validate-url-strategy! (:url-strategy config) 'rf/make-frame
                            {:frame frame-id}))
  nil)

(defn url-strategy-from-config
  "Read `:url-strategy` from a frame's stored `make-frame` config map,
  defaulting to `history-url-strategy` when unset (or when `config` is not
  a map). The default is the identity/path-form strategy.

  TRUSTED READ (rf2-ecb4sx). This backs the strategy CONSULT points — the
  `route-link` href render (per render), the `:rf.nav/push-url` /
  `:rf.nav/replace-url` fxs, and the URL-change-listener install. It reads a
  strategy that was ALREADY validated fail-loud at the sole frame-config
  commit chokepoint (`preflight-frame-config!` at frame construction /
  re-construction, rf2-ktmto9): the `frames` store is the one place a seated
  `:url-strategy` lives, and `re-frame.frame/upsert-frame!` — its only config
  writer — preflights BEFORE the store write, so no code path can seat an
  unvalidated strategy (pinned by the store-invariant + no-bypass tests in
  `routing_url_strategy_test`). The consult therefore returns the declared
  strategy VERBATIM and pays NO per-render validation — a ~30x reduction of
  the consult's cost (rf2-ecb4sx measured ~93.6 ns → ~3.1 ns per call on a
  declared-strategy frame; the eliminated `validate-url-strategy!` was
  ~90 ns/consult, dead work re-checking an immutable, already-validated map
  on every render).

  A dev-only tripwire (`interop/debug-enabled?` — `goog.DEBUG` on CLJS, the
  `re-frame.debug` gate on the JVM) re-runs `validate-url-strategy!` on the
  declared strategy so a config-write bypass introduced by a FUTURE refactor
  still fails loud in development; it is dead-code-eliminated from production
  CLJS bundles (`goog.DEBUG=false`), so the render hot path pays nothing. The
  unset/default branch skips even that: `history-url-strategy` is known-good."
  [config]
  (let [declared (when (map? config) (:url-strategy config))]
    (when (and interop/debug-enabled? (some? declared))
      (validate-url-strategy! declared 'rf/make-frame nil))
    (or declared history-url-strategy)))

(defn url-strategy-for-frame-id
  "Resolve the `:url-strategy` for `frame-id` by reading its stored frame
  config off the frames store (`frame/frame-config` — rf2-h1vqa4: frames have
  no registrar rows), defaulting to `history-url-strategy`. `nil` frame-id
  (or an unregistered / destroyed frame — `frame-config` returns nil) resolves
  to the history default. Used by the `route-link` href render (per render),
  the `:rf.nav/push-url` / `:rf.nav/replace-url` fxs, and the URL-change
  listener install — the strategy CONSULT points.

  A TRUSTED READ (rf2-ecb4sx): the store only ever holds a strategy that
  passed the registration-time preflight, so this returns it verbatim with no
  per-consult validation. See `url-strategy-from-config`.

  rf2-ecb4sx removed the per-consult VALIDATION and left the per-consult
  ALLOCATION: the read went through `frame/frame-meta`, which builds the
  canonical `:rf/frame-meta` shape by merging the config, the lifecycle and
  the id into a fresh map — a whole map, per rendered link, to reach one key.
  rf2-cno31's census probe measured this consult at 0.72 µs per link against a
  `route-url` synthesis of 4.71. It now reads the config map directly
  (`frame/frame-config`). The answer is unchanged for every input: the
  lifecycle fields and the stamped `:id` that `frame-meta` merges on top are
  disjoint from `:url-strategy`, and a missing frame yields nil from either."
  [frame-id]
  (if (nil? frame-id)
    history-url-strategy
    (url-strategy-from-config (frame/frame-config frame-id))))
