(ns day8.re-frame2-machines-viz.share-cljs-test
  "Tests for the share-URL encode/decode pipeline (rf2-8d7w1 · v1.0).

  Coverage:
  - `encode-share-url` → `decode-share-url` round-trip preserves the
    ChartState (machine-id, frame-id, definition, snapshot state).
  - Snapshot `:state` configuration arms (rf2-9l8h8): flat keyword,
    compound vector-path, and parallel region-map all round-trip; a
    malformed `:state` is rejected at ENCODE (encode/decode symmetric —
    no undecodable URL), and the closed-map rule still rejects extra
    `:snapshot` keys for every arm.
  - Canonicalisation: the same ChartState encodes byte-for-byte
    identically regardless of input map/set ordering (Principles
    §Reproducible from the registry alone).
  - Privacy: runtime `:data` on `:snapshot` and `:source-coords` are
    dropped; definition metadata is stripped.
  - Versioned envelope: `:rf.machines-viz.share/v` rides outermost;
    a newer version is rejected with `:unknown-version`.
  - Every documented `decode-failed` `:reason`.
  - Host override + `chart-state->props` projection.
  - The `:host` fragment rule (rf2-xld5m): a fragment-bearing host is
    refused, and — the negative control — every fragment-free host still
    encodes byte-identically, including the relative / `file://` forms
    the encoder deliberately does not police."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk]
            [cognitect.transit :as transit]
            [day8.re-frame2-machines-viz.grammar :as grammar]
            [day8.re-frame2-machines-viz.share :as share]))

;; ---------------------------------------------------------------------------
;; The viewer host these tests encode against. `encode-share-url` has NO
;; default host (rf2-8m344) — the one it used to carry named a repository
;; that does not exist — so every producer of a share-URL names its own
;; viewer, tests included.

(def ^:private test-host "https://x/viewer.html")

(defn- encode
  "`share/encode-share-url` against `test-host`."
  [chart-state]
  (share/encode-share-url chart-state {:host test-host}))

;; ---------------------------------------------------------------------------
;; Test helper — craft a raw share-URL fragment from an arbitrary
;; envelope (so we can build a future-version / missing-envelope payload
;; the public encoder would never emit). Mirrors the encoder's
;; transit-json → base64url step.

(defn- envelope->url
  [envelope]
  (let [transit-str (transit/write (transit/writer :json) envelope)
        b64 (-> (js/btoa (js/unescape (js/encodeURIComponent transit-str)))
                (str/replace "+" "-")
                (str/replace "/" "_")
                (str/replace "=" ""))]
    (str test-host "#machine=" b64)))

(defn- frozen-now
  "Run `f` with `js/Date.now` pinned to a constant so the envelope's
  non-reproducible `:created` stamp doesn't perturb byte-identity
  assertions. (`:created` is the ONE allowed non-reproducible bit per
  Principles §Reproducible from the registry alone.)"
  [f]
  (let [orig (.-now js/Date)]
    (try
      (set! (.-now js/Date) (fn [] 1736000000000))
      (f)
      (finally (set! (.-now js/Date) orig)))))

;; ---------------------------------------------------------------------------
;; Fixtures

(def idle-loading-success
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def chart-state
  {:machine-id :auth/login-flow
   :frame-id   :app/main
   :definition idle-loading-success
   :snapshot   {:state :loading}})

(def parallel-state
  {:machine-id :editor/flow
   :frame-id   :app/main
   :definition {:type :parallel
                :regions {:data {:initial :clean :states {:clean {} :dirty {}}}
                          :form {:initial :idle  :states {:idle {} :busy {}}}}}})

(def compound-definition
  "A compound (hierarchical) machine — its snapshot `:state` is a vector
  path from the root to the active leaf."
  {:initial :authenticated
   :states  {:authenticated
             {:initial :cart
              :states  {:cart    {:initial :browsing
                                  :states  {:browsing {} :checkout {}}}
                        :account {}}}
             :anonymous {}}})

;; ---------------------------------------------------------------------------
;; Round-trip

(deftest round-trip-preserves-chart-state
  (testing "encode → decode recovers the ChartState exactly"
    (let [url (encode chart-state)
          env (share/decode-share-url url)
          back (:rf.machines-viz.share/chart env)]
      (is (= :auth/login-flow (:machine-id back)))
      (is (= :app/main (:frame-id back)))
      (is (= idle-loading-success (:definition back)))
      (is (= {:state :loading} (:snapshot back)))
      (is (= "2" (:rf.machines-viz.share/v env)))
      (is (number? (:rf.machines-viz.share/created env))))))

(deftest round-trip-parallel
  (testing "parallel definitions round-trip"
    (let [back (:rf.machines-viz.share/chart
                 (share/decode-share-url (encode parallel-state)))]
      (is (= (:definition parallel-state) (:definition back)))
      (is (= :editor/flow (:machine-id back))))))

(deftest round-trip-no-snapshot
  (testing "a ChartState with no :snapshot round-trips without one"
    (let [cs   (dissoc chart-state :snapshot)
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (encode cs)))]
      (is (not (contains? back :snapshot)))
      (is (= idle-loading-success (:definition back))))))

;; ---------------------------------------------------------------------------
;; Snapshot :state CONFIGURATION — the three Spec 005 §Snapshot-shape arms
;; (rf2-9l8h8). The bug: encode accepted compound/parallel snapshots but a
;; keyword-only decoder rejected them → an undecodable URL. All three arms
;; must now round-trip cleanly and stay encode/decode symmetric.

(deftest round-trip-flat-snapshot
  (testing "a FLAT keyword :state round-trips"
    (let [cs   (assoc chart-state :snapshot {:state :loading})
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (encode cs)))]
      (is (= {:state :loading} (:snapshot back)))
      (is (keyword? (get-in back [:snapshot :state]))))))

(deftest round-trip-compound-snapshot
  (testing "a COMPOUND vector-path :state round-trips (was rejected pre-9l8h8)"
    (let [cs   {:machine-id :shop/store
                :frame-id   :app/main
                :definition compound-definition
                :snapshot   {:state [:authenticated :cart :browsing]}}
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (encode cs)))]
      (is (= {:state [:authenticated :cart :browsing]} (:snapshot back)))
      (is (vector? (get-in back [:snapshot :state]))))))

(deftest round-trip-parallel-snapshot
  (testing "a PARALLEL region-map :state round-trips (was rejected pre-9l8h8)"
    (let [cs   (assoc parallel-state :snapshot {:state {:data :dirty :form :busy}})
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (encode cs)))]
      (is (= {:state {:data :dirty :form :busy}} (:snapshot back)))
      (is (map? (get-in back [:snapshot :state])))))
  (testing "a PARALLEL region-map whose region value is itself a compound path"
    (let [cs   (assoc parallel-state
                      :snapshot {:state {:data :dirty :form [:edit :touched]}})
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (encode cs)))]
      (is (= {:state {:data :dirty :form [:edit :touched]}} (:snapshot back))))))

(deftest malformed-state-rejected-at-encode
  (testing "a :state that is none of the three arms is rejected at ENCODE — symmetric, no undecodable URL"
    (doseq [bad-state [42
                       "loading"
                       []                              ;; empty vector path
                       [:auth "authing"]               ;; non-keyword in path
                       {}                              ;; empty region-map
                       {:data "loading"}               ;; non-keyword/path region value
                       {"data" :loading}]]             ;; non-keyword region name
      (let [cs (assoc chart-state :snapshot {:state bad-state})
            d  (try (encode cs)
                    (catch :default e (ex-data e)))]
        (is (= :invalid-chart-state (:reason d))
            (str "encode must reject malformed :state " (pr-str bad-state)))
        (is (= :rf.machines-viz.share/encode-failed (:rf.error/id d)))))))

(deftest encode-decode-symmetric-for-all-arms
  (testing "every arm the encoder accepts, the decoder also accepts (no undecodable URL)"
    (doseq [state [:loading
                   [:authenticated :cart :browsing]
                   {:data :dirty :form :busy}
                   {:data :dirty :form [:edit :touched]}]]
      (let [cs  (assoc chart-state
                       :definition compound-definition
                       :snapshot {:state state})
            url (encode cs)
            ;; decode-share-url-safe never throws — an undecodable URL
            ;; (the pre-9l8h8 bug) would surface as {:error ...}.
            r   (share/decode-share-url-safe url)]
        (is (some? (:ok r)) (str "arm round-trips cleanly: " (pr-str state)))
        (is (nil? (:error r)))
        (is (= state (get-in (:ok r) [:rf.machines-viz.share/chart :snapshot :state])))))))

