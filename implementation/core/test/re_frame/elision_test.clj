(ns re-frame.elision-test
  "Wire elision: `elide-wire-value` against a frame's elision registry, seeded
  through the EP-0025 commit-plane classification effect path.

  ## Posture split

  Elision is production behaviour, so almost every assertion here runs under
  `scripts/test-core-prod-gate.sh`. The exception is
  `:rf.warning/large-value-unschema'd`, the dev-only auto-detect warning. It is
  the size threshold's only observable, because an unschema'd large value is
  never elided, so every assertion that reads it sits in a
  `(when rf.interop/debug-enabled? …)` arm beside an always-on assertion that the
  value rides verbatim."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- install-class!
  "Classify `sensitive` / `large` paths on the frame the way a `reg-event`
  returning `:sensitive` / `:large` alongside `:db` does."
  ([sensitive large] (install-class! :rf/default sensitive large))
  ([frame-id sensitive large]
   (let [effects (cond-> {}
                   (seq sensitive) (assoc :sensitive (mapv vec sensitive))
                   (seq large)     (assoc :large     (mapv vec large)))]
     (rf.frame/swap-runtime-db! frame-id
       (fn [rt] (rf.elision/apply-classification-effects rt effects))))))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.elision/clear-warning-cache!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  ;; `config` is a `defonce` that survives `:reload`, so a configure in one
  ;; test would otherwise leak into the next.
  (rf.elision/configure! {:rf.egress/threshold-bytes 16384})
  ;; Egress resolves its frame from the carried scope, so pin :rf/default as
  ;; the ambient frame; the frameless tests unbind it.
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- unschema'd-warnings [traces]
  (filterv #(= :rf.warning/large-value-unschema'd (:operation %)) @traces))

(deftest frame-large-path-emits-marker
  ;; An unrelated sensitive declaration must not suppress the marker, and a
  ;; threshold of 0 governs only the auto-detect warning, never a declared path.
  (install-class! [[:other :token]] [[:user :pdf]])
  (rf/configure! {:elision {:rf.egress/threshold-bytes 0}})
  (let [out    (rf.elision/elide-wire-value {:user {:name "Ada" :pdf "<<5MB-blob>>"}}
                                            {:rf.egress/include-digests? true})
        digest (get-in out [:user :pdf :rf.size/large-elided :digest])]
    (is (re-matches #"sha256:[0-9a-f]{64}" digest))
    (is (= {:user {:name "Ada"
                   :pdf  {:rf.size/large-elided {:path   [:user :pdf]
                                                 :bytes  14
                                                 :type   :string
                                                 :reason :effect
                                                 :hint   nil
                                                 :handle [:rf.elision/at [:user :pdf]]}}}}
           (update-in out [:user :pdf :rf.size/large-elided] dissoc :digest)))))

(deftest unschema'd-large-value-warns-but-does-not-elide
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        in     {:user {:photo big}}
        traces (collect-traces! :elision-test/unschema'd)]
    (is (= [in in] [(rf.elision/elide-wire-value in) (rf.elision/elide-wire-value in)])
        "schema-less large values are not auto-elided, on any pass")
    (when rf.interop/debug-enabled?
      (is (= [{:path [:user :photo] :bytes 24002}]
             (mapv #(select-keys (:tags %) [:path :bytes]) (unschema'd-warnings traces)))
          "one warning per path, however many walks"))
    (rf/unregister-listener! :trace :elision-test/unschema'd)))

(deftest threshold-precedence-and-zero-disables
  ;; API.md §Configure keys: an explicit :rf.egress/threshold-bytes opt beats
  ;; the configured value, and 0 from either source disables auto-detect. The
  ;; 302-byte value sits between the thresholds, so each row's count discriminates.
  (let [s      (apply str (repeat 300 "y"))
        traces (collect-traces! :elision-test/threshold)]
    (doseq [[configured opt warnings] [[100     nil    1]
                                       [50      100000 0]
                                       [1000000 100    1]
                                       [0       nil    0]
                                       [100     0      0]]]
      (rf/configure! {:elision {:rf.egress/threshold-bytes configured}})
      (rf.elision/clear-warning-cache!)
      (reset! traces [])
      (is (= {:a s} (rf.elision/elide-wire-value {:a s} (when opt {:rf.egress/threshold-bytes opt})))
          "the threshold governs the warning, never the walk's output")
      (when rf.interop/debug-enabled?
        (is (= warnings (count (unschema'd-warnings traces)))
            (pr-str {:configured configured :opt opt}))))
    (rf/unregister-listener! :trace :elision-test/threshold)))

(deftest walker-is-idempotent-on-large-marker
  ;; A forwarder that double-projects must not re-mark the marker, whose :bytes
  ;; would then measure the marker rather than the payload.
  (install-class! [] [[:doc :body]])
  (let [once (rf.elision/elide-wire-value {:doc {:body (apply str (repeat 2000 "X"))}})]
    (is (rf.elision/marker? (get-in once [:doc :body])))
    (is (= once (rf.elision/elide-wire-value once)))))

;; A :large subtree with a :sensitive descendant redacts the descendant and
;; emits no marker: a marker's :bytes, :type and digest over a subtree holding
;; the secret would leak it (Spec 015 §No propagation, no taint).

(deftest nested-axis-large-over-sensitive-redacts-not-marks
  (install-class! [[:a :b]] [[:a]])
  (is (= {:a {:b :rf/redacted :c "public"}}
         (rf.elision/elide-wire-value {:a {:b "TOP-SECRET-TOKEN" :c "public"}}
                                      {:rf.egress/include-digests? true}))))

(deftest nested-axis-whole-value-large-over-sensitive-descendant-redacts
  (install-class! [[:b]] [[]])
  (is (= {:b :rf/redacted :other "ok"}
         (rf.elision/elide-wire-value {:b "ROOT-LEVEL-SECRET" :other "ok"}
                                      {:rf.egress/include-digests? true}))))

(deftest streamed-cascades-elide-per-element-frame
  ;; A drain walking several frames' bundles in one tick must elide each under
  ;; its own frame's declarations; one operating frame for all would leak.
  (rf/make-frame {:id :elision-test/frame-a})
  (rf/make-frame {:id :elision-test/frame-b})
  (install-class! :elision-test/frame-a [[:secret-a]] [])
  (install-class! :elision-test/frame-b [[:secret-b]] [])
  (is (= [{:frame :elision-test/frame-a :secret-a :rf/redacted :secret-b "A-public"}
          {:frame :elision-test/frame-b :secret-a "B-public" :secret-b :rf/redacted}]
         (mapv #(rf.elision/elide-wire-value % {:frame (:frame %)})
               [{:frame :elision-test/frame-a :secret-a "A-private" :secret-b "A-public"}
                {:frame :elision-test/frame-b :secret-a "B-public" :secret-b "B-private"}]))))

;; Tool-Pair §"Direct-read privacy posture for sub-cache and get-path": a pair
;; tool routes its `{query-v {:value v :ref-count n}}` sub-cache slice through
;; `elide-wire-value`. The walker matches declarations against the walked path
;; whatever the input shape, so a query-v key is just another segment.

(deftest sub-cache-shape-walker-redacts-declared-sensitive-path
  (let [sub-cache {[:auth/token] {:value {:token "shh-secret"} :ref-count 1}
                   [:cart/total] {:value 42 :ref-count 2}}]
    (rf.elision/swap-elision-slot! :rf/default
      (fn [reg] (assoc reg :sensitive-declarations
                       {[[:auth/token] :value :token] #{{:source :test}}})))
    (is (= {[:auth/token] {:value {:token :rf/redacted} :ref-count 1}
            [:cart/total] {:value 42 :ref-count 2}}
           (rf.elision/elide-wire-value sub-cache)))
    (is (= sub-cache
           (rf.elision/elide-wire-value sub-cache {:rf.egress/include-sensitive? true})))))

(deftest sub-cache-shape-walker-emits-large-marker-on-declared-path
  ;; The marker carries the walked path, so a follow-up get-path can drill in.
  (let [path [[:user/uploaded] :value :pdf]]
    (rf.elision/swap-elision-slot! :rf/default
      (fn [reg] (assoc reg :declarations {path #{{:source :test :hint "Upload preview"}}})))
    (is (= {:path path :hint "Upload preview"}
           (-> (rf.elision/elide-wire-value {[:user/uploaded] {:value {:pdf "<<5MB-blob>>"} :ref-count 1}})
               (get-in [[:user/uploaded] :value :pdf :rf.size/large-elided])
               (select-keys [:path :hint]))))))

(deftest sub-cache-shape-walker-passes-through-when-no-declarations
  (let [sub-cache {[:cart/total] {:value 42 :ref-count 2}
                   [:user/name]  {:value "Ada" :ref-count 1}}]
    (is (= sub-cache (rf.elision/elide-wire-value sub-cache)))))

;; EP-0002: egress resolves its frame from the carried stamp (an explicit
;; `:frame` opt, else the in-effect scope) with no `:rf/default` floor. With no
;; LIVE frame the registry is unreachable, so the whole value fails closed to
;; `:rf/redacted`; `:rf.egress/include-sensitive? true` is the deliberate opt-out.

(deftest frameless-egress-fails-closed
  (binding [rf.frame/*current-frame* nil]
    (is (= :rf/redacted (rf.elision/elide-wire-value {:a 1 :b [2 3]})))))

(deftest frameless-egress-include-sensitive-opt-out
  (binding [rf.frame/*current-frame* nil]
    (is (= {:a 1 :b [2 3]}
           (rf.elision/elide-wire-value {:a 1 :b [2 3]} {:rf.egress/include-sensitive? true})))))

(deftest never-registered-explicit-frame-fails-closed
  ;; Under a live ambient frame, so falling back to it would also show.
  (is (= :rf/redacted
         (rf.elision/elide-wire-value {:a 1} {:frame :elision-test/never-registered}))))

(deftest stale-carried-scope-frame-fails-closed
  ;; A captured async callback can fire after the frame it names is destroyed.
  (rf/make-frame {:id :elision-test/stale})
  (rf.frame/destroy-frame! :elision-test/stale)
  (binding [rf.frame/*current-frame* :elision-test/stale]
    (is (= :rf/redacted (rf.elision/elide-wire-value {:a 1})))))

(deftest explicit-nil-frame-fails-closed-under-a-live-ambient-frame
  ;; `:frame` is read by presence: an absent key borrows the live ambient frame
  ;; (:rf/default, which declares nothing), while {:frame nil} says "no
  ;; governing frame" and must not borrow it.
  (is (= {:profile {:name "Ada"}}
         (rf.elision/elide-wire-value {:profile {:name "Ada"}} {})))
  (is (= :rf/redacted
         (rf.elision/elide-wire-value {:profile {:name "Ada"}} {:frame nil}))))

;; A classification declares an index-free path: `[:items :token]` covers the
;; runtime `[:items 0 :token]`, and `[:by-id :secret]` covers `[:by-id "a" :secret]`.
;; The walker threads candidate declaration coordinates that skip vector indices
;; and map-of keys, position-precisely.

(deftest collection-nested-sensitive-vector-of-maps-redacts
  (install-class! [[:items :token]] [])
  (let [in {:items [{:token "T0" :x 1} {:token "T1" :x 2}]}]
    (is (= {:items [{:token :rf/redacted :x 1} {:token :rf/redacted :x 2}]}
           (rf.elision/elide-wire-value in)))
    (is (= in (rf.elision/elide-wire-value in {:rf.egress/include-sensitive? true})))))

(deftest collection-nested-sensitive-set-of-maps-redacts
  (install-class! [[:tags :s]] [])
  (is (= {:tags #{{:s :rf/redacted}}}
         (rf.elision/elide-wire-value {:tags #{{:s "SECRET"}}}))))

(deftest collection-nested-no-over-redaction-at-non-declared-position
  ;; The empty seed may not skip leading named slots, so the same key sequence
  ;; deeper in the tree is not the declared position.
  (install-class! [[:auth :password]] [])
  (is (= {:auth {:username "ada" :password :rf/redacted}
          :tags {:some-other-slot {:auth {:password "scoped-marker"}}}}
         (rf.elision/elide-wire-value
           {:auth {:username "ada" :password "shh"}
            :tags {:some-other-slot {:auth {:password "scoped-marker"}}}}))))

(deftest collection-nested-map-of-skip-requires-started-match
  ;; Only a candidate that has begun matching may skip a map-of key.
  (install-class! [[:by-id :secret]] [])
  (is (= {:by-id {"a" {:secret :rf/redacted}} :secret "TOP-LEVEL-NOT-DECLARED"}
         (rf.elision/elide-wire-value
           {:by-id {"a" {:secret "SECRET"}} :secret "TOP-LEVEL-NOT-DECLARED"}))))

(deftest collection-nested-literal-index-declaration-redacts
  ;; A declaration may pin a concrete index; only that element redacts.
  (install-class! [[:tokens 0]] [])
  (is (= {:tokens [:rf/redacted "public"] :other "x"}
         (rf.elision/elide-wire-value {:tokens (list "SECRET" "public") :other "x"}))))

(deftest collection-nested-large-emits-marker-with-runtime-path
  ;; The marker names the concrete indexed path, so a follow-up get-path lands.
  (install-class! [] [[:docs :blob]])
  (is (= [:docs 0 :blob]
         (get-in (rf.elision/elide-wire-value {:docs [{:blob "<<5MB-blob>>"}]})
                 [:docs 0 :blob :rf.size/large-elided :path]))))

(deftest collection-nested-sensitive-wins-over-large
  (install-class! [[:vault :k]] [[:vault :k]])
  (is (= {:vault [{:k :rf/redacted}]}
         (rf.elision/elide-wire-value {:vault [{:k "payload"}]}))))

;; `:path` is the ABSOLUTE app-db offset of the walked value (the direct-read
;; `get-path` shape, Spec 015 §Direct reads), so a declaration at or above the
;; offset governs it exactly as it does in a whole-db walk.

(defn- read-at
  "Egress the value at `path` in `db` the way a direct read does: the value
  alone, walked at its absolute offset."
  ([db path] (read-at db path nil))
  ([db path opts]
   (rf.elision/elide-wire-value (get-in db path) (assoc opts :path path))))

(deftest offset-read-below-a-sensitive-declaration-redacts
  (install-class! [[:auth]] [])
  (let [db {:auth {:token "SECRET-TOKEN" :user {:name "bob"}} :public 1}]
    (is (= :rf/redacted (read-at db [:auth])) "a read at the declaration")
    (is (= :rf/redacted (read-at db [:auth :token])) "a read below it")
    (is (= 1 (read-at db [:public])) "an unclassified sibling rides verbatim")
    (is (= :rf/redacted
           (rf/project-egress (get-in db [:auth :token])
                              {:frame             :rf/default
                               :path              [:auth :token]
                               :rf.egress/profile :rf.egress/off-box-tool}))
        "through rf/project-egress under the off-box profile, as a pair get-path calls it")))

(deftest offset-read-through-an-index-obeys-an-index-free-declaration
  (install-class! [[:items :token]] [])
  (let [db {:items [{:token "T0" :x 1} {:token "T1" :x 2}]}]
    (is (= {:token :rf/redacted :x 1} (read-at db [:items 0])))
    (is (= :rf/redacted (read-at db [:items 1 :token])))))

(deftest offset-read-below-a-large-declaration-elides
  (install-class! [] [[:big]])
  (let [db {:big {:blob "xxxx" :n 1}}]
    (is (= {:rf.size/large-elided {:path   [:big :blob]
                                   :bytes  6
                                   :type   :string
                                   :reason :effect
                                   :hint   nil
                                   :handle [:rf.elision/at [:big :blob]]}}
           (read-at db [:big :blob]))
        "the marker describes the value read, at its own offset")
    (is (= "xxxx" (read-at db [:big :blob] {:rf.egress/include-large? true})))))

(deftest offset-read-below-a-shadowing-large-ancestor-redacts-and-never-marks
  (install-class! [[:a :x :secret]] [[:a]])
  (is (= {:secret :rf/redacted :pub "ok"}
         (read-at {:a {:x {:secret "TOP-SECRET" :pub "ok"}}} [:a :x]
                  {:rf.egress/include-digests? true}))))

;; The walk rebuilds each map, and `(empty v)` throws on the JVM for a record,
;; so a record is rebuilt as a plain map; otherwise egress of any value holding
;; one would throw, the event pipeline's own db projection included.

(defrecord Money [amount currency])

(deftest walk-rebuilds-a-record-as-a-plain-map
  (install-class! [[:price :amount]] [])
  (is (= {:price {:amount :rf/redacted :currency "AUD"}}
         (rf.elision/elide-wire-value {:price (->Money 10 "AUD")}))))

(deftest a-record-in-app-db-does-not-reject-db-events-on-a-classified-frame
  (rf/reg-event :elision-test/seed
    (fn [{:keys [db]} _] {:db (assoc db :money (->Money 2 "AUD"))}))
  (rf/reg-event :elision-test/inc
    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  ;; Seed before classifying, so the record is in app-db before the classified
  ;; frame's db projection ever sees it.
  (rf/dispatch-sync [:elision-test/seed])
  (is (record? (:money (rf/app-db-value :rf/default))))
  (install-class! [[:secret]] [])
  (rf/dispatch-sync [:elision-test/inc])
  (is (= 1 (:n (rf/app-db-value :rf/default)))))

(deftest a-handler-error-with-a-record-payload-is-contained
  (rf/reg-event :elision-test/boom (fn [_ _] (throw (ex-info "boom" {}))))
  (is (nil? (try (rf/dispatch-sync [:elision-test/boom {:m (->Money 1 "AUD")}])
                 nil
                 (catch Throwable t t)))))
