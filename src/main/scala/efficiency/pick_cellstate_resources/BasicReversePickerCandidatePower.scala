package efficiency.pick_cellstate_resources

import ClusterSchedulingSimulation.{CellState, Job}

import scala.collection.mutable.IndexedSeq
import scala.util.control.Breaks

/**
 * Created by dfernandez on 11/1/16.
 */
// This picker doesn't take into account yet shutted down machines nor capacity security margins nor performance
// TODO: Make a Quicksort-like strategy or pass a candidate index to iterate (e.g. the last successful candidate)
object BasicReversePickerCandidatePower extends CellStateResourcesPicker{
  override def pickResource(cellState: CellState, job: Job, candidatePool: IndexedSeq[Int], remainingCandidates: Int) = {
    var machineID = -1
    var numTries =0
    var remainingCandidatesVar= cellState.numMachines
    val loop = new Breaks;
    loop.breakable {
      for(i <-  cellState.numMachines-1 to 0 by -1){
        //FIXME: Putting a security margin of 0.01 because mesos is causing conflicts
        val mID = cellState.machinesLoad(i)
        if (cellState.isMachineOn(mID) && cellState.availableCpusPerMachine(mID) >= (job.cpusPerTask + 0.0001) && cellState.availableMemPerMachine(mID) >= (job.memPerTask + 0.0001)) {
          machineID=mID
          assert(cellState.isMachineOn(machineID), "Trying to pick a powered off machine with picker : "+name)
          loop.break;
        }
        else{
          numTries+=1
          remainingCandidatesVar -=1 // This is irrelevant in this implementation, as derivable of numTries. I'll use it in quicksort-like implementations
        }

      }
    }
    if(machineID == -1){
      assert(remainingCandidatesVar == 0, ("No ha encontrado un candidato en %s y sin embargo dice que hay %d candidatos aún disponibles").format(name, remainingCandidatesVar))
    }
    new Tuple4(machineID, numTries, remainingCandidatesVar, candidatePool)
  }
  override val name: String = "reverse-power-picker-candidate"
}
