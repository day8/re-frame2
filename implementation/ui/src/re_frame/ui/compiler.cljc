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
                      [re-frame.ui.compiler.emit-cljs :as emit-cljs]
                      [re-frame.ui.compiler.emit-jvm :as emit-jvm]
                      [re-frame.ui.compiler.env :as env]
                      [re-frame.ui.compiler.header :as header]
                      [re-frame.ui.fingerprint :as fingerprint]
                      [re-frame.ui.rules :as rules]])))

#?(:clj
   (do

(defonce ^{:doc "view-id -> [template-fingerprint hook-signature-hash],
  accumulated at macro-expansion time; `current-build-digest` derives the
  bd1- digest (compile-order independent — the triples sort by view-id)."}
  build-views
  (atom {}))

(defn current-build-digest []
  (fingerprint/build-digest
   (mapv (fn [[id [tf hs]]] [id tf hs]) @build-views)))

(def ^:private defview-option-keys #{:props :id :display-name})

(defn- fail
  ([id msg] (fail id msg nil))
  ([id msg data] (throw (env/compile-error id msg data))))

(defn- parse-defview-forms
  "[docstring? opts-map? argv template] with exactly one template form."
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
    (when (not= 1 (count body))
      (fail :rf.ui.compile/multi-form-body
            (str "defview " vname ": the body is exactly ONE template form "
                 "(got " (count body) ") — a view is a pure (props) -> "
                 "template; siblings wrap in [:<> ...], computation goes in "
                 "(let ...)")
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
    {:docstring docstring :opts opts :argv argv :template (first body)}))

(defn- capabilities [ast]
  (let [caps (volatile! #{})]
    (letfn [(scan [n]
              (when (map? n)
                (case (:op n)
                  :raw     (vswap! caps conj :raw)
                  :html    (vswap! caps conj :html)
                  :foreign (vswap! caps conj :foreign)
                  :element (do (when (:custom? n) (vswap! caps conj :custom-element))
                               (when (get-in n [:props :spread])
                                 (vswap! caps conj :spread))
                               (when (:html n) (vswap! caps conj :html)))
                  nil)
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

(defn defview*
  "The defview macro body. `form` = &form, `menv` = &env."
  [form menv vname forms]
  (when-not (simple-symbol? vname)
    (fail :rf.ui.compile/bad-defview-args
          (str "defview name must be a simple symbol; got " (pr-str vname))
          {:view vname}))
  (let [cljs?   (some? (:ns menv))
        ns-sym  (if cljs? (-> menv :ns :name) (ns-name *ns*))
        {:keys [docstring opts argv template]} (parse-defview-forms vname forms)
        view-id (or (:id opts) (keyword (str ns-sym) (str vname)))
        hdr     (header/parse-header argv)
        schema-keys (header/props-schema-keys (:props opts))
        _       (doseq [k (or schema-keys [])]
                  (when (contains? #{:key :ref} k)
                    (fail :rf.ui.compile/key-prop-declared
                          (str "defview " vname ": " k " cannot be declared in "
                               ":props — it is a reserved React slot")
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
        e       (-> (env/make-env {:host (if cljs? :cljs :clj)
                                   :cljs-env menv
                                   :ns-sym ns-sym
                                   :self vname
                                   :self-id view-id})
                    (assoc :self-children? children?
                           :self-closed-keys closed-keys)
                    (env/with-locals header-syms))
        ast     (ana/analyze e template)
        _       (doseq [w @(:warnings e)]
                  (binding [*out* *err*]
                    (println (str "WARNING re-frame.ui [" view-id "] "
                                  (:id w) ": " (:msg w)))))
        sites   @(:sites e)
        tf      (fingerprint/template-fingerprint ast)
        hs      (fingerprint/hook-signature-hash {:locals [] :effects []})
        manifest {:view-id view-id
                  :display-name display-name
                  :doc docstring
                  :source (source-coords form cljs?)
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
                  :capabilities (capabilities ast)
                  :sites sites}
        args    {:vname vname
                 :view-id view-id
                 :display-name display-name
                 :docstring docstring
                 :header hdr
                 :slots slots
                 :ast ast
                 :manifest manifest
                 :closed-keys closed-keys
                 :children? children?}]
    (swap! build-views assoc view-id [tf hs])
    (if cljs?
      (emit-cljs/emit-defview args)
      (emit-jvm/emit-defview args))))

(defn custom-element*
  "The RULED custom-element declaration grammar (Mike, 2026-07-12):
  (ui/custom-element tag {:properties #{...}}) — top-level,
  compile-resolvable, registers like defview. The :properties set is the
  ENTIRE v1 grammar (options map closed); future keys are new rulings,
  not silent growth."
  [tag opts]
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
    ;; both hosts) + emitted runtime registration (ui/spread classifies
    ;; at render via the same registry)
    (rules/register-custom-element! tag {:properties props})
    `(re-frame.ui.rules/register-custom-element! ~tag {:properties ~props})))

))
