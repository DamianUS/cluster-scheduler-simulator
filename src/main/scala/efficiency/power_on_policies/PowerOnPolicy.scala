package efficiency.power_on_policies

import ClusterSchedulingSimulation.{CellState, ClaimDelta, Job}
import efficiency.power_on_policies.action.PowerOnAction
import efficiency.power_on_policies.decision.PowerOnDecision

import scala.util.control.Breaks

/**
 * Created by dfernandez on 13/1/16.
 */
trait PowerOnPolicy {
  var powerOnAction : PowerOnAction
  var powerOnDecisionPolicy : PowerOnDecision
  val name : String
  def powerOn(cellState: CellState, job: Job, schedType: String, commitedDelta: Seq[ClaimDelta] = Seq[ClaimDelta](), conflictedDelta: Seq[ClaimDelta] =Seq[ClaimDelta]()) = {
    assertSchedulerName(schedType)
    if(powerOnDecisionPolicy.shouldPowerOn(cellState, job, schedType, commitedDelta, conflictedDelta)){
      job.turnOnRequests :+= cellState.simulator.currentTime
      var numberOfMachinesToPowerOn = powerOnAction.numberOfMachinesToPowerOn(cellState,job,schedType,commitedDelta,conflictedDelta)
      if (cellState.numberOfMachinesOff < numberOfMachinesToPowerOn && cellState.numberOfMachinesOff > 0) {
        cellState.simulator.log(("There are not enough machines turned off, turning on %d machines on %s policy").format(cellState.numberOfMachinesOff, name))
        numberOfMachinesToPowerOn = cellState.numberOfMachinesOff
      }
      else if(cellState.numberOfMachinesOff <= 0){
        cellState.simulator.log(("All machines are on, cant turn on any machines on %s policy").format(name))
        numberOfMachinesToPowerOn = 0
      }
      val loop = new Breaks;
      if((schedType == "mesos" || schedType == "omega") && numberOfMachinesToPowerOn > 0){
        cellState.simulator.sorter.orderResources(cellState)
      }
      loop.breakable {
        for (i <- cellState.numMachines - 1 to 0 by -1) {
          if (numberOfMachinesToPowerOn == 0) {
            loop.break
          }
          //Metido esto a pelo, cambiarlo luego
          if (cellState.isMachineOff(cellState.machinesLoad(i)) && job.security <= cellState.machinesSecurity(cellState.machinesLoad(i))) {
            cellState.powerOnMachine(cellState.machinesLoad(i))
            numberOfMachinesToPowerOn -= 1
          }
        }
      }
    }
  }
  def assertSchedulerName(schedType: String) = {
    assert(schedType == "mesos" || schedType == "omega" || schedType == "monolithic", ("Sched Type invalid in %s Power On Policy").format(name))
  }
}
