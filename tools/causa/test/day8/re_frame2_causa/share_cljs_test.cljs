(ns day8.re-frame2-causa.share-cljs-test
  "CLJS tests for the Causa Share infra (rf2-nqw0v, Phase 5).

  Covers:

    1. `encode-state` / `decode-state` round-trip — the encoded URL
       restores the same Causa state map.
    2. `build-share-url` / `decode-share-url` — full-URL round-trip.
    3. The query-string sentinel — non-share URLs return nil from
       `decode-state` so the on-load restore path short-circuits.
    4. Registry wiring — install! registers the share sub family.
    5. Open / close modal events flip the slot.
    6. The copy event-fx queues the clipboard fx with the encoded URL.
    7. `restore-from-share-url` writes the per-slot values into the
       Causa app-db."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.share :as share]
            [day8.re-frame2-causa.share-modal :as share-modal]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- (1) encode / decode round-trip ------------------------------------

(deftest encode-state-includes-sentinel
  (let [pairs (share/encode-state {})]
    (is (some (fn [[k _]] (= "causa-share" k)) pairs)
        "the sentinel key is always present")))

(deftest encode-state-includes-namespaced-keyword
  (let [pairs (share/encode-state {:machine-id :auth/login})
        m     (into {} pairs)]
    (is (= "auth/login" (get m "machine")))))

(deftest encode-state-handles-bare-keyword
  (let [pairs (share/encode-state {:machine-id :foo})
        m     (into {} pairs)]
    (is (= "foo" (get m "machine")))))

(deftest encode-state-includes-mode-tab-pos
  (let [pairs (share/encode-state {:machine-id :auth/login
                                   :mode :mode-b
                                   :tab :machines
                                   :position 5})
        m     (into {} pairs)]
    (is (= "mode-b" (get m "mode")))
    (is (= "machines" (get m "tab")))
    (is (= "5" (get m "pos")))))

(deftest encode-state-present-position
  (let [pairs (share/encode-state {:machine-id :auth/login
                                   :position :present})
        m     (into {} pairs)]
    (is (= "present" (get m "pos")))))

(deftest encode-state-pos-defaults-to-present-when-nil
  (let [pairs (share/encode-state {:machine-id :auth/login})
        m     (into {} pairs)]
    (is (= "present" (get m "pos")))))

(deftest encode-state-deterministic-ordering
  (testing "the encoded pair-vec is sorted by key so the URL is stable
            across calls"
    (let [a (share/encode-state {:machine-id :a :mode :mode-b :tab :event})
          b (share/encode-state {:tab :event :machine-id :a :mode :mode-b})]
      (is (= a b)))))

(deftest query-string-builds-leading-question-mark
  (let [pairs [["a" "1"] ["b" "2"]]
        qs    (share/query-string pairs)]
    (is (= "?a=1&b=2" qs))))

(deftest query-string-empty
  (is (= "" (share/query-string []))))

(deftest parse-query-string-inverts-query-string
  (let [pairs [["a" "1"] ["b" "2"]]
        qs    (share/query-string pairs)
        m     (share/parse-query-string qs)]
    (is (= {"a" "1" "b" "2"} m))))

(deftest parse-query-string-tolerant-of-blank
  (is (= {} (share/parse-query-string nil)))
  (is (= {} (share/parse-query-string "")))
  (is (= {} (share/parse-query-string "?"))))

(deftest decode-state-nil-without-sentinel
  (is (nil? (share/decode-state {})))
  (is (nil? (share/decode-state {"machine" "auth/login"}))
      "missing causa-share=1 sentinel → non-share URL → nil"))

(deftest decode-state-restores-namespaced-keyword
  (let [s (share/decode-state {"causa-share" "1"
                               "machine"     "auth/login"})]
    (is (= :auth/login (:machine-id s)))))

(deftest decode-state-restores-pos-int
  (let [s (share/decode-state {"causa-share" "1"
                               "pos"         "5"})]
    (is (= 5 (:position s)))))

(deftest decode-state-restores-pos-present
  (let [s (share/decode-state {"causa-share" "1"
                               "pos"         "present"})]
    (is (= :present (:position s)))))

(deftest decode-state-restores-mode-and-tab
  ;; rf2-y9xmf: the `:mode` slot survives the encode/decode round-trip
  ;; on the wire (legacy URLs still parse), but the restore-event drops
  ;; it because the panel is event-driven post-collapse. The decoded
  ;; map keeps the slot so callers can detect / log legacy mode bits.
  (let [s (share/decode-state {"causa-share" "1"
                               "mode"        "mode-c"
                               "tab"         "machines"})]
    (is (= :mode-c (:mode s)))
    (is (= :machines (:tab s)))))

(deftest encode-decode-round-trip
  (testing "encode → decode preserves every payload slot. `:mode` is
            kept on the wire for legacy URL compatibility even though
            the Causa restore-event drops it post-rf2-y9xmf collapse."
    (let [original {:machine-id  :auth/login
                    :instance-id :auth/login
                    :mode        :mode-b
                    :tab         :machines
                    :position    7}
          encoded  (share/encode-state original)
          qs       (share/query-string encoded)
          decoded  (share/decode-state (share/parse-query-string qs))]
      (is (= (:machine-id original)  (:machine-id decoded)))
      (is (= (:instance-id original) (:instance-id decoded)))
      (is (= (:mode original)        (:mode decoded)))
      (is (= (:tab original)         (:tab decoded)))
      (is (= (:position original)    (:position decoded))))))

;; ---- (2) build / decode full URL ---------------------------------------

(deftest build-share-url-prefixes-base
  (let [url (share/build-share-url "https://example.com/app"
                                   {:machine-id :auth/login})]
    (is (re-find #"^https://example.com/app\?" url))))

(deftest decode-share-url-extracts-state-from-full-url
  (let [url   (share/build-share-url "https://example.com/app"
                                     {:machine-id :auth/login
                                      :position   3
                                      :mode       :mode-b})
        state (share/decode-share-url url)]
    (is (= :auth/login (:machine-id state)))
    (is (= 3 (:position state)))
    (is (= :mode-b (:mode state)))))

(deftest decode-share-url-nil-on-non-share-url
  (is (nil? (share/decode-share-url "https://example.com/app")))
  (is (nil? (share/decode-share-url "https://example.com/app?other=foo"))))

;; ---- (4) registry wiring -----------------------------------------------

(deftest install-registers-share-handlers
  (testing "register-causa-handlers! installs every Phase 5 share handler"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/share-modal-open?)))
    (is (some? (registrar/handler :sub :rf.causa/share-state)))
    (is (some? (registrar/handler :sub :rf.causa/share-url)))
    (is (some? (registrar/handler :sub :rf.causa/share-copy-status)))
    (is (some? (registrar/handler :event :rf.causa/share-modal-open)))
    (is (some? (registrar/handler :event :rf.causa/share-modal-close)))
    (is (some? (registrar/handler :event :rf.causa/share-copy-status)))
    (is (some? (registrar/handler :event :rf.causa/copy-share-url-to-clipboard)))
    (is (some? (registrar/handler :event :rf.causa/open-share-url-in-new-tab)))
    (is (some? (registrar/handler :event :rf.causa/restore-from-share-url)))
    (is (some? (registrar/handler :fx :rf.causa.fx/open-in-new-tab)))))

;; ---- (5) open / close --------------------------------------------------

(deftest open-and-close-modal
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (is (false? @(rf/subscribe [:rf.causa/share-modal-open?]))
        "modal closed by default")
    (rf/dispatch-sync [:rf.causa/share-modal-open])
    (is (true? @(rf/subscribe [:rf.causa/share-modal-open?])))
    (rf/dispatch-sync [:rf.causa/share-modal-close])
    (is (false? @(rf/subscribe [:rf.causa/share-modal-open?])))))

(deftest open-resets-copy-status
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/share-copy-status :failed])
    (rf/dispatch-sync [:rf.causa/share-modal-open])
    (is (= :idle @(rf/subscribe [:rf.causa/share-copy-status]))
        "opening the modal clears any leftover :failed / :copied status")))

