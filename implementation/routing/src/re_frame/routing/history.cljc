(ns re-frame.routing.history
  "Browser-history listener (popstate → url-owner frame) for re-frame2
  routing.

  Per Spec 012 §URL changes are events / §Multi-frame routing. The
  runtime does not own a window `popstate` listener directly — a
  `:url-bound? true` frame's lifecycle owns it: the
  listener installs the moment the URL-owning frame is created (or
  re-registered) and is removed when that frame is destroyed. Nothing
  imperative for an application to call. Browser changes dispatch
  synchronously to the explicitly declared URL owner; there is no default-
  frame fallback for an absent owner.

  ONE STRATEGY-AWARE RECONCILIATION OP owns the listener. `reconcile-url-
  listener!` resolves the CURRENT URL owner (`url-owner-frame-id`) and its
  `:url-strategy`, compares them to the OWNER + STRATEGY of the listener that
  is actually installed (tracked in `history-listener-atom`), and establishes
  EXACTLY ONE matching listener — or none:

    - no owner            → tear down any installed listener (leave none);
    - owner+strategy match the installed listener → LEAVE it untouched (so a
      losing-duplicate registration never disturbs the incumbent instance, and
      a repeated reconcile never stacks a second listener);
    - otherwise           → tear the installed listener down and install the
      current owner's strategy listener, then sync the current URL into the
      owner's slice.

  Because the decision is OWNERSHIP-driven (not tied to which frame just
  (re)registered), the SAME op both installs the successor when the incumbent
  relinquishes/destroys its binding while another live claimant remains, AND
  rewires the SAME owner when only its `:url-strategy` changed (hot-reload).
  Each installed callback re-resolves its dispatch target when it fires, so
  Back/Forward always drives whichever frame currently owns the URL — symmetric
  with `:rf.nav/push-url`: one explicitly declared owner, both directions.

  The listener is one of the URL-strategy consult points: the reconcile op
  resolves the URL owner's `:url-strategy` (default `history-url-strategy`) and
  installs THAT strategy's browser listener — `popstate` for a history app,
  `hashchange` for a hash app — decoding each browser-driven change to a
  path-form URL before dispatching `:rf.route/handle-url-change`.

  Reconciliation runs from TWO lifecycle points:

    - `re-frame.routing`'s `:routing/on-frame-registered!` hook body — the
      frame engine (`re-frame.frame/upsert-frame!`) POST-CREATE hook, fired
      AFTER the frame container exists (and, on first registration, after
      `:initial-events` ran), so the listener's synchronous initial URL sync
      always targets a live frame. The facade body runs the url-bound
      exclusivity/claim maintenance FIRST (`re-frame.routing.url-bound`), then
      calls `reconcile-url-listener!` — claims are current when the owner is
      resolved. It reconciles after every (re-)registration, so a
      post-registration claim change (an owner opting out with `:url-bound?
      false` while a successor is live) rebinds to the successor's strategy.
    - `re-frame.routing/release-routing-host-caches!` —
      `frame/destroy-frame!`'s teardown hook. It drops the destroyed frame's
      URL claim and then reconciles, passing the destroyed id as the EXCLUDED
      owner (defence in depth: the destroyed frame is already invisible to
      `frame-meta` reads by hook time — `:destroyed?` flipped earlier in the
      teardown — but the exclusion keeps the contract explicit rather than
      timing-derived). A destroyed owner with a live successor rebinds to
      it; a destroyed owner with no successor leaves no listener.

  A losing duplicate `:url-bound? true` registration (`id` is NOT the resolved
  owner — `re-frame.routing.url-bound` already emitted
  `:rf.error/duplicate-url-binding`) leaves the resolved owner unchanged, so
  reconciliation is a no-op and the incumbent listener instance is untouched.

  Internal namespace; the public facade is `re-frame.routing`."
  (:require [re-frame.router :as router]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.strategy :as strategy]))

(defn current-url
  "Read the current browser URL as an app-relative string
  `pathname + search + hash`. Returns `\"/\"` on the JVM or when no
  `window.location` is available. This is a public query, not a listener-
  installation step.

  This is the PATH-FORM projection of a HISTORY-strategy app's URL (it is
  `history-url-strategy`'s `:decode`). A hash app decodes differently —
  the lifecycle listener uses the URL owner's strategy `:decode`; this
  function specifically exposes the history strategy's projection."
  []
  (strategy/history-decode))

