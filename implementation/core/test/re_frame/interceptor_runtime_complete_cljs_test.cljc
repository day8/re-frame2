(ns re-frame.interceptor-runtime-complete-cljs-test
  "EP-0022 Slice C (rf2-0adhqs.3) — runtime completion of the registered-
  interceptor surface. Adversarial coverage for the three pieces:

    1. Standard `:rf.interceptor/path` FULL contract (Spec 002 §Standard
       `:rf.interceptor/path` rules 1-5), especially RULE 4: an unchanged
       focused slice widens back to the ORIGINAL full app-db OBJECT, so the
       frame-commit `identical?` no-op (rf2-ekq28v) survives end-to-end
       through a real dispatch + commit. Plus rule 3 (no-`:db` → no synthetic
       `:db`), rule 5 (changed slice widens), nested paths, root path `[]`,
       and `:rf.error/path-interceptor-bad-path` on a non-vector arg.

    2. `:interceptor-overrides` EXACT-reference matching (Spec 002
       §`:interceptor-overrides`): a bare-keyword override matches a bare ref;
       a parameterized `[id arg]` override matches ONLY the exact reference and
       NOT a sibling `[id other-arg]`; canonical-arg identity; remove + replace;
       and `:rf.error/interceptor-override-invalid` on a malformed key.

    3. `->interceptor` is the internal lowering constructor — NOT public
       authoring (the public form is `reg-interceptor`) — but the constructor
       still produces a working executable interceptor.

  Dual-runtime (.cljc): runs on JVM (`clojure -M:test`) and CLJS
  (`npm run test:cljs`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.std-interceptors :as std]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ===========================================================================
;; PIECE 1 — standard :rf.interceptor/path FULL contract
;; ===========================================================================

(deftest path-noop-preserves-identical-app-db-end-to-end
  (testing "RULE 4: an unchanged focused slice keeps the app-db object identical? end-to-end"
    ;; Seed a non-trivial slice at [:cart].
    (rf/reg-event :cart/seed
      (fn [{:keys [db]} _] {:db (assoc db :cart {:items [:milk :eggs]})}))
    ;; A focused handler that returns its slice UNCHANGED (the same object it
    ;; received) — the canonical no-op focused-handler pattern.
    (rf/reg-event :cart/touch
      {:interceptors [[:rf.interceptor/path [:cart]]]}
      (fn [{:keys [db]} _]
        ;; `db` here is the FOCUSED slice (the value at [:cart]); return it
        ;; unchanged so rule 4 must re-emit the original full app-db object.
        {:db db}))

    (rf/dispatch-sync [:cart/seed])
    (let [before (rf/app-db-value :rf/default)]
      (rf/dispatch-sync [:cart/touch])
      (let [after (rf/app-db-value :rf/default)]
        (is (identical? before after)
            "the frame-commit identical? no-op held: the path interceptor widened the
             unchanged slice back to the ORIGINAL full app-db object, so the commit
             boundary skipped the container write")))))

(deftest path-changed-slice-widens-and-allocates
  (testing "RULE 5: a CHANGED focused slice widens back into app-db at the path"
    (rf/reg-event :cart/seed
      (fn [{:keys [db]} _] {:db (assoc db :cart {:items []})}))
    (rf/reg-event :cart/add
      {:interceptors [[:rf.interceptor/path [:cart]]]}
      (fn [{:keys [db]} [_ sku]]
        ;; `db` is the focused [:cart] slice — mutate it.
        {:db (update db :items conj sku)}))

    (rf/dispatch-sync [:cart/seed])
    (let [before (rf/app-db-value :rf/default)]
      (rf/dispatch-sync [:cart/add :milk])
      (let [after (rf/app-db-value :rf/default)]
        (is (not (identical? before after))
            "a real change produces a new app-db object (the commit wrote)")
        (is (= [:milk] (get-in after [:cart :items]))
            "the changed slice was spliced back into full app-db at [:cart]")))))

(deftest path-no-db-effect-emits-no-synthetic-db
  (testing "RULE 3: a handler emitting NO :db effect produces no synthetic :db write"
    (rf/reg-event :cart/seed
      (fn [{:keys [db]} _] {:db (assoc db :cart {:items [:x]})}))
    (rf/reg-event :cart/peek
      {:interceptors [[:rf.interceptor/path [:cart]]]}
      ;; Returns no :db effect at all (just an empty effect map).
      (fn [{:keys [db]} _] {}))

    (rf/dispatch-sync [:cart/seed])
    (let [before (rf/app-db-value :rf/default)]
      (rf/dispatch-sync [:cart/peek])
      (let [after (rf/app-db-value :rf/default)]
        (is (identical? before after)
            "no :db effect → no synthetic :db → no write → identical app-db")))))

(deftest path-nested-interceptors-compose
  (testing "nested path interceptors stack + unwind correctly"
    (rf/reg-event :nest/seed
      (fn [{:keys [db]} _] {:db (assoc-in db [:a :b] {:n 0})}))
    ;; Outer focuses [:a], inner focuses [:b] (relative to the outer slice).
    (rf/reg-event :nest/inc
      {:interceptors [[:rf.interceptor/path [:a]]
                      [:rf.interceptor/path [:b]]]}
      (fn [{:keys [db]} _]
        ;; `db` is the value at [:a :b].
        {:db (update db :n inc)}))

    (rf/dispatch-sync [:nest/seed])
    (rf/dispatch-sync [:nest/inc])
    (rf/dispatch-sync [:nest/inc])
    (is (= 2 (get-in (rf/app-db-value :rf/default) [:a :b :n]))
        "both path interceptors composed; the inner slice spliced through the outer")))

(deftest path-root-path-focuses-whole-db
  (testing "the root path [] focuses the whole app-db"
    (rf/reg-event :root/seed
      (fn [{:keys [db]} _] {:db (assoc db :seeded? true)}))
    (rf/reg-event :root/assoc
      {:interceptors [[:rf.interceptor/path []]]}
      (fn [{:keys [db]} _]
        ;; db is the whole app-db (root focus).
        {:db (assoc db :root-wrote? true)}))
    (rf/dispatch-sync [:root/seed])
    (rf/dispatch-sync [:root/assoc])
    (let [db (rf/app-db-value :rf/default)]
      (is (and (:seeded? db) (:root-wrote? db))
          "root focus saw + wrote the whole db"))))

(deftest path-root-path-noop-preserves-identical
  (testing "RULE 4 at the root path: an unchanged whole-db focus stays identical?"
    (rf/reg-event :root/seed
      (fn [{:keys [db]} _] {:db (assoc db :x 1)}))
    (rf/reg-event :root/touch
      {:interceptors [[:rf.interceptor/path []]]}
      (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:root/seed])
    (let [before (rf/app-db-value :rf/default)]
      (rf/dispatch-sync [:root/touch])
      (is (identical? before (rf/app-db-value :rf/default))
          "root-path no-op preserved the original object"))))

(deftest path-bad-path-arg-is-structured-error
  (testing ":rf.error/path-interceptor-bad-path for a non-vector path argument"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/path-interceptor-bad-path"
          (rf/reg-event :bad/path
            {:interceptors [[:rf.interceptor/path :not-a-vector]]}
            (fn [{:keys [db]} _] {:db db})))
        "registration resolves the path ref + the factory rejects the non-vector arg")))

;; ===========================================================================
;; PIECE 2 — :interceptor-overrides exact-reference matching
;; ===========================================================================

(deftest override-matches-bare-keyword-ref
  (testing "a bare-keyword override removes the matching bare ref"
    (let [log (atom [])]
      (rf/reg-interceptor :ov/auth
        {:before (fn [ctx] (swap! log conj :auth) ctx)})
      (rf/reg-interceptor :ov/audit
        {:before (fn [ctx] (swap! log conj :audit) ctx)})
      (rf/reg-event :ov/run
        {:interceptors [:ov/auth :ov/audit]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:ov/run] {:interceptor-overrides {:ov/auth nil}})
      (is (= [:audit] @log)
          ":ov/auth removed by the bare-keyword override; :ov/audit still ran"))))

(deftest override-bare-keyword-replaces-with-ref
  (testing "a bare-keyword override REPLACES with another ref"
    (let [log (atom [])]
      (rf/reg-interceptor :ov/real
        {:before (fn [ctx] (swap! log conj :real) ctx)})
      (rf/reg-interceptor :ov/stub
        {:before (fn [ctx] (swap! log conj :stub) ctx)})
      (rf/reg-event :ov/run
        {:interceptors [:ov/real]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:ov/run] {:interceptor-overrides {:ov/real :ov/stub}})
      (is (= [:stub] @log)
          "the :ov/stub ref ran in place of :ov/real"))))

(deftest override-matches-exact-parameterized-ref-not-a-sibling
  (testing "a parameterized [id arg] override matches ONLY the exact reference"
    ;; A logging factory keyed by its arg so we can observe which instances ran.
    (rf/reg-interceptor :ov/tag
      {:factory (fn [tag]
                  {:before (fn [ctx]
                             (update-in ctx [:coeffects :db ::seen]
                                        (fnil conj []) tag))})})
    (rf/reg-event :ov/run
      {:interceptors [[:ov/tag :a]
                      [:ov/tag :b]]}
      (fn [{:keys [db]} _] {:db db}))

    ;; Remove ONLY [:ov/tag :a]; [:ov/tag :b] must survive.
    (rf/dispatch-sync [:ov/run] {:interceptor-overrides {[:ov/tag :a] nil}})
    (let [seen (::seen (rf/app-db-value :rf/default))]
      (is (= [:b] seen)
          "the exact [:ov/tag :a] reference was removed; the sibling [:ov/tag :b] survived"))))

(deftest override-parameterized-does-not-match-different-arg
  (testing "an [id arg] override does NOT match an [id other-arg] reference"
    (rf/reg-interceptor :ov/tag2
      {:factory (fn [tag]
                  {:before (fn [ctx]
                             (update-in ctx [:coeffects :db ::seen2]
                                        (fnil conj []) tag))})})
    (rf/reg-event :ov/run2
      {:interceptors [[:ov/tag2 :keep]]}
      (fn [{:keys [db]} _] {:db db}))

    ;; Override targets a DIFFERENT arg — must not match; the chain is untouched.
    (rf/dispatch-sync [:ov/run2] {:interceptor-overrides {[:ov/tag2 :other] nil}})
    (is (= [:keep] (::seen2 (rf/app-db-value :rf/default)))
        "the override [:ov/tag2 :other] did not match [:ov/tag2 :keep] — it ran")))

(deftest override-parameterized-canonical-arg-identity
  (testing "exact-ref matching is by CANONICAL arg identity (map-key order ignored)"
    (rf/reg-interceptor :ov/cfg
      {:factory (fn [_cfg]
                  {:before (fn [ctx] (assoc-in ctx [:coeffects :db ::cfg-ran?] true))})})
    (rf/reg-event :ov/runc
      {:interceptors [[:ov/cfg {:role :admin :redirect [:login]}]]}
      (fn [{:keys [db]} _] {:db db}))
    ;; Same map, different key insertion order — canonically identical.
    (rf/dispatch-sync [:ov/runc]
                      {:interceptor-overrides {[:ov/cfg {:redirect [:login] :role :admin}] nil}})
    (is (not (::cfg-ran? (rf/app-db-value :rf/default)))
        "the canonically-equal [id arg] override matched + removed the interceptor")))

(deftest override-malformed-key-is-structured-error
  (testing ":rf.error/interceptor-override-invalid for a malformed override key"
    (rf/reg-interceptor :ov/ok {:before identity})
    (rf/reg-event :ov/run3
      {:interceptors [:ov/ok]}
      (fn [{:keys [db]} _] {:db db}))
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/interceptor-override-invalid"
          ;; A string key is neither a keyword id nor an [id arg] ref.
          (rf/dispatch-sync [:ov/run3] {:interceptor-overrides {"not-a-ref" nil}})))))

(deftest override-on-standard-path-by-exact-ref
  (testing "a standard [:rf.interceptor/path …] ref is removable by its exact reference"
    (rf/reg-event :ov/seed
      (fn [{:keys [db]} _] {:db (assoc db :cart {:items []})}))
    ;; A single [:cart]-focused handler. With the path interceptor present, `db`
    ;; is the FOCUSED [:cart] slice (a map) → it `assoc`s :items. With the path
    ;; interceptor REMOVED by the exact-ref override, `db` is the FULL app-db (a
    ;; map containing :cart) → the same assoc writes a TOP-LEVEL :items instead,
    ;; leaving :cart untouched. The two outcomes are distinguishable.
    (rf/reg-event :ov/add
      {:interceptors [[:rf.interceptor/path [:cart]]]}
      (fn [{:keys [db]} _] {:db (assoc db :items [:added])}))
    (rf/dispatch-sync [:ov/seed])
    ;; Remove the [:cart] path interceptor by EXACT reference.
    (rf/dispatch-sync [:ov/add]
                      {:interceptor-overrides {[:rf.interceptor/path [:cart]] nil}})
    (let [db (rf/app-db-value :rf/default)]
      (is (= [:added] (:items db))
          "with the path interceptor removed, the handler saw the FULL db and wrote top-level :items")
      (is (= {:items []} (:cart db))
          "the [:cart] slice was NOT focused/spliced — the exact-ref override removed the path interceptor"))))

;; ===========================================================================
;; PIECE 3 — ->interceptor is internal lowering only (not public authoring)
;; ===========================================================================

(deftest interceptor-lowering-constructor-still-works
  (testing "->interceptor* lowers a descriptor into a working executable interceptor"
    (let [icpt (interceptor/->interceptor*
                 :id     :lower/test
                 :before (fn [ctx] (assoc-in ctx [:coeffects :db ::lowered?] true)))]
      (is (= :lower/test (:id icpt)))
      (is (fn? (:before icpt)))
      ;; EP-0022 reference-only flip (rf2-0adhqs.9): the lowered value is NOT a
      ;; legal chain entry — chains carry refs. Register the lowered value at
      ;; the reg-interceptor boundary (the authoring input accepts a value),
      ;; then reference it by id. The lowering constructor still produces a
      ;; chain-executable value; it just reaches the chain via a ref now.
      (rf/reg-interceptor :lower/test icpt)
      (rf/reg-event :lower/run
        {:interceptors [:lower/test]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:lower/run])
      (is (::lowered? (rf/app-db-value :rf/default))
          "the lowered interceptor ran in the chain (via a registered ref)")))

  (testing "the lowered value is rejected as an INLINE chain entry (reference-only flip)"
    (let [icpt (interceptor/->interceptor*
                 :id     :lower/inline
                 :before identity)]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                            #":rf.error/inline-interceptor-removed"
                            (rf/reg-event :lower/inline-run
                              {:interceptors [icpt]}
                              (fn [{:keys [db]} _] {:db db})))
          "an inline lowered value in a chain is :rf.error/inline-interceptor-removed"))))

