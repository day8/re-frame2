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

  Plus: alias-agnostic registrar detection, bare-head binding (a renamed bare
  head's `reg-event` resolves through the emitted ns form, or the site flags
  `:binding`), path-head RESOLUTION (only a head resolving to
  re-frame.core/path lowers; custom `*/path` fns flag), scan-file/scan-paths
  over the filesystem, write-mode line-ending fidelity, and idempotence. The
  RUNTIME proof that the emitted chain shapes register against the real v2
  reg-event contract lives in the `:integration` alias (test-integration/)
  so this default suite stays self-contained."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [re-frame.migration.reg-event-codemod :as rf.migration.reg-event-codemod]))

(defn- rewrite [s] (:source (rf.migration.reg-event-codemod/rewrite-string s)))

(defn- outcome
  "`[[[form action flag target] ...] source]` for one rewrite of `s`."
  [s]
  (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string s)]
    [(mapv (juxt :form :action :flag :target) findings) source]))

(def ^:private db-rewrite [[:reg-event-db :rewrite nil :reg-event]])

;; ---------------------------------------------------------------------------
;; reg-event-fx — pure rename
;; ---------------------------------------------------------------------------

(deftest fx-renamed
  (testing "reg-event-fx is renamed to reg-event; the body and a metadata slot stay verbatim"
    (doseq [[src out]
            [["(rf/reg-event-fx :todo/add\n  (fn [{:keys [db]} [_ text]]\n    {:db (assoc-in db [:todos text] true)}))"
              "(rf/reg-event :todo/add\n  (fn [{:keys [db]} [_ text]]\n    {:db (assoc-in db [:todos text] true)}))"]
             ["(rf/reg-event-fx :todo/add {:rf.cofx/requires [:rf/time-ms]}\n  (fn [{:keys [db rf/time-ms]} [_ text]] {:db db}))"
              "(rf/reg-event :todo/add {:rf.cofx/requires [:rf/time-ms]}\n  (fn [{:keys [db rf/time-ms]} [_ text]] {:db db}))"]]]
      (is (= [[[:reg-event-fx :rename nil :reg-event]] out] (outcome src))))))

;; ---------------------------------------------------------------------------
;; reg-event-db — faithful rewrite to {:db BODY}
;; ---------------------------------------------------------------------------

(deftest db-handlers-rewrite-faithfully
  (testing "reg-event-db -> reg-event: the db param comes out of the coeffects and the LAST body form is wrapped {:db BODY}"
    (doseq [[label src out]
            [["simple update"
              "(rf/reg-event-db :counter/inc\n  (fn [db _] (update db :count inc)))"
              "(rf/reg-event :counter/inc\n  (fn [{:keys [db]} _] {:db (update db :count inc)}))"]
             ["a -> thread ending in a builder is non-nil"
              "(rf/reg-event-db :form/clear\n  (fn [db [_ k]]\n    (-> db\n        (assoc-in [:form k :value] \"\")\n        (assoc-in [:form k :error] nil))))"
              "(rf/reg-event :form/clear\n  (fn [{:keys [db]} [_ k]]\n    {:db (-> db\n        (assoc-in [:form k :value] \"\")\n        (assoc-in [:form k :error] nil))}))"]
             ["a named handler fn"
              "(rf/reg-event-db :x/y (fn handle [db _] (assoc db :ok true)))"
              "(rf/reg-event :x/y (fn handle [{:keys [db]} _] {:db (assoc db :ok true)}))"]
             ["a multi-form body wraps only its last form"
              "(rf/reg-event-db :log/it\n  (fn [_state _]\n    (js/console.log \"hi\")\n    (assoc _state :logged true)))"
              "(rf/reg-event :log/it\n  (fn [{_state :db} _]\n    (js/console.log \"hi\")\n    {:db (assoc _state :logged true)}))"]]]
      (is (= [db-rewrite out] (outcome src)) label))))

