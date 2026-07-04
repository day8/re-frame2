(ns re-frame.routing.history
  "Browser-history listener (popstate → url-owner frame) for re-frame2
  routing.

  Per Spec 012 §URL changes are events / §Multi-frame routing. The
  runtime does not own a window `popstate` listener — apps install it.
  Historically an app hand-rolled

    (.addEventListener js/window \"popstate\"
      (fn [_] (rf/dispatch [:rf.route/handle-url-change (current-url)])))

  which dispatches the URL-change event WITHOUT a `:frame`. Under Spec
  002's no-fallback ladder there is no `:rf/default` floor: a frameless
  ambient dispatch fails with `:rf.error/no-frame-context` — the runtime
  never synthesises or selects a default frame. So a hand-rolled listener
  has to name its target explicitly, and it must name the RIGHT one. When
  a non-default frame opts into URL binding
  (`(reg-frame :my-frame {:url-bound? true})`, with `:rf/default`
  releasing ownership via `{:url-bound? false}` — the single-non-default
  owner case, rf2-6qgbs.3), the PUSH side correctly routes through that
  frame (`url-owner-frame-id` gates `:rf.nav/push-url`), but a popstate
  dispatch targeted at the wrong (or defaulted) frame would update that
  frame's slice instead of the owner's — so Back/Forward never restored
  the visible route (rf2-6qgbs.4).

  `install-history-listener!` resolves the URL owner AT POP TIME via
  `url-owner-frame-id` and dispatches the URL change to THAT frame. It
  is symmetric with `:rf.nav/push-url`: the same single owner drives
  both directions. For the default-owned app the owner resolves to
  `:rf/default`, so existing apps behave identically; for a url-bound
  non-default frame Back/Forward now restores its route slice and body.

  rf2-aerrz5 (URL-strategy seam): the listener is one of the four
  strategy consult points. `install-url-listener!` resolves the URL
  owner's `:url-strategy` (default `history-url-strategy`, `popstate`) and
  installs THAT strategy's browser listener — `popstate` for a history
  app, `hashchange` for a hash app — decoding each browser-driven change
  to a PATH-FORM URL before dispatching `:rf.route/handle-url-change`. A
  hash app's hand-rolled `hashchange` adapter deletes; it uses this seam
  like every other app.

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split: HISTORY-LISTENER seam."
  (:require [re-frame.router :as router]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.strategy :as strategy]))

(defn current-url
  "Read the current browser URL as an app-relative string
  `pathname + search + hash`. CLJS-only — returns `\"/\"` when no
  `window.location` is available (SSR / node). Public so apps that wire
  their own history listener can recover the same projection
  `install-url-listener!` uses.

  This is the PATH-FORM projection of a HISTORY-strategy app's URL (it is
  `history-url-strategy`'s `:decode`). A hash app decodes differently —
  `install-url-listener!` uses the URL owner's strategy `:decode`, so a
  hash app never calls this directly; it is retained as the history
  default projection and for apps that wire their own history listener."
  []
  (strategy/history-decode))

#?(:cljs
   (defonce ^:private history-listener-atom
     ;; Holds the installed listener TEARDOWN thunk so a re-install
     ;; (`install-url-listener!` called twice — e.g. hot-reload) tears the
     ;; prior listener down rather than stacking listeners. The strategy's
     ;; `:install-listener!` returns the teardown; we hold it here.
     (atom nil)))