(deftest url-shape
  (testing "encoded URL carries the #machine= fragment + uses base64url alphabet"
    (let [url (encode chart-state)
          frag (subs url (inc (str/index-of url "#")))]
      (is (str/starts-with? url test-host))
      (is (str/starts-with? frag "machine="))
      (let [payload (subs frag (count "machine="))]
        ;; base64url: no +, /, or = padding
        (is (not (str/includes? payload "+")))
        (is (not (str/includes? payload "/")))
        (is (not (str/includes? payload "=")))))))

(deftest host-selects-the-viewer-base
  (testing "{:host ...} names the viewer the URL points at"
    (let [url (share/encode-share-url chart-state {:host "https://acme.example.com/v.html"})]
      (is (str/starts-with? url "https://acme.example.com/v.html#machine=")))))

(deftest no-host-is-refused
  (testing "rf2-8m344 — there is no default host, and a missing one is refused
            rather than filled in with a URL that 404s"
    (doseq [opts [nil {} {:host nil} {:host ""} {:host "   "}]]
      (let [d (try (share/encode-share-url chart-state opts)
                   (catch :default e (ex-data e)))]
        (is (= :rf.machines-viz.share/encode-failed (:rf.error/id d))
            (str "refused for opts " (pr-str opts)))
        (is (= :no-host (:reason d))
            (str "reason is :no-host for opts " (pr-str opts)))
        (is (string? (:message d)) "carries a human message"))))
  (testing "a blank host never yields a URL"
    (is (not (string? (try (share/encode-share-url chart-state {:host "  "})
                           (catch :default _ nil)))))))

;; ---------------------------------------------------------------------------
;; rf2-xld5m — the `:host` fragment rule, and the deliberate LIMIT of it.
;;
;; Measured before anything was written. Every non-URL `:host` the encoder
;; accepts produces a string that BEGINS with what the caller typed —
;; `{:host "banana"}` → `"banana#machine=…"` — so a wrong host is visible at a
;; glance and dead at first paste. One malformed host is different: a `:host`
;; that already carries a fragment yields
;; `…/viewer.html#docs#machine=<400 chars>`, which reads as correct and is
;; unreadable, because `extract-fragment` stops at the FIRST `#`. The sender
;; sees success; the recipient gets `:malformed-fragment`.
;;
;; So the refusal is exactly one check wide, and the two tests below pin BOTH
;; sides of it: fragment-bearing hosts are refused, and every other host —
;; absolute, relative, ported, queried, `file://` — still passes through
;; VERBATIM with a byte-identical payload.

(def ^:private fragment-bearing-hosts
  "Hosts whose `#` makes the machine payload unreachable. Each one encoded
  successfully before rf2-xld5m and produced a link the viewer refused."
  {:trailing-fragment   "https://acme.example.com/viewer.html#docs"
   :bare-hash           "https://acme.example.com/viewer.html#"
   :already-a-share-url "https://acme.example.com/viewer.html#machine=AAAA"
   :fragment-with-query "https://acme.example.com/viewer.html?theme=dark#docs"
   :bare-fragment       "#machine=x"})

(deftest host-carrying-a-fragment-is-refused
  (testing "rf2-xld5m — a :host that already has a URL fragment is refused at
            encode: the payload IS the fragment, and the viewer stops at the
            first '#'"
    (doseq [[label host] fragment-bearing-hosts]
      (let [r (try {:url (share/encode-share-url chart-state {:host host})}
                   (catch :default e {:data (ex-data e)}))
            d (:data r)]
        (is (nil? (:url r))
            (str label ": no URL is produced for a fragment-bearing host"))
        (is (= :host-carries-fragment (:reason d))
            (str label ": refused with the :host-carries-fragment reason"))
        (is (= :rf.machines-viz.share/encode-failed (:rf.error/id d)))
        (is (= :pass-a-viewer-page-url-with-no-fragment (:recovery d)))
        ;; The diagnostic NAMES what was wrong — the fragment, and where.
        (is (str/includes? (:message d) "fragment")
            (str label ": the message names the fragment"))
        (is (= (str/index-of host "#") (:fragment-index d))
            (str label ": ex-data locates the offending '#'"))
        ;; …without echoing the caller's URL (which can carry a query token).
        (is (not (str/includes? (pr-str d) host))
            (str label ": the raw host is not echoed into ex-data"))))))

(def ^:private fragment-free-hosts
  "The NEGATIVE CONTROL roster. Every legitimate viewer base a caller might
  host the page at — plus the relative and non-http forms the encoder
  deliberately does NOT police (a `file://` URL is literally what the README's
  build recipe leaves on disk, and `/viewer.html` is a legitimate same-origin
  base). None of them carries a fragment, so none of them may be refused."
  ["https://acme.example.com/viewer.html"
   "https://acme.example.com"
   "https://acme.example.com/deep/path/to/viewer.html"
   "https://acme.example.com/viewer.html?theme=dark"
   "https://acme.example.com/viewer.html?a=1&b=2"
   "https://acme.example.com:8443/viewer.html"
   "https://acme.example.com/a%20path/viewer.html"
   "http://localhost:8080/viewer.html"
   "file:///C:/out/machines-viz-viewer/viewer.html"
   "//acme.example.com/viewer.html"
   "/viewer.html"
   "viewer.html"
   "  https://acme.example.com/viewer.html  "])   ;; trimmed, as before

(deftest fragment-free-hosts-are-untouched
  (testing "rf2-xld5m NEGATIVE CONTROL — every host without a '#' still
            encodes EXACTLY as before: the host is passed through verbatim
            (only trimmed), the payload is byte-identical, and the URL decodes"
    (frozen-now
      (fn []
        ;; The fragment does not depend on the host, so with `:created` frozen
        ;; it must be identical for every base — which is what "unchanged"
        ;; means here: no normalising, no rewriting, no re-encoding.
        (let [canonical-fragment (subs (encode chart-state) (count test-host))]
          (is (str/starts-with? canonical-fragment "#machine="))
          (doseq [host fragment-free-hosts]
            (let [url (share/encode-share-url chart-state {:host host})]
              (is (= (str (str/trim host) canonical-fragment) url)
                  (str host ": host passed through verbatim + identical payload"))
              (let [{:keys [ok error]} (share/decode-share-url-safe url)]
                (is (nil? error) (str host ": decodes without error"))
                (is (= :auth/login-flow
                       (get-in ok [:rf.machines-viz.share/chart :machine-id]))
                    (str host ": the ChartState round-trips"))))))))))

(deftest non-url-host-is-accepted-and-fails-visibly
  (testing "rf2-xld5m — a host that is not a URL is NOT refused, and that is
            the ruling rather than an omission: the failure is LOUD. The
            returned string begins with the word the caller typed, so the
            defect is on screen before anyone shares it. A scheme allowlist
            would refuse the working forms above to catch this."
    (doseq [host ["banana" "not a url" "javascript:alert(1)"]]
      (let [url (share/encode-share-url chart-state {:host host})]
        (is (str/starts-with? url (str host "#machine="))
            (str host ": the caller's own string is what they get back"))
        ;; The PAYLOAD is well-formed — only the base is nonsense, which is
        ;; precisely why this class needs no guard: nothing is hidden.
        (is (some? (:ok (share/decode-share-url-safe url)))
            (str host ": the machine payload itself is intact"))))))

;; ---------------------------------------------------------------------------
;; Canonicalisation / reproducibility

(deftest reproducible-encoding
  (testing "differently-ordered but equal ChartStates encode identically"
    (let [a {:machine-id :m/x :frame-id :app/main
             :definition {:initial :a
                          :states {:a {:on {:go :b}} :b {:on {:back :a}}}}}
          ;; Same value, keys constructed in a different insertion order.
          b {:definition {:states {:b {:on {:back :a}} :a {:on {:go :b}}}
                          :initial :a}
             :frame-id :app/main :machine-id :m/x}]
      (is (= a b) "fixtures are value-equal")
      (frozen-now
        (fn []
          (is (= (encode a) (encode b))
              "and therefore encode byte-for-byte identically (created frozen)"))))))

