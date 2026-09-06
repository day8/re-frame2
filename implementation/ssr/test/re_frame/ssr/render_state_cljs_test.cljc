(ns re-frame.ssr.render-state-cljs-test
  "rf2-8arzr.3 — the render-state contract (`re-frame.ssr.render-state`):
  `project` on a settled server frame, `serialize` / `deserialize` through
  the payload's EDN domain, `restore!` into a FRESH frame — both partitions.

  Both hosts: a `.cljc` named `*-cljs-test`, so it runs under
  `clojure -M:test` from `implementation/ssr` (JVM) and under the node
  runner (`npm run test:cljs`). Handlers, machines and subs are registered
  INSIDE each test body under per-test ids — in the shared node process a
  sibling namespace's `registrar/clear-all!` wipes ns-load-time
  registrations (the same posture as `machine-after-rearm-cljs-test`), so
  nothing here leans on a sub or handler some other namespace installed.

  ## What is pinned

  - Acceptance 1 — the round-trip corpus: every value class the wire domain
    admits (`re-frame.ssr.manifest/edn-carryable?`) goes `project` ->
    `serialize` (pr-str) -> `deserialize` (the safe reader) -> `restore!`
    -> IDENTICAL, for BOTH partitions, with a route slice and a REAL machine
    snapshot (the machine ran on the server frame) in the runtime partition.
  - Acceptance 2 — the negative fixture: a fn under an allowlisted key, a
    fn returned by the escape-hatch projector, a record, and an opaque
    top-level key each fail AT PROJECTION with
    `:rf.error/ssr-render-state-invalid` (`:invalid :unserialisable`) —
    never a silent nil. Each has its control beside it.
  - Acceptance 3 — the omitted-key fixture: an allowlist naming a key the
    frame does not hold, and one omitting a key the view reads, both
    restore to `nil` where the value should be — the honest wrong page,
    no throw, the operator's allowlist mistake.
  - S3 — derived sensitivity rides along: a frame-classified `:sensitive`
    app-db path and machine-snapshot `:data` path project to `:rf/redacted`;
    the routing slice narrows to its durable `:current`; a runtime-db key
    the projector has no vocabulary for rides verbatim; and
    `:rf.runtime/elision` is refused at construction.
  - The policy is fail-closed and DISTINCT from `:payload`: absent / `{}` /
    a keyword / a `:payload` opt alone → `:rf.error/ssr-missing-payload-policy`
    carrying `:opt :render-state`; a malformed allowlist →
    `:rf.error/ssr-malformed-payload-allowlist` carrying the same.
  - `restore!` replays no boot events and runs none of the client's
    hydration concerns: the fresh frame is made with no `:initial-events`,
    and after `restore!` its partitions are EXACTLY the projection — no
    `[:rf.runtime/ssr :hydration]` metadata, no elision registry."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Loading the machines artefact publishes the late-bound
            ;; `:machines/project-ssr-runtime-db` hook the projector applies
            ;; to snapshot `:data`.
            [re-frame.machines]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.subs :as rf.subs]))

