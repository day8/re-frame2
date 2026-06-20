(ns re-frame.story.ui.dispatch-console-events
  "Event-id query surface for the Dispatch Console panel's autocomplete.

  Story does not own the registered events — the framework registrar
  (`re-frame.registrar`) does — so this namespace is a thin query
  facade over the registrar's `:event` kind plus a tiny set of helpers
  exposed for tests / programmatic callers that need to seed a
  registrar without driving real event handlers.

  ## Why not a `reg-sub`?

  The dispatch console's autocomplete is read once per keystroke; the
  registry is process-global mutable state. A sub would require a
  reactive layer (a wrapper atom around the registrar that bumps on
  every `reg-event`) and would deliver no useful caching since the
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
  - `(registered-event-meta)`            — the full `{event-id meta}`
                                            registrations map (CLJS,
                                            live registrar). The metadata
                                            carries the authored
                                            `:rf.cofx/requires` declaration
                                            (EP-0017) the console surfaces.
  - `(cofx-requires-for meta)` /
    `(cofx-requires-for registry-snapshot event-id)` — the normalised
                                            vector of required coeffect ids
                                            an event declares via
                                            `:rf.cofx/requires`. Pure
                                            data → data; the autocomplete /
                                            requirement-hint reads it.

  ## Why expose the metadata, not just ids

  EP-0017 makes a handler's `:rf.cofx/requires` declaration part of its
  dispatch contract: a provided recordable fact must be supplied under
  the flat `:rf.cofx` dispatch opt. The console can only HELP the user
  satisfy that contract if it can SEE the declaration — so the query
  surface exposes the registration metadata (not just the id set the
  autocomplete needs).

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

(defn registered-event-meta
  "Return the full `{event-id meta}` registrations map for the `:event`
  kind. CLJS-only — queries the live framework registrar; the JVM arm
  returns `{}` (tests pass a snapshot directly to `cofx-requires-for`).

  The metadata maps carry each event's authored `:rf.cofx/requires`
  declaration (EP-0017), the slot the Dispatch Console surfaces so the
  user can satisfy a provided-recordable-cofx contract. Read once per
  selection / keystroke — same posture as `registered-event-ids` (a
  plain query fn, not a `reg-sub`)."
  []
  #?(:cljs (registrar/registrations :event)
     :clj  {}))

(defn- normalise-requires
  "Normalise an authored `:rf.cofx/requires` declaration into a vector of
  coeffect id keywords. EP-0017 allows each entry to be a bare id keyword
  OR an `[id arg]` pair (a parameterized supplier); we surface the ID in
  both cases so the console shows WHICH facts the handler consumes. A nil
  / empty / non-vector declaration yields `[]`. Pure data → data — mirrors
  `re-frame.cofx/parse-requires`'s id projection without re-validating
  (the registrar already validated at registration time)."
  [requires]
  (if-not (vector? requires)
    []
    (into []
          (keep (fn [entry]
                  (cond
                    (keyword? entry) entry
                    (and (vector? entry) (keyword? (first entry))) (first entry)
                    :else nil)))
          requires)))

(defn cofx-requires-for
  "Return the vector of coeffect-id keywords an event declares via
  `:rf.cofx/requires` (EP-0017). Two arities:

  - 1-arity: take a single event's `meta` map directly and read its
    `:rf.cofx/requires`. Pure data → data.
  - 2-arity: take a `{event-id meta}` snapshot map + an `event-id`, look
    the event up, and read its requirement. Pure data → data; used by the
    panel (passing the live `registered-event-meta` snapshot) and tests.

  An unknown id, an event with no declaration, or a nil snapshot yields
  `[]`. Parameterized entries (`[id arg]`) surface their id."
  ([meta]
   (normalise-requires (:rf.cofx/requires meta)))
  ([registry-snapshot event-id]
   (normalise-requires (get-in (or registry-snapshot {}) [event-id :rf.cofx/requires]))))
