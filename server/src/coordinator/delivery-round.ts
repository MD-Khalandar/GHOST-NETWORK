/** Only a requester-confirmed supply receipt permits another delivery from the same signed offer. */
export const deliveryRoundSql=(request:string,resource:string)=>`(SELECT COUNT(*)::integer FROM assistance_cases completed
  WHERE completed.request_id=${request} AND completed.resource_id=${resource} AND completed.resource_kind=2 AND completed.state=7)`;
