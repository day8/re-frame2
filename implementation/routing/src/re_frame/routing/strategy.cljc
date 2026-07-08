(ns re-frame.routing.strategy
  "URL-strategy seam for re-frame2 routing (rf2-aerrz5).

  Per Spec 012 §URL strategies. The router is PATH-FORM on the write
  side: `route-url` builds `/active`, `match-url` reads `/active`, and
  the standard history fxs `pushState`/`replaceState` a path-form URL.
  A hash-URL app (`#/active`) — the shape most secretary-era v1 apps
  actually are — therefore had to hand-roll a `hashchange` adapter and
  hand-build `#/active` hrefs, forfeiting `route-link` / `route-url` /
  `:rf.route/navigate` wholesale.

  A **`:url-strategy`** is a frame-level config map consulted at exactly
  FOUR egress/ingress points — the two history fxs, the `route-link`
  href render, and the URL-change listener install. Everything else —
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
    href form (`#/active?q=milk` for hash; unchanged for history). PURE.
  - `:decode` reads the CURRENT browser URL and returns its path-form
    (`#/active` → `/active`; `pathname+search+hash` → itself for
    history). Side-reading (touches `window.location`), CLJS-only.
  - `:push!` / `:replace!` drive `window.history` with the ENCODED href
    — `:push!` adds a history entry, `:replace!` overwrites the current
    one. Side-effecting, CLJS-only.
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

  SSR IGNORES STRATEGIES. On the server there is no `window` and no
  address bar: the request URL is fed in path-form via
  `:rf.route/handle-url-change`, the view renders against the slice, and
  `route-link` emits its path-form `<a href>` shell (the CLJS render fn's
  strategy-encoded href takes over on hydration). A hash never reaches
  the server. So every strategy key here is CLJS-only (the JVM/SSR half
  is a no-op), and the four consult points fall back to path-form when no
  `window` is present.

  Internal namespace; the public facade is `re-frame.routing`, which
  re-exports the two shipped strategies. Per the rf2-2yabr cohesion
  split: URL-STRATEGY seam."
  (:require [re-frame.registrar :as registrar]))

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
     sync — the caller (`install-url-listener!`) drives that once at install
     via `on-change` so it happens for every strategy uniformly."
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
     "Hash strategy `:push!` — push a NEW history entry carrying the hash
     href. Uses `pushState` (not a bare `location.hash =`) so the entry is
     created deterministically without also firing a synchronous
     `hashchange` mid-drain."
     [href]
     (.pushState js/window.history nil "" (hash-encode href))))

#?(:cljs
   (defn hash-replace!
     "Hash strategy `:replace!` — overwrite the current history entry with
     the hash href via `replaceState` (no new entry)."
     [href]
     (.replaceState js/window.history nil "" (hash-encode href))))

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

      (rf/reg-frame :app {:url-bound?   true
                          :url-strategy rf/hash-url-strategy})

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

;; ---- base-path combinator (rf2-g8pbwg) ------------------------------------
;;
;; A frame served from a deployment SUB-PATH — a host mounting several demos
;; side by side, so an app that owns `/` on its own instead lives at
;; `/realworld/` — needs that prefix STRIPPED off every inbound URL before it
;; reaches the router (so `route-url` / `match-url` / the whole cascade stay
;; base-agnostic, app-relative path-form) and RE-ADDED to every outbound
;; href / history URL (so the address bar and `route-link` hrefs carry the
;; real mount-point path).
;;
;; `with-base-path` is a COMBINATOR over an existing strategy, not a third
;; shipped strategy: base-path handling is orthogonal to address-bar FORM
;; (history vs hash). It wraps the four egress/ingress consult points —
;; `:encode` / `:decode` / `:push!` / `:replace!` / `:install-listener!` —
;; so the wrapped strategy's own form (identity vs `#`-prefix) is preserved
;; underneath the base-path prefix/strip.

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
  `/`-rooted remainder. `url` that does not start with `base` is returned
  UNCHANGED (defensive — should not happen for a correctly-mounted app; fails
  safe rather than mis-slicing an unrelated URL). A blank `base` is a no-op.
  Pure."
  [base url]
  (if (and (seq base) (clojure.string/starts-with? url base))
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
                           href (the `route-link` / history-fx egress leg).
    - `:push!` / `:replace!` re-add `base` before driving the wrapped
                           strategy's history mutation, so the browser
                           address bar carries the real mount-point URL.
    - `:install-listener!` strips `base` off each browser-driven change
                           before calling `on-change`, so the dispatched
                           `:rf.route/handle-url-change` URL is always
                           app-relative.

  `route-url` / `match-url` and the rest of the navigation cascade stay
  PATH-FORM and base-agnostic; only these consult points ever see the base
  path — exactly like the two shipped strategies' address-bar-form seam.

  `base` is normalized (a leading `/` is added if missing; a trailing `/`
  is stripped). A blank/nil `base` returns `strategy` UNCHANGED — the common
  no-sub-path app pays no wrapping cost. The side-effecting keys
  (`:push!` / `:replace!` / `:install-listener!`) are CLJS-only, mirroring
  the two shipped strategies (SSR ignores strategies).

  Declare on the URL-owning frame:

      (rf/reg-frame :app {:url-bound?   true
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
             #?(:cljs
                (cond-> {}
                  (:push! strategy)
                  (assoc :push! (fn [href] ((:push! strategy) (str b href))))
                  (:replace! strategy)
                  (assoc :replace! (fn [href] ((:replace! strategy) (str b href))))
                  (:install-listener! strategy)
                  (assoc :install-listener!
                         (fn [on-change]
                           ((:install-listener! strategy)
                            (fn [decoded] (on-change (strip-base-path b decoded)))))))
                :clj {})))))

;; ---- frame-config resolution ---------------------------------------------
;; A frame declares its URL strategy in its `reg-frame` config map under
;; `:url-strategy`, exactly as it declares `:url-bound?`. The four consult
;; points resolve the ACTIVE strategy by reading the URL owner's config,
;; defaulting to the history strategy when unset. This mirrors
;; `re-frame.routing.nav-fx/url-bound?-from-config`.

(defn url-strategy-from-config
  "Read `:url-strategy` from a frame's stored `reg-frame` config map,
  defaulting to `history-url-strategy` when unset (or when `config` is not
  a map). The default is the identity/path-form strategy, so a frame that
  declares nothing behaves exactly as before this seam existed."
  [config]
  (or (when (map? config) (:url-strategy config))
      history-url-strategy))

(defn url-strategy-for-frame-id
  "Resolve the `:url-strategy` for `frame-id` by reading its stored
  `reg-frame` config, defaulting to `history-url-strategy`. `nil` frame-id
  (or an unregistered frame) resolves to the history default. Used by the
  `route-link` href render, which captures the render-time frame id."
  [frame-id]
  (if (nil? frame-id)
    history-url-strategy
    (url-strategy-from-config (get (registrar/registrations :frame) frame-id))))