(deftest standard-path-built-via-internal-constructor
  (testing "the standard path interceptor is itself built by the internal lowering constructor"
    (let [icpt (std/standard-path-interceptor [:cart])]
      (is (= :rf.interceptor/path (:id icpt)))
      (is (and (fn? (:before icpt)) (fn? (:after icpt)))
          "the internal lowering constructor produced an executable interceptor"))))

(deftest interceptor-public-authoring-is-reg-interceptor
  (testing "reg-interceptor — NOT ->interceptor — is the public authoring surface"
    ;; The public form names + registers the interceptor so it is referenced by id.
    (is (= :pub/authored
           (rf/reg-interceptor :pub/authored {:doc "public form"} {:before identity})))
    (is (some? (rf/handler-meta :interceptor :pub/authored))
        "reg-interceptor produced a registered, addressable program member")
    ;; The manifest demotes ->interceptor / ->interceptor* off the public-
    ;; authoring (front-porch) tier to :internal-public (lowering only); this
    ;; test pins the BEHAVIORAL contract: authoring goes through reg-interceptor,
    ;; while ->interceptor* remains a working internal lowering seam (above).
    (is (var? #'rf/->interceptor*)
        "->interceptor* is retained as the internal lowering constructor")))

;; ===========================================================================
;; PIECE 4 — interceptor-registry resolution-seam coverage gaps
;; (review rf2-0adhqs.11: gcmgio, 4oubli, 0f9vnr, xb3mpk)
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; rf2-gcmgio — resolve-chain's DISPATCH-TIME inline loud-fail + malformed arms
;;
;; The reg-event registration-time guard (events/validate-meta-interceptors!)
;; fires first on the public path, so every existing inline-rejection test hits
;; THAT seam (`:where rf/reg-event`), never the dispatch-time resolve-chain arm.
;; resolve-chain's `(interceptor-value? entry) -> throw-inline-interceptor-removed!`
;; (and the `:else -> throw-invalid-ref!`) arm is a defensive belt-and-braces
;; that is effectively unreachable through reg-event — but it is a live,
;; testable seam when resolve-chain is exercised DIRECTLY (the router calls it at
;; chain assembly). Pin both arms by calling icpt-reg/resolve-chain directly, and
;; assert the dispatch-time `:where rf/resolve-chain` (distinct from reg-event's).
;; ---------------------------------------------------------------------------

(deftest resolve-chain-dispatch-time-inline-value-loud-fails
  (testing "a stale INLINE interceptor value reaching resolve-chain directly fails LOUD"
    (let [inline (interceptor/->interceptor*
                   :id     :stale/inline
                   :before (fn [ctx] ctx)
                   :after  (fn [ctx] ctx))]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                            #":rf.error/inline-interceptor-removed"
                            (icpt-reg/resolve-chain [inline]))
          "resolve-chain rejects an inline value (the dispatch-time seam, not the reg-event guard)")
      ;; The dispatch-time arm stamps :where rf/resolve-chain — distinct from the
      ;; registration-time guard's :where rf/reg-event. This is the whole point of
      ;; the belt-and-braces: a stale inline value that somehow slips past
      ;; registration still loud-fails at chain assembly.
      (let [ex (try (icpt-reg/resolve-chain [inline])
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))]
        (is (some? ex) "resolve-chain threw")
        (is (= :rf.error/inline-interceptor-removed (:rf.error/id (ex-data ex))))
        (is (= 'rf/resolve-chain (:where (ex-data ex)))
            "the dispatch-time :where distinguishes it from the registration-time rf/reg-event seam")
        (is (= inline (:entry (ex-data ex)))
            "the offending inline entry rides the error data")))))

