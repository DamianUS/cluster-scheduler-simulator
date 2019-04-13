package efficiency.power_on_policies.decision

import ClusterSchedulingSimulation.{CellState, ClaimDelta, Job}

/**
 * Created by dfernandez on 15/1/16.
 */
class MarginPowerOnDecision(securityMargin: Double) extends PowerOnDecision{
  override def shouldPowerOn(cellState: CellState, job: Job, schedType: String, commitedDelta: Seq[ClaimDelta], conflictedDelta: Seq[ClaimDelta]): Boolean = {
    assert(securityMargin >= 0.0, ("Security margin in %s policy must be greater 0.0").format(name))
    val machinesCpuNeeded = Math.max(0, ((securityMargin * cellState.numberOfMachinesOn * cellState.cpusPerMachine - (cellState.availableCpus - job.cpusStillNeeded))/cellState.cpusPerMachine).ceil.toInt)
    val machinesMemNeeded = Math.max(0, ((securityMargin * cellState.numberOfMachinesOn * cellState.memPerMachine - (cellState.availableMem - job.memStillNeeded))/cellState.memPerMachine).ceil.toInt)
    machinesMemNeeded > 0 || machinesCpuNeeded > 0
  }

  override val name: String = ("margin-min-margin:%f").format(securityMargin)

}
