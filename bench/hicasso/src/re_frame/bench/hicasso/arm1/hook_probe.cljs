(ns re-frame.bench.hicasso.arm1.hook-probe
  "COUNTING HOOKS AT REACT'S OWN DISPATCHER (rf2-2rtt6.9).

  HD-020(b) makes the ≤2-hook budget a hard line, and a budget a runtime
  reports about itself is not a witness. This counts the calls React
  actually receives.

  ## How

  React routes every hook call through one mutable slot on its shared
  internals — `H`, the current dispatcher, reassigned as React enters and
  leaves each render. [[install!]] replaces that slot with an accessor
  whose getter wraps whatever React last assigned in a counting `Proxy`,
  so a read of `H.useRef` is recorded and then forwarded untouched. React
  never sees a different function; it sees the same one, one property
  read later.

  Two properties make this the right instrument rather than a clever one:

  - **It counts what the component body calls, not what React implements
    with.** `useSyncExternalStore` is ONE dispatcher call; React's
    internal machinery for it does not go back through `H`. So the shell's
    two hooks read as two, and a comparator's `use-subscribe` reads as the
    hooks *it* calls — which is the comparison the budget is about.
  - **It cannot be satisfied by a runtime that reports on itself.** The
    numbers come from React.

  ## When it cannot answer

  The internals slot is a private React implementation detail and its
  name is version-bound. [[install!]] returns `false` when it is not
  found, and every witness that uses it must then record the claim as
  **unwitnessed** — never as passed. A gate nobody has watched fire is
  not evidence."
  (:require ["react" :as react]))

(def ^:private internals-key
  "React 19's client-internals export. Version-bound by construction; the
  witness degrades to `unwitnessed` rather than to green if it moves."
  "__CLIENT_INTERNALS_DO_NOT_USE_OR_WARN_USERS_THEY_CANNOT_UPGRADE")

(defonce ^:private !log (atom []))
(defonce ^:private !counting (atom false))
(defonce ^:private !installed (atom false))

(defn- hook-name?
  "A dispatcher property that is a hook. React reads a few non-hook keys
  off the same object, and counting those would inflate every arm."
  [prop]
  (and (string? prop)
       (> (count prop) 3)
       (= "use" (subs prop 0 3))
       (let [c (subs prop 3 4)] (= c (.toUpperCase c)))))

(defn- counting-proxy [dispatcher]
  (if (nil? dispatcher)
    dispatcher
    (js/Proxy. dispatcher
               #js {"get" (fn [target prop receiver]
                            (when (and @!counting (hook-name? prop))
                              (swap! !log conj prop))
                            (js/Reflect.get target prop receiver))})))

(defn install!
  "Arm the probe. Idempotent; returns true when the dispatcher slot was
  found and wrapped, false when React's internals are not where this
  version expects them."
  []
  (if @!installed
    true
    (if-some [internals (unchecked-get react internals-key)]
      (let [raw (volatile! (unchecked-get internals "H"))]
        (js/Object.defineProperty
          internals "H"
          #js {"configurable" true
               "get" (fn [] (counting-proxy @raw))
               "set" (fn [v] (vreset! raw v))})
        (reset! !installed true)
        true)
      false)))

(defn installed? [] @!installed)

(defn record!
  "Run `thunk` with counting on, and answer the hook names React was asked
  for while it ran, in call order."
  [thunk]
  (reset! !log [])
  (reset! !counting true)
  (try (thunk)
       (finally (reset! !counting false)))
  @!log)
