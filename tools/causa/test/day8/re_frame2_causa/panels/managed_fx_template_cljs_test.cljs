(ns day8.re-frame2-causa.panels.managed-fx-template-cljs-test
  "Smoke render tests for the managed-fx wire-boundary diff template
  (rf2-uyp86, parent rf2-5aw5v).

  Pure hiccup; the test asserts the structural shape of the panel
  per-surface without booting a substrate. The data-testid attributes
  the template carries make the assertions deterministic without DOM
  introspection."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [day8.re-frame2-causa.panels.managed-fx-template :as template]
            [day8.re-frame2-causa.panels.managed-fx-helpers :as h]))

;; ---- fixture record builder --------------------------------------------

(defn- record
  [{:keys [surface fx-id status http-status wire handler paths]}]
  {:surface         surface
   :fx-id           fx-id
   :req             {:method :get :url "/x"}
   :wire            wire
   :res             {:ok true}
   :handler         handler
   :status          status
   :phase           :completed
   :correlation-id  :corr-1
   :cancel-cause    nil
   :http-status     http-status
   :duration-ms     250
   :failure         nil
   :paths-touched   (or paths [])
   :origin-event-id 99
   :dispatch-id     7
   :frame           :rf/default
   :stubbed?        false})

;; ---- recursive hiccup walker ------------------------------------------

(defn- testids
  "Collect every :data-testid in a hiccup tree. Used to assert the
  template's section structure exists without walking the DOM."
  [node]
  (cond
    (and (vector? node) (map? (second node)))
    (let [attrs (second node)
          tid   (:data-testid attrs)
          kids  (drop 2 node)]
      (concat (when tid [tid])
              (mapcat testids kids)))

    (vector? node)
    (mapcat testids (drop 1 node))

    (seq? node)
    (mapcat testids node)

    :else []))

;; ---- per-surface smoke render ------------------------------------------

(deftest record-panel-http-smoke
  (let [r   (record {:surface :http :fx-id :rf.http/managed
                     :status :ok :http-status 200
                     :handler [:user/loaded]
                     :paths [[:users 42]]})
        out (template/record-panel r)
        ids (set (testids out))]
    (is (contains? ids "rf-causa-managed-fx-record-http-99"))
    (is (contains? ids "rf-causa-managed-fx-header-http"))
    (is (contains? ids "rf-causa-managed-fx-surface-http"))
    (is (contains? ids "rf-causa-managed-fx-status-ok"))
    (is (contains? ids "rf-causa-managed-fx-section-request"))
    (is (contains? ids "rf-causa-managed-fx-section-wire"))
    (is (contains? ids "rf-causa-managed-fx-section-response"))
    (is (contains? ids "rf-causa-managed-fx-section-handler"))
    (is (contains? ids "rf-causa-managed-fx-section-app-db"))))

(deftest record-panel-machine-invoke-smoke
  (let [r   (record {:surface :machine-invoke :fx-id :rf.machine/spawn
                     :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-causa-managed-fx-header-machine-invoke"))
    (is (contains? ids "rf-causa-managed-fx-surface-machine-invoke"))))

(deftest record-panel-ssr-fx-smoke
  (let [r   (record {:surface :ssr-fx :fx-id :rf.server/set-status
                     :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-causa-managed-fx-header-ssr-fx"))
    (is (contains? ids "rf-causa-managed-fx-surface-ssr-fx"))))

(deftest record-panel-flow-smoke
  (let [r   (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-causa-managed-fx-header-flow"))
    (is (contains? ids "rf-causa-managed-fx-surface-flow"))))

(deftest record-panel-websocket-smoke
  (let [r   (record {:surface :websocket :fx-id :rf.ws/connect :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-causa-managed-fx-header-websocket"))
    (is (contains? ids "rf-causa-managed-fx-surface-websocket"))))

;; ---- error-status renders error styling -------------------------------

(deftest record-panel-error-surfaces-status-error-testid
  (let [r   (assoc (record {:surface :http :fx-id :rf.http/managed
                            :status :error :http-status 500})
                    :failure {:kind :rf.http/http-5xx
                              :tags {:status 500 :body "oops"}})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-causa-managed-fx-status-error"))))

;; ---- cross-link wiring -------------------------------------------------
;;
;; HANDLER DISPATCHED is collapsed by default; the focus button lives
;; inside the section body. The tests assert the structural surface
;; (section header is always rendered; body shows up once the section
;; is expanded). The button visibility itself rides on the panel's
;; default-collapse state and is exercised by the gallery
;; (panel-gallery isn't in scope for rf2-uyp86).

(deftest handler-section-header-present-when-handler-present
  (let [r   (record {:surface :http :fx-id :rf.http/managed
                     :status :ok :http-status 200
                     :handler [:user/loaded {:id 1}]})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-causa-managed-fx-section-handler"))
    (is (contains? ids "rf-causa-managed-fx-section-handler-header"))))

;; ---- records-list ------------------------------------------------------

(deftest records-list-renders-one-panel-per-record
  (let [recs [(record {:surface :http :fx-id :rf.http/managed :status :ok :http-status 200})
              (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
        out  (template/records-list recs)
        ids  (set (testids out))]
    (is (contains? ids "rf-causa-managed-fx-list"))
    (is (contains? ids "rf-causa-managed-fx-record-http-99"))
    (is (contains? ids "rf-causa-managed-fx-record-flow-99"))))

(deftest records-list-nil-for-empty-records
  (is (nil? (template/records-list []))))

;; ---- F.4 highlight: OK status + empty paths-touched -------------------

(deftest app-db-section-flags-empty-slice-on-ok-status
  (testing "Per spec/019 §2.4 F.4 — when status is :ok but
            paths-touched is empty, the panel renders the F.4 warning
            heuristic instead of the bare '(no changes)' caption"
    (let [r   (record {:surface :http :fx-id :rf.http/managed
                       :status :ok :http-status 200
                       :paths []})
          out (template/record-panel r)
          ;; Walk the hiccup tree for the F.4 warning text
          txt (atom [])
          walk (fn walk [node]
                 (cond
                   (string? node) (swap! txt conj node)
                   (vector? node) (doseq [c (drop 1 node)] (walk c))
                   (seq? node)    (doseq [c node] (walk c))))]
      (walk out)
      (let [combined (apply str @txt)]
        (is (str/includes? combined "F.4"))))))