;; A handler that binds the db value under a name OTHER than `db` (a path-scoped
;; slice such as `state`, or an `_`-prefixed name it still reads) keeps every
;; body reference resolved by rebinding `{name :db}`; rebinding `{:keys [db]}`
;; there orphans the body's references (an unbound-symbol compile error). A name
;; the body never reads — `_`, or one only inner bindings shadow — keeps the
;; canonical `{:keys [db]}`.

(deftest db-first-param-binds-back-only-when-referenced
  (doseq [[label src out]
          [["a renamed param binds back {state :db}"
            "(rf/reg-event-db :s/set (fn [state [_ v]] (assoc state :v v)))"
            "(rf/reg-event :s/set (fn [{state :db} [_ v]] {:db (assoc state :v v)}))"]
           ["an ignored `_` keeps {:keys [db]}"
            "(rf/reg-event-db :init (fn [_ _] {:count 0 :items []}))"
            "(rf/reg-event :init (fn [{:keys [db]} _] {:db {:count 0 :items []}}))"]
           ["a referenced `_state` binds back"
            "(reg-event-db :y (fn [_state ev] (assoc _state :x 1)))"
            "(reg-event :y (fn [{_state :db} ev] {:db (assoc _state :x 1)}))"]
           ["an unreferenced `_state` keeps {:keys [db]}"
            "(rf/reg-event-db :init (fn [_state _] {:count 0 :items []}))"
            "(rf/reg-event :init (fn [{:keys [db]} _] {:db {:count 0 :items []}}))"]
           ["a referenced `_db` binds back"
            "(rf/reg-event-db :touch (fn [_db _] (assoc _db :touched true)))"
            "(rf/reg-event :touch (fn [{_db :db} _] {:db (assoc _db :touched true)}))"]
           ["an inner let rebinding the name shadows it: the outer param is unreferenced"
            "(rf/reg-event-db :shadow\n  (fn [_state [_ k]]\n    (let [_state {:fresh k}]\n      (assoc _state :touched true))))"
            "(rf/reg-event :shadow\n  (fn [{:keys [db]} [_ k]]\n    {:db (let [_state {:fresh k}]\n      (assoc _state :touched true))}))"]
           ["an inner fn shadows locally, but a free reference still binds back"
            "(rf/reg-event-db :map-it\n  (fn [_s _]\n    (assoc _s :xs (map (fn [_s] (inc _s)) (:xs _s)))))"
            "(rf/reg-event :map-it\n  (fn [{_s :db} _]\n    {:db (assoc _s :xs (map (fn [_s] (inc _s)) (:xs _s)))}))"]
           ["every use inside an inner fn: the outer param is unreferenced"
            "(rf/reg-event-db :init\n  (fn [_s _]\n    (assoc {} :xs (mapv (fn [_s] (inc _s)) [1 2 3]))))"
            "(rf/reg-event :init\n  (fn [{:keys [db]} _]\n    {:db (assoc {} :xs (mapv (fn [_s] (inc _s)) [1 2 3]))}))"]]]
    (is (= [db-rewrite out] (outcome src)) label)))

;; ---------------------------------------------------------------------------
;; path interceptor chains LOWERED to the standard factory ref (M-70 x M-73)
;; ---------------------------------------------------------------------------
;; v2 chains are reference-only (EP-0022): `rf/path` is a throwing removal stub
;; (:rf.error/path-removed), inline values are rejected
;; (:rf.error/inline-interceptor-removed), and the positional vector middle
;; slot is rejected (:rf.error/reg-event-bad-middle-slot). The codemod lowers
;; the standard path constructor to the framework factory ref
;; `[:rf.interceptor/path [p...]]` in every mechanical source shape; the
;; runtime proof these emitted shapes actually REGISTER lives in the
;; `:integration` alias.

