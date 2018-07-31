(ns tau.alpha.tau
  (:require-macros [tau.alpha.macros :refer [log on]])
  (:require
    [tau.alpha.state :refer [id on-screen? sabs]]
    [tau.alpha.call :refer [call]]
    [tau.alpha.util :refer [get-new-id read enc unc typed-array?]]
    [cljs.reader :refer [register-tag-parser!]]
    [cljs.pprint :refer [pprint]]))

(enable-console-print!)
(set! *print-fn-bodies* true)

(def *num-taus* 25)
(def *tau-size* 10000)

(def unlocked 0)

(defonce db (atom {}))

(defn ace [iab idx ov nv]
  (js/Atomics.compareExchange iab idx ov nv))

(defn await [iab idx v n]
  (js/Atomics.wait iab idx v n))

(defn astore [iab idx n]
  (js/Atomics.store iab idx n))

(defn awake [iab idx n]
  (js/Atomics.wake iab idx n))

(defn aload [iab idx]
  (js/Atomics.load iab idx))

(def latch-token (hash id))

(defn latchm [{:keys [iab idx]}]
  (let [c (ace iab idx unlocked latch-token)]
    (if (not= c unlocked)
      (loop [ac c]
        (let [x (ace iab idx unlocked latch-token)]
          (if (not= x unlocked)
            (await iab idx ac js/Number.POSITIVE_INFINITY))
          (let [acc (ace iab idx unlocked latch-token)]
            (if (not= acc unlocked)
              (recur acc))))))))

(defn latchr
  ([iab idx] (latchr iab idx 10000))
  ([iab idx n]
   (if (<= n 0)
     (let [c (ace iab idx unlocked latch-token)]
       (if (not= c unlocked)
         (latchr iab idx (dec n))))
     (let [c (ace iab idx unlocked latch-token)]
       (when (not= c unlocked)
         (await iab idx c js/Number.POSITIVE_INFINITY)
         (latchr iab idx))))))

(defn unlatchm [{:keys [iab idx]}]
  (when (= latch-token (aload iab 0))
    (astore iab idx unlocked)
    (awake iab idx js/Number.POSITIVE_INFINITY)))

(defprotocol IDable
  "Protocol for adding for adding functionality for getting the ID of an object."
  (-id [o]))

(defprotocol ISharedAtom
  "Protocol for functionality shared atoms"
  (-get-sab [o])
  (-get-int32a [o])
  (-get-ab [o])
  (-get-lock [o])
  (-locked? [o])
  (-set [o a])
  (-get-watches [o])
  (-set-validator! [o f])
  (-get-validator [o])
  (-set-error-handler! [o f])
  (-get-error-handler [o])
  (-blocking-deref [o]))

(defn latch [o]
  (if (instance? js/SharedArrayBuffer (.-buffer o))
    (latchr o 0)
    (latchr (-get-int32a o) 0)))

(defn unlatch [o]
  (if (instance? js/SharedArrayBuffer (.-buffer o))
    (unlatchm {:iab o :idx 0})
    (unlatchm {:iab (-get-int32a o) :idx 0})))

(defn get-tau-error-handler [o]
  (-get-error-handler o))

(defn wake [o]
  (let [ia (-get-int32a o)]
    (js/Atomics.store ia 1 1)
    (js/Atomics.wake ia 1 js/Number.POSITIVE_INFINITY)
    (js/Atomics.store ia 1 0)))

(defn block [o]
  (let [ia (-get-int32a o)]
    (js/Atomics.store ia 1 0)
    (js/Atomics.wait ia 1 0)
    (js/Atomics.load ia 1)))

(defn with-latch [o ia afn]
  (latch ia)
  (try (afn)
    (catch :default e
      (if-let [efn (get-tau-error-handler o)]
        (call efn e)
        (throw e)))
    (finally (do (unlatch ia) (wake o)))))

(defn get-tau-validator [o]
  (-get-validator o))

(defn enc-ab [ar s]
  ; (println :enc-ab (if ar :got-ar :no-ar))
  ; (println :enc-ab (if s :got-s :no-s))
  (try
    (let [new-length (.-length s)
          old-index (js/Atomics.load ar 2)
          new-index (if (= 0 old-index) (int (/ (.-length ar) 2)) 0)
          write-point (if (= 0 new-index) 4 1)]
      (doall
       (map-indexed
        #(aset ar (+ %1 write-point new-index) %2)
        (array-seq s)))
      (js/Atomics.store ar (+ (dec write-point) new-index) new-length)
      (js/Atomics.store ar 2 new-index)
      ar)
    (catch :default e (println "enc-ab failed"))))

