/**
 * Copyright 2017 Alexander Pakulov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.metrics

import java.util.concurrent.TimeUnit

import com.yammer.metrics.Metrics
import com.yammer.metrics.core.{Clock, Metric, MetricName, MetricPredicate}
import com.yammer.metrics.reporting.GraphiteReporter
import com.yammer.metrics.reporting.GraphiteReporter.DefaultSocketProvider
import kafka.utils.{VerifiableProperties}
import org.apache.log4j.{Level, Logger}
import scala.collection.JavaConversions._

trait KafkaGraphiteMetricsReporterMBean extends KafkaMetricsReporterMBean

class KafkaGraphiteMetricsReporter extends KafkaMetricsReporter
                                    with KafkaGraphiteMetricsReporterMBean {

  private var underlying: GraphiteReporter = _
  private var running = false
  private var initialized = false

  val loggerName = this.getClass.getName
  lazy val logger = Logger.getLogger(loggerName)

  override def getMBeanName: String = "kafka:type=kafka.metrics.KafkaGraphiteMetricsReporter"

  override def init(props: VerifiableProperties) {
    synchronized {
      if (!initialized) {
        val metricsConfig = new KafkaGraphiteMetricsConfig(props)
        val socketProvider = new DefaultSocketProvider(metricsConfig.host, metricsConfig.port)

        val metricPredicate = new MetricPredicate {
          val include = Option(metricsConfig.include)
          val exclude = Option(metricsConfig.exclude)

          override def matches(name: MetricName, metric: Metric): Boolean = {
            if (include.isDefined && !include.get.matcher(groupMetricName(name)).matches()) {
              return false
            }
            if (exclude.isDefined && exclude.get.matcher(groupMetricName(name)).matches()) {
              return false
            }
            true
          }

          private def groupMetricName(name: MetricName): String = {
            val result = new StringBuilder().append(name.getGroup).append('.').append(name.getType).append('.')
            if (name.hasScope) {
              result.append(name.getScope).append('.')
            }
            result.append(name.getName).toString().replace(' ', '_')
          }
        }

        logger.info("Configuring Kafka Graphite Reporter with host=%s, port=%d, prefix=%s and include=%s, exclude=%s, jvm=%s".format(
          metricsConfig.host, metricsConfig.port, metricsConfig.prefix, metricsConfig.include, metricsConfig.exclude, metricsConfig.jvm))
        underlying = new GraphiteReporter(Metrics.defaultRegistry, metricsConfig.prefix, metricPredicate,
                                          socketProvider, Clock.defaultClock) {
          override def printRegularMetrics(epoch: java.lang.Long) = {
            val metrics = getMetricsRegistry.groupedMetrics(predicate).toMap.values.flatten
            metrics.foreach { case (name: MetricName, metric: Metric) if metric != null =>
              try {
                metric.processWith(this, name, epoch)
              } catch {
                case e: Exception => logger.error("Error printing regular metrics=" + name, e)
              }
            }
          }
        }
        // Controls JVM metrics output
        underlying.printVMMetrics = metricsConfig.jvm
        if (metricsConfig.enabled) {
          initialized = true
          startReporter(metricsConfig.pollingIntervalSecs)
        }
      }
    }
  }

  override def startReporter(pollingPeriodSecs: Long) {
    synchronized {
      if (initialized && !running) {
        underlying.start(pollingPeriodSecs, TimeUnit.SECONDS)
        running = true
        logger.info("Started Kafka Graphite metrics reporter with polling period %d seconds".format(pollingPeriodSecs))
      }
    }
  }

  override def stopReporter() {
    synchronized {
      if (initialized && running) {
        underlying.shutdown()
        running = false
        logger.info("Stopped Kafka Graphite metrics reporter")
        underlying = null
      }
    }
  }
}
