(ns re-frame.resources-http-completed-at-clock-cljs-test
  "rf2-2elcw3 — LIVE-path regression: the managed-HTTP transport's reply-ctx
  `:completed-at` must be WALL-CLOCK epoch ms (`interop/epoch-now-ms` =
  `js/Date.now()`), NOT the perf clock (`interop/now-ms` =
  `performance.now()`, origin-relative).

  WHY A NEW SUITE: every existing `:completed-at` test (e.g.
  `resources-managed-http-cljs-test`, `http-reply-lowering`) SCRIPTS the
  reply token's `:completed-at` / `:rf.cofx` `:rf/time-ms` with an
  explicit epoch value, bypassing the live `reply-ctx` clock read in
  `re-frame.http.transport`. So the wall-clock-vs-perf-clock bug (the
  transport boundary the router's fresh-token fix 427f260d3 / rf2-n1rh0f
  deliberately preserves as THE host-clock read) was untested on the live
  CLJS chain. JVM is benign (`now-ms` == `epoch-now-ms` there), so the JVM
  suites never caught it.

  This suite drives the REAL transport: it stubs ONLY `js/fetch` (the
  production `:rf.http/managed` fx is NOT overridden), dispatches a resource
  `ensure`, lets the genuine `reply-ctx` read the host clock, and asserts the
  resulting durable resource `:loaded-at` / `:stale-at` are wall-clock-
  meaningful — specifically that a JUST-loaded entry is NOT immediately stale
  when freshness is checked against `js/Date.now` (the exact clock the
  freshness readers in `resources/events.cljc`, `resources/subs.cljc`, and
  `resources/ssr.cljc` use). Pre-fix `:stale-at` ≈ `performance.now()` +
  window (~tens of thousands) is dwarfed by `js/Date.now()` (~1.78e12), so
  `entry-stale?` returns true the instant the load completes."
  (:require
   [cljs.test :refer-macros [deftest is testing async]]
   [re-frame.adapter.reagent :as reagent-adapter]
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   ;; production HTTP transport: registers the REAL `:rf.http/managed` fx
   ;; (we deliberately do NOT override it — the genuine `reply-ctx` clock
   ;; read is the unit under test).
   [re-frame.http.managed]
   ;; load-bearing side-effecting require: registers the :rf.resource/*
   ;; events + subs the ensure below drives.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.schemas]
   [re-frame.test-support :as test-support]))

(def ^:private stale-after-ms 60000)

(defn- json-200
  "A minimal Fetch `Response` stand-in that resolves a 200 JSON body via
  `.text()` (the text decode path the default `:auto` decode reads)."
  [text-val]
  #js {:ok          true
       :status      200
       :statusText  ""
       :headers     #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text        (fn [] (js/Promise.resolve text-val))
       :blob        (fn [] (js/Promise.resolve nil))
       :arrayBuffer (fn [] (js/Promise.resolve nil))
       :formData    (fn [] (js/Promise.resolve nil))})

(defn- article-spec []
  {:scope          :rf.scope/global
   :params-schema  [:map [:slug :string]]
   :stale-after-ms stale-after-ms
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})})

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(deftest live-completed-at-is-wall-clock-not-immediately-stale
  (testing "rf2-2elcw3 — a resource loaded through the LIVE managed-HTTP
            transport gets a wall-clock-epoch :loaded-at / :stale-at, so it
            is NOT stale immediately against `js/Date.now`. Pre-fix the
            reply-ctx :completed-at read `interop/now-ms` (performance.now()
            on CLJS), so :stale-at was ~perf-clock + window — far below
            `js/Date.now()` — and the entry read STALE the instant it loaded."
    (async done
      (rf/init! reagent-adapter/adapter)
      ;; EP-0002 (rf2-nn0jqa): `init!` does not synthesise a `:rf/default`
      ;; frame, and the managed-HTTP fxs require a carried frame stamp. Register
      ;; `:rf/default` explicitly; the dispatch below carries `{:frame
      ;; :rf/default}` so the sync dispatch AND the async reply continuation
      ;; both target it.
      (frame/ensure-default-frame!)
      (let [scoped-key (state/scoped-resource-key
                         :rf.scope/global :clk/article {:slug "w"})
            orig       (.-fetch js/globalThis)
            entry      #(get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                (state/entry-path scoped-key))]
        (rf/reg-resource :clk/article (article-spec) article-spec-request)
        (set! (.-fetch js/globalThis)
              (fn [_url _init] (js/Promise.resolve (json-200 "{\"title\":\"Welcome\"}"))))
        (rf/dispatch-sync [:rf.resource/ensure
                           {:resource :clk/article :scope :rf.scope/global
                            :params {:slug "w"} :owner [:lease :clk 1]}]
                          {:frame :rf/default})
        (is (= :loading (:status (entry)))
            "the ensure lowered to the real transport and is in flight")
        (-> (test-support/poll-until
              #(= :loaded (:status (entry)))
              {:timeout-ms 2000 :label "rf2-2elcw3 live load settles :loaded"})
            (.then
              (fn [_]
                (let [e         (entry)
                      now-epoch (js/Date.now)
                      loaded-at (:loaded-at e)
                      stale-at  (:stale-at e)]
                  (is (= {:title "Welcome"} (:data e))
                      "the live transport reply decoded + landed the data")
                  ;; The core regression assertion: freshness is checked
                  ;; against `js/Date.now` (the freshness readers' clock).
                  ;; A just-loaded entry MUST NOT be stale. A perf-clock
                  ;; :stale-at (≪ js/Date.now()) would wrongly read as stale.
                  (is (false? (state/entry-stale? e now-epoch))
                      "a just-loaded resource is NOT stale against js/Date.now")
                  ;; :loaded-at must be a wall-clock epoch value (within a
                  ;; few seconds of `js/Date.now()`), not a small perf-clock
                  ;; origin-relative number. The perf clock would be off by
                  ;; ~12 orders of magnitude (~1e4 vs ~1.78e12).
                  (is (number? loaded-at) ":loaded-at is present")
                  (is (< (js/Math.abs (- now-epoch loaded-at)) 10000)
                      ":loaded-at is a wall-clock epoch ms (within 10s of js/Date.now)")
                  ;; :stale-at = :loaded-at + window, and therefore lands in
                  ;; the FUTURE relative to js/Date.now() (the window has not
                  ;; elapsed). A perf-clock :stale-at would already be in the
                  ;; deep past.
                  (is (= (+ loaded-at stale-after-ms) stale-at)
                      ":stale-at is :loaded-at + :stale-after-ms")
                  (is (> stale-at now-epoch)
                      ":stale-at is in the future against js/Date.now (window not elapsed)"))
                (set! (.-fetch js/globalThis) orig)
                (done)))
            (.catch
              (fn [err]
                (set! (.-fetch js/globalThis) orig)
                (is false (str "rf2-2elcw3 — unexpected: " err))
                (done))))))))