#?(:cljs
   (defn install-url-listener!
     "Install the URL-owning frame's browser URL-change listener, then sync
     the current URL into that frame's route slice at
     `[:rf.runtime/routing :current]`. This is the single strategy-aware boot
     seam (rf2-aerrz5) — it replaces the hand-rolled `popstate` / `hashchange`
     wiring apps used to write themselves.

     The listener kind is chosen by the URL owner's `:url-strategy`
     (default `history-url-strategy`): a HISTORY app gets a `popstate`
     listener, a HASH app gets a `hashchange` listener. Each browser-driven
     change is DECODED to a PATH-FORM URL by the strategy's `:decode`, then
     dispatched as `:rf.route/handle-url-change` — so the route cascade stays
     path-form regardless of the address-bar form.

     Per Spec 012 §Multi-frame routing the dispatch is targeted at
     `(url-owner-frame-id)` resolved AT FIRE TIME, so it tracks whichever
     single frame currently owns the URL — `:rf/default` for the common case,
     or a non-default `:url-bound? true` frame (rf2-6qgbs.4). This is the
     inbound (browser → app) counterpart of the outbound `:rf.nav/push-url`
     gate: one owner, both directions. `url-owner-frame-id` returns nil when
     no frame declared `:url-bound? true`; the callback then skips the
     dispatch (installing a listener with no declared owner is a
     routing-config no-op, not a default-frame write — EP-0002 rf2-nn0jqa).

     The STRATEGY (which browser event, which decode) is resolved once at
     install. The 0-arity resolves it from the current URL owner's
     `:url-strategy`; the 1-arity takes an EXPLICIT strategy map — useful when
     the URL-owning frame is created asynchronously (e.g. by a
     `frame-provider` whose React effect registers the frame AFTER `mount!`
     returns), so the caller pins the strategy without depending on
     registration timing. The OWNER (dispatch target) is always resolved per
     fire. An app using the 0-arity installs after registering its URL-owning
     frame.

     Idempotent: re-installing tears down the previously-installed listener
     (hot-reload safe). Returns `nil`. CLJS-only; the JVM half is a no-op
     (`:require`-able from `.cljc` boot code without a reader conditional).

     Both the initial sync and each change dispatch use `dispatch-sync!`: a
     `popstate` / `hashchange` event always fires on the browser's macrotask
     loop, never nested inside a re-frame drain, so the run-to-completion
     update is safe and the slice (and rendered body) restore synchronously
     within the same browser turn — no intermediate paint on the stale route.
     This mirrors the locked routing-history contract (the
     `popstate-via-window-listener-cljs` test dispatches `dispatch-sync`)."
     ([] (install-url-listener!
           (strategy/url-strategy-for-frame-id (nav-fx/url-owner-frame-id))))
     ([strat]
     (let [w (when (exists? js/window) js/window)
           decode (:decode strat)
           install-listener! (:install-listener! strat)
           ;; The dispatch targets the EXPLICITLY-declared URL owner resolved
           ;; AT FIRE TIME (EP-0002 rf2-nn0jqa) with the strategy-decoded
           ;; path-form URL. Skips when no owner is declared.
           dispatch-to-owner!
           (fn [path-url]
             (when-let [owner (nav-fx/url-owner-frame-id)]
               (router/dispatch-sync! [:rf.route/handle-url-change path-url]
                                      {:frame owner})))]
       (when w
         ;; Tear down any prior listener (hot-reload safe) before installing.
         (when-let [teardown @history-listener-atom]
           (try (teardown) (catch :default _ nil)))
         ;; The strategy installs its browser listener and returns a 0-arg
         ;; teardown thunk we hold for the next re-install / removal.
         (reset! history-listener-atom (install-listener! dispatch-to-owner!)))
       ;; Initial sync: hydrate the owner's slice from the current URL so
       ;; a deep link / reload lands on the right route on first paint.
       (dispatch-to-owner! (decode))
       nil))))

#?(:cljs
   (def install-history-listener!
     "Alias for `install-url-listener!` (rf2-aerrz5). Retained as the
     established boot-seam name for HISTORY-strategy apps — since the default
     strategy is history, `install-history-listener!` and
     `install-url-listener!` behave identically for a path-form app. A hash
     app can call either name; the owner's `:url-strategy` decides the
     listener kind. New code should prefer `install-url-listener!`."
     install-url-listener!))

#?(:cljs
   (defn remove-url-listener!
     "Tear down the browser URL-change listener installed by
     `install-url-listener!` (whichever kind the strategy wired — `popstate`
     or `hashchange`), via the teardown thunk it returned. No-op when none
     is installed. Useful for test isolation and frame-teardown in single-page
     hosts that rotate URL owners."
     []
     (when-let [teardown @history-listener-atom]
       (try (teardown) (catch :default _ nil))
       (reset! history-listener-atom nil))
     nil))

#?(:cljs
   (def remove-history-listener!
     "Alias for `remove-url-listener!` (rf2-aerrz5). Retained as the
     established teardown-seam name."
     remove-url-listener!))
