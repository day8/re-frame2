(ns re-frame.elision-test
  "Schema-first wire elision tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (elision/clear-warning-cache!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

(deftest walker-noop-on-small-values
  (is (= 42 (rf/elide-wire-value 42)))
  (is (= "hello" (rf/elide-wire-value "hello")))
  (is (= {:a 1 :b [2 3]} (rf/elide-wire-value {:a 1 :b [2 3]}))))

(deftest schema-large-path-emits-marker
  (rf/reg-app-schema [:user]
                     [:map
                      [:name :string]
                      [:uploaded-pdf {:large? true :hint "Upload preview blob"}
                       :string]])
  (is (= [[:user :uploaded-pdf]]
         (rf/populate-elision-from-schemas!)))
  (let [decls (rf/elision-declarations)
        out   (rf/elide-wire-value
                {:user {:name "Ada" :uploaded-pdf "<<5MB-blob>>"}})
        slot  (get-in out [:user :uploaded-pdf])]
    (is (= {:large? true :source :schema :hint "Upload preview blob"}
           (get decls [:user :uploaded-pdf])))
    (is (elision/marker? slot))
    (is (= [:user :uploaded-pdf]
           (get-in slot [:rf.size/large-elided :path])))
    (is (= :schema (get-in slot [:rf.size/large-elided :reason])))
    (is (= "Upload preview blob"
           (get-in slot [:rf.size/large-elided :hint])))
    (is (= "Ada" (get-in out [:user :name])))))

(deftest include-large-bypasses-schema-elision
  (rf/reg-app-schema [:big] [:string {:large? true}])
  (rf/populate-elision-from-schemas!)
  (is (elision/marker? (:big (rf/elide-wire-value {:big "blob"}))))
  (is (= "blob"
         (:big (rf/elide-wire-value {:big "blob"}
                                    {:rf.size/include-large? true})))))

(deftest unschema'd-large-value-warns-but-does-not-elide
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        traces (collect-traces! :elision-test/unschema'd)
        out    (rf/elide-wire-value {:user {:photo big}})]
    (is (= big (get-in out [:user :photo]))
        "schema-less large values are not auto-elided")
    (let [warnings (filterv #(= :rf.warning/large-value-unschema'd
                                (:operation %))
                            @traces)]
      (is (= 1 (count warnings)))
      (is (= [:user :photo] (get-in (first warnings) [:tags :path])))
      (is (pos-int? (get-in (first warnings) [:tags :bytes])))
      (is (= "Add `{:large? true}` to the schema slot for this path."
             (get-in (first warnings) [:tags :hint]))))
    (rf/remove-trace-cb! :elision-test/unschema'd)))

(deftest unschema'd-large-warning-is-once-per-path
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        traces (collect-traces! :elision-test/once)]
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (is (= 1 (count (filter #(= :rf.warning/large-value-unschema'd
                                (:operation %))
                            @traces))))
    (rf/remove-trace-cb! :elision-test/once)))

(deftest schema-sensitive-path-redacts
  (rf/reg-app-schema [:auth]
                     [:map
                      [:username :string]
                      [:password {:sensitive? true} :string]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:auth {:username "ada"
                                         :password "shh"}})]
    (is (= "ada" (get-in out [:auth :username])))
    (is (= :rf/redacted (get-in out [:auth :password])))
    (is (= "shh"
           (get-in (rf/elide-wire-value
                     {:auth {:password "shh"}}
                     {:rf.size/include-sensitive? true})
                   [:auth :password])))))

(deftest sensitive-wins-over-large
  (rf/reg-app-schema [:secret-pdf]
                     [:string {:large? true
                               :sensitive? true
                               :hint "encrypted blob"}])
  (rf/populate-elision-from-schemas!)
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:secret-pdf "payload"})]
    (is (= :rf/redacted (:secret-pdf out)))
    (is (not (elision/marker? (:secret-pdf out))))))

(deftest marker-options
  (rf/reg-app-schema [:b] [:string {:large? true :hint "hint"}])
  (rf/populate-elision-from-schemas!)
  (let [out    (rf/elide-wire-value {:b "X"}
                                    {:rf.size/include-digests? true
                                     :as-of-epoch 42})
        marker (get-in out [:b :rf.size/large-elided])]
    (is (= [:rf.elision/at [:b] :as-of-epoch 42] (:handle marker)))
    (is (= :string (:type marker)))
    (is (= :schema (:reason marker)))
    (is (string? (:digest marker)))))

