package efficiency.power_off_policies.decision.deterministic.security_margin

import ClusterSchedulingSimulation.CellState
import efficiency.power_off_policies.decision.PowerOffDecision

/**
   * Created by dfernandez on 15/1/16.
   */
class ConstantMarginPowerOffDecision(percentage : Double) extends PowerOffDecision{
    override def shouldPowerOff(cellState: CellState, machineID: Int): Boolean = {
      assert(percentage > 0 && percentage < 1, "Security margin percentage value must be between 0.001 and 0.999")
          cellState.availableCpus > (cellState.totalCpus * percentage) && cellState.availableMem > (cellState.totalMem * percentage)
    }

    override val name: String = ("constant-margin-power-off-decision-with-perc:%f").format(percentage)
  }
