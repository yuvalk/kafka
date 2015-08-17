/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import java.util

import kafka.admin._
import kafka.log.LogConfig
import kafka.log.CleanerConfig
import kafka.log.LogManager
import java.util.concurrent._
import atomic.{AtomicInteger, AtomicBoolean}
import java.io.File

import kafka.utils._
import org.apache.kafka.common.metrics._
import org.apache.kafka.common.network.NetworkReceive
import org.apache.kafka.common.metrics.{JmxReporter, Metrics}

import scala.collection.mutable
import org.I0Itec.zkclient.ZkClient
import kafka.controller.{ControllerStats, KafkaController}
import kafka.cluster.{EndPoint, Broker}
import kafka.api.{ControlledShutdownResponse, ControlledShutdownRequest}
import kafka.common.{ErrorMapping, InconsistentBrokerIdException, GenerateBrokerIdException}
import kafka.network.{BlockingChannel, SocketServer}
import kafka.metrics.KafkaMetricsGroup
import com.yammer.metrics.core.Gauge
import kafka.coordinator.{GroupManagerConfig, ConsumerCoordinator}

/**
 * Represents the lifecycle of a single Kafka broker. Handles all functionality required
 * to start up and shutdown a single Kafka node.
 */
class KafkaServer(val config: KafkaConfig, time: Time = SystemTime) extends Logging with KafkaMetricsGroup {
  private val startupComplete = new AtomicBoolean(false)
  private val isShuttingDown = new AtomicBoolean(false)
  private val isStartingUp = new AtomicBoolean(false)

  private var shutdownLatch = new CountDownLatch(1)

  private val metricConfig: MetricConfig = new MetricConfig()
          .samples(config.metricNumSamples)
          .timeWindow(config.metricSampleWindowMs, TimeUnit.MILLISECONDS)
  private val jmxPrefix: String = "kafka.server"
  private val reporters: java.util.List[MetricsReporter] =  config.metricReporterClasses
  reporters.add(new JmxReporter(jmxPrefix))

  // This exists because the Metrics package from clients has its own Time implementation.
  // SocketServer/Quotas (which uses client libraries) have to use the client Time objects without having to convert all of Kafka to use them
  // Eventually, we want to merge the Time objects in core and clients
  private val kafkaMetricsTime: org.apache.kafka.common.utils.Time = new org.apache.kafka.common.utils.SystemTime()
  var metrics: Metrics = null

  val brokerState: BrokerState = new BrokerState

  var apis: KafkaApis = null
  var socketServer: SocketServer = null
  var requestHandlerPool: KafkaRequestHandlerPool = null

  var logManager: LogManager = null

  var replicaManager: ReplicaManager = null

  var dynamicConfigHandlers: Map[String, ConfigHandler] = null
  var dynamicConfigManager: DynamicConfigManager = null

  var consumerCoordinator: ConsumerCoordinator = null

  var kafkaController: KafkaController = null

  val kafkaScheduler = new KafkaScheduler(config.backgroundThreads)

  var kafkaHealthcheck: KafkaHealthcheck = null
  val metadataCache: MetadataCache = new MetadataCache(config.brokerId)


  var zkClient: ZkClient = null
  val correlationId: AtomicInteger = new AtomicInteger(0)
  val brokerMetaPropsFile = "meta.properties"
  val brokerMetadataCheckpoints = config.logDirs.map(logDir => (logDir, new BrokerMetadataCheckpoint(new File(logDir + File.separator +brokerMetaPropsFile)))).toMap

  newGauge(
    "BrokerState",
    new Gauge[Int] {
      def value = brokerState.currentState
    }
  )

