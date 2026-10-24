import { CoordinatorFailure, type MatchContext, type ProposalModel } from "./types.js";
import { instructions, readModelResponse, suggestionTool, type ModelOptions } from "./model-common.js";

/** Google's documented Chat Completions compatibility endpoint; credentials stay server-side. */
export class GeminiProposalModel implements ProposalModel {
  readonly available: boolean;
  readonly name: string | null;
  private readonly apiKey: string;
  private readonly transport: typeof fetch;
  constructor(options: ModelOptions = {}) {
    this.apiKey = options.apiKey?.trim() ?? "";
    this.name = options.model?.trim() || null;
    this.available = !!this.apiKey && !!this.name;
    this.transport = options.fetch ?? fetch;
  }
  async suggest(context: MatchContext): Promise<unknown> {
    if (!this.available) throw new CoordinatorFailure("MODEL_UNAVAILABLE");
    try {
      const { name, description, parameters, strict } = suggestionTool;
      const response = await this.transport("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", {
        method: "POST", redirect: "error", signal: AbortSignal.timeout(30000),
        headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({ model: this.name,
          messages: [{ role: "system", content: instructions }, { role: "user", content: JSON.stringify({ untrusted_records: context }) }],
          tools: [{ type: "function", function: { name, description, parameters, strict } }],
          tool_choice: { type: "function", function: { name } }, parallel_tool_calls: false, max_tokens: 6000 }),
      });
      const body = await readModelResponse(response);
      const choice = body.choices?.[0];
      if (body.choices?.length !== 1 || !["tool_calls", "stop"].includes(choice?.finish_reason) || choice.message?.refusal)
        throw new CoordinatorFailure("MODEL_ERROR");
      const calls = choice.message?.tool_calls;
      if (!Array.isArray(calls) || calls.length !== 1 || calls[0].type !== "function" ||
          calls[0].function?.name !== name || typeof calls[0].function.arguments !== "string")
        throw new CoordinatorFailure("INVALID_MODEL_OUTPUT");
      try { return JSON.parse(calls[0].function.arguments); }
      catch { throw new CoordinatorFailure("INVALID_MODEL_OUTPUT"); }
    } catch (error) {
      throw error instanceof CoordinatorFailure ? error : new CoordinatorFailure("MODEL_ERROR");
    }
  }
}
