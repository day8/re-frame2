(ns re-frame.registrar-alias-concordance-cljs-test
  "Pins the `re-frame.core` registrar concordance. Each `reg-*` registrar is
  declared twice in the facade: as a macro from the generator table
  (`defreg-macro` / `defreg-event-macro`, `#?(:clj ...)`) and as a same-name
  CLJS value alias (`#?(:cljs ...)`, Convention A, so `(map rf/reg-sub ...)`
  compiles). The alias half is a hand list and drifts.

  Both halves are read from `re_frame/core.cljc` itself under each host's
  features, so nothing here is a third list. The CLJS deftest checks each
  generator name is a live fn in the node bundle (a missing alias reads back
  nil); the JVM deftests check the delegates agree and that CLJS can reach each
  macro in call position."
  #?(:cljs (:require-macros [re-frame.registrar-alias-concordance-cljs-test
                             :refer [live-registrar-aliases]]))
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core]
            #?(:clj [clojure.java.io :as io])))

;; ---- the two source tables (JVM read; also runs at CLJS compile time) -----

#?(:clj
   (def ^:private facade-source
     "The facade's own source, as a classpath resource. Read rather than
     required because the two declarations sit in DIFFERENT reader-conditional
     branches, so no single host can see both at runtime."
     "re_frame/core.cljc"))

#?(:clj
   (defn- read-facade-forms
     "Every top-level form of the facade source, read under `features`.
     `*read-eval*` is off: this reads source, it does not run it."
     [features]
     (let [url (io/resource facade-source)]
       (assert url (str "facade source not on the classpath: " facade-source))
       (with-open [rdr (java.io.PushbackReader. (io/reader url))]
         (binding [*read-eval* false]
           (loop [acc []]
             (let [form (read {:read-cond :allow :features features :eof ::eof} rdr)]
               (if (= form ::eof) acc (recur (conj acc form))))))))))

#?(:clj
   (defn- subforms
     "Every form nested anywhere inside `forms`. The declarations sit inside a
     `(do …)` that the reader conditional collapses to, so a top-level-only
     scan would find nothing at all."
     [forms]
     (mapcat #(tree-seq coll? seq %) forms)))

#?(:clj
   (def ^:private defreg-heads
     "The macro-defining macros that emit a splice-through registrar. Matched
     on the NAME so the facade's require-alias for the generator ns is not
     baked in here."
     '#{defreg-macro defreg-event-macro}))

#?(:clj
   (defn- macro-table
     "`{macro-sym delegate-sym}` for every generator row in the facade's JVM
     branch — the registrar family's single source of truth."
     []
     (into {}
           (comp (filter #(and (seq? %)
                               (symbol? (first %))
                               (contains? defreg-heads (symbol (name (first %))))))
                 (map (fn [form] [(nth form 1) (nth form 2)])))
           (subforms (read-facade-forms #{:clj})))))

#?(:clj
   (defn- alias-table
     "`{alias-sym delegate-sym}` for every same-name `reg-*` value alias in the
     facade's CLJS branch.

     `^:no-doc` defs are skipped. That drops the three retired EP-0018
     throwing stubs (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`), which
     are `reg-`-named `def` aliases but register nothing — and it is the same
     carve-out the API-manifest generator and the CLJS publics probe already
     use for them, rather than a name-list invented here."
     []
     (into {}
           (comp (filter #(and (seq? %)
                               (= 'def (first %))
                               (= 3 (count %))
                               (symbol? (second %))
                               (.startsWith (name (second %)) "reg-")
                               (not (:no-doc (meta (second %))))))
                 (map (fn [form] [(with-meta (nth form 1) nil) (nth form 2)])))
           (subforms (read-facade-forms #{:cljs})))))

#?(:clj
   (defn- self-required-macro-names
     "The names the facade's own `#?(:cljs (:require-macros …))` `:refer`s —
     the third place a registrar name must appear for CLJS callers to reach
     the macro in call position at all."
     []
     (->> (read-facade-forms #{:cljs})
          (filter #(and (seq? %) (= 'ns (first %))))
          subforms
          (filter #(and (seq? %) (= :require-macros (first %))))
          (mapcat rest)
          (mapcat (fn [spec] (when (vector? spec) (:refer (apply hash-map (rest spec))))))
          set)))

;; ---- CLJS half: the aliases are live fns in the node bundle ---------------

#?(:clj
   (defmacro live-registrar-aliases
     "Emit `{'<name> re-frame.core/<name>, …}` for every name in the generator
     table, so the CLJS assertion reads the LIVE value of each rather than a
     list written out here."
     []
     (into {}
           (map (fn [n] [(list 'quote n) (symbol "re-frame.core" (name n))]))
           (sort (keys (macro-table))))))

;; ---- assertions ----------------------------------------------------------

#?(:clj
   (deftest generator-table-parses
     ;; control: a mis-parse would make every comparison below vacuously true
     (is (= 'rf.events/reg-event (get (macro-table) 'reg-event)))
     (is (contains? (alias-table) 'reg-event))))

#?(:clj
   (deftest alias-and-macro-delegate-to-the-same-fn
     (let [macros  (macro-table)
           aliases (alias-table)]
       (is (= (select-keys macros (keys aliases))
              (select-keys aliases (keys macros)))
           "each CLJS alias defs the fn its macro splices to"))))

#?(:clj
   (deftest every-registrar-macro-is-self-required-for-cljs
     ;; a macro absent from the facade's own :require-macros :refer is
     ;; unreachable in call position from CLJS
     (let [referred (self-required-macro-names)]
       (is (contains? referred 'reg-event) "control: the :refer list parsed")
       (is (= [] (sort (remove referred (keys (macro-table)))))))))

#?(:cljs
   (deftest every-registrar-macro-carries-a-live-cljs-fn-value
     (let [live (live-registrar-aliases)]
       (is (contains? live 'reg-event) "control: the generator table was emitted")
       (is (= [] (sort (keep (fn [[nm v]] (when-not (fn? v) nm)) live)))
           "each registrar name is a plain fn in value position"))))
