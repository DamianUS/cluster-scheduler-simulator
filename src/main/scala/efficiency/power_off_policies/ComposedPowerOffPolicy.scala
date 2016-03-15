package efficiency.power_off_policies

import efficiency.power_off_policies.action.PowerOffAction
import efficiency.power_off_policies.decision.PowerOffDecision

/**
 * Created by dfernandez on 13/1/16.
 */
class ComposedPowerOffPolicy(action : PowerOffAction, decision : PowerOffDecision) extends PowerOffPolicy{

  override var powerOffAction: PowerOffAction = action
  override var powerOffDecisionPolicy: PowerOffDecision = decision

  override val name: String = ("composed_power_off_policy_with_decision:%s_and_action:%s").format(powerOffDecisionPolicy.name, powerOffAction.name)

}