  /**
   * Start up API for bringing up a single instance of the Kafka server.
   * Instantiates the LogManager, the SocketServer and the request handlers - KafkaRequestHandlers
   */
  def startup() {
    try {
      info("starting")

      if(isShuttingDown.get)
        throw new IllegalStateException("Kafka server is still shutting down, cannot re-start!")

      if(startupComplete.get)
        return

      val canStartup = isStartingUp.compareAndSet(false, true)
      if (canStartup) {
        metrics = new Metrics(metricConfig, reporters, kafkaMetricsTime)

        brokerState.newState(Starting)

        /* start scheduler */
        kafkaScheduler.startup()

        /* setup zookeeper */
        zkClient = initZk()

        /* start log manager */
        logManager = createLogManager(zkClient, brokerState)
        logManager.startup()

        /* generate brokerId */
        config.brokerId =  getBrokerId
        this.logIdent = "[Kafka Server " + config.brokerId + "], "

        csocketServer = new SocketServer(config.brokerId,
                                        config.clisteners,
                                        config.numNetworkThreads,
                                        config.queuedMaxRequests,
                                        config.socketSendBufferBytes,
                                        config.socketReceiveBufferBytes,
                                        config.socketRequestMaxBytes,
                                        config.maxConnectionsPerIp,
                                        config.connectionsMaxIdleMs,
                                        config.maxConnectionsPerIpOverrides,
                                        kafkaMetricsTime,
                                        metrics)

        psocketServer = new SocketServer(config.brokerId,
                                        config.plisteners,
                                        config.numNetworkThreads,
                                        config.queuedMaxRequests,
                                        config.socketSendBufferBytes,
                                        config.socketReceiveBufferBytes,
                                        config.socketRequestMaxBytes,
                                        config.maxConnectionsPerIp,
                                        config.connectionsMaxIdleMs,
                                        config.maxConnectionsPerIpOverrides,
                                        kafkaMetricsTime,
                                        metrics)

          csocketServer.startup()
          psocketServer.startup()

          /* start replica manager */
          replicaManager = new ReplicaManager(config, time, zkClient, kafkaScheduler, logManager, isShuttingDown)
          replicaManager.startup()

          /* start kafka controller */
          kafkaController = new KafkaController(config, zkClient, brokerState)
          kafkaController.startup()

          /* start kafka coordinator */
          consumerCoordinator = ConsumerCoordinator.create(config, zkClient, replicaManager, kafkaScheduler)
          consumerCoordinator.startup()

          /* start processing requests */
          apis = new KafkaApis(socketServer.requestChannel, replicaManager, consumerCoordinator,
            kafkaController, zkClient, config.brokerId, config, metadataCache, metrics)
          requestHandlerPool = new KafkaRequestHandlerPool(config.brokerId, socketServer.requestChannel, apis, config.numIoThreads)
          brokerState.newState(RunningAsBroker)

          Mx4jLoader.maybeLoad()

          /* start dynamic config manager */
          dynamicConfigHandlers = Map[String, ConfigHandler](ConfigType.Topic -> new TopicConfigHandler(logManager),
                                                             ConfigType.Client -> new ClientIdConfigHandler)
          dynamicConfigManager = new DynamicConfigManager(zkClient, dynamicConfigHandlers)
          dynamicConfigManager.startup()

          /* tell everyone we are alive */
          val listeners = config.advertisedListeners.map {case(protocol, endpoint) =>
            if (endpoint.port == 0)
              (protocol, EndPoint(endpoint.host, socketServer.boundPort(), endpoint.protocolType))
            else
              (protocol, endpoint)
          }
          kafkaHealthcheck = new KafkaHealthcheck(config.brokerId, listeners, config.zkSessionTimeoutMs, zkClient)
          kafkaHealthcheck.startup()

          /* register broker metrics */
          registerStats()

          shutdownLatch = new CountDownLatch(1)
          startupComplete.set(true)
          isStartingUp.set(false)
          info("started")
      }
    }
    catch {
      case e: Throwable =>
        fatal("Fatal error during KafkaServer startup. Prepare to shutdown", e)
        isStartingUp.set(false)
        shutdown()
        throw e
    }
  }

  private def initZk(): ZkClient = {
    info("Connecting to zookeeper on " + config.zkConnect)

    val chroot = {
      if (config.zkConnect.indexOf("/") > 0)
        config.zkConnect.substring(config.zkConnect.indexOf("/"))
      else
        ""
    }

    if (chroot.length > 1) {
      val zkConnForChrootCreation = config.zkConnect.substring(0, config.zkConnect.indexOf("/"))
      val zkClientForChrootCreation = ZkUtils.createZkClient(zkConnForChrootCreation, config.zkSessionTimeoutMs, config.zkConnectionTimeoutMs)
      ZkUtils.makeSurePersistentPathExists(zkClientForChrootCreation, chroot)
      info("Created zookeeper path " + chroot)
      zkClientForChrootCreation.close()
    }

    val zkClient = ZkUtils.createZkClient(config.zkConnect, config.zkSessionTimeoutMs, config.zkConnectionTimeoutMs)
    ZkUtils.setupCommonPaths(zkClient)
    zkClient
  }