;; ---- (6) copy event-fx queues clipboard fx -----------------------------

(deftest copy-event-queues-clipboard-fx
  (testing "the copy event-fx schedules :rf.causa/copy-to-clipboard
            with the encoded URL"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
      (let [captured (atom nil)]
        (with-redefs [share/copy-to-clipboard! (fn [t] (reset! captured t) nil)]
          (rf/dispatch-sync [:rf.causa/copy-share-url-to-clipboard]))
        (is (some? @captured)
            "clipboard fx fires with a URL")
        (is (re-find #"machine=auth(?:%2F|/)login" @captured)
            "URL carries the encoded machine-id (URL-encoded or raw)")))))

;; ---- (7) restore-from-share-url writes per-slot values -----------------

(deftest restore-writes-selection
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/restore-from-share-url
                       {:machine-id :auth/login
                        :mode       :mode-c
                        :tab        :machines
                        :position   3}])
    (is (= :auth/login @(rf/subscribe [:rf.causa/selected-machine-id])))
    (is (= :machines   @(rf/subscribe [:rf.causa/selected-tab])))
    (is (= 3           @(rf/subscribe [:rf.causa/machine-scrubber-position])))))

(deftest restore-drops-legacy-mode-slot
  ;; rf2-y9xmf: the `:mode` slot is dropped on restore — the panel is
  ;; event-driven post-collapse, there is no Mode A/B/C forcing. Legacy
  ;; URLs carrying `:mode` are silently ignored rather than rejected.
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/restore-from-share-url
                       {:machine-id :auth/login
                        :mode       :mode-c}])
    (is (= :auth/login @(rf/subscribe [:rf.causa/selected-machine-id]))
        "the rest of the payload still restores")
    (let [causa-db (frame/frame-app-db-value :rf/causa)]
      (is (nil? (:machine-inspector/forced-mode causa-db))
          "the legacy :mode slot does NOT land on app-db"))))

