(ns re-frame2-pair-mcp.size-override-default-launch-test
  "The `elision false` size override is honoured on a DEFAULT launch (no
  `--allow-sensitive-reads`).

  A `:large` declaration governs its whole subtree, so `elision false` is
  the only structured route to the raw value. The launch gate protects the
  SENSITIVE axis only: the size override is an
  `:rf.egress/include-large? true` overlay with the walker still running,
  so it can never reveal a declared-sensitive slot.

  For get-path the simulated runtime lifts the egress opts out of the
  emitted form and runs them through the framework's real
  `re-frame.projection/project-egress` against a frame carrying real
  `:large` and `:sensitive` declarations, so the assertions are about what
  comes BACK. The pair server never `:require`s the framework; this test
  does, because the walker is part of what it checks."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.projection :as rf.projection]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.list-subscriptions :as list-subs]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.read-sub :as read-sub]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

;; A doc row whose `:body` is declared `:large` and whose `:token` is
;; declared `:sensitive`, both index-free so they govern every row.

(def ^:private fid :rf2-ealv5/size-override)

(def ^:private body "BODY-7-LARGE-rf2-ealv5")
(def ^:private secret "SECRET-7-rf2-ealv5-do-not-leak")

(def ^:private app-db
  {:docs [{:id 7 :body body :token secret}]})

(defn- install-frame! []
  (when-not (rf.substrate.adapter/current-adapter)
    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))
  (when-not (rf.frame/frame fid)
    (rf.frame/upsert-frame! fid {:doc "size-override fixture frame"}))
  (rf.frame/swap-runtime-db! fid
    (fn [rt] (rf.elision/apply-classification-effects
               rt {:sensitive [[:docs :token]]
                   :large     [[:docs :body]]}))))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:before (fn []
             (install-frame!)
             (raw-state/set-allow-raw-state! false)
             (raw-state/reset-runtime-signal-cache!))
   :after  (fn []
             (set! nrepl/cljs-eval-value pristine-eval)
             (raw-state/set-allow-raw-state! false)
             (raw-state/reset-runtime-signal-cache!))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- stub-runtime!
  "Answer the preload probe and the raw-state configure call; hand every
  other form to `answer`."
  [answer]
  (let [respond (fn [form]
                  (cond
                    (re-find #"__re_frame2_pair_runtime" form) (js/Promise.resolve true)
                    (re-find #"configure-raw-state!" form)     (js/Promise.resolve nil)
                    :else (js/Promise.resolve (answer form))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(defn- egress-opts-of
  "The `{:rf.egress/profile ...}` opts map the get-path form hands the door,
  read back as data. Throws when the form names none, so a form that
  stopped naming a profile cannot read as an empty map."
  [form]
  (if-let [m (re-find #"\{:rf\.egress/profile[^{}]*\}" form)]
    (reader/read-string m)
    (throw (ex-info "get-path form names no :rf.egress/profile" {:form form}))))

(defn- fail!
  "A throw inside the chain is a RED, never a silent hang."
  [e]
  (is (nil? e) (str "the round trip threw: " (or (ex-message e) e))))

(deftest default-launch-elision-false-reads-raw-and-still-redacts-sensitive
  (async done
    (let [path [:docs 0]]
      (stub-runtime! (fn [form]
                       {:ok? true :exists? true :path path :elided-count 0
                        :value (rf.projection/project-egress
                                 (get-in app-db path)
                                 (merge {:path path :frame fid} (egress-opts-of form)))}))
      (-> (get-path/get-path-tool (fresh-conn)
                                  (tu/args->js {:path "[:docs 0]" :elision false :frame (str fid)}))
          (.then
            (fn [r]
              (is (= {:elision false :value {:id 7 :body body :token :rf/redacted}}
                     (select-keys (tu/extract-edn r) [:elision :value]))
                  "the :large body comes back raw, the :sensitive token in the same read redacts, and the echo reports the honoured choice")
              (is (not (str/includes? (tu/extract-text r) secret))
                  "the secret appears nowhere in the reply")))
          (.catch fail!)
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; The other four tools that take `elision`, at the form level: without
;; this, re-gating read-sub, list-subscriptions, record or watch-until would
;; go unnoticed.
;; ---------------------------------------------------------------------------

(def ^:private canned-any
  "One reply every one of the four tools accepts as a success."
  {:ok? true :held? true :sample {0 :done} :t 1
   :recording-id "rec-rf2-ealv5" :query-v [:x] :frame :rf/default
   :value 1 :subs []})

(defn- emitted-forms!
  "Run `call` against a stub runtime; resolves to every non-prelude form
  the tool sent."
  [call]
  (let [forms (atom [])]
    (stub-runtime! (fn [form] (swap! forms conj form) canned-any))
    (-> (call (fresh-conn))
        (.then (fn [_] @forms)))))

(def ^:private elision-tools
  [["read-sub"           read-sub/read-sub-tool
    {:sub "[:x]"}]
   ["list-subscriptions" list-subs/list-subscriptions-tool
    {:include-values true}]
   ["record"             record/record-tool
    {:signals "[{:app-db [:docs]}]"}]
   ["watch-until"        watch-until/watch-until-tool
    {:signals "[{:app-db [:docs]}]" :pred #js {:signal 0 :equals 1}}]])

(defn- overlay? [form] (str/includes? form ":rf.egress/include-large? true"))
(defn- names-tool-profile? [form]
  (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool"))

(deftest default-launch-every-elision-tool-honours-elision-false
  (async done
    (-> (reduce
          (fn [p [tool-name tool-fn args]]
            (-> p
                (.then (fn [_]
                         (emitted-forms! #(tool-fn % (tu/args->js (assoc args :elision true))))))
                (.then (fn [forms]
                         ;; Control from the target: the egress form IS captured,
                         ;; and without the override it carries no overlay.
                         (is (some names-tool-profile? forms)
                             (str tool-name ": control: the egress form was captured"))
                         (is (not-any? overlay? forms)
                             (str tool-name ": control: elision true emits no overlay"))
                         (emitted-forms! #(tool-fn % (tu/args->js (assoc args :elision false))))))
                (.then (fn [forms]
                         (is (some overlay? forms)
                             (str tool-name ": elision false overlays include-large? on a default launch"))
                         (is (not-any? #(str/includes? % ":rf.egress/local-raw") forms)
                             (str tool-name ": the size override never names local-raw"))))))
          (js/Promise.resolve nil)
          elision-tools)
        (.catch fail!)
        (.then (fn [_] (done))))))
