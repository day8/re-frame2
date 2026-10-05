(ns re-frame.migration.reg-event-codemod-test
  "Migration tests for the EP-0018 Slice E reg-event scanner + codemod.

  The coverage matrix (one or more deftests per row):

    | v1 snippet                       | scanner finding   | codemod action        |
    |----------------------------------|-------------------|-----------------------|
    | simple reg-event-db              | :reg-event-db     | rewrite {:db BODY}    |
    | reg-event-db w/ path interceptor | :reg-event-db     | rewrite; chain LOWERED|
    |   (metadata / positional /       |                   |   to [:rf.interceptor |
    |    bare / metadata-plus-vector)  |                   |   /path [p...]] refs  |
    | any form w/ custom inline        | (that form)       | FLAG (:interceptors)  |
    |   interceptor (no derivable id)  |                   |   — unresolved M-70   |
    | reg-event w/ v1 chain survivor   | :reg-event        | rewrite (rescan) /    |
    |   (partially migrated tree)      |                   |   FLAG (:interceptors)|
    | reg-event-fx                     | :reg-event-fx     | rename only           |
    | reg-event-ctx                    | :reg-event-ctx    | FLAG (:ctx)           |
    | nil-capable -db body (when/if/   | :reg-event-db     | FLAG (:nil-capable)   |
    |   get/cond/and-or)               |                   |                       |
    | complex -db (var/multi-arity/    | :reg-event-db     | FLAG (:complex)       |
    |   destructured db param)         |                   |                       |

  Plus: shape-non-corruption (round-trips of untouched code), alias-agnostic
  registrar detection, bare-head binding (a renamed bare head's `reg-event`
  resolves through the emitted ns form, or the site flags `:binding`),
  path-head RESOLUTION (only a head resolving to
  re-frame.core/path lowers; custom `*/path` fns flag — rf2-8odvg reopen),
  scan-file/scan-paths over the filesystem, write-mode line-ending fidelity,
  and idempotence. The
  RUNTIME proof that the emitted chain shapes register against the real v2
  reg-event contract lives in the `:integration` alias
  (test-integration/, rf2-8odvg) so this default suite stays self-contained."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [re-frame.migration.reg-event-codemod :as rf.migration.reg-event-codemod]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- only-finding [s]
  (let [fs (rf.migration.reg-event-codemod/scan-string s)]
    (is (= 1 (count fs)) (str "expected exactly one finding in: " s))
    (first fs)))

(defn- rewrite [s] (:source (rf.migration.reg-event-codemod/rewrite-string s)))

;; ---------------------------------------------------------------------------
;; reg-event-fx — pure rename
;; ---------------------------------------------------------------------------

