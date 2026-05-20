(ns day8.re-frame2-causa.test-helpers.host-fixtures.multi-frame
  "Headless host fixture matching `testbeds/multi_frame/core.cljs` —
  three named frames (`:counter/a`, `:counter/b`, `:log`) with a
  cross-frame bridge fx so a single `[::cross-bump]` dispatch in
  `:counter/a` fans out a `[::inc]` into `:counter/b` AND a
  `[::log-append ...]` into `:log`.

  ## Bug class this catches

  rf2-83d4x / rf2-dodq2 — cross-frame dispatch landed on the wrong
  frame because the dispatch form didn't carry `:frame` opts (or
  Causa's `:rf.causa/cascades` projection dropped the frame tag). The
  multi-frame fan-out cascade is the canonical surface for that
  invariant — three trace events under one logical user gesture, each
  tagged with a different frame id.

  ## Frames installed

    :counter/a  → app-db `{:n int}`; handler `:multi-frame.core/inc`.
    :counter/b  → app-db `{:n int}`; same handler resolves per-frame.
    :log        → app-db `{:entries vector}`; handler
                  `:multi-frame.core/log-append`.

  The harness's `with-host-and-causa-frames*` registers `:host-frame`
  (`:rf/default` by default) — this fixture registers the three named
  frames it needs as a side effect of `install!`."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]))

(def frame-a   :counter/a)
(def frame-b   :counter/b)
(def frame-log :log)

(rf/reg-event-db ::counter-init
  (fn [_db _ev] {:n 0}))

(rf/reg-event-db ::log-init
  (fn [_db _ev] {:entries []}))

(rf/reg-event-db ::inc
  (fn [db _ev]
    (update db :n (fnil inc 0))))

(rf/reg-event-db ::log-append
  (fn [db [_ entry]]
    (update db :entries (fnil conj []) entry)))

(rf/reg-fx ::dispatch-to-frame
  (fn [_ctx {:keys [event frame]}]
    ;; The browser testbed uses `rf/dispatch` (async, router queue) so
    ;; the multi-frame fan-out runs across separate router drains.
    ;; In Node CLJS tests there is no event loop driving the router's
    ;; pending queue, so the fan-out events would never be drained.
    ;; `dispatch-sync` here lets the bridge fx fan out synchronously
    ;; — the cross-frame routing invariant (the bug surface this
    ;; fixture exists to probe) is identical either way.
    (rf/dispatch-sync event {:frame frame})))

(rf/reg-event-fx ::cross-bump
  (fn [{:keys [db]} _ev]
    {:db (update db :n (fnil inc 0))
     :fx [[::dispatch-to-frame {:event [::inc] :frame frame-b}]
          [::dispatch-to-frame {:event [::log-append {:from frame-a
                                                      :to #{frame-b frame-log}
                                                      :kind :cross-bump}]
                                :frame frame-log}]]}))

(rf/reg-sub ::n
  (fn [db _] (:n db)))

(rf/reg-sub ::entries
  (fn [db _] (:entries db)))

(defn install!
  "Register all three multi-frame testbed frames + their handlers.
  Idempotent — `reg-frame` is harmless to re-call (registrar replace
  is a warning, not an error)."
  []
  (frame/reg-frame frame-a {})
  (frame/reg-frame frame-b {})
  (frame/reg-frame frame-log {})
  nil)

(defn install-and-init!
  "Install + dispatch initial slot writes into each frame so :n=0 and
  :entries=[] before any test event fires."
  []
  (install!)
  (rf/dispatch-sync [::counter-init] {:frame frame-a})
  (rf/dispatch-sync [::counter-init] {:frame frame-b})
  (rf/dispatch-sync [::log-init]     {:frame frame-log})
  nil)
