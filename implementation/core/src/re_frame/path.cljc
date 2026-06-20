(ns re-frame.path
  "Internal path algebra for `:rf/path` — the shared lightweight-optics
  vocabulary every path-shaped re-frame2 fact inherits (EP-0012). The
  normative contract lives in
  [`spec/Conventions.md` §The `:rf/path` algebra]; this namespace is the
  reference implementation.

  ## Status — INTERNAL (EP-0012 disposition 1)

  The *semantics* are normative immediately; the *names* are NOT public
  API at this slice. There is no `re-frame.core` facade export of
  `rf.path/*` yet, and none is classified. An op graduates to a public
  name only once two or more consumers (flows / schemas / routing /
  resources / EP-0015 frame-config maps / EP-0016 map-form targets) use
  it through this internal namespace without requiring a shape change
  (the concrete graduation gate). Subsystems MUST NOT keep private ad hoc
  overlap / prefix logic once they cite these helpers — there is no
  \"tool-only\" path semantics.

  ## The algebra

    (get value path)        -> focused value, or nil when missing
    (get value path nf)     -> nf when missing
    (lookup value path)     -> {:present? true :value v} | {:present? false}
    (put value path x)      -> value with x installed at path
    (over value path f)     -> value with f applied to the current focus
    (compose p q)           -> the two paths appended
    (prefix? p q)           -> true when p is a prefix of q
    (overlap? p q)          -> true when either path is a prefix of the other

  ## Laws (Conventions §Path laws — generatively tested)

    lookup(put(s, p, x), p) = {:present? true, :value x}     ;; put-lookup
    if lookup(s, p) present with x then put(s, p, x) = s      ;; lookup-put
    put(put(s, p, x), p, y) = put(s, p, y)                    ;; put-put
    compose(p, []) = p ; compose([], p) = p ; assoc.          ;; compose
    get(s, compose(p, q)) = get(get(s, p), q)                 ;; get-compose
    over(s, p, f) = put(s, p, f(get(s, p)))                   ;; over
    overlap? is symmetric; overlap?([], p) = true             ;; overlap?

  Root-path laws (the laws raw `assoc-in` famously violates — it
  assoc's under key `nil` instead of replacing the root):

    get(s, [], nf)  = s
    lookup(s, [])   = {:present? true, :value s}
    put(s, [], x)   = x
    over(s, [], f)  = f(s)
    overlap?([], p) = true

  ## Intermediate-container policy

  Missing intermediate values are treated as maps, matching Clojure's
  `assoc-in` / `update-in` map-creation behaviour. `over` on a missing
  path calls `f` with `nil` (matching `update-in`); a consumer that must
  distinguish missing from present `nil` uses `lookup` first.

  Pure namespace — no runtime state, no trace, no host coupling. `.cljc`
  so the JVM test sweep exercises the algebra without the CLJS runtime."
  (:refer-clojure :exclude [get])
  (:require [clojure.core :as core]
            [re-frame.error :as error]
            [re-frame.identity :as identity])
  #?(:clj (:import [java.util UUID Date]
                   [java.time Instant])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- path shape / normalization ------------------------------------------

(defn- bad-path!
  "Throw the canonical `:rf.error/bad-path` error via the central
  `error/throw-error!` builder (Spec 009 §The thrown-error shape) so the
  message LEADS with the human `reason` sentence and TRAILS with the
  `[:rf.error/bad-path]` greppability token. `where` names the source
  helper; `reason` is the human-facing sentence; `extras` names the
  offending slot (`:bad-path`, `:bad-segment`)."
  [where reason extras]
  (error/throw-error!
    :rf.error/bad-path
    where
    (str "rf.path: " reason)
    {:recovery :fix-path
     :extra    extras}))

(defn normalize
  "Coerce a path input's CONTAINER SHAPE to the canonical vector form. The
  primary path container is a vector; APIs MAY accept any sequential
  collection for migration ergonomics, but every stored declaration MUST
  normalize to a vector (Conventions §Path shape).

  The root path is the EXPLICIT empty vector `[]`; a nil path FAILS CLOSED
  with `:rf.error/bad-path` (EP-0012 fail-closed boundary). An
  omitted path is NOT silently the whole value: a caller that legitimately
  has no path handles that absence BEFORE normalization (e.g. an `or`-default
  to `[]` at its own boundary, where targeting the root is the deliberate
  intent), so an accidental nil here can never silently put/over the whole
  value. `normalize` validates SHAPE only — it does not validate that each
  segment is a concrete EDN identity value; the validated concrete boundary
  is `normalize-concrete`."
  [p]
  (cond
    (nil? p)        (bad-path! 'rf.path/normalize
                               (str "a path must be a sequential collection of segments; "
                                    "the root path is the explicit empty vector [], not nil")
                               {:bad-path p})
    (vector? p)     p
    (sequential? p) (vec p)
    :else           (bad-path! 'rf.path/normalize
                               "a path must be a sequential collection of segments"
                               {:bad-path p})))

;; ---- concrete-segment domain (Conventions §Segment domain) ---------------
;;
;; The shared upper bound for a CONCRETE path segment: a portable EDN
;; identity value usable as an associative key or vector index — a keyword,
;; string, symbol, integer, boolean, UUID, instant, or nil. Functions,
;; atoms, promises, DOM nodes, AbortControllers, opaque host objects, and
;; other host handles are NOT valid segments. A spec MAY narrow this domain
;; for its surface, but never widen it (Conventions §Segment domain).

(defn segment?
  "True iff `seg` is a concrete EDN identity value admissible as a path
  segment (Conventions §Segment domain) — the SHARED upper bound a
  consumer narrows from, never widens past. Composite values (vectors,
  maps, sets, seqs) and host handles are NOT valid concrete segments — a
  composite vector is only the template-parameter data form, which is not a
  concrete segment.

  This is the predicate counterpart of `normalize-concrete`'s fail-closed
  validation: `normalize-concrete` THROWS on a bad segment; `segment?`
  answers the same membership question without throwing, for a consumer
  that builds its own diagnostic (e.g. flows' `reg-flow` validator names
  the offending entries under `:bad-elements`). A subsystem
  that needs a NARROWER domain composes its own predicate ON TOP of this
  one (e.g. `(and (segment? x) (not (nil? x)))`), so the shared upper bound
  stays the single definition and no consumer re-enumerates the host-type
  discrimination.

  An INTEGER segment must be inside the CEDN-1 safe-integer
  range — the shared path vocabulary MUST NOT be wider than canonical EDN
  identity, or a consumer keying off `segment?` could admit an integer
  (`9007199254740992`) that cannot be portably compared, printed, routed,
  or digested under EP-0012. The range predicate is the SAME
  `re-frame.identity/safe-segment-integer?` the CEDN-1 encoder enforces, so
  `segment?` and `canonical-bytes` agree on \"portable integer\" rather than
  each re-defining it (an out-of-range integer is rejected by BOTH)."
  [seg]
  (or (nil? seg)
      (keyword? seg)
      (string? seg)
      (symbol? seg)
      (boolean? seg)
      (identity/safe-segment-integer? seg)
      #?(:clj  (or (instance? UUID seg)
                   (instance? Instant seg)
                   (instance? Date seg))
         :cljs (or (uuid? seg)
                   (instance? js/Date seg)))))

