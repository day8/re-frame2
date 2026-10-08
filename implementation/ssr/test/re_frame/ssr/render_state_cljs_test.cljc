(ns re-frame.ssr.render-state-cljs-test
  "The render-state contract (`re-frame.ssr.render-state`): `project` on a
  settled server frame, `serialize` / `deserialize` through the payload's EDN
  domain, `restore!` into a FRESH frame — both partitions, on both hosts.

  Handlers, machines and subs are registered INSIDE each test body under
  per-test ids: in the shared node process a sibling namespace's
  `registrar/clear-all!` wipes ns-load-time registrations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Loading the machines artefact publishes the late-bound
            ;; `:machines/project-ssr-runtime-db` hook the projector applies
            ;; to snapshot `:data`.
            [re-frame.machines]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.subs :as rf.subs]))

;; COLD-START the adapter slot: this ns shares the node bundle with suites
;; that seat other adapters, and `init!` raises when handed a different one.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private counter (atom 0))

(defn- fresh-id [prefix]
  (keyword "rf.ssrrs" (str prefix (swap! counter inc))))

(defn- fresh-frame!
  "A frame under an id no other test in this shared process has used, with
  NO initial events — the shape a renderer's per-request frame has before
  `restore!`."
  [platform]
  (let [fid (fresh-id (name platform))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- thrown-data
  "The ex-data of the structured error `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (ex-data e))))

(def ^:private corpus-app-db
  "Every value class `manifest/edn-carryable?` admits, under top-level keys.
  The integers stop at 2^53 - 1, the largest a browser number holds exactly."
  {:nil-value nil
   :booleans  [true false]
   :integers  [0 1 -1 42 9007199254740991 -9007199254740991]
   :doubles   [0.5 -0.25 1.0E308 0.0025]
   :strings   ["" "plain" "with \"quotes\"" "line\nbreak" "tab\tin"
               "</script><!-- breakout" "unicode — “é” ✓"]
   :keywords  [:plain :ns/qualified :a.b.c/d-e_f :with.dots]
   :symbols   ['sym 'ns/sym]
   :vector    [1 "two" :three 'four nil]
   :list      '(1 2 3)
   :set       #{:a :b "c" 4}
   :nested    {:a {:b {:c [1 {:d #{:e}}]}}}
   :odd-keys  {"string-key" 1 42 :int-key [1 2] :vector-key :kw 'sym}
   :empties   [[] {} #{} () ""]
   :todos     [{:id 1 :text "buy milk" :done? false}
               {:id 2 :text "ship slice C" :done? true}]
   :session   {:user "u-42" :token "secret-session-token"}})

(def ^:private route-slice
  {:route-id  :route/article
   :params    {:slug "hello-world"}
   :query     {:tab "comments" :page 2}
   :fragment  nil
   :nav-token 3})

(def ^:private auth-machine
  {:initial :anon
   :data    {:retries 0 :token nil}
   :actions {:remember (fn [{data :data}]
                         {:data (assoc data :retries 1 :token "secret-machine-token")})}
   :states  {:anon   {:on {:login :authed}}
             :authed {:entry :remember}}})

(defn- settled-server-frame!
  "A server frame holding the corpus app-db, a REAL machine snapshot (the
  machine ran to `:authed` on this frame), a route slice beside a transient
  routing sibling, ssr metadata, a runtime-db key the projector has no
  vocabulary for, and frame classification of `[:session :token]` and of the
  snapshot's `:data :token`."
  [mid]
  (let [sfid (fresh-frame! :server)]
    (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key corpus-app-db})
    (rf/reg-machine mid auth-machine)
    (rf/dispatch-sync [mid [:login]] {:frame sfid})
    (rf.frame/swap-runtime-db! sfid merge
                            {:rf.runtime/routing {:current            route-slice
                                                  :pending-navigation {:to :route/next}}
                             :rf.runtime/ssr     {:hydration {:version 1}}
                             :rf.runtime/custom  {:tenant "acme"}})
    (let [classify-app (fresh-id "classify-app")
          classify-rt  (fresh-id "classify-rt")]
      (rf/reg-event classify-app (fn [_ _] {:sensitive [[:session :token]]}))
      (rf/reg-event classify-rt
                    (fn [_ _] {:sensitive [[:rf.runtime/machines :snapshots mid :data :token]]}))
      (rf/dispatch-sync [classify-app] {:frame sfid})
      (rf/dispatch-sync [classify-rt] {:frame sfid}))
    sfid))

(def ^:private full-policy
  {:render-state {:app-db     (vec (keys corpus-app-db))
                  :runtime-db [:rf.runtime/machines :rf.runtime/routing
                               :rf.runtime/ssr :rf.runtime/custom]}})

(defn- round-trip
  "The wire, there and back: `serialize` then `deserialize`."
  [partitions]
  (rf.ssr.render-state/deserialize (rf.ssr.render-state/serialize partitions)))

;; ---------------------------------------------------------------------------
;; project: allowlists, classification, the projector's vocabulary
;; ---------------------------------------------------------------------------

(deftest project-applies-the-allowlists-and-the-frames-classification
  (let [mid  (fresh-id "auth")
        sfid (settled-server-frame! mid)
        {app :rf/app-db rt :rf/runtime-db :as projected}
        (rf.ssr.render-state/project sfid full-policy)]
    (testing "app-db: every allowlisted key, the classified path redacted path-precisely"
      (is (= (assoc-in corpus-app-db [:session :token] :rf/redacted) app)))
    (testing "runtime-db: the real machine snapshot, classified :data redacted"
      (is (= :authed (get-in rt [:rf.runtime/machines :snapshots mid :state])))
      (is (= {:retries 1 :token :rf/redacted}
             (get-in rt [:rf.runtime/machines :snapshots mid :data]))))
    (testing "routing narrows to its durable :current; ssr metadata rides; a key
              outside the projector's vocabulary rides verbatim"
      (is (= {:rf.runtime/routing {:current route-slice}
              :rf.runtime/ssr     {:hydration {:version 1}}
              :rf.runtime/custom  {:tenant "acme"}}
             (select-keys rt [:rf.runtime/routing :rf.runtime/ssr :rf.runtime/custom]))))
    (testing "no secret survives anywhere in the projection"
      (is (not (re-find #"secret-(session|machine)-token" (pr-str projected)))))
    (testing "an absent partition slot projects that partition as {}"
      (is (= {} (:rf/runtime-db (rf.ssr.render-state/project sfid {:render-state {:app-db [:todos]}}))))
      (is (= {} (:rf/app-db (rf.ssr.render-state/project sfid {:render-state {:runtime-db [:rf.runtime/ssr]}})))))
    (testing "the escape hatch is handed the live frame's id and replaces the allowlist step"
      (let [seen (atom nil)
            out  (rf.ssr.render-state/project
                   sfid {:render-state (fn [fid]
                                         (reset! seen fid)
                                         {:rf/app-db (select-keys (rf.frame/frame-app-db-value fid) [:todos])})})]
        (is (= sfid @seen))
        (is (= {:rf/app-db     {:todos (:todos corpus-app-db)}
                :rf/runtime-db {}}
               out)
            "an absent :rf/runtime-db normalises to {} — both keys are always present")))))

(deftest project-honours-the-hosts-sensitive-permit-inside-its-own-allowlist
  ;; The renderer prints what the render state carries, so the host's
  ;; `:payload-include-sensitive` must reach it too, or the markup and the
  ;; payload disagree on a permitted value.
  (let [mid      (fresh-id "auth")
        sfid     (settled-server-frame! mid)
        classify (fresh-id "classify-user")]
    (rf/reg-event classify (fn [_ _] {:sensitive [[:session :user]]}))
    (rf/dispatch-sync [classify] {:frame sfid})
    (is (= {:session {:user :rf/redacted :token "secret-session-token"}
            :todos   (:todos corpus-app-db)}
           (:rf/app-db (rf.ssr.render-state/project
                         sfid {:render-state              {:app-db [:session :todos]}
                               :payload-include-sensitive [[:session :token]]})))
        "the permitted value rides raw; the classified sibling it does not name stays redacted")
    (testing "the permit applies only inside :render-state's own allowlist"
      (is (not (contains? (:rf/app-db (rf.ssr.render-state/project
                                        sfid {:render-state              {:app-db [:todos]}
                                              :payload-include-sensitive [[:session :token]]}))
                          :session))))))

;; ---------------------------------------------------------------------------
;; The round-trip corpus, both partitions, restore! identical
;; ---------------------------------------------------------------------------

(deftest round-trip-corpus-restores-both-partitions-identically
  (let [mid       (fresh-id "auth")
        sfid      (settled-server-frame! mid)
        projected (rf.ssr.render-state/project sfid full-policy)
        wire      (rf.ssr.render-state/serialize projected)]
    (testing "the wire form is key text -> EDN text, per key, for both partitions"
      (is (every? (fn [[k v]] (and (string? k) (string? v)))
                  (concat (:rf/app-db wire) (:rf/runtime-db wire))))
      (is (contains? (:rf/app-db wire) ":todos"))
      (is (contains? (:rf/runtime-db wire) ":rf.runtime/routing")))
    (let [read-back (round-trip projected)]
      (testing "pr-str -> the safe reader is EXACT for every value class"
        (is (= projected read-back)))
      (let [cfid (fresh-frame! :server)]
        (testing "restore! seeds the fresh frame with both partitions in one write"
          (is (= #{rf.frame/app-partition-key rf.frame/runtime-partition-key}
                 (rf.ssr.render-state/restore! cfid read-back)))
          (is (= [(:rf/app-db projected) (:rf/runtime-db projected)]
                 [(rf.frame/frame-app-db-value cfid) (rf.frame/frame-runtime-db-value cfid)])
              "EXACTLY the projection: no hydration metadata, no elision registry, no re-arm"))
        (testing "a runtime sub and an app sub on the fresh frame read the restored state"
          (let [machine-state (fresh-id "machine-state")
                todo-count    (fresh-id "todo-count")]
            (rf.subs/reg-runtime-sub machine-state {:doc "test"}
                                  (fn [rt [_ id]] (get-in rt [:rf.runtime/machines :snapshots id :state])))
            (rf/reg-sub todo-count (fn [db _] (count (:todos db))))
            (is (= :authed (rf/subscribe-once [machine-state mid] {:frame cfid})))
            (is (= 2 (rf/subscribe-once [todo-count] {:frame cfid})))))))))

(deftest deserialize-reads-an-absent-partition-as-empty-and-restore-installs-it
  (let [cfid (fresh-frame! :server)]
    (is (= {:rf/app-db {:a 1} :rf/runtime-db {}}
           (rf.ssr.render-state/deserialize {:rf/app-db {":a" "1"}})))
    (is (= #{rf.frame/app-partition-key}
           (rf.ssr.render-state/restore! cfid {:rf/app-db {:a 1}}))
        "only app-db changed — runtime-db was already {}")
    (is (= [{:a 1} {}]
           [(rf.frame/frame-app-db-value cfid) (rf.frame/frame-runtime-db-value cfid)]))))

;; ---------------------------------------------------------------------------
;; The negative fixture: unserialisable fails AT PROJECTION
;; ---------------------------------------------------------------------------

(deftest an-unserialisable-value-fails-at-projection-with-a-named-error
  (testing "a fn under an allowlisted app-db key (the allowlist path)"
    (let [sfid (fresh-frame! :server)]
      (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:todos [] :on-click (fn [] :clicked)}})
      (is (= [:rf.error/ssr-render-state-invalid :unserialisable :rf/app-db :on-click :value]
             ((juxt :rf.error/id :invalid :partition :key :half)
              (thrown-data #(rf.ssr.render-state/project sfid {:render-state {:app-db [:todos :on-click]}})))))
      (is (= {:rf/app-db {:todos []} :rf/runtime-db {}}
             (rf.ssr.render-state/project sfid {:render-state {:app-db [:todos]}}))
          "control: the same frame with the fn left off the allowlist projects")))
  (testing "a fn returned by the escape-hatch projector, in the runtime partition"
    (is (= [:rf.error/ssr-render-state-invalid :unserialisable :rf/runtime-db :rf.runtime/custom]
           ((juxt :rf.error/id :invalid :partition :key)
            (thrown-data
              #(rf.ssr.render-state/project
                 (fresh-frame! :server)
                 {:render-state (fn [_] {:rf/app-db     {}
                                         :rf/runtime-db {:rf.runtime/custom (fn [] 1)}})}))))))
  (testing "an opaque top-level KEY — a string where a keyword must be"
    (is (= [:rf.error/ssr-render-state-invalid :key "todos"]
           ((juxt :rf.error/id :half :key)
            (thrown-data
              #(rf.ssr.render-state/project (fresh-frame! :server)
                                            {:render-state (fn [_] {:rf/app-db {"todos" []}})})))))))

;; ---------------------------------------------------------------------------
;; The omitted-key fixture: the honest wrong page
;; ---------------------------------------------------------------------------

(deftest an-allowlisted-key-the-frame-lacks-restores-to-nil-the-honest-wrong-page
  ;; Nothing to carry — no key, and no nil-valued key either — and no throw:
  ;; a view reading it renders nil, the operator's allowlist mistake.
  (let [sfid (fresh-frame! :server)]
    (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:todos [{:id 1}]}})
    (is (= {:rf/app-db {:todos [{:id 1}]} :rf/runtime-db {}}
           (rf.ssr.render-state/project sfid {:render-state {:app-db [:todos :user]}})))))

;; ---------------------------------------------------------------------------
;; The policy: fail-closed, distinct from :payload
;; ---------------------------------------------------------------------------

(defn- policy-error [opts]
  (thrown-data #(rf.ssr.render-state/validate-policy-opts! opts)))

(deftest render-state-policy-is-fail-closed-and-distinct-from-payload
  (testing "an empty policy, a whole-partition keyword, or a :payload opt
            alone → missing, naming :render-state"
    (doseq [opts [{:render-state {}}
                  {:render-state :rf.ssr.payload/whole-app-db}
                  {:payload [:todos]}]]
      (is (= [:rf.error/ssr-missing-payload-policy :render-state]
             ((juxt :rf.error/id :opt) (policy-error opts)))
          (pr-str opts))))
  (testing "malformed allowlists → the family's malformed id, naming :render-state"
    (doseq [[bad entries] [[{:app-db []} []]
                           [{:app-db ["todos"]} ["todos"]]
                           [{:app-db #{:a}} []]
                           [{:app-db [:a] :extra [:b]} []]
                           [{:runtime-db [:rf.runtime/elision]} []]]]
      (is (= [:rf.error/ssr-malformed-payload-allowlist :render-state entries]
             ((juxt :rf.error/id :opt :bad-entries) (policy-error {:render-state bad})))
          (pr-str bad))))
  (testing "a well-formed policy returns the opts unchanged — any sequential
            allowlist is admitted, not only a vector"
    (let [opts {:render-state {:app-db '(:a :b) :runtime-db [:rf.runtime/machines]} :payload [:a]}]
      (is (identical? opts (rf.ssr.render-state/validate-policy-opts! opts)))))
  (testing "project re-validates: the runtime arm fails the same way as the construction arm"
    (is (= [:rf.error/ssr-missing-payload-policy :render-state]
           ((juxt :rf.error/id :opt)
            (thrown-data #(rf.ssr.render-state/project (fresh-frame! :server) {:payload [:todos]})))))))

;; ---------------------------------------------------------------------------
;; The envelope, and liveness — fail-closed at both doors
;; ---------------------------------------------------------------------------

(deftest the-envelope-is-two-map-partitions-at-both-doors
  (testing "an escape-hatch projector returning something other than the envelope"
    (let [sfid (fresh-frame! :server)]
      (doseq [bad [[] {:rf/app-db "not a map"} {:rf/app-db {} :extra {}}]]
        (is (= [:rf.error/ssr-render-state-invalid :envelope]
               ((juxt :rf.error/id :invalid)
                (thrown-data #(rf.ssr.render-state/project sfid {:render-state (fn [_] bad)}))))
            (pr-str bad)))))
  (testing "restore! refuses the same shapes and installs nothing"
    (let [cfid (fresh-frame! :server)]
      (rf.frame/replace-frame-state! cfid {rf.frame/app-partition-key {:kept true}})
      (is (= [:rf.error/ssr-render-state-invalid :envelope]
             ((juxt :rf.error/id :invalid)
              (thrown-data #(rf.ssr.render-state/restore! cfid {:rf/app-db 1})))))
      (is (= {:kept true} (rf.frame/frame-app-db-value cfid)) "nothing was installed")))
  (testing "a frame that is not live"
    (let [gone (fresh-frame! :server)]
      (rf/destroy-frame! gone)
      (is (= :rf.error/frame-destroyed
             (:rf.error/id (thrown-data #(rf.ssr.render-state/project gone {:render-state {:app-db [:a]}})))))
      (is (= :rf.error/frame-destroyed
             (:rf.error/id (thrown-data #(rf.ssr.render-state/restore! gone {:rf/app-db {}}))))))))
