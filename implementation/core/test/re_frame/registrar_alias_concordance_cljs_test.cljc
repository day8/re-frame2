(ns re-frame.registrar-alias-concordance-cljs-test
  "Pins the `re-frame.core` REGISTRAR CONCORDANCE (rf2-kuky.23).

  THE DEFECT THIS SUITE EXISTS FOR. `re-frame.core` declares its `reg-*`
  registration surfaces TWICE: once as macros, emitted from a single
  generator table (`re-frame.core-reg-macros/defreg-macro` /
  `defreg-event-macro`) in the facade's `#?(:clj …)` branch, and once as
  same-name CLJS value aliases (`(def reg-x owning/reg-x)`) in its
  `#?(:cljs …)` branch — Convention A, so a higher-order caller can write
  `(map rf/reg-sub …)` where a macro cannot ride. The alias half was a
  hand-written list and it DRIFTED: five registrars (`reg-flow`,
  `reg-mutation`, `reg-head`, `reg-error-projector`,
  `reg-http-interceptor`) had a macro and no alias, so inside one artefact
  `(map rf/reg-resource …)` compiled and `(map rf/reg-mutation …)` did not.
  Nothing in the corpus tripped on it, which is exactly why a hand list
  drifts unnoticed — hence a pin rather than a one-off repair.

  DERIVED, NOT RESTATED. Both halves are read out of `re_frame/core.cljc`
  itself — once under `:features #{:clj}` and once under `#{:cljs}`, so each
  reader-conditional branch collapses to the arm that host actually
  compiles. There is no third list here to go stale in its turn: adding a
  sixteenth `defreg-macro` row without its alias reds this suite, and so
  does deleting an alias.

  TWO HOSTS, TWO HALVES. The JVM deftest compares the two SOURCE tables
  (names and delegates). The CLJS deftest closes what a source read cannot
  see — that each alias is a live `fn?` in the consolidated `:node-test`
  bundle — by emitting the generator table's names as var references at
  CLJS compile time through [[live-registrar-aliases]]. A name with a macro
  row and no alias emits an undeclared var there and reads back `nil`,
  which is the red."
  #?(:cljs (:require-macros [re-frame.registrar-alias-concordance-cljs-test
                             :refer [live-registrar-aliases]]))
  (:require [clojure.test :refer [deftest is testing]]
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
     (testing "the reader found the facade's generator rows — a mis-parse would
               otherwise make every comparison below vacuously true"
       (let [macros (macro-table)]
         (is (contains? macros 'reg-event)
             "the `reg-event` row must be visible in the :clj view")
         (is (= 'rf.events/reg-event (get macros 'reg-event))
             "and carry its delegate")
         (is (contains? (alias-table) 'reg-event)
             "the `reg-event` alias must be visible in the :cljs view")
         (is (not (contains? (alias-table) 'reg-event-db))
             "the retired ^:no-doc throwing stubs are not aliases")))))

#?(:clj
   (deftest every-registrar-macro-has-a-same-name-cljs-alias
     (testing "Convention A (spec/Conventions.md §Convention A): the generator
               table and the CLJS value-alias block name the SAME registrars"
       (let [macros  (macro-table)
             aliases (alias-table)]
         (is (= (set (keys macros)) (set (keys aliases)))
             (str "re-frame.core registrar drift — macros without an alias: "
                  (sort (remove (set (keys aliases)) (keys macros)))
                  "; aliases without a macro: "
                  (sort (remove (set (keys macros)) (keys aliases)))))))))

#?(:clj
   (deftest alias-and-macro-delegate-to-the-same-fn
     (testing "an alias that points somewhere else would satisfy the name
               comparison above while silently registering through a different
               fn than the macro"
       (let [macros  (macro-table)
             aliases (alias-table)]
         (doseq [[nm delegate] macros
                 :when (contains? aliases nm)]
           (is (= delegate (get aliases nm))
               (str "re-frame.core/" nm ": the macro splices to " delegate
                    " but the CLJS alias defs " (get aliases nm))))))))

#?(:clj
   (deftest every-registrar-macro-is-self-required-for-cljs
     (testing "a registrar macro absent from the facade's own `:require-macros`
               `:refer` is unreachable in CALL position from CLJS, whatever the
               value alias does"
       (let [referred (self-required-macro-names)]
         (is (contains? referred 'reg-event)
             "control: the :refer list parsed")
         (is (empty? (remove referred (keys (macro-table))))
             (str "registrar macros missing from re-frame.core's self-"
                  ":require-macros :refer list: "
                  (sort (remove referred (keys (macro-table))))))))))

#?(:cljs
   (deftest every-registrar-macro-carries-a-live-cljs-fn-value
     (testing "each registrar name resolves to a plain fn in VALUE position in
               the consolidated node bundle — the half a source read cannot see"
       (let [live (live-registrar-aliases)]
         (is (contains? live 'reg-event)
             "control: the generator table was emitted into this build")
         (doseq [[nm v] live]
           (is (fn? v)
               (str "re-frame.core/" nm
                    " must be a plain fn on CLJS (Convention A same-name value"
                    " alias); got " (pr-str v))))))))
