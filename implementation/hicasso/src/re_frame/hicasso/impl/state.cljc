(ns re-frame.hicasso.impl.state
  "`h/reg-state` — THE INSTANCE-KEY SUGAR (rf2-2rtt6.98, HD-009 as amended
  by the 2026-08-04 explicit-key ruling).

  A widget that wants a scrap of its own state — a disclosure's open flag,
  a tab strip's selection, a row's draft text — has to answer one question
  first: *which* instance's flag is this? The guide's own answer, before
  this ruling, was a pitfall written out in prose: pick an app-db path by
  hand, forget to vary it per instance, and **every instance on the page
  silently shares one value**. Two accordions open together. Nothing
  throws, nothing logs, and the screen is simply wrong.

  This namespace converts that silence into a loud error, and it does so
  with **no new machinery at all**.

  ## What it mints — ordinary core artefacts, and nothing else

  [[reg-state]] is a plain function. It calls `re-frame.subs/reg-sub` and
  `re-frame.events/reg-event`, which is what any application author would
  have called by hand, and then it stops. There is:

  - **no new runtime state** — no cell, no registry, no per-instance
    record; the value lives in `app-db` where every other value lives;
  - **no hook** — nothing here runs inside a React render, so the ≤2-hook
    shell budget (HD-020(b), counted at React's own dispatcher by
    `arm1/hook-ledger-dom-cljs-test`) is untouched by construction;
  - **no context** — a widget reads its state through the ordinary
    subscription path and therefore through whatever frame its boundary
    already resolved.

  That is the whole architectural claim, and it is why this file is 200
  lines rather than a subsystem: the sugar is a NAMING CONVENTION with
  refusals, not a feature.

  ## The tier — ONE app-space conventional root, concern-first

  The path is `[:ui ::concern ikey]`.

  - `:ui` is **app-space**, deliberately. It is NEVER an `:rf/*` app-db
    root: `spec/Conventions.md`'s two-partition doctrine reserves the
    `:rf/*` single-root scheme for the framework, and widget state is the
    application's own data, readable and writable by ordinary handlers,
    dumpable in a tool, serialisable in a fixture.
  - **Concern-first**, then instance. Grouping by concern is what makes
    `[:ui ::open?]` one inspectable map of every open disclosure rather
    than a scatter of unrelated leaves.
  - **Per-frame comes free**, with nothing spent on it: `app-db` is
    per-frame already, so two frames holding the same concern at the same
    instance key hold two independent values without this namespace
    containing a single line about frames.

  ## The four rules an author is TAUGHT but this file does not police

  Policing them would need to know what a domain is, and it does not.

  1. **Domain ids first.** If the widget is showing a to-do, key it by the
     to-do's id; invent an identity only when the domain has none.
  2. **Entity-qualified id VALUES** — `{:id [:order/id 42]}` — when one
     widget serves multiple entity types, because a bare `42` from two
     entity types is one key and the bleed is silent.
  3. **Placement-like concerns key by placement; value-like concerns key
     by entity.** \"Is this panel open?\" belongs to where the panel is;
     \"what is the draft text for this order?\" belongs to the order.
  4. **If it would be a good React `:key`, it is a good instance key** —
     which is the determinism rule: the key must be derived from data, not
     from render order, a counter or `random-uuid`, or a server render and
     its client hydration will not agree on it.

  And the graduation rule: when an occurrence means more than \"this slot
  now holds that value\" — when something else must happen, or the change
  must be recorded — retire the concern and write a named domain event.
  This sugar is for the assignments that mean nothing but themselves.

  ## What was REJECTED, and stays rejected

  Clear is a FRAMEWORK-NAMED EVENT, `[::h/clear ::concern ikey]`, and not
  a sentinel VALUE. The rejected variant put `::h/clear` in the value
  position of the ordinary setter — which reads beautifully right up to
  the first concern whose values are keywords, at which point setting the
  concern to a legitimate value would silently `dissoc` it instead. That
  is a fail-silent hazard of exactly the class this whole ruling exists to
  delete, so there is no sentinel here and no \"convenience\" arity that
  accepts one."
  (:require [re-frame.events :as events]
            [re-frame.hicasso.impl.error :refer [fail!]]
            [re-frame.subs :as subs]))

;; ---------------------------------------------------------------------------
;; The spellings
;; ---------------------------------------------------------------------------

(def ui-root
  "`:ui` — the ONE app-space conventional root every concern sits under.
  App-space, never `:rf/*`: widget state is the application's data."
  :ui)

