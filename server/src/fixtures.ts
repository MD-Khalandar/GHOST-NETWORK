export interface Cp01FixtureInput {
  requestId: string;
  offerId: string;
  category: "WATER";
  coarseArea: string;
}

/**
 * Stable, non-secret input for CP01 fake-provider tests. The complete shared
 * contract fixture remains owned by A under contracts/fixtures.
 */
export const cp01FixtureInput: Cp01FixtureInput = Object.freeze({
  requestId: "11111111-1111-4111-8111-111111111111",
  offerId: "22222222-2222-4222-8222-222222222222",
  category: "WATER",
  coarseArea: "Sector 4",
});
