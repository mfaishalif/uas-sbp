import type { CaseDetail, CfpbCase } from '../src/generated/models/index.js';

const nullable: CfpbCase['subProduct'] = null;
const calendar: CfpbCase['receivedDate'] = '2024-11-12';
// @ts-expect-error required-nullable is not optional
const omitted: Pick<CfpbCase, 'subProduct'> = {};
// @ts-expect-error discriminator must reject unknown sources
const wrong: CaseDetail['source'] = 'OTHER';

export { nullable, calendar, omitted, wrong };