(def clear-event-id
  "`::h/clear` — the FRAMEWORK-NAMED clear event, `[::h/clear ::concern
  ikey]`. One handler serves every concern, because removing an entry
  needs to know nothing about what used to be in it."
  :re-frame.hicasso/clear)

;; ---------------------------------------------------------------------------
;; Errors — the lane's shape (front.presence/fail!)
;; ---------------------------------------------------------------------------

;; `fail!` is `re-frame.hicasso.impl.error`'s — one constructor for the whole
;; package, and the ambient view and source coordinate come with it
;; (rf2-hic-007). The eight lines that stood here were one of six identical
;; copies.

;; ---------------------------------------------------------------------------
;; Instance keys
;; ---------------------------------------------------------------------------

(defn instance-key?
  "A keyword, a string, a number, or a vector of those (recursively).

  The list is short on purpose. It is every shape that reads as an
  identity, prints legibly in a tool, survives EDN, and — the reason the
  vector case is in it — can be COMPOSED by [[child-key]] without
  becoming a new kind of thing. `nil` is not on it, and that exclusion is
  the whole point: `nil` is what a missing prop, a mistyped destructure
  and a forgotten argument all evaluate to, and `nil` as a key is
  precisely the every-instance-shares-one-value pitfall."
  [k]
  (boolean (or (keyword? k)
               (string? k)
               (number? k)
               (and (vector? k) (every? instance-key? k)))))

(defn- check-key!
  "Refuse a bad instance key, **naming the concern**, at read and at write
  alike. A read-only check would leave a bad key written and only fail on
  the way back out, one interaction later and in a different component."
  [concern ikey op]
  (when-not (instance-key? ikey)
    (fail! :rf.error/hicasso-state-bad-key
           're-frame.hicasso.impl.state/check-key!
           (str "The instance key for " (pr-str concern) " was "
                (pr-str ikey) ", which is not a keyword, string, number, "
                "or vector of those. Without a per-instance key every "
                "instance of this widget shares one value, which is a "
                "wrong screen that never complains — so it is refused "
                "here instead.")
           :key-the-widget-by-a-domain-id
           {:concern concern :instance-key ikey :op op}))
  ikey)

(defn child-key
  "Extend an instance key by one `part` — the ONE composition helper, and
  the whole answer to widget-in-widget nesting.

      (child-key :panel :row)          ;=> [:panel :row]
      (child-key [:panel :row] :cell)  ;=> [:panel :row :cell]

  Pure, total and associative-shaped: a parent hands its own key down, a
  child extends it, and the resulting keys are distinct by construction
  without any component knowing how deeply it is nested. It does not
  validate — a bad key is refused at the read or the write, which is
  where the concern is known and can be named."
  [k part]
  (if (vector? k) (conj k part) [k part]))

;; ---------------------------------------------------------------------------
;; The clear event
;; ---------------------------------------------------------------------------

(defn- clear-entry
  "Remove `[ui-root concern ikey]`, pruning an emptied concern map.

  Removal, not a write of the default: the default lives in the sub, so a
  cleared concern reads its default because the entry is ABSENT — one
  representation of \"unset\", not two."
  [db concern ikey]
  (if-some [concerns (get db ui-root)]
    (let [entries (dissoc (get concerns concern) ikey)]
      (assoc db ui-root (if (seq entries)
                          (assoc concerns concern entries)
                          (dissoc concerns concern))))
    db))

(defn- reg-clear-event!
  "Register `[::h/clear ::concern ikey]`. Idempotent — one handler serves
  every concern, and re-registration replaces it with an identical one."
  []
  (events/reg-event clear-event-id
    (fn clear-handler [{:keys [db]} [_ concern ikey]]
      (check-key! concern ikey :clear)
      {:db (clear-entry db concern ikey)})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

;; `concern -> the :default it was registered with`.
;;
;; Registration-time bookkeeping, NOT runtime state: nothing reads it
;; during a render, it holds no per-instance value, and it is the same kind
;; of thing the sub and event registries themselves are. It exists so a
;; second `reg-state` for one concern with a DIFFERENT default can be
;; refused rather than silently changing what every un-set instance reads.
;;
;; `defonce`, so a hot reload of THIS namespace does not forget what is
;; already registered — which is the stricter reading, and the cost is
;; stated in `reg-state`. (`defonce` takes no docstring in CLJS, which is
;; why this one is a comment.)
(defonce ^:private !defaults (atom {}))

(defn reg-state
  "Register a per-instance state `concern`, and mint the two ordinary core
  artefacts a widget needs to use it.

      (reg-state ::open? {:default false})

      (sub [::open? panel-id])                ;; read
      (dispatch [::open? panel-id true])      ;; write
      (dispatch [::h/clear ::open? panel-id]) ;; back to the default

  `concern` must be a NAMESPACE-QUALIFIED keyword — it is simultaneously a
  sub id, an event id and an `app-db` key, and an unqualified one would
  collide across features on all three at once. `opts` may carry
  `:default` and nothing else; an unknown option is refused rather than
  ignored, because an ignored option is a setting the author believes is
  in force.

  ## What it registers

  - a **sub** under `concern`, reading `[:ui concern ikey]` and falling
    back to `:default` when the entry is absent;
  - a **setter event** under `concern`, `[::concern ikey v]`, writing that
    same path;
  - the shared **clear event** [[clear-event-id]], if it is not already
    registered by another concern.

  Both are registered through the ordinary programmatic doors
  (`re-frame.subs/reg-sub`, `re-frame.events/reg-event`), so everything
  downstream — caching, the frame's app-db, tooling, a test's
  `dispatch-sync` — behaves exactly as it does for a hand-written pair.

  ## Registering twice

  Re-registering a concern with the SAME `:default` is a no-op-shaped
  refresh, which is what a namespace reload is. Re-registering it with a
  DIFFERENT `:default` **refuses**: the un-set instances of a live widget
  would otherwise start reading a different value with nothing on screen
  to say why. That is the stricter of the two behaviours the ruling
  allowed, and its cost is honest — changing a `:default` in a
  hot-reloading dev session needs a page reload.

  Returns `concern`.

  See the namespace docstring for the tier (`[:ui ::concern ikey]`,
  app-space and never `:rf/*`), the determinism rule (\"if it would be a
  good React `:key`, it is a good instance key\"), and the rules an author
  is taught but this function does not police."
  ([concern] (reg-state concern nil))
  ([concern opts]
   (when-not (and (keyword? concern) (namespace concern))
     (fail! :rf.error/hicasso-state-bad-concern
            're-frame.hicasso.impl.state/reg-state
            (str "A state concern must be a namespace-qualified keyword; it "
                 "was " (pr-str concern) ". The concern is a sub id, an event "
                 "id and an app-db key at once, so an unqualified one collides "
                 "with every other feature on the page in three registries.")
            :namespace-qualify-the-concern
            {:concern concern}))
   (when-not (or (nil? opts) (map? opts))
     (fail! :rf.error/hicasso-state-bad-option
            're-frame.hicasso.impl.state/reg-state
            (str "reg-state options must be a map; for " (pr-str concern)
                 " they were " (pr-str opts) ".")
            :pass-a-map-of-options
            {:concern concern :options opts}))
   (let [unknown (seq (disj (set (keys opts)) :default))]
     (when unknown
       (fail! :rf.error/hicasso-state-bad-option
              're-frame.hicasso.impl.state/reg-state
              (str "reg-state accepts :default and nothing else; "
                   (pr-str concern) " was given " (pr-str (vec (sort-by str unknown)))
                   ". An option that is quietly ignored is a setting its author "
                   "believes is in force.")
              :remove-the-unknown-option
              {:concern concern :unknown (vec (sort-by str unknown))})))
   (let [default (:default opts)]
     (when (and (contains? @!defaults concern)
                (not= default (get @!defaults concern)))
       (fail! :rf.error/hicasso-state-redefined
              're-frame.hicasso.impl.state/reg-state
              (str "The concern " (pr-str concern) " is already registered with "
                   ":default " (pr-str (get @!defaults concern)) " and cannot be "
                   "re-registered with " (pr-str default) ". Every instance that "
                   "has not been written to would start reading a different value "
                   "with nothing on screen to say why.")
              :register-each-concern-once
              {:concern concern
               :registered-default (get @!defaults concern)
               :offered-default default}))
     (subs/reg-sub concern
       (fn read-state [db query-v]
         (let [ikey (check-key! concern (nth query-v 1 nil) :read)]
           (get-in db [ui-root concern ikey] default))))
     (events/reg-event concern
       (fn write-state [{:keys [db]} [_ ikey v]]
         (check-key! concern ikey :write)
         {:db (assoc-in db [ui-root concern ikey] v)}))
     (reg-clear-event!)
     (swap! !defaults assoc concern default)
     concern)))