(deftest walker-is-idempotent-on-large-marker
  ;; rf2-fq8ep — the walker recognises its own `:rf.size/large-elided`
  ;; marker shape at a `:large?`-declared path and passes it through
  ;; unchanged on a re-projection pass. Without the guard, the marker
  ;; map itself satisfies `(map? v)` at the same declared path on the
  ;; next walk, and the walker substituted a fresh marker whose
  ;; `:bytes` reflected the printed length of the previous marker —
  ;; not the original payload — which broke fingerprint-based dedup
  ;; for forwarder pipelines that double-projected.
  (rf/reg-app-schema [:doc]
                     [:map [:body {:large? true :hint "upload"} :string]])
  (rf/populate-elision-from-schemas!)
  (let [input  {:doc {:body (apply str (repeat 2000 "X"))}}
        once   (rf/elide-wire-value input)
        twice  (rf/elide-wire-value once)
        thrice (rf/elide-wire-value twice)]
    (is (elision/marker? (get-in once [:doc :body]))
        "first pass substitutes a marker at the large slot")
    (is (= once twice)
        "second pass is byte-identical — the walker passed the marker
         through unchanged rather than re-marking it")
    (is (= once thrice)
        "third pass remains byte-identical — large-marker substitution
         is irreversible across passes")))

(deftest walker-idempotence-respects-include-large
  ;; The marker passthrough is gated on the same `:include-large?`
  ;; branch that produces the marker. With `:include-large? true`, the
  ;; walker descends through the marker map (because the substitution
  ;; branch is bypassed) — caller opted in to see the raw payload, so
  ;; the marker is just an opaque map at that point. Pinning so a
  ;; future refactor does not move the guard outside the gate.
  (rf/reg-app-schema [:doc]
                     [:map [:body {:large? true :hint "upload"} :string]])
  (rf/populate-elision-from-schemas!)
  (let [input  {:doc {:body (apply str (repeat 2000 "X"))}}
        once   (rf/elide-wire-value input)
        opened (rf/elide-wire-value once {:rf.size/include-large? true})]
    (is (elision/marker? (get-in once [:doc :body])))
    (is (= once opened)
        ":include-large? true descends into the marker map but the
         marker's structure is unchanged — the walker recurses through
         a map whose only key (`:rf.size/large-elided`) sits at a path
         that is not itself `:large?`-declared")))

(deftest nested-schema-population
  (rf/reg-app-schema [:root]
                     [:map
                      [:a [:map
                           [:b [:map
                                [:c {:large? true :hint "deep"} :string]
                                [:token {:sensitive? true} :string]]]]]])
  (is (= [[:root :a :b :c]]
         (rf/populate-elision-from-schemas!)))
  (is (= [[:root :a :b :token]]
         (rf/populate-sensitive-from-schemas!)))
  (is (= "deep"
         (get-in (rf/elision-declarations)
                 [[:root :a :b :c] :hint]))))

(deftest schema-repopulation-prunes-stale-schema-entries
  (rf/reg-app-schema [:user]
                     [:map [:pdf {:large? true} :string]])
  (rf/populate-elision-from-schemas!)
  (is (contains? (rf/elision-declarations) [:user :pdf]))
  (rf/reg-app-schema [:user] [:map [:pdf :string]])
  (rf/populate-elision-from-schemas!)
  (is (not (contains? (rf/elision-declarations) [:user :pdf]))))

(deftest registries-are-frame-isolated
  (frame/reg-frame :elision-test/other {})
  (rf/reg-app-schema [:blob] [:string {:large? true}] {:frame :rf/default})
  (rf/reg-app-schema [:blob] [:string] {:frame :elision-test/other})
  (rf/populate-elision-from-schemas! :rf/default)
  (rf/populate-elision-from-schemas! :elision-test/other)
  (is (contains? (rf/elision-declarations :rf/default) [:blob]))
  (is (not (contains? (rf/elision-declarations :elision-test/other) [:blob]))))

;; ---------------------------------------------------------------------------
;; Sub-cache direct-read wire-egress posture (rf2-0hert / rf2-vflrg).
;;
;; Per Tool-Pair §"Direct-read privacy posture for sub-cache and get-path",
;; a pair-shaped tool that ships a `sub-cache` surface MUST route the
;; returned `{query-v {:value v :ref-count n}}` map through
;; `elide-wire-value` before egress. The re-frame2-pair-mcp `snapshot` tool's
;; `:sub-cache` slice does this (per `tools/re-frame2-pair-mcp/src/.../tools/snapshot.cljs`).
;;
;; These regressions pin the framework half of the contract: the walker
;; honours sensitive / large declarations against the walked path
;; whatever the input shape — sub-cache-shaped data is no different from
;; app-db-shaped data, the walker just compares the walked path to the
;; declaration table. A future refactor of the walker that special-cases
;; map-shape will break here as well as in production.
;; ---------------------------------------------------------------------------

(deftest sub-cache-shape-walker-redacts-declared-sensitive-path
  ;; The sub-cache slice has shape `{[query-v] {:value v :ref-count n}}`.
  ;; A sensitive declaration whose path matches the walker's reach into
  ;; the cached `:value` redacts to `:rf/redacted`. The declaration path
  ;; uses the actual walked-from-root path the walker traverses (the
  ;; query-v key, then `:value`, then the slot inside the cached
  ;; projection).
  (let [path     [[:auth/token] :value :token]
        frame-id :rf/default
        sub-cache {[:auth/token]   {:value {:token "shh-secret"} :ref-count 1}
                   [:cart/total]   {:value 42 :ref-count 2}}]
    ;; Install the sensitive declaration directly into the live registry
    ;; via the private `swap-registry!` helper — the same code path
    ;; `populate-sensitive-from-schemas!` uses, just without the
    ;; schema-extraction step (sub-cache content has no natural app-
    ;; schema path).
    (#'re-frame.elision/swap-registry!
       frame-id
       (fn [reg]
         (assoc reg :sensitive-declarations
                {path {:sensitive? true :source :test}})))
    (let [out (rf/elide-wire-value sub-cache {:frame frame-id})]
      (is (= :rf/redacted (get-in out [[:auth/token] :value :token]))
          "Declared sensitive path inside the sub-cache `:value` redacts on egress")
      (is (= 42 (get-in out [[:cart/total] :value]))
          "Non-sensitive sub-cache entries pass through unchanged")
      (is (= 1 (get-in out [[:auth/token] :ref-count]))
          ":ref-count metadata is untouched"))
    ;; Opt-in: `:rf.size/include-sensitive? true` passes the raw value
    ;; through — the same escape hatch get-path / snapshot expose at the
    ;; MCP layer.
    (let [out (rf/elide-wire-value sub-cache
                                   {:frame frame-id
                                    :rf.size/include-sensitive? true})]
      (is (= "shh-secret" (get-in out [[:auth/token] :value :token]))
          "include-sensitive? true ⇒ sensitive sub-cache slots pass through verbatim"))))

(deftest sub-cache-shape-walker-emits-large-marker-on-declared-path
  ;; A declared `:large?` path inside a sub-cache `:value` emits the
  ;; `:rf.size/large-elided` marker. The marker's `:path` is the actual
  ;; walked-from-root path so the agent's follow-up `get-path` can drill
  ;; in directly. Mirrors the schema-declared :large? coverage above,
  ;; but with sub-cache-shaped input.
  (let [path     [[:user/uploaded] :value :pdf]
        frame-id :rf/default
        sub-cache {[:user/uploaded] {:value {:pdf "<<5MB-blob>>"} :ref-count 1}}]
    (#'re-frame.elision/swap-registry!
       frame-id
       (fn [reg]
         (assoc reg :declarations
                {path {:large? true :source :test :hint "Upload preview"}})))
    (let [out  (rf/elide-wire-value sub-cache {:frame frame-id})
          slot (get-in out [[:user/uploaded] :value :pdf])]
      (is (elision/marker? slot)
          "Declared large path inside sub-cache `:value` emits the size marker")
      (is (= path (get-in slot [:rf.size/large-elided :path]))
          "Marker carries the actual walked path so the agent can re-fetch")
      (is (= "Upload preview" (get-in slot [:rf.size/large-elided :hint]))))))

(deftest sub-cache-shape-walker-passes-through-when-no-declarations
  ;; The walker is a no-op on sub-cache content with no matching
  ;; declarations — routing the slice through `elide-wire-value` does
  ;; not perturb the wire shape. This pins the "uniform direct-read
  ;; surface, identity for typical content" guarantee.
  (let [sub-cache {[:cart/total] {:value 42 :ref-count 2}
                   [:user/name]  {:value "Ada" :ref-count 1}}]
    (is (= sub-cache (rf/elide-wire-value sub-cache))
        "No declarations ⇒ walker returns the sub-cache shape verbatim")))
