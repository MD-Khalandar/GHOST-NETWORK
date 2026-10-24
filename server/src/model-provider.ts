import type { ServerConfig } from "./config.js";
import type { Cp01FixtureInput } from "./fixtures.js";

export interface ModelDecision {
  schema_version: 1;
  action: "finish";
  arguments: Record<string, never>;
  reason: string;
}

export interface ModelProvider {
  readonly providerName: string;
  readonly model: string;
  decide(input: Cp01FixtureInput): Promise<ModelDecision>;
}

/**
 * Deterministic CP01 seam. It deliberately does not select a resource, call a
 * server tool, publish a proposal, or contact a model service.
 */
export class FakeModelProvider implements ModelProvider {
  readonly providerName = "fake";

  constructor(readonly model: string) {}

  async decide(_input: Cp01FixtureInput): Promise<ModelDecision> {
    return {
      schema_version: 1,
      action: "finish",
      arguments: {},
      reason: "CP01 fake provider; no coordination executed.",
    };
  }
}

/**
 * Configuration-only adapter for the selected OpenAI provider. CP01 does not
 * make network calls; later checkpoints will add the bounded tool loop.
 */
export class OpenAiModelProvider implements ModelProvider {
  readonly providerName = "openai";

  constructor(
    readonly model: string,
    private readonly apiKey: string,
  ) {
    void apiKey;
  }

  async decide(_input: Cp01FixtureInput): Promise<ModelDecision> {
    throw new Error("OpenAI provider calls are disabled in CP01; use the fake provider for local tests");
  }
}

export function createModelProvider(config: ServerConfig): ModelProvider {
  if (config.modelProvider === "fake") {
    return new FakeModelProvider(config.model);
  }
  if (!config.openAiApiKey) {
    throw new Error("OpenAI provider configuration is missing GHOST_OPENAI_API_KEY");
  }
  return new OpenAiModelProvider(config.model, config.openAiApiKey);
}