  /**
   *  Forces some dynamic jmx beans to be registered on server startup.
   */
  private def registerStats() {
    BrokerTopicStats.getBrokerAllTopicsStats()
    ControllerStats.uncleanLeaderElectionRate
    ControllerStats.leaderElectionTimer
  }

  /**
   *  Performs controlled shutdown
   */
  private def controlledShutdown() {
    if (startupComplete.get() && config.controlledShutdownEnable) {
      // We request the controller to do a controlled shutdown. On failure, we backoff for a configured period
      // of time and try again for a configured number of retries. If all the attempt fails, we simply force
      // the shutdown.
      var remainingRetries = config.controlledShutdownMaxRetries
      info("Starting controlled shutdown")
      var channel : BlockingChannel = null
      var prevController : Broker = null
      var shutdownSucceeded : Boolean = false
      try {
        brokerState.newState(PendingControlledShutdown)
        while (!shutdownSucceeded && remainingRetries > 0) {
          remainingRetries = remainingRetries - 1

          // 1. Find the controller and establish a connection to it.

          // Get the current controller info. This is to ensure we use the most recent info to issue the
          // controlled shutdown request
          val controllerId = ZkUtils.getController(zkClient)
          ZkUtils.getBrokerInfo(zkClient, controllerId) match {
            case Some(broker) =>
              if (channel == null || prevController == null || !prevController.equals(broker)) {
                // if this is the first attempt or if the controller has changed, create a channel to the most recent
                // controller
                if (channel != null) {
                  channel.disconnect()
                }
                channel = new BlockingChannel(broker.getBrokerEndPoint(config.interBrokerSecurityProtocol).host,
                  broker.getBrokerEndPoint(config.interBrokerSecurityProtocol).port,
                  BlockingChannel.UseDefaultBufferSize,
                  BlockingChannel.UseDefaultBufferSize,
                  config.controllerSocketTimeoutMs)
                channel.connect()
                prevController = broker
              }
            case None=>
              //ignore and try again
          }

          // 2. issue a controlled shutdown to the controller
          if (channel != null) {
            var response: NetworkReceive = null
            try {
              // send the controlled shutdown request
              val request = new ControlledShutdownRequest(correlationId.getAndIncrement, config.brokerId)
              channel.send(request)

              response = channel.receive()
              val shutdownResponse = ControlledShutdownResponse.readFrom(response.payload())
              if (shutdownResponse.errorCode == ErrorMapping.NoError && shutdownResponse.partitionsRemaining != null &&
                  shutdownResponse.partitionsRemaining.size == 0) {
                shutdownSucceeded = true
                info ("Controlled shutdown succeeded")
              }
              else {
                info("Remaining partitions to move: %s".format(shutdownResponse.partitionsRemaining.mkString(",")))
                info("Error code from controller: %d".format(shutdownResponse.errorCode))
              }
            }
            catch {
              case ioe: java.io.IOException =>
                channel.disconnect()
                channel = null
                warn("Error during controlled shutdown, possibly because leader movement took longer than the configured socket.timeout.ms: %s".format(ioe.getMessage))
                // ignore and try again
            }
          }
          if (!shutdownSucceeded) {
            Thread.sleep(config.controlledShutdownRetryBackoffMs)
            warn("Retrying controlled shutdown after the previous attempt failed...")
          }
        }
      }
      finally {
        if (channel != null) {
          channel.disconnect()
          channel = null
        }
      }
      if (!shutdownSucceeded) {
        warn("Proceeding to do an unclean shutdown as all the controlled shutdown attempts failed")
      }
    }
  }

