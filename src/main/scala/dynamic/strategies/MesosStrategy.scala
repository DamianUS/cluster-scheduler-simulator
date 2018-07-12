package dynamic.strategies

import ClusterSchedulingSimulation.Job
import dynamic.{DynamicScheduler, DynamicSimulator}

class MesosStrategy(sched : DynamicScheduler) extends RMStrategy {
  override val name: String = "Mesos"
  override var scheduler: DynamicScheduler = sched

  override
  def addJob(job: Job) = {
    scheduler.simulator.log("========================================================")
    scheduler.simulator.log("addJOB: CellState total usage: %fcpus (%.1f%s), %fmem (%.1f%s)."
      .format(scheduler.simulator.cellState.totalOccupiedCpus,
        scheduler.simulator.cellState.totalOccupiedCpus /
          scheduler.simulator.cellState.totalCpus * 100.0,
        "%",
        scheduler.simulator.cellState.totalOccupiedMem,
        scheduler.simulator.cellState.totalOccupiedMem /
          scheduler.simulator.cellState.totalMem * 100.0,
        "%"))
    scheduler.pendingQueue.enqueue(job)
    scheduler.simulator.log("Enqueued job %d of workload type %s."
      .format(job.id, job.workloadName))
    scheduler.simulator.asInstanceOf[DynamicSimulator].allocator.requestOffer(scheduler)
  }
}
