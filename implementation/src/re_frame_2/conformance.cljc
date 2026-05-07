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
    ;; Tolerant numeric ops — nil starting state is implicit-zero for the
    ;; fixtures (mirrors how re-frame app code typically uses (fnil inc 0)).
    :inc       (fnil inc 0)
    :dec       (fnil dec 0)
    :+         +
    :-         -
    :*         *
    :/         /
    :>=        >=
    :<=        <=
    :>         >
    :<         <
    :=         =
    :not=      not=
    :and       (fn [& xs] (every? identity xs))
    :or        (fn [& xs] (boolean (some identity xs)))
    :not       not
    :identity  identity
    :conj      conj
    :assoc     assoc
    :dissoc    dissoc
    :count     (fn [x]
                 ;; Fixtures use [:fn :count] to assert sub-exception
                 ;; recovery; raising a recognisable message lets the
                 ;; error trace expose a stable :exception-message.
                 ;; The error/sub-exception fixture sets :items to a
                 ;; string ("broken") and expects counting to fail with
                 ;; "cannot count a string" — we honour that intent by
                 ;; refusing strings AND Characters (a string element
                 ;; landed as a char during seq iteration).
                 (cond
                   (string? x)    (throw (ex-info "cannot count a string" {}))
                   (char? x)      (throw (ex-info "cannot count a string" {}))
                   (nil? x)       (throw (ex-info "cannot count nil" {}))
                   :else          (count x)))
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
  ctx = {:db <db> :event <event-vec> :cofx <cofx-map>}.

  Walks recursively into maps and literal vectors so reflection forms
  inside compound values are resolved (e.g. {:id [:event-arg 1]})."
  [form ctx]
  (cond
    (vector? form)
    (case (first form)
      :event-arg    (let [[_ idx maybe] form
                          v (get (:event ctx) idx)]
                      ;; Two shapes overload the third element:
                      ;;   [:event-arg n default-for-nil]
                      ;;   [:event-arg n key-into-value]   (when value is a map
                      ;;                                    and 3rd is keyword)
                      ;; Disambiguate by the type of the third element.
                      (cond
                        (and (>= (count form) 3)
                             (keyword? maybe)
                             (map? v))             (get v maybe)
                        (and (>= (count form) 3)
                             (nil? v))             maybe
                        :else                      v))
      :get-event-arg (let [[_ idx k] form
                           m (get (:event ctx) idx)]
                       (get m k))
      :db-get       (let [[_ path default] form
                          v (get-in (:db ctx) path)]
                      (if (and (nil? v) (>= (count form) 3)) default v))
      :get          (let [[_ path] form]
                      ;; Read from :data when present (machine bodies),
                      ;; else from :db (event bodies). The two contexts
                      ;; share the same shorthand.
                      (cond
                        (contains? ctx :data) (get-in (:data ctx) path)
                        :else                 (get-in (:db ctx) path)))
      :fn           (resolve-fn-form form ctx)
      :cofx-key     (get (:cofx ctx) (second form))
      :cofx-without (let [excluded (set (rest form))]
                      (apply dissoc (:cofx ctx) excluded))
      ;; otherwise it's a literal vector — walk into elements so any
      ;; reflection forms nested inside still resolve.
      (mapv #(resolve-value % ctx) form))

    (map? form)
    (reduce-kv (fn [m k v] (assoc m k (resolve-value v ctx))) {} form)

    :else form))

(defn resolve-value*
  "Public alias for the private resolve-value, used by the conformance
  test runner for machine-handler realisation."
  [form ctx]
  (resolve-value form ctx))

(defn eval-value*
  "For machine action/guard bodies: evaluate [:fn :k a b ...] as
  '(f a b ...)' rather than as a partial fn awaiting one runtime arg.

  Resolves nested forms and then applies the builtin to the resolved
  args. For non-:fn forms, falls back to resolve-value."
  [form ctx]
  (cond
    (and (vector? form) (= :fn (first form)))
    (let [[_ k & extra-args] form
          f (#'re-frame-2.conformance/builtin k)
          resolved (mapv #(resolve-value % ctx) extra-args)]
      (apply f resolved))

    :else (resolve-value form ctx)))

;; ---- event-db / event-fx interpreter -------------------------------------

(defn- apply-step
  "Apply one DSL step. Returns a map with :db (possibly updated) and :fx
  (possibly extended). ctx contains the current :db and :event."
  [{:keys [db fx event] :as ctx} step]
  (case (first step)
    :noop      ctx

    :set       (let [[_ path value] step
                     v (resolve-value value ctx)]
                 ;; assoc-in with an empty path would associate at key nil
                 ;; (Clojure's destructuring quirk). Treat empty path as
                 ;; "replace whole db" — used by hydrate handlers.
                 (assoc ctx :db
                        (if (empty? path) v (assoc-in db path v))))

    :update    (let [[_ path fn-form & extra-args] step
                     f             (resolve-value fn-form ctx)
                     resolved-args (mapv #(resolve-value % ctx) extra-args)]
                 (assoc ctx :db (apply update-in db path f resolved-args)))

    :merge-into-db
    (let [[_ value-form] step
          payload (resolve-value value-form ctx)]
      (assoc ctx :db (merge db payload)))

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

    :throw     (throw (ex-info (str (second step))
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
                        {:db db :event event :fx [] :cofx {:db db :event event}}
                        steps)]
      (:db final))))

(defn realise-event-fx-handler
  "DSL → an event-fx handler fn (cofx, event) → effects-map.
  Steps run in order; both :db and :fx are observed.

  cofx is threaded into ctx so [:cofx-key k] / [:cofx-without ...] forms
  resolve against the actual coeffect map (envelope keys included).

  Note: handlers that do not change :db still need to commit a :db effect
  if they wrote to it — we always include :db when the body emitted any
  :set/:update steps, since that's how the fixtures observe captures of
  cofx data into the db."
  [steps]
  (fn [cofx event]
    (let [db    (:db cofx)
          final (reduce apply-step
                        {:db db :event event :fx [] :cofx cofx}
                        steps)
          db-changed? (not= (:db final) db)]
      (cond-> {}
        db-changed?       (assoc :db (:db final))
        (seq (:fx final)) (assoc :fx (:fx final))))))

(defn- walk-hiccup
  "Recursively walk a hiccup tree, replacing reflection forms with their
  resolved values. Used by realise-view-handler so view bodies can
  embed [:event-arg n] / [:db-get path] / [:fn ...] inside hiccup."
  [form ctx]
  (cond
    (and (vector? form)
         (#{:event-arg :db-get :fn :get} (first form)))
    (resolve-value form ctx)

    (vector? form)
    (mapv #(walk-hiccup % ctx) form)

    (map? form)
    (reduce-kv (fn [m k v] (assoc m k (walk-hiccup v ctx))) {} form)

    :else form))

(defn realise-view-handler
  "DSL → a view handler fn that, given the args passed to the view (e.g.
  [\"world\"] for [:greeting \"world\"]), returns a hiccup tree with
  reflection forms resolved.

  Conventions:
    [:hiccup <tree>] — the body is the hiccup tree.
    [:event-arg n]   — indexes args (no event-id offset).
    [:db-get path]   — reads from the implicit db (currently nil)."
  [steps]
  (fn [& args]
    (let [hiccup-step (some (fn [s] (when (= :hiccup (first s)) s)) steps)
          tree        (when hiccup-step (second hiccup-step))
          ctx         {:event (vec args) :db nil}]
      (when tree (walk-hiccup tree ctx)))))

(defn realise-fx-handler
  "DSL → an fx handler fn. fx handlers receive ({:frame frame-id} args).

  A fixture's fx body may :throw, :noop, mutate the frame's app-db via
  :set/:update, or :dispatch a follow-up event (used to model
  http-stub-style fx that synthesise a result). The args is exposed
  to the body as if it were an 'event' — i.e. [:event-arg 1] resolves
  to the args value (the synthetic event is [fx-id args]).

  read-db!/write-db!/dispatch! are wired by the runner so this namespace
  stays free of internal substrate / router deps."
  [fx-id steps {:keys [read-db! write-db! dispatch!]}]
  (fn [{:keys [frame]} args]
    (let [db              (read-db! frame)
          synthetic-event [fx-id args]
          final (reduce apply-step
                        {:db db :event synthetic-event :fx []
                         :cofx {:frame frame}}
                        steps)]
      (when (not= db (:db final))
        (write-db! frame (:db final)))
      ;; Any :dispatch fx the body produced are enqueued on the same frame.
      (doseq [pair (:fx final)]
        (when (and (vector? pair) (= :dispatch (first pair)))
          (dispatch! (second pair) frame)))
      nil)))

(defn- needs-fx-handler?
  "Returns true if the body uses any op or value form that requires the
  full coeffect map (and thus must be wrapped as event-fx, not event-db).
  Detects :fx, :dispatch ops and :cofx-key / :cofx-without value forms."
  [steps]
  (letfn [(uses-cofx? [v]
            (and (vector? v)
                 (#{:cofx-key :cofx-without} (first v))))]
    (some (fn [step]
            (or (= :fx (first step))
                (= :dispatch (first step))
                (some uses-cofx? (tree-seq coll? seq step))))
          steps)))

(defn realise-event-handler
  "Pick the right handler shape based on whether the body emits any fx
  or reads cofx beyond db/event. If it does, wrap as event-fx; else event-db."
  [steps]
  (if (needs-fx-handler? steps)
    [:fx (realise-event-fx-handler steps)]
    [:db (realise-event-db-handler steps)]))

;; ---- sub interpreter ------------------------------------------------------
;;
;; Sub bodies in the corpus take a few shapes:
;;
;;   [[:get [:path]]]                                      ;; layer-1
;;   [[:reduce-input :other-sub [:fn :+] [:fn :item-am.]]] ;; layer-2 fold
;;   [[:reduce-input :other-sub [:fn :+]]]                 ;; layer-2 sum
;;
;; realise-sub returns a map describing the registration the runner should
;; perform: {:kind :layer-1 :body fn}
;;          {:kind :layer-2 :inputs [[:other-sub]] :body fn}

(defn realise-sub
  [steps]
  (let [first-step (first steps)]
    (cond
      ;; layer-2 reduce-input form
      (and (vector? first-step) (= :reduce-input (first first-step)))
      (let [[_ input-sub-id reducer-form mapper-form] first-step
            reducer (resolve-value reducer-form {})
            mapper  (when mapper-form (resolve-value mapper-form {}))]
        {:kind   :layer-2
         :inputs [[input-sub-id]]
         :body   (fn [input-val _query]
                   (reduce reducer
                           (if mapper (map mapper input-val) input-val)))})

      ;; layer-1 :get
      (and (vector? first-step) (= :get (first first-step)))
      {:kind :layer-1
       :body (fn [db _query] (get-in db (second first-step)))}

      :else
      ;; default: run the steps over db, return the resulting db (identity sub)
      {:kind :layer-1
       :body (fn [db _query]
               (:db (reduce apply-step
                            {:db db :event nil :fx []}
                            steps)))})))

(defn realise-sub-handler
  "Backwards-compatible: returns just the body fn. Prefer realise-sub when
  layer-2+ registration is needed."
  [steps]
  (:body (realise-sub steps)))
