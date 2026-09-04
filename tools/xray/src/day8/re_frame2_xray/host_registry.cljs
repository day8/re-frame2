(ns day8.re-frame2-xray.host-registry
  "Host-app registry reads for Xray's image-loaded frame.

  ## Why this exists

  Xray runs in its own image-loaded `:rf/xray` frame
  (`image_view_reads/seat-xray-frame!` → `rf/make-frame {:id :rf/xray :images
  [(xray-image)]}`). When a `:rf.xray/*` subscription RECOMPUTES, the framework
  binds the registrar resolution to that frame's sealed image generation for the
  extent of the sub build (`re-frame.registrar/*generation*`, bound by
  `re-frame.live-frame/call-with-frame-resolution` around every subscribe build
  targeting an image-loaded frame). So a bare `(rf/registrations :route)` /
  `(rf/handler-meta :event id)` call INSIDE a sub computation resolves through
  Xray's image resolver — which selects only Xray's `:rf.xray/*` namespaces
  (`xray-image`'s `:select-ns :include`), NOT the host app's registrations. The
  inspector would then see only its own handlers, routes, and resources, never the
  host app's — the Routing panel renders an empty route table, the palette lists
  only Xray's own handlers, the Resources panel loses the host's resource
  registry.

  This is correct framework behaviour (a frame resolves its own image), and
  exactly the wrong thing for an INSPECTOR: Xray reads the registry of the
  inspected app — the process-global registrar — not its own image's resolver.

  ## The mechanism

  The process-global registrar atom (`re-frame.registrar/kind->id->metadata`) is
  read directly, bypassing any bound `*generation*`. A tool reads the owning
  `re-frame.registrar` namespace directly, just as it already reads
  `re-frame.frame` / `re-frame.live-frame` (bundle isolation forbids
  `implementation/` requiring from `tools/`, not the reverse). The process-global
  registrar is the table the host app's `reg-event` / `reg-route` / `reg-resource`
  write into — the registry the inspector wants regardless of which image-loaded
  frame its sub build is running in. So every Xray host-registry read that
  happens INSIDE a sub computation routes through here.

  ## Fail-soft

  Any throw in the generation-bypass read degrades to the bare
  `(rf/registrations kind)` / `(rf/handler-meta kind id)` facade reads — the
  pre-image-loaded behaviour. An inspector catalogue must never throw on a
  runtime that cannot answer."
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]))

(defn registrations
  "`(rf/registrations kind)` for the HOST app — the process-global registrar —
  read so it survives Xray's sub computation running under its OWN image-loaded
  frame's generation (the bare keyword form would resolve through Xray's image
  resolver instead — see the ns docstring). Returns `{id metadata}`
  (or `{}`). Use this INSIDE any `:rf.xray/*` sub that inspects the host app's
  registry; a view-body read (no generation bound) may use the bare form, but
  this is always safe.

  Reads the process-global registrar atom
  (`re-frame.registrar/kind->id->metadata`) directly — the generation-bypass.
  Fail-soft: any throw degrades to the bare `(rf/registrations kind)`."
  [kind]
  (try
    (get @rf.registrar/kind->id->metadata kind {})
    (catch :default _
      (try (rf/registrations kind) (catch :default _ {})))))

(defn handler-meta
  "`(rf/handler-meta kind id)` for the HOST app's process-global registrar,
  generation-bypassing (the sibling of `registrations` for a single `[kind id]`
  lookup). Returns the registration metadata map or `nil`. Use INSIDE any
  `:rf.xray/*` sub that resolves host-app registration meta.

  Reads the process-global registrar atom directly. Fail-soft: any throw
  degrades to the bare `(rf/handler-meta kind id)`."
  [kind id]
  (try
    (-> @rf.registrar/kind->id->metadata (get kind) (get id))
    (catch :default _
      (try (rf/handler-meta kind id) (catch :default _ nil)))))
