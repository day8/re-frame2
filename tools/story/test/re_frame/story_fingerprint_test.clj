(ns re-frame.story-fingerprint-test
  "JVM tests and adversarial corpus for the single canonical projection /
  fingerprint primitive (tools/story/spec/017-Testing-Story.md
  §Canonicalization): the accumulator and volatile-field strip, the total
  per-slot ordering, the enumerated `:plan-hash` inputs, `:run-hash` over the
  canonical epoch slice, and one hashing path behind determinism,
  semantic-diff and snapshot identity. The CLJS companion
  (`re-frame.story-fingerprint-cljs-test`) pins host portability."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.fingerprint :as rf.story.fingerprint]))

;; The corpus keeps two adversarial halves apart: a VOLATILE twin differs from
;; `base-run` only in volatile / accumulator fields and must canonicalize `=`
;; and hash equal; each SEMANTIC twin differs in one behavioural field and must
;; canonicalize `not=` and hash unequal.

(def ^:private base-run
  "A representative run-result slice (spec §Run result)."
  {:status     :pass
   :variant/id :story.checkout/submits
   :plan-hash  "deadbeef"
   :run-hash   "cafef00d"
   :runner     :headless
   :elapsed-ms 12.5
   :fidelity   #{:real-setup}
   :app-db     {:checkout {:state :submitted}
                :cart     {:items [{:sku "A"}]}
                :rf.story/lifecycle :ready
                :rf.story/loaders-complete? true}
   :assertions [{:assertion :rf.assert/path-equals
                 :status    :pass
                 :passed?   true
                 :payload   [[:checkout :state] :submitted]
                 :source    "checkout_test.clj:42"
                 :runner    :headless
                 :elapsed-ms 0.3}]
   :checks     [{:check :check/no-runtime-errors :status :pass :assertions []}]
   :effects    [{:effect :rf.http/managed :dispatch-id "d-1"}
                {:effect :rf/db :dispatch-id "d-2"}]
   :schema-violations []
   :warnings   []
   :sub-overrides {}
   :epoch-tape [{:epoch-id 1 :dispatch-id "d-1"
                 :trigger-event [:checkout/submit]
                 :db-after {:checkout {:state :submitting}}
                 :effects [{:effect :rf.http/managed}]
                 :source-coord "x:1"}
                {:epoch-id 2 :dispatch-id "d-2"
                 :trigger-event [:checkout/ok]
                 :db-after {:checkout {:state :submitted}}
                 :effects []}]})

(def ^:private volatile-twin
  "`base-run` with every volatile slot perturbed and nothing behavioural changed."
  (-> base-run
      (assoc :elapsed-ms 999.0
             :runner     :dom
             :plan-hash  "00000000"
             :run-hash   "11111111")
      (assoc-in [:app-db :rf.story/lifecycle] :error)
      (assoc-in [:app-db :rf.story/loaders-complete?] false)
      (assoc-in [:assertions 0 :source] "elsewhere.clj:7")
      (assoc-in [:assertions 0 :elapsed-ms] 88.0)
      (assoc-in [:assertions 0 :runner] :dom)
      (assoc-in [:effects 0 :dispatch-id] "z-9")
      (assoc-in [:effects 1 :dispatch-id] "z-8")
      (assoc-in [:epoch-tape 0 :dispatch-id] "z-1")
      (assoc-in [:epoch-tape 0 :source-coord] "y:42")
      (assoc-in [:epoch-tape 1 :dispatch-id] "z-2")))

(def ^:private semantic-twins
  "Each entry differs from `base-run` in exactly one behavioural field."
  {:app-db-diff    (assoc-in base-run [:app-db :checkout :state] :rejected)
   :effect-diff    (assoc-in base-run [:effects 0 :effect] :rf/dispatch)
   :assertion-diff (assoc-in base-run [:assertions 0 :status] :fail)
   :status-diff    (assoc base-run :status :fail)
   :epoch-db-diff  (assoc-in base-run [:epoch-tape 1 :db-after :checkout :state] :failed)
   :warning-diff   (assoc base-run :warnings [{:warning :rf/over-render}])})

(deftest project-strips-story-accumulator-keys
  (is (= {:keep 1 :nested {:real :v}}
         (rf.story.fingerprint/project {:keep 1
                                        :rf.story/lifecycle :ready
                                        :nested {:rf.story/x 9 :real :v}}))
      ":rf.story/* accumulator keys drop at any depth")
  (is (= {:rf/db 1} (rf.story.fingerprint/project {:rf/db 1}))
      "other namespaced keys survive"))

(deftest project-strips-volatile-fields
  (testing "every volatile field is dropped recursively"
    (let [every-volatile (zipmap rf.story.fingerprint/volatile-fields (repeat :v))
          projected      (rf.story.fingerprint/project
                           (assoc base-run
                                  :nested {:rows [(assoc every-volatile :keep 1)]
                                           :set  #{(assoc every-volatile :keep 2)}}))
          maps           (filter map? (tree-seq coll? seq projected))]
      (doseq [k rf.story.fingerprint/volatile-fields]
        (is (not-any? #(contains? % k) maps)
            (str k " must be stripped from the projection at every depth")))
      (is (= [[{:keep 1}] #{{:keep 2}}]
             [(get-in projected [:nested :rows]) (get-in projected [:nested :set])])
          "the non-volatile key beside them survives")))
  (testing ":source / :elapsed-ms / :runner strip structurally, on the run
            result and the assertion record that stamp them"
    (let [stripped (rf.story.fingerprint/project
                     (rf.story.fingerprint/strip-run-stamps base-run))]
      (is (= [{} {}] [(select-keys stripped [:elapsed-ms :runner])
                      (select-keys (get-in stripped [:assertions 0]) [:source :elapsed-ms])])))))

;; `:source`, `:elapsed-ms` and `:runner` are ordinary domain keys (a feed's
;; source, a stopwatch, a race). A recursive strip would make every consumer
;; of `canonicalize` blind to app-db and args data under them.
(deftest app-data-under-run-stamp-keys-is-semantic
  (testing "an app-db change under :source / :elapsed-ms / :runner perturbs
            the canonical value and the run-hash, like any other key"
    (let [run (fn [db] {:status :pass :app-db db :epoch-tape [] :assertions []
                        :checks [] :effects [] :schema-violations [] :warnings []})]
      (doseq [[a b] [[{:src "rss"}                  {:src "atom"}] ; control
                     [{:feed {:source "rss"}}       {:feed {:source "atom"}}]
                     [{:timer {:elapsed-ms 1000}}   {:timer {:elapsed-ms 9999}}]
                     [{:race {:runner "alice"}}     {:race {:runner "bob"}}]]]
        (is (not= (rf.story.fingerprint/canonicalize (run a))
                  (rf.story.fingerprint/canonicalize (run b)))
            (str (pr-str a) " vs " (pr-str b) " must perturb the canonical value"))
        (is (not= (rf.story.fingerprint/run-hash (run a))
                  (rf.story.fingerprint/run-hash (run b)))
            (str (pr-str a) " vs " (pr-str b) " must perturb the run-hash")))))
  (testing "a variant's args / db-seed under :source perturb the plan-hash"
    (is (not= (rf.story.fingerprint/plan-hash {:world {:args {:src "a"}}})
              (rf.story.fingerprint/plan-hash {:world {:args {:src "b"}}}))
        "control")
    (is (not= (rf.story.fingerprint/plan-hash {:world {:args {:source "a"}}})
              (rf.story.fingerprint/plan-hash {:world {:args {:source "b"}}})))
    (is (not= (rf.story.fingerprint/plan-hash {:world {:db-seed {:feed {:source "a"}}}})
              (rf.story.fingerprint/plan-hash {:world {:db-seed {:feed {:source "b"}}}})))))

;; An fx-error run carries a per-run `:error-trace` pointer and
;; the raw thrown exception, which compares by identity.
(deftest fx-error-stamps-canonicalize-equal
  (let [run (fn [trace-id]
              {:status :fail :app-db {}
               :effects [{:fx-id :app.fx/boom :args {} :outcome :error
                          :error-trace trace-id :epoch-id 3}]
               :epoch-tape [{:epoch-id 3 :outcome :ok :db-after {}
                             :effects [{:fx-id :app.fx/boom :args {} :outcome :error
                                        :error-trace trace-id}]
                             :trace-events [{:operation :rf.error/fx-handler-exception
                                             :op-type :error :id trace-id
                                             :tags {:exception (ex-info "boom" {:k 1})}}]}]})]
    (testing "two replays of one failing fx differ only in :error-trace and the
              exception object's identity — they canonicalize = and hash equal"
      (is (= (rf.story.fingerprint/canonicalize (run 76))
             (rf.story.fingerprint/canonicalize (run 98))))
      (is (= (rf.story.fingerprint/run-hash (run 76))
             (rf.story.fingerprint/run-hash (run 98)))))
    (testing "the exception's message and data still count"
      (is (not= (rf.story.fingerprint/canonicalize (run 76))
                (rf.story.fingerprint/canonicalize
                  (assoc-in (run 76) [:epoch-tape 0 :trace-events 0 :tags :exception]
                            (ex-info "boom" {:k 2}))))))
    (testing ":error-trace is stripped only on an effect row, not in app data"
      (is (not= (rf.story.fingerprint/canonicalize {:status :pass :app-db {:error-trace 1}})
                (rf.story.fingerprint/canonicalize {:status :pass :app-db {:error-trace 2}}))))))

(deftest project-reconciles-variant-id-spelling
  (testing ":variant-id is rewritten to :variant/id and then stripped, so both
            spellings, alone or together, collapse to the residue"
    (is (= {:keep 1}
           (rf.story.fingerprint/project {:variant-id :x :keep 1})
           (rf.story.fingerprint/project {:variant/id :x :keep 1})
           (rf.story.fingerprint/project {:variant-id :legacy :variant/id :canonical :keep 1})))))

(deftest equivalent-runs-canonicalize-equal
  (testing "runs differing only in volatile and accumulator fields canonicalize =
            and hash equal (the determinism floor)"
    (is (= (rf.story.fingerprint/canonicalize base-run) (rf.story.fingerprint/canonicalize volatile-twin)))
    (is (= (rf.story.fingerprint/canonical-hash base-run) (rf.story.fingerprint/canonical-hash volatile-twin)))
    (is (= (rf.story.fingerprint/run-hash base-run) (rf.story.fingerprint/run-hash volatile-twin)))))

(deftest semantic-difference-changes-canonical-value
  (testing "each single-field semantic difference perturbs both the
            canonical value and the run-hash (semantic-diff is not blind)"
    (let [base-canon (rf.story.fingerprint/canonicalize base-run)
          base-hash  (rf.story.fingerprint/run-hash base-run)]
      (doseq [[label twin] semantic-twins]
        (is (not= base-canon (rf.story.fingerprint/canonicalize twin))
            (str label " must perturb the canonical value"))
        (is (not= base-hash (rf.story.fingerprint/run-hash twin))
            (str label " must perturb the run-hash"))))))

;; The framework-filled `:rf/time-ms` in a recordable-coeffect map is
;; wall-clock, fresh per dispatch, so `canonicalize` strips it on each carrier
;; — the `:rf.event/dispatched` trace's `[:tags :rf.cofx]`, the run-start
;; trace's `:rf.event/cofx`, and the epoch record's top-level `:rf.cofx` replay
;; token — or replays false-drift. Owner-qualified facts beside it stay
;; semantic.

(defn- dispatched-trace-event [cofx]
  {:operation :rf.event/dispatched
   :op-type   :rf.event
   :tags      {:rf.event/v [:some/event] :rf.cofx cofx}})

(defn- run-start-trace-event [cofx]
  {:operation :rf.event/run-start
   :op-type   :rf.event
   :tags      {:rf.event/v [:some/event] :rf.event/cofx cofx}})

(defn- epoch-record-with-cofx
  "`:epoch-id` plus a load-bearing slot, so `epoch-record?` recognises the carrier."
  [cofx]
  {:epoch-id 1 :db-after {:answer 42} :outcome :ok :rf.cofx cofx})

(deftest cofx-time-ms-is-stripped-from-the-dispatched-trace
  (let [token {:rf/time-ms 1000 :counter/delta 4 :rf.route/location "/a"}]
    (doseq [carrier [dispatched-trace-event run-start-trace-event epoch-record-with-cofx]
            :let [base (carrier token)]]
      (testing (str (:operation base :epoch-record))
        (is (= (rf.story.fingerprint/canonicalize base)
               (rf.story.fingerprint/canonicalize (carrier (assoc token :rf/time-ms 9999)))))
        (is (= (rf.story.fingerprint/canonical-hash base)
               (rf.story.fingerprint/canonical-hash (carrier (assoc token :rf/time-ms 9999)))))
        (doseq [fact [{:counter/delta 5} {:rf.route/location "/b"}]]
          (is (not= (rf.story.fingerprint/canonicalize base)
                    (rf.story.fingerprint/canonicalize (carrier (merge token fact))))
              (str fact " must perturb the canonical value"))
          (is (not= (rf.story.fingerprint/canonical-hash base)
                    (rf.story.fingerprint/canonical-hash (carrier (merge token fact))))
              (str fact " must perturb the hash"))))))
  (is (not= (rf.story.fingerprint/canonicalize {:app-db {:rf/time-ms 1}})
            (rf.story.fingerprint/canonicalize {:app-db {:rf/time-ms 2}}))
      "the strip is structural: :rf/time-ms in app data is semantic"))

(defn- epoch-record-with-cofx
  "A minimal `:rf/epoch-record` (`:epoch-id` + a load-bearing slot so
  `epoch-record?` recognises the carrier) pinning a top-level `:rf.cofx`
  replay token."
  [cofx]
  {:epoch-id    1
   :db-after    {:answer 42}
   :outcome     :ok
   :rf.cofx     cofx})

;; Each collection canonicalizes under a structural tag, so {} / #{} / [] and
;; {:a 1} / [:a 1] never collapse to byte-identical forms that hash equal.
(deftest collection-types-do-not-collide
  (testing "maps, sets and vectors are canonically distinct, empty or not"
    (doseq [[a b] [[{} []] [#{} []] [{} #{}] [{:k 1} [:k 1]] [#{:k} [:k]]]]
      (is (not= (rf.story.fingerprint/canonicalize a) (rf.story.fingerprint/canonicalize b))
          (str (pr-str a) " vs " (pr-str b)))
      (is (not= (rf.story.fingerprint/content-hash a) (rf.story.fingerprint/content-hash b))
          (str (pr-str a) " vs " (pr-str b) " must hash differently"))))
  (testing "a seq is distinct from a vector at the canonical-form / content-hash
            layer the snapshot identity hashes (canonicalize maps every
            sequential to a vector upstream, so it collapses them on purpose)"
    (is (= [[rf.story.fingerprint/seq-tag [:a :b]] [rf.story.fingerprint/vec-tag [:a :b]]]
           (map rf.story.fingerprint/canonical-form [(list :a :b) [:a :b]])))
    (is (not= (rf.story.fingerprint/content-hash (list :a :b)) (rf.story.fingerprint/content-hash [:a :b]))))
  (testing "the collision is closed nested, not only at the root"
    (is (not= (rf.story.fingerprint/canonical-hash {:effects [{:k 1}]})
              (rf.story.fingerprint/canonical-hash {:effects [[:k 1]]})))
    (is (not= (rf.story.fingerprint/canonicalize {:k {}}) (rf.story.fingerprint/canonicalize {:k []})))
    (is (not= (rf.story.fingerprint/canonicalize {:k {:a 1}}) (rf.story.fingerprint/canonicalize {:k [:a 1]})))))

;; `pr-str` of a fn embeds its per-process identity, so every fn folds to the
;; stable `opaque-fn` sentinel; otherwise any hashed slice carrying one would be
;; silently nondeterministic across processes.
(deftest fn-valued-slot-hashes-deterministically
  (testing "a plan with an inline fn fx-override hashes identically across
            independent builds, each allocating a fresh closure — as a fresh
            process would"
    (let [build-plan (fn []
                       {:story/id :story.fn/v
                        :world {:frame {:fx-overrides {:rf.http/managed (fn [_] :stub)}}}
                        :script [[:dispatch [:go]]]
                        :expect {:checks []}})]
      (is (= (rf.story.fingerprint/plan-hash (build-plan)) (rf.story.fingerprint/plan-hash (build-plan))))))
  (testing "the run-hash path too: a fn in :app-db or an effect's :args"
    (let [run-with (fn [f] {:status :pass :app-db {:cb f}
                            :effects [{:fx-id :x :args f :outcome :ok}]})]
      (is (= (rf.story.fingerprint/run-hash (run-with (fn [] 1)))
             (rf.story.fingerprint/run-hash (run-with (fn [] 1)))))
      (is (= (rf.story.fingerprint/canonicalize (run-with (fn [] 1)))
             (rf.story.fingerprint/canonicalize (run-with (fn [] 1)))))))
  (testing "only genuine fns fold to the sentinel — keywords and sets are IFn too"
    (is (= rf.story.fingerprint/opaque-fn
           (rf.story.fingerprint/canonical-form (fn [] 1))
           (rf.story.fingerprint/canonical-form (fn [x] x))))
    (is (= :kw (rf.story.fingerprint/canonical-form :kw)))
    (is (not= rf.story.fingerprint/opaque-fn (rf.story.fingerprint/canonical-form #{:a}))))
  (testing "the deliberate trade-off: plans differing only in fn identity hash
            equal, while a non-fn semantic difference still perturbs"
    (let [base-fn-plan {:story/id :story.fn/v
                        :world {:frame {:fx-overrides {:rf.http/managed (fn [_] :a)}}}
                        :script [] :expect {}}]
      (is (= (rf.story.fingerprint/plan-hash base-fn-plan)
             (rf.story.fingerprint/plan-hash (assoc-in base-fn-plan
                                                       [:world :frame :fx-overrides :rf.http/managed]
                                                       (fn [_] :b)))))
      (is (not= (rf.story.fingerprint/plan-hash base-fn-plan)
                (rf.story.fingerprint/plan-hash (assoc-in base-fn-plan [:world :args :sku] "X")))))))

;; Ratios and doubles are not host-portable through `pr-str` (JVM "1/3" and
;; "1.0" against CLJS's double and "1"), so they normalise to a bit-stable
;; form — `[:rf/double <16-hex IEEE-754 bits>]`, the `:rf/nan` sentinel, or an
;; integer for an integer-valued double — while integers, strings, keywords
;; and collections pass through byte-identical. The CLJS companion asserts the
;; same forms and hashes; that pairing is the cross-host proof.

(deftest ordinary-value-canonical-forms-are-unchanged
  (testing "the canonical form and content-hash of ordinary values are pinned
            literals: if one drifts, goldens silently mis-compare"
    ;; [value  expected-canonical-form  expected-content-hash]. A bigint past
    ;; 2^53-1 is not ordinary — `large-integers-canonicalize-host-portably`.
    (let [cases [[42                    "42"                     "211a4621"]
                 [-7                     "-7"                     "ab492c45"]
                 [9007199254740991       "9007199254740991"       "9f16836d"]
                 ["hello"                "\"hello\""              "3409cbf2"]
                 [:foo/bar               ":foo/bar"               "3ac20368"]
                 ['sym                   "sym"                    "2bdbe3fa"]
                 [true                   "true"                   "28c0a6cf"]
                 [false                  "false"                  "d4bea88b"]
                 [nil                    "nil"                    "8d40a9c3"]
                 [[1 2 3]                "[:rf/vec [1 2 3]]"      "234450cb"]
                 [{:a 1 :b "x" :c :k}    "[:rf/map [:a 1 :b \"x\" :c :k]]" "418d9acd"]
                 [#{:a :b :c}            "[:rf/set [:a :b :c]]"   "405ea2f0"]
                 [{:x [{:y #{1 2}} {:z :w}]}
                  "[:rf/map [:x [:rf/vec [[:rf/map [:y [:rf/set [1 2]]]] [:rf/map [:z :w]]]]]]"
                  "2435e981"]
                 [{:status :pass :app-db {:n 1 :items [{:sku "A"}]}}
                  "[:rf/map [:app-db [:rf/map [:items [:rf/vec [[:rf/map [:sku \"A\"]]]] :n 1]] :status :pass]]"
                  "98b520a4"]]]
      (doseq [[v cf ch] cases]
        (is (= cf (pr-str (rf.story.fingerprint/canonical-form v)))
            (str "canonical form of " (pr-str v) " drifted — golden rebase!"))
        (is (= ch (rf.story.fingerprint/content-hash v))
            (str "content-hash of " (pr-str v) " drifted — golden rebase!"))))))

(deftest large-integers-canonicalize-host-portably
  (testing "an integer beyond ±2^53-1 takes the lossy bit-double path CLJS is
            forced onto, so the same logical integer hashes equal cross-host"
    (let [big   (bigint 100000000000000000000)
          bits  #(vector rf.story.fingerprint/double-tag
                         (#'rf.story.fingerprint/double->bits-hex (double %)))]
      (is (= (bits big)
             (rf.story.fingerprint/canonical-form big)
             (rf.story.fingerprint/canonical-form (.toBigInteger (bigdec big)))
             (rf.story.fingerprint/canonical-form 1e20))
          "bigint, BigInteger and the double CLJS reads share one canonical form")
      (let [over (inc (bigint rf.story.fingerprint/max-safe-integer))]
        (is (= (bits over) (rf.story.fingerprint/canonical-form over))
            "one past max-safe-integer takes the bit-double path"))))
  (testing "integers within range, including the ±max-safe-integer boundary, pass through"
    (doseq [n [rf.story.fingerprint/max-safe-integer (- rf.story.fingerprint/max-safe-integer)
               42 -7 1000000]]
      (is (= n (rf.story.fingerprint/canonical-form n))))))

(deftest ratios-canonicalize-host-portably
  (testing "a JVM Ratio canonicalises to the SAME bit-stable double form its
            CLJS double counterpart reaches — `1/3` and `(/ 1.0 3.0)` (the
            double CLJS reads `1/3` as) share a canonical form + hash"
    (is (= [rf.story.fingerprint/double-tag "3fd5555555555555"] (rf.story.fingerprint/canonical-form 1/3)))
    (is (= (rf.story.fingerprint/canonical-form 1/3) (rf.story.fingerprint/canonical-form (/ 1.0 3.0))))
    (is (= (rf.story.fingerprint/content-hash 1/3) (rf.story.fingerprint/content-hash (/ 1.0 3.0)))
        "ratio and its double value hash equal — cross-host equivalence")
    (is (= (rf.story.fingerprint/canonical-hash {:r 1/3}) (rf.story.fingerprint/canonical-hash {:r (/ 1.0 3.0)}))
        "the same holds nested inside a hashed slice"))
  (testing "distinct ratios remain distinct (sensitivity not lost)"
    (is (not= (rf.story.fingerprint/canonical-form 1/3) (rf.story.fingerprint/canonical-form 2/3)))))

(deftest floats-canonicalize-host-portably
  (testing "an integer-valued double folds to its INTEGER form — host-mandated
            (CLJS `1.0` IS the integer `1`), so JVM `1.0` and `1` canonicalise
            EQUAL and the CLJS companion's `1.0` matches"
    (is (= 1   (rf.story.fingerprint/canonical-form 1.0)))
    (is (= 100 (rf.story.fingerprint/canonical-form 100.0)))
    (is (= (rf.story.fingerprint/canonical-form 1.0) (rf.story.fingerprint/canonical-form 1))))
  (testing "a fractional double folds to the bit-stable `[:rf/double <hex>]`"
    (is (= [rf.story.fingerprint/double-tag "3ff8000000000000"] (rf.story.fingerprint/canonical-form 1.5)))
    (is (not= (rf.story.fingerprint/canonical-form 1.5) (rf.story.fingerprint/canonical-form 1))
        "1.5 is NOT integer-valued — distinct from 1")
    (is (not= (rf.story.fingerprint/canonical-form 1.5) (rf.story.fingerprint/canonical-form 2.5))
        "distinct fractional doubles stay distinct"))
  (testing "an integer-valued double too large for a 64-bit integer takes the
            bit path (no silent overflow to a wrong long)"
    (is (= [rf.story.fingerprint/double-tag "444b1ae4d6e2ef50"] (rf.story.fingerprint/canonical-form 1e21)))))

(deftest nan-and-inf-canonicalize-host-portably
  (testing "every NaN folds to the single `:rf/nan` sentinel — a `##NaN` slot
            hashes deterministically and never perturbs a hash by bit-pattern"
    (let [payload-nan (Double/longBitsToDouble 0x7ff8000000000001)]
      (is (Double/isNaN payload-nan))
      (is (not= (Double/doubleToRawLongBits Double/NaN)
                (Double/doubleToRawLongBits payload-nan))
          "the two NaNs differ in bit-pattern")
      (is (= rf.story.fingerprint/nan-tag (rf.story.fingerprint/canonical-form Double/NaN)))
      (is (= rf.story.fingerprint/nan-tag (rf.story.fingerprint/canonical-form payload-nan)))
      (is (= (rf.story.fingerprint/content-hash Double/NaN) (rf.story.fingerprint/content-hash payload-nan)))
      (is (= (rf.story.fingerprint/canonical-hash {:x Double/NaN})
             (rf.story.fingerprint/canonical-hash {:x payload-nan}))
          "two NaN-bearing slices hash equal whatever the NaN's bit-pattern")))
  (testing "±Inf ride the bit-double path — host-stable bits, mutually distinct
            and distinct from every finite double"
    (is (= [rf.story.fingerprint/double-tag "7ff0000000000000"] (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY)))
    (is (= [rf.story.fingerprint/double-tag "fff0000000000000"] (rf.story.fingerprint/canonical-form Double/NEGATIVE_INFINITY)))
    (is (not= (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY)
              (rf.story.fingerprint/canonical-form Double/NEGATIVE_INFINITY)))
    (is (not= (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY) (rf.story.fingerprint/canonical-form 1.5)))
    (is (not= rf.story.fingerprint/nan-tag (rf.story.fingerprint/canonical-form Double/POSITIVE_INFINITY)))))

(deftest canon-set-hashes-equal-pr-str-elements-stably
  (testing "two DISTINCT fns in a set both fold to `:rf/opaque-fn` (equal
            `pr-str`); tied elements render identically, so the set hashes
            stably across independent builds whichever order they sort in"
    (let [build (fn [] #{(fn [] 1) (fn [] 2) :marker})]
      (is (= (rf.story.fingerprint/content-hash (build)) (rf.story.fingerprint/content-hash (build)))
          "an equal-pr-str-bearing set hashes identically across builds"))))

;; `canon-map-entries` sorts by the canon key's `pr-str`, with the canon value
;; as a secondary key: keys that canonicalise alike (fns, NaNs, `1` and `1.0`)
;; would otherwise fall back to map iteration order, so `=`-equal maps built in
;; different insertion orders would hash unequal.

(deftest canon-map-tied-keys-order-iteration-independently
  (testing "=-equal maps whose keys tie, built in opposite insertion orders,
            canonicalize and hash equal"
    (let [f1 (fn [] :one)
          f2 (fn [] :two)
          m-ab (array-map f1 :a f2 :b)
          m-ba (array-map f2 :b f1 :a)]
      (is (= m-ab m-ba) "precondition: the two maps are =-equal")
      (is (= (rf.story.fingerprint/canonical-form m-ab) (rf.story.fingerprint/canonical-form m-ba)))
      (is (= (rf.story.fingerprint/canonical-hash m-ab) (rf.story.fingerprint/canonical-hash m-ba)))))
  (testing "a genuine value difference under a tied key still separates"
    (let [f1 (fn [] 1) f2 (fn [] 2)]
      (is (not= (rf.story.fingerprint/canonical-hash (array-map f1 :a f2 :b))
                (rf.story.fingerprint/canonical-hash (array-map f1 :a f2 :c))))))
  (testing "the number-folding tie (1 and 1.0 both canon to 1) is iteration-independent too"
    (let [m1 (array-map 1 :a 1.0 :b)
          m2 (array-map 1.0 :b 1 :a)]
      (is (= [2 2] [(count m1) (count m2)]) "precondition: 1 and 1.0 are distinct keys")
      (is (= (rf.story.fingerprint/canonical-hash m1) (rf.story.fingerprint/canonical-hash m2)))))
  (testing "distinct keys sort by key alone"
    (is (= [rf.story.fingerprint/map-tag [:a 1 :b 2 :c 3]]
           (rf.story.fingerprint/canonical-form (array-map :c 3 :a 1 :b 2))))))

(deftest emission-order-is-preserved-and-significant
  (testing "effects and epochs keep producer order: reordering is a different
            canonical value"
    (doseq [slot [:effects :epoch-tape]]
      (is (not= (rf.story.fingerprint/canonicalize base-run)
                (rf.story.fingerprint/canonicalize (update base-run slot (comp vec reverse))))
          (str slot)))))

(def ^:private base-plan
  {:plan/id    :p1
   :variant/id :story.checkout/submits
   :story/id   :story.checkout
   :source-chain [:a :b]
   :world      {:frame {:preset :story} :args {:sku "A"} :setup [[:dispatch [:cart/add]]]}
   :script     [[:dispatch [:checkout/submit]]]
   :expect     {:checks [:check/no-runtime-errors]
                :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]}
   :required-runner #{:app-db :effects}
   :evidence   {:source :epoch-tape}
   :tags       #{:test}
   :plan-hash  "should-not-feed-itself"
   :explain    {:debug :noise}})

(deftest plan-hash-over-enumerated-inputs-only
  (testing "non-input slots (:evidence, :explain, :source-chain, :plan/id, the
            rider :plan-hash, the volatile :variant/id) do not affect plan-hash"
    (let [h (rf.story.fingerprint/plan-hash base-plan)]
      (doseq [p [(assoc base-plan :evidence {:source :other})
                 (assoc base-plan :explain {:debug :different})
                 (assoc base-plan :source-chain [:x])
                 (assoc base-plan :plan/id :other)
                 (assoc base-plan :plan-hash "different")
                 (dissoc base-plan :variant/id)]]
        (is (= h (rf.story.fingerprint/plan-hash p))))))
  (testing "a testable or renderable difference changes plan-hash"
    (doseq [p [(assoc-in base-plan [:world :args :sku] "B")
               (update base-plan :script conj [:dispatch [:extra]])
               (assoc base-plan :story/id :story.other)]]
      (is (not= (rf.story.fingerprint/plan-hash base-plan) (rf.story.fingerprint/plan-hash p))))))

(deftest plan-hash-and-run-hash-call-the-same-primitive
  (testing "plan-hash and run-hash are canonical-hash over an enumerated slice —
            no second hash implementation"
    (is (= (rf.story.fingerprint/plan-hash base-plan)
           (rf.story.fingerprint/canonical-hash (select-keys base-plan rf.story.fingerprint/plan-hash-input-keys))))
    (is (= (rf.story.fingerprint/run-hash base-run)
           (rf.story.fingerprint/canonical-hash (select-keys base-run rf.story.fingerprint/run-hash-input-keys))))))

(deftest snapshot-identity-uses-the-same-primitive
  (testing "content-hash strips nothing, so the snapshot tuple keeps its
            :variant-id sensitivity, while canonical-hash (the run / diff path)
            strips it"
    (let [tuple {:rf/snapshot-canonical :rf/snapshot-canonical-v1
                 :variant-id :story.x/v
                 :variant {:tags #{:dev}}
                 :effective-args {:a 1}}
          other (assoc tuple :variant-id :story.y/v)]
      (is (not= (rf.story.fingerprint/content-hash tuple) (rf.story.fingerprint/content-hash other)))
      (is (= (rf.story.fingerprint/canonical-hash tuple) (rf.story.fingerprint/canonical-hash other))))))
