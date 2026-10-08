(ns re-frame.after-deltas-classification-cljs-test
  "The `:rf.event/after-deltas` slot on `:rf.event/run-end` carries one ctx
  diff per user `:after` interceptor, with its `:before` / `:after` VALUES. The
  standard `[:rf.interceptor/path …]` `:after` restores the WHOLE app-db, so
  every path-focused handler stamps the whole db there, and an `:after` that
  adds `:fx` puts that fx's args there; `project-trace-event` must classify
  both.

  Under a path focus a `:db` value is a FOCUSED SLICE, which a root-anchored
  walk cannot match against `[:auth :token]`. So each delta carries its
  absolute focus to the projector in a PRIVATE metadata carrier, each value is
  walked at its true offset, and an unknown focus on a classified frame fails
  closed.

  The live legs drive the real path interceptor and a real user `:after`, and
  each first proves the slot is populated, so a secret's absence is a fact
  about a slot that exists. They read the dev trace, so they are
  `^:requires-debug`; the projector legs run in both postures."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            ;; Publishes the epoch hooks `rf/epoch-history` reads.
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
  "Make the frame and classify `[:auth :token]` in the event that writes the secret."
  []
  (rf/make-frame {:id frame-id})
  (rf/reg-event :after-deltas/seed
    (fn [{:keys [db]} _]
      {:db        (assoc db :auth {:token secret} :counter 0)
       :sensitive [[:auth :token]]}))
  (rf/dispatch-sync [:after-deltas/seed] {:frame frame-id}))

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
                 fx))
          (is (not (contains-secret? ev))))
        (finally
          (rf/unregister-listener! :trace ::fx))))))

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
        (is (= {:auth {:token rf.privacy/redacted-sentinel} :n 1}
               (get-in (diff framed) (into [segment] path)))
            (str "framed: " segment " " path " redacts only the classified path"))
        (is (= rf.privacy/redacted-sentinel (get-in (diff bare) (into [segment] path)))
            (str "frameless: " segment " " path " fails closed to the sentinel"))))))

;; ---------------------------------------------------------------------------
;; FOCUSED-SLICE values are walked at their TRUE app-db focus
;; ---------------------------------------------------------------------------

(def ^:private focus-sentinel
  "The needle every focused-slice leg counts; the old and new secrets carry it,
  no event vector does."
  "FOCUS-SENTINEL-fc84b")

(def ^:private old-secret (str focus-sentinel "-old"))
(def ^:private new-secret (str focus-sentinel "-new"))

(defn- sentinel-paths
  "Every path in `x` at which a string carrying `focus-sentinel` sits (map keys
  included)."
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
  (testing "[:rf.interceptor/path [:auth]] writing :token — both :before values
            are slices at [:auth], and the :after values are the whole db; every
            one redacts the token and keeps the benign sibling"
    (seed! {:auth {:token old-secret :user-name "alice"}} [[:auth :token]])
    (rf/reg-event :fc84b/set-token
      {:interceptors [[:rf.interceptor/path [:auth]]]}
      (fn [{:keys [db]} _] {:db (assoc db :token new-secret)}))
    (let [ev   (run-focused! [:fc84b/set-token])
          diff (diff-of ev 0)
          r    rf.privacy/redacted-sentinel]
      (is (= [:rf.interceptor/path] (delta-ids ev))
          "producer control: the path interceptor wrote the after-delta")
      (is (= [] (sentinel-paths ev))
          "no secret survives anywhere in the run-end trace")
      (is (= {:token r :user-name "alice"} (get-in diff [:coeffects :changed :db :before]))
          "the slice the handler saw")
      (is (= {:token r :user-name "alice"} (get-in diff [:effects :changed :db :before]))
          "the slice the handler returned")
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
      (is (= [] (sentinel-paths ev)))
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
    (let [ev (run-focused! [:fc84b/set-token-touched])]
      (is (= [:fc84b/touch :rf.interceptor/path] (delta-ids ev))
          "producer control: the user :after inside the focus ran first")
      (is (= {:before {:token rf.privacy/redacted-sentinel}
              :after  {:token rf.privacy/redacted-sentinel :touched true}}
             (get-in (diff-of ev 0) [:effects :changed :db]))
          "both slices redact; the user :after's own change survives")
      (is (= [] (sentinel-paths ev))))))

(deftest ^:requires-debug ancestor-declaration-redacts-a-deeper-focus-whole
  (testing "declare [[:auth]] and focus [:auth :session] — the slice sits BELOW a
            sensitive ancestor, so it is :rf/redacted whole"
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
    (let [ev (run-focused! [:fc84b/set-item-token])]
      (is (= [:rf.interceptor/path] (delta-ids ev)) "producer control")
      (is (= {:token rf.privacy/redacted-sentinel :label "first"}
             (get-in (diff-of ev 0) [:coeffects :changed :db :before])))
      (is (= [] (sentinel-paths ev))))))

(deftest ^:requires-debug unclassified-frame-keeps-focused-slices-raw
  (testing "control: with NOTHING classified the focused slices ride raw, so the
            redaction above is classification-driven, not a vanished slot"
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
  (testing "the focus rides a PRIVATE metadata carrier the projector strips: the
            listener's, the ring's and the epoch record's copies each hold
            records with exactly the two closed keys and no metadata"
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
  (testing "a path-stack entry that records NO path leaves that context's focus
            UNKNOWN, and on a classified frame its :db values are :rf/redacted
            whole. The same chain over an entry that DOES record a path walks at
            that offset"
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