(deftest restore-tolerates-empty-state
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/restore-from-share-url {}])
    (is (nil? @(rf/subscribe [:rf.causa/selected-machine-id]))
        "empty state map = no-op")))

;; ---- frame isolation ---------------------------------------------------

(deftest share-state-lives-on-causa-frame
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/share-modal-open]))
  (let [causa-db   (frame/frame-app-db-value :rf/causa)
        default-db (frame/frame-app-db-value :rf/default)]
    (is (true? (:share/modal-open? causa-db))
        "modal-open flag lands on Causa")
    (is (nil? (:share/modal-open? default-db))
        "host frame is untouched")))

;; ---- Modal positioning (rf2-om6fa) -------------------------------------

(defn- expand-tree
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (tree-seq (some-fn vector? seq?) seq (expand-tree tree))))

(deftest share-modal-backdrop-defaults-to-fixed-positioning
  (testing "with no :rf.causa/modal-positioning slot set, the share
            modal backdrop renders position: fixed at the production
            z-index"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/share-modal-open]))
    (rf/with-frame :rf/causa
      (let [tree     (share-modal/Modal)
            backdrop (find-by-testid tree "rf-causa-share-modal-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "fixed" (:position style)))
        (is (= 2147483100 (:z-index style)))
        (is (= "fixed"
               (:data-rf-causa-modal-positioning (second backdrop))))))))

(deftest share-modal-backdrop-honours-absolute-positioning
  (testing "after `:rf.causa/set-modal-positioning :absolute` the
            share modal backdrop switches to position: absolute"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/share-modal-open])
      (rf/dispatch-sync [:rf.causa/set-modal-positioning :absolute]))
    (rf/with-frame :rf/causa
      (let [tree     (share-modal/Modal)
            backdrop (find-by-testid tree "rf-causa-share-modal-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "absolute" (:position style)))
        (is (< (:z-index style) 1000))
        (is (= "absolute"
               (:data-rf-causa-modal-positioning (second backdrop))))))))

;; ---- per-cascade structured export (rf2-0us27) -------------------------
;;
;; The share/install! family also registers the cascade-export surface:
;; a sub family that projects `:rf.causa/event-detail` →
;; `day8.re-frame2-causa.export.cascade/project-cascade`, an event-fx
;; for clipboard copy, and an event-fx + fx for file download.
;;
;; These tests prove the wiring exists and short-circuits cleanly when
;; no cascade is focused. The pure projection itself has dedicated
;; coverage in `export.cascade-cljs-test.cljc`.

(deftest install-registers-cascade-export-handlers
  (testing "register-causa-handlers! installs every rf2-0us27 cascade-
            export handler"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/cascade-export)))
    (is (some? (registrar/handler :sub :rf.causa/cascade-export-edn)))
    (is (some? (registrar/handler :sub :rf.causa/cascade-export-available?)))
    (is (some? (registrar/handler :sub :rf.causa/cascade-export-status)))
    (is (some? (registrar/handler :event :rf.causa/cascade-export-status)))
    (is (some? (registrar/handler :event :rf.causa/copy-cascade-export-to-clipboard)))
    (is (some? (registrar/handler :event :rf.causa/download-cascade-export)))
    (is (some? (registrar/handler :fx :rf.causa.fx/download-text-file)))))

(deftest cascade-export-unavailable-when-no-cascade-focused
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (is (false? @(rf/subscribe [:rf.causa/cascade-export-available?]))
        "with an empty trace buffer there is no cascade to export")
    (is (nil? @(rf/subscribe [:rf.causa/cascade-export]))
        "the projection is nil when no cascade is selected")
    (is (nil? @(rf/subscribe [:rf.causa/cascade-export-edn]))
        "the EDN string is nil when no cascade is selected")))

(deftest copy-cascade-export-no-op-without-cascade
  (testing "the copy event flips status to :failed (rather than
            throwing) when no cascade is focused"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [captured (atom :unset)]
        (with-redefs [share/copy-to-clipboard! (fn [t] (reset! captured t) nil)]
          (rf/dispatch-sync [:rf.causa/copy-cascade-export-to-clipboard]))
        (is (= :unset @captured)
            "no clipboard call when nothing to export")
        (is (= :failed @(rf/subscribe [:rf.causa/cascade-export-status]))
            "status flips to :failed so the modal can show the user")))))