(deftest reproducible-with-sets
  (testing "set-valued slots (e.g. :tags) canonicalise to a stable order"
    (let [a {:machine-id :m/x :frame-id :app/main
             :definition {:initial :s1
                          :states {:s1 {:tags #{:b :a :c}}}}}
          b {:machine-id :m/x :frame-id :app/main
             :definition {:initial :s1
                          :states {:s1 {:tags #{:c :a :b}}}}}]
      (frozen-now
        (fn []
          (is (= (encode a) (encode b)))))
      (let [back (:rf.machines-viz.share/chart
                   (share/decode-share-url (encode a)))]
        (is (= #{:a :b :c} (get-in back [:definition :states :s1 :tags])))))))

;; ---------------------------------------------------------------------------
;; Privacy — no session data in shares

(deftest snapshot-data-is-dropped
  (testing "runtime :data riding on :snapshot is structurally excluded"
    (let [leaky (assoc chart-state :snapshot {:state :loading
                                              :data  {:token "secret-abc"
                                                      :form  {:password "hunter2"}}})
          back  (:rf.machines-viz.share/chart
                  (share/decode-share-url (encode leaky)))]
      (is (= {:state :loading} (:snapshot back)))
      (is (not (contains? (:snapshot back) :data)))
      (is (not (str/includes? (encode leaky) "hunter2"))
          "the secret never reaches the encoded bytes"))))

(deftest source-coords-are-dropped
  (testing ":source-coords passed by the caller never reach the payload"
    (let [leaky (assoc chart-state :source-coords {:file "/Users/mike/secret/x.cljs"})
          back  (:rf.machines-viz.share/chart
                  (share/decode-share-url (encode leaky)))]
      (is (not (contains? back :source-coords)))
      (is (not (str/includes? (encode leaky) "secret"))))))

(deftest definition-metadata-is-stripped
  (testing "macro-captured source-coord meta on the definition does not propagate"
    (let [defn-with-meta (with-meta idle-loading-success
                                    {:rf/source-coord {:file "/Users/mike/proj/m.cljs"
                                                       :line 42}})
          cs   (assoc chart-state :definition defn-with-meta)
          url  (encode cs)
          back (:rf.machines-viz.share/chart (share/decode-share-url url))]
      (is (nil? (meta (:definition back))) "no meta survives")
      (is (not (str/includes? url "proj")) "the file path never reaches the bytes"))))

;; ---------------------------------------------------------------------------
;; rf2-m285a — macro-stamped DATA (not metadata) sanitisation. A reg-machine
;; macro co-locates `:source-coords` / `:source-code` + executable `:fn`
;; values as ordinary DATA inside `:states` / `:guards` / `:actions` /
;; `:on-spawn-actions` (Spec 005 §Source-coord stamping). `strip-meta` (which
;; touches Clojure METADATA only) does NOT reach them, so the share encoder
;; leaked local-filesystem paths + source snippets and could fail to encode a
;; live `:fn`. `sanitise-definition` strips them structurally.

(def macro-stamped-like-definition
  "Mimics the reg-machine macro's dev-arm output: per-node `:source-coords`
  inside `:states`, `:guards` / `:actions` entries carrying
  `{:fn .. :source-coords .. :source-code ..}`, and inline live fns on
  `:guard` / `:action`."
  {:initial :idle
   :guards  {:form-valid? {:fn            (fn [_] true)
                           :source-coords {:ns 'app.login :file "/Users/mike/proj/login.cljs"
                                           :line 47 :column 13}
                           :source-code   "(fn [{data :data}] (valid? data))"}}
   :actions {:commit      {:fn            (fn [_] {})
                          :source-coords {:ns 'app.login :file "/Users/mike/proj/login.cljs"
                                          :line 52 :column 13}
                          :source-code   "(fn [{data :data}] {:fx [[:http ...]]})"}}
   :states  {:idle {:on            {:submit {:target :done
                                             :guard  :form-valid?
                                             :action (fn [_] {})   ;; inline live fn
                                             :source-coords {:ns 'app.login
                                                             :file "/Users/mike/proj/login.cljs"
                                                             :line 80 :column 23}}}
                    :source-coords {:ns 'app.login :file "/Users/mike/proj/login.cljs"
                                    :line 78 :column 11}}
             :done {:final?        true
                    :source-coords {:ns 'app.login :file "/Users/mike/proj/login.cljs"
                                    :line 84 :column 11}}}})

(deftest macro-stamped-source-coords-do-not-leak
  (testing "rf2-m285a — nested :source-coords / :source-code on :states /
            :guards / :actions are stripped before Transit (no local path leak)"
    (let [cs   (assoc chart-state :definition macro-stamped-like-definition)
          url  (encode cs)
          back (:rf.machines-viz.share/chart (share/decode-share-url url))
          dfn  (:definition back)]
      ;; The encoded BYTES carry no local-filesystem path / source snippet.
      (is (not (str/includes? url "Users")) "no local-filesystem path in the URL bytes")
      (is (not (str/includes? url "proj"))  "no repo dir in the URL bytes")
      ;; The decoded payload carries no debug/source fields anywhere.
      (is (nil? (get-in dfn [:states :idle :source-coords])))
      (is (nil? (get-in dfn [:states :idle :on :submit :source-coords])))
      (is (nil? (get-in dfn [:states :done :source-coords])))
      (is (nil? (get-in dfn [:guards :form-valid? :source-coords])))
      (is (nil? (get-in dfn [:guards :form-valid? :source-code])))
      (is (nil? (get-in dfn [:actions :commit :source-code])))
      ;; Topology references survive: the transition target + guard NAME.
      (is (= :done       (get-in dfn [:states :idle :on :submit :target])))
      (is (= :form-valid? (get-in dfn [:states :idle :on :submit :guard])))
      (is (true? (get-in dfn [:states :done :final?])))
      ;; The guard / action ids survive (as keys) — names-only, no body.
      (is (contains? (:guards dfn) :form-valid?))
      (is (contains? (:actions dfn) :commit)))))

(deftest macro-stamped-executable-fns-do-not-block-encoding
  (testing "rf2-m285a — a live :fn on a guards/actions entry AND an inline-fn
            action encode successfully (instead of crashing Transit) — the
            executable body is dropped / labelled, not serialised"
    (let [cs  (assoc chart-state :definition macro-stamped-like-definition)
          url (encode cs)
          dfn (:definition
                (:rf.machines-viz.share/chart (share/decode-share-url url)))]
      (is (string? url) "encoding succeeds")
      ;; The :guards / :actions entries no longer carry an executable :fn.
      (is (nil? (get-in dfn [:guards :form-valid? :fn])))
      (is (nil? (get-in dfn [:actions :commit :fn])))
      ;; The inline-fn :action slot was replaced by an opaque names-only label
      ;; (not a live fn, not a source body).
      (let [a (get-in dfn [:states :idle :on :submit :action])]
        (is (not (fn? a)) "the inline fn was not serialised as an executable")
        (is (keyword? a)  "it became an opaque label keyword")))))

;; ---------------------------------------------------------------------------
;; rf2-skhlw2.1 — consumer-attachment `:rf.cofx/requires` (EP-0017) is SAFE
;; topology metadata (a vector of coeffect-id keywords), so a share URL must
;; PRESERVE it: `sanitise-definition` drops the entry's `:fn` / `:source-*`
;; but keeps the requires vector. The decoded definition re-derives the
;; chart's `needs <id>` chips on the receiving side.

(def cofx-requires-definition
  "A named guard / action / entry / exit each declaring `:rf.cofx/requires`
  alongside a live `:fn` (the macro-stamped entry-map shape)."
  {:initial :idle
   :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [_] true)}}
   :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]
                             :fn (fn [_] nil)}
             :stamp-started  {:rf.cofx/requires [:rf/time-ms]
                             :fn (fn [_] nil)}}
   :states  {:idle {:entry :stamp-started
                    :on    {:go {:target :busy
                                 :guard  :within-window?
                                 :action :schedule-retry}}}
             :busy {}}})

(deftest cofx-requires-survive-share-round-trip
  (testing "rf2-skhlw2.1 — a share URL PRESERVES safe :rf.cofx/requires
            metadata while still dropping the executable :fn"
    (let [cs   (assoc chart-state :definition cofx-requires-definition)
          url  (encode cs)
          dfn  (:definition
                 (:rf.machines-viz.share/chart (share/decode-share-url url)))]
      ;; the requires vectors survive intact (safe topology metadata)
      (is (= [:rf/time-ms] (get-in dfn [:guards :within-window? :rf.cofx/requires])))
      (is (= [:payment/retry-jitter-ms]
             (get-in dfn [:actions :schedule-retry :rf.cofx/requires])))
      (is (= [:rf/time-ms] (get-in dfn [:actions :stamp-started :rf.cofx/requires])))
      ;; but the executable :fn is still stripped (privacy / Transit contract)
      (is (nil? (get-in dfn [:guards :within-window? :fn])))
      (is (nil? (get-in dfn [:actions :schedule-retry :fn]))))))

;; ---------------------------------------------------------------------------
;; rf2-07gg7h — `:fn` as a TOPOLOGY key (state id / event id / region id) is
;; valid and MUST survive sanitisation. The pre-fix sanitiser dropped EVERY
;; map entry whose key was `:fn`, silently removing such a state / transition /
;; region. Only the EXECUTABLE `:fn` slot (a co-located `{:fn <fn> …}` value
;; that is a function) is stripped — gated on `(fn? v)`.

(def fn-id-definition
  "A valid topology that uses `:fn` as a STATE id, an EVENT id, and a
  transition TARGET — none of which is an executable function slot."
  {:initial :fn
   :states  {:fn   {:on {:fn   :done        ;; :fn used as both state-id + event-id
                         :next :other}}
             :other {:on {:go :done}}
             :done  {:final? true}}})

(deftest fn-as-state-id-survives-sanitisation
  (testing "rf2-07gg7h — a state whose id is `:fn` is topology and is
            preserved through share encode/decode (not dropped as if it were
            an executable function slot)"
    (let [cs   (assoc chart-state :definition fn-id-definition)
          url  (encode cs)
          dfn  (:definition
                 (:rf.machines-viz.share/chart (share/decode-share-url url)))]
      (is (string? url) "encoding succeeds")
      (is (= :fn (:initial dfn)) "the `:fn` initial state id is preserved")
      (is (contains? (:states dfn) :fn)
          "the `:fn` STATE id survives — the topology is not silently dropped")
      ;; The `:fn` EVENT id (and its target) survive too.
      (is (= :done (get-in dfn [:states :fn :on :fn]))
          "the `:fn` EVENT id + its target are preserved")
      (is (= :other (get-in dfn [:states :fn :on :next]))
          "sibling transitions on the `:fn` state are intact")
      (is (contains? (:states dfn) :other))
      (is (true? (get-in dfn [:states :done :final?]))))))

(deftest fn-region-id-survives-sanitisation
  (testing "rf2-07gg7h — a parallel REGION whose id is `:fn` is topology and
            is preserved (region-id `:fn` is not an executable slot)"
    (let [defn {:type    :parallel
                :regions {:fn {:initial :one :states {:one {:on {:go :two}} :two {}}}
                          :b  {:initial :p   :states {:p {} :q {}}}}}
          cs   (assoc chart-state :definition defn)
          url  (encode cs)
          dfn  (:definition
                 (:rf.machines-viz.share/chart (share/decode-share-url url)))]
      (is (string? url) "encoding succeeds")
      (is (contains? (:regions dfn) :fn)
          "the `:fn` REGION id survives sanitisation")
      (is (= :one (get-in dfn [:regions :fn :initial]))
          "the `:fn` region's topology is intact")
      (is (contains? (:regions dfn) :b)))))

(deftest executable-fn-slot-still-dropped-alongside-fn-topology
  (testing "rf2-07gg7h — preserving topology keyed `:fn` does NOT regress the
            privacy guarantee: a co-located EXECUTABLE `:fn` slot (a fn value)
            is still stripped, even when a `:fn` STATE id is also present"
    (let [defn {:initial :fn
                :guards  {:ready? {:fn (fn [_] true)}}   ;; executable slot
                :states  {:fn {:on {:go {:target :done :guard :ready?}}}
                          :done {:final? true}}}
          cs   (assoc chart-state :definition defn)
          url  (encode cs)
          dfn  (:definition
                 (:rf.machines-viz.share/chart (share/decode-share-url url)))]
      (is (string? url) "encoding succeeds (the live fn did not crash Transit)")
      ;; Topology `:fn` (the state id) survives …
      (is (contains? (:states dfn) :fn) "the `:fn` STATE id is preserved")
      ;; … while the EXECUTABLE `:fn` slot is stripped.
      (is (nil? (get-in dfn [:guards :ready? :fn]))
          "the executable :fn slot is still dropped")
      (is (contains? (:guards dfn) :ready?)
          "the guard NAME (its key) survives, names-only"))))

;; ---------------------------------------------------------------------------
;; Versioning + failure modes

(deftest unknown-version-rejected
  (testing "a payload tagged with a newer version throws :unknown-version"
    (let [future-url (envelope->url
                       {:rf.machines-viz.share/v       "3"
                        :rf.machines-viz.share/chart   chart-state
                        :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url future-url)
                 (catch :default e (ex-data e)))]
      (is (= :unknown-version (:reason d)))
      ;; rf2-m46qv — the INTEGER the version comparison used, not the raw
      ;; `:v` off the payload (which is forged input of any size).
      (is (= 3 (:payload-version d))))))

(deftest frame-id-is-optional
  (testing "a ChartState with no :frame-id encodes + round-trips (v2 / EP-0023)"
    (let [cs   (dissoc chart-state :frame-id)
          url  (encode cs)
          back (:rf.machines-viz.share/chart (share/decode-share-url url))]
      (is (not (contains? back :frame-id))
          "no fabricated :frame-id rides the payload when none was supplied")
      (is (= :auth/login-flow (:machine-id back)))
      (is (= idle-loading-success (:definition back)))
      (is (= {:state :loading} (:snapshot back))))))

(deftest frame-id-when-present-must-be-keyword
  (testing "a non-keyword :frame-id is rejected at encode with :invalid-chart-state"
    (let [d (try (encode (assoc chart-state :frame-id "not-a-keyword"))
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))))))

(deftest decoded-frame-id-absent-still-decodes
  (testing "a forged v2 payload omitting :frame-id decodes cleanly (optional)"
    (let [url  (envelope->url
                 {:rf.machines-viz.share/v       "2"
                  :rf.machines-viz.share/chart   (dissoc chart-state :frame-id)
                  :rf.machines-viz.share/created 0})
          back (:rf.machines-viz.share/chart (share/decode-share-url url))]
      (is (not (contains? back :frame-id)))
      (is (= :auth/login-flow (:machine-id back))))))

(deftest missing-envelope-rejected
  (testing "a payload missing the envelope keys throws :missing-envelope"
    (let [bad-url (envelope->url {:not :an-envelope})
          d (try (share/decode-share-url bad-url)
                 (catch :default e (ex-data e)))]
      (is (= :missing-envelope (:reason d))))))

(deftest decoded-snapshot-with-extra-keys-rejected
  (testing "a hand-edited URL smuggling :data onto :snapshot is rejected on decode"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :snapshot {:state :loading
                                                                       :data {:token "leak"}})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "the closed :snapshot schema rejects extra keys at decode time"))))

(deftest decoded-snapshot-extra-key-alongside-compound-state-rejected
  (testing "a closed :snapshot is still closed for compound/parallel arms — extra keys rejected on decode"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :definition compound-definition
                                                            :snapshot {:state [:authenticated :cart :browsing]
                                                                       :data  {:token "leak"}})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "widening :state to a configuration does NOT loosen the closed-map rule"))))

(deftest decoded-malformed-state-rejected
  (testing "a hand-edited URL whose :state is none of the three arms is rejected on decode (symmetric)"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :snapshot {:state {:data "not-a-keyword-or-path"}})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))))))