(deftest fx-renamed
  (testing "reg-event-fx is renamed to reg-event, body untouched"
    (let [src "(rf/reg-event-fx :todo/add\n  (fn [{:keys [db]} [_ text]]\n    {:db (assoc-in db [:todos text] true)}))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= 1 (count findings)))
      (is (= :reg-event-fx (:form (first findings))))
      (is (= :rename (:action (first findings))))
      (is (= :reg-event (:target (first findings))))
      (is (str/includes? source "rf/reg-event "))
      (is (not (str/includes? source "reg-event-fx")))
      ;; body byte-for-byte preserved apart from the head rename
      (is (str/includes? source "{:db (assoc-in db [:todos text] true)}")))))

(deftest fx-rename-preserves-metadata-slot
  (testing "reg-event-fx with a metadata middle slot keeps it verbatim"
    (let [src "(rf/reg-event-fx :todo/add {:rf.cofx/requires [:rf/time-ms]}\n  (fn [{:keys [db rf/time-ms]} [_ text]] {:db db}))"
          out (rewrite src)]
      (is (str/includes? out "{:rf.cofx/requires [:rf/time-ms]}"))
      (is (str/includes? out "rf/reg-event ")))))

;; ---------------------------------------------------------------------------
;; reg-event-db — simple faithful rewrite
;; ---------------------------------------------------------------------------

(deftest db-simple-update
  (testing "simple reg-event-db -> reg-event with {:db BODY} and {:keys [db]}"
    (let [src "(rf/reg-event-db :counter/inc\n  (fn [db _] (update db :count inc)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :reg-event-db (:form (first findings))))
      (is (= :rewrite (:action (first findings))))
      (is (str/includes? source "rf/reg-event "))
      (is (not (str/includes? source "reg-event-db")))
      ;; db arg destructured out of the coeffects map
      (is (str/includes? source "{:keys [db]}"))
      ;; body wrapped as the :db effect, inner form preserved verbatim
      (is (str/includes? source "{:db (update db :count inc)}")))))

(deftest db-thread-body
  (testing "a (-> db ...) thread body wraps faithfully (last stage is a safe builder)"
    (let [src "(rf/reg-event-db :form/clear\n  (fn [db [_ k]]\n    (-> db\n        (assoc-in [:form k :value] \"\")\n        (assoc-in [:form k :error] nil))))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))) "->-thread ending in assoc-in is a safe, non-nil builder")
      (is (str/includes? source "{:keys [db]}"))
      (is (str/includes? source "{:db (-> db"))
      ;; the inner thread stages survive intact
      (is (str/includes? source "(assoc-in [:form k :value] \"\")")))))

(deftest db-named-fn
  (testing "a named handler fn (fn the-name [db ev] ...) still rewrites"
    (let [src "(rf/reg-event-db :x/y (fn handle [db _] (assoc db :ok true)))"
          out (rewrite src)]
      (is (str/includes? out "(fn handle [{:keys [db]} _] {:db (assoc db :ok true)})")))))

;; ---------------------------------------------------------------------------
;; reg-event-db — path interceptor chains NORMALIZED (M-70 x M-73, rf2-8odvg)
;; ---------------------------------------------------------------------------
;; v2 chains are reference-only (EP-0022): `rf/path` is a throwing removal stub
;; (:rf.error/path-removed), inline values are rejected
;; (:rf.error/inline-interceptor-removed), and the positional vector middle
;; slot is rejected (:rf.error/reg-event-bad-middle-slot). The codemod lowers
;; the standard path constructor to the framework factory ref
;; `[:rf.interceptor/path [p...]]` in every mechanical source shape; the
;; runtime proof these emitted shapes actually REGISTER lives in the
;; `:integration` alias.

(deftest db-path-interceptor-normalized
  (testing "the canonical metadata path chain lowers to the standard factory ref"
    (let [src "(rf/reg-event-db :counter/inc\n  {:interceptors [(rf/path :counter)]}\n  (fn [db _] (update db :value inc)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      (is (str/includes? source "{:interceptors [[:rf.interceptor/path [:counter]]]}"))
      ;; no executable rf/path call survives (v2 makes it a throwing stub)
      (is (not (str/includes? source "(rf/path")))
      (is (str/includes? source "rf/reg-event "))
      (is (str/includes? source "{:keys [db]}"))
      (is (str/includes? source "{:db (update db :value inc)}")))))

(deftest db-positional-multi-entry-order-preserved
  (testing "multiple positional entries keep their declaration order"
    (let [src "(rf/reg-event-db :x [(rf/path :a) (rf/path :b)] (fn [db _] (assoc db :k 1)))"
          out (rewrite src)]
      (is (str/includes? out "{:interceptors [[:rf.interceptor/path [:a]] [:rf.interceptor/path [:b]]]}")))))

(deftest db-bare-path-call-middle-normalized
  (testing "a single bare (rf/path ...) middle slot (v1 flattened chains) wraps into metadata"
    (let [src "(rf/reg-event-db :x (rf/path :a) (fn [db _] (assoc db :k 1)))"
          out (rewrite src)]
      (is (str/includes? out "{:interceptors [[:rf.interceptor/path [:a]]]}"))
      (is (not (str/includes? out "(rf/path"))))))

(deftest db-metadata-plus-vector-merged
  (testing "the metadata-plus-vector shape merges into ONE metadata map, map entries first"
    (let [src "(rf/reg-event-db :x {:interceptors [(rf/path :a)]} [(rf/path :b)]\n  (fn [db _] (assoc db :k 1)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      (is (str/includes? source "{:interceptors [[:rf.interceptor/path [:a]] [:rf.interceptor/path [:b]]]}"))
      ;; exactly one :interceptors key survives — the positional vector is gone
      (is (= 1 (count (re-seq #":interceptors" source))))
      (is (str/includes? source "{:db (assoc db :k 1)}")))))

(deftest db-metadata-plus-vector-no-existing-chain
  (testing "a metadata map WITHOUT :interceptors gains the merged chain"
    (let [src "(rf/reg-event-db :x {:doc \"d\"} [(rf/path :b)] (fn [db _] (assoc db :k 1)))"
          out (rewrite src)]
      (is (str/includes? out "{:doc \"d\" :interceptors [[:rf.interceptor/path [:b]]]}")))))

(deftest path-arg-variants-lower-mechanically
  (testing "variadic, vector, and mixed path args flatten as v1 path did"
    (doseq [[middle expected]
            {"{:interceptors [(rf/path :a :b)]}"   "[[:rf.interceptor/path [:a :b]]]"
             "{:interceptors [(rf/path [:a :b])]}" "[[:rf.interceptor/path [:a :b]]]"
             "{:interceptors [(rf/path [:a] :b)]}" "[[:rf.interceptor/path [:a :b]]]"}]
      (let [src (str "(rf/reg-event-db :x " middle " (fn [db _] (assoc db :k 1)))")
            out (rewrite src)]
        (is (str/includes? out expected) (str "middle slot " middle))))))

(deftest fx-with-path-chain-normalized
  (testing "reg-event-fx with a convertible chain is a :rewrite (not a bare :rename)"
    (let [src "(rf/reg-event-fx :x {:interceptors [(rf/path :a)]}\n  (fn [cofx _] {:db (:db cofx)}))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      (is (str/includes? source "rf/reg-event "))
      (is (str/includes? source "{:interceptors [[:rf.interceptor/path [:a]]]}"))
      ;; handler byte-for-byte preserved (reg-event IS reg-event-fx)
      (is (str/includes? source "(fn [cofx _] {:db (:db cofx)})")))))

(deftest already-canonical-ref-chain-kept-verbatim
  (testing "a chain that is already refs-only is preserved byte-for-byte"
    (let [src "(rf/reg-event-db :x {:interceptors [:my/ic [:rf.interceptor/path [:cart]]]}\n  (fn [db _] (assoc db :k 1)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))) "handler rewrite still applies")
      (is (str/includes? source "{:interceptors [:my/ic [:rf.interceptor/path [:cart]]]}")))))

;; ---------------------------------------------------------------------------
;; custom inline interceptors — unresolved M-70 Type B (:flag :interceptors)
;; ---------------------------------------------------------------------------
;; An inline entry with no mechanically derivable registered id (a var, a
;; custom call, a dynamic path arg) makes the WHOLE site an unresolved M-70
;; finding and the source is left unchanged: a head-only rewrite would certify
;; output v2 rejects at namespace load.

(deftest custom-inline-interceptor-flagged-db
  (testing "a custom interceptor var in the chain -> :flag :interceptors, unchanged"
    (let [src "(rf/reg-event-db :x {:interceptors [my-auth-interceptor]}\n  (fn [db _] (assoc db :k 1)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :reg-event-db (:form (first findings))))
      (is (= :flag (:action (first findings))))
      (is (= :interceptors (:flag (first findings))))
      (is (str/includes? (:note (first findings)) "M-70"))
      (is (= src source) "flagged site left byte-for-byte unchanged"))))

(deftest custom-inline-interceptor-flagged-fx
  (testing "a positional chain with a custom call (e.g. (rf/debug)) is NOT pure-renamed"
    (let [src "(rf/reg-event-fx :x [(rf/debug)]\n  (fn [c _] {:db (:db c)}))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :flag (:action (first findings))))
      (is (= :interceptors (:flag (first findings))))
      (is (= src source)))))

(deftest path-dynamic-arg-flagged
  (testing "(rf/path p) with a non-literal arg has no derivable path vector -> flag"
    (let [src "(rf/reg-event-db :x {:interceptors [(rf/path p)]} (fn [db _] (assoc db :k 1)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :flag (:action (first findings))))
      (is (= :interceptors (:flag (first findings))))
      (is (= src source)))))

(deftest mixed-chain-with-one-unresolved-entry-flags-whole-site
  (testing "a chain mixing a convertible path with an underivable entry is NOT half-converted"
    (let [src "(rf/reg-event-db :x {:interceptors [(rf/path :a) my-ic]}\n  (fn [db _] (assoc db :k 1)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :flag (:action (first findings))))
      (is (= :interceptors (:flag (first findings))))
      (is (= src source)))))

(deftest db-nil-capable-with-convertible-chain-still-gates-on-d7
  (testing "a convertible chain does not bypass the D7 nil gate; source unchanged"
    (let [src "(rf/reg-event-db :x {:interceptors [(rf/path :a)]}\n  (fn [db _] (when true db)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :flag (:action (first findings))))
      (is (= :nil-capable (:flag (first findings))))
      (is (= src source)))))

;; ---------------------------------------------------------------------------
;; path-head resolution — only the STANDARD constructor is mechanical
;; (rf2-8odvg reopen)
;; ---------------------------------------------------------------------------
;; `(app.interceptors/path :tenant)` shares the simple name `path` with the
;; standard constructor while carrying entirely different author semantics.
;; The head must RESOLVE to `re-frame.core/path` — through the file's ns form
;; (the full namespace, an `:as` alias of it, or a bare `path` it `:refer`s),
;; or, in an ns-less fragment, by the conventional bare/dotless-alias reading.
;; Any other function named `path` is a custom inline interceptor: unresolved
;; M-70 Type B, source unchanged — never a silent rewrite.

(deftest non-standard-path-heads-flagged
  (testing "a head named `path` that does not resolve to re-frame.core/path flags, source unchanged"
    (doseq [[label src]
            [["custom qualified namespace"
              (str "(ns app.events\n"
                   "  (:require [re-frame.core :as rf]\n"
                   "            [app.interceptors]))\n"
                   "\n"
                   "(rf/reg-event-db :tenant/load\n"
                   "  {:interceptors [(app.interceptors/path :tenant)]}\n"
                   "  (fn [db _] (assoc db :loaded true)))\n")]
             ["alias of a custom namespace"
              (str "(ns app.events\n"
                   "  (:require [re-frame.core :as rf]\n"
                   "            [app.interceptors :as icpt]))\n"
                   "(rf/reg-event-db :x {:interceptors [(icpt/path :tenant)]}\n"
                   "  (fn [db _] (assoc db :k 1)))\n")]
             ["dotted custom namespace in an ns-less fragment"
              "(rf/reg-event-db :x {:interceptors [(app.interceptors/path :tenant)]} (fn [db _] (assoc db :k 1)))"]
             ["alias the ns form does not resolve"
              (str "(ns app.events (:require [re-frame.core :as rf]))\n"
                   "(rf/reg-event-db :x {:interceptors [(xyz/path :a)]} (fn [db _] (assoc db :k 1)))\n")]
             ["bare path the ns form does not refer"
              (str "(ns app.events (:require [re-frame.core :as rf]))\n"
                   "(rf/reg-event-db :x {:interceptors [(path :a)]} (fn [db _] (assoc db :k 1)))\n")]]]
      (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= [:flag :interceptors] ((juxt :action :flag) (first findings)))
            (str label " must flag"))
        (is (= src source)
            (str label " left byte-for-byte unchanged, never given the standard factory ref"))))))

(deftest standard-path-heads-lowered
  (testing "a head that resolves to re-frame.core/path lowers, through the ns form or without one"
    (doseq [[label src ref]
            [["rf alias resolved through the ns form"
              (str "(ns app.events (:require [re-frame.core :as rf]))\n"
                   "(rf/reg-event-db :counter/inc\n"
                   "  {:interceptors [(rf/path :counter)]}\n"
                   "  (fn [db _] (update db :value inc)))\n")
              "{:interceptors [[:rf.interceptor/path [:counter]]]}"]
             ["bare path in :refer"
              (str "(ns app.events (:require [re-frame.core :refer [reg-event-db path]]))\n"
                   "(reg-event-db :counter/inc\n"
                   "  {:interceptors [(path :counter)]}\n"
                   "  (fn [db _] (update db :value inc)))\n")
              "{:interceptors [[:rf.interceptor/path [:counter]]]}"]
             ["bare path under :refer :all"
              (str "(ns app.events (:require [re-frame.core :refer :all]))\n"
                   "(reg-event-db :counter/inc\n"
                   "  {:interceptors [(path :counter)]}\n"
                   "  (fn [db _] (update db :value inc)))\n")
              "{:interceptors [[:rf.interceptor/path [:counter]]]}"]
             ["fully qualified, with an ns form"
              (str "(ns app.events (:require [re-frame.core]))\n"
                   "(re-frame.core/reg-event-db :x {:interceptors [(re-frame.core/path :a)]} (fn [db _] (assoc db :k 1)))\n")
              "[:rf.interceptor/path [:a]]"]
             ["fully qualified, in an ns-less fragment"
              "(re-frame.core/reg-event-db :x {:interceptors [(re-frame.core/path :a)]} (fn [db _] (assoc db :k 1)))"
              "[:rf.interceptor/path [:a]]"]]]
      (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= :rewrite (:action (first findings))) (str label " must rewrite"))
        (is (str/includes? source ref) (str label " must lower to the standard factory ref"))))))

;; ---------------------------------------------------------------------------
;; path-head resolution — LEXICAL SHADOWING at the call site (rf2-8odvg reopen)
;; ---------------------------------------------------------------------------
;; What the ns form makes AVAILABLE is not what a bare head DENOTES. A file may
;; refer `re-frame.core/path` and still rebind the name around a registration,
;; in which case `(path :tenant)` is the local — lowering it to
;; [:rf.interceptor/path [:tenant]] would swap the author's semantics silently.
;; A qualified head cannot be shadowed (locals are simple symbols), so the
;; check applies to bare heads only.

(deftest shadowed-bare-path-flagged-across-binding-forms
  (testing "an enclosing form that binds `path` makes a bare `(path ...)` the local: flag, source unchanged"
    (doseq [[label open close]
            [;; the binding vocabulary
             ["let"     "(let [path app.interceptors/path]"            ")"]
             ["if-let"  "(if-let [path (resolve-path)]"                " nil)"]
             ["fn"      "((fn [path]"                                  ") app.interceptors/path)"]
             ["defn"    "(defn install! [path]"                        ")"]
             ["letfn"   "(letfn [(path [k] [k])]"                      ")"]
             ["doseq"   "(doseq [path [:a :b]]"                        ")"]
             ;; a qualified spelling binds exactly as the simple one does, and
             ;; the vocabulary is keyed by simple names, so it must still count
             ["clojure.core/let" "(clojure.core/let [path app.interceptors/path]" ")"]
             ["cljs.core/let"    "(cljs.core/let [path app.interceptors/path]"    ")"]
             ["aliased core/let" "(c/let [path app.interceptors/path]"            ")"]
             ["clojure.core/fn"  "((clojure.core/fn [path]"                       ") app.interceptors/path)"]
             ;; a head outside the vocabulary whose vector child binds the name
             ;; is a binder whatever its head, which can only ever produce a flag
             ["defmethod"     "(defmethod install! :web [_ path]"       ")"]
             ["when-first"    "(when-first [path paths]"                ")"]
             ["dotimes"       "(dotimes [path 3]"                       ")"]
             ["project macro" "(app.macros/with-scope [path :tenant]"   ")"]]]
      (let [src (str "(ns app.events\n"
                     "  (:require [re-frame.core :refer [reg-event-db path]]))\n"
                     open "\n"
                     "  (reg-event-db :x\n"
                     "    {:interceptors [(path :tenant)]}\n"
                     "    (fn [db _] (assoc db :k 1)))" close "\n")
            {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= [:flag :interceptors] ((juxt :action :flag) (first findings)))
            (str label " must flag, not lower"))
        (is (= src source) (str label " left byte-for-byte unchanged"))))))

(deftest unrecognised-head-without-a-path-binding-still-lowers
  (testing "the catch-all is narrow: an enclosing form that does NOT bind `path` is inert"
    ;; Non-vacuity for the branch above — `deftest`/`testing`/`comment` and a
    ;; `doseq` binding some OTHER name must leave the standard site mechanical.
    (doseq [[label open close]
            [["deftest"  "(deftest registers (testing \"x\""       "))"]
             ["comment"  "(comment"                                ")"]
             ["defmethod other param" "(defmethod install! :web [_ opts]" ")"]
             ["doseq other name"      "(doseq [k [:a :b]]"          ")"]]]
      (let [src (str "(ns app.events\n"
                     "  (:require [re-frame.core :refer [reg-event-db path]]))\n"
                     open "\n"
                     "  (reg-event-db :x\n"
                     "    {:interceptors [(path :counter)]}\n"
                     "    (fn [db _] (assoc db :k 1)))" close "\n")
            {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= :rewrite (:action (first findings))) (str label " must still rewrite"))
        (is (str/includes? source "[[:rf.interceptor/path [:counter]]]")
            (str label " must still lower the standard head"))))))

(deftest shadowing-does-not-suppress-a-qualified-head
  (testing "a local named `path` cannot shadow `rf/path`, under a plain or a qualified binder"
    (doseq [binder ["let" "clojure.core/let"]]
      (let [src (str "(ns app.events (:require [re-frame.core :as rf]))\n"
                     "(" binder " [path app.interceptors/path]\n"
                     "  (rf/reg-event-db :counter/inc\n"
                     "    {:interceptors [(rf/path :counter)]}\n"
                     "    (fn [db _] (update db :value inc))))\n")
            {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= :rewrite (:action (first findings))) (str binder " must still rewrite"))
        (is (str/includes? source "{:interceptors [[:rf.interceptor/path [:counter]]]}")
            (str binder " must still lower the standard head"))))))

(deftest shadowing-elsewhere-does-not-suppress-a-referred-bare-path
  (testing "a `path` binding in a SIBLING form leaves this site's bare head standard"
    (let [src (str "(ns app.events\n"
                   "  (:require [re-frame.core :refer [reg-event-db path]]))\n"
                   "(defn helper [path] (str path))\n"
                   "(reg-event-db :counter/inc\n"
                   "  {:interceptors [(path :counter)]}\n"
                   "  (fn [db _] (update db :value inc)))\n")
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      (is (str/includes? source "{:interceptors [[:rf.interceptor/path [:counter]]]}")))))

;; ---------------------------------------------------------------------------
;; reg-event rescan — recovering a partially migrated tree
;; ---------------------------------------------------------------------------
;; The pre-rf2-8odvg codemod renamed heads while preserving v1 chains, leaving
;; `reg-event` forms v2 rejects. A re-run must find and repair those survivors
;; — and must NOT report anything for valid v2 registrations.

(deftest reg-event-rescan-recovers-metadata-inline
  (testing "an already-renamed reg-event with a preserved (rf/path ...) chain is repaired"
    (let [src "(rf/reg-event :counter/inc\n  {:interceptors [(rf/path :counter)]}\n  (fn [{:keys [db]} _] {:db (update db :value inc)}))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= 1 (count findings)))
      (is (= :reg-event (:form (first findings))))
      (is (= :rewrite (:action (first findings))))
      (is (str/includes? source "{:interceptors [[:rf.interceptor/path [:counter]]]}"))
      ;; the handler is untouched — only the chain is normalized
      (is (str/includes? source "(fn [{:keys [db]} _] {:db (update db :value inc)})")))))

(deftest reg-event-rescan-flags-custom-inline
  (testing "an already-renamed reg-event with an underivable inline entry is flagged"
    (let [src "(rf/reg-event :x {:interceptors [my-ic]} (fn [{:keys [db]} _] {:db db}))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :reg-event (:form (first findings))))
      (is (= :flag (:action (first findings))))
      (is (= :interceptors (:flag (first findings))))
      (is (= src source)))))

(deftest valid-reg-event-produces-no-finding
  (testing "valid v2 registrations are not reported by the rescan"
    (let [src (str "(rf/reg-event :a (fn [{:keys [db]} _] {:db db}))\n"
                   "(rf/reg-event :b {:interceptors [:my/ic]} (fn [{:keys [db]} _] {:db db}))\n"
                   "(rf/reg-event :c {:interceptors [[:rf.interceptor/path [:x]]]} (fn [{:keys [db]} _] {:db db}))\n"
                   "(rf/reg-event :d {:doc \"plain metadata\"} (fn [{:keys [db]} _] {:db db}))\n")
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (empty? findings))
      (is (= src source)))))

;; ---------------------------------------------------------------------------
;; reg-event-db — renamed (non-`db`) first param  (rf2-xhfxcs.15)
;; ---------------------------------------------------------------------------
;; A reg-event-db whose handler binds the db value under a name OTHER than `db`
;; (a path-scoped slice such as `c`) must keep every body reference resolved.
;; Rebinding the param to `{:keys [db]}` while the body still says `c` produced
;; an unresolvable-symbol compile error. The faithful transform rebinds the param
;; `{c :db}` — db value back under its original name — and leaves the body intact.

(deftest db-renamed-param-no-interceptor
  (testing "renamed first param with no middle slot also binds {state :db}"
    (let [src "(rf/reg-event-db :s/set (fn [state [_ v]] (assoc state :v v)))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{state :db} [_ v]] {:db (assoc state :v v)})"))
      (is (not (str/includes? out "{:keys [db]}"))))))

(deftest db-ignored-param-keeps-keys-form
  (testing "an ignored `_` first param keeps the canonical {:keys [db]} (nothing to rebind)"
    (let [src "(rf/reg-event-db :init (fn [_ _] {:count 0 :items []}))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{:keys [db]} _] {:db {:count 0 :items []}})")))))

;; ---------------------------------------------------------------------------
;; reg-event-db — `_`-prefixed first param that IS referenced  (rf2-u6m0o9)
;; ---------------------------------------------------------------------------
;; `_`-prefix is a CONVENTION ("I intend not to use this"), not a guarantee. A
;; handler may legally read an `_`-prefixed binding. Rebinding such a referenced
;; param to `{:keys [db]}` orphaned every body reference to it (unbound-symbol
;; compile error) — the rf2-xhfxcs.15 `{S :db}` bind-back covered non-`_` names
;; but explicitly NOT `_`-prefixed ones. A referenced `_`-name must bind back
;; under its original name `{_state :db}`; a TRULY-unreferenced one keeps the
;; canonical `{:keys [db]}` (nothing to rebind). The reference test respects
;; inner shadowing.

(deftest db-underscore-referenced-param-binds-back
  (testing "the bead/H repro: `_state` referenced in the body rebinds {_state :db}, body intact"
    (let [src "(reg-event-db :y (fn [_state ev] (assoc _state :x 1)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :reg-event-db (:form (first findings))))
      (is (= :rewrite (:action (first findings))) "referenced `_`-param is still a faithful rewrite")
      (is (str/includes? source "(reg-event "))
      (is (not (str/includes? source "reg-event-db")))
      (is (not (str/includes? source "{:keys [db]}")))
      ;; the param binds the db value back under `_state`, and the body keeps
      ;; reading `_state`, now the db coeffect rather than an unbound name
      (is (str/includes? source "(fn [{_state :db} ev] {:db (assoc _state :x 1)})")))))

(deftest db-underscore-unreferenced-param-keeps-keys-form
  (testing "an `_`-prefixed param NOT read in the body keeps {:keys [db]} (nothing to rebind)"
    (let [src "(rf/reg-event-db :init (fn [_state _] {:count 0 :items []}))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{:keys [db]} _] {:db {:count 0 :items []}})"))
      (is (not (str/includes? out "{_state :db}"))))))

(deftest db-underscore-db-param-referenced-binds-back
  (testing "`_db` referenced in the body binds back {_db :db} (the other classic ignore-name)"
    (let [src "(rf/reg-event-db :touch (fn [_db _] (assoc _db :touched true)))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{_db :db} _] {:db (assoc _db :touched true)})"))
      (is (not (str/includes? out "{:keys [db]}"))))))

(deftest db-underscore-referenced-multiform-body
  (testing "referenced `_`-param with a multi-form body: only LAST form wrapped, refs intact"
    (let [src "(rf/reg-event-db :log/it\n  (fn [_state _]\n    (js/console.log \"hi\")\n    (assoc _state :logged true)))"
          out (rewrite src)]
      (is (str/includes? out "{_state :db}"))
      (is (str/includes? out "(js/console.log \"hi\")"))
      (is (str/includes? out "{:db (assoc _state :logged true)}")))))

(deftest db-underscore-shadowed-in-let-not-over-rewritten
  (testing "an inner `let` that REBINDS the `_`-name shadows it: the OUTER name is unreferenced -> {:keys [db]}"
    ;; The body never reads the outer `_state` — every `_state` occurrence is the
    ;; inner let-binding. So the outer param is genuinely unused; {:keys [db]} is
    ;; correct, and the inner shadowing binding survives byte-for-byte.
    (let [src "(rf/reg-event-db :shadow\n  (fn [_state [_ k]]\n    (let [_state {:fresh k}]\n      (assoc _state :touched true))))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      ;; outer param: no free reference -> canonical {:keys [db]}
      (is (str/includes? source "(fn [{:keys [db]} [_ k]]"))
      (is (not (str/includes? source "{_state :db}")))
      ;; inner let + both inner references survive verbatim
      (is (str/includes? source "(let [_state {:fresh k}]"))
      (is (str/includes? source "(assoc _state :touched true)")))))

(deftest db-underscore-shadowed-in-fn-still-binds-back-when-also-free
  (testing "an inner (fn [_s] ...) shadows _s locally, but an OUTER free ref still forces bind-back"
    ;; `_s` is read once at the top of the body (free) and also rebound inside an
    ;; inner fn (shadowed). The free outer occurrence means the param IS
    ;; referenced -> {_s :db}; the inner shadowing fn is preserved untouched.
    (let [src "(rf/reg-event-db :map-it\n  (fn [_s _]\n    (assoc _s :xs (map (fn [_s] (inc _s)) (:xs _s)))))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{_s :db} _]"))
      ;; inner fn rebinding `_s` preserved verbatim — not over-rewritten
      (is (str/includes? out "(fn [_s] (inc _s))")))))

(deftest db-underscore-only-shadowed-in-fn-is-unreferenced
  (testing "when EVERY `_s` occurrence is inside an inner (fn [_s] ...), the outer param is unused -> {:keys [db]}"
    ;; last form headed by `assoc` (non-nil-capable) so the D7 gate lets the
    ;; rewrite proceed; the only `_s` uses are inside the inner mapped fn.
    (let [src "(rf/reg-event-db :init\n  (fn [_s _]\n    (assoc {} :xs (mapv (fn [_s] (inc _s)) [1 2 3]))))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{:keys [db]} _]"))
      (is (not (str/includes? out "{_s :db}")))
      ;; inner fn preserved
      (is (str/includes? out "(fn [_s] (inc _s))")))))

;; ---------------------------------------------------------------------------
;; reg-event-ctx — always flagged, never rewritten
;; ---------------------------------------------------------------------------

(deftest ctx-flagged-never-rewritten
  (testing "reg-event-ctx is flagged for manual review and left unchanged"
    (let [src "(rf/reg-event-ctx :advanced/thing\n  (fn [ctx] (assoc ctx :rf/skip-handler? true)))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :reg-event-ctx (:form (first findings))))
      (is (= :flag (:action (first findings))))
      (is (= :ctx (:flag (first findings))))
      ;; source is unchanged — the flag is advisory, not a rewrite
      (is (= src source)))))

;; ---------------------------------------------------------------------------
;; D7 — nil-capable bodies are flagged, not rewritten
;; ---------------------------------------------------------------------------

(deftest nil-capable-when
  (testing "a (when ...) body can yield nil -> FLAG :nil-capable, source unchanged"
    (let [src "(rf/reg-event-db :maybe/set\n  (fn [db [_ v]] (when v (assoc db :v v))))"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :reg-event-db (:form (first findings))))
      (is (= :flag (:action (first findings))))
      (is (= :nil-capable (:flag (first findings))))
      (is (= :reg-event (:target (first findings))) "target still suggested")
      (is (= src source)))))

(deftest nil-capable-bodies
  (testing "each body shape that can evaluate to nil -> FLAG :nil-capable"
    (doseq [[label src]
            [["if without else" "(rf/reg-event-db :cond/set\n  (fn [db [_ ok?]] (if ok? (assoc db :ok true))))"]
             ["get"             "(rf/reg-event-db :grab (fn [db [_ k]] (get db k)))"]
             ["cond"            "(rf/reg-event-db :route\n  (fn [db [_ x]] (cond (= x 1) (assoc db :a 1) (= x 2) (assoc db :b 2))))"]
             ["and"             "(rf/reg-event-db :a (fn [db _] (and (:ready? db) (assoc db :go true))))"]
             ["or"              "(rf/reg-event-db :o (fn [db _] (or (:cached db) (assoc db :fresh true))))"]
             ["literal nil"     "(rf/reg-event-db :noop (fn [db _] nil))"]
             ["some-> thread"   "(rf/reg-event-db :s (fn [db [_ k]] (some-> db (get k) inc)))"]]]
      (is (= :nil-capable (:flag (only-finding src))) label))))

;; ---------------------------------------------------------------------------
;; non-nil-capable bodies are NOT flagged (the rewrite proceeds)
;; ---------------------------------------------------------------------------

(deftest not-nil-capable-db-builders
  (testing "assoc / assoc-in / update / merge / dissoc bodies are non-nil -> rewrite"
    (doseq [body ["(assoc db :x 1)" "(assoc-in db [:a :b] 1)" "(update db :n inc)"
                  "(merge db {:x 1})" "(dissoc db :x)"]]
      (let [src (str "(rf/reg-event-db :id (fn [db _] " body "))")
            f   (only-finding src)]
        (is (= :rewrite (:action f)) (str "expected rewrite for body " body))))))

;; ---------------------------------------------------------------------------
;; complex reg-event-db — flagged for manual review
;; ---------------------------------------------------------------------------

(deftest complex-var-handler
  (testing "a var/symbol handler (not a literal fn) -> FLAG :complex, unchanged"
    (let [src "(rf/reg-event-db :x/y my-handler-fn)"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :flag (:action (first findings))))
      (is (= :complex (:flag (first findings))))
      (is (= src source)))))

(deftest complex-destructured-db-param
  (testing "a handler whose first param is itself destructured -> FLAG :complex"
    (let [src "(rf/reg-event-db :x/y (fn [{:keys [a b]} _] (assoc {} :a a)))"
          f (only-finding src)]
      (is (= :complex (:flag f))))))

(deftest complex-multi-arity-handler
  (testing "a multi-arity fn handler -> FLAG :complex (not the simple single-arity shape)"
    (let [src "(rf/reg-event-db :x/y (fn ([db] (assoc db :one true)) ([db _] (assoc db :two true))))"
          f (only-finding src)]
      (is (= :complex (:flag f))))))

;; ---------------------------------------------------------------------------
;; alias-agnostic detection
;; ---------------------------------------------------------------------------

(deftest alias-agnostic
  (testing "detection works regardless of the namespace alias / fully-qualified ns"
    (doseq [head ["rf/reg-event-db" "re-frame.core/reg-event-db" "reg-event-db" "rf2/reg-event-db"]]
      (let [src (str "(" head " :id (fn [db _] (assoc db :x 1)))")
            {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= 1 (count findings)) (str "one finding for head " head))
        (is (= :rewrite (:action (first findings))))
        ;; the alias/ns is preserved on the renamed symbol
        (let [ns* (when (str/includes? head "/") (subs head 0 (str/index-of head "/")))]
          (if ns*
            (is (str/includes? source (str ns* "/reg-event ")))
            (is (str/includes? source "(reg-event "))))))))

;; ---------------------------------------------------------------------------
;; bare heads — the emitted call must resolve through the emitted ns form
;; ---------------------------------------------------------------------------
;; A bare `(reg-event ...)` resolves only through what the ns form refers. These
;; tests READ the output's ns form and check that every bare registrar call in
;; the output — renamed or held — is referred from re-frame.core.

(defn- unbound-bare-heads
  "The bare registrar heads of `src`'s top-level calls that its ns form does
  not refer from re-frame.core (by name or `:refer :all`); empty when every
  bare call resolves."
  [src]
  (let [forms   (read-string (str "[" src "]"))
        ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))
        refers  (for [clause (rest ns-form)
                      :when  (and (seq? clause) (#{:require :use} (first clause)))
                      spec   (rest clause)
                      :when  (and (vector? spec) (= 're-frame.core (first spec)))
                      :let   [opts (apply hash-map (rest spec))]]
                  (or (:refer opts) (:only opts)))
        bound?  (fn [sym] (some #(or (= :all %) (some #{sym} %)) refers))]
    (vec (for [form forms
               :let [hd (when (seq? form) (first form))]
               :when (and (symbol? hd) (nil? (namespace hd))
                          (str/starts-with? (name hd) "reg-event")
                          (not (bound? hd)))]
           hd))))

(deftest bare-rename-binds-reg-event-through-the-ns-form
  (testing "an accepted bare rename adds `reg-event` to the re-frame.core refer, keeping the old name for held sites"
    (doseq [[label ns-in ns-out body-in body-out actions]
            [["fx rename"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx]]))"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx reg-event]]))"
              "(reg-event-fx :a (fn [cofx event] {}))"
              "(reg-event :a (fn [cofx event] {}))"
              [:rename]]
             ["db rewrite"
              "(ns demo (:require [re-frame.core :refer [reg-event-db]]))"
              "(ns demo (:require [re-frame.core :refer [reg-event-db reg-event]]))"
              "(reg-event-db :a (fn [db _] (assoc db :k 1)))"
              "(reg-event :a (fn [{:keys [db]} _] {:db (assoc db :k 1)}))"
              [:rewrite]]
             ["fx and db in one namespace add one binding"
              "(ns demo (:require [re-frame.core :refer [reg-event-db reg-event-fx]]))"
              "(ns demo (:require [re-frame.core :refer [reg-event-db reg-event-fx reg-event]]))"
              "(reg-event-db :a (fn [db _] (assoc db :k 1)))\n(reg-event-fx :b (fn [cofx event] {}))"
              "(reg-event :a (fn [{:keys [db]} _] {:db (assoc db :k 1)}))\n(reg-event :b (fn [cofx event] {}))"
              [:rewrite :rename]]
             ["accepted beside a held site keeps the held site's import"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx]]))"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx reg-event]]))"
              "(reg-event-fx :a (fn [cofx event] {}))\n(reg-event-fx :held [my-interceptor] (fn [cofx event] {}))"
              "(reg-event :a (fn [cofx event] {}))\n(reg-event-fx :held [my-interceptor] (fn [cofx event] {}))"
              [:rename :flag]]
             ["target already referred"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx reg-event]]))"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx reg-event]]))"
              "(reg-event-fx :a (fn [cofx event] {}))"
              "(reg-event :a (fn [cofx event] {}))"
              [:rename]]
             ["target supplied by :refer :all"
              "(ns demo (:require [re-frame.core :refer :all]))"
              "(ns demo (:require [re-frame.core :refer :all]))"
              "(reg-event-fx :a (fn [cofx event] {}))"
              "(reg-event :a (fn [cofx event] {}))"
              [:rename]]
             ["unrelated bindings and a qualified call untouched"
              "(ns demo\n  (:require [re-frame.core :as rf :refer [dispatch reg-event-fx]]\n            [app.util :refer [reg-event-helper]]))"
              "(ns demo\n  (:require [re-frame.core :as rf :refer [dispatch reg-event-fx reg-event]]\n            [app.util :refer [reg-event-helper]]))"
              "(reg-event-fx :a (fn [cofx event] {}))\n(rf/reg-event-fx :b (fn [cofx event] {}))"
              "(reg-event :a (fn [cofx event] {}))\n(rf/reg-event :b (fn [cofx event] {}))"
              [:rename :rename]]
             [":use with :only"
              "(ns demo (:use [re-frame.core :only [reg-event-fx]]))"
              "(ns demo (:use [re-frame.core :only [reg-event-fx reg-event]]))"
              "(reg-event-fx :a (fn [cofx event] {}))"
              "(reg-event :a (fn [cofx event] {}))"
              [:rename]]]]
      (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string
                                        (str ns-in "\n" body-in "\n"))]
        (is (= actions (mapv :action findings)) label)
        (is (= (str ns-out "\n" body-out "\n") source) label)
        (is (= [] (unbound-bare-heads source)) (str label ": every bare call resolves"))))))

(deftest bare-rename-without-a-provable-binding-flags
  (testing "a bare rename whose `reg-event` binding cannot be proved flags, source unchanged"
    (doseq [[label ns-form]
            [["head not referred"
              "(ns demo (:require [re-frame.core :as rf]))"]
             ["head referred from another namespace"
              "(ns demo (:require [app.events :refer [reg-event-fx]]))"]
             ["reg-event referred from another namespace"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx]] [app.events :refer [reg-event]]))"]
             ["reg-event defined in the namespace"
              "(ns demo (:require [re-frame.core :refer [reg-event-fx]]))\n(defn reg-event [& args] args)"]
             ["prefix-list libspec"
              "(ns demo (:require [re-frame [core :refer [reg-event-fx]]]))"]]]
      (let [src (str ns-form "\n(reg-event-fx :a (fn [cofx event] {}))\n")
            {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= [[:flag :binding]] (mapv (juxt :action :flag) findings)) label)
        (is (str/includes? (:note (first findings)) "Refer `reg-event` from re-frame.core") label)
        (is (= src source) (str label " left byte-for-byte unchanged"))))))

