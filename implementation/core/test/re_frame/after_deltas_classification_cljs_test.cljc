(ns re-frame.after-deltas-classification-cljs-test
  "rf2-3x7nj.4.2 — the `:rf.event/after-deltas` slot on `:rf.event/run-end`
  carries one ctx diff per user `:after` interceptor, and each diff carries its
  `:before` / `:after` VALUES. The standard `[:rf.interceptor/path …]` `:after`
  always rewrites `[:coeffects :db]` and widens `[:effects :db]` back to the
  WHOLE app-db, so every path-focused handler stamped the whole db into that
  slot — and `re-frame.classification/project-trace-event` had no arm for it, so
  the frame's classified paths shipped RAW past the emit-time chokepoint (trace
  listeners, the ring, the epoch record, and the off-box epoch projection).
  An `:after` that adds `:fx` leaked that fx's registration-classified args the
  same way.

  rf2-fc84b — the 4.2 walk was ROOT-anchored, but a path-focused context's
  `:db` values are FOCUSED SLICES: the path interceptor's own `:before` values,
  both values of a user `:after` positioned inside a focus, and even the inner
  interceptor's `:after` values under nested focus. A root walk cannot match
  `[:auth :token]` against a slice `{:token …}`, so those shipped raw. Each
  delta now carries its before/after absolute app-db focus to the projector in
  a PRIVATE metadata carrier, and each value is walked at its TRUE offset; an
  unknown focus on a classified frame fails closed.

  Producer-derived: the live legs drive the REAL path interceptor and a REAL
  user `:after`, and each first proves the slot is populated by that producer —
  so a secret's absence is a fact about a slot that exists, not a vacuum.
  Sentinels are constants the handlers write, never event args (the positional
  `:rf.event/v` limit of `redact-event` is a separate, documented matter), and
  no declared path can coincidentally match an unrelated slice.

  Posture: the live legs read the dev trace, which emits nothing under
  `-Dre-frame.debug=false`, so they are `^:requires-debug` (rf2-d2841). The
  projector leg drives `project-trace-event` on a hand-built shape and runs in
  both postures.

  Dual-runtime `.cljc` (`*-cljs-test` ns): the JVM `clojure -M:test` runner and
  the shadow-cljs `:node-test` build both pick it up."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            ;; Side-effect load: publishes the epoch hooks `rf/epoch-history`
            ;; reads (a test-only dep of core).
            [re-frame.epoch]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private secret "AFTER-DELTAS-SENTINEL-3x7nj42")

(def ^:private frame-id :after-deltas/frame)

