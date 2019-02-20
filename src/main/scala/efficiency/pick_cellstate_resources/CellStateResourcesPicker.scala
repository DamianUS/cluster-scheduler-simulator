package efficiency.pick_cellstate_resources

import ClusterSchedulingSimulation._

import scala.collection.mutable.{HashMap, IndexedSeq, ListBuffer}

/**
 * Created by dfernandez on 11/1/16.
 */
trait CellStateResourcesPicker {
  //Passing the Scheduler as a hook to update / accessing task-independent variables
  def schedule(cellState: CellState, job: Job, scheduler: Scheduler, simulator: ClusterSimulator): ListBuffer[ClaimDelta] ={
    val claimDeltas = collection.mutable.ListBuffer[ClaimDelta]()
    var candidatePool = scheduler.candidatePoolCache.getOrElseUpdate(cellState.numberOfMachinesOn, Array.range(0, cellState.numMachines))
    var numRemainingTasks = job.unscheduledTasks
    var remainingCandidates = math.max(0, cellState.numberOfMachinesOn - scheduler.numMachinesBlackList).toInt
    simulator.sorter.orderResources(cellState)
    while(numRemainingTasks > 0 && remainingCandidates > 0) {
      val pickResult = pickResource(cellState, job, candidatePool, remainingCandidates);
      remainingCandidates = pickResult._3
      candidatePool = pickResult._4
      scheduler.failedFindVictimAttempts += pickResult._2
      if(pickResult._1 > -1){
        val currMachID = pickResult._1
        var securityTime = 0.0
        if(cellState.machinesSecurity(currMachID) == 1)
          securityTime = simulator.securityLevel1Time
        if(cellState.machinesSecurity(currMachID) == 2)
          securityTime = simulator.securityLevel2Time
        else if(cellState.machinesSecurity(currMachID) == 3)
          securityTime = simulator.securityLevel3Time
        assert(currMachID >= 0 && currMachID < cellState.machineSeqNums.length)
        val claimDelta = new ClaimDelta(scheduler,
          currMachID,
          cellState.machineSeqNums(currMachID),
          //TODO: IMPORTANTE: AHORA MISMO LAS TAREAS SON CONSIDERADAS HOMOGÉNEAS EN TODOS LOS PICKERS QUE HEREDAN DE ESTE SCHEDULE SIN SOBREESCRIBIRLO
          if (cellState.machinesHeterogeneous /*&& job.workloadName == "Batch"*/) ((job.taskDuration * cellState.machinesPerformance(currMachID)) +  (securityTime * cellState.machinesPerformance(currMachID))) else job.taskDuration + securityTime,
          job.cpusPerTask,
          job.memPerTask,
          job = job)
        claimDelta.apply(cellState = cellState, locked = false)
        claimDeltas += claimDelta
        numRemainingTasks -= 1
      }
    }
    claimDeltas
  }
  def pickResource(cellstate: CellState, job: Job, candidatePool: IndexedSeq[Int], remainingCandidates: Int) : Tuple4[Int, Int, Int, IndexedSeq[Int]]
  val name : String
}
