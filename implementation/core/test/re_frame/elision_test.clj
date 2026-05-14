(ns re-frame.elision-test
  "Coverage for the size-elision wire-boundary walker (rf2-v9tw2).

  Per Spec API §`rf/elide-wire-value`, Spec 009 §Size elision in
  traces, Spec-Schemas §`:rf/elision-registry` / `:rf/elision-marker`,
  and Conventions §Reserved fx-ids / §Reserved app-db keys.

  The walker is the single normative emission site for the
  `:rf/redacted` privacy sentinel and the `:rf.size/large-elided`
  size marker; this file exercises every nomination path (declared,
  schema, runtime-flagged), the composition rule (sensitive drop
  wins), the threshold knob, the fx-driven entry, the REPL wrappers,
  and the restore-epoch / snapshot-restore correctness."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (reset! elision/threshold-bytes elision/default-threshold-bytes)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  ;; Re-register elision fxs after registrar/clear-all! wiped them.
  (require 're-frame.elision :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- frame-db []
  (rf/get-frame-db :rf/default))

(defn- frame-db-value []
  (frame/frame-app-db-value :rf/default))

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- 1. Walker no-op on small / non-elidable values ----------------------

(deftest walker-noop-on-small-values
  (testing "scalars pass through unmodified"
    (is (= 42        (rf/elide-wire-value 42)))
    (is (= nil       (rf/elide-wire-value nil)))
    (is (= "hello"   (rf/elide-wire-value "hello")))
    (is (= :a-keyword (rf/elide-wire-value :a-keyword)))
    (is (= true      (rf/elide-wire-value true))))

  (testing "small collections pass through unmodified"
    (is (= {:a 1 :b 2}  (rf/elide-wire-value {:a 1 :b 2})))
    (is (= [:x :y :z]   (rf/elide-wire-value [:x :y :z])))
    (is (= #{1 2 3}     (rf/elide-wire-value #{1 2 3}))))

  (testing "nested structure walks but does not elide when nothing declared"
    (let [v {:user {:name "Ada" :age 36}
             :ui   {:open? true}}]
      (is (= v (rf/elide-wire-value v))))))

;; ---- 2. Declared paths emit the size marker -------------------------------

(deftest declared-path-emits-marker
  (testing "declare-large-path! writes the registry slot"
    (rf/declare-large-path! [:user :uploaded-pdf] "Upload preview blob")
    (let [decls (rf/elision-declarations :rf/default)]
      (is (= 1 (count decls)))
      (is (= {:large? true :hint "Upload preview blob" :source :declared}
             (get decls [:user :uploaded-pdf])))))

  (testing "the walker substitutes a :rf.size/large-elided marker at the declared path"
    (rf/declare-large-path! [:user :uploaded-pdf] "Upload preview blob")
    (let [db    {:user {:name "Ada" :uploaded-pdf "<<5MB-blob>>"}}
          out   (rf/elide-wire-value db)
          slot  (get-in out [:user :uploaded-pdf])]
      (is (elision/marker? slot))
      (is (= [:user :uploaded-pdf]
             (get-in slot [:rf.size/large-elided :path])))
      (is (= :string (get-in slot [:rf.size/large-elided :type])))
      (is (= :declared (get-in slot [:rf.size/large-elided :reason])))
      (is (= "Upload preview blob"
             (get-in slot [:rf.size/large-elided :hint])))
      (is (= [:rf.elision/at [:user :uploaded-pdf]]
             (get-in slot [:rf.size/large-elided :handle])))
      (is (pos-int? (get-in slot [:rf.size/large-elided :bytes])))))

  (testing "non-declared siblings ride through unmodified"
    (rf/declare-large-path! [:user :uploaded-pdf])
    (let [db  {:user {:name "Ada" :uploaded-pdf "X" :role :admin}
               :ui   {:open? true}}
          out (rf/elide-wire-value db)]
      (is (= "Ada"   (get-in out [:user :name])))
      (is (= :admin  (get-in out [:user :role])))
      (is (= true    (get-in out [:ui :open?])))
      (is (elision/marker? (get-in out [:user :uploaded-pdf]))))))

;; ---- 3. include-large? bypasses the elision -------------------------------

(deftest include-large-bypasses-elision
  (rf/declare-large-path! [:big])
  (testing "default opts elide"
    (let [out (rf/elide-wire-value {:big "blob"})]
      (is (elision/marker? (get out :big)))))
  (testing ":rf.size/include-large? true passes the value through"
    (let [out (rf/elide-wire-value {:big "blob"}
                                   {:rf.size/include-large? true})]
      (is (= "blob" (get out :big))))))

;; ---- 4. Runtime auto-detect over threshold --------------------------------

(deftest runtime-auto-detect-over-threshold
  (testing "values whose pr-str exceeds threshold are auto-flagged"
    (rf/configure :elision {:rf.size/threshold-bytes 64})
    (let [big   (apply str (repeat 100 "ABCDEFGH"))   ; ~800 bytes
          db    {:user {:photo big}}
          traces (collect-traces! :elision-test/auto-detect)
          out   (rf/elide-wire-value db)]
      (is (elision/marker? (get-in out [:user :photo])))
      (is (= :runtime-flagged
             (get-in out [:user :photo :rf.size/large-elided :reason])))
      (testing "the path is persisted in [:rf/elision :runtime-flagged]"
        (let [rf-flagged (rf/elision-runtime-flagged :rf/default)]
          (is (contains? rf-flagged [:user :photo]))
          (is (pos-int? (get-in rf-flagged [[:user :photo] :bytes])))))
      (testing "the one-shot warning fires"
        (is (some #(= :rf.warning/runtime-large-elision (:operation %)) @traces)))
      (rf/remove-trace-cb! :elision-test/auto-detect))))

(deftest runtime-auto-detect-fires-once-per-path
  (testing "subsequent emits on the same path don't re-emit the warning"
    (rf/configure :elision {:rf.size/threshold-bytes 64})
    (let [big    (apply str (repeat 100 "ABCDEFGH"))
          db     {:photo big}
          traces (collect-traces! :elision-test/auto-once)
          _      (rf/elide-wire-value db)
          n1     (count (filter #(= :rf.warning/runtime-large-elision (:operation %)) @traces))
          _      (rf/elide-wire-value db)
          _      (rf/elide-wire-value db)
          n2     (count (filter #(= :rf.warning/runtime-large-elision (:operation %)) @traces))]
      (is (= 1 n1))
      (is (= 1 n2)
          "warning is one-shot per (path, frame)")
      (rf/remove-trace-cb! :elision-test/auto-once))))

;; ---- 5. Threshold-bytes knob honoured ------------------------------------

(deftest threshold-knob-honoured
  (testing "default 16384 lets a 1KB value pass"
    (let [med (apply str (repeat 100 "ABCDEFGH"))    ; ~800 bytes
          out (rf/elide-wire-value {:m med})]
      (is (= med (get out :m)))))
  (testing "lowering the threshold to 100 elides a 1KB value"
    (rf/configure :elision {:rf.size/threshold-bytes 100})
    (let [med (apply str (repeat 100 "ABCDEFGH"))
          out (rf/elide-wire-value {:m med})]
      (is (elision/marker? (get out :m)))))
  (testing "threshold 0 disables runtime auto-detect"
    (rf/configure :elision {:rf.size/threshold-bytes 0})
    (let [big (apply str (repeat 5000 "ABCDEFGH"))    ; ~40K bytes
          out (rf/elide-wire-value {:b big})]
      (is (= big (get out :b))
          "no declared / schema entry + threshold 0 = no elision"))))

;; ---- 6. Per-call threshold override --------------------------------------

(deftest per-call-threshold-override
  (testing "opts :rf.size/threshold-bytes overrides the process knob"
    (let [med (apply str (repeat 200 "ABCD"))    ; ~800 bytes
          out (rf/elide-wire-value {:m med}
                                   {:rf.size/threshold-bytes 100})]
      (is (elision/marker? (get out :m))))))

;; ---- 7. clear-large-path! removes the declaration ------------------------

(deftest clear-large-path-removes-declaration
  (rf/declare-large-path! [:big] "hint")
  (is (contains? (rf/elision-declarations) [:big]))
  (rf/clear-large-path! [:big])
  (is (not (contains? (rf/elision-declarations) [:big])))
  (testing "after clear, the value rides through"
    (let [out (rf/elide-wire-value {:big "v"})]
      (is (= "v" (get out :big))))))

(deftest clear-also-clears-runtime-flagged
  (rf/configure :elision {:rf.size/threshold-bytes 32})
  (let [big (apply str (repeat 100 "X"))]
    (rf/elide-wire-value {:p big})        ;; populates runtime-flagged
    (is (contains? (rf/elision-runtime-flagged) [:p]))
    (rf/clear-large-path! [:p])
    (is (not (contains? (rf/elision-runtime-flagged) [:p])))))

;; ---- 8. fx-driven entry — :rf.size/declare-large -------------------------

(deftest fx-declare-large-writes-registry
  (rf/reg-event-fx :elision-test/setup
    (fn [_ _]
      {:fx [[:rf.size/declare-large {:path [:user :photo] :hint "avatar"}]]}))
  (rf/dispatch-sync [:elision-test/setup])
  (let [decls (rf/elision-declarations)]
    (is (= {:large? true :hint "avatar" :source :declared}
           (get decls [:user :photo])))))

(deftest fx-clear-removes-registry
  (rf/declare-large-path! [:p] "h")
  (rf/reg-event-fx :elision-test/clear
    (fn [_ _]
      {:fx [[:rf.size/clear {:path [:p]}]]}))
  (rf/dispatch-sync [:elision-test/clear])
  (is (not (contains? (rf/elision-declarations) [:p]))))

(deftest fx-atomic-with-db-write
  (testing "a cascade that writes :db AND declares a path sees both effects"
    (rf/reg-event-fx :elision-test/atomic
      (fn [{:keys [db]} _]
        {:db (assoc db :user/uploaded "BLOB")
         :fx [[:rf.size/declare-large {:path [:user/uploaded] :hint nil}]]}))
    (rf/dispatch-sync [:elision-test/atomic])
    (is (= "BLOB" (get (frame-db-value) :user/uploaded)))
    (is (contains? (rf/elision-declarations) [:user/uploaded]))
    (testing "the walker on the post-cascade db elides the new slot"
      (let [out (rf/elide-wire-value (frame-db-value))]
        (is (elision/marker? (get out :user/uploaded)))))))

;; ---- 9. Composition: sensitive drop wins on both-flagged value ----------

(deftest sensitive-wins-over-large
  (testing "a path declared :large? AND :sensitive? drops to :rf/redacted (sensitive wins)"
    ;; Write the registry slot directly so we can co-flag both predicates.
    ;; The fx-driven path doesn't expose :sensitive? today (rf2-isdwf
    ;; ships that separately); the walker's composition rule is verified
    ;; against the registry-level read.
    (rf/declare-large-path! [:secret-pdf] "private blob")
    (let [c (frame/get-frame-db :rf/default)]
      ;; Mutate in-place: write a :sensitive? slot alongside :large?.
      (adapter/replace-container! c
                                  (assoc-in (adapter/read-container c)
                                            [:rf/elision :declarations [:secret-pdf]]
                                            {:large? true
                                             :sensitive? true
                                             :hint "private blob"
                                             :source :declared})))
    (let [out (rf/elide-wire-value {:secret-pdf "X"})]
      (is (= :rf/redacted (get out :secret-pdf))
          "sensitive sentinel emitted; no :rf.size/large-elided marker")
      (is (not (elision/marker? (get out :secret-pdf)))))))

;; ---- 10. Walker short-circuits inside an elided subtree ------------------

(deftest walker-short-circuits-inside-elided-subtree
  (rf/declare-large-path! [:huge])
  (testing "the walker does not recurse into an elided value"
    ;; Build a deeply nested map; if the walker descended, it would
    ;; elide inner nodes too. With short-circuit, only the top is a
    ;; marker — and the marker carries the absolute path of the
    ;; elision site, not any inner key.
    (let [inner (into {} (for [i (range 50)] [(keyword (str "k" i)) i]))
          v     {:huge inner}
          out   (rf/elide-wire-value v)
          slot  (get out :huge)]
      (is (elision/marker? slot))
      (is (= :map (get-in slot [:rf.size/large-elided :type])))
      (testing "marker body does not embed the original inner map"
        (let [body (:rf.size/large-elided slot)]
          (is (not (contains? body :k0))
              "inner keys did not bubble into the marker body")
          (is (= [:huge] (:path body))
              "marker path is the elision site, not any inner subkey"))))))

;; ---- 11. The marker's :path field is absolute ----------------------------

(deftest marker-path-is-absolute
  (rf/declare-large-path! [:a :b :c])
  (let [v   {:a {:b {:c "X" :d :ok}}}
        out (rf/elide-wire-value v)]
    (is (= [:a :b :c]
           (get-in out [:a :b :c :rf.size/large-elided :path]))
        "marker carries the absolute path, not the elision-site offset")))

;; ---- 12. The marker's :handle round-trips through the path ---------------

(deftest marker-handle-is-fetchable-vector
  (rf/declare-large-path! [:user :uploaded-pdf] "blob")
  (let [out    (rf/elide-wire-value {:user {:uploaded-pdf "X"}})
        handle (get-in out [:user :uploaded-pdf :rf.size/large-elided :handle])]
    (is (elision/handle? handle))
    (is (= [:rf.elision/at [:user :uploaded-pdf]] handle))))

(deftest marker-handle-carries-as-of-epoch
  (rf/declare-large-path! [:big])
  (let [out    (rf/elide-wire-value {:big "X"} {:as-of-epoch 42})
        handle (get-in out [:big :rf.size/large-elided :handle])]
    (is (= [:rf.elision/at [:big] :as-of-epoch 42] handle))))

;; ---- 13. include-digests? gates the :digest slot --------------------------

(deftest include-digests-gates-digest-slot
  (rf/declare-large-path! [:b])
  (testing "default: :digest is absent"
    (let [out (rf/elide-wire-value {:b "X"})]
      (is (not (contains? (get-in out [:b :rf.size/large-elided]) :digest)))))
  (testing "with :rf.size/include-digests? true, :digest is computed (CLJ runtime)"
    (let [out (rf/elide-wire-value {:b "X"}
                                   {:rf.size/include-digests? true})
          digest (get-in out [:b :rf.size/large-elided :digest])]
      (is (string? digest))
      (is (.startsWith ^String digest "sha256:")))))

;; ---- 14. Schema-driven boot population -----------------------------------

(deftest schema-driven-boot-population
  (testing "populate-elision-from-schemas! walks registered app-schemas"
    ;; Register schemas where one path's schema literal carries :large? true.
    (rf/reg-app-schema [:user] [:map {:doc "the user"} [:name :string]])
    (rf/reg-app-schema [:uploaded-pdf]
                       [:map {:large? true :hint "uploaded blob"}
                        [:bytes :string]])
    (rf/reg-app-schema [:csv-rows]
                       [:vector {:large? true} :any])
    (let [populated (rf/populate-elision-from-schemas!)
          decls     (rf/elision-declarations)]
      (is (= 2 (count populated)))
      (is (= #{[:uploaded-pdf] [:csv-rows]} (set populated)))
      (is (= :schema (get-in decls [[:uploaded-pdf] :source])))
      (is (= "uploaded blob" (get-in decls [[:uploaded-pdf] :hint])))
      (is (= :schema (get-in decls [[:csv-rows] :source]))))))

(deftest schema-population-is-idempotent
  (rf/reg-app-schema [:big] [:any {:large? true}])
  (rf/populate-elision-from-schemas!)
  (rf/populate-elision-from-schemas!)
  (let [decls (rf/elision-declarations)]
    (is (= 1 (count decls)))
    (is (= :schema (get-in decls [[:big] :source])))))

(deftest schema-population-preserves-declared-entries
  (testing "a :source :declared entry is NOT overwritten by schema-driven population"
    (rf/declare-large-path! [:foo] "app declared this")
    (rf/reg-app-schema [:foo] [:any {:large? true :hint "schema thinks otherwise"}])
    (rf/populate-elision-from-schemas!)
    (let [d (get (rf/elision-declarations) [:foo])]
      (is (= :declared (:source d)) "declared wins over schema")
      (is (= "app declared this" (:hint d))))))

(deftest schema-population-descends-maybe-wrapper
  (testing "the idiomatic Malli shape `[:maybe [:string {:large? true}]]` claims the slot's path (rf2-b20zm)"
    ;; Per rf2-b20zm: when `:large?` lives inside a `[:maybe inner]`
    ;; wrapper, the walker descends so the inner flag propagates to the
    ;; wrapper's app-db path. Without this descent, idiomatic "optional
    ;; but typed" schemas silently drop the elision claim.
    (rf/reg-app-schema [:user :pdf]
                       [:maybe [:string {:large? true :hint "upload preview"}]])
    (let [populated (rf/populate-elision-from-schemas!)
          decls     (rf/elision-declarations)]
      (is (= [[:user :pdf]] populated))
      (is (= :schema (get-in decls [[:user :pdf] :source])))
      (is (= "upload preview" (get-in decls [[:user :pdf] :hint]))))))

(deftest schema-population-top-level-large-still-works
  (testing "the existing top-level shape `[:string {:large? true}]` is unchanged by rf2-b20zm"
    (rf/reg-app-schema [:user :pdf] [:string {:large? true :hint "blob"}])
    (let [populated (rf/populate-elision-from-schemas!)
          decls     (rf/elision-declarations)]
      (is (= [[:user :pdf]] populated))
      (is (= :schema (get-in decls [[:user :pdf] :source])))
      (is (= "blob" (get-in decls [[:user :pdf] :hint]))))))

(deftest schema-population-maybe-without-large-no-claim
  (testing "`[:maybe [:string]]` (no flag) produces NO declaration"
    ;; Negative control: the wrapper descent must not synthesise a claim
    ;; when the wrapped schema carries no `:large?` flag.
    (rf/reg-app-schema [:user :pdf] [:maybe [:string]])
    (let [populated (rf/populate-elision-from-schemas!)
          decls     (rf/elision-declarations)]
      (is (= [] populated))
      (is (nil? (get decls [:user :pdf]))))))

(deftest schema-population-noop-when-schemas-artefact-absent
  (testing "with no late-bind hook, population is a no-op"
    (let [restore-fn (late-bind/get-fn :schemas/frame-schema-entries)]
      (late-bind/set-fn! :schemas/frame-schema-entries nil)
      (try
        (is (= [] (rf/populate-elision-from-schemas!)))
        (finally
          (late-bind/set-fn! :schemas/frame-schema-entries restore-fn))))))

(deftest schema-population-noop-when-extract-walker-absent
  (testing "with the deep-walker hook absent, population is a no-op (rf2-1vneh)"
    ;; Both required hooks (`:schemas/frame-schema-entries` and
    ;; `:schemas/extract-large-paths-from-schema`) must be present;
    ;; absence of either degrades to a no-op so ports / test fixtures
    ;; that wire one but not the other don't NPE.
    (rf/reg-app-schema [:big] [:any {:large? true}])
    (let [restore-fn (late-bind/get-fn :schemas/extract-large-paths-from-schema)]
      (late-bind/set-fn! :schemas/extract-large-paths-from-schema nil)
      (try
        (is (= [] (rf/populate-elision-from-schemas!)))
        (is (= {} (rf/elision-declarations)))
        (finally
          (late-bind/set-fn! :schemas/extract-large-paths-from-schema restore-fn))))))

;; ---- 14b. Nested `:large?` declarations — rf2-1vneh ----------------------

(deftest schema-population-walks-nested-large
  (testing "a `:large?` slot two levels deep lands at the absolute app-db path (rf2-1vneh)"
    ;; Pre-rf2-1vneh: `populate-elision-from-schemas!` only read top-
    ;; level Malli properties (with a `:maybe` descent), so a `:large?`
    ;; nested inside a `:map` child schema was silently dropped. The
    ;; fix routes through the schemas artefact's deep walker via the
    ;; `:schemas/extract-large-paths-from-schema` late-bind hook.
    (rf/reg-app-schema [:root]
                       [:map
                        [:a [:map [:b {:large? true :hint "two-level"} :string]]]])
    (let [populated (rf/populate-elision-from-schemas!)
          decls     (rf/elision-declarations)]
      (is (= [[:root :a :b]] populated))
      (is (= :schema (get-in decls [[:root :a :b] :source])))
      (is (= "two-level" (get-in decls [[:root :a :b] :hint]))))))

(deftest schema-population-walks-three-level-nested-large
  (testing "the 3-level case from Spec 010 lines 254-262 (rf2-1vneh)"
    ;; Verbatim transcription of the worked example in Spec 010 — the
    ;; deeply-nested `:large?` slot resolves to the full app-db path.
    (rf/reg-app-schema [:root]
                       [:map
                        [:a [:map
                             [:b [:map
                                  [:c {:large? true :hint "deep"} :string]]]]]])
    (let [populated (rf/populate-elision-from-schemas!)
          decls     (rf/elision-declarations)]
      (is (= [[:root :a :b :c]] populated))
      (is (= :schema (get-in decls [[:root :a :b :c] :source])))
      (is (= "deep"  (get-in decls [[:root :a :b :c] :hint]))))))

(deftest schema-population-nested-declared-precedence
  (testing "declared > schema even when the schema path is nested (rf2-1vneh)"
    ;; A path that's BOTH declared and schema-derived must remain
    ;; `:source :declared` after population — declared beats schema.
    (rf/declare-large-path! [:root :a :b] "app declared this")
    (rf/reg-app-schema [:root]
                       [:map
                        [:a [:map [:b {:large? true :hint "schema declared"} :string]]]])
    (rf/populate-elision-from-schemas!)
    (let [d (get (rf/elision-declarations) [:root :a :b])]
      (is (= :declared            (:source d)))
      (is (= "app declared this"  (:hint d))))))

(deftest schema-population-multiple-nested-large
  (testing "every nested `:large?` slot in a single schema is registered (rf2-1vneh)"
    ;; Cover the multi-slot case so we know we don't short-circuit on
    ;; the first hit.
    (rf/reg-app-schema [:root]
                       [:map
                        [:photo  {:large? true :hint "avatar"} :string]
                        [:nested [:map
                                  [:pdf {:large? true :hint "blob"} :string]
                                  [:zip {:large? true} :string]]]])
    (let [populated (rf/populate-elision-from-schemas!)
          decls     (rf/elision-declarations)]
      (is (= #{[:root :photo]
               [:root :nested :pdf]
               [:root :nested :zip]}
             (set populated)))
      (is (= "avatar" (get-in decls [[:root :photo] :hint])))
      (is (= "blob"   (get-in decls [[:root :nested :pdf] :hint])))
      (is (= :schema  (get-in decls [[:root :nested :zip] :source]))))))

;; ---- 15. Snapshot-restore correctness ------------------------------------

(deftest declarations-survive-app-db-snapshot-restore
  (testing "declarations live in app-db; serialising + restoring round-trips them"
    (rf/declare-large-path! [:user :uploaded-pdf] "blob")
    (let [snap (frame-db-value)
          decls-before (rf/elision-declarations)]
      (is (contains? decls-before [:user :uploaded-pdf]))
      ;; Simulate restore: blow away app-db then push the snapshot back.
      (let [container (frame/get-frame-db :rf/default)]
        (adapter/replace-container! container {})
        (is (= {} (rf/elision-declarations))
            "wipe confirmed")
        (adapter/replace-container! container snap)
        (is (= decls-before (rf/elision-declarations))
            "restored snapshot carries the same declarations")
        (let [out (rf/elide-wire-value {:user {:uploaded-pdf "X"}})]
          (is (elision/marker? (get-in out [:user :uploaded-pdf]))))))))

;; ---- 16. Recursive nested walk ------------------------------------------

(deftest walker-recurses-into-children
  (rf/declare-large-path! [:a :b :c])
  (rf/declare-large-path! [:x])
  (let [v   {:a {:b {:c "blob1"} :d :ok} :x "blob2" :y "small"}
        out (rf/elide-wire-value v)]
    (is (elision/marker? (get-in out [:a :b :c])))
    (is (= :ok (get-in out [:a :d])))
    (is (elision/marker? (get out :x)))
    (is (= "small" (get out :y)))))

;; ---- 17. Walker handles vectors with indexed paths -----------------------

(deftest walker-walks-vectors-by-index
  (rf/declare-large-path! [:items 1])
  (let [v   {:items ["a" "b" "c"]}
        out (rf/elide-wire-value v)]
    (is (= "a" (get-in out [:items 0])))
    (is (elision/marker? (get-in out [:items 1])))
    (is (= "c" (get-in out [:items 2])))))

;; ---- 18. registry helpers return empty maps for fresh frames -------------

(deftest registry-helpers-return-empty-maps
  (is (= {} (rf/elision-declarations)))
  (is (= {} (rf/elision-runtime-flagged))))

;; ---- 19. Marker shape carries required fields ----------------------------

(deftest marker-shape-spec-compliance
  (rf/declare-large-path! [:b] "hint")
  (let [out  (rf/elide-wire-value {:b "X"})
        body (get-in out [:b :rf.size/large-elided])]
    (testing "Spec-Schemas §:rf/elision-marker — required fields present"
      (is (contains? body :path))
      (is (contains? body :bytes))
      (is (contains? body :type))
      (is (contains? body :reason))
      (is (contains? body :hint))
      (is (contains? body :handle)))
    (testing "field types per the schema"
      (is (vector? (:path body)))
      (is (int?    (:bytes body)))
      (is (#{:map :vector :set :scalar :string} (:type body)))
      (is (#{:declared :schema :runtime-flagged} (:reason body)))
      (is (vector? (:handle body))))))

;; ---- 20. Boot-time wrapper: declare-large-path! at REPL works ------------

(deftest repl-wrappers-work-outside-event-cycle
  (testing "declare-large-path! without dispatch — REPL/boot-time form"
    (rf/declare-large-path! [:repl])
    (is (contains? (rf/elision-declarations) [:repl])))

  (testing "clear-large-path! without dispatch"
    (rf/declare-large-path! [:repl2])
    (rf/clear-large-path! [:repl2])
    (is (not (contains? (rf/elision-declarations) [:repl2])))))

;; ---- 21. Multiple frames keep isolated registries ------------------------

(deftest registries-are-frame-isolated
  (testing "declarations on :rf/default do not bleed to another frame"
    (frame/reg-frame :elision-test/other {})
    (rf/declare-large-path! [:on-default] nil :rf/default)
    (rf/declare-large-path! [:on-other]   nil :elision-test/other)
    (is (contains? (rf/elision-declarations :rf/default) [:on-default]))
    (is (not (contains? (rf/elision-declarations :rf/default) [:on-other])))
    (is (contains? (rf/elision-declarations :elision-test/other) [:on-other]))
    (is (not (contains? (rf/elision-declarations :elision-test/other) [:on-default])))))
