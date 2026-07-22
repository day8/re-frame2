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

  Corners (all test-pinned in re-frame.freehand.q5-heads-cljs-test):
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
  (:require [clojure.string :as str]))

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
   ;; True in a defview's UNCONDITIONAL top region (the body + top-region
   ;; let/do bodies), where host hooks (`local` / `effect`) are legal; cleared
   ;; on entering a branch / loop / deferred callback. Set by the defview
   ;; driver; false everywhere else (root forms, ui.test).
   :hooks-region? false
   :path      []
   :warnings  (atom [])
   ;; A shared monotonic ordinal over the LET-BODY host hooks (`local` +
   ;; the six re-frame.freehand.react hooks) in source order. Stamped on react-hook
   ;; sites so the hook signature detects a local↔react reorder (both live in
   ;; the same top-region let, interleaved in React hook order) — a per-category
   ;; count vector alone could not. Effects are structurally before (hook-prefix)
   ;; and do not advance it.
   :hook-ordinal (atom 0)
   ;; `:diagnostics` carries the compile-tier a11y findings (S4-C) — INCLUDING
   ;; suppressed ones, which stay manifest facts with their reason but never
   ;; print. Every other kind is a lowering/ownership site.
   :sites     (atom {:events [] :subs [] :htmls [] :frame-ops []
                     :slots [] :locals [] :effects [] :dispatch-fns [] :react []
                     :diagnostics []})})

(defn warn! [env w]
  (swap! (:warnings env) conj w)
  nil)

(defn add-site! [env kind site]
  (swap! (:sites env) update kind conj site)
  nil)

(defn next-hook-ordinal!
  "Advance and return (0-based) the shared body-hook ordinal — the source-order
  position of a `local` / re-frame.freehand.react hook among the top-region let-body
  hooks. Locals advance it without storing it; react-hook sites store theirs, so
  a local↔react reorder shifts a react ordinal and changes the hook signature."
  [env]
  (dec (long (swap! (:hook-ordinal env) inc))))

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

(defn view-id-of
  "The view id a resolved internal view registers under: explicit
  :rf.ui/view-id meta (from an :id override) or the family-rule
  derivation (keyword ns name)."
  [fqn meta*]
  (or (:rf.ui/view-id meta*)
      (keyword (namespace fqn) (name fqn))))

(defn classify-head
  "Q5: classify a symbol head. -> {:kind :view|:foreign :sym sym
  :fqn fqn|nil :view-id kw?} or throws for unresolvable heads."
  [env sym]
  (cond
    (contains? (:locals env) sym)
    (fail! env :rf.ui.compile/dynamic-head
           (str "component head " sym " is a local binding — heads must be "
                "literal: a keyword (DOM/custom element), a defview var, or "
                "a foreign component var. Runtime-chosen components are "
                "v/view / v/element [WAVE-2]; v/raw covers a runtime "
                "React element meanwhile")
           {:head sym})

    (and (:self env) (= sym (:self env)))
    {:kind :view :sym sym :fqn (symbol (str (:ns env)) (str sym))
     :view-id (:self-id env)}

    :else
    (if-let [{:keys [fqn meta]} (resolve-sym env sym)]
      (cond
        (:rf.ui/view meta)
        {:kind :view :sym sym :fqn fqn :view-id (view-id-of fqn meta)}
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