(defn normalize-concrete
  "Normalize a path to the canonical vector form AND validate that every
  segment is a concrete EDN identity value (Conventions §Segment domain).

  This is the VALIDATED concrete boundary (EP-0012 item 2):
  `normalize` coerces container shape only; `normalize-concrete` additionally
  fails closed with `:rf.error/bad-path` on any segment outside the concrete
  domain — an opaque host object, a function, a template-parameter segment
  (`[:rf.path/param …]`), or any other composite. Concrete boundaries such as
  frame classification and resource-scope inputs MUST route through this
  helper, never bare `normalize`, so a host/opaque segment is rejected at the
  declaration boundary rather than silently riding in a stored path."
  [p]
  (let [v (normalize p)]
    (reduce (fn [_ seg]
              (when-not (segment? seg)
                (bad-path! 'rf.path/normalize-concrete
                           (str "a concrete path segment must be a portable EDN "
                                "identity value (keyword, string, symbol, integer, "
                                "boolean, UUID, instant, or nil) — host objects and "
                                "composite/template segments are not valid concrete "
                                "segments")
                           {:bad-path v :bad-segment seg})))
            nil v)
    v))

;; ---- the unique missing sentinel -----------------------------------------
;;
;; A private, reference-unique sentinel distinguishes "missing" from any
;; possible present value (including `nil` itself). `get-in` cannot make
;; this distinction; `lookup` must, so the missing-vs-present-nil law
;; holds (Conventions §Missing versus present nil).

