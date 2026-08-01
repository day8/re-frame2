(ns reagent2.impl.diag
  "EP-0015-safe diagnostic value summary for the day8/reagent-slim
  artefact (rf2-uwqale).

  Spec 015 §Data-Classification forbids raw application values in
  framework exception messages / ex-data: a hiccup head, child vector, or
  Form-3 spec baked into a flattened message or ex-data slot is captured
  by browser consoles, error boundaries, host logs, and SSR/static-export
  error handlers BEFORE the record projector (`project-egress`) can
  classify the original paths — path-based projection cannot recover a
  value that no longer sits at a path, and hiccup children can carry
  app-owned sensitive/large values. The fix is to carry a SUMMARY of the
  offending value (its type, and the size of a counted collection), never
  the value itself.

  This is a content-byte-for-byte mirror of
  `re-frame.error/diag-value-summary` (one diagnostic vocabulary across
  every framework surface). It is replicated INLINE here, rather than
  required, because the day8/reagent-slim artefact is bundle-isolated and
  MUST NOT `:require` re-frame.* (the slim bundle-isolation gate). This ns
  is a dependency-free leaf — it `:require`s nothing inside reagent2 — so
  template / server / component can all pull it without a cycle.

  Pure; no runtime state. Hot-path safe — runs only on the failure path."
  (:refer-clojure :exclude []))

(defn value-summary
  "EP-0015-safe SUMMARY of a value for a reagent-slim diagnostic message
  or ex-data slot (Spec 015 §Data-Classification, rf2-uwqale). Returns a
  small data map describing the value's SHAPE — never the value itself —
  so the diagnostic survives off-box capture without leaking app-owned
  sensitive/large hiccup content. Mirrors
  `re-frame.error/diag-value-summary` exactly.

  Shape (`:count` present only for a counted collection or string):

    {:type   :map | :vector | :seq | :set | :keyword | :symbol
             | :string | :number | :boolean | :nil | :fn | :scalar
     :count  <int>}

  **Content-free BY CONSTRUCTION (rf2-210uq).** Every value the summary
  can carry is either a member of the closed `:type` vocabulary above or
  an integer count, so nothing here is derived from the input's CONTENT
  and the serialized summary is a fixed size whatever arrives. The former
  `:head` (a raw 24-char prefix, unbounded for keywords/symbols) and map
  `:keys` (uncapped, unsanitised, app-controlled) legs are gone; so is the
  `(str v)` that let a hostile `toString` throw out of a diagnostic."
  [v]
  (cond
    (nil? v)     {:type :nil}
    (map? v)     {:type :map :count (count v)}
    (vector? v)  {:type :vector :count (count v)}
    (set? v)     {:type :set :count (count v)}
    (string? v)  {:type :string :count (count v)}
    (keyword? v) {:type :keyword}
    (symbol? v)  {:type :symbol}
    (boolean? v) {:type :boolean}
    (number? v)  {:type :number}
    (seq? v)     {:type :seq}
    (fn? v)      {:type :fn}
    (seqable? v) {:type :seq}
    :else        {:type :scalar}))