(use-fixtures :once (fn [f] (rf/init! rf.ssr/adapter) (f)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private counter (atom 0))

(defn- fresh-id [prefix]
  (keyword "rf.ssrrs" (str prefix (swap! counter inc))))

(defn- fresh-frame!
  "A frame of the given platform under an id no other test in this shared
  process has used, with NO initial events — the shape a renderer's
  per-request frame has before `restore!`."
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
  "Every value class `manifest/edn-carryable?` admits, under top-level
  keys, alongside the shapes an app-db actually carries. The integers stop
  at 2^53 - 1 — the largest a browser number holds exactly — because the
  domain is what BOTH hosts read back equal."
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
  "The durable route slice, in the shape `routing-state-classification`
  documents for `[:rf.runtime/routing :current]`."
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
  vocabulary for, and frame classification of one app-db path and of the
  snapshot's `:data :token` — the state a settled request frame carries."
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
    ;; Frame classification through the commit-plane `:sensitive` effect —
    ;; value-independent, read only at egress (EP-0025).
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
;; S3 — project: allowlists, classification, the projector's vocabulary
;; ---------------------------------------------------------------------------

(deftest project-applies-the-allowlists-and-the-frames-classification
  (let [mid  (fresh-id "auth")
        sfid (settled-server-frame! mid)
        {app :rf/app-db rt :rf/runtime-db :as projected}
        (rf.ssr.render-state/project sfid full-policy)]
    (testing "app-db: every allowlisted key, the classified path redacted"
      (is (= (set (keys corpus-app-db)) (set (keys app))))
      (is (= :rf/redacted (get-in app [:session :token]))
          "a frame-declared :sensitive app-db path redacts — derived sensitivity rides along")
      (is (= "u-42" (get-in app [:session :user]))
          "the unclassified sibling rides verbatim — the walk is path-precise")
      (is (= (dissoc corpus-app-db :session) (dissoc app :session))))
    (testing "runtime-db: the real machine snapshot, classified :data redacted"
      (is (= :authed (get-in rt [:rf.runtime/machines :snapshots mid :state])))
      (is (= :rf/redacted (get-in rt [:rf.runtime/machines :snapshots mid :data :token])))
      (is (= 1 (get-in rt [:rf.runtime/machines :snapshots mid :data :retries]))))
    (testing "runtime-db: routing narrows to its durable :current"
      (is (= {:current route-slice} (:rf.runtime/routing rt))
          ":pending-navigation is transient and stays off the wire"))
    (testing "runtime-db: ssr metadata rides; a key outside the projector's vocabulary rides verbatim"
      (is (= {:hydration {:version 1}} (:rf.runtime/ssr rt)))
      (is (= {:tenant "acme"} (:rf.runtime/custom rt))
          "the operator named it, top-level, explicitly — silently absent would be the wrong page"))
    (testing "no secret survives anywhere in the projection"
      (is (not (str/includes? (pr-str projected) "secret-session-token")))
      (is (not (str/includes? (pr-str projected) "secret-machine-token"))))
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

;; ---------------------------------------------------------------------------
;; Acceptance 1 — the round-trip corpus, both partitions, restore! identical
;; ---------------------------------------------------------------------------

(deftest round-trip-corpus-restores-both-partitions-identically
  (let [mid       (fresh-id "auth")
        sfid      (settled-server-frame! mid)
        projected (rf.ssr.render-state/project sfid full-policy)
        wire      (rf.ssr.render-state/serialize projected)]
    (testing "the wire form is key text -> EDN text, per key, for both partitions"
      (is (= #{:rf/app-db :rf/runtime-db} (set (keys wire))))
      (is (every? (fn [[k v]] (and (string? k) (string? v)))
                  (concat (:rf/app-db wire) (:rf/runtime-db wire))))
      (is (contains? (:rf/app-db wire) ":todos"))
      (is (contains? (:rf/runtime-db wire) ":rf.runtime/routing")))
    (let [read-back (round-trip projected)]
      (testing "pr-str -> the safe reader is EXACT for every value class"
        (is (= projected read-back)))
      (let [cfid (fresh-frame! :server)]
        (testing "restore! seeds the fresh frame with both partitions in one write"
          (is (empty? (rf.frame/frame-app-db-value cfid))
              "precondition: the fresh frame ran no boot events")
          (is (= #{rf.frame/app-partition-key rf.frame/runtime-partition-key}
                 (rf.ssr.render-state/restore! cfid read-back)))
          (is (= (:rf/app-db projected) (rf.frame/frame-app-db-value cfid)))
          (is (= (:rf/runtime-db projected) (rf.frame/frame-runtime-db-value cfid))
              "EXACTLY the projection: no hydration metadata, no elision registry, no re-arm"))
        (testing "framework and app subs on the fresh frame read the restored state"
          (rf.subs/reg-runtime-sub (fresh-id "machine-state") {:doc "test"}
                                (fn [rt [_ id]] (get-in rt [:rf.runtime/machines :snapshots id :state])))
          (let [machine-state (keyword "rf.ssrrs" (str "machine-state" @counter))
                route-id      (fresh-id "route-id")
                todo-count    (fresh-id "todo-count")]
            (rf.subs/reg-runtime-sub route-id {:doc "test"}
                                  (fn [rt _] (get-in rt [:rf.runtime/routing :current :route-id])))
            (rf/reg-sub todo-count (fn [db _] (count (:todos db))))
            (is (= :authed (rf/subscribe-once [machine-state mid] {:frame cfid}))
                "the machine snapshot, read the way [:rf/machine id] reads it")
            (is (= :route/article (rf/subscribe-once [route-id] {:frame cfid}))
                "the route slice, read the way [:rf/route] reads it")
            (is (= 2 (rf/subscribe-once [todo-count] {:frame cfid})))))))))

(deftest deserialize-reads-an-absent-partition-as-empty-and-restore-installs-it
  (let [cfid (fresh-frame! :server)]
    (is (= {:rf/app-db {:a 1} :rf/runtime-db {}}
           (rf.ssr.render-state/deserialize {:rf/app-db {":a" "1"}})))
    (is (= #{rf.frame/app-partition-key}
           (rf.ssr.render-state/restore! cfid {:rf/app-db {:a 1}}))
        "only app-db changed — runtime-db was already {}")
    (is (= {:a 1} (rf.frame/frame-app-db-value cfid)))
    (is (= {} (rf.frame/frame-runtime-db-value cfid)))))

;; ---------------------------------------------------------------------------
;; Acceptance 2 — the negative fixture: unserialisable fails AT PROJECTION
;; ---------------------------------------------------------------------------

(defrecord Opaque [x])

(deftest an-unserialisable-value-fails-at-projection-with-a-named-error
  (testing "a fn under an allowlisted app-db key (the allowlist path)"
    (let [sfid (fresh-frame! :server)]
      (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:todos [] :on-click (fn [] :clicked)}})
      (let [data (thrown-data #(rf.ssr.render-state/project sfid {:render-state {:app-db [:todos :on-click]}}))]
        (is (= :rf.error/ssr-render-state-invalid (:rf.error/id data)))
        (is (= :unserialisable (:invalid data)))
        (is (= :rf/app-db (:partition data)))
        (is (= :on-click (:key data)))
        (is (= :value (:half data))))
      ;; CONTROL — the same frame with the fn left off the allowlist projects.
      (is (= {:rf/app-db {:todos []} :rf/runtime-db {}}
             (rf.ssr.render-state/project sfid {:render-state {:app-db [:todos]}})))))
  (testing "a fn returned by the escape-hatch projector, in the runtime partition"
    (let [sfid (fresh-frame! :server)
          data (thrown-data
                 #(rf.ssr.render-state/project
                    sfid {:render-state (fn [_] {:rf/app-db     {}
                                                 :rf/runtime-db {:rf.runtime/custom (fn [] 1)}})}))]
      (is (= :rf.error/ssr-render-state-invalid (:rf.error/id data)))
      (is (= :unserialisable (:invalid data)))
      (is (= :rf/runtime-db (:partition data)))
      (is (= :rf.runtime/custom (:key data)))))
  (testing "a record — map-shaped, not map-printing (it prints as a tagged literal)"
    (let [sfid (fresh-frame! :server)
          data (thrown-data
                 #(rf.ssr.render-state/project sfid {:render-state (fn [_] {:rf/app-db {:r (->Opaque 1)}})}))]
      (is (= :rf.error/ssr-render-state-invalid (:rf.error/id data)))
      (is (= :r (:key data)))
      ;; CONTROL — converted explicitly, the same value rides.
      (is (= {:rf/app-db {:r {:x 1}} :rf/runtime-db {}}
             (rf.ssr.render-state/project sfid {:render-state (fn [_] {:rf/app-db {:r (into {} (->Opaque 1))}})})))))
  (testing "an opaque top-level KEY — a string where a keyword must be"
    (let [sfid (fresh-frame! :server)
          data (thrown-data
                 #(rf.ssr.render-state/project sfid {:render-state (fn [_] {:rf/app-db {"todos" []}})}))]
      (is (= :rf.error/ssr-render-state-invalid (:rf.error/id data)))
      (is (= :key (:half data)))
      (is (= "todos" (:key data))))))

;; ---------------------------------------------------------------------------
;; Acceptance 3 — the omitted-key fixture: the honest wrong page
;; ---------------------------------------------------------------------------

(deftest an-allowlisted-key-the-frame-lacks-restores-to-nil-the-honest-wrong-page
  (let [sfid (fresh-frame! :server)
        view (fn [db]
               (str "<h1>" (get-in db [:user :name]) "</h1><ul>" (count (:todos db)) "</ul>"))]
    (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:todos [{:id 1}]}})
    (testing "the allowlist names :user, which the frame never held"
      (let [projected (rf.ssr.render-state/project sfid {:render-state {:app-db [:todos :user]}})
            cfid      (fresh-frame! :server)]
        (is (not (contains? (:rf/app-db projected) :user))
            "nothing to carry: no key, and no nil-valued key either")
        (rf.ssr.render-state/restore! cfid (round-trip projected))
        (is (nil? (get-in (rf.frame/frame-app-db-value cfid) [:user :name])))
        (is (= (view (rf.frame/frame-app-db-value sfid))
               (view (rf.frame/frame-app-db-value cfid)))
            "and it is the same page the server would render — nil on both sides")))
    (testing "the allowlist OMITS :user, which the view reads — the operator's mistake, not a throw"
      (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:todos [{:id 1}] :user {:name "Ada"}}})
      (let [projected (rf.ssr.render-state/project sfid {:render-state {:app-db [:todos]}})
            cfid      (fresh-frame! :server)]
        (rf.ssr.render-state/restore! cfid (round-trip projected))
        (is (= "<h1>Ada</h1><ul>1</ul>" (view (rf.frame/frame-app-db-value sfid))))
        (is (= "<h1></h1><ul>1</ul>" (view (rf.frame/frame-app-db-value cfid)))
            "the honest wrong page: nil where :user should be, rendered without complaint")))))

;; ---------------------------------------------------------------------------
;; The policy: fail-closed, distinct from :payload
;; ---------------------------------------------------------------------------

(defn- policy-error [opts]
  (thrown-data #(rf.ssr.render-state/validate-policy-opts! opts)))

(deftest render-state-policy-is-fail-closed-and-distinct-from-payload
  (testing "absent / {} / a keyword / a :payload opt alone → missing, naming :render-state"
    (doseq [opts [{} {:render-state nil} {:render-state {}}
                  {:render-state :rf.ssr.payload/whole-app-db}
                  {:payload [:todos]}]]
      (let [data (policy-error opts)]
        (is (= :rf.error/ssr-missing-payload-policy (:rf.error/id data)) (pr-str opts))
        (is (= :render-state (:opt data)) (pr-str opts)))))
  (testing "malformed allowlists → the family's malformed id, naming :render-state"
    (doseq [[bad entries] [[{:app-db []} []]
                           [{:app-db ["todos"]} ["todos"]]
                           [{:app-db [:a nil]} [nil]]
                           [{:app-db #{:a}} []]
                           [{:app-db [:a] :extra [:b]} []]
                           [{:runtime-db [:rf.runtime/elision]} []]]]
      (let [data (policy-error {:render-state bad})]
        (is (= :rf.error/ssr-malformed-payload-allowlist (:rf.error/id data)) (pr-str bad))
        (is (= :render-state (:opt data)) (pr-str bad))
        (is (= entries (:bad-entries data)) (pr-str bad)))))
  (testing "well-formed policies return the opts unchanged"
    (doseq [good [{:app-db [:a]}
                  {:runtime-db [:rf.runtime/routing]}
                  {:app-db '(:a :b) :runtime-db [:rf.runtime/machines]}
                  (fn [_] {})]]
      (let [opts {:render-state good :payload [:a]}]
        (is (identical? opts (rf.ssr.render-state/validate-policy-opts! opts))))))
  (testing "project re-validates: the runtime arm fails the same way as the construction arm"
    (let [sfid (fresh-frame! :server)
          data (thrown-data #(rf.ssr.render-state/project sfid {:payload [:todos]}))]
      (is (= :rf.error/ssr-missing-payload-policy (:rf.error/id data)))
      (is (= :render-state (:opt data))))))

;; ---------------------------------------------------------------------------
;; The envelope, and liveness — fail-closed at both doors
;; ---------------------------------------------------------------------------

(deftest the-envelope-is-two-map-partitions-at-both-doors
  (testing "an escape-hatch projector returning something other than the envelope"
    (let [sfid (fresh-frame! :server)]
      (doseq [bad [nil [] {:rf/app-db "not a map"} {:rf/app-db {} :rf/runtime-db nil}
                   {:rf/app-db {} :extra {}}]]
        (let [data (thrown-data #(rf.ssr.render-state/project sfid {:render-state (fn [_] bad)}))]
          (is (= :rf.error/ssr-render-state-invalid (:rf.error/id data)) (pr-str bad))
          (is (= :envelope (:invalid data)) (pr-str bad))))))
  (testing "restore! refuses the same shapes and installs nothing"
    (let [cfid (fresh-frame! :server)]
      (rf.frame/replace-frame-state! cfid {rf.frame/app-partition-key {:kept true}})
      (doseq [bad [nil {:rf/app-db 1} {:rf/runtime-db [1 2]} {:rf/app-db {} :third {}}]]
        (let [data (thrown-data #(rf.ssr.render-state/restore! cfid bad))]
          (is (= :rf.error/ssr-render-state-invalid (:rf.error/id data)) (pr-str bad))
          (is (= :envelope (:invalid data)) (pr-str bad))))
      (is (= {:kept true} (rf.frame/frame-app-db-value cfid)) "nothing was installed")))
  (testing "a frame that is not live"
    (let [gone (fresh-frame! :server)]
      (rf/destroy-frame! gone)
      (is (= :rf.error/frame-destroyed
             (:rf.error/id (thrown-data #(rf.ssr.render-state/project gone {:render-state {:app-db [:a]}})))))
      (is (= :rf.error/frame-destroyed
             (:rf.error/id (thrown-data #(rf.ssr.render-state/restore! gone {:rf/app-db {}}))))))))
