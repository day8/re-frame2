(ns re-frame.routing.history
  "Browser-history listener (popstate → url-owner frame) for re-frame2
  routing.

  Per Spec 012 §URL changes are events / §Multi-frame routing. The
  runtime does not own a window `popstate` listener — apps install it.
  Historically an app hand-rolled

    (.addEventListener js/window \"popstate\"
      (fn [_] (rf/dispatch [:rf.route/handle-url-change (current-url)])))

  which dispatches the URL-change event WITHOUT a `:frame`, so it lands
  on `:rf/default`. That is correct only while `:rf/default` owns the
  URL. When a non-default frame opts into URL binding
  (`(reg-frame :my-frame {:url-bound? true})`, with `:rf/default`
  releasing ownership via `{:url-bound? false}` — the single-non-default
  owner case, rf2-6qgbs.3), the PUSH side correctly routes through that
  frame (`url-owner-frame-id` gates `:rf.nav/push-url`), but a
  default-targeted popstate dispatch would update `:rf/default`'s
  (frozen) slice instead of the owner's — so Back/Forward never restored
  the visible route (rf2-6qgbs.4).

  `install-history-listener!` resolves the URL owner AT POP TIME via
  `url-owner-frame-id` and dispatches the URL change to THAT frame. It
  is symmetric with `:rf.nav/push-url`: the same single owner drives
  both directions. For the default-owned app the owner resolves to
  `:rf/default`, so existing apps behave identically; for a url-bound
  non-default frame Back/Forward now restores its route slice and body.

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split: HISTORY-LISTENER seam."
  (:require [re-frame.router :as router]
            [re-frame.routing.nav-fx :as nav-fx]))

(defn current-url
  "Read the current browser URL as an app-relative string
  `pathname + search + hash`. CLJS-only — returns `\"/\"` when no
  `window.location` is available (SSR / node). Public so apps that wire
  their own history listener can recover the same projection
  `install-history-listener!` uses."
  []
  #?(:cljs
     (if (and (exists? js/window) (.-location js/window))
       (let [loc (.-location js/window)]
         (str (.-pathname loc) (.-search loc) (.-hash loc)))
       "/")
     :clj "/"))

#?(:cljs
   (defonce ^:private history-listener-atom
     ;; Holds the installed popstate handler so a re-install
     ;; (`install-history-listener!` called twice — e.g. hot-reload)
     ;; replaces rather than stacks listeners.
     (atom nil)))

#?(:cljs
   (defn install-history-listener!
     "Install a `window` `popstate` listener that drives the URL-owning
     frame, then sync the current URL into that frame's route slice at
     `[:rf/runtime :routing :current]`.

     Per Spec 012 §Multi-frame routing the popstate dispatch is targeted
     at `(url-owner-frame-id)` resolved AT POP TIME, so it tracks whichever
     single frame currently owns the URL — `:rf/default` for the common
     case, or a non-default `:url-bound? true` frame (rf2-6qgbs.4). This is
     the inbound (browser → app) counterpart of the outbound `:rf.nav/push-url`
     gate: one owner, both directions.

     Idempotent: re-installing replaces the previously-installed listener
     (hot-reload safe). Returns `nil`. CLJS-only; the JVM half is a no-op
     (`:require`-able from `.cljc` boot code without a reader conditional).

     Both the initial sync and each popstate use `dispatch-sync!`: a
     `popstate` event always fires on the browser's macrotask loop, never
     nested inside a re-frame drain, so the run-to-completion update is
     safe and the slice (and rendered body) restore synchronously within
     the same browser turn — no intermediate paint on the stale route.
     This mirrors the locked routing-history contract (the
     `popstate-via-window-listener-cljs` test dispatches `dispatch-sync`)."
     []
     (let [w (when (exists? js/window) js/window)]
       (when w
         (when-let [prev @history-listener-atom]
           (try (.removeEventListener w "popstate" prev)
                (catch :default _ nil)))
         (let [handler (fn [_event]
                         (router/dispatch-sync! [:rf.route/handle-url-change (current-url)]
                                                {:frame (nav-fx/url-owner-frame-id)}))]
           (.addEventListener w "popstate" handler)
           (reset! history-listener-atom handler)))
       ;; Initial sync: hydrate the owner's slice from the current URL so
       ;; a deep link / reload lands on the right route on first paint.
       (router/dispatch-sync! [:rf.route/handle-url-change (current-url)]
                              {:frame (nav-fx/url-owner-frame-id)})
       nil)))

#?(:cljs
   (defn remove-history-listener!
     "Tear down the `popstate` listener installed by
     `install-history-listener!`. No-op when none is installed. Useful for
     test isolation and frame-teardown in single-page hosts that rotate
     URL owners."
     []
     (let [w (when (exists? js/window) js/window)]
       (when (and w @history-listener-atom)
         (try (.removeEventListener w "popstate" @history-listener-atom)
              (catch :default _ nil))
         (reset! history-listener-atom nil))
       nil)))
