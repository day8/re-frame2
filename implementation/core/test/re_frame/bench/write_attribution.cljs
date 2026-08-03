(ns re-frame.bench.write-attribution
  "rf2-jr76s — WHERE do the bytes of a NARROW WRITE go?

  B8 (rf2-lcsjg) measured, in a browser, that changing ONE cell of a
  300-cell grid costs 478,787 bytes, of which **457,181 — 95.5% — is the
  WRITE LEG**: `frame/replace-app-db!` plus the subscription graph
  re-evaluating all 300 `:b6/cell` subscriptions to discover that 299 of
  them did not change. That figure is on record; nothing decomposes it.

  A number you cannot decompose you cannot attack, so this namespace
  decomposes it.

  ## Why this runs in NODE and not in a browser

  The published figure is a browser reading. The two things that make it
  what it is are the ENGINE (V8) and the BUILD (`:advanced` with
  `goog.DEBUG false`, so the Spec 009 trace seam is dead code). Node is
  the same V8 and the build here is the same `:advanced` +
  `goog.DEBUG false`, so the DCE profile — which is what decides whether
  the trace layer is in the picture at all — is identical.

  Node also gives one thing the browser cannot: `process.memoryUsage()`
  reports V8's used-heap counter UNQUANTISED. Chrome's `performance.memory`
  is bucketed to 100 KB unless `--enable-precise-memory-info` is passed,
  which is why B8 needed that flag; here there is no flag and no bucket.

  What does NOT transfer is the HOST: no React, no DOM, no commit. That is
  deliberate — the write leg B8 isolated is exactly the part that happens
  BEFORE React is involved (B8's `hc → h1` step, `((:write! arm) i val)`),
  and that is all this namespace measures.

  ## The instrument

  Same method as B8: if no garbage collection runs between two readings of
  V8's used-heap counter, the difference is the bytes allocated in between,
  garbage included, because nothing has reclaimed it yet.

  Here every SAMPLE is bracketed by an explicit `global.gc()` (node
  `--expose-gc`), and the sample is sized so its whole allocation fits in
  one nursery. A sample whose counter FELL is discarded and counted. Each
  arm reports p50 with the full range across accepted samples, per the
  standing rule that ranges are quoted and overlapping ranges mean
  INDISTINGUISHABLE.

  ## The arm order is a MEASURED property of this run, not an assumption

  This harness is where `rf2-88pie`'s fault was FOUND: the SMI control read
  16.1052 B/slot with the plan run forwards and 8.0027 with it run
  backwards, and it was caught only because somebody happened to run both.
  Running one order and reporting the answer is exactly the thing that
  cannot be checked, and until `rf2-om73r` that is what this file did — a
  single pass over the plan, each arm measured once, in one place, after one
  predecessor. `WA_ORDER=rev` gave a second reading in a SECOND PROCESS
  whose numbers nothing here ever joined up.

  So the plan now runs in `WA_ROUNDS` rounds and the arm order ROTATES AND
  REFLECTS with the round (`order-guard/slot-order`). A bare cyclic rotation
  would not do: arm `a` sits at slot `(a - r) mod n`, so its predecessor is
  `(a - 1) mod n` in every round and only the round seam differs. Reflecting
  on odd rounds replaces every `a -> a+1` adjacency with `a -> a-1`, so
  every arm is measured after two different predecessors and at spread
  positions in the run.

  `re-frame.bench.order-guard` then partitions each arm's per-round figures
  by WHAT RAN BEFORE IT and by WHERE IN THE RUN it sat, applies the house
  rule — overlapping ranges are indistinguishable — and REFUSES the run
  (exit code 2) if either factor separates an arm. It is the same rule as
  `implementation/freehand/test/re_frame/freehand/bench/order_guard.cjs`,
  expressed twice because a JVM harness and a `core` CLJS harness can reach
  neither that file nor its runtime; both copies replay the same recorded
  fixtures in their self-tests, which is what keeps them honest.

  ## Warm-up, because position beats adjacency

  The same study measured one control in plain node over sixteen
  consecutive windows, nothing else varying: `42.32` then six windows at
  `10.3` then `8.12` for ever. The FIRST window read 5.3x the settled value
  and the next six read +27%. So `WA_WARM_WINDOWS` full-size windows per arm
  are run and thrown away before any round is measured, on top of
  `WA_WARMUP` bare calls and the calibration probe. Under-warming does not
  produce a slightly-off number; it produces a number that moves with where
  in the plan the arm sat, which is precisely what the guard refuses.

  ## The control

  A control whose size is asserted rather than checked has already been
  wrong twice on this surface (B7's `'x'.repeat(n)` string that read as six
  kilobytes; B8's `8 B/double` prediction that measured 16.002). So the
  control here is read as a SLOPE across sizes, which cancels every constant
  and every header:

    `.slice()` of a PACKED double-element JS array of D elements allocates a
    `FixedDoubleArray` of D unboxed 8-byte slots plus a fixed header. V8
    never pointer-compresses a double, so the slope is **8.000 B/element
    exactly**, independent of the pointer-compression regime the node build
    happens to use — which a `FixedArray`-of-SMI control would NOT have been
    (4 B/slot compressed, 8 uncompressed).

  Predicted and measured are both printed, for EVERY size and not merely
  as a slope. If they disagree, suspect the control first, and report the
  miss either way. Two misses are on record:

    * The SMALL double pair (D 100->200) lands, at +0.5%. The LARGE pair
      (1000->10000) reads +9%, because the 8 B/element model omits V8's
      page-tail filler — an 87 KB object wastes ~7% of a 256 KB page and
      that filler is genuine used-heap. It is a LARGE-object effect and
      every arm here allocates small ones, so the instrument is calibrated
      in the regime it is used in. STILL OPEN, and correctly so.
    * The SMI control used to read 16.1 B/slot — twice its own prediction,
      at both sizes. RESOLVED (rf2-l3jv4), and the fault was the
      instrument's, not the prediction's: `arm-ctl` was ONE function body
      closed over both kinds of template, so the harness had ONE
      `.slice()` call site that saw both PACKED_SMI_ELEMENTS and
      PACKED_DOUBLE_ELEMENTS receivers, and at that polymorphic site the
      SMI receiver loses `.slice()`'s clone fast path and allocates its
      elements store TWICE. Splitting the site per elements kind returns
      the arm to 8.0 B/slot. See `arm-ctl-smi`. The effect is
      order-INDEPENDENT — it does not depend on which kind the site saw
      first — so it does not account for the reversed-order reading of
      8.0027 recorded against this arm under rf2-88pie, which predates
      several rebuilds of it and is not re-litigated here. Both orders are
      still run, and both must now meet the prediction.

  ## The control REFUSES, it does not merely comment

  Finding that fault and fixing it left the instrument able to find it again
  and carry on anyway: the SMI/DBL ratio printed `*** NEITHER — the SMI arm
  is not measuring a tagged-slot copy ***` and nothing consumed it, so the
  run exited 0 and the arm-order guard beside it printed `VERDICT:
  reportable`. `re-frame.bench.calibration` turns that evidence into a
  boolean, and this harness's exit code is now the OR of two independent
  refusals — the arm order's and the control's. Its bands are expressed
  there and nowhere else, so what is printed below and what refuses cannot
  drift apart, and its [[re-frame.bench.calibration/self-test]] injects the
  recorded 16.11 among other fixtures so the refusal branch is adjudicated
  on every run rather than waiting for a broken machine. The DBL LARGE
  pair's ~+9% is deliberately NOT gated: it is an understood large-object
  effect and every arm here allocates small ones.

  ## The ladder

  Every arm below is a strict subset of the one under it, so subtracting
  neighbours attributes bytes to a layer rather than to a guess.

    MKDB      the application's own work — `(update db :cells assoc i v)`,
              the value B6's narrow `write!` builds before it writes
    BAREATOM  + `reset!` on a plain `clojure.core/atom` with no watches
    SPINE0    + the substrate's `replace-container!` — `with-epoch`,
              the reset, the epoch close, an empty drain — with NO
              derived values wired to the container
    RFWRITE-0 + `frame/replace-app-db!`'s own bookkeeping on a REAL frame
              with ZERO subscriptions: `commit-frame-transition!`, the
              partition carry-forward, the `changed` set, the two
              partition projections recomputing and fanning out
    RFWRITE-N + N held layer-1 subscriptions, each with one watcher, the
              shape a mounted application has
    FSWRITE-N the SAME write against a frame holding N `:frame-state` subs
              instead — the same fixed-arity-1 memo wrapper, but reading the
              frame's RAW physical container rather than the `rf=`-gated
              app-db projection. It is the control for `RFWRITE-N`
              (rf2-gncxk.1)

  `RFWRITE-N` is measured at four values of N and the SLOPE between them is
  the per-subscription cost — the number the bead is about. A slope cancels
  every constant in the ladder above it, so it is the one figure that does
  not depend on any of this attribution being right.

  ## The per-subscription pieces

  Then the slope itself is decomposed. Each arm re-walks ONE step of what a
  layer-1 subscription does when the graph re-evaluates it, through PUBLIC
  functions only, N times per call so it is directly comparable to the
  slope:

    P-BODY    the user's sub body — `(get-in db [:cells k])`
    P-EQDB    the memo wrapper's guard — `(= last-db db)` against the REAL
              app-db value, which is what decides whether the body runs
    P-EQDBF   the same guard at the OTHER input that reaches it — two
              `=`-but-not-`identical?` app-db values, where the walk runs to
              completion and finds no difference (rf2-gncxk.1)
    P-SCOPE   `trace/with-handler-scope` + `handler-scope-from-meta`, which
              bracket every recompute and are NOT dev-gated
    P-EMIT    `trace/emit!` on its production path with the tag map the
              memo wrapper builds for it
    P-MEMO    the whole shipped memo wrapper
              (`subs.memo/make-layer-1-memoised-body`) invoked with a
              CHANGED db — the sum of the four above plus its own frame
    P-MEMOW   the same wrapper over a source that publishes a MOVEMENT
              WITNESS, driven in the flush-path shape, so the guard's `=`
              walk is proved unnecessary and skipped (rf2-gncxk.1)
    P-MEMOWC  its matched control — identical scaffolding, identical inputs,
              a source that publishes nothing. `P-MEMOWC − P-MEMOW` is the
              `=` term and nothing else
    P-MEMOF   the same wrapper over a RAW ATOM source (the `:frame-state`
              shape) at an `=`-but-not-`identical?` db: the memo HIT the
              witness must NOT be allowed to touch
    P-MEMOFI  its matched control — the same wrapper objects at an
              `identical?` db, where `=` short-circuits and walks nothing.
              `P-MEMOF − P-MEMOFI` is the `=` walk still being paid
    Q-SCHED   the epoch scheduler's queue bookkeeping in its RETIRED
              spelling — N `conj` onto a PersistentHashSet + a
              PersistentVector, then N `disj` as the drain consumes them
    Q-SCHEDJS the same bookkeeping in the SHIPPED spelling — a `js/Set`
              and a JS array, mutated in place (rf2-jr76s)
    P-VALS    the app-db projection's fan-out in its RETIRED spelling —
              `run!` over `(vals ws)` of an N-entry watcher map
    P-RKV     the same fan-out in the SHIPPED spelling — `reduce-kv` over
              the map, no seq (rf2-jr76s)

  The last four are TWO PAIRED CONTROLS, both live in one process on the
  same objects — the `RC-ATTACH` / `RC-CAND` discipline `read-attribution`
  established. Keeping the retired expression measurable beside the shipped
  one is what turns a rewrite's saving into a falsifiable prediction:
  retired-minus-shipped must equal the drop in the per-subscription slope,
  and when this landed it did, to 0.3%.

  Run it:

      npx shadow-cljs release ui-bench --config-merge '{...}'
      node --expose-gc out/write-attribution.js

  ## The registration shape is part of the instrument

  The ladder's subscriptions carry SOURCE COORDS by default, because that is
  what a consumer's application has: `reg-sub` reached through its macro
  captures `:ns` / `:file` / `:line`, and those coords are what make
  `handler-scope-from-meta` build a cumulative coord map AND a three-key
  trigger map on top of the `HandlerScope` record. Without them
  `trigger-handler-from-meta` returns nil and the record is all there is.
  The same ladder reads **120.1 B/sub coord-less against 744.2 B/sub
  coord-carrying — a factor of six** — and the coord-less arm was the
  default the first published per-subscription figures came off, which is
  why they understate a real application (rf2-4k5hs).

  So the default is now the coord-carrying shape and every figure quoted
  from this harness comes off it. `WA_COORDS=0` still runs the coord-less
  ladder, and it is worth keeping: it is the cleanest way to isolate what
  the coords themselves cost. It is a labelled CONTROL, not a figure to
  publish.

  ## What a byte count here is, and is not

  These are NODE figures and the target is the BROWSER. Node ships V8 with
  pointer compression OFF and Chrome ships it ON, so a tagged slot is 8
  bytes here and 4 there — which is why the SMI control reads the regime off
  the process rather than assuming it. An absolute from this harness can
  therefore mis-rank for the browser, and any per-call absolute quoted from
  it elsewhere should carry its runtime and say so plainly. rf2-x0fe2 tracks
  the missing browser figures. Ratios between two arms measured the same way
  are unaffected by any of this.

  ## A near-zero arm is bounded, not measured (rf2-tmzie)

  `C-NOOP` is `keep!` and nothing else, so whatever it reads is what an arm
  that allocates NOTHING reads — the instrument's FLOOR, measured rather
  than assumed. The floor is a roughly fixed number of bytes per WINDOW, so
  it falls as `1/reps`, and an arm sitting on it has been BOUNDED, not
  measured. `-main` prints the floor and lists every arm at or under it.

  `WA_REPS_MAX` is the sweep that tells the two apart without leaving the
  measured plan: a REAL per-call cost is cap-independent in B/call, a floor
  is cap-independent in B/WINDOW. That distinction is what settled rf2-tmzie
  — `C-FRAME` holds 32.0 B/call from `WA_REPS_MAX=64` to `4000`, a 62x range,
  so it is a real per-call cost here and not the floor.

  What it is NOT is the visibility walk it was published as. The bisection
  (`C-FRAMEV` / `C-FRAMEL` / `C-FRAMES`) puts all 32 B in TWO
  `PersistentHashMap` lookups on the frame record, 16 B each and additive,
  and the `C-PHMGET` / `C-PAMGET` pair reproduces exactly that on maps built
  for the purpose: a `get` on a 17-entry map costs 16 B, the same `get` on a
  4-entry map costs 0. The registry itself holds four entries, which is why
  `C-FRAMEG` is free. And `-diagnose` PROBE 4/5 read ~0 for the SAME closure
  — a process whose only work is that one arm lets V8 elide the box, and a
  second live caller does not restore it. So 32.0 B/call is an UPPER BOUND:
  the cost this plan pays, and one a differently-optimised process does not.

  Environment: WA_N (subscriptions, default 300), WA_SAMPLES (samples per
  arm across ALL rounds, default 40), WA_REPS_MAX (the window-size cap
  `calibrate` clamps to, default 4000 — the rf2-tmzie sweep),
  WA_ROUNDS (default 6 — at least six,
  so the guard's phase thirds are ranges rather than single samples),
  WA_WARM_WINDOWS (full-size discarded windows per arm before the first
  measured round, default 6), WA_WARMUP (bare calls before calibration,
  default 3), WA_TOLERANCE (the guard's relative-median tolerance, default
  0.25), WA_ORDER=rev (reverse the base plan before scheduling — a knob
  now, not the mitigation), WA_COORDS=0 (register the ladder's subs WITHOUT
  source coords — the control arm, not the default)."
  (:require [goog.object :as gobj]
            [goog.string :as gstring]
            [goog.string.format]
            [re-frame.bench.calibration :as calib]
            [re-frame.bench.order-guard :as guard]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame.subs.memo :as subs-memo]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.spine :as spine]
            [re-frame.trace :as trace :include-macros true]))

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
;; sinks — Closure is entitled to delete an expression nothing reads, and this
;; surface has already published a `layout-ms` of exactly 0.000 because a
;; property read was dropped. Every arm feeds one of these.
;;
;; rf2-xu0ma — `sink` IS THE INSTRUMENT, so its own type is load-bearing.
;; `keep!` is a type-PRESERVING increment: given an Smi it produces an Smi and
;; allocates nothing; given a double it produces a double, and storing that
;; double into the volatile's tagged field boxes a fresh `HeapNumber` — 16 B
;; with pointer compression OFF, 12 B with it ON. An arm with a 300-iteration
;; inner loop therefore reads 4800 B/call MORE than it costs, purely because
;; something earlier in the plan left a double in this slot.
;;
;; It did. `arm-ctl` used to store `(aget c 0)` here, and `packed-doubles`
;; element 0 is `0.5`, so every arm that ran after a `DBL-*` control and before
;; an `SMI-*` one was charged that 4800 B — which under the reflecting schedule
;; is exactly the odd-numbered rounds. Measured at +0.00% against the 4800 B
;; prediction at five window sizes (node 24.13.0 / V8 13.6, pointer compression
;; OFF); `-diagnose` re-runs the proof.
;;
;; THE INVARIANT, and it is why this comment is here: **`keep!` is the only
;; writer of `sink`**. It is seeded with an Smi and only ever incremented, so it
;; is an Smi for the whole run and no arm can allocate on another's behalf.
;; Anything that wants to retain a VALUE rather than count one uses `sink2`,
;; which no arm reads back and which is written at most once per call.

(defonce ^:private sink (volatile! 0))
(defonce ^:private sink2 (volatile! nil))

(defn- keep! [v] (vreset! sink (if v (inc @sink) @sink)) nil)

;; ---------------------------------------------------------------------------
;; the control — a slope, not a prediction

(defonce ^:private ctl-templates (volatile! {}))

(def ^:private ctl-double-ds
  "Two SMALL sizes a decade apart from the two LARGE ones. Small so the
  copied object is a fraction of V8's 256 KB heap page and the page-tail
  filler V8 inserts when the next object will not fit is a rounding error;
  large so the same slope can be re-read where that filler is 2% of the
  object and the two readings can be compared. A constant error and a scale
  error look different across the four."
  [100 200 1000 10000])

(def ^:private ctl-smi-ds
  "The SAME `.slice()` over a PACKED SMI array. Its slot width is the ONE
  thing that differs from the double control: V8 never compresses a double,
  but a tagged pointer/SMI slot is 4 bytes under pointer compression and 8
  without it. Node.js ships V8 with pointer compression DISABLED (it caps
  the heap at 4 GB); Chrome ships it ENABLED. So this pair does not merely
  check the instrument — it READS OFF which regime this process is in, which
  is what decides whether an absolute byte count here is comparable to a
  browser one."
  [100 200])

(defn- packed-doubles
  "A PACKED double-element JS array. Built by pushing doubles rather than
  `(.fill (js/Array. d) 0.5)`, which produces a HOLEY array whose `.slice()`
  does not take the packed fast path — the exact construction difference B8
  found itself on the wrong side of."
  [d]
  (let [a (array)]
    (dotimes [i d] (.push a (+ 0.5 i)))
    a))

(defn- packed-smis
  "A PACKED SMI-element JS array — same shape, integer elements, so
  `.slice()` yields a `FixedArray` of tagged slots rather than a
  `FixedDoubleArray` of raw doubles."
  [d]
  (let [a (array)]
    (dotimes [i d] (.push a (inc i)))
    a))

(defn- ctl-key [kind d] (str kind "-" d))

;; rf2-l3jv4 — TWO factories, one per ELEMENT KIND, and the duplication is the
;; whole point.
;;
;; `.slice()`'s clone fast path is keyed on the RECEIVER'S ELEMENTS KIND, and
;; V8 has one inline cache per call SITE — that is, per function BODY, shared
;; by every closure made from it. A single `arm-ctl` body closed over both
;; kinds of template therefore gave the harness ONE `.slice()` site with two
;; receiver maps, and at that polymorphic site the PACKED_SMI receiver loses
;; the fast path: the clone allocates its elements store TWICE, so a copy costs
;; `32 + 2 x (16 + 8D)` rather than `32 + 16 + 8D`. The PACKED_DOUBLE receivers
;; keep theirs and are unaffected, which is why only the SMI half of the pair
;; was ever wrong.
;;
;; Measured in isolation, one node process, the same method, both shapes side
;; by side (node 24.13.0 / V8 13.6, pointer compression OFF):
;;
;;                 shared site        split sites      predicted
;;   SMI D=100     1665.9 B/copy      849.1 B/copy     848
;;   SMI D=200     3275.8            1651.8          1648
;;   DBL D=100      849.8             849.8            848
;;
;; So the PREDICTION was right and the INSTRUMENT was wrong — the arm was not
;; measuring `.slice()` of a packed SMI array, it was measuring `.slice()` of a
;; packed SMI array at a site that had also been shown doubles.
;;
;; Do not factor these two back together. If they are merged the SMI control
;; silently doubles again, which is why the CONTROLS readout now prints
;; predicted vs measured for every size rather than only a slope.

(defn- arm-ctl-dbl
  "The PACKED_DOUBLE control. Its `.slice()` site must never see any other
  elements kind — see `arm-ctl-smi`, which is this body's deliberate twin."
  [d]
  (let [t (get @ctl-templates (ctl-key "DBL" d))]
    (fn []
      ;; rf2-xu0ma — element 0 goes through `keep!`, NOT into `sink` directly.
      ;; The read is what stops Closure deleting the copy, and it is preserved;
      ;; what is dropped is the STORE, which used to put a double (`0.5`) in the
      ;; counter every `DBL-*` call and leave it there for every arm that ran
      ;; next. The control's own figure loses the 16 B box with it, which is
      ;; the check: `DBL-100` is predicted to fall from 864 B/copy to 848.
      (let [c (.slice t)]
        (keep! (aget c 0)))
      nil)))

(defn- arm-ctl-smi
  "The PACKED_SMI control, character-for-character `arm-ctl-dbl`'s body and
  separate from it on purpose (rf2-l3jv4). Sharing one body gives V8 one
  polymorphic `.slice()` site and doubles this arm's reading."
  [d]
  (let [t (get @ctl-templates (ctl-key "SMI" d))]
    (fn []
      (let [c (.slice t)]
        (keep! (aget c 0)))
      nil)))

(defn- arm-ctl
  "Dispatch to the per-kind factory. This function is not itself a `.slice()`
  site; it only chooses which one the arm will use."
  [kind d]
  (if (= "SMI" kind) (arm-ctl-smi d) (arm-ctl-dbl d)))

(defn- slope
  "Bytes per element between two control readings — the reading that cancels
  every header, every constant and every per-sample overhead."
  [b k1 d1 k2 d2]
  (/ (- (b k2) (b k1)) (- d2 d1)))

;; ---------------------------------------------------------------------------
;; the rig

(defonce ^:private rig (volatile! nil))
(defonce ^:private gen (volatile! 1000))

;; rf2-gncxk.1 — one flip cell per movement-witness arm. These arms need their
;; input to ALTERNATE strictly, because their whole shape is "the value the
;; wrappers last saw is the value the source just departed from"; handing an
;; arm the same value twice would put it on the memo-HIT branch and measure
;; something else. The shared `gen` cannot promise that (a neighbouring arm may
;; leave it advanced by an odd count between windows), so each arm flips its
;; own boolean. `not` on a boolean allocates nothing.
(defonce ^:private w-flip  (volatile! false))
(defonce ^:private wc-flip (volatile! false))

(defn- next-gen! [] (vswap! gen inc))

(def ^:private fid
  "A frame-id-SHAPED keyword, and nothing more: the key `arm-c-bump` bumps in
  this file's stand-in epoch map, and the frame-id the memo wrappers carry into
  their trace tags. Neither of those looks it up in the registry — `subs.memo`
  passes `frame-id` straight through to `emit-sub-skip!` / `validate-and-trace`
  as a tag value — so it does not need to be registered, and is not.

  rf2-c0awb: `C-FRAME` used to look this up, and THAT did need it registered.
  `build-rig!` builds `:wa/frame0` … `:wa/frame3` and never `:wa/frame`, so
  the arm published as `commit-frame-transition!`'s registry lookup was
  pricing a registry MISS. See `arm-c-frame`."
  :wa/frame)

(def ^:private absent-fid
  "Deliberately never registered — `arm-c-frame-miss` is the miss half of
  `C-FRAME`'s pair, and it has to be a miss on purpose rather than by accident."
  :wa/no-such-frame)

(defn- db-of [cells-n v] {:cells (vec (repeat cells-n v))})

;; ---- the ladder -----------------------------------------------------------

(defn- arm-mkdb []
  (let [{:keys [cells-n db-cell]} @rig
        v (next-gen!)
        i (mod v cells-n)]
    (vreset! sink2 (update @db-cell :cells assoc i v))
    nil))

(defn- arm-bareatom []
  (let [{:keys [cells-n db-cell bare]} @rig
        v (next-gen!)
        i (mod v cells-n)]
    (reset! bare (update @db-cell :cells assoc i v))
    nil))

(defn- arm-spine0 []
  (let [{:keys [cells-n db-cell spine-container]} @rig
        v (next-gen!)
        i (mod v cells-n)]
    (adapter/replace-container! spine-container
                                (update @db-cell :cells assoc i v))
    nil))

(defn- arm-rfwrite
  "`frame/replace-app-db!` on the rig's frame `k` (which carries `k`'s own
  number of held subscriptions). The value written is built the way B6's
  narrow `write!` builds it — read the frame's current app-db, assoc one
  cell — so this arm is B8's `write` leg and nothing else."
  [k]
  (let [{:keys [cells-n frames]} @rig
        f (nth frames k)
        v (next-gen!)
        i (mod v cells-n)]
    (frame/replace-app-db! f (update (frame/frame-app-db-value f) :cells assoc i v))
    nil))