(defn unc-ab [a]
  #_(println "unc-ab")
  (try
    (let [;_ (println :type (type a))
          i (js/Atomics.load a 2)
          read-point ({0 4} i 1)
          l (js/Atomics.load a (+ (dec read-point) i))
          #_ (println "unc-ab, i:" i "read-point:" read-point "l:" l)
          ;ca (.slice a (+ i read-point -1))
          ca (js/Int32Array. (.slice a (+ i read-point) (+ i read-point l)) #_
              (to-array (take l (drop (+ i read-point) (array-seq a))))) #_#_
          ca (js/Int32Array.
              (to-array (take l (drop (+ i read-point) (array-seq a)))))]
      ca)
    (catch :default e
      (do (println "failed on unc-ab")
          (println "error:" e)))))

(defn update-val-with-latch [meta o ia f & args]
  (with-latch o
    ia
    #(if (:ab meta)
       (let [ov (unc-ab ia)
             r (apply f ov args)]
         (enc-ab ia r)
         r)
       (let [ov (unc ia)
             v (:val ov)
             r (apply f v args)]
         (if-let [validator (get-tau-validator o)]
           (if (call validator r)
             (do
               (enc ia (assoc ov :val r))
               (-notify-watches o ov r))
             (throw (js/Error. (str "Validator failed for value: " r))))
           (do
             (enc ia (assoc ov :val r))
             (-notify-watches o ov r)))
         r))))