;; ---------------------------------------------------------------------------
;; rf2-3fc89f.18 — malformed machine DEFINITIONS fail closed at the
;; share/viewer trust boundary. The boundary previously carried a PRIVATE
;; definition-shape predicate weaker than the canonical Machines-Viz grammar
;; gate (`grammar/valid-definition?` — the SAME gate the AI / Mermaid / SCXML
;; emitters + the chart projector share): it accepted any TRUTHY flat
;; `:initial` (even a STRING) and every non-empty parallel `:regions` map
;; WITHOUT validating the region bodies. A forged-but-valid-Transit share URL
;; therefore decoded `:ok` and was handed to `MachineChart` even though the
;; same definition is rejected everywhere else. The fix deletes the private
;; copy and routes the definition slot through the canonical grammar gate
;; (after the SAME `desugar-grammar` policy the projectors use), so decode
;; FAILS CLOSED on a malformed definition.

(def timeout-definition
  "An authored `:timeout` / `:on-timeout` definition (EP-0029 A4). The share
  stores the AUTHORED form; the boundary desugars to VALIDATE without
  rewriting the stored payload — so the decoded definition is byte-identical
  to the authored one."
  {:initial :idle
   :states  {:idle    {:timeout 5000 :on-timeout :expired :on {:go :done}}
             :expired {:final? true}
             :done    {:final? true}}})

