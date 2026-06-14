(ns re-frame.migration.reg-event-codemod-test
  "Migration tests for the EP-0018 Slice E reg-event scanner + codemod.

  The coverage matrix (one or more deftests per row):

    | v1 snippet                       | scanner finding   | codemod action        |
    |----------------------------------|-------------------|-----------------------|
    | simple reg-event-db              | :reg-event-db     | rewrite {:db BODY}    |
    | reg-event-db w/ path interceptor | :reg-event-db     | rewrite, meta kept    |
    | reg-event-fx                     | :reg-event-fx     | rename only           |
    | reg-event-ctx                    | :reg-event-ctx    | FLAG (:ctx)           |
    | nil-capable -db body (when/if/   | :reg-event-db     | FLAG (:nil-capable)   |
    |   get/cond/and-or)               |                   |                       |
    | complex -db (var/multi-arity/    | :reg-event-db     | FLAG (:complex)       |
    |   destructured db param)         |                   |                       |

  Plus: shape-non-corruption (round-trips of untouched code), alias-agnostic
  detection, scan-file/scan-paths over the filesystem, and idempotence."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [re-frame.migration.reg-event-codemod :as cm]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- only-finding [s]
  (let [fs (cm/scan-string s)]
    (is (= 1 (count fs)) (str "expected exactly one finding in: " s))
    (first fs)))

(defn- rewrite [s] (:source (cm/rewrite-string s)))

;; ---------------------------------------------------------------------------
;; reg-event-fx — pure rename
;; ---------------------------------------------------------------------------

