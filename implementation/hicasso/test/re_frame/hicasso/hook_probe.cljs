(ns re-frame.hicasso.hook-probe
  "COUNTING HOOKS AT REACT'S OWN DISPATCHER (rf2-wjag).

  HD-020(b) makes the ≤2-hook budget a hard architectural line, and **a
  budget a runtime reports about itself is not a witness**. The shell
  declares its hooks in [[re-frame.hicasso.impl.collector/shell-hook-ledger]];
  this counts the calls React actually receives, so the declaration is
  checked rather than believed.

  ## Why it lives under `test/` and not under `src/`

  It installs a property accessor on a private React internals slot.
  That is a legitimate thing for an instrument to do and an illegitimate
  thing for a substrate to ship: nothing a consumer's production bundle
  loads should be able to see, let alone wrap, React's dispatcher. So it
  is a test-tree support namespace, beside `checkpoint-support` and
  `roots-frames-support`, and the package's module graph never reaches
  it.

  ## How

  React routes every hook call through one mutable slot on its shared
  internals — `H`, the current dispatcher, reassigned as React enters and
  leaves each render. [[install!]] replaces that slot with an accessor
  whose getter wraps whatever React last assigned in a counting `Proxy`,
  so a read of `H.useRef` is recorded and then forwarded untouched.
  React never sees a different function; it sees the same one, one
  property read later.

  Two properties make this the right instrument rather than a clever one:

  - **It counts what the component body calls, not what React implements
    with.** `useSyncExternalStore` is ONE dispatcher call; React's
    internal machinery for it does not go back through `H`. So the
    shell's two hooks read as two.
  - **It cannot be satisfied by a runtime that reports on itself.** The
    numbers come from React.

  ## When it cannot answer, it says so — and the caller goes red

  The internals slot is a private React implementation detail and its
  name is version-bound. [[install!]] answers `false` when it is not
  found. The prototype's convention was to record the claim as
  *unwitnessed* at that point; **this package's suite fails instead**,
  and deliberately: React is a pinned dependency here, a slot that moved
  is a fact somebody has to look at, and a gate that quietly stops
  gating on a version bump is the failure mode this repo has been bitten
  by. `unwitnessed` is the honest verdict for a benchmark reporting a
  number; `red` is the honest verdict for a gate."
  (:require ["react" :as react]))

(def ^:private internals-key
  "React 19's client-internals export. Version-bound by construction."
  "__CLIENT_INTERNALS_DO_NOT_USE_OR_WARN_USERS_THEY_CANNOT_UPGRADE")

(defonce ^:private !log (atom []))
(defonce ^:private !counting (atom false))
(defonce ^:private !installed (atom false))

(defn- hook-name?
  "A dispatcher property that is a hook. React reads a few non-hook keys
  off the same object, and counting those would inflate every reading."
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
  "Arm the probe. Idempotent; answers true when the dispatcher slot was
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
               "get"          (fn [] (counting-proxy @raw))
               "set"          (fn [v] (vreset! raw v))})
        (reset! !installed true)
        true)
      false)))

(defn record!
  "Run `thunk` with counting on, and answer the hook names React was asked
  for while it ran, in call order. Counting is off outside the thunk, so
  a render performed by anything else in the process contributes nothing."
  [thunk]
  (reset! !log [])
  (reset! !counting true)
  (try (thunk)
       (finally (reset! !counting false)))
  @!log)
