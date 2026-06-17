(ns day8.re-frame2-xray.host-registry
  "Host-app registry reads that survive Xray running in its OWN image-loaded
  frame (EP-0023 §Xray Beside The Target).

  ## Why this exists

  Xray seats in its OWN image-loaded `:rf/xray` frame
  (`image_view_reads/seat-xray-frame!` → `rf/make-frame {:id :rf/xray :images
  [(xray-image)]}`). When a `:rf.xray/*` subscription RECOMPUTES, the framework
  binds the registrar resolution to that frame's sealed image generation for the
  extent of the sub build (`re-frame.registrar/*generation*`, bound by
  `re-frame.live-frame/call-with-frame-resolution` around every subscribe build
  targeting an image-loaded frame). So a bare `(rf/registrations :route)` /
  `(rf/handler-meta :event id)` call INSIDE a sub computation resolves through
  Xray's OWN image resolver — which selects ONLY Xray's `:rf.xray/*` namespaces
  (`xray-image`'s `:include-ns`), NOT the host app's registrations. The
  inspector would then see only its OWN handlers / routes / resources, never the
  HOST app's — the Routing panel renders an empty route table, the palette lists
  only Xray's own handlers, the Resources panel loses the host's resource
  registry.

  This is correct framework behaviour (a frame resolves its own image), and
  exactly the wrong thing for an INSPECTOR: Xray reads the registry of the
  INSPECTED app — the process-global DEFAULT-REALM registrar — not its own
  image's resolver. The legacy realm seating (`reg-frame`) never bound a
  generation, so a bare `(rf/registrations …)` happened to read the global
  registrar; the image-loaded flip makes the generation binding real, so the
  inspector must read the host registrar EXPLICITLY.

  ## The mechanism

  The realm-targeted query form `(rf/registrations {:realm <realm> :kind k})`
  resolves through the realm's OWN registrar atom directly
  (`re-frame.realm/realm-registrar`), BYPASSING any bound `*generation*`. The
  default realm (`nil` ⇒ absence-is-default) is the process-global registrar the
  host app's `reg-event` / `reg-route` / `reg-resource` write into — the
  registry the inspector wants regardless of which image-loaded frame its sub
  build is running in. So every Xray host-registry read that happens INSIDE a
  sub computation routes through here.

  ## Multi-realm note

  Single-realm apps (the overwhelming common case) read the default realm and
  this is byte-identical to the old `(rf/registrations kind)`. A genuinely
  multi-realm host's non-default registrations are surfaced by the Static
  panels' realm-qualified browse (`static.shared.realm`), which already iterates
  `(rf/realm-ids)` via the same generation-bypassing map form; the dynamic
  inspection panels follow the OBSERVED frame, whose realm is the default in
  every shipped substrate, so reading the default realm here matches what those
  panels observe.

  ## Fail-soft

  A core too old for the map-shaped query form (or any throw) degrades to the
  bare `(rf/registrations kind)` / `(rf/handler-meta kind id)` — the
  pre-image-loaded behaviour. An inspector catalogue must never throw on a
  runtime that cannot answer."
  (:require [re-frame.core :as rf]))

(defn registrations
  "`(rf/registrations kind)` for the HOST app — the process-global DEFAULT-REALM
  registrar — read so it survives Xray's sub computation running under its OWN
  image-loaded frame's generation (the bare keyword form would resolve through
  Xray's image resolver instead — see the ns docstring). Returns `{id metadata}`
  (or `{}`). Use this INSIDE any `:rf.xray/*` sub that inspects the host app's
  registry; a view-body read (no generation bound) may use the bare form, but
  this is always safe.

  Fail-soft: a core too old for the realm-targeted map form degrades to the bare
  `(rf/registrations kind)`."
  [kind]
  (try
    (rf/registrations {:realm nil :kind kind})
    (catch :default _
      (try (rf/registrations kind) (catch :default _ {})))))

(defn handler-meta
  "`(rf/handler-meta kind id)` for the HOST app's DEFAULT-REALM registrar,
  generation-bypassing (the sibling of `registrations` for a single `[kind id]`
  lookup). Returns the registration metadata map or `nil`. Use INSIDE any
  `:rf.xray/*` sub that resolves host-app registration meta.

  Fail-soft: degrades to the bare `(rf/handler-meta kind id)`."
  [kind id]
  (try
    (rf/handler-meta {:realm nil :kind kind :id id})
    (catch :default _
      (try (rf/handler-meta kind id) (catch :default _ nil)))))