(deftest resolve-chain-dispatch-time-malformed-entry-invalid-ref
  (testing "a structurally-malformed chain entry reaching resolve-chain directly is :rf.error/invalid-interceptor-ref"
    ;; A string is neither a reference (keyword / [id arg] vector), nor an inline
    ;; interceptor value (a map), nor the framework default — it falls to the
    ;; resolve-chain :else arm.
    (let [ex (try (icpt-reg/resolve-chain ["not-a-ref"])
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))]
      (is (some? ex) "resolve-chain threw on the malformed entry")
      (is (= :rf.error/invalid-interceptor-ref (:rf.error/id (ex-data ex)))
          "the :else arm raises :rf.error/invalid-interceptor-ref")
      (is (= "not-a-ref" (:ref (ex-data ex)))
          "the offending entry rides the error data"))))

(deftest resolve-chain-passes-framework-default-untouched
  (testing "the framework default-wrapper (:rf/default? true) passes through resolve-chain untouched"
    ;; The ONE inline value resolve-chain lets through — proves the carve-out the
    ;; inline loud-fail arm sits beside is exercised on the same direct seam.
    (let [default {:id :rf/event-handler :rf/default? true :before identity}
          out     (icpt-reg/resolve-chain [default])]
      (is (= [default] out)
          "the framework default passed through untouched (not rejected as an inline value)"))))

