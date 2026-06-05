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
  (flows/reset-flows!)
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
;;   explicit `:rf.size/threshold-bytes` opt  >  `(rf/configure! :elision …)`  >  16384
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
  ;; rf2-le2qu — the IMPL gap: `(rf/configure! :elision {:rf.size/threshold-bytes N})`
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
    (rf/configure! :elision {:rf.size/threshold-bytes 100})
    (rf/elide-wire-value {:b {:s small}})
    (is (= 1 (count-unschema'd-warnings traces))
        "after (configure :elision {:rf.size/threshold-bytes 100}) the 300-byte string warns")
    (rf/unregister-listener! :elision-test/configured)))

(deftest configure-threshold-reaches-elision-config
  ;; The configure case stores the value where elision reads it — direct
  ;; assertion against the elision config, mirroring the :sub-cache shape
  ;; of configure-test.
  (rf/configure! :elision {:rf.size/threshold-bytes 4096})
  (is (= 4096 (:rf.size/threshold-bytes (elision/current-config)))
      "(configure :elision {:rf.size/threshold-bytes N}) reaches the elision config"))

(deftest explicit-opt-wins-over-configured
  ;; Precedence: an explicit `:rf.size/threshold-bytes` on the call wins
  ;; over the configured value. Configure a tiny threshold (would warn),
  ;; then pass a large explicit opt on the call (must NOT warn).
  (let [s      (apply str (repeat 300 "z"))          ; ~302 bytes
        traces (collect-traces! :elision-test/opt-wins)]
    (rf/configure! :elision {:rf.size/threshold-bytes 50})
    ;; Configured 50 alone would warn for a 302-byte string; but the
    ;; explicit per-call opt of 100000 raises the bar above it.
    (rf/elide-wire-value {:a {:s s}} {:rf.size/threshold-bytes 100000})
    (is (= 0 (count-unschema'd-warnings traces))
        "explicit :rf.size/threshold-bytes opt (100000) overrides configured (50) — no warning")
    ;; And conversely an explicit small opt wins over a large configured value.
    (rf/configure! :elision {:rf.size/threshold-bytes 1000000})
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
    (rf/configure! :elision {:rf.size/threshold-bytes 0})
    (rf/elide-wire-value {:a {:big big}})
    (is (= 0 (count-unschema'd-warnings traces))
        "threshold 0 disables runtime auto-detect — no warning even for a 40KB string")
    ;; Sanity: a per-call explicit 0 also disables, overriding a configured non-zero.
    (rf/configure! :elision {:rf.size/threshold-bytes 100})
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
  (rf/configure! :elision {:rf.size/threshold-bytes 0})
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
    ;; via the internal `swap-elision-slot!` helper — the same code path
    ;; `populate-sensitive-from-schemas!` uses, just without the
    ;; schema-extraction step (sub-cache content has no natural app-
    ;; schema path).
    (re-frame.elision/swap-elision-slot!
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
    (re-frame.elision/swap-elision-slot!
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

;; ---------------------------------------------------------------------------
;; Collection-nested schema-declared elision at direct-read egress
;; (rf2-wm9kp).
;;
;; The schema walker emits INDEX-FREE declarations for positional/keyed
;; containers — `[:items] [:vector [:map [:token {:sensitive? true} :string]]]`
;; declares `[:items :token]`, NOT `[:items 0 :token]`; a `:map-of` value
;; `[:by-id] [:map-of :string [:map [:secret {:sensitive? true} :string]]]`
;; declares `[:by-id :secret]`, NOT `[:by-id "a" :secret]`. The wire-elision
;; walker walks a RUNTIME value, so it sees the indexed/keyed paths. Before
;; rf2-wm9kp the walker matched only EXACT concrete runtime paths, so the
;; declaration never matched and the secret crossed the direct-read MCP
;; boundary (`get-app-db` / `get-path` / `snapshot`) RAW. The fix threads a
;; candidate declaration-coordinate set that drops vector indices / map-of
;; keys, so the index-free declaration matches the indexed/keyed runtime
;; path. Mirrors the schema-validation-trace alignment (rf2-g5auo's
;; `schema-sensitive-at?`), but on the runtime value rather than the schema.

(deftest collection-nested-sensitive-vector-of-maps-redacts
  ;; rf2-wm9kp Finding 1 (the headline leak). WITHOUT the fix
  ;; `(rf/elide-wire-value {:items [{:token "SECRET"}]})` returned the
  ;; secret verbatim because decl `[:items :token]` did not match runtime
  ;; `[:items 0 :token]`.
  (rf/reg-app-schema [:items]
                     [:vector [:map [:token {:sensitive? true} :string]]])
  (is (= [[:items :token]] (rf/populate-sensitive-from-schemas!))
      "schema walker declares the index-free path")
  (let [out (rf/elide-wire-value {:items [{:token "SECRET"}
                                          {:token "SECRET2"}]})]
    (is (= :rf/redacted (get-in out [:items 0 :token]))
        "vector-element sensitive slot redacts at direct-read egress")
    (is (= :rf/redacted (get-in out [:items 1 :token]))
        "every vector element redacts, not just index 0"))
  ;; Opt-in escape hatch still passes the raw value (the get-path /
  ;; snapshot `:rf.size/include-sensitive? true` path).
  (is (= "SECRET"
         (get-in (rf/elide-wire-value {:items [{:token "SECRET"}]}
                                      {:rf.size/include-sensitive? true})
                 [:items 0 :token]))
      "include-sensitive? true ⇒ collection-nested sensitive passes raw"))

(deftest collection-nested-sensitive-map-of-redacts
  ;; rf2-wm9kp Finding 1 — `:map-of` value-map sensitive slot. Decl
  ;; `[:by-id :secret]` must match runtime `[:by-id "a" :secret]`.
  (rf/reg-app-schema [:by-id]
                     [:map-of :string [:map [:secret {:sensitive? true} :string]]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:by-id {"a" {:secret "SECRET"}
                                          "b" {:secret "SECRET2"}}})]
    (is (= :rf/redacted (get-in out [:by-id "a" :secret])))
    (is (= :rf/redacted (get-in out [:by-id "b" :secret]))
        "map-of value-map sensitive slot redacts for every key")))

(deftest collection-nested-sensitive-sequential-redacts
  ;; rf2-wm9kp Finding 1 — `:sequential` of maps (the runtime value can
  ;; arrive as a lazy seq / list, not just a vector).
  (rf/reg-app-schema [:logs]
                     [:sequential [:map [:pw {:sensitive? true} :string]]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:logs (list {:pw "SECRET"})})]
    (is (= :rf/redacted (-> out :logs vec (get-in [0 :pw])))
        "sequential-element sensitive slot redacts")))

(deftest collection-nested-sensitive-set-of-maps-redacts
  ;; rf2-wm9kp Finding 1 — `:set` element maps descend at the same base
  ;; path (no positional segment), same as vector/sequential.
  (rf/reg-app-schema [:tags]
                     [:set [:map [:s {:sensitive? true} :string]]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:tags #{{:s "SECRET"}}})]
    (is (= :rf/redacted (:s (first (:tags out))))
        "set-element sensitive slot redacts")))

(deftest collection-nested-sensitive-mixed-map-vector-map-redacts
  ;; rf2-wm9kp Finding 1 — mixed map → vector → map nesting. Decl
  ;; `[:root :rows :pw]` matches runtime `[:root :rows N :pw]`.
  (rf/reg-app-schema [:root]
                     [:map [:rows [:vector [:map [:pw {:sensitive? true} :string]]]]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:root {:rows [{:pw "SECRET"} {:pw "SECRET2"}]}})]
    (is (= :rf/redacted (get-in out [:root :rows 0 :pw])))
    (is (= :rf/redacted (get-in out [:root :rows 1 :pw])))))

(deftest collection-nested-no-over-redaction-of-sibling-slots
  ;; rf2-wm9kp Finding 1 — the matcher must be PRECISE: a non-sensitive
  ;; sibling leaf inside the same collection element map rides verbatim.
  ;; The candidate-path fork must not blanket-redact the element.
  (rf/reg-app-schema [:items]
                     [:vector [:map
                               [:token {:sensitive? true} :string]
                               [:name :string]]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:items [{:token "SECRET" :name "Ada"}]})]
    (is (= :rf/redacted (get-in out [:items 0 :token])))
    (is (= "Ada" (get-in out [:items 0 :name]))
        "sibling non-sensitive slot is NOT over-redacted")))

(deftest collection-nested-no-over-redaction-at-non-declared-position
  ;; rf2-wm9kp follow-up — the candidate-coordinate match must be
  ;; POSITION-PRECISE, not a free-floating suffix/anywhere match. A decl
  ;; `[:auth :password]` must NOT redact the SAME key-sequence
  ;; `:auth :password` when it sits at a DIFFERENT, non-declared position
  ;; (nested under leading named map slots `:tags :some-other-slot`). The
  ;; empty seed candidate must not be allowed to skip the leading named
  ;; slots and resume the declaration deeper in the tree.
  (rf/reg-app-schema [:auth]
                     [:map
                      [:username :string]
                      [:password {:sensitive? true} :string]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value
              {;; the DECLARED position — must redact
               :auth {:username "ada" :password "shh"}
               ;; a coincidentally same-named subtree at a NON-declared
               ;; position — must ride through verbatim
               :tags {:some-other-slot {:auth {:password "scoped-marker"}}}})]
    (is (= :rf/redacted (get-in out [:auth :password]))
        "the declared [:auth :password] position still redacts")
    (is (= "ada" (get-in out [:auth :username]))
        "the non-sensitive sibling at the declared position is untouched")
    (is (= "scoped-marker"
           (get-in out [:tags :some-other-slot :auth :password]))
        "the same :auth :password key-sequence at a DIFFERENT position is
         NOT over-redacted — the match is position-precise, not free-floating")))

(deftest collection-nested-map-of-skip-requires-started-match
  ;; rf2-wm9kp follow-up — the `:map-of`-key SKIP is only granted to a
  ;; candidate that has ALREADY begun matching the declaration (a non-empty
  ;; partial prefix). This pins that the legit map-of path keeps working
  ;; (`[:by-id]` is a non-empty partial match, so it skips the key `"a"`),
  ;; while a leaf at a NON-declared top-level map key never matches.
  (rf/reg-app-schema [:by-id]
                     [:map-of :string [:map [:secret {:sensitive? true} :string]]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value
              {:by-id   {"a" {:secret "SECRET"}}
               ;; `:secret` here is a top-level map slot, NOT under :by-id —
               ;; decl [:by-id :secret] must not float to match it.
               :secret  "TOP-LEVEL-NOT-DECLARED"})]
    (is (= :rf/redacted (get-in out [:by-id "a" :secret]))
        "the declared map-of-nested :secret redacts (skip after started match)")
    (is (= "TOP-LEVEL-NOT-DECLARED" (get out :secret))
        "a same-named leaf at a non-declared position is not over-redacted")))

(deftest collection-nested-literal-index-declaration-redacts
  ;; rf2-wm9kp follow-up — a declaration may carry a CONCRETE integer index
  ;; (`[:tokens 0]`, declared directly against the indexed runtime position
  ;; rather than schema-derived index-free). The candidate-coordinate match
  ;; must still fire for it: the seq/vector descent forks the literal-index
  ;; interpretation `(conj c i)`. Pins the origin behaviour (exact indexed
  ;; path match) that the index-free coordinate threading must not regress
  ;; (the story-mcp derived-tree scrub relies on this exact match).
  (let [frame-id :rf/default]
    (re-frame.elision/swap-elision-slot!
      frame-id
      (fn [reg]
        (assoc reg :sensitive-declarations
               {[:tokens 0] {:sensitive? true :source :test}})))
    ;; seq form (list) — the shape story-mcp's derived-tree scrub relies on
    (let [out (rf/elide-wire-value
                {:tokens (list "SECRET" "public") :other "x"}
                {:frame frame-id})]
      (is (= :rf/redacted (-> out :tokens vec (get 0)))
          "literal-index decl [:tokens 0] redacts the indexed seq element")
      (is (= "public" (-> out :tokens vec (get 1)))
          "the non-declared sibling index rides through verbatim"))
    ;; vector form — same literal-index decl matches the vector element
    (let [out (rf/elide-wire-value
                {:tokens ["SECRET" "public"]}
                {:frame frame-id})]
      (is (= :rf/redacted (get-in out [:tokens 0])))
      (is (= "public" (get-in out [:tokens 1]))
          "only the literally-declared index redacts"))))

(deftest collection-nested-large-emits-marker-with-runtime-path
  ;; rf2-wm9kp Finding 1 (symmetry) — a `:large?` slot nested under a
  ;; collection element map emits the `:rf.size/large-elided` marker, and
  ;; the marker's `:path` is the CONCRETE indexed runtime path so a
  ;; follow-up `get-path` lands on the exact element.
  (rf/reg-app-schema [:docs]
                     [:vector [:map [:blob {:large? true :hint "blob"} :string]]])
  (rf/populate-elision-from-schemas!)
  (let [out  (rf/elide-wire-value {:docs [{:blob "<<5MB-blob>>"}]})
        slot (get-in out [:docs 0 :blob])]
    (is (elision/marker? slot)
        "collection-nested :large? slot emits a size marker")
    (is (= [:docs 0 :blob] (get-in slot [:rf.size/large-elided :path]))
        "marker :path is the concrete indexed runtime path (re-fetchable)")
    (is (= "blob" (get-in slot [:rf.size/large-elided :hint])))))

(deftest collection-nested-sensitive-wins-over-large
  ;; rf2-wm9kp Finding 1 (symmetry) — when a collection-nested slot is
  ;; BOTH `:large?` and `:sensitive?`, sensitive wins (redact, no marker),
  ;; same precedence as the top-level `sensitive-wins-over-large` case.
  (rf/reg-app-schema [:vault]
                     [:vector [:map [:k {:large? true :sensitive? true} :string]]])
  (rf/populate-elision-from-schemas!)
  (rf/populate-sensitive-from-schemas!)
  (let [out  (rf/elide-wire-value {:vault [{:k "payload"}]})
        slot (get-in out [:vault 0 :k])]
    (is (= :rf/redacted slot))
    (is (not (elision/marker? slot))
        "sensitive suppresses the large marker even when nested in a vector")))