(def choice-definition
  "An authored `:type :choice` transient state (EP-0029 A5). Round-trips in
  authored form; the boundary desugars only to validate."
  {:initial :evaluating
   :states  {:evaluating {:type   :choice
                          :choice [{:target :a :guard :ready?} {:target :b}]}
             :a {:final? true}
             :b {:final? true}}})

(def valid-definitions
  "Well-formed definitions the canonical grammar gate accepts (flat, compound,
  parallel, and the two authored-sugar shapes)."
  {:flat     idle-loading-success
   :compound compound-definition
   :parallel (:definition parallel-state)
   :timeout  timeout-definition
   :choice   choice-definition})

(def malformed-definitions
  "Machine definitions the canonical grammar gate REJECTS but the old private
  share predicate ACCEPTED. Each is a well-formed Transit value (it survives
  decode up to the schema check) but a malformed machine SHAPE — a non-keyword
  / missing flat `:initial`, empty `:states`, or a malformed parallel region
  body (missing keyword `:initial` / empty `:states`)."
  {:flat-string-initial     {:initial "idle" :states {:idle {}}}
   :flat-missing-initial     {:states {:idle {}}}
   :flat-empty-states        {:initial :idle :states {}}
   :parallel-empty-region    {:type :parallel :regions {:main {:states {}}}}
   :parallel-string-initial  {:type :parallel :regions {:main {:initial "x" :states {:x {}}}}}
   :parallel-no-initial      {:type :parallel :regions {:main {:states {:x {}}}}}})

(defn- forge-definition-url
  "Forge a share-URL whose `…/chart` carries `definition` verbatim (bypassing
  the encoder's own validation) so decode-side fail-closed behaviour can be
  exercised directly."
  [definition]
  (envelope->url
    {:rf.machines-viz.share/v       "2"
     :rf.machines-viz.share/chart   {:machine-id :demo :definition definition}
     :rf.machines-viz.share/created 0}))

(deftest decoded-malformed-definition-rejected-throwing
  (testing "rf2-3fc89f.18 — a forged share-URL carrying a malformed machine
            definition is rejected at decode with :invalid-chart-state (the
            throwing API), fail-closed"
    (doseq [[label definition] malformed-definitions]
      (let [d (try (share/decode-share-url (forge-definition-url definition))
                   (catch :default e (ex-data e)))]
        (is (= :invalid-chart-state (:reason d))
            (str "malformed definition " label " must fail closed at decode"))
        (is (= :rf.machines-viz.share/decode-failed (:rf.error/id d)))))))

(deftest decoded-malformed-definition-rejected-safe
  (testing "rf2-3fc89f.18 — the SAFE decode API returns
            {:error {:reason :invalid-chart-state}} — never :ok — for a
            malformed definition (the viewer's ingestion API)"
    (doseq [[label definition] malformed-definitions]
      (let [{:keys [ok error]} (share/decode-share-url-safe (forge-definition-url definition))]
        (is (nil? ok) (str "malformed definition " label " must NOT decode :ok"))
        (is (= :invalid-chart-state (:reason error))
            (str "malformed definition " label " surfaces a banner-friendly reason"))))))

(deftest encode-rejects-malformed-definitions
  (testing "rf2-3fc89f.18 — the encoder rejects the same malformed definitions
            (encode/decode stay symmetric — the encoder never emits a payload
            the decoder would reject)"
    (doseq [[label definition] malformed-definitions]
      (let [d (try (encode {:machine-id :demo :definition definition})
                   (catch :default e (ex-data e)))]
        (is (= :invalid-chart-state (:reason d))
            (str "malformed definition " label " must be rejected at encode"))
        (is (= :rf.machines-viz.share/encode-failed (:rf.error/id d)))))))

(deftest valid-definitions-round-trip-unchanged
  (testing "rf2-3fc89f.18 — hardening the gate does NOT regress valid
            definitions; flat / compound / parallel / timeout / choice authored
            forms all round-trip UNCHANGED (share stores the authored form; the
            boundary desugars only to validate, never rewriting the payload)"
    (doseq [[label definition] valid-definitions]
      (let [cs   {:machine-id :demo :definition definition}
            back (:rf.machines-viz.share/chart
                   (share/decode-share-url (encode cs)))]
        (is (= definition (:definition back))
            (str label " definition round-trips UNCHANGED (authored form preserved)"))))))

(deftest share-definition-gate-agrees-with-canonical-grammar
  (testing "rf2-3fc89f.18 — the share boundary accepts/rejects EXACTLY the
            definitions the canonical Machines-Viz grammar gate does (the same
            gate the AI / Mermaid / SCXML emitters + chart projector share), so
            one machine value cannot be accepted by one surface and rejected by
            another. Pins one table of valid + invalid shapes against the
            canonical predicate so the boundaries cannot drift again."
    (doseq [[label definition] (merge valid-definitions malformed-definitions)]
      ;; `grammar/valid-definition?` is a truthy/falsy predicate (its last
      ;; `and` term is `(seq …)` — a seq, not a literal boolean), so coerce
      ;; both sides to booleans before comparing agreement.
      (let [canonical? (boolean (grammar/valid-definition? (grammar/desugar-grammar definition)))
            share-ok?  (some? (:ok (share/decode-share-url-safe (forge-definition-url definition))))]
        (is (= canonical? share-ok?)
            (str label ": share boundary must agree with the canonical grammar gate "
                 "(canonical? " canonical? ", share-ok? " share-ok? ")"))))))

;; ---------------------------------------------------------------------------
;; rf2-j538f7.18 — RECURSIVELY-malformed definitions (structurally invalid
;; BELOW the root: a nested compound missing :initial, a dangling transition
;; target, an unknown bare node key) ALSO fail closed at the share boundary. The
;; pre-fix shallow canonical gate blessed these (their ROOT shape is fine), so a
;; forged share-URL carrying one decoded :ok and reached MachineChart even though
;; the definition is rejected everywhere the runtime is consulted. The recursive
;; gate now rejects them at BOTH encode and decode, exactly like the flat /
;; parallel shapes rf2-3fc89f.18 covered (which stay green).

(def recursively-malformed-definitions
  {:nested-compound-no-initial {:initial :outer :states {:outer {:states {:inner {}}}}}
   :dangling-target            {:initial :idle :states {:idle {:on {:go :missing}}}}
   :unknown-node-key           {:initial :idle :states {:idle {:on-entry :oops}}}})

(deftest decoded-recursively-malformed-definition-rejected
  (testing "rf2-j538f7.18 — a forged v2 share-URL carrying a recursively-
            malformed definition fails closed at decode (:invalid-chart-state),
            via BOTH the throwing and the safe decode APIs"
    (doseq [[label definition] recursively-malformed-definitions]
      (let [d (try (share/decode-share-url (forge-definition-url definition))
                   (catch :default e (ex-data e)))]
        (is (= :invalid-chart-state (:reason d))
            (str "recursively-malformed " label " must fail closed at decode"))
        (is (= :rf.machines-viz.share/decode-failed (:rf.error/id d))))
      (let [{:keys [ok error]} (share/decode-share-url-safe (forge-definition-url definition))]
        (is (nil? ok) (str label " must NOT decode :ok"))
        (is (= :invalid-chart-state (:reason error)))))))

(deftest encode-rejects-recursively-malformed-definitions
  (testing "rf2-j538f7.18 — the encoder rejects the same recursively-malformed
            definitions (encode/decode stay symmetric)"
    (doseq [[label definition] recursively-malformed-definitions]
      (let [d (try (encode {:machine-id :demo :definition definition})
                   (catch :default e (ex-data e)))]
        (is (= :invalid-chart-state (:reason d))
            (str "recursively-malformed " label " must be rejected at encode"))
        (is (= :rf.machines-viz.share/encode-failed (:rf.error/id d)))))))

(deftest share-gate-recursively-malformed-agrees-with-canonical-grammar
  (testing "rf2-j538f7.18 — the share boundary rejects EXACTLY the recursively-
            malformed definitions the canonical RECURSIVE grammar gate rejects,
            and the prior rf2-3fc89f.18 flat/parallel cases remain rejected"
    (doseq [[label definition] (merge malformed-definitions recursively-malformed-definitions)]
      (let [canonical? (boolean (grammar/valid-definition? (grammar/desugar-grammar definition)))
            share-ok?  (some? (:ok (share/decode-share-url-safe (forge-definition-url definition))))]
        (is (false? canonical?) (str label " is rejected by the canonical recursive grammar gate"))
        (is (= canonical? share-ok?)
            (str label ": share boundary agrees with the canonical grammar gate"))))))

;; ---------------------------------------------------------------------------
;; rf2-dplwxh — top-level ChartState is CLOSED on decode. The encoder
;; allowlists to #{:machine-id :frame-id :definition :snapshot} before
;; serialising, but a hand-crafted URL bypasses the encoder entirely. The
;; decoder must therefore reject any extra top-level chart key
;; (:source-coords, :data, or any future unreviewed field) rather than
;; returning it in the public envelope — the viewer contract loads nothing
;; outside the validated payload schema (API.md §Share-URL payload schema:
;; "Anything not in the schema is silently dropped by the encoder. New
;; top-level keys … require an explicit :rf.machines-viz.share/allow? opt-in").

(deftest decoded-extra-top-level-source-coords-rejected
  (testing "a forged URL adding a top-level :source-coords cannot survive decode"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :source-coords {:file "/Users/mike/secret/x.cljs"
                                                                            :line 42})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "an extra top-level :source-coords fails the closed ChartState check at decode"))))

