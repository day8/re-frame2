(ns reagent2.ratom
  "Reactive primitives for the day8/reagent-slim artefact.

  Bounded surface per IMPL-SPEC §2.3 (rf2-6hyy Stage 4-A):

    Types:      RAtom, Reaction
    Protocols:  IReactiveAtom, IDisposable
    Functions:  atom, make-reaction, dispose!, add-on-dispose!,
                reactive?, flush!
    Macros:     reaction, run!  (in reagent2/ratom.clj)

  Symbols deliberately NOT shipped (audit-confirmed zero usage across
  re-com / 10x / Dash8 / rf8 — see IMPL-SPEC §2.3 + §2.3a):

    - track, track!, cursor, wrap, make-wrapper
    - Track, RCursor, Wrapper types
    - IRunnable protocol
    - run-in-reaction, with-let-values (impl-internal in stock; not
      part of the rewrite's surface)

  Apps that genuinely need a dropped surface stay on day8/reagent-classic
  (the bridge); the rewrite's commitment is to ship only the surfaces
  the audited codebases actually exercise.

  Design notes (per IMPL-SPEC §3 + Stage 2 §4 efficiency analysis):

    - RAtom and Reaction shapes match stock Reagent byte-for-byte. The
      reactive kernel is one of stock Reagent's well-designed pieces —
      the wins live elsewhere (bundle pruning, microtask scheduler,
      narrowed convert-prop-value).

    - IReactiveAtom is the canonical `ratom?` test. Both RAtom and
      Reaction reify it. `re-frame.interop/ratom?` does
      `(satisfies? IReactiveAtom x)` — protocol-based, not class-based.

    - IDisposable is the cross-substrate cache-wiring contract. UIx
      and Helix adapters reify this same protocol on their derived-value
      objects so `re-frame.interop/add-on-dispose!` dispatches uniformly
      via protocol — no `instance?` branch in core. The shape of
      `dispose!` and `add-on-dispose!` MUST NOT change.

  Stage-4-A scope: pure reactive primitives, no DOM/component coupling.
  Stage 4-B will add the microtask render scheduler and wire
  `(set! batch/ratom-flush flush!)` so the render layer can drain the
  reactive queue on each commit boundary."
  (:refer-clojure :exclude [atom run!])
  (:require-macros [reagent2.ratom])
  (:require [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; Reactive context
;; ---------------------------------------------------------------------------

(declare flush!)

(declare ^:dynamic *ratom-context*)

(defn ^boolean reactive?
  "True if invoked inside a reactive context — i.e. inside the body of a
  Reaction or another deref-capturing scope. Reads the dynamic
  *ratom-context*. Used by render code that wants to detect whether a
  deref will subscribe (reactive) vs just snapshot (non-reactive)."
  []
  (some? *ratom-context*))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- ^number arr-len [x]
  (if (nil? x) 0 (alength x)))

(defn- ^boolean arr-eq [x y]
  (let [len (arr-len x)]
    (and (== len (arr-len y))
         (loop [i 0]
           (or (== i len)
               (if (identical? (aget x i) (aget y i))
                 (recur (inc i))
                 false))))))

(defn- in-context
  "Run f with *ratom-context* bound to obj. Any deref inside f registers
  the deref'd ratom on obj (via notify-deref-watcher!), which is how a
  Reaction discovers its dependencies."
  [obj f]
  (binding [*ratom-context* obj]
    (f)))

(defn- deref-capture
  "Run f as the body of Reaction r, capturing the set of ratoms it derefs.
  After f returns, diff the captured set against r's existing watching
  set; subscribe r to newly-watched ratoms and unsubscribe from
  no-longer-watched ratoms. Clears the dirty? flag on r."
  [f ^clj r]
  (set! (.-captured r) nil)
  (let [res (in-context r f)
        c   (.-captured r)]
    (set! (.-dirty? r) false)
    ;; Optimise common case where derefs occur in same order
    (when-not (arr-eq c (.-watching r))
      (._update-watching r c))
    res))

(defn- notify-deref-watcher!
  "Add `derefed` to the captured array on the in-flight reactive context.
  No-op outside a reactive context."
  [derefed]
  (when-some [^clj r *ratom-context*]
    (let [c (.-captured r)]
      (if (nil? c)
        (set! (.-captured r) (array derefed))
        (.push c derefed)))))

(defn- add-w [^clj this key f]
  (set! (.-watches this) (assoc (.-watches this) key f))
  (set! (.-watchesArr this) nil))

(defn- remove-w [^clj this key]
  (set! (.-watches this) (dissoc (.-watches this) key))
  (set! (.-watchesArr this) nil))

(defn- notify-w [^clj this old new]
  (let [w   (.-watchesArr this)
        a   (if (nil? w)
              (->> (.-watches this)
                   (reduce-kv #(doto %1 (.push %2) (.push %3)) #js [])
                   (set! (.-watchesArr this)))
              w)
        len (alength a)]
    (loop [i 0]
      (when (< i len)
        (let [k (aget a i)
              f (aget a (inc i))]
          (f k this old new))
        (recur (+ 2 i))))))

(defn- pr-atom [a writer opts s v]
  (-write writer (str "#object[reagent2.ratom." s " "))
  ;; `pr-writer` is marked private in cljs.core but is the documented
  ;; entrypoint for nested `IPrintWithWriter` dispatch (every reagent2
  ;; ratom's `-pr-writer` delegates to it for the value's recursive
  ;; print). The "private" tag is a CLJS-packaging artefact, not an
  ;; API contract — silence kondo's private-call check at the one
  ;; call site rather than blanket-excluding the linter.
  #_:clj-kondo/ignore
  (pr-writer (binding [*ratom-context* nil] v) writer opts)
  (-write writer "]"))

;; ---------------------------------------------------------------------------
;; Reaction-flush queue
;;
;; Reactions whose dependencies have changed get pushed into rea-queue.
;; flush! drains the queue, recomputing each Reaction. The render
;; scheduler (Stage 4-B, reagent2.impl.batching) hooks into this via
;; the assignable `batch/ratom-flush` slot so the render-commit
;; boundary drains pending reactive work.
;;
;; Stage 4-A ships rea-queue + flush! standalone; the render-side
;; integration lands with 4-B.
;; ---------------------------------------------------------------------------

(defonce ^:private rea-queue nil)

(defonce ^{:doc "Hook the render scheduler installs to schedule a microtask
                drain of rea-queue. Defaults to nil (synchronous flush
                only); Stage 4-B's reagent2.impl.batching sets this so a
                Reaction whose dependency changed schedules a drain
                automatically."}
  rea-schedule (clojure.core/atom nil))

(defn- rea-enqueue [r]
  (when (nil? rea-queue)
    (set! rea-queue #js [])
    (when-let [s @rea-schedule]
      (s)))
  (.push rea-queue r))

;; ---------------------------------------------------------------------------
;; IReactiveAtom — marker protocol
;; ---------------------------------------------------------------------------

(defprotocol IReactiveAtom
  "Marker protocol for reactive atoms — RAtom and Reaction reify this.
  `re-frame.interop/ratom?` tests `(satisfies? IReactiveAtom x)` so any
  reify that wants to be ratom-shaped to re-frame2 must implement this
  protocol.")

;; ---------------------------------------------------------------------------
;; RAtom — reactive atom
;; ---------------------------------------------------------------------------

(deftype RAtom [^:mutable state meta validator
                ^:mutable watches ^:mutable watchesArr]
  IAtom
  IReactiveAtom

  IEquiv
  (-equiv [o other] (identical? o other))

  IDeref
  (-deref [this]
    (notify-deref-watcher! this)
    state)

  IReset
  (-reset! [a new-value]
    (when-not (nil? validator)
      (assert (validator new-value) "Validator rejected reference state"))
    (let [old-value state]
      (set! state new-value)
      (when-not (nil? watches)
        (notify-w a old-value new-value))
      new-value))

  ISwap
  (-swap! [a f]          (-reset! a (f state)))
  (-swap! [a f x]        (-reset! a (f state x)))
  (-swap! [a f x y]      (-reset! a (f state x y)))
  (-swap! [a f x y more] (-reset! a (apply f state x y more)))

  IWithMeta
  (-with-meta [_ new-meta] (RAtom. state new-meta validator watches watchesArr))

  IMeta
  (-meta [_] meta)

  IPrintWithWriter
  (-pr-writer [a w opts] (pr-atom a w opts "RAtom" {:val (-deref a)}))

  IWatchable
  (-notify-watches [this old new] (notify-w this old new))
  (-add-watch [this key f]        (add-w this key f))
  (-remove-watch [this key]       (remove-w this key))

  IHash
  (-hash [this] (goog/getUid this)))

(defn atom
  "Construct a reactive atom (RAtom).

  Like clojure.core/atom — supports IDeref, IReset, ISwap, IWatchable,
  IMeta — except deref'ing inside a Reaction subscribes the Reaction
  to changes. This is the deref-time subscription mechanism that wires
  user state into the reactive graph.

  Two-arity overload accepts :meta and :validator like clojure.core/atom.
  (Stock Reagent's :equal? is NOT shipped — audit-confirmed zero usage.)"
  ([x] (->RAtom x nil nil nil nil))
  ([x & {:keys [meta validator]}]
   (->RAtom x meta validator nil nil)))

;; ---------------------------------------------------------------------------
;; IDisposable — cross-substrate cache-wiring contract
;; ---------------------------------------------------------------------------

(defprotocol IDisposable
  "Cross-substrate disposal protocol. The cache-wiring contract per
  IMPL-SPEC §3.4 + Spec 006 §subscription-cache: re-frame's per-slot
  evict logic calls `re-frame.interop/add-on-dispose!` which dispatches
  via this protocol. UIx and Helix derived-value reifies implement the
  same shape, so a single protocol-dispatch handles every substrate
  with no instance? branch in core.

  Signatures MUST NOT change."
  (dispose! [this]
    "Tear down. Removes watches, runs on-dispose hooks, marks the
     reaction dirty so future derefs recompute (or throw).")
  (add-on-dispose! [this f]
    "Register a 1-arg callback to fire when this Reaction is disposed.
     Multiple callbacks accumulate; they run in registration order."))

(defn- handle-reaction-change [^clj this _sender old new]
  (._handle-change this _sender old new))

;; ---------------------------------------------------------------------------
;; Reaction — derived value with equality memoisation
;;
;; Layout per IMPL-SPEC §3.2: the same nine-field shape stock Reagent
;; uses. Stage 2 §4 ("Keep as-is") concluded the kernel is correct.
;; ---------------------------------------------------------------------------

(deftype Reaction [^:mutable f ^:mutable state ^:mutable ^boolean dirty?
                   ^:mutable watching ^:mutable watches ^:mutable watchesArr
                   ^:mutable auto-run ^:mutable on-set ^:mutable on-dispose
                   ^:mutable on-dispose-arr ^:mutable caught]
  IAtom
  IReactiveAtom

  IWatchable
  (-notify-watches [this old new] (notify-w this old new))
  (-add-watch [this key f]        (add-w this key f))
  (-remove-watch [this key]
    (let [was-empty (empty? watches)]
      (remove-w this key)
      (when (and (not was-empty)
                 (empty? watches)
                 (nil? auto-run))
        (dispose! this))))

  IReset
  (-reset! [a newval]
    (assert (fn? on-set) "Reaction is read-only; on-set is not allowed")
    (let [oldval state]
      (set! state newval)
      (on-set oldval newval)
      (notify-w a oldval newval)
      newval))

  ISwap
  (-swap! [a f]          (-reset! a (f (._peek-at a))))
  (-swap! [a f x]        (-reset! a (f (._peek-at a) x)))
  (-swap! [a f x y]      (-reset! a (f (._peek-at a) x y)))
  (-swap! [a f x y more] (-reset! a (apply f (._peek-at a) x y more)))

  Object
  (_peek-at [this]
    (binding [*ratom-context* nil]
      (-deref this)))

  (_handle-change [this _sender oldval newval]
    (when-not (or (identical? oldval newval) dirty?)
      (if (nil? auto-run)
        (do
          (set! dirty? true)
          (rea-enqueue this))
        (if (true? auto-run)
          (._run this false)
          (auto-run this)))))

  (_update-watching [this derefed]
    (let [new (set derefed)
          old (set watching)]
      (set! watching derefed)
      (doseq [w (set/difference new old)]
        (-add-watch w this handle-reaction-change))
      (doseq [w (set/difference old new)]
        (-remove-watch w this))))

  (_queued-run [this]
    (when (and dirty? (some? watching))
      (._run this true)))

  (_try-capture [this f]
    (try
      (set! caught nil)
      (deref-capture f this)
      (catch :default e
        (set! state e)
        (set! caught e)
        (set! dirty? false))))

  (_run [this check]
    (let [oldstate state
          res      (if check
                     (._try-capture this f)
                     (deref-capture f this))]
      (set! state res)
      ;; Equality memoisation per IMPL-SPEC §3.2: use = (not identical?)
      ;; because Reaction bodies produce new data structures every run.
      ;; Skip notify-watches when the recomputed value is = the old one.
      (when-not (or (nil? watches)
                    (= oldstate res))
        (notify-w this oldstate res))
      res))

  (_set-opts [this {:keys [auto-run on-set on-dispose]}]
    (when (some? auto-run)
      (set! (.-auto-run this) auto-run))
    (when (some? on-set)
      (set! (.-on-set this) on-set))
    (when (some? on-dispose)
      (set! (.-on-dispose this) on-dispose)))

  IDeref
  (-deref [this]
    (when-some [e caught]
      (throw e))
    (let [non-reactive (nil? *ratom-context*)]
      (when non-reactive
        (flush!))
      (if (and non-reactive (nil? auto-run))
        ;; Outside any reactive context AND no auto-run subscribers:
        ;; pure on-demand recompute. No watcher-graph wiring; just run f
        ;; and notify any explicit watches if the result changed.
        (when dirty?
          (let [oldstate state]
            (set! state (f))
            (when-not (or (nil? watches) (= oldstate state))
              (notify-w this oldstate state))))
        ;; Inside a reactive context (or auto-run set): subscribe the
        ;; outer context to this Reaction; recompute if dirty.
        (do
          (notify-deref-watcher! this)
          (when dirty?
            (._run this false)))))
    state)

  IDisposable
  (dispose! [this]
    (let [s  state
          wg watching]
      (set! watching nil)
      (set! state nil)
      (set! auto-run nil)
      (set! dirty? true)
      (doseq [w (set wg)]
        (-remove-watch w this))
      (when (some? on-dispose)
        (on-dispose s))
      (when-some [a on-dispose-arr]
        (dotimes [i (alength a)]
          ((aget a i) this)))))

  (add-on-dispose! [this f]
    (if-some [a on-dispose-arr]
      (.push a f)
      (set! (.-on-dispose-arr this) (array f))))

  IEquiv
  (-equiv [o other] (identical? o other))

  IPrintWithWriter
  (-pr-writer [a w opts] (pr-atom a w opts "Reaction" {:val (-deref a)}))

  IHash
  (-hash [this] (goog/getUid this)))

(defn flush!
  "Drain rea-queue: recompute every queued Reaction whose dependencies
  changed, in enqueue order. Notifies watchers per the equality-memo
  contract. Loops until the queue is empty (a recompute may itself
  enqueue downstream Reactions).

  Stage 4-A: callable directly. Stage 4-B's reagent2.impl.batching will
  install this as `batch/ratom-flush` so the render-commit boundary
  drains automatically."
  []
  (loop []
    (let [q rea-queue]
      (when-not (nil? q)
        (set! rea-queue nil)
        (dotimes [i (alength q)]
          (let [^Reaction r (aget q i)]
            (._queued-run r)))
        (recur)))))

(defn make-reaction
  "Construct a Reaction wrapping body fn `f`.

  Optional kwargs:

    :auto-run    nil (default), true (synchronous on-change), or a 1-arg
                 fn called with the reaction on each change. With nil,
                 the reaction enqueues itself for the next flush! when
                 a dependency changes.
    :on-set      fn called on -reset!/-swap! with [old new] before
                 watches fire. Only meaningful if you intend to call
                 reset! on the Reaction.
    :on-dispose  fn called with the last computed value when dispose!
                 runs.

  IMPL-SPEC §3.2: the kernel preserves stock Reagent's shape because
  the Stage 2 efficiency analysis confirmed it's correct as-is."
  [f & {:keys [auto-run on-set on-dispose]}]
  (let [r (->Reaction f nil true nil nil nil nil nil nil nil nil)]
    (._set-opts r {:auto-run    auto-run
                   :on-set      on-set
                   :on-dispose  on-dispose})
    r))