  /**
   * Shutdown API for shutting down a single instance of the Kafka server.
   * Shuts down the LogManager, the SocketServer and the log cleaner scheduler thread
   */
  def shutdown() {
    try {
      info("shutting down")

      if(isStartingUp.get)
        throw new IllegalStateException("Kafka server is still starting up, cannot shut down!")

      val canShutdown = isShuttingDown.compareAndSet(false, true)
      if (canShutdown && shutdownLatch.getCount > 0) {
        CoreUtils.swallow(controlledShutdown())
        brokerState.newState(BrokerShuttingDown)
        if(socketServer != null)
          CoreUtils.swallow(socketServer.shutdown())
        if(requestHandlerPool != null)
          CoreUtils.swallow(requestHandlerPool.shutdown())
        CoreUtils.swallow(kafkaScheduler.shutdown())
        if(apis != null)
          CoreUtils.swallow(apis.close())
        if(replicaManager != null)
          CoreUtils.swallow(replicaManager.shutdown())
        if(logManager != null)
          CoreUtils.swallow(logManager.shutdown())
        if(consumerCoordinator != null)
          CoreUtils.swallow(consumerCoordinator.shutdown())
        if(kafkaController != null)
          CoreUtils.swallow(kafkaController.shutdown())
        if(zkClient != null)
          CoreUtils.swallow(zkClient.close())
        if (metrics != null)
          CoreUtils.swallow(metrics.close())

        brokerState.newState(NotRunning)

        startupComplete.set(false)
        isShuttingDown.set(false)
        shutdownLatch.countDown()
        info("shut down completed")
      }
    }
    catch {
      case e: Throwable =>
        fatal("Fatal error during KafkaServer shutdown.", e)
        isShuttingDown.set(false)
        throw e
    }
  }

  /**
   * After calling shutdown(), use this API to wait until the shutdown is complete
   */
  def awaitShutdown(): Unit = shutdownLatch.await()

  def getLogManager(): LogManager = logManager

  def boundPort(): Int = socketServer.boundPort()

  private def createLogManager(zkClient: ZkClient, brokerState: BrokerState): LogManager = {
    val defaultProps = copyKafkaConfigToLog(config.originals)
    val defaultLogConfig = LogConfig(defaultProps)

    val configs = AdminUtils.fetchAllTopicConfigs(zkClient).mapValues(LogConfig.fromProps(defaultProps, _))
    // read the log configurations from zookeeper
    val cleanerConfig = CleanerConfig(numThreads = config.logCleanerThreads,
                                      dedupeBufferSize = config.logCleanerDedupeBufferSize,
                                      dedupeBufferLoadFactor = config.logCleanerDedupeBufferLoadFactor,
                                      ioBufferSize = config.logCleanerIoBufferSize,
                                      maxMessageSize = config.messageMaxBytes,
                                      maxIoBytesPerSecond = config.logCleanerIoMaxBytesPerSecond,
                                      backOffMs = config.logCleanerBackoffMs,
                                      enableCleaner = config.logCleanerEnable)
    new LogManager(logDirs = config.logDirs.map(new File(_)).toArray,
                   topicConfigs = configs,
                   defaultConfig = defaultLogConfig,
                   cleanerConfig = cleanerConfig,
                   ioThreads = config.numRecoveryThreadsPerDataDir,
                   flushCheckMs = config.logFlushSchedulerIntervalMs,
                   flushCheckpointMs = config.logFlushOffsetCheckpointIntervalMs,
                   retentionCheckMs = config.logCleanupIntervalMs,
                   scheduler = kafkaScheduler,
                   brokerState = brokerState,
                   time = time)
  }