;; ---------------------------------------------------------------------------
;; shape non-corruption + idempotence
;; ---------------------------------------------------------------------------

(deftest non-registrar-code-untouched
  (testing "code with no retired registrar is returned byte-for-byte"
    (let [src "(ns my.app)\n\n(defn foo [x] (inc x))\n\n(rf/reg-sub :s (fn [db _] (:s db)))\n;; a comment\n(rf/reg-fx :my/fx (fn [_] nil))\n"
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (empty? findings))
      (is (= src source)))))

(deftest comments-and-whitespace-preserved
  (testing "surrounding comments + blank lines survive the rewrite"
    (let [src ";; counter events\n(rf/reg-event-db :counter/inc ; inline\n  (fn [db _] (update db :count inc)))\n\n;; trailing comment\n"
          out (rewrite src)]
      (is (str/includes? out ";; counter events"))
      (is (str/includes? out "; inline"))
      (is (str/includes? out ";; trailing comment")))))

(deftest idempotent-rewrite
  (testing "running the codemod twice is a no-op the second time, whichever first-param rebind each handler took"
    (let [src (str "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))\n"
                   "(rf/reg-event-db :inc (fn [c _] (update c :n inc)))\n"
                   "(rf/reg-event-db :y (fn [_state ev] (assoc _state :x 1)))\n"
                   "(rf/reg-event-fx :todo/add (fn [c e] {:db (:db c)}))")
          once (rewrite src)
          twice (rewrite once)]
      (is (= once twice) "second pass changes nothing")
      (is (not (str/includes? once "reg-event-db")))
      (is (not (str/includes? once "reg-event-fx"))))))

