(ns re-frame.core-state
  "`rf/reg-state` — per-instance view state, for every substrate.

  A widget that holds a scrap of its own state (a disclosure's open flag, a
  tab strip's selection, a row's draft text) must say WHICH instance the
  scrap belongs to. Picked by hand, an app-db path that forgets to vary per
  instance makes every instance on the page share one value, and nothing
  complains. `reg-state` makes the instance key part of every read, write
  and clear, and refuses a key that cannot tell instances apart.

  It is a naming convention with refusals, not machinery: it registers an
  ordinary sub and an ordinary event through `re-frame.subs/reg-sub` and
  `re-frame.events/reg-event`, and the value lives in app-db at
  `[:ui concern instance-key]`. So it runs on both hosts, under any view
  substrate, inside whatever frame the caller resolved, and needs no hook.

  Clear is an EVENT, `[:rf.state/clear concern ikey]`, never a sentinel
  value: a sentinel in the setter's value slot would silently `dissoc` any
  concern whose legitimate values include it.

  Contract: Spec 002 §Per-instance view state."
  (:require [re-frame.error :as rf.error]
            [re-frame.events :as rf.events]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.subs :as rf.subs]))

#?(:clj (set! *warn-on-reflection* true))

(def ui-root
  "`:ui` — the app-space root every concern sits under. Never `:rf/*`,
  because widget state is the application's own data."
  :ui)

(def clear-event-id
  "`:rf.state/clear` — the one clear event every concern shares, because
  removing an entry needs to know nothing about what it held."
  :rf.state/clear)

(defn- refuse!
  "Throw `:rf.error/state-bad-argument` (Spec 009 §Error event catalogue)."
  [where reason extra]
  (rf.error/throw-error! :rf.error/state-bad-argument where reason
                         {:extra extra}))

(defn instance-key?
  "True for a keyword, string, number, UUID, or a vector of those
  (recursively). `nil` is excluded on purpose: it is what a missing prop or
  a forgotten argument evaluates to, and as a key it is exactly the
  every-instance-shares-one-value pitfall."
  [k]
  (boolean (or (keyword? k)
               (string? k)
               (number? k)
               (uuid? k)
               (and (vector? k) (every? instance-key? k)))))

(defn- check-key!
  "Refuse a bad instance key, naming the concern. Checked at read, write and
  clear alike, because a write-side gap would store a bad key that only
  fails one interaction later, in a different component."
  [concern ikey op]
  (when-not (instance-key? ikey)
    (refuse! (if (= op :clear) 'rf.state/clear 'rf/reg-state)
             (str "The instance key for " (pr-str concern) " was "
                  (pr-str ikey) ", which is not a keyword, string, number, "
                  "UUID, or vector of those. Without a per-instance key every "
                  "instance of this widget shares one value, so it is refused.")
             {:concern concern :instance-key ikey :op op}))
  ikey)

(defn child-key
  "Extend instance key `k` by one `part`, so a widget nested in a widget
  gets a key distinct from its parent's without knowing how deep it is.

      (child-key :panel :row)          ;=> [:panel :row]
      (child-key [:panel :row] :cell)  ;=> [:panel :row :cell]

  Pure; it does not validate, because the read or write that uses the key
  refuses a bad one and can name the concern."
  [k part]
  (if (vector? k) (conj k part) [k part]))

(defn- clear-entry
  "Remove `[ui-root concern ikey]`, pruning an emptied concern map. The
  default lives in the sub, so an absent entry IS the default."
  [db concern ikey]
  (if-some [concerns (get db ui-root)]
    (let [entries (dissoc (get concerns concern) ikey)]
      (assoc db ui-root (if (seq entries)
                          (assoc concerns concern entries)
                          (dissoc concerns concern))))
    db))

(defn- reg-clear-event!
  "Register `[:rf.state/clear concern ikey]`. Re-registration replaces it
  with an identical handler.

  Registered with no source namespace whichever namespace's `reg-state` ran,
  so it is framework-owned: every frame resolves it from the framework base
  (Spec 002 §Image resolution and composition), and two namespaces using
  `reg-state` cannot make it ambiguous."
  []
  (binding [rf.source-coords/*pending-coords* nil]
    (rf.events/reg-event clear-event-id
      (fn clear-handler [{:keys [db]} [_ concern ikey]]
        (check-key! concern ikey :clear)
        {:db (clear-entry db concern ikey)}))))

(defn reg-state
  "Register the per-instance state `concern`: a sub and a setter event under
  `concern`, plus the shared clear event. Returns `concern`.

      (rf/reg-state :todo.ui/expanded? {:default false})

      @(rf/subscribe [:todo.ui/expanded? id])           ;; read
      (rf/dispatch [:todo.ui/expanded? id true])        ;; write
      (rf/dispatch [:rf.state/clear :todo.ui/expanded? id]) ;; back to the default

  `concern` must be namespace-qualified, because it is a sub id, an event id
  and an app-db key at once. `opts` may carry `:default` and nothing else.
  Re-registering replaces all three registrations, so the last `:default`
  wins. Refusals are `:rf.error/state-bad-argument`.

  The sub and setter record the namespace of the `rf/reg-state` macro call
  that reached here, so an explicit image selecting that namespace carries
  them; called directly, as a fn, they record none and only a frame made
  without `:images` resolves them. Contract: Spec 002 §Per-instance view
  state."
  ([concern] (reg-state concern nil))
  ([concern opts]
   (when-not (and (keyword? concern) (namespace concern))
     (refuse! 'rf/reg-state
              (str "A state concern must be a namespace-qualified keyword; it "
                   "was " (pr-str concern) ". The concern is a sub id, an event "
                   "id and an app-db key at once, so an unqualified one collides "
                   "with every other feature on the page in three registries.")
              {:concern concern}))
   (when-not (or (nil? opts) (map? opts))
     (refuse! 'rf/reg-state
              (str "reg-state options must be a map; for " (pr-str concern)
                   " they were " (pr-str opts) ".")
              {:concern concern :options opts}))
   (when-let [unknown (seq (disj (set (keys opts)) :default))]
     (let [unknown (vec (sort-by str unknown))]
       (refuse! 'rf/reg-state
                (str "reg-state accepts :default and nothing else; "
                     (pr-str concern) " was given " (pr-str unknown)
                     ". An option that is quietly ignored is a setting its "
                     "author believes is in force.")
                {:concern concern :unknown unknown})))
   (let [default (:default opts)]
     (rf.subs/reg-sub concern
       (fn read-state [db query-v]
         (let [ikey (check-key! concern (nth query-v 1 nil) :read)]
           (get-in db [ui-root concern ikey] default))))
     (rf.events/reg-event concern
       (fn write-state [{:keys [db]} [_ ikey v]]
         (check-key! concern ikey :write)
         {:db (assoc-in db [ui-root concern ikey] v)}))
     (reg-clear-event!)
     concern)))
