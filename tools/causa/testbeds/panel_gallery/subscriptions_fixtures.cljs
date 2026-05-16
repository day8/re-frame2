(ns panel-gallery.subscriptions-fixtures
  "Pure fixture builders for the Causa subscriptions panel gallery
  (rf2-5nvk2, Phase 1b).

  The subscriptions panel reads its rows from
  `:rf.causa/subscriptions-data`, a composite over:

    - `:rf.causa/sub-cache`        — `{query-v cache-entry}` (live or
                                     override via `:sub-cache-override`
                                     on Causa's app-db)
    - `:rf.causa/sub-error-cache`  — `{query-v error}` (live errors)
    - `:rf.causa/selected-sub`     — focused query-v
    - `:rf.causa/sub-filters`      — set of status keywords to keep
    - `:rf.causa/sub-chain-open?`  — boolean for the chain side-panel

  Each variant seeds via `:rf.causa/set-sub-cache-override-for-test`
  (the canonical override event registered in
  `subscriptions-events/install!`). The override coexists with Story's
  `:rf.story/*` runtime slots because the handler `assoc`s rather than
  replacing the whole db.

  ## cache-entry shape

  Per `subscriptions-projection/project-rows` the panel reads five
  keys off each cache entry:

      {:query-v     [<sub-id> <args>...]   ; optional; falls back to map key
       :layer       <int>                  ; layer depth — colours the dot
       :ref-count   <int>
       :input-subs  [[<input-q-v>] ...]    ; chain edges
       :rerunning?  <bool>
       :invalidated? <bool>}

  ## status taxonomy

  Per `subscriptions-status/statuses`:

      :error :re-running :invalidated :fresh :cached-no-watcher

  The panel renders one badge / chip per status; tests pin the count.")

;; ---- entry builders ---------------------------------------------------

(defn- entry
  "Build a single cache entry. `:layer 1` and `:ref-count 1` default
  so the row renders as `:fresh` unless overridden."
  ([] (entry {}))
  ([{:keys [layer ref-count input-subs rerunning? invalidated? value]
     :or   {layer      1
            ref-count  1
            input-subs []}}]
   (cond-> {:layer      layer
            :ref-count  ref-count
            :input-subs input-subs}
     (some? value)       (assoc :value value)
     rerunning?          (assoc :rerunning? true)
     invalidated?        (assoc :invalidated? true))))

;; ---- 1. empty cache --------------------------------------------------

(defn empty-cache
  "No subs in the cache — panel renders the empty-state copy."
  []
  {})

;; ---- 2. small fanout (5 subs) ----------------------------------------

(defn small-cache
  "Five subs across two layers; demonstrates the typical small-app
  fanout (auth + nav + a couple of feature subs)."
  []
  {[:auth/user]         (entry {:layer 1 :ref-count 3})
   [:route/active]      (entry {:layer 1 :ref-count 1})
   [:counter/value]     (entry {:layer 1 :ref-count 1})
   [:cart/items-count]  (entry {:layer 2 :ref-count 1
                                :input-subs [[:auth/user]]})
   [:ui/theme]          (entry {:layer 1 :ref-count 2})})

;; ---- 3. medium fanout (50 subs) --------------------------------------

(defn medium-cache
  "Fifty subs across three layers — typical mid-sized app shape.
  Demonstrates the rows component under normal load."
  []
  (let [base-subs (for [i (range 50)]
                    [(keyword (str "feature-" (mod i 10))
                              (str "row-" i))
                     (entry {:layer    (inc (mod i 3))
                             :ref-count (inc (mod i 4))})])]
    (into {} base-subs)))

;; ---- 4. large fanout (200 subs) --------------------------------------

(defn large-cache
  "Two hundred subs — exercises the overflow / virtualisation behaviour
  of the rows list under storm load."
  []
  (let [bulk (for [i (range 200)]
               [(keyword (str "perf-tier-" (mod i 8))
                         (str "key-" i))
                (entry {:layer    (inc (mod i 4))
                        :ref-count (inc (mod i 5))})])]
    (into {} bulk)))

;; ---- 5. derived-chain (sub depending on 3 inputs) --------------------

(defn derived-chain-cache
  "Six subs forming a small derivation tree:

      :cart/total (L3) ─┬─ :cart/subtotal (L2) ─ :cart/items (L1)
                       ├─ :cart/discount (L2) ─ :promo/active (L1)
                       └─ :cart/tax (L2) ─── :tax/rate (L1)

  Three input-subs on `:cart/total` — the chain view's three-input
  fanout is the axis."
  []
  {[:cart/items]    (entry {:layer 1 :ref-count 2})
   [:promo/active]  (entry {:layer 1 :ref-count 1})
   [:tax/rate]      (entry {:layer 1 :ref-count 1})
   [:cart/subtotal] (entry {:layer 2 :ref-count 1
                            :input-subs [[:cart/items]]})
   [:cart/discount] (entry {:layer 2 :ref-count 1
                            :input-subs [[:promo/active]]})
   [:cart/tax]      (entry {:layer 2 :ref-count 1
                            :input-subs [[:tax/rate]]})
   [:cart/total]    (entry {:layer 3 :ref-count 1
                            :input-subs [[:cart/subtotal]
                                         [:cart/discount]
                                         [:cart/tax]]})})

;; ---- 6. redacted sub payload -----------------------------------------

(defn redacted-cache
  "One sub whose computed value carries the `:rf/redacted` marker —
  the cache entry surfaces the marker per Spec 009 §Privacy."
  []
  {[:auth/credentials] (entry {:layer 2 :ref-count 1
                               :input-subs [[:auth/user]]
                               :value {:user-id 7
                                       :password :rf/redacted
                                       :totp     :rf/redacted}})
   [:auth/user]        (entry {:layer 1 :ref-count 1})})

;; ---- 7. re-running (in-flight) ---------------------------------------

(defn rerunning-cache
  "Three subs in `:re-running` status — exercises the in-flight badge
  rendering. The remaining two are `:fresh` for contrast."
  []
  {[:profile/data]   (entry {:layer 2 :ref-count 1
                             :rerunning? true
                             :input-subs [[:auth/user]]})
   [:perms/effective] (entry {:layer 2 :ref-count 1
                              :rerunning? true
                              :input-subs [[:auth/user]]})
   [:audit/recent]   (entry {:layer 1 :ref-count 1
                             :rerunning? true})
   [:auth/user]      (entry {:layer 1 :ref-count 3})
   [:ui/theme]       (entry {:layer 1 :ref-count 2})})

;; ---- 8. errored ------------------------------------------------------

(def errored-cache-entries
  "Cache + error-cache for the errored variant. The status projection
  marks an entry `:error` when its query-v has an error-cache hit; the
  variant fires two events to seed both shapes."
  {:cache  {[:profile/data]   (entry {:layer 2 :ref-count 1})
            [:perms/effective] (entry {:layer 2 :ref-count 1})
            [:auth/user]      (entry {:layer 1 :ref-count 3})}
   :errors {[:profile/data] {:type :handler-throw
                             :message "Sub computation threw: nil-pointer
                                      at perm-set/decorate"}
            [:perms/effective] {:type :input-missing
                                :message "Required input
                                         :perms/active-bundle absent"}}})

;; ---- 9. invalidated mix ----------------------------------------------

(defn invalidated-mix-cache
  "Five subs spanning every status the panel renders: `:fresh`,
  `:re-running`, `:invalidated`, `:cached-no-watcher`, plus an
  `:error`-driver pair seeded via the error-cache variant. Pins all
  the badge variants visible in one card."
  []
  {[:s/fresh-1]        (entry {:layer 1 :ref-count 1})
   [:s/rerunning-1]    (entry {:layer 1 :ref-count 1 :rerunning? true})
   [:s/invalidated-1]  (entry {:layer 1 :ref-count 1 :invalidated? true})
   [:s/no-watcher-1]   (entry {:layer 1 :ref-count 0})
   [:s/fresh-2]        (entry {:layer 2 :ref-count 1
                               :input-subs [[:s/fresh-1]]})})

;; ---- 10. high-layer chain (panel-specific axis) ----------------------

(defn deep-chain-cache
  "Five-layer derivation chain — exercises the panel's layer-dot
  ladder (l1 / l2 / l3 / l4 / l5) in a single card. Panel-specific
  axis: the layer dot is unique to the subscriptions panel."
  []
  {[:l1/source]      (entry {:layer 1 :ref-count 1})
   [:l2/derived]     (entry {:layer 2 :ref-count 1
                             :input-subs [[:l1/source]]})
   [:l3/composite]   (entry {:layer 3 :ref-count 1
                             :input-subs [[:l2/derived]]})
   [:l4/projection]  (entry {:layer 4 :ref-count 1
                             :input-subs [[:l3/composite]]})
   [:l5/view-model]  (entry {:layer 5 :ref-count 1
                             :input-subs [[:l4/projection]]})})