  // Copy the subset of properties that are relevant to Logs
  // I'm listing out individual properties here since the names are slightly different in each Config class...
  private def copyKafkaConfigToLog(serverProps: java.util.Map[String, Object]): java.util.Map[String, Object] = {

    val logProps = new util.HashMap[String, Object]()
    val entryset = serverProps.entrySet.iterator
    while (entryset.hasNext) {
      val entry = entryset.next
      entry.getKey match {
        case KafkaConfig.LogSegmentBytesProp => logProps.put(LogConfig.SegmentBytesProp, entry.getValue)
        case KafkaConfig.LogRollTimeMillisProp => logProps.put(LogConfig.SegmentMsProp, entry.getValue)
        case KafkaConfig.LogRollTimeJitterMillisProp => logProps.put(LogConfig.SegmentJitterMsProp, entry.getValue)
        case KafkaConfig.LogIndexSizeMaxBytesProp => logProps.put(LogConfig.SegmentIndexBytesProp, entry.getValue)
        case KafkaConfig.LogFlushIntervalMessagesProp => logProps.put(LogConfig.FlushMessagesProp, entry.getValue)
        case KafkaConfig.LogFlushIntervalMsProp => logProps.put(LogConfig.FlushMsProp, entry.getValue)
        case KafkaConfig.LogRetentionBytesProp => logProps.put(LogConfig.RetentionBytesProp, entry.getValue)
        case KafkaConfig.LogRetentionTimeMillisProp => logProps.put(LogConfig.RetentionMsProp, entry.getValue)
        case KafkaConfig.MessageMaxBytesProp => logProps.put(LogConfig.MaxMessageBytesProp, entry.getValue)
        case KafkaConfig.LogIndexIntervalBytesProp => logProps.put(LogConfig.IndexIntervalBytesProp, entry.getValue)
        case KafkaConfig.LogCleanerDeleteRetentionMsProp => logProps.put(LogConfig.DeleteRetentionMsProp, entry.getValue)
        case KafkaConfig.LogDeleteDelayMsProp => logProps.put(LogConfig.FileDeleteDelayMsProp, entry.getValue)
        case KafkaConfig.LogCleanerMinCleanRatioProp => logProps.put(LogConfig.MinCleanableDirtyRatioProp, entry.getValue)
        case KafkaConfig.LogCleanupPolicyProp => logProps.put(LogConfig.CleanupPolicyProp, entry.getValue)
        case KafkaConfig.MinInSyncReplicasProp => logProps.put(LogConfig.MinInSyncReplicasProp, entry.getValue)
        case KafkaConfig.CompressionTypeProp => logProps.put(LogConfig.CompressionTypeProp, entry.getValue)
        case KafkaConfig.UncleanLeaderElectionEnableProp => logProps.put(LogConfig.UncleanLeaderElectionEnableProp, entry.getValue)
        case KafkaConfig.LogPreAllocateProp => logProps.put(LogConfig.PreAllocateEnableProp, entry.getValue)
        case _ => // we just leave those out
      }
    }
    logProps
  }

  /**
    * Generates new brokerId or reads from meta.properties based on following conditions
    * <ol>
    * <li> config has no broker.id provided , generates a broker.id based on Zookeeper's sequence
    * <li> stored broker.id in meta.properties doesn't match in all the log.dirs throws InconsistentBrokerIdException
    * <li> config has broker.id and meta.properties contains broker.id if they don't match throws InconsistentBrokerIdException
    * <li> config has broker.id and there is no meta.properties file, creates new meta.properties and stores broker.id
    * <ol>
    * @return A brokerId.
    */
  private def getBrokerId: Int =  {
    var brokerId = config.brokerId
    var logDirsWithoutMetaProps: List[String] = List()
    val brokerIdSet = mutable.HashSet[Int]()

    for (logDir <- config.logDirs) {
      val brokerMetadataOpt = brokerMetadataCheckpoints(logDir).read()
      brokerMetadataOpt match {
        case Some(brokerMetadata: BrokerMetadata) =>
          brokerIdSet.add(brokerMetadata.brokerId)
        case None =>
          logDirsWithoutMetaProps ++= List(logDir)
      }
    }

    if(brokerIdSet.size > 1)
      throw new InconsistentBrokerIdException("Failed to match brokerId across logDirs")
    else if(brokerId >= 0 && brokerIdSet.size == 1 && brokerIdSet.last != brokerId)
      throw new InconsistentBrokerIdException("Configured brokerId %s doesn't match stored brokerId %s in meta.properties".format(brokerId, brokerIdSet.last))
    else if(brokerIdSet.size == 0 && brokerId < 0)  // generate a new brokerId from Zookeeper
      brokerId = generateBrokerId
    else if(brokerIdSet.size == 1) // pick broker.id from meta.properties
      brokerId = brokerIdSet.last

    for(logDir <- logDirsWithoutMetaProps) {
      val checkpoint = brokerMetadataCheckpoints(logDir)
      checkpoint.write(new BrokerMetadata(brokerId))
    }

    brokerId
  }

  private def generateBrokerId: Int = {
    try {
      ZkUtils.getBrokerSequenceId(zkClient, config.maxReservedBrokerId)
    } catch {
      case e: Exception =>
        error("Failed to generate broker.id due to ", e)
        throw new GenerateBrokerIdException("Failed to generate broker.id", e)
    }
  }
}