(deftest decoded-extra-top-level-data-rejected
  (testing "a forged URL adding a top-level :data cannot survive decode"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :data {:token "leak-abc"
                                                                   :form  {:password "hunter2"}})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "an extra top-level :data fails the closed ChartState check at decode"))))

(deftest decoded-future-unreviewed-top-level-key-rejected
  (testing "any unknown future top-level key is rejected — the set is closed, not just the two known leaks"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :rf.machines-viz.share/some-future-field
                                                            {:anything :goes})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "an unreviewed top-level key requires the documented allow? opt-in, so decode fails closed"))))

(deftest decoded-extra-top-level-key-rejected-safe
  (testing "decode-share-url-safe returns {:error {:reason :invalid-chart-state}} for a forged extra key"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :source-coords {:file "/Users/mike/secret/x.cljs"}
                                                            :data {:token "leak-abc"})
                      :rf.machines-viz.share/created 0})
          {:keys [ok error]} (share/decode-share-url-safe smuggled)]
      (is (nil? ok) "the forged payload is NOT returned as :ok")
      (is (= :invalid-chart-state (:reason error))
          "the safe wrapper surfaces a banner-friendly reason rather than leaking the forged chart"))))

(deftest decoded-extra-key-alongside-valid-snapshot-rejected
  (testing "a forged top-level key is rejected even when the rest of the ChartState (incl. :snapshot) is valid"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :snapshot {:state :loading}  ;; legitimately valid
                                                            :data {:token "leak"})       ;; forged extra
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "a valid :snapshot does not excuse an extra top-level key"))))