(deftest download-cascade-export-no-op-without-cascade
  (testing "the download event flips status to :failed without
            firing the file-save fx"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [captured (atom :unset)]
        (with-redefs [share/download-text-file! (fn [_ _] (reset! captured :fired))]
          (rf/dispatch-sync [:rf.causa/download-cascade-export]))
        (is (= :unset @captured)
            "the download fx does not fire when nothing to export")
        (is (= :failed @(rf/subscribe [:rf.causa/cascade-export-status])))))))

(deftest cascade-export-status-transitions
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (is (= :idle @(rf/subscribe [:rf.causa/cascade-export-status]))
        "default status is :idle")
    (rf/dispatch-sync [:rf.causa/cascade-export-status :copied])
    (is (= :copied @(rf/subscribe [:rf.causa/cascade-export-status])))
    (rf/dispatch-sync [:rf.causa/cascade-export-status :downloaded])
    (is (= :downloaded @(rf/subscribe [:rf.causa/cascade-export-status])))
    (rf/dispatch-sync [:rf.causa/cascade-export-status :idle])
    (is (= :idle @(rf/subscribe [:rf.causa/cascade-export-status])))))

(deftest cascade-export-row-renders-in-share-modal
  (testing "the modal body renders the per-cascade export row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/share-modal-open]))
    (rf/with-frame :rf/causa
      (let [tree (share-modal/Modal)
            row  (find-by-testid tree "rf-causa-share-modal-export-cascade-row")
            copy (find-by-testid tree "rf-causa-share-modal-export-cascade-copy")
            dl   (find-by-testid tree "rf-causa-share-modal-export-cascade-download")]
        (is (some? row)
            "export row mounts inside the share dialog")
        (is (some? copy)
            "the cascade-copy button is in the tree")
        (is (some? dl)
            "the cascade-download button is in the tree")
        (is (true? (:disabled (second copy)))
            "the copy button is disabled when no cascade is available")
        (is (true? (:disabled (second dl)))
            "the download button is disabled when no cascade is available")))))
