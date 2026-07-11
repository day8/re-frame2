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
  exists) the same law holds with `identical?` standing in for
  `Object.is`; `=` gives the value branch."
  (:refer-clojure :exclude [rf=]))

(defn rf=
  "The ruled per-slot equality: `Object.is(a,b) OR (= a b)`."
  [a b]
  #?(:cljs (or ^boolean (js/Object.is a b)
               (= a b))
     :clj  (or (identical? a b)
               (= a b))))
