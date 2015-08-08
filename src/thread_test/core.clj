(ns thread-test.core
  "Entry point for thread-test"
  (:require
    [hal9002
     [core :as hal]
     [zookeeper :as zk]]
    [clj-uuid :as uuid]
    [schema.core :as s]
    [compojure
     [core :refer [routes]]
     [route :as route]]
    [clojure.core.async :as a]
    [dilepis.core :as d]
    [themis.core :as timer]
    [thread-test.domain.properties :as props]
    [overtone.at-at :as scheduler]
    [zinn.log :as log])
  (:import [java.util.concurrent TimeUnit]
    [java.lang.System]
    [java.lang.Exception])
  (:gen-class))

(def timer-pool (scheduler/mk-pool))

(defn task-name
  [table-name]
  (format "%s thread-test worker" table-name))


; 30s interval for processing
(def default-processing-interval (* 30 1000))

(def file-regex-map
  {:duration
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_duration_.*csv"
   :groups
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_groups_.*csv"
   :groups_by_member
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_groups-by-member_.*csv"
   :queues
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_queues_.*csv"
   :skills
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_skills_.*csv"
   :skills_by_user
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_skills-by-user_.*csv"
   :tenants
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_tenants_.*csv"
   :tenants_by_user
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_tenants-by-user_.*csv"
   :users
   #"^[0-9a-z]{4}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_users_.*csv"
   })

(defn do-stuff
  "Gutted work fn"
  [key-val]
  (try
  (let [table-name (-> key-val key name)]
    (prn "do-stuff " table-name " " (.getId (Thread/currentThread)))
    (Thread/sleep 20000)
    (prn "done"))
  (catch InterruptedException e
    (prn (.toString (Thread/currentThread)) " got interrupted! " e))))
;; TODO FIXME retry anything that fails above

; This seemed to work with our simple jobs but is not working for the real work
; in historicalloader that we need it for
(defn stop-workers
  "Gracefully stop all of the pool's scheduled jobs"
  []
  (log/debug "Got shutdown request. Stopping workers.")
  (scheduler/show-schedule timer-pool)
  (prn "Before stop.")
  (prn timer-pool)
  ; Allow running tasks to finish but don't start any more
  (.setContinueExistingPeriodicTasksAfterShutdownPolicy
    (:thread-pool @(:pool-atom timer-pool)) false)
  ; Shutdown the ScheduledThreadPoolExecutor
  (.shutdown (:thread-pool @(:pool-atom timer-pool)))
  (prn "after shutdown, remaining jobs are " (scheduler/scheduled-jobs timer-pool))
  (prn "isShutdown?? " (.isShutdown (:thread-pool @(:pool-atom timer-pool))))
  (scheduler/show-schedule timer-pool)
  #_(.setKeepAliveTime (:thread-pool @(:pool-atom timer-pool)) 60 TimeUnit/SECONDS)
  #_(prn "Allows timeout ? " (.allowCoreThreadTimeOut (:thread-pool @(:pool-atom timer-pool)) true))
  (prn "Awaiting termination. Task count = " (.getTaskCount (:thread-pool @(:pool-atom timer-pool)))
    " Completed count = " (.getCompletedTaskCount (:thread-pool @(:pool-atom timer-pool))))
  (while (false?(.awaitTermination (:thread-pool @(:pool-atom timer-pool)) 60 TimeUnit/SECONDS))
    (prn "In loop. Task count = " (.getTaskCount (:thread-pool @(:pool-atom timer-pool)))
      " Completed count = " (.getCompletedTaskCount (:thread-pool @(:pool-atom timer-pool)))))
  (prn "***After timeout: isTerminated? " (.isTerminated (:thread-pool @(:pool-atom timer-pool)))
    " Task count = " (.getTaskCount (:thread-pool @(:pool-atom timer-pool)))
    " Completed count = " (.getCompletedTaskCount (:thread-pool @(:pool-atom timer-pool)))))

; This doesn't work... it seems to create extra thread pools each time
(s/defn interval-changed-callback :- s/Int
  "When a change to thread-test.processingIntervalMillis is detected,
  restart the workers to run on the new interval"
  [value :- s/Int]
  ; restart workers to pick up new interval
  (.setContinueExistingPeriodicTasksAfterShutdownPolicy
    (:thread-pool @(:pool-atom timer-pool)) false)
  (prn "in callback")
  ;(let [old-pool (:thread-pool @(:pool-atom timer-pool))]
    ;(prn "in callback old pool is " old-pool)
    (scheduler/stop-and-reset-pool! timer-pool :strategy :stop)
   #_ (prn "After reset. Old pool task count = " (.getTaskCount (:thread-pool  @(:pool-atom old-pool)))
      " Completed count = " (.getCompletedTaskCount (:thread-pool @(:pool-atom old-pool))))
    (prn "After reset New pool task count = " (.getTaskCount (:thread-pool @(:pool-atom timer-pool)))
      " Completed count = " (.getCompletedTaskCount (:thread-pool @(:pool-atom timer-pool))))
    ;; TODO dup logic with start-workers to avoid cycle... must be a better way!
    (doseq [key-val file-regex-map]
      (let [table-name (-> key-val key name)
            interval (d/get "thread-test.processingIntervalMillis"
                       default-processing-interval interval-changed-callback)]
        (prn "processingIntervalMillis changed: interval = " interval)
        (scheduler/every interval #(do-stuff key-val)  timer-pool
          :desc (task-name table-name))))
    ; set next callback
    (d/get "thread-test.processingIntervalMillis"
      default-processing-interval interval-changed-callback));)

(defn start-workers
  "Start a pool of workers to load files from S3->Redshift.
  There is one worker for each table."
  []
  (prn "starting workers")

  (doseq [key-val file-regex-map]
    (let [table-name (-> key-val key name)
          interval (d/get "thread-test.processingIntervalMillis" default-processing-interval
                     interval-changed-callback)]
      (prn "Run task...")
      #_(.setKeepAliveTime (:thread-pool @(:pool-atom timer-pool)) 60 TimeUnit/SECONDS)
      (scheduler/every interval #(do-stuff key-val)  timer-pool
        :desc (task-name table-name)))))

(def shutdown< (a/chan))

(defn init
  []
  (let [app-name "thread-test"
        app-id (str (uuid/v1))]
    (prn "app id is " app-id)
    (d/init app-name app-id)
    (zk/connect app-name app-id {}))
  (start-workers)

  (a/go
    (loop []
      (prn "starting loop")
      (when-let [event (a/<! shutdown<)]
        (stop-workers)
        (System/exit 0))
      (recur))))

(defn main [& args]
  (init))


