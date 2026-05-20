(ns day8.re-frame2-causa.panels.managed-fx-helpers-cljs-test
  "Pure-data tests for the managed-fx wire-boundary helpers
  (rf2-uyp86, parent rf2-5aw5v).

  ## Coverage

    1. `classify-fx-id` — surface taxonomy.
    2. Per-surface adapter (http / websocket / machine-invoke /
       ssr-fx / flow) on success and failure cases.
    3. `cascade->managed-fx-records` — cascade walker; record-per-fx;
       paths-touched cross-fold.
    4. Status / phase / cancel-cause / failure derivation."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.managed-fx-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- fx-handled
  ([fx-id args] (fx-handled fx-id args {}))
  ([fx-id args extra-tags]
   {:operation :rf.fx/handled
    :op-type   :fx
    :id        (rand-int 1000000)
    :time      1000
    :tags      (merge {:fx-id fx-id
                       :fx-args args
                       :frame :rf/default
                       :dispatch-id 7}
                      extra-tags)}))

(defn- surface-ev
  ([op tags] (surface-ev op tags 1100))
  ([op tags t]
   {:operation op
    :op-type   (cond
                 (= op :rf.machine.lifecycle/spawned) :info
                 (#{:rf.flow/failed :rf.machine/invoke-failed :rf.ssr/render-failed
                    :rf.error/flow-eval-exception} op) :error
                 :else :info)
    :id        (rand-int 1000000)
    :time      t
    :tags      tags}))

;; ---- (1) classify-fx-id ------------------------------------------------

(deftest classify-fx-id-http
  (is (= :http (h/classify-fx-id :rf.http/managed)))
  (is (= :http (h/classify-fx-id :rf.http/managed-abort)))
  (is (= :http (h/classify-fx-id :rf.http/managed-canned-success))))

(deftest classify-fx-id-ws
  (is (= :websocket (h/classify-fx-id :rf.ws/connect)))
  (is (= :websocket (h/classify-fx-id :rf.ws/send))))

(deftest classify-fx-id-machine
  (is (= :machine-invoke (h/classify-fx-id :rf.machine/spawn)))
  (is (= :machine-invoke (h/classify-fx-id :rf.machine/destroy))))

(deftest classify-fx-id-ssr
  (is (= :ssr-fx (h/classify-fx-id :rf.server/set-status)))
  (is (= :ssr-fx (h/classify-fx-id :rf.server/set-header)))
  (is (= :ssr-fx (h/classify-fx-id :rf.server/redirect))))

(deftest classify-fx-id-flow
  (is (= :flow (h/classify-fx-id :rf.flow/registered)))
  (is (= :flow (h/classify-fx-id :rf.fx/reg-flow)))
  (is (= :flow (h/classify-fx-id :rf.fx/clear-flow))))

(deftest classify-fx-id-non-managed-is-nil
  (testing "non-managed-effects fxs classify as nil"
    (is (nil? (h/classify-fx-id :db)))
    (is (nil? (h/classify-fx-id :dispatch)))
    (is (nil? (h/classify-fx-id :user/my-fx)))
    (is (nil? (h/classify-fx-id :my/persist)))
    (is (nil? (h/classify-fx-id nil)))
    (is (nil? (h/classify-fx-id "not-a-keyword")))))

(deftest managed-fx-effect?-uses-classifier
  (is (true?  (h/managed-fx-effect? {:tags {:fx-id :rf.http/managed}})))
  (is (false? (h/managed-fx-effect? {:tags {:fx-id :user/x}}))))

;; ---- (2a) HTTP adapter on success --------------------------------------

(deftest http-adapter-success-record
  (testing "HTTP success path projects a clean OK record"
    (let [args   {:request {:method :get :url "/api/users/42"
                            :headers {:accept "application/json"}}
                  :decode  :json
                  :request-id :req-1
                  :on-success [:user/loaded]}
          fx-ev  (fx-handled :rf.http/managed args)
          rec    (h/http-adapter fx-ev [])]
      (is (= :http (:surface rec)))
      (is (= :rf.http/managed (:fx-id rec)))
      (is (= :ok (:status rec)))
      (is (= [:user/loaded] (:handler rec)))
      (is (= :req-1 (:correlation-id rec)))
      (is (nil? (:cancel-cause rec)))
      (is (= {:method :get :url "/api/users/42"
              :headers {:accept "application/json"}}
             (:req rec))))))

