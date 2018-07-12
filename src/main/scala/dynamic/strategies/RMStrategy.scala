package dynamic.strategies

import ClusterSchedulingSimulation.Job
import dynamic.{DynamicScheduler}

trait RMStrategy {
  var scheduler : DynamicScheduler
  val name : String

  def addJob(job: Job)
}