;; ---------------------------------------------------------------------------
;; rf2-4oubli — chain-needs-resolution? predicate's three branches
;;
;; The hot-path predicate the resolution seams use to SKIP the resolve walk for
;; all-default chains. Three branches, all untested:
;;   (a) false for a chain that is ONLY the framework default-wrapper (the
;;       common no-authored-chain shape — the hot-path skip);
;;   (b) true when a REFERENCE is present (resolve-chain must resolve it);
;;   (c) true when a non-framework-default inline VALUE is present (resolve-chain
;;       must walk it to reject it loudly).
;; A miswired predicate (e.g. one that skipped a chain with a stale inline value)
;; would silently bypass the loud-fail — so all three are pinned.
;; ---------------------------------------------------------------------------

(deftest chain-needs-resolution-predicate-branches
  (let [default {:id :rf/event-handler :rf/default? true :before identity}
        inline  (interceptor/->interceptor*
                  :id     :nr/inline
                  :before (fn [ctx] ctx))]
    (testing "(a) false — a chain that is ONLY the framework default needs no resolution (hot-path skip)"
      (is (false? (icpt-reg/chain-needs-resolution? [default]))
          "the all-default chain skips the walk")
      (is (false? (icpt-reg/chain-needs-resolution? []))
          "an empty chain also needs no resolution"))

    (testing "(b) true — a bare-keyword reference forces the walk"
      (is (true? (icpt-reg/chain-needs-resolution? [:some/ref])))
      (is (true? (icpt-reg/chain-needs-resolution? [[:some/factory :arg]]))
          "an [id arg] reference also forces the walk")
      (is (true? (icpt-reg/chain-needs-resolution? [default :some/ref]))
          "a ref alongside the framework default still forces the walk"))

    (testing "(c) true — a non-default inline VALUE forces the walk (so resolve-chain rejects it loudly)"
      (is (true? (icpt-reg/chain-needs-resolution? [inline]))
          "a stale inline value is NOT skipped — resolve-chain must walk it to loud-fail")
      (is (true? (icpt-reg/chain-needs-resolution? [default inline]))
          "an inline value alongside the framework default still forces the walk"))))

