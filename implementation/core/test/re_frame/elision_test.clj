(ns re-frame.elision-test
  "Wire elision: `elide-wire-value` against a frame's classification policy,
  declared through `reg-classification` (Spec 015 §Static app-db
  classification).

  ## Posture split

  Elision is production behaviour, so almost every assertion here runs under
  `clojure -M:test:prod-gate`. The exception is
  `:rf.warning/large-value-unschema'd`, the dev-only auto-detect warning. It is
  the size threshold's only observable, because an unschema'd large value is
  never elided, so every assertion that reads it sits in a
  `(when rf.interop/debug-enabled? …)` arm beside an always-on assertion that the
  value rides verbatim."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- install-class!
  "Classify `sensitive` / `large` app-db paths with a `reg-classification`
  whose id derives from the declaration, so repeated calls union."
  [sensitive large]
  (let [decl (cond-> {}
               (seq sensitive) (assoc :sensitive (mapv vec sensitive))
               (seq large)     (assoc :large     (mapv vec large)))]
    (rf/reg-classification (keyword "classified" (str (hash decl))) decl)))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
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
                                                 :reason :classification
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

(deftest destroying-a-frame-forgets-only-its-own-warned-paths
  ;; The warn-once set is per (frame, path): one frame's destroy must not
  ;; re-arm the warning on another, while a same-id successor warns afresh.
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        in     {:user {:photo big}}
        traces (collect-traces! :elision-test/forget)
        warns  #(count (unschema'd-warnings traces))
        egress #(rf.elision/elide-wire-value in {:frame %})]
    (rf/make-frame {:id :elision-test/a})
    (rf/make-frame {:id :elision-test/b})
    (let [outs   [(egress :elision-test/a) (egress :elision-test/b) (egress :elision-test/a)]
          before (warns)
          _      (rf/destroy-frame! :elision-test/b)
          outs   (conj outs (egress :elision-test/a))
          _      (rf/make-frame {:id :elision-test/b})
          outs   (conj outs (egress :elision-test/b))]
      (is (= (repeat 5 in) outs) "schema-less large values ride verbatim on every egress")
      (when rf.interop/debug-enabled?
        (is (= [2 3] [before (warns)])
            "one warning per frame; :b's destroy re-fires nothing on :a, and :b's successor warns afresh")))
    (rf/unregister-listener! :trace :elision-test/forget)))

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

;; A :large subtree with a :sensitive descendant elides behind the ordinary
;; marker, measured over the subtree AFTER its sensitive descendants redact, so
;; no figure on the marker varies with the secret (Spec 015 §No propagation).

(defn- pr-str-bytes [v]
  (count (.getBytes ^String (pr-str v) "UTF-8")))

(deftest nested-axis-large-over-sensitive-elides-after-redaction
  (install-class! [[:a :b]] [[:a]])
  (let [v {:a {:b "TOP-SECRET-TOKEN" :c "public"}}]
    (is (= {:a {:rf.size/large-elided {:path   [:a]
                                       :bytes  (pr-str-bytes {:b :rf/redacted :c "public"})
                                       :type   :map
                                       :reason :classification
                                       :hint   nil
                                       :handle [:rf.elision/at [:a]]}}}
           (rf.elision/elide-wire-value v)))
    (is (= {:a {:b :rf/redacted :c "public"}}
           (rf.elision/elide-wire-value v {:rf.egress/include-large? true}))
        "the size override returns the subtree with only its secret redacted")
    (is (= (pr-str-bytes {:b "TOP-SECRET-TOKEN" :c "public"})
           (get-in (rf.elision/elide-wire-value v {:rf.egress/include-sensitive? true})
                   [:a :rf.size/large-elided :bytes]))
        "a caller that opted out of sensitive redaction is measured over the raw subtree")))

