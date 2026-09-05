(ns re-frame.resources.revalidate-listeners
  "Host window-focus / network-reconnect listeners that feed the resource
  revalidation events (rf2-vtblcq, EP-0003 slice 10). Per Spec 016 §Stale
  and GC scheduling / §Deferred slices.

  TanStack Query's most visible magic is that stale ACTIVE data refreshes
  when the user returns to the tab or the network reconnects. re-frame2
  provides the same user-facing behaviour, but through EVENTS rather than
  subscription lifecycle (Spec 016 §Deferred slices: focus/reconnect
  revalidation as resource events, NOT subscription-driven fetching).

  ## The seam

  The host browser surfaces a `focus` / `online` (network reconnect) event
  on `window` and a `visibilitychange` (tab return) event on `document` (the
  ONLY target `visibilitychange` fires on — it is a `document` event, not a
  `window` one, and `document.visibilityState` is what the handler reads).
  This namespace wires those to a resource event dispatched at a specific
  FRAME:

    window  focus                         -> [:rf.resource/window-focused]
    document visibilitychange-to-visible  -> [:rf.resource/window-focused]
    window  online                        -> [:rf.resource/network-reconnected]

  The event handlers (`re-frame.resources.events`) do the active-stale scan
  + background refetch-by-policy. The listener carries NO policy — it only
  translates a host event into a frame-targeted resource event (the cause).

  ## The frame lifecycle owns them — there is no install/remove fn

  Revalidation is a FRAME PROPERTY, declared by the `:revalidate-on`
  frame-config key: a SET drawn from the closed enum `#{:focus :reconnect}`
  (rf2-kuky.33). The frame lifecycle reconciles it — (re-)registration
  installs exactly the declared subset through the façade's
  `:resources/on-frame-registered!` hook, and frame destroy removes.
  Nothing is sequenced by hand, exactly as routing's `:url-bound?` key owns
  the browser URL-change listener (rf2-g8pbwg, API-shrink #6): there is no
  install/remove fn, on this namespace or on the façade. The retired
  imperative pair is named in spec/API.md §Resources and is GONE
  (pre-alpha, no back-compat shim).

  The installed host handles live in this module-level side table keyed by
  frame-id (NOT runtime-db, NOT serialized — transient host state, exactly
  like the work-ledger handle table, the stale/GC timer table, and the
  generation high-water cache). They are cancelled on frame destroy via the
  EXISTING single `:resources/on-frame-destroyed!` teardown hook the façade
  publishes — composed with the work-ledger + timer + generation host-cache
  release (ONE hook, no second teardown path; the rf2-afpdkn / rf2-nbjewi
  posture).

  ## JVM safety (CLJC reader conditionals)

  `window` focus/online listeners are a BROWSER-ONLY host surface. The CLJS
  arm wires `js/window` `addEventListener` / `removeEventListener`; the JVM
  arm is a no-op so the namespace `:require`s cleanly from `.cljc` boot code
  (SSR / JVM unit tests) without a live DOM. The frame-targeted dispatch
  rides the published `:router/dispatch!` late-bind hook (the resources
  artefact never statically `:require`s the router); a stripped runtime with
  no dispatcher bound no-ops harmlessly.

  Idempotent: reconciling a frame REPLACES (does not stack) its listeners —
  hot-reload safe, and repeated `frame-root` renders with identical opts
  cause no listener churn (the same teardown-then-reinstall shape
  `re-frame.routing.history/reconcile-url-listener!` uses)."
  (:require [re-frame.late-bind :as rf.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def window-focused-event
  "The resource event a window-focus (tab-return) signal dispatches. Its
  handler scans the frame's active-owner stale entries and refetches them in
  the background with cause `:focus`. User code MUST NOT dispatch it — the
  host focus listener does. Per Spec 016 §Deferred slices."
  :rf.resource/window-focused)

(def network-reconnected-event
  "The resource event a network-reconnect signal dispatches. Its handler
  scans the frame's active-owner stale entries and refetches them in the
  background with cause `:reconnect`. User code MUST NOT dispatch it — the
  host `online` listener does. Per Spec 016 §Deferred slices."
  :rf.resource/network-reconnected)

;; ---- host-side listener side table (Spec 016 [Runtime-Subsystems] 5) ------
;;
;; Module-level transient host cache keyed by `frame-id` → the installed host
;; listener handles for that frame. NOT runtime-db, NOT serialized, off the
;; epoch / SSR egress wire — exactly like the work-ledger `handle-table`, the
;; stale/GC `timer-table`, and the generation high-water cache. Cleared
;; per-frame on frame destroy via the single `:resources/on-frame-destroyed!`
;; hook (composed in the façade). Each value is a map of
;; `{:focus <handler> :visibility <handler> :online <handler>}` (CLJS), or
;; absent (JVM — no listeners installed).

(defonce
  ^{:doc "Host-side side table of NON-serializable window focus / online
   listener handles, keyed by `frame-id` → `{:focus … :visibility … :online …}`.
   Transient host state (NOT runtime-db), so an epoch restore cannot rewind it
   and it never rides the SSR / hydration / epoch wire. Cleared per-frame on
   frame destroy (`release-frame!`) through the single
   `:resources/on-frame-destroyed!` teardown hook (composed with the
   work-ledger + timer + generation host-cache release). Per Spec 016 §Stale
   and GC scheduling / [Runtime-Subsystems] clause 5."}
  listener-table
  (atom {}))

(defn dispatch-revalidation!
  "Dispatch a revalidation `event-id` (`:rf.resource/window-focused` /
  `:rf.resource/network-reconnected`) targeted at `frame-id`, through the
  published `:router/dispatch!` late-bind hook (the resources artefact never
  statically `:require`s the router), stamping `:source :revalidate` so Xray's
  timeline labels the focus/reconnect-driven cascade. No-op when no dispatcher
  is bound (a stripped runtime). The event handler does the active-stale scan
  + refetch-by-policy — the listener only translates the host event.
  Platform-neutral (no DOM — late-bind dispatch only); the only production
  caller is the CLJS focus/online listener, so the JVM never reaches it (it
  installs no listeners)."
  [frame-id event-id]
  (when-let [dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    (dispatch! [event-id] {:frame frame-id :source :revalidate})))

#?(:cljs
   (defn- browser-window
     "The `js/window` object, or nil when no DOM is present (node / SSR)."
     []
     (when (exists? js/window) js/window)))

#?(:cljs
   (defn- browser-document
     "The `js/document` object, or nil when no DOM is present (node / SSR).
     `visibilitychange` is a `document` event (it never fires on `window`),
     so the visibility listener attaches/detaches HERE."
     []
     (when (exists? js/document) js/document)))

#?(:cljs
   (defn- remove-frame-listeners!
     "Detach + drop frame-id's installed listeners from the side table (a
     no-op when none is installed). Idempotent. CLJS-only. `focus` / `online`
     detach from `window`; `visibilitychange` detaches from `document` (its
     only event target)."
     [frame-id]
     (when-let [{:keys [focus visibility online]} (get @listener-table frame-id)]
       (try (when-let [window-target (browser-window)]
               (when focus  (.removeEventListener window-target "focus" focus))
               (when online (.removeEventListener window-target "online" online)))
             (when-let [document-target (browser-document)]
               (when visibility
                 (.removeEventListener document-target "visibilitychange" visibility)))
             (catch :default _ nil)))
     (swap! listener-table dissoc frame-id)
     nil))

