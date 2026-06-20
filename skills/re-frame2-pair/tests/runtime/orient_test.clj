;;;; tests/runtime/orient_test.clj
;;;;
;;;; Babashka-runnable structural pin (rf2-3bu3d.8) that the runtime
;;;; preload defines `orient` — the app-shape orientation summary the MCP
;;;; `orient` tool wraps — and that it COMPOSES from the existing
;;;; introspection surfaces (no parallel reimplementation) and carries the
;;;; documented summary slots.
;;;;
;;;; ## What rf2-3bu3d.8 adds
;;;;
;;;;   First-contact on an UNFAMILIAR app otherwise took several calls
;;;;   (discover-app + snapshot top-keys + list-handlers +
;;;;   list-subscriptions + machines). `orient` composes them into one
;;;;   compact map by REUSING the existing surfaces:
;;;;     - `health` (liveness + frame counts),
;;;;     - `app-frame-ids` (app frames, tool frames split out),
;;;;     - `rf/app-db-value` per app frame (top-keys),
;;;;     - `registrar-list` / `rf/registrations` (registry counts + ids),
;;;;     - `rf/machines` (machine ids).
;;;;
;;;;   The summary is compact by design: counts + the navigable id vectors
;;;;   (:event / :sub / :fx) + per-app-frame app-db TOP-KEYS — not the full
;;;;   app-db — so it respects the wire cap.
;;;;
;;;; The CLJS preload can't run under bb (it needs the live re-frame2
;;;; registry), so this pins the source-level contract structurally + a
;;;; functional mirror of the shape composition.
;;;;
;;;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs §App-shape
;;;; orientation summary (`orient`).
;;;;
;;;; Run:  bb tests/runtime/orient_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns orient-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj
;; (rf2-yrpt90). Alias the vars the assertions below use.
(def ^:private defn-named rt/defn-named)
(def ^:private form-contains? rt/form-contains?)

(deftest defines-orient
  (is (some? (defn-named 'orient))
      "runtime.cljs must define `orient` — the app-shape orientation summary (rf2-3bu3d.8)."))

(deftest orient-carries-the-summary-slots
  (let [form (defn-named 'orient)]
    (is (some? form))
    (doseq [slot [:liveness :frames :app-db-top-keys :registry :machines]]
      (is (form-contains? #(= slot %) form)
          (str "orient MUST carry the " slot " summary slot (rf2-3bu3d.8).")))))

(deftest orient-reuses-existing-surfaces-no-reinvention
  (let [form        (defn-named 'orient)
        process-vw  (defn-named 'process-registry-view)]
    ;; The composition reuses health + app-frame-ids + the registry-view
    ;; helpers + rf/app-db-value + rf/machines — NOT a parallel
    ;; reimplementation.
    (is (form-contains? #(= 'health %) form)
        "orient reuses `health` for liveness/frames.")
    (is (form-contains? #(= 'app-frame-ids %) form)
        "orient reuses `app-frame-ids` (tool frames split out).")
    ;; rf2-srobm0 — the registry slot is now produced by the registry-view
    ;; helpers (frame-registry-view ⊳ process-registry-view fallback); the
    ;; process view still reuses `registrar-list` for the navigable ids.
    (is (and (form-contains? #(= 'frame-registry-view %) form)
             (form-contains? #(= 'process-registry-view %) form))
        "orient composes its :registry from the frame/process registry-view helpers.")
    (is (form-contains? #(= 'registrar-list %) process-vw)
        "process-registry-view reuses `registrar-list` for the navigable registry ids.")))

(deftest orient-excludes-tool-frames-from-top-keys
  ;; :app-db-top-keys must key on app frames (via app-frame-ids), so a
  ;; reserved :rf/* tool frame's inspection state can't overflow the
  ;; first-contact summary (rf2-3bu3d.6 posture).
  (let [form (defn-named 'orient)]
    (is (form-contains? #(= 'app-frame-ids %) form)
        "orient derives :app-db-top-keys keys from app-frame-ids — tool frames excluded.")))

;; ---------------------------------------------------------------------------
;; Functional mirror of the shape composition — assert the assembled map
;; carries the documented slots given representative inputs. KEEP IN SYNC.
;; ---------------------------------------------------------------------------

(defn- mirror-orient
  "Mirror of `orient`'s assembly from its reused inputs. KEEP IN SYNC with
   preload/re_frame2_pair/runtime.cljs §App-shape orientation summary.

   The `:registry` slot here mirrors the PROCESS-VIEW fallback shape
   (`process-registry-view`) — the byte-stable counts + navigable id vectors.
   Live, orient re-bases on the OPERATING FRAME's image generation when one
   resolves (rf2-srobm0), adding `:basis :frame` + `:frame <id>` and keying
   the counts off the frame's resolver; the process view carries `:basis
   :process`. This mirror pins the documented summary slot shape, not the
   per-basis resolution (the frame/process registry-view fns own that)."
  [{:keys [health app-fids app-dbs registrations machines]}]
  {:ok?      true
   :liveness {:debug-enabled?      (:debug-enabled? health)
              :frame-count         (count (:frames health))
              :app-frame-count     (count app-fids)
              :ambiguous-frame?    (:ambiguous-frame? health)
              :runtime-instance-id (:runtime-instance-id health)}
   :frames   {:all       (:frames health)
              :app       app-fids
              :operating (:operating-frame health)}
   :app-db-top-keys (into {} (map (fn [fid]
                                    [fid (let [db (get app-dbs fid)]
                                           (when (map? db) (vec (sort-by pr-str (keys db)))))]))
                          app-fids)
   :registry {:counts (into {} (map (fn [k] [k (count (get registrations k))]))
                            [:event :sub :fx])
              :events (vec (sort (keys (get registrations :event))))
              :subs   (vec (sort (keys (get registrations :sub))))
              :fx     (vec (sort (keys (get registrations :fx))))}
   :machines (vec machines)})

(deftest assembled-summary-has-the-documented-shape
  (let [r (mirror-orient
            {:health {:debug-enabled? true
                      :frames [:rf/default :rf/xray]
                      :ambiguous-frame? false
                      :operating-frame :rf/default
                      :runtime-instance-id "abc"}
             :app-fids [:rf/default]
             :app-dbs  {:rf/default {:cart 1 :user 2 :route 3}}
             :registrations {:event {:cart/add 0 :cart/checkout 0}
                             :sub   {:cart/total 0 :current-user 0}
                             :fx    {:http 0}}
             :machines [:checkout]})]
    (is (true? (:ok? r)))
    (is (= [:rf/default] (get-in r [:frames :app])))
    (is (= [:rf/default :rf/xray] (get-in r [:frames :all]))
        "tool frame appears in :all")
    (is (= [:cart :route :user] (get-in r [:app-db-top-keys :rf/default]))
        "per-app-frame top-keys, sorted")
    (is (not (contains? (:app-db-top-keys r) :rf/xray))
        "tool frame excluded from :app-db-top-keys")
    (is (= 2 (get-in r [:registry :counts :event])))
    (is (= (vec (sort [:cart/total :current-user])) (get-in r [:registry :subs]))
        "the sub-id vector is sorted (stable listing)")
    (is (= [:checkout] (:machines r)))))

(let [{:keys [fail error]} (run-tests 'orient-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