(deftest multiple-sites-one-file
  (testing "a file with a mix of all forms reports each, rewrites the safe ones"
    (let [src (str "(rf/reg-event-db :a (fn [db _] (assoc db :x 1)))\n"        ; rewrite
                   "(rf/reg-event-fx :b (fn [c e] {:db (:db c)}))\n"           ; rename
                   "(rf/reg-event-ctx :c (fn [ctx] ctx))\n"                    ; flag ctx
                   "(rf/reg-event-db :d (fn [db _] (when true db)))\n")        ; flag nil
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= 4 (count findings)))
      (is (= [:rewrite :rename :flag :flag] (mapv :action findings)))
      (is (= [nil nil :ctx :nil-capable] (mapv :flag findings)))
      ;; the flagged forms are left intact in the output
      (is (str/includes? source "reg-event-ctx"))
      (is (str/includes? source "(when true db)")))))

;; ---------------------------------------------------------------------------
;; reader-discarded source — a `#_` form holds no registration
;; ---------------------------------------------------------------------------

(deftest discarded-forms-not-scanned-or-rewritten
  (testing "a #_ subtree yields no finding and keeps every byte; the active sites beside it count once"
    (doseq [[label src actions out]
            [["discarded only"
              "(ns demo (:require [re-frame.core :as rf]))\n#_(rf/reg-event-fx :d (fn [cofx event] {}))\n"
              [] nil]
             ["active beside discarded, comments kept"
              "(ns demo (:require [re-frame.core :as rf]))\n;; live\n(rf/reg-event-fx :a (fn [cofx event] {}))\n#_ ;; parked\n(rf/reg-event-fx :d (fn [cofx event] {}))\n"
              [:rename]
              "(ns demo (:require [re-frame.core :as rf]))\n;; live\n(rf/reg-event :a (fn [cofx event] {}))\n#_ ;; parked\n(rf/reg-event-fx :d (fn [cofx event] {}))\n"]
             ["discarded Type B shapes"
              "#_(rf/reg-event-ctx :d (fn [ctx] ctx))\n#_(rf/reg-event-db :d2 [my-interceptor] (fn [db _] (when db db)))\n"
              [] nil]
             ["nested and stacked discards"
              (str "#_(do (rf/reg-event-fx :d1 (fn [c e] {})) #_(rf/reg-event-db :d2 (fn [db _] db)))\n"
                   "#_ #_ (rf/reg-event-fx :d3 (fn [c e] {})) (rf/reg-event-fx :d4 (fn [c e] {}))\n"
                   "(let [x 1]\n  #_(rf/reg-event-fx :d5 (fn [c e] {}))\n  x)\n")
              [] nil]
             ["a discarded slot inside an active call"
              "(rf/reg-event-fx :a #_[my-interceptor] (fn [cofx event] {}))\n(rf/reg-event-db :b (fn [db _] (assoc db :k 1) #_(old db)))\n"
              [:rename :rewrite]
              "(rf/reg-event :a #_[my-interceptor] (fn [cofx event] {}))\n(rf/reg-event :b (fn [{:keys [db]} _] {:db (assoc db :k 1)} #_(old db)))\n"]
             ["bare and qualified active sites beside discards"
              "(ns demo (:require [re-frame.core :as rf :refer [reg-event-fx]]))\n#_(reg-event-fx :d (fn [c e] {}))\n(reg-event-fx :a (fn [c e] {}))\n(rf/reg-event-fx :b (fn [c e] {}))\n"
              [:rename :rename]
              "(ns demo (:require [re-frame.core :as rf :refer [reg-event-fx reg-event]]))\n#_(reg-event-fx :d (fn [c e] {}))\n(reg-event :a (fn [c e] {}))\n(rf/reg-event :b (fn [c e] {}))\n"]]]
      (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= actions (mapv :action (rf.migration.reg-event-codemod/scan-string src))) (str label ": scan"))
        (is (= actions (mapv :action findings)) (str label ": rewrite"))
        (is (= (or out src) source) label)))))