(defn add-watch-with-latch [o ia k f]
  (with-latch o ia #(enc ia (assoc-in (unc ia) [:watches k] (pr-str f)))) o)

(defn remove-watch-with-latch [o ia k]
  (with-latch o ia #(enc ia (update (unc ia) :watches dissoc k))) o)

(defn set-validator-with-latch [o ia f]
  (with-latch o ia #(enc ia (assoc (unc ia) :validator (pr-str f)))) o)

(defn set-tau-validator! [t f]
  (-set-validator! t f))

(defn set-error-handler-with-latch [o ia f]
  (with-latch o ia #(enc ia (assoc (unc ia) :error-handler (pr-str f)))) o)

(defn set-tau-error-handler! [t f]
  (-set-error-handler! t f))

(deftype Tau [sab int32a oid meta validator watches]
  Object
  (equiv [this other] (-equiv this other))
  (toString [_]
    (if (.startsWith (:id meta) "#Tau")
      (let [n (apply str (drop-last (drop 15 (:id meta))))]
        (str "#Tau {:id " n "}"))
      (str "#Tau {:id " (:id meta) "}")))

  IPrintWithWriter
  (-pr-writer [o writer opts] (-write writer (str o)))

  ISharedAtom
  (-get-sab [o] sab)
  (-get-int32a [o] int32a)
  (-get-ab [o] int32a)
  (-get-lock [o] int32a)
  (-locked? [o] (not= 0 (aload int32a 0)))
  (-set [o a]
       (cond
        (= (:type meta) :ab) (enc-ab int32a a)
        :else (enc int32a a))
      a)
  (-get-watches [o] (:watches (unc int32a)))
  (-set-error-handler! [o f] (set-error-handler-with-latch o int32a f))
  (-get-error-handler [o] (:error-handler (unc int32a)))
  (-set-validator! [o f] (set-validator-with-latch o int32a f))
  (-get-validator [o] (:validator (unc int32a)))
  (-blocking-deref [o]
    (block o)
    (cond
     (:ab meta) (unc-ab (-get-int32a o))
     :else (:val (unc (-get-int32a o)))))



  ISwap
  (-swap! [o f] (update-val-with-latch meta o int32a f))
  (-swap! [o f a] (update-val-with-latch meta o int32a f a))
  (-swap! [o f a b] (update-val-with-latch meta o int32a f a b))
  (-swap! [o f a b xs] (apply update-val-with-latch meta o int32a f a b xs))

  IReset
  (-reset! [o nv] (update-val-with-latch meta o int32a (fn [_] nv)))

  IDable
  (-id [o] oid)

  IEquiv
  (-equiv [o other] (identical? o other))

  IDeref
  (-deref [o]
    (cond
     (:ab meta) (unc-ab (-get-int32a o))
     :else (:val (unc (-get-int32a o)))))

  IMeta
  (-meta [o] meta)

  IWatchable
  (-notify-watches [o old-val new-val]
    (if-let [watches (-get-watches o)]
      (doseq [[key f] watches]
        (call f key o old-val new-val))))
  (-add-watch [o key f]
    (add-watch-with-latch o int32a key f)
    o)
  (-remove-watch [o key]
    (remove-watch-with-latch o int32a key))

  IHash
  (-hash [this] (goog/getUid this)))

(defn wait [o]
  (-blocking-deref o))

(defn notify-watches [o ov nv]
  (-notify-watches o ov nv))

(defn swap [o f & a]
  (apply -swap! o f a))

(defn get-next-ctr
  ([sab]
   (let [ia (js/Int32Array. sab 0 1)
         n (aload ia 0)
         old-n n
         new-n (if (< (inc old-n) *num-taus*)
                 (inc old-n)
                 2)]
     (ace ia 0 old-n new-n)
     new-n)))

(defn get-next-ia-idx [n]
  (* n 2 4 *tau-size*))

(defn construct-tau [sab nid]
  (println "constructing tau:" nid)
  (let [tid (read nid)
        n1 (nth (name tid) 2)]
    (if (= n1 "_")
      (let [ab? (= "_" (nth (name tid) 3))
            size (/ (.-byteLength sab) 4)
            ia (js/Int32Array. sab 0 size)
            meta {:id (str tid) :size size :ab ab?}
            t (Tau. sab ia (str tid) meta nil nil)]
        (swap! db assoc (str t) t)
        t)
      (let [n2 (nth (name tid) 3)
            n (read (if (= n1 "0") n2 (str n1 n2)))
            idx (get-next-ia-idx n)
            sab (:main-pool @sabs)
            ia (js/Int32Array. sab idx (* 2 *tau-size*))
            t (Tau. sab ia (str tid) {:id (str tid)} nil nil)]
        (swap! db assoc (str t) t)
        t))))

;; breaking under advanced compile. reader not recognizing "#Tau {:id ...}". says "no dispatch macro for T."
(defn receive-tau [sab nid]
  (let [tau-id (str "#Tau {:id " nid "}")
        atau (construct-tau sab nid)]
    (swap! db assoc tau-id atau)))

(defn send-tau [port sab nid]
  (if (on-screen?)
    (on port [sab nid] {:error-fn println}
        (receive-tau sab nid))
    (on "screen" [sab port nid] {:error-fn println}
        (on port [sab nid] {:error-fn println}
            (receive-tau sab nid)))))

(defn send-tau-to-screen [sab nid]
  (on "screen" [sab nid] {:error-fn println}
      (swap! db assoc
             (str "#Tau {:id " nid "}")
             (construct-tau sab nid))))

(defn dist-tau [t]
  (when (not (on-screen?))
    (send-tau-to-screen (-get-sab t) (-id t)))
  (doall
    (map #(send-tau % (-get-sab t) (-id t))
          (keys @tau.alpha.state/ports))))

(defn send-taus [port]
  (let [ts (vals @db)]
    (doall
      (for [t ts
            :let [s (-get-sab t)
                  i (-id t)]]
        (send-tau port s i)))))

(swap! tau.alpha.state/new-port-fns conj send-taus)

(when (on-screen?)
  (swap! sabs assoc :main-pool
         (js/SharedArrayBuffer. (+ 4 (* 2 4 *num-taus* *tau-size*))))
  (let [s (js/Int32Array. (:main-pool @sabs) 0 1)]
    (astore s 0 0)))


(defn reconstruct-tau [s]
  (println "reconstructing-tau:" s)
  (let [tid (:id s)
        n1 (nth (name tid) 2)]
    (if (= n1 "_")
      (get @db (str tid))
      (let [n2 (nth (name tid) 3)
            n (read (if (= n1 "0") n2 (str n1 n2)))
            idx (get-next-ia-idx n)
            sab (:main-pool @sabs)
            ia (js/Int32Array. sab idx (* 2 *tau-size*))
            t (Tau. sab ia (str tid) {:id (str tid)} nil nil)]
        (swap! db assoc (str t) t)
        t))))

(register-tag-parser!
  'Tau (fn [x] #_(println "reading tau")
          (or (get @db (str "#Tau " x))
              (reconstruct-tau x))))

(defn exec-tau
  ([] (exec-tau nil))
  ([state] (exec-tau state *tau-size*))
  ([state n] (exec-tau nil state n))
  ([tid state n]
   (let [sab (:main-pool @sabs) #_ (js/SharedArrayBuffer. (* 2 4 (or n *tau-size*)))
         n (get-next-ctr sab)
         n (if (< n 10) (str "0" n) (str n))
         ia (js/Int32Array. sab (get-next-ia-idx n) (* 2 *tau-size*))
         tid (str ":tau/on" n "" (or tid (get-new-id)))
         t (Tau. sab ia (str tid) {:id (str tid)} nil nil)]
     (swap! db assoc (str t) t)
     (enc ia {:val state :watches nil :validator nil :error-handler nil})
     t)))

(defn tau
  ([] (tau nil))
  ([state] (tau state {}))
  ([state & options]
   (let [{:keys [meta executor tid size ab]
          :or {tid (get-new-id)
               size *tau-size*
               ab false}}
         (apply hash-map options)]
     (if executor
       (apply exec-tau tid state size)
       (let [sab (js/SharedArrayBuffer. (* 2 4 size))
             a (js/Int32Array. sab 0 (* 2 size))
             tau-type (if ab "_" "*")
             tid (str ":tau/on_" tau-type (or tid (get-new-id)))
             t (Tau. sab a tid
                     (merge meta {:id tid :size size :ab ab})
                     nil nil)]
         (swap! db assoc (str t) t)
         (if (or (array? state) (typed-array? state))
           (enc-ab a state)
           (enc a {:val state :watches nil :validator nil :error-handler nil}))
         (dist-tau t)
         t)))))
