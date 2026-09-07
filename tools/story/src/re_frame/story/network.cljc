(ns re-frame.story.network
  "Realize a compiled variant's authored `:network` fixture on the LIVE run
  paths (spec/017 §The network surface — \"the runner installs the route map
  and points `:fx-overrides` at the stub fx\").

  `rf.story.plan/lower-network` is pure: it keeps the authored route map at
  the plan's `[:world :network]` and emits the `{:rf.http/managed
  :rf.http/managed-test-stub}` redirect at `[:world :frame :fx-overrides]`.
  Naming the stub id REGISTERS NOTHING — `re-frame.http.test-support/
  install-managed-request-stubs!` is what creates the fx over a route map.
  Before rf2-shx4 the only executable call to it in Story was
  `rf.story.artifact/with-network-stubs!` (artifact REPLAY), so a live
  registered-variant run reached the real `:rf.http/managed` transport and a
  live inline-plan run redirected to an unregistered fx id. This namespace is
  the missing realization step; the runtime calls it either side of a frame's
  lifetime.

  ## Why one install, and why a frame-scoped route map

  `install-managed-request-stubs!` re-registers ONE global fx id
  (`:rf.http/managed-test-stub`) with a route map CLOSED OVER at install
  time, and its stack is snapshot/restore (LIFO), not a merge. So the naive
  \"install per mounted variant\" makes the second variant's routes answer for
  BOTH — exactly the silent cross-variant overwrite rf2-shx4 forbids.

  Two facts make per-frame isolation reachable from inside Story, with NO
  second HTTP simulator, no change to the lowered fx id (so the recorded
  `:fx-decisions` redirect, `spec/017` and the artifact replay path all stay
  as they are) and no change to the `re-frame.http` artefact:

  - `re-frame.http.test-support/stub-handler` reads its route map with a
    plain `(get stubs [method url])`, so `stubs` need only satisfy `ILookup`
    — it does not have to be a literal map; and
  - `re-frame.router` binds `re-frame.frame/*current-frame*` to the
    envelope's frame for the WHOLE handler chain, the fx walk included
    (`router.cljc` §2 of the `process-event!` bindings).

  So Story installs ONE route-map-shaped object whose lookup resolves
  `[method url]` against `{frame-id → routes}` keyed on the frame in flight.
  Ownership is per frame; the single install/uninstall pair is refcounted by
  the registry — installed when it goes empty→non-empty, uninstalled when it
  goes non-empty→empty — because Story's mount/destroy order is NOT LIFO and
  an install-per-variant would unbalance that stack.

  A frame with no fixture (or a request from no frame at all) resolves to
  `nil`, so `stub-handler` falls through to its own canned
  `\"no stub matched\"` transport failure — spec/017 §Network stubs, preserved
  rather than reimplemented."
  (:require [re-frame.frame :as rf.frame]
            ;; The raw HTTP stub pair lives in `re-frame.http.test-support`
            ;; (its home namespace, not the `re-frame.core` façade). CLJS
            ;; requires it directly; on the JVM it is resolved LAZILY at call
            ;; time so requiring this ns from the `re-frame.story` MACRO-ns
            ;; does not drag the http artefact's JVM-only transitive deps onto
            ;; the macro classpath. Same boundary `rf.story.artifact` uses.
            #?(:cljs [re-frame.http.test-support :as rf.http.test-support])))

(defonce
  ^{:doc "frame-id → the frame's authored `{[method url] {:reply …}}` route
         map. The SSOT for which live Story frame owns which canned network
         replies. Populated by `install-for-frame!`, evicted by
         `release-frame!`."}
  frame-routes
  (atom {}))

(defn routes-for
  "The route map owned by `frame-id`, or `nil`. Read by the frame-scoped
  lookup below and by tests."
  [frame-id]
  (get @frame-routes frame-id))

(defn- current-frame-id
  "The frame id of the cascade in flight, or `nil` outside one.

  `re-frame.frame/*current-frame*` may carry a frame VALUE (`with-frame` /
  `with-new-frame` bind `make-frame`'s token) where the router binds the
  id, so normalize through `frame-value->id` exactly as the framework's own
  `:frame/current-frame-id` late-bind hook does (rf2-h1vqa4). Keying the
  registry on an un-normalized value would miss every value-bound extent."
  []
  (rf.frame/frame-value->id (rf.frame/current-frame)))

;; The route-map-shaped object handed to `install-managed-request-stubs!`.
;; `stub-handler` only ever asks it for `[method url]`, so `ILookup` is the
;; whole contract; resolving the frame at LOOKUP time (not install time) is
;; what gives two concurrently mounted variants their own replies.
(deftype FrameScopedRoutes []
  #?(:clj clojure.lang.ILookup :cljs ILookup)
  (#?(:clj valAt :cljs -lookup) [_ k]
    (get (routes-for (current-frame-id)) k))
  (#?(:clj valAt :cljs -lookup) [_ k not-found]
    (get (routes-for (current-frame-id)) k not-found)))

(def ^:private frame-scoped-routes
  "The single `stubs` object every Story install shares. One instance: the
  per-frame answer comes from the registry it reads, never from the object."
  (->FrameScopedRoutes))

(defn- install!
  "Register `:rf.http/managed-test-stub` over the frame-scoped route map."
  []
  (let [install #?(:clj  (requiring-resolve 're-frame.http.test-support/install-managed-request-stubs!)
                   :cljs rf.http.test-support/install-managed-request-stubs!)]
    (install frame-scoped-routes)))

(defn- uninstall!
  []
  (let [uninstall #?(:clj  (requiring-resolve 're-frame.http.test-support/uninstall-managed-request-stubs!)
                     :cljs rf.http.test-support/uninstall-managed-request-stubs!)]
    (uninstall)))

(defn release-frame!
  "Drop `frame-id`'s ownership of its canned replies, and uninstall the stub
  fx once no Story frame owns any — the non-empty→empty edge of the single
  install/uninstall pair. Called from frame teardown (`destroy!` /
  `destroy-inline!`), so a reset, a destroy and an inline run's completion
  all release exactly the one fixture they own and leave every other mounted
  variant's replies intact. Idempotent — releasing an unowned frame is a
  no-op and never touches the install stack."
  [frame-id]
  (when (contains? @frame-routes frame-id)
    (swap! frame-routes dissoc frame-id)
    (when (empty? @frame-routes) (uninstall!)))
  nil)

(defn install-for-frame!
  "Take ownership of `routes` for `frame-id` and make sure the stub fx the
  plan's `:fx-overrides` redirect names is registered.

  Called by the runtime BEFORE the frame is allocated — so before any
  `:frame-setup` `:init`, loader, `:setup` or script effect can issue a
  managed request. Idempotent: re-running the same variant re-asserts the
  same ownership without a second install.

  An empty/absent `routes` RELEASES any ownership `frame-id` held, so a
  variant recompiled without `:network` stops answering from a stale
  fixture. Returns `frame-id`."
  [frame-id routes]
  (if-not (seq routes)
    (do (release-frame! frame-id) frame-id)
    (let [was-empty? (empty? @frame-routes)]
      (swap! frame-routes assoc frame-id routes)
      (when was-empty? (install!))
      frame-id)))