(defn- contains-secret?
  [x]
  (cond
    (string? x) #?(:clj  (.contains ^String x ^String secret)
                   :cljs (not= -1 (.indexOf x secret)))
    (map? x)    (boolean (some contains-secret? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-secret? x))
    :else       false))

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- run-end [traces]
  (first (filter #(= :rf.event/run-end (:operation %)) traces)))

(defn- seed-classified-secret!
  "Make the frame and classify `[:auth :token]` sensitive in the SAME event that
  writes the secret there (the canonical EP-0025 pattern)."
  []
  (rf/make-frame {:id frame-id})
  (rf/reg-event :after-deltas/seed
    (fn [{:keys [db]} _]
      {:db        (assoc db :auth {:token secret} :counter 0)
       :sensitive [[:auth :token]]}))
  (rf/dispatch-sync [:after-deltas/seed] {:frame frame-id}))

;; ---------------------------------------------------------------------------
;; Live: the standard path interceptor's whole-db diff
;; ---------------------------------------------------------------------------

(deftest ^:requires-debug path-interceptor-after-delta-redacts-classified-db-paths
  (testing "a handler focused on [:counter] never reads :auth, yet the path
            interceptor's :after diff carries the WHOLE db before and after —
            the classified [:auth :token] must be :rf/redacted there, while the
            focused change itself survives for the diff's reader"
    (seed-classified-secret!)
    (rf/reg-event :after-deltas/bump
      {:interceptors [[:rf.interceptor/path [:counter]]]}
      (fn [{:keys [db]} _] {:db (inc db)}))
    (let [acc (collect-traces! ::path)]
      (try
        (rf/dispatch-sync [:after-deltas/bump] {:frame frame-id})
        (let [ev     (run-end @acc)
              deltas (get-in ev [:tags :rf.event/after-deltas])
              diff   (:rf.interceptor.delta/ctx-delta (first deltas))]
          ;; Producer control: the slot exists and the path interceptor wrote it.
          (is (= [:rf.interceptor/path] (mapv :rf.interceptor.delta/id deltas))
              "the run-end trace carries the path interceptor's after-delta")
          (is (= 1 (get-in diff [:effects :changed :db :after :counter]))
              "the focused change survives the projection — the diff still reads")
          (is (= rf.privacy/redacted-sentinel
                 (get-in diff [:coeffects :changed :db :after :auth :token]))
              "the restored whole-db coeffect has the classified path redacted")
          (is (= rf.privacy/redacted-sentinel
                 (get-in diff [:effects :changed :db :after :auth :token]))
              "the widened whole-db effect has the classified path redacted")
          (is (not (contains-secret? ev))
              "the secret appears nowhere in the run-end trace"))
        (finally
          (rf/unregister-listener! :trace ::path))))))

;; ---------------------------------------------------------------------------
;; Live: a user :after that adds :fx
;; ---------------------------------------------------------------------------

(deftest ^:requires-debug user-after-added-fx-args-take-the-fx-registration-classification
  (testing "an :after interceptor that ADDS an :fx entry puts that fx's args in
            the diff; a classified fx's args redact there exactly as on
            :rf.event/fx, and an unclassified control fx rides raw"
    (rf/make-frame {:id frame-id})
    (rf/reg-fx :after-deltas/store {:sensitive [[:token]]} (fn [_ _] nil))
    (rf/reg-fx :after-deltas/audit (fn [_ _] nil))
    (rf/reg-interceptor :after-deltas/add-fx
      {:after (fn [ctx]
                (update-in ctx [:effects :fx] (fnil into [])
                           [[:after-deltas/store {:token secret}]
                            [:after-deltas/audit {:msg "benign"}]]))})
    (rf/reg-event :after-deltas/write
      {:interceptors [:after-deltas/add-fx]}
      (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (let [acc (collect-traces! ::fx)]
      (try
        (rf/dispatch-sync [:after-deltas/write] {:frame frame-id})
        (let [ev     (run-end @acc)
              deltas (get-in ev [:tags :rf.event/after-deltas])
              fx     (get-in (first deltas)
                             [:rf.interceptor.delta/ctx-delta :effects :added :fx])]
          (is (= [:after-deltas/add-fx] (mapv :rf.interceptor.delta/id deltas))
              "the user :after interceptor wrote the after-delta")
          (is (= [[:after-deltas/store {:token rf.privacy/redacted-sentinel}]
                  [:after-deltas/audit {:msg "benign"}]]
                 fx)
              "the classified fx's token is redacted; the control fx rides raw")
          (is (not (contains-secret? ev))
              "the secret appears nowhere in the run-end trace"))
        (finally
          (rf/unregister-listener! :trace ::fx))))))

;; ---------------------------------------------------------------------------
;; Projector: every value position, and the frameless fail-closed arm
;; ---------------------------------------------------------------------------

(deftest project-trace-event-walks-every-after-delta-value-position
  (testing "the chokepoint projects :db under :added / :removed and both sides
            of :changed, in both segments, and fails CLOSED with no frame"
    (seed-classified-secret!)
    (let [db     {:auth {:token secret} :n 1}
          seg    {:added   {:db db}
                  :removed {:db db}
                  :changed {:db {:before db :after db}}}
          ev     (fn [frame]
                   {:op-type   :rf.event
                    :operation :rf.event/run-end
                    :tags      {:frame                 frame
                                :rf.event/after-deltas
                                [{:rf.interceptor.delta/id        :x/after
                                  :rf.interceptor.delta/ctx-delta {:coeffects seg
                                                                   :effects   seg}}]}})
          framed (rf.classification/project-trace-event (ev frame-id))
          bare   (rf.classification/project-trace-event (ev nil))
          diff   (fn [e] (get-in e [:tags :rf.event/after-deltas 0
                                     :rf.interceptor.delta/ctx-delta]))]
      (is (contains-secret? (ev frame-id)) "control: the input carries the secret")
      (doseq [segment [:coeffects :effects]
              path    [[:added :db] [:removed :db]
                       [:changed :db :before] [:changed :db :after]]]
        (is (= rf.privacy/redacted-sentinel
               (get-in (diff framed) (into [segment] (conj path :auth :token))))
            (str "framed: " segment " " path " redacts the classified path"))
        (is (= 1 (get-in (diff framed) (into [segment] (conj path :n))))
            (str "framed: " segment " " path " keeps the unclassified value"))
        (is (= rf.privacy/redacted-sentinel (get-in (diff bare) (into [segment] path)))
            (str "frameless: " segment " " path " fails closed to the sentinel")))
      (is (not (contains-secret? framed)))
      (is (not (contains-secret? bare))))))

;; ---------------------------------------------------------------------------
;; rf2-fc84b — FOCUSED-SLICE values are walked at their TRUE app-db focus
;; ---------------------------------------------------------------------------

(def ^:private focus-sentinel
  "The needle every fc84b leg counts. The old and the new secret both carry
  it; no event vector does."
  "FOCUS-SENTINEL-fc84b")

(def ^:private old-secret (str focus-sentinel "-old"))
(def ^:private new-secret (str focus-sentinel "-new"))

(defn- sentinel-paths
  "Every path in `x` at which a string carrying `focus-sentinel` sits (map keys
  included) — the count the ruling measured before the fix."
  [x]
  (letfn [(hit? [s] (and (string? s)
                         #?(:clj  (.contains ^String s ^String focus-sentinel)
                            :cljs (not= -1 (.indexOf s focus-sentinel)))))
          (walk [p v]
            (cond
              (hit? v)  [p]
              (map? v)  (mapcat (fn [[k x]]
                                  (concat (when (hit? k) [(conj p k)])
                                          (walk (conj p k) x)))
                                v)
              (coll? v) (mapcat (fn [i x] (walk (conj p i) x)) (range) v)
              :else     nil))]
    (vec (walk [] x))))

(defn- seed!
  "Make the frame, then write `db` — and classify `sensitive`, when given — in
  ONE event."
  [db sensitive]
  (rf/make-frame {:id frame-id})
  (rf/reg-event :fc84b/seed
    (fn [_ _] (cond-> {:db db} (seq sensitive) (assoc :sensitive sensitive))))
  (rf/dispatch-sync [:fc84b/seed] {:frame frame-id}))

(defn- run-focused!
  "Dispatch `event` on the frame and return its delivered `:rf.event/run-end`."
  [event]
  (let [acc (collect-traces! ::focused)]
    (try
      (rf/dispatch-sync event {:frame frame-id})
      (run-end @acc)
      (finally
        (rf/unregister-listener! :trace ::focused)))))

(defn- delta-ids [ev]
  (mapv :rf.interceptor.delta/id (get-in ev [:tags :rf.event/after-deltas])))

(defn- diff-of [ev i]
  (get-in ev [:tags :rf.event/after-deltas i :rf.interceptor.delta/ctx-delta]))

(deftest ^:requires-debug path-interceptor-before-slices-redact-at-their-focus
  (testing "[:rf.interceptor/path [:auth]] writing :token — the handler saw the
            slice {:token …} and returned one, so both :before values are
            slices at [:auth] and a root walk missed [:auth :token]. A benign
            sibling in the same slice stays visible: redaction is per declared
            path, never wholesale"
    (seed! {:auth {:token old-secret :user-name "alice"}} [[:auth :token]])
    (rf/reg-event :fc84b/set-token
      {:interceptors [[:rf.interceptor/path [:auth]]]}
      (fn [{:keys [db]} _] {:db (assoc db :token new-secret)}))
    (let [ev   (run-focused! [:fc84b/set-token])
          diff (diff-of ev 0)]
      (is (= [:rf.interceptor/path] (delta-ids ev))
          "producer control: the path interceptor wrote the after-delta")
      (is (= [] (sentinel-paths ev))
          "no secret survives anywhere in the run-end trace")
      (is (= rf.privacy/redacted-sentinel
             (get-in diff [:coeffects :changed :db :before :token]))
          "the slice the handler saw has its token redacted")
      (is (= rf.privacy/redacted-sentinel
             (get-in diff [:effects :changed :db :before :token]))
          "the slice the handler returned has its token redacted")
      (is (= "alice" (get-in diff [:coeffects :changed :db :before :user-name]))
          "the benign sibling survives in the coeffect slice")
      (is (= "alice" (get-in diff [:effects :changed :db :before :user-name]))
          "the benign sibling survives in the effect slice")
      (is (= "alice" (get-in diff [:effects :changed :db :after :auth :user-name]))
          "the root-anchored :after value keeps its benign sibling too"))))

(deftest ^:requires-debug nested-focus-redacts-inner-and-outer-slices
  (testing "nested [:session] then [:creds] writing :pin — the inner
            interceptor's :before values are slices at [:session :creds], and
            even its :AFTER values are slices of the OUTER focus [:session]"
    (seed! {:session {:creds {:pin old-secret}}} [[:session :creds :pin]])
    (rf/reg-event :fc84b/set-pin
      {:interceptors [[:rf.interceptor/path [:session]]
                      [:rf.interceptor/path [:creds]]]}
      (fn [{:keys [db]} _] {:db (assoc db :pin new-secret)}))
    (let [ev (run-focused! [:fc84b/set-pin])]
      (is (= [:rf.interceptor/path :rf.interceptor/path] (delta-ids ev))
          "producer control: both path interceptors wrote an after-delta")
      (is (= [] (sentinel-paths ev))
          "no secret survives anywhere in the run-end trace")
      (is (= rf.privacy/redacted-sentinel
             (get-in (diff-of ev 0) [:effects :changed :db :after :creds :pin]))
          "the inner interceptor's :after value is walked at the outer focus"))))

(deftest ^:requires-debug user-after-inside-a-focus-redacts-both-its-slices
  (testing "a user :after positioned INSIDE the [:auth] focus runs while :db is
            still focused, so both of its :db values are slices at [:auth]"
    (seed! {:auth {:token old-secret}} [[:auth :token]])
    (rf/reg-interceptor :fc84b/touch
      {:after (fn [ctx] (assoc-in ctx [:effects :db :touched] true))})
    (rf/reg-event :fc84b/set-token-touched
      {:interceptors [[:rf.interceptor/path [:auth]] :fc84b/touch]}
      (fn [{:keys [db]} _] {:db (assoc db :token new-secret)}))
    (let [ev   (run-focused! [:fc84b/set-token-touched])
          diff (diff-of ev 0)]
      (is (= [:fc84b/touch :rf.interceptor/path] (delta-ids ev))
          "producer control: the user :after inside the focus ran first")
      (is (true? (get-in diff [:effects :changed :db :after :touched]))
          "the user :after's own change survives — the diff still reads")
      (is (= rf.privacy/redacted-sentinel
             (get-in diff [:effects :changed :db :before :token])))
      (is (= rf.privacy/redacted-sentinel
             (get-in diff [:effects :changed :db :after :token])))
      (is (= [] (sentinel-paths ev))
          "no secret survives anywhere in the run-end trace"))))

(deftest ^:requires-debug ancestor-declaration-redacts-a-deeper-focus-whole
  (testing "declare [[:auth]] and focus [:auth :session] — the slice sits
            BELOW a sensitive ancestor, so it is :rf/redacted whole (a root walk
            over the slice never meets :auth)"
    (seed! {:auth {:session {:sid old-secret}}} [[:auth]])
    (rf/reg-event :fc84b/set-sid
      {:interceptors [[:rf.interceptor/path [:auth :session]]]}
      (fn [{:keys [db]} _] {:db (assoc db :sid new-secret)}))
    (let [ev   (run-focused! [:fc84b/set-sid])
          diff (diff-of ev 0)]
      (is (= [:rf.interceptor/path] (delta-ids ev)) "producer control")
      (is (= rf.privacy/redacted-sentinel (get-in diff [:coeffects :changed :db :before])))
      (is (= rf.privacy/redacted-sentinel (get-in diff [:effects :changed :db :before])))
      (is (= [] (sentinel-paths ev))))))

(deftest ^:requires-debug indexed-focus-honours-an-index-free-declaration
  (testing "the index-free [[:items :token]] governs every element; a focus of
            [:items 0] crosses the integer segment, so the slice's token
            redacts and its benign sibling survives"
    (seed! {:items [{:token old-secret :label "first"}]} [[:items :token]])
    (rf/reg-event :fc84b/set-item-token
      {:interceptors [[:rf.interceptor/path [:items 0]]]}
      (fn [{:keys [db]} _] {:db (assoc db :token new-secret)}))
    (let [ev   (run-focused! [:fc84b/set-item-token])
          diff (diff-of ev 0)]
      (is (= [:rf.interceptor/path] (delta-ids ev)) "producer control")
      (is (= rf.privacy/redacted-sentinel
             (get-in diff [:coeffects :changed :db :before :token])))
      (is (= "first" (get-in diff [:coeffects :changed :db :before :label])))
      (is (= [] (sentinel-paths ev))))))

(deftest ^:requires-debug unclassified-frame-keeps-focused-slices-raw
  (testing "control: with NOTHING classified the focused slices ride raw
            (identity), so the redaction above is classification-driven, not a
            vanished slot"
    (seed! {:auth {:token old-secret}} nil)
    (rf/reg-event :fc84b/set-token-raw
      {:interceptors [[:rf.interceptor/path [:auth]]]}
      (fn [{:keys [db]} _] {:db (assoc db :token new-secret)}))
    (let [ev   (run-focused! [:fc84b/set-token-raw])
          diff (diff-of ev 0)]
      (is (= [:rf.interceptor/path] (delta-ids ev)) "producer control")
      (is (= old-secret (get-in diff [:coeffects :changed :db :before :token])))
      (is (= new-secret (get-in diff [:effects :changed :db :before :token])))
      (is (= 4 (count (sentinel-paths ev)))
          "both slices and both root values carry their secret raw"))))

(deftest ^:requires-debug focus-carrier-never-egresses
  (testing "the before/after focus rides a PRIVATE metadata carrier the
            projector strips unconditionally: the listener's, the ring's and
            the epoch record's copies each hold records with exactly the two
            closed :rf.interceptor.delta/* keys and no metadata"
    (seed! {:auth {:token old-secret}} [[:auth :token]])
    (rf/reg-event :fc84b/set-token-carried
      {:interceptors [[:rf.interceptor/path [:auth]]]}
      (fn [{:keys [db]} _] {:db (assoc db :token new-secret)}))
    (let [ev       (run-focused! [:fc84b/set-token-carried])
          run-end? #(= :rf.event/run-end (:operation %))
          ring-ev  (last (filter run-end? (rf/trace-buffer frame-id {:flat true})))
          epoch-ev (first (filter run-end? (:trace-events (last (rf/epoch-history frame-id)))))
          closed   #{:rf.interceptor.delta/id :rf.interceptor.delta/ctx-delta}]
      (doseq [[where e] [[:listener ev] [:ring ring-ev] [:epoch epoch-ev]]]
        (let [recs (get-in e [:tags :rf.event/after-deltas])]
          (is (= [:rf.interceptor/path] (mapv :rf.interceptor.delta/id recs))
              (str where ": the record is present"))
          (doseq [rec recs]
            (is (= closed (set (keys rec))) (str where ": exactly the two closed keys"))
            (is (empty? (meta rec)) (str where ": no carrier metadata"))))))))

(deftest ^:requires-debug unknown-focus-fails-closed-on-a-classified-frame
  (testing "a path-stack entry that records NO path (a hand-built entry, or a
            replacement standard) leaves that context's focus UNKNOWN, and on a
            classified frame its :db values are :rf/redacted whole. The same
            chain over an entry that DOES record a path walks at that offset"
    (seed! {:auth {:token old-secret}} [[:auth :token]])
    (let [touch   (rf.interceptor/->interceptor*
                    :id    :fc84b/hand-touch
                    :after (fn [ctx] (assoc-in ctx [:effects :db :touched] true)))
          slice   {:token old-secret}
          record  (fn [entry]
                    (first (:rf/interceptor-after-deltas
                             (rf.interceptor/execute-chain
                               [touch]
                               {:coeffects                 {:db slice}
                                :effects                   {:db slice}
                                :rf.interceptor.path/stack [entry]}))))
          project (fn [rec]
                    (rf.classification/project-trace-event
                      {:op-type   :rf.event
                       :operation :rf.event/run-end
                       :tags      {:frame frame-id :rf.event/after-deltas [rec]}}))
          db-diff (fn [e] (get-in e [:tags :rf.event/after-deltas 0
                                     :rf.interceptor.delta/ctx-delta :effects :changed :db]))
          unknown (project (record [{:auth slice} slice]))
          known   (project (record [{:auth slice} slice [:auth]]))]
      (is (= {:before rf.privacy/redacted-sentinel :after rf.privacy/redacted-sentinel}
             (db-diff unknown))
          "no recorded path: both sides fail closed")
      (is (= {:before {:token rf.privacy/redacted-sentinel}
              :after  {:token rf.privacy/redacted-sentinel :touched true}}
             (db-diff known))
          "control: a recorded [:auth] focus walks the slice at its offset")
      (is (= [] (sentinel-paths unknown)))
      (is (= [] (sentinel-paths known))))))