(deftest path-chains-lower-to-factory-refs
  (doseq [[label form src out]
          [["the metadata chain"
            :reg-event-db
            "(rf/reg-event-db :counter/inc\n  {:interceptors [(rf/path :counter)]}\n  (fn [db _] (update db :value inc)))"
            "(rf/reg-event :counter/inc\n  {:interceptors [[:rf.interceptor/path [:counter]]]}\n  (fn [{:keys [db]} _] {:db (update db :value inc)}))"]
           ["positional entries keep their declaration order"
            :reg-event-db
            "(rf/reg-event-db :x [(rf/path :a) (rf/path :b)] (fn [db _] (assoc db :k 1)))"
            "(rf/reg-event :x {:interceptors [[:rf.interceptor/path [:a]] [:rf.interceptor/path [:b]]]} (fn [{:keys [db]} _] {:db (assoc db :k 1)}))"]
           ["a single bare (rf/path ...) middle slot"
            :reg-event-db
            "(rf/reg-event-db :x (rf/path :a) (fn [db _] (assoc db :k 1)))"
            "(rf/reg-event :x {:interceptors [[:rf.interceptor/path [:a]]]} (fn [{:keys [db]} _] {:db (assoc db :k 1)}))"]
           ["metadata-plus-vector merges into ONE metadata map, map entries first"
            :reg-event-db
            "(rf/reg-event-db :x {:interceptors [(rf/path :a)]} [(rf/path :b)]\n  (fn [db _] (assoc db :k 1)))"
            "(rf/reg-event :x {:interceptors [[:rf.interceptor/path [:a]] [:rf.interceptor/path [:b]]]}\n  (fn [{:keys [db]} _] {:db (assoc db :k 1)}))"]
           ["a metadata map without :interceptors gains the merged chain"
            :reg-event-db
            "(rf/reg-event-db :x {:doc \"d\"} [(rf/path :b)] (fn [db _] (assoc db :k 1)))"
            "(rf/reg-event :x {:doc \"d\" :interceptors [[:rf.interceptor/path [:b]]]} (fn [{:keys [db]} _] {:db (assoc db :k 1)}))"]
           ["vector and keyword path args flatten as v1 path did"
            :reg-event-db
            "(rf/reg-event-db :x {:interceptors [(rf/path [:a] :b)]} (fn [db _] (assoc db :k 1)))"
            "(rf/reg-event :x {:interceptors [[:rf.interceptor/path [:a :b]]]} (fn [{:keys [db]} _] {:db (assoc db :k 1)}))"]
           ["reg-event-fx with a convertible chain is a :rewrite, its handler verbatim"
            :reg-event-fx
            "(rf/reg-event-fx :x {:interceptors [(rf/path :a)]}\n  (fn [cofx _] {:db (:db cofx)}))"
            "(rf/reg-event :x {:interceptors [[:rf.interceptor/path [:a]]]}\n  (fn [cofx _] {:db (:db cofx)}))"]
           ["a chain that is already refs-only is kept byte-for-byte"
            :reg-event-db
            "(rf/reg-event-db :x {:interceptors [:my/ic [:rf.interceptor/path [:cart]]]}\n  (fn [db _] (assoc db :k 1)))"
            "(rf/reg-event :x {:interceptors [:my/ic [:rf.interceptor/path [:cart]]]}\n  (fn [{:keys [db]} _] {:db (assoc db :k 1)}))"]
           ["a renamed reg-event still carrying a v1 chain is repaired, its handler untouched"
            :reg-event
            "(rf/reg-event :counter/inc\n  {:interceptors [(rf/path :counter)]}\n  (fn [{:keys [db]} _] {:db (update db :value inc)}))"
            "(rf/reg-event :counter/inc\n  {:interceptors [[:rf.interceptor/path [:counter]]]}\n  (fn [{:keys [db]} _] {:db (update db :value inc)}))"]]]
    (is (= [[[form :rewrite nil :reg-event]] out] (outcome src)) label)))

