(ns re-frame.freehand.compiler.env
  "Compile-time environment + head/symbol resolution for the template
  analyzer (the Q5 surface: internal-vs-foreign head discrimination).

  ## The Q5 discrimination rule (pinned)

  A symbol head classifies by RESOLUTION at expansion time:

    1. the symbol being `defview`d right now (self) -> INTERNAL VIEW
       (self-recursion works; the var need not exist yet);
    2. a symbol whose resolved var carries `:rf.ui/view` metadata ->
       INTERNAL VIEW (`defview` stamps it; aliases resolve through ns
       aliases and `:refer` naturally);
    3. a resolvable symbol WITHOUT view metadata -> FOREIGN component
       boundary (open props; JS values pass through);
    4. an unresolvable symbol -> COMPILE ERROR (didactic).

  Corners (test-pinned in re-frame.freehand.analyze-accept-cljs-test and
  re-frame.freehand.analyze-reject-cljs-test, with the real-CLJS-analyzer
  counterpart in re-frame.freehand.compiler-macro-resolution-jvm-test):
    - forward/mutual recursion: `(declare ^:rf.ui/view b)` marks the var
      before definition, so mutually-recursive views are expressible with
      zero extra surface; a plain `(declare b)` head classifies FOREIGN
      (rule 3) — the declare-hint is the supported spelling;
    - var-copies (`(def card2 card)`) do NOT carry view-ness (var meta is
      not copied by def) — they classify foreign; alias the NAMESPACE or
      use the original name;
    - locals shadowing a view name classify as neither (the analyzer
      treats a local-bound head as a dynamic head -> compile error, since
      dynamic tag/component heads are rejected)."
  (:require [clojure.string :as str]
            [re-frame.freehand.props-schema :as props-schema]))

(defn make-env
  "host :clj|:cljs; cljs-env = &env when :cljs; ns-sym = consuming ns;
  self = the view symbol being compiled (nil outside defview);
  self-id = its view id. `resolver` (tests only) overrides host
  resolution: (fn [sym]) -> {:fqn sym :meta {..}} | nil."
  [{:keys [host cljs-env ns-sym self self-id resolver source template-anchor]}]
  {:host      host
   :cljs-env  cljs-env
   :ns        ns-sym
   :self      self
   :self-id   self-id
   :resolver  resolver
   ;; Compiler-owned lexical-site identity inputs. `source` is the defview
   ;; declaration anchor (only relative line/column deltas are consumed) and
   ;; `template-anchor` is a semantic whole-template fallback for reader forms
   ;; that carry no usable source metadata. Neither reaches emitted code.
   :source    source
   :template-anchor template-anchor
   :locals    #{}
   :loop-syms #{}
   :in-loop?  false
   :path      []
   :warnings  (atom [])
   ;; `:diagnostics` carries the compile-tier a11y findings (S4-C) — INCLUDING
   ;; suppressed ones, which stay manifest facts with their reason but never
   ;; print. Every other kind is a lowering/ownership site.
   ;; `:views` is the CROSSING index — one entry per lexically visible
   ;; internal-view boundary in the body, in source order, each carrying the
   ;; lowering the child declaration reports. It is what makes a compiled
   ;; manifest able to say where this body stops being compiled (D010).
   :sites     (atom {:events [] :subs [] :htmls []
                     :slots [] :views [] :diagnostics []})})

(defn warn! [env w]
  (swap! (:warnings env) conj w)
  nil)

(defn add-site! [env kind site]
  (swap! (:sites env) update kind conj site)
  nil)

(defn compile-error
  "All analyzer/emitter compile errors carry {:rf.ui.compile/error <id>}
  ex-data — the S1e roster/catalogue slice keys off these ids."
  ([id msg] (compile-error id msg nil))
  ([id msg data]
   (ex-info (str "re-frame.freehand compile error: " msg)
            (merge {:rf.ui.compile/error id} data))))

(defn fail!
  ([env id msg] (fail! env id msg nil))
  ([env id msg data]
   (throw (compile-error id msg (assoc data :path (:path env))))))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- resolve-cljs [env sym]
     (try
       (let [resolve* (requiring-resolve 'cljs.analyzer.api/resolve)
             info     (resolve* (:cljs-env env) sym)]
         (when (and info (not (:local info)) (:name info) (symbol? (:name info)))
           ;; CLJS analyzer macro authority is a TOP-LEVEL `:macro` flag on
           ;; the resolved info map (not reliably duplicated in `:meta`).
           ;; Normalize it into the metadata shape shared with CLJ resolution
           ;; so expression rewriting cannot traverse an opaque binder macro.
           {:fqn (:name info)
            :meta (cond-> (or (:meta info) {})
                    (:macro info) (assoc :macro true))}))
       (catch Exception _ nil))))

