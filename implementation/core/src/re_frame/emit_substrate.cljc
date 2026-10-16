(ns re-frame.emit-substrate
  "Parameterised always-on emit substrate. Backs `re-frame.event-emit`
  and `re-frame.error-emit` — both ship a
  `register / unregister / clear / fan-out` registry with identical
  structure but distinct record shapes and short-circuit conditions.

  The substrate is always-on: no `goog.DEBUG` gating inside this
  namespace. Listener-REGISTRATION sites SHOULD use `goog.DEBUG=false`
  as a belt-and-braces gate around production-only listeners — see the
  per-substrate ns docstrings.

  Each listener invocation is try/catch wrapped so a buggy listener
  cannot break the cascade or sibling listeners. The runtime does NOT
  recursively emit through any emit-substrate on a listener throw.

  See Spec 009 §Production debugging for normative framing.")

#?(:clj (set! *warn-on-reflection* true))

(defn make-listener-registry
  "Construct an isolated always-on listener registry. Returns a map:

    {:register   (fn [id f] ...)              ;; returns id
     :unregister (fn [id] ...)                ;; returns nil
     :clear      (fn [] ...)                  ;; returns nil
     :fan-out    (fn [record continue?] ...)} ;; returns claimed?

  Pass the listener atom in as `:listeners`, held in a `defonce` (or
  equivalent), so that hot reload of the consuming namespace does not
  silently drop long-lived production listeners. Without it the factory
  makes a fresh atom on every call.

  `fan-out` short-circuits when the registry is empty so the per-emit
  hot-path cost reduces to one deref + a `seq` check. Listener exceptions
  are caught. `continue?` is checked before and after every snapshotted
  listener; exact-owner loss stops later framework-owned sibling callbacks
  while ordinary callers pass `(constantly true)`.

  `fan-out` returns true when at least one listener it delivered to CLAIMED
  the record by returning exactly `true`, else false. Any other return
  value claims nothing, so a listener whose last form happens to be a
  truthy `swap!` or `reset!` does not claim by accident, and a listener
  that throws claims nothing. A consumer that has no use for claims ignores
  the result."
  [{:keys [listeners]}]
  (let [reg (or listeners (atom {}))]
    {:register   (fn register [id f]
                   (swap! reg assoc id f)
                   id)
     :unregister (fn unregister [id]
                   (swap! reg dissoc id)
                   nil)
     :clear      (fn clear []
                   (reset! reg {})
                   nil)
     :fan-out    (fn fan-out
                   ([record] (fan-out record (constantly true)))
                   ([record continue?]
                    (loop [entries  (seq @reg)
                           claimed? false]
                      (if (and entries (continue?))
                        (let [[_id f]  (first entries)
                              claimed? (or (true? (try
                                                    (f record)
                                                    (catch #?(:clj Throwable :cljs :default) _ nil)))
                                           claimed?)]
                          (if (continue?)
                            (recur (next entries) claimed?)
                            claimed?))
                        claimed?))))}))
