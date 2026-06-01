(ns realworld.tags-test
  "Headless tests for realworld.tags.

   Covers two things:
   - tag filter and feed-kind round-trip via the route slice (the
     home-page query helpers);
   - the `:realworld/tags` machine (the :data-region machine variant
     of Pattern-RemoteData) — load happy path + failure path
     + tag-shaped queries replacing the slice's `:loading?` /
     `:fetching?` booleans."
  (:require [re-frame.core :as rf]
            [realworld.tags]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(defn tag-query-test []
  (with-new-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/app-db-value f)))))
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (assert (= "your" (:feed (rf/compute-sub [:rf.route/query] (rf/app-db-value f)))))))

(defn- snapshot [db]
  (get-in db [:rf/runtime :machines :snapshots :realworld/tags]))

(defn- machine-has-tag?
  "Read the machine's :tags union against a frame's app-db (browserless
   form of `rf/machine-has-tag?` — uses `compute-sub` instead of a reactive
   deref so the test runs in any CLJS host)."
  [frame tag]
  (rf/compute-sub [:rf/machine-has-tag? :realworld/tags tag]
                  (rf/app-db-value frame)))

(defn tags-machine-load-test []
  ;; The :tags lifecycle — load happy path through the machine. The
  ;; assertions below are the SAME questions a slice-form reader would
  ;; ask, but each answer comes from a different surface:
  ;;
  ;;     SLICE FORM                              MACHINE FORM
  ;;     ----------                              ------------
  ;;     (:status slice) = :loaded               (:state snap)  = :loaded
  ;;     (:data slice)                           (-> snap :data :tags)
  ;;     :loading? (a derived boolean sub)       (machine-has-tag? :tags/loading)
  (th/reg-canned-success! :realworld.test/canned-tags
                          {:tags ["intro" "demo" "clojure"]})
  (with-new-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-tags}})]
    ;; After :app/initialise → :tags/initialise → [:reset], the machine
    ;; sits at :idle with empty :data.
    (let [snap (snapshot (rf/app-db-value f))]
      (assert (= :idle (:state snap)))
      (assert (= []    (get-in snap [:data :tags])))
      (assert (= 0     (get-in snap [:data :attempt]))))

    ;; First fetch: the canned-success stub resolves synchronously, so
    ;; we observe the machine in :loaded (not :loading) after the
    ;; dispatch returns. The slice's `:status :loaded` and `:data
    ;; ["intro" "demo" "clojure"]` are now the machine's `:state
    ;; :loaded` + `:data :tags`.
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [db   (rf/app-db-value f)
          snap (snapshot db)]
      (assert (= :loaded (:state snap)))
      (assert (= ["intro" "demo" "clojure"]
                 (get-in snap [:data :tags])))
      (assert (= 1 (get-in snap [:data :attempt])))
      ;; Cofx-shape contract — `:loaded-at` carries the
      ;; `:realworld/now` cofx value into `:set-tags`. If the cofx body
      ;; assoc'd at top-level of ctx rather than into :coeffects, `now`
      ;; would reach the action as nil and this assertion would fail.
      (assert (number? (get-in snap [:data :loaded-at])))
      ;; tag-shaped queries — these replace the slice's `:tags/loading?`
      ;; / `:tags/fetching?` derived boolean subs.
      (assert (true?  (machine-has-tag? f :tags/loaded)))
      (assert (false? (machine-has-tag? f :tags/loading)))
      (assert (false? (machine-has-tag? f :tags/in-flight)))
      ;; the `:tags/data` sub returns the items, same name a slice-form
      ;; reader would use; only the source changed.
      (assert (= ["intro" "demo" "clojure"]
                 (rf/compute-sub [:tags/data] db))))

    ;; Second fetch with prior data present: the region picks :fetching
    ;; (not :loading) so the sidebar doesn't blank out. The
    ;; canned-success stub resolves synchronously so we see :loaded
    ;; again at the end with the attempt counter bumped.
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [snap (snapshot (rf/app-db-value f))]
      (assert (= :loaded (:state snap)))
      (assert (= 2 (get-in snap [:data :attempt]))))))

(defn tags-machine-failure-test []
  ;; Failure path — the :tags region lands in :error with the projected
  ;; failure message in :data, and the `:tags/error` derived sub picks
  ;; it up. Prior :data (if any) is preserved across the transition.
  (th/reg-canned-failure! :realworld.test/canned-tags-failure
                          :rf.http/http-5xx
                          {:status 500 :body "server error"})
  (with-new-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:rf.http/managed :realworld.test/canned-tags-failure}})]
    (rf/dispatch-sync [:tags/load] {:frame f})
    (let [db   (rf/app-db-value f)
          snap (snapshot db)]
      (assert (= :error (:state snap)))
      (assert (some? (get-in snap [:data :error])))
      (assert (some? (rf/compute-sub [:tags/error] db)))
      (assert (true?  (machine-has-tag? f :tags/error)))
      (assert (false? (machine-has-tag? f :tags/in-flight))))))
