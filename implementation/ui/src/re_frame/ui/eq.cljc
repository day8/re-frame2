(ns re-frame.ui.eq
  "`rf=` — the ruled per-slot equality of the compiled-view substrate.

  RULED (Mike, 2026-07-12, spec-004 rewrite §`ui/defview` Memo-by-default):
  per slot, `rf=` is

      Object.is(a, b)  OR  (= a b)

  Consequences (all pinned by `re-frame.ui.eq-cljs-test`):

  - CLJS data (anything with `IEquiv`, incl. records and js/Date) compares
    by VALUE — fresh-but-equal literals do not repaint.
  - Host/foreign values (plain JS objects, arrays, functions, React
    elements) fall through to identity — in-place-mutated host objects do
    not repaint (mutable foreign values belong at an explicit boundary).
  - `##NaN` props are repaint-stable via the `Object.is` branch.
  - `-0`/`+0` compare EQUAL via the `=` branch (deliberate, harmless
    divergence from raw `Object.is`).
  - JS `undefined` and `null` both read as nil (an absent slot and a
    present-nil slot compare equal — Q2 consequence).

  The identity check doubles as the generated comparator's fast path.
  Teach as: \"React.memo, except CLJS data compares by value\".

  On the JVM (where trees are plain Clojure data and no memoization
  exists) the same truth table holds: `identical?` stands in for
  `Object.is`, `=` gives the value branch, and a numeric branch supplies
  what JS number semantics give the client for free — NaN self-equality
  and `(rf= 1 1.0)`/`(rf= -0.0 0.0)` being true (JS has one number
  type). One predicate, one truth table, both hosts.")

(defn rf=
  "The ruled per-slot equality: `Object.is(a,b) OR (= a b)`."
  [a b]
  #?(:cljs (or ^boolean (js/Object.is a b)
               (= a b))
     :clj  (or (identical? a b)
               (= a b)
               (and (number? a) (number? b)
                    (or (and (Double/isNaN (double a)) (Double/isNaN (double b)))
                        (== (double a) (double b)))))))

(defn deps-rf=?
  "The effect-dependency equality doctrine: two authored deps vectors are equal
  iff they have equal arity and every authored slot is `rf=` its counterpart —
  the SAME per-slot `rf=` the memo/prop comparator uses, walked element-wise.

  Element-wise is load-bearing: `rf=` on the WHOLE vector would fall to `(= a b)`
  (the vectors are freshly allocated, so the `Object.is` fast path misses), and
  `(= [##NaN] [##NaN])` is FALSE — CLJS `=` is not NaN-reflexive — so a stable
  `##NaN` slot would spuriously re-run the effect every render. The per-slot walk
  routes each `##NaN` slot through `rf=`'s `Object.is` branch (NaN-reflexive) and
  each rebuilt-but-equal CLJS collection slot through its `=` branch, so both
  compare equal and the effect does not re-run."
  [a b]
  (and (== (count a) (count b))
       (loop [i 0]
         (if (< i (count a))
           (if (rf= (nth a i) (nth b i))
             (recur (inc i))
             false)
           true))))
