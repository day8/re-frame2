(ns re-frame.ssr.root-manifest-cljs-test
  "Root Manifest v1 (S5-A) — the SUBSET PROPERTY and the wire/discovery
  contract (Spec 011 §Root Manifest v1; schema family Spec 004C §2).

  The load-bearing test in this namespace is
  `unmodified-s1-descriptor-is-a-valid-manifest`. It does NOT assert
  against a hand-transcribed fixture — it drives the SHIPPED S1 emitter
  (`re-frame.ui.compiler.root/root-descriptor`, reached through the same
  `analyze-root` the mount macros use) and feeds the result, byte for
  byte, to the manifest validator. If Root Manifest v1 ever stops being
  a strict superset of Root Descriptor v1, that test goes red against
  the real compiler rather than against my copy of its output.

  `making-an-extension-key-mandatory-breaks-the-subset-property` is the
  RED-BEFORE, made permanent: it runs a deliberately strict validator
  (one that requires an extension key) over the same real descriptors
  and proves it REJECTS them. That is the failure the shipped validator
  must never reproduce — so the guard cannot rot into a tautology.

  Runs on BOTH hosts (`.cljc`, `-cljs-test` ns): `clojure -M:test` from
  `implementation/ssr` and the node runner via `npm run test:cljs`. The
  UI compiler is a TEST-ONLY dependency here — production `re-frame.ssr`
  does not require `re-frame.ui`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.manifest :as manifest]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.root :as root]
            #?(:clj  [clojure.edn]
               :cljs [cljs.reader])))

;; A record defined HERE rather than reached for in some production ns:
;; the rf2-v4foc defect is about any value whose `pr-str` carries a tag
;; the safe reader cannot construct, and a locally-defined record is the
;; smallest honest instance of that class on both hosts.
(defrecord WireProbeRecord [x])

;; ---------------------------------------------------------------------------
;; Driving the REAL S1 descriptor emitter
;; ---------------------------------------------------------------------------

