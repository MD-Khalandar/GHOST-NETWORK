import { CoordinatorStore } from "./store.js";
import { CoordinatorFailure, MAX_PROPOSALS, UUID, type MatchContext, type MatchDraft,
  type ProposalModel, type RunResult } from "./types.js";

function object(value: unknown, keys: string[]): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value) &&
    Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}

export function validateSuggestions(context: MatchContext, output: unknown): MatchDraft[] {
  const invalid = (): never => { throw new CoordinatorFailure("INVALID_MODEL_OUTPUT"); };
  if (!object(output, ["matches"]) || !Array.isArray(output.matches) || output.matches.length > MAX_PROPOSALS) return invalid();
  const seen = new Set<string>();
  return output.matches.map((draft: unknown) => {
    if (!object(draft, ["request_id", "resource_kind", "resource_id", "quantity", "explanation"]) ||
      draft.request_id !== context.request.request_id || !["helper", "offer"].includes(draft.resource_kind as string) ||
      typeof draft.resource_id !== "string" || typeof draft.explanation !== "string" ||
      !draft.explanation.trim() || draft.explanation.length > 1000) return invalid();
    const resource = context.resources.find(r => r.kind === draft.resource_kind && r.resource_id === draft.resource_id);
    const key = `${draft.resource_kind}:${draft.resource_id}`;
    if (!resource || seen.has(key)) return invalid();
    if (resource.kind === "helper" || resource.quantity === null ? draft.quantity !== null :
      !Number.isSafeInteger(draft.quantity) || (draft.quantity as number) <= 0 || (draft.quantity as number) > resource.quantity) return invalid();
    seen.add(key);
    return { request_id: context.request.request_id, resource_kind: resource.kind, resource_id: resource.resource_id,
      quantity: draft.quantity as number | null, explanation: draft.explanation.trim() };
  });
}

/** One bounded, on-demand run. No approval, reservation, signing or outbound delivery capability. */
export class CloudCoordinator {
  constructor(private readonly store: CoordinatorStore, private readonly model: ProposalModel,
    private readonly now: () => number = Date.now, private readonly maxCallsPerHour = 120) {
    if (!Number.isSafeInteger(maxCallsPerHour) || maxCallsPerHour < 1 || maxCallsPerHour > 1000000) throw new Error("Invalid AI call budget");
  }

  async run(requestId: string, leaseOwner?: string): Promise<RunResult> {
    if (!UUID.test(requestId)) return { run_id: null, status: "error", code: "REQUEST_UNAVAILABLE", proposal_ids: [] };
    let runId: string | null = null;
    try {
      runId = await this.store.start(requestId, this.model.name, this.now());
      if (!this.model.available) throw new CoordinatorFailure("MODEL_UNAVAILABLE");
      const context = await this.store.context(requestId, this.now());
      await this.store.recordCandidates(runId, context.resources.length);
      let suggestions: unknown = { matches: [] };
      if (context.resources.length) {
        if (!await this.store.reserveModelCall(this.now(), this.maxCallsPerHour)) throw new CoordinatorFailure("MODEL_BUDGET_EXHAUSTED");
        try { suggestions = await this.model.suggest(context); }
        catch (error) {
          throw error instanceof CoordinatorFailure ? error : new CoordinatorFailure("MODEL_ERROR");
        }
      }
      const drafts = validateSuggestions(context, suggestions);
      const ids = await this.store.save(runId, context, drafts, this.now(), leaseOwner);
      return { run_id: runId, status: "completed", code: null, proposal_ids: ids };
    } catch (error) {
      const code = error instanceof CoordinatorFailure ? error.code : "STORAGE_UNAVAILABLE";
      const status = code === "MODEL_UNAVAILABLE" ? "unavailable" : "error";
      if (runId) {
        try { await this.store.fail(runId, status, code, this.now()); }
        catch { return { run_id: runId, status: "error", code: "STORAGE_UNAVAILABLE", proposal_ids: [] }; }
      }
      return { run_id: runId, status, code, proposal_ids: [] };
    }
  }
}
