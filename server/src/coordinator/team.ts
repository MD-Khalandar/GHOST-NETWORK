import { CloudCoordinator } from "./coordinator.js";
import { CoordinatorStore } from "./store.js";
import type { ProposalModel,RunResult } from "./types.js";

/** Two scoped specialists; orchestration, progress checks and allocation stay deterministic. */
export class AssistanceTeam {
  private readonly specialist:CloudCoordinator;
  constructor(store:CoordinatorStore,model:ProposalModel,now=Date.now,budget=120) {
    this.specialist=new CloudCoordinator(store,model,now,budget);
  }
  async run(requestId:string,leaseOwner?:string):Promise<RunResult> {
    // Each actual provider call independently consumes the existing shared budget.
    const results=await Promise.all([
      this.specialist.run(requestId,leaseOwner,"helper"),
      this.specialist.run(requestId,leaseOwner,"supply"),
    ]);
    const failure=results.find(r=>r.code==="MODEL_BUDGET_EXHAUSTED")??results.find(r=>r.code!==null);
    return {run_id:results.find(r=>r.run_id)?.run_id??null,status:failure?.status??"completed",
      code:failure?.code??null,proposal_ids:results.flatMap(r=>r.proposal_ids)};
  }
}
