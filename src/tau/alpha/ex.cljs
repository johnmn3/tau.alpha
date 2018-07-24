(ns tau.alpha.ex
  (:require-macros [tau.alpha.macros :refer [log on future later]])
  (:require [figwheel.repl :as repl :refer [connect focus conns]]
            [tau.alpha.state :refer [set-conf! on-screen? repl-fn]]
            [tau.alpha.on :refer [tauon kill]]
            [tau.alpha.exec :refer [exec]]
            [tau.alpha.future :refer [yield future-call]]
            [tau.alpha.util :refer [sleep read]]
            [tau.alpha.tau :refer [Tau tau wait set-tau-validator! set-tau-error-handler!]]))

(enable-console-print!)

;; this namespace contains some examples of how to use tauons

(set-conf! {:main "tau.alpha.ex"
            :log? true})

(defn handle-response [event]
  (let [xhr (.-target event)]
    (if (= (.-readyState xhr) 4)
      (if (= (.-status xhr) 200)
        (let [res (.-responseText xhr)]
          (when res
            (yield res)))
        (yield (.-statusText xhr))))))


(defn xget [url]
  (later [url]
    (let [axhr (js/XMLHttpRequest.)]
      (.open axhr "GET" url)
      (set! (.-onreadystatechange axhr) handle-response)
      (.send axhr nil))))

(def x "x")

(def y 9)

(when (on-screen?)
  (def z {:one 1 :two 2}))


; const times = [];
; let fps;
;
; function refreshLoop() {}
;   window.requestAnimationFrame(() => {})
;     const now = performance.now();
;     while (times.length > 0 && times[0] <= now - 1000) {}
;       times.shift();
;
;     times.push(now);
;     fps = times.length;
;     refreshLoop();
;   ;
;
;
; refreshLoop();

(def raf
  (when (exists? js/requestAnimationFrame)
    js/requestAnimationFrame))

