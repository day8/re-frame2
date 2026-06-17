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
  offending value (shape / type / count / keys / bounded head), never the
  value itself.

  This is a content-byte-for-byte mirror of
  `re-frame.error/diag-value-summary` (one diagnostic vocabulary across
  every framework surface). It is replicated INLINE here, rather than
  required, because the day8/reagent-slim artefact is bundle-isolated and
  MUST NOT `:require` re-frame.* (the slim bundle-isolation gate). This ns
  is a dependency-free leaf — it `:require`s nothing inside reagent2 — so
  template / server / component can all pull it without a cycle.

  Pure; no runtime state. Hot-path safe — runs only on the failure path."
  (:refer-clojure :exclude []))

(def ^:private head-limit
  "Max chars of a leaf scalar's printed form to retain in a diagnostic
  head — bounded so a large/secret scalar never rides whole into ex-data."
  24)

(defn- head
  "A bounded, content-light head string for a scalar leaf — enough to
  recognise the value's flavour without carrying the whole value off-box."
  [v]
  (cond
    (keyword? v) (str v)
    (symbol? v)  (str v)
    :else
    (let [s (str v)]
      (if (> (count s) head-limit)
        (str (subs s 0 head-limit) "…")
        s))))

(defn value-summary
  "EP-0015-safe SUMMARY of a value for a reagent-slim diagnostic message
  or ex-data slot (Spec 015 §Data-Classification, rf2-uwqale). Returns a
  small data map describing the value's SHAPE — never the value itself —
  so the diagnostic survives off-box capture without leaking app-owned
  sensitive/large hiccup content. Mirrors
  `re-frame.error/diag-value-summary` exactly."
  [v]
  (cond
    (nil? v)     {:type :nil}
    (map? v)     {:type  :map
                  :count (count v)
                  :keys  (try (vec (sort-by str (keys v)))
                              (catch :default _ (vec (keys v))))}
    (vector? v)  {:type :vector :count (count v)}
    (set? v)     {:type :set :count (count v)}
    (string? v)  {:type :string :count (count v) :head (head v)}
    (keyword? v) {:type :keyword :head (head v)}
    (symbol? v)  {:type :symbol :head (head v)}
    (boolean? v) {:type :boolean :head (str v)}
    (number? v)  {:type :number :head (head v)}
    (seq? v)     {:type :seq}
    (fn? v)      {:type :fn}
    (seqable? v) {:type :seq}
    :else        {:type :scalar :head (head v)}))