(defn- arm-fswrite
  "rf2-gncxk.1 — the SAME write as `arm-rfwrite`, against a frame whose held
  subscriptions are `:frame-state` subs instead of `:db` subs.

  Both kinds route to `subs.memo`'s fixed-arity-1 wrapper, but they differ in
  the ONE fact the movement witness keys on: a `:db` sub's source is the
  app-db PROJECTION (a derived container whose fan-out is `rf=`-gated, so it
  publishes a witness), while a `:frame-state` sub's source is the frame's ONE
  physical container — a raw atom, which cannot. So this ladder's slope must be
  UNCHANGED by the witness while the `RFWRITE-N` slope falls by the `=` term,
  and the pair of slopes is the ladder-level statement of the same two-sided
  claim the `P-MEMOW` / `P-MEMOF` arms make at the wrapper level."
  [k]
  (let [{:keys [cells-n fs-frames]} @rig
        f (nth fs-frames k)
        v (next-gen!)
        i (mod v cells-n)]
    (frame/replace-app-db! f (update (frame/frame-app-db-value f) :cells assoc i v))
    nil))

;; ---- rf2-78ejq: the rungs UNDER RFWRITE-0 ---------------------------------
;;
;; `RFWRITE-0` — a write to a frame with ZERO subscriptions — was measured by
;; rf2-jr76s as ONE number. It is a CONSTANT, so at 300 subs it is 1.6% of the
;; write and invisible; at the 10 subs a small app or a per-frame tool panel
;; actually has, it is a THIRD of it. These arms split it.
;;
;; Each component is measured on its own, through public fns where the surface
;; is public and by re-spelling the private expression inline where it is not —
;; the `Q-SCHED` discipline, which keeps the measured expression pinned in the
;; harness so it cannot drift under the thing it is attributed to. The sum is
;; checked against the `RFWRITE-0 − SPINE0` delta and the residual is printed,
;; so an attribution that does not add up says so.
;;
;;   C-FRAME    `(frame/frame id)` — the registry lookup `commit-frame-
;;              transition!` does before anything else, on a frame the rig
;;              REGISTERED, so the visibility walk behind the registry `get`
;;              actually runs (rf2-c0awb)
;;   C-FRAMEX   the same call on an id that was never registered. PAIRED
;;              CONTROL: `frame/frame` short-circuits on the `get`, so the
;;              miss is strictly less work than the hit and only `C-FRAME`
;;              belongs in the attribution. This arm is what `C-FRAME` was
;;              measuring by accident until rf2-c0awb.
;;   C-FRAMEG   the registry `get` ALONE on the same hit, which is what says
;;              WHICH HALF of `frame/frame` a hit-vs-miss difference is in.
;;              It reads 0.0 — the registry is a FOUR-entry map, so `get` is
;;              an array scan and no hash is taken.
;;   C-FRAMEV   the same walk `frame/frame` performs, re-spelled INLINE.
;;              C-FRAME − C-FRAMEV is what the CALL costs; it is 0 (rf2-tmzie)
;;   C-FRAMEL   the `:lifecycle` half of the walk alone
;;   C-FRAMES   the `:construction` half alone. L + S = V, additively, and
;;              each half is one `PersistentHashMap` lookup on the record.
;;   C-PHMGET   CONTROL: one `get` on a 17-entry map. Predicted 16 B — a
;;   C-PAMGET   CONTROL: one `get` on a 4-entry map. Predicted 0 B. The pair
;;              differs only in the map's SIZE, which is what selects between
;;              a hashed lookup and an array scan.
;;   C-NOOP     CONTROL: `keep!` alone — the instrument's measured FLOOR at
;;              this window size. No arm at or below it has been measured.
;;   C-PARTS    `commit-frame-record-transition!`'s partition bookkeeping:
;;              the two `contains?`, the four `get`, the `next-fs` map, the
;;              `changed` cond-> set, the two `not=` partition comparisons and
;;              the two `identical?` no-op checks. NO container write, NO
;;              new-db construction — those are their own rungs.
;;   C-TAGPAIR  the two `[::ok v]` tagged pairs `call-exact-frame-callback`
;;              allocates per commit (one for `read-container`, one for
;;              `replace-container!`)
;;   C-BUMP     `bump-commit-epoch!` in its RETIRED spelling — `(fnil inc 0)`
;;              built fresh on every write
;;   C-BUMPH    the same in the SHIPPED spelling, the wrapper hoisted to a
;;              namespace constant. PAIRED CONTROL.
;;   SPINE0B    `replace-container!` of a PRE-BUILT frame-state map onto a
;;              container with NO derived values — the write with nothing
;;              layered over it
;;   SPINE0P    the same write onto a container carrying the TWO partition
;;              projections (`:rf.db/app` / `:rf.db/runtime`) a real frame
;;              wires. `SPINE0P − SPINE0B` is therefore the cost of the two
;;              projections recomputing and draining, and that term is the
;;              one Spec 002 §One physical container, two projection reactions
;;              makes non-negotiable.

