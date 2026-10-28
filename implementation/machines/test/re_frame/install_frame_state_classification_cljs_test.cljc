(ns re-frame.install-frame-state-classification-cljs-test
  "A persisted machine installed with `[:rf/install-frame-state saved]` keeps
  its privacy classification. The save carries no classification: each
  restored actor is classified by the destination's registered machines as
  soon as its snapshot is installed, on client and server frames alike, with
  no classification step."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            #?(:cljs [cljs.reader])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame! [platform]
  (let [fid (keyword "rf.install-cls" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- round-trip
  "What a localStorage save and load does to `v`."
  [v]
  #?(:clj  (read-string (pr-str v))
     :cljs (cljs.reader/read-string (pr-str v))))

(def ^:private classified
  {:sensitive [[:data :token]]
   :initial   :idle
   :data      {:token "saved-secret" :label "public"}
   :states    {:idle {}}})

(defn- token-path [actor-id]
  [:rf.runtime/machines :snapshots actor-id :data :token])

(defn- label-path [actor-id]
  [:rf.runtime/machines :snapshots actor-id :data :label])

(defn- registry [frame-id]
  (:rf.runtime/elision (rf.machines.test-support/runtime-db frame-id)))

(defn- sensitive-claims
  "`frame-id`'s sensitive claims from machines and effects, the two this test
  makes. A `reg-classification` applies to every frame that resolves it, so a
  build that loads one elsewhere adds its paths to every frame here too."
  [frame-id]
  (into {}
        (filter (fn [[_ owners]] (some #(#{:machine :effect} (:source %)) owners)))
        (rf.elision/sensitive-declarations frame-id)))

(deftest an-installed-machine-keeps-its-classification
  (rf/reg-machine :cls/singleton classified)
  (rf/reg-machine :cls/child classified)
  (rf/reg-machine :cls/parent
    {:initial :idle
     :states  {:idle {:on {:open :open}}
               :open {:spawn {:machine-id :cls/child}}}})
  (rf/reg-event :cls/classify-password
    (fn [{:keys [db]} _]
      {:db (assoc-in db [:account :password] "hunter2") :sensitive [[:account :password]]}))
  (let [src      (fresh-frame! :client)
        _        (rf/dispatch-sync [:cls/singleton [:rf.machine/noop]] {:frame src})
        _        (rf/dispatch-sync [:cls/parent [:open]] {:frame src})
        machines (get-in (rf/frame-state-value src) [:rf.db/runtime :rf.runtime/machines])
        saved    (round-trip machines)
        child    (get-in saved [:spawned :cls/parent [:open]])
        claims   {(token-path :cls/singleton) #{{:source :machine}}
                  (token-path child)          #{{:source :machine}}}
        dst      (fresh-frame! :client)
        server   (fresh-frame! :server)
        install! (fn [fid]
                   (rf/dispatch-sync [:rf/install-frame-state
                                      {:rf.db/runtime {:rf.runtime/machines saved}}]
                                     {:frame fid}))]
    (is (= machines saved) "the live machines subtree round-trips as data")
    (rf/dispatch-sync [:cls/classify-password] {:frame dst})
    (install! dst)
    (install! server)
    (is (= [(assoc claims [:account :password] #{{:source :effect}}) claims]
           [(sensitive-claims dst) (sensitive-claims server)])
        "each restored token is claimed under its own actor, the public label is
         not, the destination's unrelated claim survives, and a server frame,
         which arms no timer, is classified too")
    (let [out (rf/project-egress (rf.machines.test-support/runtime-db dst) {:frame dst})]
      (is (= [:rf/redacted "public" :rf/redacted "public"]
             (mapv #(get-in out %) [(token-path :cls/singleton) (label-path :cls/singleton)
                                    (token-path child) (label-path child)])))
      (is (not (str/includes? (pr-str out) "saved-secret")) "no restored secret egresses"))
    (let [before (sensitive-claims dst)]
      (install! dst)
      (is (= before (sensitive-claims dst)) "installing the same payload again adds nothing")
      (is (not-any? #(= :machine (:source %)) (mapcat val (:sensitive-declarations (registry dst))))
          "no machine claim is ever written into the frame's elision registry"))))
