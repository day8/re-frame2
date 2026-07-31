(ns re-frame.bench.hicasso.arm2.reconcile
  "KEYED RECONCILIATION AS PURE DATA — the floor tier of the PATCH
  differ (rf2-2rtt6.10, architecture.md Arm 2).

  architecture.md gives Arm 2 three differ tiers under one grammar:
  template extraction with data-encoded hole plans as the static fast
  path, a value-equality cutoff as the fallback, and **a full keyed diff
  as the floor**. This namespace is that floor's decision half, and it is
  deliberately separated from the DOM half.

  ## Why the plan is data

  A keyed reconciler is where renderers get their reputation for being
  untestable: the algorithm and the mutation are usually the same loop,
  so the only way to ask *which* nodes moved is to mount a page and
  compare pointers. Here the algorithm answers that question on its own,
  as a value:

      (plan [:a :b :c] [:c :a :b])
      ;; => {:removes [] :places [ … ]}

  so `no-node-recreated-on-reorder` — one of the witness set's assertions
  for `:keyed/insert-delete-reorder` — is a claim about a returned map
  that a node runtime can check, not a DOM forensics exercise. The DOM
  applier ([[re-frame.bench.hicasso.arm2.patch]]) contains no reordering
  decisions at all; it walks this plan.

  ## The plan's shape, and why `:places` runs backwards

      {:removes [old-index …]                    ;; apply first
       :places  [{:op :keep|:move|:mount, :new j, :old i-or-nil} …]}

  `:places` is emitted in **descending `:new` order** because that is the
  order in which an `insertBefore` applier needs it. Walking the new
  children from the end, the node placed at `j+1` is the anchor for `j`,
  so the applier carries exactly one variable — the last node it touched —
  and never computes a live child index. Index arithmetic against a
  parent that is being mutated is the classic source of off-by-one
  reorder bugs; this plan removes the arithmetic rather than getting it
  right.

  `:keep` means *already in the right relative position* — the applier
  patches it in place and moves no node. `:move` means the same node,
  relocated with one `insertBefore`. `:mount` is the only op that builds.

  ## Minimal moves, by construction

  The stable set is a **longest increasing subsequence** of the reused old
  indices (the algorithm Inferno and Vue 3 both settled on). That is what
  makes a rotation of 100 rows cost one move rather than 99, and it is
  why `:keep` is a distinct op rather than a `:move` that happens to be a
  no-op: a reader can count the moves in the plan.

  ## Unkeyed children

  With no keys on either side the plan is positional — pair by index,
  mount the surplus, remove the deficit. This is not a fallback that
  loses correctness: an unkeyed list has told the renderer that position
  *is* the identity. HD-006's no-default-memoization posture applies here
  too; nothing guesses at identity from content."
  (:refer-clojure :exclude [key]))

;; ---------------------------------------------------------------------------
;; Longest increasing subsequence
;; ---------------------------------------------------------------------------

(defn lis-positions
  "The set of **positions in `xs`** forming one longest strictly
  increasing subsequence of `xs`, where a `nil` entry is skipped (it is a
  position with no old node — a mount — and cannot be part of a stable
  run).

  Patience sorting with a predecessor chain: O(n log n), one binary
  search per entry. Public because it is the part of the algorithm worth
  testing on its own."
  [xs]
  (let [n     (count xs)
        prev  (js/Array. n)
        tails #js []]
    (dotimes [i n]
      (when-some [v (nth xs i)]
        (let [lo (loop [lo 0 hi (alength tails)]
                   (if (< lo hi)
                     (let [mid (bit-shift-right (+ lo hi) 1)]
                       (if (< (nth xs (aget tails mid)) v)
                         (recur (inc mid) hi)
                         (recur lo mid)))
                     lo))]
          (aset prev i (when (pos? lo) (aget tails (dec lo))))
          (if (< lo (alength tails))
            (aset tails lo i)
            (.push tails i)))))
    (loop [acc #{}
           i   (when (pos? (alength tails)) (aget tails (dec (alength tails))))]
      (if (nil? i)
        acc
        (recur (conj acc i) (aget prev i))))))

;; ---------------------------------------------------------------------------
;; The plan
;; ---------------------------------------------------------------------------

(defn- positional-plan
  "Pair by index. The unkeyed contract: position is identity."
  [old-n new-n]
  {:removes (vec (range new-n old-n))
   :places  (into []
                  (map (fn [j] (if (< j old-n)
                                 {:op :keep :new j :old j}
                                 {:op :mount :new j :old nil})))
                  (reverse (range new-n)))})

(defn- index-by-key
  "key → first old position carrying it. First wins on a duplicate, which
  makes a duplicated key degrade to *one* reused node and one fresh
  mount rather than to two children fighting over one node."
  [old-keys]
  (persistent!
   (reduce (fn [m i]
             (let [k (nth old-keys i)]
               (if (or (nil? k) (contains? m k)) m (assoc! m k i))))
           (transient {})
           (range (count old-keys)))))

(defn plan
  "Reconcile `old-keys` against `new-keys` — two vectors of child keys,
  `nil` at an unkeyed position — into the plan described in the namespace
  docstring.

  When neither side carries a single key the plan is positional. As soon
  as one key appears anywhere the keyed path runs: a `nil`-keyed child in
  a keyed list matches nothing and mounts, which is the same rule React
  and Reagent apply and the reason a partially-keyed list is a mistake
  worth making visible in the plan."
  [old-keys new-keys]
  (let [old-n (count old-keys)
        new-n (count new-keys)]
    (if (and (not-any? some? old-keys) (not-any? some? new-keys))
      (positional-plan old-n new-n)
      (let [by-key   (index-by-key old-keys)
            ;; reused[j] = the old position new child j reuses, or nil.
            ;; An old position is consumed at most ONCE: a key duplicated on
            ;; the new side would otherwise have two children claiming one
            ;; node, which is the same fight `index-by-key` settles on the
            ;; old side.
            reused   (loop [j 0 acc (transient []) used #{}]
                       (if (>= j new-n)
                         (persistent! acc)
                         (let [k (nth new-keys j)
                               i (when (some? k) (get by-key k))
                               i (when (and (some? i) (not (used i))) i)]
                           (recur (inc j) (conj! acc i) (if i (conj used i) used)))))
            taken    (into #{} (keep identity) reused)
            removes  (into [] (remove taken) (range old-n))
            stable   (lis-positions reused)
            places   (into []
                           (map (fn [j]
                                  (let [i (nth reused j)]
                                    (cond
                                      (nil? i)        {:op :mount :new j :old nil}
                                      (stable j)      {:op :keep  :new j :old i}
                                      :else           {:op :move  :new j :old i}))))
                           (reverse (range new-n)))]
        {:removes removes :places places}))))

;; ---------------------------------------------------------------------------
;; Reading a plan — for tests, and for the witness assertions
;; ---------------------------------------------------------------------------

(defn move-count
  "How many nodes this plan relocates. The number
  `:no-node-recreated-on-reorder` is really about: a reorder that mounts
  nothing and moves few."
  [{:keys [places]}]
  (count (filter #(= :move (:op %)) places)))

(defn mount-count
  "How many nodes this plan builds."
  [{:keys [places]}]
  (count (filter #(= :mount (:op %)) places)))

(defn reuse-count
  "How many nodes this plan carries over — kept or moved."
  [{:keys [places]}]
  (count (remove #(= :mount (:op %)) places)))
