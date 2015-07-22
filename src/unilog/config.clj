(ns unilog.config
  "Small veneer on top of logback.
   Originally based on the logging initialization in riemann.
   Now diverged quite a bit.

   For configuration, a single public function is exposed: `start-logging!` which
   takes care of configuring logback, later logging is done through
   standard facilities, such as [clojure.tools.logging](https://github.com/clojure/tools.logging).

   Two extension mechanism are provided to add support for more appenders and encoders,
   see `build-appender` and `build-encoder` respectively"
  (:import org.slf4j.LoggerFactory
           ch.qos.logback.classic.net.SocketAppender
           ch.qos.logback.classic.encoder.PatternLayoutEncoder
           ch.qos.logback.classic.Logger
           ch.qos.logback.classic.BasicConfigurator
           ch.qos.logback.classic.Level
           ch.qos.logback.core.ConsoleAppender
           ch.qos.logback.core.FileAppender
           ch.qos.logback.core.OutputStreamAppender
           ch.qos.logback.core.rolling.TimeBasedRollingPolicy
           ch.qos.logback.core.rolling.FixedWindowRollingPolicy
           ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
           ch.qos.logback.core.rolling.RollingFileAppender
           ch.qos.logback.core.util.Duration
           ch.qos.logback.core.net.SyslogOutputStream
           net.logstash.logback.encoder.LogstashEncoder))

;; Configuration constants
;; =======================

(def levels
  "Logging level names to log4j level association"
  {"debug" Level/DEBUG
   "info"  Level/INFO
   "warn"  Level/WARN
   "error" Level/ERROR
   "all"   Level/ALL
   "trace" Level/TRACE
   "off"   Level/OFF})

(def default-pattern
  "Default pattern for PatternLayoutEncoder"
  "%p [%d] %t - %c%n%m%n")

(def default-encoder
  "Default encoder and pattern configuration"
  {:encoder :pattern
   :pattern  default-pattern})


;; Open dispatch method to build appender configuration
;; ====================================================

(defmulti appender-config
  "Called by walking through each key/val pair in the main configuration
   map. This allows for early transformation of quick access keys such as:
   `:console`, `:file`, and `:files`"
  first)

(defmethod appender-config :default
  [_]
  nil)

(defmethod appender-config :console
  [[_ val]]
  (when (boolean val)
    (cond (string? val)  {:appender :console
                          :encoder  :pattern
                          :pattern  val}
          (map? val)     (-> (merge default-encoder val)
                             (update-in [:encoder] keyword)
                             (assoc :appender :console))
          :else          {:appender :console
                          :encoder  :pattern
                          :pattern  default-pattern})))

(defmethod appender-config :file
  [[_ val]]
  (cond (string? val) (-> default-encoder
                          (assoc :appender :file)
                          (assoc :file val))
        (map? val)    (-> (merge default-encoder val)
                          (update-in [:encoder] keyword)
                          (assoc :appender :file))
        :else         (throw (ex-info "invalid file appender config"
                                      {:config val}))))

(defmethod appender-config :files
  [[_ files]]
  (for [file files]
    (appender-config [:file file])))

(defmethod appender-config :appenders
  [[_ appenders]]
  (for [appender appenders
        :when (map? appender)]
    (-> appender
        (update-in [:encoder] keyword)
        (update-in [:appender] keyword))))

;; Open dispatch method to build encoders based on configuration
;; =============================================================

(defmulti build-encoder
  "Given a prepared configuration map, associate a prepared encoder
  to the `:encoder` key."
  :encoder)

(defmethod build-encoder :pattern
  [{:keys [pattern] :as config}]
  (let [encoder (doto (PatternLayoutEncoder.)
                  (.setPattern (or pattern default-pattern)))]
    (assoc config :encoder encoder)))

(defmethod build-encoder :json
  [config]
  (assoc config :encoder (LogstashEncoder.)))

(defmethod build-encoder :default
  [{:keys [appender] :as config}]
  (cond-> config
    (instance? OutputStreamAppender appender)
    (assoc :encoder (doto (PatternLayoutEncoder.)
                      (.setPattern default-pattern)))))

;;
;; Open dispatch to build a file rolling policy
;; ============================================

(defmulti build-rolling-policy
  "Given a configuration map, build a RollingPolicy instance."
  :type)

(defmethod build-rolling-policy :fixed-window
  [{:keys [file pattern max-index min-index parent]
    :or {max-index 5
         min-index 1
         pattern ".%i.gz"}}]
  (doto (FixedWindowRollingPolicy.)
    (.setFileNamePattern (str file pattern))
    (.setMinIndex (int min-index))
    (.setMaxIndex (int max-index))
    (.setParent parent)
    (.setContext (.getContext parent))
    (.start)))

(defmethod build-rolling-policy :time-based
  [{:keys [file pattern parent] :or {pattern ".%d{yyyy-MM-dd}.gz"}}]
  (doto (TimeBasedRollingPolicy.)
    (.setFileNamePattern (str file pattern))
    (.setParent parent)
    (.setContext (.getContext parent))
    (.start)))

;;
;; Open dispatch to build a triggering policy for rolling files
;; ============================================================

(defmulti build-triggering-policy
  "Given a configuration map, build a TriggeringPolicy instance."
  :type)


(defmethod build-triggering-policy :size-based
  [{:keys [max-size]
    :or {max-size SizeBasedTriggeringPolicy/DEFAULT_MAX_FILE_SIZE}}]
  (SizeBasedTriggeringPolicy. (str max-size)))

;; Open dispatch method to build appenders
;; =======================================

(defmulti build-appender
  "Given a prepared configuration map, associate a prepared appender
  to the `:appender` key."
  :appender)

(defmethod build-appender :console
  [config]
  (assoc config :appender (ConsoleAppender.)))

(defmethod build-appender :file
  [{:keys [file] :as config}]
  (assoc config :appender (doto (FileAppender.)
                            (.setFile file))))

(defmethod build-appender :socket
  [{:keys [remote-host port queue-size reconnection-delay event-delay-limit]
    :or {remote-host "localhost"
         port        2004
         queue-size  500
         reconnection-delay "10 seconds"
         event-delay-limit "10 seconds"}
    :as config}]
  (let [appender (SocketAppender.)]
    (.setRemoteHost appender remote-host)
    (.setPort appender (int port))
    (when queue-size
      (.setQueueSize appender (int queue-size)))
    (when reconnection-delay
      (.setReconnectionDelay appender (Duration/valueOf reconnection-delay)))
    (when event-delay-limit
      (.setEventDelayLimit appender (Duration/valueOf event-delay-limit)))
    (assoc config :appender appender)))

(defmethod build-appender :syslog
  [{:keys [host port] :or {host "localhost" port 514} :as config}]
  (assoc config :appender (fn [encoder context]
                            (doto (OutputStreamAppender.)
                              (.setContext context)
                              (.setEncoder encoder)
                              (.setOutputStream
                               (SyslogOutputStream. host (int port)))))))

(defmethod build-appender :rolling-file
  [{:keys [rolling-policy triggering-policy file context]
    :or {rolling-policy    :fixed-window
         triggering-policy :size-based}
    :as config}]
  (let [appender (RollingFileAppender.)]
    (assoc config :appender (doto appender
                              (.setFile file)
                              (.setContext context)
                              (.setRollingPolicy
                               (build-rolling-policy
                                (merge
                                 {:file file}
                                 (cond
                                   (keyword? rolling-policy)
                                   {:type rolling-policy}

                                   (string? rolling-policy)
                                   {:type (keyword rolling-policy)}

                                   (map? rolling-policy)
                                   (update-in rolling-policy [:type] keyword)

                                   :else
                                   (throw (ex-info "invalid rolling policy"
                                                   {:config rolling-policy})))
                                 {:parent appender})))
                              (.setTriggeringPolicy
                               (build-triggering-policy
                                (merge {:file file}
                                       (cond
                                         (keyword? triggering-policy)
                                         {:type triggering-policy}

                                         (string? triggering-policy)
                                         {:type (keyword triggering-policy)}

                                         (map? triggering-policy)
                                         (update-in triggering-policy [:type] keyword)

                                         :else
                                         (throw
                                          (ex-info "invalid triggering policy"
                                                   {:config triggering-policy}))))))))))

(defmethod build-appender :default
  [val]
  (throw (ex-info "invalid log appender configuration" {:config val})))

(defn start-logging!
  "Initialize log4j logging from a map.

   The map accepts the following keys as keywords
   - `:level`: Default level at which to log.
   - `:pattern`: The pattern to use for logging text messages
   - `:console`: Append messages to the console using a simple pattern
      layout. If value is a boolean, treat it as such and use a default
      encoder. If value is a string, treat it as a pattern and use
      a pattern encoder. If value is a map, expect encoder configuration
      in the map.
   - `:file`:  A file to log to. May either be a string, the log file, or
      a map which accepts optional encoder configuration.
   - `:files`: A list of either strings or maps. strings will create
      text files, maps are expected to contain a `:path` key as well
      as an optional `:json` which when present and true will switch
      the layout to a JSONEventLayout for the logger.
   - `:overrides`: A map of namespace or class-name to log level,
      this will supersede the global level.
   - `:external`: Do not proceed with configuration, this
      is useful when logging configuration is provided
      in a different manner (by supplying your own logback config file
      for instance).

   When called with no arguments, assume an empty map

example:

```clojure
{:console   true
 :level     \"info\"
 :files     [\"/var/log/app.log\"
             {:file \"/var/log/app-json.log\"
              :encoder json}]
 :overrides {\"some.namespace\" \"debug\"}}
```
  "
  ([{:keys [external level overrides] :as config}]
   (when-not external
     (let [level   (get levels (some-> level name) Level/INFO)
           root    (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME)
           context (LoggerFactory/getILoggerFactory)
           assoc-context (fn [f] (comp f #(assoc % :context context)))
           configs (->> (merge {:console true} config)
                        (map appender-config)
                        (flatten)
                        (remove nil?)
                        (map (assoc-context build-appender))
                        (map build-encoder))]

       (.detachAndStopAllAppenders root)

       (doseq [{:keys [encoder appender]} configs]
         (when encoder
           (.setContext encoder context)
           (.start encoder))
         (let [appender (if (fn? appender)
                          (appender encoder context)
                          (doto appender
                            (.setEncoder encoder)
                            (.setContext context)))]
           (.start appender)
           (.addAppender root appender)))

       (.setLevel root level)
       (doseq [[logger level] overrides
               :let [logger (LoggerFactory/getLogger (name logger))
                     level  (get levels level Level/INFO)]]
         (.setLevel logger level))
       root)))
  ([]
   (start-logging! {})))