(deftest valid-reg-event-produces-no-finding
  (testing "valid v2 registrations are not reported by the rescan"
    (let [src (str "(rf/reg-event :a (fn [{:keys [db]} _] {:db db}))\n"
                   "(rf/reg-event :b {:interceptors [:my/ic]} (fn [{:keys [db]} _] {:db db}))\n"
                   "(rf/reg-event :c {:interceptors [[:rf.interceptor/path [:x]]]} (fn [{:keys [db]} _] {:db db}))\n"
                   "(rf/reg-event :d {:doc \"plain metadata\"} (fn [{:keys [db]} _] {:db db}))\n")]
      (is (= [[] src] (outcome src))))))

;; ---------------------------------------------------------------------------
;; custom inline interceptors — unresolved M-70 Type B (:flag :interceptors)
;; ---------------------------------------------------------------------------
;; An inline entry with no mechanically derivable registered id (a var, a
;; custom call, a dynamic path arg) makes the WHOLE site an unresolved M-70
;; finding and the source is left unchanged: a head-only rewrite would certify
;; output v2 rejects at namespace load.

(deftest unresolved-chain-entry-flags-whole-site
  (testing "a chain entry with no derivable v2 reference flags the whole site, source unchanged"
    (doseq [[label src form]
            [["a custom interceptor var"
              "(rf/reg-event-db :x {:interceptors [my-auth-interceptor]}\n  (fn [db _] (assoc db :k 1)))"
              :reg-event-db]
             ["a positional custom call is not pure-renamed"
              "(rf/reg-event-fx :x [(rf/debug)]\n  (fn [c _] {:db (:db c)}))"
              :reg-event-fx]
             ["(rf/path p) with a non-literal arg has no derivable path vector"
              "(rf/reg-event-db :x {:interceptors [(rf/path p)]} (fn [db _] (assoc db :k 1)))"
              :reg-event-db]
             ["a convertible path beside an underivable entry is not half-converted"
              "(rf/reg-event-db :x {:interceptors [(rf/path :a) my-ic]}\n  (fn [db _] (assoc db :k 1)))"
              :reg-event-db]
             ["an already-renamed reg-event with an underivable entry"
              "(rf/reg-event :x {:interceptors [my-ic]} (fn [{:keys [db]} _] {:db db}))"
              :reg-event]]]
      (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= [[form :flag :interceptors] true src]
               [((juxt :form :action :flag) (first findings)) (str/includes? (:note (first findings)) "M-70") source])
            label)))))

;; ---------------------------------------------------------------------------
;; path-head resolution — only the STANDARD constructor is mechanical
;; ---------------------------------------------------------------------------
;; `(app.interceptors/path :tenant)` shares the simple name `path` with the
;; standard constructor while carrying entirely different author semantics.
;; The head must RESOLVE to `re-frame.core/path` — through the file's ns form
;; (the full namespace, an `:as` alias of it, or a bare `path` it `:refer`s),
;; or, in an ns-less fragment, by the conventional bare/dotless-alias reading.
;; Any other function named `path` is a custom inline interceptor: unresolved
;; M-70 Type B, source unchanged — never a silent rewrite.

(def ^:private path-flag [[:reg-event-db :flag :interceptors :reg-event]])

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
      (is (= [path-flag src] (outcome src)) label))))

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
              "[:rf.interceptor/path [:a]]"]
             ;; Locals are simple symbols, so a local named `path` cannot shadow
             ;; a qualified head, and a binding in a SIBLING form shadows nothing.
             ["a local named `path` cannot shadow `rf/path`"
              (str "(ns app.events (:require [re-frame.core :as rf]))\n"
                   "(let [path app.interceptors/path]\n"
                   "  (rf/reg-event-db :counter/inc\n"
                   "    {:interceptors [(rf/path :counter)]}\n"
                   "    (fn [db _] (update db :value inc))))\n")
              "{:interceptors [[:rf.interceptor/path [:counter]]]}"]
             ["a `path` binding in a sibling form leaves this site's bare head standard"
              (str "(ns app.events\n"
                   "  (:require [re-frame.core :refer [reg-event-db path]]))\n"
                   "(defn helper [path] (str path))\n"
                   "(reg-event-db :counter/inc\n"
                   "  {:interceptors [(path :counter)]}\n"
                   "  (fn [db _] (update db :value inc)))\n")
              "{:interceptors [[:rf.interceptor/path [:counter]]]}"]]]
      (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
        (is (= [[:rewrite] true] [(mapv :action findings) (str/includes? source ref)]) label)))))