;; ---------------------------------------------------------------------------
;; filesystem entry points
;; ---------------------------------------------------------------------------

(deftest scan-file-roundtrip
  (testing "scan-file + rewrite-file! over a temp file on disk"
    (let [tmp (java.io.File/createTempFile "regevent" ".cljc")]
      (try
        (spit tmp "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))\n")
        (let [findings (rf.migration.reg-event-codemod/scan-file (.getPath tmp))]
          (is (= 1 (count findings)))
          (is (= (.getPath tmp) (str (:file (first findings))))))
        ;; dry run does NOT write
        (let [{:keys [changed?]} (rf.migration.reg-event-codemod/rewrite-file! (.getPath tmp) {:write? false})
              after (slurp tmp)]
          (is changed?)
          (is (str/includes? after "reg-event-db") "dry run left the file unwritten"))
        ;; write does mutate the file
        (let [{:keys [changed?]} (rf.migration.reg-event-codemod/rewrite-file! (.getPath tmp) {:write? true})
              after (slurp tmp)]
          (is changed?)
          (is (not (str/includes? after "reg-event-db")))
          (is (str/includes? after "{:keys [db]}")))
        (finally (.delete tmp))))))

;; A write changes a file only through an accepted rewrite or rename, and
;; keeps its line endings: rewrite-clj reads every break as LF, so without
;; care a CRLF file is rewritten to LF even when no registration in it moved.