(deftest fx-renamed
  (testing "reg-event-fx is renamed to reg-event, body untouched"
    (let [src "(rf/reg-event-fx :todo/add\n  (fn [{:keys [db]} [_ text]]\n    {:db (assoc-in db [:todos text] true)}))"
          {:keys [source findings]} (cm/rewrite-string src)]
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
          {:keys [source findings]} (cm/rewrite-string src)]
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
          {:keys [source findings]} (cm/rewrite-string src)]
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

(deftest db-multiline-body-preserved
  (testing "a multi-form handler body wraps only the LAST form; earlier forms verbatim"
    (let [src "(rf/reg-event-db :log/it\n  (fn [db _]\n    (js/console.log \"hi\")\n    (assoc db :logged true)))"
          out (rewrite src)]
      ;; side-effecting first form preserved unwrapped
      (is (str/includes? out "(js/console.log \"hi\")"))
      ;; only the final form is wrapped in {:db ...}
      (is (str/includes? out "{:db (assoc db :logged true)}")))))

;; ---------------------------------------------------------------------------
;; reg-event-db — path interceptor metadata preserved
;; ---------------------------------------------------------------------------

(deftest db-path-interceptor-preserved
  (testing "path-interceptor metadata is preserved; the handler returns {:db slice}"
    (let [src "(rf/reg-event-db :counter/inc\n  {:interceptors [(rf/path :counter)]}\n  (fn [db _] (update db :value inc)))"
          {:keys [source findings]} (cm/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      ;; the metadata middle slot survives untouched
      (is (str/includes? source "{:interceptors [(rf/path :counter)]}"))
      (is (str/includes? source "rf/reg-event "))
      (is (str/includes? source "{:keys [db]}"))
      (is (str/includes? source "{:db (update db :value inc)}")))))

;; ---------------------------------------------------------------------------
;; reg-event-db — renamed (non-`db`) first param  (rf2-xhfxcs.15)
;; ---------------------------------------------------------------------------
;; A reg-event-db whose handler binds the db value under a name OTHER than `db`
;; (a path-scoped slice such as `c`) must keep every body reference resolved.
;; Rebinding the param to `{:keys [db]}` while the body still says `c` produced
;; an unresolvable-symbol compile error. The faithful transform rebinds the param
;; `{c :db}` — db value back under its original name — and leaves the body intact.

(deftest db-renamed-param-path-interceptor
  (testing "the bead example: path interceptor + first param `c` -> {c :db}, body untouched"
    (let [src "(reg-event-db :inc {:interceptors [(rf/path :counter)]}\n  (fn [c _] (update c :n inc)))"
          {:keys [source findings]} (cm/rewrite-string src)]
      (is (= :reg-event-db (:form (first findings))))
      (is (= :rewrite (:action (first findings))) "renamed-db param is still a simple, faithful rewrite")
      (is (str/includes? source "(reg-event "))
      (is (not (str/includes? source "reg-event-db")))
      ;; path-interceptor metadata preserved verbatim
      (is (str/includes? source "{:interceptors [(rf/path :counter)]}"))
      ;; param rebinds the db value back under `c`; NOT {:keys [db]}
      (is (str/includes? source "{c :db}"))
      (is (not (str/includes? source "{:keys [db]}")))
      ;; the body keeps using `c` — it now resolves to the db coeffect
      (is (str/includes? source "{:db (update c :n inc)}")))))

(deftest db-renamed-param-no-interceptor
  (testing "renamed first param with no middle slot also binds {state :db}"
    (let [src "(rf/reg-event-db :s/set (fn [state [_ v]] (assoc state :v v)))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{state :db} [_ v]] {:db (assoc state :v v)})"))
      (is (not (str/includes? out "{:keys [db]}"))))))

(deftest db-renamed-param-named-fn
  (testing "renamed first param on a NAMED handler fn rebinds + leaves body intact"
    (let [src "(rf/reg-event-db :x/y (fn handle [c _] (assoc c :ok true)))"
          out (rewrite src)]
      (is (str/includes? out "(fn handle [{c :db} _] {:db (assoc c :ok true)})")))))

(deftest db-renamed-param-multiform-body
  (testing "renamed param with a multi-form body: only LAST form wrapped, refs intact"
    (let [src "(rf/reg-event-db :log/it\n  (fn [c _]\n    (js/console.log \"hi\")\n    (assoc c :logged true)))"
          out (rewrite src)]
      (is (str/includes? out "{c :db}"))
      (is (str/includes? out "(js/console.log \"hi\")"))
      (is (str/includes? out "{:db (assoc c :logged true)}")))))

(deftest db-renamed-param-shadowing-not-over-rewritten
  (testing "a body that REBINDS the renamed symbol in an inner let keeps the inner binding"
    ;; The outer `c` is the db slice; the inner `let` rebinds `c` to a derived
    ;; value. A naive symbol-substitution codemod would rewrite the inner `c`
    ;; references too. The faithful {c :db} rebind touches the BODY not at all, so
    ;; the inner shadowing is preserved exactly as written.
    (let [src "(rf/reg-event-db :shadow\n  (fn [c [_ k]]\n    (let [c (update c :depth inc)]\n      (assoc c :touched k))))"
          {:keys [source findings]} (cm/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      ;; param rebinds the slice under `c`
      (is (str/includes? source "(fn [{c :db} [_ k]]"))
      ;; the inner let + both inner `c` references survive byte-for-byte
      (is (str/includes? source "(let [c (update c :depth inc)]"))
      (is (str/includes? source "(assoc c :touched k)"))
      ;; round-trip: the body text after the param is identical to the original body
      (is (str/includes? source "{:db (let [c (update c :depth inc)]")))))

(deftest db-renamed-param-fn-shadowing-not-over-rewritten
  (testing "an inner (fn [c] ...) shadowing the slice name is preserved untouched"
    (let [src "(rf/reg-event-db :map-it\n  (fn [c _]\n    (update c :xs (fn [c] (map inc c)))))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{c :db} _]"))
      ;; the inner fn rebinding `c` is preserved verbatim — not over-rewritten
      (is (str/includes? out "(fn [c] (map inc c))"))
      (is (str/includes? out "{:db (update c :xs (fn [c] (map inc c)))}")))))

(deftest db-renamed-param-idempotent
  (testing "running the codemod twice over a renamed-param event is a no-op the 2nd time"
    (let [src "(rf/reg-event-db :inc (fn [c _] (update c :n inc)))"
          once (rewrite src)
          twice (rewrite once)]
      (is (= once twice))
      (is (not (str/includes? once "reg-event-db"))))))

(deftest db-named-db-param-still-keys-form
  (testing "the literal `db` first param STILL produces {:keys [db]} (unchanged behaviour)"
    (let [src "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{:keys [db]} _] {:db (update db :count inc)})"))
      (is (not (str/includes? out "{db :db}"))))))

(deftest db-ignored-param-keeps-keys-form
  (testing "an ignored `_` first param keeps the canonical {:keys [db]} (nothing to rebind)"
    (let [src "(rf/reg-event-db :init (fn [_ _] {:count 0 :items []}))"
          out (rewrite src)]
      (is (str/includes? out "(fn [{:keys [db]} _] {:db {:count 0 :items []}})")))))

;; ---------------------------------------------------------------------------
;; reg-event-ctx — always flagged, never rewritten
;; ---------------------------------------------------------------------------

(deftest ctx-flagged-never-rewritten
  (testing "reg-event-ctx is flagged for manual review and left unchanged"
    (let [src "(rf/reg-event-ctx :advanced/thing\n  (fn [ctx] (assoc ctx :rf/skip-handler? true)))"
          {:keys [source findings]} (cm/rewrite-string src)]
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
          {:keys [source findings]} (cm/rewrite-string src)]
      (is (= :reg-event-db (:form (first findings))))
      (is (= :flag (:action (first findings))))
      (is (= :nil-capable (:flag (first findings))))
      (is (= :reg-event (:target (first findings))) "target still suggested")
      (is (= src source)))))

(deftest nil-capable-if-without-else
  (testing "a 2-branch if whose then-arm is db but else is implicit nil -> FLAG"
    ;; (if cond X) with no else is nil-capable.
    (let [src "(rf/reg-event-db :cond/set\n  (fn [db [_ ok?]] (if ok? (assoc db :ok true))))"
          f (only-finding src)]
      (is (= :nil-capable (:flag f))))))

(deftest nil-capable-get
  (testing "a body that is (get db k) can be nil -> FLAG"
    (let [src "(rf/reg-event-db :grab (fn [db [_ k]] (get db k)))"
          f (only-finding src)]
      (is (= :nil-capable (:flag f))))))

(deftest nil-capable-cond
  (testing "a cond with no guaranteed non-nil clause -> FLAG"
    (let [src "(rf/reg-event-db :route\n  (fn [db [_ x]] (cond (= x 1) (assoc db :a 1) (= x 2) (assoc db :b 2))))"
          f (only-finding src)]
      (is (= :nil-capable (:flag f))))))

(deftest nil-capable-and-or
  (testing "and / or bodies short-circuit to a falsey value -> FLAG"
    (let [and-src "(rf/reg-event-db :a (fn [db _] (and (:ready? db) (assoc db :go true))))"
          or-src  "(rf/reg-event-db :o (fn [db _] (or (:cached db) (assoc db :fresh true))))"]
      (is (= :nil-capable (:flag (only-finding and-src))))
      (is (= :nil-capable (:flag (only-finding or-src)))))))

(deftest nil-capable-bare-nil
  (testing "a literal nil body -> FLAG"
    (let [src "(rf/reg-event-db :noop (fn [db _] nil))"
          f (only-finding src)]
      (is (= :nil-capable (:flag f))))))

(deftest nil-capable-some-thread
  (testing "a some-> thread short-circuits to nil -> FLAG"
    (let [src "(rf/reg-event-db :s (fn [db [_ k]] (some-> db (get k) inc)))"
          f (only-finding src)]
      (is (= :nil-capable (:flag f))))))

;; ---------------------------------------------------------------------------
;; non-nil-capable bodies are NOT flagged (the rewrite proceeds)
;; ---------------------------------------------------------------------------

(deftest not-nil-capable-assoc
  (testing "assoc / assoc-in / update / merge / dissoc bodies are non-nil -> rewrite"
    (doseq [body ["(assoc db :x 1)" "(assoc-in db [:a :b] 1)" "(update db :n inc)"
                  "(merge db {:x 1})" "(dissoc db :x)"]]
      (let [src (str "(rf/reg-event-db :id (fn [db _] " body "))")
            f   (only-finding src)]
        (is (= :rewrite (:action f)) (str "expected rewrite for body " body))))))

(deftest not-nil-capable-literal-map
  (testing "a literal-map body (wholesale replace) is non-nil -> rewrite"
    (let [src "(rf/reg-event-db :init (fn [_ _] {:count 0 :items []}))"
          f   (only-finding src)]
      ;; first param _ is a plain symbol token, still simple
      (is (= :rewrite (:action f))))))

;; ---------------------------------------------------------------------------
;; complex reg-event-db — flagged for manual review
;; ---------------------------------------------------------------------------

(deftest complex-var-handler
  (testing "a var/symbol handler (not a literal fn) -> FLAG :complex, unchanged"
    (let [src "(rf/reg-event-db :x/y my-handler-fn)"
          {:keys [source findings]} (cm/rewrite-string src)]
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
            {:keys [source findings]} (cm/rewrite-string src)]
        (is (= 1 (count findings)) (str "one finding for head " head))
        (is (= :rewrite (:action (first findings))))
        ;; the alias/ns is preserved on the renamed symbol
        (let [ns* (when (str/includes? head "/") (subs head 0 (str/index-of head "/")))]
          (if ns*
            (is (str/includes? source (str ns* "/reg-event ")))
            (is (str/includes? source "(reg-event "))))))))

