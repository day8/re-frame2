(ns re-frame.bench.p0-workcount
  "EP-0038 P0 — the WORK CENSUS: three monotone counters read per measured
  window, beside the byte counters (rf2-n1b9h).

  ## The question it exists to answer

  `rf2-77gz8` measured the floor arm's steady-state `rise/W` sitting in
  either of two levels EXACTLY 3,792 B apart at one substrate revision,
  page-global, per-write, byte-stable for fourteen rounds, and certifying
  in both. `rf2-77gz8`'s re-analysis narrowed the cause to two candidates
  the committed corpus cannot separate:

    (a) DIFFERENT WORK per write — a re-entrant registration, a duplicated
        reaction, an extra pass.
    (b) A V8 TIER or DEOPTIMISATION in the compiled write path — most
        specifically a loss of escape analysis, which turns elided
        allocations into real ones at a fixed cost per invocation.

  `alloc-tick` counts WRITES, so an identical schedule says nothing about
  the work inside one. Nothing committed counts that. This does:

    events   — event-handler invocations (the `:p0/write-*` handler bodies)
    subs     — subscription recomputations (the `reg-sub` computation fns)
    renders  — render calls (each subscribing BOUNDARY body, and the
               floor's own per-cell element builder)

  Read at a window's open and again at its close, the difference is the
  work that window did. Counts IDENTICAL across the two modes while the
  bytes differ by 3,792 per write excludes (a) and leaves a per-invocation
  allocation, i.e. a runtime codegen effect. Counts that DIFFER establish
  (a) and exclude (b).

  ## Why a census rather than a clock

  Every quantity here is a MONOTONE COUNTER. It is not a duration, it has
  no resolution and it does not degrade under load, so this reads the same
  on a busy box as on a quiet one — which is the whole reason it is the
  first move rather than `--trace-deopt`.

  ## OFF AT COMPILE TIME, and that is the point

  [[counting?]] is a `goog-define`, default **false**. Every call site
  below is a MACRO expanding to `(when counting? …)`, so under `:advanced`
  with the flag false Closure constant-folds the gate, the branch is
  dead-code-eliminated, and [[counts]] itself becomes unreferenced and goes
  with it. **A run that does not ask for the census compiles the bundle it
  compiled before this namespace existed.**

  That is not tidiness. The rig's blobs are the constancy guarantee the
  whole `alloc-9jrhi` series and the `rf2-nkeba` figures are published
  against, and an always-on counter would have moved the write path under
  every one of them. A macro rather than a function for the same reason
  `re-frame.performance/mark-and-measure` is one: a function-shaped helper
  forces its call site to survive DCE even when its body does nothing.

  The driver arms it with `P0_WORK_COUNT=1`, which adds the closure-define
  to its `--config-merge`; [[armed?]] reports back what the page was
  actually compiled with, so the driver proves the flag took rather than
  trusting it.

  ## Nothing here allocates inside a measured window

  [[counts]] is a `Float64Array` built ONCE at namespace load. An increment
  is an unboxed store into a preallocated slot — the same reason
  `p0-heap/alloc-prepare!` sizes its sample buffer outside the window. A
  counter that allocated would be indistinguishable from the arm it is
  counting.

  `Float64Array` and not `Int32Array`: a double holds every integer below
  2^53 exactly, so no run this rig can drive wraps, and a wrapped counter
  would read as a NEGATIVE delta that the driver would have to special-case.

  [[snapshot]] DOES allocate — it answers a fresh object — and it is called
  only OUTSIDE the sampled region, before the window's first counter read
  and after its last.

  Owner: rf2-n1b9h, under the operator-owned governance set enumerated once
  in `docs/design/hicasso/studio/README.md`."
  #?(:cljs (:require-macros [re-frame.bench.p0-workcount])))

;; ---------------------------------------------------------------------------
;; The switch, and the counters
;; ---------------------------------------------------------------------------

#?(:cljs (goog-define counting? false)
   :clj  (def counting? false))

;; The three monotone counters, in ONE preallocated `Float64Array`:
;;
;;   slot 0  events   — event-handler invocations
;;   slot 1  subs     — subscription recomputations
;;   slot 2  renders  — boundary renders
;;
;; Never reset. Monotone for the life of the page is what makes a window's
;; work the DIFFERENCE of two readings rather than a quantity the instrument
;; has to be told to zero — and a counter the instrument has to zero is a
;; counter that can be zeroed at the wrong moment.
;;
;; The slot numbers are literals at the three macro call sites below rather
;; than named constants, because a named constant read through a var is one
;; more thing between the call site and the store; the mapping lives here,
;; once, where a reader looks for it.
#?(:cljs (defonce counts (js/Float64Array. 3))
   :clj  (def counts nil))

#?(:cljs
   (defn bump!
     "Increment one counter. Called only from the macros below, which is
     what keeps it behind the compile-time gate — a direct call would
     survive DCE."
     [slot]
     (aset counts slot (+ 1 (aget counts slot)))
     nil))

;; ---------------------------------------------------------------------------
;; The call sites — macros, so an unarmed build carries none of them
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- cljs-env?
     "True when this macro is expanding into ClojureScript. The counters are
     a browser instrument; on the JVM every site below expands to `nil`, so
     `p0-fixture` — which is `.cljc` — still loads."
     [env]
     (boolean (:ns env))))

#?(:clj
   (defmacro event!
     "Count ONE event-handler invocation."
     []
     (when (cljs-env? &env)
       `(when re-frame.bench.p0-workcount/counting?
          (re-frame.bench.p0-workcount/bump! 0)))))

#?(:clj
   (defmacro sub!
     "Count ONE subscription recomputation — the `reg-sub` computation fn
     running, which is the quantity a duplicated reaction would move."
     []
     (when (cljs-env? &env)
       `(when re-frame.bench.p0-workcount/counting?
          (re-frame.bench.p0-workcount/bump! 1)))))

#?(:clj
   (defmacro render!
     "Count ONE render call — one BOUNDARY body running."
     []
     (when (cljs-env? &env)
       `(when re-frame.bench.p0-workcount/counting?
          (re-frame.bench.p0-workcount/bump! 2)))))

;; ---------------------------------------------------------------------------
;; The reading side — outside every measured window
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn snapshot
     "The three counters as a flat JS object.

     A literal `#js` map and never `clj->js`, for the reason `p0-heap/mount!`
     carries the scar from: `clj->js` would render a keyword ending in `?`
     as a key nothing on the driver side reads, and a census that always
     answered `undefined` would look like a census that never moved.

     Allocates, so it is called only outside the sampled region."
     []
     #js {:events  (aget counts 0)
          :subs    (aget counts 1)
          :renders (aget counts 2)}))

#?(:cljs
   (defn armed?
     "What the page was COMPILED with, not what the driver asked for. The
     closure-define rides on a `--config-merge`, and a merge that failed to
     reach the compiler would leave a bundle whose counters never move —
     which reads exactly like a page that did no work."
     []
     counting?))
