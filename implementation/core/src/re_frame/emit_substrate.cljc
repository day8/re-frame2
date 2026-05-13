(ns re-frame.emit-substrate
  "Parameterised always-on emit substrate. Backs `re-frame.event-emit`
  (rf2-rirbq) and `re-frame.error-emit` (rf2-bacs4 / rf2-hqbeh) — both
  ship a `register / unregister / clear / fan-out` registry with
  identical structure but distinct record shapes and short-circuit
  conditions. Factored per rf2-z5ffh (audit finding EE1/A4 of
  rf2-spr6q).

  The substrate is always-on: no `goog.DEBUG` gating inside this
  namespace. Listener-REGISTRATION sites SHOULD use `goog.DEBUG=false`
  as a belt-and-braces gate around production-only listeners — see the
  per-substrate ns docstrings.

  Each listener invocation is try/catch wrapped so a buggy listener
  cannot break the cascade or sibling listeners. The runtime does NOT
  recursively emit through any emit-substrate on a listener throw.

  See Spec 009 §Production debugging for normative framing."
  (:require))

(defn make-listener-registry
  "Construct an isolated always-on listener registry. Returns a map:

    {:listeners <atom of id->fn>
     :register  (fn [id f] ...)        ;; returns id
     :unregister (fn [id] ...)         ;; returns nil
     :clear     (fn [] ...)            ;; returns nil
     :fan-out   (fn [record] ...)}     ;; returns nil

  Caller MUST hold the returned `:listeners` atom in a `defonce` (or
  equivalent) so that hot reload of the consuming namespace does not
  silently drop long-lived production listeners. The `make-` factory
  itself produces a fresh atom on every call — pass an externally-held
  `defonce` atom via `:listeners` to bind the surface to it.

  `fan-out` short-circuits to nil when the registry is empty so the
  per-emit hot-path cost reduces to one deref + an `empty?` check.
  Listener exceptions are caught — the cascade does NOT abort and
  sibling listeners still run."
  [{:keys [listeners]}]
  (let [reg (or listeners (atom {}))]
    {:listeners  reg
     :register   (fn register [id f]
                   (swap! reg assoc id f)
                   id)
     :unregister (fn unregister [id]
                   (swap! reg dissoc id)
                   nil)
     :clear      (fn clear []
                   (reset! reg {})
                   nil)
     :fan-out    (fn fan-out [record]
                   (let [snap @reg]
                     (when (seq snap)
                       (doseq [[_id f] snap]
                         (try
                           (f record)
                           (catch #?(:clj Throwable :cljs :default) _ nil)))))
                   nil)}))
