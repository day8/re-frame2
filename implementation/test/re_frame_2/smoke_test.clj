(ns re-frame-2.smoke-test
  "Smoke tests — exercise the foundation end-to-end on the JVM via the
  plain-atom adapter. These are the bare-minimum 'does the dispatch
  pipeline actually work?' tests. Conformance fixtures are a separate
  TODO."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame-2.core :as rf]
            [re-frame-2.frame :as frame]
            [re-frame-2.registrar :as registrar]
            [re-frame-2.flows :as flows]
            [re-frame-2.machines :as machines]
            [re-frame-2.routing :as routing]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (rf/init!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- registry round-trip --------------------------------------------------

(deftest registrar-round-trip
  (testing "registering and looking up a handler"
    (rf/reg-event-db :counter/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [meta (rf/handler-meta :event :counter/inc)]
      (is (some? meta))
      (is (fn? (:handler-fn meta)))
      (is (= :db (:event/kind meta))))))

;; ---- end-to-end dispatch --------------------------------------------------

(deftest dispatch-sync-event-db
  (testing "dispatch-sync runs an event-db handler and commits :db"
    (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/init])
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:n (rf/get-frame-db :rf/default))))))

(deftest dispatch-sync-event-fx
  (testing "dispatch-sync runs an event-fx handler with :db and :fx"
    (let [fired (atom 0)]
      (rf/reg-fx :test/incr-counter
                 (fn [_ _] (swap! fired inc)))
      (rf/reg-event-fx :do-it
        (fn [_ _]
          {:db {:flag :set}
           :fx [[:test/incr-counter :go]]}))
      (rf/dispatch-sync [:do-it])
      (is (= {:flag :set} (rf/get-frame-db :rf/default)))
      (is (= 1 @fired)))))

;; ---- standard interceptors ------------------------------------------------

(deftest path-interceptor
  (testing "(path :a :b) focuses a handler on the [:a :b] sub-slice"
    (rf/reg-event-db :init   (fn [_ _] {:user {:profile {:name "alice" :role :admin}}}))
    (rf/reg-event-db :rename
      [(rf/path :user :profile :name)]
      ;; Handler sees ONLY the slice value as :db.
      (fn [name [_ new-name]]
        (str new-name "-renamed-from-" name)))
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:rename "bob"])
    ;; Result spliced back at [:user :profile :name]; :role is preserved.
    (is (= {:user {:profile {:name "bob-renamed-from-alice"
                             :role :admin}}}
           (rf/get-frame-db :rf/default)))))

(deftest unwrap-interceptor
  (testing "[unwrap] gives the handler the payload map directly"
    (let [seen (atom nil)]
      (rf/reg-event-fx :consume
        [rf/unwrap]
        (fn [_cofx payload]
          (reset! seen payload)
          {}))
      (rf/dispatch-sync [:consume {:k "v" :n 7}])
      (is (= {:k "v" :n 7} @seen)
          "handler should receive the payload map as :event"))))

(deftest sub-cache-ref-counting
  (testing "subscribe / unsubscribe pair tracks ref-count and disposes on zero"
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [cache (:sub-cache (frame/frame :rf/default))]
      ;; Two subscriptions to the same query share a single cache slot.
      (let [r1 (rf/subscribe [:n])
            r2 (rf/subscribe [:n])]
        (is (identical? r1 r2) "cache hit returns the same reaction")
        (is (contains? @cache [:n]))
        (is (= 2 (get-in @cache [[:n] :ref-count]))))
      ;; First unsubscribe drops to 1, slot still present.
      (rf/unsubscribe [:n])
      (is (contains? @cache [:n]))
      (is (= 1 (get-in @cache [[:n] :ref-count])))
      ;; Second unsubscribe drops to 0, slot evicted.
      (rf/unsubscribe [:n])
      (is (not (contains? @cache [:n]))
          "cache slot is removed when ref-count reaches zero"))))

