(ns tau.alpha.exec
  (:require-macros [tau.alpha.macros :refer [log on future]])
  (:require [tau.alpha.state :refer [on-screen? id ports default-executor]]
            [tau.alpha.util :refer [get-new-id read sleep]]
            [tau.alpha.call :refer [call respile-args]]
            [tau.alpha.tau :refer [wait]]
            [tau.alpha.on :refer [tauon exec-db]]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :as walk]))

(enable-console-print!)
(set! *print-fn-bodies* true)

(defn num-cores []
  (.-hardwareConcurrency js/self.navigator))

(defn mk-pool [pid]
  (swap! exec-db assoc pid
         {:val nil
          :ready {}
          :pending {}
          :pq #queue []})
  pid)

(defn queue [form] (into cljs.core.PersistentQueue.EMPTY form))

(defn popq! [pid]
  (let [q (queue (get-in @exec-db [pid :pq]))
        m (peek q)]
    (swap! exec-db update-in [pid :pq] #(pop q)) m))

(defn next-tauon [pid]
  (let [i (ffirst (get-in @exec-db [pid :ready]))
        _ (swap! exec-db update-in [pid :ready] dissoc i)
        _ (swap! exec-db update-in [pid :pending] assoc i i)]
    i))

(defn unpend [pid k]
  (assert (not (nil? k)))
  (assert (get-in @exec-db [pid :pending k]))
  (let [i (get-in @exec-db [pid :pending k])
        _ (swap! exec-db update-in [pid :pending] dissoc i)
        _ (swap! exec-db update-in [pid :ready] assoc i i)]
    i))

(defn mk-fixed-thread-pool [pid n]
  (on "screen" [pid n]
      (let [pid (mk-pool pid)
            pts (doall (map #(tauon 1) (range n)))]
        (swap! exec-db assoc-in [pid :ready] (zipmap pts pts))))
  pid)

(defn enqueue [pid m]
  (swap! exec-db update-in [pid :pq] (comp queue conj) m))

(declare run-q)

(def tau-afn (atom nil))

(def tau-tau (atom nil))

(defn submit-action [pid local-id atauon atau conveyer]
  (on atauon {:error-fn #(println %)} [atau pid local-id conveyer]
    (let [afn (:afn @atau)
          _ (reset! tau-tau atau)
          _ (reset! tau-afn afn)
          conveyed (respile-args conveyer)
          res (when afn
                (try (apply call afn conveyed)
                     (catch :default e (println "error:" e))))]
      res))
  (unpend pid atauon)
  (specify! atau
    IDeref
    (-deref [this]
      (let [{:keys [completed? result]} (:val (tau.alpha.util/unc (tau.alpha.tau/-get-int32a atau)))]
        (if (on-screen?)
          result
          (if completed?
            result
            (:result (wait atau))))))))

(declare Executor)

(defn run-q [pid]
  (if-let [{:keys [atau afn conveyer]} (peek (queue (get-in @exec-db [pid :pq])))]
    (if-let [t (next-tauon pid)]
      (let [_ (popq! pid)
            local-id (if (keyword? id)
                       id
                       (if (.startsWith id ":")
                         (read id)
                         (keyword id)))
            local-id (str id)
            pid (if (instance? Executor pid) (:id (meta pid)) pid)]
        (submit-action pid local-id t atau conveyer)))))

(defn local-submit [pid afn conveyer]
  (let [pid (if (instance? Executor pid) (:id (meta pid)) pid)
        atau (tau.alpha.tau/exec-tau {:completed? false :afn (pr-str afn) :result nil})]
    (enqueue pid {:atau atau :afn afn :conveyer conveyer})
    (sleep 20)
    (let [res (run-q pid)]
      res)))

(defn mk-executor [oid state opts]
  (let [thread-pool (mk-fixed-thread-pool oid (:num-threads opts))]
    (swap! exec-db assoc-in [thread-pool :val] state)
    (swap! exec-db update oid merge opts)
    thread-pool))

(defn time-trip [f]
  (let [t (.getTime (js/Date.))] (on f [t] (log (- (.getTime (js/Date.)) t)))))

(defprotocol IExecutor
  "Marker protocol indicating an atom shared between web workers."
  (-num-threads [n]
                "Returns the number of threads in the executor thread pool."))

(defprotocol IDable
  "Protocol for adding for adding functionality for getting the ID of an object."
  (-id [o]))

(defn oid [a] (if (keyword? a) a (-id a)))

(declare submit)

(deftype Executor [state oid meta validator watches num-threads]
  Object
  (equiv [this other] (-equiv this other))
  (toString [_] (str "#Executor " (:id meta)))

  IExecutor
  (-num-threads [o] (get-in @exec-db [(:id meta) :meta]))

  IPrintWithWriter
  (-pr-writer [o writer opts] (-write writer (str o)))

  IDable
  (-id [o] (:id meta))

  IEquiv
  (-equiv [o other] (identical? o other))

  IDeref
  (-deref [o] (get-in @exec-db [(:id meta) :val]))

  IMeta
  (-meta [o] (get-in @exec-db [(:id meta) :meta]))

  IWatchable
  (-notify-watches [o old-val new-val]
    (if-let [watches (get-in @exec-db [(:id meta) :watches])]
      (doseq [[key f] watches]
        (f key o old-val new-val))))
  (-add-watch [o key f] (swap! exec-db assoc-in [o :watches key] f) o)
  (-remove-watch [o key] (swap! exec-db update-in [o :watches] dissoc key))

  IHash
  (-hash [this] (goog/getUid this)))

(defn new-id-key []
  (keyword "tau.alpha.exec" (get-new-id)))

(defn num-threads [o] (-num-threads o))

(defn executor-object-facade
  ([x & {:keys [meta validator num-threads]}]
   (Executor. x (:id meta) meta validator nil num-threads)))

(defn exec [state & options]
  (let [opts (apply hash-map options)
        num-threads (if-let [n (:num-threads opts)] n (+ 1 (num-cores)))
        oid (if-let [i (:id (:meta opts))] i (new-id-key))
        meta (if-let [m (:meta opts)] m {:id oid})
        opts (merge opts {:meta meta :num-threads num-threads})
        o (executor-object-facade state opts)]
    (if-let [m (:meta opts)] (set! (.-meta o) m))
    (if-let [t (:num-threads opts)] (set! (.-num-threads o) t))
    (mk-executor oid state opts)
    o))

(defn init-default-executor []
  (if-not (:default-executor @exec-db) #_ @default-executor
    (do (js/setTimeout #(exec {} :meta {:id :default-executor}) 100))))

(when (on-screen?)
  #_
  (init-default-executor))
