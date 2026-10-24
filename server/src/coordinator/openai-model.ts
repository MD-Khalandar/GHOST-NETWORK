import { instructions, suggestionTool, readModelResponse } from "./model-common.js";
import { CoordinatorFailure, type MatchContext, type ProposalModel } from "./types.js";

export interface OpenAIModelOptions { apiKey?: string; model?: string; fetch?: typeof fetch }

/** Native server-side HTTPS; no SDK/framework, generated SQL, tool dispatch or raw provider errors. */
export class OpenAIProposalModel implements ProposalModel {
  readonly available: boolean;
  readonly name: string | null;
  private readonly apiKey: string;
  private readonly transport: typeof fetch;

  constructor(options: OpenAIModelOptions = {}) {
    this.apiKey = options.apiKey?.trim() ?? "";
    this.name = options.model?.trim() || null;
    this.available = !!this.apiKey && !!this.name;
    this.transport = options.fetch ?? fetch;
  }

  async suggest(context: MatchContext): Promise<unknown> {
    if (!this.available) throw new CoordinatorFailure("MODEL_UNAVAILABLE");
    try {
      const response = await this.transport("https://api.openai.com/v1/responses", {
        method: "POST", redirect: "error", signal: AbortSignal.timeout(30000),
        headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({ model: this.name, store: false, instructions,
          input: [{ role: "user", content: JSON.stringify({ untrusted_records: context }) }],
          tools: [suggestionTool], tool_choice: { type: "function", name: "suggest_matches" },
          parallel_tool_calls: false, max_output_tokens: 6000 }),
      });
      const body = await readModelResponse(response);
      if (body.status !== "completed" || !Array.isArray(body.output)) throw new CoordinatorFailure("MODEL_ERROR");
      const calls = body.output.filter((item: any) => item.type === "function_call");
      if (calls.length !== 1 || calls[0].name !== "suggest_matches" || typeof calls[0].arguments !== "string" ||
        body.output.some((item: any) => !["function_call", "reasoning"].includes(item.type)))
        throw new CoordinatorFailure("INVALID_MODEL_OUTPUT");
      try { return JSON.parse(calls[0].arguments); }
      catch { throw new CoordinatorFailure("INVALID_MODEL_OUTPUT"); }
    } catch (error) {
      throw error instanceof CoordinatorFailure ? error : new CoordinatorFailure("MODEL_ERROR");
    }
  }
}
