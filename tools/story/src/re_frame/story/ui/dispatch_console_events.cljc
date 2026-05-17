(ns re-frame.story.ui.dispatch-console-events
  "Event-id query surface for the Dispatch Console panel's autocomplete
  (rf2-q9kv5).

  Story does not own the registered events — the framework registrar
  (`re-frame.registrar`) does — so this namespace is a thin query
  facade over the registrar's `:event` kind plus a tiny set of helpers
  exposed for tests / programmatic callers that need to seed a
  registrar without driving real event handlers.

  ## Why not a `reg-sub`?

  The dispatch console's autocomplete is read once per keystroke; the
  registry is process-global mutable state. A sub would require a
  reactive layer (a wrapper atom around the registrar that bumps on
  every `reg-event-*`) and would deliver no useful caching since the
  filter recomputes on every prefix change. A plain query fn is the
  honest shape — and it stays pure-data so tests can stub
  `registered-event-ids` via `with-redefs`.

  ## Public surface

  - `(registered-event-ids)`             — set of all registered event-id
                                            keywords across the framework.
  - `(registered-event-ids registry-snapshot-map)` — pure 1-arity that
                                            takes the registrations map
                                            directly (used by tests +
                                            JVM corpus where the
                                            framework registrar may
                                            not be populated).

  ## Elision

  The CLJS arm requires the framework registrar; the JVM arm takes
  whatever snapshot the caller passes in. Production CLJS builds skip
  the panel entirely (per the shell's `config/enabled?` gate); the
  registrar query is never reached."
  #?(:cljs (:require [re-frame.registrar :as registrar])))

(defn registered-event-ids
  "Return the set of registered event-id keywords. Two arities:

  - 0-arity (CLJS only): query the live framework registrar.
  - 1-arity: take a `{event-id meta}` snapshot map and return its
    key-set. Used by tests + JVM coverage."
  ([]
   #?(:cljs (set (keys (registrar/registrations :event)))
      :clj  #{}))
  ([registry-snapshot]
   (set (keys (or registry-snapshot {})))))