;; ---------------------------------------------------------------------------
;; path-head resolution — LEXICAL SHADOWING at the call site
;; ---------------------------------------------------------------------------
;; What the ns form makes AVAILABLE is not what a bare head DENOTES. A file may
;; refer `re-frame.core/path` and still rebind the name around a registration,
;; in which case `(path :tenant)` is the local — lowering it to
;; [:rf.interceptor/path [:tenant]] would swap the author's semantics silently.

(defn- referred-path-site
  "A registration of a bare, referred `(path k)` inside `open` ... `close`."
  [open close k]
  (str "(ns app.events\n"
       "  (:require [re-frame.core :refer [reg-event-db path]]))\n"
       open "\n"
       "  (reg-event-db :x\n"
       "    {:interceptors [(path " k ")]}\n"
       "    (fn [db _] (assoc db :k 1)))" close "\n"))

(deftest shadowed-bare-path-flagged-across-binding-forms
  (testing "an enclosing form that binds `path` makes a bare `(path ...)` the local: flag, source unchanged"
    (doseq [[label open close]
            [;; the binding vocabulary
             ["let"     "(let [path app.interceptors/path]"            ")"]
             ["fn"      "((fn [path]"                                  ") app.interceptors/path)"]
             ["defn"    "(defn install! [path]"                        ")"]
             ["letfn"   "(letfn [(path [k] [k])]"                      ")"]
             ["doseq"   "(doseq [path [:a :b]]"                        ")"]
             ;; a qualified spelling binds exactly as the simple one does, and
             ;; the vocabulary is keyed by simple names, so it must still count
             ["clojure.core/let" "(clojure.core/let [path app.interceptors/path]" ")"]
             ;; a head outside the vocabulary whose vector child binds the name
             ;; is a binder whatever its head, which can only ever produce a flag
             ["defmethod"     "(defmethod install! :web [_ path]"       ")"]
             ["when-first"    "(when-first [path paths]"                ")"]
             ["project macro" "(app.macros/with-scope [path :tenant]"   ")"]]]
      (let [src (referred-path-site open close ":tenant")]
        (is (= [path-flag src] (outcome src)) (str label " must flag, source unchanged"))))))

(deftest unrecognised-head-without-a-path-binding-still-lowers
  (testing "the catch-all is narrow: an enclosing form that binds some OTHER name is inert"
    (doseq [[label open close]
            [["defmethod other param" "(defmethod install! :web [_ opts]" ")"]
             ["doseq other name"      "(doseq [k [:a :b]]"          ")"]]]
      (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string
                                        (referred-path-site open close ":counter"))]
        (is (= [[:rewrite] true]
               [(mapv :action findings) (str/includes? source "[[:rf.interceptor/path [:counter]]]")])
            (str label " must still lower the standard head"))))))

;; ---------------------------------------------------------------------------
;; flagged sites — reported, never rewritten
;; ---------------------------------------------------------------------------

(deftest ctx-flagged-never-rewritten
  (let [src "(rf/reg-event-ctx :advanced/thing\n  (fn [ctx] (assoc ctx :rf/skip-handler? true)))"]
    (is (= [[[:reg-event-ctx :flag :ctx nil]] src] (outcome src)))))

;; D7 — a body that can evaluate to nil is flagged, not rewritten, and the
;; rewrite still suggests its target.

