package dynamic.normalization

import ClusterSchedulingSimulation.{Job, Offer, Scheduler}

trait MesosCapableScheduler extends Scheduler{
  val name : String

  def nextJob() : Job
  def resourceOffer(offer: Offer): Unit
}