(deftest valid-chart-state-with-exact-keys-still-decodes
  (testing "guard against over-tightening — a legitimate ChartState (no extra keys) still round-trips"
    (let [url  (encode chart-state)
          back (:rf.machines-viz.share/chart (share/decode-share-url url))]
      (is (= :auth/login-flow (:machine-id back)))
      (is (= {:state :loading} (:snapshot back)))
      (is (= #{:machine-id :frame-id :definition :snapshot} (set (keys back)))
          "the decoded chart carries exactly the four ChartState keys"))))

(deftest malformed-fragment-rejected
  (testing "a URL with no #machine= fragment throws :malformed-fragment"
    ;; rf2-vvixub — the message is now the human sentence + the
    ;; [:rf.machines-viz.share/decode-failed] token; the fine-grained
    ;; classification rides the documented :reason slot (branch on that).
    (is (thrown? :default
          (share/decode-share-url "https://example.com/app")))
    (let [d (try (share/decode-share-url "https://example.com/app")
                 (catch :default e (ex-data e)))]
      (is (= :rf.machines-viz.share/decode-failed (:rf.error/id d)))
      (is (= :malformed-fragment (:reason d))))))

(deftest malformed-base64-rejected
  (testing "a #machine= fragment that isn't valid base64url throws"
    (let [d (try (share/decode-share-url "https://x/viewer.html#machine=@@@not-b64@@@")
                 (catch :default e (ex-data e)))]
      (is (contains? #{:malformed-fragment :malformed-payload} (:reason d))))))

(deftest invalid-chart-state-rejected
  (testing "a decoded chart that fails the schema throws :invalid-chart-state"
    ;; Encode a valid one, then re-encode a tampered ChartState directly
    ;; through the encoder is blocked by the encoder's own validation.
    ;; So assert the encoder rejects an invalid ChartState up front.
    (is (thrown? :default
          (encode {:machine-id :x :frame-id :y :definition {}})))
    (let [d (try (encode {:machine-id "not-a-kw"
                                          :frame-id :y
                                          :definition idle-loading-success})
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))))))

(deftest decode-safe-wraps-errors
  (testing "decode-share-url-safe returns {:error ...} not a throw"
    (let [r (share/decode-share-url-safe "https://example.com/app")]
      (is (= :malformed-fragment (get-in r [:error :reason])))
      (is (nil? (:ok r))))
    (testing "and {:ok envelope} on success"
      (let [r (share/decode-share-url-safe (encode chart-state))]
        (is (some? (:ok r)))
        (is (nil? (:error r)))))))

;; ---------------------------------------------------------------------------
;; chart-state->props

(deftest chart-state->props-projection
  (testing "envelope → MachineChart props (read-only, current-state from snapshot)"
    (let [env   (share/decode-share-url (encode chart-state))
          props (share/chart-state->props env)]
      (is (= :auth/login-flow (:machine-id props)))
      (is (= idle-loading-success (:definition props)))
      (is (= :loading (:current-state props)))
      (is (true? (:read-only? props)))
      (is (not (contains? props :frame-id)) "frame-id is provenance, not a prop")))
  (testing "no snapshot → no :current-state"
    (let [env   (share/decode-share-url (encode (dissoc chart-state :snapshot)))
          props (share/chart-state->props env)]
      (is (not (contains? props :current-state)))
      (is (true? (:read-only? props)))))
  (testing "compound vector-path snapshot projects :current-state verbatim"
    (let [cs    {:machine-id :shop/store :frame-id :app/main
                 :definition compound-definition
                 :snapshot   {:state [:authenticated :cart :browsing]}}
          props (share/chart-state->props (share/decode-share-url (encode cs)))]
      (is (= [:authenticated :cart :browsing] (:current-state props)))))
  (testing "parallel region-map snapshot projects :current-state verbatim"
    (let [cs    (assoc parallel-state :snapshot {:state {:data :dirty :form :busy}})
          props (share/chart-state->props (share/decode-share-url (encode cs)))]
      (is (= {:data :dirty :form :busy} (:current-state props))))))

;; ---------------------------------------------------------------------------
;; EP-0015 — error ex-data carries NO raw payload (rf2-8nzxib)
;;
;; A thrown encode/decode error must NOT retain the rejected payload in
;; ex-data: a forged share URL can smuggle a `:snapshot {:data …}` map or
;; arbitrary runtime values, and projection cannot walk ex-data after the
;; fact (Spec 015 §exception-path residual). The error keeps value-FREE
;; structural diagnostics (reason/category, key SET, type) instead.

(defn- ex-data-strings
  "Every string that appears ANYWHERE in `m` (deep walk) — so a test can
  assert a secret value never survives into the error map under any key."
  [m]
  (let [acc (atom [])]
    (clojure.walk/postwalk
      (fn [x] (when (string? x) (swap! acc conj x)) x)
      m)
    @acc))

(deftest encode-error-omits-raw-chart-state
  (testing "encode-failed ex-data carries a value-free summary, not the raw chart-state"
    ;; A chart-state whose :snapshot smuggles a secret-bearing :data map,
    ;; AND a malformed :state so encode rejects it.
    (let [secret "hunter2-super-secret-token"
          leaky  {:machine-id :auth/flow
                  :frame-id   :app/main
                  :definition idle-loading-success
                  :snapshot   {:state "not-an-arm"        ;; rejected
                               :data  {:password secret}}}
          d      (try (encode leaky)
                      (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d)) "reason/category preserved")
      (is (not (contains? d :chart-state)) "no raw chart-state slot")
      (is (some? (:chart-state-summary d)) "value-free summary present")
      (is (not (some #(str/includes? % secret) (ex-data-strings d)))
          "the secret string must not survive anywhere in ex-data"))))

(deftest decode-error-omits-raw-envelope
  (testing "missing-envelope decode-failed carries a value-free envelope summary, not the raw envelope"
    (let [secret  "session-cookie-abc123"
          ;; A forged envelope missing the required keys but carrying a secret.
          url     (envelope->url {:totally :wrong :secret secret})
          d       (try (share/decode-share-url url)
                       (catch :default e (ex-data e)))]
      (is (= :missing-envelope (:reason d)) "reason/category preserved")
      (is (not (contains? d :envelope)) "no raw envelope slot")
      (is (some? (:envelope-summary d)) "value-free summary present")
      (is (not (some #(str/includes? % secret) (ex-data-strings d)))
          "the secret string must not survive anywhere in ex-data"))))

(deftest decode-error-omits-raw-chart
  (testing "invalid-chart-state decode-failed carries a value-free chart summary, not the raw chart"
    (let [secret "bearer-token-xyz789"
          ;; A well-formed envelope whose :chart smuggles a secret via a
          ;; :snapshot {:data …} the viewer will reject (closed-map rule).
          forged  {:rf.machines-viz.share/v     "1"
                   :rf.machines-viz.share/chart {:machine-id :auth/flow
                                                 :frame-id   :app/main
                                                 :definition idle-loading-success
                                                 :snapshot   {:state :loading
                                                              :data  {:token secret}}}}
          url     (envelope->url forged)
          d       (try (share/decode-share-url url)
                       (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d)) "reason/category preserved")
      (is (not (contains? d :chart)) "no raw chart slot")
      (is (some? (:chart-summary d)) "value-free summary present")
      (is (not (some #(str/includes? % secret) (ex-data-strings d)))
          "the secret string must not survive anywhere in ex-data"))))

;; ---------------------------------------------------------------------------
;; EP-0015 — the thrown diagnostic is content-free BY CONSTRUCTION (rf2-m46qv)
;;
;; The section above plants a secret in a VALUE position and hunts for it.
;; It passed for as long as `value-free-summary` disclosed the payload
;; anyway, because a sentinel hunt only ever finds the leak someone thought
;; to plant: the map leg returned `:keys` — every top-level key, uncapped
;; and unsanitised — and the keyword leg returned the raw keyword as
;; `:value`. Both sit in KEY / TYPE positions the old hunt never looked at,
;; and both are attacker-chosen in CONTENT and in SIZE, since the whole
;; point of this namespace's header is that "a forged share URL can smuggle
;; arbitrary runtime values".
;;
;; So the checks below are a GRAMMAR, not a hunt. Every summary this
;; namespace can emit, over a corpus of hostile inputs, must consist of a
;; `:type` drawn from a closed vocabulary plus an integer `:count` and
;; NOTHING else, and every thrown ex-data must serialize inside a fixed
;; bound however large the forged payload is. A future leak fails that
;; without anyone remembering to plant a sentinel for it.

(def ^:private sentinel
  "The token planted in every position a forged payload can reach. Nothing
  this namespace throws may reproduce it — as a string, a keyword, a
  symbol, a map KEY, or a fragment of any of them."
  "hunter2-swordfish-SENTINEL")

(def ^:private sentinel-fragments
  "Every 8-character window of the sentinel. A leak is proved by a FRAGMENT,
  not only by the whole token: a bounded prefix of attacker material is the
  defect, not the fix, and a host `JSON.parse` message discloses exactly
  such a prefix."
  (into #{} (map #(subs sentinel % (+ % 8))) (range (- (count sentinel) 7))))

(defn- discloses?
  "Does `x`, once serialized, reproduce any fragment of the sentinel — under
  any key, at any depth, as a string, a keyword, a symbol or a map key?
  `pr-str` is the check rather than the string walk above precisely because
  a leaked KEYWORD is not a string, which is how the `:keys` leg survived a
  test file that already claimed to assert this."
  [x]
  (let [s (pr-str x)]
    (boolean (some #(str/includes? s %) sentinel-fragments))))

(def ^:private summary-type-vocabulary
  "The CLOSED `:type` vocabulary a value-free summary may emit — the same
  set `re-frame.error/diag-value-summary` uses, so a tool reading a thrown
  ex-data from either surface reads ONE diagnostic vocabulary."
  #{:map :vector :seq :set :keyword :symbol :string :number :boolean :nil
    :fn :scalar})

(defn- content-free-summary?
  "The grammar. A summary is a map whose key set is a subset of
  `#{:type :count}`, whose `:type` is in the closed vocabulary, and whose
  `:count` — when present — is a non-negative integer. Nothing else may
  appear, because every other slot would have to be derived from the
  input's CONTENT."
  [s]
  (and (map? s)
       (every? #{:type :count} (keys s))
       (contains? summary-type-vocabulary (:type s))
       (or (not (contains? s :count))
           (let [c (:count s)]
             (and (integer? c) (not (neg? c)))))))

(def ^:private summary-serialized-bound
  "A summary is `{:type :keyword}`-sized whatever arrives. 48 characters is
  slack over the longest legal shape (`{:type :boolean, :count 999999}`)
  and orders of magnitude under the inputs below."
  48)

(def ^:private ex-data-serialized-bound
  "The whole thrown ex-data — human `:message` included — against forged
  payloads of ~50 KB. The bound proves SIZE-INDEPENDENCE, not brevity."
  600)

(defn- exploding-object
  "A host object whose `toString` throws. A caller can put one in a
  chart-state key, and `(sort-by str (keys v))` ran `str` over every key —
  so the old summariser could throw the key's OWN exception in place of the
  failure it was called to describe."
  []
  (let [o #js {}]
    (set! (.-toString o)
          (fn [] (throw (js/Error. (str "toString exploded: " sentinel)))))
    o))

(def ^:private hostile-keys
  "Sentinel-bearing map keys of every key type transit carries, plus keys
  that are markup and control characters — a disclosed key set is pasted
  into a console, a log viewer or an issue tracker."
  {(str "string-key-" sentinel)                    1
   (keyword sentinel)                              2
   (keyword sentinel sentinel)                     3
   (symbol sentinel)                               4
   [sentinel]                                      5
   {sentinel sentinel}                             6
   (str "<script>alert(" sentinel ")</script>")    7
   (str (js/String.fromCharCode 27) "[31m" sentinel (js/String.fromCharCode 27) "[0m")  8
   (str "CR\r\nLF-" sentinel)                      9
   (str "NUL" (js/String.fromCharCode 0) "-" sentinel)               10
   4111111111111111                                11
   true                                            12})

(def ^:private attacker-sized-envelope
  "2000 sentinel-bearing keys. The old `:keys` leg reproduced every one of
  them, so the summary grew with the forger's input without limit."
  (into {} (map (fn [i] [(keyword (str sentinel "-" i)) i])) (range 2000)))

(def ^:private forged-payloads
  "Payloads a forged `#machine=` fragment can decode to. Transit carries
  every one of them, so every one is attacker-reachable through the PUBLIC
  decoder — including the arms whose summary legs the old code never
  bounded at all."
  [["a map keyed by sentinels of every key type"  hostile-keys]
   ["an attacker-sized 2000-key map"              attacker-sized-envelope]
   ["a nested map-of-map-of-set"                  {:a {:b #{sentinel}}
                                                   :c [{(keyword sentinel) 1}]}]
   ["a keyword with no length bound"              (keyword (apply str (repeat 20 sentinel)))]
   ["a namespaced keyword"                        (keyword sentinel sentinel)]
   ["a symbol"                                    (symbol sentinel)]
   ["a 4004-character string"                     (apply str (repeat 154 sentinel))]
   ["a vector of secrets"                         [sentinel sentinel]]
   ["a set of secrets"                            #{sentinel}]
   ["a list of secrets"                           (list sentinel)]
   ["a 16-digit card number"                      4111111111111111]
   ["a boolean"                                   true]
   ["nil"                                         nil]])

(deftest value-free-summary-is-content-free-by-construction
  (testing "every summary is a closed-vocabulary :type plus an integer :count, and nothing else"
    (doseq [[label v] (concat forged-payloads
                              ;; The arms no transit payload can reach, which a
                              ;; caller of `encode-share-url` still can.
                              [["a lazy seq"                          (map identity [sentinel])]
                               ["a live fn"                           (fn [] sentinel)]
                               ["an opaque host object"               (js-obj "k" sentinel)]
                               ["a host object whose toString throws" (exploding-object)]
                               ["a map keyed by an exploding object"  {(exploding-object) :x}]])]
      (let [s (#'share/value-free-summary v)]
        (is (content-free-summary? s)
            (str label " — expected {:type …} (+ :count), got " (pr-str s)))
        (is (not (discloses? s))
            (str label " — no sentinel fragment may survive"))
        (is (<= (count (pr-str s)) summary-serialized-bound)
            (str label " — fixed serialized bound, got " (count (pr-str s))))))))

(deftest decode-error-discloses-nothing-it-was-given
  (testing "a forged payload's decode failure names the shape and nothing else"
    (doseq [[label payload] forged-payloads]
      (let [url (envelope->url payload)
            e   (try (share/decode-share-url url) nil
                     (catch :default ex ex))
            d   (ex-data e)]
        (is (some? e) (str label " — decode must refuse"))
        (is (= :missing-envelope (:reason d)) (str label " — the category survives"))
        (is (content-free-summary? (:envelope-summary d))
            (str label " — summary was " (pr-str (:envelope-summary d))))
        (is (not (discloses? d))
            (str label " — no sentinel fragment anywhere in ex-data"))
        (is (not (discloses? (ex-message e)))
            (str label " — no sentinel fragment in the thrown message"))
        (is (< (count (pr-str d)) ex-data-serialized-bound)
            (str label " — ex-data serialized " (count (pr-str d)) " chars"))))))

(deftest decode-error-ex-data-does-not-grow-with-the-payload
  (testing "a 2000-key forged envelope throws the same size ex-data as a 2-key one"
    (let [size  (fn [payload]
                  (count (pr-str (ex-data (try (share/decode-share-url (envelope->url payload))
                                               nil
                                               (catch :default e e))))))
          small (size {:a 1 :b 2})
          big   (size attacker-sized-envelope)]
      (is (<= (- big small) 4)
          (str "ex-data may grow only by the DIGITS of :count; "
               small " → " big)))))

(deftest decode-error-omits-the-caller-url
  (testing "a URL with no #machine= fragment does not ride into ex-data"
    ;; The encoder already refuses to put `:host` in ex-data, because "a
    ;; viewer URL can carry a query string with a token in it" — that is what
    ;; its `:fragment-index` slot is for. The decoder is the far more exposed
    ;; side (it is handed URLs from elsewhere) and it carried the WHOLE url.
    (let [url (str "https://x/viewer.html?session=" sentinel)
          e   (try (share/decode-share-url url) nil (catch :default ex ex))
          d   (ex-data e)]
      (is (= :malformed-fragment (:reason d)))
      (is (not (contains? d :url)) "no raw :url slot")
      (is (content-free-summary? (:url-summary d)))
      (is (not (discloses? d)) "no sentinel fragment in ex-data")
      (is (not (discloses? (ex-message e))) "no sentinel fragment in the message"))))

(deftest decode-error-omits-the-host-parse-message
  (testing "the host parser's own error message does not republish the payload"
    ;; `transit/read` calls `JSON.parse`, and V8 embeds a PREFIX OF ITS INPUT
    ;; in the SyntaxError it throws. `:cause (.-message e)` therefore
    ;; republished the forged payload's plaintext under a slot named for the
    ;; cause — a leak nobody wrote, inherited from the host.
    (let [plaintext (str sentinel "-not-transit")
          b64       (-> (js/btoa (js/unescape (js/encodeURIComponent plaintext)))
                        (str/replace "+" "-")
                        (str/replace "/" "_")
                        (str/replace "=" ""))
          e         (try (share/decode-share-url (str test-host "#machine=" b64))
                         nil
                         (catch :default ex ex))
          d         (ex-data e)]
      (is (= :malformed-payload (:reason d)))
      (is (not (contains? d :cause)) "no host-message slot")
      (is (not (discloses? d)) "no sentinel fragment in ex-data")
      (is (not (discloses? (ex-message e))) "no sentinel fragment in the message")))
  (testing "the base64 stage carries no host message either"
    (let [e (try (share/decode-share-url (str test-host "#machine=" sentinel "!!!"))
                 nil
                 (catch :default ex ex))
          d (ex-data e)]
      (is (= :malformed-fragment (:reason d)))
      (is (not (contains? d :cause)) "no host-message slot")
      (is (not (discloses? d)) "no sentinel fragment in ex-data"))))

(deftest decode-error-omits-the-forged-version
  (testing "a numeric-looking version reports the INTEGER the compare used"
    (let [url (envelope->url {:rf.machines-viz.share/v     (str "9999-" sentinel)
                              :rf.machines-viz.share/chart chart-state})
          e   (try (share/decode-share-url url) nil (catch :default ex ex))
          d   (ex-data e)]
      (is (= :unknown-version (:reason d)))
      (is (= 9999 (:payload-version d)) "the parsed integer, not the raw :v")
      (is (not (discloses? d)) "no sentinel fragment in ex-data")))
  (testing "a version that does not parse at all reports no version"
    (let [url (envelope->url {:rf.machines-viz.share/v     (str "v" sentinel)
                              :rf.machines-viz.share/chart chart-state})
          e   (try (share/decode-share-url url) nil (catch :default ex ex))
          d   (ex-data e)]
      (is (= :unknown-version (:reason d)))
      (is (nil? (:payload-version d)) "nil, not a 4000-character 'version'")
      (is (not (discloses? d)) "no sentinel fragment in ex-data"))))

(deftest encode-error-discloses-nothing-it-was-given
  (testing "a rejected chart-state's key set does not ride into ex-data"
    (let [leaky (merge hostile-keys
                       {:machine-id :auth/flow
                        :definition idle-loading-success
                        :snapshot   {:state "not-an-arm"}})
          e     (try (encode leaky) nil (catch :default ex ex))
          d     (ex-data e)]
      (is (= :invalid-chart-state (:reason d)))
      (is (content-free-summary? (:chart-state-summary d)))
      (is (not (discloses? d)) "no sentinel fragment in ex-data")
      (is (not (discloses? (ex-message e))) "no sentinel fragment in the message")))
  (testing "a chart-state key whose toString throws no longer destroys the failure being described"
    (let [leaky {(exploding-object) :whatever
                 :machine-id        :auth/flow
                 :definition        idle-loading-success
                 :snapshot          {:state "not-an-arm"}}
          e     (try (encode leaky) nil (catch :default ex ex))
          d     (ex-data e)]
      (is (= :rf.machines-viz.share/encode-failed (:rf.error/id d))
          "the documented ex-info, not the hostile key's own exception")
      (is (= :invalid-chart-state (:reason d)))
      (is (content-free-summary? (:chart-state-summary d)))
      (is (not (discloses? d)) "no sentinel fragment in ex-data"))))