;; ---------------------------------------------------------------------------
;; rf2-0f9vnr — resolve-factory's deliberate :rf.error/* propagate-verbatim
;; vs. wrap-as-factory-arity discrimination.
;;
;; resolve-factory catches factory throws and DISCRIMINATES: a factory raising
;; its own structured :rf.error/* ex-info (has :rf.error/id in ex-data)
;; propagates VERBATIM; any other throw is wrapped as
;; :rf.error/interceptor-factory-arity. The existing path-bad-path-arg test only
;; covers the verbatim leg via the STANDARD path factory — leaving the generic
;; discrimination (a CUSTOM factory, both legs) untested.
;; ---------------------------------------------------------------------------

(deftest resolve-factory-wraps-plain-throw-as-factory-arity
  (testing "(a) a custom :factory throwing a PLAIN exception is wrapped as :rf.error/interceptor-factory-arity"
    (rf/reg-interceptor :fac/boom
      {:factory (fn [_arg] (throw (ex-info "kaboom" {:not-an-rf-error true})))})
    (let [ex (try (icpt-reg/resolve-ref [:fac/boom :x])
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))]
      (is (some? ex) "resolve-factory threw")
      (is (= :rf.error/interceptor-factory-arity (:rf.error/id (ex-data ex)))
          "a non-:rf.error throw is wrapped as factory-arity")
      (is (re-find #"kaboom" (:reason (ex-data ex)))
          "the wrapping reason carries the original throw's message"))))

(deftest resolve-factory-propagates-structured-rf-error-verbatim
  (testing "(b) a custom :factory throwing a structured :rf.error/* propagates VERBATIM (generic, not path-specific)"
    ;; A custom error id distinct from :rf.error/path-interceptor-bad-path proves
    ;; the discrimination is generic — any :rf.error/* passes through untouched.
    (rf/reg-interceptor :fac/custom-err
      {:factory (fn [_arg]
                  (throw (ex-info ":rf.error/my-custom-factory-error"
                                  {:rf.error/id :rf.error/my-custom-factory-error
                                   :detail      :verbatim})))})
    (let [ex (try (icpt-reg/resolve-ref [:fac/custom-err :x])
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))]
      (is (some? ex) "resolve-factory threw")
      (is (= :rf.error/my-custom-factory-error (:rf.error/id (ex-data ex)))
          "the structured :rf.error/* propagated VERBATIM — NOT wrapped as factory-arity")
      (is (= :verbatim (:detail (ex-data ex)))
          "the original ex-data survived intact (verbatim propagation)"))))

;; ---------------------------------------------------------------------------
;; rf2-xb3mpk — resolve-factory's final cond :else arm: a :factory whose builder
;; returns a value that is NEITHER a static descriptor NOR an executable
;; interceptor (e.g. a keyword, a number, an empty map {}) — throws
;; :rf.error/interceptor-factory-arity ("returned a value that is neither …").
;; ---------------------------------------------------------------------------

(deftest resolve-factory-non-descriptor-non-value-return-rejected
  (testing "a :factory returning a non-descriptor/non-value is :rf.error/interceptor-factory-arity"
    ;; A keyword return — neither interceptor-value? nor static-descriptor?.
    (rf/reg-interceptor :fac/garbage-kw {:factory (fn [_] :garbage)})
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/interceptor-factory-arity"
                          (icpt-reg/resolve-ref [:fac/garbage-kw :x]))
        "a keyword return falls to the :else arm")

    ;; A number return.
    (rf/reg-interceptor :fac/garbage-num {:factory (fn [_] 42)})
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/interceptor-factory-arity"
                          (icpt-reg/resolve-ref [:fac/garbage-num :x]))
        "a number return falls to the :else arm")

    ;; An EMPTY map {} — a map, but with no :before/:after/:id, so neither
    ;; interceptor-value? nor static-descriptor? — the most adversarial leg.
    (rf/reg-interceptor :fac/garbage-empty {:factory (fn [_] {})})
    (let [ex (try (icpt-reg/resolve-ref [:fac/garbage-empty :x])
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e e))]
      (is (some? ex) "an empty-map return threw")
      (is (= :rf.error/interceptor-factory-arity (:rf.error/id (ex-data ex)))
          "an empty map {} (neither descriptor nor value) falls to the :else arm")
      (is (re-find #"neither a static descriptor" (:reason (ex-data ex)))
          "the reason names the non-descriptor/non-value cause"))))