(defn fps-mon [times fps] ;(atom nil)
  (raf
    (fn []
      (let [now (js/performance.now)]
        (while (and (> (.-length @times) 0) (<= (aget @times 0) (- now 1000)))
          (swap! times #(do (.shift %) %)))
        (swap! times #(do (.push % now) %))
        (reset! fps (.-length @times))
        (fps-mon times fps)))))

(defn fps-atom []
  (when raf
    (let [times (atom #js [])
          fps (atom 0)]
      (fps-mon times fps)
      fps)))

(def fps (fps-atom))

(defn make-initial-conditions [width]
  (-> (take width (repeat 0))
      vec
      (assoc (int (/ width 2)) 1)))

(defn amake-initial-conditions [width]
  (let [w (* 4 width)]
    (-> (take (dec (* w width)) (flatten (repeat [0 0 0 255])))
        vec
        (assoc (int (/ w 2)) 255)
        (assoc (+ 1 (int (/ w 2))) 255)
        (assoc (+ 2 (int (/ w 2))) 255)
        (js/Uint8ClampedArray.))))


(def white (js/Uint8ClampedArray. [  0   0   0 255]))
(def black (js/Uint8ClampedArray. [255 255 255 255]))

(defn make-rule [v1 v2 v3 v4 v5 v6 v7 v8]
  { [1 1 1] v1
    [1 1 0] v2
    [1 0 1] v3
    [1 0 0] v4
    [0 1 1] v5
    [0 1 0] v6
    [0 0 1] v7
    [0 0 0] v8})

(defn make-arule [v1 v2 v3 v4 v5 v6 v7 v8]
  { [255 255 255] ({0 white 1 black} v1)
    [255 255   0] ({0 white 1 black} v2)
    [255   0 255] ({0 white 1 black} v3)
    [255   0   0] ({0 white 1 black} v4)
    [  0 255 255] ({0 white 1 black} v5)
    [  0 255   0] ({0 white 1 black} v6)
    [  0   0 255] ({0 white 1 black} v7)
    [  0   0   0] ({0 white 1 black} v8)})

(defn wrap-sub [the-state the-index the-width]
  (let [left-index (- (int (/ the-width 2)))
        right-index (+ the-width left-index)
        size (count the-state)]
    (->> (vec (range left-index right-index))
         (map #(nth the-state
                (rem (+ the-index size %) size)))
         (vec))))

(defn wrap-asub [the-state the-index]
  ;(println :wrap-asub (if the-state :state-present :state-nil))
  (let [size (.-length the-state)
        norm (+ the-index (* 4 size))]
    (->> [-3 0 4]
         (map #(aget the-state (rem (+ norm %) size)))
         (vec))))

(defn make-stepper [the-rule]
  (fn [the-state]
    (->> (vec (range (count the-state)))
         (map #(the-rule (wrap-sub the-state % 3)))
         (vec))))

(defn make-astepper [the-rule width]
  (fn [state]
    ;(println :stepper (if state :state-present :state-nil))
    (let [width (dec (* 4 width))
          x (- (.-length state) width 4)]
      (loop [the-state state i 0]
        (when (< i x)
          (let [pre (wrap-asub the-state i)
                res (the-rule pre)]
            (.set the-state res (+ width 1 i))
            (recur the-state (+ i 4))))))
    (let [sl (.slice state 16128 16383)]
      (.set state sl 0))
    (js/Uint8ClampedArray. state)))

(defn run-steps [the-steps the-stepper the-state]
  (take the-steps (iterate the-stepper the-state)))

(def rule30 (make-rule 0 0 0 1 1 1 1 0))

(def arule30 (make-arule 0 0 0 1 1 1 1 0))

(def rule-30-stepper (make-stepper rule30))

(def rule-30-astepper (make-astepper arule30 64))

(def aic (amake-initial-conditions 64))

(defn rasterize
  "From [[0 1 0] [1 1 1] ...] to [[x y pixel] ...]"
  [steps width lines]
  (map (fn [[x y] pixel] [x y pixel])
       (for [y (range steps)
             x (range width)]
         [x y])
       (apply concat lines)))


(defn get-frame [a] (run-steps 64 rule-30-stepper a))

(defn get-aframe [a] (rule-30-astepper a))

(defn fill [d a cw ch]
  (loop [y 0 x 0]
    (let [x (mod x (inc cw))
          i (* 4 (+ x (* y cw)))
          r (nth (nth a y) x)
          v (if (= 0 r) 0 255)]
      (aset d i v)
      (aset d (inc i) v)
      (aset d (+ 2 i) v)
      (aset d (+ 3 i) 255)
      (when-not (and (= y (dec ch)) (= x (dec cw)))
        (if-not (= x (dec cw))
          (recur y (inc x))
          (recur (inc y) 0))))))

(defn actx [astr]
  (let [acanvas (.getElementById js/document astr)
        ctx (.getContext acanvas "2d")
        w (.-width acanvas)
        h (.-height acanvas)
        idata (.getImageData ctx 0 0 w h)
        data {:canvas acanvas
              :ctx ctx
              :width w
              :height h
              :image-data idata}]
    data))

(defn start [on? tail ctx width height]
  (when @on?
    (raf
      #(let [_ (swap! tail get-aframe)
             idata (.createImageData ctx width height)
  ;           ui8ca @tail
             _ (.set (.-data idata) @tail)]
         (.putImageData ctx idata 0 0 0 0 width height)
         (start on? tail ctx width height)))))

(defn on-click [el afn]
  (.addEventListener el "click" afn))

(defn setup [ctx width]
  (let [tail (atom (amake-initial-conditions width))
        on? (atom false)]
    (set! (.-fillColor ctx) "#ffffff")
    {:tail tail :on? on?}))

(defn hookup [astr]
  (let [{:keys [ctx width height canvas]} (actx astr)
        {:keys [tail on?]} (setup ctx width)]
    (on-click canvas
              #(do (swap! on? not)
                 (start on? tail ctx width height)))))

(when (on-screen?)
  (hookup "a1")
  (hookup "b1")
  (hookup "c1")
  (hookup "d1")
  (hookup "e1")
  (hookup "f1"))

(def initial-conditions (make-initial-conditions 64))


(when (on-screen?)
  (def fps-div (.getElementById js/document "fps"))

  (add-watch fps :fps
    (fn [k a o n]
      (set! (.-innerHTML fps-div) (str "<p>" n "</p>")))))


(defn get-data [acanvas]
  (let [w (.-width acanvas)
        h (.-height acanvas)]
    (-> (.getContext acanvas "2d")
        (.getImageData 0 0 w h))))

(defn local-loop [d t w h]
  (loop [y 0 x 0]
    (let [x (mod x (inc w))
          i (* 4 (+ x (* y w)))
          r (nth (nth t y) x)
          v (if (= 0 r) 0 255)]
      (aset d i v)
      (aset d (inc i) v)
      (aset d (+ 2 i) v)
      (aset d (+ 3 i) 255)
      (when-not (and (= y (dec h)) (= x (dec w)))
        (if-not (= x (dec w))
          (recur y (inc x))
          (recur (inc y) 0))))))

(def local-store (atom {}))

(defn process-results [lid]
  (raf
    #(let [{:keys [tau ctx width height tau trigger]} (get @local-store lid)
           idata (.createImageData ctx width height)]
      (.set (.-data idata) @tau)
      (.putImageData ctx idata 0 0 0 0 width height)
      (raf 
        (fn []
          (swap! trigger (constantly nil)))))))


(defn send-work [tauon2 tau lid]
  (on tauon2 {:error-fn println} [tau lid]
    ;(println "tau:" (type @tau))
    (swap! tau get-aframe)
    (on "screen" {:error-fn println} [lid]
        (process-results lid))
    #_
    (when @tau
      (swap! tau get-aframe)
      (on "screen" {:error-fn println} [lid]
        (process-results lid)))))

(defn run-loop-once [on? lid]
  (when @on?
    (raf
      #(let [{:keys [tau tauon data ctx width height]} (get @local-store lid)]
         ;(reset! data (.getImageData ctx 0 0 width height))
         (send-work tauon tau lid)))))