;; ---------------------------------------------------------------------------
;; shape non-corruption + idempotence
;; ---------------------------------------------------------------------------

(deftest non-registrar-code-untouched
  (testing "code with no retired registrar is returned byte-for-byte"
    (let [src "(ns my.app)\n\n(defn foo [x] (inc x))\n\n(rf/reg-sub :s (fn [db _] (:s db)))\n;; a comment\n(rf/reg-fx :my/fx (fn [_] nil))\n"
          {:keys [source findings]} (cm/rewrite-string src)]
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
  (testing "running the codemod twice is a no-op the second time (output has no retired names)"
    (let [src "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))\n(rf/reg-event-fx :todo/add (fn [c e] {:db (:db c)}))"
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
          {:keys [source findings]} (cm/rewrite-string src)]
      (is (= 4 (count findings)))
      (is (= [:rewrite :rename :flag :flag] (mapv :action findings)))
      (is (= [nil nil :ctx :nil-capable] (mapv :flag findings)))
      ;; the flagged forms are left intact in the output
      (is (str/includes? source "reg-event-ctx"))
      (is (str/includes? source "(when true db)")))))

;; ---------------------------------------------------------------------------
;; filesystem entry points
;; ---------------------------------------------------------------------------

(deftest scan-file-roundtrip
  (testing "scan-file + rewrite-file! over a temp file on disk"
    (let [tmp (java.io.File/createTempFile "regevent" ".cljc")]
      (try
        (spit tmp "(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))\n")
        (let [findings (cm/scan-file (.getPath tmp))]
          (is (= 1 (count findings)))
          (is (= (.getPath tmp) (str (:file (first findings))))))
        ;; dry run does NOT write
        (let [{:keys [changed?]} (cm/rewrite-file! (.getPath tmp) {:write? false})
              after (slurp tmp)]
          (is changed?)
          (is (str/includes? after "reg-event-db") "dry run left the file unwritten"))
        ;; write does mutate the file
        (let [{:keys [changed?]} (cm/rewrite-file! (.getPath tmp) {:write? true})
              after (slurp tmp)]
          (is changed?)
          (is (not (str/includes? after "reg-event-db")))
          (is (str/includes? after "{:keys [db]}")))
        (finally (.delete tmp))))))

(deftest scan-paths-recurses-dir
  (testing "scan-paths walks a directory for .clj/.cljc/.cljs sources"
    (let [dir (java.io.File/createTempFile "regdir" "")]
      (.delete dir)
      (.mkdirs dir)
      (try
        (spit (io/file dir "a.cljs") "(rf/reg-event-db :a (fn [db _] (assoc db :x 1)))")
        (spit (io/file dir "b.clj")  "(rf/reg-event-fx :b (fn [c e] {:db (:db c)}))")
        (spit (io/file dir "c.txt")  "(rf/reg-event-db :ignored (fn [db _] db))") ; not a source ext
        (let [findings (cm/scan-paths [(.getPath dir)])]
          (is (= 2 (count findings)) "only .cljs + .clj scanned, .txt ignored")
          (is (= #{:reg-event-db :reg-event-fx} (set (map :form findings)))))
        (finally
          (doseq [f (reverse (file-seq dir))] (.delete f)))))))
