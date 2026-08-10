(ns re-frame.hicasso.state-cljs-test
  "`h/reg-state`, PROVED AGAINST A REAL FRAME (rf2-2rtt6.98).

  The sugar's whole claim is that it mints ORDINARY core artefacts, so
  every row here drives it the way an application would: a real frame, a
  real `subscribe`, a real `dispatch-sync`, and the frame's real `app-db`
  read back out. Nothing here stubs a registry or calls a private fn.

  ## Why the refusals are read off the ERROR CHANNEL and not off a throw

  This is the trap this file exists to not fall into. In this substrate
  neither refusal reaches the caller as an exception:

  - an **event handler**'s throw is captured by the interceptor chain
    into `:rf/interceptor-error` and fanned as
    `:rf.error/handler-exception` — `dispatch-sync` returns normally;
  - a **sub body**'s throw is caught by `compute-and-cache!`, fanned, and
    **recovered to `nil`** — so `(deref (subscribe …))` answers `nil`.

  A row spelled `(is (thrown? …))` would therefore be RED over a working
  refusal, and — far worse — a row spelled `(is (nil? …))` would be GREEN
  over a refusal that had been deleted. So [[refusals]] registers a real
  `error-emit` listener, unwraps the fanned exception, and every refusal
  row asserts on the `:rf.error/id` AND on the concern being named. Each
  one was proved able to go red by deleting the guard it watches; the
  mutation ledger is in the PR body.

  One consequence of that recovery is worth stating, because it shapes
  how the read rows are written: the recovered `nil` is **cached**, so a
  second read of the same bad query is served from the cache and fans
  nothing. Every read row below therefore uses each bad key exactly once
  and takes all of its claims off that single read.

  The DOM half — two mounted instances, the hook ledger, and the memo
  bail-out over fresh-but-equal key vectors — is
  `arm1/state-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.hicasso.impl.state :as state]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(defn- frame! [id]
  (rf/make-frame {:id id})
  id)

(defn- read* [frame-id query]
  (rf/with-frame frame-id (deref (rf/subscribe query))))

(defn- send! [frame-id event]
  (rf/with-frame frame-id (rf/dispatch-sync event))
  nil)

(defn- db-of [frame-id] (rf/app-db-value frame-id))

(defn- refusals
  "Every framework ex-data `thunk` produced, from BOTH paths — a direct
  throw (registration, which happens on the caller's own stack) and the
  always-on error channel (a read or a write, whose throw the runtime
  captures and fans). Watching one path only would be green over the
  other, and the two surfaces genuinely differ."
  [thunk]
  (let [direct  (volatile! nil)
        records (volatile! [])]
    (error-emit/register-error-listener! ::state (fn [r] (vswap! records conj r)))
    (try
      (try (thunk) (catch :default e (vreset! direct (ex-data e))))
      (finally (error-emit/unregister-error-listener! ::state)))
    (->> (concat [@direct]
                 (map (comp ex-data :exception) @records)
                 (map (comp ex-data ex-cause :exception) @records))
         (filter #(some-> % :rf.error/id namespace (= "rf.error")))
         (vec))))

(defn- refusal
  "The ex-data of the `id` refusal `thunk` produced, or nil.

  Selected by id rather than taken as the first record, because the
  runtime fans its OWN category alongside ours — a refused read arrives
  as a sub-exception carrying our ex-info as its cause — and a row that
  read the first record would be asserting about the wrapper."
  [id thunk]
  (first (filter #(= id (:rf.error/id %)) (refusals thunk))))

(defn- refused?
  "Did `thunk` refuse with `id`, naming `concern`?"
  [id concern thunk]
  (let [d (refusal id thunk)]
    (boolean (and d (= concern (:concern d)) (string? (:reason d))))))

;; ---------------------------------------------------------------------------
;; Independence — the whole reason the key is explicit
;; ---------------------------------------------------------------------------

(deftest two-instances-of-one-widget-are-independent
  (testing "the pitfall this sugar deletes: two disclosures on one page,
           one concern, two keys, two values"
    (state/reg-state ::open? {:default false})
    (let [f (frame! ::independent)]
      (is (= false (read* f [::open? :billing])) "unwritten reads the default")
      (is (= false (read* f [::open? :shipping])))
      (send! f [::open? :billing true])
      (is (= true  (read* f [::open? :billing])))
      (is (= false (read* f [::open? :shipping]))
          "the sibling did NOT move — this is the row that was silently
           false before the ruling")
      (is (= {:ui {::open? {:billing true}}} (db-of f))
          "one entry, at the documented app-space path, concern first"))))

(deftest the-same-instance-key-shares-and-that-is-correct
  (testing "two widgets given ONE key are one instance on purpose — a
           master/detail pair that must open together says so by sharing
           the key, and nothing here treats that as an error"
    (state/reg-state ::open? {:default false})
    (let [f (frame! ::shared)]
      (send! f [::open? :billing true])
      (is (= true (read* f [::open? :billing])))
      (is (= 1 (count (get-in (db-of f) [:ui ::open?])))
          "one key, one entry, however many widgets read it"))))

(deftest a-default-is-per-concern-and-any-value
  (state/reg-state ::draft {:default ""})
  (state/reg-state ::tab {:default :first})
  (let [f (frame! ::defaults)]
    (is (= "" (read* f [::draft [:order/id 42]])))
    (is (= :first (read* f [::tab :panel])))
    (testing "a concern registered with no options at all defaults to nil"
      (state/reg-state ::no-opts)
      (is (nil? (read* f [::no-opts :x]))))))

;; ---------------------------------------------------------------------------
;; Clear — a framework-named EVENT, and a removal
;; ---------------------------------------------------------------------------

(deftest clear-restores-the-default-by-removing-the-entry
  (testing "the entry is GONE, not set to the default — one representation
           of unset, and the concern map is pruned once it empties"
    (state/reg-state ::open? {:default false})
    (let [f (frame! ::cleared)]
      (send! f [::open? :billing true])
      (is (= {:ui {::open? {:billing true}}} (db-of f)))
      (send! f [state/clear-event-id ::open? :billing])
      (is (= false (read* f [::open? :billing])) "back to the default")
      (is (= {:ui {}} (db-of f))
          "the entry is removed AND the emptied concern map is pruned"))))

(deftest clear-leaves-its-siblings-alone
  (state/reg-state ::open? {:default false})
  (let [f (frame! ::clear-sibling)]
    (send! f [::open? :billing true])
    (send! f [::open? :shipping true])
    (send! f [state/clear-event-id ::open? :billing])
    (is (= {:ui {::open? {:shipping true}}} (db-of f))
        "one key removed, the concern map kept because it is not empty")
    (is (= false (read* f [::open? :billing])))
    (is (= true  (read* f [::open? :shipping])))))

(deftest clear-of-something-never-written-changes-nothing
  (testing "and in particular does not plant an empty `:ui` root in a db
           that never had one"
    (state/reg-state ::open? {:default false})
    (let [f (frame! ::clear-absent)]
      (send! f [state/clear-event-id ::open? :billing])
      (is (= {} (db-of f)))
      (is (= false (read* f [::open? :billing]))))))

(deftest a-keyword-valued-concern-survives-being-set-to-any-value
  (testing "the REJECTED value-sentinel clear would have made this row a
           silent dissoc: a concern whose values are keywords could be set
           to the sentinel by legitimate domain data. Clear is an event, so
           there is no value this concern cannot hold."
    (state/reg-state ::tab {:default :first})
    (let [f (frame! ::sentinel-free)]
      (doseq [v [:second :re-frame.hicasso/clear ::anything nil false]]
        (send! f [::tab :panel v])
        (is (= v (read* f [::tab :panel]))
            (str "the concern holds " (pr-str v) " as an ordinary value"))
        (is (contains? (get-in (db-of f) [:ui ::tab]) :panel)
            "and the entry is still THERE — no value clears anything")))))

;; ---------------------------------------------------------------------------
;; The loud refusals
;; ---------------------------------------------------------------------------

(deftest a-bad-instance-key-is-refused-at-the-read-naming-the-concern
  (state/reg-state ::open? {:default false})
  (let [f (frame! ::bad-read)]
    (testing "nil — what a missing prop, a mistyped destructure and a
             forgotten argument all evaluate to"
      (is (refused? :rf.error/hicasso-state-bad-key ::open?
                    #(read* f [::open? nil]))))
    (testing "a map — a whole entity handed over where its id was meant.
             Read ONCE, and every claim about it taken off that one read:
             the runtime recovers a failed sub to nil and CACHES it, so a
             second read of the same query is served from the cache and
             fans nothing. Each bad key below therefore appears exactly
             once in this file."
      (let [d (refusal :rf.error/hicasso-state-bad-key
                       #(read* f [::open? {:id 1}]))]
        (is (some? d) "the read refused")
        (is (= ::open? (:concern d)) "and NAMED the concern")
        (is (= {:id 1} (:instance-key d)) "and carried the offending key")
        (is (= :read (:op d)) "and the side it fired on")))
    (testing "and a missing key altogether"
      (is (refused? :rf.error/hicasso-state-bad-key ::open?
                    #(read* f [::open?]))))))

(deftest a-bad-instance-key-is-refused-at-the-write-naming-the-concern
  (state/reg-state ::open? {:default false})
  (let [f (frame! ::bad-write)]
    (is (refused? :rf.error/hicasso-state-bad-key ::open?
                  #(send! f [::open? nil true])))
    (is (refused? :rf.error/hicasso-state-bad-key ::open?
                  #(send! f [::open? {:id 1} true])))
    (testing "and nothing was written"
      (is (= {} (db-of f))
          "a refused write is a refused write — the read-side check alone
           would have left this entry in the db"))
    (testing "the refusal names the side it fired on"
      (is (= :write (:op (refusal :rf.error/hicasso-state-bad-key
                                  #(send! f [::open? nil true])))))))

  (testing "clear refuses a bad key too — it is a write"
    (state/reg-state ::open? {:default false})
    (let [f (frame! ::bad-clear)]
      (is (refused? :rf.error/hicasso-state-bad-key ::open?
                    #(send! f [state/clear-event-id ::open? nil]))))))

(deftest registration-refuses-an-unqualified-concern
  (is (refused? :rf.error/hicasso-state-bad-concern :open?
                #(state/reg-state :open? {:default false})))
  (is (refused? :rf.error/hicasso-state-bad-concern "open?"
                #(state/reg-state "open?" {:default false}))))

(deftest registration-refuses-an-unknown-option
  (testing "an option that is quietly ignored is a setting its author
           believes is in force"
    (is (refused? :rf.error/hicasso-state-bad-option ::typo
                  #(state/reg-state ::typo {:default false :defualt true})))
    (is (refused? :rf.error/hicasso-state-bad-option ::not-a-map
                  #(state/reg-state ::not-a-map [:default false])))
    (testing "and the refusal names what it did not recognise"
      (is (= [:defualt]
             (:unknown (refusal :rf.error/hicasso-state-bad-option
                                #(state/reg-state ::typo2 {:defualt true}))))))))

(deftest re-registering-is-a-refresh-only-when-the-default-agrees
  (testing "a namespace reload re-runs the same call, and that must work"
    (is (= ::reloaded (state/reg-state ::reloaded {:default 0})))
    (is (= ::reloaded (state/reg-state ::reloaded {:default 0}))))
  (testing "a DIFFERENT default refuses — the stricter of the two
           behaviours the ruling allowed. Every un-set instance of a live
           widget would otherwise start reading a different value with
           nothing on screen to say why."
    (is (refused? :rf.error/hicasso-state-redefined ::reloaded
                  #(state/reg-state ::reloaded {:default 1})))
    (testing "and the first registration is still the one in force"
      (let [f (frame! ::reg-twice)]
        (is (= 0 (read* f [::reloaded :a])))))))

;; ---------------------------------------------------------------------------
;; Instance keys as values
;; ---------------------------------------------------------------------------

(deftest instance-key-admits-exactly-the-composable-shapes
  (testing "admitted"
    (doseq [k [:kw ::ns-kw "s" 0 -1 1.5 [:a] [:a 1 "b"] [[:order/id 42] :row] []]]
      (is (state/instance-key? k) (str (pr-str k) " is an instance key"))))
  (testing "refused"
    (doseq [k [nil false true {} {:id 1} #{:a} '(:a) [:a nil] [:a {}]]]
      (is (not (state/instance-key? k)) (str (pr-str k) " is not an instance key")))))

(deftest child-key-nests-and-deep-nests
  (testing "a scalar parent key becomes a two-element vector"
    (is (= [:panel :row] (state/child-key :panel :row)))
    (is (= ["panel" 0] (state/child-key "panel" 0))))
  (testing "a vector parent key CONJes — so depth costs one element, not
           one level of nesting, and no component needs to know how deep
           it is"
    (is (= [:panel :row :cell] (state/child-key [:panel :row] :cell)))
    (is (= [:panel :row :cell :label]
           (-> :panel (state/child-key :row) (state/child-key :cell) (state/child-key :label)))))
  (testing "and every key it produces is a legal instance key, which is
           what makes nesting total"
    (is (state/instance-key? (state/child-key [[:order/id 42]] :row)))))

(deftest sibling-widgets-nested-under-one-parent-do-not-collide
  (state/reg-state ::open? {:default false})
  (let [f    (frame! ::nested)
        row1 (state/child-key :panel 1)
        row2 (state/child-key :panel 2)]
    (send! f [::open? (state/child-key row1 :detail) true])
    (is (= true  (read* f [::open? (state/child-key row1 :detail)])))
    (is (= false (read* f [::open? (state/child-key row2 :detail)])))
    (is (= false (read* f [::open? :panel]))
        "the parent's own key is a different key from any child's")))

(deftest a-fresh-but-equal-key-vector-is-the-same-instance
  (testing "keys are compared by VALUE, so a key rebuilt every render
           addresses the entry the previous render wrote"
    (state/reg-state ::draft {:default ""})
    (let [f (frame! ::value-equality)]
      (send! f [::draft [:order/id 42] "hi"])
      (is (= "hi" (read* f [::draft (into [] [:order/id 42])]))
          "a distinct vector object, equal by value")
      (is (= "hi" (read* f [::draft (state/child-key :order/id 42)]))
          "and one composed by child-key")
      (is (= 1 (count (get-in (db-of f) [:ui ::draft])))
          "one entry, not two"))))

;; ---------------------------------------------------------------------------
;; Frames
;; ---------------------------------------------------------------------------

(deftest two-frames-hold-the-same-concern-and-key-independently
  (testing "per-frame isolation costs this namespace NOTHING — app-db is
           per-frame already, and there is not one line about frames in it"
    (state/reg-state ::open? {:default false})
    (let [a (frame! ::frame-a)
          b (frame! ::frame-b)]
      (send! a [::open? :billing true])
      (is (= true  (read* a [::open? :billing])))
      (is (= false (read* b [::open? :billing])) "frame b never moved")
      (is (= {:ui {::open? {:billing true}}} (db-of a)))
      (is (= {} (db-of b)))
      (testing "and clearing in one frame leaves the other"
        (send! b [::open? :billing true])
        (send! a [state/clear-event-id ::open? :billing])
        (is (= false (read* a [::open? :billing])))
        (is (= true  (read* b [::open? :billing])))))))
