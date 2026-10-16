(ns re-frame.routing.navigate
  "`:rf.route/navigate` — the programmatic navigation door.

  Per Spec 012 §Navigation is an event: ONE flat request map,
  `[:rf.route/navigate {request}]`. Address keys `:to` / `:url` / `:params` /
  `:query` / `:fragment`; policy keys `:replace?` / `:scroll` /
  `:bypass-leave?`; the in-place edit key `:query-merge`. A destination
  request (`:to` / `:url`) builds a FRESH address; an in-place request
  (neither) PATCHES the current location. `:rf.route/continue` replays a
  blocked navigation through this door with its one-shot `:bypass-leave?`.

  The handler supplies its inputs to the one navigation pipeline,
  `re-frame.routing.url-change/navigate*`, which runs every stage — the
  structural gate, target resolution, the no-op / fragment-only / full
  classification, both guards and the commit — exactly as it does for the
  link and URL-driven doors.

  Internal namespace; the public facade is `re-frame.routing`. The facade
  owns the `events/reg-event :rf.route/navigate` call so a `:reload`
  re-wires it on a fresh registrar."
  (:require [re-frame.routing.url-change :as rf.routing.url-change]))

(defn- event-arity-error
  "The event-VECTOR shape rule for `:rf.route/navigate` (Spec 012 §Validity
  rules): the event is exactly `[:rf.route/navigate {request}]`. A THIRD
  element — a positional opts map, as in a `[:rf.route/navigate target
  opts]` call — would otherwise be silently dropped while the navigation
  proceeds, so it rejects `:reason :bad-event-arity` through the same
  `:rf.error/navigate-bad-request` channel as the request-map gate. Returns
  nil when the arity is right."
  [event-vec]
  (when (not= 2 (count event-vec))
    {:reason :bad-event-arity :keys []}))

(defn navigate-handler
  "`:rf.route/navigate` event handler. Registered by the facade so a
  `:reload` re-wires it on a fresh registrar.

  Hands `navigate*` the caller's request as it stands — the pipeline's
  always-on structural gate validates the whole roster, so a malformed
  request (or a non-map payload) rejects before any guard runs — together
  with this door's event-arity verdict, cause `:navigate`, `url-driven?
  false` (the address bar moves only after the commit) and the forward
  default scroll `:top`."
  [cofx [_ request :as event-vec]]
  (rf.routing.url-change/navigate*
    (assoc (rf.routing.url-change/door-inputs cofx :rf.route/navigate
                                              'rf.route/navigate-handler)
           :request        request
           :shape-error    (event-arity-error event-vec)
           :cause          :navigate
           :url-driven?    false
           :default-scroll :top)))