(def resolver
  "Injected resolution stub — the S1b pattern the UI compiler's own
  suites use, so the analyzer runs host-neutrally here."
  (fn [sym]
    (case sym
      frame-root {:fqn 're-frame.ui/frame-root :meta {}}
      app-view   {:fqn 'app.views/app-view
                  :meta {:rf.ui/view true :rf.ui/view-id :app.views/app-view}}
      panel-view {:fqn 'app.views/panel-view
                  :meta {:rf.ui/view true :rf.ui/view-id :app.views/panel-view}}
      nil)))

(defn descriptor*
  "-> a genuine Root Descriptor v1, produced by the shipped compiler."
  ([form] (descriptor* form {}))
  ([form opts]
   (let [e (env/make-env {:host :clj :ns-sym 'app.test :resolver resolver})
         {:keys [ast views plans]} (root/analyze-root e 'ui/mount form)
         {:keys [root-id provenance]} (root/resolve-root-identity
                                       'ui/mount opts views)]
     (root/root-descriptor {:root-id root-id :provenance provenance
                            :views views :plans plans :ast ast}))))

(def real-descriptors
  "One descriptor per shape the S1 emitter can produce — authored and
  derived identity, literal and dynamic props, with and without frame
  plans, and the `render!` descriptor-base that carries NO identity keys
  at all (root-id is nil, so the identity keys are omitted). The subset
  property must hold for every one of them, not just the tidy case."
  (delay
    {:authored-literal-props
     (descriptor* '[app-view {:promo :spring :sizes [1 2]}]
                  {:root-id :page/shop})

     :derived-identity
     (descriptor* '[app-view {}] {})

     :derived-with-disambiguator
     (descriptor* '[app-view {}] {:disambiguator :left})

     :dynamic-props
     (descriptor* '[app-view {:promo current-promo}] {:root-id :page/shop})

     :with-frame-plans
     (descriptor* '[frame-root {:id :shop :initial-events [[:boot]]}
                    [app-view {}]]
                  {:root-id :page/shop})

     :no-mounted-view
     (descriptor* '[:div {} [:span {} "static"]] {:root-id :page/static})

     :render-base-no-identity
     (root/root-descriptor
      (let [e (env/make-env {:host :clj :ns-sym 'app.test :resolver resolver})
            {:keys [ast views plans]} (root/analyze-root
                                       e 'ui/render! '[app-view {}])]
        {:views views :plans plans :ast ast}))}))

;; ---------------------------------------------------------------------------
;; THE SUBSET PROPERTY — the acceptance evidence
;; ---------------------------------------------------------------------------

(deftest unmodified-s1-descriptor-is-a-valid-manifest
  (testing "every shape the shipped S1 emitter produces validates AS a
            Root Manifest v1, with no edit, no upgrade step, no migration"
    (doseq [[shape d] @real-descriptors]
      (is (nil? (manifest/problems d))
          (str "S1 descriptor shape " shape " must be a valid manifest "
               "unchanged; problems: " (pr-str (manifest/problems d))))
      (is (manifest/valid? d) (str shape))
      (is (= d (manifest/validate! 'test d))
          (str shape " — validate! returns the descriptor UNCHANGED; a "
               "manifest is not a rewritten descriptor"))))

  (testing "one version integer governs both shapes — no negotiation"
    (doseq [[shape d] @real-descriptors]
      (is (= manifest/schema-version (:rf.root/schema-version d))
          (str shape " declares the same :rf.root/schema-version as the "
               "manifest that extends it (additive keys never bump it)")))))

(deftest making-an-extension-key-mandatory-breaks-the-subset-property
  (testing "THE RED-BEFORE, kept executable: a validator that requires an
            extension key rejects the very descriptors the shipped
            validator accepts — so the property above is load-bearing,
            not vacuous"
    (letfn [(strict-problems [required m]
              (or (manifest/problems m)
                  (when-not (contains? m required)
                    {:missing required})))]
      (doseq [required [:element-locator :phase :props :identifier-prefix
                        :render-fingerprint :frame-payload-ids]
              [shape d] @real-descriptors]
        (is (= {:missing required} (strict-problems required d))
            (str "if " required " were mandatory, S1 descriptor shape "
                 shape " would FAIL — that is the regression this guards"))
        (is (nil? (manifest/problems d))
            (str "…and the SHIPPED validator still accepts it (" shape ")"))))))

(deftest schema-version-is-the-only-required-key
  (is (nil? (manifest/problems {:rf.root/schema-version 1}))
      "the minimal valid manifest is the version alone")
  (is (= {:missing :schema-version} (manifest/problems {:root-id :page/shop})))
  (is (= {:invalid :schema-version :got 2 :expected 1}
         (manifest/problems {:rf.root/schema-version 2}))
      "a foreign version is rejected — but there is no negotiation or
       upgrade path; v1 is the first version, not a compatibility layer")
  (is (= {:invalid :not-a-map :got (type "nope")}
         (manifest/problems "nope"))))

(deftest unknown-keys-are-ignored-by-readers
  (testing "004C §2 rule 2 — including the dev-only :root-id-provenance an
            S1 descriptor still carries; stripping it is an EMIT duty, not
            a read-side rejection (or a descriptor could not validate)"
    (is (nil? (manifest/problems
               {:rf.root/schema-version 1
                :root-id-provenance     :derived
                :some.future/key        [:whatever]})))))

;; ---------------------------------------------------------------------------
;; Assembly — the manifest EXTENDS, it does not replace
;; ---------------------------------------------------------------------------

(deftest manifest-preserves-every-descriptor-key-verbatim
  (let [d (:authored-literal-props @real-descriptors)
        m (manifest/manifest d {:element-locator    {:id "shop-root"}
                                :props              {:promo :spring}
                                :frame-payload-ids  [:shop :frame/session]
                                :render-fingerprint "rf1-abc"
                                :identifier-prefix  "rf2-page_Sshop-"})]
    (testing "no descriptor key is renamed, retyped, or re-semanticised"
      (doseq [[k v] (dissoc d :root-id-provenance)]
        (is (= v (get m k))
            (str "descriptor key " k " rides the manifest verbatim"))))
    (testing "only the dev-only key is dropped"
      (is (not (contains? m :root-id-provenance)))
      (is (contains? d :root-id-provenance)
          "…and it really was there to drop"))
    (testing "the extension keys land"
      (is (= {:id "shop-root"} (:element-locator m)))
      (is (= {:promo :spring} (:props m)))
      (is (= [:shop :frame/session] (:frame-payload-ids m)))
      (is (= "rf1-abc" (:render-fingerprint m)))
      (is (= "rf2-page_Sshop-" (:identifier-prefix m)))
      (is (= :server (:phase m)) ":phase is always stamped — the only v1 value"))
    (is (nil? (manifest/problems m)))))

(deftest manifest-with-no-render-facts-is-the-descriptor-plus-phase
  (let [d (:derived-identity @real-descriptors)
        m (manifest/manifest d)]
    (is (= (assoc (dissoc d :root-id-provenance) :phase :server) m)
        "absent extension facts are OMITTED, never stubbed with nil")
    (is (nil? (manifest/problems m)))))

(deftest assembly-validates-its-extension-facts
  (let [d (:derived-identity @real-descriptors)]
    (doseq [bad [{:element-locator {:id ""}}
                 {:element-locator {:id 42}}
                 {:element-locator {:selector "#shop"}}
                 {:render-fingerprint 42}
                 {:identifier-prefix :not-a-string}
                 {:frame-payload-ids ["shop"]}]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                   (manifest/manifest 'test d bad))
          (str "ill-formed extension fact fails at ASSEMBLY: " (pr-str bad))))))

;; ---------------------------------------------------------------------------
;; Locators (004C §4) — the closed `{:id string}` vocabulary
;; ---------------------------------------------------------------------------

(deftest host-authored-container-needs-an-id
  (is (= {:id "shop-root"} (manifest/host-locator 'test "shop-root")))
  (doseq [bad [nil "" "   "]]
    (let [data (try (manifest/host-locator 'test bad) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
          (str "host container id " (pr-str bad)))
      (is (= :container-id (get-in data [:missing]))
          "the emitter NEVER synthesises an id onto host-owned markup"))))

(deftest synthesised-locator-inherits-slug-injectivity
  (is (= {:id "rf2-root-page_Sshop"} (manifest/synthesised-locator "page_Sshop")))
  (is (not= (manifest/synthesised-locator "page_Sshop")
            (manifest/synthesised-locator "_V_Kshop_Sapp_Kleft"))
      "distinct slugs ⇒ distinct locators; the slug is injective (004C §1)"))

;; ---------------------------------------------------------------------------
;; Render-time props (004C §5) — fail loud, never truncate
;; ---------------------------------------------------------------------------

(deftest unserialisable-prop-fails-the-render-for-that-root
  (let [d    (:authored-literal-props @real-descriptors)
        data (try (manifest/manifest 'test d {:props {:promo :spring
                                                      :chart-fn (fn [_] 1)}})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core/ExceptionInfo) e
                    (ex-data e)))]
    (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
    (is (= :chart-fn (:unserialisable-prop data))
        "the offending prop is NAMED — a silently truncated manifest would
         hydrate a different tree, not a smaller one"))
  (is (manifest/edn-carryable? {:a [1 "x" :k {:b #{1 2}}]}))
  (is (not (manifest/edn-carryable? {:a [1 {:b (fn [])}]}))
      "opaqueness is detected through nesting"))

;; ---------------------------------------------------------------------------
;; The wire form
;; ---------------------------------------------------------------------------

(deftest wire-form-round-trips
  (let [d (:with-frame-plans @real-descriptors)
        m (manifest/manifest d {:element-locator   {:id "shop-root"}
                                :identifier-prefix "rf2-page_Sshop-"})
        html (manifest/script-html m)]
    (testing "the element carries both marks and no identity in attributes"
      (is (re-find #"^<script type=\"application/edn\" data-rf-root>" html))
      (is (re-find #"</script>$" html))
      (is (not (re-find #"data-rf-root=" html))
          "the marker is BARE — identity lives in the content, spelled once"))
    (testing "the body reads back to the same manifest"
      (let [body (-> html
                     (subs (count "<script type=\"application/edn\" data-rf-root>"))
                     (as-> s (subs s 0 (- (count s) (count "</script>")))))]
        (is (= m (manifest/read-manifest 'test body)))))))

(deftest wire-body-cannot-break-out-of-the-script
  (let [m (manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                             {:props {:bio "</script><img onerror=x>"}})
        html (manifest/script-html m)]
    (is (not (re-find #"(?i)</script" (subs html 0 (- (count html) 9))))
        "the shared EDN script-body escape neutralises the breakout")
    (is (= m (manifest/read-manifest
              'test
              (-> html
                  (subs (count "<script type=\"application/edn\" data-rf-root>"))
                  (as-> s (subs s 0 (- (count s) 9)))))))))

(deftest unreadable-or-foreign-body-fails-loud
  (doseq [body ["{:rf.root/schema-version 1" "@@@" ""]]
    (let [data (try (manifest/read-manifest 'test body) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
          (str "body " (pr-str body) " — a hydrating root never guesses")))))

;; ---------------------------------------------------------------------------
;; THE EXACT ROUND TRIP (rf2-v4foc)
;; ---------------------------------------------------------------------------
;;
;; #6383 shipped a wire whose ACCEPTANCE predicate was not equivalent to
;; its READ. Three ways apart, each measured on both hosts before the fix:
;;
;;   1. a record-valued prop satisfied `map?`, so `edn-carryable?` walked
;;      its entries and said yes — but `pr-str` emits `#my.ns.R{…}` and
;;      the safe reader has no constructor for that tag;
;;   2. `serialise-props` tested only the VALUE half of each entry, so an
;;      opaque KEY (a fn, a host object) emitted `#object[…]`;
;;   3. `read-manifest` took the first form and silently discarded any
;;      trailing text or second form.
;;
;; All three turn an EMIT-time authoring mistake into a CLIENT hydration
;; failure, which is the one thing a fail-loud wire exists to prevent.
;;
;; The property below is `read(write(m)) = m` driven through the SHIPPED
;; `script-html` / `read-manifest` — not a hand-transcribed sample, and
;; not `pr-str` alone: it goes through the real script-body escape, so
;; the escape is part of what is proven.

(def ^:private script-prefix
  "<script type=\"application/edn\" data-rf-root>")

(defn- script-body
  "-> the exact bytes a browser hands back as the script's `.textContent`."
  [html]
  (-> html
      (subs (count script-prefix))
      (as-> s (subs s 0 (- (count s) (count "</script>"))))))

(defn- round-trips?
  "The property, through the shipped emitter and the shipped reader."
  [m]
  (= m (manifest/read-manifest 'test (script-body (manifest/script-html m)))))

(def ^:private wire-values
  "Every value class the manifest wire PROMISES to carry, spelled with
  literals that mean the same thing on both hosts. `nil` appears as a
  VALUE (distinct from an absent key, which the shapes below cover) and
  the string/keyword cases carry the `<` and `</script` sequences the
  body escape has to survive."
  {:nil            nil
   :true           true
   :false          false
   :string         "plain"
   :empty-string   ""
   :unicode        "héllo — ☃"
   :breakout       "</script><img onerror=x>"
   :lt-in-string   "a < b"
   :keyword        :k
   :ns-keyword     :a/b
   :lt-in-keyword  :x<y
   :symbol         'sym
   :ns-symbol      'a/b
   :zero           0
   :negative       -1
   :decimal        1.5
   :whole-double   9.0
   :vector         [1 2 3]
   :list           '(1 2 3)
   :set            #{1 2 3}
   :empty-map      {}
   :empty-vector   []
   :string-keys    {"a" 1 "b" 2}
   :mixed-keys     {:a 1 "b" 2 'c 3}
   :nil-value      {:a nil}
   :nested         {:a {:b [1 #{:c} {"d" :e}]}}})

(deftest every-emitted-manifest-round-trips-exactly
  (testing "rf2-v4foc — the SHIPPED emitter over the SHIPPED S1 descriptor
            shapes: every manifest that reaches the wire reads back EQUAL,
            not merely readable"
    (doseq [[shape d] @real-descriptors]
      (let [m (manifest/manifest d {:element-locator    {:id "shop-root"}
                                    :props              {:promo :spring}
                                    :frame-payload-ids  [:shop :frame/session]
                                    :render-fingerprint "rf1-abc"
                                    :identifier-prefix  "rf2-page_Sshop-"})]
        (is (round-trips? m)
            (str "descriptor shape " shape " must survive the wire exactly")))))

  (testing "…and with NO extension facts at all, so `nil` vs ABSENT is
            covered in both directions"
    (doseq [[shape d] @real-descriptors]
      (is (round-trips? (manifest/manifest d))
          (str "bare manifest for shape " shape))))

  (testing "every value class the wire promises to carry, as a prop value
            and again as a prop KEY where the class can be a key"
    (doseq [[label v] wire-values]
      (let [m (manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                                 {:props {:v v}})]
        (is (round-trips? m) (str "prop value " label " => " (pr-str v))))))

  (testing "a manifest whose props map is keyed by each carryable scalar —
            the KEY half of the entry rides the same wire as the value"
    (doseq [k [:k :a/b 'sym "str" 1 true nil]]
      (let [m (manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                                 {:props {k :v}})]
        (is (round-trips? m) (str "prop key " (pr-str k)))))))

(deftest round-trip-guard-is-load-bearing
  (testing "THE RED-BEFORE for rf2-v4foc, kept executable: re-create the
            defective predicate (the one that tested `map?` before
            `record?`) and prove it ACCEPTS a value whose printed form the
            reader then REJECTS. That gap is the whole bug; the shipped
            predicate must never reproduce it, and this test fails if the
            `record?` arm is ever removed."
    (let [r (->WireProbeRecord 1)
          ;; The PRE-FIX predicate, verbatim in the one respect that
          ;; mattered: `map?` reached before any record test.
          lenient-carryable?
          (fn lenient? [v]
            (cond
              (nil? v) true (boolean? v) true (number? v) true
              (string? v) true (keyword? v) true (symbol? v) true
              (map? v) (every? (fn [[k v']] (and (lenient? k) (lenient? v'))) v)
              (coll? v) (every? lenient? v)
              :else false))]

      (testing "the defective predicate waves the record through"
        (is (true? (lenient-carryable? {:x r}))
            "if this ever goes false the lever is gone and the guard below
             proves nothing"))

      (testing "…yet its printed form does NOT read back — the exact
                asymmetry #6383 shipped"
        (is (not= (pr-str {:x r}) (pr-str (into {} r)))
            "a record does not PRINT as a map, which is why map?-shape was
             the wrong question")
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core/ExceptionInfo)
                     (manifest/read-manifest
                      'test (pr-str {:rf.root/schema-version 1 :props {:x r}})))
            "the safe reader has no constructor for the record's tag"))

      (testing "and the SHIPPED predicate closes it"
        (is (not (manifest/edn-carryable? r)))
        (is (not (manifest/edn-carryable? {:x r})))
        (is (not (manifest/edn-carryable? {:x [1 {:y r}]}))
            "detected through nesting, like every other opaque value")))))

(deftest record-valued-prop-fails-before-emission
  (testing "rf2-v4foc — a record prop is rejected at ASSEMBLY, naming the
            prop, rather than emitting a body the client cannot read"
    (let [data (try (manifest/manifest
                     'test {:rf.root/schema-version 1 :root-id :page/shop}
                     {:props {:promo :spring :row (->WireProbeRecord 1)}})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
      (is (= :row (:unserialisable-prop data)) "the offending prop is NAMED")
      (is (= :value (:unserialisable-half data))))))

(deftest opaque-prop-KEY-fails-before-emission
  (testing "rf2-v4foc — `serialise-props` checked only the value half, so
            an opaque KEY reached `pr-str` and emitted `#object[…]`. Both
            halves are carried, so both halves are validated."
    (let [k    (fn [] 1)
          data (try (manifest/manifest
                     'test {:rf.root/schema-version 1 :root-id :page/shop}
                     {:props {k 1}})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
      (is (= :key (:unserialisable-half data))
          "the diagnostic says WHICH half — a fn key and a fn value are
           different authoring mistakes")))

  (testing "a record used as a prop KEY is caught by the same arm"
    (is (not (manifest/edn-carryable? {(->WireProbeRecord 1) :v})))))

;; ---------------------------------------------------------------------------
;; THE NUMERIC HALF OF THE ROUND TRIP (rf2-v4foc, Codex audit of #6407)
;; ---------------------------------------------------------------------------
;;
;; The round-trip property above is driven by ONE host at a time: the JVM
;; suite emits and reads with `clojure.edn`, the CLJS suite with
;; `cljs.reader`. Every JVM-only numeric form passes THAT test — it is
;; the CROSSING that loses, and no same-host suite can see it. This is
;; why SSR 419/1911, UI 928/16132 and CLJS 10099/49944 were all green
;; while the wire silently changed application-visible hydration props.
;;
;; So the proof below is joined by BYTES rather than by a shared host.
;; `jvm-emitted-body` is the exact script body the shipped emitter
;; produced for `{:props {:x 9007199254740993N}}`. Each host then asserts
;; its OWN half against those same bytes, with the shipped reader:
;;
;;   JVM  reads them back as 9007199254740993N  — the value rendered
;;   CLJS reads them back as 9007199254740992   — a DIFFERENT number
;;
;; One wire, two hosts, two values, no error on either side. That is the
;; defect stated as an executable fact, and it stays executable after the
;; fix: it is *why* the emitter must now refuse the value.

(def ^:private jvm-emitted-body
  "The exact `<script>` body the JVM emitter produced for a bigint prop
  before this fix. Pinned as BYTES because bytes are what actually cross
  — reconstructing it per-host would beg the question the test asks.

  The JVM half below pins the one token that matters
  (`9007199254740993N`) against the live printer, so this literal cannot
  quietly go stale."
  "{:rf.root/schema-version 1, :root-id :page/shop, :phase :server, :props {:x 9007199254740993N}}")

(deftest jvm-emitted-number-reads-back-differently-on-cljs
  (testing "rf2-v4foc — the SAME wire bytes, read by the SHIPPED
            `read-manifest` on each host, yield DIFFERENT hydration props.
            Asserted as an observable VALUE: nothing throws, which is the
            entire danger."
    (let [x (get-in (manifest/read-manifest 'test jvm-emitted-body) [:props :x])]
      #?(:clj
         (do
           (is (= (pr-str 9007199254740993N) "9007199254740993N")
               "the JVM printer really does emit the bigint token pinned in
                `jvm-emitted-body` — if this reds, that literal is stale")
           (is (= 9007199254740993N x)
               "the SERVER reads its own wire back exactly")
           (is (= "9007199254740993N" (pr-str x))))

         :cljs
         (do
           (is (= "9007199254740992" (pr-str x))
               "THE DEFECT: the browser's reader silently rounds the
                server's 9007199254740993 down to 9007199254740992. The app
                hydrates with a number the server never rendered.")
           (is (not= "9007199254740993" (pr-str x))
               "stated the other way round, so the assertion cannot pass by
                naming the value it is supposed to reject"))))))

(deftest numeric-wire-guard-is-load-bearing
  (testing "THE RED-BEFORE for the numeric arm, kept executable in the same
            shape as the `record?` lever above: re-create the predicate
            that said `(number? v) true` and prove it ACCEPTS the very
            value the test above shows the far host mangles."
    (let [lenient-number? (fn [v] (number? v))]
      #?(:clj
         (doseq [v [9007199254740993N 1.5M 1/3 9007199254740993 (float 0.1)]]
           (is (true? (lenient-number? v))
               (str "the defective predicate waves " (pr-str v) " through — if
                     this ever goes false the lever is gone"))
           (is (not (manifest/edn-carryable? v))
               (str "…and the SHIPPED predicate refuses it: " (pr-str v))))
         :cljs
         (let [nan js/NaN]
           (is (true? (lenient-number? nan)))
           (is (not (manifest/edn-carryable? nan))))))))

(deftest only-cross-host-numbers-ride-the-wire
  (testing "rf2-v4foc — what the numeric subset ADMITS. These must keep
            working: narrowing the wire must not narrow ordinary props."
    (doseq [v [0 -1 1 1.5 -1.5 9.0 0.1
               manifest/max-safe-integer
               (- manifest/max-safe-integer)
               #?(:clj 1.0E308 :cljs 1e308)]]
      (is (manifest/edn-carryable? v) (str "must still carry " (pr-str v))))

    (testing "…including through nesting and as a prop KEY"
      (is (manifest/edn-carryable? {:a [1 {:b #{2 3.5}}]}))
      (is (manifest/edn-carryable? {1 :v}))))

  (testing "what it REFUSES, and why each one is not merely pedantic"
    (is (not (manifest/edn-carryable? #?(:clj Double/NaN :cljs js/NaN)))
        "NaN is excluded by the round-trip property itself — it is not `=`
         to itself, so it cannot read back EQUAL on any host")

    #?(:clj
       (do
         (is (not (manifest/edn-carryable? 9007199254740993N))
             "bigint: prints an `N` token the browser reproduces as a
              different number")
         (is (not (manifest/edn-carryable? 1.5M))
             "bigdec: prints an `M` token the browser reads as a plain
              number, changing the prop's TYPE")
         (is (not (manifest/edn-carryable? 1/3))
             "ratio: the browser reads `1/3` as 0.3333333333333333 — an
              APPROXIMATION, silently")
         (is (not (manifest/edn-carryable? (inc manifest/max-safe-integer)))
             "a Long one past 2^53-1 carries precision no double holds")
         (is (not (manifest/edn-carryable? (- (inc manifest/max-safe-integer))))
             "…and the bound is symmetric about zero")
         (is (not (manifest/edn-carryable? (float 0.1)))
             "float: fails the property on the JVM ALONE — the printed
              shortest-decimal names the DOUBLE 0.1, so it does not read
              back equal even here")))

    (testing "a large DOUBLE is admitted though it dwarfs the integer
              bound — the bound is about representability, not magnitude"
      (is (manifest/edn-carryable? #?(:clj 1.0E308 :cljs 1e308))))))

#?(:clj
   (deftest non-carryable-number-fails-before-emission
     (testing "rf2-v4foc — a JVM-only number is refused at ASSEMBLY naming
               the prop, and at the EMISSION gate naming the manifest key,
               exactly as an opaque value is. Not a second mechanism —
               the same `edn-carryable?`, told the truth about numbers."
       (let [data (try (manifest/manifest
                        'test {:rf.root/schema-version 1 :root-id :page/shop}
                        {:props {:promo :spring :ratio 1/3}})
                       nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
             "the EXISTING error id — a non-carryable number is not a new
              failure kind, so it needs no new catalogue row")
         (is (= :ratio (:unserialisable-prop data)) "the offending prop is NAMED")
         (is (= :value (:unserialisable-half data))))

       (testing "and the emission gate covers a DESCRIPTOR key too, which
                 `serialise-props` never sees"
         (let [data (try (manifest/script-html
                          {:rf.root/schema-version 1
                           :root-id                :page/shop
                           :static-props           {:limit 9007199254740993N}})
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
           (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
           (is (= :static-props (:unserialisable-manifest-key data)))))

       (testing "the bigint that started this: `script-html` no longer
                 emits the body the CLJS reader mangles"
         (is (thrown? clojure.lang.ExceptionInfo
                      (manifest/script-html
                       (assoc {:rf.root/schema-version 1 :root-id :page/shop}
                              :props {:x 9007199254740993N}))))))))

(deftest script-emission-gates-the-whole-manifest
  (testing "rf2-v4foc — `serialise-props` screens `:props` at assembly, but
            `script-html` is the only door onto the wire and DESCRIPTOR
            keys ride through it too. A non-carryable `:static-props` was
            emitted happily before this gate; the property `every manifest
            accepted for script emission round-trips` is only true if the
            gate is at emission."
    (doseq [k [:static-props :props-shape :frame-plans :root-id]]
      (let [m    (assoc {:rf.root/schema-version 1 :root-id :page/shop}
                        k {:x (->WireProbeRecord 1)})
            data (try (manifest/script-html m) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e
                        (ex-data e)))]
        (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
            (str "descriptor key " k " must be gated too"))
        (is (= k (:unserialisable-manifest-key data))))))

  (testing "the gate does NOT narrow what a valid manifest may hold — every
            real descriptor shape still emits"
    (doseq [[shape d] @real-descriptors]
      (is (string? (manifest/script-html (manifest/manifest d)))
          (str shape " still emits — the gate rejects unwireable values, "
               "not ordinary data")))))

(deftest body-must-hold-exactly-one-edn-form
  (testing "rf2-v4foc — `read-string` returns the FIRST form and discards
            the rest, so a truncated render, two concatenated manifests, or
            an injected suffix all hydrated silently against the first map.
            A manifest is ONE value."
    (doseq [[label body] {:trailing-text  "{:rf.root/schema-version 1} trailing"
                          :second-form    "{:rf.root/schema-version 1} {:second true}"
                          :second-scalar  "{:rf.root/schema-version 1} 42"
                          :duplicate      (str "{:rf.root/schema-version 1} "
                                               "{:rf.root/schema-version 1}")
                          :empty          ""}]
      (let [data (try (manifest/read-manifest 'test body) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e
                        (ex-data e)))]
        (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
            (str label " must be rejected, not silently truncated to its "
                 "first form"))
        (is (= :form-count (:invalid data)) (str label)))))

  (testing "…while whitespace around the one form is FINE — the rule is one
            FORM, not one line"
    (doseq [body ["  {:rf.root/schema-version 1}  "
                  "\n\t{:rf.root/schema-version 1}\n"
                  "{:rf.root/schema-version 1}\n"]]
      (is (= {:rf.root/schema-version 1} (manifest/read-manifest 'test body))
          (str "whitespace-padded body " (pr-str body)))))

  (testing "a trailing `;` comment does not swallow the wrapper's closing
            bracket — the reader wraps in `[ … \\n]` precisely so it cannot"
    (is (= {:rf.root/schema-version 1}
           (manifest/read-manifest 'test "{:rf.root/schema-version 1} ; note"))))

  (testing "an EDN discard is not a second form"
    (is (= {:rf.root/schema-version 1}
           (manifest/read-manifest 'test "{:rf.root/schema-version 1} #_{:x 1}")))))

(deftest one-form-guard-is-load-bearing
  (testing "THE RED-BEFORE for the one-form rule, kept executable: the
            bundled reader really does discard the suffix, so without the
            count check the bodies above are accepted. If a future reader
            starts throwing on trailing content this test goes red and the
            explicit check can be reconsidered — it never rots into a
            tautology."
    (doseq [body ["{:rf.root/schema-version 1} trailing"
                  "{:rf.root/schema-version 1} {:second true}"]]
      (is (= {:rf.root/schema-version 1}
             #?(:clj  (clojure.edn/read-string body)
                :cljs (cljs.reader/read-string body)))
          (str "the raw reader silently accepts " (pr-str body)
               " — that is the hole `read-manifest` now closes")))))

(deftest marker-attribute-is-pinned-in-one-place
  (is (= "data-rf-root" constants/root-manifest-marker-attribute))
  (is (= "application/edn" manifest/manifest-script-type)))

;; ---------------------------------------------------------------------------
;; Discovery (CLJS) — adjacency, and nothing else
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn stub-el
     "A structural stand-in for a DOM element — `discover` reaches for
     exactly three things, so the contract is testable in the node runner
     without a browser."
     [{:keys [tag attrs text next]}]
     #js {:tagName            (or tag "SCRIPT")
          :textContent        (or text "")
          :nextElementSibling next
          :getAttribute       (fn [k] (get attrs k))
          :hasAttribute       (fn [k] (contains? attrs k))}))

#?(:cljs
   (deftest discovery-is-adjacency-and-content
     (let [m    (manifest/manifest {:rf.root/schema-version 1
                                    :root-id :page/shop}
                                   {:element-locator {:id "shop-root"}})
           body (pr-str m)
           script (stub-el {:attrs {"type" "application/edn"
                                    constants/root-manifest-marker-attribute ""}
                            :text  body})]
       (testing "the immediately following element sibling IS the manifest"
         (is (= m (manifest/discover (stub-el {:tag "DIV" :next script})))))

       (testing "identity comes from the CONTENT, never the element"
         (is (= :page/shop
                (:root-id (manifest/discover (stub-el {:tag "DIV" :next script}))))))

       (testing "no sibling ⇒ nil; the caller decides what absence means"
         (is (nil? (manifest/discover (stub-el {:tag "DIV" :next nil})))))

       (testing "an ordinary application/edn script is NOT a manifest —
                 the bare marker is what distinguishes the hydration
                 payload and data islands from a root manifest"
         (is (nil? (manifest/discover
                    (stub-el {:tag "DIV"
                              :next (stub-el {:attrs {"type" "application/edn"}
                                              :text  body})})))))

       (testing "a non-script sibling is not searched through"
         (is (nil? (manifest/discover
                    (stub-el {:tag "DIV"
                              :next (stub-el {:tag "DIV" :next script})})))
             "discovery does not walk — adjacency is the whole rule")))))
