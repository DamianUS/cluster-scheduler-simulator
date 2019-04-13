package efficiency.power_on_policies.decision

import ClusterSchedulingSimulation.{CellState, ClaimDelta, Job}

/**
 * Created by dfernandez on 15/1/16.
 */
class AvailableCapacityPowerOnDecision(availabilityFactor: Double) extends PowerOnDecision{
  override def shouldPowerOn(cellState: CellState, job: Job, schedType: String, commitedDelta: Seq[ClaimDelta], conflictedDelta: Seq[ClaimDelta]): Boolean = {
    /*var should = false
    //FIXME : So naive, it only turns on machine if the job literally cant fit
    if(schedType != "omega" || ((schedType == "omega") && (conflictedDelta.isEmpty || conflictedDelta.map(_.cpus).sum > cellState.availableCpus || conflictedDelta.map(_.mem).sum > cellState.availableMem))){
      should = job!=null && job.unscheduledTasks > 0 && (job.turnOnRequests.length <=1 || (cellState.simulator.currentTime - job.turnOnRequests(job.turnOnRequests.length-1)) > cellState.powerOnTime*1.05)
    }
    should*/
    assert(availabilityFactor > 0.0, "Availability ")
    job!=null && job.unscheduledTasks > 0 && job.cpusStillNeeded*availabilityFactor > cellState.availableCpus && (job.turnOnRequests.length <=1 || (cellState.simulator.currentTime - job.turnOnRequests(job.turnOnRequests.length-1)) > cellState.powerOnTime*1.05)

  }

  override val name: String = ("availability-capacity:%f").format(availabilityFactor)
}