(def revalidation-triggers
  "The CLOSED enum a frame's `:revalidate-on` config key draws from — the
  host signals that can enter a frame as a revalidation cause.

  `:focus` is ONE setting covering both tab-return spellings (window `focus`
  AND document `visibilitychange`-to-visible); `:reconnect` is window
  `online`. Which host events ENTER the frame is this key's question; which
  resources REFETCH for them is the resource-level stale/owner policy, and
  the two stay separate. Per Spec 016 §Stale and GC scheduling."
  #{:focus :reconnect})

(defn reconcile-listeners!
  "Reconcile `frame-id`'s host revalidation listeners against `triggers` —
  the frame's `:revalidate-on` config value, a subset of
  `revalidation-triggers`. Per Spec 016 §Deferred slices (focus/reconnect
  revalidation as resource events).

  Installs EXACTLY the selected subset, replace-don't-stack:

    - `:focus`     -> `window` `focus`             -> `[:rf.resource/window-focused]`     @ frame-id
                   -> `document` `visibilitychange` -> `[:rf.resource/window-focused]`     @ frame-id
                      (only when the document becomes VISIBLE — the tab-return
                      case TanStack Query revalidates on; `visibilitychange`
                      is a `document` event, its only valid target);
    - `:reconnect` -> `window` `online`            -> `[:rf.resource/network-reconnected]` @ frame-id.

  `nil` / an empty set installs nothing and REMOVES whatever the frame had —
  an explicit `#{}` is a legitimate \"none\", and a re-registration that drops
  the key relinquishes the listeners (the `:url-bound? false` relinquish rule
  routing already uses). Members outside `revalidation-triggers` select no
  listener. The event handlers do the scan + background refetch-by-policy;
  the listener only translates the host event into the frame-targeted
  resource event (the cause). Listeners are recorded in the host side table
  keyed by `frame-id` and cancelled on frame destroy via the single
  `:resources/on-frame-destroyed!` hook.

  Called by the façade's `:resources/on-frame-registered!` lifecycle hook —
  there is no install/remove fn for an app to sequence. Idempotent, so
  repeated renders with identical opts cause no listener churn (hot-reload
  safe). CLJS wires the host listeners; the JVM arm has no DOM under SSR /
  JVM tests and only drops the side-table slot, so this is `:require`-able
  from `.cljc` boot code without a reader conditional at the call site.
  Returns nil."
  [frame-id triggers]
  #?(:cljs
     (let [triggers   (set triggers)
           focus?     (contains? triggers :focus)
           reconnect? (contains? triggers :reconnect)]
       ;; replace, don't stack (hot-reload safe) — and this is also the
       ;; relinquish path when `triggers` selects nothing.
       (remove-frame-listeners! frame-id)
       (when-let [window-target (and (or focus? reconnect?) (browser-window))]
         (let [document-target    (browser-document)
               focus-handler      (when focus?
                                    (fn [_e] (dispatch-revalidation! frame-id window-focused-event)))
               visibility-handler (when focus?
                                    (fn [_e]
                                      ;; `visibilitychange` fires on BOTH the
                                      ;; visible→hidden and hidden→visible
                                      ;; transitions; revalidate ONLY on the
                                      ;; tab-return (document becomes VISIBLE),
                                      ;; never when the tab is hidden.
                                      (when (and document-target
                                                 (= "visible" (.-visibilityState document-target)))
                                        (dispatch-revalidation! frame-id window-focused-event))))
               online-handler     (when reconnect?
                                    (fn [_e] (dispatch-revalidation! frame-id network-reconnected-event)))]
           (when focus-handler
             (.addEventListener window-target "focus" focus-handler))
           ;; `visibilitychange` is a `document` event — attach it to `document`,
           ;; not `window` (the handler reads `document.visibilityState`).
           (when (and visibility-handler document-target)
             (.addEventListener document-target "visibilitychange" visibility-handler))
           (when online-handler
             (.addEventListener window-target "online" online-handler))
           (swap! listener-table assoc frame-id
                  {:focus focus-handler :visibility visibility-handler :online online-handler})))
       nil)
     ;; JVM arm: no DOM under SSR / JVM — nothing to install, and the
     ;; side-table slot (if any) is dropped so the reconcile is total on
     ;; both hosts.
     :clj (do (swap! listener-table dissoc frame-id) nil)))

