(ns re-frame.parallel-raise-broadcast-test
  "A `:raise` inside a parallel region re-enters the PARENT macrostep: it is
  re-broadcast FIFO to every region against the evolving snapshot, a raise
  every region declines falls back to the root `:on`, and a runaway raise
  loop rolls the whole macrostep back (XState v5 / SCXML)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

(defn- log! [data label]
  (update data :log (fnil conj []) label))

(deftest sibling-guard-sees-raise-evolving-snapshot
  (let [spec {:type    :parallel
              :data    {:token nil}
              :guards  {:token-set? (fn [{:keys [data]}] (= :granted (:token data)))}
              :actions {:grant (fn [{:keys [data]}]
                                 {:data (-> data (assoc :token :granted) (log! :grant))
                                  :fx   [[:raise [:check]]]})
                        :admit (fn [{:keys [data]}] {:data (log! data :admit)})}
              :regions
              {:auth {:initial :anon
                      :states  {:anon {:on {:login {:target :authed :action :grant}}}
                                :authed {}}}
               ;; Passes only because :auth's raise already wrote :token.
               :gate {:initial :closed
                      :states  {:closed {:on {:check {:target :open :guard :token-set? :action :admit}}}
                                :open   {}}}}}
        {snap :snapshot} (rf.machines/machine-transition
                           spec {:state {:auth :anon :gate :closed} :data {:token nil}} [:login])]
    (is (= {:state {:auth :authed :gate :open} :data {:token :granted :log [:grant :admit]}}
           (select-keys snap [:state :data])))))

(deftest parallel-raise-drains-fifo
  ;; [:go] seeds the queue [p q]; handling p raises r, which lands behind q.
  ;; Depth-first would give :p :r :q.
  (let [step (fn [label & raise]
               (fn [{:keys [data]}] (cond-> {:data (log! data label)} raise (assoc :fx [[:raise (vec raise)]]))))
        spec {:type    :parallel
              :data    {}
              :actions {:one-go (step :one-go :p) :one-p (step :p :r) :one-r (step :r)
                        :two-go (step :two-go :q) :two-q (step :q)}
              :regions
              {:one {:initial :s
                     :states  {:s {:on {:go {:action :one-go} :p {:action :one-p} :r {:action :one-r}}}}}
               :two {:initial :s
                     :states  {:s {:on {:go {:action :two-go} :q {:action :two-q}}}}}}}
        {snap :snapshot} (rf.machines/machine-transition spec {:state {:one :s :two :s} :data {}} [:go])]
    (is (= [:one-go :two-go :p :q :r] (:log (:data snap))))))

(deftest parallel-raise-depth-bound-rolls-back-atomically
  (let [tick (fn [{:keys [data]}] {:data (log! data :tick) :fx [[:raise [:tick]]]})
        spec {:type              :parallel
              :raise-depth-limit 4
              :data              {}
              :actions           {:tick tick}
              :regions
              {:loop {:initial :run
                      :states  {:run {:on {:go {:action :tick} :tick {:action :tick}}}}}
               ;; A second region: the rollback discards the whole macrostep.
               :idle {:initial :z
                      :states  {:z {:on {:go {:action :tick}}}}}}}
        r (rf.machines/machine-transition spec {:state {:loop :run :idle :z} :data {}} [:go])]
    (is (= {:status :error :error {:kind :rf.error/machine-raise-depth-exceeded}}
           (update r :error select-keys [:kind])))))

(deftest raised-event-declined-by-regions-consults-root-on
  (let [spec {:type    :parallel
              :data    {}
              :actions {:raise-reset (fn [{:keys [data]}]
                                       {:data (log! data :raise-reset) :fx [[:raise [:reset]]]})
                        :root-reset  (fn [{:keys [data]}] {:data (log! data :root-reset)})}
              :on      {:reset {:target [:left :done] :action :root-reset}}
              :regions {:left {:initial :idle
                               :states  {:idle  {:on {:trigger {:target :fired :action :raise-reset}}}
                                         :fired {}
                                         :done  {}}}}}
        {snap :snapshot} (rf.machines/machine-transition spec {:state {:left :idle} :data {}} [:trigger])]
    (is (= {:state {:left :done} :data {:log [:raise-reset :root-reset]}}
           (select-keys snap [:state :data])))))
