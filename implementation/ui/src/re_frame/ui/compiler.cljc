(ns re-frame.ui.compiler
  "defview / custom-element expansion pipeline: arity + options parsing,
  header (Q2) analysis, template analysis, manifest + fingerprint
  assembly, and per-host emission. One template parse -> one normalized
  AST -> per-host emitter (the portability law: no emitter consumes raw
  source or another emitter's output).

  Runs on the JVM only (macro expansion for both hosts); .cljc so the
  namespace stays loadable everywhere."
  (:require [clojure.string :as str]
            #?@(:clj [[re-frame.ui.compiler.analyze :as ana]
                      [re-frame.ui.compiler.build :as build]
                      [re-frame.ui.compiler.emit-cljs :as emit-cljs]
                      [re-frame.ui.compiler.emit-jvm :as emit-jvm]
                      [re-frame.ui.compiler.env :as env]
                      [re-frame.ui.compiler.header :as header]
                      [re-frame.ui.fingerprint :as fingerprint]])))

#?(:clj
   (do

;; Each Shadow build carries accepted registries plus disposable compiler-env
;; scratch. A recompiled / edited / renamed source replaces its prior rows and
;; independent builds never share state. Distinct declarations with equal ids
;; fail atomically; the same var remains the HMR replacement path. The digest is
;; computed once from the finalized candidate and becomes accepted only when
;; Shadow retains the returned build-state after the complete pipeline.
;;
;; The compile-time custom-element declarations are a build-scoped registry
;; like the other four: WRITTEN here (macro expansion → `build/contribute!
;; build/elements`) and READ on the compile path by the template analyzer
;; through `build/element-properties`, which resolves the AMBIENT build's
;; slice (rf2-vxgfnd.91). No process-global mirror atom: the former
;; `build/register! build/elements rules/custom-elements` bridge was a
;; last-writer-wins hazard where a sibling build's contribution could flip
;; another build's classification. The CLJS/JVM RUNTIME arm keeps its own
;; `re-frame.ui.rules/custom-elements` ledger (populated by the emitted
;; `register-custom-element!`, read at render for dynamic `ui/spread` props);
;; it is separate from this compile registry.

(defn current-build-digest
  "The deterministic whole-build digest (`bd1-...`) of an accepted build.

  With no argument this reads the currently bound compiler-env (or the
  plain-JVM test fallback). JVM tooling outside compilation must pass the
  explicit retained Shadow build-state/compiler-env; there is no process-global
  'latest build' authority."
  ([] (build/finalized-build-digest))
  ([build-state-or-compiler-env]
   (build/accepted-build-digest build-state-or-compiler-env)))

(def ^:private defview-option-keys #{:props :id :display-name})

(defn- fail
  ([id msg] (fail id msg nil))
  ([id msg data] (throw (env/compile-error id msg data))))

(defn- parse-defview-forms
  "Parse `[docstring? opts-map? argv & body]`. The body is partitioned after
  symbol resolution into a leading lease-declaration prefix plus one template."
  [vname forms]
  (let [[docstring forms] (if (string? (first forms))
                            [(first forms) (rest forms)]
                            [nil forms])
        [opts forms]      (if (map? (first forms))
                            [(first forms) (rest forms)]
                            [{} forms])
        argv              (first forms)
        body              (rest forms)]
    (when-not (vector? argv)
      (fail :rf.ui.compile/bad-defview-args
            (str "defview " vname ": missing argument vector — "
                 "(defview name docstring? opts? [props?] template)")
            {:view vname}))
    ;; a lone template vector reads as an argv — diagnose the real mistake
    (when (and (empty? body) (keyword? (first argv)))
      (fail :rf.ui.compile/bad-defview-args
            (str "defview " vname ": " (pr-str argv) " looks like the "
                 "template, but the argument vector is missing — write "
                 "(defview " vname " [] " (pr-str argv) ") for a zero-prop view")
            {:view vname}))
    (let [unknown (remove defview-option-keys (keys opts))]
      (when (seq unknown)
        (fail :rf.ui.compile/unknown-option
              (str "defview " vname ": unknown option"
                   (when (next unknown) "s") " "
                   (str/join ", " (map pr-str unknown))
                   " — the options map is CLOSED for v1: "
                   ":props, :id, :display-name. (:memo false, :on-mount, "
                   ":on-unmount, :catch and :fallback were considered and "
                   "rejected — see Spec 004 §Removed forms)")
              {:view vname :unknown (vec unknown)})))
    (when-let [id (:id opts)]
      (when-not (and (keyword? id) (namespace id))
        (fail :rf.ui.compile/bad-view-id
              (str "defview " vname ": :id override must be a qualified "
                   "keyword; got " (pr-str id))
              {:view vname :id id})))
    {:docstring docstring :opts opts :argv argv :body body}))

(defn- split-defview-body
  "Return `[lease-forms body-forms]` for the closed v1 body grammar:

      direct-resolved-lease* (effect …)* final-template

  Leading `(lease descriptor)` declarations are peeled here; the remaining
  `body-forms` (leading `(effect …)` statements, then the one final template)
  are analyzed as a hooks-region body. A conditional lease keeps the
  declaration direct and makes its descriptor conditional; no arbitrary
  statement body is opened."
  [e vname body]
  (loop [leases [] forms (seq body)]
    (cond
      (nil? forms)
      (fail :rf.ui.compile/multi-form-body
            (str "defview " vname ": leading lease declarations must be "
                 "followed by a template form")
            {:view vname})

      (ana/lease-declaration-form? e (first forms))
      (recur (conj leases (first forms)) (next forms))

      :else [leases (vec forms)])))

(defn- capabilities [ast]
  (let [caps (volatile! #{})]
    (letfn [(scan [n]
              (when (map? n)
                (case (:op n)
                  :raw     (vswap! caps conj :raw)
                  :html    (vswap! caps conj :html)
                  :foreign (vswap! caps conj :foreign)
                  :slot    (vswap! caps conj :render-slot)
                  :element (do (when (:custom? n) (vswap! caps conj :custom-element))
                               (when (get-in n [:props :spread])
                                 (vswap! caps conj :spread))
                               (when (get-in n [:props :safe-spread])
                                 (vswap! caps conj :spread-safe))
                               (when (:html n) (vswap! caps conj :html)))
                  nil)
                ;; A compiled render slot callback (a component prop value or an
                ;; inline ui/slot argument) carries a :render-fn compiled body.
                (when (:render-fn n) (vswap! caps conj :render-fn))
                (doseq [[_ v] n]
                  (cond
                    (map? v) (scan v)
                    (vector? v) (doseq [x v] (scan x))))))]
      (scan ast))
    @caps))

(defn- source-coords [form cljs?]
  (let [m (meta form)]
    (cond-> {:file (if cljs?
                     (try @(requiring-resolve 'cljs.analyzer/*cljs-file*)
                          (catch Exception _ *file*))
                     *file*)}
      (:line m)   (assoc :line (:line m))
      (:column m) (assoc :column (:column m)))))

(defn- anchored
  "Run `thunk`; a compile error escaping it gains the declaration form's
  source coordinates (:file/:line/:column) in its ex-data — the S1e
  file:line anchoring, so every {:rf.ui.compile/error <id>} carries WHERE
  alongside id + didactic message. Existing ex-data wins on collisions;
  id, message and cause are preserved."
  [coords thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (throw
         (if (and (:rf.ui.compile/error data) (nil? (:file data)))
           (ex-info (ex-message ex) (merge coords data) ex)
           ex))))))

(defn- defview**
  [form menv vname forms]
  (when-not (simple-symbol? vname)
    (fail :rf.ui.compile/bad-defview-args
          (str "defview name must be a simple symbol; got " (pr-str vname))
          {:view vname}))
  (let [cljs?   (some? (:ns menv))
        ns-sym  (if cljs? (-> menv :ns :name) (ns-name *ns*))
        {:keys [docstring opts argv body]} (parse-defview-forms vname forms)
        view-id (or (:id opts) (keyword (str ns-sym) (str vname)))
        hdr     (header/parse-header argv)
        schema-keys (header/props-schema-keys (:props opts))
        _       (doseq [k (or schema-keys [])]
                  (when (contains? #{:key :ref} k)
                    (fail :rf.ui.compile/key-prop-declared
                          (str "defview " vname ": " k " cannot be declared in "
                               ":props — it is a reserved React slot (callers "
                               "pass it at the call site; it never arrives as "
                               "a prop). Remove the schema entry")
                          {:view vname :key k})))
        slots       (header/declared-slots hdr schema-keys)
        children?   (boolean (or (:children? hdr)
                                 (some #(= :children %) (or schema-keys []))))
        closed-keys (when (contains? opts :props) slots)
        display-name (or (:display-name opts)
                         (str (namespace view-id) "/" (name view-id)))
        header-syms (into (set (mapcat env/binding-syms
                                       (keep :pattern (:entries hdr))))
                          (when (:as-sym hdr) [(:as-sym hdr)]))
        src     (source-coords form cljs?)
        e0      (-> (env/make-env {:host (if cljs? :cljs :clj)
                                    :cljs-env menv
                                    :ns-sym ns-sym
                                    :self vname
                                    :self-id view-id
                                    :source src
                                    ;; Reader metadata is not guaranteed (macro-
                                    ;; generated templates). In that case every
                                    ;; site incorporates this whole-template
                                    ;; semantic anchor, so an edit safely
                                    ;; reacquires instead of transferring an
                                    ;; ordinal to another lexical site.
                                    :template-anchor
                                    (fingerprint/digest "sta1-" body)})
                    (assoc :self-children? children?
                           :self-closed-keys closed-keys))
        _       (ana/reject-reactive-binding! e0 argv)
        ;; The defview body is the UNCONDITIONAL hooks region — where `local` /
        ;; `effect` host hooks are legal (cleared on entering any branch/loop/
        ;; deferred callback).
        e       (-> (env/with-locals e0 header-syms)
                    (assoc :hooks-region? true))
        [lease-forms body-forms] (split-defview-body e vname body)
        lease-declarations
        (mapv #(ana/analyze-lease-declaration e %1 %2)
              (range) lease-forms)
        ast     (ana/analyze-view-body e body-forms)
        _       (doseq [w @(:warnings e)]
                  (binding [*out* *err*]
                    (println (str "WARNING re-frame.ui [" view-id "] "
                                  (:id w) ": " (:msg w)))))
        sites   @(:sites e)
        ast-projection (ana/template-fingerprint-projection ast)
        tf      (fingerprint/template-fingerprint
                 (if (seq lease-declarations)
                   {:leases (mapv (comp ana/template-fingerprint-projection
                                        :descriptor)
                                  lease-declarations)
                    :template ast-projection}
                   ast-projection))
        ;; The HMR hook signature: `local` sites (all `:local`) and `effect`
        ;; sites (each `:connect`/`:deps`) in source order. Adding, removing, or
        ;; changing the KIND of a host hook changes the signature, so the dev
        ;; view remounts (React hook order must stay stable within a Fiber);
        ;; `sub` sites stay excluded by design (Spec 004 §Hot reload).
        hs      (fingerprint/hook-signature-hash
                 {:locals (mapv (constantly :local) (:locals sites))
                  :effects (mapv :kind (:effects sites))})
        manifest {:view-id view-id
                  :display-name display-name
                  :doc docstring
                  :source src
                  :prop-slots (into []
                                    (map-indexed
                                     (fn [i k]
                                       {:key k :slot (header/slot-name k) :index i}))
                                    (cond-> slots
                                      (and children?
                                           (not (some #(= :children %) slots)))
                                      (conj :children)))
                  :props-schema (:props opts)
                  :open-props? (not (contains? opts :props))
                  :children? children?
                  :as? (= :as (:mode hdr))
                  :template-fingerprint tf
                  :hook-signature hs
                  :capabilities (cond-> (capabilities ast)
                                  (seq (:locals sites))       (conj :local)
                                  (seq (:effects sites))      (conj :effect)
                                  (seq (:dispatch-fns sites)) (conj :dispatch-fn))
                  :sites sites}
        args    {:vname vname
                 ;; The canonical current-namespace Var a self-recursive head
                 ;; must emit AGAINST — identical to the `:fqn` env/classify-head
                 ;; stamps on an exact self component node (rf2-rr26cq). A bare
                 ;; self head resolves through a same-named `:refer` on CLJS
                 ;; (refers outrank local defs in cljs.analyzer/resolve-var), so
                 ;; both emitters target THIS fqn, not the authored spelling.
                 :self-fqn (symbol (str ns-sym) (str vname))
                 :view-id view-id
                 :display-name display-name
                 :docstring docstring
                 :header hdr
                 :slots slots
                 :lease-declarations lease-declarations
                 :ast ast
                 :manifest manifest
                 :closed-keys closed-keys
                 :children? children?}]
    (when-let [{:keys [conflict]}
               (build/contribute-view-checked!
                ns-sym [ns-sym vname] view-id [tf hs])]
      (fail :rf.ui.compile/bad-view-id
            (str "defview " vname ": view id " (pr-str view-id)
                 " is already owned by a different defview declaration. "
                 "Explicit :id overrides must be unique across declarations; "
                 "give this view a distinct qualified keyword (re-expanding "
                 "the exact same var remains the HMR replacement path)")
            {:view vname
             :id view-id
             :namespace ns-sym
             :declaration [ns-sym vname]
             :existing-declaration conflict}))
    (if cljs?
      ;; Direct no-pass REPL evaluation may replace the runtime view body, but
      ;; carries no digest assignment. Only a successful configured file/watch
      ;; pass patches the whole-build carrier (Option C).
      (emit-cljs/emit-defview args)
      (emit-jvm/emit-defview args))))

(defn defview*
  "The defview macro body. `form` = &form, `menv` = &env. Every compile
  error thrown during expansion is anchored with the defview form's
  source coordinates (S1e roster: id + didactic message + file:line)."
  [form menv vname forms]
  (anchored (source-coords form (some? (:ns menv)))
            #(defview** form menv vname forms)))

(defn- custom-element**
  [coords ns-sym tag opts]
  (when-not (and (keyword? tag) (nil? (namespace tag))
                 (str/includes? (name tag) "-"))
    (fail :rf.ui.compile/bad-custom-element
          (str "(ui/custom-element tag opts): tag must be an unqualified "
               "keyword containing '-' (the custom-element grammar); got "
               (pr-str tag))
          {:tag tag}))
  (when-not (map? opts)
    (fail :rf.ui.compile/bad-custom-element
          "(ui/custom-element tag {:properties #{...}}) needs a literal options map"
          {:tag tag :opts opts}))
  (let [unknown (remove #{:properties} (keys opts))]
    (when (seq unknown)
      (fail :rf.ui.compile/bad-custom-element
            (str "unknown custom-element option"
                 (when (next unknown) "s") " "
                 (str/join ", " (map pr-str unknown))
                 " — the v1 grammar is exactly {:properties #{...}} "
                 "(closed; :events / per-prop types / attribute reflection "
                 "are future rulings, not silent growth)")
            {:tag tag :unknown (vec unknown)})))
  (let [props (:properties opts #{})]
    (when-not (and (set? props)
                   (every? #(and (keyword? %) (nil? (namespace %))) props))
      (fail :rf.ui.compile/bad-custom-element
            (str ":properties must be a literal set of unqualified keyword "
                 "property names (kebab; :help-text -> the helpText JS "
                 "property); got " (pr-str props))
            {:tag tag :properties props}))
    ;; compile-time registration (this JVM performs macroexpansion for
    ;; both hosts) routed through the build-scoped model so a removed /
    ;; renamed declaration drops its stale property classification with
    ;; its file (rf2-vxgfnd.16). The emitted runtime registration (ui/spread
    ;; classifies at render via the same registry, on the client) carries the
    ;; declaring ns so a hot-reload that DROPS this declaration evicts its
    ;; stale runtime row — the runtime arm of the same eviction model
    ;; (rf2-vxgfnd.48).
    ;;
    ;; The ONE cross-source declaration law (rf2-vxgfnd.143, delegated ruling
    ;; 2026-07-15 Option A): rf=-equal duplicates co-exist; a CONTRADICTORY
    ;; declaration of the same tag from another source fails ATOMICALLY with
    ;; both [build-id ns-sym] anchors and leaves the last-known-good aggregate
    ;; unchanged. Which of two contradicting sources expands second decides
    ;; only WHERE the failure is anchored — never who wins, and never whether
    ;; the build fails. `:declarations` evidence is sorted, so it is identical
    ;; under every source / evaluation / build-order permutation.
    (let [decl {:properties props}]
      (when-let [{:keys [conflict]} (build/contribute-element-checked!
                                     ns-sym tag decl)]
        (let [build-id (build/current-build-id)
              rows (vec (sort-by (juxt (comp str :build) (comp str :ns))
                                 [{:build build-id
                                   :ns (:owner conflict)
                                   :properties (:properties (:declaration conflict) #{})}
                                  {:build build-id :ns ns-sym :properties props}]))]
          (fail :rf.ui.compile/custom-element-conflict
                (str "conflicting (ui/custom-element " tag " ...) declarations — "
                     (str/join " vs "
                               (map (fn [{:keys [ns properties]}]
                                      (str ns " declares :properties "
                                           (pr-str (vec (sort properties)))))
                                    rows))
                     ". One tag has ONE property manifest: delete the duplicate "
                     "declaration, or make both sources declare an IDENTICAL "
                     ":properties set (identical declarations may co-exist). "
                     "re-frame.ui will not pick a winner by compile order")
                {:tag tag :declarations rows}))))
    `(re-frame.ui.rules/register-custom-element!
      ~tag {:properties ~props} '~ns-sym)))

(defn custom-element*
  "The RULED custom-element declaration grammar (Mike, 2026-07-12):
  (ui/custom-element tag {:properties #{...}}) — top-level,
  compile-resolvable, registers like defview. The :properties set is the
  ENTIRE v1 grammar (options map closed); future keys are new rulings,
  not silent growth. `form` = &form, `menv` = &env — compile errors are
  anchored with the declaration form's source coordinates (S1e)."
  [form menv tag opts]
  (let [coords (source-coords form (some? (:ns menv)))
        ns-sym (if (:ns menv) (-> menv :ns :name) (ns-name *ns*))]
    (anchored coords
              #(custom-element** coords ns-sym tag opts))))

))
