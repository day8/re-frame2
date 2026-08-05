(ns ^:no-doc re-frame.ssr.diagnostic
  "Cycle-safe printing for the SSR emitters' own DIAGNOSTICS (rf2-9s68n).

  A validator that rejects a malformed form correctly but EXPLODES while
  explaining why is still broken on hostile input. The SSR emitters build
  their `:rf.error/invalid-hiccup-head` / `:rf.error/ui-tree-malformed`
  messages with `(pr-str el)` over the offending runtime value, and on
  ClojureScript that is a live stack-overflow: `cljs.core`'s printer has
  exactly TWO branches that descend into a foreign JS value — `object?`
  (prints `#js {…}` by walking `js-keys`) and `array?` (prints `#js […]`
  by walking elements, `cljs/core.cljs` `pr-writer-impl`) — and NEITHER
  carries a seen-set. Every other print form a foreign value can take
  (`#inst`, `#\"re\"`, `#object[Ctor]`, `#object[Symbol(x)]`) is bounded
  and recurses into nothing.

  So an author who writes `[ThemeContext.Provider {…}]` on the hiccup
  tier — the ordinary mistake `:rf.error/invalid-hiccup-head` exists to
  catch, and one reachable straight from the shipped
  `re-frame.ssr/render-to-string` — got `RangeError: Maximum call stack
  size exceeded` and NO message at all. React 19's `createContext`
  returns an object carrying a `Provider` key that points back at the
  context itself, so `ctx.Provider` IS a cycle; the defect is a property
  of the foreign object graph rather than of React, and a hand-built
  `(unchecked-set o \"self\" o)` reproduces it exactly.

  `re-frame.ssr.hash/canonical-edn-into` met the same crossing from the
  other side (rf2-56iys) and STOPS at those two predicates: a hash wants
  one identity-free token, so collapsing every foreign value is right
  there. A DIAGNOSTIC wants the opposite — it exists to show the author
  their value — so this namespace elides the strict minimum instead:

    **A foreign JS value is replaced only when a cycle is REACHABLE from
    it. Everything else, foreign or not, is handed to `pr-str`
    untouched.**

  Which is what makes the guarantee below hold by construction rather
  than by inspection:

    **An input from which no cycle is reachable is returned IDENTICAL, so
    its diagnostic is byte-identical to the one it produced before this
    namespace existed.** `safe-form` scans first and only rebuilds when
    the scan finds a cycle; on the JVM it is `identity` outright (no
    foreign objects, and no cyclic value can be built from the persistent
    collections these walks otherwise see).

  ## The values this protects, and where they go

  `pr-form` is for the message string; `safe-form` for the `:extra`
  ex-data payload. BOTH matter: a cyclic value that merely rides out in
  `{:extra {:element el}}` explodes at a DOWNSTREAM logger / error
  projector / trace sink that `pr-str`s the ex-data, i.e. at someone
  else's boundary rather than at the emitter's, which is strictly worse
  to diagnose.

  ## rf2-y1jbaq (never stringify an unescaped hiccup form to the wire)

  This does NOT widen the wire surface. A thrown-error message reaches
  the trace bus, the always-on error axis and host logs; the HTTP body is
  built by `re-frame.ssr.ring/render-error-body` from the PROJECTED
  public-error's locked `{:status :code :message}` keys (default
  `\"Something went wrong\"`) and rendered through the emitter's own
  position-appropriate escaping. The elision token is a fixed constant
  carrying no input-derived text, so the only content this namespace can
  add to any surface is content an ACYCLIC form of the same shape already
  put there."
  ;; The WHOLE `:require` clause is CLJS-only: the JVM arm of `safe-form` is
  ;; `identity`, so `clojure.walk` would be an unused namespace there.
  #?(:cljs (:require [clojure.walk :as walk])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The foreign crossing — CLJS only.
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftype ElidedForeign [token]
     Object
     (toString [_] token)
     IPrintWithWriter
     (-pr-writer [_ writer _opts] (-write writer token))))

#?(:cljs
   (def ^:private elided-object
     (ElidedForeign. "#js {…cyclic…}")))

#?(:cljs
   (def ^:private elided-array
     (ElidedForeign. "#js […cyclic…]")))

#?(:cljs
   (defn- descends?
     "Would `cljs.core`'s printer DESCEND into `x`? Exactly the two
     branches of `pr-writer-impl` that do: `object?` (own enumerable keys,
     via `js-keys`) and `array?` (elements). Deliberately the same pair
     `re-frame.ssr.hash/canonical-edn-into` intercepts — widening it would
     make the two walks disagree about what `pr-str` can survive."
     [x]
     (or (object? x) (array? x))))

#?(:cljs
   (defn- reaches-self?
     "Depth-first over the descend-able foreign graph rooted at `x`,
     carrying the ancestors on the CURRENT path. True as soon as a value
     already on that path is reached again — which is precisely the
     condition under which `pr-str` would not terminate.

     Path-scoped, not global: a value reachable twice by DIFFERENT paths
     (a shared subtree) is not a cycle, and `pr-str` prints it twice and
     terminates. Cost tracks `pr-str`'s own — it visits the same graph the
     printer would — so a graph this is slow on is a graph `pr-str` was
     already slow on. Failure path only."
     [x path]
     (cond
       (not (descends? x))            false
       (some #(identical? % x) path)  true
       :else
       (let [path (conj path x)]
         (boolean
           (if (array? x)
             (some #(reaches-self? % path) (array-seq x))
             (some #(reaches-self? (unchecked-get x %) path) (js-keys x))))))))

#?(:cljs
   (defn- cyclic-foreign?
     [x]
     (and (descends? x) (reaches-self? x []))))

#?(:cljs
   (defn- elide-cyclic
     "Leaf rewrite: a foreign value from which a cycle is reachable becomes
     the fixed token; everything else — including an ACYCLIC foreign value,
     which `pr-str` renders fully and terminates on — is returned as-is."
     [x]
     (if (cyclic-foreign? x)
       (if (array? x) elided-array elided-object)
       x)))

#?(:cljs
   (defn- reaches-cycle?
     "Does any value anywhere in `form` carry a foreign cycle? Walks the
     persistent structure (`tree-seq` over `coll?`), which is where a
     hiccup element / tree node keeps its foreign leaves.

     This scan is what buys the byte-identity guarantee: when it says no,
     `safe-form` returns the input object itself and cannot have perturbed
     anything."
     [form]
     (boolean (some cyclic-foreign? (tree-seq coll? seq form)))))

;; ---------------------------------------------------------------------------
;; Public surface.
;; ---------------------------------------------------------------------------

(defn safe-form
  "`form` with every foreign JS value FROM WHICH A CYCLE IS REACHABLE
  replaced by a fixed, identity-free, self-describing token
  (`#js {…cyclic…}` / `#js […cyclic…]`), so the result is safe to `pr-str`
  — by the caller building a message, and by any downstream consumer that
  `pr-str`s it out of `:extra` ex-data.

  Returns `form` ITSELF when no cycle is reachable (always, on the JVM)."
  [form]
  #?(:clj  form
     :cljs (if (reaches-cycle? form)
             (walk/postwalk elide-cyclic form)
             form)))

(defn pr-form
  "`(pr-str form)`, made total: byte-identical to `pr-str` for every form
  `pr-str` terminates on, and a bounded elision instead of `RangeError:
  Maximum call stack size exceeded` for the ones it does not."
  [form]
  (pr-str (safe-form form)))