(deftest dispatch-sync-in-handler-errors
  (testing "calling dispatch-sync from inside a handler raises a structured error"
    (let [traces (atom [])]
      (rf/register-trace-cb! ::dsih (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-db :outer (fn [db _] (assoc db :ran? true)))
      (rf/reg-event-fx :nested
        (fn [_ _]
          ;; Calling dispatch-sync from inside a handler should NOT silently
          ;; interleave; it must raise :rf.error/dispatch-sync-in-handler.
          (rf/dispatch-sync [:outer])
          {}))
      (rf/dispatch-sync [:nested])
      (rf/remove-trace-cb! ::dsih)
      (is (some (fn [ev]
                  (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                       (= :error (:op-type ev))
                       (= :no-recovery (:recovery ev))))
                @traces)
          "expected :rf.error/dispatch-sync-in-handler trace event"))))

;; ---- subscription chain ---------------------------------------------------

(deftest layer-1-and-layer-2-subs
  (testing "layer-1 and layer-2 subs return computed values"
    (rf/reg-event-db :seed (fn [_ _] {:items [1 2 3 4 5]}))
    (rf/reg-sub :items     (fn [db _] (:items db)))
    (rf/reg-sub :item-count :<- [:items] (fn [items _] (count items)))
    (rf/dispatch-sync [:seed])
    (is (= [1 2 3 4 5] (rf/subscribe-value :rf/default [:items])))
    (is (= 5           (rf/subscribe-value :rf/default [:item-count])))))

;; ---- machine ---------------------------------------------------------------

(deftest pure-machine-transition
  (testing "machine-transition is pure"
    (let [m {:id     :traffic-light
             :initial :red
             :data    {}
             :states
             {:red    {:on {:tick {:target :green}}}
              :green  {:on {:tick {:target :yellow}}}
              :yellow {:on {:tick {:target :red}}}}}]
      (let [[s1 _] (machines/machine-transition m {:state :red :data {}} [:tick])]
        (is (= :green (:state s1))))
      (let [[s2 _] (machines/machine-transition m {:state :green :data {}} [:tick])]
        (is (= :yellow (:state s2)))))))

(deftest machine-always-microstep
  (testing ":always fires once after the resolving event under a true guard"
    (let [m {:id     :auth
             :initial :checking
             :data    {:authed? true}
             :guards  {:authed? (fn [snap _] (:authed? (:data snap)))}
             :states
             {:checking {:always [{:guard :authed? :target :authed}]}
              :authed   {}
              :idle     {}}}
          ;; Even with a no-op event (no match in :on), :always is checked
          ;; and the guard passes — transition to :authed.
          [s _] (machines/machine-transition m {:state :checking :data {:authed? true}} [:noop])]
      (is (= :authed (:state s))))))

(deftest machine-raise-pre-commit
  (testing ":raise routes locally pre-commit (does not go to runtime fifo)"
    (let [calls (atom [])
          m {:id      :counter
             :initial :idle
             :data    {:n 0}
             :actions {:start (fn [_ _]
                                {:fx [[:raise [:bump]] [:raise [:bump]]]})
                       :bump  (fn [snap _]
                                {:data {:n (inc (:n (:data snap)))}})}
             :states
             {:idle {:on {:start {:target :busy :action :start}
                          :bump  {:action :bump}}}
              :busy {:on {:bump {:action :bump}}}}}
          [s fx] (machines/machine-transition m {:state :idle :data {:n 0}} [:start])]
      ;; Two raised :bump events should have been processed pre-commit;
      ;; final data :n should be 2.
      (is (= 2 (:n (:data s))))
      ;; No :raise should escape to the outer fx.
      (is (not (some #{:raise} (map first fx)))))))

;; ---- flows ----------------------------------------------------------------

(deftest flow-rectangle-area
  (testing "a flow recomputes :area when :width or :height changes"
    (rf/reg-event-db :init (fn [_ _] {:width 0 :height 0}))
    (rf/reg-event-db :w! (fn [db [_ w]] (assoc db :width w)))
    (rf/reg-event-db :h! (fn [db [_ h]] (assoc db :height h)))
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:width] [:height]]
                  :output (fn [w h] (* w h))
                  :path   [:area]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:w! 3])
    (rf/dispatch-sync [:h! 4])
    ;; The drain calls run-flows! after :db commit per Spec 013.
    (is (= 12 (:area (rf/get-frame-db :rf/default))))))

;; ---- routing --------------------------------------------------------------

(deftest match-and-route-url
  (testing "match-url and route-url round-trip"
    (rf/reg-route :user/show {:path "/users/:id"})
    (let [m (rf/match-url "/users/42")]
      (is (= :user/show (:route-id m)))
      (is (= "42" (:id (:params m)))))
    (is (= "/users/42" (rf/route-url :user/show {:id 42})))))

;; ---- SSR emitter ----------------------------------------------------------

(deftest ssr-render-to-string-basics
  (testing "basic hiccup → HTML"
    (require 're-frame-2.ssr)
    (let [r2s @(resolve 're-frame-2.ssr/render-to-string)]
      (is (= "<div>hi</div>"
             (r2s [:div "hi"] {})))
      (is (= "<div class=\"a\">hi</div>"
             (r2s [:div {:class "a"} "hi"] {})))
      ;; class on tag-name + class in attrs merges
      (is (= "<div id=\"main\" class=\"col bold\">x</div>"
             (r2s [:div#main.col {:class "bold"} "x"] {})))
      ;; void elements per HTML5 — no closing tag, no self-close slash.
      (is (= "<br>" (r2s [:br] {})))
      (is (= "<input type=\"text\">"
             (r2s [:input {:type "text"}] {})))
      ;; boolean attribute
      (is (= "<input disabled>"
             (r2s [:input {:disabled true}] {})))
      ;; HTML-escape text
      (is (clojure.string/includes? (r2s [:p "a < b & c > d"] {}) "&lt;"))
      ;; doctype
      (is (clojure.string/starts-with? (r2s [:html [:body]] {:doctype? true})
                                       "<!DOCTYPE html>")))))