(defn- file-bytes [f]
  (vec (java.nio.file.Files/readAllBytes (.toPath (io/file f)))))

(defn- text-bytes [^String s]
  (vec (.getBytes s "UTF-8")))

(defn- with-source-dir
  "Write `files` ({name text}) into a fresh temp dir, call `(f dir)`, and
  delete the dir."
  [files f]
  (let [dir (doto (java.io.File/createTempFile "regeol" "") .delete .mkdirs)]
    (try
      (doseq [[n text] files] (spit (io/file dir n) text))
      (f dir)
      (finally (doseq [x (reverse (file-seq dir))] (.delete x))))))

(defn- rewrite-dir!
  "Run the codemod in write mode over `dir`; return the names of the files it
  reports changed."
  [dir]
  (set (for [r (rf.migration.reg-event-codemod/rewrite-paths! [(.getPath dir)] {:write? true})
             :when (:changed? r)]
         (.getName (io/file (:path r))))))

(deftest write-keeps-line-endings
  (let [sources   {"no-event" ["(ns eol-control)" "(def value 1)"]
                   "flagged"  ["(ns eol-control)" "(rf/reg-event-db :eol-control/event [(rf/unwrap)] (fn [db event] db))"]
                   "discarded" ["(ns eol-control)" "#_(rf/reg-event-fx :eol-control/event (fn [cofx event] {}))"]
                   "rename"   ["(ns eol-control)" "(rf/reg-event-fx :eol-control/event (fn [cofx event] {}))"]
                   "bare"     ["(ns eol-control (:require [re-frame.core :refer [reg-event-fx]]))"
                               "(reg-event-fx :eol-control/event (fn [cofx event] {}))"]}
        rewritten {"rename"   ["(ns eol-control)" "(rf/reg-event :eol-control/event (fn [cofx event] {}))"]
                   "bare"     ["(ns eol-control (:require [re-frame.core :refer [reg-event-fx reg-event]]))"
                               "(reg-event :eol-control/event (fn [cofx event] {}))"]}
        eols      {"lf" "\n" "crlf" "\r\n"}
        text      (fn [lines sep] (str (str/join sep lines) sep))
        files     (into {} (for [[n lines] sources [eol sep] eols]
                             [(str n "-" eol ".cljs") (text lines sep)]))]
    (testing "no-event, flagged-only and discarded-only files are left byte-identical; a rename, qualified or bare, keeps its convention"
      (with-source-dir files
        (fn [dir]
          (is (= (set (for [n (keys rewritten) eol (keys eols)] (str n "-" eol ".cljs")))
                 (rewrite-dir! dir)))
          (doseq [n ["no-event-lf.cljs" "no-event-crlf.cljs" "flagged-lf.cljs" "flagged-crlf.cljs"
                     "discarded-lf.cljs" "discarded-crlf.cljs"]]
            (is (= (text-bytes (files n)) (file-bytes (io/file dir n))) n))
          (doseq [[n lines] rewritten [eol sep] eols]
            (is (= (text-bytes (text lines sep)) (file-bytes (io/file dir (str n "-" eol ".cljs"))))
                (str n "-" eol))))))
    (testing "the CLI dry run and write count only the renamed files, and a discard adds no finding"
      (with-source-dir files
        (fn [dir]
          (is (str/includes? (with-out-str (rf.migration.reg-event-codemod/-main "--rewrite" (.getPath dir)))
                             "4 file(s) would change (dry run); summary: {:total 6, :rewrite 0, :rename 4, :flag 2}"))
          (is (str/includes? (with-out-str (rf.migration.reg-event-codemod/-main "--rewrite" "--write" (.getPath dir)))
                             "4 file(s) rewritten")))))))

