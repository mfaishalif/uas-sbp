import { EditCasePage } from '@/components/cases/EditCasePage';

export default async function EditCaseRoute({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <EditCasePage id={id} />;
}