(defonce ^:private bump-epochs (atom {}))

(def ^:private hoisted-fnil-inc
  "The SHIPPED spelling's hoisted wrapper. `(fnil inc 0)` is a function CALL
  that returns a fresh closure, so writing it at the `swap!` site allocates
  one per write."
  (fnil inc 0))

;; rf2-c0awb — `commit-frame-transition!`'s first act is `(frame/frame id)`, and
;; this arm is what that costs. It has to be a HIT to be that.
;;
;; `frame/frame` is `(when-let [f (get @frames id)] (when (visible? id f) f))`,
;; so a miss short-circuits on the registry `get` and never reaches the
;; visibility predicate — the two-level `:lifecycle`/`:construction` walk that
;; a real commit's lookup always runs. The arm used to be handed `:wa/frame`,
;; which `build-rig!` never registers, so it priced the short-circuit.
;;
;; A factory rather than a bare arm, so the id is resolved ONCE at plan-build
;; time: `(nth (:frames @rig) 0)` inside the measured body would charge this
;; arm a rig lookup it is not measuring. Frame 0 is the one `RFWRITE-0` writes
;; through, which is the rung this figure is subtracted from.
;;
;; The miss is kept beside it as `C-FRAMEX` rather than discarded. Both halves
;; live in one process on one registry — the `P-VALS`/`P-RKV` discipline — so
;; the pair MEASURES what the hit costs over the miss instead of assuming it,
;; and an arm that silently reverts to a miss shows up as the pair collapsing.

(defn- arm-c-frame
  "The HIT: the registry lookup a commit performs, on a frame that exists."
  []
  (let [id (nth (:frames @rig) 0)]
    (fn []
      (keep! (frame/frame id))
      nil)))

(defn- arm-c-frame-miss
  "The MISS: the same call on an id that was never registered, which is what
  this arm measured before rf2-c0awb."
  []
  (let [id absent-fid]
    (fn []
      (keep! (frame/frame id))
      nil)))

(defn- arm-c-frame-get
  "`frame/frame`'s registry `get` ALONE, on the same hit — the first half of
  the lookup with the visibility predicate removed. `frames` is public, so
  this is the `Q-SCHED` discipline: the expression re-spelled in the harness
  so what is measured is pinned here and cannot drift under the attribution.

  It exists because the HIT/MISS pair alone cannot say WHICH half of
  `frame/frame` a non-zero difference is in — the miss short-circuits on the
  `get`, so it skips both halves at once."
  []
  (let [id (nth (:frames @rig) 0)]
    (fn []
      (keep! (get @frame/frames id))
      nil)))

;; rf2-tmzie — BISECTING the hit, because `C-FRAME − C-FRAMEG` was published as
;; "the visibility walk" and no expression in that walk allocates.
;;
;; `frame-record-visible-to-current-actor?` is, for a FINAL record — which is
;; every record `build-rig!` installs — two keyword invokes, a `not`, a third
;; keyword invoke and a `not=`. The `:provisional` branch is unreachable: the
;; `or`'s first arm is true, so `*frame-transaction-owner*` is never read and
;; `owner-holds-frame-id?` never runs. Not one of those steps allocates on any
;; reading of `cljs.core`, so a 32 B/call figure attributed to them is a claim
;; about V8, not about the predicate — and V8 is exactly what a bisection can
;; separate.
;;
;;   C-FRAMEG  the registry `get` alone                          (already there)
;;   C-FRAMEV  + the WHOLE reachable predicate, re-spelled INLINE — the
;;             `Q-SCHED` discipline. Identical WORK to `C-FRAME`, with the
;;             cross-namespace CALL removed and nothing else changed.
;;   C-FRAMEL  + the `:lifecycle` half alone
;;   C-FRAMES  + the `:construction` half alone
;;
;; So `C-FRAME − C-FRAMEV` is what the CALL costs over the work, and
;; `C-FRAMEV − C-FRAMEG` is what the work costs on its own. A figure that lands
;; entirely in the first is not the visibility walk however stable it reads.