(deftest nil-capable-bodies
  (testing "each body shape that can evaluate to nil -> FLAG :nil-capable, source unchanged"
    (doseq [[label src]
            [["when"            "(rf/reg-event-db :maybe/set\n  (fn [db [_ v]] (when v (assoc db :v v))))"]
             ["if without else" "(rf/reg-event-db :cond/set\n  (fn [db [_ ok?]] (if ok? (assoc db :ok true))))"]
             ["get"             "(rf/reg-event-db :grab (fn [db [_ k]] (get db k)))"]
             ["cond"            "(rf/reg-event-db :route\n  (fn [db [_ x]] (cond (= x 1) (assoc db :a 1) (= x 2) (assoc db :b 2))))"]
             ["and"             "(rf/reg-event-db :a (fn [db _] (and (:ready? db) (assoc db :go true))))"]
             ["or"              "(rf/reg-event-db :o (fn [db _] (or (:cached db) (assoc db :fresh true))))"]
             ["literal nil"     "(rf/reg-event-db :noop (fn [db _] nil))"]
             ["some-> thread"   "(rf/reg-event-db :s (fn [db [_ k]] (some-> db (get k) inc)))"]
             ["a convertible chain does not bypass the gate"
              "(rf/reg-event-db :x {:interceptors [(rf/path :a)]}\n  (fn [db _] (when true db)))"]]]
      (is (= [[[:reg-event-db :flag :nil-capable :reg-event]] src] (outcome src)) label))))

(deftest not-nil-capable-db-builders
  (testing "assoc / assoc-in / update / merge / dissoc bodies are non-nil -> rewrite"
    (doseq [body ["(assoc db :x 1)" "(assoc-in db [:a :b] 1)" "(update db :n inc)"
                  "(merge db {:x 1})" "(dissoc db :x)"]]
      (is (= [:rewrite] (mapv :action (rf.migration.reg-event-codemod/scan-string
                                        (str "(rf/reg-event-db :id (fn [db _] " body "))"))))
          body))))

(deftest complex-handlers-flagged
  (testing "a handler that is not the simple single-arity (fn [db ev] ...) shape -> FLAG :complex, unchanged"
    (doseq [[label src]
            [["a var handler"           "(rf/reg-event-db :x/y my-handler-fn)"]
             ["a destructured db param" "(rf/reg-event-db :x/y (fn [{:keys [a b]} _] (assoc {} :a a)))"]
             ["a multi-arity handler"   "(rf/reg-event-db :x/y (fn ([db] (assoc db :one true)) ([db _] (assoc db :two true))))"]]]
      (is (= [[[:reg-event-db :flag :complex nil]] src] (outcome src)) label))))

;; ---------------------------------------------------------------------------
;; alias-agnostic detection
;; ---------------------------------------------------------------------------

(deftest alias-agnostic
  (testing "any alias, the full namespace or a bare head is detected, and the rename keeps it"
    (doseq [[head renamed] [["re-frame.core/reg-event-db" "re-frame.core/reg-event"]
                            ["rf2/reg-event-db"           "rf2/reg-event"]
                            ["reg-event-db"               "reg-event"]]]
      (is (= [db-rewrite (str "(" renamed " :id (fn [{:keys [db]} _] {:db (assoc db :x 1)}))")]
             (outcome (str "(" head " :id (fn [db _] (assoc db :x 1)))")))
          head))))

;; ---------------------------------------------------------------------------
;; bare heads — the emitted call must resolve through the emitted ns form
;; ---------------------------------------------------------------------------
;; A bare `(reg-event ...)` resolves only through what the ns form refers, so
;; every bare registrar call in the output — renamed or held — must be referred
;; from re-frame.core.

(deftest bare-rename-binds-reg-event-through-the-ns-form
  (testing "an accepted bare rename adds `reg-event` to the re-frame.core refer, keeping the old name for held sites"
    (doseq [[label ns-in ns-out body-in body-out actions]
            [["db rewrite"
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
        (is (= [actions (str ns-out "\n" body-out "\n")] [(mapv :action findings) source]) label)))))

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
        (is (= [[[:flag :binding]] true src]
               [(mapv (juxt :action :flag) findings)
                (str/includes? (:note (first findings)) "Refer `reg-event` from re-frame.core")
                source])
            label)))))