(deftest http-adapter-failure-record
  (testing "HTTP transport failure surfaces under :failure"
    (let [args   {:request {:method :get :url "/api/x"}
                  :request-id :req-2
                  :on-failure [:x/failed]}
          fx-ev  (fx-handled :rf.http/managed args)
          fail-ev (surface-ev :rf.http/transport
                              {:request-id :req-2 :message "ECONNREFUSED"})
          rec    (h/http-adapter fx-ev [fail-ev])]
      (is (= :http (:surface rec)))
      (is (= :error (:status rec)))
      (is (= :rf.http/transport (-> rec :failure :kind))))))

(deftest http-adapter-aborted-record
  (testing "HTTP abort-on-actor-destroy surfaces :cancel-cause"
    (let [args   {:request {:method :post :url "/api/finalize"}
                  :request-id :req-3}
          fx-ev  (fx-handled :rf.http/managed args)
          abort  (surface-ev :rf.http/aborted-on-actor-destroy
                             {:request-id :req-3})
          rec    (h/http-adapter fx-ev [abort])]
      (is (= :aborted (:phase rec)))
      (is (= :actor-destroyed (:cancel-cause rec))))))

(deftest http-adapter-synthesised-wire-timing
  (testing "When per-phase timing is absent the synthesised round-trip
            two-row waterfall lights up so the user still sees elapsed"
    (let [fx-ev (fx-handled :rf.http/managed
                            {:request {:method :get :url "/x"}}
                            {})
          end   (surface-ev :rf.http/handled
                            {:request-id :req-1 :status 200}
                            1250)
          rec   (h/http-adapter fx-ev [end])]
      (is (= 250 (:duration-ms rec)))
      (is (some? (:wire rec)))
      (is (= 2 (count (-> rec :wire :phases))))
      (is (true? (-> rec :wire :synthesised?))))))

;; ---- (2b) WebSocket adapter --------------------------------------------

(deftest websocket-adapter-basic-record
  (let [args   {:url "wss://chat.example.com" :socket-id :sock-1}
        fx-ev  (fx-handled :rf.ws/connect args)
        rec    (h/websocket-adapter fx-ev [])]
    (is (= :websocket (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= :sock-1 (:correlation-id rec)))
    (is (= args (:req rec)))))

(deftest websocket-adapter-failure
  (let [args   {:url "wss://chat.example.com" :socket-id :sock-2}
        fx-ev  (fx-handled :rf.ws/connect args)
        fail   (surface-ev :rf.ws/transport
                           {:socket-id :sock-2 :message "ECONNRESET"})
        rec    (h/websocket-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.ws/transport (-> rec :failure :kind)))))

;; ---- (2c) machine-invoke adapter ---------------------------------------

(deftest machine-invoke-adapter-spawn-record
  (let [args   {:machine-id :auth/main :spawn-id :inv-1
                :data {:user-id 42}}
        fx-ev  (fx-handled :rf.machine/spawn args)
        spawn  (surface-ev :rf.machine.lifecycle/spawned
                           {:spawn-id :inv-1 :machine-id :auth/main
                            :state :idle})
        rec    (h/machine-invoke-adapter fx-ev [spawn])]
    (is (= :machine-invoke (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= :inv-1 (:correlation-id rec)))
    (is (= {:spawn-id :inv-1 :machine-id :auth/main :state :idle}
           (:res rec)))))

(deftest machine-invoke-adapter-failure
  (let [fx-ev (fx-handled :rf.machine/spawn
                          {:machine-id :auth/main :spawn-id :inv-2})
        fail  (surface-ev :rf.machine/invoke-failed
                          {:spawn-id :inv-2 :reason :no-such-machine})
        rec   (h/machine-invoke-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.machine/invoke-failed (-> rec :failure :kind)))))

;; ---- (2d) SSR-fx adapter -----------------------------------------------

(deftest ssr-fx-adapter-set-status
  (let [args  {:status 302}
        fx-ev (fx-handled :rf.server/set-status args)
        rec   (h/ssr-fx-adapter fx-ev [])]
    (is (= :ssr-fx (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= args (:req rec)))
    (is (= args (:res rec)))))

(deftest ssr-fx-adapter-failure-render
  (let [fx-ev (fx-handled :rf.server/set-status {:status 500})
        fail  (surface-ev :rf.ssr/render-failed
                          {:request-id :ssr-1 :message "boom"})
        rec   (h/ssr-fx-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.ssr/render-failed (-> rec :failure :kind)))))

;; ---- (2e) flow adapter --------------------------------------------------

(deftest flow-adapter-registered-record
  (let [args  {:flow-id :flow/cart-subtotal :input [:cart] :output [:cart :subtotal]}
        fx-ev (fx-handled :rf.fx/reg-flow args)
        comp  (surface-ev :rf.flow/computed
                          {:flow-id :flow/cart-subtotal :output 42})
        rec   (h/flow-adapter fx-ev [comp])]
    (is (= :flow (:surface rec)))
    (is (= :ok (:status rec)))
    (is (= :flow/cart-subtotal (:correlation-id rec)))
    (is (= 42 (:res rec)))))

(deftest flow-adapter-eval-exception
  (let [fx-ev (fx-handled :rf.fx/reg-flow
                          {:flow-id :flow/x})
        fail  (surface-ev :rf.error/flow-eval-exception
                          {:flow-id :flow/x :message "div by zero"})
        rec   (h/flow-adapter fx-ev [fail])]
    (is (= :error (:status rec)))
    (is (= :rf.error/flow-eval-exception (-> rec :failure :kind)))))

;; ---- (3) cascade walker ------------------------------------------------

(deftest cascade-walker-extracts-managed-fx-only
  (testing "non-managed fxs (e.g. :db, :dispatch, user/x) are dropped"
    (let [cascade {:dispatch-id 7
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}})
                             (fx-handled :db {:foo 1})
                             (fx-handled :user/persist {:bar 2})]
                   :other []}
          records (h/cascade->managed-fx-records cascade)]
      (is (= 1 (count records)))
      (is (= :http (-> records first :surface)))
      (is (= :rf.http/managed (-> records first :fx-id))))))