(defn setupb [{:keys [ctx width] :as aut}]
  (let [lid (keyword (gensym 'lid-))
        atauon (tauon)
        init (amake-initial-conditions width)
        tau (tau init :ab true :size 1000000)]
    (swap! local-store assoc lid
      (merge aut
        {:tau tau
         :on? (atom false) :trigger (atom nil) :data (atom nil)
         :tauon atauon}))
    (set! (.-fillColor ctx) "#ffffff")
    lid))

(defn hookupb [astr]
  (let [{:keys [canvas] :as aut} (actx astr)
        lid (setupb aut)
        {:keys [on? trigger tau]} (get @local-store lid)]
    (on-click canvas
              #(when @tau
                 (swap! on? not)
                 (run-loop-once on? lid)))
    (add-watch trigger lid (fn [k a o n] (run-loop-once on? lid)))))

(when (on-screen?)
  (hookupb "a2")
  (hookupb "b2")
  (hookupb "c2")
  (hookupb "d2")
  (hookupb "e2")
  (hookupb "f2")


; (def b2
;   (when (exists? js/document)
;     (.getElementById js/document "b2")))
; (when (on-screen?)
;   (def tauonb2 (tauon)))
; (def autb2 (atom (get-frame initial-conditions)))
; (def lb2 (atom (last @autb2)))
; (when (on-screen?)
;   (def taub2 (tau nil)))
; (def datab2 (atom nil))
; (defn do-loopb2 [cw ch]
;   (let [d (.-data @datab2)
;         t @taub2]
;     (local-loop d t cw ch)
;     (.putImageData (.getContext b2 "2d") @datab2 0 0 0 0 cw ch)
;     (reset! lb2 (last t))))
; (defn fillb2 [idata last-line cw ch]
;   (swap! datab2 (constantly idata))
;   (swap! taub2 (constantly last-line))
;   (on tauonb2 {:error-fn println} [taub2 cw ch]
;     (let [res (get-frame @taub2)]
;       (swap! taub2 (constantly res))
;       (on "screen" {:error-fn println} [cw ch]
;         (do-loopb2 cw ch)))))
; (when (on-screen?)
;     (def b2-on? (atom false))
;     (defn start-b2 []
;       (when @b2-on?
;         (.requestAnimationFrame js/window
;           #(let [ctx (.getContext b2 "2d")
;                  cw (.-width b2)
;                  ch (.-height b2)
;                  idata (get-data b2)]
;              (set! (.-fillColor ctx) "#ffffff")
;              (fillb2 idata @lb2 cw ch)))))
;     (.addEventListener b2 "click" #(do (swap! b2-on? not) (start-b2)))
;     (add-watch lb2 :lb2
;       (fn [k a o n]
;         (when @b2-on?
;           (.requestAnimationFrame js/window
;             #(let [ctx (.getContext b2 "2d")
;                    cw (.-width b2)
;                    ch (.-height b2)
;                    idata (get-data b2)]
;                (set! (.-fillColor ctx) "#ffffff")
;                (fillb2 idata @lb2 cw ch))))))







  ; (def aut2 (tau (get-frame initial-conditions)))
  ; (def l2 (tau (last @aut2)))





  :end)

; (def rule90 (make-rule 0 1 0 1 1 0 1 0))
;
; (def rule-90-stepper
;   (make-stepper rule90))
;
; (def rule250 (make-rule 1 1 1 1 1 0 1 0))
;
; (def rule-250-stepper
;   (make-stepper rule250))

(comment

  (ns tau.alpha.ex)

  (def t (tauon))

  t

  (on t (println "hi" x y))

  (on t
    (def result (assoc z :y y))
    (println result))

  (on t [z]
    (def working-result (assoc z :y y :x x))
    (println working-result))

  (on t (println js/document))

  (on t {:error-fn #(println "oops!" %)} (println js/document))

  (log "hi")

  (log :ret "hi")

  (log :ret "hi" "there")

  (on t (log "<- look, tauon id, namespace and line number!!!"))

  (def t2 (tauon 1))

  (on t2 (log "init value was:" tau.alpha.on/init-val))

  (def t3 (tauon #(log "init result was:" (inc %)) 1))

  (def t4 (tauon "t4" #(log "fancy business with" %) :fancy/value))

  (on "t4" (log "But patches are welcome!"))

  (deft t5 []
    (def one 1)
    (def two 2)
    (log "finished launching with result:" (+ one two)))

  (deft t6 [z]
    (def my-map (atom {:original-value 100}))
    (swap! my-map merge z)
    (log "t6 is ready:" @my-map))

  t2

  (on t [z]
    (let [q 1]
      (on :tau.alpha.on/t1ne3Ug5kRYOrnvtATlH99Q [z q]             ; on t2
        (log "result:" (assoc z :q q)))))

  (kill t)
  (kill t2)
  (kill t3)
  (kill t4)
  (kill t5)
  (kill t6)

  :tau/end)