(deftest write-mixed-line-endings
  (testing "a mixed file is left alone unless rewritten, then takes its first break's convention"
    (with-source-dir {"unchanged.cljs" "(ns m)\r\n(def a 1)\n(def b 2)\r\n"
                      "crlf-first.cljs" "(ns m)\r\n(rf/reg-event-fx :e (fn [c e] {}))\n(def a 1)\n"
                      "lf-first.cljs"   "(ns m)\n(rf/reg-event-fx :e (fn [c e] {}))\r\n(def a 1)\r\n"}
      (fn [dir]
        (is (= #{"crlf-first.cljs" "lf-first.cljs"} (rewrite-dir! dir)))
        (is (= (text-bytes "(ns m)\r\n(def a 1)\n(def b 2)\r\n")
               (file-bytes (io/file dir "unchanged.cljs"))))
        (is (= (text-bytes "(ns m)\r\n(rf/reg-event :e (fn [c e] {}))\r\n(def a 1)\r\n")
               (file-bytes (io/file dir "crlf-first.cljs"))))
        (is (= (text-bytes "(ns m)\n(rf/reg-event :e (fn [c e] {}))\n(def a 1)\n")
               (file-bytes (io/file dir "lf-first.cljs"))))))))

(deftest scan-paths-recurses-dir
  (testing "scan-paths walks a directory for .clj/.cljc/.cljs sources"
    (let [dir (java.io.File/createTempFile "regdir" "")]
      (.delete dir)
      (.mkdirs dir)
      (try
        (spit (io/file dir "a.cljs") "(rf/reg-event-db :a (fn [db _] (assoc db :x 1)))")
        (spit (io/file dir "b.clj")  "(rf/reg-event-fx :b (fn [c e] {:db (:db c)}))")
        (spit (io/file dir "c.txt")  "(rf/reg-event-db :ignored (fn [db _] db))") ; not a source ext
        (let [findings (rf.migration.reg-event-codemod/scan-paths [(.getPath dir)])]
          (is (= 2 (count findings)) "only .cljs + .clj scanned, .txt ignored")
          (is (= #{:reg-event-db :reg-event-fx} (set (map :form findings)))))
        (finally
          (doseq [f (reverse (file-seq dir))] (.delete f)))))))