#?(:clj
   (defn- resolve-clj [env sym]
     (when-let [ns* (find-ns (:ns env))]
       (when-let [v (try (ns-resolve ns* sym) (catch Exception _ nil))]
         (when (var? v)
           (let [m (meta v)]
             {:fqn  (symbol (str (ns-name (:ns m))) (str (:name m)))
              :meta m}))))))

#?(:clj
   (defn- jvm-lazy-value?
     "JVM structural compile: `sym` resolves to a bound var whose value is a
     re-frame.freehand.react/lazy component (marked `:rf.ui/lazy` by lazy-jvm). Lets
     the JVM emitter render a bare `[HeavyChart …]` head's fallback rather than
     raise the foreign host-op — without any authored var metadata."
     [env sym]
     (boolean
      (when-let [ns* (find-ns (:ns env))]
        (when-let [v (try (ns-resolve ns* sym) (catch Exception _ nil))]
          (and (var? v)
               (bound? v)
               (:rf.ui/lazy (try (deref v) (catch Exception _ nil)))))))))

(defn resolve-sym
  "Resolve `sym` in the consuming namespace. Returns {:fqn sym :meta m}
  or nil. Local (template-level) bindings shadow vars."
  [env sym]
  (when-not (contains? (:locals env) sym)
    (if-let [r (:resolver env)]
      (r sym)
      #?(:clj  (if (= :cljs (:host env))
                 (resolve-cljs env sym)
                 (resolve-clj env sym))
         :cljs nil))))

(defn resolves-to?
  "Does `sym` resolve to one of the fully-qualified `targets`?"
  [env sym targets]
  (when-let [{:keys [fqn]} (resolve-sym env sym)]
    (contains? targets fqn)))

(defn declared-view?
  "Does resolved var metadata `m` mark a DECLARED Freehand view?

  `:re-frame.freehand/view` is the marker `v/defview` stamps.
  `:rf.ui/view` is the donor-era spelling the transplanted suites still
  resolve through; it is read here so one classifier serves both while
  the migration finishes, and it goes when the donor artifact does."
  [m]
  (boolean (or (:re-frame.freehand/view m) (:rf.ui/view m))))

(defn accepts-children?
  "May a declared view whose var metadata is `m` be called WITH children?

  Freehand declares a children POLICY (`:none` / `:optional` /
  `:required`), so the compiled tier reads the policy and rejects only
  the view that declared it accepts none — the same law
  `v/normalize-call` enforces at an interpreted call, from the same
  declaration."
  [m]
  (if-let [policy (:re-frame.freehand/children-policy m)]
    (not= :none policy)
    (boolean (:rf.ui/children? m))))

(defn closed-prop-keys
  "The key roster a declared view whose var metadata is `m` closes its props
  map against, or `nil` when it closes none.

  Freehand stamps the SCHEMA itself on the var, and the roster is derived
  from it here through the same function the boundary calls — so a compiled
  parent checking a literal call site and a render checking a delivered
  props map cannot disagree about what a schema admits. `:rf.ui/closed-prop-
  keys` is the donor-era spelling, a precomputed roster the transplanted
  suites still resolve through; it goes when the donor artifact does."
  [m]
  (if (contains? m :re-frame.freehand/props-schema)
    (props-schema/closing-keys (:re-frame.freehand/props-schema m))
    (:rf.ui/closed-prop-keys m)))

