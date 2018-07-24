(ns tau.alpha.on
  (:require-macros [tau.alpha.macros :refer [log on]])
  (:require [tau.alpha.io :refer [worker dist-ports connect kill-local]]
            [tau.alpha.call :refer [call]]
            [tau.alpha.util :refer [new-id on? if-str->obj get-worker-script read
                                    get-new-id write]]
            [tau.alpha.tau :refer [send-taus tau exec-tau]]
            [tau.alpha.state :refer [ports serve-handlers loaded? sabs
                                     screen on-screen? off-screen? conf
                                     add-handler add-listeners id]]))

(enable-console-print!)
(set! *print-fn-bodies* true)

(defn run-launch []
  (connect)
  (swap! loaded? (constantly true))
  (if-let [v (read (aget js/taupreload "args"))]
    (def init-val v))
  (if-let [afn (read (aget js/taupreload "fn"))]
    (def init-fn afn))
  (if (and init-fn init-val)
    (if (seqable? init-val)
      (apply call init-fn init-val)
      (call init-fn init-val))))

(declare exec-db)

(when (on-screen?)
  (def exec-db (exec-tau {})))

(defn ui-tauon
  ([value] (ui-tauon [value] nil nil))
  ([value afn tid]
   (when (on-screen?)
     (let [tid (if tid tid (str "f_" (new-id)))
           tid (if (string? tid) (read tid) tid)
           w (worker (get-worker-script nil (:main @conf) (write afn) value tid)
               @serve-handlers)
           main-pool (:main-pool @sabs)
           int32 (tau.alpha.tau/-get-int32a exec-db)
           t (tau.alpha.tau/-id exec-db)]
       (swap! ports assoc-in [tid :w] w)
       (dist-ports tid)
       (on tid [main-pool] (swap! sabs assoc :main-pool main-pool))
       (on tid (run-launch))
       (on tid [int32 t]
           (def exec-db
             (tau.alpha.tau/Tau. (:main-pool @sabs) int32 (str t) {:id (str t)} nil nil)))
       tid))))

(defn new-id-key []
  (let [nid (get-new-id)
        c (count nid)]
    (if (< c 32)
      (keyword nid)
      nid)))

(defn tauon
  ([] (tauon (new-id-key) nil nil))
  ([v] (tauon (new-id-key) nil v))
  ([afn v] (tauon (new-id-key) afn v))
  ([tid afn v]
   (let [tid (if tid tid (new-id-key))
         tid (if (string? tid) (read tid) tid)]
     (if (on-screen?)
       (ui-tauon v afn tid)
       (when @loaded?
         (on screen [afn v tid] (ui-tauon v afn tid))))
     tid)))

(defn kill [t]
  (on t (log "tauon killed") (kill-local)))

(add-listeners @serve-handlers)
