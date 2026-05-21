(ns day8.re-frame2-causa.panels.shared.sub-input-paths
  "Registry-side walk for sub input-paths (rf2-gblq6).

  ## Why this exists

  The App-DB downstream-subs popover (rf2-op9v2 / PR #1747) lists every
  sub that ran in the focused cascade — full cascade, not path-filtered.
  Without per-sub `:input-paths` attribution the popover can't surgically
  answer 'which subs read THIS path?'. The substrate's
  `:rf.cascade/captured` aggregate doesn't carry per-sub input-paths
  either: subs read app-db via arbitrary handler-fn bodies (`get-in`,
  destructuring, walks), and the substrate adapter has no tap on those
  reads.

  This helper bridges the gap by walking the static sub registry —
  pure data, no app-db, no reactive runtime. Same shape as
  `re-frame.subs.tooling/sub-topology`, just narrower: walk the
  `:input-signals` chain and report the **layer-1 leaves** the
  target sub ultimately depends on.

  Layer-1 leaves are the subs that read `app-db` directly. They're
  the closest static proxy for 'which paths does this sub touch'
  because the body of a layer-1 sub IS the contract — and the
  convention `(reg-sub :foo/bar (fn [db _] (get-in db [:foo :bar])))`
  means the sub-id of a layer-1 sub often carries the path semantics
  the operator hovered.

  ## What the walk returns

  For each sub-id, an `:input-paths` slot — `nil` or a sorted vector
  of layer-1 sub-ids:

    - `nil` — **unknown**. The sub has at least one unresolvable
      upstream (cycle, missing registration, opaque resolution). The
      popover's path-filter treats `nil` as 'could match any path' —
      conservative include.

    - `[layer-1-sub-id ...]` — the layer-1 leaves the sub composes
      over. Singleton vector `[sub-id]` for layer-1 subs themselves
      (each layer-1 sub IS its own leaf). Layer-n subs union the
      leaves of every upstream signal. The vector is `sort-by pr-str`
      ordered for deterministic test output.

  Layer-1 sub-ids stand in for paths because there's no static
  `:rf.sub/path` declaration on sub registrations today (Spec 002
  §Subscriptions composing — handler-fn bodies are opaque). When a
  future bead adds an explicit `:rf.sub/path` slot, the resolver can
  read it directly and the popover gains true path-vec attribution.
  Until then, layer-1 sub-ids are the cleanest static proxy.

  ## Cycle + missing handling

  Cycle detection uses a per-call `visited?` set; encountering a
  visited sub-id during recursion returns the `:unknown` sentinel.
  Subs not found in the registrar also return `:unknown`. The walk
  is bounded by the registry size — no transitive recurse can
  revisit a node — so total cost is `O(N * fanout)` where N is the
  registry size.

  ## Path-filter contract

  `sub-touches-path?` takes a hovered path-vec + a sub's declared
  input-paths (the `resolve-input-paths` result) and returns truthy
  when the sub should appear in the popover.

  Match rules:

    1. `input-paths` is `nil` → include (unknown ⇒ conservative).
    2. `input-paths` is `[]` → exclude. The sub composes no layer-1
       reads — it depends on no app-db state, so it can't have been
       affected by the hovered path.
    3. `input-paths` carries layer-1 sub-ids → include when ANY
       leaf's sub-id overlaps the hovered path via the
       `sub-id-touches-path?` heuristic below.

  `sub-id-touches-path?` matches a layer-1 sub-id against a path-vec
  via keyword-segment overlap:

    - The sub-id is split into namespace + name (`:cart/state` →
      `:cart` + `:state`).
    - The path-vec's keyword segments are compared against the
      sub-id's segments. Any segment match counts as a touch.

  This heuristic matches the spec §4.4 example (path `[:cart :state]`
  → sub `:cart/state`) without claiming static guarantees. The
  conservative-include branch (nil ⇒ include) keeps the popover
  honest when the heuristic can't speak.

  ## CLJC

  The helper is `.cljc` so the same code runs in CLJS (Causa's
  primary target) and JVM tests. The walk has no platform-specific
  branches — pure registry data."
  (:require [re-frame.core :as rf]))

;; ---- registry projection -------------------------------------------------

(defn- input-signal-ids
  "Project a sub's `:input-signals` slot — vector of `[query-id args...]`
  — to a vector of just the upstream sub-ids. Mirrors the
  `re-frame.subs.tooling/sub-topology` projection."
  [input-signals]
  (mapv first input-signals))

(defn sub-meta-map
  "Snapshot of every registered sub's static metadata as a flat map
  `{sub-id {:input-signal-ids [...] :layer-1? bool}}`. Pure data over
  `rf/registrations` — no app-db, no reactive runtime.

  Layer-1 subs (which read app-db directly) report
  `:input-signal-ids []` + `:layer-1? true`. Layer-2+ subs report
  their declared upstream sub-ids + `:layer-1? false`.

  Tests can pass an injected map to `resolve-input-paths` / `resolve-
  many` so the walk is testable without seeding the registrar."
  []
  (reduce-kv
    (fn [acc sub-id meta]
      (let [signals (input-signal-ids (:input-signals meta))]
        (assoc acc sub-id
               {:input-signal-ids signals
                :layer-1?         (empty? signals)})))
    {}
    (rf/registrations :sub)))

;; ---- the walk -----------------------------------------------------------

(defn- -resolve
  "Recursive resolution. Returns either a set of layer-1 sub-ids the
  target ultimately depends on, or the keyword `:unknown` when the
  walk can't terminate (cycle, missing registration).

  The accumulator is a set rather than vector — duplicate-upstream
  diamonds collapse to a single leaf. Top-level caller projects the
  set to a sorted vector for stable test assertions.

  `:unknown` sentinel + the `visited?` set live inside the call;
  callers consume `resolve-input-paths` which converts the sentinel
  to `nil`."
  [sub-id sub-meta-map visited?]
  (cond
    (contains? visited? sub-id)
    :unknown ;; cycle — short-circuit to unknown

    :else
    (let [entry (get sub-meta-map sub-id)]
      (cond
        (nil? entry)
        :unknown ;; missing registration — unknown

        (:layer-1? entry)
        ;; Layer-1 leaf — the sub itself IS the static input-path
        ;; surface. Return a set containing just this sub-id so a
        ;; downstream layer-2 sub composing several layer-1 leaves
        ;; sees the union.
        #{sub-id}

        :else
        ;; Layer-n — recurse into every upstream. If ANY upstream is
        ;; unknown, the whole result is unknown (conservative — we
        ;; can't claim to know paths when part of the chain is
        ;; opaque).
        (let [visited?' (conj visited? sub-id)]
          (reduce
            (fn [acc upstream-id]
              (let [u (-resolve upstream-id sub-meta-map visited?')]
                (if (= u :unknown)
                  (reduced :unknown)
                  (into acc u))))
            #{}
            (:input-signal-ids entry)))))))

(defn resolve-input-paths
  "Return the layer-1 leaves `sub-id` ultimately depends on, or `nil`
  when the walk can't terminate. Pure fn over the registry projection
  returned by `sub-meta-map` (or any equivalent `{sub-id {:input-
  signal-ids [...] :layer-1? bool}}` shape).

  Result is a sorted vector of layer-1 sub-ids (sort by `pr-str` so
  tests get deterministic ordering across keyword + string ids), or
  `nil` for unknown. Layer-1 subs themselves return a single-element
  vector `[sub-id]` so the leaf IS its own input-path proxy.

  Empty layer-1 vector (`[]`) is reserved for the theoretical case
  of a layer-n sub with no declared upstream signals — pathological
  in practice (such a sub would never recompute), but representable
  for completeness.

  See the namespace docstring for the contract + why we return
  layer-1 sub-ids rather than path-vecs."
  ([sub-id]
   (resolve-input-paths sub-id (sub-meta-map)))
  ([sub-id sub-meta-map]
   (let [result (-resolve sub-id sub-meta-map #{})]
     (when (not= result :unknown)
       (vec (sort-by pr-str result))))))

(defn resolve-many
  "Batch form — return `{sub-id input-paths}` for every sub-id in the
  registry. Tools that need the full map (e.g. Causa's downstream
  popover walking every cascade-captured sub) call this once per
  walk to amortise the registrar projection."
  ([]
   (resolve-many (sub-meta-map)))
  ([sub-meta-map]
   (reduce-kv
     (fn [acc sub-id _entry]
       (assoc acc sub-id (resolve-input-paths sub-id sub-meta-map)))
     {}
     sub-meta-map)))

;; ---- path-filter ---------------------------------------------------------

(defn- keyword-segments
  "Return the set of keyword 'segments' a sub-id occupies. Keywords
  carry up to two segments: namespace + name. `:cart/state` →
  `#{:cart :state}`; `:foo` → `#{:foo}`. Strings and other id shapes
  return `#{}` — out of scope for the path-overlap heuristic."
  [sub-id]
  (cond
    (keyword? sub-id)
    (let [n  (name sub-id)
          ns (namespace sub-id)]
      (cond-> #{(keyword n)}
        (and ns (seq ns)) (conj (keyword ns))))
    :else
    #{}))

(defn- path-keyword-segments
  "Return the keyword segments of a path-vec (filtering out
  non-keyword segments like indices, strings)."
  [path-vec]
  (set (filter keyword? path-vec)))

(defn sub-id-touches-path?
  "Heuristic: returns true when a layer-1 sub-id's keyword segments
  overlap a path-vec's keyword segments. Matches the spec §4.4
  example shape (path `[:cart :state]` ↔ sub `:cart/state`).

  Pure fn — no app-db, no registry. Exposed for tests + reuse by
  panels that want to render 'this layer-1 sub touches this path'
  affordances."
  [sub-id path-vec]
  (let [sub-segs  (keyword-segments sub-id)
        path-segs (path-keyword-segments path-vec)]
    (boolean
      (and (seq sub-segs)
           (seq path-segs)
           (some sub-segs path-segs)))))

(defn sub-touches-path?
  "Returns truthy when a sub whose declared `input-paths` (the
  `resolve-input-paths` result) should appear in the popover for a
  hovered `path-vec`.

  Match rules (see the namespace docstring §Path-filter contract):

    1. `input-paths` is `nil` → include (unknown ⇒ conservative).
    2. `input-paths` is `[]` → exclude (sub composes no layer-1
       reads — it can't be affected by ANY path).
    3. `input-paths` carries layer-1 sub-ids → include when ANY leaf
       sub-id overlaps `path-vec` via `sub-id-touches-path?`.

  `path-vec` may be `nil`; treated as `[]` (root) — falls through to
  rule 3 with no keyword segments, so non-nil non-empty layer-1
  leaves always EXCLUDE when the path is root. Operators hovering
  the root would expect every sub in the cascade — for that case the
  caller should bypass this filter entirely (path-vec `[]` is a
  whole-db signal)."
  [input-paths path-vec]
  (cond
    (nil? input-paths) true                      ;; rule 1 — unknown
    (empty? input-paths) false                   ;; rule 2 — no app-db reads
    :else                                        ;; rule 3 — keyword overlap
    (boolean (some #(sub-id-touches-path? % path-vec) input-paths))))
