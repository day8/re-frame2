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

(deftest flow-hot-reload-invalidates-last-inputs
  (testing "re-registering a flow re-evaluates even when inputs are unchanged"
    (rf/reg-event-db :init   (fn [_ _] {:n 5}))
    (rf/reg-event-db :inc-n  (fn [db _] (update db :n inc)))
    ;; v1 flow: doubles :n at [:doubled].
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:doubled]})
    (rf/dispatch-sync [:init])
    (is (= 10 (:doubled (rf/get-frame-db :rf/default))))
    (rf/dispatch-sync [:inc-n])
    (is (= 12 (:doubled (rf/get-frame-db :rf/default))))
    ;; Re-register with a NEW formula. Inputs haven't changed yet — but the
    ;; flow body did, so the next drain should re-evaluate.
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 100 n))
                  :path   [:doubled]})
    ;; Trigger ANY event to drive the drain (no input change).
    (rf/dispatch-sync [:inc-n])
    (is (= 700 (:doubled (rf/get-frame-db :rf/default)))
        "after re-registration the flow body re-evaluates on the next drain")))

(deftest sub-hot-reload-invalidates-cache
  (testing "re-registering a :sub disposes cached reactions and emits a trace"
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/dispatch-sync [:seed])
    ;; v1 of :answer returns the value as-is.
    (rf/reg-sub :answer (fn [db _] (:n db)))
    (is (= 7 (rf/subscribe-value [:answer])))
    ;; Force the cache to retain the entry by holding a ref via subscribe.
    (let [_pinned (rf/subscribe [:answer])
          traces  (atom [])]
      (rf/register-trace-cb! ::hot-reload (fn [ev] (swap! traces conj ev)))
      ;; Re-register with a transformed body.
      (rf/reg-sub :answer (fn [db _] (* 10 (:n db))))
      (rf/remove-trace-cb! ::hot-reload)
      ;; After re-registration, the next subscribe-value sees the new fn.
      (is (= 70 (rf/subscribe-value [:answer]))
          "after re-registration the new sub body is used")
      (is (some (fn [ev]
                  (and (= :rf.registry/handler-replaced (:operation ev))
                       (= :registry (:op-type ev))
                       (= :sub (:kind (:tags ev)))
                       (= :answer (:id (:tags ev)))))
                @traces)
          "expected :rf.registry/handler-replaced trace"))))

(deftest subscriber-captures-frame
  (testing "subscriber closes over the current frame so closures don't need to thread it"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    ;; Seed each frame synchronously so the assertions are deterministic.
    (rf/dispatch-sync [:seed 7]  {:frame :left})
    (rf/dispatch-sync [:seed 99] {:frame :right})
    ;; Capture frame-bound subscribers via with-frame.
    (let [sl (rf/with-frame :left  (fn [] (rf/subscriber)))
          sr (rf/with-frame :right (fn [] (rf/subscriber)))]
      (is (= 7  @(sl [:n])) "left subscriber sees left's :n")
      (is (= 99 @(sr [:n])) "right subscriber sees right's :n")
      ;; And :rf/default is unaffected.
      (is (nil? (rf/subscribe-value :rf/default [:n]))))))

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

(deftest views-macros-load-cleanly
  (testing "the views-macros ns loads on the JVM and macros expand"
    (require 're-frame-2.views-macros)
    ;; with-frame expands into binding *current-frame*.
    (let [exp (macroexpand-1 `(re-frame-2.views-macros/with-frame :foo :body))]
      (is (some #(= 're-frame-2.core/*current-frame* %)
                (tree-seq coll? seq exp))
          "with-frame expansion references *current-frame*"))
    ;; reg-view defs a local var named after the keyword's name. Use
    ;; macroexpand (not -1) because the 2-arity form delegates to the
    ;; 3-arity form via a self-call.
    (let [exp (macroexpand `(re-frame-2.views-macros/reg-view
                              :my-widget (fn [] :body)))]
      (is (= 'def (first exp))
          "reg-view expansion starts with def")
      (is (= 'my-widget (second exp))
          "reg-view defs the var with the keyword's name"))
    ;; h leaves DOM tags alone but rewrites namespaced view refs.
    (let [exp (macroexpand-1 `(re-frame-2.views-macros/h [:my-ns/w {:k 1}]))]
      (is (some #(= 're-frame-2.core/get-view %)
                (tree-seq coll? seq exp))
          "h expansion references get-view for namespaced keywords"))))

(deftest render-tree-hash-is-stable
  (testing "render-tree-hash is deterministic and order-sensitive on vectors"
    (require 're-frame-2.ssr)
    (let [hash (resolve 're-frame-2.ssr/render-tree-hash)
          h1   (@hash [:div {:class "x"} [:p "hello"]])
          h2   (@hash [:div {:class "x"} [:p "hello"]])
          h3   (@hash [:div {:class "y"} [:p "hello"]])]
      (is (= h1 h2) "identical trees hash identically")
      (is (not= h1 h3) "different attrs change the hash")
      (is (re-matches #"[0-9a-f]{8}" h1)
          "hash is 8-char lowercase hex (FNV-1a 32-bit)"))))

(deftest render-to-string-emits-hash
  (testing ":emit-hash? opts adds data-rf-render-hash on the root element"
    (let [out (rf/render-to-string [:div [:p "hi"]] {:emit-hash? true})]
      (is (re-find #"<div data-rf-render-hash=\"[0-9a-f]{8}\">" out)
          "root element carries the data-rf-render-hash attribute"))))

(deftest ssr-end-to-end
  (testing "complete SSR flow: dispatch-sync → render-to-string → embedded hash"
    ;; Register a trivial articles app — an event seeds state, a sub
    ;; reads it, a view renders it.
    (rf/reg-event-db :articles/seed
      (fn [_ _] {:articles [{:id "a" :title "Article A" :body "Body A"}
                            {:id "b" :title "Article B" :body "Body B"}]}))
    (rf/reg-sub :articles (fn [db _] (:articles db)))
    (rf/reg-view :pages/articles
      (fn []
        (let [arts (rf/subscribe-value [:articles])]
          [:div.page
           [:h1 "Recent articles"]
           [:ul
            (for [{:keys [id title body]} arts]
              ^{:key id} [:li [:h3 title] [:p body]])]])))

    ;; Server flow: dispatch the seed event, render the root, capture hash.
    (rf/dispatch-sync [:articles/seed])
    (let [html (rf/render-to-string [:pages/articles] {:emit-hash? true})]
      (is (clojure.string/includes? html "Article A")
          "rendered HTML contains the title from app-db")
      (is (clojure.string/includes? html "Article B"))
      (is (re-find #"<div[^>]*data-rf-render-hash=\"[0-9a-f]{8}\""
                   html)
          "root <div> carries a data-rf-render-hash attribute")
      ;; The hash is reproducible: re-render the same tree, same hash.
      (let [h1 (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\""  html)
            html-2 (rf/render-to-string [:pages/articles] {:emit-hash? true})
            h2 (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\""  html-2)]
        (is (= (second h1) (second h2))
            "re-rendering the same view+state yields the same hash")))))

(deftest reg-view-jvm
  (testing "reg-view registers a view that render-to-string resolves"
    (rf/reg-view :greet
      (fn [name] [:p "hello " [:strong name]]))
    (is (= "<p>hello <strong>world</strong></p>"
           (rf/render-to-string [:greet "world"]))
        "render-to-string resolves [:greet args] via the :view registry")
    (is (fn? (rf/get-view :greet))
        "get-view returns the registered render fn")
    (is (nil? (rf/get-view :no-such-view)))))

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
