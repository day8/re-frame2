(ns re-frame.bench.read-attribution-cljs
  "rf2-x0fe2 — the READ path, measured on the host re-frame2 SHIPS TO.

  Every allocation figure on this surface — rf2-21pck, rf2-mvqwe (PR
  #7151), rf2-j8ls2 / rf2-ncjyt (PR #7154) — is a JVM figure, taken with
  `com.sun.management.ThreadMXBean/getThreadAllocatedBytes`. That was
  always stated. It MATTERS because the JVM decomposition's largest single
  term is one that does not exist in ClojureScript at all:

    the dynamic `binding` of `registrar/*generation*` inside
    `call-with-frame-resolution`      ~760 B/call JVM      ~0 in CLJS

  JVM `binding` is `push-thread-bindings`: a hash-map for the pair, a fresh
  `TBox` assoc'd into the thread's binding map (itself a path copy), and a
  `Frame`. CLJS has no threads and no binding stack — `binding` expands to
  `let` + `set!` + `try`/`finally` restore. So the JVM ranking of
  `subscribe`'s cache-HIT cost may be ranking work a browser app does not
  pay, and three beads were prioritised off that ranking.

  This namespace is `read_attribution.clj`'s CLJS counterpart. It measures
  the SAME ladder, arm for arm, so the two can be compared TERM BY TERM.

  ## What it does NOT do

  It carries no `probe` / observation-port arm and computes no
  Freehand-versus-Reagent increment. That comparison is held pending an
  operator ruling; this file measures `@(subscribe [:q])` and its parts,
  which is what rf2-x0fe2 asks for and all it asks for.

  ## Why NODE, and what that costs

  The target is the browser. Node is the same V8 and this harness is built
  `:advanced` with `goog.DEBUG false`, so the DCE profile — which decides
  whether the Spec 009 trace seam is in the picture at all — is identical.
  Node also reports V8's used-heap counter UNQUANTISED, where Chrome
  buckets `performance.memory` to 100 KB without
  `--enable-precise-memory-info`.

  What differs is POINTER COMPRESSION: Node ships V8 with it OFF and Chrome
  ships it ON, so a tagged slot is 8 bytes here and 4 there. CLJS persistent
  collections are almost nothing but tagged slots, so a Chrome absolute is
  roughly HALF a Node absolute for structures of that kind. The SMI control
  below READS that regime off the running process rather than assuming it.
  **Shares, ratios and slopes transfer; absolute byte counts do not.**
  Every figure printed carries its runtime.

  ## The instrument

  The same one `write_attribution.cljs` uses, and deliberately not a new
  one: if no collection runs between two readings of V8's used-heap
  counter, the difference is the bytes allocated in between, garbage
  included. Every sample is bracketed by an explicit `global.gc()` (node
  `--expose-gc`) and sized so its whole allocation fits one nursery. A
  sample whose counter FELL is discarded and counted.

  Three faults were fixed in that harness on 2026-07-27, and all three are
  designed out here rather than inherited:

  1. **A shared counter clobbered by a control** (rf2-xu0ma / PR #7229).
     `keep!` is a type-PRESERVING increment; the old `arm-ctl` wrote a
     DOUBLE into the same counter, so every subsequent `keep!` boxed a
     fresh 16-byte `HeapNumber` — 4,800 B/call on odd rounds, nothing on
     even ones. Deterministic, so it read as ZERO VARIANCE, not noise.
     Here `keep!` and `reset-sink!` are the ONLY writers of `sink`,
     `reset-sink!` writes the literal Smi `0`, and it runs OUTSIDE the
     measured window. No arm can charge another.

  2. **A positive control reading 2x its own prediction** (rf2-l3jv4 / PR
     #7230). One `.slice()` call SITE saw both PACKED_SMI and
     PACKED_DOUBLE receivers; at that polymorphic site the Smi receiver
     loses V8's clone fast path and allocates its elements store twice.
     The prediction was right and the measurement was wrong. The two
     factories below are character-for-character identical ON PURPOSE and
     must not be merged.

  3. **An arm-order guard that exits 2 on refusal** (rf2-om73r). It
     partitions each arm's per-round figures by predecessor AND by
     position and refuses `unchecked` as loudly as `contaminated`.
     One downgrade exists (rf2-hydpy): a refusal every one of whose arms
     provably reads the instrument's per-WINDOW floor — shown by the
     rf2-tmzie reps sweep, per arm, at the time of refusal — is quoted as
     CERTIFIED AT THE FLOOR (each arm an upper bound, never a p50) and the
     run exits 0. Any arm the sweep cannot attribute keeps exit 2; the
     guard itself, its tolerance and its refusals are untouched.

  ## Read-back: \"N unverified of M\"

  `measure` here does something `write_attribution`'s `-main` does not: it
  VERIFIES every window. Each arm advances the shared counter exactly
  `per` times per call, so after a window of `reps` calls the counter must
  have moved by exactly `per x reps`. A window that fails is UNVERIFIED and
  is reported per arm rather than quietly pooled. An arm whose body was
  dead-code-eliminated, short-circuited, or took a branch it was not meant
  to take cannot pass this silently.

  ## Position beats adjacency, so warm first

  The same study measured one control over sixteen consecutive windows with
  nothing else varying: `42.32`, then six windows at `10.3`, then `8.12`
  for ever. The first window read 5.3x the settled value and the next six
  read +27%; the PREDECESSOR is worth 0.0-0.3%. So `RA_WARM_WINDOWS`
  full-size windows per arm are run and discarded before any round is
  measured, on top of `RA_WARMUP` bare calls and the calibration probe.

  ## The controls, and the floor

  A control whose size is ASSERTED rather than checked has been wrong twice
  on this surface. So the control is read as a SLOPE across sizes, which
  cancels every header and every constant:

    `.slice()` of a PACKED double-element JS array of D elements allocates
    a `FixedDoubleArray` of D unboxed 8-byte slots plus a fixed header. V8
    never pointer-compresses a double, so the slope is 8.000 B/element
    exactly, in EITHER regime.

  and the SMI pair at the same D reads the regime off the process. Both are
  reported predicted-vs-measured at every size, not merely as a slope —
  printing only the slope is how an arm reading exactly TWICE its
  prediction at BOTH sizes went unremarked for as long as it did.

  And the SMI pair REFUSES rather than merely commenting (rf2-l3jv4). It
  used to print `*** NEITHER — the SMI arm is not measuring a tagged-slot
  copy ***` and then exit 0 under the arm-order guard's `VERDICT:
  reportable` beside it, so the exact recurrence the control exists to catch
  could still be published. `re-frame.bench.calibration` turns the ratios
  and the slope into a boolean, and this run's exit code is the OR of three
  independent refusals: the arm order's, the read-back's, and the control's.
  The DBL LARGE pair's ~+9% is deliberately NOT gated — it is an understood
  large-object effect and every arm here allocates small ones.

  `NOOP` is the bare `keep!` loop — the inner-loop skeleton every arm below
  shares, with the arm's own body removed. It is PREDICTED TO READ 0, and
  it is the instrument's FLOOR. Near-zero arms are the CLJS story here (the
  whole point is that most JVM terms vanish), and a near-zero arm carries a
  window-size-dependent floor — one arm elsewhere read 0.2 B/call at
  reps=818 and 32.0 at reps=4000. So `FLOOR SWEEP` re-measures `NOOP`
  across window sizes, and any arm at or under the floor is reported as an
  UPPER BOUND at the instrument's floor, never as a measurement.

  Every ladder arm runs `n` inner iterations per call, so a per-read figure
  is the per-call figure divided by `n` — which divides the per-window
  floor down by `n` as well. That amplification is the only reason a
  ~0 B/read arm is separable from the floor at all.

  ## A closure is not one number, and neither is an arm made of one

  A floor is not the only thing that makes a p50 unquotable. Five arms here
  hand a freshly-allocated thunk to a callee, and on this runtime a closure
  costs 64 B in one mode and 128 B in another — a per-WINDOW choice, with
  nothing in between. `N-NEWFN` is the control that establishes it: one
  closure per inner iteration and nothing else at all, and it steps by
  exactly one closure just as the thunk arms do, in BOTH plan orders.

  So the step is closure CREATION, not any arm's subject, and it is additive
  rather than proportional — the same absolute size on arms whose totals
  differ by 75%. No re-shaping of a thunk arm can remove it: the arm would
  have to stop allocating the thing it exists to price. `-main` prints the
  bimodality table, and a thunk-dominated arm is quotable as its RANGE and as
  an upper bound at the high mode, never as a p50 alone (rf2-ktrvw).

  Nothing this file publishes rests on one: the binding figure is
  `N-BINDONLY`, which carries no closure, and the budget and symmetry checks
  difference two arms that each carry exactly one, so the mode cancels.

  ## The ladder — the same arms as `read_attribution.clj`

    GETIN     the application's own work — `(get-in db [:items i])`
    RAWDV     + a bare substrate derived value over the same container,
              with NO re-frame sub machinery. (The JVM harness spells this
              `interop/make-reaction`; under the React spine that hook is
              deliberately unpublished, and `adapter/make-derived-value` is
              the CLJS surface for the same thing.)
    DEREF     + re-frame's signal graph — `@reaction` on a cached node
    RGSUB     `subscribe` WITHOUT the deref: frame resolution, cache lookup
              and the ref-count attach
    RGREAD    `@(subscribe q)` — the whole substrate-free reader

  ### Inside `subscribe`'s cache-HIT path (rf2-j8ls2 / rf2-ncjyt)

  S1..S4 re-walk `subs/subscribe`'s 1-arity through PUBLIC functions only,
  each a strict prefix of the next and of `RGSUB`:

    S1-CURFRM  `(or (resolve-current-frame) (require-current-frame! ...))`
               — the SHIPPED reader-then-require spelling (rf2-a8bw0)
    S2-TGTID   + `frame-target->id`
    S3-CWFR    + `call-with-frame-resolution` around an empty thunk — the
               flush consult, the generation read, and THE BINDING
    S4-PRELOOK + `(frame/frame id)` + `(get @cache k)`
    RGSUB      + the ref-count attach and the post-swap re-check

  Two RETIRED spellings are kept live beside their replacements as PAIRED
  CONTROLS in one process, so each claimed saving is falsifiable:

    S1-EAGER   the `{:where :event-id}` payload built EAGERLY (pre-rf2-a8bw0)
    N-CWFRWRAP `cwfr` behind the retired `frame-resolution-target` wrapper,
               against N-CWFRRAW, the shipped form. Their difference must
               equal N-RESTGT (rf2-8gb3t).

  ### The ref-count attach, part by part

    RC-ATTACH  the PRE-rf2-j8ls2 `update-in` spelling — the paired control
    RC-CAND    the form that replaced it in `subs/bump-ref-count-fn`
    RC-GUARD   the same `swap-vals!` returning `m` UNCHANGED
    RC-SWAPID  `(swap-vals! cache identity)` — the machinery alone
    RC-UPDIN   pure `(update-in m [k :ref-count] (fnil inc 0))`
    RC-NEST    pure hand-rolled two-level update, same result
    RC-ASSOC   pure `(assoc m k entry)` — the outer HAMT path copy alone
    RC-EASSOC  pure `(assoc entry :ref-count n)` — the inner copy alone

  ### The pre-node lookups (rf2-ncjyt / rf2-ezwnl)

    N-RESTGT   the throwaway frame VALUE the retired wrapper minted
    N-GENREAD  `frame-resolution-generation`
    N-FLUSH    the late-bind flush consult
    N-BINDONLY THE BINDING, standalone — the term that is ~46% of the JVM
               figure and is predicted to be ZERO here
    N-CWFRNOG  `cwfr` on a target that names no image-loaded frame, so the
               binding branch is NOT taken. `S3-CWFR - S2-TGTID - N-CWFRNOG`
               is the binding by subtraction, and must agree with N-BINDONLY.
    N-NEWFN    ONE closure per inner iteration and nothing else — no callee,
               no binding, not even a call. It prices the thunk every arm
               above hands to somebody, and it is what says the bimodal step
               those arms carry is closure CREATION (rf2-ktrvw, below).
    N-LOOKGEN  `registrar/lookup :sub` with `*generation*` BOUND — the
               generation-routed branch that allocates a `[kind id]` key
               vector per call (rf2-ezwnl, ~98 B/call JVM)
    N-LOOKATOM the same lookup on the registrar-atom branch

  ### The ambient-frame reader's route (rf2-f70iq)

  `S0-SCOPE - S0-VAR` prices the CLJS-only React-context consult and says
  nothing about WHERE the bytes go. The H arms walk the route:

    H-FVID     `frame-value->id` on the result
    H-CACHE    `late-bind/get-fn-cached` — the hook lookup alone
    H-SPEC     `substrate-adapter/current-adapter-spec` — the state read alone
    H-IMPL     `function-component-current-frame` — the routed impl alone
    H-SAMEH    `same-adapter?` on two HELD maps — the SHIPPED routing predicate
    H-SAMEFLAT the CANDIDATE flat re-spelling of the same predicate, in the
               same process, as the paired control
    H-ROUTED   `((get-fn-cached :adapter/current-frame))` — the whole shipped
               hook call

  `H-ROUTED` strictly contains `H-CACHE + H-SPEC + H-SAMEH + H-IMPL`, so the
  residual is `(apply impl-fn args)` at `route-hook!`'s OWN site — ONE site
  shared by every routed hook in the bundle, hence callee-POLYMORPHIC. That is
  why no arm here measures `apply` at a private site: with a single callee V8
  inlines it and it reads zero, which is a fast path the shipped code never
  takes. `S0-SCOPE - H-ROUTED - H-FVID` must close to the floor, and does.

  These arms publish NO routed hooks — they only call ones that already exist.
  That is deliberate: an earlier revision added four re-spellings of
  `route-hook!` itself, which changed how many shapes had passed through the
  shared `same-adapter?` and `apply` sites, moved `S0-SCOPE` from 264.0 to
  280.0 B/read, and split eight arms 4x-7x by PHASE. The guard refused, and was
  right. Do not add hook-PUBLISHING arms to this plan.

  ## Cache HIT, and why the db is held STILL

  Every arm here is measured against an UNCHANGING app-db. That is the
  cache-HIT shape the bead names, and it is what a re-render triggered by
  unrelated state does. It is ALSO the one place the JVM harness and this
  one cannot be compared arm-for-arm without saying so: the JVM plain-atom
  adapter's derived value RECOMPUTES ON EVERY DEREF (there is no caching
  layer), so the JVM `DEREF` arm prices a read whose value MOVED, while the
  CLJS spine caches and this `DEREF` arm prices a genuine cache hit. The
  cost of a read whose value moved is the WRITE leg, and that is
  `write_attribution.cljs`'s subject, not this one. Every S/RC/N arm is
  unaffected — none of them touches the node.

  Run it:

      npx shadow-cljs release ui-bench --config-merge '{:main re-frame.bench.read-attribution-cljs/-main :output-to \"out/read-attribution-cljs.js\" :compiler-options {:optimizations :advanced :infer-externs :auto :closure-defines {goog.DEBUG false}}}'
      node --expose-gc out/read-attribution-cljs.js

  Environment: RA_N (subscriptions / inner iterations, default 300),
  RA_SAMPLES (samples per arm across ALL rounds, default 42), RA_ROUNDS
  (default 6 — at least six, so the guard's phase thirds are ranges rather
  than single samples), RA_WARM_WINDOWS (full-size discarded windows per
  arm, default 12 — 6 was `write_attribution`'s figure and the FORWARD plan
  refuses on two arms at 6 while the reversed plan passes. More warm-up
  cannot settle the closure bimodality above, and is not meant to: that is a
  per-window choice, not a settling curve), RA_WARMUP (bare
  calls before calibration, default 3),
  RA_TOLERANCE (the guard's relative-median tolerance, default 0.25),
  RA_ORDER=rev (reverse the base plan before scheduling — a knob, not the
  mitigation), RA_COORDS=0 (register WITHOUT source coords — a labelled
  control, not the default)."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [goog.string :as gstring]
            [goog.string.format]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.bench.calibration :as calib]
            [re-frame.bench.order-guard :as guard]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.spine :as spine]))

;; ---------------------------------------------------------------------------
;; the counter

(defn- used-heap ^number []
  (.-heapUsed (js/process.memoryUsage)))

(defn- collect! []
  (when-let [g (gobj/get js/globalThis "gc")]
    (g)
    (g)))

(defn- env [k d]
  (or (gobj/get (.-env js/process) k) d))

(defn- env-int [k d] (js/parseInt (env k (str d)) 10))

(defn- env-num [k d] (js/parseFloat (env k (str d))))

;; ---------------------------------------------------------------------------
;; the sink — rf2-xu0ma, and it is the instrument
;;
;; `keep!` is a type-PRESERVING increment: given an Smi it produces an Smi and
;; allocates nothing. Given a DOUBLE it produces a double, and storing that
;; double back into the volatile's tagged field boxes a fresh `HeapNumber` —
;; 16 B with pointer compression OFF, 12 B with it ON. An arm with a
;; 300-iteration inner loop would then read 4800 B/call MORE than it costs,
;; purely because something earlier in the plan left a double in this slot.
;; That is exactly what happened in `write_attribution.cljs`, and it presented
;; as ZERO VARIANCE rather than noise, because it was deterministic.
;;
;; THE INVARIANT: `keep!` and `reset-sink!` are the ONLY writers. `reset-sink!`
;; writes the literal Smi `0` and runs OUTSIDE the measured window, so it can
;; neither change the slot's type nor be charged to an arm. No arm here stores
;; a computed value into the counter; every arm READS its value (which is what
;; stops Closure deleting the work) and counts it.
;;
;; The reset is not decoration either: `keep!` is called `per x reps` times per
;; window and the plan runs thousands of windows. Left to run up, the counter
;; would leave the 32-bit Smi range and become a double mid-plan — the very
;; fault this comment is about, arriving by arithmetic instead of by clobber.

(defonce ^:private sink (volatile! 0))

(defn- keep! [v] (vreset! sink (if v (inc @sink) @sink)) nil)

(defn- reset-sink! [] (vreset! sink 0) nil)

;; ---------------------------------------------------------------------------
;; the control — a slope, not a prediction

(defonce ^:private ctl-templates (volatile! {}))

(def ^:private ctl-double-ds
  "Two SMALL sizes a decade apart from the two LARGE ones. Small so the copied
  object is a fraction of V8's 256 KB heap page and the page-tail filler is a
  rounding error; large so the same slope can be re-read where that filler is
  ~2% of the object. A constant error and a scale error look different across
  the four."
  [100 200 1000 10000])

(def ^:private ctl-smi-ds
  "The SAME `.slice()` over a PACKED SMI array. Its slot width is the ONE thing
  that differs from the double control: V8 never compresses a double, but a
  tagged slot is 4 bytes under pointer compression and 8 without it. Node ships
  V8 with it DISABLED; Chrome ships it ENABLED. So this pair does not merely
  check the instrument — it READS OFF which regime this process is in, which is
  what decides whether an absolute here is comparable to a browser one."
  [100 200])

(defn- packed-doubles
  "A PACKED double-element JS array. Built by pushing doubles rather than
  `(.fill (js/Array. d) 0.5)`, which produces a HOLEY array whose `.slice()`
  does not take the packed fast path."
  [d]
  (let [a (array)]
    (dotimes [i d] (.push a (+ 0.5 i)))
    a))

(defn- packed-smis
  "A PACKED SMI-element JS array — same shape, integer elements, so `.slice()`
  yields a `FixedArray` of tagged slots rather than a `FixedDoubleArray`."
  [d]
  (let [a (array)]
    (dotimes [i d] (.push a (inc i)))
    a))

(defn- ctl-key [kind d] (str kind "-" d))

;; rf2-l3jv4 — TWO factories, one per ELEMENT KIND, and the duplication is the
;; whole point. `.slice()`'s clone fast path is keyed on the RECEIVER's elements
;; kind, and V8 has one inline cache per call SITE — that is, per function BODY,
;; shared by every closure made from it. One body closed over both kinds of
;; template gives the harness ONE `.slice()` site with two receiver maps, and at
;; that polymorphic site the PACKED_SMI receiver loses the fast path: the clone
;; allocates its elements store TWICE, so a copy costs `32 + 2 x (16 + 8D)`
;; rather than `32 + 16 + 8D`. Measured in isolation, one process:
;;
;;                 shared site        split sites      predicted
;;   SMI D=100     1665.9 B/copy      849.1 B/copy     848
;;   SMI D=200     3275.8            1651.8          1648
;;   DBL D=100      849.8             849.8            848
;;
;; DO NOT factor these two back together.

(defn- arm-ctl-dbl
  "The PACKED_DOUBLE control. Its `.slice()` site must never see any other
  elements kind — see `arm-ctl-smi`, which is this body's deliberate twin."
  [d]
  (let [t (get @ctl-templates (ctl-key "DBL" d))]
    (fn []
      (let [c (.slice t)]
        (keep! (aget c 0)))
      nil)))

(defn- arm-ctl-smi
  "The PACKED_SMI control, character-for-character `arm-ctl-dbl`'s body and
  separate from it on purpose (rf2-l3jv4)."
  [d]
  (let [t (get @ctl-templates (ctl-key "SMI" d))]
    (fn []
      (let [c (.slice t)]
        (keep! (aget c 0)))
      nil)))

(defn- arm-ctl [kind d]
  (if (= "SMI" kind) (arm-ctl-smi d) (arm-ctl-dbl d)))

(defn- slope
  "Bytes per element between two control readings — the reading that cancels
  every header, every constant and every per-sample overhead."
  [b k1 d1 k2 d2]
  (/ (- (b k2) (b k1)) (- d2 d1)))

;; ---------------------------------------------------------------------------
;; the rig

(defonce ^:private rig (volatile! nil))

;; rf2-f70iq — was `:adapter/current-frame` already published when the bench
;; installed its adapter? Read before `rf/init!`, printed with the figures.
(defonce ^:private pre-published (volatile! nil))

(def ^:private fid :ra/frame)

(def ^:private prod-sub-meta
  "What `reg-sub`'s macro path leaves in the registrar slot for a PRODUCTION
  build: `:ns` / `:file` / `:line` are the locked coord keys; `:column` is
  dev-only (`source-coords/prod-coords-form`) so it is absent here."
  {:ns 'ra.bench :file "ra/bench.cljs" :line 42})

;; ---------------------------------------------------------------------------
;; the RETIRED spellings, held here verbatim as paired controls
;;
;; Each is deliberately NOT a call into the shipped source: the whole point is
;; to keep the retired EXPRESSION measurable beside the one that replaced it,
;; in the same process, against the same live frame — so a claimed saving is a
;; prediction the instrument can falsify rather than a before/after story.

(defn- retired-resolution-target
  "rf2-8gb3t. `live-frame/frame-resolution-target` as it stood before the
  wrapper was retired: a frame VALUE verbatim, a frame-id keyword through
  `live-frame` (which MINTS a fresh frame value), anything else nil."
  [target]
  (cond
    (live-frame/frame-value? target) target
    (keyword? target)                (live-frame/live-frame target)
    :else                            nil))

(defn- retired-current-frame!
  "rf2-a8bw0. `subscribe`'s 1-arity before the payload was deferred: the
  `{:where :event-id}` extra map built on EVERY call, read only on the
  `:rf.error/no-frame-context` path."
  [query-v]
  (frame/require-current-frame!
    :subscribe
    {:where    're-frame.subs/subscribe
     :event-id (first query-v)}))

(defn- shipped-current-frame!
  "The SHIPPED spelling (rf2-a8bw0): the scope reader first, and the payload —
  with `require-current-frame!` building it — only when the reader found
  nothing."
  [query-v]
  (or (frame/resolve-current-frame)
      (frame/require-current-frame!
        :subscribe
        {:where    're-frame.subs/subscribe
         :event-id (first query-v)})))

;; ---------------------------------------------------------------------------
;; the arms
;;
;; EVERY arm advances the counter exactly `n` times per call (the controls,
;; once), which is what makes the per-window read-back exact.

(defn- arm-noop
  "The bare `keep!` loop — the inner-loop skeleton every arm below shares, with
  the arm's own body removed. PREDICTED to read 0 B/call. It is the
  instrument's floor and the reason no subtraction here is load-bearing."
  []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! true))
    nil))

;; ---- the read ladder ------------------------------------------------------

(defn- arm-getin []
  (let [{:keys [n db]} @rig]
    (dotimes [k n] (keep! (get-in db [:items k])))
    nil))

(defn- arm-rawdv []
  (let [{:keys [n raw]} @rig]
    (dotimes [k n] (keep! (adapter/read-container (nth raw k))))
    nil))

(defn- arm-deref []
  (let [{:keys [n held]} @rig]
    (dotimes [k n] (keep! (deref (nth held k))))
    nil))

(defn- arm-rgsub []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n] (keep! (subs/subscribe (nth qs k))))
    nil))

(defn- arm-rgread []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n] (keep! (deref (subs/subscribe (nth qs k)))))
    nil))

;; ---- inside subscribe: the prefix ladder ----------------------------------

;; rf2-x0fe2 — the ambient reader, PAIRED, because this is the one place the
;; CLJS read path does MORE than the JVM one rather than less.
;;
;; `frame/resolve-current-frame` is a plain `*current-frame*` var read on the
;; JVM. On CLJS it consults the `:adapter/current-frame` late-bind hook so the
;; React-context tier is live — a hook lookup plus a routed-hook chain that
;; bottoms out in `frame/current-frame`. Every ambient subscribe pays it, and
;; nothing in the JVM decomposition can see it.
;;
;;   S0-VAR    `(frame/current-frame)` — the dynamic-var tier ALONE, which is
;;             the whole of what the JVM does
;;   S0-SCOPE  `(frame/resolve-current-frame)` — the shipped CLJS reader
;;
;; S0-SCOPE - S0-VAR is the React-context-tier consult, and it is a CLJS-ONLY
;; term. The arms are in one process against one installed adapter, so the pair
;; measures it rather than assuming it.

(defn- arm-s0-var []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (frame/current-frame)))
    nil))

(defn- arm-s0-scope []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (frame/resolve-current-frame)))
    nil))

(defn- arm-s1-curfrm []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n] (keep! (shipped-current-frame! (nth qs k))))
    nil))

(defn- arm-s1-eager []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n] (keep! (retired-current-frame! (nth qs k))))
    nil))

(defn- arm-s2-tgtid []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n]
      (keep! (frame/frame-target->id (shipped-current-frame! (nth qs k)))))
    nil))

(defn- arm-s3-cwfr []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n]
      (let [fid* (frame/frame-target->id (shipped-current-frame! (nth qs k)))]
        (live-frame/call-with-frame-resolution fid* (fn [] (keep! true)))))
    nil))

(defn- arm-s4-prelook []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n]
      (let [q    (nth qs k)
            fid* (frame/frame-target->id (shipped-current-frame! q))]
        (live-frame/call-with-frame-resolution
          fid*
          (fn []
            (let [cache* (:sub-cache (frame/frame fid*))]
              (keep! (get @cache* q)))))))
    nil))

;; ---- the ref-count attach, part by part -----------------------------------
;;
;; RC-CAND is the SHIPPED form (`subs/bump-ref-count-fn`), spelled out here
;; rather than called, so the pair stays a comparison of two EXPRESSIONS and
;; neither arm can drift under the other. RC-ATTACH is the retired `update-in`
;; spelling. The rest strip ONE thing each, so a difference names a part rather
;; than a suspicion. The pure arms run against a SNAPSHOT of the same real
;; n-entry cache map, so the outer HAMT they copy is the real one.

(defn- arm-rc-attach []
  (let [{:keys [n qs cache reactions]} @rig]
    (dotimes [k n]
      (let [q        (nth qs k)
            reaction (nth reactions k)
            [_old new]
            (swap-vals! cache
                        (fn [m]
                          (if (identical? reaction (:reaction (get m q)))
                            (update-in m [q :ref-count] (fnil inc 0))
                            m)))]
        ;; nil unless the guard held — so a window in which the attach did NOT
        ;; land fails the read-back rather than passing quietly.
        (keep! (when (identical? reaction (:reaction (get new q))) reaction))))
    nil))

(defn- arm-rc-cand []
  (let [{:keys [n qs cache reactions]} @rig]
    (dotimes [k n]
      (let [q        (nth qs k)
            reaction (nth reactions k)
            [_old new]
            (swap-vals! cache
                        (fn [m]
                          (let [e (get m q)]
                            (if (identical? reaction (:reaction e))
                              (assoc m q (assoc e :ref-count
                                                (inc (or (:ref-count e) 0))))
                              m))))]
        (keep! (when (identical? reaction (:reaction (get new q))) reaction))))
    nil))

(defn- arm-rc-guard []
  (let [{:keys [n qs cache reactions]} @rig]
    (dotimes [k n]
      (let [q        (nth qs k)
            reaction (nth reactions k)
            [_old new]
            (swap-vals! cache
                        (fn [m]
                          (if (identical? reaction (:reaction (get m q))) m m)))]
        (keep! (when (identical? reaction (:reaction (get new q))) reaction))))
    nil))

(defn- arm-rc-swapid []
  (let [{:keys [n cache]} @rig]
    (dotimes [_ n]
      (let [[_old new] (swap-vals! cache identity)]
        (keep! new)))
    nil))

(defn- arm-rc-updin []
  (let [{:keys [n qs snap]} @rig]
    (dotimes [k n]
      (keep! (update-in snap [(nth qs k) :ref-count] (fnil inc 0))))
    nil))

(defn- arm-rc-nest []
  (let [{:keys [n qs snap]} @rig]
    (dotimes [k n]
      (let [q (nth qs k)
            e (get snap q)]
        (keep! (assoc snap q (assoc e :ref-count (inc (or (:ref-count e) 0)))))))
    nil))

;; The outer HAMT path copy and NOTHING else. `bumped` is a DISTINCT entry
;; object prepared once, because a persistent map's `assoc` short-circuits and
;; returns `this` when the new value is `identical?` to the old — assoc-ing an
;; entry back over itself measures the no-op path and would badly understate
;; the copy this arm exists to price. (On the JVM that mistake understated it
;; by 22x before it was caught.)
(defn- arm-rc-assoc []
  (let [{:keys [n qs snap bumped]} @rig]
    (dotimes [k n]
      (keep! (assoc snap (nth qs k) (nth bumped k))))
    nil))

(defn- arm-rc-eassoc []
  (let [{:keys [n qs snap]} @rig]
    (dotimes [k n]
      (keep! (assoc (get snap (nth qs k)) :ref-count 2)))
    nil))

;; ---- rf2-ncjyt / rf2-ezwnl — the pre-node lookups -------------------------

(defn- arm-n-restgt []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (retired-resolution-target fid)))
    nil))

(defn- arm-n-genread []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (live-frame/frame-resolution-generation fid)))
    nil))

(defn- arm-n-flush []
  (let [{:keys [n]} @rig]
    (dotimes [_ n]
      (when-let [flush! (late-bind/get-fn-cached :live-frame/flush-projection!)]
        (flush!))
      (keep! true))
    nil))

;; THE HEADLINE ARM. On the JVM this is ~760 B/call — `push-thread-bindings`
;; builds a hash-map for the pair, assocs a fresh `TBox` into the thread's
;; binding map (a path copy) and allocates a `Frame`, then pops. In CLJS
;; `binding` expands to `let` + `set!` + `try`/`finally` restore. PREDICTION,
;; stated before the measurement: 0 B/call, at the instrument's floor.
(defn- arm-n-bindonly []
  (let [{:keys [n gen]} @rig]
    (dotimes [_ n]
      (binding [registrar/*generation* gen]
        (keep! registrar/*generation*)))
    nil))

;; `call-with-frame-resolution` with a target that names no image-loaded frame:
;; the late-bind flush consult, the generation read and the thunk call, with NO
;; `binding`. This is the arm the JVM harness subtracts to isolate the binding.
(defn- arm-n-cwfr-nogen []
  (let [{:keys [n]} @rig]
    (dotimes [_ n]
      (live-frame/call-with-frame-resolution nil (fn [] (keep! true))))
    nil))

;; THE EXACT ISOLATOR, and it is why this file does not simply copy the JVM
;; harness's subtraction — TWICE over, because the obvious repair is also wrong.
;;
;; FAULT 1, in the JVM subtraction. `read_attribution.clj` isolates the binding
;; as `S3-CWFR - S2-TGTID - N-CWFRNOG`. That assumes the only difference between
;; `cwfr` on a real target and `cwfr` on `nil` is the binding — but a nil target
;; SHORT-CIRCUITS `frame-resolution-generation`, so the difference also contains
;; the generation read. On the JVM the binding was ~760 B and the generation read
;; 16-40 B, so the conflation was 2-5% and invisible. Here the binding is
;; predicted to be ZERO, so that residual would be ENTIRELY the generation read —
;; a subtraction that reports the generation read AS the binding and manufactures
;; a "CLJS binding cost" that does not exist.
;;
;; FAULT 2, in the obvious repair, and it was caught by this harness disagreeing
;; with itself. Re-spelling cwfr's body INLINE and subtracting reads 64 B/read
;; where standalone `N-BINDONLY` reads 0.1 — because `call-with-frame-resolution`
;; takes a THUNK and its caller allocates a fresh closure per call, while an
;; inline re-spelling allocates none and lets V8 elide what never escapes. The
;; subtraction then prices the CLOSURE, and would have been published as the
;; binding. Same shape of error as fault 1, opposite sign.
;;
;; So the pair below is SYMMETRIC: two sibling functions of identical arity and
;; identical shape, each taking a thunk the arm allocates fresh, differing in
;; exactly ONE token — `(binding [registrar/*generation* gen] (thunk))` against
;; `(thunk)`. Same closure, same escape, same flush consult, same generation
;; read. `N-CWFRBIND - N-CWFRGEN` is therefore the binding and nothing else.
;;
;; `cwfr-bind` is `call-with-frame-resolution`'s body verbatim (the `Q-SCHED`
;; discipline — the measured expression pinned in the harness so it cannot drift
;; under the attribution). That it is verbatim is CHECKED rather than asserted:
;; `N-CWFRBIND` must agree with `N-CWFRRAW`, which calls the shipped function.

(defn- cwfr-bind
  "`live-frame/call-with-frame-resolution`'s body, verbatim. Its twin below
  differs in one token; do not let them drift apart."
  [frame-target thunk]
  (when-let [flush! (late-bind/get-fn-cached :live-frame/flush-projection!)]
    (flush!))
  (if-let [gen (live-frame/frame-resolution-generation frame-target)]
    (binding [registrar/*generation* gen]
      (thunk))
    (thunk)))

(defn- cwfr-nobind
  "`cwfr-bind` with the `binding` removed and NOTHING else changed."
  [frame-target thunk]
  (when-let [flush! (late-bind/get-fn-cached :live-frame/flush-projection!)]
    (flush!))
  (if-let [_gen (live-frame/frame-resolution-generation frame-target)]
    (thunk)
    (thunk)))

;; rf2-x0fe2 — `thunk-escape` exists because the ARM-ORDER GUARD REFUSED the
;; arm below, and it was right to.
;;
;; `call-thunk` was originally `(defn- call-thunk [thunk] (thunk))`. Closure
;; inlines a private one-liner, so the thunk never escaped, so V8's escape
;; analysis elided it — and the arm read 16.0 B/call, the instrument floor,
;; which is to say it measured NOTHING. Except in some windows, where it read
;; 19,219 B/call: exactly 64 B per inner iteration, one closure each. The guard
;; refused it by PHASE in three separate configurations (6 warm windows, 12 warm
;; windows, 8 rounds) with BIT-IDENTICAL values every time, so it is not a
;; settling curve and more warm-up does not touch it — it is escape analysis
;; succeeding or failing according to V8's optimization tier at that point in
;; the plan.
;;
;; The same ~19,300 B/call step shows up as the HIGH end of `N-CWFRBIND`,
;; `N-CWFRGEN` and `N-CWFRRAW`'s ranges, on all three at once. That is the
;; mechanism named: an arm whose figure is dominated by a thunk closure is
;; reading V8's optimization state as much as its own allocation.
;;
;; So the thunk is made to ESCAPE for real — stored where nothing can prove it
;; dead. The arm then prices one closure, which is what it was always supposed
;; to do. This repairs the ARM; the guard's tolerance is untouched.
;;
;; It is also why the binding is isolated by a SYMMETRIC PAIR and not by any
;; subtraction between differently-shaped arms: whatever V8 does to a closure it
;; does to both halves, and cancels.
;;
;; rf2-ktrvw — AND IT PRICES ONE CLOSURE *BIMODALLY*, WHICH NO ARM CAN FIX.
;;
;; The escape repair above removed the ELISION. It did not, and could not,
;; remove the remaining ~64 B/read step, because that step is not the arm's:
;; `N-NEWFN` creates one closure per inner iteration and does NOTHING else —
;; no callee, no binding, not even a call — and it carries the step too.
;;
;;   N-NEWFN      settled  64.0   high  128.1   step 64.1 B/read = 1.00 closures
;;   N-CALLTHUNK  settled  64.0   high  128.1   step 64.1        = 1.00
;;   N-CWFRRAW    settled 112.0   high  176.5   step 64.5        = 1.01
;;
;; The step is the SAME ABSOLUTE SIZE on arms whose totals differ by 75%, so it
;; is ADDITIVE and belongs to closure CREATION. `N-CALLTHUNK - N-NEWFN` is
;; 0.0 B/read against a prediction of 0, so `call-thunk` itself costs nothing
;; beyond the closure and there is no arm-shaped defect left to repair.
;;
;; It appears in the FORWARD plan and the REVERSED one alike, so it is NOT a
;; position effect. That is why the guard's PHASE factor adjudicates it by
;; luck — it fires when a third happens to land wholly in one mode and passes
;; when a third straddles both, which is the house rule working correctly on a
;; factor that does not describe the phenomenon. Widening the tolerance would
;; hide a real bimodality; narrowing it would refuse runs at random. Neither is
;; the repair, and the guard is left alone.
;;
;; WHAT THESE ARMS THEREFORE MEASURE: one closure, in whichever of two modes
;; V8's closure site is in — 64 B or 128 B. They are quotable as a RANGE and as
;; an upper bound at the high mode, never as a p50 alone, and `-main` prints
;; that table. No headline here depends on one: the published binding figure is
;; `N-BINDONLY`, which allocates no closure at all, and the BUDGET
;; reconstruction and SYMMETRY CHECK both difference two arms that each carry
;; exactly one, so the mode cancels.

(defonce ^:private thunk-escape (volatile! nil))

(defn- call-thunk
  "Prices what the cwfr arms pay for the CALLING CONVENTION alone: one fresh
  closure per call that genuinely escapes. `N-CWFRNOG - N-CALLTHUNK` is then
  cwfr's own work with the harness's thunk overhead removed."
  [thunk]
  (vreset! thunk-escape thunk)
  (thunk))

;; rf2-ktrvw — THE CLOSURE, PRICED ON ITS OWN, because every arm that hands a
;; thunk to another function is dominated by it and none of them could say what
;; the thunk cost without assuming it.
;;
;; This arm creates ONE closure per inner iteration and does nothing else with
;; it: no callee, no binding, no generation read, not even a call. The thunk is
;; stored where nothing can prove it dead — `call-thunk`'s own discipline — so
;; escape analysis cannot remove it.
;;
;; TWO PREDICTIONS, both stated before the measurement:
;;
;;   1. `N-NEWFN` reads what one closure costs, and `N-CALLTHUNK - N-NEWFN` is
;;      then `call-thunk`'s own work — a `vreset!` and a call — which is 0.
;;   2. If the ~64 B/read STEP that appears on every thunk arm at once is a
;;      property of CLOSURE CREATION rather than of any arm's subject, this
;;      arm — which is nothing but closure creation — must show the SAME step.
;;
;; The second is the one the bead is about. An arm cannot be re-shaped out of a
;; cost its own subject carries, so if the step is here it is not a defect in
;; `N-CALLTHUNK` and no repair of `N-CALLTHUNK` can remove it.

(defonce ^:private fn-escape (volatile! nil))

(defn- arm-n-newfn []
  (let [{:keys [n]} @rig]
    (dotimes [_ n]
      ;; created and made to escape, never called — so the counter advances
      ;; exactly `n` times per call, as every arm here must.
      (vreset! fn-escape (fn [] (keep! true)))
      (keep! true))
    nil))

(defn- arm-n-cwfr-bind []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (cwfr-bind fid (fn [] (keep! true))))
    nil))

(defn- arm-n-cwfr-gen []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (cwfr-nobind fid (fn [] (keep! true))))
    nil))

(defn- arm-n-callthunk []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (call-thunk (fn [] (keep! true))))
    nil))

;; rf2-8gb3t, the falsifiable pair: the RETIRED composition every caller wrote
;; (`(cwfr (frame-resolution-target X) thunk)`) against the SHIPPED one
;; (`(cwfr X thunk)`). Their difference must be N-RESTGT — the wrapper and
;; nothing else.
(defn- arm-n-cwfr-wrap []
  (let [{:keys [n]} @rig]
    (dotimes [_ n]
      (live-frame/call-with-frame-resolution
        (retired-resolution-target fid)
        (fn [] (keep! true))))
    nil))

(defn- arm-n-cwfr-raw []
  (let [{:keys [n]} @rig]
    (dotimes [_ n]
      (live-frame/call-with-frame-resolution fid (fn [] (keep! true))))
    nil))

;; rf2-ezwnl's pair. The generation-routed branch is `(get resolver [kind id])`
;; and builds a fresh two-element key vector per call; the registrar-atom
;; branch is two paired `get`s and builds nothing. Both arms carry the same
;; `(first query-v)`, so the DIFFERENCE is the key vector.
(defn- arm-n-lookgen []
  (let [{:keys [n qs gen]} @rig]
    (binding [registrar/*generation* gen]
      (dotimes [k n]
        (keep! (registrar/lookup :sub (first (nth qs k))))))
    nil))

(defn- arm-n-lookatom []
  (let [{:keys [n qs]} @rig]
    (dotimes [k n]
      (keep! (registrar/lookup :sub (first (nth qs k)))))
    nil))

;; ---------------------------------------------------------------------------
;; rf2-f70iq — the ambient-frame reader, STEP BY STEP
;;
;; `S0-SCOPE - S0-VAR` says the CLJS-only React-context consult costs 264 B/read
;; and nothing about WHERE. The route has four steps and each is a candidate:
;;
;;   (frame-value->id                                    <- H-FVID
;;     (if-let [f (late-bind/get-fn-cached               <- H-CACHE
;;                  :adapter/current-frame)]
;;       (f)                                             <- H-ROUTED (the whole call)
;;       (current-frame)))
;;
;; and `f` is `route-hook!`'s closure, which is itself three things:
;;
;;   (fn routed-hook [& args]
;;     (if (same-adapter? spec (current-adapter-spec))   <- H-SAMEH + H-SPEC
;;       (apply impl-fn args)                            <- the RESIDUAL
;;       ...))
;;
;; with `impl-fn` = `adapter-context/function-component-current-frame`  <- H-IMPL.
;;
;; The arms below price each step against the SAME installed adapter in the
;; SAME process, and the budget must close:
;;
;;   H-ROUTED  =  H-CACHE + H-SPEC + H-SAMEH + H-IMPL + the apply
;;
;; THE CANDIDATE, held here as a PAIRED CONTROL. `same-adapter?`'s body is
;;
;;     (boolean (and a b (let [ka (:kind a)] …)))
;;
;; and every one of `boolean` / `and` / `let` is a STATEMENT-emitting form in
;; EXPRESSION position, so the ClojureScript compiler wraps the whole body in an
;; IIFE that CLOSES OVER `a` and `b`. `:advanced` does not remove it — the
;; compiled output reads
;;
;;     function $E(a,b){return be(function(){ … a … b … }())}
;;
;; so a fresh JS closure (with its context and feedback cell) is allocated on
;; EVERY call, and `same-adapter?` runs once per chain link on every routed-hook
;; call. `same-adapter-flat?` threads the same decisions through `if` in RETURN
;; position, where the compiler emits statements and allocates nothing; it
;; compiles to
;;
;;     function(a,b){if(n(a)&&n(b)){var c=…;return …}return !1}
;;
;; It is kept BESIDE the shipped predicate rather than swapped in for it, so the
;; saving is a prediction the instrument can falsify in ONE process rather than a
;; before/after story across two — the same discipline as S1-EAGER beside
;; S1-CURFRM and N-CWFRWRAP beside N-CWFRRAW.

(defn- same-adapter-flat?
  "`substrate-adapter/same-adapter?` with IDENTICAL semantics and no
  expression-position `boolean` / `and` / `let`: false for a falsey `a` or `b`,
  the canonical-kind comparison when `a`'s kind is canonical, object identity
  otherwise, and a real boolean in every branch. That it decides identically is
  CHECKED rather than asserted — the agreement gate in `-main` runs both
  spellings over the cases that discriminate the branches."
  [a b]
  (if a
    (if b
      (let [ka (:kind a)]
        (if (and (keyword? ka) (= "rf.adapter" (namespace ka)))
          (= ka (:kind b))
          (identical? a b)))
      false)
    false))

(defn- arm-h-spec []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (adapter/current-adapter-spec)))
    nil))

(defn- arm-h-sameh []
  (let [{:keys [n spec]} @rig]
    (dotimes [_ n] (keep! (adapter/same-adapter? spec spec)))
    nil))

(defn- arm-h-sameflat []
  (let [{:keys [n spec]} @rig]
    (dotimes [_ n] (keep! (same-adapter-flat? spec spec)))
    nil))

(defn- arm-h-fvid []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (frame/frame-value->id fid)))
    nil))

(defn- arm-h-cache []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (late-bind/get-fn-cached :adapter/current-frame)))
    nil))

(defn- arm-h-impl []
  (let [{:keys [n]} @rig]
    (dotimes [_ n] (keep! (adapter-context/function-component-current-frame)))
    nil))

;; The whole shipped call — the hook lookup AND the routed closure. `S0-SCOPE -
;; H-ROUTED` is then `frame-value->id`, which H-FVID prices independently.
(defn- arm-h-routed []
  (let [{:keys [n]} @rig]
    (dotimes [_ n]
      (keep! ((late-bind/get-fn-cached :adapter/current-frame))))
    nil))


;; ---------------------------------------------------------------------------
;; the driver

(defn- advanced-by
  "READ-BACK for an arm that advances the counter `per` times per call: after a
  window of `reps` calls the counter must have moved by exactly `per x reps`."
  [per]
  (fn [before after reps] (== (- after before) (* per reps))))

(defn- measure
  "Run `f` `reps` times inside one collection-free sample, `samples` times.
  Answers bytes per CALL. `:xs` is the accepted samples themselves, because a
  round's figures have to be poolable across rounds and a p50 of p50s is not
  one.

  The counter is reset OUTSIDE the window (before the collection and before the
  first heap reading), so the reset can be charged to nothing and cannot change
  the slot's type. `verify` is then handed the counter before and after: a
  window whose arm did not do the work it was credited with is UNVERIFIED and
  is counted rather than quietly pooled."
  [f reps samples verify]
  (let [acc        (array)
        dropped    (volatile! 0)
        unverified (volatile! 0)]
    (dotimes [_ samples]
      (reset-sink!)
      (collect!)
      (let [before @sink
            h0     (used-heap)]
        (dotimes [_ reps] (f))
        (let [d     (- (used-heap) h0)
              after @sink]
          (when-not (verify before after reps) (vswap! unverified inc))
          (if (neg? d)
            (vswap! dropped inc)
            (.push acc (/ d reps))))))
    (.sort acc (fn [a b] (- a b)))
    (let [c (alength acc)]
      {:p50        (if (pos? c) (aget acc (js/Math.floor (/ c 2))) -1)
       :lo         (if (pos? c) (aget acc 0) -1)
       :hi         (if (pos? c) (aget acc (dec c)) -1)
       :xs         (vec acc)
       :accepted   c
       :dropped    @dropped
       :unverified @unverified})))

(defn- summarise
  "Pool an arm's accepted samples from every round into one figure."
  [xs]
  (let [s (vec (sort xs))
        c (count s)]
    {:p50      (if (pos? c) (nth s (js/Math.floor (/ c 2))) -1)
     :lo       (if (pos? c) (first s) -1)
     :hi       (if (pos? c) (peek s) -1)
     :accepted c}))

(defn- calibrate
  "Pick a rep count that puts one sample near `target` bytes, so no sample
  outgrows the nursery and no sample is lost in the counter's own noise."
  [f target]
  (collect!)
  (let [h0  (used-heap)
        _   (dotimes [_ 4] (f))
        per (max 1 (/ (- (used-heap) h0) 4))]
    (-> (js/Math.round (/ target per)) (max 1) (min 4000))))

;; ---------------------------------------------------------------------------
;; rf2-hydpy — the floor-aware quote
;;
;; Every improvement on this surface now lands its arms at the instrument's
;; own floor, and the arm-order guard refuses a floor-level arm exactly as it
;; refuses a contaminated one — rf2-2ix22's two AFTER runs exited 2 on six
;; arms the levers had optimised to ~nothing, every one refused by PHASE with
;; an identical ratio and nothing else in common. The signature is a
;; per-WINDOW floor: an arm that no longer allocates reads the instrument's
;; own per-window bytes divided by its rep count, and when that floor moves
;; mid-run the arm's rounds split disjointly. That is not the arm's subject
;; moving, but the guard cannot know it from the plan alone.
;;
;; The discriminator is rf2-tmzie's, applied per REFUSED arm: re-measure the
;; arm across window sizes. A REAL per-call cost cannot see the window, so
;; its B/call is flat across the ladder; a per-WINDOW quantity divides by
;; reps, so across a span-S ladder it falls ~S-fold. [[floor-verdict]] demands
;; STRICTLY MORE than half the full per-window collapse, so every recorded
;; per-call signature refuses attribution: rf2-tmzie's C-FRAME drifted 1.09x
;; over a 62x range (real, stays refused), and rf2-ktrvw's closure bimodality
;; is bounded by 2x — its two modes (real, stays refused). The strictness is
;; load-bearing at the MINIMUM span: there the demanded ratio is span/2 = 2,
;; exactly the bimodal class's own bound, and the inclusive comparison this
;; replaced classified that bound as the floor (MERGED-PR AUDIT #7292).
;; Equality is not collapse. A refused arm whose sweep DOES
;; collapse is CERTIFIED AT THE FLOOR: quotable as the upper bound of its
;; worst round, never as a measured p50 — the same quote the floor arms in
;; the table above already carry, now with the exit code to match.
;;
;; What this is NOT: the guard is untouched — its tolerance, its factors and
;; its refusals stand exactly as before, and an arm the sweep cannot attribute
;; (flat, bimodal, unverified, or a plan-level :unchecked) leaves the run at
;; exit 2. Attribution can only ever move a refusal to a WEAKER claim (an
;; upper bound), never to a certified measurement, and every branch of
;; [[floor-verdict]] fails toward refusal. Residual risk, stated: a genuinely
;; per-call mechanism that fell by more than half the ladder span across an
;; 8x window range would read as the floor; no mechanism on record does, and
;; one that did would first have to defeat the read-back check that pins
;; `per x reps` units of work to every sweep window.

(defn- floor-verdict
  "Classify what one guard-refused arm reads: a per-CALL cost, or the
  instrument's per-WINDOW floor. PURE — [[floor-self-test]] prices it on
  recorded fixtures before any live sweep is trusted.

  `points` — [{:reps r :p50 bytes-per-call :unverified u} ...] from
  re-measuring the arm at window sizes derived from its own calibrated reps.

  Answers `{:status :floor | :per-call | :indeterminate}` plus the numbers.
  `:floor` needs B/call to fall by STRICTLY MORE than span/2 from the
  smallest window to the largest — more than half the collapse a pure
  per-window floor shows — over a span of at least 4. Strict, because at the
  minimum span the threshold is exactly the 2x bimodal class's bound
  (rf2-ktrvw), and equality is not collapse. Everything else, including an
  unverified sweep window, a short ladder or a zero-only sweep, is NOT
  attribution: the refusal stands."
  [points]
  (let [pts   (vec (sort-by :reps points))
        r-lo  (:reps (first pts) 0)
        r-hi  (:reps (peek pts) 0)
        span  (if (pos? r-lo) (/ r-hi r-lo) 0)
        small (:p50 (first pts))
        large (:p50 (peek pts))
        unv   (reduce + (map #(:unverified % 0) pts))]
    (cond
      (< (count pts) 2)
      {:status :indeterminate :why "fewer than two ladder points"}

      (pos? unv)
      {:status :indeterminate
       :why    (str unv " UNVERIFIED sweep window(s) — the sweep did not measure the arm")}

      (some #(neg? (:p50 %)) pts)
      {:status :indeterminate :why "a ladder point accepted no samples"}

      (< span 4)
      {:status :indeterminate
       :why    (str "ladder span " (.toFixed span 1) "x < 4x — too narrow to tell a floor from a cost")}

      (and (zero? small) (zero? large))
      {:status :indeterminate :why "both endpoints read 0 — nothing here explains a refusal"}

      :else
      (let [ratio     (cond (== small large) 1.0
                            (zero? large)    js/Infinity
                            :else            (/ small large))
            threshold (/ span 2.0)
            ;; STRICT — equality is not collapse. At the minimum span of 4
            ;; the threshold is exactly 2, which is the rf2-ktrvw bimodal
            ;; class's own bound; an inclusive comparison classified that
            ;; bound as :floor (MERGED-PR AUDIT #7292, and the pinned
            ;; minimum-span fixture in [[floor-self-test]] holds the line).
            floor?    (> ratio threshold)]
        {:status    (if floor? :floor :per-call)
         :ratio     ratio
         :span      span
         :threshold threshold
         :why       (if floor?
                      (str "B/call falls " (.toFixed ratio 2) "x across a " (.toFixed span 1)
                           "x ladder (> span/2 = " (.toFixed threshold 1)
                           ") — a per-WINDOW floor, not a per-call cost")
                      (str "B/call moves only " (.toFixed ratio 2) "x across a " (.toFixed span 1)
                           "x ladder (<= span/2 = " (.toFixed threshold 1)
                           ") — a real per-call reading; the refusal stands"))}))))

(defn- floor-self-test
  "Deterministic fixtures replayed from recorded readings, one per property
  the verdict must hold. Both directions are priced: the floors that must
  attribute, and the real per-call signatures — flat, and bimodal — that must
  not."
  []
  (let [checks
        [;; rf2-tmzie's C-FRAME: the recorded REAL per-call cost, flat within
         ;; 9% over a 62x range. A cost this flat may never read as the floor.
         {:name   "a rep-INDEPENDENT reading is a per-call cost — the refusal stands"
          :want   :per-call
          :points [{:reps 64 :p50 34.8} {:reps 256 :p50 32.7}
                   {:reps 512 :p50 32.1} {:reps 4000 :p50 32.0}]}
         ;; rf2-tmzie's C-FRAMEG: the recorded floor, falling as 1/reps to
         ;; zero — the endpoint ratio is infinite and infinity clears span/2.
         {:name   "the recorded 1/reps collapse to zero is the floor"
          :want   :floor
          :points [{:reps 64 :p50 2.3} {:reps 256 :p50 0.6}
                   {:reps 512 :p50 0.0} {:reps 4000 :p50 0.0}]}
         ;; a floor that never reaches zero still collapses with the window
         {:name   "a 1/reps collapse that stays nonzero is still the floor"
          :want   :floor
          :points [{:reps 500 :p50 128.0} {:reps 1000 :p50 64.0}
                   {:reps 2000 :p50 32.0} {:reps 4000 :p50 16.0}]}
         ;; rf2-ktrvw's closure bimodality: a REAL 64/128 B per-call cost whose
         ;; p50 lands in different modes at different ladder points. Its 2x
         ;; never EXCEEDS span/2 at any accepted span — it touches it exactly
         ;; at span 4 (the pinned fixture below) — so it must NOT attribute.
         ;; This is the planted-bimodal direction, priced as a fixture.
         {:name   "the bimodal closure step is NOT the floor — the refusal stands"
          :want   :per-call
          :points [{:reps 500 :p50 128.1} {:reps 1000 :p50 128.1}
                   {:reps 2000 :p50 64.0} {:reps 4000 :p50 64.0}]}
         ;; rf2-hydpy (MERGED-PR AUDIT #7292): the SAME 2x class at the
         ;; verdict's MINIMUM legal span, where span/2 is exactly the class's
         ;; own bound — ratio 2.0 against threshold 2.0. The inclusive
         ;; comparison classified this :floor; equality is not collapse, and
         ;; the bound refuses. PINNED at the boundary — do not widen the span
         ;; or nudge a p50 to make it pass.
         {:name   "the 2x bimodal bound at the minimum span 4 is NOT the floor"
          :want   :per-call
          :points [{:reps 1 :p50 128.0} {:reps 2 :p50 128.0}
                   {:reps 4 :p50 64.0}]}
         ;; the recorded 2.0125x order contamination (rf2-jr76s's fixture in
         ;; the guard's own self-test) is likewise under span/2
         {:name   "a 2x contaminated reading is NOT the floor at span 8"
          :want   :per-call
          :points [{:reps 500 :p50 16.1} {:reps 1000 :p50 12.0}
                   {:reps 2000 :p50 9.0} {:reps 4000 :p50 8.0}]}
         ;; LIVE, recorded from this harness on this host, 2026-07-31, one
         ;; run: H-ROUTED refused by phase (its plan rounds split 179.4 ->
         ;; 1227.9 B/call at reps=4000, the rf2-2ix22 signature) and its sweep
         ;; collapsed 7.83x across an 8x ladder — the floor, attributed.
         {:name   "the live rf2-2ix22 signature attributes to the floor"
          :want   :floor
          :points [{:reps 500 :p50 9616.0} {:reps 1000 :p50 1227.9}
                   {:reps 2000 :p50 1227.9} {:reps 4000 :p50 1227.9}]}
         ;; The SAME run's H-SAMEFLAT — the flat predicate, nominally the same
         ;; near-zero subject — was pinned by the instrument in a rep-
         ;; INDEPENDENT state instead (9632.0 B/call, bit-flat across its
         ;; ladder), and its refusal must stand: a flat sweep proves nothing
         ;; about the floor, whatever the arm's subject nominally costs.
         {:name   "the same run's rep-independent sibling stays refused"
          :want   :per-call
          :points [{:reps 75 :p50 9632.0} {:reps 149 :p50 9632.0}
                   {:reps 297 :p50 9632.0} {:reps 593 :p50 9632.0}]}
         {:name   "a ladder too narrow to discriminate refuses attribution"
          :want   :indeterminate
          :points [{:reps 2 :p50 10.0} {:reps 4 :p50 2.0}]}
         {:name   "an unverified sweep window refuses attribution"
          :want   :indeterminate
          :points [{:reps 500 :p50 128.0} {:reps 4000 :p50 16.0 :unverified 1}]}
         {:name   "a zero-only sweep explains no refusal"
          :want   :indeterminate
          :points [{:reps 500 :p50 0.0} {:reps 4000 :p50 0.0}]}]
        results (mapv (fn [{:keys [name want points]}]
                        (let [v (floor-verdict points)]
                          {:name name :want want :got (:status v)
                           :ok (= want (:status v)) :why (:why v)}))
                      checks)]
    {:ok? (every? :ok results) :checks results}))

(defn- print-floor-self-test!
  "Run [[floor-self-test]], print each check, and answer whether it passed. A
  harness that gets `false` must measure nothing."
  []
  (let [st (floor-self-test)]
    (doseq [c (:checks st)]
      (println (str ";; floor-verdict " (if (:ok c) "ok  " "FAIL") " " (:name c)
                    "  — want " (name (:want c)) ", got " (name (:got c)))))
    (:ok? st)))

;; ---------------------------------------------------------------------------
;; rig construction

(defn- db-of [n] {:items (mapv (fn [i] [:v i]) (range n))})

(defn- build-rig! [n coords?]
  ;; One sub-id per item, registered per index so every subscription is a
  ;; distinct cache entry with a distinct body closure — the shape a real
  ;; application's n boundaries have.
  ;;
  ;; `coords?` decides whether those registrations carry SOURCE COORDS, and it
  ;; is not a detail (rf2-4k5hs). This ns requires `re-frame.core` WITHOUT
  ;; `:include-macros true`, so `rf/reg-sub` here is the plain FUNCTION and no
  ;; call site is captured — left alone the registrar slot comes out `{}`. A
  ;; real application writes `(rf/reg-sub ...)` in a namespace that does get the
  ;; macro, so its slots carry `:ns` / `:file` / `:line`. The DEFAULT hands the
  ;; registrar the production meta explicitly.
  (let [sub-ids (mapv #(keyword "ra" (str "s" %)) (range n))
        qs      (mapv vector sub-ids)]
    (doseq [[i id] (map-indexed vector sub-ids)]
      (let [body (fn [db _] (get-in db [:items i]))]
        (if coords?
          (rf/reg-sub id prod-sub-meta body)
          (rf/reg-sub id body))))
    (live-frame/make-frame {:id fid})
    (frame/replace-app-db! fid (db-of n))
    (let [src   (frame/app-db-container fid)
          cache (:sub-cache (frame/frame fid))
          ;; Hold n subscriptions the way a mounted application holds them, so
          ;; every node is live for the whole run — which is what makes DEREF a
          ;; cache HIT rather than a cold compute.
          held  (mapv subs/subscribe qs)
          ;; The substrate shell over the same container computing the same
          ;; body, with NO re-frame sub machinery over it. One watcher each, the
          ;; shape a mounted boundary gives it — without it the derived value
          ;; takes its zero-subscriber branch and is not the thing an
          ;; application holds.
          raw   (mapv (fn [i]
                        (let [dv (adapter/make-derived-value
                                   [src] (fn [db] (get-in db [:items i])))]
                          (adapter/subscribe-container dv (fn [_ _] nil))
                          (adapter/read-container dv)
                          dv))
                      (range n))]
      ;; Establish each held node's memo baseline the way a first render does.
      (doseq [r held] (deref r))
      (vreset! rig
               {:n         n
                :qs        qs
                :db        (deref src)
                :cache     cache
                :held      held
                :raw       raw
                :container src
                ;; rf2-f70iq: the installed spec the routing predicate compares
                ;; against, and the routed impl the calling-convention pair calls
                :spec      (adapter/current-adapter-spec)
                :impl      adapter-context/function-component-current-frame
                ;; the exact reaction the shipped `identical?` guard compares
                ;; against, a SNAPSHOT of the real n-entry cache map for the
                ;; pure arms, and the frame's sealed generation for the
                ;; binding arms
                :reactions (mapv #(:reaction (get @cache %)) qs)
                :snap      @cache
                :bumped    (mapv #(update (get @cache %) :ref-count inc) qs)
                :gen       (live-frame/frame-resolution-generation fid)}))))

;; ---------------------------------------------------------------------------

(defn- fmt [x] (.toFixed x 1))

(defn- fmt3 [x] (.toFixed x 3))

(defn ^:export -main [& _]
  ;; Ahead of everything, because a broken guard makes every figure below
  ;; unpublishable and finding that out after the run is wasteful. The checks
  ;; are fixtures replayed from recorded readings, so this is deterministic.
  (when-not (guard/print-self-test!)
    (throw (ex-info "order guard self-test FAILED — nothing may be measured" {})))
  ;; And its floor-attribution counterpart (rf2-hydpy), for the same reason:
  ;; a broken verdict here could lift a refusal it had no right to lift.
  (when-not (print-floor-self-test!)
    (throw (ex-info "floor-verdict self-test FAILED — nothing may be measured" {})))
  ;; rf2-l3jv4 — and the control calibration, whose refusal is a third
  ;; independent contributor to this run's exit code. Injected ratios,
  ;; including the recorded 16.11 B/slot the owner bead was opened on.
  (when-not (calib/print-self-test!)
    (throw (ex-info "calibration self-test FAILED — nothing may be measured" {})))
  (let [n            (env-int "RA_N" 300)
        samples      (env-int "RA_SAMPLES" 42)
        warmup       (env-int "RA_WARMUP" 3)
        ;; rf2-x0fe2: 6 was `write_attribution`'s figure and it is NOT enough
        ;; here. At 6 the FORWARD plan refused on two arms — `N-CALLTHUNK` and
        ;; `N-CWFRNOG`, both by PHASE, both reading up to 2x their settled value
        ;; in the FIRST THIRD and dead flat thereafter — while the REVERSED plan
        ;; passed with the same 6. That is the settling curve aligned with
        ;; position, exactly the confound the phase factor exists to catch, and
        ;; the answer to it is more warm-up, never a wider tolerance.
        warm-windows (env-int "RA_WARM_WINDOWS" 12)
        rounds       (max 2 (env-int "RA_ROUNDS" 6))
        per-round    (max 1 (js/Math.ceil (/ samples rounds)))
        tolerance    (env-num "RA_TOLERANCE" 0.25)
        rev?         (= "rev" (env "RA_ORDER" ""))
        coords?      (not= "0" (env "RA_COORDS" "1"))]
    ;; rf2-f70iq PROVENANCE. `route-hook!` chains, so the shipped hook is one
    ;; closure PER PUBLISHING ADAPTER. Whether any adapter published this key at
    ;; ns-load — before the bench installs its own — decides whether the shipped
    ;; route is ONE link or two — and a second link would be a second
    ;; `same-adapter?` call. Read it, do not assume it.
    (vreset! pre-published (some? (late-bind/get-fn :adapter/current-frame)))
    (rf/init! (spine/make-react-adapter
                (spine/make-react-spine
                  {:substrate-name        "read-attribution-cljs"
                   :gensym-prefix-sub     "ra-sub-"
                   :gensym-prefix-derived "ra-derived-"
                   :gensym-prefix-use-sub "ra-use-sub-"
                   :use-memo              (fn [t _] (t))
                   :use-callback          (fn [t _] t)
                   :use-context           (fn [_] nil)})
                {:kind :rf.adapter/read-attribution-cljs :frame-provider nil}))
    (vreset! ctl-templates
             (into {} (concat (map (fn [d] [(ctl-key "DBL" d) (packed-doubles d)]) ctl-double-ds)
                              (map (fn [d] [(ctl-key "SMI" d) (packed-smis d)]) ctl-smi-ds))))
    ;; Everything below runs under an established frame scope, exactly as a
    ;; mounted application's render does — bound ONCE, outside every measured
    ;; window, so the scope itself is never charged to an arm.
    (binding [frame/*current-frame* fid]
      (build-rig! n coords?)
      (println ";; rf2-x0fe2 READ attribution — CLJS, node V8, :advanced")
      (println (gstring/format ";; debug-enabled? = %s   gc-exposed? = %s"
                       interop/debug-enabled?
                       (some? (gobj/get js/globalThis "gc"))))
      (println (gstring/format ";; node %s  V8 %s  pointer-compression=%s (Chrome ships it ON: a tagged slot is 4 B there, 8 B here)"
                       (.-node js/process.versions) (.-v8 js/process.versions)
                       (if (= 1 (gobj/getValueByKeys js/process "config" "variables"
                                                     "v8_enable_pointer_compression"))
                         "ON" "OFF")))
      (println (gstring/format ";; n=%d samples=%d (%d rounds x %d) warmup=%d calls + %d windows  base order=%s"
                       n samples rounds per-round warmup warm-windows
                       (if rev? "REVERSED" "forward")))
      (println (gstring/format ";; arm order ROTATES AND REFLECTS with the round (rf2-88pie); guard tolerance %s%%"
                       (.toFixed (* 100.0 tolerance) 0)))
      (println (gstring/format ";; registration = %s"
                       (if coords?
                         "COORD-CARRYING (default — the shape a real application registers)"
                         "COORD-LESS (RA_COORDS=0 — a labelled CONTROL; do not quote a figure from this run)")))
      (let [slot (registrar/lookup :sub (first (first (:qs @rig))))]
        (println (gstring/format ";; registered sub-meta coord keys = %s"
                         (pr-str (select-keys slot [:ns :file :line :column])))))
      ;; PROVENANCE. The binding arm is only measuring a binding if the frame
      ;; actually seals a generation — a nil generation would send
      ;; `call-with-frame-resolution` down its NO-binding branch and quietly
      ;; turn S3-CWFR into N-CWFRNOG.
      (let [g (:gen @rig)]
        (println (gstring/format ";; frame sealed generation = %s -> call-with-frame-resolution takes the %s"
                         (pr-str (some? g))
                         (if g "BINDING branch (S3-CWFR prices it)"
                             "*** NO-BINDING branch — S3-CWFR IS NOT PRICING A BINDING ***")))
        (when-not g
          (throw (ex-info "frame carries no generation; the binding arm is not measuring a binding" {}))))
      ;; Agreement gate: every reader must return what the writer wrote, or the
      ;; arms are not reading the same thing and nothing below is comparable.
      (let [{:keys [qs held raw db]} @rig
            expect  (get-in db [:items 7])
            rg-v    (deref (subs/subscribe (nth qs 7)))
            dr-v    (deref (nth held 7))
            raw-v   (adapter/read-container (nth raw 7))
            scope-v (frame/resolve-current-frame)
            agree?  (and (= expect rg-v dr-v raw-v) (= fid scope-v))]
        (println (gstring/format ";; agreement at site 7: want %s rgread %s deref %s rawdv %s | scope %s -> %s"
                         (pr-str expect) (pr-str rg-v) (pr-str dr-v) (pr-str raw-v)
                         (pr-str scope-v) (if agree? "AGREE" "*** DISAGREE ***")))
        (when-not agree?
          (throw (ex-info "arms disagree; measurement is meaningless"
                          {:want expect :rgread rg-v :deref dr-v :raw raw-v :scope scope-v}))))
      ;; rf2-f70iq — the retired predicate spelling is only a paired CONTROL
      ;; while it decides identically. Checked over the cases that discriminate
      ;; the branches: nil, a canonical-kind match, a canonical-kind mismatch, a
      ;; kindless map (object identity), and a copied canonical map.
      (let [spec   (adapter/current-adapter-spec)
            copy   (assoc spec :rf.bench/copied true)
            other  {:kind :rf.adapter/ra-not-installed}
            custom {:kind :custom}
            cases  [[nil nil] [spec nil] [nil spec] [spec spec] [spec copy]
                    [spec other] [other spec] [custom custom] [custom {:kind :custom}]]
            bad    (remove (fn [[a b]] (= (adapter/same-adapter? a b)
                                          (same-adapter-flat? a b)))
                           cases)]
        (println (gstring/format ";; predicate agreement over %d discriminating cases -> %s"
                         (count cases)
                         (if (empty? bad) "AGREE (H-SAMEFLAT is a control, not a variant)"
                             "*** DISAGREE ***")))
        (when (seq bad)
          (throw (ex-info "same-adapter? spellings disagree; H-SAMEFLAT is not a control"
                          {:cases (vec bad)}))))
      (let [plan (vec (concat
                        (map (fn [d] [(ctl-key "DBL" d) (arm-ctl "DBL" d) 1]) ctl-double-ds)
                        (map (fn [d] [(ctl-key "SMI" d) (arm-ctl "SMI" d) 1]) ctl-smi-ds)
                        [["NOOP"       arm-noop        n]]
                        [;; the read ladder
                         ["GETIN"      arm-getin       n]
                         ["RAWDV"      arm-rawdv       n]
                         ["DEREF"      arm-deref       n]
                         ["RGSUB"      arm-rgsub       n]
                         ["RGREAD"     arm-rgread      n]
                         ;; inside subscribe — the prefix ladder
                         ["S0-VAR"     arm-s0-var      n]
                         ["S0-SCOPE"   arm-s0-scope    n]
                         ["S1-CURFRM"  arm-s1-curfrm   n]
                         ["S1-EAGER"   arm-s1-eager    n]
                         ["S2-TGTID"   arm-s2-tgtid    n]
                         ["S3-CWFR"    arm-s3-cwfr     n]
                         ["S4-PRELOOK" arm-s4-prelook  n]
                         ;; the ref-count attach
                         ["RC-ATTACH"  arm-rc-attach   n]
                         ["RC-CAND"    arm-rc-cand     n]
                         ["RC-GUARD"   arm-rc-guard    n]
                         ["RC-SWAPID"  arm-rc-swapid   n]
                         ["RC-UPDIN"   arm-rc-updin    n]
                         ["RC-NEST"    arm-rc-nest     n]
                         ["RC-ASSOC"   arm-rc-assoc    n]
                         ["RC-EASSOC"  arm-rc-eassoc   n]
                         ;; the pre-node lookups
                         ["N-RESTGT"   arm-n-restgt    n]
                         ["N-GENREAD"  arm-n-genread   n]
                         ["N-FLUSH"    arm-n-flush     n]
                         ["N-BINDONLY" arm-n-bindonly  n]
                         ["N-CWFRNOG"  arm-n-cwfr-nogen n]
                         ["N-CWFRBIND" arm-n-cwfr-bind n]
                         ["N-CWFRGEN"  arm-n-cwfr-gen  n]
                         ["N-CALLTHUNK" arm-n-callthunk n]
                         ["N-NEWFN"    arm-n-newfn     n]
                         ["N-CWFRWRAP" arm-n-cwfr-wrap n]
                         ["N-CWFRRAW"  arm-n-cwfr-raw  n]
                         ["N-LOOKGEN"  arm-n-lookgen   n]
                         ["N-LOOKATOM" arm-n-lookatom  n]
                         ;; rf2-f70iq — the ambient reader's route, decomposed
                         ;; HERE because this is the plan in which S0-SCOPE reads
                         ;; the 264.0 B/read the bead is about. NONE of these arms
                         ;; publishes a routed hook; they only call ones that
                         ;; already exist, which is what keeps them from moving
                         ;; the arms above (the 2x2 and the chain ladder DO
                         ;; publish, and they live in RA_SUITE=hooks for that
                         ;; reason).
                         ["H-FVID"     arm-h-fvid      n]
                         ["H-CACHE"    arm-h-cache     n]
                         ["H-SPEC"     arm-h-spec      n]
                         ["H-SAMEH"    arm-h-sameh     n]
                         ["H-SAMEFLAT"  arm-h-sameflat   n]
                         ["H-IMPL"     arm-h-impl      n]
                         ["H-ROUTED"   arm-h-routed    n]]))
            plan (if rev? (vec (reverse plan)) plan)
            k    (count plan)
            ;; --- warm-up and calibration, one pass, nothing read -----------
            ;; A site reads 1.26x to 5.3x its settled value until it has run
            ;; several full-size windows (rf2-tb345). Charged to round 1 that
            ;; reads as allocation and — worse — as an arm whose figure moves
            ;; with where in the plan it sat, which the guard refuses.
            reps (mapv (fn [[label f _]]
                         (dotimes [_ warmup] (f))
                         (let [r (calibrate f 2000000)]
                           (dotimes [_ warm-windows] (measure f r 1 (fn [_ _ _] true)))
                           (println (gstring/format ";; warm %-12s reps=%-6d %d discarded windows"
                                            label r warm-windows))
                           r))
                       plan)
            ;; --- the measured rounds ---------------------------------------
            run  (reduce
                   (fn [acc [round j]]
                     (let [[label f per] (nth plan j)
                           r (measure f (nth reps j) per-round (advanced-by per))]
                       (-> acc
                           (update-in [:xs label] (fnil into []) (:xs r))
                           (update-in [:dropped label] (fnil + 0) (:dropped r))
                           (update-in [:unverified label] (fnil + 0) (:unverified r))
                           (update-in [:windows label] (fnil + 0) per-round)
                           (update :order conj {:arm         label
                                                :value       (:p50 r)
                                                :predecessor (:prev acc)
                                                :position    (count (:order acc))
                                                :round       round})
                           (assoc :prev label))))
                   {:xs {} :dropped {} :unverified {} :windows {} :order [] :prev nil}
                   (vec (for [round (range rounds)
                              j     (guard/slot-order k round)]
                          [round j])))
            res  (into {}
                       (map-indexed
                         (fn [i [label _ per]]
                           (let [pooled (summarise (get-in run [:xs label] []))
                                 rnd    (mapv :value (filter #(= label (:arm %)) (:order run)))]
                             [label (assoc pooled
                                           :per        per
                                           :reps       (nth reps i)
                                           :rounds     rnd
                                           :dropped    (get-in run [:dropped label] 0)
                                           :unverified (get-in run [:unverified label] 0)
                                           :windows    (get-in run [:windows label] 0))]))
                         plan))
            b    (fn [l] (:p50 (get res l)))
            ;; rf2-l3jv4 — the control's own verdict, computed ONCE from the
            ;; same p50s the CONTROLS block prints, and carried to the exit
            ;; code at the bottom. The bands live in
            ;; `re-frame.bench.calibration` and are expressed nowhere else,
            ;; so what is printed and what refuses cannot drift apart.
            cal  (calib/verdict
                   (mapv (fn [d] {:d d :smi (b (ctl-key "SMI" d)) :dbl (b (ctl-key "DBL" d))})
                         ctl-smi-ds)
                   (slope b (ctl-key "SMI" 100) 100 (ctl-key "SMI" 200) 200))
            noop (b "NOOP")
            ;; per-READ figures. Every ladder arm runs `n` inner iterations, so
            ;; the per-read figure is the per-call figure over n — which divides
            ;; the harness's fixed per-call overhead down by n as well.
            ;;
            ;; RAW is the arm as measured; NET is net of `NOOP`, the same
            ;; discipline `read_attribution.clj` applies to its own NOOP. NOOP
            ;; is the bare `keep!` loop — the inner-loop skeleton EVERY arm
            ;; shares — so the overhead it carries is common to all of them and
            ;; cancels in any subtraction between two arms. Every decomposition
            ;; below is quoted NET; the per-arm table above is RAW.
            pr*  (fn [l] (/ (b l) n))
            net* (fn [l] (/ (- (b l) noop) n))
            floor (/ noop n)]
        (doseq [[label _ per] plan]
          (let [r   (get res label)
                rnd (:rounds r)]
            (println (gstring/format ";; %-12s reps=%-6d %12s B/call  [%s – %s]  per-unit %10s B  (%d ok, %d dropped, %d UNVERIFIED of %d)  rounds p50 [%s – %s]"
                             label (:reps r) (fmt (:p50 r)) (fmt (:lo r)) (fmt (:hi r))
                             (fmt (/ (:p50 r) per)) (:accepted r) (:dropped r)
                             (:unverified r) (:windows r)
                             (fmt (apply min rnd)) (fmt (apply max rnd))))))
        (println ";;")
        ;; --- the controls, predicted vs measured --------------------------
        (println ";; CONTROLS — predicted vs measured at EVERY size, not merely a slope")
        (doseq [d ctl-double-ds]
          (let [m (b (ctl-key "DBL" d))
                p (+ 32 16 (* 8 d))]
            (println (gstring/format ";;   DBL D=%-6d %12s B/copy   predicted %7d  %+.2f%%"
                             d (fmt m) p (* 100.0 (/ (- m p) p))))))
        (doseq [{:keys [d smi dbl ratio regime]} (:pairs cal)]
          (println (gstring/format
                     ";;   SMI D=%-6d %12s B/copy   x%.4f of DBL D=%d (%s B)  -> %s"
                     d (fmt smi) ratio d (fmt dbl)
                     (case regime
                       :off     "8 B/slot, compression OFF"
                       :on      "4 B/slot, compression ON"
                       :neither "*** NEITHER — the SMI arm is not measuring a tagged-slot copy ***"))))
        (let [sm (slope b (ctl-key "DBL" 100) 100 (ctl-key "DBL" 200) 200)
              lg (slope b (ctl-key "DBL" 1000) 1000 (ctl-key "DBL" 10000) 10000)
              si (:slope cal)]
          (println (gstring/format
                     ";;   DBL slope, SMALL pair (100->200)     %.4f B/double  predicted 8.0000  %+.2f%%"
                     sm (* 100.0 (/ (- sm 8.0) 8.0))))
          (println (gstring/format
                     ";;   DBL slope, LARGE pair (1000->10000)  %.4f B/double  predicted 8.0000  %+.2f%%   (page-tail filler; the arms here are all SMALL objects)"
                     lg (* 100.0 (/ (- lg 8.0) 8.0))))
          ;; rf2-l3jv4 — the width is the one the RATIOS above selected, not
          ;; one read off this slope. Selecting it from the slope cannot see a
          ;; doubled slope at all: 16.1 simply answers "OFF" and is then
          ;; compared to 8.
          (println (gstring/format
                     ";;   SMI slope, SMALL pair (100->200)     %.4f B/slot    -> pointer compression %s   %s"
                     si (case (:regime cal)
                          :off     "OFF (8 B/slot, Node default)"
                          :on      "ON (4 B/slot, Chrome-like)"
                          :neither "UNREADABLE — the ratios above name no regime")
                     (if-let [off (:off-by cal)]
                       (gstring/format "%+.2f%% off that width" (* 100.0 off))
                       "no width to check it against"))))
        (println ";;")
        ;; --- the floor -----------------------------------------------------
        (println ";; THE FLOOR — NOOP is the bare keep! loop, PREDICTED 0 B")
        (println (gstring/format ";;   NOOP %s B/call over %d inner iterations = %s B/read"
                         (fmt noop) n (fmt3 floor)))
        (println ";;   FLOOR SWEEP — a REAL per-call allocation is rep-INDEPENDENT; a floor is")
        (println ";;   a roughly fixed number of bytes per WINDOW and moves with the window size.")
        (doseq [r [256 512 1024 2048 4000]]
          (let [m (measure arm-noop r 9 (advanced-by n))]
            (println (gstring/format ";;     NOOP reps=%-6d %10s B/call [%s – %s]  %10s B/window  %8s B/read  (%d UNVERIFIED of 9)"
                             r (fmt (:p50 m)) (fmt (:lo m)) (fmt (:hi m))
                             (fmt (* r (:p50 m))) (fmt3 (/ (:p50 m) n))
                             (:unverified m)))))
        (println ";;")
        ;; --- rf2-ktrvw: what a thunk-dominated arm actually measures ----------
        ;;
        ;; Five arms here hand a freshly-allocated thunk to another function,
        ;; and every one of them reads TWO values across windows: a settled one
        ;; and a high one, bit-identical, with nothing between. The bead was
        ;; opened on the suspicion that this is escape analysis eliding the
        ;; subject in some windows. `N-NEWFN` decides it, by pricing the closure
        ;; with nothing else in the arm at all.
        (println ";; rf2-ktrvw — THE THUNK-DOMINATED ARMS ARE BIMODAL, and the mode is the CLOSURE")
        (let [closure (net* "N-NEWFN")
              steps   (volatile! [])]
          (println (gstring/format ";;   N-NEWFN  one closure per inner iteration, nothing else:  %s B/read net" (fmt closure)))
          (println (gstring/format ";;   N-CALLTHUNK - N-NEWFN = %s B/read   predicted 0 (a vreset! and a call)"
                           (fmt (- (net* "N-CALLTHUNK") closure))))
          (println ";;   arm           settled      high      step  = closures")
          (doseq [l ["N-NEWFN" "N-CALLTHUNK" "N-CWFRNOG" "N-CWFRBIND" "N-CWFRGEN" "N-CWFRRAW"]]
            (let [r    (get res l)
                  lo   (/ (- (:lo r) noop) n)
                  hi   (/ (- (:hi r) noop) n)
                  step (- hi lo)]
              (vswap! steps conj step)
              (println (gstring/format ";;     %-12s %9s %9s %9s    %s"
                               l (fmt lo) (fmt hi) (fmt step)
                               (if (pos? closure)
                                 (gstring/format "%.2f" (/ step closure))
                                 "n/a")))))
          ;; The point the table has to carry: the step is the SAME ABSOLUTE
          ;; SIZE on arms whose totals differ by 75%, so it is additive and
          ;; belongs to closure creation rather than to any arm's own subject.
          (let [ss (filterv pos? @steps)]
            (when (seq ss)
              (println (gstring/format ";;   step across those arms: %s – %s B/read  (one closure = %s)"
                               (fmt (apply min ss)) (fmt (apply max ss)) (fmt closure)))))
          (println ";;   READ THIS AS: the step is ONE closure, the same absolute size on arms whose")
          (println ";;   totals differ — additive, so it is closure CREATION and not any arm's")
          (println ";;   subject. `N-NEWFN` carries it too, and `N-NEWFN` is nothing BUT a closure,")
          (println ";;   so no re-shaping of a thunk arm can remove it: the arm would have to stop")
          (println ";;   allocating the thing it exists to price. It appears in the FORWARD plan")
          (println ";;   and the REVERSED one alike, so it is NOT a position effect — which is why")
          (println ";;   the guard's phase factor catches it only when a third happens to straddle")
          (println ";;   both modes, and why tuning that factor would be the wrong repair.")
          (println ";;   CONSEQUENCE: a thunk-dominated arm is quotable as its RANGE and as an")
          (println ";;   UPPER BOUND at the high mode, never as a p50 alone. The binding figure")
          (println ";;   this file publishes is N-BINDONLY, which allocates no closure at all."))
        (println ";;")
        ;; --- the read ladder ------------------------------------------------
        (println ";; THE READ LADDER — bytes per READ (CLJS / node V8; NOT JVM, NOT Chrome)")
        (println ";;   app-db is held STILL, so DEREF is a genuine cache HIT (the CLJS spine")
        (println ";;   caches; the JVM plain-atom adapter recomputes on every deref, so the")
        (println ";;   JVM DEREF arm is NOT the same measurement — see the ns docstring).")
        (doseq [[lbl l] [["GETIN     the application's own (get-in db [:items i])" "GETIN"]
                         ["RAWDV     + a bare substrate derived value"            "RAWDV"]
                         ["DEREF     + re-frame's signal graph, cache HIT"        "DEREF"]
                         ["RGSUB     subscribe, no deref"                         "RGSUB"]
                         ["RGREAD    @(subscribe q) — the whole reader"           "RGREAD"]]]
          (println (gstring/format ";;   %-52s raw %9s   net %9s B/read%s"
                           lbl (fmt (pr* l)) (fmt (net* l))
                           (if (<= (net* l) floor)
                             "   <= FLOOR: an UPPER BOUND, not a measurement" ""))))
        (println ";;")
        ;; --- inside subscribe -----------------------------------------------
        (println ";; rf2-j8ls2 — INSIDE subscribe's cache-HIT path (prefix ladder)")
        (println ";;   all figures NET of NOOP; shares are of RGSUB (= subscribe, cache hit)")
        (let [rgsub (net* "RGSUB")
              pct   (fn [v] (* 100.0 (/ v rgsub)))]
          (doseq [[lbl v] [["  of S1: the var read      (S0-VAR)" (net* "S0-VAR")]
                           ["  of S1: + the CLJS context hook (S0-SCOPE-S0-VAR)"
                            (- (net* "S0-SCOPE") (net* "S0-VAR"))]
                           ["require-current-frame! (S1)"       (net* "S1-CURFRM")]
                           ["+ frame-target->id     (S2-S1)"    (- (net* "S2-TGTID") (net* "S1-CURFRM"))]
                           ["+ call-with-frame-res  (S3-S2)"    (- (net* "S3-CWFR") (net* "S2-TGTID"))]
                           ["+ frame + cache get    (S4-S3)"    (- (net* "S4-PRELOOK") (net* "S3-CWFR"))]
                           ["+ the REF-COUNT ATTACH (RGSUB-S4)" (- (net* "RGSUB") (net* "S4-PRELOOK"))]
                           ["= subscribe            (RGSUB)"    rgsub]
                           ["+ the deref            (RGREAD-RGSUB)" (- (net* "RGREAD") (net* "RGSUB"))]
                           ["= the whole read       (RGREAD)"   (net* "RGREAD")]]]
            (println (gstring/format ";;   %-40s %10s B/read   %6.1f%%" lbl (fmt v) (pct v)))))
        (println ";; the retired spellings, as paired controls:")
        (doseq [[lbl v] [["S1-EAGER (retired eager payload)"       (net* "S1-EAGER")]
                         ["S1-CURFRM (shipped, deferred)"          (net* "S1-CURFRM")]
                         ["rf2-a8bw0 saves (S1-EAGER - S1-CURFRM)" (- (net* "S1-EAGER") (net* "S1-CURFRM"))]
                         ["N-CWFRWRAP (retired target wrapper)"    (net* "N-CWFRWRAP")]
                         ["N-CWFRRAW  (shipped, carried target)"   (net* "N-CWFRRAW")]
                         ["rf2-8gb3t saves (WRAP - RAW)"           (- (net* "N-CWFRWRAP") (net* "N-CWFRRAW"))]
                         ["  ... predicted by N-RESTGT"            (net* "N-RESTGT")]]]
          (println (gstring/format ";;   %-40s %10s B/read" lbl (fmt v))))
        (println ";; the attach, part by part (RC-CAND is the SHIPPED form):")
        (doseq [l ["RC-ATTACH" "RC-CAND" "RC-GUARD" "RC-SWAPID" "RC-UPDIN"
                   "RC-NEST" "RC-ASSOC" "RC-EASSOC"]]
          (println (gstring/format ";;   %-40s %10s B/read" l (fmt (net* l)))))
        (doseq [[lbl v] [["update-in cost (ATTACH-GUARD)"
                          (- (net* "RC-ATTACH") (net* "RC-GUARD"))]
                         ["irreducible persistent write (ASSOC+EASSOC)"
                          (+ (net* "RC-ASSOC") (net* "RC-EASSOC"))]
                         ["ATTACH - CAND (what the rewrite saves)"
                          (- (net* "RC-ATTACH") (net* "RC-CAND"))]]]
          (println (gstring/format ";;   %-40s %10s B/read" lbl (fmt v))))
        (println ";; rf2-ncjyt / rf2-ezwnl — the pre-node lookups:")
        (doseq [l ["N-RESTGT" "N-GENREAD" "N-FLUSH" "N-BINDONLY" "N-CWFRNOG"
                   "N-CWFRBIND" "N-CWFRGEN" "N-CALLTHUNK" "N-LOOKGEN" "N-LOOKATOM"]]
          (println (gstring/format ";;   %-40s %10s B/read%s"
                           l (fmt (net* l))
                           (if (<= (net* l) floor) "   <= FLOOR (upper bound)" ""))))
        (println (gstring/format ";;   %-40s %10s B/read"
                         "rf2-ezwnl key vector (LOOKGEN-LOOKATOM)"
                         (fmt (- (net* "N-LOOKGEN") (net* "N-LOOKATOM")))))
        (println ";;   ^ NOT on the cache-HIT read path: subscribe's hit branch performs no")
        (println ";;     registrar/lookup. It runs per dispatch, per fx, per cofx and per")
        (println ";;     subscribe MISS. Quoted here because rf2-ezwnl's reopen condition names it.")
        (println ";;")
        ;; --- rf2-f70iq — WHERE the ambient reader's bytes go -----------------
        (let [rng (fn [l]
                    (let [rnd (:rounds (get res l))]
                      [(/ (- (apply min rnd) noop) n) (/ (- (apply max rnd) noop) n)]))
              row (fn [lbl v l]
                    (let [[lo hi] (if l (rng l) [v v])]
                      (println (gstring/format ";;   %-46s %10s B/read   rounds [%s – %s]%s"
                                       lbl (fmt v) (fmt lo) (fmt hi)
                                       (if (<= v floor)
                                         "   <= FLOOR (upper bound)" "")))))]
          (println ";; rf2-f70iq — the CLJS ambient-frame reader, STEP BY STEP")
          (println ";;   S0-SCOPE - S0-VAR says the React-context consult costs bytes and says")
          (println ";;   nothing about WHERE. These arms price each step of the route in the")
          (println ";;   same process against the same installed adapter. All NET of NOOP.")
          (row "S0-SCOPE  the shipped reader, whole"       (net* "S0-SCOPE") "S0-SCOPE")
          (row "S0-VAR    the dynamic-var tier alone"      (net* "S0-VAR")   "S0-VAR")
          (row "  = the CLJS-only consult (SCOPE - VAR)"
               (- (net* "S0-SCOPE") (net* "S0-VAR")) nil)
          (println ";;   and the route, part by part:")
          (row "H-FVID    frame-value->id on the result"   (net* "H-FVID")   "H-FVID")
          (row "H-CACHE   late-bind/get-fn-cached, lookup only" (net* "H-CACHE") "H-CACHE")
          (row "H-SPEC    current-adapter-spec alone"      (net* "H-SPEC")   "H-SPEC")
          (row "H-IMPL    function-component-current-frame" (net* "H-IMPL")  "H-IMPL")
          (row "H-SAMEH   same-adapter?, two HELD maps  (SHIPPED)" (net* "H-SAMEH") "H-SAMEH")
          (row "H-SAMEFLAT the CANDIDATE flat re-spelling" (net* "H-SAMEFLAT") "H-SAMEFLAT")
          (row "  = the per-call IIFE (SAMEH - SAMEFLAT)"
               (- (net* "H-SAMEH") (net* "H-SAMEFLAT")) nil)
          (row "H-ROUTED  the SHIPPED hook: lookup + routed call" (net* "H-ROUTED") "H-ROUTED")
          (println ";;")
          ;; BUDGET. H-ROUTED is a strict superset of the parts above it — the
          ;; hook lookup, the adapter-state read, the predicate, the apply, the
          ;; impl. The residual is therefore `(apply impl-fn args)` at
          ;; `route-hook!`'s OWN site: ONE site shared by every routed hook in
          ;; the bundle, so its callee is POLYMORPHIC and `cljs.core/apply`
          ;; cannot be inlined there. Measured at a private site with a single
          ;; callee it is free — which is exactly why it is not measured that way.
          (let [budget (+ (net* "H-CACHE") (net* "H-SPEC") (net* "H-SAMEH") (net* "H-IMPL"))
                meas   (net* "H-ROUTED")
                resid  (- meas budget)]
            (println (gstring/format
                       ";;   BUDGET — H-CACHE + H-SPEC + H-SAMEH + H-IMPL = %s vs H-ROUTED %s"
                       (fmt budget) (fmt meas)))
            (println (gstring/format
                       ";;   residual %s B/read (%s of H-ROUTED) — `apply` at route-hook!'s own,"
                       (fmt resid)
                       (if (zero? meas) "n/a"
                           (gstring/format "%.1f%%" (* 100.0 (/ resid meas))))))
            (println ";;   callee-POLYMORPHIC site — one site, every routed hook in the bundle.")
            (println (gstring/format
                       ";;   and S0-SCOPE - H-ROUTED - H-FVID = %s B/read left over"
                       (fmt (- (net* "S0-SCOPE") (net* "H-ROUTED") (net* "H-FVID"))))))
          (println ";;")
          (let [whole (- (net* "S0-SCOPE") (net* "S0-VAR"))
                pctof (fn [v] (if (zero? whole) "n/a"
                                  (gstring/format "%.1f%%" (* 100.0 (/ v whole)))))
                iife  (- (net* "H-SAMEH") (net* "H-SAMEFLAT"))
                appl  (- (net* "H-ROUTED") (net* "H-CACHE") (net* "H-SPEC")
                         (net* "H-SAMEH") (net* "H-IMPL"))]
            (println (gstring/format
                       ";;   VERDICT — of the %s B/read CLJS-only consult:" (fmt whole)))
            (println (gstring/format ";;     same-adapter?, SHIPPED                      %10s  %s"
                             (fmt (net* "H-SAMEH")) (pctof (net* "H-SAMEH"))))
            (println (gstring/format ";;       ... its expression-position IIFE, per call%10s  %s"
                             (fmt iife) (pctof iife)))
            (println (gstring/format ";;       ... the CANDIDATE flat re-spelling leaves %10s  %s"
                             (fmt (net* "H-SAMEFLAT")) (pctof (net* "H-SAMEFLAT"))))
            (println (gstring/format ";;     apply at route-hook!'s polymorphic site     %10s  %s"
                             (fmt appl) (pctof appl)))
            (println ";;   and, at or under the floor, contributing nothing:")
            (println (gstring/format ";;     the adapter-state read (current-adapter-spec) %9s  %s"
                             (fmt (net* "H-SPEC")) (pctof (net* "H-SPEC"))))
            (println (gstring/format ";;     the hook lookup (get-fn-cached)              %10s  %s"
                             (fmt (net* "H-CACHE")) (pctof (net* "H-CACHE"))))
            (println (gstring/format ";;     the impl itself (function-component-…)       %10s  %s"
                             (fmt (net* "H-IMPL")) (pctof (net* "H-IMPL"))))
            (println (gstring/format ";;     frame-value->id on the result                %10s  %s"
                             (fmt (net* "H-FVID")) (pctof (net* "H-FVID"))))
            (println ";;   route-hook! publishes ~11 hook keys and EVERY call through any of")
            (println ";;   them carries this shape, so these are per ROUTED-HOOK CALL figures,")
            (println ";;   not per subscribe. Both terms are therefore paid by ambient dispatch")
            (println ";;   and rf/current-frame-id as well, and once per PUBLISHING adapter:")
            (println ";;   an inactive adapter loaded in the same bundle adds a chain link, and")
            (println ";;   a link is another same-adapter? plus another apply.")
            (println ";;   NEITHER is fixed here. This is a measurement: the flat re-spelling is")
            (println ";;   local and contract-free, the arity spelling of route-hook!'s closure")
            (println ";;   changes every adapter's routed hooks and is an operator call."))
          (println ";;"))
        ;; --- THE HEADLINE ---------------------------------------------------
        (println ";; ==== THE TERM THIS BEAD IS ABOUT ====")
        (println ";;   JVM: the dynamic `binding` of registrar/*generation* inside")
        (println ";;   call-with-frame-resolution reads ~760 B/call and is ~41-46% of")
        (println ";;   subscribe's cache-HIT allocation. CLJS `binding` is let + set! +")
        (println ";;   try/finally restore. PREDICTION, stated before the run: 0 B/read.")
        (let [exact      (- (net* "N-CWFRBIND") (net* "N-CWFRGEN"))
              jvm-style  (- (net* "S3-CWFR") (net* "S2-TGTID") (net* "N-CWFRNOG"))
              budget     (- (net* "N-CWFRRAW") (net* "N-CALLTHUNK") (net* "N-GENREAD"))
              inline-err (- (net* "N-CWFRNOG") (net* "N-BINDONLY"))
              fidelity   (- (net* "N-CWFRBIND") (net* "N-CWFRRAW"))
              standalone (net* "N-BINDONLY")
              rgsub      (net* "RGSUB")
              ;; SYMMETRY CHECK, and it is load-bearing (rf2-x0fe2). The pair is
              ;; only symmetric while BOTH halves actually allocate their thunk.
              ;; They do not always: at n=30 V8 elides `cwfr-nobind`'s thunk
              ;; while the `binding`'s try/finally blocks the same elision on the
              ;; bind side, and the difference then reads one whole closure —
              ;; 64.0 B/read — as if it were the binding. So the no-bind half's
              ;; thunk is CHECKED against `N-CALLTHUNK`, which is one escaping
              ;; thunk and nothing else.
              nobind-thunk (- (net* "N-CWFRGEN") (net* "N-GENREAD"))
              ct           (net* "N-CALLTHUNK")
              symmetric?   (< (js/Math.abs (- nobind-thunk ct))
                              (max 1.0 (* 0.25 (js/Math.abs ct))))]
          (println ";;   THE PRIMARY FIGURE is N-BINDONLY: it contains no thunk, so no escape")
          (println ";;   analysis can move it, and it needs no subtraction at all.")
          (println (gstring/format ";;   N-BINDONLY standalone                        %10s B/read%s"
                           (fmt standalone)
                           (if (<= standalone floor) "   <= FLOOR (upper bound)" "")))
          (println (gstring/format ";;   BUDGET (N-CWFRRAW - N-CALLTHUNK - N-GENREAD) %10s B/read%s"
                           (fmt budget)
                           (if (<= (js/Math.abs budget) (max 1.0 (* 20.0 floor)))
                             "   — cwfr's cost is FULLY accounted without a binding" "")))
          (println (gstring/format ";;   SYMMETRIC pair (N-CWFRBIND - N-CWFRGEN)      %10s B/read%s"
                           (fmt exact)
                           (if (<= (js/Math.abs exact) (* 2.0 floor))
                             "   <= FLOOR (upper bound)" "")))
          (println (gstring/format ";;     symmetry check: the no-bind half's thunk reads %s B/read against N-CALLTHUNK's %s -> %s"
                           (fmt nobind-thunk) (fmt ct)
                           (if symmetric?
                             "SYMMETRIC, the pair is quotable"
                             "*** ASYMMETRIC — V8 elided one half's thunk; THIS PAIR MAY NOT BE QUOTED ***")))
          (when symmetric?
            (println (gstring/format ";;   N-BINDONLY and the pair agree to             %10s B/read   (instrument floor %s B/read)"
                             (fmt (js/Math.abs (- standalone exact))) (fmt3 floor))))
          (println (gstring/format ";;   -> the binding is %.2f%% of subscribe's cache-HIT allocation here"
                           (* 100.0 (/ (max 0.0 standalone) rgsub))))
          (println ";;")
          (println ";;   FIDELITY OF THE RE-SPELLING — cwfr-bind is call-with-frame-resolution's")
          (println ";;   body verbatim, so the arm that calls it must agree with the arm that")
          (println ";;   calls the shipped function. If these diverge the pair above is measuring")
          (println ";;   something else and may not be quoted.")
          (println (gstring/format ";;     N-CWFRBIND (re-spelled) %s   N-CWFRRAW (shipped) %s   delta %s B/read"
                           (fmt (net* "N-CWFRBIND")) (fmt (net* "N-CWFRRAW")) (fmt fidelity)))
          (println ";;")
          (println ";;   TWO SUBTRACTIONS THAT LOOK RIGHT AND ARE NOT — both are on record")
          (println ";;   because each one, published, would have been a precise wrong number.")
          (println (gstring/format ";;     (a) the JVM harness's own (S3-S2-N-CWFRNOG)      %10s B/read"
                           (fmt jvm-style)))
          (println (gstring/format ";;         a nil target SHORT-CIRCUITS the generation read, so this residual")
                   )
          (println (gstring/format ";;         is the generation read (N-GENREAD %s B/read), not the binding."
                           (fmt (net* "N-GENREAD"))))
          (println ";;         On the JVM the binding was ~760 B and that conflation was 2-5%,")
          (println ";;         so it did not matter there. Here it would be the whole answer.")
          (println ";;     (b) an INLINE re-spelling of cwfr's body — MEASURED at 64.1 B/read")
          (println ";;         against standalone N-BINDONLY's 0.1 in this harness's own")
          (println ";;         development, which is how it was caught. `cwfr` takes a THUNK and")
          (println ";;         its caller allocates one per call that ESCAPES; an inline")
          (println ";;         re-spelling allocates one that does not, and V8 elides what it")
          (println ";;         can prove never escapes. So the subtraction prices the CLOSURE:")
          (println (gstring/format ";;           N-CALLTHUNK (one escaping thunk, nothing else) %s B/read"
                           (fmt (net* "N-CALLTHUNK"))))
          (println (gstring/format ";;           N-CWFRNOG   (cwfr, nil target, no gen read)   %s B/read"
                           (fmt (net* "N-CWFRNOG"))))
          (println (gstring/format ";;         and N-CWFRNOG - N-BINDONLY reconstructs it at %s B/read."
                           (fmt inline-err)))
          (println ";;         Same error as (a), opposite sign.")
          (println ";;     (c) the SYMMETRIC PAIR itself, when V8 elides one half's thunk.")
          (println ";;         The bind half's try/finally blocks an elision the no-bind half")
          (println ";;         gets, and the difference then reads one whole closure AS the")
          (println ";;         binding. Seen at n=30, absent at n=300. That is what the")
          (println ";;         symmetry check above exists to refuse — and it is why the")
          (println ";;         PRIMARY figure is N-BINDONLY, which carries no thunk at all."))
        (println ";;")
        ;; --- THE CLJS RANKING, which is the point of the exercise -----------
        (println ";; ==== THE CLJS RANKING of subscribe's cache-HIT allocation ====")
        (let [rgsub (net* "RGSUB")
              terms [["the ref-count attach (RGSUB - S4-PRELOOK)"
                      (- (net* "RGSUB") (net* "S4-PRELOOK"))]
                     ["require-current-frame! (S1-CURFRM)"
                      (net* "S1-CURFRM")]
                     ["the frame + cache get (S4 - S3)"
                      (- (net* "S4-PRELOOK") (net* "S3-CWFR"))]
                     ["call-with-frame-resolution (S3 - S2)"
                      (- (net* "S3-CWFR") (net* "S2-TGTID"))]
                     ["frame-target->id (S2 - S1)"
                      (- (net* "S2-TGTID") (net* "S1-CURFRM"))]]]
          (doseq [[lbl v] (sort-by (comp - second) terms)]
            (println (gstring/format ";;   %-52s %10s B/read  %6.1f%%"
                             lbl (fmt v) (* 100.0 (/ v rgsub)))))
          (println ";;   and call-with-frame-resolution splits:")
          (doseq [[lbl v] [["the generation read (N-GENREAD)" (net* "N-GENREAD")]
                           ["the escaping thunk (N-CALLTHUNK)" (net* "N-CALLTHUNK")]
                           ["THE BINDING (N-BINDONLY, no thunk to elide)"
                            (net* "N-BINDONLY")]
                           ["the late-bind flush consult (N-FLUSH)" (net* "N-FLUSH")]]]
            (println (gstring/format ";;     %-50s %10s B/read  %6.1f%%%s"
                             lbl (fmt v) (* 100.0 (/ v rgsub))
                             (if (<= v floor) "   <= FLOOR" "")))))
        (println ";;")
        ;; --- arm order, LAST, and it governs everything above ---------------
        ;; The figures are printed and the refusal is about what may be QUOTED,
        ;; not about throwing the measurement away — so the exit code carries it.
        ;;
        ;; rf2-hydpy: a refusal is no longer the last word. Each refused arm is
        ;; re-measured across a reps ladder (the rf2-tmzie sweep), and a
        ;; refusal every one of whose arms provably reads the instrument's
        ;; per-WINDOW floor — not its own subject — is downgraded to a
        ;; CERTIFIED-AT-FLOOR quote: the arm's worst round as an upper bound,
        ;; never its p50. Any arm the sweep cannot attribute, and any
        ;; :unchecked refusal (a plan defect, which no sweep can excuse),
        ;; keeps the run at exit 2 exactly as before.
        (let [v (guard/verdict (:order run) {:tolerance tolerance})]
          (doseq [line (guard/report-lines v "the per-arm figure, one p50 per round")]
            (println line))
          (when (:refuse? v)
            (let [arms-with    (fn [k]
                                 (->> (:arms v)
                                      (filter (fn [[_ a]]
                                                (some (fn [[_ r]] (= k (:status r)))
                                                      (:factors a))))
                                      (map key)
                                      sort
                                      vec))
                  unchecked    (arms-with :unchecked)
                  contaminated (arms-with :contaminated)
                  by-label     (into {} (map (fn [[label f per]] [label [f per]])) plan)
                  refuse!      (fn [extra-lines]
                                 (println ";;")
                                 (println ";; ==== ARM ORDER: THESE FIGURES ARE NOT REPORTABLE ====")
                                 (println (str ";;   at least one arm reads differently for what preceded it, or for where "
                                               "in the run"))
                                 (println (str ";;   it was measured (rf2-88pie). The table above stands as raw data; "
                                               "nothing in it"))
                                 (println ";;   may be quoted.")
                                 (doseq [l extra-lines] (println l))
                                 (set! (.-exitCode js/process) 2))]
              (println ";;")
              (println ";; ==== FLOOR ATTRIBUTION (rf2-hydpy) — is each refused arm reading its subject, or the instrument? ====")
              (println ";;   each refused arm re-measured across window sizes derived from its own reps.")
              (println ";;   a REAL per-call cost is rep-INDEPENDENT in B/call; a per-WINDOW floor falls")
              (println ";;   as the window grows. attribution demands STRICTLY MORE than half the full")
              (println ";;   per-window collapse (ratio > span/2), which no recorded per-call signature reaches.")
              (if (seq unchecked)
                (refuse! [(str ";;   FLOOR ATTRIBUTION does not apply: "
                               (str/join ", " unchecked)
                               " refused as UNCHECKED — a plan defect, which no sweep can excuse.")])
                (let [verdicts
                      (mapv
                        (fn [label]
                          (let [[f per] (get by-label label)
                                reps*   (:reps (get res label))
                                ladder  (->> [(/ reps* 8) (/ reps* 4) (/ reps* 2) reps*]
                                             (map #(js/Math.ceil %))
                                             (map #(max 1 %))
                                             distinct
                                             vec)
                                points  (mapv (fn [r]
                                                (let [m (measure f r 9 (advanced-by per))]
                                                  {:reps r :p50 (:p50 m)
                                                   :unverified (:unverified m)}))
                                              ladder)
                                fv      (floor-verdict points)
                                bound   (apply max (:rounds (get res label)))]
                            (println (gstring/format ";;   %-12s worst round p50 %s B/call (%s per unit)"
                                             label (fmt bound) (fmt (/ bound per))))
                            (doseq [{:keys [reps p50 unverified]} points]
                              (println (gstring/format ";;     reps=%-6d %12s B/call  %14s B/window  (%d UNVERIFIED of 9)"
                                               reps (fmt p50) (fmt (* reps p50)) unverified)))
                            (println (gstring/format ";;     -> %s: %s"
                                             (case (:status fv)
                                               :floor         "FLOOR"
                                               :per-call      "PER-CALL"
                                               "INDETERMINATE")
                                             (:why fv)))
                            (assoc fv :label label :bound bound :per per)))
                        contaminated)]
                  (if (every? #(= :floor (:status %)) verdicts)
                    (do
                      (println ";;")
                      (println ";; ==== ARM ORDER: EVERY REFUSAL IS THE INSTRUMENT'S OWN FLOOR ====")
                      (println ";;   the guard's refusals above are attributed, arm by arm, to a per-WINDOW")
                      (println ";;   floor by the sweep. Each arm below is CERTIFIED AT THE FLOOR: quotable")
                      (println ";;   ONLY as its upper bound, never as a measured p50. Arms the guard did")
                      (println ";;   not refuse are unaffected, and remain quotable as measured.")
                      (doseq [{:keys [label bound per]} verdicts]
                        (println (gstring/format ";;   %-12s <= %s B/call  (%s per unit)  — an UPPER BOUND at the instrument's floor"
                                         label (fmt bound) (fmt (/ bound per))))))
                    (refuse! [(str ";;   FLOOR ATTRIBUTION failed on: "
                                   (str/join ", "
                                             (keep #(when (not= :floor (:status %)) (:label %))
                                                   verdicts))
                                   " — what those arms read is not the per-window floor.")])))))))
        ;; rf2-l3jv4 — the CONTROL's refusal, independent of the arm order's.
        ;; The order guard asks whether an arm's figure moved with where in
        ;; the plan it sat; this asks whether the instrument was measuring a
        ;; tagged-slot copy at all. Until this bead the answer only ever
        ;; printed, so a run could report `NEITHER` above and still exit 0
        ;; under a `VERDICT: reportable`.
        (doseq [line (calib/report-lines cal)]
          (println line))
        (when (:refuse? cal)
          (set! (.-exitCode js/process) 2))
        (let [total (reduce + (map (fn [[l _ _]] (:unverified (get res l))) plan))
              wins  (reduce + (map (fn [[l _ _]] (:windows (get res l))) plan))]
          (println ";;")
          (println (gstring/format ";; READ-BACK: %d UNVERIFIED of %d windows across the whole plan"
                           total wins))
          (when (pos? total)
            (println ";;   a window is UNVERIFIED when the arm did not advance the counter by")
            (println ";;   exactly `per x reps` — i.e. it did not do the work it was credited with.")
            (set! (.-exitCode js/process) 2)))
        (println (gstring/format ";; sink %s" @sink))))))