;; ---- frame teardown (Spec 016 [Runtime-Subsystems] clause 5) --------------

(defn release-frame!
  "Cancel + drop frame-id's window focus / online listeners from the side
  table — `reconcile-listeners!` against no triggers (removal is the empty
  reconcile, not a second code path). Invoked from the single
  `:resources/on-frame-destroyed!` teardown hook (composed in the façade with
  the work-ledger host-handle release, the stale/GC timer release, and the
  generation host-cache release — ONE hook, no second teardown path).
  Idempotent. Per Spec 016 §Stale and GC scheduling (frame destroy cancels
  all the frame's resource host handles) / [Runtime-Subsystems] clause 5.
  Returns nil."
  [frame-id]
  (reconcile-listeners! frame-id nil))

(defn on-frame-destroyed!
  "The focus/reconnect-listener half of the `:resources/on-frame-destroyed!`
  teardown body. The façade composes THIS with the work-ledger host-handle
  release + the stale/GC timer release + the generation host-cache release
  (one composed hook, no second teardown path). Detaches + drops the
  destroyed frame's window focus / online listeners (`release-frame!`). Per
  Spec 016 [Runtime-Subsystems] clause 5. Returns nil."
  [frame-id]
  (release-frame! frame-id))

(defn reset-cache!
  "Tear down + drop EVERY frame's window focus / online listeners (test
  isolation). Published via the resources test-support reset hook so the
  shared CLJS `make-reset-runtime-fixture` clears it per test (host-side
  transient state, NOT cleared by the runtime / frames reset). Returns nil."
  []
  (doseq [frame-id (keys @listener-table)]
    (reconcile-listeners! frame-id nil))
  (reset! listener-table {})
  nil)