#?(:cljs
   (defonce ^:private history-listener-atom
     ;; Holds the INSTALLED-LISTENER RECORD `{:owner frame-id :strategy strat
     ;; :teardown thunk}`, or `nil` when nothing is installed. `reconcile-url-
     ;; listener!` compares the current URL owner + strategy to `:owner` /
     ;; `:strategy` here to decide whether the installed listener already
     ;; matches (leave it — no re-install, no stacking) or must be torn down and
     ;; replaced. The strategy's `:install-listener!` returns the `:teardown`
     ;; thunk we hold for that teardown. CLJS-only host state — deliberately
     ;; OUTSIDE runtime-db (the listener is a host resource, not app state).
     (atom nil)))

#?(:cljs
   (defn- teardown-current!
     "Tear down the currently-installed listener (if any) via its held
     teardown thunk and clear the record. Idempotent."
     []
     (when-let [teardown (:teardown @history-listener-atom)]
       (try (teardown) (catch :default _ nil)))
     (reset! history-listener-atom nil)
     nil))

#?(:cljs
   (defn- install-listener-for-owner!
     "Tear down any installed listener, then install `owner`'s `strat` browser
     URL-change listener, record `{:owner :strategy :teardown}`, and sync the
     current URL into the owner's route slice at `[:rf.runtime/routing
     :current]`.

     The listener kind is the strategy's (`popstate` for history, `hashchange`
     for hash); each browser-driven change is DECODED to a PATH-FORM URL by the
     strategy's `:decode` then dispatched as `:rf.route/handle-url-change`, so
     the route cascade stays path-form regardless of the address-bar form.

     The dispatch targets `(url-owner-frame-id)` re-resolved AT FIRE TIME, so
     Back/Forward always drives whichever frame currently owns the URL even if
     ownership changes after install. Both the initial sync and each change
     dispatch use `dispatch-sync!`: a `popstate` / `hashchange` fires on the
     browser's macrotask loop, never nested inside a re-frame drain, so the
     run-to-completion update is safe and the slice restores synchronously
     within the same browser turn (the locked routing-history contract)."
     [owner strat]
     (let [w (when (exists? js/window) js/window)
           decode (:decode strat)
           install-listener! (:install-listener! strat)
           ;; EP-0037 R0b: `:rf.route/handle-url-change` stands for three
           ;; doors, so the listener names WHICH one it is via the
           ;; runtime-internal `:rf.route/cause` rider on the event's trailing
           ;; opts map (the sibling of `:rf.route/decided?`). Both dispatches
           ;; below go through this one closure, so the two causes are the
           ;; closure's one argument rather than two dispatch sites that can
           ;; drift.
           dispatch-to-owner!
           (fn [cause path-url]
             (when-let [o (nav-fx/url-owner-frame-id)]
               (router/dispatch-sync! [:rf.route/handle-url-change path-url
                                       {:rf.route/cause cause}]
                                      {:frame o})))]
       (when w
         ;; Failure-atomic handoff (rf2-j538f7.11): install the REPLACEMENT
         ;; listener FIRST, then tear the incumbent down only once the new one
         ;; is in hand. If `install-listener!` throws (a callable-but-throwing
         ;; adapter — the non-callable case already fails loud at
         ;; strategy-resolution, before this fn), the incumbent listener +
         ;; record survive rather than being orphaned. The two listeners
         ;; coexist only within this synchronous block (no popstate/hashchange
         ;; fires mid-block), and the old one is removed immediately, so there
         ;; is no window for a double-fire.
         (let [new-teardown (install-listener! (partial dispatch-to-owner! :popstate))]
           (teardown-current!)
           (reset! history-listener-atom
                   {:owner    owner
                    :strategy strat
                    :teardown new-teardown})))
       ;; Initial sync: hydrate the owner's slice from the current URL so a deep
       ;; link / reload / ownership transfer lands on the right route. Cause
       ;; `:initial` — this is the initial page load, not a Back/Forward.
       (dispatch-to-owner! :initial (decode))
       nil)))

#?(:cljs
   (defn reconcile-url-listener!
     "THE single strategy-aware listener reconciliation op. Resolve the current
     URL owner (`url-owner-frame-id`) and its `:url-strategy`, compare them to
     the owner + strategy of the listener actually installed
     (`history-listener-atom`), and establish EXACTLY ONE matching listener — or
     none:

       - no current owner                              → tear down any installed
                                                          listener (leave none);
       - current owner + strategy MATCH the installed  → leave the installed
                                                          listener instance
                                                          UNTOUCHED (no
                                                          re-install, no
                                                          stacking, no re-sync);
       - otherwise (owner changed, or same owner's
         strategy changed — hot-reload rewire)         → tear the installed
                                                          listener down and
                                                          install the current
                                                          owner's strategy
                                                          listener + initial
                                                          sync.

     `excluded-id` (destroy path) is the frame being torn down. By the time
     `:routing/on-frame-destroyed!` fires, the destroyed frame's `:destroyed?`
     flag is already flipped, so `frame-meta` reads it as absent and the
     claim-free fallback cannot resolve it — the exclusion is defence in
     depth (an explicit contract rather than a timing-derived one): a
     resolved owner equal to `excluded-id` is treated as no owner.

     Ownership-driven, so ONE op serves both lifecycle points: it installs the
     successor when the incumbent relinquishes/is destroyed while a live
     claimant remains, and rewires the same owner when only its strategy
     changed. A losing-duplicate registration does not change the resolved
     owner, so reconciliation is a no-op — the incumbent's listener instance is
     preserved. Returns `nil`."
     ([] (reconcile-url-listener! nil))
     ([excluded-id]
      (let [resolved  (nav-fx/url-owner-frame-id)
            owner     (when (not= resolved excluded-id) resolved)
            strat     (when owner (strategy/url-strategy-for-frame-id owner))
            installed @history-listener-atom]
        (cond
          ;; No declared owner (or the only resolvable candidate is the frame
          ;; being torn down) → ensure no listener remains.
          (nil? owner)
          (teardown-current!)

          ;; The installed listener already matches the current owner AND
          ;; strategy → leave the exact instance untouched.
          (and (= owner (:owner installed))
               (= strat (:strategy installed)))
          nil

          ;; Ownership transferred, or the same owner's strategy changed →
          ;; establish the matching listener (tears down the prior one first).
          :else
          (install-listener-for-owner! owner strat))
        nil))))

#?(:cljs
   (defn remove-url-listener!
     "Tear down the browser URL-change listener (whichever kind the strategy
     wired — `popstate` or `hashchange`) unconditionally. No-op when none is
     installed. Published as the `:routing/reset-url-listener!` test-isolation
     hook (the shared `make-reset-runtime-fixture` reset-hook table calls it
     between tests — a raw `frame/frames` reset does not run `destroy-frame!`'s
     teardown chain, so a leftover installed listener would otherwise survive
     into the next test). The `:routing/on-frame-destroyed!` teardown reconciles
     instead (`reconcile-url-listener!`), which tears the destroyed owner's
     listener down as part of resolving the successor (or none)."
     []
     (teardown-current!)))

;; The post-(re-)registration listener reconcile is composed into the facade's
;; `:routing/on-frame-registered!` hook body (`re-frame.routing`), ORDERED
;; AFTER the url-bound exclusivity/claim maintenance so `url-owner-frame-id`
;; resolves against current claims. The facade calls
;; `reconcile-url-listener!` directly — ownership-driven, so it does the right
;; thing on every registration path: first URL owner installs; a frame newly
;; opting into `:url-bound? true` installs; an owner re-registering with a
;; changed `:url-strategy` rewires once; an owner opting OUT with
;; `:url-bound? false` while a successor is live rebinds to the successor's
;; strategy; and a losing-duplicate `:url-bound? true` registration (the
;; resolved owner is unchanged — `re-frame.routing.url-bound` already emitted
;; `:rf.error/duplicate-url-binding`) or an ordinary non-url-bound frame is a
;; no-op, leaving the incumbent listener instance untouched.
