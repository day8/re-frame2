(ns re-frame.destroyed-reason-channel-conformance-test
  "Every machine-destroy `:reason` belongs to exactly one trace channel:
  `:rf.machine.lifecycle/destroyed` carries only `:parent-frame-destroyed`,
  `:rf.machine/destroyed` carries `:rf.machine/finished` and `:explicit`
  (Spec 009 §`:op-type` vocabulary). Holds the spec surfaces that state that
  matrix, and every destroy emit site in source, to it."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io PushbackReader]))

(def ^:private lifecycle-channel :rf.machine.lifecycle/destroyed)
(def ^:private fx-channel        :rf.machine/destroyed)
(def ^:private destroy-channels  #{lifecycle-channel fx-channel})

(def ^:private expected-lifecycle-reasons #{:parent-frame-destroyed})
(def ^:private expected-fx-reasons        #{:rf.machine/finished :explicit})

;; The one dynamic `:reason` an fx emit site may carry: the `reason` parameter
;; the fx terminal (`emit-destroyed!`) and its forwarder (`destroy-resolved!`)
;; pass through.
(def ^:private forwarding-reason-sym 'reason)

;; ---- the spec surfaces (test CWD is implementation/machines) ---------------

(defn- spec-file [rel] (io/file "../../spec" rel))

(def ^:private spec-009-file     (spec-file "009-Instrumentation.md"))
(def ^:private spec-schemas-file (spec-file "Spec-Schemas.md"))
(def ^:private spec-005-file     (spec-file "005-StateMachines.md"))
(def ^:private cross-spec-file   (spec-file "Cross-Spec-Interactions.md"))

(defn- backticked-keywords [s]
  (map (fn [[_ k]] (keyword (subs k 1))) (re-seq #"`(:[\w./-]+)`" s)))

(defn- find-line [file pred]
  (->> (slurp file) str/split-lines (filter pred) first))

(defn- parse-009-matrix
  "The `| `:reason` | `:channel` | … |` rows of Spec 009's canonical matrix
  table, as `[{:reason kw :channel kw} …]`."
  []
  (->> (slurp spec-009-file)
       str/split-lines
       (drop-while #(not (str/includes? % "the canonical channel/reason matrix")))
       (take-while #(not (str/includes? % "The enum is open")))
       (keep (fn [line]
               (when-let [[_ reason channel] (re-find #"^\s*\|\s*`(:[\w./-]+)`\s*\|\s*`(:rf\.machine[\w./-]*)`\s*\|([^|]*)\|" line)]
                 {:reason  (keyword (subs reason 1))
                  :channel (keyword (subs channel 1))})))))

(defn- spec-schemas-fx-reasons
  "The `carries `:reason` — one of … —` span of Spec-Schemas' `:machine` row."
  []
  (let [row (find-line spec-schemas-file #(str/starts-with? % "| `:machine` |"))]
    (set (some->> row (re-find #"carries `:reason` — one of (.*?) —") second backticked-keywords))))

(defn- spec-schemas-lifecycle-reasons
  "Spec-Schemas' `:rf.machine.lifecycle/destroyed` row: every `:reason` its
  `:tags` map binds, and every keyword its \"`:reason` is always …\" sentence
  names."
  []
  (let [row       (find-line spec-schemas-file #(str/starts-with? % "| `:rf.machine.lifecycle/destroyed` |"))
        tags-body (some->> row (re-find #"`:tags \{([^}]*)\}`") second)]
    [(mapv (comp keyword #(subs % 1) second) (re-seq #":reason (:[\w./-]+)" (or tags-body "")))
     (some->> row (re-find #"`:reason` is always (.*?) [—–-] ") second backticked-keywords vec)]))

(defn- spec-005-d6-reasons
  "The `:reason` vocabulary of Spec 005's D6 row. The span ends at the
  backtick-then-period closing the sentence: a bare `.` stop would cut inside
  `:rf.machine/finished`."
  []
  (let [row (find-line spec-005-file #(str/starts-with? % "| D6 |"))]
    (set (some->> row (re-find #"`:reason` tag — one of (.*?`)\. ") second backticked-keywords))))

(defn- cross-spec-route-teardown-tuple
  "The `[channel reason]` Cross-Spec-Interactions documents for a route-change
  / view-unmount teardown."
  []
  (when-let [[_ ch reason] (re-find #"emits the fx-substrate `(:rf\.machine[\w./-]*)` with `:reason (:[\w./-]+)`"
                                    (slurp cross-spec-file))]
    [(keyword (subs ch 1)) (keyword (subs reason 1))]))

(deftest spec-surfaces-agree-on-the-channel-reason-matrix
  (is (= {lifecycle-channel expected-lifecycle-reasons fx-channel expected-fx-reasons}
         (update-vals (group-by :channel (parse-009-matrix)) #(set (map :reason %))))
      "Spec 009's matrix: one channel per reason, and only the two teardown channels")
  (is (= expected-fx-reasons (spec-schemas-fx-reasons)) "Spec-Schemas' `:machine` row")
  (is (= [[:parent-frame-destroyed] [:parent-frame-destroyed]] (spec-schemas-lifecycle-reasons))
      "Spec-Schemas' lifecycle row names the sole reason and no alternative")
  (is (= expected-fx-reasons (spec-005-d6-reasons)) "Spec 005 D6")
  (is (= [fx-channel :explicit] (cross-spec-route-teardown-tuple))
      "Cross-Spec-Interactions' route-change teardown"))

;; ---- the emit sites ---------------------------------------------------------
;;
;; Every destroy emit choke point is found by READING the source forms, both
;; `#?(:clj …)` and `#?(:cljs …)` branches:
;;   - `(trace/emit! <op-type> <destroy-channel> <arg>)`;
;;   - `(emit-destroyed! <arg-map>)`, the fx reason origination;
;;   - `(destroy-resolved! _ _ <reason> …)`, the fx reason forwarder.
;; A reason argument whose shape cannot be enumerated fails closed.

(defn- source-files []
  (->> [(io/file "src") (io/file "../core/src")]
       (mapcat file-seq)
       (filter #(and (.isFile %) (re-find #"\.clj[cs]?$" (.getName %))))))

(defn- reader-ns-sym [file]
  (with-open [r (PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (let [form (read {:read-cond :allow :eof ::eof} r)]
        (when (and (seq? form) (= 'ns (first form))) (second form))))))

(defn- expand-reader-conditionals
  "Replace every preserved reader conditional in `form` with a list of ALL its
  branch bodies, so one walk sees both host views."
  [form]
  (cond
    (reader-conditional? form)
    (->> (:form form) (partition 2) (map (comp expand-reader-conditionals second)) (apply list))
    (map? form)    (into (empty form)
                         (map (fn [[k v]] [(expand-reader-conditionals k) (expand-reader-conditionals v)]))
                         form)
    (seq? form)    (apply list (map expand-reader-conditionals form))
    (vector? form) (mapv expand-reader-conditionals form)
    (set? form)    (into (empty form) (map expand-reader-conditionals) form)
    :else form))

(defn- read-source-forms
  "Every top-level form of `file`, read with `*ns*` bound to the file's loaded
  namespace (so `::` keywords resolve) and unknown tags read as their value. A
  read error propagates rather than truncating the scan."
  [file]
  (with-open [r (PushbackReader. (io/reader file))]
    (binding [*read-eval*              false
              *ns*                     (or (find-ns (reader-ns-sym file))
                                           (create-ns (gensym "rf-destroy-scan")))
              *default-data-reader-fn* (fn [_tag value] value)]
      (->> (repeatedly #(read {:read-cond :preserve :eof ::eof} r))
           (take-while #(not= ::eof %))
           (mapv expand-reader-conditionals)))))

(defn- call-name [form]
  (when (and (seq? form) (symbol? (first form))) (name (first form))))

(defn- assoc-step-reason
  "For one `cond->` step: `[::ok <literal :reason values it assocs>]` when it
  is an `assoc`/`assoc!` with literal keyword keys, else `[::unproven]`."
  [step]
  (let [kvs (when (contains? #{"assoc" "assoc!"} (call-name step)) (rest step))]
    (if (and kvs (even? (count kvs)) (every? keyword? (take-nth 2 kvs)))
      [::ok (keep (fn [[k v]] (when (= k :reason) v)) (partition 2 kvs))]
      [::unproven])))

(defn- resolve-reason-arg
  "`{:proven? bool :reasons […]}` for the reason-bearing argument of an emit:
  a map literal, or a `cond->` over one whose steps are all `assoc-step-reason`
  safe, is enumerable; anything else (a local, a `merge`, a computed value) is
  not."
  [arg]
  (cond
    (map? arg)
    {:proven? true :reasons (if (contains? arg :reason) [(:reason arg)] [])}

    (contains? #{"cond->" "cond->>"} (call-name arg))
    (let [base       (resolve-reason-arg (second arg))
          step-verds (map (comp assoc-step-reason second) (partition 2 (drop 2 arg)))]
      (if (and (:proven? base) (every? #(= ::ok (first %)) step-verds))
        {:proven? true :reasons (into (vec (:reasons base)) (mapcat second step-verds))}
        {:proven? false :reasons []}))

    :else {:proven? false :reasons []}))

(defn- or-default-reasons
  "Every `:or {reason <v>}` destructuring default in `forms`."
  [forms]
  (set (for [form forms
             sf   (tree-seq coll? seq form)
             :when (and (map? sf) (contains? sf 'reason))]
         (get sf 'reason))))

(defn- destroy-emit-files
  "Source files carrying a destroy emit choke point (a text pre-filter, so a
  new emit site in a new file is picked up)."
  []
  (filter (fn [f]
            (let [s (slurp f)]
              (or (str/includes? s "emit-destroyed!")
                  (str/includes? s "destroy-resolved!")
                  (and (str/includes? s "trace/emit!")
                       (or (str/includes? s (str fx-channel))
                           (str/includes? s (str lifecycle-channel)))))))
          (source-files)))

(defn- site-findings [fname sf]
  (let [v (vec sf)]
    (case (call-name sf)
      "emit!"
      (let [ch (nth v 2 nil)]
        (when (contains? destroy-channels ch)
          [(assoc (resolve-reason-arg (nth v 3 nil)) :kind :channel-emit :file fname :form sf :channel ch)]))

      "emit-destroyed!"
      [(assoc (resolve-reason-arg (nth v 1 nil)) :kind :emit-destroyed :file fname :form sf)]

      "destroy-resolved!"
      [{:kind :destroy-resolved :file fname :form sf :reason (nth v 3 ::missing)}]

      nil)))

(defn- enumerate-emit-sites [files]
  (vec (for [f       files
             form    (read-source-forms f)
             sf      (tree-seq coll? seq form)
             :when   (seq? sf)
             finding (site-findings (.getName f) sf)]
         finding)))

(defn- valid-fx-reason? [r]
  (or (= r forwarding-reason-sym) (contains? expected-fx-reasons r)))

(defn- emit-site-violations
  "One message per emit site whose reason is unprovable, or is not a
  documented literal for its channel (or the forwarding `reason`)."
  [findings]
  (vec (for [{:keys [kind channel reason proven? reasons file form]} findings
             :let [at (str " at " file " :: " (pr-str form))]
             msg (case kind
                   (:channel-emit :emit-destroyed)
                   (if-not proven?
                     [(str "reason argument is not structurally provable" at)]
                     (for [r     reasons
                           :when (not (if (= channel lifecycle-channel)
                                        (contains? expected-lifecycle-reasons r)
                                        (valid-fx-reason? r)))]
                       (str "undocumented reason " (pr-str r) at)))

                   :destroy-resolved
                   (when-not (contains? expected-fx-reasons reason)
                     [(str "destroy-resolved! needs a documented fx literal; saw " (pr-str reason) at)]))]
         msg)))

(deftest emit-sites-pin-exact-channel-reason-tuples
  (let [files    (destroy-emit-files)
        findings (enumerate-emit-sites files)
        of-kind  (fn [kind] (filter #(= kind (:kind %)) findings))]
    (is (empty? (emit-site-violations findings)) (str/join "\n" (emit-site-violations findings)))
    ;; Documented == emitted, in both directions: the fx vocabulary is the
    ;; literals that originate an fx reason (emit-destroyed! maps,
    ;; destroy-resolved! arguments, the terminal's `:or` default).
    (is (= [expected-lifecycle-reasons expected-fx-reasons]
           [(set (mapcat :reasons (filter #(= lifecycle-channel (:channel %)) (of-kind :channel-emit))))
            (set/union (set (filter keyword? (mapcat :reasons (of-kind :emit-destroyed))))
                       (set (filter keyword? (map :reason (of-kind :destroy-resolved))))
                       (or-default-reasons (mapcat read-source-forms files)))]))))
