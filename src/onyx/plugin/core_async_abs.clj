(ns onyx.plugin.core-async-abs
  (:require [clojure.core.async :refer [poll! timeout chan alts!! >!!]]
            [clojure.core.async.impl.protocols :refer [closed?]]
            [clojure.set :refer [join]]
            [onyx.plugin.simple-input :as i]
            [onyx.plugin.simple-output :as o]))

(defrecord AbsCoreAsyncReader [event channel]
  i/SimpleInput

  (start [this]
    (assoc this
           :channel channel :processing []
           :segment nil :checkpoint 0 :offset 0))

  (stop [this]
    (dissoc this :processing :segment))

  (checkpoint [{:keys [checkpoint]}]
    checkpoint)

  (recover [this checkpoint]
    this)

  (offset-id [{:keys [offset]}]
    offset)

  (segment [{:keys [segment]}]
    segment)

  (next-state [{:keys [channel processing segment offset] :as this}
               {:keys [core.async/chan] :as event}]
    (let [segment (poll! chan)]
      (assoc this
             :channel chan
             :segment segment
             :processing (if segment (conj processing segment) processing)
             :offset (if segment (inc offset) offset)
             :closed? (closed? chan))))

  (ack-barrier [{:keys [processing checkpoint] :as this} barrier-id]
    (let [drop-index (- barrier-id checkpoint)]
      (assoc this :checkpoint barrier-id :processing (drop drop-index processing))))

  (segment-complete! [{:keys [conn]} segment])

  (completed? [this]
    (and (closed? (:channel this)) (not (seq (:processing this))))))

(defrecord AbsCoreAsyncWriter [event]
  o/SimpleOutput

  (start [this])

  (stop [this])

  (write-batch
    [_ {:keys [onyx.core/results core.async/chan] :as event}]
    (doseq [msg (mapcat :leaves (:tree results))]
      (>!! chan (:message msg)))
    {})

  (seal-resource
    [_ {:keys [core.async/chan]}]
    (>!! chan :done)))

(defn input [event]
  (map->AbsCoreAsyncReader {:event event}))

(defn output [event]
  (map->AbsCoreAsyncWriter {:event event}))

(defn take-segments!
  "Takes segments off the channel until :done is found.
   Returns a seq of segments, including :done."
  ([ch] (take-segments! ch nil))
  ([ch timeout-ms]
   (when-let [tmt (if timeout-ms
                    (timeout timeout-ms)
                    (chan))]
     (loop [ret []]
       (let [[v c] (alts!! [ch tmt] :priority true)]
         (if (= c tmt)
           ret
           (if (and v (not= v :done))
             (recur (conj ret v))
             (conj ret :done))))))))

(def channels (atom {}))

(def default-channel-size 1000)

(defn get-channel
  ([id] (get-channel id default-channel-size))
  ([id size]
   (if-let [id (get @channels id)]
     id
     (do (swap! channels assoc id (chan size))
         (get-channel id)))))

(defn inject-in-ch
  [_ lifecycle]
  {:core.async/chan (get-channel (:core.async/id lifecycle))})
(defn inject-out-ch
  [_ lifecycle]
  {:core.async/chan (get-channel (:core.async/id lifecycle))})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn get-core-async-channels
  [{:keys [catalog lifecycles]}]
  (let [lifecycle-catalog-join (join catalog lifecycles {:onyx/name :lifecycle/task})]
    (reduce (fn [acc item]
              (assoc acc
                     (:onyx/name item)
                     (get-channel (:core.async/id item)))) {} (filter :core.async/id lifecycle-catalog-join))))