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

  The host browser surfaces a `focus` / `visibilitychange` (tab return) and
  an `online` (network reconnect) event on `window`. This namespace wires
  those to a resource event dispatched at a specific FRAME:

    focus / visibilitychange-to-visible  -> [:rf.resource/window-focused]
    online                               -> [:rf.resource/network-reconnected]

  The event handlers (`re-frame.resources.events`) do the active-stale scan
  + background refetch-by-policy. The listener carries NO policy — it only
  translates a host event into a frame-targeted resource event (the cause).

  ## Per-frame registration + frame-destroy teardown (COMPOSE, don't duplicate)

  Listeners are registered PER FRAME (an app calls
  `install-revalidation-listeners!` for each frame that wants
  focus/reconnect revalidation, naming the frame the events target). The
  installed host handles live in this module-level side table keyed by
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

  Idempotent: re-installing for a frame replaces (does not stack) its
  listeners — hot-reload safe (mirrors routing's
  `install-history-listener!`)."
  (:require [re-frame.late-bind :as late-bind]))

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
  (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
    (dispatch! [event-id] {:frame frame-id :source :revalidate})))

#?(:cljs
   (defn- window-obj
     "The `js/window` object, or nil when no DOM is present (node / SSR)."
     []
     (when (exists? js/window) js/window)))

#?(:cljs
   (defn- remove-frame-listeners!
     "Detach + drop frame-id's installed window listeners from the side table
     (a no-op when none is installed). Idempotent. CLJS-only."
     [frame-id]
     (when-let [w (window-obj)]
       (when-let [{:keys [focus visibility online]} (get @listener-table frame-id)]
         (try (when focus      (.removeEventListener w "focus" focus))
              (when visibility  (.removeEventListener w "visibilitychange" visibility))
              (when online      (.removeEventListener w "online" online))
              (catch :default _ nil))))
     (swap! listener-table dissoc frame-id)
     nil))

(defn install-revalidation-listeners!
  "Install host window focus / network-reconnect listeners that drive
  active-stale revalidation for `frame-id`. Per Spec 016 §Deferred slices
  (focus/reconnect revalidation as resource events).

  Wires three `window` listeners:

    - `focus`            -> `[:rf.resource/window-focused]`  @ frame-id
    - `visibilitychange` -> `[:rf.resource/window-focused]`  @ frame-id (only
                            when the document becomes VISIBLE — the tab-return
                            case TanStack Query revalidates on);
    - `online`           -> `[:rf.resource/network-reconnected]` @ frame-id.

  The event handlers do the scan + background refetch-by-policy; the listener
  only translates the host event into the frame-targeted resource event (the
  cause). Listeners are recorded in the host side table keyed by `frame-id`
  and cancelled on frame destroy via the single
  `:resources/on-frame-destroyed!` hook.

  Idempotent: re-installing for a frame REPLACES its prior listeners (does not
  stack) — hot-reload safe (mirrors routing's `install-history-listener!`).
  CLJS-only; the JVM arm is a no-op (no DOM under SSR / JVM tests), so this is
  `:require`-able from `.cljc` boot code without a reader conditional at the
  call site. Returns nil."
  [frame-id]
  #?(:cljs
     (when-let [w (window-obj)]
       ;; replace, don't stack (hot-reload safe)
       (remove-frame-listeners! frame-id)
       (let [focus-handler      (fn [_e] (dispatch-revalidation! frame-id window-focused-event))
             visibility-handler (fn [_e]
                                  (when (and (exists? js/document)
                                             (= "visible" (.-visibilityState js/document)))
                                    (dispatch-revalidation! frame-id window-focused-event)))
             online-handler     (fn [_e] (dispatch-revalidation! frame-id network-reconnected-event))]
         (.addEventListener w "focus" focus-handler)
         (.addEventListener w "visibilitychange" visibility-handler)
         (.addEventListener w "online" online-handler)
         (swap! listener-table assoc frame-id
                {:focus focus-handler :visibility visibility-handler :online online-handler})
         nil))
     ;; JVM arm: no DOM under SSR / JVM — no listeners to install (no-op nil).
     :clj nil))

(defn remove-revalidation-listeners!
  "Tear down the window focus / online listeners installed for `frame-id` by
  `install-revalidation-listeners!`. No-op when none is installed (and on the
  JVM). Useful for test isolation and single-page hosts that rotate which
  frame owns revalidation. CLJS detaches the host listeners; both arms drop
  the side-table slot. Returns nil."
  [frame-id]
  #?(:cljs (remove-frame-listeners! frame-id)
     :clj  (do (swap! listener-table dissoc frame-id) nil))
  nil)

;; ---- frame teardown (Spec 016 [Runtime-Subsystems] clause 5) --------------

(defn release-frame!
  "Cancel + drop frame-id's window focus / online listeners from the side
  table. Invoked from the single `:resources/on-frame-destroyed!` teardown
  hook (composed in the façade with the work-ledger host-handle release, the
  stale/GC timer release, and the generation host-cache release — ONE hook,
  no second teardown path). Idempotent. Per Spec 016 §Stale and GC scheduling
  (frame destroy cancels all the frame's resource host handles) /
  [Runtime-Subsystems] clause 5. Returns nil."
  [frame-id]
  (remove-revalidation-listeners! frame-id))

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
    (remove-revalidation-listeners! frame-id))
  (reset! listener-table {})
  nil)