(def lowerings
  "The closed roster of answers [[view-lowering]] gives.

  `:unknown` is a real answer and not a failure: a forward-declared head
  (`(declare ^:rf.ui/view b)`) is a view whose declaration the compiler has
  not seen, and a manifest that guessed would be claiming evidence it does
  not have (D010 — a compiled manifest is honest about where what is
  statically known stops)."
  #{:interpreted :compiled :unknown})

(defn view-lowering
  "The execution mode the resolved var metadata `m` reports for a declared
  view — the marker `v/defview` stamps alongside `:re-frame.freehand/view`,
  so a compiled parent can see which of its child boundaries CROSS back into
  the interpreted mode without loading the child's runtime value.

  One rule and no special cases: a declaration that did not say is
  `:unknown`."
  [m]
  (get m :re-frame.freehand/lowering :unknown))

(defn view-id-of
  "The view id a resolved internal view registers under: explicit
  :rf.ui/view-id meta (from an :id override) or the family-rule
  derivation (keyword ns name)."
  [fqn meta*]
  (or (:rf.ui/view-id meta*)
      (keyword (namespace fqn) (name fqn))))

(defn classify-head
  "Q5: classify a symbol head. -> {:kind :view|:foreign :sym sym
  :fqn fqn|nil :view-id kw? :lowering kw?} or throws for unresolvable
  heads. `:lowering` rides an internal-view head only — it is the CROSSING
  fact, and a foreign boundary has no Freehand mode to report."
  [env sym]
  (cond
    (contains? (:locals env) sym)
    (fail! env :rf.ui.compile/dynamic-head
           (str "component head " sym " is a local binding — heads must be "
                "literal: a keyword (DOM/custom element), a defview var, or "
                "a foreign component var. Runtime-chosen components are "
                "v/view / v/element [WAVE-2]; meanwhile keep a "
                "runtime-assembled subtree interpreted, where heads resolve "
                "at render")
           {:head sym})

    ;; The declaration being compiled: a self-recursive head mounts THIS
    ;; view, and this view is the one the compiled front end is lowering, so
    ;; the mode is settled without resolving a var that need not exist yet.
    (and (:self env) (= sym (:self env)))
    {:kind :view :sym sym :fqn (symbol (str (:ns env)) (str sym))
     :view-id (:self-id env) :lowering :compiled}

    :else
    (if-let [{:keys [fqn meta]} (resolve-sym env sym)]
      (cond
        (declared-view? meta)
        {:kind :view :sym sym :fqn fqn :view-id (view-id-of fqn meta)
         :lowering (view-lowering meta)}
        ;; a re-frame.freehand.react/lazy component: a foreign head that IS callable on
        ;; the JVM structural render (it renders its fallback / nothing), so the
        ;; JVM emitter invokes it instead of raising the foreign host-op. Detected
        ;; by authored var meta OR (JVM structural compile) the marked value.
        (or (:rf.ui/lazy meta)
            #?(:clj (and (= :clj (:host env)) (jvm-lazy-value? env sym))
               :cljs false))
        {:kind :foreign :sym sym :fqn fqn :lazy? true}
        :else
        {:kind :foreign :sym sym :fqn fqn})
      (fail! env :rf.ui.compile/unresolved-head
             (str "unresolved component head " sym " — require/refer it, or "
                  "for a forward/mutual view reference declare it first: "
                  "(declare ^:rf.ui/view " sym ")")
             {:head sym}))))

;; ---------------------------------------------------------------------------
;; Scope helpers
;; ---------------------------------------------------------------------------

(defn binding-syms
  "All symbols bound by a destructuring pattern (recursive; ignores :or
  defaults' values, collects :as)."
  [pattern]
  (cond
    (symbol? pattern) (if (= '& pattern) [] [pattern])
    (vector? pattern) (into [] (mapcat binding-syms) pattern)
    (map? pattern)
    (into []
          (mapcat (fn [[k v]]
                    (cond
                      (= k :as)   [v]
                      (= k :or)   []
                      (and (keyword? k) (= "keys" (name k))) (mapv #(symbol (name %)) v)
                      (and (keyword? k) (= "syms" (name k))) (mapv #(symbol (name %)) v)
                      (and (keyword? k) (= "strs" (name k))) (mapv #(symbol (name %)) v)
                      :else (binding-syms k))))
          pattern)
    :else []))

(defn with-locals [env syms]
  (update env :locals into syms))

(defn with-loop [env syms]
  (-> env
      (update :locals into syms)
      (update :loop-syms into syms)
      (assoc :in-loop? true)))

(defn syms-in-form
  "Symbols occurring anywhere in `form` (quote-form contents skipped) —
  the loop-capture detector's currency."
  [form]
  (cond
    (symbol? form) #{form}
    (and (seq? form) (= 'quote (first form))) #{}
    (coll? form) (into #{} (mapcat syms-in-form) form)
    :else #{}))

(defn captured-loop-syms [env form]
  (into #{} (filter (:loop-syms env)) (syms-in-form form)))

(defn kws-in-form
  "Keywords occurring anywhere in `form` (quote contents skipped)."
  [form]
  (cond
    (keyword? form) #{form}
    (and (seq? form) (= 'quote (first form))) #{}
    (coll? form) (into #{} (mapcat kws-in-form) form)
    :else #{}))
