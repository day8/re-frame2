(ns re-frame.ssr.request
  "Per-frame request storage and teardown.

  Host adapters populate a request slot before draining a frame. The
  server-only `:rf.server/request` coeffect reads that slot for transient
  decisions. Because the ambient value is not recorded, durable state must use
  a sanitized request-derived value supplied in an event or recordable
  coeffect leaf. The whole request must never enter a causal token or app-db.

  The slots live outside app-db so concurrent frames remain isolated and host
  request data cannot enter the hydration payload. Frame teardown clears the
  request, response and pending-error side channels."
  (:require [re-frame.frame :as frame]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.install :as install]
            [re-frame.ssr.response :as response]))

(defonce
  ^{:doc "Per-frame storage for the active HTTP request. Keys are
  frame-ids; values are the host-supplied request map (Ring shape, or
  whatever the host adapter normalises to). Side-channel — not in
  app-db so the request never rides the hydration payload to the
  client. Host adapters populate via `set-request!` before drain and
  clear via `clear-request!` after response materialisation."}
  request-slots
  (atom {}))

(defn set-request!
  "Populate the per-frame request slot. Called by an SSR host adapter
  once per request, before kicking off the drain.

  The shape of `request` is host-defined: the Ring adapter passes the
  Ring request map (`:request-method`, `:uri`, `:headers`, `:cookies`,
  `:body`, `:query-string`, `:server-name`, `:scheme`, etc.); other
  adapters may pass their native context shape. The cofx surfaces
  whatever the adapter stored — the runtime never inspects the request.

  Returns `frame-id`."
  [frame-id request]
  ;; All SSR side channels use the process-local frame address.
  (swap! request-slots assoc (frame/frame-address frame-id) request)
  frame-id)

(defn get-request
  "Read the active request for `frame-id`. Returns nil when no host
  adapter has populated the slot (e.g. JVM tests that drive the drain
  directly without a host wrapper, or a client-side dispatch that
  injected the cofx — in that case the `:platforms` gate fires the
  `:rf.cofx/skipped-on-platform` trace before this fn is called).

  Public read surface — host adapters and tools may inspect the active
  request via this fn."
  [frame-id]
  (get @request-slots (frame/frame-address frame-id)))

(defn clear-request!
  "Clear the per-frame request slot. Host adapters call this after
  building the wire response (typically as part of per-request frame
  teardown). Safe to call when no slot is populated.

  Returns `frame-id`."
  [frame-id]
  (swap! request-slots dissoc (frame/frame-address frame-id))
  frame-id)

;; ---- per-request frame teardown -------------------------------------------
;;
;; Per Spec 011 §Per-request frame teardown contract. The SSR runtime owns
;; three side-channel `defonce` atoms keyed by frame-id —
;; `pending-error-traces` (per-frame buffer of captured error trace events,
;; in `re-frame.ssr.error-listener`), `request-slots` (per-frame HTTP-request
;; map, here), and `response-slots` (per-frame HTTP response accumulator,
;; in `re-frame.ssr.response`). All three
;; live outside app-db (see the rationale comments above each defonce) and
;; so are NOT cleared by the frame's app-db / sub-cache teardown in
;; `frame/destroy-frame!`.
;;
;; This fn is the cleanup hook. Wired into `frame/destroy-frame!` via the
;; `:ssr/on-frame-destroyed` late-bind key — `core` calls it from its
;; ordered teardown step list when the SSR artefact is on the classpath;
;; the hook resolves to nil and the destroy proceeds without it when the
;; SSR artefact is absent. Idempotent: tolerates a frame-id with no slot
;; in any of the three atoms.

(defn on-frame-destroyed!
  "Drop
  the per-frame entries in `pending-error-traces`, `request-slots`, and
  `response-slots` for `frame-id`. Called from `frame/destroy-frame!`
  via the `:ssr/on-frame-destroyed` late-bind hook. Idempotent — a
  second call against the same frame-id sees the atoms already cleared
  and does nothing.

  Reading a head is a pure read (`render-head` / `active-head` RETURN
  the model), so the head namespace keeps no per-frame bookkeeping and
  there is nothing here to release on its behalf."
  [frame-id]
  (error-listener/clear-pending-error-traces! frame-id)
  (clear-request! frame-id)
  (response/clear-response! frame-id)
  ;; S5 — release this frame's hydration-payload install claim. Payload ids
  ;; ARE frame ids (004C §6), so a destroyed frame's claim must go with it:
  ;; a frame later re-created under the same id would otherwise meet a
  ;; phantom `:rf.error/frame-payload-conflict` raised by a lifetime that
  ;; no longer exists.
  (install/release-payload! frame-id)
  nil)

(defn request-cofx
  "Value-returning ambient supplier for `:rf.server/request`.
  Reads the per-frame request slot for the frame currently being dispatched
  (`frame/*current-frame*`, bound by the router during processing). Returns
  nil when no host adapter has populated the slot (e.g. JVM tests that drive
  the drain without a host wrapper, or a client-side dispatch — in which case
  the `:platforms #{:server}` gate fires `:rf.cofx/skipped-on-platform` and
  the supplier never runs).

  The request is HOST-CONTROLLED INPUT (a read of the active host wire-shape),
  delivered to handlers that declare `:rf.cofx/requires [:rf.server/request]`
  and never recorded — replay re-runs it (and reads nil after the per-request
  frame's slot is cleared). Because the read is unrecorded, this AMBIENT cofx
  is for non-durable request reads only; a durable request-derived fact uses
  the recordable boundary pattern (event payload or a provided recordable
  `:rf.cofx` leaf the host stamps after sanitizing the request).
  Tests / conformance harnesses that drive the drain without a host adapter
  `set-request!` the slot for the target frame first (the visible seam),
  before dispatch."
  []
  (get-request frame/*current-frame*))