(deftest nested-axis-marker-does-not-vary-with-the-secret
  (install-class! [[:a :b]] [[:a]])
  (let [short-secret {:a {:b "s" :c "public"}}
        long-secret  {:a {:b (apply str (repeat 500 "LONG-SECRET")) :c "public"}}
        opts         {:rf.egress/include-digests? true}
        durable      (rf.elision/elide-wire-value short-secret opts)
        path-walk    #(rf.classification/redact-with-paths % [[:a :b]] [[:a]])]
    (is (string? (get-in durable [:a :rf.size/large-elided :digest]))
        "control: the digest is computed")
    (is (= durable (rf.elision/elide-wire-value long-secret opts)))
    (is (rf.elision/marker? (:a (path-walk short-secret)))
        "control: the transient walker marks the collision node")
    (is (= (path-walk short-secret) (path-walk long-secret)))))

(deftest streamed-cascades-elide-per-element-frame
  ;; A drain walking several frames' bundles in one tick must elide each under
  ;; its own frame's declarations; one operating frame for all would leak. A
  ;; flow's output classification is per frame: only the frame running it.
  (rf/reg-flow ::secret-a {:inputs [[:in]] :output-path [:secret-a] :sensitive [[]]} identity)
  (rf/reg-flow ::secret-b {:inputs [[:in]] :output-path [:secret-b] :sensitive [[]]} identity)
  (rf/make-frame {:id :elision-test/frame-a :flows [::secret-a]})
  (rf/make-frame {:id :elision-test/frame-b :flows [::secret-b]})
  (is (= [{:frame :elision-test/frame-a :secret-a :rf/redacted :secret-b "A-public"}
          {:frame :elision-test/frame-b :secret-a "B-public" :secret-b :rf/redacted}]
         (mapv #(rf.elision/elide-wire-value % {:frame (:frame %)})
               [{:frame :elision-test/frame-a :secret-a "A-private" :secret-b "A-public"}
                {:frame :elision-test/frame-b :secret-a "B-public" :secret-b "B-private"}]))))

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
                                   :reason :classification
                                   :hint   nil
                                   :handle [:rf.elision/at [:big :blob]]}}
           (read-at db [:big :blob]))
        "the marker describes the value read, at its own offset")
    (is (= "xxxx" (read-at db [:big :blob] {:rf.egress/include-large? true})))))

(deftest offset-read-below-a-shadowing-large-ancestor-elides-after-redaction
  (install-class! [[:a :x :secret]] [[:a]])
  (let [db {:a {:x {:secret "TOP-SECRET" :pub "ok"}}}]
    (is (= {:rf.size/large-elided {:path   [:a :x]
                                   :bytes  (pr-str-bytes {:secret :rf/redacted :pub "ok"})
                                   :type   :map
                                   :reason :classification
                                   :hint   nil
                                   :handle [:rf.elision/at [:a :x]]}}
           (read-at db [:a :x]))
        "the marker sits at the read's own offset, measured after redaction")
    (is (= {:secret :rf/redacted :pub "ok"}
           (read-at db [:a :x] {:rf.egress/include-large? true})))
    (is (= [:rf/redacted :rf/redacted]
           [(read-at db [:a :x :secret])
            (read-at db [:a :x :secret] {:rf.egress/include-large? true})])
        "a direct read of the secret redacts, with or without the size override")))

(deftest offset-read-at-a-finer-large-below-a-collision-elides
  (install-class! [[:a :secret]] [[:a] [:a :big]])
  (let [marker (read-at {:a {:secret "TOP-SECRET" :big {:rows (vec (range 100))}}}
                        [:a :big])]
    (is (rf.elision/marker? marker))
    (is (= [:a :big] (get-in marker [:rf.size/large-elided :path])))))

;; The walk rebuilds each map it descends — every map under a declared prefix —
;; and `(empty v)` throws on the JVM for a record, so a record there is rebuilt
;; as a plain map; otherwise egress of any value holding one would throw, the
;; event pipeline's own db projection included.

(defrecord Money [amount currency])

(deftest walk-rebuilds-a-record-as-a-plain-map
  (install-class! [[:price :amount]] [])
  (is (= {:price {:amount :rf/redacted :currency "AUD"}}
         (rf.elision/elide-wire-value {:price (->Money 10 "AUD")}))))

;; Below every declared prefix no declaration can match, so the walk returns the
;; subtree as it found it — no rebuild, so a `:db`-bearing trace on a classified
;; frame allocates nothing there — while the dev size probe still reports a
;; large string inside it.

(deftest walk-returns-a-subtree-no-declared-path-reaches-unchanged
  (install-class! [[:auth :token]] [])
  (let [big      (apply str (repeat 3000 "ABCDEFGH"))
        articles {:list   (mapv (fn [i] {:id i :body (str "body " i)}) (range 200))
                  :price  (->Money 10 "AUD")
                  :recent (list 3 2 1)
                  :draft  big}
        traces   (collect-traces! :elision-test/unreachable)
        out      (rf.elision/elide-wire-value {:auth     {:token "TOP-SECRET" :user "ada"}
                                               :articles articles})]
    (is (identical? articles (:articles out))
        "the undeclared sibling is the input itself — record and list intact")
    (is (= {:token :rf/redacted :user "ada"} (:auth out))
        "the declared path still redacts")
    (when rf.interop/debug-enabled?
      (is (= [{:path [:articles :draft] :bytes 24002}]
             (mapv #(select-keys (:tags %) [:path :bytes]) (unschema'd-warnings traces)))
          "the advisory still reaches a large string the walk returned unchanged"))
    (rf/unregister-listener! :trace :elision-test/unreachable)))