(deftest cascade-walker-handles-multiple-surfaces
  (testing "a cascade with HTTP + SSR + flow fxs produces three records"
    (let [cascade {:dispatch-id 8
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}})
                             (fx-handled :rf.server/set-header
                                         {:name "X" :value "Y"})
                             (fx-handled :rf.fx/reg-flow
                                         {:flow-id :flow/x})]
                   :other []}
          records (h/cascade->managed-fx-records cascade)]
      (is (= 3 (count records)))
      (is (= #{:http :ssr-fx :flow}
             (set (map :surface records)))))))

(deftest cascade-walker-folds-paths-touched
  (testing "paths-by-dispatch-id supplies the F.4 slice-touched check"
    (let [cascade {:dispatch-id 9
                   :frame :rf/default
                   :effects [(fx-handled :rf.http/managed
                                         {:request {:method :get :url "/x"}}
                                         {:dispatch-id 9})]
                   :other []}
          rec     (first (h/cascade->managed-fx-records
                           cascade {9 [[:users 42] [:loading? :user-profile]]}))]
      (is (= [[:users 42] [:loading? :user-profile]]
             (:paths-touched rec))))))

(deftest cascade-walker-empty-for-non-managed-cascade
  (testing "a cascade with only :db / :dispatch fxs returns empty"
    (let [cascade {:dispatch-id 10
                   :frame :rf/default
                   :effects [(fx-handled :db {:foo 1})
                             (fx-handled :dispatch [:x])]
                   :other []}]
      (is (= [] (h/cascade->managed-fx-records cascade))))))

;; ---- (4) formatting helpers --------------------------------------------

(deftest format-fx-id-handles-keyword-and-nil
  (is (= ":rf.http/managed" (h/format-fx-id :rf.http/managed)))
  (is (= "—"                (h/format-fx-id nil))))

(deftest format-status-label-covers-taxonomy
  (doseq [s [:ok :error :in-flight :overridden :skipped :stub]]
    (is (some? (h/format-status-label s)))))

(deftest format-http-status-band-bands
  (is (= :green         (h/format-http-status-band 200)))
  (is (= :green         (h/format-http-status-band 204)))
  (is (= :yellow        (h/format-http-status-band 302)))
  (is (= :red           (h/format-http-status-band 404)))
  (is (= :red           (h/format-http-status-band 503)))
  (is (= :text-tertiary (h/format-http-status-band nil))))

(deftest format-duration-ms-ranges
  (is (= "—"     (h/format-duration-ms nil)))
  (is (= "250ms" (h/format-duration-ms 250)))
  (is (= "1500ms" (h/format-duration-ms 1500))))

(deftest surfaces-and-glyphs-match
  (testing "every canonical surface has a label, glyph, and adapter"
    (doseq [s h/surfaces]
      (is (some? (get h/surface->label s))   (str "label for " s))
      (is (some? (get h/surface->glyph s))   (str "glyph for " s))
      (is (some? (get h/surface->adapter s)) (str "adapter for " s)))))

;; ---- (5) bug-class coverage table --------------------------------------

(deftest bug-class-coverage-is-data
  (testing "every F.<n> the panel claims to address lists at least one
            record-field it surfaces"
    (doseq [[k fields] h/bug-class-coverage]
      (is (keyword? k))
      (is (vector? fields))
      (is (seq fields) (str "no fields listed for " k)))))
