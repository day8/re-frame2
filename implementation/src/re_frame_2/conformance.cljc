(ns re-frame-2.conformance
  "DSL interpreter for the conformance fixture handler-body forms.

  The conformance corpus represents handler bodies as data — a small DSL
  the harness interprets into native fns. Per
  docs/specification/conformance/README.md §Handler-body DSL ops.

  Operator set:

  Data ops:
    [:set path value]               assoc-in db at path with value
    [:update path fn-form]          update-in db at path with fn-form
    [:get path]                     (sub bodies) read db at path

  Effect ops:
    [:fx fx-id args]                emit a single fx
    [:fx [[fx-id args] ...]]        emit multiple
    [:dispatch event-vec]           sugar for [:fx :dispatch event-vec]

  Control:
    [:throw msg]                    throw
    [:noop]                         no-op

  Reflection (used as args to data ops):
    [:event-arg n]                  the n-th element of event (0-based)
    [:db-get path]                  read db at path
    [:fn :keyword]                  reference a builtin
    [:fn :keyword arg1 ...]         partial application of a builtin

  Builtins: :inc :dec :+ :- :* :/ :identity :conj :assoc :dissoc
            :item-amount :count")

;; ---- builtins -------------------------------------------------------------

(defn- builtin [k]
  (case k
    :inc       inc
    :dec       dec
    :+         +
    :-         -
    :*         *
    :/         /
    :identity  identity
    :conj      conj
    :assoc     assoc
    :dissoc    dissoc
    :count     count
    :item-amount (fn [item] (* (:qty item) (:price item)))
    (throw (ex-info "unknown :fn builtin" {:builtin k}))))

;; ---- value resolver -------------------------------------------------------

(declare resolve-value)

(defn- resolve-fn-form
  "[:fn :keyword arg1 ...] → fn. With extra args, returns a partially-
  applied fn (one-arg-from-the-runtime, the partial-args bound)."
  [form ctx]
  (let [[_ k & extra-args] form
        f (builtin k)
        resolved (mapv #(resolve-value % ctx) extra-args)]
    (if (seq resolved)
      ;; The runtime calls the fn with one arg (the slot value); we apply
      ;; with the runtime-arg first, then our partial args. e.g.
      ;; [:update [:log] [:fn :conj] :a]  →  (update db [:log] (fn [coll] (conj coll :a)))
      (fn [x] (apply f x resolved))
      f)))

(defn- resolve-value
  "Resolve a value form against the runtime context.
  ctx = {:db <db> :event <event-vec>}."
  [form ctx]
  (cond
    (vector? form)
    (case (first form)
      :event-arg (get (:event ctx) (second form))
      :db-get    (get-in (:db ctx) (second form))
      :fn        (resolve-fn-form form ctx)
      ;; otherwise it's a literal vector (a value).
      form)

    :else form))

;; ---- event-db / event-fx interpreter -------------------------------------

(defn- apply-step
  "Apply one DSL step. Returns a map with :db (possibly updated) and :fx
  (possibly extended). ctx contains the current :db and :event."
  [{:keys [db fx event] :as ctx} step]
  (case (first step)
    :noop      ctx

    :set       (let [[_ path value] step
                     v (resolve-value value ctx)]
                 (assoc ctx :db (assoc-in db path v)))

    :update    (let [[_ path fn-form] step
                     f (resolve-value fn-form ctx)]
                 (assoc ctx :db (update-in db path f)))

    :fx        (let [[_ a b] step]
                 (cond
                   ;; Multi-form: [:fx [[fx-id args] ...]]
                   (and (vector? a) (every? vector? a))
                   (assoc ctx :fx (into (or fx []) a))

                   ;; Single form: [:fx fx-id args]
                   :else
                   (assoc ctx :fx (conj (or fx []) [a (resolve-value b ctx)]))))

    :dispatch  (let [ev (resolve-value (second step) ctx)]
                 (assoc ctx :fx (conj (or fx []) [:dispatch ev])))

    :throw     (throw (ex-info (str "fixture-thrown: " (second step))
                               {:from-fixture? true}))

    ;; :get and :reduce-input are sub-body ops; the realise-sub-handler
    ;; reads them separately. Treated as no-op here.
    :get       ctx
    :reduce-input ctx
    :db-get    ctx

    (throw (ex-info "unknown DSL op" {:op step}))))

(defn realise-event-db-handler
  "DSL → an event-db handler fn (db, event) → new-db.
  Steps run in order; only :db is observed from the result."
  [steps]
  (fn [db event]
    (let [final (reduce apply-step
                        {:db db :event event :fx []}
                        steps)]
      (:db final))))

(defn realise-event-fx-handler
  "DSL → an event-fx handler fn (cofx, event) → effects-map.
  Steps run in order; both :db and :fx are observed."
  [steps]
  (fn [cofx event]
    (let [db    (:db cofx)
          final (reduce apply-step
                        {:db db :event event :fx []}
                        steps)]
      (cond-> {}
        (not= (:db final) db) (assoc :db (:db final))
        (seq (:fx final))     (assoc :fx (:fx final))))))

(defn- has-fx-op? [steps]
  (some #(or (= :fx (first %)) (= :dispatch (first %))) steps))

(defn realise-event-handler
  "Pick the right handler shape based on whether the body emits any fx.
  If it does, wrap as event-fx; else event-db."
  [steps]
  (if (has-fx-op? steps)
    [:fx (realise-event-fx-handler steps)]
    [:db (realise-event-db-handler steps)]))

;; ---- sub interpreter ------------------------------------------------------

(defn realise-sub-handler
  "DSL → a sub fn (db, query) → value. Sub bodies typically use [:get path]
  but may also use [:reduce-input ...] etc. (TODO — first pass handles
  :get only)."
  [steps]
  (fn [db _query]
    (let [final (reduce apply-step
                        {:db db :event nil :fx []}
                        steps)]
      ;; The 'value' for a sub is the result of the LAST :get step, OR if
      ;; the steps included no :get, the final db (sub of identity).
      (let [last-get (->> steps (filter #(= :get (first %))) last)]
        (if last-get
          (get-in db (second last-get))
          (:db final))))))