(def ^:private missing
  #?(:clj (Object.) :cljs (js-obj)))

;; ---- lookup / get --------------------------------------------------------

(defn lookup
  "Presence-aware lookup. Returns `{:present? true :value v}` when the
  focus exists (even when `v` is `nil`), else `{:present? false}`.

  The root path `[]` is always present with the whole value. This is the
  operation a subsystem uses when it must tell an absent key from a key
  present with value `nil` — `get` alone cannot."
  [value path]
  (let [path (normalize path)]
    (loop [v    value
           segs (seq path)]
      (if (nil? segs)
        {:present? true :value v}
        (let [seg (first segs)
              nxt (if (associative? v)
                    (core/get v seg missing)
                    missing)]
          (if (identical? nxt missing)
            {:present? false}
            (recur nxt (next segs))))))))

(defn get
  "Return the value focused by `path`, or `not-found` (default `nil`)
  when the focus is missing. `(get s [])` is the whole value `s`."
  ([value path] (get value path nil))
  ([value path not-found]
   (let [{:keys [present? value]} (lookup value path)]
     (if present? value not-found))))

;; ---- put / over ----------------------------------------------------------

(defn- container-for
  "Return a container for `value` that can hold an entry at `seg`,
  preserving `value` when it is already a usable container for that
  segment kind, else replacing it with a fresh map.

  Container/segment compatibility (the explicit intermediate-container
  policy — Conventions §Path laws):

    - a map can hold any segment;
    - a vector can hold ONLY an in-range non-negative integer index
      (`assoc` on a vector with a non-integer or out-of-range index
      throws on the JVM and is meaningless as a path step), so a vector
      faced with a non-integer / out-of-range segment is replaced by a
      fresh map;
    - any non-associative value (nil, a scalar, a set, a seq) faced with
      ANY segment is replaced by a fresh map (missing / mismatched
      intermediate values become maps, matching `assoc-in`).

  Replacing — rather than throwing — keeps `put` total so the put-lookup
  law holds for every generated path, while never silently corrupting a
  compatible existing container."
  [value seg]
  (cond
    (map? value) value
    (vector? value)
    (if (and (integer? seg) (<= 0 seg) (< seg (count value)))
      value
      {})
    :else {}))

(defn put
  "Return `value` with `x` installed at `path`. The root path replaces the
  whole value: `(put s [] x) = x` — the law raw `assoc-in` violates (it
  assoc's under key `nil`).

  Missing or segment-incompatible intermediate values are created as maps
  (see `container-for` for the explicit policy), matching `assoc-in`'s
  map-creation behaviour and defining vector-index behaviour explicitly
  rather than letting a host `assoc` throw. A present-`nil` write is
  stored as `nil` (NOT dissoc'd) so the put-lookup law holds at `x = nil`."
  [value path x]
  (let [path (normalize path)]
    (if (zero? (count path))
      x
      (let [seg  (nth path 0)
            base (container-for value seg)]
        (assoc base seg
               (put (let [{:keys [present? value]} (lookup base [seg])]
                      (if present? value nil))
                    (subvec path 1)
                    x))))))

(defn over
  "Return `value` with `f` applied to the current focus. `(over s [] f) =
  (f s)`. On a missing path `f` is called with `nil` (matching
  `update-in`); a consumer needing the missing/present-nil distinction
  uses `lookup` first.

  Law: `over(s, p, f) = put(s, p, f(get(s, p)))` for the public
  nil-on-missing `get` semantics."
  [value path f]
  (put value path (f (get value path))))

;; ---- compose / prefix? / overlap? ----------------------------------------

(defn compose
  "Append two paths. `(compose p [])` = `p`, `(compose [] p)` = `p`, and
  compose is associative. The result is the canonical vector form."
  [p q]
  (into (normalize p) (normalize q)))

(defn prefix?
  "True iff `p` is a path-prefix of `q` (a prefix of itself included).
  `[]` is a prefix of every path. Implementation note: `subvec` is O(1)
  and allocates only a thin view — `prefix?` is hot in flow topo-sort."
  [p q]
  (let [p (normalize p)
        q (normalize q)
        n (count p)]
    (and (<= n (count q))
         (= p (subvec q 0 n)))))

(defn overlap?
  "True iff `p` and `q` may affect the same focused value — i.e. either is
  a prefix of the other. Symmetric. `(overlap? [] p)` is always true (the
  root path overlaps everything). This is the shared relation flows use
  for dependency edges and output-collision detection; no subsystem keeps
  a private overlap predicate."
  [p q]
  (or (prefix? p q)
      (prefix? q p)))

;; ---- path templates (EP-0012 disposition 2) ------------------------------
;;
;; The canonical STORED shape of a template variable is the explicit data
;; form `[:rf.path/param :name]`. The `'?name` quote-symbol spelling is
;; declaration-boundary SUGAR, normalized into the data form by
;; `normalize-template`. The data form is what CEDN-1 encodes and what
;; traces / Xray display — `'?name` never appears in any stored or
;; serialized shape (one fact, one identity; disposition 2 rider). A
;; concrete runtime path that literally contains the symbol `?name` is
;; just a symbol segment and is NOT substituted unless it is processed as
;; a template declaration.

(defn param-segment?
  "True iff `seg` is a canonical template-parameter segment, the data form
  `[:rf.path/param :name]` (a 2-vector headed by `:rf.path/param` with a
  keyword name). This is the ONLY template-variable shape that appears in
  a stored or serialized path."
  [seg]
  (and (vector? seg)
       (= 2 (count seg))
       (= :rf.path/param (nth seg 0))
       (keyword? (nth seg 1))))

(defn- sugar-param-name
  "When `seg` is the declaration sugar `'?name` (a simple symbol whose
  name begins with `?`), return the parameter name as a keyword
  (`'?invoice-id` -> `:invoice-id`); else nil. The marker is recognised
  only on a symbol with no namespace whose name is longer than the bare
  `?` and starts with `?`."
  [seg]
  (when (and (symbol? seg)
             (nil? (namespace seg)))
    (let [s (name seg)]
      (when (and (> (count s) 1) (= \? (core/get s 0)))
        (keyword (subs s 1))))))

(defn normalize-template
  "Normalize a path-template vector to its canonical stored shape: every
  `'?name` sugar segment becomes the data form `[:rf.path/param :name]`;
  already-canonical `[:rf.path/param :name]` segments pass through; every
  other segment is a literal and passes through untouched. Returns a
  vector. Idempotent — re-normalizing a canonical template is a no-op
  (the rider guarantee that `'?name` never survives into storage)."
  [path]
  (mapv (fn [seg]
          (cond
            (param-segment? seg) seg
            (sugar-param-name seg) [:rf.path/param (sugar-param-name seg)]
            :else seg))
        (normalize path)))

(defn template?
  "True iff `path` (after template normalization) contains at least one
  `[:rf.path/param …]` segment — i.e. it is a template, not a concrete
  path."
  [path]
  (boolean (some param-segment? (normalize-template path))))

(defn instantiate
  "Instantiate a path template into a concrete path by substituting each
  `[:rf.path/param :name]` segment with `(core/get bindings :name)`. Accepts
  either a raw path vector / template or a named-path-declaration map
  carrying an `:rf/path`. `'?name` sugar in the template is normalized
  first. Pure. Throws `:rf.error/missing-path-param` when a parameter has
  no binding (fail-closed — an unbound param would silently produce a
  `nil` segment).

      (instantiate [:billing :invoices :by-id '?invoice-id :email]
                   {:invoice-id #uuid \"…\"})
      ;; => [:billing :invoices :by-id #uuid \"…\" :email]

  `instantiate` is a CONCRETE-path PRODUCER: its result is meant to be fed
  to `get` / `put` / `over` as a runtime path, so it routes the substituted
  result through `normalize-concrete` — the SAME validated boundary frame
  classification and resource scope use. A binding whose VALUE
  is outside the concrete-segment domain — a function, host object, or a
  composite (vector / map / set), including a literal `[:rf.path/param …]`
  data form — FAILS CLOSED with `:rf.error/bad-path` rather than silently
  smuggling a non-portable segment into a path presented as concrete.
  A present-`nil` binding is a legal concrete segment and
  passes (the missing-binding case is the fail-closed
  `:rf.error/missing-path-param` above)."
  [path-or-decl bindings]
  (let [path (normalize-template
               (if (map? path-or-decl)
                 (:rf/path path-or-decl)
                 path-or-decl))]
    (normalize-concrete
      (mapv (fn [seg]
              (if (param-segment? seg)
                (let [param (nth seg 1)]
                  (if (contains? bindings param)
                    (core/get bindings param)
                    (error/throw-error!
                      :rf.error/missing-path-param
                      'rf.path/instantiate
                      (str "the :rf/path template references parameter " (pr-str param)
                           " but no binding was supplied for it; provide a binding for "
                           "every [:rf.path/param …] segment when instantiating the path.")
                      {:recovery :supply-binding
                       :extra    {:param    param
                                  :bad-path path}})))
                seg))
            path))))
