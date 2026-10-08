(ns re-frame2-pair-mcp.get-path-frame-test
  "Operating-frame resolution for the `get-path` tool.

  `get-path` resolves explicit override -> session pin -> sole app frame
  -> nil, and REFUSES at nil. An implicit-frame form would read
  `(get db nil)` and tell the agent the path does not exist when the truth
  is that it could not tell which frame was meant.

  A stub canning an `:ambiguous-frame` response would pass on that defect,
  because the tool relays whatever the runtime hands it. So
  `runtime-answer` plays a live runtime instead: it DERIVES the operating
  frame from the emitted form and refuses only when the form guards on a
  nil frame before reading; an unguarded form reads nil and reports the
  miss."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.get-path :as get-path]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

;; Two app frames, no pin: the session the resolver calls ambiguous.
(def ^:private two-frames [:rf/default :stories])

;; ---------------------------------------------------------------------------
;; The runtime simulator — answers are DERIVED from the emitted form.
;; ---------------------------------------------------------------------------

(defn- guards-ambiguity?
  "True when `form` binds the resolved id, branches to
  `ambiguous-frame-error` on nil, and only then reads app-db. The
  ordering is the assertion."
  [form]
  (let [resolve-at (str/index-of form "(let [fid (re-frame2-pair.runtime/current-frame")
        refuse-at  (str/index-of form
                                 (str "(if (nil? fid) "
                                      "(re-frame2-pair.runtime/ambiguous-frame-error :get-path)"))
        read-at    (str/index-of form "(re-frame2-pair.runtime/snapshot fid)")]
    (boolean (and resolve-at refuse-at read-at
                  (< resolve-at refuse-at read-at)))))

(defn- resolved-id
  "The id the browser-side resolver returns for `form`: override (read off
  the resolve call or, in an implicit-frame shape, the `snapshot` call) ->
  pin -> sole app frame -> nil."
  [form app-frames pin]
  (if-let [override (second (or (re-find #"current-frame\s+(:[^\s)]+)\)" form)
                                (re-find #"snapshot\s+(:[^\s)]+)\)" form)))]
    (keyword (subs override 1))
    (or pin
        (when (= 1 (count app-frames)) (first app-frames)))))

(defn- ambiguous-envelope
  "The runtime's `ambiguous-frame-error` envelope, reproduced because the
  preload is not on this test's classpath."
  [app-frames pin]
  {:ok?              false
   :reason           :ambiguous-frame
   :operation        :get-path
   :available-frames app-frames
   :selected-frame   pin
   :hint             (str "multiple app frames are registered and no frame is "
                          "selected, so get-path cannot pick a target. "
                          "Pass `frame` (one of " (pr-str app-frames) ") or pin one with "
                          "`select-frame!` / set-operating-frame, then retry.")})

(defn- runtime-answer
  "Answer `form` as a live runtime holding `app-frames` / `pin` / `db`
  would. The FRAME is whatever the form resolves."
  [form {:keys [app-frames pin db path paths]}]
  (let [fid (resolved-id form app-frames pin)]
    (if (and (nil? fid) (guards-ambiguity? form))
      (ambiguous-envelope app-frames pin)
      (let [frame-db (get db fid)
            missing  (js-obj)]
        (if paths
          {:ok?     true
           :results (into {}
                          (map (fn [p]
                                 (let [v (get-in frame-db p missing)]
                                   [p (if (identical? v missing)
                                        {:exists? false :value nil}
                                        {:exists? true :value v})])))
                          paths)
           :elided-count 0}
          (let [v (get-in frame-db path missing)]
            (if (identical? v missing)
              {:ok? false :reason :path-not-found :path path :deepest-valid-prefix []}
              {:ok? true :exists? true :path path :value v :elided-count 0})))))))

(defn- stub-runtime!
  "Install the simulator. The preload probe and raw-state signal are
  answered directly; the get-path form is captured into `captured*`."
  [captured* session]
  (let [respond
        (fn [form]
          (cond
            (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
            (js/Promise.resolve true)

            (and (string? form) (re-find #"configure-raw-state!" form))
            (js/Promise.resolve nil)

            :else
            (do (when (and captured* (string? form))
                  (reset! captured* form))
                (js/Promise.resolve
                  (if (string? form)
                    (runtime-answer form session)
                    nil)))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

;; ---------------------------------------------------------------------------
;; The witnesses — these fail on an implicit-frame tree.
;; ---------------------------------------------------------------------------

(deftest singular-ambiguous-frame-refuses-rather-than-reporting-path-not-found
  ;; The path exists in BOTH frames, so :path-not-found would be a falsehood.
  (async done
    (stub-runtime! nil {:app-frames two-frames
                        :pin        nil
                        :db         {:rf/default {:cart {:items [1 2]}}
                                     :stories    {:cart {:items []}}}
                        :path       [:cart :items]})
    (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:path "[:cart :items]"}))
        (.then (fn [r]
                 (is (= [true (ambiguous-envelope two-frames nil)] [(err? r) (read-edn r)]))
                 (done))))))

(deftest batch-ambiguous-frame-refuses-rather-than-reporting-every-path-absent
  ;; The batch shape would present an all-missing results map as a SUCCESS.
  (async done
    (stub-runtime! nil {:app-frames two-frames
                        :pin        nil
                        :db         {:rf/default {:cart {:items [1 2]} :user {:id 7}}
                                     :stories    {:cart {:items []} :user {:id 9}}}
                        :paths      [[:cart :items] [:user :id]]})
    (-> (get-path/get-path-tool (fresh-conn)
                                (tu/args->js {:paths "[[:cart :items] [:user :id]]"}))
        (.then (fn [r]
                 (is (= [true (ambiguous-envelope two-frames nil)] [(err? r) (read-edn r)]))
                 (done))))))

(deftest one-resolution-serves-both-the-read-and-the-walker
  ;; The read and the elision walker must describe the same frame — a
  ;; second resolve could pick a different one — and the raw-source wrapper
  ;; must ship a form whose delimiters balance.
  (async done
    (let [balanced? (fn [s]
                      (every? (fn [[o c]] (= (count (filter #{o} s)) (count (filter #{c} s))))
                              [[\( \)] [\[ \]] [\{ \}]]))]
      (-> (reduce
            (fn [p [args session]]
              (.then p (fn [_]
                         (let [captured (atom nil)]
                           (stub-runtime! captured (merge {:app-frames [:rf/default] :pin nil
                                                           :db {:rf/default {:a 1}}}
                                                          session))
                           (.then (get-path/get-path-tool (fresh-conn) (tu/args->js args))
                                  (fn [_]
                                    (let [form @captured]
                                      (is (balanced? form) (pr-str args))
                                      (is (re-find #":frame fid" form) (pr-str args))
                                      (is (= 1 (count (re-seq #"re-frame2-pair\.runtime/current-frame" form)))
                                          (pr-str args)))))))))
            (js/Promise.resolve nil)
            [[{:path "[:a]"} {:path [:a]}]
             [{:paths "[[:a]]"} {:paths [[:a]]}]])
          (.catch (fn [e] (is false (str "drive rejected: " e))))
          (.then (fn [_] (done)))))))

(deftest explicit-frame-still-reads-that-frame
  ;; An explicit override wins even with two frames registered.
  (async done
    (stub-runtime! nil {:app-frames two-frames
                        :pin        nil
                        :db         {:rf/default {:cart {:items [1 2]}}
                                     :stories    {:cart {:items [:only-here]}}}
                        :path       [:cart :items]})
    (-> (get-path/get-path-tool (fresh-conn)
                                (tu/args->js {:path "[:cart :items]" :frame ":stories"}))
        (.then (fn [r]
                 (is (= {:ok? true :exists? true :value [:only-here] :frame :stories}
                        (select-keys (read-edn r) [:ok? :exists? :value :frame])))
                 (done))))))
