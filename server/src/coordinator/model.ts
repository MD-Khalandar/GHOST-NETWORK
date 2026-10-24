import type { ServerConfig } from "../config.js";
import { GeminiProposalModel } from "./gemini-model.js";
import { OpenAIProposalModel } from "./openai-model.js";

export function createProposalModel(config: ServerConfig) {
  return config.aiProvider === "gemini"
    ? new GeminiProposalModel({ apiKey: config.geminiApiKey, model: config.coordinatorModel })
    : new OpenAIProposalModel({ apiKey: config.openaiApiKey, model: config.coordinatorModel });
}