;; ---------------------------------------------------------------------------
;; shape non-corruption + idempotence
;; ---------------------------------------------------------------------------

(deftest comments-and-whitespace-preserved
  (testing "surrounding comments + blank lines survive the rewrite"
    (is (= ";; counter events\n(rf/reg-event :counter/inc ; inline\n  (fn [{:keys [db]} _] {:db (update db :count inc)}))\n\n;; trailing comment\n"
           (rewrite ";; counter events\n(rf/reg-event-db :counter/inc ; inline\n  (fn [db _] (update db :count inc)))\n\n;; trailing comment\n")))))

(deftest idempotent-rewrite
  (testing "running the codemod twice is a no-op the second time, whichever first-param rebind each handler took"
    (let [once (rewrite (str "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))\n"
                             "(rf/reg-event-db :inc (fn [c _] (update c :n inc)))\n"
                             "(rf/reg-event-db :y (fn [_state ev] (assoc _state :x 1)))\n"
                             "(rf/reg-event-fx :todo/add (fn [c e] {:db (:db c)}))"))]
      (is (not-any? #(str/includes? once %) ["reg-event-db" "reg-event-fx"]) "the first pass rewrote every site")
      (is (= once (rewrite once)) "second pass changes nothing"))))

(deftest multiple-sites-one-file
  (testing "a file with a mix of all forms reports each, rewrites the safe ones and leaves the flagged ones intact"
    (is (= [[[:reg-event-db :rewrite nil :reg-event]
             [:reg-event-fx :rename nil :reg-event]
             [:reg-event-ctx :flag :ctx nil]
             [:reg-event-db :flag :nil-capable :reg-event]]
            (str "(rf/reg-event :a (fn [{:keys [db]} _] {:db (assoc db :x 1)}))\n"
                 "(rf/reg-event :b (fn [c e] {:db (:db c)}))\n"
                 "(rf/reg-event-ctx :c (fn [ctx] ctx))\n"
                 "(rf/reg-event-db :d (fn [db _] (when true db)))\n")]
           (outcome (str "(rf/reg-event-db :a (fn [db _] (assoc db :x 1)))\n"
                         "(rf/reg-event-fx :b (fn [c e] {:db (:db c)}))\n"
                         "(rf/reg-event-ctx :c (fn [ctx] ctx))\n"
                         "(rf/reg-event-db :d (fn [db _] (when true db)))\n"))))))

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
    (let [tmp (java.io.File/createTempFile "regevent" ".cljc")
          src "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))\n"]
      (try
        (spit tmp src)
        (is (= [(.getPath tmp)] (mapv (comp str :file) (rf.migration.reg-event-codemod/scan-file (.getPath tmp)))))
        (is (:changed? (rf.migration.reg-event-codemod/rewrite-file! (.getPath tmp) {:write? false})))
        (is (= src (slurp tmp)) "dry run left the file unwritten")
        (is (:changed? (rf.migration.reg-event-codemod/rewrite-file! (.getPath tmp) {:write? true})))
        (is (= "(rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))\n" (slurp tmp)))
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
  (testing "scan-paths walks a directory for .clj/.cljc/.cljs sources and ignores the rest"
    (with-source-dir {"a.cljs" "(rf/reg-event-db :a (fn [db _] (assoc db :x 1)))"
                      "b.clj"  "(rf/reg-event-fx :b (fn [c e] {:db (:db c)}))"
                      "c.txt"  "(rf/reg-event-db :ignored (fn [db _] db))"}
      (fn [dir]
        (is (= [:reg-event-db :reg-event-fx]
               (sort (map :form (rf.migration.reg-event-codemod/scan-paths [(.getPath dir)])))))))))