(defn- arm-c-frame-vis
  "The reachable predicate, INLINE. Verbatim `frame-record-visible-to-current-
  actor?` minus the provisional branch, which a final record short-circuits
  before it."
  []
  (let [id (nth (:frames @rig) 0)]
    (fn []
      (let [f (get @frame/frames id)]
        (keep! (and (not (-> f :lifecycle :destroyed?))
                    (not= :provisional (-> f :construction :state)))))
      nil)))

(defn- arm-c-frame-life
  "The `:lifecycle` half alone — one two-level keyword walk and a `not`."
  []
  (let [id (nth (:frames @rig) 0)]
    (fn []
      (keep! (not (-> (get @frame/frames id) :lifecycle :destroyed?)))
      nil)))

(defn- arm-c-frame-state
  "The `:construction` half alone — one two-level keyword walk and a `not=`.
  `:construction` is ABSENT from a `new-frame-record` map, so this is the
  missing-key path, which is the path the real predicate takes."
  []
  (let [id (nth (:frames @rig) 0)]
    (fn []
      (keep! (not= :provisional (-> (get @frame/frames id) :construction :state)))
      nil)))

;; rf2-tmzie — THE MECHANISM, as a PREDICTION stated before it is measured.
;;
;; The bisection lands on `16.0 B` per two-level keyword walk, twice, additive.
;; 16 B is this file's OWN standing figure for a boxed `HeapNumber` with pointer
;; compression OFF (see the `sink` header), and the two walks differ from the
;; free registry `get` in exactly one structural way: the map they index.
;;
;;   `@frames` holds FOUR entries, so it is a `PersistentArrayMap` and `get` is
;;   a linear `identical?` scan over an array — no hash is ever taken.
;;
;;   A frame record holds a dozen, so it is a `PersistentHashMap` and `get`
;;   runs `(hash k)` then `inode-lookup`, whose first act is
;;   `(bit-shift-right-zero-fill hash shift)`. A CLJS hash is a SIGNED int32;
;;   `>>>` re-reads it as UNSIGNED, and any int32 with the sign bit set becomes
;;   a value above 2^31 — outside Smi range, hence a `HeapNumber`.
;;
;; PREDICTION: one `get` on a >8-entry map costs 16 B/call; the same `get` on a
;; <=8-entry map costs 0. If that is right, `C-FRAME`'s 32 B is two hash-map
;; lookups on the frame record and has nothing to do with visibility.
;;
;; Both maps are built here rather than borrowed, so the sizes are chosen and
;; not inherited, and both TYPES are printed by `-main` rather than asserted —
;; the collection type is the whole claim, so it is read off the process.

(def ^:private phm-probe
  "SEVENTEEN entries. `PersistentArrayMap` promotes to `PersistentHashMap`
  above eight, so this is comfortably clear of the boundary."
  (into {} (map (fn [i] [(keyword "wa" (str "probe" i)) (inc i)])) (range 17)))

(def ^:private pam-probe
  "FOUR entries — the same shape as the rig's `@frames`, and below the
  promotion boundary, so `get` is an array scan with no hash."
  (into {} (map (fn [i] [(keyword "wa" (str "probe" i)) (inc i)])) (range 4)))

(defn- arm-c-phm-get
  "One `get` on a PersistentHashMap. Predicted 16.0 B/call."
  []
  (keep! (get phm-probe :wa/probe3))
  nil)

(defn- arm-c-pam-get
  "The SAME `get`, same key, same value, on a PersistentArrayMap. Predicted
  0.0 B/call. The pair differs in the map's SIZE and in nothing else."
  []
  (keep! (get pam-probe :wa/probe3))
  nil)

(defn- arm-c-noop
  "The FLOOR, measured rather than inferred: `keep!` and nothing else. Whatever
  this arm reads is what an arm that allocates nothing reads at this window
  size, and no arm at or below it has been measured — it has been bounded."
  []
  (keep! true)
  nil)

(defn- arm-c-parts []
  (let [{:keys [fs-a fs-b db-a db-b]} @rig
        e?         (even? @gen)
        ;; Alternate so the two `not=` comparisons do the work a real commit
        ;; makes them do — a genuinely different app-db value, one cell apart,
        ;; which `=` walks element-wise exactly as it does on the real path.
        current    (if e? fs-a fs-b)
        partitions (if e? {frame/app-partition-key db-b}
                          {frame/app-partition-key db-a})
        app-given? (contains? partitions frame/app-partition-key)
        rt-given?  (contains? partitions frame/runtime-partition-key)
        next-app   (if app-given? (get partitions frame/app-partition-key)
                       (get current frame/app-partition-key))
        next-rt    (if rt-given? (get partitions frame/runtime-partition-key)
                       (get current frame/runtime-partition-key))
        next-fs    {frame/app-partition-key     next-app
                    frame/runtime-partition-key next-rt}
        changed    (cond-> #{}
                     (and app-given?
                          (not= next-app (get current frame/app-partition-key)))
                     (conj frame/app-partition-key)
                     (and rt-given?
                          (not= next-rt (get current frame/runtime-partition-key)))
                     (conj frame/runtime-partition-key))]
    (keep! (and (identical? next-app (get current frame/app-partition-key))
                (identical? next-rt  (get current frame/runtime-partition-key))))
    (next-gen!)
    ;; Sink the two results SEPARATELY. Wrapping them in a `[next-fs changed]`
    ;; pair to sink them in one go allocates a vector this arm is not
    ;; measuring, and charges it to the bookkeeping — the control error that
    ;; standing method says to suspect first.
    (vreset! sink2 next-fs)
    (keep! changed)
    nil))

(defn- arm-c-tagpair []
  (let [{:keys [db-a]} @rig]
    ;; TWO pairs, sunk separately — one per host-adapter callback. An
    ;; enclosing `[pair pair]` vector would make this arm read three.
    (vreset! sink2 [::ok db-a])
    (vreset! sink2 [::ok nil])
    nil))

(defn- arm-c-bump []
  (swap! bump-epochs update fid (fnil inc 0))
  nil)

(defn- arm-c-bump-h []
  (swap! bump-epochs update fid hoisted-fnil-inc)
  nil)

(defn- arm-spine0b []
  (let [{:keys [fs-a fs-b bare-fs-container]} @rig]
    (adapter/replace-container! bare-fs-container (if (even? @gen) fs-a fs-b))
    (next-gen!)
    nil))

(defn- arm-spine0p []
  (let [{:keys [fs-a fs-b proj-fs-container]} @rig]
    (adapter/replace-container! proj-fs-container (if (even? @gen) fs-a fs-b))
    (next-gen!)
    nil))

;; ---- the per-subscription pieces ------------------------------------------

(defn- arm-p-body []
  (let [{:keys [n db-a]} @rig]
    (dotimes [k n]
      (keep! (get-in db-a [:cells k])))))

(defn- arm-p-eqdb []
  (let [{:keys [n db-a db-b]} @rig]
    (dotimes [_ n]
      (keep! (= db-a db-b)))))

(defn- arm-p-scope []
  (let [{:keys [n qids]} @rig]
    (dotimes [k n]
      (trace/with-handler-scope
        (trace/handler-scope-from-meta :sub (nth qids k) nil)
        (keep! true)))))

;; rf2-zxv06 — the handler-scope bracket, in BOTH spellings, one process, the
;; same sub-ids. `P-SCOPE` above is kept EXACTLY as rf2-jr76s left it (nil
;; meta) so its 120.8 B figure stays comparable, but nil meta is not the
;; production shape: a macro-registered sub carries `:ns` / `:file` / `:line`
;; (`:column` is dev-only and absent under `:advanced` + goog.DEBUG=false), and
;; those are what make `trigger-handler-from-meta` build a coord map AND a
;; three-key trigger map on top of the record. So:
;;
;;   P-SCOPEM  the RETIRED spelling with REALISTIC registration meta — build
;;             the scope per recompute, the way `validate-and-trace` did
;;   P-SCOPEH  the SHIPPED spelling — the scope built ONCE per cache entry and
;;             closed over, so the per-recompute cost is the `binding` and
;;             `inherit-scope` alone (rf2-zxv06)
;;
;; and, because `inherit-scope`'s copies only happen when a PARENT scope is
;; bound — which is the real shape, since an app's write runs inside the
;; router's event scope, but is NOT the shape of the RFWRITE ladder below,
;; where nothing binds a parent:
;;
;;   P-INHER   the RETIRED `inherit-scope` under a bound parent whose
;;             `:call-site` / `:dispatch-id` are both nil, as they always are
;;             in a production build
;;   P-INHERH  the SHIPPED one under the same parent
;;
;; P-INHER − P-INHERH is therefore a saving the ladder here CANNOT show and a
;; real application does get. It is quoted separately rather than folded in.

(def ^:private prod-sub-meta
  "What `reg-sub`'s macro path leaves in the registrar slot for a PRODUCTION
  build: `:ns` / `:file` / `:line` are the locked coord keys; `:column` is
  dev-only (`source-coords/prod-coords-form`) so it is absent here."
  {:ns 'wa.bench :file "wa/bench.cljs" :line 42})

(defn- arm-p-scope-m []
  (let [{:keys [n qids]} @rig]
    (dotimes [k n]
      (trace/with-handler-scope
        (trace/handler-scope-from-meta :sub (nth qids k) prod-sub-meta)
        (keep! true)))))

(defn- arm-p-scope-h []
  (let [{:keys [n sub-scopes]} @rig]
    (dotimes [k n]
      (trace/with-handler-scope (nth sub-scopes k) (keep! true)))))

(defn- arm-p-inher []
  (let [{:keys [n sub-scopes parent-scope]} @rig]
    (dotimes [k n]
      (let [s (nth sub-scopes k)]
        ;; the RETIRED expression, pinned here so it cannot drift under the
        ;; shipped one: assoc unconditionally wherever the child's slot is nil
        (keep! (cond-> s
                 (nil? (:call-site s))   (assoc :call-site   (:call-site parent-scope))
                 (nil? (:dispatch-id s)) (assoc :dispatch-id (:dispatch-id parent-scope))))))))

(defn- arm-p-inher-h []
  (let [{:keys [n sub-scopes parent-scope]} @rig]
    (dotimes [k n]
      (keep! (trace/inherit-scope (nth sub-scopes k) parent-scope)))))

(defn- arm-p-emit []
  (let [{:keys [n qids qvs]} @rig]
    (dotimes [k n]
      (trace/emit! :rf.sub :rf.sub/run
                   {:rf.sub/id      (nth qids k)
                    :rf.sub/query-v (nth qvs k)
                    :frame          fid}))))

(defn- arm-p-memo []
  (let [{:keys [n memos db-a db-b]} @rig]
    (dotimes [k n]
      ;; Alternate the db so the guard NEVER hits — which is exactly what a
      ;; real write does to it.
      (keep! ((nth memos k) (if (even? @gen) db-a db-b))))
    (next-gen!)
    nil))

;; ---- rf2-gncxk.1 — the MOVEMENT WITNESS, both halves ----------------------
;;
;; `P-EQDB` above prices the memo wrapper's `(= last-db db)` guard at 147.0
;; B/sub, and rf2-gncxk.1 retires it — but only where it is provably wasted.
;; The guard is NOT dead code: the spine's derived value is pull-based, so it
;; is the only thing stopping a render from re-running every sub body, and on
;; a `:frame-state` sub it genuinely HITS on the flush path. So the change is
;; two-sided by construction, and so is the measurement. Neither half is
;; optional; a one-sided result does not discharge the claim.
;;
;; THE `:db` HALF — a strict paired control, the `RC-ATTACH` / `RC-CAND`
;; discipline. Two arms, identical in every respect but ONE:
;;
;;   P-MEMOW   the shipped wrapper over a WITNESSING source — a real spine
;;             derived container, which publishes
;;             `re-frame.movement/IMovementWitness` because its fan-out is
;;             `rf=`-gated — driven in the FLUSH-PATH shape.
;;   P-MEMOWC  the shipped wrapper over the RAW ATOM under that same derived
;;             container, so `witness-src` resolves nil and the guard
;;             expression is byte-for-byte the one that shipped.
;;
;; Both arms move their source once per call and then invoke 300 wrappers, so
;; the scaffolding — one `replace-container!`, one epoch, one drain, one
;; recompute-and-notify — is IDENTICAL and cancels in the difference.
;; `P-MEMOWC − P-MEMOW` is therefore the `=` term and nothing else, and it
;; must equal `P-EQDB`. `P-MEMOWC` is separately checked against `P-MEMO` to
;; show the scaffolding is not carrying the result.
;;
;; The flush-path shape is set up rather than simulated. Each call moves the
;; source to `cur`, which arms its witness with `prev` — the very object the
;; 300 wrappers each hold in `last-db` from the previous call — and then hands
;; every wrapper `cur`. `(identical? (-moved-from S) @last-db)` is exactly the
;; precondition the proof needs, and the wrapper then recomputes without
;; walking. Each arm alternates on its OWN flip cell rather than the shared
;; `gen`, so a neighbouring arm's odd call count cannot silently hand it the
;; same value twice and put it on the memo-hit branch instead.
;;
;; THE `:frame-state` HALF — the same wrapper, over a RAW ATOM (which is what
;; `subs/single-source-container-for` maps `:frame-state` to: the frame's ONE
;; physical container, whose fan-out is not movement-gated and which therefore
;; cannot implement the protocol). Two arms over the SAME wrapper objects,
;; both landing on the memo-HIT branch — the real `:frame-state` flush-path
;; shape, the one PR #7233 pins — differing only in the identity of the
;; argument:
;;
;;   P-MEMOF   invoked with an `=`-but-NOT-`identical?` db, so `=` walks the
;;             whole structure and finds no difference.
;;   P-MEMOFI  invoked with the IDENTICAL db object, so `=` short-circuits on
;;             `identical?` and walks nothing.
;;
;; `P-MEMOF − P-MEMOFI` is the full `=` walk at that input, and it must equal
;; `P-EQDBF` — the bare `(= db-a db-a2)` measured on its own. That is the
;; "still paid where it earns its keep" half, and it is a genuine pair: same
;; wrapper set, same source, same memo cells, one object identity different.
;; (Both arms take the hit branch, so neither mutates `last-db` — the pairing
;; is stable across any number of calls in any order.)

(defn- arm-p-eqdb-f []
  (let [{:keys [n db-a db-a2]} @rig]
    (dotimes [_ n]
      (keep! (= db-a db-a2)))))

(defn- arm-p-memo-w []
  (let [{:keys [n memos-w w-src db-a db-b]} @rig
        cur (if (vswap! w-flip not) db-a db-b)]
    ;; Move the witnessing source: `notify`'s `rf=` gate arms its witness
    ;; with the value the 300 wrappers below each hold in `last-db`.
    (adapter/replace-container! w-src cur)
    (dotimes [k n]
      (keep! ((nth memos-w k) cur)))
    nil))

(defn- arm-p-memo-wc []
  (let [{:keys [n memos-wc wc-src db-a db-b]} @rig
        cur (if (vswap! wc-flip not) db-a db-b)]
    ;; Byte-for-byte `arm-p-memo-w`'s scaffolding — the same container shape
    ;; carrying the same lone derived dependent — so the only thing left in
    ;; the difference is whether the wrapper's source publishes a witness.
    (adapter/replace-container! wc-src cur)
    (dotimes [k n]
      (keep! ((nth memos-wc k) cur)))
    nil))

(defn- arm-p-memo-f []
  (let [{:keys [n memos-f db-a2]} @rig]
    (dotimes [k n]
      (keep! ((nth memos-f k) db-a2)))))

(defn- arm-p-memo-fi []
  (let [{:keys [n memos-f db-a]} @rig]
    (dotimes [k n]
      (keep! ((nth memos-f k) db-a)))))

;; The scheduler's queue bookkeeping, in BOTH spellings, in one process on the
;; same 300 thunks — the paired-control discipline `read-attribution`'s
;; `RC-ATTACH` / `RC-CAND` established. `Q-SCHED` is the RETIRED form (a
;; volatile over a persistent vector + persistent set); `Q-SCHED-JS` is the
;; form that replaced it (a JS array + `js/Set`, mutated in place). Neither is
;; a call into `spine`: the whole point is to keep the retired expression
;; measurable beside the shipped one so the difference is a comparison of two
;; EXPRESSIONS and neither arm can drift under the other.

(defn- arm-q-sched []
  (let [{:keys [n thunks]} @rig
        [s q] (loop [k 0 s #{} q []]
                (if (< k n)
                  (let [t (nth thunks k)]
                    (recur (inc k)
                           (if (contains? s t) s (conj s t))
                           (if (contains? s t) q (conj q t))))
                  [s q]))]
    ;; the drain's `(vswap! queued disj thunk)`, once per entry
    (vreset! sink2 (loop [k 0 s s] (if (< k n) (recur (inc k) (disj s (nth q k))) s)))
    nil))

(defn- arm-q-sched-js []
  (let [{:keys [n thunks]} @rig
        s (js/Set.)
        q (array)]
    (dotimes [k n]
      (let [t (nth thunks k)]
        (when-not (.has s t) (.add s t) (.push q t))))
    ;; the drain's `(.delete queued thunk)`, once per entry
    (dotimes [k n] (.delete s (aget q k)))
    (vreset! sink2 s)
    nil))

;; The derived-value fan-out over a two-plus-subscriber watcher map, in BOTH
;; spellings. `P-VALS` is the RETIRED `(run! f (vals ws))`; `P-RKV` is the
;; `reduce-kv` walk that replaced it.
;;
;; rf2-ktrvw — BOTH ARMS HAND A FRESH CLOSURE TO A CALLEE, one per call, and
;; that is a bimodal cost on this runtime. `read_attribution_cljs`'s `N-NEWFN`
;; prices a bare closure at 64.0 B settled and 128.1 B in its high mode — a
;; step of exactly ONE closure, per WINDOW, in both plan orders — and an arm
;; whose subject IS a closure cannot be re-shaped out of it.
;;
;; It is visible here on `P-RKV`, which allocates one closure per call against
;; a body of ~81 B: it reads 80.9 B/call [80.9 – 83.6] at the default
;; `WA_REPS_MAX=4000` and 86.9 [86.9 – 145.2] at 512 — a ~58 B step, one
;; closure — and that step is what makes the guard refuse it by PHASE at the
;; smaller cap. A long window hammers the closure site enough to settle inside
;; the window; a short one can sit wholly in either mode.
;;
;; So `P-RKV`'s and `P-VALS`'s ranges are load-bearing and their p50s are not
;; quotable alone. The SHIPPED-vs-RETIRED difference these two exist for is
;; unaffected: both halves allocate one closure, so it cancels.

(defn- arm-p-vals []
  (let [{:keys [watch-map]} @rig]
    (run! (fn [w] (keep! w)) (vals watch-map))
    nil))

(defn- arm-p-rkv []
  (let [{:keys [watch-map]} @rig]
    (reduce-kv (fn [_ _ w] (keep! w) nil) nil watch-map)
    nil))

;; ---------------------------------------------------------------------------
;; the driver

(defn- measure
  "Run `f` `reps` times inside one collection-free sample, `samples` times.
  Answers `{:p50 … :lo … :hi … :xs … :accepted … :dropped …}` in bytes per
  CALL. `:xs` is the accepted samples themselves, because a round's figures
  have to be poolable across rounds and a p50 of p50s is not one."
  [f reps samples]
  (let [acc (array)
        dropped (volatile! 0)]
    (dotimes [_ samples]
      (collect!)
      (let [h0 (used-heap)]
        (dotimes [_ reps] (f))
        (let [d (- (used-heap) h0)]
          (if (neg? d)
            (vswap! dropped inc)
            (.push acc (/ d reps))))))
    (.sort acc (fn [a b] (- a b)))
    (let [c (alength acc)]
      {:p50      (if (pos? c) (aget acc (js/Math.floor (/ c 2))) -1)
       :lo       (if (pos? c) (aget acc 0) -1)
       :hi       (if (pos? c) (aget acc (dec c)) -1)
       :xs       (vec acc)
       :accepted c
       :dropped  @dropped})))

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
  outgrows the nursery and no sample is lost in the counter's own noise.

  `cap` bounds the answer. It is a KNOB (`WA_REPS_MAX`) and not a constant
  because an arm that allocates ~nothing per call always lands on it, and an
  arm pinned at the cap cannot be told apart from the instrument's own
  per-window floor by looking at one window size. Re-running the whole plan at
  several caps IS the sweep: a real per-call cost is cap-INDEPENDENT in B/call,
  a per-window constant is cap-independent in B/WINDOW and so falls as 1/reps.
  That is the distinction rf2-tmzie was opened on, and `-main` can now make it
  without leaving `-main` (see `floor-lines`)."
  [f target cap]
  (collect!)
  (let [h0 (used-heap)
        _  (dotimes [_ 4] (f))
        per (max 1 (/ (- (used-heap) h0) 4))]
    (-> (js/Math.round (/ target per)) (max 1) (min cap))))

;; ---------------------------------------------------------------------------
;; rig construction

(defn- build-rig! [n cells-n ns-per-frame coords?]
  ;; One sub-id per cell, exactly B6's `:b6/cell` shape but registered per
  ;; index so every subscription is a distinct cache entry with a distinct
  ;; body closure — the shape a real application's 300 boundaries have.
  ;;
  ;; `coords?` decides whether those registrations carry SOURCE COORDS, and it
  ;; is not a detail (rf2-zxv06, rf2-4k5hs). This ns requires `re-frame.core`
  ;; WITHOUT `:include-macros true`, so `rf/reg-sub` here is the plain FUNCTION
  ;; and no call site is captured — left alone, the registrar slot comes out
  ;; `{}`. A real application writes `(rf/reg-sub …)` in a namespace that does
  ;; get the macro, so its slots carry `:ns` / `:file` / `:line` (`:column` is
  ;; dev-only and absent under `:advanced` + goog.DEBUG=false). That is the
  ;; difference between `P-SCOPE` and `P-SCOPEM` — a factor of six.
  ;;
  ;; So the DEFAULT hands the registrar the production meta explicitly, which
  ;; is the shape a consumer's application actually registers. `WA_COORDS=0`
  ;; runs the coord-less ladder instead: a labelled CONTROL that isolates what
  ;; the coords cost, and not an arm any published figure comes off. The
  ;; header prints which arm ran, either way.
  (doseq [i (range cells-n)]
    (let [body (fn [db _] (get-in db [:cells i]))
          qid  (keyword "wa" (str "cell" i))]
      (if coords?
        (rf/reg-sub qid prod-sub-meta body)
        (rf/reg-sub qid body))))
  ;; rf2-gncxk.1 — the `:frame-state` ladder's subs. Same fixed-arity-1 memo
  ;; wrapper as the `:db` ladder's, same registration shape; the ONE difference
  ;; is the signal source the reactive build resolves — the frame's raw
  ;; physical container rather than the `rf=`-gated app-db projection. That is
  ;; the fact the movement witness keys on, so this ladder's slope is the
  ;; control for the `RFWRITE-N` ladder's.
  (doseq [i (range cells-n)]
    (let [body (fn [fs _] (get-in fs [frame/app-partition-key :cells i]))
          qid  (keyword "wa" (str "fscell" i))]
      (if coords?
        (subs/reg-frame-state-sub qid prod-sub-meta body)
        (subs/reg-frame-state-sub qid body))))
  (let [qids  (mapv #(keyword "wa" (str "cell" %)) (range cells-n))
        qvs   (mapv vector qids)
        ;; One frame per subscription count, each with its OWN held
        ;; subscriptions, so the N-slope is read across four live graphs
        ;; rather than by subscribing and unsubscribing between samples.
        frames (mapv (fn [k]
                       (let [f (keyword "wa" (str "frame" k))]
                         (live-frame/make-frame {:id f})
                         (frame/replace-app-db! f (db-of cells-n 0))
                         f))
                     (range (count ns-per-frame)))
        holds  (mapv (fn [f cnt]
                       (binding [frame/*current-frame* f]
                         (mapv (fn [q]
                                 (let [r (subs/subscribe q)]
                                   ;; One watcher per reaction — the shape a
                                   ;; mounted boundary gives it. Without this
                                   ;; the derived value's fan-out takes its
                                   ;; zero-subscriber branch and the graph is
                                   ;; not the graph an application has.
                                   (adapter/subscribe-container r (fn [_ _] nil))
                                   ;; Establish the memo baseline the way a
                                   ;; first render does.
                                   (deref r)
                                   r))
                               (subvec qvs 0 cnt))))
                     frames ns-per-frame)
        ;; rf2-gncxk.1 — the `:frame-state` ladder: one frame per subscription
        ;; count, each holding `cnt` `:frame-state` subs, wired exactly as the
        ;; `:db` ladder's frames are (one watcher per reaction, one deref to
        ;; establish the memo baseline). Written by `arm-fswrite` with the same
        ;; narrow `assoc`-one-cell write.
        fs-qvs (mapv (fn [i] [(keyword "wa" (str "fscell" i))]) (range cells-n))
        fs-frames (mapv (fn [k]
                          (let [f (keyword "wa" (str "fsframe" k))]
                            (live-frame/make-frame {:id f})
                            (frame/replace-app-db! f (db-of cells-n 0))
                            f))
                        (range (count ns-per-frame)))
        fs-holds (mapv (fn [f cnt]
                         (binding [frame/*current-frame* f]
                           (mapv (fn [q]
                                   (let [r (subs/subscribe q)]
                                     (adapter/subscribe-container r (fn [_ _] nil))
                                     (deref r)
                                     r))
                                 (subvec fs-qvs 0 cnt))))
                       fs-frames ns-per-frame)
        db-a  (db-of cells-n 7)
        db-b  (update (db-of cells-n 7) :cells assoc (dec cells-n) 8)
        ;; rf2-gncxk.1 — a FRESH map `=` to `db-a` but not `identical?` to it:
        ;; the input at which the `=` walk runs to completion and finds no
        ;; difference. That is the `:frame-state` flush-path shape PR #7233
        ;; pins — a value-equal commit that reaches the wrapper and memo-HITS —
        ;; and `P-EQDBF` / `P-MEMOF` / `P-MEMOFI` are all priced at it.
        db-a2 (db-of cells-n 7)
        ;; rf2-78ejq — the two frame-state values a commit alternates between,
        ;; PRE-BUILT so the SPINE0B / SPINE0P arms measure the container write
        ;; and the projections alone, with no value construction folded in.
        fs-a  {frame/app-partition-key db-a frame/runtime-partition-key {}}
        fs-b  {frame/app-partition-key db-b frame/runtime-partition-key {}}
        ;; A container with NOTHING layered over it, and the same container
        ;; shape carrying the TWO partition projections a real frame wires
        ;; (`frame.cljc`'s `(make-derived-value [frame-state] :rf.db/app)` and
        ;; its `:rf.db/runtime` sibling). The pair isolates the projections.
        bare-fs-container (adapter/make-state-container fs-a)
        proj-fs-container (adapter/make-state-container fs-a)
        _proj (mapv (fn [k] (adapter/make-derived-value [proj-fs-container] k))
                    [frame/app-partition-key frame/runtime-partition-key])
        ;; rf2-gncxk.1 — the movement-witness pair's rigs. TWO structurally
        ;; identical stacks (a raw container carrying one derived dependent),
        ;; differing only in which of the two the 300 memo wrappers are handed
        ;; as their lone source:
        ;;
        ;;   w-*   wrappers over the DERIVED container, which publishes
        ;;         `IMovementWitness` (its fan-out is `rf=`-gated)
        ;;   wc-*  wrappers over the RAW container underneath it, which cannot
        ;;
        ;; so `P-MEMOWC − P-MEMOW` isolates the guard and nothing else.
        w-src      (adapter/make-state-container db-a)
        w-derived  (adapter/make-derived-value [w-src] identity)
        wc-src     (adapter/make-state-container db-a)
        wc-derived (adapter/make-derived-value [wc-src] identity)
        ;; The `:frame-state` half's source: a raw container, exactly what
        ;; `subs/single-source-container-for` hands a `:frame-state` sub.
        f-src      (adapter/make-state-container db-a)
        mk-memos   (fn [source]
                     (mapv (fn [i]
                             (subs-memo/make-layer-1-memoised-body
                               (fn [db _] (get-in db [:cells i]))
                               (nth qids i) (nth qvs i) fid {} source))
                           (range n)))
        memos-w    (mk-memos w-derived)
        memos-wc   (mk-memos wc-src)
        memos-f    (mk-memos f-src)]
    ;; Seed each derived container's baseline BEFORE moving it, so the first
    ;; movement's `notify` arms the witness with a REAL predecessor rather than
    ;; the spine's construction sentinel.
    (deref w-derived)
    (deref wc-derived)
    ;; Put both stacks — and every wrapper over them — into the state the
    ;; measured arms assume on entry: source at `db-b`, `last-db` at `db-b`.
    ;; `w-flip` / `wc-flip` start false, so the first measured call flips to
    ;; true and drives `db-b -> db-a`, arming the witness with the very object
    ;; the wrappers hold.
    (adapter/replace-container! w-src db-b)
    (adapter/replace-container! wc-src db-b)
    (dotimes [k n]
      ((nth memos-w k) db-b)
      ((nth memos-wc k) db-b)
      ;; `memos-f` is primed at `db-a` and never moves off it: both of its arms
      ;; take the memo-HIT branch, which does not write `last-db`.
      ((nth memos-f k) db-a))
    (vreset! rig
             {:n        n
              :cells-n  cells-n
              :qids     qids
              :qvs      qvs
              :frames   frames
              :holds    holds
              ;; rf2-gncxk.1 — the `:frame-state` ladder
              :fs-frames fs-frames
              :fs-holds  fs-holds
              :db-cell  (atom (db-of cells-n 0))
              :bare     (atom (db-of cells-n 0))
              :spine-container (adapter/make-state-container (db-of cells-n 0))
              :db-a     db-a
              :db-b     db-b
              ;; rf2-gncxk.1 — the movement-witness pair
              :db-a2      db-a2
              :w-src      w-src
              :w-derived  w-derived
              :wc-src     wc-src
              :wc-derived wc-derived
              :f-src      f-src
              :memos-w    memos-w
              :memos-wc   memos-wc
              :memos-f    memos-f
              :fs-a     fs-a
              :fs-b     fs-b
              :bare-fs-container bare-fs-container
              :proj-fs-container proj-fs-container
              :projections       _proj
              ;; rf2-zxv06 — one PRE-BUILT scope per sub, the shipped shape
              ;; (`subs.memo`'s wrappers now close over exactly this), plus a
              ;; parent whose inheritable slots are both nil, which is what a
              ;; production build always presents to `inherit-scope`.
              :sub-scopes   (mapv #(trace/handler-scope-from-meta
                                     :sub % prod-sub-meta)
                                  qids)
              :parent-scope (trace/handler-scope-from-meta
                              :event :wa/parent prod-sub-meta)
              ;; the shipped memo wrapper, one per subscription. The trailing
              ;; source is the RAW container (rf2-gncxk.1) — a source that
              ;; publishes no movement witness, so `P-MEMO` keeps measuring
              ;; exactly the expression it always measured, its published
              ;; figure intact and comparable.
              :memos    (mk-memos f-src)
              ;; distinct fn identities, the way the scheduler's queue holds
              ;; one flush thunk per dirty derived value
              :thunks    (mapv (fn [i] (fn [] i)) (range n))
              :watch-map (into {} (map (fn [i] [(gensym "w") (fn [] i)])) (range n))})))

;; ---------------------------------------------------------------------------

(defn- fmt [x] (.toFixed x 1))

(defn ^:export -main [& _]
  ;; Ahead of everything, because a broken guard makes every figure below
  ;; unpublishable and finding that out after the run is wasteful. The checks
  ;; are fixtures replayed from `rf2-jr76s`'s recorded readings, so this is
  ;; deterministic — exactly as `b8_run.cjs` does it.
  (when-not (guard/print-self-test!)
    (throw (ex-info "order guard self-test FAILED — nothing may be measured" {})))
  ;; rf2-l3jv4 — and the SAME discipline for the control calibration, whose
  ;; refusal is the other half of this run's exit code. Injected ratios,
  ;; including the recorded 16.11 B/slot this bead was opened on.
  (when-not (calib/print-self-test!)
    (throw (ex-info "calibration self-test FAILED — nothing may be measured" {})))
  (let [n        (env-int "WA_N" 300)
        samples  (env-int "WA_SAMPLES" 40)
        warmup   (env-int "WA_WARMUP" 3)
        warm-windows (env-int "WA_WARM_WINDOWS" 6)
        rounds   (max 2 (env-int "WA_ROUNDS" 6))
        ;; rf2-tmzie — the window-size cap `calibrate` clamps to. Every arm that
        ;; allocates ~nothing per call pins here, so this is the ONE knob that
        ;; separates a per-CALL cost from a per-WINDOW one without leaving the
        ;; measured plan.
        reps-max (max 1 (env-int "WA_REPS_MAX" 4000))
        per-round (max 1 (js/Math.ceil (/ samples rounds)))
        tolerance (env-num "WA_TOLERANCE" 0.25)
        rev?     (= "rev" (env "WA_ORDER" ""))
        ;; rf2-4k5hs — the coord-carrying registration is the DEFAULT, because
        ;; that is what a consumer's macro-registered subs give the registrar.
        ;; `WA_COORDS=0` selects the coord-less control.
        coords?  (not= "0" (env "WA_COORDS" "1"))
        ns-per-frame [0 (js/Math.round (/ n 4)) (js/Math.round (/ n 2)) n]]
    (rf/init! (spine/make-react-adapter
                (spine/make-react-spine
                  {:substrate-name        "write-attribution"
                   :gensym-prefix-sub     "wa-sub-"
                   :gensym-prefix-derived "wa-derived-"
                   :gensym-prefix-use-sub "wa-use-sub-"
                   :use-memo              (fn [t _] (t))
                   :use-callback          (fn [t _] t)
                   :use-context           (fn [_] nil)})
                {:kind :rf.adapter/write-attribution :frame-provider nil}))
    (vreset! ctl-templates
             (into {} (concat (map (fn [d] [(ctl-key "DBL" d) (packed-doubles d)]) ctl-double-ds)
                              (map (fn [d] [(ctl-key "SMI" d) (packed-smis d)]) ctl-smi-ds))))
    (build-rig! n n ns-per-frame coords?)
    (println (gstring/format ";; rf2-jr76s WRITE attribution — node V8, :advanced"))
    (println (gstring/format ";; debug-enabled? = %s   gc-exposed? = %s"
                     interop/debug-enabled?
                     (some? (gobj/get js/globalThis "gc"))))
    (println (gstring/format ";; node %s  V8 %s  pointer-compression=%s (Chrome ships it ON: a tagged slot is 4 B there, 8 B here)"
                     (.-node js/process.versions) (.-v8 js/process.versions)
                     (if (= 1 (gobj/getValueByKeys js/process "config" "variables" "v8_enable_pointer_compression")) "ON" "OFF")))
    (println (gstring/format ";; n=%d samples=%d (%d rounds x %d) warmup=%d calls + %d windows  reps-max=%d  base order=%s frames=%s"
                     n samples rounds per-round warmup warm-windows reps-max
                     (if rev? "REVERSED" "forward")
                     (pr-str ns-per-frame)))
    (println (gstring/format ";; arm order ROTATES AND REFLECTS with the round (rf2-88pie); guard tolerance %s"
                     (.toFixed (* 100.0 tolerance) 0)))
    (println (gstring/format ";; registration = %s"
                     (if coords?
                       "COORD-CARRYING (default — the shape a real application registers)"
                       "COORD-LESS (WA_COORDS=0 — the CONTROL arm; do not quote a figure from this run)")))
    ;; PROVENANCE, not decoration (rf2-zxv06). `handler-scope-from-meta`'s cost
    ;; is dominated by whether the registrar slot carries source coords: with
    ;; `:ns` / `:file` / `:line` it builds a coord map AND a trigger map on top
    ;; of the record; without them `trigger-handler-from-meta` returns nil and
    ;; the record is all there is. So which of `P-SCOPE` (coord-less) and
    ;; `P-SCOPEM` (coord-carrying) predicts the ladder's slope depends on a
    ;; fact about THIS build, and guessing it would be guessing the headline.
    (let [slot (registrar/lookup :sub (first (:qids @rig)))]
      (println (gstring/format ";; registered sub-meta coord keys = %s  (trigger-handler %s)"
                       (pr-str (select-keys slot [:ns :file :line :column]))
                       (if (trace/trigger-handler-from-meta :sub :probe slot)
                         "BUILT — P-SCOPEM is the predictive arm"
                         "nil — P-SCOPE is the predictive arm"))))
    ;; rf2-tmzie — the mechanism pair's whole claim is the COLLECTION TYPE, so
    ;; the type is read off the process rather than asserted from an entry
    ;; count. `@frames` is printed beside them because `C-FRAMEG`'s 0.0 is only
    ;; evidence about array maps while the registry actually is one.
    ;; Type NAMES are minified under `:advanced`, so the claim is put as an
    ;; identity comparison, which survives minification: the big map's type
    ;; must DIFFER from the small one's, and the registry's must MATCH it.
    (println (gstring/format ";; probe maps: %d-entry %s   %d-entry %s   registry @frames %d-entry %s"
                     (count phm-probe) (pr-str (type phm-probe))
                     (count pam-probe) (pr-str (type pam-probe))
                     (count @frame/frames) (pr-str (type @frame/frames))))
    (println (gstring/format ";;   big differs from small = %s   registry is the SMALL-map type = %s  (%s)"
                     (not (identical? (type phm-probe) (type pam-probe)))
                     (identical? (type @frame/frames) (type pam-probe))
                     (if (and (not (identical? (type phm-probe) (type pam-probe)))
                              (identical? (type @frame/frames) (type pam-probe)))
                       "as the C-PHMGET / C-PAMGET pair requires"
                       "*** the mechanism pair is NOT contrasting what it claims ***")))
    ;; Agreement gate: every held subscription must read what the writer
    ;; wrote, or the graph is not wired and nothing below is evidence.
    (let [f (last (:frames @rig))
          v (next-gen!)]
      (frame/replace-app-db! f (update (frame/frame-app-db-value f) :cells assoc 3 v))
      (let [got (binding [frame/*current-frame* f] (deref (subs/subscribe [(keyword "wa" "cell3")])))]
        (println (gstring/format ";; agreement at cell3: wrote %d read %s -> %s"
                         v (pr-str got) (if (= v got) "AGREE" "*** DISAGREE ***")))
        (when-not (= v got)
          (throw (ex-info "graph not wired; measurement is meaningless" {:wrote v :read got})))))
    (let [plan (cond-> (into (vec (concat
                                    (map (fn [d] [(ctl-key "DBL" d) (arm-ctl "DBL" d) 1]) ctl-double-ds)
                                    (map (fn [d] [(ctl-key "SMI" d) (arm-ctl "SMI" d) 1]) ctl-smi-ds)))
                       [["MKDB"      arm-mkdb             1]
                        ["BAREATOM"  arm-bareatom         1]
                        ["SPINE0"    arm-spine0           1]
                        ;; rf2-78ejq — the rungs under RFWRITE-0
                        ["SPINE0B"   arm-spine0b          1]
                        ["SPINE0P"   arm-spine0p          1]
                        ;; rf2-c0awb — the registry lookup, HIT / MISS / the
                        ;; `get` on its own, all three in one process
                        ["C-FRAME"   (arm-c-frame)        1]
                        ["C-FRAMEX"  (arm-c-frame-miss)   1]
                        ["C-FRAMEG"  (arm-c-frame-get)    1]
                        ;; rf2-tmzie — the same work with the CALL removed
                        ["C-FRAMEV"  (arm-c-frame-vis)    1]
                        ["C-FRAMEL"  (arm-c-frame-life)   1]
                        ["C-FRAMES"  (arm-c-frame-state)  1]
                        ;; rf2-tmzie — the mechanism pair, and the measured floor
                        ["C-PHMGET"  arm-c-phm-get        1]
                        ["C-PAMGET"  arm-c-pam-get        1]
                        ["C-NOOP"    arm-c-noop           1]
                        ["C-PARTS"   arm-c-parts          1]
                        ["C-TAGPAIR" arm-c-tagpair        1]
                        ["C-BUMP"    arm-c-bump           1]
                        ["C-BUMPH"   arm-c-bump-h         1]])
                 true (into (map-indexed
                              (fn [k cnt]
                                [(str "RFWRITE-" cnt) (fn [] (arm-rfwrite k)) 1])
                              ns-per-frame))
                 ;; rf2-gncxk.1 — the same ladder over `:frame-state` subs,
                 ;; whose source cannot publish a movement witness. Its slope
                 ;; is the control for `RFWRITE-N`'s.
                 true (into (map-indexed
                              (fn [k cnt]
                                [(str "FSWRITE-" cnt) (fn [] (arm-fswrite k)) 1])
                              ns-per-frame))
                 true (into [["P-BODY"  arm-p-body  n]
                             ["P-EQDB"  arm-p-eqdb  n]
                             ;; rf2-gncxk.1 paired controls, both halves
                             ["P-EQDBF"   arm-p-eqdb-f   n]
                             ["P-MEMOW"   arm-p-memo-w   n]
                             ["P-MEMOWC"  arm-p-memo-wc  n]
                             ["P-MEMOF"   arm-p-memo-f   n]
                             ["P-MEMOFI"  arm-p-memo-fi  n]
                             ["P-SCOPE" arm-p-scope n]
                             ;; rf2-zxv06 paired controls
                             ["P-SCOPEM"  arm-p-scope-m n]
                             ["P-SCOPEH"  arm-p-scope-h n]
                             ["P-INHER"   arm-p-inher   n]
                             ["P-INHERH"  arm-p-inher-h n]
                             ["P-EMIT"  arm-p-emit  n]
                             ["P-MEMO"  arm-p-memo  n]
                             ["Q-SCHED" arm-q-sched n]
                             ["Q-SCHEDJS" arm-q-sched-js n]
                             ["P-VALS"  arm-p-vals  n]
                             ["P-RKV"   arm-p-rkv   n]]))
          plan (if rev? (vec (reverse plan)) plan)
          k    (count plan)
          ;; --- warm-up and calibration, one pass, nothing read ------------
          ;; rf2-tb345: a site reads 1.26x to 5.3x its settled value until it
          ;; has run several full-size windows. Charged to round 1 that reads
          ;; as allocation, and — worse — it reads as an arm whose figure
          ;; moves with where in the plan it sat, which the guard below
          ;; refuses. So every arm is walked to its settled value first.
          reps (mapv (fn [[label f _]]
                       (dotimes [_ warmup] (f))
                       (let [r (calibrate f 2000000 reps-max)]
                         (dotimes [_ warm-windows] (measure f r 1))
                         (println (gstring/format ";; warm %-12s reps=%-6d %d discarded windows"
                                          label r warm-windows))
                         r))
                     plan)
          ;; --- the measured rounds ----------------------------------------
          ;; Every sample carries the label of the arm that ran immediately
          ;; before it and its position in the whole run; those two are what
          ;; the guard stratifies on.
          run  (reduce
                 (fn [acc [round j]]
                   (let [[label f _] (nth plan j)
                         r (measure f (nth reps j) per-round)]
                     (-> acc
                         (update-in [:xs label] (fnil into []) (:xs r))
                         (update-in [:dropped label] (fnil + 0) (:dropped r))
                         (update :order conj {:arm         label
                                              :value       (:p50 r)
                                              :predecessor (:prev acc)
                                              :position    (count (:order acc))
                                              :round       round})
                         (assoc :prev label))))
                 {:xs {} :dropped {} :order [] :prev nil}
                 (vec (for [round (range rounds)
                            j     (guard/slot-order k round)]
                        [round j])))
          res  (into {}
                     (map-indexed
                       (fn [i [label _ per]]
                         (let [pooled (summarise (get-in run [:xs label] []))
                               rnd    (mapv :value (filter #(= label (:arm %)) (:order run)))]
                           [label (assoc pooled
                                         :per     per
                                         :reps    (nth reps i)
                                         :rounds  rnd
                                         :dropped (get-in run [:dropped label] 0))]))
                       plan))
          b    (fn [l] (:p50 (get res l)))
          ;; rf2-l3jv4 — the control's own verdict, computed ONCE from the
          ;; same p50s the CONTROLS block prints, and carried to the exit
          ;; code at the bottom of this function. The bands live in
          ;; `re-frame.bench.calibration` and are expressed nowhere else, so
          ;; what is printed and what refuses cannot drift apart.
          cal  (calib/verdict
                 (mapv (fn [d] {:d d :smi (b (ctl-key "SMI" d)) :dbl (b (ctl-key "DBL" d))})
                       ctl-smi-ds)
                 (slope b (ctl-key "SMI" 100) 100 (ctl-key "SMI" 200) 200))]
      (doseq [[label _ per] plan]
        (let [r   (get res label)
              rnd (:rounds r)]
          (println (gstring/format ";; %-12s reps=%-6d %12s B/call  [%s – %s]  per-unit %10s B  (%d ok, %d dropped)  rounds p50 [%s – %s]"
                           label (:reps r) (fmt (:p50 r)) (fmt (:lo r)) (fmt (:hi r))
                           (fmt (/ (:p50 r) per)) (:accepted r) (:dropped r)
                           (fmt (apply min rnd)) (fmt (apply max rnd))))))
      ;; rf2-tmzie — THE FLOOR, measured rather than assumed. `C-NOOP` is
      ;; `keep!` and nothing else, so whatever it reads is what "allocates
      ;; nothing" reads at THIS window size. The floor is window-size
      ;; dependent — it is a roughly fixed number of bytes per WINDOW, so it
      ;; falls as 1/reps — which is why `WA_REPS_MAX` exists and why an arm
      ;; that sits on it must be reported as an upper bound rather than a
      ;; measurement. Re-run at a smaller `WA_REPS_MAX` and an arm whose figure
      ;; RISES was never measuring itself.
      (println ";;")
      (println ";; THE FLOOR — what an arm that allocates NOTHING reads in this run")
      (let [nreps (:reps (get res "C-NOOP"))
            floor (b "C-NOOP")]
        (println (gstring/format ";;   C-NOOP  %s B/call at reps=%-6d (%s B per WINDOW)  [reps-max=%d]"
                         (fmt floor) nreps (fmt (* floor nreps)) reps-max))
        (let [bounded (filterv (fn [[label _ _]] (<= (b label) floor)) plan)]
          (if (seq bounded)
            (do (println ";;   AT OR UNDER IT — bounded, not measured; report these as <=:")
                (doseq [[label _ _] bounded]
                  (println (gstring/format ";;     <= %-10s %10s B/call" label (fmt (b label))))))
            (println ";;   no arm is at the floor in this run"))))
      (println ";;")
      (println ";; CONTROLS")
      ;; rf2-l3jv4 — the ABSOLUTE, checked, at every size. The slopes below
      ;; cancel both headers and so cannot see a constant FACTOR; printing only
      ;; them is how an arm reading exactly twice its prediction at BOTH sizes
      ;; went unremarked for as long as it did.
      ;;
      ;; The double control is checked against an asserted layout — JSArray
      ;; 32 B + `FixedDoubleArray` (16 B header + 8 B x D) — which is safe
      ;; because V8 never pointer-compresses a double, so that prediction holds
      ;; in either regime.
      ;;
      ;; The SMI control is NOT checked that way, because its whole job is to
      ;; READ the regime and predicting it from an assumed slot width would be
      ;; circular. It is checked against the DOUBLE control at the SAME D
      ;; instead — one measurement against another, from the same process and
      ;; the same window, with no header asserted anywhere. With pointer
      ;; compression OFF a tagged slot is 8 bytes and the two copies have
      ;; identical layout, so the ratio must be 1.0000; with it ON every slot
      ;; and both headers halve and it must be about 0.50.
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
                   ";;   DBL slope, LARGE pair (1000->10000)  %.4f B/double  predicted 8.0000  %+.2f%%"
                   lg (* 100.0 (/ (- lg 8.0) 8.0))))
        ;; rf2-l3jv4 — the regime is READ off this pair, so it cannot also be
        ;; predicted from an assumed slot width. What CAN be checked, without
        ;; circularity, is how close the slope sits to the exact width of the
        ;; regime THE RATIOS ABOVE selected: a tagged slot is 8 bytes or 4,
        ;; never 16.1. Selecting the width from the slope itself — which is
        ;; what this line used to do — cannot see a doubled slope at all,
        ;; because 16.1 simply answers "OFF" and is then compared to 8.
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
      (println ";; THE LADDER — bytes per WRITE")
      (doseq [[lbl v] [["MKDB      the application's own new-db value" (b "MKDB")]
                       ["BAREATOM  + reset! on a watch-free atom"      (b "BAREATOM")]
                       ["SPINE0    + the substrate's replace-container!" (b "SPINE0")]]]
        (println (gstring/format ";;   %-46s %10s B" lbl (fmt v))))
      (doseq [cnt ns-per-frame]
        (println (gstring/format ";;   %-46s %10s B"
                         (str "RFWRITE-" cnt "  frame/replace-app-db!, " cnt " subs")
                         (fmt (b (str "RFWRITE-" cnt))))))
      (doseq [cnt ns-per-frame]
        (println (gstring/format ";;   %-46s %10s B"
                         (str "FSWRITE-" cnt "  same write, " cnt " :frame-state subs")
                         (fmt (b (str "FSWRITE-" cnt))))))
      (println ";;")
      (let [lo (first ns-per-frame) hi (last ns-per-frame)
            slope (/ (- (b (str "RFWRITE-" hi)) (b (str "RFWRITE-" lo))) (- hi lo))
            fs-slope (/ (- (b (str "FSWRITE-" hi)) (b (str "FSWRITE-" lo))) (- hi lo))]
        (println (gstring/format ";;   PER-SUBSCRIPTION SLOPE  %s B / sub / write   (:db subs)" (fmt slope)))
        (println (gstring/format ";;   PER-SUBSCRIPTION SLOPE  %s B / sub / write   (:frame-state subs — rf2-gncxk.1 control)"
                         (fmt fs-slope)))
        (println ";;")
        (println ";; WHERE THE PER-SUBSCRIPTION BYTES GO (each arm × n, reported per sub)")
        (println ";;   P-SCOPE is the COORD-LESS control; P-SCOPEM is the production shape")
        (doseq [l ["P-BODY" "P-EQDB" "P-EQDBF" "P-SCOPE" "P-SCOPEM" "P-SCOPEH" "P-INHER" "P-INHERH"
                   "P-EMIT" "P-MEMO" "P-MEMOW" "P-MEMOWC" "P-MEMOF" "P-MEMOFI"
                   "Q-SCHED" "Q-SCHEDJS" "P-VALS" "P-RKV"]]
          (let [v (/ (b l) n)]
            (println (gstring/format ";;   %-10s %10s B/sub   %5.1f%% of the slope"
                             l (fmt v) (* 100.0 (/ v slope))))))
        (println (gstring/format ";;   %-10s %10s B/sub"
                         "RESIDUAL" (fmt (- slope (/ (b "P-MEMO") n)
                                            (/ (b "Q-SCHEDJS") n) (/ (b "P-RKV") n)))))
        (println ";;")
        (println ";; rf2-zxv06 — THE HANDLER-SCOPE BRACKET, PAIRED")
        (println (gstring/format
                   ";;   P-SCOPEM (retired, prod meta)   %10s B/sub" (fmt (/ (b "P-SCOPEM") n))))
        (println (gstring/format
                   ";;   P-SCOPEH (shipped, hoisted)     %10s B/sub" (fmt (/ (b "P-SCOPEH") n))))
        (println (gstring/format
                   ";;   -> hoist predicts               %10s B/sub saved"
                   (fmt (/ (- (b "P-SCOPEM") (b "P-SCOPEH")) n))))
        (println (gstring/format
                   ";;   P-INHER  (retired inherit)      %10s B/sub" (fmt (/ (b "P-INHER") n))))
        (println (gstring/format
                   ";;   P-INHERH (shipped inherit)      %10s B/sub" (fmt (/ (b "P-INHERH") n))))
        (println (gstring/format
                   ";;   -> inherit predicts             %10s B/sub saved  (ONLY under a bound parent — a real app's write; NOT visible in the ladder above)"
                   (fmt (/ (- (b "P-INHER") (b "P-INHERH")) n))))
        ;; rf2-gncxk.1 — BOTH halves, quoted as the two paired differences the
        ;; change stands or falls on. Neither is optional: the first says the
        ;; `=` term is GONE where the source publishes a movement witness, the
        ;; second says it is STILL PAID where it does not — which is the
        ;; `:frame-state` shape PR #7233 pins.
        (println ";;")
        (println ";; rf2-gncxk.1 — THE MOVEMENT WITNESS, PAIRED, BOTH HALVES")
        (println ";;   [:db half] same wrapper, same inputs, same scaffolding; witnessing vs not")
        (println (gstring/format
                   ";;   P-MEMOWC (raw-atom source, no witness) %10s B/sub" (fmt (/ (b "P-MEMOWC") n))))
        (println (gstring/format
                   ";;   P-MEMOW  (witnessing source, proof fires) %7s B/sub" (fmt (/ (b "P-MEMOW") n))))
        (println (gstring/format
                   ";;   -> witness retires                     %10s B/sub   (must equal P-EQDB %s)"
                   (fmt (/ (- (b "P-MEMOWC") (b "P-MEMOW")) n)) (fmt (/ (b "P-EQDB") n))))
        (println (gstring/format
                   ";;   (cross-check) P-MEMO                   %10s B/sub   — P-MEMOWC's scaffolding is not carrying the result"
                   (fmt (/ (b "P-MEMO") n))))
        (println ";;   [:frame-state half] same wrapper objects, same source, same memo cells;")
        (println ";;     only the ARGUMENT's identity differs — both land on the memo-HIT branch")
        (println (gstring/format
                   ";;   P-MEMOF  (=-but-not-identical? db)     %10s B/sub" (fmt (/ (b "P-MEMOF") n))))
        (println (gstring/format
                   ";;   P-MEMOFI (identical? db, = short-circuits) %6s B/sub" (fmt (/ (b "P-MEMOFI") n))))
        (println (gstring/format
                   ";;   -> = walk STILL PAID                   %10s B/sub   (must equal P-EQDBF %s)"
                   (fmt (/ (- (b "P-MEMOF") (b "P-MEMOFI")) n)) (fmt (/ (b "P-EQDBF") n)))))
      (println ";;")
      (println ";; rf2-78ejq — WHERE RFWRITE-0's CONSTANT GOES")
      (let [rf0    (b "RFWRITE-0")
            sp0    (b "SPINE0")
            delta  (- rf0 sp0)
            proj   (- (b "SPINE0P") (b "SPINE0B"))
            comps  [["C-FRAME    (frame id) lookup, HIT  [<= — rf2-tmzie]" (b "C-FRAME")]
                    ["C-PARTS    partition bookkeeping"      (b "C-PARTS")]
                    ["C-TAGPAIR  two [::ok v] pairs"         (b "C-TAGPAIR")]
                    ["C-PROJ     the two projections"        proj]
                    ["C-BUMP     bump-commit-epoch!"         (b "C-BUMP")]]
            summed (reduce + (map second comps))]
        (println (gstring/format ";;   RFWRITE-0 %s B  −  SPINE0 %s B  =  %s B to attribute"
                         (fmt rf0) (fmt sp0) (fmt delta)))
        (doseq [[lbl v] comps]
          (println (gstring/format ";;     %-42s %10s B   %5.1f%% of the delta"
                           lbl (fmt v) (* 100.0 (/ v delta)))))
        (println (gstring/format ";;     %-42s %10s B   %5.1f%%"
                         "RESIDUAL" (fmt (- delta summed))
                         (* 100.0 (/ (- delta summed) delta))))
        (println (gstring/format ";;   SPINE0B (bare container write) %s B ; SPINE0P (+2 projections) %s B"
                         (fmt (b "SPINE0B")) (fmt (b "SPINE0P"))))
        (println (gstring/format ";;   C-BUMP %s B vs C-BUMPH %s B  -> hoisting (fnil inc 0) predicts %s B/write"
                         (fmt (b "C-BUMP")) (fmt (b "C-BUMPH"))
                         (fmt (- (b "C-BUMP") (b "C-BUMPH")))))
        ;; rf2-c0awb — the lookup, paired. Only C-FRAME (the HIT) is a component
        ;; of the delta above; C-FRAMEX is here to say what the hit costs OVER
        ;; the miss rather than leaving it assumed, and to make it visible if
        ;; this arm ever reverts to pricing the short-circuit again.
        (println (gstring/format ";;   C-FRAME (registry HIT) %s B vs C-FRAMEX (MISS) %s B  -> a HIT costs %s B/lookup more than a miss"
                         (fmt (b "C-FRAME")) (fmt (b "C-FRAMEX"))
                         (fmt (- (b "C-FRAME") (b "C-FRAMEX")))))
        (println (gstring/format ";;   C-FRAMEG (the registry `get` alone, same hit) %s B  -> the record walk is the remaining %s B"
                         (fmt (b "C-FRAMEG"))
                         (fmt (- (b "C-FRAME") (b "C-FRAMEG")))))
        ;; rf2-tmzie — WHAT THAT NON-ZERO IS. It was published as "the
        ;; visibility walk" and disputed against `-diagnose`, which reads a
        ;; constant 32 B per WINDOW for the same closure. Both readings stand;
        ;; they are of different things, and these arms say which is which.
        (println ";;")
        (println ";;   rf2-tmzie — WHAT THE 32 B IS, bisected")
        (doseq [[lbl v note]
                [["C-FRAME   frame/frame, the shipped call" (b "C-FRAME")
                  "the whole hit"]
                 ["C-FRAMEV  the same walk, re-spelled INLINE" (b "C-FRAMEV")
                  "no call: so the cost is the WORK, not the call"]
                 ["C-FRAMEL  the :lifecycle half alone" (b "C-FRAMEL")
                  "one PersistentHashMap `get` on the record"]
                 ["C-FRAMES  the :construction half alone" (b "C-FRAMES")
                  "one PersistentHashMap `get` on the record"]
                 ["C-FRAMEG  the registry `get` alone" (b "C-FRAMEG")
                  "PersistentArrayMap: no hash is taken"]]]
          (println (gstring/format ";;     %-44s %8s B   %s" lbl (fmt v) note)))
        (println (gstring/format ";;     -> C-FRAMEL + C-FRAMES = %s B against C-FRAMEV's %s B  (%+.1f%%)"
                         (fmt (+ (b "C-FRAMEL") (b "C-FRAMES"))) (fmt (b "C-FRAMEV"))
                         (let [s (+ (b "C-FRAMEL") (b "C-FRAMES"))
                               v (b "C-FRAMEV")]
                           (if (zero? v) 0.0 (* 100.0 (/ (- s v) v))))))
        ;; The mechanism, predicted before it was measured, on maps built for
        ;; the purpose and differing only in size.
        (let [phm (b "C-PHMGET") pam (b "C-PAMGET")]
          (println (gstring/format ";;     CONTROL  `get` on a %d-entry map %8s B   predicted 16.0 (one boxed HeapNumber)  %+.1f%%"
                           (count phm-probe) (fmt phm) (* 100.0 (/ (- phm 16.0) 16.0))))
          (println (gstring/format ";;     CONTROL  `get` on a %d-entry map %8s B   predicted  0.0 (array scan, no hash)"
                           (count pam-probe) (fmt pam)))
          (println (str ";;     So the 32 B is TWO boxed hash values, one per PersistentHashMap lookup on"))
          (println (str ";;     the frame record — NOT the visibility test, which is a `not`, a `not=` and"))
          (println (str ";;     nothing else. `-diagnose` PROBE 4/5 read ~0 B/call for the same closure"))
          (println (str ";;     because a process whose only work is that one arm lets V8 keep the shift"))
          (println (str ";;     result in a register; PROBE 5 shows a second live caller does NOT restore"))
          (println (str ";;     it. So this figure is an UPPER BOUND — the cost this plan pays and a"))
          (println (str ";;     single-arm process does not. Quote it as <=, with the runtime named."))))
      ;; --- the two refusals, LAST, and together they govern everything
      ;; above. The figures are printed and a refusal is about what may be
      ;; QUOTED, not about throwing the measurement away — so the exit code
      ;; carries it rather than an exception.
      ;;
      ;; rf2-l3jv4 — there are TWO of them and they are independent. The arm
      ;; order asks whether an arm's figure moved with where in the plan it
      ;; sat; the calibration asks whether the instrument was measuring a
      ;; tagged-slot copy at all. A run that fails either is not reportable,
      ;; and until this bead the second one only ever printed.
      (println ";;")
      (let [v (guard/verdict (:order run) {:tolerance tolerance})]
        (doseq [line (guard/report-lines v "the per-arm figure, one p50 per round")]
          (println line))
        (when (:refuse? v)
          (println ";;")
          (println ";; ==== ARM ORDER: THESE FIGURES ARE NOT REPORTABLE ====")
          (println (str ";;   at least one arm reads differently for what preceded it, or for where "
                        "in the run"))
          (println (str ";;   it was measured (rf2-88pie). The table above stands as raw data; "
                        "nothing in it"))
          (println ";;   may be quoted."))
        (doseq [line (calib/report-lines cal)]
          (println line))
        (when (or (:refuse? v) (:refuse? cal))
          (set! (.-exitCode js/process) 2))))
    (println (gstring/format ";; sink %s %s" @sink (some? @sink2)))))

;; ---------------------------------------------------------------------------
;; rf2-xu0ma — the sink-typing probe
;;
;; `-main` above refuses on `P-SCOPEH`, `P-INHERH` and `P-RKV`, whose figures
;; step by ~4800 B/call with the round's PARITY. This entry point prices the
;; mechanism directly rather than inferring it, and it is the same instrument:
;; the same `collect!`, the same `used-heap`, the same `measure`, the same rig.
;;
;; The claim under test, in one line: `keep!` increments a SHARED counter whose
;; TYPE the control arms decide, and an increment of a double allocates.
;;
;;   - `keep!` is `(vreset! sink (if v (inc @sink) @sink))` — type-PRESERVING.
;;   - `arm-ctl` is `(vreset! sink (aget c 0))` — it CLOBBERS the same slot with
;;     an element of the control template.
;;   - `packed-doubles` element 0 is `0.5`; `packed-smis` element 0 is `1`.
;;
;; So after any `DBL-*` arm the counter is a double and every subsequent `keep!`
;; boxes a fresh `HeapNumber` — 8 B map word + 8 B payload = 16 B with pointer
;; compression OFF — until an `SMI-*` arm puts an Smi back. `slot-order` runs
;; the plan ASCENDING on even rounds (so `SMI-200` resets the counter before the
;; body) and DESCENDING on odd ones (so a `DBL-*` arm is the last control the
;; body sees). An arm with a 300-iteration inner loop therefore carries
;; 300 x 16 = 4800 B/call on odd rounds and 0 on even ones.
;;
;; PREDICTION, stated before the measurement: seeding the counter with `0.5`
;; rather than `1` costs `16 x iters` B/call, and nothing else about the loop
;; changes. `WA_PROBE_REPS` sweeps the window size, because the second half of
;; the bead — the step reading 606 B/call in one run and 4800 in another — is
;; predicted to be this same 4800 TRUNCATED by scavenges once `reps x 4800`
;; outgrows the nursery, and a sweep is what tells linear from truncated.

(defn- sink-loop
  "`iters` bare `keep!` calls and nothing else — the inner loop every refusing
  arm shares, with the arm's own body removed."
  [iters]
  (fn [] (dotimes [_ iters] (keep! true)) nil))

(defn- probe
  "`windows` collection-free windows of `reps` calls of `f`, after seeding the
  shared counter with `seed`. Answers RAW bytes per window plus a READ-BACK:
  `verify` is handed the counter's value before and after the window and says
  whether the writes the window was CREDITED with actually landed. A window
  that fails it is UNVERIFIED and is reported as such rather than quietly
  pooled."
  [f reps windows seed verify]
  (let [ds (array) unverified (volatile! 0)]
    (dotimes [_ windows]
      (vreset! sink seed)
      (collect!)
      (let [before @sink
            h0     (used-heap)]
        (dotimes [_ reps] (f))
        (let [d (- (used-heap) h0)]
          (when-not (verify before @sink reps) (vswap! unverified inc))
          (.push ds d))))
    (.sort ds (fn [a b] (- a b)))
    {:lo (aget ds 0)
     :p50 (aget ds (js/Math.floor (/ (alength ds) 2)))
     :hi (aget ds (dec (alength ds)))
     :unverified @unverified
     :windows windows}))

(defn- advanced-by
  "READ-BACK for an arm that increments the counter `per` times per call: the
  counter must have moved by exactly `per x reps`."
  [per]
  (fn [before after reps] (== (- after before) (* per reps))))

(defn- probe-line [label reps r per-call-divisor]
  (println (gstring/format ";;   %-22s reps=%-6d raw [%12s – %12s]  p50 %12s B   %10s B/call   %d unverified of %d"
                   label reps
                   (fmt (:lo r)) (fmt (:hi r)) (fmt (:p50 r))
                   (fmt (/ (:p50 r) per-call-divisor))
                   (:unverified r) (:windows r))))

(defn ^:export -diagnose [& _]
  (let [iters   (env-int "WA_N" 300)
        windows (env-int "WA_PROBE_WINDOWS" 5)
        sweep   (mapv #(js/parseInt % 10)
                      (.split (env "WA_PROBE_REPS" "64,128,256,512,1024,2048,4000") ","))
        pc-off? (not= 1 (gobj/getValueByKeys js/process "config" "variables"
                                             "v8_enable_pointer_compression"))
        box-b   (if pc-off? 16 12)]
    (rf/init! (spine/make-react-adapter
                (spine/make-react-spine
                  {:substrate-name        "write-attribution"
                   :gensym-prefix-sub     "wa-sub-"
                   :gensym-prefix-derived "wa-derived-"
                   :gensym-prefix-use-sub "wa-use-sub-"
                   :use-memo              (fn [t _] (t))
                   :use-callback          (fn [t _] t)
                   :use-context           (fn [_] nil)})
                {:kind :rf.adapter/write-attribution :frame-provider nil}))
    (vreset! ctl-templates
             (into {} (concat (map (fn [d] [(ctl-key "DBL" d) (packed-doubles d)]) ctl-double-ds)
                              (map (fn [d] [(ctl-key "SMI" d) (packed-smis d)]) ctl-smi-ds))))
    (build-rig! iters iters [0 (js/Math.round (/ iters 4)) (js/Math.round (/ iters 2)) iters] true)
    (println ";; rf2-xu0ma — SINK-TYPING PROBE for write-attribution's refusing arms")
    (println (gstring/format ";; node %s  V8 %s  pointer-compression=%s  debug-enabled?=%s  gc-exposed?=%s"
                     (.-node js/process.versions) (.-v8 js/process.versions)
                     (if pc-off? "OFF" "ON") interop/debug-enabled?
                     (some? (gobj/get js/globalThis "gc"))))
    (println (gstring/format ";; iters/call=%d  windows/point=%d  HeapNumber predicted at %d B"
                     iters windows box-b))
    ;; ---- the control templates, read back --------------------------------
    (let [d0 (aget (get @ctl-templates (ctl-key "DBL" 100)) 0)
          s0 (aget (get @ctl-templates (ctl-key "SMI" 100)) 0)]
      (println (gstring/format ";; DBL template elt0 = %s (integral? %s)   SMI template elt0 = %s (integral? %s)"
                       d0 (js/Number.isInteger d0) s0 (js/Number.isInteger s0))))
    ;; ---- warm, the same discipline the measured plan uses -----------------
    (let [f (sink-loop iters)]
      (dotimes [_ 3] (f))
      (dotimes [_ 6] (measure f 256 1))
      (println ";;")
      (println ";; PROBE 1 — the bare keep! loop, counter seeded Smi vs double")
      (println (gstring/format ";;   PREDICTED difference: %d B x %d iters = %d B/call, at EVERY reps"
                       box-b iters (* box-b iters)))
      (doseq [reps sweep]
        (let [smi (probe f reps windows 1 (advanced-by iters))
              dbl (probe f reps windows 0.5 (advanced-by iters))
              step (- (/ (:p50 dbl) reps) (/ (:p50 smi) reps))]
          (probe-line "keep!-loop  seed=Smi" reps smi reps)
          (probe-line "keep!-loop  seed=dbl" reps dbl reps)
          (println (gstring/format ";;     -> step %10s B/call   predicted %d   %+.2f%%   (window at seed=dbl would be %s B if linear)"
                           (fmt step) (* box-b iters)
                           (* 100.0 (/ (- step (* box-b iters)) (* box-b iters)))
                           (fmt (* reps (+ (* box-b iters) (/ (:p50 smi) reps))))))))
      ;; ---- PROBE 2 — the positive control, predicted vs measured ----------
      (println ";;")
      (println ";; PROBE 2 — POSITIVE CONTROL: .slice() of a packed array, predicted vs measured")
      (println ";;   predicted per copy = JSArray 32 B + backing store (16 B header + 8 B x D),")
      (println ";;   and, in the RETIRED counter spelling only, one 16 B HeapNumber for the")
      (println ";;   double the DBL arms store into the shared counter. The SMI arms store an")
      (println ";;   Smi, so they are predicted to carry no box in EITHER spelling — which is")
      (println ";;   what makes the pair diagnostic rather than decorative.")
      (doseq [[kind d] (concat (map (fn [d] ["DBL" d]) ctl-double-ds)
                               (map (fn [d] ["SMI" d]) ctl-smi-ds))]
        (let [pred (+ 32 16 (* 8 d))
              boxed? (= "DBL" kind)
              pred-box (if boxed? (+ pred box-b) pred)
              reps (max 1 (js/Math.round (/ 500000 pred)))
              elt0 (aget (get @ctl-templates (ctl-key kind d)) 0)
              ;; READ-BACK for the control: it writes the copy's element 0 into
              ;; the counter, so after the window the counter must HOLD that
              ;; element. A window where the copy was optimised away fails it.
              ctl-arm (arm-ctl kind d)
              _    (do (dotimes [_ 3] (ctl-arm)) (dotimes [_ 6] (measure ctl-arm 64 1)))
              ;; The control is the ONE arm whose counter spelling this bead
              ;; changes, so the read-back accepts either: the RETIRED spelling
              ;; leaves the counter HOLDING elt0, the shipped one ADVANCES it
              ;; once per call. Neither is satisfied if the copy did not run.
              r    (probe ctl-arm reps windows 0.5
                          (fn [before after reps]
                            (or (== after elt0) (== (- after before) reps))))
              meas (/ (:p50 r) reps)]
          (println (gstring/format ";;   %s D=%-6d reps=%-5d measured %10s B/copy   no-box %6d (%+.2f%%)   +box %6d (%+.2f%%)   %d unverified of %d"
                           kind d reps (fmt meas)
                           pred (* 100.0 (/ (- meas pred) pred))
                           pred-box (* 100.0 (/ (- meas pred-box) pred-box))
                           (:unverified r) (:windows r)))))
      ;; ---- PROBE 3 — the three refusing arms, both seeds ------------------
      (println ";;")
      (println ";; PROBE 3 — the refusing arms themselves, counter seeded both ways")
      (doseq [[label f* reps per] [["P-SCOPEH" arm-p-scope-h 4000 iters]
                                   ["P-SCOPEH" arm-p-scope-h 818  iters]
                                   ["P-INHERH" arm-p-inher-h 818  iters]
                                   ["P-INHERH" arm-p-inher-h 4000 iters]
                                   ["P-RKV"    arm-p-rkv     1269 iters]
                                   ["P-RKV"    arm-p-rkv     4000 iters]
                                   ;; P-EMIT is the NEGATIVE control: it is the
                                   ;; one 4000-rep arm with no `keep!` in it, and
                                   ;; the one the guard passes. It must not step.
                                   ["P-EMIT"   arm-p-emit    4000 0]]]
        (dotimes [_ 3] (f*))
        (dotimes [_ 6] (measure f* 256 1))
        (let [smi (probe f* reps windows 1 (advanced-by per))
              dbl (probe f* reps windows 0.5 (advanced-by per))]
          (probe-line (str label " seed=Smi") reps smi reps)
          (probe-line (str label " seed=dbl") reps dbl reps)
          (println (gstring/format ";;     -> step %10s B/call  (%s B per inner iteration)"
                           (fmt (- (/ (:p50 dbl) reps) (/ (:p50 smi) reps)))
                           (fmt (/ (- (/ (:p50 dbl) reps) (/ (:p50 smi) reps)) iters))))))
      ;; ---- PROBE 4 — is C-FRAME's step real, or the instrument's floor? ----
      ;; rf2-c0awb pointed `C-FRAME` at a frame the rig actually registers and
      ;; the arm went from 0.0 to 32.0 B/call. 32.0 B/call at reps=4000 is
      ;; exactly what `P-SCOPEH` — an arm that allocates essentially nothing —
      ;; also reads, so the figure has to be separated from the floor before it
      ;; is quoted. A REAL per-call allocation is rep-INDEPENDENT; a floor is a
      ;; roughly fixed number of bytes per WINDOW and therefore falls as 1/reps.
      ;; Both halves of the pair are swept, because the miss is the matched
      ;; control: identical arm shape, identical windows, one branch different.
      (println ";;")
      (println ";; PROBE 4 — C-FRAME hit vs miss, swept across window sizes")
      (println ";;   rep-INDEPENDENT B/call = real allocation; ~constant B/window = the floor")
      (doseq [[label f* seed verify]
              [["C-FRAME  HIT s=1"     (arm-c-frame)      1         (advanced-by 1)]
               ;; `-main` never resets the counter — it stands near 1.2e8 by the
               ;; time C-FRAME is measured there. That is still an Smi with
               ;; pointer compression off, but rf2-xu0ma WAS a counter-state
               ;; effect, so the magnitude is swept rather than assumed away.
               ["C-FRAME  HIT s=1.2e8" (arm-c-frame)      120000000 (advanced-by 1)]
               ["C-FRAMEG get-only"    (arm-c-frame-get)  1         (advanced-by 1)]
               ["C-FRAMEX MISS"        (arm-c-frame-miss) 1
                ;; the miss feeds `keep!` a nil, so the counter must NOT move —
                ;; a window where it did was not measuring the miss
                (fn [before after _] (== after before))]]]
        (dotimes [_ 3] (f*))
        (dotimes [_ 6] (measure f* 256 1))
        (doseq [reps sweep]
          (let [r (probe f* reps windows seed verify)]
            (probe-line label reps r reps))))
      ;; ---- PROBE 5 — the SAME sweep, once `frame/frame` has a SECOND caller --
      ;;
      ;; rf2-tmzie. PROBE 4 above and the measured plan disagree about the same
      ;; closure by a factor of the rep count, and the sweep says which is which:
      ;; PROBE 4 is flat in B/WINDOW (so ~0 B/call), the plan is flat in B/CALL
      ;; across a 62x range of window sizes. Both are stable, so this is not
      ;; noise; something about the two PROCESSES differs.
      ;;
      ;; The candidate is escape analysis, and it is testable. In PROBE 4 the
      ;; measured loop is the ONLY thing in the process that calls
      ;; `frame/frame`, so V8 can inline it whole — registry `get`, visibility
      ;; predicate and all — into one loop body, prove nothing it builds
      ;; escapes, and delete it. `-main` cannot offer that: its `RFWRITE-*` arms
      ;; drive `frame/replace-app-db!`, whose `commit-frame-transition!` calls
      ;; `frame/frame` on every write, so the function is hot from a second site
      ;; whose result genuinely escapes.
      ;;
      ;; So: run the real write path until that second call site is hot, then
      ;; re-run PROBE 4's exact sweep on a FRESH closure over the same id, in
      ;; the SAME process. Nothing else changes. If the arm converts from
      ;; ~constant-per-window to ~32-per-call, the elision is named and `-main`
      ;; is the reading a real application — which never has a single-caller
      ;; `frame/frame` — actually gets.
      (println ";;")
      (println ";; PROBE 5 — PROBE 4's sweep again, after the SHIPPED write path has made")
      (println ";;   `frame/frame` hot from a SECOND call site (commit-frame-transition!).")
      (println ";;   ~constant B/window -> still elided;  flat B/call -> the elision is gone")
      (let [writer (fn [] (arm-rfwrite 0))]
        (dotimes [_ 20000] (writer))
        (println (gstring/format ";;   (20000 frame/replace-app-db! writes through frame 0 first; sink2 %s)"
                         (some? @sink2))))
      (doseq [[label f* seed verify]
              [["C-FRAME  HIT s=1"  (arm-c-frame)     1 (advanced-by 1)]
               ["C-FRAMEG get-only" (arm-c-frame-get) 1 (advanced-by 1)]
               ;; the inline re-spelling of the same work — it has no call to
               ;; leave un-inlined, so if the step is the CALL and not the WALK
               ;; this arm stays on the floor while `C-FRAME` leaves it.
               ["C-FRAMEV inline"   (arm-c-frame-vis) 1 (advanced-by 1)]]]
        (dotimes [_ 3] (f*))
        (dotimes [_ 6] (measure f* 256 1))
        (doseq [reps sweep]
          (let [r (probe f* reps windows seed verify)]
            (probe-line label reps r reps))))
      (println ";;")
      (println (gstring/format ";; sink %s %s" @sink (some? @sink2))))))
