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
  (trace/clear-listeners!)
  (elision/clear-warning-cache!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  ;; `config` is a `defonce` (survives `:reload`); restore the documented
  ;; default so a configure tweak in one test does not leak into the next.
  (elision/configure! {:rf.size/threshold-bytes 16384})
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! id (fn [ev] (swap! acc conj ev)))
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
    (rf/unregister-listener! :elision-test/unschema'd)))

(deftest unschema'd-large-warning-is-once-per-path
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        traces (collect-traces! :elision-test/once)]
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (is (= 1 (count (filter #(= :rf.warning/large-value-unschema'd
                                (:operation %))
                            @traces))))
    (rf/unregister-listener! :elision-test/once)))

;; ---------------------------------------------------------------------------
;; Runtime size-threshold configuration (rf2-le2qu).
;;
;; Per API.md §Size-elision wire-boundary walker (L507) and §Configure keys
;; (`:elision`), the runtime auto-detect threshold for the
;; `:rf.warning/large-value-unschema'd` advisory is configurable, with
;; normative precedence:
;;
;;   explicit `:rf.size/threshold-bytes` opt  >  `(rf/configure :elision …)`  >  16384
;;
;; A threshold of 0 disables runtime auto-detect (only declared / schema
;; entries elide; the unschema'd-large warning never fires).
;; ---------------------------------------------------------------------------

(defn- count-unschema'd-warnings [traces]
  (count (filter #(= :rf.warning/large-value-unschema'd (:operation %))
                 @traces)))

(deftest default-threshold-is-16384
  ;; A string just under the documented 16384-byte default does not warn;
  ;; one just over does. Pins the default when neither opt nor configure set.
  (let [under   (apply str (repeat 16000 "x"))      ; ~16002 pr-str bytes? -> under cap
        over    (apply str (repeat 20000 "x"))
        traces  (collect-traces! :elision-test/default-thresh)]
    ;; `under` here is genuinely under 16384 bytes once quoted (16000 chars
    ;; + 2 quote bytes = 16002), so no warning.
    (rf/elide-wire-value {:a {:small under}})
    (is (= 0 (count-unschema'd-warnings traces))
        "value under the 16384 default does not trip the auto-detect warning")
    (rf/elide-wire-value {:b {:big over}})
    (is (= 1 (count-unschema'd-warnings traces))
        "value over the 16384 default trips the warning")
    (rf/unregister-listener! :elision-test/default-thresh)))

(deftest configured-threshold-takes-effect
  ;; rf2-le2qu — the IMPL gap: `(rf/configure :elision {:rf.size/threshold-bytes N})`
  ;; must lower (or raise) the runtime auto-detect threshold. A 100-byte
  ;; threshold makes a small string trip the warning that the 16384 default
  ;; would have ignored.
  (let [small  (apply str (repeat 300 "y"))         ; ~302 bytes — under default, over 100
        traces (collect-traces! :elision-test/configured)]
    ;; Baseline: under the default, no warning.
    (rf/elide-wire-value {:a {:s small}})
    (is (= 0 (count-unschema'd-warnings traces))
        "300-byte string is under the 16384 default — no warning")
    ;; Lower the configured threshold; now the same shape warns.
    (rf/configure :elision {:rf.size/threshold-bytes 100})
    (rf/elide-wire-value {:b {:s small}})
    (is (= 1 (count-unschema'd-warnings traces))
        "after (configure :elision {:rf.size/threshold-bytes 100}) the 300-byte string warns")
    (rf/unregister-listener! :elision-test/configured)))

(deftest configure-threshold-reaches-elision-config
  ;; The configure case stores the value where elision reads it — direct
  ;; assertion against the elision config, mirroring the :sub-cache shape
  ;; of configure-test.
  (rf/configure :elision {:rf.size/threshold-bytes 4096})
  (is (= 4096 (:rf.size/threshold-bytes (elision/current-config)))
      "(configure :elision {:rf.size/threshold-bytes N}) reaches the elision config"))

(deftest explicit-opt-wins-over-configured
  ;; Precedence: an explicit `:rf.size/threshold-bytes` on the call wins
  ;; over the configured value. Configure a tiny threshold (would warn),
  ;; then pass a large explicit opt on the call (must NOT warn).
  (let [s      (apply str (repeat 300 "z"))          ; ~302 bytes
        traces (collect-traces! :elision-test/opt-wins)]
    (rf/configure :elision {:rf.size/threshold-bytes 50})
    ;; Configured 50 alone would warn for a 302-byte string; but the
    ;; explicit per-call opt of 100000 raises the bar above it.
    (rf/elide-wire-value {:a {:s s}} {:rf.size/threshold-bytes 100000})
    (is (= 0 (count-unschema'd-warnings traces))
        "explicit :rf.size/threshold-bytes opt (100000) overrides configured (50) — no warning")
    ;; And conversely an explicit small opt wins over a large configured value.
    (rf/configure :elision {:rf.size/threshold-bytes 1000000})
    (rf/elide-wire-value {:b {:s s}} {:rf.size/threshold-bytes 100})
    (is (= 1 (count-unschema'd-warnings traces))
        "explicit :rf.size/threshold-bytes opt (100) overrides configured (1000000) — warns")
    (rf/unregister-listener! :elision-test/opt-wins)))

(deftest threshold-zero-disables-runtime-auto-detect
  ;; Per API.md §Configure keys — "0 disables runtime auto-detect (only
  ;; declared / schema entries elide)". With threshold 0, even a very large
  ;; unschema'd string never trips the warning.
  (let [big    (apply str (repeat 5000 "ABCDEFGH")) ; ~40002 bytes — well over default
        traces (collect-traces! :elision-test/zero)]
    (rf/configure :elision {:rf.size/threshold-bytes 0})
    (rf/elide-wire-value {:a {:big big}})
    (is (= 0 (count-unschema'd-warnings traces))
        "threshold 0 disables runtime auto-detect — no warning even for a 40KB string")
    ;; Sanity: a per-call explicit 0 also disables, overriding a configured non-zero.
    (rf/configure :elision {:rf.size/threshold-bytes 100})
    (rf/elide-wire-value {:b {:big big}} {:rf.size/threshold-bytes 0})
    (is (= 0 (count-unschema'd-warnings traces))
        "explicit threshold-bytes 0 opt disables runtime auto-detect for that call")
    (rf/unregister-listener! :elision-test/zero)))

(deftest configured-threshold-does-not-affect-declared-elision
  ;; The threshold governs ONLY the runtime auto-detect warning for
  ;; unschema'd values — schema-declared `:large?` paths still elide to a
  ;; marker regardless of threshold (including threshold 0).
  (rf/reg-app-schema [:doc] [:string {:large? true :hint "blob"}])
  (rf/populate-elision-from-schemas!)
  (rf/configure :elision {:rf.size/threshold-bytes 0})
  (let [out (rf/elide-wire-value {:doc "x"})]
    (is (elision/marker? (:doc out))
        "schema-declared :large? paths elide independent of the runtime threshold")))

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
